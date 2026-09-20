package com.modar.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Настройки: ключ API, адрес сервера, модель, системный промпт, голос. */
public class SettingsActivity extends Activity {

    private static final String[] PRESET_MODELS = new String[]{
            "gpt-6-astra",
            "gpt-5.6",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5",
            "gpt-4o",
            "o3",
            "anthropic/claude-sonnet-4.5",
            "google/gemini-2.5-pro",
            "meta-llama/llama-3.3-70b-instruct"
    };

    private Prefs prefs;
    private EditText keyField;
    private EditText urlField;
    private EditText modelField;
    private EditText systemField;
    private EditText tokensField;
    private EditText localeField;
    private SeekBar tempSeek;
    private TextView tempLabel;
    private Switch ttsSwitch;
    private Switch streamSwitch;

    private OpenAiClient client = new OpenAiClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        prefs.seedFromAssetsIfEmpty();

        keyField = (EditText) findViewById(R.id.set_key);
        urlField = (EditText) findViewById(R.id.set_url);
        modelField = (EditText) findViewById(R.id.set_model);
        systemField = (EditText) findViewById(R.id.set_system);
        tokensField = (EditText) findViewById(R.id.set_tokens);
        localeField = (EditText) findViewById(R.id.set_locale);
        tempSeek = (SeekBar) findViewById(R.id.set_temp);
        tempLabel = (TextView) findViewById(R.id.set_temp_label);
        ttsSwitch = (Switch) findViewById(R.id.set_tts);
        streamSwitch = (Switch) findViewById(R.id.set_stream);

        keyField.setText(prefs.apiKey());
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        urlField.setText(prefs.baseUrl());
        modelField.setText(prefs.model());
        systemField.setText(prefs.systemPrompt());
        tokensField.setText(String.valueOf(prefs.maxTokens()));
        localeField.setText(prefs.speechLocale());
        ttsSwitch.setChecked(prefs.ttsEnabled());
        streamSwitch.setChecked(prefs.streaming());

        tempSeek.setMax(15);
        tempSeek.setProgress(Math.round(prefs.temperature() * 10f));
        updateTempLabel(tempSeek.getProgress());
        tempSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTempLabel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        findViewById(R.id.set_models).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelChooser();
            }
        });

        findViewById(R.id.set_paste).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });

        findViewById(R.id.set_toggle_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleKeyVisibility();
            }
        });

        findViewById(R.id.set_help).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyHelp();
            }
        });

        findViewById(R.id.set_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });

        findViewById(R.id.set_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        findViewById(R.id.set_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        findViewById(R.id.set_save2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    private void updateTempLabel(int progress) {
        tempLabel.setText(String.format(java.util.Locale.US, "%.1f", progress / 10f));
    }

    /** Вставка ключа из буфера обмена — чтобы не набирать вручную. */
    private void pasteFromClipboard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                toast("Буфер обмена пуст");
                return;
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (text == null || text.toString().trim().isEmpty()) {
                toast("Буфер обмена пуст");
                return;
            }
            String value = text.toString().trim().replace("\n", "").replace("\r", "");
            keyField.setText(value);
            keyField.setSelection(keyField.getText().length());
            toast(value.length() > 12
                    ? "Ключ вставлен: " + value.substring(0, 6) + "…" + value.substring(value.length() - 4)
                    : "Ключ вставлен");
        } catch (Throwable t) {
            toast("Не удалось вставить: " + t.getMessage());
        }
    }

    private void toggleKeyVisibility() {
        boolean hidden = (keyField.getInputType()
                & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
        if (hidden) {
            keyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            keyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        keyField.setSelection(keyField.getText().length());
        ((android.widget.Button) findViewById(R.id.set_toggle_key)).setText(hidden ? "Скрыть" : "Показать");
    }

    /** Подсказка: где взять ключ и как обойтись без него. */
    private void showKeyHelp() {
        showResult("Где взять ключ API",
                "1. Ключ создаётся в личном кабинете вашего ИИ-сервиса, например на platform.openai.com → "
                + "API keys → Create new secret key. Ключ показывается один раз — сразу скопируйте его.\n\n"
                + "2. Скопированный ключ вставьте сюда кнопкой «Вставить».\n\n"
                + "3. Можно работать вообще без ключа, если модель запущена на вашем компьютере "
                + "(Ollama, LM Studio, llama.cpp): укажите в «Адрес сервера» адрес вида "
                + "http://192.168.1.50:11434/v1 — поле ключа оставьте пустым.\n\n"
                + "Ключ хранится только на этом устройстве и никуда, кроме указанного сервера, не отправляется.");
    }

    private void save() {
        prefs.setApiKey(keyField.getText().toString());
        prefs.setBaseUrl(urlField.getText().toString());
        prefs.setModel(modelField.getText().toString());
        prefs.setSystemPrompt(systemField.getText().toString());
        int tokens = 0;
        try {
            tokens = Integer.parseInt(tokensField.getText().toString().trim());
        } catch (Throwable ignored) {
        }
        prefs.setMaxTokens(tokens);
        prefs.setTemperature(tempSeek.getProgress() / 10f);
        prefs.setTtsEnabled(ttsSwitch.isChecked());
        prefs.setStreaming(streamSwitch.isChecked());
        prefs.setSpeechLocale(localeField.getText().toString());
        setResult(RESULT_OK);
        toast("Сохранено");
        finish();
    }

    private void showModelChooser() {
        final List<String> models = new ArrayList<String>();
        for (int i = 0; i < PRESET_MODELS.length; i++) {
            models.add(PRESET_MODELS[i]);
        }
        models.add("⤓ Загрузить список с сервера…");
        new AlertDialog.Builder(this)
                .setTitle("Выберите модель")
                .setItems(models.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == models.size() - 1) {
                            loadModelsFromServer();
                        } else {
                            modelField.setText(models.get(which));
                            toast("Модель: " + models.get(which));
                        }
                    }
                })
                .show();
    }

    private void loadModelsFromServer() {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Запрашиваю список моделей…");
        progress.setCancelable(false);
        progress.show();
        client.listModels(urlField.getText().toString().trim(), keyField.getText().toString().trim(),
                new OpenAiClient.ModelsCallback() {
                    @Override
                    public void onModels(List<String> models, String error) {
                        dismiss(progress);
                        if (error != null) {
                            showResult("Не удалось получить список", error);
                            return;
                        }
                        if (models == null || models.isEmpty()) {
                            showResult("Пусто", "Сервер не вернул ни одной модели.");
                            return;
                        }
                        final List<String> list = models;
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("Модели сервера (" + list.size() + ")")
                                .setItems(list.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        modelField.setText(list.get(which));
                                    }
                                })
                                .show();
                    }
                });
    }

    private void testConnection() {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Проверяю соединение…");
        progress.setCancelable(false);
        progress.show();
        client.listModels(urlField.getText().toString().trim(), keyField.getText().toString().trim(),
                new OpenAiClient.ModelsCallback() {
                    @Override
                    public void onModels(List<String> models, String error) {
                        dismiss(progress);
                        if (error != null) {
                            showResult("Ошибка соединения", error);
                        } else {
                            showResult("Соединение работает",
                                    "Сервер ответил. Доступно моделей: " + (models == null ? 0 : models.size())
                                            + ".\nВыбранная модель: " + modelField.getText().toString().trim());
                        }
                    }
                });
    }

    private void dismiss(ProgressDialog p) {
        try {
            if (p != null && p.isShowing()) {
                p.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private void showResult(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (client != null) {
            client.cancel();
        }
    }
}
