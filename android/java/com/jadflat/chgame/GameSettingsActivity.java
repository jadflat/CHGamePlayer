package com.jadflat.chgame;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * One game's own settings, over the ones in Options: its sound style and
 * volume (some games suit the piezo sound, some the bare pin). "Same as
 * Options" keeps the game following Options.
 */
public class GameSettingsActivity extends Page {
    private SharedPreferences prefs;
    private String file, title;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        file = getIntent().getStringExtra("file");
        title = getIntent().getStringExtra("title");
        if (file == null) { finish(); return; }
        if (title == null) title = Saves.base(file);
        build();
        new Thread(() -> {
            android.net.Uri m = uriPref("mediaTree", true);
            if (m == null) return;
            boolean has = SnapMaker.names(getContentResolver(), m, "videos").contains(Saves.base(file) + ".mp4");
            runOnUiThread(() -> { if (has != hasClip) { hasClip = has; if (!making && !isFinishing()) build(); } });
        }).start();
    }

    @Override
    void build() {
        header("Settings: " + title);
        section("Sound");
        String sound = Options.gameSound(prefs, file), volume = Options.gameVolume(prefs, file);
        row("Sound", sound == null ? "Same as Options (" + Options.SOUND_NAMES[indexOf(Options.SOUNDS, Options.sound(prefs))] + ")"
                : Options.SOUND_NAMES[indexOf(Options.SOUNDS, sound)], () -> {
            String[] items = new String[Options.SOUNDS.length + 1];
            items[0] = "Same as Options";
            for (int i = 0; i < Options.SOUNDS.length; i++) items[i + 1] = Options.SOUND_NAMES[i];
            pick("Sound for " + title, items, sound == null ? 0 : indexOf(Options.SOUNDS, sound) + 1,
                    i -> Options.setGame(prefs, file, "sound", i == 0 ? null : Options.SOUNDS[i - 1]));
        });
        row("Volume", volume == null ? "Same as Options (" + Options.volume(prefs) + ")" : volume, () -> {
            String[] items = new String[Options.VOLUMES.length + 1];
            items[0] = "Same as Options";
            System.arraycopy(Options.VOLUMES, 0, items, 1, Options.VOLUMES.length);
            pick("Volume for " + title, items, volume == null ? 0 : indexOf(Options.VOLUMES, volume) + 1,
                    i -> Options.setGame(prefs, file, "volume", i == 0 ? null : Options.VOLUMES[i - 1]));
        });
        if (sound != null || volume != null)
            row("Back to the Options settings", null, () -> {
                Options.setGame(prefs, file, "sound", null);
                Options.setGame(prefs, file, "volume", null);
                build();
            });
        section("Screen");
        String rotate = prefs.getString("rotate:" + file, "auto");
        String[] rotKeys = { "auto", "portrait", "landscape" };
        String[] rotNames = { "Follow the device", "Always upright", "Always sideways" };
        row("Screen rotation", rotNames[indexOf(rotKeys, rotate)], () ->
                pick("Screen rotation for " + title, rotNames, indexOf(rotKeys, rotate),
                        i -> prefs.edit().putString("rotate:" + file, rotKeys[i]).apply()));
        String pv = prefs.getString("preview:" + file, "default");
        String[] pvKeys = { "default", "live", "video", "picture" };
        String[] pvNames = { "Default (Options)", "Live", "Video", "Picture" };
        row("Preview", pvNames[indexOf(pvKeys, pv)], () ->
                pick("Preview for " + title, pvNames, indexOf(pvKeys, pv), i -> {
                    if (i == 0) prefs.edit().remove("preview:" + file).apply();
                    else prefs.edit().putString("preview:" + file, pvKeys[i]).apply();
                }));
        section("Buttons");
        row("Buttons", prefs.contains("keymap:" + file) ? "Own mapping" : "The main mapping", () ->
                startActivity(new Intent(this, MapButtonsActivity.class).putExtra("file", file).putExtra("title", title)));
        section("ES-DE media");
        row(hasClip ? "Remake preview clip" : "Make preview clip", clipStatus != null ? clipStatus
                : "Records a 15 s video, screenshot and cover for this game", this::makeClip);
        section("Game");
        String path = getIntent().getStringExtra("path") == null ? file : getIntent().getStringExtra("path");
        boolean hid = Options.hidden(prefs).contains(path);
        row(hid ? "Show game in the list again" : "Hide game", hid ? "Hidden now" : "Leave it out of the list (Options shows hidden games)", () -> {
            boolean now = Options.toggleHidden(prefs, path);
            android.widget.Toast.makeText(this, title + (now ? " hidden. Options shows hidden games." : " shown again"),
                    android.widget.Toast.LENGTH_LONG).show();
            build();
        });
        String repo = Updates.repoFor(this, prefs, file);
        if (repo != null) row("Open on GitHub", "github.com/" + repo, () -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/" + repo)));
            } catch (Exception ex) {
                android.widget.Toast.makeText(this, "No browser to open it", android.widget.Toast.LENGTH_LONG).show();
            }
        });
        row("Remove game", "Delete the game file; you choose whether it can be downloaded again", this::confirmRemove);
        focus();
    }

    /** Asks, then deletes this game's file from the games folder; it may still be offered for download. */
    private void confirmRemove() {
        new android.app.AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Remove " + title + "?")
                .setMessage("The game file is deleted from the games folder. Its saves stay.\n\nKeep it available to download again?")
                .setPositiveButton("Keep available", (dlg, w) -> removeGame(true))
                .setNegativeButton("Don't offer", (dlg, w) -> removeGame(false))
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void removeGame(boolean offerAgain) {
        android.net.Uri t = uriPref("tree", true);
        if (t == null) {
            android.widget.Toast.makeText(this, "Choose the games folder in Options first", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            boolean ok = false, there = false;
            try {
                for (Files.Entry e : Files.list(getContentResolver(), t))
                    if (Files.isGame(e.path) && e.path.substring(e.path.lastIndexOf('/') + 1).equals(file)) {
                        there = true;
                        ok = android.provider.DocumentsContract.deleteDocument(getContentResolver(), e.uri);
                        break;
                    }
            } catch (Exception ignored) { }
            /* a game that is only offered (no file here): "Don't offer" drops it from the list, "Keep available" leaves it */
            if (!there && Updates.offered(prefs, file, !offerAgain)) ok = true;
            final boolean done = ok;
            if (done && there) Updates.removed(this, prefs, file, title, offerAgain);
            final String msg = !done ? "Could not remove " + title : there ? title + " removed"
                    : offerAgain ? title + " is not installed: it stays in the list to download" : title + " will not be offered again";
            runOnUiThread(() -> {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                if (done) finish();
            });
        }).start();
    }

    private volatile boolean making;
    private String clipStatus;
    private boolean hasClip;

    private android.net.Uri uriPref(String key, boolean write) {
        String s = prefs.getString(key, null);
        if (s == null) return null;
        android.net.Uri u = android.net.Uri.parse(s);
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && (write ? p.isWritePermission() : p.isReadPermission())) return u;
        return null;
    }

    /** Makes this one game's ES-DE clip, screenshot and cover with SnapMaker, on a thread. */
    private void makeClip() {
        if (making) return;
        android.net.Uri t = uriPref("tree", false), m = uriPref("mediaTree", true);
        if (t == null || m == null) {
            android.widget.Toast.makeText(this, "Choose the games folder and the ES-DE media folder in Options first",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        making = true;
        clipStatus = "Making the clip, about a minute...";
        android.widget.Toast.makeText(this, clipStatus, android.widget.Toast.LENGTH_LONG).show();
        build();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String err;
            Preview.gameRunning = true;
            try {
                Files.Entry found = null;
                for (Files.Entry e : Files.list(getContentResolver(), t))
                    if (Files.isGame(e.path) && e.path.substring(e.path.lastIndexOf('/') + 1).equals(file)) { found = e; break; }
                if (found == null) err = "game not found";
                else {
                    Cart cart = Cart.read(getContentResolver(), found.uri);
                    synchronized (Preview.CORE) {
                        err = SnapMaker.make(this, m, found, file, cart, () -> false);
                    }
                }
            } finally {
                Preview.gameRunning = false;
            }
            final String e2 = err;
            runOnUiThread(() -> {
                making = false;
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                clipStatus = e2 == null ? "Done. Restart ES-DE to see it." : "Failed: " + e2;
                if (e2 == null) hasClip = true;
                android.widget.Toast.makeText(this, e2 == null ? "Preview clip done" : clipStatus,
                        android.widget.Toast.LENGTH_LONG).show();
                if (!isFinishing()) build();
            });
        }, "snap1").start();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        build();
    }

    interface IntCallback { void run(int i); }

    private void pick(String t, String[] items, int current, IntCallback cb) {
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(t)
                .setSingleChoiceItems(items, current, (d, i) -> { d.dismiss(); cb.run(i); build(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return 0;
    }
}
