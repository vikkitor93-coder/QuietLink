package is.quietlink.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text inside a QuietLink host QR code: QL1:<route>:<6 digits>. A scan only
 * ever yields these two values, and nothing from a scan is opened or executed.
 * This class must stay free of Android imports so tools/QrPayloadSelfTest.java
 * can run on a plain JDK in CI.
 */
final class QrPayload {
    private static final String VERSION = "QL1";
    private static final Pattern FORMAT = Pattern.compile(VERSION + ":("
            + Pattern.quote(ConnectionRoute.LOCAL) + "|" + Pattern.quote(ConnectionRoute.MOBILE)
            + "):([0-9]{6})");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    // Longer than any valid payload, so hostile input never reaches the regex.
    private static final int MAX_LENGTH = 32;

    final String route;
    final String code;

    private QrPayload(String route, String code) {
        this.route = route;
        this.code = code;
    }

    /** Returns the QR text, or null if the route is unknown or the code is malformed. */
    static String build(String route, String code) {
        if (!ConnectionRoute.LOCAL.equals(route) && !ConnectionRoute.MOBILE.equals(route)) return null;
        if (code == null || !CODE.matcher(code).matches()) return null;
        return VERSION + ":" + route + ":" + code;
    }

    /** Returns the parsed payload, or null for anything other than an exact match. */
    static QrPayload parse(String text) {
        if (text == null || text.length() > MAX_LENGTH) return null;
        Matcher matcher = FORMAT.matcher(text);
        if (!matcher.matches()) return null;
        return new QrPayload(matcher.group(1), matcher.group(2));
    }
}
