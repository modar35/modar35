package com.modar.family;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Служба защиты телефона ребёнка.
 *
 * Каждые N секунд (по умолчанию 5 минут, настраивается родителем) отправляет на сервер
 * отчёт: местоположение, заряд, экранное время, уведомления, заблокированные сайты —
 * и получает в ответ команды родителя: сирена, блокировка экрана, сообщение, фото и т.п.
 *
 * Параллельно следит за режимами: «школа», «ночной режим», лимит экранного времени,
 * запрещённые приложения и интернет по расписанию.
 */
public class ChildService extends Service implements LocationListener, SensorEventListener {

    public static final String ACTION_START = "com.modar.family.START";
    public static final String ACTION_STOP = "com.modar.family.STOP";
    public static final String ACTION_REFRESH = "com.modar.family.REFRESH";
    public static final String ACTION_APPLY = "com.modar.family.APPLY";

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL = "modar_guard";
    private static final long ENFORCE_INTERVAL_MS = 15000;

    private HandlerThread thread;
    private Handler handler;
    private boolean reporting;
    private long lastTick;
    private String lastForeground = "";
    private long lastLockShown;
    private Location lastLocation;
    private int steps = -1;
    private static ChildService instance;

    /* --------------------------------------------------------------- запуск */

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, ChildService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void refresh(Context ctx) {
        Intent intent = new Intent(ctx, ChildService.class);
        intent.setAction(ACTION_REFRESH);
        try {
            ctx.startService(intent);
        } catch (Exception ignored) {
        }
    }

