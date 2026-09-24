package is.quietlink.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Developer-only camera rotation experiments.
 *
 * Defaults intentionally reproduce the shipping v0.3.50 behavior. Nothing in
 * this class changes production orientation unless the hidden developer mode
 * explicitly enables/changes a setting.
 */
final class RotationLabConfig {
    static final int TX_CURRENT = 0;
    static final int TX_ANDROID_RELATIVE = 1;
    static final int TX_WEBRTC_STYLE = 2;
    static final int TX_SENSOR_ONLY = 3;
    // v0.3.63 normalized wire semantics: preserve Android Camera2's documented
    // sensor-relative quarter-turn value directly. The first ROT_CW1 live test
    // showed that converting front-camera values to an assumed generic
    // clockwise angle produced the wrong decoded quarter-turn on a fresh phone.
    static final int TX_CANONICAL_CLOCKWISE = 4;

    static final int SOURCE_DISPLAY = 0;
    static final int SOURCE_PHYSICAL_SENSOR = 1;

    static final int PREVIEW_STREAM_ROTATION = 0;
    static final int PREVIEW_DISPLAY_ONLY = 1;
    static final int PREVIEW_NONE = 2;
    static final int PREVIEW_INVERSE_STREAM = 3;

    static final int REMOTE_DIRECT = 0;
    static final int REMOTE_INVERSE = 1;

    static final int ASPECT_AUTO = 0;
    static final int ASPECT_16_9 = 1;
    static final int ASPECT_4_3 = 2;
    static final int ASPECT_3_2 = 3;
    static final int ASPECT_1_1 = 4;
    static final int ASPECT_STRETCH = 5;
    // Preserve the original persisted numeric values above. New portrait /
    // reciprocal ratios are appended so existing v0.3.56 settings migrate
    // without reinterpretation.
    static final int ASPECT_9_16 = 6;
    static final int ASPECT_3_4 = 7;
    static final int ASPECT_2_3 = 8;
    static final int ASPECT_5_4 = 9;
    static final int ASPECT_4_5 = 10;

    static final int PRESET_PRODUCTION = 0;
    static final int PRESET_ANDROID_TEXTUREVIEW = 1;
    static final int PRESET_WEBRTC = 2;
    static final int PRESET_ANDROID_FRAME_METADATA = 3;

    private static final String PREF = "quietlink_rotation_lab";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TX_FORMULA = "tx_formula";
    private static final String KEY_SOURCE = "rotation_source";
    private static final String KEY_PREVIEW = "preview_mode";
    private static final String KEY_LOCAL_PREVIEW_OFFSET = "local_preview_offset";
    private static final String KEY_MIRROR = "mirror_local";
    private static final String KEY_SEND_FRAME_META = "send_frame_meta";
    private static final String KEY_ACCEPT_FRAME_META = "accept_frame_meta";
    private static final String KEY_FORCE_ROTATION = "force_rotation";
    private static final String KEY_REMOTE_MODE = "remote_mode";
    private static final String KEY_REMOTE_OFFSET = "remote_offset";
    private static final String KEY_FORCE_JPEG = "force_jpeg";
    private static final String KEY_LOCAL_ASPECT = "local_aspect";
    private static final String KEY_REMOTE_ASPECT = "remote_aspect";
    private static final String KEY_FULLSCREEN_ASPECT = "fullscreen_aspect";
    private static final String KEY_FORCE_LEGACY_PIPELINE = "force_legacy_pipeline";
    private static final String PROFILE_PREFIX = "profile_";

    private RotationLabConfig() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String profileSlot(int mode, boolean fullscreen) {
        String kind = mode == SessionService.MODE_BABY ? "baby" : "video";
        return kind + "_" + (fullscreen ? "fullscreen" : "inline");
    }

    private static String profileKey(int mode, boolean fullscreen, String key) {
        return PROFILE_PREFIX + profileSlot(mode, fullscreen) + "_" + key;
    }

