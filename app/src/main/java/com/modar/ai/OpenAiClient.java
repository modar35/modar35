package com.modar.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Клиент любого OpenAI-совместимого API (/chat/completions).
 * Потоковый разбор SSE, поддержка изображений, отмена запроса.
 */
public class OpenAiClient {

    private static final String TAG = "OpenAiClient";

    public interface Callback {
        /** Очередной фрагмент ответа (в главном потоке). */
        void onDelta(String text);

        /** Ответ завершён. full — полный текст ответа. */
        void onDone(String full);

        /** Ошибка: сообщение готово к показу пользователю. */
        void onError(String message);
    }

    /** Запрос к модели. */
    public static class Request {
        public String baseUrl;
        public String apiKey;
        public String model;
        public String systemPrompt;
        public float temperature = 0.7f;
        public int maxTokens = 0;
        public boolean stream = true;
        public List<Message> messages;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private Thread worker;

    /** Адрес конечной точки: поддерживает и базу (/v1), и полный URL. */
    public static String endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.toLowerCase().endsWith("/chat/completions")) {
            return base;
        }
        if (base.toLowerCase().endsWith("/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    public static String modelsEndpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.toLowerCase().endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        return base + "/models";
    }

    public boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    /** Отменить текущий запрос (если он есть). */
    public void cancel() {
        cancelled = true;
        HttpURLConnection c = connection;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void send(final Request req, final Callback cb) {
        cancel();
        cancelled = false;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doSend(req, cb);
            }
        }, "openai-request");
        worker.start();
    }

    private void post(Runnable r) {
        main.post(r);
    }

    // ---------------------------------------------------------------- запрос

    private void doSend(Request req, Callback cb) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = buildBody(req);
            URL url = new URL(endpoint(req.baseUrl));
            conn = (HttpURLConnection) url.openConnection();
            connection = conn;
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", req.stream ? "text/event-stream" : "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + req.apiKey);
            conn.setRequestProperty("User-Agent", "ModarAI-Android/1.0");
            byte[] payload = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(conn.getErrorStream());
                final String msg = friendlyError(code, err);
                post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onError(msg);
                    }
                });
                return;
            }

            final StringBuilder full = new StringBuilder();
            if (req.stream) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled) {
                        break;
                    }
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String piece = parseDelta(data);
                    if (piece == null) {
                        continue;
                    }
                    if (piece.length() == 0) {
                        continue;
                    }
                    full.append(piece);
                    final String delta = piece;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onDelta(delta);
                        }
                    });
                }
                reader.close();
            } else {
                String raw = readAll(conn.getInputStream());
                String text = parseMessage(raw);
                full.append(text);
                final String whole = text;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (whole.length() > 0) {
                            cb.onDelta(whole);
                        }
                    }
                });
            }

            if (!cancelled) {
                final String result = full.toString();
                post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(result);
                    }
                });
            }
        } catch (Throwable t) {
            if (!cancelled) {
                final String msg = friendlyError(0, String.valueOf(t.getMessage() == null ? t.toString() : t.getMessage()));
                post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onError(msg);
                    }
                });
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            connection = null;
        }
    }

    private JSONObject buildBody(Request req) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", req.model);
        JSONArray messages = new JSONArray();

        if (req.systemPrompt != null && !req.systemPrompt.trim().isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", req.systemPrompt.trim());
            messages.put(sys);
        }

        if (req.messages != null) {
            for (int i = 0; i < req.messages.size(); i++) {
                Message m = req.messages.get(i);
                if (m.skipForModel || m.error) {
                    continue;
                }
                if (m.imagePath != null && m.isUser()) {
                    String dataUri = imageDataUri(m.imagePath);
                    if (dataUri != null) {
                        JSONObject msg = new JSONObject();
                        msg.put("role", m.role);
                        JSONArray parts = new JSONArray();
                        JSONObject textPart = new JSONObject();
                        textPart.put("type", "text");
                        textPart.put("text", m.text == null || m.text.trim().isEmpty()
                                ? "Что на этом изображении?" : m.text);
                        parts.put(textPart);
                        JSONObject imgPart = new JSONObject();
                        imgPart.put("type", "image_url");
                        JSONObject imgUrl = new JSONObject();
                        imgUrl.put("url", dataUri);
                        imgPart.put("image_url", imgUrl);
                        parts.put(imgPart);
                        msg.put("content", parts);
                        messages.put(msg);
                        continue;
                    }
                }
                if (m.text == null || m.text.trim().isEmpty()) {
                    continue;
                }
                JSONObject msg = new JSONObject();
                msg.put("role", m.role);
                msg.put("content", m.text);
                messages.put(msg);
            }
        }

        body.put("messages", messages);
        if (req.stream) {
            body.put("stream", true);
        }
        body.put("temperature", req.temperature);
        if (req.maxTokens > 0) {
            body.put("max_tokens", req.maxTokens);
        }
        return body;
    }

    /** Локальный файл → data:image/jpeg;base64,… (с сжатием до ~1024 px). */
    private String imageDataUri(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxSide / sample > 1024) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            bmp.recycle();
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;
        } catch (Throwable t) {
            Log.w(TAG, "image encode failed", t);
            return null;
        }
    }

    /** Разбор строки SSE: choices[0].delta.content либо choices[0].message.content. */
    private String parseDelta(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error")) {
                JSONObject e = o.optJSONObject("error");
                String m = e == null ? o.optString("error") : e.optString("message", "Ошибка API");
                throw new RuntimeException(m);
            }
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) {
                return null;
            }
            JSONObject delta = choice.optJSONObject("delta");
            if (delta != null) {
                String c = contentToString(delta.opt("content"));
                if (c != null) {
                    return c;
                }
                String r = delta.optString("reasoning_content", "");
                return r.isEmpty() ? null : r;
            }
            JSONObject msg = choice.optJSONObject("message");
            if (msg != null) {
                return contentToString(msg.opt("content"));
            }
            return null;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private String parseMessage(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (o.has("error")) {
                JSONObject e = o.optJSONObject("error");
                return "⚠️ " + (e == null ? o.optString("error") : e.optString("message", "Ошибка API"));
            }
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return "";
            }
            JSONObject choice = choices.optJSONObject(0);
            if (choice == null) {
                return "";
            }
            JSONObject msg = choice.optJSONObject("message");
            if (msg == null) {
                return "";
            }
            String c = contentToString(msg.opt("content"));
            return c == null ? "" : c;
        } catch (Exception e) {
            return "";
        }
    }

    /** content может быть строкой или массивом частей [{type:text,text:…}]. */
    private String contentToString(Object content) {
        if (content == null || content == JSONObject.NULL) {
            return null;
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object part = arr.opt(i);
                if (part instanceof JSONObject) {
                    JSONObject p = (JSONObject) part;
                    String t = p.optString("text", "");
                    if (!t.isEmpty()) {
                        sb.append(t);
                    }
                } else if (part instanceof String) {
                    sb.append((String) part);
                }
            }
            return sb.toString();
        }
        return null;
    }

    // ----------------------------------------------------------- утилиты/ошибки

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Понятное сообщение об ошибке вместо кодов HTTP. */
    public static String friendlyError(int code, String raw) {
        String detail = extractApiMessage(raw);
        switch (code) {
            case 0:
                return connectionErrorText(raw);
            case 400:
                return "Запрос отклонён (400). " + (detail.isEmpty() ? "Проверьте название модели и параметры." : detail);
            case 401:
                return "Неверный API-ключ (401). Откройте настройки и вставьте ключ заново.";
            case 403:
                return "Доступ запрещён (403). " + (detail.isEmpty() ? "Ключ не имеет прав на эту модель." : detail);
            case 404:
                return "Модель или адрес не найдены (404). " + (detail.isEmpty() ? "Проверьте Base URL и название модели." : detail);
            case 413:
                return "Слишком большой запрос (413). Попробуйте изображение меньшего размера.";
            case 422:
                return "Модель отклонила параметры (422). " + detail;
            case 429:
                return "Превышен лимит запросов (429). Подождите немного или проверьте баланс.";
            case 500:
            case 502:
            case 503:
            case 504:
                return "Сервер модели временно недоступен (" + code + "). Попробуйте ещё раз.";
            default:
                if (code > 0) {
                    return "Ошибка API (" + code + "). " + detail;
                }
                return detail.isEmpty() ? "Не удалось выполнить запрос." : detail;
        }
    }

    private static String connectionErrorText(String raw) {
        String r = raw == null ? "" : raw;
        if (r.contains("UnknownHostException") || r.contains("Unable to resolve host")) {
            return "Нет соединения: не удалось найти сервер. Проверьте интернет и адрес Base URL.";
        }
        if (r.contains("SocketTimeoutException") || r.contains("timeout") || r.contains("timed out")) {
            return "Сервер не ответил вовремя. Попробуйте ещё раз.";
        }
        if (r.contains("SSL") || r.contains("Cert")) {
            return "Ошибка защищённого соединения (SSL).";
        }
        if (r.contains("Connection refused") || r.contains("ECONNREFUSED")) {
            return "Сервер отклонил подключение. Проверьте адрес (для локальных моделей нужен IP компьютера, а не localhost).";
        }
        return "Ошибка сети: " + (r.isEmpty() ? "проверьте подключение" : r);
    }

    private static String extractApiMessage(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject o = new JSONObject(raw);
            Object err = o.opt("error");
            if (err instanceof JSONObject) {
                JSONObject e = (JSONObject) err;
                String m = e.optString("message", "");
                if (!m.isEmpty()) {
                    return m;
                }
            } else if (err instanceof String) {
                return (String) err;
            }
            String m = o.optString("message", "");
            if (!m.isEmpty()) {
                return m;
            }
        } catch (Exception ignored) {
        }
        String t = raw.trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    // --------------------------------------------------------- список моделей

    public interface ModelsCallback {
        void onModels(java.util.List<String> models, String error);
    }

    public void listModels(final String baseUrl, final String apiKey, final ModelsCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(modelsEndpoint(baseUrl));
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setRequestProperty("Accept", "application/json");
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        final String msg = friendlyError(code, readAll(conn.getErrorStream()));
                        post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onModels(null, msg);
                            }
                        });
                        return;
                    }
                    final String raw = readAll(conn.getInputStream());
                    JSONObject o = new JSONObject(raw);
                    JSONArray data = o.optJSONArray("data");
                    final java.util.List<String> ids = new java.util.ArrayList<String>();
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.optJSONObject(i);
                            if (m != null) {
                                String id = m.optString("id", "");
                                if (!id.isEmpty()) {
                                    ids.add(id);
                                }
                            }
                        }
                    }
                    java.util.Collections.sort(ids);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onModels(ids, ids.isEmpty() ? "Сервер вернул пустой список моделей." : null);
                        }
                    });
                } catch (UnknownHostException e) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onModels(null, "Нет соединения: сервер не найден.");
                        }
                    });
                } catch (SocketTimeoutException e) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onModels(null, "Сервер не ответил вовремя.");
                        }
                    });
                } catch (final Throwable t) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onModels(null, friendlyError(0, String.valueOf(t)));
                        }
                    });
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "openai-models").start();
    }

    /** Небольшая утилита для тестового изображения (не используется в UI). */
    static byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
