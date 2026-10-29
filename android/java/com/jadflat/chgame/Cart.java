package com.jadflat.chgame;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What a .CHG file says about itself (CHGame's spec/chg.md): the title,
 * author and version in its 512-byte header, and the 128x128 picture the
 * CHGame's visual menu shows (MENU.BG's encoding, card.md: "CHB1", a
 * 16-colour RGB565 palette at byte 8, then 128 rows of 64 bytes, two pixels a
 * byte, the left one in the high nibble).
 */
final class Cart {
    static final int HEADER = 512, PICTURE = 512 + 128 * 64, PICTURE_MAX_OFFSET = 0x20000;
    static final int RECORD_MAX = 16 << 10;     /* a record this app reads; the spec allows 1 MiB */

    final String title, author, version;
    final Bitmap picture;       /* null when the file has none */
    /* from the record, when there is one: the full author (the header holds 16 characters),
       where the game was ported from ("PICO-8", "Pokitto"; null for a CHGame original) and by whom */
    final String fullAuthor, portedFrom, porter;
    String description, license;    /* from the record, or null */
    /** .DAT files the program names (e.g. STARLIFT.DAT): what it will look for on the card */
    final java.util.List<String> dats = new java.util.ArrayList<>();

    private Cart(String title, String author, String version, Bitmap picture, String fullAuthor,
                 String portedFrom, String porter) {
        this.title = title; this.author = author; this.version = version; this.picture = picture;
        this.fullAuthor = fullAuthor; this.portedFrom = portedFrom; this.porter = porter;
    }

    /** The author to show: the record's full one if there is one. */
    String credit() {
        return fullAuthor != null && !fullAuthor.isEmpty() ? fullAuthor : author;
    }

    /** {title, author, version} from a 512-byte header; null if this is not a CHG file. */
    static String[] ident(byte[] head, int n) {
        if (n < HEADER || head[0] != 'C' || head[1] != 'H' || head[2] != 'G' || head[3] != '1') return null;
        return new String[] { text(head, 0x20, 32), text(head, 0x40, 16), text(head, 0x50, 16) };
    }

    static String[] ident(ContentResolver cr, Uri uri) {
        byte[] head = new byte[HEADER];
        try (InputStream in = cr.openInputStream(uri)) {
            return in == null ? null : ident(head, readFully(in, head));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The same game whatever the file is called: title and author, lower case. */
    static String key(String[] id) {
        return (id[0] + "\n" + id[1]).toLowerCase(java.util.Locale.ROOT);
    }

    /** Just the title in the 512-byte header; null if there is none or this is not a CHG file. */
    static String title(ContentResolver cr, Uri uri) {
        byte[] head = new byte[HEADER];
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null || readFully(in, head) < HEADER) return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (head[0] != 'C' || head[1] != 'H' || head[2] != 'G' || head[3] != '1') return null;
        String t = text(head, 0x20, 32);
        return t.isEmpty() ? null : t;
    }

    /** Reads the header and the picture; null if this is not a CHG file. */
    static Cart read(ContentResolver cr, Uri uri) {
        byte[] head = new byte[PICTURE_MAX_OFFSET + PICTURE + RECORD_MAX];
        int n;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return null;
            n = readFully(in, head);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (n < HEADER || head[0] != 'C' || head[1] != 'H' || head[2] != 'G' || head[3] != '1') return null;
        String title = text(head, 0x20, 32), author = text(head, 0x40, 16), version = text(head, 0x50, 16);
        String fullAuthor = null, from = null, porter = null;
        JSONObject game = record(head, n);
        if (game != null) {
            fullAuthor = game.optString("author", null);
            /* "PICO-8 port: Celeste 2 by EXOK Games, ported to CHGame by Jadflat." */
            Matcher m = PORT.matcher(game.optString("description", ""));
            if (m.find()) { from = m.group(1); porter = m.group(2); }
        }
        Cart c = new Cart(title, author, version, picture(head, n), fullAuthor, from, porter);
        int payload = u32(head, 0x10);
        Matcher dm = DAT.matcher(new String(head, HEADER, Math.max(0, Math.min(payload, n - HEADER)),
                StandardCharsets.ISO_8859_1));
        while (dm.find()) if (!c.dats.contains(dm.group())) c.dats.add(dm.group());
        if (game != null) {
            c.description = game.optString("description", null);
            c.license = game.optString("license", null);
        }
        return c;
    }

    private static final Pattern DAT = Pattern.compile("(?<![A-Za-z0-9_])[A-Z0-9_]{1,8}\\.DAT(?![A-Za-z0-9])");
    private static final Pattern PORT = Pattern.compile("^(.{1,24}?) port: .*ported to CHGame by (.+?)\\.?$");

    /** The record's "game" object (spec/chg.md, "The record"), or null when there is none or it fails its checks. */
    private static JSONObject record(byte[] d, int n) {
        int at = u32(d, 0x6C), size = u32(d, 0x70);
        if (at <= 0 || size <= 0 || size > RECORD_MAX || at % 512 != 0 || at + size > n) return null;
        CRC32 crc = new CRC32();
        crc.update(d, at, size);
        long want = (d[0x74] & 0xffL) | (d[0x75] & 0xffL) << 8 | (d[0x76] & 0xffL) << 16 | (d[0x77] & 0xffL) << 24;
        if (crc.getValue() != want) return null;
        try {
            JSONObject r = new JSONObject(new String(d, at, size, StandardCharsets.UTF_8));
            return r.optInt("chgRecord") == 1 ? r.optJSONObject("game") : null;
        } catch (JSONException e) {
            return null;
        }
    }

    private static Bitmap picture(byte[] d, int n) {
        int at = u32(d, 0x60), size = u32(d, 0x64);
        if (at <= 0 || size != PICTURE || at % 512 != 0 || at >= PICTURE_MAX_OFFSET || at + PICTURE > n) return null;
        if (d[at] != 'C' || d[at + 1] != 'H' || d[at + 2] != 'B' || d[at + 3] != '1') return null;
        int[] pal = new int[16];
        for (int i = 0; i < 16; i++) {
            int c = (d[at + 8 + 2 * i] & 0xff) | (d[at + 9 + 2 * i] & 0xff) << 8;
            int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
            pal[i] = 0xff000000 | (r * 255 / 31) << 16 | (g * 255 / 63) << 8 | (b * 255 / 31);
        }
        /* colour 15 is the menu's own (the selection bar's): drawn as the text colour,
           as the bootloader's Static style does */
        pal[15] = pal[11];
        int[] px = new int[128 * 128];
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 64; x++) {
                int v = d[at + 512 + y * 64 + x] & 0xff;
                px[y * 128 + 2 * x] = pal[v >> 4];
                px[y * 128 + 2 * x + 1] = pal[v & 15];
            }
        return Bitmap.createBitmap(px, 128, 128, Bitmap.Config.ARGB_8888);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        for (int r; total < buf.length && (r = in.read(buf, total, buf.length - total)) > 0; ) total += r;
        return total;
    }

    private static int u32(byte[] d, int o) {
        return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0x7f) << 24;
    }

    private static String text(byte[] d, int o, int len) {
        int end = o;
        while (end < o + len && d[end] != 0) end++;
        StringBuilder s = new StringBuilder();
        for (char c : new String(d, o, end - o, StandardCharsets.UTF_8).toCharArray())
            if (c >= ' ' && c != 0x7f) s.append(c);
        return s.toString().trim();
    }
}
