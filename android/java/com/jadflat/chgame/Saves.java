package com.jadflat.chgame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A game's saves, all in the app's private storage:
 *   saves/<game>.sav           what the game itself wrote to its flash (the
 *                              emulator's format, the same file RetroArch's core writes)
 *   states/<game>.<n>.state    save states, slots 1 to SLOTS
 * (SavesActivity shows them; export and import here move a .sav through the
 * games folder, to back it up or to or from RetroArch).
 */
final class Saves {
    static final int SLOTS = 4;
    private static final long MAX_SAV = 64 << 10;     /* the whole user flash is 62 KB */
    private static final String BACKUP = "CHGame saves";   /* in the games folder */

    static String base(String gameFile) {
        int dot = gameFile.lastIndexOf('.');
        return dot > 0 ? gameFile.substring(0, dot) : gameFile;
    }

    static File saveFile(Activity a, String gameFile) throws IOException {
        File d = new File(a.getFilesDir(), "saves");
        d.mkdirs();
        return Files.inside(d, base(gameFile) + ".sav");
    }

    static File stateFile(Activity a, String gameFile, int slot) throws IOException {
        File d = new File(a.getFilesDir(), "states");
        d.mkdirs();
        return Files.inside(d, base(gameFile) + "." + slot + ".state");
    }

    /** The 128x128 picture saved beside a state, shown in the slot list. */
    static File pictureFile(File state) {
        return new File(state.getPath() + ".png");
    }

    /** A slot's state and its picture. */
    static void deleteState(File state) {
        state.delete();
        pictureFile(state).delete();
    }

    /** The resume point, written when a game is left. */
    static File autoFile(Activity a, String gameFile) throws IOException {
        File d = new File(a.getFilesDir(), "states");
        d.mkdirs();
        return Files.inside(d, base(gameFile) + ".auto.state");
    }

