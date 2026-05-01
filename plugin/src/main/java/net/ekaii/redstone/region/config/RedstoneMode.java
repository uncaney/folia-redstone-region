package net.ekaii.redstone.region.config;

/**
 * Per-chunk redstone evaluator selection.
 *
 * <p>Wire format (PDC byte): {@link #id()} returns a stable byte. Existing
 * worlds with bytes {0,1} continue to load as VANILLA / ALTERNATE_CURRENT.
 * Bytes {2,3} are the new EIGENCRAFT / DISABLED — only present after this
 * version is installed.
 */
public enum RedstoneMode {
    VANILLA           (0),
    ALTERNATE_CURRENT (1),
    EIGENCRAFT        (2),
    DISABLED          (3);

    private final byte id;
    RedstoneMode(int id) { this.id = (byte) id; }
    public byte id() { return id; }

    private static final RedstoneMode[] BY_ID = { VANILLA, ALTERNATE_CURRENT, EIGENCRAFT, DISABLED };

    public static RedstoneMode fromId(byte id) {
        if (id < 0 || id >= BY_ID.length) return VANILLA;
        return BY_ID[id];
    }

    public static RedstoneMode parse(String s) {
        if (s == null) return null;
        String n = s.trim().toLowerCase().replace('_', '-');
        return switch (n) {
            case "vanilla", "v"                                          -> VANILLA;
            case "alternate-current", "alternatecurrent", "ac",
                 "alternate"                                             -> ALTERNATE_CURRENT;
            case "eigencraft", "eigen", "turbo", "ec"                    -> EIGENCRAFT;
            case "disabled", "off", "frozen", "none", "no"               -> DISABLED;
            default -> null;
        };
    }

    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
