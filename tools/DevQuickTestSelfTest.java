package is.quietlink.app;

public final class DevQuickTestSelfTest {
    private static DevQuickTest.Metrics sample(
            boolean connected, int mode, boolean visual,
            long aTx, long aRx, long vTx, long vRx,
            long heartbeat, float fps, boolean canonical) {
        return new DevQuickTest.Metrics(
                true, connected, connected,
                true, true,
                mode, false, false, mode != DevQuickTest.SessionServiceMode.BABY || true,
                visual, visual, true, true,
                canonical, visual, visual,
                false, 0,
                connected ? "Connected" : "Reconnecting",
                "Wi-Fi",
                visual ? "H.264 1280×720 • Good" : "Audio only",
                heartbeat, 42L,
                1, 2, 3,
                aTx, aRx, vTx, vRx,
                0L, 0L, 0L, 0L,
                fps, 0, 270, 270);
    }

    public static void main(String[] args) {
        DevQuickTest.Metrics before = sample(
                true, DevQuickTest.SessionServiceMode.VIDEO, true,
                100, 100, 1000, 1000, 1000, 25f, true);
        DevQuickTest.Metrics after = sample(
                true, DevQuickTest.SessionServiceMode.VIDEO, true,
                160, 170, 1500, 1450, 900, 28f, true);
        DevQuickTest.Report good = DevQuickTest.evaluate(before, after);
        if (good.failCount != 0) {
            throw new AssertionError("healthy sample produced failures: " + good.failCount);
        }

        DevQuickTest.Metrics broken = sample(
                false, DevQuickTest.SessionServiceMode.VIDEO, true,
                100, 100, 1000, 1000, 30000, 0f, false);
        DevQuickTest.Report bad = DevQuickTest.evaluate(before, broken);
        if (bad.failCount < 2) {
            throw new AssertionError("broken sample did not produce enough failures");
        }

        String rendered = good.render("test");
        if (rendered.contains("peerName") || rendered.contains("room code")) {
            throw new AssertionError("report unexpectedly contains identifying labels");
        }

        System.out.println("DevQuickTestSelfTest PASS");
    }
}
