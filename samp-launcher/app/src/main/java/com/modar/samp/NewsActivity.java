package com.modar.samp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Новости проекта и обновления лаунчера — всё приходит с сервера лаунчера. */
public class NewsActivity extends Activity {

    private ListView list;
    private TextView empty;
    private TextView updateBox;
    private NewsAdapter adapter;

    private final List<News> items = new ArrayList<News>();
    private News.Update update;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news);

        list = (ListView) findViewById(R.id.news);
        empty = (TextView) findViewById(R.id.news_empty);
        updateBox = (TextView) findViewById(R.id.news_update);
        adapter = new NewsAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        updateBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showUpdate();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (!Api.configured(this)) {
            empty.setText("Новости приходят с сервера лаунчера.\n\nУкажите его адрес в настройках.");
            updateBox.setVisibility(View.GONE);
            return;
        }
        empty.setText("Загружаем…");
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<News> remote = Api.news(NewsActivity.this);
                    final News.Update info = Api.update(NewsActivity.this);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            items.clear();
                            items.addAll(remote);
                            adapter.notifyDataSetChanged();
                            update = info;
                            if (info.isNewerThan(MainActivity.BuildConfigVersion.CODE)) {
                                updateBox.setText("⬆ Доступна версия лаунчера " + info.version + " — нажмите, чтобы обновить");
                                updateBox.setVisibility(View.VISIBLE);
                            } else {
                                updateBox.setVisibility(View.GONE);
                            }
                            if (items.isEmpty()) {
                                empty.setText("Новостей пока нет");
                            }
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            empty.setText("Не удалось загрузить новости:\n" + e.getMessage());
                            updateBox.setVisibility(View.GONE);
                        }
                    });
                }
            }
        });
    }

    private void showUpdate() {
        if (update == null || update.url.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("Версия ").append(update.version).append(" уже на сервере.\n");
        if (!update.notes.isEmpty()) {
            message.append("\n").append(update.notes).append("\n");
        }
        message.append("\nУстановка: скачайте APK, откройте его и подтвердите установку "
                + "(Android спросит разрешение на установку из этого источника).");
        new AlertDialog.Builder(this)
                .setTitle("Обновление лаунчера")
                .setMessage(message.toString())
                .setNegativeButton("Позже", null)
                .setNeutralButton("Открыть в браузере", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClientLauncher.openUrl(NewsActivity.this, update.url);
                    }
                })
                .setPositiveButton("Скачать", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        download();
                    }
                })
                .show();
    }

    private void download() {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                ClientLauncher.openUrl(this, update.url);
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.url));
            request.setTitle("Modar SAMP " + update.version);
            request.setDescription("Загрузка обновления лаунчера");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS,
                    "ModarSAMP-" + update.version + ".apk");
            manager.enqueue(request);
            Ui.toastLong(this, "Загрузка пошла. Когда файл скачается, откройте его из уведомления — "
                    + "останется подтвердить установку.");
        } catch (Throwable t) {
            ClientLauncher.openUrl(this, update.url);
        }
    }

    private class NewsAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public News getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(NewsActivity.this).inflate(R.layout.item_news, parent, false);
            }
            News news = getItem(position);
            TextView tag = (TextView) view.findViewById(R.id.news_tag);
            TextView date = (TextView) view.findViewById(R.id.news_date);
            TextView title = (TextView) view.findViewById(R.id.news_title);
            TextView text = (TextView) view.findViewById(R.id.news_text);

            if (news.tag.isEmpty()) {
                tag.setVisibility(View.GONE);
            } else {
                tag.setVisibility(View.VISIBLE);
                tag.setText(news.tag);
            }
            date.setText(news.date > 0 ? Ui.ago(news.date) : "");
            title.setText((news.pinned ? "📌 " : "") + news.title);
            text.setText(news.text);
            return view;
        }
    }
}
