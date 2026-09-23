package is.quietlink.app;

import android.graphics.Bitmap;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionBus {
    public interface Listener {
        void onStatus(String status);
        void onModeChanged(int mode, boolean host);
        void onConnected(String verification);
        void onAudioLevel(float level);
        void onRemoteVideo(Bitmap bitmap);
        void onLocalVideo(Bitmap bitmap);
        void onRemoteVideoEnabledChanged(boolean enabled);
        void onVideoFrameRendered();
        void onRemoteVideoRotation(int degrees);
        void onLocalVideoRotation(int degrees);
        void onBabyRoleChanged(boolean babyStation);
        void onBabySettingsChanged(boolean micEnabled, boolean cameraEnabled, boolean known);
        void onSleepingBabyChanged(boolean enabled);
        void onBabyAuxStateChanged(boolean torchEnabled, boolean brightnessBoost, boolean known);
        void onChatMessagesChanged(List<ChatMessage> messages);
        void onNearbyDevicesChanged(List<PeerDiscovery.Peer> peers);
        void onIncomingRequest(IncomingRequest request);
        void onOutgoingRequest(OutgoingRequest request);
        void onKnownDevicesChanged();
        void onPairingModeChanged(boolean enabled);
        void onDisconnected(String reason);
    }

    public interface VideoSurfaceListener {
        void onRemoteVideoSurface(Surface surface);
        void onLocalVideoSurface(Surface surface);
    }

    public static final class ChatMessage {
        public final boolean mine;
        public final String text;
        public final long timeMs;

        public ChatMessage(boolean mine, String text, long timeMs) {
            this.mine = mine;
            this.text = text;
            this.timeMs = timeMs;
        }
    }

    public static final class IncomingRequest {
        public final String name;
        public final String fingerprint;
        public final String verification;
        public final boolean known;
        public final long expiresAtMs;

        public IncomingRequest(String name, String fingerprint, String verification,
                               boolean known, long expiresAtMs) {
            this.name = name;
            this.fingerprint = fingerprint;
            this.verification = verification;
            this.known = known;
            this.expiresAtMs = expiresAtMs;
        }
    }

    public static final class OutgoingRequest {
        public final String name;
        public final String fingerprint;
        public final String verification;
        public final boolean known;
        public final long expiresAtMs;

        public OutgoingRequest(String name, String fingerprint, String verification,
                               boolean known, long expiresAtMs) {
            this.name = name;
            this.fingerprint = fingerprint;
            this.verification = verification;
            this.known = known;
            this.expiresAtMs = expiresAtMs;
        }
    }

    public static final class DiagnosticsSnapshot {
        public final String state;
        public final String network;
        public final String codec;
        public final long heartbeatAgeMs;
        public final long rttMs;
        public final int audioQueue;
        public final int videoQueue;
        public final int audioJitterFrames;
        public final long audioTxPackets;
        public final long audioRxPackets;
        public final long videoTxPackets;
        public final long videoRxPackets;
        public final long audioConcealedFrames;
        public final long audioPlaybackDrops;
        public final long videoDroppedTxPackets;
        public final long videoLostRxUnits;
        public final int videoBitrateBps;
        public final float videoTxFps;
        public final float videoRxFps;
        public final long keyFrameRequests;
        public final int recoveries;

        public DiagnosticsSnapshot(
                String state, String network, String codec,
                long heartbeatAgeMs, long rttMs,
                int audioQueue, int videoQueue, int audioJitterFrames,
                long audioTxPackets, long audioRxPackets,
                long videoTxPackets, long videoRxPackets,
                long audioConcealedFrames, long audioPlaybackDrops,
                long videoDroppedTxPackets, long videoLostRxUnits,
                int videoBitrateBps, float videoTxFps, float videoRxFps,
                long keyFrameRequests, int recoveries) {
            this.state = state;
            this.network = network;
            this.codec = codec;
            this.heartbeatAgeMs = heartbeatAgeMs;
            this.rttMs = rttMs;
            this.audioQueue = audioQueue;
            this.videoQueue = videoQueue;
            this.audioJitterFrames = audioJitterFrames;
            this.audioTxPackets = audioTxPackets;
            this.audioRxPackets = audioRxPackets;
            this.videoTxPackets = videoTxPackets;
            this.videoRxPackets = videoRxPackets;
            this.audioConcealedFrames = audioConcealedFrames;
            this.audioPlaybackDrops = audioPlaybackDrops;
            this.videoDroppedTxPackets = videoDroppedTxPackets;
            this.videoLostRxUnits = videoLostRxUnits;
            this.videoBitrateBps = videoBitrateBps;
            this.videoTxFps = videoTxFps;
            this.videoRxFps = videoRxFps;
            this.keyFrameRequests = keyFrameRequests;
            this.recoveries = recoveries;
        }
    }

    private static volatile Listener listener;
    private static volatile VideoSurfaceListener videoSurfaceListener;
    public static volatile Surface remoteVideoSurface;
    public static volatile Surface localVideoSurface;
    public static volatile int remoteVideoRotation = 0;
    public static volatile int localVideoRotation = 0;
    public static volatile boolean localCameraFront = true;
    public static volatile boolean remoteVideoEnabled = true;
    public static volatile Bitmap latestVideo;
    public static volatile Bitmap latestLocalVideo;
    public static volatile String latestStatus = "Idle";
    public static volatile String verification = "";
    public static volatile boolean connected = false;
    public static volatile boolean active = false;
    public static volatile boolean host = false;
    public static volatile boolean babyStation = false;
    public static volatile boolean babyMicEnabled = true;
    public static volatile boolean babyCameraEnabled = true;
    public static volatile boolean babySettingsKnown = false;
    public static volatile boolean babyTorchEnabled = false;
    public static volatile boolean babyBrightnessBoost = false;
    public static volatile boolean babyAuxKnown = false;
    public static volatile boolean sleepingBaby = false;
    public static volatile boolean pairingMode = false;
    // Deliberate disconnects pause trusted Auto reconnect for this app process
    // until a person explicitly initiates another connection.
    public static volatile boolean autoConnectSuspended = false;
    // Keep mute across service recreation so UI and actual capture agree.
    public static volatile boolean localMicMuted = false;
    public static volatile int mode = 0;
    public static volatile String code = "";
    public static volatile String peerName = "";
    public static volatile IncomingRequest incomingRequest;
    public static volatile OutgoingRequest outgoingRequest;
    private static final List<ChatMessage> chatMessages = new ArrayList<>();
    private static int unreadChatCount = 0;
    public static volatile List<PeerDiscovery.Peer> nearbyPeers = Collections.emptyList();
    private static volatile DiagnosticsSnapshot diagnostics = new DiagnosticsSnapshot(
            "Idle", "Unknown", "None", -1L, -1L,
            0, 0, 0,
            0L, 0L, 0L, 0L,
            0L, 0L, 0L, 0L,
            0, 0f, 0f, 0L, 0);

    private SessionBus() {}

    public static void diagnostics(DiagnosticsSnapshot snapshot) {
        if (snapshot != null) diagnostics = snapshot;
    }

    public static DiagnosticsSnapshot diagnosticsSnapshot() {
        return diagnostics;
    }

    public static void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onModeChanged(mode, host);
            l.onBabyRoleChanged(babyStation);
            l.onBabySettingsChanged(babyMicEnabled, babyCameraEnabled, babySettingsKnown);
            l.onSleepingBabyChanged(sleepingBaby);
            l.onBabyAuxStateChanged(babyTorchEnabled, babyBrightnessBoost, babyAuxKnown);
            l.onStatus(latestStatus);
            l.onPairingModeChanged(pairingMode);
            l.onNearbyDevicesChanged(new ArrayList<>(nearbyPeers));
            if (incomingRequest != null) l.onIncomingRequest(incomingRequest);
            if (outgoingRequest != null) l.onOutgoingRequest(outgoingRequest);
            if (connected) l.onConnected(verification);
            if (latestVideo != null) l.onRemoteVideo(latestVideo);
            if (latestLocalVideo != null) l.onLocalVideo(latestLocalVideo);
            l.onRemoteVideoEnabledChanged(remoteVideoEnabled);
            l.onRemoteVideoRotation(remoteVideoRotation);
            l.onLocalVideoRotation(localVideoRotation);
            l.onChatMessagesChanged(chatSnapshot());
        }
    }

    public static void setVideoSurfaceListener(VideoSurfaceListener l) {
        videoSurfaceListener = l;
        if (l != null) {
            l.onRemoteVideoSurface(remoteVideoSurface);
            l.onLocalVideoSurface(localVideoSurface);
        }
    }

    public static void remoteVideoSurface(Surface surface) {
        remoteVideoSurface = surface;
        VideoSurfaceListener l = videoSurfaceListener;
        if (l != null) l.onRemoteVideoSurface(surface);
    }

    public static void localVideoSurface(Surface surface) {
        localVideoSurface = surface;
        VideoSurfaceListener l = videoSurfaceListener;
        if (l != null) l.onLocalVideoSurface(surface);
    }

    public static void remoteVideoEnabled(boolean enabled) {
        remoteVideoEnabled = enabled;
        if (!enabled) latestVideo = null;
        Listener l = listener;
        if (l != null) l.onRemoteVideoEnabledChanged(enabled);
    }

    public static void videoFrameRendered() {
        Listener l = listener;
        if (l != null) l.onVideoFrameRendered();
    }

    public static void videoRotation(int degrees) {
        remoteVideoRotation = ((degrees % 360) + 360) % 360;
        Listener l = listener;
        if (l != null) l.onRemoteVideoRotation(remoteVideoRotation);
    }

    public static void localVideoRotation(int degrees) {
        localVideoRotation = ((degrees % 360) + 360) % 360;
        Listener l = listener;
        if (l != null) l.onLocalVideoRotation(localVideoRotation);
    }

    public static void localCameraFacing(boolean front) {
        localCameraFront = front;
        // Re-use the rotation callback to make the local preview transform
        // immediately re-evaluate its developer mirror setting.
        Listener l = listener;
        if (l != null) l.onLocalVideoRotation(localVideoRotation);
    }

    public static void status(String s) {
        latestStatus = s;
        Listener l = listener;
        if (l != null) l.onStatus(s);
    }

    public static void modeChanged(int newMode, boolean isHost) {
        mode = newMode;
        host = isHost;
        if (newMode != SessionService.MODE_BABY) sleepingBaby = false;
        Listener l = listener;
        if (l != null) l.onModeChanged(newMode, isHost);
    }

    public static void babyRoleChanged(boolean isBabyStation) {
        babyStation = isBabyStation;
        babySettingsKnown = false;
        Listener l = listener;
        if (l != null) {
            l.onBabyRoleChanged(isBabyStation);
            l.onBabySettingsChanged(babyMicEnabled, babyCameraEnabled, false);
        }
    }

    public static void babySettings(boolean micEnabled, boolean cameraEnabled, boolean known) {
        babyMicEnabled = micEnabled;
        babyCameraEnabled = cameraEnabled;
        babySettingsKnown = known;
        Listener l = listener;
        if (l != null) l.onBabySettingsChanged(micEnabled, cameraEnabled, known);
    }

    public static void sleepingBabyChanged(boolean enabled) {
        sleepingBaby = enabled;
        Listener l = listener;
        if (l != null) l.onSleepingBabyChanged(enabled);
    }

    public static void babyAuxState(boolean torchEnabled, boolean brightnessBoost, boolean known) {
        babyTorchEnabled = torchEnabled;
        babyBrightnessBoost = brightnessBoost;
        babyAuxKnown = known;
        Listener l = listener;
        if (l != null) l.onBabyAuxStateChanged(torchEnabled, brightnessBoost, known);
    }

    public static void connected(String v) {
        connected = true;
        verification = v;
        Listener l = listener;
        if (l != null) l.onConnected(v);
    }

    public static void reconnecting(String reason) {
        connected = false;
        latestVideo = null;
        latestLocalVideo = null;
        remoteVideoEnabled = true;
        remoteVideoRotation = 0;
        localVideoRotation = 0;
        localCameraFront = true;
        latestStatus = (reason == null || reason.trim().isEmpty())
                ? "Connection interrupted • reconnecting…"
                : reason;
        Listener l = listener;
        if (l != null) {
            l.onRemoteVideo(null);
            l.onLocalVideo(null);
            l.onRemoteVideoRotation(0);
            l.onLocalVideoRotation(0);
            l.onStatus(latestStatus);
        }
    }

    public static void level(float value) {
        Listener l = listener;
        if (l != null) l.onAudioLevel(value);
    }

    public static void video(Bitmap bitmap) {
        latestVideo = bitmap;
        Listener l = listener;
        if (l != null) l.onRemoteVideo(bitmap);
    }

    public static void localVideo(Bitmap bitmap) {
        latestLocalVideo = bitmap;
        Listener l = listener;
        if (l != null) l.onLocalVideo(bitmap);
    }

    public static synchronized List<ChatMessage> chatSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(chatMessages));
    }

    public static synchronized void chat(boolean mine, String text) {
        if (text == null) return;
        String clean = text.trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 1000) clean = clean.substring(0, 1000);
        chatMessages.add(new ChatMessage(mine, clean, System.currentTimeMillis()));
        while (chatMessages.size() > 100) chatMessages.remove(0);
        if (!mine) unreadChatCount = Math.min(99, unreadChatCount + 1);
        Listener l = listener;
        if (l != null) l.onChatMessagesChanged(chatSnapshot());
    }

    public static synchronized int unreadChatCount() {
        return unreadChatCount;
    }

    public static synchronized void markChatRead() {
        unreadChatCount = 0;
    }

    public static synchronized void clearChat() {
        chatMessages.clear();
        unreadChatCount = 0;
        Listener l = listener;
        if (l != null) l.onChatMessagesChanged(Collections.emptyList());
    }

    public static void nearbyPeers(List<PeerDiscovery.Peer> peers) {
        nearbyPeers = peers == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(peers));
        Listener l = listener;
        if (l != null) l.onNearbyDevicesChanged(new ArrayList<>(nearbyPeers));
    }

    public static void incoming(IncomingRequest request) {
        incomingRequest = request;
        Listener l = listener;
        if (l != null && request != null) l.onIncomingRequest(request);
    }

    public static void clearIncoming() {
        incomingRequest = null;
        Listener l = listener;
        if (l != null) l.onIncomingRequest(null);
    }

    public static void outgoing(OutgoingRequest request) {
        outgoingRequest = request;
        Listener l = listener;
        if (l != null && request != null) l.onOutgoingRequest(request);
    }

    public static void clearOutgoing() {
        outgoingRequest = null;
        Listener l = listener;
        if (l != null) l.onOutgoingRequest(null);
    }

    public static void knownChanged() {
        Listener l = listener;
        if (l != null) l.onKnownDevicesChanged();
    }

    public static void pairingMode(boolean enabled) {
        pairingMode = enabled;
        Listener l = listener;
        if (l != null) l.onPairingModeChanged(enabled);
    }

    public static void disconnected(String reason) {
        connected = false;
        active = false;
        sleepingBaby = false;
        babyStation = false;
        babyMicEnabled = true;
        babyCameraEnabled = true;
        babySettingsKnown = false;
        latestVideo = null;
        latestLocalVideo = null;
        remoteVideoEnabled = true;
        remoteVideoRotation = 0;
        localVideoRotation = 0;
        latestStatus = (reason == null || reason.trim().isEmpty())
                ? "Disconnected" : reason;
        peerName = "";
        incomingRequest = null;
        outgoingRequest = null;
        clearChat();
        Listener l = listener;
        if (l != null) l.onDisconnected(reason);
    }
}
