package net.dedale.redstone.region.util;

public final class ChunkKey {
    private ChunkKey() {}

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) { return (int) (key >> 32); }
    public static int unpackZ(long key) { return (int) key; }
}
