package com.enate.videosubtitle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 다른 앱(삼성 비디오 플레이어 등)에서 재생되는 미디어 소리만 받아
 * 말소리 단위로 잘라 Gemini로 번역하고, 화면 위/아래에 한국어 자막을 띄운다.
 */
public class CaptionService extends Service {
    static final String ACTION_START = "start";
    static final String ACTION_STOP = "stop";
    static final String ACTION_TOGGLE = "toggle";
    static final String ACTION_POSITION = "position";
    static final String ACTION_RELOAD = "reload";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";

    static volatile boolean running = false;

    private static final int NOTI_ID = 1;
    private static final String CHANNEL = "caption";

    // 소리 처리 설정
    private static final int RATE = 16000;
    private static final int FRAME_BYTES = RATE / 10 * 2;   // 100ms
    private static final int PREROLL_FRAMES = 3;            // 말 시작 전 0.3초 포함
    private static final int END_SILENCE_FRAMES = 7;        // 0.7초 쉬면 문장 끝
    private static final int MAX_FRAMES = 70;               // 최대 7초
    private static final int MIN_SPEECH_FRAMES = 4;         // 0.4초 미만은 버림

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private WindowManager wm;

    private MediaProjection projection;
    private AudioRecord record;
    private Thread captureThread;
    private volatile boolean capturing = false;
    private volatile boolean enabled = true;
    private boolean stopping = false;

    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
    private ThreadPoolExecutor executor;
    private final AtomicLong seq = new AtomicLong();
    private volatile long shownSeq = 0;
    private final ArrayDeque<String> context = new ArrayDeque<>();
    private long lastErrorAt = 0;

