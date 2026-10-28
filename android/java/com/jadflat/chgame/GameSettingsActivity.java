package com.jadflat.chgame;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * One game's own settings, over the ones in Options: its sound style and
 * volume (some games suit the piezo sound, some the bare pin). "Same as
 * Options" keeps the game following Options.
 */
public class GameSettingsActivity extends Page {
    private SharedPreferences prefs;
    private String file, title;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        file = getIntent().getStringExtra("file");
        title = getIntent().getStringExtra("title");
        if (file == null) { finish(); return; }
        if (title == null) title = Saves.base(file);
        build();
    }

    @Override
    void build() {
        header("Settings: " + title);
        section("Sound");
        String sound = Options.gameSound(prefs, file), volume = Options.gameVolume(prefs, file);
        row("Sound", sound == null ? "Same as Options (" + Options.SOUND_NAMES[indexOf(Options.SOUNDS, Options.sound(prefs))] + ")"
                : Options.SOUND_NAMES[indexOf(Options.SOUNDS, sound)], () -> {
            String[] items = new String[Options.SOUNDS.length + 1];
            items[0] = "Same as Options";
            for (int i = 0; i < Options.SOUNDS.length; i++) items[i + 1] = Options.SOUND_NAMES[i];
            pick("Sound for " + title, items, sound == null ? 0 : indexOf(Options.SOUNDS, sound) + 1,
                    i -> Options.setGame(prefs, file, "sound", i == 0 ? null : Options.SOUNDS[i - 1]));
        });
        row("Volume", volume == null ? "Same as Options (" + Options.volume(prefs) + ")" : volume, () -> {
            String[] items = new String[Options.VOLUMES.length + 1];
            items[0] = "Same as Options";
            System.arraycopy(Options.VOLUMES, 0, items, 1, Options.VOLUMES.length);
            pick("Volume for " + title, items, volume == null ? 0 : indexOf(Options.VOLUMES, volume) + 1,
                    i -> Options.setGame(prefs, file, "volume", i == 0 ? null : Options.VOLUMES[i - 1]));
        });
        if (sound != null || volume != null)
            row("Back to the Options settings", null, () -> {
                Options.setGame(prefs, file, "sound", null);
                Options.setGame(prefs, file, "volume", null);
                build();
            });
        section("Buttons");
        row("Buttons", prefs.contains("keymap:" + file) ? "Own mapping" : "The main mapping", () ->
                startActivity(new Intent(this, MapButtonsActivity.class).putExtra("file", file).putExtra("title", title)));
        focus();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        build();
    }

    interface IntCallback { void run(int i); }

    private void pick(String t, String[] items, int current, IntCallback cb) {
        new AlertDialog.Builder(this, Ui.dialogTheme()).setTitle(t)
                .setSingleChoiceItems(items, current, (d, i) -> { d.dismiss(); cb.run(i); build(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return 0;
    }
}
