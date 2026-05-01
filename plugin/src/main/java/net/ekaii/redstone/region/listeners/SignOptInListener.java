package net.ekaii.redstone.region.listeners;

import net.ekaii.redstone.region.audit.AuditLog;
import net.ekaii.redstone.region.bridge.DiscordWebhook;
import net.ekaii.redstone.region.config.ChunkPdcCodec;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.PluginConfig;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.plugin.Plugin;

/**
 * Sign-based opt-in: place a sign with line 0 = {@code [ac]}, {@code [vanilla]},
 * {@code [eigencraft]}, or {@code [disabled]} (case-insensitive). Optional line 1 = radius
 * in chunks (capped by config {@code sign.max-radius}).
 */
public final class SignOptInListener implements Listener {

    private final Plugin plugin;
    private final ChunkRegistry registry;
    private final AuditLog audit;
    private final DiscordWebhook discord;
    private final PluginConfig cfg;

    public SignOptInListener(Plugin plugin, ChunkRegistry registry, AuditLog audit,
                             DiscordWebhook discord, PluginConfig cfg) {
        this.plugin = plugin;
        this.registry = registry;
        this.audit = audit;
        this.discord = discord;
        this.cfg = cfg;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPermission(cfg.signPermission)) return;

        Component first = e.line(0);
        if (first == null) return;
        String line0 = stripFormat(first);
        if (line0 == null || line0.length() < 3 || !line0.startsWith("[") || !line0.endsWith("]")) return;
        String tag = line0.substring(1, line0.length() - 1).toLowerCase();
        RedstoneMode mode = RedstoneMode.parse(tag);
        if (mode == null) return;

        // Optional radius on line 1
        int radius = 0;
        Component lineOne = e.line(1);
        if (lineOne != null) {
            String s = stripFormat(lineOne).trim();
            if (!s.isEmpty()) {
                try { radius = Math.max(0, Math.min(cfg.signMaxRadius, Integer.parseInt(s))); }
                catch (NumberFormatException ignored) { /* leave radius=0 */ }
            }
        }

        Block sign = e.getBlock();
        World w = sign.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        Chunk c = sign.getChunk();
        int cx0 = c.getX(), cz0 = c.getZ();

        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = cx0 + dx, cz = cz0 + dz;
                RedstoneMode prev = registry.modeOfChunk(dim, cx, cz);
                if (prev == mode) continue;
                registry.setMode(dim, cx, cz, mode);
                final int fcx = cx, fcz = cz;
                Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                    ChunkPdcCodec.write(w.getChunkAt(fcx, fcz), mode);
                });
                AuditLog.Event ev = audit.makeEvent(AuditLog.Source.SIGN, p,
                        dim.identifier().toString(), cx, cz, prev, mode, "sign at " + sign.getLocation());
                audit.record(ev);
                if (discord != null) discord.send(ev);
                n++;
            }
        }
        p.sendMessage(Component.text("[redstone-region] sign → " + n + " chunks set to " + mode.slug()
                + " (radius=" + radius + ", cap=" + cfg.signMaxRadius + ")", NamedTextColor.GREEN));

        // Repaint the sign so it shows the applied state
        e.line(0, Component.text("[" + mode.slug() + "]", NamedTextColor.AQUA));
        if (radius > 0) {
            e.line(1, Component.text("r=" + radius, NamedTextColor.GRAY));
        }
        e.line(2, Component.text(n + " chunks", NamedTextColor.DARK_GRAY));
        e.line(3, Component.text("by " + p.getName(), NamedTextColor.DARK_GRAY));
    }

    private static String stripFormat(Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }
}
