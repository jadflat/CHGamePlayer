package com.jadflat.chgame;

import android.content.UriPermission;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.io.IOException;

/**
 * One game's saves, as a page: the game's own save (what it wrote to the
 * CHGame's flash) with export, import and delete, then the 4 save-state slots
 * and the resume point. States are saved and loaded in the game's menu; here
 * they can be seen and deleted. Everything that deletes asks first.
 */
public class SavesActivity extends Page {
    private String file, title;
    private File sav, auto;
    private final File[] slots = new File[Saves.SLOTS];

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        file = getIntent().getStringExtra("file");
        title = getIntent().getStringExtra("title");
        if (file == null) { finish(); return; }
        if (title == null) title = Saves.base(file);
        try {
            sav = Saves.saveFile(this, file);
            auto = Saves.autoFile(this, file);
            for (int i = 0; i < Saves.SLOTS; i++) slots[i] = Saves.stateFile(this, file, i + 1);
        } catch (IOException e) {
            finish();
            return;
        }
        build();
    }

    @Override
    void build() {
        header("Saves: " + title);
        String base = Saves.base(file);
        Uri tree = tree();

        section("Game save");
        row("The game's own save", sav.isFile() ? "Saved " + Saves.describe(sav) + " · " + sav.length() + " bytes"
                : "None yet: the game hasn't saved anything", null);
        if (tree != null) {
            if (sav.isFile())
                row("Export to the games folder", "Writes " + base + ".sav into your games folder: a backup, "
                        + "or to use it in RetroArch (same format)", () -> {
                    Saves.toast(this, Saves.exportSave(this, tree, sav) ? "Exported " + sav.getName() : "Not exported");
                    build();
                });
            row("Import from the games folder", "Takes " + base + ".sav from the top of your games folder (or its "
                    + "saves folder)" + (sav.isFile() ? ", replacing this save" : ""), () -> {
                Runnable go = () -> {
                    Saves.toast(this, Saves.importSave(this, tree, sav) ? "Imported " + sav.getName()
                            : "There is no " + sav.getName() + " in the games folder");
                    build();
                };
                if (sav.isFile()) Saves.confirm(this, "Replace " + title + "'s save with " + sav.getName()
                        + " from the games folder?", go);
                else go.run();
            });
        }
        if (sav.isFile())
            row("Delete the game save", "Can't be undone: export it first to keep a copy", () ->
                    Saves.confirm(this, "Delete " + title + "'s own save? This can't be undone.", () -> {
                        sav.delete();
                        build();
                    }));

        section("Save states");
        row("Save and load them in the game", "In a game, BACK opens the menu: Save state, Load state", null);
        boolean any = false;
        for (int i = 0; i < Saves.SLOTS; i++) {
            File f = slots[i];
            final int n = i + 1;
            any |= f.isFile();
            slotRow("Slot " + n, f.isFile() ? "Saved " + Saves.describe(f) + " · press to delete" : "Empty", f,
                    f.isFile() ? () -> Saves.confirm(this, "Delete slot " + n + "?", () -> {
                        Saves.deleteState(f);
                        build();
                    }) : null);
        }
        any |= auto.isFile();
        row("Resume point", auto.isFile() ? "From when you last left the game, " + Saves.describe(auto)
                + " · press to delete (the game then starts from the beginning)" : "None", auto.isFile()
                ? () -> Saves.confirm(this, "Delete the resume point? The game will start from the beginning next time.", () -> {
                    auto.delete();
                    build();
                }) : null);
        if (any)
            row("Delete all states and the resume point", null, () ->
                    Saves.confirm(this, "Delete all " + Saves.SLOTS + " slots and the resume point of " + title + "?", () -> {
                        for (File f : slots) Saves.deleteState(f);
                        auto.delete();
                        build();
                    }));
        focus();
    }

    /**
     * A row for a slot: its picture (64dp, from the state's .png) to the left of
     * the text, when there is one. The picture goes into the row row() just made,
     * so the row keeps its place in the section, its press and its focus.
     */
    private void slotRow(String title, String summary, File state, Runnable action) {
        row(title, summary, action);
        File pic = Saves.pictureFile(state);
        if (action == null || !pic.isFile()) return;
        LinearLayout section = (LinearLayout) page.getChildAt(page.getChildCount() - 1);
        LinearLayout r = (LinearLayout) section.getChildAt(section.getChildCount() - 1);
        ImageView img = new ImageView(this);
        img.setImageBitmap(BitmapFactory.decodeFile(pic.getPath()));
        img.setContentDescription(title + " picture");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        lp.setMargins(0, 0, Ui.dp(this, 16), 0);
        r.addView(img, 0, lp);
    }

    private Uri tree() {
        String s = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
        return null;
    }
}
