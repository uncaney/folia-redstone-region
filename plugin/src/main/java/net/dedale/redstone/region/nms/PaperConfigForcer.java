package net.dedale.redstone.region.nms;

import io.papermc.paper.configuration.WorldConfiguration;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.logging.Logger;

/**
 * Forces {@code paperConfig.misc.redstoneImplementation = VANILLA} for every world.
 * Required because Paper short-circuits to its bundled Alternate-Current
 * {@code level.getWireHandler()} (which is per-{@link Level}, not per-region —
 * latently broken under Folia) when the config is set to {@code ALTERNATE_CURRENT}.
 * <p>
 * We force the value globally so every wire update flows through
 * {@code RedStoneWireBlock.updateSurroundingRedstone} →
 * {@code updatePowerStrength} → {@code this.evaluator.updatePowerStrength(...)},
 * which is the field we have swapped with our dispatcher.
 */
public final class PaperConfigForcer implements Listener {

    private final Logger log;

    public PaperConfigForcer(Logger log) { this.log = log; }

    public void forceAll() {
        for (var w : Bukkit.getWorlds()) {
            try {
                Level lvl = ((CraftWorld) w).getHandle();
                forceFor(lvl);
            } catch (Throwable t) {
                log.warning("could not force vanilla redstone-implementation for world "
                        + w.getName() + ": " + t);
            }
        }
    }

    private void forceFor(Level lvl) {
        WorldConfiguration cfg = lvl.paperConfig();
        var prior = cfg.misc.redstoneImplementation;
        if (prior != WorldConfiguration.Misc.RedstoneImplementation.VANILLA) {
            cfg.misc.redstoneImplementation = WorldConfiguration.Misc.RedstoneImplementation.VANILLA;
            log.info("[" + lvl.dimension().identifier() + "] redstone-implementation forced "
                    + prior + " -> VANILLA so per-chunk dispatch can hook the evaluator");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent e) {
        try { forceFor(((CraftWorld) e.getWorld()).getHandle()); } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldLoad(WorldLoadEvent e) {
        try { forceFor(((CraftWorld) e.getWorld()).getHandle()); } catch (Throwable ignored) {}
    }
}
