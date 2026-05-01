package net.ekaii.redstone.region.cmd;

import net.ekaii.redstone.region.config.RedstoneMode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Thread-safe ring buffer of the last N flip-batches for {@code /redstone-region undo}.
 * One entry per command-batch (e.g. a /fill of 81 chunks = one entry of 81 chunk-flips).
 *
 * <p>Bounded at {@link #CAPACITY}; oldest entries are dropped silently. Per-actor
 * undo: {@link #popLatestBy} returns the most recent batch made by a specific
 * actor (or the most recent batch overall if {@code actor == null}).
 */
public final class UndoBuffer {

    public static final int CAPACITY = 64;

    public record FlipChange(int chunkX, int chunkZ, RedstoneMode prev, RedstoneMode next) {}

    public static final class Batch {
        public final long ts;
        public final UUID actorUuid;     // nullable
        public final String actorName;
        public final String dim;
        public final String reason;
        public final java.util.List<FlipChange> changes;

        public Batch(long ts, UUID actorUuid, String actorName, String dim, String reason, java.util.List<FlipChange> changes) {
            this.ts = ts; this.actorUuid = actorUuid; this.actorName = actorName;
            this.dim = dim; this.reason = reason; this.changes = changes;
        }
    }

    private final Deque<Batch> deque = new ArrayDeque<>(CAPACITY);

    public synchronized void push(Batch b) {
        if (b.changes.isEmpty()) return;
        if (deque.size() >= CAPACITY) deque.pollFirst();
        deque.addLast(b);
    }

    /**
     * Pop the most recent batch by the given actor. If {@code actorUuid} is null,
     * pop the most recent batch overall. Returns {@code null} if nothing matches.
     */
    public synchronized Batch popLatestBy(UUID actorUuid) {
        var it = deque.descendingIterator();
        while (it.hasNext()) {
            Batch b = it.next();
            if (actorUuid == null || actorUuid.equals(b.actorUuid)) {
                it.remove();
                return b;
            }
        }
        return null;
    }

    public synchronized int size() { return deque.size(); }
    public synchronized void clear() { deque.clear(); }
}
