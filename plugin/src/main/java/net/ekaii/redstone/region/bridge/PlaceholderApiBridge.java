package net.ekaii.redstone.region.bridge;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI integration. Soft-dep — only registered if PAPI is present.
 *
 * <p>Placeholders:
 * <ul>
 *   <li>{@code %redstone-region_mode%} — mode of the chunk under the player's feet</li>
 *   <li>{@code %redstone-region_ac_count%} — non-default AC chunk count in player's world</li>
 *   <li>{@code %redstone-region_eigencraft_count%} — same, eigencraft</li>
 *   <li>{@code %redstone-region_disabled_count%} — same, disabled</li>
 *   <li>{@code %redstone-region_total_count%} — total non-default in player's world</li>
 * </ul>
 */
public final class PlaceholderApiBridge extends PlaceholderExpansion {

    private final Plugin plugin;
    private final ChunkRegistry registry;

    public PlaceholderApiBridge(Plugin plugin, ChunkRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override public @NotNull String getIdentifier() { return "redstone-region"; }
    @Override public @NotNull String getAuthor()     { return "ekaii"; }
    @Override public @NotNull String getVersion()    { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist()                { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player p, @NotNull String params) {
        if (p == null) return "";
        return switch (params.toLowerCase()) {
            case "mode" -> {
                World w = p.getWorld();
                ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
                int cx = p.getLocation().getBlockX() >> 4;
                int cz = p.getLocation().getBlockZ() >> 4;
                yield registry.modeOfChunk(dim, cx, cz).slug();
            }
            case "ac_count"          -> Long.toString(countByMode(p, RedstoneMode.ALTERNATE_CURRENT));
            case "eigencraft_count"  -> Long.toString(countByMode(p, RedstoneMode.EIGENCRAFT));
            case "disabled_count"    -> Long.toString(countByMode(p, RedstoneMode.DISABLED));
            case "total_count"       -> Integer.toString(registry.trackedCount(((CraftWorld) p.getWorld()).getHandle().dimension()));
            default                  -> null;
        };
    }

    private long countByMode(Player p, RedstoneMode target) {
        ResourceKey<Level> dim = ((CraftWorld) p.getWorld()).getHandle().dimension();
        long[] keys = registry.snapshotKeys(dim);
        long n = 0;
        for (long k : keys) {
            int cx = net.ekaii.redstone.region.util.ChunkKey.unpackX(k);
            int cz = net.ekaii.redstone.region.util.ChunkKey.unpackZ(k);
            if (registry.modeOfChunk(dim, cx, cz) == target) n++;
        }
        return n;
    }
}
