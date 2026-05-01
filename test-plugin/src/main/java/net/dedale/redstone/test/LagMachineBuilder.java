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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Builds a deliberate lag machine in one chunk neighborhood.
 *  • 64×64 redstone-dust mat (4096 wires)
 *  • 8 stacked 4-tick repeater clocks driving torch towers
 *  • a 16-piston firing array
 *  • a 32×32 grid of redstone-torch ladders inverting each other
 *
 * One copy in vanilla zone, one in AC zone, ~200 blocks apart so a player
 * can fly between them and watch a TPS bar tank or hold steady.
 */
public final class LagMachineBuilder {

    private final Plugin plugin;
    public LagMachineBuilder(Plugin plugin) { this.plugin = plugin; }

    public void buildAround(Player p) {
        World w = p.getWorld();
        int oy = Math.max(p.getLocation().getBlockY(), -55);
        int baseX = p.getLocation().getBlockX();
        int baseZ = p.getLocation().getBlockZ();

        Location vanOrigin = new Location(w, baseX + 32,  oy, baseZ + 32);
        Location acOrigin  = new Location(w, baseX + 32,  oy, baseZ + 200);

        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();

        // Pre-paint floors for both zones
        paintFloor(w, vanOrigin, 96, 96, oy);
        paintFloor(w, acOrigin,  96, 96, oy);

        // Build vanilla copy
        Bukkit.getRegionScheduler().execute(plugin, w,
                vanOrigin.getBlockX() >> 4, vanOrigin.getBlockZ() >> 4, () -> {
            buildAll(w, vanOrigin);
            placeSign(w, vanOrigin.getBlockX() - 1, oy + 2, vanOrigin.getBlockZ(),
                    "VANILLA",  "lag machine", "expect TPS", "to drop");
        });

        // Mark AC chunks then build AC copy
        Bukkit.getRegionScheduler().execute(plugin, w,
                acOrigin.getBlockX() >> 4, acOrigin.getBlockZ() >> 4, () -> {
            int cx0 = acOrigin.getBlockX() >> 4;
            int cx1 = (acOrigin.getBlockX() + 96) >> 4;
            int cz0 = acOrigin.getBlockZ() >> 4;
            int cz1 = (acOrigin.getBlockZ() + 96) >> 4;
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++)
                    ChunkRegistry.get().setMode(dim, cx, cz, RedstoneMode.ALTERNATE_CURRENT);
            buildAll(w, acOrigin);
            placeSign(w, acOrigin.getBlockX() - 1, oy + 2, acOrigin.getBlockZ(),
                    "ALTERNATE-", "CURRENT", "same machine", "TPS holds");
        });

        // TP the player to hover above the gap between the two machines
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, $ -> {
            Location tp = new Location(w, baseX + 70, oy + 30, baseZ + 116);
            tp.setYaw(180); tp.setPitch(45);
            p.teleportAsync(tp);
            p.sendMessage("§a[lag-machine] built. flip the lever in front of each machine.");
            p.sendMessage("§7vanilla zone: z=" + (baseZ + 32) + "  AC zone: z=" + (baseZ + 200));
            p.sendMessage("§7open /tpsbar to watch the difference live.");
        }, 60);
    }

    /* --- paint a flat smooth-stone floor of (sx*sz) cells around origin --- */
    private void paintFloor(World w, Location origin, int sx, int sz, int y) {
        int cx0 = origin.getBlockX() >> 4;
        int cx1 = (origin.getBlockX() + sx) >> 4;
        int cz0 = origin.getBlockZ() >> 4;
        int cz1 = (origin.getBlockZ() + sz) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                final int fcx = cx, fcz = cz, fy = y;
                Bukkit.getRegionScheduler().execute(plugin, w, cx, cz, () -> {
                    int bx = fcx << 4, bz = fcz << 4;
                    for (int dx = 0; dx < 16; dx++)
                        for (int dz = 0; dz < 16; dz++)
                            w.getBlockAt(bx + dx, fy, bz + dz).setType(Material.SMOOTH_STONE, false);
                });
            }
        }
    }

    private void buildAll(World w, Location origin) {
        int bx = origin.getBlockX(), by = origin.getBlockY(), bz = origin.getBlockZ();

        // 1. 64×64 dust mat at y+1 (4096 wires)
        for (int dx = 0; dx < 64; dx++) {
            for (int dz = 0; dz < 64; dz++) {
                w.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);
                w.getBlockAt(bx + dx, by + 1, bz + dz).setType(Material.REDSTONE_WIRE, false);
            }
        }
        // master lever in (-2, 0)
        Block masterLever = w.getBlockAt(bx - 2, by + 1, bz);
        w.getBlockAt(bx - 2, by, bz).setType(Material.STONE, false);
        masterLever.setType(Material.LEVER, false);
        // bridge lever to mat
        w.getBlockAt(bx - 1, by, bz).setType(Material.STONE, false);
        w.getBlockAt(bx - 1, by + 1, bz).setType(Material.REDSTONE_WIRE, false);

        // 2. 8 stacked 4-tick repeater clocks at (66, *, 0..40)
        for (int i = 0; i < 8; i++) {
            buildRepeaterClockAt(w, bx + 66, by, bz + i * 5);
        }

        // 3. 16-piston firing array at (0..30, +2, 70)
        for (int i = 0; i < 16; i++) {
            int x = bx + i * 2;
            int z = bz + 70;
            w.getBlockAt(x, by, z).setType(Material.STONE, false);
            w.getBlockAt(x, by + 1, z).setType(Material.REDSTONE_BLOCK, false);
            w.getBlockAt(x + 1, by, z).setType(Material.STONE, false);
            Block pistonBlock = w.getBlockAt(x + 1, by + 1, z);
            pistonBlock.setType(Material.PISTON, false);
            if (pistonBlock.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                dir.setFacing(BlockFace.EAST);
                pistonBlock.setBlockData(dir, false);
            }
        }

        // 4. 32×32 torch ladder grid at (+0..32, +2, +84..115)
        for (int dx = 0; dx < 32; dx++) {
            for (int dz = 0; dz < 32; dz++) {
                int x = bx + dx;
                int z = bz + 84 + dz;
                w.getBlockAt(x, by, z).setType(Material.STONE, false);
                w.getBlockAt(x, by + 1, z).setType(Material.REDSTONE_TORCH, false);
            }
        }
    }

    private void buildRepeaterClockAt(World w, int bx, int by, int bz) {
        // 4-cell loop with a 1-tick repeater on each side, kicked with redstone block
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                w.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);
            }
        }
        for (int dx = 0; dx < 4; dx++) {
            w.getBlockAt(bx + dx, by + 1, bz).setType(Material.REDSTONE_WIRE, false);
            w.getBlockAt(bx + dx, by + 1, bz + 3).setType(Material.REDSTONE_WIRE, false);
        }
        for (int dz = 1; dz < 3; dz++) {
            Block repE = w.getBlockAt(bx, by + 1, bz + dz);
            repE.setType(Material.REPEATER, false);
            if (repE.getBlockData() instanceof Repeater r) {
                r.setFacing(BlockFace.EAST);
                r.setDelay(2);
                repE.setBlockData(r, false);
            }
            w.getBlockAt(bx + 3, by + 1, bz + dz).setType(Material.REDSTONE_WIRE, false);
        }
        // kick
        w.getBlockAt(bx + 1, by + 1, bz + 1).setType(Material.REDSTONE_BLOCK, false);
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
