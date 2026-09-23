package is.quietlink.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Pairing {
    private static final byte[] SALT = "QuietLink local pairing v2".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 120_000;

    private Pairing() {}

    public static byte[] secret(String code) throws Exception {
        if (code == null || !code.matches("\\d{6}")) throw new IllegalArgumentException("Pairing code must be 6 digits");
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), SALT, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    public static String roomId(String code) {
        try {
            byte[] secret = secret(code);
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update("room-id".getBytes(StandardCharsets.UTF_8));
            byte[] h = d.digest(secret);
            StringBuilder b = new StringBuilder("QL-");
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", h[i] & 0xff));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
