package is.quietlink.app;

import java.util.Arrays;

public final class ConnectionRouteSelfTest {
    public static void main(String[] args) throws Exception {
        routeDefaultsToLocal();
        backoffIsBoundedAndGrows();
        recoveryHostIsDeterministicAndOpposite();
        networkChangeOnlyEndsSilentCalls();
        recoverySecretRoundTrips();
        recoverySecretRejectsBadInput();
        recoveryRoomFitsServerRulesAndHidesSecret();
        System.out.println("ConnectionRouteSelfTest OK");
    }

    private static void routeDefaultsToLocal() {
        check(!ConnectionRoute.isMobile(null), "missing route must be local");
        check(!ConnectionRoute.isMobile(""), "empty route must be local");
        check(!ConnectionRoute.isMobile("MOBILE"), "unknown route must be local");
        check(!ConnectionRoute.isMobile(ConnectionRoute.LOCAL), "local route must be local");
        check(ConnectionRoute.isMobile(ConnectionRoute.MOBILE), "mobile route must be mobile");
        check(ConnectionRoute.LOCAL.equals(ConnectionRoute.normalize("junk")), "junk normalizes to local");
    }

    private static void backoffIsBoundedAndGrows() {
        long previous = 0L;
        for (int attempts = 1; attempts <= ConnectionRoute.MAX_INTERNET_RECOVERY_ATTEMPTS + 4; attempts++) {
            long delay = ConnectionRoute.recoveryBackoffMs(attempts);
            check(delay >= previous, "backoff must not shrink");
            check(delay <= 30_000L, "backoff must stay bounded");
            previous = delay;
        }
        check(ConnectionRoute.recoveryBackoffMs(0) == 2_000L, "first delay");
        check(!ConnectionRoute.recoveryAttemptsExhausted(
                ConnectionRoute.MAX_INTERNET_RECOVERY_ATTEMPTS - 1), "attempt still allowed");
        check(ConnectionRoute.recoveryAttemptsExhausted(
                ConnectionRoute.MAX_INTERNET_RECOVERY_ATTEMPTS), "attempts must be bounded");
    }

    private static void recoveryHostIsDeterministicAndOpposite() {
        check(ConnectionRoute.isRecoveryHost("aaa", "bbb"), "lower fingerprint hosts");
        check(!ConnectionRoute.isRecoveryHost("bbb", "aaa"), "higher fingerprint joins");
        check(!ConnectionRoute.isRecoveryHost(null, "aaa"), "missing local identity");
        check(!ConnectionRoute.isRecoveryHost("aaa", null), "missing peer identity");
    }

    private static void networkChangeOnlyEndsSilentCalls() {
        check(ConnectionRoute.shouldDeclareLossAfterNetworkChange(100L, 100L), "silent peer is lost");
        check(!ConnectionRoute.shouldDeclareLossAfterNetworkChange(100L, 101L), "answering peer is healthy");
    }

    private static void recoverySecretRoundTrips() {
        byte[] secret = new byte[ConnectionRoute.RECOVERY_SECRET_BYTES];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i * 7 + 251);
        String encoded = ConnectionRoute.encodeRecoverySecret(secret);
        check(Arrays.equals(secret, ConnectionRoute.decodeRecoverySecret(encoded)), "secret round trip");
        check(ConnectionRoute.sameSecret(secret, ConnectionRoute.copy(secret)), "copy matches");
        check(!ConnectionRoute.sameSecret(secret, null), "null never matches");
    }

    private static void recoverySecretRejectsBadInput() {
        check(ConnectionRoute.decodeRecoverySecret(null) == null, "null secret");
        check(ConnectionRoute.decodeRecoverySecret("") == null, "empty secret");
        check(ConnectionRoute.decodeRecoverySecret("not base64 !!") == null, "invalid secret");
        check(ConnectionRoute.decodeRecoverySecret("AAAA") == null, "short secret");
        check("".equals(ConnectionRoute.encodeRecoverySecret(new byte[4])), "short secret never encodes");
        byte[] tooLong = new byte[ConnectionRoute.RECOVERY_SECRET_BYTES + 1];
        check(ConnectionRoute.decodeRecoverySecret(
                java.util.Base64.getUrlEncoder().encodeToString(tooLong)) == null, "long secret");
        boolean rejected = false;
        try {
            ConnectionRoute.deriveRecoveryRoom(new byte[4]);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        } catch (Exception other) {
            throw new AssertionError("unexpected " + other.getClass().getSimpleName());
        }
        check(rejected, "short secret must not derive a room");
    }

    private static void recoveryRoomFitsServerRulesAndHidesSecret() throws Exception {
        byte[] secret = new byte[ConnectionRoute.RECOVERY_SECRET_BYTES];
        Arrays.fill(secret, (byte) 42);
        String room = ConnectionRoute.deriveRecoveryRoom(secret);
        check(room.equals(ConnectionRoute.deriveRecoveryRoom(ConnectionRoute.copy(secret))),
                "both phones must derive the same room");
        check(room.matches("^[A-Za-z0-9_=-]+$"), "room must pass the server token rule");
        check(room.length() >= 16 && room.length() <= 128, "room must fit the server and relay limits");
        check(!room.equals(ConnectionRoute.encodeRecoverySecret(secret)), "room must not reveal the secret");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
