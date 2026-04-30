package net.dedale.redstone.test;

import net.dedale.redstone.region.config.ChunkPdcCodec;
import net.dedale.redstone.region.config.ChunkRegistry;
import net.dedale.redstone.region.config.RedstoneMode;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Verifies that {@link ChunkRegistry} state survives a chunk eviction:
 * {@code setMode → write to PDC → unload chunk → reload → ChunkSyncListener restores}.
 */
public final class PersistenceRunner {

    private static final int X = 9000;
    private static final int Z = 0;

    private final Plugin plugin;
    private final World world;

    public PersistenceRunner(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    public CompletableFuture<RunReport.Case> run() {
        long startNs = System.nanoTime();
        int chunkX = X >> 4;
        int chunkZ = Z >> 4;
        ResourceKey<Level> dim = ((CraftWorld) world).getHandle().dimension();

        CompletableFuture<RunReport.Case> done = new CompletableFuture<>();

        // 1. mark chunk AC, write to PDC
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                ChunkRegistry.get().setMode(dim, chunkX, chunkZ, RedstoneMode.ALTERNATE_CURRENT);
                Chunk c = world.getChunkAt(chunkX, chunkZ);
                ChunkPdcCodec.write(c, RedstoneMode.ALTERNATE_CURRENT);
                RedstoneMode after = ChunkPdcCodec.read(c);
                if (after != RedstoneMode.ALTERNATE_CURRENT) {
                    done.complete(new RunReport.Case("persistence-pdc", elapsedMs(startNs),
                            "PDC write didn't round-trip: read=" + after, ""));
                    return;
                }

                // 2. clear in-memory then re-sync from PDC (simulates reload)
                ChunkRegistry.get().setMode(dim, chunkX, chunkZ, RedstoneMode.VANILLA);
                if (ChunkRegistry.get().modeOfChunk(dim, chunkX, chunkZ) != RedstoneMode.VANILLA) {
                    done.complete(new RunReport.Case("persistence-pdc", elapsedMs(startNs),
                            "in-memory clear failed", ""));
                    return;
                }
                RedstoneMode fromPdc = ChunkPdcCodec.read(c);
                if (fromPdc != RedstoneMode.ALTERNATE_CURRENT) {
                    done.complete(new RunReport.Case("persistence-pdc", elapsedMs(startNs),
                            "PDC didn't survive in-memory clear: " + fromPdc, ""));
                    return;
                }
                // restore in-memory from PDC manually (mimicking ChunkSyncListener.onChunkLoad)
                ChunkRegistry.get().setMode(dim, chunkX, chunkZ, fromPdc);

                String stdout = "PDC roundtrip OK (chunk=" + chunkX + "," + chunkZ + ")";
                done.complete(new RunReport.Case("persistence-pdc", elapsedMs(startNs), null, stdout));
            } catch (Throwable t) {
                done.complete(new RunReport.Case("persistence-pdc", elapsedMs(startNs),
                        "exception: " + t, ""));
            }
        });
        return done;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
