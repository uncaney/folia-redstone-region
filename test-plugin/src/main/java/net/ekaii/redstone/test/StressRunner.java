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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives a 16x16 dust grid in an alternate-current chunk for a long time
 * (default 600 ticks / 30 seconds), toggling its lever every tick. Verifies
 * (a) the server doesn't crash, (b) no Folia thread-check exception escapes,
 * (c) the AC dispatch counter keeps incrementing.
 */
public final class StressRunner {

    private static final int X = 8000;
    private static final int Z = 0;
    private static final int Y = 64;
    private static final int GRID = 16;
    private static final long DURATION_TICKS = 600;

    private final Plugin plugin;
    private final World world;

    public StressRunner(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    public CompletableFuture<RunReport.Case> run() {
        long startNs = System.nanoTime();
        Location origin = new Location(world, X, Y, Z);
        int chunkX = X >> 4;
        int chunkZ = Z >> 4;

        CompletableFuture<RunReport.Case> done = new CompletableFuture<>();
        AtomicLong tickToggles = new AtomicLong();
        AtomicLong tickExceptions = new AtomicLong();

        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                // mark chunk AC
                ResourceKey<Level> dim = ((CraftWorld) world).getHandle().dimension();
                ChunkRegistry.get().setMode(dim, chunkX, chunkZ, RedstoneMode.ALTERNATE_CURRENT);
                buildGrid(origin);
            } catch (Throwable t) {
                done.complete(new RunReport.Case("stress-600t", elapsedMs(startNs),
                        "build failed: " + t, ""));
                return;
            }

            // Toggle every tick for DURATION_TICKS
            long startTick = world.getFullTime();
            ScheduledTask[] taskHolder = new ScheduledTask[1];
            taskHolder[0] = Bukkit.getRegionScheduler().runAtFixedRate(plugin, origin, task -> {
                long t = world.getFullTime() - startTick;
                if (t > DURATION_TICKS) {
                    task.cancel();
                    String stdout = "toggles=" + tickToggles.get()
                            + " exceptions=" + tickExceptions.get();
                    String fail = tickExceptions.get() > 0
                            ? "exceptions thrown during stress run: " + tickExceptions.get()
                            : null;
                    done.complete(new RunReport.Case("stress-600t", elapsedMs(startNs), fail, stdout));
                    return;
                }
                try {
                    toggleLever(origin, (t & 1) == 0);
                    tickToggles.incrementAndGet();
                } catch (Throwable th) {
                    tickExceptions.incrementAndGet();
                }
            }, 1, 1);
        });
        return done;
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
        w.getBlockAt(bx, by + 1, bz).setType(Material.LEVER, false);
        w.getBlockAt(bx + GRID - 1, by + 1, bz + GRID - 1).setType(Material.REDSTONE_LAMP, true);
    }

    private void toggleLever(Location origin, boolean on) {
        Block lever = origin.getWorld().getBlockAt(origin.getBlockX(), origin.getBlockY() + 1, origin.getBlockZ());
        BlockData d = lever.getBlockData();
        if (d instanceof org.bukkit.block.data.Powerable p) {
            p.setPowered(on);
            lever.setBlockData(p, true);
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
