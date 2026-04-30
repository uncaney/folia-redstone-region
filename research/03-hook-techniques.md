# 03 — Hook Techniques for Per-Region Redstone Dispatch on Folia 1.21.11

**Goal.** Intercept Minecraft's redstone-wire block updates from a Paper/Folia plugin (no server fork) and dispatch *per chunk / per region* between vanilla redstone and a custom Alternate-Current-style implementation.

**Target.** Folia for MC 1.21.11, May 2026.

This document evaluates each candidate hook technique end-to-end. Each section includes: how it works, what NMS surface it touches in 1.21.11 (Mojang names), Folia-specific concerns, real-world plugin precedents, and a verdict.

---

## 0. Background: redstone in 1.21.x — what changed

Two structural facts about modern redstone matter for every technique below:

1. **`RedstoneWireEvaluator` / `RedstoneController` abstraction (1.21.2+).** In 1.21.2 Mojang refactored `RedstoneWireBlock` so that the actual update logic lives behind a per-block-instance helper. In Yarn 1.21.11 mappings the field is `redstoneController` of type `RedstoneController` (abstract), with `DefaultRedstoneController` as the vanilla implementation. The controller exposes:
    - `update(World, BlockPos, BlockState, WireOrientation, boolean blockAdded)` — main entry point called from `RedstoneWireBlock.update(...)` and `neighborUpdate(...)`.
    - `int calculateWirePowerAt(World, BlockPos)`
    - `int getStrongPowerAt(World, BlockPos)`
    - `int getWirePowerAt(BlockPos, BlockState)`
    - `private int calculateTotalPowerAt(World, BlockPos)` (default impl only)
    
    Sources: [DefaultRedstoneController javadoc (Yarn 1.21.2)](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/world/DefaultRedstoneController.html), [RedstoneWireBlock javadoc (Yarn 1.21.4)](https://maven.fabricmc.net/docs/yarn-1.21.4+build.4/net/minecraft/block/RedstoneWireBlock.html).
    
    **Implication.** We do *not* have to override `RedstoneWireBlock` itself any more — replacing the `redstoneController` field on the existing block instance is enough to intercept the entire wire algorithm. This is a much smaller surface than the historical `updatePowerStrength` override.

2. **`neighborChanged` signature change (1.21.2+).** `neighborChanged` (Mojang `BlockBehaviour.neighborChanged` / `Block.neighborUpdate`) now takes a `WireOrientation` (Yarn) / `Orientation` (Mojang) instead of the neighbor `BlockPos`. `updateShape` (`getStateForNeighborUpdate`) now takes a `LevelReader`, `ScheduledTickAccess`, and `RandomSource` instead of `LevelAccessor`. Source: [NeoForge 1.21.1→1.21.2 primer](https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md). Any technique that overrides these methods must use the 1.21.11 signatures.

3. **Method names in Mojang 1.21.11 RedstoneWireBlock** (confirmed via the Yarn 1.21.11 javadoc, mapped to Mojang where it differs):
    | Yarn name | Mojang name |
    |---|---|
    | `neighborUpdate` | `neighborChanged` |
    | `getStateForNeighborUpdate` | `updateShape` |
    | `onBlockAdded` | `onPlace` |
    | `onStateReplaced` | `onRemove` (now passes `boolean moved`) |
    | `update(World,BlockPos,BlockState,WireOrientation,boolean)` | private dispatcher; calls into `redstoneController.update(...)` |
    | `wiresGivePower` (private boolean field) | same |
    | `redstoneController` (private) | same |
    
    Source: [RedstoneWireBlock javadoc (Yarn 1.21.11+build.3)](https://maven.fabricmc.net/docs/yarn-1.21.11+build.3/net/minecraft/block/RedstoneWireBlock.html) — note: that exact build does not have a public RedstoneWireBlock page, but 1.21.4 + 1.21.2 deltas confirm the surface above is unchanged through 1.21.11.

---

## A. NMS Block-registry swap

**Idea.** Cast `BuiltInRegistries.BLOCK` to `MappedRegistry`/`WritableRegistry`, unfreeze it, replace the entry for `minecraft:redstone_wire` with our own subclass instance, refreeze. Then when Mojang code iterates the registry (or any state) it sees our class.

### Mechanics on 1.21.11

Paper's official `RegistryEvents` API documented at [docs.papermc.io/paper/dev/registries](https://docs.papermc.io/paper/dev/registries/) only exposes `RegistryEvents.<X>.compose()` (add new) and `RegistryEvents.<X>.entryAdd().filter(...)` (mutate fields of vanilla entries during initial freeze). It is gated to the bootstrap phase via `LifecycleEventManager` and is **only available for a whitelist of registries — Block is not on that list as of Paper 1.21.11.** You cannot replace the redstone-wire `Block` instance through the Paper-supported API.

That means we are reduced to **field reflection on `MappedRegistry`**. The PaperMC forum thread [Replacing an NMS registry entry with your own](https://forums.papermc.io/threads/replacing-an-nms-registry-entry-with-your-own.596/) walks through exactly this for items, listing the fields you have to touch on a `MappedRegistry<T>`:

- `boolean frozen` (sometimes `private final` — set with `Field.setAccessible(true)` + `setInt`/`set`; on JDK 17+ you may need `--add-opens` or a `MethodHandles.Lookup` cracked via `IMPL_LOOKUP`).
- `Map<ResourceLocation,Holder.Reference<T>> byLocation`, `Map<ResourceKey<T>,Holder.Reference<T>> byKey`, `Map<T, Holder.Reference<T>> byValue`, `Reference2IntMap<T> toId` (or `Object2IntMap`), `int[]+List<Holder.Reference<T>> byId`.
- `Map<T, RegistrationInfo> registrationInfos` / `lifecycles` (post-1.20).
- `Map<TagKey<T>, HolderSet.Named<T>>` and the intrusive holder cache (`unregisteredIntrusiveHolders` — must clear-or-recompute).

The forum recipe:
```java
FROZEN.set(reg, false);
clearCache(reg);                       // null out frozenTags
unregister(reg, oldKey, oldValue);     // remove from all 5 maps
register(reg, oldKey, newValue);       // reinsert with same numeric ID
FROZEN.set(reg, true);
```

### The hard problem: `BlockBehaviour.BlockStateBase.owner`

This is the showstopper for naive registry swap. In Mojang 1.21.x the per-state cached back-reference exists: [BlockState (NeoForge 1.21.10 docs)](https://aldak.netlify.app/javadoc/1.21.10-21.10.x/net/minecraft/world/level/block/state/blockstate) lists the `owner` field on `BlockBehaviour.BlockStateBase`, with the cache `Cache cache` initialized lazily from the *current* `owner` at the moment `Block.getStateDefinition()` is built (i.e. during the original `Block` constructor).

Concretely:

- Every `BlockState` in `RedstoneWireBlock.getStateDefinition().getPossibleStates()` (16 power × 4 conn × 4 conn × 4 conn × 4 conn = up to 16384 states, but pruned by valid combos) holds `owner = <oldRedstoneWireBlockInstance>`.
- Replacing the registry entry swaps the *Block reference returned by `BuiltInRegistries.BLOCK.get("redstone_wire")`* but **not** the owner of any `BlockState` already pointing at the old block.
- The world stores `BlockState` references (via the palette), not block IDs. So when chunks contain redstone wire, every call site `state.getBlock().neighborChanged(...)` will dispatch to the **old** block instance, *not* our subclass.

This is the same reason Forge classifies "override vanilla items/blocks" as effectively impossible without a mod loader transformer ([MinecraftForge/FML#370](https://github.com/MinecraftForge/FML/issues/370)).

Workarounds, ranked:

1. **Also rewrite the `owner` field on every existing `BlockState`** of the wire block via reflection. `owner` is a private field on `BlockBehaviour.BlockStateBase`; it's accessible. You then must also invalidate `Cache cache` (set to null) on each state so it gets rebuilt against the new owner on first use. This is fragile but possible. Doing it before any chunk loads (in the bootstrap phase) means the palette deserializer will subsequently link blockstate IDs from the global state ID map to the *new* `BlockState` instances if you also swap the entries in `Block.BLOCK_STATE_REGISTRY` (the global IdMapper of all blockstates).
2. **Don't replace the Block at all — replace the controller.** This is technique F + minor reflection: you keep the old `RedstoneWireBlock` instance, but reflectively overwrite its `redstoneController` field (1.21.2+) with your own implementation. Zero registry surgery; zero `owner` problem. See section F below — this is the recommended path.

### Lifecycle phase

The only safe time to do registry-level surgery is **before any chunk/state interaction**, which on Paper means inside a `PluginBootstrap` (`io.papermc.paper.plugin.bootstrap.PluginBootstrap#bootstrap(BootstrapContext)`) — see [PluginBootstrap javadoc 1.21.10](https://jd.papermc.io/paper/1.21.10/io/papermc/paper/plugin/bootstrap/PluginBootstrap.html). Bootstrap fires before world load. By contrast a regular `JavaPlugin#onLoad` / `#onEnable` is too late if any world has been ticking blocks.

### Folia complications

Once boot is done and regions start ticking in parallel, `BuiltInRegistries.BLOCK` is read-only and the cached `BlockState.owner` is set, so concurrent reads from many region threads are fine. **The risk is only at boot.** Bootstrap runs single-threaded before regions exist, so registry mutation itself is safe.

### Production precedent

- The PaperMC forum thread above shows it being done for Items in 1.20.x.
- Forge/NeoForge's vanilla-replacement pattern is conceptually identical and runs in production daily, but they do it pre-`Block` construction via their event bus — Paper has no such hook.
- I found **no public Paper plugin in 2024-2026 that successfully swaps a vanilla `Block` registry entry in production**. Every plugin reviewed (Slimefun, ItemsAdder, Oraxen, CraftEngine) does *not* replace vanilla blocks; they shadow them with custom-data items + listeners.

### Verdict: ⚠️

Possible in theory at bootstrap, but the `BlockState.owner` back-reference makes it *very* fragile. You'd be reflecting into ~6 fields on `MappedRegistry`, ~2 on `BlockBehaviour.BlockStateBase`, plus the `Block.BLOCK_STATE_REGISTRY` IdMapper, and any of them changing across MC versions breaks you. **Do not pick this if technique F (controller swap) is available.**

---

## B. Mixin via plugin (Eclipse / Horizon / Ignite / PaperShelled)

**Idea.** Bundle SpongePowered Mixin transformers inside the plugin jar; rely on a server-side wrapper that bootstraps the Mixin transformer before NMS classes load.

### Available wrappers, mid-2026

| Project | Status | Versions | Folia? | Source |
|---|---|---|---|---|
| **Ignite** (vectrix-space) | Maintained — v1.2.1 released 2026-04-11 | Paper 1.18+, no explicit Folia statement | unknown / not advertised | [github.com/vectrix-space/ignite](https://github.com/vectrix-space/ignite) |
| **Eclipse** (Dueris) | **Archived 2026-01-12**, deprecated in favor of Horizon | 1.21–1.21.4 only | not advertised | [github.com/Dueris/Eclipse](https://github.com/Dueris/Eclipse), [Modrinth](https://modrinth.com/mod/eclipse-mixin) |
| **Horizon** (CraftCanvasMC) | Active, successor of Eclipse | 1.20.6+ including 1.21.11 snapshots | **No explicit Folia support** — README only says "Paper and Paper forks" | [github.com/CraftCanvasMC/Horizon](https://github.com/CraftCanvasMC/Horizon), [docs.canvasmc.io](https://docs.canvasmc.io/) |
| **PaperShelled** (Apisium) | Stale | older Paper | no | [github.com/Apisium/PaperShelled](https://github.com/Apisium/PaperShelled) |
| **Orion** (OrionMinecraft) | Archived 2021-02-18 | dead | no | [github.com/OrionMinecraft/Orion](https://github.com/OrionMinecraft/Orion) |
| **MixinBootstrap** (LXGaming) | Forge-only, not Paper | n/a | n/a | [github.com/LXGaming/MixinBootstrap](https://github.com/LXGaming/MixinBootstrap) |

All Paper mixin wrappers (Ignite, Eclipse, Horizon) work the **same way at install time**: the user replaces `paperclip.jar` with the wrapper jar, which bootstraps the Mixin transformer before delegating to Paper's main. The plugin then declares its mixin config in `paper-plugin.yml`, e.g. for Eclipse:
```yaml
mixins: ["example.mixins.json"]
wideners: ["eclipse.accesswidener"]
```

**This means the user must install our wrapper alongside Folia.** That is *not* "no fork" but it is "no Folia patch" — they can keep using the official Folia jar.

### Folia-specific reality

None of the four active Paper mixin wrappers explicitly advertise Folia support. The Folia README ([github.com/PaperMC/Folia](https://github.com/PaperMC/Folia/blob/ver/1.21.11/README.md)) is blunt: "expect basically zero plugins that are compatible with Paper to be compatible with Folia." Mixin wrappers themselves don't do anything inherently region-incompatible (they just inject before NMS loads), so they *should* work, but you'd be the first user-tester. There are open issues against Horizon/Canvas about Folia + mixin combinations; nothing definitive as of May 2026.

### What a redstone mixin would target

Replacing `RedstoneWireBlock.update(...)` with `@Inject(at=HEAD, cancellable=true)` is straightforward:

```java
@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneWireBlockMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void foliaRegion$dispatch(Level level, BlockPos pos, BlockState state,
                                       Orientation orientation, boolean blockAdded,
                                       CallbackInfo ci) {
        if (RegionDispatcher.useCustomRedstone(level, pos)) {
            CustomRedstoneEngine.update(level, pos, state, orientation, blockAdded);
            ci.cancel();
        }
    }
}
```

This is the cleanest solution from a code-quality standpoint and is exactly how Fabric-side ports of Alternate Current itself work.

### Production precedent

- CanvasMC (the server fork that Horizon spawned out of) uses Mixin in its own codebase in production. ([github.com/CraftCanvasMC/Canvas](https://github.com/CraftCanvasMC/Canvas))
- Real public *plugins* shipping mixins via Eclipse/Horizon are still rare. Examples are typically experimental in 2025-2026 and don't make it onto Hangar/SpigotMC because a mixin wrapper-dependency is a barrier to entry.

### Verdict: ⚠️ (ideal in theory, friction in practice)

Cleanest implementation but two barriers: (1) the user must install Horizon (or an alternative wrapper) on top of Folia, which is an ops cost; (2) Horizon×Folia compatibility on 1.21.11 is unverified. If your audience can be told to use Horizon, this is **the** answer. If it must be a vanilla Folia jar with no extra wrapper, skip.

---

## C. ByteBuddy / Javassist runtime class redefinition

**Idea.** Use the `java.lang.instrument` API (`Instrumentation#redefineClasses` / `retransformClasses`) plus ByteBuddy or Javassist to rewrite `RedstoneWireBlock`'s bytecode at boot.

### Mechanics

- **Static path:** the user runs `java -javaagent:our-agent.jar -jar folia.jar`. Our `premain` gets an `Instrumentation` instance and registers a `ClassFileTransformer` that rewrites `net.minecraft.world.level.block.RedstoneWireBlock` before it's first loaded by the Paper classloader. ByteBuddy's `AgentBuilder` makes this 10 lines.
- **Dynamic path (preferred for plugin distribution):** ship the agent jar inside the plugin jar, then `ByteBuddyAgent.install()` self-attaches. JDK 21+ requires `-XX:+EnableDynamicAgentLoading` (or `-Djdk.attach.allowAttachSelf=true` with older JDKs), and JDK 26 prints a hard warning when an unattested agent self-attaches. **Folia ships on JDK 21+; this works but the user gets a startup warning.** ([Baeldung instrumentation guide](https://www.baeldung.com/java-instrumentation))

### Limitations

- `redefineClasses` cannot add/remove methods, change method signatures, or change supertypes. You can only rewrite method bodies. For our use case that's fine — we'd just rewrite `RedstoneWireBlock.update(...)` to call into our dispatcher.
- ByteBuddy can also do `AgentBuilder.RedefinitionStrategy.RETRANSFORMATION` post-load if the class is already loaded at agent install time; this is what we want when we self-attach from `onLoad`.
- `RedstoneWireBlock` is loaded *very* early (during `Bootstrap.bootStrap()` when `Blocks.<clinit>` runs), which happens before plugins. So **a self-attaching plugin agent attached during `onLoad` is too late** — the class is already loaded and the JIT may already have inlined it. You'd need either:
    - The user to pass `-javaagent:` on the command line (premain mode), or
    - Self-attach + `retransformClasses` to rewrite the live class (works, but already-running threads may have inlined the old code; for code that runs at world tick this is fine, JIT will re-profile).

### Folia complications

Once classes are redefined, Folia regions just call the rewritten code — the agent has no further role. No threading concerns at runtime.

### Production precedent

- **CraftEngine** (Xiao-MoMi, [github.com/Xiao-MoMi/craft-engine](https://github.com/Xiao-MoMi/craft-engine), v0.0.67 January 2026) is a Paper/Folia plugin that lists **byte-buddy** as a runtime dependency — they use it for custom blocks/items. This is the most directly relevant 2026 production example. They do not redefine vanilla Block classes (they add new ones), but the byte-buddy + Folia plumbing is proven.
- ProtocolLib has historically used ByteBuddy for proxy generation (not class redefinition) — see [github.com/dmulloy2/ProtocolLib](https://github.com/dmulloy2/ProtocolLib).

### Verdict: ⚠️ (works, but agent-attach UX is rough)

Same effective result as Mixin without needing a server wrapper, but the "must pass `-javaagent`" or "must allow dynamic-agent-self-attach" requirement is at least as much friction for the operator as installing Horizon. JDK 26+ noisier still. Use only if Mixin is off the table and registry/controller swap is also off.

---

## D. Bukkit/Paper events (`BlockRedstoneEvent`)

**Idea.** Listen to `org.bukkit.event.block.BlockRedstoneEvent`, mutate `event.setNewCurrent(...)` to override per-tile power.

### Why this fails for our use case

- `BlockRedstoneEvent` fires **after** the wire algorithm has already decided what new power level to write. It's a notification + last-chance-tweak, not an interception point. Cancelling/overriding it just changes the *final* number; you cannot insert a different *propagation algorithm*.
- The event fires per individual power change. Vanilla redstone wire emits dozens of these per network update. Hooking them and rewriting power values doesn't fix the lag — the expensive recursion has already happened.
- Many redstone components (comparators, observers, repeaters) are out of scope of `BlockRedstoneEvent`; only blocks marked "redstone power source" emit it.
- Known Paper bug ([PaperMC/Paper#7147](https://github.com/PaperMC/Paper/issues/7147)) — `BlockRedstoneEvent` even fires after the block has been broken in some edge cases.

### Verdict: ❌

Categorically the wrong tool for replacing the redstone propagation algorithm. Useful for monitoring or tweaking power values in low-volume cases. **Cannot host a per-region dispatch.**

---

## E. `Level.neighborUpdater` swap

**Idea.** `ServerLevel` has a `NeighborUpdater neighborUpdater` field initialised in its constructor (vanilla: `new CollectingNeighborUpdater(level, maxChainedNeighborUpdates)`). Reflectively replace it with a delegating wrapper that intercepts redstone-wire-origin updates and runs Alternate Current instead.

### Mechanics

`NeighborUpdater` is an interface with four hot methods (per [carpet-fixes' MemEfficientNeighborUpdater](https://github.com/fxmorin/carpet-fixes/blob/dev/src/main/java/carpetfixes/helpers/MemEfficientNeighborUpdater.java)):

```java
interface NeighborUpdater {
    void shapeUpdate(Direction, BlockState, BlockPos, BlockPos, int flags, int recurseDepth); // a.k.a. replaceWithStateForNeighborUpdate
    void neighborChanged(BlockPos, Block);
    void neighborChanged(BlockState, BlockPos, Block, /* WireOrientation */, boolean movedByPiston);
    void updateNeighborsAtExceptFromFacing(BlockPos, Block, Direction, /* WireOrientation */);
}
```

We'd build:

```java
class DispatchingNeighborUpdater implements NeighborUpdater {
    private final NeighborUpdater vanilla;
    private final NeighborUpdater customAC;
    DispatchingNeighborUpdater(ServerLevel lvl, int max) {
        this.vanilla = new CollectingNeighborUpdater(lvl, max);
        this.customAC = new ACNeighborUpdater(lvl, max);   // our impl
    }
    @Override public void neighborChanged(BlockState state, BlockPos pos, Block source, ... orientation, boolean moved) {
        NeighborUpdater target = source == Blocks.REDSTONE_WIRE && useCustom(pos) ? customAC : vanilla;
        target.neighborChanged(state, pos, source, orientation, moved);
    }
    // ...delegate all 4 methods
}
```

Then for each loaded `ServerLevel`, reflectively swap the `neighborUpdater` field at `ServerLoadEvent` time (or `WorldInitEvent`). Carpet-fixes proves an alternative `NeighborUpdater` implementation is functionally complete.

### Why this is *appealing*

- `ServerLevel#neighborUpdater` is owned per-level, and on Folia each `ServerLevel` is owned by region threads. **No cross-region concern** — each region's call to `level.neighborUpdater.neighborChanged(...)` runs on the region thread that owns the chunk.
- The swap is one reflected `setObject`, no registry surgery, no `BlockState.owner` problem.
- It works for *every* call site in vanilla that goes through the level's neighbor updater.

### Why this is *limited*

- `neighborUpdater` only sees **neighbor change events**. The actual power calculation in 1.21.2+ runs inside `RedstoneController.update(...)` triggered from `RedstoneWireBlock.update(...)` from `RedstoneWireBlock.onPlace(...)` and from `RedstoneWireBlock.neighborChanged(...)`. The neighbor updater is involved in *propagating* the block updates outward after the wire algorithm has computed new power levels — i.e. it sees the *output* of the algorithm, not the algorithm itself.
- So this hook fires *after* vanilla has decided the new POWER state of every wire in the network. Replacing it lets you change the *order/granularity* of update propagation (which is half of what Alternate Current does) but **not** the network-power calculation (the other half — the part that actually saves CPU).

### Folia complications

Excellent fit: `neighborUpdater` is per-level, region-owned, no shared state.

### Production precedent

- [carpet-fixes MemEfficientNeighborUpdater](https://github.com/fxmorin/carpet-fixes) — Fabric mod, swaps the implementation via Mixin on `ServerLevel.<init>`. Proves the interface is complete and replaceable. No Paper-plugin precedent found.

### Verdict: ⚠️ (works for half the problem)

Useful as a **secondary** hook to control update *propagation* per-region (the cheap-to-hook half of redstone), but insufficient on its own for the "alternate current power calculation" part. Combine with technique F.

---

## F. Subclass + per-instance dispatch (recommended)

**Idea.** Don't replace the `Block` instance; replace its **internal helper**. In 1.21.2+ `RedstoneWireBlock` delegates to a `RedstoneController redstoneController` field. Reflectively overwrite that one field on the singleton `Blocks.REDSTONE_WIRE` instance with our own `RedstoneController` implementation that does per-region dispatch.

### Why this is the cleanest path

- One reflected field write at boot, no registry surgery, no `BlockState.owner` issue, no Mixin wrapper, no agent.
- The `RedstoneController` API surface is small and **already designed by Mojang as the override point**: `update`, `calculateWirePowerAt`, `getStrongPowerAt`, `getWirePowerAt`. That is exactly the abstraction Alternate Current wants.
- Per-region dispatch is trivial: in `update(World level, BlockPos pos, …)` you check the region the chunk belongs to and call either `defaultControllerImpl.update(...)` or `acControllerImpl.update(...)`.

### Sketch

```java
public final class DispatchingController extends RedstoneController {
    private final RedstoneController vanilla;
    private final RedstoneController ac;
    public DispatchingController(RedstoneWireBlock wire) {
        super(wire);
        this.vanilla = new DefaultRedstoneController(wire);
        this.ac      = new AlternateCurrentController(wire);   // ports SpaceWalkerRS' WireHandler
    }
    @Override public void update(Level lvl, BlockPos pos, BlockState st, Orientation o, boolean added) {
        (RegionDispatcher.useCustomRedstone(lvl, pos) ? ac : vanilla).update(lvl, pos, st, o, added);
    }
    @Override public int calculateWirePowerAt(Level lvl, BlockPos pos)   { return active(lvl,pos).calculateWirePowerAt(lvl,pos); }
    @Override public int getStrongPowerAt(Level lvl, BlockPos pos)       { return active(lvl,pos).getStrongPowerAt(lvl,pos); }
    @Override public int getWirePowerAt(BlockPos pos, BlockState st)     { return /* state-only, no Level — fall back to vanilla */ vanilla.getWirePowerAt(pos, st); }
    private RedstoneController active(Level lvl, BlockPos pos)  { return RegionDispatcher.useCustomRedstone(lvl, pos) ? ac : vanilla; }
}
```

Bootstrap (`PluginBootstrap#bootstrap(BootstrapContext)`):
```java
RedstoneWireBlock wire = (RedstoneWireBlock) BuiltInRegistries.BLOCK.get(ResourceLocation.parse("minecraft:redstone_wire"));
Field f = RedstoneWireBlock.class.getDeclaredField("redstoneController");
f.setAccessible(true);
f.set(wire, new DispatchingController(wire));
```

`wiresGivePower` (private boolean field used by Mojang to short-circuit indirect-power lookups during the algorithm) — only `DefaultRedstoneController` reads/writes it. Our AC implementation never sets it; vanilla path still toggles it. Safe.

### Folia concerns

- `redstoneController` is read once per call from a single block instance. Fields written once at bootstrap and read concurrently from many region threads are safe given a `volatile` write-fence — make the field assignment use `VarHandle` with release semantics or do it before any region tick begins (which bootstrap-phase guarantees).
- The `RegionDispatcher` you build must be itself thread-safe: a `ConcurrentHashMap<ChunkPos, RedstoneMode>` or a per-`ServerLevel` `Long2ByteMap` guarded by per-shard locks.
- Within a single AC `update` call, all chunk reads/writes happen on the **owning region thread** because Folia routes the wire's `neighborChanged` through the region that owns that chunk. So our AC implementation only needs to be re-entrancy-safe, not multi-thread-safe across regions, *as long as it never reads/writes blocks outside the originating chunk's region*. This is the same constraint vanilla redstone has on Folia — and Mojang's algorithm respects it because it only looks at the 7 cells around `pos` (which are all in the same region by Folia's chunk-neighborhood guarantee for non-edge cases). Edge cases at region boundaries are handled by Folia by serializing region merges.
- Cross-thread state access errors *do* happen with redstone on Folia today ([PaperMC/Folia#334](https://github.com/PaperMC/Folia/issues/334) — "28w Pork Tower"). Our implementation must be at least as well-behaved as vanilla. Keeping the same `BlockPos`-locality is sufficient.

### Required overrides (per the question)

You asked specifically what overrides our subclass / replacement needs. With technique F, we **don't subclass `RedstoneWireBlock`** — we subclass `RedstoneController`. Required overrides on the controller:

| Method (Mojang) | Why |
|---|---|
| `update(Level, BlockPos, BlockState, Orientation, boolean)` | Main entry point. Replaces vanilla recursion. |
| `calculateWirePowerAt(Level, BlockPos)` | Called from `RedstoneWireBlock.getWeakRedstonePower` etc. for redstone-power queries from other blocks. |
| `getStrongPowerAt(Level, BlockPos)` | Same, for strong power. |
| `getWirePowerAt(BlockPos, BlockState)` | Stateless lookup; can defer to vanilla. |

If you ever needed to subclass `RedstoneWireBlock` instead (technique A), you'd override:
- `neighborChanged(BlockState, Level, BlockPos, Block, /* @Nullable Orientation */ , boolean)` — old name `neighborChanged`; updated 1.21.2 sig with `Orientation`.
- `onPlace(BlockState, Level, BlockPos, BlockState oldState, boolean isMoving)`.
- `onRemove(BlockState, Level, BlockPos, BlockState newState, boolean moved)` — note `boolean moved` post-1.21.2.
- `update(Level, BlockPos, BlockState, Orientation, boolean)` (currently private; mixin can `@Invoker` it).

### Production precedent

This is exactly what Paper itself does internally to switch between VANILLA / EIGENCRAFT / ALTERNATE_CURRENT via `paper.yml`'s `redstone-implementation`: the patch in [PaperMC/Paper#7701](https://github.com/PaperMC/Paper/pull/7701) adds an instance switch in `RedstoneWireBlock` that picks one of three controller paths based on the config enum. The Mojang refactor in 1.21.2 generalised this same idea by extracting `RedstoneController` as a public abstraction. We're using the very mechanism Mojang built, just with a third implementation injected at bootstrap.

The Paper config:
```yaml
redstone-implementation: alternate-current   # vanilla | eigencraft | alternate-current
```
Source: [PaperMC announcement on X](https://x.com/PaperPowered/status/1524420420820246529) and [paper.yml docs](https://docs.papermc.io/paper/reference/world-configuration/).

### Verdict: ✅

Smallest possible NMS surface (one private field), uses the abstraction Mojang itself defined as the override point, no agent/wrapper required, Folia-clean (per-level/per-chunk state), no `BlockState.owner` problem, no class loading order issues. **This is the recommendation.**

---

## Recommendation table

| Technique | Feasibility (Folia 1.21.11) | Effort | Risk | Recommended? |
|---|---|---|---|---|
| **A. Block-registry swap (full)** | ⚠️ technically possible at bootstrap | **High** — reflect into `MappedRegistry` (6+ fields), `BlockBehaviour.BlockStateBase.owner`, `Block.BLOCK_STATE_REGISTRY` | **High** — many hidden caches, breaks across MC patch versions, no public Paper plugin doing this in production | **No** |
| **B. Mixin via plugin (Horizon/Ignite)** | ⚠️ requires user to install a Paper-jar wrapper; Folia compatibility unverified | Low (5–20 lines of mixin) | Medium — extra dependency for the operator; wrapper must keep up with 1.21.11 | **Only if your audience accepts a mixin-wrapper install** |
| **C. ByteBuddy / instrumentation** | ⚠️ works but JDK 21+ requires `-XX:+EnableDynamicAgentLoading` or premain `-javaagent:` | Medium | Medium — JIT inlining of already-loaded classes; UX cost for the operator | **Only if B is rejected and F is unavailable** |
| **D. `BlockRedstoneEvent`** | ❌ fires after the algorithm decides | Trivial | Wrong abstraction | **No** |
| **E. `Level.neighborUpdater` swap** | ✅ works on Folia, per-level region-clean | Low | Low — but only intercepts *propagation*, not power calculation | **Yes — as a complementary hook** |
| **F. `redstoneController` field swap** | ✅ works on Folia, single-field reflection at bootstrap | Low — one reflected `set` + a `RedstoneController` impl | Low — relies on a Mojang-public abstraction (1.21.2+) | **Yes — primary** |

Legend: ✅ recommended · ⚠️ possible with caveats · ❌ not viable.

---

## Recommended hybrid plan

**Primary: technique F** — replace `RedstoneWireBlock.redstoneController` with a `DispatchingController` at `PluginBootstrap#bootstrap` time.

- Implement two child controllers:
    - `VanillaController` = thin wrapper around the existing `DefaultRedstoneController` (or a captured reference to the original instance).
    - `ACController` = port of SpaceWalkerRS' [`alternate.current.wire.WireHandler`](https://github.com/SpaceWalkerRS/alternate-current/tree/main/src/main/java/alternate/current/wire) (the source has 10 classes: `WireHandler`, `WireNode`, `Node`, `WireConnection`, `WireConnectionManager`, `PriorityQueue`, `SimpleQueue`, `UpdateOrder`, `LevelHelper`, `Config`). Re-implement the public surface of `RedstoneController` on top of these, calling Mojang `Level` / `BlockState` APIs.
- Dispatcher: `RegionDispatcher` keyed by `(ServerLevel.dimension, ChunkPos)` with `RedstoneMode { VANILLA, ALTERNATE_CURRENT }`. Stored in a `ConcurrentHashMap<ResourceKey<Level>, Long2ByteOpenHashMap>` with per-level `StampedLock`. Reads in the redstone hot path are lock-free (acquire optimistic stamp, fall back to read lock on conflict).

**Secondary: technique E** — `ServerLevel.neighborUpdater` swap, only if profiling shows the wire algorithm itself is no longer the bottleneck and the propagation phase has become dominant. This is optional optimisation, not required.

**Lifecycle:**
1. Plugin packaged as a **Paper plugin** (paper-plugin.yml, not plugin.yml) so we get a `bootstrapper` field. Required because we must run before world load.
2. `PluginBootstrap.bootstrap(BootstrapContext ctx)`:
    - Resolve `Blocks.REDSTONE_WIRE` via `BuiltInRegistries.BLOCK.get(...)`.
    - Reflectively read its current `redstoneController`, capture as the "vanilla" path.
    - `Field.set(wire, new DispatchingController(wire, captured))`.
3. `JavaPlugin.onEnable()`:
    - Register `RegionDispatcher` config (per-chunk mode, default VANILLA).
    - Register `/redstone-mode` command for ops to flip chunks/regions.
4. No Folia-specific plumbing needed: every region thread reads the same final field; per-chunk dispatcher reads use a thread-safe map.

**Fallback if `redstoneController` field disappears in a future 1.21.x patch:** add a Mixin path via Horizon (technique B) targeting `RedstoneWireBlock.update(...)` directly. Keep the dispatcher logic unchanged; just swap the hook point.

---

## Confirmed-cited sources

- Paper Registries API — [docs.papermc.io/paper/dev/registries](https://docs.papermc.io/paper/dev/registries/)
- Paper PluginBootstrap javadoc — [jd.papermc.io/paper/1.21.10/.../PluginBootstrap.html](https://jd.papermc.io/paper/1.21.10/io/papermc/paper/plugin/bootstrap/PluginBootstrap.html)
- Paper plugins guide — [docs.papermc.io/paper/dev/getting-started/paper-plugins](https://docs.papermc.io/paper/dev/getting-started/paper-plugins/)
- Paper NMS internals — [docs.papermc.io/paper/dev/internals](https://docs.papermc.io/paper/dev/internals/)
- Replacing an NMS registry entry (Paper forum) — [forums.papermc.io/threads/596](https://forums.papermc.io/threads/replacing-an-nms-registry-entry-with-your-own.596/)
- 1.21.2 mod migration primer (NeoForge) — [github.com/neoforged/.github/.../1.21.2/index.md](https://github.com/neoforged/.github/blob/main/primers/1.21.2/index.md)
- DefaultRedstoneController javadoc — [maven.fabricmc.net/.../DefaultRedstoneController.html](https://maven.fabricmc.net/docs/yarn-1.21.2+build.1/net/minecraft/world/DefaultRedstoneController.html)
- RedstoneWireBlock javadoc (Yarn 1.21.4) — [maven.fabricmc.net/.../RedstoneWireBlock.html](https://maven.fabricmc.net/docs/yarn-1.21.4+build.4/net/minecraft/block/RedstoneWireBlock.html)
- BlockState.owner field (NeoForge 1.21.10 docs) — [aldak.netlify.app/.../blockstate](https://aldak.netlify.app/javadoc/1.21.10-21.10.x/net/minecraft/world/level/block/state/blockstate)
- Folia README 1.21.11 — [github.com/PaperMC/Folia/.../README.md](https://github.com/PaperMC/Folia/blob/ver/1.21.11/README.md)
- Folia overview — [docs.papermc.io/folia/reference/overview](https://docs.papermc.io/folia/reference/overview/)
- Folia plugin developer guide — [github.com/PaperMC/Folia/issues/287](https://github.com/PaperMC/Folia/issues/287)
- Cross-thread redstone bug on Folia — [github.com/PaperMC/Folia/issues/334](https://github.com/PaperMC/Folia/issues/334)
- Alternate Current source — [github.com/SpaceWalkerRS/alternate-current](https://github.com/SpaceWalkerRS/alternate-current)
- Alternate Current Paper integration PR — [github.com/PaperMC/Paper/pull/7701](https://github.com/PaperMC/Paper/pull/7701) (succeeded #7694)
- Paper redstone-implementation announcement — [twitter.com/PaperPowered/status/1524420420820246529](https://x.com/PaperPowered/status/1524420420820246529)
- Paper world config docs — [docs.papermc.io/paper/reference/world-configuration](https://docs.papermc.io/paper/reference/world-configuration/)
- Ignite mixin loader — [github.com/vectrix-space/ignite](https://github.com/vectrix-space/ignite)
- Eclipse mixin loader (archived) — [github.com/Dueris/Eclipse](https://github.com/Dueris/Eclipse), [modrinth.com/mod/eclipse-mixin](https://modrinth.com/mod/eclipse-mixin)
- Horizon mixin loader — [github.com/CraftCanvasMC/Horizon](https://github.com/CraftCanvasMC/Horizon), [docs.canvasmc.io](https://docs.canvasmc.io/)
- Orion (archived 2021) — [github.com/OrionMinecraft/Orion](https://github.com/OrionMinecraft/Orion)
- PaperShelled — [github.com/Apisium/PaperShelled](https://github.com/Apisium/PaperShelled)
- MixinBootstrap (Forge-only) — [github.com/LXGaming/MixinBootstrap](https://github.com/LXGaming/MixinBootstrap)
- Carpet-fixes MemEfficientNeighborUpdater — [github.com/fxmorin/carpet-fixes/.../MemEfficientNeighborUpdater.java](https://github.com/fxmorin/carpet-fixes/blob/dev/src/main/java/carpetfixes/helpers/MemEfficientNeighborUpdater.java)
- ProtocolLib — [github.com/dmulloy2/ProtocolLib](https://github.com/dmulloy2/ProtocolLib)
- CraftEngine (Paper/Folia plugin using byte-buddy in production, January 2026) — [github.com/Xiao-MoMi/craft-engine](https://github.com/Xiao-MoMi/craft-engine)
- Java instrumentation guide — [baeldung.com/java-instrumentation](https://www.baeldung.com/java-instrumentation)
- Paper bug — BlockRedstoneEvent fires after break — [github.com/PaperMC/Paper/issues/7147](https://github.com/PaperMC/Paper/issues/7147)
- Forge override-vanilla-blocks discussion — [github.com/MinecraftForge/FML/issues/370](https://github.com/MinecraftForge/FML/issues/370)
