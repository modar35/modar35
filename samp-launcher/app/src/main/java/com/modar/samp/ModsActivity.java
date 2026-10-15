package com.modar.samp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Мод-паки: что раздаёт сервер лаунчера, что уже установлено и что можно снять.
 * Установка кладёт файлы в каталог игры, прежние версии сохраняет в резервную копию.
 */
public class ModsActivity extends Activity {

    private static final int PICK_ZIP = 4201;

    private ListView list;
    private TextView empty;
    private TextView header;
    private TextView storageNote;
    private ModsAdapter adapter;
    private Button grantButton;

    private final List<Mod> mods = new ArrayList<Mod>();
    private Map<String, Mods.Installed> installed;
    private String serverId = "";
    private String serverName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mods);

        serverId = getIntent().getStringExtra("server_id");
        serverName = getIntent().getStringExtra("server_name");
        if (serverId == null) {
            serverId = "";
        }
        if (serverName == null) {
            serverName = "";
        }

        list = (ListView) findViewById(R.id.mods);
        empty = (TextView) findViewById(R.id.mods_empty);
        header = (TextView) findViewById(R.id.mods_header);
        storageNote = (TextView) findViewById(R.id.mods_storage);
        grantButton = (Button) findViewById(R.id.btn_grant);

        adapter = new ModsAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                details(mods.get(position));
            }
        });

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
        findViewById(R.id.btn_import).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importZip();
            }
        });
        grantButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Storage.request(ModsActivity.this, null);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStorage();
        load();
    }

    private void updateStorage() {
        Prefs prefs = new Prefs(this);
        long free = Storage.freeSpace(prefs.gameDir());
        header.setText("Игра: " + prefs.gameDir() + "\nМоды и копии: " + prefs.dataDir()
                + (free > 0 ? "\nСвободно: " + Ui.size(free) : ""));
        boolean granted = Storage.granted(this);
        grantButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        storageNote.setText(granted ? "Доступ к файлам: есть" : "Доступ к файлам: нет — установка не сработает");
        storageNote.setTextColor(Ui.color(this, granted ? R.color.ok : R.color.danger));
    }

    private void load() {
        installed = Mods.installed(this);
        if (!Api.configured(this)) {
            mods.clear();
            adapter.notifyDataSetChanged();
            empty.setText("Список мод-паков приходит с сервера лаунчера.\n\n"
                    + "Укажите адрес в настройках — или установите мод из файла кнопкой «из файла».");
            return;
        }
        empty.setText("Загружаем каталог…");
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Mod> remote = Mods.forServer(Mods.fetch(ModsActivity.this), serverId);
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            mods.clear();
                            mods.addAll(remote);
                            adapter.notifyDataSetChanged();
                            if (mods.isEmpty()) {
                                empty.setText(serverId.isEmpty()
                                        ? "На сервере лаунчера пока нет мод-паков.\nДобавьте их в веб-панели сервера."
                                        : "Для этого сервера нет мод-паков.\nСмотрите общие: они появятся здесь при выборе «Все моды».");
                            }
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            empty.setText("Не удалось получить список модов:\n" + e.getMessage()
                                    + "\n\nПроверьте адрес сервера лаунчера в настройках.");
                        }
                    });
                }
            }
        });
    }

    /* ------------------------------------------------------------------ действия */

    private void install(final Mod mod) {
        Storage.ensure(this, new Runnable() {
            @Override
            public void run() {
                final ProgressBar bar = new ProgressBar(ModsActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(100);
                final TextView text = new TextView(ModsActivity.this);
                text.setPadding(Ui.dp(ModsActivity.this, 20), Ui.dp(ModsActivity.this, 8), Ui.dp(ModsActivity.this, 20), 0);
                LinearLayout box = new LinearLayout(ModsActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.addView(text);
                box.addView(bar);

                final boolean[] cancelled = {false};
                final AlertDialog dialog = new AlertDialog.Builder(ModsActivity.this)
                        .setTitle(mod.name)
                        .setView(box)
                        .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                cancelled[0] = true;
                            }
                        })
                        .setCancelable(false)
                        .create();
                dialog.show();

                Ui.bg(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Mods.install(ModsActivity.this, mod, new Mods.Listener() {
                                @Override
                                public void onStage(final String stage) {
                                    Ui.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            text.setText(stage + "…");
                                        }
                                    });
                                }

                                @Override
                                public void onProgress(final long done, final long total) {
                                    Ui.ui(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (total > 0) {
                                                bar.setIndeterminate(false);
                                                bar.setProgress((int) (done * 100 / total));
                                                text.setText("Скачивание: " + Ui.size(done) + " из " + Ui.size(total));
                                            } else {
                                                bar.setIndeterminate(true);
                                                text.setText("Распаковка: " + Ui.size(done));
                                            }
                                        }
                                    });
                                }
                            }, new Mods.Cancel() {
                                @Override
                                public boolean cancelled() {
                                    return cancelled[0];
                                }
                            });
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    dialog.dismiss();
                                    Ui.toast(ModsActivity.this, "Мод установлен: " + mod.name);
                                    load();
                                }
                            });
                        } catch (final Exception e) {
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    dialog.dismiss();
                                    Ui.alert(ModsActivity.this, "Не удалось установить мод", String.valueOf(e.getMessage()));
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void uninstall(final Mod mod) {
        Ui.confirm(this, "Снять мод?", mod.name + "\n\nЗаменённые файлы вернутся из резервной копии, "
                + "добавленные модом — будут удалены.", new Runnable() {
            @Override
            public void run() {
                Ui.bg(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Mods.uninstall(ModsActivity.this, mod.id);
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    Ui.toast(ModsActivity.this, "Мод снят");
                                    load();
                                }
                            });
                        } catch (final Exception e) {
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    Ui.alert(ModsActivity.this, "Не удалось снять мод", String.valueOf(e.getMessage()));
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void details(final Mod mod) {
        Mods.Installed state = Mods.find(installed, mod.id);
        StringBuilder text = new StringBuilder();
        text.append("Версия: ").append(mod.version);
        if (mod.size > 0) {
            text.append("\nРазмер: ").append(Ui.size(mod.size));
        }
        if (!mod.author.isEmpty()) {
            text.append("\nАвтор: ").append(mod.author);
        }
        text.append("\nТип: ").append(mod.isZip() ? "архив → " + (mod.target.isEmpty() ? "каталог игры" : mod.target) : "файл → " + mod.target);
        if (!mod.sha256.isEmpty()) {
            text.append("\nSHA-256: ").append(mod.sha256.substring(0, Math.min(16, mod.sha256.length()))).append("…");
        }
        if (state != null) {
            text.append("\n\nУстановлено: ").append(Ui.date(state.installedAt))
                    .append("\nФайлов: ").append(state.files.size())
                    .append(state.hasBackup() ? " (есть резервная копия)" : " (без резервной копии)");
        }
        if (!mod.description.isEmpty()) {
            text.append("\n\n").append(mod.description);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(mod.name)
                .setMessage(text.toString())
                .setPositiveButton("Закрыть", null);
        if (state != null) {
            boolean outdated = Mods.isOutdated(installed, mod);
            builder.setNegativeButton(outdated ? "Обновить" : "Снять мод", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (Mods.isOutdated(installed, mod)) {
                        install(mod);
                    } else {
                        uninstall(mod);
                    }
                }
            });
        } else {
            builder.setNeutralButton("Установить", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    install(mod);
                }
            });
        }
        builder.show();
    }

    private void importZip() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Выберите zip-архив мода"), PICK_ZIP);
        } catch (Throwable t) {
            Ui.toast(this, "Не нашлось приложения для выбора файла");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ZIP || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        Storage.ensure(this, new Runnable() {
            @Override
            public void run() {
                Ui.bg(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Prefs prefs = new Prefs(ModsActivity.this);
                            File tmp = new File(prefs.tmpDir());
                            Mods.ensureDir(tmp);
                            String name = displayName(uri);
                            File target = new File(tmp, "import-" + System.currentTimeMillis() + ".zip");
                            InputStream in = getContentResolver().openInputStream(uri);
                            FileOutputStream out = new FileOutputStream(target);
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                out.write(buffer, 0, read);
                            }
                            out.close();
                            in.close();
                            Mods.importZip(ModsActivity.this, target, name, null, Mods.NEVER);
                            target.delete();
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    Ui.toast(ModsActivity.this, "Архив установлен в каталог игры");
                                    load();
                                }
                            });
                        } catch (final Exception e) {
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    Ui.alert(ModsActivity.this, "Не удалось установить архив", String.valueOf(e.getMessage()));
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private String displayName(Uri uri) {
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (cursor.moveToFirst() && index >= 0) {
                    String name = cursor.getString(index);
                    cursor.close();
                    return name;
                }
                cursor.close();
            }
        } catch (Throwable ignored) {
        }
        String path = uri.getLastPathSegment();
        return path == null ? "Локальный мод" : path;
    }

    /* ------------------------------------------------------------------ адаптер */

    private class ModsAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mods.size();
        }

        @Override
        public Mod getItem(int position) {
            return mods.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(ModsActivity.this).inflate(R.layout.item_mod, parent, false);
            }
            final Mod mod = getItem(position);
            TextView name = (TextView) view.findViewById(R.id.mod_name);
            TextView meta = (TextView) view.findViewById(R.id.mod_meta);
            TextView description = (TextView) view.findViewById(R.id.mod_description);
            final TextView action = (TextView) view.findViewById(R.id.mod_action);
            TextView badge = (TextView) view.findViewById(R.id.mod_badge);

            name.setText(mod.name + (mod.version.isEmpty() ? "" : " " + mod.version));

            StringBuilder metaText = new StringBuilder();
            if (mod.size > 0) {
                metaText.append(Ui.size(mod.size));
            }
            if (!mod.author.isEmpty()) {
                metaText.append(metaText.length() > 0 ? " · " : "").append(mod.author);
            }
            if (!serverName.isEmpty() && !mod.serverId.isEmpty()) {
                metaText.append(metaText.length() > 0 ? " · " : "").append("для сервера");
            }
            meta.setText(metaText);
            description.setText(mod.description.isEmpty()
                    ? (mod.isZip() ? "Архив → " + (mod.target.isEmpty() ? "каталог игры" : mod.target) : "Файл → " + mod.target)
                    : mod.description);

            Mods.Installed state = Mods.find(installed, mod.id);
            if (state == null) {
                badge.setText("не установлен");
                badge.setTextColor(Ui.color(ModsActivity.this, R.color.text_muted));
                action.setText("Установить");
            } else if (Mods.isOutdated(installed, mod)) {
                badge.setText("установлен " + (state.version == null ? "" : state.version));
                badge.setTextColor(Ui.color(ModsActivity.this, R.color.warn));
                action.setText("Обновить");
            } else {
                badge.setText("установлен");
                badge.setTextColor(Ui.color(ModsActivity.this, R.color.ok));
                action.setText("Снять");
            }
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Mods.Installed state = Mods.find(installed, mod.id);
                    if (state != null && !Mods.isOutdated(installed, mod)) {
                        uninstall(mod);
                    } else {
                        install(mod);
                    }
                }
            });
            return view;
        }
    }
}
