package com.modar.family;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;

/**
 * Минимальный HTTP-клиент (HttpURLConnection, без сторонних библиотек).
 * Умеет GET/POST JSON и автоматически подставляет нужный токен: родителя или устройства.
 */
public class Http {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final int TIMEOUT_MS = 25000;

    public static class ApiException extends Exception {
        public final int code;
        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Базовый адрес сервера. */
    public static String base(Context ctx) {
        String url = Prefs.server(ctx);
        if (url.isEmpty()) {
            throw new IllegalStateException("Не указан адрес сервера");
        }
        return url;
    }

    public static JSONObject get(Context ctx, String path) throws Exception {
        return request(ctx, "GET", path, null);
    }

    public static JSONObject post(Context ctx, String path, JSONObject body) throws Exception {
        return request(ctx, "POST", path, body);
    }

    public static JSONObject request(Context ctx, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base(ctx) + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("User-Agent", "ModarFamily/2.1 Android");

            String token = Prefs.isParent(ctx) ? Prefs.parentToken(ctx) : Prefs.deviceToken(ctx);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] raw = body.toString().getBytes(UTF8);
                conn.setFixedLengthStreamingMode(raw.length);
                OutputStream out = conn.getOutputStream();
                out.write(raw);
                out.flush();
                out.close();
            }

            int code = conn.getResponseCode();
            String text = read(conn, code);
            if (code < 200 || code >= 300) {
                String message = "Ошибка связи (" + code + ")";
                try {
                    JSONObject parsed = new JSONObject(text);
                    if (parsed.has("error")) {
                        message = parsed.getString("error");
                    }
                } catch (Exception ignored) {
                }
                throw new ApiException(code, message);
            }
            if (text == null || text.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(text);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String read(HttpURLConnection conn, int code) throws Exception {
        InputStream stream = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
            stream = new GZIPInputStream(stream);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        stream.close();
        return new String(buffer.toByteArray(), UTF8);
    }

    /** Удобная проверка доступа к серверу при настройке. */
    public static JSONObject ping(String serverUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            String url = serverUrl;
            if (!url.startsWith("http")) {
                url = "http://" + url;
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            conn = (HttpURLConnection) new URL(url + "/api/v1/ping").openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new ApiException(code, "Сервер ответил кодом " + code);
            }
            return new JSONObject(read(conn, code));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

}
