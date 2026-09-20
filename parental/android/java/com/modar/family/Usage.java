package com.modar.family;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Учёт экранного времени.
 *
 * Отдельного разрешения «статистика использования» может не быть, поэтому основу даёт
 * собственная статистика приложения (сколько секунд приложение ребёнка было активным),
 * а системная статистика используется только если родитель выдал доступ.
 */
public class Usage {

    public static final String KEY_DAY = "_day";
    public static final String KEY_TOTAL = "_total";

    /** Добавляет секунды к счётчику приложения (с автоматическим сбросом на новый день). */
    public static synchronized void add(Context ctx, String pkg, long millis) {
        if (pkg == null || pkg.isEmpty() || millis <= 0) {
            return;
        }
        JSONObject usage = Prefs.usage(ctx);
        String today = Ui.dayKey();
        try {
            if (!today.equals(usage.optString(KEY_DAY, ""))) {
                usage = new JSONObject();
                usage.put(KEY_DAY, today);
            }
            long current = usage.optLong(pkg, 0L);
            usage.put(pkg, current + millis);
            usage.put(KEY_TOTAL, total(usage));
        } catch (Exception e) {
            return;
        }
        Prefs.setUsage(ctx, usage);
    }

    /** Сколько миллисекунд сегодня накопило приложение. */
    public static synchronized long app(Context ctx, String pkg) {
        JSONObject usage = Prefs.usage(ctx);
        if (!Ui.dayKey().equals(usage.optString(KEY_DAY, ""))) {
            return 0;
        }
        return usage.optLong(pkg, 0L);
    }

    public static long total(JSONObject usage) {
        long sum = 0;
        Iterator<String> keys = usage.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) {
                continue;
            }
            sum += usage.optLong(key, 0L);
        }
        return sum;
    }

    public static long totalMs(Context ctx) {
        return total(Prefs.usage(ctx));
    }

    /** Список «пакет → минуты» для отчёта родителю. */
    public static JSONArray toReport(Context ctx, PackageManager pm) {
        JSONObject usage = Prefs.usage(ctx);
        JSONArray result = new JSONArray();
        if (!Ui.dayKey().equals(usage.optString(KEY_DAY, ""))) {
            return result;
        }
        List<String> keys = new ArrayList<String>();
        Iterator<String> it = usage.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (!key.startsWith("_")) {
                keys.add(key);
            }
        }
        final JSONObject source = usage;
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Long.compare(source.optLong(b, 0), source.optLong(a, 0));
            }
        });
        for (String pkg : keys) {
            long ms = usage.optLong(pkg, 0);
            if (ms < 60000) {
                continue; // меньше минуты в отчёт не отправляем
            }
            JSONObject row = new JSONObject();
            try {
                row.put("pkg", pkg);
                row.put("label", appLabel(pm, pkg));
                row.put("ms", ms);
            } catch (Exception ignored) {
            }
            result.put(row);
            if (result.length() >= 60) {
                break;
            }
        }
        return result;
    }

    public static String appLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(info));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Пересчитывает системную статистику за сегодня (если разрешение выдано). */
    public static JSONObject systemUsage(Context ctx, PackageManager pm) {
        JSONObject result = new JSONObject();
        UsageStatsManager manager = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return result;
        }
        long now = System.currentTimeMillis();
        long start = now - 24 * 3600 * 1000L;
        List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now);
        if (stats == null) {
            return result;
        }
        for (UsageStats stat : stats) {
            long ms = stat.getTotalTimeInForeground();
            if (ms > 60000) {
                try {
                    result.put(stat.getPackageName(), ms);
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    /** Какое приложение сейчас на экране (по системной статистике). */
    public static String currentForeground(Context ctx) {
        UsageStatsManager manager = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        try {
            List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 3600 * 1000L, now);
            if (stats == null || stats.isEmpty()) {
                return null;
            }
            UsageStats last = null;
            for (UsageStats stat : stats) {
                if (last == null || stat.getLastTimeUsed() > last.getLastTimeUsed()) {
                    last = stat;
                }
            }
            return last == null ? null : last.getPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean hasStatsPermission(Context ctx) {
        UsageStatsManager manager = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3600 * 1000L, now);
        return stats != null && !stats.isEmpty();
    }
}
