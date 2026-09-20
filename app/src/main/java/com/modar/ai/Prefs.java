package com.modar.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/** Локальные настройки приложения (ключ API, модель, голос и т.д.). */
public class Prefs {

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-6-astra";
    public static final String DEFAULT_SYSTEM =
            "Ты — Modar AI, дружелюбный и точный ИИ-ассистент. Отвечай на языке пользователя, "
            + "структурно и по делу. Используй Markdown, когда это делает ответ понятнее.";

    private static final String FILE = "modar_settings";

    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
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
        return !apiKey().isEmpty() || isLocalServer();
    }

    /** Локальный сервер (Ollama, LM Studio, llama.cpp) — ключ обычно не нужен. */
    public boolean isLocalServer() {
        String host = host();
        if (host.isEmpty()) {
            return false;
        }
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || host.startsWith("127.") || host.endsWith(".local") || host.startsWith("fe80::")) {
            return true;
        }
        if (host.startsWith("192.168.") || host.startsWith("10.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    /** Имя хоста из Base URL (без схемы, порта и пути). */
    public String host() {
        String url = baseUrl();
        int scheme = url.indexOf("://");
        String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        if (rest.startsWith("[")) { // IPv6 в квадратных скобках
            int end = rest.indexOf(']');
            return end > 0 ? rest.substring(0, end + 1).toLowerCase(Locale.ROOT) : rest.toLowerCase(Locale.ROOT);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest.toLowerCase(Locale.ROOT);
    }

    /**
     * Предзаполнение настроек из assets (используется при сборке с параметрами
     * API_KEY / BASE_URL / MODEL — см. tools/build-apk.sh). Уже введённые
     * пользователем значения не перезаписываются.
     */
    public void seedFromAssetsIfEmpty() {
        if (apiKey().isEmpty()) {
            String key = readAsset("api_key.txt");
            if (key != null && !key.isEmpty()) {
                setApiKey(key);
            }
        }
        if (!sp.contains("base_url")) {
            String url = readAsset("base_url.txt");
            if (url != null && !url.isEmpty()) {
                setBaseUrl(url);
            }
        }
        if (!sp.contains("model")) {
            String model = readAsset("model.txt");
            if (model != null && !model.isEmpty()) {
                setModel(model);
            }
        }
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
