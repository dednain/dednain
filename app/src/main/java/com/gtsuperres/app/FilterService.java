package com.gtsuperres.app;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.projection.*;
import android.opengl.*;
import android.os.*;
import android.util.*;
import android.view.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.*;

public class FilterService extends Service {
  private static final String CHANNEL="gt-filter";
  private MediaProjection projection;
  private VirtualDisplay virtualDisplay;
  private WindowManager windowManager;
  private WindowManager.LayoutParams params;
  private FilterView overlay;
  private Surface captureSurface;
  private boolean closing=false;
  private final Handler main=new Handler(Looper.getMainLooper());
  @Override public IBinder onBind(Intent i){return null;}
  @Override public int onStartCommand(Intent intent,int flags,int id){
    if(intent==null||"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
    if(projection!=null)return START_NOT_STICKY;
    try{
      NotificationManager nm=getSystemService(NotificationManager.class);
      nm.createNotificationChannel(new NotificationChannel(CHANNEL,"GT SuperRes",NotificationManager.IMPORTANCE_LOW));
      Intent stop=new Intent(this,FilterService.class).setAction("STOP");
      PendingIntent pending=PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
      Notification note=new Notification.Builder(this,CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("GT SuperRes работает")
        .setContentText("Нажмите «Остановить», чтобы выключить фильтр")
        .addAction(android.R.drawable.ic_media_pause,"Остановить",pending).setOngoing(true).build();
      startForeground(77,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
      int resultCode=intent.getIntExtra("resultCode",0);
      Intent resultData=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("resultData",Intent.class):intent.getParcelableExtra("resultData");
      if(resultCode!=Activity.RESULT_OK||resultData==null)throw new IllegalStateException("No screen capture consent");
      MediaProjectionManager pm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      projection=pm.getMediaProjection(resultCode,resultData);
      projection.registerCallback(new MediaProjection.Callback(){
        @Override public void onStop(){main.post(()->{if(!closing)stopSelf();});}
      },main);
      windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
      DisplayMetrics dm=new DisplayMetrics();windowManager.getDefaultDisplay().getRealMetrics(dm);
      final int w=Math.max(2,dm.widthPixels/2*2),h=Math.max(2,dm.heightPixels/2*2);
      overlay=new FilterView(this,w,h,surface->main.post(()->openDisplay(surface,w,h,dm.densityDpi)));
      params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT);
      params.alpha=.55f;
      windowManager.addView(overlay,params);
    }catch(Exception e){Log.e("GTFilter","Start failed",e);stopSelf();}
    return START_NOT_STICKY;
  }
  private void openDisplay(Surface surface,int w,int h,int dpi) {
    if(projection==null||closing||virtualDisplay!=null){surface.release();return;}
    try{
      captureSurface=surface;
      virtualDisplay=projection.createVirtualDisplay("GT-Filter",w,h,dpi,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,main);
    }catch(Exception e){Log.e("GTFilter","Capture failed",e);stopSelf();}
  }
  @Override public void onDestroy(){
    closing=true;
    if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
    if(captureSurface!=null){captureSurface.release();captureSurface=null;}
    if(projection!=null){projection.stop();projection=null;}
    if(overlay!=null){overlay.shutdown();try{windowManager.removeViewImmediate(overlay);}catch(Exception e){Log.w("GTFilter","overlay",e);}overlay=null;}
    super.onDestroy();
  }
  interface CaptureReady{void ready(Surface s);}
  static class FilterView extends GLSurfaceView implements GLSurfaceView.Renderer {
    final int captureW,captureH; final CaptureReady ready;
    SurfaceTexture texture;Surface surface;int oes,program,pos,uv,mat,texel;
    int sharp,colors,contrast; final float[] transform=new float[16];
    FloatBuffer vertex;boolean hasFrame=false,stopped=false;
    FilterView(Context c,int w,int h,CaptureReady r){
      super(c);captureW=w;captureH=h;ready=r;
      setEGLContextClientVersion(2);setEGLConfigChooser(8,8,8,8,0,0);
      getHolder().setFormat(PixelFormat.TRANSLUCENT);setZOrderOnTop(true);
      ByteBuffer b=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
      vertex=b.asFloatBuffer();vertex.put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1});vertex.position(0);
      Matrix.setIdentityM(transform,0);setRenderer(this);setRenderMode(RENDERMODE_WHEN_DIRTY);
    }
    private static int shader(int type,String code){
      int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,code);GLES20.glCompileShader(s);
      int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
      if(ok[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));
      return s;
    }
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig cfg){
      GLES20.glClearColor(0,0,0,0);
      String vs="attribute vec2 aPos;attribute vec2 aUV;varying vec2 vUV;void main(){gl_Position=vec4(aPos,0.,1.);vUV=aUV;}";
      String fs="#extension GL_OES_EGL_image_external : require\nprecision highp float;\n"
        +"uniform samplerExternalOES inputImage;uniform mat4 uMatrix;uniform vec2 uTexel;varying vec2 vUV;"
        +"void main(){vec2 p=(uMatrix*vec4(vUV,0.,1.)).xy;"
        +"vec3 c=texture2D(inputImage,p).rgb;"
        +"vec3 a=texture2D(inputImage,p+vec2(uTexel.x,0.)).rgb;"
        +"vec3 b=texture2D(inputImage,p-vec2(uTexel.x,0.)).rgb;"
        +"vec3 d=texture2D(inputImage,p+vec2(0.,uTexel.y)).rgb;"
        +"vec3 e=texture2D(inputImage,p-vec2(0.,uTexel.y)).rgb;"
        +"vec3 s=clamp(c+.35*(c-.25*(a+b+d+e)),0.,1.);"
        +"float l=dot(s,vec3(.2126,.7152,.0722));"
        +"vec3 color=clamp((mix(vec3(l),s,1.08)-.5)*1.06+.5,0.,1.);"
        +"gl_FragColor=vec4(color,.72);}";
      int v=shader(GLES20.GL_VERTEX_SHADER,vs),f=shader(GLES20.GL_FRAGMENT_SHADER,fs);
      program=GLES20.glCreateProgram();GLES20.glAttachShader(program,v);GLES20.glAttachShader(program,f);
      GLES20.glLinkProgram(program);int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
      if(ok[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
      GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);
      pos=GLES20.glGetAttribLocation(program,"aPos");uv=GLES20.glGetAttribLocation(program,"aUV");
      mat=GLES20.glGetUniformLocation(program,"uMatrix");texel=GLES20.glGetUniformLocation(program,"uTexel");
      int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);oes=ids[0];
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
      texture=new SurfaceTexture(oes);texture.setDefaultBufferSize(captureW,captureH);
      texture.setOnFrameAvailableListener(t->{if(!stopped)requestRender();},new Handler(Looper.getMainLooper()));
      surface=new Surface(texture);ready.ready(surface);
    }
    @Override public void onSurfaceChanged(GL10 gl,int w,int h){GLES20.glViewport(0,0,w,h);}
    @Override public void onDrawFrame(GL10 gl){
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
      if(stopped||texture==null)return;
      try{texture.updateTexImage();texture.getTransformMatrix(transform);hasFrame=true;}
      catch(RuntimeException e){return;}
      if(!hasFrame)return;
      GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes);
      GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"inputImage"),0);
      GLES20.glUniformMatrix4fv(mat,1,false,transform,0);
      GLES20.glUniform2f(texel,1f/captureW,1f/captureH);
      vertex.position(0);GLES20.glEnableVertexAttribArray(pos);
      GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertex);
      vertex.position(2);GLES20.glEnableVertexAttribArray(uv);
      GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertex);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
      GLES20.glDisableVertexAttribArray(pos);GLES20.glDisableVertexAttribArray(uv);
    }
    void shutdown(){stopped=true;queueEvent(()->{if(texture!=null)texture.release();});onPause();}
  }
}
