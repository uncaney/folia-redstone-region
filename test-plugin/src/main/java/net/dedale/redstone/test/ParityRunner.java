package net.dedale.redstone.test;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.dedale.redstone.region.config.ChunkRegistry;
import net.dedale.redstone.region.config.RedstoneMode;
import net.dedale.redstone.test.contraptions.Contraption;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Builds one contraption in a vanilla zone and an identical copy in an
 * alternate-current zone, then samples both for K ticks and asserts byte-for-byte
 * equality of the output sequences.
 *
 * <p>Layout (default y=64 in flat world):
 * <pre>
 *   x = 100..1000     vanilla zone   (chunks left as default → vanilla evaluator)
 *   x = 2000..3000    alternate-current zone (chunks marked via ChunkRegistry)
 * </pre>
 * Each contraption gets its own (origin_v, origin_ac) inside the corresponding zone,
 * 64 blocks apart so consecutive contraptions don't bleed into each other.
 */
public final class ParityRunner {

    private static final int Y = 64;
    private static final int VANILLA_X0 = 100;
    private static final int AC_X0 = 2000;
    private static final int LANE_Z = 0;
    private static final int LANE_PITCH = 64;

    private static final int WARMUP_TICKS = 5;
    private static final int RUN_TICKS = 60;
    private static final int OFF_AT_TICK = 30;   // toggle input off mid-run

    private final Plugin plugin;
    private final World world;
    private final Logger log;

    public ParityRunner(Plugin plugin, World world, Logger log) {
        this.plugin = plugin;
        this.world = world;
        this.log = log;
    }

    public CompletableFuture<RunReport.Case> run(Contraption c, int laneIndex) {
        Location oVan = new Location(world, VANILLA_X0, Y, LANE_Z + laneIndex * LANE_PITCH);
        Location oAc  = new Location(world, AC_X0, Y, LANE_Z + laneIndex * LANE_PITCH);
        long startNs = System.nanoTime();

        register(oVan, c);
        register(oAc, c);
        return runOn(world, oVan.getBlockX() >> 4, oVan.getBlockZ() >> 4, () -> c.build(oVan))
                .thenCompose(v -> runOn(world, oAc.getBlockX() >> 4, oAc.getBlockZ() >> 4, () -> {
                    markChunks(oAc, c, RedstoneMode.ALTERNATE_CURRENT);
                    c.build(oAc);
                }))
                .thenCompose(v -> sampleBoth(c, oVan, oAc))
                .handle((samples, err) -> {
                    try { return finish(c, samples, err, startNs); }
                    finally { unregister(oVan); unregister(oAc); }
                });
    }

    /* ---------------- Build & sample ---------------- */

    private CompletableFuture<TickSamples> sampleBoth(Contraption c, Location oVan, Location oAc) {
        TickSamples samples = new TickSamples();
        long startTick = world.getFullTime();

        // Schedule sampling on each contraption's region. Each tick we record (tick, vanillaOut, acOut).
        ScheduledTask vanTask = scheduleSampling(oVan, startTick, samples, true);
        ScheduledTask acTask = scheduleSampling(oAc, startTick, samples, false);

        // Toggle ON immediately, OFF at OFF_AT_TICK.
        runOn(world, oVan.getBlockX() >> 4, oVan.getBlockZ() >> 4, () -> c.setInput(oVan, true));
        runOn(world, oAc.getBlockX() >> 4, oAc.getBlockZ() >> 4, () -> c.setInput(oAc, true));

        // Schedule the OFF toggle.
        Bukkit.getRegionScheduler().runDelayed(plugin, oVan, $ -> c.setInput(oVan, false), OFF_AT_TICK);
        Bukkit.getRegionScheduler().runDelayed(plugin, oAc, $ -> c.setInput(oAc, false), OFF_AT_TICK);

        // Resolve the future after the full sampling window.
        CompletableFuture<TickSamples> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, $ -> {
            try { vanTask.cancel(); } catch (Throwable ignored) {}
            try { acTask.cancel(); } catch (Throwable ignored) {}
            done.complete(samples);
        }, WARMUP_TICKS + RUN_TICKS + 5);

