package com.modar.samp;

import org.json.JSONObject;

/** Новость с сервера лаунчера. */
public class News {

    public String id = "";
    public String title = "";
    public String text = "";
    public String tag = "";
    public long date;
    public boolean pinned;

    public static News fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        News news = new News();
        news.id = json.optString("id", String.valueOf(json.optLong("date", 0)));
        news.title = json.optString("title", "");
        news.text = json.optString("text", "");
        news.tag = json.optString("tag", "");
        news.date = json.optLong("date", 0);
        news.pinned = json.optBoolean("pinned", false);
        if (news.title.isEmpty() && news.text.isEmpty()) {
            return null;
        }
        return news;
    }

    /** Сведения об обновлении лаунчера (ответ /api/v1/launcher). */
    public static class Update {
        public String version = "";
        public int versionCode;
        public String url = "";
        public String sha256 = "";
        public String notes = "";
        public int minSupported;

        public static Update fromJson(JSONObject json, String baseUrl) {
            Update update = new Update();
            if (json == null) {
                return update;
            }
            update.version = json.optString("version", "");
            update.versionCode = json.optInt("versionCode", 0);
            update.url = absolute(baseUrl, json.optString("url", ""));
            update.sha256 = json.optString("sha256", "");
            update.notes = json.optString("notes", "");
            update.minSupported = json.optInt("minSupported", 0);
            return update;
        }

        /** Есть ли смысл предлагать обновление для установленной версии. */
        public boolean isNewerThan(int installedVersionCode) {
            return versionCode > installedVersionCode
                    && !url.isEmpty()
                    && versionCode > 0;
        }

        private static String absolute(String baseUrl, String url) {
            if (url == null || url.isEmpty()) {
                return "";
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
            String base = baseUrl == null ? "" : baseUrl;
            if (base.isEmpty()) {
                return url;
            }
            return base + (url.startsWith("/") ? url : "/" + url);
        }
    }
}
