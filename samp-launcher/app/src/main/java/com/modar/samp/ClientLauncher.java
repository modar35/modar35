package com.modar.samp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Запуск установленного SA-MP-клиента с нужным адресом.
 *
 * Мобильные клиенты SA-MP — это форки одного проекта, и адрес сервера они принимают
 * по-разному. Лаунчер делает всё, что можно сделать снаружи:
 *   1) кладёт адрес, порт, ник и пароль в extras Intent'а (у большинства сборок это
 *      ключи «ip» и «port», имена настраиваются в настройках лаунчера);
 *   2) дополнительно может положить строку аргументов вида «-h 1.2.3.4 -p 7777 -n Nick»;
 *   3) копирует «ip:port» в буфер обмена — если сборка extras не читает, адрес
 *      остаётся под рукой для вставки в меню «Добавить сервер» внутри игры.
 */
public final class ClientLauncher {

    /** Известные сборки мобильного SA-MP: подсказки в выборе клиента. */
    private static final String[][] KNOWN = {
            {"com.gta.game", "SA-MP Mobile (kuzia15, Ryzellx, сборки на GTA 2.10/2.11)"},
            {"com.xyron.game", "News RP / сборки Xyron"},
            {"com.nativo.rpg", "Nativo SA-MP"},
            {"com.rstarx.hexrays", "Hexrays SA-MP"},
            {"ru.unisamp_mobile.launcher", "SA-MP Launcher (UniSamp)"},
            {"com.sampmobile.launcher", "SAMP Mobile Launcher"},
            {"com.rockstargames.gtasa", "GTA: San Andreas (одиночная игра, без SA-MP)"},
            {"com.gta.sa.multiplayer", "SA-MP сборки с таким именем пакета"},
    };

    public static final class Client {
        public String pkg = "";
        public String label = "";
        public String activity = "";
        public boolean installed;
        public boolean known;

        public Client(String pkg, String label, String activity, boolean installed, boolean known) {
            this.pkg = pkg;
            this.label = label;
            this.activity = activity;
            this.installed = installed;
            this.known = known;
        }

        public String title() {
            return label == null || label.isEmpty() ? pkg : label;
        }

        public String subtitle() {
            String base = pkg;
            if (!installed) {
                base += " · не установлен";
            }
            if (activity != null && !activity.isEmpty()) {
                base += " · " + activity;
            }
            return base;
        }
    }

    /** Ссылка на сборку клиента, которую раздаёт сервер лаунчера. */
    public static final class ClientLink {
        public String name = "";
        public String description = "";
        public String url = "";
        public String pkg = "";

        public static ClientLink fromJson(JSONObject json, String baseUrl) {
            ClientLink link = new ClientLink();
            link.name = json.optString("name", "");
            link.description = json.optString("description", "");
            link.url = Mod.absolute(baseUrl, json.optString("url", ""));
            link.pkg = json.optString("pkg", "");
            return link;
        }

