package com.modar.family;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Служба доступа к уведомлениям: собирает, что приходит ребёнку в мессенджерах
 * и приложениях, и складывает в очередь — телефон ребёнка отправит это родителю
 * с ближайшим отчётом.
 */
public class NotifListenerService extends NotificationListenerService {

    private static final List<JSONObject> QUEUE = new ArrayList<JSONObject>();

    /** Уведомления, ожидающие отправки (не больше 40 штук). */
    public static synchronized JSONArray drain() {
        JSONArray array = new JSONArray();
        for (JSONObject item : QUEUE) {
            array.put(item);
        }
        QUEUE.clear();
        return array;
    }

    public static synchronized int size() {
        return QUEUE.size();
    }

    public static boolean isEnabled(Context ctx) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        if (sbn.getPackageName().equals(getPackageName())) {
            return;
        }
        Bundle extras = sbn.getNotification().extras;
        String title = extras == null ? "" : text(extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = extras == null ? "" : text(extras.getCharSequence(Notification.EXTRA_TEXT));
        if (body.isEmpty() && extras != null) {
            body = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }
        if (title.isEmpty() && body.isEmpty()) {
            return;
        }
        JSONObject item = new JSONObject();
        try {
            item.put("app", Usage.appLabel(getPackageManager(), sbn.getPackageName()));
            item.put("pkg", sbn.getPackageName());
            item.put("title", cut(title, 120));
            item.put("text", cut(body, 300));
        } catch (Exception e) {
            return;
        }
        synchronized (NotifListenerService.class) {
            QUEUE.add(item);
            while (QUEUE.size() > 40) {
                QUEUE.remove(0);
            }
        }
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
