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

    /**
     * Soft cap on total tracked cells across all dimensions. When exceeded,
     * the lowest-totalNs entries are evicted in batches. Prevents unbounded
     * growth on long-lived servers that visit many chunks.
     */
    private static final int CELL_SOFT_CAP = 10_000;


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
        m.computeIfAbsent(key, k -> {
            // Lazy eviction check on insert — cheap, runs only when adding a new cell
            maybeEvictColdest();
            return new Cell();
        }).record(ns);
    }

    /** Crude LRU-ish: when over cap, drop the 25% lowest-totalNs cells. */
    private void maybeEvictColdest() {
        int total = 0;
        for (Map<Long, Cell> m : byDim.values()) total += m.size();
        if (total <= CELL_SOFT_CAP) return;
        // collect all cells, sort by totalNs ascending, drop first 25%
        List<Object[]> all = new ArrayList<>(total);
        for (var e : byDim.entrySet())
            for (var c : e.getValue().entrySet())
                all.add(new Object[]{ e.getKey(), c.getKey(), c.getValue().totalNs() });
        all.sort(Comparator.comparingLong(o -> (long) o[2]));
        int toDrop = total / 4;
        for (int i = 0; i < toDrop && i < all.size(); i++) {
            Map<Long, Cell> m = byDim.get((String) all.get(i)[0]);
            if (m != null) m.remove((long) all.get(i)[1]);
        }
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
