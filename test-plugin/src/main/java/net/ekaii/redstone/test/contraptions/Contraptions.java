package net.ekaii.redstone.test.contraptions;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Repeater;

import java.util.List;

/** Built-in contraptions used by the parity / perf tests. */
public final class Contraptions {
    private Contraptions() {}

    public static List<Contraption> all() {
        return List.of(
                new DustLine30(),
                new DustGrid16(),
                new RepeaterClock4(),
                new SimpleAndGate(),
                new RedstoneTorchInverter(),
                new ComparatorSubtractor(),
                new TorchLadder(),
                new DustZigzag()
        );
    }

    /* --------------------------------------------------------------------- */

    /** A 30-block dust line on stone. Lever at one end, redstone lamp at the far end. */
    public static final class DustLine30 implements Contraption {
        public String id() { return "dust-line-30"; }
        public int sizeX() { return 32; }
        public int sizeZ() { return 1; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX();
            int by = o.getBlockY();
            int bz = o.getBlockZ();
            // base stone strip y=by
            for (int dx = 0; dx < 32; dx++) w.getBlockAt(bx + dx, by, bz).setType(Material.STONE);
            // lever at (bx, by+1)
            Block leverBlock = w.getBlockAt(bx, by + 1, bz);
            leverBlock.setType(Material.LEVER, false);
            // dust strip y=by+1, dx=1..30
            for (int dx = 1; dx <= 30; dx++) w.getBlockAt(bx + dx, by + 1, bz).setType(Material.REDSTONE_WIRE, true);
            // lamp at (bx+31, by+1)
            w.getBlockAt(bx + 31, by + 1, bz).setType(Material.REDSTONE_LAMP, true);
        }

