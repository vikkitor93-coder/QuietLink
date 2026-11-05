package is.quietlink.app;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds one session's request for the cellular network. Sockets are bound one by
 * one and the process is never bound, so the updater and other app traffic stay
 * on the default network. A closed holder refuses new requests, which prevents a
 * request from leaking if close() races with acquireCellular().
 */
final class MobileNetworkHolder {
    private final ConnectivityManager connectivity;
    private final Object lock = new Object();
    private ConnectivityManager.NetworkCallback callback;
    private CountDownLatch available;
    private Network network;
    private boolean closed;

    MobileNetworkHolder(ConnectivityManager connectivity) {
        this.connectivity = connectivity;
    }

    /** Blocks for up to timeoutMs, so it must not run on the main thread. */
    Network acquireCellular(long timeoutMs) {
        CountDownLatch latch;
        synchronized (lock) {
            if (closed || connectivity == null) return null;
            if (network != null) return network;
            if (callback == null && !requestLocked(timeoutMs)) return null;
            latch = available;
        }

        boolean interrupted = false;
        try {
            latch.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }

        Network result;
        synchronized (lock) {
            result = closed ? null : network;
            // A timed-out request is finished. Drop it so the next call asks again.
            if (result == null) releaseLocked();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return result;
    }

    Network current() {
        synchronized (lock) {
            return closed ? null : network;
        }
    }

    void close() {
        synchronized (lock) {
            closed = true;
            releaseLocked();
        }
    }

    private boolean requestLocked(long timeoutMs) {
        ConnectivityManager.NetworkCallback requested = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network value) {
                synchronized (lock) {
                    if (callback != this) return;
                    network = value;
                    available.countDown();
                }
            }

            @Override public void onLost(Network value) {
                synchronized (lock) {
                    if (callback != this || value == null || !value.equals(network)) return;
                    network = null;
                    available = new CountDownLatch(1);
                }
            }

            @Override public void onUnavailable() {
                synchronized (lock) {
                    if (callback != this) return;
                    available.countDown();
                }
            }
        };

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            callback = requested;
            available = new CountDownLatch(1);
            connectivity.requestNetwork(request, requested,
                    (int) Math.max(1L, Math.min(timeoutMs, Integer.MAX_VALUE)));
            return true;
        } catch (RuntimeException e) {
            // SecurityException and the per-app network request limit both land here.
            QuietLog.log("ONLINE", "mobile_network_request_failed",
                    "reason=" + e.getClass().getSimpleName());
            callback = null;
            available = null;
            return false;
        }
    }

    private void releaseLocked() {
        ConnectivityManager.NetworkCallback current = callback;
        CountDownLatch latch = available;
        callback = null;
        available = null;
        network = null;
        if (latch != null) latch.countDown();
        if (current != null) {
            try {
                connectivity.unregisterNetworkCallback(current);
            } catch (RuntimeException ignored) {
                // Android rejects a second unregister of a timed-out request.
            }
        }
    }

    /** The default network, only when Android has validated its internet access. */
    static Network activeValidatedInternet(ConnectivityManager connectivity) {
        if (connectivity == null) return null;
        try {
            Network active = connectivity.getActiveNetwork();
            return hasValidatedInternet(connectivity, active) ? active : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean hasValidatedInternet(ConnectivityManager connectivity, Network candidate) {
        if (connectivity == null || candidate == null) return false;
        try {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isCellular(ConnectivityManager connectivity, Network candidate) {
        if (connectivity == null || candidate == null) return false;
        try {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isGone(ConnectivityManager connectivity, Network candidate) {
        if (connectivity == null || candidate == null) return true;
        try {
            return connectivity.getNetworkCapabilities(candidate) == null;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
