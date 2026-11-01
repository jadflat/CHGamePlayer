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

    static boolean isGame(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".chg");
    }

    /** Every file under the tree, down to MAX_DEPTH folders. */
    static List<Entry> list(ContentResolver cr, Uri tree) {
        List<Entry> out = new ArrayList<>();
        walk(cr, tree, DocumentsContract.getTreeDocumentId(tree), "", 0, out);
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String docId, String prefix, int depth, List<Entry> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED };
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = safeName(c.getString(1));
                if (id == null || name == null) continue;
                if (Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    if (depth < MAX_DEPTH) walk(cr, tree, id, prefix + name + "/", depth + 1, out);
                } else {
                    out.add(new Entry(prefix + name, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            c.isNull(3) ? -1 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
                }
            }
        } catch (RuntimeException e) {
            /* a folder the provider will not list: skip it */
        }
    }

    static File sdFolder(Context ctx) {
        return new File(ctx.getFilesDir(), "sys/chgame/sdcard");
    }

    /** The picked folder's files (not the games) into the private SD folder, where they differ. */
    static Map<String, Long> importCard(Context ctx, Uri tree) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        File sd = sdFolder(ctx);
        Map<String, Long> stamps = new HashMap<>();
        for (Entry e : list(cr, tree)) {
            if (isGame(e.path) || isSave(e.path) || e.path.startsWith("screenshots/") || e.size > MAX_FILE) continue;
            try {
                File f = inside(sd, e.path);
                /* a .DAT is the game's data, never written by the game: it is current only if it is the very file
                   imported (same size, stamp and header); other files may have been changed by the game, so a newer copy stays */
                boolean data = e.path.toUpperCase(Locale.ROOT).endsWith(".DAT") && !e.path.toUpperCase(Locale.ROOT).contains("SAVE");   /* a save is written by the game: the newer copy stays */
                boolean same = f.isFile() && f.length() == e.size
                        && (data ? f.lastModified() == e.modified && sameHead(cr, e.uri, f) : f.lastModified() >= e.modified);
                if (!same) {
                    f.getParentFile().mkdirs();
                    try (InputStream in = cr.openInputStream(e.uri); OutputStream out = new FileOutputStream(f)) {
                        copy(in, out);
                    }
                    if (data && e.modified > 0) f.setLastModified(e.modified);
                }
                stamps.put(e.path, f.lastModified());
            } catch (IOException | RuntimeException ex) {
                /* this file only: the others still go on the card */
            }
        }
        return stamps;
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

    /** The .DAT files this game names (its .CHG holds the card file name, as "STARLIFTDAT" or "STARLIFT.DAT")
        that the picked folder has: copied in whatever the stamps say if the private copy is missing or differs. */
    static void ensureData(Context ctx, File game) {
        try {
            String t = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString("tree", null);
            if (t == null) return;
            byte[] all;
            try (InputStream in = new FileInputStream(game)) {
                all = new byte[(int) Math.min(game.length(), 1 << 20)];
                int n = 0;
                for (int r; n < all.length && (r = in.read(all, n, all.length - n)) > 0; ) n += r;
            }
            String text = new String(all, java.nio.charset.StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
            ContentResolver cr = ctx.getContentResolver();
            File sd = sdFolder(ctx);
            for (Entry e : list(cr, Uri.parse(t))) {
                String n = e.path.substring(e.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
                if (!n.endsWith(".DAT") || e.size > MAX_FILE) continue;
                String bare = n.substring(0, n.length() - 4);
                if (!text.contains(n) && !text.contains(bare + "DAT")) continue;
                File f = inside(sd, e.path);
                if (f.isFile() && f.length() == e.size && sameHead(cr, e.uri, f)) continue;
                f.getParentFile().mkdirs();
                try (InputStream in = cr.openInputStream(e.uri); OutputStream out = new FileOutputStream(f)) {
                    copy(in, out);
                }
                if (e.modified > 0) f.setLastModified(e.modified);
            }
        } catch (Exception ignored) { }
    }

    /** The games folder's .DAT files into the SD folder, for a game run without GameActivity (preview, clips); quiet on failure. */
    static void importData(Context ctx) {
        try {
            String t = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString("tree", null);
            if (t != null) importCard(ctx, Uri.parse(t));
        } catch (Exception ignored) { }
    }

    /** Files the game changed or made in the SD folder, back into the picked folder. */
    static void exportCard(Context ctx, Uri tree, Map<String, Long> stamps) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        File sd = sdFolder(ctx);
        Map<String, Uri> existing = new HashMap<>();
        for (Entry e : list(cr, tree)) existing.put(e.path, e.uri);
        List<String> changed = new ArrayList<>();
        collect(sd, "", 0, changed);
        for (String rel : changed) {
            File f = inside(sd, rel);
            Long was = stamps.get(rel);
            if (was != null && f.lastModified() == was) continue;
            Uri target = existing.get(rel);
            if (target == null) {
                if (rel.contains("/")) continue;    /* new files only at the top level */
                target = DocumentsContract.createDocument(cr,
                        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                        "application/octet-stream", rel);
                if (target == null) continue;
            }
            try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(target, "wt")) {
                if (out != null) copy(in, out);
            }
            stamps.put(rel, f.lastModified());
        }
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
        String safe = safeName(name);
        if (safe == null || !isGame(safe)) throw new IOException("not a .CHG: " + name);
        File dir = new File(ctx.getFilesDir(), "games");
        dir.mkdirs();
        File f = inside(dir, safe);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
            copy(in, out);
        }
        return f;
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
