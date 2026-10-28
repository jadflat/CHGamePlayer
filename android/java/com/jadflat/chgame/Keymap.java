package com.jadflat.chgame;

import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.KeyEvent;

/**
 * Which pad or keyboard key presses which CHGame button, kept in the app's
 * preferences as "keycode:button,...". One key a CHGame button, plus the menu.
 * The d-pad's directions on the left stick and the hat always work, and BACK
 * always opens the menu, so a bad mapping can't lock anyone out.
 */
final class Keymap {
    static final int MENU = 1 << 16;   /* not a CHGame button: opens the in-game menu */
    static final int FAST = 1 << 17;   /* not a CHGame button: fast-forward while held */
    static final int UP2 = 1 << 18;    /* a second key for Up */
    static final int REWIND = 1 << 19; /* not a CHGame button: rewind while held */
    static final int SHOT = 1 << 20;   /* not a CHGame button: a screenshot on key down */
    static final int QSAVE = 1 << 21;  /* not a CHGame button: quick save to slot 1 on key down */
    static final int QLOAD = 1 << 22;  /* not a CHGame button: quick load from slot 1 on key down */

    static final int[] BUTTONS = { Native.UP, Native.DOWN, Native.LEFT, Native.RIGHT,
            Native.A, Native.B, Native.START, Native.SELECT, MENU, FAST, UP2, REWIND, SHOT, QSAVE, QLOAD };
    static final String[] NAMES = { "Up", "Down", "Left", "Right", "A", "B", "START", "SELECT", "Menu",
            "Fast-forward (hold)", "Up (second button)", "Rewind (hold)", "Screenshot",
            "Quick save", "Quick load" };
    static final int FIRST_EXTRA = 8;  /* BUTTONS from here on are the app's, not the board's */
    /* Android's BUTTON_B is the right face button, BUTTON_A the bottom one */
    private static final int[] DEFAULT_KEYS = { KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_L2, 0, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR };
    /* 0: no key by default (Screenshot) */

    /** keycode -> button bit */
    final SparseIntArray map = new SparseIntArray();

    static Keymap load(SharedPreferences prefs) {
        return parse(prefs, prefs.getString("keymap", null));
    }

    /** A game's own mapping if it has one, else the main mapping. */
    static Keymap load(SharedPreferences prefs, String gameFile) {
        String s = gameFile == null ? null : prefs.getString("keymap:" + gameFile, null);
        return s != null ? parse(prefs, s) : load(prefs);
    }

    /** Reads "keycode:button,..." (null: nothing saved, so the defaults), then the migrations. */
    private static Keymap parse(SharedPreferences prefs, String s) {
        Keymap k = new Keymap();
        if (s != null) {
            for (String pair : s.split(",")) {
                String[] kv = pair.split(":");
                try {
                    if (kv.length == 2) k.set(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (k.map.size() == 0) k.defaults(prefs.getBoolean("swap", false));
        /* a mapping saved before fast-forward existed: give it R1 if R1 is free */
        if (k.map.indexOfValue(FAST) < 0 && k.map.get(KeyEvent.KEYCODE_BUTTON_R1, 0) == 0)
            k.map.put(KeyEvent.KEYCODE_BUTTON_R1, FAST);
        /* and one saved before the second Up: Y (Android's BUTTON_X), if Y is free and it wasn't cleared */
        if (k.map.indexOfValue(UP2) < 0 && !prefs.getBoolean("up2Cleared", false)
                && k.map.get(KeyEvent.KEYCODE_BUTTON_X, 0) == 0)
            k.map.put(KeyEvent.KEYCODE_BUTTON_X, UP2);
        /* and one saved before rewind existed: L2, if L2 is free */
        if (k.map.indexOfValue(REWIND) < 0 && k.map.get(KeyEvent.KEYCODE_BUTTON_L2, 0) == 0)
            k.map.put(KeyEvent.KEYCODE_BUTTON_L2, REWIND);
        /* and quick save and quick load, from before they existed: L3 and R3, if those are free */
        if (k.map.indexOfValue(QSAVE) < 0 && k.map.get(KeyEvent.KEYCODE_BUTTON_THUMBL, 0) == 0)
            k.map.put(KeyEvent.KEYCODE_BUTTON_THUMBL, QSAVE);
        if (k.map.indexOfValue(QLOAD) < 0 && k.map.get(KeyEvent.KEYCODE_BUTTON_THUMBR, 0) == 0)
            k.map.put(KeyEvent.KEYCODE_BUTTON_THUMBR, QLOAD);
        return k;
    }

    void save(SharedPreferences prefs) {
        save(prefs, null);
    }

    /** The main mapping, or a game's own when gameFile is set. */
    void save(SharedPreferences prefs, String gameFile) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < map.size(); i++) {
            if (s.length() > 0) s.append(',');
            s.append(map.keyAt(i)).append(':').append(map.valueAt(i));
        }
        if (gameFile != null) {
            prefs.edit().putString("keymap:" + gameFile, s.toString()).apply();
            return;
        }
        prefs.edit().putString("keymap", s.toString()).remove("swap")
                .putBoolean("up2Cleared", map.indexOfValue(UP2) < 0).apply();
    }

    /** Drops a game's own mapping, so it follows the main one again. */
    static void forget(SharedPreferences prefs, String gameFile) {
        prefs.edit().remove("keymap:" + gameFile).apply();
    }

    void defaults(boolean swapAB) {
        map.clear();
        for (int i = 0; i < BUTTONS.length; i++) if (DEFAULT_KEYS[i] != 0) map.put(DEFAULT_KEYS[i], BUTTONS[i]);
        if (swapAB) {
            map.put(KeyEvent.KEYCODE_BUTTON_A, Native.A);
            map.put(KeyEvent.KEYCODE_BUTTON_B, Native.B);
        }
    }

    /** The key now presses button; whatever key pressed it before no longer does. */
    void set(int keycode, int button) {
        if (keycode <= 0 || keycode == KeyEvent.KEYCODE_BACK || !valid(button)) return;
        for (int i = map.size() - 1; i >= 0; i--)
            if (map.valueAt(i) == button) map.removeAt(i);
        map.put(keycode, button);
    }

    /** No key presses the button now (only the app's extras can be cleared). */
    void clear(int button) {
        for (int i = map.size() - 1; i >= 0; i--)
            if (map.valueAt(i) == button) map.removeAt(i);
    }

    int button(int keycode) {
        return map.get(keycode, 0);
    }

    String keyFor(int button) {
        for (int i = 0; i < map.size(); i++)
            if (map.valueAt(i) == button) return name(map.keyAt(i));
        return "(none)";
    }

    /** A key's name as a Retroid labels it: Android's A/B and X/Y codes are the swapped labels. */
    static String name(int keycode) {
        switch (keycode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "D-pad up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-pad down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-pad left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-pad right";
            case KeyEvent.KEYCODE_BUTTON_A: return "B (bottom)";
            case KeyEvent.KEYCODE_BUTTON_B: return "A (right)";
            case KeyEvent.KEYCODE_BUTTON_X: return "Y (left)";
            case KeyEvent.KEYCODE_BUTTON_Y: return "X (top)";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "Home";
            case KeyEvent.KEYCODE_BUTTON_Z: return "M2";
            case KeyEvent.KEYCODE_BUTTON_C: return "M1";
        }
        String s = KeyEvent.keyCodeToString(keycode);
        s = s.startsWith("KEYCODE_") ? s.substring(8) : s;
        return s.length() > 1 ? s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ') : s;
    }

    private static boolean valid(int button) {
        for (int b : BUTTONS) if (b == button) return true;
        return false;
    }
}
