package com.enate.videosubtitle;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 1;
    private static final int REQ_NOTI = 2;
    private static final int REQ_PROJECTION = 3;

    private Prefs prefs;
    private EditText keyEdit, modelEdit;
    private SeekBar sizeBar;
    private TextView sizeLabel, statusText;
    private RadioButton topRadio, bottomRadio;
    private CheckBox origCheck;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("영상 자막 (영·일·중 → 한국어)");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("삼성 비디오 플레이어 등에서 재생되는 소리를 받아 화면 위·아래에 한국어 자막을 띄웁니다.");
        desc.setTextSize(14);
        desc.setPadding(0, dp(6), 0, dp(18));
        root.addView(desc);

        root.addView(label("Gemini API 키"));
        keyEdit = new EditText(this);
        keyEdit.setSingleLine(true);
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyEdit.setHint("AIza...");
        keyEdit.setText(prefs.apiKey());
        root.addView(keyEdit);

        root.addView(label("모델"));
        modelEdit = new EditText(this);
        modelEdit.setSingleLine(true);
        modelEdit.setText(prefs.model());
        root.addView(modelEdit);

        Button check = new Button(this);
        check.setText("키 확인 · 모델 자동 선택");
        check.setOnClickListener(v -> checkKey(check));
        root.addView(check);

        sizeLabel = label("");
        root.addView(sizeLabel);
        sizeBar = new SeekBar(this);
        sizeBar.setMax(26); // 14 ~ 40
        sizeBar.setProgress(prefs.textSize() - 14);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { updateSizeLabel(); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sizeBar);
        updateSizeLabel();

        root.addView(label("자막 위치"));
        RadioGroup pos = new RadioGroup(this);
        pos.setOrientation(RadioGroup.HORIZONTAL);
        bottomRadio = new RadioButton(this);
        bottomRadio.setText("아래");
        bottomRadio.setId(View.generateViewId());
        topRadio = new RadioButton(this);
        topRadio.setText("위");
        topRadio.setId(View.generateViewId());
        pos.addView(bottomRadio);
        pos.addView(topRadio);
        pos.check(prefs.top() ? topRadio.getId() : bottomRadio.getId());
        root.addView(pos);

        origCheck = new CheckBox(this);
        origCheck.setText("원문도 함께 보기");
        origCheck.setChecked(prefs.showOriginal());
        root.addView(origCheck);

        Button start = new Button(this);
        start.setText("자막 시작");
        start.setTextSize(18);
        start.setBackgroundColor(Color.parseColor("#FACC15"));
        start.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(20);
        start.setLayoutParams(lp);
        start.setOnClickListener(v -> {
            saveSettings();
            proceed();
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("자막 종료");
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp2.topMargin = dp(8);
        stop.setLayoutParams(lp2);
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptionService.class).setAction(CaptionService.ACTION_STOP);
            startService(i);
            statusText.postDelayed(this::updateStatus, 300);
        });
        root.addView(stop);

        statusText = new TextView(this);
        statusText.setPadding(0, dp(14), 0, 0);
        statusText.setTextSize(14);
        root.addView(statusText);

        TextView help = new TextView(this);
        help.setText("\n사용법\n"
                + "1. '자막 시작'을 누르고 권한을 차례로 허용합니다.\n"
                + "2. 화면 공유 창에서 '시작'을 누릅니다 (소리만 사용, 화면은 저장 안 함).\n"
                + "3. 비디오 플레이어에서 영상을 재생하면 자막이 나옵니다.\n\n"
                + "동그란 '자막' 버튼\n"
                + "· 탭: 자막 켜기/끄기\n"
                + "· 길게 누르기: 위/아래 전환\n"
                + "· 끌기: 버튼 위치 이동\n\n"
                + "※ 일부 앱(넷플릭스 등)은 소리 캡처를 막아 자막이 안 나올 수 있습니다.");
        help.setTextSize(13);
        help.setGravity(Gravity.START);
        root.addView(help);
        return scroll;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(Color.DKGRAY);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private void updateSizeLabel() {
        sizeLabel.setText("글자 크기: " + (sizeBar.getProgress() + 14));
    }

    private void updateStatus() {
        if (statusText == null) return;
        statusText.setText(CaptionService.running ? "상태: 실행 중 ✅" : "상태: 꺼짐");
    }

    private void saveSettings() {
        prefs.save(keyEdit.getText().toString(), modelEdit.getText().toString(),
                sizeBar.getProgress() + 14, topRadio.isChecked(), origCheck.isChecked());
        if (CaptionService.running) {
            startService(new Intent(this, CaptionService.class).setAction(CaptionService.ACTION_RELOAD));
        }
    }

    /** 권한을 하나씩 확인하고, 다 되면 화면 공유(소리 캡처) 허락을 요청한다. */
    private void proceed() {
        if (prefs.apiKey().isEmpty()) {
            toast("Gemini API 키를 입력해 주세요.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !notiAsked) {
            notiAsked = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("'다른 앱 위에 표시'를 허용한 뒤 다시 '자막 시작'을 눌러 주세요.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    private boolean notiAsked = false;

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) proceed();
            else toast("소리를 받으려면 '마이크(오디오 녹음)' 권한이 필요합니다.");
        } else if (code == REQ_NOTI) {
            proceed(); // 알림은 거부해도 동작은 함
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_PROJECTION) return;
        if (result != RESULT_OK || data == null) {
            toast("화면 공유를 허용해야 재생 소리를 받을 수 있습니다.");
            return;
        }
        Intent i = new Intent(this, CaptionService.class)
                .setAction(CaptionService.ACTION_START)
                .putExtra(CaptionService.EXTRA_CODE, result)
                .putExtra(CaptionService.EXTRA_DATA, data);
        startForegroundService(i);
        toast("자막을 시작합니다. 비디오 플레이어에서 영상을 재생하세요.");
        moveTaskToBack(true);
    }

    /** 키가 맞는지 확인하고, 이 키로 쓸 수 있는 최신 flash 모델을 골라 넣는다. */
    private void checkKey(Button btn) {
        String key = keyEdit.getText().toString().trim();
        if (key.isEmpty()) {
            toast("Gemini API 키를 먼저 입력해 주세요.");
            return;
        }
        btn.setEnabled(false);
        btn.setText("확인 중…");
        new Thread(() -> {
            String msg;
            String picked = null;
            try {
                picked = GeminiClient.pickModel(key);
                msg = picked == null ? "키는 맞지만 쓸 수 있는 flash 모델을 찾지 못했습니다."
                        : "키 확인 완료 ✅ 모델: " + picked;
            } catch (GeminiClient.ApiException e) {
                msg = "키 오류 (" + e.code + "): " + e.getMessage();
            } catch (Exception e) {
                msg = "인터넷 연결을 확인해 주세요. (" + e.getClass().getSimpleName() + ")";
            }
            final String m = msg, p = picked;
            runOnUiThread(() -> {
                btn.setEnabled(true);
                btn.setText("키 확인 · 모델 자동 선택");
                if (p != null) {
                    modelEdit.setText(p);
                    saveSettings();
                }
                toast(m);
            });
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
