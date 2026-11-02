package com.jadflat.chgame;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Update check for the games already installed, run quietly when the app opens: each installed
 * .CHG that the catalogue knows a GitHub repo for is compared with that repo's latest release.
 * A game with a newer one gets "upd:<FILE>" = "tag|repo|asset" in the preferences; the list
 * shows it in green and the right panel offers an Update button.
 */
final class Updates {
    private Updates() {}

    private static String key(String file) { return "upd:" + file.toUpperCase(Locale.ROOT); }

    /** Latest release tag for the installed game file, or null when it is up to date. */
    static String[] pending(SharedPreferences p, String file) {
        String v = p.getString(key(file), null);
        return v == null ? null : v.split("\\|", 3);
    }

    /** Catalogue file name (upper case) -> {repo, asset name}, from the bundled lists, the found games and the project's list. */
    private static Map<String, String[]> sources(Context c, SharedPreferences prefs) {
        Map<String, String[]> out = new HashMap<>();
        for (String a : new String[] { "catalogue.json", "catalogue-private.json" }) {
            try (InputStream in = c.getAssets().open(a)) {
                add(out, CatalogueActivity.readAll(in, 1 << 20));
            } catch (Exception e) {
                /* not there */
            }
        }
        add(out, prefs.getString("cat:found", "{\"games\":[]}"));
        try {
            HttpURLConnection h = CatalogueActivity.open(CatalogueActivity.REMOTE);
            try (InputStream in = h.getInputStream()) {
                add(out, CatalogueActivity.readAll(in, 1 << 20));
            } finally {
                h.disconnect();
            }
        } catch (Exception e) {
            /* no network: the bundled list is used */
        }
        return out;
    }

    /** The GitHub repo ("owner/name") a game file belongs to, from the bundled and found lists (no network); null when unknown. */
    static String repoFor(Context c, SharedPreferences prefs, String file) {
        Map<String, String[]> out = new HashMap<>();
        for (String a : new String[] { "catalogue.json", "catalogue-private.json" }) {
            try (InputStream in = c.getAssets().open(a)) {
                add(out, CatalogueActivity.readAll(in, 1 << 20));
            } catch (Exception e) {
                /* not there */
            }
        }
        add(out, prefs.getString("cat:found", "{\"games\":[]}"));
        String[] s = out.get(file.toUpperCase(Locale.ROOT));
        return s == null ? null : s[0];
    }

