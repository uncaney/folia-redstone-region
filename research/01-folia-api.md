# Folia API research for the redstone-region plugin (Minecraft 1.21.11)

Research date: 2026-05-01.
Target: a Folia plugin that intercepts redstone-wire updates per chunk to dispatch
between vanilla and Alternate Current implementations.
All facts below are sourced from public PaperMC docs, the PaperMC/Folia GitHub repo
(branch `ver/1.21.11`), the Paper Maven repo, and the official downloads page.

---

## 1. Latest Folia release for 1.21.11 and build pipeline

### 1.1 Latest official Folia builds

The PaperMC downloads page only lists 1.21.11 builds for Folia as of May 2026.
There is no Folia branch yet for any later MC.

| Build | MC version | Date       |
|-------|------------|------------|
| #14   | 1.21.11    | 2026-02-22 |
| #13   | 1.21.11    | 2026-02-15 |
| #12   | 1.21.11    | 2026-02-08 |
| #11   | 1.21.11    | 2026-01-20 |
| #10   | 1.21.11    | 2026-01-04 |

Source: <https://papermc.io/downloads/folia>

`Folia` source for 1.21.11 lives on the `ver/1.21.11` branch:
<https://github.com/PaperMC/Folia/tree/ver/1.21.11>

### 1.2 paperweight-userdev / Folia dev bundle

