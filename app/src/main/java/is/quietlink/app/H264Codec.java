package is.quietlink.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hardware H.264/AVC codec path used by QuietLink HD video.
 *
 * Camera2 feeds the encoder through a Surface and the decoder renders directly
 * to a Surface. JPEG remains the compatibility fallback when a device cannot
 * sustain the hardware path.
 */
public final class H264Codec {
    public static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    public static final int FPS = 30;
    public static final int START_BITRATE = 4_000_000;
    public static final int I_FRAME_SECONDS = 1;

    private H264Codec() {}

    public static final class Capability {
        public final boolean encoder;
        public final boolean decoder;
        public final boolean hardwareEncoder;
        public final boolean hardwareDecoder;
        public final String encoderName;
        public final String decoderName;

        Capability(boolean encoder, boolean decoder,
                   boolean hardwareEncoder, boolean hardwareDecoder,
                   String encoderName, String decoderName) {
            this.encoder = encoder;
            this.decoder = decoder;
            this.hardwareEncoder = hardwareEncoder;
            this.hardwareDecoder = hardwareDecoder;
            this.encoderName = encoderName;
            this.decoderName = decoderName;
        }

        public boolean usable() {
            return encoder && decoder;
        }

        public String summary() {
            if (!usable()) {
                return "H.264 unavailable • encoder=" + encoder + " decoder=" + decoder;
            }
            return "H.264 720p30 ready"
                    + " • encoder " + shortName(encoderName)
                    + " • decoder " + shortName(decoderName)
                    + (hardwareEncoder && hardwareDecoder ? " • hardware" : " • mixed codec");
        }

        private static String shortName(String name) {
            if (name == null || name.isEmpty()) return "default";
            int last = name.lastIndexOf('.');
            return last >= 0 && last + 1 < name.length() ? name.substring(last + 1) : name;
        }
    }

    public static Capability probe() {
        String encoderName = null;
        String decoderName = null;
        boolean encoderHw = false;
        boolean decoderHw = false;

        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo info : infos) {
                MediaCodecInfo.CodecCapabilities caps;
                try {
                    caps = info.getCapabilitiesForType(MIME);
                } catch (Exception ignored) {
                    continue;
                }

                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video == null || !video.areSizeAndRateSupported(WIDTH, HEIGHT, FPS)) continue;

