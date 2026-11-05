package is.quietlink.app;

public final class QrPayloadSelfTest {
    public static void main(String[] args) {
        buildsExactFormat();
        roundTrips();
        buildRejectsBadInput();
        parseRejectsEverythingElse();
        System.out.println("QrPayloadSelfTest OK");
    }

    private static void buildsExactFormat() {
        check("QL1:local:012345".equals(QrPayload.build(ConnectionRoute.LOCAL, "012345")),
                "local payload format");
        check("QL1:mobile:987654".equals(QrPayload.build(ConnectionRoute.MOBILE, "987654")),
                "mobile payload format");
    }

    private static void roundTrips() {
        for (String route : new String[] {ConnectionRoute.LOCAL, ConnectionRoute.MOBILE}) {
            QrPayload parsed = QrPayload.parse(QrPayload.build(route, "000123"));
            check(parsed != null, "valid payload must parse: " + route);
            check(route.equals(parsed.route), "route must round trip: " + route);
            check("000123".equals(parsed.code), "leading zeros must round trip: " + route);
        }
    }

    private static void buildRejectsBadInput() {
        check(QrPayload.build(null, "123456") == null, "null route");
        check(QrPayload.build("", "123456") == null, "empty route");
        check(QrPayload.build("MOBILE", "123456") == null, "route case must match");
        check(QrPayload.build("relay", "123456") == null, "unknown route");
        check(QrPayload.build(ConnectionRoute.LOCAL, null) == null, "null code");
        check(QrPayload.build(ConnectionRoute.LOCAL, "12345") == null, "short code");
        check(QrPayload.build(ConnectionRoute.LOCAL, "1234567") == null, "long code");
        check(QrPayload.build(ConnectionRoute.LOCAL, "12345a") == null, "letter in code");
    }

    private static void parseRejectsEverythingElse() {
        String[] rejected = {
                null,
                "",
                "123456",
                "QL1",
                "QL1:",
                "QL1:local",
                "QL1:local:",
                "QL1::123456",
                "QL2:local:123456",
                "QL0:local:123456",
                "ql1:local:123456",
                "QL1:LOCAL:123456",
                "QL1:Mobile:123456",
                "QL1:relay:123456",
                "QL1:local:12345",
                "QL1:local:1234567",
                "QL1:local:12345a",
                "QL1:local:12 456",
                "QL1:local:-12345",
                " QL1:local:123456",
                "QL1:local:123456 ",
                "QL1:local:123456\n",
                "\nQL1:local:123456",
                "QL1:local:123456:extra",
                "QL1:local:123456\nQL1:mobile:123456",
                "QL1:local|mobile:123456",
                "QL1:local:\u0661\u0662\u0663\u0664\u0665\u0666",
                "QL1:local:\uff11\uff12\uff13\uff14\uff15\uff16",
                "https://example.com/?QL1:local:123456",
                "quietlink://join/QL1:local:123456",
                "tel:123456",
                "QL1:local:" + "1".repeat(100_000),
        };
        for (String text : rejected) {
            check(QrPayload.parse(text) == null,
                    "must reject: " + (text == null ? "null" : text.substring(0, Math.min(text.length(), 40))));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
