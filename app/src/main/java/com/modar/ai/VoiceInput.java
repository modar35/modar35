package com.modar.ai;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/** Голосовой ввод через системный распознаватель речи. */
public class VoiceInput {

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onStateChanged(boolean listening);

        void onError(String message);
    }

    private final Context ctx;
    private final String locale;
    private SpeechRecognizer recognizer;
    private Listener listener;
    private boolean listening;

    public VoiceInput(Context ctx, String locale) {
        this.ctx = ctx.getApplicationContext();
        this.locale = locale == null || locale.trim().isEmpty() ? "ru-RU" : locale.trim();
    }

    public static boolean isAvailable(Context ctx) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isListening() {
        return listening;
    }

    public void start(Listener l) {
        this.listener = l;
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        listening = true;
                        if (listener != null) {
                            listener.onStateChanged(true);
                        }
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {
                    }

                    @Override
                    public void onEndOfSpeech() {
                    }

                    @Override
                    public void onError(int error) {
                        listening = false;
                        String msg;
                        switch (error) {
                            case SpeechRecognizer.ERROR_NO_MATCH:
                            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                                msg = "Речь не распознана";
                                break;
                            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                                msg = "Нет разрешения на запись звука";
                                break;
                            case SpeechRecognizer.ERROR_NETWORK:
                            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                                msg = "Ошибка сети при распознавании";
                                break;
                            default:
                                msg = "Не удалось распознать речь (код " + error + ")";
                        }
                        if (listener != null) {
                            listener.onStateChanged(false);
                            listener.onError(msg);
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        listening = false;
                        ArrayList<String> texts = results == null
                                ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        String best = texts != null && !texts.isEmpty() ? texts.get(0) : "";
                        if (listener != null) {
                            listener.onStateChanged(false);
                            if (!best.isEmpty()) {
                                listener.onFinal(best);
                            }
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        ArrayList<String> texts = partialResults == null
                                ? null : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (texts != null && !texts.isEmpty() && listener != null) {
                            listener.onPartial(texts.get(0));
                        }
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) {
                    }
                });
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
            recognizer.startListening(intent);
            listening = true;
            if (listener != null) {
                listener.onStateChanged(true);
            }
        } catch (Throwable t) {
            listening = false;
            if (listener != null) {
                listener.onStateChanged(false);
                listener.onError("Распознавание речи недоступно: " + t.getMessage());
            }
        }
    }

    public void stop() {
        try {
            if (recognizer != null) {
                recognizer.stopListening();
            }
        } catch (Throwable ignored) {
        }
        listening = false;
        if (listener != null) {
            listener.onStateChanged(false);
        }
    }

    public void cancel() {
        try {
            if (recognizer != null) {
                recognizer.cancel();
            }
        } catch (Throwable ignored) {
        }
        listening = false;
    }

    public void destroy() {
        try {
            if (recognizer != null) {
                recognizer.destroy();
            }
        } catch (Throwable ignored) {
        }
        recognizer = null;
    }

    public static String defaultLocale() {
        return Locale.getDefault().toString().replace('_', '-');
    }
}
