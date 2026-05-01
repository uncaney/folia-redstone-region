# FAQ

## "Is it safe to use on a production server?"

Yes for vanilla or alternate-current chunks built from compliant
contraptions. The plugin is byte-for-byte parity with vanilla on all 8
test contraptions in the harness (dust line, dust grid, repeater clock,
AND gate, comparator subtract, torch ladder, torch inverter, dust
zigzag) over 60+ ticks each. Run `/redstone-region check` first if your
build mixes pistons + observers — see `docs/MODES.md`.

The plugin doesn't open network ports, doesn't read files outside its
own data folder, gates every command via `redstone-region.admin`, and
the NMS swap is reversible at plugin disable. There are no known crash
paths on Folia 1.21.11 build #6+.

## "What does it do to my world?"

It writes one byte (the mode value) to each chunk's
`PersistentDataContainer` whenever you set that chunk to non-vanilla
via `/set`, `/fill`, sign, WorldEdit selection, or auto-AC. That byte
survives world saves and server restarts. It changes nothing else
about the chunk — no block changes, no entity changes.

If you remove the plugin, the PDC entries stay (harmless). Reinstall
later and your marks come back.

## "Will it work on Paper without Folia?"

Probably yes, but it's untested in CI. The hot path doesn't use Folia
schedulers, but `RegionScheduler.execute` is used in commands. Paper
exposes a no-op `RegionScheduler` for plugin compatibility, so it
should fall through. Open an issue if you hit a Paper-specific
problem.

## "Why per-chunk and not per-region or per-world?"

Per-chunk is fine-grained enough that you can mark just the salle des
coffres without affecting anything else, and coarse enough that the
storage cost is one byte per chunk. Per-region (Folia's threading
unit) would mean ~256 chunks change at once, blunt for the user. Per-
world is what Paper's bundled config already does.

## "Does it conflict with Paper's `redstone-implementation` config?"

The plugin **forces** `paperConfig.misc.redstoneImplementation = VANILLA`
on every world at boot, so Paper's gating doesn't short-circuit our
hook. Even if you set Paper's config to AC or Eigencraft, our forcer
overrides it. Use `/redstone-region fill 32 alternate-current` if you
want AC everywhere instead.

## "Can I use it with redstone clones / map copy / world templates?"

Yes — the chunk PDC bytes travel with the chunk. Copying a chunk via
WorldEdit, exporting a structure, etc. carries the redstone-engine
mark. Removing it on a copy: `/redstone-region clear` or remove the
PDC entry manually.

## "AC broke my piston build, what do I do?"

`/redstone-region set vanilla` or `/redstone-region set eigencraft` on
that chunk. The first restores Mojang behaviour exactly; the second
keeps vanilla semantics with 3-5× perf gain. See `docs/MODES.md` for
why this happens.

## "The chunk I'm on doesn't show up in /stats."

The timing table only records chunks where redstone wires actually
recomputed. A chunk full of dust with no power changes won't show up.
Toggle a lever in the chunk and run `/stats` again.

If you set `timing.mode: non-vanilla-only` in config, vanilla chunks
are intentionally skipped (zero overhead on them). Set back to `all`
or `sample` to see them again.

## "How do I know auto-AC is doing its job?"

Look at the audit log: `tail plugins/folia-redstone-region/audit.<date>.jsonl`.
Auto-AC entries have `"src":"AUTO_AC"`. You'll also see Discord
notifications if the webhook is configured.

## "What if the server crashes mid-flip?"

The flip is split into (1) update in-memory ChunkRegistry, (2) write
PDC. If the JVM dies between those two, the chunk reverts to its
on-disk state (the previous mode) on next boot. No corruption — the
chunk PDC is written atomically by the underlying region file.

## "How big can /redstone-region fill go?"

`fill <radius>` accepts radius up to 32, so up to 65×65 = 4225 chunks
in one batch. Larger areas via WorldEdit selection +
`/redstone-region selection <mode>` (no upper bound — limited by your
WE selection size, which is itself capped by WE config).

## "I use a non-default world (skyblock, custom worldgen)…"

The plugin doesn't care about the world generator. It hooks the
redstone evaluator at the global Block level, so all worlds get the
swap. Per-chunk PDC is per-world, so chunk (0,0) in `world` and chunk
(0,0) in `world_nether` are independent.

## "Can I export / import the AC marks?"

Not yet built-in. The PDC bytes are part of each chunk's NBT under
`Level/PaperPDC/ekaii:redstone_engine`. You can dump them with any
chunk-NBT tool. A `/redstone-region export` and `import` command is on
the open-issue list — open a ticket if you need it.

## "What's the upgrade story for future versions?"

The PDC byte values 0/1/2/3 are stable across releases. Future
versions may add modes (4, 5, …) but won't change the meaning of
existing bytes. Plugin upgrades are drop-in: replace the jar, restart.

The `config.yml` schema is additive — new sections are added with
defaults, old sections are kept. If a future release renames or removes
a key, the upgrade notes in the release will say so.