    private static void add(Map<String, String[]> out, String json) {
        try {
            JSONArray games = new JSONObject(json).getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                String repo = g.optString("repo", "");
                if (!repo.matches("[\\w.-]+/[\\w.-]+")) continue;
                JSONArray files = g.getJSONArray("files");
                for (int k = 0; k < files.length(); k++) {
                    String n = files.getJSONObject(k).optString("name", "");
                    if (!Files.isGame(n)) continue;
                    String key = n.toUpperCase(Locale.ROOT);
                    out.put(key, new String[] { repo, n });
                    out.putIfAbsent(key.replaceFirst("[.]CHG$","-REV0.CHG"), new String[] { repo, n });    /* the packed "-rev0.chg" copy of the same game */
                }
            }
        } catch (Exception e) {
            /* a bad list adds nothing */
        }
    }

    /** Blocking: look at every installed game; true when the set of games with updates changed. */
    static boolean check(Context c, SharedPreferences prefs, Uri tree) {
        ContentResolver cr = c.getContentResolver();
        Map<String, String[]> src = sources(c, prefs);
        Map<String, String> tags = new HashMap<>();       /* repo -> latest tag ("" when unreachable) */
        boolean changed = false;
        SharedPreferences.Editor ed = prefs.edit();
        for (Files.Entry e : Files.list(cr, tree)) {
            if (!Files.isGame(e.path)) continue;
            String file = e.path.substring(e.path.lastIndexOf('/') + 1);
            String[] s = src.get(file.toUpperCase(Locale.ROOT));
            if (s == null) continue;
            String tag = tags.get(s[0]);
            if (tag == null) {
                tag = "";
                try {
                    HttpURLConnection h = CatalogueActivity.open("https://github.com/" + s[0] + "/releases/latest");
                    String u = h.getURL().toString();
                    h.disconnect();
                    int at = u.lastIndexOf("/tag/");
                    if (at >= 0) tag = Uri.decode(u.substring(at + 5));
                } catch (IOException ex) {
                    /* leave this repo as it was */
                }
                tags.put(s[0], tag);
            }
            if (tag.isEmpty()) {
                /* no release: no version can be worked out */
                ed.remove("ver:" + file.toUpperCase(Locale.ROOT));
                continue;
            }
            String bare = tag.replaceFirst("^[vV]", "");
            ed.putString("ver:" + file.toUpperCase(Locale.ROOT), bare);       /* shown when the game's header holds no version */
            String got = prefs.getString("got:" + file.toUpperCase(Locale.ROOT), null);
            String[] id = Cart.ident(cr, e.uri);
            boolean up = !bare.equals(got == null ? null : got.replaceFirst("^[vV]", ""))
                    && id != null && CatalogueActivity.newer(bare, id[2]);
            String old = prefs.getString(key(file), null);
            String now = up ? tag + "|" + s[0] + "|" + s[1] : null;
            if (now == null ? old != null : !now.equals(old)) changed = true;
            if (now == null) ed.remove(key(file)); else ed.putString(key(file), now);
        }
        ed.apply();
        return changed;
    }

    /** Blocking: a game whose header names no author gets its GitHub owner ("auth:<FILE>"), for the list and panel; true when one was added. */
    static boolean authors(Context c, SharedPreferences prefs, Uri tree) {
        ContentResolver cr = c.getContentResolver();
        Map<String, String[]> src = null;
        boolean changed = false;
        Set<String> orphans = new java.util.HashSet<>();
        for (Files.Entry e : Files.list(cr, tree)) {
            if (!Files.isGame(e.path)) continue;
            String file = e.path.substring(e.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
            if (prefs.contains("got:" + file) && (repoFor(c, prefs, file) == null || bareName(prefs, file))) orphans.add(file);
        }
        if (!orphans.isEmpty() && backfill(c, prefs, null, orphans)) changed = true;
        for (Files.Entry e : Files.list(cr, tree)) {
            if (!Files.isGame(e.path)) continue;
            String file = e.path.substring(e.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
            if (prefs.contains("auth:" + file)) continue;
            String[] id = Cart.ident(cr, e.uri);
            if (id == null || !id[1].isEmpty()) continue;
            Cart cart = Cart.read(cr, e.uri);
            if (cart == null || !cart.credit().isEmpty()) continue;
            if (src == null) src = sources(c, prefs);
            String[] s = src.get(file);
            if (s == null) continue;
            prefs.edit().putString("auth:" + file, s[0].substring(0, s[0].indexOf('/'))).apply();
            changed = true;
        }
        return changed;
    }

    /** Keeps a game installed from a developer's release in the found list, so Get games lists it and its repo
     *  (author, "Open on GitHub", updates) is known. */
    static void remember(SharedPreferences prefs, String repo, String file, String tag, String name, String desc) {
        try {
            JSONObject all = new JSONObject(prefs.getString("cat:found", "{\"games\":[]}"));
            JSONArray games = all.getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                JSONArray fs = g.optJSONArray("files");
                if (!repo.equals(g.optString("repo")) || fs == null) continue;
                for (int k = 0; k < fs.length(); k++)
                    if (file.equalsIgnoreCase(fs.getJSONObject(k).optString("name"))) {
                        if (desc.isEmpty()) return;                 /* a better name and description than the file's */
                        g.put("name", name).put("description", desc);
                        prefs.edit().putString("cat:found", all.toString()).apply();
                        return;
                    }
            }
            String owner = repo.substring(0, repo.indexOf('/'));
            games.put(new JSONObject().put("name", name).put("author", owner).put("developer", owner)
                    .put("description", desc).put("licence", "").put("version", tag.replaceFirst("^[vV]", ""))
                    .put("repo", repo).put("files", new JSONArray().put(new JSONObject().put("name", file)
                            .put("url", "https://github.com/" + repo + "/releases/download/" + Uri.encode(tag) + "/" + Uri.encode(file)))));
            prefs.edit().putString("cat:found", all.toString()).apply();
        } catch (Exception e) {
            /* not kept this time */
        }
    }

    /** The found list holds this file under its file name as the game's name (kept by an earlier build): to be renamed. */
    private static boolean bareName(SharedPreferences prefs, String file) {
        try {
            JSONArray games = new JSONObject(prefs.getString("cat:found", "{\"games\":[]}")).getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                JSONArray fs = g.optJSONArray("files");
                for (int k = 0; fs != null && k < fs.length(); k++)
                    if (file.equalsIgnoreCase(fs.getJSONObject(k).optString("name")))
                        return g.optString("description", "").isEmpty() && file.replaceFirst("(?i)[.]chg$", "").equalsIgnoreCase(g.optString("name"));
            }
        } catch (Exception e) {
            /* not bare */
        }
        return false;
    }

    /** Blocking: games installed from "new" offers before they were kept (a "got:" with no known repo): find
     *  their repo among the "newdone:" ones by the release's file names, and keep them. True when any was. */
    private static boolean backfill(Context c, SharedPreferences prefs, Map<String, String[]> unused, Set<String> orphans) {
        boolean any = false;
        for (String key : new java.util.ArrayList<>(prefs.getAll().keySet())) {
            if (!key.startsWith("newdone:") || orphans.isEmpty()) continue;
            String repo = key.substring(8);
            if (!repo.matches("[\\w.-]+/[\\w.-]+")) continue;
            try {
                HttpURLConnection h = CatalogueActivity.open("https://api.github.com/repos/" + repo + "/releases/latest");
                String body;
                try (InputStream in = h.getInputStream()) {
                    body = CatalogueActivity.readAll(in, 1 << 20);
                } finally {
                    h.disconnect();
                }
                JSONObject rel = new JSONObject(body);
                String title = "", desc = "";
                try {
                    HttpURLConnection rh = CatalogueActivity.open("https://api.github.com/repos/" + repo);
                    try (InputStream in = rh.getInputStream()) {
                        desc = new JSONObject(CatalogueActivity.readAll(in, 1 << 20)).optString("description", "");
                    } finally {
                        rh.disconnect();
                    }
                } catch (Exception e) {
                    /* no description */
                }
                for (String w : repo.substring(repo.indexOf('/') + 1).split("[-_.]+"))
                    if (!w.isEmpty()) title += (title.isEmpty() ? "" : " ") + Character.toUpperCase(w.charAt(0)) + w.substring(1);
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    String name = assets.getJSONObject(i).optString("name");
                    if (!orphans.remove(name.toUpperCase(Locale.ROOT))) continue;
                    remember(prefs, repo, name, rel.optString("tag_name"), title, desc);
                    any = true;
                }
            } catch (Exception e) {
                /* offline or rate limited: tried again next time */
            }
        }
        return any;
    }

    /** The GitHub owner kept for a game with no author in its header, or "". */
    static String owner(SharedPreferences prefs, String file) {
        return prefs.getString("auth:" + file.toUpperCase(Locale.ROOT), "");
    }

    /* ---- new games from developers whose games are installed ---- */

    /** The file a new game is saved as: the release's .CHG, or the repo's name when it ships a package. */
    static String newFile(JSONObject n) {
        String asset = n.optString("asset", "");
        if (asset.toLowerCase(Locale.ROOT).endsWith(".chg")) return asset;
        String base = n.optString("repo", "game");
        base = base.substring(base.indexOf('/') + 1).replaceAll("[^\\w.-]", "");
        return (base.isEmpty() ? "game" : base) + ".CHG";
    }

    /** Blocking: a game's file was removed. Offered again: it shows in the list as a not-installed game; else it is not offered again. */
    static void removed(Context c, SharedPreferences prefs, String file, String name, boolean offerAgain) {
        String key = file.toUpperCase(Locale.ROOT);
        String[] src = sources(c, prefs).get(key);
        SharedPreferences.Editor ed = prefs.edit();
        if (src == null) {                      /* not a catalogue game: nothing to offer */
            ed.remove("got:" + key).remove("upd:" + key).remove("gotUrl:" + key).apply();
            return;
        }
        String repo = src[0];
        dropPics(c, repo);
        if (offerAgain) {
            String tag = prefs.getString("got:" + key, "");
            /* where it came from: a .chgame package is not named like the installed .CHG (src[1]) */
            String url = prefs.getString("gotUrl:" + key, null);
            if (url == null) url = gameUrl(repo, tag);
            try {
                JSONObject n = new JSONObject().put("repo", repo).put("tag", tag)
                        .put("asset", src[1]).put("name", name).put("owner", repo.substring(0, repo.indexOf('/')))
                        .put("desc", "Removed from this device.");
                if (url != null) n.put("url", url);
                ed.putString("new:" + repo, n.toString());
            } catch (Exception ex) {
                /* not offered */
            }
            ed.remove("newdone:" + repo).remove("upd:" + key).remove("gotUrl:" + key).apply();
            enrich(prefs, repo);                /* its description, version and author again */
            return;
        } else {
            ed.putBoolean("newdone:" + repo, true).remove("new:" + repo).remove("got:" + key).remove("upd:" + key).remove("gotUrl:" + key);
        }
        ed.apply();
    }

    /** A game that is only offered (not in the folder): true when this file is one of the new games; with dismiss, it is not offered again. */
    static boolean offered(SharedPreferences p, String file, boolean dismiss) {
        for (JSONObject n : newGames(p)) {
            if (!newFile(n).equalsIgnoreCase(file)) continue;
            if (dismiss) p.edit().remove("new:" + n.optString("repo")).putBoolean("newdone:" + n.optString("repo"), true).apply();
            return true;
        }
        return false;
    }

    /** The games removed or dismissed so that they are not offered ("newdone:" in the preferences). */
    static int dismissedCount(SharedPreferences p) {
        int n = 0;
        for (String k : p.getAll().keySet()) if (k.startsWith("newdone:")) n++;
        return n;
    }

    /** Global reset: every game that was removed or dismissed can be offered again. */
    static void resetDismissed(SharedPreferences p) {
        SharedPreferences.Editor ed = p.edit();
        for (String k : p.getAll().keySet()) if (k.startsWith("newdone:")) ed.remove(k);
        ed.remove("newAt2").remove("newAt11").remove("searchAt").remove("newSeen").apply();
    }

    /** Copies the bundled catalogue's games (those with a repo and a bundled file) into "cat:bundled", once per app version. */
    static boolean seedBundled(Context c, SharedPreferences prefs, String version) {
        if (version.equals(prefs.getString("cat:bundledVer", ""))) return false;
        try (InputStream in = c.getAssets().open("catalogue-private.json")) {
            JSONObject all = new JSONObject(CatalogueActivity.readAll(in, 1 << 20));
            prefs.edit().putString("cat:bundled", all.toString()).putString("cat:bundledVer", version).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The new games found so far ("new:<repo>" in the preferences), not yet installed. */
    static java.util.List<JSONObject> newGames(SharedPreferences p) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (!e.getKey().startsWith("new:") || !(e.getValue() instanceof String)) continue;
            try {
                out.add(new JSONObject((String) e.getValue()));
            } catch (Exception ex) {
                /* a bad entry is skipped */
            }
        }
        for (String src : new String[] { "cat:found", "cat:bundled" }) try {   /* the games Get games' Refresh found, and the ones bundled, as new games too */
            JSONArray games = new JSONObject(p.getString(src, "{\"games\":[]}")).getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                String repo = g.optString("repo", "");
                JSONArray files = g.optJSONArray("files");
                if (!repo.matches("[\\w.-]+/[\\w.-]+") || files == null || p.contains("newdone:" + repo)) continue;
                for (int k = 0; k < files.length(); k++) {
                    String n = files.getJSONObject(k).optString("name", "");
                    if (!Files.isGame(n)) continue;
                    JSONObject o = new JSONObject().put("repo", repo).put("tag", g.optString("version", "")).put("asset", n)
                            .put("name", g.optString("name", n)).put("owner", repo.substring(0, repo.indexOf('/')))
                            .put("desc", g.optString("description", "").replaceFirst("^New(, not checked yet)?(: |$)", "")).put("url", files.getJSONObject(k).optString("url", ""));
                    String local = files.getJSONObject(k).optString("asset", "");
                    if (src.equals("cat:bundled") && !local.isEmpty()) {       /* installed from the app itself */
                        o.put("local", local).put("quiet", true).put("extra", files);
                    }
                    out.add(o);
                    break;
                }
            }
        } catch (Exception ex) {
            /* none */
        }
        return out;
    }

    /** The "new:<repo>" entry for a repo's latest release: its .CHG, or a small .chgame package (offered as <repo>.CHG); null when it has neither. */
    private static JSONObject offer(JSONObject r, JSONObject rel) throws org.json.JSONException {
        if (rel == null) return null;
        JSONArray assets = rel.optJSONArray("assets");
        String asset = null, url = "", pkg = null, pkgUrl = "";
        for (int k = 0; assets != null && k < assets.length(); k++) {
            JSONObject a = assets.getJSONObject(k);
            String an = a.optString("name", "");
            if (Files.isGame(an)) { asset = an; break; }
            /* one game is about 30 KB; a far bigger package is a bundle of many, not one game */
            if (pkg == null && an.toLowerCase(Locale.ROOT).endsWith(".chgame") && a.optLong("size", 0) > 0 && a.optLong("size", 0) <= 300000
                    && a.optString("browser_download_url").startsWith("https://")) {
                pkg = an;
                pkgUrl = a.optString("browser_download_url");
            }
        }
        if (asset == null && pkg != null) {
            String base = r.optString("name", "game").replaceAll("[^\\w.-]", "");
            asset = (base.isEmpty() ? "game" : base) + ".CHG";
            url = pkgUrl;
        }
        if (asset == null) return null;
        String full = r.optString("full_name");
        return new JSONObject().put("repo", full).put("tag", rel.optString("tag_name"))
                .put("asset", asset).put("name", r.optString("name").replaceFirst("^CH(?=[A-Z])", ""))
                .put("owner", full.substring(0, full.indexOf('/'))).put("url", url)
                .put("desc", r.optString("description", "").replaceFirst("^null$", ""));
    }

    /**
     * A repo's latest release read from its github.com pages (they don't count against the API's 60 calls an hour),
     * shaped like the API's answer: tag_name and assets (name, browser_download_url, size); null when it has no release.
     */
    private static JSONObject releasePage(String full) throws IOException, org.json.JSONException {
        HttpURLConnection rc = CatalogueActivity.open("https://github.com/" + full + "/releases/latest");
        String u = rc.getURL().toString();
        rc.disconnect();
        int at = u.lastIndexOf("/tag/");
        if (at < 0) return null;
        String tag = Uri.decode(u.substring(at + 5));
        HttpURLConnection h = CatalogueActivity.open("https://github.com/" + full + "/releases/expanded_assets/" + Uri.encode(tag));
        String html;
        try (InputStream in = h.getInputStream()) {
            html = CatalogueActivity.readAll(in, 1 << 20);
        } finally {
            h.disconnect();
        }
        JSONArray assets = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/releases/download/[^/\"]+/([^\"?]+)\"").matcher(html);
        while (m.find() && assets.length() < 12) {
            String raw = m.group(1);
            if (!seen.add(raw)) continue;
            String name = Uri.decode(raw), url = "https://github.com/" + full + "/releases/download/" + Uri.encode(tag) + "/" + raw;
            long size = 0;
            if (name.toLowerCase(Locale.ROOT).endsWith(".chgame")) {      /* only a package's size matters (a bundle is far bigger than one game) */
                try {
                    HttpURLConnection sc = CatalogueActivity.open(url);
                    size = sc.getContentLengthLong();
                    sc.disconnect();
                } catch (IOException ex) { /* size unknown: 0 */ }
            }
            assets.put(new JSONObject().put("name", name).put("browser_download_url", url).put("size", size));
        }
        return new JSONObject().put("tag_name", tag).put("assets", assets);
    }

    /**
     * Blocking: a not-installed game offered from a .chgame package: reads the package's info.json for the game's own
     * description, version and author, and keeps them in its "new:" entry (once). True when the entry changed.
     */
    static boolean enrich(SharedPreferences prefs, String repo) {
        try {
            JSONObject n = new JSONObject(prefs.getString("new:" + repo, ""));
            String url = n.optString("url", "");
            if (!url.startsWith("https://") || n.optBoolean("enriched")) return false;
            ByteArrayOutputStream mem = new ByteArrayOutputStream();
            HttpURLConnection h = CatalogueActivity.open(url);
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[1 << 14];
                for (int r; (r = in.read(buf)) > 0; ) {
                    mem.write(buf, 0, r);
                    if (mem.size() > 400000) return false;
                }
            } finally {
                h.disconnect();
            }
            String info = null;
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(mem.toByteArray()))) {
                for (java.util.zip.ZipEntry ze; (ze = z.getNextEntry()) != null; ) {
                    if (!ze.getName().equals("info.json")) continue;
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int r; (r = z.read(buf)) > 0; ) { b.write(buf, 0, r); if (b.size() > 65536) return false; }
                    info = b.toString("UTF-8");
                    break;
                }
            }
            if (info == null) return false;
            JSONObject root = new JSONObject(info), g = root;
            JSONArray games = root.optJSONArray("games");
            if (games != null && games.length() > 0) g = games.getJSONObject(0);
            String desc = g.optString("description", ""), ver = g.optString("version", root.optString("version", "")),
                    by = g.optString("author", root.optString("author", ""));
            if (!desc.isEmpty()) n.put("desc", desc);
            if (!ver.isEmpty()) n.put("version", ver);
            if (!by.isEmpty()) n.put("author", by);
            n.put("enriched", true);
            if (prefs.contains("newdone:" + repo) || !prefs.contains("new:" + repo)) return false;   /* dismissed while it downloaded */
            prefs.edit().putString("new:" + repo, n.toString()).apply();
            android.util.Log.i("CHGameScan", repo + ": details read (version " + ver + ")");
            return true;
        } catch (Exception ex) {
            android.util.Log.i("CHGameScan", repo + ": details not read: " + ex);
            return false;
        }
    }

    /** Set when GitHub answered 403/429 (its 60 calls an hour are used up): the scan is tried again at the next start. */
    static volatile boolean limited;
    private static void noteLimit(Exception e) {
        String m = String.valueOf(e);
        if (m.contains("HTTP 403") || m.contains("HTTP 429") || m.contains("HTTP response code: 403") || m.contains("HTTP response code: 429")) limited = true;
    }

    /** Blocking: searches all of GitHub for repos that mention CHGame and puts the ones with a release in as new games; true when any were added. */
    static boolean search(SharedPreferences prefs) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
            if (e.getKey().startsWith("new:") || e.getKey().startsWith("newdone:")) known.add(e.getKey().substring(e.getKey().indexOf(':') + 1));
        for (String src : new String[] { "cat:found", "cat:bundled" }) try {
            JSONArray games = new JSONObject(prefs.getString(src, "{\"games\":[]}")).getJSONArray("games");
            for (int i = 0; i < games.length(); i++) known.add(games.getJSONObject(i).optString("repo", ""));
        } catch (Exception ex) { /* none */ }
        boolean changed = false;
        try {
            HttpURLConnection c = CatalogueActivity.open("https://api.github.com/search/repositories?q="
                    + Uri.encode("chgame in:name,description,topics,readme fork:false") + "&sort=updated&per_page=100");
            JSONArray list;
            try (InputStream in = c.getInputStream()) {
                list = new JSONObject(CatalogueActivity.readAll(in, 4 << 20)).getJSONArray("items");
            } finally {
                c.disconnect();
            }
            SharedPreferences.Editor ed = prefs.edit();
            int tried = 0;
            for (int i = 0; i < list.length() && tried < 15; i++) {
                JSONObject r = list.getJSONObject(i);
                String full = r.optString("full_name");
                if (r.optBoolean("fork") || !full.matches("[\\w.-]+/[\\w.-]+") || known.contains(full)) continue;
                tried++;
                try {
                    JSONObject o = offer(r, releasePage(full));
                    android.util.Log.i("CHGameScan", "search " + full + (o == null ? ": no game in its latest release" : ": offered " + o.optString("asset")));
                    if (o == null) continue;
                    ed.putString("new:" + full, o.toString());
                    changed = true;
                } catch (Exception ex) {
                    noteLimit(ex);      /* no release, or the limit: next time */
                }
            }
            ed.apply();
        } catch (Exception ex) {
            noteLimit(ex);              /* no network or the limit: tomorrow */
        }
        return changed;
    }

    /** Blocking: looks at the other repos of each developer whose game is installed; true when the set of new games changed. */
    static boolean discover(Context c, SharedPreferences prefs, Uri tree) {
        ContentResolver cr = c.getContentResolver();
        Map<String, String[]> src = sources(c, prefs);
        java.util.Set<String> known = new java.util.HashSet<>(), owners = new java.util.LinkedHashSet<>(), byAuthor = new java.util.TreeSet<>();
        for (String[] s : src.values()) known.add(s[0]);
        java.util.Set<String> have = new java.util.HashSet<>(), haveRepos = new java.util.HashSet<>();
        for (Files.Entry e : Files.list(cr, tree)) {
            if (!Files.isGame(e.path)) continue;
            String up = e.path.substring(e.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
            have.add(up);
            String[] s = src.get(up);
            if (s != null) { owners.add(s[0].substring(0, s[0].indexOf('/'))); haveRepos.add(s[0]); continue; }
            /* a game from no catalogue: its header's author may be the GitHub user (juliendorra) */
            String[] id = Cart.ident(cr, e.uri);
            if (id != null && id[1].matches("[A-Za-z0-9-]{3,16}")) byAuthor.add(id[1]);
        }
        for (String a : byAuthor) {
            boolean dup = false;
            for (String o : owners) dup |= o.equalsIgnoreCase(a);
            if (!dup) owners.add(a);       /* after the catalogue's own developers */
        }
        boolean changed = false;
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : prefs.getAll().keySet())
            if (k.startsWith("new:") && haveRepos.contains(k.substring(4))) { ed.remove(k); changed = true; }
        int back = 0;                  /* a catalogue game that was installed here and is gone: offered again */
        for (Map.Entry<String, String[]> m : src.entrySet()) {
            String repo = m.getValue()[0];
            if (back >= 4 || have.contains(m.getKey()) || haveRepos.contains(repo) || !prefs.contains("got:" + m.getKey())
                    || prefs.contains("newdone:" + repo) || prefs.contains("new:" + repo)) continue;
            try {
                HttpURLConnection rc = CatalogueActivity.open("https://github.com/" + repo + "/releases/latest");
                String u = rc.getURL().toString();
                rc.disconnect();
                int at = u.lastIndexOf("/tag/");
                if (at < 0) continue;
                String nm = repo.substring(repo.indexOf('/') + 1).replaceFirst("^CH(?=[A-Z])", "");
                ed.putString("new:" + repo, new JSONObject().put("repo", repo).put("tag", Uri.decode(u.substring(at + 5)))
                        .put("asset", m.getValue()[1]).put("name", nm).put("owner", repo.substring(0, repo.indexOf('/')))
                        .put("desc", "Installed before; not on this device now.").toString());
                changed = true;
                back++;
            } catch (Exception ex) {
                /* next time */
            }
        }
        int owned = 0;
        android.util.Log.i("CHGameScan", "developers to look at: " + owners + " (catalogue " + src.size() + " files, installed " + have.size() + ")");
        for (String owner : owners) {
            if (++owned > 8 || limited) break;      /* GitHub's limit is hit: the rest waits for the next start */
            try {
                JSONArray list;
                HttpURLConnection h = CatalogueActivity.open("https://api.github.com/users/" + owner + "/repos?per_page=100&sort=pushed");
                try (InputStream in = h.getInputStream()) {
                    list = new JSONArray(CatalogueActivity.readAll(in, 4 << 20));
                } finally {
                    h.disconnect();
                }
                int tried = 0;
                for (int i = 0; i < list.length() && tried < 8; i++) {
                    JSONObject r = list.getJSONObject(i);
                    String full = r.optString("full_name");
                    String blob = (r.optString("name") + " " + r.optString("description") + " " + r.optJSONArray("topics")).toLowerCase(Locale.ROOT);
                    if (r.optBoolean("fork") || known.contains(full) || !full.matches("[\\w.-]+/[\\w.-]+")
                            || prefs.contains("newdone:" + full) || prefs.contains("new:" + full)
                            || !(blob.contains("chgame") || r.optString("name").startsWith("CH"))) continue;
                    tried++;
                    try {
                        JSONObject o = offer(r, releasePage(full));
                        android.util.Log.i("CHGameScan", full + (o == null ? ": no game in its latest release" : ": offered " + o.optString("asset")));
                        if (o == null) continue;
                        ed.putString("new:" + full, o.toString());
                        changed = true;
                    } catch (Exception ex) {
                        noteLimit(ex);  /* no release, or no network: next time */
                    }
                }
            } catch (Exception ex) {
                noteLimit(ex);          /* this developer is looked at again next time */
            }
        }
        ed.apply();
        return changed;
    }

    /** A game bundled in the app: its files are copied into the games folder (the ones already there are kept). */
    static String installLocal(Context c, SharedPreferences prefs, Uri tree, JSONObject n) {
        try {
            ContentResolver cr = c.getContentResolver();
            Uri root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                    android.provider.DocumentsContract.getTreeDocumentId(tree));
            java.util.Set<String> have = new java.util.HashSet<>();
            for (Files.Entry e : Files.list(cr, tree)) if (e.path.indexOf('/') < 0) have.add(e.path.toUpperCase(Locale.ROOT));
            JSONArray files = n.getJSONArray("extra");
            String mainFile = n.optString("asset");
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String name = f.optString("name"), asset = f.optString("asset");
                if (asset.isEmpty() || (have.contains(name.toUpperCase(Locale.ROOT)) && !name.equalsIgnoreCase(mainFile))) continue;
                Uri doc = existing(cr, tree, name);       /* the main file is rewritten in place, not copied as "(1)" */
                boolean fresh = doc == null;
                if (fresh) doc = android.provider.DocumentsContract.createDocument(cr, root, "application/octet-stream", name);
                if (doc == null) return "cannot write to the games folder";
                try (InputStream in = c.getAssets().open(asset); OutputStream out = cr.openOutputStream(doc, "wt")) {
                    if (out == null) throw new IOException("cannot write to the games folder");
                    byte[] buf = new byte[1 << 16];
                    for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                } catch (IOException | RuntimeException ex) {
                    if (fresh) try { android.provider.DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
                    throw ex;
                }
            }
            String repo = n.optString("repo");
            SharedPreferences.Editor ed = prefs.edit();
            if (!n.optString("tag").isEmpty()) ed.putString("got:" + mainFile.toUpperCase(Locale.ROOT), n.optString("tag"));
            ed.remove("new:" + repo).remove("gotUrl:" + mainFile.toUpperCase(Locale.ROOT)).putBoolean("newdone:" + repo, true).apply();
            dropPics(c, repo);
            return null;
        } catch (Exception ex) {
            return ex.getMessage() == null ? "install failed" : ex.getMessage();
        }
    }

    /** The document already in the games folder with this name (any case), or null. */
    private static Uri existing(ContentResolver cr, Uri tree, String name) {
        try {
            for (Files.Entry e : Files.list(cr, tree))
                if (e.path.indexOf('/') < 0 && e.path.equalsIgnoreCase(name)) return e.uri;
        } catch (Exception ignored) { }
        return null;
    }

    /** Blocking: downloads a new game into the games folder; null when done, else what went wrong. */
    static String install(Context c, SharedPreferences prefs, Uri tree, JSONObject n) {
        String repo = n.optString("repo"), tag = n.optString("tag"), asset = n.optString("asset");
        if (n.has("local")) return installLocal(c, prefs, tree, n);
        String err = installRemote(c, prefs, tree, n, repo, tag, asset);
        if (err == null) return null;
        JSONObject own = bundledCopy(c, repo);        /* the download failed: the copy inside the app, if it has one */
        return own == null ? err : installLocal(c, prefs, tree, own);
    }

    /** The bundled catalogue's entry for a repo, shaped like a not-installed game that installs from the app; null if none. */
    private static JSONObject bundledCopy(Context c, String repo) {
        try (InputStream in = c.getAssets().open("catalogue-private.json")) {
            JSONArray games = new JSONObject(CatalogueActivity.readAll(in, 1 << 20)).getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                JSONArray files = g.optJSONArray("files");
                if (!repo.equals(g.optString("repo")) || files == null) continue;
                for (int k = 0; k < files.length(); k++) {
                    String name = files.getJSONObject(k).optString("name", "");
                    if (Files.isGame(name) && !files.getJSONObject(k).optString("asset", "").isEmpty())
                        return new JSONObject().put("repo", repo).put("tag", g.optString("version", "")).put("asset", name).put("extra", files);
                }
            }
        } catch (Exception e) {
            /* none */
        }
        return null;
    }

    private static String installRemote(Context c, SharedPreferences prefs, Uri tree, JSONObject n, String repo, String tag, String asset) {
        try {
            HttpURLConnection h;
            String direct = n.optString("url", "");        /* the scan's own address: a .chgame package has another name than <repo>.CHG */
            String from = direct.startsWith("https://") ? direct
                    : "https://github.com/" + repo + "/releases/download/" + Uri.encode(tag) + "/" + Uri.encode(asset);
            try {
                h = CatalogueActivity.open(from);
            } catch (IOException first) {
                String found = gameUrl(repo, tag);
                if (found == null) throw first;
                h = CatalogueActivity.open(from = found);
            }
            ByteArrayOutputStream mem = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) {
                    mem.write(buf, 0, r);
                    if (mem.size() > 16 << 20) return "file too big";
                }
            } finally {
                h.disconnect();
            }
            byte[] data = mem.toByteArray();
            if (ChgPackage.isPackage(data)) {
                data = ChgPackage.toChg(data, ChgPackage.coverFor(c, null, newFile(n)));
                if (data == null) return "the package holds no game for this board";
            }
            if (Cart.ident(data, Math.min(data.length, Cart.HEADER)) == null) return "not a game file";
            ContentResolver cr = c.getContentResolver();
            Uri root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                    android.provider.DocumentsContract.getTreeDocumentId(tree));
            String file = newFile(n);
            Uri doc = existing(cr, tree, file);
            boolean fresh = doc == null;
            if (fresh) doc = android.provider.DocumentsContract.createDocument(cr, root, "application/octet-stream", file);
            if (doc == null) return "cannot write to the games folder";
            try (OutputStream out = cr.openOutputStream(doc, "wt")) {
                if (out == null) throw new IOException("cannot write to the games folder");
                out.write(data);
            } catch (IOException | RuntimeException ex) {
                if (fresh) try { android.provider.DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }     /* no half file left behind */
                throw ex;
            }
            prefs.edit().putString("got:" + file.toUpperCase(Locale.ROOT), tag).remove("new:" + repo)
                    .putString("gotUrl:" + file.toUpperCase(Locale.ROOT), from)      /* removed() offers it again from here */
                    .putBoolean("newdone:" + repo, true).apply();
            dropPics(c, repo);
            remember(prefs, repo, file, tag, n.optString("name", file), n.optString("desc", ""));
            return null;
        } catch (IOException | RuntimeException ex) {
            return ex.getMessage() == null ? "download failed" : ex.getMessage();
        }
    }

    /** Blocking: the download address of a repo's game file (.chg / .chgame) in release tag, else in its latest release; null if none. */
    static String gameUrl(String repo, String tag) {
        if (tag != null && !tag.isEmpty()) {
            String found = findAsset(repo, tag);
            if (found != null) return "https://github.com/" + repo + "/releases/download/" + Uri.encode(tag) + "/" + found;
        }
        try {
            JSONObject r = releasePage(repo);
            JSONArray a = r == null ? null : r.optJSONArray("assets");
            for (int i = 0; a != null && i < a.length(); i++) {
                String name = a.getJSONObject(i).optString("name").toLowerCase(Locale.ROOT);
                if (name.endsWith(".chg") || name.endsWith(".chgame")) return a.getJSONObject(i).optString("browser_download_url");
            }
        } catch (Exception e) {
            /* none */
        }
        return null;
    }

    /** Drops a repo's cached cart picture and frame (newpic-, newshot-): they are made again from its current file. */
    static void dropPics(Context c, String repo) {
        String key = repo.replace('/', '_');
        new java.io.File(c.getCacheDir(), "newpic-" + key + ".bin").delete();
        new java.io.File(c.getCacheDir(), "newshot-" + key + ".png").delete();
    }

    /** The release's one .chg / .chgame file (still percent-encoded), or null. */
    static String findAsset(String repo, String tag) {
        try {
            HttpURLConnection h = CatalogueActivity.open("https://github.com/" + repo + "/releases/expanded_assets/" + Uri.encode(tag));
            String html;
            try (InputStream in = h.getInputStream()) {
                html = CatalogueActivity.readAll(in, 1 << 20);
            } finally {
                h.disconnect();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "/releases/download/[^/\"]+/([^\"?]+\\.(?:chg|chgame))\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Blocking: fetch the game's new .CHG over the installed file; null when done, else what went wrong. */
    static String apply(Context c, SharedPreferences prefs, Files.Entry e, String file) {
        String[] p = pending(prefs, file);
        if (p == null || p.length < 3) return "no update";
        try {
            HttpURLConnection h;
            try {
                h = CatalogueActivity.open("https://github.com/" + p[1] + "/releases/download/"
                        + Uri.encode(p[0]) + "/" + Uri.encode(p[2]));
            } catch (IOException first) {
                /* the release names its game file differently (MysticBalloon-1.4.1.chgame): look at its asset list */
                String asset = findAsset(p[1], p[0]);
                if (asset == null) throw first;
                h = CatalogueActivity.open("https://github.com/" + p[1] + "/releases/download/"
                        + Uri.encode(p[0]) + "/" + asset);
            }
            ByteArrayOutputStream mem = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) {
                    mem.write(buf, 0, r);
                    if (mem.size() > 16 << 20) return "file too big";
                }
            } finally {
                h.disconnect();
            }
            byte[] head = mem.toByteArray();
            if (ChgPackage.isPackage(head)) {          /* the release ships a .chgame package: pack the .CHG from it */
                head = ChgPackage.toChg(head, ChgPackage.coverFor(c, e.uri, file));
                if (head == null) return "the package holds no game for this board";
                mem.reset();
                mem.write(head, 0, head.length);
            }
            if (Cart.ident(head, Math.min(head.length, Cart.HEADER)) == null) return "the release holds a package, not a .CHG file";
            try (OutputStream out = c.getContentResolver().openOutputStream(e.uri, "wt")) {      /* same file: its saves stay */
                if (out == null) return "cannot write to the games folder";
                mem.writeTo(out);
            }
            prefs.edit().putString("got:" + file.toUpperCase(Locale.ROOT), p[0]).remove(key(file)).apply();
            return null;
        } catch (IOException ex) {
            return ex.getMessage() == null ? "download failed" : ex.getMessage();
        }
    }

    /** The bundled catalogue's extra (non-game) files for a repo: name (upper case) -> asset path inside the app. */
    private static Map<String, String> bundledFiles(Context c, String repo) {
        Map<String, String> out = new HashMap<>();
        if (repo == null) return out;
        try (InputStream in = c.getAssets().open("catalogue-private.json")) {
            JSONArray games = new JSONObject(CatalogueActivity.readAll(in, 1 << 20)).getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                JSONArray files = g.optJSONArray("files");
                if (!repo.equals(g.optString("repo")) || files == null) continue;
                for (int k = 0; k < files.length(); k++) {
                    String name = files.getJSONObject(k).optString("name", "");
                    if (!name.isEmpty() && !Files.isGame(name))
                        out.put(name.toUpperCase(Locale.ROOT), files.getJSONObject(k).optString("asset", ""));
                }
            }
        } catch (Exception e) {
            /* none */
        }
        return out;
    }

    /** Blocking, quiet: the card files a game needs (its .DAT names, and the catalogue's extra files) into the
        games folder where they are missing or differ from the release's (or the app's own copy), once per game version. */
    static synchronized void syncData(Context c, SharedPreferences prefs, Uri tree, String file, java.util.Collection<String> dats) {
        try {
            if (tree == null) return;
            String u = file.toUpperCase(Locale.ROOT);
            String repo = repoFor(c, prefs, file);
            Map<String, String> bundled = bundledFiles(c, repo);
            java.util.Set<String> want = new java.util.LinkedHashSet<>();
            if (dats != null) for (String d : dats) want.add(d.toUpperCase(Locale.ROOT));
            want.addAll(bundled.keySet());
            if (want.isEmpty()) return;
            String tag = prefs.getString("got:" + u, null);     /* the installed version: only then may a file be replaced */
            if (tag != null && tag.isEmpty()) tag = null;
            ContentResolver cr = c.getContentResolver();
            java.util.Set<String> have = new java.util.HashSet<>();
            for (Files.Entry f : Files.list(cr, tree)) have.add(f.path.substring(f.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT));
            boolean missing = !have.containsAll(want);
            
            if (!missing && (tag == null ? "latest" : tag).equals(prefs.getString("datok:" + u, ""))) return;
            if (System.currentTimeMillis() - prefs.getLong("datfail:" + u, 0) < 3600_000L) return;
            boolean ok = true;
            for (String n : want) {
                try {
                    ok &= syncOne(c, cr, tree, repo, tag, n, bundled.get(n), tag != null && !n.contains("SAVE"));
                } catch (Exception ex) {
                    ok = false;
                }
            }
            SharedPreferences.Editor ed = prefs.edit();
            if (ok) {
                ed.remove("datfail:" + u);
                ed.putString("datok:" + u, tag == null ? "latest" : tag);
            } else {
                ed.putLong("datfail:" + u, System.currentTimeMillis());
            }
            ed.apply();
        } catch (Exception ignored) { }
    }

    /** One card file: true if it is now current (or there is nothing to get it from while one is there). */
    private static boolean syncOne(Context c, ContentResolver cr, Uri tree, String repo, String tag, String name, String asset, boolean replace) throws IOException {
        Uri doc = existing(cr, tree, name);
        if (doc != null && !replace) return true;       /* never touch a file we cannot prove is an old copy (saves, hand-installed games) */
        HttpURLConnection h = null;
        InputStream in = null;
        long len = -1;
        if (repo != null) {
            String[] urls = tag == null ? new String[] { "latest/download" }      /* tags are stored with or without the v */
                    : new String[] { "download/" + Uri.encode(tag), "download/v" + Uri.encode(tag) };
            for (String u : urls) {      /* the installed version's release, else the latest one */
                try {
                    h = CatalogueActivity.open("https://github.com/" + repo + "/releases/" + u + "/" + Uri.encode(name));
                    in = h.getInputStream();
                    len = h.getContentLengthLong();
                    break;
                } catch (IOException ex) {
                    if (h != null) h.disconnect();
                    h = null;
                    in = null;
                }
            }
        }
        if (in == null && doc == null && asset != null && !asset.isEmpty()) in = c.getAssets().open(asset);     /* the app's copy only fills a gap */
        if (in == null) return doc != null;
        java.io.File tmp = java.io.File.createTempFile("card", ".tmp", c.getCacheDir());
        try {
            byte[] head = new byte[4096];
            int n = 0;
            for (int r; n < head.length && (r = in.read(head, n, head.length - n)) > 0; ) n += r;
            if (doc != null) {
                byte[] mine = new byte[4096];
                int m = 0;
                long mineLen = -1;
                try (InputStream x = cr.openInputStream(doc); android.content.res.AssetFileDescriptor fd = cr.openAssetFileDescriptor(doc, "r")) {
                    if (fd != null) mineLen = fd.getLength();
                    if (x != null) for (int r; m < mine.length && (r = x.read(mine, m, mine.length - m)) > 0; ) m += r;
                }
                if (m == n && (len < 0 || len == mineLen) && java.util.Arrays.equals(mine, head)) return true;
            }
            long total = n;
            try (OutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(head, 0, n);
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) {
                    total += r;
                    if (total > (128L << 20)) throw new IOException("file too big");
                    out.write(buf, 0, r);
                }
            }
            if (total == 0) return doc != null;
            if (doc == null) {
                doc = android.provider.DocumentsContract.createDocument(cr, android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        tree, android.provider.DocumentsContract.getTreeDocumentId(tree)), "application/octet-stream", name);
                if (doc == null) return false;
            }
            try (InputStream src = new java.io.FileInputStream(tmp); OutputStream out = cr.openOutputStream(doc, "wt")) {
                if (out == null) return false;
                byte[] buf = new byte[1 << 16];
                for (int r; (r = src.read(buf)) > 0; ) out.write(buf, 0, r);
            }
            return true;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            if (h != null) h.disconnect();
            tmp.delete();
        }
    }
}
