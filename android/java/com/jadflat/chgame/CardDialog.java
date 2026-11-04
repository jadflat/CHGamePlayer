package com.jadflat.chgame;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's dialog: a rounded card with the name in Press Start 2P and rows styled like the
 * list's and Options' rows (the same card the game shows for Continue). It takes the calls of
 * AlertDialog.Builder the app uses, so a dialog is changed by swapping the class. A pad moves one
 * highlight (up/down, left/right); A or Enter answers; BACK cancels.
 */
final class CardDialog extends Dialog {
    private final Context ctx;
    private final TextView head, message;
    private final LinearLayout content, buttons;
    private final ScrollView scroll;
    private final List<View> stops = new ArrayList<>();
    private final List<TextView> ticks = new ArrayList<>();
    private int sel;
    private int defaultButton;
    private boolean hasItems;
    private boolean customView;
    private OnKeyListener userKey;

    /** Not wrapping AlertDialog.Builder: just the calls the app makes. */
    static final class Builder {
        private final Context ctx;
        private CharSequence title, message;
        private View view;
        private String[] items;
        private int checked = -1;
        private boolean single;
        private boolean[] multi;
        private DialogInterface.OnClickListener itemClick, pos, neg, neu;
        private DialogInterface.OnMultiChoiceClickListener multiClick;
        private String posText, negText, neuText;
        private boolean cancelable = true;
        private int defaultButton = 0;       /* 0: the safe one (Cancel, else Negative) */
        private DialogInterface.OnCancelListener onCancel;
        private DialogInterface.OnDismissListener onDismiss;

