package com.modar.ai;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Неблокирующий клиент API. При недоступном сервере приложение продолжает работать офлайн. */
public final class ApiClient {
    public static final String BASE_URL = "http://10.0.2.2:8080"; // для реального телефона замените на IP компьютера/VPS
    public interface Callback { void done(boolean ok, String response); }
    public static void post(String path, String json, String token, Callback callback) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
                c.setRequestMethod("POST"); c.setConnectTimeout(3500); c.setReadTimeout(5000);
                c.setRequestProperty("Content-Type", "application/json");
                if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
                c.setDoOutput(true); try (OutputStream out = c.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder text = new StringBuilder();
                if (stream != null) { try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = r.readLine()) != null) text.append(line); } }
                callback.done(code >= 200 && code < 300, text.toString());
            } catch (Exception e) { callback.done(false, e.getMessage() == null ? "offline" : e.getMessage()); }
            finally { if (c != null) c.disconnect(); }
        }).start();
    }
    private ApiClient() {}
}
