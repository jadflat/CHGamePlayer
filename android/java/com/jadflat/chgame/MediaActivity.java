package com.jadflat.chgame;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pictures and clips in one place: the games folder's CHGame screenshots
 * (share, export or delete), the save-state pictures the app keeps (copy one
 * to the screenshots folder to share it, or export it), and every game's cart
 * picture as a 512x512 PNG. Lists load off the UI thread, then the page is made.
 */
public class MediaActivity extends Page {
    private static final String TITLE = "Screenshots, clips and pictures", SHOTS = "CHGame screenshots";
    private static final Pattern STATE_PNG = Pattern.compile("(.+)\\.(\\d+)\\.state\\.png");
    private static final int PICK = 3;      /* OptionsActivity uses 1 and 2 */

    /** A screenshot (PNG) or clip (GIF) in the games folder. */
    private static final class Shot {
        final String name, mime;
        final Uri uri;
        final long modified;
        Bitmap thumb;
        Shot(String name, String mime, Uri uri, long modified) {
            this.name = name; this.mime = mime; this.uri = uri; this.modified = modified;
        }
    }

    /** A save-state picture: <game> slot N. */
    private static final class Pic {
        final File file;
        final String game;
        final int slot;
        Bitmap thumb;
        Pic(File file, String game, int slot) { this.file = file; this.game = game; this.slot = slot; }
        String label() { return game + " slot " + slot; }
    }

    /** Opens the bytes of a file, from the games folder or the app. */
    private interface Opener { InputStream open() throws IOException; }

    /** Writes a file's bytes into an open stream. */
    private interface Body { void write(OutputStream out) throws IOException; }

    /** One file to write into the picked folder. */
    private static final class Item {
        final String name, mime;
        final Body body;
        Item(String name, String mime, Body body) { this.name = name; this.mime = mime; this.body = body; }
    }

