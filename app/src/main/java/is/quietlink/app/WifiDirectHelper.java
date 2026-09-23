package is.quietlink.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

public final class WifiDirectHelper implements AutoCloseable {
    public interface Connected { void onConnected(String host, int port); }
    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private WifiP2pDnsSdServiceRequest request;
    private Connected callback;
    private int expectedPort;
    private boolean joiner;

    public WifiDirectHelper(Context context) {
        this.context = context.getApplicationContext();
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(context, context.getMainLooper(), null);
    }

    private boolean permitted() {
        if (Build.VERSION.SDK_INT >= 33) return context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void startHost(String code, int port) {
        if (manager == null || channel == null || !permitted()) { SessionBus.status("Wi-Fi Direct unavailable or permission missing"); return; }
        SessionBus.status("No LAN peer yet • enabling Wi-Fi Direct fallback…");
        registerReceiver();
        Map<String,String> record = new HashMap<>(); record.put("room", Pairing.roomId(code)); record.put("port", Integer.toString(port));
        WifiP2pDnsSdServiceInfo service = WifiP2pDnsSdServiceInfo.newInstance("QuietLink", "_quietlink._tcp", record);
        manager.clearLocalServices(channel, new SimpleAction(() -> manager.createGroup(channel, new SimpleAction(() -> manager.addLocalService(channel, service, new SimpleAction(() -> SessionBus.status("Wi-Fi Direct host ready • encrypted pairing")))))));
    }

    public void discoverAndConnect(String code, Connected connected) {
        if (manager == null || channel == null || !permitted()) { SessionBus.status("Wi-Fi Direct unavailable or permission missing"); return; }
        joiner = true; callback = connected; registerReceiver();
        SessionBus.status("Trying Wi-Fi Direct fallback…");
        final String expectedRoom = Pairing.roomId(code);
        final Map<String,Map<String,String>> txtByAddress = new HashMap<>();
        manager.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, device) -> {
                    Map<String,String> txt = txtByAddress.get(device.deviceAddress);
                    if (txt != null && expectedRoom.equals(txt.get("room"))) connect(device, parsePort(txt.get("port")));
                },
                (fullDomainName, record, device) -> {
                    txtByAddress.put(device.deviceAddress, record);
                    if (expectedRoom.equals(record.get("room"))) connect(device, parsePort(record.get("port")));
                });
        request = WifiP2pDnsSdServiceRequest.newInstance();
        manager.addServiceRequest(channel, request, new SimpleAction(() -> manager.discoverServices(channel, new SimpleAction(null))));
    }

    private int parsePort(String p) { try { return Integer.parseInt(p); } catch (Exception e) { return 0; } }

    private void connect(WifiP2pDevice device, int port) {
        if (port <= 0 || expectedPort > 0) return;
        expectedPort = port;
        WifiP2pConfig config = new WifiP2pConfig(); config.deviceAddress = device.deviceAddress; config.groupOwnerIntent = 0;
        manager.connect(channel, config, new SimpleAction(() -> SessionBus.status("Connecting directly to peer…")));
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(intent.getAction())) return;
                NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (ni != null && ni.isConnected() && joiner && callback != null) {
                    manager.requestConnectionInfo(channel, info -> {
                        InetAddress addr = info.groupOwnerAddress;
                        if (addr != null && expectedPort > 0) callback.onConnected(addr.getHostAddress(), expectedPort);
                    });
                }
            }
        };
        IntentFilter f = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, f);
    }

    public void resetJoinAttempt() {
        expectedPort = 0;
        if (manager != null && channel != null) {
            try { manager.cancelConnect(channel, new SimpleAction(null)); } catch (Exception ignored) {}
        }
    }

    public void stopDiscoveryKeepConnection() {
        if (manager != null && channel != null) {
            if (request != null) try { manager.removeServiceRequest(channel, request, new SimpleAction(null)); } catch (Exception ignored) {}
            try { manager.clearLocalServices(channel, new SimpleAction(null)); } catch (Exception ignored) {}
        }
        request = null;
    }

    @Override public void close() {
        stopDiscoveryKeepConnection();
        if (manager != null && channel != null) {
            try { manager.removeGroup(channel, new SimpleAction(null)); } catch (Exception ignored) {}
        }
        if (receiver != null) try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
        receiver = null;
    }

    private static final class SimpleAction implements WifiP2pManager.ActionListener {
        private final Runnable success; SimpleAction(Runnable r) { success = r; }
        @Override public void onSuccess() { if (success != null) success.run(); }
        @Override public void onFailure(int reason) {}
    }
}
