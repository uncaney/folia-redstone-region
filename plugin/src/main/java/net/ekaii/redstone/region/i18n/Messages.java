package net.ekaii.redstone.region.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Lightweight i18n: loads {@code lang/<code>.yml} from the plugin resources
 * (bundled) or from {@code plugins/folia-redstone-region/lang/<code>.yml}
 * (operator override) and serves Bukkit-color-formatted Adventure components.
 *
 * <p>Lookup order on {@link #reload}:
 * <ol>
 *   <li>{@code plugins/folia-redstone-region/lang/<code>.yml} — operator override</li>
 *   <li>{@code plugin.jar/lang/<code>.yml} — bundled default</li>
 *   <li>{@code plugin.jar/lang/en.yml} — final fallback</li>
 * </ol>
 *
 * <p>Placeholders use {@code {name}} syntax (literal). The {@link #tr} method
 * does a simple String.replace for each provided pair.
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static volatile Messages INSTANCE = new Messages(new HashMap<>(), "en");

    private final Map<String, String> entries;
    private final String lang;

    private Messages(Map<String, String> entries, String lang) {
        this.entries = entries;
        this.lang = lang;
    }

    public static Messages get() { return INSTANCE; }

    public String language() { return lang; }

    /**
     * Load the language identified by {@code code} (e.g. "en", "fr"). Falls back
     * to bundled default and then to "en" if the requested file is missing.
     */
    public static synchronized void reload(Plugin plugin, String code, Logger log) {
        if (code == null || code.isBlank()) code = "en";
        String c = code.trim().toLowerCase(Locale.ROOT);
        Map<String, String> map = loadLang(plugin, c, log);
        if (map.isEmpty() && !"en".equals(c)) {
            log.warning("language '" + c + "' could not be loaded — falling back to en");
            map = loadLang(plugin, "en", log);
        }
        INSTANCE = new Messages(map, c);
        log.info("language loaded: " + c + " (" + map.size() + " keys)");
    }

    private static Map<String, String> loadLang(Plugin plugin, String code, Logger log) {
        Map<String, String> out = new HashMap<>();
        // Override path
        java.nio.file.Path override = plugin.getDataFolder().toPath().resolve("lang").resolve(code + ".yml");
        if (java.nio.file.Files.isRegularFile(override)) {
            try (var r = java.nio.file.Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(r);
                flatten(y.getValues(true), "", out);
                return out;
            } catch (IOException e) {
                log.warning("could not read lang override " + override + ": " + e);
            }
        }
        // Bundled
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) return out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(r);
                flatten(y.getValues(true), "", out);
            }
        } catch (IOException e) {
            log.warning("could not read bundled lang/" + code + ".yml: " + e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> in, String prefix, Map<String, String> out) {
        for (var e : in.entrySet()) {
            String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof String s)            out.put(k, s);
            else if (v instanceof Map<?,?> m)     flatten((Map<String, Object>) m, k, out);
        }
    }

    /** Look up {@code key}, replace {placeholders}, and return as a legacy-color Adventure Component. */
    public Component tr(String key, Object... pairs) {
        String s = entries.getOrDefault(key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            String val  = String.valueOf(pairs[i + 1]);
            s = s.replace("{" + name + "}", val);
        }
        return LEGACY.deserialize(s);
    }

    /** Same as {@link #tr} but as a raw String (used when the consumer needs a String, e.g. signs). */
    public String trStr(String key, Object... pairs) {
        String s = entries.getOrDefault(key, key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + String.valueOf(pairs[i]) + "}", String.valueOf(pairs[i + 1]));
        }
        return s;
    }

    public boolean has(String key) { return entries.containsKey(key); }

    /**
     * Same as {@link #tr} but allows placeholder values to be {@link Component}s.
     * The translation string is split at each {@code {name}} placeholder; the
     * fragments are converted to legacy-color Components and stitched with the
     * provided Component (or text) values.
     */
    public Component trMixed(String key, Object... pairs) {
        String s = entries.getOrDefault(key, key);
        // First, replace plain-string placeholders ahead of time
        Map<String, Component> componentArgs = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            Object v = pairs[i + 1];
            if (v instanceof Component c) {
                componentArgs.put(name, c);
            } else {
                s = s.replace("{" + name + "}", String.valueOf(v));
            }
        }
        if (componentArgs.isEmpty()) return LEGACY.deserialize(s);
        // Split on each component placeholder, stitching components in
        Component out = Component.empty();
        int cursor = 0;
        while (cursor < s.length()) {
            int nextStart = s.length();
            String matchedKey = null;
            int matchedAt = -1;
            for (var e : componentArgs.entrySet()) {
                String token = "{" + e.getKey() + "}";
                int idx = s.indexOf(token, cursor);
                if (idx >= 0 && idx < nextStart) {
                    nextStart = idx;
                    matchedKey = e.getKey();
                    matchedAt = idx;
                }
            }
            if (matchedKey == null) {
                out = out.append(LEGACY.deserialize(s.substring(cursor)));
                break;
            }
            out = out.append(LEGACY.deserialize(s.substring(cursor, matchedAt)));
            out = out.append(componentArgs.get(matchedKey));
            cursor = matchedAt + ("{" + matchedKey + "}").length();
        }
        return out;
    }

    /**
     * Build a clickable component "(cx, cz)" with hover-tooltip "click to /tp"
     * and a ClickEvent that runs {@code /tp <world> <wx> 80 <wz>} where (wx,wz)
     * is the chunk's center in world coordinates.
     */
    public Component chunkLink(String world, int chunkX, int chunkZ) {
        String label = "(" + chunkX + ", " + chunkZ + ")";
        int wx = (chunkX << 4) + 8;
        int wz = (chunkZ << 4) + 8;
        String cmd = "/execute in minecraft:" + world + " run tp @s " + wx + " 80 " + wz;
        Component hover = tr("tp.hover");
        return Component.text(label)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(hover));
    }

    public Component chunkLink(int chunkX, int chunkZ) {
        // Without a world we just attach the hover; click defaults to current world.
        String label = "(" + chunkX + ", " + chunkZ + ")";
        int wx = (chunkX << 4) + 8;
        int wz = (chunkZ << 4) + 8;
        String cmd = "/tp @s " + wx + " 80 " + wz;
        Component hover = tr("tp.hover");
        return Component.text(label)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(hover));
    }
}
