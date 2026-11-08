package com.gtsuperres.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import java.nio.ByteBuffer;

/**
 * EXPERIMENTAL: secure, untouchable overlay; does not feed its own pixels
 * back into a MediaProjection stream. Not a frame injector, HDR10, or 60 FPS.
 */
public final class NeuralScreenService extends Service {
  private static final String TAG="GT-Neural";
  private static final String CHANNEL="gt-neural-screen";
  private final Handler main=new Handler(Looper.getMainLooper());
  private HandlerThread thread;
  private Handler inferenceHandler;
  private MediaProjection projection;
  private VirtualDisplay display;
  private ImageReader reader;
  private WindowManager windows;
  private NeuralOverlay overlay;
  private EsrganEngine engine;
  private volatile boolean closing=false;
  private long lastFrame=0;
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(intent==null||"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
    if(thread!=null)return START_NOT_STICKY;
    try{
      NotificationManager nm=getSystemService(NotificationManager.class);
      nm.createNotificationChannel(new NotificationChannel(CHANNEL,"GT SuperRes AI",NotificationManager.IMPORTANCE_LOW));
      Intent stop=new Intent(this,NeuralScreenService.class).setAction("STOP");
      PendingIntent pi=PendingIntent.getService(this,10,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
      Notification note=new Notification.Builder(this,CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("GT SuperRes AI: эксперимент")
        .setContentText("ESRGAN; экранный режим медленный. Нажмите «Остановить».")
        .addAction(android.R.drawable.ic_media_pause,"Остановить",pi).setOngoing(true).build();
      startForeground(101,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
      int code=intent.getIntExtra("resultCode",0);
      Intent token;
      if(Build.VERSION.SDK_INT>=33) token=intent.getParcelableExtra("resultData",Intent.class);
      else token=intent.getParcelableExtra("resultData");
      if(code!=Activity.RESULT_OK||token==null)throw new IllegalStateException("Screen capture not allowed");
      MediaProjectionManager pm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      projection=pm.getMediaProjection(code,token);
      projection.registerCallback(new MediaProjection.Callback(){
        @Override public void onStop(){main.post(()->stopSelf());}
      },main);
      windows=(WindowManager)getSystemService(WINDOW_SERVICE);
      DisplayMetrics dm=new DisplayMetrics();
      windows.getDefaultDisplay().getRealMetrics(dm);
      int w=dm.widthPixels,h=dm.heightPixels;
      if(w<32||h<32)throw new IllegalStateException("Invalid display resolution");
      overlay=new NeuralOverlay(this);
      WindowManager.LayoutParams p=new WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
        WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
      );
      // Android may block touches through untrusted full-opacity overlays.
      p.alpha=0.70f;
      windows.addView(overlay,p);
      thread=new HandlerThread("gt-neural-esrgan");thread.start();
      inferenceHandler=new Handler(thread.getLooper());
      reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
      reader.setOnImageAvailableListener(ir->processFrame(ir),inferenceHandler);
      display=projection.createVirtualDisplay("GT-Neural-AI",w,h,dm.densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,main);
    }catch(Exception e){Log.e(TAG,"Start failed",e);stopSelf();}
    return START_NOT_STICKY;
  }
  private void processFrame(ImageReader ir){
    if(closing)return;
    Image frame=null;
    try{
      frame=ir.acquireLatestImage();
      if(frame==null)return;
      long time=android.os.SystemClock.elapsedRealtime();
      // Model takes hundreds of milliseconds; never build a backlog.
      if(time-lastFrame<600)return;
      lastFrame=time;
      if(engine==null)engine=new EsrganEngine(this);
      int w=frame.getWidth(),h=frame.getHeight();
      Image.Plane plane=frame.getPlanes()[0];
      int pixelStride=plane.getPixelStride(),stride=plane.getRowStride();
      if(pixelStride!=4||stride%4!=0)return;
      int widthWithPadding=stride/4;
      ByteBuffer buffer=plane.getBuffer();
      buffer.rewind();
      Bitmap full=Bitmap.createBitmap(widthWithPadding,h,Bitmap.Config.ARGB_8888);
      full.copyPixelsFromBuffer(buffer);
      Bitmap actual=Bitmap.createBitmap(full,0,0,w,h);
      full.recycle();
      frame.close();frame=null;
      Bitmap improved=engine.enhance(actual);
      actual.recycle();
      if(closing){improved.recycle();return;}
      main.post(()->{
        if(closing||overlay==null){improved.recycle();return;}
        overlay.show(improved);
      });
    }catch(Throwable e){
      Log.e(TAG,"Neural screen failed",e);
      main.post(()->stopSelf());
    }finally{
      if(frame!=null)frame.close();
    }
  }
  @Override public void onDestroy(){
    closing=true;
    if(reader!=null){reader.setOnImageAvailableListener(null,null);}
    if(display!=null){display.release();display=null;}
    if(projection!=null){projection.stop();projection=null;}
    if(reader!=null){reader.close();reader=null;}
    if(overlay!=null){
      overlay.close();
      try{windows.removeViewImmediate(overlay);}catch(Exception e){Log.w(TAG,"Overlay removal",e);}
      overlay=null;
    }
    if(thread!=null){thread.quitSafely();thread=null;}
    // Inference runs only on its own worker thread. It is not interrupted mid-tensor.
    if(engine!=null){try{engine.close();}catch(Exception ignored){}engine=null;}
    super.onDestroy();
  }
  static final class NeuralOverlay extends View {
    Bitmap bitmap;Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    NeuralOverlay(Context context){super(context);setWillNotDraw(false);}
    @Override protected void onDraw(Canvas canvas){
      super.onDraw(canvas);
      if(bitmap!=null){
        Rect src=new Rect(0,0,bitmap.getWidth(),bitmap.getHeight());
        Rect dst=new Rect(0,0,getWidth(),getHeight());
        canvas.drawBitmap(bitmap,src,dst,paint);
      }
    }
    void show(Bitmap output){
      Bitmap old=bitmap;bitmap=output;invalidate();if(old!=null&&old!=output)old.recycle();
    }
    void close(){if(bitmap!=null){bitmap.recycle();bitmap=null;}}
  }
}
