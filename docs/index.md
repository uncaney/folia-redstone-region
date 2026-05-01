---
layout: default
title: folia-redstone-region
description: Per-chunk redstone-engine dispatch (vanilla / Alternate Current / Eigencraft / Disabled) for Folia 1.21.11.
---

# folia-redstone-region

> **Per-chunk redstone-engine dispatch for Folia.** Default chunks tick
> vanilla; opt chunks into Alternate Current, Eigencraft, or fully
> disabled with a single command. Persistent across restarts.
> Folia-thread-safe. i18n. ~50 ns hot-path overhead, configurable to 0.

## Quickstart

```
/redstone-region set alternate-current     ← this chunk
/redstone-region fill 8 alternate-current  ← 17×17 chunks around you
/redstone-region selection eigencraft       ← your WorldEdit selection
```

See [Install](INSTALL.md) for drop-in instructions.

## Documentation

- [Install](INSTALL.md) — requirements, drop-in, first-run files
- [Commands](COMMANDS.md) — every sub-command, every argument
- [Configuration](CONFIGURATION.md) — `config.yml` annotated
- [Modes](MODES.md) — vanilla / AC / eigencraft / disabled, when to
  use each, edge cases
- [Performance](PERFORMANCE.md) — overhead breakdown, dust-grid
  scaling table, RAM caps, tick-budget perspective
- [Architecture](ARCHITECTURE.md) — how the NMS hook works, Folia
  thread-safety, design decisions
- [Building from source](BUILDING.md)
- [FAQ](FAQ.md) — "is it safe?", "PaperMC?", "will it break my build?"
- [Troubleshooting](TROUBLESHOOTING.md) — every known error → cause →
  fix
- [Usage](USAGE.md) — short usage cheatsheet

## Status

End-to-end validated on **Folia 1.21.11 build #6** — 14/14 in-server
test cases green:

| Test | Outcome |
|---|---|
| evaluator-installed | ✅ |
| persistence-pdc | ✅ PDC roundtrip |
| perf-32×32-grid | ✅ 2.9-14.2× speedup |
| 8 parity contraptions | ✅ byte-for-byte over 60+ ticks each |
| stress-600t | ✅ 596 toggles, 0 exceptions |
| cross-region-stress | ✅ 780+780 toggles in 2 different Folia regions |
| ac-actually-exercised | ✅ 655 dispatches verified |

## Performance

32×32 dust grid, 50 toggles, M-series Mac + Java 21:

| Metric | Vanilla | AC | Speedup |
|---|---|---|---|
| Avg per toggle | 41 ms | 14 ms | **2.9×** |
| % of one server tick (50 ms) | 82% | 28% | |
| Worst tick | 234 ms (>4 ticks) | 22 ms | **10.6×** |

[Full performance table](PERFORMANCE.md).

## Repos

- Canonical: <https://forgejo.ekaii.fr/exo/folia-redstone-region>
- Mirror: <https://github.com/uncaney/folia-redstone-region>
- bStats: <https://bstats.org/plugin/bukkit/redstone-region/31033>

## License

MIT — see [LICENSE](https://github.com/uncaney/folia-redstone-region/blob/main/LICENSE).
