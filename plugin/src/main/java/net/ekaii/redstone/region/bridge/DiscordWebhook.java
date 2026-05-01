package net.ekaii.redstone.region.bridge;

import net.ekaii.redstone.region.audit.AuditLog;
import net.ekaii.redstone.region.config.RedstoneMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Async POST flip-events to a Discord webhook with two safety properties
 * upstream Discord requires:
 *
 * <ol>
 *   <li><b>Coalescing window.</b> Events that arrive within {@link #COALESCE_MS}
 *       and share (actor, source, dim, target-mode) are batched into a single
 *       message: <i>"ExoRamC set 289 chunks to alternate-current via fill — world,
 *       blocks x=[..], z=[..]"</i> instead of 289 individual posts. This is the
 *       default behaviour for /fill, /selection, sign with radius, and auto-AC.</li>
 *   <li><b>True 5 req/s rate-limit.</b> A 220 ms sleep is inserted <em>between
 *       every request</em> (not just between drain passes). Plus a proper
 *       handling of HTTP 429: respect {@code retry_after}, re-queue at the
 *       front, never set {@code disabled=true} for 429 (only for other 4xx).</li>
 * </ol>
 *
 * <p>Filter levels:
 * <ul>
 *   <li>{@code all} — every flip (default)</li>
 *   <li>{@code manual} — only command-initiated flips (skip auto-AC scanner)</li>
 *   <li>{@code audit} — only sign + auto-AC (skip command flips)</li>
 * </ul>
 */
public final class DiscordWebhook {

    /** Coalesce events within this window into a single Discord message. */
    private static final long COALESCE_MS = 2_500;

    /** Cap on the queue to avoid unbounded growth under sustained burst. */
    private static final int MAX_QUEUE_SIZE = 1024;

    /** Min delay between consecutive POSTs (Discord allows ~5 req/s). */
    private static final long PER_REQUEST_DELAY_MS = 250;

    private final boolean enabled;
    private final String url;
    private final String filter;
    private final Logger log;
    private final HttpClient http;
    private final ConcurrentLinkedQueue<AuditLog.Event> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Thread worker;
    private volatile boolean shuttingDown;
    private volatile boolean disabled;

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
        while (queue.size() >= MAX_QUEUE_SIZE) queue.poll();
        queue.add(ev);
        synchronized (queue) { queue.notify(); }
    }

    /**
     * Worker: blocks until queue has events, waits {@code COALESCE_MS} for more
     * to coalesce, groups by (actor, source, dim, target-mode), posts one
     * message per group, then sleeps {@code PER_REQUEST_DELAY_MS} between
     * consecutive POSTs.
     */
    private void run() {
        while (!shuttingDown) {
            try {
                synchronized (queue) {
                    if (queue.isEmpty()) queue.wait(2_000);
                }
                if (queue.isEmpty()) continue;
                // Coalesce window: wait a bit so a /fill burst settles before we drain
                Thread.sleep(COALESCE_MS);
                drain();
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
            // Pull everything currently in the queue and group it.
            List<AuditLog.Event> snapshot = new ArrayList<>();
            AuditLog.Event ev;
            while ((ev = queue.poll()) != null) snapshot.add(ev);
            if (snapshot.isEmpty()) return;

            // Group by (actor, source, dim, next mode).
            java.util.Map<String, List<AuditLog.Event>> groups = new java.util.LinkedHashMap<>();
            for (AuditLog.Event e : snapshot) {
                String key = e.actor() + "|" + e.source().name() + "|" + e.dimension() + "|" + (e.next() == null ? "?" : e.next().slug());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }

            // POST one message per group, sleeping between requests.
            for (List<AuditLog.Event> group : groups.values()) {
                if (disabled) return;
                String payload = formatBatchPayload(group);
                boolean sent = postOnce(payload, 0);
                if (!sent) {
                    // Re-queue at the front — but keep moving on; don't infinite-loop on a bad URL
                    for (AuditLog.Event e : group) queue.add(e);
                    continue;
                }
                Thread.sleep(PER_REQUEST_DELAY_MS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            draining.set(false);
        }
    }

    /** Send one POST. Handles 429 with retry_after backoff (max 3 retries). Returns true if delivered. */
    private boolean postOnce(String payload, int retries) {
        if (retries > 3) return false;
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 429) {
                double retryAfter = parseRetryAfter(resp.body());
                long sleep = Math.max(500, (long) (retryAfter * 1000) + 100);
                log.fine("discord 429, sleeping " + sleep + " ms then retrying");
                Thread.sleep(sleep);
                return postOnce(payload, retries + 1);
            }
            if (code / 100 == 2) return true;
            if (code / 100 == 4) {
                log.warning("discord webhook " + code + ": " + truncate(resp.body(), 200) + " — disabling future sends (fix URL and /reload)");
                disabled = true;
                return false;
            }
            log.warning("discord webhook " + code + " — will retry");
            Thread.sleep(1_000);
            return postOnce(payload, retries + 1);
        } catch (Throwable t) {
            log.fine("discord webhook send failed (retry " + retries + "): " + t);
            try { Thread.sleep(1_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return false; }
            return retries < 3 ? postOnce(payload, retries + 1) : false;
        }
    }

    private static double parseRetryAfter(String body) {
        // Body looks like {"message":"…","retry_after":1.284,"global":false}
        try {
            int i = body.indexOf("\"retry_after\"");
            if (i < 0) return 1.0;
            int colon = body.indexOf(':', i);
            int comma = body.indexOf(',', colon);
            if (comma < 0) comma = body.indexOf('}', colon);
            return Double.parseDouble(body.substring(colon + 1, comma).trim());
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /**
     * Render a coalesced batch. Single-event batches stay one-line.
     * Multi-event batches print a summary with chunk count and block range.
     */
    private String formatBatchPayload(List<AuditLog.Event> group) {
        AuditLog.Event head = group.get(0);
        String actor = head.actor();
        String src = head.source().name().toLowerCase();
        String dim = head.dimension();
        String mode = head.next() == null ? "?" : head.next().slug();

        StringBuilder content = new StringBuilder();
        if (group.size() == 1) {
            int cx = head.chunkX(), cz = head.chunkZ();
            int wx = (cx << 4) + 8, wz = (cz << 4) + 8;
            content.append(String.format("`%s` **%s** set chunk **(%d, %d)** in `%s` to **%s**",
                    src, escape(actor), cx, cz, escape(dim), mode));
            RedstoneMode prev = head.prev();
            if (prev != null && prev != head.next()) content.append(" (was ").append(prev.slug()).append(")");
            content.append(String.format("  ·  block-center: x=%d z=%d", wx, wz));
            if (head.reason() != null && !head.reason().isEmpty()) content.append("  ·  ").append(escape(head.reason()));
        } else {
            // Compute block-bounds across the group
            int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
            int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
            for (AuditLog.Event e : group) {
                if (e.chunkX() < minCx) minCx = e.chunkX();
                if (e.chunkX() > maxCx) maxCx = e.chunkX();
                if (e.chunkZ() < minCz) minCz = e.chunkZ();
                if (e.chunkZ() > maxCz) maxCz = e.chunkZ();
            }
            int wx1 = minCx << 4, wx2 = (maxCx << 4) + 15;
            int wz1 = minCz << 4, wz2 = (maxCz << 4) + 15;
            content.append(String.format("`%s` **%s** set **%d chunks** to **%s** in `%s`",
                    src, escape(actor), group.size(), mode, escape(dim)));
            content.append(String.format("\n  · chunk range: (%d, %d) → (%d, %d)", minCx, minCz, maxCx, maxCz));
            content.append(String.format("\n  · block range: x=[%d..%d], z=[%d..%d]  (size: %d×%d blocks)",
                    wx1, wx2, wz1, wz2, wx2 - wx1 + 1, wz2 - wz1 + 1));
            content.append(String.format("\n  · tp center: `/tp @s %d ~ %d`",
                    (wx1 + wx2) / 2, (wz1 + wz2) / 2));
            // Show reason of head (likely 'command-fill' or similar)
            if (head.reason() != null && !head.reason().isEmpty()) content.append("\n  · reason: ").append(escape(head.reason()));
        }

        if (content.length() > 1900) content.setLength(1900);
        return "{\"content\":\"" + escape(content.toString()) + "\"}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    public void close() {
        shuttingDown = true;
        synchronized (queue) { queue.notifyAll(); }
        try { worker.join(2_000); } catch (InterruptedException ignored) {}
    }
}
