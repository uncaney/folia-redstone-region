# Command reference

All commands live under `/redstone-region` and require the
`redstone-region.admin` permission (default op-only). Bare
`/redstone-region` and `/redstone-region help` print the in-game help
in your configured language (`config.yml: language: en|fr`).

## Per-chunk control

| Command | Effect |
|---|---|
| `/redstone-region info` | Print the mode (vanilla / alternate-current / eigencraft / disabled) of the chunk you're standing on, with a clickable `[click to /tp]` link. |
| `/redstone-region set <mode>` | Set the current chunk to `<mode>`. |
| `/redstone-region fill <radius> <mode>` | Apply `<mode>` to a `(2r+1)×(2r+1)` chunk square centred on you. `radius` ≤ 32 (so up to 65×65 chunks = 1040×1040 blocks). |
| `/redstone-region clear` | Reset the current chunk to vanilla. |
| `/redstone-region clear <radius>` | Reset a square area to vanilla. |
| `/redstone-region selection <mode>` | Apply `<mode>` to every chunk overlapping your **WorldEdit** selection. Requires WE installed. |
| `/redstone-region undo` | Reverse the most recent batch made by *you* (or anyone, if op'd) — works for `set`, `fill`, `selection`, sign-flips, auto-AC. Holds 64 batches in a ring. |

## Discovery

| Command | Effect |
|---|---|
| `/redstone-region list` | Per-dimension count of non-vanilla chunks. |
| `/redstone-region where` | Detailed list of every non-vanilla chunk in your current world, with chunk coords, block bounds, and a clickable tp link. Truncated at 50 entries. |
| `/redstone-region map [radius]` | ASCII mini-map centred on you. Each chunk = 2 chars wide × 1 line tall (roughly square in MC chat). Z-coords on the left every 4 lines, X-coords scale at the bottom. Default radius = 8 (17×17 chunks); max = 32 (65×65). |

## Performance & diagnostics

| Command | Effect |
|---|---|
| `/redstone-region stats [limit]` | Top-N hottest chunks by total redstone time. Each row shows chunk coords (clickable to tp), world block bounds, count of recomputes, average time per recompute, worst single recompute, cumulative total. Default limit = 10. |
| `/redstone-region stats reset` | Clear all timing counters. |
| `/redstone-region check` | Scan the current chunk for piston / observer / dust mixes that may break under Alternate Current. Prints a verdict: ✓ AC-friendly, ⚠ risky, or empty. |
| `/redstone-region profile` | Combined diagnostic of the current chunk: header (mode + dim + bounds + clickable tp) → component count → timing stats with "% of a 50ms tick budget" annotation → recommendation engine that interprets all of the above. |
| `/redstone-region why <x> <y> <z>` | Explain a wire's current power: dump each of its 6 neighbors (N/S/E/W/↑/↓) with type + power, identify the max contributor, flag any discrepancy with the expected `max(neighbor) - 1` formula. Use F3 in-game to copy block coords. |

## Ops / lifecycle

| Command | Effect |
|---|---|
| `/redstone-region reload` | Re-read `config.yml` and the language bundle. Live-applies: language, sign max-radius, audit on/off, auto-AC threshold, Discord URL/filter, timing policy. **Does not** re-install the NMS evaluator swap (that requires a server restart — see ARCHITECTURE.md). |

## Sign opt-in

In addition to commands, players with `redstone-region.sign` can place a
sign whose first line is `[ac]`, `[vanilla]`, `[eigencraft]`, or
`[disabled]` (case-insensitive). Optional second line: a numeric radius
(in chunks), capped by `sign.max-radius` in config. The sign repaints
itself with the applied state and the actor name.

## Tab completion & aliases

All sub-commands tab-complete. Mode aliases recognized everywhere a mode
is expected:

| Canonical | Aliases |
|---|---|
| `vanilla` | `v` |
| `alternate-current` | `ac`, `alternate`, `alternatecurrent` |
| `eigencraft` | `eigen`, `turbo`, `ec` |
| `disabled` | `off`, `frozen`, `none`, `no` |
