package com.radiomod.radio;

import com.mojang.logging.LogUtils;
import com.radiomod.blockentity.RadioBlockEntity;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Client-side MP3 stream player with distance attenuation. Decodes MP3 via JLayer
 * and outputs PCM through JavaSound. Supports position updates for moving sources.
 */
@OnlyIn(Dist.CLIENT)
public class RadioPlayer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float BASE_MAX_DISTANCE = 100f;

    private static final Map<String, RadioPlayer> ACTIVE = new HashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "RadioMod-Stream");
        t.setDaemon(true);
        return t;
    });

    private static volatile Boolean jlayerAvailable = null;

    private static boolean checkJLayerAvailable() {
        if (jlayerAvailable == null) {
            try {
                Class.forName("javazoom.jl.decoder.Bitstream");
                Class.forName("javazoom.jl.decoder.Decoder");
                Class.forName("javazoom.jl.decoder.Header");
                Class.forName("javazoom.jl.decoder.SampleBuffer");
                jlayerAvailable = true;
            } catch (ClassNotFoundException e) {
                jlayerAvailable = false;
                LOGGER.error("RadioMod: JLayer not found! MP3 playback disabled.");
            }
        }
        return jlayerAvailable;
    }

    /**
     * Starts or updates a station for a specific radio block.
     * If the same station is already playing, only the position is updated (no stream restart).
     *
     * @param blockKey  Unique radio key "radiomod:<UUID>"
     * @param stationId Station ID (or "off" to stop)
     * @param streamUrl Direct MP3 stream URL
     * @param blockPos  World position of the audio source
     */
    public static void setStation(String blockKey, String stationId, String streamUrl, Vec3 blockPos) {
        if (!checkJLayerAvailable()) return;

        RadioPlayer existing = ACTIVE.get(blockKey);
        if (existing != null && stationId.equals(existing.currentStationId)) {
            existing.blockPos = blockPos;
            return;
        }

        stopBlock(blockKey);
        if (stationId.equals("off") || stationId.isBlank()) return;

        String url = (streamUrl != null && !streamUrl.isBlank())
                ? streamUrl
                : RadioStation.getById(stationId).getStreamUrl();
        if (url.isBlank()) {
            LOGGER.warn("RadioMod: No URL for station '{}'", stationId);
            return;
        }

        LOGGER.info("RadioMod: Starting stream '{}' from: {}", stationId, url);
        RadioPlayer player = new RadioPlayer(stationId, blockKey, blockPos);
        ACTIVE.put(blockKey, player);
        player.start(url);
    }

    /**
     * Overload without streamUrl — looks up the URL from the local station registry.
     */
    public static void setStation(String blockKey, String stationId, Vec3 blockPos) {
        RadioStation station = RadioStation.getById(stationId);
        setStation(blockKey, stationId, station.getStreamUrl(), blockPos);
    }

    /**
     * Stops the stream for a specific radio block.
     */
    public static void stopBlock(String blockKey) {
        RadioPlayer p = ACTIVE.remove(blockKey);
        if (p != null) p.stop();
    }

    /**
     * Stops all active streams.
     */
    public static void stopAll() {
        ACTIVE.values().forEach(RadioPlayer::stop);
        ACTIVE.clear();
    }

    /**
     * Sets the volume (0-100) for a specific radio block. Also affects the maximum hearing range.
     *
     * @param blockKey Unique radio key "radiomod:<UUID>"
     * @param vol      Volume 0-100
     */
    public static void setVolume(String blockKey, int vol) {
        RadioPlayer p = ACTIVE.get(blockKey);
        if (p != null) p.setVolume(vol);
    }

    /**
     * Returns the volume (0-100) for a specific radio block, or 50 if not playing.
     *
     * @param blockKey Unique radio key "radiomod:<UUID>"
     * @return Volume 0-100
     */
    public static int getVolume(String blockKey) {
        RadioPlayer p = ACTIVE.get(blockKey);
        return p != null ? p.volume : 50;
    }

    /**
     * Sets this player's volume (0-100) and refreshes the audio line gain.
     *
     * @param vol Volume 0-100
     */
    public void setVolume(int vol) {
        this.volume = Math.max(0, Math.min(100, vol));
        updateVolume();
    }

    public int getVolume() {
        return volume;
    }

    /**
     * Called every client tick. Updates position and volume
     * of all active streams based on player distance.
     */
    public static void tickAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ACTIVE.values().forEach(p -> {
            p.updateBlockPos();
            p.updateVolume();
        });
    }

    private final String currentStationId;
    private final String radioKey;
    private volatile Vec3 blockPos;
    private int volume = 50;

    private volatile boolean running = false;
    private volatile SourceDataLine line;
    private Future<?> future;

    private RadioPlayer(String stationId, String radioKey, Vec3 blockPos) {
        this.currentStationId = stationId;
        this.radioKey = radioKey;
        this.blockPos = blockPos;
    }

    /**
     * Updates the audio source position. For SubLevel blocks, uses Sable reflection
     * to resolve the real world position so audio follows the physics object.
     * Detects when the client-side Sable pose freezes (player far from SubLevel)
     * and falls back to server-side resolution in singleplayer.
     */
    private Vec3 lastSablePos = null;
    private int staleTicks = 0;
    private static final int STALE_THRESHOLD = 20; // ~5 seconds at 5-tick interval

    private void updateBlockPos() {
        RadioBlockEntity be = RadioBlockEntity.findByKey(radioKey);
        if (be == null) return;

        BlockPos bp = be.getBlockPos();
        boolean isSubLevel = Math.abs(bp.getX()) > 1_000_000 || Math.abs(bp.getZ()) > 1_000_000;

        if (isSubLevel) {
            // Try client-side Sable first (always accurate when nearby)
            Vec3 worldPos = tryGetClientSableWorldPos(be);
            if (worldPos != null) {
                // Detect staleness: if position hasn't changed, increment counter
                if (lastSablePos != null && worldPos.distanceToSqr(lastSablePos) < 0.0001) {
                    staleTicks++;
                } else {
                    staleTicks = 0;
                }
                lastSablePos = worldPos;

                // If position is stale (frozen by Sable at distance), try server resolution
                if (staleTicks >= STALE_THRESHOLD) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.getSingleplayerServer() != null) {
                        Vec3 serverResolved = tryResolveViaIntegratedServer(be);
                        if (serverResolved != null) {
                            blockPos = serverResolved;
                            return;
                        }
                    }
                }
                // Use client Sable result (either fresh, or best we have)
                blockPos = worldPos;
            } else {
                // Client Sable failed entirely — try server fallback
                Minecraft mc = Minecraft.getInstance();
                if (mc.getSingleplayerServer() != null) {
                    Vec3 serverResolved = tryResolveViaIntegratedServer(be);
                    if (serverResolved != null) {
                        blockPos = serverResolved;
                    }
                }
            }
        } else {
            // Issue #4: Create contraption/train — resolve via transform when possible.
            boolean contraption = false;
            try {
                contraption = be.getLevel() != null
                        && be.getLevel().getClass().getName().toLowerCase().contains("contraption");
            } catch (Exception ignored) {
            }
            if (contraption) {
                Vec3 worldPos = com.radiomod.network.ModPayloads.tryGetClientCreateWorldPosition(be);
                if (worldPos != null) {
                    blockPos = worldPos;
                }
                // else: keep last known position (server rebroadcasts every 10 ticks anyway)
            } else {
                blockPos = Vec3.atCenterOf(bp);
            }
        }
    }

    /**
     * In singleplayer (integrated server), resolves the world position using
     * the Sable server-side HELPER singleton (which lives in the same JVM).
     * Only calls the HELPER directly — does NOT access server level/chunk data
     * from the render thread (which could cause threading issues).
     */
    private static Vec3 tryResolveViaIntegratedServer(RadioBlockEntity clientBe) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) return null;

        try {
            // Use the server-side Sable resolution (HELPER is a thread-safe singleton)
            return com.radiomod.network.ModPayloads.resolveWorldPosition(clientBe);
        } catch (Exception e) {
            return null;
        }
    }

    private static volatile boolean sableClientChecked = false;
    private static volatile boolean sableClientAvailable = false;

    /**
     * Client-side Sable position resolution via reflection.
     * Tries getContaining(be) → logicalPose().transformPosition(), then Level.logicalPose() as fallback.
     *
     * @param be BlockEntity on a SubLevel
     * @return Vec3 world position, or null on failure
     */
    private static Vec3 tryGetClientSableWorldPos(BlockEntity be) {
        if (!sableClientChecked) {
            try {
                Class.forName("dev.ryanhcode.sable.Sable");
                sableClientAvailable = true;
            } catch (ClassNotFoundException ignored) {
                sableClientAvailable = false;
            }
            sableClientChecked = true;
        }
        if (!sableClientAvailable) return null;

        Vec3 localPos = Vec3.atCenterOf(be.getBlockPos());

        try {
            Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sableClass.getField("HELPER").get(null);

            java.lang.reflect.Method getContaining = null;
            for (java.lang.reflect.Method m : helper.getClass().getMethods()) {
                if ("getContaining".equals(m.getName()) && m.getParameterCount() == 1) {
                    getContaining = m;
                    break;
                }
            }

            if (getContaining != null) {
                try {
                    Object subLevel = getContaining.invoke(helper, be);
                    if (subLevel != null) {
                        Vec3 result = callTransformPosition(subLevel, localPos);
                        if (result != null) return result;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Object level = be.getLevel();
            if (level == null) return null;
            java.lang.reflect.Method logicalPoseMethod;
            try {
                logicalPoseMethod = level.getClass().getMethod("logicalPose");
            } catch (NoSuchMethodException ignored) {
                return null;
            }
            Object pose = logicalPoseMethod.invoke(level);
            if (pose == null) return null;
            return callTransformPositionOnPose(pose, localPos);
        } catch (Exception e) {
            return null;
        }
    }

    private static Vec3 callTransformPosition(Object subLevel, Vec3 localPos) {
        try {
            Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            if (pose == null) return null;
            return callTransformPositionOnPose(pose, localPos);
        } catch (Exception e) {
            return null;
        }
    }

    private static Vec3 callTransformPositionOnPose(Object pose, Vec3 localPos) {
        try {
            for (java.lang.reflect.Method m : pose.getClass().getMethods()) {
                if ("transformPosition".equals(m.getName()) && m.getParameterCount() == 1) {
                    if (m.getReturnType() == Vec3.class || m.getParameterTypes()[0] == Vec3.class) {
                        Object result = m.invoke(pose, localPos);
                        if (result instanceof Vec3 vec) return vec;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void start(String url) {
        running = true;
        future = EXECUTOR.submit(() -> {
            while (running) {
                try {
                    URLConnection conn = URI.create(url).toURL().openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "RadioMod/1.1 Minecraft");
                    conn.setRequestProperty("Icy-MetaData", "0");

                    String contentType = null;
                    try {
                        contentType = conn.getContentType();
                    } catch (Exception ignored) {
                    }
                    boolean aac = isAacStream(url, contentType);
                    if (aac) {
                        LOGGER.info("RadioMod: AAC/AAC+ stream detected (Content-Type: {})", contentType);
                    }

                    try (InputStream raw = conn.getInputStream();
                         BufferedInputStream bis = new BufferedInputStream(raw, 128 * 1024)) {
                        if (aac) {
                            streamDecodeAac(bis);
                        } else {
                            streamDecodeMp3(bis);
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        LOGGER.warn("RadioMod: Stream error, retry in 3s: {}", e.getMessage());
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
            }
        });
    }

    /**
     * Detects AAC/AAC+ streams (issue #7) by URL suffix and HTTP Content-Type.
     * MP3 remains the default.
     */
    private static boolean isAacStream(String url, String contentType) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("aac") || ct.contains("mp4") || ct.contains("x-aac")
                    || ct.contains("aacp") || ct.contains("m4a")) {
                return true;
            }
        }
        if (url != null) {
            String u = url.toLowerCase();
            if (u.contains(".aac") || u.contains(".m4a") || u.contains(".mp4")
                    || u.contains("aacp") || u.contains("/aac") || u.contains("codec=aac")
                    || u.contains("type=aac")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes MP3 frames and outputs them as PCM. Applies the computed
     * gain (volume × distance attenuation) per sample.
     */
    private void streamDecodeMp3(InputStream in) throws Exception {
        streamDecode(in);
    }

    /**
     * Decodes AAC-LC / HE-AAC (AAC+) ADTS streams via JAAD (pure Java, bundled with jarJar).
     * Implemented with reflection so a missing/mismatched JAAD jar degrades to a clear
     * log message instead of a NoClassDefFoundError crash (issue #7).
     *
     * <p>JAAD API used (verified against JAADec sources):
     * {@code ADTSDemultiplexer(InputStream)}, {@code getDecoderSpecificInfo()},
     * {@code readNextFrame()}, {@code Decoder(byte[])},
     * {@code decodeFrame(byte[], SampleBuffer)}, {@code SampleBuffer.getData()},
     * {@code getSampleRate()}, {@code getChannels()}, {@code setBigEndian(boolean)}.
     */
    private void streamDecodeAac(InputStream in) throws Exception {
        Class<?> demuxClass;
        try {
            demuxClass = Class.forName("net.sourceforge.jaad.adts.ADTSDemultiplexer");
            Class.forName("net.sourceforge.jaad.aac.Decoder");
            Class.forName("net.sourceforge.jaad.aac.SampleBuffer");
        } catch (ClassNotFoundException e) {
            throw new IOException("AAC stream requires JAAD (not on classpath). MP3-only fallback. " + e.getMessage());
        }

        Object demux;
        try {
            demux = demuxClass.getConstructor(InputStream.class).newInstance(in);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw new IOException("Not an ADTS AAC stream: "
                    + (ite.getCause() != null ? ite.getCause().getMessage() : ite.getMessage()));
        }
        byte[] decoderInfo = (byte[]) demuxClass.getMethod("getDecoderSpecificInfo").invoke(demux);
        Class<?> decoderClass = Class.forName("net.sourceforge.jaad.aac.Decoder");
        Class<?> bufClass = Class.forName("net.sourceforge.jaad.aac.SampleBuffer");
        Object decoder = decoderClass.getConstructor(byte[].class).newInstance((Object) decoderInfo);
        Object sampleBuf = bufClass.getConstructor().newInstance();
        // JAAD outputs big-endian PCM by default — switch to little-endian once.
        try {
            bufClass.getMethod("setBigEndian", boolean.class).invoke(sampleBuf, false);
        } catch (Exception ignored) {
        }
        java.lang.reflect.Method readFrame = demuxClass.getMethod("readNextFrame");
        java.lang.reflect.Method decodeFrame = decoderClass.getMethod("decodeFrame", byte[].class, bufClass);
        java.lang.reflect.Method getData = bufClass.getMethod("getData");

        SourceDataLine localLine = null;
        int lineRate = -1, lineChannels = -1;
        int consecutiveErrors = 0;
        try {
            while (running) {
                byte[] frame;
                try {
                    frame = (byte[]) readFrame.invoke(demux);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    if (ite.getCause() instanceof java.io.EOFException) break;
                    throw ite;
                }
                if (frame == null) break;
                try {
                    decodeFrame.invoke(decoder, frame, sampleBuf);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    // One corrupt frame must not kill the stream — skip it.
                    if (++consecutiveErrors > 20) throw ite;
                    LOGGER.debug("RadioMod: skipping corrupt AAC frame: {}",
                            ite.getCause() != null ? ite.getCause().getMessage() : ite.getMessage());
                    continue;
                }
                consecutiveErrors = 0;
                byte[] pcm = (byte[]) getData.invoke(sampleBuf);
                if (pcm == null || pcm.length == 0) continue;

                int sampleRate = reflectInt(sampleBuf, "getSampleRate", 44100);
                int channels = reflectInt(sampleBuf, "getChannels", 2);
                if (channels < 1) channels = 1;
                if (channels > 2) channels = 2; // downmix guard: some HE-AAC reports 5.1
                if (sampleRate < 8000) sampleRate = 44100;

                if (localLine == null || sampleRate != lineRate || channels != lineChannels) {
                    if (localLine != null) {
                        try {
                            localLine.drain();
                            localLine.stop();
                            localLine.close();
                        } catch (Exception ignored) {
                        }
                    }
                    AudioFormat fmt = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            sampleRate, 16, channels, channels * 2, sampleRate, false);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                    localLine = (SourceDataLine) AudioSystem.getLine(info);
                    localLine.open(fmt, 8192);
                    localLine.start();
                    line = localLine;
                    lineRate = sampleRate;
                    lineChannels = channels;
                    updateVolume();
                }

                // JAAD SampleBuffer.getData() = 16-bit little-endian PCM. Apply gain per sample.
                float gain = computeLinearGain();
                int samples = pcm.length / 2;
                byte[] out = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    int lo = pcm[i * 2] & 0xFF;
                    int hi = pcm[i * 2 + 1]; // signed
                    int sample = (hi << 8) | lo;
                    int scaled = Math.round(sample * gain);
                    if (scaled > 32767) scaled = 32767;
                    if (scaled < -32768) scaled = -32768;
                    out[i * 2] = (byte) (scaled & 0xFF);
                    out[i * 2 + 1] = (byte) ((scaled >> 8) & 0xFF);
                }
                localLine.write(out, 0, out.length);
            }
        } finally {
            if (localLine != null) {
                try {
                    localLine.drain();
                    localLine.stop();
                    localLine.close();
                } catch (Exception ignored) {
                }
            }
            if (line == localLine) line = null;
        }
    }

    private static int reflectInt(Object target, String method, int fallback) {
        try {
            Object v = target.getClass().getMethod(method).invoke(target);
            if (v instanceof Number n) return n.intValue();
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * Decodes MP3 frames and outputs them as PCM. Applies the computed
     * gain (volume × distance attenuation) per sample.
     */
    private void streamDecode(InputStream in) throws Exception {
        Bitstream bitstream = new Bitstream(in);
        Decoder decoder = new Decoder();
        SourceDataLine localLine = null;

        try {
            while (running) {
                Header header = bitstream.readFrame();
                if (header == null) break;

                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                if (localLine == null) {
                    int sampleRate = (int) header.frequency();
                    int channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                    AudioFormat fmt = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            sampleRate, 16, channels, channels * 2, sampleRate, false
                    );
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                    localLine = (SourceDataLine) AudioSystem.getLine(info);
                    localLine.open(fmt, 8192);
                    localLine.start();
                    line = localLine;
                    updateVolume();
                }

                short[] pcm = output.getBuffer();
                int len = output.getBufferLength();
                float gain = computeLinearGain();
                byte[] bytes = new byte[len * 2];
                for (int i = 0; i < len; i++) {
                    int sample = Math.round(pcm[i] * gain);
                    if (sample > 32767) sample = 32767;
                    if (sample < -32768) sample = -32768;
                    bytes[i * 2] = (byte) (sample & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                }

                localLine.write(bytes, 0, bytes.length);
                bitstream.closeFrame();
            }
        } finally {
            try {
                bitstream.close();
            } catch (Exception ignored) {
            }
            if (localLine != null) {
                localLine.drain();
                localLine.stop();
                localLine.close();
            }
            line = null;
        }
    }

    private void stop() {
        running = false;
        SourceDataLine l = line;
        if (l != null) {
            l.stop();
            l.close();
            line = null;
        }
        if (future != null) {
            future.cancel(true);
            future = null;
        }
    }

    private void updateVolume() {
        SourceDataLine l = line;
        if (l == null || !l.isOpen()) return;
        try {
            FloatControl ctrl = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
            float db = toDb(computeLinearGain());
            float clamped = Math.max(ctrl.getMinimum(), Math.min(ctrl.getMaximum(), db));
            ctrl.setValue(clamped);
        } catch (Exception ignored) {
        }
    }

    /**
     * Computes the linear gain multiplier [0..1] based on volume and distance.
     * Maximum range scales linearly with volume (vol=100 → 200 blocks).
     * Quadratic fadeout over distance.
     *
     * @return Gain value 0.0 (silent) to 1.0 (full volume)
     */
    private float computeLinearGain() {
        float userGain = volume / 100f;
        if (userGain <= 0f) return 0f;

        float maxDist = BASE_MAX_DISTANCE * 2f * userGain;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return userGain;

        double dx = mc.player.getX() - blockPos.x;
        double dy = mc.player.getY() - blockPos.y;
        double dz = mc.player.getZ() - blockPos.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist >= maxDist) return 0f;

        float distGain = 1f - (dist / maxDist);
        distGain = distGain * distGain;

        return userGain * distGain;
    }

    private static float toDb(float linear) {
        if (linear <= 0f) return -80f;
        return (float) (Math.log10(linear) * 20.0);
    }
}
