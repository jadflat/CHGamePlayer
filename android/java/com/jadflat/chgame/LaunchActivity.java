package com.jadflat.chgame;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Starts a game handed over by a front end such as ES-DE: an ACTION_VIEW with a
 * content:// URI to one .CHG file, readable for this launch only (ES-DE's
 * %ROMPROVIDER%). The file is copied into the app's private games folder and
 * played from there; anything that is not a readable .CHG is refused.
 * Before it starts, the card files the game names are looked for in the games
 * folder and in the launched file's folder (as MainActivity does for its own
 * list): with one missing, "Missing card file" offers to start anyway or cancel.
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent in = getIntent();
        Uri uri = in == null ? null : in.getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            Toast.makeText(this, "No game was given.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        /* Who is asking is taken from the file itself, not from what the caller says: the app that serves the
           content:// address is resolved by the system and cannot be faked, and the caller must hand over read access
           with the intent (so it cannot borrow the access this app already has to the games folder). The one-time
           Allow is remembered for that app. A file served by a shared system provider (storage, downloads) says
           nothing about the caller, so then it asks every time; the referrer is only shown, never trusted. */
        String provider = null;
        boolean system = false;
        try {
            android.content.pm.ProviderInfo pi = getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            if (pi != null) {
                provider = pi.packageName;
                system = pi.applicationInfo != null && (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            }
        } catch (RuntimeException e) {
            provider = null;
        }
        final String who = provider;
        boolean granted = (in.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean allowed = who != null && getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(ALLOW + who, false);
        int what = decide(granted, who, system, getPackageName(), allowed);
        if (what == REFUSE) {
            Toast.makeText(this, "The game was not started: the app that sent it did not share the file.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (what == RUN) {
            go(uri);
            return;
        }
        String ref = referrer();
        String asker = what == ASK_REMEMBER ? label(who)
                : "An app" + (ref != null && !ref.equals(getPackageName()) ? " (it says it is " + label(ref) + ")" : "");
        new CardDialog.Builder(this)
                .setTitle("Start a game?")
                .setMessage(asker + " wants to start a game in CHGame Player."
                        + (what == ASK_REMEMBER ? "" : " This will be asked every time."))
                .setPositiveButton("Allow", (d, w) -> {
                    if (what == ASK_REMEMBER) getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean(ALLOW + who, true).apply();
                    go(uri);
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private static final String ALLOW = "launch-ok:";
    static final int REFUSE = 0, RUN = 1, ASK_REMEMBER = 2, ASK_EACH_TIME = 3;

    /**
     * What a launch may do. granted: the intent hands over read access to the file; provider: the package serving the
     * file (null when it cannot be seen); system: that package is part of the system (a shared provider such as
     * storage or downloads); allowed: the user allowed that provider's app before.
     */
    static int decide(boolean granted, String provider, boolean system, String self, boolean allowed) {
        if (!granted) return REFUSE;                    /* would read the file with this app's own access */
        if (provider == null || provider.isEmpty()) return ASK_EACH_TIME;
        if (provider.equals(self)) return REFUSE;       /* this app serves no files: an address back into it is not a launch */
        if (system) return ASK_EACH_TIME;
        return allowed ? RUN : ASK_REMEMBER;
    }

    /** The package that started this activity, or null when it did not say. */
    private String referrer() {
        try {
            Uri r = getReferrer();
            return r != null && "android-app".equals(r.getScheme()) && r.getHost() != null && !r.getHost().isEmpty() ? r.getHost() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The calling app's name as the launcher shows it ("ES-DE"), or its package name when it cannot be seen
        (package visibility: the manifest's <queries> covers launcher apps only). */
    private String label(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            String s = l == null ? "" : l.toString().trim();
            return s.isEmpty() ? pkg : s;
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Copies the game and checks its card files on a background thread (a slow provider, or another copy of the same
        game holding its lock, never blocks the screen), then starts it or shows what is missing. configChanges in the
        manifest keeps a rotation, theme, font or language change from recreating this activity mid-copy. */
    private void go(Uri uri) {
        new Thread(() -> {
            File game;
            try {
                /* stops when this screen goes (Back, Home: noHistory finishes it): no copy runs on for nothing */
                game = Files.copyGame(this, uri, displayName(uri), cancel);
            } catch (Throwable e) {        /* never a crash behind the frontend: a message, and back */
                if (!closed() && !(e instanceof Files.Cancelled)) fail("Could not open the game: " + why(e));
                else fail(null);
                return;
            }
            if (closed()) return;
            Cart c = null;
            List<String> missing = new ArrayList<>();
            try {
                c = Cart.read(getContentResolver(), Uri.fromFile(game));
                missing = missingDats(c, uri, game.getName());
            } catch (Throwable e) {
                /* the check could not be made: the game starts as it did before the check existed */
            }
            Cart cart = c;
            List<String> gone = missing;
            /* no title: null, so GameActivity names the game from its file as it always has */
            String title = cart != null && !cart.title.isEmpty() ? cart.title : null;
            String shown = title != null ? title : game.getName();
            onUi(() -> {
                if (gone.isEmpty()) {
                    start(game, title);
                    return;
                }
                new CardDialog.Builder(this)
                        .setTitle(gone.size() == 1 ? "Missing card file" : "Missing card files")
                        .setMessage(shown + " needs " + (gone.size() == 1 ? "this file" : "these files") + " on the SD card:\n\n"
                                + TextUtils.join("\n", gone) + "\n\nPut " + (gone.size() == 1 ? "it" : "them") + " in the games folder.")
                        .setPositiveButton("Start anyway", (d, w) -> onUi(() -> start(game, title)))
                        .setNegativeButton("Cancel", (d, w) -> finish())
                        .setOnCancelListener(d -> finish())
                        .show();
            });
        }, "launch").start();
    }

    /** Cancelled as soon as this screen is on its way out (Back, Home with noHistory, finish()), not only at
        onDestroy: the copy then stops in its lock wait, in the provider's open or in a read. */
    private final android.os.CancellationSignal cancel = new android.os.CancellationSignal();
    private boolean closed() { return cancel.isCanceled(); }

    @Override
    protected void onPause() {
        if (isFinishing()) cancel.cancel();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (isFinishing()) cancel.cancel();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancel.cancel();
        super.onDestroy();
    }

    /** Runs on the UI thread while this screen is still up; anything it throws (a window gone between the check and
        show(), startActivity failing) is logged and ends this screen with a message, instead of crashing the app. */
    private void onUi(Runnable r) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                android.util.Log.e("CHGameLaunch", "launch failed", e);
                fail("Could not start the game: " + why(e));
            }
        });
    }

    /** A message (none when err is null; only while this screen is still up: not over whatever the player went to)
        and the end. */
    private void fail(String err) {
        runOnUiThread(() -> {
            try {
                if (err != null && !isFinishing() && !isDestroyed()) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            } catch (RuntimeException ignored) { }
            finish();
        });
    }

    private static String why(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Starts GameActivity on the copied game, with the extras it has always had. */
    private void start(File game, String title) {
        Intent g = new Intent(this, GameActivity.class);
        g.putExtra("file", game.getName());
        g.putExtra("title", title);         /* read on the launch thread: no file I/O here on the UI thread */
        /* a game left with Home must not stay stacked under the next one: both would share the core */
        g.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(g);
        finish();
    }

    /** The card files a game names that are in neither the games folder nor the launched file's folder. */
    private List<String> missingDats(Cart c, Uri launched, String file) {
        java.util.Set<String> need = MainActivity.neededFiles(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE), c, file);
        if (need.isEmpty()) return new ArrayList<>();
        return MainActivity.missingFrom(getContentResolver(), tree(), need, new HashSet<>(folderNames(launched)));
    }

    /** The games folder, if still usable (Files.tree). */
    private Uri tree() {
        return Files.tree(this, "tree", false);
    }

    /** The file names (upper case) in the folder the launched file is in; none if its provider will not list it. */
    private List<String> folderNames(Uri file) {
        List<String> names = new ArrayList<>();
        try {
            if (!DocumentsContract.isDocumentUri(this, file)) return names;
            String id = DocumentsContract.getDocumentId(file);
            /* the parent is the id without its last part: "primary:Games/a.CHG" is in "primary:Games",
               "primary:a.CHG" is at the root, "primary:" */
            int cut = id.lastIndexOf('/');
            if (cut < 0) cut = id.indexOf(':') + 1;
            if (cut <= 0) return names;
            Uri children = DocumentsContract.buildChildDocumentsUri(file.getAuthority(), id.substring(0, cut));
            try (Cursor c = getContentResolver().query(children,
                    new String[] { Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE }, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String n = Files.safeName(c.getString(0));
                    if (n != null && !Document.MIME_TYPE_DIR.equals(c.getString(1)))
                        names.add(n.toUpperCase(Locale.ROOT));
                }
            }
        } catch (RuntimeException e) {
            /* a folder the provider will not list: none */
        }
        return names;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        }
        return uri.getLastPathSegment();
    }
}
