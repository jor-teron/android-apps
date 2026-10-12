package com.jorteron.motionandroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Main mode: paired Remote Cams. */
public final class CamStore {
    public static final class Cam {
        public String id, name, token, lastHost = "";
        public String[] hosts;
        public int port;

        public String folder() { return SettingsStore.fileSafeName(name); }
    }

    private final SharedPreferences p;

    public CamStore(Context c) { p = c.getSharedPreferences("cams", Context.MODE_PRIVATE); }

    public synchronized List<Cam> list() {
        List<Cam> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Cam c = new Cam();
                c.id = o.getString("id");
                c.name = o.optString("name", "Remote");
                c.token = o.getString("token");
                c.port = o.optInt("port", 8081);
                c.lastHost = o.optString("lastHost", "");
                c.hosts = o.optString("hosts", "").split(",");
                out.add(c);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void save(List<Cam> l) {
        JSONArray a = new JSONArray();
        try {
            for (Cam c : l) {
                JSONObject o = new JSONObject();
                o.put("id", c.id); o.put("name", c.name); o.put("token", c.token);
                o.put("port", c.port); o.put("lastHost", c.lastHost); o.put("hosts", String.join(",", c.hosts));
                a.put(o);
            }
        } catch (Exception ignored) {}
        p.edit().putString("list", a.toString()).apply();
    }

    /** Adds a cam, or updates the existing one with the same token. */
    public synchronized void add(Cam c) {
        List<Cam> l = list();
        for (Cam x : l) if (x.token.equals(c.token)) { c.id = x.id; l.remove(x); break; }
        if (c.id == null) c.id = UUID.randomUUID().toString();
        l.add(c);
        save(l);
    }

    public synchronized void remove(String id) {
        List<Cam> l = list();
        l.removeIf(x -> x.id.equals(id));
        save(l);
    }

    public synchronized void update(Cam c) {
        List<Cam> l = list();
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(c.id)) l.set(i, c);
        save(l);
    }

    /** QR payload: motionandroid://pair?name=..&port=..&token=..&ips=a,b */
    public static String qrPayload(String name, int port, String token, List<String> ips) {
        return "motionandroid://pair?name=" + Uri.encode(name) + "&port=" + port + "&token=" + token
                + "&ips=" + Uri.encode(String.join(",", ips));
    }

    public static Cam parseQr(String text) {
        Uri u = Uri.parse(text.trim());
        if (!"motionandroid".equals(u.getScheme())) return null;
        Cam c = new Cam();
        c.name = u.getQueryParameter("name");
        if (c.name == null || c.name.isEmpty()) c.name = "Remote";
        c.token = u.getQueryParameter("token");
        String ips = u.getQueryParameter("ips");
        try { c.port = Integer.parseInt(u.getQueryParameter("port")); } catch (Exception e) { c.port = 8081; }
        if (c.token == null || ips == null || ips.isEmpty()) return null;
        c.hosts = ips.split(",");
        return c;
    }
}
