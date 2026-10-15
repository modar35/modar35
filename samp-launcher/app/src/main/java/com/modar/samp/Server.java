package com.modar.samp;

import org.json.JSONObject;

/** Сервер SA-MP, сохранённый в лаунчере (или пришедший из API лаунчера). */
public class Server {

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_API = "api";

    public String id = "";
    /** Своё название сервера; если пусто — показываем имя из опроса. */
    public String name = "";
    public String host = "";
    public int port = SampQuery.DEFAULT_PORT;
    /** Пароль сервера (если нужен для входа). */
    public String password = "";
    public boolean favorite;
    /** manual — добавлен вручную, api — пришёл со списком лаунчера. */
    public String source = SOURCE_MANUAL;
    public String gamemode = "";
    public String note = "";
    public long addedAt;

    /** Последний результат опроса — чтобы список выглядел живым даже без сети. */
    public SampQuery.Info last;

    /** Сервер не ответил при последнем опросе (в файл не пишется). */
    public boolean offline;

    public String statusLabel() {
        if (offline) {
            return "оффлайн";
        }
        if (last != null) {
            return last.fill();
        }
        return "не опрошен";
    }

    public String address() {
        return host + ":" + port;
    }

    /** Что показываем заголовком строки. */
    public String title() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (last != null && last.hostname != null && !last.hostname.trim().isEmpty()) {
            return last.hostname.trim();
        }
        return address();
    }

    public String gamemodeLabel() {
        String mode = last != null && last.gamemode != null && !last.gamemode.isEmpty() ? last.gamemode : gamemode;
        return mode == null ? "" : mode.trim();
    }

    public boolean online() {
        return last != null && System.currentTimeMillis() - last.time < 5 * 60 * 1000L;
    }

    public boolean needsPassword() {
        return (password != null && !password.isEmpty()) || (last != null && last.password);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("host", host);
            json.put("port", port);
            json.put("password", password);
            json.put("favorite", favorite);
            json.put("source", source);
            json.put("gamemode", gamemode);
            json.put("note", note);
            json.put("addedAt", addedAt);
            if (last != null) {
                JSONObject cache = new JSONObject();
                cache.put("hostname", last.hostname);
                cache.put("gamemode", last.gamemode);
                cache.put("language", last.language);
                cache.put("players", last.players);
                cache.put("maxPlayers", last.maxPlayers);
                cache.put("password", last.password);
                cache.put("rtt", last.rtt);
                cache.put("time", last.time);
                json.put("cache", cache);
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    public static Server fromJson(JSONObject json) {
        Server server = new Server();
        server.id = json.optString("id", "");
        server.name = json.optString("name", "");
        server.host = json.optString("host", "");
        server.port = json.optInt("port", SampQuery.DEFAULT_PORT);
        server.password = json.optString("password", "");
        server.favorite = json.optBoolean("favorite", false);
        server.source = json.optString("source", SOURCE_MANUAL);
        server.gamemode = json.optString("gamemode", "");
        server.note = json.optString("note", "");
        server.addedAt = json.optLong("addedAt", System.currentTimeMillis());
        JSONObject cache = json.optJSONObject("cache");
        if (cache != null) {
            SampQuery.Info info = new SampQuery.Info();
            info.host = server.host;
            info.port = server.port;
            info.hostname = cache.optString("hostname", "");
            info.gamemode = cache.optString("gamemode", "");
            info.language = cache.optString("language", "");
            info.players = cache.optInt("players", 0);
            info.maxPlayers = cache.optInt("maxPlayers", 0);
            info.password = cache.optBoolean("password", false);
            info.rtt = cache.optInt("rtt", 0);
            info.time = cache.optLong("time", 0);
            server.last = info;
        }
        return server;
    }

    /** Разбор строки «1.2.3.4:7777», «1.2.3.4» или «samp.example.com 7777». */
    public static Server parse(String text) {
        if (text == null) {
            return null;
        }
        String address = text.trim();
        if (address.isEmpty()) {
            return null;
        }
        int space = address.lastIndexOf(' ');
        if (space > 0 && address.indexOf(':') < 0) {
            address = address.substring(0, space) + ":" + address.substring(space + 1).trim();
        }
        String host = address;
        int port = SampQuery.DEFAULT_PORT;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && colon < address.length() - 1) {
            String portPart = address.substring(colon + 1).trim();
            if (isDigits(portPart)) {
                host = address.substring(0, colon);
                port = Integer.parseInt(portPart);
            }
        }
        host = host.trim();
        while (host.startsWith("/")) {
            host = host.substring(1);
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return null;
        }
        Server server = new Server();
        server.host = host;
        server.port = port;
        server.addedAt = System.currentTimeMillis();
        return server;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
