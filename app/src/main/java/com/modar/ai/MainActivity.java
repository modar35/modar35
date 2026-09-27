package com.modar.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

/** Главный экран «Грузовичок»: заказы для газелей и грузовых автомобилей. */
public class MainActivity extends Activity {
    private View orders, trips, profile;
    private android.widget.LinearLayout orderList;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        orders = findViewById(R.id.orders_content);
        trips = findViewById(R.id.trips_content);
        profile = findViewById(R.id.profile_content);
        orderList = findViewById(R.id.order_list);
        loadSavedOrders();

        findViewById(R.id.tab_orders).setOnClickListener(v -> showTab(0));
        findViewById(R.id.tab_trips).setOnClickListener(v -> showTab(1));
        findViewById(R.id.tab_profile).setOnClickListener(v -> showTab(2));
        findViewById(R.id.btn_filter).setOnClickListener(v -> showFilter());
        findViewById(R.id.btn_create).setOnClickListener(v -> createOrder());
        findViewById(R.id.btn_add_trip).setOnClickListener(v -> Toast.makeText(this, "Раздел «Мои рейсы» готовится к подключению", Toast.LENGTH_SHORT).show());
        findViewById(R.id.btn_support).setOnClickListener(v -> Toast.makeText(this, "Поддержка: ответим в течение 5 минут", Toast.LENGTH_SHORT).show());
        findViewById(R.id.btn_login).setOnClickListener(v -> showLogin());
        showTab(0);
    }

    private void showTab(int tab) {
        orders.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        trips.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        profile.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.tab_orders).setSelected(tab == 0);
        findViewById(R.id.tab_trips).setSelected(tab == 1);
        findViewById(R.id.tab_profile).setSelected(tab == 2);
    }

    private void showFilter() {
        String[] choices = {"Все заказы", "Газель тент", "Газель термо", "Грузовик до 5 т", "Грузовик до 10 т"};
        new AlertDialog.Builder(this).setTitle("Тип кузова").setSingleChoiceItems(choices, 0, (d, which) -> {
            Toast.makeText(this, choices[which] + " — фильтр применён", Toast.LENGTH_SHORT).show(); d.dismiss();
        }).setNegativeButton("Отмена", null).show();
    }

    private void createOrder() {
        final EditText from = field("Откуда, например: Химки");
        final EditText to = field("Куда, например: Мытищи");
        final EditText cargo = field("Что везём, например: мебель");
        final EditText weight = field("Вес груза, кг"); weight.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final EditText date = field("Дата и время загрузки");
        final EditText price = field("Предложенная стоимость, ₽"); price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final android.widget.Spinner vehicle = new android.widget.Spinner(this);
        vehicle.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Газель тент", "Газель термо", "Грузовик до 5 т", "Грузовик до 10 т"}));
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL); box.setPadding(48, 0, 48, 0);
        box.addView(from); box.addView(to); box.addView(cargo); box.addView(weight); box.addView(vehicle); box.addView(date); box.addView(price);
        new AlertDialog.Builder(this).setTitle("Новая заявка")
            .setMessage("Заполните маршрут — подходящие водители увидят заказ сразу")
            .setView(box).setPositiveButton("Опубликовать", (d, w) -> {
                if (from.getText().toString().trim().isEmpty() || to.getText().toString().trim().isEmpty()) {
                    Toast.makeText(this, "Укажите адреса загрузки и выгрузки", Toast.LENGTH_LONG).show();
                } else {
                    String record = from.getText().toString().trim() + "\u001f" + to.getText().toString().trim() + "\u001f" + cargo.getText().toString().trim() + "\u001f" + weight.getText().toString().trim() + "\u001f" + price.getText().toString().trim() + "\u001f" + vehicle.getSelectedItem().toString();
                    saveOrder(record);
                    String json = "{\"from\":\"" + safe(from.getText().toString()) + "\",\"to\":\"" + safe(to.getText().toString()) + "\",\"cargo\":\"" + safe(cargo.getText().toString()) + "\",\"weight\":\"" + safe(weight.getText().toString()) + "\",\"vehicle\":\"" + safe(vehicle.getSelectedItem().toString()) + "\",\"price\":\"" + safe(price.getText().toString()) + "\"}";
                    ApiClient.post("/api/orders", json, getSharedPreferences("cargo_auth", MODE_PRIVATE).getString("token", ""), (ok, ignored) -> {});
                    addOrderCard(from.getText().toString().trim(), to.getText().toString().trim(), cargo.getText().toString().trim(), weight.getText().toString().trim(), price.getText().toString().trim(), vehicle.getSelectedItem().toString());
                    Toast.makeText(this, "Заявка опубликована. Ищем машину рядом", Toast.LENGTH_LONG).show();
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void showLogin() {
        final EditText phone = field("Номер телефона");
        new AlertDialog.Builder(this).setTitle("Вход в Грузовичок").setMessage("Отправим код подтверждения по SMS")
            .setView(phone).setPositiveButton("Получить код", (d, w) -> {
                String p = phone.getText().toString().trim();
                ApiClient.post("/api/auth/request-code", "{\"phone\":\"" + p + "\"}", "", (ok, response) -> runOnUiThread(() -> {
                    if (ok) showCodeDialog(p); else Toast.makeText(this, "Сервер недоступен. Проверьте адрес API", Toast.LENGTH_LONG).show();
                }));
            }).setNegativeButton("Отмена", null).show();
    }

    private void showCodeDialog(final String phone) {
        final EditText code = field("Код из SMS");
        new AlertDialog.Builder(this).setTitle("Подтверждение номера").setView(code)
            .setPositiveButton("Войти", (d, w) -> ApiClient.post("/api/auth/verify-code", "{\"phone\":\"" + phone + "\",\"code\":\"" + code.getText().toString().trim() + "\",\"role\":\"driver\"}", "", (ok, response) -> runOnUiThread(() -> {
                if (ok) { String token = response.replaceAll(".*\\\"token\\\":\\\"([^\\\"]+).*", "$1"); getSharedPreferences("cargo_auth", MODE_PRIVATE).edit().putString("token", token).apply(); Toast.makeText(this, "Вы вошли как водитель", Toast.LENGTH_LONG).show(); }
                else Toast.makeText(this, "Неверный код или сервер недоступен", Toast.LENGTH_LONG).show();
            }))).setNegativeButton("Отмена", null).show();
    }

    private String safe(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    private void saveOrder(String record) {
        android.content.SharedPreferences p = getSharedPreferences("cargo_orders", MODE_PRIVATE);
        String old = p.getString("items", "");
        p.edit().putString("items", old.isEmpty() ? record : old + "\u001e" + record).apply();
    }

    private void loadSavedOrders() {
        String all = getSharedPreferences("cargo_orders", MODE_PRIVATE).getString("items", "");
        if (all.isEmpty()) return;
        for (String record : all.split("\\u001e")) {
            String[] x = record.split("\\u001f", -1);
            if (x.length == 6) addOrderCard(x[0], x[1], x[2], x[3], x[4], x[5]);
        }
    }

    private void addOrderCard(String from, String to, String cargo, String weight, String price, String vehicle) {
        android.widget.TextView card = new android.widget.TextView(this);
        String load = cargo.isEmpty() ? "Груз" : cargo;
        String mass = weight.isEmpty() ? "вес не указан" : weight + " кг";
        String cost = price.isEmpty() ? "Цена договорная" : price + " ₽";
        card.setText("Новая заявка  ·  только что\n" + from + "  →  " + to + "\n" + load + " · " + mass + "\n" + vehicle + " · " + cost + "\n\nОжидает откликов водителей");
        card.setTextColor(getResources().getColor(R.color.text)); card.setTextSize(14); card.setPadding(28, 24, 28, 24);
        card.setBackgroundResource(R.drawable.card_bg);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 16, 0, 0);
        orderList.addView(card, 0, lp);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true);
        e.setPadding(0, 12, 0, 4); return e;
    }
}
