# Security Policy

## Supported versions

The plugin tracks Folia 1.21.11 only. Earlier or later Minecraft / Folia
versions are not supported and may break (the NMS hook depends on the
1.21.2+ `RedstoneWireBlock.evaluator` field; see `docs/ARCHITECTURE.md`).

| Version | Supported |
|---|---|
| 0.1.x (current main) | ✅ |
| < 0.1 | ❌ |

## Reporting a vulnerability

If you find a security issue (e.g. a way to bypass the
`redstone-region.admin` permission, a path-traversal in audit log /
config write, an NMS swap that crashes the server, or any plugin abuse
that affects servers other than the operator's own), please **do not
open a public issue or PR**.

Email a description to **exo@chauvat.com** with subject
`[SECURITY] folia-redstone-region`. Include:

- a description of the issue
- steps to reproduce, ideally on a minimal Folia 1.21.11 setup
- the affected commit hash
- your suggested fix, if any

We aim to acknowledge reports within 7 days and ship a fix within 30
days for high-severity issues. Lower severity may take longer.

## Security stance & threat model

This plugin runs server-side and modifies vanilla redstone behaviour
per chunk. It does **not** read or alter any data outside Minecraft
worlds it has been granted access to via Bukkit. No network listeners
are opened. Configuration is read from the plugin's own data folder.

What we **do** trust:

- The Folia / Paper server JVM
- Mojang and Paper-bundled NMS classes
- The integrity of files you put in `plugins/folia-redstone-region/`

What we **do not** trust:

- Player-supplied chat / command input — all commands are gated by
  `redstone-region.admin` (default op-only).
- Sign opt-in — gated by `redstone-region.sign` and capped by
  `sign.max-radius`.
- Discord webhook URLs — you provide them; we send what we send.
  Don't paste a webhook from an untrusted third party.

## Fixed CVEs

None to date. (See `git log --grep=CVE`.)
