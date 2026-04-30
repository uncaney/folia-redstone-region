package net.dedale.redstone.test;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.dedale.redstone.region.config.ChunkRegistry;
import net.dedale.redstone.region.config.RedstoneMode;
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
 * The critical Folia thread-safety test: drive two independent
 * 12×12 dust grids, marked AC, in two regions guaranteed to be different
 * (placed 4096 blocks apart so even Folia's region merging won't fold them).
 * If our per-thread WireHandler design were wrong, this is where you'd see
 * data races, mis-mapped state, or IllegalStateException from TickThread checks.
 */
public final class CrossRegionStressRunner {

    private static final int Y = 64;
    private static final int Z = 0;
    private static final int GRID = 12;
    private static final long DURATION_TICKS = 800;     // ~40 s of activity

    private final Plugin plugin;
    private final World world;

    public CrossRegionStressRunner(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    public CompletableFuture<RunReport.Case> run() {
        long startNs = System.nanoTime();
        int xA = 12000;   // chunk 750  → region (750>>4 = 46)
        int xB = 16000;   // chunk 1000 → region (1000>>4 = 62)  ≥ 16-region gap

        Location oA = new Location(world, xA, Y, Z);
        Location oB = new Location(world, xB, Y, Z);

        AtomicLong togglesA = new AtomicLong();
        AtomicLong togglesB = new AtomicLong();
        AtomicLong exceptionsA = new AtomicLong();
        AtomicLong exceptionsB = new AtomicLong();

        CompletableFuture<Void> doneA = driveRegion(oA, togglesA, exceptionsA);
        CompletableFuture<Void> doneB = driveRegion(oB, togglesB, exceptionsB);

        return CompletableFuture.allOf(doneA, doneB).handle((v, err) -> {
            long durMs = (System.nanoTime() - startNs) / 1_000_000;
            long totalToggles = togglesA.get() + togglesB.get();
            long totalExceptions = exceptionsA.get() + exceptionsB.get();
            String stdout = "regionA(toggles=" + togglesA.get() + ", exc=" + exceptionsA.get() + ") "
                    + "regionB(toggles=" + togglesB.get() + ", exc=" + exceptionsB.get() + ")";
            String fail = null;
            if (err != null)        fail = "outer: " + err;
            else if (totalExceptions > 0) fail = "thread-safety failures during cross-region stress: " + totalExceptions;
            else if (togglesA.get() < DURATION_TICKS / 2 || togglesB.get() < DURATION_TICKS / 2)
                fail = "one region under-ticked: " + stdout;
            return new RunReport.Case("cross-region-stress", durMs, fail, stdout);
        });
    }

    private CompletableFuture<Void> driveRegion(Location origin, AtomicLong toggles, AtomicLong exceptions) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int chunkX = origin.getBlockX() >> 4;
        int chunkZ = origin.getBlockZ() >> 4;

        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                ResourceKey<Level> dim = ((CraftWorld) world).getHandle().dimension();
                ChunkRegistry.get().setMode(dim, chunkX, chunkZ, RedstoneMode.ALTERNATE_CURRENT);
                buildGrid(origin);
            } catch (Throwable t) {
                exceptions.incrementAndGet();
                done.complete(null);
                return;
            }
            long startTick = world.getFullTime();
            ScheduledTask[] taskHolder = new ScheduledTask[1];
            taskHolder[0] = Bukkit.getRegionScheduler().runAtFixedRate(plugin, origin, task -> {
                long t = world.getFullTime() - startTick;
                if (t > DURATION_TICKS) {
                    task.cancel();
                    done.complete(null);
                    return;
                }
                try {
                    toggleLever(origin, (t & 1) == 0);
                    toggles.incrementAndGet();
                } catch (Throwable th) {
                    exceptions.incrementAndGet();
                }
            }, 1, 1);
        });
        return done;
    }

    private void buildGrid(Location origin) {
        World w = origin.getWorld();
        int bx = origin.getBlockX(), by = origin.getBlockY(), bz = origin.getBlockZ();
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
}
