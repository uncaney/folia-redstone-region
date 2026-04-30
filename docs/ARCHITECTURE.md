# Architecture

## One-line summary
At plugin bootstrap, reflectively replace `Blocks.REDSTONE_WIRE.redstoneController`
(a private field, abstract type since 1.21.2) with a `DispatchingController`
that consults a per-chunk flag and delegates either to the captured vanilla
controller or to a port of SpaceWalkerRS' Alternate-Current `WireHandler`.

## Why this hook, in one paragraph
Mojang refactored `RedstoneWireBlock` in 1.21.2 so the actual algorithm
lives behind `RedstoneController` (abstract: `update`, `calculateWirePowerAt`,
`getStrongPowerAt`, `getWirePowerAt`) with `DefaultRedstoneController` as the
vanilla impl. Replacing one private-field reference is the smallest possible
NMS surface for our needs: no registry surgery, no `BlockState.owner` cache
problem, no Mixin loader requirement, no `-javaagent` flag for operators.
Paper itself already used the same pattern internally to switch between
`vanilla` / `eigencraft` / `alternate-current` (PR #7701) — we generalise it
per-chunk.

## Module layout

```
plugin/
  src/main/java/net/dedale/redstone/region/
    PluginMain.java                     # JavaPlugin entrypoint (onEnable / onDisable)
    Bootstrap.java                      # PluginBootstrap — does the field swap
    config/
      ChunkRegistry.java                # per-(Level,ChunkPos) RedstoneMode storage
      RedstoneMode.java                 # enum { VANILLA, ALTERNATE_CURRENT }
      ChunkPdcCodec.java                # serialise/deserialise from PersistentDataContainer
    nms/
      DispatchingController.java        # extends RedstoneController, dispatches
      ControllerSwap.java               # reflective installation
    ac/
      AcRedstoneController.java         # implements RedstoneController via WireHandler
      WireHandler.java                  # ported from SpaceWalkerRS (MIT)
      WireNode.java                     # ported
      Node.java                         # ported
      WireConnection.java               # ported
      PriorityQueue.java                # ported
      SimpleQueue.java                  # ported
      UpdateOrder.java                  # ported
      LevelHelper.java                  # AC <-> Mojang Level adapter
      Config.java                       # AC algorithm flags
    cmd/
      RedstoneRegionCommand.java        # Brigadier command tree
      Permissions.java
    util/
      RegionLog.java                    # rate-limited logger
      ChunkKey.java                     # Long-packed (x,z) key

test-plugin/
  src/main/java/net/dedale/redstone/test/
    TestPluginMain.java
    contraptions/
      Contraption.java                  # interface: build(Location), expectedOutputs(tick)
      DustGrid.java                     # 32x32 dust + lever + lamp matrix
      RepeaterClock.java                # 4-tick clock
      LongLine.java                     # 100-block dust line
      Comparator.java
      BudSwitch.java
      PistonExtender.java
      SrLatch.java
    ParityRunner.java                   # builds same contraption in vanilla + AC chunks, asserts parity
    PerfBenchmark.java                  # microbench AC vs vanilla
    JunitXmlWriter.java                 # writes test-results/junit.xml

test-harness/
  Dockerfile.folia
  entrypoint.sh
  docker-compose.yml
  run-tests.sh                          # build everything, start server, wait for results, tear down
```

## Lifecycle

1. **`paper-plugin.yml`** declares `bootstrapper: net.dedale.redstone.region.Bootstrap`
   and `main: net.dedale.redstone.region.PluginMain`. `folia-supported: true`.
2. **`Bootstrap.bootstrap(BootstrapContext ctx)`** — runs before world load:
   - Resolve `RedstoneWireBlock wire = (RedstoneWireBlock) BuiltInRegistries.BLOCK.get(ResourceLocation.parse("minecraft:redstone_wire"));`
   - `Field f = RedstoneWireBlock.class.getDeclaredField("redstoneController");`
   - `f.setAccessible(true);`
   - `RedstoneController vanilla = (RedstoneController) f.get(wire);`
   - `f.set(wire, new DispatchingController(wire, vanilla, ChunkRegistry.SINGLETON));`
   - The field is final-effective but not declared `final`; Mojang uses `set`-once
     in the constructor. We use `VarHandle` with release-fence to be safe.
3. **`PluginMain.onEnable()`** — runs at normal plugin enable:
   - Instantiate `ChunkRegistry` (singleton already referenced by the controller).
   - Hook chunk-load event: read PDC entry → populate `ChunkRegistry`.
   - Hook chunk-unload event: persist current mode → PDC.
   - Register `/redstone-region` command via the Paper Lifecycle API
     (`LifecycleEvents.COMMANDS`).
4. **`PluginMain.onDisable()`** — best-effort: persist all currently-tracked
   chunks back to PDC. The controller swap is left in place; on next boot
   it's a no-op if our plugin is no longer installed (the captured vanilla
   controller is gc'd because the dispatching controller is gone, but the
   field still points at it from the previous run? — no, the JVM is fresh).

