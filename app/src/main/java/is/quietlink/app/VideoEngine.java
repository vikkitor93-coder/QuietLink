package is.quietlink.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Range;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VideoEngine implements AutoCloseable {
    public interface RotationReporter {
        void onRotationChanged(int degrees);
    }

    public interface FallbackReporter {
        void onFallback(String reason);
    }

    public interface KeyFrameRequester {
        void requestKeyFrame();
    }

    private static final int TARGET_PIXELS = 1280 * 720;
    private static final int START_JPEG_QUALITY = 42;
    private static final int MIN_JPEG_QUALITY = 28;
    private static final int MAX_JPEG_QUALITY = 82;
    private static final int START_FRAME_INTERVAL_MS = 100;
    private static final int MIN_FRAME_INTERVAL_MS = 75;
    private static final int MAX_FRAME_INTERVAL_MS = 170;
    private static final int CHUNK = 1024;
    private static final int MAX_CHUNKS = 256;
    private static final long ASSEMBLY_MAX_AGE_MS = 1_500;

    private final Context context;
    private final MediaTransport transport;
    private final CameraManager cameraManager;
    private final RotationReporter rotationReporter;
    private final H264Codec.Capability h264Capability;
    private final FallbackReporter fallbackReporter;
    private final KeyFrameRequester keyFrameRequester;
    private final H264VideoTransport h264;
    private final SessionBus.VideoSurfaceListener videoSurfaceListener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger(1);
    private final Map<Integer, FrameAssembly> assemblies = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "QuietLink-VideoDecode");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private final ThreadPoolExecutor previewDecoder = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "QuietLink-PreviewDecode");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder captureBuilder;
    private String currentCameraId;
    private int currentFacing = CameraCharacteristics.LENS_FACING_FRONT;
    private volatile boolean sendingEnabled;
    private volatile int physicalOrientationDegrees = 0;
    private volatile boolean physicalOrientationKnown = false;
    private volatile int remoteRotationDegrees = -1;
    private volatile int remoteReportedRotationDegrees = -1;
    private volatile int lastCaptureRotation = 0;
    private volatile int lastReportedRotation = -1;
    private volatile long lastRotationReportAtMs = 0;
    private volatile long lastPreviewAtMs = 0;
    private OrientationEventListener orientationListener;
    private volatile Size captureSize = new Size(640, 480);
    private volatile int adaptiveJpegQuality = START_JPEG_QUALITY;
    private volatile int adaptiveFrameIntervalMs = START_FRAME_INTERVAL_MS;
    private volatile Surface localPreviewSurface;
    private volatile boolean h264Enabled = false;
    private volatile boolean h264Session = false;
    private volatile boolean peerCanonicalRotation = false;
    private volatile boolean appliedCanonicalRotation = false;
    private long lastAdaptAtMs = 0;
    private int stableAdaptSamples = 0;
    private volatile boolean remoteVideoExpected = true;
    private volatile boolean torchRequested = false;
    private volatile long lastCameraRecoveryMs = 0L;
    private volatile long lastDecoderRecoveryMs = 0L;
    private volatile long engineStartedElapsedMs = 0L;
    private volatile long lastRemoteJpegFrameElapsedMs = 0L;
    private volatile long lastRemoteRecoveryRequestMs = 0L;
    private final AtomicInteger cameraGeneration = new AtomicInteger(0);
    private final AtomicBoolean healthLoopStarted = new AtomicBoolean(false);

    public VideoEngine(Context context, MediaTransport transport) {
        this(context, transport, null, null, null, null);
    }

    public VideoEngine(Context context, MediaTransport transport, RotationReporter rotationReporter) {
        this(context, transport, rotationReporter, null, null, null);
    }

    public VideoEngine(Context context,
                       MediaTransport transport,
                       RotationReporter rotationReporter,
                       H264Codec.Capability h264Capability,
                       FallbackReporter fallbackReporter,
                       KeyFrameRequester keyFrameRequester) {
        this.context = context;
        this.transport = transport;
        this.rotationReporter = rotationReporter;
        this.h264Capability = h264Capability;
        this.fallbackReporter = fallbackReporter;
        this.keyFrameRequester = keyFrameRequester;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.h264 = new H264VideoTransport(transport, h264Capability, new H264VideoTransport.Listener() {
            @Override public void onFrameRendered() {
                SessionBus.videoFrameRendered();
            }

            @Override public void onFrameRotation(int degrees) {
                if (canonicalRotationActive()
                        || RotationLabConfig.acceptFrameRotation(context)) {
                    setRemoteRotation(degrees);
                }
            }

            @Override public void onKeyFrameNeeded() {
                KeyFrameRequester requester = keyFrameRequester;
                if (requester != null) {
                    try { requester.requestKeyFrame(); } catch (Exception ignored) {}
                }
            }

            @Override public void onQualityTierRequested(int tier) {
                requestAdaptiveQualityTier(tier);
            }

            @Override public void onFatal(String reason) {
                handleH264Failure(reason);
            }
        });
        this.videoSurfaceListener = new SessionBus.VideoSurfaceListener() {
            @Override public void onRemoteVideoSurface(Surface surface) {
                h264.setOutputSurface(surface);
            }

            @Override public void onLocalVideoSurface(Surface surface) {
                boolean changed = localPreviewSurface != surface;
                localPreviewSurface = surface;
                if (changed && running.get() && sendingEnabled && h264Enabled) {
                    Handler handler = cameraHandler;
                    if (handler != null) {
                        handler.postDelayed(() -> {
                            if (running.get() && sendingEnabled && h264Enabled) {
                                startCamera(currentFacing);
                            }
                        }, 80L);
                    }
                }
            }
        };
        this.localPreviewSurface = SessionBus.localVideoSurface;
        SessionBus.setVideoSurfaceListener(videoSurfaceListener);

        orientationListener = new OrientationEventListener(context) {
            @Override public void onOrientationChanged(int orientation) {
                boolean physicalExperiment = RotationLabConfig.enabled(context)
                        && RotationLabConfig.rotationSource(context)
                        == RotationLabConfig.SOURCE_PHYSICAL_SENSOR;
                // Production behavior still respects Android rotation lock.
                // The developer physical-sensor experiment deliberately keeps
                // reading the sensor even while rotation lock is on.
                if (!physicalExperiment && !isAutoRotateEnabled()) {
                    physicalOrientationKnown = false;
                    return;
                }
                if (orientation == ORIENTATION_UNKNOWN) return;
                int rounded = ((orientation + 45) / 90 * 90) % 360;
                physicalOrientationDegrees = rounded;
                physicalOrientationKnown = true;
            }
        };
    }

    public void start(boolean sendVideo) {
        sendingEnabled = sendVideo;
        running.set(true);
        engineStartedElapsedMs = android.os.SystemClock.elapsedRealtime();
        lastRemoteJpegFrameElapsedMs = 0L;
        lastRemoteRecoveryRequestMs = 0L;
        QuietLog.log("VIDEO", "engine_start", "send=" + (sendVideo ? 1 : 0));
        applyRotationLabConfig();
        refreshOrientation();
        startVideoHealthLoop();
        if (sendVideo) startCamera(currentFacing);
    }

    public void setSendingEnabled(boolean enabled) {
        sendingEnabled = enabled;
        if (enabled) {
            adaptiveJpegQuality = START_JPEG_QUALITY;
            adaptiveFrameIntervalMs = START_FRAME_INTERVAL_MS;
            stableAdaptSamples = 0;
            lastAdaptAtMs = 0;
        }
        if (enabled && camera == null) startCamera(currentFacing);
        if (!enabled) {
            cameraGeneration.incrementAndGet();
            closeCameraOnly();
            SessionBus.localVideo(null);
        }
    }

    public boolean isSendingEnabled() { return sendingEnabled; }

    public void setRemoteVideoExpected(boolean expected) {
        remoteVideoExpected = expected;
        QuietLog.log("VIDEO", "remote_camera_state", "expected=" + (expected ? 1 : 0));
        if (expected && h264Enabled) {
            h264.refreshDecoderAfterWake();
            h264.forceRemoteKeyFrame();
            KeyFrameRequester requester = keyFrameRequester;
            if (requester != null) {
                try { requester.requestKeyFrame(); } catch (Exception ignored) {}
            }
        }
    }

    public void setH264Enabled(boolean enabled) {
        boolean use = enabled
                && h264Capability != null
                && h264Capability.usable()
                && !RotationLabConfig.forceJpeg(context);
        if (h264Enabled == use) {
            updateRotationProtocolState(false);
            return;
        }

        h264Enabled = use;
        h264.setEnabled(use);
        updateRotationProtocolState(false);
        if (use) {
            SessionBus.video(null);
            SessionBus.localVideo(null);
            SessionBus.status("Video HD • H.264 720p30 starting");
        }

        if (running.get() && sendingEnabled) {
            startCamera(currentFacing);
        }
    }

    public void setPeerCanonicalRotation(boolean supported) {
        boolean before = canonicalRotationActive();
        peerCanonicalRotation = supported;
        boolean after = canonicalRotationActive();
        updateRotationProtocolState(false);

        if (before != after && running.get() && sendingEnabled && h264Enabled) {
            startCamera(currentFacing);
        } else if (running.get() && sendingEnabled && h264Enabled) {
            refreshOrientation();
        }

        QuietLog.log("VIDEO", "rotation_wire_capability",
                "peer=" + (supported ? 1 : 0)
                        + " active=" + (after ? 1 : 0)
                        + " legacy_override="
                        + (RotationLabConfig.forceLegacyPipeline(context) ? 1 : 0));
    }

    private boolean canonicalRotationActive() {
        return h264Enabled
                && peerCanonicalRotation
                && !RotationLabConfig.forceLegacyPipeline(context);
    }

    private void updateRotationProtocolState(boolean restartCameraIfChanged) {
        boolean canonical = canonicalRotationActive();
        boolean changed = appliedCanonicalRotation != canonical;
        appliedCanonicalRotation = canonical;
        SessionBus.canonicalVideoRotation(canonical);
        h264.setRotationMetadataMode(
                canonical || RotationLabConfig.sendFrameRotation(context),
                canonical || RotationLabConfig.acceptFrameRotation(context));

        if (remoteReportedRotationDegrees >= 0) {
            remoteRotationDegrees = canonical
                    ? RotationLabConfig.normalize(remoteReportedRotationDegrees)
                    : RotationLabConfig.resolveRemoteRotation(
                            context, remoteReportedRotationDegrees);
            SessionBus.videoRotation(remoteRotationDegrees);
        }

        if (restartCameraIfChanged && changed
                && running.get() && sendingEnabled && h264Enabled) {
            startCamera(currentFacing);
        }
    }

    public boolean isH264Enabled() {
        return h264Enabled;
    }

    public int currentVideoBitrate() { return h264Enabled ? h264.currentBitrate() : 0; }
    public float videoTxFps() { return h264Enabled ? h264.txFps() : 0f; }
    public float videoRxFps() { return h264Enabled ? h264.rxFps() : 0f; }
    public long lostVideoUnits() { return h264Enabled ? h264.lostVideoUnits() : 0L; }
    public long keyFrameRequests() { return h264Enabled ? h264.keyFrameRequests() : 0L; }
    public int currentVideoWidth() { return h264Enabled ? h264.currentWidth() : captureSize.getWidth(); }
    public int currentVideoHeight() { return h264Enabled ? h264.currentHeight() : captureSize.getHeight(); }
    public String currentQualityLabel() { return h264Enabled ? h264.qualityLabel() : "JPEG"; }

    private void requestAdaptiveQualityTier(int requestedTier) {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.post(() -> {
            if (!running.get() || !sendingEnabled || !h264Enabled) return;
            int selected = chooseSupportedQualityTier(requestedTier);
            if (selected == h264.qualityTier()) return;
            if (!h264.applyQualityTier(selected)) return;
            SessionBus.status("Video adapting • H.264 "
                    + h264.currentWidth() + "×" + h264.currentHeight()
                    + " • 30 fps • " + h264.qualityLabel());
            startCamera(currentFacing);
        });
    }

    private int chooseSupportedQualityTier(int requestedTier) {
        int direction = requestedTier > h264.qualityTier() ? 1 : -1;
        int tier = Math.max(H264VideoTransport.TIER_HD,
                Math.min(H264VideoTransport.MAX_TIER, requestedTier));
        while (tier >= H264VideoTransport.TIER_HD && tier <= H264VideoTransport.MAX_TIER) {
            if (cameraSupportsH264Size(
                    currentCameraId,
                    H264VideoTransport.tierWidth(tier),
                    H264VideoTransport.tierHeight(tier))) {
                return tier;
            }
            tier += direction;
        }
        return h264.qualityTier();
    }

    private boolean cameraSupportsH264Size(String cameraId, int width, int height) {
        if (cameraId == null) return true;
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return true;
            Size[] sizes = map.getOutputSizes(android.media.MediaCodec.class);
            if (sizes == null || sizes.length == 0) return true;
            for (Size s : sizes) {
                if (s.getWidth() == width && s.getHeight() == height) return true;
            }
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    public void requestKeyFrame() {
        if (h264Enabled) {
            h264.requestKeyFrame();
            return;
        }
        if (running.get() && sendingEnabled) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastCameraRecoveryMs > 2500L) {
                lastCameraRecoveryMs = now;
                QuietLog.log("VIDEO", "peer_jpeg_recovery_restart", "");
                startCamera(currentFacing);
            }
        }
    }

    private void handleH264Failure(String reason) {
        if (!h264Enabled) return;
        new Thread(() -> {
            if (!h264Enabled) return;
            h264Enabled = false;
            h264.setEnabled(false);
            SessionBus.status("Video fell back to JPEG • " + (reason == null ? "codec unavailable" : reason));
            FallbackReporter reporter = fallbackReporter;
            if (reporter != null) {
                try { reporter.onFallback(reason); } catch (Exception ignored) {}
            }
            if (running.get() && sendingEnabled) startCamera(currentFacing);
        }, "QuietLink-H264-Fallback").start();
    }

    public void switchCamera() {
        currentFacing = currentFacing == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        if (sendingEnabled) startCamera(currentFacing);
    }

    public void setTorchEnabled(boolean enabled) {
        torchRequested = enabled;
        QuietLog.log("VIDEO", "torch_request", "enabled=" + (enabled ? 1 : 0));
        Handler h = cameraHandler;
        Runnable apply = this::applyTorchState;
        if (h != null) h.post(apply);
        else new Thread(apply, "QuietLink-Torch").start();
    }

    private void applyTorchState() {
        try {
            String activeId = currentCameraId;
            if (activeId != null && hasFlash(activeId)
                    && session != null && captureBuilder != null) {
                applyTorchToBuilder(captureBuilder, activeId);
                if (h264Session) {
                    session.setRepeatingRequest(captureBuilder.build(), null, cameraHandler);
                }
                QuietLog.log("VIDEO", "torch_apply", "path=active_camera");
                return;
            }
            String flashId = findFlashCamera();
            if (flashId != null) {
                cameraManager.setTorchMode(flashId, torchRequested);
                QuietLog.log("VIDEO", "torch_apply", "path=torch_api");
            } else {
                QuietLog.log("VIDEO", "torch_unavailable", "");
            }
        } catch (Exception e) {
            QuietLog.log("VIDEO", "torch_error",
                    "type=" + e.getClass().getSimpleName());
        }
    }

    private void applyTorchToBuilder(CaptureRequest.Builder builder, String cameraId) {
        if (builder == null || cameraId == null || !hasFlash(cameraId)) return;
        try {
            builder.set(CaptureRequest.FLASH_MODE,
                    torchRequested
                            ? CaptureRequest.FLASH_MODE_TORCH
                            : CaptureRequest.FLASH_MODE_OFF);
        } catch (Exception ignored) {}
    }

    private boolean hasFlash(String cameraId) {
        try {
            Boolean flash = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return Boolean.TRUE.equals(flash);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String findFlashCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                if (!hasFlash(id)) continue;
                Integer facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
            for (String id : cameraManager.getCameraIdList()) if (hasFlash(id)) return id;
        } catch (Exception ignored) {}
        return null;
    }

    private void startCamera(int facing) {
        if (!running.get() || !sendingEnabled) return;
        currentFacing = facing;
        SessionBus.localCameraFacing(
                facing == CameraCharacteristics.LENS_FACING_FRONT);

        Handler handler;
        synchronized (this) {
            if (cameraThread == null) {
                cameraThread = new HandlerThread("QuietLink-Camera");
                cameraThread.start();
                cameraHandler = new Handler(cameraThread.getLooper());
            }
            handler = cameraHandler;
        }
        if (handler == null) return;

        final int generation = cameraGeneration.incrementAndGet();
        QuietLog.log("VIDEO", "camera_start_queued",
                "gen=" + generation
                        + " facing=" + (facing == CameraCharacteristics.LENS_FACING_FRONT
                        ? "front" : "back"));

        // Older Camera2 HALs can fail when close/open/reconfigure requests overlap.
        // Coalesce bursts from surface changes, codec negotiation and watchdogs.
        handler.postDelayed(() -> openCameraGeneration(facing, generation), 240L);
    }

    private void openCameraGeneration(int facing, int generation) {
        if (!running.get() || !sendingEnabled
                || generation != cameraGeneration.get()) {
            return;
        }

        refreshOrientation();
        QuietLog.log("VIDEO", "camera_start",
                "gen=" + generation
                        + " facing=" + (facing == CameraCharacteristics.LENS_FACING_FRONT
                        ? "front" : "back")
                        + " display=" + displayRotationDegrees());

        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            SessionBus.status("Camera permission is required for video");
            return;
        }

        closeCameraOnly();

        try {
            currentCameraId = findCamera(facing);
            String[] ids = cameraManager.getCameraIdList();
            if (currentCameraId == null && ids.length > 0) currentCameraId = ids[0];
            if (currentCameraId == null) {
                SessionBus.status("No camera found");
                return;
            }
            captureSize = chooseJpegSize(currentCameraId);
            cameraManager.openCamera(
                    currentCameraId,
                    cameraStateCallback(generation),
                    cameraHandler);
        } catch (Exception e) {
            QuietLog.log("VIDEO", "camera_open_exception",
                    "type=" + e.getClass().getSimpleName());
            SessionBus.status("Camera unavailable • retrying");
            scheduleCameraRecovery("open_exception", 700L);
        }
    }

    private CameraDevice.StateCallback cameraStateCallback(final int generation) {
        return new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice c) {
                if (generation != cameraGeneration.get()
                        || !running.get() || !sendingEnabled) {
                    QuietLog.log("VIDEO", "camera_open_stale",
                            "gen=" + generation);
                    try { c.close(); } catch (Exception ignored) {}
                    return;
                }

                QuietLog.log("VIDEO", "camera_opened",
                        "gen=" + generation
                                + " facing=" + (currentFacing == CameraCharacteristics.LENS_FACING_FRONT
                                ? "front" : "back"));
                camera = c;
                createSession();
            }

            @Override public void onDisconnected(CameraDevice c) {
                boolean current = generation == cameraGeneration.get();
                QuietLog.log("VIDEO", "camera_disconnected",
                        "current=" + (current ? 1 : 0));
                try { c.close(); } catch (Exception ignored) {}
                if (!current) return;
                if (camera == c) camera = null;
                session = null;
                h264Session = false;
                SessionBus.status("Camera interrupted • recovering");
                scheduleCameraRecovery("disconnect", 650L);
            }

            @Override public void onError(CameraDevice c, int error) {
                boolean current = generation == cameraGeneration.get();
                QuietLog.log("VIDEO", "camera_error",
                        "code=" + error + " current=" + (current ? 1 : 0));
                try { c.close(); } catch (Exception ignored) {}
                if (!current) return;
                if (camera == c) camera = null;
                session = null;
                h264Session = false;
                SessionBus.status("Camera error • recovering");
                scheduleCameraRecovery("error_" + error, 900L);
            }
        };
    }

    private void scheduleCameraRecovery(String reason, long delayMs) {
        if (!running.get() || !sendingEnabled) return;
        Handler handler = cameraHandler;
        if (handler == null) return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastCameraRecoveryMs < 500L) return;
        lastCameraRecoveryMs = now;
        QuietLog.log("VIDEO", "camera_recovery_scheduled",
                "reason=" + reason);

        handler.postDelayed(() -> {
            if (running.get() && sendingEnabled) {
                startCamera(currentFacing);
            }
        }, Math.max(350L, delayMs));
    }

    private String findCamera(int facing) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            Integer f = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == facing) return id;
        }
        return null;
    }

    private Size chooseJpegSize(String cameraId) throws CameraAccessException {
        CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map =
                ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(640, 480);

        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long pixels = (long) s.getWidth() * s.getHeight();
            long diff = Math.abs((long) TARGET_PIXELS - pixels);
            long overshootPenalty =
                    pixels > TARGET_PIXELS ? (pixels - TARGET_PIXELS) * 3L : 0L;
            long orientationPenalty =
                    s.getWidth() >= s.getHeight() ? 0L : TARGET_PIXELS / 8L;
            long score = diff + overshootPenalty + orientationPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best == null ? new Size(640, 480) : best;
    }

    private void createSession() {
        if (h264Enabled && h264Capability != null && h264Capability.usable()) {
            createH264Session();
        } else {
            createJpegSession();
        }
    }

    private void createJpegSession() {
        h264Session = false;
        h264.stopEncoder();
        CameraDevice activeCamera = camera;
        if (activeCamera == null) return;
        try {
            int width = captureSize.getWidth();
            int height = captureSize.getHeight();
            reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(r -> {
                try (Image image = r.acquireLatestImage()) {
                    if (image == null || !sendingEnabled || !running.get()) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buffer.remaining()];
                    buffer.get(jpeg);
                    int frameRotation = lastCaptureRotation;
                    sendFrame(jpeg);
                    publishLocalPreview(jpeg, frameRotation);
                } catch (Exception ignored) {
                }
            }, cameraHandler);

            CaptureRequest.Builder b = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) adaptiveJpegQuality);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(currentCameraId));
            captureBuilder = b;
            applyTorchToBuilder(b, currentCameraId);

            activeCamera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (!running.get() || !sendingEnabled || camera != activeCamera) {
                        s.close();
                        return;
                    }
                    session = s;
                    SessionBus.status("Video " + width + "×" + height);
                    scheduleCapture(0);
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    QuietLog.log("VIDEO", "jpeg_session_failed", "");
                    SessionBus.status("Camera session failed • recovering");
                    scheduleCameraRecovery("jpeg_session", 700L);
                }
            }, cameraHandler);
        } catch (Exception e) {
            QuietLog.log("VIDEO", "jpeg_setup_error",
                    "type=" + e.getClass().getSimpleName());
            SessionBus.status("Video setup failed • recovering");
            scheduleCameraRecovery("jpeg_setup", 700L);
        }
    }

    private void createH264Session() {
        CameraDevice activeCamera = camera;
        if (activeCamera == null) return;

        try {
            h264.setEnabled(true);
            final Surface encoderSurface = h264.startEncoder();
            final Surface previewSurface = localPreviewSurface;

            List<Surface> outputs = new ArrayList<>();
            outputs.add(encoderSurface);
            if (previewSurface != null && previewSurface.isValid()) outputs.add(previewSurface);

            CaptureRequest.Builder record = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            record.addTarget(encoderSurface);
            if (previewSurface != null && previewSurface.isValid()) record.addTarget(previewSurface);
            record.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            applyCanonicalRotateAndCrop(record, currentCameraId);

            Range<Integer> fps = choose30FpsRange(currentCameraId);
            if (fps != null) {
                record.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            }
            captureBuilder = record;
            applyTorchToBuilder(record, currentCameraId);

            activeCamera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (!running.get() || !sendingEnabled || camera != activeCamera || !h264Enabled) {
                        s.close();
                        return;
                    }
                    try {
                        session = s;
                        h264Session = true;
                        s.setRepeatingRequest(
                                record.build(),
                                canonicalCaptureCallback(),
                                cameraHandler);
                        int rotation = surfaceStreamOrientation(currentCameraId);
                        lastCaptureRotation = rotation;
                        h264.setLocalFrameRotation(rotation);
                        reportRotation(rotation);
                        SessionBus.status("Video • H.264 "
                                + h264.currentWidth() + "×" + h264.currentHeight()
                                + " • 30 fps • adaptive " + h264.qualityLabel());
                        scheduleH264Orientation();
                    } catch (Exception e) {
                        handleH264Failure("Camera record start: " + safeMessage(e));
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    handleH264Failure("Camera cannot provide 720p30 H.264");
                }
            }, cameraHandler);
        } catch (Exception e) {
            handleH264Failure("H.264 setup: " + safeMessage(e));
        }
    }

    private void applyCanonicalRotateAndCrop(
            CaptureRequest.Builder builder, String cameraId) {
        if (!canonicalRotationActive() || Build.VERSION.SDK_INT < 31
                || builder == null || cameraId == null) {
            return;
        }
        try {
            int[] modes = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES);
            boolean noneSupported = false;
            if (modes != null) {
                for (int mode : modes) {
                    if (mode == CameraMetadata.SCALER_ROTATE_AND_CROP_NONE) {
                        noneSupported = true;
                        break;
                    }
                }
            }
            if (noneSupported) {
                builder.set(
                        CaptureRequest.SCALER_ROTATE_AND_CROP,
                        CameraMetadata.SCALER_ROTATE_AND_CROP_NONE);
            }
            QuietLog.log("VIDEO", "rotate_crop_request",
                    "canonical=1 none_supported=" + (noneSupported ? 1 : 0));
        } catch (Exception e) {
            QuietLog.log("VIDEO", "rotate_crop_request_failed",
                    "type=" + e.getClass().getSimpleName());
        }
    }

    private CameraCaptureSession.CaptureCallback canonicalCaptureCallback() {
        if (!canonicalRotationActive() || Build.VERSION.SDK_INT < 31) {
            return null;
        }
        AtomicBoolean logged = new AtomicBoolean(false);
        return new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(
                    CameraCaptureSession session,
                    CaptureRequest request,
                    TotalCaptureResult result) {
                if (!logged.compareAndSet(false, true) || result == null) return;
                try {
                    Integer actual = result.get(
                            CaptureResult.SCALER_ROTATE_AND_CROP);
                    QuietLog.log("VIDEO", "rotate_crop_result",
                            "actual=" + (actual == null ? -1 : actual)
                                    + " canonical=1");
                } catch (Exception ignored) {}
            }
        };
    }

    private Range<Integer> choose30FpsRange(String cameraId) {
        if (cameraId == null) return null;
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
            Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null;
            Range<Integer> best = null;
            for (Range<Integer> r : ranges) {
                if (r == null || r.getLower() > H264Codec.FPS || r.getUpper() < H264Codec.FPS) continue;
                if (best == null
                        || r.getLower() > best.getLower()
                        || (r.getLower().equals(best.getLower()) && r.getUpper() < best.getUpper())) {
                    best = r;
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void scheduleH264Orientation() {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.postDelayed(() -> {
            if (!running.get() || !sendingEnabled || !h264Enabled || !h264Session) return;
            try {
                int rotation = surfaceStreamOrientation(currentCameraId);
                lastCaptureRotation = rotation;
                h264.setLocalFrameRotation(rotation);
                reportRotation(rotation);
            } catch (Exception ignored) {}
            scheduleH264Orientation();
        }, 500L);
    }

    private int surfaceStreamOrientation(String id) throws CameraAccessException {
        int display = displayRotationDegrees();
        CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
        Integer sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
        int s = sensor == null ? 0 : sensor;
        boolean front = facing != null
                && facing == CameraCharacteristics.LENS_FACING_FRONT;

        int result;
        String formula;
        if (canonicalRotationActive()) {
            result = RotationLabConfig.computeCanonicalRelativeRotation(
                    s, front, display);
            formula = "Canonical relative";
        } else {
            result = RotationLabConfig.computeTransmitRotation(
                    context,
                    s,
                    front,
                    display,
                    physicalOrientationDegrees,
                    physicalOrientationKnown);
            formula = RotationLabConfig.txFormulaLabel(
                    RotationLabConfig.txFormula(context));
        }
        QuietLog.log("VIDEO", "surface_rotation_calc",
                "sensor=" + s
                        + " display=" + display
                        + " facing=" + (front ? "front" : "back")
                        + " formula=" + formula
                        + " source=" + RotationLabConfig.sourceLabel(
                            RotationLabConfig.rotationSource(context))
                        + " canonical=" + (canonicalRotationActive() ? 1 : 0)
                        + " result=" + result);
        return result;
    }

    @SuppressWarnings("deprecation")
    private int jpegOrientation(String id) throws CameraAccessException {
        // The display rotation is the authoritative orientation for what the
        // user is actually seeing. OrientationEventListener reports 90/270 in
        // a different convention on some devices, which made landscape H.264
        // appear upside down remotely.
        int display = displayRotationDegrees();
        CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
        Integer sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
        int s = sensor == null ? 0 : sensor;
        boolean front = facing != null
                && facing == CameraCharacteristics.LENS_FACING_FRONT;
        int result = front
                ? (s + display) % 360
                : (s - display + 360) % 360;
        // Do not log this per-frame calculation. JPEG capture can call it
        // many times per second and drown out rarer audio/recovery events in
        // the bounded privacy-safe trace. rotation_report still records the
        // applied orientation at a useful cadence.
        return result;
    }

    private void scheduleCapture(long delay) {
        if (h264Session) return;
        Handler h = cameraHandler;
        if (h == null) return;
        h.postDelayed(() -> {
            if (!running.get() || !sendingEnabled || session == null || captureBuilder == null) return;
            try {
                adaptVideoQuality();
                captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) adaptiveJpegQuality);
                int rotation = jpegOrientation(currentCameraId);
                lastCaptureRotation = rotation;
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
                reportRotation(rotation);
                session.capture(captureBuilder.build(), null, cameraHandler);
            } catch (Exception ignored) {
            }
            scheduleCapture(adaptiveFrameIntervalMs);
        }, delay);
    }

    private void adaptVideoQuality() {
        long now = System.currentTimeMillis();
        if (now - lastAdaptAtMs < 1000) return;
        lastAdaptAtMs = now;

        int queued = transport.videoQueueDepth();
        int dropped = transport.consumeDroppedVideoPackets();

        if (dropped > 0 || queued > 90) {
            adaptiveJpegQuality = Math.max(MIN_JPEG_QUALITY, adaptiveJpegQuality - 10);
            adaptiveFrameIntervalMs = Math.min(MAX_FRAME_INTERVAL_MS, adaptiveFrameIntervalMs + 20);
            stableAdaptSamples = 0;
            if (queued > 120) transport.discardQueuedVideo();
            return;
        }

        if (queued > 36) {
            adaptiveJpegQuality = Math.max(MIN_JPEG_QUALITY, adaptiveJpegQuality - 5);
            adaptiveFrameIntervalMs = Math.min(MAX_FRAME_INTERVAL_MS, adaptiveFrameIntervalMs + 10);
            stableAdaptSamples = 0;
            return;
        }

        if (queued <= 8) {
            stableAdaptSamples++;
            if (stableAdaptSamples >= 3) {
                adaptiveJpegQuality = Math.min(MAX_JPEG_QUALITY, adaptiveJpegQuality + 4);
                adaptiveFrameIntervalMs = Math.max(MIN_FRAME_INTERVAL_MS, adaptiveFrameIntervalMs - 5);
                stableAdaptSamples = 0;
            }
        } else {
            stableAdaptSamples = 0;
        }
    }

    private void sendFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;

        // Prefer the newest frame over accumulated old frames. This keeps
        // glass-to-glass latency low when Wi-Fi briefly slows down.
        if (transport.videoQueueDepth() > 110) {
            transport.discardQueuedVideo();
        }
        int count = (jpeg.length + CHUNK - 1) / CHUNK;
        if (count < 1 || count > MAX_CHUNKS) {
            SessionBus.status("Video frame too large; lowering camera load");
            return;
        }

        int frameId = frameCounter.getAndIncrement();
        for (int i = 0; i < count && running.get() && sendingEnabled; i++) {
            int off = i * CHUNK;
            int n = Math.min(CHUNK, jpeg.length - off);
            ByteBuffer p = ByteBuffer.allocate(8 + n);
            p.putInt(frameId).putShort((short) i).putShort((short) count).put(jpeg, off, n);
            transport.send(MediaTransport.TYPE_VIDEO, p.array());
        }
    }

    public void onRemoteChunk(byte[] payload) {
        if (payload == null || !running.get()) return;
        if (H264VideoTransport.looksLikePacket(payload)) {
            h264.onPacket(payload);
            return;
        }
        if (payload.length < 9) return;
        ByteBuffer b = ByteBuffer.wrap(payload);
        int frameId = b.getInt();
        int idx = b.getShort() & 0xffff;
        int count = b.getShort() & 0xffff;
        if (count < 1 || count > MAX_CHUNKS || idx >= count) return;

        byte[] part = new byte[b.remaining()];
        b.get(part);
        FrameAssembly a = assemblies.compute(frameId, (id, old) -> {
            if (old == null) return new FrameAssembly(count);
            return old.parts.length == count ? old : new FrameAssembly(count);
        });
        a.put(idx, part);

        if (a.complete() && assemblies.remove(frameId, a)) {
            lastRemoteJpegFrameElapsedMs = android.os.SystemClock.elapsedRealtime();
            byte[] jpeg = a.join();
            decoder.execute(() -> {
                Bitmap bmp = decodeOriented(jpeg, remoteRotationDegrees);
                if (bmp != null && running.get()) SessionBus.video(bmp);
            });
        }
        cleanupAssemblies();
    }

    private Bitmap decodeOriented(byte[] jpeg, int rotationHint) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return null;

        int rotate = 0;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
        } catch (Exception ignored) {}

        // Trust a real EXIF orientation first. Some camera HALs physically rotate
        // the JPEG and leave EXIF at normal; in that case use the sender's
        // per-orientation hint only when the bitmap shape still looks unrotated.
        if (rotate == 0 && rotationHint >= 0) {
            int hint = ((rotationHint % 360) + 360) % 360;
            if ((hint == 90 || hint == 270) && bmp.getWidth() >= bmp.getHeight()) {
                rotate = hint;
            } else if (hint == 180) {
                rotate = 180;
            }
        }

        if (rotate == 0) return bmp;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotate);
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }

    public void setRemoteRotation(int degrees) {
        if (degrees < 0) {
            remoteReportedRotationDegrees = -1;
            remoteRotationDegrees = -1;
            return;
        }
        remoteReportedRotationDegrees = RotationLabConfig.normalize(degrees);
        remoteRotationDegrees = canonicalRotationActive()
                ? remoteReportedRotationDegrees
                : RotationLabConfig.resolveRemoteRotation(
                        context, remoteReportedRotationDegrees);
        SessionBus.videoRotation(remoteRotationDegrees);
    }

    public void applyRotationLabConfig() {
        updateRotationProtocolState(true);
        h264.setLocalFrameRotation(lastCaptureRotation);

        // Force local + remote TextureViews to re-evaluate their transforms
        // even when the numeric stream rotation did not change.
        SessionBus.localVideoRotation(lastCaptureRotation);
        refreshOrientation();

        boolean after = canonicalRotationActive();
        QuietLog.log("VIDEO", "rotation_lab_apply",
                "enabled=" + (RotationLabConfig.enabled(context) ? 1 : 0)
                        + " tx=" + RotationLabConfig.txFormulaLabel(
                            RotationLabConfig.txFormula(context))
                        + " source=" + RotationLabConfig.sourceLabel(
                            RotationLabConfig.rotationSource(context))
                        + " preview=" + RotationLabConfig.previewLabel(
                            RotationLabConfig.localPreviewMode(context))
                        + " canonical=" + (after ? 1 : 0)
                        + " legacy_override="
                        + (RotationLabConfig.forceLegacyPipeline(context) ? 1 : 0)
                        + " frame_tx="
                        + ((after || RotationLabConfig.sendFrameRotation(context)) ? 1 : 0)
                        + " frame_rx="
                        + ((after || RotationLabConfig.acceptFrameRotation(context)) ? 1 : 0)
                        + " force_jpeg=" + (RotationLabConfig.forceJpeg(context) ? 1 : 0));
    }

    public void refreshAfterDisplayWake() {
        refreshOrientation();
        if (!running.get()) return;
        QuietLog.log("VIDEO", "display_wake_refresh", "h264=" + (h264Enabled ? 1 : 0));

        Handler handler = new Handler(context.getMainLooper());
        handler.postDelayed(() -> {
            if (!running.get()) return;
            if (h264Enabled) {
                QuietLog.log("VIDEO", "wake_decoder_rebind",
                        "render_age_ms=" + h264.renderedFrameAgeMs());
                h264.refreshDecoderAfterWake();
                h264.forceRemoteKeyFrame();
                KeyFrameRequester requester = keyFrameRequester;
                if (requester != null) {
                    try { requester.requestKeyFrame(); } catch (Exception ignored) {}
                }
            }
            recoverVideoIfStalled("wake_immediate");
        }, 120L);

        handler.postDelayed(() -> recoverVideoIfStalled("wake_followup"), 1200L);
        handler.postDelayed(() -> recoverVideoIfStalled("wake_followup"), 3000L);
    }

    private void startVideoHealthLoop() {
        if (!healthLoopStarted.compareAndSet(false, true)) return;
        new Handler(context.getMainLooper()).postDelayed(this::videoHealthTick, 1600L);
    }

    private void videoHealthTick() {
        if (!running.get()) {
            healthLoopStarted.set(false);
            return;
        }
        recoverVideoIfStalled("watchdog");
        new Handler(context.getMainLooper()).postDelayed(this::videoHealthTick, 1600L);
    }

    private void recoverVideoIfStalled(String source) {
        if (!running.get()) return;
        long now = android.os.SystemClock.elapsedRealtime();

        if (h264Enabled) {
            if (remoteVideoExpected && h264.decoderStalled(3200L)
                    && now - lastDecoderRecoveryMs > 2800L) {
                lastDecoderRecoveryMs = now;
                QuietLog.log("VIDEO", "decoder_stall_recover",
                        "source=" + source + " age_ms=" + h264.renderedFrameAgeMs());
                h264.refreshDecoderAfterWake();
                h264.forceRemoteKeyFrame();
                KeyFrameRequester requester = keyFrameRequester;
                if (requester != null) {
                    try { requester.requestKeyFrame(); } catch (Exception ignored) {}
                }
            }

            if (sendingEnabled && h264.encoderStalled(3200L)
                    && now - lastCameraRecoveryMs > 3500L) {
                lastCameraRecoveryMs = now;
                QuietLog.log("VIDEO", "encoder_stall_restart",
                        "source=" + source + " age_ms=" + h264.encodedFrameAgeMs());
                startCamera(currentFacing);
            }
        } else {
            if (remoteVideoExpected) {
                long base = lastRemoteJpegFrameElapsedMs > 0L
                        ? lastRemoteJpegFrameElapsedMs
                        : engineStartedElapsedMs;
                long remoteAge = base > 0L ? now - base : 0L;
                if (remoteAge > 4200L
                        && now - lastRemoteRecoveryRequestMs > 5000L) {
                    lastRemoteRecoveryRequestMs = now;
                    QuietLog.log("VIDEO", "remote_jpeg_stall_request",
                            "source=" + source + " age_ms=" + remoteAge);
                    KeyFrameRequester requester = keyFrameRequester;
                    if (requester != null) {
                        try { requester.requestKeyFrame(); } catch (Exception ignored) {}
                    }
                }
            }

            if (sendingEnabled && (camera == null || session == null)
                    && now - lastCameraRecoveryMs > 3500L) {
                lastCameraRecoveryMs = now;
                QuietLog.log("VIDEO", "jpeg_camera_restart", "source=" + source);
                startCamera(currentFacing);
            }
        }
    }

    public void refreshOrientation() {
        OrientationEventListener listener = orientationListener;
        physicalOrientationKnown = false;
        lastReportedRotation = -1;
        lastRotationReportAtMs = 0;
        if (listener == null || !listener.canDetectOrientation()) return;
        try { listener.disable(); } catch (Exception ignored) {}
        boolean physicalExperiment = RotationLabConfig.enabled(context)
                && RotationLabConfig.rotationSource(context)
                == RotationLabConfig.SOURCE_PHYSICAL_SENSOR;
        if (isAutoRotateEnabled() || physicalExperiment) {
            try { listener.enable(); } catch (Exception ignored) {}
        }
    }

    private boolean isAutoRotateEnabled() {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION,
                    1) == 1;
        } catch (Exception ignored) {
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private int displayRotationDegrees() {
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

    private void reportRotation(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        long now = System.currentTimeMillis();
        if (normalized == lastReportedRotation && now - lastRotationReportAtMs < 3000) return;
        lastReportedRotation = normalized;
        lastRotationReportAtMs = now;
        SessionBus.localVideoRotation(normalized);
        h264.setLocalFrameRotation(normalized);
        QuietLog.log("VIDEO", "rotation_report", "degrees=" + normalized);
        RotationReporter reporter = rotationReporter;
        if (reporter != null) {
            try { reporter.onRotationChanged(normalized); } catch (Exception ignored) {}
        }
    }

    private void publishLocalPreview(byte[] jpeg, int rotationHint) {
        long now = System.currentTimeMillis();
        if (now - lastPreviewAtMs < 350) return;
        lastPreviewAtMs = now;
        previewDecoder.execute(() -> {
            Bitmap bmp = decodeOriented(jpeg, rotationHint);
            if (bmp != null && running.get() && sendingEnabled) SessionBus.localVideo(bmp);
        });
    }

    private void cleanupAssemblies() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, FrameAssembly> e : assemblies.entrySet()) {
            if (now - e.getValue().createdAtMs > ASSEMBLY_MAX_AGE_MS) assemblies.remove(e.getKey(), e.getValue());
        }
        if (assemblies.size() > 8) {
            List<Map.Entry<Integer, FrameAssembly>> entries = new ArrayList<>(assemblies.entrySet());
            entries.sort(Comparator.comparingLong(x -> x.getValue().createdAtMs));
            for (int i = 0; i < entries.size() - 8; i++) assemblies.remove(entries.get(i).getKey(), entries.get(i).getValue());
        }
    }

    private void closeCameraOnly() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        h264.stopEncoder();
        session = null;
        camera = null;
        reader = null;
        captureBuilder = null;
        h264Session = false;
    }

    @Override public void close() {
        if (torchRequested) {
            torchRequested = false;
            try {
                String flashId = findFlashCamera();
                if (flashId != null) cameraManager.setTorchMode(flashId, false);
            } catch (Exception ignored) {}
        }
        running.set(false);
        sendingEnabled = false;
        cameraGeneration.incrementAndGet();
        healthLoopStarted.set(false);
        if (orientationListener != null) orientationListener.disable();
        closeCameraOnly();
        SessionBus.localVideo(null);
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
        assemblies.clear();
        h264.close();
        SessionBus.setVideoSurfaceListener(null);
        decoder.getQueue().clear();
        decoder.shutdownNow();
        previewDecoder.getQueue().clear();
        previewDecoder.shutdownNow();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static final class FrameAssembly {
        final byte[][] parts;
        final long createdAtMs = System.currentTimeMillis();
        int received = 0;

        FrameAssembly(int count) { parts = new byte[count][]; }

        synchronized void put(int i, byte[] data) {
            if (parts[i] == null) {
                parts[i] = data;
                received++;
            }
        }

        synchronized boolean complete() { return received == parts.length; }

        synchronized byte[] join() {
            int n = 0;
            for (byte[] p : parts) n += p == null ? 0 : p.length;
            byte[] out = new byte[n];
            int o = 0;
            for (byte[] p : parts) {
                if (p == null) continue;
                System.arraycopy(p, 0, out, o, p.length);
                o += p.length;
            }
            return out;
        }
    }
}
