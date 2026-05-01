package net.ekaii.redstone.region;

import net.ekaii.redstone.region.audit.AuditLog;
import net.ekaii.redstone.region.auto.AutoAcScanner;
import net.ekaii.redstone.region.bridge.BlueMapBridge;
import net.ekaii.redstone.region.bridge.DiscordWebhook;
import net.ekaii.redstone.region.bridge.PlaceholderApiBridge;
import net.ekaii.redstone.region.cmd.RedstoneRegionCommand;
import net.ekaii.redstone.region.config.ChunkRegistry;
import net.ekaii.redstone.region.config.PluginConfig;
import net.ekaii.redstone.region.listeners.ChunkSyncListener;
import net.ekaii.redstone.region.listeners.SignOptInListener;
import net.ekaii.redstone.region.nms.DispatchingEvaluator;
import net.ekaii.redstone.region.nms.EvaluatorSwap;
import net.ekaii.redstone.region.nms.PaperConfigForcer;
import net.ekaii.redstone.region.timing.ChunkTimingTable;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public final class PluginMain extends JavaPlugin {

    private PluginConfig config;
    private AuditLog auditLog;
    private ChunkTimingTable timing;
    private AutoAcScanner autoAcScanner;
    private DiscordWebhook discord;
    private BlueMapBridge blueMap;

    @Override
    public void onEnable() {
        Logger log = getLogger();
        this.config = PluginConfig.load(this);

        // 1. Force Paper's redstone-impl to VANILLA so our evaluator is the only one called.
        PaperConfigForcer forcer = new PaperConfigForcer(log);
        forcer.forceAll();
        Bukkit.getPluginManager().registerEvents(forcer, this);

        // 2. Swap RedStoneWireBlock.evaluator for our 4-mode dispatcher.
        try {
            EvaluatorSwap.install(ChunkRegistry.get(), log);
        } catch (ReflectiveOperationException e) {
            log.severe("could not install dispatching evaluator: " + e);
        }

        // 3. Audit log + timing table.
        this.auditLog = new AuditLog(this, config.auditEnabled,
                getDataFolder().toPath().resolve(config.auditPath), config.auditRotateKeep);
        this.timing = new ChunkTimingTable();
        DispatchingEvaluator de = EvaluatorSwap.installedDispatcher();
        if (de != null) de.setTimingTable(timing);

        // 4. Discord webhook (no-op if disabled or url empty).
        this.discord = new DiscordWebhook(config.discordEnabled, config.discordWebhookUrl, config.discordFilter, log);

        // 5. BlueMap bridge (soft-dep) — only instantiated if BlueMap classes are loadable
        // (avoids NoClassDefFoundError for the bridge's BlueMapAPI-typed fields when absent).
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            try {
                this.blueMap = new BlueMapBridge(this);
                blueMap.install();
            } catch (Throwable t) {
                log.warning("BlueMap bridge init failed: " + t);
                this.blueMap = null;
            }
        } else {
            log.info("BlueMap not installed — overlay disabled");
        }

        // 6. Chunk PDC sync — also publishes to BlueMap on load.
        Bukkit.getPluginManager().registerEvents(new ChunkSyncListener(ChunkRegistry.get(), blueMap), this);

        // 7. Sign opt-in listener (config-gated).
        if (config.signEnabled) {
            Bukkit.getPluginManager().registerEvents(
                    new SignOptInListener(this, ChunkRegistry.get(), auditLog, discord, config), this);
        }

        // 8. Auto-AC scanner (config-gated).
        if (config.autoAcEnabled) {
            this.autoAcScanner = new AutoAcScanner(this, ChunkRegistry.get(), timing, auditLog, discord, config);
            this.autoAcScanner.start();
        }

        // 9. PlaceholderAPI bridge (soft-dep).
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new PlaceholderApiBridge(this, ChunkRegistry.get()).register();
                log.info("PlaceholderAPI bridge registered");
            } catch (Throwable t) {
                log.warning("PlaceholderAPI present but bridge failed to register: " + t);
            }
        }

        // 10. Bind plugin reference + integrations for region-scheduled PDC writes from commands.
        RedstoneRegionCommand.bindContext(this, auditLog, timing, discord, config, blueMap);

        log.info("folia-redstone-region " + getPluginMeta().getVersion() + " ready"
                + (config.signEnabled ? " (sign-opt-in radius<=" + config.signMaxRadius + ")" : "")
                + (config.autoAcEnabled ? " (auto-ac threshold=" + config.autoAcMsThreshold + "ms)" : ""));
    }

    @Override
    public void onDisable() {
        Logger log = getLogger();
        if (autoAcScanner != null) autoAcScanner.stop();
        if (blueMap != null) blueMap.uninstall();
        if (discord != null) discord.close();
        if (auditLog != null) auditLog.close();
        try {
            EvaluatorSwap.uninstall(log);
        } catch (ReflectiveOperationException e) {
            log.warning("could not uninstall evaluator: " + e);
        }
    }

    public PluginConfig pluginConfig() { return config; }
    public AuditLog auditLog()         { return auditLog; }
    public ChunkTimingTable timing()    { return timing; }
}
