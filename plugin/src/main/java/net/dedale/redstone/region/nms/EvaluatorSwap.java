package net.dedale.redstone.region.nms;

import net.dedale.redstone.region.ac.AcRedstoneWireEvaluator;
import net.dedale.redstone.region.config.ChunkRegistry;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;

import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * Installs the {@link DispatchingEvaluator} onto {@code Blocks.REDSTONE_WIRE}'s
 * private {@code evaluator} field. Reverse operation supported for graceful
 * unloading on disable, though that is best-effort: any wire updates that
 * happen between disable and full server shutdown go back to vanilla.
 */
public final class EvaluatorSwap {

    private EvaluatorSwap() {}

    private static volatile Field FIELD;
    private static volatile RedstoneWireEvaluator ORIGINAL;
    private static volatile DispatchingEvaluator INSTALLED;

    private static synchronized Field field() throws NoSuchFieldException {
        if (FIELD == null) {
            Field f = RedStoneWireBlock.class.getDeclaredField("evaluator");
            f.setAccessible(true);
            FIELD = f;
        }
        return FIELD;
    }

    public static synchronized void install(ChunkRegistry registry, Logger log) throws ReflectiveOperationException {
        if (INSTALLED != null) {
            log.warning("evaluator already swapped — refusing to re-install");
            return;
        }
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        Field f = field();
        RedstoneWireEvaluator current = (RedstoneWireEvaluator) f.get(wire);
        if (current instanceof DispatchingEvaluator de) {
            log.warning("evaluator already a DispatchingEvaluator — capturing as installed");
            INSTALLED = de;
            ORIGINAL = de.capturedVanilla();
            return;
        }
        ORIGINAL = current;
        AcRedstoneWireEvaluator ac = new AcRedstoneWireEvaluator(wire);
        DispatchingEvaluator dispatcher = new DispatchingEvaluator(wire, current, ac, registry);
        f.set(wire, dispatcher);
        INSTALLED = dispatcher;
        log.info("redstone evaluator swapped: " + current.getClass().getName()
                + " -> " + dispatcher.getClass().getName());
    }

    public static synchronized void uninstall(Logger log) throws ReflectiveOperationException {
        if (INSTALLED == null) return;
        RedStoneWireBlock wire = (RedStoneWireBlock) Blocks.REDSTONE_WIRE;
        Field f = field();
        RedstoneWireEvaluator current = (RedstoneWireEvaluator) f.get(wire);
        if (current != INSTALLED) {
            log.warning("evaluator no longer ours (now " + current.getClass().getName()
                    + ") — leaving in place");
            INSTALLED = null;
            return;
        }
        f.set(wire, ORIGINAL);
        log.info("redstone evaluator restored to " + ORIGINAL.getClass().getName());
        INSTALLED = null;
        ORIGINAL = null;
    }

    public static boolean isInstalled() { return INSTALLED != null; }
}
