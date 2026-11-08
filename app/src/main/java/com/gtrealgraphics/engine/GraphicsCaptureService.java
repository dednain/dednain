package com.gtrealgraphics.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import java.nio.ByteBuffer;

public final class GraphicsCaptureService extends Service {
    private static final String TAG = "GTGraphicsCapture";
    private static final String CHANNEL_ID = "gt_graphics_capture";
    private static final int NOTIFICATION_ID = 7301;
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String ACTION_STOP = "com.gtrealgraphics.engine.STOP";

    private static volatile GraphicsCaptureService activeInstance;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private GraphicsOverlay overlay;
    private int densityDpi;
    private int captureWidth;
    private int captureHeight;
    private long fpsWindowStart;
    private int framesInWindow;
    private volatile boolean shuttingDown;

    public static void updateSettings(GpuPostProcessor.Settings settings) {
        GraphicsCaptureService service = activeInstance;
        if (service != null && service.overlay != null) {
            service.overlay.setSettings(settings);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY;

        try {
            startAsForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultCode == 0 || resultData == null) {
                throw new IllegalStateException("Screen capture consent data is missing");
            }

            MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("MediaProjection unavailable");

            captureThread = new HandlerThread("GT-Capture-Frames");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            DisplayMetrics metrics = new DisplayMetrics();
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            densityDpi = metrics.densityDpi;

            overlay = new GraphicsOverlay(this);
            addOverlay();

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    stopSelf();
                }

                @Override public void onCapturedContentResize(int width, int height) {
                    if (width > 0 && height > 0 && captureHandler != null) {
                        captureHandler.post(() -> resizeCapture(width, height));
                    }
                }

                @Override public void onCapturedContentVisibilityChanged(boolean isVisible) {
                    if (overlay != null) {
                        overlay.post(() -> overlay.setVisibility(isVisible ? android.view.View.VISIBLE : android.view.View.INVISIBLE));
                    }
                }
            }, captureHandler);

            setupCapture(metrics.widthPixels, metrics.heightPixels, true);
            fpsWindowStart = System.currentTimeMillis();
        } catch (Throwable error) {
            Log.e(TAG, "Could not start capture pipeline", error);
            updateNotification("Не удалось запустить графический конвейер");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Intent stopIntent = new Intent(this, GraphicsCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
            this, 7302, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("GT Real Graphics Engine")
            .setContentText("GPU-постобработка активна")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPending)
            .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GT Graphics Engine", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Состояние GPU-постобработки экрана");
            manager.createNotificationChannel(channel);
        }
    }

    private void addOverlay() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("GT Real Graphics Engine");
        windowManager.addView(overlay, params);
    }

    private void resizeCapture(int width, int height) {
        if (shuttingDown || projection == null || width <= 0 || height <= 0) return;
        setupCapture(width, height, false);
    }

    private void setupCapture(int width, int height, boolean firstCreate) {
        if (width <= 0 || height <= 0) return;
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        captureWidth = width;
        captureHeight = height;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        if (firstCreate) {
            virtualDisplay = projection.createVirtualDisplay(
                "GT-Real-Graphics-Capture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        } else if (virtualDisplay != null) {
            virtualDisplay.resize(width, height, densityDpi);
            virtualDisplay.setSurface(imageReader.getSurface());
        }
    }

    private void onImageAvailable(ImageReader source) {
        if (shuttingDown || overlay == null) return;
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;
            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;
            Image.Plane plane = planes[0];
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride != 4 || width <= 0 || height <= 0) return;

            int byteCount = width * height * 4;
            byte[] rgba = overlay.obtainFrameBuffer(byteCount);
            ByteBuffer input = plane.getBuffer();
            int rowBytes = width * 4;
            for (int y = 0; y < height; y++) {
                input.position(y * rowStride);
                input.get(rgba, y * rowBytes, rowBytes);
            }
            overlay.submitFrame(rgba, width, height);
            recordFrame();
        } catch (Throwable error) {
            Log.w(TAG, "Frame copy failed", error);
        } finally {
            if (image != null) image.close();
        }
    }

    private void recordFrame() {
        framesInWindow++;
        long now = System.currentTimeMillis();
        long elapsed = now - fpsWindowStart;
        if (elapsed >= 2000) {
            int fps = Math.round(framesInWindow * 1000f / elapsed);
            updateNotification("Захват: " + fps + " кадров/с • GPU-постобработка");
            framesInWindow = 0;
            fpsWindowStart = now;
        }
    }

    private void updateNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Intent stopIntent = new Intent(this, GraphicsCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
            this, 7302, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("GT Real Graphics Engine")
            .setContentText(message)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPending)
            .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    @Override public void onDestroy() {
        shuttingDown = true;
        activeInstance = null;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (overlay != null && windowManager != null) {
            try { windowManager.removeViewImmediate(overlay); } catch (Exception ignored) {}
            overlay.releaseRenderer();
            overlay = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
