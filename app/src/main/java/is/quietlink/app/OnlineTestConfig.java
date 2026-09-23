package is.quietlink.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OnlineTestConfig {
    private static final String PREFS = "quietlink_online_test";
    private static final String KEY_URL = "rendezvous_url";
    private static final int MAX_URL_CHARS = 512;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;

    public interface ProbeCallback {
        void onResult(ProbeResult result);
    }

    public static final class ProbeResult {
        public final boolean reachable;
        public final String message;

        ProbeResult(boolean reachable, String message) {
            this.reachable = reachable;
            this.message = message == null ? "" : message;
        }
    }

    private OnlineTestConfig() {}

    public static String get(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, "");
    }

    public static String normalize(String raw) throws Exception {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (value.length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("URL is too long");
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);

        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("HTTPS is required");
        }
        if (url.getHost() == null || url.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("Hostname is required");
        }
        if (url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IllegalArgumentException("Credentials, query strings and fragments are not allowed");
        }
        String path = url.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("Use only the rendezvous base hostname");
        }
        return value;
    }

    public static void set(Context context, String raw) throws Exception {
        if (context == null) throw new IllegalArgumentException("Context unavailable");
        String value = normalize(raw);
        SharedPreferences.Editor edit =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (value.isEmpty()) edit.remove(KEY_URL);
        else edit.putString(KEY_URL, value);
        edit.apply();
    }

    public static void probe(String rawUrl, ProbeCallback callback) {
        new Thread(() -> {
            ProbeResult result = probeNow(rawUrl);
            if (callback != null) callback.onResult(result);
        }, "QuietLink-TestRendezvousProbe").start();
    }

    private static ProbeResult probeNow(String rawUrl) {
        HttpURLConnection connection = null;
        try {
            String base = normalize(rawUrl);
            if (base.isEmpty()) {
                return new ProbeResult(false, "No test rendezvous URL is configured.");
            }
            connection = (HttpURLConnection) new URL(base + "/health").openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                QuietLog.log("ONLINE", "test_health", "ok=0 http=" + code);
                return new ProbeResult(false,
                        "Rendezvous health check returned HTTP " + code + ".");
            }

            byte[] body;
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("Health response too large");
                    }
                    out.write(buffer, 0, read);
                }
                body = out.toByteArray();
            }

            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            boolean ok = "quietlink-online".equals(json.optString("service", ""))
                    && "ok".equalsIgnoreCase(json.optString("status", ""));
            if (!ok) {
                QuietLog.log("ONLINE", "test_health", "ok=0 payload=unexpected");
                return new ProbeResult(false,
                        "The HTTPS endpoint responded, but it was not a QuietLink rendezvous service.");
            }

            String build = json.optString("build", "").replaceAll("[^A-Za-z0-9._-]", "");
            QuietLog.log("ONLINE", "test_health", "ok=1");
            return new ProbeResult(true,
                    build.isEmpty()
                            ? "QuietLink rendezvous is reachable over HTTPS."
                            : "QuietLink rendezvous is reachable over HTTPS. Build: " + build);
        } catch (Exception e) {
            QuietLog.log("ONLINE", "test_health",
                    "ok=0 reason=" + e.getClass().getSimpleName());
            return new ProbeResult(false,
                    "Rendezvous could not be reached. The URL is kept local on this phone.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
