package com.tombadplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import java.util.List;
import java.util.Locale;

public class PlayerActivity extends Activity {

    private static final int BG = 0xFF0E1016, CARD = 0xFF171A24, FIELD = 0xFF232837;
    private static final int ACCENT = 0xFF4C8DFF, GREEN = 0xFF2FBF71, RED = 0xFFE5484D;
    private static final int DIM = 0xFF9AA3B5;

    private static final int REQ_VIDEO = 11, REQ_AUDIO = 12;
    private static final int V = 0, A = 1;
    private static final int MAX_RETRIES = 8;
    private static final long MAX_DELAY = 120000;
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final long[] STEPS = {10, 100, 1000};
    private static final int[] FALLBACK_ORDER = {
            SourceTools.HLS, SourceTools.PROG, SourceTools.DASH, SourceTools.SS};

    private ExoPlayer videoPlayer, audioPlayer;
    private PlayerView playerView;
    private EditText videoUrl, audioUrl;
    private TextView status, syncInfo, delayLabel, speedLabel;
    private final Button[] stepButtons = new Button[3];

    private long stepMs = 100, delayMs = 0;
    private int speedIndex = 2;
    private boolean started = false;
    private boolean activityActive = true;
    private boolean networkUp = true;
    private boolean resolving = false;
    private boolean linkDialogOpen = false;
    private boolean positionRestored = false;
    private float lastAudioSpeed = -1f;
    private long lastSeekAt = 0;
    private long lastPosSave = 0;
    private int sessionId = 0;

    private String sourceOne = "", sourceTwo = "";
    private boolean swapped = false;

    private final SourceTools.Spec[] spec = new SourceTools.Spec[2];
    private final int[] kind = new int[2];
    private final int[] triedMask = new int[2];
    private final int[] uaIdx = new int[2];
    private final long[] recoveryPosition = new long[2];
    private final int[] retries = new int[2];
    private final boolean[] needsRecovery = new boolean[2];
    private final Runnable[] retryTask = new Runnable[2];

