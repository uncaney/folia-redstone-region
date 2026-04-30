package net.dedale.redstone.region.nms;

import net.dedale.redstone.region.ac.AcRedstoneWireEvaluator;
import net.dedale.redstone.region.config.ChunkRegistry;
import net.dedale.redstone.region.config.RedstoneMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * Reads the per-chunk redstone mode from {@link ChunkRegistry} and dispatches each
 * call either to the captured original (vanilla) evaluator or to our
 * {@link AcRedstoneWireEvaluator}. Installed by reflection at plugin enable; see
 * {@link EvaluatorSwap}.
 */
public final class DispatchingEvaluator extends RedstoneWireEvaluator {

    private final RedstoneWireEvaluator vanilla;
    private final AcRedstoneWireEvaluator alternateCurrent;
    private final ChunkRegistry registry;

    public DispatchingEvaluator(RedStoneWireBlock wire,
                                RedstoneWireEvaluator vanilla,
                                AcRedstoneWireEvaluator alternateCurrent,
                                ChunkRegistry registry) {
        super(wire);
        this.vanilla = vanilla;
        this.alternateCurrent = alternateCurrent;
        this.registry = registry;
    }

    @Override
    public void updatePowerStrength(Level level, BlockPos pos, BlockState state,
                                    @Nullable Orientation orientation, boolean updateShape) {
        RedstoneMode mode = registry.mode(level, pos);
        if (mode == RedstoneMode.ALTERNATE_CURRENT) {
            alternateCurrent.updatePowerStrength(level, pos, state, orientation, updateShape);
        } else {
            vanilla.updatePowerStrength(level, pos, state, orientation, updateShape);
        }
    }

    public RedstoneWireEvaluator capturedVanilla() { return vanilla; }
    public AcRedstoneWireEvaluator alternateCurrent() { return alternateCurrent; }
}
