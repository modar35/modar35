package com.modar.samp;

import org.json.JSONObject;

/**
 * Мод-пак, который раздаёт сервер лаунчера.
 *
 * kind = "zip"  — архив, который распаковывается в каталог игры (target — подкаталог внутри него);
 * kind = "file" — один файл, который кладётся по пути target (относительно каталога игры).
 */
public class Mod {

    public String id = "";
    public String name = "";
    public String version = "1.0";
    public String kind = "zip";
    public String url = "";
    public String target = "";
    public String description = "";
    public String author = "";
    /** Если задан — мод относится к конкретному серверу из списка лаунчера. */
    public String serverId = "";
    public String sha256 = "";
    public long size;
    public long updatedAt;

    public boolean isZip() {
        return "zip".equalsIgnoreCase(kind);
    }

    public String sizeLabel() {
        return size > 0 ? Ui.size(size) : "";
    }

    public static Mod fromJson(JSONObject json, String baseUrl) {
        if (json == null) {
            return null;
        }
        Mod mod = new Mod();
        mod.id = json.optString("id", "");
        mod.name = json.optString("name", "");
        mod.version = json.optString("version", "1.0");
        mod.kind = json.optString("kind", "zip");
        mod.url = absolute(baseUrl, json.optString("url", ""));
        mod.target = trimSlashes(json.optString("target", ""));
        mod.description = json.optString("description", "");
        mod.author = json.optString("author", "");
        mod.serverId = json.optString("serverId", "");
        mod.sha256 = json.optString("sha256", "");
        mod.size = json.optLong("size", 0);
        mod.updatedAt = json.optLong("updatedAt", 0);
        if (mod.id.isEmpty()) {
            return null;
        }
        return mod;
    }

    public static String absolute(String baseUrl, String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            return url;
        }
        return baseUrl + (url.startsWith("/") ? url : "/" + url);
    }

    private static String trimSlashes(String value) {
        String result = value == null ? "" : value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
