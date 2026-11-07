package com.enate.videosubtitle;

import android.content.Context;
import android.content.SharedPreferences;

/** 이 폰의 앱 전용 저장소에만 설정을 보관한다 (백업 꺼짐). */
final class Prefs {
    static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    String apiKey() { return sp.getString("key", ""); }
    String model() {
        String m = sp.getString("model", DEFAULT_MODEL).trim();
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }
    int textSize() { return sp.getInt("size", 20); }
    boolean top() { return sp.getBoolean("top", false); }
    boolean showOriginal() { return sp.getBoolean("orig", false); }
    int bubbleX() { return sp.getInt("bx", -1); }
    int bubbleY() { return sp.getInt("by", -1); }

    void save(String key, String model, int size, boolean top, boolean orig) {
        sp.edit()
                .putString("key", key.trim())
                .putString("model", model.trim())
                .putInt("size", size)
                .putBoolean("top", top)
                .putBoolean("orig", orig)
                .apply();
    }

    void setTop(boolean top) { sp.edit().putBoolean("top", top).apply(); }
    void setBubble(int x, int y) { sp.edit().putInt("bx", x).putInt("by", y).apply(); }
}
