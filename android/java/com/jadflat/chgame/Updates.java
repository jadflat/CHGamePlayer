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
                useNet(c);
                JSONObject rel = latestRelease(repo);       /* the github.com pages; the API only as a fallback */
                if (rel == null) continue;
                String title = "", desc = "";
                try {
                    if (open("api")) desc = new JSONObject(fetchJson("https://api.github.com/repos/" + repo)).optString("description", "");
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
        if (asset.toLowerCase(Locale.ROOT).endsWith(".chg") && Files.safeName(asset) != null) return asset;
        String base = n.optString("repo", "game");
        base = base.substring(base.indexOf('/') + 1).replaceAll("[^\\w.-]", "").replaceFirst("^[.]+", "");
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
        ed.remove("newAt2").remove("newAt14").remove("searchAt162").remove("newSeen").apply();
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
            if (Files.safeName(an) == null) continue;       /* a name with a path in it is never used */
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
            if (Files.safeName(asset) == null) asset = "game.CHG";
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
    static JSONObject releasePage(String full) throws IOException, org.json.JSONException {
        if (!open("web")) throw new Blocked("web back-off");
        String u;
        try {
            HttpURLConnection rc = CatalogueActivity.open("https://github.com/" + full + "/releases/latest");
            u = rc.getURL().toString();
            rc.disconnect();
        } catch (IOException e) {
            if (String.valueOf(e).contains("HTTP 429")) backOff("web", null);
            throw e;
        }
        int at = u.lastIndexOf("/tag/");
        if (at < 0) return null;
        String tag = Uri.decode(u.substring(at + 5));
        String html = fetch("https://github.com/" + full + "/releases/expanded_assets/" + Uri.encode(tag));
        JSONArray assets = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/releases/download/[^/\"]+/([^\"?]+)\"").matcher(html);
        while (m.find() && assets.length() < 30) {
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
    static boolean search(Context ctx, SharedPreferences prefs) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (String a : new String[] { "catalogue.json", "catalogue-private.json" }) {
            try (InputStream in = ctx.getAssets().open(a)) {
                JSONArray games = new JSONObject(CatalogueActivity.readAll(in, 1 << 20)).getJSONArray("games");
                for (int i = 0; i < games.length(); i++) {
                    JSONObject g = games.getJSONObject(i);
                    known.add(g.optString("repo", ""));
                    JSONArray f = g.optJSONArray("files");
                    for (int k = 0; f != null && k < f.length(); k++) known.add(repoOfUrl(f.getJSONObject(k).optString("url", "")));
                }
            } catch (Exception ex) { /* not there */ }
        }
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
            if (e.getKey().startsWith("new:") || e.getKey().startsWith("newdone:")) known.add(e.getKey().substring(e.getKey().indexOf(':') + 1));
        for (String src : new String[] { "cat:found", "cat:bundled" }) try {
            JSONArray games = new JSONObject(prefs.getString(src, "{\"games\":[]}")).getJSONArray("games");
            for (int i = 0; i < games.length(); i++) known.add(games.getJSONObject(i).optString("repo", ""));
        } catch (Exception ex) { /* none */ }
        known.remove("");
        /* the known developers and the ones added by hand: their repo pages are read even when every search is down */
        java.util.List<String> extra = new java.util.ArrayList<>(java.util.Arrays.asList(KNOWN_DEVS));
        for (String d : prefs.getString("cat:devs", "").split(",")) if (d.matches("[\\w-]+")) extra.add(d);
        Hunt h = hunt(ctx, known, extra, 30, 150_000L, null);
        if (h.sources.isEmpty() || h.limited || h.left > 0) limited = true;     /* the rest at the next start */
        boolean changed = false;
        SharedPreferences.Editor ed = prefs.edit();
        for (JSONObject[] g : h.games) try {
            JSONObject o = offer(g[0], g[1]);
            if (o == null) continue;
            String full = g[0].optString("full_name");
            if (prefs.contains("new:" + full) || prefs.contains("newdone:" + full)) continue;
            ed.putString("new:" + full, o.toString());
            changed = true;
        } catch (Exception ex) { /* skipped */ }
        ed.apply();
        android.util.Log.i("CHGameScan", "search: " + h.summary() + ", " + h.games.size() + " with a game");
        return changed;
    }

    /** "owner/repo" of a github.com release download address (the public list names Jadflat's games only so), else "". */
    static String repoOfUrl(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https://github\\.com/([\\w.-]+/[\\w.-]+)/releases/").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /* ---------------------------------------------------------------- */
    /* GitHub access: github.com pages first, the API as a fallback       */
    /* ---------------------------------------------------------------- */

    /** The app's own cap on API calls an hour: GitHub allows 60 for each network address, shared by every device on it. */
    static final int API_PER_HOUR = 25;
    /** Developers known to make CHGame games: their repo pages are read by every search (one page each, no API). */
    static final String[] KNOWN_DEVS = { "bateske", "juliendorra", "poevoid", "SellswordSoftware", "joyrider3774" };

    private static volatile SharedPreferences net;
    private static volatile java.io.File netCache;

    /** Sets the preferences (API budget, back-off) and the cache folder (ETags) that fetch() uses. */
    static void useNet(Context c) {
        net = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        java.io.File d = new java.io.File(c.getCacheDir(), "gh");
        if (d.isDirectory() || d.mkdirs()) netCache = d;
    }

    /** Thrown when a source is not asked at all: its back-off runs, or the app's own API budget is spent. */
    static final class Blocked extends IOException {
        Blocked(String m) { super(m); }
    }

    private static String source(String url) {
        /* github.com's search pages have a limit of their own (about 8 a minute logged out): kept apart from the other pages */
        return url.startsWith("https://api.github.com/search/") ? "search" : url.startsWith("https://api.github.com/") ? "api"
                : url.startsWith("https://github.com/search?") ? "websearch" : "web";
    }

    /** True when the source can be asked now (no back-off, and for the API some of this hour's budget left). */
    static boolean open(String src) {
        SharedPreferences p = net;
        if (p == null) return true;
        if (System.currentTimeMillis() < p.getLong("net:" + src + ":until", 0)) return false;
        if (!src.equals("api")) return true;
        long hour = System.currentTimeMillis() / 3600_000L;
        return p.getLong("net:api:hour", 0) != hour || p.getInt("net:api:used", 0) < API_PER_HOUR;
    }

    /** Takes one API call from this hour's budget before the call is made (several threads ask at once); false when spent. */
    private static synchronized boolean reserveApi() {
        SharedPreferences p = net;
        if (p == null) return true;
        long hour = System.currentTimeMillis() / 3600_000L;
        int used = p.getLong("net:api:hour", 0) == hour ? p.getInt("net:api:used", 0) : 0;
        if (used >= API_PER_HOUR) return false;
        p.edit().putLong("net:api:hour", hour).putInt("net:api:used", used + 1).commit();
        return true;
    }

    /** API calls used this hour (the app's own count). */
    static int apiUsed() {
        SharedPreferences p = net;
        if (p == null || p.getLong("net:api:hour", 0) != System.currentTimeMillis() / 3600_000L) return 0;
        return p.getInt("net:api:used", 0);
    }

    private static synchronized void backOff(String src, HttpURLConnection c) {
        SharedPreferences p = net;
        if (p == null) return;
        long now = System.currentTimeMillis(), until = 0;
        if (c != null) try {
            String ra = c.getHeaderField("Retry-After"), reset = c.getHeaderField("X-RateLimit-Reset");
            if (ra != null && ra.matches("\\d{1,5}")) until = now + Long.parseLong(ra) * 1000;
            else if (reset != null && reset.matches("\\d{9,11}")) until = Long.parseLong(reset) * 1000 + 2000;
        } catch (Exception e) { /* none */ }
        int strikes = p.getInt("net:" + src + ":strikes", 0) + 1;      /* without a hint: 1, 2, 4 ... 30 minutes */
        if (until <= now) until = now + Math.min(30, 1L << Math.min(strikes - 1, 5)) * 60_000L;
        if (until > now + 3600_000L) until = now + 3600_000L;
        p.edit().putLong("net:" + src + ":until", until).putInt("net:" + src + ":strikes", strikes).apply();
    }

    private static void cleared(String src) {
        SharedPreferences p = net;
        if (p != null && p.getInt("net:" + src + ":strikes", 0) != 0) p.edit().remove("net:" + src + ":strikes").apply();
    }

    /**
     * Blocking GET of a github.com page or an API address, following redirects, with the back-off and the API budget.
     * API answers are kept with their ETag: an unchanged one comes back as 304 (which GitHub does not count) and is
     * read from the cache. Throws Blocked when not asked, IOException("HTTP n") on an error answer.
     */
    static String fetch(String url) throws IOException {
        String src = source(url);
        if (!open(src)) throw new Blocked(src.equals("api") ? "API budget or back-off" : src + " back-off");
        java.io.File cf = null;
        String etag = null, cached = null;
        if (src.startsWith("api") || src.equals("search")) if (netCache != null) {
            cf = new java.io.File(netCache, Integer.toHexString(url.hashCode()) + ".txt");
            try {
                if (cf.isFile()) {
                    String all = new String(java.nio.file.Files.readAllBytes(cf.toPath()), "UTF-8");
                    int nl = all.indexOf('\n');
                    if (nl > 0) { etag = all.substring(0, nl); cached = all.substring(nl + 1); }
                }
            } catch (Exception e) { /* no cache */ }
        }
        if (src.equals("api") && !reserveApi()) throw new Blocked("the app's API budget for this hour is spent");
        String u = url;
        for (int hops = 0; hops < 8; hops++) {
            if (!u.startsWith("https://")) throw new IOException("not https");
            HttpURLConnection c = (HttpURLConnection) new java.net.URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "CHGamePlayer/" + MainActivity.VERSION);
            if (src.equals("api") || src.equals("search")) c.setRequestProperty("Accept", "application/vnd.github+json");
            if (etag != null && hops == 0) c.setRequestProperty("If-None-Match", etag);
            try {
                int code = c.getResponseCode();
                if (code >= 301 && code <= 308 && code != 304 && code != 305 && code != 306) {
                    String next = c.getHeaderField("Location");
                    if (next == null) throw new IOException("bad redirect");
                    u = new java.net.URL(new java.net.URL(u), next).toString();
                    continue;
                }
                if (code == 304 && cached != null) { cleared(src); return cached; }
                if (code == 403 || code == 429) { backOff(src, c); throw new IOException("HTTP " + code); }
                if (code != 200) throw new IOException("HTTP " + code);
                String body;
                try (InputStream in = c.getInputStream()) {
                    body = CatalogueActivity.readAll(in, 6 << 20);
                }
                cleared(src);
                String tag = c.getHeaderField("ETag");
                if (cf != null && tag != null && !tag.contains("\n")) {
                    try (OutputStream o = new java.io.FileOutputStream(cf)) { o.write((tag + "\n" + body).getBytes("UTF-8")); }
                    catch (Exception e) { /* not cached */ }
                }
                return body;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static boolean limitError(Exception e) {
        String m = String.valueOf(e);
        return e instanceof Blocked || m.contains("HTTP 403") || m.contains("HTTP 429");
    }

    /** A github.com page that did not have what it should: GitHub changed its layout. */
    static final class Layout extends IOException {
        Layout(String m) { super(m); }
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    /** github.com/search (repositories), one page of 10, as API-shaped repo objects. */
    static java.util.List<JSONObject> webSearch(String q, int page, boolean newest) throws IOException {
        String html = fetch("https://github.com/search?q=" + Uri.encode(q) + "&type=repositories" + (newest ? "&s=updated&o=desc" : "") + "&p=" + page);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("data-target=\"react-app\\.embeddedData\">(.*?)</script>",
                java.util.regex.Pattern.DOTALL).matcher(html);
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        try {
            if (!m.find()) throw new Layout("search page: no results data");
            JSONObject p = new JSONObject(m.group(1)).getJSONObject("payload").getJSONObject("blackbirdSearchRoute");
            JSONArray res = p.getJSONArray("results");
            for (int i = 0; i < res.length(); i++) {
                JSONObject r = res.getJSONObject(i);
                JSONObject repo = r.getJSONObject("repo").getJSONObject("repository");
                String owner = repo.getString("owner_login"), name = repo.getString("name");
                out.add(new JSONObject().put("full_name", owner + "/" + name).put("name", name)
                        .put("owner", new JSONObject().put("login", owner))
                        .put("description", strip(r.optString("hl_trunc_description", "")))
                        .put("language", r.optString("language", "")).put("archived", r.optBoolean("archived"))
                        .put("topics", r.optJSONArray("topics") == null ? new JSONArray() : r.getJSONArray("topics"))
                        .put("pushed_at", repo.optString("updated_at", "")).put("fork", false));
            }
        } catch (org.json.JSONException e) {
            throw new Layout("search page: " + e.getMessage());
        }
        return out;
    }

    /** github.com/topics/<topic>: the repos tagged with it (names only, newest pages first). */
    static java.util.List<JSONObject> topicPage(String topic) throws IOException {
        String html = fetch("https://github.com/topics/" + topic + "?o=desc&s=updated");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "href=\"/([\\w.-]+)/([\\w.-]+)\"[^>]*class=\"Link text-bold wb-break-word\"").matcher(html);
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        try {
            while (m.find()) out.add(new JSONObject().put("full_name", m.group(1) + "/" + m.group(2)).put("name", m.group(2))
                    .put("owner", new JSONObject().put("login", m.group(1))).put("description", "")
                    .put("topics", new JSONArray().put(topic)).put("fork", false));
        } catch (org.json.JSONException e) { /* none */ }
        if (out.isEmpty() && !html.contains("topic")) throw new Layout("topic page: no repos");
        return out;
    }

    /**
     * A developer's repos, newest first, API-shaped (full_name, name, description, fork, language, pushed_at): read
     * from github.com/<owner>?tab=repositories (30, no API), else from the API (counts against the budget).
     */
    static JSONArray ownerRepos(String owner, java.util.List<String> errors) throws IOException {
        try {
            String html = fetch("https://github.com/" + owner + "?tab=repositories");
            String[] parts = html.split("itemprop=\"name codeRepository\"");
            JSONArray out = new JSONArray();
            java.util.regex.Pattern name = java.util.regex.Pattern.compile("href=\"/([\\w.-]+)/([\\w.-]+)\"\\s*$"),
                    desc = java.util.regex.Pattern.compile("itemprop=\"description\">(.*?)</p>", java.util.regex.Pattern.DOTALL),
                    lang = java.util.regex.Pattern.compile("itemprop=\"programmingLanguage\">([^<]*)<"),
                    time = java.util.regex.Pattern.compile("<relative-time datetime=\"([^\"]+)\"");
            for (int i = 1; i < parts.length; i++) {
                java.util.regex.Matcher n = name.matcher(parts[i - 1].substring(Math.max(0, parts[i - 1].length() - 300)));
                if (!n.find()) continue;
                String block = parts[i];
                java.util.regex.Matcher d = desc.matcher(block), l = lang.matcher(block), t = time.matcher(block);
                out.put(new JSONObject().put("full_name", n.group(1) + "/" + n.group(2)).put("name", n.group(2))
                        .put("owner", new JSONObject().put("login", n.group(1)))
                        .put("description", d.find() ? strip(d.group(1)) : "")
                        .put("fork", block.substring(0, Math.min(block.length(), 1500)).contains("Forked from"))
                        .put("language", l.find() ? l.group(1).trim() : "")
                        .put("pushed_at", t.find() ? t.group(1) : "").put("topics", new JSONArray()));
            }
            if (out.length() == 0 && !html.contains("doesn’t have any public repositories") && !html.contains("have any public repositories"))
                throw new Layout(owner + "'s repo page: no repos read");
            return out;
        } catch (IOException e) {
            if (e instanceof Layout) errors.add("github.com changed its page layout (" + e.getMessage() + "): used the API");
            else if (String.valueOf(e).contains("HTTP 404")) throw e;
            /* the page is blocked or changed: the API, if there is budget */
            try {
                return new JSONArray(fetchJson("https://api.github.com/users/" + owner + "/repos?per_page=100&sort=pushed"));
            } catch (org.json.JSONException e2) {
                throw new IOException("bad answer from the API");
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad answer: " + e.getMessage());
        }
    }

    private static String fetchJson(String url) throws IOException {
        String s = fetch(url);
        if (!s.trim().startsWith("{") && !s.trim().startsWith("[")) throw new IOException("not JSON");
        return s;
    }

    /** A repo's latest release, from the github.com pages, else from the API; null when it has none. */
    static JSONObject latestRelease(String full) throws IOException {
        try {
            return releasePage(full);
        } catch (IOException e) {
            if (String.valueOf(e).contains("HTTP 404")) throw e;
            try {
                return new JSONObject(fetchJson("https://api.github.com/repos/" + full + "/releases/latest"));
            } catch (IOException e2) {
                if (String.valueOf(e2).contains("HTTP 404")) return null;    /* the API: 404 = no release */
                throw e;
            } catch (org.json.JSONException e2) {
                throw new IOException("bad answer");
            }
        } catch (org.json.JSONException e) {
            throw new Layout("release page: " + e.getMessage());
        }
    }

    /** What a GitHub-wide hunt for CHGame games found (see hunt()). */
    static final class Hunt {
        final java.util.List<JSONObject[]> games = new java.util.ArrayList<>();   /* {repo (API shape), latest release (releasePage shape)} */
        final java.util.List<String> noGame = new java.util.ArrayList<>();        /* looked at: no game in the latest release (or none) */
        final Set<String> seen = new java.util.HashSet<>();                       /* every repo the searches and owner lists named */
        final java.util.List<String> errors = new java.util.ArrayList<>();
        final java.util.List<String> sources = new java.util.ArrayList<>();       /* "API search 2", "web search 3" ... */
        int queries, listed, looked, skipped, released, junk, owners, left, ownersLeft;
        boolean limited, searchLimited, webLimited, fromCache;
        String cacheDate = "";

        String summary() {
            return (fromCache ? "Search was down: used the repo list saved " + cacheDate + ". " : "")
                    + "Sources: " + (sources.isEmpty() ? "none answered" : String.join(", ", sources)) + ". "
                    + listed + " repos named" + (owners > 0 ? ", " + owners + " developers' repo lists read" : "")
                    + ", looked at " + looked + (released > 0 ? " (" + released + " with a new release since checked)" : "")
                    + (skipped > 0 ? ", skipped " + skipped + " unchanged since checked" : "")
                    + (junk > 0 ? ", ignored " + junk + " unrelated" : "")
                    + (left > 0 || ownersLeft > 0 ? ", " + (left + ownersLeft) + " left for next time" : "")
                    + ". API calls this hour: " + apiUsed() + " of the app's " + API_PER_HOUR;
        }
    }

    /* The API's searches (their own limit, 10 a minute); the github.com search pages below add to them */
    private static final String[][] QUERIES = {
        { "chgame in:name,description,topics,readme fork:false", "&sort=updated", "2" },
        { "topic:chgame", "", "1" },
        { "ch32x035 in:name,description,topics,readme fork:false", "", "1" },
    };

    private static void addRepo(Map<String, JSONObject> repos, JSONObject r) {
        String full = r.optString("full_name");
        if (!full.matches("[\\w.-]+/[\\w.-]+") || r.optBoolean("fork")) return;
        JSONObject had = repos.get(full);
        if (had == null) { repos.put(full, r); return; }
        try {      /* a topic page names only the repo: keep the fuller answer, with the topic */
            if (had.optString("description").isEmpty() && !r.optString("description").isEmpty()) { repos.put(full, r); r.put("topics", had.optJSONArray("topics")); }
            else if (r.optJSONArray("topics") != null && r.optJSONArray("topics").length() > 0 && had.optJSONArray("topics") != null && had.optJSONArray("topics").length() == 0) had.put("topics", r.optJSONArray("topics"));
        } catch (org.json.JSONException e) { /* kept */ }
    }

    /**
     * Blocking: searches GitHub for CHGame games. The API's searches (paged) and github.com's search and topic pages,
     * and the repo pages of the developers left from last time and of extraOwners, merged; unrelated repos dropped; the
     * likeliest looked at first; each latest release read from its github.com page (the API only as a fallback), four at
     * a time. Then the repo pages of developers whose game was just found (siblings). known: repos already listed (named in seen, not looked at). A repo
     * with no game is remembered (cat:checked) and skipped until it is pushed to again; repos and developers not
     * reached (limits, the time cap) are kept and come first next time. If every search fails, the last saved list is used.
     */
    static Hunt hunt(Context ctx, Set<String> known, java.util.Collection<String> extraOwners, int maxLook, long maxMillis,
                     java.util.function.Consumer<String> say) {
        useNet(ctx);
        SharedPreferences prefs = net;
        long deadline = System.currentTimeMillis() + maxMillis;
        Hunt h = new Hunt();
        Set<String> gameOwners = new java.util.HashSet<>();
        for (String k : known) if (k.indexOf('/') > 0) gameOwners.add(k.substring(0, k.indexOf('/')).toLowerCase(Locale.ROOT));
        Map<String, JSONObject> repos = new java.util.LinkedHashMap<>();
        int api = 0, web = 0;
        for (String[] q : QUERIES) {
            int pages = Integer.parseInt(q[2]);
            for (int page = 1; page <= pages; page++) {
                if (!open("search")) { h.searchLimited = true; break; }
                if (say != null) say.accept("Searching GitHub: " + q[0].replace(" in:name,description,topics,readme fork:false", ""));
                try {
                    JSONObject res = new JSONObject(fetchJson("https://api.github.com/search/repositories?q=" + Uri.encode(q[0])
                            + q[1] + "&per_page=100&page=" + page));
                    h.queries++;
                    api++;
                    JSONArray items = res.optJSONArray("items");
                    for (int i = 0; items != null && i < items.length(); i++) addRepo(repos, items.getJSONObject(i));
                    if (items == null || page * 100 >= res.optInt("total_count", 0)) break;
                } catch (Exception ex) {
                    if (limitError(ex)) h.searchLimited = true;
                    else h.errors.add("API search \"" + q[0] + "\": " + ex.getMessage());
                    break;
                }
            }
        }
        /* github.com's own search pages (10 a page, a limit of their own): a few always, more when the API's search failed */
        String[][] webQ = api > 0 ? new String[][] { { "chgame", "1", "3" }, { "chgame", "0", "2" } }
                : new String[][] { { "chgame", "1", "5" }, { "chgame", "0", "3" }, { "topic:chgame", "1", "1" }, { "ch32x035 game", "0", "1" } };
        for (String[] q : webQ) {
            for (int page = 1; page <= Integer.parseInt(q[2]) && !h.webLimited; page++) {
                if (!open("websearch")) { h.webLimited = true; break; }
                if (say != null) say.accept("Searching github.com: " + q[0] + " (page " + page + ")");
                try {
                    java.util.List<JSONObject> got = webSearch(q[0], page, q[1].equals("1"));
                    web++;
                    for (JSONObject r : got) addRepo(repos, r);
                    if (got.size() < 10) break;
                } catch (Exception ex) {
                    if (limitError(ex)) h.webLimited = true;
                    else h.errors.add((ex instanceof Layout ? "github.com changed its page layout: " : "github.com search: ") + ex.getMessage());
                    break;
                }
            }
        }
        try {
            for (JSONObject r : topicPage("chgame")) addRepo(repos, r);
            h.sources.add("topic page");
        } catch (Exception ex) {
            if (limitError(ex)) h.limited = true;
            else h.errors.add((ex instanceof Layout ? "github.com changed its page layout: " : "topic page: ") + ex.getMessage());
        }
        if (api > 0) h.sources.add(0, "API search (" + api + ")");
        if (web > 0) h.sources.add(api > 0 ? 1 : 0, "github.com search (" + web + " pages)");
        h.queries = api + web;
        java.io.File saved = netCache == null ? null : new java.io.File(netCache, "hunt-repos.json");
        if (repos.isEmpty() && saved != null && saved.isFile()) {
            try {      /* every search failed: the list from the last good run, so the developers' pages and releases still get looked at */
                JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(saved.toPath()), "UTF-8"));
                for (int i = 0; i < a.length(); i++) addRepo(repos, a.getJSONObject(i));
                h.fromCache = true;
                h.cacheDate = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(saved.lastModified()));
            } catch (Exception ex) { /* none */ }
        } else if (repos.size() >= 20 && saved != null) {
            try (OutputStream o = new java.io.FileOutputStream(saved)) {
                JSONArray a = new JSONArray();
                for (JSONObject r : repos.values()) a.put(r);
                o.write(a.toString().getBytes("UTF-8"));
            } catch (Exception ex) { /* not saved */ }
        }
        h.listed = repos.size();
        h.seen.addAll(repos.keySet());
        Map<String, String> checked = checkedMap(prefs);
        long today = System.currentTimeMillis() / 86400000L;
        /* the known developers' repo pages (free github.com pages, where new games most often appear), and any left last time:
           read before the looking starts, so their repos are ranked with the search's */
        Set<String> owners = new java.util.LinkedHashSet<>();
        for (String o : prefs.getString("cat:ownersTodo", "").split(",")) if (!o.isEmpty()) owners.add(o);
        for (String o : extraOwners) owners.add(o.toLowerCase(Locale.ROOT));
        Set<String> listedOwners = new java.util.HashSet<>();
        java.util.List<String> notReached = new java.util.ArrayList<>();
        Map<String, JSONObject> fromOwners = listOwners(h, owners, known, deadline, say, listedOwners, notReached);
        java.util.List<JSONObject> todo = new java.util.ArrayList<>();
        Set<String> looked = new java.util.HashSet<>();
        Map<String, JSONObject> all = new java.util.LinkedHashMap<>(repos);
        for (JSONObject r : fromOwners.values()) all.putIfAbsent(r.optString("full_name"), r);
        java.util.List<JSONObject> skippedNow = new java.util.ArrayList<>();
        for (JSONObject r : all.values()) {
            String full = r.optString("full_name");
            if (known.contains(full)) continue;
            if (junk(r)) { h.junk++; continue; }
            if (!looked.add(full)) continue;
            if (recent(checked, r, today)) { skippedNow.add(r); continue; }
            todo.add(r);
        }
        /* a release made in GitHub's web form may not change pushed_at: the skipped ones' latest release tags are compared */
        todo.addAll(newRelease(h, skippedNow, checked, deadline, say));
        final Set<String> owners0 = gameOwners;
        java.util.Collections.sort(todo, (a, b) -> score(b, owners0) - score(a, owners0));     /* stable: newest first among equals */
        if (todo.size() > maxLook) { h.left += todo.size() - maxLook; todo = new java.util.ArrayList<>(todo.subList(0, maxLook)); }
        look(h, todo, checked, today, deadline, say);
        saveChecked(prefs, checked, today);
        /* siblings: the repo pages of developers whose game was just found (not listed yet) */
        Set<String> newOwners = new java.util.LinkedHashSet<>();
        for (JSONObject[] g : h.games) if (!listedOwners.contains(ownerOf(g[0]))) newOwners.add(ownerOf(g[0]));
        java.util.List<JSONObject> more = new java.util.ArrayList<>(), skippedSib = new java.util.ArrayList<>();
        for (JSONObject r : listOwners(h, newOwners, known, deadline, say, listedOwners, notReached).values()) {
            String full = r.optString("full_name");
            if (junk(r) || !looked.add(full)) continue;
            if (recent(checked, r, today)) { skippedSib.add(r); continue; }
            more.add(r);
        }
        more.addAll(newRelease(h, skippedSib, checked, deadline, say));
        h.ownersLeft = 0;
        for (String o : notReached) if (!extraOwners.contains(o)) h.ownersLeft++;
        prefs.edit().putString("cat:ownersTodo", String.join(",", notReached)).apply();
        if (more.size() > 30) { h.left += more.size() - 30; more = new java.util.ArrayList<>(more.subList(0, 30)); }
        look(h, more, checked, today, deadline, say);
        saveChecked(prefs, checked, today);
        return h;
    }

    /**
     * Reads the repo pages of these developers (github.com first, the API as a fallback), at most 14 a run; returns their
     * repos that look like CHGame work (a CH name, or CHGame in the name or description), forks and known ones left out.
     * Developers not reached (limits, the time cap) go to notReached; the ones read to listed.
     */
    private static Map<String, JSONObject> listOwners(Hunt h, Set<String> owners, Set<String> known, long deadline,
                                                      java.util.function.Consumer<String> say, Set<String> listed, java.util.List<String> notReached) {
        Map<String, JSONObject> out = new java.util.LinkedHashMap<>();
        for (String o : owners) {
            if (!o.matches("[\\w.-]+") || listed.contains(o)) continue;
            if (h.owners >= 14 || System.currentTimeMillis() > deadline || !open("web") && !open("api")) { if (!notReached.contains(o)) notReached.add(o); continue; }
            if (say != null) say.accept("Looking at " + o + "'s repos");
            try {
                JSONArray list = ownerRepos(o, h.errors);
                h.owners++;
                listed.add(o);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject r = list.getJSONObject(i);
                    String full = r.optString("full_name");
                    if (r.optBoolean("fork") || !full.matches("[\\w.-]+/[\\w.-]+")) continue;
                    h.seen.add(full);
                    if (known.contains(full)) continue;
                    if (blob(r).contains("chgame") || r.optString("name").matches("CH[A-Z0-9].*")) out.putIfAbsent(full, r);
                }
            } catch (Exception ex) {
                if (limitError(ex)) { h.limited = true; if (!notReached.contains(o)) notReached.add(o); }
                else if (!String.valueOf(ex).contains("HTTP 404")) { h.errors.add(o + "'s repos: " + ex.getMessage()); if (!notReached.contains(o)) notReached.add(o); }
            }
        }
        return out;
    }

    /** Reads each repo's latest release, four at a time, until the deadline; fills h.games / h.noGame and checked. */
    private static void look(Hunt h, java.util.List<JSONObject> list, Map<String, String> checked, long today, long deadline,
                             java.util.function.Consumer<String> say) {
        if (list.isEmpty()) return;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
        final int total = list.size();
        for (JSONObject r : list) jobs.add(pool.submit(() -> {
            String full = r.optString("full_name");
            if (System.currentTimeMillis() > deadline || !open("web") && !open("api")) {
                synchronized (h) { h.left++; }      /* not looked at, not marked: first in line next time */
                return;
            }
            if (say != null) say.accept("Looking at " + full + " (" + n.incrementAndGet() + "/" + total + ")");
            try {
                JSONObject rel = latestRelease(full);
                synchronized (h) {
                    h.looked++;
                    if (hasGame(rel)) h.games.add(new JSONObject[] { r, rel });
                    else {
                        h.noGame.add(full + (rel == null ? " (no release)" : " (" + rel.optString("tag_name") + ": no .CHG)"));
                        mark(checked, full, today, r.optString("pushed_at"), rel == null ? "-" : rel.optString("tag_name", "-"),
                                rel == null || rel.optJSONArray("assets") == null ? 0 : Math.min(30, rel.optJSONArray("assets").length()));
                    }
                }
            } catch (Exception ex) {
                synchronized (h) {
                    String m = String.valueOf(ex);
                    if (m.contains("HTTP 404")) { h.looked++; h.noGame.add(full + " (gone)"); mark(checked, full, today, r.optString("pushed_at"), "-", 0); }
                    else if (limitError(ex)) { h.limited = true; h.left++; }
                    else { h.left++; h.errors.add(full + ": " + (ex instanceof Layout ? "github.com changed its page layout: " : "") + ex.getMessage()); }
                }
            }
        }));
        for (java.util.concurrent.Future<?> f : jobs) try { f.get(); } catch (Exception ex) { /* counted inside */ }
        pool.shutdown();
    }

    private static String blob(JSONObject r) {
        return (r.optString("name") + " " + r.optString("description") + " " + r.optJSONArray("topics")).toLowerCase(Locale.ROOT);
    }

    /** How likely a repo is to hold a CHGame game, from what the search already told (no extra calls). */
    private static int score(JSONObject r, Set<String> gameOwners) {
        String n = r.optString("name"), b = blob(r), own = ownerOf(r);
        int s = 0;
        if (gameOwners.contains(own)) s += 4;
        if (n.matches("CH[A-Z0-9].*") || n.toLowerCase(Locale.ROOT).contains("chgame")) s += 3;
        String d = r.optString("description", "").replaceFirst("^null$", "").toLowerCase(Locale.ROOT), lang = r.optString("language", "");
        if (d.contains("chgame")) s += 3;
        if (d.contains("chgame") && (d.contains("handheld") || d.contains("ch32x035") || d.contains("game"))) s += 2;
        if (b.contains("\"chgame\"")) s += 2;          /* the topic */
        if (b.contains("ch32x035")) s += 1;
        if (lang.equals("C") || lang.equals("C++")) s += 1;
        if (d.isEmpty() && !gameOwners.contains(own)) s -= 2;
        if (lang.matches("HTML|CSS|JavaScript|TypeScript|PHP|Solidity|Vue") && !d.contains("chgame")) s -= 2;
        return s;
    }

    /** Repos that are plainly not a game: web sites, ones not pushed to since before the CHGame existed (autumn 2026). */
    private static boolean junk(JSONObject r) {
        String n = r.optString("name").toLowerCase(Locale.ROOT), d = r.optString("description").toLowerCase(Locale.ROOT);
        String pushed = r.optString("pushed_at", "");
        return n.endsWith(".github.io") || d.contains("official website") || (!pushed.isEmpty() && pushed.compareTo("2026-08") < 0)
                || r.optBoolean("archived") && !blob(r).contains("chgame");
    }

    private static String ownerOf(JSONObject r) {
        String f = r.optString("full_name");
        int s = f.indexOf('/');
        return s > 0 ? f.substring(0, s).toLowerCase(Locale.ROOT) : "";
    }

    /** A release with a .CHG, or a .chgame package small enough to be one game (a bundle of many is far bigger). */
    static boolean hasGame(JSONObject rel) {
        JSONArray a = rel == null ? null : rel.optJSONArray("assets");
        for (int k = 0; a != null && k < a.length(); k++) {
            JSONObject x = a.optJSONObject(k);
            if (x == null) continue;
            String n = x.optString("name", "").toLowerCase(Locale.ROOT);
            if (n.endsWith(".chg") || n.endsWith(".chgame") && x.optLong("size", 0) > 0 && x.optLong("size", 0) <= 300000) return true;
        }
        return false;
    }

    private static long dayOf(String v) {
        try { return Long.parseLong(v.substring(0, v.indexOf('|'))); } catch (Exception e) { return 0; }
    }

    /** Repos looked at with no game: "repo" -> "day|pushed_at" (cat:checked). */
    private static Map<String, String> checkedMap(SharedPreferences prefs) {
        Map<String, String> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("cat:checked", "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.optString(k)); }
        } catch (Exception ex) { /* none */ }
        return m;
    }

    /** Looked at before with no game and not pushed to since (a new release pushes its tag): no time limit. */
    private static boolean recent(Map<String, String> checked, JSONObject r, long today) {
        String v = checked.get(r.optString("full_name")), pushed = r.optString("pushed_at", "");
        if (v == null || v.indexOf('|') < 0 || pushed.isEmpty()) return false;     /* no date to compare: looked at again */
        return java.util.Arrays.asList(v.substring(v.indexOf('|') + 1).split("[|]")).contains(pushed);
    }

    /** The latest release tag stored with a checked repo ("-" for none), or null for an entry made before tags were kept. */
    static String storedTag(String v) {
        if (v == null) return null;
        for (String x : v.split("[|]")) if (x.startsWith("T=")) return x.substring(2);
        return null;
    }

    /**
     * Notes a repo with no game: the day, its latest release tag ("T=<tag>", "-" for none), how many files that release
     * had ("N=<n>") and the last 3 pushed dates seen for it (the API, the search page and the repo page give different ones).
     */
    static void mark(Map<String, String> checked, String full, long today, String pushed, String tag, int files) {
        String v = checked.get(full);
        java.util.List<String> stamps = new java.util.ArrayList<>();
        if (v != null && v.indexOf('|') >= 0)
            for (String x : v.substring(v.indexOf('|') + 1).split("[|]"))
                if (!x.isEmpty() && !x.startsWith("T=") && !x.startsWith("N=") && !x.equals(pushed)) stamps.add(x);
        stamps.add(pushed);
        while (stamps.size() > 3) stamps.remove(0);
        checked.put(full, today + "|T=" + cleanTag(tag) + "|N=" + files + "|" + String.join("|", stamps));
    }

    /** A tag as kept in cat:checked: "-" for none, no "|" (the field separator). */
    static String cleanTag(String tag) {
        return tag == null || tag.isEmpty() ? "-" : tag.replace("|", "");
    }

    /** The number of release files stored with a checked repo, or -1 when unknown. */
    static int storedFiles(String v) {
        if (v != null) for (String x : v.split("[|]")) if (x.startsWith("N=")) try { return Integer.parseInt(x.substring(2)); } catch (NumberFormatException e) { return -1; }
        return -1;
    }

    /**
     * The latest release tag from github.com/<repo>/releases/latest without following its redirect (one small web request,
     * no API call, no page body): "-" when the repo has no release. Throws on a limit or a network error.
     */
    static String probeTag(String full) throws IOException {
        if (!open("web")) throw new Blocked("web back-off");
        HttpURLConnection c = (HttpURLConnection) new java.net.URL("https://github.com/" + full + "/releases/latest").openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("HEAD");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "CHGamePlayer/" + MainActivity.VERSION);
        try {
            int code = c.getResponseCode();
            if (code == 429 || code == 403) { backOff("web", c); throw new IOException("HTTP " + code); }
            if (code == 404) return "-";
            String loc = c.getHeaderField("Location");
            if (code < 300 || code > 399 || loc == null) throw new IOException("HTTP " + code);
            int at = loc.lastIndexOf("/tag/");
            return at < 0 ? "-" : Uri.decode(loc.substring(at + 5));
        } finally {
            c.disconnect();
        }
    }

    interface Count { int get() throws IOException; }

    /**
     * Whether a checked repo should be looked at again: stored tag had (null for a 1.61 entry) and file count files (-1 when
     * unknown), against the tag probed now; count gives the release's file count now (asked only for the same release).
     */
    static boolean changed(String had, int files, String tag, Count count) throws IOException {
        if (had == null) return !tag.equals("-");          /* an old entry: looked at again if it has a release */
        if (!tag.equals(had)) return true;                  /* a new release */
        if (tag.equals("-")) return false;                  /* still none */
        return files < 0 || count.get() != files;           /* the same release: files added since */
    }

    /** How many files a release has, from its github.com expanded_assets page (no API call). */
    static int assetCount(String full, String tag) throws IOException {
        String html = fetch("https://github.com/" + full + "/releases/expanded_assets/" + Uri.encode(tag));
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/releases/download/[^/\"]+/([^\"?]+)\"").matcher(html);
        while (m.find() && seen.size() < 30) seen.add(m.group(1));
        return seen.size();
    }

    /**
     * Of the repos skipped as unchanged, the ones whose latest release tag differs from the one stored when they were
     * checked (a release made in GitHub's web form may not change pushed_at), or whose release has a different number of
     * files (they are often uploaded after the release is made). Probed six at a time until the deadline;
     * the rest stay skipped. Counts h.skipped and h.released.
     */
    private static java.util.List<JSONObject> newRelease(Hunt h, java.util.List<JSONObject> skipped, Map<String, String> checked,
                                                         long deadline, java.util.function.Consumer<String> say) {
        java.util.List<JSONObject> out = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        if (skipped.isEmpty()) return out;
        if (say != null) say.accept("Checking " + skipped.size() + " repos for new releases");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        java.util.List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
        for (JSONObject r : skipped) jobs.add(pool.submit(() -> {
            String full = r.optString("full_name"), had;
            int files;
            synchronized (h) { had = storedTag(checked.get(full)); files = storedFiles(checked.get(full)); }
            boolean again = false;
            if (System.currentTimeMillis() < deadline) try {
                final String tag = cleanTag(probeTag(full));
                again = changed(had, files, tag, () -> assetCount(full, tag));
            } catch (Exception ex) {
                if (limitError(ex)) synchronized (h) { h.limited = true; }
            }
            synchronized (h) { if (again) { h.released++; out.add(r); } else h.skipped++; }
        }));
        for (java.util.concurrent.Future<?> f : jobs) try { f.get(); } catch (Exception ex) { /* counted inside */ }
        pool.shutdown();
        return new java.util.ArrayList<>(out);
    }

    /** Keeps at most CHECKED_MAX entries, dropping the ones looked at longest ago (a size cap, not an age limit). */
    private static final int CHECKED_MAX = 800;

    private static void saveChecked(SharedPreferences prefs, Map<String, String> checked, long today) {
        try {
            java.util.List<Map.Entry<String, String>> all = new java.util.ArrayList<>(checked.entrySet());
            java.util.Collections.sort(all, (x, y) -> Long.compare(dayOf(y.getValue()), dayOf(x.getValue())));
            JSONObject o = new JSONObject();
            for (int i = 0; i < all.size() && i < CHECKED_MAX; i++) o.put(all.get(i).getKey(), all.get(i).getValue());
            prefs.edit().putString("cat:checked", o.toString()).remove("cat:nogame").apply();
        } catch (Exception ex) { /* not kept */ }
    }

    /** Blocking: looks at the other repos of each developer whose game is installed; true when the set of new games changed. */
    static boolean discover(Context c, SharedPreferences prefs, Uri tree) {
        useNet(c);
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
                JSONArray list = ownerRepos(owner, new java.util.ArrayList<>());     /* github.com's page; the API only as a fallback */
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
                if (Files.safeName(name) == null) continue;      /* a name from a list is a plain file name or nothing */
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
            String why = Pins.check(c, from, null, Pins.sha256(data, data.length));      /* before anything is written */
            if (why != null) return why;
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
            /* a file of that name is another game's (or the user's): a download never replaces it */
            if (doc != null && !repo.equalsIgnoreCase(repoFor(c, prefs, file))) return file + " is already in the games folder";
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
            String from = "https://github.com/" + p[1] + "/releases/download/" + Uri.encode(p[0]) + "/" + Uri.encode(p[2]);
            try {
                h = CatalogueActivity.open(from);
            } catch (IOException first) {
                /* the release names its game file differently (MysticBalloon-1.4.1.chgame): look at its asset list */
                String asset = findAsset(p[1], p[0]);
                if (asset == null) throw first;
                h = CatalogueActivity.open(from = "https://github.com/" + p[1] + "/releases/download/"
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
            String why = Pins.check(c, from, null, Pins.sha256(head, head.length));      /* before the installed file is touched */
            if (why != null) return why;
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
        if (Files.safeName(name) == null) return false;
        Uri doc = existing(cr, tree, name);
        if (doc != null && !replace) return true;       /* never touch a file we cannot prove is an old copy (saves, hand-installed games) */
        HttpURLConnection h = null;
        InputStream in = null;
        long len = -1;
        String from = null;             /* the release address actually read: its checksum is checked before writing */
        if (repo != null) {
            if (tag == null) {          /* the latest release, by its tag: a checksum belongs to one release */
                try {
                    String t = probeTag(repo);
                    if (!"-".equals(t)) tag = t;
                } catch (IOException ex) {
                    /* no tag: nothing remote */
                }
            }
            String[] urls = tag == null ? new String[0]      /* tags are stored with or without the v */
                    : new String[] { "download/" + Uri.encode(tag), "download/v" + Uri.encode(tag) };
            for (String u : urls) {      /* the installed version's release */
                try {
                    from = "https://github.com/" + repo + "/releases/" + u + "/" + Uri.encode(name);
                    h = CatalogueActivity.open(from);
                    in = h.getInputStream();
                    len = h.getContentLengthLong();
                    break;
                } catch (IOException ex) {
                    if (h != null) h.disconnect();
                    h = null;
                    in = null;
                    from = null;
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
            java.security.MessageDigest sum = Pins.sha();
            sum.update(head, 0, n);
            try (OutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(head, 0, n);
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) {
                    total += r;
                    if (total > (128L << 20)) throw new IOException("file too big");
                    out.write(buf, 0, r);
                    sum.update(buf, 0, r);
                }
            }
            if (total == 0) return doc != null;
            if (from != null && Pins.check(c, from, null, Pins.hex(sum.digest())) != null) return doc != null;     /* a file that fails its checksum is not written */
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
