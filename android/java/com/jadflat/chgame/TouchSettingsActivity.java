package com.jadflat.chgame;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Touch controls: when they show, the layout editor, and a little haptic
 * tick on each press.
 */
public class TouchSettingsActivity extends Page {
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        build();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        build();
    }

    @Override
    void build() {
        header("Touch controls");
        section("Controls");
        int cur = 0;
        for (int i = 0; i < Options.TOUCHES.length; i++) if (Options.TOUCHES[i].equals(Options.touch(prefs))) cur = i;
        row("Show", Options.TOUCH_NAMES[cur], () -> new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle("Touch controls")
                .setSingleChoiceItems(Options.TOUCH_NAMES, cur0(), (d, i) -> {
                    d.dismiss();
                    prefs.edit().putString("touch", Options.TOUCHES[i]).apply();
                    build();
                })
                .setNegativeButton("Cancel", null).show());
        row("Edit layout", "Move and resize the buttons, and set how see-through they are",
                () -> startActivity(new Intent(this, TouchEditorActivity.class)));
        row("Vibrate on press", Options.vibrate(prefs) ? "On: a short tick when a button is pressed" : "Off", () -> {
            prefs.edit().putBoolean("vibrate", !Options.vibrate(prefs)).apply();
            build();
        });
        focus();
    }

    private int cur0() {
        for (int i = 0; i < Options.TOUCHES.length; i++) if (Options.TOUCHES[i].equals(Options.touch(prefs))) return i;
        return 0;
    }
}