    static String describe(File f) {
        if (!f.isFile()) return "empty";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(f.lastModified()));
    }

    /** <game>.sav into the top of the games folder, replacing one there. */
    static boolean exportSave(Activity a, Uri tree, File sav) {
        if (tree == null || !sav.isFile()) return false;
        ContentResolver cr = a.getContentResolver();
        try {
            Uri target = null;
            for (Files.Entry e : Files.list(cr, tree))
                if (e.path.equals(sav.getName())) target = e.uri;
            if (target == null)
                target = DocumentsContract.createDocument(cr,
                        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                        "application/octet-stream", sav.getName());
            if (target == null) return false;
            try (InputStream in = new FileInputStream(sav); OutputStream out = cr.openOutputStream(target, "wt")) {
                if (out == null) return false;
                copy(in, out);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** <game>.sav from the top of the games folder (or its saves/ folder). */
    static boolean importSave(Activity a, Uri tree, File sav) {
        if (tree == null) return false;
        ContentResolver cr = a.getContentResolver();
        Uri src = null;
        for (Files.Entry e : Files.list(cr, tree))
            if (e.path.equals(sav.getName()) || (src == null && e.path.equals("saves/" + sav.getName()))) src = e.uri;
        if (src == null) return false;
        File tmp = new File(sav.getPath() + ".tmp");
        try (InputStream in = cr.openInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) return false;
            copy(in, out);
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(sav);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            total += n;
            if (total > MAX_SAV) throw new IOException("not a CHGame save: too big");
            out.write(buf, 0, n);
        }
    }

    /** Every save, state and picture into CHGame saves in the games folder; returns how many. */
    static int exportAll(Activity a, Uri tree) {
        if (tree == null) return 0;
        ContentResolver cr = a.getContentResolver();
        try {
            String dir = SnapMaker.dir(cr, tree, DocumentsContract.getTreeDocumentId(tree), BACKUP);
            return copyOut(cr, tree, dir, new File(a.getFilesDir(), "saves"), "")
                    + copyOut(cr, tree, dir, new File(a.getFilesDir(), "states"), "");
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** dir's files into the picked folder's dirId, subfolders flattened with '_'. */
    private static int copyOut(ContentResolver cr, Uri tree, String dirId, File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                n += copyOut(cr, tree, dirId, f, prefix + f.getName() + "_");
            } else if (f.isFile()) {
                try {
                    String mime = f.getName().endsWith(".png") ? "image/png" : "application/octet-stream";
                    SnapMaker.put(cr, tree, dirId, prefix + f.getName(), mime, f);
                    n++;
                } catch (IOException | RuntimeException e) {
                    /* this one is skipped; the rest still go */
                }
            }
        }
        return n;
    }

    /** Copies CHGame saves back: the new files at once, the differing ones after asking. */
    static void importAll(Activity a, Uri tree, Runnable done) {
        new Thread(() -> {
            List<Files.Entry> fresh = new ArrayList<>(), differ = new ArrayList<>();
            if (tree != null) {
                String prefix = BACKUP + "/";
                for (Files.Entry e : Files.list(a.getContentResolver(), tree)) {
                    if (!e.path.startsWith(prefix)) continue;
                    File local = localFile(a, e.path.substring(prefix.length()));
                    if (local == null) continue;
                    if (!local.isFile()) fresh.add(e);
                    else if (e.size != local.length() || e.modified != local.lastModified()) differ.add(e);
                }
            }
            final int got = copyBack(a, fresh);
            if (differ.isEmpty()) {
                report(a, got, fresh.size(), done);
                return;
            }
            a.runOnUiThread(() -> confirm(a, "Replace " + differ.size() + " saves on this device with the copies in CHGame saves?",
                    () -> new Thread(() -> report(a, got + copyBack(a, differ), fresh.size() + differ.size(), done)).start(),
                    () -> report(a, got, fresh.size(), done)));
        }, "import").start();
    }

    /** The local file a backup name goes to, or null if it is not a save, state or picture. */
    private static File localFile(Activity a, String name) {
        if (Files.safeName(name) == null) return null;
        try {
            if (name.endsWith(".sav")) return Files.inside(new File(a.getFilesDir(), "saves"), name);
            if (name.endsWith(".state") || name.endsWith(".state.png"))
                return Files.inside(new File(a.getFilesDir(), "states"), name);
        } catch (IOException e) {
            /* not a plain name: skipped */
        }
        return null;
    }

    private static int copyBack(Activity a, List<Files.Entry> list) {
        String prefix = BACKUP + "/";
        int n = 0;
        for (Files.Entry e : list) {
            File local = localFile(a, e.path.substring(prefix.length()));
            if (local != null && copyIn(a.getContentResolver(), e.uri, local)) n++;
        }
        return n;
    }

    /** A file from the picked folder over dst, through a .tmp so a failed copy leaves dst alone. */
    private static boolean copyIn(ContentResolver cr, Uri src, File dst) {
        dst.getParentFile().mkdirs();
        File tmp = new File(dst.getPath() + ".tmp");
        try (InputStream in = cr.openInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("cannot open");
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(dst);
    }

    private static void report(Activity a, int got, int wanted, Runnable done) {
        a.runOnUiThread(() -> {
            toast(a, wanted == 0 ? "Nothing imported" : got == wanted ? "Imported " + got + " saves"
                    : "Imported " + got + " of " + wanted + " saves");
            if (done != null) done.run();
        });
    }

    static void confirm(Activity a, String q, Runnable yes) {
        confirm(a, q, yes, null);
    }

    /** no runs for No, or when the dialog is closed another way. */
    static void confirm(Activity a, String q, Runnable yes, Runnable no) {
        new AlertDialog.Builder(a, Ui.dialogTheme()).setMessage(q)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("No", (d, w) -> { if (no != null) no.run(); })
                .setOnCancelListener(d -> { if (no != null) no.run(); })
                .show();
    }

    static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private Saves() {}
}
