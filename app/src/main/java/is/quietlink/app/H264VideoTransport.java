package is.quietlink.app;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-latency H.264 packetization + hardware decode for QuietLink.
 *
 * The transport stays on the existing authenticated/encrypted TYPE_VIDEO UDP
 * channel. A magic/version header keeps this wire format distinguishable from
 * the legacy JPEG frames, so old JPEG remains a safe fallback.
 */
final class H264VideoTransport implements AutoCloseable {
    interface Listener {
        void onFrameRendered();
        void onKeyFrameNeeded();
        void onQualityTierRequested(int tier);
        void onFatal(String reason);
    }

    private static final int MAGIC = 0x514C4832; // "QLH2"
    private static final int VERSION = 1;
    private static final int FLAG_CONFIG = 1;
    private static final int FLAG_KEYFRAME = 1 << 1;
    private static final int HEADER_BYTES = 22;
    private static final int CHUNK_BYTES = 1380;
    private static final int MAX_CHUNKS = 768;
    private static final long ASSEMBLY_MAX_AGE_MS = 900L;
    static final int TIER_HD = 0;
    static final int TIER_BALANCED = 1;
    static final int TIER_SMOOTH = 2;
    static final int MAX_TIER = TIER_SMOOTH;

    private static final int[] TIER_WIDTH = {1280, 960, 640};
    private static final int[] TIER_HEIGHT = {720, 540, 360};
    private static final int[] TIER_START_BITRATE = {4_000_000, 2_600_000, 1_250_000};
    private static final int[] TIER_MIN_BITRATE = {1_800_000, 950_000, 450_000};
    private static final int[] TIER_MAX_BITRATE = {6_500_000, 4_200_000, 2_200_000};

    private final MediaTransport transport;
    private final H264Codec.Capability capability;
    private final Listener listener;
    private final AtomicInteger unitCounter = new AtomicInteger(1);
    private final Map<Integer, Assembly> assemblies = new ConcurrentHashMap<>();
    private final AtomicBoolean fatalNotified = new AtomicBoolean(false);

    private volatile boolean enabled;
    private volatile Surface outputSurface;
    private volatile byte[] remoteCsd0;
    private volatile byte[] remoteCsd1;
    private volatile byte[] localCsd0;
    private volatile byte[] localCsd1;
    private volatile int remoteWidth = H264Codec.WIDTH;
    private volatile int remoteHeight = H264Codec.HEIGHT;
    private volatile int qualityTier = TIER_HD;
    private volatile long lastTierChangeMs = 0L;
    private volatile long lastTierRequestMs = 0L;
    private int congestedSamples = 0;
    private int upgradeStableSamples = 0;
    private H264Codec.Encoder encoder;
    private H264Codec.Decoder decoder;

    private int adaptiveBitrate = H264Codec.START_BITRATE;
    private long lastAdaptAtMs;
    private int stableAdaptSamples;
    private long lastFrameNotifyMs;
    private long lastKeyFrameRequestMs;
    private final AtomicLong lostVideoUnits = new AtomicLong(0);
    private final AtomicLong keyFrameRequests = new AtomicLong(0);
    private volatile float txFps = 0f;
    private volatile float rxFps = 0f;
    private int txFramesWindow = 0;
    private int rxFramesWindow = 0;
    private long txFpsWindowStartMs = SystemClock.elapsedRealtime();
    private long rxFpsWindowStartMs = SystemClock.elapsedRealtime();
    private volatile long lastEncodedFrameMs = 0L;
    private volatile long lastRenderedFrameMs = 0L;
    private volatile long encoderStartedMs = 0L;
    private volatile long decoderStartedMs = 0L;

    H264VideoTransport(MediaTransport transport,
                       H264Codec.Capability capability,
                       Listener listener) {
        this.transport = transport;
        this.capability = capability;
        this.listener = listener;
    }

