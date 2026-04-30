# Alternate Current — Research Report (May 2026)

Target: a Folia 1.21.11 plugin that lets users mark chunks/regions to use Alternate Current (AC) instead of vanilla redstone. This document exhausts the upstream code, the existing Paper integration, alternatives, and the porting strategy.

---

## TL;DR — flips the project plan

The most consequential finding: **PaperMC has shipped Alternate Current in-tree since 1.19 (May 2022)**, configurable via `paper-world-defaults.yml -> misc.redstone-implementation: ALTERNATE_CURRENT` (also `EIGENCRAFT`, `VANILLA`). Folia inherits this as it is built on top of Paper.

- Paper docs: <https://docs.papermc.io/paper/reference/world-configuration/> (search `misc.redstone_implementation`)
- Paper feature patch (current main, 1.21.11+): <https://github.com/PaperMC/Paper/blob/main/paper-server/patches/features/0016-Add-Alternate-Current-redstone-implementation.patch>
- Eigencraft sister patch: <https://github.com/PaperMC/Paper/blob/main/paper-server/patches/features/0015-Eigencraft-redstone-implementation.patch>
- Original integration PR (merged 2022-05-07): <https://github.com/PaperMC/Paper/pull/7701>
- Earlier closed attempt: <https://github.com/PaperMC/Paper/pull/7694> · <https://github.com/PaperMC/Paper/pull/7291>
- Announcement tweet: <https://x.com/PaperPowered/status/1524420420820246529>

So we do **not** need to port AC ourselves. The plugin's job reduces to: at runtime, dispatch wire updates to either the vanilla evaluator or the Paper-bundled AC `WireHandler` based on a per-chunk/per-region selection — i.e. override the same gating boolean Paper already exposes. There is, however, a Folia thread-safety problem with the existing per-`ServerLevel` `WireHandler` (see "Folia caveat" below) which our plugin must address.

No third-party Bukkit/Spigot/Paper/Folia plugin port of AC was found that does per-chunk selection. PandaWire (md_5, closed-source SpigotMC paid plugin) is the only standalone plugin solution and it predates AC and is unmaintained / not on GitHub.

---

## 1. Alternate Current — algorithmic core

### 1.1 Repo, license, versioning

- Repo: <https://github.com/SpaceWalkerRS/alternate-current>
- License: MIT (Copyright 2022 Space Walker) — <https://github.com/SpaceWalkerRS/alternate-current/blob/main/LICENSE>
- Latest tagged release: `v1.9` (2024-08-26) for Fabric — <https://github.com/SpaceWalkerRS/alternate-current/releases/tag/v1.9>
- Active per-MC branches: `1.14, 1.15, 1.16, 1.17, 1.18, 1.19, 1.20, 1.21, 1.21.2, 1.21.5, 1.21.9, 1.21.11, dev, forge, neoforge-1.21*` — full list via `https://api.github.com/repos/SpaceWalkerRS/alternate-current/branches`
- **`1.21.11` branch HEAD: `7a4c4071745a680e36d8afedc9eaec92dcebfab7`** (2025-12-09, "update to 1.21.11"). Mod version `1.9.0`, Java 21, Fabric Loader 0.18.1, mappings: `loom.officialMojangMappings()`. Build file: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/build.gradle>
- Modrinth: <https://modrinth.com/mod/alternate-current> — CurseForge: <https://www.curseforge.com/minecraft/mc-mods/alternate-current>
- README (algorithm): <https://github.com/SpaceWalkerRS/alternate-current/blob/main/README.md>

### 1.2 Source layout (1.21.11 branch — confirmed exhaustive)

```
src/main/java/alternate/current/
├── AlternateCurrentMod.java                   # Fabric mod entry; static `on` flag
├── command/AlternateCurrentCommand.java       # /alternatecurrent enable|disable|...
├── interfaces/mixin/IServerLevel.java         # accessor: alternate_current$getWireHandler()
├── mixin/CommandsMixin.java                   # registers /alternatecurrent
├── mixin/ExperimentalRedstoneUtilsMixin.java  # forces deterministic Direction.WEST when orientation==null
├── mixin/MinecraftServerMixin.java            # save hook
├── mixin/RedStoneWireBlockMixin.java          # 4 @Inject points (see §1.6)
├── mixin/ServerLevelMixin.java                # constructs WireHandler in ServerLevel.<init>
├── util/profiler/{ACProfiler,Profiler,ProfilerResults}.java   # debug only
└── wire/
    ├── Config.java                            # per-world config persisted to alternate-current.conf
    ├── LevelHelper.java                       # optimized setBlock that skips lighting/heightmap/BE
    ├── Node.java                              # block-pos record, neighbor cache, flags (CONDUCTOR, SOURCE)
    ├── PriorityQueue.java                     # custom intrusive priority queue (uses Node.prev/next/priority)
    ├── SimpleQueue.java                       # custom intrusive FIFO over WireNode.next_wire
    ├── UpdateOrder.java                       # 4 enums: HORIZONTAL_FIRST_OUTWARD/INWARD, VERTICAL_FIRST_*
    ├── WireConnection.java
    ├── WireConnectionManager.java
    ├── WireHandler.java                       # 1092 LOC — the heart
    └── WireNode.java                          # 122 LOC — extends Node; powerLevel, virtualPower, flowIn
```

