package is.quietlink.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class UpdateManager {
    // Signing-key migration pins. The old signer is public/compromised and is
    // retained only so already-installed copies can migrate to the new signer.
    // The new signer private key is never committed to the repository.
    private static final String OLD_SIGNER_SHA256 =
            "90b075695287a8080bda9fcb4cd4dff188e781a25a1d135ddfeeed85f1a09da1";
    private static final String NEXT_SIGNER_SHA256 =
            "da4ff75b61d65a3ff1c335bf87a2173c63bb031711faebfded2b965fb1c851b1";
    private static final String MANIFEST_URL =
            "https://vikkitor93-coder.github.io/QuietLink/update.json";
    private static final String ALLOWED_APK_PREFIX =
            "https://vikkitor93-coder.github.io/QuietLink/";
    private static final String PREF = "quietlink_update";
    private static final String KEY_PENDING_APK = "pending_apk";

    private UpdateManager() {}

    static void checkForUpdate(Activity activity, boolean userInitiated) {
        if (activity == null || activity.isFinishing()) return;
        QuietLog.log("UPDATE", "check_start", "manual=" + (userInitiated ? 1 : 0));
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject(fetchText(MANIFEST_URL));
                int latestCode = json.getInt("versionCode");
                String latestName = json.optString("versionName", "");
                String apkUrl = json.getString("apkUrl");
                String sha256 = json.getString("sha256").toLowerCase(Locale.ROOT);
                String notes = json.optString("notes", "");

                if (!apkUrl.startsWith(ALLOWED_APK_PREFIX) || !apkUrl.startsWith("https://")) {
                    throw new SecurityException("Update host rejected");
                }
                if (!sha256.matches("[0-9a-f]{64}")) {
                    throw new SecurityException("Invalid update checksum");
                }

                long currentCode = currentVersionCode(activity);
                activity.runOnUiThread(() -> {
                    if (latestCode <= currentCode) {
                        QuietLog.log("UPDATE", "up_to_date",
                                "current=" + currentCode + " latest=" + latestCode);
                        Toast.makeText(activity,
                                "QuietLink is up to date • v" + currentVersionName(activity),
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    String message = "Installed: v" + currentVersionName(activity)
                            + "\nAvailable: v" + latestName;
                    if (!notes.trim().isEmpty()) message += "\n\n" + notes.trim();

                    new AlertDialog.Builder(activity)
                            .setTitle("QuietLink update available")
                            .setMessage(message)
                            .setNegativeButton("Not now", null)
                            .setPositiveButton("Update", (d,w) ->
                                    downloadAndInstall(activity, latestCode,
                                            latestName, apkUrl, sha256))
                            .show();
                });
            } catch (Exception e) {
                QuietLog.log("UPDATE", "check_failed",
                        "type=" + e.getClass().getSimpleName());
                if (userInitiated) {
                    activity.runOnUiThread(() ->
                            new AlertDialog.Builder(activity)
                                    .setTitle("Could not check for update")
                                    .setMessage("QuietLink could not reach the update service. Try again when internet access is available.")
                                    .setPositiveButton("OK", null)
                                    .show());
                }
            }
        }, "QuietLink-UpdateCheck").start();
    }

    private static void downloadAndInstall(Activity activity,
                                           int versionCode,
                                           String versionName,
                                           String apkUrl,
                                           String expectedSha) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("QuietLink update");
        progress.setMessage("Downloading v" + versionName + "…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            File out = null;
            String stage = "prepare";
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("Could not create update cache");
                }
                out = new File(dir, "QuietLink-v" + versionName + ".apk");
                stage = "download";
                download(apkUrl, out);

                stage = "checksum";
                String actualSha = sha256(out);
                if (!actualSha.equalsIgnoreCase(expectedSha)) {
                    throw new SecurityException("Checksum mismatch");
                }

                stage = "package";
                verifyArchive(activity, out, versionCode);
                File finalOut = out;
                QuietLog.log("UPDATE", "download_verified",
                        "version_code=" + versionCode);
                activity.runOnUiThread(() -> {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                    beginInstall(activity, finalOut);
                });
            } catch (Exception e) {
                if (out != null) try { out.delete(); } catch (Exception ignored) {}
                final String failedStage = stage;
                String safeReason = safeFailureReason(e);
                QuietLog.log("UPDATE", "download_failed",
                        "stage=" + failedStage + " reason=" + safeReason);
                activity.runOnUiThread(() -> {
                    try { progress.dismiss(); } catch (Exception ignored) {}
                    new AlertDialog.Builder(activity)
                            .setTitle("Update failed")
                            .setMessage("QuietLink was not changed.\n\nStage: "
                                    + failedStage + "\nReason: " + safeReason)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "QuietLink-UpdateDownload").start();
    }

    static void onResume(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String path = activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE)
                .getString(KEY_PENDING_APK, null);
        if (path == null || path.trim().isEmpty()) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) return;

        activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_APK).apply();
        File apk = new File(path);
        if (apk.isFile()) {
            QuietLog.log("UPDATE", "install_permission_return", "");
            beginInstall(activity, apk);
        }
    }

    private static void beginInstall(Activity activity, File apk) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE)
                    .edit().putString(KEY_PENDING_APK, apk.getAbsolutePath()).apply();
            QuietLog.log("UPDATE", "request_install_permission", "");
            new AlertDialog.Builder(activity)
                    .setTitle("Allow QuietLink to update")
                    .setMessage("Android needs one-time permission for QuietLink to install its own signed updates. Enable “Allow from this source”, then return to QuietLink.")
                    .setNegativeButton("Cancel", (d,w) ->
                            activity.getSharedPreferences(PREF, Activity.MODE_PRIVATE)
                                    .edit().remove(KEY_PENDING_APK).apply())
                    .setPositiveButton("Open settings", (d,w) -> {
                        Intent settings = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(settings);
                    })
                    .show();
            return;
        }

        try {
            Uri apkUri = UpdateApkProvider.uriFor(activity, apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (install.resolveActivity(activity.getPackageManager()) == null) {
                throw new IllegalStateException("No package installer");
            }

            QuietLog.log("UPDATE", "installer_handoff", "method=content_uri");
            activity.startActivity(install);
        } catch (Exception e) {
            String safe = e instanceof android.content.ActivityNotFoundException
                    ? "no_package_installer"
                    : e.getClass().getSimpleName();
            QuietLog.log("UPDATE", "installer_failed", "reason=" + safe);
            new AlertDialog.Builder(activity)
                    .setTitle("Could not open Android updater")
                    .setMessage("QuietLink downloaded and verified the update, but Android's package installer could not be opened.\n\nReason: " + safe)
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private static String fetchText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 128 * 1024) throw new SecurityException("Manifest too large");
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            long declared = c.getContentLengthLong();
            // Content-Length is optional for valid HTTPS responses (CDNs may
            // use chunked transfer). Only reject a declared size when it is
            // clearly too large; the streaming byte counter below remains the
            // authoritative 100 MB safety bound.
            if (declared > 100L * 1024L * 1024L) {
                throw new SecurityException("APK too large");
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 100L * 1024L * 1024L) {
                        throw new SecurityException("APK too large");
                    }
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void verifyArchive(Activity activity,
                                      File apk,
                                      int expectedVersionCode) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) {
            throw new UpdateVerificationException("package_parse");
        }
        if (!activity.getPackageName().equals(archive.packageName)) {
            throw new UpdateVerificationException("package_name");
        }
        if (archive.getLongVersionCode() != expectedVersionCode) {
            throw new UpdateVerificationException("version_manifest");
        }
        if (archive.getLongVersionCode() <= currentVersionCode(activity)) {
            throw new UpdateVerificationException("version_not_newer");
        }

        PackageInfo installed = pm.getPackageInfo(
                activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Set<String> installedCerts = signerDigests(installed);
        Set<String> archiveCerts = signerDigests(archive);

        boolean installedLegacy = false;
        boolean archiveLegacy = false;

        if (installedCerts.isEmpty()) {
            PackageInfo legacyInstalled = pm.getPackageInfo(
                    activity.getPackageName(), PackageManager.GET_SIGNATURES);
            installedCerts = legacySignerDigests(legacyInstalled);
            installedLegacy = !installedCerts.isEmpty();
        }

        if (archiveCerts.isEmpty()) {
            PackageInfo legacyArchive = pm.getPackageArchiveInfo(
                    apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            archiveCerts = legacySignerDigests(legacyArchive);
            archiveLegacy = !archiveCerts.isEmpty();
        }

        QuietLog.log("UPDATE", "signer_source",
                "installed_legacy=" + (installedLegacy ? 1 : 0)
                        + " archive_legacy=" + (archiveLegacy ? 1 : 0));

        if (installedCerts.isEmpty() || archiveCerts.isEmpty()) {
            throw new UpdateVerificationException("signature_unavailable");
        }

        if (installedCerts.equals(archiveCerts)) {
            return;
        }

        // Some older/OEM PackageManager implementations expose the already-
        // rotated v2 signer correctly as the current signer, but return
        // different certificate-history shapes for the installed package and
        // an APK archive. Once both sides' CURRENT signer is exactly the pinned
        // v2 certificate, that is sufficient for QuietLink's pre-install check;
        // Android's package installer still enforces the platform signing
        // lineage before installation.
        if (samePinnedV2CurrentSigner(installed, archive,
                installedCerts, archiveCerts,
                installedLegacy, archiveLegacy)) {
            QuietLog.log("UPDATE", "signer_post_rotation",
                    "approved=1 compatibility=history_shape");
            return;
        }

        if (approvedSignerRotation(installed, archive,
                installedCerts, archiveCerts,
                installedLegacy, archiveLegacy)) {
            QuietLog.log("UPDATE", "signer_rotation",
                    "approved=1 legacy="
                            + ((installedLegacy || archiveLegacy) ? 1 : 0));
            return;
        }

        QuietLog.log("UPDATE", "signer_rotation", "approved=0");
        throw new UpdateVerificationException("signature_mismatch");
    }

    private static boolean samePinnedV2CurrentSigner(PackageInfo installed,
                                                     PackageInfo archive,
                                                     Set<String> installedCerts,
                                                     Set<String> archiveCerts,
                                                     boolean installedLegacy,
                                                     boolean archiveLegacy)
            throws Exception {
        boolean installedIsV2 = pinnedCurrentSigner(
                installed, installedCerts, installedLegacy, NEXT_SIGNER_SHA256);
        boolean archiveIsV2 = pinnedCurrentSigner(
                archive, archiveCerts, archiveLegacy, NEXT_SIGNER_SHA256);

        QuietLog.log("UPDATE", "signer_shape",
                "installed_current_v2=" + (installedIsV2 ? 1 : 0)
                        + " archive_current_v2=" + (archiveIsV2 ? 1 : 0)
                        + " installed_history_count=" + installedCerts.size()
                        + " archive_history_count=" + archiveCerts.size());

        return installedIsV2 && archiveIsV2;
    }

    private static boolean pinnedCurrentSigner(PackageInfo info,
                                               Set<String> observedCerts,
                                               boolean legacy,
                                               String pinnedDigest)
            throws Exception {
        if (legacy || info == null || info.signingInfo == null) {
            return observedCerts.size() == 1 && observedCerts.contains(pinnedDigest);
        }

        if (info.signingInfo.hasMultipleSigners()) return false;
        Set<String> current = currentSignerDigests(info);
        return current.size() == 1 && current.contains(pinnedDigest);
    }

    private static boolean approvedSignerRotation(PackageInfo installed,
                                                  PackageInfo archive,
                                                  Set<String> installedCerts,
                                                  Set<String> archiveCerts,
                                                  boolean installedLegacy,
                                                  boolean archiveLegacy)
            throws Exception {
        // Legacy PackageManager fallback cannot expose proof-of-rotation.
        // For that compatibility case, accept exactly one pinned transition:
        // old current signer -> new current signer. Android's package installer
        // still performs the platform signature/lineage verification afterwards.
        if (installedLegacy || archiveLegacy
                || installed == null || archive == null
                || installed.signingInfo == null || archive.signingInfo == null) {
            return installedCerts.size() == 1
                    && archiveCerts.size() == 1
                    && installedCerts.contains(OLD_SIGNER_SHA256)
                    && archiveCerts.contains(NEXT_SIGNER_SHA256);
        }

        if (installed.signingInfo.hasMultipleSigners()
                || archive.signingInfo.hasMultipleSigners()) {
            return false;
        }

        Set<String> installedCurrent = currentSignerDigests(installed);
        Set<String> archiveCurrent = currentSignerDigests(archive);
        Set<String> archiveHistory = signerDigests(archive);

        return installedCurrent.size() == 1
                && archiveCurrent.size() == 1
                && installedCurrent.contains(OLD_SIGNER_SHA256)
                && archiveCurrent.contains(NEXT_SIGNER_SHA256)
                && archiveHistory.contains(OLD_SIGNER_SHA256)
                && archiveHistory.contains(NEXT_SIGNER_SHA256);
    }

    private static final class UpdateVerificationException extends SecurityException {
        final String safeCode;
        UpdateVerificationException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }
    }

    private static String safeFailureReason(Exception e) {
        if (e instanceof UpdateVerificationException) {
            return ((UpdateVerificationException) e).safeCode;
        }
        if (e instanceof java.net.SocketTimeoutException) return "network_timeout";
        if (e instanceof java.net.UnknownHostException) return "network_dns";
        if (e instanceof javax.net.ssl.SSLException) return "tls";
        if (e instanceof SecurityException) {
            String m = e.getMessage();
            if ("Checksum mismatch".equals(m)) return "checksum_mismatch";
            if ("APK too large".equals(m)) return "apk_too_large";
            return "security_check";
        }
        if (e instanceof java.io.IOException) return "network_io";
        return e.getClass().getSimpleName();
    }

    private static long currentVersionCode(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String currentVersionName(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static Set<String> currentSignerDigests(PackageInfo info) throws Exception {
        Set<String> out = new HashSet<>();
        if (info == null || info.signingInfo == null) return out;
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null) return out;
        for (Signature s : signatures) {
            if (s == null) continue;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            out.add(hex(md.digest(s.toByteArray())));
        }
        return out;
    }

    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Set<String> out = new HashSet<>();
        if (info == null || info.signingInfo == null) return out;
        Signature[] signatures = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners()
                : info.signingInfo.getSigningCertificateHistory();
        if (signatures == null) return out;
        for (Signature s : signatures) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            out.add(hex(md.digest(s.toByteArray())));
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> legacySignerDigests(PackageInfo info) throws Exception {
        Set<String> out = new HashSet<>();
        if (info == null || info.signatures == null) return out;
        for (Signature s : info.signatures) {
            if (s == null) continue;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            out.add(hex(md.digest(s.toByteArray())));
        }
        return out;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) b.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return b.toString();
    }
}
