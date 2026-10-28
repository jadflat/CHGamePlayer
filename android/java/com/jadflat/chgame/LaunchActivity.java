package com.jadflat.chgame;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;

/**
 * Starts a game handed over by a front end such as ES-DE: an ACTION_VIEW with a
 * content:// URI to one .CHG file, readable for this launch only (ES-DE's
 * %ROMPROVIDER%). The file is copied into the app's private games folder and
 * played from there; anything that is not a readable .CHG is refused.
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent in = getIntent();
        Uri uri = in == null ? null : in.getData();
        String err = null;
        File game = null;
        if (uri == null || !"content".equals(uri.getScheme())) {
            err = "No game was given.";
        } else {
            try {
                String name = displayName(uri);
                game = Files.copyGame(this, uri, name);
            } catch (Exception e) {
                err = "Could not open the game: " + e.getMessage();
            }
        }
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        } else {
            Intent g = new Intent(this, GameActivity.class);
            g.putExtra("file", game.getName());
            g.putExtra("title", Cart.title(getContentResolver(), Uri.fromFile(game)));
            startActivity(g);
        }
        finish();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        }
        return uri.getLastPathSegment();
    }
}
