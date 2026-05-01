package net.ekaii.redstone.region.nms;

import net.ekaii.redstone.region.ac.AcRedstoneWireEvaluator;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.ekaii.redstone.region.timing.ChunkTimingTable;
import net.ekaii.redstone.region.timing.TimingPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * Reads the per-chunk redstone mode from {@link ChunkRegistry} and dispatches
 * each call to one of four evaluators: vanilla, alternate-current, eigencraft,
 * or disabled. Records per-chunk timing so the auto-AC scanner and the
 * /stats command have data to act on.
 *
 * <p><b>Why we extend {@link DefaultRedstoneWireEvaluator} (not the abstract
 * {@code RedstoneWireEvaluator}).</b> Paper's bundled Eigencraft/RedstoneWireTurbo
 * calls {@code RedStoneWireBlock.calculateCurrentChanges()}, which itself does
 * {@code ((DefaultRedstoneWireEvaluator) this.evaluator).calculateTargetStrength(...)}.
 * Since we replace the field with this dispatcher, the cast must succeed —
 * which means we must extend {@code DefaultRedstoneWireEvaluator}, not just
 * {@code RedstoneWireEvaluator}. The inherited {@code calculateTargetStrength}
 * is fine: it computes the vanilla power, which is what Eigencraft's per-wire
 * recompute step needs.
 */
public final class DispatchingEvaluator extends DefaultRedstoneWireEvaluator {

    private final RedstoneWireEvaluator vanilla;
    private final AcRedstoneWireEvaluator alternateCurrent;
    private final EigencraftWireEvaluator eigencraft;
    private final DisabledWireEvaluator disabled;
    private final ChunkRegistry registry;
    private volatile ChunkTimingTable timing;   // late-bound: nullable until plugin onEnable wires it
    private volatile TimingPolicy policy = TimingPolicy.DEFAULT;

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
    public void setTimingPolicy(TimingPolicy p)    { this.policy = (p != null) ? p : TimingPolicy.DEFAULT; }

    /**
     * Hot path. Branch order optimised for the steady state:
     * <ol>
     *   <li>read mode (one StampedLock optimistic read; ~5 ns)</li>
     *   <li>ask the policy whether to time (one volatile load + 1-3 cmps; ~3 ns when {@code mode=ALL})</li>
     *   <li>if not timing → direct dispatch, zero {@code System.nanoTime()} call</li>
     *   <li>if timing → bracket the dispatch with two {@code nanoTime()} reads + {@link ChunkTimingTable#record}</li>
     * </ol>
     */
    @Override
    public void updatePowerStrength(Level level, BlockPos pos, BlockState state,
                                    @Nullable Orientation orientation, boolean updateShape) {
        RedstoneMode mode = registry.mode(level, pos);
        TimingPolicy p = this.policy;
        ChunkTimingTable t = this.timing;
        boolean record = (t != null) && p.shouldRecord(mode);

        if (!record) {
            // Fast path: no timing → no nanoTime call, just dispatch
            switch (mode) {
                case ALTERNATE_CURRENT -> alternateCurrent.updatePowerStrength(level, pos, state, orientation, updateShape);
                case EIGENCRAFT        -> eigencraft.updatePowerStrength(level, pos, state, orientation, updateShape);
                case DISABLED          -> disabled.updatePowerStrength(level, pos, state, orientation, updateShape);
                case VANILLA           -> vanilla.updatePowerStrength(level, pos, state, orientation, updateShape);
            }
            return;
        }

        // Slow path: time it
        long t0 = System.nanoTime();
        try {
            switch (mode) {
                case ALTERNATE_CURRENT -> alternateCurrent.updatePowerStrength(level, pos, state, orientation, updateShape);
                case EIGENCRAFT        -> eigencraft.updatePowerStrength(level, pos, state, orientation, updateShape);
                case DISABLED          -> disabled.updatePowerStrength(level, pos, state, orientation, updateShape);
                case VANILLA           -> vanilla.updatePowerStrength(level, pos, state, orientation, updateShape);
            }
        } finally {
            if (level instanceof ServerLevel sl) {
                t.record(sl.dimension().identifier().toString(),
                        pos.getX() >> 4, pos.getZ() >> 4,
                        System.nanoTime() - t0);
            }
        }
    }

    public TimingPolicy timingPolicy() { return policy; }

    public RedstoneWireEvaluator      capturedVanilla()    { return vanilla; }
    public AcRedstoneWireEvaluator    alternateCurrent()   { return alternateCurrent; }
    public EigencraftWireEvaluator    eigencraft()         { return eigencraft; }
    public DisabledWireEvaluator      disabled()           { return disabled; }
}
