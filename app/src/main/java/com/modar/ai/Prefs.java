package com.modar.ai;

import android.content.Context;
import android.content.SharedPreferences;

/** Локальные настройки приложения (ключ API, модель, голос и т.д.). */
public class Prefs {

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-6-astra";
    public static final String DEFAULT_SYSTEM =
            "Ты — Modar AI, дружелюбный и точный ИИ-ассистент. Отвечай на языке пользователя, "
            + "структурно и по делу. Используй Markdown, когда это делает ответ понятнее.";

    private static final String FILE = "modar_settings";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private String get(String key, String def) {
        String v = sp.getString(key, def);
        return v == null ? def : v;
    }

    public String apiKey() {
        return get("api_key", "").trim();
    }

    public void setApiKey(String v) {
        sp.edit().putString("api_key", v == null ? "" : v.trim()).apply();
    }

    public String baseUrl() {
        String v = get("base_url", DEFAULT_BASE_URL).trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.isEmpty() ? DEFAULT_BASE_URL : v;
    }

    public void setBaseUrl(String v) {
        sp.edit().putString("base_url", v == null ? "" : v.trim()).apply();
    }

    public String model() {
        String v = get("model", DEFAULT_MODEL).trim();
        return v.isEmpty() ? DEFAULT_MODEL : v;
    }

    public void setModel(String v) {
        sp.edit().putString("model", v == null ? "" : v.trim()).apply();
    }

    public String systemPrompt() {
        String v = get("system_prompt", DEFAULT_SYSTEM);
        return v.trim().isEmpty() ? DEFAULT_SYSTEM : v;
    }

    public void setSystemPrompt(String v) {
        sp.edit().putString("system_prompt", v == null ? "" : v).apply();
    }

    public float temperature() {
        return sp.getFloat("temperature", 0.7f);
    }

    public void setTemperature(float v) {
        sp.edit().putFloat("temperature", v).apply();
    }

    /** 0 = не отправлять ограничение (пусть решает модель). */
    public int maxTokens() {
        return sp.getInt("max_tokens", 0);
    }

    public void setMaxTokens(int v) {
        sp.edit().putInt("max_tokens", Math.max(0, v)).apply();
    }

    public boolean ttsEnabled() {
        return sp.getBoolean("tts", false);
    }

    public void setTtsEnabled(boolean v) {
        sp.edit().putBoolean("tts", v).apply();
    }

    public String speechLocale() {
        String v = get("locale", "ru-RU").trim();
        return v.isEmpty() ? "ru-RU" : v;
    }

    public void setSpeechLocale(String v) {
        sp.edit().putString("locale", v == null ? "ru-RU" : v.trim()).apply();
    }

    public boolean streaming() {
        return sp.getBoolean("stream", true);
    }

    public void setStreaming(boolean v) {
        sp.edit().putBoolean("stream", v).apply();
    }

    public boolean isConfigured() {
        return !apiKey().isEmpty();
    }
}
