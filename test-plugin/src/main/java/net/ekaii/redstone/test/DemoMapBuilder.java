package net.ekaii.redstone.test;

import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.ekaii.redstone.test.contraptions.Contraptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Builds an in-world demo: 3 side-by-side 16x16 dust grids (vanilla / AC /
 * vanilla) + 1 32-block dust line + 1 repeater clock + 1 dust zigzag,
 * centred near the player. AC zones get auto-marked via ChunkRegistry.
 *
 * Triggered by {@code /demo-map} console command.
 */
public final class DemoMapBuilder {

    private final Plugin plugin;
    public DemoMapBuilder(Plugin plugin) { this.plugin = plugin; }

    public void buildAround(Player p) {
        World w = p.getWorld();
        // Anchor 32 blocks north of the player so they spawn looking at it.
        int ox = p.getLocation().getBlockX() + 8;
        int oy = Math.max(p.getLocation().getBlockY(), -55);
        int oz = p.getLocation().getBlockZ() - 32;

        // Floor concrete platform 96x96 for visibility
        int chunkX0 = (ox - 16) >> 4;
        int chunkX1 = (ox + 96) >> 4;
        int chunkZ0 = (oz - 16) >> 4;
        int chunkZ1 = (oz + 96) >> 4;
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();

        for (int cx = chunkX0; cx <= chunkX1; cx++) {
            for (int cz = chunkZ0; cz <= chunkZ1; cz++) {
                final int fcx = cx, fcz = cz, fox = ox, foz = oz, foy = oy;
                Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                    paintFloor(w, fcx, fcz, foy);
                });
            }
        }

        // === Lane 0 (z+0..15)   = vanilla baseline 16x16 grid ===
        Bukkit.getRegionScheduler().execute(plugin, w, ox >> 4, oz >> 4, () -> {
            buildDustGrid16(w, ox, oy + 1, oz);
            placeSign(w, ox - 1, oy + 2, oz, "VANILLA",   "16x16 grid", "leftmost lever", "");
        });

        // === Lane 1 (z+20..35)  = AC 16x16 grid ===
        int acZ = oz + 20;
        Bukkit.getRegionScheduler().execute(plugin, w, ox >> 4, acZ >> 4, () -> {
            // mark all chunks the AC grid touches
            int cx0 = ox >> 4;
            int cx1 = (ox + 15) >> 4;
            int cz0 = acZ >> 4;
            int cz1 = (acZ + 15) >> 4;
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++)
                    ChunkRegistry.get().setMode(dim, cx, cz, RedstoneMode.ALTERNATE_CURRENT);
            buildDustGrid16(w, ox, oy + 1, acZ);
            placeSign(w, ox - 1, oy + 2, acZ, "ALTERNATE-", "CURRENT", "16x16 grid", "lever leftmost");
        });

        // === Lane 2 (z+40..55)  = AC 32-block dust line ===
        int lineZ = oz + 42;
        Bukkit.getRegionScheduler().execute(plugin, w, ox >> 4, lineZ >> 4, () -> {
            int cx0 = ox >> 4;
            int cx1 = (ox + 31) >> 4;
            for (int cx = cx0; cx <= cx1; cx++)
                ChunkRegistry.get().setMode(dim, cx, lineZ >> 4, RedstoneMode.ALTERNATE_CURRENT);
            new Contraptions.DustLine30().build(new Location(w, ox, oy, lineZ));
            placeSign(w, ox - 1, oy + 2, lineZ, "AC line", "30 dust", "lever→lamp", "");
        });

        // === Lane 3 (z+60..67)  = AC repeater clock ===
        int clockZ = oz + 60;
        Bukkit.getRegionScheduler().execute(plugin, w, ox >> 4, clockZ >> 4, () -> {
            ChunkRegistry.get().setMode(dim, ox >> 4, clockZ >> 4, RedstoneMode.ALTERNATE_CURRENT);
            new Contraptions.RepeaterClock4().build(new Location(w, ox, oy, clockZ));
            placeSign(w, ox - 1, oy + 2, clockZ, "AC clock", "4-tick", "self-running", "");
        });

        // === Lane 4 (z+72..80)  = AC zigzag ===
        int zigZ = oz + 72;
        Bukkit.getRegionScheduler().execute(plugin, w, ox >> 4, zigZ >> 4, () -> {
            int cx0 = ox >> 4;
            int cx1 = (ox + 7) >> 4;
            int cz0 = zigZ >> 4;
            int cz1 = (zigZ + 7) >> 4;
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++)
                    ChunkRegistry.get().setMode(dim, cx, cz, RedstoneMode.ALTERNATE_CURRENT);
            new Contraptions.DustZigzag().build(new Location(w, ox, oy, zigZ));
            placeSign(w, ox - 1, oy + 2, zigZ, "AC zigzag", "8x8", "flow turns", "");
        });

        // teleport the player just south of the platform looking north
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Location tp = new Location(w, ox + 8, oy + 3, oz - 4);
            tp.setYaw(180); tp.setPitch(20);
            p.teleportAsync(tp);
            p.sendMessage("§a[demo-map] built. fly along the row of signs and toggle the leftmost lever in each panel.");
            p.sendMessage("§7chunks marked AC: rows 1-4 (vanilla baseline = row 0).");
        });
    }

    private void paintFloor(World w, int cx, int cz, int y) {
        int bx = cx << 4, bz = cz << 4;
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++)
                w.getBlockAt(bx + dx, y, bz + dz).setType(Material.SMOOTH_STONE, false);
    }

    private void buildDustGrid16(World w, int bx, int by, int bz) {
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++) {
                w.getBlockAt(bx + dx, by - 1, bz + dz).setType(Material.STONE, false);
                w.getBlockAt(bx + dx, by, bz + dz).setType(Material.REDSTONE_WIRE, false);
            }
        w.getBlockAt(bx, by, bz).setType(Material.LEVER, false);
        w.getBlockAt(bx + 15, by, bz + 15).setType(Material.REDSTONE_LAMP, true);
    }

    private void placeSign(World w, int x, int y, int z, String l1, String l2, String l3, String l4) {
        var b = w.getBlockAt(x, y, z);
        b.setType(Material.OAK_SIGN, false);
        if (b.getState() instanceof org.bukkit.block.Sign s) {
            s.line(0, net.kyori.adventure.text.Component.text(l1));
            s.line(1, net.kyori.adventure.text.Component.text(l2));
            s.line(2, net.kyori.adventure.text.Component.text(l3));
            s.line(3, net.kyori.adventure.text.Component.text(l4));
            s.update(true, false);
        }
    }
}
