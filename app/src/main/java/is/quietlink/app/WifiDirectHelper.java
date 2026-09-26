package is.quietlink.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Android Wi-Fi Direct fallback for CODE sessions.
 *
 * This transport is deliberately independent from normal LAN and internet
 * rendezvous. It creates a local P2P group when two nearby phones cannot use a
 * shared router/hotspot. QL5 still authenticates/encrypts the actual QuietLink
 * session after the P2P route exists.
 */
public final class WifiDirectHelper implements AutoCloseable {
    public interface Connected { void onConnected(String host, int port); }

    private static final long RETRY_MS = 1800L;
    private static final int MAX_HOST_RETRIES = 8;

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler main;

    private BroadcastReceiver receiver;
    private WifiP2pDnsSdServiceRequest request;
    private Connected callback;

    private int expectedPort;
    private boolean joiner;
    private boolean closed;
    private boolean active;
    private boolean connectedCallbackDelivered;
    private boolean p2pStateKnown;
    private boolean p2pEnabled;
    private boolean p2pStartReleased;
    private Runnable pendingP2pStart;
    private int generation;

    private String joinCode;
    private String hostCode;
    private int hostPort;

    public WifiDirectHelper(Context context) {
        this.context = context.getApplicationContext();
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null
                ? null
                : manager.initialize(context, context.getMainLooper(), null);
        main = new Handler(context.getMainLooper());
    }