    static boolean looksLikePacket(byte[] payload) {
        return payload != null
                && payload.length >= HEADER_BYTES
                && ByteBuffer.wrap(payload, 0, 4).getInt() == MAGIC;
    }

    synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        fatalNotified.set(false);
        qualityTier = TIER_HD;
        adaptiveBitrate = tierStartBitrate(qualityTier);
        stableAdaptSamples = 0;
        congestedSamples = 0;
        upgradeStableSamples = 0;
        lastTierChangeMs = SystemClock.elapsedRealtime();
        lastTierRequestMs = 0L;
        lastAdaptAtMs = 0L;
        lastKeyFrameRequestMs = 0L;
        assemblies.clear();

        if (!enabled) {
            stopEncoder();
            closeDecoder();
            remoteCsd0 = null;
            remoteCsd1 = null;
        } else {
            rebuildDecoder();
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    synchronized Surface startEncoder() throws Exception {
        if (!enabled) throw new IllegalStateException("H.264 transport is disabled");
        stopEncoder();

        encoder = new H264Codec.Encoder(
                capability,
                tierWidth(qualityTier),
                tierHeight(qualityTier),
                adaptiveBitrate,
                new H264Codec.EncoderListener() {
            @Override public void onOutputFormat(MediaFormat format) {
                byte[] csd0 = copyBuffer(format, "csd-0");
                byte[] csd1 = copyBuffer(format, "csd-1");
                if (csd0 != null && csd0.length > 0) {
                    localCsd0 = csd0;
                    localCsd1 = csd1 == null ? new byte[0] : csd1;
                    sendConfig(localCsd0, localCsd1);
                }
            }

            @Override public void onAccessUnit(byte[] data, long presentationTimeUs, int flags) {
                if (!enabled || data == null || data.length == 0) return;
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;

                boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                recordEncodedFrame();
                adaptBitrate();
                if (key && localCsd0 != null && localCsd0.length > 0) {
                    sendConfig(localCsd0, localCsd1 == null ? new byte[0] : localCsd1);
                }
                sendBlob(data, presentationTimeUs, key ? FLAG_KEYFRAME : 0);
            }

            @Override public void onEncoderError(String message) {
                fatal("Encoder: " + message);
            }
        });
        encoder.start();
        encoderStartedMs = SystemClock.elapsedRealtime();
        encoder.setBitrate(adaptiveBitrate);
        encoder.requestKeyFrame();
        return encoder.inputSurface();
    }

    synchronized void stopEncoder() {
        if (encoder != null) {
            try { encoder.close(); } catch (Exception ignored) {}
            encoder = null;
        }
    }

    synchronized void requestKeyFrame() {
        if (encoder != null) encoder.requestKeyFrame();
    }

    synchronized void setOutputSurface(Surface surface) {
        if (outputSurface == surface) return;
        outputSurface = surface;
        rebuildDecoder();
    }

    synchronized void refreshDecoderAfterWake() {
        if (!enabled) return;
        Surface surface = outputSurface;
        if (surface == null || !surface.isValid()) return;
        rebuildDecoder();
    }

    boolean encoderStalled(long thresholdMs) {
        if (!enabled || encoder == null) return false;
        long now = SystemClock.elapsedRealtime();
        long last = Math.max(lastEncodedFrameMs, encoderStartedMs);
        return last > 0L && now - last > Math.max(500L, thresholdMs);
    }

    boolean decoderStalled(long thresholdMs) {
        if (!enabled || decoder == null) return false;
        long now = SystemClock.elapsedRealtime();
        long last = Math.max(lastRenderedFrameMs, decoderStartedMs);
        return last > 0L && now - last > Math.max(500L, thresholdMs);
    }

    long encodedFrameAgeMs() {
        long last = Math.max(lastEncodedFrameMs, encoderStartedMs);
        return last <= 0L ? -1L : Math.max(0L, SystemClock.elapsedRealtime() - last);
    }

    long renderedFrameAgeMs() {
        long last = Math.max(lastRenderedFrameMs, decoderStartedMs);
        return last <= 0L ? -1L : Math.max(0L, SystemClock.elapsedRealtime() - last);
    }

    boolean onPacket(byte[] payload) {
        if (!looksLikePacket(payload)) return false;
        if (!enabled) return true;

        try {
            ByteBuffer b = ByteBuffer.wrap(payload);
            if (b.getInt() != MAGIC) return false;
            int version = b.get() & 0xff;
            int flags = b.get() & 0xff;
            int index = b.getShort() & 0xffff;
            int count = b.getShort() & 0xffff;
            int unitId = b.getInt();
            long ptsUs = b.getLong();

            if (version != VERSION || count < 1 || count > MAX_CHUNKS || index >= count) return true;

            byte[] part = new byte[b.remaining()];
            b.get(part);
            Assembly a = assemblies.compute(unitId, (id, old) -> {
                if (old == null || old.parts.length != count || old.flags != flags) {
                    return new Assembly(count, flags, ptsUs);
                }
                return old;
            });
            a.put(index, part);

            if (a.complete() && assemblies.remove(unitId, a)) {
                byte[] data = a.join();
                if ((a.flags & FLAG_CONFIG) != 0) {
                    acceptConfig(data);
                } else {
                    H264Codec.Decoder d;
                    synchronized (this) { d = decoder; }
                    if (d != null) {
                        d.queue(data, a.ptsUs, (a.flags & FLAG_KEYFRAME) != 0);
                    }
                }
            }
            cleanupAssemblies();
        } catch (Exception ignored) {
            // Malformed authenticated video payloads are discarded.
        }
        return true;
    }

    private void sendConfig(byte[] csd0, byte[] csd1) {
        // Width/height are appended for v0.3.19+ receivers. Older receivers
        // safely ignore the trailing bytes and continue using SPS dimensions.
        ByteBuffer b = ByteBuffer.allocate(16 + csd0.length + csd1.length);
        b.putInt(csd0.length).put(csd0).putInt(csd1.length).put(csd1);
        b.putInt(tierWidth(qualityTier)).putInt(tierHeight(qualityTier));
        sendBlob(b.array(), 0L, FLAG_CONFIG);
    }

    private void sendBlob(byte[] data, long ptsUs, int flags) {
        if (!enabled || data == null || data.length == 0) return;

        int queued = transport.videoQueueDepth();
        boolean important = (flags & (FLAG_CONFIG | FLAG_KEYFRAME)) != 0;

        // Never let an old backlog sit in front of decoder config or an IDR.
        // Starting an important unit from a clean queue is much more useful than
        // delivering stale P-frames first.
        if (important && queued > 36) {
            transport.discardQueuedVideo();
            queued = 0;
        }
        if (!important && queued > 150) {
            requestKeyFrame();
            return;
        }
        if (queued > 220) transport.discardQueuedVideo();

        int count = (data.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        if (count < 1 || count > MAX_CHUNKS) {
            fatal("Encoded frame exceeded packet budget");
            return;
        }

        int unitId = unitCounter.getAndIncrement();
        for (int i = 0; i < count && enabled; i++) {
            int off = i * CHUNK_BYTES;
            int n = Math.min(CHUNK_BYTES, data.length - off);
            ByteBuffer packet = ByteBuffer.allocate(HEADER_BYTES + n);
            packet.putInt(MAGIC);
            packet.put((byte) VERSION);
            packet.put((byte) flags);
            packet.putShort((short) i);
            packet.putShort((short) count);
            packet.putInt(unitId);
            packet.putLong(ptsUs);
            packet.put(data, off, n);
            transport.sendVideo(packet.array(), important);
        }
    }

    private synchronized void acceptConfig(byte[] payload) {
        if (payload == null || payload.length < 8) return;
        try {
            ByteBuffer b = ByteBuffer.wrap(payload);
            int n0 = b.getInt();
            if (n0 < 1 || n0 > b.remaining() - 4) return;
            byte[] c0 = new byte[n0];
            b.get(c0);
            int n1 = b.getInt();
            if (n1 < 0 || n1 > b.remaining()) return;
            byte[] c1 = new byte[n1];
            b.get(c1);

            int width = remoteWidth;
            int height = remoteHeight;
            if (b.remaining() >= 8) {
                int w = b.getInt();
                int h = b.getInt();
                if (w >= 320 && w <= 3840 && h >= 240 && h <= 2160) {
                    width = w;
                    height = h;
                }
            }

            boolean changed = !Arrays.equals(remoteCsd0, c0)
                    || !Arrays.equals(remoteCsd1, c1)
                    || remoteWidth != width
                    || remoteHeight != height;
            remoteCsd0 = c0;
            remoteCsd1 = c1;
            remoteWidth = width;
            remoteHeight = height;
            if (changed || decoder == null) rebuildDecoder();
        } catch (Exception ignored) {}
    }

    private synchronized void rebuildDecoder() {
        closeDecoder();
        if (!enabled || capability == null || !capability.decoder) return;
        Surface surface = outputSurface;
        if (surface == null || !surface.isValid()) return;
        if (remoteCsd0 == null || remoteCsd0.length == 0) return;

        try {
            decoder = new H264Codec.Decoder(
                    capability,
                    surface,
                    remoteWidth,
                    remoteHeight,
                    remoteCsd0,
                    remoteCsd1,
                    new H264Codec.DecoderListener() {
                        @Override public void onFrameRendered() {
                            recordRenderedFrame();
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastFrameNotifyMs >= 500L) {
                                lastFrameNotifyMs = now;
                                if (listener != null) listener.onFrameRendered();
                            }
                        }

                        @Override public void onKeyFrameNeeded() {
                            requestRemoteKeyFrame();
                        }

                        @Override public void onDecoderError(String message) {
                            Surface current = outputSurface;
                            if (enabled && current != null && current.isValid()) {
                                fatal("Decoder: " + message);
                            }
                        }
                    });
            decoder.start();
            decoderStartedMs = SystemClock.elapsedRealtime();
            QuietLog.log("H264", "decoder_rebuilt",
                    "remote=" + remoteWidth + "x" + remoteHeight);
            // A Surface can appear after the latest keyframe already passed.
            // Ask for a fresh one so first picture arrives immediately.
            requestRemoteKeyFrame();
        } catch (Exception e) {
            fatal("Decoder setup: " + safeMessage(e));
        }
    }

    private synchronized void closeDecoder() {
        if (decoder != null) {
            try { decoder.close(); } catch (Exception ignored) {}
            decoder = null;
        }
    }

    private void adaptBitrate() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAdaptAtMs < 1000L) return;
        lastAdaptAtMs = now;

