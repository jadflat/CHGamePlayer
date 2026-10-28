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

    static final int[] BUTTONS = { Native.UP, Native.DOWN, Native.LEFT, Native.RIGHT,
            Native.A, Native.B, Native.START, Native.SELECT, MENU, FAST };
    static final String[] NAMES = { "Up", "Down", "Left", "Right", "A", "B", "START", "SELECT", "Menu", "Fast-forward (hold)" };
    /* Android's BUTTON_B is the right face button, BUTTON_A the bottom one */
    private static final int[] DEFAULT_KEYS = { KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_R1 };

    /** keycode -> button bit */
    final SparseIntArray map = new SparseIntArray();

    static Keymap load(SharedPreferences prefs) {
        Keymap k = new Keymap();
        String s = prefs.getString("keymap", null);
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
        return k;
    }

    void save(SharedPreferences prefs) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < map.size(); i++) {
            if (s.length() > 0) s.append(',');
            s.append(map.keyAt(i)).append(':').append(map.valueAt(i));
        }
        prefs.edit().putString("keymap", s.toString()).remove("swap").apply();
    }

    void defaults(boolean swapAB) {
        map.clear();
        for (int i = 0; i < BUTTONS.length; i++) map.put(DEFAULT_KEYS[i], BUTTONS[i]);
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

    int button(int keycode) {
        return map.get(keycode, 0);
    }

    String keyFor(int button) {
        for (int i = 0; i < map.size(); i++)
            if (map.valueAt(i) == button) return name(map.keyAt(i));
        return "(none)";
    }

    static String name(int keycode) {
        String s = KeyEvent.keyCodeToString(keycode);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }

    private static boolean valid(int button) {
        for (int b : BUTTONS) if (b == button) return true;
        return false;
    }
}
