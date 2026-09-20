package com.modar.ai;

import org.json.JSONException;
import org.json.JSONObject;

/** Одно сообщение в диалоге. */
public class Message {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public String role = ROLE_USER;
    public String text = "";
    /** Путь к локальной копии картинки (вложение пользователя), может быть null. */
    public String imagePath;
    public long time = System.currentTimeMillis();
    /** Сообщение об ошибке сети/API — рисуется красным. */
    public boolean error;
    /** Ответ ещё печатается. */
    public boolean streaming;
    /** Об этом сообщении не нужно рассказывать модели (например, ошибка). */
    public boolean skipForModel;

    public Message() {
    }

    public Message(String role, String text) {
        this.role = role;
        this.text = text;
    }

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("text", text);
        o.put("time", time);
        if (imagePath != null) {
            o.put("image", imagePath);
        }
        if (error) {
            o.put("error", true);
        }
        return o;
    }

    public static Message fromJson(JSONObject o) {
        Message m = new Message();
        m.role = o.optString("role", ROLE_USER);
        m.text = o.optString("text", "");
        m.imagePath = o.optString("image", null);
        m.time = o.optLong("time", System.currentTimeMillis());
        m.error = o.optBoolean("error", false);
        return m;
    }
}
