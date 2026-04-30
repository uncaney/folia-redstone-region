package net.dedale.redstone.region;

import net.dedale.redstone.region.cmd.RedstoneRegionCommand;
import net.dedale.redstone.region.config.ChunkRegistry;
import net.dedale.redstone.region.listeners.ChunkSyncListener;
import net.dedale.redstone.region.nms.EvaluatorSwap;
import net.dedale.redstone.region.nms.PaperConfigForcer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public final class PluginMain extends JavaPlugin {

    private PaperConfigForcer configForcer;

    @Override
    public void onEnable() {
        Logger log = getLogger();

        // 1. Force redstone-implementation to VANILLA on every world so
        //    Paper's gating doesn't short-circuit to its (Folia-unsafe) per-level
        //    bundled WireHandler before we get a chance to dispatch.
        configForcer = new PaperConfigForcer(log);
        configForcer.forceAll();
        Bukkit.getPluginManager().registerEvents(configForcer, this);

        // 2. Swap RedStoneWireBlock.evaluator with our DispatchingEvaluator.
        try {
            EvaluatorSwap.install(ChunkRegistry.get(), log);
        } catch (ReflectiveOperationException e) {
            log.severe("could not install dispatching evaluator: " + e);
            log.severe("plugin will continue but per-chunk dispatch is INACTIVE — disable to revert");
        }

        // 3. Sync chunk PDC ↔ in-memory registry on chunk load/unload.
        Bukkit.getPluginManager().registerEvents(new ChunkSyncListener(ChunkRegistry.get()), this);

        // 4. Bind plugin reference for region-scheduled PDC writes from commands.
        RedstoneRegionCommand.bindPlugin(this);

        log.info("folia-redstone-region " + getPluginMeta().getVersion() + " ready");
    }

    @Override
    public void onDisable() {
        Logger log = getLogger();
        try {
            EvaluatorSwap.uninstall(log);
        } catch (ReflectiveOperationException e) {
            log.warning("could not uninstall evaluator: " + e);
        }
    }
}
