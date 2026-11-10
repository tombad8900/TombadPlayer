package com.tombadplayer;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SourceTools {
    private SourceTools() {}

    public static final int AUTO = 0, HLS = 1, DASH = 2, SS = 3, PROG = 4;

    private static final String[] UAS = {
            "Mozilla/5.0 (Linux; Android 13; Redmi Note 12 5G) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "VLC/3.0.20 LibVLC/3.0.20",
            "TombadPlayer/1.0 (Linux; Android) ExoPlayerLib"
    };
    public static final int UA_COUNT = UAS.length;

    public static final class Spec {
        public String url = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
    }

    public static final class Sniff {
        public int kind = AUTO;
        public List<M3uPlaylistParser.Entry> playlist = null;
    }

    public static Spec parse(String raw) {
        Spec s = new Spec();
        String t = raw == null ? "" : raw.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        int bar = t.indexOf('|');
        if (bar > 0) {
            String h = t.substring(bar + 1);
            t = t.substring(0, bar).trim();
            for (String pair : h.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String v = pair.substring(eq + 1).trim();
                    try { v = URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) { }
                    if (!k.isEmpty()) s.headers.put(k, v);
                }
            }
        }
        if (t.startsWith("//")) t = "https:" + t;
        else if (t.startsWith("/")) t = "file://" + t;
        s.url = t;
        return s;
    }

    public static boolean supported(String raw) {
        String l = parse(raw).url.toLowerCase(Locale.US);
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("content://")
                || l.startsWith("file://") || l.startsWith("rtsp://") || l.startsWith("rtsps://");
    }

    public static int kindFromUrl(String url) {
        String path = "";
        try {
            String p = Uri.parse(url).getPath();
            if (p != null) path = p.toLowerCase(Locale.US);
        } catch (Exception ignored) { }
        if (path.endsWith(".m3u8")) return HLS;
        if (path.endsWith(".mpd")) return DASH;
        if (path.endsWith(".ism") || path.endsWith(".isml")
                || path.contains(".ism/manifest") || path.contains(".isml/manifest")) return SS;
        String low = url.toLowerCase(Locale.US);
        if (low.contains(".m3u8")) return HLS;
        if (low.contains(".mpd")) return DASH;
        return AUTO;
    }

    public static Sniff sniff(Context ctx, String raw) {
        Sniff r = new Sniff();
        Spec s;
        try { s = parse(raw); } catch (Exception e) { return r; }
        int ext = kindFromUrl(s.url);
        r.kind = ext;
        String low = s.url.toLowerCase(Locale.US);
        boolean http = low.startsWith("http://") || low.startsWith("https://");
        boolean content = low.startsWith("content://");
        if (ext == DASH || ext == SS || (!http && !content)) return r;

        HttpURLConnection c = null;
        InputStream in = null;
        try {
            String ctype = null;
            if (content) {
                Uri u = Uri.parse(s.url);
                ctype = ctx.getContentResolver().getType(u);
                in = ctx.getContentResolver().openInputStream(u);
            } else {
                c = open(s);
                if (c == null) return r;
                ctype = c.getContentType();
                in = c.getInputStream();
            }
            if (in == null) return r;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            readInto(in, out, 64 * 1024);
            byte[] head = out.toByteArray();
            String start = new String(head, 0, Math.min(head.length, 4000), StandardCharsets.UTF_8)
                    .replace("\uFEFF", "").trim();
            String type = ctype == null ? "" : ctype.toLowerCase(Locale.US);

            if (start.startsWith("#EXTM3U")) {
                readInto(in, out, 2 * 1024 * 1024);
                String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                if (M3uPlaylistParser.looksLikeHls(body)) {
                    r.kind = HLS;
                } else {
                    List<M3uPlaylistParser.Entry> list = M3uPlaylistParser.parse(body, s.url);
                    if (!list.isEmpty()) r.playlist = list;
                    else r.kind = HLS;
                }
            } else if (start.contains("<MPD")) {
                r.kind = DASH;
            } else if (start.contains("<SmoothStreamingMedia")) {
                r.kind = SS;
            } else if (type.contains("mpegurl")) {
                r.kind = HLS;
            } else if (type.contains("dash+xml")) {
                r.kind = DASH;
            } else if (type.contains("vnd.ms-sstr")) {
                r.kind = SS;
            }
        } catch (Exception ignored) {
        } finally {
            if (content && in != null) { try { in.close(); } catch (Exception ignored) { } }
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static void readInto(InputStream in, ByteArrayOutputStream out, int limit) {
        try {
            byte[] b = new byte[8192];
            int n;
            while (out.size() < limit
                    && (n = in.read(b, 0, Math.min(b.length, limit - out.size()))) != -1) {
                out.write(b, 0, n);
            }
        } catch (Exception ignored) { }
    }

    private static HttpURLConnection open(Spec s) throws Exception {
        String cur = s.url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            String ua = UAS[0];
            for (Map.Entry<String, String> e : s.headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase("user-agent")) ua = e.getValue();
                else c.setRequestProperty(e.getKey(), e.getValue());
            }
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) return null;
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            if (code >= 400) { c.disconnect(); return null; }
            return c;
        }
        return null;
    }

    public static MediaSource build(Context ctx, Spec spec, int kind, int attempt) {
        String ua = UAS[attempt % UAS.length];
        Map<String, String> h = new LinkedHashMap<>();
        boolean hasReferer = false;
        for (Map.Entry<String, String> e : spec.headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("user-agent")) ua = e.getValue();
            else {
                if (e.getKey().equalsIgnoreCase("referer")) hasReferer = true;
                h.put(e.getKey(), e.getValue());
            }
        }
        if (attempt >= 1 && !hasReferer) {
            try {
                Uri u = Uri.parse(spec.url);
                if (u.getScheme() != null && u.getHost() != null) {
                    String origin = u.getScheme() + "://" + u.getHost()
                            + (u.getPort() > 0 ? ":" + u.getPort() : "");
                    h.put("Referer", origin + "/");
                    h.put("Origin", origin);
                }
            } catch (Exception ignored) { }
        }

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);
        if (!h.isEmpty()) http.setDefaultRequestProperties(h);
        DataSource.Factory ds = new DefaultDataSource.Factory(ctx, http);

        DefaultExtractorsFactory ex = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setConstantBitrateSeekingEnabled(true);

        String mime = null;
        if (kind == HLS) mime = MimeTypes.APPLICATION_M3U8;
        else if (kind == DASH) mime = MimeTypes.APPLICATION_MPD;
        else if (kind == SS) mime = MimeTypes.APPLICATION_SS;

        MediaItem.Builder b = new MediaItem.Builder().setUri(spec.url);
        if (mime != null) b.setMimeType(mime);
        b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build());
        MediaItem item = b.build();

        DefaultLoadErrorHandlingPolicy policy = new DefaultLoadErrorHandlingPolicy(8);

        if (kind == PROG) {
            return new ProgressiveMediaSource.Factory(ds, ex)
                    .setLoadErrorHandlingPolicy(policy).createMediaSource(item);
        }
        return new DefaultMediaSourceFactory(ds, ex)
                .setLoadErrorHandlingPolicy(policy).createMediaSource(item);
    }
}
