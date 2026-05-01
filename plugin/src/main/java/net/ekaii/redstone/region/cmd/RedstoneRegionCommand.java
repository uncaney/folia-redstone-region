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
import net.ekaii.redstone.region.i18n.Messages;
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

/**
 * Brigadier tree for {@code /redstone-region}. All player-facing strings go
 * through {@link Messages} (i18n via {@code lang/<code>.yml}). Chunk coords
 * shown in chat are clickable {@link Messages#chunkLink} so operators can
 * teleport with a single click.
 */
public final class RedstoneRegionCommand {

    private static final String PERM = "redstone-region.admin";

    private RedstoneRegionCommand() {}

    private static Messages msg() { return Messages.get(); }

    public static LiteralArgumentBuilder<CommandSourceStack> root(ChunkRegistry registry) {
        return Commands.literal("redstone-region")
                .requires(s -> s.getSender().hasPermission(PERM))
                .executes(c -> help(c))
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
                                .executes(c -> selection(c, registry))))
                .then(Commands.literal("reload").executes(c -> reload(c)));
    }

    private static CompletableFuture<Suggestions> suggestModes(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        b.suggest("vanilla");
        b.suggest("alternate-current");
        b.suggest("eigencraft");
        b.suggest("disabled");
        return b.buildFuture();
    }

    /* -------------------------------------------------------------------- */
    /* Commands                                                             */
    /* -------------------------------------------------------------------- */

    private static int help(CommandContext<CommandSourceStack> ctx) {
        var s = ctx.getSource().getSender();
        s.sendMessage(msg().tr("help.header"));
        s.sendMessage(msg().tr("help.intro"));
        s.sendMessage(Component.empty());
        for (String k : new String[]{
                "help.cmd-info", "help.cmd-set", "help.cmd-fill", "help.cmd-clear",
                "help.cmd-list", "help.cmd-where", "help.cmd-map",
                "help.cmd-stats", "help.cmd-stats-reset", "help.cmd-check",
                "help.cmd-profile", "help.cmd-selection", "help.cmd-reload"}) {
            s.sendMessage(msg().tr(k));
        }
        s.sendMessage(Component.empty());
        s.sendMessage(msg().tr("help.modes-header"));
        s.sendMessage(msg().tr("help.mode-vanilla"));
        s.sendMessage(msg().tr("help.mode-ac"));
        s.sendMessage(msg().tr("help.mode-eigen"));
        s.sendMessage(msg().tr("help.mode-disabled"));
        s.sendMessage(Component.empty());
        s.sendMessage(msg().tr("help.edges-header"));
        s.sendMessage(msg().tr("help.edge-1"));
        s.sendMessage(msg().tr("help.edge-2"));
        s.sendMessage(msg().tr("help.edge-3"));
        s.sendMessage(Component.empty());
        s.sendMessage(msg().tr("help.advice"));
        s.sendMessage(Component.empty());
        s.sendMessage(msg().tr("help.example-header"));
        s.sendMessage(msg().tr("help.example-1"));
        s.sendMessage(msg().tr("help.example-2"));
        s.sendMessage(msg().tr("help.example-3"));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        RedstoneMode mode = registry.modeOfChunk(dim, chunk.getX(), chunk.getZ());
        Component link = msg().chunkLink(w.getName(), chunk.getX(), chunk.getZ());
        src.getSender().sendMessage(msg().trMixed("info.chunk-uses",
                "chunk", link, "dim", abbrevDim(dim.identifier().toString(), 32), "mode", mode.slug()));
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        String modeStr = StringArgumentType.getString(ctx, "mode");
        RedstoneMode mode = RedstoneMode.parse(modeStr);
        if (mode == null) { src.getSender().sendMessage(msg().tr("error.unknown-mode", "mode", modeStr)); return 0; }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        applyChunk(w, chunk.getX(), chunk.getZ(), mode, registry, src.getSender(), "command");
        Component link = msg().chunkLink(w.getName(), chunk.getX(), chunk.getZ());
        src.getSender().sendMessage(msg().trMixed("set.applied", "mode", mode.slug(), "chunk", link));
        return 1;
    }

    private static int fill(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        String modeStr = StringArgumentType.getString(ctx, "mode");
        RedstoneMode mode = RedstoneMode.parse(modeStr);
        if (mode == null) { src.getSender().sendMessage(msg().tr("error.unknown-mode", "mode", modeStr)); return 0; }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        int count = applyArea(w, chunk.getX(), chunk.getZ(), radius, mode, registry, src.getSender(), "command-fill");
        src.getSender().sendMessage(msg().tr("fill.applied", "mode", mode.slug(), "n", String.valueOf(count)));
        return count;
    }

    private static int clearOne(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        applyChunk(w, chunk.getX(), chunk.getZ(), RedstoneMode.VANILLA, registry, src.getSender(), "command-clear");
        Component link = msg().chunkLink(w.getName(), chunk.getX(), chunk.getZ());
        src.getSender().sendMessage(msg().trMixed("clear.applied-one", "chunk", link));
        return 1;
    }

    private static int clearArea(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        Chunk chunk = executor.getLocation().getChunk();
        int count = applyArea(executor.getWorld(), chunk.getX(), chunk.getZ(), radius, RedstoneMode.VANILLA, registry, src.getSender(), "command-clear-area");
        src.getSender().sendMessage(msg().tr("clear.applied-area", "n", String.valueOf(count)));
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        for (World w : Bukkit.getWorlds()) {
            ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
            int n = registry.trackedCount(dim);
            String key = n > 0 ? "list.line" : "list.line-empty";
            src.getSender().sendMessage(msg().tr(key, "dim", abbrevDim(dim.identifier().toString(), 32), "n", String.valueOf(n)));
        }
        return 1;
    }

    private static int where(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        long[] keys = registry.snapshotKeys(dim);
        var sender = src.getSender();
        sender.sendMessage(msg().tr("where.header", "dim", abbrevDim(dim.identifier().toString(), 32), "n", String.valueOf(keys.length)));
        if (keys.length == 0) { sender.sendMessage(msg().tr("where.empty")); return 1; }
        java.util.Arrays.sort(keys);
        int max = Math.min(keys.length, 50);
        for (int i = 0; i < max; i++) {
            int cx = net.ekaii.redstone.region.util.ChunkKey.unpackX(keys[i]);
            int cz = net.ekaii.redstone.region.util.ChunkKey.unpackZ(keys[i]);
            int wx = cx << 4, wz = cz << 4;
            Component link = msg().chunkLink(w.getName(), cx, cz);
            sender.sendMessage(msg().trMixed("where.line",
                    "chunk", link,
                    "wx1", wx, "wx2", wx + 15, "wz1", wz, "wz2", wz + 15));
        }
        if (keys.length > max) {
            sender.sendMessage(msg().tr("where.truncated", "n", String.valueOf(keys.length - max)));
        }
        return keys.length;
    }

    private static int map(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry, int radius) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        Chunk you = executor.getLocation().getChunk();
        int cx = you.getX(), cz = you.getZ();
        var sender = src.getSender();
        Component chunkLabel = Component.text("(" + cx + ", " + cz + ")");
        sender.sendMessage(msg().trMixed("map.header", "chunk", chunkLabel, "r", String.valueOf(radius)));
        sender.sendMessage(msg().tr("map.legend"));
        for (int dz = -radius; dz <= radius; dz++) {
            Component line = Component.empty();
            for (int dx = -radius; dx <= radius; dx++) {
                int qx = cx + dx, qz = cz + dz;
                boolean isYou = (dx == 0 && dz == 0);
                RedstoneMode m = registry.modeOfChunk(dim, qx, qz);
                if (isYou) {
                    line = line.append(Component.text("◉", NamedTextColor.YELLOW));
                } else {
                    line = line.append(switch (m) {
                        case ALTERNATE_CURRENT -> Component.text("█", NamedTextColor.GREEN);
                        case EIGENCRAFT        -> Component.text("█", NamedTextColor.AQUA);
                        case DISABLED          -> Component.text("█", NamedTextColor.RED);
                        case VANILLA           -> Component.text("·", NamedTextColor.DARK_GRAY);
                    });
                }
            }
            sender.sendMessage(line);
        }
        sender.sendMessage(msg().tr("map.footer", "blocks", String.valueOf(radius * 16)));
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx, int limit) {
        var src = ctx.getSource();
        if (TIMING == null) { src.getSender().sendMessage(msg().tr("error.timing-unavailable")); return 0; }
        var top = TIMING.top(limit);
        src.getSender().sendMessage(msg().tr("stats.header", "n", String.valueOf(top.size())));
        if (top.isEmpty()) { src.getSender().sendMessage(msg().tr("stats.no-activity")); return 0; }
        src.getSender().sendMessage(msg().tr("stats.column-header"));
        int i = 0;
        for (var hot : top) {
            i++;
            String dimStr = hot.dim();
            var cell = hot.cell();
            int wx = hot.cx() << 4, wz = hot.cz() << 4;
            String world = worldNameFromDim(dimStr);
            Component link = msg().chunkLink(world, hot.cx(), hot.cz());
            src.getSender().sendMessage(msg().trMixed("stats.row",
                    "i", String.valueOf(i),
                    "dim", abbrevDim(dimStr, 12),
                    "chunk", link,
                    "wx1", wx, "wx2", wx + 15, "wz1", wz, "wz2", wz + 15,
                    "count", cell.count(),
                    "avg", humanTime(cell.totalNs() / Math.max(1, cell.count())),
                    "max", humanTime(cell.maxNs()),
                    "total", humanTime(cell.totalNs())));
        }
        src.getSender().sendMessage(msg().tr("stats.legend"));
        return 1;
    }

    private static int statsReset(CommandContext<CommandSourceStack> ctx) {
        if (TIMING != null) TIMING.resetAll();
        ctx.getSource().getSender().sendMessage(msg().tr("stats.reset-ok"));
        return 1;
    }

    private static int check(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        Chunk c = executor.getLocation().getChunk();
        World w = executor.getWorld();
        var sender = src.getSender();
        Component link = msg().chunkLink(w.getName(), c.getX(), c.getZ());
        sender.sendMessage(msg().trMixed("check.header", "chunk", link));
        int pistons = 0, stickies = 0, observers = 0, dust = 0, repeaters = 0, comparators = 0;
        int bx = c.getX() << 4, bz = c.getZ() << 4;
        int yMin = w.getMinHeight(), yMax = w.getMaxHeight();
        for (int x = bx; x < bx + 16; x++)
            for (int z = bz; z < bz + 16; z++)
                for (int y = yMin; y < yMax; y++) {
                    var t = w.getBlockAt(x, y, z).getType();
                    switch (t) {
                        case PISTON:        pistons++;     break;
                        case STICKY_PISTON: stickies++;    break;
                        case OBSERVER:      observers++;   break;
                        case REDSTONE_WIRE: dust++;        break;
                        case REPEATER:      repeaters++;   break;
                        case COMPARATOR:    comparators++; break;
                        default: break;
                    }
                }
        sender.sendMessage(msg().tr("check.counts",
                "dust", dust, "rep", repeaters, "comp", comparators,
                "piston", pistons, "sticky", stickies, "obs", observers));
        boolean risky = (pistons + stickies > 0 && observers > 0)
                     || (pistons + stickies > 0 && dust > 8);
        if (risky) {
            sender.sendMessage(msg().tr("check.risky"));
            sender.sendMessage(msg().tr("check.risky-detail"));
            sender.sendMessage(msg().tr("check.risky-advice"));
        } else if (dust > 0) {
            sender.sendMessage(msg().tr("check.safe"));
        } else {
            sender.sendMessage(msg().tr("check.empty"));
        }
        return 1;
    }

    private static int profile(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (executor == null) { src.getSender().sendMessage(msg().tr("error.must-be-entity")); return 0; }
        Chunk chunk = executor.getLocation().getChunk();
        World w = executor.getWorld();
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        int cx = chunk.getX(), cz = chunk.getZ();
        var sender = src.getSender();
        RedstoneMode mode = registry.modeOfChunk(dim, cx, cz);
        int totalNonVanilla = registry.trackedCount(dim);
        var cell = TIMING == null ? null : TIMING.get(dim.identifier().toString(), cx, cz);

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

            Component link = msg().chunkLink(w.getName(), cx, cz);
            sender.sendMessage(msg().trMixed("profile.header",
                    "dim", abbrevDim(dim.identifier().toString(), 32), "chunk", link));
            sender.sendMessage(msg().tr("profile.bounds",
                    "wx1", bx, "wx2", bx + 15, "wz1", bz, "wz2", bz + 15,
                    "ymin", yMin, "ymax", yMax));
            Component modeLabel = Component.text(mode.slug(), modeColor(mode));
            sender.sendMessage(msg().trMixed("profile.mode-line",
                    "mode-colored", modeLabel, "total", totalNonVanilla));
            sender.sendMessage(msg().tr("profile.components-header"));
            sender.sendMessage(msg().tr("profile.components-1",
                    "dust", dust, "rep", repeaters, "comp", comparators,
                    "piston", pistons, "sticky", stickies, "obs", observers));
            sender.sendMessage(msg().tr("profile.components-2",
                    "sources", sources, "lamps", lamps, "torches", torches));
            sender.sendMessage(msg().tr("profile.timing-header"));
            if (cell == null || cell.count() == 0) {
                sender.sendMessage(msg().tr("profile.timing-empty"));
            } else {
                long c = cell.count();
                long total = cell.totalNs();
                long avg = total / c;
                long worst = cell.maxNs();
                double tickPctAvg  = avg / 50_000_000.0 * 100.0;
                double tickPctWorst = worst / 50_000_000.0 * 100.0;
                sender.sendMessage(msg().tr("profile.timing-row",
                        "count", c, "total", humanTime(total),
                        "avg", humanTime(avg), "worst", humanTime(worst)));
                sender.sendMessage(msg().tr("profile.timing-pct",
                        "pct-avg", String.format("%.2f", tickPctAvg),
                        "pct-worst", String.format("%.1f", tickPctWorst)));
            }
            sender.sendMessage(msg().tr("profile.reco-header"));
            sender.sendMessage(buildRecommendation(mode, dust, pistons + stickies, observers, cell));
            sender.sendMessage(msg().tr("profile.reco-hint"));
        });
        return 1;
    }

    private static Component buildRecommendation(RedstoneMode current, int dust, int pistonsAll, int observers,
                                                 net.ekaii.redstone.region.timing.ChunkTimingTable.Cell cell) {
        long avgUs = (cell == null || cell.count() == 0) ? 0 : cell.totalNs() / cell.count() / 1_000;
        boolean hot     = avgUs >= 1_000;
        boolean veryHot = avgUs >= 4_000;
        boolean dustHeavy = dust >= 32;
        boolean risky     = pistonsAll > 0 && (observers > 0 || dust >= 16);
        if (current == RedstoneMode.DISABLED) return msg().tr("reco.frozen");
        if (current == RedstoneMode.VANILLA) {
            if (veryHot && !risky) return msg().tr("reco.vanilla.hot-friendly", "avg", humanTime(avgUs * 1_000L));
            if (veryHot && risky)  return msg().tr("reco.vanilla.hot-risky");
            if (hot && dustHeavy)  return msg().tr("reco.vanilla.mid-dust");
            if (hot && risky)      return msg().tr("reco.vanilla.mid-risky");
            if (dust + pistonsAll + observers == 0) return msg().tr("reco.vanilla.no-redstone");
            return msg().tr("reco.vanilla.fast");
        }
        if (current == RedstoneMode.ALTERNATE_CURRENT) {
            if (risky) return msg().tr("reco.ac.risky");
            if (avgUs > 0 && avgUs < 200) return msg().tr("reco.ac.fast", "avg", humanTime(avgUs * 1_000L));
            return msg().tr("reco.ac.default");
        }
        if (current == RedstoneMode.EIGENCRAFT) {
            if (avgUs > 2_000) return msg().tr("reco.eigen.still-hot");
            return msg().tr("reco.eigen.default");
        }
        return Component.empty();
    }

    private static int selection(CommandContext<CommandSourceStack> ctx, ChunkRegistry registry) {
        var src = ctx.getSource();
        Entity executor = src.getExecutor();
        if (!(executor instanceof org.bukkit.entity.Player p)) {
            src.getSender().sendMessage(msg().tr("error.must-be-entity"));
            return 0;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")
                && !Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) {
            src.getSender().sendMessage(msg().tr("error.we-not-installed"));
            return 0;
        }
        String modeStr = StringArgumentType.getString(ctx, "mode");
        RedstoneMode mode = RedstoneMode.parse(modeStr);
        if (mode == null) { src.getSender().sendMessage(msg().tr("error.unknown-mode", "mode", modeStr)); return 0; }
        var result = net.ekaii.redstone.region.bridge.WorldEditBridge.collectSelectionChunks(p);
        if (!result.ok()) {
            // map status to translation key
            String key = switch (result.status()) {
                case NO_SELECTION         -> "error.we-no-selection";
                case INCOMPLETE_SELECTION -> "error.we-incomplete";
                case WRONG_WORLD          -> "error.we-wrong-world";
                default                   -> "error.we-error";
            };
            src.getSender().sendMessage(msg().tr(key, "detail", result.message(),
                    "we-world", "?", "your-world", p.getWorld().getName()));
            return 0;
        }
        for (long[] xz : result.chunks()) {
            applyChunk(p.getWorld(), (int) xz[0], (int) xz[1], mode, registry, p, "we-selection");
        }
        src.getSender().sendMessage(msg().tr("selection.applied", "mode", mode.slug(), "n", String.valueOf(result.chunks().size())));
        return result.chunks().size();
    }

    /**
     * Live config + language reload. Re-reads {@code config.yml}, updates the
     * Messages instance, refreshes the static config reference. Mode swaps and
     * chunk PDC are unaffected. NMS evaluator swap is left alone (would need a
     * full restart to truly hot-reload — Java's class-file-redefinition can't
     * undo the field swap once in place).
     */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        try {
            if (RELOAD_HOOK == null) {
                sender.sendMessage(msg().tr("reload.error", "detail", "reload hook not bound"));
                return 0;
            }
            String newLang = RELOAD_HOOK.run();
            sender.sendMessage(msg().tr("reload.ok", "lang", newLang));
            return 1;
        } catch (Throwable t) {
            sender.sendMessage(msg().tr("reload.error", "detail", t.getMessage() == null ? t.toString() : t.getMessage()));
            return 0;
        }
    }

    /* -------------------------------------------------------------------- */
    /* Helpers                                                              */
    /* -------------------------------------------------------------------- */

    /** Human-readable nanos: "850ns", "12µs", "3.4ms", "1.2s". */
    private static String humanTime(long ns) {
        if (ns < 1_000)         return String.format("%5dns", ns);
        if (ns < 1_000_000)     return String.format("%5.1fµs", ns / 1_000.0);
        if (ns < 1_000_000_000) return String.format("%5.2fms", ns / 1_000_000.0);
        return String.format("%5.2fs", ns / 1_000_000_000.0);
    }

    private static String abbrevDim(String dim, int max) {
        int slash = dim.indexOf(':');
        String s = slash >= 0 ? dim.substring(slash + 1) : dim;
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static String worldNameFromDim(String dim) {
        // Best effort: scan loaded worlds for a matching dimension key.
        for (World w : Bukkit.getWorlds()) {
            ResourceKey<Level> k = ((CraftWorld) w).getHandle().dimension();
            if (k.identifier().toString().equals(dim)) return w.getName();
        }
        // fallback: strip namespace
        int slash = dim.indexOf(':');
        return slash >= 0 ? dim.substring(slash + 1) : dim;
    }

    private static NamedTextColor modeColor(RedstoneMode m) {
        return switch (m) {
            case VANILLA           -> NamedTextColor.WHITE;
            case ALTERNATE_CURRENT -> NamedTextColor.GREEN;
            case EIGENCRAFT        -> NamedTextColor.AQUA;
            case DISABLED          -> NamedTextColor.RED;
        };
    }

    private static void applyChunk(World w, int x, int z, RedstoneMode mode, ChunkRegistry registry,
                                   org.bukkit.command.CommandSender actor, String reason) {
        ResourceKey<Level> dim = ((CraftWorld) w).getHandle().dimension();
        RedstoneMode prev = registry.modeOfChunk(dim, x, z);
        registry.setMode(dim, x, z, mode);
        Bukkit.getRegionScheduler().execute(pluginRef(), w, x, z, () -> {
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

    private static int applyArea(World w, int cx, int cz, int radius, RedstoneMode mode, ChunkRegistry registry,
                                 org.bukkit.command.CommandSender actor, String reason) {
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                applyChunk(w, cx + dx, cz + dz, mode, registry, actor, reason);
                n++;
            }
        return n;
    }

    /* -------------------------------------------------------------------- */
    /* Bound context                                                        */
    /* -------------------------------------------------------------------- */

    /** Functional reload hook returning the new language code. */
    public interface ReloadHook { String run(); }

    private static volatile org.bukkit.plugin.Plugin PLUGIN;
    private static volatile net.ekaii.redstone.region.audit.AuditLog AUDIT;
    private static volatile net.ekaii.redstone.region.timing.ChunkTimingTable TIMING;
    private static volatile net.ekaii.redstone.region.bridge.DiscordWebhook DISCORD;
    private static volatile net.ekaii.redstone.region.config.PluginConfig CFG;
    private static volatile net.ekaii.redstone.region.bridge.BlueMapBridge BLUE_MAP;
    private static volatile ReloadHook RELOAD_HOOK;

    public static void bindContext(org.bukkit.plugin.Plugin plugin,
                                   net.ekaii.redstone.region.audit.AuditLog audit,
                                   net.ekaii.redstone.region.timing.ChunkTimingTable timing,
                                   net.ekaii.redstone.region.bridge.DiscordWebhook discord,
                                   net.ekaii.redstone.region.config.PluginConfig cfg,
                                   net.ekaii.redstone.region.bridge.BlueMapBridge blueMap,
                                   ReloadHook reloadHook) {
        PLUGIN = plugin;
        AUDIT = audit;
        TIMING = timing;
        DISCORD = discord;
        CFG = cfg;
        BLUE_MAP = blueMap;
        RELOAD_HOOK = reloadHook;
    }

    public static void rebindContextLight(net.ekaii.redstone.region.audit.AuditLog audit,
                                          net.ekaii.redstone.region.bridge.DiscordWebhook discord,
                                          net.ekaii.redstone.region.config.PluginConfig cfg) {
        AUDIT = audit;
        DISCORD = discord;
        CFG = cfg;
    }

    private static org.bukkit.plugin.Plugin pluginRef() {
        var p = PLUGIN;
        if (p == null) throw new IllegalStateException("RedstoneRegionCommand not bound to plugin");
        return p;
    }
}