Confirmed from the public Paper Maven repo
(<https://repo.papermc.io/repository/maven-public/dev/folia/dev-bundle/maven-metadata.xml>),
the available Folia dev-bundle versions are:

```
1.19.4-R0.1-SNAPSHOT
1.20.1-R0.1-SNAPSHOT
1.20.2-R0.1-SNAPSHOT
1.20.4-R0.1-SNAPSHOT
1.20.6-R0.1-SNAPSHOT
1.21.4-R0.1-SNAPSHOT
1.21.5-R0.1-SNAPSHOT
1.21.6-R0.1-SNAPSHOT
1.21.8-R0.1-SNAPSHOT
1.21.11-R0.1-SNAPSHOT     <-- use this for 1.21.11
```

Last metadata update: `20260222203024` (2026-02-22).

Coordinates:

```kotlin
plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" // latest as of 2026
}

dependencies {
    paperweight.foliaDevBundle("1.21.11-R0.1-SNAPSHOT")
}
```

The `paperweight.foliaDevBundle(...)` form is documented in
PaperMC's `paperweight-test-plugin` (canonical reference):
<https://github.com/PaperMC/paperweight-test-plugin/blob/master/build.gradle.kts>

The note in PaperMC docs is that `paperweight.foliaDevBundle` is an alias for
`paperweight.devBundle("dev.folia", ...)`. The dev bundle pulls in the matching
`folia-api` artifact, so do NOT also depend on `paper-api`.

Sources:
- <https://docs.papermc.io/paper/dev/userdev/>
- <https://github.com/PaperMC/paperweight-test-plugin/blob/master/build.gradle.kts>

### 1.3 Java toolchain

Paper 1.21.11 (and therefore Folia 1.21.11) requires **Java 21** to run vanilla
gameplay code, but the official Paper test plugin currently configures
`JavaLanguageVersion.of(25)` and `options.release = 25` for compilation.
For 1.21.11 specifically Java 21 is sufficient, and using 21 keeps you compatible
with the published `1.21.11` build. PaperMC docs:
<https://docs.papermc.io/misc/java-install/>

If targeting the next major (the upcoming MC 26.1 era), Paper bumps the minimum
to Java 25 — that does not apply yet to 1.21.11.

### 1.4 Mappings at runtime

Since 1.20.5 Paper ships a **Mojang-mapped runtime** by default (no Spigot reobf).
This applies to Folia as well (Folia is just patched Paper).

> "As of version 1.20.5, Paper ships with a Mojang-mapped runtime by default
> instead of reobfuscating the server to Spigot mappings."
> — <https://docs.papermc.io/paper/dev/internals/>

The PaperMC 1.21.11 release post explicitly warns that the internal remapper
will be **fully dropped in 26.1**, so for 1.21.11 reobf still technically exists
but has been deprecated for a year. **Use Mojang-mapped output**:

```kotlin
// default in paperweight 2.x for Paper-only plugins; do NOT add reobfJar.
// Spigot-style reobf is unnecessary on Folia 1.21.11.
```

If you ever need to test against an unobfuscated jar, run with
`-Dpaper.disablePluginRemapping=true`.

Source: <https://papermc.io/news/1-21-11/>

---

## 2. Folia plugin API

### 2.1 Schedulers

The four schedulers that replace `BukkitScheduler` (which is `@Deprecated` on Folia
per the Folia API patch
[`folia-api/paper-patches/features/0002-Region-scheduler-API.patch`](https://github.com/PaperMC/Folia/blob/ver/1.21.11/folia-api/paper-patches/features/0002-Region-scheduler-API.patch))
are defined under `io.papermc.paper.threadedregions.scheduler`. They live in
`paper-api` and Folia just enables their backing implementations.

Source files (verified verbatim against `main` branch — interfaces are stable
across 1.21.x and the patch in `ver/1.21.11` does not modify them):
- <https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/io/papermc/paper/threadedregions/scheduler/RegionScheduler.java>
- <https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.java>
- <https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/io/papermc/paper/threadedregions/scheduler/EntityScheduler.java>
- <https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/io/papermc/paper/threadedregions/scheduler/AsyncScheduler.java>
- <https://github.com/PaperMC/Paper/blob/main/paper-api/src/main/java/io/papermc/paper/threadedregions/scheduler/ScheduledTask.java>

#### RegionScheduler

Get via `Bukkit.getRegionScheduler()` (also `Server#getRegionScheduler()`).

```java
public interface RegionScheduler {
    void execute(Plugin plugin, World world, int chunkX, int chunkZ, Runnable run);
    default void execute(Plugin plugin, Location location, Runnable run);

    ScheduledTask run(Plugin plugin, World world, int chunkX, int chunkZ,
                      Consumer<ScheduledTask> task);
    default ScheduledTask run(Plugin plugin, Location location,
                              Consumer<ScheduledTask> task);

    ScheduledTask runDelayed(Plugin plugin, World world, int chunkX, int chunkZ,
                             Consumer<ScheduledTask> task, long delayTicks);
    default ScheduledTask runDelayed(Plugin plugin, Location location,
                                     Consumer<ScheduledTask> task, long delayTicks);

    ScheduledTask runAtFixedRate(Plugin plugin, World world, int chunkX, int chunkZ,
                                 Consumer<ScheduledTask> task,
                                 long initialDelayTicks, long periodTicks);
    default ScheduledTask runAtFixedRate(Plugin plugin, Location location,
                                         Consumer<ScheduledTask> task,
                                         long initialDelayTicks, long periodTicks);
}
```

Class javadoc says explicitly:
> "It is entirely inappropriate to use the region scheduler to schedule tasks
> for entities. If you wish to schedule tasks to perform actions on entities,
> you should be using `Entity.getScheduler()` as the entity scheduler will
> 'follow' an entity if it is teleported."

Notes:
- Tasks are dispatched on the thread that currently owns the chunk
  `(world, chunkX, chunkZ)`. If chunks/regions split or merge between
  scheduling and execution, the runtime resolves correctly to whichever thread
  ends up owning the chunk at execution time.
- `execute(...)` is a "best-effort same-tick" dispatch (it may run inline if
  the calling thread already owns the region; otherwise it is queued).
- There is no `cancelTasks(plugin)` on RegionScheduler in 1.21.x — cancellation
  is per-task via `ScheduledTask#cancel()`.

#### GlobalRegionScheduler

`Bukkit.getGlobalRegionScheduler()`.

```java
public interface GlobalRegionScheduler {
    void execute(Plugin plugin, Runnable run);
    ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task);
    ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                             long delayTicks);
    ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                 long initialDelayTicks, long periodTicks);
    void cancelTasks(Plugin plugin);
}
```

Used for state owned by the "global region": world day time, world game time,
weather cycles, sleep night-skipping, console command dispatch.

#### EntityScheduler

Obtained from `entity.getScheduler()`. Returns a per-entity scheduler that
follows the entity across teleports/region transitions.

```java
public interface EntityScheduler {
    boolean execute(Plugin plugin, Runnable run, /* nullable */ Runnable retired,
                    long delay);

    /* nullable */ ScheduledTask run(Plugin plugin,
                                     Consumer<ScheduledTask> task,
                                     /* nullable */ Runnable retired);

    /* nullable */ ScheduledTask runDelayed(Plugin plugin,
                                            Consumer<ScheduledTask> task,
                                            /* nullable */ Runnable retired,
                                            long delayTicks);

    /* nullable */ ScheduledTask runAtFixedRate(Plugin plugin,
                                                Consumer<ScheduledTask> task,
                                                /* nullable */ Runnable retired,
                                                long initialDelayTicks,
                                                long periodTicks);
}
```

Returns null / false if the scheduler is "retired" (entity has been removed).
The `retired` callback is invoked instead of the run callback in that case;
it runs in critical code so it must not load chunks or remove entities.

#### AsyncScheduler

`Bukkit.getAsyncScheduler()`. Off-tick thread pool.

```java
public interface AsyncScheduler {
    ScheduledTask runNow(Plugin plugin, Consumer<ScheduledTask> task);
    ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task,
                             long delay, TimeUnit unit);
    ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task,
                                 long initialDelay, long period, TimeUnit unit);
    void cancelTasks(Plugin plugin);
}
```

Note delays are in **TimeUnit**, not ticks.

#### ScheduledTask

```java
public interface ScheduledTask {
    Plugin getOwningPlugin();
    boolean isRepeatingTask();
    CancelledState cancel();
    ExecutionState getExecutionState();
    default boolean isCancelled();
    enum CancelledState { CANCELLED_BY_CALLER, CANCELLED_ALREADY,
                          RUNNING, ALREADY_EXECUTED,
                          NEXT_RUNS_CANCELLED, NEXT_RUNS_CANCELLED_ALREADY }
    enum ExecutionState  { IDLE, RUNNING, FINISHED,
                          CANCELLED, CANCELLED_RUNNING }
}
```

### 2.2 Brigadier command registration on Folia

Folia inherits Paper's lifecycle-based command API (Folia exposes
`io.papermc.paper.command.brigadier.Commands` in `folia-api 1.21.11-R0.1-SNAPSHOT`,
javadocs at <https://jd.papermc.io/folia/1.21.11/io/papermc/paper/command/brigadier/Commands.html>).

The canonical pattern (from
<https://docs.papermc.io/paper/dev/command-api/basics/registration/>):

```java
public final class RedstoneRegionPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        this.getLifecycleManager().registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> {
                LiteralCommandNode<CommandSourceStack> node =
                    Commands.literal("redstoneregion")
                        .then(Commands.literal("status")
                            .executes(ctx -> {
                                ctx.getSource().getSender().sendMessage("OK");
                                return Command.SINGLE_SUCCESS;
                            }))
                        .build();
                event.registrar().register(node, "Redstone region tools",
                                           List.of("rsr"));
            }
        );
    }
}
```

Or via `PluginBootstrap` (preferred, requires `paper-plugin.yml`):

```java
public final class Bootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> { /* register commands */ }
        );
    }
}
```

Notes for Folia:
- `LifecycleEventManager` is fully Folia-compatible. Command callbacks run on
  the global region tick thread by default. If your command implementation
  needs to touch chunk-owned state, dispatch with
  `Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, ...)`.
- Avoid `Bukkit.getCommandMap()` and the legacy `commands:` block in
  `plugin.yml` — those still work but they bypass the lifecycle/Brigadier path
  and can fight with Folia's region scheduler.

Source: <https://docs.papermc.io/paper/dev/lifecycle/>

### 2.3 Block / Chunk thread-safety on Folia

The hard rule (`PROJECT_DESCRIPTION.md` and overview):
> "Regions tick in parallel, and not concurrently. They do not share data,
> they do not expect to share data, and sharing of data will cause data
> corruption."
— <https://docs.papermc.io/folia/reference/overview/>

Anything that reads/writes chunk-owned state must run on the region thread
that currently owns that chunk. The check API exposed via Bukkit
(<https://jd.papermc.io/folia/1.21.11/org/bukkit/Bukkit.html>) is:

```java
static boolean isOwnedByCurrentRegion(Location location);
static boolean isOwnedByCurrentRegion(Location location, int squareRadiusChunks);
static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ);
static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ,
                                      int squareRadiusChunks);
static boolean isOwnedByCurrentRegion(World world,
                                      int minChunkX, int minChunkZ,
                                      int maxChunkX, int maxChunkZ);
static boolean isOwnedByCurrentRegion(World world, Position position);
static boolean isOwnedByCurrentRegion(World world, Position position,
                                      int squareRadiusChunks);
static boolean isOwnedByCurrentRegion(Block  block);
static boolean isOwnedByCurrentRegion(Entity entity);

static RegionScheduler        getRegionScheduler();
static AsyncScheduler         getAsyncScheduler();
static GlobalRegionScheduler  getGlobalRegionScheduler();
```

Operations that throw `IllegalStateException` (via
`ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(...)`) when
called off the owning region thread, as patched by Folia's
`folia-server/paper-patches/features/0001-Region-Threading-Base.patch`
(grep'd at lines 959–1241 of
<https://github.com/PaperMC/Folia/blob/ver/1.21.11/folia-server/paper-patches/features/0001-Region-Threading-Base.patch>):

- `World#getChunkAt(...)` async / `getChunkAtAsync(...)` (must be on owning thread)
- `World#unloadChunk(...)`
- `World#refreshChunk(...)`
- `World#loadChunk(...)` (sync)
- `World#isChunkForceLoaded`, `setChunkForceLoaded` (must be **global** region thread)
- `World#getForceLoadedChunks` (global)
- `World#generateTree(...)`
- `World#setTime`, `setFullTime` (global)
- `World#createExplosion(...)` (owning region for explosion center)
- `World#hasStorm`, `setStorm`, `setWeatherDuration`, `setThundering`,
  `setThunderDuration` (global)
- `Server`/`World` settings mutators, metadata get/set (global)
- `ServerCommandSender`-based `dispatchCommand(async)` (global)

Entity state access: every `getHandle()` operation on `CraftEntity` is guarded
by `TickThread.ensureTickThread(this.entity, "Accessing entity state off
owning region's thread")` per the same patch (~line 479). Crossing this throws
`IllegalStateException` and a stack dump.

#### Chunk#getPersistentDataContainer

There is no public statement that `Chunk#getPersistentDataContainer()` is
explicitly thread-safe on Folia. It is **region-bound** — the underlying
storage is a field on `LevelChunk` which is owned by whichever region currently
owns the chunk. A read/write that happens on the wrong thread will either:
1. trip a `TickThread.ensureTickThread` check (newer code paths on Folia
   1.21.11 have these for chunk holder/state access), or
2. silently race (older code paths may not be guarded).

Empirical verdict: **always wrap PDC access with the region scheduler**:

```java
Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
    Chunk chunk = world.getChunkAt(cx, cz);
    PersistentDataContainer pdc = chunk.getPersistentDataContainer();
    pdc.set(KEY, PersistentDataType.BYTE, (byte) 1);
});
```

This is **not** spelled out anywhere in the Folia docs explicitly — the docs
only say "thread-safety comes from the fact that a single region owns data in
certain chunks." So treat as region-bound until proven otherwise; we will need
to verify empirically in the test harness.

### 2.4 paper-plugin.yml keys (Folia)

From <https://docs.papermc.io/paper/dev/plugin-yml/> and the
`folia-supported` patch
([`0003-Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch`](https://github.com/PaperMC/Folia/blob/ver/1.21.11/folia-api/paper-patches/features/0003-Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch))
the canonical `paper-plugin.yml` for our plugin should be:

```yaml
name: RedstoneRegion
version: '0.1.0'
main: com.example.redstoneregion.RedstoneRegionPlugin
description: Per-chunk dispatch between vanilla and Alternate Current redstone wire.
api-version: '1.21.11'        # MC 1.21.11; for the next era it becomes '26.1.2'
folia-supported: true         # MANDATORY — Folia refuses to load otherwise

bootstrapper: com.example.redstoneregion.RedstoneRegionBootstrap
loader: com.example.redstoneregion.RedstoneRegionLoader   # optional
authors: [you]

dependencies:
  bootstrap: {}
  server: {}
```

Key facts:
- Without `folia-supported: true` Folia logs a refusal and skips the plugin,
  even on a successful classpath load (Folia patch is verbatim
  `if (map.get("folia-supported") != null) foliaSupported = ...; ...
   public boolean isFoliaSupported() { return foliaSupported != null
   && foliaSupported.equalsIgnoreCase("true"); }`).
- `api-version` for 1.21.11 is `1.21.11` (or just `1.21`). The value `26.1.2`
  applies to the upcoming MC era and **will not load** on 1.21.11. Allowed
  range per docs: `1.13` … `26.1.2`.
- `bootstrapper` runs `PluginBootstrap#bootstrap(BootstrapContext)` before the
  server starts. This is where you register lifecycle event handlers
  (commands, registry compose) and (per Section 3.3 below) the only sane place
  to mutate registries.
- `loader` (optional) implements `PluginLoader` and is the only way to declare
  Maven Central library dependencies via `MavenLibraryResolver`.
- `dependencies.bootstrap.<name>` and `dependencies.server.<name>` replace
  the legacy `depend`/`softdepend` lists.
- Paper plugins (i.e. those using `paper-plugin.yml`) **do not** declare
  commands in the YAML — register them via Brigadier in the lifecycle event.

Sources:
- <https://docs.papermc.io/paper/dev/getting-started/paper-plugins/>
- <https://docs.papermc.io/paper/dev/plugin-yml/>

---

## 3. NMS access from a Folia plugin

### 3.1 paperweight-userdev — still canonical

Yes. Paperweight 2.x (`io.papermc.paperweight.userdev` v2.0.0-beta.21 as of
April 2026) is the only supported way to consume NMS on Paper/Folia. It pulls
the dev bundle, applies the Mojang-mapped server jar to the dependency graph,
and produces a Mojang-mapped output JAR. No reobfuscation step is needed for
1.21.11+.

Reference: <https://docs.papermc.io/paper/dev/userdev/> and
<https://github.com/PaperMC/paperweight-test-plugin/blob/master/build.gradle.kts>

### 3.2 Mojang vs reobf at runtime on 1.21.11

- Server runtime: **Mojang-mapped** (since 1.20.5).
  Confirmed: <https://docs.papermc.io/paper/dev/internals/>.
- Plugin classpath: Paper's **plugin-remapper** transparently remaps
  Spigot-mapped plugins to Mojang at load time on 1.21.x.
- Starting MC 26.1 the plugin remapper is dropped — you must ship Mojang-mapped
  jars. For 1.21.11 you can still ship reobf'd jars, but there is no reason to;
  ship Mojang-mapped output.

### 3.3 Block-registry mutation / swapping `minecraft:redstone_wire`

There is no first-class Paper API for **replacing** a built-in block. The
`RegistryEvents` `compose` lifecycle (the documented path) only adds new
entries; it does not replace existing ones. Modifying *behavior* of an
existing entry is allowed through `entryAdd` lifecycle but only for fields
the API exposes (e.g. enchantment levels) — not for blocks.

Sources:
- <https://docs.papermc.io/paper/dev/registries/>
- <https://gist.github.com/Machine-Maker/2901c790219862ef1ad6070b8872a889>

To actually swap `minecraft:redstone_wire` you have three real options:

**Option A — don't swap, intercept.**
Hook the existing block's update path (e.g. through a Mixin-like agent or by
registering a `BlockPhysicsEvent` listener at the Bukkit layer plus an
NMS-level interception of `RedStoneWireBlock#neighborChanged` /
`#updateShape`/`#getSignal`). On Folia 1.21.11, neighbor updates already go
through a `TickThread.isTickThreadFor(level, pos, 16)` guard
(see [`0004-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`](https://github.com/PaperMC/Folia/blob/ver/1.21.11/folia-server/minecraft-patches/features/0004-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch)
lines 54–66 patch `RedStoneWireBlock`), so any interception you write must
either run on the owning region thread or also bail with `isTickThreadFor`.
This is what we should pursue — see notes in Section 4.3.

**Option B — bytecode-level redefine.**
Use a Java agent (`Instrumentation#redefineClasses`) attached during
bootstrap to patch `RedStoneWireBlock` directly. This is what `Lithium`-style
mods do via Mixin. On Paper this is unsupported but works.

**Option C — reflective registry replacement (NOT recommended).**
The community technique reported on
<https://forums.papermc.io/threads/replacing-an-nms-registry-entry-with-your-own.596/>
(from Machine-Maker, a Paper dev) is:

```java
// Reflectively flip the FROZEN AtomicBoolean and INTRUSIVE_HOLDER_CACHE
// fields on net.minecraft.core.MappedRegistry, then unregister + re-register.
public synchronized T replace(ResourceKey<T> key, T entry) {
    if (isClosed()) open();          // sets FROZEN.set(registry, false)
    int id = toId.getInt(key);
    T old = unregister(key);
    if (old != null) register(key, entry, id, Lifecycle.stable());
    else            register(key, entry);
    return old;
}
```

The thread reports the technique works server-side but breaks client-side
sync (the client never knows about the new BlockState class). For
`minecraft:redstone_wire`, where the BlockState IDs and properties must
stay identical to vanilla, this is workable IF the replacement BlockState
keeps the same properties and same default state.

**Lifecycle phase**: **bootstrap**, before any world loads.
The `BuiltInRegistries` are frozen at the end of
`Bootstrap.bootStrap()` which Paper calls before plugin enable. By the time
your plugin's `onEnable()` runs, the BLOCK registry is already frozen.
The ONLY safe phase to do reflective unfreezing is inside
`PluginBootstrap#bootstrap(BootstrapContext)`.

Caveat: there is no documented Paper API to do this at the right phase.
**Treat this as empirically required**: you will have to confirm in the test
harness that your bootstrap runs BEFORE the registry freeze, and use
reflection on `MappedRegistry$frozen`. Paper's docs do not endorse this.

A cleaner alternative we should evaluate first:
- Drop the registry-replacement plan.
- Keep the vanilla `RedStoneWireBlock` class intact.
- Instrument the few hot methods (`getSignalNonNormalCube` / `updatePower` /
  `updateShape`) via a Java agent attached at bootstrap, branching on the
  per-chunk PDC flag.

Recommendation for our plugin: **Option A or B**, not C.

---

## 4. Folia threading model details that impact redstone

### 4.1 Region size

From [`folia-server/paper-patches/features/0001-Region-Threading-Base.patch`](https://github.com/PaperMC/Folia/blob/ver/1.21.11/folia-server/paper-patches/features/0001-Region-Threading-Base.patch)
and `folia-api`:

```java
public class ThreadedRegions {                       // GlobalConfiguration child
    public int threads = -1;                         // -1 = auto
    public int gridExponent = 4;                     // <-- DEFAULT
    public TickRegionScheduler.SchedulerType scheduler =
        TickRegionScheduler.SchedulerType.EDF;
}
```

```java
public final class TickRegions ... {
    private static int regionShift = 31;             // overwritten at init
    public static int getRegionChunkShift() { return regionShift; }

    public static void init(GlobalConfiguration.ThreadedRegions config) {
        int gridExponent = Math.max(0, Math.min(31, config.gridExponent));
        regionShift = gridExponent;                   // default 4
        ...
    }
}
```

Each `ServerLevel` then constructs a regionizer:

```java
this.regioniser = new ThreadedRegionizer<>(
    /* maxDeadSections           */ (int) Math.max(1L, (8L * 16L * 16L)
                                       / (1L << (2 * shift))),  // = 8 at shift=4
    /* maxDeadRatio              */ (1.0 / 6.0),
    /* emptySectionCreateRadius  */ Math.max(1, 8 / (1 << shift)), // = 1
    /* regionSectionMergeRadius  */ 1,
    /* sectionChunkShift         */ shift,                          // = 4
    /* level                     */ this,
    /* tickRegions callback      */ this.tickRegions
);
```

So with the defaults:

- **Section size = 2^4 = 16 chunks per side = 256 chunks per section.**
- **Empty-section create radius = 1 section.**
- **Merge radius = 1 section** = 16 chunks.

This means:
- A region must own at least 1 section (16x16 chunks = 256 chunks).
- Two regions whose chunks come within 1 section (16 chunks) of each other
  are forced to merge before they can both be ticking.
- A "buffer" of empty sections is owned around any active region.

The Folia README advises plugins assume an event source has ~8 chunks
of writable safe area — this is half a section, intentionally conservative.
<https://github.com/PaperMC/Folia/blob/ver/1.21.11/README.md>

### 4.2 Region merge / split semantics

From <https://docs.papermc.io/folia/reference/region-logic/>:

> "Any ticking region may not grow while it is ticking."
> "Any ticking region must initially own a small buffer of chunks outside its
>  perimeter."
> "Regions may not begin to tick if they have a neighboring adjacent region."
> "Adjacent regions must eventually merge to form a single region."
> "for every existing chunk holder x ... every chunk position within the
>  'merge radius' of x is owned by the region"

When two adjacent regions are detected:
- The Folia chunk system creates "transient" regions around any ticking region
  (they exist but cannot tick).
- When the ticking region finishes its tick, the regionizer merges all
  transient/adjacent regions into a single region for the next tick.
- Splits happen when independent areas of a region drift apart.

Game-time tick state (block ticks, fluid ticks, redstone scheduling) is
**preserved across merges/splits**:
> "redstone or any other events scheduled by current tick remain unaffected
>  when regions split or merge as the relative deadline is maintained by
>  applying an offset in the merge case and by copying the tick number in the
>  split case."
> — <https://docs.papermc.io/folia/reference/region-logic/>

Each region also maintains its own per-region game time / redstone time
counter. The block-tick and fluid-tick lists are stored in `RegionizedData`,
attached to the region, so a single tick step processes only the tick events
scheduled within the region's owned chunks.

### 4.3 Cross-region neighbor updates and the redstone wire chain

This is the critical question for our design. Hard answer: **a redstone wire
chain that spans further than a region's owned chunks does NOT cause a region
merge**, and Folia silently drops the cross-region updates.

Evidence — patch `folia-server/minecraft-patches/features/0004-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch`
(<https://github.com/PaperMC/Folia/blob/ver/1.21.11/folia-server/minecraft-patches/features/0004-Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch>):

```java
// Level#updateNeighbourForOutputSignal
- if (this.hasChunkAt(blockPos)) {
+ if (TickThread.isTickThreadFor(this, blockPos, 16) && this.hasChunkAt(blockPos)) {

// CollectingNeighborUpdater (the queue used by all neighborChanged dispatches)
- BlockState blockState = level.getBlockState(blockPos);
+ BlockState blockState = !TickThread.isTickThreadFor(level, blockPos, 16)
+     ? null : level.getBlockState(blockPos);
+ if (blockState != null) { /* run neighbor update */ }

// RedStoneWireBlock#updatePower (inside the per-direction loop)
+ BlockState currState; mutableBlockPos.setWithOffset(pos, direction);
- if (redstoneSide != RedstoneSide.NONE && !level.getBlockState(...).is(this)) {
+ if (redstoneSide != RedstoneSide.NONE
+     && (currState = (level instanceof ServerLevel sl
+                     && !TickThread.isTickThreadFor(sl, pos, 16)
+                     ? null
+                     : level.getBlockStateIfLoaded(...))) != null
+     && !currState.is(this)) {
```

The patch header is unambiguous:
> "This is to prevent block physics from tripping thread checks by far
>  exceeding the bounds of the current region. While this does add explicit
>  block update suppression techniques, it's better than the server crashing."

Implications for our redstone-region plugin:

1. **Folia does NOT merge regions for redstone.** A wire chain that runs
   across the boundary between regions A and B will simply have its neighbor
   updates from A→B suppressed (and vice versa). The wire becomes
   "non-deterministic at the seam" — exactly the behavior described in
   Folia issue #334 ("28w Pork Tower" still throws thread-check errors,
   open as of May 2026).
   <https://github.com/PaperMC/Folia/issues/334>

2. **The 16-block thread check uses block radius**, so the safe write zone
   from any source position is `pos ± 16` blocks (= 1 chunk in any direction).
   Anything outside that is `isTickThreadFor(...) == false` and the update
   is dropped.

3. **For our plugin's design**, this means:
   - Switching the algorithm per-chunk is safe in principle, because a
     region always contains contiguous chunks; you cannot have "vanilla on
     chunk X, AC on chunk X+1, both ticking simultaneously and racing".
   - BUT a single AC power network must be **fully contained inside one
     region**, otherwise AC's BFS will silently truncate at the seam and the
     wire's far end will not update. Effectively, AC has the same
     correctness limit Folia already imposes on vanilla wire.
   - We cannot use AC to fix redstone machines that span >16 chunks — they
     are already broken on Folia at the same boundary.

4. **Cross-region neighbor updates from `Block#neighborChanged`**: dropped.
   The dispatch goes via `CollectingNeighborUpdater`, which is the patched
   class above. So *any* plugin-driven block edit that triggers neighbor
   updates outside the owning region's 1-chunk boundary will be suppressed
   silently — no exception, no warning.

### 4.4 Operations that throw IllegalStateException off-region

Already enumerated in 2.3 above. The relevant additions for redstone are:

- Calling `world.getBlockState`, `world.setBlock`, `world.scheduleTick` on a
  position outside the current region's chunk set (via the wrappers
  `CraftWorld#getChunkAt`, etc.) throws
  `IllegalStateException: ensureTickThread(...)`.
- The Block-update path itself does NOT throw — it silently drops, per 4.3.
- Any access to `Entity` state from another region's thread throws
  `Accessing entity state off owning region's thread`.

For our plugin, the safe-pattern is:

```java
// hot path inside the wire-update interception, on the owning thread
if (!TickThread.isTickThreadFor(level, pos, 16)) return;
// ... run interception ...
```

(Or via the Bukkit-level wrapper: `Bukkit.isOwnedByCurrentRegion(block)`.)

---

## 5. Existing Folia redstone plugins / forks

The pickings are slim. As of May 2026:

| Project | Source | Type | Notes |
|---|---|---|---|
| **Alternate Current** | <https://github.com/SpaceWalkerRS/alternate-current> | Fabric mod (1.20.1 / 1.21.x snapshots) | Original AC. **Not** ported to Paper/Folia. Latest tagged release 1.9 (Aug 2024); newer snapshots in master. The algorithm itself is BSD-style licensed, can be reimplemented as a Bukkit plugin if you keep correct attribution. |
| **PerfoBooster** (Hangar, BLOODRED) | <https://hangar.papermc.io/BLOODRED/PerfoBooster> | Closed-source paid plugin | Claims "Redstone Implementation using Alternate Current for 2-10x faster redstone performance" + "Full Folia support using region-based and async scheduling." Source NOT public. Only known commercial Folia AC port. |
| **AntiRedstone** (SpigotMC, paid) | <https://www.spigotmc.org/resources/antiredstone-advanced-redstone-lag-prevention-%E2%9C%85-1-18-1-21-4-support-folia.123264/> | Closed-source | Anti-clock / rate limiter, NOT an algorithmic optimizer. Not relevant. |
| **AntiRedstoneClock-Remastered** | <https://hangar.papermc.io/OneLiteFeather/AntiRedstoneClock-Remastered> | Open source | Clock detector only. |
| **RedstoneLimiter** | <https://github.com/KRYMZ0N/RedstoneLimiter> | Open source | Per-chunk redstone-block placement cap. Not algorithmic. Useful as a reference for chunk-keyed configuration. |
| **Lithium / Slice / RedstoneTweaks** | — | Fabric/Forge mods only | None has a published Paper/Folia port as of May 2026. |
| **BlockhostOfficial/folia-plugins** index | <https://github.com/BlockhostOfficial/folia-plugins> | List | Lists no redstone-algorithm plugins. |

So: **no open-source prior art for a Folia + AC bridge** exists. We are
the first; PerfoBooster is the only known closed-source competitor.

---

## 6. Confidence and unverified items

The following items are NOT cleanly settled in public docs and need to be
verified empirically in our test harness:

1. Exact thread-safety semantics of `Chunk#getPersistentDataContainer()` —
   no explicit Folia statement. (See 2.3.)
2. Whether `PluginBootstrap#bootstrap(BootstrapContext)` runs before Paper
   freezes `BuiltInRegistries.BLOCK`. The docs say "before the server is
   loaded" but do not pin the order against `Bootstrap.bootStrap()`.
   (See 3.3.)
3. Whether reflective unfreeze of the BLOCK registry survives Paper's plugin
   remapper / classpath isolation on 1.21.11. (See 3.3.)
4. Whether `Bukkit.isOwnedByCurrentRegion(block)` is sound for a block in a
   chunk that is currently in a *transient* (not yet merged) region — the
   docs warn "even a region considered to be ready in the past may be
   unexpectedly marked transient", so we must build retry/dispatch logic.
5. Behavior of `World#scheduleTick(...)` near a region boundary — the patch
   does not show explicit thread-check additions for the schedule call, only
   for the dispatch.

---

## 7. Critical-facts summary (10 lines)

1. Use Folia **build #14 (2026-02-22)** of MC **1.21.11** — there is no Folia for any newer MC.
2. Build with **paperweight-userdev 2.0.0-beta.21** + `paperweight.foliaDevBundle("1.21.11-R0.1-SNAPSHOT")`, Mojang-mapped output, Java 21+.
3. `paper-plugin.yml` MUST contain `folia-supported: true` and `api-version: '1.21.11'`; register commands via `LifecycleEvents.COMMANDS` + `Commands.literal(...)` + `event.registrar().register(node)`.
4. Schedulers: `Bukkit.getRegionScheduler()` for chunk-keyed work, `Bukkit.getGlobalRegionScheduler()` for world/global state, `entity.getScheduler()` for entity-following work, `Bukkit.getAsyncScheduler()` for off-tick.
5. Default region grid is **gridExponent=4 → 16x16 chunk sections (256 chunks)**, merge radius 1 section, empty-create radius 1 section; regions ALWAYS merge before two adjacent regions can both tick.
6. Folia DOES NOT merge regions for redstone — patch `0004-Prevent-block-updates-in-non-loaded-or-non-owned-chu` silently drops `neighborChanged`/`updatePower` updates whose target is outside `TickThread.isTickThreadFor(level, pos, 16)` (i.e. ±1 chunk from the owning region).
7. Thread checks via `Bukkit.isOwnedByCurrentRegion(...)` (8 overloads); off-region access to `World#load/unloadChunk`, `setTime`, `setStorm`, `getChunkAtAsync`, and any `CraftEntity#getHandle()` throws `IllegalStateException`.
8. There is **no** Paper API to replace `minecraft:redstone_wire` in `BuiltInRegistries.BLOCK`. The only working paths are: (a) an interception/Mixin-style java agent attached in `PluginBootstrap#bootstrap`, or (b) reflective `MappedRegistry.frozen` flip during bootstrap (unsupported, breaks client sync if state IDs change).
9. `Chunk#getPersistentDataContainer()` is **region-bound** — wrap reads/writes in `Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, ...)`; verify in test harness, no public guarantee exists.
10. No open-source prior art exists for a Folia + Alternate Current bridge as of May 2026; only PerfoBooster (closed-source) claims the integration. AC's algorithm is in <https://github.com/SpaceWalkerRS/alternate-current> and must be reimplemented as a Bukkit plugin.
