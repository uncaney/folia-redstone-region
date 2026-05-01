package net.ekaii.redstone.region.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/** Strongly-typed view over the plugin's config.yml. Loaded once at enable. */
public final class PluginConfig {

    public final String language;
    public final RedstoneMode defaultMode;
    public final boolean signEnabled;
    public final int signMaxRadius;
    public final String signPermission;

    public final boolean auditEnabled;
    public final String auditPath;
    public final int auditRotateKeep;

    public final boolean autoAcEnabled;
    public final long autoAcScanIntervalTicks;
    public final double autoAcMsThreshold;
    public final int autoAcMinSamples;
    public final boolean autoAcAutoRevert;

    public final String acDefaultUpdateOrder;

    public final boolean discordEnabled;
    public final String discordWebhookUrl;
    public final String discordFilter;

    private PluginConfig(FileConfiguration c) {
        this.language = c.getString("language", "en");
        this.defaultMode = orDefault(RedstoneMode.parse(c.getString("default-mode", "vanilla")), RedstoneMode.VANILLA);

        this.signEnabled       = c.getBoolean("sign.enabled", true);
        this.signMaxRadius     = c.getInt("sign.max-radius", 4);
        this.signPermission    = c.getString("sign.permission", "redstone-region.sign");

        this.auditEnabled      = c.getBoolean("audit.enabled", true);
        this.auditPath         = c.getString("audit.path", "audit.jsonl");
        this.auditRotateKeep   = c.getInt("audit.rotate-keep", 14);

        this.autoAcEnabled         = c.getBoolean("auto-ac.enabled", false);
        this.autoAcScanIntervalTicks = c.getLong("auto-ac.scan-interval-ticks", 200);
        this.autoAcMsThreshold     = c.getDouble("auto-ac.ms-per-update-threshold", 4.0);
        this.autoAcMinSamples      = c.getInt("auto-ac.min-samples", 50);
        this.autoAcAutoRevert      = c.getBoolean("auto-ac.auto-revert", false);

        this.acDefaultUpdateOrder = c.getString("alternate-current.default-update-order", "horizontal-first-outward");

        this.discordEnabled    = c.getBoolean("discord.enabled", false);
        this.discordWebhookUrl = c.getString("discord.webhook-url", "");
        this.discordFilter     = c.getString("discord.filter", "all");
    }

    public static PluginConfig load(Plugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        return new PluginConfig(plugin.getConfig());
    }

    private static <T> T orDefault(T value, T def) { return value != null ? value : def; }
}
