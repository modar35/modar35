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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        orders = findViewById(R.id.orders_content);
        trips = findViewById(R.id.trips_content);
        profile = findViewById(R.id.profile_content);

        findViewById(R.id.tab_orders).setOnClickListener(v -> showTab(0));
        findViewById(R.id.tab_trips).setOnClickListener(v -> showTab(1));
        findViewById(R.id.tab_profile).setOnClickListener(v -> showTab(2));
        findViewById(R.id.btn_filter).setOnClickListener(v -> showFilter());
        findViewById(R.id.btn_create).setOnClickListener(v -> createOrder());
        findViewById(R.id.btn_add_trip).setOnClickListener(v -> Toast.makeText(this, "Раздел «Мои рейсы» готовится к подключению", Toast.LENGTH_SHORT).show());
        findViewById(R.id.btn_support).setOnClickListener(v -> Toast.makeText(this, "Поддержка: ответим в течение 5 минут", Toast.LENGTH_SHORT).show());
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
                    Toast.makeText(this, "Заявка опубликована. Ищем машину рядом", Toast.LENGTH_LONG).show();
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true);
        e.setPadding(0, 12, 0, 4); return e;
    }
}