## The dispatcher

```java
public final class DispatchingController extends RedstoneController {
    private final RedstoneController vanilla;
    private final RedstoneController ac;
    private final ChunkRegistry registry;

    public DispatchingController(RedstoneWireBlock wire,
                                 RedstoneController vanilla,
                                 ChunkRegistry registry) {
        super(wire);
        this.vanilla = vanilla;
        this.ac = new AcRedstoneController(wire);
        this.registry = registry;
    }

    @Override
    public void update(Level level, BlockPos pos, BlockState state,
                       Orientation orientation, boolean blockAdded) {
        controllerFor(level, pos).update(level, pos, state, orientation, blockAdded);
    }

    @Override public int calculateWirePowerAt(Level level, BlockPos pos)   { return controllerFor(level, pos).calculateWirePowerAt(level, pos); }
    @Override public int getStrongPowerAt   (Level level, BlockPos pos)    { return controllerFor(level, pos).getStrongPowerAt   (level, pos); }
    @Override public int getWirePowerAt     (BlockPos pos, BlockState st)  { return vanilla.getWirePowerAt(pos, st); /* stateless */ }

    private RedstoneController controllerFor(Level level, BlockPos pos) {
        return registry.modeOf(level, pos) == RedstoneMode.ALTERNATE_CURRENT ? ac : vanilla;
    }
}
```

The hot path is one `ConcurrentHashMap` lookup + one `Long2ByteMap.get`.
Estimated overhead per wire update: ~30 ns. Vanilla AC saves ~10–100 µs per
update on dust-heavy contraptions, so the dispatch overhead is invisible.

## ChunkRegistry — thread-safety & storage

```java
final class ChunkRegistry {
    static final ChunkRegistry SINGLETON = new ChunkRegistry();
    private final Map<ResourceKey<Level>, Long2ByteOpenHashMap> byLevel = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, StampedLock> locks         = new ConcurrentHashMap<>();

    RedstoneMode modeOf(Level level, BlockPos pos) {
        var key  = level.dimension();
        var map  = byLevel.get(key);
        if (map == null) return RedstoneMode.VANILLA;     // default
        var lock = locks.get(key);
        var stamp = lock.tryOptimisticRead();
        long ck = ChunkKey.pack(pos.getX() >> 4, pos.getZ() >> 4);
        byte v = map.get(ck);
        if (lock.validate(stamp)) return RedstoneMode.fromByte(v);
        // fall back to a read lock under contention
        stamp = lock.readLock();
        try { return RedstoneMode.fromByte(map.get(ck)); }
        finally { lock.unlockRead(stamp); }
    }

    void setMode(Level level, ChunkPos cpos, RedstoneMode mode) { /* write under writeLock */ }
}
```

- `byLevel` itself is `ConcurrentHashMap`; we lazy-init the `Long2ByteOpenHashMap`
  per dimension on first write.