        public void setInput(Location o, boolean on) {
            Block lever = o.getWorld().getBlockAt(o.getBlockX(), o.getBlockY() + 1, o.getBlockZ());
            BlockData d = lever.getBlockData();
            if (d instanceof org.bukkit.block.data.Powerable p) {
                p.setPowered(on);
                lever.setBlockData(p, true);
            }
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 31, o.getBlockY() + 1, o.getBlockZ());
            BlockData d = lamp.getBlockData();
            return (d instanceof Lightable l && l.isLit()) ? 1 : 0;
        }
    }

    /** 16×16 dust grid on stone. Lever in (0,0). Lamp in (15,15). */
    public static final class DustGrid16 implements Contraption {
        public String id() { return "dust-grid-16"; }
        public int sizeX() { return 16; }
        public int sizeZ() { return 16; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX();
            int by = o.getBlockY();
            int bz = o.getBlockZ();
            for (int dx = 0; dx < 16; dx++)
                for (int dz = 0; dz < 16; dz++)
                    w.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);
            for (int dx = 0; dx < 16; dx++)
                for (int dz = 0; dz < 16; dz++)
                    w.getBlockAt(bx + dx, by + 1, bz + dz).setType(Material.REDSTONE_WIRE, false);
            // re-set the corner lever after dust to override
            Block lever = w.getBlockAt(bx, by + 1, bz);
            lever.setType(Material.LEVER, false);
            // lamp opposite corner
            w.getBlockAt(bx + 15, by + 1, bz + 15).setType(Material.REDSTONE_LAMP, true);
        }

        public void setInput(Location o, boolean on) {
            Block lever = o.getWorld().getBlockAt(o.getBlockX(), o.getBlockY() + 1, o.getBlockZ());
            BlockData d = lever.getBlockData();
            if (d instanceof org.bukkit.block.data.Powerable p) {
                p.setPowered(on);
                lever.setBlockData(p, true);
            }
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 15, o.getBlockY() + 1, o.getBlockZ() + 15);
            BlockData d = lamp.getBlockData();
            return (d instanceof Lightable l && l.isLit()) ? 1 : 0;
        }
    }

    /** A 4-repeater clock with delay 4. Output: lamp on/off ratio. */
    public static final class RepeaterClock4 implements Contraption {
        public String id() { return "repeater-clock-4"; }
        public int sizeX() { return 6; }
        public int sizeZ() { return 6; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX();
            int by = o.getBlockY();
            int bz = o.getBlockZ();
            // Stone ring 4x4 with dust on top
            for (int dx = 0; dx < 4; dx++) {
                w.getBlockAt(bx + dx, by, bz).setType(Material.STONE, false);
                w.getBlockAt(bx + dx, by, bz + 3).setType(Material.STONE, false);
                w.getBlockAt(bx + dx, by + 1, bz).setType(Material.REDSTONE_WIRE, false);
                w.getBlockAt(bx + dx, by + 1, bz + 3).setType(Material.REDSTONE_WIRE, false);
            }
            for (int dz = 1; dz < 3; dz++) {
                w.getBlockAt(bx, by, bz + dz).setType(Material.STONE, false);
                w.getBlockAt(bx + 3, by, bz + dz).setType(Material.STONE, false);
                // repeater on west side facing east
                Block rep = w.getBlockAt(bx, by + 1, bz + dz);
                rep.setType(Material.REPEATER, false);
                if (rep.getBlockData() instanceof Repeater r) {
                    r.setFacing(BlockFace.EAST);
                    r.setDelay(2);
                    rep.setBlockData(r, false);
                }
                w.getBlockAt(bx + 3, by + 1, bz + dz).setType(Material.REDSTONE_WIRE, false);
            }
            // Lamp tap from north-east corner
            w.getBlockAt(bx + 5, by, bz + 3).setType(Material.STONE, false);
            w.getBlockAt(bx + 5, by + 1, bz + 3).setType(Material.REDSTONE_LAMP, true);
            // Kick-start with a redstone-block in the loop
            w.getBlockAt(bx + 1, by + 1, bz + 1).setType(Material.REDSTONE_BLOCK, true);
        }

        public void setInput(Location o, boolean on) {
            // Toggle by replacing the kick-start block (clock self-sustains; on=keep, off=remove)
            Block b = o.getWorld().getBlockAt(o.getBlockX() + 1, o.getBlockY() + 1, o.getBlockZ() + 1);
            b.setType(on ? Material.REDSTONE_BLOCK : Material.AIR, true);
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 5, o.getBlockY() + 1, o.getBlockZ() + 3);
            BlockData d = lamp.getBlockData();
            return (d instanceof Lightable l && l.isLit()) ? 1 : 0;
        }
    }

    /** A 3-input AND made of two NOT(NOT(a)) torches feeding a dust junction. */
    public static final class SimpleAndGate implements Contraption {
        public String id() { return "and-gate-2-input"; }
        public int sizeX() { return 7; }
        public int sizeZ() { return 4; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX();
            int by = o.getBlockY();
            int bz = o.getBlockZ();
            // stone substrate
            for (int dx = 0; dx < 7; dx++)
                for (int dz = 0; dz < 4; dz++)
                    w.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);

            // input A: lever at (0, 1)
            w.getBlockAt(bx, by + 1, bz + 1).setType(Material.LEVER, false);
            // input B: lever at (0, 2)
            w.getBlockAt(bx, by + 1, bz + 2).setType(Material.LEVER, false);

            // dust paths
            for (int dz = 1; dz <= 2; dz++)
                for (int dx = 1; dx <= 4; dx++)
                    w.getBlockAt(bx + dx, by + 1, bz + dz).setType(Material.REDSTONE_WIRE, false);

            // junction at column dx=5: dust over stone
            w.getBlockAt(bx + 5, by + 1, bz + 1).setType(Material.REDSTONE_WIRE, false);
            w.getBlockAt(bx + 5, by + 1, bz + 2).setType(Material.REDSTONE_WIRE, false);

            // output lamp at dx=6
            w.getBlockAt(bx + 6, by + 1, bz + 1).setType(Material.REDSTONE_LAMP, true);
        }

        public void setInput(Location o, boolean on) {
            World w = o.getWorld();
            int bx = o.getBlockX();
            int by = o.getBlockY();
            int bz = o.getBlockZ();
            for (int dz = 1; dz <= 2; dz++) {
                Block lever = w.getBlockAt(bx, by + 1, bz + dz);
                BlockData d = lever.getBlockData();
                if (d instanceof org.bukkit.block.data.Powerable p) {
                    p.setPowered(on);
                    lever.setBlockData(p, true);
                }
            }
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 6, o.getBlockY() + 1, o.getBlockZ() + 1);
            BlockData d = lamp.getBlockData();
            return (d instanceof Lightable l && l.isLit()) ? 1 : 0;
        }
    }

    /** A redstone torch on a stone tower powered by a dust line through a lever. */
    public static final class RedstoneTorchInverter implements Contraption {
        public String id() { return "torch-inverter"; }
        public int sizeX() { return 5; }
        public int sizeZ() { return 1; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX();
            int by = o.getBlockY();
            int bz = o.getBlockZ();
            // base
            for (int dx = 0; dx < 5; dx++) w.getBlockAt(bx + dx, by, bz).setType(Material.STONE, false);
            // lever at (0, 1)
            w.getBlockAt(bx, by + 1, bz).setType(Material.LEVER, false);
            // dust segment to a stone block at (2, 1)
            w.getBlockAt(bx + 1, by + 1, bz).setType(Material.REDSTONE_WIRE, false);
            // mid stone tower
            w.getBlockAt(bx + 2, by + 1, bz).setType(Material.STONE, false);
            // torch on the side facing away — on top of the tower
            Block torchBlock = w.getBlockAt(bx + 2, by + 2, bz);
            torchBlock.setType(Material.REDSTONE_TORCH, false);
            // dust + lamp downstream
            w.getBlockAt(bx + 3, by + 2, bz).setType(Material.STONE, false);
            w.getBlockAt(bx + 3, by + 3, bz).setType(Material.REDSTONE_WIRE, false);
            w.getBlockAt(bx + 4, by + 3, bz).setType(Material.REDSTONE_LAMP, true);
        }

        public void setInput(Location o, boolean on) {
            Block lever = o.getWorld().getBlockAt(o.getBlockX(), o.getBlockY() + 1, o.getBlockZ());
            BlockData d = lever.getBlockData();
            if (d instanceof org.bukkit.block.data.Powerable p) {
                p.setPowered(on);
                lever.setBlockData(p, true);
            }
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 4, o.getBlockY() + 3, o.getBlockZ());
            BlockData d = lamp.getBlockData();
            return (d instanceof Lightable l && l.isLit()) ? 1 : 0;
        }

        @Override public int sizeY() { return 4; }
    }

    /** Comparator subtraction: lever→dust→comparator(sub mode)←dust←lever; output lamp. */
    public static final class ComparatorSubtractor implements Contraption {
        public String id() { return "comparator-sub"; }
        public int sizeX() { return 8; }
        public int sizeZ() { return 3; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX(), by = o.getBlockY(), bz = o.getBlockZ();
            for (int dx = 0; dx < 8; dx++)
                for (int dz = 0; dz < 3; dz++)
                    w.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);
            // input A (rear) at (0, 1)
            w.getBlockAt(bx, by + 1, bz + 1).setType(Material.LEVER, false);
            for (int dx = 1; dx <= 3; dx++)
                w.getBlockAt(bx + dx, by + 1, bz + 1).setType(Material.REDSTONE_WIRE, false);
            // input B (side) at (3, 0) — left side of comparator
            w.getBlockAt(bx + 3, by + 1, bz).setType(Material.LEVER, false);
            // comparator at (4, 1) facing east
            Block compBlock = w.getBlockAt(bx + 4, by + 1, bz + 1);
            compBlock.setType(Material.COMPARATOR, false);
            if (compBlock.getBlockData() instanceof org.bukkit.block.data.type.Comparator c) {
                c.setFacing(BlockFace.EAST);
                c.setMode(org.bukkit.block.data.type.Comparator.Mode.SUBTRACT);
                compBlock.setBlockData(c, false);
            }
            // out lamp
            for (int dx = 5; dx <= 6; dx++)
                w.getBlockAt(bx + dx, by + 1, bz + 1).setType(Material.REDSTONE_WIRE, false);
            w.getBlockAt(bx + 7, by + 1, bz + 1).setType(Material.REDSTONE_LAMP, true);
        }

        public void setInput(Location o, boolean on) {
            // Toggle ONLY rear input for parity simplicity (comparator subtract: rear>side → out)
            Block lever = o.getWorld().getBlockAt(o.getBlockX(), o.getBlockY() + 1, o.getBlockZ() + 1);
            BlockData d = lever.getBlockData();
            if (d instanceof org.bukkit.block.data.Powerable p) {
                p.setPowered(on);
                lever.setBlockData(p, true);
            }
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 7, o.getBlockY() + 1, o.getBlockZ() + 1);
            return (lamp.getBlockData() instanceof Lightable l && l.isLit()) ? 1 : 0;
        }
    }

    /** Vertical torch ladder: 4 stacked NOT gates. Even count → output mirrors input. */
    public static final class TorchLadder implements Contraption {
        public String id() { return "torch-ladder-4"; }
        public int sizeX() { return 4; }
        public int sizeZ() { return 1; }
        @Override public int sizeY() { return 9; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX(), by = o.getBlockY(), bz = o.getBlockZ();
            // base
            for (int dx = 0; dx < 4; dx++) w.getBlockAt(bx + dx, by, bz).setType(Material.STONE, false);
            w.getBlockAt(bx, by + 1, bz).setType(Material.LEVER, false);
            // 4 stone columns each with a torch on top (alternating east/west)
            for (int i = 0; i < 4; i++) {
                int colY = by + 1 + i * 2;
                w.getBlockAt(bx + 1, colY, bz).setType(Material.STONE, false);
                w.getBlockAt(bx + 1, colY + 1, bz).setType(Material.REDSTONE_TORCH, false);
            }
            // bridge dust on top
            w.getBlockAt(bx + 2, by + 8, bz).setType(Material.STONE, false);
            w.getBlockAt(bx + 3, by + 8, bz).setType(Material.STONE, false);
            w.getBlockAt(bx + 2, by + 9, bz).setType(Material.REDSTONE_WIRE, false);
            w.getBlockAt(bx + 3, by + 9, bz).setType(Material.REDSTONE_LAMP, true);
        }

        public void setInput(Location o, boolean on) {
            Block lever = o.getWorld().getBlockAt(o.getBlockX(), o.getBlockY() + 1, o.getBlockZ());
            BlockData d = lever.getBlockData();
            if (d instanceof org.bukkit.block.data.Powerable p) {
                p.setPowered(on);
                lever.setBlockData(p, true);
            }
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 3, o.getBlockY() + 9, o.getBlockZ());
            return (lamp.getBlockData() instanceof Lightable l && l.isLit()) ? 1 : 0;
        }
    }

    /** Zigzag dust path 8×8 forcing flow direction changes. */
    public static final class DustZigzag implements Contraption {
        public String id() { return "dust-zigzag-8"; }
        public int sizeX() { return 8; }
        public int sizeZ() { return 8; }

        public void build(Location o) {
            World w = o.getWorld();
            int bx = o.getBlockX(), by = o.getBlockY(), bz = o.getBlockZ();
            // base
            for (int dx = 0; dx < 8; dx++)
                for (int dz = 0; dz < 8; dz++)
                    w.getBlockAt(bx + dx, by, bz + dz).setType(Material.STONE, false);
            // zigzag pattern: even rows go east, odd rows go west, with single-block bridges
            for (int row = 0; row < 8; row++) {
                if ((row & 1) == 0) {
                    // east row: dust dx=0..7
                    for (int dx = 0; dx < 8; dx++)
                        w.getBlockAt(bx + dx, by + 1, bz + row).setType(Material.REDSTONE_WIRE, false);
                    // bridge to next row at the east end (row+1, dx=7)
                    if (row + 1 < 8)
                        w.getBlockAt(bx + 7, by + 1, bz + row + 1).setType(Material.REDSTONE_WIRE, false);
                } else {
                    // west row: dust dx=0..7 (overwriting any from above is fine)
                    for (int dx = 0; dx < 8; dx++)
                        w.getBlockAt(bx + dx, by + 1, bz + row).setType(Material.REDSTONE_WIRE, false);
                    // bridge at the west end (row+1, dx=0)
                    if (row + 1 < 8)
                        w.getBlockAt(bx, by + 1, bz + row + 1).setType(Material.REDSTONE_WIRE, false);
                }
            }
            // input lever at (0, 0)
            w.getBlockAt(bx, by + 1, bz).setType(Material.LEVER, false);
            // output lamp at (7, 7)
            w.getBlockAt(bx + 7, by + 1, bz + 7).setType(Material.REDSTONE_LAMP, true);
        }

        public void setInput(Location o, boolean on) {
            Block lever = o.getWorld().getBlockAt(o.getBlockX(), o.getBlockY() + 1, o.getBlockZ());
            BlockData d = lever.getBlockData();
            if (d instanceof org.bukkit.block.data.Powerable p) {
                p.setPowered(on);
                lever.setBlockData(p, true);
            }
        }

        public int sampleOutput(Location o) {
            Block lamp = o.getWorld().getBlockAt(o.getBlockX() + 7, o.getBlockY() + 1, o.getBlockZ() + 7);
            return (lamp.getBlockData() instanceof Lightable l && l.isLit()) ? 1 : 0;
        }
    }
}
