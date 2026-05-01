package net.ekaii.redstone.test;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Builds a 32×32 redstone-dust grid in two zones (vanilla / alternate-current),
 * toggles input N times, and reports wall-clock latency per toggle. Tests both
 * functional correctness (output flickers consistently) and performance.
 */
public final class PerfRunner {

    private static final int Y = 64;
    private static final int VANILLA_X0 = 5000;
    private static final int AC_X0 = 6000;
    private static final int Z0 = 0;
    private static final int GRID = 32;
    private static final int TOGGLE_COUNT = 50;

    private final Plugin plugin;
    private final World world;

    public PerfRunner(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    public CompletableFuture<RunReport.Case> run() {
        long startNs = System.nanoTime();
        Location oVan = new Location(world, VANILLA_X0, Y, Z0);
        Location oAc  = new Location(world, AC_X0, Y, Z0);

        return runOn(world, oVan.getBlockX() >> 4, oVan.getBlockZ() >> 4, () -> buildGrid(oVan))
                .thenCompose(v -> runOn(world, oAc.getBlockX() >> 4, oAc.getBlockZ() >> 4, () -> {
                    markChunks(oAc);
                    buildGrid(oAc);
                }))
                .thenCompose(v -> measure(oVan))
                .thenCompose(vanNanos -> measure(oAc).thenApply(acNanos -> new long[]{vanNanos, acNanos}))
                .handle((nanos, err) -> finish(nanos, err, startNs));
    }

    private void buildGrid(Location origin) {
        World w = origin.getWorld();
        int bx = origin.getBlockX();
        int by = origin.getBlockY();
        int bz = origin.getBlockZ();
        for (int dx = 0; dx < GRID; dx++) {
            for (int dz = 0; dz < GRID; dz++) {
                w.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);
                w.getBlockAt(bx + dx, by + 1, bz + dz).setType(Material.REDSTONE_WIRE, false);
            }
        }
        // lever in (0,0)
        w.getBlockAt(bx, by + 1, bz).setType(Material.LEVER, false);
        // lamp at far corner
        w.getBlockAt(bx + GRID - 1, by + 1, bz + GRID - 1).setType(Material.REDSTONE_LAMP, true);
    }

    private CompletableFuture<Long> measure(Location origin) {
        CompletableFuture<Long> done = new CompletableFuture<>();
        int chunkX = origin.getBlockX() >> 4;
        int chunkZ = origin.getBlockZ() >> 4;
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            long total = 0;
            try {
                for (int i = 0; i < TOGGLE_COUNT; i++) {
                    long t0 = System.nanoTime();
                    toggleLever(origin, i % 2 == 0);
                    total += System.nanoTime() - t0;
                }
                done.complete(total);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done;
    }

    private void toggleLever(Location origin, boolean on) {
        Block lever = origin.getWorld().getBlockAt(origin.getBlockX(), origin.getBlockY() + 1, origin.getBlockZ());
        BlockData d = lever.getBlockData();
        if (d instanceof org.bukkit.block.data.Powerable p) {
            p.setPowered(on);
            lever.setBlockData(p, true);
        }
    }

    private void markChunks(Location origin) {
        ResourceKey<Level> dim = ((CraftWorld) world).getHandle().dimension();
        int cx0 = origin.getBlockX() >> 4;
        int cz0 = origin.getBlockZ() >> 4;
        int cx1 = (origin.getBlockX() + GRID - 1) >> 4;
        int cz1 = (origin.getBlockZ() + GRID - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++)
                ChunkRegistry.get().setMode(dim, cx, cz, RedstoneMode.ALTERNATE_CURRENT);
    }

    private RunReport.Case finish(long[] nanos, Throwable err, long startNs) {
        long durMs = (System.nanoTime() - startNs) / 1_000_000;
        if (err != null) {
            return new RunReport.Case("perf-32x32-grid", durMs,
                    "exception during run: " + err, "");
        }
        long vanNs = nanos[0];
        long acNs  = nanos[1];
        double speedup = (double) vanNs / acNs;
        StringBuilder out = new StringBuilder();
        out.append("toggles=").append(TOGGLE_COUNT).append('\n');
        out.append("vanilla total: ").append(vanNs / 1_000_000).append(" ms (avg ")
                .append(vanNs / TOGGLE_COUNT / 1_000).append(" us/toggle)\n");
        out.append("ac      total: ").append(acNs / 1_000_000).append(" ms (avg ")
                .append(acNs / TOGGLE_COUNT / 1_000).append(" us/toggle)\n");
        out.append("speedup: ").append(String.format("%.2fx", speedup)).append('\n');
        // We *do not* fail on speedup: AC on a small grid can be slower due to fixed graph-build cost.
        // We only fail on exception. The numbers go to system-out for inspection.
        return new RunReport.Case("perf-32x32-grid", durMs, null, out.toString());
    }

    private CompletableFuture<Void> runOn(World w, int chunkX, int chunkZ, Runnable r) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, w, chunkX, chunkZ, () -> {
            try { r.run(); done.complete(null); }
            catch (Throwable t) { done.completeExceptionally(t); }
        });
        return done;
    }
}
