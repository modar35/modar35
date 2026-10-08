package com.modar.samp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/** Подробности сервера: онлайн, карта, правила и список игроков. */
public class ServerInfoActivity extends Activity {

    private Server server;
    private TextView title;
    private TextView subtitle;
    private TextView statusLine;
    private LinearLayout rulesBox;
    private LinearLayout playersBox;
    private TextView playersTitle;
    private TextView rulesTitle;
    private View refresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_info);

        title = (TextView) findViewById(R.id.info_title);
        subtitle = (TextView) findViewById(R.id.info_subtitle);
        statusLine = (TextView) findViewById(R.id.info_status);
        rulesBox = (LinearLayout) findViewById(R.id.info_rules);
        playersBox = (LinearLayout) findViewById(R.id.info_players);
        playersTitle = (TextView) findViewById(R.id.info_players_title);
        rulesTitle = (TextView) findViewById(R.id.info_rules_title);
        refresh = findViewById(R.id.btn_refresh);

        String id = getIntent().getStringExtra("server_id");
        server = Servers.byId(Servers.all(this), id);
        if (server == null) {
            Ui.toast(this, "Сервер не найден");
            finish();
            return;
        }
        title.setText(server.title());
        subtitle.setText(server.address());

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        findViewById(R.id.btn_connect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Prefs prefs = new Prefs(ServerInfoActivity.this);
                if (prefs.clientPackage().isEmpty()) {
                    ClientPicker.show(ServerInfoActivity.this, new ClientPicker.Callback() {
                        @Override
                        public void onPicked(String pkg, String activity, String label) {
                            prefs.setClientPackage(pkg);
                            connect();
                        }
                    });
                    return;
                }
                connect();
            }
        });
        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClientLauncher.copy(ServerInfoActivity.this, server.address());
                Ui.toast(ServerInfoActivity.this, "Скопировано: " + server.address());
            }
        });
    }

    private void connect() {
        ClientLauncher.Result result = ClientLauncher.connect(this, server);
        new AlertDialog.Builder(this)
                .setTitle(result.ok ? "Подключение" : "Не получилось запустить клиент")
                .setMessage(server.address() + "\n\n" + result.message)
                .setPositiveButton("Ок", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        statusLine.setText("Опрашиваем сервер…");
        refresh.setAlpha(0.4f);
        final Prefs prefs = new Prefs(this);
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                final StringBuilder text = new StringBuilder();
                List<SampQuery.Player> players;
                Map<String, String> rules;
                try {
                    long started = System.currentTimeMillis();
                    SampQuery.Info info = SampQuery.info(server.host, server.port, prefs.queryTimeout());
                    info.rtt = (int) (System.currentTimeMillis() - started);
                    server.last = info;
                    server.offline = false;
                    text.append("Онлайн: ").append(info.fill());
                    if (info.password) {
                        text.append(" · сервер под паролем");
                    }
                    if (!info.gamemode.isEmpty()) {
                        text.append("\nРежим: ").append(info.gamemode);
                    }
                    if (!info.language.isEmpty()) {
                        text.append("\nЯзык: ").append(info.language);
                    }
                    text.append("\nЗадержка: ").append(info.rtt).append(" мс");
                } catch (final Exception e) {
                    server.offline = true;
                    text.append("Сервер не ответил: ").append(e.getMessage());
                }

                try {
                    rules = SampQuery.rules(server.host, server.port, prefs.queryTimeout());
                } catch (Throwable t) {
                    rules = null;
                }
                try {
                    players = SampQuery.players(server.host, server.port, prefs.queryTimeout());
                } catch (Throwable t) {
                    players = null;
                }

                final Map<String, String> rulesResult = rules;
                final List<SampQuery.Player> playersResult = players;
                Ui.ui(new Runnable() {
                    @Override
                    public void run() {
                        statusLine.setText(text.toString());
                        refresh.setAlpha(1f);
                        fillRules(rulesResult);
                        fillPlayers(playersResult);
                    }
                });
            }
        });
    }

    private void fillRules(Map<String, String> rules) {
        rulesBox.removeAllViews();
        if (rules == null || rules.isEmpty()) {
            rulesTitle.setText("Правила сервера");
            rulesBox.addView(row("сервер не отдаёт правила", ""));
            return;
        }
        rulesTitle.setText("Правила сервера · " + rules.size());
        String[] important = {"mapname", "version", "lagcomp", "weather", "worldtime", "gravity", "weburl", "discord"};
        for (String key : important) {
            String value = rules.get(key);
            if (value != null && !value.isEmpty()) {
                rulesBox.addView(row(label(key), value));
                rules.remove(key);
            }
        }
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            rulesBox.addView(row(entry.getKey(), entry.getValue()));
        }
    }

    private void fillPlayers(List<SampQuery.Player> players) {
        playersBox.removeAllViews();
        if (players == null) {
            playersTitle.setText("Игроки");
            playersBox.addView(row("список недоступен (сервер закрыл опрос игроков)", ""));
            return;
        }
        playersTitle.setText("Игроки · " + players.size());
        if (players.isEmpty()) {
            playersBox.addView(row("никого нет онлайн", ""));
            return;
        }
        int shown = 0;
        for (SampQuery.Player player : players) {
            if (shown++ >= 60) {
                playersBox.addView(row("…и ещё " + (players.size() - 60), ""));
                break;
            }
            String right = player.ping >= 0 ? "счёт " + player.score + " · " + player.ping + " мс" : "счёт " + player.score;
            playersBox.addView(row(player.label(), right));
        }
    }

    private View row(String left, String right) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_info_row, null);
        TextView leftView = (TextView) view.findViewById(R.id.info_row_left);
        TextView rightView = (TextView) view.findViewById(R.id.info_row_right);
        leftView.setText(left);
        if (right == null || right.isEmpty()) {
            rightView.setVisibility(View.GONE);
        } else {
            rightView.setText(right);
        }
        return view;
    }

    private static String label(String key) {
        if ("mapname".equals(key)) {
            return "карта";
        }
        if ("version".equals(key)) {
            return "версия";
        }
        if ("lagcomp".equals(key)) {
            return "компенсация лага";
        }
        if ("weather".equals(key)) {
            return "погода";
        }
        if ("worldtime".equals(key)) {
            return "время в мире";
        }
        if ("gravity".equals(key)) {
            return "гравитация";
        }
        if ("weburl".equals(key)) {
            return "сайт";
        }
        if ("discord".equals(key)) {
            return "discord";
        }
        return key;
    }
}
