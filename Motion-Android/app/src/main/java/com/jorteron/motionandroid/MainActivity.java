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
import android.widget.Button;
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

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 500); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Motion Android", 26, Ui.ACCENT));
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

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        MotionService.setPreviewWanted(true);
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        MotionService.setPreviewWanted(false);
        ui.removeCallbacks(tick);
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
