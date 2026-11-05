package is.quietlink.app;

import java.io.IOException;

/**
 * The rendezvous service answered, but it does not offer the relay endpoints.
 * That means the server software is out of date, so retrying cannot help.
 */
final class RelayUnavailableException extends IOException {
    RelayUnavailableException() {
        super("Relay endpoints unavailable");
    }

    static boolean isCauseOf(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof RelayUnavailableException) return true;
            current = current.getCause();
        }
        return false;
    }
}
