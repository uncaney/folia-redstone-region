package net.ekaii.redstone.region.config;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class ChunkPdcCodec {

    public static final NamespacedKey KEY = new NamespacedKey("ekaii", "redstone_engine");

    private ChunkPdcCodec() {}

    public static RedstoneMode read(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        Byte v = pdc.get(KEY, PersistentDataType.BYTE);
        return v == null ? RedstoneMode.VANILLA : RedstoneMode.fromId(v);
    }

    public static void write(Chunk chunk, RedstoneMode mode) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (mode == RedstoneMode.VANILLA) {
            pdc.remove(KEY);
        } else {
            pdc.set(KEY, PersistentDataType.BYTE, mode.id());
        }
    }
}
