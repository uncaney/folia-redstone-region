# Configuration

`plugins/folia-redstone-region/config.yml` is created on first boot
with the defaults below. Most values can be edited and applied live via
`/redstone-region reload` — exceptions noted.

```yaml
# === Language ===
language: en   # available: en, fr (drop your own lang/<code>.yml to add more)

# === Default mode ===
default-mode: vanilla   # what new chunks (no PDC entry) inherit

# === Sign opt-in ===
sign:
  enabled: true
  max-radius: 4         # cap on the radius any [ac] sign can apply
  permission: redstone-region.sign

# === Audit log ===
audit:
  enabled: true
  path: audit.jsonl     # path under plugins/folia-redstone-region/
  rotate-keep: 14       # how many daily-rolled files to keep

# === Auto-AC threshold ===
auto-ac:
  enabled: false                      # opt-in
  scan-interval-ticks: 200            # 10 s at 20 TPS
  ms-per-update-threshold: 4.0        # vanilla chunks above this go to AC
  min-samples: 50                     # don't flip until we've measured at least this many updates
  auto-revert: false                  # also undo when an AC chunk drops below threshold/2

# === AC update order ===
alternate-current:
  default-update-order: horizontal-first-outward
  # other values: horizontal-first-inward, vertical-first-outward, vertical-first-inward

# === Timing instrumentation ===
timing:
  mode: all             # all | sample | non-vanilla-only | off
  sample-rate: 1        # used only when mode=sample (1 = like 'all')

# === Discord webhook ===
discord:
  enabled: false
  webhook-url: ""       # required when enabled=true
  filter: all           # all | manual | audit
```

## Detailed sections

### `language`

The plugin ships English (`lang/en.yml`) and French (`lang/fr.yml`).
To override or add a language, drop a yaml file in
`plugins/folia-redstone-region/lang/<code>.yml` — it takes precedence
over the bundled one. Strings use `{name}` placeholders and Bukkit `§`
color codes.

### `default-mode`

The mode used when a chunk has never had `/redstone-region set` applied
to it. Default `vanilla`. Setting this to `alternate-current` makes AC
the global default; individual chunks can still be flipped back via
commands.

### `sign`

When enabled, players with the `permission` can place a sign whose
first line is `[ac]` / `[vanilla]` / `[eigencraft]` / `[disabled]`.
Optional second-line numeric radius is capped by `max-radius`. Set
`max-radius: 0` to make signs only affect their own chunk.

### `audit`

Append-only JSONL log of every flip:

```jsonl
{"ts":1777647845389,"src":"COMMAND","actor":"ExoRamC","actorUuid":"4c24…","dim":"minecraft:overworld","cx":-1301,"cz":267,"prev":"vanilla","next":"alternate-current","reason":"command"}
```

Sources: `COMMAND`, `SIGN`, `AUTO_AC`, `WORLDEDIT`, `BLUEMAP`, `OTHER`.
Rolled at midnight; old files kept up to `rotate-keep`. Tail or `jq` it
to answer "who flipped chunk X when?".

### `auto-ac`

Opt-in background scanner. Every `scan-interval-ticks`, looks at the
top 64 hottest chunks; vanilla chunks whose average ms-per-update
exceeds `ms-per-update-threshold` (and have ≥ `min-samples` updates
recorded) get auto-flipped to alternate-current. With `auto-revert:
true`, AC chunks that drop below `threshold/2` get reverted to vanilla.

Auto-AC requires `timing.mode = all` or `sample` (it needs visibility
on vanilla chunks to detect them as hot). With `non-vanilla-only` or
`off`, it refuses to start and logs a warning.

### `alternate-current.default-update-order`

The order in which the AC algorithm propagates power updates around a
wire. Four upstream orders, all functionally equivalent for compliant
contraptions but differ on the ones that depend on a specific order
(rare). Leave on the default unless you're debugging a specific build.

### `timing`

Controls the cost/benefit of the per-call instrumentation that powers
`/stats`, `/profile`, and the auto-AC scanner.

| `mode` | Per-update overhead | Usable for /stats? | Usable for auto-AC? |
|---|---|---|---|
| `all` | ~50 ns | full | yes |
| `sample` (rate=N) | ~50/N ns | scaled estimate | yes (slightly noisier) |
| `non-vanilla-only` | ~50 ns on flipped chunks, 0 on vanilla | partial | **no** (refuses to start) |
| `off` | 0 ns | empty | **no** (refuses to start) |

For most servers `all` is the right default — 50 ns × even 10 000 wire
updates per tick = 0.5 ms which is well below a tick budget. Only flip
to `sample` (rate=10 or 100) on servers with truly enormous redstone
loads where every nanosecond counts.

### `discord`

Async webhook for every flip event. `filter`:

- `all` (default) — every flip including auto-AC.
- `manual` — only command + WorldEdit selection flips (skip auto-AC noise).
- `audit` — only sign + auto-AC flips (skip command flips).

Rate-limited to ~5 req/s; queue capped at 1024 events with oldest-drop
under sustained burst.

## What requires a restart vs `/reload`

| Change | Restart needed? |
|---|---|
| `language` | No — `/reload` switches it live |
| `default-mode` | No |
| `sign.*`, `audit.*`, `auto-ac.*`, `discord.*`, `timing.*` | No — all picked up by `/reload` |
| Anything in the plugin jar (code change) | **Yes** — the NMS evaluator swap can't be safely undone live |

`/reload` re-creates the Discord worker, re-applies the timing policy,
re-reads sign limits, and reloads the language bundle. It does NOT
touch `ChunkRegistry` (your AC chunks stay AC) and does NOT re-install
the evaluator (the swap is one-shot per JVM).
