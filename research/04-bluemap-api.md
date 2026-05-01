# Research 04 — BlueMap API for chunk-rectangle markers

Target: Paper/Folia 1.21.11 plugin (`net.ekaii.redstone`, Java 21).
Goal: paint each loaded chunk on the BlueMap webapp with a 16x16 colored
rectangle whose color reflects the chunk's redstone-mode
(`vanilla` / `alternate-current` / `eigencraft` / `disabled`).

---

## 1. BlueMap version on the live server

```
$ ssh maison 'ls -la /fast/creaekaiiserver/plugins/ | grep -i blue'
drwxrwxr-x  5 …    9 Oct 13  2025 BlueMap                    # config dir
-rw-rw-r--  1 … 5.7M Oct  1  2025 bluemap-5.12-paper.jar.old # disabled
drwxr-xr-x  2 …    5 Jul 29  2025 BlueMapSignMarkers
```

- The currently-installed jar is `bluemap-5.12-paper.jar` but it has been
  renamed `.old`, i.e. **BlueMap is disabled at the moment**. No other
  `bluemap*.jar` exists anywhere under `/fast/creaekaiiserver`
  (`find … -name "bluemap*.jar*"` returned only the `.old` file).
- 5.12 supports Paper/Folia **1.20.6 → 1.21.10**, **not 1.21.11**.
  Its bundled BlueMapAPI is **2.7.6**.
  (See <https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.12>.)
- The latest releases that explicitly support Paper/Folia 1.21.11 (Java 21)
  are **v5.14, v5.15, v5.16**. v5.16 is the most recent.
  - v5.16 release: <https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.16>
    - Targets: `Paper/Folia 1.21.5 - 1.21.11`, Java 21, BlueMapAPI 2.7.7.
- v5.17+ require Java 25 and target Mojang's new "26.1" version stream
  (which corresponds to MC 1.21.12+, *not* 1.21.11).
  v5.20 (latest, 2026-04-12) targets Paper/Folia 26.1.1–26.1.2 with Java 25
  and ships BlueMapAPI 2.7.8.

**Recommendation for this project**: ask the operator to install
`bluemap-5.16-paper.jar` (Paper/Folia 1.21.11, Java 21, API 2.7.7); compile
against API **2.7.8** (forward-compatible patch, see §2).
Source: <https://api.github.com/repos/BlueMap-Minecraft/BlueMap/releases?per_page=15>

---

## 2. Maven coordinates for `bluemap-api`

Reposilite repository (the dashboard at `https://repo.bluecolored.de/`
returns 404 on directory URLs but the raw paths work):

```
GET https://repo.bluecolored.de/releases/de/bluecolored/bluemap-api/maven-metadata.xml
→ groupId    = de.bluecolored
  artifactId = bluemap-api
  release    = 2.7.8
  versions   = 2.7.3, 2.7.4, 2.7.5, 2.7.6, 2.7.7, 2.7.8
  lastUpdated= 2026-04-07
```

Artifact (cited):
<https://repo.bluecolored.de/releases/de/bluecolored/bluemap-api/2.7.8/bluemap-api-2.7.8.jar>
(68 KB; transitive deps: `com.flowpowered:flow-math:1.0.3`,
`com.google.code.gson:gson:2.8.9`, both `compile` per
`bluemap-api-2.7.8.pom`).

### Gradle (Kotlin DSL) — what to add to `plugin/build.gradle.kts`

```kotlin
repositories {
    // existing entries kept as-is; add:
    maven("https://repo.bluecolored.de/releases") {
        name = "BlueColored"
    }
}

dependencies {
    paperweight.foliaDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("it.unimi.dsi:fastutil:8.5.13")

    // BlueMap API — provided by the BlueMap plugin at runtime
    compileOnly("de.bluecolored:bluemap-api:2.7.8")
}
```

`compileOnly` is mandatory: BlueMap loads the API itself at runtime; shading
it would cause class-loader conflicts.
Source: <https://github.com/BlueMap-Minecraft/BlueMapAPI> README + wiki Home page.

### Maven equivalent (for completeness)

