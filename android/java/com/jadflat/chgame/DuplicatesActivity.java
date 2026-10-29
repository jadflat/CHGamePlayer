package com.jadflat.chgame;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Duplicate games: two or more .CHG files in the games folder with the same
 * title and author (whatever they are called). Each set lists its files; the
 * one to keep is the one whose saves exist, else the shorter-named, older one.
 * The others are deleted only after a confirm that names them.
 */
public class DuplicatesActivity extends Page {
    private final List<List<Files.Entry>> sets = new ArrayList<>();   /* each: the file to keep first */
    private final Map<String, String> titles = new LinkedHashMap<>();   /* path -> the set's title */
    private boolean loaded, busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        build();
        scan();
    }

    @Override
    void build() {
        header("Duplicate games");
        if (!loaded) {
            section("");
            row("Looking…", "Reading the games folder", null);
        } else if (sets.isEmpty()) {
            section("");
            row("No duplicate games", "No two .CHG files have the same title and author", null);
        }
        for (List<Files.Entry> set : sets) {
            section(titles.get(set.get(0).path));
            for (int i = 0; i < set.size(); i++)
                row(set.get(i).path, i == 0 ? (hasSaves(set.get(i)) ? "Keep: it has saves" : "Keep") : "Extra copy",
                        null);
            List<Files.Entry> extra = set.subList(1, set.size());
            StringBuilder names = new StringBuilder();
            for (Files.Entry e : extra) names.append(names.length() > 0 ? ", " : "").append(e.path);
            row("Delete the extra " + (extra.size() == 1 ? "copy" : "copies"), names.toString(), () -> {
                if (busy) return;
                Saves.confirm(this, "Delete " + names + "? " + set.get(0).path + " is kept.", () -> delete(extra));
            });
        }
        focus();
    }

    private Uri tree() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String s = prefs.getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return u;
        return null;
    }

    /** True when the game has a save, a state or a resume point, all kept under its file's base name. */
    private boolean hasSaves(Files.Entry e) {
        String name = e.path.substring(e.path.lastIndexOf('/') + 1);
        try {
            if (Saves.saveFile(this, name).isFile() || Saves.autoFile(this, name).isFile()) return true;
            for (int i = 1; i <= Saves.SLOTS; i++) if (Saves.stateFile(this, name, i).isFile()) return true;
        } catch (java.io.IOException ex) {
            /* none */
        }
        return false;
    }

    private void scan() {
        Uri t = tree();
        if (t == null) { loaded = true; build(); return; }
        new Thread(() -> {
            Map<String, List<Files.Entry>> by = new LinkedHashMap<>();
            Map<String, String> name = new LinkedHashMap<>();
            for (Files.Entry e : Files.list(getContentResolver(), t)) {
                if (!Files.isGame(e.path)) continue;
                String[] id = Cart.ident(getContentResolver(), e.uri);
                if (id == null || id[0].isEmpty()) continue;
                String k = Cart.key(id);
                if (!by.containsKey(k)) by.put(k, new ArrayList<>());
                by.get(k).add(e);
                name.put(k, id[0] + (id[1].isEmpty() ? "" : " · " + id[1]));
            }
            List<List<Files.Entry>> found = new ArrayList<>();
            Map<String, String> tt = new LinkedHashMap<>();
            for (Map.Entry<String, List<Files.Entry>> g : by.entrySet()) {
                if (g.getValue().size() < 2) continue;
                List<Files.Entry> l = g.getValue();
                Collections.sort(l, (a, b) -> {
                    int s = Boolean.compare(hasSaves(b), hasSaves(a));
                    if (s != 0) return s;
                    s = Integer.compare(a.path.length(), b.path.length());
                    if (s != 0) return s;
                    s = Long.compare(a.modified, b.modified);
                    return s != 0 ? s : a.path.compareToIgnoreCase(b.path);
                });
                found.add(l);
                tt.put(l.get(0).path, name.get(g.getKey()));
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                sets.clear();
                sets.addAll(found);
                titles.clear();
                titles.putAll(tt);
                loaded = true;
                build();
            });
        }, "duplicates-scan").start();
    }

    /** Deletes through the document provider, after the user has said yes. */
    private void delete(List<Files.Entry> files) {
        busy = true;
        List<Files.Entry> list = new ArrayList<>(files);
        new Thread(() -> {
            ContentResolver cr = getContentResolver();
            int n = 0;
            for (Files.Entry e : list) {
                try {
                    if (DocumentsContract.deleteDocument(cr, e.uri)) n++;
                } catch (Exception ex) {
                    /* not deleted */
                }
            }
            final int done = n;
            runOnUiThread(() -> {
                busy = false;
                Saves.toast(this, done == list.size() ? "Deleted " + done : "Deleted " + done + " of " + list.size());
                scan();
            });
        }, "duplicates-delete").start();
    }
}
