package com.modar.family;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Приёмник прав администратора устройства: нужен для команды «заблокировать экран».
 * Родитель включает их один раз на телефоне ребёнка в разделе «Права защиты».
 */
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Prefs.setFlag(context, "admin", true);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Prefs.setFlag(context, "admin", false);
    }

    public static boolean isActive(Context ctx) {
        android.app.admin.DevicePolicyManager manager =
                (android.app.admin.DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (manager == null) {
            return false;
        }
        return manager.isAdminActive(new android.content.ComponentName(ctx, AdminReceiver.class));
    }
}
