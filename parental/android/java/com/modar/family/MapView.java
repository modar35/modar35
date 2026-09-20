package com.modar.family;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Карта без внешних библиотек: тайлы OpenStreetMap рисуются прямо на Canvas.
 * Поддерживает перетаскивание, зум, маршрут ребёнка, метки детей и геозоны.
 */
public class MapView extends View {

    public interface Listener {
        /** Долгое нажатие по карте — сюда добавим новую геозону. */
        void onMapLongPress(double lat, double lon);

        void onMarkerTap(String deviceId);
    }

    private static final int TILE = 256;
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 18;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(160) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private static final Set<String> LOADING = new HashSet<String>();

    private double centerLat = 52.3702;
    private double centerLon = 4.8952;
    private float zoom = 12;
    private List<double[]> track = new ArrayList<double[]>();
    private List<JSONObject> places = new ArrayList<JSONObject>();
    private List<JSONObject> devices = new ArrayList<JSONObject>();
    private String selectedId;
    private Listener listener;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attribution = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint();

    private float lastX, lastY;
    private long lastTap;
    private float downX, downY;
    private float pinchStart = 0;
    private float pinchZoom = 0;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            longPress(downX, downY);
        }
    };

    public MapView(Context ctx) {
        super(ctx);
        init();
    }

    public MapView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        trackShadow.setStyle(Paint.Style.STROKE);
        trackShadow.setStrokeWidth(Ui.dp(getContext(), 8));
        trackShadow.setColor(0x33000000);
        trackShadow.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(Ui.dp(getContext(), 4));
        trackPaint.setColor(0xFF6C8CFF);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        placeFill.setStyle(Paint.Style.FILL);
        placeFill.setColor(0x226C8CFF);
        placeStroke.setStyle(Paint.Style.STROKE);
        placeStroke.setStrokeWidth(Ui.dp(getContext(), 2));
        placeStroke.setColor(0xAA6C8CFF);
        markerPaint.setColor(0xFF6C8CFF);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(Ui.dp(getContext(), 12));
        textPaint.setTextAlign(Paint.Align.CENTER);
        attribution.setColor(0xFF9AA6C4);
        attribution.setTextSize(Ui.dp(getContext(), 10));
        placeholderPaint.setColor(0xFF0E1428);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTrack(List<double[]> points) {
        track = points == null ? new ArrayList<double[]>() : points;
        invalidate();
    }

    public void setPlaces(List<JSONObject> places) {
        this.places = places == null ? new ArrayList<JSONObject>() : places;
        invalidate();
    }

    public void setDevices(List<JSONObject> devices, String selectedId) {
        this.devices = devices == null ? new ArrayList<JSONObject>() : devices;
        this.selectedId = selectedId;
        invalidate();
    }

    public void setCenter(double lat, double lon, float zoom) {
        centerLat = lat;
        centerLon = lon;
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        invalidate();
    }

    public float getZoom() {
        return zoom;
    }

    /** Показать все точки маршрута и метки. */
    public void fitAll() {
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        boolean any = false;
        for (double[] point : track) {
            minLat = Math.min(minLat, point[0]);
            maxLat = Math.max(maxLat, point[0]);
            minLon = Math.min(minLon, point[1]);
            maxLon = Math.max(maxLon, point[1]);
            any = true;
        }
        for (JSONObject device : devices) {
            if (device.has("lat")) {
                minLat = Math.min(minLat, device.optDouble("lat", 0));
                maxLat = Math.max(maxLat, device.optDouble("lat", 0));
                minLon = Math.min(minLon, device.optDouble("lon", 0));
                maxLon = Math.max(maxLon, device.optDouble("lon", 0));
                any = true;
            }
        }
        if (!any || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        centerLat = (minLat + maxLat) / 2;
        centerLon = (minLon + maxLon) / 2;
        double spanLat = Math.max(0.002, maxLat - minLat);
        double spanLon = Math.max(0.002, maxLon - minLon);
        float zoomLat = (float) (Math.log(360.0 / (spanLat * 1.4) * (getHeight() / (double) TILE)) / Math.log(2));
        float zoomLon = (float) (Math.log(360.0 / (spanLon * 1.4) * (getWidth() / (double) TILE) / 2.0) / Math.log(2));
        zoom = Math.max(MIN_ZOOM, Math.min(15, Math.min(zoomLat, zoomLon) + 1));
        invalidate();
    }

    /* -------------------------------------------------------------- отрисовка */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF0A0F1E);

        float tileZoom = (float) Math.floor(zoom);
        float scaleFactor = (float) Math.pow(2, zoom - tileZoom);
        double worldSize = TILE * Math.pow(2, tileZoom);

        double centerX = lonToX(centerLon, tileZoom);
        double centerY = latToY(centerLat, tileZoom);
        float offsetX = (float) (getWidth() / 2 - centerX * scaleFactor);
        float offsetY = (float) (getHeight() / 2 - centerY * scaleFactor);
        float tileSize = TILE * scaleFactor;

        int firstCol = (int) Math.floor(-offsetX / tileSize);
        int lastCol = (int) Math.ceil((getWidth() - offsetX) / tileSize);
        int firstRow = (int) Math.floor(-offsetY / tileSize);
        int lastRow = (int) Math.ceil((getHeight() - offsetY) / tileSize);
        int maxIndex = 1 << (int) tileZoom;

        for (int col = firstCol; col <= lastCol; col++) {
            for (int row = firstRow; row <= lastRow; row++) {
                if (row < 0 || row >= maxIndex) {
                    continue;
                }
                int wrappedCol = ((col % maxIndex) + maxIndex) % maxIndex;
                float left = offsetX + col * tileSize;
                float top = offsetY + row * tileSize;
                Bitmap bitmap = tile((int) tileZoom, wrappedCol, row);
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, new android.graphics.RectF(
                            left, top, left + tileSize, top + tileSize), null);
                } else {
                    canvas.drawRect(left, top, left + tileSize, top + tileSize, placeholderPaint);
                }
            }
        }

        // Геозоны
        for (JSONObject place : places) {
            float[] point = toScreen(place.optDouble("lat", 0), place.optDouble("lon", 0),
                    tileZoom, scaleFactor, offsetX, offsetY);
            double radius = place.optDouble("radius", 300);
            float pixels = (float) (radius / (156543.03392 * Math.cos(Math.toRadians(centerLat))
                    / Math.pow(2, zoom)));
            canvas.drawCircle(point[0], point[1], Math.max(8, pixels), placeFill);
            canvas.drawCircle(point[0], point[1], Math.max(8, pixels), placeStroke);
            canvas.drawText(place.optString("name", ""), point[0], point[1] + Ui.dp(getContext(), 3), textPaint);
        }

        // Маршрут
        if (track.size() > 1) {
            Path path = new Path();
            boolean first = true;
            for (double[] item : track) {
                float[] point = toScreen(item[0], item[1], tileZoom, scaleFactor, offsetX, offsetY);
                if (first) {
                    path.moveTo(point[0], point[1]);
                    first = false;
                } else {
                    path.lineTo(point[0], point[1]);
                }
            }
            canvas.drawPath(path, trackShadow);
            canvas.drawPath(path, trackPaint);
            float[] start = toScreen(track.get(0)[0], track.get(0)[1], tileZoom, scaleFactor, offsetX, offsetY);
            float[] end = toScreen(track.get(track.size() - 1)[0], track.get(track.size() - 1)[1],
                    tileZoom, scaleFactor, offsetX, offsetY);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFF37D6B0);
            canvas.drawCircle(start[0], start[1], Ui.dp(getContext(), 5), dot);
            dot.setColor(0xFFFF5D6C);
            canvas.drawCircle(end[0], end[1], Ui.dp(getContext(), 5), dot);
        }

        // Дети
        for (JSONObject device : devices) {
            if (!device.has("lat") || device.isNull("lat")) {
                continue;
            }
            float[] point = toScreen(device.optDouble("lat", 0), device.optDouble("lon", 0),
                    tileZoom, scaleFactor, offsetX, offsetY);
            boolean online = device.optBoolean("online", false);
            boolean selected = device.optString("id", "").equals(selectedId);
            float radius = Ui.dp(getContext(), selected ? 13 : 10);
            markerPaint.setColor(online ? (selected ? 0xFF6C8CFF : 0xFF4A63B8) : 0xFF8C93A8);
            canvas.drawCircle(point[0], point[1], radius + Ui.dp(getContext(), 3), markerPaint);
            markerPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(point[0], point[1], radius, markerPaint);
            markerPaint.setColor(online ? 0xFF1B2559 : 0xFF8C93A8);
            canvas.drawCircle(point[0], point[1], radius - Ui.dp(getContext(), 4), markerPaint);
            canvas.drawText(device.optString("name", ""), point[0], point[1] - radius - Ui.dp(getContext(), 6), textPaint);
        }

        canvas.drawText("© OpenStreetMap", Ui.dp(getContext(), 6), getHeight() - Ui.dp(getContext(), 6), attribution);
    }

    private float[] toScreen(double lat, double lon, float tileZoom, float scaleFactor,
                             float offsetX, float offsetY) {
        double x = lonToX(lon, tileZoom) * scaleFactor + offsetX;
        double y = latToY(lat, tileZoom) * scaleFactor + offsetY;
        return new float[]{(float) x, (float) y};
    }

    private static double lonToX(double lon, float zoom) {
        return (lon + 180.0) / 360.0 * TILE * Math.pow(2, zoom);
    }

    private static double latToY(double lat, float zoom) {
        double rad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat)));
        return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * TILE * Math.pow(2, zoom);
    }

    private static double xToLon(double x, float zoom) {
        return x / (TILE * Math.pow(2, zoom)) * 360.0 - 180.0;
    }

    private static double yToLat(double y, float zoom) {
        double n = Math.PI - 2 * Math.PI * y / (TILE * Math.pow(2, zoom));
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /* ----------------------------------------------------------------- тайлы */

    private Bitmap tile(final int zoomLevel, final int col, final int row) {
        final String key = zoomLevel + "/" + col + "/" + row;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        // Дисковый кэш: работает и в офлайне, и быстрее при повторных открытиях
        File file = new File(getContext().getCacheDir(), "tiles/" + zoomLevel + "_" + col + "_" + row + ".png");
        if (file.exists()) {
            Bitmap fromDisk = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (fromDisk != null) {
                CACHE.put(key, fromDisk);
                return fromDisk;
            }
            file.delete();
        }
        synchronized (LOADING) {
            if (LOADING.contains(key)) {
                return null;
            }
            LOADING.add(key);
        }
        Ui.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://tile.openstreetmap.org/" + key + ".png";
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(12000);
                    conn.setRequestProperty("User-Agent", "ModarFamily/2.1 (Android)");
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        final Bitmap bitmap = BitmapFactory.decodeStream(in);
                        in.close();
                        if (bitmap != null) {
                            CACHE.put(key, bitmap);
                            try {
                                File dir = new File(getContext().getCacheDir(), "tiles");
                                dir.mkdirs();
                                File target = new File(dir, zoomLevel + "_" + col + "_" + row + ".png");
                                FileOutputStream out = new FileOutputStream(target);
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                                out.close();
                            } catch (Exception ignored) {
                            }
                            Ui.ui(new Runnable() {
                                @Override
                                public void run() {
                                    invalidate();
                                }
                            });
                        }
                    }
                    conn.disconnect();
                } catch (Exception ignored) {
                } finally {
                    synchronized (LOADING) {
                        LOADING.remove(key);
                    }
                }
            }
        });
        return null;
    }

    /* ----------------------------------------------------------------- жесты */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                downX = lastX;
                downY = lastY;
                handler.removeCallbacks(longPressTask);
                handler.postDelayed(longPressTask, 650);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 2) {
                    float distance = distance(event);
                    if (pinchStart == 0) {
                        pinchStart = distance;
                        pinchZoom = zoom;
                    } else if (distance > 0) {
                        double ratio = Math.log(distance / pinchStart) / Math.log(2);
                        zoom = (float) Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, pinchZoom + ratio));
                        invalidate();
                    }
                } else {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) + Math.abs(dy) > Ui.dp(getContext(), 6)) {
                        handler.removeCallbacks(longPressTask);
                    }
                    pan(dx, dy);
                    lastX = event.getX();
                    lastY = event.getY();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressTask);
                pinchStart = 0;
                float moved = Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY);
                if (moved < Ui.dp(getContext(), 8)) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 300) {
                        zoom = Math.min(MAX_ZOOM, zoom + 1);
                        invalidate();
                    } else {
                        handleTap(event.getX(), event.getY());
                    }
                    lastTap = now;
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleTap(float x, float y) {
        for (JSONObject device : devices) {
            if (!device.has("lat") || device.isNull("lat")) {
                continue;
            }
            float[] point = screenOf(device.optDouble("lat", 0), device.optDouble("lon", 0));
            if (Math.hypot(point[0] - x, point[1] - y) < Ui.dp(getContext(), 24)) {
                if (listener != null) {
                    listener.onMarkerTap(device.optString("id", ""));
                }
                return;
            }
        }
    }

    /** Долгое нажатие: 600 мс без движения. */
    private boolean longPress(float x, float y) {
        if (listener == null) {
            return false;
        }
        double[] point = fromScreen(x, y);
        listener.onMapLongPress(point[0], point[1]);
        return true;
    }

    private float[] screenOf(double lat, double lon) {
        float tileZoom = (float) Math.floor(zoom);
        float scaleFactor = (float) Math.pow(2, zoom - tileZoom);
        double centerX = lonToX(centerLon, tileZoom);
        double centerY = latToY(centerLat, tileZoom);
        float offsetX = (float) (getWidth() / 2 - centerX * scaleFactor);
        float offsetY = (float) (getHeight() / 2 - centerY * scaleFactor);
        return toScreen(lat, lon, tileZoom, scaleFactor, offsetX, offsetY);
    }

    private double[] fromScreen(float x, float y) {
        float tileZoom = (float) Math.floor(zoom);
        float scaleFactor = (float) Math.pow(2, zoom - tileZoom);
        double centerX = lonToX(centerLon, tileZoom);
        double centerY = latToY(centerLat, tileZoom);
        double offsetX = getWidth() / 2 - centerX * scaleFactor;
        double offsetY = getHeight() / 2 - centerY * scaleFactor;
        double worldX = (x - offsetX) / scaleFactor;
        double worldY = (y - offsetY) / scaleFactor;
        return new double[]{yToLat(worldY, tileZoom), xToLon(worldX, tileZoom)};
    }

    private void pan(float dx, float dy) {
        float tileZoom = (float) Math.floor(zoom);
        float scaleFactor = (float) Math.pow(2, zoom - tileZoom);
        double centerX = lonToX(centerLon, tileZoom);
        double centerY = latToY(centerLat, tileZoom);
        centerX -= dx / scaleFactor;
        centerY -= dy / scaleFactor;
        centerLon = xToLon(centerX, tileZoom);
        centerLat = yToLat(centerY, tileZoom);
        invalidate();
    }

    private static float distance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
