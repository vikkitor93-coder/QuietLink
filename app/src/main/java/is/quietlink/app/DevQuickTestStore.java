package is.quietlink.app;

import android.content.Context;

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

public final class DevQuickTestStore {
    private static final String FILE_NAME = "quietlink_quick_app_test.txt";
    private static final int MAX_CHARS = 64 * 1024;

    private DevQuickTestStore() {}

    public static synchronized void save(Context context, String report) {
        if (context == null || report == null || report.trim().isEmpty()) return;
        String body = report.trim();
        if (body.length() > MAX_CHARS) body = body.substring(0, MAX_CHARS);
        String payload = "QuietLink Quick App Test export\n"
                + "Generated: "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "\n"
                + "Privacy-safe: no peer/device names, network addresses, room codes, identifiers, keys, chat or media content.\n\n"
                + body + "\n";
        try (FileOutputStream fos = new FileOutputStream(
                new File(context.getFilesDir(), FILE_NAME), false);
             OutputStreamWriter writer = new OutputStreamWriter(
                     fos, StandardCharsets.UTF_8)) {
            writer.write(payload);
            writer.flush();
        } catch (Exception ignored) {}
    }

    public static synchronized String read(Context context) {
        if (context == null) return "";
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists() || !file.isFile()) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < MAX_CHARS) {
                out.append(line).append('\n');
            }
        } catch (Exception ignored) {
            return "";
        }
        return out.toString();
    }

    public static synchronized boolean hasReport(Context context) {
        return !read(context).trim().isEmpty();
    }
}