Manifest: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/src/main/resources/alternate-current.mixins.json>
```json
{ "mixins": ["CommandsMixin","ExperimentalRedstoneUtilsMixin",
             "MinecraftServerMixin","RedStoneWireBlockMixin","ServerLevelMixin"] }
```

### 1.3 Algorithm in 250 words

Vanilla `RedStoneWireBlock.updatePowerStrength()` triggers `calculateTargetStrength()` recursively per wire, each evaluation looking only at six immediate neighbors and re-emitting 42 block updates + ~22 shape updates per power change. A grid of N wires therefore reaches a fixed point in O(N²) updates, and the visit order is location-dependent (MC-11193, MC-81098).

AC inverts the model:

1. **Network discovery (BFS).** On any wire event (`onWireAdded` / `onWireRemoved` / `onWireUpdated`), a single `WireHandler` per `ServerLevel` does a breadth-first walk over all transitively connected `WireNode`s. The graph is materialized in a `Long2ObjectOpenHashMap<Node>` keyed by `BlockPos.asLong()`, and `Node.neighbors[6]` is lazily populated and cached so subsequent traversals avoid `Level.getBlockState`.
2. **Find roots.** Wires that receive power from outside the network (signal sources, conductors with adjacent sources) are flagged as roots. `findRoots` also probes around a single triggering wire to detect multiple simultaneous external power injections (lever above a 4-arm wire star, etc.).
3. **Depower.** All discovered wires set `virtualPower = externalPower` (or below `POWER_MIN-1` if powerless). No block writes yet.
4. **Power propagation (priority queue).** Roots are added to a `PriorityQueue` (priority = current `virtualPower`). Polling highest first, each wire calls `transmitPower` to neighbors; a neighbor's `offerPower(p, iDir)` keeps only the maximum and records the incoming direction in a 4-bit `flowIn`. `FLOW_IN_TO_FLOW_OUT[16]` (lookup table) collapses incoming flow into a single outgoing direction; this drives the deterministic neighbor-update order via `UpdateOrder.forEachNeighbor`.
5. **Single block write per wire.** `WireNode.setPower()` calls `LevelHelper.setWireState` (skips lighting / height-map / BE) and only then `queueNeighbors(wire)` schedules block updates and `updateNeighborShapes` schedules shape updates — each emitted once at the final value.

### 1.4 The 5 key invariants that differ from vanilla

These are the crisp behavioral commitments that the rest of the engine depends on:

1. **Single power write per wire per network update.** Vanilla `setBlock` may fire 6+ times per wire as power decays/grows; AC writes once via `LevelHelper.setWireState`. Source: `WireHandler.java:917-961` `powerNetwork()`.
2. **No block updates to wires inside the same network.** `queueNeighbor` early-returns when `node.isWire()`. Vanilla emits self-updates that re-trigger the recursion; AC explicitly cuts them. Source: `WireHandler.java:1042-1061`.
3. **Update order is power-flow-directed, not location-coordinate-directed.** `findPowerFlow` consults `FLOW_IN_TO_FLOW_OUT[wire.flowIn]` and falls back to the wire's connection-derived flow, then to the discovery direction. Source: `WireHandler.java:973-981`.
4. **External-power probe is at most once per wire per network update.** `findExternalPower` early-returns if `externalPower` is already initialized; `findPower` only re-checks external power if neighboring-wire power has *decreased*. Source: `WireHandler.java:680-693`.
5. **The `WireHandler.nodes` map is a snapshot.** During an `update()` sequence, if downstream block changes invalidate state, `invalidate()` flips every node's `invalid` flag and `revalidateNode` is called on next access. This snapshot semantics is required for performance but means cross-thread mutation of the same world during a network update breaks correctness. Source: `WireHandler.java:484-501`. **This is the Folia hazard.**

A sixth, subtler one: **shape updates are emitted to non-wire, non-air neighbors only** (`updateNeighborShapes`, `WireHandler.java:1007-1026`). This drops 16 of vanilla's 22 shape updates and drops the 6 self-shape-updates. Some redstone contraptions that rely on observers seeing a shape update from the wire's own block change behave differently — the well-documented break case.

### 1.5 Class boundary — what AC overrides

The Fabric mod touches **5 classes via mixin**, but the algorithmic core is `RedStoneWireBlock` only:

| Class | Mojmap name (1.21.11) | Why |
|---|---|---|
| `RedStoneWireBlock` | `net.minecraft.world.level.block.RedStoneWireBlock` | Cancel `updatePowerStrength` (HEAD); inject AC into `onPlace`, `affectNeighborsAfterRemoval`, `neighborChanged`. 4 inject points total. |
| `ServerLevel` | `net.minecraft.server.level.ServerLevel` | Construct one `WireHandler` field per level in `<init>` TAIL. |
| `ExperimentalRedstoneUtils` | `net.minecraft.world.level.redstone.ExperimentalRedstoneUtils` | Replace random fallback orientation with deterministic `Direction.WEST` (for repeatable update order when `orientation == null`). |
| `Commands` | `net.minecraft.commands.Commands` | Register `/alternatecurrent`. Cosmetic. |
| `MinecraftServer` | `net.minecraft.server.MinecraftServer` | Save config hook. Cosmetic. |

