package com.jadflat.chgame;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * The app's own update: on start (at most every 12 hours, quietly) the latest release of
 * jadflat/CHGamePlayer is compared with MainActivity.VERSION; a newer one is offered with
 * Install / Later / Skip this version. Install downloads the .apk over https into the cache
 * and hands it to PackageInstaller, which asks the user to confirm. The new APK installs over
 * this one only because every build is signed with the same key (see build_apk.sh).
 */
final class Updater {
    private Updater() {}

    private static final String LATEST = "https://api.github.com/repos/jadflat/CHGamePlayer/releases/latest";
    private static final long EVERY = 12 * 3600_000L;
    private static final long MAX_APK = 100L << 20;
    private static volatile boolean busy;

    private static final class Found { String tag, url; long size; }

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
                    a.runOnUiThread(() -> { if (!a.isFinishing()) offer(a, prefs, f); });
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

    /** The newest release with an .apk asset, or null. */
    private static Found latest() throws Exception {
        HttpURLConnection h = CatalogueActivity.open(LATEST);
        JSONObject rel;
        try (InputStream in = h.getInputStream()) {
            rel = new JSONObject(CatalogueActivity.readAll(in, 1 << 20));
        } finally {
            h.disconnect();
        }
        String tag = rel.optString("tag_name", "");
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null || !tag.matches("[vV]?\\d+(\\.\\d+)*")) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject s = assets.getJSONObject(i);
            String url = s.optString("browser_download_url", "");
            if (s.optString("name", "").toLowerCase(java.util.Locale.ROOT).endsWith(".apk") && url.startsWith("https://")) {
                Found f = new Found();
                f.tag = tag;
                f.url = url;
                f.size = s.optLong("size", -1);
                return f;
            }
        }
        return null;
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
        new AlertDialog.Builder(a, Ui.dialogTheme())
                .setTitle("Update available")
                .setMessage("Version " + v + " is available (you have " + MainActivity.VERSION + ").")
                .setPositiveButton("Install", (d, w) -> install(a, f))
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", (d, w) -> prefs.edit().putString("selfSkip", f.tag).apply())
                .show();
    }

    private static void install(Activity a, Found f) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow installs from CHGame Player, then tap Install again", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        android.widget.TextView progress = Ui.text(a, "Starting…", 16, Ui.ON_SURFACE, Ui.REGULAR);
        progress.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
        final boolean[] cancel = { false };
        AlertDialog d = new AlertDialog.Builder(a, Ui.dialogTheme())
                .setTitle("Downloading update")
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton("Cancel", (x, y) -> cancel[0] = true)
                .show();
        new Thread(() -> {
            String err = null;
            File apk = new File(a.getCacheDir(), "update.apk");
            try {
                download(a, f, apk, progress, cancel);
                if (!cancel[0]) commit(a, apk);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                apk.delete();
            }
            final String msg = err;
            a.runOnUiThread(() -> {
                d.dismiss();
                if (msg != null) Toast.makeText(a, "Update failed: " + msg, Toast.LENGTH_LONG).show();
            });
        }, "updater-install").start();
    }

    private static void download(Activity a, Found f, File apk, android.widget.TextView progress, boolean[] cancel) throws Exception {
        if (f.size <= 0 || f.size > MAX_APK) throw new Exception("bad size");
        HttpURLConnection h = CatalogueActivity.open(f.url);
        long got = 0;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[32768];
            long shown = 0;
            for (int n; !cancel[0] && (n = in.read(buf)) > 0; ) {
                got += n;
                if (got > MAX_APK || got > f.size) throw new Exception("too big");
                out.write(buf, 0, n);
                if (got - shown > 100_000) {
                    shown = got;
                    final String t = (got * 100 / f.size) + "%  (" + (got >> 10) + " of " + (f.size >> 10) + " KB)";
                    a.runOnUiThread(() -> progress.setText(t));
                }
            }
        } finally {
            h.disconnect();
        }
        if (cancel[0]) { apk.delete(); return; }
        if (got != f.size) throw new Exception("download incomplete");
    }

    private static void commit(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setSize(apk.length());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new java.io.FileInputStream(apk); OutputStream out = s.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, Result.class).setAction("com.jadflat.chgame.UPDATE_RESULT");
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(p.getIntentSender());
        }
    }

    /** Gets the installer's answer: asks the user to confirm, or reports a failure. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (st != PackageInstaller.STATUS_SUCCESS) {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Update failed" + (m == null ? "" : ": " + m), Toast.LENGTH_LONG).show();
            }
            new File(c.getCacheDir(), "update.apk").delete();
        }
    }
}
