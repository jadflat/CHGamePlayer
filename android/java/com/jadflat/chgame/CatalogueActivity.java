package com.jadflat.chgame;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Get games: a short catalogue of CHGame games to download straight into the
 * games folder. The list is bundled (assets/catalogue.json) and refreshed from
 * the project's catalogue.json when there is a network; if that fails the
 * bundled list stays. This is the only thing in the app that uses the internet.
 */
public class CatalogueActivity extends Page {
    static final String REMOTE = "https://raw.githubusercontent.com/jadflat/CHGamePlayer/main/catalogue.json";

    private static final class Item {
        String name, author, description, licence;
        final List<String[]> files = new ArrayList<>();   /* {name, url} */
    }

    private SharedPreferences prefs;
    private List<Item> items = new ArrayList<>();
    private Set<String> have = new HashSet<>();          /* upper-case names in the games folder */
    private TextView status;                              /* one line under the title: progress */
    private String statusText = "";
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        try (InputStream in = getAssets().open("catalogue.json")) {
            items = parse(readAll(in, 1 << 20));
        } catch (Exception e) {
            /* no bundled list: the page shows none */
        }
        build();
        scanFolder();
        refreshRemote();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        scanFolder();
    }

    @Override
    void build() {
        header("Get games");
        status = Ui.text(this, statusText, 14, Ui.PRIMARY, Ui.MEDIUM);
        status.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 4));
        status.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);
        page.addView(status);
        Uri t = tree();
        if (t == null) {
            section("Games folder");
            row("Games folder", "Not chosen: tap to choose where the games are saved",
                    () -> startActivity(new Intent(this, OptionsActivity.class).putExtra("pickOnly", true)));
        }
        section("Games");
        if (items.isEmpty()) row("No games listed", "The catalogue could not be read", null);
        for (Item it : items) {
            boolean in = installed(it);
            String summary = it.description + (it.author.isEmpty() ? "" : "\nBy " + it.author)
                    + (it.licence.isEmpty() ? "" : " · " + it.licence);
            row(it.name, summary, in ? "Installed" : "Get", in ? null : () -> get(it));
        }
        focus();
    }

    private boolean installed(Item it) {
        for (String[] f : it.files)
            if (!have.contains(f[0].toUpperCase(Locale.ROOT))) return false;
        return !it.files.isEmpty();
    }

    private void say(String s) {
        statusText = s;
        if (status == null) return;
        status.setText(s);
        status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private Uri tree() {
        String s = prefs.getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return u;
        return null;
    }

    /** What is in the games folder now, then the rows again. */
    private void scanFolder() {
        Uri t = tree();
        if (t == null) {
            have = new HashSet<>();
            build();
            return;
        }
        new Thread(() -> {
            Set<String> names = new HashSet<>();
            for (Files.Entry e : Files.list(getContentResolver(), t))
                names.add(e.path.substring(e.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                have = names;
                build();
            });
        }, "catalogue-scan").start();
    }

    /* ---------------------------------------------------------------- */
    /* The list                                                           */
    /* ---------------------------------------------------------------- */

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("too big");
        }
        return out.toString("UTF-8");
    }

    /** The catalogue's games; one with a bad file name or a non-https address is left out. */
    private static List<Item> parse(String json) throws Exception {
        List<Item> out = new ArrayList<>();
        JSONArray games = new JSONObject(json).getJSONArray("games");
        for (int i = 0; i < games.length(); i++) {
            JSONObject g = games.getJSONObject(i);
            Item it = new Item();
            it.name = g.getString("name");
            it.author = g.optString("author", "");
            it.description = g.optString("description", "");
            it.licence = g.optString("licence", "");
            JSONArray files = g.getJSONArray("files");
            boolean ok = files.length() > 0;
            for (int k = 0; k < files.length() && ok; k++) {
                JSONObject f = files.getJSONObject(k);
                String name = Files.safeName(f.getString("name")), url = f.getString("url");
                if (name == null || !url.startsWith("https://")) ok = false;
                else it.files.add(new String[] { name, url });
            }
            if (ok) out.add(it);
        }
        return out;
    }

    /** The newer list from the project, if it can be had; otherwise nothing changes. */
    private void refreshRemote() {
        new Thread(() -> {
            try {
                HttpURLConnection c = open(REMOTE);
                try (InputStream in = c.getInputStream()) {
                    List<Item> got = parse(readAll(in, 1 << 20));
                    if (got.isEmpty()) return;
                    runOnUiThread(() -> {
                        if (isFinishing() || busy) return;
                        items = got;
                        build();
                    });
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                /* no network, or no list there: the bundled one stays */
            }
        }, "catalogue-list").start();
    }

    /* ---------------------------------------------------------------- */
    /* Downloading                                                        */
    /* ---------------------------------------------------------------- */

    /** A GET to an https address, following redirects (GitHub sends releases to another host). */
    private static HttpURLConnection open(String url) throws IOException {
        for (int hops = 0; hops < 8; hops++) {
            if (!url.startsWith("https://")) throw new IOException("not https");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "CHGamePlayer/" + MainActivity.VERSION);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new IOException("bad redirect");
                url = new URL(new URL(url), next).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private void get(Item it) {
        if (busy) return;
        Uri t = tree();
        if (t == null) {
            Saves.toast(this, "Choose the games folder first");
            return;
        }
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        say("Starting " + it.name + "…");
        new Thread(() -> {
            String result;
            try {
                result = download(it, t);
            } catch (IOException e) {
                String m = e.getMessage();
                result = e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                        || e instanceof java.net.ConnectException ? "No network connection"
                        : "Could not get " + it.name + (m == null ? "" : ": " + m);
            } catch (RuntimeException e) {
                result = "Could not get " + it.name;
            }
            final String msg = result;
            runOnUiThread(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                say("");
                Saves.toast(this, msg);
                scanFolder();     /* the game list rescans when you go back to it */
            });
        }, "catalogue-get").start();
    }

    /** Every file of the game that is not in the folder yet, into the games folder; a message for the user. */
    private String download(Item it, Uri tree) throws IOException {
        ContentResolver cr = getContentResolver();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        int n = 0, all = 0;
        for (String[] f : it.files) if (!have.contains(f[0].toUpperCase(Locale.ROOT))) all++;
        for (String[] f : it.files) {
            if (have.contains(f[0].toUpperCase(Locale.ROOT))) continue;
            n++;
            final String label = it.name + (all > 1 ? " (file " + n + " of " + all + ")" : "");
            HttpURLConnection c = open(f[1]);
            Uri doc = null;
            boolean done = false;
            try (InputStream in = c.getInputStream()) {
                long total = c.getContentLengthLong();
                doc = DocumentsContract.createDocument(cr, root, "application/octet-stream", f[0]);
                if (doc == null) throw new IOException("cannot write to the games folder");
                try (OutputStream out = cr.openOutputStream(doc, "wt")) {
                    if (out == null) throw new IOException("cannot write to the games folder");
                    byte[] buf = new byte[1 << 16];
                    long got = 0, shown = 0;
                    for (int r; (r = in.read(buf)) > 0; ) {
                        got += r;
                        if (got > Files.MAX_FILE) throw new IOException("file too big");
                        out.write(buf, 0, r);
                        long now = System.currentTimeMillis();
                        if (now - shown > 250) {
                            shown = now;
                            final String line = "Downloading " + label + ": "
                                    + (total > 0 ? (got * 100 / total) + "%" : (got >> 10) + " KB");
                            runOnUiThread(() -> say(line));
                        }
                    }
                }
                done = true;
            } finally {
                c.disconnect();
                if (!done && doc != null) {
                    try {
                        DocumentsContract.deleteDocument(cr, doc);   /* a half file is not left behind */
                    } catch (Exception e) {
                        /* nothing more to do */
                    }
                }
            }
        }
        return it.name + " is in your games folder";
    }
}
