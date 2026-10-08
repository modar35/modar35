package com.modar.samp;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Установка мод-паков: скачивание с сервера лаунчера, распаковка в каталог игры,
 * резервная копия заменяемых файлов и журнал установленного — чтобы мод можно было снять.
 *
 * Журнал лежит рядом с игрой, в <dataDir>/mods.json, резервные копии — в <dataDir>/backup.
 * Всё видно пользователю в файловом менеджере: никаких скрытых изменений в папке игры.
 */
public final class Mods {

    /** Журнал установленных модов. */
    public static final class Installed {
        public String id = "";
        public String name = "";
        public String version = "";
        public String kind = "zip";
        public String target = "";
        public long installedAt;
        public List<Entry> files = new ArrayList<Entry>();

        public boolean hasBackup() {
            for (Entry entry : files) {
                if (entry.backup != null && !entry.backup.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Один файл из состава мода. */
    public static final class Entry {
        /** Путь относительно каталога игры. */
        public String path = "";
        /** Резервная копия прежнего файла (относительно dataDir) или пусто. */
        public String backup = "";
        /** true — файла до установки мода не было, при удалении его нужно стереть. */
        public boolean added;

        public Entry() {
        }

        public Entry(String path, String backup, boolean added) {
            this.path = path;
            this.backup = backup;
            this.added = added;
        }
    }

    /** Прогресс установки для интерфейса. */
    public interface Listener {
        void onStage(String stage);

        void onProgress(long done, long total);
    }

    /** Возможность отменить операцию. */
    public interface Cancel {
        boolean cancelled();
    }

    public static final Cancel NEVER = new Cancel() {
        @Override
        public boolean cancelled() {
            return false;
        }
    };

    private Mods() {
    }

    /* ------------------------------------------------------------------ каталог модов */

    public static List<Mod> fetch(Context ctx) throws Exception {
        return Api.mods(ctx);
    }

    public static List<Mod> forServer(List<Mod> mods, String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return mods;
        }
        List<Mod> result = new ArrayList<Mod>();
        for (Mod mod : mods) {
            if (mod.serverId == null || mod.serverId.isEmpty() || mod.serverId.equals(serverId)) {
                result.add(mod);
            }
        }
        return result;
    }

    /* ------------------------------------------------------------------ журнал */

    public static File journalFile(Context ctx) {
        return new File(new Prefs(ctx).dataDir(), "mods.json");
    }

    public static Map<String, Installed> installed(Context ctx) {
        Map<String, Installed> result = new LinkedHashMap<String, Installed>();
        File file = journalFile(ctx);
        if (!file.exists()) {
            return result;
        }
        try {
            String text = readText(file);
            JSONObject root = new JSONObject(text);
            JSONObject items = root.optJSONObject("installed");
            if (items == null) {
                return result;
            }
            JSONArray names = items.names();
            if (names == null) {
                return result;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                JSONObject json = items.optJSONObject(key);
                if (json == null) {
                    continue;
                }
                Installed installed = new Installed();
                installed.id = json.optString("id", key);
                installed.name = json.optString("name", installed.id);
                installed.version = json.optString("version", "");
                installed.kind = json.optString("kind", "zip");
                installed.target = json.optString("target", "");
                installed.installedAt = json.optLong("installedAt", 0);
                JSONArray files = json.optJSONArray("files");
                if (files != null) {
                    for (int j = 0; j < files.length(); j++) {
                        JSONObject item = files.optJSONObject(j);
                        if (item == null) {
                            continue;
                        }
                        installed.files.add(new Entry(
                                item.optString("path", ""),
                                item.optString("backup", ""),
                                item.optBoolean("added", false)));
                    }
                }
                result.put(installed.id, installed);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return result;
    }

    public static void saveInstalled(Context ctx, Map<String, Installed> map) {
        JSONObject root = new JSONObject();
        JSONObject items = new JSONObject();
        try {
            for (Installed installed : map.values()) {
                JSONObject json = new JSONObject();
                json.put("id", installed.id);
                json.put("name", installed.name);
                json.put("version", installed.version);
                json.put("kind", installed.kind);
                json.put("target", installed.target);
                json.put("installedAt", installed.installedAt);
                JSONArray files = new JSONArray();
                for (Entry entry : installed.files) {
                    JSONObject item = new JSONObject();
                    item.put("path", entry.path);
                    item.put("backup", entry.backup == null ? "" : entry.backup);
                    item.put("added", entry.added);
                    files.put(item);
                }
                json.put("files", files);
                items.put(installed.id, json);
            }
            root.put("installed", items);
            File file = journalFile(ctx);
            ensureParent(file);
            writeText(file, root.toString(2));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static Installed find(Map<String, Installed> map, String modId) {
        for (Installed installed : map.values()) {
            if (installed.id.equals(modId)) {
                return installed;
            }
        }
        return null;
    }

    /** Установлена ли уже эта версия мода. */
    public static boolean isCurrent(Map<String, Installed> map, Mod mod) {
        Installed installed = find(map, mod.id);
        return installed != null && installed.version != null && installed.version.equals(mod.version);
    }

    public static boolean isOutdated(Map<String, Installed> map, Mod mod) {
        Installed installed = find(map, mod.id);
        return installed != null && (installed.version == null || !installed.version.equals(mod.version));
    }

    /* ------------------------------------------------------------------ установка */

    public static Installed install(Context ctx, Mod mod, Listener listener, Cancel cancel) throws Exception {
        Prefs prefs = new Prefs(ctx);
        if (mod.url.isEmpty() && !"local".equals(mod.kind)) {
            throw new IOException("У мода нет адреса загрузки");
        }
        if (cancel == null) {
            cancel = NEVER;
        }
        File gameDir = new File(prefs.gameDir());
        if (!gameDir.exists() && !gameDir.mkdirs()) {
            throw new IOException("Не удалось открыть каталог игры: " + gameDir.getAbsolutePath());
        }
        if (!gameDir.canWrite()) {
            throw new IOException("Нет доступа к каталогу игры. Разрешите приложению доступ ко всем файлам в настройках.");
        }

        if (mod.size > 0) {
            long need = mod.size * 2 + 16L * 1024 * 1024;
            long free = gameDir.getUsableSpace();
            if (free > 0 && free < need) {
                throw new IOException("Мало места: нужно ~" + Ui.size(need) + ", свободно " + Ui.size(free));
            }
        }

        File tmp = new File(prefs.tmpDir());
        ensureDir(tmp);
        File downloaded = new File(tmp, mod.id + ".part");

        if (listener != null) {
            listener.onStage("Скачивание");
        }
        long total = mod.size;
        try {
            download(mod.url, downloaded, total, listener, cancel);
        } catch (Exception e) {
            downloaded.delete();
            throw e;
        }
        if (cancel.cancelled()) {
            downloaded.delete();
            throw new IOException("Отменено");
        }
        if (!mod.sha256.isEmpty() && !Hash.matches(downloaded, mod.sha256)) {
            downloaded.delete();
            throw new IOException("Контрольная сумма не совпала — файл повреждён или подменён. Установка прервана.");
        }

        Installed installed = new Installed();
        installed.id = mod.id;
        installed.name = mod.name;
        installed.version = mod.version;
        installed.kind = mod.kind;
        installed.target = mod.target;
        installed.installedAt = System.currentTimeMillis();

        if (listener != null) {
            listener.onStage("Установка");
        }
        if (mod.isZip()) {
            FileInputStream in = new FileInputStream(downloaded);
            try {
                extractZip(in, gameDir, mod.target, installed, prefs, listener, cancel);
            } finally {
                in.close();
            }
        } else {
            if (mod.target.isEmpty()) {
                downloaded.delete();
                throw new IOException("Для мода-файла не указан путь установки (target)");
            }
            File target = new File(gameDir, mod.target);
            Entry entry = new Entry(mod.target, "", !target.exists());
            backup(prefs, target, installed.id, 0, entry);
            ensureParent(target);
            copy(downloaded, target);
            installed.files.add(entry);
        }
        downloaded.delete();

        if (cancel.cancelled()) {
            rollback(ctx, installed);
            throw new IOException("Отменено");
        }

        Map<String, Installed> map = installed(ctx);
        map.put(installed.id, installed);
        saveInstalled(ctx, map);
        if (listener != null) {
            listener.onStage("Готово");
        }
        return installed;
    }

    /** Локальный архив: пользователь выбрал zip-файл, распаковываем в каталог игры. */
    public static Installed importZip(Context ctx, File zipFile, String displayName, Listener listener, Cancel cancel) throws Exception {
        Prefs prefs = new Prefs(ctx);
        if (cancel == null) {
            cancel = NEVER;
        }
        File gameDir = new File(prefs.gameDir());
        if (!gameDir.exists() && !gameDir.mkdirs()) {
            throw new IOException("Не удалось открыть каталог игры: " + gameDir.getAbsolutePath());
        }
        Installed installed = new Installed();
        installed.id = "local-" + System.currentTimeMillis();
        installed.name = displayName == null || displayName.isEmpty() ? "Локальный мод" : displayName;
        installed.version = "локальный";
        installed.kind = "zip";
        installed.target = "";
        installed.installedAt = System.currentTimeMillis();
        FileInputStream in = new FileInputStream(zipFile);
        try {
            extractZip(in, gameDir, "", installed, prefs, listener, cancel);
        } finally {
            in.close();
        }
        if (cancel.cancelled()) {
            rollback(ctx, installed);
            throw new IOException("Отменено");
        }
        Map<String, Installed> map = installed(ctx);
        map.put(installed.id, installed);
        saveInstalled(ctx, map);
        return installed;
    }

    /** Удаление мода: возвращаем прежние файлы из резервной копии, добавленные — стираем. */
    public static void uninstall(Context ctx, String modId) throws Exception {
        Prefs prefs = new Prefs(ctx);
        Map<String, Installed> map = installed(ctx);
        Installed installed = find(map, modId);
        if (installed == null) {
            return;
        }
        File gameDir = new File(prefs.gameDir());
        File backupRoot = new File(prefs.backupDir(), modId);
        for (Entry entry : installed.files) {
            File target = new File(gameDir, entry.path);
            if (entry.backup != null && !entry.backup.isEmpty()) {
                File backup = new File(prefs.dataDir(), entry.backup);
                if (backup.exists()) {
                    ensureParent(target);
                    copy(backup, target);
                }
            } else if (entry.added) {
                target.delete();
            }
        }
        deleteDir(backupRoot);
        map.remove(modId);
        saveInstalled(ctx, map);
    }

    private static void rollback(Context ctx, Installed installed) {
        try {
            Prefs prefs = new Prefs(ctx);
            File gameDir = new File(prefs.gameDir());
            for (Entry entry : installed.files) {
                File target = new File(gameDir, entry.path);
                if (entry.backup != null && !entry.backup.isEmpty()) {
                    File backup = new File(prefs.dataDir(), entry.backup);
                    if (backup.exists()) {
                        copy(backup, target);
                    }
                } else if (entry.added) {
                    target.delete();
                }
            }
            deleteDir(new File(prefs.backupDir(), installed.id));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /* ------------------------------------------------------------------ загрузка */

    private static void download(String url, File file, long knownSize, Listener listener, Cancel cancel) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(Api.TIMEOUT_MS);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "ModarSAMP/1.0 Android");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Сервер отдал файл с кодом " + code);
            }
            long total = conn.getContentLength() > 0 ? conn.getContentLength() : knownSize;
            InputStream in = new BufferedInputStream(conn.getInputStream());
            FileOutputStream out = new FileOutputStream(file);
            try {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (cancel.cancelled()) {
                        throw new IOException("Отменено");
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    if (listener != null) {
                        listener.onProgress(done, total);
                    }
                }
                out.flush();
            } finally {
                out.close();
                in.close();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /* ------------------------------------------------------------------ распаковка */

    private static void extractZip(InputStream raw, File gameDir, String target, Installed installed,
                                   Prefs prefs, Listener listener, Cancel cancel) throws Exception {
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw));
        int index = 0;
        long written = 0;
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (cancel.cancelled()) {
                    throw new IOException("Отменено");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.contains("..")) {
                    throw new IOException("Архив содержит недопустимый путь: " + name);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String relative = target.isEmpty() ? name : target + "/" + name;
                File out = new File(gameDir, relative);
                if (!out.getCanonicalPath().startsWith(gameDir.getCanonicalPath())) {
                    throw new IOException("Архив пытается писать за пределы каталога игры: " + name);
                }
                Mods.Entry journal = new Mods.Entry(relative, "", !out.exists());
                backup(prefs, out, installed.id, index, journal);
                index++;
                ensureParent(out);
                FileOutputStream stream = new FileOutputStream(out);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        stream.write(buffer, 0, read);
                        written += read;
                        if (listener != null) {
                            listener.onProgress(written, -1);
                        }
                    }
                    stream.flush();
                } finally {
                    stream.close();
                }
                zip.closeEntry();
                installed.files.add(journal);
            }
        } finally {
            zip.close();
        }
        if (installed.files.isEmpty()) {
            throw new IOException("В архиве нет файлов");
        }
    }

    private static void backup(Prefs prefs, File target, String modId, int index, Entry journal) throws Exception {
        if (!target.exists() || !target.isFile()) {
            return;
        }
        if (!prefs.backupMods()) {
            return;
        }
        File dir = new File(prefs.backupDir(), modId);
        ensureDir(dir);
        File backup = new File(dir, index + "_" + target.getName());
        copy(target, backup);
        journal.backup = prefs.backupDir() + "/" + modId + "/" + backup.getName();
        // в журнале храним путь относительно каталога данных
        journal.backup = journal.backup.substring(prefs.dataDir().length() + 1);
        journal.added = false;
    }

    /* ------------------------------------------------------------------ файлы */

    public static void ensureDir(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Не удалось создать каталог " + dir.getAbsolutePath());
        }
    }

    public static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            ensureDir(parent);
        }
    }

    public static void copy(File from, File to) throws IOException {
        ensureParent(to);
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDir(child);
            }
        }
        dir.delete();
    }

    public static String readText(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    public static void writeText(File file, String text) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    /** Размер установленных мод-паков на диске (для строки в настройках). */
    public static long installedCount(Context ctx) {
        return installed(ctx).size();
    }
}