    private boolean permitted() {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void startHost(String code, int port) {
        if (!usable()) return;

        generation++;
        int g = generation;
        active = true;
        joiner = false;
        connectedCallbackDelivered = false;
        expectedPort = 0;
        hostCode = code;
        hostPort = port;
        registerReceiver();

        SessionBus.status("No LAN peer yet • preparing Wi-Fi Direct fallback…");
        QuietLog.log("P2P", "wifi_direct_host_start", "permission=1");

        waitForP2pEnabled(g,
                () -> cleanupStaleState(g, () -> createHostGroup(g, 0)));
    }

    public void discoverAndConnect(String code, Connected connected) {
        if (!usable()) return;

        generation++;
        int g = generation;
        active = true;
        joiner = true;
        callback = connected;
        joinCode = code;
        expectedPort = 0;
        connectedCallbackDelivered = false;
        registerReceiver();

        SessionBus.status("Preparing Wi-Fi Direct fallback…");
        QuietLog.log("P2P", "wifi_direct_join_start", "permission=1");

        waitForP2pEnabled(g,
                () -> cleanupStaleState(g, () -> beginJoinDiscovery(g)));
    }

    private boolean usable() {
        if (manager == null || channel == null) {
            SessionBus.status("Wi-Fi Direct unavailable on this device");
            QuietLog.log("P2P", "wifi_direct_unavailable", "reason=manager");
            return false;
        }
        if (!permitted()) {
            SessionBus.status("Wi-Fi Direct permission missing • other connection paths still available");
            QuietLog.log("P2P", "wifi_direct_unavailable", "reason=permission");
            return false;
        }
        return true;
    }

    private void waitForP2pEnabled(int g, Runnable start) {
        if (!valid(g)) return;
        pendingP2pStart = start;
        p2pStartReleased = false;
        p2pStateKnown = false;
        p2pEnabled = false;

        if (Build.VERSION.SDK_INT >= 29) {
            queryP2pState(g, 0);
        } else {
            // Older Android exposes P2P state only through broadcasts. Give
            // the freshly-registered receiver a moment to deliver the current
            // state before starting a group/discovery operation.
            schedule(g, () -> {
                if (!valid(g) || p2pStartReleased) return;
                if (p2pStateKnown && !p2pEnabled) {
                    waitForLegacyP2pState(g, 0);
                } else if (!wifiRadioEnabled()) {
                    p2pStateKnown = true;
                    p2pEnabled = false;
                    waitForLegacyP2pState(g, 0);
                } else {
                    releaseP2pStart(g, "legacy_no_disabled_state");
                }
            }, 450L);
        }
    }

    private void queryP2pState(int g, int attempt) {
        if (!valid(g) || p2pStartReleased || Build.VERSION.SDK_INT < 29) return;
        try {
            manager.requestP2pState(channel, state -> {
                if (!valid(g) || p2pStartReleased) return;
                boolean enabled =
                        state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                p2pStateKnown = true;
                p2pEnabled = enabled;
                logP2pState(enabled, "query");

                if (enabled) {
                    releaseP2pStart(g, "query_enabled");
                    return;
                }

                showWaitingForP2p(attempt);
                schedule(g, () -> queryP2pState(g, attempt + 1), 1000L);
            });
        } catch (Exception e) {
            QuietLog.log("P2P", "wifi_direct_state_query_failed",
                    "reason=" + e.getClass().getSimpleName());
            if (wifiRadioEnabled()) {
                // Query itself failing should not permanently disable P2P.
                // Fall back to the operation path and let ActionListener
                // report a concrete BUSY/ERROR/UNSUPPORTED reason.
                releaseP2pStart(g, "query_exception");
            } else {
                showWaitingForP2p(attempt);
                schedule(g, () -> queryP2pState(g, attempt + 1), 1000L);
            }
        }
    }

    private void waitForLegacyP2pState(int g, int attempt) {
        if (!valid(g) || p2pStartReleased) return;

        if (p2pEnabled) {
            releaseP2pStart(g, "broadcast_enabled");
            return;
        }

        showWaitingForP2p(attempt);
        schedule(g, () -> {
            if (!valid(g) || p2pStartReleased) return;
            if (p2pEnabled) releaseP2pStart(g, "broadcast_enabled");
            else waitForLegacyP2pState(g, attempt + 1);
        }, 1000L);
    }

    private void releaseP2pStart(int g, String source) {
        if (!valid(g) || p2pStartReleased) return;
        p2pStartReleased = true;
        Runnable start = pendingP2pStart;
        pendingP2pStart = null;
        QuietLog.log("P2P", "wifi_direct_start_released",
                "source=" + source);
        if (start != null) start.run();
    }

    private void showWaitingForP2p(int attempt) {
        boolean wifiOn = wifiRadioEnabled();
        if (!wifiOn) {
            SessionBus.status("Wi-Fi Direct waiting • turn Wi-Fi radio on (no router required)");
        } else {
            SessionBus.status("Wi-Fi Direct • waiting for Android P2P to become ready…");
        }
        if (attempt == 0 || attempt % 10 == 0) {
            QuietLog.log("P2P", "wifi_direct_waiting",
                    "wifi_radio=" + (wifiOn ? 1 : 0)
                            + " attempt=" + attempt);
        }
    }

    private boolean wifiRadioEnabled() {
        try {
            WifiManager wifi = (WifiManager)
                    context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            return wifi != null && wifi.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void logP2pState(boolean enabled, String source) {
        QuietLog.log("P2P", "wifi_direct_state",
                "enabled=" + (enabled ? 1 : 0)
                        + " wifi_radio=" + (wifiRadioEnabled() ? 1 : 0)
                        + " source=" + source);
    }

    private void cleanupStaleState(int g, Runnable next) {
        if (!valid(g)) return;

        try {
            manager.cancelConnect(channel, new ContinueAction(
                    () -> removeStaleGroup(g, next),
                    () -> removeStaleGroup(g, next)));
        } catch (Exception ignored) {
            removeStaleGroup(g, next);
        }
    }

    private void removeStaleGroup(int g, Runnable next) {
        if (!valid(g)) return;

        try {
            manager.removeGroup(channel, new ContinueAction(
                    () -> clearRequestsAndServices(g, next),
                    () -> clearRequestsAndServices(g, next)));
        } catch (Exception ignored) {
            clearRequestsAndServices(g, next);
        }
    }

    private void clearRequestsAndServices(int g, Runnable next) {
        if (!valid(g)) return;

        Runnable clearServices = () -> {
            if (!valid(g)) return;
            try {
                manager.clearLocalServices(channel, new ContinueAction(
                        next, next));
            } catch (Exception ignored) {
                if (next != null) next.run();
            }
        };

        try {
            manager.clearServiceRequests(channel, new ContinueAction(
                    clearServices, clearServices));
        } catch (Exception ignored) {
            clearServices.run();
        }
    }

    private void createHostGroup(int g, int attempt) {
        if (!valid(g) || joiner) return;

        try {
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    QuietLog.log("P2P", "wifi_direct_group_ready",
                            "attempt=" + attempt);
                    publishHostService(g, 0);
                }

                @Override public void onFailure(int reason) {
                    QuietLog.log("P2P", "wifi_direct_group_failed",
                            "reason=" + reasonLabel(reason)
                                    + " attempt=" + attempt);
                    if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                        SessionBus.status("Wi-Fi Direct is not supported on this phone");
                        return;
                    }
                    if (attempt >= MAX_HOST_RETRIES) {
                        SessionBus.status("Wi-Fi Direct host could not start • other paths still available");
                        return;
                    }
                    schedule(g, () -> cleanupStaleState(
                            g, () -> createHostGroup(g, attempt + 1)));
                }
            });
        } catch (Exception e) {
            QuietLog.log("P2P", "wifi_direct_group_failed",
                    "reason=" + e.getClass().getSimpleName()
                            + " attempt=" + attempt);
            if (attempt < MAX_HOST_RETRIES) {
                schedule(g, () -> cleanupStaleState(
                        g, () -> createHostGroup(g, attempt + 1)));
            }
        }
    }

    private void publishHostService(int g, int attempt) {
        if (!valid(g) || joiner) return;

        Map<String,String> record = new HashMap<>();
        record.put("room", Pairing.roomId(hostCode));
        record.put("port", Integer.toString(hostPort));

        WifiP2pDnsSdServiceInfo service =
                WifiP2pDnsSdServiceInfo.newInstance(
                        "QuietLink", "_quietlink._tcp", record);

        try {
            manager.clearLocalServices(channel, new ContinueAction(() -> {
                if (!valid(g)) return;
                manager.addLocalService(channel, service,
                        new WifiP2pManager.ActionListener() {
                            @Override public void onSuccess() {
                                SessionBus.status("Wi-Fi Direct host ready • encrypted pairing");
                                QuietLog.log("P2P", "wifi_direct_service_ready",
                                        "attempt=" + attempt);
                            }

                            @Override public void onFailure(int reason) {
                                QuietLog.log("P2P", "wifi_direct_service_failed",
                                        "reason=" + reasonLabel(reason)
                                                + " attempt=" + attempt);
                                if (attempt < MAX_HOST_RETRIES) {
                                    schedule(g, () -> publishHostService(
                                            g, attempt + 1));
                                }
                            }
                        });
            }, () -> {
                if (attempt < MAX_HOST_RETRIES) {
                    schedule(g, () -> publishHostService(g, attempt + 1));
                }
            }));
        } catch (Exception e) {
            QuietLog.log("P2P", "wifi_direct_service_failed",
                    "reason=" + e.getClass().getSimpleName()
                            + " attempt=" + attempt);
            if (attempt < MAX_HOST_RETRIES) {
                schedule(g, () -> publishHostService(g, attempt + 1));
            }
        }
    }

    private void beginJoinDiscovery(int g) {
        if (!valid(g) || !joiner) return;

        final String expectedRoom = Pairing.roomId(joinCode);
        final Map<String,Map<String,String>> txtByAddress = new HashMap<>();

        manager.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, device) -> {
                    if (!valid(g) || !joiner) return;
                    Map<String,String> txt = txtByAddress.get(device.deviceAddress);
                    if (txt != null && expectedRoom.equals(txt.get("room"))) {
                        connect(g, device, parsePort(txt.get("port")));
                    }
                },
                (fullDomainName, record, device) -> {
                    if (!valid(g) || !joiner) return;
                    txtByAddress.put(device.deviceAddress, record);
                    if (expectedRoom.equals(record.get("room"))) {
                        connect(g, device, parsePort(record.get("port")));
                    }
                });

        restartJoinDiscovery(g, 0);
    }

    private void restartJoinDiscovery(int g, int attempt) {
        if (!valid(g) || !joiner || connectedCallbackDelivered
                || expectedPort > 0) return;

        Runnable addRequest = () -> {
            if (!valid(g) || !joiner) return;
            request = WifiP2pDnsSdServiceRequest.newInstance();
            try {
                manager.addServiceRequest(channel, request,
                        new WifiP2pManager.ActionListener() {
                            @Override public void onSuccess() {
                                if (!valid(g)) return;
                                try {
                                    manager.discoverServices(channel,
                                            new WifiP2pManager.ActionListener() {
                                                @Override public void onSuccess() {
                                                    QuietLog.log("P2P",
                                                            "wifi_direct_discovery",
                                                            "state=running attempt=" + attempt);
                                                    if (attempt == 0) {
                                                        SessionBus.status("Wi-Fi Direct • searching nearby…");
                                                    }
                                                    schedule(g, () -> restartJoinDiscovery(
                                                            g, attempt + 1), 5000L);
                                                }

                                                @Override public void onFailure(int reason) {
                                                    discoveryFailed(
                                                            g, attempt, reason);
                                                }
                                            });
                                } catch (Exception e) {
                                    discoveryException(g, attempt, e);
                                }
                            }

                            @Override public void onFailure(int reason) {
                                discoveryFailed(g, attempt, reason);
                            }
                        });
            } catch (Exception e) {
                discoveryException(g, attempt, e);
            }
        };

        try {
            manager.clearServiceRequests(channel, new ContinueAction(
                    addRequest, addRequest));
        } catch (Exception ignored) {
            addRequest.run();
        }
    }

    private void discoveryFailed(int g, int attempt, int reason) {
        if (!valid(g)) return;
        QuietLog.log("P2P", "wifi_direct_discovery_failed",
                "reason=" + reasonLabel(reason)
                        + " attempt=" + attempt);
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            SessionBus.status("Wi-Fi Direct is not supported on this phone");
            return;
        }
        expectedPort = 0;
        schedule(g, () -> restartJoinDiscovery(g, attempt + 1));
    }

    private void discoveryException(int g, int attempt, Exception e) {
        if (!valid(g)) return;
        QuietLog.log("P2P", "wifi_direct_discovery_failed",
                "reason=" + e.getClass().getSimpleName()
                        + " attempt=" + attempt);
        expectedPort = 0;
        schedule(g, () -> restartJoinDiscovery(g, attempt + 1));
    }

    private int parsePort(String p) {
        try { return Integer.parseInt(p); }
        catch (Exception e) { return 0; }
    }

    private void connect(int g, WifiP2pDevice device, int port) {
        if (!valid(g) || !joiner || device == null || port <= 0
                || expectedPort > 0 || connectedCallbackDelivered) {
            return;
        }

        expectedPort = port;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        // Strongly prefer the advertising/host phone to own the group.
        config.groupOwnerIntent = 0;

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    SessionBus.status("Wi-Fi Direct • negotiating direct link…");
                    QuietLog.log("P2P", "wifi_direct_connect",
                            "state=requested");
                    final int attemptedPort = port;
                    main.postDelayed(() -> {
                        if (!valid(g) || connectedCallbackDelivered
                                || expectedPort != attemptedPort) {
                            return;
                        }
                        QuietLog.log("P2P", "wifi_direct_connect_timeout",
                                "retry=1");
                        expectedPort = 0;
                        try {
                            manager.cancelConnect(
                                    channel, new ContinueAction(null, null));
                        } catch (Exception ignored) {}
                        restartJoinDiscovery(g, 0);
                    }, 12000L);
                }

                @Override public void onFailure(int reason) {
                    expectedPort = 0;
                    QuietLog.log("P2P", "wifi_direct_connect_failed",
                            "reason=" + reasonLabel(reason));
                    if (valid(g)) {
                        SessionBus.status("Wi-Fi Direct retrying…");
                        schedule(g, () -> restartJoinDiscovery(g, 0));
                    }
                }
            });
        } catch (Exception e) {
            expectedPort = 0;
            QuietLog.log("P2P", "wifi_direct_connect_failed",
                    "reason=" + e.getClass().getSimpleName());
            schedule(g, () -> restartJoinDiscovery(g, 0));
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (closed || intent == null) return;
                String action = intent.getAction();

                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                    boolean enabled =
                            state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                    p2pStateKnown = true;
                    p2pEnabled = enabled;
                    logP2pState(enabled, "broadcast");

                    if (!active) return;
                    int g = generation;
                    if (enabled) {
                        if (!p2pStartReleased) {
                            releaseP2pStart(g, "broadcast_enabled");
                        } else if (joiner) {
                            schedule(g, () -> restartJoinDiscovery(g, 0), 500L);
                        }
                    } else if (!p2pStartReleased) {
                        showWaitingForP2p(0);
                    }
                    return;
                }

                if (!WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    return;
                }

                NetworkInfo ni = intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);
                boolean connected = ni != null && ni.isConnected();

                if (!connected) {
                    if (joiner && active && !connectedCallbackDelivered) {
                        expectedPort = 0;
                        int g = generation;
                        schedule(g, () -> restartJoinDiscovery(g, 0));
                    }
                    return;
                }

                if (!joiner || callback == null || connectedCallbackDelivered) {
                    return;
                }

                manager.requestConnectionInfo(channel, info -> {
                    if (closed || !active || info == null
                            || connectedCallbackDelivered) {
                        return;
                    }
                    InetAddress addr = info.groupOwnerAddress;
                    if (addr != null && expectedPort > 0) {
                        connectedCallbackDelivered = true;
                        QuietLog.log("P2P", "wifi_direct_route_ready",
                                "group_owner=1");
                        SessionBus.status("Wi-Fi Direct link ready • opening encrypted session…");
                        callback.onConnected(addr.getHostAddress(), expectedPort);
                    }
                });
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, f);
        }
    }

    public void resetJoinAttempt() {
        expectedPort = 0;
        connectedCallbackDelivered = false;
        if (manager != null && channel != null) {
            try {
                manager.cancelConnect(channel, new ContinueAction(null, null));
            } catch (Exception ignored) {}
        }

        if (!closed && active && joiner) {
            int g = generation;
            schedule(g, () -> restartJoinDiscovery(g, 0), 500L);
        }
    }

    public void stopDiscoveryKeepConnection() {
        active = false;
        generation++;
        p2pStartReleased = false;
        pendingP2pStart = null;
        p2pStateKnown = false;
        p2pEnabled = false;
        main.removeCallbacksAndMessages(null);

        if (manager != null && channel != null) {
            if (request != null) {
                try {
                    manager.removeServiceRequest(
                            channel, request, new ContinueAction(null, null));
                } catch (Exception ignored) {}
            }
            try {
                manager.clearServiceRequests(
                        channel, new ContinueAction(null, null));
            } catch (Exception ignored) {}
            try {
                manager.clearLocalServices(
                        channel, new ContinueAction(null, null));
            } catch (Exception ignored) {}
        }
        request = null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        active = false;
        generation++;
        main.removeCallbacksAndMessages(null);

        stopDiscoveryKeepConnection();

        if (manager != null && channel != null) {
            try {
                manager.cancelConnect(channel, new ContinueAction(null, null));
            } catch (Exception ignored) {}
            try {
                manager.removeGroup(channel, new ContinueAction(null, null));
            } catch (Exception ignored) {}
        }

        if (receiver != null) {
            try { context.unregisterReceiver(receiver); }
            catch (Exception ignored) {}
        }
        receiver = null;
    }

    private boolean valid(int g) {
        return !closed && active && generation == g
                && manager != null && channel != null;
    }

    private void schedule(int g, Runnable action) {
        schedule(g, action, RETRY_MS);
    }

    private void schedule(int g, Runnable action, long delayMs) {
        main.postDelayed(() -> {
            if (valid(g) && action != null) action.run();
        }, Math.max(100L, delayMs));
    }

    private static String reasonLabel(int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) return "unsupported";
        if (reason == WifiP2pManager.BUSY) return "busy";
        if (reason == WifiP2pManager.ERROR) return "error";
        return "code_" + reason;
    }

    private static final class ContinueAction
            implements WifiP2pManager.ActionListener {
        private final Runnable success;
        private final Runnable failure;

        ContinueAction(Runnable success, Runnable failure) {
            this.success = success;
            this.failure = failure;
        }

        @Override public void onSuccess() {
            if (success != null) success.run();
        }

        @Override public void onFailure(int reason) {
            if (failure != null) failure.run();
        }
    }
}
