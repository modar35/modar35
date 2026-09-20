package com.modar.family;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Первый экран: выбор режима и настройка связи с сервером.
 *
 * Режим «Родитель» — вход в аккаунт семьи (создаётся здесь же) и панель управления.
 * Режим «Ребёнок» — телефон привязывается кодом, который показывает родитель,
 * и начинает отправлять отчёты.
 */
public class MainActivity extends Activity {

    private EditText serverField;
    private EditText emailField;
    private EditText passwordField;
    private EditText nameField;
    private EditText codeField;
    private EditText childNameField;
    private LinearLayout parentBox;
    private LinearLayout childBox;
    private TextView statusView;
    private Button parentTab;
    private Button childTab;
    private boolean parentMode = true;
    private boolean registerMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverField = (EditText) findViewById(R.id.serverField);
        emailField = (EditText) findViewById(R.id.emailField);
        passwordField = (EditText) findViewById(R.id.passwordField);
        nameField = (EditText) findViewById(R.id.nameField);
        codeField = (EditText) findViewById(R.id.codeField);
        childNameField = (EditText) findViewById(R.id.childNameField);
        parentBox = (LinearLayout) findViewById(R.id.parentBox);
        childBox = (LinearLayout) findViewById(R.id.childBox);
        statusView = (TextView) findViewById(R.id.setupStatus);
        parentTab = (Button) findViewById(R.id.tabParent);
        childTab = (Button) findViewById(R.id.tabChild);

        serverField.setText(Prefs.server(this));
        nameField.setText(Prefs.get(this, "parent_name", ""));
        emailField.setText(Prefs.parentEmail(this));

        parentTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                parentMode = true;
                updateTabs();
            }
        });
        childTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                parentMode = false;
                updateTabs();
            }
        });
        findViewById(R.id.checkServer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                checkServer();
            }
        });
        findViewById(R.id.loginButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                login();
            }
        });
        findViewById(R.id.registerButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                register();
            }
        });
        findViewById(R.id.connectButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectChild();
            }
        });
        findViewById(R.id.enterButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openConfigured();
            }
        });

        // Если APK собран с предзаполнением (MODAR_SERVER / MODAR_CODE) — заполняем поля сами
        applyBaked();

        // Если приложение уже настроено — пускаем сразу в нужный экран
        if (Prefs.configured(this)) {
            ((TextView) findViewById(R.id.setupTitle)).setText(
                    Prefs.isParent(this) ? "Режим родителя" : "Режим ребёнка");
            statusView.setText(Prefs.isParent(this)
                    ? "Сервер: " + Prefs.server(this) + " • семья: " + Prefs.familyName(this)
                    : "Сервер: " + Prefs.server(this) + " • устройство: " + Prefs.childName(this));
            Ui.setVisible(findViewById(R.id.enterButton), true);
            parentMode = Prefs.isParent(this);
            updateTabs();
        } else {
            Ui.setVisible(findViewById(R.id.enterButton), false);
        }
    }

    /** Читает значения, вшитые при сборке APK (assets/modar_*.txt). */
    private void applyBaked() {
        if (Prefs.configured(this)) {
            return;
        }
        String server = asset("modar_server.txt");
        String code = asset("modar_code.txt");
        String email = asset("modar_email.txt");
        String password = asset("modar_password.txt");
        if (!server.isEmpty()) {
            serverField.setText(server);
        }
        if (!email.isEmpty()) {
            emailField.setText(email);
            parentMode = true;
            updateTabs();
        }
        if (!password.isEmpty()) {
            passwordField.setText(password);
        }
        if (!code.isEmpty()) {
            codeField.setText(code);
            parentMode = false;
            updateTabs();
            if (!server.isEmpty()) {
                // Полностью готовый APK для телефона ребёнка: подключаемся без ввода
                statusView.setText("Подключаем телефон по вшитому коду…");
                connectChild();
            }
        }
    }

    private String asset(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void updateTabs() {
        parentTab.setSelected(parentMode);
        childTab.setSelected(!parentMode);
        parentTab.setTextColor(parentMode ? 0xFFFFFFFF : 0xFF93A0BF);
        childTab.setTextColor(parentMode ? 0xFF93A0BF : 0xFFFFFFFF);
        parentTab.setBackgroundColor(parentMode ? 0xFF6C8CFF : 0x00000000);
        childTab.setBackgroundColor(parentMode ? 0x00000000 : 0xFF6C8CFF);
        Ui.setVisible(parentBox, parentMode);
        Ui.setVisible(childBox, !parentMode);
        ((TextView) findViewById(R.id.setupHint)).setText(parentMode
                ? "Аккаунт родителя создаётся за 5 секунд: e-mail и пароль. Тот же вход работает в веб-панели " +
                  "на компьютере — просто откройте адрес сервера в браузере."
                : "Возьмите код у родителя (кнопка «＋ Подключить телефон» в его приложении или веб-панели) " +
                  "и введите его здесь. После подключения телефон начнёт отправлять отчёты.");
    }

    /* --------------------------------------------------------------- сервер */

    private void checkServer() {
        final String server = serverField.getText().toString().trim();
        if (server.isEmpty()) {
            Ui.toast(this, "Введите адрес сервера");
            return;
        }
        statusView.setText("Проверяем сервер…");
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject ping = Http.ping(server);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            Prefs.setServer(MainActivity.this, server);
                            statusView.setText("✅ Сервер отвечает (версия " + ping.optString("version", "?") + ")");
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText("❌ Сервер недоступен: " + e.getMessage() +
                                    "\nПроверьте адрес, интернет и что сервер запущен.");
                        }
                    });
                }
            }
        });
    }

    /* ------------------------------------------------------------- родитель */

    private void login() {
        final String server = serverField.getText().toString().trim();
        final String email = emailField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        if (server.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Ui.toast(this, "Заполните адрес сервера, e-mail и пароль");
            return;
        }
        statusView.setText("Входим…");
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs.setServer(MainActivity.this, server);
                    Prefs.setParentToken(MainActivity.this, "");
                    Prefs.setMode(MainActivity.this, Prefs.MODE_PARENT);
                    JSONObject body = new JSONObject();
                    body.put("email", email);
                    body.put("password", password);
                    final JSONObject data = Http.post(MainActivity.this, "/api/v1/auth/login", body);
                    Prefs.setParentToken(MainActivity.this, data.optString("token", ""));
                    Prefs.setParentEmail(MainActivity.this, email);
                    Prefs.setFamilyName(MainActivity.this, Ui.object(data, "family").optString("name", ""));
                    Prefs.setMode(MainActivity.this, Prefs.MODE_PARENT);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(MainActivity.this, "Добро пожаловать!");
                            startActivity(new Intent(MainActivity.this, ParentActivity.class));
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText("Не удалось войти: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void register() {
        final String server = serverField.getText().toString().trim();
        final String email = emailField.getText().toString().trim();
        final String password = passwordField.getText().toString();
        final String name = nameField.getText().toString().trim();
        if (server.isEmpty() || email.isEmpty() || password.length() < 6) {
            Ui.toast(this, "Нужны сервер, e-mail и пароль не короче 6 символов");
            return;
        }
        statusView.setText("Создаём семью…");
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs.setServer(MainActivity.this, server);
                    Prefs.setParentToken(MainActivity.this, "");
                    Prefs.setMode(MainActivity.this, Prefs.MODE_PARENT);
                    JSONObject body = new JSONObject();
                    body.put("email", email);
                    body.put("password", password);
                    body.put("name", name);
                    body.put("familyName", name.isEmpty() ? "Наша семья" : "Семья " + name);
                    final JSONObject data = Http.post(MainActivity.this, "/api/v1/auth/register", body);
                    Prefs.setParentToken(MainActivity.this, data.optString("token", ""));
                    Prefs.setParentEmail(MainActivity.this, email);
                    Prefs.put(MainActivity.this, "parent_name", name);
                    Prefs.setFamilyName(MainActivity.this, Ui.object(data, "family").optString("name", ""));
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toastLong(MainActivity.this, "Семья создана. Теперь подключите телефон ребёнка.");
                            startActivity(new Intent(MainActivity.this, ParentActivity.class));
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText("Не удалось создать семью: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /* -------------------------------------------------------------- ребёнок */

    private void connectChild() {
        final String server = serverField.getText().toString().trim();
        final String code = codeField.getText().toString().trim();
        String childName = childNameField.getText().toString().trim();
        if (server.isEmpty() || code.isEmpty()) {
            Ui.toast(this, "Нужны адрес сервера и код от родителя");
            return;
        }
        if (childName.isEmpty()) {
            childName = Prefs.deviceInfo(this);
        }
        final String name = childName;
        statusView.setText("Подключаем телефон…");
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    Prefs.setServer(MainActivity.this, server);
                    Prefs.setDeviceToken(MainActivity.this, "");
                    Prefs.setMode(MainActivity.this, Prefs.MODE_CHILD);
                    JSONObject body = new JSONObject();
                    body.put("code", code);
                    body.put("name", name);
                    body.put("model", Prefs.deviceInfo(MainActivity.this));
                    body.put("androidId", Build.MANUFACTURER + "-" + Build.MODEL);
                    body.put("version", ChildService.BuildConfigVersion.VERSION_NAME);
                    JSONObject data = Http.post(MainActivity.this, "/api/v1/device/register", body);
                    Prefs.setDeviceToken(MainActivity.this, data.optString("deviceToken", ""));
                    Prefs.setDeviceId(MainActivity.this, data.optString("deviceId", ""));
                    Prefs.setChildName(MainActivity.this, name);
                    Prefs.setFamilyName(MainActivity.this, data.optString("familyName", ""));
                    Prefs.setFlag(MainActivity.this, "accessibility", false);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            ChildService.start(MainActivity.this);
                            requestChildPermissions();
                            Ui.toastLong(MainActivity.this, "Телефон подключён! Выдайте разрешения — без них " +
                                    "родитель не увидит геолокацию и приложения.");
                            startActivity(new Intent(MainActivity.this, ChildActivity.class));
                            finish();
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText("Не удалось подключиться: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void requestChildPermissions() {
        java.util.List<String> permissions = new java.util.ArrayList<String>();
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            permissions.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 10);
        }
    }

    /* ----------------------------------------------------------- переходы */

    private void openConfigured() {
        if (Prefs.isParent(this)) {
            startActivity(new Intent(this, ParentActivity.class));
        } else {
            ChildService.start(this);
            startActivity(new Intent(this, ChildActivity.class));
        }
        finish();
    }
}
