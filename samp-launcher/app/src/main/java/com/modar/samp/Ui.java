package com.modar.samp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Мелкие помощники интерфейса: размеры, потоки, диалоги, форматирование. */
public final class Ui {

    public static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {
    }

    public static int dp(Context ctx, float value) {
        Resources r = ctx.getResources();
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, r.getDisplayMetrics());
    }

    public static int color(Context ctx, int res) {
        return ctx.getResources().getColor(res);
    }

    public static void ui(Runnable runnable) {
        MAIN.post(runnable);
    }

    public static void bg(final Runnable runnable) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }

    public static void toast(Context ctx, String text) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }

    public static void toastLong(Context ctx, String text) {
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
    }

    public static void alert(Context ctx, String title, String message) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Понятно", null)
                .show();
    }

    public static void confirm(Context ctx, String title, String message, final Runnable onYes) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Да", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onYes.run();
                    }
                })
                .show();
    }

    /* ------------------------------------------------------------------ формат */

    /** «12,3 МБ» — размер в удобном виде. */
    public static String size(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " Б";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f КБ", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f МБ", bytes / 1048576.0);
        }
        return String.format(Locale.getDefault(), "%.2f ГБ", bytes / 1073741824.0);
    }

    public static String time(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String date(long millis) {
        return new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("ru")).format(new Date(millis));
    }

    /** «5 мин назад», «2 ч назад», «12.03». */
    public static String ago(long millis) {
        long diff = System.currentTimeMillis() - millis;
        if (diff < 0) {
            diff = 0;
        }
        long minutes = diff / 60000;
        if (minutes < 1) {
            return "только что";
        }
        if (minutes < 60) {
            return minutes + " мин назад";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " ч назад";
        }
        long days = hours / 24;
        if (days < 7) {
            return days + " дн назад";
        }
        return new SimpleDateFormat("dd.MM", Locale.getDefault()).format(new Date(millis));
    }
}
