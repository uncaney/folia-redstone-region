package net.ekaii.redstone.region.cmd;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.ekaii.redstone.region.config.ChunkPdcCodec;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.RedstoneMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;

/** Brigadier tree for {@code /redstone-region}. */
public final class RedstoneRegionCommand {

    private static final String PERM = "redstone-region.admin";

    private RedstoneRegionCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> root(ChunkRegistry registry) {
        return Commands.literal("redstone-region")
                .requires(s -> s.getSender().hasPermission(PERM))
                .executes(c -> help(c))   // bare /redstone-region also prints help
                .then(Commands.literal("help").executes(c -> help(c)))
                .then(Commands.literal("info").executes(c -> info(c, registry)))
                .then(Commands.literal("set")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests(RedstoneRegionCommand::suggestModes)
                                .executes(c -> set(c, registry))))
                .then(Commands.literal("fill")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 32))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(RedstoneRegionCommand::suggestModes)
                                        .executes(c -> fill(c, registry)))))
                .then(Commands.literal("clear")
                        .executes(c -> clearOne(c, registry))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 32))
                                .executes(c -> clearArea(c, registry))))
                .then(Commands.literal("list").executes(c -> list(c, registry)));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        var s = ctx.getSource().getSender();
        s.sendMessage(Component.text("=== /redstone-region ===", NamedTextColor.GOLD));
        s.sendMessage(Component.text("Choisis l'algo redstone par chunk: vanilla (Mojang) ou alternate-current (Space Walker, ~3-15× plus rapide sur dust).", NamedTextColor.GRAY));
        s.sendMessage(Component.empty());
        helpLine(s, "/redstone-region info",                          "mode du chunk où tu te trouves");
        helpLine(s, "/redstone-region set <mode>",                    "applique <mode> au chunk courant");
        helpLine(s, "/redstone-region fill <radius> <mode>",          "applique à un carré (2r+1)×(2r+1) chunks (r ≤ 32 = jusqu'à 65×65 chunks)");
        helpLine(s, "/redstone-region clear",                         "remet le chunk courant en vanilla");
        helpLine(s, "/redstone-region clear <radius>",                "remet un carré en vanilla");
        helpLine(s, "/redstone-region list",                          "compteur des chunks non-vanilla par dimension");
        s.sendMessage(Component.empty());
        s.sendMessage(Component.text("modes:", NamedTextColor.AQUA));
        s.sendMessage(Component.text("  vanilla            ", NamedTextColor.GRAY).append(Component.text("comportement Mojang strict, toutes les contraptions marchent", NamedTextColor.WHITE)));
        s.sendMessage(Component.text("  alternate-current  ", NamedTextColor.GRAY).append(Component.text("BFS + single-write, plus rapide mais quelques edge cases (cf. ci-dessous)", NamedTextColor.WHITE)));
        s.sendMessage(Component.empty());
        s.sendMessage(Component.text("edge cases qui ne marchent QU'EN vanilla:", NamedTextColor.YELLOW));
        s.sendMessage(Component.text("  • piston BUD via self-shape-update du wire (un palier intermédiaire est skippé en AC)", NamedTextColor.WHITE));
        s.sendMessage(Component.text("  • observer face à un wire qui compte les paliers de power", NamedTextColor.WHITE));
        s.sendMessage(Component.text("  • update-order strict MC-11193 (rare, ne casse que des designs ultra-précis)", NamedTextColor.WHITE));
        s.sendMessage(Component.empty());
        s.sendMessage(Component.text("conseil: garde le monde en vanilla par défaut, marque en AC les zones dust-heavy (sorters, mega-bases, ferme à coffres). Évite AC sur des trucs piston+observer mixés.", NamedTextColor.GRAY));
        s.sendMessage(Component.empty());
        s.sendMessage(Component.text("exemple:", NamedTextColor.AQUA));
        s.sendMessage(Component.text("  /redstone-region fill 8 alternate-current   ", NamedTextColor.GRAY).append(Component.text("17×17 chunks autour de toi en AC", NamedTextColor.WHITE)));
        s.sendMessage(Component.text("  /redstone-region info                       ", NamedTextColor.GRAY).append(Component.text("vérifie la prise d'effet", NamedTextColor.WHITE)));
        s.sendMessage(Component.text("  /redstone-region clear 8                    ", NamedTextColor.GRAY).append(Component.text("annule, retour au vanilla", NamedTextColor.WHITE)));
        return 1;
    }

    private static void helpLine(org.bukkit.command.CommandSender s, String cmd, String desc) {
        s.sendMessage(Component.text(cmd, NamedTextColor.GREEN)
                .append(Component.text(" — " + desc, NamedTextColor.WHITE)));
    }

    private static CompletableFuture<Suggestions> suggestModes(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        b.suggest("vanilla");
        b.suggest("alternate-current");
        return b.buildFuture();
    }

    private static int info(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) {
            src.getSender().sendMessage(Component.text("must be run by an entity to know which chunk", NamedTextColor.RED));
            return 0;
        }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        RedstoneMode mode = registry.modeOfChunk(dim, chunk.getX(), chunk.getZ());
        src.getSender().sendMessage(Component.text("chunk (" + chunk.getX() + ", " + chunk.getZ() + ") in "
                + dim.identifier() + " uses " + mode.slug(), NamedTextColor.GRAY));
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) {
            src.getSender().sendMessage(Component.text("must be run by an entity for /redstone-region set", NamedTextColor.RED));
            return 0;
        }
        String modeStr = StringArgumentType.getString(ctx, "mode");
        RedstoneMode mode = RedstoneMode.parse(modeStr);
        if (mode == null) {
            src.getSender().sendMessage(Component.text("unknown mode: " + modeStr, NamedTextColor.RED));
            return 0;
        }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        applyChunk(w, chunk.getX(), chunk.getZ(), mode, registry);
        src.getSender().sendMessage(Component.text("→ " + mode.slug() + " for chunk ("
                + chunk.getX() + ", " + chunk.getZ() + ")", NamedTextColor.GREEN));
        return 1;
    }

    private static int fill(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) {
            src.getSender().sendMessage(Component.text("must be run by an entity for /redstone-region fill", NamedTextColor.RED));
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        String modeStr = StringArgumentType.getString(ctx, "mode");
        RedstoneMode mode = RedstoneMode.parse(modeStr);
        if (mode == null) {
            src.getSender().sendMessage(Component.text("unknown mode: " + modeStr, NamedTextColor.RED));
            return 0;
        }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        int count = applyArea(w, chunk.getX(), chunk.getZ(), radius, mode, registry);
        src.getSender().sendMessage(Component.text("→ " + mode.slug() + " for "
                + count + " chunks", NamedTextColor.GREEN));
        return count;
    }

    private static int clearOne(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) return 0;
        Chunk chunk = executor.getLocation().getChunk();
        applyChunk(executor.getWorld(), chunk.getX(), chunk.getZ(), RedstoneMode.VANILLA, registry);
        src.getSender().sendMessage(Component.text("→ vanilla for chunk ("
                + chunk.getX() + ", " + chunk.getZ() + ")", NamedTextColor.GREEN));
        return 1;
    }

    private static int clearArea(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) return 0;
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        Chunk chunk = executor.getLocation().getChunk();
        int count = applyArea(executor.getWorld(), chunk.getX(), chunk.getZ(), radius, RedstoneMode.VANILLA, registry);
        src.getSender().sendMessage(Component.text("→ vanilla for " + count + " chunks", NamedTextColor.GREEN));
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        for (World w : Bukkit.getWorlds()) {
            ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
            int n = registry.trackedCount(dim);
            src.getSender().sendMessage(Component.text(dim.identifier() + ": " + n + " non-vanilla chunks",
                    n > 0 ? NamedTextColor.AQUA : NamedTextColor.GRAY));
        }
        return 1;
    }

    private static void applyChunk(World w, int x, int z, RedstoneMode mode, ChunkRegistry registry) {
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        registry.setMode(dim, x, z, mode);
        // Persist by writing the PDC on the owning region thread.
        var scheduler = Bukkit.getRegionScheduler();
        scheduler.execute(/* plugin */ pluginRef(), w, x, z, () -> {
            Chunk c = w.getChunkAt(x, z);
            ChunkPdcCodec.write(c, mode);
        });
    }

    private static int applyArea(World w, int cx, int cz, int radius, RedstoneMode mode, ChunkRegistry registry) {
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                applyChunk(w, cx + dx, cz + dz, mode, registry);
                n++;
            }
        }
        return n;
    }

    /* Plugin reference is injected at registration time by PluginMain. */
    private static volatile org.bukkit.plugin.Plugin PLUGIN;
    public static void bindPlugin(org.bukkit.plugin.Plugin plugin) { PLUGIN = plugin; }
    private static org.bukkit.plugin.Plugin pluginRef() {
        var p = PLUGIN;
        if (p == null) throw new IllegalStateException("RedstoneRegionCommand not bound to plugin");
        return p;
    }
}
