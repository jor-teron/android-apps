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

    private int w, h2;
    private byte[] yA, uA, vA, nv21;
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

    @SuppressLint("MissingPermission")
    private void openAll() {
        if (closed) return;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cm);
            if (id == null) { cb.onError("No camera found", true); return; }
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map, s.width(), s.height());
            w = size.getWidth() & ~1;
            h2 = size.getHeight() & ~1;
            Range<Integer> fpsRange = chooseFps(cc, s.fps());
            int fps = fpsRange != null ? Math.min(s.fps(), fpsRange.getUpper()) : s.fps();

            yA = new byte[w * h2];
            uA = new byte[(w / 2) * (h2 / 2)];
            vA = new byte[(w / 2) * (h2 / 2)];
            nv21 = new byte[w * h2 * 3 / 2];
            watermark = new Watermark(s.cameraName());
            detector.reset();

            if (recorder == null) {
                recorder = new ClipRecorder(new ClipStorage(ctx, s.folderUri(), s.storageLimitMb()), s.cameraName(),
                        s.preCaptureMs(), s.postCaptureMs(), s.eventGapS(), new ClipRecorder.Listener() {
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
            cb.onRunning(w + "x" + h2 + " @ " + fps + " fps, " + s.bitrateKbps() + " kbps");
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
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h2);
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

        // 2. watermark
        watermark.apply(yA, uA, vA, w, h2);

        // 3. encoder
        if (encoder != null && !freeInputs.isEmpty()) {
            int idx = freeInputs.poll();
            try {
                Image in = encoder.getInputImage(idx);
                if (in != null) {
                    fromI420(in);
                    encoder.queueInputBuffer(idx, 0, w * h2 * 3 / 2, now, 0);
                } else {
                    ByteBuffer bb = encoder.getInputBuffer(idx);
                    bb.clear();
                    bb.put(yA);
                    for (int i = 0; i < uA.length; i++) { bb.put(uA[i]); bb.put(vA[i]); } // NV12 fallback
                    encoder.queueInputBuffer(idx, 0, w * h2 * 3 / 2, now, 0);
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
        writePlane(p[0], w, h2, yA);
        writePlane(p[1], w / 2, h2 / 2, uA);
        writePlane(p[2], w / 2, h2 / 2, vA);
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
        System.arraycopy(yA, 0, nv21, 0, yA.length);
        int o = yA.length;
        for (int i = 0; i < uA.length; i++) { nv21[o++] = vA[i]; nv21[o++] = uA[i]; }
        YuvImage yi = new YuvImage(nv21, ImageFormat.NV21, w, h2, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h2 / 4);
        yi.compressToJpeg(new Rect(0, 0, w, h2), 70, bos);
        return bos.toByteArray();
    }
}
