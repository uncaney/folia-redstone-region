# folia-redstone-region

Per-chunk redstone-engine dispatch for Folia (Minecraft 1.21.11).
Default chunks tick vanilla; opt chunks into Alternate-Current with
`/redstone-region set alternate-current`. Reverse with `set vanilla`.

## Status

End-to-end validated on Folia 1.21.11 build #6 with a 13-case in-server
test suite — see `test-harness/`. Most recent run:

| Test | Outcome |
|---|---|
| evaluator-installed | ✅ |
| persistence-pdc | ✅ PDC roundtrip |
| perf-32×32-grid | ✅ 14.22× vanilla→AC |
| dust-line-30 | ✅ parity OK 63 sample-pairs |
| dust-grid-16 | ✅ parity OK 63 sample-pairs |
| repeater-clock-4 | ✅ parity OK 63 sample-pairs |
| and-gate-2-input | ✅ parity OK 63 sample-pairs |
| torch-inverter | ✅ parity OK 63 sample-pairs |
| comparator-sub | ✅ parity OK 63 sample-pairs |
| torch-ladder-4 | ✅ parity OK 63 sample-pairs |
| dust-zigzag-8 | ✅ parity OK 63 sample-pairs |
| stress-600t | ✅ 593 toggles, 0 exceptions over 30 s |
| ac-actually-exercised | ✅ 569 dispatches verified |

## How it works

At plugin enable, reflectively swaps `Blocks.REDSTONE_WIRE.evaluator` (a
private `RedstoneWireEvaluator` field added by Mojang in 1.21.2) with a
`DispatchingEvaluator` that consults a per-chunk flag and delegates to either
the captured `DefaultRedstoneWireEvaluator` (vanilla) or to a port of
SpaceWalkerRS' Alternate-Current algorithm (vendored from upstream branch
`1.21.11`, MIT).

The AC engine holds its `WireHandler` per-thread (one per Folia region
thread) instead of per-`ServerLevel` like Paper's bundled implementation,
sidestepping the latent Folia race in vanilla Paper-on-Folia today.

Forces `paperConfig.misc.redstoneImplementation = VANILLA` on every world
at boot; otherwise Paper's gating short-circuits to its own per-level
WireHandler before our dispatcher gets called.

See `docs/ARCHITECTURE.md` for the full plan, `docs/USAGE.md` for operator
documentation, and the `research/` directory for pre-implementation analysis
of Folia API, Alternate Current, and hook techniques considered.

## Layout

```
plugin/                 the runtime plugin
test-plugin/            in-server companion: parity / perf / persistence / stress
test-harness/           scripts to launch Folia, run tests, dump junit.xml
research/               pre-implementation reports + upstream AC clone
docs/                   architecture + usage
```

## Build

```
./gradlew :plugin:build
# → plugin/build/libs/plugin-0.1.0-reobf.jar
```

## Test

```
./gradlew build
./test-harness/run-tests.sh
# → test-harness/server/test-results/junit.xml
```

The harness downloads Folia 1.21.11 (or reuses the one in
`test-harness/server/folia.jar`), spawns a flat world, runs all 13 test
cases, prints the JUnit XML and exits non-zero if any case fails.

## License

Plugin glue: MIT.
Bundled Alternate-Current engine: MIT (Space Walker — see
`plugin/src/main/java/net/dedale/redstone/region/ac/LICENSE`).
