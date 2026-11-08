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
import android.os.Handler;
import android.os.Looper;
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
    private int inputFramesInWindow,renderedFramesInWindow,inputFps,renderFps;
    private volatile String rendererInfo="GLES3";
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private volatile boolean shuttingDown;
    private volatile long lastInputFrameAtMs;
    private final Runnable frameWatchdog=new Runnable(){
        @Override public void run(){
            if(shuttingDown)return;
            long last=lastInputFrameAtMs;
            boolean fresh=last>0&&System.currentTimeMillis()-last<=1500;
            boolean game=GraphicsAccessibilityService.isTargetGameForeground();
            if(!fresh||!game)GraphicsAccessibilityService.setOverlayVisible(false);
            mainHandler.postDelayed(this,500);
        }
    };

    public static void updateSettings(GpuPostProcessor.Settings settings){
        GraphicsCaptureService s=activeInstance;
        if(s!=null&&s.overlay!=null)s.overlay.setSettings(settings);
    }
    public static void stopActiveCapture(){
        GraphicsCaptureService s=activeInstance;
        if(s!=null)s.stopSelf();
    }

    public static void setGameForeground(boolean foreground){
        GraphicsCaptureService s=activeInstance;
        if(s==null)return;
        s.mainHandler.post(()->{
            if(s.overlay!=null&&!s.shuttingDown){
                long last=s.lastInputFrameAtMs;
                boolean fresh=last>0&&System.currentTimeMillis()-last<=1500;
                boolean visible=foreground&&fresh;
                GraphicsAccessibilityService.setOverlayVisible(visible);
                Log.i(TAG,"Game foreground="+foreground+"; fresh GPU frame="+fresh+"; overlay visible="+visible);
            }
        });
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();activeInstance=this;createNotificationChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null){Log.w(TAG,"Duplicate start ignored; projection remains active");return START_NOT_STICKY;}
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

            // Register before GLSurfaceView asynchronously publishes its Surface.
            projectionCallback=new MediaProjection.Callback(){
                @Override public void onStop(){stopSelf();}
                @Override public void onCapturedContentResize(int width,int height){
                    if(width<=0||height<=0||shuttingDown)return;
                    captureWidth=width;captureHeight=height;
                    if(overlay!=null)overlay.setCaptureSize(width,height);
                    if(virtualDisplay!=null)virtualDisplay.resize(width,height,densityDpi);
                    int outW=overlay!=null?overlay.getWidth():0;
                    int outH=overlay!=null?overlay.getHeight():0;
                    String geometry="Capture "+width+"x"+height+" / output "+outW+"x"+outH;
                    updateNotification(geometry);
                    Log.i(TAG,"Captured app resized: "+geometry);
                }
                @Override public void onCapturedContentVisibilityChanged(boolean visible){
                    // Informational only: our trusted overlay can itself cover the selected app.
                    Log.i(TAG,"Captured app visibility callback="+visible+"; accessibility package tracking controls overlay visibility");
                }
            };
            projection.registerCallback(projectionCallback,new android.os.Handler(getMainLooper()));

            windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
            DisplayMetrics metrics=new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            densityDpi=metrics.densityDpi;
            captureWidth=metrics.widthPixels;
            captureHeight=metrics.heightPixels;

            Context overlayContext=GraphicsAccessibilityService.activeContext();
            if(overlayContext==null)throw new IllegalStateException("Enable GT Real Graphics Engine in Accessibility settings first");
            overlay=new GraphicsOverlay(overlayContext);
            overlay.setAlpha(1.0f);
            overlay.setVisibility(android.view.View.INVISIBLE);
            overlay.setCaptureSize(captureWidth,captureHeight);
            overlay.setSettings(loadSettings());
            overlay.setInputSurfaceReadyListener(this::onInputSurfaceReady);
            overlay.setFrameAvailableListener(this::recordFrame);
            overlay.setFrameRenderedListener(this::recordRenderedFrame);
            overlay.setRendererErrorListener(this::onRendererError);
            overlay.setRendererInfoListener(this::onRendererInfo);
            addOverlay();


            fpsWindowStart=System.currentTimeMillis();
            lastInputFrameAtMs=0;
            mainHandler.removeCallbacks(frameWatchdog);
            mainHandler.postDelayed(frameWatchdog,500);
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
        if(p.getInt("settings_version",0)<2)return s;
        s.saturation=p.getFloat("saturation",s.saturation);
        s.contrast=p.getFloat("contrast",s.contrast);
        s.exposure=p.getFloat("exposure",s.exposure);
        s.hdrLook=p.getFloat("hdrLook",s.hdrLook);
        s.bloom=p.getFloat("bloom",s.bloom);
        s.sharpness=p.getFloat("sharpness",s.sharpness);
        s.shadowLift=p.getFloat("shadowLift",s.shadowLift);
        s.localContrast=p.getFloat("localContrast",s.localContrast);
        s.detailBoost=p.getFloat("detailBoost",s.detailBoost);
        s.temperature=p.getFloat("temperature",s.temperature);
        s.vignette=p.getFloat("vignette",s.vignette);
        s.bloomThreshold=p.getFloat("bloomThreshold",s.bloomThreshold);
        s.renderScale=p.getFloat("renderScale",s.renderScale);
        return s;
    }

    private void onRendererInfo(String info){
        rendererInfo=info;
        updateNotification("GPU ready: "+rendererInfo);
    }

    private void onRendererError(String message){
        Log.e(TAG,"GPU renderer unavailable: "+message);
        updateNotification("GPU initialization failed: "+message);
        stopSelf();
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
        if(!GraphicsAccessibilityService.showOverlay(overlay))
            throw new IllegalStateException("Trusted accessibility overlay is not connected");
        Log.i(TAG,"Opaque trusted overlay attached; game touch events pass through");
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
    private synchronized void recordFrame(){
        if(shuttingDown)return;
        lastInputFrameAtMs=System.currentTimeMillis();
        inputFramesInWindow++;
        if(GraphicsAccessibilityService.isTargetGameForeground())
            GraphicsAccessibilityService.setOverlayVisible(true);
        publishFrameStats();
    }
    private synchronized void recordRenderedFrame(){
        if(shuttingDown)return;
        renderedFramesInWindow++;
        publishFrameStats();
    }
    private void publishFrameStats(){
        long now=System.currentTimeMillis();long elapsed=now-fpsWindowStart;
        if(elapsed>=2000){
            inputFps=Math.round(inputFramesInWindow*1000f/elapsed);
            renderFps=Math.round(renderedFramesInWindow*1000f/elapsed);
            inputFramesInWindow=0;renderedFramesInWindow=0;fpsWindowStart=now;
            updateNotification(rendererInfo+" • input "+inputFps+" FPS • render "+renderFps+" FPS");
        }
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
        shuttingDown=true;
        mainHandler.removeCallbacks(frameWatchdog);
        GraphicsAccessibilityService.setOverlayVisible(false);
        activeInstance=null;
        if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        if(projection!=null){
            try{if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);}catch(Exception ignored){}
            try{projection.stop();}catch(Exception ignored){}
            projection=null;
        }
        if(overlay!=null&&windowManager!=null){
            try{overlay.releaseRenderer();}catch(Exception ignored){}
            try{GraphicsAccessibilityService.hideOverlay(overlay);}catch(Exception ignored){}
            overlay=null;
        }
        if(captureSurface!=null){try{captureSurface.release();}catch(Exception ignored){}captureSurface=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
