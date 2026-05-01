package net.ekaii.redstone.region.nms;

import net.ekaii.redstone.region.ac.AcRedstoneWireEvaluator;
import net.ekaii.redstone.region.config.ChunkRegistry;
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
 *
 * <p><b>Why reflective write on a {@code private final} field works (JDK 21).</b>
 * The {@code evaluator} field is {@code private final} but <em>non-static</em>.
 * Per JLS 17.5 + the JDK reflection spec, {@code Field.set} on an instance
 * final field is allowed once {@code setAccessible(true)} is called — the
 * "final" prohibition applies only to <em>static</em> finals (those may be
 * inlined by the JIT before the swap). For instance finals, the JIT does not
 * inline across {@code getfield}, so our subsequent reads see the new value.
 * If Mojang ever changes the field to {@code static} we will need a different
 * hook (e.g. ByteBuddy retransform); detect that by catching the swap failure
 * and logging — do not crash the plugin.
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
        AcRedstoneWireEvaluator ac    = new AcRedstoneWireEvaluator(wire);
        EigencraftWireEvaluator eig   = new EigencraftWireEvaluator(wire);
        DisabledWireEvaluator off     = new DisabledWireEvaluator(wire);
        DispatchingEvaluator dispatcher =
                new DispatchingEvaluator(wire, current, ac, eig, off, registry);
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
    public static DispatchingEvaluator installedDispatcher() { return INSTALLED; }
}
