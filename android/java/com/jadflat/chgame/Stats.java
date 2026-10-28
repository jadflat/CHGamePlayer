package com.jadflat.chgame;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;

/**
 * Play time and when each game was last played, in the app's private
 * preferences, keyed by the game's file name (the same however it was
 * started: the list or ES-DE). Only time the game actually ran counts, not
 * time paused in a menu.
 */
final class Stats {
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("stats", Context.MODE_PRIVATE);
    }

    static void addPlay(Context c, String gameFile, long ms) {
        SharedPreferences p = prefs(c);
        p.edit().putLong("time:" + gameFile, p.getLong("time:" + gameFile, 0) + Math.max(0, ms))
                .putLong("last:" + gameFile, System.currentTimeMillis()).apply();
    }

    static long time(Context c, String gameFile) { return prefs(c).getLong("time:" + gameFile, 0); }
    static long last(Context c, String gameFile) { return prefs(c).getLong("last:" + gameFile, 0); }

    /** "2 h 13 min", "25 min", "under a minute"; "" if never played. */
    static String duration(long ms) {
        if (ms <= 0) return "";
        long min = ms / 60000;
        if (min < 1) return "under a minute";
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    /** "3 hours ago", "yesterday"…, or "" if never played. */
    static String when(long time) {
        if (time <= 0) return "";
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }

    private Stats() {}
}
