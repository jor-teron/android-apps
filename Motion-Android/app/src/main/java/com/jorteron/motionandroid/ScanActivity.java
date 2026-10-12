package com.jorteron.motionandroid;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** Minimal QR scanner: Camera2 back camera + ZXing core (no Play Services). Returns "qr" extra. */
public class ScanActivity extends Activity {
    private static final int W = 1280, H = 720;
    private TextureView tv;
    private TextView msg;
    private HandlerThread thread;
    private Handler h;
    private CameraDevice cam;
    private CameraCaptureSession session;
    private ImageReader reader;
    private final MultiFormatReader zx = new MultiFormatReader();
    private volatile boolean done;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        zx.setHints(hints);
        LinearLayout col = Ui.column(this);
        msg = Ui.text(this, "Point at the QR code shown on the Remote Cam phone", 16, Ui.TEXT);
        col.addView(msg);
        tv = new TextureView(this);
        FrameLayout fl = new FrameLayout(this);
        fl.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        col.addView(fl, new LinearLayout.LayoutParams(-1, Ui.dp(this, 480)));
        setContentView(col);
    }

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
            return;
        }
        begin();
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) begin();
        else msg.setText("Camera permission is needed to scan.");
    }

    private void begin() {
        if (thread != null) return;
        if (MotionService.running) {
            msg.setText("Press Stop first: the camera is busy watching for motion. Or enter the address by hand.");
            return;
        }
        thread = new HandlerThread("scan");
        thread.start();
        h = new Handler(thread.getLooper());
        if (tv.isAvailable()) open();
        else tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) { open(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
    }

    private void open() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = cm.getCameraIdList()[0];
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, h);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { cam = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) {
                    d.close();
                    runOnUiThread(() -> msg.setText("Camera error " + e + ". Enter the address by hand instead."));
                }
            }, h);
        } catch (Exception e) {
            msg.setText("Cannot open camera: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void startSession() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            st.setDefaultBufferSize(W, H);
            Surface prev = new Surface(st);
            CaptureRequest.Builder rb = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(prev);
            rb.addTarget(reader.getSurface());
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            cam.createCaptureSession(Arrays.asList(prev, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try { s.setRepeatingRequest(rb.build(), null, h); } catch (Exception ignored) {}
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    runOnUiThread(() -> msg.setText("Camera setup failed."));
                }
            }, h);
        } catch (Exception e) {
            runOnUiThread(() -> msg.setText("Camera setup failed: " + e.getMessage()));
        }
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            if (done) return;
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int stride = y.getRowStride(), w = img.getWidth(), hh = img.getHeight();
            byte[] data = new byte[stride * hh];
            buf.get(data, 0, Math.min(buf.remaining(), data.length));
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, stride, hh, 0, 0, w, hh, false);
            Result res;
            try { res = zx.decodeWithState(new BinaryBitmap(new HybridBinarizer(src))); }
            catch (Exception e) { return; }
            finally { zx.reset(); }
            String text = res.getText();
            if (CamStore.parseQr(text) == null) {
                runOnUiThread(() -> msg.setText("That QR code is not a Motion Android Remote Cam."));
                return;
            }
            done = true;
            runOnUiThread(() -> { setResult(RESULT_OK, new Intent().putExtra("qr", text)); finish(); });
        } finally {
            img.close();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (cam != null) cam.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null; cam = null; reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
    }
}
