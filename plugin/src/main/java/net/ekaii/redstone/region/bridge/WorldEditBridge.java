package net.ekaii.redstone.region.bridge;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a player's WorldEdit selection and returns the chunks it spans.
 * Soft-dep — only callable when WorldEdit is present (Bukkit#getPluginManager
 * check is the gate; the importing class has to know).
 *
 * <p>We intentionally do not call any WorldEdit operation that schedules
 * world I/O (paste/undo/etc.); WE 7.3.x is not Folia-aware. All we touch is
 * {@code LocalSession} (in-memory).
 */
public final class WorldEditBridge {

    public enum Status { OK, NO_SELECTION, INCOMPLETE_SELECTION, WRONG_WORLD }

    public record Result(Status status, List<long[]> chunks, String message) {
        public boolean ok() { return status == Status.OK; }
    }

    private WorldEditBridge() {}

    public static Result collectSelectionChunks(Player player) {
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager()
                    .get(BukkitAdapter.adapt(player));
            com.sk89q.worldedit.world.World weWorld = session.getSelectionWorld();
            if (weWorld == null) {
                return new Result(Status.NO_SELECTION, List.of(),
                        "no WE selection — //pos1 //pos2 first, or use //sel <type>");
            }
            // Ensure the WE world matches the player's current world.
            if (!weWorld.getName().equals(player.getWorld().getName())) {
                return new Result(Status.WRONG_WORLD, List.of(),
                        "selection is in world '" + weWorld.getName()
                                + "' but you are in '" + player.getWorld().getName() + "'");
            }
            Region region = session.getSelection(weWorld);
            List<long[]> out = new ArrayList<>(region.getChunks().size());
            for (BlockVector2 c : region.getChunks()) {
                out.add(new long[]{ c.x(), c.z() });
            }
            return new Result(Status.OK, out, "");
        } catch (IncompleteRegionException ire) {
            return new Result(Status.INCOMPLETE_SELECTION, List.of(),
                    "WE selection is incomplete (set both corners)");
        } catch (Throwable t) {
            return new Result(Status.NO_SELECTION, List.of(),
                    "WorldEdit error: " + t.getMessage());
        }
    }
}
