package is.quietlink.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java evaluator for the developer in-call Quick App Test.
 *
 * This class deliberately has no Android dependencies so the same regression
 * rules can be exercised in CI. It only consumes privacy-safe technical state.
 */
public final class DevQuickTest {
    public static final long SAMPLE_MS = 6000L;

    public enum Status { PASS, WARN, FAIL, SKIP }

    public static final class Metrics {
        public final boolean sessionActive;
        public final boolean connected;
        public final boolean verificationPresent;
        public final boolean microphonePermission;
        public final boolean cameraPermission;
        public final int mode;
        public final boolean babyStation;
        public final boolean sleepingBaby;
        public final boolean babySettingsKnown;
        public final boolean localVideoExpected;
        public final boolean remoteVideoExpected;
        public final boolean microphoneTxExpected;
        public final boolean audioRxExpected;
        public final boolean canonicalVideoRotation;
        public final boolean remoteSurfaceValid;
        public final boolean localSurfaceValid;
        public final boolean fileTransferActive;
        public final int fileTransferPercent;
        public final String state;
        public final String network;
        public final String codec;
        public final long heartbeatAgeMs;
        public final long rttMs;
        public final int audioQueue;
        public final int videoQueue;
        public final int audioJitterFrames;
        public final long audioTxPackets;
        public final long audioRxPackets;
        public final long videoTxPackets;
        public final long videoRxPackets;
        public final long audioConcealedFrames;
        public final long audioPlaybackDrops;
        public final long videoDroppedTxPackets;
        public final long videoLostRxUnits;
        public final float videoRxFps;
        public final int recoveries;
        public final int remoteRotation;
        public final int localRotation;

        public Metrics(
                boolean sessionActive, boolean connected, boolean verificationPresent,
                boolean microphonePermission, boolean cameraPermission,
                int mode, boolean babyStation, boolean sleepingBaby,
                boolean babySettingsKnown,
                boolean localVideoExpected, boolean remoteVideoExpected,
                boolean microphoneTxExpected, boolean audioRxExpected,
                boolean canonicalVideoRotation,
                boolean remoteSurfaceValid, boolean localSurfaceValid,
                boolean fileTransferActive, int fileTransferPercent,
                String state, String network, String codec,
                long heartbeatAgeMs, long rttMs,
                int audioQueue, int videoQueue, int audioJitterFrames,
                long audioTxPackets, long audioRxPackets,
                long videoTxPackets, long videoRxPackets,
                long audioConcealedFrames, long audioPlaybackDrops,
                long videoDroppedTxPackets, long videoLostRxUnits,
                float videoRxFps, int recoveries,
                int remoteRotation, int localRotation) {
            this.sessionActive = sessionActive;
            this.connected = connected;
            this.verificationPresent = verificationPresent;
            this.microphonePermission = microphonePermission;
            this.cameraPermission = cameraPermission;
            this.mode = mode;
            this.babyStation = babyStation;
            this.sleepingBaby = sleepingBaby;
            this.babySettingsKnown = babySettingsKnown;
            this.localVideoExpected = localVideoExpected;
            this.remoteVideoExpected = remoteVideoExpected;
            this.microphoneTxExpected = microphoneTxExpected;
            this.audioRxExpected = audioRxExpected;
            this.canonicalVideoRotation = canonicalVideoRotation;
            this.remoteSurfaceValid = remoteSurfaceValid;
            this.localSurfaceValid = localSurfaceValid;
            this.fileTransferActive = fileTransferActive;
            this.fileTransferPercent = Math.max(0, Math.min(100, fileTransferPercent));
            this.state = safe(state);
            this.network = safe(network);
            this.codec = safe(codec);
            this.heartbeatAgeMs = heartbeatAgeMs;
            this.rttMs = rttMs;
            this.audioQueue = audioQueue;
            this.videoQueue = videoQueue;
            this.audioJitterFrames = audioJitterFrames;
            this.audioTxPackets = audioTxPackets;
            this.audioRxPackets = audioRxPackets;
            this.videoTxPackets = videoTxPackets;
            this.videoRxPackets = videoRxPackets;
            this.audioConcealedFrames = audioConcealedFrames;
            this.audioPlaybackDrops = audioPlaybackDrops;
            this.videoDroppedTxPackets = videoDroppedTxPackets;
            this.videoLostRxUnits = videoLostRxUnits;
            this.videoRxFps = videoRxFps;
            this.recoveries = recoveries;
            this.remoteRotation = normalizeRotation(remoteRotation);
            this.localRotation = normalizeRotation(localRotation);
        }
    }

