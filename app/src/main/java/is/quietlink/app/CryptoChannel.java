package is.quietlink.app;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoChannel implements Closeable {
    public static final int MAGIC = 0x514C4B32; // QLK2 framing magic
    public static final int NEARBY_MAGIC = 0x514C4E33; // QLN3 framing magic
    public static final int PROTOCOL_VERSION = 5;
    public static final String CIPHER_SUITE = "QL5-P256-HKDF-SHA256-AES256GCM-RK";
    private static final byte CHANNEL_CONTROL = 0x43;
    private static final byte CHANNEL_CHAT = 0x48;
    static final long KEY_EPOCH_PACKETS = 8192L;
    static final int MAX_CONTROL_PLAINTEXT = 16 * 1024;
    static final int MAX_CHAT_PLAINTEXT = 8 * 1024;
    static final int MAX_MEDIA_PLAINTEXT = 64 * 1024;
    private static final int GCM_TAG_BYTES = 16;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final TrafficSet tx;
    private final TrafficSet rx;
    private final AtomicLong ctrlTxSeq = new AtomicLong(1);
    private final AtomicLong chatTxSeq = new AtomicLong(1);
    private final AtomicLong audioTxSeq = new AtomicLong(1);
    private final AtomicLong videoTxSeq = new AtomicLong(1);
    private final AtomicLong otherTxSeq = new AtomicLong(1);
    private long ctrlRxExpected = 1;
    private long chatRxExpected = 1;
    private final ReplayWindow audioRxWindow = new ReplayWindow();
    private final ReplayWindow videoRxWindow = new ReplayWindow();
    private final ReplayWindow otherMediaRxWindow = new ReplayWindow();
    private final String verification;
    private final int peerUdpPort;
    private final byte[] peerIdentityPublic;
    private final String peerFingerprint;

    private CryptoChannel(Socket socket, TrafficSet tx, TrafficSet rx,
                          String verification, int peerUdpPort,
                          byte[] peerIdentityPublic, String peerFingerprint) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.tx = tx;
        this.rx = rx;
        this.verification = verification;
        this.peerUdpPort = peerUdpPort;
        this.peerIdentityPublic = peerIdentityPublic;
        this.peerFingerprint = peerFingerprint;
    }

    public static CryptoChannel handshake(Socket socket, boolean host, String code, int localUdpPort) throws Exception {
        if (code == null || !code.matches("\\d{6}")) throw new GeneralSecurityException("Invalid pairing code");
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(12000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair local = gen.generateKeyPair();
        byte[] pub = local.getPublic().getEncoded();
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        Handshake localHs = new Handshake(PROTOCOL_VERSION, CIPHER_SUITE, pub, nonce, localUdpPort);

        Handshake peer;
        if (host) {
            writeHandshake(out, localHs);
            peer = readHandshake(in);
        } else {
            peer = readHandshake(in);
            writeHandshake(out, localHs);
        }

        PublicKey peerPub = decodeP256Public(peer.pub);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(local.getPrivate());
        ka.doPhase(peerPub, true);
        byte[] shared = ka.generateSecret();

        Handshake hostHs = host ? localHs : peer;
        Handshake clientHs = host ? peer : localHs;
        byte[] transcript = transcript(hostHs, clientHs);
        byte[] pairingSecret = Pairing.secret(code);
        byte[] salt = sha256(concat(("QuietLink-code-v" + PROTOCOL_VERSION + "/" + CIPHER_SUITE)
                        .getBytes(StandardCharsets.UTF_8),
                pairingSecret, hostHs.nonce, clientHs.nonce));
        Arrays.fill(pairingSecret, (byte)0);
        byte[] master = hkdf(shared, salt,
                ("quietlink code master v" + PROTOCOL_VERSION).getBytes(StandardCharsets.UTF_8), 32);

        byte[] hostFinishedKey = hkdf(master, null, "host-finished".getBytes(StandardCharsets.UTF_8), 32);
        byte[] clientFinishedKey = hkdf(master, null, "client-finished".getBytes(StandardCharsets.UTF_8), 32);
        byte[] hostTag = hmac(hostFinishedKey, transcript);
        byte[] clientTag = hmac(clientFinishedKey, transcript);
        if (host) {
            writeFinished(out, hostTag);
            verifyFinished(readFinished(in), clientTag);
        } else {
            verifyFinished(readFinished(in), hostTag);
            writeFinished(out, clientTag);
        }

        TrafficSet h2c = trafficSet(master, "h2c");
        TrafficSet c2h = trafficSet(master, "c2h");
        TrafficSet tx = host ? h2c : c2h;
        TrafficSet rx = host ? c2h : h2c;

        socket.setSoTimeout(0);
        String verify = verification(master, transcript);
        wipe(shared, master, hostFinishedKey, clientFinishedKey, hostTag, clientTag, salt);
        return new CryptoChannel(socket, tx, rx, verify, peer.udpPort, null, null);
    }

    public static CryptoChannel handshakeNearby(Socket socket, boolean serverSide, KeyPair identity, int localUdpPort) throws Exception {
        if (identity == null || identity.getPrivate() == null || identity.getPublic() == null)
            throw new GeneralSecurityException("Missing device identity");

        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(15000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ephemeral = gen.generateKeyPair();

        byte[] pub = ephemeral.getPublic().getEncoded();
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        byte[] identityPub = identity.getPublic().getEncoded();
        byte[] signed = nearbySignedData(pub, nonce, localUdpPort, identityPub);
        byte[] signature = signIdentity(identity.getPrivate(), signed);
        NearbyHello local = new NearbyHello(PROTOCOL_VERSION, CIPHER_SUITE,
                pub, nonce, localUdpPort, identityPub, signature);

        NearbyHello peer;
        if (serverSide) {
            writeNearbyHello(out, local);
            peer = readNearbyHello(in);
        } else {
            peer = readNearbyHello(in);
            writeNearbyHello(out, local);
        }

        PublicKey peerIdentityKey = decodeP256Public(peer.identityPub);
        if (!verifyIdentity(peerIdentityKey,
                nearbySignedData(peer.pub, peer.nonce, peer.udpPort, peer.identityPub),
                peer.signature)) {
            throw new GeneralSecurityException("Peer identity signature failed");
        }

        PublicKey peerEphemeral = decodeP256Public(peer.pub);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ephemeral.getPrivate());
        ka.doPhase(peerEphemeral, true);
        byte[] shared = ka.generateSecret();

        NearbyHello serverHello = serverSide ? local : peer;
        NearbyHello clientHello = serverSide ? peer : local;
        byte[] transcript = nearbyTranscript(serverHello, clientHello);
        byte[] salt = sha256(concat(
                ("QuietLink-nearby-v" + PROTOCOL_VERSION + "/" + CIPHER_SUITE).getBytes(StandardCharsets.UTF_8),
                serverHello.nonce, clientHello.nonce,
                serverHello.identityPub, clientHello.identityPub));
        byte[] master = hkdf(shared, salt,
                ("quietlink nearby master v" + PROTOCOL_VERSION).getBytes(StandardCharsets.UTF_8), 32);

        byte[] serverFinishedKey = hkdf(master, null, "nearby-server-finished".getBytes(StandardCharsets.UTF_8), 32);
        byte[] clientFinishedKey = hkdf(master, null, "nearby-client-finished".getBytes(StandardCharsets.UTF_8), 32);
        byte[] serverTag = hmac(serverFinishedKey, transcript);
        byte[] clientTag = hmac(clientFinishedKey, transcript);
        if (serverSide) {
            writeFinished(out, serverTag);
            verifyFinished(readFinished(in), clientTag);
        } else {
            verifyFinished(readFinished(in), serverTag);
            writeFinished(out, clientTag);
        }

        TrafficSet s2c = trafficSet(master, "nearby-s2c");
        TrafficSet c2s = trafficSet(master, "nearby-c2s");
        TrafficSet tx = serverSide ? s2c : c2s;
        TrafficSet rx = serverSide ? c2s : s2c;

        socket.setSoTimeout(0);
        String verify = verification(master, transcript);
        String fingerprint = identityFingerprint(peer.identityPub);
        wipe(shared, master, serverFinishedKey, clientFinishedKey, serverTag, clientTag, salt);
        return new CryptoChannel(socket, tx, rx,
                verify, peer.udpPort, peer.identityPub, fingerprint);
    }

    private static byte[] nearbySignedData(byte[] pub, byte[] nonce, int udpPort, byte[] identityPub) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bytes);
        d.writeInt(NEARBY_MAGIC);
        d.writeInt(PROTOCOL_VERSION);
        writeString(d, CIPHER_SUITE);
        d.writeInt(pub.length); d.write(pub);
        d.writeInt(nonce.length); d.write(nonce);
        d.writeInt(udpPort);
        d.writeInt(identityPub.length); d.write(identityPub);
        d.flush();
        return bytes.toByteArray();
    }

    private static byte[] nearbyTranscript(NearbyHello server, NearbyHello client) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bytes);
        writeNearbyTranscriptPart(d, server);
        writeNearbyTranscriptPart(d, client);
        d.flush();
        return sha256Unchecked(bytes.toByteArray());
    }

    private static void writeNearbyTranscriptPart(DataOutputStream d, NearbyHello h) throws IOException {
        d.write(nearbySignedData(h.pub, h.nonce, h.udpPort, h.identityPub));
        d.writeInt(h.signature.length);
        d.write(h.signature);
    }

    private static void writeNearbyHello(DataOutputStream out, NearbyHello h) throws IOException {
        out.writeInt(NEARBY_MAGIC);
        out.writeInt(h.version);
        writeString(out, h.suite);
        out.writeInt(h.pub.length); out.write(h.pub);
        out.writeInt(h.nonce.length); out.write(h.nonce);
        out.writeInt(h.udpPort);
        out.writeInt(h.identityPub.length); out.write(h.identityPub);
        out.writeInt(h.signature.length); out.write(h.signature);
        out.flush();
    }

    private static NearbyHello readNearbyHello(DataInputStream in) throws IOException {
        if (in.readInt() != NEARBY_MAGIC) throw new IOException("Not a Nearby QuietLink peer");
        int version = in.readInt();
        String suite = readString(in, 128);
        requireProtocol(version, suite);
        int pubLen = in.readInt();
        if (pubLen < 64 || pubLen > 512) throw new IOException("Bad nearby public key");
        byte[] pub = new byte[pubLen]; in.readFully(pub);
        int nonceLen = in.readInt();
        if (nonceLen != 16) throw new IOException("Bad nearby nonce");
        byte[] nonce = new byte[nonceLen]; in.readFully(nonce);
        int udpPort = in.readInt();
        if (udpPort < 1 || udpPort > 65535) throw new IOException("Bad nearby UDP port");
        int idLen = in.readInt();
        if (idLen < 64 || idLen > 1024) throw new IOException("Bad identity key");
        byte[] identityPub = new byte[idLen]; in.readFully(identityPub);
        int sigLen = in.readInt();
        if (sigLen < 48 || sigLen > 256) throw new IOException("Bad identity signature");
        byte[] signature = new byte[sigLen]; in.readFully(signature);
        return new NearbyHello(version, suite, pub, nonce, udpPort, identityPub, signature);
    }

    private static byte[] signIdentity(PrivateKey key, byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    private static boolean verifyIdentity(PublicKey key, byte[] data, byte[] signature) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(key);
        s.update(data);
        return s.verify(signature);
    }

    private static String identityFingerprint(byte[] encoded) throws Exception {
        byte[] digest = sha256(encoded);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i++) out.append(String.format("%02x", digest[i] & 0xff));
        return out.toString();
    }

    private static void writeHandshake(DataOutputStream out, Handshake h) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(h.version);
        writeString(out, h.suite);
        out.writeInt(h.pub.length); out.write(h.pub);
        out.writeInt(h.nonce.length); out.write(h.nonce); out.writeInt(h.udpPort); out.flush();
    }

    private static Handshake readHandshake(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Not a compatible QuietLink peer");
        int version = in.readInt();
        String suite = readString(in, 128);
        requireProtocol(version, suite);
        int pubLen = in.readInt();
        if (pubLen < 64 || pubLen > 512) throw new IOException("Bad public key");
        byte[] pub = new byte[pubLen]; in.readFully(pub);
        int nonceLen = in.readInt();
        if (nonceLen != 16) throw new IOException("Bad handshake nonce");
        byte[] nonce = new byte[nonceLen]; in.readFully(nonce);
        int udpPort = in.readInt();
        if (udpPort < 1 || udpPort > 65535) throw new IOException("Bad UDP port");
        return new Handshake(version, suite, pub, nonce, udpPort);
    }

    private static byte[] transcript(Handshake host, Handshake client) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bytes);
        d.writeInt(MAGIC);
        d.writeInt(PROTOCOL_VERSION);
        writeString(d, CIPHER_SUITE);
        d.writeInt(host.pub.length); d.write(host.pub); d.write(host.nonce); d.writeInt(host.udpPort);
        d.writeInt(client.pub.length); d.write(client.pub); d.write(client.nonce); d.writeInt(client.udpPort);
        d.flush();
        return sha256Unchecked(bytes.toByteArray());
    }

    private static void requireProtocol(int version, String suite) throws IOException {
        if (version != PROTOCOL_VERSION || !CIPHER_SUITE.equals(suite)) {
            throw new IOException("QuietLink security protocol mismatch; update both devices");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int len = in.readInt();
        if (len < 1 || len > maxBytes) throw new IOException("Invalid protocol identifier");
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeFinished(DataOutputStream out, byte[] tag) throws IOException {
        out.writeInt(tag.length); out.write(tag); out.flush();
    }

    private static byte[] readFinished(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len != 32) throw new IOException("Invalid key confirmation");
        byte[] tag = new byte[len]; in.readFully(tag); return tag;
    }

    private static void verifyFinished(byte[] got, byte[] expected) throws GeneralSecurityException {
        if (!MessageDigest.isEqual(got, expected)) throw new GeneralSecurityException("Pairing code or secure handshake did not match");
    }

    public synchronized void sendControl(String text) throws Exception {
        if (text == null) throw new IOException("Null control frame");
        boolean isChat = text.startsWith("CHAT:");
        byte channel = isChat ? CHANNEL_CHAT : CHANNEL_CONTROL;
        String payload = isChat ? text.substring("CHAT:".length()) : text;
        AtomicLong counter = isChat ? chatTxSeq : ctrlTxSeq;
        Material material = isChat ? tx.chat : tx.control;
        int maxPlain = isChat ? MAX_CHAT_PLAINTEXT : MAX_CONTROL_PLAINTEXT;

        long seq = counter.getAndIncrement();
        if (seq <= 0) throw new GeneralSecurityException("Control sequence exhausted");
        byte[] plain = payload.getBytes(StandardCharsets.UTF_8);
        if (plain.length > maxPlain) throw new IOException("Control frame too large");

        EpochMaterial epoch = material.forSequence(seq);
        byte[] cipher = crypt(true, epoch.key,
                iv(epoch.ivPrefix, seq), aad(channel, seq, epoch.epoch), plain);
        out.writeByte(channel);
        out.writeLong(seq);
        out.writeInt(cipher.length);
        out.write(cipher);
        out.flush();
    }

    public String readControl() throws Exception {
        byte channel = in.readByte();
        boolean isChat;
        Material material;
        long expected;
        int maxPlain;
        if (channel == CHANNEL_CONTROL) {
            isChat = false;
            material = rx.control;
            expected = ctrlRxExpected;
            maxPlain = MAX_CONTROL_PLAINTEXT;
        } else if (channel == CHANNEL_CHAT) {
            isChat = true;
            material = rx.chat;
            expected = chatRxExpected;
            maxPlain = MAX_CHAT_PLAINTEXT;
        } else {
            throw new GeneralSecurityException("Unknown encrypted control channel");
        }

        long seq = in.readLong();
        if (seq <= 0 || seq != expected)
            throw new GeneralSecurityException("Invalid control sequence");
        int len = in.readInt();
        if (len < GCM_TAG_BYTES || len > maxPlain + GCM_TAG_BYTES)
            throw new IOException("Invalid control frame");

        byte[] cipher = new byte[len];
        in.readFully(cipher);
        EpochMaterial epoch = material.forSequence(seq);
        byte[] plain = crypt(false, epoch.key,
                iv(epoch.ivPrefix, seq), aad(channel, seq, epoch.epoch), cipher);
        if (plain.length > maxPlain) throw new IOException("Invalid control plaintext");

        String text = decodeUtf8Strict(plain);
        if (isChat) chatRxExpected++;
        else ctrlRxExpected++;
        return isChat ? "CHAT:" + text : text;
    }

    public EncryptedMedia encryptMedia(byte type, byte[] plain) throws Exception {
        requireMediaType(type);
        if (plain == null || plain.length == 0 || plain.length > MAX_MEDIA_PLAINTEXT)
            throw new IOException("Invalid media payload");

        Material material = mediaMaterial(tx, type);
        AtomicLong counter = mediaCounter(type);
        long seq = counter.getAndIncrement();
        if (seq <= 0) throw new GeneralSecurityException("Media sequence exhausted");

        EpochMaterial epoch = material.forSequence(seq);
        byte[] cipher = crypt(true, epoch.key,
                iv(epoch.ivPrefix, seq), aad(type, seq, epoch.epoch), plain);
        return new EncryptedMedia(seq, cipher);
    }

    public byte[] decryptMedia(byte type, long seq, byte[] cipher) throws Exception {
        requireMediaType(type);
        if (seq <= 0) throw new GeneralSecurityException("Invalid media sequence");
        if (cipher == null || cipher.length < GCM_TAG_BYTES
                || cipher.length > MAX_MEDIA_PLAINTEXT + GCM_TAG_BYTES)
            throw new IOException("Invalid media frame");

        ReplayWindow window = type == 1 ? audioRxWindow : videoRxWindow;
        if (!window.mayAccept(seq))
            throw new GeneralSecurityException("Duplicate or stale media packet");

        Material material = mediaMaterial(rx, type);
        EpochMaterial epoch = material.forSequence(seq);
        byte[] plain = crypt(false, epoch.key,
                iv(epoch.ivPrefix, seq), aad(type, seq, epoch.epoch), cipher);
        if (plain.length == 0 || plain.length > MAX_MEDIA_PLAINTEXT)
            throw new IOException("Invalid media plaintext");
        if (!window.accept(seq))
            throw new GeneralSecurityException("Duplicate or stale media packet");
        return plain;
    }

    private AtomicLong mediaCounter(byte type) {
        return type == 1 ? audioTxSeq : type == 2 ? videoTxSeq : otherTxSeq;
    }

    private static void requireMediaType(byte type) throws GeneralSecurityException {
        if (type != 1 && type != 2)
            throw new GeneralSecurityException("Unknown encrypted media channel");
    }

    private static Material mediaMaterial(TrafficSet set, byte type) {
        return type == 1 ? set.audio : set.video;
    }

    public int getPeerUdpPort() { return peerUdpPort; }
    public String getVerification() { return verification; }
    public Socket getSocket() { return socket; }
    public byte[] getPeerIdentityPublic() {
        return peerIdentityPublic == null ? null : Arrays.copyOf(peerIdentityPublic, peerIdentityPublic.length);
    }
    public String getPeerFingerprint() { return peerFingerprint; }

    private static byte[] aad(byte type, long seq, long epoch) {
        return ByteBuffer.allocate(21)
                .putInt(PROTOCOL_VERSION)
                .put(type)
                .putLong(seq)
                .putLong(epoch)
                .array();
    }
    private static byte[] iv(byte[] prefix, long seq) { return ByteBuffer.allocate(12).put(prefix, 0, 4).putLong(seq).array(); }
    private static byte[] crypt(boolean enc, SecretKeySpec key, byte[] iv, byte[] aad, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        c.updateAAD(aad);
        return c.doFinal(data);
    }

    private static Material material(byte[] master, String label) throws Exception {
        byte[] all = hkdf(master, null, label.getBytes(StandardCharsets.UTF_8), 36);
        Material material = new Material(
                Arrays.copyOfRange(all, 0, 32),
                Arrays.copyOfRange(all, 32, 36));
        Arrays.fill(all, (byte)0);
        return material;
    }

    private static TrafficSet trafficSet(byte[] master, String prefix) throws Exception {
        return new TrafficSet(
                material(master, prefix + "-control"),
                material(master, prefix + "-chat"),
                material(master, prefix + "-audio"),
                material(master, prefix + "-video"),
                material(master, prefix + "-other"));
    }

    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] realSalt = salt == null ? new byte[32] : salt;
        mac.init(new SecretKeySpec(realSalt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        int counter = 1;
        while (out.size() < len) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(t); mac.update(info); mac.update((byte) counter++);
            t = mac.doFinal(); out.write(t);
        }
        return Arrays.copyOf(out.toByteArray(), len);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] sha256(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }
    private static byte[] sha256Unchecked(byte[] data) {
        try { return sha256(data); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static byte[] concat(byte[]... arrays) {
        int n = 0; for (byte[] a : arrays) n += a.length;
        byte[] r = new byte[n]; int p = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, r, p, a.length); p += a.length; }
        return r;
    }

    private static PublicKey decodeP256Public(byte[] encoded) throws Exception {
        PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(encoded));
        if (!(key instanceof ECPublicKey))
            throw new GeneralSecurityException("Peer key is not EC");
        ECPublicKey ec = (ECPublicKey) key;
        if (ec.getParams() == null
                || ec.getParams().getCurve() == null
                || ec.getParams().getCurve().getField().getFieldSize() != 256) {
            throw new GeneralSecurityException("Peer key is not P-256");
        }
        return key;
    }

    private static String decodeUtf8Strict(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static void wipe(byte[]... arrays) {
        if (arrays == null) return;
        for (byte[] array : arrays) if (array != null) Arrays.fill(array, (byte)0);
    }

    private static String verification(byte[] master, byte[] transcript) throws Exception {
        String[] words = {"MOON","PINE","BLUE","WARM","STAR","MINT","WAVE","SNOW","BIRD","LEAF","FROG","ROSE","CLOUD","LIME","SAND","TREE"};
        byte[] d = sha256(concat(master, transcript, "verify".getBytes(StandardCharsets.UTF_8)));
        int number = ((d[3] & 0xff) * 256 + (d[4] & 0xff)) % 100;
        return words[d[0] & 15] + " • " + String.format("%02d", number) + " • " + words[d[1] & 15];
    }

    @Override public void close() throws IOException { socket.close(); }

    private static final class ReplayWindow {
        private long highest = 0;
        private long bitmap = 0;

        synchronized boolean mayAccept(long seq) {
            if (seq <= 0) return false;
            if (seq > highest) return true;
            long delta = highest - seq;
            if (delta >= 64) return false;
            long bit = 1L << (int)delta;
            return (bitmap & bit) == 0;
        }

        synchronized boolean accept(long seq) {
            if (seq > highest) {
                long shift = seq - highest;
                bitmap = shift >= 64 ? 1L : (bitmap << (int)shift) | 1L;
                highest = seq;
                return true;
            }
            long delta = highest - seq;
            if (delta >= 64) return false;
            long bit = 1L << (int)delta;
            if ((bitmap & bit) != 0) return false;
            bitmap |= bit;
            return true;
        }
    }

    private static final class NearbyHello {
        final int version;
        final String suite;
        final byte[] pub, nonce, identityPub, signature;
        final int udpPort;
        NearbyHello(int version, String suite, byte[] pub, byte[] nonce, int udpPort,
                    byte[] identityPub, byte[] signature) {
            this.version = version;
            this.suite = suite;
            this.pub = pub;
            this.nonce = nonce;
            this.udpPort = udpPort;
            this.identityPub = identityPub;
            this.signature = signature;
        }
    }

    private static final class Handshake {
        final int version;
        final String suite;
        final byte[] pub, nonce;
        final int udpPort;
        Handshake(int version, String suite, byte[] pub, byte[] nonce, int udpPort) {
            this.version = version;
            this.suite = suite;
            this.pub = pub;
            this.nonce = nonce;
            this.udpPort = udpPort;
        }
    }

    private static final class EpochMaterial {
        final long epoch;
        final SecretKeySpec key;
        final byte[] ivPrefix;

        EpochMaterial(long epoch, SecretKeySpec key, byte[] ivPrefix) {
            this.epoch = epoch;
            this.key = key;
            this.ivPrefix = ivPrefix;
        }
    }

    private static final class Material {
        final byte[] rootKey;
        final byte[] ivSeed;
        private long currentEpoch = -1L;
        private long previousEpoch = -1L;
        private EpochMaterial current;
        private EpochMaterial previous;

        Material(byte[] rootKey, byte[] ivSeed) {
            this.rootKey = rootKey;
            this.ivSeed = ivSeed;
        }

        synchronized EpochMaterial forSequence(long seq) throws Exception {
            long epoch = (seq - 1L) / KEY_EPOCH_PACKETS;
            if (current != null && currentEpoch == epoch) return current;
            if (previous != null && previousEpoch == epoch) return previous;

            byte[] info = ("quietlink-key-epoch-v" + PROTOCOL_VERSION + ":" + epoch)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] all = hkdf(rootKey, ivSeed, info, 36);
            EpochMaterial next = new EpochMaterial(
                    epoch,
                    new SecretKeySpec(Arrays.copyOfRange(all, 0, 32), "AES"),
                    Arrays.copyOfRange(all, 32, 36));
            Arrays.fill(all, (byte)0);

            previousEpoch = currentEpoch;
            previous = current;
            currentEpoch = epoch;
            current = next;
            return next;
        }
    }

    private static final class TrafficSet {
        final Material control, chat, audio, video, other;
        TrafficSet(Material control, Material chat, Material audio, Material video, Material other) {
            this.control = control;
            this.chat = chat;
            this.audio = audio;
            this.video = video;
            this.other = other;
        }
    }

    public static final class EncryptedMedia {
        private final long seq; private final byte[] cipher;
        EncryptedMedia(long seq, byte[] cipher) { this.seq = seq; this.cipher = cipher; }
        public long seq() { return seq; } public byte[] cipher() { return cipher; }
    }
}
