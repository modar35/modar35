package com.modar.samp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;

/** Настройки лаунчера: адрес API, каталог игры, клиент, ник и параметры опроса. */
public class Prefs {

    /** Публичный сервер мод-паков по умолчанию пустой: пользователь указывает свой. */
    public static final String DEFAULT_API = "";
    public static final String DEFAULT_GAME_DIR = "/storage/emulated/0/GTA";
    public static final String DEFAULT_DATA_DIR = "/storage/emulated/0/ModarLauncher";

    private static final String NAME = "modar_samp";

    private final Context ctx;
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private String get(String key, String def) {
        String value = sp.getString(key, def);
        return value == null ? def : value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /* ------------------------------------------------------------------ сервер лаунчера */

    public String apiUrl() {
        String url = clean(get("api_url", DEFAULT_API));
        if (url.isEmpty()) {
            return "";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public void setApiUrl(String url) {
        sp.edit().putString("api_url", clean(url)).apply();
    }

    /* ------------------------------------------------------------------ пути */

    public String gameDir() {
        String dir = clean(get("game_dir", DEFAULT_GAME_DIR));
        return dir.isEmpty() ? DEFAULT_GAME_DIR : stripSlash(dir);
    }

    public void setGameDir(String dir) {
        sp.edit().putString("game_dir", clean(dir)).apply();
    }

    public String dataDir() {
        String dir = clean(get("data_dir", DEFAULT_DATA_DIR));
        return dir.isEmpty() ? DEFAULT_DATA_DIR : stripSlash(dir);
    }

    public void setDataDir(String dir) {
        sp.edit().putString("data_dir", clean(dir)).apply();
    }

    private static String stripSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** Каталог резервных копий заменённых файлов. */
    public String backupDir() {
        return dataDir() + "/backup";
    }

    /** Каталог со временными файлами загрузок. */
    public String tmpDir() {
        return dataDir() + "/tmp";
    }

    /* ------------------------------------------------------------------ клиент */

    public String clientPackage() {
        return clean(get("client_pkg", ""));
    }

    public void setClientPackage(String value) {
        sp.edit().putString("client_pkg", clean(value)).apply();
    }

    /** Пусто — берём главную активити клиента. */
    public String clientActivity() {
        return clean(get("client_activity", ""));
    }

    public void setClientActivity(String value) {
        sp.edit().putString("client_activity", clean(value)).apply();
    }

    public String nickname() {
        String nick = clean(get("nickname", ""));
        return nick.isEmpty() ? "Modar" + (100 + new Random().nextInt(900)) : nick;
    }

    public void setNickname(String value) {
        sp.edit().putString("nickname", clean(value)).apply();
    }

    /** Пароль сервера по умолчанию (для серверов с паролем). */
    public String serverPassword() {
        return get("server_password", "");
    }

    public void setServerPassword(String value) {
        sp.edit().putString("server_password", clean(value)).apply();
    }

    /* ------------------------------------------------------------------ передача адреса клиенту */

    /** Имена extras, которые читает клиент: у разных сборок они разные. */
    public String extraIpKey() {
        String value = clean(get("extra_ip", "ip"));
        return value.isEmpty() ? "ip" : value;
    }

    public void setExtraIpKey(String value) {
        sp.edit().putString("extra_ip", clean(value)).apply();
    }

    public String extraPortKey() {
        String value = clean(get("extra_port", "port"));
        return value.isEmpty() ? "port" : value;
    }

    public void setExtraPortKey(String value) {
        sp.edit().putString("extra_port", clean(value)).apply();
    }

    public String extraNickKey() {
        String value = clean(get("extra_nick", "nickname"));
        return value.isEmpty() ? "nickname" : value;
    }

    public void setExtraNickKey(String value) {
        sp.edit().putString("extra_nick", clean(value)).apply();
    }

    public String extraPassKey() {
        String value = clean(get("extra_pass", "password"));
        return value.isEmpty() ? "password" : value;
    }

    public void setExtraPassKey(String value) {
        sp.edit().putString("extra_pass", clean(value)).apply();
    }

    /** Дополнительно кладём в extras строку вида «-h 1.2.3.4 -p 7777 -n Nick». */
    public boolean sendArgsExtra() {
        return sp.getBoolean("send_args", true);
    }

    public void setSendArgsExtra(boolean value) {
        sp.edit().putBoolean("send_args", value).apply();
    }

    /** Копировать «ip:port» в буфер обмена при запуске клиента (ручная вставка в меню игры). */
    public boolean copyToClipboard() {
        return sp.getBoolean("copy_clipboard", true);
    }

    public void setCopyToClipboard(boolean value) {
        sp.edit().putBoolean("copy_clipboard", value).apply();
    }

    /* ------------------------------------------------------------------ поведение */

    public int queryTimeout() {
        return sp.getInt("query_timeout", SampQuery.DEFAULT_TIMEOUT);
    }

    public void setQueryTimeout(int ms) {
        sp.edit().putInt("query_timeout", Math.max(400, Math.min(10000, ms))).apply();
    }

    public boolean autoRefresh() {
        return sp.getBoolean("auto_refresh", true);
    }

    public void setAutoRefresh(boolean value) {
        sp.edit().putBoolean("auto_refresh", value).apply();
    }

    public boolean backupMods() {
        return sp.getBoolean("backup_mods", true);
    }

    public void setBackupMods(boolean value) {
        sp.edit().putBoolean("backup_mods", value).apply();
    }

    public boolean showFreeOnly() {
        return sp.getBoolean("free_only", false);
    }

    public void setShowFreeOnly(boolean value) {
        sp.edit().putBoolean("free_only", value).apply();
    }

    /* ------------------------------------------------------------------ первый запуск */

    /** Предзаполнение из assets: API_URL=… GRADLE-сборкой или tools/build-apk.sh. */
    public void seedFromAssetsIfEmpty() {
        if (!sp.contains("api_url")) {
            String url = readAsset("api_url.txt");
            if (url != null && !url.isEmpty()) {
                setApiUrl(url);
            }
        }
        if (!sp.contains("game_dir")) {
            String dir = readAsset("game_dir.txt");
            if (dir != null && !dir.isEmpty()) {
                setGameDir(dir);
            }
        }
    }

    /** Внешнее хранилище сейчас доступно? */
    public static boolean externalStorageReady() {
        try {
            return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        } catch (Throwable t) {
            return false;
        }
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
