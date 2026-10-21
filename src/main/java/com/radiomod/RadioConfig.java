package com.radiomod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.radiomod.radio.RadioStation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple JSON config for RadioMod (no extra dependency).
 *
 * <p>Files (created on first run):
 * <ul>
 *   <li>{@code config/radiomod.json} — general settings
 *       (max stations shown, fetch limits, custom-station permissions).</li>
 *   <li>{@code config/radiomod_custom_stations.json} — user-defined stations
 *       (issues #2 / #6). Same format as ETS2 {@code live_streams.sii} entries,
 *       but as JSON: {@code [{"id","name","url"}]}.</li>
 * </ul>
 */
public final class RadioConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String MAIN_FILE = "radiomod.json";
    private static final String CUSTOM_FILE = "radiomod_custom_stations.json";

    // Defaults (also address issue #8: more stations by default)
    public static volatile int maxStations = 25;
    public static volatile int fetchLimit = 100;
    public static volatile boolean allowCustomStations = true;
    public static volatile boolean customRequireOp = false;

    private static volatile List<RadioStation> customCache = null;
    private static volatile long customFileMtime = 0L;

    private RadioConfig() {
    }

    public static Path configDir() {
        try {
            return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        } catch (Throwable t) {
            return Path.of("config");
        }
    }

    /** Load (or create) both config files. Safe to call multiple times. */
    public static synchronized void load() {
        Path dir = configDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("RadioMod: cannot create config dir: {}", e.getMessage());
            return;
        }
        loadMain(dir.resolve(MAIN_FILE));
        loadCustom(dir.resolve(CUSTOM_FILE), true);
    }

    private static void loadMain(Path file) {
        if (!Files.exists(file)) {
            saveMain(file);
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject o = GSON.fromJson(r, JsonObject.class);
            if (o == null) {
                saveMain(file);
                return;
            }
            maxStations = clampInt(getInt(o, "maxStations", maxStations), 5, 60);
            fetchLimit = clampInt(getInt(o, "fetchLimit", fetchLimit), 20, 200);
            allowCustomStations = getBool(o, "allowCustomStations", allowCustomStations);
            customRequireOp = getBool(o, "customRequireOp", customRequireOp);
        } catch (Exception e) {
            LOGGER.warn("RadioMod: failed to read {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private static void saveMain(Path file) {
        JsonObject o = new JsonObject();
        o.addProperty("_comment", "RadioMod settings. maxStations: stations shown per country (5-60). fetchLimit: API rows fetched before dedup (20-200). allowCustomStations: enable Custom tab. customRequireOp: only OPs may broadcast custom URLs on servers.");
        o.addProperty("maxStations", maxStations);
        o.addProperty("fetchLimit", fetchLimit);
        o.addProperty("allowCustomStations", allowCustomStations);
        o.addProperty("customRequireOp", customRequireOp);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(o, w);
        } catch (Exception e) {
            LOGGER.warn("RadioMod: failed to write {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private static void loadCustom(Path file, boolean createDefault) {
        if (!Files.exists(file)) {
            if (createDefault) {
                JsonArray arr = new JsonArray();
                arr.add(customEntry("custom_example1", "My Favourite Station",
                        "https://example.com/stream.mp3"));
                arr.add(customEntry("custom_example2", "Another Station (AAC+ also works)",
                        "https://example.com/stream.aac"));
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(arr, w);
                } catch (Exception e) {
                    LOGGER.warn("RadioMod: failed to write {}: {}", file.getFileName(), e.getMessage());
                }
                customCache = Collections.emptyList();
                return;
            }
            customCache = Collections.emptyList();
            return;
        }
        try {
            customFileMtime = Files.getLastModifiedTime(file).toMillis();
            List<RadioStation> list = new ArrayList<>();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement root = GSON.fromJson(r, JsonElement.class);
                JsonArray arr = null;
                if (root != null && root.isJsonArray()) {
                    arr = root.getAsJsonArray();
                } else if (root != null && root.isJsonObject()
                        && root.getAsJsonObject().has("stations")
                        && root.getAsJsonObject().get("stations").isJsonArray()) {
                    arr = root.getAsJsonObject().getAsJsonArray("stations");
                }
                if (arr != null) {
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        String url = getStr(o, "url", "").trim();
                        String name = getStr(o, "name", "").trim();
                        String id = getStr(o, "id", "").trim();
                        if (url.isEmpty() || name.isEmpty()) continue;
                        if (!isUrlAllowed(url)) {
                            LOGGER.warn("RadioMod: ignoring custom station with bad URL: {}", url);
                            continue;
                        }
                        if (id.isEmpty()) id = "custom_" + Math.abs(url.hashCode());
                        if (name.length() > 40) name = name.substring(0, 38) + "..";
                        list.add(new RadioStation(id, name, url));
                        if (list.size() >= 60) break;
                    }
                }
            }
            // Drop placeholder examples pointing at example.com
            list.removeIf(s -> s.getStreamUrl().contains("example.com"));
            customCache = Collections.unmodifiableList(list);
            LOGGER.info("RadioMod: loaded {} custom station(s)", customCache.size());
        } catch (Exception e) {
            LOGGER.warn("RadioMod: failed to read {}: {}", file.getFileName(), e.getMessage());
            if (customCache == null) customCache = Collections.emptyList();
        }
    }

    private static JsonObject customEntry(String id, String name, String url) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("url", url);
        return o;
    }

    /** Custom stations from {@code radiomod_custom_stations.json} (never null). Reloads if file changed. */
    public static List<RadioStation> getCustomStations() {
        if (customCache == null) load();
        try {
            Path file = configDir().resolve(CUSTOM_FILE);
            if (Files.exists(file)
                    && Files.getLastModifiedTime(file).toMillis() != customFileMtime) {
                synchronized (RadioConfig.class) {
                    loadCustom(file, false);
                }
            }
        } catch (Exception ignored) {
        }
        List<RadioStation> c = customCache;
        return c != null ? c : Collections.emptyList();
    }

    /** Only http(s) stream URLs are allowed (blocks file://, javascript:, private hosts are still possible — server ops can disable customs). */
    public static boolean isUrlAllowed(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty() || u.length() > 512 || u.contains(" ") || u.contains("\n")) return false;
        String lower = u.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false;
        // Reject obvious placeholders
        if (lower.contains("example.com")) return false;
        try {
            java.net.URI uri = java.net.URI.create(u);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return false;
            // Block localhost to avoid SSRF-style abuse on servers (singleplayer still fine via 127.0.0.1? keep blocked — use LAN IP instead)
            String h = host.toLowerCase();
            if (h.equals("localhost")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int getInt(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean getBool(JsonObject o, String key, boolean def) {
        try {
            return o.has(key) ? o.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String getStr(JsonObject o, String key, String def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
