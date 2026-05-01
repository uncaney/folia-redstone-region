package net.ekaii.redstone.region.config;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.ekaii.redstone.region.util.ChunkKey;
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
 *
 * <p><b>Why {@link StampedLock} and not {@link ConcurrentHashMap}.</b>
 * The hot path runs <em>inside</em> the redstone evaluator, ~once per wire per
 * tick on AC-marked chunks. A {@code ConcurrentHashMap.get} pays a {@code volatile}
 * read + a CAS for the iteration counter on each call; on dust-heavy networks
 * that overhead would dominate the dispatch. A {@code StampedLock} optimistic
 * read is one plain field read for the stamp, one plain map read, one
 * {@code validate} that's a {@code volatile} read — and steady-state
 * contention is zero (writes only on chunk-load and on /redstone-region set).
 * The fallback to {@code readLock()} on stamp invalidation is rare in practice.
 *
 * <p>Do not "simplify" this to {@code ConcurrentHashMap<Long,Byte>} without
 * measuring — the dispatch overhead becomes visible on 32×32+ dust grids.
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

    /**
     * Snapshot of every non-default chunk in a level. List of long-packed
     * {@code (chunkX, chunkZ)} keys. Caller decodes via {@link net.ekaii.redstone.region.util.ChunkKey#unpackX}.
     * Held under the level's read-lock for the duration of the iteration.
     */
    public long[] snapshotKeys(ResourceKey<Level> levelKey) {
        PerLevel pl = byLevel.get(levelKey);
        if (pl == null) return new long[0];
        long stamp = pl.lock.readLock();
        try {
            long[] out = new long[pl.modes.size()];
            int i = 0;
            for (var it = pl.modes.long2ByteEntrySet().fastIterator(); it.hasNext(); ) {
                out[i++] = it.next().getLongKey();
            }
            return out;
        } finally {
            pl.lock.unlockRead(stamp);
        }
    }

    /** Drop in-memory state for a level (e.g. on world unload). */
    public void evictLevel(ResourceKey<Level> levelKey) {
        byLevel.remove(levelKey);
    }
}
