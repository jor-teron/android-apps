package com.jorteron.motionandroid;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;

/**
 * Rotation (Auto or manual) is applied on the CPU after motion detection, so the encoder,
 * watermark and stream all get the upright picture.
 * Camera2 -> ImageReader (YUV_420_888) -> CPU frame (I420 arrays) on one pipeline thread:
 *   1. motion detection on the luma (at analysis fps),
 *   2. watermark burned into the Y/U/V planes,
 *   3. frame copied into the H.264 encoder (MediaCodec, async, YUV420Flexible input image),
 *   4. JPEG for the MJPEG stream (at stream fps).
 * Encoded output goes to ClipRecorder, which owns the pre-roll ring buffer and MP4 files.
 */
public final class CameraPipeline {
    private static final String TAG = "CameraPipeline";

    public interface Callback {
        void onStatus(int motionPixels, boolean recording);
        void onClipStarted(String name);
        void onClipFinished(String name, boolean ok);
        void onError(String msg, boolean fatal);
        void onRunning(String info);
    }

    private final Context ctx;
    private final SettingsStore s;
    private final Callback cb;
    private final MjpegServer server;
    private HandlerThread thread;
    private Handler h;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private MediaCodec encoder;
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();

    private int w, h2;          // camera (sensor) frame size
    private int ow, oh;         // output (upright) frame size
    private int rotation;       // clockwise degrees applied to camera frames
    private int deviceOrient = -1; // sampled once per session (Auto)
    private boolean sampling;
    private byte[] yA, uA, vA, nv21;
    private byte[] yR, uR, vR;  // rotated planes (== yA/uA/vA when rotation is 0)
    private final MotionDetector detector = new MotionDetector();
    private Watermark watermark;
    private ClipRecorder recorder;
    private long lastAnalysisUs, lastJpegUs, lastStatusUs;
    private int lastMotion;
    private volatile boolean previewWanted;
    private volatile byte[] lastJpeg;
    private boolean closed;
    private int retries;

    public CameraPipeline(Context ctx, SettingsStore s, MjpegServer server, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.s = s;
        this.server = server;
        this.cb = cb;
    }

    public void setPreviewWanted(boolean v) { previewWanted = v; }
    public byte[] lastJpeg() { return lastJpeg; }

    public void start() {
        thread = new HandlerThread("pipeline");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(this::openAll);
    }

    public void stop() {
        if (h == null) return;
        h.post(() -> {
            closed = true;
            if (recorder != null) recorder.closeClip();
            closeCamera();
            closeEncoder();
            thread.quitSafely();
        });
    }

    // ---------------------------------------------------------------- setup

