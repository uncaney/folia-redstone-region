package net.ekaii.redstone.region.auto;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.ekaii.redstone.region.audit.AuditLog;
import net.ekaii.redstone.region.bridge.DiscordWebhook;
import net.ekaii.redstone.region.config.ChunkPdcCodec;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.PluginConfig;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.ekaii.redstone.region.timing.ChunkTimingTable;
import net.ekaii.redstone.region.util.ChunkKey;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.Plugin;

/**
 * Periodically inspects the timing table; chunks whose average ms-per-update
 * exceeds {@code auto-ac.ms-per-update-threshold} (and aren't already AC) get
 * auto-flipped. Optionally reverts AC chunks that quiet down.
 *
 * <p>Runs on the global region scheduler; the actual PDC write per chunk is
 * dispatched to that chunk's region scheduler.
 */
public final class AutoAcScanner {

    private final Plugin plugin;
    private final ChunkRegistry registry;
    private final ChunkTimingTable timing;
    private final AuditLog audit;
    private final DiscordWebhook discord;
    private final PluginConfig cfg;
    private ScheduledTask task;

    public AutoAcScanner(Plugin plugin, ChunkRegistry registry, ChunkTimingTable timing,
                         AuditLog audit, DiscordWebhook discord, PluginConfig cfg) {
        this.plugin = plugin;
        this.registry = registry;
        this.timing = timing;
        this.audit = audit;
        this.discord = discord;
        this.cfg = cfg;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                $ -> scan(), cfg.autoAcScanIntervalTicks, cfg.autoAcScanIntervalTicks);
        plugin.getLogger().info("auto-ac scanner enabled (every " + cfg.autoAcScanIntervalTicks + " ticks)");
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void scan() {
        var hotList = timing.top(64);  // top 64 hottest across all dims
        for (var hot : hotList) {
            if (hot.cell().count() < cfg.autoAcMinSamples) continue;
            double avgMs = hot.cell().avgMs();
            // Find the World/dimension key from the dim string.
            World world = matchWorld(hot.dim());
            if (world == null) continue;
            ResourceKey<Level> dim = ((CraftWorld) world).getHandle().dimension();

            RedstoneMode current = registry.modeOfChunk(dim, hot.cx(), hot.cz());

            if (current == RedstoneMode.VANILLA && avgMs > cfg.autoAcMsThreshold) {
                flip(world, hot.cx(), hot.cz(), dim, current, RedstoneMode.ALTERNATE_CURRENT,
                        String.format("auto-flip: %.2f ms/update > %.2f ms threshold", avgMs, cfg.autoAcMsThreshold));
            } else if (cfg.autoAcAutoRevert && current == RedstoneMode.ALTERNATE_CURRENT && avgMs < cfg.autoAcMsThreshold / 2) {
                flip(world, hot.cx(), hot.cz(), dim, current, RedstoneMode.VANILLA,
                        String.format("auto-revert: %.2f ms/update < %.2f ms half-threshold", avgMs, cfg.autoAcMsThreshold / 2));
            }
        }
    }

    private void flip(World world, int cx, int cz, ResourceKey<Level> dim,
                      RedstoneMode prev, RedstoneMode next, String reason) {
        registry.setMode(dim, cx, cz, next);
        Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
            ChunkPdcCodec.write(world.getChunkAt(cx, cz), next);
        });
        AuditLog.Event ev = audit.makeEvent(AuditLog.Source.AUTO_AC, null,
                dim.identifier().toString(), cx, cz, prev, next, reason);
        audit.record(ev);
        if (discord != null) discord.send(ev);
        plugin.getLogger().info("[auto-ac] " + dim.identifier() + " (" + cx + "," + cz + ") "
                + prev.slug() + " → " + next.slug() + " (" + reason + ")");
    }

    private World matchWorld(String dimString) {
        for (World w : Bukkit.getWorlds()) {
            ResourceKey<Level> k = ((CraftWorld) w).getHandle().dimension();
            if (k.identifier().toString().equals(dimString)) return w;
        }
        return null;
    }
}
