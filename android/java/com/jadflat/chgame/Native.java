package com.jadflat.chgame;

/** The CHGame core (libchgame.so): the same libretro core as RetroArch's, driven directly. */
final class Native {
    static { System.loadLibrary("chgame"); }

    /* buttons for frame(): 1 << RETRO_DEVICE_ID_JOYPAD_* */
    static final int B = 1, SELECT = 1 << 2, START = 1 << 3, UP = 1 << 4, DOWN = 1 << 5,
            LEFT = 1 << 6, RIGHT = 1 << 7, A = 1 << 8;

    /** The core takes RetroArch's B (south) as the board's A and RetroArch's A (east) as the board's B
     *  (libretro.c), so the app's A and B bits trade places on the way in. */
    static int toCore(int b) {
        return (b & ~(A | B)) | ((b & A) != 0 ? B : 0) | ((b & B) != 0 ? A : 0);
    }

    /** A core option: chgame_sound (bare pin|piezo), chgame_volume (10%..150%). */
    static native void setOption(String key, String value);
    /** game: a .CHG file; sys: the folder holding chgame/sdcard; saves: where game.sav goes */
    static native boolean start(String game, String sys, String saves, String log);
    /** One 1/60 s frame: 128x128 ARGB into pixels, 48 kHz stereo into sound; returns the samples. */
    static native int frame(int buttons, int[] pixels, short[] sound);
    static native void flush();
    /** The game has the screen on and has drawn a whole frame; until then it is blank white. */
    static native boolean screenReady();
    /** SD blocks the game has read so far; a count that stops rising means loading is done. */
    static native long sdReads();
    static native boolean saveState(String path);
    static native boolean loadState(String path);
    /** The state as bytes (reuse: an old array of the right size, filled in place); null when there is none. */
    static native byte[] saveStateBytes(byte[] reuse);
    static native boolean loadStateBytes(byte[] state);
    static native void stop();
    /** Starts a GIF recording of the 128x128 screen; a running one is dropped first. */
    static native boolean gifBegin();
    /** Adds the 128x128 ARGB screen (alpha ignored), shown for 'seconds' since the last frame. */
    static native void gifFrame(int[] argb, double seconds);
    /** Finishes the recording and writes it to path; false if none was running or the write failed. */
    static native boolean gifEnd(String path);
    /** Drops the running GIF recording, if any. */
    static native void gifAbort();

    private Native() {}
}
