package com.jadflat.chgame;

import android.app.Activity;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Map;

/**
 * One game, full screen. A thread runs the core a frame at a time and writes
 * its sound to a blocking AudioTrack, so the sound card paces the game at
 * 60 frames a second, as the emulator's own front end does.
 */
public class GameActivity extends Activity implements SurfaceHolder.Callback {
    private static final int RATE = 48000;

    private SurfaceView view;
    private volatile SurfaceHolder holder;
    private volatile int keys, stick;       /* Native.* bits from buttons, and from the sticks */
    private volatile int pressed;           /* pressed lately: a quick tap is held for TAP_FRAMES, */
    private int tapFrames;                  /* so a game reading its buttons at 30 fps still sees it */
    private static final int TAP_FRAMES = 3;
    private volatile boolean fast;          /* fast-forward held */
    private boolean hotkey;                 /* M2 held: with START, quit */
    private static final int FAST_SPEED = 3;
    private final int[] screen = new int[128 * 128];   /* the last frame, for a screenshot */
    private volatile boolean running;
    private boolean menuOpen;               /* the game stays paused while a menu is up */
    private long runSince, played;          /* play time: since the loop last started, and so far */
    private Thread thread;
    private boolean started;
    private Keymap keymap;
    private boolean fill;                   /* Options: as tall as the screen, not whole-number */
    private boolean autosave;               /* write the resume point on leaving: not before the
                                               player chose whether to continue from the old one */
    private Uri tree;
    private Map<String, Long> stamps;
    private String name;
    private String title;                   /* from the game's header, for the menu */
    private volatile boolean rewinding;     /* REWIND held: the game runs back through the ring */
    private static final int REWIND_EVERY = 30;   /* a state into the ring every this many frames of play */
    private static final int REWIND_STEP = 15;    /* rewinding: one state back every this many frames */
    private static final int REWIND_RING = 20;    /* the ring: about 10 seconds */
    private int rewindHead, rewindCount;    /* the ring: next slot to write, slots in use */
    private int rewindSteps;                /* steps back from the newest, while rewinding */
    private int rewindTick;                 /* frames of play since the last state */
    private TouchControls touch;            /* the touch pad over the picture */
    private boolean touchAuto;              /* Options: hidden on the first key event */
    private Bitmap bezelBmp;                /* the handheld frame, drawn once per surface size (loop thread) */
    private volatile Bitmap scanBmp;        /* Options: scanlines (and CRT's vignette) over the picture, built per surface size */
    private String speedText = "";          /* the speed line (loop thread only) */
    private int speedFrames;                /* frames, and time in Native.frame, since the last speed line */
    private long speedNanos, speedSince, speedLogged;
    private volatile long frameAt;          /* System.nanoTime() when Native.frame last returned */
    private final Handler watch = new Handler(Looper.getMainLooper());   /* the hang check (UI thread) */
    private final Runnable watchdog = this::watchdogTick;
    private static final long HANG_NANOS = 3000000000L;    /* no frame for this long: the game stopped */
    private volatile boolean clipOn;        /* a GIF clip is recording: the loop feeds it each picture */
    private volatile boolean clipFull;      /* the clip reached its 20 s; the UI thread ends it */
    private int clipFrames;                 /* pictures in the clip so far */
    private static final int CLIP_FRAMES = 20 * 60;   /* 20 s at 60 frames a second */
    private static boolean resetNext;       /* the hang dialog's Reset: the next activity starts fresh */
    private boolean fresh;                  /* this run starts from the beginning: no resume offer */
    private boolean skipResume;             /* this run was reset: its stuck state is not written */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        view.setOnClickListener(v -> { if (portrait() || touch.getVisibility() != View.VISIBLE) menu(); });   /* landscape with the touch pad: top centre only */
        touch = new TouchControls(this);
        touch.setOnTouchListener((v, ev) -> {
            /* a tap at the top centre opens the menu; the pad still gets the touch (returns false) */
            if (ev.getActionMasked() == MotionEvent.ACTION_UP && topCentre(ev, v)) menu();
            return false;
        });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(view);
        frame.addView(touch);
        /* every size or turn: the pad goes where the portrait picture leaves room (the whole screen in landscape) */
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeTouch(frame));
        setContentView(frame);
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String touchMode = Options.touch(prefs);
        touchAuto = "auto".equals(touchMode);
        touch.setVisibility("off".equals(touchMode) ? View.GONE : View.VISIBLE);
        fill = Options.fill(prefs);
        String t = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("tree", null);
        tree = t == null ? null : Uri.parse(t);
        /* "game" + "name": a document from the picked folder (MainActivity);
           "file": a game LaunchActivity already copied into the private games folder */
        final String game = getIntent().getStringExtra("game");
        final String copied = getIntent().getStringExtra("file");
        name = copied != null ? copied : getIntent().getStringExtra("name");
        title = getIntent().getStringExtra("title");
        if (name == null || (game == null && copied == null)) { finish(); return; }
        keymap = Keymap.load(prefs, name);      /* this game's own keys, else the main ones */
        fresh = resetNext;
        resetNext = false;

        /* the card and the game are copied off the UI thread; then the loop starts. The core is
           the list's preview's until it lets go (Preview.CORE) */
        Preview.gameRunning = true;
        new Thread(() -> {
            String err = null;
            synchronized (Preview.CORE) {
            try {
                if (tree != null) {
                    try {
                        stamps = Files.importCard(this, tree);
                    } catch (Exception e) {
                        stamps = null;  /* the folder is gone: play with the card as it was */
                    }
                }
                File g = game != null ? Files.copyGame(this, Uri.parse(game), name)
                        : Files.inside(new File(getFilesDir(), "games"), Files.safeName(name));
                if (!g.isFile() || !Files.isGame(g.getName())) throw new java.io.IOException("no game " + name);
                File saves = new File(getFilesDir(), "saves");
                saves.mkdirs();
                clearRewind();      /* a new run starts with an empty ring */
                Options.apply(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE), name);
                if (!Native.start(g.getPath(), new File(getFilesDir(), "sys").getPath(), saves.getPath(),
                        new File(getFilesDir(), "log.txt").getPath()))
                    err = "This game would not start.";
            } catch (Exception e) {
                err = "Could not load: " + e.getMessage();
            }
            }
            final String msg = err;
            runOnUiThread(() -> {
                if (msg != null) {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                started = true;
                offerResume();
            });
        }).start();
    }

    /* ---------------------------------------------------------------- */
    /* Resume where you left off                                          */
    /* ---------------------------------------------------------------- */

    /** At the start: continue from the resume point, as Options says (ask, always, off). */
    private void offerResume() {
        if (fresh) {    /* a Reset: from the beginning; the resume point is written on leaving */
            autosave = true;
            if (hasWindowFocus()) resumeGame();
            return;
        }
        String mode =Options.resume(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
        File auto;
        try {
            auto = Saves.autoFile(this, name);
        } catch (java.io.IOException e) {
            auto = null;
        }
        if ("off".equals(mode) || auto == null || !auto.isFile()) {
            autosave = !"off".equals(mode);
            if (hasWindowFocus()) resumeGame();
            return;
        }
        final File f = auto;
        if ("always".equals(mode)) {
            continueFrom(f);
            autosave = true;
            if (hasWindowFocus()) resumeGame();
            return;
        }
        menuOpen = true;
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(title != null ? title : Saves.base(name))
                .setItems(new String[] { "Continue where you left off  (" + Saves.describe(f) + ")",
                        "Start from the beginning" }, (d, which) -> {
                    if (which == 0) continueFrom(f);
                    autosave = true;
                    closeMenu();
                })
                .setOnCancelListener(d -> { autosave = true; closeMenu(); })
                .show();
    }

    private void continueFrom(File f) {
        if (!Native.loadState(f.getPath())) toast("The resume point could not be loaded; starting fresh");
    }

    /** On leaving (or going to the background): the resume point. */
    private void writeResumePoint() {
        if (!autosave || skipResume) return;
        try {
            File f = Saves.autoFile(this, name);
            File tmp = new File(f.getPath() + ".tmp");
            if (Native.saveState(tmp.getPath()) && !tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            immersive();
            resumeGame();
        } else {
            pauseGame();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseGame();
        abortClip();    /* leaving the app: a clip in progress is dropped */
        if (started) {
            Stats.addPlay(this, name, played);
            played = 0;
            writeResumePoint();
            Native.flush();
            exportCard();
            if (isFinishing()) {
                /* leaving: free the core now, so the list's preview can have it */
                Native.stop();
                started = false;
                Preview.gameRunning = false;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pauseGame();
        abortClip();
        if (started) {
            Native.stop();
            exportCard();
            started = false;
        }
        Preview.gameRunning = false;
    }

    private void exportCard() {
        if (tree == null || stamps == null) return;
        try {
            Files.exportCard(this, tree, stamps);
        } catch (Exception e) {
            /* the folder went away or is read-only: the private copy keeps the files */
        }
    }

    private synchronized void resumeGame() {
        if (!started || running || holder == null || menuOpen) return;
        running = true;
        runSince = android.os.SystemClock.elapsedRealtime();
        thread = new Thread(this::loop, "chgame");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        frameAt = System.nanoTime();
        watch.postDelayed(watchdog, 1000);
    }

    private void pauseGame() {
        watch.removeCallbacks(watchdog);
        Thread t;
        synchronized (this) {
            if (running) played += android.os.SystemClock.elapsedRealtime() - runSince;
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) { }
        }
    }

    private void loop() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        String ab = Options.audioBuffer(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
        int normal = Math.max(min, 800 * 4 * 4);    /* the buffer as it was */
        int size = "low".equals(ab) ? min : "high".equals(ab) ? normal * 2 : normal;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        speedSince = speedLogged = System.nanoTime();
        speedFrames = 0;
        speedNanos = 0;
        int[] px = new int[128 * 128];
        short[] sound = new short[4096 * 2];
        Bitmap bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setFilterBitmap(false);
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String look = Options.look(sp);
        boolean grid = "grid".equals(look);
        boolean crt = "crt".equals(look);
        boolean scan = "scanlines".equals(look) || crt;
        if (Options.panelColours(sp)) {
            /* like the CHGame's ST7735: a little less saturated, blacks a touch lifted */
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0.82f);
            android.graphics.ColorMatrix lift = new android.graphics.ColorMatrix(new float[] {
                    0.94f, 0, 0, 0, 10, 0, 0.94f, 0, 0, 10, 0, 0, 0.94f, 0, 12, 0, 0, 0, 1, 0 });
            cm.postConcat(lift);
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        }
        Paint lines = new Paint();
        lines.setColor(0x38000000);
        Paint glow = new Paint();               /* CRT: the picture again, filtered, at 25% over the sharp one */
        glow.setFilterBitmap(true);
        glow.setAlpha(64);
        glow.setColorFilter(paint.getColorFilter());
        boolean overlay = Options.overlay(sp), bezel = Options.bezel(sp);
        float dp = getResources().getDisplayMetrics().density;
        Paint speed = new Paint(Paint.ANTI_ALIAS_FLAG);     /* the speed line: white, dark shadow */
        speed.setTypeface(Typeface.MONOSPACE);
        speed.setTextSize(14 * dp);
        speed.setColor(Color.WHITE);
        speed.setShadowLayer(2 * dp, dp, dp, Color.BLACK);
        Rect src = new Rect(0, 0, 128, 128);
        short[] silence = new short[800 * 2];   /* a frame's silence: paces the loop while rewinding */
        boolean was = false;                    /* rewinding in the last loop */
        int wait = 0;                           /* rewinding: frames until the next state back */
        while (running) {
            speedTick();
            boolean back = rewinding;
            int n = 0;
            if (back) {
                /* rewind: a state back every REWIND_STEP frames, and one frame with no buttons to
                   show it; the sound is muted (silence below) */
                if (!was) wait = 0;
                if (wait-- <= 0) {
                    wait = REWIND_STEP - 1;
                    if (rewindBack()) timedFrame(0, px, sound);
                }
            } else {
                if (rewindSteps > 0) rewindCommit();    /* let go: play on from the state loaded */
                /* fast-forward: several frames for each one shown, and only the last one's sound */
                for (int k = fast ? FAST_SPEED : 1; k > 0; k--) {
                    int tap = pressed;
                    if (tap != 0 && ++tapFrames >= TAP_FRAMES) {
                        pressed = 0;
                        tapFrames = 0;
                    }
                    n = timedFrame(keys | stick | tap | padBits(), px, sound);
                    if (++rewindTick >= REWIND_EVERY) {
                        rewindTick = 0;
                        rewindPut();
                    }
                }
            }
            was = back;
            bmp.setPixels(px, 0, 128, 0, 0, 128, 128);
            System.arraycopy(px, 0, screen, 0, px.length);
            if (clipOn) clipFrame();
            SurfaceHolder h = holder;
            Canvas c = h == null ? null : h.lockCanvas();
            if (c != null) {
                /* the biggest whole-number scale that fits (or, with fill, the whole height), centred;
                   in portrait, the top of the screen, as wide as it can be */
                int side = pictureSide(c.getWidth(), c.getHeight());
                int x = (c.getWidth() - side) / 2, y = portrait() ? 0 : (c.getHeight() - side) / 2;
                if (bezel && (c.getWidth() - side) / 2 >= side / 3) {
                    /* the handheld's frame, with room for the pad and buttons beside the picture */
                    if (bezelBmp == null || bezelBmp.getWidth() != c.getWidth() || bezelBmp.getHeight() != c.getHeight())
                        bezelBmp = buildBezel(c.getWidth(), c.getHeight(), x, y, side);
                    c.drawBitmap(bezelBmp, 0, 0, null);
                } else {
                    c.drawColor(Color.BLACK);
                }
                Rect dst = new Rect(x, y, x + side, y + side);
                c.drawBitmap(bmp, src, dst, paint);
                if (crt) c.drawBitmap(bmp, src, dst, glow);
                if (grid && side >= 128 * 3) {
                    /* the LCD look: a thin dark line between pixels */
                    float step = side / 128f;
                    for (int i = 1; i < 128; i++) {
                        float o = i * step;
                        c.drawLine(x + o, y, x + o, y + side, lines);
                        c.drawLine(x, y + o, x + side, y + o, lines);
                    }
                }
                Bitmap sc = scanBmp;
                if (scan && sc != null && sc.getWidth() == side) c.drawBitmap(sc, x, y, null);   /* scanlines, CRT vignette */
                if (overlay && speedText.length() > 0)
                    c.drawText(speedText, 6 * dp, 6 * dp + speed.getTextSize(), speed);
                h.unlockCanvasAndPost(c);
            }
            if (n > 0) track.write(sound, 0, n * 2);    /* blocks: the pace of the game */
            else if (back) track.write(silence, 0, silence.length);
        }
        if (rewindSteps > 0) rewindCommit();    /* stopped while rewinding: keep the state loaded */
        track.stop();
        track.release();
    }

    /** Native.frame, timed for the speed line. */
    private int timedFrame(int buttons, int[] px, short[] sound) {
        long t = System.nanoTime();
        int n = Native.frame(buttons, px, sound);
        frameAt = System.nanoTime();
        speedNanos += System.nanoTime() - t;
        speedFrames++;
        return n;
    }

    /** Twice a second: the speed line (fps, percent of 60, ms in Native.frame per frame);
     *  every 5 seconds the same line goes to the log, for logcat. */
    private void speedTick() {
        long now = System.nanoTime();
        long span = now - speedSince;
        if (span < 500000000L) return;
        double fps = speedFrames * 1e9 / span;
        double ms = speedFrames == 0 ? 0 : speedNanos / 1e6 / speedFrames;
        speedText = String.format(java.util.Locale.ROOT, "%.1f fps  %.0f%%  %.2f ms", fps, fps / 60 * 100, ms);
        if (now - speedLogged >= 5000000000L) {
            android.util.Log.i("CHGame", "speed " + name + ": " + speedText);
            speedLogged = now;
        }
        speedFrames = 0;
        speedNanos = 0;
        speedSince = now;
    }

    /** The handheld's frame around the picture, drawn once per surface size: a dark body with
     *  the picture in it, a darker screen border, a "CHGame" label, a d-pad left, A and B right. */
    private static Bitmap buildBezel(int w, int h, int x, int y, int side) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int pad = side / 24, border = Math.max(1, side / 64), label = side / 10, margin = side / 32;
        float top = y - pad, bottom = y + side + pad + label;
        float cy = y + side / 2f;
        p.setColor(0xFF2B2D31);
        c.drawRoundRect(new RectF(margin, top, w - margin, bottom), side / 12f, side / 12f, p);
        p.setColor(0xFF15161A);
        c.drawRect(x - border, y - border, x + side + border, y + side + border, p);
        p.setColor(0xFFB8BCC4);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(label * 0.55f);
        c.drawText("CHGame", x + side / 2f, y + side + pad + label * 0.75f, p);
        /* the d-pad, in the room left of the picture */
        float lx1 = x - pad, u = Math.min(lx1 - margin, bottom - top) / 4f, dx = (margin + lx1) / 2f;
        p.setColor(0xFF3A3D42);
        c.drawRect(dx - 1.5f * u, cy - 0.5f * u, dx + 1.5f * u, cy + 0.5f * u, p);
        c.drawRect(dx - 0.5f * u, cy - 1.5f * u, dx + 0.5f * u, cy + 1.5f * u, p);
        /* A (upper right) and B (lower left), in the room right of the picture */
        float rx0 = x + side + pad, r = Math.min(w - margin - rx0, bottom - top) / 6f, bx = (rx0 + w - margin) / 2f;
        float ay = cy - 0.6f * r, by = cy + 0.6f * r;
        c.drawCircle(bx + r, ay, r, p);
        c.drawCircle(bx - r, by, r, p);
        p.setColor(0xFF8A8F98);
        p.setTextSize(r);
        c.drawText("A", bx + r, ay + 0.35f * r, p);
        c.drawText("B", bx - r, by + 0.35f * r, p);
        return bmp;
    }

    /** The scanlines over the picture, drawn once per surface size (main thread): a bitmap the size
     *  of the picture, clear but for a dark band (about 45% black) across the bottom 35% of each
     *  pixel row; CRT adds a vignette, darkening the corners to about 45% black. */
    private static Bitmap buildScanlines(int side, boolean vignette) {
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        float step = side / 128f;
        p.setColor(0x73000000);
        for (int i = 0; i < 128; i++) {
            float top = i * step;
            c.drawRect(0, top + 0.65f * step, side, top + step, p);
        }
        if (vignette) {
            float mid = side / 2f;
            p.setShader(new android.graphics.RadialGradient(mid, mid, mid * 1.414f,
                    new int[] { 0x00000000, 0x73000000 }, new float[] { 0.6f, 1f },
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, side, side, p);
        }
        return bmp;
    }

    /* ---------------------------------------------------------------- */
    /* Rewind: a ring of states, about 10 seconds (game thread only)      */
    /* ---------------------------------------------------------------- */

    /** A new run starts with an empty ring: the last run's states go. */
    private void clearRewind() {
        File dir = new File(getCacheDir(), "rewind");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        dir.mkdirs();
    }

    private File rewindFile(int i) {
        return new File(getCacheDir(), "rewind/r" + ((i % REWIND_RING) + REWIND_RING) % REWIND_RING + ".st");
    }

    /** The state now goes into the ring, as the newest. */
    private void rewindPut() {
        if (!Native.saveState(rewindFile(rewindHead).getPath())) return;
        rewindHead = (rewindHead + 1) % REWIND_RING;
        if (rewindCount < REWIND_RING) rewindCount++;
    }

    /** One state further back; false at the oldest (or with none yet). */
    private boolean rewindBack() {
        if (rewindSteps >= rewindCount) return false;
        rewindSteps++;
        return Native.loadState(rewindFile(rewindHead - rewindSteps).getPath());
    }

    /** Rewind let go: play on from the state loaded, so the newer states are dropped. */
    private void rewindCommit() {
        rewindHead = (rewindHead - rewindSteps + 1 + REWIND_RING) % REWIND_RING;
        rewindCount -= rewindSteps - 1;
        rewindSteps = 0;
    }

    /* ---------------------------------------------------------------- */
    /* GIF clips, and the hang check                                      */
    /* ---------------------------------------------------------------- */

    /** The date and time for a file name: "2026-10-28 14-03-07". */
    private static String stamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
    }

    /** Record clip (the menu, the game paused): the loop feeds the clip from now on. */
    private void startClip() {
        if (tree == null) {
            toast("Choose a games folder first (Options)");
            return;
        }
        if (!Native.gifBegin()) {
            toast("Could not start the clip");
            return;
        }
        clipFrames = 0;
        clipOn = true;
        toast("Recording a clip (20 seconds at most)");
    }

    /** Record clip or Stop clip, from the menu. */
    private void toggleClip() {
        if (clipOn || clipFull) endClip();
        else startClip();
    }

    /** One picture into the clip (game thread): 1/60 s each; the clip stops itself at 20 s. */
    private void clipFrame() {
        Native.gifFrame(screen, 1 / 60.0);
        if (++clipFrames >= CLIP_FRAMES) {
            clipOn = false;
            clipFull = true;
        }
    }

    /** Ends the clip: encoded on a thread of its own, then copied into CHGame screenshots. */
    private void endClip() {
        clipOn = false;
        clipFull = false;
        String file = Saves.base(name) + " " + stamp() + ".gif";
        Uri dest = tree;
        new Thread(() -> {
            String msg;
            try {
                File gif = new File(getCacheDir(), "clip.gif");
                if (!Native.gifEnd(gif.getPath())) throw new java.io.IOException("no clip");
                String root = android.provider.DocumentsContract.getTreeDocumentId(dest);
                SnapMaker.put(getContentResolver(), dest, SnapMaker.dir(getContentResolver(), dest, root, "CHGame screenshots"),
                        file, "image/gif", gif);
                gif.delete();
                msg = "Saved CHGame screenshots/" + file;
            } catch (Exception e) {
                msg = "Clip not saved: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    /** Leaving the app: a clip in progress is dropped, not saved. */
    private void abortClip() {
        if (!clipOn && !clipFull) return;
        clipOn = false;
        clipFull = false;
        Native.gifAbort();
    }

    /** The game has sent no frame for 3 s while it should run: say so, and offer a reset or a quit. */
    private void hangDialog() {
        menuOpen = true;    /* no more checks: the dialog is the way on */
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("The game stopped responding")
                .setCancelable(false)
                .setPositiveButton("Reset", (d, w) -> reset())
                .setNegativeButton("Quit", (d, w) -> quit())
                .show();
    }

    /** Reset (the hang dialog): the stuck state is not written as the resume point, and the game
     *  starts again from the beginning. recreate() keeps the launch intent, so a static flag says so. */
    private void reset() {
        skipResume = true;
        resetNext = true;
        recreate();
    }

    /** Each second while the game runs (UI thread). A frame must come within 3 s, but the clock is
     *  held in a menu and while rewinding; a clip that reached 20 s is ended here. */
    private void watchdogTick() {
        if (!running) return;
        if (clipFull) endClip();
        long now = System.nanoTime();
        if (menuOpen || rewinding) frameAt = now;
        else if (now - frameAt > HANG_NANOS) {
            hangDialog();
            return;
        }
        watch.postDelayed(watchdog, 1000);
    }

    @Override public void surfaceCreated(SurfaceHolder h) { holder = h; if (hasWindowFocus()) resumeGame(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        holder = h;
        /* the picture's size, as the loop works it out: the overlays are cached for it. The old one is
           only dropped here (the loop may be drawing it); surfaceDestroyed recycles it */
        String look = Options.look(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
        boolean scan = "scanlines".equals(look) || "crt".equals(look);
        int side = pictureSide(w, hh);
        scanBmp = scan ? buildScanlines(side, "crt".equals(look)) : null;
    }

    private boolean portrait() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    /** The picture's side for a w x h surface. Portrait: the widest whole-number scale (fit if under 1x). */
    private int pictureSide(int w, int h) {
        if (portrait()) {
            int s = w >= 128 ? w / 128 * 128 : Math.min(w, h);
            return s <= h ? s : Math.min(w, h);
        }
        int side = Math.min(w, h);
        return fill ? side : Math.max(1, side / 128) * 128;
    }

    /** The touch pad: the whole screen in landscape; in portrait, the space under the picture. */
    private void placeTouch(FrameLayout frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int top = portrait() ? pictureSide(w, h) : 0;
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) touch.getLayoutParams();
        if (old != null && old.topMargin == top && old.height == h - top) return;   /* no change: no new layout */
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h - top);
        lp.topMargin = top;
        touch.setLayoutParams(lp);
    }
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        pauseGame();
        holder = null;
        Bitmap sc = scanBmp;
        scanBmp = null;
        if (sc != null) sc.recycle();     /* pauseGame has stopped the loop: nothing draws it now */
    }

    /* ---------------------------------------------------------------- */
    /* Input                                                              */
    /* ---------------------------------------------------------------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        /* a pad or keyboard is in use: no touch pad (not for BACK or volume, which phones have too) */
        if (touchAuto && !ev.isSystem()) touch.setVisibility(View.GONE);
        /* hold M2 (BUTTON_Z on the Retroid) and press START: quit, as other emulators do
           from ES-DE; the resume point is saved as for any quit */
        if (code == KeyEvent.KEYCODE_BUTTON_Z) {
            hotkey = ev.getAction() == KeyEvent.ACTION_DOWN;
            return true;
        }
        if (hotkey && code == KeyEvent.KEYCODE_BUTTON_START) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) quit();
            return true;
        }
        int b = keymap.button(code);
        if (b == Keymap.UP2) b = Native.UP;   /* a second Up, on Y unless Map buttons moved it */
        if (code == KeyEvent.KEYCODE_BACK || b == Keymap.MENU) {
            if (ev.getAction() == KeyEvent.ACTION_UP) menu();
            return true;
        }
        if (b == Keymap.SHOT) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) saveScreenshot();
            return true;
        }
        if (b == Keymap.QSAVE || b == Keymap.QLOAD) {    /* never the game's: a key for slot 1 */
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) quickSlot(b == Keymap.QSAVE);
            return true;
        }
        if (b == Keymap.FAST) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) fast = true;
            else if (ev.getAction() == KeyEvent.ACTION_UP) fast = false;
            return true;
        }
        if (b == Keymap.REWIND) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) rewinding = true;
            else if (ev.getAction() == KeyEvent.ACTION_UP) rewinding = false;
            return true;
        }
        if (b == 0) return super.dispatchKeyEvent(ev);
        if (ev.getAction() == KeyEvent.ACTION_DOWN) { keys |= b; pressed |= b; tapFrames = 0; }
        else if (ev.getAction() == KeyEvent.ACTION_UP) keys &= ~b;
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) == 0 || ev.getAction() != MotionEvent.ACTION_MOVE)
            return super.dispatchGenericMotionEvent(ev);
        float x = ev.getAxisValue(MotionEvent.AXIS_X), y = ev.getAxisValue(MotionEvent.AXIS_Y);
        float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X), hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int s = 0;
        if (x < -0.5f || hx < -0.5f) s |= Native.LEFT;
        if (x > 0.5f || hx > 0.5f) s |= Native.RIGHT;
        if (y < -0.5f || hy < -0.5f) s |= Native.UP;
        if (y > 0.5f || hy > 0.5f) s |= Native.DOWN;
        stick = s;
        return true;
    }

    /** The top centre of the touch pad: where a tap opens the menu. */
    private static boolean topCentre(MotionEvent ev, View v) {
        return ev.getX() > v.getWidth() * 0.35f && ev.getX() < v.getWidth() * 0.65f
                && ev.getY() < v.getHeight() * 0.15f;
    }

    /** Buttons held on the touch pad (none while it is hidden). Read on the loop thread. */
    private int padBits() {
        return touch.getVisibility() == View.VISIBLE ? touch.buttons() : 0;
    }

    /* ---------------------------------------------------------------- */
    /* The menu: BACK, the home button on a pad, or a tap                 */
    /* ---------------------------------------------------------------- */

    private void menu() {
        if (!started) return;
        menuOpen = true;
        pauseGame();
        keys = 0;
        stick = 0;
        fast = false;
        rewinding = false;
        String[] items = { "Resume", "Save state…", "Load state…", "Save screenshot",
                clipOn || clipFull ? "Stop clip" : "Record clip",
                fromFrontEnd() ? "Quit" : "Quit to game list" };
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(title != null ? title : Saves.base(name))
                .setItems(items, (d, which) -> {
                    if (which == 1 || which == 2) {
                        slots(which == 1);
                        return;
                    }
                    if (which == 3) saveScreenshot();
                    if (which == 4) toggleClip();
                    if (which == 5) quit();
                    else closeMenu();
                })
                .setOnCancelListener(d -> closeMenu())
                .show();
    }

    /** Quick save or quick load from a pad key: slot 1, as the menu's slot 1 (the game paused). */
    private void quickSlot(boolean save) {
        if (!started || menuOpen) return;
        File f;
        try {
            f = Saves.stateFile(this, name, 1);
        } catch (java.io.IOException e) {
            toast("Bad game name");
            return;
        }
        pauseGame();    /* the machine must be still while it is copied */
        if (save) {
            boolean ok = Native.saveState(f.getPath());
            if (ok) slotPicture(f);
            toast(ok ? "Saved to slot 1" : "Save failed");
        } else {
            toast(f.isFile() && Native.loadState(f.getPath()) ? "Loaded slot 1" : "Slot 1 is empty");
        }
        closeMenu();
    }

    /** The state slots, to save into or load from; the game stays paused until a choice. */
    private void slots(boolean save) {
        File[] f = new File[Saves.SLOTS];
        String[] items = new String[Saves.SLOTS];
        try {
            for (int i = 0; i < Saves.SLOTS; i++) {
                f[i] = Saves.stateFile(this, name, i + 1);
                items[i] = "Slot " + (i + 1) + ":  " + Saves.describe(f[i]);
            }
        } catch (java.io.IOException e) {
            toast("Bad game name");
            closeMenu();
            return;
        }
        /* a row per slot: its picture (64dp, empty if none) and its text; each row is a stop for the pad */
        int pad = Ui.dp(this, 8);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        final AlertDialog d = new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(save ? "Save state" : "Load state")
                .setView(scroll)
                .setOnCancelListener(c -> menu())
                .create();
        for (int i = 0; i < Saves.SLOTS; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            row.setBackground(Ui.card(this, Ui.SURFACE, 12));
            row.setFocusable(true);
            row.setClickable(true);
            ImageView picture = new ImageView(this);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap pic = BitmapFactory.decodeFile(Saves.pictureFile(f[i]).getPath());   /* null: no picture */
            if (pic != null) picture.setImageBitmap(pic);
            row.addView(picture, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
            TextView label = Ui.text(this, items[i], 16, Ui.ON_SURFACE, Ui.REGULAR);
            label.setPadding(pad, 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = pad;
            list.addView(row, lp);
            row.setOnClickListener(v -> {
                d.dismiss();
                pauseGame();    /* the machine must be still while it is copied */
                if (save) {
                    boolean ok = Native.saveState(f[slot].getPath());
                    if (ok) slotPicture(f[slot]);
                    toast(ok ? "Saved to slot " + (slot + 1) : "Save failed");
                } else {
                    toast(f[slot].isFile() && Native.loadState(f[slot].getPath())
                            ? "Loaded slot " + (slot + 1) : "Slot " + (slot + 1) + " is empty");
                }
                closeMenu();
            });
        }
        d.show();
        list.getChildAt(0).requestFocus();    /* the pad's first stop */
    }

    /** The screen as it was when the menu opened, 512x512 with sharp pixels, into
     *  the games folder's CHGame screenshots/ ("<game> 2026-10-28 14-03-07.png"). */
    private void saveScreenshot() {
        if (tree == null) {
            toast("Choose a games folder first (Options)");
            return;
        }
        int[] shot = screen.clone();
        String file = Saves.base(name) + " " + stamp() + ".png";
        new Thread(() -> {
            String msg;
            try {
                File png = new File(getCacheDir(), "screenshot.png");
                android.graphics.Bitmap b = android.graphics.Bitmap.createScaledBitmap(
                        android.graphics.Bitmap.createBitmap(shot, 128, 128, android.graphics.Bitmap.Config.ARGB_8888),
                        512, 512, false);
                try (java.io.OutputStream out = new java.io.FileOutputStream(png)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                String root = android.provider.DocumentsContract.getTreeDocumentId(tree);
                SnapMaker.put(getContentResolver(), tree, SnapMaker.dir(getContentResolver(), tree, root, "CHGame screenshots"),
                        file, "image/png", png);
                png.delete();
                msg = "Saved CHGame screenshots/" + file;
            } catch (Exception e) {
                msg = "Screenshot not saved: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    /** The screen as it is, beside a slot's state: the slot list shows it. The game is paused. */
    private void slotPicture(File state) {
        try (java.io.OutputStream out = new java.io.FileOutputStream(Saves.pictureFile(state))) {
            Bitmap.createBitmap(screen, 128, 128, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (java.io.IOException e) {
            /* no picture: the state is still saved */
        }
    }

    /** Started by a front end such as ES-DE (LaunchActivity), not from this app's list. */
    private boolean fromFrontEnd() {
        return getIntent().getStringExtra("file") != null;
    }

    /** Leaves the game: back to the front end that started it (the whole app goes, so ES-DE
     *  is what shows), or to this app's list. */
    private void quit() {
        if (fromFrontEnd()) finishAndRemoveTask();
        else finish();
    }

    private void closeMenu() {
        menuOpen = false;
        immersive();
        resumeGame();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
