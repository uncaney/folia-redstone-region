package net.ekaii.redstone.region.nms;

import io.papermc.paper.redstone.RedstoneWireTurbo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.jspecify.annotations.Nullable;

import java.util.WeakHashMap;

/**
 * Wraps Paper's {@link RedstoneWireTurbo} (Eigencraft) as a {@link RedstoneWireEvaluator}
 * so it can be plugged into the per-chunk dispatcher.
 *
 * <p>Like AC, Eigencraft's Turbo carries mutable state. Paper itself stores
 * <em>one Turbo per Folia region</em> via {@code RegionizedWorldData}, so the
 * implementation is already region-safe upstream. We mirror that by holding
 * one {@code RedstoneWireTurbo} per region thread (ThreadLocal) and the same
 * thread-locality contract.
 */
public final class EigencraftWireEvaluator extends RedstoneWireEvaluator {

    private static final ThreadLocal<WeakHashMap<RedStoneWireBlock, RedstoneWireTurbo>> CACHE =
            ThreadLocal.withInitial(WeakHashMap::new);

    public EigencraftWireEvaluator(RedStoneWireBlock wireBlock) {
        super(wireBlock);
    }

    private RedstoneWireTurbo turbo() {
        return CACHE.get().computeIfAbsent(this.wireBlock, RedstoneWireTurbo::new);
    }

    /**
     * Mojang's single-method API ↔ Paper's Turbo expects a {@code source}
     * BlockPos (where the signal came from). Paper synthesises it from the
     * orientation in {@code RedStoneWireBlock.updateSurroundingRedstone}; we
     * replicate the same conversion here.
     */
    @Override
    public void updatePowerStrength(Level level, BlockPos pos, BlockState state,
                                    @Nullable Orientation orientation, boolean updateShape) {
        BlockPos source = null;
        if (orientation != null) {
            source = pos.relative(orientation.getFront().getOpposite());
        }
        turbo().updateSurroundingRedstone(level, pos, state, source);
    }
}
