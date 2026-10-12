package com.jorteron.motionandroid;

import android.app.Activity;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Range;
import android.util.Size;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final int PICK_FOLDER = 7;

    private SettingsStore s;
    private EditText name, bitrate, threshold, anaFps, pre, post, gap, port, limit, maxClip, keepAfter;
    private CheckBox front;
    private Spinner res, fps, rot;
    private List<String> resValues = new ArrayList<>();
    private TextView folderView;
    private String folderUri;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        s = new SettingsStore(this);
        folderUri = s.folderUri();
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Settings", 24, Ui.ACCENT));
        col.addView(Ui.text(this, "Changes apply the next time you press Start.", 13, Ui.DIM));

        name = field(col, "Camera name (watermark and file name)", s.cameraName(), false);
        front = new CheckBox(this);
        front.setText("Use front camera");
        front.setTextColor(Ui.TEXT);
        front.setChecked(s.frontCamera());
        front.setOnCheckedChangeListener((v, c) -> fillResolutions(c));
        col.addView(front);

        col.addView(Ui.text(this, "Resolution", 14, Ui.DIM));
        res = new Spinner(this);
        col.addView(res);
        fillResolutions(s.frontCamera());

        col.addView(Ui.text(this, "Frame rate (unsupported rates fall back to the closest the camera can do)", 14, Ui.DIM));
        fps = new Spinner(this);
        List<String> fl = new ArrayList<>();
        int maxFps = cameraMaxFps(s.frontCamera());
        int sel = 3;
        for (int i = 0; i < SettingsStore.FPS_CHOICES.length; i++) {
            int f = SettingsStore.FPS_CHOICES[i];
            fl.add(f + " fps" + (maxFps > 0 && f > maxFps ? "  (not supported, uses " + maxFps + ")" : ""));
            if (f == s.fps()) sel = i;
        }
        fps.setAdapter(adapter(fl));
        fps.setSelection(sel);
        col.addView(fps);

        col.addView(Ui.text(this, "Rotation (Auto = how the phone is held when you press Start, kept until Stop)", 14, Ui.DIM));
        rot = new Spinner(this);
        List<String> rl = new ArrayList<>();
        int rsel = 0;
        for (int i = 0; i < SettingsStore.ROTATIONS.length; i++) {
            int r = SettingsStore.ROTATIONS[i];
            rl.add(r < 0 ? "Auto" : r + "\u00B0" + (r == 90 ? "  (portrait, back camera)" : r == 0 ? "  (camera native, landscape)" : ""));
            if (r == s.rotation()) rsel = i;
        }
        rot.setAdapter(adapter(rl));
        rot.setSelection(rsel);
        col.addView(rot);

        bitrate = field(col, "Bitrate, kbps (500-8000)", String.valueOf(s.bitrateKbps()), true);
        threshold = field(col, "Motion threshold, changed pixels on the 320x240 analysis frame", String.valueOf(s.threshold()), true);
        anaFps = field(col, "Motion analysis rate, frames per second (1-15)", String.valueOf(s.analysisFps()), true);
        pre = field(col, "Pre-capture, ms (0-10000)", String.valueOf(s.preCaptureMs()), true);
        post = field(col, "Post-capture, ms (0-30000)", String.valueOf(s.postCaptureMs()), true);
        gap = field(col, "Event gap, seconds (0-300)", String.valueOf(s.eventGapS()), true);
        maxClip = field(col, "Maximum clip length, minutes (1-120; a new clip starts after this)", String.valueOf(s.maxClipMin()), true);
        port = field(col, "Stream port (1024-65535)", String.valueOf(s.port()), true);
        limit = field(col, "Storage limit, MB (oldest clips deleted beyond this)", String.valueOf(s.storageLimitMb()), true);

        keepAfter = field(col, "Remote Cam: delete a clip this many minutes after Main confirmed it (0-1440; leave empty or -1 = Never delete)",
                String.valueOf(s.keepAfterSendMin()), false);
        keepAfter.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        Button never = Ui.button(this, "Remote Cam: Never delete sent clips", 0xFFBDBDBD);
        never.setOnClickListener(v -> keepAfter.setText("-1"));
        col.addView(never);

        col.addView(Ui.text(this, "Save folder (Main mode; clips from Remote Cams go in a sub folder per camera name)", 14, Ui.DIM));
        folderView = Ui.text(this, "", 15, Ui.TEXT);
        col.addView(folderView);
        showFolder();
        Button pick = Ui.button(this, "Pick folder", 0xFFBDBDBD);
        pick.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER));
        col.addView(pick);
        Button def = Ui.button(this, "Use default folder", 0xFFBDBDBD);
        def.setOnClickListener(v -> { folderUri = ""; showFolder(); });
        col.addView(def);

        Button batt = Ui.button(this, "Battery optimisation: allow background running", 0xFFBDBDBD);
        batt.setOnClickListener(v -> openBattery());
        col.addView(batt);

        Button save = Ui.button(this, "Save", Ui.ACCENT);
        save.setOnClickListener(v -> { save(); finish(); });
        col.addView(save);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col);
        setContentView(sv);
    }

    private ArrayAdapter<String> adapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private EditText field(LinearLayout col, String label, String value, boolean number) {
        col.addView(Ui.text(this, label, 14, Ui.DIM));
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(e);
        return e;
    }

    private CameraCharacteristics chars(boolean frontCam) {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            int want = frontCam ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            CameraCharacteristics first = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                if (first == null) first = cc;
                Integer f = cc.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) return cc;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private int cameraMaxFps(boolean frontCam) {
        CameraCharacteristics cc = chars(frontCam);
        if (cc == null) return 0;
        Range<Integer>[] r = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int m = 0;
        if (r != null) for (Range<Integer> x : r) m = Math.max(m, x.getUpper());
        return m;
    }

    private void fillResolutions(boolean frontCam) {
        resValues = new ArrayList<>();
        CameraCharacteristics cc = chars(frontCam);
        Size[] sizes = null;
        if (cc != null) {
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
        }
        MediaCodecInfo.VideoCapabilities vc = encoderCaps();
        for (String r : SettingsStore.RESOLUTIONS) {
            int w = Integer.parseInt(r.split("x")[0]), h = Integer.parseInt(r.split("x")[1]);
            boolean camOk = sizes == null;
            if (sizes != null) for (Size z : sizes) if (z.getWidth() == w && z.getHeight() == h) camOk = true;
            boolean encOk = vc == null || vc.isSizeSupported(w, h);
            if (camOk && encOk) resValues.add(r);
        }
        if (resValues.isEmpty()) resValues.add("640x480");
        res.setAdapter(adapter(resValues));
        int i = resValues.indexOf(s.resolution());
        res.setSelection(i >= 0 ? i : Math.max(0, resValues.indexOf("640x480")));
    }

    private static MediaCodecInfo.VideoCapabilities encoderCaps() {
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC))
                        return ci.getCapabilitiesForType(t).getVideoCapabilities();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void showFolder() {
        folderView.setText(ClipStorage.describe(this, folderUri) + (folderUri.isEmpty() ? "  (default)" : ""));
    }

    private void openBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        try {
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == PICK_FOLDER && result == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                folderUri = u.toString();
                showFolder();
            } catch (Exception e) {
                folderView.setText("Could not keep access to that folder");
            }
        }
    }

    private int num(EditText e, int def, int lo, int hi) {
        try { return SettingsStore.clamp(Integer.parseInt(e.getText().toString().trim()), lo, hi); }
        catch (Exception x) { return def; }
    }

    private int keepValue() {
        String t = keepAfter.getText().toString().trim();
        if (t.isEmpty()) return -1;
        try { int v = Integer.parseInt(t); return v < 0 ? -1 : Math.min(v, 1440); } catch (Exception e) { return 5; }
    }

    private void save() {
        String n = name.getText().toString().trim();
        s.edit()
                .putString("cameraName", n.isEmpty() ? "Front_Door" : n)
                .putBoolean("frontCamera", front.isChecked())
                .putString("resolution", resValues.get(Math.max(0, res.getSelectedItemPosition())))
                .putInt("fps", SettingsStore.FPS_CHOICES[Math.max(0, fps.getSelectedItemPosition())])
                .putInt("bitrateKbps", num(bitrate, 1500, 500, 8000))
                .putInt("threshold", num(threshold, 1000, 1, 76800))
                .putInt("analysisFps", num(anaFps, 5, 1, 15))
                .putInt("preCaptureMs", num(pre, 1000, 0, 10000))
                .putInt("postCaptureMs", num(post, 500, 0, 30000))
                .putInt("eventGapS", num(gap, 30, 0, 300))
                .putInt("rotation", SettingsStore.ROTATIONS[Math.max(0, rot.getSelectedItemPosition())])
                .putInt("maxClipMin", num(maxClip, 10, 1, 120))
                .putInt("port", num(port, 8081, 1024, 65535))
                .putInt("storageLimitMb", num(limit, 2048, 50, 1_000_000))
                .putInt("keepAfterSendMin", keepValue())
                .putString("folderUri", folderUri)
                .apply();
    }
}
