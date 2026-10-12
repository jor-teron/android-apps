package com.jorteron.motionandroid;

/**
 * Compares the luma of consecutive analysis frames, downsampled to about 320x240.
 * A pixel counts as changed when its luma differs by more than NOISE_LEVEL, and it is
 * kept only if at least two of its four neighbours also changed (simple despeckle).
 */
public final class MotionDetector {
    private static final int NOISE_LEVEL = 24;

    private byte[] prev;
    private byte[] cur;
    private boolean[] changed;
    private int aw, ah;

    /** Returns the number of changed pixels on the analysis frame (0 on the first frame). */
    public int process(byte[] y, int w, int h) {
        int step = Math.max(1, Math.round(w / 320f));
        int nw = w / step, nh = h / step;
        if (cur == null || nw != aw || nh != ah) {
            aw = nw; ah = nh;
            cur = new byte[aw * ah];
            prev = null;
            changed = new boolean[aw * ah];
        }
        for (int j = 0; j < ah; j++) {
            int src = j * step * w;
            int dst = j * aw;
            for (int i = 0; i < aw; i++) cur[dst + i] = y[src + i * step];
        }
        if (prev == null) {
            prev = cur.clone();
            return 0;
        }
        for (int k = 0; k < cur.length; k++) {
            int d = (cur[k] & 0xff) - (prev[k] & 0xff);
            changed[k] = d > NOISE_LEVEL || d < -NOISE_LEVEL;
        }
        int count = 0;
        for (int j = 1; j < ah - 1; j++) {
            int row = j * aw;
            for (int i = 1; i < aw - 1; i++) {
                int k = row + i;
                if (!changed[k]) continue;
                int n = (changed[k - 1] ? 1 : 0) + (changed[k + 1] ? 1 : 0)
                        + (changed[k - aw] ? 1 : 0) + (changed[k + aw] ? 1 : 0);
                if (n >= 2) count++;
            }
        }
        byte[] t = prev; prev = cur; cur = t;
        return count;
    }

    public void reset() { prev = null; }
}
