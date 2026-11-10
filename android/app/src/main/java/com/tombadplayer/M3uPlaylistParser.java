package com.tombadplayer;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class M3uPlaylistParser {
    private M3uPlaylistParser() {}

    public static final class Entry {
        public final String title;
        public final String url;
        public Entry(String title, String url) { this.title = title; this.url = url; }
        @Override public String toString() { return title; }
    }

    public static boolean looksLikeHls(String content) {
        String l = content.toLowerCase(Locale.ROOT);
        return l.contains("#ext-x-targetduration") || l.contains("#ext-x-stream-inf")
                || l.contains("#ext-x-media-sequence");
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    public static List<Entry> parse(String content, String base) {
        List<Entry> out = new ArrayList<>();
        if (content == null) return out;
        String c = content.replace("\uFEFF", "");
        if (looksLikeHls(c)) return out;

        String title = null, ua = null, ref = null;
        for (String raw : c.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                String up = line.toUpperCase(Locale.ROOT);
                int eq = line.indexOf('=');
                if (up.startsWith("#EXTINF:")) {
                    int comma = line.lastIndexOf(',');
                    title = comma >= 0 ? line.substring(comma + 1).trim() : null;
                } else if (up.startsWith("#EXTVLCOPT:HTTP-USER-AGENT=") && eq > 0) {
                    ua = line.substring(eq + 1).trim();
                } else if ((up.startsWith("#EXTVLCOPT:HTTP-REFERRER=")
                        || up.startsWith("#EXTVLCOPT:HTTP-REFERER=")) && eq > 0) {
                    ref = line.substring(eq + 1).trim();
                }
                continue;
            }
            String head = line, tail = "";
            int bar = line.indexOf('|');
            if (bar > 0) { head = line.substring(0, bar); tail = line.substring(bar); }
            String url = resolve(base, head);
            if (url == null) continue;

            StringBuilder sb = new StringBuilder(url);
            if (!tail.isEmpty()) sb.append(tail);
            else if (ua != null || ref != null) {
                sb.append('|');
                if (ua != null) sb.append("User-Agent=").append(enc(ua));
                if (ref != null) {
                    if (ua != null) sb.append('&');
                    sb.append("Referer=").append(enc(ref));
                }
            }
            String name = (title == null || title.isEmpty()) ? "رابط " + (out.size() + 1) : title;
            out.add(new Entry(name, sb.toString()));
            title = null; ua = null; ref = null;
            if (out.size() >= 5000) break;
        }
        return out;
    }

    private static String resolve(String base, String v) {
        if (v.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) return v;
        if (v.startsWith("//")) return "https:" + v;
        if (base == null || base.isEmpty()) return null;
        try {
            String b = base.contains("|") ? base.substring(0, base.indexOf('|')) : base;
            return URI.create(b).resolve(URI.create(v.replace(" ", "%20"))).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
