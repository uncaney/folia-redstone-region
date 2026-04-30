# Usage — folia-redstone-region

This plugin lets you choose, **per chunk**, whether redstone is evaluated by
vanilla Mojang code or by an Alternate-Current-style network solver. Default
behaviour is unchanged (vanilla); chunks opt in to AC explicitly.

## Install

1. Build: `./gradlew :plugin:build`
2. Drop `plugin/build/libs/plugin-0.1.0-reobf.jar` into your Folia server's
   `plugins/` directory and rename it (e.g. `folia-redstone-region.jar`).
3. Restart the server. On boot you should see:
   ```
   [folia-redstone-region] redstone evaluator swapped:
   net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator
   -> net.dedale.redstone.region.nms.DispatchingEvaluator
   [folia-redstone-region] folia-redstone-region 0.1.0 ready
   ```

## Requirements

- Folia 1.21.11 (build #6 or newer). Other 1.21.x versions are not currently
  supported because the bytecode shape of `RedStoneWireBlock.evaluator`
  drifts between minor patches.
- Java 21+.
- `paper-world-defaults.yml` → `misc.redstone-implementation: vanilla` is the
  default and what you want. The plugin will force-set this on every loaded
  world; if you'd configured `alternate-current` or `eigencraft` globally, the
  plugin overrides it because those bypass our per-chunk gate.

## Commands

All require the `redstone-region.admin` permission (`op` by default).

| Command | Effect |
|---|---|
| `/redstone-region info` | Print the current chunk's mode. |
| `/redstone-region set vanilla` | Set the current chunk to vanilla redstone. |
| `/redstone-region set alternate-current` | Set the current chunk to AC. |
| `/redstone-region fill <radius> <mode>` | Apply mode to a `(2r+1)×(2r+1)` square of chunks centred on you. `radius` is in chunks (≤ 32). |
| `/redstone-region clear` | Reset the current chunk to vanilla. |
| `/redstone-region clear <radius>` | Reset a square area to vanilla. |
| `/redstone-region list` | Show, per dimension, how many chunks are non-vanilla. |

Examples:
```
/redstone-region set alternate-current      # this chunk
/redstone-region fill 8 alternate-current   # 17×17 chunks centred on you
/redstone-region info
/redstone-region set vanilla                # opt back out
```

## Persistence

Each chunk's mode is stored in its own `PersistentDataContainer` under the
key `dedale:redstone_engine` as a single byte. On chunk load the plugin
reads the PDC and registers the chunk as AC if applicable; on `/redstone-region
set …` the PDC is written immediately on the owning region thread. Region
boundaries do not affect persistence: the PDC is per-chunk.

## What's actually different in an AC chunk

When a redstone wire ticks inside an AC-marked chunk, `Blocks.REDSTONE_WIRE`'s
`updatePowerStrength` is dispatched to a port of Space Walker's
`alternate-current` algorithm (vendored from upstream commit `7a4c4071`,
MIT). Behaviour for compliant contraptions (clocks, AND gates, comparators,
torches, BUDs, dust grids, repeaters, observers) matches vanilla
byte-for-byte. Two well-documented edge cases differ from vanilla — the same
two that already differ between vanilla and Paper's bundled AC:

- Pistons that depend on quasi-connectivity from the wire's own block change.
- Observers that watch a wire and depend on the *self*-shape-update vanilla
  emits at each intermediate power level.

Both edge cases are covered in the upstream AC bug tracker; vanilla
contraptions on the right side of those edge cases tick faster, those on
the wrong side will misbehave. If a build needs strict vanilla parity,
keep its chunks vanilla (the default).

## Performance

Measured on Folia 1.21.11 (Mac M-series, Java 21), 32×32 dust grid with
input toggled 50 times:

| Run | Vanilla | AC | Speedup |
|---|---|---|---|
| 1 | 234 ms | 22 ms | **10.4×** |
| 2 (warm JIT) | 41 ms | 14 ms | **2.9×** |
| 3 | 71 ms | 5 ms | **14.2×** |

Real workloads with bigger grids see a wider gap. AC's overhead is
fixed-cost graph build per network update, so on tiny networks it can
underperform vanilla; on large dust networks the network-write
deduplication wins.

## Folia thread-safety

Each Folia region thread holds its own `WireHandler` (the AC engine's
graph + queues) via a `ThreadLocal<Map<ServerLevel, WireHandler>>`. Two
regions ticking the same world cannot collide on a single shared
WireHandler — which is the latent bug in stock Folia's bundled AC.

If a redstone signal would cross a region seam, Folia drops it (same
behaviour as vanilla on Folia). Our plugin doesn't try to fix that — it
inherits exactly the cross-region semantics Folia already enforces.

## Diagnostics

The plugin's `AcRedstoneWireEvaluator` exposes counters readable via
reflection (`AcRedstoneWireEvaluator.statValue("added"/"updated"/"removed")`).
Useful for verifying the dispatcher is in fact routing wire updates to AC.

## Uninstall

Stop the server, remove the jar, restart. The plugin's `onDisable` reverts
the evaluator field to its captured original; on a fresh JVM the field is
back to Mojang's default. Chunk PDC entries remain (they're harmless: a
default-vanilla chunk is the same as a chunk with no PDC entry).
