package net.ekaii.redstone.test.contraptions;

import org.bukkit.Location;

/**
 * Self-contained redstone test piece. Implementations must be deterministic:
 * given the same {@link Location origin}, {@link #build} produces the same
 * blocks, {@link #setInput} accepts the same boolean-on/off input, and
 * {@link #sampleOutput} reads the same state.
 */
public interface Contraption {
    /** Short name used in test reports (lowercase, kebab). */
    String id();

    /** Bounding box X-extent (cells). */
    int sizeX();
    /** Bounding box Z-extent (cells). */
    int sizeZ();
    /** Bounding box Y-extent (cells). At least 1. */
    default int sizeY() { return 3; }

    /** Build the contraption at the given origin (origin = (-x, base, -z) corner). */
    void build(Location origin);

    /** Drive the contraption's input. */
    void setInput(Location origin, boolean on);

    /**
     * Sample the contraption's output state. Return value is contraption-specific
     * (e.g. lamp on/off as 0/1, or dust power-level 0..15). The parity check
     * compares samples for equality byte-for-byte.
     */
    int sampleOutput(Location origin);
}
