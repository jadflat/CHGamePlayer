package com.jadflat.chgame;

import android.app.Activity;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
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
    private final Object tapLock = new Object();
    private int tapFrames;                  /* so a game reading its buttons at 30 fps still sees it */
    private static final int TAP_FRAMES = 3;
    private volatile boolean fast;          /* fast-forward held */
    private int quitKey1, quitKey2;         /* the quit combo's keys (Options: SELECT then START by default) */
    private boolean quitHold, quitOff;      /* held for 2 s; or no combo at all */
    private boolean quitKey1Down, quitKey2Down, quitFired;   /* the combo keys are down; the combo took a press */
    private int quitTaken;      /* bit 1/2: the combo key whose press completed the combo (its release is the combo's too) */
    private final Runnable quitLater = this::quit;   /* the held combo: quit when its 2 s are up */
    private static final long QUIT_HOLD_MS = 2000;
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
    private volatile Bitmap scanBmp;        /* Options: scanlines (and CRT's vignette) over the picture, built per surface size */
    private String speedText = "";          /* the speed line (loop thread only) */
    private int speedFrames;                /* frames, and time in Native.frame, since the last speed line */
    private long speedNanos, speedSince, speedLogged;
    private volatile long frameAt;          /* System.nanoTime() when Native.frame last returned */
    private volatile long coreAt;           /* when the loop entered Native.frame; 0 while it is outside one */
    private final Handler watch = new Handler(Looper.getMainLooper());   /* the hang check (UI thread) */
    private final Runnable watchdog = this::watchdogTick;
    private static final long HANG_NANOS = 3000000000L;    /* no frame for this long: the game stopped */
    private volatile boolean clipOn;        /* a GIF clip is recording: the loop feeds it each picture */
    private volatile boolean clipFull;      /* the clip reached its 20 s; the UI thread ends it */
    private int clipFrames;                 /* pictures in the clip so far */
    private static final int CLIP_FRAMES = 20 * 60;   /* 20 s at 60 frames a second */
    private static GameActivity current;   /* the newest game: an older one destroyed late must not stop the core */
    private static boolean resetNext;       /* the hang dialog's Reset: the next activity starts fresh */
    private boolean fresh;                  /* this run starts from the beginning: no resume offer */
    private boolean skipResume;             /* this run was reset: its stuck state is not written */

    @Override
    protected void onCreate(Bundle saved) {
        current = this;
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SurfaceView(this);
        /* clickable makes it focusable; with a pad (non-touch mode) Android would paint its
           default focus highlight, a 30% white veil, over the whole game */
        view.setDefaultFocusHighlightEnabled(false);
        view.getHolder().addCallback(this);
        view.setOnTouchListener((v, ev) -> { lastTapX = ev.getX(); lastTapY = ev.getY(); return false; });
        view.setOnClickListener(v -> tapAt(v, lastTapX, lastTapY));
        touch = new TouchControls(this);
        touch.setOnMenu(this::menu);        /* the pad's MENU button */
        touch.setOnTap((x, y) -> {          /* the pad keeps every gesture: its taps off the controls, as the game's */
            int[] a = new int[2], b = new int[2];
            touch.getLocationOnScreen(a);
            view.getLocationOnScreen(b);
            float vx = x + a[0] - b[0], vy = y + a[1] - b[1];
            if (vx >= 0 && vy >= 0 && vx < view.getWidth() && vy < view.getHeight()) tapAt(view, vx, vy);
        });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(view);
        frame.addView(touch);
        /* every size or turn: the pad goes where the portrait picture leaves room (the whole screen in landscape) */
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeTouch(frame));
        frame.setOnApplyWindowInsetsListener((v, wi) -> { placeTouch(frame); return v.onApplyWindowInsets(wi); });
        setContentView(frame);
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String touchMode = Options.touch(prefs);
        touchAuto = "auto".equals(touchMode);
        touch.setVisibility("off".equals(touchMode) ? View.GONE : View.VISIBLE);
        if (touchAuto) {
            /* auto: the controls follow the gamepad, now and as one is plugged in or out */
            padSync(-1);
            ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE)).registerInputDeviceListener(padListener, null);
        }
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
        quitKey1 = Options.quitKey1(prefs);
        quitKey2 = Options.quitKey2(prefs);
        quitHold = Options.quitHold(prefs);
        quitOff = Options.quitOff(prefs);
        /* this game's screen rotation (GameSettingsActivity): follow the device, or always upright or sideways */
        String rotate = prefs.getString("rotate:" + name, "auto");
        setRequestedOrientation("portrait".equals(rotate) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : "landscape".equals(rotate) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        fresh = resetNext;
        resetNext = false;

        /* the card and the game are copied off the UI thread; then the loop starts. The core is
           the list's preview's until it lets go (Preview.CORE) */
        Preview.gameActive = true;
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
                if (!coreIdle()) throw new java.io.IOException("the last game is still stuck in a frame");
                if (isDestroyed()) throw new java.io.IOException("left");
                if (!Native.start(g.getPath(), new File(getFilesDir(), "sys").getPath(), saves.getPath(),
                        new File(getFilesDir(), "log.txt").getPath()))
                    err = "This game would not start.";
            } catch (Exception e) {
                err = "Could not load: " + e.getMessage();
            }
            }
            final String msg = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {   /* quit while loading: nothing to start */
                    if (msg == null && (current == null || current == this)) {
                        Native.stop();
                        Preview.gameActive = false;
                        if (current == this) current = null;
                    }
                    return;
                }
                if (msg != null) {
                    if (current == null || current == this) Preview.gameActive = false;     /* a stuck core (Reset) must not keep the list's preview off */
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
        String mode = Options.resume(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
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
        /* a card: the game's name, then Continue (focused) and Start from the beginning */
        android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, pad, pad, Ui.dp(this, 20));
        box.setBackground(Ui.rounded(this, Ui.SURFACE, 28));
        TextView head = Ui.text(this, title != null ? title : Saves.base(name), 16, Ui.ON_SURFACE, Ui.pixel(this));
        head.setGravity(Gravity.CENTER);
        box.addView(head);
        head.setPadding(0, 0, 0, Ui.dp(this, 16));
        View cont = menuButton("Continue", "Where you left off, " + Saves.describe(f)),
                fresh = menuButton("Start from the beginning", "A new game");
        cont.setOnClickListener(v -> { continueFrom(f); autosave = true; dlg.dismiss(); closeMenu(); });
        fresh.setOnClickListener(v -> { autosave = true; dlg.dismiss(); closeMenu(); });
        box.addView(cont);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.setMargins(0, Ui.dp(this, 10), 0, 0);
        box.addView(fresh, fp);
        dlg.setContentView(box);
        /* Back: back to the list, the game not started and its resume point untouched */
        dlg.setOnCancelListener(d -> { skipResume = true; quit(); });
        /* the pad moves the highlight at once (no first press lost leaving touch mode) */
        dlg.setOnKeyListener((d, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
                boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN;
                cont.setSelected(!down);
                fresh.setSelected(down);
                return true;
            }
            /* the game's own A and B, as mapped (on a Retroid, Android's BUTTON_B is the key marked A) */
            int b = keymap != null ? keymap.button(code) : 0;
            /* either face button answers the card: which one is "A" differs from pad to pad, and a
               button taken for B used to cancel it and close the game. Back still cancels. */
            if (b == Native.A || b == Native.B || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                (fresh.isSelected() ? fresh : cont).performClick();
                return true;
            }
            return false;
        });
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int wide = Math.min(Ui.dp(this, 420), (int) (getResources().getDisplayMetrics().widthPixels * 0.9f));
            w.setLayout(wide, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
        cont.setSelected(true);
    }

    /** One of the start card's rows, styled like the list's and Options' rows: a name and a line under it. */
    private View menuButton(String label, String detail) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        b.setBackground(Ui.card(this, Ui.SURFACE_HIGH, 16));
        b.addView(Ui.text(this, label, 17, Ui.ON_SURFACE, Ui.MEDIUM));
        b.addView(Ui.text(this, detail, 14, Ui.ON_SURFACE_VARIANT, Typeface.DEFAULT));
        /* not focusable: the card's key listener moves one highlight (selected), so focus never lights a second row */
        b.setFocusable(false);
        b.setClickable(true);
        /* the highlight shows from the start, as in the list, not only after a first key press */
        return b;
    }

    private void continueFrom(File f) {
        if (Native.loadState(f.getPath()) || Native.loadState(f.getPath())) return;     /* twice: a moment's trouble */
        /* another build's, or damaged: moved aside (not deleted), so it is not offered again and nothing is lost */
        File aside = new File(f.getPath() + ".unloadable");
        aside.delete();
        if (!f.renameTo(aside)) Saves.deleteState(f);
        toast("The resume point could not be loaded (another version, or damaged); starting fresh");
    }

    /** A state written whole to f or not at all: through <f><tmp>, on the disk, then renamed over f (the old state stays
        if anything fails). The game is paused. */
    private static boolean saveStateTo(File f, String tmpSuffix) {
        File tmp = new File(f.getPath() + tmpSuffix);
        try {
            if (!Native.saveState(tmp.getPath())) return false;
            Files.syncFile(tmp);
            Files.replaceWith(tmp, f);
            return true;
        } catch (java.io.IOException e) {
            return false;
        } finally {
            tmp.delete();       /* gone after the rename; left over only if something failed */
        }
    }

    /** "Loaded", "is empty" or "could not be loaded", for a slot. */
    private static String loadSlot(File f, int slot) {
        if (!f.isFile()) return "Slot " + slot + " is empty";
        return Native.loadState(f.getPath()) ? "Loaded slot " + slot
                : "Slot " + slot + " could not be loaded (another version, or damaged)";
    }

    /** On leaving (or going to the background): the resume point. */
    private void writeResumePoint() {
        if (!autosave || skipResume) return;
        try {
            /* its own temporary name (a restore writes <f>.tmp): no lock, so leaving the game never waits on a restore;
               whichever finishes last is the resume point, each one whole */
            saveStateTo(Saves.autoFile(this, name), ".resume.tmp");
        } catch (java.io.IOException ignored) {
        }
    }

    private void immersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            }
            return;
        }
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
            keys = 0;       /* a key-up sent to another window never arrives: nothing stays held */
            stick = 0;
            fast = false;
            rewinding = false;
            synchronized (tapLock) { pressed = 0; tapFrames = 0; }
            quitKey1Down = quitKey2Down = false;   /* a release sent to a dialog never arrives: the combo starts clean */
            quitTaken = 0;
            quitFired = false;
            watch.removeCallbacks(quitLater);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Thread st = slotThread;
        if (st != null) { try { st.join(8000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        boolean idle = pauseGame();
        abortClip();    /* leaving the app: a clip in progress is dropped */
        if (started && idle) {
            Stats.addPlay(this, name, played);
            played = 0;
            writeResumePoint();
            Native.flush();
            exportCard();
            if (isFinishing()) {
                /* leaving: free the core now, so the list's preview can have it */
                Native.stop();
                started = false;
                Preview.gameActive = false;
            }
        }
    }

    private final android.hardware.input.InputManager.InputDeviceListener padListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { padSync(-1); }
        @Override public void onInputDeviceChanged(int id) { padSync(-1); }
        @Override public void onInputDeviceRemoved(int id) { padSync(id); }
    };

    /** Touch mode "auto": the pad is hidden while a gamepad is connected (ignoring device `gone`), shown when not. */
    private void padSync(int gone) {
        if (!touchAuto) return;
        boolean pad = MainActivity.hasPad(gone);
        touch.setVisibility(pad ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE)).unregisterInputDeviceListener(padListener);
        boolean idle = pauseGame();
        abortClip();
        if (current != this) return;   /* a newer game owns the core now */
        current = null;
        if (started && idle) {
            Native.stop();
            exportCard();
            started = false;
        }
        if (idle) Preview.gameActive = false;   /* stuck: the preview must not start the core under the frame */
    }

    /** Copies back to the games folder: on a thread of its own (a slow folder never holds up leaving the game), one at
        a time; the next game's card import waits for it (Files.IMPORT). */
    private static final java.util.concurrent.ExecutorService EXPORT = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void exportCard() {
        if (tree == null || stamps == null) return;
        android.content.Context app = getApplicationContext();
        Uri t = tree;
        java.util.Map<String, Long> s = stamps;
        EXPORT.execute(() -> {
            try {
                Files.exportCard(app, t, s);
            } catch (Exception e) {
                /* the folder went away or is read-only: the private copy keeps the files */
            }
        });
    }

    private synchronized void resumeGame() {
        if (!started || running || holder == null || menuOpen) return;
        if (!coreIdle()) {          /* the last frame is still running: start once it has ended */
            watch.removeCallbacks(retryResume);
            watch.postDelayed(retryResume, 250);
            return;
        }
        running = true;
        runSince = android.os.SystemClock.elapsedRealtime();
        thread = new Thread(this::loopSafe, "chgame");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        frameAt = System.nanoTime();
        watch.postDelayed(watchdog, 1000);
    }

    private final Runnable retryResume = this::resumeGame;

    /** The loop thread that did not stop when last paused (a frame stuck over 2 s); the core is not touched while it runs. */
    private static volatile Thread stuck;

    /** True when the core is still: no loop thread runs Native.frame. */
    private static boolean coreIdle() {
        Thread t = stuck;
        if (t != null && t.isAlive()) return false;
        stuck = null;
        return true;
    }

    /** Stops the loop thread; false when it is still inside a frame after 2 s (then the core must not be touched). */
    private boolean pauseGame() {
        watch.removeCallbacks(watchdog);
        watch.removeCallbacks(retryResume);
        Thread t;
        synchronized (this) {
            if (running) played += android.os.SystemClock.elapsedRealtime() - runSince;
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) { }
            if (t.isAlive()) stuck = t;
        }
        return coreIdle();
    }

    /** loop(), but a failure to open the sound (or any other error) ends the run with a message, not a frozen screen. */
    private void loopSafe() {
        try {
            loop();
        } catch (RuntimeException e) {
            running = false;
            runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this, "The game stopped: " + e, Toast.LENGTH_LONG).show(); });
        }
    }

    private void loop() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        String ab = Options.audioBuffer(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
        int normal = Math.max(min, 800 * 4 * 4);    /* the buffer as it was */
        int size = "low".equals(ab) && min > 0 ? min : "high".equals(ab) ? normal * 2 : normal;
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
        boolean lcd = "lcd".equals(look);
        boolean grid = "grid".equals(look) || lcd;
        int[] ghost = new int[128 * 128];       /* LCD: the picture as shown last frame, 25% of it stays on */
        boolean haveGhost = false;
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
        lines.setColor(lcd ? 0x26000000 : 0x38000000);    /* the LCD's gaps are fainter */
        Paint glow = new Paint();               /* CRT: the picture again, filtered, at 25% over the sharp one */
        glow.setFilterBitmap(true);
        glow.setAlpha(64);
        glow.setColorFilter(paint.getColorFilter());
        boolean overlay = Options.overlay(sp);
        float dp = getResources().getDisplayMetrics().density;
        Paint speed = new Paint(Paint.ANTI_ALIAS_FLAG);     /* the speed line: white, dark shadow */
        speed.setTypeface(Typeface.MONOSPACE);
        speed.setTextSize(14 * dp);
        speed.setColor(Color.WHITE);
        speed.setShadowLayer(2 * dp, dp, dp, Color.BLACK);
        Rect src = new Rect(0, 0, 128, 128);
        Rect dst = new Rect();
        short[] silence = new short[800 * 2];   /* a frame's silence: paces the loop while rewinding */
        boolean was = false;                    /* rewinding in the last loop */
        int wait = 0;                           /* rewinding: frames until the next state back */
        try {
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
                    int tap;
                    synchronized (tapLock) {
                        tap = pressed;
                        if (tap != 0 && ++tapFrames >= TAP_FRAMES) {
                            pressed = 0;
                            tapFrames = 0;
                        }
                    }
                    n = timedFrame(keys | stick | tap | padBits(), px, sound);
                    if (++rewindTick >= REWIND_EVERY) {
                        rewindTick = 0;
                        rewindPut();
                    }
                }
            }
            was = back;
            int[] shown = px;
            if (lcd) {
                /* slight ghosting: this frame 75%, the one shown before 25% (so it fades over a few frames) */
                if (haveGhost) {
                    for (int i = 0; i < ghost.length; i++) {
                        int a = px[i], b = ghost[i];
                        int r = (((a >> 16) & 255) * 3 + ((b >> 16) & 255)) >> 2;
                        int g = (((a >> 8) & 255) * 3 + ((b >> 8) & 255)) >> 2;
                        int bl = ((a & 255) * 3 + (b & 255)) >> 2;
                        ghost[i] = 0xFF000000 | r << 16 | g << 8 | bl;
                    }
                } else {
                    System.arraycopy(px, 0, ghost, 0, px.length);
                    haveGhost = true;
                }
                shown = ghost;
            }
            bmp.setPixels(shown, 0, 128, 0, 0, 128, 128);
            synchronized (screen) { System.arraycopy(px, 0, screen, 0, px.length); }
            if (clipOn) clipFrame();
            SurfaceHolder h = holder;
            Canvas c = h == null ? null : h.lockCanvas();
            if (c != null) {
                /* the biggest whole-number scale that fits (or, with fill, the whole height), centred;
                   in portrait, the top of the screen, as wide as it can be */
                int side = pictureSide(c.getWidth(), c.getHeight());
                int x = (c.getWidth() - side) / 2, y = portrait() ? 0 : (c.getHeight() - side) / 2;
                c.drawColor(Color.BLACK);
                dst.set(x, y, x + side, y + side);
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
            if (n > 0) pace(track, sound, n * 2);    /* blocks: the pace of the game */
            else if (back) pace(track, silence, silence.length);
        }
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
        }
        if (rewindSteps > 0) rewindCommit();    /* stopped while rewinding: keep the state loaded */
    }

    /** Writes a frame's sound; the write is what paces the game, so when the output is gone (a headset
     *  disconnected: it returns an error at once) the frame waits its 1/60 s instead. */
    static void pace(AudioTrack track, short[] buf, int len) {
        int w;
        try { w = track.write(buf, 0, len); } catch (IllegalStateException e) { w = -1; }
        if (w < 0) try { Thread.sleep(16); } catch (InterruptedException ignored) { }
    }

    /** Native.frame, timed for the speed line. */
    private int timedFrame(int buttons, int[] px, short[] sound) {
        long t = System.nanoTime();
        coreAt = t;
        int n = Native.frame(Native.toCore(buttons), px, sound);
        coreAt = 0;
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

    /** The ring, in memory (no flash writes while playing): each slot's array is reused once it has one. */
    private final byte[][] rewindRing = new byte[REWIND_RING][];

    /** A new run starts with an empty ring: the last run's states go (and the old file ring, from before 2.12). */
    private void clearRewind() {
        java.util.Arrays.fill(rewindRing, null);
        rewindHead = rewindCount = rewindSteps = 0;
        File dir = new File(getCacheDir(), "rewind");
        if (dir.exists()) new Thread(() -> {       /* the old file ring (before 2.12): off the UI thread */
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            dir.delete();
        }).start();
    }

    /** The state now goes into the ring, as the newest. */
    private void rewindPut() {
        byte[] s = Native.saveStateBytes(rewindRing[rewindHead]);
        if (s == null) return;
        rewindRing[rewindHead] = s;
        rewindHead = (rewindHead + 1) % REWIND_RING;
        if (rewindCount < REWIND_RING) rewindCount++;
    }

    /** One state further back; false at the oldest (or with none yet). */
    private boolean rewindBack() {
        if (rewindSteps >= rewindCount) return false;
        rewindSteps++;
        byte[] s = rewindRing[((rewindHead - rewindSteps) % REWIND_RING + REWIND_RING) % REWIND_RING];
        if (s != null && Native.loadStateBytes(s)) return true;
        rewindCount = --rewindSteps;     /* a state that will not load is the oldest there is */
        return false;
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
    private static String lastStamp = "";
    private static int sameStamp;

    /** The time for a file name; a second one in the same second gets " 2", " 3"... (never the same name twice). */
    private static synchronized String stamp() {
        String t = new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.ROOT).format(new java.util.Date());
        if (t.equals(lastStamp)) return t + " " + (++sameStamp);
        lastStamp = t;
        sameStamp = 1;
        return t;
    }

    /** Record clip (the menu, the game paused): the loop feeds the clip from now on. */
    private void startClip() {
        if (tree == null) {
            toast("Choose a games folder first (Options)");
            return;
        }
        if (!Native.gifBegin()) {
            toast("Could not start the clip (the last one may still be saving: try again in a moment)");
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
            File gif = null;
            try {
                gif = File.createTempFile("clip", ".gif", getCacheDir());     /* one per clip: they may overlap */
                if (!Native.gifEnd(gif.getPath())) throw new java.io.IOException("no clip");
                String root = android.provider.DocumentsContract.getTreeDocumentId(dest);
                SnapMaker.put(getContentResolver(), dest, SnapMaker.dir(getContentResolver(), dest, root, Files.SHOTS),
                        file, "image/gif", gif);
                msg = "Saved CHGame screenshots/" + file;
            } catch (Exception e) {
                msg = "Clip not saved: " + e.getMessage();
            } finally {
                if (gif != null) gif.delete();
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
        new CardDialog.Builder(this)
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
        long in = coreAt;
        if (menuOpen || rewinding) frameAt = now;
        /* stuck only inside the core: a loop held up outside it (sound output or the screen waiting, as when
           Bluetooth headphones connect) is not the game hanging, and catches up by itself */
        else if (now - frameAt > HANG_NANOS && in != 0 && now - in > HANG_NANOS) {
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
        scanBmp = scan && side > 0 ? buildScanlines(side, "crt".equals(look)) : null;
    }

    private float lastTapX, lastTapY;

    /** A tap on the game at (x, y) in v's coordinates: portrait, the picture opens the menu (the black around it does
        nothing); landscape, the top centre does, only while the touch pad is hidden. */
    private void tapAt(View v, float x, float y) {
        if (portrait()) {
            int side = pictureSide(v.getWidth(), v.getHeight());
            float x0 = (v.getWidth() - side) / 2f;
            if (y < side && x >= x0 && x < x0 + side) menu();
        } else if (touch.getVisibility() != View.VISIBLE
                && x > v.getWidth() * 0.35f && x < v.getWidth() * 0.65f
                && y < v.getHeight() * 0.15f) menu();
    }

    private boolean portrait() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    /** The picture's side for a w x h surface. Portrait: the widest whole-number scale (fit if under 1x), or with fill the full width. */
    private int pictureSide(int w, int h) {
        return TouchControls.pictureSide(portrait(), fill, w, h);
    }

    /** The touch pad: the whole screen in landscape; in portrait, the space under the picture. */
    private void placeTouch(FrameLayout frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int top = portrait() ? pictureSide(w, h) : 0;
        int bottom = 0;                                  /* portrait: stay above the navigation bar */
        if (portrait()) {
            android.view.WindowInsets wi = frame.getRootWindowInsets();
            if (wi != null) bottom = wi.getStableInsetBottom();     /* stable: the same whether the bars show or hide */
        }
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) touch.getLayoutParams();
        if (old != null && old.topMargin == top && old.height == h - top - bottom) return;   /* no change: no new layout */
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Math.max(1, h - top - bottom));
        lp.topMargin = top;
        touch.setLayoutParams(lp);
    }
    @Override public void surfaceDestroyed(SurfaceHolder h) {
        boolean stopped = pauseGame();
        holder = null;
        Bitmap sc = scanBmp;
        scanBmp = null;
        if (sc != null && stopped) sc.recycle();     /* the loop has stopped: nothing draws it now (a stuck one keeps it to the GC) */
    }

    /* ---------------------------------------------------------------- */
    /* Input                                                              */
    /* ---------------------------------------------------------------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        /* a pad or keyboard is in use: no touch pad (not for BACK or volume, which phones have too) */
        if (touchAuto && !ev.isSystem()) touch.setVisibility(View.GONE);
        /* the quit combo (Options): hold SELECT and press START, by default (either order); the
           resume point is saved as for any quit */
        if (keymap == null) return super.dispatchKeyEvent(ev);
        if (!quitOff && (code == quitKey1 || code == quitKey2) && quitCombo(ev, code)) return true;
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
        if (ev.getAction() == KeyEvent.ACTION_DOWN) { keys |= b; synchronized (tapLock) { pressed |= b; tapFrames = 0; } }
        else if (ev.getAction() == KeyEvent.ACTION_UP) keys &= ~b;
        return true;
    }

    /** The quit combo's key events, either key first. True: the key is the combo's (not the game's). A key
     *  with no game use is always the combo's; one that is also a CHGame button (START, SELECT...) reaches the
     *  game, except the press that completes the combo (and its release). The completing press quits at once,
     *  or after 2 s held with the hold setting. */
    private boolean quitCombo(KeyEvent ev, int code) {
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN, up = ev.getAction() == KeyEvent.ACTION_UP;
        boolean first = code == quitKey1;
        int bit = first ? 1 : 2;
        if (down || up) { if (first) quitKey1Down = down; else quitKey2Down = down; }
        int gb = keymap.button(code);
        boolean owned = gb == 0;    /* only a key mapped to nothing is the combo's alone: one with a game button or an app
                                       function (menu, fast-forward, screenshot...) still does that when the combo is not made */
        if (up) {
            watch.removeCallbacks(quitLater);
            quitFired = false;      /* releasing either key re-arms the combo */
            if ((quitTaken & bit) == 0) return owned;
            quitTaken &= ~bit;      /* the combo took this press, so its release goes too */
            return true;
        }
        if (down && (first ? quitKey2Down : quitKey1Down) && ev.getRepeatCount() == 0 && !quitFired) {
            quitFired = true;
            quitTaken |= bit;
            if (quitHold) watch.postDelayed(quitLater, QUIT_HOLD_MS);
            else quit();
        }
        return (quitTaken & bit) != 0 || owned;
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
        return touch.getVisibility() == View.VISIBLE ? keymap.swapBits(touch.buttons()) : 0;
    }

    /* ---------------------------------------------------------------- */
    /* The menu: BACK, the home button on a pad, or a tap                 */
    /* ---------------------------------------------------------------- */

    private void menu() {
        if (!started || menuOpen) return;      /* one menu at a time */
        menuOpen = true;
        pauseGame();
        keys = 0;
        quitKey1Down = quitKey2Down = false;
        quitTaken = 0;
        quitFired = false;
        watch.removeCallbacks(quitLater);
        stick = 0;
        fast = false;
        rewinding = false;
        String[] items = { "Resume", "Save state…", "Load state…", "Save screenshot",
                clipOn || clipFull ? "Stop clip" : "Record clip",
                fromFrontEnd() ? "Quit" : "Quit to game list" };
        CardDialog menuDlg = new CardDialog.Builder(this)
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
                .create();
        menuDlg.show();
    }

    /** Quick save or quick load from a pad key: slot 1, as the menu's slot 1 (the game paused). */
    private void quickSlot(boolean save) {
        if (!started || menuOpen) return;
        menuOpen = true;      /* one at a time: a second press while it works does nothing */
        File f;
        try {
            f = Saves.stateFile(this, name, 1);
        } catch (java.io.IOException e) {
            toast("Bad game name");
            closeMenu();
            return;
        }
        if (!pauseGame()) { toast("The game is busy: try again"); closeMenu(); return; }    /* the machine must be still while it is copied */
        runSlot(save, f, 1);
    }

    /** A state written or read off the UI thread (flash can be slow); the game is paused meanwhile. */
    private volatile Thread slotThread;

    private void runSlot(boolean save, File f, int slot) {
        final Bitmap shot = save ? slotShot() : null;
        slotThread = new Thread(() -> {
            String msg;
            if (save) {
                boolean ok = saveStateTo(f, ".slot.tmp");
                if (ok) slotPicture(f, shot);
                msg = ok ? "Saved to slot " + slot : "Save failed (the slot keeps what it had)";
            } else {
                msg = loadSlot(f, slot);
            }
            runOnUiThread(() -> {
                if (!save) clearRewind();
                if (!isDestroyed()) toast(msg);
                if (isFinishing() || isDestroyed()) { menuOpen = false; return; }
                closeMenu();
            });
        }, "slot");
        slotThread.start();
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
        CardDialog d = new CardDialog.Builder(this)
                .setTitle(save ? "Save state" : "Load state")
                .setView(scroll)
                .setOnCancelListener(c -> { menuOpen = false; menu(); })
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
            else picture.setVisibility(View.GONE);   /* no empty gap before the text */
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
                if (!pauseGame()) { toast("The game is busy: try again"); closeMenu(); return; }    /* the machine must be still while it is copied */
                runSlot(save, f[slot], slot + 1);
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
        int[] shot;
        synchronized (screen) { shot = screen.clone(); }
        String file = Saves.base(name) + " " + stamp() + ".png";
        new Thread(() -> {
            String msg;
            File png = null;
            try {
                png = File.createTempFile("screenshot", ".png", getCacheDir());      /* one per shot: they may overlap */
                android.graphics.Bitmap b = android.graphics.Bitmap.createScaledBitmap(
                        android.graphics.Bitmap.createBitmap(shot, 128, 128, android.graphics.Bitmap.Config.ARGB_8888),
                        512, 512, false);
                try (java.io.OutputStream out = new java.io.FileOutputStream(png)) {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                String root = android.provider.DocumentsContract.getTreeDocumentId(tree);
                SnapMaker.put(getContentResolver(), tree, SnapMaker.dir(getContentResolver(), tree, root, Files.SHOTS),
                        file, "image/png", png);
                msg = "Saved CHGame screenshots/" + file;
            } catch (Exception e) {
                msg = "Screenshot not saved: " + e.getMessage();
            } finally {
                if (png != null) png.delete();
            }
            final String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
    }

    /** The screen as it is, beside a slot's state: the slot list shows it. The game is paused. */
    private Bitmap slotShot() {
        int[] px;
        synchronized (screen) { px = screen.clone(); }
        return Bitmap.createBitmap(px, 128, 128, Bitmap.Config.ARGB_8888);
    }

    private void slotPicture(File state, Bitmap shot) {
        try (java.io.OutputStream out = new java.io.FileOutputStream(Saves.pictureFile(state))) {
            shot.compress(Bitmap.CompressFormat.PNG, 100, out);
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
