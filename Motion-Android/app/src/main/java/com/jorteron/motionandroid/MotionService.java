package com.jorteron.motionandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Foreground service (type camera) that owns the camera pipeline and the MJPEG server. */
public class MotionService extends Service {
    public static final String ACTION_STOP = "com.jorteron.motionandroid.STOP";
    private static final String CHANNEL = "motion";
    private static final int NOTIF_ID = 1;

    // Simple in-process status read by MainActivity.
    public static volatile boolean running;
    public static volatile String state = "Idle";
    public static volatile int motionPixels;
    public static volatile String lastClip = "";
    public static volatile String info = "";
    public static volatile String error = "";
    public static volatile int port = 8081;
    private static volatile CameraPipeline pipelineRef;

    private CameraPipeline pipeline;
    private MjpegServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String notifText = "";

    public static void setPreviewWanted(boolean v) {
        CameraPipeline p = pipelineRef;
        if (p != null) p.setPreviewWanted(v);
    }

    public static byte[] previewJpeg() {
        CameraPipeline p = pipelineRef;
        return p != null ? p.lastJpeg() : null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (pipeline != null) return START_NOT_STICKY;
        createChannel();
        Notification n = buildNotification("Starting...");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, n);
        }

        SettingsStore s = new SettingsStore(this);
        port = s.port();
        error = "";
        lastClip = "";
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotionAndroid:watch");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MotionAndroid:stream");
            wifiLock.acquire();
        }

        server = new MjpegServer(s.port());
        try {
            server.start();
        } catch (Exception e) {
            error = "Stream port " + s.port() + " unavailable: " + e.getMessage();
        }

        running = true;
        state = "Watching";
        pipeline = new CameraPipeline(this, s, server, new CameraPipeline.Callback() {
            @Override public void onStatus(int px, boolean rec) {
                motionPixels = px;
                String st = rec ? "Recording" : "Watching";
                if (!st.equals(state)) { state = st; updateNotification(); }
            }
            @Override public void onClipStarted(String name) {
                lastClip = name; state = "Recording"; updateNotification();
            }
            @Override public void onClipFinished(String name, boolean ok) {
                if (ok) lastClip = name; else error = "Could not save " + name;
                state = "Watching"; updateNotification();
            }
            @Override public void onError(String msg, boolean fatal) {
                error = msg;
                updateNotification();
                if (fatal) stopSelf();
            }
            @Override public void onRunning(String i) { info = i; error = ""; updateNotification(); }
        });
        pipelineRef = pipeline;
        pipeline.start();
        updateNotification();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        state = "Idle";
        motionPixels = 0;
        pipelineRef = null;
        if (pipeline != null) pipeline.stop();
        if (server != null) server.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    private void updateNotification() {
        String t = state + " - " + streamUrl(this);
        if (!error.isEmpty()) t = error;
        if (t.equals(notifText)) return;
        notifText = t;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(t));
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Motion watching", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MotionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Motion Android")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    /** http://<wifi-ip>:<port>, preferring wlan interfaces. */
    public static String streamUrl(Context c) {
        String ip = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (ni.getName().startsWith("wlan")) { ip = a.getHostAddress(); break; }
                    if (ip == null && a.isSiteLocalAddress()) ip = a.getHostAddress();
                }
                if (ip != null && ni.getName().startsWith("wlan")) break;
            }
        } catch (Exception ignored) {}
        return ip == null ? "(no Wi-Fi) port " + port : "http://" + ip + ":" + port;
    }
}