    public static final class Check {
        public final String section;
        public final String name;
        public final Status status;
        public final String detail;

        Check(String section, String name, Status status, String detail) {
            this.section = safe(section);
            this.name = safe(name);
            this.status = status == null ? Status.WARN : status;
            this.detail = safe(detail);
        }
    }

    public static final class Report {
        public final List<Check> checks;
        public final int passCount;
        public final int warnCount;
        public final int failCount;
        public final int skipCount;

        Report(List<Check> checks) {
            this.checks = Collections.unmodifiableList(new ArrayList<>(checks));
            int p = 0, w = 0, f = 0, s = 0;
            for (Check c : checks) {
                if (c.status == Status.PASS) p++;
                else if (c.status == Status.FAIL) f++;
                else if (c.status == Status.SKIP) s++;
                else w++;
            }
            passCount = p;
            warnCount = w;
            failCount = f;
            skipCount = s;
        }

        public Status overall() {
            if (failCount > 0) return Status.FAIL;
            if (warnCount > 0) return Status.WARN;
            return Status.PASS;
        }

        public String render(String versionName) {
            StringBuilder out = new StringBuilder();
            out.append("QuietLink Quick App Test");
            if (versionName != null && !versionName.trim().isEmpty()) {
                out.append(" • v").append(versionName.trim());
            }
            out.append("\n");
            out.append("6-second safe in-call scan • no settings/toggles changed\n");
            out.append("Overall: ").append(overall())
                    .append(" • ").append(passCount).append(" PASS")
                    .append(" • ").append(warnCount).append(" WARN")
                    .append(" • ").append(failCount).append(" FAIL")
                    .append(" • ").append(skipCount).append(" N/A\n\n");

            String last = null;
            for (Check c : checks) {
                if (!c.section.equals(last)) {
                    if (last != null) out.append('\n');
                    out.append(c.section).append("\n");
                    last = c.section;
                }
                out.append(symbol(c.status)).append(' ').append(c.name);
                if (!c.detail.isEmpty()) out.append(" — ").append(c.detail);
                out.append('\n');
            }

            out.append("\nMANUAL SPOT CHECKS\n");
            out.append("○ Hear peer audio / peer hears you\n");
            out.append("○ Mute/unmute behaves correctly\n");
            out.append("○ Chat message round-trip\n");
            if (hasVisualMode(checks)) {
                out.append("○ Incoming video upright and moving\n");
                out.append("○ Self preview upright/not stretched\n");
                out.append("○ Camera switch + fullscreen round-trip\n");
            }
            out.append("\nPrivacy-safe report: no peer name, address, room code, key or media content.\n");
            return out.toString();
        }
    }

    private DevQuickTest() {}

