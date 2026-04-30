package net.dedale.redstone.region.ac;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter that exposes the bundled (vendored) Alternate-Current
 * {@link WireHandler} as a {@link RedstoneWireEvaluator}, so it can be
 * dropped into {@code RedStoneWireBlock.evaluator} via the dispatcher.
 *
 * <h2>Folia thread-safety</h2>
 * Upstream's {@code WireHandler} carries unguarded mutable state and is built
 * one-per-{@link ServerLevel}. Under Folia, multiple region threads tick the
 * same level in parallel and would corrupt that shared state. This adapter
 * holds <strong>one {@code WireHandler} per (region thread, level)</strong>
 * via a thread-local map. Each region thread gets its own handler;
 * intra-handler state (the {@code nodes} map, search/update queues) never
 * crosses a thread boundary.
 *
 * <p>The handler is reset implicitly by AC's own snapshot semantics at the
 * start of every {@code onWire*} call, so we only need to ensure the same
 * handler is reused on the same thread for cache-locality benefits.
 */
public final class AcRedstoneWireEvaluator extends RedstoneWireEvaluator {

    /** Per-thread cache: {@code Map<ServerLevel, WireHandler>} owned by one region thread. */
    private static final ThreadLocal<Map<ServerLevel, WireHandler>> THREAD_HANDLERS =
            ThreadLocal.withInitial(WeakHashMap::new);

    /** Coarse counter for diagnostics. */
    private static final ConcurrentHashMap<String, Long> stats = new ConcurrentHashMap<>();

    public AcRedstoneWireEvaluator(RedStoneWireBlock wireBlock) {
        super(wireBlock);
    }

    private WireHandler handlerFor(ServerLevel level) {
        Map<ServerLevel, WireHandler> map = THREAD_HANDLERS.get();
        WireHandler h = map.get(level);
        if (h == null) {
            h = new WireHandler(level);
            map.put(level, h);
        }
        return h;
    }

    @Override
    public void updatePowerStrength(Level level, BlockPos pos, BlockState state,
                                    @Nullable Orientation orientation, boolean updateShape) {
        if (!(level instanceof ServerLevel sl)) {
            // Client-side or non-server level: fall through to no-op.
            return;
        }

        WireHandler h = handlerFor(sl);

        if (updateShape) {
            // Place case (caller is RedStoneWireBlock.onPlace -> updateSurroundingRedstone).
            h.onWireAdded(pos, state);
            stats.merge("added", 1L, Long::sum);
        } else if (!sl.getBlockState(pos).is(this.wireBlock)) {
            // Removal: the block at `pos` is no longer wire (already replaced).
            // `state` is the OLD wire state, passed by Paper for AC's removal accounting.
            h.onWireRemoved(pos, state);
            stats.merge("removed", 1L, Long::sum);
        } else {
            // Neighbor change: wire still present.
            h.onWireUpdated(pos, state, orientation);
            stats.merge("updated", 1L, Long::sum);
        }
    }

    public static long statValue(String key) { return stats.getOrDefault(key, 0L); }
}
