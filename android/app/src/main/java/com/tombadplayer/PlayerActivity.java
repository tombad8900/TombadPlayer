package com.tombadplayer;

import android.app.Activity;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

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

    private ExoPlayer videoPlayer, audioPlayer;
    private PlayerView playerView;
    private EditText videoUrl, audioUrl;
    private TextView status, syncInfo, delayLabel, speedLabel;
    private CheckBox allowHttp;
    private final Button[] stepButtons = new Button[3];

    private long stepMs = 100, delayMs = 0;
    private int speedIndex = 2;
    private boolean started = false;
    private boolean activityActive = true;
    private boolean networkUp = true;
    private float lastAudioSpeed = -1f;
    private long lastSeekAt = 0;
    private int sessionId = 0;
    private String sourceOne = "";
    private String sourceTwo = "";
    private boolean videoUsingSecond = false;
    private boolean audioUsingFirst = false;


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

    // ---------------- أدوات التصميم ----------------

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

    // ---------------- بناء الواجهة ----------------

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
        addFixed(r1, button("ملف", FIELD, v -> pick(REQ_VIDEO)), 64, 44);
        add(sources, r1, 6);

        add(sources, text("مصدر الصوت", 14, Color.WHITE, true), 12);
        audioUrl = input("https://example.com/audio.m3u8");
        LinearLayout r2 = row();
        addWeighted(r2, audioUrl, 1f, 0);
        addFixed(r2, button("ملف", FIELD, v -> pick(REQ_AUDIO)), 64, 44);
        add(sources, r2, 6);

        allowHttp = new CheckBox(this);
        allowHttp.setText("السماح بروابط HTTP غير المشفّرة");
        allowHttp.setTextColor(DIM);
        allowHttp.setTextSize(12);
        add(sources, allowHttp, 8);

        LinearLayout r3 = row();
        addWeighted(r3, button("⇄ تبديل", FIELD, v -> swapSources()), 1f, 48);
        addWeighted(r3, button("▶ تشغيل المصدرين", ACCENT, v -> startPlayback()), 2f, 48);
        add(sources, r3, 12);
        LinearLayout independentSwitches = row();
        addWeighted(independentSwitches,
                button("تبديل الصورة", 0xFF315C9E, v -> switchVideoSource()), 1f, 46);
        addWeighted(independentSwitches,
                button("تبديل الصوت", 0xFF287A58, v -> switchAudioSource()), 1f, 46);
        add(sources, independentSwitches, 8);

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
        allowHttp.setChecked(prefs.getBoolean("http", false));
        delayMs = prefs.getLong("delay", 0);
        stepMs = prefs.getLong("step", 100);
        refreshSteps();
        updateDelayLabel();
        handler.postDelayed(ticker, 250);
    }

    private void createPlayers() {
        videoPlayer = new ExoPlayer.Builder(this).build();
        audioPlayer = new ExoPlayer.Builder(this).build();

        videoPlayer.setTrackSelectionParameters(videoPlayer.getTrackSelectionParameters()
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        audioPlayer.setTrackSelectionParameters(audioPlayer.getTrackSelectionParameters()
                .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build());

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

    // ---------------- المصادر ----------------

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

    private void swapSources() {
        String v = videoUrl.getText().toString().trim();
        String a = audioUrl.getText().toString().trim();

        videoUrl.setText(a);
        audioUrl.setText(v);

        savePrefs();

        if (started) {
            startPlayback();
            notice("تم تبديل المصدرين وإعادة تشغيلهما");
        } else {
            notice("تم تبديل حقلي المصدرين");
        }
    }


    private void switchVideoSource() {
        if (!started) {
            notice("شغّل المصدرين أولاً");
            return;
        }
        videoUsingSecond = !videoUsingSecond;
        replaceActiveSource(V, videoUsingSecond ? sourceTwo : sourceOne);
        notice("تم تبديل مصدر الصورة فقط");
    }

    private void switchAudioSource() {
        if (!started) {
            notice("شغّل المصدرين أولاً");
            return;
        }
        audioUsingFirst = !audioUsingFirst;
        replaceActiveSource(A, audioUsingFirst ? sourceOne : sourceTwo);
        notice("تم تبديل مصدر الصوت فقط");
    }

    private void replaceActiveSource(int type, String url) {
        ExoPlayer p = player(type);
        if (p == null || url == null || url.trim().isEmpty()) return;
        boolean resume = p.getPlayWhenReady();
        p.stop();
        p.setMediaItem(createMediaItem(url));
        p.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        p.prepare();
        p.setPlayWhenReady(resume);
    }

    private boolean valid(String s) {
        String x = s.toLowerCase(Locale.US);
        if (x.startsWith("https://") || x.startsWith("content://") || x.startsWith("file://"))
            return true;
        return x.startsWith("http://") && allowHttp.isChecked();
    }

    private MediaItem createMediaItem(String value) {
        Uri uri = Uri.parse(value.trim());
        MediaItem.Builder b = new MediaItem.Builder().setUri(uri);
        String path = uri.getPath();
        if (path != null && path.toLowerCase(Locale.US).endsWith(".m3u8"))
            b.setMimeType(MimeTypes.APPLICATION_M3U8);
        b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                .setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build());
        return b.build();
    }

    private void startPlayback() {
        String v = videoUrl.getText().toString().trim();
        String a = audioUrl.getText().toString().trim();
        if (!valid(v)) { notice("رابط الصورة غير صالح (HTTP يحتاج تفعيل الخيار)"); return; }
        if (!valid(a)) { notice("رابط الصوت غير صالح (HTTP يحتاج تفعيل الخيار)"); return; }
        savePrefs();
        sourceOne = v;
        sourceTwo = a;
        videoUsingSecond = false;
        audioUsingFirst = false;


        sessionId++;                       // يُبطل أي إعادة محاولة قديمة
        cancelRetries();
        retries[V] = retries[A] = 0;
        needsRecovery[V] = needsRecovery[A] = false;
        lastAudioSpeed = -1f;
        lastSeekAt = 0;
        started = true;

        videoPlayer.stop();
        audioPlayer.stop();
        videoPlayer.setMediaItem(createMediaItem(v));
        audioPlayer.setMediaItem(createMediaItem(a));
        videoPlayer.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        audioPlayer.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        audioPlayer.setPlayWhenReady(false);
        videoPlayer.prepare();
        audioPlayer.prepare();
        videoPlayer.setPlayWhenReady(true);
    }

    // ---------------- المزامنة ----------------

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
        boolean live = isLive();
        long diff;          // موجب = الصوت متأخر عن المطلوب في البث / متقدم في الملفات

        if (live) {
            long vOff = videoPlayer.getCurrentLiveOffset();
            long aOff = audioPlayer.getCurrentLiveOffset();
            if (!audioPlayer.getPlayWhenReady()) audioPlayer.setPlayWhenReady(true);
            if (vOff == C.TIME_UNSET || aOff == C.TIME_UNSET || as != Player.STATE_READY) {
                syncInfo.setText("بث مباشر: بانتظار معلومات التوقيت");
                return;
            }
            long want = vOff + delayMs;          // الصوت يجب أن يكون أبعد عن الحافة بمقدار التأخير
            long lag = aOff - want;              // موجب = الصوت متأخر أكثر من المطلوب
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

        diff = audioPlayer.getCurrentPosition() - target;
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
        lastSeekAt = 0;   // يسمح للمزامنة بالتصحيح فورًا
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
        speedLabel.setText(String.format(Locale.US, "السرعة: %.2fx", SPEEDS[speedIndex]));
        if (videoPlayer != null)
            videoPlayer.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
        lastAudioSpeed = -1f;
    }

    // ---------------- الأخطاء والشبكة ----------------

    private void notice(String s) {
        notice = s;
        noticeUntil = SystemClock.elapsedRealtime() + 5000;
        status.setText(s);
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
    }

    private void onSourceError(int i, PlaybackException e) {
        needsRecovery[i] = true;
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player(i) != null) {
            player(i).seekToDefaultPosition();
        }
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
                if (!activityActive || !networkUp) return;   // تُستأنف عند العودة أو عودة الشبكة
                ExoPlayer p = player(i);
                if (p == null) return;
                notice("استعادة " + who(i) + "... المحاولة " + Math.max(1, retries[i]));
                p.prepare();
            }
        };
        retryTask[i] = task;
        handler.postDelayed(task, wait);
    }

    private void cancelRetries() {
        for (int i = 0; i < 2; i++) {
            if (retryTask[i] != null) handler.removeCallbacks(retryTask[i]);
            retryTask[i] = null;
        }
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
            networkUp = true;   // لو فشلت المراقبة نعتمد على أخطاء المشغل فقط
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
                .putBoolean("http", allowHttp.isChecked())
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
