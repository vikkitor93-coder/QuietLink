package is.quietlink.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/** Draws QR text as black on white with the standard 4-module quiet zone, so it scans on a dark theme. */
final class QrCodeBitmap {
    private static final int QUIET_ZONE_MODULES = 4;

    private QrCodeBitmap() {}

    /** Returns the bitmap, or null if it cannot be drawn. The code stays readable as text either way. */
    static Bitmap encode(String payload, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
            BitMatrix matrix = new QRCodeWriter().encode(
                    payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (WriterException | RuntimeException e) {
            QuietLog.log("UI", "qr_encode_failed", "type=" + e.getClass().getSimpleName());
            return null;
        }
    }
}
