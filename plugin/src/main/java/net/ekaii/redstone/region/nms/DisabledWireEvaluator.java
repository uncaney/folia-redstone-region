package net.ekaii.redstone.region.nms;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * No-op evaluator: redstone wire never recomputes its power. The wire keeps
 * whatever {@code POWER} it had before being marked DISABLED. Useful for
 * archive zones / decorative wire that should not consume any cycles.
 *
 * <p>Note: this disables only <em>updates</em>. The wire still emits its
 * current power to neighbors via {@code getSignal} / {@code getDirectSignal}
 * (those are read paths, not on the dispatcher). To fully freeze a build,
 * pair this with a region claim that prevents re-place.
 */
public final class DisabledWireEvaluator extends RedstoneWireEvaluator {

    public DisabledWireEvaluator(RedStoneWireBlock wireBlock) {
        super(wireBlock);
    }

    @Override
    public void updatePowerStrength(Level level, BlockPos pos, BlockState state,
                                    @Nullable Orientation orientation, boolean updateShape) {
        // intentional no-op
    }
}
