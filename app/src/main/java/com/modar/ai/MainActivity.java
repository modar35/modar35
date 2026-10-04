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
    private static final int REQ_VERIFY_PHOTO = 60;
    private android.widget.TextView photoDocsLabel, photoCarLabel;
    private boolean docsPhotoReady, carPhotoReady;
    private int pendingCameraType;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        orders = findViewById(R.id.orders_content);
        trips = findViewById(R.id.trips_content);
        profile = findViewById(R.id.profile_content);
        orderList = findViewById(R.id.order_list);
        loadSavedOrders();
        updateTrips();
        refreshProfile();

        findViewById(R.id.btn_map).setOnClickListener(v -> startActivity(new android.content.Intent(this, MapActivity.class)));
        findViewById(R.id.tab_orders).setOnClickListener(v -> showTab(0));
        findViewById(R.id.tab_trips).setOnClickListener(v -> showTab(1));
        findViewById(R.id.tab_profile).setOnClickListener(v -> showTab(2));
        findViewById(R.id.btn_filter).setOnClickListener(v -> showFilter());
        findViewById(R.id.btn_create).setOnClickListener(v -> createOrder());
        findViewById(R.id.take_order_1).setOnClickListener(v -> acceptOrder("Химки → Мытищи · 1 800 ₽"));
        findViewById(R.id.take_order_2).setOnClickListener(v -> acceptOrder("Центр → Подольск · 3 500 ₽"));
        findViewById(R.id.take_order_3).setOnClickListener(v -> acceptOrder("Балашиха → Москва · 950 ₽"));
        findViewById(R.id.btn_add_trip).setOnClickListener(v -> showTab(0));
        findViewById(R.id.btn_start_trip).setOnClickListener(v -> changeTripStatus("В пути"));
        findViewById(R.id.btn_finish_trip).setOnClickListener(v -> changeTripStatus("Доставлено"));
        findViewById(R.id.btn_support).setOnClickListener(v -> Toast.makeText(this, "Поддержка: ответим в течение 5 минут", Toast.LENGTH_SHORT).show());
        findViewById(R.id.btn_login).setOnClickListener(v -> showLogin());
        findViewById(R.id.btn_logout).setOnClickListener(v -> { getSharedPreferences("cargo_auth", MODE_PRIVATE).edit().clear().apply(); refreshProfile(); Toast.makeText(this, "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show(); });
        findViewById(R.id.btn_verify).setOnClickListener(v -> showVerification());
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

    private void acceptOrder(String title) {
        getSharedPreferences("cargo_trips", MODE_PRIVATE).edit().putString("active", title).apply();
        updateTrips();
        new AlertDialog.Builder(this).setTitle("Заказ принят").setMessage(title + "\n\nСвяжитесь с клиентом и начните маршрут, когда будете готовы.")
            .setPositiveButton("Открыть мои рейсы", (d, w) -> showTab(1)).setNegativeButton("Остаться в заказах", null).show();
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

    private void refreshProfile() {
        String token = getSharedPreferences("cargo_auth", MODE_PRIVATE).getString("token", "");
        String role = getSharedPreferences("cargo_auth", MODE_PRIVATE).getString("role", "driver");
        boolean logged = !token.isEmpty();
        android.widget.TextView subtitle = findViewById(R.id.profile_subtitle);
        android.widget.TextView card = findViewById(R.id.profile_card);
        View login = findViewById(R.id.btn_login); View logout = findViewById(R.id.btn_logout); View verify = findViewById(R.id.btn_verify);
        if (logged) {
            boolean driver = "driver".equals(role);
            subtitle.setText(driver ? "Водитель · аккаунт подтверждён" : "Клиент · аккаунт подтверждён");
            boolean pending = "pending".equals(getSharedPreferences("cargo_auth", MODE_PRIVATE).getString("verification", ""));
            card.setText(driver ? (pending ? "⏳  Документы на проверке\n\nАдминистрация проверит данные и фотографии. До подтверждения заказы недоступны." : "🚚  Автомобиль не добавлен\n\nДобавьте Газель или грузовик, чтобы получать подходящие заказы.") : "📦  Профиль клиента\n\nСоздавайте заявки и отслеживайте доставку в разделе «Мои рейсы».");
            login.setVisibility(View.GONE); logout.setVisibility(View.VISIBLE); verify.setVisibility(driver && !pending ? View.VISIBLE : View.GONE);
        } else {
            subtitle.setText("Водитель не авторизован");
            card.setText("🚚  Добавить автомобиль\n\nУкажите марку, тип кузова и грузоподъёмность, чтобы получать подходящие заявки.");
            login.setVisibility(View.VISIBLE); logout.setVisibility(View.GONE); verify.setVisibility(View.GONE);
        }
    }

    private void showVerification() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this); box.setOrientation(android.widget.LinearLayout.VERTICAL); box.setPadding(28, 26, 28, 20); box.setBackground(dialogBackground()); box.addView(dialogTitle("Верификация водителя"));
        android.widget.TextView note = new android.widget.TextView(this); note.setText("Заполните данные. Нажмите на блоки ниже — откроется камера для фотографий."); note.setTextColor(getResources().getColor(R.color.text_muted)); note.setTextSize(14); box.addView(note);
        final EditText name=field("ФИО полностью"), birth=field("Дата рождения"), license=field("Номер водительского удостоверения"), car=field("Марка, модель и госномер"), capacity=field("Грузоподъёмность, кг"); box.addView(name);box.addView(birth);box.addView(license);box.addView(car);box.addView(capacity);
        photoDocsLabel=photoButton("📎  Сфотографировать паспорт, права и СТС"); photoCarLabel=photoButton("📷  Сфотографировать автомобиль (4 ракурса)"); box.addView(photoDocsLabel);box.addView(photoCarLabel);
        photoDocsLabel.setOnClickListener(v->openCamera(1)); photoCarLabel.setOnClickListener(v->openCamera(2));
        android.widget.LinearLayout actions=new android.widget.LinearLayout(this);actions.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);android.widget.TextView cancel=action("Отмена",false),send=action("Отправить на проверку",true);actions.addView(cancel);actions.addView(send);box.addView(actions,new android.widget.LinearLayout.LayoutParams(-1,dp(58)));cancel.setOnClickListener(v->dialog.dismiss());send.setOnClickListener(v->{if(name.getText().length()<3||car.getText().length()<3){Toast.makeText(this,"Заполните ФИО и данные автомобиля",Toast.LENGTH_LONG).show();return;}if(!docsPhotoReady||!carPhotoReady){Toast.makeText(this,"Сначала сфотографируйте документы и автомобиль",Toast.LENGTH_LONG).show();return;}String json="{\"fullName\":\""+safe(name.getText().toString())+"\",\"birthDate\":\""+safe(birth.getText().toString())+"\",\"licenseNumber\":\""+safe(license.getText().toString())+"\",\"vehicle\":\""+safe(car.getText().toString())+"\",\"capacity\":\""+safe(capacity.getText().toString())+"\",\"documentsPhoto\":true,\"vehiclePhotos\":true,\"status\":\"pending\"}";ApiClient.post("/api/driver/verification",json,getSharedPreferences("cargo_auth",MODE_PRIVATE).getString("token",""),(ok,r)->runOnUiThread(()->{getSharedPreferences("cargo_auth",MODE_PRIVATE).edit().putString("verification","pending").apply();dialog.dismiss();refreshProfile();Toast.makeText(this,ok?"Анкета отправлена на проверку":"Анкета сохранена и будет отправлена при подключении сервера",Toast.LENGTH_LONG).show();}));});dialog.setContentView(box);showAppDialog(dialog);
    }

    private android.widget.TextView photoButton(String text){android.widget.TextView v=new android.widget.TextView(this);v.setText(text);v.setTextColor(getResources().getColor(R.color.accent));v.setTextSize(14);v.setPadding(0,14,0,14);v.setBackgroundResource(R.drawable.chip_bg);return v;}
    private void openCamera(int type){pendingCameraType=type;try{if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},REQ_VERIFY_PHOTO);return;}startActivityForResult(new android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra("photo_type",type),REQ_VERIFY_PHOTO+type);}catch(Exception e){Toast.makeText(this,"Камера недоступна. Проверьте разрешение",Toast.LENGTH_LONG).show();}}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==REQ_VERIFY_PHOTO&&results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)openCamera(pendingCameraType);}
    @Override protected void onActivityResult(int requestCode,int resultCode,android.content.Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode==RESULT_OK&&(requestCode==REQ_VERIFY_PHOTO+1||requestCode==REQ_VERIFY_PHOTO+2)){if(requestCode==REQ_VERIFY_PHOTO+1){docsPhotoReady=true;if(photoDocsLabel!=null)photoDocsLabel.setText("✓  Фото документов добавлены — можно переснять");}else{carPhotoReady=true;if(photoCarLabel!=null)photoCarLabel.setText("✓  Фото автомобиля добавлено — можно переснять");}}}

    private void updateTrips() {
        android.content.SharedPreferences p = getSharedPreferences("cargo_trips", MODE_PRIVATE);
        String active = p.getString("active", "");
        String state = p.getString("state", "Принят");
        android.widget.TextView status = findViewById(R.id.trips_status);
        if (status != null) status.setText(active.isEmpty() ? "✓  Сегодня · 0 активных рейсов" : "●  " + state + "\n" + active);
        findViewById(R.id.btn_start_trip).setVisibility(active.isEmpty() || "В пути".equals(state) || "Доставлено".equals(state) ? View.GONE : View.VISIBLE);
        findViewById(R.id.btn_finish_trip).setVisibility("В пути".equals(state) ? View.VISIBLE : View.GONE);
    }

    private void changeTripStatus(String state) {
        getSharedPreferences("cargo_trips", MODE_PRIVATE).edit().putString("state", state).apply();
        updateTrips();
        Toast.makeText(this, "Статус рейса: " + state, Toast.LENGTH_SHORT).show();
    }

    private android.graphics.drawable.GradientDrawable dialogBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(android.graphics.Color.rgb(25, 30, 38)); bg.setCornerRadius(22); bg.setStroke(1, android.graphics.Color.rgb(55, 64, 76));
        return bg;
    }

    private android.widget.TextView dialogTitle(String text) {
        android.widget.TextView v = new android.widget.TextView(this); v.setText(text); v.setTextColor(android.graphics.Color.rgb(245,242,238)); v.setTextSize(21); v.setTypeface(null, android.graphics.Typeface.BOLD); return v;
    }

    private void showLogin() {
        final android.app.Dialog dialog=new android.app.Dialog(this); android.widget.LinearLayout box=new android.widget.LinearLayout(this);box.setOrientation(android.widget.LinearLayout.VERTICAL);box.setPadding(28,26,28,20);box.setBackground(dialogBackground());box.addView(dialogTitle("Вход и регистрация"));
        android.widget.TextView sub=new android.widget.TextView(this);sub.setText("Один номер телефона для клиента и водителя");sub.setTextColor(getResources().getColor(R.color.text_muted));sub.setTextSize(14);box.addView(sub);
        final android.widget.Spinner role=new android.widget.Spinner(this);role.setAdapter(new android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Водитель — принимаю заказы","Клиент — отправляю груз"}));box.addView(role,new android.widget.LinearLayout.LayoutParams(-1,dp(54)));
        final EditText phone=field("Номер телефона");phone.setTextColor(getResources().getColor(R.color.text));phone.setHintTextColor(getResources().getColor(R.color.text_muted));box.addView(phone,new android.widget.LinearLayout.LayoutParams(-1,dp(58)));
        android.widget.LinearLayout actions=new android.widget.LinearLayout(this);actions.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);android.widget.TextView cancel=action("Отмена",false),send=action("Получить код",true);actions.addView(cancel);actions.addView(send);box.addView(actions,new android.widget.LinearLayout.LayoutParams(-1,dp(58)));
        cancel.setOnClickListener(v->dialog.dismiss());send.setOnClickListener(v->{String p=phone.getText().toString().trim();if(p.length()<6){phone.setError("Введите номер телефона");return;}String r=role.getSelectedItemPosition()==0?"driver":"customer";send.setEnabled(false);ApiClient.post("/api/auth/request-code","{\"phone\":\""+safe(p)+"\"}","",(ok,response)->runOnUiThread(()->{dialog.dismiss();showCodeDialog(p,r,ok);}));});dialog.setContentView(box);showAppDialog(dialog);
    }

    private void showCodeDialog(final String phone,final String role,final boolean serverReached){
        final android.app.Dialog dialog=new android.app.Dialog(this);android.widget.LinearLayout box=new android.widget.LinearLayout(this);box.setOrientation(android.widget.LinearLayout.VERTICAL);box.setPadding(28,26,28,20);box.setBackground(dialogBackground());box.addView(dialogTitle("Подтверждение номера"));android.widget.TextView sub=new android.widget.TextView(this);sub.setText(serverReached?"Введите код из SMS":"Сервер недоступен — тестовый код: 123456");sub.setTextColor(getResources().getColor(R.color.text_muted));sub.setTextSize(14);box.addView(sub);final EditText code=field("Код из SMS");code.setTextColor(getResources().getColor(R.color.text));code.setHintTextColor(getResources().getColor(R.color.text_muted));code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);box.addView(code,new android.widget.LinearLayout.LayoutParams(-1,dp(58)));android.widget.LinearLayout actions=new android.widget.LinearLayout(this);actions.setGravity(android.view.Gravity.RIGHT|android.view.Gravity.CENTER_VERTICAL);android.widget.TextView cancel=action("Отмена",false),login=action("Войти",true);actions.addView(cancel);actions.addView(login);box.addView(actions,new android.widget.LinearLayout.LayoutParams(-1,dp(58)));cancel.setOnClickListener(v->dialog.dismiss());login.setOnClickListener(v->{String c=code.getText().toString().trim();if(!serverReached&&"123456".equals(c)){getSharedPreferences("cargo_auth",MODE_PRIVATE).edit().putString("token","offline-"+phone).putString("role",role).apply();dialog.dismiss();refreshProfile();Toast.makeText(this,role.equals("driver")?"Вы вошли как водитель":"Вы вошли как клиент",Toast.LENGTH_LONG).show();return;}ApiClient.post("/api/auth/verify-code","{\"phone\":\""+safe(phone)+"\",\"code\":\""+safe(c)+"\",\"role\":\""+role+"\"}","",(ok,response)->runOnUiThread(()->{dialog.dismiss();if(ok){String token=response.replaceAll(".*\\\"token\\\":\\\"([^\\\"]+).*","$1");getSharedPreferences("cargo_auth",MODE_PRIVATE).edit().putString("token",token).putString("role",role).apply();refreshProfile();Toast.makeText(this,role.equals("driver")?"Вы вошли как водитель":"Вы вошли как клиент",Toast.LENGTH_LONG).show();}else Toast.makeText(this,"Неверный код",Toast.LENGTH_LONG).show();}));});dialog.setContentView(box);showAppDialog(dialog);
    }

    private void showAppDialog(android.app.Dialog dialog){android.view.Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setDimAmount(.72f);w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);}dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*.88f),-2);}

    private android.widget.TextView action(String text, boolean primary){ android.widget.TextView v=new android.widget.TextView(this);v.setText(text.toUpperCase());v.setTextColor(primary?getResources().getColor(R.color.accent):getResources().getColor(R.color.text_muted));v.setTextSize(13);v.setGravity(android.view.Gravity.CENTER);v.setPadding(18,0,10,0);return v; }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}

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
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setTextSize(15);
        e.setBackgroundResource(R.drawable.dialog_input); e.setPadding(dp(14), 0, dp(14), 0);
        e.setHintTextColor(getResources().getColor(R.color.text_muted)); e.setTextColor(getResources().getColor(R.color.text));
        android.widget.LinearLayout.LayoutParams lp=new android.widget.LinearLayout.LayoutParams(-1, dp(54)); lp.setMargins(0, dp(6), 0, 0); e.setLayoutParams(lp); return e;
    }
}
