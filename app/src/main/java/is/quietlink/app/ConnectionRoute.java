package is.quietlink.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Pure connection-route rules shared by the UI and SessionService. This class
 * must stay free of Android imports so tools/ConnectionRouteSelfTest.java can
 * run on a plain JDK in CI.
 */
final class ConnectionRoute {
    static final String LOCAL = "local";
    static final String MOBILE = "mobile";

    static final String PREFS = "quietlink_ui";
    static final String PREF_ROUTE = "connection_route";
    static final String PREF_ALLOW_MOBILE_RECONNECT = "allow_mobile_reconnect";
    static final boolean DEFAULT_ALLOW_MOBILE_RECONNECT = true;

    static final String RECOVERY_ROOM_MESSAGE = "RECOVERY_ROOM_V1:";
    static final String RECOVERY_ROOM_ACK_MESSAGE = "RECOVERY_ROOM_ACK_V1";
    static final int RECOVERY_SECRET_BYTES = 32;

    static final int MAX_INTERNET_RECOVERY_ATTEMPTS = 6;
    private static final long[] RECOVERY_BACKOFF_MS = {
            2_000L, 4_000L, 8_000L, 15_000L, 30_000L
    };

    private ConnectionRoute() {}

    /** A missing or unknown route always means the local route. */
    static boolean isMobile(String route) {
        return MOBILE.equals(route);
    }

    static String normalize(String route) {
        return isMobile(route) ? MOBILE : LOCAL;
    }

    /** Delay before the next attempt, after {@code attemptsMade} attempts have run. */
    static long recoveryBackoffMs(int attemptsMade) {
        int index = Math.max(0, attemptsMade - 1);
        return RECOVERY_BACKOFF_MS[Math.min(index, RECOVERY_BACKOFF_MS.length - 1)];
    }

    static boolean recoveryAttemptsExhausted(int attemptsMade) {
        return attemptsMade >= MAX_INTERNET_RECOVERY_ATTEMPTS;
    }

    /**
     * The side with the lower fingerprint generates the recovery room and takes
     * the listening handshake role. This matches the local preferred-dialer rule.
     */
    static boolean isRecoveryHost(String localFingerprint, String remoteFingerprint) {
        return localFingerprint != null && remoteFingerprint != null
                && localFingerprint.compareTo(remoteFingerprint) < 0;
    }

    /**
     * A network change only ends a live call when the peer stayed silent after a
     * keepalive was sent. Any control traffic since the change proves the session
     * still works, so cellular appearing or Wi-Fi returning cannot break it.
     */
    static boolean shouldDeclareLossAfterNetworkChange(long peerSeenAtEventMs, long peerSeenNowMs) {
        return peerSeenNowMs <= peerSeenAtEventMs;
    }

    /** Rendezvous room derived from the secret, so the relay never sees the secret. */
    static String deriveRecoveryRoom(byte[] secret) throws Exception {
        if (secret == null || secret.length != RECOVERY_SECRET_BYTES) {
            throw new IllegalArgumentException("Invalid recovery secret");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("quietlink-recovery-v1".getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().encodeToString(digest.digest(secret));
    }

    static String encodeRecoverySecret(byte[] secret) {
        if (secret == null || secret.length != RECOVERY_SECRET_BYTES) return "";
        return Base64.getUrlEncoder().encodeToString(secret);
    }

    /** Returns the secret, or null unless the value is exactly one valid secret. */
    static byte[] decodeRecoverySecret(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 64) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return decoded.length == RECOVERY_SECRET_BYTES ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean sameSecret(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }

    static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}
