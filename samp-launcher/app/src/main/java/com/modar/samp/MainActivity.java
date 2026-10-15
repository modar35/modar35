package com.modar.samp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Главный экран: список серверов, опрос онлайна и запуск клиента с выбранным адресом. */
public class MainActivity extends Activity {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_FAVORITE = 1;
    private static final int FILTER_ONLINE = 2;

    private ListView list;
    private ServerAdapter adapter;
    private TextView empty;
    private TextView status;
    private TextView chipAll;
    private TextView chipFavorite;
    private TextView chipOnline;
    private EditText search;
    private LinearLayout banner;
    private TextView bannerTitle;
    private TextView bannerText;
    private View refreshButton;

    private final List<Server> servers = new ArrayList<Server>();
    private int filter = FILTER_ALL;
    private boolean refreshing;
    private News.Update pendingUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        new Prefs(this).seedFromAssetsIfEmpty();

        list = (ListView) findViewById(R.id.servers);
        empty = (TextView) findViewById(R.id.servers_empty);
        status = (TextView) findViewById(R.id.main_status);
        chipAll = (TextView) findViewById(R.id.chip_all);
        chipFavorite = (TextView) findViewById(R.id.chip_favorite);
        chipOnline = (TextView) findViewById(R.id.chip_online);
        search = (EditText) findViewById(R.id.main_search);
        banner = (LinearLayout) findViewById(R.id.main_banner);
        bannerTitle = (TextView) findViewById(R.id.banner_title);
        bannerText = (TextView) findViewById(R.id.banner_text);
        refreshButton = findViewById(R.id.btn_refresh);

