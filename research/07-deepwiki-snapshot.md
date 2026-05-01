# DeepWiki snapshot — uncaney/folia-redstone-region

Captured: 2026-05-01.
DeepWiki "Last indexed" stamp on every page: **1 May 2026, commit `af74c1df`**.
Wiki refresh notice: "This wiki was recently refreshed. Please wait 7 days to refresh again."

Source: https://deepwiki.com/uncaney/folia-redstone-region

> NOTE: DeepWiki is a JS-rendered SPA; raw `curl` returns ~60 lines of bootstrap HTML. The
> text below was retrieved via the WebFetch tool which renders + flattens the page. It is the
> closest available verbatim text. Diagrams are referenced by name only — DeepWiki renders
> Mermaid diagrams as canvas/SVG that the fetcher does not capture.

Sidebar / table of contents (every linked sub-page):
1. Overview — `/uncaney/folia-redstone-region/1-overview`
2. Getting Started — `/1.1-getting-started`
3. Project Layout — `/1.2-project-layout`
4. Core Architecture — `/2-core-architecture`
5. Plugin Lifecycle & Bootstrap — `/2.1-plugin-lifecycle-and-bootstrap`
6. Evaluator Swap & Dispatching — `/2.2-evaluator-swap-and-dispatching`
7. Chunk Registry & Persistence — `/2.3-chunk-registry-and-persistence`
8. Folia Thread-Safety Model — `/2.4-folia-thread-safety-model`
9. Alternate Current Engine — `/3-alternate-current-engine`
10. AcRedstoneWireEvaluator — `/3.1-acredstonewireevaluator`
11. WireHandler & Internal Data Structures — `/3.2-wirehandler-and-internal-data-structures`
12. Admin Command Reference — `/4-admin-command-reference`
13. Testing & Validation — `/5-testing-and-validation`
14. Test Plugin (In-Server Suite) — `/5.1-test-plugin-(in-server-suite)`
15. Demo Map & Lag Machine — `/5.2-demo-map-and-lag-machine`
16. Test Harness (Docker Runner) — `/5.3-test-harness-(docker-runner)`
17. Build & CI/CD — `/6-build-and-cicd`
18. Gradle Build Configuration — `/6.1-gradle-build-configuration`
19. CI Pipeline (Forgejo Actions) — `/6.2-ci-pipeline-(forgejo-actions)`
20. Research Notes — `/7-research-notes`
21. Folia API Research — `/7.1-folia-api-research`
22. Alternate Current Algorithm Research — `/7.2-alternate-current-algorithm-research`
23. Hook Technique Research — `/7.3-hook-technique-research`
24. Glossary — `/8-glossary`

---

## 1. Overview (`/1-overview`)

> "`folia-redstone-region` is a specialized plugin for Folia 1.21.11 that enables per-chunk
> selection of redstone evaluation engines."

Key claims:
- Targets Folia 1.21.11; addresses Paper-bundled AC not being thread-safe under Folia.
- Provides "thread-local handlers for regional isolation."
- Engine choice persists in chunk `PersistentDataContainer` (PDC).
- Reflective swap of `RedstoneWireEvaluator` field rather than bytecode manipulation.
- Engine dispatch flow table:
  - `DispatchingEvaluator` — extends vanilla evaluator; routes to Vanilla or AC.
  - `ChunkRegistry` — thread-safe in-memory store of per-chunk `RedstoneMode`.
  - `AcRedstoneWireEvaluator` — entry point for ported AC algorithm.
- Sources cited: README.md, docs/ARCHITECTURE.md (lines 102-130, 79-86, 141-143), docs/USAGE.md (lines 98-101).

## 2. Getting Started (`/1.1-getting-started`)

