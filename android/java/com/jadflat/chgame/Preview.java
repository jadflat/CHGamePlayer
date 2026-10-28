package com.jadflat.chgame;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The game list's live preview: the selected game running in the picture's
 * place, with its sound, like an arcade's attract mode. It starts after the
 * selection has rested a moment, runs on its own thread with no buttons held,
 * and starts with no save, the way the game first starts (with the player's
 * save some games sit on a continue screen); the real save is never touched.
 *
 * The core is one machine: CORE holds it, so a game (GameActivity) only starts
 * once the preview has let go, and a preview never starts while a game runs.
 */
final class Preview {
    static final Object CORE = new Object();
    static volatile boolean gameRunning;
    private static final long DELAY_MS = 1000;

    interface Screen { void frame(Bitmap b); }

    private final Activity a;
    private final Screen screen;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private volatile int session;           /* bumped by every stop: a running preview checks it */
    private volatile boolean soundOn;       /* the mute button changes it while a preview runs */
    private Runnable pending;

    Preview(Activity a, Screen screen) {
        this.a = a;
        this.screen = screen;
    }

    /** Previews this game once the selection has rested; any running preview stops now. */
    void schedule(Files.Entry e, String fileName) {
        stop();
        SharedPreferences p = a.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
        if ("off".equals(Options.preview(p))) return;
        final int s = session;
        soundOn = "sound".equals(Options.preview(p));
        pending = () -> runner.execute(() -> run(e, fileName, s));
        ui.postDelayed(pending, DELAY_MS);
    }

    /** The mute button: the running preview's sound on or off, at once. */
    void setSound(boolean on) {
        soundOn = on;
    }

    /** Stops the preview (it finishes on its own thread; CORE says when the core is free). */
    void stop() {
        session++;
        if (pending != null) ui.removeCallbacks(pending);
        pending = null;
    }

    void shutdown() {
        stop();
        runner.shutdown();
    }

    private void run(Files.Entry e, String fileName, int s) {
        synchronized (CORE) {
            if (s != session || gameRunning) return;
            File game, saves;
            try {
                game = Files.copyGame(a, e.uri, fileName);
                /* an empty save folder: the game starts as it does the first time, and
                   what it writes stays here */
                saves = new File(a.getCacheDir(), "preview");
                saves.mkdirs();
                File[] old = saves.listFiles();
                if (old != null) for (File o : old) o.delete();
            } catch (Exception ex) {
                return;
            }
            if (s != session) return;
            SharedPreferences p = a.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
            Options.apply(p, fileName);
            if (!Native.start(game.getPath(), new File(a.getFilesDir(), "sys").getPath(), saves.getPath(),
                    new File(a.getCacheDir(), "preview.log").getPath()))
                return;
            int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, 800 * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            boolean sound = soundOn;
            track.setVolume(sound ? 0.7f : 0f);     /* silent, it still paces the frames */
            track.play();
            int[] px = new int[128 * 128];
            short[] buf = new short[4096 * 2];
            Bitmap[] bmp = { Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888) };
            int which = 0;
            boolean ready = false;
            while (s == session && !gameRunning) {
                int n = Native.frame(0, px, buf);
                /* the cart picture stays until the game has drawn its first frame: before
                   that the panel is blank white */
                if (ready || (ready = Native.screenReady())) {
                    Bitmap b = bmp[which ^= 1];
                    b.setPixels(px, 0, 128, 0, 0, 128, 128);
                    ui.post(() -> { if (s == session) screen.frame(b); });
                }
                if (sound != soundOn) {
                    sound = soundOn;
                    track.setVolume(sound ? 0.7f : 0f);
                }
                if (n > 0) track.write(buf, 0, n * 2);
            }
            track.pause();
            track.flush();
            track.release();
            Native.stop();
        }
    }
}
