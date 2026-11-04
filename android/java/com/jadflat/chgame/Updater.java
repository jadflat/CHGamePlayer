package com.jadflat.chgame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * The app's own update notice: on start (at most every 12 hours, quietly) the latest release of
 * jadflat/CHGamePlayer is compared with MainActivity.VERSION; a newer one is offered with
 * Open page / Later / Skip this version. Open page shows the release on GitHub in the browser; the
 * app installs nothing itself (so it needs no install permission), and the new APK installs over
 * this one because every build is signed with the same key (see build_apk.sh).
 */
final class Updater {
    private Updater() {}

    private static final String REPO = "https://github.com/jadflat/CHGamePlayer/releases/";
    private static final String LATEST = "https://api.github.com/repos/jadflat/CHGamePlayer/releases/latest";
    private static final long EVERY = 12 * 3600_000L;
    private static volatile boolean busy;

    private static final class Found { String tag, page; }

    /** Quietly, on a background thread, if the last check was over 12 hours ago. */
    static void checkOnStart(Activity a, SharedPreferences prefs) {
        if (System.currentTimeMillis() - prefs.getLong("selfAt", 0) < EVERY) return;
        check(a, prefs, false);
    }

    /** Options' "Check for updates": ignores the 12 hours and the skipped version, and says the result. */
    static void checkNow(Activity a, SharedPreferences prefs) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        check(a, prefs, true);
    }

    private static void check(Activity a, SharedPreferences prefs, boolean manual) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                Found f = latest();
                prefs.edit().putLong("selfAt", System.currentTimeMillis()).apply();
                if (f != null && newer(f.tag, MainActivity.VERSION)
                        && (manual || !f.tag.equals(prefs.getString("selfSkip", "")))) {
                    a.runOnUiThread(() -> { if (!a.isFinishing() && !a.isDestroyed()) offer(a, prefs, f); });
                } else if (manual) {
                    a.runOnUiThread(() -> Toast.makeText(a, "You have the latest version", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                if (manual) a.runOnUiThread(() -> Toast.makeText(a, "Could not check for updates", Toast.LENGTH_LONG).show());
            } finally {
                busy = false;
            }
        }, "updater").start();
    }

    /** The newest release, or null. */
    private static Found latest() throws Exception {
        HttpURLConnection h = CatalogueActivity.open(LATEST);
        JSONObject rel;
        try (InputStream in = h.getInputStream()) {
            rel = new JSONObject(CatalogueActivity.readAll(in, 1 << 20));
        } finally {
            h.disconnect();
        }
        String tag = rel.optString("tag_name", "");
        if (!tag.matches("[vV]?\\d+(\\.\\d+)*")) return null;
        Found f = new Found();
        f.tag = tag;
        String page = rel.optString("html_url", "");
        f.page = page.startsWith(REPO) ? page : REPO + "tag/" + tag;
        return f;
    }

    /** Numeric comparison by dot parts, ignoring a leading v: is a newer than b? */
    static boolean newer(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("\\."), y = b.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static void offer(Activity a, SharedPreferences prefs, Found f) {
        String v = f.tag.replaceFirst("^[vV]", "");
        new CardDialog.Builder(a)
                .setTitle("Update available")
                .setMessage("Version " + v + " is available (you have " + MainActivity.VERSION
                        + "). Open the download page, then tap the .apk there to install it.")
                .setPositiveButton("Open page", (d, w) -> {
                    try {
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(f.page)));
                    } catch (RuntimeException e) {
                        Toast.makeText(a, "No browser to open the page", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", (d, w) -> prefs.edit().putString("selfSkip", f.tag).apply())
                .show();
    }
}
