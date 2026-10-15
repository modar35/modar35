package com.modar.samp;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Клиент сервера лаунчера (Node.js, см. samp-launcher/server):
 * список серверов, мод-паки, новости и обновления самого лаунчера.
 */
public final class Api {

    public static final int TIMEOUT_MS = 20000;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private Api() {
    }

    public static class ApiException extends Exception {
        public final int code;

        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static String base(Context ctx) {
        return new Prefs(ctx).apiUrl();
    }

    public static boolean configured(Context ctx) {
        return !base(ctx).isEmpty();
    }

    public static JSONObject get(Context ctx, String path) throws Exception {
        return request(base(ctx), "GET", path, null);
    }

    public static JSONObject post(Context ctx, String path, JSONObject body) throws Exception {
        return request(base(ctx), "POST", path, body);
    }

    /** Разовый запрос к произвольному адресу — используется кнопкой «Проверить связь». */
    public static JSONObject ping(String baseUrl) throws Exception {
        String url = normalize(baseUrl);
        if (url.isEmpty()) {
            throw new ApiException(0, "Адрес не указан");
        }
        return request(url, "GET", "/api/v1/ping", null);
    }

    /* ------------------------------------------------------------------ разделы API */

    /** Серверы со статусом онлайна (сервер лаунчера опрашивает их сам). */
    public static List<Server> servers(Context ctx) throws Exception {
        JSONObject json = get(ctx, "/api/v1/servers?live=1");
        return parseServers(json, ctx);
    }

    public static List<Server> parseServers(JSONObject json, Context ctx) throws Exception {
        List<Server> list = new ArrayList<Server>();
        JSONArray array = json.optJSONArray("servers");
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Server server = new Server();
            server.id = item.optString("id", "");
            server.name = item.optString("name", "");
            server.host = item.optString("host", "");
            server.port = item.optInt("port", SampQuery.DEFAULT_PORT);
            server.gamemode = item.optString("gamemode", "");
            server.note = item.optString("note", "");
            String password = item.optString("password", "");
            server.password = "true".equalsIgnoreCase(password) ? "" : password;
            server.source = Server.SOURCE_API;
            if (server.host.isEmpty()) {
                continue;
            }
            JSONObject live = item.optJSONObject("live");
            if (live != null && live.optBoolean("online", false)) {
                SampQuery.Info info = new SampQuery.Info();
                info.host = server.host;
                info.port = server.port;
                info.hostname = live.optString("hostname", "");
                info.gamemode = live.optString("gamemode", "");
                info.language = live.optString("language", "");
                info.players = live.optInt("players", 0);
                info.maxPlayers = live.optInt("maxPlayers", 0);
                info.password = live.optBoolean("password", false);
                info.rtt = live.optInt("rtt", 0);
                info.time = live.optLong("time", System.currentTimeMillis());
                server.last = info;
            }
            list.add(server);
        }
        return list;
    }

    public static List<Mod> mods(Context ctx) throws Exception {
        JSONObject json = get(ctx, "/api/v1/mods");
        List<Mod> list = new ArrayList<Mod>();
        JSONArray array = json.optJSONArray("mods");
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                Mod mod = Mod.fromJson(item, base(ctx));
                if (mod != null) {
                    list.add(mod);
                }
            }
        }
        return list;
    }

    public static List<News> news(Context ctx) throws Exception {
        JSONObject json = get(ctx, "/api/v1/news");
        List<News> list = new ArrayList<News>();
        JSONArray array = json.optJSONArray("news");
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                News news = News.fromJson(item);
                if (news != null) {
                    list.add(news);
                }
            }
        }
        return list;
    }

    /** Сведения о новой версии лаунчера на сервере. */
    public static News.Update update(Context ctx) throws Exception {
        JSONObject json = get(ctx, "/api/v1/launcher");
        return News.Update.fromJson(json, base(ctx));
    }

    /* ------------------------------------------------------------------ транспорт */

    public static JSONObject request(String baseUrl, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection conn = null;
        try {
            String url = normalize(baseUrl);
            if (url.isEmpty()) {
                throw new ApiException(0, "Не указан адрес сервера лаунчера (Настройки → Адрес сервера)");
            }
            conn = (HttpURLConnection) new URL(url + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("User-Agent", "ModarSAMP/1.0 Android");
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
                String message = "Сервер ответил кодом " + code;
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

    public static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String result = url.trim();
        if (result.isEmpty()) {
            return "";
        }
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            result = "http://" + result;
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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
}
