# Building from source

## Prerequisites

- **Java 21** (Temurin / OpenJDK 21+)
- **Git** for the gradle wrapper checkout
- A Unix-y shell. Windows works via WSL or Git Bash; native cmd
  is untested.
- ~2 GB free disk for Gradle caches + paperweight devbundle

## One-shot build

```sh
git clone https://github.com/uncaney/folia-redstone-region.git
cd folia-redstone-region
./gradlew :plugin:build :test-plugin:build
```

Outputs:

- `plugin/build/libs/plugin-0.1.0-reobf.jar` — the runtime plugin (drop
  in `plugins/`)
- `test-plugin/build/libs/test-plugin-0.1.0-reobf.jar` — companion
  in-server test harness (used by `test-harness/run-tests.sh`)

The first build downloads the Folia 1.21.11 dev-bundle from
`repo.papermc.io` (~80 MB), patches the Paper sources, and remaps to
Mojang names. Allow 1-2 minutes. Subsequent builds are 1-3 seconds
incremental.

## Running the harness

```sh
./test-harness/run-tests.sh
```

This:

1. Downloads Folia 1.21.11 build #6 from `papermc.io` if missing.
2. Spawns a local server in `test-harness/server/`.
3. Loads the plugin + the companion test-plugin (the test-plugin sets
   itself to autoRun=true and triggers the harness automatically).
4. Waits up to 5 minutes for `test-results/ready` to appear.
5. Prints the JUnit XML and exits non-zero if any case failed.

Expected output:

```
[run-tests] tests completed after 50s
cases=14 failures=0
…
[run-tests] all tests passed
```

The 14 cases include parity tests for 8 contraptions, persistence,
perf benchmark, stress (600 toggles), cross-region stress (2 regions
in parallel for 800 ticks each), AC-actually-exercised counter check,
and evaluator-installed sentinel.

## CI

Pushes to `main` trigger:

- **Forgejo Actions** (canonical) — see
  `https://forgejo.ekaii.fr/exo/folia-redstone-region/actions`. Builds
  + uploads jars as artifacts. Runner: Coolify-hosted Linux.
- **GitHub Actions** (`build.yml`, `codeql.yml`) — same build, plus
  CodeQL security scan.

## Project layout

```
plugin/                                             # runtime plugin
  src/main/java/net/ekaii/redstone/region/
    PluginMain.java                                 # JavaPlugin entry
    Bootstrap.java                                  # PluginBootstrap entry
    nms/   DispatchingEvaluator.java                # the per-chunk dispatcher
           EvaluatorSwap.java                        # reflective NMS swap
           PaperConfigForcer.java                    # forces paper redstoneImpl=VANILLA
           EigencraftWireEvaluator.java
           DisabledWireEvaluator.java
    ac/    *                                         # vendored Alternate Current (MIT, Space Walker)
    config/ ChunkRegistry.java
            ChunkPdcCodec.java
            PluginConfig.java
            RedstoneMode.java                         # vanilla / AC / eigencraft / disabled
    timing/ ChunkTimingTable.java
            TimingPolicy.java                          # all / sample / non-vanilla-only / off
    audit/  AuditLog.java                              # JSONL daily-rolled
    auto/   AutoAcScanner.java                          # background hot-chunk detector
    bridge/ BlueMapBridge.java
            WorldEditBridge.java
            PlaceholderApiBridge.java
            DiscordWebhook.java
    listeners/ ChunkSyncListener.java
               SignOptInListener.java
    cmd/    RedstoneRegionCommand.java                  # Brigadier tree
            UndoBuffer.java                              # ring buffer for /undo
    i18n/   Messages.java                                # en/fr loader
  src/main/resources/
    paper-plugin.yml                                     # Folia/Paper plugin manifest
    config.yml                                            # operator defaults
    lang/en.yml                                           # English strings
    lang/fr.yml                                           # French strings

test-plugin/                                          # in-server harness
  src/main/java/net/ekaii/redstone/test/
    TestPluginMain.java
    ParityRunner.java                                  # builds 8 contraptions in vanilla + AC zones
    PerfRunner.java                                     # 32x32 grid bench
    PersistenceRunner.java                              # PDC roundtrip
    StressRunner.java                                   # 600-tick activity
    CrossRegionStressRunner.java                        # 2 regions in parallel
    PerfSweep.java                                      # 4..64 grid sizes sweep, CSV out
    DemoMapBuilder.java                                 # /demo-map for hands-on
    LagMachineBuilder.java                              # /lag-machine for tpsbar test
    contraptions/Contraptions.java                      # 8 redstone test pieces

test-harness/
  Dockerfile.folia + entrypoint.sh + docker-compose.yml
  run-tests.sh                                          # one-shot orchestrator

docs/
  ARCHITECTURE.md   COMMANDS.md      CONFIGURATION.md
  FAQ.md            INSTALL.md        MODES.md
  PERFORMANCE.md    TROUBLESHOOTING.md  USAGE.md

research/
  01-folia-api.md           02-alternate-current.md
  03-hook-techniques.md     04-bluemap-api.md
  05-worldedit-api.md       06-placeholderapi.md
  07-deepwiki-snapshot.md
```

## Coding conventions

- Java 21 records, switch expressions, var. No external code style — I
  follow Mojang's vibe (4-space indent, opening brace on same line).
- Files start with a one-paragraph javadoc explaining *why* the file
  exists. Public APIs get javadoc. Private helpers usually don't.
- Comments are for non-obvious decisions. The 4 load-bearing comments
  are in `EvaluatorSwap`, `AcRedstoneWireEvaluator`, `PaperConfigForcer`,
  `ChunkRegistry` — see those files for examples of the threshold.
- All player-facing strings go through `Messages.tr(key, ...)` — never
  `Component.text("...")` directly except for purely decorative glyphs.

## Submitting a change

1. Run the harness: `./test-harness/run-tests.sh` should be 14/14.
2. If you touch the redstone hot path, attach a perf number from
   `/redstone-region stats` before/after.
3. If you touch i18n, both `lang/en.yml` and `lang/fr.yml` must update.
4. Open a PR; the build CI will reject changes that don't compile or
   that fail the harness.

## Release process

There is no formal release cadence — `main` is always shippable.

- Tag a commit with `v0.X.Y`
- The CI artifact for that commit's build run is the release
- Update `gradle.properties` `version` ahead of the next patch
