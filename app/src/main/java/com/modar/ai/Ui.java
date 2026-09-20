package com.modar.ai;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.TypedValue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Мелкие помощники интерфейса. */
public class Ui {

    private static final Map<String, Bitmap> THUMBS = new HashMap<String, Bitmap>();

    public static int dp(Context ctx, float value) {
        Resources r = ctx.getResources();
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, r.getDisplayMetrics());
    }

    public static int color(Context ctx, int res) {
        return ctx.getResources().getColor(res);
    }

    public static String time(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String date(long millis) {
        return new SimpleDateFormat("d MMM, HH:mm", new Locale("ru")).format(new Date(millis));
    }

    /** Миниатюра картинки с кэшем (для вложений в сообщениях). */
    public static Bitmap thumbnail(Context ctx, String path, int maxSide) {
        if (path == null) {
            return null;
        }
        String key = path + "@" + maxSide;
        Bitmap cached = THUMBS.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            int side = Math.max(bounds.outWidth, bounds.outHeight);
            while (side / sample > maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, opts);
            if (bmp != null) {
                THUMBS.put(key, bmp);
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
