package is.quietlink.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LanDiscovery implements AutoCloseable {
    public interface Found { void onFound(String host, int port); }
    private static final String TYPE = "_quietlink._tcp.";
    private final NsdManager nsd;
    private LocalBroadcastDiscovery localBroadcast;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LanDiscovery(Context context) {
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public void advertise(String code, int port) {
        NsdServiceInfo info = new NsdServiceInfo();
        String room = Pairing.roomId(code);
        info.setServiceType(TYPE); info.setServiceName(room); info.setPort(port);
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) { SessionBus.status("Waiting on local Wi-Fi • pairing code ready"); }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { SessionBus.status("Local discovery unavailable; direct fallback will be tried"); }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration); } catch (Exception ignored) {}
        if (localBroadcast != null) try { localBroadcast.close(); } catch (Exception ignored) {}
        localBroadcast = new LocalBroadcastDiscovery("", null);
        localBroadcast.advertise("room", room, port, "", "");
    }

    public void discover(String code, Found found) {
        String expected = Pairing.roomId(code);
        if (localBroadcast != null) try { localBroadcast.close(); } catch (Exception ignored) {}
        localBroadcast = new LocalBroadcastDiscovery("", (kind, token, fingerprint, name, host, port) -> {
            if (!closed.get() && "room".equals(kind) && expected.equals(token)) {
                found.onFound(host, port);
            }
        });
        localBroadcast.discover();

        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { SessionBus.status("Searching local Wi-Fi for " + code + "…"); }
            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!serviceInfo.getServiceName().equals(expected)) return;
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                        @Override public void onServiceResolved(NsdServiceInfo resolved) {
                            if (!closed.get() && resolved.getHost() != null) {
                                found.onFound(resolved.getHost().getHostAddress(), resolved.getPort());
                            }
                        }
                    });
                } catch (Exception ignored) {}
            }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { try { nsd.stopServiceDiscovery(this); } catch (Exception ignored) {} }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); } catch (Exception e) { SessionBus.status("Local discovery failed; direct fallback will be tried"); }
    }

    @Override public void close() {
        closed.set(true);
        if (registration != null) try { nsd.unregisterService(registration); } catch (Exception ignored) {}
        if (discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        if (localBroadcast != null) {
            try { localBroadcast.close(); } catch (Exception ignored) {}
            localBroadcast = null;
        }
    }
}