        public static List<ClientLink> parse(JSONObject json, String baseUrl) {
            List<ClientLink> list = new ArrayList<ClientLink>();
            JSONArray array = json == null ? null : json.optJSONArray("clients");
            if (array == null) {
                return list;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    ClientLink link = fromJson(item, baseUrl);
                    if (!link.url.isEmpty()) {
                        list.add(link);
                    }
                }
            }
            return list;
        }
    }

    /** Результат попытки подключения. */
    public static final class Result {
        public boolean ok;
        public String message = "";
        public String clipboard = "";
        public String pkg = "";
    }

    private ClientLauncher() {
    }

    /* ------------------------------------------------------------------ поиск клиентов */

    public static boolean isInstalled(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Известные сборки + выбранный пользователем пакет (даже если он не в списке). */
    public static List<Client> known(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Client> list = new ArrayList<Client>();
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        for (String[] item : KNOWN) {
            boolean installed = isInstalled(ctx, item[0]);
            list.add(new Client(item[0], item[1], "", installed, true));
            seen.put(item[0], Boolean.TRUE);
        }
        String custom = new Prefs(ctx).clientPackage();
        if (!custom.isEmpty() && !seen.containsKey(custom)) {
            String label = "";
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(custom, 0)));
            } catch (Throwable ignored) {
            }
            list.add(0, new Client(custom, label.isEmpty() ? "Выбранный клиент" : label,
                    new Prefs(ctx).clientActivity(), isInstalled(ctx, custom), false));
        }
        Collections.sort(list, new Comparator<Client>() {
            @Override
            public int compare(Client a, Client b) {
                if (a.installed != b.installed) {
                    return a.installed ? -1 : 1;
                }
                return a.title().compareToIgnoreCase(b.title());
            }
        });
        return list;
    }

    /** Все приложения с иконкой в меню — чтобы выбрать клиент вручную, без угадывания пакета. */
    public static List<Client> launchable(Context ctx) {
        List<Client> list = new ArrayList<Client>();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = ctx.getPackageManager().queryIntentActivities(intent, 0);
            Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
            for (ResolveInfo info : resolved) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || seen.containsKey(pkg)) {
                    continue;
                }
                seen.put(pkg, Boolean.TRUE);
                String label = String.valueOf(info.loadLabel(ctx.getPackageManager()));
                String activity = info.activityInfo.name;
                list.add(new Client(pkg, label, activity, true, false));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        Collections.sort(list, new Comparator<Client>() {
            @Override
            public int compare(Client a, Client b) {
                return a.title().compareToIgnoreCase(b.title());
            }
        });
        return list;
    }

    /** Имя приложения по пакету (для настроек и списка). */
    public static String label(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "";
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ подключение */

    public static Result connect(Activity activity, Server server) {
        Prefs prefs = new Prefs(activity);
        Result result = new Result();
        String pkg = prefs.clientPackage();
        if (pkg.isEmpty()) {
            result.message = "Клиент не выбран. Выберите установленный SA-MP-клиент в настройках лаунчера.";
            return result;
        }
        Intent intent = null;
        String activityName = prefs.clientActivity();
        if (!activityName.isEmpty()) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(pkg, activityName);
        }
        if (intent == null) {
            intent = activity.getPackageManager().getLaunchIntentForPackage(pkg);
        }
        if (intent == null) {
            result.message = "Не удалось запустить «" + pkg + "»: приложение не найдено на устройстве.";
            return result;
        }

        String nick = prefs.nickname();
        String password = server.password != null && !server.password.isEmpty() ? server.password : prefs.serverPassword();

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(prefs.extraIpKey(), server.host);
        intent.putExtra(prefs.extraPortKey(), server.port);
        if (nick != null && !nick.isEmpty()) {
            intent.putExtra(prefs.extraNickKey(), nick);
        }
        if (password != null && !password.isEmpty()) {
            intent.putExtra(prefs.extraPassKey(), password);
        }
        // наиболее распространённые синонимы: некоторые сборки читают их, а не настраиваемые ключи
        intent.putExtra("host", server.host);
        intent.putExtra("port", server.port);
        intent.putExtra("server_ip", server.host);
        intent.putExtra("server_port", server.port);
        if (nick != null && !nick.isEmpty()) {
            intent.putExtra("nick", nick);
            intent.putExtra("name", nick);
        }
        if (password != null && !password.isEmpty()) {
            intent.putExtra("server_password", password);
        }
        if (prefs.sendArgsExtra()) {
            StringBuilder args = new StringBuilder("-c");
            args.append(" -h ").append(server.host);
            args.append(" -p ").append(server.port);
            if (nick != null && !nick.isEmpty()) {
                args.append(" -n ").append(nick.replace(" ", "_"));
            }
            if (password != null && !password.isEmpty()) {
                args.append(" -z ").append(password);
            }
            intent.putExtra("args", args.toString());
            intent.putExtra("cmdline", args.toString());
        }

        String address = server.address();
        if (prefs.copyToClipboard()) {
            copy(activity, address);
            result.clipboard = address;
        }

        try {
            activity.startActivity(intent);
            result.ok = true;
            result.pkg = pkg;
            result.message = "Открываем " + (label(activity, pkg).isEmpty() ? pkg : label(activity, pkg))
                    + "\nАдрес: " + address
                    + (nick == null || nick.isEmpty() ? "" : "\nНик: " + nick)
                    + (result.clipboard.isEmpty() ? "" : "\n\nАдрес скопирован в буфер обмена — если игра не подставила его сама,"
                    + " добавьте сервер в меню игры (долгое нажатие в поле ввода → «Вставить»).");
        } catch (Throwable t) {
            result.message = "Клиент найден, но не запустился: " + t.getMessage()
                    + "\nАдрес сервера: " + address + (result.clipboard.isEmpty() ? "" : " (скопирован)");
        }
        return result;
    }

    public static void copy(Context ctx, String text) {
        try {
            ClipboardManager manager = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("SA-MP", text));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static void openUrl(Context ctx, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Ui.toast(ctx, "Не удалось открыть ссылку");
        }
    }

    /** Открыть настройки приложения (например, чтобы выдать доступ к файлам). */
    public static void openAppSettings(Context ctx, String pkg) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Ui.toast(ctx, "Откройте настройки приложения вручную");
        }
    }

    /** Есть ли на устройстве хотя бы один установленный SA-MP-клиент из известных. */
    public static boolean anyKnownInstalled(Context ctx) {
        for (String[] item : KNOWN) {
            if ("com.rockstargames.gtasa".equals(item[0])) {
                continue; // одиночная игра — не считать клиентом
            }
            if (isInstalled(ctx, item[0])) {
                return true;
            }
        }
        String custom = new Prefs(ctx).clientPackage();
        return !custom.isEmpty() && isInstalled(ctx, custom);
    }

    /** Первый найденный клиент — чтобы предложить его при первом подключении. */
    public static Client firstInstalled(Context ctx) {
        for (Client client : known(ctx)) {
            if (client.installed && !"com.rockstargames.gtasa".equals(client.pkg)) {
                return client;
            }
        }
        return null;
    }

    public static String deviceAbiHint() {
        return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
    }

    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
