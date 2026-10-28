package com.jadflat.chgame;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes ES-DE's media for a game on the device: the game runs headless (as
 * fast as it can, no buttons held) and the app writes
 *   chgame/videos/<game>.mp4       15 s from its first drawn screen: 512x512 (4x, sharp pixels),
 *                                  30 fps H.264, its sound as 48 kHz stereo AAC
 *   chgame/screenshots/<game>.png  a frame 5 s in, 512x512
 *   chgame/covers/<game>.png       the cart picture, 512x512 (the screenshot if it has none)
 * into the folder the user chose (normally ES-DE/downloaded_media). <game> is the
 * .CHG's file name without ".CHG", as ES-DE names media after the game file.
 *
 * The caller holds Preview.CORE: the core is one machine.
 */
final class SnapMaker {
    static final int SCALE = 4, SIZE = 128 * SCALE, FPS = 30, SECONDS = 15, SHOT_AT = 5;
    private static final int RATE = 48000;
    private static final long TIMEOUT_US = 10_000;

    interface Cancel { boolean cancelled(); }

    private static boolean play;        /* the video shows some play: buttons pressed after the title */

    /** The buttons held at video frame f (60 a second), for "the title, then some play": 5 s of
     *  title, then START and A to get in, then right held with A tapped (jump or fire). */
    private static int script(int f) {
        if (!play) return 0;
        double t = f / 60.0;
        if (t < 5) return 0;
        if (t < 5.15 || (t >= 7 && t < 7.15)) return Native.START;
        if ((t >= 6 && t < 6.15) || (t >= 8 && t < 8.15)) return Native.A;
        if (t < 9) return 0;
        int b = Native.RIGHT;
        if ((f / 15) % 3 == 0) b |= Native.A;
        if ((f / 90) % 4 == 3) b = Native.LEFT | ((f / 15) % 2 == 0 ? Native.A : 0);
        return b;
    }

    /** Makes the three files for one game; returns null when done, or what went wrong. */
    static String make(Context c, Uri mediaTree, Files.Entry game, String fileName, Cart cart, Cancel cancel) {
        String base = Saves.base(fileName);
        File work = new File(c.getCacheDir(), "snap");
        work.mkdirs();
        File mp4 = new File(work, "snap.mp4"), shot = new File(work, "shot.png"), cover = new File(work, "cover.png");
        mp4.delete();
        Bitmap shotBmp = null;
        try {
            File g = Files.copyGame(c, game.uri, fileName);
            File saves = new File(work, "saves");
            saves.mkdirs();
            /* no save: as the game starts the first time (with one it may sit on a continue screen) */
            File[] old = saves.listFiles();
            if (old != null) for (File o : old) o.delete();
            android.content.SharedPreferences prefs = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
            Options.apply(prefs, fileName);
            play = "play".equals(Options.snaps(prefs));
            if (!Native.start(g.getPath(), new File(c.getFilesDir(), "sys").getPath(), saves.getPath(),
                    new File(work, "log.txt").getPath()))
                return "would not start";
            try {
                shotBmp = record(mp4, cancel);
            } finally {
                Native.stop();
            }
            if (shotBmp == null) return cancel.cancelled() ? "cancelled" : "never drew its screen";
            savePng(shotBmp, shot);
            savePng(cart != null && cart.picture != null ? scale(cart.picture) : shotBmp, cover);

            ContentResolver cr = c.getContentResolver();
            String root = DocumentsContract.getTreeDocumentId(mediaTree);
            String chgame = dir(cr, mediaTree, root, "chgame");
            put(cr, mediaTree, dir(cr, mediaTree, chgame, "videos"), base + ".mp4", "video/mp4", mp4);
            put(cr, mediaTree, dir(cr, mediaTree, chgame, "screenshots"), base + ".png", "image/png", shot);
            put(cr, mediaTree, dir(cr, mediaTree, chgame, "covers"), base + ".png", "image/png", cover);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        } finally {
            mp4.delete();
            shot.delete();
            cover.delete();
        }
    }

    /* ---------------------------------------------------------------- */
    /* Recording                                                          */
    /* ---------------------------------------------------------------- */

