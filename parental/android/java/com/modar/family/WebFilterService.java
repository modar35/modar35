package com.modar.family;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Служба «Специальные возможности» — сердце родительского контроля на телефоне:
 *
 *   1. следит, какое приложение сейчас открыто (для учёта экранного времени
 *      и мгновенной блокировки запрещённых приложений);
 *   2. находит адрес сайта в браузере и блокирует нежелательные страницы;
 *   3. умеет нажать «Назад», когда приложение запрещено, и показать окно-заглушку.
 *
 * Веб-фильтр работает по списку доменов: взрослый контент, азартные игры и
 * собственный список родителей. Полная фильтрация трафика без VPN невозможна,
 * но подавляющее большинство сайтов определяется по адресной строке браузера.
 */
public class WebFilterService extends AccessibilityService {

    private static WebFilterService instance;

    /** Заблокированные адреса, которые надо отправить родителю. */
    private static final List<JSONObject> BLOCKED = new ArrayList<JSONObject>();
    private static final List<JSONObject> EVENTS = new ArrayList<JSONObject>();

    private static String foreground = "";

    /* ------------------------------------------------------- взрослый контент */

    private static final String[] ADULT = {
            "porn", "xxx", "xvideos", "xnxx", "pornhub", "redtube", "youporn", "brazzers",
            "onlyfans", "erotic", "sex-", "sexy", "milf", "hentai", "bdsm", "camgirl",
            "adult-site", "18plus", "fap", "nude", "escort",
    };
    private static final String[] GAMBLING = {
            "casino", "bet", "betting", "slots", "poker", "jackpot", "1xbet", "1win",
            "vulkan", "azart", "roulette", "bookmaker", "stavka", "loto", "gambling",
    };

    @Override
    public void onServiceConnected() {
        instance = this;
        Prefs.setFlag(this, "accessibility", true);
    }

    @Override
    public void onDestroy() {
        instance = null;
        Prefs.setFlag(this, "accessibility", false);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                foreground = event.getPackageName().toString();
                ChildService.noteForeground(this, foreground);
            }
            ChildService.checkNow(this);
        }
        // Адрес страницы виден в тексте адресной строки у большинства браузеров
        String host = findHost(getRootInActiveWindow());
        if (host != null) {
            if (isBlocked(host)) {
                onBlocked(host);
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Ничего: служба просто перестаёт получать события.
    }

    /* -------------------------------------------------------------- помощники */

    private String findHost(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        String text = node.getText() == null ? null : node.getText().toString();
        if (text != null) {
            String candidate = hostOf(text);
            if (candidate != null) {
                return candidate;
            }
        }
        if (Build.VERSION.SDK_INT >= 21) {
            for (int i = 0; i < node.getChildCount(); i++) {
                String found = findHost(node.getChild(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Вытаскивает домен из текста адресной строки. */
    static String hostOf(String text) {
        String value = text.trim().toLowerCase(Locale.US);
        if (value.isEmpty() || value.length() > 300) {
            return null;
        }
        int start = value.indexOf("://");
        if (start >= 0) {
            value = value.substring(start + 3);
        } else if (!value.contains(".") || value.contains(" ")) {
            return null;
        }
        int slash = value.indexOf('/');
        if (slash > 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        if (colon > 0) {
            value = value.substring(0, colon);
        }
        if (!value.contains(".") || value.startsWith(".") || value.endsWith(".")) {
            return null;
        }
        if (value.contains(" ")) {
            return null;
        }
        return value;
    }

    /** Проверяет домен по всем правилам родителя. */
    public static boolean isBlocked(String host) {
        if (host == null || host.isEmpty() || instance == null) {
            return false;
        }
        String value = host.toLowerCase(Locale.US);
        JSONObject policy = Prefs.policy(instance);
        JSONObject filter = policy.optJSONObject("webFilter");
        boolean adult = filter == null || filter.optBoolean("adult", true);
        boolean gambling = filter == null || filter.optBoolean("gambling", true);
        JSONArray custom = filter == null ? null : filter.optJSONArray("custom");
        if (custom != null) {
            for (int i = 0; i < custom.length(); i++) {
                String rule = custom.optString(i, "").toLowerCase(Locale.US).trim();
                if (!rule.isEmpty() && value.contains(rule)) {
                    return true;
                }
            }
        }
        if (adult) {
            for (String rule : ADULT) {
                if (value.contains(rule)) {
                    return true;
                }
            }
        }
        if (gambling) {
            for (String rule : GAMBLING) {
                if (value.contains(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void onBlocked(String host) {
        // Заглушка: уводим ребёнка назад и показываем объяснение
        performGlobalAction(GLOBAL_ACTION_BACK);
        ChildService.showSafeScreen(this, "Сайт заблокирован", host + "\n\nДоступ к этому сайту закрыт родителями.");
        JSONObject item = new JSONObject();
        try {
            item.put("host", host);
            item.put("reason", reason(host));
        } catch (Exception ignored) {
        }
        synchronized (WebFilterService.class) {
            BLOCKED.add(item);
            while (BLOCKED.size() > 40) {
                BLOCKED.remove(0);
            }
        }
    }

    private static String reason(String host) {
        String value = host.toLowerCase(Locale.US);
        for (String rule : ADULT) {
            if (value.contains(rule)) {
                return "adult";
            }
        }
        for (String rule : GAMBLING) {
            if (value.contains(rule)) {
                return "gambling";
            }
        }
        return "custom";
    }

    /** Приложение сейчас на экране (по данным службы). */
    public static String foregroundPackage() {
        return foreground;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static synchronized JSONArray drainBlocked() {
        JSONArray array = new JSONArray();
        for (JSONObject item : BLOCKED) {
            array.put(item);
        }
        BLOCKED.clear();
        return array;
    }

    public static synchronized JSONArray drainEvents() {
        JSONArray array = new JSONArray();
        for (JSONObject item : EVENTS) {
            array.put(item);
        }
        EVENTS.clear();
        return array;
    }

    /** Событие для родителя: попытка открыть запрещённое приложение и т.п. */
    public static void report(Context ctx, String type, String message, String level) {
        JSONObject item = new JSONObject();
        try {
            item.put("type", type);
            item.put("message", message);
            item.put("level", level == null ? "info" : level);
        } catch (Exception e) {
            return;
        }
        synchronized (WebFilterService.class) {
            EVENTS.add(item);
            while (EVENTS.size() > 40) {
                EVENTS.remove(0);
            }
        }
    }
}
