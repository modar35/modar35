package com.modar.riftarena;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Small optional Android TTS bridge used for hero-specific Russian voice barks. */
public final class RiftSpeechPlugin {
    private static TextToSpeech engine;
    private static boolean ready;
    private static String queuedText;
    private static float queuedPitch = 1f;
    private static float queuedRate = 1f;

    private RiftSpeechPlugin() { }

    public static synchronized void speak(final Activity activity, final String text,
                                          final float pitch, final float rate) {
        if (activity == null || text == null || text.trim().isEmpty()) return;
        queuedText = text;
        queuedPitch = clamp(pitch, .72f, 1.35f);
        queuedRate = clamp(rate, .78f, 1.12f);
        if (engine == null) {
            Context context = activity.getApplicationContext();
            engine = new TextToSpeech(context, status -> {
                synchronized (RiftSpeechPlugin.class) {
                    ready = status == TextToSpeech.SUCCESS;
                    if (ready) {
                        int language = engine.setLanguage(new Locale("ru", "RU"));
                        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine.setLanguage(new Locale("ru"));
                        }
                        chooseDistinctRussianVoice();
                    }
                    if (ready && queuedText != null) playQueued();
                }
            });
        } else if (ready) {
            new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (RiftSpeechPlugin.class) { playQueued(); }
            });
        }
    }

    private static void chooseDistinctRussianVoice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || engine == null) return;
        try {
            List<Voice> candidates = new ArrayList<>();
            for (Voice voice : engine.getVoices()) {
                Locale locale = voice.getLocale();
                if (locale != null && "ru".equalsIgnoreCase(locale.getLanguage()) && !voice.isNetworkConnectionRequired()) {
                    candidates.add(voice);
                }
            }
            Collections.sort(candidates, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            if (!candidates.isEmpty()) {
                // Pitch/rate provide a distinct character even when a device only has one Russian voice.
                engine.setVoice(candidates.get(0));
            }
        } catch (Exception ignored) {
            // Keep the system's currently selected voice if this engine exposes no Russian voice list.
        }
    }

    private static void playQueued() {
        if (!ready || engine == null || queuedText == null) return;
        String text = queuedText;
        queuedText = null;
        engine.setPitch(queuedPitch);
        engine.setSpeechRate(queuedRate);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rift-hero-line");
        } else {
            // Compatibility path for Android versions below API 21.
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
