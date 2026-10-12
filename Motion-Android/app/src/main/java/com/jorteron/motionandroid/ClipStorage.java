package com.jorteron.motionandroid;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where clips go:
 *  - SAF tree picked by the user (any API level): DocumentsContract.createDocument.
 *  - Default on Android 10+ : MediaStore, Movies/MotionAndroid (no permission needed).
 *  - Default on Android 8-9 : app-specific external storage,
 *    Android/data/com.jorteron.motionandroid/files/Movies/MotionAndroid (no permission needed).
 */
public final class ClipStorage {
    private static final String TAG = "ClipStorage";
    public static final String SUBDIR = "MotionAndroid";

    public static final class Output {
        public ParcelFileDescriptor pfd;
        public String displayName;
        Uri uri;
        File file;
        File finalFile;
        boolean pending;
        boolean saf;
    }

    private final Context ctx;
    private final String treeUri;
    private final long limitBytes;
    /** Remote Cam mode: plain files in an app folder, written as name.part and renamed when complete. */
    private final File forcedDir;
    /** Files the storage limit must never delete (Remote Cam: clips not yet confirmed by Main). */
    private final java.util.function.Predicate<String> protect;

    public ClipStorage(Context ctx, String treeUri, int limitMb) {
        this(ctx, treeUri, limitMb, null, null);
    }

    public ClipStorage(Context ctx, String treeUri, int limitMb, File forcedDir, java.util.function.Predicate<String> protect) {
        this.ctx = ctx.getApplicationContext();
        this.treeUri = treeUri == null ? "" : treeUri;
        this.limitBytes = limitMb * 1024L * 1024L;
        this.forcedDir = forcedDir;
        this.protect = protect;
    }

    /** Local clip folder used in Remote Cam mode (app-specific external storage, no permission needed). */
    public static File remoteDir(Context c) {
        File base = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = new File(c.getFilesDir(), "Movies");
        return new File(base, SUBDIR + "-RemoteCam");
    }