        return done;
    }

    private ScheduledTask scheduleSampling(Location origin, long startTick, TickSamples samples, boolean isVanilla) {
        return Bukkit.getRegionScheduler().runAtFixedRate(plugin, origin, task -> {
            long t = world.getFullTime() - startTick;
            if (t < 0) return;
            int output = sampleOutput(origin, isVanilla);
            samples.record(t, output, isVanilla);
            if (t > WARMUP_TICKS + RUN_TICKS + 4) task.cancel();
        }, 1, 1);
    }

    private int sampleOutput(Location origin, boolean isVanilla) {
        // We need the contraption to do this — it knows where the lamp is.
        // Stored at the start of run() in a thread-local lookup keyed by origin.
        SamplingContraption sc = SAMPLERS.get(origin);
        if (sc == null) return -1;
        return sc.contraption.sampleOutput(origin);
    }

    /* registry of "which contraption is at which origin" so the sampling task can find its sampler */
    private static final ConcurrentHashMap<Location, SamplingContraption> SAMPLERS = new ConcurrentHashMap<>();
    private static final class SamplingContraption {
        final Contraption contraption;
        SamplingContraption(Contraption c) { this.contraption = c; }
    }
    static void register(Location origin, Contraption c) { SAMPLERS.put(origin, new SamplingContraption(c)); }
    static void unregister(Location origin) { SAMPLERS.remove(origin); }

    /* ---------------- Marking AC chunks ---------------- */

    private void markChunks(Location origin, Contraption c, RedstoneMode mode) {
        ResourceKey<Level> dim = ((CraftWorld) world).getHandle().dimension();
        int cx0 = origin.getBlockX() >> 4;
        int cz0 = origin.getBlockZ() >> 4;
        int cx1 = (origin.getBlockX() + c.sizeX() - 1) >> 4;
        int cz1 = (origin.getBlockZ() + c.sizeZ() - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++)
                ChunkRegistry.get().setMode(dim, cx, cz, mode);
    }

    /* ---------------- Tick samples ---------------- */

    private static final class TickSamples {
        private final ConcurrentHashMap<Long, int[]> byTick = new ConcurrentHashMap<>();

        synchronized void record(long tick, int value, boolean isVanilla) {
            int[] pair = byTick.computeIfAbsent(tick, t -> new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE});
            pair[isVanilla ? 0 : 1] = value;
        }

        synchronized List<long[]> mismatches() {
            List<long[]> out = new ArrayList<>();
            for (var e : byTick.entrySet()) {
                int[] pair = e.getValue();
                if (pair[0] == Integer.MIN_VALUE || pair[1] == Integer.MIN_VALUE) continue;
                if (pair[0] != pair[1]) out.add(new long[]{e.getKey(), pair[0], pair[1]});
            }
            out.sort((a, b) -> Long.compare(a[0], b[0]));
            return out;
        }

        synchronized int sampleCount() { return byTick.size(); }
    }

    /* ---------------- Finalization ---------------- */

    private RunReport.Case finish(Contraption c, TickSamples samples, Throwable err, long startNs) {
        long durMs = (System.nanoTime() - startNs) / 1_000_000;
        StringBuilder out = new StringBuilder();
        out.append("samples=").append(samples == null ? 0 : samples.sampleCount()).append('\n');
        if (err != null) {
            out.append("error: ").append(err);
            return new RunReport.Case(c.id(), durMs, "exception during run: " + err, out.toString());
        }
        var diff = samples.mismatches();
        if (diff.isEmpty()) {
            out.append("parity OK across ").append(samples.sampleCount()).append(" sample-pairs\n");
            return new RunReport.Case(c.id(), durMs, null, out.toString());
        }
        out.append("first 5 mismatches (tick, vanilla, ac):\n");
        for (int i = 0; i < Math.min(5, diff.size()); i++) {
            long[] r = diff.get(i);
            out.append("  t=").append(r[0]).append(" v=").append(r[1]).append(" ac=").append(r[2]).append('\n');
        }
        return new RunReport.Case(c.id(), durMs,
                "parity diverged at " + diff.size() + " ticks (out of " + samples.sampleCount() + ")",
                out.toString());
    }

    /* ---------------- Folia helper ---------------- */

    /** Run a region-bound action and return when complete. */
    private CompletableFuture<Void> runOn(World w, int chunkX, int chunkZ, Runnable r) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, w, chunkX, chunkZ, () -> {
            try { r.run(); done.complete(null); }
            catch (Throwable t) { done.completeExceptionally(t); }
        });
        return done;
    }
}
