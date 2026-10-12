package com.jorteron.motionandroid;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.widget.Button;
import android.view.View;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private TextView status, url, clip, infoView, errorView;
    private Button startStop;
    private ImageView preview;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private byte[] shownJpeg;
    private TextView modeView, pairInfo, remoteStats;
    private Button modeBtn;
    private ImageView qr;
    private LinearLayout remoteSection, mainSection, camList;
    private String qrShownFor = "";
    private final Map<String, ImageView> camViews = new HashMap<>();
    private final Map<String, TextView> camStatus = new HashMap<>();
    private volatile boolean liveRunning;
    private Thread liveThread;

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 500); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Motion Android", 26, Ui.ACCENT));
        col.addView(Ui.text(this, "v0.3", 13, Ui.DIM));
        modeView = Ui.text(this, "", 16, Ui.TEXT);
        col.addView(modeView);
        modeBtn = Ui.button(this, "", 0xFF4FC3F7);
        modeBtn.setOnClickListener(v -> switchMode());
        col.addView(modeBtn);
        status = Ui.text(this, "", 20, Ui.TEXT);
        url = Ui.text(this, "", 18, Ui.TEXT);
        url.setTextIsSelectable(true);
        infoView = Ui.text(this, "", 14, Ui.DIM);
        clip = Ui.text(this, "", 14, Ui.DIM);
        errorView = Ui.text(this, "", 14, 0xFFFF5252);
        col.addView(status);
        col.addView(url);
        col.addView(infoView);
        col.addView(clip);
        col.addView(errorView);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 220));
        lp.topMargin = Ui.dp(this, 8);
        preview.setLayoutParams(lp);
        preview.setBackgroundColor(Ui.CARD);
        col.addView(preview);

        startStop = Ui.button(this, "Start", Ui.ACCENT);
        startStop.setOnClickListener(v -> toggle());
        col.addView(startStop);
        Button settings = Ui.button(this, "Settings", 0xFFBDBDBD);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        col.addView(settings);
        col.addView(Ui.text(this, "Open the stream URL in any browser on the same Wi-Fi. "
                + "Keeps running with the screen off.", 13, Ui.DIM));

        // Remote Cam mode: pairing QR
        remoteSection = new LinearLayout(this);
        remoteSection.setOrientation(LinearLayout.VERTICAL);
        remoteSection.addView(Ui.text(this, "Pair with Main", 20, Ui.ACCENT));
        remoteSection.addView(Ui.text(this, "On the Main phone tap Add Remote Cam and scan this code.", 13, Ui.DIM));
        qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        qr.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 260), Ui.dp(this, 260)));
        remoteSection.addView(qr);
        pairInfo = Ui.text(this, "", 15, Ui.TEXT);
        pairInfo.setTextIsSelectable(true);
        remoteSection.addView(pairInfo);
        remoteStats = Ui.text(this, "", 14, Ui.DIM);
        remoteSection.addView(remoteStats);
        Button newCode = Ui.button(this, "New pairing code (Main must pair again)", 0xFFBDBDBD);
        newCode.setOnClickListener(v -> {
            if (MotionService.running) { errorView.setText("Press Stop before changing the pairing code."); return; }
            new SettingsStore(this).newPairToken();
            qrShownFor = "";
        });
        remoteSection.addView(newCode);
        col.addView(remoteSection);

        // Main mode: paired Remote Cams
        mainSection = new LinearLayout(this);
        mainSection.setOrientation(LinearLayout.VERTICAL);
        mainSection.addView(Ui.text(this, "Remote Cams", 20, Ui.ACCENT));
        mainSection.addView(Ui.text(this, "Clips are fetched while Main is started, into a sub folder per camera.", 13, Ui.DIM));
        Button add = Ui.button(this, "Add Remote Cam", 0xFF4FC3F7);
        add.setOnClickListener(v -> startActivity(new Intent(this, AddCamActivity.class)));
        mainSection.addView(add);
        camList = new LinearLayout(this);
        camList.setOrientation(LinearLayout.VERTICAL);
        mainSection.addView(camList);
        col.addView(mainSection);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        MotionService.setPreviewWanted(true);
        buildCams();
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        MotionService.setPreviewWanted(false);
        ui.removeCallbacks(tick);
        stopLive();
    }

    private void switchMode() {
        SettingsStore s = new SettingsStore(this);
        if (MotionService.running) stopService(new Intent(this, MotionService.class));
        s.edit().putString("mode", s.remoteMode() ? "main" : "remote").apply();
        qrShownFor = "";
        buildCams();
        ui.postDelayed(this::refresh, 300);
    }

    private void buildCams() {
        stopLive();
        camList.removeAllViews();
        camViews.clear();
        camStatus.clear();
        if (new SettingsStore(this).remoteMode()) return;
        List<CamStore.Cam> cams = new CamStore(this).list();
        if (cams.isEmpty()) camList.addView(Ui.text(this, "No Remote Cams yet.", 14, Ui.DIM));
        for (CamStore.Cam c : cams) {
            camList.addView(Ui.text(this, c.name, 17, Ui.TEXT));
            TextView st = Ui.text(this, "", 13, Ui.DIM);
            camList.addView(st);
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setBackgroundColor(Ui.CARD);
            iv.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 200)));
            camList.addView(iv);
            Button rm = Ui.button(this, "Remove " + c.name, 0xFFBDBDBD);
            rm.setOnClickListener(v -> { new CamStore(this).remove(c.id); buildCams(); });
            camList.addView(rm);
            camViews.put(c.id, iv);
            camStatus.put(c.id, st);
        }
        if (!cams.isEmpty()) startLive(cams);
    }

    /** Live view: pulls snapshots from each paired cam about twice a second while this screen is visible. */
    private void startLive(List<CamStore.Cam> cams) {
        liveRunning = true;
        liveThread = new Thread(() -> {
            Map<String, String> bases = new HashMap<>();
            while (liveRunning) {
                for (CamStore.Cam c : cams) {
                    if (!liveRunning) return;
                    String b = bases.get(c.id);
                    if (b == null) {
                        b = RemotePuller.resolve(c);
                        if (b == null) { show(c.id, null, "Live view: offline"); continue; }
                        bases.put(c.id, b);
                    }
                    try {
                        HttpURLConnection h = (HttpURLConnection) new URL(b + "/snapshot.jpg?token=" + c.token).openConnection();
                        h.setConnectTimeout(2500);
                        h.setReadTimeout(5000);
                        Bitmap bm;
                        try (InputStream in = h.getInputStream()) { bm = BitmapFactory.decodeStream(in); }
                        finally { h.disconnect(); }
                        show(c.id, bm, "Live from " + c.lastHost);
                    } catch (Exception e) {
                        bases.remove(c.id);
                        show(c.id, null, "Live view: offline (is the Remote Cam started?)");
                    }
                }
                try { Thread.sleep(400); } catch (InterruptedException e) { return; }
            }
        }, "live-view");
        liveThread.setDaemon(true);
        liveThread.start();
    }

    private void show(String id, Bitmap bm, String text) {
        ui.post(() -> {
            ImageView iv = camViews.get(id);
            TextView st = camStatus.get(id);
            if (iv != null && bm != null) iv.setImageBitmap(bm);
            if (st != null) {
                String p = RemotePuller.status.get(id);
                st.setText(text + (p != null && MotionService.running ? "\nClips: " + p
                        : "\nClips: press Start to receive clips"));
            }
        });
    }

    private void stopLive() {
        liveRunning = false;
        if (liveThread != null) liveThread.interrupt();
        liveThread = null;
    }

    private void showQr(SettingsStore s) {
        List<String> ips = MotionService.allIps();
        String payload = CamStore.qrPayload(s.cameraName(), s.port(), s.pairToken(), ips);
        if (payload.equals(qrShownFor)) return;
        qrShownFor = payload;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512, hints);
            int w = m.getWidth(), hh = m.getHeight();
            int[] px = new int[w * hh];
            for (int y = 0; y < hh; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            qr.setImageBitmap(Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888));
        } catch (Exception e) {
            qr.setImageDrawable(null);
        }
        pairInfo.setText("Name: " + s.cameraName() + "\nIP: " + (ips.isEmpty() ? "(no network)" : String.join(", ", ips))
                + "\nPort: " + s.port() + "\nPairing code: " + s.pairToken());
    }

    private void toggle() {
        if (MotionService.running) {
            stopService(new Intent(this, MotionService.class));
            ui.postDelayed(this::refresh, 300);
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
            return;
        }
        startForegroundService(new Intent(this, MotionService.class));
        ui.postDelayed(() -> MotionService.setPreviewWanted(true), 1000);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            toggle();
        } else {
            errorView.setText("Camera permission is needed to watch for motion.");
        }
    }

    private void refresh() {
        boolean run = MotionService.running;
        SettingsStore ms = new SettingsStore(this);
        boolean remote = ms.remoteMode();
        modeView.setText("Mode: " + (remote ? "Remote Cam" : "Main"));
        modeBtn.setText(remote ? "Switch to Main mode" : "Switch to Remote Cam mode");
        remoteSection.setVisibility(remote ? View.VISIBLE : View.GONE);
        mainSection.setVisibility(remote ? View.GONE : View.VISIBLE);
        if (remote) {
            showQr(ms);
            long ago = RemoteCamServer.lastMainContact;
            remoteStats.setText(run ? "Clips waiting for Main: " + RemoteCamServer.queued + "  ·  sent, awaiting delete: "
                    + RemoteCamServer.sent + (ago > 0 ? "\nMain last connected " + (System.currentTimeMillis() - ago) / 1000 + " s ago" : "\nMain not connected yet")
                    : "Press Start so Main can connect.");
        }
        startStop.setText(run ? "Stop" : "Start");
        String st = MotionService.state;
        status.setText(run ? st + "  ·  motion pixels: " + MotionService.motionPixels : "Idle");
        status.setTextColor("Recording".equals(st) ? 0xFFFF5252 : Ui.TEXT);
        SettingsStore s = new SettingsStore(this);
        if (!run) MotionService.port = s.port();
        url.setText("Stream: " + MotionService.streamUrl(this));
        infoView.setText(run ? MotionService.info : "Camera: " + s.cameraName() + ", " + s.resolution() + " @ " + s.fps() + " fps");
        clip.setText(MotionService.lastClip.isEmpty() ? "Last clip: none yet" : "Last clip: " + MotionService.lastClip);
        errorView.setText(MotionService.error);
        byte[] j = run ? MotionService.previewJpeg() : null;
        if (j != shownJpeg) {
            shownJpeg = j;
            if (j == null) preview.setImageDrawable(null);
            else {
                Bitmap bm = BitmapFactory.decodeByteArray(j, 0, j.length);
                preview.setImageBitmap(bm);
            }
        }
    }
}
