package com.modar.family;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.Size;
import android.view.Surface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Фото с камеры телефона ребёнка по запросу родителя.
 * Работает как служба переднего плана с типом «camera» — так Android разрешает
 * доступ к камере, когда приложение свёрнуто.
 */
public class CaptureService extends Service {

    public static final String ACTION_START = "com.modar.family.CAPTURE_START";
    private static final String CHANNEL = "modar_camera";
    private static final int NOTIF_ID = 1003;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;

    public static void start(Context ctx, String camera) {
        Intent intent = new Intent(ctx, CaptureService.class);
        intent.setAction(ACTION_START);
        intent.putExtra("camera", camera == null ? "" : camera);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("modar-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        notifyForeground();
        final String which = intent == null ? "" : intent.getStringExtra("camera");
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ChildService.event(this, "camera", "Фото не сделано: нет разрешения на камеру", "warn");
            stopSelf();
            return START_NOT_STICKY;
        }
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                capture(which);
            }
        });
        return START_NOT_STICKY;
    }

    private void notifyForeground() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Камера", NotificationManager.IMPORTANCE_MIN);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Modar Family")
                .setContentText("Съёмка по запросу родителя")
                .setSmallIcon(R.drawable.ic_shield)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void capture(String which) {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = chooseCamera(manager, which);
            if (cameraId == null) {
                stopSelf();
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size size = chooseSize(characteristics);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    android.graphics.ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader source) {
                    Image image = null;
                    try {
                        image = source.acquireLatestImage();
                        if (image == null) {
                            return;
                        }
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        upload(bytes);
                    } catch (Exception e) {
                        Prefs.put(CaptureService.this, "camera_error", String.valueOf(e.getMessage()));
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                        close();
                    }
                }
            }, handler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera = device;
                    shoot();
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    camera = null;
                    stopSelf();
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    camera = null;
                    ChildService.event(CaptureService.this, "camera", "Камера недоступна (код " + error + ")", "warn");
                    stopSelf();
                }
            }, handler);
        } catch (SecurityException e) {
            ChildService.event(this, "camera", "Нет доступа к камере", "warn");
            stopSelf();
        } catch (Exception e) {
            ChildService.event(this, "camera", "Ошибка камеры: " + e.getMessage(), "warn");
            stopSelf();
        }
    }

    private String chooseCamera(CameraManager manager, String which) throws CameraAccessException {
        String front = null;
        String back = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null) {
                continue;
            }
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                front = id;
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                back = id;
            }
        }
        if ("back".equals(which)) {
            return back != null ? back : front;
        }
        return front != null ? front : back;
    }

    private Size chooseSize(CameraCharacteristics characteristics) {
        android.util.Size[] sizes = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(android.graphics.ImageFormat.JPEG);
        Size best = new Size(1280, 720);
        int bestScore = Integer.MAX_VALUE;
        if (sizes != null) {
            for (android.util.Size size : sizes) {
                int score = Math.abs(size.getWidth() - 1280) + Math.abs(size.getHeight() - 720);
                if (size.getWidth() <= 1920 && score < bestScore) {
                    bestScore = score;
                    best = new Size(size.getWidth(), size.getHeight());
                }
            }
        }
        return best;
    }

    private void shoot() {
        try {
            final CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            List<Surface> surfaces = new ArrayList<Surface>();
            surfaces.add(reader.getSurface());
            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    try {
                        configured.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                                Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                                if (af != null && af == CaptureResult.CONTROL_AF_STATE_INACTIVE) {
                                    // ничего: ждём кадр от ImageReader
                                }
                            }
                        }, handler);
                    } catch (Exception e) {
                        stopSelf();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession configured) {
                    ChildService.event(CaptureService.this, "camera", "Не удалось подготовить камеру", "warn");
                    stopSelf();
                }
            }, handler);
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void upload(byte[] jpeg) {
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            byte[] payload = jpeg;
            if (bitmap != null) {
                int maxWidth = 900;
                if (bitmap.getWidth() > maxWidth) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                            maxWidth, Math.round(bitmap.getHeight() * (maxWidth / (float) bitmap.getWidth())), true);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, out);
                    payload = out.toByteArray();
                    scaled.recycle();
                } else {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);
                    payload = out.toByteArray();
                }
                bitmap.recycle();
            }
            JSONObject body = new JSONObject();
            body.put("kind", "camera");
            body.put("data", "data:image/jpeg;base64," + Base64.encodeToString(payload, Base64.NO_WRAP));
            Http.post(this, "/api/v1/device/image", body);
            ChildService.event(this, "camera", "Фото с камеры отправлено родителям", "info");
        } catch (Exception e) {
            Prefs.put(this, "camera_error", String.valueOf(e.getMessage()));
        }
    }

    private void close() {
        try {
            if (session != null) {
                session.close();
                session = null;
            }
            if (camera != null) {
                camera.close();
                camera = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
        } catch (Exception ignored) {
        }
        Ui.ui(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        });
    }

    @Override
    public void onDestroy() {
        close();
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }
}
