package is.quietlink.app;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class MediaTransport implements AutoCloseable {
    public static final byte TYPE_AUDIO = 1;
    public static final byte TYPE_VIDEO = 2;
    public interface Listener { void onMedia(byte type, byte[] payload); }

    private final DatagramSocket socket;
    private final CryptoChannel crypto;
    private final InetAddress peerAddress;
    private final int peerPort;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger droppedVideoPackets = new AtomicInteger(0);
    private final AtomicLong totalDroppedVideoPackets = new AtomicLong(0);
    private final AtomicLong audioTxPackets = new AtomicLong(0);
    private final AtomicLong videoTxPackets = new AtomicLong(0);
    private final AtomicLong audioRxPackets = new AtomicLong(0);
    private final AtomicLong videoRxPackets = new AtomicLong(0);

    // Audio always gets first chance at the socket. Video may be dropped rather
    // than letting a burst of JPEG chunks make speech late.
    private final ArrayBlockingQueue<Outbound> audioTx = new ArrayBlockingQueue<>(96);
    private final ArrayBlockingQueue<Outbound> videoTx = new ArrayBlockingQueue<>(320);

    private Thread rxThread;
    private Thread txThread;

    public MediaTransport(DatagramSocket socket, CryptoChannel crypto, InetAddress peerAddress, int peerPort, Listener listener) {
        this.socket = socket;
        this.crypto = crypto;
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.listener = listener;
        try {
            socket.setReceiveBufferSize(2 * 1024 * 1024);
            socket.setSendBufferSize(1024 * 1024);
        } catch (SocketException ignored) {}
    }

    public void start() {
        txThread = new Thread(this::sendLoop, "QuietLink-UDP-TX");
        rxThread = new Thread(this::receiveLoop, "QuietLink-UDP-RX");
        txThread.setPriority(Thread.MAX_PRIORITY);
        rxThread.setPriority(Thread.MAX_PRIORITY);
        txThread.start();
        rxThread.start();
    }

    public void send(byte type, byte[] plain) {
        if (!running.get() || plain == null) return;
        Outbound item = new Outbound(type, plain, false);

        if (type == TYPE_AUDIO) {
            if (!audioTx.offer(item)) {
                audioTx.poll();
                audioTx.offer(item);
            }
        } else {
            offerVideo(item);
        }
    }

    /**
     * H.264 config and keyframe chunks can be marked important so ordinary
     * P-frame backlog is discarded before those recovery packets are lost.
     */
    public void sendVideo(byte[] plain, boolean important) {
        if (!running.get() || plain == null) return;
        offerVideo(new Outbound(TYPE_VIDEO, plain, important));
    }

    private void offerVideo(Outbound item) {
        if (videoTx.offer(item)) return;

        int dropped = removeOldNonImportantVideo(32);
        if (dropped > 0) recordVideoDrops(dropped);
        if (videoTx.offer(item)) return;

        // The queue can contain only important chunks during a large IDR. This
        // should be rare; prefer the newest recovery data over an older unit.
        Outbound old = videoTx.poll();
        if (old != null) recordVideoDrops(1);
        if (!videoTx.offer(item)) recordVideoDrops(1);
    }

    private int removeOldNonImportantVideo(int max) {
        int dropped = 0;
        for (Outbound queued : videoTx) {
            if (dropped >= max) break;
            if (!queued.important && videoTx.remove(queued)) dropped++;
        }
        return dropped;
    }

    private void sendLoop() {
        while (running.get()) {
            try {
                Outbound item = audioTx.poll();
                if (item == null) item = videoTx.poll(4, TimeUnit.MILLISECONDS);
                if (item == null) continue;

                // Re-check audio after waking for video so speech can preempt a
                // backlog one packet at a time.
                if (item.type == TYPE_VIDEO) {
                    Outbound urgent = audioTx.poll();
                    if (urgent != null) {
                        sendPacket(urgent);
                        offerVideo(item);
                    } else {
                        sendPacket(item);
                    }
                } else {
                    sendPacket(item);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running.get()) SessionBus.status("Media send error: " + safeMessage(e));
            }
        }
    }

    private void sendPacket(Outbound item) throws Exception {
        CryptoChannel.EncryptedMedia em = crypto.encryptMedia(item.type, item.payload);
        byte[] cipher = em.cipher();
        ByteBuffer b = ByteBuffer.allocate(10 + cipher.length);
        b.put((byte)1).put(item.type).putLong(em.seq()).put(cipher);
        byte[] packet = b.array();
        socket.send(new DatagramPacket(packet, packet.length, peerAddress, peerPort));
        if (item.type == TYPE_AUDIO) audioTxPackets.incrementAndGet();
        else if (item.type == TYPE_VIDEO) videoTxPackets.incrementAndGet();
    }

    private void recordVideoDrops(int count) {
        if (count <= 0) return;
        droppedVideoPackets.addAndGet(count);
        totalDroppedVideoPackets.addAndGet(count);
    }

    public int audioQueueDepth() {
        return audioTx.size();
    }

    public int videoQueueDepth() {
        return videoTx.size();
    }

    public int consumeDroppedVideoPackets() {
        return droppedVideoPackets.getAndSet(0);
    }

    public long totalDroppedVideoPackets() { return totalDroppedVideoPackets.get(); }
    public long audioTxPackets() { return audioTxPackets.get(); }
    public long videoTxPackets() { return videoTxPackets.get(); }
    public long audioRxPackets() { return audioRxPackets.get(); }
    public long videoRxPackets() { return videoRxPackets.get(); }

    public void discardQueuedVideo() {
        int n = videoTx.size();
        videoTx.clear();
        if (n > 0) recordVideoDrops(n);
    }

    private void receiveLoop() {
        byte[] buf = new byte[1500];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running.get()) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
                if (!p.getAddress().equals(peerAddress) || p.getPort() != peerPort || p.getLength() < 27) continue;
                ByteBuffer b = ByteBuffer.wrap(p.getData(), 0, p.getLength());
                if (b.get() != 1) continue;
                byte type = b.get();
                long seq = b.getLong();
                byte[] cipher = new byte[b.remaining()];
                b.get(cipher);
                byte[] plain = crypto.decryptMedia(type, seq, cipher);
                if (type == TYPE_AUDIO) audioRxPackets.incrementAndGet();
                else if (type == TYPE_VIDEO) videoRxPackets.incrementAndGet();
                listener.onMedia(type, plain);
            } catch (SocketException e) {
                if (running.get()) SessionBus.status("Media socket closed");
                break;
            } catch (Exception ignored) {
                // Authentication failures, replayed packets, and malformed
                // datagrams are intentionally dropped.
            }
        }
    }

    @Override public void close() {
        running.set(false);
        socket.close();
        audioTx.clear();
        videoTx.clear();
        if (rxThread != null) rxThread.interrupt();
        if (txThread != null) txThread.interrupt();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static final class Outbound {
        final byte type;
        final byte[] payload;
        final boolean important;
        Outbound(byte type, byte[] payload, boolean important) {
            this.type = type;
            this.payload = payload;
            this.important = important;
        }
    }
}