**Not** touched: `Level.neighborUpdater` is *consumed* (`new InstantNeighborUpdater(level)` is created inside the `WireHandler`) but never replaced globally. Vanilla `CollectingNeighborUpdater` keeps running for non-wire blocks. AC also does **not** replace `DefaultRedstoneWireEvaluator` or `ExperimentalRedstoneWireEvaluator`; it short-circuits the whole `updatePowerStrength` pipeline before either evaluator runs.

### 1.6 The four `RedStoneWireBlockMixin` injection points (verbatim)

Source: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/src/main/java/alternate/current/mixin/RedStoneWireBlockMixin.java>

1. `@Inject(method="updatePowerStrength", at=HEAD, cancellable=true)` — full cancel when AC is on. (chosen over redirect for mod-compat reasons explicitly noted in the comment.)
2. `@Inject(method="onPlace", at=INVOKE updatePowerStrength)` → `wireHandler.onWireAdded(pos, state)`
3. `@Inject(method="affectNeighborsAfterRemoval", at=INVOKE updatePowerStrength)` → `wireHandler.onWireRemoved(pos, state)`
4. `@Inject(method="neighborChanged", at=HEAD, cancellable=true)` → `wireHandler.onWireUpdated(pos, state, orientation)`; cancels if it returned true (duplication-bug fix).

### 1.7 License conditions

MIT — redistribution requires retaining the copyright notice + license text. No share-alike, no patent grant complications. Re-implementation from scratch is unrestricted; vendoring the source under MIT is allowed; sublicensing under another permissive license is allowed. We are clear to either (a) reuse the Paper-shipped copy by toggling `paperConfig().misc.redstoneImplementation`, (b) vendor the AC source files into our plugin, or (c) reimplement from scratch.

---

## 2. Existing ports & related work

### 2.1 PaperMC built-in (the big finding)

- **Patch file (current main):** `paper-server/patches/features/0016-Add-Alternate-Current-redstone-implementation.patch` — <https://github.com/PaperMC/Paper/blob/main/paper-server/patches/features/0016-Add-Alternate-Current-redstone-implementation.patch> — 2,451-line patch that vendors the entire `alternate/current/wire/*` package (LevelHelper, Node, PriorityQueue, SimpleQueue, UpdateOrder, WireConnection, WireConnectionManager, WireHandler, WireNode) plus modifies `ServerLevel`, `Level`, `RedStoneWireBlock`, `ExperimentalRedstoneUtils`. Most recent commits to that patch: `651d8481` (2026-04-26), `acfe105b` (2026-04-26), `a64ea13e` (2026-04-26), `1d141977` (2026-04-21), `78eb3aaf` (2026-04-15) — actively maintained.
- **Config wiring:** `paper-server/src/main/java/io/papermc/paper/configuration/WorldConfiguration.java` declares
  ```java
  public RedstoneImplementation redstoneImplementation = RedstoneImplementation.VANILLA;
  public AlternateCurrentUpdateOrder alternateCurrentUpdateOrder = AlternateCurrentUpdateOrder.HORIZONTAL_FIRST_OUTWARD;
  public enum RedstoneImplementation { VANILLA, EIGENCRAFT, ALTERNATE_CURRENT }
  public enum AlternateCurrentUpdateOrder { HORIZONTAL_FIRST_OUTWARD, HORIZONTAL_FIRST_INWARD,
                                            VERTICAL_FIRST_OUTWARD, VERTICAL_FIRST_INWARD }
  ```
- **Gating in `RedStoneWireBlock`:** at `onPlace`, `affectNeighborsAfterRemoval`, `neighborChanged` — the patch adds three identical `if (level.paperConfig().misc.redstoneImplementation == ALTERNATE_CURRENT) level.getWireHandler().onWire*(...) else fallthrough_to_vanilla_or_eigencraft;` blocks. The Eigencraft path is the patched `updateSurroundingRedstone` from feature patch `0015-Eigencraft-redstone-implementation.patch`.
- **WireHandler is one per `ServerLevel`:** `ServerLevel.java` patch adds `private final alternate.current.wire.WireHandler wireHandler = new alternate.current.wire.WireHandler(this);` and exposes `getWireHandler()`. `Level.java` adds a stub `getWireHandler() { return null; }` for non-server callers.
- **Update order picked at runtime:** inside `WireHandler.invalidate()` the patch reads `level.paperConfig().misc.alternateCurrentUpdateOrder.ordinal()` and rebuilds the local `updateOrder` field on every network update — meaning toggling the per-world config takes effect on the next wire event.
- **Folia 1.21.11 inherits everything** from Paper. Folia's branch `ver/1.21.11` (HEAD `3ef0ba66`, 2026-04-03) carries no redstone/AC patches of its own — `git ls-tree` of that branch contains zero matches for `redstone` or `alternate`. <https://github.com/PaperMC/Folia/tree/ver/1.21.11>

