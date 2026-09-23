package is.quietlink.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RendezvousClient implements AutoCloseable {
    private static final long REFRESH_MS = 15_000L;
    private static final long POLL_MS = 2_500L;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    public interface Listener {
        void onPeerMatch(Match match);
        void onStatus(String status);
    }

    public static final class Match {
        public final String peerToken;
        public final int protocolVersion;
        public final JSONArray candidates;

        Match(String peerToken, int protocolVersion, JSONArray candidates) {
            this.peerToken = peerToken;
            this.protocolVersion = protocolVersion;
            this.candidates = candidates == null ? new JSONArray() : candidates;
        }
    }

    private final String baseUrl;
    private final String roomToken;
    private final String peerToken;
    private final boolean host;
    private final int protocolVersion;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String candidatesJson = "[]";
    private Thread worker;

    public RendezvousClient(String baseUrl, String sixDigitCode, boolean host,
                            int protocolVersion, Listener listener) throws Exception {
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("HTTPS rendezvous URL required");
        }
        if (sixDigitCode == null || !sixDigitCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("Invalid pairing code");
        }
        this.baseUrl = trimSlash(baseUrl);
        this.roomToken = deriveRoomToken(sixDigitCode);
        this.peerToken = randomToken();
        this.host = host;
        this.protocolVersion = protocolVersion;
        this.listener = listener;
    }

    public void setCandidates(JSONArray candidates) {
        if (candidates == null) {
            candidatesJson = "[]";
            return;
        }
        String encoded = candidates.toString();
        if (encoded.length() > 6 * 1024) throw new IllegalArgumentException("Candidate list too large");
        candidatesJson = encoded;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this::runLoop, "QuietLink-Rendezvous");
        worker.start();
    }

    private void runLoop() {
        long nextRegister = 0L;
        while (running.get()) {
            long now = System.currentTimeMillis();
            try {
                if (now >= nextRegister) {
                    register();
                    nextRegister = now + REFRESH_MS;
                }
                Match match = poll();
                if (match != null && listener != null) {
                    listener.onPeerMatch(match);
                }
            } catch (Exception e) {
                QuietLog.log("ONLINE", "rendezvous_error",
                        "reason=" + e.getClass().getSimpleName());
                if (listener != null) listener.onStatus("Online rendezvous unavailable • local search continues");
            }

            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }

        try { leave(); } catch (Exception ignored) {}
    }

    private void register() throws Exception {
        JSONObject body = new JSONObject();
        body.put("room", roomToken);
        body.put("peer", peerToken);
        body.put("role", host ? "host" : "join");
        body.put("protocol", protocolVersion);
        body.put("candidates", new JSONArray(candidatesJson));
        request("/v1/register", body);

        QuietLog.log("ONLINE", "rendezvous_register",
                "role=" + (host ? "host" : "join"));
        if (listener != null) listener.onStatus("Online rendezvous ready • waiting for peer candidates");
    }

    private Match poll() throws Exception {
        JSONObject body = new JSONObject();
        body.put("room", roomToken);
        body.put("peer", peerToken);
        JSONObject response = request("/v1/poll", body);
        if (!response.optBoolean("matched", false)) return null;

        String peer = response.optString("peer", "");
        int protocol = response.optInt("protocol", -1);
        JSONArray candidates = response.optJSONArray("candidates");
        if (peer.isEmpty() || protocol <= 0) return null;

        QuietLog.log("ONLINE", "rendezvous_match",
                "protocol=" + protocol + " candidates=" + (candidates == null ? 0 : candidates.length()));
        return new Match(peer, protocol, candidates);
    }

    private void leave() throws Exception {
        JSONObject body = new JSONObject();
        body.put("room", roomToken);
        body.put("peer", peerToken);
        request("/v1/leave", body);
        QuietLog.log("ONLINE", "rendezvous_leave", "");
    }

    private JSONObject request(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(3500);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > 8 * 1024) throw new IllegalArgumentException("Rendezvous request too large");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(encoded);
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Rendezvous HTTP " + code);
            }

            byte[] bytes;
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("Rendezvous response too large");
                    }
                    out.write(buffer, 0, read);
                }
                bytes = out.toByteArray();
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String deriveRoomToken(String code) throws Exception {
        byte[] pairingSecret = Pairing.secret(code);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("quietlink-rendezvous-v1".getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest.digest(pairingSecret);
        java.util.Arrays.fill(pairingSecret, (byte)0);
        return Base64.encodeToString(hash, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static String randomToken() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static String trimSlash(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    @Override public void close() {
        running.set(false);
        if (worker != null) worker.interrupt();
    }
}
