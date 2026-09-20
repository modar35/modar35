package com.modar.family;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Голосовой вызов «родитель ↔ ребёнок».
 *
 * Родитель: приложение создаёт предложение соединения (WebRTC) и звонит ребёнку.
 * Ребёнок: видит «Родители звонят» и нажимает «Ответить» — только после этого
 * начинается разговор. Если приложение собрано без модуля WebRTC
 * (tools/fetch-webrtc.sh), кнопка предлагает обычный телефонный звонок.
 */
public class CallActivity extends Activity implements WebRtc.Events {

    public static final String EXTRA_CALL_ID = "callId";
    public static final String EXTRA_DEVICE_ID = "deviceId";
    public static final String EXTRA_ROLE = "role";
    public static final String ROLE_CHILD = "child";
    public static final String ROLE_PARENT = "parent";

    private String callId;
    private String deviceId;
    private String role;
    private WebRtc webrtc;
    private Handler handler;
    private long since;
    private boolean answered;
    private boolean finishing;
    private boolean muted;
    private TextView stateView;
    private Button answerButton;
    private Button muteButton;
    private int attempts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        role = getIntent().getStringExtra(EXTRA_ROLE);
        if (role == null) {
            role = ROLE_CHILD;
        }
        handler = new Handler();
        stateView = (TextView) findViewById(R.id.callState);
        answerButton = (Button) findViewById(R.id.callAnswer);
        muteButton = (Button) findViewById(R.id.callMute);
        ((TextView) findViewById(R.id.callName)).setText(
                ROLE_PARENT.equals(role) ? "Звонок ребёнку" : "Родители звонят");

        answerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                accept();
            }
        });
        findViewById(R.id.callHangup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                hangup();
            }
        });
        muteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                muted = !muted;
                if (webrtc != null) {
                    webrtc.setMuted(muted);
                }
                muteButton.setText(muted ? "🔇 Включить микрофон" : "🎙 Микрофон");
            }
        });
        Ui.setVisible(muteButton, false);

        if (ROLE_PARENT.equals(role)) {
            stateView.setText("Соединяем…");
            Ui.setVisible(answerButton, false);
            startAsParent();
        } else {
            stateView.setText("Нажмите «Ответить», чтобы поговорить");
            watchCallState();
        }
    }

    /* ----------------------------------------------------------- ребёнок */

    /** Следим, не завершил ли родитель вызов, пока ребёнок думает. */
    private void watchCallState() {
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject state = Http.request(CallActivity.this, "GET",
                            "/api/v1/call/poll?callId=" + callId + "&since=0", null);
                    final String value = state.optString("state", "");
                    if (("ended".equals(value) || "missed".equals(value)) && !answered) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(CallActivity.this, "Вызов завершён");
                                finishCall();
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!finishing && !answered) {
                            watchCallState();
                        }
                    }
                }, 3000);
            }
        });
    }

    private void accept() {
        if (!WebRtc.available()) {
            Ui.setVisible(answerButton, false);
            stateView.setText("В этой сборке голосовой вызов через интернет недоступен.\n" +
                    "Позвоните родителям обычным телефонным звонком — тревога уже отправлена.");
            sendAnswer(null, true);
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 70);
            return;
        }
        stateView.setText("Подключаемся…");
        Ui.setVisible(answerButton, false);
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject state = Http.request(CallActivity.this, "GET",
                            "/api/v1/call/poll?callId=" + callId + "&since=0", null);
                    final String offer = state.optString("offer", "");
                    if (offer.isEmpty() || "null".equals(offer)) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(CallActivity.this, "Вызов уже завершён");
                                finishCall();
                            }
                        });
                        return;
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                webrtc = new WebRtc(CallActivity.this, CallActivity.this);
                                webrtc.connect();
                                webrtc.setRemoteDescription(offer, false);
                                String answer = webrtc.createAnswer();
                                sendAnswer(answer, false);
                                answered = true;
                                Ui.setVisible(muteButton, true);
                                stateView.setText("Разговор идёт…");
                                AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
                                if (audio != null) {
                                    audio.setSpeakerphoneOn(true);
                                }
                                pollSignaling();
                            } catch (Exception e) {
                                stateView.setText("Не удалось начать разговор: " + e.getMessage());
                                sendAnswer(null, true);
                            }
                        }
                    });
                } catch (Exception e) {
                    Prefs.put(CallActivity.this, "call_error", String.valueOf(e.getMessage()));
                }
            }
        });
    }

    /* ------------------------------------------------------------ родитель */

    private void startAsParent() {
        if (!WebRtc.available()) {
            Ui.toast(this, "Голосовые вызовы недоступны: приложение собрано без модуля WebRTC.\n" +
                    "Отправим обычный звонок на телефон ребёнка.");
            Ui.bg(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("deviceId", deviceId);
                        body.put("cmd", "ring");
                        Http.post(CallActivity.this, "/api/v1/command", body);
                    } catch (Exception ignored) {
                    }
                }
            });
            finish();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 70);
            return;
        }
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    final WebRtc rtc = new WebRtc(CallActivity.this, CallActivity.this);
                    rtc.connect();
                    final String offer = rtc.createOffer();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            webrtc = rtc;
                            Ui.setVisible(muteButton, true);
                        }
                    });
                    JSONObject body = new JSONObject();
                    body.put("deviceId", deviceId);
                    body.put("offer", offer);
                    JSONObject started = Http.post(CallActivity.this, "/api/v1/call/start", body);
                    callId = started.optString("callId", "");
                    pollSignaling();
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            stateView.setText("Не удалось позвонить: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void sendAnswer(final String sdp, final boolean declined) {
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("callId", callId);
                    body.put("accepted", !declined);
                    body.put("answer", sdp == null ? JSONObject.NULL : sdp);
                    Http.post(CallActivity.this, "/api/v1/call/answer", body);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Обмен ICE-кандидатами и состоянием. */
    private void pollSignaling() {
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject state = Http.request(CallActivity.this, "GET",
                            "/api/v1/call/poll?callId=" + callId + "&since=" + since, null);
                    final JSONArray ice = state.optJSONArray("ice");
                    final String answer = state.optString("answer", "");
                    final String callState = state.optString("state", "");
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            since = System.currentTimeMillis();
                            if (webrtc != null) {
                                for (int i = 0; ice != null && i < ice.length(); i++) {
                                    JSONObject item = ice.optJSONObject(i);
                                    if (item != null && !role.equals(item.optString("from"))) {
                                        webrtc.addIce(item.optString("candidate"));
                                    }
                                }
                                if (ROLE_PARENT.equals(role) && !answered && answer.length() > 4 && !"null".equals(answer)) {
                                    webrtc.setRemoteDescription(answer, true);
                                    answered = true;
                                    stateView.setText("Разговор идёт…");
                                }
                            }
                            if ("ended".equals(callState) || "rejected".equals(callState)) {
                                finishCall();
                                return;
                            }
                            if (ROLE_PARENT.equals(role) && "ringing".equals(callState)) {
                                attempts += 1;
                                if (attempts == 40) {
                                    stateView.setText("Ребёнок не отвечает…");
                                }
                                if (attempts > 80) {
                                    Ui.toast(CallActivity.this, "Никто не ответил");
                                    finishCall();
                                }
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!finishing && !callId.isEmpty()) {
                            pollSignaling();
                        }
                    }
                }, 2000);
            }
        });
    }

    /* ------------------------------------------------------------- WebRTC */

    @Override
    public void onIce(final String candidateJson) {
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("callId", callId);
                    body.put("side", role);
                    body.put("candidate", candidateJson);
                    Http.post(CallActivity.this, "/api/v1/call/ice", body);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onConnected() {
        Ui.ui(new Runnable() {
            @Override
            public void run() {
                stateView.setText("Разговор идёт…");
            }
        });
    }

    @Override
    public void onFailed(final String reason) {
        Ui.ui(new Runnable() {
            @Override
            public void run() {
                stateView.setText(reason);
            }
        });
    }

    @Override
    public void onClosed() {
        finishCall();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 70) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (ROLE_PARENT.equals(role)) {
                startAsParent();
            } else {
                accept();
            }
        } else {
            Ui.toast(this, "Без доступа к микрофону разговор невозможен");
            sendAnswer(null, true);
            finishCall();
        }
    }

    private void hangup() {
        if (callId != null && !callId.isEmpty()) {
            Ui.bg(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("callId", callId);
                        Http.post(CallActivity.this, "/api/v1/call/end", body);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        if (!answered && ROLE_CHILD.equals(role)) {
            sendAnswer(null, true);
        }
        finishCall();
    }

    private void finishCall() {
        if (finishing) {
            return;
        }
        finishing = true;
        if (webrtc != null) {
            webrtc.close();
            webrtc = null;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        if (webrtc != null) {
            webrtc.close();
            webrtc = null;
        }
        super.onDestroy();
    }
}