Requirements table:
- Folia 1.21.11 (Build #6+) — "Specific bytecode shape for `RedStoneWireBlock.evaluator`".
- Java 21+.
- `paper-world-defaults.yml` redstoneImplementation must be `vanilla`; the plugin enforces
  `paperConfig.misc.redstoneImplementation = VANILLA` at boot.

Install: `./gradlew :plugin:build` → `plugin/build/libs/plugin-0.1.0-reobf.jar` → drop into `plugins/`.

Listed commands (Quick-Start):
- `/redstone-region info`
- `/redstone-region set <mode>` — modes listed: `vanilla`, `alternate-current`.
- `/redstone-region fill <r> <m>` (radius cap 32).
- `/redstone-region clear [r]`
- `/redstone-region list`

Permission: `redstone-region.admin`.
PDC key: `ekaii:redstone_engine`, type Byte, 0=Vanilla, 1=AC.
Uninstall: stop server, delete jar; `onDisable` reverts the field.

## 3. Project Layout (`/1.2-project-layout`)

- `plugin/`, `test-plugin/`, `test-harness/`, `research/`, `docs/`.
- Java 21, Folia 1.21.11-R0.1-SNAPSHOT, group `net.ekaii.redstone`.
- Mentions `paperweight-userdev`, `foliaDevBundle`, `fastutil` (`Long2ByteOpenHashMap`).

## 4. Core Architecture (`/2-core-architecture`)

- "Proxy" pattern; `DispatchingEvaluator` intercepts the redstone evaluation pipeline.
- "Replaces Minecraft's internal `RedstoneWireEvaluator` (or `RedstoneController` in 1.21.2+ NMS)".
- Four subsystems: Plugin Lifecycle & Bootstrap, Evaluator Swap & Dispatching, Chunk Registry & Persistence, Folia Thread-Safety Model.
- Performance: dispatch overhead "20-30 ns per update" (~10-15 ns `ChunkRegistry.modeOf()`, ~5-10 ns delegation).

## 5. Plugin Lifecycle & Bootstrap (`/2.1-plugin-lifecycle-and-bootstrap`)

- Bootstrap class implements `PluginBootstrap`; registers `/redstone-region` via `LifecycleEvents.COMMANDS`.
- onEnable steps: PaperConfigForcer.forceAll → EvaluatorSwap.install → ChunkSyncListener registration → bind plugin to RedstoneRegionCommand.
- onDisable: `EvaluatorSwap.uninstall()`.
- Entity table: Bootstrap, PaperConfigForcer, EvaluatorSwap, ChunkSyncListener.

## 6. Evaluator Swap & Dispatching (`/2.2-evaluator-swap-and-dispatching`)

- Reflective replacement of `private final evaluator` field in `RedStoneWireBlock`.
- Cites JLS 17.5 + JDK 21 reflection: `Field.set` legal on instance final fields after `setAccessible(true)`.
- `EvaluatorSwap` manages install/uninstall.
- `DispatchingEvaluator` queries `ChunkRegistry` per update.
- `PaperConfigForcer` listens to `WorldInitEvent`/`WorldLoadEvent`, forces `redstoneImplementation = VANILLA`.

## 7. Chunk Registry & Persistence (`/2.3-chunk-registry-and-persistence`)

- `ConcurrentHashMap<ResourceKey<Level>, PerLevel>` of `Long2ByteOpenHashMap`.
- `StampedLock` with optimistic reads.
- **`RedstoneMode` enum claimed to define TWO engines: VANILLA(0), ALTERNATE_CURRENT(1)**.
- `ChunkPdcCodec` under key `ekaii:redstone_engine`.
- `ChunkSyncListener` events: chunk load, chunk unload, world unload.
- `ChunkKey.pack(int,int)` → `long`.

## 8. Folia Thread-Safety Model (`/2.4-folia-thread-safety-model`)

- AcRedstoneWireEvaluator uses `ThreadLocal<Map<ServerLevel, WireHandler>>` with `WeakHashMap`.
- ChunkRegistry uses StampedLock.
- PDC writes scheduled via RegionScheduler.
- Stats use `ConcurrentHashMap`.

## 9. Alternate Current Engine (`/3-alternate-current-engine`)

- Adapted from SpaceWalkerRS/alternate-current; graph-based BFS replacement of vanilla recursion.
- Component table: AcRedstoneWireEvaluator (singleton), WireHandler (per Thread×Level), WireNode, WireConnection, PriorityQueue.
- Three perf techniques: intrusive data structures, neighbor caching, deterministic update order via 4-bit flowIn bitmask.

## 10. AcRedstoneWireEvaluator (`/3.1-acredstonewireevaluator`)

- Demuxes `updatePowerStrength` to `onWireAdded` / `onWireRemoved` / `onWireUpdated`.
- `ConcurrentHashMap` named `stats` with keys "added", "removed", "updated".
- Bypasses `Level.setBlock`; uses `LevelHelper.setWireState` and `level.getChunkSource().blockChanged(pos)`.

## 11. WireHandler & Internal Data Structures (`/3.2-wirehandler-...`)

- Classes: Node, WireNode, WireConnection, WireConnectionManager.
- Phases: graph build, power spread (priority queue 0-15), update dedupe / determinism.
- Queues: PriorityQueue (O(1)) and SimpleQueue (FIFO via `next_wire`).
- UpdateOrder strategies: `HORIZONTAL_FIRST_OUTWARD`, `HORIZONTAL_FIRST_INWARD`. Addresses MC-11193.

## 12. Admin Command Reference (`/4-admin-command-reference`)

**Five subcommands listed: `info`, `set`, `fill`, `clear`, `list`. No others.**
- All require `redstone-region.admin`.
- radius `IntegerArgumentType.integer(0, 32)`.
- `set` modes documented: `vanilla` and `alternate-current` only.

## 13. Testing & Validation (`/5-testing-and-validation`)

- In-server tests in `test-plugin`, autoRun via `folia-redstone-region.autoRun` system property.
- ParityRunner, CrossRegionStressRunner, PerfRunner, PersistenceRunner.
- Manual commands: `/demo-map`, `/lag-machine`, `/perf-sweep`.
- Docker harness watches `test-results/ready` marker, parses JUnit XML.

## 14. Test Plugin (In-Server Suite) (`/5.1`)

- `folia-redstone-region.autoRun` default true; 100-tick startup delay; commands `/demo-map`, `/lag-machine`, `/perf-sweep`.
- ParityRunner samples 60 ticks.
- PerfRunner toggles 32x32 dust grid 50 times; PerfSweep iterates 4×4 → 64×64 → `perf-sweep.csv`.
- CrossRegionStressRunner places two AC grids 4096 blocks apart.
- PersistenceRunner write/clear/reload through PDC.
- Contraptions: DustLine30, RepeaterClock4, ComparatorSubtractor, TorchLadder.
- Output: `test-results/junit.xml`, marker `test-results/ready`.

## 15. Demo Map & Lag Machine (`/5.2`)

- `/demo-map` builds 5 lanes (lanes 0-4: 16×16 vanilla mat, 16×16 AC mat, 32-line, repeater clock, zigzag).
- `/lag-machine` builds two machines ~170 blocks apart: 64×64 dust mat (4096 wires), 8 repeater clocks, 16 pistons, 32×32 torch ladder.
- Files: DemoMapBuilder.java, DemoMapCommand.java, LagMachineBuilder.java, LagMachineCommand.java.

## 16. Test Harness (Docker Runner) (`/5.3`)

- Base image `eclipse-temurin:21-jdk-alpine`; EULA accepted; `online-mode=false`, `level-type=flat`; RCON 25575.
- `entrypoint.sh` downloads via `FOLIA_BUILD_URL`.
- Volumes: `./server/folia.jar`, `./server/plugins`, `./server/test-results`.
- Exit codes 2 (no Java 21), 3 (missing jars), 4 (server died), 5 (timeout 180s), 6 (JUnit failures).

## 17. Build & CI/CD (`/6`)

- Three modules: `:plugin`, `:test-plugin`, `:test-harness`.
- `paperweight-userdev` for mojang-mapped dev → reobf production jars.
- Forgejo Actions on push to main + PRs; uploads `folia-redstone-region` and `folia-redstone-region-tests`.

## 18. Gradle Build Configuration (`/6.1`)

- mavenCentral + PaperMC repos. Group `net.ekaii.redstone`, version `0.1.0`.
- Reobf target `MOJANG_PRODUCTION`. Fastutil compileOnly.

## 19. CI Pipeline (`/6.2`)

- Triggers: push to main, PR open/update, workflow_dispatch.
- Runner: ubuntu-latest, container `node:20-bookworm`, JDK temurin 21.
- Steps: checkout → Java 21 → cache Gradle → `:plugin:build` and `:test-plugin:build` with `--no-daemon` → upload artifacts.

## 20-23. Research Notes / Folia API / AC Algorithm / Hook Technique (`/7…/7.3`)

Folia API (7.1): MC 1.21.11, Java 21, paperweight 2.0.0-beta.21, dev bundle 1.21.11-R0.1-SNAPSHOT. Schedulers: RegionScheduler, GlobalRegionScheduler, EntityScheduler, AsyncScheduler. Brigadier in Bootstrap phase via `LifecycleEventManager`.

AC Algorithm (7.2): five invariants; `ThreadLocal<Map<ServerLevel, WireHandler>>` per region thread; entry points onWireAdded/Removed/Updated; "deterministic behavior through mixin-based direction forcing in 1.21.11" (note: phrasing dubious).

Hook Technique (7.3): three options evaluated.
- A: Reflective field swap (selected) — `private final evaluator` of `Blocks.REDSTONE_WIRE`. **However the page concludes by saying the implementation swaps `redstoneController` field** — DeepWiki here is internally inconsistent (mixes the legacy "controller" name from the research doc with the actual code's "evaluator").
- B: Bytecode redefinition (rejected).
- C: Registry replacement (rejected — `BlockState.owner` back-reference).

## 24. Glossary (`/8`)

- AC, DispatchingEvaluator, Evaluator Swap (Hook), Redstone Mode (**states "VANILLA(0) and ALTERNATE_CURRENT(1)" — only two**), ChunkKey.
- Thread-Safety: `ThreadLocal<Map<ServerLevel, WireHandler>>`.
- PDC key: `ekaii:redstone_engine`.
- Abbreviations: NMS, PDC, AC, JLS, CAS.

---

# ACCURACY AUDIT

DeepWiki was indexed against commit `af74c1df` on 1 May 2026 — the same date as today. So
"freshness" is essentially current. The discrepancy is then about *coverage of features that
exist in the source tree at that commit*.

| # | Question | DeepWiki | Source tree truth | Status |
|---|---|---|---|---|
| 1 | All 14+ commands listed? | Lists only 5 (info, set, fill, clear, list) | 14 in `RedstoneRegionCommand.root(...)` lines 41-86 + `help` literal: `info, set, fill, clear, list, where, map, stats` (with `stats reset` + `stats <limit>`), `check, profile, selection, undo, why, reload, help`. en.yml carries help.cmd-info/-set/-fill/-clear/-list/-where/-map/-stats/-stats-reset/-check/-profile/-selection/-undo/-why/-reload (14 distinct commands). | **STALE — major undercount** (9 of 14 commands missing: where, map, stats, check, profile, selection, undo, why, reload) |
| 2 | All 4 modes (vanilla / alternate-current / eigencraft / disabled)? | Documents only **vanilla** + **alternate-current**. RedstoneMode glossary says: "VANILLA(0), ALTERNATE_CURRENT(1)". Suggests modes only in `set <mode>` as those two. | `RedstoneMode.java` is `{VANILLA(0), ALTERNATE_CURRENT(1), EIGENCRAFT(2), DISABLED(3)}`. `paper-plugin.yml` description: "(vanilla / alternate-current / eigencraft / disabled)". `EigencraftWireEvaluator` and `DisabledWireEvaluator` exist in `nms/`. `DispatchingEvaluator` ctor takes 4 evaluators. config.yml `default-mode` documents all 4. Suggestion list in command suggests all 4. | **STALE — modes 2 and 3 entirely absent from DeepWiki** |
| 3 | Integrations listed? | None of: BlueMap, WorldEdit, PlaceholderAPI, Discord webhook, sign opt-in, audit log, auto-AC, timing policy. DeepWiki mentions only EvaluatorSwap, ChunkRegistry, ChunkSyncListener. | Source tree has `bridge/{BlueMapBridge, WorldEditBridge, PlaceholderApiBridge, DiscordWebhook}.java`, `auto/AutoAcScanner.java`, `audit/AuditLog.java`, `timing/{TimingPolicy, ChunkTimingTable}.java`, `listeners/SignOptInListener.java`, plus bStats. paper-plugin.yml soft-deps on BlueMap, PlaceholderAPI, WorldEdit; permission `redstone-region.sign`. config.yml has `sign:`, `audit:`, `auto-ac:`, `timing:`, `discord:` blocks. | **MISSING — entire ~50% of plugin surface area is undocumented** |
| 4 | Folia thread-safety story (per-thread WireHandler) and "technique-F" evaluator-swap hook? | Thread-safety: correctly described — `ThreadLocal<Map<ServerLevel, WireHandler>>` with WeakHashMap, per (Thread,Level). Hook: page 7.3 calls the chosen technique "Option A: Reflective Field Swap" and says "swapping the `redstoneController` field." The `research/03-hook-techniques.md` doc uses the label **technique F** and calls the field `redstoneController`. The actual code (`EvaluatorSwap.java`, line 39) reflects field name `evaluator` of type `RedstoneWireEvaluator`. Glossary Evaluator-Swap entry correctly says "`private final` field `evaluator`". | Per-thread WireHandler description: **CORRECT**. Hook description: **partially WRONG / inconsistent** — DeepWiki never uses the label "technique F" used internally by the research doc; and page 7.3 alone uses the obsolete field name `redstoneController` (the research doc's name) instead of the actually-deployed `evaluator` (1.21.11 NMS). Glossary, Overview, Getting Started, and Evaluator Swap pages do say `evaluator`, so DeepWiki is internally inconsistent. | **MOSTLY CORRECT, with one wrong field name on /7.3 and missing the "technique F" label** |
| 5 | i18n (en/fr lang files) and chunkLink click-to-tp? | No mention anywhere. | `plugin/src/main/resources/lang/en.yml` and `lang/fr.yml` (149 lines each). `Messages.java` reload(plugin, code, log), supports operator override at `plugins/folia-redstone-region/lang/<code>.yml`. `chunkLink(world,cx,cz)` returns Adventure component with `ClickEvent.runCommand("/tp <world> <wx> 80 <wz>")`. config.yml has `language: en` and notes "Available: en, fr." | **MISSING — entirely undocumented** |
| 6 | Timing-policy modes (all / sample / non-vanilla-only / off)? | No mention. The `/stats` command itself isn't even documented (see row 1). | `TimingPolicy.java` enum `Mode { ALL, SAMPLE, NON_VANILLA_ONLY, OFF }`. config.yml documents all four with overhead notes. PluginMain wires `setTimingPolicy(policy)` into the dispatcher; dispatcher hot path has 4-arm switch. | **MISSING** |
| 7 | Factual errors (wrong API names, packages, commit refs)? | (a) /4 says `set` modes are only `vanilla`/`alternate-current` — wrong: 4 valid modes. (b) /7.3 says "swap the `redstoneController` field" — wrong: in deployed source the field is `evaluator` of type `RedstoneWireEvaluator`. (c) /7.2 phrase "deterministic behavior through mixin-based direction forcing in 1.21.11" — misleading: this plugin uses no Mixin (research doc rejects Mixin/ByteBuddy in favor of reflection, and `build.gradle.kts` confirms no Mixin dep). (d) /2 says "replaces … `RedstoneWireEvaluator` (or `RedstoneController` in 1.21.2+ NMS)" — backwards: `RedstoneWireEvaluator` IS the 1.21.2+ name. (e) Commit `af74c1df` referenced consistently — matches what DeepWiki was indexed from. (f) /1.1 "Specific bytecode shape for `RedStoneWireBlock.evaluator`" — name and class match source exactly. (g) /5.1 says autoRun "default true" — needs source check; DeepWiki may be wrong. | Confirmed: source uses `evaluator` everywhere; no Mixin or ByteBuddy on classpath; 4 evaluator implementations exist. Errors (a)-(d) all real. | **WRONG** on the 4 items listed |

## Per-section status table

| Section | Status | Notes |
|---|---|---|
| 1 Overview | up-to-date | Describes only 2-mode model; doesn't claim more, so technically stale-by-omission only. |
| 1.1 Getting Started | stale | `set <mode>` shown with only 2 modes; ignores `eigencraft`/`disabled`. PDC byte description "0 or 1" wrong: also 2, 3. |
| 1.2 Project Layout | up-to-date | Module list matches. |
| 2 Core Architecture | mostly up-to-date | Wrong direction in "RedstoneWireEvaluator (or RedstoneController in 1.21.2+ NMS)" — the latter is the older name, not newer. |
| 2.1 Lifecycle & Bootstrap | up-to-date | Matches PluginMain.java onEnable order roughly. Misses bridges, audit, timing, autoAc init steps 3-11. |
| 2.2 Evaluator Swap | up-to-date | Field name correct, JLS 17.5 reasoning correct. |
| 2.3 Chunk Registry | stale | RedstoneMode listed as 2-state. Matches StampedLock + Long2ByteOpenHashMap. |
| 2.4 Folia Thread-Safety | up-to-date | Per-thread WireHandler model correctly stated. |
| 3 AC Engine | up-to-date | Algorithm summary matches WireHandler.java structure. |
| 3.1 AcRedstoneWireEvaluator | up-to-date | Correct demux + ConcurrentHashMap stats. |
| 3.2 WireHandler & Data Structures | up-to-date | Class set matches `ac/` package. |
| 4 Admin Command Reference | **stale (severe)** | Only 5 of 14 commands documented. No mention of `/redstone-region selection` (WorldEdit), `/redstone-region why` (PowerTrace), `/redstone-region map` (ASCII map), `/redstone-region stats`, `/redstone-region check`, `/redstone-region profile`, `/redstone-region undo`, `/redstone-region where`, `/redstone-region reload`. |
| 5 Testing & Validation | up-to-date | |
| 5.1 Test Plugin | up-to-date | Runner names match. |
| 5.2 Demo Map & Lag Machine | up-to-date | |
| 5.3 Test Harness | up-to-date | |
| 6 Build & CI/CD | up-to-date | |
| 6.1 Gradle | up-to-date | |
| 6.2 CI Pipeline | up-to-date | |
| 7 Research Notes | up-to-date | |
| 7.1 Folia API Research | up-to-date | |
| 7.2 AC Algorithm Research | mostly up-to-date | "mixin-based direction forcing" claim is wrong — no mixins are used. |
| 7.3 Hook Technique Research | **wrong** | Calls the field `redstoneController` once. Field is `evaluator`. Also doesn't use the "technique F" label that the research markdown uses internally. |
| 8 Glossary | stale | `RedstoneMode` listed as 2-state; missing EIGENCRAFT and DISABLED. |

## Top inaccuracies (factual errors)

1. **Mode count: only 2 of 4 documented.** Glossary, /2.3, /1.1, /4 all describe RedstoneMode as `{VANILLA, ALTERNATE_CURRENT}` (bytes 0/1). Source: `RedstoneMode.java` lines 11-15 has 4 entries `{VANILLA(0), ALTERNATE_CURRENT(1), EIGENCRAFT(2), DISABLED(3)}`. `paper-plugin.yml` description string explicitly lists all four.
2. **Command count: 5 of 14 documented.** /4 misses `where, map, stats (+ stats reset), check, profile, selection, undo, why, reload`. Source: `RedstoneRegionCommand.root(...)` and `lang/en.yml` keys `help.cmd-*`.
3. **/7.3 says the swap target is `redstoneController`.** Source: `EvaluatorSwap.java` line 39 reflects on field `evaluator`; class `RedstoneWireEvaluator` (not `RedstoneController`).
4. **/7.2 claims "mixin-based direction forcing".** No Mixin/ByteBuddy on classpath; `build.gradle.kts` confirms; `research/03-hook-techniques.md` explicitly rejects Mixin in favor of pure reflection.
5. **/2 inverts the rename history.** Says `RedstoneController` is the 1.21.2+ name; in fact `RedstoneWireEvaluator` is the 1.21.2+ name (and what the source tree imports).

## Top missing topics

1. **All optional integrations**: BlueMap markers, WorldEdit selection bridge, PlaceholderAPI, Discord webhook.
2. **Sign opt-in feature** with `redstone-region.sign` permission and `[ac]/[vanilla]/[eigencraft]/[disabled]` magic line-0 syntax (capped by `sign.max-radius`).
3. **Audit log** — JSONL file with daily roll, source enum {COMMAND, SIGN, AUTO_AC, WORLDEDIT, BLUEMAP, OTHER}.
4. **Auto-AC scanner** — scans timing table; auto-flips chunks above `ms-per-update-threshold`; optional auto-revert.
5. **Timing policy modes** (`all`, `sample`, `non-vanilla-only`, `off`) with sample-rate, including the constraint that auto-AC refuses to start when policy is `off` or `non-vanilla-only`.
6. **i18n** — `lang/en.yml` and `lang/fr.yml`, override path `plugins/folia-redstone-region/lang/<code>.yml`, `Messages` class.
7. **Clickable `chunkLink`** — every chunk reference in chat is wrapped with `ClickEvent.runCommand("/tp …")` so operators teleport with one click.
8. **bStats integration** with custom charts (language, default_mode, sign_enabled, auto_ac_enabled, discord_enabled, non_vanilla_chunks).
9. **`/redstone-region reload`** live-reload semantics: re-reads config + lang, refreshes Discord webhook and timing policy, but does NOT touch the evaluator swap or registry.
10. **`/redstone-region map` ASCII mini-map** (north-up, 2 chars/chunk, color-coded per mode).
11. **`/redstone-region why <x> <y> <z>`** wire-power explanation command.
12. **`UndoBuffer`** — per-sender batched undo for set/fill/selection.
13. **`PaperConfigForcer`** is documented but the page does not surface that the plugin reapplies on `WorldInitEvent` and `WorldLoadEvent`, which is operationally important for new dimensions.

---

# VERDICT

**DeepWiki is ~60% accurate as of 2026-05-01 (commit `af74c1df`).**

The architectural skeleton (DispatchingEvaluator, ChunkRegistry with StampedLock, per-thread
WireHandler via ThreadLocal+WeakHashMap, reflective swap of `RedStoneWireBlock.evaluator`,
Folia regional threading model, Gradle/paperweight build, Forgejo CI) is captured correctly.
The detailed AC engine internals (WireNode, PriorityQueue, UpdateOrder, MC-11193, intrusive
queues) match the source.

**Top inaccuracies:**
1. RedstoneMode is documented as 2-state but is 4-state (`EIGENCRAFT`, `DISABLED` missing).
2. Admin command reference lists 5 of 14 commands.
3. `/7.3` calls the swap target field `redstoneController`; the deployed code uses `evaluator`.
4. `/7.2` claims "mixin-based direction forcing"; no mixins are used.
5. `/2` reverses the NMS rename history (`RedstoneWireEvaluator` is the newer name, not the older).

**Top missing topics:**
1. Every optional integration: BlueMap, WorldEdit, PlaceholderAPI, Discord webhook.
2. Sign opt-in (`redstone-region.sign`).
3. Audit log (JSONL, daily roll).
4. Auto-AC scanner + threshold.
5. Timing policy (`all`/`sample`/`non-vanilla-only`/`off`) and its overhead model.
6. i18n (en/fr lang files, operator override path) and clickable `chunkLink` teleport.
7. bStats charts.
8. Live-reload command and its semantics.
9. Half the command tree (`map`, `stats`, `check`, `profile`, `selection`, `undo`, `why`,
   `where`, `reload`).
