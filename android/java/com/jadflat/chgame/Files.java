package com.jadflat.chgame;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The games folder the user picked (a document tree; the app has no storage
 * permission) and the app's private copy of it that the emulator uses as the
 * SD card. Everything but the .CHG files is copied in before a game starts,
 * and what the game changed or added is copied back after.
 */
final class Files {
    static final int MAX_DEPTH = 4;
    static final long MAX_FILE = 256L << 20;

    static final class Entry {
        final String path;      /* relative, '/'-separated, every part checked by safeName */
        final Uri uri;
        final long size, modified;
        Entry(String path, Uri uri, long size, long modified) {
            this.path = path; this.uri = uri; this.size = size; this.modified = modified;
        }
    }

    /** A name from a document provider, or null if it is not a plain file or folder name. */
    static String safeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 200) return null;
        if (name.startsWith(".") || name.contains("/") || name.contains("\\") || name.contains("\0")) return null;
        return name;
    }

    /** exported saves (the save manager's): not part of the SD card */
    static boolean isSave(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".sav") || n.endsWith(".state");
    }

    /** A card path that is the app's own save copy (a <game>.sav or .state at the top, or in the saves folder), not a
        game's file that merely ends in .sav. */
    static boolean isAppSave(String rel) {
        int cut = rel.lastIndexOf('/');
        return isSave(rel) && (cut < 0 || rel.substring(0, cut).equalsIgnoreCase("saves"));
    }

    static boolean isGame(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".chg");
    }

    /** The folder saved under pref ("tree": the games folder, "mediaTree": ES-DE media) if this app may still use it:
        read access, and write access too when needWrite (screens that only read pass false). Null otherwise. The one
        check every screen uses. */
    static Uri tree(Context c, String pref, boolean needWrite) {
        String s = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(pref, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission() && (!needWrite || p.isWritePermission())) return u;
        return null;
    }

    /** The app's screenshots and clips in the games folder: never copied onto the emulated card. */
    static final String SHOTS = "CHGame screenshots";

    /** Every file under the tree, down to MAX_DEPTH folders. */
    static List<Entry> list(ContentResolver cr, Uri tree) {
        return list(cr, tree, MAX_DEPTH);
    }

    /** Every file under the tree, down to maxDepth folders (0: the top only). */
    static List<Entry> list(ContentResolver cr, Uri tree, int maxDepth) {
        List<Entry> out = new ArrayList<>();
        walk(cr, tree, DocumentsContract.getTreeDocumentId(tree), "", 0, Math.min(maxDepth, MAX_DEPTH), out);
        return out;
    }

    /** Set when a folder could not be listed in the last list() on this thread (a listing with holes). */
    private static final ThreadLocal<Boolean> HOLES = new ThreadLocal<>();

    /** list(), but an IOException when any folder could not be listed: a sync must not take half a tree for all of it. */
    static List<Entry> listAll(ContentResolver cr, Uri tree) throws IOException {
        HOLES.set(false);
        List<Entry> l = list(cr, tree);
        if (Boolean.TRUE.equals(HOLES.get())) throw new IOException("the folder could not be read in full");
        return l;
    }

    private static void walk(ContentResolver cr, Uri tree, String docId, String prefix, int depth, int maxDepth, List<Entry> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED };
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) { HOLES.set(true); return; }
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = safeName(c.getString(1));
                if (id == null || name == null) continue;
                if (Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    if (depth < maxDepth) walk(cr, tree, id, prefix + name + "/", depth + 1, maxDepth, out);
                } else {
                    out.add(new Entry(prefix + name, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            c.isNull(3) ? -1 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
                }
            }
        } catch (RuntimeException e) {
            HOLES.set(true);      /* a folder the provider will not list: skipped (listAll then fails) */
        }
    }

    static File sdFolder(Context ctx) {
        return new File(ctx.getFilesDir(), "sys/chgame/sdcard");
    }

    /** The picked folder's files (not the games) into the private SD folder, where they differ. A file that fails
        is tried once more at the end (a provider's passing hiccup); one that fails again is left as it was. */
    static Map<String, Long> importCard(Context ctx, Uri tree) throws IOException {
        synchronized (IMPORT) { return importCardLocked(ctx, tree); }
    }

    private static Map<String, Long> importCardLocked(Context ctx, Uri tree) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        File sd = sdFolder(ctx);
        Map<String, Long> stamps = new HashMap<>();
        List<Entry> again = new ArrayList<>();
        sweepParts(ctx, sd);
        android.content.SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String treeKey = tree.toString();
        /* list first: nothing is wiped or recorded for a folder that cannot be read at all */
        HOLES.set(false);
        List<Entry> listed = list(cr, tree);
        boolean holes = Boolean.TRUE.equals(HOLES.get());
        if (holes && listed.isEmpty()) throw new IOException("the folder could not be read");
        String known = p.getString("cardTree", null);
        if (!treeKey.equals(known)) {
            /* another games folder: the private card mirrors this one, so the last folder's files go (nothing of them
               is copied into the new folder later). The very first run keeps what is there. */
            if (known != null) wipeDir(sd);
            p.edit().putString("cardTree", treeKey).putStringSet("cardKnown", new java.util.HashSet<>()).apply();
        }
        java.util.Set<String> here = new java.util.HashSet<>();
        for (Entry e : listed) here.add(e.path);
        /* a file the folder had at the last sync and no longer has was deleted there: off the card too
           (not when a subfolder could not be listed: its files only look missing) */
        java.util.Set<String> was = p.getStringSet("cardKnown", new java.util.HashSet<>());
        if (!holes) for (String rel : was) {
            if (here.contains(rel)) continue;
            try { inside(sd, rel).delete(); } catch (IOException ignored) { }
        }
        for (Entry e : listed) {
            if (isGame(e.path) || isAppSave(e.path) || e.path.startsWith("screenshots/") || e.path.startsWith(SHOTS + "/")
                    || e.path.startsWith("chgame/covers/") || e.path.startsWith(Saves.BACKUP + "/") || e.size > MAX_FILE) continue;
            try {
                File f = inside(sd, e.path);
                boolean kept = keepLocal(cr, e, f);
                if (!kept) copyIn(ctx, cr, e, f);
                /* a local copy newer than the folder's has not been exported yet: a stamp that differs, so the export sends it */
                stamps.put(e.path, kept && e.modified > 0 && f.lastModified() > e.modified ? -1L : f.lastModified());
            } catch (IOException | RuntimeException ex) {
                again.add(e);
            }
        }
        for (Entry e : again) {
            if (e.size > (16L << 20)) continue;     /* only small files are tried again: a big one is not read twice per launch */
            try {
                File f = inside(sd, e.path);
                copyIn(ctx, cr, e, f);
                stamps.put(e.path, f.lastModified());
            } catch (IOException | RuntimeException ex) {
                /* this file only: the others are on the card */
            }
        }
        java.util.Set<String> keep = new java.util.HashSet<>(stamps.keySet());
        if (holes) for (String rel : was) if (!here.contains(rel)) keep.add(rel);   /* unknown, not gone */
        p.edit().putStringSet("cardKnown", keep).apply();
        lastImport = stamps;
        return stamps;
    }

    /** The stamps of the newest import: an export of older stamps must not rewrite the record. */
    private static volatile Map<String, Long> lastImport;

    private static void wipeDir(File dir) {
        File[] in = dir.listFiles();
        if (in != null) for (File f : in) {
            if (f.isDirectory()) wipeDir(f);
            f.delete();
        }
    }

    /** import.part every time (no copy runs then); once: 1.90's <name>.part beside a card file, in any folder of the card. */
    private static void sweepParts(Context ctx, File sd) {
        new File(ctx.getFilesDir(), "sys/import.part").delete();      /* no copy runs now: importCard holds the lock */
        android.content.SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("partSwept2", false)) return;
        java.util.ArrayDeque<File> dirs = new java.util.ArrayDeque<>();
        dirs.add(sd);
        while (!dirs.isEmpty()) {
            File[] kids = dirs.pop().listFiles();
            if (kids != null) for (File x : kids) {
                if (x.isDirectory()) dirs.add(x);
                else if (x.getName().endsWith(".part") && new File(x.getParentFile(), x.getName().substring(0, x.getName().length() - 5)).isFile()) x.delete();
            }
        }
        p.edit().putBoolean("partSwept2", true).apply();
    }

    /** Once: the temporary files 1.95-1.96 named <name><digits>.tmp (createTempFile) in games/, saves/ and states/.
        Called from copyGame, which every way of starting a game goes through (with or without a picked folder). */
    private static void sweepTemps(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("tmpSwept", false)) return;
        for (String d : new String[] { "games", "saves", "states" }) {
            File[] kids = new File(ctx.getFilesDir(), d).listFiles();
            /* no temp name in use since 2.04 ends in a digit before .tmp (<game>.CHG.tmp, <x>.sav.tmp, <x>.state.tmp,
               <x>.state.png.tmp, <x>.auto.state.resume.tmp), so every match is a leftover, however recent */
            if (kids != null) for (File x : kids)
                if (x.isFile() && x.getName().matches("(?i).*\\d[.]tmp")) x.delete();
        }
        p.edit().putBoolean("tmpSwept", true).apply();
    }

    /** One import at a time: copyIn's temporary file has one name. */
    private static final Object IMPORT = new Object();

    /** Copies the games-folder file e over the private copy f: written whole to files/sys/import.part (beside the card,
        never on it, the same file system) and then moved over f atomically (replaceWith), so a failure leaves f as it was. */
    private static void copyIn(Context ctx, ContentResolver cr, Entry e, File f) throws IOException {
        File tmp = new File(ctx.getFilesDir(), "sys/import.part");      /* never on the card; one name, reused (importCard's lock) */
        try (InputStream in = cr.openInputStream(e.uri)) {
            writeReplacing(in, f, tmp, MAX_FILE, e.modified, !isGameData(e.path));     /* a save must survive a power cut; data is imported again */
        }
    }

    /** Game data the player never changes (.DAT-like, not a save): may be replaced by a newer or different copy. One rule for keepLocal and Updates.syncData. */
    static boolean isGameData(String path) {
        String p = path.toUpperCase(Locale.ROOT);
        return Updates.plainData(p) && !p.contains("SAVE");      /* MYBLSAVE.DAT, SAVEDATA/SLOT1.DAT: the player's */
    }

    /**
     * Whether the private copy f of the games-folder file e stays (importCard). Read-only data
     * (.DAT-like, not a save) is current only if it is the very file imported: same size, stamp and first 4 KB.
     * Anything else (saves, HISCORE.BIN) may have been written by the game: imported copies carry the folder's stamp,
     * so a later stamp means the game wrote it and it stays.
     */
    static boolean keepLocal(ContentResolver cr, Entry e, File f) {
        if (!f.isFile()) return false;
        /* a provider with no dates (modified 0): size and first 4 KB decide for data (copying it all on every run would
           stall launches), so a same-size update that changes only later blocks is missed there; anything else stays */
        if (isGameData(e.path)) return f.length() == e.size && (e.modified <= 0 || f.lastModified() == e.modified) && sameHead(cr, e.uri, f);
        return e.modified <= 0 || f.lastModified() >= e.modified;
    }

    /** The first 4 KB of both copies match (a data file's header: Starlift's build hash is in block 0). */
    private static boolean sameHead(ContentResolver cr, Uri uri, File f) {
        byte[] a = new byte[4096], b = new byte[4096];
        try (InputStream x = cr.openInputStream(uri); InputStream y = new FileInputStream(f)) {
            if (x == null) return true;
            int na = readUpTo(x, a), nb = readUpTo(y, b);
            return na == nb && java.util.Arrays.equals(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static int readUpTo(InputStream in, byte[] buf) throws IOException {
        int t = 0;
        for (int r; t < buf.length && (r = in.read(buf, t, buf.length - t)) > 0; ) t += r;
        return t;
    }

    /** The games folder's files into the SD folder, for a game run without GameActivity (preview, clips); quiet on failure. */
    static void importData(Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        String t0 = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString("tree", null);
        if (importedAt != 0 && now - importedAt < IMPORT_FRESH_MS && java.util.Objects.equals(t0, importedTree)) return;     /* browsing: the card was read moments ago */
        try {
            String t = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString("tree", null);
            if (t != null) {
                importCard(ctx, Uri.parse(t));
                importedAt = now;
                importedTree = t;
            }
        } catch (Exception ignored) { }
    }

    /** importData's last full read of the card (0: read it next time); a game's start always reads it (importCard). */
    private static volatile String importedTree;
    private static volatile long importedAt;
    private static final long IMPORT_FRESH_MS = 60_000;

    /** A card file was written to the games folder (a download): the next preview reads the card again. */
    static void cardChanged() {
        importedAt = 0;
    }

    /** May a file the game left in the SD folder be written to the picked folder? Never a game or a save; a file
        that was imported (so the game may have changed it); a new top-level file; never a file that exists but was not imported. */
    static boolean mayExport(String rel, boolean imported, boolean exists) {
        if (rel == null) return false;
        String leaf = rel.substring(rel.lastIndexOf('/') + 1);
        if (isGame(leaf) || isAppSave(rel)) return false;
        if (rel.startsWith(Saves.BACKUP + "/")) return false;
        if (imported) return true;
        if (exists) return false;
        return !rel.contains("/") && safeName(rel) != null;
    }

    /** Files the game changed or made in the SD folder, back into the picked folder. */
    static void exportCard(Context ctx, Uri tree, Map<String, Long> stamps) throws IOException {
        synchronized (IMPORT) { exportCardLocked(ctx, tree, stamps); }      /* never while a card is being imported */
    }

    private static void exportCardLocked(Context ctx, Uri tree, Map<String, Long> stamps) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        File sd = sdFolder(ctx);
        Map<String, Uri> existing = new HashMap<>();
        HOLES.set(false);
        for (Entry e : list(cr, tree)) existing.put(e.path, e.uri);
        boolean holes = Boolean.TRUE.equals(HOLES.get());
        boolean current = stamps == lastImport;
        List<String> changed = new ArrayList<>();
        collect(sd, "", 0, changed);
        for (String rel : changed) {
            File f = inside(sd, rel);
            Long was = stamps.get(rel);
            if (was != null && f.lastModified() == was) continue;
            Uri target = existing.get(rel);
            if (holes && target == null) continue;     /* maybe in a folder that could not be listed: no duplicate */
            if (!mayExport(rel, was != null, target != null)) continue;
            if (target != null) {
                overwrite(ctx, target, out -> { try (InputStream in = new FileInputStream(f)) { copy(in, out); } });
            } else {
                target = DocumentsContract.createDocument(cr,
                        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                        "application/octet-stream", rel);
                if (target == null) continue;
                try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(target, "wt")) {
                    if (out == null) throw new IOException("no output");
                    copy(in, out);
                } catch (IOException | RuntimeException ex) {
                    try { DocumentsContract.deleteDocument(cr, target); } catch (Exception ignored) { }     /* no empty file left to look newer */
                    throw ex;
                }
            }
            stamps.put(rel, f.lastModified());
        }
        /* a file this run imported that the game deleted from its card: deleted in the folder too */
        java.util.Set<String> now = new java.util.HashSet<>(changed);
        if (!holes && current) for (String rel : new ArrayList<>(stamps.keySet())) {
            if (now.contains(rel) || isGame(rel) || isAppSave(rel)) continue;
            Uri there = existing.get(rel);
            try {
                if (there != null) DocumentsContract.deleteDocument(cr, there);
                stamps.remove(rel);
            } catch (Exception e) {
                /* left in the folder: the next import brings it back */
            }
        }
        android.content.SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        if (current && tree.toString().equals(p.getString("cardTree", null)))
            p.edit().putStringSet("cardKnown", new java.util.HashSet<>(stamps.keySet())).apply();
    }

    private static void collect(File dir, String prefix, int depth, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH) collect(f, prefix + f.getName() + "/", depth + 1, out);
            } else {
                out.add(prefix + f.getName());
            }
        }
    }

    /** base/rel, refusing anything that would land outside base. */
    static File inside(File base, String rel) throws IOException {
        File f = new File(base, rel);
        String b = base.getCanonicalPath() + File.separator;
        if (!f.getCanonicalPath().startsWith(b)) throw new IOException("bad path " + rel);
        return f;
    }

    /** A game from the picked folder into the private games folder, under its own name. */
    static File copyGame(Context ctx, Uri uri, String name) throws IOException {
        return copyGame(ctx, uri, name, null);
    }

    /** As copyGame; stops (Cancelled, the old copy kept) once cancel (null: never) is cancelled: in the wait for the
        file's lock, in the provider's open (it gets the signal), or in a read (the source is closed under it). The
        signal is this copy's own: its cancel listener is set here and cleared at the end. */
    static File copyGame(Context ctx, Uri uri, String name, android.os.CancellationSignal cancel) throws IOException {
        String safe = safeName(name);
        if (safe == null || !isGame(safe)) throw new IOException("not a .CHG: " + name);
        File dir = new File(ctx.getFilesDir(), "games");
        dir.mkdirs();
        File f = inside(dir, safe);
        sweepTemps(ctx);
        /* through <game>.tmp under that file's PathLock, taken before the source is opened (a second copy of the same
           game waits without holding a stream open); writeReplacing takes the same lock again (it re-enters) */
        PathLock lock = PathLock.take(fileKey(f));
        try {
            try {
                while (!lock.tryLock(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                    if (cancelled(cancel)) throw new Cancelled();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
            try { copyLocked(ctx, uri, name, f, cancel); } finally { lock.unlock(); }
        } finally { lock.release(); }
        return f;
    }

    private static boolean cancelled(android.os.CancellationSignal cancel) {
        return cancel != null && cancel.isCanceled();
    }

    private static void copyLocked(Context ctx, Uri uri, String name, File f, android.os.CancellationSignal cancel) throws IOException {
        if (cancelled(cancel)) throw new Cancelled();
        InputStream opened;
        try {
            /* as openInputStream does, with the signal handed to the provider (a cloud fetch inside the open stops) */
            android.content.res.AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r", cancel);
            if (afd == null) {
                opened = null;
            } else {
                try {
                    opened = afd.createInputStream();
                } catch (IOException | RuntimeException e) {
                    afd.close();
                    throw e;
                }
            }
        } catch (IOException | RuntimeException e) {
            if (cancelled(cancel)) throw new Cancelled();
            throw new IOException("cannot open " + name, e);      /* gone from the folder, or no permission */
        }
        if (opened == null) {
            if (cancelled(cancel)) throw new Cancelled();       /* some providers answer a cancelled open with null */
            throw new IOException("cannot open " + name);
        }
        try (InputStream raw = new Source(opened, () -> cancelled(cancel))) {
            /* a read blocked in the provider ends; closed on a thread of its own, as cancel() runs on the UI thread
               and a close can block in a hung provider */
            if (cancel != null) cancel.setOnCancelListener(() -> new Thread(() -> {
                try { opened.close(); } catch (IOException | RuntimeException ignored) { }
            }, "copy-cancel").start());
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(raw, 1 << 16);
            in.mark(4);
            byte[] head = new byte[4];
            if (readUpTo(in, head) != 4 || head[0] != 'C' || head[1] != 'H' || head[2] != 'G' || head[3] != '1')
                throw new NotAGame("not a CHGame game: " + name);
            in.reset();
            writeReplacing(in, f, null, MAX_FILE, 0, false);      /* copied again on every run: no fsync */
        } catch (NotAGame | Cancelled e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (cancelled(cancel)) throw new Cancelled();     /* the source was closed under a read */
            if (e instanceof Source.Failed)
                throw new IOException("cannot read " + name, e.getCause());       /* the folder's side: provider, USB, cloud */
            if (e instanceof TooBig) throw new IOException(name + " is too big", e);
            throw new IOException("cannot save " + name, e);       /* this device's side; no private paths in the message */
        } finally {
            if (cancel != null) cancel.setOnCancelListener(null);
        }
    }

    /** The PathLock key for writes to f (one for copyGame and writeReplacing alike). */
    private static String fileKey(File f) { return "file:" + f.getAbsolutePath(); }

    /**
     * A lock per path, held only while someone uses it: writers of one file wait for each other, writers of different
     * files never do, and the table drops a path when its last user leaves (it never grows). take() then
     * lock() (or a timed tryLock) ... unlock(), and release() in a finally. Re-entrant: copyGame holds it while
     * writeReplacing takes it again.
     */
    static final class PathLock extends java.util.concurrent.locks.ReentrantLock {
        private static final Map<String, PathLock> HELD = new HashMap<>();
        private final String key;
        private int users;
        private PathLock(String key) { this.key = key; }
        static PathLock take(String key) {
            synchronized (HELD) {
                PathLock l = HELD.get(key);
                if (l == null) HELD.put(key, l = new PathLock(key));
                l.users++;
                return l;
            }
        }
        void release() {
            synchronized (HELD) { if (--users == 0) HELD.remove(key); }
        }
    }

    /** A stream whose read failures are told apart from write failures (Source.Failed). */
    private static final class Source extends java.io.FilterInputStream {
        static final class Failed extends IOException {
            Failed(IOException cause) { super(cause); }
        }
        private final java.util.function.BooleanSupplier cancelled;
        Source(InputStream in, java.util.function.BooleanSupplier cancelled) { super(in); this.cancelled = cancelled; }
        private void check() throws IOException {
            if (cancelled.getAsBoolean()) throw new Cancelled();
        }
        @Override public int read() throws IOException {
            check();
            try { return super.read(); } catch (IOException e) { throw new Failed(e); }
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            check();
            try { return super.read(b, off, len); } catch (IOException e) { throw new Failed(e); }
        }
        @Override public int available() throws IOException {
            try { return super.available(); } catch (IOException e) { throw new Failed(e); }
        }
        @Override public long skip(long n) throws IOException {
            try { return super.skip(n); } catch (IOException e) { throw new Failed(e); }
        }
        @Override public void close() throws IOException {
            try { super.close(); } catch (IOException e) { throw new Failed(e); }
        }
    }

    /** A cancelled copy (the old file kept); not an error to show. */
    static final class Cancelled extends IOException {
        Cancelled() { super("cancelled"); }
    }

    /** A file over the size limit given to writeReplacing. */
    static final class TooBig extends IOException {
        TooBig() { super("file too big"); }
    }

    /** The file read is not a game (shown as is). */
    private static final class NotAGame extends IOException {
        NotAGame(String m) { super(m); }
    }

    /** Flushes a finished file to the disk (one written by the core, not through writeReplacing). */
    static void syncFile(File f) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f, true)) { o.getFD().sync(); }
    }

    /**
     * Puts the finished file tmp in place of f in one atomic step (rename(2) on one file system): f is the old file or
     * the new one, never missing or half-written. A directory at f is left alone (the move fails). The temporary file
     * is removed if the move fails. Shared by every temp-then-rename in the app (copyGame, copyIn, saves, resume points).
     */
    static void replaceWith(File tmp, File f) throws IOException {
        try {
            java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * Writes in (at most max bytes, else TooBig) to dst: through tmp (null: <dst>.tmp, under that file's PathLock), synced to
     * the disk when sync (saves, restored states and card files the player may own; not game copies or game data,
     * which are copied again), stamped with mtime when it is above 0, then moved over dst atomically (replaceWith).
     * A failure leaves dst as it was and removes the temporary file. The one write path for card files, games and saves.
     */
    static void writeReplacing(InputStream in, File dst, File tmp, long max, long mtime, boolean sync) throws IOException {
        if (tmp != null) { writeLocked(in, dst, tmp, max, mtime, sync); return; }
        PathLock lock = PathLock.take(fileKey(dst));      /* <dst>.tmp: one writer per file */
        try {
            lock.lock();
            try { writeLocked(in, dst, null, max, mtime, sync); } finally { lock.unlock(); }
        } finally { lock.release(); }
    }

    private static void writeLocked(InputStream in, File dst, File tmp, long max, long mtime, boolean sync) throws IOException {
        if (in == null) throw new IOException("cannot open");
        dst.getParentFile().mkdirs();
        if (tmp == null) tmp = new File(dst.getPath() + ".tmp");      /* one name: a copy cut short is overwritten next time */
        tmp.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            for (int r; (r = in.read(buf)) > 0; ) {
                total += r;
                if (total > max) throw new TooBig();
                out.write(buf, 0, r);
            }
            if (sync) out.getFD().sync();     /* on the disk before the rename: a power cut leaves the old file or the new */
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            throw e;
        }
        if (mtime > 0) tmp.setLastModified(mtime);      /* the rename keeps it: content and date change together */
        replaceWith(tmp, dst);
    }

    interface Body { void write(OutputStream out) throws IOException; }

    /**
     * Replaces the content of an existing document (a game, a card file, a save in the picked folder; SAF has no
     * rename-over) without ever leaving it half written: the old content is kept in the cache first, and written back
     * if the new write fails. The document keeps its name and place (so a game's saves stay with it).
     */
    static void overwrite(Context c, Uri doc, Body body) throws IOException {
        ContentResolver cr = c.getContentResolver();
        File back = File.createTempFile("keep", ".bin", c.getCacheDir());
        try {
            try (InputStream in = cr.openInputStream(doc); OutputStream o = new FileOutputStream(back)) {
                if (in == null) throw new IOException("cannot read " + doc.getLastPathSegment());
                copy(in, o);
            }       /* a failed read: thrown here, before the document is touched */
            try (OutputStream out = cr.openOutputStream(doc, "wt")) {
                if (out == null) throw new IOException("cannot write to the games folder");
                body.write(out);
            } catch (IOException | RuntimeException e) {
                try (InputStream in = new FileInputStream(back); OutputStream out = cr.openOutputStream(doc, "wt")) {
                    if (out != null) copy(in, out);       /* the old content back */
                } catch (IOException | RuntimeException ignored) {
                }
                throw e;
            }
        } finally {
            back.delete();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null) throw new IOException("cannot open");
        byte[] buf = new byte[1 << 16];
        long total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            total += n;
            if (total > MAX_FILE) throw new IOException("file too big");
            out.write(buf, 0, n);
        }
    }

    private Files() {}
}