```xml
<repositories>
  <repository>
    <id>bluecolored</id>
    <url>https://repo.bluecolored.de/releases</url>
  </repository>
</repositories>

<dependency>
  <groupId>de.bluecolored</groupId>
  <artifactId>bluemap-api</artifactId>
  <version>2.7.8</version>
  <scope>provided</scope>
</dependency>
```

### `paper-plugin.yml` / `plugin.yml`

Declare BlueMap as a `softdepend` (or `loadafter` in paper-plugin format) so
the plugin loads even when BlueMap is absent (see §4 — the bridge no-ops in
that case):

```yaml
# plugin.yml
softdepend: [BlueMap]
```

```yaml
# paper-plugin.yml
dependencies:
  server:
    BlueMap: { load: BEFORE, required: false, join-classpath: true }
```

---

## 3. Code patterns

### 3.1 Public API surface (verified from sources jar 2.7.8)

Files I extracted and read directly:

- `de/bluecolored/bluemap/api/BlueMapAPI.java`
- `de/bluecolored/bluemap/api/BlueMapMap.java`
- `de/bluecolored/bluemap/api/BlueMapWorld.java`
- `de/bluecolored/bluemap/api/markers/MarkerSet.java`
- `de/bluecolored/bluemap/api/markers/ShapeMarker.java`
- `de/bluecolored/bluemap/api/markers/Marker.java`
- `de/bluecolored/bluemap/api/markers/ObjectMarker.java`
- `de/bluecolored/bluemap/api/math/Shape.java`
- `de/bluecolored/bluemap/api/math/Color.java`

Key facts (from javadoc / source — **this is the load-bearing part**):

