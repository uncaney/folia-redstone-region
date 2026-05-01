package net.ekaii.redstone.region;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.ekaii.redstone.region.cmd.RedstoneRegionCommand;
import net.ekaii.redstone.region.config.ChunkRegistry;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the {@code /redstone-region} Brigadier command tree at the lifecycle
 * phase recommended by Paper for plugin commands. The actual NMS evaluator swap
 * happens in {@link PluginMain#onEnable()} (after worlds load, when
 * {@code Blocks.REDSTONE_WIRE} is fully wired).
 */
public final class Bootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                event -> event.registrar().register(
                        RedstoneRegionCommand.root(ChunkRegistry.get()).build(),
                        "Per-chunk redstone-engine dispatch (vanilla / alternate-current)."));
    }
}
