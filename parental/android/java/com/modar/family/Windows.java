package com.modar.family;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Расписания, которые приходят от родителя: «школа», «ночной режим», «интернет по расписанию».
 * Формат: {"enabled":true,"from":"08:00","to":"14:00","days":[1,2,3,4,5]}
 */
public class Windows {

    public static int minutes(String hhmm) {
        if (hhmm == null) {
            return -1;
        }
        String[] parts = hhmm.trim().split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Попадает ли текущее время в окно расписания. */
    public static boolean inside(JSONObject window) {
        return inside(window, System.currentTimeMillis());
    }

    public static boolean inside(JSONObject window, long ts) {
        if (window == null || !window.optBoolean("enabled", false)) {
            return false;
        }
        int from = minutes(window.optString("from", ""));
        int to = minutes(window.optString("to", ""));
        if (from < 0 || to < 0 || from == to) {
            return false;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts);
        int day = cal.get(Calendar.DAY_OF_WEEK);
        int index = (day + 5) % 7; // Пн=0 … Вс=6, как в панели родителя
        JSONArray days = window.optJSONArray("days");
        if (days != null && days.length() > 0) {
            boolean match = false;
            for (int i = 0; i < days.length(); i++) {
                if (days.optInt(i, -1) == index) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                return false;
            }
        }
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (from < to) {
            return now >= from && now < to;
        }
        return now >= from || now < to; // окно через полночь, например 22:00 → 07:00
    }

    public static String describe(JSONObject window) {
        if (window == null || !window.optBoolean("enabled", false)) {
            return "выключено";
        }
        return window.optString("from", "?") + "–" + window.optString("to", "?");
    }
}
