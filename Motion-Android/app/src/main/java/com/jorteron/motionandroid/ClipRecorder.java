package com.jorteron.motionandroid;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Turns the continuous H.264 stream into motion clips, following Linux motion's
 * pre_capture / post_capture / event_gap semantics. All methods run on the pipeline thread.
 *
 *  - A ring buffer keeps the encoded frames of the last (pre-capture + ~1 keyframe interval),
 *    always starting at a keyframe.
 *  - Motion starts an event: a new MP4 opens and is fed from the newest keyframe at or before
 *    (now - pre-capture), then live frames.
 *  - Frames are written while motion continues and for post-capture after the last motion.
 *  - The file stays open until event-gap seconds pass with no motion. Motion inside the gap
 *    resumes writing into the SAME file (with pre-capture again, starting at a keyframe).
 *    Frames in the quiet part of the gap are skipped, exactly like motion's movie output, so
 *    the player jumps over the idle time.
 *  - Event gap 0 means the clip ends right after post-capture.
 */
public final class ClipRecorder {
    private static final String TAG = "ClipRecorder";

    public interface Listener {
        void onClipStarted(String name);
        void onClipFinished(String name, boolean ok);
        void requestKeyFrame();
    }

    private static final class Frame {
        final byte[] data; final long pts; final boolean key;
        Frame(byte[] d, long p, boolean k) { data = d; pts = p; key = k; }
    }

    private final ClipStorage storage;
    private final String camName;
    private final long preUs, postUs, gapUs;
    private final Listener listener;
    private final ArrayDeque<Frame> ring = new ArrayDeque<>();
    private final SimpleDateFormat nameFmt = new SimpleDateFormat("yyyy_MM_dd_HH-mm-ss", Locale.US);

    private MediaFormat format;
    private MediaMuxer muxer;
    private ClipStorage.Output out;
    private int track = -1;
    private long basePts = -1, lastWrittenPts = -1;
    private long lastMotionUs = -1;
    private boolean eventOpen, writing, waitKey;
    private int written;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public ClipRecorder(ClipStorage storage, String camName, int preMs, int postMs, int gapS, Listener l) {
        this.storage = storage;
        this.camName = SettingsStore.fileSafeName(camName);
        this.preUs = preMs * 1000L;
        this.postUs = postMs * 1000L;
        this.gapUs = gapS * 1_000_000L;
        this.listener = l;
    }

    public void setFormat(MediaFormat f) { format = f; }

    public boolean isRecording() { return eventOpen && writing; }
    public boolean isEventOpen() { return eventOpen; }

    /** Called by the detector when motion is present at time nowUs (same clock as frame pts). */
    public void onMotion(long nowUs) {
        lastMotionUs = nowUs;
        if (!eventOpen) {
            if (format == null) return; // encoder not ready yet
            if (!openClip()) return;
            eventOpen = true;
            writing = true;
            flushRing(nowUs, Long.MIN_VALUE);
        } else if (!writing) {
            writing = true;
            flushRing(nowUs, lastWrittenPts);
        }
    }

    /** Called for every encoded frame. */
    public void onEncoded(ByteBuffer buf, MediaCodec.BufferInfo bi) {
        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || bi.size == 0) return;
        byte[] d = new byte[bi.size];
        buf.position(bi.offset);
        buf.limit(bi.offset + bi.size);
        buf.get(d);
        Frame f = new Frame(d, bi.presentationTimeUs, (bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
        ring.addLast(f);
        trimRing(f.pts);

        if (eventOpen) {
            if (writing) {
                write(f);
                if (lastMotionUs >= 0 && f.pts > lastMotionUs + postUs) writing = false; // post-capture done
            }
            if (!writing && f.pts > lastMotionUs + Math.max(gapUs, postUs)) closeClip();
        }
    }

    /** Keep frames back to the newest keyframe at or before (now - pre-capture). */
    private void trimRing(long nowPts) {
        long cutoff = nowPts - preUs;
        while (ring.size() > 1) {
            Iterator<Frame> it = ring.iterator();
            it.next();
            Frame nextKey = null;
            while (it.hasNext()) { Frame x = it.next(); if (x.key) { nextKey = x; break; } }
            if (nextKey == null || nextKey.pts > cutoff) break;
            while (ring.peekFirst() != nextKey) ring.pollFirst();
        }
        // Safety cap (e.g. encoder ignoring I-frame interval): ~ pre + 10 s.
        while (ring.size() > 2 && nowPts - ring.peekFirst().pts > preUs + 10_000_000L) ring.pollFirst();
    }

    private void flushRing(long nowUs, long after) {
        long cutoff = nowUs - preUs;
        List<Frame> cands = new ArrayList<>();
        for (Frame f : ring) if (f.pts > after) cands.add(f);
        int start = -1;
        for (int i = 0; i < cands.size(); i++) {
            Frame f = cands.get(i);
            if (!f.key) continue;
            if (f.pts <= cutoff || start < 0) start = i;
            if (f.pts > cutoff) break;
        }
        if (start < 0) {
            // No keyframe available: wait for the next one.
            waitKey = true;
            listener.requestKeyFrame();
            return;
        }
        waitKey = false;
        for (int i = start; i < cands.size(); i++) write(cands.get(i));
    }

    private void write(Frame f) {
        if (muxer == null) return;
        if (waitKey) {
            if (!f.key) return;
            waitKey = false;
        }
        if (f.pts <= lastWrittenPts) return;
        if (basePts < 0) basePts = f.pts;
        info.set(0, f.data.length, f.pts - basePts, f.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        try {
            muxer.writeSampleData(track, ByteBuffer.wrap(f.data), info);
            lastWrittenPts = f.pts;
            written++;
        } catch (Exception e) {
            Log.e(TAG, "write", e);
        }
    }

    private boolean openClip() {
        String name = camName + "_" + nameFmt.format(new Date()) + ".mp4";
        try {
            out = storage.create(name);
            muxer = new MediaMuxer(out.pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            track = muxer.addTrack(format);
            muxer.start();
        } catch (Exception e) {
            Log.e(TAG, "openClip", e);
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) {}
            muxer = null;
            if (out != null) storage.discard(out);
            out = null;
            listener.onClipFinished(name, false);
            return false;
        }
        basePts = -1; lastWrittenPts = -1; written = 0; waitKey = true;
        listener.onClipStarted(name);
        return true;
    }

    public void closeClip() {
        if (!eventOpen) return;
        eventOpen = false; writing = false;
        boolean ok = written > 0;
        try {
            if (ok) muxer.stop();
        } catch (Exception e) { Log.e(TAG, "stop", e); ok = false; }
        try { muxer.release(); } catch (Exception ignored) {}
        muxer = null;
        if (ok) storage.finish(out); else storage.discard(out);
        String n = out.displayName;
        out = null;
        listener.onClipFinished(n, ok);
        if (ok) new Thread(storage::enforceLimit, "storage-limit").start();
    }
}
