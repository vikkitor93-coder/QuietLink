package is.quietlink.app;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Socket-shaped reliable byte stream carried through the rendezvous service.
 *
 * The rendezvous only sees opaque QL5 handshake bytes and already-encrypted
 * control/chat frames. It never terminates QuietLink crypto and never receives
 * media. Messages are sequence-numbered and explicitly acknowledged so an HTTP
 * timeout cannot silently duplicate or lose stream bytes.
 */
final class RendezvousRelaySocket extends Socket {
    private static final int MAX_RELAY_RESPONSE_BYTES = 32 * 1024;
    private static final int MAX_RELAY_CHUNK_BYTES = 20 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int LONG_POLL_READ_TIMEOUT_MS = 6500;

    private final String baseUrl;
    private final String roomToken;
    private final String localPeerToken;
    private final String remotePeerToken;
    private final InetAddress peerAddress;
    private final RelayInputStream relayIn = new RelayInputStream();
    private final RelayOutputStream relayOut = new RelayOutputStream();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile int soTimeoutMs = 0;

    RendezvousRelaySocket(String baseUrl, String roomToken,
                          String localPeerToken, String remotePeerToken,
                          InetAddress peerAddress) throws IOException {
        super();
        if (baseUrl == null || !baseUrl.startsWith("https://")) {
            throw new IOException("HTTPS relay URL required");
        }
        if (!validToken(roomToken) || !validToken(localPeerToken) || !validToken(remotePeerToken)) {
            throw new IOException("Invalid relay token");
        }
        this.baseUrl = trimSlash(baseUrl);
        this.roomToken = roomToken;
        this.localPeerToken = localPeerToken;
        this.remotePeerToken = remotePeerToken;
        this.peerAddress = peerAddress;
    }

    @Override public InputStream getInputStream() throws IOException {
        ensureOpen();
        return relayIn;
    }

    @Override public OutputStream getOutputStream() throws IOException {
        ensureOpen();
        return relayOut;
    }

    @Override public synchronized void setSoTimeout(int timeout) throws SocketException {
        if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
        soTimeoutMs = timeout;
    }

    @Override public synchronized int getSoTimeout() {
        return soTimeoutMs;
    }

