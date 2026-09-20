package com.modar.family;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Полноэкранные окна для телефона ребёнка:
 *   LOCK    — блокировка (родитель нажал «заблокировать» или сработал режим/лимит);
 *   ALARM   — громкая сирена;
 *   RING    — звонок, чтобы найти телефон;
 *   MESSAGE — сообщение от родителя;
 *   SAFE    — заглушка вместо запрещённого приложения или сайта.
 */
public class AlertActivity extends Activity {

    public static final String MODE_LOCK = "lock";
    public static final String MODE_ALARM = "alarm";
    public static final String MODE_RING = "ring";
    public static final String MODE_MESSAGE = "message";
    public static final String MODE_SAFE = "safe";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TEXT = "text";

    private static final String CHANNEL_ALERTS = "modar_alerts";
    private static final int NOTIF_ID = 2001;

    private static WeakReference<AlertActivity> current = new WeakReference<AlertActivity>(null);
    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler handler = new Handler();

    /**
     * Показать окно (безопасно вызывать из службы).
     *
     * Android 10+ ограничивает запуск окон из фона, поэтому параллельно ставим
     * уведомление с полноэкранным намерением: если окно не открылось само,
     * оно появится по уведомлению (в том числе на заблокированном экране).
     */
    public static void show(Context ctx, String mode, String text) {
        Intent intent = new Intent(ctx, AlertActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_TEXT, text);
        postFullScreenNotification(ctx, intent, mode, text);
        try {
            ctx.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /** Уведомление с полноэкранным намерением — надёжный путь к экрану ребёнка. */
    private static void postFullScreenNotification(Context ctx, Intent intent, String mode, String text) {
        try {
            NotificationManager manager = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ALERTS) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ALERTS, "Тревоги и сообщения",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Блокировка, сирена, сообщения родителей");
                channel.enableVibration(true);
                manager.createNotificationChannel(channel);
            }
            PendingIntent pending = PendingIntent.getActivity(ctx, mode.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL_ALERTS)
                    : new Notification.Builder(ctx);
            String title = MODE_LOCK.equals(mode) ? "Телефон заблокирован"
                    : MODE_ALARM.equals(mode) ? "Сигнал от родителей" : "Modar Family";
            Notification notification = builder
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .setFullScreenIntent(pending, true)
                    .build();
            manager.notify(NOTIF_ID, notification);
        } catch (Exception ignored) {
        }
    }

    private static void clearNotification(Context ctx) {
        try {
            NotificationManager manager = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIF_ID);
            }
        } catch (Exception ignored) {
        }
    }

    public static void closeAll(Context ctx) {
        clearNotification(ctx);
        AlertActivity activity = current.get();
        if (activity != null) {
            activity.finish();
        }
        stopSoundIfAny();
    }

    /** Закрывает только блокировку (оставляя сообщение или сирену). */
    public static void closeLock(Context ctx) {
        AlertActivity activity = current.get();
        if (activity != null && MODE_LOCK.equals(activity.mode)) {
            activity.finish();
        }
        if (activity == null) {
            clearNotification(ctx);
        }
    }

    private static void stopSoundIfAny() {
        AlertActivity activity = current.get();
        if (activity != null) {
            activity.stopSound();
        }
    }

    private String mode = MODE_MESSAGE;
    private boolean closed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new WeakReference<AlertActivity>(this);
        setContentView(R.layout.activity_alert);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            handleIntent(intent);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        mode = intent.getStringExtra(EXTRA_MODE);
        if (mode == null) {
            mode = MODE_MESSAGE;
        }
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (text == null) {
            text = "";
        }
        render(mode, text);
    }

    private void render(String mode, String text) {
        TextView icon = (TextView) findViewById(R.id.alertIcon);
        TextView title = (TextView) findViewById(R.id.alertTitle);
        TextView body = (TextView) findViewById(R.id.alertText);
        Button primary = (Button) findViewById(R.id.alertPrimary);
        Button sos = (Button) findViewById(R.id.alertSos);

        stopSound();
        body.setText("");
        icon.setText("🛡️");
        title.setText("Modar Family");
        Ui.setVisible(sos, true);

        if (MODE_LOCK.equals(mode)) {
            icon.setText("🔒");
            String[] parts = text.split("\n\n", 2);
            title.setText(parts[0]);
            body.setText(parts.length > 1 ? parts[1] : "Телефон заблокирован родителями.");
            primary.setText("Позвонить родителям");
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    callParent();
                }
            });
        } else if (MODE_ALARM.equals(mode)) {
            icon.setText("🚨");
            title.setText("Сигнал от родителей");
            body.setText(text);
            primary.setText("Я услышал(а)");
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    stopSound();
                    ChildService.event(AlertActivity.this, "info", "Ребёнок услышал сигнал", "info");
                    ChildService.refresh(AlertActivity.this);
                    finish();
                }
            });
            playAlarm();
        } else if (MODE_RING.equals(mode)) {
            icon.setText("🔔");
            title.setText("Родители ищут телефон");
            body.setText("Позвоните им, пожалуйста.");
            primary.setText("Хорошо");
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    stopSound();
                    finish();
                }
            });
            playAlarm();
        } else if (MODE_SAFE.equals(mode)) {
            icon.setText("🚫");
            String[] parts = text.split("\n\n", 2);
            title.setText(parts[0]);
            body.setText(parts.length > 1 ? parts[1] : "");
            primary.setText("Понятно");
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    finish();
                }
            });
            Ui.setVisible(sos, false);
            // Заглушка сама закрывается через 8 секунд
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!closed) {
                        finish();
                    }
                }
            }, 8000);
        } else {
            icon.setText("💬");
            title.setText("Сообщение от родителей");
            body.setText(text);
            primary.setText("Хорошо");
            primary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    ChildService.event(AlertActivity.this, "info", "Ребёнок прочитал сообщение", "info");
                    ChildService.refresh(AlertActivity.this);
                    finish();
                }
            });
        }

        sos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ChildService.sos(AlertActivity.this);
                stopSound();
                Ui.toast(AlertActivity.this, "Тревога отправлена родителям");
                finish();
            }
        });
    }

    private void callParent() {
        // Открываем набор номера: реальный звонок ребёнок делает сам, одной кнопкой.
        String number = Prefs.get(this, "parent_phone", "");
        if (number.isEmpty()) {
            ChildService.sos(this);
            Ui.toast(this, "Тревога отправлена родителям");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Ui.toast(this, "Не удалось открыть телефон");
        }
    }

    private void playAlarm() {
        try {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null) {
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_ALARM);
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            player = null;
        }
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                long[] pattern = {0, 600, 400};
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopSound() {
        try {
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (vibrator != null) {
                vibrator.cancel();
                vibrator = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (MODE_LOCK.equals(mode) || MODE_ALARM.equals(mode)) {
            return; // блокировку и сирену «назад» не закрывает
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        stopSound();
        clearNotification(this);
        super.onDestroy();
    }
}
