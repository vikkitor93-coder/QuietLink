package is.quietlink.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

public final class PeerDiscovery implements AutoCloseable {
    public static final String TYPE = "_quietlink-nearby._tcp.";

    public interface Listener {
        void onPeers(List<Peer> peers);
        void onStatus(String status);
    }

    public static final class Peer {
        public final String fingerprint;
        public final String name;
        public final String host;
        public final int port;
        public final String serviceName;

        public Peer(String fingerprint, String name, String host, int port, String serviceName) {
            this.fingerprint = fingerprint;
            this.name = name;
            this.host = host;
            this.port = port;
            this.serviceName = serviceName;
        }
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final String ownFingerprint;
    private final Map<String, Peer> byService = new LinkedHashMap<>();
    private final Map<String, Long> udpSeen = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private LocalBroadcastDiscovery localBroadcast;

    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;

    public PeerDiscovery(Context context, String ownFingerprint, Listener listener) {
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.ownFingerprint = ownFingerprint;
        this.listener = listener;
        this.localBroadcast = new LocalBroadcastDiscovery(ownFingerprint,
                (kind, token, fingerprint, name, host, port) -> {
                    if (closed.get() || !"nearby".equals(kind) || !"all".equals(token)) return;
                    if (fingerprint == null || fingerprint.isEmpty()
                            || fingerprint.equals(this.ownFingerprint)) return;
                    String key = "udp:" + fingerprint;
                    synchronized (byService) {
                        byService.put(key, new Peer(
                                fingerprint,
                                name == null || name.trim().isEmpty() ? "Android device" : name,
                                host,
                                port,
                                "udp-" + DeviceIdentity.shortId(fingerprint)));
                    }
                    udpSeen.put(key, System.currentTimeMillis());
                    publish();
                });
        Thread sweeper = new Thread(() -> {
            while (!closed.get()) {
                try { Thread.sleep(3000L); }
                catch (InterruptedException e) { return; }
                long cutoff = System.currentTimeMillis() - 6500L;
                boolean changed = false;
                for (Map.Entry<String, Long> e : new ArrayList<>(udpSeen.entrySet())) {
                    if (e.getValue() < cutoff) {
                        udpSeen.remove(e.getKey());
                        synchronized (byService) {
                            changed |= byService.remove(e.getKey()) != null;
                        }
                    }
                }
                // Republish even when unchanged. Android activity/service
                // lifecycles can attach the UI listener after the first NSD
                // callback; periodic replay prevents an already-found device
                // from remaining invisible until the user changes tabs.
                publish();
            }
        }, "QuietLink-PeerSweep");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    public void advertise(int port, String fingerprint, String deviceName) {
        if (closed.get()) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceType(TYPE);
        info.setServiceName("QuietLink-" + DeviceIdentity.shortId(fingerprint));
        info.setPort(port);
        try {
            info.setAttribute("id", fingerprint);
            info.setAttribute("name", sanitizeName(deviceName));
            info.setAttribute("v", String.valueOf(CryptoChannel.PROTOCOL_VERSION));
        } catch (Exception ignored) {}

        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                listener.onStatus("Pairing mode • discoverable");
            }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                listener.onStatus("Could not advertise on this network");
            }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };

        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            listener.onStatus("NSD unavailable • using hotspot/LAN discovery");
        }
        if (localBroadcast != null) {
            localBroadcast.advertise("nearby", "all", port, fingerprint, sanitizeName(deviceName));
        }
    }

    public void discover() {
        if (closed.get()) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                listener.onStatus("Searching nearby QuietLink devices…");
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (closed.get()) return;
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}

                        @Override public void onServiceResolved(NsdServiceInfo resolved) {
                            if (closed.get() || resolved.getHost() == null) return;
                            String id = attr(resolved, "id");
                            if (id == null || id.isEmpty() || id.equals(ownFingerprint)) return;
                            String name = attr(resolved, "name");
                            if (name == null || name.trim().isEmpty()) {
                                name = resolved.getServiceName();
                            }
                            Peer peer = new Peer(
                                    id,
                                    name,
                                    resolved.getHost().getHostAddress(),
                                    resolved.getPort(),
                                    resolved.getServiceName());
                            synchronized (byService) {
                                byService.put(resolved.getServiceName(), peer);
                            }
                            publish();
                        }
                    });
                } catch (Exception ignored) {}
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                synchronized (byService) {
                    byService.remove(serviceInfo.getServiceName());
                }
                publish();
            }

            @Override public void onDiscoveryStopped(String serviceType) {}

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                try { nsd.stopServiceDiscovery(this); } catch (Exception ignored) {}
                listener.onStatus("Nearby search unavailable");
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };

        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            listener.onStatus("NSD search unavailable • using hotspot/LAN discovery");
        }
        if (localBroadcast != null) localBroadcast.discover();
    }

    private void publish() {
        Map<String, Peer> unique = new LinkedHashMap<>();
        synchronized (byService) {
            for (Peer p : byService.values()) {
                if (p != null && p.fingerprint != null && !p.fingerprint.isEmpty()) {
                    unique.put(p.fingerprint, p);
                }
            }
        }
        List<Peer> peers = new ArrayList<>(unique.values());
        Collections.sort(peers, Comparator.comparing(p -> p.name.toLowerCase()));
        listener.onPeers(peers);
    }

    private static String attr(NsdServiceInfo info, String key) {
        try {
            byte[] value = info.getAttributes().get(key);
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeName(String name) {
        if (name == null) return "Android device";
        String clean = name.replace("\n", " ").replace("\r", " ").trim();
        if (clean.length() > 48) clean = clean.substring(0, 48);
        return clean.isEmpty() ? "Android device" : clean;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (registration != null) {
            try { nsd.unregisterService(registration); } catch (Exception ignored) {}
        }
        if (discovery != null) {
            try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        }
        if (localBroadcast != null) {
            try { localBroadcast.close(); } catch (Exception ignored) {}
            localBroadcast = null;
        }
        udpSeen.clear();
        synchronized (byService) { byService.clear(); }
        publish();
    }
}