    public static Report evaluate(Metrics before, Metrics after) {
        if (before == null || after == null) {
            List<Check> bad = new ArrayList<>();
            bad.add(new Check("SESSION", "Scan data", Status.FAIL, "missing sample"));
            return new Report(bad);
        }

        List<Check> checks = new ArrayList<>();

        checks.add(check("SESSION", "Active encrypted session",
                after.sessionActive && after.connected,
                after.sessionActive && after.connected ? "connected" : "not fully connected"));
        checks.add(new Check("SESSION", "Session state",
                "Connected".equals(after.state) ? Status.PASS
                        : ("Reconnecting".equals(after.state) ? Status.FAIL : Status.WARN),
                after.state.isEmpty() ? "unknown" : after.state));
        checks.add(check("SESSION", "Peer verification",
                after.verificationPresent,
                after.verificationPresent ? "verification phrase established" : "missing"));
        checks.add(heartbeat(after.heartbeatAgeMs));
        checks.add(new Check("SESSION", "Round-trip time",
                after.rttMs >= 0L ? Status.PASS : Status.WARN,
                after.rttMs >= 0L ? formatMs(after.rttMs) : "not sampled yet"));
        checks.add(new Check("SESSION", "Network available",
                "Offline".equalsIgnoreCase(after.network) ? Status.FAIL
                        : ("Unknown".equalsIgnoreCase(after.network) || after.network.isEmpty()
                            ? Status.WARN : Status.PASS),
                after.network.isEmpty() ? "unknown" : after.network));

        int recoveryDelta = Math.max(0, after.recoveries - before.recoveries);
        checks.add(new Check("RECOVERY", "No recovery during scan",
                recoveryDelta == 0 ? Status.PASS
                        : (recoveryDelta == 1 ? Status.WARN : Status.FAIL),
                recoveryDelta == 0 ? "stable" : recoveryDelta + " recovery event(s)"));

        checks.add(check("AUDIO", "Microphone permission", after.microphonePermission,
                after.microphonePermission ? "granted" : "missing"));
        long audioTx = delta(after.audioTxPackets, before.audioTxPackets);
        long audioRx = delta(after.audioRxPackets, before.audioRxPackets);
        checks.add(flow("AUDIO", "Audio transmit flow", after.microphoneTxExpected,
                audioTx, true));
        checks.add(flow("AUDIO", "Audio receive flow", after.audioRxExpected,
                audioRx, false));
        checks.add(queue("AUDIO", "Audio TX queue", after.audioQueue, 20, 60, "frames"));
        checks.add(queue("AUDIO", "Audio jitter buffer", after.audioJitterFrames, 30, 80, "frames"));

        long concealedDelta = delta(after.audioConcealedFrames, before.audioConcealedFrames);
        long playbackDropDelta = delta(after.audioPlaybackDrops, before.audioPlaybackDrops);
        checks.add(loss("AUDIO", "Concealed audio loss", concealedDelta, 4, 20));
        checks.add(loss("AUDIO", "Playback queue drops", playbackDropDelta, 0, 8));

        boolean visual = after.mode != SessionServiceMode.VOICE;
        if (!visual) {
            checks.add(new Check("VIDEO", "Video engine", Status.SKIP, "Voice mode"));
        } else {
            checks.add(new Check("VIDEO", "Camera permission",
                    after.localVideoExpected
                            ? (after.cameraPermission ? Status.PASS : Status.FAIL)
                            : Status.SKIP,
                    after.localVideoExpected
                            ? (after.cameraPermission ? "granted" : "missing")
                            : "local camera not expected"));

            Status codecStatus = after.codec.startsWith("H.264") ? Status.PASS
                    : (after.codec.contains("JPEG") ? Status.WARN : Status.FAIL);
            checks.add(new Check("VIDEO", "Video codec", codecStatus,
                    after.codec.isEmpty() ? "none" : after.codec));

            long videoTx = delta(after.videoTxPackets, before.videoTxPackets);
            long videoRx = delta(after.videoRxPackets, before.videoRxPackets);
            checks.add(flow("VIDEO", "Video transmit flow", after.localVideoExpected,
                    videoTx, true));
            checks.add(flow("VIDEO", "Video receive flow", after.remoteVideoExpected,
                    videoRx, false));
            checks.add(new Check("VIDEO", "Rendered video FPS",
                    after.remoteVideoExpected
                            ? (after.videoRxFps >= 1.0f ? Status.PASS : Status.WARN)
                            : Status.SKIP,
                    after.remoteVideoExpected
                            ? String.format(Locale.US, "%.1f fps", after.videoRxFps)
                            : "remote video not expected"));

            boolean h264 = after.codec.startsWith("H.264");
            checks.add(new Check("VIDEO", "Normalized orientation",
                    h264
                            ? (after.canonicalVideoRotation ? Status.PASS : Status.WARN)
                            : Status.SKIP,
                    h264
                            ? (after.canonicalVideoRotation ? "active" : "legacy/compatibility")
                            : "not H.264"));

            checks.add(new Check("VIDEO", "Remote render surface",
                    after.remoteVideoExpected && h264
                            ? (after.remoteSurfaceValid ? Status.PASS : Status.FAIL)
                            : Status.SKIP,
                    after.remoteVideoExpected && h264
                            ? (after.remoteSurfaceValid ? "valid" : "missing/invalid")
                            : "not required"));
            checks.add(new Check("VIDEO", "Local camera surface",
                    after.localVideoExpected && h264
                            ? (after.localSurfaceValid ? Status.PASS : Status.FAIL)
                            : Status.SKIP,
                    after.localVideoExpected && h264
                            ? (after.localSurfaceValid ? "valid" : "missing/invalid")
                            : "not required"));

            checks.add(rotation("VIDEO", "Remote rotation metadata", after.remoteRotation));
            checks.add(rotation("VIDEO", "Local rotation metadata", after.localRotation));
            checks.add(queue("VIDEO", "Video TX queue", after.videoQueue, 36, 100, "packets"));

            long dropped = delta(after.videoDroppedTxPackets, before.videoDroppedTxPackets);
            long lost = delta(after.videoLostRxUnits, before.videoLostRxUnits);
            checks.add(loss("VIDEO", "Dropped TX packets", dropped, 8, 80));
            checks.add(loss("VIDEO", "Lost/incomplete RX units", lost, 2, 20));
        }

        if (after.mode == SessionServiceMode.BABY) {
            checks.add(new Check("BABY MONITOR", "Role state", Status.PASS,
                    after.babyStation ? "Baby Station" : "Parent Station"));
            checks.add(new Check("BABY MONITOR", "Remote baby settings",
                    after.babyStation ? Status.SKIP
                            : (after.babySettingsKnown ? Status.PASS : Status.WARN),
                    after.babyStation ? "local Baby Station"
                            : (after.babySettingsKnown ? "synchronized" : "not confirmed yet")));
            checks.add(new Check("BABY MONITOR", "Sleeping Baby state", Status.PASS,
                    after.sleepingBaby ? "enabled" : "normal Baby mode"));
        } else {
            checks.add(new Check("BABY MONITOR", "Baby-specific state", Status.SKIP,
                    "not in Baby mode"));
        }

        checks.add(new Check("CHAT / FILE", "Diagnostic file transfer",
                after.fileTransferActive ? Status.PASS : Status.PASS,
                after.fileTransferActive
                        ? "active • " + after.fileTransferPercent + "%"
                        : "idle/ready"));
        checks.add(new Check("CHAT / FILE", "Chat round-trip", Status.SKIP,
                "manual spot check; scan does not send messages"));

        return new Report(checks);
    }

