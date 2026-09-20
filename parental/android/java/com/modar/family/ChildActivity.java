package com.modar.family;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Экран телефона ребёнка: понятный статус «связь с родителями есть»,
 * большая кнопка SOS и выдача разрешений защиты.
 */
public class ChildActivity extends Activity {

    public static final String EXTRA_REQUEST_SCREEN = "requestScreen";
    private static final int REQUEST_PROJECTION = 301;

    private LinearLayout permissionsBox;
    private TextView statusView;
    private TextView timeView;
    private TextView modeView;
    private LinearLayout sosButtonBox;
    private android.widget.EditText parentPhone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child);
        permissionsBox = (LinearLayout) findViewById(R.id.permissionsBox);
        parentPhone = (android.widget.EditText) findViewById(R.id.parentPhoneField);
        parentPhone.setText(Prefs.get(this, "parent_phone", ""));
        findViewById(R.id.parentPhoneSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String value = parentPhone.getText().toString().replaceAll("[^0-9+]", "");
                Prefs.put(ChildActivity.this, "parent_phone", value);
                Ui.toast(ChildActivity.this, value.isEmpty()
                        ? "Номер удалён" : "Теперь кнопка «Позвонить родителям» набирает " + value);
            }
        });
        statusView = (TextView) findViewById(R.id.childStatus);
        timeView = (TextView) findViewById(R.id.childTime);
        modeView = (TextView) findViewById(R.id.childMode);

        findViewById(R.id.childSos).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Ui.confirm(ChildActivity.this, "Тревога SOS",
                        "Отправить родителям сигнал «мне нужна помощь»?", new Runnable() {
                            @Override
                            public void run() {
                                ChildService.sos(ChildActivity.this);
                                Ui.toast(ChildActivity.this, "Родители получили сигнал");
                            }
                        });
            }
        });
        findViewById(R.id.childRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ChildService.refresh(ChildActivity.this);
                Ui.toast(ChildActivity.this, "Обновляем данные…");
                Ui.ui(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                });
            }
        });
        findViewById(R.id.childHide).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Убираем с экрана: служба продолжает работать
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
            }
        });

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 11);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 12);
        }

        int screenSeconds = getIntent().getIntExtra(EXTRA_REQUEST_SCREEN, 0);
        if (screenSeconds > 0) {
            requestScreenCapture(screenSeconds);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        renderPermissions();
        if (!ChildService.isRunning() && Prefs.configured(this)) {
            ChildService.start(this);
        }
    }

    /* ------------------------------------------------------------- статус */

    private void refreshStatus() {
        boolean configured = Prefs.configured(this);
        long lastReport = 0;
        try {
            lastReport = Long.parseLong(Prefs.get(this, "last_report", "0"));
        } catch (Exception ignored) {
        }
        boolean fresh = System.currentTimeMillis() - lastReport < 15 * 60 * 1000;
        statusView.setText(configured
                ? (fresh ? "✅ Связь с родителями: есть (" + Ui.ago(lastReport) + ")"
                : (Prefs.flag(this, "report_failed") ? "⚠️ Нет связи с сервером — проверьте интернет"
                : "⏳ Ждём первый отчёт…"))
                : "⚠️ Приложение не настроено");
        long used = Usage.totalMs(this);
        JSONObject policy = Prefs.policy(this);
        int limit = policy.optInt("dailyLimitMin", 0);
        timeView.setText(limit > 0
                ? "Экранное время сегодня: " + Ui.minutes(used) + " из " + limit + " мин"
                : "Экранное время сегодня: " + Ui.minutes(used));

        boolean bedtime = Windows.inside(policy.optJSONObject("bedtime"));
        boolean school = Windows.inside(policy.optJSONObject("schoolMode"));
        boolean internetOff = Windows.inside(policy.optJSONObject("internetOff"));
        StringBuilder modes = new StringBuilder();
        if (school) {
            modes.append("🏫 режим «Школа»  ");
        }
        if (bedtime) {
            modes.append("😴 ночной режим  ");
        }
        if (internetOff) {
            modes.append("📵 интернет выключен  ");
        }
        modeView.setText(modes.length() == 0 ? "Режимы: обычный" : modes.toString());
    }

    /* --------------------------------------------------------- разрешения */

    private void renderPermissions() {
        permissionsBox.removeAllViews();
        addPermission("📍 Геолокация", granted(Manifest.permission.ACCESS_FINE_LOCATION),
                new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, 11);
                    }
                });
        if (Build.VERSION.SDK_INT >= 29) {
            addPermission("📍 Геолокация в фоне", granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    new Runnable() {
                        @Override
                        public void run() {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", getPackageName(), null));
                            startActivity(intent);
                            Ui.toast(ChildActivity.this, "Выберите «Разрешения» → «Геопозиция» → «Всегда разрешать»");
                        }
                    });
        }
        addPermission("🔔 Доступ к уведомлениям", NotifListenerService.isEnabled(this), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        addPermission("♿ Специальные возможности", WebFilterService.isRunning(), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Ui.toastLong(ChildActivity.this, "Найдите «Modar Family» и включите службу");
            }
        });
        addPermission("📊 Статистика приложений", Usage.hasStatsPermission(this), new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Ui.toastLong(ChildActivity.this, "В списке выберите «Modar Family» и включите доступ");
            }
        });
        addPermission("🛡️ Права администратора", AdminReceiver.isActive(this), new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        new ComponentName(ChildActivity.this, AdminReceiver.class));
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Права нужны, чтобы родитель мог выключить экран по команде.");
                startActivity(intent);
            }
        });
        addPermission("🪟 Показ поверх окон", Settings.canDrawOverlays(this), new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            }
        });
        addPermission("🔋 Работа без ограничений батареи", isBatteryUnrestricted(), new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                } catch (Exception e) {
                    Ui.toast(ChildActivity.this, "Откройте настройки → Батарея → Без ограничений");
                }
            }
        });
        addPermission("🌐 Фильтр сайтов (VPN)", FilterVpnService.isRunning(), new Runnable() {
            @Override
            public void run() {
                Intent prepare = android.net.VpnService.prepare(ChildActivity.this);
                if (prepare != null) {
                    startActivityForResult(prepare, 201);
                } else {
                    FilterVpnService.start(ChildActivity.this);
                    Ui.toast(ChildActivity.this, "Фильтр сайтов включён");
                }
            }
        });
        addPermission("📸 Съёмка экрана", ScreenService.hasConsent(this), new Runnable() {
            @Override
            public void run() {
                requestScreenCapture(20);
            }
        });
        addPermission("🎙 Микрофон и камера", granted(Manifest.permission.RECORD_AUDIO)
                        && granted(Manifest.permission.CAMERA),
                new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.CAMERA}, 13);
                    }
                });
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBatteryUnrestricted() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void addPermission(String title, boolean ok, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        TextView text = Ui.text(this, (ok ? "✅ " : "⬜ ") + title, 14,
                ok ? R.color.ok : R.color.text);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text);
        if (!ok) {
            Button button = new Button(this);
            button.setText("Включить");
            button.setTextSize(12);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    action.run();
                }
            });
            row.addView(button);
        }
        permissionsBox.addView(row);
    }

    /* ------------------------------------------------------ снимок экрана */

    private void requestScreenCapture(final int seconds) {
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION + seconds);
            Ui.toastLong(this, "Родитель просит показать экран: разрешите запись экрана один раз");
        } catch (Exception e) {
            Ui.toast(this, "Не удалось запросить снимок экрана");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode >= REQUEST_PROJECTION && requestCode < REQUEST_PROJECTION + 120) {
            int seconds = requestCode - REQUEST_PROJECTION;
            if (resultCode == RESULT_OK && data != null) {
                ScreenService.startFromActivity(this, resultCode, data, seconds);
                Ui.toast(this, "Экран доступен родителям " + seconds + " с");
            } else {
                Ui.toast(this, "Снимок экрана не разрешён");
            }
            return;
        }
        if (requestCode == 201) {
            if (resultCode == RESULT_OK) {
                FilterVpnService.start(this);
                Ui.toast(this, "Фильтр сайтов включён");
            } else {
                Ui.toast(this, "Фильтр сайтов не включён");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        renderPermissions();
    }

    /** Пароль родителя в этом приложении не показывается ребёнку. */
    public static void open(Context ctx) {
        Intent intent = new Intent(ctx, ChildActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }
}
