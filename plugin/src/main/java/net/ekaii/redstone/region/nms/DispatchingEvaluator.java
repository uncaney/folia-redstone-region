package net.ekaii.redstone.region.nms;

import net.ekaii.redstone.region.ac.AcRedstoneWireEvaluator;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.ekaii.redstone.region.timing.ChunkTimingTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * Reads the per-chunk redstone mode from {@link ChunkRegistry} and dispatches
 * each call to one of four evaluators: vanilla, alternate-current, eigencraft,
 * or disabled. Records per-chunk timing so the auto-AC scanner and the
 * /stats command have data to act on.
 */
public final class DispatchingEvaluator extends RedstoneWireEvaluator {

    private final RedstoneWireEvaluator vanilla;
    private final AcRedstoneWireEvaluator alternateCurrent;
    private final EigencraftWireEvaluator eigencraft;
    private final DisabledWireEvaluator disabled;
    private final ChunkRegistry registry;
    private volatile ChunkTimingTable timing;   // late-bound: nullable until plugin onEnable wires it

    public DispatchingEvaluator(RedStoneWireBlock wire,
                                RedstoneWireEvaluator vanilla,
                                AcRedstoneWireEvaluator alternateCurrent,
                                EigencraftWireEvaluator eigencraft,
                                DisabledWireEvaluator disabled,
                                ChunkRegistry registry) {
        super(wire);
        this.vanilla = vanilla;
        this.alternateCurrent = alternateCurrent;
        this.eigencraft = eigencraft;
        this.disabled = disabled;
        this.registry = registry;
    }

    public void setTimingTable(ChunkTimingTable t) { this.timing = t; }

    @Override
    public void updatePowerStrength(Level level, BlockPos pos, BlockState state,
                                    @Nullable Orientation orientation, boolean updateShape) {
        RedstoneMode mode = registry.mode(level, pos);
        long t0 = System.nanoTime();
        try {
            switch (mode) {
                case ALTERNATE_CURRENT -> alternateCurrent.updatePowerStrength(level, pos, state, orientation, updateShape);
                case EIGENCRAFT        -> eigencraft.updatePowerStrength(level, pos, state, orientation, updateShape);
                case DISABLED          -> disabled.updatePowerStrength(level, pos, state, orientation, updateShape);
                case VANILLA           -> vanilla.updatePowerStrength(level, pos, state, orientation, updateShape);
            }
        } finally {
            ChunkTimingTable t = this.timing;
            if (t != null && level instanceof ServerLevel sl) {
                t.record(sl.dimension().identifier().toString(),
                        pos.getX() >> 4, pos.getZ() >> 4,
                        System.nanoTime() - t0);
            }
        }
    }

    public RedstoneWireEvaluator      capturedVanilla()    { return vanilla; }
    public AcRedstoneWireEvaluator    alternateCurrent()   { return alternateCurrent; }
    public EigencraftWireEvaluator    eigencraft()         { return eigencraft; }
    public DisabledWireEvaluator      disabled()           { return disabled; }
}
