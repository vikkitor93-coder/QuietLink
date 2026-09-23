package is.quietlink.app;

import android.content.Context;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AudioEngine implements AutoCloseable {
    public interface TxGate { boolean canTransmit(); }
    public interface LevelListener { void onLevel(float level); }

    public static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 480; // 10 ms
    private static final int PCM_BYTES = FRAME_SAMPLES * 2;
    private static final int WIRE_BYTES = PCM_BYTES + 4;

    // Start with ~100 ms buffered. After this first fill, audio drains continuously.
    private static final int JITTER_START_FRAMES = 10;
    private static final int JITTER_CONCEAL_AFTER = 3;

    private final MediaTransport transport;
    private final TxGate txGate;
    private final LevelListener levelListener;
    private final ArrayBlockingQueue<byte[]> playback = new ArrayBlockingQueue<>(48);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger txFrame = new AtomicInteger(1);
    private final AtomicLong remoteFrames = new AtomicLong(0);
    private final AtomicLong concealedFrames = new AtomicLong(0);
    private final AtomicLong playbackQueueDrops = new AtomicLong(0);
    private final Context context;
    private volatile boolean playbackEnabled = true;
    private final Object recorderLock = new Object();
    private volatile AudioRecord record;
    private AudioTrack track;
    private volatile long lastTxFlowLogMs = 0L;
    private volatile long lastRxFlowLogMs = 0L;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;
    private Thread captureThread, playbackThread;

    private final Object jitterLock = new Object();
    private final TreeMap<Integer, byte[]> jitter = new TreeMap<>(Comparator.comparingInt(Integer::intValue));
    private int expectedRxFrame = -1;
    private boolean jitterPrimed = false;
    private byte[] lastGoodFrame;
    private int consecutiveLosses = 0;

    public AudioEngine(Context context, MediaTransport transport, TxGate txGate, LevelListener levelListener) {
        this.context = context; this.transport = transport; this.txGate = txGate; this.levelListener = levelListener;
    }

    public void start() throws Exception {
        int minOut = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int outBuffer = Math.max(minOut, PCM_BYTES * 20);

        rebuildRecorder("initial");

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat outFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(outFormat)
                .setBufferSizeInBytes(outBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("48 kHz playback unavailable");
        }

        running.set(true);
        track.play();
        QuietLog.log("AUDIO", "engine_start", "rate=48000");
        captureThread = new Thread(this::captureLoop, "QuietLink-Audio-TX");
        playbackThread = new Thread(this::playbackLoop, "QuietLink-Audio-RX");
        captureThread.start();
        playbackThread.start();
    }

    private void rebuildRecorder(String reason) throws Exception {
        synchronized (recorderLock) {
            releaseRecorderLocked();

            int minIn = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int inBuffer = Math.max(minIn, PCM_BYTES * 12);
            AudioFormat inFormat = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            AudioRecord next = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(inFormat)
                    .setBufferSizeInBytes(inBuffer)
                    .build();
            if (next.getState() != AudioRecord.STATE_INITIALIZED) {
                try { next.release(); } catch (Exception ignored) {}
                QuietLog.log("AUDIO", "recorder_init_failed", "reason=" + reason);
                throw new IllegalStateException("48 kHz microphone unavailable");
            }

            record = next;
            preferBuiltInMicrophone(
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE),
                    next);

            int sid = next.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sid);
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sid);
                if (ns != null) ns.setEnabled(true);
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sid);
                if (agc != null) agc.setEnabled(true);
            }
            QuietLog.log("AUDIO", "recorder_ready", "reason=" + reason);
        }
    }

    private void preferBuiltInMicrophone(AudioManager am, AudioRecord target) {
        if (am == null || target == null) return;
        try {
            AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo d : inputs) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    boolean preferred = target.setPreferredDevice(d);
                    QuietLog.log("AUDIO", "preferred_mic",
                            "builtin=" + (preferred ? 1 : 0));
                    SessionBus.status("High-quality media audio • phone microphone");
                    return;
                }
            }
            QuietLog.log("AUDIO", "preferred_mic", "builtin=0");
        } catch (Exception e) {
            QuietLog.log("AUDIO", "preferred_mic_error",
                    "type=" + e.getClass().getSimpleName());
        }
    }

    private void releaseRecorderLocked() {
        try { if (record != null) record.stop(); } catch (Exception ignored) {}
        try { if (record != null) record.release(); } catch (Exception ignored) {}
        record = null;
        try { if (aec != null) aec.release(); } catch (Exception ignored) {}
        try { if (ns != null) ns.release(); } catch (Exception ignored) {}
        try { if (agc != null) agc.release(); } catch (Exception ignored) {}
        aec = null;
        ns = null;
        agc = null;
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] pcm = new byte[PCM_BYTES];
        boolean recording = false;
        boolean gateOpen = false;
        boolean gateOpenedBefore = false;

        try {
            while (running.get()) {
                boolean shouldCapture = txGate.canTransmit();

                if (shouldCapture != gateOpen) {
                    gateOpen = shouldCapture;
                    QuietLog.log("AUDIO", "capture_gate",
                            "open=" + (gateOpen ? 1 : 0));
                    if (!gateOpen) {
                        AudioRecord active = record;
                        if (recording && active != null) {
                            try { active.stop(); } catch (Exception ignored) {}
                        }
                        recording = false;
                    } else {
                        // Some older Android audio HALs do not reliably resume the
                        // same AudioRecord after stop(). Rebuild on a real gate resume
                        // so role swaps / Sleeping Baby do not inherit a dead recorder.
                        if (gateOpenedBefore) {
                            try {
                                rebuildRecorder("gate_resume");
                            } catch (Exception e) {
                                QuietLog.log("AUDIO", "recorder_rebuild_failed",
                                        "reason=gate_resume type="
                                                + e.getClass().getSimpleName());
                            }
                        }
                        gateOpenedBefore = true;
                    }
                }

                if (!shouldCapture) {
                    try { Thread.sleep(20); }
                    catch (InterruptedException e) { break; }
                    continue;
                }

                AudioRecord active = record;
                if (active == null || active.getState() != AudioRecord.STATE_INITIALIZED) {
                    try {
                        rebuildRecorder("missing");
                        active = record;
                    } catch (Exception e) {
                        QuietLog.log("AUDIO", "recorder_rebuild_failed",
                                "reason=missing type=" + e.getClass().getSimpleName());
                        try { Thread.sleep(150); }
                        catch (InterruptedException interrupted) { break; }
                        continue;
                    }
                }

                if (!recording) {
                    try {
                        active.startRecording();
                        if (active.getRecordingState()
                                != AudioRecord.RECORDSTATE_RECORDING) {
                            throw new IllegalStateException("not_recording");
                        }
                        recording = true;
                        QuietLog.log("AUDIO", "recorder_started", "");
                    } catch (Exception e) {
                        QuietLog.log("AUDIO", "recorder_start_failed",
                                "type=" + e.getClass().getSimpleName());
                        try { rebuildRecorder("start_failed"); }
                        catch (Exception rebuild) {
                            QuietLog.log("AUDIO", "recorder_rebuild_failed",
                                    "reason=start_failed type="
                                            + rebuild.getClass().getSimpleName());
                        }
                        recording = false;
                        try { Thread.sleep(150); }
                        catch (InterruptedException interrupted) { break; }
                        continue;
                    }
                }

                int off = 0;
                boolean readFailed = false;
                while (off < pcm.length && running.get() && txGate.canTransmit()) {
                    int n;
                    try {
                        n = active.read(
                                pcm,
                                off,
                                pcm.length - off,
                                AudioRecord.READ_BLOCKING);
                    } catch (Exception e) {
                        QuietLog.log("AUDIO", "recorder_read_exception",
                                "type=" + e.getClass().getSimpleName());
                        n = AudioRecord.ERROR_INVALID_OPERATION;
                    }
                    if (n <= 0) {
                        QuietLog.log("AUDIO", "recorder_read_error",
                                "code=" + n);
                        readFailed = true;
                        break;
                    }
                    off += n;
                }

                if (readFailed) {
                    recording = false;
                    try { rebuildRecorder("read_error"); }
                    catch (Exception e) {
                        QuietLog.log("AUDIO", "recorder_rebuild_failed",
                                "reason=read_error type="
                                        + e.getClass().getSimpleName());
                    }
                    try { Thread.sleep(100); }
                    catch (InterruptedException interrupted) { break; }
                    continue;
                }

                if (off == pcm.length && txGate.canTransmit()) {
                    ByteBuffer wire = ByteBuffer.allocate(WIRE_BYTES);
                    wire.putInt(txFrame.getAndIncrement()).put(pcm);
                    transport.send(MediaTransport.TYPE_AUDIO, wire.array());

                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastTxFlowLogMs >= 5000L) {
                        lastTxFlowLogMs = now;
                        QuietLog.log("AUDIO", "tx_flow", "active=1");
                    }
                }
            }
        } finally {
            AudioRecord active = record;
            if (recording && active != null) {
                try { active.stop(); } catch (Exception ignored) {}
            }
            QuietLog.log("AUDIO", "capture_loop_end", "");
        }
    }

    private void playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (running.get()) {
            try {
                byte[] frame = playback.take();
                if (playbackEnabled) {
                    int written = 0;
                    while (written < frame.length && running.get()) {
                        int n = track.write(frame, written, frame.length - written, AudioTrack.WRITE_BLOCKING);
                        if (n <= 0) break;
                        written += n;
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public void onRemoteAudio(byte[] wire) {
        if (wire.length != WIRE_BYTES) return;
        remoteFrames.incrementAndGet();
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastRxFlowLogMs >= 5000L) {
            lastRxFlowLogMs = now;
            QuietLog.log("AUDIO", "rx_flow", "active=1");
        }
        ByteBuffer b = ByteBuffer.wrap(wire);
        int frameId = b.getInt();
        byte[] pcm = new byte[PCM_BYTES];
        b.get(pcm);
        levelListener.onLevel(rms(pcm));

        synchronized (jitterLock) {
            if (expectedRxFrame < 0) expectedRxFrame = frameId;

            long frameU = Integer.toUnsignedLong(frameId);
            long expectedU = Integer.toUnsignedLong(expectedRxFrame);
            long behind = (expectedU - frameU) & 0xffff_ffffL;
            if (behind > 64 && behind < 0x8000_0000L) return;

            jitter.putIfAbsent(frameId, pcm);

            if (!jitterPrimed) {
                if (jitter.size() < JITTER_START_FRAMES) return;
                jitterPrimed = true;
            }

            drainJitterLocked();

            // Keep pathological network bursts bounded.
            while (jitter.size() > 64) jitter.pollFirstEntry();
        }
    }

    private void drainJitterLocked() {
        while (jitterPrimed) {
            byte[] ready = jitter.remove(expectedRxFrame);
            if (ready != null) {
                consecutiveLosses = 0;
                lastGoodFrame = ready;
                enqueuePlayback(ready);
                expectedRxFrame++;
                continue;
            }

            // Do not stall forever on one lost datagram. Wait for a few future
            // frames first so normal reordering still has time to recover.
            if (jitter.size() >= JITTER_CONCEAL_AFTER) {
                consecutiveLosses++;
                concealedFrames.incrementAndGet();
                enqueuePlayback(conceal(lastGoodFrame, consecutiveLosses));
                expectedRxFrame++;
                continue;
            }
            break;
        }
    }

    private static byte[] conceal(byte[] previous, int lossCount) {
        if (previous == null || lossCount > 4) return new byte[PCM_BYTES];
        byte[] out = new byte[PCM_BYTES];
        double gain = Math.pow(0.72, lossCount);
        for (int i = 0; i + 1 < previous.length; i += 2) {
            short sample = (short)((previous[i] & 0xff) | (previous[i + 1] << 8));
            short faded = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
            out[i] = (byte)(faded & 0xff);
            out[i + 1] = (byte)((faded >>> 8) & 0xff);
        }
        return out;
    }

    private void enqueuePlayback(byte[] pcm) {
        if (!playback.offer(pcm)) {
            playbackQueueDrops.incrementAndGet();
            playback.poll();
            playback.offer(pcm);
        }
    }

    public long remoteFrames() { return remoteFrames.get(); }
    public long concealedFrames() { return concealedFrames.get(); }
    public long playbackQueueDrops() { return playbackQueueDrops.get(); }
    public int jitterDepth() {
        synchronized (jitterLock) { return jitter.size(); }
    }

    private static float rms(byte[] pcm) {
        long sum = 0;
        int count = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short)((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long)s * s;
        }
        if (count == 0) return 0;
        double v = Math.sqrt(sum / (double)count) / 32768.0;
        return (float)Math.min(1.0, v * 4.0);
    }

    public void setPlaybackEnabled(boolean enabled) {
        playbackEnabled = enabled;
        QuietLog.log("AUDIO", "playback_gate",
                "open=" + (enabled ? 1 : 0));
        if (!enabled) playback.clear();
    }

    public void setVolume(float volume) {
        float v = Math.max(0f, Math.min(1f, volume));
        try { if (track != null) track.setVolume(v); } catch (Exception ignored) {}
    }

    @Override public void close() {
        running.set(false);
        if (captureThread != null) captureThread.interrupt();
        if (playbackThread != null) playbackThread.interrupt();
        synchronized (recorderLock) {
            releaseRecorderLocked();
        }
        try { if (track != null) track.stop(); } catch (Exception ignored) {}
        try { if (track != null) track.release(); } catch (Exception ignored) {}
        track = null;
        QuietLog.log("AUDIO", "engine_close", "");
        playback.clear();
        synchronized (jitterLock) {
            jitter.clear();
            expectedRxFrame = -1;
            jitterPrimed = false;
            lastGoodFrame = null;
            consecutiveLosses = 0;
        }
    }
}