    private static Check check(String section, String name, boolean ok, String detail) {
        return new Check(section, name, ok ? Status.PASS : Status.FAIL, detail);
    }

    private static Check heartbeat(long ms) {
        if (ms < 0L) return new Check("SESSION", "Heartbeat", Status.WARN, "not sampled yet");
        if (ms <= 12000L) return new Check("SESSION", "Heartbeat", Status.PASS, formatMs(ms));
        if (ms <= 25000L) return new Check("SESSION", "Heartbeat", Status.WARN, formatMs(ms));
        return new Check("SESSION", "Heartbeat", Status.FAIL, formatMs(ms));
    }

    private static Check flow(String section, String name,
                              boolean expected, long packetDelta, boolean hardFail) {
        if (!expected) return new Check(section, name, Status.SKIP, "off by current mode/state");
        if (packetDelta > 0L) {
            return new Check(section, name, Status.PASS, "+" + packetDelta + " packets");
        }
        return new Check(section, name, hardFail ? Status.FAIL : Status.WARN,
                "no packets observed in sample");
    }

    private static Check queue(String section, String name, int value,
                               int passMax, int warnMax, String units) {
        Status status = value <= passMax ? Status.PASS
                : (value <= warnMax ? Status.WARN : Status.FAIL);
        return new Check(section, name, status, value + " " + units);
    }

    private static Check loss(String section, String name, long value,
                              long passMax, long warnMax) {
        Status status = value <= passMax ? Status.PASS
                : (value <= warnMax ? Status.WARN : Status.FAIL);
        return new Check(section, name, status, "+" + value);
    }

    private static Check rotation(String section, String name, int degrees) {
        boolean valid = degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270;
        return new Check(section, name, valid ? Status.PASS : Status.FAIL,
                degrees + "°");
    }

    private static long delta(long after, long before) {
        return Math.max(0L, after - before);
    }

    private static int normalizeRotation(int degrees) {
        int r = degrees % 360;
        return r < 0 ? r + 360 : r;
    }

    private static String formatMs(long ms) {
        if (ms < 1000L) return ms + " ms";
        return String.format(Locale.US, "%.1f s", ms / 1000f);
    }

    private static String symbol(Status status) {
        switch (status) {
            case PASS: return "✓";
            case FAIL: return "✗";
            case SKIP: return "○";
            default: return "!";
        }
    }

    private static boolean hasVisualMode(List<Check> checks) {
        for (Check c : checks) {
            if ("VIDEO".equals(c.section)
                    && !"Video engine".equals(c.name)
                    && c.status != Status.SKIP) return true;
        }
        return false;
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * Constants mirror SessionService without importing Android-dependent code,
     * keeping this evaluator runnable in plain-Java CI.
     */
    public static final class SessionServiceMode {
        public static final int VOICE = 0;
        public static final int VIDEO = 1;
        public static final int BABY = 2;
        private SessionServiceMode() {}
    }
}
