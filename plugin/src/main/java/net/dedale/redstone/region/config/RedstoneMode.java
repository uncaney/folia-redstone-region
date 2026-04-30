package net.dedale.redstone.region.config;

public enum RedstoneMode {
    VANILLA(0),
    ALTERNATE_CURRENT(1);

    private final byte id;
    RedstoneMode(int id) { this.id = (byte) id; }
    public byte id() { return id; }

    private static final RedstoneMode[] BY_ID = { VANILLA, ALTERNATE_CURRENT };

    public static RedstoneMode fromId(byte id) {
        if (id < 0 || id >= BY_ID.length) return VANILLA;
        return BY_ID[id];
    }

    public static RedstoneMode parse(String s) {
        if (s == null) return null;
        String n = s.trim().toLowerCase().replace('_', '-');
        return switch (n) {
            case "vanilla", "v" -> VANILLA;
            case "alternate-current", "alternatecurrent", "ac", "alternate" -> ALTERNATE_CURRENT;
            default -> null;
        };
    }

    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
