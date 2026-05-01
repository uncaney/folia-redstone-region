# Research 05 — WorldEdit API for Player Selections (Paper/Folia 1.21.11)

Date: 2026-05-01
Author: research note for `folia-redstone-region` plugin

---

## 1. WorldEdit version on the live server

Command run:
```
ssh maison 'ls -la /fast/creaekaiiserver/plugins/ | grep -iE "worldedit|fawe"'
```

Result (verbatim):
```
drwxrwxr-x  6 matelatsolaire matelatsolaire        7 Jan 10 17:15 WorldEdit
-rw-r--r--  1 matelatsolaire matelatsolaire  7311584 Jan  8 18:15 worldedit-bukkit-7.3.19-SNAPSHOT-dist.jar
```

- Exact jar: **`worldedit-bukkit-7.3.19-SNAPSHOT-dist.jar`** (7.3 MB, dropped 2026-01-08)
- No FAWE on this server.
- Branch: `version/7.3.x` (the latest published 7.3.x line; supports MC up to 1.21.11
  per `worldedit-bukkit/build.gradle.kts` adapter list:
  `"1.21.4", "1.21.5", "1.21.6", "1.21.9", "1.21.11"`).
- Upstream EngineHub 7.4.1 (released 2026-03-21) is the first release line with
  *experimental* Folia support — but the live server is still on 7.3.19.

---

## 2. Maven coordinates

Repository:
```
maven { url = uri("https://maven.enginehub.org/repo/") }
```

Coordinate (compileOnly — the jar ships in `plugins/`):
```kotlin
compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19-SNAPSHOT")
// or, to track latest 7.3.x:
// compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.20-SNAPSHOT")
```

Notes:
- The artifact `worldedit-bukkit` *transitively* exposes `worldedit-core`
  (`com.sk89q.worldedit.LocalSession`, `Region`, etc.), so a single `compileOnly`
  is enough.
- We pin to the exact same major as the deployed jar to avoid binary-incompat
  surprises (LocalSession internals shifted between 7.2 → 7.3).
- 7.4.1 (Hangar) supports MC 1.21.4–1.21.11 *and* adds experimental Folia, but
  upgrading the server is out of scope for this plugin — design for 7.3.19.

---

## 3. Code pattern: get player selection

### 3a. The minimal happy path

```java
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.Player;        // WE Player, not Bukkit
import com.sk89q.worldedit.world.World;          // WE World, not Bukkit
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.math.BlockVector2;

org.bukkit.entity.Player bukkitPlayer = ...;

Player actor = BukkitAdapter.adapt(bukkitPlayer);
LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

World selectionWorld = session.getSelectionWorld();   // @Nullable
if (selectionWorld == null) {
    // Player has no selection at all
    return;
}

Region region;
try {
    region = session.getSelection(selectionWorld);
} catch (IncompleteRegionException ex) {
    // Selection only half-defined (e.g. only pos1)
    return;
}
```

### 3b. Region API surface relevant to us

From `com.sk89q.worldedit.regions.Region` (7.3.x):
- `BlockVector3 getMinimumPoint()`
- `BlockVector3 getMaximumPoint()`
- `@Nullable World getWorld()` — should equal `selectionWorld`, may be null
  in pathological cases.
- `Set<BlockVector2> getChunks()` — **what we want for chunk-level region
  registration.** Each `BlockVector2` is `(chunkX, chunkZ)`.
- `Set<BlockVector3> getChunkCubes()` — 16×16×16 sub-chunks; not what we need.
- `boolean contains(BlockVector3)` — useful for fine-grained block tests later.

`BlockVector2` exposes `int x()` (chunkX) and `int z()` (chunkZ).

### 3c. Cross-world / no-selection edge cases

| Case                                            | Symptom                                | Handling                                  |
| ----------------------------------------------- | -------------------------------------- | ----------------------------------------- |
| Player never used `//pos1`/wand                 | `getSelectionWorld() == null`          | Bail out with user-facing error.          |
| Only `//pos1` set (CuboidRegion incomplete)     | `IncompleteRegionException` thrown     | Catch, ask player to set both corners.    |
| Player made selection in world A, runs cmd in B | `selectionWorld != bukkitPlayer.getWorld()` | Decide policy: refuse, or use selectionWorld. For redstone-region we should refuse — registering a region the player can't see is foot-gunny. |
| Selection is a `Polygonal2DRegion` / `EllipsoidRegion` / `ConvexPolyhedralRegion` | `getChunks()` still works (interface contract) | No special handling needed for chunk enumeration. |

