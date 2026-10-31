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
        String name, author, developer, description, licence, repo = "", group = "";
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
    private final Map<String, JSONObject> releases = new HashMap<>();   /* "owner/repo" -> its latest GitHub release */
    private final List<Item> found = new ArrayList<>();   /* new releases from the developers, not in the list */
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
        loadFound();
        build();
        scanFolder();
        refreshRemote();
    }

    /** New games found by Refresh stay in the list (kept in the preferences, in the catalogue's own format). */
    private void loadFound() {
        try {
            found.clear();
            found.addAll(parse(prefs.getString("cat:found", "{\"games\":[]}")));
            items.addAll(found);
        } catch (Exception e) {
            /* a bad saved list is ignored */
        }
    }

    private void saveFound() {
        try {
            JSONArray games = new JSONArray();
            for (Item it : found) {
                JSONArray files = new JSONArray();
                for (String[] f : it.files) files.put(new JSONObject().put("name", f[0]).put("url", f[1]));
                games.put(new JSONObject().put("name", it.name).put("author", it.author).put("developer", it.developer)
                        .put("description", it.description).put("licence", it.licence).put("version", it.version)
                        .put("repo", it.repo).put("files", files));
            }
            prefs.edit().putString("cat:found", new JSONObject().put("games", games).toString()).apply();
        } catch (Exception e) {
            /* not saved this time */
        }
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
        row("Refresh games", "Check each developer's GitHub for updates and new games", "Refresh", this::refreshSources);
        Set<String> extra = devs();
        row("Add a developer", extra.isEmpty() ? "Type a GitHub name to look for their CHGame games"
                : "Also watching: " + String.join(", ", new java.util.TreeSet<>(extra)), "Add", this::addDeveloper);
        row("Search GitHub", "Look for any new CHGame games from developers not in the list", "Search", this::searchGitHub);
        row("Remove a developer", "Hide a developer's games and stop watching them", "Remove", this::removeDeveloper);
        row("Remove a game", "Hide one game from the list", "Remove", this::removeGame);
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
        found.removeIf(this::gone);
        /* new games sit in their developer's section, under the name the list already uses for that GitHub owner */
        Map<String, String> ownerName = new HashMap<>();
        for (Item it : items) if (!found.contains(it) && !owner(it).isEmpty()) ownerName.putIfAbsent(owner(it), it.developer);
        for (Item it : found) if (ownerName.containsKey(owner(it))) it.developer = ownerName.get(owner(it));
        for (Item it : items) {
            if (gone(it)) continue;
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
            it.repo = g.optString("repo", "");
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
                        items.addAll(found);
                        applyReleases(items);
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

    private static String owner(Item it) {
        int s = it.repo.indexOf('/');
        return s > 0 ? it.repo.substring(0, s).toLowerCase(Locale.ROOT) : "";
    }

    /** Developers the user removed (lower case GitHub names, kept in the preferences). */
    private Set<String> goneSet() {
        Set<String> s = new HashSet<>();
        for (String d : prefs.getString("cat:gone", "").split(",")) if (!d.isEmpty()) s.add(d);
        return s;
    }

    private boolean gone(Item it) {
        return goneSet().contains(owner(it)) || hiddenSet().contains(hideKey(it));
    }

    /** Single games the user removed: the repo (or the name when there is none), kept in the preferences. */
    private Set<String> hiddenSet() {
        Set<String> s = new HashSet<>();
        for (String d : prefs.getString("cat:hidden", "").split("\n")) if (!d.isEmpty()) s.add(d);
        return s;
    }

    private static String hideKey(Item it) {
        return it.repo.isEmpty() ? "name:" + it.name : it.repo;
    }

    private void hideGame(Item it) {
        Set<String> h = hiddenSet();
        h.add(hideKey(it));
        prefs.edit().putString("cat:hidden", String.join("\n", h)).apply();
        found.removeIf(this::gone);
        saveFound();
        statusText = "Removed " + it.name;
        build();
    }

    /** Lets the user pick a game to hide; "Restore all" brings the hidden ones back. */
    private void removeGame() {
        final List<Item> shown = new ArrayList<>();
        for (Item it : items) if (!gone(it)) shown.add(it);
        Collections.sort(shown, (a, b) -> a.name.compareToIgnoreCase(b.name));
        final Set<String> hidden = hiddenSet();
        if (shown.isEmpty() && hidden.isEmpty()) {
            say("No games to remove");
            return;
        }
        final String[] list = new String[shown.size()];
        for (int k = 0; k < list.length; k++) list[k] = shown.get(k).name + " (" + shown.get(k).developer + ")";
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("Remove a game")
                .setItems(list, (d, which) -> {
                    Item it = shown.get(which);
                    final List<Files.Entry> onDisk = new ArrayList<>();
                    for (String[] f : it.files) {
                        if (!Files.isGame(f[0])) continue;
                        Files.Entry e = chgIn(it, f);
                        if (e != null && !onDisk.contains(e)) onDisk.add(e);
                    }
                    if (onDisk.isEmpty()) {
                        hideGame(it);
                        return;
                    }
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Remove " + it.name)
                            .setMessage("It is installed in your games folder. Delete the game file as well?\n(Its saves and data files are left alone.)")
                            .setPositiveButton("Delete file too", (d2, w2) -> {
                                final List<Files.Entry> list2 = new ArrayList<>(onDisk);
                                new Thread(() -> {
                                    int n = 0;
                                    for (Files.Entry e : list2) {
                                        try {
                                            if (DocumentsContract.deleteDocument(getContentResolver(), e.uri)) n++;
                                        } catch (Exception ex) { /* not deleted */ }
                                    }
                                    final int done = n;
                                    runOnUiThread(() -> {
                                        if (isFinishing()) return;
                                        Saves.toast(this, done == list2.size() ? "Deleted the game file" : "Could not delete the game file");
                                        hideGame(it);
                                        scanFolder();
                                    });
                                }, "catalogue-delete").start();
                            })
                            .setNeutralButton("Just hide", (d2, w2) -> hideGame(it))
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null);
        if (!hidden.isEmpty())
            b.setNeutralButton("Restore all (" + hidden.size() + ")", (d, w) -> {
                prefs.edit().remove("cat:hidden").apply();
                statusText = "Restored removed games";
                build();
            });
        b.show();
    }

    /** Lets the user pick a developer to hide; Add a developer brings them back. */
    private void removeDeveloper() {
        final java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (Item it : items) if (!owner(it).isEmpty() && !gone(it)) names.add(owner(it));
        for (String d : devs()) names.add(d.toLowerCase(Locale.ROOT));
        names.removeAll(goneSet());
        if (names.isEmpty()) {
            say("No developers to remove");
            return;
        }
        final String[] list = names.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Remove a developer")
                .setItems(list, (d, which) -> {
                    String n = list[which];
                    Set<String> g = goneSet();
                    g.add(n);
                    Set<String> s = devs();
                    for (String x : new ArrayList<>(s)) if (x.equalsIgnoreCase(n)) s.remove(x);
                    prefs.edit().putString("cat:gone", String.join(",", g)).putString("cat:devs", String.join(",", s)).apply();
                    found.removeIf(this::gone);
                    saveFound();
                    statusText = "Removed " + n;
                    build();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** GitHub names the user added by hand (kept in the preferences). */
    private Set<String> devs() {
        Set<String> s = new HashSet<>();
        for (String d : prefs.getString("cat:devs", "").split(",")) if (d.matches("[\\w-]+")) s.add(d);
        return s;
    }

    /** Asks for a GitHub name (or a pasted address), remembers it, then looks for that developer's games. */
    private void addDeveloper() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setHint("GitHub name, e.g. juliendorra");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Add a developer")
                .setView(in)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> {
                    String n = in.getText().toString().trim().replaceFirst("^(https?://)?(www\\.)?github\\.com/", "");
                    int slash = n.indexOf('/');
                    if (slash >= 0) n = n.substring(0, slash);
                    if (n.startsWith("@")) n = n.substring(1);
                    if (!n.matches("[\\w-]+")) {
                        say("That is not a GitHub name");
                        return;
                    }
                    Set<String> s = devs();
                    s.add(n);
                    Set<String> g = goneSet();
                    g.remove(n.toLowerCase(Locale.ROOT));
                    prefs.edit().putString("cat:devs", String.join(",", s)).putString("cat:gone", String.join(",", g)).apply();
                    build();
                    refreshSources();
                })
                .show();
    }

    /** Asks each game's own GitHub repo for its latest release, then points the games at those files. */
    private void refreshSources() {
        if (busy) return;
        busy = true;
        say("Checking developers' repos…");
        new Thread(() -> {
            Set<String> repos = new HashSet<>();
            for (Item it : items) if (it.repo.matches("[\\w.-]+/[\\w.-]+") && !gone(it)) repos.add(it.repo);
            int ok = 0, i = 0;
            final StringBuilder dbg = new StringBuilder();
            for (String repo : repos) {
                final String line = "Checking " + repo + " (" + (++i) + "/" + repos.size() + ")";
                runOnUiThread(() -> say(line));
                try {
                    /* the web page, not the API (which allows 60 calls an hour): /releases/latest redirects to the tag */
                    HttpURLConnection c = open("https://github.com/" + repo + "/releases/latest");
                    String u = c.getURL().toString();
                    c.disconnect();
                    int at = u.lastIndexOf("/tag/");
                    if (at < 0) continue;
                    String tag = Uri.decode(u.substring(at + 5));
                    ok++;
                    JSONArray assets = new JSONArray();
                    for (Item it : items) if (it.repo.equals(repo)) {
                        if (tag.replaceFirst("^[vV]", "").equals(it.version)) continue;      /* already that version */
                        for (String[] f : it.files) {
                            String link = "https://github.com/" + repo + "/releases/download/" + Uri.encode(tag) + "/" + Uri.encode(f[0]);
                            try {
                                open(link).disconnect();
                                assets.put(new JSONObject().put("name", f[0]).put("browser_download_url", link));
                            } catch (IOException e) {
                                /* not in that release */
                            }
                        }
                    }
                    releases.put(repo, new JSONObject().put("tag_name", tag).put("assets", assets));
                } catch (Exception e) {
                    dbg.append("\n").append(repo).append(": ").append(e);
                }
            }
            final int got = ok, total = repos.size();
            Set<String> owners = new HashSet<>();
            for (String repo : repos) owners.add(repo.substring(0, repo.indexOf('/')));
            owners.addAll(devs());
            Set<String> known = new HashSet<>(repos);
            for (Item it : found) known.add(it.repo);
            final List<Item> fresh = new ArrayList<>();
            if (got > 0 || !devs().isEmpty()) for (String owner : owners) {
                runOnUiThread(() -> say("Looking for new games from " + owner + "…"));
                try {
                    HttpURLConnection c = open("https://api.github.com/users/" + owner + "/repos?per_page=100&sort=pushed");
                    JSONArray list;
                    try (InputStream in = c.getInputStream()) {
                        list = new JSONArray(readAll(in, 4 << 20));
                    } finally {
                        c.disconnect();
                    }
                    dbg.append("\n").append(owner).append(": ").append(list.length()).append(" repos");
                    int tried = 0;
                    for (int k = 0; k < list.length() && tried < 12; k++) {
                        JSONObject r = list.getJSONObject(k);
                        String full = r.optString("full_name");
                        String blob = (r.optString("name") + " " + r.optString("description") + " " + r.optJSONArray("topics")).toLowerCase(Locale.ROOT);
                        if (r.optBoolean("fork") || known.contains(full) || !full.matches("[\\w.-]+/[\\w.-]+")
                                || !(blob.contains("chgame") || r.optString("name").startsWith("CH"))) continue;
                        tried++;
                        dbg.append("\ntry ").append(full);
                        try {
                            HttpURLConnection rc = open("https://api.github.com/repos/" + full + "/releases/latest");
                            try (InputStream in = rc.getInputStream()) {
                                JSONObject rel = new JSONObject(readAll(in, 1 << 20));
                                Item it = newItem(r, rel);
                                if (it != null) fresh.add(it);
                                else dbg.append(" no .chg in ").append(rel.optString("tag_name"));
                            } finally {
                                rc.disconnect();
                            }
                        } catch (Exception e) {
                            dbg.append(" ").append(e);
                        }
                    }
                } catch (Exception e) {
                    dbg.append("\n").append(owner).append(" failed: ").append(e);
                }
            }
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) return;
                found.addAll(fresh);
                items.addAll(fresh);
                saveFound();
                applyReleases(items);
                say("");
                Saves.toast(this, got == 0 ? "Could not reach GitHub" : "Checked " + got + " of " + total + " repos, "
                        + (fresh.isEmpty() ? "no new games" : fresh.size() + " new"));
                build();
            });
        }, "catalogue-sources").start();
    }

    /** Searches all of GitHub for repos that mention CHGame and have a release with a .chg file; new ones join the list. */
    private void searchGitHub() {
        if (busy) return;
        busy = true;
        say("Searching GitHub for CHGame games…");
        new Thread(() -> {
            Set<String> known = new HashSet<>();
            Set<String> hid0 = hiddenSet();
            for (Item it : items) if (!hid0.contains(hideKey(it))) known.add(it.repo);   /* removed games are looked for again */
            final Set<String> backRepos = new HashSet<>();
            Set<String> goneOwners = goneSet();
            /* repos checked in the last week with no CHGame release are skipped ("repo=day" in cat:nogame) */
            long today = System.currentTimeMillis() / 86400000L;
            Map<String, Long> skip = new HashMap<>();
            for (String e : prefs.getString("cat:nogame", "").split(",")) {
                int q = e.lastIndexOf('=');
                if (q < 1) continue;
                try {
                    long d = Long.parseLong(e.substring(q + 1));
                    if (today - d < 7) skip.put(e.substring(0, q), d);
                } catch (NumberFormatException ex) { /* dropped */ }
            }
            int skipped = 0;
            final List<Item> fresh = new ArrayList<>();
            final Set<String> restored = new HashSet<>();
            final List<String> nogame = new ArrayList<>();
            String err = null;
            int tried = 0, hits = 0;
            try {
                // removed developers: look at their own repos directly
                for (String gd : goneOwners) {
                    if (!gd.matches("[\\w.-]+")) continue;
                    runOnUiThread(() -> say("Checking removed developer " + gd));
                    try {
                        HttpURLConnection gc = open("https://api.github.com/users/" + gd + "/repos?sort=updated&per_page=30");
                        JSONArray repos;
                        try (InputStream in = gc.getInputStream()) {
                            repos = new JSONArray(readAll(in, 4 << 20));
                        } finally {
                            gc.disconnect();
                        }
                        int looked = 0;
                        for (int k = 0; k < repos.length() && looked < 10; k++) {
                            JSONObject r = repos.getJSONObject(k);
                            String full = r.optString("full_name");
                            if (r.optBoolean("fork") || !full.matches("[\\w.-]+/[\\w.-]+")) continue;
                            if (known.contains(full)) { restored.add(gd); break; }
                            if (skip.containsKey(full)) { skipped++; continue; }
                            looked++;
                            try {
                                HttpURLConnection rc = open("https://api.github.com/repos/" + full + "/releases/latest");
                                try (InputStream in = rc.getInputStream()) {
                                    Item it = newItem(r, new JSONObject(readAll(in, 1 << 20)));
                                    if (it != null) { fresh.add(it); known.add(full); restored.add(gd); break; }
                                } finally {
                                    rc.disconnect();
                                }
                            } catch (java.io.FileNotFoundException e) {
                                skip.put(full, today); /* no release at all */
                            } catch (Exception e) { /* network or rate limit: try again next time */ }
                        }
                    } catch (Exception e) { nogame.add(gd + " (could not read repos)"); }
                }
                HttpURLConnection c = open("https://api.github.com/search/repositories?q="
                        + Uri.encode("chgame in:name,description,topics,readme fork:false") + "&sort=updated&per_page=100");
                JSONArray list;
                try (InputStream in = c.getInputStream()) {
                    list = new JSONObject(readAll(in, 4 << 20)).getJSONArray("items");
                } finally {
                    c.disconnect();
                }
                hits = list.length();
                for (int k = 0; k < list.length() && tried < 40; k++) {
                    JSONObject r = list.getJSONObject(k);
                    String full = r.optString("full_name");
                    JSONObject own = r.optJSONObject("owner");
                    String login = own == null ? "" : own.optString("login", "").toLowerCase(Locale.ROOT);
                    if (r.optBoolean("fork") || !full.matches("[\\w.-]+/[\\w.-]+")) continue;
                    if (hid0.contains(full)) { backRepos.add(full); continue; }   /* a game the user removed: found again */
                    boolean wasGone = goneOwners.contains(login);
                    if (known.contains(full)) {
                        if (wasGone) restored.add(login);
                        continue;
                    }
                    if (skip.containsKey(full)) { skipped++; continue; }
                    tried++;
                    final String line = "Looking at " + full;
                    runOnUiThread(() -> say(line));
                    try {
                        HttpURLConnection rc = open("https://api.github.com/repos/" + full + "/releases/latest");
                        try (InputStream in = rc.getInputStream()) {
                            Item it = newItem(r, new JSONObject(readAll(in, 1 << 20)));
                            if (it != null) {
                                fresh.add(it);
                                if (wasGone) restored.add(login);
                            } else { nogame.add(full); skip.put(full, today); }
                        } finally {
                            rc.disconnect();
                        }
                    } catch (java.io.FileNotFoundException e) {
                        nogame.add(full);
                        skip.put(full, today);
                    } catch (Exception e) {
                        nogame.add(full);
                    }
                }
            } catch (Exception e) {
                err = e.toString();
            }
            StringBuilder sk = new StringBuilder();
            for (Map.Entry<String, Long> e : skip.entrySet()) {
                if (sk.length() > 0) sk.append(',');
                sk.append(e.getKey()).append('=').append(e.getValue());
            }
            prefs.edit().putString("cat:nogame", sk.toString()).apply();
            final String error = err;
            final int h = hits, t = tried, sp = skipped;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) return;
                if (!restored.isEmpty()) {
                    Set<String> g = goneSet(), s = devs();
                    g.removeAll(restored);
                    s.addAll(restored);
                    prefs.edit().putString("cat:gone", String.join(",", g)).putString("cat:devs", String.join(",", s)).apply();
                }
                Set<String> hid = hiddenSet();
                final List<String> backGames = new ArrayList<>();
                Set<String> freshRepos = new HashSet<>();
                for (Item it : fresh) {
                    freshRepos.add(it.repo);
                    if (hid.remove(hideKey(it))) backGames.add(it.name);
                }
                for (Item it : items) if (backRepos.contains(it.repo) && hid.remove(hideKey(it))) backGames.add(it.name);
                if (!backGames.isEmpty()) prefs.edit().putString("cat:hidden", String.join("\n", hid)).apply();
                found.removeIf(x -> freshRepos.contains(x.repo) && !x.repo.isEmpty());
                items.removeIf(x -> freshRepos.contains(x.repo) && !x.repo.isEmpty());
                found.addAll(fresh);
                items.addAll(fresh);
                saveFound();
                say("");
                build();
                StringBuilder sb = new StringBuilder();
                if (error != null) sb.append("Search failed: ").append(error).append("\n\n");
                sb.append("Searched ").append(h).append(" repos, looked at ").append(t).append(" new ones");
                if (sp > 0) sb.append(" (skipped ").append(sp).append(" with no release, checked this week)");
                sb.append(".\n\n");
                if (fresh.isEmpty()) sb.append("No new games found.\n");
                else {
                    sb.append("New games (now in the list under their developer):\n");
                    for (Item it : fresh) sb.append("\n• ").append(it.name).append(" — ").append(it.repo);
                    sb.append("\n");
                }
                if (!backGames.isEmpty()) sb.append("\nBrought back removed games: ").append(String.join(", ", backGames)).append("\n");
                if (!restored.isEmpty()) sb.append("\nBrought back removed developers: ").append(String.join(", ", restored)).append("\n");
                if (!nogame.isEmpty()) sb.append("\nChecked, no CHGame release:\n").append(String.join("\n", nogame));
                new android.app.AlertDialog.Builder(this).setTitle("Search results")
                        .setMessage(sb.toString()).setPositiveButton("OK", null).show();
            });
        }, "catalogue-search").start();
    }

    /** A game from a developer's repo that the list does not have: its .CHG and .DAT files from the release. */
    private static Item newItem(JSONObject repo, JSONObject rel) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) return null;
        Item it = new Item();
        boolean chg = false;
        for (int k = 0; k < assets.length(); k++) {
            JSONObject a = assets.optJSONObject(k);
            if (a == null) continue;
            String n = Files.safeName(a.optString("name")), u = a.optString("browser_download_url");
            if (n == null || !u.startsWith("https://")) continue;
            if (Files.isGame(n)) chg = true;
            else if (!n.toUpperCase(Locale.ROOT).endsWith(".DAT")) continue;
            it.files.add(new String[] { n, u, "" });
        }
        if (!chg) return null;
        it.repo = repo.optString("full_name");
        it.name = repo.optString("name");
        JSONObject own = repo.optJSONObject("owner");
        it.developer = it.author = own != null ? own.optString("login", "Other") : "Other";
        String d = repo.optString("description", "");
        it.description = "New, not checked yet" + (d.isEmpty() || d.equals("null") ? "" : ": " + d);
        JSONObject lic = repo.optJSONObject("license");
        it.licence = lic == null ? "" : lic.optString("spdx_id", "");
        if (it.licence.equals("NOASSERTION")) it.licence = "";
        String tag = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
        if (tag.matches("\\d+(\\.\\d+)*")) it.version = tag;
        return it;
    }

    /** Files a release has under the same name replace the bundled/listed ones; the tag becomes the version. */
    private void applyReleases(List<Item> list) {
        for (Item it : list) {
            JSONObject rel = releases.get(it.repo);
            if (rel == null) continue;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) continue;
            boolean all = true;
            List<String[]> nf = new ArrayList<>();
            for (String[] f : it.files) {
                String url = null;
                for (int k = 0; k < assets.length(); k++) {
                    JSONObject a = assets.optJSONObject(k);
                    if (a != null && f[0].equalsIgnoreCase(a.optString("name"))
                            && a.optString("browser_download_url").startsWith("https://"))
                        url = a.optString("browser_download_url");
                }
                if (url == null) { all = false; break; }
                nf.add(new String[] { f[0], url, "" });
            }
            if (!all) continue;       /* the release lacks a file: leave the game as it was */
            it.files.clear();
            it.files.addAll(nf);
            String tag = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
            if (tag.matches("\\d+(\\.\\d+)*")) it.version = tag;
        }
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
