package com.jadflat.chgame;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The touch control editor: the pad over a sample game frame, laid out as it
 * is in a game (the whole screen in landscape; under the picture in portrait).
 * Drag a button group to move it, pinch or use + and - to resize it. The layout
 * is kept for the orientation the phone is in now; turn it to edit the other.
 */
public class TouchEditorActivity extends Activity {
    private SharedPreferences prefs;
    private TouchControls pad;
    private TextView which;
    private boolean fill;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        fill = Options.fill(prefs);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        View sample = new Sample(this);
        frame.addView(sample);
        pad = new TouchControls(this);
        pad.setEditing(true, this::showSelected);
        frame.addView(pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(Ui.rounded(this, 0xE6111318, 20));
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));

        TextView hint = Ui.text(this, "Drag a button to move it. Pinch, or + and -, to resize.", 13,
                Ui.ON_SURFACE_VARIANT, Ui.REGULAR);
        hint.setGravity(Gravity.CENTER);
        bar.addView(hint);

        LinearLayout size = new LinearLayout(this);
        size.setGravity(Gravity.CENTER_VERTICAL);
        size.addView(button("−", () -> pad.resizeSelected(1 / 1.1f)));
        which = Ui.text(this, "", 16, Ui.PRIMARY, Ui.MEDIUM);
        which.setGravity(Gravity.CENTER);
        size.addView(which, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        size.addView(button("+", () -> pad.resizeSelected(1.1f)));
        bar.addView(size);

        int opacity = Options.touchOpacity(prefs);
        TextView ol = Ui.text(this, "Opacity " + opacity + "%", 14, Ui.ON_SURFACE, Ui.REGULAR);
        ol.setPadding(0, Ui.dp(this, 8), 0, 0);
        bar.addView(ol);
        SeekBar seek = new SeekBar(this);
        seek.setMax(80);                                    /* 20..100% */
        seek.setProgress(opacity - 20);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                ol.setText("Opacity " + (p + 20) + "%");
                prefs.edit().putInt("touchOpacity", p + 20).apply();
                pad.setAlpha((p + 20) / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        bar.addView(seek);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(button("Reset", () -> pad.resetLayout()));
        row.addView(button("Done", this::finish));
        bar.addView(row);

        int w = Ui.dp(this, 300);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        frame.addView(bar, lp);

        /* where the game puts things: the pad under the picture in portrait, the bar over the picture's middle */
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol2, ot, or, ob) -> {
            int fw = r - l, fh = b - t;
            boolean portrait = fh > fw;
            int side = TouchControls.pictureSide(portrait, fill, fw, fh);
            int top = portrait ? side : 0;
            FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) pad.getLayoutParams();
            if (old == null || old.topMargin != top || old.height != fh - top) {
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fh - top);
                p.topMargin = top;
                pad.setLayoutParams(p);
            }
            int py = portrait ? 0 : (fh - side) / 2;
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            int want = py + Math.max(0, (side - bar.getHeight()) / 2);
            if (bp.topMargin != want) {
                bp.topMargin = want;
                bar.setLayoutParams(bp);
            }
        });
        setContentView(frame);
        showSelected();
    }

    private TextView button(String label, Runnable r) {
        TextView t = Ui.text(this, label, 18, Ui.ON_SURFACE, Ui.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.card(this, Ui.SURFACE_HIGH, 20));
        t.setFocusable(true);
        t.setClickable(true);
        t.setMinWidth(Ui.dp(this, 64));
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void showSelected() {
        which.setText(pad.selectedName());
    }

    /** A sample game frame (128x128, drawn here), scaled as a game's picture is. */
    private final class Sample extends View {
        private final Bitmap bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        private final Paint paint = new Paint();

        Sample(Activity a) {
            super(a);
            paint.setFilterBitmap(false);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint();
            for (int y = 0; y < 128; y += 2) {           /* a dusk sky */
                p.setColor(Color.rgb(20 + y / 3, 24 + y / 3, 70 + y / 2));
                c.drawRect(0, y, 128, y + 2, p);
            }
            p.setColor(0xFF2E8B57);
            c.drawRect(0, 96, 128, 128, p);
            p.setColor(0xFF1E5E3A);
            for (int x = 0; x < 128; x += 16) c.drawRect(x, 96, x + 8, 100, p);
            p.setColor(0xFF8B5A2B);
            c.drawRect(24, 80, 40, 96, p);
            c.drawRect(88, 72, 112, 96, p);
            p.setColor(0xFFFFD25A);
            c.drawRect(60, 84, 68, 96, p);                 /* a little player */
            c.drawRect(62, 80, 66, 84, p);
            p.setColor(Color.WHITE);
            for (int i = 0; i < 12; i++) c.drawRect((i * 37) % 128, (i * 23) % 60, (i * 37) % 128 + 1, (i * 23) % 60 + 1, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            boolean portrait = h > w;
            int side = TouchControls.pictureSide(portrait, fill, w, h);
            int x = (w - side) / 2, y = portrait ? 0 : (h - side) / 2;
            c.drawBitmap(bmp, new Rect(0, 0, 128, 128), new Rect(x, y, x + side, y + side), paint);
        }
    }
}
