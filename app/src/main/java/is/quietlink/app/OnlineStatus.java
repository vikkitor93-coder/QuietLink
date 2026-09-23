package is.quietlink.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OnlineStatus {
    public static final String PRODUCTION_RENDEZVOUS_URL =
            "https://rendezvousquietlinkvikman.dpdns.org";
    private static final String STATUS_URL =
            "https://vikkitor93-coder.github.io/QuietLink/online-status.json";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final boolean reachable;
        public final boolean callsAvailable;
        public final String message;
        public final String rendezvousUrl;
        public final long checkedAtMs;

        Result(boolean reachable, boolean callsAvailable, String message,
               String rendezvousUrl, long checkedAtMs) {
            this.reachable = reachable;
            this.callsAvailable = callsAvailable;
            this.message = message == null ? "" : message;
            this.rendezvousUrl = rendezvousUrl == null ? "" : rendezvousUrl.trim();
            this.checkedAtMs = checkedAtMs;
        }

        public static Result unchecked() {
            return new Result(false, false, "Checking QuietLink online service…", "", 0L);
        }
    }

    private OnlineStatus() {}

    public static void check(Callback callback) {
        new Thread(() -> {
            Result result = probe();
            if (callback != null) callback.onResult(result);
        }, "QuietLink-OnlineStatus").start();
    }

    private static Result probe() {
        HttpURLConnection connection = null;
        long checkedAt = System.currentTimeMillis();
        try {
            connection = (HttpURLConnection) new URL(STATUS_URL).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                QuietLog.log("ONLINE", "status_probe",
                        "reachable=0 http=" + code);
                return new Result(false, false,
                        "Online service returned HTTP " + code + ". Local connections are still available.",
                        "", checkedAt);
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
                        throw new IllegalStateException("status response too large");
                    }
                    out.write(buffer, 0, read);
                }
                body = out.toByteArray();
            }

            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            boolean serviceOk = "ok".equalsIgnoreCase(json.optString("status", ""));
            boolean callsAvailable = serviceOk && json.optBoolean("onlineCallsAvailable", false);
            String message = json.optString("message", "").trim();
            String rendezvousUrl = json.optString("rendezvousUrl", "").trim();
            if (!rendezvousUrl.isEmpty() && !rendezvousUrl.startsWith("https://")) {
                rendezvousUrl = "";
            }
            if (message.isEmpty()) {
                message = callsAvailable
                        ? "QuietLink online calling is available."
                        : "Online calling is not available right now. Local connections are still available.";
            }

            QuietLog.log("ONLINE", "status_probe",
                    "reachable=" + (serviceOk ? 1 : 0)
                            + " calls=" + (callsAvailable ? 1 : 0));
            return new Result(serviceOk, callsAvailable, message, rendezvousUrl, checkedAt);
        } catch (Exception e) {
            QuietLog.log("ONLINE", "status_probe",
                    "reachable=0 reason=" + e.getClass().getSimpleName());
            return new Result(false, false,
                    "QuietLink online service could not be reached. Check your internet connection or try again later. Local connections are still available.",
                    "", checkedAt);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
