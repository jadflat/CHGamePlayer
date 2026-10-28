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
import java.util.Date;

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

    static void confirm(Activity a, String q, Runnable yes) {
        new AlertDialog.Builder(a, Ui.dialogTheme()).setMessage(q)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("No", null)
                .show();
    }

    static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    private Saves() {}
}