    @Override public void setTcpNoDelay(boolean on) {}
    @Override public void setKeepAlive(boolean on) {}
    @Override public boolean getTcpNoDelay() { return true; }
    @Override public boolean getKeepAlive() { return true; }
    @Override public InetAddress getInetAddress() { return peerAddress; }
    @Override public boolean isConnected() { return !closed.get(); }
    @Override public boolean isClosed() { return closed.get(); }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { relayIn.close(); } catch (Exception ignored) {}
        try { relayOut.close(); } catch (Exception ignored) {}
    }

    private void ensureOpen() throws SocketException {
        if (closed.get()) throw new SocketException("Relay socket closed");
    }

    private JSONObject post(String path, JSONObject body, int readTimeoutMs) throws IOException {
        ensureOpen();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(Math.max(1000, readTimeoutMs));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > 32 * 1024) throw new IOException("Relay request too large");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(encoded);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Relay HTTP " + status);
            }

            byte[] bytes;
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RELAY_RESPONSE_BYTES) {
                        throw new IOException("Relay response too large");
                    }
                    out.write(buffer, 0, read);
                }
                bytes = out.toByteArray();
            }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Relay request failed", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void sendChunk(long seq, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return;
        if (bytes.length > MAX_RELAY_CHUNK_BYTES) throw new IOException("Relay chunk too large");

        JSONObject body = new JSONObject();
        try {
            body.put("room", roomToken);
            body.put("peer", localPeerToken);
            body.put("to", remotePeerToken);
            body.put("seq", seq);
            body.put("data", Base64.encodeToString(
                    bytes, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING));
        } catch (Exception e) {
            throw new IOException("Relay encode failed", e);
        }

        IOException last = null;
        for (int attempt = 0; attempt < 3 && !closed.get(); attempt++) {
            try {
                JSONObject response = post("/v1/relay-send", body, 4500);
                if (response.optBoolean("ok", false)) return;
                last = new IOException("Relay send rejected");
            } catch (IOException e) {
                last = e;
            }
            try { Thread.sleep(120L * (attempt + 1)); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Relay send interrupted", e);
            }
        }
        throw last == null ? new IOException("Relay send failed") : last;
    }

    private RelayMessage pollMessage(long expectedSeq, int readTimeoutMs) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("room", roomToken);
            body.put("peer", localPeerToken);
            body.put("from", remotePeerToken);
        } catch (Exception e) {
            throw new IOException("Relay poll encode failed", e);
        }

        JSONObject response = post("/v1/relay-poll", body, readTimeoutMs);
        if (!response.optBoolean("ok", false)) throw new IOException("Relay poll rejected");
        if (!response.optBoolean("peerPresent", true)) throw new EOFException("Relay peer unavailable");

        String data = response.optString("data", "");
        if (data.isEmpty()) return null;
        long seq = response.optLong("seq", -1L);
        if (seq <= 0L) throw new IOException("Invalid relay sequence");
        byte[] decoded;
        try {
            decoded = Base64.decode(data, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception e) {
            throw new IOException("Invalid relay payload", e);
        }
        if (decoded.length <= 0 || decoded.length > MAX_RELAY_CHUNK_BYTES) {
            throw new IOException("Invalid relay chunk");
        }
        if (seq > expectedSeq) throw new IOException("Relay sequence gap");
        return new RelayMessage(seq, decoded);
    }

    private void ackMessage(long seq) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("room", roomToken);
            body.put("peer", localPeerToken);
            body.put("from", remotePeerToken);
            body.put("seq", seq);
        } catch (Exception e) {
            throw new IOException("Relay ack encode failed", e);
        }

        IOException last = null;
        for (int attempt = 0; attempt < 3 && !closed.get(); attempt++) {
            try {
                JSONObject response = post("/v1/relay-ack", body, 4500);
                if (response.optBoolean("ok", false)) return;
                last = new IOException("Relay ack rejected");
            } catch (IOException e) {
                last = e;
            }
            try { Thread.sleep(100L * (attempt + 1)); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Relay ack interrupted", e);
            }
        }
        throw last == null ? new IOException("Relay ack failed") : last;
    }

    private final class RelayInputStream extends InputStream {
        private ByteArrayInputStream current;
        private long expectedSeq = 1L;
        private boolean inputClosed = false;

        @Override public synchronized int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException("buffer");
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            if (inputClosed || closed.get()) return -1;

            long timeout = soTimeoutMs;
            long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE;
            while (current == null || current.available() == 0) {
                current = null;
                if (closed.get() || inputClosed) return -1;
                long remaining = deadline == Long.MAX_VALUE
                        ? LONG_POLL_READ_TIMEOUT_MS
                        : deadline - System.currentTimeMillis();
                if (remaining <= 0) throw new SocketTimeoutException("Relay read timed out");
                int httpTimeout = (int)Math.max(1000L,
                        Math.min(LONG_POLL_READ_TIMEOUT_MS, remaining + 500L));

                RelayMessage message;
                try {
                    message = pollMessage(expectedSeq, httpTimeout);
                } catch (SocketTimeoutException e) {
                    if (deadline != Long.MAX_VALUE && System.currentTimeMillis() >= deadline) throw e;
                    continue;
                }
                if (message == null) continue;
                if (message.seq < expectedSeq) {
                    ackMessage(message.seq);
                    continue;
                }
                ackMessage(message.seq);
                expectedSeq++;
                current = new ByteArrayInputStream(message.data);
            }
            return current.read(b, off, Math.min(len, current.available()));
        }

        @Override public synchronized void close() {
            inputClosed = true;
            current = null;
        }
    }

    private final class RelayOutputStream extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private long nextSeq = 1L;
        private boolean outputClosed = false;

        @Override public synchronized void write(int b) throws IOException {
            ensureWritable(1);
            pending.write(b);
        }

        @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException("buffer");
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (len == 0) return;
            int cursor = off;
            int remaining = len;
            while (remaining > 0) {
                int room = MAX_RELAY_CHUNK_BYTES - pending.size();
                if (room == 0) flushChunk();
                int copy = Math.min(remaining, MAX_RELAY_CHUNK_BYTES - pending.size());
                ensureWritable(copy);
                pending.write(b, cursor, copy);
                cursor += copy;
                remaining -= copy;
                if (pending.size() == MAX_RELAY_CHUNK_BYTES) flushChunk();
            }
        }

        private void ensureWritable(int incoming) throws IOException {
            ensureOpen();
            if (outputClosed) throw new IOException("Relay output closed");
            if (incoming < 0 || pending.size() + incoming > MAX_RELAY_CHUNK_BYTES) {
                throw new IOException("Relay output overflow");
            }
        }

        private void flushChunk() throws IOException {
            if (pending.size() == 0) return;
            byte[] bytes = pending.toByteArray();
            sendChunk(nextSeq, bytes);
            nextSeq++;
            pending.reset();
        }

        @Override public synchronized void flush() throws IOException {
            ensureOpen();
            if (outputClosed) throw new IOException("Relay output closed");
            flushChunk();
        }

        @Override public synchronized void close() throws IOException {
            if (outputClosed) return;
            if (!closed.get() && pending.size() > 0) flushChunk();
            outputClosed = true;
            pending.reset();
        }
    }

    private static final class RelayMessage {
        final long seq;
        final byte[] data;
        RelayMessage(long seq, byte[] data) {
            this.seq = seq;
            this.data = data;
        }
    }

    private static boolean validToken(String value) {
        return value != null && value.length() >= 16 && value.length() <= 256
                && value.matches("[A-Za-z0-9_\\-=]+");
    }

    private static String trimSlash(String url) {
        String value = url;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
