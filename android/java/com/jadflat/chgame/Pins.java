package com.jadflat.chgame;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checksums for every file downloaded from a game's release (games, packages, card files). A file is written to
 * the games folder only when its SHA-256 matches, in this order:
 *   1. the pin in the catalogue (catalogue.json / catalogue-private.json, "sha256" on a file): shipped in the app or
 *      in jadflat's own catalogue, so it holds even against the game's own author;
 *   2. the digest GitHub publishes for the release file (shown on the release page, "sha256:..."): it proves the file
 *      is the one the author uploaded, not that the author is honest;
 *   3. the checksum recorded the first time this release's file was downloaded on this device: a file swapped later
 *      under the same release tag is refused (trust on first use).
 * No published digest at all (an old release, or the page could not be read) means no download: it fails safe.
 * Exception: a file committed in a release-less repo and addressed by its commit (raw.githubusercontent.com
 * .../<40-hex commit>/...) has no published checksum. It gets the catalogue pin if there is one, else trust on first use
 * (the checksum of the first download of that commit and name is recorded and must match later), is size-bounded by the
 * caller, and its .CHG header is checked by the installer.
 * The files bundled inside the app are not checked here: they come with the app.
 */
final class Pins {
    private Pins() { }

    private static final Pattern LINK = Pattern.compile("/releases/download/[^/\"]+/([^\"?/]+)\"");
    private static final Pattern RAW_URL = Pattern.compile("https://raw\\.githubusercontent\\.com/([\\w.-]+/[\\w.-]+)/([0-9a-f]{40})/([^/?#]+(?:/[^/?#]+){0,2})");
    private static final Pattern DIGEST = Pattern.compile("sha256:([0-9a-fA-F]{64})");
    private static final Pattern RELEASE_URL = Pattern.compile(
            "^https://github\\.com/([\\w.-]+/[\\w.-]+)/releases/download/([^/]+)/([^/?#]+)$");

    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) s.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return s.toString();
    }

    static String sha256(byte[] d, int n) {
        MessageDigest m = sha();
        m.update(d, 0, n);
        return hex(m.digest());
    }

    /** %XX escapes undone (a '+' stays a '+': these are path parts, not form fields); null if malformed. */
    static String unescape(String s) {
        if (s == null) return null;
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '%') {
                if (i + 2 >= s.length()) return null;
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                b.write(hi * 16 + lo);
                i += 2;
            } else if (ch < 0x80) {
                b.write(ch);
            } else {
                byte[] u = String.valueOf(ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                b.write(u, 0, u.length);
            }
        }
        return new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** { repo, tag, file name } of a github.com release download address (parts decoded), or null for anything else. */
    static String[] releaseParts(String url) {
        if (url == null) return null;
        Matcher m = RELEASE_URL.matcher(url);
        if (!m.matches()) return null;
        String tag = unescape(m.group(2)), name = unescape(m.group(3));
        if (tag == null || name == null || tag.isEmpty() || name.isEmpty()) return null;
        return new String[] { m.group(1), tag, name };
    }

    /** File name (lower case) -> SHA-256 (lower case hex) from a release's expanded_assets page: each digest belongs
        to the download link before it. A name seen twice with two digests is dropped (ambiguous). */
    static Map<String, String> parseDigests(String html) {
        Map<String, String> out = new HashMap<>();
        java.util.Set<String> bad = new java.util.HashSet<>();
        if (html == null) return out;
        Matcher l = LINK.matcher(html), d = DIGEST.matcher(html);
        String name = null;
        int at = 0;
        while (true) {
            int nl = l.find(at) ? l.start() : -1;
            int nd = d.find(at) ? d.start() : -1;
            if (nl < 0 && nd < 0) break;
            if (nl >= 0 && (nd < 0 || nl < nd)) {
                String n = unescape(l.group(1).replace("&amp;", "&"));      /* the same key as linked() */
                name = n == null ? null : n.toLowerCase(Locale.ROOT);
                at = l.end();
            } else {
                String h = d.group(1).toLowerCase(Locale.ROOT);
                if (name != null) {
                    String was = out.get(name);
                    if (was != null && !was.equals(h)) bad.add(name);
                    out.put(name, h);
                }
                at = d.end();
            }
        }
        for (String b : bad) out.remove(b);
        return out;
    }

    /** What the checks say: null when the file may be written, else why not. Pure; see the class comment. */
    static String verdict(String pinned, String published, String recorded, String seen) {
        if (seen == null || seen.length() != 64) return "the file could not be checked";
        if (pinned != null && !pinned.isEmpty())
            return pinned.equalsIgnoreCase(seen) ? null : "the file does not match the catalogue's checksum: not installed";
        if (published == null || published.isEmpty())
            return "no checksum is published for this file, so it was not installed (try again later)";
        if (!published.equalsIgnoreCase(seen)) return "the file does not match the checksum on its release page: not installed";
        if (recorded != null && !recorded.equalsIgnoreCase(seen))
            return "the release's file changed since it was first downloaded: not installed";
        return null;
    }

    static String key(String repo, String tag, String name) {
        return "pin:" + repo.toLowerCase(Locale.ROOT) + "|" + tag + "|" + name.toLowerCase(Locale.ROOT);
    }

    /** One release's expanded_assets page: found false for a 404 (no such tag). */
    static final class Page {
        final boolean found;
        final Map<String, String> digests, links;      /* lower-case name -> digest / -> the name as linked (URL form) */
        final long time;
        Page(boolean found, Map<String, String> digests, Map<String, String> links) {
            this.found = found;
            this.digests = digests;
            this.links = links;
            time = android.os.SystemClock.elapsedRealtime();
        }
    }

    private static final Map<String, Page> pages = new HashMap<>();
    private static final long EMPTY_KEEP = 60_000L;     /* a 404, or a page with no digest yet, is read again after this */
    private static final long PAGE_KEEP = 300_000L;     /* a page with digests */

    /** Blocking: one release's page, cached 5 minutes (a 404 or one with no digest only a minute); null when it could not be read (not cached). */
    static Page page(String repo, String tag) {
        String k = repo.toLowerCase(Locale.ROOT) + "|" + tag;
        synchronized (pages) {
            Page got = pages.get(k);
            if (got != null && android.os.SystemClock.elapsedRealtime() - got.time
                    < (got.found && !got.digests.isEmpty() ? PAGE_KEEP : EMPTY_KEEP)) return got;
        }
        Page got;
        try {
            HttpURLConnection h = CatalogueActivity.open("https://github.com/" + repo + "/releases/expanded_assets/"
                    + android.net.Uri.encode(tag));
            try (InputStream in = h.getInputStream()) {
                String html = CatalogueActivity.readAll(in, 2 << 20);
                Map<String, String> names = new HashMap<>();
                for (Matcher m = LINK.matcher(html); m.find(); ) {
                    String raw = m.group(1).replace("&amp;", "&"), n = unescape(raw);
                    if (n != null) names.put(n.toLowerCase(Locale.ROOT), raw);
                }
                got = new Page(true, parseDigests(html), names);
            } finally {
                h.disconnect();
            }
        } catch (IOException e) {
            if (!"HTTP 404".equals(e.getMessage())) return null;     /* not cached: tried again next time */
            got = new Page(false, new HashMap<>(), new HashMap<>());
        } catch (RuntimeException e) {
            return null;
        }
        synchronized (pages) {
            pages.put(k, got);
        }
        return got;
    }

    /** Blocking: the digests GitHub publishes for one release; empty if the page cannot be read or is not there. */
    static Map<String, String> published(String repo, String tag) {
        Page p = page(repo, tag);
        return p == null ? new HashMap<>() : p.digests;
    }

    /** Blocking: lower-case file name -> its name as the release page links it (URL form), from the same page read. */
    static Map<String, String> linked(String repo, String tag) {
        Page p = page(repo, tag);
        return p == null ? new HashMap<>() : p.links;
    }

    /**
     * Blocking: may the file downloaded from address 'url' (SHA-256 'seen') be written? null if so (and its checksum is
     * then remembered), else the reason. 'pinned' is the catalogue's checksum or null.
     */
    static String check(Context c, String url, String pinned, String seen) {
        String[] p = releaseParts(url);
        if (p == null && url != null) {         /* a file committed in a repo, addressed by its commit: no published checksum, accepted (see the class comment) */
            Matcher r = RAW_URL.matcher(url);
            if (r.matches() && (pinned == null || pinned.isEmpty())) {
                String fp = r.group(3);
                String fn = unescape(fp.substring(fp.lastIndexOf('/') + 1));
                if (fn == null || fn.isEmpty()) return "the file's name could not be read: not installed";
                if (seen == null || seen.length() != 64) return "the file could not be checked";
                /* trust on first use, as for releases: the same commit and name must always give the same bytes */
                SharedPreferences prefs = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
                String k = key(r.group(1), r.group(2), fn);
                String was = prefs.getString(k, null);
                if (was != null && !was.equalsIgnoreCase(seen))
                    return "the file changed since it was first downloaded: not installed";
                if (was == null) prefs.edit().putString(k, seen.toLowerCase(Locale.ROOT)).apply();
                return null;
            }
        }
        if (p == null) return pinned != null && !pinned.isEmpty() ? verdict(pinned, null, null, seen)
                : "the file is not from a GitHub release and has no checksum: not installed";
        return check(c, p[0], p[1], p[2], pinned, seen);
    }

    static String check(Context c, String repo, String tag, String name, String pinned, String seen) {
        SharedPreferences prefs = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String k = key(repo, tag, name);
        String published = pinned != null && !pinned.isEmpty() ? null
                : published(repo, tag).get(name.toLowerCase(Locale.ROOT));
        String why = verdict(pinned, published, prefs.getString(k, null), seen);
        if (why == null) prefs.edit().putString(k, seen.toLowerCase(Locale.ROOT)).apply();
        return why;
    }
}
