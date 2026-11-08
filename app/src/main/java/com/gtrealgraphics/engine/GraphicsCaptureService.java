package com.gtrealgraphics.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;

public final class GraphicsCaptureService extends Service {
    private static final String TAG="GTGraphicsCapture";
    private static final String CHANNEL_ID="gt_graphics_capture";
    private static final int NOTIFICATION_ID=7301;
    public static final String EXTRA_RESULT_CODE="projection_result_code";
    public static final String EXTRA_RESULT_DATA="projection_result_data";
    private static final String ACTION_STOP="com.gtrealgraphics.engine.STOP";
    private static volatile GraphicsCaptureService activeInstance;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private WindowManager windowManager;
    private GraphicsOverlay overlay;
    private Surface captureSurface;
    private int densityDpi,captureWidth,captureHeight;
    private long fpsWindowStart;
    private int framesInWindow,lastFps;
    private volatile boolean shuttingDown;

    public static void updateSettings(GpuPostProcessor.Settings settings){
        GraphicsCaptureService s=activeInstance;
        if(s!=null&&s.overlay!=null)s.overlay.setSettings(settings);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();activeInstance=this;createNotificationChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        try{
            startAsForeground();
            int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,0);
            Intent resultData;
            if(Build.VERSION.SDK_INT>=33)resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent.class);
            else resultData=intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if(resultCode==0||resultData==null)throw new IllegalStateException("Missing MediaProjection consent");
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection=manager.getMediaProjection(resultCode,resultData);
            if(projection==null)throw new IllegalStateException("MediaProjection unavailable");

            windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
            DisplayMetrics metrics=new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            densityDpi=metrics.densityDpi;
            captureWidth=metrics.widthPixels;
            captureHeight=metrics.heightPixels;

            overlay=new GraphicsOverlay(this);
            overlay.setCaptureSize(captureWidth,captureHeight);
            overlay.setSettings(loadSettings());
            overlay.setInputSurfaceReadyListener(this::onInputSurfaceReady);
            overlay.setFrameAvailableListener(this::recordFrame);
            addOverlay();

            projectionCallback=new MediaProjection.Callback(){
                @Override public void onStop(){stopSelf();}
                @Override public void onCapturedContentResize(int width,int height){
                    if(width<=0||height<=0||shuttingDown)return;
                    captureWidth=width;captureHeight=height;
                    if(overlay!=null)overlay.setCaptureSize(width,height);
                    if(virtualDisplay!=null)virtualDisplay.resize(width,height,densityDpi);
                    Log.i(TAG,"Captured app resized to "+width+"x"+height);
                }
                @Override public void onCapturedContentVisibilityChanged(boolean visible){
                    if(overlay!=null)overlay.post(()->overlay.setVisibility(visible?android.view.View.VISIBLE:android.view.View.INVISIBLE));
                }
            };
            projection.registerCallback(projectionCallback,new android.os.Handler(getMainLooper()));
            fpsWindowStart=System.currentTimeMillis();
            updateNotification("Ожидание GPU SurfaceTexture");
        }catch(Throwable error){
            Log.e(TAG,"Could not start GPU capture",error);
            updateNotification("Ошибка запуска: "+error.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private GpuPostProcessor.Settings loadSettings(){
        android.content.SharedPreferences p=getSharedPreferences("gt_graphics",MODE_PRIVATE);
        GpuPostProcessor.Settings s=GpuPostProcessor.Settings.defaults();
        s.saturation=p.getFloat("saturation",s.saturation);
        s.contrast=p.getFloat("contrast",s.contrast);
        s.exposure=p.getFloat("exposure",s.exposure);
        s.hdrLook=p.getFloat("hdrLook",s.hdrLook);
        s.bloom=p.getFloat("bloom",s.bloom);
        s.sharpness=p.getFloat("sharpness",s.sharpness);
        s.shadowLift=p.getFloat("shadowLift",s.shadowLift);
        s.localContrast=p.getFloat("localContrast",s.localContrast);
        s.temperature=p.getFloat("temperature",s.temperature);
        s.vignette=p.getFloat("vignette",s.vignette);
        s.bloomThreshold=p.getFloat("bloomThreshold",s.bloomThreshold);
        return s;
    }

    private void onInputSurfaceReady(Surface surface){
        if(shuttingDown||projection==null){if(surface!=null)surface.release();return;}
        try{
            if(virtualDisplay==null){
                captureSurface=surface;
                virtualDisplay=projection.createVirtualDisplay(
                    "GT-Real-Graphics-GPU-Capture",captureWidth,captureHeight,densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSurface,null,new android.os.Handler(getMainLooper()));
            }else{
                Surface old=captureSurface;
                captureSurface=surface;
                virtualDisplay.setSurface(surface);
                if(old!=null&&old!=surface)old.release();
            }
            updateNotification("GPU capture active • waiting for frames");
            Log.i(TAG,"GPU-native virtual display created: "+captureWidth+"x"+captureHeight);
        }catch(Throwable e){Log.e(TAG,"VirtualDisplay creation failed",e);updateNotification("Не удалось создать GPU-захват");stopSelf();}
    }

    private void addOverlay(){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;
        p.setTitle("GT Real Graphics Engine");
        windowManager.addView(overlay,p);
    }

    private void startAsForeground(){
        Intent stop=new Intent(this,GraphicsCaptureService.class);stop.setAction(ACTION_STOP);
        PendingIntent pending=PendingIntent.getService(this,7302,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("GT Real Graphics Engine")
            .setContentText("Запуск GPU-конвейера")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Остановить",pending).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID,n);
    }
    private void createNotificationChannel(){
        NotificationManager m=getSystemService(NotificationManager.class);
        if(m!=null&&Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"GT Graphics Engine",NotificationManager.IMPORTANCE_LOW);c.setDescription("Состояние GPU-постобработки");m.createNotificationChannel(c);}
    }
    private void recordFrame(){
        if(shuttingDown)return;
        framesInWindow++;long now=System.currentTimeMillis();long elapsed=now-fpsWindowStart;
        if(elapsed>=2000){lastFps=Math.round(framesInWindow*1000f/elapsed);framesInWindow=0;fpsWindowStart=now;updateNotification("GPU input: "+lastFps+" FPS • multi-pass post-process");}
    }
    private void updateNotification(String message){
        NotificationManager m=getSystemService(NotificationManager.class);if(m==null)return;
        Intent stop=new Intent(this,GraphicsCaptureService.class);stop.setAction(ACTION_STOP);
        PendingIntent pending=PendingIntent.getService(this,7302,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("GT Real Graphics Engine").setContentText(message).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Остановить",pending).build();
        m.notify(NOTIFICATION_ID,n);
    }

    @Override public void onDestroy(){
        shuttingDown=true;activeInstance=null;
        if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        if(projection!=null){
            try{if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);}catch(Exception ignored){}
            try{projection.stop();}catch(Exception ignored){}
            projection=null;
        }
        if(overlay!=null&&windowManager!=null){
            try{windowManager.removeViewImmediate(overlay);}catch(Exception ignored){}
            overlay.releaseRenderer();overlay=null;
        }
        if(captureSurface!=null){try{captureSurface.release();}catch(Exception ignored){}captureSurface=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
