package net.ekaii.redstone.region.timing;

import net.ekaii.redstone.region.util.ChunkKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-chunk running statistics on time spent in the redstone evaluator.
 * Keyed by long-packed {@code (chunkX, chunkZ)}.
 *
 * <p>Each cell stores a {@code count} and a {@code totalNanos} via {@link LongAdder}
 * for lock-free aggregation across region threads. Sample reads (for /stats) are
 * eventually-consistent — they use {@link LongAdder#sum} which is fine for stats.
 */
public final class ChunkTimingTable {

    public static final class Cell {
        final LongAdder count = new LongAdder();
        final LongAdder totalNs = new LongAdder();
        // Track a coarse maximum (Java has no atomic max for long without CAS-loop)
        volatile long maxNs;

        void record(long ns) {
            count.add(1);
            totalNs.add(ns);
            // monotonic-ish max — slight under-update under contention is fine
            long cur = maxNs;
            if (ns > cur) maxNs = ns;
        }

        public long count()    { return count.sum(); }
        public long totalNs()  { return totalNs.sum(); }
        public long maxNs()    { return maxNs; }
        public double avgUs()  { long c = count(); return c == 0 ? 0 : (totalNs() / 1_000.0) / c; }
        public double avgMs()  { return avgUs() / 1_000.0; }
    }

    private final Map<String, Map<Long, Cell>> byDim = new ConcurrentHashMap<>();

    public void record(String dim, int chunkX, int chunkZ, long ns) {
        Map<Long, Cell> m = byDim.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        long key = ChunkKey.pack(chunkX, chunkZ);
        m.computeIfAbsent(key, k -> new Cell()).record(ns);
    }

    public Cell get(String dim, int chunkX, int chunkZ) {
        Map<Long, Cell> m = byDim.get(dim);
        if (m == null) return null;
        return m.get(ChunkKey.pack(chunkX, chunkZ));
    }

    public void resetDim(String dim) { byDim.remove(dim); }
    public void resetAll()            { byDim.clear(); }

    /** Top-N hottest chunks across all dimensions (by total ns). */
    public List<Hot> top(int limit) {
        List<Hot> all = new ArrayList<>();
        for (var e : byDim.entrySet()) {
            String d = e.getKey();
            for (var c : e.getValue().entrySet()) {
                Cell cell = c.getValue();
                if (cell.count() < 5) continue;   // ignore noise
                long key = c.getKey();
                all.add(new Hot(d, ChunkKey.unpackX(key), ChunkKey.unpackZ(key), cell));
            }
        }
        all.sort(Comparator.comparingLong((Hot h) -> h.cell.totalNs()).reversed());
        if (all.size() > limit) all.subList(limit, all.size()).clear();
        return all;
    }

    public record Hot(String dim, int cx, int cz, Cell cell) {}
}
