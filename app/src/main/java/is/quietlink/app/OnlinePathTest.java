package is.quietlink.app;

import java.net.DatagramSocket;

public final class OnlinePathTest {
    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final boolean publicCandidateFound;
        public final boolean secondaryConfirmed;
        public final boolean stableMapping;
        public final String title;
        public final String detail;

        Result(boolean publicCandidateFound, boolean secondaryConfirmed,
               boolean stableMapping, String title, String detail) {
            this.publicCandidateFound = publicCandidateFound;
            this.secondaryConfirmed = secondaryConfirmed;
            this.stableMapping = stableMapping;
            this.title = title;
            this.detail = detail;
        }
    }

    private OnlinePathTest() {}

    public static void run(Callback callback) {
        new Thread(() -> {
            Result result = execute();
            if (callback != null) callback.onResult(result);
        }, "QuietLink-OnlinePathTest").start();
    }

    private static Result execute() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(0);

            StunClient.Endpoint primary = null;
            boolean cloudflarePrimary = false;
            try {
                primary = StunClient.probe(socket, "stun.cloudflare.com", 3478, 3500);
                cloudflarePrimary = primary != null;
            } catch (Exception ignored) {}

            if (primary == null) {
                try {
                    primary = StunClient.probe(socket, "stun.cloudflare.com", 53, 3000);
                } catch (Exception ignored) {}
            }

            if (primary == null) {
                QuietLog.log("ONLINE", "path_test",
                        "candidate=0 secondary=0 stable=0");
                return new Result(false, false, false,
                        "Online path test: blocked",
                        "QuietLink could not discover a public UDP endpoint from this network. "
                                + "Local connections still work normally. This network may block STUN/UDP, "
                                + "or the public STUN service may be temporarily unreachable.");
            }

            StunClient.Endpoint secondary = null;
            try {
                secondary = StunClient.probe(socket, "stun.l.google.com", 19302, 3500);
            } catch (Exception ignored) {}

            boolean confirmed = secondary != null;
            boolean stable = confirmed && primary.sameMapping(secondary);

            QuietLog.log("ONLINE", "path_test",
                    "candidate=1 secondary=" + (confirmed ? 1 : 0)
                            + " stable=" + (stable ? 1 : 0)
                            + " primary=" + (cloudflarePrimary ? "cf3478" : "cf53"));

            if (!confirmed) {
                return new Result(true, false, false,
                        "Online path test: candidate found",
                        "QuietLink successfully discovered a public UDP endpoint. "
                                + "A second independent STUN check did not answer, so NAT mapping stability "
                                + "could not be confirmed. This is still enough to continue online-P2P development.");
            }

            if (stable) {
                return new Result(true, true, true,
                        "Online path test: strong result",
                        "QuietLink discovered a public UDP endpoint and the same mapping was seen by two "
                                + "independent STUN services. That is a good sign for direct peer-to-peer UDP. "
                                + "It does not guarantee every NAT will permit a full call.");
            }

            return new Result(true, true, false,
                    "Online path test: restrictive NAT possible",
                    "QuietLink discovered a public UDP endpoint, but the mapping changed between independent "
                            + "STUN services. Direct P2P may still work, but this network is more likely to need "
                            + "additional hole-punching logic or an encrypted relay fallback.");
        } catch (Exception e) {
            QuietLog.log("ONLINE", "path_test",
                    "candidate=0 reason=" + e.getClass().getSimpleName());
            return new Result(false, false, false,
                    "Online path test: unavailable",
                    "QuietLink could not run the UDP connectivity test on this device. "
                            + "Local connections are unaffected.");
        } finally {
            if (socket != null) socket.close();
        }
    }
}
