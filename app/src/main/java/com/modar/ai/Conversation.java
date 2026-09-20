package com.modar.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Диалог (беседа) со списком сообщений. */
public class Conversation {

    public String id = UUID.randomUUID().toString();
    public String title = "";
    public long updatedAt = System.currentTimeMillis();
    public final List<Message> messages = new ArrayList<Message>();

    public Message lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public String preview() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            String t = messages.get(i).text;
            if (t != null && !t.trim().isEmpty()) {
                t = t.replace('\n', ' ').trim();
                return t.length() > 80 ? t.substring(0, 80) + "…" : t;
            }
        }
        return "Пустой диалог";
    }

    /** Заголовок из первого вопроса пользователя. */
    public void autoTitle() {
        if (title != null && !title.trim().isEmpty()) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.isUser() && m.text != null && !m.text.trim().isEmpty()) {
                String t = m.text.replace('\n', ' ').trim();
                title = t.length() > 40 ? t.substring(0, 40) + "…" : t;
                return;
            }
        }
        title = "Новый диалог";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title == null ? "" : title);
        o.put("updated", updatedAt);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.streaming) {
                continue;
            }
            arr.put(m.toJson());
        }
        o.put("messages", arr);
        return o;
    }

    public static Conversation fromJson(JSONObject o) {
        Conversation c = new Conversation();
        c.id = o.optString("id", UUID.randomUUID().toString());
        c.title = o.optString("title", "");
        c.updatedAt = o.optLong("updated", System.currentTimeMillis());
        JSONArray arr = o.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mo = arr.optJSONObject(i);
                if (mo != null) {
                    c.messages.add(Message.fromJson(mo));
                }
            }
        }
        return c;
    }
}
