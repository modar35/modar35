package com.modar.samp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Список серверов лаунчера: хранение, поиск, объединение со списком с сервера. */
public final class Servers {

    private static final String FILE = "modar_samp_servers";
    private static final String KEY = "servers";

    private Servers() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static List<Server> all(Context ctx) {
        List<Server> list = new ArrayList<Server>();
        try {
            JSONArray array = new JSONArray(sp(ctx).getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) {
                    Server server = Server.fromJson(json);
                    if (server.host != null && !server.host.isEmpty()) {
                        list.add(server);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sort(list);
        return list;
    }

    public static void save(Context ctx, List<Server> list) {
        JSONArray array = new JSONArray();
        List<Server> copy = new ArrayList<Server>(list);
        sort(copy);
        for (Server server : copy) {
            array.put(server.toJson());
        }
        sp(ctx).edit().putString(KEY, array.toString()).apply();
    }

    /** Сначала избранное, потом те, где есть игроки, затем по названию. */
    public static void sort(List<Server> list) {
        Collections.sort(list, new Comparator<Server>() {
            @Override
            public int compare(Server a, Server b) {
                if (a.favorite != b.favorite) {
                    return a.favorite ? -1 : 1;
                }
                int playersA = a.last == null ? -1 : a.last.players;
                int playersB = b.last == null ? -1 : b.last.players;
                if (playersA != playersB) {
                    return playersB - playersA;
                }
                return a.title().compareToIgnoreCase(b.title());
            }
        });
    }

    public static Server find(List<Server> list, String host, int port) {
        for (Server server : list) {
            if (server.port == port && server.host != null && server.host.equalsIgnoreCase(host)) {
                return server;
            }
        }
        return null;
    }

    public static Server byId(List<Server> list, String id) {
        if (id == null) {
            return null;
        }
        for (Server server : list) {
            if (id.equals(server.id)) {
                return server;
            }
        }
        return null;
    }

    /** Добавляет сервер; возвращает false, если такой адрес уже есть. */
    public static boolean add(Context ctx, Server server) {
        List<Server> list = all(ctx);
        if (find(list, server.host, server.port) != null) {
            return false;
        }
        if (server.id == null || server.id.isEmpty()) {
            server.id = newId(server.host, server.port);
        }
        server.addedAt = System.currentTimeMillis();
        list.add(server);
        save(ctx, list);
        return true;
    }

    public static void update(Context ctx, Server server) {
        List<Server> list = all(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(server.id)) {
                list.set(i, server);
                break;
            }
        }
        save(ctx, list);
    }

    public static void remove(Context ctx, String id) {
        List<Server> list = all(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
            }
        }
        save(ctx, list);
    }

    public static boolean toggleFavorite(Context ctx, String id) {
        List<Server> list = all(ctx);
        boolean state = false;
        for (Server server : list) {
            if (server.id.equals(id)) {
                server.favorite = !server.favorite;
                state = server.favorite;
                break;
            }
        }
        save(ctx, list);
        return state;
    }

    /**
     * Сливает список с сервера лаунчера: новые добавляет, у известных обновляет подпись,
     * а серверы, которых больше нет в списке API, удаляет (если их не добавили вручную).
     * Возвращает число новых серверов.
     */
    public static int mergeApi(Context ctx, List<Server> fromApi) {
        List<Server> local = all(ctx);
        int added = 0;

        for (Server remote : fromApi) {
            Server existing = find(local, remote.host, remote.port);
            if (existing == null) {
                remote.source = Server.SOURCE_API;
                if (remote.id == null || remote.id.isEmpty()) {
                    remote.id = newId(remote.host, remote.port);
                }
                if (remote.addedAt == 0) {
                    remote.addedAt = System.currentTimeMillis();
                }
                local.add(remote);
                added++;
            } else {
                existing.gamemode = remote.gamemode;
                existing.note = remote.note;
                if (existing.name == null || existing.name.isEmpty()) {
                    existing.name = remote.name;
                }
                if (existing.password.isEmpty()) {
                    existing.password = remote.password;
                }
                if (!Server.SOURCE_MANUAL.equals(existing.source)) {
                    existing.source = Server.SOURCE_API;
                }
            }
        }

        for (int i = local.size() - 1; i >= 0; i--) {
            Server server = local.get(i);
            if (!Server.SOURCE_API.equals(server.source)) {
                continue;
            }
            boolean stillThere = false;
            for (Server remote : fromApi) {
                if (remote.port == server.port && remote.host.equalsIgnoreCase(server.host)) {
                    stillThere = true;
                    break;
                }
            }
            if (!stillThere) {
                local.remove(i);
            }
        }

        save(ctx, local);
        return added;
    }

    public static String newId(String host, int port) {
        return (host + "_" + port).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
    }

    public static int count(List<Server> list, boolean onlyOnline) {
        if (!onlyOnline) {
            return list.size();
        }
        int count = 0;
        for (Server server : list) {
            if (server.online()) {
                count++;
            }
        }
        return count;
    }

    public static List<Server> filter(List<Server> source, String query) {
        if (query == null || query.trim().isEmpty()) {
            return source;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Server> result = new ArrayList<Server>();
        for (Server server : source) {
            String haystack = (server.title() + " " + server.address() + " " + server.gamemodeLabel()).toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) {
                result.add(server);
            }
        }
        return result;
    }
}
