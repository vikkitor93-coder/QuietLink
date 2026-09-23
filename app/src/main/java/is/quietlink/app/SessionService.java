package is.quietlink.app;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.util.Base64;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SessionService extends Service {
    public static final String ACTION_HOST = "is.quietlink.HOST";
    public static final String ACTION_JOIN = "is.quietlink.JOIN";
    public static final String ACTION_PAIRING_START = "is.quietlink.PAIRING_START";
    public static final String ACTION_PAIRING_STOP = "is.quietlink.PAIRING_STOP";
    public static final String ACTION_NEARBY_CONNECT = "is.quietlink.NEARBY_CONNECT";
    public static final String ACTION_INCOMING_ACCEPT = "is.quietlink.INCOMING_ACCEPT";
    public static final String ACTION_INCOMING_DECLINE = "is.quietlink.INCOMING_DECLINE";
    public static final String ACTION_OUTGOING_CANCEL = "is.quietlink.OUTGOING_CANCEL";
    public static final String ACTION_DISCONNECT = "is.quietlink.DISCONNECT";
    public static final String ACTION_MIC_MUTE = "is.quietlink.MIC_MUTE";
    public static final String ACTION_PTT = "is.quietlink.PTT";
    public static final String ACTION_REMOTE_SWITCH_CAMERA = "is.quietlink.REMOTE_SWITCH_CAMERA";
    public static final String ACTION_LOCAL_SWITCH_CAMERA = "is.quietlink.LOCAL_SWITCH_CAMERA";
    public static final String ACTION_REMOTE_VIDEO = "is.quietlink.REMOTE_VIDEO";
    public static final String ACTION_REMOTE_BABY_MIC = "is.quietlink.REMOTE_BABY_MIC";
    public static final String ACTION_REMOTE_BABY_CAMERA = "is.quietlink.REMOTE_BABY_CAMERA";
    public static final String ACTION_REMOTE_BABY_TORCH = "is.quietlink.REMOTE_BABY_TORCH";
    public static final String ACTION_REMOTE_BABY_BRIGHTNESS = "is.quietlink.REMOTE_BABY_BRIGHTNESS";
    public static final String ACTION_LISTEN = "is.quietlink.LISTEN";
    public static final String ACTION_SLEEPING_MODE = "is.quietlink.SLEEPING_MODE";
    public static final String ACTION_SENSITIVITY = "is.quietlink.SENSITIVITY";
    public static final String ACTION_LOCAL_VIDEO = "is.quietlink.LOCAL_VIDEO";
    public static final String ACTION_OUTPUT_VOLUME = "is.quietlink.OUTPUT_VOLUME";
    public static final String ACTION_SET_MODE = "is.quietlink.SET_MODE";
    public static final String ACTION_REFRESH_ORIENTATION = "is.quietlink.REFRESH_ORIENTATION";
    public static final String ACTION_REFRESH_VIDEO_PIPELINE = "is.quietlink.REFRESH_VIDEO_PIPELINE";
    public static final String ACTION_APPLY_ROTATION_LAB = "is.quietlink.APPLY_ROTATION_LAB";
    public static final String ACTION_SWAP_BABY_ROLE = "is.quietlink.SWAP_BABY_ROLE";
    public static final String ACTION_SEND_CHAT = "is.quietlink.SEND_CHAT";
    public static final String ACTION_CHAT_READ = "is.quietlink.CHAT_READ";
    public static final String ACTION_RESTORE_SESSION = "is.quietlink.RESTORE_SESSION";

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_NEW_MODE = "new_mode";
    public static final String EXTRA_PEER_HOST = "peer_host";
    public static final String EXTRA_PEER_PORT = "peer_port";
    public static final String EXTRA_PEER_FINGERPRINT = "peer_fingerprint";
    public static final String EXTRA_PEER_NAME = "peer_name";
    public static final String EXTRA_CHAT_TEXT = "chat_text";

    public static final int MODE_VOICE = 0;
    public static final int MODE_VIDEO = 1;
    public static final int MODE_BABY = 2;
    public static final int MODE_SLEEPING_BABY = 3;

    private static final int NOTIFY_ID = 7101;
    private static final int ALERT_ID = 7102;
    private static final int MESSAGE_NOTIFY_ID = 7103;
    private static final String CHANNEL = "quietlink_session";
    private static final String ALERT_CHANNEL = "quietlink_alerts";
    private static final String MESSAGE_CHANNEL = "quietlink_messages";
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;
    private static final long PEER_STALE_WARN_MS = 12_000L;
    private static final long PEER_TIMEOUT_MS = 25_000L;
    private static final long RECOVERY_RETRY_MS = 2_500L;
    private static final long RECOVERY_DISCOVERY_RESTART_MS = 4_000L;
    private static final long CONNECTION_REQUEST_EXPIRY_MS = 30_000L;
    private static final long RECOVERY_CHECKPOINT_MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final long SERVICE_WATCHDOG_INTERVAL_MS = 5_000L;
    private static final String RECOVERY_PREF = "quietlink_recovery_checkpoint";

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean established = new AtomicBoolean(false);
    private final AtomicBoolean onlineConnecting = new AtomicBoolean(false);
    private final Semaphore incomingHandshakeSlots = new Semaphore(4, true);

    private ServerSocket serverSocket;
    private ServerSocket pairingServerSocket;
    private DatagramSocket udpSocket;
    private CryptoChannel crypto;
    private MediaTransport media;
    private AudioEngine audio;
    private VideoEngine video;
    private H264Codec.Capability h264Capability;
    private volatile boolean peerH264Capable = false;
    private LanDiscovery lan;
    private WifiDirectHelper wifiDirect;
    private PeerDiscovery peerDiscovery;
    private PeerDiscovery recoveryDiscovery;
    private RendezvousClient onlineRendezvous;
    private volatile boolean internetControlRelay = false;
    private ServerSocket recoveryServerSocket;
    // Lightweight beacon stays advertised during a healthy call. If one phone
    // notices a break first, its recovery request makes the still-connected
    // phone enter recovery immediately instead of waiting for heartbeat timeout.
    private PeerDiscovery recoveryBeacon;
    private ServerSocket recoveryBeaconServerSocket;
    private volatile List<PeerDiscovery.Peer> recoveryPeers = Collections.emptyList();
    private volatile boolean recoveryDiscoveryFailed = false;
    private volatile long recoveryDiscoveryStartedElapsedMs = 0L;
    private volatile boolean recovering = false;
    private String activePeerFingerprint;
    private byte[] activePeerIdentityPublic;
    private boolean recoveryLocalVideoEnabled = false;
    private boolean recoveryRemoteVideoEnabled = true;
    private boolean recoveryListening = true;
    private DeviceIdentity deviceIdentity;
    private KnownDeviceStore knownDevices;

    private CryptoChannel pendingNearbyCrypto;
    private Socket pendingNearbySocket;
    private String pendingNearbyName;
    private String pendingNearbyFingerprint;
    private byte[] pendingNearbyPublic;
    private CryptoChannel pendingOutgoingCrypto;
    private Socket pendingOutgoingSocket;

    private boolean host;
    private volatile boolean babyStation = false;
    private int mode;
    private String code;
    private volatile boolean pairingMode = false;
    private volatile boolean micMuted = false;
    private volatile boolean ptt = false;
    private volatile boolean listening = true;
    private volatile boolean remoteVideoEnabled = true;
    private volatile boolean localVideoEnabled = false;
    private volatile boolean babyTorchEnabled = false;
    private volatile boolean babyBrightnessBoost = false;
    private volatile boolean sleepingBaby = false;
    private volatile float soundThreshold = 0.12f;
    private volatile float outputVolume = 1.0f;
    private int loudMs = 0;
    private long lastAlert = 0;
    private volatile long lastPeerSeenElapsedMs = 0L;
    private volatile boolean peerStaleWarningShown = false;
    private volatile long lastDiagRttMs = -1L;
    private volatile int recoveryCount = 0;
    private volatile long recoveryStartedElapsedMs = 0L;
    private volatile boolean recoveryFallbackProbeUsed = false;
    private volatile boolean resumeAuthorizedForSession = false;
    private volatile boolean restoringFromCheckpoint = false;
    private final AtomicBoolean diagnosticsLoopStarted = new AtomicBoolean(false);
    private final AtomicBoolean serviceWatchdogStarted = new AtomicBoolean(false);
    private PowerManager.WakeLock babyWakeLock;
    private WifiManager.WifiLock babyWifiLock;

    @Override public void onCreate() {
        super.onCreate();
        QuietLog.init(this);
        QuietLog.log("SERVICE", "service_create", "");
        createChannels();
        knownDevices = new KnownDeviceStore(this);
        try {
            deviceIdentity = DeviceIdentity.loadOrCreate(this);
        } catch (Exception e) {
            deviceIdentity = null;
        }
        h264Capability = H264Codec.probe();
        micMuted = SessionBus.localMicMuted;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (restoreRecoveryCheckpoint()) return START_STICKY;
            return START_NOT_STICKY;
        }
        String action = intent.getAction();

        if (ACTION_RESTORE_SESSION.equals(action)) {
            if (restoreRecoveryCheckpoint()) return START_STICKY;
        } else if (ACTION_PAIRING_START.equals(action)) {
            recovering = false;
            startPairingMode();
        } else if (ACTION_PAIRING_STOP.equals(action)) {
            stopPairingMode(true);
        } else if (ACTION_NEARBY_CONNECT.equals(action)) {
            connectNearby(
                    intent.getStringExtra(EXTRA_PEER_HOST),
                    intent.getIntExtra(EXTRA_PEER_PORT, 0),
                    intent.getStringExtra(EXTRA_PEER_FINGERPRINT),
                    intent.getStringExtra(EXTRA_PEER_NAME),
                    true);
        } else if (ACTION_INCOMING_ACCEPT.equals(action)) {
            SessionBus.autoConnectSuspended = false;
            acceptIncomingNearby();
        } else if (ACTION_INCOMING_DECLINE.equals(action)) {
            declineIncomingNearby();
        } else if (ACTION_OUTGOING_CANCEL.equals(action)) {
            cancelOutgoingNearby();
        } else if (ACTION_HOST.equals(action) || ACTION_JOIN.equals(action)) {
            if (SessionBus.active && !stopped.get()) return START_NOT_STICKY;
            recovering = false;
            SessionBus.autoConnectSuspended = false;
            stopPairingResources(true);
            stopped.set(false);
            established.set(false);
            connecting.set(false);
            onlineConnecting.set(false);
            internetControlRelay = false;
            recoveryCount = 0;
            lastDiagRttMs = -1L;
            host = ACTION_HOST.equals(action);
            mode = intent.getIntExtra(EXTRA_MODE, MODE_VOICE);
            sleepingBaby = mode == MODE_SLEEPING_BABY;
            if (sleepingBaby) mode = MODE_BABY;
            babyStation = mode == MODE_BABY && host;
            code = intent.getStringExtra(EXTRA_CODE);
            SessionBus.clearChat();
            SessionBus.active = true;
            SessionBus.code = code == null ? "" : code;
            SessionBus.peerName = "";
            SessionBus.sleepingBaby = sleepingBaby;
            SessionBus.modeChanged(mode, host);
            SessionBus.babyRoleChanged(babyStation);
            if (code == null || !code.matches("\\d{6}")) {
                stopSession("Invalid pairing code");
                return START_NOT_STICKY;
            }
            startForegroundForMode();
            if (host) io.execute(this::startHost);
            else io.execute(this::startJoin);
        } else if (ACTION_DISCONNECT.equals(action)) {
            if (established.get() || SessionBus.active) disconnectManually();
            else {
                SessionBus.autoConnectSuspended = true;
                stopPairingMode(true);
            }
        } else if (ACTION_MIC_MUTE.equals(action)) {
            boolean muted = intent.getBooleanExtra(EXTRA_VALUE, false);
            QuietLog.log("AUDIO", "local_mic_set",
                    "muted=" + (muted ? 1 : 0));
            setMicMuted(muted);
            if (mode == MODE_BABY && babyStation) sendBabySettings();
        } else if (ACTION_PTT.equals(action)) {
            ptt = intent.getBooleanExtra(EXTRA_VALUE, false);
            QuietLog.log("AUDIO", "ptt",
                    "active=" + (ptt ? 1 : 0));
        } else if (ACTION_REMOTE_SWITCH_CAMERA.equals(action)) {
            sendControl("SWITCH_CAMERA");
        } else if (ACTION_LOCAL_SWITCH_CAMERA.equals(action)) {
            if (video != null) video.switchCamera();
        } else if (ACTION_REMOTE_VIDEO.equals(action)) {
            remoteVideoEnabled = intent.getBooleanExtra(EXTRA_VALUE, true);
            sendControl(remoteVideoEnabled ? "VIDEO_ON" : "VIDEO_OFF");
        } else if (ACTION_REMOTE_BABY_MIC.equals(action)) {
            if (mode == MODE_BABY && !babyStation) {
                boolean enabled = intent.getBooleanExtra(EXTRA_VALUE, true);
                QuietLog.log("AUDIO", "remote_baby_mic_request",
                        "enabled=" + (enabled ? 1 : 0));
                sendControl("BABY_MIC_SET:" + (enabled ? "1" : "0"));
            }
        } else if (ACTION_REMOTE_BABY_CAMERA.equals(action)) {
            if (mode == MODE_BABY && !babyStation) {
                boolean enabled = intent.getBooleanExtra(EXTRA_VALUE, true);
                remoteVideoEnabled = enabled;
                sendControl("BABY_CAMERA_SET:" + (enabled ? "1" : "0"));
            }
        } else if (ACTION_REMOTE_BABY_TORCH.equals(action)) {
            if (mode == MODE_BABY && !babyStation) {
                boolean enabled = intent.getBooleanExtra(EXTRA_VALUE, false);
                sendControl("BABY_TORCH_SET:" + (enabled ? "1" : "0"));
            }
        } else if (ACTION_REMOTE_BABY_BRIGHTNESS.equals(action)) {
            if (mode == MODE_BABY && !babyStation) {
                boolean enabled = intent.getBooleanExtra(EXTRA_VALUE, false);
                sendControl("BABY_BRIGHTNESS_SET:" + (enabled ? "1" : "0"));
            }
        } else if (ACTION_LISTEN.equals(action)) {
            listening = intent.getBooleanExtra(EXTRA_VALUE, true);
            QuietLog.log("AUDIO", "listen_set",
                    "enabled=" + (listening ? 1 : 0));
            if (audio != null) audio.setPlaybackEnabled(listening);
            persistRecoveryCheckpoint();
        } else if (ACTION_SLEEPING_MODE.equals(action)) {
            setSleepingBaby(intent.getBooleanExtra(EXTRA_VALUE, false), true);
        } else if (ACTION_SENSITIVITY.equals(action)) {
            soundThreshold = Math.max(0f, Math.min(1f, intent.getFloatExtra(EXTRA_LEVEL, 0.12f)));
            persistRecoveryCheckpoint();
        } else if (ACTION_LOCAL_VIDEO.equals(action)) {
            boolean enabled = intent.getBooleanExtra(EXTRA_VALUE, true);
            QuietLog.log("SERVICE", "local_camera_toggle", "enabled=" + (enabled ? 1 : 0));
            setLocalVideoSending(enabled);
            sendControl(enabled ? "PEER_VIDEO_ON" : "PEER_VIDEO_OFF");
            if (mode == MODE_BABY && babyStation) sendBabySettings();
        } else if (ACTION_OUTPUT_VOLUME.equals(action)) {
            outputVolume = Math.max(0f, Math.min(1f, intent.getFloatExtra(EXTRA_LEVEL, 1f)));
            if (audio != null) audio.setVolume(outputVolume);
            persistRecoveryCheckpoint();
        } else if (ACTION_SET_MODE.equals(action)) {
            int requested = intent.getIntExtra(EXTRA_NEW_MODE, MODE_VOICE);
            if (requested == MODE_BABY && !host) return START_NOT_STICKY;
            if (requested == MODE_VOICE || requested == MODE_VIDEO || requested == MODE_BABY) {
                setSessionMode(requested, true);
            }
        } else if (ACTION_SEND_CHAT.equals(action)) {
            String text = intent.getStringExtra(EXTRA_CHAT_TEXT);
            sendChat(text);
        } else if (ACTION_CHAT_READ.equals(action)) {
            SessionBus.markChatRead();
            cancelChatNotification();
        } else if (ACTION_REFRESH_ORIENTATION.equals(action)) {
            if (video != null) video.refreshOrientation();
        } else if (ACTION_APPLY_ROTATION_LAB.equals(action)) {
            if (video != null) {
                video.applyRotationLabConfig();
                if (RotationLabConfig.forceJpeg(this)) {
                    peerH264Capable = false;
                    video.setH264Enabled(false);
                    sendControl("VIDEO_FALLBACK_JPEG");
                } else {
                    // Ask the peer to advertise again so H.264 can be restored
                    // after a live Force JPEG experiment without reconnecting.
                    sendControl("VIDEO_CAPS:" + localVideoCaps());
                    sendControl("VIDEO_CAPS_REQUEST");
                    video.setH264Enabled(peerH264Capable);
                }
            }
        } else if (ACTION_REFRESH_VIDEO_PIPELINE.equals(action)) {
            if (video != null) video.refreshAfterDisplayWake();
        } else if (ACTION_SWAP_BABY_ROLE.equals(action)) {
            if (mode == MODE_BABY) setBabyStation(!babyStation, true);
        }
        return (established.get() || recovering || SessionBus.active)
                ? START_STICKY : START_NOT_STICKY;
    }

    private synchronized void startPairingMode() {
        if (established.get() || SessionBus.active) return;
        if (pairingMode && peerDiscovery != null) return;

        try {
            if (deviceIdentity == null) deviceIdentity = DeviceIdentity.loadOrCreate(this);
            if (udpSocket == null || udpSocket.isClosed()) udpSocket = new DatagramSocket(0);

            pairingServerSocket = new ServerSocket(0);
            pairingServerSocket.setReuseAddress(true);
            pairingMode = true;
            stopped.set(false);
            connecting.set(false);
            SessionBus.pairingMode(true);
            startForegroundPairing();

            peerDiscovery = new PeerDiscovery(this, deviceIdentity.fingerprint(), new PeerDiscovery.Listener() {
                @Override public void onPeers(List<PeerDiscovery.Peer> peers) {
                    SessionBus.nearbyPeers(peers);
                    // Fresh calls are always deliberate. "Auto" is reserved for
                    // resuming an already-established session after interruption.
                }

                @Override public void onStatus(String status) {
                    if (pairingMode && !established.get()) {
                        SessionBus.status(status);
                        updateSessionNotification(status);
                    }
                }
            });

            peerDiscovery.advertise(pairingServerSocket.getLocalPort(), deviceIdentity.fingerprint(), deviceIdentity.deviceName());
            peerDiscovery.discover();
            io.execute(this::pairingAcceptLoop);
        } catch (Exception e) {
            SessionBus.status("Nearby pairing unavailable: " + readable(e));
            stopPairingMode(true);
        }
    }

    private void startForegroundPairing() {
        Notification n = sessionNotification("Pairing mode • searching nearby");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private void pairingAcceptLoop() {
        while (pairingMode && !established.get()) {
            Socket socket = null;
            try {
                ServerSocket server = pairingServerSocket;
                if (server == null || server.isClosed()) return;
                socket = server.accept();
                Socket accepted = socket;
                if (!incomingHandshakeSlots.tryAcquire()) {
                    try { accepted.close(); } catch (Exception ignored) {}
                    continue;
                }
                io.execute(() -> {
                    try { handleIncomingNearby(accepted); }
                    finally { incomingHandshakeSlots.release(); }
                });
            } catch (SocketException e) {
                return;
            } catch (Exception e) {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handleIncomingNearby(Socket socket) {
        CryptoChannel channel = null;
        try {
            if (!pairingMode || established.get() || pendingNearbyCrypto != null) {
                socket.close();
                return;
            }

            channel = CryptoChannel.handshakeNearby(socket, true, deviceIdentity.keyPair(), udpSocket.getLocalPort());
            socket.setSoTimeout(12000);
            String request = channel.readControl();
            socket.setSoTimeout(0);
            if (!request.startsWith("CALL|")) {
                channel.close();
                return;
            }

            String[] parts = request.split("\\|", 4);
            if (parts.length < 3) {
                channel.close();
                return;
            }

            String callerName = decodeName(parts[1]);
            // Kept in the wire format for compatibility with older builds.
            // v0.3.19+ never auto-accepts a fresh call.
            boolean callerAllowsAuto = "1".equals(parts[2]);
            boolean callerExplicit = parts.length >= 4 && "1".equals(parts[3]);
            String fingerprint = channel.getPeerFingerprint();
            byte[] peerPublic = channel.getPeerIdentityPublic();

            KnownDeviceStore.KnownDevice known = knownDevices.get(fingerprint);
            if (known != null && !Arrays.equals(known.publicKey(), peerPublic)) {
                channel.sendControl("DECLINE");
                channel.close();
                return;
            }

            // AUTO means "no one needs to walk over to the receiving phone".
            // It never originates a fresh call by itself. A manually initiated
            // authenticated call from a device marked Auto is accepted here.
            if (known != null && known.autoConnect && callerExplicit) {
                channel.sendControl("ACCEPT|" + encodeName(deviceIdentity.deviceName()));
                socket.setSoTimeout(12000);
                String ready = channel.readControl();
                socket.setSoTimeout(0);
                if (!"READY".equals(ready)) throw new IOException("Caller did not confirm connection");
                rememberPeer(fingerprint, callerName, peerPublic);
                finalizeNearby(channel, socket, false, callerName);
                return;
            }

            synchronized (this) {
                if (pendingNearbyCrypto != null || established.get()) {
                    channel.sendControl("BUSY");
                    channel.close();
                    return;
                }
                pendingNearbyCrypto = channel;
                pendingNearbySocket = socket;
                pendingNearbyName = callerName;
                pendingNearbyFingerprint = fingerprint;
                pendingNearbyPublic = peerPublic;
            }

            long expiresAtMs = System.currentTimeMillis() + CONNECTION_REQUEST_EXPIRY_MS;
            SessionBus.incoming(new SessionBus.IncomingRequest(
                    callerName,
                    fingerprint,
                    channel.getVerification(),
                    known != null,
                    expiresAtMs));
            SessionBus.status(callerName + " wants to connect • expires in 30s");

            CryptoChannel expectedIncoming = channel;
            main.postDelayed(() -> expireIncomingNearby(expectedIncoming),
                    CONNECTION_REQUEST_EXPIRY_MS);
        } catch (Exception e) {
            try { if (channel != null) channel.close(); else socket.close(); } catch (Exception ignored) {}
        }
    }

    private void connectNearby(String address, int port, String expectedFingerprint, String peerName, boolean userInitiated) {
        if (!pairingMode || established.get() || address == null || port <= 0) return;
        if (userInitiated) SessionBus.autoConnectSuspended = false;
        if (!connecting.compareAndSet(false, true)) return;

        io.execute(() -> {
            Socket socket = null;
            CryptoChannel channel = null;
            try {
                SessionBus.status("Calling " + safePeerName(peerName) + "…");
                socket = new Socket();
                socket.connect(new InetSocketAddress(address, port), 5000);
                channel = CryptoChannel.handshakeNearby(socket, false, deviceIdentity.keyPair(), udpSocket.getLocalPort());

                if (expectedFingerprint != null && !expectedFingerprint.equals(channel.getPeerFingerprint())) {
                    throw new IOException("Device identity changed");
                }

                KnownDeviceStore.KnownDevice known = knownDevices.get(channel.getPeerFingerprint());
                if (known != null && !Arrays.equals(known.publicKey(), channel.getPeerIdentityPublic())) {
                    throw new IOException("Known device identity changed");
                }

                // A fresh CONNECT is never automatic on the receiving phone.
                // Auto Resume is used only by the authenticated recovery path.
                boolean allowAuto = false;

                synchronized (this) {
                    pendingOutgoingCrypto = channel;
                    pendingOutgoingSocket = socket;
                }

                long expiresAtMs = System.currentTimeMillis() + CONNECTION_REQUEST_EXPIRY_MS;
                SessionBus.OutgoingRequest outgoing = new SessionBus.OutgoingRequest(
                        safePeerName(peerName),
                        channel.getPeerFingerprint(),
                        channel.getVerification(),
                        known != null,
                        expiresAtMs);

                SessionBus.outgoing(outgoing);

                channel.sendControl("CALL|" + encodeName(deviceIdentity.deviceName())
                        + "|" + (allowAuto ? "1" : "0")
                        + "|" + (userInitiated ? "1" : "0"));

                socket.setSoTimeout((int) CONNECTION_REQUEST_EXPIRY_MS);
                String answer = channel.readControl();
                socket.setSoTimeout(0);

                if (answer.startsWith("ACCEPT")) {
                    String acceptedName = peerName;
                    String[] answerParts = answer.split("\\|", 2);
                    if (answerParts.length == 2) acceptedName = decodeName(answerParts[1]);

                    channel.sendControl("READY");
                    clearOutgoingNearby(false);
                    rememberPeer(channel.getPeerFingerprint(), safePeerName(acceptedName), channel.getPeerIdentityPublic());
                    finalizeNearby(channel, socket, true, safePeerName(acceptedName));
                    return;
                }

                clearOutgoingNearby(false);
                if ("EXPIRED".equals(answer)) {
                    SessionBus.status("Connection request expired");
                } else if ("BUSY".equals(answer)) {
                    SessionBus.status("Device is busy");
                } else {
                    SessionBus.status("Connection declined");
                }
                channel.close();
            } catch (Exception e) {
                boolean expired = e instanceof SocketTimeoutException;
                clearOutgoingNearby(false);
                try {
                    if (channel != null) channel.close();
                    else if (socket != null) socket.close();
                } catch (Exception ignored) {}
                if (pairingMode && !established.get()) {
                    SessionBus.status(expired
                            ? "Connection request expired"
                            : "Could not connect to " + safePeerName(peerName));
                }
            } finally {
                if (!established.get()) connecting.set(false);
            }
        });
    }

    private synchronized void expireIncomingNearby(CryptoChannel expectedChannel) {
        if (expectedChannel == null || pendingNearbyCrypto != expectedChannel || established.get()) return;

        try { expectedChannel.sendControl("EXPIRED"); } catch (Exception ignored) {}
        clearPendingNearby(true);
        connecting.set(false);
        SessionBus.status("Connection request expired");
    }

    private synchronized void acceptIncomingNearby() {
        CryptoChannel channel = pendingNearbyCrypto;
        Socket socket = pendingNearbySocket;
        if (channel == null || socket == null) return;

        SessionBus.IncomingRequest currentRequest = SessionBus.incomingRequest;
        if (currentRequest == null || System.currentTimeMillis() >= currentRequest.expiresAtMs) {
            expireIncomingNearby(channel);
            return;
        }

        String name = pendingNearbyName;
        String fingerprint = pendingNearbyFingerprint;
        byte[] publicKey = pendingNearbyPublic;
        clearPendingNearby(false);

        io.execute(() -> {
            try {
                channel.sendControl("ACCEPT|" + encodeName(deviceIdentity.deviceName()));
                socket.setSoTimeout(12000);
                String ready = channel.readControl();
                socket.setSoTimeout(0);
                if (!"READY".equals(ready)) throw new IOException("Caller did not confirm connection");

                rememberPeer(fingerprint, name, publicKey);
                finalizeNearby(channel, socket, false, name);
            } catch (Exception e) {
                try { channel.close(); } catch (Exception ignored) {}
                SessionBus.status("Connection failed after acceptance");
            }
        });
    }

    private synchronized void declineIncomingNearby() {
        CryptoChannel channel = pendingNearbyCrypto;
        clearPendingNearby(false);
        if (channel != null) {
            try { channel.sendControl("DECLINE"); } catch (Exception ignored) {}
            try { channel.close(); } catch (Exception ignored) {}
        }
        SessionBus.status("Pairing mode • searching nearby");
    }

    private synchronized void clearPendingNearby(boolean close) {
        CryptoChannel channel = pendingNearbyCrypto;
        pendingNearbyCrypto = null;
        pendingNearbySocket = null;
        pendingNearbyName = null;
        pendingNearbyFingerprint = null;
        pendingNearbyPublic = null;
        SessionBus.clearIncoming();
        if (close && channel != null) {
            try { channel.close(); } catch (Exception ignored) {}
        }
    }

    private synchronized void clearOutgoingNearby(boolean close) {
        CryptoChannel channel = pendingOutgoingCrypto;
        Socket socket = pendingOutgoingSocket;
        pendingOutgoingCrypto = null;
        pendingOutgoingSocket = null;
        SessionBus.clearOutgoing();

        if (close) {
            try { if (channel != null) channel.close(); } catch (Exception ignored) {}
            try { if (channel == null && socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private synchronized void cancelOutgoingNearby() {
        if (pendingOutgoingCrypto == null && pendingOutgoingSocket == null) return;
        clearOutgoingNearby(true);
        connecting.set(false);
        SessionBus.status("Call cancelled");
    }

    private void rememberPeer(String fingerprint, String name, byte[] publicKey) {
        knownDevices.remember(fingerprint, name, publicKey);
        SessionBus.knownChanged();
    }

    private synchronized void finalizeNearby(CryptoChannel channel, Socket socket, boolean initiator, String peerName) throws Exception {
        if (established.get()) {
            channel.close();
            return;
        }

        clearPendingNearby(false);
        clearOutgoingNearby(false);
        host = initiator;
        mode = MODE_VOICE;
        sleepingBaby = false;
        code = "";
        SessionBus.clearChat();
        SessionBus.active = true;
        SessionBus.code = "";
        SessionBus.peerName = safePeerName(peerName);
        SessionBus.sleepingBaby = false;
        SessionBus.modeChanged(mode, host);

        activePeerFingerprint = channel.getPeerFingerprint();
        activePeerIdentityPublic = channel.getPeerIdentityPublic();
        recoveryCount = 0;
        lastDiagRttMs = -1L;
        crypto = channel;
        stopPairingResources(false);
        startForegroundForMode();
        activateConnectedSession(socket);
    }

    private synchronized void stopPairingMode(boolean stopServiceIfIdle) {
        stopPairingResources(true);
        if (!established.get() && !SessionBus.active) {
            SessionBus.status("Idle");
            if (stopServiceIfIdle) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private synchronized void stopPairingResources(boolean closeUdp) {
        pairingMode = false;
        SessionBus.pairingMode(false);
        SessionBus.nearbyPeers(Collections.emptyList());
        clearPendingNearby(true);
        clearOutgoingNearby(true);

        try { if (peerDiscovery != null) peerDiscovery.close(); } catch (Exception ignored) {}
        try { if (pairingServerSocket != null) pairingServerSocket.close(); } catch (Exception ignored) {}
        peerDiscovery = null;
        pairingServerSocket = null;

        if (!established.get()) stopOnlineRendezvous();

        if (closeUdp && !established.get()) {
            try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
            udpSocket = null;
        }
    }

    private void startForegroundForMode() {
        Notification n = sessionNotification("Starting local connection…");
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (host && mode != MODE_VOICE && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            startForeground(NOTIFY_ID, n, types);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private void upgradeForegroundForCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            startForeground(NOTIFY_ID, sessionNotification(connectionLabel()), types);
        }
    }

    private void startHost() {
        try {
            SessionBus.status("Preparing host…");
            udpSocket = new DatagramSocket(0);
            serverSocket = new ServerSocket(0);
            serverSocket.setReuseAddress(true);
            int port = serverSocket.getLocalPort();
            lan = new LanDiscovery(this);
            lan.advertise(code, port);
            main.postDelayed(() -> {
                if (!established.get() && !stopped.get()) {
                    startOnlineRendezvousBootstrap();
                }
            }, 1500);
            main.postDelayed(() -> {
                if (!established.get() && !stopped.get()) {
                    wifiDirect = new WifiDirectHelper(this);
                    wifiDirect.startHost(code, port);
                }
            }, 8000);

            while (!stopped.get() && !established.get()) {
                Socket s = null;
                try {
                    s = serverSocket.accept();
                    establishCodeSession(s);
                } catch (Exception e) {
                    if (s != null) try { s.close(); } catch (Exception ignored) {}
                    if (!stopped.get() && !established.get()) SessionBus.status("Connection failed • still waiting…");
                }
            }
        } catch (Exception e) {
            if (!stopped.get()) fail("Host failed: " + readable(e));
        }
    }

    private void startOnlineRendezvousBootstrap() {
        if (code == null || !code.matches("\\d{6}") || stopped.get() || established.get()) return;

        String testUrl = OnlineTestConfig.get(this);
        if (testUrl != null && !testUrl.isEmpty()) {
            String normalizedTest = testUrl;
            while (normalizedTest.endsWith("/")) {
                normalizedTest = normalizedTest.substring(0, normalizedTest.length() - 1);
            }

            if (OnlineStatus.PRODUCTION_RENDEZVOUS_URL.equals(normalizedTest)) {
                // This URL was previously entered manually during milestone-7
                // testing. It is now the official production endpoint, so
                // remove the redundant local override automatically.
                try { OnlineTestConfig.set(this, ""); } catch (Exception ignored) {}
                QuietLog.log("ONLINE", "test_rendezvous_override",
                        "enabled=0 reason=promoted_to_production");
            } else {
                QuietLog.log("ONLINE", "test_rendezvous_override", "enabled=1");
                startOnlineRendezvousAt(testUrl, true);
                return;
            }
        }

        OnlineStatus.check(result -> {
            if (result == null || stopped.get() || established.get()) return;

            // Production rendezvous signaling may be enabled before the public
            // Online dot turns green. The dot remains conservative until full
            // internet calling is validated, but CODE host/join can already
            // exchange candidates through the permanent rendezvous service.
            boolean rendezvousReady = result.reachable
                    && result.rendezvousUrl != null
                    && !result.rendezvousUrl.isEmpty();
            if (!rendezvousReady) {
                QuietLog.log("ONLINE", "rendezvous_disabled",
                        "reachable=" + (result.reachable ? 1 : 0)
                                + " calls=" + (result.callsAvailable ? 1 : 0)
                                + " endpoint=0");
                return;
            }

            QuietLog.log("ONLINE", "production_rendezvous",
                    "enabled=1 calls=" + (result.callsAvailable ? 1 : 0));
            startOnlineRendezvousAt(result.rendezvousUrl, false);
        });
    }

    private void startOnlineRendezvousAt(String rendezvousUrl, boolean testMode) {
        synchronized (SessionService.this) {
            if (stopped.get() || established.get() || onlineRendezvous != null) return;
            try {
                onlineRendezvous = new RendezvousClient(
                        rendezvousUrl,
                        code,
                        host,
                        CryptoChannel.PROTOCOL_VERSION,
                        new RendezvousClient.Listener() {
                            @Override public void onPeerMatch(RendezvousClient.Match match) {
                                if (match != null && !established.get()) {
                                    int count = match.candidates == null
                                            ? 0 : match.candidates.length();
                                    QuietLog.log("ONLINE", "peer_match_ready",
                                            "protocol=" + match.protocolVersion
                                                    + " candidates=" + count);
                                    if (testMode) {
                                        SessionBus.status(count > 0
                                                ? "Online test • peer matched • candidate received"
                                                : "Online test • peer matched • no candidate received");
                                    } else if (count > 0) {
                                        SessionBus.status(
                                                "Internet peer found • checking connection paths…");
                                        startInternetRelaySession(match);
                                    }
                                }
                            }

                            @Override public void onStatus(String onlineStatus) {
                                String safeStatus = onlineStatus == null ? "" : onlineStatus;
                                QuietLog.log("ONLINE", "rendezvous_status", safeStatus);
                                if (testMode && !established.get() && !safeStatus.isEmpty()) {
                                    SessionBus.status("TEST • " + safeStatus);
                                }
                            }
                        });

                try {
                    StunClient.Endpoint publicUdp = null;
                    try {
                        publicUdp = StunClient.probe(
                                udpSocket, "stun.cloudflare.com", 3478, 3500);
                    } catch (Exception first) {
                        try {
                            publicUdp = StunClient.probe(
                                    udpSocket, "stun.cloudflare.com", 53, 3000);
                        } catch (Exception ignored) {}
                    }

                    if (publicUdp != null) {
                        org.json.JSONArray candidates = new org.json.JSONArray();
                        candidates.put(publicUdp.toCandidateJson());
                        onlineRendezvous.setCandidates(candidates);
                        QuietLog.log("ONLINE", "candidate_ready",
                                "udp=1 count=1");
                        if (testMode) {
                            SessionBus.status("Online test • candidate ready • registering…");
                        }
                    } else {
                        QuietLog.log("ONLINE", "candidate_ready",
                                "udp=0 count=0");
                        if (testMode) {
                            SessionBus.status("Online test • no public UDP candidate");
                        }
                    }
                } catch (Exception e) {
                    QuietLog.log("ONLINE", "candidate_discovery_failed",
                            "reason=" + e.getClass().getSimpleName());
                    if (testMode) {
                        SessionBus.status("Online test • candidate discovery failed");
                    }
                }

                onlineRendezvous.start();
            } catch (Exception e) {
                QuietLog.log("ONLINE", "rendezvous_start_failed",
                        "reason=" + e.getClass().getSimpleName());
                if (testMode) {
                    SessionBus.status("Online test • rendezvous could not start");
                }
                onlineRendezvous = null;
            }
        }
    }

    private synchronized void stopOnlineRendezvous() {
        try { if (onlineRendezvous != null) onlineRendezvous.close(); }
        catch (Exception ignored) {}
        onlineRendezvous = null;
        onlineConnecting.set(false);
    }

    private void startInternetRelaySession(RendezvousClient.Match match) {
        if (match == null || stopped.get() || established.get()) return;
        if (match.protocolVersion != CryptoChannel.PROTOCOL_VERSION) {
            SessionBus.status("Internet peer uses a different QuietLink security version");
            QuietLog.log("ONLINE", "internet_protocol_mismatch",
                    "protocol=" + match.protocolVersion);
            return;
        }
        if (!onlineConnecting.compareAndSet(false, true)) return;

        io.execute(() -> {
            RendezvousRelaySocket relaySocket = null;
            try {
                InetSocketAddress mediaEndpoint = selectOnlineMediaCandidate(match.candidates);
                if (mediaEndpoint == null || mediaEndpoint.getAddress() == null) {
                    throw new IOException("No usable internet media candidate");
                }

                RendezvousClient rendezvous;
                synchronized (SessionService.this) {
                    rendezvous = onlineRendezvous;
                }
                if (rendezvous == null || stopped.get() || established.get()) return;

                SessionBus.status("Internet peer found • establishing secure session…");
                relaySocket = rendezvous.openRelaySocket(match, mediaEndpoint.getAddress());
                QuietLog.log("ONLINE", "internet_control_relay", "state=starting");
                establishCodeSession(
                        relaySocket,
                        mediaEndpoint.getAddress(),
                        mediaEndpoint.getPort(),
                        true);
            } catch (Exception e) {
                try { if (relaySocket != null) relaySocket.close(); } catch (Exception ignored) {}
                QuietLog.log("ONLINE", "internet_session_failed",
                        "reason=" + e.getClass().getSimpleName());
                if (!stopped.get() && !established.get()) {
                    SessionBus.status("Internet path not connected • local search continues");
                }
            } finally {
                if (!established.get()) {
                    onlineConnecting.set(false);
                }
            }
        });
    }

    private InetSocketAddress selectOnlineMediaCandidate(org.json.JSONArray candidates) {
        if (candidates == null) return null;
        for (int i = 0; i < candidates.length(); i++) {
            try {
                org.json.JSONObject candidate = candidates.optJSONObject(i);
                if (candidate == null) continue;
                if (!"srflx".equals(candidate.optString("kind", ""))) continue;
                String candidateHost = candidate.optString("host", "").trim();
                int candidatePort = candidate.optInt("udpPort", 0);
                if (candidateHost.isEmpty() || candidateHost.length() > 128
                        || candidatePort < 1 || candidatePort > 65535) continue;
                InetAddress address = InetAddress.getByName(candidateHost);
                if (address.isAnyLocalAddress() || address.isMulticastAddress()) continue;
                return new InetSocketAddress(address, candidatePort);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void startJoin() {
        try {
            SessionBus.status("Searching the same Wi-Fi first…");
            udpSocket = new DatagramSocket(0);
            lan = new LanDiscovery(this);
            lan.discover(code, this::connectOnce);
            main.postDelayed(() -> {
                if (!established.get() && !connecting.get() && !stopped.get()) {
                    startOnlineRendezvousBootstrap();
                }
            }, 1500);
            main.postDelayed(() -> {
                if (!established.get() && !connecting.get() && !stopped.get()) {
                    wifiDirect = new WifiDirectHelper(this);
                    wifiDirect.discoverAndConnect(code, this::connectOnce);
                }
            }, 8000);
        } catch (Exception e) {
            fail("Join failed: " + readable(e));
        }
    }

    private void connectOnce(String address, int port) {
        if (!connecting.compareAndSet(false, true) || stopped.get() || established.get()) return;
        io.execute(() -> {
            Socket s = null;
            try {
                SessionBus.status("Found peer • connecting…");
                s = new Socket();
                s.connect(new InetSocketAddress(address, port), 5000);
                establishCodeSession(s);
            } catch (Exception e) {
                if (s != null) try { s.close(); } catch (Exception ignored) {}
                connecting.set(false);
                if (wifiDirect != null) wifiDirect.resetJoinAttempt();
                if (!stopped.get() && !established.get()) SessionBus.status("Connection failed • continuing search…");
            }
        });
    }

    private synchronized void establishCodeSession(Socket socket) throws Exception {
        establishCodeSession(socket, null, 0, false);
    }

    private synchronized void establishCodeSession(
            Socket socket, InetAddress mediaAddress, int mediaPort, boolean internetRelay) throws Exception {
        if (established.get() || stopped.get()) {
            socket.close();
            return;
        }

        SessionBus.status(internetRelay ? "Establishing encrypted internet session…" : "Connecting…");
        CryptoChannel newCrypto = CryptoChannel.handshake(socket, host, code, udpSocket.getLocalPort());

        if (host) {
            newCrypto.sendControl("MODE:" + mode);
        } else {
            String init = newCrypto.readControl();
            if (!init.startsWith("MODE:")) throw new IOException("Mode negotiation failed");
            mode = Integer.parseInt(init.substring(5));
            if (mode < MODE_VOICE || mode > MODE_SLEEPING_BABY) throw new IOException("Unsupported session mode");
            sleepingBaby = mode == MODE_SLEEPING_BABY;
            if (sleepingBaby) mode = MODE_BABY;
            SessionBus.sleepingBaby = sleepingBaby;
            SessionBus.modeChanged(mode, false);
        }

        try {
            exchangeCodeIdentity(newCrypto, socket);
        } catch (Exception ignored) {
            // Identity exchange is optional for compatibility with older QuietLink builds.
        }

        crypto = newCrypto;
        if (internetRelay) {
            if (mediaAddress == null || mediaPort < 1 || mediaPort > 65535) {
                throw new IOException("Invalid internet media endpoint");
            }
            activateConnectedSession(socket, mediaAddress, mediaPort, true);
        } else {
            activateConnectedSession(socket);
        }
    }

    private void exchangeCodeIdentity(CryptoChannel channel, Socket socket) throws Exception {
        if (deviceIdentity == null) return;

        String local = "IDENTITY|"
                + deviceIdentity.fingerprint()
                + "|"
                + Base64.encodeToString(deviceIdentity.publicKeyEncoded(), Base64.NO_WRAP | Base64.URL_SAFE)
                + "|"
                + encodeName(deviceIdentity.deviceName());

        String remote = null;
        socket.setSoTimeout(5000);
        try {
            if (host) {
                channel.sendControl(local);
                remote = channel.readControl();
            } else {
                remote = channel.readControl();
                if (remote != null && remote.startsWith("IDENTITY|")) channel.sendControl(local);
            }
        } catch (SocketTimeoutException ignored) {
            // Older peers do not know about remembered-device identity exchange.
        } finally {
            socket.setSoTimeout(0);
        }

        if (remote == null || !remote.startsWith("IDENTITY|")) return;
        String[] parts = remote.split("\\|", 4);
        if (parts.length != 4) return;

        String fingerprint = parts[1];
        byte[] publicKey = Base64.decode(parts[2], Base64.NO_WRAP | Base64.URL_SAFE);
        if (!fingerprint.equals(DeviceIdentity.fingerprint(publicKey))) {
            throw new IOException("Peer identity fingerprint mismatch");
        }

        KnownDeviceStore.KnownDevice existing = knownDevices.get(fingerprint);
        if (existing != null && !Arrays.equals(existing.publicKey(), publicKey)) {
            throw new IOException("Known device identity changed");
        }

        String peerName = decodeName(parts[3]);
        rememberPeer(fingerprint, peerName, publicKey);
        activePeerFingerprint = fingerprint;
        activePeerIdentityPublic = Arrays.copyOf(publicKey, publicKey.length);
        SessionBus.peerName = safePeerName(peerName);
    }

    private synchronized void activateConnectedSession(Socket socket) throws Exception {
        activateConnectedSession(socket, socket.getInetAddress(), crypto.getPeerUdpPort(), false);
    }

    private synchronized void activateConnectedSession(
            Socket socket, InetAddress mediaAddress, int mediaPort, boolean internetRelay) throws Exception {
        boolean resumed = recovering || restoringFromCheckpoint;
        if (!resumed) {
            resumeAuthorizedForSession = localAutoResumePreference();
        }
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) PEER_TIMEOUT_MS);
        established.set(true);
        connecting.set(false);
        internetControlRelay = internetRelay;
        if (!internetRelay) {
            stopOnlineRendezvous();
        } else {
            onlineConnecting.set(true);
            QuietLog.log("ONLINE", "internet_control_relay", "state=connected");
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
        lastPeerSeenElapsedMs = SystemClock.elapsedRealtime();
        peerStaleWarningShown = false;
        if (lan != null) {
            lan.close();
            lan = null;
        }
        if (wifiDirect != null) wifiDirect.stopDiscoveryKeepConnection();
        if (sleepingBaby) remoteVideoEnabled = false;
        if (resumed) {
            remoteVideoEnabled = recoveryRemoteVideoEnabled;
            if (sleepingBaby && !babyStation) remoteVideoEnabled = false;
        }

        if (mediaAddress == null || mediaPort < 1 || mediaPort > 65535) {
            throw new IOException("Invalid media endpoint");
        }
        if (internetRelay) {
            primeInternetUdpPath(mediaAddress, mediaPort);
        }
        media = new MediaTransport(udpSocket, crypto, mediaAddress, mediaPort, (type, payload) -> {
            if (type == MediaTransport.TYPE_AUDIO && audio != null) audio.onRemoteAudio(payload);
            else if (type == MediaTransport.TYPE_VIDEO && video != null) video.onRemoteChunk(payload);
        });
        media.start();

        // Re-apply sticky mute on recovery, but a fresh Baby Station must never
        // inherit a mute from an earlier Voice/Video session.
        micMuted = SessionBus.localMicMuted;
        if (!resumed && mode == MODE_BABY && babyStation) {
            setMicMuted(false);
        }
        QuietLog.log("AUDIO", "session_audio_state",
                "mode=" + mode
                        + " baby=" + (babyStation ? 1 : 0)
                        + " sleeping=" + (sleepingBaby ? 1 : 0)
                        + " muted=" + (micMuted ? 1 : 0)
                        + " resumed=" + (resumed ? 1 : 0));
        audio = new AudioEngine(this, media, this::canTransmitAudio, this::onAudioLevel);
        audio.start();
        audio.setVolume(outputVolume);
        boolean parentSleeping = sleepingBaby && !babyStation;
        listening = resumed ? recoveryListening : !parentSleeping;
        if (parentSleeping) listening = false;
        audio.setPlaybackEnabled(listening);

        if (mode != MODE_VOICE) {
            video = new VideoEngine(
                    this,
                    media,
                    degrees -> sendControl("VIDEO_ROT:" + degrees),
                    h264Capability,
                    reason -> {
                        peerH264Capable = false;
                        sendControl("VIDEO_FALLBACK_JPEG");
                    },
                    () -> sendControl("VIDEO_KEYFRAME_REQUEST"));
            boolean cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean sendVideo = resumed
                    ? (recoveryLocalVideoEnabled && cameraAllowed)
                    : ((mode == MODE_VIDEO && cameraAllowed) || (mode == MODE_BABY && babyStation));
            localVideoEnabled = sendVideo && cameraAllowed;
            if (localVideoEnabled) upgradeForegroundForCamera();
            video.start(localVideoEnabled);
        } else {
            localVideoEnabled = false;
        }

        recovering = false;
        restoringFromCheckpoint = false;
        stopRecoveryResources(false);
        startRecoveryBeacon();
        startDiagnosticsLoop();
        startServiceWatchdog();
        SessionBus.connected(crypto.getVerification());
        SessionBus.status(resumed ? "Reconnected • " + connectionLabel().substring("Connected • ".length()) : connectionLabel());
        updateSessionNotification(connectionLabel());
        cancelConnectionLostAlert();
        updateReliabilityLocks();
        io.execute(this::controlLoop);
        io.execute(this::heartbeatLoop);

        // Capability negotiation is deliberately harmless to older peers:
        // unknown control messages are ignored, so JPEG remains the fallback.
        sendControl("VIDEO_CAPS:" + localVideoCaps());
        sendControl("RESUME_CAP:" + (localAutoResumePreference() ? "1" : "0"));
        persistRecoveryCheckpoint();

        if (mode == MODE_BABY) {
            if (babyStation) {
                sendBabySettings();
                sendBabyAuxState();
            } else {
                sendControl("BABY_STATE_REQUEST");
                sendControl("BABY_AUX_STATE_REQUEST");
            }
        }
    }

    private void primeInternetUdpPath(InetAddress address, int port) {
        if (udpSocket == null || udpSocket.isClosed() || address == null || port < 1 || port > 65535) return;
        // Anonymous, content-free UDP probes create the NAT pinhole in both
        // directions before encrypted media begins. They carry no identity,
        // room token, pairing code, media, or session key material.
        byte[] probe = new byte[] { 0x51, 0x4c, 0x50, 0x31 }; // QLP1
        int sent = 0;
        for (int i = 0; i < 3; i++) {
            try {
                udpSocket.send(new DatagramPacket(probe, probe.length, address, port));
                sent++;
                if (i < 2) Thread.sleep(35L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {}
        }
        QuietLog.log("ONLINE", "udp_path_warmup", "sent=" + sent);
    }

    private String localVideoCaps() {
        return h264Capability != null
                && h264Capability.usable()
                && !RotationLabConfig.forceJpeg(this)
                ? "H264_720P30,JPEG"
                : "JPEG";
    }

    private boolean canTransmitAudio() {
        if (mode == MODE_BABY && !babyStation) {
            // Hold-to-talk is explicit temporary permission to transmit from
            // Parent Station, even when the normal call mic toggle is muted.
            return ptt;
        }
        if (micMuted) return false;
        if (mode == MODE_BABY) return babyStation;
        return true;
    }

    private void onAudioLevel(float level) {
        SessionBus.level(level);
        if (sleepingBaby && !babyStation) {
            if (level >= soundThreshold) loudMs += 10;
            else loudMs = Math.max(0, loudMs - 30);
            if (loudMs >= 700) {
                loudMs = 0;
                long now = System.currentTimeMillis();
                if (now - lastAlert > 10000) {
                    lastAlert = now;
                    SessionBus.status("Sound detected • room " + Math.round(level * 100f) + "%");
                    postSoundAlert();
                }
            }
        } else {
            loudMs = 0;
        }
    }

    private void controlLoop() {
        try {
            while (!stopped.get() && established.get()) {
                String c = crypto.readControl();
                boolean wasStale = peerStaleWarningShown;
                lastPeerSeenElapsedMs = SystemClock.elapsedRealtime();
                peerStaleWarningShown = false;
                if (wasStale) SessionBus.status(connectionLabel());
                if ("SWITCH_CAMERA".equals(c) && video != null) {
                    video.switchCamera();
                } else if (c.startsWith("MODE_CHANGE:")) {
                    int requested = Integer.parseInt(c.substring("MODE_CHANGE:".length()));
                    if (!(requested == MODE_BABY && host)) {
                        setSessionMode(requested, false);
                    }
                } else if (c.startsWith("CHAT:")) {
                    receiveChat(c.substring("CHAT:".length()));
                } else if (c.startsWith("RESUME_CAP:")) {
                    if ("1".equals(c.substring("RESUME_CAP:".length()))) {
                        resumeAuthorizedForSession = true;
                        persistRecoveryCheckpoint();
                        startRecoveryBeacon();
                    }
                } else if (c.startsWith("VIDEO_CAPS:")) {
                    String caps = c.substring("VIDEO_CAPS:".length());
                    peerH264Capable = caps.contains("H264_720P30")
                            && h264Capability != null
                            && h264Capability.usable()
                            && !RotationLabConfig.forceJpeg(this);
                    if (video != null) video.setH264Enabled(peerH264Capable);
                    if (peerH264Capable) {
                        SessionBus.status(connectionLabel() + " • HD video negotiated");
                    }
                } else if ("VIDEO_CAPS_REQUEST".equals(c)) {
                    sendControl("VIDEO_CAPS:" + localVideoCaps());
                } else if ("VIDEO_FALLBACK_JPEG".equals(c)) {
                    peerH264Capable = false;
                    if (video != null) video.setH264Enabled(false);
                    SessionBus.status(connectionLabel() + " • video compatibility mode");
                } else if ("VIDEO_KEYFRAME_REQUEST".equals(c)) {
                    if (video != null) video.requestKeyFrame();
                } else if (c.startsWith("VIDEO_ROT:")) {
                    if (video != null) {
                        try { video.setRemoteRotation(Integer.parseInt(c.substring("VIDEO_ROT:".length()))); }
                        catch (Exception ignored) {}
                    }
                } else if ("BABY_STATE_REQUEST".equals(c)) {
                    if (mode == MODE_BABY && babyStation) sendBabySettings();
                } else if ("BABY_AUX_STATE_REQUEST".equals(c)) {
                    if (mode == MODE_BABY && babyStation) sendBabyAuxState();
                } else if (c.startsWith("BABY_AUX_STATE:")) {
                    String[] parts = c.split(":", 3);
                    if (parts.length == 3 && mode == MODE_BABY && !babyStation) {
                        SessionBus.babyAuxState(
                                "1".equals(parts[1]), "1".equals(parts[2]), true);
                    }
                } else if (c.startsWith("BABY_TORCH_SET:")) {
                    if (mode == MODE_BABY && babyStation) {
                        babyTorchEnabled = "1".equals(
                                c.substring("BABY_TORCH_SET:".length()));
                        if (video != null) video.setTorchEnabled(babyTorchEnabled);
                        SessionBus.babyAuxState(
                                babyTorchEnabled, babyBrightnessBoost, true);
                        sendBabyAuxState();
                        SessionBus.status(babyTorchEnabled
                                ? "Parent turned flashlight on"
                                : "Parent turned flashlight off");
                    }
                } else if (c.startsWith("BABY_BRIGHTNESS_SET:")) {
                    if (mode == MODE_BABY && babyStation) {
                        babyBrightnessBoost = "1".equals(
                                c.substring("BABY_BRIGHTNESS_SET:".length()));
                        SessionBus.babyAuxState(
                                babyTorchEnabled, babyBrightnessBoost, true);
                        sendBabyAuxState();
                        SessionBus.status(babyBrightnessBoost
                                ? "Parent increased screen brightness"
                                : "Parent restored screen brightness");
                    }
                } else if (c.startsWith("BABY_STATE:")) {
                    String[] parts = c.split(":", 3);
                    if (parts.length == 3 && mode == MODE_BABY && !babyStation) {
                        boolean micOn = "1".equals(parts[1]);
                        boolean cameraOn = "1".equals(parts[2]);
                        remoteVideoEnabled = cameraOn;
                        SessionBus.babySettings(micOn, cameraOn, true);
                    }
                } else if (c.startsWith("BABY_MIC_SET:")) {
                    if (mode == MODE_BABY && babyStation) {
                        boolean enabled = "1".equals(c.substring("BABY_MIC_SET:".length()));
                        QuietLog.log("AUDIO", "baby_mic_control_rx",
                                "enabled=" + (enabled ? 1 : 0));
                        setMicMuted(!enabled);
                        sendBabySettings();
                    }
                } else if (c.startsWith("BABY_CAMERA_SET:")) {
                    if (mode == MODE_BABY && babyStation) {
                        boolean enabled = "1".equals(c.substring("BABY_CAMERA_SET:".length()));
                        setLocalVideoSending(enabled);
                        sendControl(enabled ? "PEER_VIDEO_ON" : "PEER_VIDEO_OFF");
                        sendBabySettings();
                    }
                } else if (c.startsWith("BABY_ROLE:")) {
                    if (mode == MODE_BABY) {
                        boolean peerIsBaby = "1".equals(c.substring("BABY_ROLE:".length()));
                        setBabyStation(!peerIsBaby, false);
                    }
                } else if ("SLEEPING_ON".equals(c)) {
                    setSleepingBaby(true, false);
                } else if ("SLEEPING_OFF".equals(c)) {
                    setSleepingBaby(false, false);
                } else if ("VIDEO_ON".equals(c) && video != null) {
                    setLocalVideoSending(true);
                    sendControl("PEER_VIDEO_ON");
                    if (mode == MODE_BABY && babyStation) sendBabySettings();
                } else if ("VIDEO_OFF".equals(c) && video != null) {
                    setLocalVideoSending(false);
                    sendControl("PEER_VIDEO_OFF");
                    if (mode == MODE_BABY && babyStation) sendBabySettings();
                } else if ("PEER_VIDEO_OFF".equals(c)) {
                    QuietLog.log("SERVICE", "peer_camera_state", "enabled=0");
                    SessionBus.remoteVideoEnabled(false);
                    SessionBus.video(null);
                    if (video != null) video.setRemoteVideoExpected(false);
                } else if ("PEER_VIDEO_ON".equals(c)) {
                    QuietLog.log("SERVICE", "peer_camera_state", "enabled=1");
                    SessionBus.remoteVideoEnabled(true);
                    SessionBus.video(null);
                    if (video != null) video.setRemoteVideoExpected(true);
                } else if (c.startsWith("DIAG_PING:")) {
                    sendControl("DIAG_PONG:" + c.substring("DIAG_PING:".length()));
                } else if (c.startsWith("DIAG_PONG:")) {
                    try {
                        long sent = Long.parseLong(c.substring("DIAG_PONG:".length()));
                        long sample = SystemClock.elapsedRealtime() - sent;
                        if (sample >= 0L && sample < 60_000L) lastDiagRttMs = sample;
                    } catch (Exception ignored) {}
                } else if ("PING".equals(c)) {
                    sendControl("PONG");
                } else if ("PONG".equals(c)) {
                    // Keepalive acknowledgement.
                } else if (c.startsWith("BYE_MANUAL")) {
                    SessionBus.autoConnectSuspended = true;
                    String who = safePeerName(SessionBus.peerName);
                    String[] bye = c.split("\\|", 2);
                    if (bye.length == 2 && !bye[1].isEmpty()) {
                        who = safePeerName(decodeName(bye[1]));
                    }
                    recovering = false;
                    stopSession(who + " disconnected");
                    return;
                } else if ("BYE".equals(c)) {
                    stopSession("Peer disconnected");
                    return;
                }
            }
        } catch (SocketTimeoutException e) {
            if (!stopped.get()) handleConnectionLoss(peerLostReason("Heartbeat timeout"));
        } catch (Exception e) {
            if (!stopped.get()) handleConnectionLoss(peerLostReason("Connection lost"));
        }
    }

    private void heartbeatLoop() {
        try {
            while (!stopped.get() && established.get()) {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                if (stopped.get() || !established.get()) break;

                long silentFor = SystemClock.elapsedRealtime() - lastPeerSeenElapsedMs;
                if (silentFor >= PEER_TIMEOUT_MS) {
                    handleConnectionLoss(peerLostReason("Heartbeat timeout"));
                    return;
                }
                if (silentFor >= PEER_STALE_WARN_MS && !peerStaleWarningShown) {
                    peerStaleWarningShown = true;
                    SessionBus.status(mode == MODE_BABY && !babyStation
                            ? "Baby Station not responding…"
                            : "Peer not responding…");
                }
                sendControl("PING");
                sendControl("DIAG_PING:" + SystemClock.elapsedRealtime());
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void handleConnectionLoss(String reason) {
        if (stopped.get() || recovering) return;
        if (!established.get() && !SessionBus.active) return;

        // Brief transport loss is always recoverable once the session has
        // already been authenticated. Fresh calls remain manual; only an
        // explicit BYE_MANUAL / local Disconnect ends the logical session.
        //
        // resumeAuthorizedForSession is intentionally NOT checked here. That
        // flag is reserved for durable Android service/process restoration.
        // Recovery is a continuation of an already authenticated session.
        // If the peer identity was unavailable (e.g. an old compatible build),
        // fall back to the old clean disconnect behavior rather than reconnecting
        // to an unverified device.
        if (activePeerFingerprint == null || activePeerIdentityPublic == null || deviceIdentity == null) {
            stopSession(reason);
            return;
        }

        recovering = true;
        recoveryCount++;
        recoveryStartedElapsedMs = SystemClock.elapsedRealtime();
        recoveryFallbackProbeUsed = false;
        established.set(false);
        connecting.set(false);
        stopRecoveryBeacon();
        recoveryLocalVideoEnabled = localVideoEnabled;
        recoveryRemoteVideoEnabled = remoteVideoEnabled;
        recoveryListening = listening;

        if (internetControlRelay) {
            QuietLog.log("ONLINE", "internet_control_relay", "state=lost");
            stopOnlineRendezvous();
            internetControlRelay = false;
        }

        try { if (audio != null) audio.close(); } catch (Exception ignored) {}
        try { if (video != null) video.close(); } catch (Exception ignored) {}
        try { if (media != null) media.close(); } catch (Exception ignored) {}
        try { if (crypto != null) crypto.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (lan != null) lan.close(); } catch (Exception ignored) {}
        try { if (wifiDirect != null) wifiDirect.close(); } catch (Exception ignored) {}

        audio = null;
        video = null;
        media = null;
        crypto = null;
        serverSocket = null;
        lan = null;
        wifiDirect = null;
        peerH264Capable = false;

        SessionBus.reconnecting("Connection interrupted • reconnecting to " + safePeerName(SessionBus.peerName) + "…");
        updateSessionNotification("Reconnecting • " + safePeerName(SessionBus.peerName));

        if (mode == MODE_BABY && !babyStation && sleepingBaby) {
            postConnectionLostAlert(reason);
        }

        if (resumeAuthorizedForSession) persistRecoveryCheckpoint();
        startRecoveryResources();
        scheduleRecoveryMaintenance();
        startServiceWatchdog();
    }

    private boolean localAutoResumePreference() {
        if (activePeerFingerprint == null || knownDevices == null) return false;
        KnownDeviceStore.KnownDevice known = knownDevices.get(activePeerFingerprint);
        return known != null && known.autoConnect;
    }

    private synchronized void startRecoveryBeacon() {
        stopRecoveryBeacon();
        if (!established.get() || recovering || stopped.get()
                || deviceIdentity == null || activePeerFingerprint == null
                || activePeerIdentityPublic == null || udpSocket == null || udpSocket.isClosed()) {
            return;
        }

        try {
            recoveryBeaconServerSocket = new ServerSocket(0);
            recoveryBeaconServerSocket.setReuseAddress(true);
            recoveryBeacon = new PeerDiscovery(this, deviceIdentity.fingerprint(), new PeerDiscovery.Listener() {
                @Override public void onPeers(List<PeerDiscovery.Peer> peers) {}
                @Override public void onStatus(String status) {}
            });
            recoveryBeacon.advertise(
                    recoveryBeaconServerSocket.getLocalPort(),
                    deviceIdentity.fingerprint(),
                    deviceIdentity.deviceName());
            io.execute(this::recoveryBeaconAcceptLoop);
        } catch (Exception ignored) {
            stopRecoveryBeacon();
        }
    }

    private void recoveryBeaconAcceptLoop() {
        while (established.get() && !recovering && !stopped.get()) {
            Socket socket = null;
            try {
                ServerSocket server = recoveryBeaconServerSocket;
                if (server == null || server.isClosed()) return;
                socket = server.accept();
                Socket accepted = socket;
                if (!incomingHandshakeSlots.tryAcquire()) {
                    try { accepted.close(); } catch (Exception ignored) {}
                    continue;
                }
                io.execute(() -> {
                    try { handleRecoveryBeaconIncoming(accepted); }
                    finally { incomingHandshakeSlots.release(); }
                });
            } catch (SocketException e) {
                return;
            } catch (Exception e) {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handleRecoveryBeaconIncoming(Socket socket) {
        CryptoChannel channel = null;
        try {
            if (!established.get() || recovering || stopped.get()
                    || udpSocket == null || udpSocket.isClosed()) {
                socket.close();
                return;
            }

            channel = CryptoChannel.handshakeNearby(
                    socket, true, deviceIdentity.keyPair(), udpSocket.getLocalPort());
            if (!isExpectedRecoveryPeer(channel)) {
                channel.close();
                return;
            }

            socket.setSoTimeout(6000);
            String request = channel.readControl();
            if (request == null || !request.startsWith("RECOVER|")) {
                channel.close();
                return;
            }

            // Authentication succeeded for the exact peer from this call.
            // A recovery request is therefore authoritative evidence that its
            // side of the old transport has died.
            channel.sendControl("RECOVER_SYNC");
            try { channel.close(); } catch (Exception ignored) {}
            channel = null;
            handleConnectionLoss(peerLostReason("Peer requested recovery"));
        } catch (Exception e) {
            try { if (channel != null) channel.close(); else socket.close(); } catch (Exception ignored) {}
        }
    }

    private synchronized void stopRecoveryBeacon() {
        try { if (recoveryBeacon != null) recoveryBeacon.close(); } catch (Exception ignored) {}
        try { if (recoveryBeaconServerSocket != null) recoveryBeaconServerSocket.close(); } catch (Exception ignored) {}
        recoveryBeacon = null;
        recoveryBeaconServerSocket = null;
    }

    private synchronized void startRecoveryResources() {
        if (!recovering || stopped.get()) return;

        try {
            if (udpSocket == null || udpSocket.isClosed()) udpSocket = new DatagramSocket(0);
            if (recoveryServerSocket == null || recoveryServerSocket.isClosed()) {
                recoveryServerSocket = new ServerSocket(0);
                recoveryServerSocket.setReuseAddress(true);
                io.execute(this::recoveryAcceptLoop);
            }

            if (recoveryDiscovery != null) {
                try { recoveryDiscovery.close(); } catch (Exception ignored) {}
            }
            recoveryPeers = Collections.emptyList();
            recoveryDiscoveryFailed = false;
            recoveryDiscoveryStartedElapsedMs = SystemClock.elapsedRealtime();

            recoveryDiscovery = new PeerDiscovery(this, deviceIdentity.fingerprint(), new PeerDiscovery.Listener() {
                @Override public void onPeers(List<PeerDiscovery.Peer> peers) {
                    if (!recovering) return;
                    recoveryPeers = peers == null ? Collections.emptyList() : peers;
                    maybeRecoverConnect(recoveryPeers);
                }

                @Override public void onStatus(String status) {
                    if (!recovering) return;
                    if (status != null && (status.contains("unavailable") || status.contains("Could not"))) {
                        recoveryDiscoveryFailed = true;
                    }
                }
            });
            recoveryDiscovery.advertise(
                    recoveryServerSocket.getLocalPort(),
                    deviceIdentity.fingerprint(),
                    deviceIdentity.deviceName());
            recoveryDiscovery.discover();
        } catch (Exception e) {
            recoveryDiscoveryFailed = true;
            SessionBus.status("Reconnecting • waiting for network…");
        }
    }

    private void recoveryAcceptLoop() {
        while (recovering && !stopped.get() && !established.get()) {
            Socket socket = null;
            try {
                ServerSocket server = recoveryServerSocket;
                if (server == null || server.isClosed()) return;
                socket = server.accept();
                Socket accepted = socket;
                if (!incomingHandshakeSlots.tryAcquire()) {
                    try { accepted.close(); } catch (Exception ignored) {}
                    continue;
                }
                io.execute(() -> {
                    try { handleRecoveryIncoming(accepted); }
                    finally { incomingHandshakeSlots.release(); }
                });
            } catch (SocketException e) {
                return;
            } catch (Exception e) {
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handleRecoveryIncoming(Socket socket) {
        CryptoChannel channel = null;
        try {
            if (!recovering || stopped.get() || established.get()) {
                socket.close();
                return;
            }

            channel = CryptoChannel.handshakeNearby(
                    socket, true, deviceIdentity.keyPair(), udpSocket.getLocalPort());

            if (!isExpectedRecoveryPeer(channel)) {
                channel.close();
                return;
            }

            socket.setSoTimeout(8000);
            String request = channel.readControl();
            if (request == null || !request.startsWith("RECOVER|")) {
                channel.close();
                return;
            }

            channel.sendControl("RECOVER_ACCEPT|" + encodeName(deviceIdentity.deviceName()));
            String ready = channel.readControl();
            socket.setSoTimeout(0);
            if (!"READY".equals(ready)) throw new IOException("Recovery peer did not confirm");

            finalizeRecovered(channel, socket);
        } catch (Exception e) {
            try { if (channel != null) channel.close(); else socket.close(); } catch (Exception ignored) {}
        }
    }

    private void maybeRecoverConnect(List<PeerDiscovery.Peer> peers) {
        if (!recovering || stopped.get() || established.get() || connecting.get()
                || peers == null || deviceIdentity == null || activePeerFingerprint == null) return;

        PeerDiscovery.Peer expected = null;
        for (PeerDiscovery.Peer peer : peers) {
            if (activePeerFingerprint.equals(peer.fingerprint)) {
                expected = peer;
                break;
            }
        }
        if (expected == null) return;

        boolean preferredDialer =
                deviceIdentity.fingerprint().compareTo(activePeerFingerprint) < 0;
        if (preferredDialer) {
            connectRecoveryPeer(expected, false);
            return;
        }

        // If this is the non-preferred side, give the preferred peer a short
        // chance to dial first. If it is still on the old healthy socket, it
        // will never dial us, so perform one authenticated sync probe. A healthy
        // peer answers RECOVER_SYNC and immediately enters recovery itself.
        long elapsed = SystemClock.elapsedRealtime() - recoveryStartedElapsedMs;
        if (!recoveryFallbackProbeUsed) {
            if (elapsed < 1200L) {
                long delay = Math.max(100L, 1200L - elapsed);
                main.postDelayed(() -> maybeRecoverConnect(recoveryPeers), delay);
                return;
            }
            recoveryFallbackProbeUsed = true;
            connectRecoveryPeer(expected, true);
        }
    }

    private void connectRecoveryPeer(PeerDiscovery.Peer peer, boolean fallbackProbe) {
        if (!recovering || peer == null || !connecting.compareAndSet(false, true)) return;

        io.execute(() -> {
            Socket socket = null;
            CryptoChannel channel = null;
            try {
                SessionBus.status("Reconnecting to " + safePeerName(SessionBus.peerName) + "…");
                socket = new Socket();
                socket.connect(new InetSocketAddress(peer.host, peer.port), 4500);
                channel = CryptoChannel.handshakeNearby(
                        socket, false, deviceIdentity.keyPair(), udpSocket.getLocalPort());

                if (!isExpectedRecoveryPeer(channel)) {
                    throw new IOException("Recovery device identity changed");
                }

                channel.sendControl("RECOVER|" + encodeName(deviceIdentity.deviceName()));
                socket.setSoTimeout(8000);
                String answer = channel.readControl();
                socket.setSoTimeout(0);
                if ("RECOVER_SYNC".equals(answer)) {
                    // The peer still believed the previous TCP session was alive.
                    // Our authenticated recovery probe has now forced it into the
                    // same recovery state; retry once its recovery advertisement appears.
                    try { channel.close(); } catch (Exception ignored) {}
                    connecting.set(false);
                    SessionBus.status("Peer detected interruption • reconnecting…");
                    // The peer is switching from its old healthy socket into
                    // recovery. Let the deterministic preferred side reconnect.
                    return;
                }
                if (answer == null || !answer.startsWith("RECOVER_ACCEPT|")) {
                    throw new IOException("Recovery was not accepted");
                }

                channel.sendControl("READY");
                finalizeRecovered(channel, socket);
            } catch (Exception e) {
                try {
                    if (channel != null) channel.close();
                    else if (socket != null) socket.close();
                } catch (Exception ignored) {}
                connecting.set(false);
                if (recovering) {
                    SessionBus.status("Connection interrupted • retrying…");
                    if (fallbackProbe) {
                        main.postDelayed(() -> {
                            if (recovering && !established.get()) {
                                recoveryFallbackProbeUsed = false;
                                maybeRecoverConnect(recoveryPeers);
                            }
                        }, 2500L);
                    }
                }
            }
        });
    }

    private boolean isExpectedRecoveryPeer(CryptoChannel channel) {
        if (channel == null || activePeerFingerprint == null || activePeerIdentityPublic == null) return false;
        return activePeerFingerprint.equals(channel.getPeerFingerprint())
                && Arrays.equals(activePeerIdentityPublic, channel.getPeerIdentityPublic());
    }

    private synchronized void finalizeRecovered(CryptoChannel channel, Socket socket) throws Exception {
        if (!recovering || stopped.get() || established.get()) {
            channel.close();
            return;
        }

        activePeerFingerprint = channel.getPeerFingerprint();
        activePeerIdentityPublic = Arrays.copyOf(
                channel.getPeerIdentityPublic(), channel.getPeerIdentityPublic().length);
        crypto = channel;
        connecting.set(false);
        activateConnectedSession(socket);
    }

    private void scheduleRecoveryMaintenance() {
        main.postDelayed(() -> {
            if (!recovering || stopped.get() || established.get()) return;

            long age = SystemClock.elapsedRealtime() - recoveryDiscoveryStartedElapsedMs;
            if (recoveryDiscovery == null || recoveryDiscoveryFailed
                    || (recoveryPeers.isEmpty() && age >= RECOVERY_DISCOVERY_RESTART_MS)) {
                startRecoveryResources();
            } else {
                maybeRecoverConnect(recoveryPeers);
            }
            scheduleRecoveryMaintenance();
        }, RECOVERY_RETRY_MS);
    }

    private synchronized void stopRecoveryResources(boolean closeUdp) {
        recoveryPeers = Collections.emptyList();
        recoveryDiscoveryFailed = false;

        try { if (recoveryDiscovery != null) recoveryDiscovery.close(); } catch (Exception ignored) {}
        try { if (recoveryServerSocket != null) recoveryServerSocket.close(); } catch (Exception ignored) {}
        recoveryDiscovery = null;
        recoveryServerSocket = null;

        if (closeUdp) {
            try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
            udpSocket = null;
        }
    }

    public static boolean hasRecoverableCheckpoint(Context context) {
        try {
            android.content.SharedPreferences p =
                    context.getSharedPreferences(RECOVERY_PREF, Context.MODE_PRIVATE);
            if (!p.getBoolean("active", false)) return false;
            long savedAt = p.getLong("saved_at", 0L);
            return savedAt > 0L
                    && System.currentTimeMillis() - savedAt <= RECOVERY_CHECKPOINT_MAX_AGE_MS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void persistRecoveryCheckpoint() {
        if (!resumeAuthorizedForSession || activePeerFingerprint == null
                || activePeerIdentityPublic == null || stopped.get()) return;
        if (!established.get() && !recovering && !restoringFromCheckpoint) return;

        try {
            getSharedPreferences(RECOVERY_PREF, MODE_PRIVATE).edit()
                    .putBoolean("active", true)
                    .putLong("saved_at", System.currentTimeMillis())
                    .putString("peer_fingerprint", activePeerFingerprint)
                    .putString("peer_public", Base64.encodeToString(
                            activePeerIdentityPublic, Base64.NO_WRAP | Base64.URL_SAFE))
                    .putString("peer_name", safePeerName(SessionBus.peerName))
                    .putInt("mode", mode)
                    .putBoolean("host", host)
                    .putBoolean("baby_station", babyStation)
                    .putBoolean("sleeping_baby", sleepingBaby)
                    .putBoolean("mic_muted", micMuted)
                    .putBoolean("local_video", localVideoEnabled)
                    .putBoolean("remote_video", remoteVideoEnabled)
                    .putBoolean("listening", listening)
                    .putFloat("sound_threshold", soundThreshold)
                    .putFloat("output_volume", outputVolume)
                    .putBoolean("resume_authorized", resumeAuthorizedForSession)
                    .apply();
        } catch (Exception ignored) {}
    }

    private void clearRecoveryCheckpoint() {
        try {
            getSharedPreferences(RECOVERY_PREF, MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {}
    }

    private synchronized boolean restoreRecoveryCheckpoint() {
        if (established.get() || recovering || restoringFromCheckpoint) return true;
        if (deviceIdentity == null) return false;

        try {
            android.content.SharedPreferences p = getSharedPreferences(RECOVERY_PREF, MODE_PRIVATE);
            if (!p.getBoolean("active", false)) return false;
            long savedAt = p.getLong("saved_at", 0L);
            if (savedAt <= 0L
                    || System.currentTimeMillis() - savedAt > RECOVERY_CHECKPOINT_MAX_AGE_MS) {
                clearRecoveryCheckpoint();
                return false;
            }

            String fingerprint = p.getString("peer_fingerprint", "");
            String encodedPublic = p.getString("peer_public", "");
            byte[] publicKey = Base64.decode(encodedPublic, Base64.NO_WRAP | Base64.URL_SAFE);
            if (fingerprint.isEmpty() || publicKey.length == 0
                    || !fingerprint.equals(DeviceIdentity.fingerprint(publicKey))) {
                clearRecoveryCheckpoint();
                return false;
            }

            activePeerFingerprint = fingerprint;
            activePeerIdentityPublic = Arrays.copyOf(publicKey, publicKey.length);
            resumeAuthorizedForSession = p.getBoolean("resume_authorized", true);
            if (!resumeAuthorizedForSession) {
                clearRecoveryCheckpoint();
                return false;
            }

            host = p.getBoolean("host", false);
            mode = p.getInt("mode", MODE_VOICE);
            if (mode < MODE_VOICE || mode > MODE_BABY) mode = MODE_VOICE;
            babyStation = mode == MODE_BABY && p.getBoolean("baby_station", host);
            sleepingBaby = mode == MODE_BABY && p.getBoolean("sleeping_baby", false);
            micMuted = p.getBoolean("mic_muted", false);
            SessionBus.localMicMuted = micMuted;
            localVideoEnabled = p.getBoolean("local_video",
                    mode == MODE_VIDEO || (mode == MODE_BABY && babyStation));
            remoteVideoEnabled = p.getBoolean("remote_video", !sleepingBaby);
            listening = p.getBoolean("listening", !(sleepingBaby && !babyStation));
            soundThreshold = Math.max(0f, Math.min(1f,
                    p.getFloat("sound_threshold", 0.12f)));
            outputVolume = Math.max(0f, Math.min(1f,
                    p.getFloat("output_volume", 1.0f)));

            recoveryLocalVideoEnabled = localVideoEnabled;
            recoveryRemoteVideoEnabled = remoteVideoEnabled;
            recoveryListening = listening;
            recoveryCount++;
            recoveryStartedElapsedMs = SystemClock.elapsedRealtime();
            recoveryFallbackProbeUsed = false;
            restoringFromCheckpoint = true;
            recovering = true;
            stopped.set(false);
            established.set(false);
            connecting.set(false);

            SessionBus.active = true;
            SessionBus.code = "";
            SessionBus.peerName = safePeerName(p.getString("peer_name", "Known device"));
            SessionBus.sleepingBaby = sleepingBaby;
            SessionBus.modeChanged(mode, host);
            SessionBus.babyRoleChanged(babyStation);
            SessionBus.reconnecting("Restoring session • waiting for " + SessionBus.peerName + "…");

            startForegroundRecovery();
            updateReliabilityLocks();
            startDiagnosticsLoop();
            startRecoveryResources();
            scheduleRecoveryMaintenance();
            startServiceWatchdog();
            persistRecoveryCheckpoint();
            return true;
        } catch (Exception e) {
            clearRecoveryCheckpoint();
            restoringFromCheckpoint = false;
            recovering = false;
            return false;
        }
    }

    private void startForegroundRecovery() {
        Notification n = sessionNotification("Restoring previous session • reconnecting…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFY_ID, n);
            }
        } catch (Exception e) {
            startForeground(NOTIFY_ID, n);
        }
    }

    private void startServiceWatchdog() {
        if (!serviceWatchdogStarted.compareAndSet(false, true)) return;
        main.postDelayed(this::serviceWatchdogTick, SERVICE_WATCHDOG_INTERVAL_MS);
    }

    private void serviceWatchdogTick() {
        if (!serviceWatchdogStarted.get() || stopped.get()) return;

        if (resumeAuthorizedForSession && SessionBus.active) {
            persistRecoveryCheckpoint();

            if (!established.get() && !recovering) {
                recovering = true;
                recoveryLocalVideoEnabled = localVideoEnabled;
                recoveryRemoteVideoEnabled = remoteVideoEnabled;
                recoveryListening = listening;
                SessionBus.reconnecting("Session watchdog • reconnecting to "
                        + safePeerName(SessionBus.peerName) + "…");
                startForegroundRecovery();
                startRecoveryResources();
                scheduleRecoveryMaintenance();
            } else if (recovering && (recoveryDiscovery == null || recoveryDiscoveryFailed)) {
                startRecoveryResources();
            }
        }

        main.postDelayed(this::serviceWatchdogTick, SERVICE_WATCHDOG_INTERVAL_MS);
    }

    private void closeTransientForServiceRestart() {
        try { if (audio != null) audio.close(); } catch (Exception ignored) {}
        try { if (video != null) video.close(); } catch (Exception ignored) {}
        try { if (media != null) media.close(); } catch (Exception ignored) {}
        try { if (crypto != null) crypto.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        try { if (lan != null) lan.close(); } catch (Exception ignored) {}
        try { if (wifiDirect != null) wifiDirect.close(); } catch (Exception ignored) {}
        try { if (peerDiscovery != null) peerDiscovery.close(); } catch (Exception ignored) {}
        try { if (pairingServerSocket != null) pairingServerSocket.close(); } catch (Exception ignored) {}
        stopOnlineRendezvous();
        internetControlRelay = false;
        stopRecoveryResources(true);
        stopRecoveryBeacon();
        releaseReliabilityLocks();

        audio = null;
        video = null;
        media = null;
        crypto = null;
        serverSocket = null;
        udpSocket = null;
        lan = null;
        wifiDirect = null;
        peerDiscovery = null;
        pairingServerSocket = null;
        established.set(false);
        connecting.set(false);
    }

    private void startDiagnosticsLoop() {
        if (!diagnosticsLoopStarted.compareAndSet(false, true)) return;
        publishDiagnostics();
        main.postDelayed(this::diagnosticsTick, 1000L);
    }

    private void diagnosticsTick() {
        if (!diagnosticsLoopStarted.get() || stopped.get() || !SessionBus.active) return;
        publishDiagnostics();
        main.postDelayed(this::diagnosticsTick, 1000L);
    }

    private void publishDiagnostics() {
        MediaTransport m = media;
        AudioEngine a = audio;
        VideoEngine v = video;

        long heartbeatAge = established.get() && lastPeerSeenElapsedMs > 0L
                ? Math.max(0L, SystemClock.elapsedRealtime() - lastPeerSeenElapsedMs)
                : -1L;

        String state = recovering ? "Reconnecting"
                : established.get() ? "Connected"
                : SessionBus.active ? "Starting" : "Idle";
        String codec = v == null ? "Audio only"
                : (v.isH264Enabled()
                    ? "H.264 " + v.currentVideoWidth() + "×" + v.currentVideoHeight()
                            + " • " + v.currentQualityLabel()
                    : "JPEG fallback");

        SessionBus.diagnostics(new SessionBus.DiagnosticsSnapshot(
                state,
                activeNetworkType(),
                codec,
                heartbeatAge,
                lastDiagRttMs,
                m == null ? 0 : m.audioQueueDepth(),
                m == null ? 0 : m.videoQueueDepth(),
                a == null ? 0 : a.jitterDepth(),
                m == null ? 0L : m.audioTxPackets(),
                m == null ? 0L : m.audioRxPackets(),
                m == null ? 0L : m.videoTxPackets(),
                m == null ? 0L : m.videoRxPackets(),
                a == null ? 0L : a.concealedFrames(),
                a == null ? 0L : a.playbackQueueDrops(),
                m == null ? 0L : m.totalDroppedVideoPackets(),
                v == null ? 0L : v.lostVideoUnits(),
                v == null ? 0 : v.currentVideoBitrate(),
                v == null ? 0f : v.videoTxFps(),
                v == null ? 0f : v.videoRxFps(),
                v == null ? 0L : v.keyFrameRequests(),
                recoveryCount));
    }

    private String activeNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Unknown";
            Network network = cm.getActiveNetwork();
            if (network == null) return "Offline";
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "Unknown";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                if (LocalBroadcastDiscovery.hasHotspotOrLocalWifiInterface()) return "Hotspot + Cellular";
                return "Cellular";
            }
            if (LocalBroadcastDiscovery.hasHotspotOrLocalWifiInterface()) return "Hotspot/LAN";
            return "Other";
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private String peerLostReason(String fallback) {
        if (mode == MODE_BABY && !babyStation) return "Baby Station connection lost";
        if (mode == MODE_BABY && babyStation) return "Parent Station connection lost";
        return fallback;
    }

    private synchronized void setSessionMode(int newMode, boolean tellPeer) {
        if (newMode < MODE_VOICE || newMode > MODE_BABY) return;
        if (newMode == mode && !sleepingBaby) return;

        sleepingBaby = false;
        SessionBus.sleepingBaby = false;
        listening = true;
        if (audio != null) audio.setPlaybackEnabled(true);
        mode = newMode;
        if (mode == MODE_BABY) {
            babyStation = host;
            SessionBus.babyRoleChanged(babyStation);
        } else {
            babyStation = false;
            SessionBus.babyRoleChanged(false);
        }
        SessionBus.modeChanged(mode, host);

        if (established.get()) {
            if (tellPeer) sendControl("MODE_CHANGE:" + mode);

            if (mode == MODE_VOICE) {
                if (video != null) {
                    try { video.close(); } catch (Exception ignored) {}
                    video = null;
                }
                localVideoEnabled = false;
                SessionBus.video(null);
            } else {
                boolean newlyCreated = false;
                if (video == null && media != null) {
                    video = new VideoEngine(
                    this,
                    media,
                    degrees -> sendControl("VIDEO_ROT:" + degrees),
                    h264Capability,
                    reason -> {
                        peerH264Capable = false;
                        sendControl("VIDEO_FALLBACK_JPEG");
                    },
                    () -> sendControl("VIDEO_KEYFRAME_REQUEST"));
                    video.setH264Enabled(peerH264Capable);
                    newlyCreated = true;
                }
                if (video != null) {
                    boolean cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    boolean sendVideo = (mode == MODE_VIDEO && cameraAllowed) || (mode == MODE_BABY && babyStation && cameraAllowed);
                    localVideoEnabled = sendVideo;
                    if (sendVideo) upgradeForegroundForCamera();
                    if (newlyCreated) video.start(sendVideo);
                    else video.setSendingEnabled(sendVideo);
                }
            }

            if (mode == MODE_BABY) {
                if (babyStation) {
                    setMicMuted(false);
                    sendBabySettings();
                } else {
                    SessionBus.babySettings(true, true, false);
                    sendControl("BABY_STATE_REQUEST");
                }
            }
            updateReliabilityLocks();
            SessionBus.status(connectionLabel());
            updateSessionNotification(connectionLabel());
            persistRecoveryCheckpoint();
        }
    }

    private String connectionLabel() {
        String kind = (mode == MODE_BABY)
                ? (sleepingBaby
                    ? (babyStation ? "Baby station" : "Parent station • sleeping monitor")
                    : (babyStation ? "Baby station" : "Parent station"))
                : (mode == MODE_VIDEO ? "Video" : "Voice");
        return "Connected • " + kind + (internetControlRelay ? " • Online" : "");
    }

    private synchronized void setBabyStation(boolean makeBabyStation, boolean tellPeer) {
        if (mode != MODE_BABY) return;

        if (sleepingBaby) {
            sleepingBaby = false;
            SessionBus.sleepingBaby = false;
            loudMs = 0;
        }

        babyStation = makeBabyStation;
        SessionBus.babyRoleChanged(babyStation);
        if (babyStation) setMicMuted(false);

        listening = true;
        if (audio != null) audio.setPlaybackEnabled(true);
        remoteVideoEnabled = true;
        SessionBus.video(null);

        if (video != null) {
            boolean cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean shouldSend = babyStation && cameraAllowed;
            localVideoEnabled = shouldSend;
            if (shouldSend) upgradeForegroundForCamera();
            video.setSendingEnabled(shouldSend);
        } else {
            localVideoEnabled = false;
        }

        if (tellPeer) sendControl("BABY_ROLE:" + (babyStation ? "1" : "0"));
        if (babyStation) {
            sendBabySettings();
            sendBabyAuxState();
        } else {
            SessionBus.babySettings(true, true, false);
            SessionBus.babyAuxState(false, false, false);
            sendControl("BABY_STATE_REQUEST");
            sendControl("BABY_AUX_STATE_REQUEST");
        }
        SessionBus.status(connectionLabel());
        updateSessionNotification(connectionLabel());
        persistRecoveryCheckpoint();
    }

    private void setSleepingBaby(boolean enabled, boolean tellPeer) {
        if (mode != MODE_BABY) return;
        sleepingBaby = enabled;
        SessionBus.sleepingBabyChanged(enabled);
        QuietLog.log("AUDIO", "sleeping_mode",
                "enabled=" + (enabled ? 1 : 0)
                        + " baby=" + (babyStation ? 1 : 0)
                        + " muted=" + (micMuted ? 1 : 0));
        loudMs = 0;

        if (!babyStation) {
            listening = !enabled;
            if (audio != null) audio.setPlaybackEnabled(listening);
            remoteVideoEnabled = !enabled;
            sendControl("BABY_CAMERA_SET:" + (remoteVideoEnabled ? "1" : "0"));
            // Keep the older command too so 0.3.9 peers still follow the request.
            sendControl(enabled ? "VIDEO_OFF" : "VIDEO_ON");
        }

        if (tellPeer) sendControl(enabled ? "SLEEPING_ON" : "SLEEPING_OFF");
        SessionBus.status(connectionLabel());
        updateSessionNotification(connectionLabel());
        persistRecoveryCheckpoint();
    }

    private void setLocalVideoSending(boolean enabled) {
        boolean cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        localVideoEnabled = enabled && cameraAllowed && video != null;
        if (localVideoEnabled) upgradeForegroundForCamera();
        if (video != null) video.setSendingEnabled(localVideoEnabled);
        persistRecoveryCheckpoint();
    }

    private void sendBabySettings() {
        if (!established.get() || mode != MODE_BABY || !babyStation) return;
        boolean micOn = !micMuted;
        boolean cameraOn = localVideoEnabled;
        SessionBus.babySettings(micOn, cameraOn, true);
        sendControl("BABY_STATE:" + (micOn ? "1" : "0") + ":" + (cameraOn ? "1" : "0"));
    }

    private void sendBabyAuxState() {
        if (!established.get() || mode != MODE_BABY || !babyStation) return;
        SessionBus.babyAuxState(babyTorchEnabled, babyBrightnessBoost, true);
        sendControl("BABY_AUX_STATE:"
                + (babyTorchEnabled ? "1" : "0") + ":"
                + (babyBrightnessBoost ? "1" : "0"));
    }

    private void sendChat(String text) {
        if (!established.get() || text == null) return;
        String clean = text.trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 1000) clean = clean.substring(0, 1000);
        SessionBus.chat(true, clean);
        String encoded = Base64.encodeToString(
                clean.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE);
        sendControl("CHAT:" + encoded);
    }

    private void receiveChat(String encoded) {
        try {
            byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP | Base64.URL_SAFE);
            String text = new String(decoded, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return;
            if (text.length() > 1000) text = text.substring(0, 1000);
            SessionBus.chat(false, text);
            postChatNotification();
        } catch (Exception ignored) {}
    }

    private void setMicMuted(boolean muted) {
        boolean changed = micMuted != muted;
        micMuted = muted;
        SessionBus.localMicMuted = muted;
        if (changed) {
            QuietLog.log("AUDIO", "mic_gate_state",
                    "muted=" + (muted ? 1 : 0)
                            + " baby=" + (babyStation ? 1 : 0));
        }
        persistRecoveryCheckpoint();
    }

    private void disconnectManually() {
        SessionBus.autoConnectSuspended = true;
        clearRecoveryCheckpoint();
        resumeAuthorizedForSession = false;
        recovering = false;
        stopRecoveryBeacon();
        ptt = false;

        CryptoChannel ch = crypto;
        if (ch == null || !established.get()) {
            stopSession("Disconnected");
            return;
        }

        AtomicBoolean finished = new AtomicBoolean(false);
        io.execute(() -> {
            try {
                ch.sendControl("BYE_MANUAL|" + encodeName(deviceIdentity == null
                        ? android.os.Build.MODEL : deviceIdentity.deviceName()));
                try { Thread.sleep(80L); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception ignored) {}
            if (finished.compareAndSet(false, true)) {
                main.post(() -> stopSession("Disconnected"));
            }
        });
        // Never allow a dead peer to make the local Disconnect button hang.
        main.postDelayed(() -> {
            if (finished.compareAndSet(false, true)) stopSession("Disconnected");
        }, 350L);
    }

    private void sendControl(String c) {
        CryptoChannel ch = crypto;
        if (ch != null && established.get()) {
            io.execute(() -> {
                try {
                    ch.sendControl(c);
                } catch (Exception e) {
                    if (!stopped.get() && established.get()) {
                        handleConnectionLoss(peerLostReason("Connection lost"));
                    }
                }
            });
        }
    }

    private void updateReliabilityLocks() {
        if ((!established.get() && !recovering) || mode != MODE_BABY) {
            releaseReliabilityLocks();
            return;
        }
        try {
            if (babyWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    babyWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuietLink:BabyMonitor");
                    babyWakeLock.setReferenceCounted(false);
                }
            }
            if (babyWakeLock != null && !babyWakeLock.isHeld()) babyWakeLock.acquire();
        } catch (Exception ignored) {}

        try {
            if (babyWifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    babyWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "QuietLink:BabyMonitorWiFi");
                    babyWifiLock.setReferenceCounted(false);
                }
            }
            if (babyWifiLock != null && !babyWifiLock.isHeld()) babyWifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseReliabilityLocks() {
        try { if (babyWakeLock != null && babyWakeLock.isHeld()) babyWakeLock.release(); } catch (Exception ignored) {}
        try { if (babyWifiLock != null && babyWifiLock.isHeld()) babyWifiLock.release(); } catch (Exception ignored) {}
        babyWakeLock = null;
        babyWifiLock = null;
    }

    private void saveSessionDiagnostic(String reason) {
        try {
            getSharedPreferences("quietlink_diagnostics", MODE_PRIVATE)
                    .edit()
                    .putLong("last_session_end_ms", System.currentTimeMillis())
                    .putString("last_session_reason", reason == null ? "Unknown" : reason)
                    .putString("last_session_mode", mode == MODE_BABY ? (babyStation ? "Baby Station" : "Parent Station")
                            : mode == MODE_VIDEO ? "Video" : "Voice")
                    .apply();
        } catch (Exception ignored) {}
    }

    private void cancelConnectionLostAlert() {
        try {
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ALERT_ID + 1);
        } catch (Exception ignored) {}
    }

    private void postConnectionLostAlert(String reason) {
        try {
            Notification n = new Notification.Builder(this, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("QuietLink connection lost")
                    .setContentText("Baby Station is no longer responding")
                    .setStyle(new Notification.BigTextStyle()
                            .bigText("Baby Station is no longer responding. Open QuietLink and reconnect."))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(ALERT_ID + 1, n);
        } catch (Exception ignored) {}
    }

    private String encodeName(String name) {
        return Base64.encodeToString(safePeerName(name).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private String decodeName(String encoded) {
        try {
            return new String(Base64.decode(encoded, Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Nearby device";
        }
    }

    private String safePeerName(String name) {
        if (name == null || name.trim().isEmpty()) return "Nearby device";
        String clean = name.replace("\n", " ").replace("\r", " ").trim();
        return clean.length() > 48 ? clean.substring(0, 48) : clean;
    }

    private String readable(Exception e) {
        String s = e.getMessage();
        return s == null || s.trim().isEmpty() ? e.getClass().getSimpleName() : s;
    }

    private void fail(String msg) {
        SessionBus.status(msg);
        stopSession(msg);
    }

    private synchronized void stopSession(String reason) {
        if (!stopped.compareAndSet(false, true)) return;
        clearRecoveryCheckpoint();
        resumeAuthorizedForSession = false;
        recovering = false;
        stopRecoveryBeacon();
        diagnosticsLoopStarted.set(false);
        serviceWatchdogStarted.set(false);
        boolean wasSleepingParent = established.get() && mode == MODE_BABY && !babyStation && sleepingBaby;
        established.set(false);
        connecting.set(false);
        saveSessionDiagnostic(reason);

        // Do not synchronously write BYE here: a dead TCP peer can make a
        // blocking write stall local teardown. Closing the socket is enough
        // for a healthy peer to detect the disconnect.
        try { if (audio != null) audio.close(); } catch (Exception ignored) {}
        try { if (video != null) video.close(); } catch (Exception ignored) {}
        try { if (media != null) media.close(); } catch (Exception ignored) {}
        try { if (crypto != null) crypto.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        try { if (lan != null) lan.close(); } catch (Exception ignored) {}
        try { if (wifiDirect != null) wifiDirect.close(); } catch (Exception ignored) {}
        try { if (peerDiscovery != null) peerDiscovery.close(); } catch (Exception ignored) {}
        try { if (pairingServerSocket != null) pairingServerSocket.close(); } catch (Exception ignored) {}
        stopOnlineRendezvous();
        internetControlRelay = false;
        onlineConnecting.set(false);
        stopRecoveryResources(true);
        releaseReliabilityLocks();
        clearPendingNearby(true);
        clearOutgoingNearby(true);

        audio = null;
        video = null;
        media = null;
        crypto = null;
        lan = null;
        wifiDirect = null;
        peerDiscovery = null;
        peerH264Capable = false;
        serverSocket = null;
        pairingServerSocket = null;
        udpSocket = null;
        activePeerFingerprint = null;
        activePeerIdentityPublic = null;
        pairingMode = false;
        sleepingBaby = false;
        babyStation = false;

        SessionBus.pairingMode(false);
        SessionBus.nearbyPeers(Collections.emptyList());
        SessionBus.sleepingBaby = false;
        SessionBus.babyRoleChanged(false);
        SessionBus.disconnected(reason);
        cancelChatNotification();
        if (wasSleepingParent && !"Disconnected".equals(reason) && !"Peer disconnected".equals(reason)) {
            postConnectionLostAlert(reason);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (!stopped.get()) {
            if (resumeAuthorizedForSession
                    && (established.get() || recovering || SessionBus.active)) {
                persistRecoveryCheckpoint();
                SessionBus.reconnecting("QuietLink service restarting • reconnecting…");
                closeTransientForServiceRestart();
            } else if (established.get() || SessionBus.active) {
                stopSession("Session ended");
            } else {
                stopPairingMode(false);
            }
        }
        serviceWatchdogStarted.set(false);
        diagnosticsLoopStarted.set(false);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel session = new NotificationChannel(
                CHANNEL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        session.setDescription("Keeps a local QuietLink connection running");
        nm.createNotificationChannel(session);

        NotificationChannel alert = new NotificationChannel(
                ALERT_CHANNEL,
                "Baby sound alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Alerts when Sleeping Baby mode detects sustained sound");
        nm.createNotificationChannel(alert);

        NotificationChannel messages = new NotificationChannel(
                MESSAGE_CHANNEL,
                "QuietLink messages",
                NotificationManager.IMPORTANCE_DEFAULT);
        messages.setDescription("Generic notification when a QuietLink chat message arrives");
        nm.createNotificationChannel(messages);
    }

    private void postChatNotification() {
        try {
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent openPi = PendingIntent.getActivity(
                    this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            int unread = SessionBus.unreadChatCount();
            Notification n = new Notification.Builder(this, MESSAGE_CHANNEL)
                    .setSmallIcon(android.R.drawable.presence_audio_online)
                    .setContentTitle("New QuietLink message")
                    .setContentText("You received a message")
                    .setContentIntent(openPi)
                    .setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setNumber(Math.max(1, unread))
                    .build();
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(MESSAGE_NOTIFY_ID, n);
        } catch (Exception ignored) {}
    }

    private void cancelChatNotification() {
        try {
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(MESSAGE_NOTIFY_ID);
        } catch (Exception ignored) {}
    }

    private Notification sessionNotification(String text) {
        Intent stop = new Intent(this, SessionService.class).setAction(ACTION_DISCONNECT);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentTitle("QuietLink")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Disconnect", stopPi).build())
                .build();
    }

    private void updateSessionNotification(String text) {
        ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(NOTIFY_ID, sessionNotification(text));
    }

    private void postSoundAlert() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Sound detected")
                .setContentText("Sustained sound was detected by the baby device.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(ALERT_ID, n);
    }
}
