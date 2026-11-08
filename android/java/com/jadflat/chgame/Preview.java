package com.jadflat.chgame;

import android.app.Activity;
import android.content.Context;
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
    /** A game is playing (GameActivity, its only writer): no one else may run the core, not even holding CORE,
        because the game's loop runs frames without it. */
    static volatile boolean gameActive;
    private static final java.util.concurrent.atomic.AtomicInteger JOBS = new java.util.concurrent.atomic.AtomicInteger();

    /** Picture/media jobs that run the core (each holds CORE while it does): one count each, so one ending never
        clears another's. */
    static void jobStart() { JOBS.incrementAndGet(); }
    static void jobEnd() { JOBS.decrementAndGet(); }

    /** The core is wanted elsewhere: a game is playing or a job runs. The preview stops and starts nothing. */
    static boolean busy() { return gameActive || JOBS.get() > 0; }
    private static final long DELAY_MS = 150;

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
    void schedule(Files.Entry e, String fileName, boolean video) {
        stop();
        SharedPreferences p = a.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
        if ("off".equals(Options.preview(p))) return;
        final int s = session;
        soundOn = "sound".equals(Options.preview(p));
        pending = () -> runner.execute(() -> { if (s != session) return; syncCard(e, fileName); if (s != session) return; if (!(video && playVideo(fileName, s))) run(e, fileName, s); });
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

    /** Plays the game's ES-DE clip (looping, with its sound unless muted) in the preview's place;
     *  false when there is none (the caller falls back to the live preview). Runs until the selection moves. */
    private boolean playVideo(String fileName, int s) {
        if (s != session || busy()) return true;
        File clip = new File(a.getCacheDir(), "previewclip.mp4");
        try {
            String m = a.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE).getString("mediaTree", null);
            if (m == null) return false;
            android.net.Uri tree = android.net.Uri.parse(m);
            android.content.ContentResolver cr = a.getContentResolver();
            String root = android.provider.DocumentsContract.getTreeDocumentId(tree);
            String d = SnapMaker.child(cr, tree, root, "chgame");
            d = d == null ? null : SnapMaker.child(cr, tree, d, "videos");
            String id = d == null ? null : SnapMaker.child(cr, tree, d, Saves.base(fileName) + ".mp4");
            if (id == null) return false;
            try (java.io.InputStream in = cr.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id));
                 java.io.OutputStream out = new java.io.FileOutputStream(clip)) {
                if (in == null) return false;
                byte[] b = new byte[1 << 16];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
        } catch (Exception ex) {
            return false;
        }
        if (s != session) return true;
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        try {
            r.setDataSource(clip.getPath());
            String ds = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = ds == null ? 0 : Long.parseLong(ds);
            if (durMs <= 0) return false;
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(clip.getPath());
            mp.setLooping(true);
            mp.prepare();
            boolean sound = soundOn;
            mp.setVolume(sound ? 0.7f : 0f, sound ? 0.7f : 0f);
            mp.start();
            Bitmap last = null;
            /* Android 9+: frames decoded in order, a few at a time (cheap); before, each one looked up by time */
            int count = 0;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                String fc = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                try { count = fc == null ? 0 : Integer.parseInt(fc); } catch (NumberFormatException ignored) { }
            }
            java.util.List<Bitmap> chunk = null;
            int chunkAt = -1;
            while (s == session && !busy()) {
                if (sound != soundOn) {
                    sound = soundOn;
                    mp.setVolume(sound ? 0.7f : 0f, sound ? 0.7f : 0f);
                }
                long pos = Math.min(mp.getCurrentPosition(), durMs - 1);
                Bitmap f;
                if (count > 0) {
                    int idx = (int) Math.min(count - 1, pos * count / durMs);
                    if (chunk == null || idx < chunkAt || idx >= chunkAt + chunk.size()) {
                        android.media.MediaMetadataRetriever.BitmapParams bp = new android.media.MediaMetadataRetriever.BitmapParams();
                        bp.setPreferredConfig(Bitmap.Config.RGB_565);
                        chunk = r.getFramesAtIndex(idx, Math.min(8, count - idx), bp);
                        chunkAt = idx;
                    }
                    if (chunk.isEmpty()) { count = 0; continue; }     /* cannot decode by index: use the time path */
                    f = chunk.isEmpty() ? null : chunk.get(Math.min(idx - chunkAt, chunk.size() - 1));
                } else {
                    f = r.getFrameAtTime(pos * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (f != null && f != last) {
                    last = f;
                    final Bitmap fb = f;
                    ui.post(() -> { if (s == session) screen.frame(fb); });
                }
                try { Thread.sleep(15); } catch (InterruptedException ie) { break; }
            }
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            try { mp.stop(); } catch (Exception ignored) { }
            mp.release();
            try { r.release(); } catch (Exception ignored) { }
            clip.delete();
        }
    }

    /** The card files the game needs, fetched into the games folder if missing or out of date (quiet; once per version). */
    private void syncCard(Files.Entry e, String fileName) {
        try {
            SharedPreferences p = a.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
            String t = p.getString("tree", null);
            if (t == null) return;
            Cart c = Cart.read(a.getContentResolver(), e.uri);
            Updates.syncData(a, p, android.net.Uri.parse(t), fileName, c == null ? null : c.dats);
        } catch (Exception ignored) { }
    }

    /** Games that wait on an intro for a key: ones with no cart picture, and Press Play On Tape's (by credit, or a repo of theirs or forked from theirs). */
    static boolean needsTap(Context c, Cart cart, String fileName) {
        if (cart == null || cart.picture == null) return true;
        String who = (cart.credit() + " " + cart.fullAuthor + " " + cart.portedFrom + " " + cart.porter).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (who.contains("pressplayontape") || who.contains("filmote")) return true;
        try {
            SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean("intro:" + fileName.toUpperCase(java.util.Locale.ROOT), false)) return true;     /* a fork of theirs (Updates.markIntro) */
            String repo = Updates.repoFor(c, p, fileName);
            return repo != null && repo.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z/]", "").matches("pressplayontape/.*|.*/.*astarok.*");
        } catch (Exception ex) { return false; }
    }

    private void run(Files.Entry e, String fileName, int s) {
        synchronized (CORE) {
            if (s != session || busy()) return;
            File game, saves, sys;
            try {
                game = Files.copyGame(a, e.uri, fileName);
                Files.importData(a);      /* the card files the game needs (importCard retries a missed one) */
                /* an empty save folder: the game starts as it does the first time, and
                   what it writes stays here */
                saves = new File(a.getCacheDir(), "preview");
                saves.mkdirs();
                File[] old = saves.listFiles();
                if (old != null) for (File o : old) o.delete();
                /* its own card too: the real one hard-linked (no copying), so what the game writes in the
                   preview never reaches the player's card (the core replaces files, it never writes in place) */
                sys = new File(a.getCacheDir(), "preview-sys");
                wipe(sys);
                mirror(new File(a.getFilesDir(), "sys"), sys);
            } catch (Exception ex) {
                return;
            }
            if (s != session) return;
            SharedPreferences p = a.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
            Options.apply(p, fileName);
            if (!Native.start(game.getPath(), sys.getPath(), saves.getPath(),
                    new File(a.getCacheDir(), "preview.log").getPath()))
                return;
            /* run unseen and silent, flat out, until the screen is ready and the card has been
               idle for 2 s of emulated time (30 s cap): a game that streams from the SD card
               shows its loading screen first (as SnapMaker does) */
            {
                int[] wpx = new int[128 * 128];
                short[] wbuf = new short[4096 * 2];
                int f = 0, quiet = 0;
                long reads = -1;
                boolean scr = false;
                while (f < 50 * 60 && s == session && !busy()) {
                    Native.frame(0, wpx, wbuf);
                    f++;
                    if (!scr) {
                        if (!(scr = Native.screenReady())) continue;
                        reads = Native.sdReads();
                        if (reads <= 0) break;
                        f = 0;
                        continue;
                    }
                    long r = Native.sdReads();
                    quiet = r == reads ? quiet + 1 : 0;
                    reads = r;
                    if (quiet >= 2 * 60 || f >= 30 * 60) break;
                }
                if (s != session || busy()) {
                    Native.stop();
                    return;
                }
            }
            int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track;
            try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, 800 * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            } catch (RuntimeException ex) { Native.stop(); return; }
            boolean sound = soundOn;
            track.setVolume(sound ? 0.7f : 0f);     /* silent, it still paces the frames */
            track.play();
            int[] px = new int[128 * 128];
            short[] buf = new short[4096 * 2];
            /* three: the one being drawn, the one posted behind it, and the one filled next */
            Bitmap[] bmp = { Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888) };
            int which = 0;
            boolean ready = false;
            /* a game with no cart picture of its own may wait on its title screen for a key (poevoid's Astarok):
               A is tapped twice, 2 s and 3.5 s in, as for its picture (SnapMaker.frame) */
            Cart cart = Cart.read(a.getContentResolver(), e.uri);
            boolean press = needsTap(a, cart, fileName);
            int shown = 0;
            try {
            while (s == session && !busy()) {
                boolean tap = press && (shown >= 120 && shown < 126 || shown >= 210 && shown < 216);
                if (shown < 300) shown++;
                int n = Native.frame(tap ? Native.B : 0, px, buf);
                /* the cart picture stays until the game has drawn its first frame: before
                   that the panel is blank white */
                if (ready || (ready = Native.screenReady())) {
                    Bitmap b = bmp[which = (which + 1) % 3];
                    b.setPixels(px, 0, 128, 0, 0, 128, 128);
                    ui.post(() -> { if (s == session) screen.frame(b); });
                }
                if (sound != soundOn) {
                    sound = soundOn;
                    track.setVolume(sound ? 0.7f : 0f);
                }
                if (n > 0) GameActivity.pace(track, buf, n * 2);
            }
            } finally {
            try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
            track.release();
            Native.stop();
            }
        }
    }

    private static void wipe(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File c : in) wipe(c);
        f.delete();
    }

    /** A throwaway card for a run that is not the player's (SnapMaker's pictures and clips): <cache>/<name>, the real
        card hard-linked into it, so what the game writes there never reaches the player's card (the core replaces
        files, it never writes in place). Caller holds CORE. */
    static File scratchSys(android.content.Context c, String name) throws java.io.IOException {
        File sys = new File(c.getCacheDir(), name);
        wipe(sys);
        mirror(new File(c.getFilesDir(), "sys"), sys);
        return sys;
    }

    /** dst as a tree of hard links to src's files (a copy where a link fails). */
    private static void mirror(File src, File dst) throws java.io.IOException {
        dst.mkdirs();
        File[] in = src.listFiles();
        if (in == null) return;
        for (File c : in) {
            File d = new File(dst, c.getName());
            if (c.isDirectory()) { mirror(c, d); continue; }
            try {
                if (c.length() < (1 << 20)) throw new android.system.ErrnoException("small", 0);   /* small files (saves, config) are copied: a core writing in place must not reach the real card */
                android.system.Os.link(c.getPath(), d.getPath());
            } catch (android.system.ErrnoException e) {
                java.nio.file.Files.copy(c.toPath(), d.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