        int queued = transport.videoQueueDepth();
        int dropped = transport.consumeDroppedVideoPackets();
        int min = tierMinBitrate(qualityTier);
        int max = tierMaxBitrate(qualityTier);
        int next = adaptiveBitrate;

        boolean severe = dropped > 0 || queued > 125;
        boolean moderate = queued > 55;

        if (severe) {
            next = Math.max(min, adaptiveBitrate - Math.max(250_000, adaptiveBitrate / 5));
            stableAdaptSamples = 0;
            upgradeStableSamples = 0;
            congestedSamples++;
            transport.discardQueuedVideo();
            requestKeyFrame();
        } else if (moderate) {
            next = Math.max(min, adaptiveBitrate - Math.max(150_000, adaptiveBitrate / 10));
            stableAdaptSamples = 0;
            upgradeStableSamples = 0;
            congestedSamples++;
        } else if (queued < 14) {
            congestedSamples = Math.max(0, congestedSamples - 1);
            stableAdaptSamples++;
            if (dropped == 0 && queued < 10
                    && adaptiveBitrate >= (int)(max * 0.82f)) {
                upgradeStableSamples++;
            } else {
                upgradeStableSamples = 0;
            }
            if (stableAdaptSamples >= 3) {
                next = Math.min(max, adaptiveBitrate + Math.max(120_000, adaptiveBitrate / 12));
                stableAdaptSamples = 0;
            }
        } else {
            stableAdaptSamples = 0;
            upgradeStableSamples = 0;
            congestedSamples = Math.max(0, congestedSamples - 1);
        }

