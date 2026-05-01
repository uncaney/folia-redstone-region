package net.ekaii.redstone.test;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class DemoMapCommand extends Command {
    private final Plugin plugin;

    public DemoMapCommand(Plugin plugin) {
        super("demo-map");
        this.plugin = plugin;
        setDescription("Build the redstone demo showcase around you (vanilla vs AC).");
        setUsage("/demo-map");
        setPermission("redstone-region.demo");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cmust be run by a player");
            return true;
        }
        if (!sender.hasPermission(getPermission())) {
            sender.sendMessage("§cmissing permission " + getPermission());
            return true;
        }
        new DemoMapBuilder(plugin).buildAround(p);
        sender.sendMessage("§a[demo-map] building...");
        return true;
    }
}
