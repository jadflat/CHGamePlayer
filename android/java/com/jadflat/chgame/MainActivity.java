package com.jadflat.chgame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The game list: the games in the chosen folder as cards (cart picture, title,
 * author and version, all read from each .CHG), and the selected game's
 * picture large beside them. A plays, X hides, SELECT (or the gear) opens
 * Options, where everything else lives.
 */
public class MainActivity extends Activity {
    static final String VERSION = "0.32";
    static final String PREFS = "chgame";

    private SharedPreferences prefs;
    private TextView count, artTitle, artInfo, artPort;
    private Preview preview;
    private TextView mute;
    private ArtView art;
    private ListView list;
    private View listPane, emptyPane;
    private final GameAdapter adapter = new GameAdapter();
    private final List<Files.Entry> games = new ArrayList<>();         /* in list order */
    private final List<Object> rows = new ArrayList<>();               /* a section's name (String) or a game's index (Integer) */
    private Set<String> hidden = new HashSet<>(), favs = new HashSet<>();
    private int shownRow = -1;                                  /* a game can be on two rows (Favourites, Last played) */
    private final Map<Uri, String> titles = new HashMap<>();   /* the title in each header */
    private final Map<Uri, Cart> carts = new HashMap<>();      /* picture, author, version */
    private final Set<Uri> loading = new HashSet<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private String treeSeen;                                    /* the folder the caches are for */
    private int generation;                                     /* drops a listing a newer one replaced */
    private int shown = -1;
    private boolean gridMode;                                   /* the list as cells, not rows (Options: Layout) */
    private int gridCols = 2;                                   /* cells in a grid row */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);

        /* left: header and the list (or, with no folder yet, the first step) */
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        /* the title, and the app's version */
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, "CHGame", 28, Ui.ON_SURFACE, Ui.heavy());
        name.setLetterSpacing(-0.03f);
        name.setPadding(0, 0, 0, 0);
        titleLine.addView(name);
        TextView version = Ui.text(this, "v" + VERSION, 14, Ui.ON_SURFACE_VARIANT, Ui.MEDIUM);
        version.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), 0, 0);
        titleLine.addView(version);
        heading.addView(titleLine);
        count = Ui.text(this, "", 14, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        heading.addView(count);
        head.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView gear = Ui.text(this, "⚙", 24, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.card(this, Color.TRANSPARENT, 24));
        gear.setContentDescription("Options");
        gear.setFocusable(false);
        gear.setOnClickListener(v -> openOptions());
        /* search the titles (R3 or SEARCH on a pad) */
        TextView find = Ui.text(this, "🔍", 20, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        find.setGravity(Gravity.CENTER);
        find.setBackground(Ui.card(this, Color.TRANSPARENT, 24));
        find.setContentDescription("Search the games");
        find.setFocusable(false);
        find.setOnClickListener(v -> search());
        head.addView(find, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        /* the preview's sound: on or muted (Options' "Video and sound" / "Video only") */
        mute = Ui.text(this, "", 22, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        mute.setGravity(Gravity.CENTER);
        mute.setBackground(Ui.card(this, Color.TRANSPARENT, 24));
        mute.setFocusable(false);
        mute.setOnClickListener(v -> toggleMute());
        head.addView(mute, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        head.addView(gear, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        head.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 12));
        left.addView(head);

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(Ui.dp(this, 4));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((p, v, row, id) -> {
            int g = gameAt(row);
            if (g < 0) return;
            if (row == shownRow) play(g);
            else select(g, row);
        });
        list.setOnItemLongClickListener((p, v, row, id) -> {
            int g = gameAt(row);
            if (g >= 0) gameMenu(g, row);
            return g >= 0;
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int row, long id) {
                int g = gameAt(row);
                if (g >= 0) select(g, row);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        /* grid mode: a width change (a turn, a fold) cuts the rows again */
        list.addOnLayoutChangeListener((v, x0, y0, x1, y1, ox0, oy0, ox1, oy1) -> {
            if (gridMode && x1 - x0 != ox1 - ox0) list.post(this::regrid);
        });
        listPane = list;
        body.addView(list);
        emptyPane = emptyState();
        body.addView(emptyPane);
        left.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));

        /* right: the selected game, large */
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setBackground(Ui.rounded(this, Ui.SURFACE, 28));
        right.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 14));
        art = new ArtView(this, 12);
        art.fill = true;            /* as big as the panel allows, not a whole-number scale */
        right.addView(art, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        artTitle = Ui.text(this, "", 17, Ui.ON_SURFACE, Ui.pixel(this));
        artTitle.setGravity(Gravity.CENTER);
        artTitle.setMaxLines(2);
        artTitle.setEllipsize(TextUtils.TruncateAt.END);
        artTitle.setLineSpacing(Ui.dp(this, 6), 1f);
        artTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        right.addView(artTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        artInfo = Ui.text(this, "", 15, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        artInfo.setGravity(Gravity.CENTER);
        right.addView(artInfo);
        artPort = Ui.text(this, "", 13, Ui.ON_PRIMARY_CONTAINER, Ui.MEDIUM);
        artPort.setBackground(Ui.rounded(this, Ui.PRIMARY_CONTAINER, 8));
        artPort.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.setMargins(0, Ui.dp(this, 8), 0, 0);
        right.addView(artPort, pp);
        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER);
        hints.setPadding(0, Ui.dp(this, 10), 0, 0);
        for (String[] h : new String[][] { { "A", "Play" }, { "Y", "Favourite" }, { "X", "Hide" } }) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            hints.addView(Ui.chip(this, h[0], h[1]), lp);
        }
        right.addView(hints);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        rp.setMargins(Ui.dp(this, 20), 0, 0, 0);
        root.addView(right, rp);

        setContentView(root);
        Ui.edgeToEdge(this, root);
        preview = new Preview(this, b -> art.set(b));
    }

    /** No folder yet: one clear first step. */
    private View emptyState() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.rounded(this, Ui.SURFACE, 28));
        e.setPadding(Ui.dp(this, 32), Ui.dp(this, 32), Ui.dp(this, 32), Ui.dp(this, 32));
        TextView t = Ui.text(this, "Choose your games folder", 22, Ui.ON_SURFACE, Ui.REGULAR);
        t.setGravity(Gravity.CENTER);
        e.addView(t);
        TextView s = Ui.text(this, "The folder with your .CHG games and the .DAT files they use. "
                + "The app sees only that folder.", 15, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        e.addView(s);
        TextView b = Ui.text(this, "Choose folder", 16, Ui.ON_PRIMARY, Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 28), Ui.dp(this, 12), Ui.dp(this, 28), Ui.dp(this, 12));
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33000000),
                Ui.rounded(this, Ui.PRIMARY, 24), null));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> startActivity(new Intent(this, OptionsActivity.class).putExtra("pickOnly", true)));
        e.addView(b);
        e.setVisibility(View.GONE);
        return e;
    }

    @Override
    protected void onResume() {
        super.onResume();
        String t = prefs.getString("tree", null);
        if (t == null ? treeSeen != null : !t.equals(treeSeen)) {
            synchronized (titles) { titles.clear(); }
            carts.clear();
            loading.clear();
            treeSeen = t;
        }
        showMute();
        gridMode = Options.gridList(prefs);     /* the refresh below builds the rows in this layout */
        refresh();
        ((InputManager) getSystemService(INPUT_SERVICE)).registerInputDeviceListener(pads, null);
        offerTouch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        preview.stop();
        ((InputManager) getSystemService(INPUT_SERVICE)).unregisterInputDeviceListener(pads);
    }

    /* ---------------------------------------------------------------- */
    /* Touch controls: off by default; offered once a run when no pad is  */
    /* connected (a Retroid's own controls count as one), and again when  */
    /* a pad is unplugged                                                 */
    /* ---------------------------------------------------------------- */

    private static boolean touchOffered;

    private final InputManager.InputDeviceListener pads = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { }
        @Override public void onInputDeviceChanged(int id) { }
        @Override public void onInputDeviceRemoved(int id) {
            touchOffered = false;
            offerTouch();
        }
    };

    static boolean hasPad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    private void offerTouch() {
        if (touchOffered || !"off".equals(Options.touch(prefs)) || hasPad()) return;
        touchOffered = true;
        new android.app.AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("No controller found")
                .setMessage("Show touch controls on the screen in games? They hide again once a controller "
                        + "is used. Options → Touch controls changes this later.")
                .setPositiveButton("Turn on touch controls", (d, w) -> prefs.edit().putString("touch", "auto").apply())
                .setNegativeButton("Not now", null)
                .show();
    }

    /** The mute button's look: hidden when previews are off in Options. */
    private void showMute() {
        String mode = Options.preview(prefs);
        mute.setVisibility("off".equals(mode) ? View.GONE : View.VISIBLE);
        boolean on = "sound".equals(mode);
        mute.setText(on ? "\uD83D\uDD0A" : "\uD83D\uDD07");
        mute.setContentDescription(on ? "Mute the preview" : "Sound on for the preview");
    }

    private void toggleMute() {
        String mode = Options.preview(prefs);
        if ("off".equals(mode)) return;
        boolean on = !"sound".equals(mode);
        prefs.edit().putString("preview", on ? "sound" : "video").apply();
        preview.setSound(on);
        showMute();
        Saves.toast(this, on ? "Preview sound on" : "Preview muted");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        preview.shutdown();
        reader.shutdownNow();
    }

    private Uri tree() {
        String s = prefs.getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
        return null;
    }

    /* ---------------------------------------------------------------- */
    /* The list                                                           */
    /* ---------------------------------------------------------------- */

    private void refresh() {
        Uri t = tree();
        if (t == null) {
            games.clear();
            adapter.notifyDataSetChanged();
            listPane.setVisibility(View.GONE);
            emptyPane.setVisibility(View.VISIBLE);
            emptyPane.requestFocus();
            count.setText("No games folder yet");
            shown = -1;
            showSelected();
            return;
        }
        listPane.setVisibility(View.VISIBLE);
        emptyPane.setVisibility(View.GONE);
        if (games.isEmpty()) count.setText("Reading the games folder…");
        final int gen = ++generation;
        /* the folder and each game's header (its title) are read off the UI thread */
        new Thread(() -> {
            List<Files.Entry> found = new ArrayList<>();
            Map<Uri, String> read = new HashMap<>();
            Map<Uri, Cart> readCarts = new HashMap<>();
            for (Files.Entry e : Files.list(getContentResolver(), t)) {
                if (!Files.isGame(e.path)) continue;
                found.add(e);
                String title;
                synchronized (titles) { title = titles.get(e.uri); }
                Cart c = Cart.read(getContentResolver(), e.uri);
                if (c != null) readCarts.put(e.uri, c);
                read.put(e.uri, title != null ? title : c != null && !c.title.isEmpty() ? c.title : null);
            }
            runOnUiThread(() -> {
                if (gen != generation || isFinishing()) return;
                synchronized (titles) {
                    for (Map.Entry<Uri, String> r : read.entrySet())
                        if (r.getValue() != null) titles.put(r.getKey(), r.getValue());
                }
                carts.putAll(readCarts);
                showList(found);
            });
        }).start();
    }

    private void showList(List<Files.Entry> found) {
        hidden = Options.hidden(prefs);
        boolean showHidden = Options.showHidden(prefs);
        int hiddenCount = 0;
        games.clear();
        for (Files.Entry e : found) {
            if (hidden.contains(e.path)) {
                hiddenCount++;
                if (!showHidden) continue;
            }
            games.add(e);
        }
        /* CHGame originals first, then ports; in each, by the chosen sort (name, most played, newest file) */
        String sort = Options.sort(prefs);
        Collections.sort(games, (a, b) -> {
            int s = Boolean.compare(isPort(a), isPort(b));
            if (s != 0) return s;
            if ("played".equals(sort)) {
                int k = Long.compare(Stats.time(this, fileName(b)), Stats.time(this, fileName(a)));
                if (k != 0) return k;
            } else if ("added".equals(sort)) {
                int k = Long.compare(b.modified, a.modified);
                if (k != 0) return k;
            }
            int c = displayTitle(a).compareToIgnoreCase(displayTitle(b));
            return c != 0 ? c : a.path.compareToIgnoreCase(b.path);
        });
        favs = Options.favourites(prefs);
        buildRows();
        count.setText(games.size() + (games.size() == 1 ? " game" : " games")
                + (hiddenCount > 0 && !showHidden ? " · " + hiddenCount + " hidden" : ""));
        adapter.notifyDataSetChanged();
        int pos = 0;
        String lastPath = prefs.getString("lastPath", null);
        for (int i = 0; i < games.size(); i++) if (games.get(i).path.equals(lastPath)) pos = i;
        shown = -1;
        shownRow = -1;
        if (games.isEmpty()) {
            showSelected();
            return;
        }
        list.requestFocus();
        int row = rowOf(pos);
        list.setSelectionFromTop(row, headerAbove(row) ? Ui.dp(this, 40) : 0);
        previewing = false;
        select(pos, row);
    }

    private static final int RECENT = 1;     /* "Last played": the one game */

    /** The list's rows: each section's name, then its games. Grid mode puts a section's games in rows of gridCols. */
    private void buildRows() {
        gridCols = colsFor(listWidth());
        rows.clear();
        /* ★ Favourites and Last played first: those games are in their own section too */
        List<Integer> fav = new ArrayList<>(), recent = new ArrayList<>();
        List<Integer> originals = new ArrayList<>(), ports = new ArrayList<>();
        for (int i = 0; i < games.size(); i++) {
            if (favs.contains(games.get(i).path)) fav.add(i);
            if (Stats.last(this, fileName(games.get(i))) > 0) recent.add(i);
            (isPort(games.get(i)) ? ports : originals).add(i);
        }
        Collections.sort(recent, (a, b) -> Long.compare(Stats.last(this, fileName(games.get(b))),
                Stats.last(this, fileName(games.get(a)))));
        if (recent.size() > RECENT) recent = recent.subList(0, RECENT);
        addSection("★ Favourites", fav);
        addSection("Last played", recent);
        addSection("CHGame originals", originals);
        addSection("Ports", ports);
    }

    private void addSection(String name, List<Integer> ids) {
        if (ids.isEmpty()) return;
        rows.add(name);
        if (!gridMode) {
            rows.addAll(ids);
            return;
        }
        for (int i = 0; i < ids.size(); i += gridCols) {
            int n = Math.min(gridCols, ids.size() - i);
            int[] cells = new int[n];
            for (int k = 0; k < n; k++) cells[k] = ids.get(i + k);
            rows.add(cells);
        }
    }

    /** Grid mode's columns: about 200 dp a cell, at least two. */
    private int colsFor(int px) {
        return Math.max(2, px / Ui.dp(this, 200));
    }

    /** The list's width, or half the screen before the list is laid out. */
    private int listWidth() {
        return list.getWidth() > 0 ? list.getWidth() : getResources().getDisplayMetrics().widthPixels / 2;
    }

    /** Grid mode: the rows again for the list's new width, the selected game kept selected. */
    private void regrid() {
        if (!gridMode || games.isEmpty()) return;
        buildRows();
        adapter.notifyDataSetChanged();
        if (shown >= 0) goTo(shown);
    }

    private void select(int pos, int row) {
        if (pos < 0 || pos >= games.size()) return;
        boolean moved = pos != shown;
        shown = pos;
        shownRow = row;
        if (!gridMode) list.setItemChecked(row, true);
        /* the first game of a section: keep its section's name in view above it (the list
           would otherwise scroll the game to the top and the name out of sight) */
        if (headerAbove(row) && row - 1 < list.getFirstVisiblePosition()) {
            final int r = row;
            list.post(() -> list.setSelectionFromTop(r, Ui.dp(this, 40)));
        }
        showSelected();
        if (gridMode) adapter.notifyDataSetChanged();       /* the highlight moves to this cell */
        if (moved || !previewing) startPreview();
    }

    /** True for a section's name row. */
    private boolean isHeader(int row) {
        return rows.get(row) instanceof String;
    }

    private boolean headerAbove(int row) {
        return row > 0 && isHeader(row - 1);
    }

    /** Moves the selection to the game at pos, its section's name kept in view. */
    private void goTo(int pos) {
        int row = rowOf(pos);
        list.setSelectionFromTop(row, headerAbove(row) ? Ui.dp(this, 40) : 0);
        select(pos, row);
    }

    /** The first letter of a game's title, upper case; '#' for a title that starts with no letter. */
    private char letterOf(int pos) {
        String t = displayTitle(games.get(pos));
        char c = t.isEmpty() ? '#' : Character.toUpperCase(t.charAt(0));
        return Character.isLetter(c) ? c : '#';
    }

    /** L2 (dir -1) or R2 (dir 1): to the first game of the previous or next letter. */
    private void jumpLetter(int dir) {
        if (shown < 0 || games.isEmpty()) return;
        char from = letterOf(shown);
        int j = shown + dir;
        while (j >= 0 && j < games.size() && letterOf(j) == from) j += dir;
        if (j < 0 || j >= games.size()) return;
        char to = letterOf(j);
        /* going back, step to the first game of that letter, not its last */
        while (j - dir >= 0 && j - dir < games.size() && letterOf(j - dir) == to) j -= dir;
        goTo(j);
    }

    /** D-pad left (dir -1) or right (dir 1): up or down a page of rows, onto the nearest game. */
    private void page(int dir) {
        if (shown < 0 || rows.isEmpty()) return;
        int n = Math.max(1, list.getLastVisiblePosition() - list.getFirstVisiblePosition());
        int row = Math.max(0, Math.min(rows.size() - 1, shownRow + dir * n));
        int r = row;
        while (r >= 0 && r < rows.size() && gameAt(r) < 0) r += dir;      /* a header: the game past it */
        if (r < 0 || r >= rows.size()) for (r = row; r >= 0 && r < rows.size() && gameAt(r) < 0; ) r -= dir;
        if (r < 0 || r >= rows.size() || r == shownRow) return;
        list.setSelectionFromTop(r, headerAbove(r) ? Ui.dp(this, 40) : 0);
        select(gameAt(r), r);
    }

    /** Asks for part of a title, then selects the first game that has it. */
    private void search() {
        if (games.isEmpty()) return;
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Part of a title");
        field.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        new android.app.AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Search the games")
                .setView(field)
                .setPositiveButton("OK", (d, w) -> findTitle(field.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
        field.requestFocus();
    }

    /** The first game whose title contains the text, in any case. */
    private void findTitle(String text) {
        String want = text.trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty()) return;
        for (int i = 0; i < games.size(); i++) {
            if (displayTitle(games.get(i)).toLowerCase(Locale.ROOT).contains(want)) {
                goTo(i);
                return;
            }
        }
        Saves.toast(this, "No match");
    }

    private boolean previewing;

    private boolean isPort(Files.Entry e) {
        Cart c = carts.get(e.uri);
        return c != null && c.portedFrom != null;
    }

    /** The game a list row shows, or -1 for a section header. */
    private int gameAt(int row) {
        return row >= 0 && row < rows.size() && rows.get(row) instanceof Integer ? (Integer) rows.get(row) : -1;
    }

    /** The row showing a game: its list row, or in grid mode the grid row that holds it. */
    private int rowOf(int game) {
        for (int r = 0; r < rows.size(); r++) {
            if (Integer.valueOf(game).equals(rows.get(r))) return r;
            if (cellsAt(r) != null && indexIn(cellsAt(r), game) >= 0) return r;
        }
        return 0;
    }

    private void startPreview() {
        if (shown < 0 || shown >= games.size()) return;
        previewing = true;
        preview.schedule(games.get(shown), fileName(games.get(shown)));
    }

    /** The title in the game's header, or its file name if it has none. */
    private String displayTitle(Files.Entry e) {
        String t;
        synchronized (titles) { t = titles.get(e.uri); }
        if (t != null) return t;
        String n = fileName(e);
        return n.substring(0, n.length() - 4);
    }

    private static String fileName(Files.Entry e) {
        return e.path.substring(e.path.lastIndexOf('/') + 1);
    }

    /** "author · v1.0", once the cart has been read. */
    private String details(Files.Entry e) {
        Cart c = cart(e);
        StringBuilder s = new StringBuilder();
        if (c != null) {
            s.append(c.credit());
            if (!c.version.isEmpty()) s.append(s.length() > 0 ? " · " : "").append("v").append(c.version);
            if (c.portedFrom != null) s.append(s.length() > 0 ? " · " : "").append(c.portedFrom);
        }
        String t = Stats.duration(Stats.time(this, fileName(e)));
        if (!t.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(t);
        if (hidden.contains(e.path)) s.append(s.length() > 0 ? " · " : "").append("Hidden");
        return s.toString();
    }

    /** The game's cart (picture, author, version), read in the background the first time. */
    private Cart cart(Files.Entry e) {
        Cart c = carts.get(e.uri);
        if (c == null && !loading.contains(e.uri)) {
            loading.add(e.uri);
            reader.execute(() -> {
                Cart read = Cart.read(getContentResolver(), e.uri);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (read != null) carts.put(e.uri, read);
                    adapter.notifyDataSetChanged();
                    if (shown >= 0 && shown < games.size() && games.get(shown).uri.equals(e.uri)) showSelected();
                });
            });
        }
        return c;
    }

    private void showSelected() {
        if (shown < 0 || shown >= games.size()) {
            boolean folder = tree() != null;
            art.set(null);
            artTitle.setText(folder && games.isEmpty() ? "No games here" : "");
            artInfo.setText(folder && games.isEmpty() ? "Put .CHG files in the games folder" : "");
            artPort.setVisibility(View.GONE);
            return;
        }
        Files.Entry e = games.get(shown);
        Cart c = cart(e);
        art.set(c == null ? null : c.picture);
        artTitle.setText(displayTitle(e));
        StringBuilder d = new StringBuilder();
        if (c != null) {
            if (!c.credit().isEmpty()) d.append("by ").append(c.credit());
            if (!c.version.isEmpty()) d.append(d.length() > 0 ? " · " : "").append("v").append(c.version);
            if (c.picture == null) d.append(d.length() > 0 ? " · " : "").append("No cartridge picture");
        }
        if (hidden.contains(e.path)) d.append(d.length() > 0 ? " · " : "").append("Hidden");
        long time = Stats.time(this, fileName(e)), last = Stats.last(this, fileName(e));
        if (last > 0) d.append("\n").append(time > 0 ? "Played " + Stats.duration(time) + " · " : "")
                .append("last ").append(Stats.when(last));
        artInfo.setText(d);
        if (c != null && c.portedFrom != null) {
            artPort.setText(c.portedFrom + " port" + (c.porter != null ? ", ported by " + c.porter : ""));
            artPort.setVisibility(View.VISIBLE);
        } else {
            artPort.setVisibility(View.GONE);
        }
    }

    private void play(int pos) {
        if (pos < 0 || pos >= games.size()) return;
        Files.Entry e = games.get(pos);
        /* a game that names card files not in the games folder: say so, and let the player start anyway */
        Cart c = carts.get(e.uri);
        if (c == null) {
            try { c = Cart.read(getContentResolver(), e.uri); } catch (Exception ignored) { }
        }
        List<String> missing = missingDats(c);
        if (missing.isEmpty()) {
            start(pos);
            return;
        }
        new android.app.AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Missing card file")
                .setMessage(displayTitle(e) + " needs " + TextUtils.join(", ", missing)
                        + " on the SD card. Put it in the games folder.")
                .setPositiveButton("Start anyway", (d, w) -> start(pos))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Starts the game at pos. */
    private void start(int pos) {
        preview.stop();
        previewing = false;
        Files.Entry e = games.get(pos);
        prefs.edit().putString("lastPath", e.path).apply();
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra("game", e.uri.toString());
        i.putExtra("name", fileName(e));
        i.putExtra("title", displayTitle(e));
        startActivity(i);
    }

    /** The card files a game names that are not in the games folder (the app's SD card). */
    List<String> missingDats(Cart c) {
        List<String> missing = new ArrayList<>();
        if (c == null || c.dats.isEmpty()) return missing;
        Set<String> have = new HashSet<>();
        Uri t = tree();
        if (t != null)
            for (Files.Entry f : Files.list(getContentResolver(), t))
                have.add(f.path.substring(f.path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT));
        for (String d : c.dats)
            if (!have.contains(d)) missing.add(d);
        return missing;
    }

    private void openOptions() {
        preview.stop();
        previewing = false;
        Intent i = new Intent(this, OptionsActivity.class);
        if (shown >= 0 && shown < games.size()) {
            Files.Entry e = games.get(shown);
            i.putExtra("file", fileName(e));
            i.putExtra("title", displayTitle(e));
        }
        startActivity(i);
    }

    /** Stars the game at pos, or takes its star away. */
    private void toggleFavourite(int pos) {
        if (pos < 0 || pos >= games.size()) return;
        Files.Entry e = games.get(pos);
        boolean now = Options.toggleFavourite(prefs, e.path);
        Saves.toast(this, displayTitle(e) + (now ? " is a favourite" : " is no longer a favourite"));
        prefs.edit().putString("lastPath", e.path).apply();
        refresh();
    }

    /** A long press: what can be done with this game. */
    private void gameMenu(int pos, int row) {
        select(pos, row);
        Files.Entry e = games.get(pos);
        boolean fav = favs.contains(e.path), hid = hidden.contains(e.path);
        String[] items = { "Play", fav ? "Remove from favourites" : "\u2605 Add to favourites",
                hid ? "Show in the list again" : "Hide", "Saves…", "Game settings…", "About this game" };
        new android.app.AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(displayTitle(e))
                .setItems(items, (d, which) -> {
                    if (which == 0) play(pos);
                    else if (which == 1) toggleFavourite(pos);
                    else if (which == 2) toggleHidden(pos);
                    else if (which == 3) startActivity(new Intent(this, SavesActivity.class)
                            .putExtra("file", fileName(e)).putExtra("title", displayTitle(e)));
                    else if (which == 4) startActivity(new Intent(this, GameSettingsActivity.class)
                            .putExtra("file", fileName(e)).putExtra("title", displayTitle(e)));
                    else about(e);
                })
                .show();
    }

    /** Everything the app knows about a game: its credits, record, file and play time. */
    private void about(Files.Entry e) {
        Cart c = cart(e);
        StringBuilder s = new StringBuilder();
        if (c != null) {
            if (!c.credit().isEmpty()) s.append("By ").append(c.credit()).append("\n");
            if (!c.version.isEmpty()) s.append("Version ").append(c.version).append("\n");
            if (c.portedFrom != null) s.append(c.portedFrom).append(" port").append(c.porter != null ? ", ported to CHGame by " + c.porter : "").append("\n");
            if (c.description != null && !c.description.isEmpty()) s.append("\n").append(c.description).append("\n");
            if (c.license != null && !c.license.isEmpty()) s.append("\nLicence: ").append(c.license).append("\n");
            s.append(c.picture != null ? "\nHas a cartridge picture" : "\nNo cartridge picture").append("\n");
            if (!c.dats.isEmpty()) {
                /* the card files it names, against what is in the games folder */
                List<String> missing = missingDats(c);
                s.append("\nNeeds on the SD card:");
                for (String d : c.dats)
                    s.append("\n  ").append(d).append(!missing.contains(d) ? "  \u2713 in the games folder" : "  \u2717 MISSING: put it in the games folder");
                s.append("\n");
            }
        }
        s.append("\nFile: ").append(e.path);
        if (e.size > 0) s.append(" (").append(e.size / 1024).append(" KB)");
        long time = Stats.time(this, fileName(e)), last = Stats.last(this, fileName(e));
        s.append("\nPlayed: ").append(time > 0 ? Stats.duration(time) : last > 0 ? "under a minute" : "not yet");
        if (last > 0) s.append(", last ").append(Stats.when(last));
        new android.app.AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(displayTitle(e))
                .setMessage(s).setPositiveButton("OK", null).show();
    }

    /** Hides the game at pos from the list, or shows a hidden one again. */
    private void toggleHidden(int pos) {
        if (pos < 0 || pos >= games.size()) return;
        Files.Entry e = games.get(pos);
        boolean now = Options.toggleHidden(prefs, e.path);
        Saves.toast(this, displayTitle(e) + (now ? " hidden. Options shows hidden games." : " shown again"));
        if (now && pos + 1 < games.size()) prefs.edit().putString("lastPath", games.get(pos + 1).path).apply();
        refresh();
    }

    /** Grid mode: the d-pad moves the cells, so the list does not move its own focus. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (gridMode && shown >= 0 && dpad) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) gridMove(code);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** The games of a grid row, or null when the row is not one. */
    private int[] cellsAt(int row) {
        return row >= 0 && row < rows.size() && rows.get(row) instanceof int[] ? (int[]) rows.get(row) : null;
    }

    private static int indexIn(int[] ids, int game) {
        for (int k = 0; k < ids.length; k++) if (ids[k] == game) return k;
        return -1;
    }

    /** The grid row before (dir -1) or after (dir 1) row, or -1: section names are skipped. */
    private int gridRowNear(int row, int dir) {
        for (int r = row + dir; r >= 0 && r < rows.size(); r += dir)
            if (rows.get(r) instanceof int[]) return r;
        return -1;
    }

    /** Left or right a cell (on to the next row's end or start at an edge); up or down a row, same column. */
    private void gridMove(int code) {
        int r = shownRow;
        int[] ids = cellsAt(r);
        int c = ids == null ? -1 : indexIn(ids, shown);
        if (c < 0) return;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (c + dir >= 0 && c + dir < ids.length) c += dir;
            else {
                r = gridRowNear(r, dir);
                if (r < 0) return;
                ids = cellsAt(r);
                c = dir < 0 ? ids.length - 1 : 0;
            }
        } else {
            r = gridRowNear(r, code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (r < 0) return;
            ids = cellsAt(r);
            c = Math.min(c, ids.length - 1);
        }
        keepVisible(r);
        select(ids[c], r);
    }

    /** Scrolls the list so grid row r shows, with its section's name above it when it starts a section. */
    private void keepVisible(int r) {
        int top = headerAbove(r) ? r - 1 : r;
        if (top < list.getFirstVisiblePosition()) list.setSelectionFromTop(r, headerAbove(r) ? Ui.dp(this, 40) : 0);
        else if (r >= list.getLastVisiblePosition()) list.smoothScrollToPosition(r);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        int pos = shown;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (pos >= 0) { play(pos); return true; }
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (pos >= 0) { toggleHidden(pos); return true; }
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (pos >= 0) { toggleFavourite(pos); return true; }
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                toggleMute();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                page(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                page(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L2:
                jumpLetter(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                jumpLetter(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: case KeyEvent.KEYCODE_SEARCH:
                search();
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_BUTTON_MODE:
                openOptions();
                return true;
        }
        return super.onKeyDown(code, ev);
    }

    /* ---------------------------------------------------------------- */
    /* Rows                                                               */
    /* ---------------------------------------------------------------- */

    /** Grid mode: one row of up to gridCols cells, each a card with the cart picture and its title under it. */
    private View gridRow(int row, int[] ids) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT));
        int cell = listWidth() / gridCols, gap = Ui.dp(this, 8), pad = Ui.dp(this, 6);
        for (int g : ids) {
            Files.Entry e = games.get(g);
            boolean on = row == shownRow && g == shown;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, pad, pad, pad);
            card.setBackground(Ui.rounded(this, on ? Ui.PRIMARY_CONTAINER : Ui.SURFACE, 16)   /* not Ui.card: the list row's own highlight would light every cell */);
            card.setAlpha(hidden.contains(e.path) ? 0.6f : 1f);
            Cart c = cart(e);
            ArtView pic = new ArtView(this, 12);
            pic.fill = true;
            pic.set(c == null ? null : c.picture);
            int side = Math.max(1, cell - gap - 2 * pad);   /* the card's inside width */
            card.addView(pic, new LinearLayout.LayoutParams(side, side));
            TextView title = Ui.text(this, (favs.contains(e.path) ? "★ " : "") + displayTitle(e), 13,
                    on ? Ui.ON_PRIMARY_CONTAINER : Ui.ON_SURFACE, Ui.REGULAR);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), 0);
            card.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            card.setFocusable(false);
            /* a tap selects; a tap on the selected cell plays, as in the list */
            card.setOnClickListener(v -> { if (row == shownRow && g == shown) play(g); else select(g, row); });
            card.setOnLongClickListener(v -> { gameMenu(g, row); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell - gap, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap / 2, 0, gap / 2, gap);     /* cell wide in all, margins included */
            line.addView(card, lp);
        }
        return line;
    }

    private final class GameAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int row) { return rows.get(row); }
        @Override public long getItemId(int row) { return row; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int row) { return isHeader(row) ? 1 : cellsAt(row) != null ? 2 : 0; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int row) { return !isHeader(row); }

        @Override
        public View getView(int row, View convert, ViewGroup parent) {
            if (cellsAt(row) != null) return gridRow(row, cellsAt(row));
            int pos = gameAt(row);
            if (pos < 0) {
                /* a section's name: a fresh view every time, never one recycled from a game's row
                   (a recycled one could come back empty after scrolling) */
                Header h = new Header(MainActivity.this, (String) rows.get(row), row == 0);
                h.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT, 1));
                return h;
            }
            Row r = convert instanceof Row ? (Row) convert : new Row(MainActivity.this);
            Files.Entry e = games.get(pos);
            Cart c = cart(e);
            r.thumb.set(c == null ? null : c.picture);
            r.title.setText(displayTitle(e));
            String d = details(e);
            r.sub.setText(d);
            r.sub.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
            r.setAlpha(hidden.contains(e.path) ? 0.6f : 1f);
            r.star.setVisibility(favs.contains(e.path) ? View.VISIBLE : View.GONE);
            return r;
        }
    }

    /** A section's name in the list: ★ Favourites, Last played, CHGame originals, Ports. */
    private static final class Header extends TextView {
        Header(Context c, String name, boolean first) {
            super(c);
            setText(name);
            setTextSize(14);
            setTextColor(Ui.PRIMARY);
            setTypeface(Ui.MEDIUM);
            setSingleLine(true);
            setPadding(Ui.dp(c, 12), Ui.dp(c, first ? 2 : 14), 0, Ui.dp(c, 2));
        }
    }

    /** One game: thumbnail, title, details; tinted while it is the selected one. */
    private static final class Row extends LinearLayout {
        final ArtView thumb;
        final TextView title, sub, star;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
            setBackground(Ui.card(c, Ui.SURFACE, 16));
            thumb = new ArtView(c, 8);
            addView(thumb, new LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(VERTICAL);
            text.setPadding(Ui.dp(c, 14), 0, 0, 0);
            text.setDuplicateParentStateEnabled(true);
            ColorStateList strong = new ColorStateList(
                    new int[][] { { android.R.attr.state_activated }, { android.R.attr.state_selected }, {} },
                    new int[] { Ui.ON_PRIMARY_CONTAINER, Ui.ON_PRIMARY_CONTAINER, Ui.ON_SURFACE });
            title = Ui.text(c, "", 17, Ui.ON_SURFACE, Ui.MEDIUM);
            title.setTextColor(strong);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setDuplicateParentStateEnabled(true);
            text.addView(title);
            sub = Ui.text(c, "", 13, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(sub);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            star = Ui.text(c, "\u2605", 18, Ui.PRIMARY, Ui.REGULAR);
            star.setPadding(Ui.dp(c, 8), 0, 0, 0);
            addView(star);
        }
    }

    /** A 128x128 picture with sharp pixels and rounded corners: the largest whole-number scale
     *  that fits, or with fill as big as fits. */
    static final class ArtView extends View {
        boolean fill;
        private Bitmap bmp;
        private final Paint paint = new Paint();
        private final Rect src = new Rect(0, 0, 128, 128), dst = new Rect();
        private final float radius;

        ArtView(Context c, float radiusDp) {
            super(c);
            paint.setFilterBitmap(false);
            radius = Ui.dp(c, radiusDp);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(dst.isEmpty() ? new Rect(0, 0, v.getWidth(), v.getHeight()) : dst, radius);
                }
            });
            setClipToOutline(true);
        }

        void set(Bitmap b) {
            if (b == bmp) return;
            bmp = b;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            int side = Math.min(w, h);
            int s = fill || side < 128 ? side : side / 128 * 128;     /* thumbnails smaller than 128 just scale */
            int x = (w - s) / 2, y = (h - s) / 2;
            dst.set(x, y, x + s, y + s);
            invalidateOutline();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bmp != null) {
                c.drawBitmap(bmp, src, dst, paint);
            } else {
                paint.setColor(Ui.SURFACE_HIGH);
                c.drawRect(dst, paint);
            }
        }
    }
}
