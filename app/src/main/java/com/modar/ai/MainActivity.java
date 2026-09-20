package com.modar.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Главный экран: чат с ИИ-ассистентом. */
public class MainActivity extends Activity {

    private static final int REQ_SETTINGS = 11;
    private static final int REQ_CHATS = 12;
    private static final int REQ_PERM_MIC = 13;
    private static final int REQ_PICK_IMAGE = 14;

    private Prefs prefs;
    private Store store;
    private Conversation conv;
    private ChatAdapter adapter;
    private OpenAiClient client;
    private Tts tts;
    private VoiceInput voice;

    private ListView list;
    private EditText input;
    private ImageButton sendBtn;
    private ImageButton micBtn;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private View welcomeView;
    private View attachBar;
    private ImageView attachThumb;
    private TextView attachLabel;

    private String pendingImagePath;
    private boolean busy;
    private boolean nearBottom = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        prefs.seedFromAssetsIfEmpty();
        store = new Store(this);
        conv = store.current();
        client = new OpenAiClient();
        tts = new Tts(this, prefs.speechLocale());
        tts.setEnabled(prefs.ttsEnabled());

        list = (ListView) findViewById(R.id.messages);
        input = (EditText) findViewById(R.id.input);
        sendBtn = (ImageButton) findViewById(R.id.btn_send);
        micBtn = (ImageButton) findViewById(R.id.btn_mic);
        headerTitle = (TextView) findViewById(R.id.header_title);
        headerSubtitle = (TextView) findViewById(R.id.header_subtitle);
        welcomeView = findViewById(R.id.welcome);
        attachBar = findViewById(R.id.attach_bar);
        attachThumb = (ImageView) findViewById(R.id.attach_thumb);
        attachLabel = (TextView) findViewById(R.id.attach_label);