    /** Runs the game to its first screen, then records; returns the screenshot frame (null: none). */
    private static Bitmap record(File out, Cancel cancel) throws IOException {
        int[] px = new int[128 * 128];
        short[] sound = new short[4096 * 2];
        /* up to 20 s of start-up (some games load from the card first) */
        for (int f = 0; f < 20 * 60 && !Native.screenReady(); f++) {
            if (cancel.cancelled()) return null;
            Native.frame(0, px, sound);
        }
        if (!Native.screenReady()) return null;

        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIZE, SIZE);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaCodec audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        Muxing m = new Muxing(mux);
        Bitmap shot = null;
        try {
            video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            video.start();
            audio.start();
            byte[] pcm = new byte[RATE * 4];
            int pcmLen = 0;
            long samples = 0;
            int frames = SECONDS * 60;
            for (int f = 0; f < frames; f++) {
                if (cancel.cancelled()) return null;
                int n = Native.frame(script(f), px, sound);
                for (int i = 0; i < n * 2; i++) {
                    pcm[pcmLen++] = (byte) sound[i];
                    pcm[pcmLen++] = (byte) (sound[i] >> 8);
                }
                if (f % 2 == 0) {
                    /* 30 fps: every other frame, stamped by its emulated time */
                    long pts = f * 1_000_000L / 60;
                    feedVideo(video, px, pts);
                    if (f == SHOT_AT * 60) shot = Bitmap.createBitmap(scaled(px), SIZE, SIZE, Bitmap.Config.ARGB_8888);
                }
                int took = feedAudio(audio, pcm, pcmLen, samples, false);
                samples += took / 4;
                System.arraycopy(pcm, took, pcm, 0, pcmLen - took);
                pcmLen -= took;
                drain(video, m, true, false);
                drain(audio, m, false, false);
            }
            feedAudio(audio, pcm, pcmLen, samples, true);
            endVideo(video);
            drain(video, m, true, true);
            drain(audio, m, false, true);
            return shot;
        } finally {
            try { video.stop(); } catch (RuntimeException ignored) { }
            try { audio.stop(); } catch (RuntimeException ignored) { }
            video.release();
            audio.release();
            if (m.started) {
                try { mux.stop(); } catch (RuntimeException ignored) { }
            }
            mux.release();
        }
    }

    /** The muxer starts once both tracks are known; samples before that wait. */
    private static final class Muxing {
        final MediaMuxer mux;
        int videoTrack = -1, audioTrack = -1;
        boolean started;
        final List<Object[]> waiting = new ArrayList<>();

        Muxing(MediaMuxer mux) { this.mux = mux; }

        void add(boolean isVideo, MediaFormat f) {
            if (isVideo) videoTrack = mux.addTrack(f); else audioTrack = mux.addTrack(f);
            if (videoTrack >= 0 && audioTrack >= 0) {
                mux.start();
                started = true;
                for (Object[] w : waiting)
                    mux.writeSampleData((Boolean) w[0] ? videoTrack : audioTrack, (ByteBuffer) w[1], (MediaCodec.BufferInfo) w[2]);
                waiting.clear();
            }
        }

        void write(boolean isVideo, ByteBuffer data, MediaCodec.BufferInfo info) {
            if (started) {
                mux.writeSampleData(isVideo ? videoTrack : audioTrack, data, info);
            } else {
                ByteBuffer copy = ByteBuffer.allocate(info.size);
                data.position(info.offset);
                data.limit(info.offset + info.size);
                copy.put(data);
                copy.flip();
                MediaCodec.BufferInfo i = new MediaCodec.BufferInfo();
                i.set(0, info.size, info.presentationTimeUs, info.flags);
                waiting.add(new Object[] { isVideo, copy, i });
            }
        }
    }

    private static void drain(MediaCodec codec, Muxing m, boolean isVideo, boolean toEnd) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int spins = 0; spins < 2000; spins++) {
            int i = codec.dequeueOutputBuffer(info, toEnd ? TIMEOUT_US : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!toEnd) return;
                continue;
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                m.add(isVideo, codec.getOutputFormat());
                continue;
            }
            if (i < 0) continue;
            ByteBuffer b = codec.getOutputBuffer(i);
            if (b != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) m.write(isVideo, b, info);
            codec.releaseOutputBuffer(i, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /** One 128x128 frame, 4x, into the encoder as YUV 4:2:0 (BT.601, limited range). */
    private static void feedVideo(MediaCodec codec, int[] px, long pts) {
        int i = codec.dequeueInputBuffer(TIMEOUT_US * 10);
        if (i < 0) return;      /* the encoder is behind: this frame is dropped */
        Image img = codec.getInputImage(i);
        if (img == null) {
            codec.queueInputBuffer(i, 0, 0, pts, 0);
            return;
        }
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer(), u = p[1].getBuffer(), v = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        for (int oy = 0; oy < SIZE; oy++) {
            int sy = oy / SCALE;
            for (int ox = 0; ox < SIZE; ox++) {
                int c = px[sy * 128 + ox / SCALE];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                y.put(oy * yRow + ox * yPix, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
                if ((oy & 1) == 0 && (ox & 1) == 0) {
                    int cy = oy >> 1, cx = ox >> 1;
                    u.put(cy * uRow + cx * uPix, (byte) (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                    v.put(cy * vRow + cx * vPix, (byte) (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
                }
            }
        }
        codec.queueInputBuffer(i, 0, SIZE * SIZE * 3 / 2, pts, 0);
    }

    private static void endVideo(MediaCodec codec) {
        for (int tries = 0; tries < 100; tries++) {
            int i = codec.dequeueInputBuffer(TIMEOUT_US);
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, SECONDS * 1_000_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return;
            }
        }
    }

    /** Queues what PCM fits, stamped by the samples queued before it; returns the bytes taken. */
    private static int feedAudio(MediaCodec codec, byte[] pcm, int len, long samples, boolean end) {
        int done = 0;
        while (done < len || end) {
            int i = codec.dequeueInputBuffer(end ? TIMEOUT_US * 10 : 0);
            if (i < 0) break;
            ByteBuffer b = codec.getInputBuffer(i);
            int n = Math.min(len - done, b.remaining()) & ~3;
            b.put(pcm, done, n);
            long pts = (samples + done / 4) * 1_000_000L / RATE;
            boolean last = end && done + n >= len;
            codec.queueInputBuffer(i, 0, n, pts, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            done += n;
            if (last) break;
        }
        return done;
    }

    /* ---------------------------------------------------------------- */
    /* Pictures and files                                                 */
    /* ---------------------------------------------------------------- */

    private static int[] scaled(int[] px) {
        int[] out = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) out[y * SIZE + x] = px[(y / SCALE) * 128 + x / SCALE] | 0xff000000;
        return out;
    }

    private static Bitmap scale(Bitmap b) {
        return Bitmap.createScaledBitmap(b, SIZE, SIZE, false);
    }

    private static void savePng(Bitmap b, File f) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** The folder name inside parent (made if missing); its document id. */
    static String dir(ContentResolver cr, Uri tree, String parent, String name) throws IOException {
        String found = child(cr, tree, parent, name);
        if (found != null) return found;
        Uri made = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, parent),
                Document.MIME_TYPE_DIR, name);
        if (made == null) throw new IOException("cannot make " + name);
        return DocumentsContract.getDocumentId(made);
    }

    private static String child(ContentResolver cr, Uri tree, String parent, String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        try (android.database.Cursor c = cr.query(kids, new String[] { Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            while (c != null && c.moveToNext())
                if (name.equals(c.getString(1))) return c.getString(0);
        }
        return null;
    }

    /** dir/name, replaced if it is there, from a local file. */
    static void put(ContentResolver cr, Uri tree, String dir, String name, String mime, File src) throws IOException {
        String id = child(cr, tree, dir, name);
        Uri target = id != null ? DocumentsContract.buildDocumentUriUsingTree(tree, id)
                : DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, dir), mime, name);
        if (target == null) throw new IOException("cannot write " + name);
        try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(target, "wt")) {
            if (out == null) throw new IOException("cannot write " + name);
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
    }

    private SnapMaker() {}
}
