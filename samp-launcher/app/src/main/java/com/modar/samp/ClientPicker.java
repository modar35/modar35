package com.modar.samp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Выбор SA-MP-клиента: сначала известные сборки (установленные — сверху), затем все приложения
 * устройства, а если клиента нет — список ссылок с сервера лаунчера.
 */
public final class ClientPicker {

    /** Что делать после выбора. */
    public interface Callback {
        void onPicked(String pkg, String activity, String label);
    }

    private ClientPicker() {
    }

    public static void show(final Activity activity, final Callback callback) {
        final List<ClientLauncher.Client> clients = ClientLauncher.known(activity);
        final List<String> labels = new ArrayList<String>();
        for (ClientLauncher.Client client : clients) {
            labels.add(client.title() + "\n" + client.subtitle());
        }
        labels.add("▸ Все приложения на устройстве…");
        labels.add("▸ Клиент не установлен — где скачать");

        new AlertDialog.Builder(activity)
                .setTitle("Клиент SA-MP")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < clients.size()) {
                            ClientLauncher.Client client = clients.get(which);
                            if (!client.installed) {
                                askInstall(activity, client);
                                return;
                            }
                            callback.onPicked(client.pkg, client.activity, client.title());
                        } else if (which == clients.size()) {
                            showAllApps(activity, callback);
                        } else {
                            showDownloads(activity);
                        }
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void showAllApps(final Activity activity, final Callback callback) {
        final List<ClientLauncher.Client> apps = ClientLauncher.launchable(activity);
        final List<String> labels = new ArrayList<String>();
        for (ClientLauncher.Client app : apps) {
            labels.add(app.title() + "\n" + app.pkg);
        }
        new AlertDialog.Builder(activity)
                .setTitle("Все приложения")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClientLauncher.Client app = apps.get(which);
                        callback.onPicked(app.pkg, app.activity, app.title());
                    }
                })
                .setNegativeButton("Назад", null)
                .show();
    }

    private static void askInstall(final Activity activity, final ClientLauncher.Client client) {
        new AlertDialog.Builder(activity)
                .setTitle(client.title())
                .setMessage("Сборка «" + client.title() + "» не установлена.\n\n"
                        + "Лаунчер запускает уже установленный клиент SA-MP — сам клиент нужно поставить "
                        + "один раз вручную. Ссылки на рекомендованные сборки приходят с вашего сервера "
                        + "лаунчера (Настройки → Адрес сервера).")
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Где скачать", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showDownloads(activity);
                    }
                })
                .show();
    }

    /** Список сборок клиента с сервера лаунчера (/api/v1/clients). */
    public static void showDownloads(final Activity activity) {
        if (!Api.configured(activity)) {
            Ui.alert(activity, "Ссылки недоступны",
                    "Список сборок приходит с сервера лаунчера, а его адрес пока не указан.\n\n"
                            + "Настройки → «Адрес сервера лаунчера» — укажите адрес вашего сервера "
                            + "(samp-launcher/server в этом репозитории).");
            return;
        }
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject json = Api.get(activity, "/api/v1/clients");
                    final List<ClientLauncher.ClientLink> links = ClientLauncher.ClientLink.parse(json, Api.base(activity));
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            if (links.isEmpty()) {
                                Ui.alert(activity, "Список пуст",
                                        "На сервере лаунчера не задано ни одной сборки клиента. "
                                                + "Добавьте их в веб-панели сервера (раздел «Клиенты»).");
                                return;
                            }
                            final List<String> labels = new ArrayList<String>();
                            for (ClientLauncher.ClientLink link : links) {
                                labels.add(link.name + (link.description.isEmpty() ? "" : "\n" + link.description));
                            }
                            new AlertDialog.Builder(activity)
                                    .setTitle("Откуда скачать клиент")
                                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            ClientLauncher.openUrl(activity, links.get(which).url);
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (final Exception e) {
                    Ui.ui(new Runnable() {
                        @Override
                        public void run() {
                            Ui.alert(activity, "Не удалось получить список",
                                    String.valueOf(e.getMessage()) + "\n\nПроверьте адрес сервера в настройках.");
                        }
                    });
                }
            }
        });
    }
}