        Builder(Context c) { ctx = c; }
        Builder setTitle(CharSequence t) { title = t; return this; }
        Builder setMessage(CharSequence m) { message = m; return this; }
        Builder setView(View v) { view = v; return this; }
        Builder setItems(CharSequence[] i, DialogInterface.OnClickListener l) {
            items = toStrings(i); itemClick = l; return this;
        }
        Builder setSingleChoiceItems(CharSequence[] i, int current, DialogInterface.OnClickListener l) {
            items = toStrings(i); checked = current; single = true; itemClick = l; return this;
        }
        Builder setMultiChoiceItems(CharSequence[] i, boolean[] on, DialogInterface.OnMultiChoiceClickListener l) {
            items = toStrings(i); multi = on; multiClick = l; return this;
        }
        Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) { posText = t.toString(); pos = l; return this; }
        Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) { negText = t.toString(); neg = l; return this; }
        Builder setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) { neuText = t.toString(); neu = l; return this; }
        Builder setDefaultButton(int which) { defaultButton = which; return this; }
        Builder setCancelable(boolean c) { cancelable = c; return this; }
        Builder setOnCancelListener(DialogInterface.OnCancelListener l) { onCancel = l; return this; }
        Builder setOnDismissListener(DialogInterface.OnDismissListener l) { onDismiss = l; return this; }

        private static String[] toStrings(CharSequence[] a) {
            String[] s = new String[a.length];
            for (int i = 0; i < s.length; i++) s[i] = a[i].toString();
            return s;
        }

        CardDialog create() {
            CardDialog d = new CardDialog(ctx);
            if (title != null) d.head.setText(title); else d.head.setVisibility(View.GONE);
            d.setMessage(message);
            if (view != null) d.setCustomView(view);
            if (items != null) d.addItems(items, checked, single, multi, itemClick, multiClick);
            if (posText != null) d.addButton(posText, DialogInterface.BUTTON_POSITIVE, pos);
            if (neuText != null) d.addButton(neuText, DialogInterface.BUTTON_NEUTRAL, neu);
            if (negText != null) d.addButton(negText, DialogInterface.BUTTON_NEGATIVE, neg);
            d.defaultButton = defaultButton;
            d.setCancelable(cancelable);
            d.setCanceledOnTouchOutside(cancelable);
            if (onCancel != null) d.setOnCancelListener(onCancel);
            if (onDismiss != null) d.setOnDismissListener(onDismiss);
            return d;
        }

        CardDialog show() {
            CardDialog d = create();
            d.show();
            return d;
        }
    }

    /** A scroll area that never grows past about half the screen, so title and buttons stay in view. */
    private static final class CappedScroll extends ScrollView {
        CappedScroll(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
        }
    }

    private CardDialog(Context c) {
        super(c);
        ctx = c;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 24);
        box.setPadding(pad, pad, pad, Ui.dp(c, 20));
        box.setBackground(Ui.rounded(c, Ui.SURFACE, 28));
        head = Ui.text(c, "", 16, Ui.ON_SURFACE, Ui.pixel(c));
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, 0, 0, Ui.dp(c, 14));
        box.addView(head);
        message = Ui.text(c, "", 15, Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        message.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), Ui.dp(c, 14));
        message.setVisibility(View.GONE);
        scroll = new CappedScroll(c);
        scroll.setDefaultFocusHighlightEnabled(false);     /* Android's 30% white focus veil */
        scroll.setFocusable(false);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(message);         /* the message scrolls with the rows, so the buttons stay in view */
        scroll.addView(content);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.VERTICAL);
        box.addView(buttons);
        setContentView(box);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int wide = Math.min(Ui.dp(c, 420), (int) (c.getResources().getDisplayMetrics().widthPixels * 0.9f));
            w.setLayout(wide, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** Replaces the text under the name (also after show()). */
    public void setMessage(CharSequence m) {
        message.setText(m == null ? "" : m);
        message.setVisibility(m == null || m.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void setCustomView(View v) {
        customView = true;
        content.addView(v);
        scroll.setVisibility(View.VISIBLE);
    }

    /** One of the buttons (BUTTON_POSITIVE, NEGATIVE, NEUTRAL) so a caller can change what it does. */
    public View getButton(int which) {
        for (View b : stops) if (b.getTag() instanceof Integer && (Integer) b.getTag() == which) return b;
        return null;
    }

    /** Ticks or clears item i of a multi-choice list. */
    public void setChecked(int i, boolean on) {
        if (i >= 0 && i < ticks.size()) ticks.get(i).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private LinearLayout row(String label, boolean withTick) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12));
        r.setBackground(Ui.card(ctx, Ui.SURFACE_HIGH, 16));
        r.setFocusable(customView);
        r.setClickable(true);
        TextView t = Ui.text(ctx, label, 17, Ui.ON_SURFACE, Ui.MEDIUM);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (withTick) {
            TextView k = Ui.text(ctx, "✓", 18, Ui.PRIMARY, Ui.MEDIUM);
            k.setPadding(Ui.dp(ctx, 8), 0, 0, 0);
            k.setVisibility(View.INVISIBLE);
            r.addView(k);
            ticks.add(k);
        }
        return r;
    }

    private void addItems(String[] items, int checked, boolean single, boolean[] multi,
                          OnClickListener click, OnMultiChoiceClickListener mclick) {
        scroll.setVisibility(View.VISIBLE);
        hasItems = true;
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean tick = multi != null || single;
            LinearLayout r = row(items[i], tick);
            if (multi != null) setChecked(i, multi[i]);
            else if (checked >= 0) setChecked(i, i == checked);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(ctx, 8);
            content.addView(r, lp);
            stops.add(r);
            r.setOnClickListener(v -> {
                if (multi != null) {
                    multi[idx] = !multi[idx];
                    setChecked(idx, multi[idx]);
                    if (mclick != null) mclick.onClick(this, idx, multi[idx]);
                    return;
                }
                if (single) for (int j = 0; j < ticks.size(); j++) setChecked(j, j == idx);
                if (click != null) click.onClick(this, idx);
                if (!single) dismiss();          /* a plain list closes; a choice list's caller closes it */
            });
        }
        if (checked > 0 && checked < stops.size()) sel = checked;
    }

    private void addButton(String text, int which, OnClickListener l) {
        TextView t = Ui.text(ctx, text, 16, which == BUTTON_POSITIVE ? Ui.PRIMARY : Ui.ON_SURFACE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12));
        t.setBackground(Ui.card(ctx, Ui.SURFACE_HIGH, 16));
        t.setFocusable(customView);
        t.setClickable(true);
        t.setTag(which);
        t.setOnClickListener(v -> {
            if (l != null) l.onClick(this, which);
            dismiss();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 8);
        buttons.addView(t, lp);
        stops.add(t);
    }

    @Override public void show() {
        super.show();
        /* with a field in the card the system's focus does the work; otherwise one highlight (selected) */
        if (!customView && !stops.isEmpty()) {
            if (sel >= stops.size()) sel = 0;
            if (!hasItems) sel = startButton();
            mark();
        }
    }

    @Override public void setOnKeyListener(OnKeyListener l) {
        userKey = l;
        super.setOnKeyListener(l);
    }

    /** A message-only card starts on its safe button, so a stray A or B never does the deed:
     *  the one asked for, else one called Cancel, else Negative, else the first. */
    private int startButton() {
        int found = -1;
        if (defaultButton != 0) found = indexOfButton(defaultButton);
        if (found < 0)
            for (int i = 0; i < stops.size(); i++) {
                View v = stops.get(i);
                if (v instanceof TextView && "Cancel".contentEquals(((TextView) v).getText())) { found = i; break; }
            }
        if (found < 0) found = indexOfButton(DialogInterface.BUTTON_NEGATIVE);
        return found < 0 ? 0 : found;
    }

    private int indexOfButton(int which) {
        for (int i = 0; i < stops.size(); i++)
            if (stops.get(i).getTag() instanceof Integer && (Integer) stops.get(i).getTag() == which) return i;
        return -1;
    }

    private void mark() {
        for (int i = 0; i < stops.size(); i++) stops.get(i).setSelected(i == sel);
        View v = stops.get(sel);
        v.post(() -> v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), v.getHeight()), false));
    }

    /** Scrolls the message/rows a page; false when already at that end. */
    private boolean page(boolean down) {
        if (scroll.getVisibility() != View.VISIBLE || !scroll.canScrollVertically(down ? 1 : -1)) return false;
        int h = Math.max(1, scroll.getHeight() * 4 / 5);
        scroll.smoothScrollBy(0, down ? h : -h);
        return true;
    }

    /** The next enabled stop from sel in that direction, or -1. */
    private int nextStop(int dir) {
        for (int i = sel + dir; i >= 0 && i < stops.size(); i += dir)
            if (stops.get(i).isEnabled()) return i;
        return -1;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent ev) {
        if (userKey != null || customView) return super.dispatchKeyEvent(ev);
        int code = ev.getKeyCode();
        boolean move = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean vertical = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP;
        /* either face button answers: which one is "A" differs from pad to pad */
        boolean act = code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (stops.isEmpty()) {
            /* nothing to highlight: Up/Down still read a long message */
            if (vertical && ev.getAction() == KeyEvent.ACTION_DOWN && page(code == KeyEvent.KEYCODE_DPAD_DOWN)) return true;
            return super.dispatchKeyEvent(ev);
        }
        if (!move && !act) return super.dispatchKeyEvent(ev);
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 || move && ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (move) {
                boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                int dir = down ? 1 : -1;
                int next = nextStop(dir);
                /* Up/Down page the message at the first/last stop, and always when there are
                 * only buttons (they also move with Left/Right), so a long text reads to the end */
                if (vertical && (next < 0 || !hasItems) && page(down)) return true;
                if (next >= 0) { sel = next; mark(); }
            } else {
                View v = stops.get(sel);
                if (v.isEnabled()) v.performClick();
            }
        }
        return true;
    }
}
