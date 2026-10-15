package com.modar.samp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

/**
 * Доступ к общей памяти: мод-паки и файлы игры лежат в /storage/emulated/0/GTA,
 * поэтому приложению нужно разрешение «Все файлы» (Android 11+) или запись в хранилище.
 */
public final class Storage {

    public static final int REQUEST_ALL_FILES = 4101;
    public static final int REQUEST_LEGACY_WRITE = 4102;

    private Storage() {
    }

    /** Есть ли доступ к каталогу игры. */
    public static boolean granted(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager();
            }
            return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Проверяет доступ и, если его нет, объясняет и ведёт в системные настройки. */
    public static void ensure(Activity activity, final Runnable onReady) {
        if (granted(activity)) {
            onReady.run();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Нужен доступ к файлам игры")
                .setMessage("Мод-паки ставятся в папку игры (" + new Prefs(activity).gameDir() + "), "
                        + "а резервные копии — в " + new Prefs(activity).dataDir() + ".\n\n"
                        + "На Android 11 и новее разрешите приложению «Доступ ко всем файлам» — "
                        + "иначе система не даст записать мод в папку игры. Приложению видна только "
                        + "эта папка, дальше ничего не передаётся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Разрешить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        request(activity, onReady);
                    }
                })
                .show();
    }

    /** Открывает системное окно выдачи доступа. */
    public static void request(Activity activity, Runnable onReady) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } else {
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_LEGACY_WRITE);
            }
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                activity.startActivity(intent);
            } catch (Throwable inner) {
                Ui.toast(activity, "Откройте «Настройки → Приложения → Разрешения» вручную");
            }
        }
    }

    /** Подпись для строки состояния в настройках. */
    public static String status(Context ctx) {
        if (granted(ctx)) {
            return "Доступ к файлам есть";
        }
        return "Нет доступа к файлам — моды не установятся";
    }

    /** Сколько места осталось в каталоге (для проверки перед установкой мода). */
    public static long freeSpace(String path) {
        try {
            return new java.io.File(path).getUsableSpace();
        } catch (Throwable t) {
            return -1;
        }
    }
}
