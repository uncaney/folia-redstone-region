# Installation

## Requirements

- **Folia** for Minecraft **1.21.11** (build #6 or newer; Luminol
  1.21.11-8b41a91 and other Folia forks should work too — anything
  exposing the same `RedstoneWireBlock.evaluator` field added by Mojang
  in 1.21.2)
- **Java 21+**

## Get the jar

Three options:

1. **Pre-built artifact** — download the latest `plugin-*-reobf.jar`
   from the [Forgejo Actions runs](https://forgejo.ekaii.fr/exo/folia-redstone-region/actions)
   or the [GitHub mirror](https://github.com/uncaney/folia-redstone-region/actions).
2. **Build from source** — see `docs/BUILDING.md`.
3. **Drop-in for dev** — `./gradlew :plugin:build` produces
   `plugin/build/libs/plugin-0.1.0-reobf.jar` ready to ship.

## Drop the jar into the server

```
mv plugin-0.1.0-reobf.jar <server>/plugins/folia-redstone-region.jar
```

Restart the server. On the next boot you should see, near the end of
the start log:

```
[folia-redstone-region] redstone evaluator swapped:
    net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator ->
    net.ekaii.redstone.region.nms.DispatchingEvaluator
[folia-redstone-region] timing policy: ALL
[folia-redstone-region] folia-redstone-region 0.1.0 ready
```

If you don't see those lines or you see an `ERROR` mentioning
`folia-redstone-region`, see `docs/TROUBLESHOOTING.md`.

## First-run files

On first boot the plugin creates:

```
plugins/folia-redstone-region/
├── config.yml                    # operator-editable settings
├── audit.<YYYY-MM-DD>.jsonl      # one line per chunk-mode flip
└── lang/                         # if you want to override en.yml / fr.yml
```

Default config values are documented inline in `config.yml`. See
`docs/CONFIGURATION.md` for a longer explanation of each section.

## Optional integrations

The plugin gracefully no-ops when these aren't installed; install any of
them to unlock the corresponding feature:

| Plugin | Version tested | What it gives you |
|---|---|---|
| **BlueMap** | 5.16+ | Colored 16×16 chunk rectangles overlay on the BlueMap webapp |
| **PlaceholderAPI** | 2.11.7 | `%redstone-region_*%` placeholders for TAB / scoreboard |
| **WorldEdit** | 7.3.19+ (Folia-patched preferred) | `/redstone-region selection <mode>` from a WE selection |

## Operator permissions

The plugin defines two permissions:

| Permission | Default | Purpose |
|---|---|---|
| `redstone-region.admin` | op | All `/redstone-region …` subcommands |
| `redstone-region.sign` | op | Place `[ac]` / `[vanilla]` etc. signs to flip chunks |

Wire them in your favourite permissions plugin (LuckPerms recommended).

## Quick smoke test

Once the plugin loads:

```
/redstone-region info
/redstone-region set alternate-current
/redstone-region info
/redstone-region clear
```

You should see your chunk flip to AC and back to vanilla. If you have
BlueMap, a green rectangle appears and disappears on the webapp.