- The per-level `StampedLock` allows hot-path optimistic reads with no
  contention (typical contention level in steady state: zero).
- Default value (missing key) is `VANILLA = 0`. `Long2ByteOpenHashMap.defaultReturnValue(0)`
  is set at construction.

## Persistence

Per-chunk: `Chunk#getPersistentDataContainer().set(KEY_MODE, BYTE, mode.byteValue())`.
- `KEY_MODE = NamespacedKey.fromString("dedale:redstone_engine")`.
- Loaded on `ChunkLoadEvent` (Folia: handler runs on the region thread that
  owns the chunk, which is what we want); written on `ChunkUnloadEvent`.
- For chunks never visited, no PDC entry → defaults to vanilla. Zero overhead.

## Commands

`/redstone-region <subcommand>` via Paper's `LifecycleEvents.COMMANDS` Brigadier
registrar. All sub-commands schedule via `RegionScheduler.execute` so writes
to chunk PDC happen on the owning region thread.

| Sub-command | Args | Behaviour |
|---|---|---|
| `info` | — | Prints current mode for chunk under sender's feet |
| `set` | `<vanilla\|alternate-current>` | Set current chunk |
| `fill` | `<radius:int> <mode>` | Set a (2r+1)×(2r+1) chunk square centered on sender |
| `clear` | `<radius:int>` | Reset to vanilla over a region |
| `list` | `[radius]` | Print non-default chunks within radius |

Permission: `redstone-region.admin` (op by default).

## Folia thread-safety contract

- `Bootstrap` runs single-threaded before regions exist → field swap is safe.
- The `redstoneController` field is read once per call by the wire block; once
  written at bootstrap it's never changed. Region threads see the same final
  reference.
- `ChunkRegistry` is fully thread-safe (`ConcurrentHashMap` + per-level
  `StampedLock`).
- The AC controller's `update` runs on the region thread that owns the chunk
  containing `pos`. Its accesses (`level.getBlockState(neighbor)`,
  `level.setBlock`) all stay within ±1 chunk in vanilla terms; Folia routes
  these to the same region thread.
- Cross-region edge cases (a wire chain straddling a region boundary): Folia
  serializes region merges and pauses both regions during the merge window —
  vanilla redstone has the same constraint. AC's algorithm is no more
  cross-region than vanilla's, so the constraint is satisfied.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Mojang renames `redstoneController` field in a future 1.21.x patch | Pin Mojang field name in a single constant; if reflection fails at boot, log and fall through to vanilla (don't crash). Add a CI step that builds against latest Folia snapshot daily. |
| Folia issue #334 (cross-thread redstone access on edge wires) | Same exposure as vanilla. We don't *increase* it. Document that edge-of-region pathological contraptions might still be flaky. |
| AC algorithm state leakage between calls in the same region | `WireHandler` is per-controller, but Folia gives us one region thread per call site — re-entrancy is impossible by Folia's contract. Add a tripwire `ThreadLocal` to assert. |
| `BlockState.owner` problem for our future wishes | Not relevant for technique F (we don't subclass the Block). |
| Paper updates `RedstoneController`'s method signatures (e.g., 1.22) | Pin to 1.21.11 in `paper-plugin.yml`; emit a clear error if API mismatch. |

## Test strategy (summary)

- **Parity**: build N contraptions in two side-by-side chunk-aligned regions
  (one vanilla, one alternate-current), run both for K=1000 ticks, sample
  `BlockState` of every wire/output every tick, assert byte-for-byte equality.
- **Performance**: same contraption in two chunks, alternate ticking, measure
  `System.nanoTime()` over the controller call. Target ≥3× speedup on
  dust-heavy networks.
- **Stability**: run all contraptions for 100 000 ticks under load. Assert
  no Folia thread-check throws, no exceptions.
- **Boundary**: contraption straddling a chunk boundary, both halves AC,
  half AC half vanilla — assert no exceptions even if behaviour differs.

Full details in `docs/TEST-PLAN.md` (written separately).
