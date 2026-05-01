package net.dedale.redstone.test;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class LagMachineCommand extends Command {
    private final Plugin plugin;

    public LagMachineCommand(Plugin plugin) {
        super("lag-machine");
        this.plugin = plugin;
        setDescription("Build a vanilla and AC copy of an intentional redstone lag machine.");
        setUsage("/lag-machine");
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
        new LagMachineBuilder(plugin).buildAround(p);
        sender.sendMessage("§a[lag-machine] building two side-by-side machines (vanilla + AC, ~170 blocks apart)");
        return true;
    }
}