        adapter = new ChatAdapter(this, conv.messages);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);

        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                nearBottom = totalItemCount == 0 || (firstVisibleItem + visibleItemCount) >= totalItemCount - 1;
            }
        });

        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                showMessageMenu(conv.messages.get(position));
                return true;
            }
        });

        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    stopGenerating();
                } else {
                    sendMessage();
                }
            }
        });

        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVoice();
            }
        });

        findViewById(R.id.btn_attach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });

        findViewById(R.id.btn_new).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newConversation();
            }
        });

        findViewById(R.id.btn_chats).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, ChatsActivity.class), REQ_CHATS);
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQ_SETTINGS);
            }
        });

        findViewById(R.id.attach_remove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAttachment();
            }
        });

        int[] chipIds = new int[]{R.id.chip1, R.id.chip2, R.id.chip3, R.id.chip4};
        String[] chipTexts = new String[]{
                "Объясни простыми словами, как работает нейросеть",
                "Составь план тренировок на неделю",
                "Помоги написать резюме",
                "Переведи текст на английский"
        };
        for (int i = 0; i < chipIds.length; i++) {
            final String text = chipTexts[i];
            final TextView chip = (TextView) findViewById(chipIds[i]);
            chip.setText(text);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    input.setText(text);
                    input.setSelection(input.getText().length());
                    sendMessage();
                }
            });
        }

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sendBtn.setAlpha(s.toString().trim().isEmpty() && pendingImagePath == null ? 0.45f : 1f);
            }
        });

        updateHeader();
        updateWelcome();
        scrollToBottom();

        if (!prefs.isConfigured()) {
            promptForApiKey();
        }
    }

    // ------------------------------------------------------------- сообщения

    private void sendMessage() {
        if (busy) {
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingImagePath == null) {
            return;
        }
        if (!prefs.isConfigured()) {
            promptForApiKey();
            return;
        }

        Message user = new Message(Message.ROLE_USER, text);
        user.imagePath = pendingImagePath;
        conv.messages.add(user);
        conv.autoTitle();

        final Message answer = new Message(Message.ROLE_ASSISTANT, "");
        answer.streaming = true;
        conv.messages.add(answer);

        input.setText("");
        clearAttachment();
        adapter.refresh();
        updateWelcome();
        setBusy(true);
        scrollToBottom();
        hideKeyboard();

        OpenAiClient.Request req = new OpenAiClient.Request();
        req.baseUrl = prefs.baseUrl();
        req.apiKey = prefs.apiKey();
        req.model = prefs.model();
        req.systemPrompt = prefs.systemPrompt();
        req.temperature = prefs.temperature();
        req.maxTokens = prefs.maxTokens();
        req.stream = prefs.streaming();
        req.messages = new ArrayList<Message>(conv.messages);

        tts.stop();

        client.send(req, new OpenAiClient.Callback() {
            @Override
            public void onDelta(String delta) {
                answer.text = answer.text + delta;
                answer.streaming = true;
                adapter.refresh();
                scrollToBottom();
            }

            @Override
            public void onDone(String full) {
                answer.streaming = false;
                if (answer.text.trim().isEmpty()) {
                    answer.text = "(пустой ответ)";
                }
                if (full != null && !full.isEmpty()) {
                    answer.text = full;
                }
                finishTurn(answer);
                if (prefs.ttsEnabled() && !answer.error) {
                    tts.speak(answer.text);
                }
            }

            @Override
            public void onError(String message) {
                answer.streaming = false;
                answer.error = true;
                answer.skipForModel = true;
                answer.text = message;
                finishTurn(answer);
            }
        });
    }

    private void finishTurn(Message answer) {
        setBusy(false);
        conv.updatedAt = System.currentTimeMillis();
        conv.autoTitle();
        store.save(conv);
        adapter.refresh();
        updateHeader();
        scrollToBottom();
    }

    private void stopGenerating() {
        client.cancel();
        for (int i = conv.messages.size() - 1; i >= 0; i--) {
            Message m = conv.messages.get(i);
            if (m.streaming) {
                m.streaming = false;
                if (m.text == null || m.text.trim().isEmpty()) {
                    m.text = "(остановлено)";
                    m.skipForModel = true;
                } else {
                    m.text = m.text + "\n\n(остановлено)";
                }
                break;
            }
        }
        setBusy(false);
        store.save(conv);
        adapter.refresh();
    }

    private void setBusy(boolean value) {
        busy = value;
        sendBtn.setImageResource(value ? R.drawable.ic_stop : R.drawable.ic_send);
        sendBtn.setContentDescription(getString(value ? R.string.stop : R.string.send));
        sendBtn.setAlpha(1f);
    }

    private void newConversation() {
        if (busy) {
            stopGenerating();
        }
        conv = store.create();
        adapter = new ChatAdapter(this, conv.messages);
        list.setAdapter(adapter);
        updateHeader();
        updateWelcome();
        scrollToBottom();
    }

    private void showMessageMenu(final Message m) {
        final List<String> actions = new ArrayList<String>();
        actions.add("Копировать текст");
        if (m.isUser() && !busy) {
            actions.add("Повторить этот запрос");
        }
        if (!m.isUser() && !busy) {
            actions.add("Озвучить");
        }
        actions.add("Удалить сообщение");

        new AlertDialog.Builder(this)
                .setTitle(m.isUser() ? "Ваше сообщение" : "Ответ ассистента")
                .setItems(actions.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String action = actions.get(which);
                        if ("Копировать текст".equals(action)) {
                            copyToClipboard(m.text);
                        } else if ("Повторить этот запрос".equals(action)) {
                            input.setText(m.text);
                            input.setSelection(input.getText().length());
                            sendMessage();
                        } else if ("Озвучить".equals(action)) {
                            boolean was = tts.isEnabled();
                            tts.setEnabled(true);
                            tts.speak(m.text);
                            tts.setEnabled(was);
                        } else if ("Удалить сообщение".equals(action)) {
                            conv.messages.remove(m);
                            store.save(conv);
                            adapter.refresh();
                            updateWelcome();
                        }
                    }
                })
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("modar-ai", text == null ? "" : text));
                toast("Скопировано");
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- голос

    private void toggleVoice() {
        if (voice == null) {
            voice = new VoiceInput(this, prefs.speechLocale());
        }
        if (voice.isListening()) {
            voice.stop();
            return;
        }
        if (!VoiceInput.isAvailable(this)) {
            toast("На устройстве нет распознавания речи");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM_MIC);
            return;
        }
        startListening();
    }

    private void startListening() {
        if (voice == null) {
            voice = new VoiceInput(this, prefs.speechLocale());
        }
        final String previous = input.getText().toString();
        voice.start(new VoiceInput.Listener() {
            @Override
            public void onPartial(String text) {
                input.setText(text);
                input.setSelection(input.getText().length());
            }

            @Override
            public void onFinal(String text) {
                String base = previous.trim();
                input.setText(base.isEmpty() ? text : base + " " + text);
                input.setSelection(input.getText().length());
            }

            @Override
            public void onStateChanged(boolean listening) {
                micBtn.setBackgroundResource(listening ? R.drawable.btn_circle_active : R.drawable.btn_circle);
                micBtn.setColorFilter(listening ? Ui.color(MainActivity.this, R.color.bg) : Ui.color(MainActivity.this, R.color.text_muted));
            }

            @Override
            public void onError(String message) {
                toast(message);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                toast("Без разрешения микрофон недоступен");
            }
        }
    }

    // ------------------------------------------------------------ вложения

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Выберите изображение"), REQ_PICK_IMAGE);
        } catch (Throwable t) {
            toast("Не найдено приложение для выбора изображений");
        }
    }

    private void clearAttachment() {
        pendingImagePath = null;
        attachBar.setVisibility(View.GONE);
        attachThumb.setImageDrawable(null);
    }

    private void attachImage(Uri uri) {
        try {
            File dir = new File(getFilesDir(), "images");
            if (!dir.exists() && !dir.mkdirs()) {
                toast("Не удалось сохранить изображение");
                return;
            }
            File out = new File(dir, "img_" + System.currentTimeMillis() + ".jpg");
            InputStream boundsIn = getContentResolver().openInputStream(uri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(boundsIn, null, bounds);
            if (boundsIn != null) {
                boundsIn.close();
            }
            int sample = 1;
            int side = Math.max(bounds.outWidth, bounds.outHeight);
            while (side / sample > 1600) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            InputStream in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
            if (in != null) {
                in.close();
            }
            if (bmp == null) {
                toast("Не удалось прочитать изображение");
                return;
            }
            OutputStream os = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, os);
            os.close();
            bmp.recycle();

            pendingImagePath = out.getAbsolutePath();
            attachThumb.setImageBitmap(Ui.thumbnail(this, pendingImagePath, 256));
            attachLabel.setText("Изображение прикреплено");
            attachBar.setVisibility(View.VISIBLE);
            sendBtn.setAlpha(1f);
        } catch (Throwable t) {
            toast("Ошибка вложения: " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            attachImage(data.getData());
        } else if (requestCode == REQ_SETTINGS) {
            refreshAfterSettings();
        } else if (requestCode == REQ_CHATS && resultCode == RESULT_OK) {
            conv = store.current();
            adapter = new ChatAdapter(this, conv.messages);
            list.setAdapter(adapter);
            updateHeader();
            updateWelcome();
            scrollToBottom();
        }
    }

    private void refreshAfterSettings() {
        tts.setEnabled(prefs.ttsEnabled());
        tts.setLocale(prefs.speechLocale());
        if (voice != null) {
            voice.destroy();
            voice = null;
        }
        updateHeader();
        adapter.refresh();
    }

    // -------------------------------------------------------------- прочее

    private void updateHeader() {
        String title = conv.title == null || conv.title.trim().isEmpty() ? getString(R.string.app_name) : conv.title;
        headerTitle.setText(title);
        headerSubtitle.setText(prefs.model() + " · " + shortHost(prefs.baseUrl()));
    }

    private static String shortHost(String url) {
        try {
            String s = url.replace("https://", "").replace("http://", "");
            int slash = s.indexOf('/');
            return slash > 0 ? s.substring(0, slash) : s;
        } catch (Throwable t) {
            return url;
        }
    }

    private void updateWelcome() {
        welcomeView.setVisibility(conv.messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void scrollToBottom() {
        if (!nearBottom) {
            return;
        }
        list.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getCount() > 0) {
                    list.setSelection(adapter.getCount() - 1);
                }
            }
        });
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void promptForApiKey() {
        new AlertDialog.Builder(this)
                .setTitle("Нужен API-ключ")
                .setMessage("Вставьте ключ OpenAI (или другого OpenAI-совместимого сервиса) в настройках. "
                        + "Ключ хранится только на этом устройстве и отправляется напрямую в выбранный сервис.")
                .setPositiveButton("Открыть настройки", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQ_SETTINGS);
                    }
                })
                .setNegativeButton("Позже", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            stopGenerating();
            toast("Генерация остановлена");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (client != null) {
            client.cancel();
        }
        if (tts != null) {
            tts.shutdown();
        }
        if (voice != null) {
            voice.destroy();
        }
    }
}