    private Uri tree;                                 /* the games folder, or null */
    private final List<Shot> shots = new ArrayList<>();
    private final List<Pic> pics = new ArrayList<>();
    private List<Item> pending;                       /* what the picked folder gets, set before the picker opens */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        build();
    }

    @Override
    void build() {
        header(TITLE);
        section("");
        row("Loading…", null, null);
        focus();
        new Thread(this::load, "media").start();
    }

    /** The lists and their thumbnails, off the UI thread; then the page is made from them. */
    private void load() {
        ContentResolver cr = getContentResolver();
        int px = Ui.dp(this, 64);
        Uri t = tree();
        List<Shot> s = t == null ? new ArrayList<Shot>() : shotList(cr, t);
        for (Shot x : s) x.thumb = thumbnail(() -> cr.openInputStream(x.uri), px);
        List<Pic> p = picList();
        for (Pic x : p) x.thumb = thumbnail(() -> new FileInputStream(x.file), px);
        runOnUiThread(() -> {
            if (isFinishing()) return;
            tree = t;
            shots.clear();
            shots.addAll(s);
            pics.clear();
            pics.addAll(p);
            show();
        });
    }

    /** The page from the lists: export all, then the three sections. */
    private void show() {
        header(TITLE);
        section("");
        row("Export all…", "Every screenshot, clip and save-state picture into a folder you choose", this::exportAll);

        section("Screenshots and clips");
        if (tree == null) row("No games folder", "Pick one in Options", null);
        else if (shots.isEmpty()) row("None yet", "Screenshots and clips you make in a game are kept here", null);
        for (Shot s : shots) thumbRow(s.name, when(s.modified), s.thumb, () -> shotMenu(s));

        section("Save-state pictures");
        if (pics.isEmpty()) row("None yet", "Save a state in a game and its picture is kept here", null);
        for (Pic p : pics) thumbRow(p.label(), when(p.file.lastModified()), p.thumb, () -> picMenu(p));
        TextView note = Ui.text(this, "These stay inside the app, which can't share them: copy one to CHGame "
                + "screenshots to share it.", 14, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        note.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        page.addView(note);

        section("Cart pictures");
        row("Export every game's cart picture", "Each game's picture as a 512x512 PNG, named after the game",
                this::exportCarts);
        focus();
    }

    /** A row with its 64dp thumbnail to the left of the text, as SavesActivity's slot rows have. */
    private void thumbRow(String title, String summary, Bitmap thumb, Runnable action) {
        row(title, summary, action);
        LinearLayout section = (LinearLayout) page.getChildAt(page.getChildCount() - 1);
        LinearLayout r = (LinearLayout) section.getChildAt(section.getChildCount() - 1);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(thumb);
        img.setContentDescription(title + " picture");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        lp.setMargins(0, 0, Ui.dp(this, 16), 0);
        r.addView(img, 0, lp);
    }

    /** A screenshot or clip: share, export or delete it. */
    private void shotMenu(Shot s) {
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(s.name)
                .setItems(new String[] { "Share", "Export…", "Delete" }, (d, w) -> {
                    if (w == 0) share(s);
                    else if (w == 1) export(Collections.singletonList(shotItem(s)));
                    else Saves.confirm(this, "Delete " + s.name + "? This can't be undone.", () -> deleteShot(s));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A save-state picture: copy it to the screenshots folder, or export it. */
    private void picMenu(Pic p) {
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(p.label())
                .setItems(new String[] { "Copy to CHGame screenshots", "Export…" }, (d, w) -> {
                    if (w == 0) copyPic(p);
                    else export(Collections.singletonList(picItem(p)));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void share(Shot s) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(s.mime);
        i.putExtra(Intent.EXTRA_STREAM, s.uri);
        i.setClipData(ClipData.newRawUri("", s.uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share " + s.name));
    }

    private void deleteShot(Shot s) {
        ContentResolver cr = getContentResolver();
        new Thread(() -> {
            String msg;
            try {
                DocumentsContract.deleteDocument(cr, s.uri);
                msg = "Deleted " + s.name;
            } catch (IOException | RuntimeException e) {
                msg = "Not deleted";
            }
            say(msg);
            runOnUiThread(this::build);
        }, "delete").start();
    }

    /** A state picture into CHGame screenshots, under a free name, so it can be shared from there. */
    private void copyPic(Pic p) {
        Uri t = tree;
        if (t == null) {
            Saves.toast(this, "Pick a games folder in Options first");
            return;
        }
        new Thread(() -> {
            String msg;
            try {
                ContentResolver cr = getContentResolver();
                String dir = SnapMaker.dir(cr, t, DocumentsContract.getTreeDocumentId(t), SHOTS);
                create(cr, t, dir, picItem(p));
                msg = "Copied to CHGame screenshots";
            } catch (IOException | RuntimeException e) {
                msg = "Not copied";
            }
            say(msg);
            runOnUiThread(this::build);
        }, "copy").start();
    }

    private void exportAll() {
        List<Item> items = new ArrayList<>();
        for (Shot s : shots) items.add(shotItem(s));
        for (Pic p : pics) items.add(picItem(p));
        if (items.isEmpty()) {
            Saves.toast(this, "Nothing to export");
            return;
        }
        export(items);
    }

    /** Every game's cart picture, listed off the UI thread, then the picker opens. */
    private void exportCarts() {
        Uri t = tree;
        if (t == null) {
            Saves.toast(this, "Pick a games folder in Options first");
            return;
        }
        new Thread(() -> {
            ContentResolver cr = getContentResolver();
            List<Item> items = new ArrayList<>();
            for (Files.Entry e : Files.list(cr, t)) {
                if (!Files.isGame(e.path)) continue;
                String file = e.path.substring(e.path.lastIndexOf('/') + 1);
                items.add(new Item(Saves.base(file) + ".png", "image/png", out -> writeCart(cr, e.uri, out)));
            }
            if (items.isEmpty()) say("No games in the games folder");
            else runOnUiThread(() -> export(items));
        }, "carts").start();
    }

    /** Remembers what to write, then asks for a folder; onActivityResult writes it. */
    private void export(List<Item> items) {
        pending = items;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        List<Item> items = pending;
        pending = null;
        if (items == null || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri dest = data.getData();
        new Thread(() -> {
            ContentResolver cr = getContentResolver();
            int n = 0;
            for (Item it : items) {
                try {
                    create(cr, dest, DocumentsContract.getTreeDocumentId(dest), it);
                    n++;
                } catch (IOException | RuntimeException e) {
                    /* this one is skipped; the rest still go */
                }
            }
            say("Exported " + n + " files");
        }, "export").start();
    }

    private Item shotItem(Shot s) {
        ContentResolver cr = getContentResolver();
        return new Item(s.name, s.mime, out -> copy(() -> cr.openInputStream(s.uri), out));
    }

    private static Item picItem(Pic p) {
        return new Item(p.label() + ".png", "image/png", out -> copy(() -> new FileInputStream(p.file), out));
    }

    /** A new document named item.name in dirId, written by its body; " (2)" and so on if the name is taken. */
    private static void create(ContentResolver cr, Uri tree, String dirId, Item item) throws IOException {
        String name = item.name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; SnapMaker.child(cr, tree, dirId, name) != null; i++) name = stem + " (" + i + ")" + ext;
        Uri target = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, dirId),
                item.mime, name);
        if (target == null) throw new IOException("cannot write " + name);
        try (OutputStream out = cr.openOutputStream(target, "wt")) {
            if (out == null) throw new IOException("cannot write " + name);
            item.body.write(out);
        } catch (IOException | RuntimeException e) {
            /* a failed write leaves no empty file behind */
            try { DocumentsContract.deleteDocument(cr, target); } catch (IOException | RuntimeException ignored) { }
            throw e;
        }
    }

    /** A game's cart picture, scaled 4x (nearest neighbour) to a 512x512 PNG. */
    private static void writeCart(ContentResolver cr, Uri game, OutputStream out) throws IOException {
        Cart c = Cart.read(cr, game);
        if (c == null || c.picture == null) throw new IOException("no picture");
        Bitmap big = Bitmap.createScaledBitmap(c.picture, SnapMaker.SIZE, SnapMaker.SIZE, false);
        if (!big.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("cannot write");
    }

    /** The screenshots and clips in CHGame screenshots, newest first. */
    private static List<Shot> shotList(ContentResolver cr, Uri tree) {
        List<Shot> out = new ArrayList<>();
        String dir = SnapMaker.child(cr, tree, DocumentsContract.getTreeDocumentId(tree), SHOTS);
        if (dir == null) return out;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir);
        try (Cursor c = cr.query(kids, new String[] { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_LAST_MODIFIED }, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), name = Files.safeName(c.getString(1));
                String mime = name == null ? null : mimeOf(name);
                if (id == null || mime == null) continue;
                out.add(new Shot(name, mime, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        c.isNull(2) ? 0 : c.getLong(2)));
            }
        } catch (RuntimeException e) {
            /* the folder will not list: none */
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") ? "image/png" : n.endsWith(".gif") ? "image/gif" : null;
    }

    /** The save-state pictures in the app's states folder, by game then slot. */
    private List<Pic> picList() {
        List<Pic> out = new ArrayList<>();
        File[] files = new File(getFilesDir(), "states").listFiles();
        if (files == null) return out;
        for (File f : files) {
            Matcher m = STATE_PNG.matcher(f.getName());
            if (f.isFile() && m.matches()) out.add(new Pic(f, m.group(1), Integer.parseInt(m.group(2))));
        }
        Collections.sort(out, (a, b) -> a.game.equals(b.game) ? a.slot - b.slot : a.game.compareTo(b.game));
        return out;
    }

    /** A picture's thumbnail, sampled down to about px pixels; null if it won't decode. */
    private static Bitmap thumbnail(Opener src, int px) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = src.open()) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            try (InputStream in = src.open()) { return BitmapFactory.decodeStream(in, null, d); }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** src's bytes into out. */
    private static void copy(Opener src, OutputStream out) throws IOException {
        try (InputStream in = src.open()) {
            if (in == null) throw new IOException("cannot open");
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
    }

    private static String when(long ms) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms));
    }

    /** A message from any thread. */
    private void say(String s) {
        runOnUiThread(() -> Saves.toast(this, s));
    }

    private Uri tree() {
        String s = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
        return null;
    }
}
