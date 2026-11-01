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
        Map<String, String> days = new HashMap<>();       /* repo -> date of its latest change, for repos with no release */
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
                /* no release: the date of the repo's latest change stands in for a version */
                String day = days.get(s[0]);
                if (day == null) {
                    day = "";
                    try {
                        HttpURLConnection h = CatalogueActivity.open("https://api.github.com/repos/" + s[0] + "/commits?per_page=1");
                        try (InputStream in = h.getInputStream()) {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"date\"\\s*:\\s*\"(\\d{4}-\\d\\d-\\d\\d)")
                                    .matcher(CatalogueActivity.readAll(in, 1 << 16));
                            if (m.find()) day = m.group(1);
                        } finally {
                            h.disconnect();
                        }
                    } catch (Exception ex) {
                        /* leave it */
                    }
                    days.put(s[0], day);
                }
                if (!day.isEmpty()) ed.putString("ver:" + file.toUpperCase(Locale.ROOT), day);
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
            ed.remove("got:" + key).remove("upd:" + key).apply();
            return;
        }
        String repo = src[0];
        if (offerAgain) {
            try {
                ed.putString("new:" + repo, new JSONObject().put("repo", repo).put("tag", prefs.getString("got:" + key, ""))
                        .put("asset", src[1]).put("name", name).put("owner", repo.substring(0, repo.indexOf('/')))
                        .put("desc", "Removed from this device.").toString());
            } catch (Exception ex) {
                /* not offered */
            }
            ed.remove("newdone:" + repo).remove("upd:" + key);
        } else {
            ed.putBoolean("newdone:" + repo, true).remove("new:" + repo).remove("got:" + key).remove("upd:" + key);
        }
        ed.apply();
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
        ed.remove("newAt2").remove("newSeen").apply();
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
                            .put("desc", g.optString("description", ""));
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

    /** Blocking: looks at the other repos of each developer whose game is installed; true when the set of new games changed. */
    static boolean discover(Context c, SharedPreferences prefs, Uri tree) {
        ContentResolver cr = c.getContentResolver();
        Map<String, String[]> src = sources(c, prefs);
        java.util.Set<String> known = new java.util.HashSet<>(), owners = new java.util.TreeSet<>();
        for (String[] s : src.values()) known.add(s[0]);
        java.util.Set<String> have = new java.util.HashSet<>(), haveRepos = new java.util.HashSet<>();
        for (Files.Entry e : Files.list(cr, tree)) {
            if (!Files.isGame(e.path)) continue;
            String up = e.path.substring(e.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
            have.add(up);
            String[] s = src.get(up);
            if (s != null) { owners.add(s[0].substring(0, s[0].indexOf('/'))); haveRepos.add(s[0]); }
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
        for (String owner : owners) {
            if (++owned > 8) break;
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
                        HttpURLConnection rc = CatalogueActivity.open("https://api.github.com/repos/" + full + "/releases/latest");
                        JSONObject rel;
                        try (InputStream in = rc.getInputStream()) {
                            rel = new JSONObject(CatalogueActivity.readAll(in, 1 << 20));
                        } finally {
                            rc.disconnect();
                        }
                        JSONArray assets = rel.optJSONArray("assets");
                        String asset = null;
                        for (int k = 0; assets != null && k < assets.length(); k++) {
                            String an = assets.getJSONObject(k).optString("name", "");
                            if (Files.isGame(an)) { asset = an; break; }
                        }
                        if (asset == null) continue;
                        String name = r.optString("name").replaceFirst("^CH(?=[A-Z])", "");
                        ed.putString("new:" + full, new JSONObject().put("repo", full).put("tag", rel.optString("tag_name"))
                                .put("asset", asset).put("name", name).put("owner", owner)
                                .put("desc", r.optString("description", "")).toString());
                        changed = true;
                    } catch (Exception ex) {
                        /* no release, or no network: next time */
                    }
                }
            } catch (Exception ex) {
                /* this developer is looked at again next time */
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
            ed.remove("new:" + repo).putBoolean("newdone:" + repo, true).apply();
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
            try {
                h = CatalogueActivity.open("https://github.com/" + repo + "/releases/download/" + Uri.encode(tag) + "/" + Uri.encode(asset));
            } catch (IOException first) {
                String found = findAsset(repo, tag);
                if (found == null) throw first;
                h = CatalogueActivity.open("https://github.com/" + repo + "/releases/download/" + Uri.encode(tag) + "/" + found);
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
                    .putBoolean("newdone:" + repo, true).apply();
            return null;
        } catch (IOException | RuntimeException ex) {
            return ex.getMessage() == null ? "download failed" : ex.getMessage();
        }
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
}
