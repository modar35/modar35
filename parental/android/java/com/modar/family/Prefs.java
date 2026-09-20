package com.modar.family;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Хранилище настроек приложения: адрес сервера, токены, политика с телефона родителя.
 * Всё лежит в приватном SharedPreferences приложения.
 */
public class Prefs {

    public static final String MODE_PARENT = "parent";
    public static final String MODE_CHILD = "child";

    private static final String NAME = "modar_family";
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /* --------------------------------------------------------------- основные */

    public static String mode(Context ctx) { return get(ctx, "mode", ""); }
    public static void setMode(Context ctx, String mode) { put(ctx, "mode", mode); }

    public static String server(Context ctx) {
        String url = get(ctx, "server", "");
        if (url.isEmpty()) {
            return "";
        }
        if (!url.startsWith("http")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
    public static void setServer(Context ctx, String url) { put(ctx, "server", url == null ? "" : url.trim()); }

    public static boolean configured(Context ctx) {
        return !server(ctx).isEmpty() && (isParent(ctx) ? !parentToken(ctx).isEmpty() : !deviceToken(ctx).isEmpty());
    }

    public static boolean isParent(Context ctx) { return MODE_PARENT.equals(mode(ctx)); }
    public static boolean isChild(Context ctx) { return MODE_CHILD.equals(mode(ctx)); }

    /* ----------------------------------------------------------------- родитель */

    public static String parentToken(Context ctx) { return get(ctx, "parent_token", ""); }
    public static void setParentToken(Context ctx, String token) { put(ctx, "parent_token", token); }
    public static String parentEmail(Context ctx) { return get(ctx, "parent_email", ""); }
    public static void setParentEmail(Context ctx, String email) { put(ctx, "parent_email", email); }
    public static String familyName(Context ctx) { return get(ctx, "family", ""); }
    public static void setFamilyName(Context ctx, String name) { put(ctx, "family", name); }

    /* ------------------------------------------------------------------ ребёнок */

    public static String deviceToken(Context ctx) { return get(ctx, "device_token", ""); }
    public static void setDeviceToken(Context ctx, String token) { put(ctx, "device_token", token); }
    public static String deviceId(Context ctx) { return get(ctx, "device_id", ""); }
    public static void setDeviceId(Context ctx, String id) { put(ctx, "device_id", id); }
    public static String childName(Context ctx) { return get(ctx, "child_name", "Телефон ребёнка"); }
    public static void setChildName(Context ctx, String name) { put(ctx, "child_name", name); }

    /** Политика, пришедшая с сервера (лимиты, блокировки, режимы). */
    public static JSONObject policy(Context ctx) {
        try {
            String raw = get(ctx, "policy", "");
            return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    public static void setPolicy(Context ctx, JSONObject policy) {
        put(ctx, "policy", policy == null ? "{}" : policy.toString());
    }

    /** Локальные счётчики использования: {"day":"2026-09-20","com.app":123456}. */
    public static JSONObject usage(Context ctx) {
        try {
            String raw = get(ctx, "usage", "");
            return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    public static void setUsage(Context ctx, JSONObject usage) {
        put(ctx, "usage", usage == null ? "{}" : usage.toString());
    }

    public static int reportSeconds(Context ctx) {
        JSONObject policy = policy(ctx);
        JSONObject alerts = policy.optJSONObject("alerts");
        int value = policy.optInt("reportSeconds", 300);
        if (alerts != null && alerts.has("reportSeconds")) {
            value = alerts.optInt("reportSeconds", value);
        }
        return Math.max(30, Math.min(3600, value));
    }

    public static String deviceInfo(Context ctx) {
        return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
    }

    /* ---------------------------------------------------------------- служебное */

    public static String get(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static void put(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    public static boolean flag(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, false);
    }

    public static void setFlag(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
