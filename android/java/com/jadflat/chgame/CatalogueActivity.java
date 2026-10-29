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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        String name, author, developer, description, licence, group;
        String chgTitle = "", chgAuthor = "", version = "";   /* the .CHG's header, for matching what is installed */
        final List<String[]> files = new ArrayList<>();   /* {name, url, asset}: one of url / asset is "" */
    }

    private SharedPreferences prefs;
    private List<Item> items = new ArrayList<>();
    private List<Item> bundled = new ArrayList<>();      /* games inside this APK (private build only) */
    private Set<String> have = new HashSet<>();          /* upper-case names in the games folder */
    private final Map<String, Files.Entry> haveKey = new HashMap<>();   /* title+author of each .CHG there */
    private final Map<String, String> haveVersion = new HashMap<>();    /* path -> the header's version */
    private final Map<String, Files.Entry> haveName = new HashMap<>();  /* upper-case name -> .CHG */
    private TextView status;                              /* one line under the title: progress */
    private String statusText = "";
    private volatile boolean busy;
    private int dupSets;                                  /* games in the folder more than once */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        try (InputStream in = getAssets().open("catalogue.json")) {
            items = parse(readAll(in, 1 << 20));
            readHeaders(items);
        } catch (Exception e) {
            /* no bundled list: the page shows none */
        }
        try (InputStream in = getAssets().open("catalogue-private.json")) {
            bundled = parse(readAll(in, 1 << 20));
            readHeaders(bundled);
            items.addAll(bundled);
        } catch (Exception e) {
            /* the public build has no games inside it */
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
        if (dupSets > 0)
            row(dupSets + (dupSets == 1 ? " duplicate game" : " duplicate games"), "Tap to tidy up",
                    () -> startActivity(new Intent(this, DuplicatesActivity.class)));
        if (items.isEmpty()) {
            section("Games");
            row("No games listed", "The catalogue could not be read", null);
        }
        /* one section a developer: Jadflat first, then A-Z. With the option on, each is a collapsible group. */
        Map<String, List<Item>> by = new LinkedHashMap<>();
        List<String> devs = new ArrayList<>();
        for (Item it : items) {
            if (!by.containsKey(it.developer)) {
                by.put(it.developer, new ArrayList<>());
                devs.add(it.developer);
            }
            by.get(it.developer).add(it);
        }
        Collections.sort(devs, (a, b) -> {
            boolean ja = a.equalsIgnoreCase("Jadflat"), jb = b.equalsIgnoreCase("Jadflat");
            if (ja != jb) return ja ? -1 : 1;
            return a.compareToIgnoreCase(b);
        });
        boolean byDev = Options.groupByDeveloper(prefs);
        for (String dev : devs) {
            List<Item> list = by.get(dev);
            Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
            if (byDev) {
                section("");
                if (!toggleRow("cat:dev:" + dev, dev + " (" + list.size() + ")")) continue;
                indent = 1;
            } else {
                section(dev);
            }
            /* a "group" in the list (Board and card games): one row, opened with A, with Get all inside */
            Map<String, List<Item>> groups = new LinkedHashMap<>();
            for (Item it : list) if (!it.group.isEmpty()) {
                if (!groups.containsKey(it.group)) groups.put(it.group, new ArrayList<>());
                groups.get(it.group).add(it);
            }
            int base = indent;
            for (Map.Entry<String, List<Item>> g : groups.entrySet()) {
                if (toggleRow("cat:grp:" + g.getKey(), g.getKey() + " (" + g.getValue().size() + ")")) {
                    indent = base + 1;
                    List<Item> todo = new ArrayList<>();
                    for (Item it : g.getValue()) if (!installed(it)) todo.add(it);
                    row("Get all", todo.isEmpty() ? "All installed" : todo.size() + " to get",
                            todo.isEmpty() ? "Installed" : "Get", todo.isEmpty() ? null : () -> getAll(todo, g.getKey()));
                    for (Item it : g.getValue()) addRow(it);
                    indent = base;
                }
            }
            for (Item it : list) if (it.group.isEmpty()) addRow(it);
            indent = 0;
        }
        focus();
    }

    /** A row that opens or closes a group (state kept in the preferences); true when it is open. */
    private boolean toggleRow(String key, String label) {
        boolean open = prefs.getBoolean(key, false);
        row(label, null, open ? "▾" : "▸", () -> {
            prefs.edit().putBoolean(key, !open).apply();
            build();
        });
        return open;
    }

    private void addRow(Item it) {
        {
            boolean in = installed(it), up = in && update(it) != null;
            String summary = it.description + (it.author.isEmpty() ? "" : "\nBy " + it.author)
                    + (it.licence.isEmpty() ? "" : " · " + it.licence);
            row(it.name, summary, up ? "Update" : in ? "Installed" : "Get",
                    up ? () -> doUpdate(it) : in ? null : () -> get(it));
        }
    }

    private boolean installed(Item it) {
        for (String[] f : it.files)
            if (!present(it, f)) return false;
        return !it.files.isEmpty();
    }

    /** The file is in the folder: a .CHG by its title and author (whatever it is called), anything else by name. */
    private boolean present(Item it, String[] f) {
        if (Files.isGame(f[0])) return chgIn(it, f) != null;
        return have.contains(f[0].toUpperCase(Locale.ROOT));
    }

    private Files.Entry chgIn(Item it, String[] f) {
        if (!it.chgTitle.isEmpty()) {
            return haveKey.get(Cart.key(new String[] { it.chgTitle, it.chgAuthor }));
        }
        return haveName.get(f[0].toUpperCase(Locale.ROOT));      /* no header known (a list without it): by name */
    }

    /** The installed .CHG to write the new one over, when the catalogue's version is newer; else null. */
    private Files.Entry update(Item it) {
        for (String[] f : it.files) {
            if (!Files.isGame(f[0])) continue;
            Files.Entry e = chgIn(it, f);
            String old = e == null ? null : haveVersion.get(e.path);
            if (old != null && !it.version.isEmpty() && newer(it.version, old)) return e;
        }
        return null;
    }

    private static boolean newer(String cat, String old) {
        cat = cat.trim().replaceFirst("^[vV]", "");
        old = old.trim().replaceFirst("^[vV]", "");
        if (cat.equals(old)) return false;
        String num = "\\d+(\\.\\d+)*";
        if (!cat.matches(num) || !old.matches(num)) return true;      /* different, not comparable: an update */
        String[] a = cat.split("\\."), b = old.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? Long.parseLong(a[i]) : 0, y = i < b.length ? Long.parseLong(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** Fills in each game's .CHG header from the APK where the file is in it. */
    private void readHeaders(List<Item> list) {
        for (Item it : list)
            for (String[] f : it.files) {
                if (!Files.isGame(f[0]) || f[2].isEmpty()) continue;
                byte[] head = new byte[Cart.HEADER];
                try (InputStream in = getAssets().open(f[2])) {
                    int n = 0;
                    for (int r; n < head.length && (r = in.read(head, n, head.length - n)) > 0; ) n += r;
                    String[] id = Cart.ident(head, n);
                    if (id != null) { it.chgTitle = id[0]; it.chgAuthor = id[1]; it.version = id[2]; }
                } catch (IOException e) {
                    /* name matching stays */
                }
                break;
            }
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
            haveKey.clear();
            haveVersion.clear();
            haveName.clear();
            dupSets = 0;
            build();
            return;
        }
        new Thread(() -> {
            Set<String> names = new HashSet<>();
            Map<String, Files.Entry> keys = new HashMap<>(), byName = new HashMap<>();
            Map<String, String> versions = new HashMap<>();
            int dups = 0;
            for (Files.Entry e : Files.list(getContentResolver(), t)) {
                String n = e.path.substring(e.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
                names.add(n);
                if (!Files.isGame(n)) continue;
                byName.put(n, e);
                String[] id = Cart.ident(getContentResolver(), e.uri);
                if (id == null) continue;
                versions.put(e.path, id[2]);
                if (!keys.containsKey(Cart.key(id))) keys.put(Cart.key(id), e);
                else dups++;
            }
            final int dupCount = dups;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                dupSets = dupCount;
                have = names;
                haveKey.clear();
                haveKey.putAll(keys);
                haveName.clear();
                haveName.putAll(byName);
                haveVersion.clear();
                haveVersion.putAll(versions);
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
            it.developer = g.optString("developer", it.author.isEmpty() ? "Other" : it.author);
            it.description = g.optString("description", "");
            it.licence = g.optString("licence", "");
            it.group = g.optString("group", "");
            it.chgTitle = g.optString("chgTitle", "");
            it.chgAuthor = g.optString("chgAuthor", "");
            it.version = g.optString("version", "");
            JSONArray files = g.getJSONArray("files");
            boolean ok = files.length() > 0;
            for (int k = 0; k < files.length() && ok; k++) {
                JSONObject f = files.getJSONObject(k);
                String name = Files.safeName(f.getString("name")), url = f.optString("url", ""),
                        asset = f.optString("asset", "");
                boolean fromApk = !asset.isEmpty() && url.isEmpty() && asset.startsWith("catalogue-games/")
                        && !asset.contains("..") && !asset.contains("\\");
                if (name == null || !(fromApk || (asset.isEmpty() && url.startsWith("https://")))) ok = false;
                else it.files.add(new String[] { name, url, asset });
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
                        readHeaders(got);
                        items = new ArrayList<>(got);
                        items.addAll(bundled);
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
        List<Item> one = new ArrayList<>();
        one.add(it);
        getAll(one, it.name);
    }

    /** The new .CHG over the installed one, under its own name; data files already there are left alone. */
    private void doUpdate(Item it) {
        Files.Entry e = update(it);
        if (e != null) run(Collections.singletonList(it), it.name, e);
    }

    /** Every game in the list that is not installed yet, one after the other. */
    private void getAll(List<Item> todo, String label) {
        run(todo, label, null);
    }

    private void run(List<Item> todo, String label, Files.Entry over) {
        if (busy) return;
        Uri t = tree();
        if (t == null) {
            Saves.toast(this, "Choose the games folder first");
            return;
        }
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        say("Starting " + label + "…");
        new Thread(() -> {
            String result = label + " is in your games folder";
            for (Item it : todo) {
                try {
                    String cn = null;
                    if (over != null) for (String[] f : it.files) if (Files.isGame(f[0])) { cn = f[0]; break; }
                    download(it, t, cn, over == null ? null : over.uri);
                } catch (IOException e) {
                    String m = e.getMessage();
                    result = e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                            || e instanceof java.net.ConnectException ? "No network connection"
                            : "Could not get " + it.name + (m == null ? "" : ": " + m);
                    break;
                } catch (RuntimeException e) {
                    result = "Could not get " + it.name;
                    break;
                }
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
    private String download(Item it, Uri tree, String over, Uri overDoc) throws IOException {
        ContentResolver cr = getContentResolver();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        int n = 0, all = 0;
        for (String[] f : it.files) if (!present(it, f) || f[0].equals(over)) all++;
        for (String[] f : it.files) {
            boolean replace = f[0].equals(over);
            if (present(it, f) && !replace) continue;
            n++;
            final String label = it.name + (all > 1 ? " (file " + n + " of " + all + ")" : "");
            HttpURLConnection c = f[2].isEmpty() ? open(f[1]) : null;
            Uri doc = null;
            boolean done = false;
            try (InputStream in = c != null ? c.getInputStream() : getAssets().open(f[2])) {
                long total = c != null ? c.getContentLengthLong() : -1;
                /* an update is held in memory: the old game is only overwritten once the new one is all here */
                ByteArrayOutputStream mem = replace ? new ByteArrayOutputStream() : null;
                if (!replace) doc = DocumentsContract.createDocument(cr, root, "application/octet-stream", f[0]);
                if (!replace && doc == null) throw new IOException("cannot write to the games folder");
                try (OutputStream out = replace ? mem : cr.openOutputStream(doc, "wt")) {
                    if (out == null) throw new IOException("cannot write to the games folder");
                    byte[] buf = new byte[1 << 16];
                    long got = 0, shown = 0;
                    for (int r; (r = in.read(buf)) > 0; ) {
                        got += r;
                        if (got > (replace ? 16 << 20 : Files.MAX_FILE)) throw new IOException("file too big");
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
                if (replace) {
                    try (OutputStream out = cr.openOutputStream(overDoc, "wt")) {      /* same file, so its saves stay */
                        if (out == null) throw new IOException("cannot write to the games folder");
                        mem.writeTo(out);
                    }
                }
                done = true;
            } finally {
                if (c != null) c.disconnect();
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
