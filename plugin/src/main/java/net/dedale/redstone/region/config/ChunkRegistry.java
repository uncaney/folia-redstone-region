package net.dedale.redstone.region.config;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.dedale.redstone.region.util.ChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

/**
 * Per-(level, chunkPos) redstone-mode registry. Read on every wire update on the
 * region thread that owns the chunk; writes happen at chunk-load (PDC restore) and
 * via /redstone-region commands.
 */
public final class ChunkRegistry {

    private static final ChunkRegistry INSTANCE = new ChunkRegistry();
    public static ChunkRegistry get() { return INSTANCE; }
    private ChunkRegistry() {}

    private static final class PerLevel {
        final Long2ByteOpenHashMap modes = new Long2ByteOpenHashMap();
        final StampedLock lock = new StampedLock();
        PerLevel() { modes.defaultReturnValue(RedstoneMode.VANILLA.id()); }
    }

    private final Map<ResourceKey<Level>, PerLevel> byLevel = new ConcurrentHashMap<>();

    private PerLevel level(ResourceKey<Level> key) {
        return byLevel.computeIfAbsent(key, k -> new PerLevel());
    }

    /** Hot path. Called once per wire update on the owning region thread. Lock-free in steady state. */
    public RedstoneMode mode(Level level, BlockPos pos) {
        PerLevel pl = byLevel.get(level.dimension());
        if (pl == null) return RedstoneMode.VANILLA;
        long ck = ChunkKey.pack(pos.getX() >> 4, pos.getZ() >> 4);
        long stamp = pl.lock.tryOptimisticRead();
        byte v = pl.modes.get(ck);
        if (pl.lock.validate(stamp)) return RedstoneMode.fromId(v);
        stamp = pl.lock.readLock();
        try { return RedstoneMode.fromId(pl.modes.get(ck)); }
        finally { pl.lock.unlockRead(stamp); }
    }

    public RedstoneMode modeOfChunk(ResourceKey<Level> levelKey, int chunkX, int chunkZ) {
        PerLevel pl = byLevel.get(levelKey);
        if (pl == null) return RedstoneMode.VANILLA;
        long ck = ChunkKey.pack(chunkX, chunkZ);
        long stamp = pl.lock.readLock();
        try { return RedstoneMode.fromId(pl.modes.get(ck)); }
        finally { pl.lock.unlockRead(stamp); }
    }

    /** Returns the previous mode. */
    public RedstoneMode setMode(ResourceKey<Level> levelKey, int chunkX, int chunkZ, RedstoneMode mode) {
        PerLevel pl = level(levelKey);
        long ck = ChunkKey.pack(chunkX, chunkZ);
        long stamp = pl.lock.writeLock();
        try {
            byte prev;
            if (mode == RedstoneMode.VANILLA) {
                prev = pl.modes.remove(ck); // back to default → drop the entry
            } else {
                prev = pl.modes.put(ck, mode.id());
            }
            return RedstoneMode.fromId(prev);
        } finally {
            pl.lock.unlockWrite(stamp);
        }
    }

    /** Number of non-default chunks for a level (debug / info). */
    public int trackedCount(ResourceKey<Level> levelKey) {
        PerLevel pl = byLevel.get(levelKey);
        if (pl == null) return 0;
        long stamp = pl.lock.readLock();
        try { return pl.modes.size(); }
        finally { pl.lock.unlockRead(stamp); }
    }

    /** Drop in-memory state for a level (e.g. on world unload). */
    public void evictLevel(ResourceKey<Level> levelKey) {
        byLevel.remove(levelKey);
    }
}
