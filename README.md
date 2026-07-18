# folia-redstone-region

[![build](https://forgejo.ekaii.fr/exo/folia-redstone-region/actions/workflows/build.yml/badge.svg)](https://forgejo.ekaii.fr/exo/folia-redstone-region/actions)
[![codeql](https://github.com/uncaney/folia-redstone-region/actions/workflows/codeql.yml/badge.svg)](https://github.com/uncaney/folia-redstone-region/actions/workflows/codeql.yml)
[![bstats](https://img.shields.io/badge/bStats-31033-blue)](https://bstats.org/plugin/bukkit/redstone-region/31033)
[![license](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![mc](https://img.shields.io/badge/Minecraft-26.2-brightgreen)](#requirements)

> **Per-chunk redstone-engine dispatch for Folia.** Default chunks tick
> vanilla; opt chunks into Alternate Current, Eigencraft, or fully
> disabled with a single command. Persistent across restarts.
> Folia-thread-safe. i18n. ~50 ns hot-path overhead, configurable to 0.

```
/redstone-region set alternate-current     ← this chunk
/redstone-region fill 8 alternate-current  ← 17×17 chunks around you
/redstone-region selection eigencraft       ← your WorldEdit selection
```

## What it does

Vanilla redstone is slow and recursive. Mojang exposed an override
point in 1.21.2 (`RedStoneWireBlock.evaluator`) that the plugin
swaps at boot for a dispatcher. Each wire update consults a per-chunk
flag and calls one of four engines:

- **vanilla** — stock Mojang behaviour (default)
- **alternate-current** — Space Walker's BFS algorithm, **2-15× faster**
- **eigencraft** — theosib's RedstoneWireTurbo, 3-5× faster, milder
  edge cases than AC
- **disabled** — the wire never recomputes (archive zones)

Per-chunk choice means you flip *only* the salle des coffres or the
mega-base and leave the rest of the world untouched.

## Status

End-to-end validated on Folia 1.21.11 build #6 — **14/14 in-server
test cases green**, including byte-for-byte parity on 8 contraptions
(dust line, dust grid, repeater clock, AND gate, comparator subtract,
torch ladder, torch inverter, dust zigzag), persistence roundtrip, 30s
stress, cross-region stress (2 Folia regions in parallel, 0
exceptions), and a counter-based assertion that the AC code path is
actually exercised.

| Test | Outcome |
|---|---|
| evaluator-installed | ✅ |
| persistence-pdc | ✅ |
| perf-32×32-grid | ✅ ~3-15× speedup |
| 8 parity contraptions | ✅ byte-for-byte over 60+ ticks each |
| stress-600t | ✅ 596 toggles, 0 exceptions |
| cross-region-stress | ✅ 780+780 toggles in different Folia regions |
| ac-actually-exercised | ✅ 655 dispatches verified |

## Performance

32×32 dust grid, 50 toggles, M-series Mac + Java 21:

| Metric | Vanilla | AC | Speedup |
|---|---|---|---|
| Avg per toggle | 41 ms | 14 ms | **2.9×** |
| % of one server tick (50 ms) | 82% | 28% | |
| Worst tick | 234 ms (>4 ticks) | 22 ms | **10.6×** |

Larger grids show wider gaps. Full sweep + tick-budget table in
[`docs/PERFORMANCE.md`](docs/PERFORMANCE.md).

## Features

- **Per-chunk redstone mode** with PDC persistence, 4 modes
- **Auto-AC threshold scanner** (opt-in) — flips hot vanilla chunks
  automatically based on configured threshold
- **Sign opt-in** `[ac]` `[vanilla]` `[eigencraft]` `[disabled]` with
  config-capped radius
- **`/redstone-region selection`** integrates with WorldEdit
- **`/redstone-region undo`** (64-batch ring) reverses any
  `/set`/`/fill`/`/selection`/sign/auto-AC
- **`/redstone-region stats`** top-N hottest chunks with `[click to /tp]`
- **`/redstone-region profile`** combined diagnostic + recommendation
  engine (mode + components + timing → suggested action)
- **`/redstone-region check`** scans for piston/observer combos that
  may break under AC
- **`/redstone-region why x y z`** explains a wire's current power by
  enumerating its 6 neighbors' contributions
- **`/redstone-region map`** ASCII mini-map (square cells in chat font)
- **`/redstone-region reload`** hot-reloads `config.yml` + language
  bundle without restarting
- **Audit log** (JSONL, daily rolled) of every flip with actor + UUID
- **Discord webhook** (opt-in) async notifications on flip
- **PlaceholderAPI** provider (`%redstone-region_mode%`, counts per mode)
- **BlueMap markers** (soft-dep) — colored chunk rectangles overlay
- **i18n** — English + French built-in, drop your own
  `lang/<code>.yml` to add more
- **Configurable timing instrumentation** (all / sample / non-vanilla-only
  / off) — overhead from ~50 ns down to 0
- **bStats** anonymous metrics (plugin id 31033)

## Quick install

```sh
# Download the latest plugin-*-reobf.jar from your CI of choice, then:
mv plugin-0.1.0-reobf.jar /your-server/plugins/folia-redstone-region.jar
# Restart server. Verify the boot log shows:
#   [folia-redstone-region] redstone evaluator swapped: ... -> DispatchingEvaluator
```

Full install + first-run files + integrations: [`docs/INSTALL.md`](docs/INSTALL.md).

## Quick reference

| Command | What |
|---|---|
| `/redstone-region info` | Mode of the chunk you're on |
| `/redstone-region set <mode>` | Apply mode to current chunk |
| `/redstone-region fill <r> <mode>` | Apply to (2r+1)² chunks |
| `/redstone-region selection <mode>` | Apply to your WorldEdit selection |
| `/redstone-region undo` | Reverse the last batch |
| `/redstone-region clear [r]` | Reset to vanilla |
| `/redstone-region list` | Counts per dimension |
| `/redstone-region where` | List of non-vanilla chunks (clickable) |
| `/redstone-region map [r]` | ASCII mini-map |
| `/redstone-region stats [N]` | Top-N hottest chunks |
| `/redstone-region check` | Risky-pattern scan |
| `/redstone-region profile` | Full chunk diagnostic |
| `/redstone-region why x y z` | Explain wire power |
| `/redstone-region reload` | Re-load config + lang |
| `/redstone-region help` | In-game help in your language |

Full reference: [`docs/COMMANDS.md`](docs/COMMANDS.md).

## Documentation

- [INSTALL](docs/INSTALL.md) — requirements, drop-in, first-run files
- [COMMANDS](docs/COMMANDS.md) — every sub-command, every argument
- [CONFIGURATION](docs/CONFIGURATION.md) — `config.yml` annotated
- [MODES](docs/MODES.md) — vanilla / AC / eigencraft / disabled, when
  to use each, edge cases
- [PERFORMANCE](docs/PERFORMANCE.md) — overhead numbers, scaling, RAM
- [ARCHITECTURE](docs/ARCHITECTURE.md) — how the NMS hook works,
  Folia thread-safety, design decisions
- [BUILDING](docs/BUILDING.md) — from source
- [FAQ](docs/FAQ.md) — answers to "is it safe?", "will it break my
  build?", "Paper without Folia?"
- [TROUBLESHOOTING](docs/TROUBLESHOOTING.md) — common errors and fixes

## Requirements

- **MC 26.2**: Folia 26.2 (`ver/26.2.x`), Paper 26.2 (build 62+) and
  Purpur 26.2 boot-tested green — grab `RedstoneRegions-0.2.3+26.2.jar`.
  Luminol/Lophine 26.2 forks tested working. Requires **Java 25+**.
- **MC 1.21.11**: Folia build #6+ (Luminol 1.21.11 and other Folia
  forks tested working) — use `RedstoneRegions-0.2.2.jar`, Java 21+.
- Optional integrations:
  - **BlueMap** 5.16+ (chunk overlay)
  - **PlaceholderAPI** 2.11.7+ (placeholders)
  - **WorldEdit** 7.3.19+ Folia-patched preferred (selection support)

The plugin gracefully no-ops when an integration is missing.

## Repositories

- Canonical: <https://forgejo.ekaii.fr/exo/folia-redstone-region>
- Mirror: <https://github.com/uncaney/folia-redstone-region>
- Wiki: see the `docs/` folder (rendered the same way on both
  Forgejo and GitHub)
- Issues / PRs: GitHub
- Security reports: see [`SECURITY.md`](SECURITY.md)

## License

Plugin glue: **MIT** (see [`LICENSE`](LICENSE)).
Bundled Alternate-Current engine: MIT (Space Walker — see
`plugin/src/main/java/net/ekaii/redstone/region/ac/LICENSE`).
Bundled bStats: LGPLv3, relocated under
`net.ekaii.redstone.bstats` to avoid collisions.

## Acknowledgments

- **Space Walker** ([SpaceWalkerRS/alternate-current](https://github.com/SpaceWalkerRS/alternate-current))
  for the AC algorithm and a clean reference implementation we vendored.
- **theosib** for RedstoneWireTurbo (Eigencraft) — exposed by Paper as
  the `eigencraft` mode.
- **PaperMC / Folia team** for upstream that exposes the
  `RedstoneWireEvaluator` override point in the first place.
- **Mojang** for the 1.21.2 refactor that turned this from a
  registry-surgery problem into a one-field-swap.
