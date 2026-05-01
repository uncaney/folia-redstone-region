package net.ekaii.redstone.test;

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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sweeps a series of square dust-grid sizes, measuring vanilla and AC
 * toggle latency at each. Writes results as CSV to {@code perf-sweep.csv}
 * in the server CWD.
 */
public final class PerfSweep {

    /** sizes are "N" → N×N dust grid */
    private static final int[] SIZES = {4, 8, 16, 24, 32, 48, 64};
    private static final int TOGGLES_PER_RUN = 50;
    private static final int WARMUP_TOGGLES   = 10;
    private static final int Y = 64;
    private static final int VAN_X = 7000;
    private static final int AC_X  = 9000;

    private final Plugin plugin;
    private final World world;
    private final Player who;

    public PerfSweep(Plugin plugin, World world, Player who) {
        this.plugin = plugin;
        this.world = world;
        this.who = who;
    }

    public void start() {
        who.sendMessage("§e[perf-sweep] running " + SIZES.length + " sizes, ~" + (SIZES.length * 2) + " seconds total");
        ResourceKey<Level> dim = ((CraftWorld) world).getHandle().dimension();
        // Pre-mark all AC chunks the AC grid will touch (use the largest size as upper bound)
        int maxSize = SIZES[SIZES.length - 1];
        int cx0 = AC_X >> 4, cx1 = (AC_X + maxSize - 1) >> 4;
        int cz0 = 0 >> 4, cz1 = (maxSize - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++)
                ChunkRegistry.get().setMode(dim, cx, cz, RedstoneMode.ALTERNATE_CURRENT);

        // Run sequentially: build size i in both zones, measure both, record, repeat
        runNext(0, new ArrayList<>());
    }

    private void runNext(int index, List<long[]> rows) {
        if (index >= SIZES.length) {
            writeCsv(rows);
            who.sendMessage("§a[perf-sweep] done. CSV at " + Path.of("perf-sweep.csv").toAbsolutePath());
            return;
        }
        int size = SIZES[index];
        Location vanOrigin = new Location(world, VAN_X, Y, 0);
        Location acOrigin  = new Location(world, AC_X,  Y, 0);
        clearAndBuild(vanOrigin, size)
                .thenCompose(v -> clearAndBuild(acOrigin, size))
                .thenCompose(v -> measureWithWarmup(vanOrigin))
                .thenCompose(vanNs -> measureWithWarmup(acOrigin)
                        .thenAccept(acNs -> {
                            rows.add(new long[]{size, vanNs, acNs});
                            who.sendMessage(String.format("§7[perf-sweep] size=%dx%d vanilla=%d ms ac=%d ms speedup=%.2fx",
                                    size, size,
                                    vanNs / TOGGLES_PER_RUN / 1_000,    // µs per toggle
                                    acNs  / TOGGLES_PER_RUN / 1_000,
                                    (double) vanNs / acNs));
                            runNext(index + 1, rows);
                        }));
    }

    private CompletableFuture<Void> clearAndBuild(Location origin, int size) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int chunkX = origin.getBlockX() >> 4;
        int chunkZ = origin.getBlockZ() >> 4;
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                int bx = origin.getBlockX(), by = origin.getBlockY(), bz = origin.getBlockZ();
                // clear an area larger than max size
                int max = SIZES[SIZES.length - 1];
                for (int dx = 0; dx < max; dx++)
                    for (int dz = 0; dz < max; dz++)
                        world.getBlockAt(bx + dx, by + 1, bz + dz).setType(Material.AIR, false);
                // build size×size dust grid
                for (int dx = 0; dx < size; dx++)
                    for (int dz = 0; dz < size; dz++) {
                        world.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);
                        world.getBlockAt(bx + dx, by + 1, bz + dz).setType(Material.REDSTONE_WIRE, false);
                    }
                world.getBlockAt(bx, by + 1, bz).setType(Material.LEVER, false);
                world.getBlockAt(bx + size - 1, by + 1, bz + size - 1).setType(Material.REDSTONE_LAMP, true);
                done.complete(null);
            } catch (Throwable t) { done.completeExceptionally(t); }
        });
        return done;
    }

    private CompletableFuture<Long> measureWithWarmup(Location origin) {
        CompletableFuture<Long> done = new CompletableFuture<>();
        int chunkX = origin.getBlockX() >> 4;
        int chunkZ = origin.getBlockZ() >> 4;
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                Block lever = world.getBlockAt(origin.getBlockX(), origin.getBlockY() + 1, origin.getBlockZ());
                // warmup
                for (int i = 0; i < WARMUP_TOGGLES; i++) toggleLever(lever, i % 2 == 0);
                long total = 0;
                for (int i = 0; i < TOGGLES_PER_RUN; i++) {
                    long t0 = System.nanoTime();
                    toggleLever(lever, i % 2 == 0);
                    total += System.nanoTime() - t0;
                }
                done.complete(total);
            } catch (Throwable t) { done.completeExceptionally(t); }
        });
        return done;
    }

    private void toggleLever(Block lever, boolean on) {
        BlockData d = lever.getBlockData();
        if (d instanceof org.bukkit.block.data.Powerable p) {
            p.setPowered(on);
            lever.setBlockData(p, true);
        }
    }

    private void writeCsv(List<long[]> rows) {
        try (Writer w = Files.newBufferedWriter(Path.of("perf-sweep.csv"))) {
            w.write("size,wires,vanilla_total_ns,ac_total_ns,vanilla_us_per_toggle,ac_us_per_toggle,speedup\n");
            for (long[] r : rows) {
                long size = r[0];
                long wires = size * size;
                long vanNs = r[1], acNs = r[2];
                w.write(size + "," + wires + "," + vanNs + "," + acNs + ","
                        + (vanNs / TOGGLES_PER_RUN / 1_000) + ","
                        + (acNs  / TOGGLES_PER_RUN / 1_000) + ","
                        + String.format("%.3f", (double) vanNs / acNs) + "\n");
            }
        } catch (IOException e) {
            who.sendMessage("§c[perf-sweep] failed to write CSV: " + e);
        }
    }
}
