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
                .then(Commands.literal("profile").executes(c -> profile(c, registry)))
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
        helpLine(s, "/redstone-region profile",                       "rapport complet du chunk : composants + timing + reco mode");
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
        applyChunk(w, chunk.getX(), chunk.getZ(), mode, registry, src.getSender(), "command");
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
        int count = applyArea(w, chunk.getX(), chunk.getZ(), radius, mode, registry, src.getSender(), "command-fill");
        src.getSender().sendMessage(Component.text("→ " + mode.slug() + " for "
                + count + " chunks", NamedTextColor.GREEN));
        return count;
    }

    private static int clearOne(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) return 0;
        Chunk chunk = executor.getLocation().getChunk();
        applyChunk(executor.getWorld(), chunk.getX(), chunk.getZ(), RedstoneMode.VANILLA, registry, src.getSender(), "command-clear");
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
        int count = applyArea(executor.getWorld(), chunk.getX(), chunk.getZ(), radius, RedstoneMode.VANILLA, registry, src.getSender(), "command-clear-area");
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
            src.getSender().sendMessage(Component.text(
                    "no recorded redstone activity yet — toggle a lever or break/place a wire first",
                    NamedTextColor.GRAY));
            return 0;
        }
        src.getSender().sendMessage(Component.text(
                "  #   world         chunk(cx,cz)        block-bounds (x..x, z..z)            count    avg     max      total",
                NamedTextColor.GRAY));
        int i = 0;
        for (var hot : top) {
            i++;
            String dim = hot.dim();
            var cell = hot.cell();
            int wx = hot.cx() << 4, wz = hot.cz() << 4;
            src.getSender().sendMessage(Component.text(
                    String.format("  %2d. %-12s (%5d, %5d)  blocks (%6d..%6d, %6d..%6d)  %6d  %s  %s  %s",
                            i, abbrevDim(dim, 12),
                            hot.cx(), hot.cz(),
                            wx, wx + 15, wz, wz + 15,
                            cell.count(),
                            humanTime(cell.totalNs() / Math.max(1, cell.count())),  // avg
                            humanTime(cell.maxNs()),
                            humanTime(cell.totalNs())),
                    NamedTextColor.AQUA));
        }
        src.getSender().sendMessage(Component.text(
                "  count = number of recomputes; avg = mean time per recompute; max = worst single recompute; total = cumulative.",
                NamedTextColor.DARK_GRAY));
        return 1;
    }

    /** Human-readable nanos: "850ns", "12µs", "3.4ms", "1.2s". */
    private static String humanTime(long ns) {
        if (ns < 1_000)         return String.format("%5dns", ns);
        if (ns < 1_000_000)     return String.format("%5.1fµs", ns / 1_000.0);
        if (ns < 1_000_000_000) return String.format("%5.2fms", ns / 1_000_000.0);
        return String.format("%5.2fs ", ns / 1_000_000_000.0);
    }

    private static String abbrevDim(String dim, int max) {
        // "minecraft:overworld" → "overworld"; truncate if longer than max
        int slash = dim.indexOf(':');
        String s = slash >= 0 ? dim.substring(slash + 1) : dim;
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
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

    /**
     * Full diagnostic of the chunk under the player: current mode, components
     * count, timing stats since boot, and a recommended action. Block scan runs
     * on the chunk's owning region thread (Folia-safe); the report message is
     * sent back via the same scheduled task so the output order is deterministic.
     */
    private static int profile(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) {
            src.getSender().sendMessage(Component.text("must be run by an entity (uses your current chunk)", NamedTextColor.RED));
            return 0;
        }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        int cx = chunk.getX(), cz = chunk.getZ();
        var sender = src.getSender();

        // Snapshot what we can read from any thread first
        RedstoneMode mode = registry.modeOfChunk(dim, cx, cz);
        int totalNonVanilla = registry.trackedCount(dim);
        var cell = TIMING == null ? null : TIMING.get(dim.identifier().toString(), cx, cz);

        // Block scan must run on the region thread that owns this chunk
        Bukkit.getRegionScheduler().execute(pluginRef(), w, cx, cz, () -> {
            int dust = 0, repeaters = 0, comparators = 0, pistons = 0, stickies = 0,
                observers = 0, sources = 0, lamps = 0, torches = 0;
            int bx = cx << 4, bz = cz << 4;
            int yMin = w.getMinHeight(), yMax = w.getMaxHeight();
            for (int x = bx; x < bx + 16; x++)
                for (int z = bz; z < bz + 16; z++)
                    for (int y = yMin; y < yMax; y++) {
                        var t = w.getBlockAt(x, y, z).getType();
                        switch (t) {
                            case REDSTONE_WIRE -> dust++;
                            case REPEATER -> repeaters++;
                            case COMPARATOR -> comparators++;
                            case PISTON -> pistons++;
                            case STICKY_PISTON -> stickies++;
                            case OBSERVER -> observers++;
                            case REDSTONE_BLOCK, LEVER, STONE_BUTTON, OAK_BUTTON, BIRCH_BUTTON,
                                 SPRUCE_BUTTON, JUNGLE_BUTTON, ACACIA_BUTTON, DARK_OAK_BUTTON,
                                 MANGROVE_BUTTON, CHERRY_BUTTON, BAMBOO_BUTTON, CRIMSON_BUTTON,
                                 WARPED_BUTTON, PALE_OAK_BUTTON, POLISHED_BLACKSTONE_BUTTON -> sources++;
                            case REDSTONE_LAMP -> lamps++;
                            case REDSTONE_TORCH, REDSTONE_WALL_TORCH -> torches++;
                            default -> {}
                        }
                    }

            // Build and send the report (Adventure components are async-safe)
            sender.sendMessage(Component.text("═══ Profile @ " + abbrevDim(dim.identifier().toString(), 32)
                    + " chunk (" + cx + ", " + cz + ") ═══", NamedTextColor.GOLD));
            sender.sendMessage(Component.text(
                    "  block-bounds: (" + bx + ".." + (bx + 15) + ", " + bz + ".." + (bz + 15)
                            + ")    full Y range: " + yMin + ".." + yMax,
                    NamedTextColor.DARK_GRAY));
            sender.sendMessage(Component.text("  current mode:  ", NamedTextColor.GRAY)
                    .append(Component.text(mode.slug(), modeColor(mode)))
                    .append(Component.text("    (" + totalNonVanilla + " non-vanilla chunks in this dim)", NamedTextColor.DARK_GRAY)));

            sender.sendMessage(Component.text("  ── Components ──", NamedTextColor.AQUA));
            sender.sendMessage(Component.text(String.format(
                    "  dust=%d  repeaters=%d  comparators=%d  pistons=%d  sticky=%d  observers=%d",
                    dust, repeaters, comparators, pistons, stickies, observers), NamedTextColor.WHITE));
            sender.sendMessage(Component.text(String.format(
                    "  sources(lever/button/red-block)=%d  lamps=%d  torches=%d",
                    sources, lamps, torches), NamedTextColor.WHITE));

            sender.sendMessage(Component.text("  ── Timing (since plugin start / last /stats reset) ──", NamedTextColor.AQUA));
            if (cell == null || cell.count() == 0) {
                sender.sendMessage(Component.text(
                        "  no redstone updates recorded yet — toggle a lever, then /profile again",
                        NamedTextColor.GRAY));
            } else {
                long c = cell.count();
                long total = cell.totalNs();
                long avg = total / c;
                long worst = cell.maxNs();
                double tickPctAvg  = avg / 50_000_000.0 * 100.0;   // 1 tick = 50 ms
                double tickPctWorst = worst / 50_000_000.0 * 100.0;
                sender.sendMessage(Component.text(String.format(
                        "  updates=%d   total=%s   avg=%s/update   worst=%s/update",
                        c, humanTime(total), humanTime(avg), humanTime(worst)), NamedTextColor.WHITE));
                sender.sendMessage(Component.text(String.format(
                        "  → 1 typical update consumes ~%.2f%% of a server tick (50 ms budget); worst was %.1f%%.",
                        tickPctAvg, tickPctWorst), NamedTextColor.GRAY));
            }

            // Recommendation engine
            sender.sendMessage(Component.text("  ── Recommendation ──", NamedTextColor.AQUA));
            String reco = recommend(mode, dust, pistons + stickies, observers, cell);
            sender.sendMessage(Component.text("  " + reco, recoColor(reco)));
            sender.sendMessage(Component.text(
                    "  Try: /redstone-region set <mode>   then re-run /profile to compare.",
                    NamedTextColor.DARK_GRAY));
        });
        return 1;
    }

    private static String recommend(RedstoneMode current, int dust, int pistonsAll, int observers,
                                     net.ekaii.redstone.region.timing.ChunkTimingTable.Cell cell) {
        long avgUs = cell == null ? 0 : (cell.count() == 0 ? 0 : cell.totalNs() / cell.count() / 1_000);
        boolean hot     = avgUs >= 1_000;       // ≥ 1 ms per update
        boolean veryHot = avgUs >= 4_000;       // ≥ 4 ms — clearly slow on vanilla
        boolean dustHeavy = dust >= 32;
        boolean risky     = pistonsAll > 0 && (observers > 0 || dust >= 16);

        if (current == RedstoneMode.DISABLED) {
            return "ℹ This chunk is FROZEN — wire never updates. /set vanilla|alternate-current to thaw.";
        }
        if (current == RedstoneMode.VANILLA) {
            if (veryHot && !risky) return "✓ Hot chunk + dust-friendly. Switching to alternate-current is recommended (avg "
                    + humanTime(avgUs * 1_000L) + " > 4 ms).";
            if (veryHot && risky)  return "⚠ Hot AND risky (piston+observer/dust mix). Try eigencraft first — milder edge cases than AC.";
            if (hot && dustHeavy)  return "✓ Mid-hot dust-heavy. alternate-current likely 2-5× faster here.";
            if (hot && risky)      return "→ Mildly hot + risky. eigencraft is the safe perf upgrade for this build.";
            if (dust + pistonsAll + observers == 0) return "(no redstone here — no recommendation)";
            return "✓ Currently fast enough. Keep vanilla unless you measure a problem.";
        }
        if (current == RedstoneMode.ALTERNATE_CURRENT) {
            if (risky) return "⚠ AC chunk with piston+observer mix — verify your build still works (self-shape-update edge case).";
            if (avgUs > 0 && avgUs < 200) return "✓ AC is doing its job — avg " + humanTime(avgUs * 1_000L) + " is well below 1 ms/update.";
            return "✓ alternate-current active. Run /redstone-region check to confirm no risky patterns.";
        }
        if (current == RedstoneMode.EIGENCRAFT) {
            if (avgUs > 2_000) return "→ Still hot under eigencraft. alternate-current usually 2× faster but check edge cases first.";
            return "✓ eigencraft active. Good middle ground if AC was breaking things.";
        }
        return "(no recommendation)";
    }

    private static NamedTextColor modeColor(RedstoneMode m) {
        return switch (m) {
            case VANILLA           -> NamedTextColor.WHITE;
            case ALTERNATE_CURRENT -> NamedTextColor.GREEN;
            case EIGENCRAFT        -> NamedTextColor.AQUA;
            case DISABLED          -> NamedTextColor.RED;
        };
    }

    private static NamedTextColor recoColor(String reco) {
        if (reco.startsWith("⚠")) return NamedTextColor.YELLOW;
        if (reco.startsWith("✓")) return NamedTextColor.GREEN;
        if (reco.startsWith("→")) return NamedTextColor.AQUA;
        return NamedTextColor.GRAY;
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
        return applyArea(w, cx, cz, radius, mode, registry, null, "command-area");
    }

    private static int applyArea(World w, int cx, int cz, int radius, RedstoneMode mode, ChunkRegistry registry,
                                 org.bukkit.command.CommandSender actor, String reason) {
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                applyChunk(w, cx + dx, cz + dz, mode, registry, actor, reason);
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