        adapter = new ServerAdapter(this);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                connect(adapter.getItem(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                serverMenu(adapter.getItem(position));
                return true;
            }
        });

        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                serverDialog(null);
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshAll();
            }
        });
        findViewById(R.id.btn_mods).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ModsActivity.class));
            }
        });
        findViewById(R.id.btn_news).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NewsActivity.class));
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.banner_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                banner.setVisibility(View.GONE);
            }
        });
        banner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NewsActivity.class));
            }
        });

        chipAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(FILTER_ALL);
            }
        });
        chipFavorite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(FILTER_FAVORITE);
            }
        });
        chipOnline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFilter(FILTER_ONLINE);
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadServers();
        render();
        if (new Prefs(this).autoRefresh()) {
            refreshAll();
        }
        fetchApi();
    }

    /* ------------------------------------------------------------------ список */

    private void loadServers() {
        servers.clear();
        servers.addAll(Servers.all(this));
    }

    private void setFilter(int value) {
        filter = value;
        render();
    }

    private List<Server> visible() {
        List<Server> result = new ArrayList<Server>();
        for (Server server : servers) {
            if (filter == FILTER_FAVORITE && !server.favorite) {
                continue;
            }
            if (filter == FILTER_ONLINE && !(server.last != null && !server.offline)) {
                continue;
            }
            result.add(server);
        }
        return Servers.filter(result, search.getText().toString());
    }

    private void render() {
        List<Server> shown = visible();
        adapter.setItems(shown);

        chipAll.setText("Все · " + servers.size());
        int favorites = 0;
        int online = 0;
        for (Server server : servers) {
            if (server.favorite) {
                favorites++;
            }
            if (server.last != null && !server.offline) {
                online++;
            }
        }
        chipFavorite.setText("★ " + favorites);
        chipOnline.setText("Онлайн · " + online);
        chipAll.setBackgroundResource(filter == FILTER_ALL ? R.drawable.chip_bg_active : R.drawable.chip_bg);
        chipFavorite.setBackgroundResource(filter == FILTER_FAVORITE ? R.drawable.chip_bg_active : R.drawable.chip_bg);
        chipOnline.setBackgroundResource(filter == FILTER_ONLINE ? R.drawable.chip_bg_active : R.drawable.chip_bg);

        if (!refreshing) {
            status.setText(servers.isEmpty()
                    ? "Список пуст — добавьте сервер кнопкой «+»"
                    : online + " из " + servers.size() + " онлайн" + (filter == FILTER_ALL ? "" : " · показано " + shown.size()));
        }
        if (servers.isEmpty()) {
            empty.setText("Пока нет ни одного сервера.\n\n«+» — добавить по адресу,\nили укажите сервер лаунчера в настройках — список придёт оттуда.");
        } else if (shown.isEmpty()) {
            empty.setText("Ничего не найдено");
        }
    }

    /* ------------------------------------------------------------------ опрос */

    private void refreshAll() {
        if (refreshing) {
            return;
        }
        if (servers.isEmpty()) {
            Ui.toast(this, "Сначала добавьте сервер");
            return;
        }
        refreshing = true;
        final List<Server> snapshot = new ArrayList<Server>(servers);
        final AtomicInteger done = new AtomicInteger();
        final int total = snapshot.size();
        status.setText("Опрос серверов: 0/" + total + "…");
        refreshButton.setAlpha(0.4f);

        final Prefs prefs = new Prefs(this);
        for (final Server server : snapshot) {
            Ui.bg(new Runnable() {
                @Override
                public void run() {
                    long started = System.currentTimeMillis();
                    try {
                        SampQuery.Info info = SampQuery.info(server.host, server.port, prefs.queryTimeout());
                        info.rtt = (int) (System.currentTimeMillis() - started);
                        server.last = info;
                        server.offline = false;
                    } catch (Throwable t) {
                        server.offline = true;
                    }
                    final int finished = done.incrementAndGet();
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("Опрос серверов: " + finished + "/" + total + "…");
                            if (finished >= total) {
                                refreshing = false;
                                refreshButton.setAlpha(1f);
                                Servers.save(MainActivity.this, servers);
                                render();
                            } else {
                                adapter.notifyDataSetChanged();
                            }
                        }
                    });
                }
            });
        }
    }

    private void queryOne(final Server server) {
        final Prefs prefs = new Prefs(this);
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                long started = System.currentTimeMillis();
                try {
                    SampQuery.Info info = SampQuery.info(server.host, server.port, prefs.queryTimeout());
                    info.rtt = (int) (System.currentTimeMillis() - started);
                    server.last = info;
                    server.offline = false;
                } catch (Throwable t) {
                    server.offline = true;
                }
                Ui.ui(new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                });
            }
        });
    }

    /* ------------------------------------------------------------------ список с сервера лаунчера */

    private void fetchApi() {
        if (!Api.configured(this)) {
            return;
        }
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Server> remote = Api.servers(MainActivity.this);
                    Servers.mergeApi(MainActivity.this, remote);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            loadServers();
                            render();
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                try {
                    final List<News> news = Api.news(MainActivity.this);
                    final News.Update update = Api.update(MainActivity.this);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            showBanner(news, update);
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        });
    }

    private void showBanner(List<News> news, News.Update update) {
        pendingUpdate = update;
        String title = null;
        String text = null;
        if (update != null && update.isNewerThan(BuildConfigVersion.CODE)) {
            title = "Доступна версия лаунчера " + update.version;
            text = update.notes.isEmpty() ? "Нажмите, чтобы обновить" : update.notes;
        } else if (news != null && !news.isEmpty()) {
            News first = news.get(0);
            title = first.title;
            text = first.text.length() > 140 ? first.text.substring(0, 140) + "…" : first.text;
        }
        if (title == null) {
            banner.setVisibility(View.GONE);
            return;
        }
        bannerTitle.setText(title);
        bannerText.setText(text);
        banner.setVisibility(View.VISIBLE);
    }

    /* ------------------------------------------------------------------ подключение */

    private void connect(final Server server) {
        final Prefs prefs = new Prefs(this);
        if (prefs.clientPackage().isEmpty()) {
            Ui.toast(this, "Выберите клиент SA-MP — куда передавать адрес");
            ClientPicker.show(this, new ClientPicker.Callback() {
                @Override
                public void onPicked(String pkg, String activity, String label) {
                    prefs.setClientPackage(pkg);
                    if (activity != null && !activity.isEmpty()) {
                        prefs.setClientActivity(activity);
                    }
                    connect(server);
                }
            });
            return;
        }
        if (server.last == null && !server.offline) {
            queryOne(server);
        }
        final ClientLauncher.Result result = ClientLauncher.connect(this, server);
        new AlertDialog.Builder(this)
                .setTitle(result.ok ? "Подключение" : "Не получилось запустить клиент")
                .setMessage("Сервер: " + server.title() + "\n" + server.address() + "\n\n" + result.message)
                .setNegativeButton("Другой клиент", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClientPicker.show(MainActivity.this, new ClientPicker.Callback() {
                            @Override
                            public void onPicked(String pkg, String activity, String label) {
                                new Prefs(MainActivity.this).setClientPackage(pkg);
                                connect(server);
                            }
                        });
                    }
                })
                .setNeutralButton("Адрес", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClientLauncher.copy(MainActivity.this, server.address());
                        Ui.toast(MainActivity.this, "Адрес скопирован: " + server.address());
                    }
                })
                .setPositiveButton("Ок", null)
                .show();
    }

    /* ------------------------------------------------------------------ меню сервера */

    private void serverMenu(final Server server) {
        final String[] actions = {
                "Подключиться",
                "Информация: игроки, правила, карта",
                server.favorite ? "Убрать из избранного" : "В избранное",
                "Мод-паки для этого сервера",
                "Изменить",
                "Скопировать адрес",
                "Удалить",
        };
        new AlertDialog.Builder(this)
                .setTitle(server.title())
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                connect(server);
                                break;
                            case 1:
                                Intent info = new Intent(MainActivity.this, ServerInfoActivity.class);
                                info.putExtra("server_id", server.id);
                                startActivity(info);
                                break;
                            case 2:
                                Servers.toggleFavorite(MainActivity.this, server.id);
                                loadServers();
                                render();
                                break;
                            case 3:
                                Intent mods = new Intent(MainActivity.this, ModsActivity.class);
                                mods.putExtra("server_id", server.id);
                                mods.putExtra("server_name", server.title());
                                startActivity(mods);
                                break;
                            case 4:
                                serverDialog(server);
                                break;
                            case 5:
                                ClientLauncher.copy(MainActivity.this, server.address());
                                Ui.toast(MainActivity.this, "Скопировано: " + server.address());
                                break;
                            default:
                                Ui.confirm(MainActivity.this, "Удалить сервер?", server.title() + " (" + server.address() + ")",
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                Servers.remove(MainActivity.this, server.id);
                                                loadServers();
                                                render();
                                            }
                                        });
                                break;
                        }
                    }
                })
                .show();
    }

    /* ------------------------------------------------------------------ добавление и правка */

    /** Диалог сервера: адрес, название, пароль. existing == null — добавление. */
    void serverDialog(final Server existing) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_server, null);
        final EditText address = (EditText) content.findViewById(R.id.dlg_address);
        final EditText name = (EditText) content.findViewById(R.id.dlg_name);
        final EditText password = (EditText) content.findViewById(R.id.dlg_password);
        final TextView hint = (TextView) content.findViewById(R.id.dlg_hint);

        if (existing != null) {
            address.setText(existing.address());
            name.setText(existing.name);
            password.setText(existing.password);
            hint.setText("Пустое название — покажем имя сервера из его ответа.");
        } else {
            hint.setText("Адрес в виде ip:порт или ip (порт по умолчанию 7777).\nМожно вставить адрес из буфера.");
            address.setText(clipboardAddress());
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Новый сервер" : "Изменить сервер")
                .setView(content)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Server parsed = Server.parse(address.getText().toString());
                if (parsed == null) {
                    hint.setText("Не разобрал адрес. Пример: 12.34.56.78:7777");
                    return;
                }
                if (existing == null && Servers.find(servers, parsed.host, parsed.port) != null) {
                    hint.setText("Такой сервер уже есть в списке.");
                    return;
                }
                Server target = existing == null ? parsed : existing;
                target.host = parsed.host;
                target.port = parsed.port;
                target.name = name.getText().toString().trim();
                target.password = password.getText().toString().trim();
                if (target.id == null || target.id.isEmpty()) {
                    target.id = Servers.newId(target.host, target.port);
                    target.addedAt = System.currentTimeMillis();
                    servers.add(target);
                }
                if (existing == null) {
                    target.favorite = servers.size() == 1;
                }
                Servers.save(MainActivity.this, servers);
                dialog.dismiss();
                render();
                queryOne(target);
            }
        });
    }

    private String clipboardAddress() {
        try {
            android.content.ClipboardManager manager =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null && manager.hasPrimaryClip() && manager.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (text != null) {
                    String value = text.toString().trim();
                    if (value.length() <= 64) {
                        Server parsed = Server.parse(value);
                        if (parsed != null) {
                            return parsed.address();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Небольшая внутренняя «версия» — сравнивается с versionCode сервера. */
    static final class BuildConfigVersion {
        static final int CODE = 1;
    }
}
