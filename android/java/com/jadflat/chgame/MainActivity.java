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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
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
    static final String VERSION = "0.62";
    static final String PREFS = "chgame";

    private SharedPreferences prefs;
    private TextView count, version, artTitle, artInfo, artPlayed, artPort, dupBanner;
    private Preview preview;
    private TextView mute;
    private View[] headIcons;                                   /* the header buttons the pad can reach, left to right */
    private ArtView art;
    private ListView list;
    private View listPane, emptyPane;
    private final GameAdapter adapter = new GameAdapter();
    private final List<Files.Entry> games = new ArrayList<>();         /* in list order */
    private final List<Object> rows = new ArrayList<>();               /* a section's name (String) or a game's index (Integer) */
    private final List<Integer> depths = new ArrayList<>();            /* parallel to rows: how far a row is indented */
    private int groupRow = -1;                                  /* the selected group row, or -1 when a game is selected */
    private long toggledAt;                                     /* the key and the click can both arrive: one toggle */
    private Set<String> hidden = new HashSet<>(), favs = new HashSet<>();
    private int shownRow = -1;                                  /* a game can be on two rows (Favourites, Last played) */
    private final Map<String, Integer> sectionCounts = new HashMap<>();   /* games in each section, shown in its header */
    private final Map<Uri, String> titles = new HashMap<>();   /* the title in each header */
    private final Map<Uri, Cart> carts = new HashMap<>();      /* picture, author, version */
    private final Set<Uri> loading = new HashSet<>();
    private final Map<String, Bitmap> snaps = new HashMap<>();   /* ES-DE title screenshots for carts without a picture */
    private final Set<String> snapTried = new HashSet<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private String treeSeen;                                    /* the folder the caches are for */
    private int generation;                                     /* drops a listing a newer one replaced */
    private int shown = -1;
    private boolean gridMode;                                   /* the list as cells, not rows (Options: Layout) */
    private int gridCols = 2;                                   /* cells in a grid row */
    private LinearLayout root, left, right, info, hintRow;                     /* the panes: list (left), preview (right) */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);

        /* left: header and the list (or, with no folder yet, the first step) */
        left = new LinearLayout(this);
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
        version = Ui.text(this, "v" + VERSION, 14, Ui.ON_SURFACE_VARIANT, Ui.MEDIUM);
        version.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), 0, 0);
        version.setSingleLine(true);
        titleLine.addView(version);
        heading.addView(titleLine);
        count = Ui.text(this, "", 14, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        heading.addView(count);
        head.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView gear = Ui.text(this, "⚙", 24, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.card(this, Color.TRANSPARENT, 24));
        gear.setContentDescription("Options");
        gear.setFocusable(true);
        gear.setOnClickListener(v -> openOptions());
        /* search the titles (R3 or SEARCH on a pad) */
        TextView find = Ui.text(this, "🔍", 20, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        find.setGravity(Gravity.CENTER);
        find.setBackground(Ui.card(this, Color.TRANSPARENT, 24));
        find.setContentDescription("Search the games");
        find.setFocusable(true);
        find.setOnClickListener(v -> search());
        head.addView(find, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        /* the preview's sound: on or muted (Options' "Video and sound" / "Video only") */
        mute = Ui.text(this, "", 22, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        mute.setGravity(Gravity.CENTER);
        mute.setBackground(Ui.card(this, Color.TRANSPARENT, 24));
        mute.setFocusable(true);
        mute.setOnClickListener(v -> toggleMute());
        head.addView(mute, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        head.addView(gear, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        headIcons = new View[] { find, mute, gear };
        head.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 12));
        left.addView(head);
        /* two .CHG files that are the same game: a row above the list leads to the tidy-up page */
        dupBanner = Ui.text(this, "", 14, Ui.PRIMARY, Ui.MEDIUM);
        dupBanner.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        dupBanner.setBackground(Ui.card(this, Color.TRANSPARENT, 12));
        dupBanner.setFocusable(true);
        dupBanner.setVisibility(View.GONE);
        dupBanner.setOnClickListener(v -> startActivity(new Intent(this, DuplicatesActivity.class)));
        left.addView(dupBanner);

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(Ui.dp(this, 4));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((p, v, row, id) -> {
            if (isGroup(row)) { toggleGroup(row); return; }
            int g = gameAt(row);
            if (g < 0) return;
            if (row == shownRow) play(g);
            else select(g, row);
        });
        list.setOnItemLongClickListener((p, v, row, id) -> {
            int g = gameAt(row);
            if (g >= 0) gameSettings(g, row);
            return g >= 0;
        });
        /* portrait: the preview strip is a one-line bar while the list scrolls, whole again 600 ms after */
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                if (!portrait()) return;
                list.removeCallbacks(expandLater);
                if (state != SCROLL_STATE_IDLE) strip(true);
                else list.postDelayed(expandLater, 600);
            }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) { }
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int row, long id) {
                int g = gameAt(row);
                if (g >= 0) select(g, row);
                else if (isGroup(row)) {
                    groupRow = row;
                    list.setItemChecked(row, true);
                    groupCard();
                }
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

        /* right: the selected game, large */
        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setBackground(Ui.rounded(this, Ui.SURFACE, 28));
        right.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 14));
        art = new ArtView(this, 12);
        art.fade = true;
        art.fill = true;            /* as big as the panel allows, not a whole-number scale */
        right.addView(art, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        art.setOnClickListener(v -> { if (shown >= 0) play(shown); });     /* a tap on the picture starts the selected game */
        info = new LinearLayout(this);          /* title, author line, port note, hints: below the picture, or beside it in portrait */
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        artTitle = Ui.text(this, "", 17, Ui.ON_SURFACE, Ui.pixel(this));
        artTitle.setGravity(Gravity.CENTER);
        artTitle.setMaxLines(2);
        artTitle.setEllipsize(TextUtils.TruncateAt.END);
        artTitle.setLineSpacing(Ui.dp(this, 6), 1f);
        artTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        info.addView(artTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        artInfo = Ui.text(this, "", 15, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        artInfo.setGravity(Gravity.CENTER);
        info.addView(artInfo);
        artPlayed = Ui.text(this, "", 15, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        artPlayed.setGravity(Gravity.CENTER);
        artPlayed.setSingleLine(true);
        artPlayed.setEllipsize(TextUtils.TruncateAt.END);
        artPlayed.setVisibility(View.GONE);
        info.addView(artPlayed);
        artPort = Ui.text(this, "", 13, Ui.ON_PRIMARY_CONTAINER, Ui.MEDIUM);
        artPort.setBackground(Ui.rounded(this, Ui.PRIMARY_CONTAINER, 8));
        artPort.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.setMargins(0, Ui.dp(this, 8), 0, 0);
        info.addView(artPort, pp);
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
        info.addView(hints);
        hintRow = hints;
        right.addView(info, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        applyOrientation();

        setContentView(root);
        Ui.edgeToEdge(this, root);
        preview = new Preview(this, b -> art.setNow(b));
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation();
        if (!games.isEmpty()) {                 /* portrait has no "Last played" section */
            buildRows();
            adapter.notifyDataSetChanged();
            if (shown >= 0) goTo(shown);
        }
    }

    private LinearLayout topRow;                               /* portrait: the picture and the title */
    private int stripSide;                                      /* portrait: the strip's picture at full size */
    private boolean stripSmall;
    private android.animation.ValueAnimator stripAnim;
    private final Runnable expandLater = () -> strip(false);

    /** Portrait: shrinks the strip to a one-line bar (small = true) or expands it, animated. */
    private void strip(boolean small) {
        if (small == stripSmall || stripSide == 0 || !portrait()) return;
        stripSmall = small;
        if (stripAnim != null) stripAnim.cancel();
        if (small) {
            info.setVisibility(View.GONE);
        } else {
            info.setVisibility(View.VISIBLE);
        }
        stripColumn(!small);
        int from = art.getLayoutParams().height, to = small ? Ui.dp(this, 40) : stripSide;
        stripAnim = android.animation.ValueAnimator.ofInt(from, to);
        stripAnim.setDuration(220);
        stripAnim.addUpdateListener(a -> {
            int s = (Integer) a.getAnimatedValue();
            art.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        });
        stripAnim.start();
    }

    /** Portrait strip: expanded is a centred column (picture, title); collapsed is the one-line bar (picture left, title right). */
    private void stripColumn(boolean column) {
        topRow.setOrientation(column ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        topRow.setGravity(column ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = column
                ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (column) tp.setMargins(0, Ui.dp(this, 8), 0, 0); else tp.setMargins(Ui.dp(this, 14), 0, 0, 0);
        artTitle.setLayoutParams(tp);
        artTitle.setGravity(column ? Gravity.CENTER : Gravity.START);
        artTitle.setMaxLines(column ? 2 : 3);
    }

    private boolean portrait() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    /** Landscape: the list left, the preview right. Portrait: the preview on top (square, whole-number scale), the list below. */
    private void applyOrientation() {
        boolean port = portrait();
        if (stripAnim != null) stripAnim.cancel();
        list.removeCallbacks(expandLater);
        info.setVisibility(View.VISIBLE);
        artPlayed.setVisibility(port ? View.VISIBLE : View.GONE);
        stripSmall = false;
        stripSide = 0;
        root.setOrientation(port ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.removeAllViews();
        if (right.getParent() != null) ((android.view.ViewGroup) right.getParent()).removeView(right);
        art.fill = !port;
        /* a narrow screen has no room beside the name: wrapped, the version stood one letter a line */
        boolean shortScreen = getResources().getConfiguration().screenHeightDp < 420;   /* a phone on its side (the Nova is 480) */
        version.setVisibility(port || shortScreen ? View.GONE : View.VISIBLE);
        hintRow.setVisibility(port || shortScreen ? View.GONE : View.VISIBLE);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        if (topRow == null) { topRow = new LinearLayout(this); topRow.setGravity(Gravity.CENTER_VERTICAL); }
        topRow.removeAllViews();
        right.removeAllViews();
        if (artTitle.getParent() != null) ((android.view.ViewGroup) artTitle.getParent()).removeView(artTitle);
        if (port) {
            /* header on top, then a strip: picture and title side by side, the info text under them */
            int pad = Ui.dp(this, 10);
            right.setPadding(pad, pad, pad, pad);
            int hpx = getResources().getDisplayMetrics().heightPixels;
            int wpx = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 40) - 2 * pad;
            /* whole-number scale of 128: as wide as fits, at most 30% of the height */
            int side = Math.max(1, Math.min(wpx / 128, (int) (hpx * 0.30f) / 128)) * 128;
            art.setLayoutParams(new LinearLayout.LayoutParams(side, side));
            stripSide = side;
            topRow.addView(art);
            artTitle.setPadding(0, 0, 0, 0);
            topRow.addView(artTitle);
            stripColumn(true);
            right.addView(topRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            info.setGravity(Gravity.CENTER_HORIZONTAL);
            artInfo.setSingleLine(true);
            artInfo.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pl.setMargins(0, Ui.dp(this, 8), 0, 0);
            artPlayed.setLayoutParams(pl);
            artInfo.setTextSize(14);
            artPlayed.setTextSize(14);
            artTitle.setTextSize(11);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.setMargins(0, Ui.dp(this, 8), 0, 0);
            right.addView(info, ip);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, Ui.dp(this, 12));
            left.addView(right, 1, rp);
            root.addView(left, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));
        } else {
            artInfo.setSingleLine(false);
            artInfo.setTextSize(shortScreen ? 13 : 15);
            artTitle.setTextSize(shortScreen ? 13 : 17);
            /* a short screen: one line each, so the picture keeps the height */
            artPlayed.setTextSize(shortScreen ? 13 : 15);
            artPlayed.setSingleLine(shortScreen);
            artPlayed.setEllipsize(shortScreen ? TextUtils.TruncateAt.END : null);
            right.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 14));
            art.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            right.addView(art);
            artTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
            artTitle.setMaxLines(shortScreen ? 1 : 2);
            artTitle.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(artTitle, 0, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            info.setGravity(Gravity.CENTER_HORIZONTAL);
            right.addView(info, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            artTitle.setGravity(Gravity.CENTER);
            artInfo.setGravity(Gravity.CENTER);
            root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            rp.setMargins(Ui.dp(this, 20), 0, 0, 0);
            root.addView(right, rp);
        }
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
        TextView g = Ui.text(this, "Get games", 16, Ui.PRIMARY, Ui.MEDIUM);
        g.setGravity(Gravity.CENTER);
        g.setPadding(Ui.dp(this, 28), Ui.dp(this, 12), Ui.dp(this, 28), Ui.dp(this, 12));
        g.setBackground(Ui.card(this, android.graphics.Color.TRANSPARENT, 24));
        g.setFocusable(true);
        g.setClickable(true);
        g.setOnClickListener(v -> startActivity(new Intent(this, CatalogueActivity.class)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gp.topMargin = Ui.dp(this, 12);
        e.addView(g, gp);
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
        snaps.clear();                  /* the media maker may have made new screenshots */
        snapTried.clear();
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
        if (shown >= 0 && shown < games.size() && shownRow >= 0) {
            SharedPreferences.Editor ed = prefs.edit().putString("lastPath", games.get(shown).path);
            View v = list.getChildAt(shownRow - list.getFirstVisiblePosition());
            if (v != null) ed.putInt("selTop:" + gridMode + getResources().getConfiguration().orientation, v.getTop());
            ed.apply();
        }
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
            dupBanner.setVisibility(View.GONE);
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
            Map<String, Integer> same = new HashMap<>();
            int dups = 0;
            for (Cart c : readCarts.values()) {
                String k = Cart.key(new String[] { c.title, c.author });
                if (c.title.isEmpty()) continue;
                Integer n = same.get(k);
                same.put(k, n == null ? 1 : n + 1);
                if (n != null && n == 1) dups++;
            }
            final int dupSets = dups;
            runOnUiThread(() -> {
                if (gen != generation || isFinishing()) return;
                dupBanner.setText(dupSets + (dupSets == 1 ? " duplicate game" : " duplicate games") + ": tap to tidy up");
                dupBanner.setVisibility(dupSets > 0 ? View.VISIBLE : View.GONE);
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
            if (!Options.boardCards(prefs) && isBoardCard(e)) continue;
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
        groupRow = -1;
        if (games.isEmpty()) {
            showSelected();
            return;
        }
        list.requestFocus();
        int row = rowOf(pos);
        if (row < 0) {              /* the game is inside a closed group: the first game on show, else the first group */
            for (int r = 0; r < rows.size() && row < 0; r++) {
                if (gameAt(r) >= 0) { pos = gameAt(r); row = r; }
                else if (cellsAt(r) != null) { pos = cellsAt(r)[0]; row = r; }
            }
        }
        if (row < 0) {
            for (int r = 0; r < rows.size(); r++) if (isGroup(r)) { groupRow = r; break; }
            showSelected();
            if (groupRow >= 0) {
                if (!gridMode) list.setItemChecked(groupRow, true);
                list.setSelection(groupRow);
            }
            return;
        }
        int top = headerAbove(row) ? Ui.dp(this, 40) : 0;
        String mk = "selTop:" + gridMode + getResources().getConfiguration().orientation;
        if (prefs.contains(mk)) { top = prefs.getInt(mk, top); prefs.edit().remove(mk).apply(); }   /* reopen where it was */
        list.setSelectionFromTop(row, top);
        previewing = false;
        select(pos, row);
    }

    private static final int RECENT = 1;     /* "Last played": the one game */

    /** A collapsible row in the list: a developer's games, or the board and card games. */
    private static final class Group {
        final String key, label;
        final boolean open;
        final int first;                                         /* a game in it, for the preview's picture */
        Group(String key, String label, boolean open, int first) {
            this.key = key; this.label = label; this.open = open; this.first = first;
        }
    }

    /** The files (upper case) that make up "Board and card games": bateske's casino-style set. */
    private static final Set<String> BOARD_CARD = new HashSet<>(Arrays.asList(
        "CHCHESS.CHG",
        "CHCHESS.CHG",
        "CHBLACKJ.CHG",
        "CHBLACKJACK.CHG",
        "CHPOKER.CHG",
        "CHPOKER.CHG",
        "CHMAHJON.CHG",
        "CHMAHJONG.CHG",
        "CHROULET.CHG",
        "CHROULETTE.CHG",
        "CHCRAPS.CHG",
        "CHCRAPS.CHG",
        "CHBOARDW.CHG",
        "CHBOARDWALK.CHG",
        "CHBACKGA.CHG",
        "CHBACKGAMMON.CHG",
        "CHSOLITA.CHG",
        "CHSOLITAIRE.CHG",
        "CHCHECKE.CHG",
        "CHCHECKERS.CHG",
        "CHYACHT.CHG",
        "CHYACHT.CHG",
        "CHFOUR.CHG",
        "CHFOUR.CHG",
        "CHTICTAC.CHG",
        "CHTICTACTOE.CHG",
        "CHSNAKES.CHG",
        "CHSNAKES.CHG",
        "CHSLOTS.CHG",
        "CHSLOTS.CHG",
        "CHBINGO.CHG",
        "CHBINGO.CHG",
        "CHDOMINO.CHG",
        "CHDOMINOES.CHG",
        "CHWORDS.CHG",
        "CHWORDS.CHG",
        "CHCROSSW.CHG",
        "CHCROSSWORD.CHG",
        "CHWORDWH.CHG",
        "CHWORDWHEEL.CHG"));

    private static boolean groupOpen(SharedPreferences p, String key) { return p.getBoolean("grp:" + key, false); }

    private static boolean isBoardCard(Files.Entry e) {
        return BOARD_CARD.contains(fileName(e).toUpperCase(Locale.ROOT));
    }

    /** The developer a game's cart names; "Other" when it names none. */
    private String developer(Files.Entry e) {
        Cart c = carts.get(e.uri);
        String a = c == null ? "" : c.credit().trim();
        return a.isEmpty() ? "Other" : a;
    }

    /** The list's rows: each section's name, then its games. Grid mode puts a section's games in rows of gridCols. */
    private void buildRows() {
        gridCols = colsFor(listWidth());
        rows.clear();
        depths.clear();
        sectionCounts.clear();
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
        addSection("★ Favourites", fav, false);
        if (!portrait()) addSection("Last played", recent, false);     /* portrait: the strip shows it */
        addSection("CHGame originals", originals, true);
        addSection("Ports", ports, true);
    }

    private void addSection(String name, List<Integer> ids, boolean grouped) {
        if (ids.isEmpty()) return;
        sectionCounts.put(name, ids.size());
        rows.add(name);
        depths.add(0);
        if (!grouped) {
            addGames(ids, 0);
            return;
        }
        /* with "Group by developer": one collapsible group a developer (Jadflat first, then A-Z) */
        TreeMap<String, List<Integer>> devs = new TreeMap<>((a, b) -> {
            boolean ja = a.equalsIgnoreCase("Jadflat"), jb = b.equalsIgnoreCase("Jadflat");
            return ja != jb ? (ja ? -1 : 1) : a.compareToIgnoreCase(b);
        });
        boolean byDev = Options.groupByDeveloper(prefs);
        for (int id : ids) {
            String dev = byDev ? developer(games.get(id)) : "";
            List<Integer> l = devs.get(dev);
            if (l == null) devs.put(dev, l = new ArrayList<>());
            l.add(id);
        }
        for (Map.Entry<String, List<Integer>> d : devs.entrySet()) {
            int depth = 0;
            if (byDev) {
                String key = name + ":dev:" + d.getKey();
                boolean open = groupOpen(prefs, key);
                addGroup(new Group(key, d.getKey() + " (" + d.getValue().size() + ")", open, d.getValue().get(0)), 0);
                if (!open) continue;
                depth = 1;
            }
            List<Integer> board = new ArrayList<>(), rest = new ArrayList<>();
            for (int id : d.getValue()) (isBoardCard(games.get(id)) ? board : rest).add(id);
            if (!board.isEmpty()) {
                boolean open = groupOpen(prefs, "board");
                addGroup(new Group("board", "Board and card games (" + board.size() + ")", open, board.get(0)), depth);
                if (open) addGames(board, depth + 1);
            }
            addGames(rest, depth);
        }
    }

    private void addGroup(Group g, int depth) {
        rows.add(g);
        depths.add(depth);
    }

    /** Games as rows, or in grid mode as grid rows of gridCols cells. */
    private void addGames(List<Integer> ids, int depth) {
        if (!gridMode) {
            for (int id : ids) { rows.add(id); depths.add(depth); }
            return;
        }
        for (int i = 0; i < ids.size(); i += gridCols) {
            int n = Math.min(gridCols, ids.size() - i);
            int[] cells = new int[n];
            for (int k = 0; k < n; k++) cells[k] = ids.get(i + k);
            rows.add(cells);
            depths.add(depth);
        }
    }

    private boolean isGroup(int row) {
        return row >= 0 && row < rows.size() && rows.get(row) instanceof Group;
    }

    /** Opens or closes the group at row, the group staying selected where it is on the screen. */
    private void toggleGroup(int row) {
        if (!isGroup(row) || System.currentTimeMillis() - toggledAt < 300) return;
        toggledAt = System.currentTimeMillis();
        Group g = (Group) rows.get(row);
        prefs.edit().putBoolean("grp:" + g.key, !g.open).apply();
        View child = list.getChildAt(row - list.getFirstVisiblePosition());
        int top = child == null ? 0 : child.getTop();
        buildRows();
        int now = 0;
        for (int r = 0; r < rows.size(); r++)
            if (rows.get(r) instanceof Group && ((Group) rows.get(r)).key.equals(g.key)) now = r;
        groupRow = now;
        showSelected();
        shownRow = shown >= 0 ? rowOf(shown) : -1;
        adapter.notifyDataSetChanged();
        if (!gridMode) list.setItemChecked(now, true);
        list.setSelectionFromTop(now, top);
    }

    /** Grid mode's columns: about 200 dp a cell, at least two; portrait: about 240 dp, at least one. */
    private int colsFor(int px) {
        return portrait() ? Math.max(1, px / Ui.dp(this, 240)) : Math.max(2, px / Ui.dp(this, 200));
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
        groupRow = -1;
        /* remembered at once: a killed app (no onPause) still reopens here */
        if (moved) prefs.edit().putString("lastPath", games.get(pos).path).apply();
        if (!gridMode) list.setItemChecked(row, true);
        /* the first game of a section: keep its section's name in view above it (the list
           would otherwise scroll the game to the top and the name out of sight) */
        if (headerAbove(row) && row - 1 < list.getFirstVisiblePosition()) {
            final int r = row;
            list.post(() -> list.setSelectionFromTop(r, Ui.dp(this, 40)));
        }
        showSelected();
        if (moved) {
            Ui.sfx(this, false);
            info.setAlpha(0f);                  /* the panel's text fades in */
            info.animate().alpha(1f).setDuration(120).start();
        }
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
        if (row < 0) return;            /* inside a closed group */
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
        if (shown < 0 || shownRow < 0 || groupRow >= 0 || rows.isEmpty()) return;
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
        return -1;
    }

    private void startPreview() {
        if (shown < 0 || shown >= games.size()) return;
        previewing = true;
        String fn = fileName(games.get(shown));
        String mode = Options.previewMode(prefs, fn);
        if ("picture".equals(mode)) { preview.stop(); return; }
        preview.schedule(games.get(shown), fn, "video".equals(mode));
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

    /** The cart's picture; with none built in, the game's ES-DE title screenshot (loaded in the background). */
    private Bitmap pic(Files.Entry e, Cart c) {
        if (c == null || e == null) return null;
        if (c.picture != null) return c.picture;
        String base = Saves.base(fileName(e)), m = prefs.getString("mediaTree", null);
        if (m == null) return null;
        Bitmap b = snaps.get(base);
        if (b == null && snapTried.add(base)) {
            reader.execute(() -> {
                Bitmap got = SnapMaker.shot(getContentResolver(), Uri.parse(m), base);
                if (got == null) return;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    snaps.put(base, got);
                    adapter.notifyDataSetChanged();
                    if (shown >= 0 && shown < games.size() && fileName(games.get(shown)).equals(fileName(e))) showSelected();
                });
            });
        }
        return b;
    }

    /** A group row is selected: the preview shows a plain card with the group's name, not the game before it. */
    private void groupCard() {
        previewing = false;
        preview.stop();
        showSelected();
    }

    private void showSelected() {
        if (groupRow >= 0 && isGroup(groupRow)) {
            Group g = (Group) rows.get(groupRow);
            Cart gc = g.first >= 0 && g.first < games.size() ? cart(games.get(g.first)) : null;
            art.set(pic(gc == null ? null : games.get(g.first), gc));
            artTitle.setText(g.label);
            artInfo.setText(g.open ? "A closes the group" : "A opens the group");
            hintRow.setVisibility(View.GONE);
            artPort.setVisibility(View.GONE);
            if (artPlayed != null) artPlayed.setVisibility(View.GONE);
            return;
        }
        if (shown < 0 || shown >= games.size()) {
            boolean folder = tree() != null;
            art.set(null);
            artTitle.setText(folder && games.isEmpty() ? "No games here" : "");
            artInfo.setText(folder && games.isEmpty() ? "Put .CHG files in the games folder" : "");
            artPort.setVisibility(View.GONE);
            return;
        }
        hintRow.setVisibility(portrait() || getResources().getConfiguration().screenHeightDp < 420 ? View.GONE : View.VISIBLE);
        Files.Entry e = games.get(shown);
        Cart c = cart(e);
        art.set(pic(e, c));
        artTitle.setText(displayTitle(e));
        StringBuilder d = new StringBuilder();
        if (c != null) {
            if (!c.credit().isEmpty()) d.append("by ").append(c.credit());
            if (!c.version.isEmpty()) d.append(d.length() > 0 ? " · " : "").append("v").append(c.version);
            if (c.picture == null) d.append(d.length() > 0 ? " · " : "").append("No cartridge picture");
        }
        if (hidden.contains(e.path)) d.append(d.length() > 0 ? " · " : "").append("Hidden");
        long time = Stats.time(this, fileName(e)), last = Stats.last(this, fileName(e));
        StringBuilder pl = new StringBuilder();
        if (last > 0) pl.append(time > 0 ? "Played " + Stats.duration(time) + " · " : "").append("last ").append(Stats.when(last));
        if (portrait()) { artPlayed.setText(pl); artPlayed.setVisibility(pl.length() > 0 ? View.VISIBLE : View.GONE); }
        else if (pl.length() > 0) d.append("\n").append(pl);
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
        Ui.sfx(this, true);
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

    /** A long press on a touch screen: the game's settings page. */
    private void gameSettings(int pos, int row) {
        select(pos, row);
        Files.Entry e = games.get(pos);
        startActivity(new Intent(this, GameSettingsActivity.class)
                .putExtra("file", fileName(e)).putExtra("title", displayTitle(e)));
    }

    /** What can be done with this game (the menu: long press is now the settings page). */
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
        if (headFocused() >= 0 && headKey(ev)) return true;
        if ((code == KeyEvent.KEYCODE_DPAD_UP) && ev.getAction() == KeyEvent.ACTION_DOWN && atTop()) {
            focusHead(1);
            return true;
        }
        boolean dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (gridMode && (shown >= 0 || groupRow >= 0) && dpad) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) gridMove(code);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** Index of the focused header icon, or -1. */
    private int headFocused() {
        if (headIcons == null) return -1;
        for (int i = 0; i < headIcons.length; i++) if (headIcons[i].hasFocus()) return i;
        return -1;
    }

    /** Focuses a visible header icon, trying from index 0 (dir 1) or the end (dir -1). */
    private void focusHead(int dir) {
        for (int i = dir > 0 ? 0 : headIcons.length - 1; i >= 0 && i < headIcons.length; i += dir)
            if (headIcons[i].getVisibility() == View.VISIBLE) { headIcons[i].requestFocus(); return; }
    }

    /** True when UP from the shown game has nowhere further to go in the list. */
    private boolean atTop() {
        int cur = groupRow >= 0 ? groupRow : shownRow;
        if (cur < 0 || list.getVisibility() != View.VISIBLE) return false;
        for (int r = cur - 1; r >= 0; r--) if (gameAt(r) >= 0 || cellsAt(r) != null || isGroup(r)) return false;
        return true;
    }

    /** D-pad and A while a header icon has focus. */
    private boolean headKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        int i = headFocused();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (!down) return true;
                int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                for (int k = i + dir; k >= 0 && k < headIcons.length; k += dir)
                    if (headIcons[k].getVisibility() == View.VISIBLE) { headIcons[k].requestFocus(); break; }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) list.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER:
                if (down && ev.getRepeatCount() == 0) headIcons[i].performClick();
                return true;
        }
        return false;
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
            if (rows.get(r) instanceof int[] || rows.get(r) instanceof Group) return r;
        return -1;
    }

    /** Left or right a cell (on to the next row's end or start at an edge); up or down a row, same column. */
    private void gridMove(int code) {
        int r = groupRow >= 0 ? groupRow : shownRow;
        if (isGroup(r)) {                       /* a group row selected: up or down only */
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                int n = gridRowNear(r, code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (n < 0) return;
                keepVisible(n);
                if (isGroup(n)) { groupRow = n; adapter.notifyDataSetChanged(); groupCard(); }
                else select(cellsAt(n)[0], n);
            }
            return;
        }
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
            if (isGroup(r)) {
                keepVisible(r);
                groupRow = r;
                adapter.notifyDataSetChanged();
                groupCard();
                return;
            }
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

    private boolean aHeld;

    private static boolean isPlayKey(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_START
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent ev) {
        if (isPlayKey(code) && shown >= 0 && groupRow < 0) {
            aHeld = true;
            gameSettings(shown, shownRow);
            return true;
        }
        return super.onKeyLongPress(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (isPlayKey(code) && groupRow < 0 && shown >= 0) {
            if (!aHeld && ev.isTracking() && !ev.isCanceled()) play(shown);
            aHeld = false;
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        int pos = shown;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (groupRow >= 0) { toggleGroup(groupRow); return true; }
                if (pos >= 0) {
                    /* a game starts on release; held, it opens the game's settings instead */
                    if (ev.getRepeatCount() == 0) { ev.startTracking(); aHeld = false; }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (pos >= 0 && groupRow < 0) { toggleHidden(pos); return true; }
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (pos >= 0 && groupRow < 0) { toggleFavourite(pos); return true; }
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
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (pos >= 0 && groupRow < 0) { gameSettings(pos, shownRow); return true; }
                openOptions();
                return true;
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_BUTTON_MODE:
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
            pic.set(pic(e, c));
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
            card.setOnLongClickListener(v -> { gameSettings(g, row); return true; });
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
        @Override public int getViewTypeCount() { return 4; }
        @Override public int getItemViewType(int row) { return isHeader(row) ? 1 : cellsAt(row) != null ? 2 : isGroup(row) ? 3 : 0; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int row) { return !isHeader(row); }

        @Override
        public View getView(int row, View convert, ViewGroup parent) {
            if (cellsAt(row) != null) return gridRow(row, cellsAt(row));
            int pos = gameAt(row);
            if (isGroup(row)) {
                GroupRow gr = new GroupRow(MainActivity.this, (Group) rows.get(row), depths.get(row));
                gr.setActivated(row == groupRow);
                gr.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT));
                return gr;
            }
            if (pos < 0) {
                /* a section's name: a fresh view every time, never one recycled from a game's row
                   (a recycled one could come back empty after scrolling) */
                String hn = (String) rows.get(row);
                Integer hc = sectionCounts.get(hn);
                Header h = new Header(MainActivity.this, hc == null ? hn : hn + " · " + hc, row == 0);
                h.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT,
                        AbsListView.LayoutParams.WRAP_CONTENT, 1));
                return h;
            }
            Row r = convert instanceof Row ? (Row) convert : new Row(MainActivity.this);
            Files.Entry e = games.get(pos);
            int ind = depths.get(row);
            r.setPadding(Ui.dp(MainActivity.this, 8 + 20 * ind), Ui.dp(MainActivity.this, 8),
                    Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 8));
            Cart c = cart(e);
            r.thumb.set(pic(e, c));
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
    private static final class Header extends LinearLayout {
        Header(Context c, String name, boolean first) {
            super(c);
            setOrientation(VERTICAL);
            if (!first) {                       /* a hairline above every header but the first */
                View line = new View(c);
                line.setBackgroundColor(Ui.OUTLINE_VARIANT);
                LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 1));
                lp.setMargins(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), 0);
                addView(line, lp);
            }
            TextView t = new TextView(c);
            t.setText(name);
            t.setTextSize(14);
            t.setTextColor(Ui.PRIMARY);
            t.setTypeface(Ui.MEDIUM);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(c, 12), Ui.dp(c, first ? 2 : 8), 0, Ui.dp(c, 2));
            addView(t);
        }
    }

    /** A group's row: a triangle and its name; tinted while the pad is on it. */
    private static final class GroupRow extends LinearLayout {
        GroupRow(Context c, Group g, int depth) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(c, 16 + 20 * depth), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
            setBackground(Ui.card(c, Ui.SURFACE, 16));
            ColorStateList strong = new ColorStateList(
                    new int[][] { { android.R.attr.state_activated }, { android.R.attr.state_selected }, {} },
                    new int[] { Ui.ON_PRIMARY_CONTAINER, Ui.ON_PRIMARY_CONTAINER, Ui.PRIMARY });
            TextView arrow = Ui.text(c, g.open ? "\u25BE" : "\u25B8", 18, Ui.PRIMARY, Ui.MEDIUM);
            arrow.setTextColor(strong);
            arrow.setDuplicateParentStateEnabled(true);
            addView(arrow);
            TextView t = Ui.text(c, g.label, 17, Ui.PRIMARY, Ui.MEDIUM);
            t.setTextColor(strong);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(Ui.dp(c, 12), 0, 0, 0);
            t.setDuplicateParentStateEnabled(true);
            addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
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
        boolean fill, fade;
        private Bitmap bmp, old;
        private float t = 1f;
        private android.animation.ValueAnimator anim;
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
            if (fade && bmp != null && b != null && isShown()) {      /* a new selection: cross-fade, 120 ms */
                old = bmp;
                t = 0f;
                if (anim != null) anim.cancel();
                anim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(120);
                anim.addUpdateListener(a -> {
                    t = (Float) a.getAnimatedValue();
                    if (t >= 1f) old = null;
                    invalidate();
                });
                anim.start();
            }
            bmp = b;
            invalidate();
        }

        /** A preview frame: replaces the picture at once, with no fade. */
        void setNow(Bitmap b) {
            if (t < 1f && old != null) { if (anim != null) anim.cancel(); old = null; t = 1f; }
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
                if (old != null) {
                    paint.setAlpha(255);
                    c.drawBitmap(old, src, dst, paint);
                }
                paint.setAlpha(old != null ? (int) (255 * t) : 255);
                src.set(0, 0, bmp.getWidth(), bmp.getHeight());
                c.drawBitmap(bmp, src, dst, paint);
                paint.setAlpha(255);
            } else {
                paint.setColor(Ui.SURFACE_HIGH);
                c.drawRect(dst, paint);
            }
        }
    }
}
