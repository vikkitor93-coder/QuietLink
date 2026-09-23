package is.quietlink.app;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Privacy-safe QuietLink diagnostic trace.
 *
 * Only call this with controlled technical state. The exporter also performs
 * defensive redaction so network addresses, pairing codes and identifier-like
 * tokens are not included in files a user shares.
 */
public final class QuietLog {
    private static final String FILE_NAME = "quietlink_diagnostic_trace.log";
    private static final long MAX_BYTES = 768L * 1024L;
    private static final long TRIM_AT_BYTES = 384L * 1024L;

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern MAC = Pattern.compile(
            "(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
    private static final Pattern ROOM_CODE = Pattern.compile("\\b\\d{6}\\b");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)\\b[a-f0-9]{16,}\\b|\\b[A-Za-z0-9_-]{32,}\\b");

    private static Context app;
    private static long processStartElapsedMs;
    private static boolean crashHandlerInstalled;

    private QuietLog() {}

    public static synchronized void init(Context context) {
        if (app != null || context == null) return;
        app = context.getApplicationContext();
        processStartElapsedMs = SystemClock.elapsedRealtime();
        installCrashHandler();
        log("APP", "logger_init", "privacy_safe=1");
    }

    private static synchronized void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;

        final Thread.UncaughtExceptionHandler prior =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String type = error == null
                        ? "Unknown"
                        : error.getClass().getSimpleName();
                String site = "unknown";
                int line = -1;
                if (error != null) {
                    StackTraceElement[] stack = error.getStackTrace();
                    if (stack != null) {
                        for (StackTraceElement frame : stack) {
                            if (frame != null
                                    && frame.getClassName() != null
                                    && frame.getClassName().startsWith(
                                            "is.quietlink.app.")) {
                                site = safeWord(
                                        frame.getClassName() + "."
                                                + frame.getMethodName(),
                                        120);
                                line = frame.getLineNumber();
                                break;
                            }
                        }
                    }
                }
                // Never persist Throwable messages: they can contain arbitrary
                // runtime values. Class/site/line are enough for a privacy-safe
                // crash fingerprint.
                log("CRASH", "uncaught_exception",
                        "type=" + safeWord(type, 64)
                                + " site=" + site
                                + " line=" + line);
            } catch (Exception ignored) {}

            if (prior != null) {
                prior.uncaughtException(thread, error);
            }
        });
    }

    public static synchronized void log(String area, String event) {
        log(area, event, "");
    }

    public static synchronized void log(String area, String event, String details) {
        if (app == null) return;
        try {
            File f = new File(app.getFilesDir(), FILE_NAME);
            if (f.exists() && f.length() > MAX_BYTES) rotate(f);

            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - processStartElapsedMs);
            String line = String.format(Locale.US,
                    "%08dms | %-8s | %-28s | %s%n",
                    elapsed,
                    safeWord(area, 16),
                    safeWord(event, 40),
                    redact(details == null ? "" : details));
            try (FileOutputStream fos = new FileOutputStream(f, true);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(line);
            }
        } catch (Exception ignored) {}
    }

    private static void rotate(File f) {
        try {
            byte[] data;
            try (FileInputStream in = new FileInputStream(f)) {
                long skip = Math.max(0L, f.length() - TRIM_AT_BYTES);
                while (skip > 0L) {
                    long n = in.skip(skip);
                    if (n <= 0L) break;
                    skip -= n;
                }
                data = new byte[(int)Math.min(TRIM_AT_BYTES, Math.max(0L, f.length()))];
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off < data.length) {
                    byte[] smaller = new byte[off];
                    System.arraycopy(data, 0, smaller, 0, off);
                    data = smaller;
                }
            }
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write("--- QuietLink trace rotated; newest retained data follows ---\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.write(data);
            }
        } catch (Exception ignored) {
            try { new FileOutputStream(f, false).close(); } catch (Exception ignored2) {}
        }
    }

    public static synchronized String exportProfilesText(Context context) {
        if (app == null) init(context);

        StringBuilder out = new StringBuilder();
        out.append("QuietLink video calibration report\n");
        out.append("Generated: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("\n");
        out.append("Privacy-safe: no peer/device names, network addresses, codes, keys, chat or media content.\n\n");

        out.append("CURRENT CALIBRATION SNAPSHOT\n");
        out.append("Display rotation: ")
                .append(RotationLabConfig.displayRotationDegrees(context))
                .append("°\n");
        out.append("Reported local stream rotation: ")
                .append(SessionBus.localVideoRotation)
                .append("°\n");
        out.append("Reported remote stream rotation: ")
                .append(SessionBus.remoteVideoRotation)
                .append("°\n");
        out.append("Resolved local preview rotation: ")
                .append(RotationLabConfig.resolveLocalPreviewRotation(
                        context, SessionBus.localVideoRotation))
                .append("°\n");
        out.append("Resolved remote rotation: ")
                .append(RotationLabConfig.resolveRemoteRotation(
                        context, SessionBus.remoteVideoRotation))
                .append("°\n");
        out.append("Current tuning: ")
                .append(RotationLabConfig.summary(context))
                .append("\n\n");

        out.append("SAVED VIDEO CALIBRATION PROFILES\n");
        appendProfile(out, context, SessionService.MODE_VIDEO, false);
        appendProfile(out, context, SessionService.MODE_VIDEO, true);
        appendProfile(out, context, SessionService.MODE_BABY, false);
        appendProfile(out, context, SessionService.MODE_BABY, true);
        return out.toString();
    }

    public static synchronized String exportText(Context context) {
        if (app == null) init(context);
        StringBuilder out = new StringBuilder();
        out.append("QuietLink privacy-safe diagnostic log\n");
        out.append("Generated: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("\n");
        out.append("Contains QuietLink technical events only.\n");
        out.append("Excluded by design: IP addresses, peer/device names, device IDs, ")
                .append("fingerprints/keys, room codes, chat text, audio/video content.\n\n");

        out.append(exportProfilesText(context)).append('\n');

        if (app == null) return out.append("(logger unavailable)\n").toString();
        File f = new File(app.getFilesDir(), FILE_NAME);
        if (!f.exists()) return out.append("(no trace entries yet)\n").toString();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(redact(line)).append('\n');
            }
        } catch (Exception e) {
            out.append("(could not read trace)\n");
        }
        return out.toString();
    }

    private static void appendProfile(StringBuilder out, Context context,
                                      int mode, boolean fullscreen) {
        String label = RotationLabConfig.profileLabel(mode, fullscreen);
        if (!RotationLabConfig.hasProfile(context, mode, fullscreen)) {
            out.append("- ").append(label).append(": not saved\n");
            return;
        }
        String summary = RotationLabConfig.profileSummary(context, mode, fullscreen);
        out.append("- ").append(label).append(": saved\n");
        String[] lines = summary.split("\\n");
        for (int i = 1; i < lines.length; i++) {
            out.append("  ").append(lines[i]).append('\n');
        }
    }

    public static synchronized void clear(Context context) {
        if (app == null) init(context);
        if (app == null) return;
        try {
            File f = new File(app.getFilesDir(), FILE_NAME);
            if (f.exists()) new FileOutputStream(f, false).close();
            log("APP", "log_cleared", "");
        } catch (Exception ignored) {}
    }

    private static String safeWord(String value, int max) {
        String s = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String redact(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text.replace('\n', ' ').replace('\r', ' ');
        s = IPV4.matcher(s).replaceAll("[redacted-ip]");
        s = MAC.matcher(s).replaceAll("[redacted-mac]");
        s = ROOM_CODE.matcher(s).replaceAll("[redacted-code]");
        s = TOKEN.matcher(s).replaceAll("[redacted-token]");
        return s;
    }
}
