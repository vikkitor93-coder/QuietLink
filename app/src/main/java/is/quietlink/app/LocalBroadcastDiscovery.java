package is.quietlink.app;

import android.util.Base64;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local discovery fallback for Wi-Fi hotspot/tethering cases where Android NSD
 * follows cellular as the "active network" instead of the hotspot LAN.
 * Discovery metadata only; call authentication/encryption stays in CryptoChannel.
 */
public final class LocalBroadcastDiscovery implements AutoCloseable {
    public static final int DISCOVERY_PORT = 39877;
    private static final String MAGIC = "QLD1";
    private static final long SEND_INTERVAL_MS = 1800L;

    public interface Listener {
        void onBeacon(String kind, String token, String fingerprint,
                      String name, String host, int port);
    }

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final String ownFingerprint;
    private final Listener listener;
    private volatile String advertiseKind;
    private volatile String advertiseToken;
    private volatile String advertiseFingerprint;
    private volatile String advertiseName;
    private volatile int advertisePort;
    private DatagramSocket receiveSocket;
    private Thread receiverThread;
    private Thread senderThread;

    public LocalBroadcastDiscovery(String ownFingerprint, Listener listener) {
        this.ownFingerprint = ownFingerprint == null ? "" : ownFingerprint;
        this.listener = listener;
    }

    public synchronized void advertise(String kind, String token, int port,
                                       String fingerprint, String name) {
        if (closed.get()) return;
        advertiseKind = safe(kind);
        advertiseToken = safe(token);
        advertisePort = port;
        advertiseFingerprint = safe(fingerprint);
        advertiseName = name == null ? "" : name;
        ensureStarted();
    }

    public synchronized void discover() {
        if (!closed.get()) ensureStarted();
    }

    private synchronized void ensureStarted() {
        if (receiverThread == null) {
            receiverThread = new Thread(this::receiveLoop, "QuietLink-LAN-RX");
            receiverThread.setDaemon(true);
            receiverThread.start();
        }
        if (senderThread == null) {
            senderThread = new Thread(this::sendLoop, "QuietLink-LAN-TX");
            senderThread.setDaemon(true);
            senderThread.start();
        }
    }

    private void receiveLoop() {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            socket.setSoTimeout(1500);
            synchronized (this) { receiveSocket = socket; }

            byte[] buffer = new byte[1400];
            while (!closed.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    parse(packet);
                } catch (SocketTimeoutException ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (this) {
                if (receiveSocket != null) {
                    try { receiveSocket.close(); } catch (Exception ignored) {}
                    receiveSocket = null;
                }
            }
        }
    }

    private void parse(DatagramPacket packet) {
        if (listener == null || packet == null || packet.getAddress() == null) return;
        try {
            String text = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8);
            String[] p = text.split("\\|", 7);
            if (p.length != 7 || !MAGIC.equals(p[0])) return;
            String rawKind = p[1];
            boolean isReply = rawKind.endsWith("_reply");
            String kind = isReply
                    ? rawKind.substring(0, rawKind.length() - "_reply".length())
                    : rawKind;
            int port = Integer.parseInt(p[3]);
            if (port <= 0 || port > 65535) return;
            String fingerprint = p[4];
            if (!ownFingerprint.isEmpty() && ownFingerprint.equals(fingerprint)) return;

            String name = new String(Base64.decode(
                    p[5], Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8);
            String sourceHost = packet.getAddress().getHostAddress();
            String host = isUsableIpv4(p[6]) ? p[6] : sourceHost;
            QuietLog.log("DISCOVERY", "udp_beacon_rx",
                    "kind=" + kind + " reply=" + (isReply ? 1 : 0));
            listener.onBeacon(kind, p[2], fingerprint, name, host, port);
            if (!isReply && advertiseKind != null && advertisePort > 0) {
                sendUnicastReply(packet.getAddress());
            }
        } catch (Exception ignored) {}
    }

