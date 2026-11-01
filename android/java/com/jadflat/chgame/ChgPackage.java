package com.jadflat.chgame;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A release that ships a .chgame package (a zip: info.json and the game's rev0.bin) in place of a
 * .CHG. toChg() packs the board's .CHG from it as tools/chgpack.py does: the 512-byte header,
 * the program, then the game's record at the next 512-byte boundary. The cart picture is the one given (the game's earlier one), if any.
 */
final class ChgPackage {
    private ChgPackage() {}

    static boolean isPackage(byte[] b) {
        return b.length > 4 && b[0] == 'P' && b[1] == 'K';
    }

    /** The .CHG made from the package; null when it holds no rev0 program or no info.json. */
    static byte[] toChg(byte[] zip) { return toChg(zip, null); }

    /** The 8,704-byte cart picture block (CHB1) of a .CHG, or null when it has none. */
    static byte[] pictureOf(byte[] d) {
        if (d == null || d.length < 512 + 4) return null;
        int at = le(d, 0x60), size = le(d, 0x64);
        if (at <= 0 || size != Cart.PICTURE || at % 512 != 0 || at + size > d.length) return null;
        if (d[at] != 'C' || d[at + 1] != 'H' || d[at + 2] != 'B' || d[at + 3] != '1') return null;
        return java.util.Arrays.copyOfRange(d, at, at + size);
    }

    /** The picture the game had: the installed file's own, else the bundled copy with the same name. */
    static byte[] coverFor(android.content.Context c, android.net.Uri old, String file) {
        try {
            if (old != null) {
                try (java.io.InputStream in = c.getContentResolver().openInputStream(old)) {
                    if (in != null) {
                        byte[] b = new byte[Cart.PICTURE_MAX_OFFSET + Cart.PICTURE];
                        int n = 0;
                        for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
                        byte[] p = pictureOf(java.util.Arrays.copyOf(b, n));
                        if (p != null) return p;
                    }
                }
            }
            android.content.res.AssetManager am = c.getAssets();
            String[] devs = am.list("catalogue-games");
            if (devs != null) for (String dv : devs) {
                String[] fs = am.list("catalogue-games/" + dv);
                if (fs != null) for (String f : fs) if (f.equalsIgnoreCase(file)) {
                    try (java.io.InputStream in = am.open("catalogue-games/" + dv + "/" + f)) {
                        byte[] b = new byte[Cart.PICTURE_MAX_OFFSET + Cart.PICTURE];
                        int n = 0;
                        for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
                        return pictureOf(java.util.Arrays.copyOf(b, n));
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static int le(byte[] d, int o) {
        return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0x7f) << 24;
    }

    static byte[] toChg(byte[] zip, byte[] pic) {
        byte[] info = null, bin = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                boolean isInfo = n.equals("info.json"), isBin = n.endsWith("rev0.bin");
                if (!isInfo && !isBin) continue;
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                for (int r; (r = z.read(buf)) > 0; ) {
                    o.write(buf, 0, r);
                    if (o.size() > 1 << 20) return null;
                }
                if (isInfo) info = o.toByteArray(); else if (bin == null) bin = o.toByteArray();
            }
        } catch (IOException ex) {
            return null;
        }
        if (info == null || bin == null || bin.length == 0 || bin.length > 50944) return null;
        try {
            JSONObject top = new JSONObject(new String(info, StandardCharsets.UTF_8));
            JSONObject game = top.getJSONArray("games").getJSONObject(0);
            if (!game.has("license") && top.has("license")) game.put("license", top.getString("license"));
            String title = game.optString("title", top.optString("title", "GAME"));
            String author = game.optString("author", top.optString("author", ""));
            String version = game.optString("version", top.optString("version", ""));
            byte[] record = game.toString().getBytes(StandardCharsets.UTF_8);

            byte[] payload = new byte[(bin.length + 3) & ~3];
            java.util.Arrays.fill(payload, (byte) 0xFF);
            System.arraycopy(bin, 0, payload, 0, bin.length);
            int pat = (512 + payload.length + 511) / 512 * 512;
            int at = pic == null ? pat : pat + pic.length;
            ByteBuffer h = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            h.put(new byte[] { 'C', 'H', 'G', '1' }).putShort((short) 1).putShort((short) 512)
                    .putInt(0x35335843).putInt(0x003000F7).putInt(payload.length).putInt(crc(payload)).putInt(0).putInt(0);
            text(h, 0x20, title, 32);
            text(h, 0x40, author, 16);
            text(h, 0x50, version, 8);
            if (pic != null) h.putInt(0x60, pat).putInt(0x64, pic.length);
            h.putInt(0x6C, at).putInt(0x70, record.length).putInt(0x74, crc(record));
            h.putInt(0x1FC, crc(java.util.Arrays.copyOf(h.array(), 0x1FC)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(h.array(), 0, 512);
            out.write(payload, 0, payload.length);
            out.write(new byte[pat - 512 - payload.length], 0, pat - 512 - payload.length);
            if (pic != null) out.write(pic, 0, pic.length);
            out.write(record, 0, record.length);
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private static int crc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b);
        return (int) c.getValue();
    }

    private static void text(ByteBuffer h, int off, String s, int n) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < n - 1 && i < b.length; i++) h.put(off + i, b[i] < 32 || b[i] > 126 ? (byte) '?' : b[i]);
    }
}
