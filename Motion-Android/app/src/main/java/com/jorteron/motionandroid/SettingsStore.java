package com.jorteron.motionandroid;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings with the v0.2 defaults. Values are read once when the service starts. */
public final class SettingsStore {
    public static final String[] RESOLUTIONS = {"320x240", "640x480", "1280x720", "1920x1080"};
    public static final int[] FPS_CHOICES = {10, 15, 24, 30, 60};

    private final SharedPreferences p;

    public SettingsStore(Context c) {
        p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public String cameraName() { return p.getString("cameraName", "Front_Door"); }
    public boolean frontCamera() { return p.getBoolean("frontCamera", false); }
    public String resolution() { return p.getString("resolution", "640x480"); }
    public int width() { return Integer.parseInt(resolution().split("x")[0]); }
    public int height() { return Integer.parseInt(resolution().split("x")[1]); }
    public int fps() { return p.getInt("fps", 30); }
    public int bitrateKbps() { return p.getInt("bitrateKbps", 1500); }
    public int threshold() { return p.getInt("threshold", 1000); }
    public int analysisFps() { return p.getInt("analysisFps", 5); }
    public int preCaptureMs() { return p.getInt("preCaptureMs", 1000); }
    public int postCaptureMs() { return p.getInt("postCaptureMs", 500); }
    public int eventGapS() { return p.getInt("eventGapS", 30); }
    public int port() { return p.getInt("port", 8081); }
    public int streamFps() { return p.getInt("streamFps", 5); }
    /** Empty = default folder (Movies/MotionAndroid). Otherwise a SAF tree URI. */
    public String folderUri() { return p.getString("folderUri", ""); }
    /** -1 = Auto (phone orientation when Start is pressed), else 0/90/180/270 clockwise on the camera picture. */
    public int rotation() { return p.getInt("rotation", -1); }
    public static final int[] ROTATIONS = {-1, 0, 90, 180, 270};
    public int maxClipMin() { return p.getInt("maxClipMin", 10); }
    public int storageLimitMb() { return p.getInt("storageLimitMb", 2048); }

    public SharedPreferences.Editor edit() { return p.edit(); }

    public static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** "Front Door" -> "Front_Door"; strips characters not allowed in file names. */
    public static String fileSafeName(String name) {
        String s = name.trim().replace(' ', '_').replaceAll("[^A-Za-z0-9_\\-]", "");
        return s.isEmpty() ? "Camera" : s;
    }
}
