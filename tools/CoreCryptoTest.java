package is.quietlink.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.concurrent.*;

/** Pure-JVM smoke test for the pairing/authenticated transport core. */
public final class CoreCryptoTest {
    public static void main(String[] args) throws Exception {
        matchingCodeRoundTripAndReplayRejection();
        wrongCodeRejected();
        downgradeVersionRejected();
        nearbyIdentityHandshake();
        rotatingMediaKeysCrossEpoch();
        oversizedControlFrameRejected();
        oversizedMediaFrameRejected();
        malformedNearbyLengthRejected();
        System.out.println("ALL CORE CRYPTO TESTS PASSED");
    }

    private static void matchingCodeRoundTripAndReplayRejection() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<CryptoChannel> hostF = ex.submit(() -> {
                Socket s = server.accept();
                return CryptoChannel.handshake(s, true, "482731", 41001);
            });
            Future<CryptoChannel> joinF = ex.submit(() -> {
                Socket s = new Socket("127.0.0.1", server.getLocalPort());
                return CryptoChannel.handshake(s, false, "482731", 41002);
            });
            CryptoChannel host = hostF.get(8, TimeUnit.SECONDS);
            CryptoChannel join = joinF.get(8, TimeUnit.SECONDS);

            if (!host.getVerification().equals(join.getVerification()))
                throw new AssertionError("Verification phrases differ");
            if (host.getPeerUdpPort() != 41002 || join.getPeerUdpPort() != 41001)
                throw new AssertionError("UDP port exchange failed");

            Future<String> read = ex.submit(join::readControl);
            host.sendControl("SWITCH_CAMERA");
            if (!"SWITCH_CAMERA".equals(read.get(2, TimeUnit.SECONDS)))
                throw new AssertionError("Control round trip failed");

            Future<String> chatRead = ex.submit(join::readControl);
            host.sendControl("CHAT:hello");
            if (!"CHAT:hello".equals(chatRead.get(2, TimeUnit.SECONDS)))
                throw new AssertionError("Chat channel round trip failed");

            byte[] audio = "audio-data".getBytes();
            CryptoChannel.EncryptedMedia audioPacket = host.encryptMedia((byte) 1, audio);
            byte[] audioGot = join.decryptMedia((byte) 1, audioPacket.seq(), audioPacket.cipher());
            if (!Arrays.equals(audio, audioGot)) throw new AssertionError("Audio decrypt failed");

            byte[] plain = "frame-data".getBytes();
            CryptoChannel.EncryptedMedia packet = host.encryptMedia((byte) 2, plain);
            byte[] got = join.decryptMedia((byte) 2, packet.seq(), packet.cipher());
            if (!Arrays.equals(plain, got)) throw new AssertionError("Video decrypt failed");
            if (audioPacket.seq() != 1L || packet.seq() != 1L)
                throw new AssertionError("Audio/video counters are not independently separated");

            boolean replayRejected = false;
            try { join.decryptMedia((byte) 2, packet.seq(), packet.cipher()); }
            catch (GeneralSecurityException expected) { replayRejected = true; }
            if (!replayRejected) throw new AssertionError("Replay was accepted");

