package is.quietlink.app;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

public final class StunClient {
    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_SUCCESS = 0x0101;
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    public static final class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public JSONObject toCandidateJson() throws Exception {
            JSONObject out = new JSONObject();
            out.put("kind", "srflx");
            out.put("host", host);
            out.put("tcpPort", 0);
            out.put("udpPort", port);
            return out;
        }

        boolean sameMapping(Endpoint other) {
            return other != null && port == other.port && host.equals(other.host);
        }
    }

    private StunClient() {}

    public static Endpoint probe(DatagramSocket socket, String serverHost,
                                 int serverPort, int timeoutMs) throws Exception {
        if (socket == null || socket.isClosed()) throw new IllegalStateException("UDP socket unavailable");
        if (serverHost == null || serverHost.trim().isEmpty()) throw new IllegalArgumentException("Missing STUN host");
        if (serverPort < 1 || serverPort > 65535) throw new IllegalArgumentException("Bad STUN port");

        byte[] tx = new byte[12];
        new SecureRandom().nextBytes(tx);

        ByteBuffer request = ByteBuffer.allocate(20);
        request.putShort((short) BINDING_REQUEST);
        request.putShort((short) 0);
        request.putInt(MAGIC_COOKIE);
        request.put(tx);

        InetAddress destination = InetAddress.getByName(serverHost);
        DatagramPacket outbound = new DatagramPacket(
                request.array(), request.array().length, destination, serverPort);

        int previousTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(Math.max(500, Math.min(timeoutMs, 8000)));
            socket.send(outbound);

            byte[] buffer = new byte[2048];
            DatagramPacket inbound = new DatagramPacket(buffer, buffer.length);
            while (true) {
                socket.receive(inbound);
                Endpoint endpoint = parseResponse(
                        inbound.getData(), inbound.getOffset(), inbound.getLength(), tx);
                if (endpoint != null) return endpoint;
            }
        } finally {
            try { socket.setSoTimeout(previousTimeout); } catch (Exception ignored) {}
        }
    }

    private static Endpoint parseResponse(byte[] data, int offset, int length,
                                          byte[] expectedTransaction) throws Exception {
        if (data == null || length < 20) return null;
        ByteBuffer b = ByteBuffer.wrap(data, offset, length).slice();

        int type = b.getShort() & 0xffff;
        int messageLength = b.getShort() & 0xffff;
        int cookie = b.getInt();
        byte[] transaction = new byte[12];
        b.get(transaction);

        if (type != BINDING_SUCCESS || cookie != MAGIC_COOKIE
                || !Arrays.equals(transaction, expectedTransaction)) return null;
        if (messageLength < 0 || messageLength > b.remaining()) return null;

        int remaining = messageLength;
        while (remaining >= 4 && b.remaining() >= 4) {
            int attrType = b.getShort() & 0xffff;
            int attrLength = b.getShort() & 0xffff;
            remaining -= 4;
            if (attrLength < 0 || attrLength > remaining || attrLength > b.remaining()) return null;

            byte[] value = new byte[attrLength];
            b.get(value);
            remaining -= attrLength;

            int padding = (4 - (attrLength & 3)) & 3;
            if (padding > remaining || padding > b.remaining()) return null;

            if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                Endpoint endpoint = parseAddress(value, true, expectedTransaction);
                if (endpoint != null) return endpoint;
            } else if (attrType == ATTR_MAPPED_ADDRESS) {
                Endpoint endpoint = parseAddress(value, false, expectedTransaction);
                if (endpoint != null) return endpoint;
            }

            b.position(b.position() + padding);
            remaining -= padding;
        }
        return null;
    }

    private static Endpoint parseAddress(byte[] value, boolean xor,
                                         byte[] transaction) throws Exception {
        if (value == null || value.length < 8) return null;
        int family = value[1] & 0xff;
        int port = ((value[2] & 0xff) << 8) | (value[3] & 0xff);
        if (xor) port ^= (MAGIC_COOKIE >>> 16);

        final int addressLength;
        if (family == 0x01) addressLength = 4;
        else if (family == 0x02) addressLength = 16;
        else return null;

        if (value.length < 4 + addressLength || port < 1 || port > 65535) return null;
        byte[] address = Arrays.copyOfRange(value, 4, 4 + addressLength);

        if (xor) {
            byte[] mask = new byte[16];
            ByteBuffer.wrap(mask).putInt(MAGIC_COOKIE).put(transaction);
            for (int i = 0; i < address.length; i++) address[i] ^= mask[i];
        }

        String host = InetAddress.getByAddress(address).getHostAddress();
        if (host == null || host.trim().isEmpty()) return null;
        return new Endpoint(host, port);
    }
}