    private void sendUnicastReply(InetAddress target) {
        if (target == null || advertiseKind == null || advertisePort <= 0) return;
        for (LocalEndpoint endpoint : localEndpoints()) {
            DatagramSocket out = null;
            try {
                String encodedName = Base64.encodeToString(
                        (advertiseName == null ? "" : advertiseName)
                                .getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP | Base64.URL_SAFE);
                String msg = MAGIC + "|" + advertiseKind + "_reply|"
                        + advertiseToken + "|" + advertisePort + "|"
                        + safe(advertiseFingerprint) + "|" + encodedName + "|"
                        + endpoint.address;
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                out = new DatagramSocket(null);
                out.setReuseAddress(true);
                out.setBroadcast(true);
                out.bind(new InetSocketAddress(endpoint.localAddress, 0));
                out.send(new DatagramPacket(data, data.length, target, DISCOVERY_PORT));
                QuietLog.log("DISCOVERY", "udp_reply_tx", "kind=" + advertiseKind);
                return;
            } catch (Exception ignored) {
            } finally {
                if (out != null) try { out.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void sendLoop() {
        while (!closed.get()) {
            try {
                sendBeacon();
                Thread.sleep(SEND_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                try { Thread.sleep(SEND_INTERVAL_MS); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sendBeacon() {
        String kind = advertiseKind;
        String token = advertiseToken;
        int port = advertisePort;
        if (kind == null || token == null || port <= 0) return;

        for (LocalEndpoint endpoint : localEndpoints()) {
            DatagramSocket out = null;
            try {
                String encodedName = Base64.encodeToString(
                        (advertiseName == null ? "" : advertiseName)
                                .getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP | Base64.URL_SAFE);
                String msg = MAGIC + "|" + kind + "|" + token + "|" + port + "|"
                        + safe(advertiseFingerprint) + "|" + encodedName + "|" + endpoint.address;
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);

                out = new DatagramSocket(null);
                out.setReuseAddress(true);
                out.setBroadcast(true);
                out.bind(new InetSocketAddress(endpoint.localAddress, 0));
                for (InetAddress target : endpoint.broadcasts) {
                    try {
                        out.send(new DatagramPacket(data, data.length, target, DISCOVERY_PORT));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            } finally {
                if (out != null) try { out.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static List<LocalEndpoint> localEndpoints() {
        List<LocalEndpoint> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return out;
            for (NetworkInterface nif : Collections.list(interfaces)) {
                try {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                } catch (Exception ignored) { continue; }

                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    InetAddress broadcast = ia.getBroadcast();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()
                            || broadcast == null) continue;
                    if (!addr.isSiteLocalAddress() && !addr.isLinkLocalAddress()) continue;

                    Set<InetAddress> targets = new LinkedHashSet<>();
                    targets.add(broadcast);
                    try { targets.add(InetAddress.getByName("255.255.255.255")); }
                    catch (Exception ignored) {}
                    out.add(new LocalEndpoint(addr, addr.getHostAddress(),
                            new ArrayList<>(targets)));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static boolean hasHotspotOrLocalWifiInterface() {
        return !localEndpoints().isEmpty();
    }

    private static boolean isUsableIpv4(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet4Address
                    && (address.isSiteLocalAddress() || address.isLinkLocalAddress());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "").replace("\n", " ").replace("\r", " ");
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (receiveSocket != null) try { receiveSocket.close(); } catch (Exception ignored) {}
        if (receiverThread != null) receiverThread.interrupt();
        if (senderThread != null) senderThread.interrupt();
        receiverThread = null;
        senderThread = null;
    }

    private static final class LocalEndpoint {
        final InetAddress localAddress;
        final String address;
        final List<InetAddress> broadcasts;
        LocalEndpoint(InetAddress localAddress, String address, List<InetAddress> broadcasts) {
            this.localAddress = localAddress;
            this.address = address;
            this.broadcasts = broadcasts;
        }
    }
}
