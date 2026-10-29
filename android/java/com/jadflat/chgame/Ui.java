package com.jadflat.chgame;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.TextView;

/**
 * The app's look: Material 3's dark scheme, built from CHGame's yellow, done by
 * hand (the app has no libraries). Surfaces step up in lightness; the accent is
 * for what is selected or focused, so a pad shows clearly where it is.
 */
final class Ui {
    static final int BG = 0xFF111318;           /* surface */
    static final int SURFACE = 0xFF1D2024;      /* surface container */
    static final int SURFACE_HIGH = 0xFF272A2F; /* surface container high */
    static final int PRIMARY = 0xFFFFD25A;
    static final int ON_PRIMARY = 0xFF3D2E00;
    static final int PRIMARY_CONTAINER = 0xFF574400;
    static final int ON_PRIMARY_CONTAINER = 0xFFFFDF99;
    static final int ON_SURFACE = 0xFFE3E2E6;
    static final int ON_SURFACE_VARIANT = 0xFFC5C6D0;
    static final int OUTLINE = 0xFF8F9099;
    static final int OUTLINE_VARIANT = 0xFF44474E;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private static Typeface pixel;

    /** The system sans-serif at weight 800, as chgame.website sets "CHGame" (bold before Android 9). */
    static Typeface heavy() {
        return Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.DEFAULT, 800, false)
                : Typeface.create("sans-serif", Typeface.BOLD);
    }

    /** Press Start 2P (SIL Open Font License, assets/fonts/OFL.txt): the game's name, big. */
    static Typeface pixel(Context c) {
        if (pixel == null) {
            try {
                pixel = Typeface.createFromAsset(c.getAssets(), "fonts/PressStart2P-Regular.ttf");
            } catch (RuntimeException e) {
                pixel = MEDIUM;
            }
        }
        return pixel;
    }

    private static AudioTrack clickTrack, blipTrack;

    /** Menu sounds (Options > Game list): a soft click when the highlight moves (blip false), a higher blip
     *  on A or open. Two tiny PCM buffers made here; silent when off or when the preview is muted. */
    static void sfx(Context c, boolean blip) {
        try {
            SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
            if (!p.getBoolean("menuSounds", false) || "video".equals(Options.preview(p))) return;
            if (clickTrack == null) {
                clickTrack = tone(1100, 900, 18);
                blipTrack = tone(1500, 2200, 55);
            }
            AudioTrack t = blip ? blipTrack : clickTrack;
            if (t == null) return;
            t.stop();
            t.setPlaybackHeadPosition(0);
            t.play();
        } catch (Exception ignored) { }
    }

    private static AudioTrack tone(int hz, int hz2, int ms) {
        int rate = 22050, n = rate * ms / 1000;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double f = hz + (hz2 - hz) * (double) i / n, env = 1 - (double) i / n;
            pcm[i] = (short) (Math.sin(2 * Math.PI * f * i / rate) * env * env * 3000);
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
        t.write(pcm, 0, n);
        t.setVolume(0.6f);
        return t;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable outlined(Context c, int stroke, float radiusDp) {
        GradientDrawable g = rounded(c, Color.TRANSPARENT, radiusDp);
        g.setStroke(dp(c, 1), stroke);
        return g;
    }

    /**
     * A row or card: its colour normally, the accent container while focused or
     * selected (a pad's cursor), and a ripple on touch.
     */
    static Drawable card(Context c, int normal, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_focused }, rounded(c, PRIMARY_CONTAINER, radiusDp));
        s.addState(new int[] { android.R.attr.state_selected }, rounded(c, PRIMARY_CONTAINER, radiusDp));
        s.addState(new int[] { android.R.attr.state_activated }, rounded(c, PRIMARY_CONTAINER, radiusDp));
        s.addState(new int[0], normal == Color.TRANSPARENT ? new ColorDrawable(Color.TRANSPARENT) : rounded(c, normal, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), s, rounded(c, Color.WHITE, radiusDp));
    }

    static TextView text(Context c, String s, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    /** A small outlined key hint: [A] Play. */
    static TextView chip(Context c, String key, String what) {
        TextView t = text(c, key + "  " + what, 13, ON_SURFACE_VARIANT, MEDIUM);
        t.setBackground(outlined(c, OUTLINE_VARIANT, 8));
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        return t;
    }

    /** Dark system bars in the page's colour, and the page kept clear of them. */
    static void edgeToEdge(Activity a, View root) {
        Window w = a.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        int pad = dp(a, 20);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
                r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
            }
            /* upright, the bars already leave space: only a small gap above and below */
            boolean port = v.getHeight() > v.getWidth() || a.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_PORTRAIT;
            int pv = port ? dp(a, 4) : pad;
            v.setPadding(l + pad, t + pv, r + pad, b + pv);
            return in;
        });
    }

    /** Dialogs in the device's dark Material style. */
    static int dialogTheme() {
        return android.R.style.Theme_DeviceDefault_Dialog_Alert;
    }

    private Ui() {}
}