| Concern | Truth from source |
| --- | --- |
| Registering a consumer | `BlueMapAPI.onEnable(Consumer<BlueMapAPI>)` and `onDisable(Consumer<BlueMapAPI>)`. If BlueMap is already up at registration, the enable consumer fires synchronously immediately. **Both consumers may be invoked off the main server thread** ("the consumer will likely be called asynchronously, **not** on the server-thread"). |
| Listing maps | `api.getMaps() : Collection<BlueMapMap>` (one entry per `maps:` block in BlueMap's config). `api.getWorld(Object) : Optional<BlueMapWorld>`; one world has 1..N maps via `world.getMaps()`. |
| Where MarkerSets live | **Per map**, not per world. `BlueMapMap.getMarkerSets() : Map<String, MarkerSet>` — modifiable, mutating it is the way to add/remove sets. |
| MarkerSet container | `MarkerSet.getMarkers()` returns the **internal `ConcurrentHashMap<String, Marker>`** (line 39 of `MarkerSet.java`). Putting/removing on it directly is safe and visible to BlueMap. Convenience: `markerSet.put(id, marker)`, `markerSet.remove(id)`. |
| Building a rectangle | `Shape.createRect(double x1, double z1, double x2, double z2)` returns a 4-point shape on the X/Z plane. **Important quirk**: `Shape` uses `Vector2d` whose `getY()` is treated as the **map z-coordinate** (per ShapeMarker javadoc: "the y-coordinates of the Shape's points are the z-coordinates in the map"). |
| `ShapeMarker` builder | `ShapeMarker.builder().label(s).shape(shape, y).position(x,y,z).fillColor(c).lineColor(c).lineWidth(2).depthTestEnabled(false).build()`. Defaults: `lineWidth=2`, `depthTest=true`, `lineColor=(255,0,0,1.0)`, `fillColor=(200,0,0,0.3)`. If `position` is omitted, builder calls `centerPosition()`. |
| Color | `de.bluecolored.bluemap.api.math.Color`. Constructors: `(int r, int g, int b)`, `(int r, int g, int b, float alpha)`, `(int rgb)`, `(int rgb, float alpha)`, `(String cssColorString)`. |
| Threading | `BlueMapAPI` class javadoc line 44 says **"This API is thread-save, so you can use it async, off the main-server-thread, to save performance!"** `MarkerSet` uses `ConcurrentHashMap` internally. So mutation from arbitrary Folia region threads is safe **with respect to BlueMap**, but you must still guard your own marker-id index. |
| Persistence | "Markers that you create are **not persistent!** … recreate markers each time `BlueMapAPI.onEnable()` fires." We don't care: our chunk modes are computed from per-chunk PDC at load time, so we re-emit on chunk-load anyway. |

### 3.2 Pattern: register at plugin load

```java
@Override
public void onEnable() {
    BlueMapAPI.onEnable(api -> bridge.attach(api));   // may run async
    BlueMapAPI.onDisable(api -> bridge.detach(api));  // may run async
}

@Override
public void onDisable() {
    BlueMapAPI.unregisterListener(bridge::attach); // optional cleanup
    BlueMapAPI.unregisterListener(bridge::detach);
}
```

### 3.3 Pattern: create a MarkerSet (one per BlueMapMap)

```java
MarkerSet set = MarkerSet.builder()
        .label("Redstone modes")
        .toggleable(true)
        .defaultHidden(false)
        .sorting(100)
        .build();
api.getMaps().forEach(map ->
        map.getMarkerSets().put("redstone-modes", set));
```

(One shared `MarkerSet` instance can live in several maps' marker-set maps.
If you want per-world isolation, build one per `BlueMapWorld` and only
attach it to maps whose `map.getWorld()` matches.)

### 3.4 Pattern: add a `ShapeMarker` for a 16x16 chunk

```java
// chunkX, chunkZ are Bukkit Chunk coords (block coords / 16).
double minX = chunkX * 16.0, minZ = chunkZ * 16.0;
double maxX = minX + 16.0,    maxZ = minZ + 16.0;

Shape rect = Shape.createRect(minX, minZ, maxX, maxZ);
float drawY = 65f; // sea-level-ish; visible above terrain in top-down view

ShapeMarker marker = ShapeMarker.builder()
        .label("Chunk " + chunkX + "," + chunkZ + " — " + mode.name())
        .shape(rect, drawY)
        .lineWidth(1)
        .lineColor(modeLineColor(mode))
        .fillColor(modeFillColor(mode))
        .depthTestEnabled(false)   // always visible from above
        .build();

set.put(chunkKey(chunkX, chunkZ), marker); // idempotent — replaces if present
```

`chunkKey(x,z)` = `x + ":" + z` (or include the world id if you share one
`MarkerSet` across worlds).

### 3.5 Pattern: update on mode change

Two ergonomic options — both are safe:

(a) **Replace the marker** (simpler, what we'll do):

```java
set.put(chunkKey(cx, cz), buildMarker(cx, cz, newMode)); // overwrites
```

(b) **Mutate in place** (saves an alloc; valid because field setters exist):

```java
ShapeMarker m = (ShapeMarker) set.get(chunkKey(cx, cz));
if (m == null) { set.put(...); return; }
m.setColors(modeLineColor(newMode), modeFillColor(newMode));
m.setLabel("Chunk " + cx + "," + cz + " — " + newMode.name());
```

For removal (e.g. chunk unload, mode set to `vanilla` if we choose not to
draw it): `set.remove(chunkKey(cx, cz))`.

### 3.6 Color palette per redstone mode

```java
import de.bluecolored.bluemap.api.math.Color;

// (line, fill) pairs — alpha kept low on fill so the map stays readable.
static Color modeLineColor(RedstoneMode mode) {
    return switch (mode) {
        case VANILLA       -> new Color(255, 255, 255, 0.0f); // transparent border
        case ALTERNATE_CURRENT -> new Color( 56, 142,  60, 0.9f); // green-700
        case EIGENCRAFT    -> new Color( 25, 118, 210, 0.9f); // blue-700
        case DISABLED      -> new Color(198,  40,  40, 0.95f); // red-800
    };
}
static Color modeFillColor(RedstoneMode mode) {
    return switch (mode) {
        case VANILLA       -> new Color(0, 0, 0, 0.0f);   // fully transparent
        case ALTERNATE_CURRENT -> new Color( 76, 175,  80, 0.20f); // green-500
        case EIGENCRAFT    -> new Color( 33, 150, 243, 0.20f); // blue-500
        case DISABLED      -> new Color(244,  67,  54, 0.30f); // red-500
    };
}
```

Vanilla deliberately renders as a fully-transparent rectangle: it occupies a
slot in the marker map (so toggles work) but is invisible. Alternative: skip
inserting a marker for `VANILLA` chunks at all (saves memory at scale —
Crea Ekaii has *many* chunks). Prefer the "skip" approach if marker count
exceeds ~50k; the BlueMap webapp loads them all client-side.

---

## 4. Folia compatibility

### 4.1 Threading constraints in BlueMapAPI calls

**TL;DR — none from BlueMap's side.** Every relevant class is thread-safe:

- `BlueMapAPI` javadoc (top of file, line 44):
  *"This API is thread-save, so you can use it async, off the
  main-server-thread, to save performance!"*
- `BlueMapAPI.onEnable(Consumer)` javadoc:
  *"The consumer will likely be called asynchronously, **not** on the
  server-thread."*
- `MarkerSet` stores markers in a `ConcurrentHashMap` (line 39 of
  `MarkerSet.java`); `BlueMapMap.getMarkerSets()` returns a "modifiable"
  Map — BlueMap implementations also use a `ConcurrentHashMap` here.

Practical consequence on Folia:

- You **may** call `set.put(...)`, `set.remove(...)`, `marker.setColors(...)`
  from any region thread, the global region thread, or an async pool —
  no scheduling needed.
- Bukkit/Folia API calls (`world.getChunks()`, reading chunk PDC, etc.)
  must still be issued from the appropriate region thread — that
  constraint is from Folia, not BlueMap.
- The recommended pattern is therefore: gather data via
  `RegionScheduler` / `GlobalRegionScheduler`, then push the resulting
  marker mutation to BlueMap directly from that thread (or from a
  `ForkJoinPool`/`Executors` task) without bouncing back through a
  scheduler.

### 4.2 Plugin lifecycle / Folia gotchas

- Folia has no `Bukkit.getScheduler().runTask` for plugin shutdown work.
  `onDisable()` runs once on the global region thread — fine for calling
  `BlueMapAPI.unregisterListener(...)` and clearing the marker set.
- Don't hold direct references to `Chunk` across threads. Encode chunk
  state as `(worldUid, chunkX, chunkZ, RedstoneMode)` and pass that DTO
  to the bridge.
- When BlueMap is reloaded (`/bluemap reload`), the API's enable consumer
  re-fires — keep the bridge idempotent (you'll re-build the MarkerSet
  from your in-memory cache).

---

## 5. Complete `BlueMapBridge` snippet (Paper/Folia 1.21.11, ~150 LOC)

Drop into `plugin/src/main/java/net/ekaii/redstone/bluemap/BlueMapBridge.java`.
Compile-only dep `de.bluecolored:bluemap-api:2.7.8` and `softdepend: BlueMap`
in `plugin.yml` (or `paper-plugin.yml` equivalent). All BlueMap calls are
issued on whatever thread invokes the bridge — they're thread-safe.

```java
package net.ekaii.redstone.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bridges per-chunk redstone-mode state to the BlueMap webapp by drawing a
 * 16x16 rectangle per chunk, colored by mode. Thread-safe: BlueMap's API is
 * documented thread-safe and {@link MarkerSet} uses a ConcurrentHashMap, so
 * mutation can come from any Folia region thread.
 *
 * <p>Lifecycle: call {@link #install()} from the plugin's onEnable, and
 * {@link #uninstall()} from onDisable. The bridge no-ops when the BlueMap
 * plugin is absent.</p>
 *
 * <p>Update flow: call {@link #setMode} on chunk-load and on mode-changes.
 * The bridge keeps an internal cache so it can rebuild the marker set when
 * BlueMap reloads.</p>
 */
public final class BlueMapBridge {

    public enum RedstoneMode { VANILLA, ALTERNATE_CURRENT, EIGENCRAFT, DISABLED }

    private static final String SET_ID = "redstone-modes";
    private static final String SET_LABEL = "Redstone modes";
    private static final float DRAW_Y = 65f;

    private final Plugin plugin;
    /** worldId -> (chunkKey -> mode); mirrors what's pushed to BlueMap. */
    private final Map<String, Map<Long, RedstoneMode>> cache = new ConcurrentHashMap<>();
    /** non-null while BlueMap is up; cleared on disable. */
    private volatile @Nullable BlueMapAPI api;

    private final Consumer<BlueMapAPI> onEnable = this::attach;
    private final Consumer<BlueMapAPI> onDisable = this::detach;

    public BlueMapBridge(Plugin plugin) { this.plugin = plugin; }

    public void install() {
        if (plugin.getServer().getPluginManager().getPlugin("BlueMap") == null) {
            plugin.getLogger().info("BlueMap not present — chunk-mode rendering disabled.");
            return;
        }
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    public void uninstall() {
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
        api = null;
    }

    /** Record/update a chunk's mode. Safe from any thread. */
    public void setMode(String worldId, int chunkX, int chunkZ, RedstoneMode mode) {
        long key = chunkKey(chunkX, chunkZ);
        cache.computeIfAbsent(worldId, w -> new ConcurrentHashMap<>()).put(key, mode);
        BlueMapAPI a = api;
        if (a != null) pushOne(a, worldId, chunkX, chunkZ, mode);
    }

    /** Drop a chunk from the overlay (e.g. on unload, optional). */
    public void clearChunk(String worldId, int chunkX, int chunkZ) {
        Map<Long, RedstoneMode> m = cache.get(worldId);
        if (m != null) m.remove(chunkKey(chunkX, chunkZ));
        BlueMapAPI a = api;
        if (a != null) {
            forEachMapOfWorld(a, worldId, map -> {
                MarkerSet s = map.getMarkerSets().get(SET_ID);
                if (s != null) s.remove(markerId(chunkX, chunkZ));
            });
        }
    }

    /* ---------- BlueMap callbacks ---------- */

    private void attach(BlueMapAPI a) {
        this.api = a;
        // Re-build the MarkerSet from cache (markers are non-persistent).
        for (Map.Entry<String, Map<Long, RedstoneMode>> wEntry : cache.entrySet()) {
            String worldId = wEntry.getKey();
            forEachMapOfWorld(a, worldId, map ->
                    map.getMarkerSets().computeIfAbsent(SET_ID, k -> newSet()));
            for (Map.Entry<Long, RedstoneMode> e : wEntry.getValue().entrySet()) {
                long k = e.getKey();
                pushOne(a, worldId, unpackX(k), unpackZ(k), e.getValue());
            }
        }
    }

    private void detach(BlueMapAPI a) { this.api = null; }

    /* ---------- internals ---------- */

    private static MarkerSet newSet() {
        return MarkerSet.builder()
                .label(SET_LABEL)
                .toggleable(true)
                .defaultHidden(false)
                .sorting(100)
                .build();
    }

    private void pushOne(BlueMapAPI a, String worldId, int cx, int cz, RedstoneMode mode) {
        forEachMapOfWorld(a, worldId, map -> {
            MarkerSet set = map.getMarkerSets().computeIfAbsent(SET_ID, k -> newSet());
            String id = markerId(cx, cz);
            if (mode == RedstoneMode.VANILLA) { set.remove(id); return; } // skip vanilla
            Marker existing = set.get(id);
            if (existing instanceof ShapeMarker sm) {
                sm.setColors(lineColor(mode), fillColor(mode));
                sm.setLabel(label(cx, cz, mode));
            } else {
                set.put(id, buildMarker(cx, cz, mode));
            }
        });
    }

    private static ShapeMarker buildMarker(int cx, int cz, RedstoneMode mode) {
        double x1 = cx * 16.0, z1 = cz * 16.0;
        Shape rect = Shape.createRect(x1, z1, x1 + 16.0, z1 + 16.0);
        return ShapeMarker.builder()
                .label(label(cx, cz, mode))
                .shape(rect, DRAW_Y)
                .lineWidth(1)
                .lineColor(lineColor(mode))
                .fillColor(fillColor(mode))
                .depthTestEnabled(false)
                .build();
    }

    private static void forEachMapOfWorld(BlueMapAPI a, String worldId,
                                          Consumer<BlueMapMap> action) {
        a.getWorld(worldId).ifPresent(w -> w.getMaps().forEach(action));
    }

    private static String label(int cx, int cz, RedstoneMode m) {
        return "Chunk " + cx + "," + cz + " — " + m.name().toLowerCase();
    }
    private static String markerId(int cx, int cz) { return "c:" + cx + ":" + cz; }
    private static long chunkKey(int cx, int cz) { return (((long) cx) << 32) ^ (cz & 0xffffffffL); }
    private static int unpackX(long k) { return (int) (k >> 32); }
    private static int unpackZ(long k) { return (int) k; }

    private static Color lineColor(RedstoneMode m) {
        return switch (m) {
            case VANILLA           -> new Color(0, 0, 0, 0f);
            case ALTERNATE_CURRENT -> new Color( 56, 142,  60, 0.9f);
            case EIGENCRAFT        -> new Color( 25, 118, 210, 0.9f);
            case DISABLED          -> new Color(198,  40,  40, 0.95f);
        };
    }
    private static Color fillColor(RedstoneMode m) {
        return switch (m) {
            case VANILLA           -> new Color(0, 0, 0, 0f);
            case ALTERNATE_CURRENT -> new Color( 76, 175,  80, 0.20f);
            case EIGENCRAFT        -> new Color( 33, 150, 243, 0.20f);
            case DISABLED          -> new Color(244,  67,  54, 0.30f);
        };
    }
}
```

Notes on the snippet:

- `forEachMapOfWorld` resolves the `BlueMapWorld` by the same string id you
  configured for the BlueMap world (typically the Bukkit world name; see
  BlueMap's `bluemap/configs/worlds/<name>.conf`). If your worldId differs
  (UUID, path), pass that — `BlueMapAPI.getWorld(Object)` accepts any of
  String/Path/UUID/world-object (javadoc lines 110-122 of `BlueMapAPI.java`).
- `setMode` dedupes via `ConcurrentHashMap.put` so callers can fire it on
  every chunk-load without bookkeeping. Idempotent on `attach()` reloads.
- `VANILLA` is intentionally not drawn (cleared if a marker existed).
  Flip to drawing a transparent rect if you want vanilla chunks to register
  in the toggle list.
- `markerId` keys are stable per world; if you ever share one MarkerSet
  across worlds, prefix with the world id.

---

## 6. URLs cited

- BlueMap repo: <https://github.com/BlueMap-Minecraft/BlueMap>
- BlueMap v5.20 (latest): <https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.20>
- BlueMap v5.16 (last 1.21.11/Java 21): <https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.16>
- BlueMapAPI repo: <https://github.com/BlueMap-Minecraft/BlueMapAPI>
- BlueMapAPI wiki: <https://github.com/BlueMap-Minecraft/BlueMapAPI/wiki>
- Reposilite root: <https://repo.bluecolored.de/>
- bluemap-api maven-metadata: <https://repo.bluecolored.de/releases/de/bluecolored/bluemap-api/maven-metadata.xml>
- bluemap-api 2.7.8 jar: <https://repo.bluecolored.de/releases/de/bluecolored/bluemap-api/2.7.8/bluemap-api-2.7.8.jar>
- bluemap-api 2.7.8 sources jar: <https://repo.bluecolored.de/releases/de/bluecolored/bluemap-api/2.7.8/bluemap-api-2.7.8-sources.jar>
- bluemap-api 2.7.8 POM: <https://repo.bluecolored.de/releases/de/bluecolored/bluemap-api/2.7.8/bluemap-api-2.7.8.pom>

---

## 7. Five-line implementation plan

1. Add `maven("https://repo.bluecolored.de/releases")` and
   `compileOnly("de.bluecolored:bluemap-api:2.7.8")` to
   `plugin/build.gradle.kts`; add `BlueMap` to `softdepend` /
   `paper-plugin.yml` `dependencies.server`.
2. Create `plugin/src/main/java/net/ekaii/redstone/bluemap/BlueMapBridge.java`
   with the snippet above; instantiate it in the main plugin class and call
   `bridge.install()` in `onEnable`, `bridge.uninstall()` in `onDisable`.
3. Wherever the plugin already computes/persists per-chunk redstone mode
   (PDC read on chunk-load, `/redstone-mode` command, config reload), call
   `bridge.setMode(world.getName(), chunk.getX(), chunk.getZ(), mode)` from
   the same Folia region thread — no scheduler hop needed (BlueMap API is
   thread-safe).
4. On chunk-unload (optional) call `bridge.clearChunk(...)`; on world-unload
   drop the entire world entry from the bridge's cache.
5. Verify by running v5.16 BlueMap on the live server, opening the webapp,
   toggling the "Redstone modes" set in the layer panel, and confirming
   colors match the chunks' PDC values.
