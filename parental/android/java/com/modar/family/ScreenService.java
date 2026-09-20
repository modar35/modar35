package com.modar.family;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Снимок экрана телефона ребёнка по запросу родителя («Снимок экрана» и «Экскурсия»).
 *
 * Android не отдаёт снимок экрана без согласия человека — приложение просит ребёнка
 * один раз подтвердить запись экрана (и запоминает согласие). После этого родитель
 * может снимать экран по команде: одиночный кадр или серия кадров каждые 2 секунды.
 */
public class ScreenService extends Service {

    private static final String CHANNEL = "modar_screen";
    private static final int NOTIF_ID = 1002;
    public static final String ACTION_START = "com.modar.family.SCREEN_START";
    public static final String ACTION_STOP = "com.modar.family.SCREEN_STOP";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_SECONDS = "seconds";

    private HandlerThread thread;
    private Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int width;
    private int height;

    /** Согласие на запись экрана (выданное ребёнком один раз). */
    public static boolean hasConsent(Context ctx) {
        return Prefs.get(ctx, "screen_result", "").length() > 0 && Prefs.get(ctx, "screen_data", "").length() > 0;
    }

    public static void rememberConsent(Context ctx, int resultCode, Intent data) {
        if (data == null) {
            return;
        }
        Prefs.put(ctx, "screen_result", String.valueOf(resultCode));
        Prefs.put(ctx, "screen_data", data.toUri(Intent.URI_INTENT_SCHEME));
    }

    public static void start(Context ctx, int seconds) {
        Intent intent = new Intent(ctx, ScreenService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SECONDS, seconds);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stop(Context ctx) {
        Intent intent = new Intent(ctx, ScreenService.class);
        intent.setAction(ACTION_STOP);
        try {
            ctx.startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("modar-screen");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Снимок экрана",
                        NotificationManager.IMPORTANCE_MIN);
                manager.createNotificationChannel(channel);
            }
            Notification.Builder builder = new Notification.Builder(this, CHANNEL);
            Notification notification = builder
                    .setContentTitle("Modar Family")
                    .setContentText("Родитель запросил снимок экрана")
                    .setSmallIcon(R.drawable.ic_shield)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } catch (Exception e) {
                    Prefs.put(this, "screen_error", String.valueOf(e.getMessage()));
                    startForeground(NOTIF_ID, notification);
                }
            } else {
                startForeground(NOTIF_ID, notification);
            }
        }

        int seconds = intent == null ? 1 : intent.getIntExtra(EXTRA_SECONDS, 1);
        capture(seconds);
        return START_NOT_STICKY;
    }

    private void capture(final int seconds) {
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    startProjection();
                    final int frames = Math.max(1, Math.min(60, seconds / 2 + 1));
                    long delay = 0;
                    for (int i = 0; i < frames; i++) {
                        final boolean last = i == frames - 1;
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                grabFrame(last);
                            }
                        }, delay);
                        delay += 2000;
                    }
                } catch (Exception e) {
                    // Согласие могло устареть (Android часто разрешает сессию один раз) —
                    // в следующий раз попросим ребёнка подтвердить снова.
                    forgetConsent(ScreenService.this);
                    ChildService.event(ScreenService.this, "camera",
                            "Снимок экрана: нужно подтверждение на телефоне ребёнка", "warn");
                    stopSelf();
                }
            }
        });
    }

    /** Согласие больше не действует — следующий запрос попросит подтверждение. */
    public static void forgetConsent(Context ctx) {
        Prefs.put(ctx, "screen_result", "");
        Prefs.put(ctx, "screen_data", "");
    }

    private void startProjection() throws Exception {
        String result = Prefs.get(this, "screen_result", "");
        String data = Prefs.get(this, "screen_data", "");
        if (result.isEmpty() || data.isEmpty()) {
            throw new IllegalStateException("Нет согласия на запись экрана");
        }
        Intent intent = Intent.parseUri(data, Intent.URI_INTENT_SCHEME);
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(Integer.parseInt(result), intent);

        WindowManager window = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        window.getDefaultDisplay().getRealMetrics(metrics);
        width = Math.max(240, metrics.widthPixels / 2);
        height = Math.max(320, metrics.heightPixels / 2);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("modar-screen", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        // Даём системе время отрисовать первый кадр
        Thread.sleep(900);
    }

    private void grabFrame(final boolean last) {
        try {
            Image image = reader.acquireLatestImage();
            if (image == null) {
                Thread.sleep(250);
                image = reader.acquireLatestImage();
            }
            if (image == null) {
                if (last) {
                    release();
                }
                return;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            final Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            final Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
            if (bitmap != cropped) {
                bitmap.recycle();
            }
            Ui.bg(new Runnable() {
                @Override
                public void run() {
                    upload(cropped);
                    cropped.recycle();
                    if (last) {
                        release();
                    }
                }
            });
        } catch (Exception e) {
            if (last) {
                release();
            }
        }
    }

    private void upload(Bitmap bitmap) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out);
            String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            JSONObject body = new JSONObject();
            body.put("kind", "screen");
            body.put("data", "data:image/jpeg;base64," + encoded);
            Http.post(this, "/api/v1/device/image", body);
        } catch (Exception e) {
            Prefs.put(this, "screen_error", String.valueOf(e.getMessage()));
        }
    }

    private void release() {
        // На Android 14+ одна выданная сессия записи обычно используется один раз:
        // чтобы следующий запрос работал, просим подтверждение заново.
        if (Build.VERSION.SDK_INT >= 34) {
            forgetConsent(this);
        }
        try {
            if (display != null) {
                display.release();
                display = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            if (projection != null) {
                projection.stop();
                projection = null;
            }
        } catch (Exception ignored) {
        }
        Ui.ui(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        });
    }

    @Override
    public void onDestroy() {
        try {
            if (display != null) {
                display.release();
            }
            if (reader != null) {
                reader.close();
            }
            if (projection != null) {
                projection.stop();
            }
        } catch (Exception ignored) {
        }
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    /** Запускается из активного экрана после согласия ребёнка (Android 11+). */
    public static void startFromActivity(Activity activity, int resultCode, Intent data, int seconds) {
        rememberConsent(activity, resultCode, data);
        start(activity, seconds);
    }
}