        if (next != adaptiveBitrate) {
            adaptiveBitrate = next;
            H264Codec.Encoder e;
            synchronized (this) { e = encoder; }
            if (e != null) e.setBitrate(next);
        }

        long sinceTier = now - lastTierChangeMs;
        if (qualityTier < MAX_TIER
                && sinceTier >= 6_000L
                && congestedSamples >= 3
                && adaptiveBitrate <= min + 150_000) {
            requestQualityTier(qualityTier + 1);
            return;
        }

        // Climb back slowly so a marginal network does not bounce between
        // resolutions. Require a sustained clean queue and near-top bitrate.
        if (qualityTier > TIER_HD
                && sinceTier >= 15_000L
                && upgradeStableSamples >= 8) {
            upgradeStableSamples = 0;
            requestQualityTier(qualityTier - 1);
        }
    }

    private void requestQualityTier(int requestedTier) {
        int tier = clampTier(requestedTier);
        if (tier == qualityTier) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastTierRequestMs < 4_000L) return;
        lastTierRequestMs = now;
        if (listener != null) {
            try { listener.onQualityTierRequested(tier); } catch (Exception ignored) {}
        }
    }

    synchronized boolean applyQualityTier(int tier) {
        int next = clampTier(tier);
        if (next == qualityTier) return false;
        qualityTier = next;
        adaptiveBitrate = tierStartBitrate(next);
        stableAdaptSamples = 0;
        congestedSamples = 0;
        upgradeStableSamples = 0;
        lastAdaptAtMs = 0L;
        lastTierChangeMs = SystemClock.elapsedRealtime();
        assemblies.clear();
        transport.discardQueuedVideo();
        return true;
    }

    static int tierWidth(int tier) { return TIER_WIDTH[clampTier(tier)]; }
    static int tierHeight(int tier) { return TIER_HEIGHT[clampTier(tier)]; }
    private static int tierStartBitrate(int tier) { return TIER_START_BITRATE[clampTier(tier)]; }
    private static int tierMinBitrate(int tier) { return TIER_MIN_BITRATE[clampTier(tier)]; }
    private static int tierMaxBitrate(int tier) { return TIER_MAX_BITRATE[clampTier(tier)]; }
    private static int clampTier(int tier) { return Math.max(TIER_HD, Math.min(MAX_TIER, tier)); }

    int qualityTier() { return qualityTier; }
    int currentWidth() { return tierWidth(qualityTier); }
    int currentHeight() { return tierHeight(qualityTier); }
    String qualityLabel() {
        switch (qualityTier) {
            case TIER_BALANCED: return "Balanced";
            case TIER_SMOOTH: return "Smooth";
            default: return "HD";
        }
    }

    private void cleanupAssemblies() {
        long now = SystemClock.elapsedRealtime();
        int lostCount = 0;
        for (Map.Entry<Integer, Assembly> e : assemblies.entrySet()) {
            Assembly a = e.getValue();
            if (now - a.createdAtMs > ASSEMBLY_MAX_AGE_MS
                    && assemblies.remove(e.getKey(), a)
                    && (a.flags & FLAG_CONFIG) == 0) {
                lostCount++;
            }
        }
        if (assemblies.size() > 12) {
            List<Map.Entry<Integer, Assembly>> entries = new ArrayList<>(assemblies.entrySet());
            entries.sort(Comparator.comparingLong(x -> x.getValue().createdAtMs));
            for (int i = 0; i < entries.size() - 12; i++) {
                Assembly a = entries.get(i).getValue();
                if (assemblies.remove(entries.get(i).getKey(), a)
                        && (a.flags & FLAG_CONFIG) == 0) {
                    lostCount++;
                }
            }
        }
        if (lostCount > 0) {
            lostVideoUnits.addAndGet(lostCount);
            H264Codec.Decoder d;
            synchronized (this) { d = decoder; }
            if (d != null) d.requestResync();
            requestRemoteKeyFrame();
        }
    }

    void forceRemoteKeyFrame() {
        lastKeyFrameRequestMs = 0L;
        requestRemoteKeyFrame();
    }

    private void requestRemoteKeyFrame() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastKeyFrameRequestMs < 600L) return;
        lastKeyFrameRequestMs = now;
        keyFrameRequests.incrementAndGet();
        if (listener != null) {
            try { listener.onKeyFrameNeeded(); } catch (Exception ignored) {}
        }
    }

    private synchronized void recordEncodedFrame() {
        long now = SystemClock.elapsedRealtime();
        lastEncodedFrameMs = now;
        txFramesWindow++;
        long elapsed = now - txFpsWindowStartMs;
        if (elapsed >= 1000L) {
            txFps = txFramesWindow * 1000f / Math.max(1L, elapsed);
            txFramesWindow = 0;
            txFpsWindowStartMs = now;
        }
    }

    private synchronized void recordRenderedFrame() {
        long now = SystemClock.elapsedRealtime();
        lastRenderedFrameMs = now;
        rxFramesWindow++;
        long elapsed = now - rxFpsWindowStartMs;
        if (elapsed >= 1000L) {
            rxFps = rxFramesWindow * 1000f / Math.max(1L, elapsed);
            rxFramesWindow = 0;
            rxFpsWindowStartMs = now;
        }
    }

    int currentBitrate() { return enabled ? adaptiveBitrate : 0; }
    float txFps() {
        return SystemClock.elapsedRealtime() - lastEncodedFrameMs > 1800L ? 0f : txFps;
    }
    float rxFps() {
        return SystemClock.elapsedRealtime() - lastRenderedFrameMs > 1800L ? 0f : rxFps;
    }
    long lostVideoUnits() { return lostVideoUnits.get(); }
    long keyFrameRequests() { return keyFrameRequests.get(); }

    private void fatal(String reason) {
        if (!enabled || !fatalNotified.compareAndSet(false, true)) return;
        if (listener != null) listener.onFatal(reason == null ? "H.264 failure" : reason);
    }

    @Override public synchronized void close() {
        enabled = false;
        stopEncoder();
        closeDecoder();
        assemblies.clear();
        outputSurface = null;
        remoteCsd0 = null;
        remoteCsd1 = null;
        localCsd0 = null;
        localCsd1 = null;
    }

    private static byte[] copyBuffer(MediaFormat format, String key) {
        try {
            ByteBuffer src = format.getByteBuffer(key);
            if (src == null) return null;
            ByteBuffer copy = src.duplicate();
            byte[] out = new byte[copy.remaining()];
            copy.get(out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static final class Assembly {
        final byte[][] parts;
        final int flags;
        final long ptsUs;
        final long createdAtMs = SystemClock.elapsedRealtime();
        int received;

        Assembly(int count, int flags, long ptsUs) {
            this.parts = new byte[count][];
            this.flags = flags;
            this.ptsUs = ptsUs;
        }

        synchronized void put(int index, byte[] data) {
            if (parts[index] == null) {
                parts[index] = data;
                received++;
            }
        }

        synchronized boolean complete() {
            return received == parts.length;
        }

        synchronized byte[] join() {
            int size = 0;
            for (byte[] part : parts) size += part == null ? 0 : part.length;
            byte[] out = new byte[size];
            int offset = 0;
            for (byte[] part : parts) {
                if (part == null) continue;
                System.arraycopy(part, 0, out, offset, part.length);
                offset += part.length;
            }
            return out;
        }
    }
}
