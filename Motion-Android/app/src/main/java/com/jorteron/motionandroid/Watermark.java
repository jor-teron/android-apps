package com.jorteron.motionandroid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Burns "Camera_Name YYYY-MM-DD HH:MM:SS" into the bottom-left corner of an I420 frame:
 * white text with a black outline. The text is rendered with Canvas once per second into
 * a pixel mask, and the mask is then stamped straight into the Y/U/V planes each frame.
 */
public final class Watermark {
    private final String name;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private String lastText = "";
    private int[] textIdx = new int[0];    // Y-plane indices for text pixels
    private int[] outlineIdx = new int[0]; // Y-plane indices for outline pixels
    private int[] chromaIdx = new int[0];  // chroma indices to neutralise
    private int fw, fh;

    public Watermark(String name) { this.name = name; }

    public void apply(byte[] y, byte[] u, byte[] v, int w, int h) {
        String text = name + "  " + fmt.format(new Date());
        if (!text.equals(lastText) || w != fw || h != fh) {
            build(text, w, h);
            lastText = text; fw = w; fh = h;
        }
        for (int k : outlineIdx) y[k] = 16;
        for (int k : textIdx) y[k] = (byte) 235;
        for (int k : chromaIdx) { u[k] = (byte) 128; v[k] = (byte) 128; }
    }

    private void build(String text, int w, int h) {
        float size = Math.max(10f, Math.min(w, h) / 22f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setTypeface(Typeface.MONOSPACE);
        fill.setTextSize(size);
        float maxW = w * 0.94f; // portrait frames are narrow: shrink to fit
        if (fill.measureText(text) > maxW) { size = Math.max(8f, size * maxW / fill.measureText(text)); fill.setTextSize(size); }
        fill.setColor(Color.WHITE);
        Paint stroke = new Paint(fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, size / 7f));
        stroke.setColor(Color.RED); // marker colour for outline pixels
        int margin = Math.max(4, h / 60);
        int bw = Math.min(w, (int) (fill.measureText(text) + margin * 2 + 8));
        int bh = (int) (size * 1.5f);
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float baseline = bh - size * 0.35f;
        c.drawText(text, margin, baseline, stroke);
        c.drawText(text, margin, baseline, fill);
        int[] px = new int[bw * bh];
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
        bmp.recycle();
        int ox = 0, oy = Math.max(0, h - bh - margin);
        int[] t = new int[px.length], o = new int[px.length];
        int nt = 0, no = 0;
        java.util.HashSet<Integer> cs = new java.util.HashSet<>();
        int cw = w / 2;
        for (int j = 0; j < bh && oy + j < h; j++) {
            for (int i = 0; i < bw && ox + i < w; i++) {
                int p = px[j * bw + i];
                if (Color.alpha(p) < 128) continue;
                int k = (oy + j) * w + (ox + i);
                if (Color.green(p) > 128) t[nt++] = k; else o[no++] = k;
                cs.add(((oy + j) / 2) * cw + (ox + i) / 2);
            }
        }
        textIdx = java.util.Arrays.copyOf(t, nt);
        outlineIdx = java.util.Arrays.copyOf(o, no);
        chromaIdx = new int[cs.size()];
        int n = 0;
        for (int k : cs) chromaIdx[n++] = k;
    }
}
