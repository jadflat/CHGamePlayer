package com.jadflat.chgame;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * Options, laid out like Android's own Settings: sections, and rows with a
 * title and what it is set to now. Everything that used to be a button on the
 * game list is here: the games folder, the selected game's saves, button
 * mapping, hidden games, and how games play.
 */
public class OptionsActivity extends Page {
    private static final int PICK_FOLDER = 1, PICK_MEDIA = 2;

    private SharedPreferences prefs;

    /* back from Map buttons or Saves: show what changed */
    @Override
    protected void onRestart() {
        super.onRestart();
        build();
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        build();
        /* from the list's first step: straight to Android's folder picker */
        if (saved == null && getIntent().getBooleanExtra("pickOnly", false)) pickFolder();
    }

    /* ---------------------------------------------------------------- */
    /* The page                                                           */
    /* ---------------------------------------------------------------- */

    @Override
    void build() {
        header("Options");

        String gameTitle = getIntent().getStringExtra("title");
        String gameFile = getIntent().getStringExtra("file");
        int hiddenCount = Options.hidden(prefs).size();

        section("Games");
        row("Games folder", folderName(), this::pickFolder);
        if (gameFile != null)
            row("Saves: " + gameTitle, "Its own save, the 4 state slots and the resume point",
                    () -> startActivity(new Intent(this, SavesActivity.class).putExtra("file", gameFile)
                            .putExtra("title", gameTitle)));
        row("Export saves", "Copies every save, state and picture to CHGame saves in the games folder", this::exportSaves);
        row("Import saves", "Copies them back; asks before replacing", this::importSaves);
        row("Hidden games", (Options.showHidden(prefs) ? "Shown in the list, marked Hidden" : "Not shown")
                + " · " + hiddenCount + " hidden. X on a game hides it", () -> {
            prefs.edit().putBoolean("showHidden", !Options.showHidden(prefs)).apply();
            build();
        });
        if (hiddenCount > 0)
            row("Show all hidden games again", hiddenCount + " games", () -> Saves.confirm(this,
                    "Show all " + hiddenCount + " hidden games again?", () -> {
                        prefs.edit().remove("hidden").apply();
                        build();
                    }));

        section("Game list");
        row("Live preview", Options.PREVIEW_NAMES[indexOf(Options.PREVIEWS, Options.preview(prefs))]
                        + ". The selected game runs in the picture's place after a moment",
                () -> pick("Live preview", Options.PREVIEW_NAMES, indexOf(Options.PREVIEWS, Options.preview(prefs)),
                        i -> prefs.edit().putString("preview", Options.PREVIEWS[i]).apply()));
        row("Sort games", Options.SORT_NAMES[indexOf(Options.SORTS, Options.sort(prefs))],
                () -> pick("Sort games", Options.SORT_NAMES, indexOf(Options.SORTS, Options.sort(prefs)),
                        i -> prefs.edit().putString("sort", Options.SORTS[i]).apply()));

        section("Playing");
        row("Resume where you left off", Options.RESUME_NAMES[indexOf(Options.RESUMES, Options.resume(prefs))],
                () -> pick("Resume where you left off", Options.RESUME_NAMES, indexOf(Options.RESUMES, Options.resume(prefs)),
                        i -> prefs.edit().putString("resume", Options.RESUMES[i]).apply()));
        row("Picture size", Options.fill(prefs) ? "Fill the screen height" : "Sharp: a whole-number scale, every pixel the same", () -> {
            prefs.edit().putBoolean("fill", !Options.fill(prefs)).apply();
            build();
        });
        row("Speed overlay", Options.overlay(prefs) ? "On: shows fps, % of real speed and frame time" : "Off", () -> {
            prefs.edit().putBoolean("overlay", !Options.overlay(prefs)).apply();
            build();
        });

        section("Screen");
        row("Look", Options.LOOK_NAMES[indexOf(Options.LOOKS, Options.look(prefs))],
                () -> pick("Look", Options.LOOK_NAMES, indexOf(Options.LOOKS, Options.look(prefs)),
                        i -> prefs.edit().putString("look", Options.LOOKS[i]).apply()));
        row("Colours", Options.panelColours(prefs) ? "Like the CHGame's own panel (a little softer)" : "As the game draws them", () -> {
            prefs.edit().putBoolean("panel", !Options.panelColours(prefs)).apply();
            build();
        });
        row("Border", Options.bezel(prefs) ? "On: a CHGame-shaped frame beside the picture" : "Off", () -> {
            prefs.edit().putBoolean("bezel", !Options.bezel(prefs)).apply();
            build();
        });

        section("Sound");
        row("Sound", Options.SOUND_NAMES[indexOf(Options.SOUNDS, Options.sound(prefs))],
                () -> pick("Sound", Options.SOUND_NAMES, indexOf(Options.SOUNDS, Options.sound(prefs)),
                        i -> prefs.edit().putString("sound", Options.SOUNDS[i]).apply()));
        row("Volume", Options.volume(prefs),
                () -> pick("Volume", Options.VOLUMES, indexOf(Options.VOLUMES, Options.volume(prefs)),
                        i -> prefs.edit().putString("volume", Options.VOLUMES[i]).apply()));
        row("Audio buffer", Options.AUDIO_BUFFER_NAMES[indexOf(Options.AUDIO_BUFFERS, Options.audioBuffer(prefs))],
                () -> pick("Audio buffer", Options.AUDIO_BUFFER_NAMES, indexOf(Options.AUDIO_BUFFERS, Options.audioBuffer(prefs)),
                        i -> prefs.edit().putString("audioBuffer", Options.AUDIO_BUFFERS[i]).apply()));

        section("Controls");
        Keymap k = Keymap.load(prefs);
        row("Map buttons", "A: " + k.keyFor(Native.A) + " · B: " + k.keyFor(Native.B) + " · Second Up: " + k.keyFor(Keymap.UP2),
                () -> startActivity(new Intent(this, MapButtonsActivity.class)));
        row("Touch controls", Options.TOUCH_NAMES[indexOf(Options.TOUCHES, Options.touch(prefs))],
                () -> pick("Touch controls", Options.TOUCH_NAMES, indexOf(Options.TOUCHES, Options.touch(prefs)),
                        i -> prefs.edit().putString("touch", Options.TOUCHES[i]).apply()));

        section("ES-DE");
        row("ES-DE media folder", mediaName(), this::pickMedia);
        if (media() != null) {
            row("Videos show", Options.SNAP_NAMES[indexOf(Options.SNAPS, Options.snaps(prefs))],
                    () -> pick("The ES-DE videos show", Options.SNAP_NAMES, indexOf(Options.SNAPS, Options.snaps(prefs)),
                            i -> prefs.edit().putString("snaps", Options.SNAPS[i]).apply()));
            row("Make videos, screenshots and covers", "For every game: a 15-second video with sound, a screenshot "
                    + "and the cart picture, into chgame/videos, screenshots and covers", () -> makeMedia(null));
            if (gameFile != null)
                row("Make them for " + gameTitle + " only", null, () -> makeMedia(gameFile));
        }

        section("About");
        row("CHGame Player " + MainActivity.VERSION, "An app by jadflat for playing CHGame games. No permissions: "
                + "it sees only the folders you choose.", null);
        row("CHGame", "The CHGame handheld, its platform and the SD bootloader are by Kevin Bates (bateske), "
                + "chgame.website. Apache-2.0; the bootloader is MIT.", null);
        row("Emulation", "CHGame Emulator by joyrider3774, MIT licence: all of the emulation, unchanged.", null);
        row("Also", "libretro.h by the RetroArch team (MIT). The game-name font Press Start 2P by CodeMan38 "
                + "(SIL Open Font License 1.1).", null);
        row("The games", "Each game's own credits are in its About this game (long-press it in the list); "
                + "ports name the original game's makers.", null);

        focus();
    }

