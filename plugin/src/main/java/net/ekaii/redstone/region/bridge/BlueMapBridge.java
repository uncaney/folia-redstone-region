package net.ekaii.redstone.region.bridge;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.ekaii.redstone.region.config.RedstoneMode;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bridges per-chunk redstone-mode state to the BlueMap webapp by drawing a
 * 16×16 rectangle per chunk, colored by mode. Soft-dep — no-op when BlueMap
 * is absent.
 *
 * <p>BlueMap's API is documented thread-safe and {@code MarkerSet} uses a
 * {@code ConcurrentHashMap}, so mutation can come from any Folia region thread.
 * Markers are non-persistent on BlueMap reload — we keep an in-memory cache
 * to rebuild the {@link MarkerSet} on every {@code BlueMapAPI.onEnable}.
 */
public final class BlueMapBridge {

    private static final String SET_ID    = "redstone-region";
    private static final String SET_LABEL = "Redstone modes";
    private static final float DRAW_Y     = 65f;

    private final Plugin plugin;
    private final Map<String, Map<Long, RedstoneMode>> cache = new ConcurrentHashMap<>();
    private volatile @Nullable BlueMapAPI api;

    /**
     * Lazily created in {@link #install()}; only resolved if BlueMap is on the
     * classpath. Storing them as {@code Consumer<BlueMapAPI>} fields would
     * force lambda metafactory to resolve {@code BlueMapAPI} at construction
     * time — which crashes when BlueMap is absent.
     */
    private Consumer<BlueMapAPI> onEnableCb;
    private Consumer<BlueMapAPI> onDisableCb;

    public BlueMapBridge(Plugin plugin) { this.plugin = plugin; }

    public void install() {
        if (plugin.getServer().getPluginManager().getPlugin("BlueMap") == null) {
            plugin.getLogger().info("BlueMap not installed — chunk-mode overlay disabled");
            return;
        }
        // From here on it's safe to touch BlueMap types — the plugin is loaded.
        this.onEnableCb  = this::attach;
        this.onDisableCb = this::detach;
        BlueMapAPI.onEnable(onEnableCb);
        BlueMapAPI.onDisable(onDisableCb);
        plugin.getLogger().info("BlueMap bridge installed");
    }

    public void uninstall() {
        if (onEnableCb != null) BlueMapAPI.unregisterListener(onEnableCb);
        if (onDisableCb != null) BlueMapAPI.unregisterListener(onDisableCb);
        api = null;
    }

    /** Record/update a chunk's mode. Safe from any thread. No-op when BlueMap not installed. */
    public void setMode(String worldId, int chunkX, int chunkZ, RedstoneMode mode) {
        if (onEnableCb == null) return;   // BlueMap absent
        long key = chunkKey(chunkX, chunkZ);
        cache.computeIfAbsent(worldId, w -> new ConcurrentHashMap<>()).put(key, mode);
        BlueMapAPI a = api;
        if (a != null) pushOne(a, worldId, chunkX, chunkZ, mode);
    }

    public void clearChunk(String worldId, int chunkX, int chunkZ) {
        if (onEnableCb == null) return;
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

    private void attach(BlueMapAPI a) {
        this.api = a;
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
            if (mode == RedstoneMode.VANILLA) { set.remove(id); return; }
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

    private static void forEachMapOfWorld(BlueMapAPI a, String worldId, Consumer<BlueMapMap> action) {
        a.getWorld(worldId).ifPresent(w -> w.getMaps().forEach(action));
    }

    private static String label(int cx, int cz, RedstoneMode m) {
        return "Chunk " + cx + "," + cz + " — " + m.slug();
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
