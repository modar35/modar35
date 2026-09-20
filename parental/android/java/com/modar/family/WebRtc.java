package com.modar.family;

import android.content.Context;

import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Голосовая связь «родитель ↔ ребёнок» через WebRTC.
 *
 * Модуль подключается отдельно (см. tools/fetch-webrtc.sh): он весит несколько
 * мегабайт и содержит нативные библиотеки. Если модуль не подключён, классы
 * org.webrtc отсутствуют — приложение сообщит об этом и предложит обычный звонок.
 *
 * Обёртка сделана на рефлексии, чтобы приложение собиралось и без модуля.
 */
public class WebRtc {

    public interface Events {
        /** Новый ICE-кандидат — его нужно отправить второй стороне. */
        void onIce(String candidateJson);

        void onConnected();

        void onFailed(String reason);

        void onClosed();
    }

    private final Events events;
    private Object factory;
    private Object peerConnection;
    private Object audioSource;
    private Object audioTrack;
    private Object localDescription;
    private Object initializationOptions;
    private boolean closed;

    public static boolean available() {
        try {
            Class.forName("org.webrtc.PeerConnectionFactory");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public WebRtc(Context ctx, Events events) {
        this.events = events;
        setUp(ctx);
    }

    /* ------------------------------------------------------------- настройка */

    private void setUp(Context ctx) {
        try {
            Class<?> optionsClass = Class.forName("org.webrtc.PeerConnectionFactory$InitializationOptions");
            Class<?> builderClass = Class.forName("org.webrtc.PeerConnectionFactory$InitializationOptions$Builder");
            Object builder = optionsClass.getMethod("builder", Context.class).invoke(null, ctx);
            Object options = builderClass.getMethod("createInitializationOptions").invoke(builder);
            initializationOptions = options;
            Class.forName("org.webrtc.PeerConnectionFactory")
                    .getMethod("initialize", optionsClass).invoke(null, options);

            Class<?> factoryClass = Class.forName("org.webrtc.PeerConnectionFactory");
            try {
                Object factoryBuilder = factoryClass.getMethod("builder").invoke(null);
                factoryBuilder.getClass().getMethod("setOptions", optionsClass).invoke(factoryBuilder, options);
                factory = factoryBuilder.getClass().getMethod("createPeerConnectionFactory").invoke(factoryBuilder);
            } catch (Throwable ignored) {
                factory = factoryClass.getConstructor().newInstance();
            }

            Class<?> constraintsClass = Class.forName("org.webrtc.MediaConstraints");
            Object constraints = constraintsClass.getConstructor().newInstance();

            // Источник звука и дорожка
            audioSource = factory.getClass()
                    .getMethod("createAudioSource", constraintsClass).invoke(factory, constraints);
            audioTrack = factory.getClass()
                    .getMethod("createAudioTrack", String.class, Class.forName("org.webrtc.AudioSource"))
                    .invoke(factory, "audio0", audioSource);
            // createAudioTrack(String, AudioTrack) — сигнатура источника AudioSource
            if (audioTrack == null) {
                throw new IllegalStateException("Не удалось создать аудиодорожку");
            }
        } catch (Throwable t) {
            throw new RuntimeException("WebRTC-модуль не подключён: " + t.getMessage(), t);
        }
    }

    private Object iceServer(String url) throws Exception {
        Class<?> iceServerClass = Class.forName("org.webrtc.PeerConnection$IceServer");
        Object builder = iceServerClass.getMethod("builder", String.class).invoke(null, url);
        return builder.getClass().getMethod("createIceServer").invoke(builder);
    }

    private Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    /** Создаёт соединение и добавляет локальную звуковую дорожку. */
    public void connect() {
        try {
            Class<?> iceServerClass = Class.forName("org.webrtc.PeerConnection$IceServer");
            Class<?> configurationClass = Class.forName("org.webrtc.PeerConnection$RTCConfiguration");
            Class<?> observerClass = Class.forName("org.webrtc.PeerConnection$Observer");

            List<Object> servers = new ArrayList<Object>();
            servers.add(iceServer("stun:stun.l.google.com:19302"));
            servers.add(iceServer("stun:stun1.l.google.com:19302"));
            Object configuration = configurationClass.getConstructor(List.class).newInstance(servers);

            Object observer = proxy(observerClass, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    try {
                        if ("onIceCandidate".equals(name) && args != null && args.length > 0 && args[0] != null) {
                            Object candidate = args[0];
                            String sdpMid = (String) candidate.getClass().getField("sdpMid").get(candidate);
                            int index = candidate.getClass().getField("sdpMLineIndex").getInt(candidate);
                            String sdp = (String) candidate.getClass().getField("sdp").get(candidate);
                            JSONObject json = new JSONObject();
                            json.put("sdpMid", sdpMid);
                            json.put("sdpMLineIndex", index);
                            json.put("candidate", sdp);
                            events.onIce(json.toString());
                        } else if ("onIceConnectionChange".equals(name) && args != null && args.length > 0) {
                            String state = String.valueOf(args[0]);
                            if (state.contains("CONNECTED") || state.contains("COMPLETED")) {
                                events.onConnected();
                            } else if (state.contains("FAILED") || state.contains("DISCONNECTED")) {
                                events.onFailed("Соединение не установлено (" + state + ")");
                            } else if (state.contains("CLOSED")) {
                                events.onClosed();
                            }
                        } else if ("onConnectionChange".equals(name) && args != null && args.length > 0) {
                            String state = String.valueOf(args[0]);
                            if (state.contains("CONNECTED")) {
                                events.onConnected();
                            }
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    return null;
                }
            });

            peerConnection = factory.getClass()
                    .getMethod("createPeerConnection", configurationClass, observerClass)
                    .invoke(factory, configuration, observer);

            peerConnection.getClass()
                    .getMethod("addTrack", Class.forName("org.webrtc.MediaStreamTrack"), List.class)
                    .invoke(peerConnection, audioTrack, Arrays.asList("modar-stream"));
        } catch (Throwable t) {
            throw new RuntimeException("Не удалось создать соединение: " + t.getMessage(), t);
        }
    }

    /* --------------------------------------------------------------- сигналинг */

    /** Создать предложение (вызывающая сторона) и вернуть SDP. */
    public String createOffer() {
        return createDescription(true);
    }

    /** Создать ответ на предложение (принимающая сторона) и вернуть SDP. */
    public String createAnswer() {
        return createDescription(false);
    }

    private String createDescription(final boolean offer) {
        try {
            Class<?> observerClass = Class.forName("org.webrtc.SdpObserver");
            Class<?> constraintsClass = Class.forName("org.webrtc.MediaConstraints");
            final String[] result = new String[1];
            final Throwable[] failure = new Throwable[1];
            Object observer = proxy(observerClass, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onCreateSuccess".equals(method.getName()) && args != null && args.length > 0) {
                        try {
                            Object description = args[0];
                            result[0] = (String) description.getClass().getField("description").get(description);
                            localDescription = description;
                        } catch (Throwable t) {
                            failure[0] = t;
                        }
                    } else if ("onCreateFailure".equals(method.getName()) && args != null && args.length > 0) {
                        failure[0] = new RuntimeException(String.valueOf(args[0]));
                    } else if ("onSetFailure".equals(method.getName()) && args != null && args.length > 0) {
                        failure[0] = new RuntimeException(String.valueOf(args[0]));
                    }
                    return null;
                }
            });
            Object constraints = constraintsClass.getConstructor().newInstance();
            String method = offer ? "createOffer" : "createAnswer";
            peerConnection.getClass().getMethod(method, observerClass, constraintsClass)
                    .invoke(peerConnection, observer, constraints);

            for (int i = 0; i < 100 && result[0] == null && failure[0] == null; i++) {
                Thread.sleep(50);
            }
            if (failure[0] != null) {
                throw new RuntimeException(failure[0].getMessage());
            }
            if (result[0] == null) {
                throw new RuntimeException("Истекло время ожидания описания соединения");
            }
            // Локальное описание нужно применить сразу
            Class<?> descriptionClass = Class.forName("org.webrtc.SessionDescription");
            peerConnection.getClass().getMethod("setLocalDescription", observerClass, descriptionClass)
                    .invoke(peerConnection, observer, localDescription);
            return result[0];
        } catch (Exception e) {
            throw new RuntimeException("Ошибка подготовки соединения: " + e.getMessage(), e);
        }
    }

    /** Применить SDP второй стороны. */
    public void setRemoteDescription(String sdp, boolean isAnswer) {
        try {
            Class<?> typeClass = Class.forName("org.webrtc.SessionDescription$Type");
            Object type = null;
            for (Object value : typeClass.getEnumConstants()) {
                if (String.valueOf(value).equals(isAnswer ? "ANSWER" : "OFFER")) {
                    type = value;
                }
            }
            Class<?> descriptionClass = Class.forName("org.webrtc.SessionDescription");
            Object description = descriptionClass.getConstructor(typeClass, String.class).newInstance(type, sdp);
            Class<?> observerClass = Class.forName("org.webrtc.SdpObserver");
            Object observer = proxy(observerClass, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if (method.getName().contains("Failure") && args != null && args.length > 0) {
                        events.onFailed(String.valueOf(args[0]));
                    }
                    return null;
                }
            });
            peerConnection.getClass().getMethod("setRemoteDescription", observerClass, descriptionClass)
                    .invoke(peerConnection, observer, description);
        } catch (Exception e) {
            events.onFailed("Не удалось применить описание: " + e.getMessage());
        }
    }

    /** Добавить ICE-кандидата второй стороны (JSON: sdpMid, sdpMLineIndex, candidate). */
    public void addIce(String candidateJson) {
        try {
            JSONObject json = new JSONObject(candidateJson);
            Class<?> candidateClass = Class.forName("org.webrtc.IceCandidate");
            Object candidate = candidateClass
                    .getConstructor(String.class, int.class, String.class)
                    .newInstance(json.optString("sdpMid", "0"), json.optInt("sdpMLineIndex", 0),
                            json.optString("candidate", ""));
            peerConnection.getClass().getMethod("addIceCandidate", candidateClass)
                    .invoke(peerConnection, candidate);
        } catch (Exception ignored) {
        }
    }

    /** Микрофон включён/выключен. */
    public void setMuted(boolean muted) {
        try {
            audioTrack.getClass().getMethod("setEnabled", boolean.class).invoke(audioTrack, !muted);
        } catch (Exception ignored) {
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (peerConnection != null) {
                peerConnection.getClass().getMethod("close").invoke(peerConnection);
                peerConnection = null;
            }
            if (factory != null) {
                factory.getClass().getMethod("dispose").invoke(factory);
                factory = null;
            }
        } catch (Exception ignored) {
        }
    }
}