    interface IntCallback { void run(int i); }

    private void pick(String title, String[] items, int current, IntCallback cb) {
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(title)
                .setSingleChoiceItems(items, current, (d, i) -> { d.dismiss(); cb.run(i); build(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return 0;
    }

    /* ---------------------------------------------------------------- */
    /* The games folder                                                   */
    /* ---------------------------------------------------------------- */

    private Uri tree() {
        String s = prefs.getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
        return null;
    }

    private String folderName() {
        Uri t = tree();
        if (t == null) return "Not chosen yet: choose the folder with your .CHG games and .DAT files";
        return place(t);
    }

    private Uri media() {
        String s = prefs.getString("mediaTree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        return null;
    }

    private String mediaName() {
        Uri m = media();
        return m == null ? "Not chosen: choose ES-DE's downloaded_media folder (in the ES-DE folder)" : place(m);
    }

    void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_MEDIA);
    }

    /** "Internal storage › ROMs › chgame" for a folder the user picked. */
    private static String place(Uri t) {
        String id = DocumentsContract.getTreeDocumentId(t);
        int colon = id.indexOf(':');
        String vol = colon > 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return ("primary".equals(vol) ? "Internal storage" : "SD card") + (path.isEmpty() ? "" : " › " + path.replace("/", " › "));
    }

    /** Every save, state and picture into CHGame saves in the games folder. */
    private void exportSaves() {
        Uri t = tree();
        if (t == null) {
            Saves.toast(this, "Choose the games folder first");
            return;
        }
        new Thread(() -> {
            int n = Saves.exportAll(this, t);
            runOnUiThread(() -> Saves.toast(this, n == 0 ? "Nothing to export" : "Copied " + n + " files to CHGame saves"));
        }, "export").start();
    }

    /** Copies CHGame saves back; asks before replacing a save that differs. */
    private void importSaves() {
        Uri t = tree();
        if (t == null) {
            Saves.toast(this, "Choose the games folder first");
            return;
        }
        Saves.importAll(this, t, this::build);
    }

    void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER && request != PICK_MEDIA) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (getIntent().getBooleanExtra("pickOnly", false)) finish();
            return;
        }
        Uri u = data.getData();
        String key = request == PICK_MEDIA ? "mediaTree" : "tree";
        /* the app keeps two folders, the games and ES-DE's media: the one replaced is given up */
        String other = prefs.getString(request == PICK_MEDIA ? "tree" : "mediaTree", null);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (!p.getUri().equals(u) && !p.getUri().toString().equals(other))
                getContentResolver().releasePersistableUriPermission(p.getUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(u,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        prefs.edit().putString(key, u.toString()).apply();
        if (getIntent().getBooleanExtra("pickOnly", false)) {
            finish();
            return;
        }
        build();
    }

    /* ---------------------------------------------------------------- */
    /* ES-DE media                                                        */
    /* ---------------------------------------------------------------- */

    private volatile boolean cancelMaking;

    /** Videos, screenshots and covers for every game (or just one), on a thread, with progress. */
    private void makeMedia(String onlyFile) {
        Uri t = tree(), m = media();
        if (t == null || m == null) return;
        cancelMaking = false;
        android.widget.TextView progress = Ui.text(this, "Finding the games…", 16, Ui.ON_SURFACE, Ui.REGULAR);
        progress.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        AlertDialog d = new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Making ES-DE media")
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton("Cancel", (x, y) -> cancelMaking = true)
                .show();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            java.util.List<Files.Entry> games = new java.util.ArrayList<>();
            for (Files.Entry e : Files.list(getContentResolver(), t))
                if (Files.isGame(e.path) && (onlyFile == null || e.path.endsWith("/" + onlyFile) || e.path.equals(onlyFile)))
                    games.add(e);
            int made = 0;
            StringBuilder problems = new StringBuilder();
            Preview.gameRunning = true;         /* no list preview while the core is busy here */
            try {
                for (int i = 0; i < games.size() && !cancelMaking; i++) {
                    Files.Entry e = games.get(i);
                    String file = e.path.substring(e.path.lastIndexOf('/') + 1);
                    Cart cart = Cart.read(getContentResolver(), e.uri);
                    String name = cart != null && !cart.title.isEmpty() ? cart.title : Saves.base(file);
                    final String msg = "Game " + (i + 1) + " of " + games.size() + ":\n" + name;
                    runOnUiThread(() -> progress.setText(msg));
                    String err;
                    synchronized (Preview.CORE) {
                        err = SnapMaker.make(this, m, e, file, cart, () -> cancelMaking);
                    }
                    if (err == null) made++;
                    else if (!cancelMaking) problems.append("\n").append(name).append(": ").append(err);
                }
            } finally {
                Preview.gameRunning = false;
            }
            final String summary = (cancelMaking ? "Stopped. " : "") + "Made media for " + made + " of " + games.size()
                    + (games.size() == 1 ? " game." : " games.") + (problems.length() > 0 ? "\n\nProblems:" + problems : "")
                    + "\n\nIn ES-DE: restart it to see them.";
            runOnUiThread(() -> {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                d.dismiss();
                new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("ES-DE media")
                        .setMessage(summary).setPositiveButton("OK", null).show();
            });
        }, "snaps").start();
    }
}
