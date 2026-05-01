package net.ekaii.redstone.region.bridge;

import net.ekaii.redstone.region.audit.AuditLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Async POST every flip event to a Discord webhook. Ratelimited to ~5 req/s
 * to stay within Discord webhook limits. Soft-fails: if the URL is bad or
 * Discord rejects, logs once and stops trying that URL.
 *
 * <p>Filter levels:
 * <ul>
 *   <li>{@code all} — every flip (default)</li>
 *   <li>{@code manual} — only command-initiated flips (skip auto-AC scanner)</li>
 *   <li>{@code audit} — only sign + auto-AC (skip command flips)</li>
 * </ul>
 */
public final class DiscordWebhook {

    private final boolean enabled;
    private final String url;
    private final String filter;
    private final Logger log;
    private final HttpClient http;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Thread worker;
    private volatile boolean shuttingDown;
    private volatile boolean disabled;   // flip if Discord starts rejecting

    public DiscordWebhook(boolean enabled, String url, String filter, Logger log) {
        this.enabled = enabled && url != null && !url.isBlank();
        this.url = url;
        this.filter = filter == null ? "all" : filter;
        this.log = log;
        this.http = this.enabled ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() : null;
        this.worker = new Thread(this::run, "redstone-region-discord");
        this.worker.setDaemon(true);
        if (this.enabled) this.worker.start();
    }

    public void send(AuditLog.Event ev) {
        if (!enabled || disabled) return;
        if (filter.equalsIgnoreCase("manual") && ev.source() != AuditLog.Source.COMMAND
                && ev.source() != AuditLog.Source.WORLDEDIT) return;
        if (filter.equalsIgnoreCase("audit") && ev.source() == AuditLog.Source.COMMAND) return;
        String payload = formatPayload(ev);
        queue.add(payload);
        synchronized (queue) { queue.notify(); }
    }

    private String formatPayload(AuditLog.Event ev) {
        String content = String.format("`%s` %s set chunk **(%d, %d)** in `%s` to **%s** (was %s)%s",
                ev.source().name().toLowerCase(),
                escape(ev.actor()),
                ev.chunkX(), ev.chunkZ(),
                escape(ev.dimension()),
                ev.next() == null ? "?" : ev.next().slug(),
                ev.prev() == null ? "?" : ev.prev().slug(),
                (ev.reason() == null || ev.reason().isEmpty()) ? "" : " — " + escape(ev.reason()));
        // Truncate to Discord max content length (2000); leave room for JSON envelope.
        if (content.length() > 1900) content = content.substring(0, 1900) + "…";
        return "{\"content\":\"" + escape(content) + "\"}";
    }

    private static String escape(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");
    }

    private void run() {
        while (!shuttingDown) {
            try {
                synchronized (queue) {
                    if (queue.isEmpty()) queue.wait(2_000);
                }
                drain();
                Thread.sleep(220);  // ~5 req/s upper bound
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.warning("discord worker error: " + t);
            }
        }
        drain();
    }

    private void drain() {
        if (queue.isEmpty() || !draining.compareAndSet(false, true)) return;
        try {
            String payload;
            while ((payload = queue.poll()) != null) {
                try {
                    HttpResponse<String> resp = http.send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .header("Content-Type", "application/json; charset=utf-8")
                                    .timeout(Duration.ofSeconds(5))
                                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() / 100 == 4) {
                        log.warning("discord webhook " + resp.statusCode() + ": " + resp.body() + "; disabling future sends");
                        disabled = true;
                        return;
                    } else if (resp.statusCode() == 429) {
                        // Ratelimited — sleep and re-queue
                        Thread.sleep(1000);
                        queue.add(payload);
                    }
                } catch (Throwable t) {
                    log.warning("discord webhook send failed: " + t);
                }
            }
        } finally {
            draining.set(false);
        }
    }

    public void close() {
        shuttingDown = true;
        synchronized (queue) { queue.notifyAll(); }
        try { worker.join(2_000); } catch (InterruptedException ignored) {}
    }
}