    // 화면 요소
    private FrameLayout captionWindow;
    private LinearLayout captionBox;
    private TextView koView, origView, bubble;
    private WindowManager.LayoutParams captionParams, bubbleParams;
    private Typeface font;
    private final Runnable hideCaption = () -> {
        if (captionBox != null) captionBox.setVisibility(View.GONE);
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        wm = getSystemService(WindowManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "자막 실행", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            start(intent);
            return START_NOT_STICKY;
        }
        if (!running) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
        } else if (ACTION_TOGGLE.equals(action)) {
            setEnabled(!enabled);
        } else if (ACTION_POSITION.equals(action)) {
            switchPosition();
        } else if (ACTION_RELOAD.equals(action)) {
            applyStyle();
        }
        return START_NOT_STICKY;
    }

    // ───────────────────────── 시작 / 종료

    private void start(Intent intent) {
        if (running) shutdown();
        stopping = false;
        startForeground(NOTI_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(() -> {
                    if (!stopping) {
                        shutdown();
                        stopSelf();
                    }
                });
            }
        }, main);

        executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, queue);
        enabled = true;
        running = true;
        font = loadFont();
        createOverlay();
        startCapture();
    }

    private void shutdown() {
        stopping = true;
        running = false;
        capturing = false;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { } // 읽기 대기를 풀어 줌
        }
        if (captureThread != null) {
            try { captureThread.join(1000); } catch (InterruptedException ignored) { }
            captureThread = null;
        }
        if (record != null) {
            record.release();
            record = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        queue.clear();
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
            projection = null;
        }
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override
    public void onDestroy() {
        if (running) shutdown();
        super.onDestroy();
    }

    // ───────────────────────── 소리 받기 + 말소리 자르기

    private void startCapture() {
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            record = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(minBuf * 4, RATE * 2))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            record.startRecording();
        } catch (Exception e) {
            showError("⚠ 소리를 받을 수 없습니다 (" + e.getClass().getSimpleName() + ")");
            return;
        }
        capturing = true;
        captureThread = new Thread(this::captureLoop, "capture");
        captureThread.start();
    }

    private void captureLoop() {
        final AudioRecord rec = record;
        if (rec == null) return;
        byte[] frame = new byte[FRAME_BYTES];
        ArrayDeque<byte[]> preroll = new ArrayDeque<>();
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        boolean inSpeech = false;
        int segFrames = 0, speechFrames = 0, silentFrames = 0;
        double noise = 150;

        while (capturing) {
            int got = 0;
            while (got < FRAME_BYTES && capturing) {
                int n = rec.read(frame, got, FRAME_BYTES - got);
                if (n <= 0) break;
                got += n;
            }
            if (got < FRAME_BYTES) {
                if (!capturing) break;
                continue;
            }

            double rms = rms(frame);
            double threshold = Math.max(350, noise * 2.5);
            boolean loud = rms > threshold;
            if (!inSpeech && !loud) noise = noise * 0.95 + rms * 0.05;

            if (!enabled) {
                inSpeech = false;
                seg.reset();
                preroll.clear();
                continue;
            }

            if (!inSpeech) {
                if (loud) {
                    inSpeech = true;
                    seg.reset();
                    for (byte[] p : preroll) seg.write(p, 0, p.length);
                    segFrames = preroll.size();
                    preroll.clear();
                    seg.write(frame, 0, FRAME_BYTES);
                    segFrames++;
                    speechFrames = 1;
                    silentFrames = 0;
                } else {
                    preroll.addLast(Arrays.copyOf(frame, FRAME_BYTES));
                    while (preroll.size() > PREROLL_FRAMES) preroll.removeFirst();
                }
                continue;
            }

            seg.write(frame, 0, FRAME_BYTES);
            segFrames++;
            if (loud) {
                speechFrames++;
                silentFrames = 0;
            } else {
                silentFrames++;
            }
            if (silentFrames >= END_SILENCE_FRAMES || segFrames >= MAX_FRAMES) {
                if (speechFrames >= MIN_SPEECH_FRAMES) submit(seg.toByteArray());
                seg.reset();
                inSpeech = false;
                segFrames = speechFrames = silentFrames = 0;
            }
        }
    }

    private static double rms(byte[] b) {
        long sum = 0;
        int n = b.length / 2;
        for (int i = 0; i < n; i++) {
            int s = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
            sum += (long) s * s;
        }
        return Math.sqrt((double) sum / n);
    }

    // ───────────────────────── 번역

    private void submit(byte[] pcm) {
        ThreadPoolExecutor ex = executor;
        if (ex == null || ex.isShutdown()) return;
        // 밀리면 오래된 것부터 버리고 최신 대사를 우선
        while (queue.size() >= 2) queue.pollFirst();
        final long id = seq.incrementAndGet();
        try {
            ex.execute(() -> translate(id, pcm));
        } catch (Exception ignored) { }
    }

    private void translate(long id, byte[] pcm) {
        List<String> ctx;
        synchronized (context) {
            ctx = new ArrayList<>(context);
        }
        String key = prefs.apiKey();
        String model = prefs.model();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!running || !enabled) return;
            try {
                GeminiClient.Result r = GeminiClient.translate(key, model, pcm, RATE, ctx);
                if (r.korean.isEmpty()) return;
                if (!r.original.isEmpty()) {
                    synchronized (context) {
                        context.addLast(r.original);
                        while (context.size() > 3) context.removeFirst();
                    }
                }
                main.post(() -> {
                    if (id < shownSeq || !running || !enabled) return;
                    shownSeq = id;
                    showCaption(r.korean, r.original);
                });
                return;
            } catch (GeminiClient.ApiException e) {
                if (e.code == 429) {
                    showError("⚠ 요청 한도 초과 — 잠시 후 다시 나옵니다");
                    return;
                }
                if (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 404) {
                    showError("⚠ API 키 또는 모델(" + model + ")을 확인해 주세요");
                    return;
                }
                sleep(800L * (attempt + 1)); // 500대 오류는 재시도
            } catch (IOException e) {
                sleep(800L * (attempt + 1));  // 네트워크 오류 재시도
            } catch (Exception e) {
                return;
            }
        }
        showError("⚠ 인터넷 연결을 확인해 주세요");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void showError(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastErrorAt < 15000) return;
        lastErrorAt = now;
        main.post(() -> {
            if (running) showCaption(msg, "");
        });
    }

    // ───────────────────────── 화면 (자막 + 버튼)

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private Typeface loadFont() {
        try {
            String[] list = getAssets().list("");
            if (list != null && Arrays.asList(list).contains("NotoSansKR.ttf")) {
                Typeface t = new Typeface.Builder(getAssets(), "NotoSansKR.ttf")
                        .setFontVariationSettings("'wght' 500")
                        .build();
                if (t != null) return t;
            }
        } catch (Exception ignored) { }
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private void createOverlay() {
        // 자막 창 (터치는 아래 앱으로 그대로 통과)
        captionWindow = new FrameLayout(this);
        captionBox = new LinearLayout(this);
        captionBox.setOrientation(LinearLayout.VERTICAL);
        captionBox.setGravity(Gravity.CENTER_HORIZONTAL);
        captionBox.setPadding(dp(14), dp(6), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(160, 0, 0, 0));
        bg.setCornerRadius(dp(8));
        captionBox.setBackground(bg);

        koView = new TextView(this);
        koView.setTextColor(Color.WHITE);
        koView.setGravity(Gravity.CENTER);
        koView.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        origView = new TextView(this);
        origView.setTextColor(Color.parseColor("#D1D5DB"));
        origView.setGravity(Gravity.CENTER);
        captionBox.addView(koView);
        captionBox.addView(origView);
        captionBox.setVisibility(View.GONE);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL);
        boxLp.leftMargin = dp(16);
        boxLp.rightMargin = dp(16);
        captionWindow.addView(captionBox, boxLp);

        captionParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        applyStyle();
        wm.addView(captionWindow, captionParams);

        // 동그란 '자막' 버튼
        bubble = new TextView(this);
        bubble.setText("자막");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setTypeface(font);
        bubble.setGravity(Gravity.CENTER);
        bubble.setAlpha(0.85f);
        updateBubble();

        int size = dp(52);
        bubbleParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        bubbleParams.x = prefs.bubbleX() >= 0 ? prefs.bubbleX() : sw - size - dp(8);
        bubbleParams.y = prefs.bubbleY() >= 0 ? prefs.bubbleY() : sh / 3;
        bubble.setOnTouchListener(new BubbleTouch());
        wm.addView(bubble, bubbleParams);
    }

    private void removeOverlay() {
        main.removeCallbacks(hideCaption);
        if (captionWindow != null) {
            try { wm.removeView(captionWindow); } catch (Exception ignored) { }
            captionWindow = null;
        }
        if (bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
            bubble = null;
        }
    }

    /** 글자 크기·위치·원문 표시를 설정값대로 적용 */
    private void applyStyle() {
        if (koView == null) return;
        int size = prefs.textSize();
        koView.setTextSize(size);
        koView.setTypeface(font);
        origView.setTextSize(Math.max(11, size * 0.65f));
        origView.setTypeface(font);
        origView.setVisibility(prefs.showOriginal() && origView.getText().length() > 0 ? View.VISIBLE : View.GONE);
        captionParams.gravity = (prefs.top() ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        captionParams.y = dp(prefs.top() ? 32 : 40);
        if (captionWindow != null && captionWindow.isAttachedToWindow()) {
            wm.updateViewLayout(captionWindow, captionParams);
        }
    }

    private void showCaption(String ko, String orig) {
        if (koView == null) return;
        koView.setText(ko);
        origView.setText(orig);
        origView.setVisibility(prefs.showOriginal() && !orig.isEmpty() ? View.VISIBLE : View.GONE);
        captionBox.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideCaption);
        long ms = Math.min(8000, Math.max(3000, 2500 + ko.length() * 90L));
        main.postDelayed(hideCaption, ms);
    }

    private void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            queue.clear();
            main.removeCallbacks(hideCaption);
            if (captionBox != null) captionBox.setVisibility(View.GONE);
        }
        updateBubble();
        updateNotification();
    }

    private void switchPosition() {
        prefs.setTop(!prefs.top());
        applyStyle();
        updateNotification();
        showCaption(prefs.top() ? "자막 위치: 위" : "자막 위치: 아래", "");
    }

    private void updateBubble() {
        if (bubble == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(enabled ? Color.parseColor("#16A34A") : Color.parseColor("#6B7280"));
        d.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(d);
        bubble.setText(enabled ? "자막" : "꺼짐");
    }

    /** 탭=켜기/끄기, 길게=위·아래 전환, 끌기=이동 */
    private class BubbleTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging, longFired;
        private final int slop = ViewConfiguration.get(CaptionService.this).getScaledTouchSlop();
        private final Runnable longPress = () -> {
            if (!dragging) {
                longFired = true;
                switchPosition();
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    dragging = false;
                    longFired = false;
                    main.postDelayed(longPress, 600);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true;
                        main.removeCallbacks(longPress);
                    }
                    if (dragging) {
                        bubbleParams.x = Math.max(0, startX + (int) dx);
                        bubbleParams.y = Math.max(0, startY + (int) dy);
                        wm.updateViewLayout(bubble, bubbleParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (dragging) {
                        prefs.setBubble(bubbleParams.x, bubbleParams.y);
                    } else if (!longFired) {
                        setEnabled(!enabled);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
            }
            return false;
        }
    }

    // ───────────────────────── 알림

    private PendingIntent servicePi(String action, int req) {
        Intent i = new Intent(this, CaptionService.class).setAction(action);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_notify);
        boolean top = prefs.top();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("영상 자막 실행 중")
                .setContentText((enabled ? "자막 켜짐" : "자막 꺼짐") + " · " + (top ? "위" : "아래"))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(icon, enabled ? "끄기" : "켜기",
                        servicePi(ACTION_TOGGLE, 1)).build())
                .addAction(new Notification.Action.Builder(icon, top ? "아래로" : "위로",
                        servicePi(ACTION_POSITION, 2)).build())
                .addAction(new Notification.Action.Builder(icon, "종료",
                        servicePi(ACTION_STOP, 3)).build())
                .build();
    }

    private void updateNotification() {
        if (!running) return;
        getSystemService(NotificationManager.class).notify(NOTI_ID, buildNotification());
    }
}