    public static void apply(Context ctx) {
        Intent intent = new Intent(ctx, ChildService.class);
        intent.setAction(ACTION_APPLY);
        try {
            ctx.startService(intent);
        } catch (Exception ignored) {
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        thread = new HandlerThread("modar-child");
        thread.start();
        handler = new Handler(thread.getLooper());
        startForegroundSafely();
    }

    private void startForegroundSafely() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Защита включена",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Служба родительского контроля отправляет отчёты родителям");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, ChildActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("Modar Family")
                .setContentText("Защита включена. Телефон на связи с родителями.")
                .setSmallIcon(R.drawable.ic_shield)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            // Android 14 бьёт исключением, если тип «location» заявлен без разрешения на геопозицию,
            // поэтому тип выбираем по факту выданных разрешений.
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            try {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
                startForeground(NOTIF_ID, notification, type);
                return;
            } catch (Exception e) {
                Prefs.put(this, "fg_error", String.valueOf(e.getMessage()));
            }
        }
        try {
            startForeground(NOTIF_ID, notification);
        } catch (Exception e) {
            Prefs.put(this, "fg_error", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action) || ACTION_APPLY.equals(action)) {
            report(false);
            enforce();
            return START_STICKY;
        }
        startLocation();
        startSensors();
        scheduleReport(1000);
        scheduleEnforce(2000);
        report(false);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopLocation();
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    /* ---------------------------------------------------------- местоположение */

    private void startLocation() {
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000, 30, this, Looper.getMainLooper());
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 100, this, Looper.getMainLooper());
            }
            lastLocation = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (lastLocation == null) {
                lastLocation = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
        } catch (SecurityException e) {
            Prefs.setFlag(this, "location_denied", true);
        } catch (Exception ignored) {
        }
    }

    private void stopLocation() {
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager != null) {
            try {
                manager.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            lastLocation = location;
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /* ------------------------------------------------------------------ шаги */

    private void startSensors() {
        try {
            SensorManager sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor step = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (step != null) {
                sensors.registerListener(this, step, SensorManager.SENSOR_DELAY_NORMAL);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.sensor.getType() != Sensor.TYPE_STEP_COUNTER) {
            return;
        }
        long value = (long) event.values[0];
        String storedDay = Prefs.get(this, "steps_day", "");
        if (!Ui.dayKey().equals(storedDay)) {
            Prefs.put(this, "steps_day", Ui.dayKey());
            Prefs.put(this, "steps_base", String.valueOf(value));
        }
        long base = 0;
        try {
            base = Long.parseLong(Prefs.get(this, "steps_base", String.valueOf(value)));
        } catch (Exception ignored) {
        }
        steps = (int) Math.max(0, value - base);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /* ----------------------------------------------------------- отчёты серверу */

    private void scheduleReport(long delay) {
        handler.removeCallbacks(reportTask);
        handler.postDelayed(reportTask, delay);
    }

    private final Runnable reportTask = new Runnable() {
        @Override
        public void run() {
            report(true);
        }
    };

    private void scheduleEnforce(long delay) {
        handler.removeCallbacks(enforceTask);
        handler.postDelayed(enforceTask, delay);
    }

    private final Runnable enforceTask = new Runnable() {
        @Override
        public void run() {
            enforce();
            scheduleEnforce(ENFORCE_INTERVAL_MS);
        }
    };

    /** Отправка отчёта и получение команд. */
    public void report(final boolean reschedule) {
        if (reporting || !Prefs.configured(this)) {
            if (reschedule) {
                scheduleReport(30000);
            }
            return;
        }
        reporting = true;
        final long startedAt = System.currentTimeMillis();
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = buildReport();
                    JSONObject response = Http.post(ChildService.this, "/api/v1/device/report", body);
                    Prefs.setFlag(ChildService.this, "had_report", true);
                    Prefs.put(ChildService.this, "last_report", String.valueOf(System.currentTimeMillis()));
                    JSONObject settings = response.optJSONObject("settings");
                    if (settings != null) {
                        Prefs.setPolicy(ChildService.this, settings);
                        enforce();
                    }
                    JSONArray commands = response.optJSONArray("commands");
                    if (commands != null) {
                        for (int i = 0; i < commands.length(); i++) {
                            handleCommand(commands.optJSONObject(i));
                        }
                    }
                } catch (Exception e) {
                    Prefs.put(ChildService.this, "last_error", String.valueOf(e.getMessage()));
                    Prefs.setFlag(ChildService.this, "report_failed", true);
                } finally {
                    reporting = false;
                    if (reschedule) {
                        long interval = Math.max(30, Prefs.reportSeconds(ChildService.this)) * 1000L;
                        long spent = System.currentTimeMillis() - startedAt;
                        scheduleReport(Math.max(5000, interval - spent));
                    }
                }
            }
        });
    }

    /** Собирает отчёт о телефоне ребёнка. */
    private JSONObject buildReport() throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", Prefs.childName(this));
        body.put("model", Prefs.deviceInfo(this));
        body.put("version", BuildConfigVersion.VERSION_NAME);
        body.put("accessibility", WebFilterService.isRunning());
        body.put("admin", AdminReceiver.isActive(this));
        body.put("locationPermission", Prefs.flag(this, "location_denied") ? false : true);
        body.put("notificationsPermission", NotifListenerService.isEnabled(this));
        body.put("vpn", FilterVpnService.isRunning());
        body.put("steps", steps >= 0 ? steps : JSONObject.NULL);

        int battery = batteryLevel();
        if (battery >= 0) {
            body.put("battery", battery);
            body.put("charging", isCharging());
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        body.put("screenOn", power != null && power.isInteractive());

        if (lastLocation != null) {
            body.put("lat", lastLocation.getLatitude());
            body.put("lon", lastLocation.getLongitude());
            body.put("acc", Math.round(lastLocation.getAccuracy()));
            body.put("speed", Math.round(lastLocation.getSpeed() * 10) / 10.0);
        }

        body.put("apps", installedApps());
        JSONArray usage = Usage.toReport(this, getPackageManager());
        if (usage.length() > 0) {
            body.put("usage", usage);
            body.put("day", Ui.dayKey());
        }

        JSONArray notifications = NotifListenerService.drain();
        if (notifications.length() > 0) {
            body.put("notifications", notifications);
        }
        JSONArray blocked = WebFilterService.drainBlocked();
        JSONArray dnsBlocked = FilterVpnService.drainBlocked();
        for (int i = 0; i < dnsBlocked.length(); i++) {
            blocked.put(dnsBlocked.opt(i));
        }
        if (blocked.length() > 0) {
            body.put("webBlocked", blocked);
        }
        JSONArray events = pendingEvents();
        if (events.length() > 0) {
            body.put("events", events);
        }
        return body;
    }

    private int batteryLevel() {
        try {
            BatteryManager manager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (manager != null) {
                return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                return scale > 0 ? Math.round(level * 100f / scale) : -1;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private boolean isCharging() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) {
                return false;
            }
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }

    /** Список приложений с иконками-«ярлыками» (без картинок, чтобы отчёт был лёгким). */
    private JSONArray installedApps() {
        JSONArray array = new JSONArray();
        try {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            Collections.sort(list, new Comparator<android.content.pm.ResolveInfo>() {
                @Override
                public int compare(android.content.pm.ResolveInfo a, android.content.pm.ResolveInfo b) {
                    return String.valueOf(a.loadLabel(getPackageManager()))
                            .compareToIgnoreCase(String.valueOf(b.loadLabel(getPackageManager())));
                }
            });
            for (android.content.pm.ResolveInfo info : list) {
                JSONObject app = new JSONObject();
                String pkg = info.activityInfo.packageName;
                app.put("pkg", pkg);
                app.put("label", String.valueOf(info.loadLabel(pm)));
                app.put("system", (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                array.put(app);
                if (array.length() >= 200) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return array;
    }

    /* ------------------------------------------------------------- события */

    private static final List<JSONObject> EVENTS = new ArrayList<JSONObject>();

    public static void event(Context ctx, String type, String message, String level) {
        JSONObject item = new JSONObject();
        try {
            item.put("type", type);
            item.put("message", message);
            item.put("level", level == null ? "info" : level);
        } catch (Exception e) {
            return;
        }
        synchronized (ChildService.class) {
            EVENTS.add(item);
            while (EVENTS.size() > 40) {
                EVENTS.remove(0);
            }
        }
    }

    public static void sos(Context ctx) {
        event(ctx, "sos", "🆘 Тревога SOS от ребёнка!", "alert");
        refresh(ctx);
    }

    private JSONArray pendingEvents() {
        JSONArray array = new JSONArray();
        synchronized (ChildService.class) {
            for (JSONObject item : EVENTS) {
                array.put(item);
            }
            EVENTS.clear();
        }
        JSONArray local = WebFilterService.drainEvents();
        for (int i = 0; i < local.length(); i++) {
            array.put(local.opt(i));
        }
        return array;
    }

    /* ------------------------------------------------------------- команды */

    private void handleCommand(final JSONObject command) {
        if (command == null) {
            return;
        }
        final String name = command.optString("cmd", "");
        final String arg = command.optString("arg", "");
        final String id = command.optString("id", "");
        Ui.ui(new Runnable() {
            @Override
            public void run() {
                execute(name, arg, id);
            }
        });
    }

    private void execute(String name, String arg, String id) {
        if ("message".equals(name)) {
            AlertActivity.show(this, AlertActivity.MODE_MESSAGE, arg.isEmpty() ? "Сообщение от родителя" : arg);
        } else if ("alarm".equals(name)) {
            AlertActivity.show(this, AlertActivity.MODE_ALARM, "Родители подали сигнал. Позвоните им.");
        } else if ("ring".equals(name)) {
            AlertActivity.show(this, AlertActivity.MODE_RING, "Родители ищут этот телефон");
        } else if ("lock".equals(name)) {
            Prefs.setFlag(this, "locked", true);
            AlertActivity.show(this, AlertActivity.MODE_LOCK, "Телефон заблокирован родителями");
            lockScreenNow();
        } else if ("unlock".equals(name)) {
            Prefs.setFlag(this, "locked", false);
            AlertActivity.closeAll(this);
        } else if ("sos_clear".equals(name)) {
            AlertActivity.closeAll(this);
        } else if ("refresh".equals(name) || "locate".equals(name) || "sync".equals(name)) {
            report(false);
        } else if ("apply_settings".equals(name)) {
            report(false);
        } else if ("snapshot".equals(name)) {
            startScreenCapture(arg, 1);
        } else if ("tour".equals(name)) {
            startScreenCapture(arg, Math.max(1, parseInt(arg, 20)));
        } else if ("photo".equals(name)) {
            CaptureService.start(this, arg);
        } else if ("call".equals(name)) {
            Intent intent = new Intent(this, CallActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(CallActivity.EXTRA_CALL_ID, arg);
            intent.putExtra(CallActivity.EXTRA_ROLE, CallActivity.ROLE_CHILD);
            startActivity(intent);
        } else if ("torch".equals(name)) {
            torch("1".equals(arg));
        }
    }

    private static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void torch(boolean on) {
        // Фонарик: используем камеру через CaptureService-подобный путь не нужен —
        // для краткой подсветки достаточно фонарика основного приложения.
        try {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) {
                return;
            }
            String id = manager.getCameraIdList()[0];
            manager.setTorchMode(id, on);
        } catch (Exception ignored) {
        }
    }

    private void startScreenCapture(String seconds, int duration) {
        if (ScreenService.hasConsent(this)) {
            ScreenService.start(this, duration);
        } else {
            Intent intent = new Intent(this, ChildActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(ChildActivity.EXTRA_REQUEST_SCREEN, duration);
            startActivity(intent);
        }
    }

    private void lockScreenNow() {
        try {
            DevicePolicyManager manager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (manager != null && manager.isAdminActive(admin)) {
                manager.lockNow();
            } else {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                if (power != null) {
                    long timeout = 1;
                    // Без прав администратора просто гасим экран по короткому таймауту.
                    android.provider.Settings.System.putInt(getContentResolver(),
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT, (int) timeout);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /* -------------------------------------------------------- контроль режимов */

    /** Пришло новое активное приложение (от службы спец. возможностей). */
    public static void noteForeground(Context ctx, String pkg) {
        if (instance == null) {
            return;
        }
        instance.tick(pkg);
        instance.enforce();
    }

    /**
     * Учёт экранного времени: добавляет прошедшее время прошлому активному приложению
     * и запоминает новое. Если служба «Специальные возможности» выключена, приложение
     * определяется по системной статистике (polling раз в 15 секунд).
     */
    private void tick(String hint) {
        long now = System.currentTimeMillis();
        long delta = lastTick > 0 ? now - lastTick : 0;
        String previous = lastForeground;
        if (delta > 0 && delta < 10 * 60 * 1000 && previous != null && !previous.isEmpty()
                && !previous.equals(getPackageName())) {
            Usage.add(this, previous, delta);
        }
        String current = hint;
        if (current == null || current.isEmpty()) {
            current = WebFilterService.foregroundPackage();
        }
        if (current == null || current.isEmpty()) {
            current = Usage.currentForeground(this);
        }
        if (current != null && !current.isEmpty()) {
            lastForeground = current;
        }
        lastTick = now;
    }

    public static void checkNow(Context ctx) {
        if (instance != null) {
            instance.enforce();
        }
    }

    /** Текущее активное приложение (по данным службы защиты). */
    public static String foreground() {
        return WebFilterService.foregroundPackage();
    }

    private boolean inEnforce;

    /** Проверяет режимы и лимиты: «школа», ночь, интернет, экранное время, блокировки. */
    public void enforce() {
        if (inEnforce || !Prefs.configured(this)) {
            return;
        }
        inEnforce = true;
        try {
            enforceInner();
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            inEnforce = false;
        }
    }

    private void enforceInner() {
        noteUsageTick();
        JSONObject policy = Prefs.policy(this);
        boolean bedtime = Windows.inside(policy.optJSONObject("bedtime"));
        boolean school = Windows.inside(policy.optJSONObject("schoolMode"));
        boolean internetOff = Windows.inside(policy.optJSONObject("internetOff"));
        boolean kiosk = policy.optBoolean("kiosk", false);
        int dailyLimit = policy.optInt("dailyLimitMin", 0);
        long usedMs = Usage.totalMs(this);
        boolean overDaily = dailyLimit > 0 && usedMs >= dailyLimit * 60000L;
        boolean locked = Prefs.flag(this, "locked") || bedtime || school || overDaily || kiosk;

        FilterVpnService.setInternetOff(this, internetOff);

        if (locked) {
            long now = System.currentTimeMillis();
            if (now - lastLockShown > 20000) {
                lastLockShown = now;
                String reason = Prefs.flag(this, "locked") ? "Телефон заблокирован родителями"
                        : bedtime ? "Ночной режим: пора спать"
                        : school ? "Режим «Школа»: уроки до " + Ui.str(policy.optJSONObject("schoolMode"), "to", "")
                        : kiosk ? "Режим «Только учёба»"
                        : "Лимит экранного времени на сегодня исчерпан";
                AlertActivity.show(this, AlertActivity.MODE_LOCK, reason);
            }
        } else {
            AlertActivity.closeLock(this);
        }

        // Запрещённые приложения: если открыто — закрываем и объясняем
        String foreground = WebFilterService.foregroundPackage();
        if (foreground != null && !foreground.isEmpty() && isAppBlocked(foreground, locked)) {
            showSafeScreen(this, "Приложение недоступно",
                    appLabel(foreground) + "\n\nЭто приложение закрыто родителями.");
            WebFilterService.report(this, "app", "Попытка открыть " + appLabel(foreground) + " — заблокировано", "warn");
        }
    }

    /** Учёт экранного времени без службы спец. возможностей (раз в 15 секунд). */
    private void noteUsageTick() {
        if (WebFilterService.foregroundPackage() == null) {
            tick(null);
        } else {
            lastTick = System.currentTimeMillis();
        }
    }

    private boolean isAppBlocked(String pkg, boolean locked) {
        JSONObject policy = Prefs.policy(this);
        JSONArray blocked = policy.optJSONArray("blockApps");
        if (blocked != null && contains(blocked, pkg)) {
            return true;
        }
        JSONArray allowOnly = policy.optJSONArray("allowOnly");
        if (allowOnly != null && allowOnly.length() > 0 && !contains(allowOnly, pkg)) {
            return true;
        }
        int limit = Ui.integer(Ui.object(policy, "appLimits"), pkg, 0);
        if (limit > 0 && Usage.app(this, pkg) >= limit * 60000L) {
            return true;
        }
        if (locked && !pkg.equals(getPackageName()) && !isSystemAllowed(pkg)) {
            return true;
        }
        return false;
    }

    private boolean isSystemAllowed(String pkg) {
        // Во время блокировки оставляем телефон, сообщения, камеру и системные приложения
        String[] allowed = {"com.android.dialer", "com.android.phone", "com.google.android.dialer",
                "com.android.mms", "com.google.android.apps.messaging", "com.android.settings",
                "com.android.systemui", "com.android.camera", "com.google.android.GoogleCamera"};
        for (String item : allowed) {
            if (pkg.startsWith(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    private String appLabel(String pkg) {
        return Usage.appLabel(getPackageManager(), pkg);
    }

    /** Показывает ребёнку понятное окно-заглушку вместо запрещённого контента. */
    public static void showSafeScreen(Context ctx, String title, String text) {
        AlertActivity.show(ctx, AlertActivity.MODE_SAFE, title + "\n\n" + text);
    }

    /** Версия приложения — используется в отчётах. */
    public static final class BuildConfigVersion {
        public static final String VERSION_NAME = "2.1.0";
    }
}