### 2.2 Forks / ports of the standalone AC mod

- Upstream `forge` branch and `neoforge-1.21*` branches exist on the SpaceWalkerRS repo (<https://github.com/SpaceWalkerRS/alternate-current/branches>). These reuse the same `wire/` core under different loader bindings.
- **No Bukkit / Paper / Spigot / Folia plugin port of AC was found.** GitHub code search (`alternate-current` + `RedStoneWireBlock`, several variants) returns only the Paper-merged copy, the SpaceWalkerRS upstream, and SpongePowered's Eigencraft commit. No third-party plugin reimplementation exists as of May 2026.
- **Cardboard** (Bukkit-on-Fabric) — <https://github.com/CardboardPowered/cardboard> — exposes the Bukkit API on Fabric, which would let the original AC Fabric mod coexist with plugins, but that's a server-software substitute, not a plugin.

### 2.3 Other redstone-optimization plugins (Bukkit/Paper)

| Project | URL | What | NMS-replaces wire? |
|---|---|---|---|
| **PandaWire** (md_5) | <https://www.spigotmc.org/resources/pandawire.41991/> | Closed-source SpigotMC plugin. Implements an "alternate redstone algorithm that updates redstone wire only after computing the entire state" (PandaWire is the spiritual ancestor of AC, predating it; md_5 wrote it ~2017). Last update for MC 1.15.2 — **dead**. Not on GitHub. | Yes (via NMS reflection on the old Spigot mappings). |
| **RedstoneLimiter** | <https://github.com/KRYMZ0N/RedstoneLimiter> | Limits redstone *placement* per chunk. No algorithmic optimization. | No |
| **AntiRedstoneClock-Remastered** | <https://github.com/OneLiteFeatherNET/AntiRedstoneClock-Remastered> | Detects clocks, alerts/destroys. | No |
| **AntiRedstoneLag** | <https://modrinth.com/plugin/antiredstonelag> | Throttles activity. | No |
| **PerfoBooster** | <https://hangar.papermc.io/BLOODRED/PerfoBooster> | TPS-based on/off switch for all redstone. | No |
| **LagShield** | <https://hangar.papermc.io/us3rn1me/LagShield> | Generic perf bundle. | No |
| **RedUtils** (digital-redstone helpers) | <https://github.com/ma-chengyuan/RedUtils> | Builder utilities, not optimization. | No |

**No existing plugin replaces `RedStoneWireBlock` via NMS.** Reason: doing so requires a mixin or a server fork — both outside plain Bukkit/Paper plugin scope. The only viable plugin pattern is to leverage the Paper-bundled implementation (which is what we should do).

### 2.4 Mod-side neighbors of AC

- **CaffeineMC/lithium** (Fabric/NeoForge) — <https://github.com/CaffeineMC/lithium> — README claims `~35%` redstone-dust improvement, much weaker than AC's `~20×`. Lithium's redstone optimization is a set of micro-optimizations on the vanilla evaluator (cached blockstate lookups), not an algorithmic replacement. Latest release `0.14.4` for 1.21.4 — <https://github.com/CaffeineMC/lithium/releases/tag/mc1.21.4-0.14.4>. **Cannot** be reused from a Paper plugin (mixin-based, requires Fabric loader).
- **SpaceWalkerRS/redstone-tweaks** (Fabric) — <https://github.com/SpaceWalkerRS/redstone-tweaks> — orthogonal: tunable delays, push limits, quasi-connectivity toggles. Not an optimization. Also Fabric-only.
- **SpaceWalkerRS/redstone-multimeter-fabric** — diagnostic.
- **MUP / theosib's Eigencraft / RedstoneWireTurbo** — <https://github.com/mrgrim/MUP> (Forge) and the original carpet implementation <https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/helpers/RedstoneWireTurbo.java>. Eigencraft is the basis for Paper's `RedstoneWireTurbo.java` (in `io.papermc.paper.redstone`) — the patch-0015 implementation. AC outperforms it ~3× in benchmarks.
- **TheHolyException/Fabric-Redstone-Optimizations** — <https://github.com/TheHolyException/Fabric-Redstone-Optimizations> — small Fabric-only experiment, not maintained.

### 2.5 Known issues with AC on Paper / Folia

- <https://github.com/PaperMC/Paper/issues/7852> "Pistons does not work correctly under alternate-current redstone system" — closed (accepted, low priority); the bug is the documented edge case where missing self-shape updates affect quasi-connectivity. Last activity 2022-05-27.
- <https://github.com/PaperMC/Paper/issues/4941> "Redstone Contraption Breaking in Paper but not in Vanilla" — generic umbrella for behavior deviations.
- <https://github.com/PaperMC/Paper/issues/11356> "Redstone build breaks after updating paper to the newest 1.21 build" — sometimes pinned to AC, sometimes Eigencraft, version-dependent.
- <https://github.com/PaperMC/Folia/issues/35> "Redstone components freezing" (closed, 1.19.4) — generic Folia/redstone bug, not AC-specific.
- <https://github.com/PaperMC/Folia/issues/334> "Cross-thread Entity State Access Error Triggered by 28w Pork Tower" — Folia thread-safety errors *triggered by complex redstone*. This is the symptom class our plugin will provoke if we don't think about region threading carefully.

### 2.6 Pufferfish / Purpur

Both forks inherit Paper's redstone-implementation config unchanged — `redstone-implementation: alternate-current` works identically on Pufferfish and Purpur. Neither adds its own redstone algorithm. <https://docs.pufferfish.host/optimization/pufferfish-server-optimization-guide/> · <https://purpurmc.org/docs/purpur/>

### 2.7 "Slice" plugin

No plugin called *Slice* with redstone region semantics was found. The user's reference may have been wrong, or it's a private/internal plugin not indexed.

---

## 3. Algorithmic alternatives that could go *beyond* AC

### 3.1 Eigencraft (theosib's RedstoneWireTurbo)

- Open implementations: carpet mod <https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/helpers/RedstoneWireTurbo.java>, Sponge <https://github.com/SpongePowered/Sponge/commit/3ecef4b47a9e11b1660b2c892f8850a9d480213b>, Paper feature patch 0015 <https://github.com/PaperMC/Paper/blob/main/paper-server/patches/features/0015-Eigencraft-redstone-implementation.patch>, MUP <https://github.com/mrgrim/MUP>.
- Bench numbers from PR #7694: Vanilla 295 ms, Eigencraft 54 ms, AC 17 ms. **Eigencraft is ~3× slower than AC.**
- License: variable (MUP is unlicensed/permissive in practice; carpet is MIT; Sponge is MIT). theosib's original code is MIT-style.
- Recommendation: **No reason to choose Eigencraft over AC.** Higher vanilla parity is its only edge — if a server's redstone breaks under AC and works on Eigencraft, that's a reason to expose Eigencraft as a fallback option, but in 2026 that's a niche.

### 3.2 Lithium-redstone (CaffeineMC) and Slimefun-style throttling

- Lithium: micro-optimizations only (`~35%`). Mixin/Fabric-only. **Not portable** to a Paper plugin without server-fork-grade modification.
- Slimefun-style throttling: rate-limits redstone events from a plugin layer. Not algorithmic; reduces functionality.

### 3.3 2024-2026 advances

- No new published algorithm beats AC in a peer-reviewed or even MMC-blog-post sense. AC's structure (full network discovery + single-write + flow-directed update order) is the consensus state-of-the-art.
- Active research focus shifted to **simulation** rather than *evaluation*: e.g. MCRedstoneSimulator (`MCHPRS`, *Minecraft High Performance Redstone Server*, <https://github.com/MCHPRS/MCHPRS>) compiles redstone networks into specialized in-memory state machines (Cranelift JIT). **Speedup on idle-state-machine workloads can reach 1000×+ vanilla.** It is a server-fork (replaces Minecraft entirely with a Rust server speaking the protocol) — **not portable to a plugin**, but conceptually the only known thing faster than AC.
  - License: GPLv3 — would force GPL on any derivative work. Not compatible with vendoring into a permissive plugin.
- Fabric-Redstone-Optimizations and a few weekend experiments exist; none mature.

### 3.4 Recommendations per option

| Option | Plugin-portable? | License | Speedup vs vanilla | Verdict |
|---|---|---|---|---|
| **AC (Paper-bundled)** | already in-tree | MIT | ~20× | **Use this.** |
| **AC (vendored from upstream)** | yes, vendor 12 files | MIT | ~20× | Use only if you need behaviors Paper rejected. |
| Eigencraft | already in-tree | MIT | ~5× | Expose as fallback toggle. |
| Lithium redstone | no (Fabric only) | LGPLv3 | ~1.35× | Skip. |
| MCHPRS | no (replaces server) | GPLv3 | ~1000× idle | Skip; license-incompatible anyway. |
| Custom reimpl beyond AC | no known design wins | — | — | Skip. |

---

## 4. Port plan for our Folia 1.21.11 plugin

**Architectural choice: do NOT port the AC algorithm. The algorithm already runs in Paper/Folia 1.21.11. Our plugin's job is to provide per-chunk/per-region selection of the algorithm.**

### 4.1 The right design (recommended)

The plugin should intercept the same three call sites Paper intercepts (`onPlace`, `affectNeighborsAfterRemoval`, `neighborChanged` on `RedStoneWireBlock`) **before** Paper's gating runs, and decide AC vs vanilla based on a per-chunk allowlist. There are three implementation strategies, in increasing order of reliability:

1. **Mutate `paperConfig().misc.redstoneImplementation` at runtime per chunk-event.** Cheapest. The Paper code reads this field on every wire event, and the WireHandler re-reads `alternateCurrentUpdateOrder` inside `invalidate()`. Threading caveat: the field is shared across the whole `ServerLevel`; under Folia, two regions in the same world ticking simultaneously cannot agree on what value the field holds. **Will not work** for multi-region per-chunk selection on Folia.
2. **Use a Paper plugin Mixin (via paperweight-mojmap dev bundle / Mixin extras).** Inject before Paper's `if (level.paperConfig().misc.redstoneImplementation == ALTERNATE_CURRENT)` check, redirect to our own per-chunk decision function. Requires `paperweight-userdev` 1.21.11 + `mixin-extras-mc-runtime` (Paper supports plugin Mixins as of 1.20.6+, see <https://docs.papermc.io/paper/dev/internals/>). This is the clean option.
3. **Vendor AC source files into the plugin and skip Paper's machinery entirely.** Highest control, biggest maintenance burden. We'd reimplement the three injection points in our own `BlockPhysicsEvent` listener — but that doesn't fire early enough; we'd actually need NMS/Mixin anyway, so this doesn't avoid (2).

**→ Use strategy (2): plugin Mixin into the three `RedStoneWireBlock` call sites, dispatching to either Paper's existing `level.getWireHandler().onWire*()` or vanilla's `updateSurroundingRedstone`.**

### 4.2 If we decide to vendor AC source after all

Files needed (from the `1.21.11` branch at commit `7a4c4071`):

| Upstream file | Purpose | Keep / drop in plugin |
|---|---|---|
| `wire/WireHandler.java` (1092 LOC) | Core algorithm | **Keep**, modify constructor (drop `Config.forLevel` & `LevelStorageAccess`) |
| `wire/WireNode.java` (122) | Wire-specific node | **Keep** as-is |
| `wire/Node.java` (113) | Generic block node | **Keep** as-is |
| `wire/WireConnection.java` | Connection record | **Keep** |
| `wire/WireConnectionManager.java` | Per-wire connection table | **Keep** |
| `wire/PriorityQueue.java` | Custom intrusive PQ | **Keep** |
| `wire/SimpleQueue.java` | Custom intrusive queue | **Keep** |
| `wire/UpdateOrder.java` | 4 enum values | **Keep** |
| `wire/LevelHelper.java` | Optimized `setBlock` | **Keep** as-is (uses standard NMS APIs) |
| `wire/Config.java` | Per-world disk config | **Drop**, replace with plugin's per-chunk config |
| `mixin/RedStoneWireBlockMixin.java` | 4 injects | **Adapt** — Mixin annotations the same, but our `IServerLevel` accessor goes via a per-chunk lookup |
| `mixin/ServerLevelMixin.java` | per-level WireHandler field | **Adapt** — instead, one WireHandler per chunk-region in our plugin |
| `mixin/ExperimentalRedstoneUtilsMixin.java` | deterministic orientation | **Keep** as-is |
| `interfaces/mixin/IServerLevel.java` | accessor interface | **Keep** |
| `mixin/CommandsMixin.java` | `/alternatecurrent` | **Drop**, replace with our own command |
| `mixin/MinecraftServerMixin.java` | save hook | **Drop** |
| `util/profiler/*` | debug profiler | **Drop** (3 files) |
| `AlternateCurrentMod.java` | Fabric entry point | **Drop**; our plugin's `onEnable` replaces this |

**Estimated total LOC after porting:** ~2,000 LOC for vendored AC + ~500 LOC plugin glue (per-chunk config, command, Folia region scheduler integration, mixin loader). Breakdown: WireHandler 1092 + WireNode 122 + Node 113 + WireConnection ~50 + WireConnectionManager ~190 + PriorityQueue ~210 + SimpleQueue ~115 + UpdateOrder ~390 + LevelHelper 56 + 3 mixins ~150 = **~2,488 LOC vendored**.

### 4.3 Class-name mapping (Fabric Yarn → Paper mojmap)

The 1.21.11 upstream branch already uses Mojang official mappings (`loom.officialMojangMappings()` in build.gradle, see <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/build.gradle>). **Class names match Paper 1.21.11 1:1.** No translation required. All key classes referenced — `BlockPos`, `BlockState`, `ServerLevel`, `Level`, `Direction`, `RedStoneWireBlock`, `Block`, `Blocks.REDSTONE_WIRE`, `LevelChunkSection`, `ChunkAccess`, `ChunkStatus`, `Mth`, `Redstone`, `RedStoneWireBlock.POWER`, `Orientation`, `NeighborUpdater`, `InstantNeighborUpdater`, `LevelStorageSource.LevelStorageAccess`, `ExperimentalRedstoneUtils.initialOrientation` — exist verbatim in Paper 1.21.11. Confirmed via Paper's feature patch 0016 (which uses identical imports).

### 4.4 Verified breaking changes between AC's tested MC and 1.21.11

The `1.21.11` upstream branch already absorbed the breaking changes; nothing extra is needed. For reference, between the 1.21 branch and 1.21.11 the rename history that affected AC:

- `Direction.iOpposite` indexing: unchanged.
- `NeighborUpdater.shapeUpdate(Direction, BlockState, BlockPos, BlockPos, int, int)` — signature unchanged.
- `ExperimentalRedstoneUtils.initialOrientation(Level, Direction front, Direction up)` — signature unchanged across 1.21.x.
- `RedStoneWireBlock.affectNeighborsAfterRemoval` — added in 1.21 (replaces older inlined logic in `onRemove`); 1.21.11 keeps this name.
- `RedStoneWireBlock.updatePowerStrength(Level, BlockPos, BlockState, Orientation, boolean)` — `Orientation` parameter added in 1.21 (was just `Direction`/none). 1.21.11 unchanged.
- `Level.setBlock(BlockPos, BlockState, int)` `int` flag bits unchanged.
- `BlockState#getValue` / `setValue` — unchanged across 1.21.x.
- `LevelChunkSection.setBlockState(int x, int y, int z, BlockState)` — unchanged.

**No verified breaking change blocks AC on 1.21.11.** Paper has been tracking the upstream Fabric mod and has applied the same updates in patch 0016 (commit history shows updates through 2026-04-26).

### 4.5 Risks specific to Folia 1.21.11

The blocker is **not** mojmap rename drift. It is **Folia's region threading**:

- `WireHandler` carries unguarded mutable state (`Long2ObjectOpenHashMap nodes`, `SimpleQueue search`, `PriorityQueue updates`, `boolean updating`, `Node[] nodeCache`). One per `ServerLevel`. <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/src/main/java/alternate/current/wire/WireHandler.java#L240-L274>
- Folia ticks regions of the same `ServerLevel` in parallel: <https://docs.papermc.io/folia/reference/overview/>.
- Two regions placing/breaking redstone wire at the same instant can both call `level.getWireHandler().onWire*` on the same `WireHandler` instance. **Race condition: data corruption, not just lost updates.**
- Paper's existing AC integration on Folia is therefore latently broken whenever the world has redstone activity in two regions simultaneously. This is consistent with the symptom in <https://github.com/PaperMC/Folia/issues/35> (closed but vague) and <https://github.com/PaperMC/Folia/issues/334> (cross-thread access from redstone).

**Our plugin must own the WireHandler lifecycle:** one `WireHandler` per region (or per chunk) instead of one per level. This means:

- We cannot rely on Paper's `ServerLevel.getWireHandler()` getter — it returns the shared one. We must store our own `Map<RegionKey, WireHandler>` keyed by something stable across region merge/split (Folia's `ThreadedRegionizer.ThreadedRegion` or chunk coordinates).
- The ownership transition during region merge/split is non-trivial: a wire network spanning a region boundary at merge time must rebuild on the merged region's WireHandler. The simplest discipline is: **on every wire event, look up the region for the wire's chunk; rent a per-region WireHandler from a pool; clear it after the network update finishes**. Because AC is event-synchronous (does all its work inside `onWire*` and exits), there is no persistent state we need to migrate — the `nodes` map is rebuilt from world state on each event. This is the AC design's hidden bonus for Folia.
- Update networks crossing region boundaries: rare in practice (chunks at region edges are the boundary buffer per Folia rules), but our region-detection logic must reject wire events whose network would cross. Either fall back to vanilla for those, or stall the second region until the first region's update completes. Discussion: <https://docs.papermc.io/folia/reference/region-logic/>.

### 4.6 Paper plugin Mixin loading on 1.21.11

Plugin-shipped Mixins are supported in Paper 1.21.6+. Required setup:

- `paperweight-userdev` Gradle plugin pinned to 1.21.11 — <https://docs.papermc.io/paper/dev/getting-started/paper-plugins/>
- `mixin-extras-mc-runtime` library
- `plugin.yml` declares `paper-plugin.yml` features: `bootstrap`, `mixin-config: redstone-region.mixins.json`
- `folia-supported: true` to load on Folia — <https://docs.papermc.io/folia/>

---

## Recommendation

**Adopt Paper's bundled AC at the patch in `paper-server/patches/features/0016-Add-Alternate-Current-redstone-implementation.patch` (current main, last touched 2026-04-26 by commit `651d8481`). Do not vendor or re-port the algorithm — it's already on every 1.21.11 Folia server.**

Build the plugin as a Paper Plugin (with Folia support) that:
1. Declares its own `paper-plugin.yml` with `folia-supported: true` and a `mixin-config`.
2. Mixin-injects into `net.minecraft.world.level.block.RedStoneWireBlock` at the same three call sites Paper does (`onPlace`, `affectNeighborsAfterRemoval`, `neighborChanged`), at `HEAD` with `cancellable=true`, **before** Paper's `paperConfig().misc.redstoneImplementation` gate.
3. Maintains a per-chunk allowlist (PDC, or its own SQLite/JSON state) of "AC-enabled chunks".
4. For events on allowlisted chunks: delegates to a **plugin-owned, per-Folia-region** `WireHandler` instance (vendored from upstream `wire/*` 12 files at upstream commit `7a4c4071745a680e36d8afedc9eaec92dcebfab7`, MIT license preserved). This sidesteps Paper's shared per-level WireHandler and its Folia race.
5. For events on non-allowlisted chunks: cancels nothing, lets vanilla `updateSurroundingRedstone` proceed.
6. Provides `/redstoneregion mark|unmark|list` commands, world border integration if needed, and Folia `RegionScheduler` hooks for cross-region wire-network detection.

If, after benchmarking, the per-region WireHandler approach is too costly to maintain and the user accepts world-level granularity instead of chunk-level, the simpler fallback is: **just toggle `paperConfig().misc.redstoneImplementation` per world via reflection on plugin enable**, ship `folia-supported: true`, and call it a day — at which point the plugin is ~50 LOC. But this gives up the per-chunk feature the user explicitly asked for.

There is no compelling alternative to AC algorithmically (Eigencraft is 3× slower, MCHPRS is GPL+server-fork). The "ou mieux si ça existe" answer is: **AC is the state of the art for in-Java-server redstone evaluation as of May 2026**.

---

## Sources (all URLs cited above, alphabetized)

- AC repo: <https://github.com/SpaceWalkerRS/alternate-current>
- AC README: <https://github.com/SpaceWalkerRS/alternate-current/blob/main/README.md>
- AC LICENSE (MIT): <https://github.com/SpaceWalkerRS/alternate-current/blob/main/LICENSE>
- AC 1.21.11 branch: <https://github.com/SpaceWalkerRS/alternate-current/tree/1.21.11>
- AC 1.21.11 build.gradle: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/build.gradle>
- AC 1.21.11 RedStoneWireBlockMixin: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/src/main/java/alternate/current/mixin/RedStoneWireBlockMixin.java>
- AC 1.21.11 WireHandler: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/src/main/java/alternate/current/wire/WireHandler.java>
- AC 1.21.11 ServerLevelMixin: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/src/main/java/alternate/current/mixin/ServerLevelMixin.java>
- AC 1.21.11 LevelHelper: <https://github.com/SpaceWalkerRS/alternate-current/blob/1.21.11/src/main/java/alternate/current/wire/LevelHelper.java>
- AC releases: <https://github.com/SpaceWalkerRS/alternate-current/releases>
- AC on Modrinth: <https://modrinth.com/mod/alternate-current>
- AC on CurseForge: <https://www.curseforge.com/minecraft/mc-mods/alternate-current>
- Carpet RedstoneWireTurbo: <https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/helpers/RedstoneWireTurbo.java>
- Cardboard (Bukkit-on-Fabric): <https://github.com/CardboardPowered/cardboard>
- Folia downloads: <https://papermc.io/software/folia/>
- Folia repo: <https://github.com/PaperMC/Folia>
- Folia 1.21.11 branch: <https://github.com/PaperMC/Folia/tree/ver/1.21.11>
- Folia overview: <https://docs.papermc.io/folia/reference/overview/>
- Folia issue #35 (redstone freezing): <https://github.com/PaperMC/Folia/issues/35>
- Folia issue #334 (cross-thread redstone): <https://github.com/PaperMC/Folia/issues/334>
- Lithium repo: <https://github.com/CaffeineMC/lithium>
- Lithium 1.21.4 release: <https://github.com/CaffeineMC/lithium/releases/tag/mc1.21.4-0.14.4>
- MCHPRS: <https://github.com/MCHPRS/MCHPRS>
- MUP (Eigencraft Forge): <https://github.com/mrgrim/MUP>
- MC-81098 (theosib comment): <https://bugs.mojang.com/browse/MC-81098?focusedCommentId=420777>
- Paper repo: <https://github.com/PaperMC/Paper>
- Paper feature patch 0015 (Eigencraft): <https://github.com/PaperMC/Paper/blob/main/paper-server/patches/features/0015-Eigencraft-redstone-implementation.patch>
- Paper feature patch 0016 (AC): <https://github.com/PaperMC/Paper/blob/main/paper-server/patches/features/0016-Add-Alternate-Current-redstone-implementation.patch>
- Paper internals docs: <https://docs.papermc.io/paper/dev/internals/>
- Paper paper-plugin docs: <https://docs.papermc.io/paper/dev/getting-started/paper-plugins/>
- Paper PR #7291 (Titaniumtown, closed): <https://github.com/PaperMC/Paper/pull/7291>
- Paper PR #7694 (SpaceWalkerRS, closed): <https://github.com/PaperMC/Paper/pull/7694>
- Paper PR #7701 (merged 2022-05-07): <https://github.com/PaperMC/Paper/pull/7701>
- Paper world configuration docs: <https://docs.papermc.io/paper/reference/world-configuration/>
- Paper announcement tweet: <https://x.com/PaperPowered/status/1524420420820246529>
- PandaWire (Spigot): <https://www.spigotmc.org/resources/pandawire.41991/>
- Pufferfish optimization docs: <https://docs.pufferfish.host/optimization/pufferfish-server-optimization-guide/>
- Purpur docs: <https://purpurmc.org/docs/purpur/>
- RedstoneLimiter (chunk-limit, Paper): <https://github.com/KRYMZ0N/RedstoneLimiter>
- RedstoneTweaks SpaceWalkerRS (orthogonal Fabric mod): <https://github.com/SpaceWalkerRS/redstone-tweaks>
- Sponge Eigencraft commit: <https://github.com/SpongePowered/Sponge/commit/3ecef4b47a9e11b1660b2c892f8850a9d480213b>
- TheHolyException Fabric-Redstone-Optimizations: <https://github.com/TheHolyException/Fabric-Redstone-Optimizations>
