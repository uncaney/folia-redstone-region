# 06 — PlaceholderAPI provider pattern (Paper/Folia 1.21.11)

Target: expose chunk-mode info from `folia-redstone-region` to chat / scoreboard
plugins that drive PAPI placeholders. We ship a single `PlaceholderExpansion`
inside the plugin jar (no external `.jar` expansion).

## 1. PAPI version installed on `creaekaiiserver`

`ssh maison 'ls -la /fast/creaekaiiserver/plugins/ | grep -i Placeholder'`:

```
drwxrwxr-x  3 matelatsolaire matelatsolaire        4 Jan  7 00:52 PlaceholderAPI
-rw-rw-r--  1 matelatsolaire matelatsolaire   953837 Jan  7 00:28 PlaceholderAPI-2.11.7.jar
```

→ **PAPI 2.11.7**, the first release with first-class Folia support
(UniversalScheduler merged via PR #1127). The repo only goes up to 2.11.7 in the
2.11 series; 2.12.x is the next branch but we pin to what's deployed.

Sources:
- [PlaceholderAPI 2.11.7 — Hangar](https://hangar.papermc.io/HelpChat/PlaceholderAPI/versions/2.11.7)
- [2.11.7 Folia Support and Code Improvements — Modrinth](https://modrinth.com/plugin/placeholderapi/version/2.11.7)
- [PlaceholderAPI Releases — GitHub](https://github.com/PlaceholderAPI/PlaceholderAPI/releases)

## 2. Maven / Gradle coordinates

Repository (helpch.at, formerly extendedclip.com — the latter 301-redirects):

- `https://repo.helpch.at/releases`

Artifact: `me.clip:placeholderapi:2.11.7` — **`compileOnly`** (PAPI is a hard
runtime dep declared in `plugin.yml`'s `depend:` list, never shaded).

`plugin/build.gradle.kts`:

```kotlin
repositories {
    maven("https://repo.helpch.at/releases") { name = "helpch" }
}

dependencies {
    compileOnly("me.clip:placeholderapi:2.11.7")
}
```

`plugin/src/main/resources/paper-plugin.yml` (or `plugin.yml`):

```yaml
depend: [ PlaceholderAPI ]
```

Sources:
- [PlaceholderAPI Wiki — Creating a PlaceholderExpansion](https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/)
- [helpch.at releases — me/clip/placeholderapi/](https://repo.helpch.at/releases/me/clip/placeholderapi/)

## 3. Provider pattern — methods to override

We extend `me.clip.placeholderapi.expansions.PlaceholderExpansion`. Required:

| Method | Why |
|---|---|
| `@NotNull String getIdentifier()` | placeholder prefix between `%` and first `_`. We use `redstone-region`. May not contain `%`, `{`, `}`, `_`. |
| `@NotNull String getAuthor()` | identification in `/papi list`. |
| `@NotNull String getVersion()` | shown in `/papi info`. We forward `getPluginMeta().getVersion()`. |
| `boolean persist()` | **return `true`**: the expansion class lives inside our jar, so `/papi reload` must not unregister it. |
| `boolean canRegister()` | called once at `register()` time. We just `return true` (PAPI presence already vetted by `depend:`). |
| `String onPlaceholderRequest(Player, String)` | hot path. Called from PAPI on whatever thread the consuming plugin invoked `setPlaceholders` from. Return `null` for unknown params so PAPI falls through. |

Note: the wiki shows `onRequest(OfflinePlayer, String)` as the modern variant.
For our use case (chunk-under-feet placeholder needs an online location) we
override `onPlaceholderRequest(Player, String)` per the brief; PAPI dispatches
to it before falling back to `onRequest`.

Registration in `PluginMain#onEnable()` — guard with the standard PAPI loaded
check (Bukkit's `isPluginEnabled` is preferred over `getPlugin() != null`
because it also returns true only after the dep finished `onEnable`):

```java
if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
    new RedstoneRegionExpansion(this).register();
} else {
    getLogger().info("PlaceholderAPI not present — skipping expansion register");
}
```

Sources:
- [PlaceholderAPI Wiki — PlaceholderExpansion](https://github.com/PlaceholderAPI/PlaceholderAPI/wiki/PlaceholderExpansion)
- [PlaceholderAPI Wiki — Plugin Developers](https://github.com/PlaceholderAPI/PlaceholderAPI/wiki/Plugin-Developers)

## 4. Folia threading

PAPI 2.11.7 uses UniversalScheduler under the hood, but **the
`onPlaceholderRequest` callback runs on whichever thread the *caller* invoked
`PlaceholderAPI.setPlaceholders` from**. For Folia, the wiki tells *consumers*
to call `setPlaceholders` from the world thread of the player they target —
which for chat / scoreboard plugins is normally the player's region thread, or
the global region thread for `setPlaceholders(null, …)`.

What that means for us — our placeholders only read state, never mutate level
data. Two access patterns:

1. **Read `ChunkRegistry`** — already lock-free in steady state via
   `StampedLock.tryOptimisticRead()`
   (`plugin/src/main/java/net/ekaii/redstone/region/config/ChunkRegistry.java:50-60`).
   Safe from any thread, including async chat plugins. Confirmed:

   ```java
   long stamp = pl.lock.tryOptimisticRead();
   byte v = pl.modes.get(ck);
   if (pl.lock.validate(stamp)) return RedstoneMode.fromId(v);
   ```

   plus a `readLock()` fallback on stamp invalidation. No region-thread
   affinity required; we never touch `Level` from the placeholder path.

2. **`%redstone-region_mode%`** needs `Player#getLocation()`. Bukkit
   `Location` is a snapshot value object — `getLocation()` constructs a new
   `Location` from the player's last-known coords every call, which is safe
   from any thread on Folia (Paper API explicitly allows it; the player object
   is owned by the player's region but `getLocation` returns by-value). We
   then take only `getWorld()`, `getBlockX()/Z()` — no entity traversal, no
   chunk fetch.

   For full safety on Folia we resolve the world's `ResourceKey<Level>` via
   the public Bukkit `World#getKey()` → `NamespacedKey` and translate to
   NMS only via the same helper `EvaluatorSwap` already uses, **without**
   touching the chunk. We never call `getChunkAt`, `getBlock`, or any
   region-confined accessor in the placeholder handler.

→ **No region-scheduling needed inside `onPlaceholderRequest`.** This is the
key Folia win: lock-free reads against the registry let any caller (chat
plugin's async chat event, scoreboard plugin's region tick, `/papi parse` from
the console) get a correct value without us having to hop threads.

Sources:
- [Folia thread safety thread on Folia issue tracker — Folia#334](https://github.com/PaperMC/Folia/issues/334)
- [PlaceholderAPI Wiki — Using PlaceholderAPI](https://wiki.placeholderapi.com/developers/using-placeholderapi/)

## 5. Recommended placeholder names

All under the `redstone-region` identifier:

| Placeholder | Returns | Player-scoped? |
|---|---|---|
| `%redstone-region_mode%` | mode of the chunk under the player's feet — `vanilla` / `alternate-current` / `disabled` / `eigencraft` (uses `RedstoneMode#slug()`) | yes |
| `%redstone-region_ac_count%` | count of chunks set to `alternate-current` in the player's current world | yes (world-scoped) |
| `%redstone-region_disabled_count%` | count of `disabled` chunks in the player's current world (placeholder reserved for upcoming mode; today returns `0`) | yes |
| `%redstone-region_eigencraft_count%` | count of `eigencraft` chunks in the player's current world (placeholder reserved; today `0`) | yes |
| `%redstone-region_total_count%` | total non-vanilla chunks in the player's current world | yes |

Two forward-compat notes:

- `RedstoneMode` currently has only `VANILLA(0)` and `ALTERNATE_CURRENT(1)`
  (`plugin/src/main/java/net/ekaii/redstone/region/config/RedstoneMode.java:3-6`).
  The `disabled` / `eigencraft` placeholders are stable names that resolve to
  `0` until those modes land. This avoids breaking scoreboard configs later.
- All `_count` placeholders default to the player's current world; an
  alternative form `%redstone-region_ac_count_<worldName>%` could be added by
  parsing trailing args, but is not in scope here.

## 6. Compilable Java skeleton

`plugin/src/main/java/net/ekaii/redstone/region/papi/RedstoneRegionExpansion.java`:

```java
package net.ekaii.redstone.region.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.ekaii.redstone.region.PluginMain;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI provider for folia-redstone-region.
 *
 * <p>Lock-free reads — the hot path only hits {@link ChunkRegistry} which
 * already guards its state with {@code StampedLock.tryOptimisticRead()}.
 * Safe from any thread, no Folia region-scheduling required here.
 */
public final class RedstoneRegionExpansion extends PlaceholderExpansion {

    private final PluginMain plugin;

    public RedstoneRegionExpansion(PluginMain plugin) {
        this.plugin = plugin;
    }

    @Override @NotNull
    public String getIdentifier() { return "redstone-region"; }

    @Override @NotNull
    public String getAuthor() { return String.join(", ", plugin.getPluginMeta().getAuthors()); }

    @Override @NotNull
    public String getVersion() { return plugin.getPluginMeta().getVersion(); }

    /** Internal class — survive {@code /papi reload}. */
    @Override public boolean persist() { return true; }

    /** PAPI presence already enforced via plugin.yml depend; nothing else to check. */
    @Override public boolean canRegister() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        final ChunkRegistry reg = ChunkRegistry.get();
        final World world = player.getWorld();
        final ResourceKey<Level> levelKey = ((CraftWorld) world).getHandle().dimension();

        return switch (params.toLowerCase()) {
            case "mode" -> {
                Location loc = player.getLocation(); // by-value snapshot, thread-safe
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;
                yield reg.modeOfChunk(levelKey, cx, cz).slug();
            }
            case "ac_count"         -> Integer.toString(countMode(reg, levelKey, RedstoneMode.ALTERNATE_CURRENT));
            case "disabled_count"   -> "0"; // reserved for upcoming DISABLED mode
            case "eigencraft_count" -> "0"; // reserved for upcoming EIGENCRAFT mode
            case "total_count"      -> Integer.toString(reg.trackedCount(levelKey));
            default -> null; // let PAPI fall through to onRequest / unknown
        };
    }

    private static int countMode(ChunkRegistry reg, ResourceKey<Level> key, RedstoneMode want) {
        // Cheap until/unless we add more modes; today every tracked chunk is AC,
        // so trackedCount == ac_count. Kept generic so later modes don't change
        // the placeholder contract.
        if (want == RedstoneMode.ALTERNATE_CURRENT) return reg.trackedCount(key);
        long[] keys = reg.snapshotKeys(key);
        int n = 0;
        for (long packed : keys) {
            int cx = (int) (packed >> 32);
            int cz = (int) packed;
            if (reg.modeOfChunk(key, cx, cz) == want) n++;
        }
        return n;
    }
}
```

Wire it into `PluginMain#onEnable()`
(`plugin/src/main/java/net/ekaii/redstone/region/PluginMain.java:18`) right
after step 4:

```java
// 5. Register PlaceholderAPI expansion if PAPI is loaded.
if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
    new RedstoneRegionExpansion(this).register();
    log.info("PlaceholderAPI expansion registered");
}
```

`CraftWorld` import note — Paper 1.21.11 uses the unrelocated
`org.bukkit.craftbukkit` package (Mojang-mapped, no version suffix), matching
what `EvaluatorSwap` already does in this codebase.

---

## Implementation plan (5 lines)

1. Add `compileOnly("me.clip:placeholderapi:2.11.7")` + `helpch.at` repo to `plugin/build.gradle.kts`, and `depend: [ PlaceholderAPI ]` to `paper-plugin.yml`.
2. Drop `RedstoneRegionExpansion.java` from section 6 into `plugin/src/main/java/net/ekaii/redstone/region/papi/`.
3. In `PluginMain#onEnable()` after step 4, guard `Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")` and call `new RedstoneRegionExpansion(this).register()`.
4. Smoke-test on `creaekaiiserver` with `/papi parse me %redstone-region_mode%` standing on AC/vanilla chunks, plus `%redstone-region_ac_count%` / `%redstone-region_total_count%`.
5. Once `RedstoneMode.DISABLED` / `.EIGENCRAFT` land, replace the two `"0"` literals in `onPlaceholderRequest` with `countMode(reg, levelKey, RedstoneMode.DISABLED)` etc. — placeholder names stay stable.