    private String notice = null;
    private long noticeUntil = 0;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try { tick(); } catch (Exception ignored) { }
            handler.postDelayed(this, 250);
        }
    };

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        c.setBackground(shape(CARD, 16));
        return c;
    }

    private TextView text(String v, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(v);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private Button button(String v, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(v);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(shape(color, 12));
        b.setOnClickListener(l);
        return b;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(DIM);
        e.setHint(hint);
        e.setTextSize(13);
        e.setBackground(shape(FIELD, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void add(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        parent.addView(child, p);
    }

    private void addWeighted(LinearLayout r, View child, float weight, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                heightDp > 0 ? dp(heightDp) : ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(dp(3), 0, dp(3), 0);
        r.addView(child, p);
    }

    private void addFixed(LinearLayout r, View child, int widthDp, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
        p.setMargins(dp(3), 0, dp(3), 0);
        r.addView(child, p);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tombad", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(14), dp(12), dp(20));
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(content);
        setContentView(scroll);

        add(content, text("TombadPlayer ∞", 24, Color.WHITE, true), 0);
        add(content, text("صورة من مصدر ... وصوت من مصدر آخر", 13, DIM, false), 2);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        add(content, playerView, 12);
        applyVideoHeight();

        LinearLayout sources = card();
        add(sources, text("مصدر الصورة", 14, Color.WHITE, true), 0);
        videoUrl = input("https://example.com/video.m3u8");
        LinearLayout r1 = row();
        addWeighted(r1, videoUrl, 1f, 0);
        addFixed(r1, button("لصق", FIELD, v -> paste(videoUrl)), 52, 44);
        addFixed(r1, button("ملف", FIELD, v -> pick(REQ_VIDEO)), 52, 44);
        add(sources, r1, 6);

        add(sources, text("مصدر الصوت", 14, Color.WHITE, true), 12);
        audioUrl = input("https://example.com/audio.m3u8");
        LinearLayout r2 = row();
        addWeighted(r2, audioUrl, 1f, 0);
        addFixed(r2, button("لصق", FIELD, v -> paste(audioUrl)), 52, 44);
        addFixed(r2, button("ملف", FIELD, v -> pick(REQ_AUDIO)), 52, 44);
        add(sources, r2, 6);

        add(sources, text("للروابط التي تحتاج ترويسات: رابط|Referer=...&User-Agent=...",
                11, DIM, false), 8);

        LinearLayout r3 = row();
        addWeighted(r3, button("⇄ تبديل", FIELD, v -> swapSources()), 1f, 48);
        addWeighted(r3, button("▶ تشغيل المصدرين", ACCENT, v -> startPlayback()), 2f, 48);
        add(sources, r3, 12);

        LinearLayout r4 = row();
        addWeighted(r4, button("تبديل الصورة", 0xFF315C9E, v -> swapSources()), 1f, 46);
        addWeighted(r4, button("تبديل الصوت", 0xFF287A58, v -> swapSources()), 1f, 46);
        add(sources, r4, 8);
        add(content, sources, 12);

        LinearLayout sync = card();
        add(sync, text("مزامنة الصوت", 15, Color.WHITE, true), 0);
        add(sync, text("+ يؤخّر الصوت    − يقدّمه", 12, DIM, false), 2);
        delayLabel = text("+0.000 ث", 32, Color.WHITE, true);
        add(sync, delayLabel, 6);

        LinearLayout stepRow = row();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            stepButtons[i] = button(i == 2 ? "1 ث" : STEPS[i] + " ms", FIELD, v -> {
                stepMs = STEPS[idx];
                refreshSteps();
            });
            addWeighted(stepRow, stepButtons[i], 1f, 40);
        }
        add(sync, stepRow, 8);

        LinearLayout ctl = row();
        ctl.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button minus = button("−", RED, v -> changeDelay(-stepMs));
        Button plus = button("+", GREEN, v -> changeDelay(stepMs));
        minus.setTextSize(22);
        plus.setTextSize(22);
        addWeighted(ctl, minus, 1f, 52);
        addWeighted(ctl, button("0", FIELD, v -> changeDelay(-delayMs)), 0.6f, 52);
        addWeighted(ctl, plus, 1f, 52);
        add(sync, ctl, 8);
        add(content, sync, 12);

        LinearLayout info = card();
        LinearLayout sr = row();
        speedLabel = text("السرعة: 1.00x", 14, Color.WHITE, true);
        addWeighted(sr, speedLabel, 1f, 0);
        addWeighted(sr, button("تغيير السرعة", FIELD, v -> cycleSpeed()), 1f, 44);
        add(info, sr, 0);
        status = text("أدخل المصدرين ثم اضغط تشغيل", 13, DIM, false);
        add(info, status, 10);
        syncInfo = text("", 12, DIM, false);
        add(info, syncInfo, 4);
        add(content, info, 12);

        createPlayers();
        registerNetwork();

        videoUrl.setText(prefs.getString("video", ""));
        audioUrl.setText(prefs.getString("audio", ""));
        delayMs = prefs.getLong("delay", 0);
        stepMs = prefs.getLong("step", 100);
        refreshSteps();
        updateDelayLabel();
        handler.postDelayed(ticker, 250);
    }

    private void createPlayers() {
        videoPlayer = Media3EngineFactory.create(this);
        audioPlayer = Media3EngineFactory.create(this);

        videoPlayer.setTrackSelectionParameters(videoPlayer.getTrackSelectionParameters()
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        audioPlayer.setTrackSelectionParameters(audioPlayer.getTrackSelectionParameters()
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setMaxVideoSize(426, 240).build());

        audioPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        audioPlayer.setWakeMode(C.WAKE_MODE_NETWORK);
        playerView.setPlayer(videoPlayer);

        videoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) markReady(V);
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo o,
                                                          Player.PositionInfo n, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) onUserSeek(n);
            }
            @Override public void onPlayerError(PlaybackException e) { onSourceError(V, e); }
        });
        audioPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) markReady(A);
            }
            @Override public void onPlayerError(PlaybackException e) { onSourceError(A, e); }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyVideoHeight();
    }

    private void applyVideoHeight() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        ViewGroup.LayoutParams p = playerView.getLayoutParams();
        if (p != null) {
            p.height = w > h ? h : (int) ((w - dp(24)) * 9f / 16f);
            playerView.setLayoutParams(p);
        }
    }

    private void paste(EditText target) {
        try {
            ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clip != null && clip.hasPrimaryClip() && clip.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = clip.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null) target.setText(t.toString().trim());
            }
        } catch (Exception e) {
            notice("تعذر اللصق");
        }
    }

    private void pick(int code) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, code);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        if (req == REQ_VIDEO) videoUrl.setText(uri.toString());
        if (req == REQ_AUDIO) audioUrl.setText(uri.toString());
    }

    private String roleSource(int role) {
        if (role == V) return swapped ? sourceTwo : sourceOne;
        return swapped ? sourceOne : sourceTwo;
    }

    private String posKey(String raw) {
        return "pos_" + Integer.toHexString(SourceTools.parse(raw).url.hashCode());
    }

    private void startPlayback() {
        if (resolving) return;
        final String v = videoUrl.getText().toString().trim();
        final String a = audioUrl.getText().toString().trim();
        if (v.isEmpty() || a.isEmpty()) { notice("أدخل رابط الصورة ورابط الصوت"); return; }
        if (!SourceTools.supported(v)) { notice("رابط الصورة غير مدعوم (RTMP غير مدعوم)"); return; }
        if (!SourceTools.supported(a)) { notice("رابط الصوت غير مدعوم (RTMP غير مدعوم)"); return; }
        savePrefs();

        resolving = true;
        notice("جارٍ فحص الروابط...");
        final int session = ++sessionId;

        new Thread(() -> {
            final SourceTools.Sniff sv = SourceTools.sniff(this, v);
            final SourceTools.Sniff sa = v.equals(a) ? sv : SourceTools.sniff(this, a);
            runOnUiThread(() -> {
                resolving = false;
                if (session != sessionId || isFinishing() || isDestroyed()) return;
                if (sv.playlist != null) { choosePlaylist(sv.playlist, V); return; }
                if (sa.playlist != null) { choosePlaylist(sa.playlist, A); return; }
                beginPlayback(v, a, sv.kind, sa.kind);
            });
        }, "Tombad-sniff").start();
    }

    private void choosePlaylist(final List<M3uPlaylistParser.Entry> list, final int role) {
        if (list.size() == 1) {
            if (role == V) videoUrl.setText(list.get(0).url);
            else audioUrl.setText(list.get(0).url);
            startPlayback();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle(role == V ? "اختر مصدر الصورة" : "اختر مصدر الصوت")
                .setItems(names, (d, which) -> {
                    String sel = list.get(which).url;
                    if (role == V) videoUrl.setText(sel);
                    else audioUrl.setText(sel);
                    startPlayback();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void resetRoleState(int i) {
        retries[i] = 0;
        needsRecovery[i] = false;
        recoveryPosition[i] = 0;
        triedMask[i] = 0;
        uaIdx[i] = 0;
    }

    private void beginPlayback(String v, String a, int kv, int ka) {
        sourceOne = v;
        sourceTwo = a;
        swapped = false;
        sessionId++;
        cancelRetries();
        resetRoleState(V);
        resetRoleState(A);
        kind[V] = kv;
        kind[A] = ka;
        spec[V] = SourceTools.parse(v);
        spec[A] = SourceTools.parse(a);
        lastAudioSpeed = -1f;
        lastSeekAt = 0;
        positionRestored = false;
        started = true;

        videoPlayer.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        audioPlayer.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        prepareRole(V, false);
        prepareRole(A, false);
        notice("تم إرسال المصدرين إلى المشغل");
    }

    private void prepareRole(int i, boolean keepPosition) {
        ExoPlayer p = player(i);
        if (p == null || spec[i] == null) return;
        long pos = C.TIME_UNSET;
        if (keepPosition && recoveryPosition[i] > 0 && !p.isCurrentMediaItemLive())
            pos = recoveryPosition[i];
        try {
            MediaSource src = SourceTools.build(this, spec[i], kind[i], uaIdx[i]);
            p.stop();
            if (pos == C.TIME_UNSET) p.setMediaSource(src);
            else p.setMediaSource(src, pos);
            p.prepare();
            p.setPlayWhenReady(i == V);
        } catch (Exception e) {
            notice("تعذر تجهيز مصدر " + who(i) + ": " + e.getMessage());
        }
    }

    private void swapSources() {
        if (!started) {
            String v = videoUrl.getText().toString().trim();
            String a = audioUrl.getText().toString().trim();
            videoUrl.setText(a);
            audioUrl.setText(v);
            savePrefs();
            notice("تم تبديل الحقلين");
            return;
        }
        swapped = !swapped;
        videoUrl.setText(roleSource(V));
        audioUrl.setText(roleSource(A));
        savePrefs();

        sessionId++;
        cancelRetries();
        int t = kind[V]; kind[V] = kind[A]; kind[A] = t;
        resetRoleState(V);
        resetRoleState(A);
        spec[V] = SourceTools.parse(roleSource(V));
        spec[A] = SourceTools.parse(roleSource(A));
        positionRestored = false;
        lastAudioSpeed = -1f;
        lastSeekAt = 0;
        prepareRole(V, false);
        prepareRole(A, false);
        notice("تم تبديل الصورة والصوت");
    }

    private void replaceRoleSource(int role, String raw) {
        boolean first = (role == V) != swapped;
        if (first) sourceOne = raw; else sourceTwo = raw;
        if (role == V) videoUrl.setText(raw); else audioUrl.setText(raw);
        savePrefs();
        cancelRetry(role);
        long keep = recoveryPosition[role];
        resetRoleState(role);
        recoveryPosition[role] = keep;
        spec[role] = SourceTools.parse(raw);
        kind[role] = SourceTools.kindFromUrl(spec[role].url);
        if (role == V) positionRestored = true;
        prepareRole(role, true);
        notice("تم تحديث رابط " + who(role));
    }

    private void askNewLink(final int role, String reason) {
        needsRecovery[role] = false;
        if (linkDialogOpen || isFinishing() || isDestroyed()) { notice(reason); return; }
        linkDialogOpen = true;
        final EditText et = input("الصق الرابط الجديد");
        et.setText(roleSource(role));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("تحديث رابط " + who(role))
                .setMessage(reason + "\nالصق رابطًا جديدًا ويكمل من نفس الموضع.")
                .setView(et)
                .setPositiveButton("تحديث", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (SourceTools.supported(n)) replaceRoleSource(role, n);
                    else notice("رابط غير صالح");
                })
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.setOnDismissListener(d -> linkDialogOpen = false);
        dlg.show();
    }

    private boolean timelinesReady() {
        return !videoPlayer.getCurrentTimeline().isEmpty()
                && !audioPlayer.getCurrentTimeline().isEmpty();
    }

    private boolean isLive() {
        return videoPlayer.isCurrentMediaItemLive() || audioPlayer.isCurrentMediaItemLive();
    }

    private void setAudioSpeed(float s) {
        if (Math.abs(s - lastAudioSpeed) > 0.001f) {
            audioPlayer.setPlaybackParameters(new PlaybackParameters(s, 1f));
            lastAudioSpeed = s;
        }
    }

    private void tick() {
        if (!started || videoPlayer == null || audioPlayer == null) return;

        int vs = videoPlayer.getPlaybackState();
        int as = audioPlayer.getPlaybackState();
        if (SystemClock.elapsedRealtime() < noticeUntil && notice != null) {
            status.setText(notice);
        } else {
            status.setText("الصورة: " + stateName(vs) + "   |   الصوت: " + stateName(as));
        }

        if (!timelinesReady()) return;

        boolean videoRunning = videoPlayer.getPlayWhenReady() && vs == Player.STATE_READY;
        if (!videoRunning) {
            if (audioPlayer.getPlayWhenReady() && !videoPlayer.getPlayWhenReady())
                audioPlayer.setPlayWhenReady(false);
            return;
        }

        float user = SPEEDS[speedIndex];
        long now = SystemClock.elapsedRealtime();

        if (isLive()) {
            long vOff = videoPlayer.getCurrentLiveOffset();
            long aOff = audioPlayer.getCurrentLiveOffset();
            if (!audioPlayer.getPlayWhenReady()) audioPlayer.setPlayWhenReady(true);
            if (vOff == C.TIME_UNSET || aOff == C.TIME_UNSET || as != Player.STATE_READY) {
                syncInfo.setText("بث مباشر: بانتظار معلومات التوقيت");
                return;
            }
            long want = vOff + delayMs;
            long lag = aOff - want;
            if (Math.abs(lag) > 1500 && now - lastSeekAt > 4000) {
                audioPlayer.seekTo(Math.max(0, audioPlayer.getCurrentPosition() + lag));
                lastSeekAt = now;
                setAudioSpeed(user);
            } else if (lag > 60) setAudioSpeed(user * 1.03f);
            else if (lag < -60) setAudioSpeed(user * 0.97f);
            else if (Math.abs(lag) < 30) setAudioSpeed(user);
            syncInfo.setText("بث مباشر | انحراف: " + (lag >= 0 ? "+" : "") + lag + " ms");
            return;
        }

        if (now - lastPosSave > 5000) {
            lastPosSave = now;
            prefs.edit().putLong(posKey(roleSource(V)), videoPlayer.getCurrentPosition()).apply();
        }

        long target = videoPlayer.getCurrentPosition() - delayMs;
        if (target < 0) {
            audioPlayer.setPlayWhenReady(false);
            if (audioPlayer.getCurrentPosition() != 0) audioPlayer.seekTo(0);
            syncInfo.setText("الصوت ينتظر " + (-target) + " ms");
            return;
        }
        if (!audioPlayer.getPlayWhenReady()) {
            audioPlayer.seekTo(target);
            audioPlayer.setPlayWhenReady(true);
            lastSeekAt = now;
            return;
        }
        if (as != Player.STATE_READY) return;

        long diff = audioPlayer.getCurrentPosition() - target;
        if (Math.abs(diff) > 300 && now - lastSeekAt > 1500) {
            audioPlayer.seekTo(target);
            lastSeekAt = now;
            setAudioSpeed(user);
        } else if (diff > 40) setAudioSpeed(user * 0.97f);
        else if (diff < -40) setAudioSpeed(user * 1.03f);
        else if (Math.abs(diff) < 20) setAudioSpeed(user);
        syncInfo.setText("فرق التزامن: " + (diff >= 0 ? "+" : "") + diff + " ms");
    }

    private void onUserSeek(Player.PositionInfo n) {
        if (!started || audioPlayer == null || !timelinesReady() || isLive()) return;
        audioPlayer.seekTo(Math.max(0, n.positionMs - delayMs));
        lastSeekAt = SystemClock.elapsedRealtime();
    }

    private void changeDelay(long amount) {
        delayMs = Math.max(-MAX_DELAY, Math.min(MAX_DELAY, delayMs + amount));
        updateDelayLabel();
        prefs.edit().putLong("delay", delayMs).apply();
        lastSeekAt = 0;
        if (started && timelinesReady() && !isLive()) {
            audioPlayer.seekTo(Math.max(0, videoPlayer.getCurrentPosition() - delayMs));
        }
    }

    private void updateDelayLabel() {
        delayLabel.setText(String.format(Locale.US, "%s%.3f ث",
                delayMs >= 0 ? "+" : "−", Math.abs(delayMs) / 1000.0));
    }

    private void refreshSteps() {
        for (int i = 0; i < 3; i++)
            stepButtons[i].setBackground(shape(STEPS[i] == stepMs ? ACCENT : FIELD, 12));
        prefs.edit().putLong("step", stepMs).apply();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float s = SPEEDS[speedIndex];
        speedLabel.setText(String.format(Locale.US, "السرعة: %.2fx", s));
        if (videoPlayer != null) videoPlayer.setPlaybackParameters(new PlaybackParameters(s));
        if (audioPlayer != null) audioPlayer.setPlaybackParameters(new PlaybackParameters(s, 1f));
        lastAudioSpeed = s;
    }

    private void notice(String s) {
        notice = s;
        noticeUntil = SystemClock.elapsedRealtime() + 5000;
        if (status != null) status.setText(s);
    }

    private ExoPlayer player(int i) { return i == V ? videoPlayer : audioPlayer; }
    private String who(int i) { return i == V ? "الصورة" : "الصوت"; }

    private void markReady(int i) {
        retries[i] = 0;
        needsRecovery[i] = false;
        if (retryTask[i] != null) {
            handler.removeCallbacks(retryTask[i]);
            retryTask[i] = null;
        }
        if (i == V && !positionRestored && videoPlayer != null) {
            positionRestored = true;
            if (!videoPlayer.isCurrentMediaItemLive()) {
                long saved = prefs.getLong(posKey(roleSource(V)), 0);
                long dur = videoPlayer.getDuration();
                if (saved > 5000 && (dur == C.TIME_UNSET || saved < dur - 15000)) {
                    videoPlayer.seekTo(saved);
                    notice("استئناف من آخر موضع");
                }
            }
        }
    }

    private int httpErrorCode(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException)
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            t = t.getCause();
        }
        return -1;
    }

    private boolean isFormatError(int code) {
        return code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private int nextKind(int i) {
        triedMask[i] |= (1 << kind[i]);
        for (int k : FALLBACK_ORDER) if ((triedMask[i] & (1 << k)) == 0) return k;
        return -1;
    }

    private void onSourceError(int i, PlaybackException e) {
        ExoPlayer failed = player(i);
        if (failed != null && !failed.isCurrentMediaItemLive())
            recoveryPosition[i] = Math.max(0, failed.getCurrentPosition());

        int code = e.errorCode;

        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && failed != null) {
            failed.seekToDefaultPosition();
            failed.prepare();
            return;
        }

        int http = httpErrorCode(e);
        if (http == 401 || http == 403 || http == 406 || http == 429) {
            if (uaIdx[i] < SourceTools.UA_COUNT - 1) {
                uaIdx[i]++;
                notice("الخادم رفض (" + http + ")، تجربة هوية اتصال بديلة...");
                prepareRole(i, true);
                return;
            }
            askNewLink(i, "الخادم رفض الوصول (" + http + ")");
            return;
        }
        if (http == 404 || http == 410) {
            askNewLink(i, "الرابط غير موجود أو انتهت صلاحيته (" + http + ")");
            return;
        }

        if (isFormatError(code)) {
            int next = nextKind(i);
            if (next != -1) {
                kind[i] = next;
                notice("تجربة طريقة تشغيل بديلة لـ" + who(i) + "...");
                prepareRole(i, true);
            } else {
                needsRecovery[i] = false;
                notice("صيغة مصدر " + who(i) + " غير مدعومة أو الرابط ليس وسائط");
            }
            return;
        }

        if (code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                || code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
            needsRecovery[i] = false;
            notice("لا يمكن قراءة الملف. استخدم زر «ملف» لاختياره من الهاتف");
            return;
        }

        if (code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
            needsRecovery[i] = false;
            notice("الجهاز لا يدعم فك ترميز " + who(i) + " (" + e.getErrorCodeName() + ")");
            return;
        }

        needsRecovery[i] = true;
        notice("خطأ في مصدر " + who(i) + ": " + e.getErrorCodeName());
        scheduleRetry(i, true);
    }

    private void scheduleRetry(final int i, boolean countFailure) {
        if (retryTask[i] != null) return;
        if (countFailure) retries[i]++;
        if (retries[i] > MAX_RETRIES) {
            notice("توقفت محاولات " + who(i) + " — اضغط تشغيل لإعادة البدء");
            return;
        }
        final int session = sessionId;
        long wait = countFailure
                ? Math.min(30000L, 1000L << Math.min(Math.max(retries[i] - 1, 0), 5)) : 0L;

        Runnable task = new Runnable() {
            @Override public void run() {
                retryTask[i] = null;
                if (session != sessionId || isFinishing() || isDestroyed()) return;
                if (!activityActive || !networkUp) return;
                ExoPlayer p = player(i);
                if (p == null) return;
                notice("استعادة " + who(i) + "... المحاولة " + Math.max(1, retries[i]));
                p.prepare();
            }
        };
        retryTask[i] = task;
        handler.postDelayed(task, wait);
    }

    private void cancelRetry(int i) {
        if (retryTask[i] != null) handler.removeCallbacks(retryTask[i]);
        retryTask[i] = null;
    }

    private void cancelRetries() {
        cancelRetry(V);
        cancelRetry(A);
    }

    private void recoverAll() {
        for (int i = 0; i < 2; i++) if (needsRecovery[i]) scheduleRetry(i, false);
    }

    private void registerNetwork() {
        try {
            cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    handler.post(() -> {
                        boolean was = networkUp;
                        networkUp = true;
                        if (!was && started) {
                            notice("عاد الاتصال؛ جارٍ استعادة المصادر");
                            recoverAll();
                        }
                    });
                }
                @Override public void onLost(Network n) {
                    handler.post(() -> {
                        networkUp = false;
                        if (started) {
                            needsRecovery[V] = needsRecovery[A] = true;
                            notice("انقطع الاتصال؛ في انتظار عودة الشبكة");
                        }
                    });
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
            networkUp = true;
        }
    }

    private String stateName(int s) {
        switch (s) {
            case Player.STATE_BUFFERING: return "تحميل";
            case Player.STATE_READY: return "جاهز";
            case Player.STATE_ENDED: return "انتهى";
            default: return "خامل";
        }
    }

    private void savePrefs() {
        prefs.edit()
                .putString("video", videoUrl.getText().toString().trim())
                .putString("audio", audioUrl.getText().toString().trim())
                .putLong("delay", delayMs)
                .putLong("step", stepMs)
                .apply();
    }

    @Override protected void onStart() {
        super.onStart();
        activityActive = true;
        if (started) recoverAll();
    }

    @Override protected void onStop() {
        activityActive = false;
        savePrefs();
        if (started && videoPlayer != null && !videoPlayer.isCurrentMediaItemLive()) {
            prefs.edit().putLong(posKey(roleSource(V)), videoPlayer.getCurrentPosition()).apply();
        }
        if (videoPlayer != null) videoPlayer.pause();
        if (audioPlayer != null) audioPlayer.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        sessionId++;
        handler.removeCallbacksAndMessages(null);
        try {
            if (cm != null && netCallback != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) { }
        if (videoPlayer != null) { videoPlayer.release(); videoPlayer = null; }
        if (audioPlayer != null) { audioPlayer.release(); audioPlayer = null; }
        super.onDestroy();
    }
}
