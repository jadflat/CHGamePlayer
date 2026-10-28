package com.jadflat.chgame;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A page laid out like Android's own Settings: a back arrow and a title,
 * sections, and rows with a title and a line saying what it is now. Options
 * and Saves are pages. build() makes the page again after anything changes,
 * keeping the pad's place.
 */
abstract class Page extends Activity {
    LinearLayout page;
    private int focusRow;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(page);
        setContentView(scroll);
        Ui.edgeToEdge(this, scroll);
    }

    /** Makes the page; starts with header() and ends with focus(). */
    abstract void build();

    void header(String title) {
        page.removeAllViews();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "←", 24, Ui.ON_SURFACE, Ui.REGULAR);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.card(this, android.graphics.Color.TRANSPARENT, 24));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView t = Ui.text(this, title, 28, Ui.ON_SURFACE, Ui.REGULAR);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        page.addView(head);
    }

    /** The pad's place again after a rebuild. */
    void focus() {
        View v = page.getChildAt(Math.min(focusRow, page.getChildCount() - 1));
        if (v != null && v.isFocusable()) v.requestFocus();
        else for (int i = 0; i < page.getChildCount(); i++)
            if (page.getChildAt(i).isFocusable()) { page.getChildAt(i).requestFocus(); break; }
    }

    void section(String name) {
        TextView t = Ui.text(this, name, 14, Ui.PRIMARY, Ui.MEDIUM);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 8));
        page.addView(t);
    }

    /** A row; with no action it is just information. */
    void row(String title, String summary, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        r.addView(Ui.text(this, title, 18, action != null ? Ui.ON_SURFACE : Ui.ON_SURFACE_VARIANT, Ui.REGULAR));
        if (summary != null) {
            TextView s = Ui.text(this, summary, 14, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
            s.setPadding(0, Ui.dp(this, 2), 0, 0);
            r.addView(s);
        }
        if (action != null) {
            r.setBackground(Ui.card(this, android.graphics.Color.TRANSPARENT, 16));
            r.setFocusable(true);
            r.setClickable(true);
            final int index = page.getChildCount();
            r.setOnClickListener(v -> {
                focusRow = index;
                action.run();
            });
        }
        page.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
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
