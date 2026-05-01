package net.ekaii.redstone.region.timing;

import net.ekaii.redstone.region.config.RedstoneMode;

import java.util.concurrent.atomic.LongAdder;

/**
 * Controls when (and how often) {@link ChunkTimingTable#record} is called from
 * the redstone hot path. Three orthogonal levers, configurable in
 * {@code config.yml}:
 *
 * <ul>
 *   <li>{@link Mode#OFF} — no timing recorded; absolute zero overhead beyond
 *       a single {@code volatile} read of the policy. Auto-AC scanner refuses
 *       to start in this mode.</li>
 *   <li>{@link Mode#NON_VANILLA_ONLY} — record only when the chunk is in a
 *       non-vanilla mode. Auto-AC's "find hot vanilla chunks" cannot work in
 *       this mode (we won't measure them), so the scanner falls back to
 *       a no-op with a one-shot warn log.</li>
 *   <li>{@link Mode#SAMPLE} — record every {@code 1/sample-rate} updates only.
 *       Cuts {@code System.nanoTime()} cost by the same factor. The {@code
 *       count}/{@code totalNs} stored in {@link ChunkTimingTable} are sample
 *       counts; consumers that want "estimated total" multiply by sampleRate.</li>
 *   <li>{@link Mode#ALL} — record every update (default).</li>
 * </ul>
 *
 * <p>Sampling uses a per-thread counter modulo sampleRate. No global atomic
 * is touched in the hot path beyond what fastutil's {@code Long2ByteMap.get}
 * already does.
 */
public final class TimingPolicy {

    public enum Mode { ALL, SAMPLE, NON_VANILLA_ONLY, OFF }

    public static final TimingPolicy DEFAULT = new TimingPolicy(Mode.ALL, 1);

    private final Mode mode;
    private final int sampleRate;          // ≥ 1; 1 means "every call"
    private final ThreadLocal<long[]> sampleCounter = ThreadLocal.withInitial(() -> new long[1]);
    private final LongAdder skippedCount = new LongAdder();   // diagnostics

    public TimingPolicy(Mode mode, int sampleRate) {
        this.mode = (mode == null) ? Mode.ALL : mode;
        this.sampleRate = Math.max(1, sampleRate);
    }

    public static TimingPolicy parse(String modeStr, int sampleRate) {
        Mode m = Mode.ALL;
        if (modeStr != null) {
            switch (modeStr.trim().toLowerCase().replace('_', '-')) {
                case "off"               -> m = Mode.OFF;
                case "sample"            -> m = Mode.SAMPLE;
                case "non-vanilla-only",
                     "nonvanilla",
                     "marked-only"       -> m = Mode.NON_VANILLA_ONLY;
                case "all", ""           -> m = Mode.ALL;
                default                  -> m = Mode.ALL;
            }
        }
        return new TimingPolicy(m, sampleRate);
    }

    /** Hot-path predicate. Returns true if this call should be timed and recorded. */
    public boolean shouldRecord(RedstoneMode chunkMode) {
        switch (mode) {
            case OFF: return false;
            case NON_VANILLA_ONLY:
                if (chunkMode == RedstoneMode.VANILLA) {
                    skippedCount.increment();
                    return false;
                }
                return true;
            case SAMPLE: {
                long[] c = sampleCounter.get();
                long n = ++c[0];
                if (n % sampleRate != 0) {
                    skippedCount.increment();
                    return false;
                }
                return true;
            }
            case ALL:
            default:
                return true;
        }
    }

    public Mode mode()           { return mode; }
    public int sampleRate()      { return sampleRate; }
    public long skippedCount()   { return skippedCount.sum(); }

    public boolean isAuditableForAutoAc() {
        // Auto-AC needs to see VANILLA chunks to know if they got hot.
        return mode != Mode.OFF && mode != Mode.NON_VANILLA_ONLY;
    }

    @Override public String toString() {
        return mode == Mode.SAMPLE ? "SAMPLE(1/" + sampleRate + ")" : mode.name();
    }
}