    /** Creates a clip in a sub folder of the save folder (Main mode, clips received from a Remote Cam). */
    public Output createIn(String sub, String name) throws Exception {
        Output o = new Output();
        o.displayName = name;
        ContentResolver cr = ctx.getContentResolver();
        if (!treeUri.isEmpty()) {
            Uri tree = Uri.parse(treeUri);
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri dir = null;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (sub.equals(c.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                        dir = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                        break;
                    }
                }
            }
            if (dir == null) dir = DocumentsContract.createDocument(cr,
                    DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, sub);
            if (dir == null) throw new Exception("Cannot create folder " + sub);
            o.uri = DocumentsContract.createDocument(cr, dir, "video/mp4", name);
            if (o.uri == null) throw new Exception("Cannot create file in chosen folder");
            o.pfd = cr.openFileDescriptor(o.uri, "rw");
            o.saf = true;
        } else if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + SUBDIR + "/" + sub);
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            o.uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (o.uri == null) throw new Exception("MediaStore insert failed");
            o.pending = true;
            o.pfd = cr.openFileDescriptor(o.uri, "rw");
        } else {
            File d = new File(legacyDir(ctx), sub);
            if (!d.exists() && !d.mkdirs()) throw new Exception("Cannot create " + d);
            o.file = new File(d, name);
            o.pfd = ParcelFileDescriptor.open(o.file,
                    ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        if (o.pfd == null) throw new Exception("Cannot open output file");
        return o;
    }

    public static String describe(Context c, String treeUri) {
        if (treeUri != null && !treeUri.isEmpty()) return Uri.decode(treeUri);
        if (Build.VERSION.SDK_INT >= 29) return "Movies/" + SUBDIR;
        return legacyDir(c).getAbsolutePath();
    }

    private static File legacyDir(Context c) {
        File base = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = new File(c.getFilesDir(), "Movies");
        return new File(base, SUBDIR);
    }

    public Output create(String name) throws Exception {
        Output o = new Output();
        o.displayName = name;
        ContentResolver cr = ctx.getContentResolver();
        if (forcedDir != null) {
            if (!forcedDir.exists() && !forcedDir.mkdirs()) throw new Exception("Cannot create " + forcedDir);
            o.finalFile = new File(forcedDir, name);
            o.file = new File(forcedDir, name + ".part");
            o.pfd = ParcelFileDescriptor.open(o.file,
                    ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        } else if (!treeUri.isEmpty()) {
            o.saf = true;
            Uri tree = Uri.parse(treeUri);
            Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            o.uri = DocumentsContract.createDocument(cr, dir, "video/mp4", name);
            if (o.uri == null) throw new Exception("Cannot create file in chosen folder");
            o.pfd = cr.openFileDescriptor(o.uri, "rw");
        } else if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + SUBDIR);
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            o.uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (o.uri == null) throw new Exception("MediaStore insert failed");
            o.pending = true;
            o.pfd = cr.openFileDescriptor(o.uri, "rw");
        } else {
            File d = legacyDir(ctx);
            if (!d.exists() && !d.mkdirs()) throw new Exception("Cannot create " + d);
            o.file = new File(d, name);
            o.pfd = ParcelFileDescriptor.open(o.file,
                    ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        if (o.pfd == null) throw new Exception("Cannot open output file");
        return o;
    }

    public void finish(Output o) {
        try { if (o.pfd != null) o.pfd.close(); } catch (Exception ignored) {}
        if (o.finalFile != null && o.file != null && !o.file.renameTo(o.finalFile)) Log.w(TAG, "rename failed " + o.file);
        if (o.pending && Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(o.uri, cv, null, null);
            } catch (Exception e) { Log.w(TAG, "unpend", e); }
        }
    }

    /** Deletes a failed/empty clip. */
    public void discard(Output o) {
        try { if (o.pfd != null) o.pfd.close(); } catch (Exception ignored) {}
        try {
            if (o.file != null) o.file.delete();
            else if (o.saf) DocumentsContract.deleteDocument(ctx.getContentResolver(), o.uri);
            else ctx.getContentResolver().delete(o.uri, null, null);
        } catch (Exception e) { Log.w(TAG, "discard", e); }
    }

    private static final class Entry {
        final long time, size; final Uri uri; final File file;
        Entry(long t, long s, Uri u, File f) { time = t; size = s; uri = u; file = f; }
    }

    /** Deletes the oldest .mp4 clips in our folder until the total is under the limit. */
    public void enforceLimit() {
        if (limitBytes <= 0) return;
        List<Entry> list = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        try {
            if (forcedDir != null) {
                File[] fs = forcedDir.listFiles();
                if (fs != null) for (File f : fs) if (f.getName().endsWith(".mp4")
                        && (protect == null || !protect.test(f.getName())))
                    list.add(new Entry(f.lastModified(), f.length(), null, f));
            } else if (!treeUri.isEmpty()) {
                Uri tree = Uri.parse(treeUri);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                try (Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        String n = c.getString(1);
                        if (n == null || !n.endsWith(".mp4")) continue;
                        list.add(new Entry(c.getLong(3), c.getLong(2),
                                DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), null));
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                Uri col = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                String sel = MediaStore.Video.Media.RELATIVE_PATH + "=?";
                String[] args = {Environment.DIRECTORY_MOVIES + "/" + SUBDIR + "/"};
                try (Cursor c = cr.query(col, new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED}, sel, args, null)) {
                    while (c != null && c.moveToNext()) {
                        list.add(new Entry(c.getLong(2), c.getLong(1), ContentUris.withAppendedId(col, c.getLong(0)), null));
                    }
                }
            } else {
                File[] fs = legacyDir(ctx).listFiles();
                if (fs != null) for (File f : fs) if (f.getName().endsWith(".mp4"))
                    list.add(new Entry(f.lastModified(), f.length(), null, f));
            }
            long total = 0;
            for (Entry e : list) total += e.size;
            if (total <= limitBytes) return;
            Collections.sort(list, (a, b) -> Long.compare(a.time, b.time));
            for (Entry e : list) {
                if (total <= limitBytes) break;
                boolean ok;
                if (e.file != null) ok = e.file.delete();
                else if (!treeUri.isEmpty()) ok = DocumentsContract.deleteDocument(cr, e.uri);
                else ok = cr.delete(e.uri, null, null) > 0;
                if (ok) total -= e.size;
            }
        } catch (Exception e) {
            Log.w(TAG, "enforceLimit", e);
        }
    }
}