                boolean hw = isHardwareCodec(info);
                if (info.isEncoder()) {
                    if (!supportsSurfaceInput(caps)) continue;
                    if (encoderName == null || (hw && !encoderHw)) {
                        encoderName = info.getName();
                        encoderHw = hw;
                    }
                } else {
                    if (decoderName == null || (hw && !decoderHw)) {
                        decoderName = info.getName();
                        decoderHw = hw;
                    }
                }
            }
        } catch (Exception ignored) {}

        return new Capability(
                encoderName != null,
                decoderName != null,
                encoderHw,
                decoderHw,
                encoderName,
                decoderName);
    }

    private static boolean supportsSurfaceInput(MediaCodecInfo.CodecCapabilities caps) {
        if (caps == null || caps.colorFormats == null) return false;
        for (int color : caps.colorFormats) {
            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
        }
        return false;
    }

    private static boolean isHardwareCodec(MediaCodecInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated();
        }
        String n = info.getName().toLowerCase(Locale.ROOT);
        return !(n.startsWith("omx.google.")
                || n.startsWith("c2.android.")
                || n.contains(".sw.")
                || n.contains("software"));
    }

    private static void applyBestAvcProfile(MediaFormat format,
                                            MediaCodecInfo.CodecCapabilities caps) {
        if (format == null || caps == null || caps.profileLevels == null) return;
        int bestProfile = -1;
        int bestLevel = -1;
        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
            if (pl == null || pl.level < MediaCodecInfo.CodecProfileLevel.AVCLevel31) continue;
            if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
                bestProfile = pl.profile;
                bestLevel = pl.level;
                break;
            }
            if (bestProfile < 0 && pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain) {
                bestProfile = pl.profile;
                bestLevel = pl.level;
            } else if (bestProfile < 0 && pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                bestProfile = pl.profile;
                bestLevel = pl.level;
            }
        }
        if (bestProfile >= 0) {
            try {
                format.setInteger(MediaFormat.KEY_PROFILE, bestProfile);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        Math.max(MediaCodecInfo.CodecProfileLevel.AVCLevel31, bestLevel));
            } catch (Exception ignored) {}
        }
    }

    public interface EncoderListener {
        void onOutputFormat(MediaFormat format);
        void onAccessUnit(byte[] data, long presentationTimeUs, int flags);
        void onEncoderError(String message);
    }

    /**
     * Hardware encoder wrapper. Camera2 can feed {@link #inputSurface()} directly,
     * avoiding JPEG compression and bitmap allocation on the transmit path.
     */
    public static final class Encoder implements AutoCloseable {
        private final MediaCodec codec;
        private final Surface inputSurface;
        private final EncoderListener listener;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread drainThread;

        public Encoder(Capability capability, EncoderListener listener) throws Exception {
            this(capability, WIDTH, HEIGHT, START_BITRATE, listener);
        }

        public Encoder(Capability capability,
                       int width,
                       int height,
                       int initialBitrate,
                       EncoderListener listener) throws Exception {
            if (capability == null || !capability.encoder) {
                throw new IllegalStateException("No compatible H.264 encoder");
            }
            this.listener = listener;
            codec = capability.encoderName == null
                    ? MediaCodec.createEncoderByType(MIME)
                    : MediaCodec.createByCodecName(capability.encoderName);

            MediaFormat format = MediaFormat.createVideoFormat(
                    MIME, Math.max(320, width), Math.max(240, height));
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(350_000, initialBitrate));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECONDS);
            // Ask the codec stack for real-time behavior. Unsupported hints are
            // deliberately best-effort so compatibility remains broad.
            try { format.setInteger(MediaFormat.KEY_PRIORITY, 0); } catch (Exception ignored) {}
            try { format.setFloat(MediaFormat.KEY_OPERATING_RATE, (float) FPS); } catch (Exception ignored) {}
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try { format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); } catch (Exception ignored) {}
            }

            try {
                MediaCodecInfo.CodecCapabilities caps =
                        codec.getCodecInfo().getCapabilitiesForType(MIME);
                MediaCodecInfo.EncoderCapabilities enc = caps.getEncoderCapabilities();
                applyBestAvcProfile(format, caps);
                if (enc != null && enc.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                }
            } catch (Exception ignored) {}

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
        }

        public Surface inputSurface() {
            return inputSurface;
        }

        public void start() {
            if (!running.compareAndSet(false, true)) return;
            codec.start();
            drainThread = new Thread(this::drainLoop, "QuietLink-H264-Encoder");
            drainThread.setDaemon(true);
            drainThread.start();
        }

        public void setBitrate(int bitsPerSecond) {
            if (!running.get()) return;
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,
                        Math.max(250_000, Math.min(7_000_000, bitsPerSecond)));
                codec.setParameters(b);
            } catch (Exception ignored) {}
        }

        public void requestKeyFrame() {
            if (!running.get()) return;
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                codec.setParameters(b);
            } catch (Exception ignored) {}
        }

        private void drainLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (running.get()) {
                    int index = codec.dequeueOutputBuffer(info, 10_000);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (listener != null) listener.onOutputFormat(codec.getOutputFormat());
                        continue;
                    }
                    if (index < 0) continue;

                    ByteBuffer out = codec.getOutputBuffer(index);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size];
                        out.get(bytes);
                        if (listener != null) {
                            listener.onAccessUnit(bytes, info.presentationTimeUs, info.flags);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            } catch (Exception e) {
                if (running.get() && listener != null) {
                    String m = e.getMessage();
                    listener.onEncoderError(m == null ? e.getClass().getSimpleName() : m);
                }
            }
        }

        @Override public void close() {
            if (!running.getAndSet(false)) {
                try { codec.release(); } catch (Exception ignored) {}
                try { inputSurface.release(); } catch (Exception ignored) {}
                return;
            }
            if (drainThread != null) drainThread.interrupt();
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            try { inputSurface.release(); } catch (Exception ignored) {}
            drainThread = null;
        }
    }

    public interface DecoderListener {
        void onFrameRendered();
        void onKeyFrameNeeded();
        void onDecoderError(String message);
    }

    /**
     * Hardware decoder that renders straight to a Surface. This avoids creating
     * Bitmaps for every received video frame and keeps the receive path close to
     * the codec/display hardware.
     */
    public static final class Decoder implements AutoCloseable {
        private final MediaCodec codec;
        private final DecoderListener listener;
        private final ArrayBlockingQueue<AccessUnit> input = new ArrayBlockingQueue<>(8);
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean resyncRequested = new AtomicBoolean(false);
        private volatile boolean waitingForKeyFrame = true;
        private Thread worker;

        public Decoder(Capability capability,
                       Surface outputSurface,
                       byte[] csd0,
                       byte[] csd1,
                       DecoderListener listener) throws Exception {
            this(capability, outputSurface, WIDTH, HEIGHT, csd0, csd1, listener);
        }

        public Decoder(Capability capability,
                       Surface outputSurface,
                       int width,
                       int height,
                       byte[] csd0,
                       byte[] csd1,
                       DecoderListener listener) throws Exception {
            if (capability == null || !capability.decoder) {
                throw new IllegalStateException("No compatible H.264 decoder");
            }
            if (outputSurface == null || !outputSurface.isValid()) {
                throw new IllegalArgumentException("Video output surface is unavailable");
            }
            this.listener = listener;
            codec = capability.decoderName == null
                    ? MediaCodec.createDecoderByType(MIME)
                    : MediaCodec.createByCodecName(capability.decoderName);

            MediaFormat format = MediaFormat.createVideoFormat(
                    MIME, Math.max(320, width), Math.max(240, height));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
            if (csd0 != null && csd0.length > 0) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            }
            if (csd1 != null && csd1.length > 0) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            }
            try { format.setInteger(MediaFormat.KEY_PRIORITY, 0); } catch (Exception ignored) {}
            try { format.setFloat(MediaFormat.KEY_OPERATING_RATE, (float) FPS); } catch (Exception ignored) {}
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try { format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); }
                catch (Exception ignored) {}
            }

            codec.configure(format, outputSurface, null, 0);
        }

        public void start() {
            if (!running.compareAndSet(false, true)) return;
            codec.start();
            worker = new Thread(this::decodeLoop, "QuietLink-H264-Decoder");
            worker.setDaemon(true);
            worker.setPriority(Thread.MAX_PRIORITY);
            worker.start();
        }

        public void queue(byte[] data, long presentationTimeUs, boolean keyFrame) {
            if (!running.get() || data == null || data.length == 0) return;
            if (waitingForKeyFrame && !keyFrame) return;
            AccessUnit unit = new AccessUnit(data, presentationTimeUs, keyFrame);
            if (!input.offer(unit)) {
                requestResync();
                if (keyFrame) input.offer(unit);
            }
        }

        public void requestResync() {
            if (!running.get()) return;
            input.clear();
            waitingForKeyFrame = true;
            resyncRequested.set(true);
            if (listener != null) {
                try { listener.onKeyFrameNeeded(); } catch (Exception ignored) {}
            }
        }

        private void decodeLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (running.get()) {
                    if (resyncRequested.getAndSet(false)) {
                        try { codec.flush(); } catch (Exception ignored) {}
                    }

                    AccessUnit unit = input.poll(5, TimeUnit.MILLISECONDS);
                    if (unit != null) {
                        if (waitingForKeyFrame && !unit.keyFrame) continue;
                        int inIndex = codec.dequeueInputBuffer(8_000);
                        if (inIndex >= 0) {
                            ByteBuffer in = codec.getInputBuffer(inIndex);
                            if (in != null && in.capacity() >= unit.data.length) {
                                in.clear();
                                in.put(unit.data);
                                codec.queueInputBuffer(inIndex, 0, unit.data.length,
                                        unit.presentationTimeUs, 0);
                                if (unit.keyFrame) waitingForKeyFrame = false;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, 0,
                                        unit.presentationTimeUs, 0);
                                requestResync();
                            }
                        } else {
                            // Do not silently lose a reference frame when the
                            // hardware decoder is briefly saturated.
                            requestResync();
                        }
                    }

                    while (running.get()) {
                        int outIndex = codec.dequeueOutputBuffer(info, 0);
                        if (outIndex >= 0) {
                            codec.releaseOutputBuffer(outIndex, true);
                            if (listener != null) listener.onFrameRendered();
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                running.set(false);
                                break;
                            }
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                                || outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            continue;
                        } else {
                            break;
                        }
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (running.get() && listener != null) {
                    String m = e.getMessage();
                    listener.onDecoderError(m == null ? e.getClass().getSimpleName() : m);
                }
            }
        }

        @Override public void close() {
            if (!running.getAndSet(false)) {
                try { codec.release(); } catch (Exception ignored) {}
                input.clear();
                return;
            }
            if (worker != null) worker.interrupt();
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            input.clear();
            worker = null;
        }

        private static final class AccessUnit {
            final byte[] data;
            final long presentationTimeUs;
            final boolean keyFrame;

            AccessUnit(byte[] data, long presentationTimeUs, boolean keyFrame) {
                this.data = data;
                this.presentationTimeUs = presentationTimeUs;
                this.keyFrame = keyFrame;
            }
        }
    }

}
