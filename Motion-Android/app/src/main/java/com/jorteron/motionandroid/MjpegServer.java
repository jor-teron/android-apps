package com.jorteron.motionandroid;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal MJPEG-over-HTTP server (like motion's stream port). Any number of clients.
 *   /  or /stream   multipart/x-mixed-replace JPEG stream
 *   /snapshot.jpg   single current frame
 */
public final class MjpegServer {
    private static final String TAG = "MjpegServer";
    private static final String BOUNDARY = "motionandroidframe";

    private final int port;
    private ServerSocket server;
    private volatile boolean running;
    private final Object lock = new Object();
    private byte[] jpeg;
    private long seq;
    private final AtomicInteger clients = new AtomicInteger();

    /** Extra endpoints (Remote Cam clip API). Return false if the path is not handled. */
    public interface Api {
        boolean handle(String path, java.util.Map<String, String> query, OutputStream out) throws Exception;
    }

    private volatile String token;
    private volatile Api api;

    public MjpegServer(int port) { this.port = port; }

    /** Remote Cam mode: every request must carry ?token=... */
    public void setToken(String t) { token = t; }
    public void setApi(Api a) { api = a; }

    public static java.util.Map<String, String> parseQuery(String q) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e < 0) m.put(java.net.URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(java.net.URLDecoder.decode(kv.substring(0, e), "UTF-8"), java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }

    static void sendText(OutputStream out, int code, String status, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.0 " + code + " " + status + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private volatile long lastSnapshotMs;

    /** Stream clients, plus one if someone polled /snapshot.jpg in the last 3 s (Main's live view). */
    public int clientCount() { return clients.get() + (System.currentTimeMillis() - lastSnapshotMs < 3000 ? 1 : 0); }

    public void start() throws Exception {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(this::acceptLoop, "mjpeg-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        synchronized (lock) { lock.notifyAll(); }
    }

    public void publish(byte[] frame) {
        synchronized (lock) {
            jpeg = frame;
            seq++;
            lock.notifyAll();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "mjpeg-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept", e);
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            sock.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
            String req = in.readLine();
            if (req == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }
            String[] parts = req.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            java.util.Map<String, String> query = parseQuery(q >= 0 ? path.substring(q + 1) : null);
            if (q >= 0) path = path.substring(0, q);
            OutputStream out = sock.getOutputStream();
            String tk = token;
            if (tk != null && !java.security.MessageDigest.isEqual(tk.getBytes(StandardCharsets.UTF_8),
                    String.valueOf(query.get("token")).getBytes(StandardCharsets.UTF_8))) {
                sendText(out, 401, "Unauthorized", "Pairing code required\r\n");
                return;
            }
            Api a = api;
            if (a != null && path.startsWith("/api/")) {
                sock.setSoTimeout(60000);
                if (!a.handle(path, query, out)) send404(out);
                return;
            }
            if (path.equals("/snapshot.jpg")) {
                lastSnapshotMs = System.currentTimeMillis();
                byte[] f = waitFrame(-1);
                if (f == null) { send404(out); return; }
                out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length
                        + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(f);
                out.flush();
                return;
            }
            if (!path.equals("/") && !path.equals("/stream")) { send404(out); return; }
            clients.incrementAndGet();
            try {
                out.write(("HTTP/1.0 200 OK\r\nServer: MotionAndroid\r\nConnection: close\r\nMax-Age: 0\r\nExpires: 0\r\n"
                        + "Cache-Control: no-cache, private\r\nPragma: no-cache\r\n"
                        + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                long last = -1;
                while (running) {
                    byte[] f;
                    synchronized (lock) {
                        long deadline = System.currentTimeMillis() + 10000;
                        while (running && seq == last && System.currentTimeMillis() < deadline) lock.wait(1000);
                        if (!running) break;
                        if (seq == last) continue;
                        last = seq;
                        f = jpeg;
                    }
                    if (f == null) continue;
                    out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(f);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            } finally {
                clients.decrementAndGet();
            }
        } catch (Exception e) {
            // client went away
        }
    }

    private byte[] waitFrame(long ms) throws InterruptedException {
        synchronized (lock) {
            if (jpeg == null) lock.wait(3000);
            return jpeg;
        }
    }

    private static void send404(OutputStream out) throws Exception {
        out.write("HTTP/1.0 404 Not Found\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nNot found. Use / or /stream\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