            host.close(); join.close(); ex.shutdownNow();
            System.out.println("PASS matching-code handshake/control/media/replay");
        }
    }

    private static void downgradeVersionRejected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);

            Future<Boolean> hostF = ex.submit(() -> {
                try (Socket s = server.accept()) {
                    CryptoChannel.handshake(s, true, "482731", 44001);
                    return false;
                } catch (Exception expected) {
                    return true;
                }
            });

            Future<Boolean> fakeOldPeer = ex.submit(() -> {
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort())) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());

                    if (in.readInt() != CryptoChannel.MAGIC) return false;
                    int version = in.readInt();
                    int suiteLen = in.readInt();
                    if (version != CryptoChannel.PROTOCOL_VERSION || suiteLen < 1 || suiteLen > 128) return false;
                    byte[] suite = new byte[suiteLen];
                    in.readFully(suite);

                    int pubLen = in.readInt();
                    if (pubLen < 1 || pubLen > 2048) return false;
                    in.skipNBytes(pubLen);
                    int nonceLen = in.readInt();
                    in.skipNBytes(nonceLen);
                    in.readInt(); // UDP port

                    byte[] suiteBytes = CryptoChannel.CIPHER_SUITE.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(CryptoChannel.MAGIC);
                    out.writeInt(CryptoChannel.PROTOCOL_VERSION - 1);
                    out.writeInt(suiteBytes.length);
                    out.write(suiteBytes);
                    out.flush();
                    return true;
                }
            });

            if (!fakeOldPeer.get(5, TimeUnit.SECONDS) || !hostF.get(5, TimeUnit.SECONDS))
                throw new AssertionError("Downgrade protocol version was not rejected");
            ex.shutdownNow();
            System.out.println("PASS protocol downgrade rejection");
        }
    }

    private static void nearbyIdentityHandshake() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair serverIdentity = gen.generateKeyPair();
        KeyPair clientIdentity = gen.generateKeyPair();

        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<CryptoChannel> serverF = ex.submit(() -> {
                Socket s = server.accept();
                return CryptoChannel.handshakeNearby(s, true, serverIdentity, 43001);
            });
            Future<CryptoChannel> clientF = ex.submit(() -> {
                Socket s = new Socket("127.0.0.1", server.getLocalPort());
                return CryptoChannel.handshakeNearby(s, false, clientIdentity, 43002);
            });

            CryptoChannel serverChannel = serverF.get(8, TimeUnit.SECONDS);
            CryptoChannel clientChannel = clientF.get(8, TimeUnit.SECONDS);

            if (!serverChannel.getVerification().equals(clientChannel.getVerification()))
                throw new AssertionError("Nearby verification phrases differ");
            if (!Arrays.equals(serverChannel.getPeerIdentityPublic(), clientIdentity.getPublic().getEncoded()))
                throw new AssertionError("Server did not verify client identity");
            if (!Arrays.equals(clientChannel.getPeerIdentityPublic(), serverIdentity.getPublic().getEncoded()))
                throw new AssertionError("Client did not verify server identity");

            Future<String> read = ex.submit(serverChannel::readControl);
            clientChannel.sendControl("CALL");
            if (!"CALL".equals(read.get(2, TimeUnit.SECONDS)))
                throw new AssertionError("Nearby control round trip failed");

            serverChannel.close();
            clientChannel.close();
            ex.shutdownNow();
            System.out.println("PASS nearby signed identity handshake");
        }
    }


    private static void rotatingMediaKeysCrossEpoch() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<CryptoChannel> hostF = ex.submit(() -> {
                Socket s = server.accept();
                return CryptoChannel.handshake(s, true, "482731", 45001);
            });
            Future<CryptoChannel> joinF = ex.submit(() -> {
                Socket s = new Socket("127.0.0.1", server.getLocalPort());
                return CryptoChannel.handshake(s, false, "482731", 45002);
            });
            CryptoChannel host = hostF.get(8, TimeUnit.SECONDS);
            CryptoChannel join = joinF.get(8, TimeUnit.SECONDS);

            for (long i = 1; i <= CryptoChannel.KEY_EPOCH_PACKETS + 2; i++) {
                byte[] plain = new byte[] {
                        (byte)(i >>> 24), (byte)(i >>> 16), (byte)(i >>> 8), (byte)i
                };
                CryptoChannel.EncryptedMedia packet =
                        host.encryptMedia((byte)2, plain);
                byte[] got = join.decryptMedia(
                        (byte)2, packet.seq(), packet.cipher());
                if (!Arrays.equals(plain, got))
                    throw new AssertionError("Media failed across key epoch at " + i);
            }

            host.close();
            join.close();
            ex.shutdownNow();
            System.out.println("PASS rotating media key epochs");
        }
    }

    private static void oversizedControlFrameRejected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<CryptoChannel> hostF = ex.submit(() -> {
                Socket s = server.accept();
                return CryptoChannel.handshake(s, true, "482731", 46001);
            });
            Future<CryptoChannel> joinF = ex.submit(() -> {
                Socket s = new Socket("127.0.0.1", server.getLocalPort());
                return CryptoChannel.handshake(s, false, "482731", 46002);
            });
            CryptoChannel host = hostF.get(8, TimeUnit.SECONDS);
            CryptoChannel join = joinF.get(8, TimeUnit.SECONDS);

            Future<Boolean> rejected = ex.submit(() -> {
                try {
                    join.readControl();
                    return false;
                } catch (Exception expected) {
                    return true;
                }
            });

            DataOutputStream raw = new DataOutputStream(host.getSocket().getOutputStream());
            raw.writeByte(0x43);
            raw.writeLong(1L);
            raw.writeInt(CryptoChannel.MAX_CONTROL_PLAINTEXT + 17);
            raw.flush();

            if (!rejected.get(2, TimeUnit.SECONDS))
                throw new AssertionError("Oversized control frame was accepted");

            host.close();
            join.close();
            ex.shutdownNow();
            System.out.println("PASS oversized control rejection");
        }
    }

    private static void oversizedMediaFrameRejected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<CryptoChannel> hostF = ex.submit(() -> {
                Socket s = server.accept();
                return CryptoChannel.handshake(s, true, "482731", 47001);
            });
            Future<CryptoChannel> joinF = ex.submit(() -> {
                Socket s = new Socket("127.0.0.1", server.getLocalPort());
                return CryptoChannel.handshake(s, false, "482731", 47002);
            });
            CryptoChannel host = hostF.get(8, TimeUnit.SECONDS);
            CryptoChannel join = joinF.get(8, TimeUnit.SECONDS);

            boolean rejected = false;
            try {
                join.decryptMedia((byte)2, 1L,
                        new byte[CryptoChannel.MAX_MEDIA_PLAINTEXT + 17]);
            } catch (Exception expected) {
                rejected = true;
            }
            if (!rejected) throw new AssertionError("Oversized media frame was accepted");

            host.close();
            join.close();
            ex.shutdownNow();
            System.out.println("PASS oversized media rejection");
        }
    }

    private static void malformedNearbyLengthRejected() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair serverIdentity = gen.generateKeyPair();

        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<Boolean> serverF = ex.submit(() -> {
                try (Socket s = server.accept()) {
                    CryptoChannel.handshakeNearby(s, true, serverIdentity, 48001);
                    return false;
                } catch (Exception expected) {
                    return true;
                }
            });

            Future<Boolean> attacker = ex.submit(() -> {
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort())) {
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    byte[] suite = CryptoChannel.CIPHER_SUITE.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(CryptoChannel.NEARBY_MAGIC);
                    out.writeInt(CryptoChannel.PROTOCOL_VERSION);
                    out.writeInt(suite.length);
                    out.write(suite);
                    out.writeInt(Integer.MAX_VALUE);
                    out.flush();
                    return true;
                }
            });

            if (!attacker.get(3, TimeUnit.SECONDS)
                    || !serverF.get(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Malformed Nearby length was not rejected");
            }
            ex.shutdownNow();
            System.out.println("PASS malformed Nearby length rejection");
        }
    }

    private static void wrongCodeRejected() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<Boolean> hostF = ex.submit(() -> {
                try (Socket s = server.accept()) {
                    CryptoChannel.handshake(s, true, "111111", 42001);
                    return false;
                } catch (Exception expected) { return true; }
            });
            Future<Boolean> joinF = ex.submit(() -> {
                try (Socket s = new Socket("127.0.0.1", server.getLocalPort())) {
                    CryptoChannel.handshake(s, false, "222222", 42002);
                    return false;
                } catch (Exception expected) { return true; }
            });
            if (!hostF.get(8, TimeUnit.SECONDS) || !joinF.get(8, TimeUnit.SECONDS))
                throw new AssertionError("Wrong pairing codes were not rejected");
            ex.shutdownNow();
            System.out.println("PASS wrong-code rejection");
        }
    }
}
