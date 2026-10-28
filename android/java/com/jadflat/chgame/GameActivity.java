package com.jadflat.chgame;

import android.app.Activity;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
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

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        view.setOnClickListener(v -> menu());
        setContentView(view);
        keymap = Keymap.load(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
        fill = Options.fill(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
        String t = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("tree", null);
        tree = t == null ? null : Uri.parse(t);
        /* "game" + "name": a document from the picked folder (MainActivity);
           "file": a game LaunchActivity already copied into the private games folder */
        final String game = getIntent().getStringExtra("game");
        final String copied = getIntent().getStringExtra("file");
        name = copied != null ? copied : getIntent().getStringExtra("name");
        title = getIntent().getStringExtra("title");
        if (name == null || (game == null && copied == null)) { finish(); return; }

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
        if (!autosave) return;
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
    }

    private void pauseGame() {
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
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, 800 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        int[] px = new int[128 * 128];
        short[] sound = new short[4096 * 2];
        Bitmap bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setFilterBitmap(false);
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        boolean grid = "grid".equals(Options.look(sp));
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
        Rect src = new Rect(0, 0, 128, 128);
        while (running) {
            /* fast-forward: several frames for each one shown, and only the last one's sound */
            int n = 0;
            for (int k = fast ? FAST_SPEED : 1; k > 0; k--) {
                int tap = pressed;
                if (tap != 0 && ++tapFrames >= TAP_FRAMES) {
                    pressed = 0;
                    tapFrames = 0;
                }
                n = Native.frame(keys | stick | tap, px, sound);
            }
            bmp.setPixels(px, 0, 128, 0, 0, 128, 128);
            System.arraycopy(px, 0, screen, 0, px.length);
            SurfaceHolder h = holder;
            Canvas c = h == null ? null : h.lockCanvas();
            if (c != null) {
                /* the biggest whole-number scale that fits (or, with fill, the whole height), centred */
                int side = Math.min(c.getWidth(), c.getHeight());
                if (!fill) side = Math.max(1, side / 128) * 128;
                int x = (c.getWidth() - side) / 2, y = (c.getHeight() - side) / 2;
                c.drawColor(Color.BLACK);
                c.drawBitmap(bmp, src, new Rect(x, y, x + side, y + side), paint);
                if (grid && side >= 128 * 3) {
                    /* the LCD look: a thin dark line between pixels */
                    float step = side / 128f;
                    for (int i = 1; i < 128; i++) {
                        float o = i * step;
                        c.drawLine(x + o, y, x + o, y + side, lines);
                        c.drawLine(x, y + o, x + side, y + o, lines);
                    }
                }
                h.unlockCanvasAndPost(c);
            }
            if (n > 0) track.write(sound, 0, n * 2);    /* blocks: the pace of the game */
        }
        track.stop();
        track.release();
    }

    @Override public void surfaceCreated(SurfaceHolder h) { holder = h; if (hasWindowFocus()) resumeGame(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { holder = h; }
    @Override public void surfaceDestroyed(SurfaceHolder h) { pauseGame(); holder = null; }

    /* ---------------------------------------------------------------- */
    /* Input                                                              */
    /* ---------------------------------------------------------------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
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
        if (code == KeyEvent.KEYCODE_BACK || b == Keymap.MENU) {
            if (ev.getAction() == KeyEvent.ACTION_UP) menu();
            return true;
        }
        if (b == Keymap.FAST) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) fast = true;
            else if (ev.getAction() == KeyEvent.ACTION_UP) fast = false;
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
        String[] items = { "Resume", "Save state…", "Load state…", "Save screenshot",
                fromFrontEnd() ? "Quit" : "Quit to game list" };
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(title != null ? title : Saves.base(name))
                .setItems(items, (d, which) -> {
                    if (which == 1 || which == 2) {
                        slots(which == 1);
                        return;
                    }
                    if (which == 3) saveScreenshot();
                    if (which == 4) quit();
                    else closeMenu();
                })
                .setOnCancelListener(d -> closeMenu())
                .show();
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
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(save ? "Save state" : "Load state")
                .setItems(items, (d, i) -> {
                    pauseGame();    /* the machine must be still while it is copied */
                    if (save) {
                        toast(Native.saveState(f[i].getPath()) ? "Saved to slot " + (i + 1) : "Save failed");
                    } else {
                        toast(f[i].isFile() && Native.loadState(f[i].getPath())
                                ? "Loaded slot " + (i + 1) : "Slot " + (i + 1) + " is empty");
                    }
                    closeMenu();
                })
                .setOnCancelListener(d -> menu())
                .show();
    }

    /** The screen as it was when the menu opened, 512x512 with sharp pixels, into
     *  the games folder's screenshots/ ("<game> 2026-10-28 14-03-07.png"). */
    private void saveScreenshot() {
        if (tree == null) {
            toast("Choose a games folder first (Options)");
            return;
        }
        int[] shot = screen.clone();
        String file = Saves.base(name) + " " + new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss",
                java.util.Locale.ROOT).format(new java.util.Date()) + ".png";
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
                SnapMaker.put(getContentResolver(), tree, SnapMaker.dir(getContentResolver(), tree, root, "screenshots"),
                        file, "image/png", png);
                png.delete();
                msg = "Saved screenshots/" + file;
            } catch (Exception e) {
                msg = "Screenshot not saved: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> toast(m));
        }).start();
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
