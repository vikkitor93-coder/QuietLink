package is.quietlink.app;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Locale;

public final class DeviceIdentity {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "quietlink-device-identity-v1";

    private final Context context;
    private final KeyPair keyPair;
    private final String fingerprint;

    private DeviceIdentity(Context context, KeyPair keyPair) throws Exception {
        this.context = context.getApplicationContext();
        this.keyPair = keyPair;
        this.fingerprint = fingerprint(keyPair.getPublic().getEncoded());
    }

    public static DeviceIdentity loadOrCreate(Context context) throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);

        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build();
            gen.initialize(spec);
            gen.generateKeyPair();
        }

        PrivateKey privateKey = (PrivateKey) ks.getKey(ALIAS, null);
        PublicKey publicKey = ks.getCertificate(ALIAS).getPublicKey();
        return new DeviceIdentity(context, new KeyPair(publicKey, privateKey));
    }

    public KeyPair keyPair() { return keyPair; }
    public byte[] publicKeyEncoded() { return keyPair.getPublic().getEncoded(); }
    public String fingerprint() { return fingerprint; }

    public String deviceName() {
        String saved = context.getSharedPreferences("quietlink_identity", Context.MODE_PRIVATE)
                .getString("device_name", "");
        if (saved != null && !saved.trim().isEmpty()) return saved.trim();
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "Android device" : Build.MODEL.trim();
        if (model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) return model;
        return (maker + " " + model).trim();
    }

    public static String fingerprint(byte[] encodedPublicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedPublicKey);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    public static boolean verify(PublicKey key, byte[] data, byte[] signature) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(key);
        s.update(data);
        return s.verify(signature);
    }

    public static String shortId(String fingerprint) {
        return fingerprint == null ? "unknown" : fingerprint.substring(0, Math.min(8, fingerprint.length()));
    }
}
