package com.jadflat.chgame;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Map buttons, as a page: each CHGame button (and the app's own: the menu,
 * fast-forward, a second Up) with the key it is on now. Choose one, then press
 * the key you want for it. BACK cancels, and always opens the menu in a game.
 */
public class MapButtonsActivity extends Page {
    private SharedPreferences prefs;
    private String file, title;     /* set when mapping one game's own buttons */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        file = getIntent().getStringExtra("file");
        title = getIntent().getStringExtra("title");
        build();
    }

    @Override
    void build() {
        header(title != null ? "Map buttons: " + title : "Map buttons");
        Keymap k = Keymap.load(prefs, file);
        section("CHGame buttons");
        for (int i = 0; i < Keymap.BUTTONS.length; i++) {
            if (i == Keymap.FIRST_EXTRA) section("Extras");
            final int n = i;
            row(Keymap.NAMES[i], k.keyFor(Keymap.BUTTONS[i]), () -> capture(k, n));
        }
        section("");
        row("Test buttons", "Press a key to see which button it presses", () -> testButtons(k));
        row("Reset to defaults", "The d-pad, A and B, START and SELECT as labelled, the menu on the home "
                + "button, fast-forward on R1, Up also on Y (left)", () -> {
            k.defaults(false);
            k.save(prefs, file);
            build();
        });
        if (file != null)
            row("Use the main mapping", prefs.contains("keymap:" + file) ? "This game has its own buttons"
                    : "Uses the main mapping", () -> {
                Keymap.forget(prefs, file);
                build();
            });
        row("The left stick always works as the d-pad, and BACK always opens the menu", null, null);
        focus();
    }

    /** Shows the name of each key pressed and the button it presses; Done or BACK closes. */
    private void testButtons(Keymap k) {
        AlertDialog d = new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Test buttons")
                .setMessage("Press any button.")
                .setNegativeButton("Done", null)
                .create();
        d.setOnKeyListener((dialog, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK) return false;  /* lets BACK dismiss the dialog */
            if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) return true;
            int button = k.button(code);
            String pressed = "nothing";
            if (button != 0) {
                for (int i = 0; i < Keymap.BUTTONS.length; i++)
                    if (Keymap.BUTTONS[i] == button) pressed = Keymap.NAMES[i];
            }
            d.setMessage(Keymap.name(code) + " presses: " + pressed);
            return true;
        });
        d.show();
    }

    /** Waits for the next key and gives it to button i; BACK cancels. */
    private void capture(Keymap k, int i) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(Keymap.NAMES[i])
                .setMessage("Press the button for " + Keymap.NAMES[i] + ".\n\nBACK cancels.")
                .setNegativeButton("Cancel", null);
        if (i >= Keymap.FIRST_EXTRA)
            b.setNeutralButton("No key", (dialog, w) -> {
                k.clear(Keymap.BUTTONS[i]);
                k.save(prefs, file);
                build();
            });
        AlertDialog d = b.create();
        d.setOnKeyListener((dialog, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) return true;
            if (code != KeyEvent.KEYCODE_BACK) {
                k.set(code, Keymap.BUTTONS[i]);
                k.save(prefs, file);
            }
            dialog.dismiss();
            build();
            return true;
        });
        d.show();
    }
}
