package com.jorteron.motionandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remote Cam side of the link. Serves (all behind the pairing token):
 *   /api/info              "MotionAndroid\t<camera name>\t<version>"
 *   /api/clips             one line per complete clip: name \t size \t sha256
 *   /api/clip?name=        the clip bytes
 *   /api/ack?name=&sha=    Main confirms it has the complete file; deletion is then scheduled
 * Clips are only deleted N minutes after a confirmed ack; unsent clips are never deleted here.
 */
public final class RemoteCamServer implements MjpegServer.Api {
    private static final String TAG = "RemoteCam";
    private final File dir;
    private final String camName;
    private final SharedPreferences acks; // name -> ack time (ms)
    private final Map<String, String[]> hashCache = new HashMap<>(); // name -> {size, mtime, sha}
    public static volatile int queued, sent;
    public static volatile long lastMainContact;

    public RemoteCamServer(Context c, String camName) {
        this.dir = ClipStorage.remoteDir(c);
        this.camName = camName;
        this.acks = c.getSharedPreferences("remote_acks", Context.MODE_PRIVATE);
        dir.mkdirs();
    }

    /** True if the storage limit must not delete this clip (not yet confirmed by Main). */
    public boolean isProtected(String name) { return !acks.contains(name); }

    private File[] clips() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".mp4"));
        if (fs == null) return new File[0];
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return fs;
    }

    private synchronized String sha(File f) throws Exception {
        String[] c = hashCache.get(f.getName());
        if (c != null && c[0].equals(String.valueOf(f.length())) && c[1].equals(String.valueOf(f.lastModified()))) return c[2];
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        String h = hex(md.digest());
        hashCache.put(f.getName(), new String[]{String.valueOf(f.length()), String.valueOf(f.lastModified()), h});
        return h;
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private File find(String name) {
        if (name == null || name.contains("/") || name.contains("..") || !name.endsWith(".mp4")) return null;
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    @Override
    public boolean handle(String path, Map<String, String> q, OutputStream out) throws Exception {
        lastMainContact = System.currentTimeMillis();
        switch (path) {
            case "/api/info":
                MjpegServer.sendText(out, 200, "OK", "MotionAndroid\t" + camName + "\t0.3\n");
                return true;
            case "/api/clips": {
                StringBuilder sb = new StringBuilder();
                for (File f : clips()) {
                    if (acks.contains(f.getName())) continue;
                    sb.append(f.getName()).append('\t').append(f.length()).append('\t').append(sha(f)).append('\n');
                }
                MjpegServer.sendText(out, 200, "OK", sb.toString());
                return true;
            }
            case "/api/clip": {
                File f = find(q.get("name"));
                if (f == null) { MjpegServer.sendText(out, 404, "Not Found", "no such clip\n"); return true; }
                long len = f.length();
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: " + len
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    long left = len;
                    int n;
                    while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) { out.write(buf, 0, n); left -= n; }
                }
                out.flush();
                return true;
            }
            case "/api/ack": {
                String name = q.get("name");
                File f = find(name);
                if (f == null) {
                    // Already deleted or acked earlier: fine for Main.
                    MjpegServer.sendText(out, 200, "OK", acks.contains(String.valueOf(name)) ? "OK\n" : "GONE\n");
                    return true;
                }
                if (!sha(f).equalsIgnoreCase(String.valueOf(q.get("sha")))) {
                    MjpegServer.sendText(out, 409, "Conflict", "hash mismatch\n");
                    return true;
                }
                if (!acks.contains(name)) acks.edit().putLong(name, System.currentTimeMillis()).apply();
                MjpegServer.sendText(out, 200, "OK", "OK\n");
                return true;
            }
        }
        return false;
    }

    /** Deletes clips confirmed by Main more than keepMin minutes ago (keepMin < 0 = never). Updates counters. */
    public void sweep(int keepMin) {
        try {
            long now = System.currentTimeMillis();
            int qd = 0, sd = 0;
            List<String> names = new ArrayList<>();
            for (File f : clips()) {
                names.add(f.getName());
                if (!acks.contains(f.getName())) { qd++; continue; }
                sd++;
                if (keepMin >= 0 && now - acks.getLong(f.getName(), now) >= keepMin * 60_000L) {
                    if (f.delete()) { acks.edit().remove(f.getName()).apply(); sd--; }
                }
            }
            // Forget acks for files that are gone (deleted by hand or by the storage limit).
            SharedPreferences.Editor e = acks.edit();
            for (String k : acks.getAll().keySet()) if (!names.contains(k)) e.remove(k);
            e.apply();
            queued = qd;
            sent = sd;
        } catch (Exception e) {
            Log.w(TAG, "sweep", e);
        }
    }
}
