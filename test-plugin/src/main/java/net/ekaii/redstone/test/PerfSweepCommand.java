package net.ekaii.redstone.test;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class PerfSweepCommand extends Command {
    private final Plugin plugin;
    public PerfSweepCommand(Plugin plugin) {
        super("perf-sweep");
        this.plugin = plugin;
        setDescription("Sweep grid sizes 4..64, measure vanilla vs AC, write CSV.");
        setUsage("/perf-sweep");
        setPermission("redstone-region.demo");
    }
    @Override public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("§cmust be run by a player"); return true; }
        if (!sender.hasPermission(getPermission())) { sender.sendMessage("§cmissing permission " + getPermission()); return true; }
        new PerfSweep(plugin, p.getWorld(), p).start();
        return true;
    }
}
