package net.ekaii.redstone.region.audit;

import net.ekaii.redstone.region.config.RedstoneMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Append-only JSONL audit log of every mode flip. One line per event.
 *
 * <p>Writes go through a queue and are flushed on a single dedicated thread
 * (no I/O on Folia region threads). Daily roll: filename suffix is the date
 * {@code audit.YYYY-MM-DD.jsonl}.
 */
public final class AuditLog {

    public enum Source { COMMAND, SIGN, AUTO_AC, WORLDEDIT, BLUEMAP, OTHER }

    public record Event(
            long epochMs,
            Source source,
            String actor,
            UUID actorUuid,
            String dimension,
            int chunkX,
            int chunkZ,
            RedstoneMode prev,
            RedstoneMode next,
            String reason
    ) {
        public String toJsonl() {
            // Hand-rolled JSON to avoid pulling Gson in the runtime classpath.
            StringBuilder sb = new StringBuilder(192);
            sb.append('{');
            field(sb, "ts", epochMs); sb.append(',');
            field(sb, "src", source.name()); sb.append(',');
            field(sb, "actor", actor); sb.append(',');
            field(sb, "actorUuid", actorUuid == null ? "" : actorUuid.toString()); sb.append(',');
            field(sb, "dim", dimension); sb.append(',');
            field(sb, "cx", chunkX); sb.append(',');
            field(sb, "cz", chunkZ); sb.append(',');
            field(sb, "prev", prev == null ? "" : prev.slug()); sb.append(',');
            field(sb, "next", next == null ? "" : next.slug()); sb.append(',');
            field(sb, "reason", reason == null ? "" : reason);
            sb.append('}');
            return sb.toString();
        }
        private static void field(StringBuilder sb, String k, Object v) {
            sb.append('"').append(k).append("\":");
            if (v instanceof Number) sb.append(v);
            else sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
        private static String escape(String s) {
            return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");
        }
    }

    private final Plugin plugin;
    private final Path baseDir;
    private final boolean enabled;
    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final Thread flushThread;
    private final int rotateKeep;
    private volatile boolean shuttingDown;

    public AuditLog(Plugin plugin, boolean enabled, Path file, int rotateKeep) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.rotateKeep = rotateKeep;
        this.baseDir = plugin.getDataFolder().toPath();
        if (enabled) {
            try { Files.createDirectories(baseDir); }
            catch (IOException e) { plugin.getLogger().warning("audit: cannot create dir: " + e); }
        }
        this.flushThread = new Thread(this::flushLoop, "redstone-region-audit");
        this.flushThread.setDaemon(true);
        if (enabled) this.flushThread.start();
    }

    public void record(Event e) {
        if (!enabled) return;
        queue.add(e);
        // Wake up flusher
        synchronized (queue) { queue.notify(); }
    }

    /** Convenience: build an event from a Bukkit command sender. */
    public Event makeEvent(Source src, CommandSender sender,
                            String dim, int cx, int cz,
                            RedstoneMode prev, RedstoneMode next,
                            String reason) {
        String actor = sender == null ? "system" : sender.getName();
        UUID uuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        return new Event(System.currentTimeMillis(), src, actor, uuid, dim, cx, cz, prev, next, reason);
    }

    private void flushLoop() {
        while (!shuttingDown) {
            try {
                synchronized (queue) {
                    if (queue.isEmpty()) queue.wait(2_000);
                }
                drain();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                plugin.getLogger().warning("audit flush error: " + t);
            }
        }
        drain();
    }

    private void drain() {
        if (queue.isEmpty() || !flushing.compareAndSet(false, true)) return;
        try {
            Path file = currentFile();
            try (BufferedWriter w = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                Event e;
                while ((e = queue.poll()) != null) {
                    w.write(e.toJsonl());
                    w.newLine();
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("audit write failed: " + e);
        } finally {
            flushing.set(false);
        }
    }

    public Path currentFile() {
        return baseDir.resolve("audit." + LocalDate.now() + ".jsonl");
    }

    public void close() {
        shuttingDown = true;
        synchronized (queue) { queue.notifyAll(); }
        try { flushThread.join(2_000); } catch (InterruptedException ignored) {}
        drain();
    }

    public boolean enabled() { return enabled; }
}
