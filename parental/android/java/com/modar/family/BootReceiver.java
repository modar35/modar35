package com.modar.family;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Автозапуск защиты после перезагрузки телефона ребёнка. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.isChild(context) || !Prefs.configured(context)) {
            return;
        }
        Prefs.setFlag(context, "booted", true);
        ChildService.start(context);
    }
}
