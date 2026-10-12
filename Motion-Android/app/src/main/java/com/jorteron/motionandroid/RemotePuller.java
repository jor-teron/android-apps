package com.jorteron.motionandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main mode, runs inside the foreground service: polls every paired Remote Cam, downloads clips Main
 * does not have yet into <save folder>/<cam name>/, checks size and SHA-256, then confirms (acks).
 * After a dropped link the next poll simply picks up whatever is still queued on the cam.
 */
public final class RemotePuller {
    private static final String TAG = "RemotePuller";
    public static final Map<String, String> status = new ConcurrentHashMap<>();
    public static volatile int received;

    private final Context ctx;
    private final SettingsStore s;
    private final CamStore store;
    private final SharedPreferences got;
    private volatile boolean running;
    private Thread thread;

    public RemotePuller(Context c) {
        ctx = c.getApplicationContext();
        s = new SettingsStore(ctx);
        store = new CamStore(ctx);
        got = ctx.getSharedPreferences("received", Context.MODE_PRIVATE);
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "remote-puller");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            for (CamStore.Cam c : store.list()) {
                if (!running) break;
                try { poll(c); }
                catch (Exception e) { status.put(c.id, "Offline (" + e.getClass().getSimpleName() + "), will retry"); }
            }
            try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
        }
    }

    static String base(String host, int port) {
        return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    /** Finds a working address for the cam (last good one first). Returns base URL or null. */
    public static String resolve(CamStore.Cam c) {
        java.util.List<String> order = new java.util.ArrayList<>();
        if (!c.lastHost.isEmpty()) order.add(c.lastHost);
        for (String h : c.hosts) if (!h.trim().isEmpty() && !order.contains(h.trim())) order.add(h.trim());
        for (String h : order) {
            try {
                String b = base(h, c.port);
                String r = getText(b + "/api/info?token=" + c.token, 2500);
                if (r.startsWith("MotionAndroid")) { c.lastHost = h; return b; }
            } catch (Exception ignored) {}
        }
        return null;
    }

    static String getText(String url, int timeout) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(timeout);
        h.setReadTimeout(15000);
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            return sb.toString();
        } finally { h.disconnect(); }
    }

    private Set<String> gotSet(String camId) { return new HashSet<>(got.getStringSet(camId, new HashSet<>())); }

    private void poll(CamStore.Cam c) throws Exception {
        String prevHost = c.lastHost;
        String b = resolve(c);
        if (b == null) { status.put(c.id, "Offline, will retry"); return; }
        if (!c.lastHost.equals(prevHost)) store.update(c);
        String list = getText(b + "/api/clips?token=" + c.token, 3000);
        int queued = 0;
        for (String line : list.split("\n")) if (!line.trim().isEmpty()) queued++;
        status.put(c.id, "Online at " + c.lastHost + (queued > 0 ? ", " + queued + " clip(s) to fetch" : ", all clips received"));
        for (String line : list.split("\n")) {
            if (!running) return;
            String[] f = line.split("\t");
            if (f.length < 3) continue;
            String name = f[0], sha = f[2];
            long size = Long.parseLong(f[1]);
            String key = name + "|" + sha;
            Set<String> have = gotSet(c.id);
            if (!have.contains(key)) {
                status.put(c.id, "Receiving " + name);
                if (!download(c, b, name, size, sha)) continue; // retried next poll
                have = gotSet(c.id);
                have.add(key);
                if (have.size() > 3000) have.clear(); // crude cap; cam forgets acked clips anyway
                got.edit().putStringSet(c.id, have).apply();
                received++;
            }
            String r = getText(b + "/api/ack?token=" + c.token + "&name=" + Uri.encode(name) + "&sha=" + sha, 3000);
            if (!r.startsWith("OK") && !r.startsWith("GONE")) Log.w(TAG, "ack " + name + ": " + r);
        }
        status.put(c.id, "Online at " + c.lastHost + ", all clips received");
    }

    private boolean download(CamStore.Cam c, String b, String name, long size, String sha) {
        ClipStorage st = new ClipStorage(ctx, s.folderUri(), 0);
        ClipStorage.Output o = null;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(b + "/api/clip?token=" + c.token + "&name=" + Uri.encode(name)).openConnection();
            h.setConnectTimeout(3000);
            h.setReadTimeout(20000);
            if (h.getResponseCode() != 200) return false;
            o = st.createIn(c.folder(), name);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            // Do not close this stream: the ParcelFileDescriptor owns the fd and is closed by finish/discard.
            FileOutputStream out = new FileOutputStream(o.pfd.getFileDescriptor());
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); total += n; }
                out.flush();
                out.getFD().sync();
            }
            if (total != size || !RemoteCamServer.hex(md.digest()).equalsIgnoreCase(sha)) {
                Log.w(TAG, "incomplete " + name + " " + total + "/" + size);
                st.discard(o);
                return false;
            }
            st.finish(o);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "download " + name, e);
            if (o != null) st.discard(o);
            status.put(c.id, "Transfer interrupted, will retry");
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