    static String profileLabel(int mode, boolean fullscreen) {
        return (mode == SessionService.MODE_BABY ? "BABY" : "VIDEO")
                + " • " + (fullscreen ? "FULLSCREEN" : "INLINE");
    }

    static boolean hasProfile(Context context, int mode, boolean fullscreen) {
        return prefs(context).getBoolean(
                profileKey(mode, fullscreen, "saved"), false);
    }

    static void saveProfile(Context context, int mode, boolean fullscreen) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor e = p.edit();
        String pre = PROFILE_PREFIX + profileSlot(mode, fullscreen) + "_";
        e.putBoolean(pre + "saved", true);
        e.putInt(pre + KEY_TX_FORMULA, txFormula(context));
        e.putInt(pre + KEY_SOURCE, rotationSource(context));
        e.putInt(pre + KEY_PREVIEW, localPreviewMode(context));
        e.putInt(pre + KEY_LOCAL_PREVIEW_OFFSET, localPreviewOffset(context));
        e.putBoolean(pre + KEY_MIRROR, mirrorLocalPreview(context));
        e.putBoolean(pre + KEY_SEND_FRAME_META, sendFrameRotation(context));
        e.putBoolean(pre + KEY_ACCEPT_FRAME_META, acceptFrameRotation(context));
        e.putInt(pre + KEY_FORCE_ROTATION, forcedTxRotation(context));
        e.putInt(pre + KEY_REMOTE_MODE, remoteMode(context));
        e.putInt(pre + KEY_REMOTE_OFFSET, remoteOffset(context));
        e.putBoolean(pre + KEY_FORCE_JPEG, forceJpeg(context));
        e.putInt(pre + KEY_LOCAL_ASPECT, localAspect(context));
        e.putInt(pre + KEY_REMOTE_ASPECT, remoteAspect(context));
        e.putInt(pre + KEY_FULLSCREEN_ASPECT, fullscreenAspect(context));
        e.apply();
    }

    static boolean applyProfile(Context context, int mode, boolean fullscreen) {
        SharedPreferences p = prefs(context);
        String pre = PROFILE_PREFIX + profileSlot(mode, fullscreen) + "_";
        if (!p.getBoolean(pre + "saved", false)) return false;

        SharedPreferences.Editor e = p.edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_TX_FORMULA,
                        p.getInt(pre + KEY_TX_FORMULA, TX_CURRENT))
                .putInt(KEY_SOURCE,
                        p.getInt(pre + KEY_SOURCE, SOURCE_DISPLAY))
                .putInt(KEY_PREVIEW,
                        p.getInt(pre + KEY_PREVIEW, PREVIEW_STREAM_ROTATION))
                .putInt(KEY_LOCAL_PREVIEW_OFFSET,
                        p.getInt(pre + KEY_LOCAL_PREVIEW_OFFSET, 0))
                .putBoolean(KEY_MIRROR,
                        p.getBoolean(pre + KEY_MIRROR, true))
                .putBoolean(KEY_SEND_FRAME_META,
                        p.getBoolean(pre + KEY_SEND_FRAME_META, false))
                .putBoolean(KEY_ACCEPT_FRAME_META,
                        p.getBoolean(pre + KEY_ACCEPT_FRAME_META, false))
                .putInt(KEY_FORCE_ROTATION,
                        p.getInt(pre + KEY_FORCE_ROTATION, -1))
                .putInt(KEY_REMOTE_MODE,
                        p.getInt(pre + KEY_REMOTE_MODE, REMOTE_DIRECT))
                .putInt(KEY_REMOTE_OFFSET,
                        p.getInt(pre + KEY_REMOTE_OFFSET, 0))
                .putBoolean(KEY_FORCE_JPEG,
                        p.getBoolean(pre + KEY_FORCE_JPEG, false))
                .putInt(KEY_LOCAL_ASPECT,
                        p.getInt(pre + KEY_LOCAL_ASPECT, ASPECT_AUTO))
                .putInt(KEY_REMOTE_ASPECT,
                        p.getInt(pre + KEY_REMOTE_ASPECT, ASPECT_AUTO))
                .putInt(KEY_FULLSCREEN_ASPECT,
                        p.getInt(pre + KEY_FULLSCREEN_ASPECT, ASPECT_AUTO));
        e.apply();
        return true;
    }

    static String profileSummary(Context context, int mode, boolean fullscreen) {
        SharedPreferences p = prefs(context);
        String pre = PROFILE_PREFIX + profileSlot(mode, fullscreen) + "_";
        boolean saved = p.getBoolean(pre + "saved", false);

        int tx = saved ? p.getInt(pre + KEY_TX_FORMULA, TX_CURRENT) : txFormula(context);
        int source = saved ? p.getInt(pre + KEY_SOURCE, SOURCE_DISPLAY) : rotationSource(context);
        int preview = saved ? p.getInt(pre + KEY_PREVIEW, PREVIEW_STREAM_ROTATION) : localPreviewMode(context);
        int previewOffset = saved ? p.getInt(pre + KEY_LOCAL_PREVIEW_OFFSET, 0) : localPreviewOffset(context);
        boolean mirror = saved ? p.getBoolean(pre + KEY_MIRROR, true) : mirrorLocalPreview(context);
        boolean frameTx = saved ? p.getBoolean(pre + KEY_SEND_FRAME_META, false) : sendFrameRotation(context);
        boolean frameRx = saved ? p.getBoolean(pre + KEY_ACCEPT_FRAME_META, false) : acceptFrameRotation(context);
        int forced = saved ? p.getInt(pre + KEY_FORCE_ROTATION, -1) : forcedTxRotation(context);
        int remoteModeValue = saved ? p.getInt(pre + KEY_REMOTE_MODE, REMOTE_DIRECT) : remoteMode(context);
        int remoteOffsetValue = saved ? p.getInt(pre + KEY_REMOTE_OFFSET, 0) : remoteOffset(context);
        int localAspectValue = saved ? p.getInt(pre + KEY_LOCAL_ASPECT, ASPECT_AUTO) : localAspect(context);
        int remoteAspectValue = saved ? p.getInt(pre + KEY_REMOTE_ASPECT, ASPECT_AUTO) : remoteAspect(context);
        int fullAspectValue = saved ? p.getInt(pre + KEY_FULLSCREEN_ASPECT, ASPECT_AUTO) : fullscreenAspect(context);
        boolean jpeg = saved ? p.getBoolean(pre + KEY_FORCE_JPEG, false) : forceJpeg(context);

        return "QuietLink " + profileLabel(mode, fullscreen)
                + " profile"
                + "\nWindow 1: direction=" + remoteModeLabel(remoteModeValue)
                + ", offset=+" + normalizeQuarter(remoteOffsetValue) + "°"
                + ", aspect=" + aspectLabel(fullscreen ? fullAspectValue : remoteAspectValue)
                + ", frameRX=" + (frameRx ? "ON" : "OFF")
                + "\nWindow 2: preview=" + previewLabel(preview)
                + ", offset=+" + normalizeQuarter(previewOffset) + "°"
                + ", aspect=" + aspectLabel(localAspectValue)
                + ", mirror=" + (mirror ? "ON" : "OFF")
                + "\nSender: rotation=" + forcedLabel(forced)
                + ", formula=" + txFormulaLabel(tx)
                + ", source=" + sourceLabel(source)
                + ", frameTX=" + (frameTx ? "ON" : "OFF")
                + ", codec=" + (jpeg ? "JPEG" : "AUTO/H264");
    }

    static void clearProfile(Context context, int mode, boolean fullscreen) {
        SharedPreferences p = prefs(context);
        String pre = PROFILE_PREFIX + profileSlot(mode, fullscreen) + "_";
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) {
            if (key != null && key.startsWith(pre)) e.remove(key);
        }
        e.apply();
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply();
    }

    static int txFormula(Context context) {
        return enabled(context)
                ? prefs(context).getInt(KEY_TX_FORMULA, TX_CURRENT)
                : TX_CURRENT;
    }

    static void setTxFormula(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_TX_FORMULA, clamp(value, TX_CURRENT, TX_CANONICAL_CLOCKWISE))
                .apply();
    }

    static int rotationSource(Context context) {
        return enabled(context)
                ? prefs(context).getInt(KEY_SOURCE, SOURCE_DISPLAY)
                : SOURCE_DISPLAY;
    }

    static void setRotationSource(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_SOURCE, value == SOURCE_PHYSICAL_SENSOR
                        ? SOURCE_PHYSICAL_SENSOR : SOURCE_DISPLAY)
                .apply();
    }

    static int localPreviewMode(Context context) {
        return enabled(context)
                ? prefs(context).getInt(KEY_PREVIEW, PREVIEW_STREAM_ROTATION)
                : PREVIEW_STREAM_ROTATION;
    }

    static void setLocalPreviewMode(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_PREVIEW, clamp(value, PREVIEW_STREAM_ROTATION, PREVIEW_INVERSE_STREAM))
                .apply();
    }

    static int localPreviewOffset(Context context) {
        if (!enabled(context)) return 0;
        return normalizeQuarter(prefs(context).getInt(KEY_LOCAL_PREVIEW_OFFSET, 0));
    }

    static void setLocalPreviewOffset(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_LOCAL_PREVIEW_OFFSET, normalizeQuarter(value))
                .apply();
    }

    static boolean mirrorLocalPreview(Context context) {
        return !enabled(context)
                || prefs(context).getBoolean(KEY_MIRROR, true);
    }

    static void setMirrorLocalPreview(Context context, boolean value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_MIRROR, value)
                .apply();
    }

    static boolean sendFrameRotation(Context context) {
        return enabled(context)
                && prefs(context).getBoolean(KEY_SEND_FRAME_META, false);
    }

    static void setSendFrameRotation(Context context, boolean value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_SEND_FRAME_META, value)
                .apply();
    }

    static boolean acceptFrameRotation(Context context) {
        return enabled(context)
                && prefs(context).getBoolean(KEY_ACCEPT_FRAME_META, false);
    }

    static void setAcceptFrameRotation(Context context, boolean value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_ACCEPT_FRAME_META, value)
                .apply();
    }

    static int forcedTxRotation(Context context) {
        if (!enabled(context)) return -1;
        int value = prefs(context).getInt(KEY_FORCE_ROTATION, -1);
        return value == 0 || value == 90 || value == 180 || value == 270
                ? value : -1;
    }

    static void setForcedTxRotation(Context context, int value) {
        int safe = value == 0 || value == 90 || value == 180 || value == 270
                ? value : -1;
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_FORCE_ROTATION, safe)
                .apply();
    }

    static int remoteMode(Context context) {
        return enabled(context)
                ? prefs(context).getInt(KEY_REMOTE_MODE, REMOTE_DIRECT)
                : REMOTE_DIRECT;
    }

    static void setRemoteMode(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_REMOTE_MODE, value == REMOTE_INVERSE ? REMOTE_INVERSE : REMOTE_DIRECT)
                .apply();
    }

    static int remoteOffset(Context context) {
        if (!enabled(context)) return 0;
        return normalizeQuarter(prefs(context).getInt(KEY_REMOTE_OFFSET, 0));
    }

    static boolean forceLegacyPipeline(Context context) {
        return prefs(context).getBoolean(KEY_FORCE_LEGACY_PIPELINE, false);
    }

    static void setForceLegacyPipeline(Context context, boolean value) {
        prefs(context).edit()
                .putBoolean(KEY_FORCE_LEGACY_PIPELINE, value)
                .apply();
    }


    static void setRemoteOffset(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_REMOTE_OFFSET, normalizeQuarter(value))
                .apply();
    }

    static boolean forceJpeg(Context context) {
        return enabled(context)
                && prefs(context).getBoolean(KEY_FORCE_JPEG, false);
    }

    static void setForceJpeg(Context context, boolean value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_FORCE_JPEG, value)
                .apply();
    }

    static int localAspect(Context context) {
        return enabled(context)
                ? clampAspect(prefs(context).getInt(KEY_LOCAL_ASPECT, ASPECT_AUTO))
                : ASPECT_AUTO;
    }

    static void setLocalAspect(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_LOCAL_ASPECT, clampAspect(value))
                .apply();
    }

    static int remoteAspect(Context context) {
        return enabled(context)
                ? clampAspect(prefs(context).getInt(KEY_REMOTE_ASPECT, ASPECT_AUTO))
                : ASPECT_AUTO;
    }

    static void setRemoteAspect(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_REMOTE_ASPECT, clampAspect(value))
                .apply();
    }

    static int fullscreenAspect(Context context) {
        return enabled(context)
                ? clampAspect(prefs(context).getInt(KEY_FULLSCREEN_ASPECT, ASPECT_AUTO))
                : ASPECT_AUTO;
    }

    static void setFullscreenAspect(Context context, int value) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_FULLSCREEN_ASPECT, clampAspect(value))
                .apply();
    }

    static float aspectRatio(int mode) {
        switch (clampAspect(mode)) {
            case ASPECT_9_16: return 9f / 16f;
            case ASPECT_4_3: return 4f / 3f;
            case ASPECT_3_4: return 3f / 4f;
            case ASPECT_3_2: return 3f / 2f;
            case ASPECT_2_3: return 2f / 3f;
            case ASPECT_5_4: return 5f / 4f;
            case ASPECT_4_5: return 4f / 5f;
            case ASPECT_1_1: return 1f;
            case ASPECT_16_9:
            case ASPECT_AUTO:
            default: return 16f / 9f;
        }
    }

    static boolean stretchAspect(int mode) {
        return clampAspect(mode) == ASPECT_STRETCH;
    }

    static String aspectLabel(int mode) {
        switch (clampAspect(mode)) {
            case ASPECT_16_9: return "16:9";
            case ASPECT_9_16: return "9:16";
            case ASPECT_4_3: return "4:3";
            case ASPECT_3_4: return "3:4";
            case ASPECT_3_2: return "3:2";
            case ASPECT_2_3: return "2:3";
            case ASPECT_5_4: return "5:4";
            case ASPECT_4_5: return "4:5";
            case ASPECT_1_1: return "1:1";
            case ASPECT_STRETCH: return "Stretch";
            default: return "Auto";
        }
    }

    static void resetProduction(Context context) {
        prefs(context).edit().clear().apply();
    }

    static void applyPreset(Context context, int preset) {
        SharedPreferences.Editor e = prefs(context).edit().clear();
        if (preset == PRESET_PRODUCTION) {
            e.apply();
            return;
        }

        e.putBoolean(KEY_ENABLED, true)
                .putInt(KEY_SOURCE, SOURCE_DISPLAY)
                .putBoolean(KEY_MIRROR, true)
                .putInt(KEY_FORCE_ROTATION, -1)
                .putInt(KEY_REMOTE_MODE, REMOTE_DIRECT)
                .putInt(KEY_REMOTE_OFFSET, 0)
                .putBoolean(KEY_FORCE_JPEG, false)
                .putInt(KEY_LOCAL_ASPECT, ASPECT_AUTO)
                .putInt(KEY_REMOTE_ASPECT, ASPECT_AUTO)
                .putInt(KEY_FULLSCREEN_ASPECT, ASPECT_AUTO);

        if (preset == PRESET_ANDROID_TEXTUREVIEW) {
            e.putInt(KEY_TX_FORMULA, TX_ANDROID_RELATIVE)
                    .putInt(KEY_PREVIEW, PREVIEW_DISPLAY_ONLY)
                    .putInt(KEY_LOCAL_PREVIEW_OFFSET, 0)
                    .putBoolean(KEY_SEND_FRAME_META, false)
                    .putBoolean(KEY_ACCEPT_FRAME_META, false);
        } else if (preset == PRESET_WEBRTC) {
            e.putInt(KEY_TX_FORMULA, TX_WEBRTC_STYLE)
                    .putInt(KEY_PREVIEW, PREVIEW_DISPLAY_ONLY)
                    .putInt(KEY_LOCAL_PREVIEW_OFFSET, 0)
                    .putBoolean(KEY_SEND_FRAME_META, true)
                    .putBoolean(KEY_ACCEPT_FRAME_META, true);
        } else {
            e.putInt(KEY_TX_FORMULA, TX_ANDROID_RELATIVE)
                    .putInt(KEY_PREVIEW, PREVIEW_DISPLAY_ONLY)
                    .putBoolean(KEY_SEND_FRAME_META, true)
                    .putBoolean(KEY_ACCEPT_FRAME_META, true);
        }
        e.apply();
    }

    /**
     * Compute rotation metadata for the encoded H.264 stream.
     *
     * physicalClockwise comes from OrientationEventListener. Android documents
     * that value in the opposite convention from Display#getRotation, so it is
     * negated before use when the physical-sensor experiment is selected.
     */
    static int computeTransmitRotation(Context context,
                                       int sensorDegrees,
                                       boolean frontFacing,
                                       int displayDegrees,
                                       int physicalClockwise,
                                       boolean physicalKnown) {
        int forced = forcedTxRotation(context);
        if (forced >= 0) return forced;

        int device = normalize(displayDegrees);
        if (enabled(context)
                && rotationSource(context) == SOURCE_PHYSICAL_SENSOR
                && physicalKnown) {
            device = normalize(360 - physicalClockwise);
        }

        int sensor = normalize(sensorDegrees);
        switch (txFormula(context)) {
            case TX_ANDROID_RELATIVE: {
                int sign = frontFacing ? 1 : -1;
                return normalize(sensor - device * sign);
            }
            case TX_WEBRTC_STYLE: {
                int deviceForCamera = frontFacing ? device : normalize(360 - device);
                return normalize(sensor + deviceForCamera);
            }
            case TX_SENSOR_ONLY:
                return sensor;
            case TX_CANONICAL_CLOCKWISE:
                return computeCanonicalRelativeRotation(
                        sensor, frontFacing, device);
            case TX_CURRENT:
            default:
                return normalize(sensor - device);
        }
    }

    /**
     * Returns Android Camera2's documented sensor-relative quarter-turn value.
     *
     * v0.3.62 ROT_CW1 attempted to convert the front-camera value into an
     * abstract clockwise display angle. The fresh-device test proved that
     * conversion was the wrong contract for QuietLink's decoded TextureView:
     * sensor=270/display=0 was transmitted as 90 and rendered with the wrong
     * quarter-turn. ROT_REL2 therefore keeps the Camera2 relative-rotation
     * value itself. A new capability token prevents mixed v0.3.62/v0.3.63
     * peers from interpreting the same number with different semantics.
     */
    static int computeCanonicalRelativeRotation(int sensorDegrees,
                                                boolean frontFacing,
                                                int deviceDegrees) {
        int sensor = normalize(sensorDegrees);
        int device = normalize(deviceDegrees);
        int sign = frontFacing ? 1 : -1;
        return normalize(sensor - device * sign);
    }

    static int resolveLocalPreviewRotation(Context context, int streamRotation) {
        int base;
        switch (localPreviewMode(context)) {
            case PREVIEW_DISPLAY_ONLY:
                // TextureView already compensates sensor orientation. Android's
                // Camera2 sample applies the inverse display rotation.
                base = normalize(360 - displayRotationDegrees(context));
                break;
            case PREVIEW_NONE:
                base = 0;
                break;
            case PREVIEW_INVERSE_STREAM:
                base = normalize(360 - streamRotation);
                break;
            case PREVIEW_STREAM_ROTATION:
            default:
                base = normalize(streamRotation);
                break;
        }
        return normalize(base + localPreviewOffset(context));
    }

    static int resolveRemoteRotation(Context context, int reportedRotation) {
        int base = normalize(reportedRotation);
        if (remoteMode(context) == REMOTE_INVERSE) base = normalize(360 - base);
        return normalize(base + remoteOffset(context));
    }

    @SuppressWarnings("deprecation")
    static int displayRotationDegrees(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                switch (wm.getDefaultDisplay().getRotation()) {
                    case Surface.ROTATION_90: return 90;
                    case Surface.ROTATION_180: return 180;
                    case Surface.ROTATION_270: return 270;
                    default: return 0;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    static String summary(Context context) {
        if (!enabled(context)) return "Production defaults";
        return "TX=" + txFormulaLabel(txFormula(context))
                + " • source=" + sourceLabel(rotationSource(context))
                + " • preview=" + previewLabel(localPreviewMode(context))
                + "+" + localPreviewOffset(context)
                + " • mirror=" + (mirrorLocalPreview(context) ? "ON" : "OFF")
                + " • frameMeta=" + (sendFrameRotation(context) ? "TX" : "-")
                + "/" + (acceptFrameRotation(context) ? "RX" : "-")
                + " • force=" + forcedLabel(forcedTxRotation(context))
                + " • remote=" + remoteModeLabel(remoteMode(context))
                + "+" + remoteOffset(context)
                + " • localAspect=" + aspectLabel(localAspect(context))
                + " • remoteAspect=" + aspectLabel(remoteAspect(context))
                + " • fullAspect=" + aspectLabel(fullscreenAspect(context))
                + " • pipeline=" + (forceLegacyPipeline(context)
                    ? "LEGACY OVERRIDE" : "AUTO")
                + " • codec=" + (forceJpeg(context) ? "JPEG" : "AUTO");
    }

    static String txFormulaLabel(int value) {
        switch (value) {
            case TX_ANDROID_RELATIVE: return "Android relative";
            case TX_WEBRTC_STYLE: return "WebRTC/JPEG";
            case TX_SENSOR_ONLY: return "Sensor only";
            case TX_CANONICAL_CLOCKWISE: return "Canonical relative";
            default: return "Current QL";
        }
    }

    static String sourceLabel(int value) {
        return value == SOURCE_PHYSICAL_SENSOR ? "Physical sensor" : "Display";
    }

    static String previewLabel(int value) {
        switch (value) {
            case PREVIEW_DISPLAY_ONLY: return "TextureView display-only";
            case PREVIEW_NONE: return "No rotation";
            case PREVIEW_INVERSE_STREAM: return "Inverse stream";
            default: return "Stream rotation";
        }
    }

    static String remoteModeLabel(int value) {
        return value == REMOTE_INVERSE ? "Inverse" : "Direct";
    }

    static String forcedLabel(int value) {
        return value < 0 ? "Auto" : value + "°";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampAspect(int value) {
        return clamp(value, ASPECT_AUTO, ASPECT_4_5);
    }

    private static int normalizeQuarter(int value) {
        int n = normalize(value);
        if (n < 45 || n >= 315) return 0;
        if (n < 135) return 90;
        if (n < 225) return 180;
        return 270;
    }

    static int normalize(int value) {
        return ((value % 360) + 360) % 360;
    }
}
