package is.quietlink.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera2 preview plus an ImageReader that feeds ZXing. Decoded text goes to the
 * listener on the main thread and is never logged. Everything touching the camera
 * runs on one background thread, and stop() always releases the camera.
 */
final class QrScanner {
    interface Listener {
        void onQrText(QrScanner source, String text);
        void onQrFailed(QrScanner source);
    }

    private static final int TARGET_FRAME_AREA = 1280 * 720;
    private static final int MAX_FRAME_AREA = 1920 * 1080;
    private static final int MIN_FRAME_WIDTH = 640;
    private static final long DECODE_INTERVAL_MS = 150L;

    private final Activity activity;
    private final TextureView preview;
    private final Listener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean delivering = new AtomicBoolean(false);
    private final QRCodeReader decoder = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    private HandlerThread thread;
    private Handler handler;
    private volatile Size frameSize;

    // Camera thread only.
    private boolean opening = false;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private byte[] luminance;
    private long lastDecodeElapsedMs = 0L;

    QrScanner(Activity activity, TextureView preview, Listener listener) {
        this.activity = activity;
        this.preview = preview;
        this.listener = listener;
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }

    void start() {
        thread = new HandlerThread("QuietLink-QrScan");
        thread.start();
        handler = new Handler(thread.getLooper());
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                handler.post(() -> openCamera(texture));
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                applyPreviewTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        if (preview.isAvailable()) {
            SurfaceTexture texture = preview.getSurfaceTexture();
            handler.post(() -> openCamera(texture));
        }
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        preview.setSurfaceTextureListener(null);
        if (handler != null) handler.post(this::closeCamera);
    }

    private void openCamera(SurfaceTexture texture) {
        if (stopped.get() || opening || camera != null) return;
        try {
            if (activity.checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                fail("permission");
                return;
            }
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = chooseCamera(manager);
            if (cameraId == null) {
                fail("no_camera");
                return;
            }
            Size size = chooseFrameSize(manager.getCameraCharacteristics(cameraId));
            if (size == null) {
                fail("no_frame_size");
                return;
            }
            frameSize = size;
            activity.runOnUiThread(this::applyPreviewTransform);
            opening = true;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    opening = false;
                    if (stopped.get()) {
                        opened.close();
                        thread.quitSafely();
                        return;
                    }
                    camera = opened;
                    startCapture(texture);
                }
                @Override public void onDisconnected(CameraDevice lost) {
                    cameraLost(lost, "disconnected");
                }
                @Override public void onError(CameraDevice lost, int error) {
                    cameraLost(lost, "error");
                }
            }, handler);
        } catch (Exception e) {
            opening = false;
            QuietLog.log("UI", "qr_scan_camera_exception", "type=" + e.getClass().getSimpleName());
            fail("open");
        }
    }

    private void cameraLost(CameraDevice lost, String reason) {
        opening = false;
        if (camera == lost) camera = null;
        lost.close();
        if (stopped.get()) thread.quitSafely();
        else fail(reason);
    }

    private String chooseCamera(CameraManager manager) throws Exception {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    private Size chooseFrameSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] frames = map.getOutputSizes(ImageFormat.YUV_420_888);
        Size[] previews = map.getOutputSizes(SurfaceTexture.class);
        if (frames == null || previews == null) return null;
        Size best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Size size : frames) {
            int area = size.getWidth() * size.getHeight();
            if (area > MAX_FRAME_AREA || size.getWidth() < MIN_FRAME_WIDTH
                    || !Arrays.asList(previews).contains(size)) {
                continue;
            }
            int distance = Math.abs(area - TARGET_FRAME_AREA);
            if (distance < bestDistance) {
                best = size;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void startCapture(SurfaceTexture texture) {
        try {
            Size size = frameSize;
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(texture);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, handler);
            CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(reader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (stopped.get() || camera == null) {
                                configured.close();
                                return;
                            }
                            session = configured;
                            try {
                                configured.setRepeatingRequest(request.build(), null, handler);
                            } catch (Exception e) {
                                QuietLog.log("UI", "qr_scan_camera_exception",
                                        "type=" + e.getClass().getSimpleName());
                                fail("repeat");
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession failed) {
                            fail("configure");
                        }
                    }, handler);
        } catch (Exception e) {
            QuietLog.log("UI", "qr_scan_camera_exception", "type=" + e.getClass().getSimpleName());
            fail("capture");
        }
    }

    private void onFrame(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null || stopped.get() || delivering.get()) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastDecodeElapsedMs < DECODE_INTERVAL_MS) return;
            lastDecodeElapsedMs = now;
            String text = decode(image);
            if (text == null) return;
            delivering.set(true);
            activity.runOnUiThread(() -> {
                try {
                    if (!stopped.get()) listener.onQrText(this, text);
                } finally {
                    delivering.set(false);
                }
            });
        } catch (IllegalStateException ignored) {
            // The reader closed or ran out of images. The next frame tries again.
        }
    }

    private String decode(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        if (luminance == null || luminance.length != width * height) luminance = new byte[width * height];
        for (int row = 0; row < height; row++) {
            buffer.position(row * rowStride);
            buffer.get(luminance, row * width, width);
        }
        PlanarYUVLuminanceSource frame = new PlanarYUVLuminanceSource(
                luminance, width, height, 0, 0, width, height, false);
        try {
            return decoder.decode(new BinaryBitmap(new HybridBinarizer(frame)), hints).getText();
        } catch (ReaderException | RuntimeException e) {
            return null;
        } finally {
            decoder.reset();
        }
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {}
        session = null;
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {}
        camera = null;
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {}
        reader = null;
        if (previewSurface != null) previewSurface.release();
        previewSurface = null;
        luminance = null;
        // A camera still opening must be closed by its callback, which needs this thread.
        if (!opening) thread.quitSafely();
        QuietLog.log("UI", "qr_scan_camera_closed");
    }

    private void fail(String reason) {
        QuietLog.log("UI", "qr_scan_failed", "reason=" + reason);
        activity.runOnUiThread(() -> {
            if (!stopped.get()) listener.onQrFailed(this);
        });
    }

    // A TextureView stretches the camera frame to its own shape, so this crops to fill instead.
    private void applyPreviewTransform() {
        Size size = frameSize;
        int viewWidth = preview.getWidth();
        int viewHeight = preview.getHeight();
        if (size == null || viewWidth == 0 || viewHeight == 0) return;
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;
        float longSide = Math.max(size.getWidth(), size.getHeight());
        float shortSide = Math.min(size.getWidth(), size.getHeight());
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, shortSide, longSide);
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(viewHeight / shortSide, viewWidth / longSide);
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else {
            float scale = Math.max(viewWidth / shortSide, viewHeight / longSide);
            matrix.setScale(shortSide * scale / viewWidth, longSide * scale / viewHeight, centerX, centerY);
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180, centerX, centerY);
        }
        preview.setTransform(matrix);
    }
}
