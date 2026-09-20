package com.modar.family;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Главный экран родителя: карта, экранное время, команды, события, настройки.
 * Данные приходят с сервера, поэтому всё это работает из любой точки мира —
 * достаточно интернета на телефоне родителя.
 */
public class ParentActivity extends Activity implements MapView.Listener {

    private MapView map;
    private LinearLayout devicesBox;
    private LinearLayout eventsBox;
    private LinearLayout usageBox;
    private LinearLayout settingsBox;
    private LinearLayout placesBox;
    private TextView summaryView;
    private ImageView screenView;
    private JSONObject status = new JSONObject();
    private String selectedId = "";
    private int rangeHours = 24;
    private android.os.Handler handler = new android.os.Handler();
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent);

        map = (MapView) findViewById(R.id.mapView);
        map.setListener(this);
        devicesBox = (LinearLayout) findViewById(R.id.devicesBox);
        eventsBox = (LinearLayout) findViewById(R.id.eventsBox);
        usageBox = (LinearLayout) findViewById(R.id.usageBox);
        settingsBox = (LinearLayout) findViewById(R.id.settingsBox);
        placesBox = (LinearLayout) findViewById(R.id.placesBox);
        summaryView = (TextView) findViewById(R.id.summaryView);
        screenView = (ImageView) findViewById(R.id.screenView);

        ((TextView) findViewById(R.id.parentTitle)).setText(
                Prefs.familyName(this).isEmpty() ? "Родительский контроль" : Prefs.familyName(this));

        findViewById(R.id.parentRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                load(false);
            }
        });
        findViewById(R.id.parentPair).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPairCode();
            }
        });
        findViewById(R.id.parentScreen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                askScreenshot();
            }
        });
        findViewById(R.id.parentLogout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Ui.confirm(ParentActivity.this, "Выход", "Отключить это устройство от семьи?", new Runnable() {
                    @Override
                    public void run() {
                        Prefs.clear(ParentActivity.this);
                        startActivity(new Intent(ParentActivity.this, MainActivity.class));
                        finish();
                    }
                });
            }
        });

        buildRanges();
        buildCommands();
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacksAndMessages(null);
        load(false);
        tick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    private void tick() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                load(false);
                tick();
            }
        }, 20000);
    }

    /* --------------------------------------------------------- переключатели */

    private void buildRanges() {
        LinearLayout box = (LinearLayout) findViewById(R.id.rangeBox);
        final int[] hours = {1, 6, 24, 168};
        final String[] labels = {"1 ч", "6 ч", "24 ч", "7 дней"};
        for (int i = 0; i < hours.length; i++) {
            final int value = hours[i];
            Button button = new Button(this);
            button.setText(labels[i]);
            button.setTextSize(12);
            button.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    rangeHours = value;
                    load(false);
                    map.fitAll();
                }
            });
            box.addView(button);
        }
    }

    private void buildCommands() {
        final LinearLayout box = (LinearLayout) findViewById(R.id.commandsBox);
        String[][] commands = {
                {"📍", "Где сейчас", "locate"},
                {"🚨", "Сирена", "alarm"},
                {"🔒", "Блокировать", "lock"},
                {"🔓", "Разблокировать", "unlock"},
                {"💬", "Сообщение", "message"},
                {"🔔", "Найти телефон", "ring"},
                {"📞", "Позвонить", "call"},
                {"🤳", "Фото с камеры", "photo"},
                {"🖼", "Снимок экрана", "snapshot"},
                {"🧭", "Экскурсия", "tour"},
                {"🔆", "Фонарик", "torch"},
                {"⤓", "Обновить", "sync"},
        };
        for (final String[] item : commands) {
            Button button = new Button(this);
            button.setText(item[0] + " " + item[1]);
            button.setTextSize(13);
            button.setAllCaps(false);
            button.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            button.setLayoutParams(params);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendCommand(item[2], item[1]);
                }
            });
            box.addView(button);
        }
    }

    /* ------------------------------------------------------------- загрузка */

    private void load(final boolean first) {
        if (loading) {
            return;
        }
        loading = true;
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject data = Http.get(ParentActivity.this, "/api/v1/status");
                    final JSONObject history = selectedDeviceId().isEmpty() ? new JSONObject()
                            : Http.get(ParentActivity.this, "/api/v1/history?deviceId=" + selectedDeviceId()
                            + "&hours=" + rangeHours);
                    final JSONObject dashboard = Http.get(ParentActivity.this, "/api/v1/dashboard");
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            status = data;
                            render(data, history, dashboard);
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            summaryView.setText("Нет связи с сервером: " + e.getMessage());
                            if (first) {
                                Ui.toastLong(ParentActivity.this, "Проверьте адрес сервера и интернет: " + e.getMessage());
                            }
                        }
                    });
                } finally {
                    loading = false;
                }
            }
        });
    }

    private String selectedDeviceId() {
        JSONArray devices = Ui.array(status, "devices");
        if (devices.length() == 0) {
            return "";
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && selectedId.equals(device.optString("id", ""))) {
                return selectedId;
            }
        }
        selectedId = devices.optJSONObject(0).optString("id", "");
        Prefs.put(this, "selected_device", selectedId);
        return selectedId;
    }

    private JSONObject selectedDevice() {
        JSONArray devices = Ui.array(status, "devices");
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && selectedId.equals(device.optString("id", ""))) {
                return device;
            }
        }
        return devices.length() > 0 ? devices.optJSONObject(0) : null;
    }

    private void render(JSONObject data, JSONObject history, JSONObject dashboard) {
        JSONArray devices = Ui.array(data, "devices");
        JSONObject family = Ui.object(data, "family");
        Prefs.setFamilyName(this, family.optString("name", ""));
        String pairCode = family.optString("pairCode", "");
        ((TextView) findViewById(R.id.parentTitle)).setText(family.optString("name", "Родительский контроль") +
                (pairCode.isEmpty() ? "" : ""));

        JSONObject totals = Ui.object(dashboard, "totals");
        summaryView.setText("Устройств: " + totals.optInt("devices", devices.length())
                + " • на связи: " + totals.optInt("online", 0)
                + " • экран сегодня: " + totals.optInt("screenTodayMin", 0) + " мин"
                + " • тревог за сутки: " + totals.optInt("alerts24h", 0));

        /* --- список детей --- */
        devicesBox.removeAllViews();
        for (int i = 0; i < devices.length(); i++) {
            final JSONObject device = devices.optJSONObject(i);
            if (device == null) {
                continue;
            }
            devicesBox.addView(deviceCard(device));
        }
        if (devices.length() == 0) {
            TextView empty = Ui.text(this, "Пока нет подключённых телефонов. Нажмите «＋ Подключить телефон», " +
                    "чтобы получить код для приложения ребёнка.", 13, R.color.text_muted);
            devicesBox.addView(empty);
        }

        /* --- карта --- */
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (int i = 0; i < devices.length(); i++) {
            list.add(devices.optJSONObject(i));
        }
        map.setDevices(list, selectedId);
        JSONArray track = Ui.array(history, "track");
        List<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < track.length(); i++) {
            JSONObject point = track.optJSONObject(i);
            if (point != null) {
                points.add(new double[]{point.optDouble("lat", 0), point.optDouble("lon", 0)});
            }
        }
        map.setTrack(points);
        List<JSONObject> places = new ArrayList<JSONObject>();
        JSONArray placeArray = Ui.array(data, "places");
        for (int i = 0; i < placeArray.length(); i++) {
            places.add(placeArray.optJSONObject(i));
        }
        map.setPlaces(places);

        JSONObject device = selectedDevice();
        if (device != null && device.has("lat") && !device.isNull("lat")) {
            map.setCenter(device.optDouble("lat", 0), device.optDouble("lon", 0), map.getZoom());
        }
        ((TextView) findViewById(R.id.mapInfo)).setText(device == null ? "Нет устройства"
                : "Точек за период: " + points.size() + " • обновлено " + Ui.ago(device.optLong("locatedAt", 0)));

        /* --- события --- */
        eventsBox.removeAllViews();
        JSONArray events = Ui.array(data, "events");
        for (int i = 0; i < events.length() && i < 25; i++) {
            eventsBox.addView(eventRow(events.optJSONObject(i)));
        }
        if (events.length() == 0) {
            eventsBox.addView(Ui.text(this, "Событий пока нет", 13, R.color.text_muted));
        }

        /* --- экранное время --- */
        renderUsage(device);

        /* --- настройки выбранного ребёнка --- */
        renderSettings(device);

        /* --- геозоны --- */
        placesBox.removeAllViews();
        for (int i = 0; i < placeArray.length(); i++) {
            placesBox.addView(placeRow(placeArray.optJSONObject(i)));
        }
        if (placeArray.length() == 0) {
            placesBox.addView(Ui.text(this, "Долгое нажатие по карте добавляет геозону " +
                    "(школа, дом, секция) — придёт уведомление, когда ребёнок придёт или уйдёт.", 13, R.color.text_muted));
        }

        /* --- снимок экрана --- */
        loadScreen();
    }

    private View deviceCard(final JSONObject device) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        int pad = Ui.dp(this, 12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(params);

        boolean selected = device.optString("id", "").equals(selectedId);
        if (selected) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xFF1B2340);
            background.setCornerRadius(Ui.dp(this, 14));
            background.setStroke(Ui.dp(this, 2), 0xFF6C8CFF);
            card.setBackground(background);
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = Ui.text(this, device.optString("name", "Устройство"), 16, R.color.text);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(name);
        head.addView(Ui.text(this, device.optBoolean("online", false) ? "🟢 на связи" : "⚪ " + Ui.ago(device.optLong("lastSeen", 0)),
                12, R.color.text_muted));
        card.addView(head);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, Ui.dp(this, 6), 0, 0);
        JSONObject state = Ui.object(device, "state");
        String battery = device.isNull("battery") ? "—" : device.optInt("battery", 0) + "%";
        Ui.chip(chips, this, "🔋 " + battery + (device.optBoolean("charging", false) ? "⚡" : ""),
                battery.startsWith("—") ? R.color.chip_bg : (device.optInt("battery", 0) <= 20 ? R.color.warn_soft : R.color.ok_soft),
                device.optInt("battery", 100) <= 20 ? R.color.warn : R.color.ok);
        if (device.optJSONObject("usage") != null) {
            Ui.chip(chips, this, "⏱ " + Ui.minutes(device.optJSONObject("usage").optLong("totalMs", 0)),
                    R.color.chip_bg, R.color.text);
        }
        if (state.optBoolean("schoolNow", false)) {
            Ui.chip(chips, this, "🏫 школа", R.color.warn_soft, R.color.warn);
        }
        if (state.optBoolean("bedtimeNow", false)) {
            Ui.chip(chips, this, "😴 ночь", R.color.warn_soft, R.color.warn);
        }
        if (state.optBoolean("internetOffNow", false)) {
            Ui.chip(chips, this, "📵 интернет off", R.color.bad_soft, R.color.danger);
        }
        if (state.has("limitLeftMin") && !state.isNull("limitLeftMin")) {
            Ui.chip(chips, this, "⏳ осталось " + state.optInt("limitLeftMin", 0) + " мин", R.color.chip_bg, R.color.text);
        }
        if (!device.optBoolean("accessibility", false)) {
            Ui.chip(chips, this, "⚠️ нет «Спец. возможностей»", R.color.bad_soft, R.color.danger);
        }
        if (!device.optBoolean("vpn", true)) {
            Ui.chip(chips, this, "🌐 фильтр сайтов выкл.", R.color.warn_soft, R.color.warn);
        }
        card.addView(chips);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button select = new Button(this);
        select.setText(selected ? "Выбрано" : "Выбрать");
        select.setTextSize(12);
        select.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectedId = device.optString("id", "");
                Prefs.put(ParentActivity.this, "selected_device", selectedId);
                load(false);
            }
        });
        Button rename = new Button(this);
        rename.setText("Название");
        rename.setTextSize(12);
        rename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                renameDevice(device);
            }
        });
        actions.addView(select);
        actions.addView(rename);
        card.addView(actions);
        return card;
    }

    private View eventRow(JSONObject event) {
        if (event == null) {
            return Ui.text(this, "", 12, R.color.text_muted);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 9);
        row.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        String level = event.optString("level", "info");
        background.setColor("alert".equals(level) ? 0x33FF5D6C : "warn".equals(level) ? 0x22FFB020 : 0xFF1A2340);
        background.setCornerRadius(Ui.dp(this, 10));
        row.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(params);
        TextView text = Ui.text(this, icon(event.optString("type", "")) + " " + event.optString("message", ""),
                14, R.color.text);
        row.addView(text);
        row.addView(Ui.text(this, Ui.ago(event.optLong("ts", 0)) + " • " + deviceName(event.optString("deviceId", "")),
                12, R.color.text_muted));
        return row;
    }

    private String deviceName(String id) {
        JSONArray devices = Ui.array(status, "devices");
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && id.equals(device.optString("id", ""))) {
                return device.optString("name", "");
            }
        }
        return "семья";
    }

    private static String icon(String type) {
        if ("sos".equals(type)) return "🆘";
        if ("geofence".equals(type)) return "📍";
        if ("web".equals(type)) return "🚫";
        if ("battery".equals(type)) return "🔋";
        if ("offline".equals(type)) return "📴";
        if ("notification".equals(type)) return "🔔";
        if ("app".equals(type)) return "📱";
        if ("camera".equals(type)) return "🤳";
        if ("limit".equals(type)) return "⏳";
        if ("cmd".equals(type)) return "🎮";
        if ("call".equals(type)) return "📞";
        return "•";
    }

    /* ------------------------------------------------------- экранное время */

    private void renderUsage(JSONObject device) {
        usageBox.removeAllViews();
        if (device == null) {
            return;
        }
        JSONObject usage = device.optJSONObject("usage");
        JSONArray list = usage == null ? new JSONArray() : Ui.array(usage, "list");
        long total = usage == null ? 0 : usage.optLong("totalMs", 0);
        usageBox.addView(Ui.text(this, "Всего сегодня: " + Ui.minutes(total), 15, R.color.text));
        JSONObject limits = Ui.object(Ui.object(device, "settings"), "appLimits");
        long max = 1;
        for (int i = 0; i < list.length(); i++) {
            max = Math.max(max, list.optJSONObject(i).optLong("ms", 0));
        }
        for (int i = 0; i < list.length() && i < 10; i++) {
            JSONObject row = list.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String pkg = row.optString("pkg", "");
            int limit = limits.optInt(pkg, 0);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            TextView label = Ui.text(this, row.optString("label", pkg), 14, R.color.text);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(label);
            line.addView(Ui.text(this, Ui.minutes(row.optLong("ms", 0)) + (limit > 0 ? " / " + limit + "м" : ""),
                    13, limit > 0 && row.optLong("ms", 0) >= limit * 60000L ? R.color.danger : R.color.text_muted));
            usageBox.addView(line);
        }
        if (list.length() == 0) {
            usageBox.addView(Ui.text(this, "Статистика появится после первого отчёта с телефона ребёнка",
                    13, R.color.text_muted));
        }
    }

    /* ---------------------------------------------------------- настройки */

    private void renderSettings(JSONObject device) {
        settingsBox.removeAllViews();
        if (device == null) {
            settingsBox.addView(Ui.text(this, "Нет устройства", 13, R.color.text_muted));
            return;
        }
        final JSONObject settings = Ui.object(device, "settings");
        final LinearLayout appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);

        JSONArray apps = Ui.array(device, "apps");
        final List<String> packages = new ArrayList<String>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) {
                continue;
            }
            packages.add(app.optString("pkg", ""));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            final CheckBox block = new CheckBox(this);
            block.setChecked(contains(Ui.array(settings, "blockApps"), app.optString("pkg", "")));
            final String pkg = app.optString("pkg", "");
            TextView label = Ui.text(this, app.optString("label", pkg), 14, R.color.text);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final EditText limit = new EditText(this);
            limit.setInputType(InputType.TYPE_CLASS_NUMBER);
            limit.setHint("мин");
            limit.setText(String.valueOf(Ui.integer(Ui.object(settings, "appLimits"), pkg, 0)));
            limit.setTextSize(13);
            limit.setWidth(Ui.dp(this, 64));
            row.addView(block);
            row.addView(label);
            row.addView(limit);
            appsBox.addView(row);
        }
        settingsBox.addView(Ui.text(this, "Галочка — запретить приложение. Число — лимит минут в день (0 — без лимита).",
                12, R.color.text_muted));
        ScrollView appScroll = new ScrollView(this);
        appScroll.addView(appsBox);
        appScroll.getLayoutParams().height = Ui.dp(this, 240);
        settingsBox.addView(appScroll);

        final EditText daily = new EditText(this);
        daily.setHint("Общий лимит экрана в день, минут (0 — выключен)");
        daily.setInputType(InputType.TYPE_CLASS_NUMBER);
        daily.setText(String.valueOf(settings.optInt("dailyLimitMin", 0)));
        settingsBox.addView(daily);

        JSONObject school = Ui.object(settings, "schoolMode");
        JSONObject bedtime = Ui.object(settings, "bedtime");
        final CheckBox schoolOn = new CheckBox(this);
        schoolOn.setText("Режим «Школа» " + Ui.str(school, "from", "08:00") + "–" + Ui.str(school, "to", "14:00"));
        schoolOn.setChecked(school.optBoolean("enabled", false));
        settingsBox.addView(schoolOn);
        final CheckBox bedtimeOn = new CheckBox(this);
        bedtimeOn.setText("Ночной режим " + Ui.str(bedtime, "from", "22:00") + "–" + Ui.str(bedtime, "to", "07:00"));
        bedtimeOn.setChecked(bedtime.optBoolean("enabled", false));
        settingsBox.addView(bedtimeOn);

        JSONObject internet = Ui.object(settings, "internetOff");
        final CheckBox internetOn = new CheckBox(this);
        internetOn.setText("Выключать интернет " + Ui.str(internet, "from", "23:00") + "–" + Ui.str(internet, "to", "06:00"));
        internetOn.setChecked(internet.optBoolean("enabled", false));
        settingsBox.addView(internetOn);

        JSONObject filter = Ui.object(settings, "webFilter");
        final CheckBox adult = new CheckBox(this);
        adult.setText("Блокировать взрослый контент (18+)");
        adult.setChecked(filter.optBoolean("adult", true));
        settingsBox.addView(adult);
        final CheckBox gambling = new CheckBox(this);
        gambling.setText("Блокировать азартные игры и казино");
        gambling.setChecked(filter.optBoolean("gambling", true));
        settingsBox.addView(gambling);

        final EditText custom = new EditText(this);
        custom.setHint("Свои запрещённые сайты через запятую");
        JSONArray customArray = Ui.array(filter, "custom");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < customArray.length(); i++) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(customArray.optString(i, ""));
        }
        custom.setText(builder.toString());
        settingsBox.addView(custom);

        final CheckBox voice = new CheckBox(this);
        voice.setText("Разрешить голосовые звонки");
        voice.setChecked(settings.optBoolean("voiceEnabled", true));
        settingsBox.addView(voice);

        Button save = new Button(this);
        save.setText("Сохранить и отправить на телефон ребёнка");
        save.setAllCaps(false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    JSONArray blockApps = new JSONArray();
                    JSONObject appLimits = new JSONObject();
                    int index = 0;
                    for (int i = 0; i < appsBox.getChildCount() && index < packages.size(); i++) {
                        View child = appsBox.getChildAt(i);
                        if (!(child instanceof LinearLayout)) {
                            continue;
                        }
                        LinearLayout row = (LinearLayout) child;
                        if (row.getChildCount() < 3) {
                            continue;
                        }
                        String pkg = packages.get(index++);
                        if (((CheckBox) row.getChildAt(0)).isChecked()) {
                            blockApps.put(pkg);
                        }
                        int minutes = 0;
                        try {
                            minutes = Integer.parseInt(((EditText) row.getChildAt(2)).getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                        if (minutes > 0) {
                            appLimits.put(pkg, minutes);
                        }
                    }
                    JSONObject body = new JSONObject();
                    JSONObject payload = new JSONObject();
                    payload.put("blockApps", blockApps);
                    payload.put("appLimits", appLimits);
                    payload.put("dailyLimitMin", parse(daily.getText().toString(), 0));
                    JSONObject schoolPayload = new JSONObject(school.toString());
                    schoolPayload.put("enabled", schoolOn.isChecked());
                    payload.put("schoolMode", schoolPayload);
                    JSONObject bedtimePayload = new JSONObject(bedtime.toString());
                    bedtimePayload.put("enabled", bedtimeOn.isChecked());
                    payload.put("bedtime", bedtimePayload);
                    JSONObject internetPayload = new JSONObject(internet.toString());
                    internetPayload.put("enabled", internetOn.isChecked());
                    payload.put("internetOff", internetPayload);
                    JSONObject filterPayload = new JSONObject();
                    filterPayload.put("adult", adult.isChecked());
                    filterPayload.put("gambling", gambling.isChecked());
                    JSONArray list = new JSONArray();
                    for (String item : custom.getText().toString().split(",")) {
                        String value = item.trim();
                        if (!value.isEmpty()) {
                            list.put(value);
                        }
                    }
                    filterPayload.put("custom", list);
                    payload.put("webFilter", filterPayload);
                    payload.put("voiceEnabled", voice.isChecked());
                    body.put("deviceId", selectedDevice().optString("id", ""));
                    body.put("settings", payload);
                    Http.post(ParentActivity.this, "/api/v1/settings", body);
                    Ui.toastLong(ParentActivity.this, "Настройки отправлены. Телефон ребёнка получит их в течение " +
                            "нескольких секунд, если он в сети.");
                    load(false);
                } catch (Exception e) {
                    Ui.toast(ParentActivity.this, "Не удалось сохранить: " + e.getMessage());
                }
            }
        });
        settingsBox.addView(save);
    }

    private static int parse(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    private View placeRow(final JSONObject place) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 9);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(params);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1A2340);
        background.setCornerRadius(Ui.dp(this, 10));
        row.setBackground(background);
        row.addView(Ui.text(this, "🏠 " + place.optString("name", "") + " • " + place.optInt("radius", 300) + " м",
                14, R.color.text));
        TextView remove = Ui.text(this, "Удалить", 12, R.color.accent);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Ui.bg(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("id", place.optString("id", ""));
                            Http.post(ParentActivity.this, "/api/v1/places/remove", body);
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    load(false);
                                }
                            });
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        });
        row.addView(remove);
        return row;
    }

    /* ----------------------------------------------------------------- карта */

    @Override
    public void onMapLongPress(final double lat, final double lon) {
        Ui.ui(new Runnable() {
            @Override
            public void run() {
                final EditText name = new EditText(ParentActivity.this);
                name.setHint("Название (Школа, Дом, Секция)");
                final EditText radius = new EditText(ParentActivity.this);
                radius.setHint("Радиус, метров");
                radius.setInputType(InputType.TYPE_CLASS_NUMBER);
                radius.setText("300");
                LinearLayout box = new LinearLayout(ParentActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(Ui.dp(ParentActivity.this, 16), Ui.dp(ParentActivity.this, 8),
                        Ui.dp(ParentActivity.this, 16), 0);
                box.addView(name);
                box.addView(radius);
                new android.app.AlertDialog.Builder(ParentActivity.this)
                        .setTitle("Новая геозона")
                        .setView(box)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Добавить", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                Ui.bg(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            JSONObject body = new JSONObject();
                                            body.put("name", name.getText().toString().isEmpty()
                                                    ? "Место" : name.getText().toString());
                                            body.put("lat", lat);
                                            body.put("lon", lon);
                                            body.put("radius", parse(radius.getText().toString(), 300));
                                            Http.post(ParentActivity.this, "/api/v1/places", body);
                                            Ui.ui(new Runnable() {
                                                @Override
                                                public void run() {
                                                    Ui.toast(ParentActivity.this, "Геозона добавлена");
                                                    load(false);
                                                }
                                            });
                                        } catch (Exception ignored) {
                                        }
                                    }
                                });
                            }
                        })
                        .show();
            }
        });
    }

    @Override
    public void onMarkerTap(String deviceId) {
        selectedId = deviceId;
        load(false);
    }

    /* ------------------------------------------------------------- действия */

    private void sendCommand(final String cmd, String label) {
        final JSONObject device = selectedDevice();
        if (device == null) {
            Ui.toast(this, "Сначала подключите телефон ребёнка");
            return;
        }
        if ("message".equals(cmd)) {
            showTextPrompt("Сообщение ребёнку", "Текст сообщения", new OnText() {
                @Override
                public void run(String value) {
                    command(cmd, value);
                }
            });
            return;
        }
        if ("call".equals(cmd)) {
            if (!WebRtc.available()) {
                Ui.confirm(this, "Голосовой звонок",
                        "Приложение собрано без модуля WebRTC, поэтому голосового вызова из приложения не будет. " +
                                "Отправить на телефон ребёнка громкий сигнал, чтобы он позвонил вам?", new Runnable() {
                            @Override
                            public void run() {
                                command("ring", null);
                            }
                        });
                return;
            }
            Intent intent = new Intent(this, CallActivity.class);
            intent.putExtra(CallActivity.EXTRA_ROLE, CallActivity.ROLE_PARENT);
            intent.putExtra(CallActivity.EXTRA_DEVICE_ID, device.optString("id", ""));
            startActivity(intent);
            return;
        }
        command(cmd, null);
    }

    private void command(String cmd, String arg) {
        final JSONObject device = selectedDevice();
        if (device == null) {
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("deviceId", device.optString("id", ""));
            body.put("cmd", cmd);
            if (arg != null) {
                body.put("arg", arg);
            }
            Http.post(this, "/api/v1/command", body);
            Ui.toast(this, "Команда отправлена");
        } catch (Exception e) {
            Ui.toast(this, "Не удалось отправить команду: " + e.getMessage());
        }
    }

    private interface OnText {
        void run(String value);
    }

    private void showTextPrompt(String title, String hint, final OnText action) {
        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        action.run(input.getText().toString());
                    }
                })
                .show();
    }

    private void renameDevice(final JSONObject device) {
        showTextPrompt("Название устройства", device.optString("name", ""), new OnText() {
            @Override
            public void run(String value) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("deviceId", device.optString("id", ""));
                    body.put("name", value);
                    Http.post(ParentActivity.this, "/api/v1/device/name", body);
                    load(false);
                } catch (Exception e) {
                    Ui.toast(ParentActivity.this, "Не удалось переименовать");
                }
            }
        });
    }

    private void showPairCode() {
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject data = Http.post(ParentActivity.this, "/api/v1/paircode", new JSONObject());
                    final String code = data.optString("code", "");
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            new android.app.AlertDialog.Builder(ParentActivity.this)
                                    .setTitle("Подключение телефона ребёнка")
                                    .setMessage("1. Установите приложение Modar Family на телефон ребёнка " +
                                            "(файл есть на сервере: " + Prefs.server(ParentActivity.this) + "/app/modar-family.apk)\n\n" +
                                            "2. Выберите режим «Я ребёнок».\n\n" +
                                            "3. Введите адрес сервера: " + Prefs.server(ParentActivity.this) + "\n\n" +
                                            "4. Введите код: " + code + "\n\n" +
                                            "Код действует 24 часа.")
                                    .setPositiveButton("Понятно", null)
                                    .show();
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(ParentActivity.this, "Не удалось получить код: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void askScreenshot() {
        final JSONObject device = selectedDevice();
        if (device == null) {
            Ui.toast(this, "Сначала подключите телефон ребёнка");
            return;
        }
        showTextPrompt("Снимок экрана", "Сколько секунд показывать экран (1–60)?", new OnText() {
            @Override
            public void run(String value) {
                int seconds = Math.max(1, Math.min(60, parse(value, 1)));
                command(seconds > 2 ? "tour" : "snapshot", String.valueOf(seconds));
                loadScreenDelayed();
            }
        });
    }

    private void loadScreenDelayed() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                loadScreen();
            }
        }, 6000);
    }

    /** Показывает последний снимок экрана / фото ребёнка. */
    private void loadScreen() {
        final JSONObject device = selectedDevice();
        if (device == null) {
            return;
        }
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject image = Http.get(ParentActivity.this, "/api/v1/image/latest?deviceId="
                            + device.optString("id", "") + "&kind=screen").optJSONObject("image");
                    if (image == null || image.isNull("data")) {
                        return;
                    }
                    String data = image.optString("data", "");
                    int comma = data.indexOf(',');
                    if (comma < 0) {
                        return;
                    }
                    final byte[] bytes = Base64.decode(data.substring(comma + 1), Base64.DEFAULT);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap != null) {
                                screenView.setImageBitmap(bitmap);
                                Ui.setVisible(screenView, true);
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }
}
