# Troubleshooting

## Plugin fails to enable

### `NoClassDefFoundError: net/minecraft/world/level/redstone/RedstoneWireEvaluator`

You're not on Folia 1.21.11. The plugin is pinned to that version's
NMS layout. Upgrade your server.

### `Caused by: NoSuchFieldException: evaluator`

Mojang renamed or removed the `evaluator` field on `RedStoneWireBlock`
in your server's MC version. Open an issue with the server jar version
and we'll add a path for it.

### `BlueMap not installed — overlay disabled`

Cosmetic — BlueMap is optional. Install BlueMap 5.16+ and restart to
enable the chunk-mode overlay on the BlueMap webapp.

### `PlaceholderAPI present but bridge failed to register`

Verify your `paper-plugin.yml` has `dependencies.server.PlaceholderAPI:
{ load: BEFORE, required: false, join-classpath: true }`. The
`join-classpath: true` is critical — without it, PAPI's classes aren't
visible to our bridge. (Newer plugin builds set this correctly; if you
see this on a recent jar, file a bug.)

## Commands not working

### `/redstone-region selection alternate-current` → "Command exception"

Same root as above: WorldEdit's classpath isn't joined. Check the
server log on the line right after the error for a `NoClassDefFoundError`
mentioning `com.sk89q.worldedit`. Fixed by upgrading the plugin to a
build that has `join-classpath: true` in its `paper-plugin.yml`.

### `redstone-region set` does nothing

- Check you have op or `redstone-region.admin` permission.
- Check the chunk actually has redstone wires — without wire activity,
  no behaviour change is visible. Toggle a lever after the set.
- `/redstone-region info` immediately after the set should report the
  new mode. If it doesn't, the PDC write failed — check
  `logs/latest.log` for warnings.

### Sign with `[ac]` doesn't flip the chunk

- Check you have `redstone-region.sign` permission (default op).
- Check `sign.enabled: true` in `config.yml`.
- The first line of the sign must be exactly `[ac]`, `[vanilla]`,
  `[eigencraft]`, or `[disabled]` (case-insensitive, brackets included).
- Any text on the second line that isn't a number is ignored — radius
  defaults to 0 (just the sign's own chunk).

## Performance / stats

### `/stats` always says "no recorded redstone activity yet"

- If `timing.mode: off` in config, this is expected.
- Otherwise: there hasn't been any redstone activity yet. Toggle a
  lever in a chunk that has a wire and re-run.

### Server TPS dropped after enabling the plugin

The plugin's hot-path overhead is ~50 ns per wire update — typically
negligible vs the wire algorithm itself. If your TPS drop is real:

1. `/redstone-region stats` to see the hottest chunks. If a chunk
   averages >5 ms/update, it's doing real work — the plugin isn't the
   problem.
2. `/redstone-region profile` on the hot chunk for a
   recommendation. Often the right answer is `set alternate-current`
   on that chunk.
3. If the cost is genuinely the plugin instrumentation (rare):
   `timing.mode: sample` `sample-rate: 100` cuts overhead by 100×.

### AC chunk's behaviour differs from vanilla

Run `/redstone-region check` on the chunk. If it warns about a
piston/observer mix, you're hitting one of the documented edge cases —
see `docs/MODES.md`. Either revert the chunk to vanilla or switch to
eigencraft.

## Audit / Discord

### Audit log file is empty

- Check `audit.enabled: true` in config.
- Check `plugins/folia-redstone-region/` exists and is writable by the
  server process. Look for warnings in the log: `audit:`.

### Discord webhook posts nothing

- Check the webhook URL is correct in config (test with `curl -X POST
  -d '{"content":"test"}' -H 'Content-Type: application/json'
  <webhook-url>`).
- Check `discord.enabled: true` AND a non-empty `webhook-url` AND the
  desired filter level matches the source you're flipping with (e.g.
  `filter: manual` skips auto-AC flips).
- Discord rate-limited responses (HTTP 429): the plugin pauses 1 s and
  re-queues. Sustained burst beyond 5 req/s drops oldest events from
  the queue (cap 1024).

## Reload behaviour

### `/redstone-region reload` doesn't pick up my code change

It only reloads `config.yml` and `lang/*.yml`. To pick up plugin code
changes (a new jar version), restart the server. The NMS evaluator
swap is one-shot per JVM — undoing and re-installing it mid-flight
would race with running region threads.

### After `/reload`, my Discord webhook stopped working

The `/reload` recreates the webhook worker with the new config. If you
see a sudden flood of "discord webhook 4xx" warnings in the log, your
new URL is bad — verify and `/reload` again.

## Bug reporting

If none of the above helps, file an issue with:

- the server software + version (Folia 1.21.11 build #?)
- the plugin version (`/version folia-redstone-region`)
- the relevant chunk-load and onEnable lines from `logs/latest.log`
- a minimum reproducer (which `/redstone-region` commands, what
  redstone build)

See `SECURITY.md` for security-sensitive issues.
