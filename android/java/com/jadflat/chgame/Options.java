package com.jadflat.chgame;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * The app's options, in its private preferences (OptionsActivity is the page
 * that changes them). Sound and volume are the core's own options
 * (chgame_sound, chgame_volume), handed to it before a game starts.
 */
final class Options {
    static final String[] SOUNDS = { "bare pin", "piezo" };
    static final String[] SOUND_NAMES = { "Bare pin (as the emulator)", "Piezo (like the speaker)" };
    static final String[] VOLUMES = { "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "100%", "120%", "150%" };
    static final String[] RESUMES = { "ask", "always", "off" };
    static final String[] RESUME_NAMES = { "Ask each time", "Always continue", "Off (always start fresh, no resume point)" };

    static String sound(SharedPreferences p) { return p.getString("sound", SOUNDS[0]); }
    static String volume(SharedPreferences p) { return p.getString("volume", "50%"); }
    static final String[] AUDIO_BUFFERS = { "low", "normal", "high" };
    static final String[] AUDIO_BUFFER_NAMES = { "Low latency (may crackle)", "Normal", "High (smoothest)" };
    /** low, normal or high: the sound card's buffer (low answers quickest, high is safest from crackles) */
    static String audioBuffer(SharedPreferences p) { return p.getString("audioBuffer", "normal"); }
    /** false: the biggest whole-number scale (sharp, even pixels); true: as tall as the screen */
    static boolean fill(SharedPreferences p) { return p.getBoolean("fill", false); }
    static boolean showHidden(SharedPreferences p) { return p.getBoolean("showHidden", false); }
    /** ask, always or off: continuing a game from where it was left */
    static String resume(SharedPreferences p) { return p.getString("resume", "ask"); }
    static final String[] PREVIEWS = { "sound", "video", "off" };
    static final String[] PREVIEW_NAMES = { "Video and sound", "Video only", "Off (the cart picture)" };
    /** sound, video or off: the selected game running in the list */
    static String preview(SharedPreferences p) { return p.getString("preview", "sound"); }

    static final String[] PREVIEW_MODES = { "live", "video", "picture" };
    static final String[] PREVIEW_MODE_NAMES = { "Live (the game runs, emulated)", "Video (the ES-DE clip, falling back to live)", "Picture only" };
    /** live, video or picture: what the list's preview shows */
    static String previewMode(SharedPreferences p) { return p.getString("previewMode", "live"); }
    /** The mode for one game: its own (pref preview:<file>) when set, else the global one. */
    static String previewMode(SharedPreferences p, String file) {
        String g = p.getString("preview:" + file, null);
        return g != null ? g : previewMode(p);
    }

    /** off (default) or on: a soft click as the highlight moves, a blip on A */
    static boolean menuSounds(SharedPreferences p) { return p.getBoolean("menuSounds", false); }

    static final String[] SORTS = { "name", "played", "added" };
    static final String[] SORT_NAMES = { "By name", "Most played", "Recently added" };
    /** name, played or added: the order of games inside each section of the list */
    static String sort(SharedPreferences p) { return p.getString("sort", "name"); }

    static Set<String> favourites(SharedPreferences p) {
        return new HashSet<>(p.getStringSet("favs", new HashSet<>()));
    }

    /** Stars the game at path, or takes its star away; returns whether it is now a favourite. */
    static boolean toggleFavourite(SharedPreferences p, String path) {
        Set<String> f = favourites(p);
        boolean now = !f.remove(path);
        if (now) f.add(path);
        p.edit().putStringSet("favs", f).apply();
        return now;
    }

    static Set<String> hidden(SharedPreferences p) {
        return new HashSet<>(p.getStringSet("hidden", new HashSet<>()));
    }

    /** Hides the game at path (relative, in the games folder), or shows it again; returns whether it is now hidden. */
    static boolean toggleHidden(SharedPreferences p, String path) {
        Set<String> h = hidden(p);
        boolean now = !h.remove(path);
        if (now) h.add(path);
        p.edit().putStringSet("hidden", h).apply();
        return now;
    }

    /* a game's own settings, over Options' (null: the game follows Options) */
    static String gameSound(SharedPreferences p, String file) { return p.getString("g:" + file + ":sound", null); }
    static String gameVolume(SharedPreferences p, String file) { return p.getString("g:" + file + ":volume", null); }

    static void setGame(SharedPreferences p, String file, String key, String value) {
        if (value == null) p.edit().remove("g:" + file + ":" + key).apply();
        else p.edit().putString("g:" + file + ":" + key, value).apply();
    }

    /** The core's options for the next game: the game's own, else Options'. */
    static void apply(SharedPreferences p, String file) {
        String s = file != null ? gameSound(p, file) : null, v = file != null ? gameVolume(p, file) : null;
        Native.setOption("chgame_sound", s != null ? s : sound(p));
        Native.setOption("chgame_volume", v != null ? v : volume(p));
    }

    static final String[] LOOKS = { "sharp", "grid", "lcd", "scanlines", "crt" };
    static final String[] LOOK_NAMES = { "Sharp pixels", "Pixel grid (thin dark lines between pixels)",
            "LCD (a faint pixel grid and slight ghosting, like a handheld's screen)",
            "Scanlines (dark lines between pixel rows)", "CRT (scanlines, a soft glow and darker corners)" };
    /** sharp, grid, lcd, scanlines or crt: how the game's screen is drawn */
    static String look(SharedPreferences p) { return p.getString("look", "sharp"); }
    /** true: the game list as a grid of big cart pictures instead of rows */
    static boolean gridList(SharedPreferences p) { return p.getBoolean("gridList", false); }
    /** true: the game list has one collapsible group a developer (the board and card games group nests in bateske's) */
    static boolean groupByDeveloper(SharedPreferences p) { return p.getBoolean("groupByDeveloper", false); }
    /** true (default): the "Board and card games" group is in the list; false: left out */
    static boolean boardCards(SharedPreferences p) { return p.getBoolean("boardCards", true); }
    /** true: colours toned like the CHGame's own panel (a little less saturated) */
    static boolean panelColours(SharedPreferences p) { return p.getBoolean("panel", false); }

    static final String[] TOUCHES = { "auto", "on", "off" };
    static final String[] TOUCH_NAMES = { "Automatic: hidden once a gamepad is used", "Always", "Off" };
    /** auto, on or off: the on-screen controls (auto hides them once a gamepad button is pressed) */
    static String touch(SharedPreferences p) { return p.getString("touch", "off"); }
    /** 20 to 100: how solid the touch controls are */
    static int touchOpacity(SharedPreferences p) { return Math.max(20, Math.min(100, p.getInt("touchOpacity", 100))); }
    /** true: a short haptic tick when a touch button goes down */
    static boolean vibrate(SharedPreferences p) { return p.getBoolean("vibrate", false); }
    /** true: a CHGame-shaped frame beside the picture */
    static boolean bezel(SharedPreferences p) { return p.getBoolean("bezel", false); }
    /** true: the speed overlay (fps, % of real speed and frame time) */
    static boolean overlay(SharedPreferences p) { return p.getBoolean("overlay", false); }

    /** The quit combo: two keys held or pressed in turn (M2 then START by default), held 2 s or not, or off. */
    static int quitKey1(SharedPreferences p) { return p.getInt("quitKey1", android.view.KeyEvent.KEYCODE_BUTTON_Z); }
    static int quitKey2(SharedPreferences p) { return p.getInt("quitKey2", android.view.KeyEvent.KEYCODE_BUTTON_START); }
    static boolean quitHold(SharedPreferences p) { return p.getBoolean("quitHold", false); }
    static boolean quitOff(SharedPreferences p) { return p.getBoolean("quitOff", false); }

    static final String[] SNAPS = { "title", "play" };
    static final String[] SNAP_NAMES = { "The title screen", "The title, then some play (presses START and A)" };
    /** title or play: what the ES-DE videos show */
    static String snaps(SharedPreferences p) { return p.getString("snaps", "play"); }

    private Options() {}
}
