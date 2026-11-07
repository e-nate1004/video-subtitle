package com.enate.videosubtitle;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 말소리 조각(PCM 16kHz 모노)을 Gemini에 보내 원문과 한국어 번역을 받는다. */
final class GeminiClient {

    static final class Result {
        final String original;
        final String korean;
        Result(String o, String k) { original = o; korean = k; }
    }

    static final class ApiException extends Exception {
        final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    private static final String PROMPT =
            "이 오디오는 동영상에서 나온 소리입니다. 대사는 주로 영어, 일본어, 중국어입니다.\n"
            + "1) 들리는 말을 원래 언어 그대로 받아 적고(orig), 2) 자연스러운 한국어 자막으로 번역하세요(ko).\n"
            + "- 자막답게 짧고 읽기 쉽게, 존댓말·반말은 원문 말투를 따르세요.\n"
            + "- 말소리가 없거나(음악·효과음·잡음뿐) 알아들을 수 없으면 orig와 ko를 모두 빈 문자열로 두세요.\n"
            + "- 이미 한국어인 말이면 ko에 그대로 적으세요.\n"
            + "- 설명이나 따옴표를 덧붙이지 마세요.\n"
            + "JSON 한 개만 출력: {\"orig\":\"...\",\"ko\":\"...\"}";

    static Result translate(String apiKey, String model, byte[] pcm, int sampleRate,
                            List<String> context) throws Exception {
        String wavB64 = Base64.encodeToString(wav(pcm, sampleRate), Base64.NO_WRAP);

        StringBuilder prompt = new StringBuilder(PROMPT);
        if (context != null && !context.isEmpty()) {
            prompt.append("\n\n참고용 바로 앞 대사(번역하지 말 것):\n");
            for (String c : context) prompt.append("- ").append(c).append('\n');
        }

        JSONObject audio = new JSONObject()
                .put("inline_data", new JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", wavB64));
        JSONObject text = new JSONObject().put("text", prompt.toString());
        JSONObject content = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(audio).put(text));

        try {
            return send(apiKey, model, content, true);
        } catch (ApiException e) {
            // 모델이 생각 설정을 받지 않으면 설정 없이 한 번 더
            if (e.code == 400 && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("think")) {
                return send(apiKey, model, content, false);
            }
            throw e;
        }
    }

    private static Result send(String apiKey, String model, JSONObject content,
                               boolean fastThinking) throws Exception {
        JSONObject gen = new JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json");
        if (fastThinking) {
            if (model.contains("gemini-2")) {
                if (model.contains("flash")) {
                    gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
                }
            } else {
                // Gemini 3 이후: 생각을 가장 짧게 해서 응답을 빠르게
                gen.put("thinkingConfig", new JSONObject().put("thinkingLevel", "minimal"));
            }
        }
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(content))
                .put("generationConfig", gen);

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setRequestProperty("x-goog-api-key", apiKey);
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        String resp = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
        con.disconnect();
        if (code >= 400) {
            String msg = resp;
            try {
                msg = new JSONObject(resp).getJSONObject("error").optString("message", resp);
            } catch (Exception ignored) { }
            throw new ApiException(code, msg);
        }

        JSONObject root = new JSONObject(resp);
        JSONArray cands = root.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return new Result("", "");
        JSONObject c0 = cands.getJSONObject(0).optJSONObject("content");
        if (c0 == null) return new Result("", "");
        JSONArray parts = c0.optJSONArray("parts");
        if (parts == null) return new Result("", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.getJSONObject(i);
            if (p.optBoolean("thought", false)) continue;
            out.append(p.optString("text", ""));
        }
        return parse(out.toString());
    }

    /**
     * 이 키로 쓸 수 있는 모델 중 자막 번역에 가장 알맞은 것(최신 정식 flash)을 고른다.
     * 키가 틀리면 ApiException.
     */
    static String pickModel(String apiKey) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        con.setRequestProperty("x-goog-api-key", apiKey);
        int code = con.getResponseCode();
        String resp = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
        con.disconnect();
        if (code >= 400) throw new ApiException(code, errorMessage(resp));

        JSONArray models = new JSONObject(resp).optJSONArray("models");
        String best = null;
        double bestScore = -1;
        if (models == null) return null;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            String name = m.optString("name", "").replace("models/", "");
            JSONArray methods = m.optJSONArray("supportedGenerationMethods");
            if (methods == null || !methods.toString().contains("generateContent")) continue;
            if (!name.startsWith("gemini-") || !name.contains("flash")) continue;
            if (name.matches(".*(image|tts|live|audio|embedding|robotics|exp|computer|thinking).*")) continue;
            double ver = version(name);
            double score = ver * 10;
            if (name.contains("lite")) score -= 3;      // lite는 번역 품질이 조금 낮음
            if (name.contains("preview")) score -= 5;   // 정식 모델 우선
            if (name.matches(".*-\\d{2,3}$")) score -= 0.5; // 날짜 붙은 고정판보다 기본 이름 우선
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    private static double version(String name) {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)").matcher(name);
        if (!mt.find()) return 0;
        try { return Double.parseDouble(mt.group(1)); } catch (Exception e) { return 0; }
    }

    private static String errorMessage(String resp) {
        try {
            return new JSONObject(resp).getJSONObject("error").optString("message", resp);
        } catch (Exception e) {
            return resp;
        }
    }

    private static Result parse(String s) {
        s = s.trim();
        if (s.startsWith("```")) {
            int a = s.indexOf('{');
            int b = s.lastIndexOf('}');
            if (a >= 0 && b > a) s = s.substring(a, b + 1);
        }
        try {
            JSONObject j = new JSONObject(s);
            return new Result(j.optString("orig", "").trim(), j.optString("ko", "").trim());
        } catch (Exception e) {
            return new Result("", s); // JSON이 아니면 통째로 자막으로
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** PCM16 모노를 WAV 파일 바이트로 감싼다. */
    static byte[] wav(byte[] pcm, int rate) {
        int dataLen = pcm.length;
        byte[] h = new byte[44];
        putStr(h, 0, "RIFF");
        putInt(h, 4, 36 + dataLen);
        putStr(h, 8, "WAVE");
        putStr(h, 12, "fmt ");
        putInt(h, 16, 16);
        putShort(h, 20, 1);      // PCM
        putShort(h, 22, 1);      // 모노
        putInt(h, 24, rate);
        putInt(h, 28, rate * 2); // byte rate
        putShort(h, 32, 2);      // block align
        putShort(h, 34, 16);     // bits
        putStr(h, 36, "data");
        putInt(h, 40, dataLen);
        byte[] out = new byte[44 + dataLen];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void putStr(byte[] b, int o, String s) {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8);
    }
}
