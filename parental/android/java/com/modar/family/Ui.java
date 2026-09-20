package com.modar.family;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Мелкие помощники: размеры, потоки, форматирование, простые карточки. */
public class Ui {

    public static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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

    /* ------------------------------------------------------------- время */

    public static String clock(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String dateTime(long millis) {
        return new SimpleDateFormat("d MMM, HH:mm", new Locale("ru")).format(new Date(millis));
    }

    public static String ago(long ts) {
        if (ts <= 0) {
            return "нет данных";
        }
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60000) {
            return "только что";
        }
        if (diff < 3600000) {
            return Math.round(diff / 60000.0) + " мин назад";
        }
        if (diff < 86400000) {
            return Math.round(diff / 3600000.0) + " ч назад";
        }
        return dateTime(ts);
    }

    public static String minutes(long ms) {
        long total = Math.round(ms / 60000.0);
        if (total < 60) {
            return total + " мин";
        }
        return (total / 60) + " ч " + (total % 60) + " мин";
    }

    public static String dayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static int hourNow() {
        return Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(new Date()));
    }

    /* --------------------------------------------------------- построение UI */

    /** Карточка-раздел: заголовок + содержимое. */
    public static LinearLayout card(Context ctx, String title) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        int pad = dp(ctx, 14);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(ctx, 12);
        box.setLayoutParams(params);
        if (title != null && !title.isEmpty()) {
            TextView head = new TextView(ctx);
            head.setText(title);
            head.setTextColor(color(ctx, R.color.accent));
            head.setTextSize(13);
            head.setAllCaps(true);
            head.setLetterSpacing(0.08f);
            head.setPadding(0, 0, 0, dp(ctx, 8));
            box.addView(head);
        }
        return box;
    }

    public static TextView text(Context ctx, String value, int sizeSp, int colorRes) {
        TextView view = new TextView(ctx);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(ctx, colorRes));
        return view;
    }

    public static LinearLayout line(Context ctx, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 3), 0, dp(ctx, 3));
        TextView left = text(ctx, label, 14, R.color.text_muted);
        TextView right = text(ctx, value, 14, R.color.text);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(left);
        row.addView(right);
        return row;
    }

    public static void chip(LinearLayout parent, Context ctx, String label, int bg, int fg) {
        TextView chip = text(ctx, label, 12, fg);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(ctx, 10));
        shape.setColor(color(ctx, bg));
        chip.setBackground(shape);
        chip.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(ctx, 6);
        params.bottomMargin = dp(ctx, 6);
        chip.setLayoutParams(params);
        parent.addView(chip);
    }

    public static void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /* ------------------------------------------------------------- JSON */

    public static String str(JSONObject obj, String key, String def) {
        if (obj == null) {
            return def;
        }
        String value = obj.optString(key, def);
        return value == null || "null".equals(value) ? def : value;
    }

    public static int integer(JSONObject obj, String key, int def) {
        return obj == null ? def : obj.optInt(key, def);
    }

    public static double number(JSONObject obj, String key, double def) {
        return obj == null ? def : obj.optDouble(key, def);
    }

    public static JSONArray array(JSONObject obj, String key) {
        JSONArray value = obj == null ? null : obj.optJSONArray(key);
        return value == null ? new JSONArray() : value;
    }

    public static JSONObject object(JSONObject obj, String key) {
        JSONObject value = obj == null ? null : obj.optJSONObject(key);
        return value == null ? new JSONObject() : value;
    }
}
