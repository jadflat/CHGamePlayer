package com.jadflat.chgame;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A page laid out like Android's own Settings: a back arrow and a title, then
 * sections, each a rounded card of rows; a row has a title and a line saying
 * what it is now, and a › when it opens something. Options, Saves, Game
 * settings and Map buttons are pages. build() makes the page again after
 * anything changes, keeping the pad's place.
 */
abstract class Page extends Activity {
    LinearLayout page;
    private LinearLayout card;                      /* the section rows go into */
    private final List<View> rows = new ArrayList<>();  /* the rows that do something, in order */
    private int focusRow;
    int indent;                                     /* rows added from now on are indented this many steps */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 24));
        scroll.addView(page);
        setContentView(scroll);
        Ui.edgeToEdge(this, scroll);
        /* a pad's first key leaves touch mode: that is when the cursor can land on a row
           (before it, requestFocus is refused and the first press shows nothing) */
        scroll.getViewTreeObserver().addOnTouchModeChangeListener(inTouch -> {
            if (!inTouch && getCurrentFocus() == null) focus();
        });
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** Makes the page; starts with header() and ends with focus(). */
    abstract void build();

    void header(String title) {
        page.removeAllViews();
        rows.clear();
        card = null;
        indent = 0;
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, Ui.dp(this, 8));
        TextView back = Ui.text(this, "←", 22, Ui.ON_SURFACE, Ui.REGULAR);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.card(this, Ui.SURFACE, 24));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView t = Ui.text(this, title, 30, Ui.ON_SURFACE, Ui.REGULAR);
        t.setPadding(Ui.dp(this, 16), 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        page.addView(head);
    }

    /** The pad's place again after a rebuild. */
    void focus() {
        if (rows.isEmpty()) return;
        View r = rows.get(Math.min(focusRow, rows.size() - 1));
        r.requestFocus();
        /* once the window is up: take the focus again if it was lost, and redraw the highlight */
        r.post(() -> {
            if (!r.isFocused()) r.requestFocus();
            r.refreshDrawableState();
            r.invalidate();
        });
    }

    /** A new section: its name, then a card its rows go into. */
    void section(String name) {
        boolean named = name != null && !name.isEmpty();
        if (named) {
            TextView t = Ui.text(this, name, 14, Ui.PRIMARY, Ui.MEDIUM);
            t.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 8));
            page.addView(t);
        }
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(this, Ui.SURFACE, 20));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!named) lp.topMargin = Ui.dp(this, 16);  /* no heading: the gap comes from the card */
        page.addView(card, lp);
    }

    /** A row in the current section; with no action it is just information. */
    void row(String title, String summary, Runnable action) {
        row(title, summary, null, action);
    }

    /** A row with a word on its right (Installed, Get) in place of the ›. */
    void row(String title, String summary, String trailing, Runnable action) {
        if (card == null) section("");
        if (card.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(Ui.OUTLINE_VARIANT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
            card.addView(line, lp);
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 20 + 20 * indent), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(this, title, 17, action != null ? Ui.ON_SURFACE : Ui.ON_SURFACE_VARIANT, Ui.REGULAR));
        if (summary != null && !summary.isEmpty()) {
            TextView s = Ui.text(this, summary, 14, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
            s.setPadding(0, Ui.dp(this, 2), 0, 0);
            text.addView(s);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (action != null) {
            TextView chevron = trailing != null ? Ui.text(this, trailing, 15, Ui.PRIMARY, Ui.MEDIUM)
                    : Ui.text(this, "›", 24, Ui.OUTLINE, Ui.REGULAR);
            chevron.setPadding(Ui.dp(this, 12), 0, 0, 0);
            r.addView(chevron);
            r.setClickable(true);
            final int index = rows.size();
            r.setBackground(Ui.card(this, android.graphics.Color.TRANSPARENT, 0));
            r.setFocusable(true);
            rows.add(r);
            r.setOnFocusChangeListener((v, has) -> { if (has && v.isInTouchMode() == false) Ui.sfx(this, false); });
            r.setOnClickListener(v -> {
                focusRow = index;
                Ui.sfx(this, true);
                action.run();
            });
        }
        if (action == null && trailing != null) {
            TextView t = Ui.text(this, trailing, 15, Ui.ON_SURFACE_VARIANT, Ui.MEDIUM);
            t.setPadding(Ui.dp(this, 12), 0, 0, 0);
            r.addView(t);
        }
        if (action == null) {
            /* information rows take the pad's focus too, so the page can scroll to its last lines */
            r.setBackground(Ui.card(this, android.graphics.Color.TRANSPARENT, 0));
            r.setFocusable(true);
            rows.add(r);               /* counted, so a row that turns into information keeps the pad's place */
        }
        card.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /* a new window can come up without the pad's cursor on a row (nothing shows
       until the second press): put it on the first row once the window has focus */
    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has && getCurrentFocus() == null) focus();
    }

    /* B or BACK: back to the list */
    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_BUTTON_B) {
            finish();
            return true;
        }
        return super.onKeyDown(code, ev);
    }
}