    private String pickCamera(CameraManager cm) throws CameraAccessException {
        int want = s.frontCamera() ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (f != null && f == want) return id;
        }
        return fallback;
    }

    /** Requested size if the camera offers it in YUV, else the closest offered size by area. */
    static Size chooseSize(StreamConfigurationMap map, int rw, int rh) {
        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        Size best = null;
        long target = (long) rw * rh;
        for (Size z : sizes) {
            if (z.getWidth() == rw && z.getHeight() == rh) return z;
            if (z.getWidth() > 1920 || z.getHeight() > 1080) continue;
            if (best == null || Math.abs((long) z.getWidth() * z.getHeight() - target)
                    < Math.abs((long) best.getWidth() * best.getHeight() - target)) best = z;
        }
        return best != null ? best : sizes[0];
    }

    static Range<Integer> chooseFps(CameraCharacteristics cc, int fps) {
        Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> best = null;
        if (ranges == null) return null;
        for (Range<Integer> r : ranges) {
            if (best == null) { best = r; continue; }
            int d = Math.abs(r.getUpper() - fps), bd = Math.abs(best.getUpper() - fps);
            // closest max fps; prefer fixed range (lower == upper) for steady video
            if (d < bd || (d == bd && r.getLower() > best.getLower())) best = r;
        }
        return best;
    }

    /** Auto rotation: read the accelerometer once (max 1.5 s), then continue opening. */
    private boolean sampleOrientation() {
        if (s.rotation() >= 0 || deviceOrient >= 0) return false;
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (acc == null) { deviceOrient = 0; return false; }
        if (sampling) return true;
        sampling = true;
        final SensorEventListener[] holder = new SensorEventListener[1];
        final Runnable timeout = () -> {
            if (deviceOrient >= 0) return;
            sm.unregisterListener(holder[0]);
            deviceOrient = 0;
            sampling = false;
            openAll();
        };
        holder[0] = new SensorEventListener() {
            int n;
            @Override public void onSensorChanged(SensorEvent e) {
                if (deviceOrient >= 0 || ++n < 3) return; // skip first stale readings
                float x = e.values[0], y = e.values[1];
                int o;
                if (Math.abs(x) < 2.5f && Math.abs(y) < 2.5f) o = 0; // lying flat: assume portrait
                else if (Math.abs(y) >= Math.abs(x)) o = y >= 0 ? 0 : 180;
                else o = x < 0 ? 90 : 270;          // OrientationEventListener convention
                deviceOrient = o;
                sm.unregisterListener(this);
                h.removeCallbacks(timeout);
                sampling = false;
                openAll();
            }
            @Override public void onAccuracyChanged(Sensor sensor, int a) {}
        };
        sm.registerListener(holder[0], acc, SensorManager.SENSOR_DELAY_UI, h);
        h.postDelayed(timeout, 1500);
        return true;
    }

    @SuppressLint("MissingPermission")
    private void openAll() {
        if (closed) return;
        if (sampleOrientation()) return;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cm);
            if (id == null) { cb.onError("No camera found", true); return; }
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map, s.width(), s.height());
            w = size.getWidth() & ~1;
            h2 = size.getHeight() & ~1;
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            int sensor = so != null ? so : 90;
            boolean isFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            if (s.rotation() >= 0) rotation = s.rotation();
            else rotation = isFront ? (sensor - deviceOrient + 360) % 360 : (sensor + deviceOrient) % 360;
            boolean swap = rotation == 90 || rotation == 270;
            ow = swap ? h2 : w;
            oh = swap ? w : h2;
            Range<Integer> fpsRange = chooseFps(cc, s.fps());
            int fps = fpsRange != null ? Math.min(s.fps(), fpsRange.getUpper()) : s.fps();

            yA = new byte[w * h2];
            uA = new byte[(w / 2) * (h2 / 2)];
            vA = new byte[(w / 2) * (h2 / 2)];
            nv21 = new byte[w * h2 * 3 / 2];
            if (rotation == 0) { yR = yA; uR = uA; vR = vA; }
            else { yR = new byte[yA.length]; uR = new byte[uA.length]; vR = new byte[vA.length]; }
            watermark = new Watermark(s.cameraName());
            detector.reset();

            if (recorder == null) {
                recorder = new ClipRecorder(new ClipStorage(ctx, s.folderUri(), s.storageLimitMb()), s.cameraName(),
                        s.preCaptureMs(), s.postCaptureMs(), s.eventGapS(), s.maxClipMin(), new ClipRecorder.Listener() {
                    @Override public void onClipStarted(String n) { cb.onClipStarted(n); }
                    @Override public void onClipFinished(String n, boolean ok) { cb.onClipFinished(n, ok); }
                    @Override public void requestKeyFrame() { requestSync(); }
                });
            }
            openEncoder(fps);

            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 4);
            reader.setOnImageAvailableListener(this::onImage, h);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (closed) { d.close(); return; }
                    camera = d;
                    createSession(fpsRange);
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (camera == d) camera = null;
                    scheduleRetry("Camera disconnected (in use by another app?)");
                }
                @Override public void onError(CameraDevice d, int error) {
                    d.close();
                    if (camera == d) camera = null;
                    scheduleRetry("Camera error " + error);
                }
            }, h);
            cb.onRunning(ow + "x" + oh + " (rotation " + rotation + "\u00B0" + (s.rotation() < 0 ? ", auto" : "") + ") @ " + fps + " fps, " + s.bitrateKbps() + " kbps");
        } catch (SecurityException e) {
            cb.onError("Camera permission missing", true);
        } catch (Exception e) {
            Log.e(TAG, "openAll", e);
            scheduleRetry("Camera/encoder start failed: " + e.getMessage());
        }
    }

    private void createSession(Range<Integer> fpsRange) {
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    if (closed) return;
                    session = cs;
                    try {
                        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        b.addTarget(reader.getSurface());
                        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        cs.setRepeatingRequest(b.build(), null, h);
                        retries = 0;
                    } catch (Exception e) {
                        scheduleRetry("Capture request failed: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) {
                    scheduleRetry("Camera session configuration failed");
                }
            }, h);
        } catch (Exception e) {
            scheduleRetry("Camera session failed: " + e.getMessage());
        }
    }

    private void scheduleRetry(String why) {
        if (closed) return;
        if (recorder != null) recorder.closeClip();
        closeCamera();
        closeEncoder();
        retries++;
        if (retries > 20) { cb.onError(why + " - giving up", true); return; }
        cb.onError(why + " - retrying in 5 s", false);
        h.postDelayed(this::openAll, 5000);
    }

    private void openEncoder(int fps) throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, s.bitrateKbps() * 1000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        freeInputs.clear();
        final MediaCodec enc = encoder;
        encoder.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec c, int index) {
                if (c == encoder) freeInputs.add(index);
            }
            @Override public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
                if (c != encoder) return;
                try {
                    ByteBuffer b = c.getOutputBuffer(index);
                    if (b != null) recorder.onEncoded(b, info);
                    c.releaseOutputBuffer(index, false);
                } catch (Exception e) { Log.w(TAG, "output", e); }
            }
            @Override public void onError(MediaCodec c, MediaCodec.CodecException e) {
                if (c == enc) scheduleRetry("Encoder error: " + e.getDiagnosticInfo());
            }
            @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
                if (c == encoder) recorder.setFormat(format);
            }
        }, h);
        encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
    }

    private void requestSync() {
        if (encoder == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(b);
        } catch (Exception ignored) {}
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        session = null;
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        camera = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;
    }

    private void closeEncoder() {
        MediaCodec e = encoder;
        encoder = null;
        freeInputs.clear();
        if (e != null) {
            try { e.stop(); } catch (Exception ignored) {}
            try { e.release(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- per frame

    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
        if (img == null) return;
        long now = System.nanoTime() / 1000;
        try {
            toI420(img);
        } catch (Exception e) {
            Log.w(TAG, "toI420", e);
            img.close();
            return;
        }
        img.close();

        // 1. motion detection on the clean frame (before watermark, whose clock changes every second)
        long anaInterval = 1_000_000L / Math.max(1, s.analysisFps());
        if (now - lastAnalysisUs >= anaInterval) {
            lastAnalysisUs = now;
            lastMotion = detector.process(yA, w, h2);
            if (lastMotion >= s.threshold() && recorder != null) recorder.onMotion(now);
        }

        // 2. rotate to upright, then watermark (bottom of the upright picture)
        if (rotation != 0) {
            rotatePlane(yA, w, h2, yR, rotation);
            rotatePlane(uA, w / 2, h2 / 2, uR, rotation);
            rotatePlane(vA, w / 2, h2 / 2, vR, rotation);
        }
        watermark.apply(yR, uR, vR, ow, oh);

        // 3. encoder
        if (encoder != null && !freeInputs.isEmpty()) {
            int idx = freeInputs.poll();
            try {
                Image in = encoder.getInputImage(idx);
                if (in != null) {
                    fromI420(in);
                    encoder.queueInputBuffer(idx, 0, ow * oh * 3 / 2, now, 0);
                } else {
                    ByteBuffer bb = encoder.getInputBuffer(idx);
                    bb.clear();
                    bb.put(yR);
                    for (int i = 0; i < uR.length; i++) { bb.put(uR[i]); bb.put(vR[i]); } // NV12 fallback
                    encoder.queueInputBuffer(idx, 0, ow * oh * 3 / 2, now, 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "encode", e);
            }
        }

        // 4. JPEG for stream / preview (every 2 s when nobody is watching, for /snapshot.jpg)
        long jpegInterval = (server.clientCount() > 0 || previewWanted)
                ? 1_000_000L / Math.max(1, s.streamFps()) : 2_000_000L;
        if (now - lastJpegUs >= jpegInterval) {
            lastJpegUs = now;
            byte[] j = makeJpeg();
            lastJpeg = j;
            server.publish(j);
        }

        if (now - lastStatusUs >= 500_000) {
            lastStatusUs = now;
            cb.onStatus(lastMotion, recorder != null && recorder.isRecording());
        }
    }

    private void toI420(Image img) {
        Image.Plane[] p = img.getPlanes();
        copyPlane(p[0], w, h2, yA);
        copyPlane(p[1], w / 2, h2 / 2, uA);
        copyPlane(p[2], w / 2, h2 / 2, vA);
    }

    private static void copyPlane(Image.Plane pl, int pw, int ph, byte[] dst) {
        ByteBuffer b = pl.getBuffer();
        int rs = pl.getRowStride(), ps = pl.getPixelStride();
        int cap = b.limit();
        if (ps == 1) {
            for (int y = 0; y < ph; y++) {
                b.position(y * rs);
                b.get(dst, y * pw, Math.min(pw, cap - y * rs));
            }
        } else {
            for (int y = 0; y < ph; y++) {
                int row = y * rs, o = y * pw;
                for (int x = 0; x < pw; x++) {
                    int i = row + x * ps;
                    dst[o + x] = i < cap ? b.get(i) : 0;
                }
            }
        }
    }

    private void fromI420(Image in) {
        Image.Plane[] p = in.getPlanes();
        writePlane(p[0], ow, oh, yR);
        writePlane(p[1], ow / 2, oh / 2, uR);
        writePlane(p[2], ow / 2, oh / 2, vR);
    }

    /** Rotate a sw x sh plane clockwise by deg (90/180/270) into dst. */
    static void rotatePlane(byte[] src, int sw, int sh, byte[] dst, int deg) {
        if (deg == 180) {
            int n = sw * sh;
            for (int i = 0; i < n; i++) dst[n - 1 - i] = src[i];
        } else if (deg == 90) {
            // dst is sh wide, sw tall: dst(x=sh-1-y, y=x) = src(x, y)
            for (int y = 0; y < sh; y++) {
                int row = y * sw, dx = sh - 1 - y;
                for (int x = 0; x < sw; x++) dst[x * sh + dx] = src[row + x];
            }
        } else if (deg == 270) {
            // dst(x=y, y=sw-1-x) = src(x, y)
            for (int y = 0; y < sh; y++) {
                int row = y * sw;
                for (int x = 0; x < sw; x++) dst[(sw - 1 - x) * sh + y] = src[row + x];
            }
        }
    }

    private static void writePlane(Image.Plane pl, int pw, int ph, byte[] src) {
        ByteBuffer b = pl.getBuffer();
        int rs = pl.getRowStride(), ps = pl.getPixelStride();
        int cap = b.limit();
        if (ps == 1) {
            for (int y = 0; y < ph; y++) {
                b.position(y * rs);
                b.put(src, y * pw, Math.min(pw, cap - y * rs));
            }
        } else {
            for (int y = 0; y < ph; y++) {
                int row = y * rs, o = y * pw;
                for (int x = 0; x < pw; x++) {
                    int i = row + x * ps;
                    if (i < cap) b.put(i, src[o + x]);
                }
            }
        }
    }

    private byte[] makeJpeg() {
        System.arraycopy(yR, 0, nv21, 0, yR.length);
        int o = yR.length;
        for (int i = 0; i < uR.length; i++) { nv21[o++] = vR[i]; nv21[o++] = uR[i]; }
        YuvImage yi = new YuvImage(nv21, ImageFormat.NV21, ow, oh, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(ow * oh / 4);
        yi.compressToJpeg(new Rect(0, 0, ow, oh), 70, bos);
        return bos.toByteArray();
    }
}
