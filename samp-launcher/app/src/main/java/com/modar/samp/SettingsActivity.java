package com.modar.samp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

/** Настройки: сервер лаунчера, клиент, ник, параметры передачи адреса и каталоги. */
public class SettingsActivity extends Activity {

    private EditText api;
    private EditText client;
    private EditText activity;
    private EditText nickname;
    private EditText serverPassword;
    private EditText extraIp;
    private EditText extraPort;
    private EditText extraNick;
    private EditText extraPass;
    private EditText gameDir;
    private EditText dataDir;
    private EditText timeout;
    private CheckBox sendArgs;
    private CheckBox copyClipboard;
    private CheckBox autoRefresh;
    private CheckBox backupMods;
    private TextView apiStatus;
    private TextView clientLabel;

    private final Prefs prefs = new Prefs(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        api = (EditText) findViewById(R.id.set_api);
        client = (EditText) findViewById(R.id.set_client);
        activity = (EditText) findViewById(R.id.set_activity);
        nickname = (EditText) findViewById(R.id.set_nickname);
        serverPassword = (EditText) findViewById(R.id.set_server_password);
        extraIp = (EditText) findViewById(R.id.set_extra_ip);
        extraPort = (EditText) findViewById(R.id.set_extra_port);
        extraNick = (EditText) findViewById(R.id.set_extra_nick);
        extraPass = (EditText) findViewById(R.id.set_extra_pass);
        gameDir = (EditText) findViewById(R.id.set_game_dir);
        dataDir = (EditText) findViewById(R.id.set_data_dir);
        timeout = (EditText) findViewById(R.id.set_timeout);
        sendArgs = (CheckBox) findViewById(R.id.set_send_args);
        copyClipboard = (CheckBox) findViewById(R.id.set_copy_clipboard);
        autoRefresh = (CheckBox) findViewById(R.id.set_auto_refresh);
        backupMods = (CheckBox) findViewById(R.id.set_backup_mods);
        apiStatus = (TextView) findViewById(R.id.set_api_status);
        clientLabel = (TextView) findViewById(R.id.set_client_label);
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER);

        fill();

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btn_check_api).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setApiUrl(api.getText().toString());
                checkApi();
            }
        });
        findViewById(R.id.btn_pick_client).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClientPicker.show(SettingsActivity.this, new ClientPicker.Callback() {
                    @Override
                    public void onPicked(String pkg, String act, String label) {
                        client.setText(pkg);
                        if (act != null && !act.isEmpty()) {
                            activity.setText(act);
                        }
                        clientLabel.setText("Выбрано: " + (label.isEmpty() ? pkg : label));
                    }
                });
            }
        });
        findViewById(R.id.btn_storage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Storage.request(SettingsActivity.this, null);
            }
        });
        findViewById(R.id.btn_help).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp();
            }
        });
        findViewById(R.id.btn_about).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ((TextView) findViewById(R.id.set_storage_status)).setText(Storage.status(this));
    }

    private void fill() {
        api.setText(prefs.apiUrl());
        client.setText(prefs.clientPackage());
        activity.setText(prefs.clientActivity());
        nickname.setText(prefs.nickname());
        serverPassword.setText(prefs.serverPassword());
        extraIp.setText(prefs.extraIpKey());
        extraPort.setText(prefs.extraPortKey());
        extraNick.setText(prefs.extraNickKey());
        extraPass.setText(prefs.extraPassKey());
        gameDir.setText(prefs.gameDir());
        dataDir.setText(prefs.dataDir());
        timeout.setText(String.valueOf(prefs.queryTimeout()));
        sendArgs.setChecked(prefs.sendArgsExtra());
        copyClipboard.setChecked(prefs.copyToClipboard());
        autoRefresh.setChecked(prefs.autoRefresh());
        backupMods.setChecked(prefs.backupMods());
        String label = ClientLauncher.label(this, prefs.clientPackage());
        clientLabel.setText(prefs.clientPackage().isEmpty()
                ? "Клиент не выбран"
                : "Выбрано: " + (label.isEmpty() ? prefs.clientPackage() : label));
        apiStatus.setText(Api.configured(this) ? "Адрес задан: " + prefs.apiUrl() : "Адрес не задан — списки, моды и новости подключатся после его указания");
    }

    private void save() {
        prefs.setApiUrl(api.getText().toString());
        prefs.setClientPackage(client.getText().toString());
        prefs.setClientActivity(activity.getText().toString());
        prefs.setNickname(nickname.getText().toString());
        prefs.setServerPassword(serverPassword.getText().toString());
        prefs.setExtraIpKey(extraIp.getText().toString());
        prefs.setExtraPortKey(extraPort.getText().toString());
        prefs.setExtraNickKey(extraNick.getText().toString());
        prefs.setExtraPassKey(extraPass.getText().toString());
        prefs.setGameDir(gameDir.getText().toString());
        prefs.setDataDir(dataDir.getText().toString());
        try {
            prefs.setQueryTimeout(Integer.parseInt(timeout.getText().toString().trim()));
        } catch (Throwable ignored) {
        }
        prefs.setSendArgsExtra(sendArgs.isChecked());
        prefs.setCopyToClipboard(copyClipboard.isChecked());
        prefs.setAutoRefresh(autoRefresh.isChecked());
        prefs.setBackupMods(backupMods.isChecked());
        Ui.toast(this, "Настройки сохранены");
        fill();
    }

    private void checkApi() {
        apiStatus.setText("Проверяем связь…");
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final org.json.JSONObject json = Api.ping(prefs.apiUrl());
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            String version = json.optString("version", "?");
                            int servers = json.optInt("servers", -1);
                            int mods = json.optInt("mods", -1);
                            apiStatus.setText("Связь есть. Сервер лаунчера версии " + version
                                    + (servers >= 0 ? ", серверов в списке: " + servers : "")
                                    + (mods >= 0 ? ", мод-паков: " + mods : ""));
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            apiStatus.setText("Нет связи: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Как подключиться к серверу")
                .setMessage(
                        "1. Установите клиент SA-MP на телефон (любую сборку — например, SA-MP Mobile).\n\n"
                        + "2. Здесь, в настройках, нажмите «Выбрать установленный клиент» и укажите его в списке.\n\n"
                        + "3. Задайте ник и, если нужно, пароль сервера по умолчанию.\n\n"
                        + "4. Вернитесь на главный экран, добавьте сервер кнопкой «+» (ip:порт) "
                        + "и нажмите на него — лаунчер запустит клиент и передаст адрес.\n\n"
                        + "Если сборка не читает extras (ключи «ip»/«port» указаны выше), адрес всё равно "
                        + "скопируется в буфер — вставьте его в меню «Добавить сервер» внутри игры долгим нажатием.\n\n"
                        + "Мод-паки: укажите адрес своего сервера лаунчера — список модов, новости и "
                        + "обновления придут оттуда.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Modar SAMP")
                .setMessage("Лаунчер SA-MP для Android.\n\n"
                        + "• список серверов с живым опросом (протокол SA-MP Query, UDP);\n"
                        + "• запуск установленного клиента с адресом, ником и паролем;\n"
                        + "• мод-паки с сервера: установка, проверка SHA-256, резервная копия, откат;\n"
                        + "• новости и обновления с вашего сервера.\n\n"
                        + "Приложение не содержит рекламы, аналитики и трекеров. Все данные — "
                        + "только на устройстве и на вашем сервере.\n\n"
                        + "Исходный код: samp-launcher/ в репозитории modar35/modar35.")
                .setPositiveButton("Закрыть", null)
                .show();
    }
}
