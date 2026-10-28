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

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 24));
        scroll.addView(page);
        setContentView(scroll);
        Ui.edgeToEdge(this, scroll);
    }

    /** Makes the page; starts with header() and ends with focus(). */
    abstract void build();

    void header(String title) {
        page.removeAllViews();
        rows.clear();
        card = null;
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
        rows.get(Math.min(focusRow, rows.size() - 1)).requestFocus();
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
        r.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
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
            TextView chevron = Ui.text(this, "›", 24, Ui.OUTLINE, Ui.REGULAR);
            chevron.setPadding(Ui.dp(this, 12), 0, 0, 0);
            r.addView(chevron);
            r.setClickable(true);
            final int index = rows.size();
            r.setBackground(Ui.card(this, android.graphics.Color.TRANSPARENT, 0));
            r.setFocusable(true);
            rows.add(r);
            r.setOnClickListener(v -> {
                focusRow = index;
                action.run();
            });
        }
        if (action == null) {
            /* information rows take the pad's focus too, so the page can scroll to its last lines */
            r.setBackground(Ui.card(this, android.graphics.Color.TRANSPARENT, 0));
            r.setFocusable(true);
        }
        card.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
