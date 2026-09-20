package com.modar.ai;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/** Озвучка ответов ассистента (системный TTS). */
public class Tts {

    public interface StateListener {
        void onState(boolean ready, String error);
    }

    private TextToSpeech tts;
    private boolean ready;
    private boolean enabled;
    private String error;
    private String locale = "ru-RU";

    public Tts(Context ctx, String localeTag) {
        this.locale = localeTag == null || localeTag.trim().isEmpty() ? "ru-RU" : localeTag.trim();
        this.tts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    applyLocale();
                    ready = true;
                } else {
                    error = "Синтез речи недоступен на этом устройстве";
                }
            }
        });
    }

    private void applyLocale() {
        if (tts == null) {
            return;
        }
        try {
            Locale l = Locale.forLanguageTag(locale);
            int res = tts.setLanguage(l);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // пробуем язык без региона
                String[] parts = locale.split("-");
                if (parts.length > 1) {
                    tts.setLanguage(new Locale(parts[0]));
                }
            }
            tts.setSpeechRate(1.0f);
        } catch (Throwable ignored) {
        }
    }

    public void setEnabled(boolean v) {
        enabled = v;
        if (!v) {
            stop();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isReady() {
        return ready;
    }

    public String error() {
        return error;
    }

    public void setLocale(String tag) {
        if (tag != null && !tag.trim().isEmpty()) {
            locale = tag.trim();
            applyLocale();
        }
    }

    public void speak(String text) {
        if (!enabled || !ready || tts == null) {
            return;
        }
        String plain = Markdown.toPlainText(text);
        if (plain.isEmpty()) {
            return;
        }
        if (plain.length() > 4000) {
            plain = plain.substring(0, 4000);
        }
        tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "modar-ai");
    }

    public void stop() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    public void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
            ready = false;
        }
    }

    /** Заглушка, чтобы подписка на прогресс не падала на старых API. */
    @SuppressWarnings("unused")
    private void attachProgressListener(UtteranceProgressListener l) {
        if (tts != null) {
            tts.setOnUtteranceProgressListener(l);
        }
    }
}