---

## 4. Folia compatibility

Findings (from EngineHub 7.3.x and 7.4.x CHANGELOG.txt, Hangar release notes,
issue #2348):

- **WorldEdit 7.3.x has no Folia support.** The 7.3.x changelog has zero
  mentions of "Folia". Internally it still uses `BukkitScheduler` (single
  global tick thread), which throws on Folia.
- **WorldEdit 7.4.1** (2026-03-21) introduced *experimental* Folia support
  (changelog: "[Bukkit] Added experimental Folia support").
- A community fork **`Euphillya/WorldEdit-Folia`** maintains 7.3.x and 7.4.x
  branches with Folia patches — this is what most Folia operators run today.
- **Practical implication for our plugin**: assume the WorldEdit jar on the
  server is *not* Folia-aware. Therefore:
  - Do **not** call any WorldEdit edit / paste / undo APIs from arbitrary
    Folia threads — those will hit `BukkitScheduler` internally.
  - **Reading** `LocalSession.getSelection(...)` is safe from the calling
    thread because it only touches in-memory per-session data structures
    (no scheduler, no world I/O). We only do reads.
  - We must call our `collectSelectionChunks()` from a context where the
    Bukkit `Player` reference is valid. On Folia, command executors run on
    the executor's region thread, which is fine for reading the player's
    selection.
  - Our plugin will register the resulting chunk set with Folia's
    `RegionScheduler` for any subsequent per-chunk work — we do not delegate
    that work back to WorldEdit.

Sources:
- EngineHub WorldEdit 7.4.x changelog (Folia entry):
  https://github.com/EngineHub/WorldEdit/blob/version/7.4.x/CHANGELOG.txt
- EngineHub WorldEdit 7.3.x changelog (no Folia entries):
  https://github.com/EngineHub/WorldEdit/blob/version/7.3.x/CHANGELOG.txt
- Folia support feature request (open since 2023):
  https://github.com/EngineHub/WorldEdit/issues/2348
- Community fork: https://github.com/Euphillya/WorldEdit-Folia
- Hangar release page (7.4.1, MC 1.21.4–1.21.11):
  https://hangar.papermc.io/EngineHub/WorldEdit/versions/7.4.1

---

## 5. Compilable Java snippet — `WorldEditBridge.collectSelectionChunks(Player)`

Drop this into `plugin/src/main/java/.../worldedit/WorldEditBridge.java`. It
returns a `List<long[]>` where each entry is `{chunkX, chunkZ}` — caller is
free to pack into a `ChunkPos`-equivalent long key.

```java
package com.example.foliaredstoneregion.worldedit;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Thin read-only bridge into WorldEdit's per-player selection state.
 *
 * <p>Safe to call from any Folia region thread: only touches in-memory
 * per-session data; performs no scheduling and no world I/O.</p>
 *
 * <p>Compatible with worldedit-bukkit 7.3.19 (deployed jar on creaekaiiserver
 * as of 2026-01-08). No Folia-specific API used.</p>
 */
public final class WorldEditBridge {

    private WorldEditBridge() {}

    public enum SelectionStatus {
        OK,
        NO_SELECTION,           // player never used //pos1 / wand
        INCOMPLETE_SELECTION,   // only one corner defined
        WRONG_WORLD             // selection lives in a different world than the player
    }

    public static final class Result {
        public final SelectionStatus status;
        public final List<long[]> chunks;          // {chunkX, chunkZ} pairs
        public final org.bukkit.World world;       // null unless status == OK

        Result(SelectionStatus status, List<long[]> chunks, org.bukkit.World world) {
            this.status = status;
            this.chunks = chunks;
            this.world = world;
        }
    }

    /**
     * Collect the chunks of {@code player}'s current WorldEdit selection,
     * requiring the selection to live in the same world the player is in.
     *
     * @return a {@link Result}; {@code chunks} is empty unless status == OK.
     */
    @NotNull
    public static Result collectSelectionChunks(@NotNull Player player) {
        com.sk89q.worldedit.entity.Player actor = BukkitAdapter.adapt(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(actor);

        World selectionWorld = session.getSelectionWorld();
        if (selectionWorld == null) {
            return new Result(SelectionStatus.NO_SELECTION, Collections.emptyList(), null);
        }

        org.bukkit.World bukkitSelectionWorld = BukkitAdapter.adapt(selectionWorld);
        if (!bukkitSelectionWorld.getUID().equals(player.getWorld().getUID())) {
            return new Result(SelectionStatus.WRONG_WORLD, Collections.emptyList(), null);
        }

        Region region;
        try {
            region = session.getSelection(selectionWorld);
        } catch (IncompleteRegionException ex) {
            return new Result(SelectionStatus.INCOMPLETE_SELECTION,
                              Collections.emptyList(), null);
        }

        Set<BlockVector2> weChunks = region.getChunks();
        List<long[]> out = new ArrayList<>(weChunks.size());
        for (BlockVector2 v : weChunks) {
            out.add(new long[] { v.x(), v.z() });
        }
        return new Result(SelectionStatus.OK, out, bukkitSelectionWorld);
    }
}
```

### Build wiring (`plugin/build.gradle.kts` excerpt)

```kotlin
repositories {
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19-SNAPSHOT")
}
```

`plugin.yml` should declare a soft-dependency:
```yaml
softdepend: [WorldEdit]
```
(or `depend: [WorldEdit]` if the bridge is not optional).

---

## Sources

- EngineHub Maven (artifact location):
  https://maven.enginehub.org/repo/com/sk89q/worldedit/worldedit-bukkit/
- WorldEdit API concepts — LocalSession:
  https://worldedit.enginehub.org/en/latest/api/concepts/local-sessions/
- WorldEdit examples — LocalSession (raw):
  https://github.com/EngineHub/WorldEditDocs/blob/master/source/api/examples/local-sessions.rst
- BukkitAdapter Javadoc:
  https://docs.enginehub.org/javadoc/com.sk89q.worldedit/worldedit-bukkit/release/com/sk89q/worldedit/bukkit/BukkitAdapter.html
- Region.java source (7.3.x):
  https://github.com/EngineHub/WorldEdit/blob/version/7.3.x/worldedit-core/src/main/java/com/sk89q/worldedit/regions/Region.java
- LocalSession.java source (7.3.x):
  https://github.com/EngineHub/WorldEdit/blob/version/7.3.x/worldedit-core/src/main/java/com/sk89q/worldedit/LocalSession.java
- WorldEdit 7.4.x CHANGELOG (Folia experimental support):
  https://github.com/EngineHub/WorldEdit/blob/version/7.4.x/CHANGELOG.txt
- WorldEdit 7.3.x CHANGELOG (no Folia support):
  https://github.com/EngineHub/WorldEdit/blob/version/7.3.x/CHANGELOG.txt
- Folia support tracker issue: https://github.com/EngineHub/WorldEdit/issues/2348
- Community Folia fork: https://github.com/Euphillya/WorldEdit-Folia
- WorldEdit 7.4.1 on Hangar (MC 1.21.4–1.21.11):
  https://hangar.papermc.io/EngineHub/WorldEdit/versions/7.4.1

---

## Implementation plan (5 lines)

1. Add EngineHub Maven repo + `compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19-SNAPSHOT")` to `plugin/build.gradle.kts`; add `softdepend: [WorldEdit]` in `plugin.yml`.
2. Create `plugin/src/main/java/.../worldedit/WorldEditBridge.java` with the `collectSelectionChunks(Player)` snippet above; keep it read-only and Folia-thread-safe.
3. Wire the bridge into the `/redstoneregion claim` command handler: on `SelectionStatus.OK` feed `result.chunks` into the Folia `RegionScheduler` registration path; otherwise send the player a localized error per status enum.
4. Guard the bridge call with `Bukkit.getPluginManager().getPlugin("WorldEdit") != null` and class-load `WorldEditBridge` lazily so the plugin still loads when WorldEdit is absent.
5. Test on the live `creaekaiiserver` (Folia + WE 7.3.19): pos1/pos2 across chunk borders, cross-world selection, no selection, large 256×256 cuboid (~256 chunks) — confirm no `BukkitScheduler` exceptions are thrown by WE during the read.
