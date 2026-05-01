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
                .then(Commands.literal("list").executes(c -> list(c, registry)))
                .then(Commands.literal("where").executes(c -> where(c, registry)))
                .then(Commands.literal("map")
                        .executes(c -> map(c, registry, 8))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(c -> map(c, registry, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("stats")
                        .executes(c -> stats(c, 10))
                        .then(Commands.literal("reset").executes(c -> statsReset(c)))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 100))
                                .executes(c -> stats(c, IntegerArgumentType.getInteger(c, "limit")))))
                .then(Commands.literal("check").executes(c -> check(c, registry)))
                .then(Commands.literal("selection")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests(RedstoneRegionCommand::suggestModes)
                                .executes(c -> selection(c, registry))));
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
        helpLine(s, "/redstone-region where",                         "liste détaillée des chunks non-vanilla du monde courant");
        helpLine(s, "/redstone-region map [radius]",                  "mini-map ASCII en chat (radius par défaut 8 = 17×17 chunks)");
        helpLine(s, "/redstone-region stats [limit]",                 "top-N chunks les plus chers en redstone");
        helpLine(s, "/redstone-region stats reset",                   "vide les compteurs de timing");
        helpLine(s, "/redstone-region check",                         "scanne le chunk pour patterns piston/observer suspects");
        helpLine(s, "/redstone-region selection <mode>",              "applique <mode> à la selection WorldEdit du joueur");
        s.sendMessage(Component.empty());
        s.sendMessage(Component.text("modes:", NamedTextColor.AQUA));
        s.sendMessage(Component.text("  vanilla            ", NamedTextColor.GRAY).append(Component.text("comportement Mojang strict, toutes les contraptions marchent", NamedTextColor.WHITE)));
        s.sendMessage(Component.text("  alternate-current  ", NamedTextColor.GRAY).append(Component.text("BFS + single-write, plus rapide mais quelques edge cases (cf. ci-dessous)", NamedTextColor.WHITE)));
        s.sendMessage(Component.text("  eigencraft         ", NamedTextColor.GRAY).append(Component.text("RedstoneWireTurbo (theosib), 3-5× plus rapide, meilleure compat que AC", NamedTextColor.WHITE)));
        s.sendMessage(Component.text("  disabled           ", NamedTextColor.GRAY).append(Component.text("le wire ne tick plus du tout, état figé (archive zone)", NamedTextColor.WHITE)));
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

    private static int where(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) {
            src.getSender().sendMessage(Component.text("must be run by an entity (uses your current world)", NamedTextColor.RED));
            return 0;
        }
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        long[] keys = registry.snapshotKeys(dim);
        var sender = src.getSender();
        sender.sendMessage(Component.text("=== AC chunks in " + dim.identifier() + " (" + keys.length + ") ===", NamedTextColor.GOLD));
        if (keys.length == 0) {
            sender.sendMessage(Component.text("(none — every chunk is vanilla)", NamedTextColor.GRAY));
            return 1;
        }
        // Sort for deterministic output
        java.util.Arrays.sort(keys);
        int max = Math.min(keys.length, 50);
        for (int i = 0; i < max; i++) {
            int cx = net.ekaii.redstone.region.util.ChunkKey.unpackX(keys[i]);
            int cz = net.ekaii.redstone.region.util.ChunkKey.unpackZ(keys[i]);
            int wx = cx << 4, wz = cz << 4;
            sender.sendMessage(Component.text(
                    String.format("  chunk (%4d, %4d)  →  blocks (%5d..%5d, %5d..%5d)",
                            cx, cz, wx, wx + 15, wz, wz + 15),
                    NamedTextColor.AQUA));
        }
        if (keys.length > max) {
            sender.sendMessage(Component.text("  … " + (keys.length - max) + " more (truncated)", NamedTextColor.GRAY));
        }
        return keys.length;
    }

    private static int map(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry, int radius) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) {
            src.getSender().sendMessage(Component.text("must be run by an entity (centred on your chunk)", NamedTextColor.RED));
            return 0;
        }
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        Chunk you = executor.getLocation().getChunk();
        int cx = you.getX(), cz = you.getZ();
        var sender = src.getSender();
        sender.sendMessage(Component.text("=== map @ chunk (" + cx + ", " + cz + ") radius=" + radius + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("█ AC   · vanilla   ◉ you", NamedTextColor.GRAY));
        // Render N→S top-to-bottom (decreasing Z is north on Bukkit; player oriented map: north up)
        for (int dz = -radius; dz <= radius; dz++) {
            Component line = Component.empty();
            for (int dx = -radius; dx <= radius; dx++) {
                int qx = cx + dx, qz = cz + dz;
                boolean isYou = (dx == 0 && dz == 0);
                RedstoneMode m = registry.modeOfChunk(dim, qx, qz);
                if (isYou) {
                    line = line.append(Component.text("◉",
                            m == RedstoneMode.ALTERNATE_CURRENT ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
                } else if (m == RedstoneMode.ALTERNATE_CURRENT) {
                    line = line.append(Component.text("█", NamedTextColor.GREEN));
                } else {
                    line = line.append(Component.text("·", NamedTextColor.DARK_GRAY));
                }
            }
            sender.sendMessage(line);
        }
        // Footer scale hint
        sender.sendMessage(Component.text(
                "1 char = 1 chunk (16 blocks); covers ±" + (radius * 16) + " blocks",
                NamedTextColor.GRAY));
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx, int limit) {
        var src = ctx.getSource();
        if (TIMING == null) {
            src.getSender().sendMessage(Component.text("timing table unavailable", NamedTextColor.RED));
            return 0;
        }
        var top = TIMING.top(limit);
        src.getSender().sendMessage(Component.text("=== Top " + top.size() + " hottest chunks (by total redstone time) ===", NamedTextColor.GOLD));
        if (top.isEmpty()) {
            src.getSender().sendMessage(Component.text("no recorded redstone activity yet — toggle a lever first", NamedTextColor.GRAY));
            return 0;
        }
        int i = 0;
        for (var hot : top) {
            i++;
            String dim = hot.dim();
            var cell = hot.cell();
            src.getSender().sendMessage(Component.text(
                    String.format("%2d. %s (%4d, %4d)  count=%5d  avg=%6.2fms  max=%6.2fms  total=%7.0fms",
                            i, shortDim(dim), hot.cx(), hot.cz(),
                            cell.count(), cell.avgMs(), cell.maxNs() / 1_000_000.0, cell.totalNs() / 1_000_000.0),
                    NamedTextColor.AQUA));
        }
        return 1;
    }

    private static String shortDim(String dim) {
        int slash = dim.indexOf(':');
        return slash >= 0 ? dim.substring(slash + 1) : dim;
    }

    private static int statsReset(CommandContext<CommandSourceStack> ctx) {
        if (TIMING != null) TIMING.resetAll();
        ctx.getSource().getSender().sendMessage(Component.text("timing table cleared", NamedTextColor.GREEN));
        return 1;
    }

    private static int check(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) {
            src.getSender().sendMessage(Component.text("must be run by an entity", NamedTextColor.RED));
            return 0;
        }
        Chunk c = executor.getLocation().getChunk();
        World w = executor.getWorld();
        var sender = src.getSender();
        sender.sendMessage(Component.text("=== AC compatibility check (chunk " + c.getX() + "," + c.getZ() + ") ===", NamedTextColor.GOLD));
        // Sample blocks for piston / observer / sticky-piston / wire mix
        int pistons = 0, stickies = 0, observers = 0, dust = 0, repeaters = 0, comparators = 0;
        int bx = c.getX() << 4, bz = c.getZ() << 4;
        int yMin = w.getMinHeight(), yMax = w.getMaxHeight();
        for (int x = bx; x < bx + 16; x++) {
            for (int z = bz; z < bz + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    var t = w.getBlockAt(x, y, z).getType();
                    switch (t) {
                        case PISTON:        pistons++;     break;
                        case STICKY_PISTON: stickies++;    break;
                        case OBSERVER:      observers++;   break;
                        case REDSTONE_WIRE: dust++;        break;
                        case REPEATER:      repeaters++;   break;
                        case COMPARATOR:    comparators++; break;
                        default:                           break;
                    }
                }
            }
        }
        sender.sendMessage(Component.text(
                "  dust=" + dust + "  repeaters=" + repeaters + "  comparators=" + comparators
                        + "  pistons=" + pistons + "  sticky=" + stickies + "  observers=" + observers,
                NamedTextColor.AQUA));
        boolean risky = (pistons + stickies > 0 && observers > 0)
                     || (pistons + stickies > 0 && dust > 8);
        if (risky) {
            sender.sendMessage(Component.text(
                    "⚠ Potentially incompatible with Alternate Current: piston/observer/dust mix detected.",
                    NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("  Pistons relying on quasi-connectivity from a wire's self-shape-update", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  may not fire under AC. Consider keeping this chunk vanilla, or test in a copy.", NamedTextColor.GRAY));
        } else if (dust > 0) {
            sender.sendMessage(Component.text("✓ Looks AC-friendly (dust-heavy, no risky piston/observer mix)", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("(no redstone components detected — AC vs vanilla is moot here)", NamedTextColor.GRAY));
        }
        return 1;
    }

    private static int selection(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (!(executor instanceof org.bukkit.entity.Player p)) {
            src.getSender().sendMessage(Component.text("must be run by a player (uses your WorldEdit selection)", NamedTextColor.RED));
            return 0;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")
                && !Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) {
            src.getSender().sendMessage(Component.text("WorldEdit / FAWE not installed", NamedTextColor.RED));
            return 0;
        }
        String modeStr = StringArgumentType.getString(ctx, "mode");
        RedstoneMode mode = RedstoneMode.parse(modeStr);
        if (mode == null) {
            src.getSender().sendMessage(Component.text("unknown mode: " + modeStr, NamedTextColor.RED));
            return 0;
        }
        var result = net.ekaii.redstone.region.bridge.WorldEditBridge.collectSelectionChunks(p);
        if (!result.ok()) {
            src.getSender().sendMessage(Component.text("[WE] " + result.message(), NamedTextColor.YELLOW));
            return 0;
        }
        for (long[] xz : result.chunks()) {
            applyChunk(p.getWorld(), (int) xz[0], (int) xz[1], mode, registry, p, "we-selection");
        }
        src.getSender().sendMessage(Component.text("→ " + mode.slug() + " for "
                + result.chunks().size() + " chunks (WE selection)", NamedTextColor.GREEN));
        return result.chunks().size();
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
        applyChunk(w, x, z, mode, registry, null, "command");
    }

    private static void applyChunk(World w, int x, int z, RedstoneMode mode, ChunkRegistry registry,
                                   org.bukkit.command.CommandSender actor, String reason) {
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        RedstoneMode prev = registry.modeOfChunk(dim, x, z);
        registry.setMode(dim, x, z, mode);
        var scheduler = Bukkit.getRegionScheduler();
        scheduler.execute(pluginRef(), w, x, z, () -> {
            Chunk c = w.getChunkAt(x, z);
            ChunkPdcCodec.write(c, mode);
        });
        if (prev != mode) {
            if (AUDIT != null) {
                net.ekaii.redstone.region.audit.AuditLog.Source src =
                        "we-selection".equals(reason) ? net.ekaii.redstone.region.audit.AuditLog.Source.WORLDEDIT
                                                      : net.ekaii.redstone.region.audit.AuditLog.Source.COMMAND;
                var ev = AUDIT.makeEvent(src, actor, dim.identifier().toString(), x, z, prev, mode, reason);
                AUDIT.record(ev);
                if (DISCORD != null) DISCORD.send(ev);
            }
            if (BLUE_MAP != null) BLUE_MAP.setMode(w.getName(), x, z, mode);
        }
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

    /* Plugin context injected at registration time by PluginMain. */
    private static volatile org.bukkit.plugin.Plugin PLUGIN;
    private static volatile net.ekaii.redstone.region.audit.AuditLog AUDIT;
    private static volatile net.ekaii.redstone.region.timing.ChunkTimingTable TIMING;
    private static volatile net.ekaii.redstone.region.bridge.DiscordWebhook DISCORD;
    private static volatile net.ekaii.redstone.region.config.PluginConfig CFG;
    private static volatile net.ekaii.redstone.region.bridge.BlueMapBridge BLUE_MAP;

    public static void bindContext(org.bukkit.plugin.Plugin plugin,
                                   net.ekaii.redstone.region.audit.AuditLog audit,
                                   net.ekaii.redstone.region.timing.ChunkTimingTable timing,
                                   net.ekaii.redstone.region.bridge.DiscordWebhook discord,
                                   net.ekaii.redstone.region.config.PluginConfig cfg,
                                   net.ekaii.redstone.region.bridge.BlueMapBridge blueMap) {
        PLUGIN = plugin;
        AUDIT = audit;
        TIMING = timing;
        DISCORD = discord;
        CFG = cfg;
        BLUE_MAP = blueMap;
    }
    private static org.bukkit.plugin.Plugin pluginRef() {
        var p = PLUGIN;
        if (p == null) throw new IllegalStateException("RedstoneRegionCommand not bound to plugin");
        return p;
    }
}
