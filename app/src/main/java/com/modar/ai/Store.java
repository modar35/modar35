package com.modar.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Хранилище диалогов в SharedPreferences (JSON). Без интернета и без внешних баз. */
public class Store {

    private static final String FILE = "modar_chats";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_CURRENT = "current";

    private final SharedPreferences sp;

    public Store(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public synchronized List<Conversation> all() {
        List<Conversation> out = new ArrayList<Conversation>();
        String raw = sp.getString(KEY_ITEMS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(Conversation.fromJson(o));
                }
            }
        } catch (Exception ignored) {
            // повреждённые данные просто игнорируем
        }
        Collections.sort(out, new Comparator<Conversation>() {
            @Override
            public int compare(Conversation a, Conversation b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    public synchronized void saveAll(List<Conversation> items) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            try {
                arr.put(items.get(i).toJson());
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    public synchronized void save(Conversation c) {
        List<Conversation> items = all();
        boolean replaced = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(c.id)) {
                items.set(i, c);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            items.add(c);
        }
        saveAll(items);
    }

    public synchronized Conversation get(String id) {
        if (id == null) {
            return null;
        }
        List<Conversation> items = all();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                return items.get(i);
            }
        }
        return null;
    }

    public synchronized void delete(String id) {
        List<Conversation> items = all();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                break;
            }
        }
        saveAll(items);
    }

    public synchronized Conversation create() {
        Conversation c = new Conversation();
        c.title = "Новый диалог";
        c.updatedAt = System.currentTimeMillis();
        save(c);
        setCurrentId(c.id);
        return c;
    }

    public String currentId() {
        return sp.getString(KEY_CURRENT, null);
    }

    public void setCurrentId(String id) {
        sp.edit().putString(KEY_CURRENT, id).apply();
    }

    /** Возвращает текущий диалог, создавая его при необходимости. */
    public synchronized Conversation current() {
        Conversation c = get(currentId());
        if (c == null) {
            List<Conversation> items = all();
            if (items.isEmpty()) {
                c = create();
            } else {
                c = items.get(0);
                setCurrentId(c.id);
            }
        }
        return c;
    }
}
