package com.radiomod.radio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches radio stations from radio-browser.info for any country.
 * Results are cached per country code. For "local" mode, uses IP geolocation first.
 *
 * <p>Fixes for issue #8 (station list missing entries / unstable):
 * <ul>
 *   <li>Stable ordering by {@code votes} instead of volatile {@code clickcount}.</li>
 *   <li>Mirror failover across de1/de2/nl1 instead of a single hardcoded host.</li>
 *   <li>No aggressive regional-variant dedup — only exact name+URL duplicates are removed,
 *       so all stations for a location are actually shown.</li>
 *   <li>Limits come from {@code config/radiomod.json} (maxStations/fetchLimit).</li>
 *   <li>HLS (m3u8) entries are filtered out — the in-game player handles direct
 *       MP3 / AAC / AAC+ streams only.</li>
 * </ul>
 *
 * <p>Fix for issue #7: the API query no longer hardcodes {@code codec=MP3};
 * AAC and AAC+ stations are accepted and played via the bundled JAAD decoder.
 */
@OnlyIn(Dist.CLIENT)
public class LocalStationFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TIMEOUT_MS = 8000;

    /** Mirrors, tried in order (see https://api.radio-browser.info). */
    private static final String[] MIRRORS = {
            "https://de1.api.radio-browser.info",
            "https://de2.api.radio-browser.info",
            "https://nl1.api.radio-browser.info"
    };

    /** Codecs the in-game player can actually decode (MP3 via JLayer, AAC/AAC+ via JAAD). */
    private static final Set<String> SUPPORTED_CODECS = Set.of("MP3", "AAC", "AAC+");

    // Cache: countryCode -> fetched station list (null = not yet fetched, empty list = failed)
    private static final Map<String, List<RadioStation>> cache = new ConcurrentHashMap<>();
    // Track which country codes are currently being fetched
    private static final Map<String, Boolean> fetching = new ConcurrentHashMap<>();

    // Local geolocation
    private static final AtomicReference<String> detectedCountryCode = new AtomicReference<>(null);
    private static final AtomicReference<String> detectedState = new AtomicReference<>("");
    private static final AtomicReference<String> detectedCountryName = new AtomicReference<>("");

    // ─────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Get stations for the player's detected local area.
     * Returns null if still loading, empty list if failed.
     */
    public static List<RadioStation> getLocalStations() {
        // First ensure we know the player's location
        String cc = detectedCountryCode.get();
        if (cc == null) {
            triggerGeoDetection();
            return null; // not ready
        }
        // Fetch stations for detected country + state
        return getStationsForCountry(cc);
    }

    /**
     * Get stations for a specific country code (e.g. "DE", "JP", "US").
     * Returns null if still loading, the list once available.
     */
    public static List<RadioStation> getStationsForCountry(String countryCode) {
        if (countryCode == null || countryCode.isEmpty()) return null;
        String cc = countryCode.toUpperCase();

        List<RadioStation> cached = cache.get(cc);
        if (cached != null) return cached;

        // Trigger async fetch if not already running
        if (!fetching.containsKey(cc)) {
            fetching.put(cc, true);
            final String state = cc.equals(detectedCountryCode.get()) ? detectedState.get() : "";
            CompletableFuture.runAsync(() -> fetchForCountry(cc, state));
        }
        return null; // not ready yet
    }

    /**
     * Check if stations for a country code have been loaded
     */
    public static boolean isReady(String countryCode) {
        if (countryCode == null) return false;
        return cache.containsKey(countryCode.toUpperCase());
    }

    /**
     * Check if local geolocation is ready
     */
    public static boolean isLocalReady() {
        String cc = detectedCountryCode.get();
        return cc != null && cache.containsKey(cc);
    }

    /**
     * Shortcut for old code compatibility
     */
    public static boolean isReady() {
        return isLocalReady();
    }

    public static String getDetectedCountry() {
        return detectedCountryName.get();
    }

    public static String getDetectedState() {
        return detectedState.get();
    }

    // ─────────────────────────────────────────────────────────────────
    //  INTERNAL
    // ─────────────────────────────────────────────────────────────────

    private static void triggerGeoDetection() {
        if (fetching.containsKey("__geo__")) return;
        fetching.put("__geo__", true);
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("RadioMod: Detecting location...");
                String cc = null, state = "", country = "", city = "";
                // Primary: ip-api.com (free tier, http only)
                try {
                    String geoJson = httpGet("http://ip-api.com/json/?fields=country,regionName,city,countryCode");
                    JsonObject geo = JsonParser.parseString(geoJson).getAsJsonObject();
                    cc = geo.has("countryCode") ? geo.get("countryCode").getAsString() : null;
                    state = geo.has("regionName") ? geo.get("regionName").getAsString() : "";
                    country = geo.has("country") ? geo.get("country").getAsString() : "";
                    city = geo.has("city") ? geo.get("city").getAsString() : "";
                } catch (Exception e) {
                    LOGGER.debug("RadioMod: primary geolocation failed: {}", e.getMessage());
                }
                // Fallback: ipapi.co (https, no key for basic fields)
                if (cc == null || cc.isEmpty()) {
                    try {
                        String geoJson = httpGet("https://ipapi.co/json/");
                        JsonObject geo = JsonParser.parseString(geoJson).getAsJsonObject();
                        cc = geo.has("country_code") ? geo.get("country_code").getAsString() : "DE";
                        state = geo.has("region") ? geo.get("region").getAsString() : "";
                        country = geo.has("country_name") ? geo.get("country_name").getAsString() : "";
                        city = geo.has("city") ? geo.get("city").getAsString() : "";
                    } catch (Exception e) {
                        LOGGER.debug("RadioMod: fallback geolocation failed: {}", e.getMessage());
                    }
                }
                if (cc == null || cc.isEmpty()) cc = "DE";

                detectedCountryCode.set(cc);
                detectedState.set(state != null ? state : "");
                detectedCountryName.set(country != null ? country : "");
                LOGGER.info("RadioMod: Location: {}, {}, {} ({})", city, state, country, cc);

                // Immediately start fetching for this country
                fetchForCountry(cc, detectedState.get());

            } catch (Exception e) {
                LOGGER.warn("RadioMod: Geolocation failed: {}", e.getMessage());
                detectedCountryCode.set("DE"); // fallback
                detectedState.set("");
            }
        });
    }

    private static void fetchForCountry(String countryCode, String state) {
        try {
            ensureConfigLoaded();
            List<RadioStation> stations;

            // If we have a state, try local first for better results
            if (state != null && !state.isEmpty()) {
                stations = queryRadioBrowser(countryCode, state);
                if (stations.size() >= 6) {  // need at least 6 unique regional results to use them
                    cache.put(countryCode, Collections.unmodifiableList(stations));
                    LOGGER.info("RadioMod: Loaded {} stations for {} ({})", stations.size(), countryCode, state);
                    return;
                }
            }

            // Fetch country-wide
            stations = queryRadioBrowser(countryCode, "");
            cache.put(countryCode, Collections.unmodifiableList(stations));
            LOGGER.info("RadioMod: Loaded {} stations for {}", stations.size(), countryCode);

        } catch (Exception e) {
            LOGGER.warn("RadioMod: Fetch failed for {}: {}", countryCode, e.getMessage());
            cache.put(countryCode, Collections.emptyList());
        }
    }

    private static void ensureConfigLoaded() {
        try {
            com.radiomod.RadioConfig.load();
        } catch (Throwable ignored) {
        }
    }

    private static int maxResults() {
        try {
            return com.radiomod.RadioConfig.maxStations;
        } catch (Throwable t) {
            return 25;
        }
    }

    private static int fetchLimit() {
        try {
            return com.radiomod.RadioConfig.fetchLimit;
        } catch (Throwable t) {
            return 100;
        }
    }

    private static List<RadioStation> queryRadioBrowser(String countryCode, String state) {
        List<RadioStation> best = Collections.emptyList();
        Exception lastError = null;
        for (String mirror : MIRRORS) {
            try {
                List<RadioStation> got = queryMirror(mirror, countryCode, state);
                if (!got.isEmpty()) return got;
                if (got.size() > best.size()) best = got;
            } catch (Exception e) {
                lastError = e;
                LOGGER.debug("RadioMod: mirror {} failed for {}: {}", mirror, countryCode, e.getMessage());
            }
        }
        if (lastError != null && best.isEmpty()) {
            LOGGER.debug("RadioMod: radio-browser query failed for {}: {}", countryCode, lastError.getMessage());
        }
        return best;
    }

    private static List<RadioStation> queryMirror(String mirror, String countryCode, String state) throws Exception {
        StringBuilder url = new StringBuilder();
        url.append(mirror).append("/json/stations/search?");
        url.append("countrycode=").append(URLEncoder.encode(countryCode, StandardCharsets.UTF_8.name()));
        // NOTE: no codec= filter here (issue #7) — we accept MP3 + AAC/AAC+ and
        // filter client-side to what the player can decode.
        url.append("&bitrateMin=64");
        url.append("&limit=").append(fetchLimit());
        // votes = stable popularity ranking; clickcount fluctuates hourly (issue #8)
        url.append("&order=votes");
        url.append("&reverse=true");
        url.append("&hidebroken=true");
        if (state != null && !state.isEmpty()) {
            url.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8.name()));
        }

        String json = httpGet(url.toString());
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();

        // Collect candidates with votes for a stable client-side sort.
        record Candidate(RadioStation station, int votes, String normName, String streamUrl) {
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> seenUrls = new HashSet<>();
        for (JsonElement elem : arr) {
            JsonObject obj = elem.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString().trim() : "";
            String streamUrl = obj.has("url_resolved") ? obj.get("url_resolved").getAsString().trim() : "";
            String uuid = obj.has("stationuuid") ? obj.get("stationuuid").getAsString() : "";
            String codec = obj.has("codec") ? obj.get("codec").getAsString().trim().toUpperCase() : "MP3";
            int hls = 0;
            try {
                hls = obj.has("hls") ? obj.get("hls").getAsInt() : 0;
            } catch (Exception ignored) {
            }
            int votes = 0;
            try {
                votes = obj.has("votes") ? obj.get("votes").getAsInt() : 0;
            } catch (Exception ignored) {
            }

            if (name.isEmpty() || streamUrl.isEmpty()) continue;
            if (hls != 0) continue; // m3u8 playlists are not direct streams
            if (!SUPPORTED_CODECS.contains(codec)) continue; // e.g. OGG/FLAC/OPUS — not decodable in-game
            if (!(streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))) continue;
            if (name.length() > 32) name = name.substring(0, 30) + "..";

            // Issue #8 fix: dedup ONLY exact duplicates (normalized name or identical URL).
            // The old base-name/prefix logic hid most regional stations.
            String norm = normalizeName(name);
            if (seenNames.contains(norm)) continue;
            if (!seenUrls.add(streamUrl)) continue;
            seenNames.add(norm);

            String id = "dyn_" + uuid.substring(0, Math.min(8, uuid.length()));
            candidates.add(new Candidate(new RadioStation(id, name, streamUrl), votes, norm, streamUrl));
        }

        // Stable order: votes desc, then name asc (deterministic across restarts).
        candidates.sort(Comparator.comparingInt(Candidate::votes).reversed()
                .thenComparing(Candidate::normName));

        List<RadioStation> result = new ArrayList<>();
        int max = Math.max(5, Math.min(60, maxResults()));
        for (Candidate c : candidates) {
            result.add(c.station());
            if (result.size() >= max) break;
        }
        return result;
    }

    /** Lowercase, strip punctuation/extra spaces — for exact-duplicate detection only. */
    private static String normalizeName(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9а-яїієґ ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "RadioMod/1.1 Minecraft");
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + urlStr);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
