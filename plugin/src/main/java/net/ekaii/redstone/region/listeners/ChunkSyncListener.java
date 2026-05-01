package net.ekaii.redstone.region.listeners;

import net.ekaii.redstone.region.config.ChunkPdcCodec;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Keeps the in-memory {@link ChunkRegistry} in sync with each chunk's
 * {@code PersistentDataContainer}. On Folia these handlers run on the region
 * thread that owns the chunk, which is the only valid context for PDC
 * read/write — same thread as the redstone evaluator hot path.
 */
public final class ChunkSyncListener implements Listener {

    private final ChunkRegistry registry;

    public ChunkSyncListener(ChunkRegistry registry) {
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk c = e.getChunk();
        RedstoneMode mode = ChunkPdcCodec.read(c);
        if (mode != RedstoneMode.VANILLA) {
            registry.setMode(
                    ((CraftWorld) c.getWorld()).getHandle().dimension(),
                    c.getX(), c.getZ(), mode);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk c = e.getChunk();
        RedstoneMode in = registry.modeOfChunk(
                ((CraftWorld) c.getWorld()).getHandle().dimension(),
                c.getX(), c.getZ());
        // PDC was already read at load; only write if the in-memory state diverges.
        RedstoneMode disk = ChunkPdcCodec.read(c);
        if (in != disk) {
            ChunkPdcCodec.write(c, in);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) {
        registry.evictLevel(((CraftWorld) e.getWorld()).getHandle().dimension());
    }
}
