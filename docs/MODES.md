# Redstone modes

The plugin lets you mark each chunk with one of four redstone evaluation
modes. The mode is stored in the chunk's `PersistentDataContainer`
(survives unloads, restarts, world saves) and applies the moment a wire
in that chunk recomputes.

## Mode comparison

| Mode | Algorithm | Speed (vs vanilla, dust grid) | Edge cases | Use when |
|---|---|---|---|---|
| **vanilla** | Mojang `DefaultRedstoneWireEvaluator` | 1× (baseline) | None — strict Mojang behaviour | Default. Every contraption works. |
| **alternate-current** | Space Walker's BFS + single-write | 2-15× faster, scales with grid size | A few — see below | Dust-heavy machines: sorters, mega-bases, big chest rooms |
| **eigencraft** | Theosib's RedstoneWireTurbo (Paper-bundled) | 3-5× faster | Fewer than AC | Hot machines that include pistons/observers, where AC breaks |
| **disabled** | No-op (wire never recomputes) | n/a — frozen | Wire keeps its last-known power forever | Archive zones, dead builds you don't want to delete |

## Edge cases that only work in vanilla (AC limitations)

These are inherent to AC's algorithm — they exist in any AC
implementation (Paper-bundled, the original Fabric mod, our port).
**Eigencraft does NOT have these issues** (it preserves the vanilla
update sequence, just optimizes the visit order).

1. **Piston BUD switches via wire's self-shape-update**

   Vanilla emits a shape-update for the wire's own block at every
   intermediate power level (15→14→13→…). A piston relying on
   quasi-connectivity through that shape change will fire under
   vanilla, not under AC (which writes the final power level once).

2. **Observers counting power-level intermediates**

   An observer watching a wire sees one block-update under AC instead
   of multiple as power decays. Counters built on this signal misfire.

3. **Strict update-order designs (MC-11193 dependents)**

   Some redstone-engineer designs depend on the deterministic vanilla
   update order. AC has its own deterministic order (flow-directional,
   not coordinate-directional), but it differs from vanilla. Affects
   sub-tick timing in the most complex builds only.

If a build needs strict vanilla parity, keep its chunks vanilla
(default) or switch to **eigencraft**, which keeps vanilla semantics
while still running 3-5× faster.

## How to choose

Run `/redstone-region check` on the chunk to see if it has a piston /
observer / dust mix. If yes, prefer `eigencraft`. If no, `alternate-current`
gives the biggest perf win.

Run `/redstone-region profile` on a chunk to see the timing data + a
recommendation engine that combines mode + components + timing stats.

## Performance numbers (32×32 dust grid)

Measured on Folia 1.21.11 build #6, M-series Mac, Java 21, 50 toggles
per run:

| Run | Vanilla | AC | Speedup |
|---|---|---|---|
| 1 (cold) | 234 ms | 22 ms | **10.4×** |
| 2 (warm JIT) | 41 ms | 14 ms | **2.9×** |
| 3 | 71 ms | 5 ms | **14.2×** |

Larger grids show wider gaps; AC's overhead is a fixed graph-build cost
per network update, so it dominates only on very small networks
(< 10 wires). See `docs/PERFORMANCE.md` for the full sweep.

## Persistence & migration

The mode is a single byte stored in the chunk's
`PersistentDataContainer` under the key `ekaii:redstone_engine`:

| Byte value | Mode |
|---|---|
| 0 | vanilla |
| 1 | alternate-current |
| 2 | eigencraft |
| 3 | disabled |

Existing entries from earlier plugin versions (bytes 0/1) are
unchanged. Bytes 2/3 are added by this version onward.

Removing the plugin from your server leaves the PDC entries in place
(harmless: a default-vanilla chunk is the same as a chunk with no PDC
entry). If you re-install the plugin later, all your AC marks come
back.
