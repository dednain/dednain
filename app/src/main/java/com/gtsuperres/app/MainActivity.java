package com.gtsuperres.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.content.ContentValues;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  private static final int PICK=5, CAPTURE=6, OVERLAY=7;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private TextView status;
  private ImageView before,after;
  private Button save;
  private Bitmap enhanced;
  private boolean processing=false;
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    ScrollView scroll=new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(22),dp(32),dp(22),dp(32));root.setBackgroundColor(0xff091321);
    scroll.addView(root);
    addTitle(root,"GT SUPERRES AI",27,0xffedf5ff);
    addText(root,"НЕЙРОСЕТЕВОЕ ВОССТАНОВЛЕНИЕ • ESRGAN 4×",12,0xff72eac8);
    addText(root,"Настоящая обученная нейросеть TensorFlow Lite. Повышает детализацию отдельных изображений, но не изменяет графику игрового движка и не создаёт HDR10.",14,0xffa8b8cb);
    addTitle(root,"01 / Улучшить изображение",20,0xffe9f2ff);
    Button pick=makeButton(root,"Выбрать скриншот или фотографию");
    pick.setOnClickListener(v->{ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,PICK);});
    before=new ImageView(this);before.setAdjustViewBounds(true);before.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(before,new LinearLayout.LayoutParams(-1,0));
    after=new ImageView(this);after.setAdjustViewBounds(true);after.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(after,new LinearLayout.LayoutParams(-1,0));
    save=makeButton(root,"Сохранить результат в Галерею");
    save.setEnabled(false);save.setOnClickListener(v->saveImage());
    addTitle(root,"02 / AI поверх экрана",20,0xffe9f2ff);
    addText(root,"Экспериментально: захват экрана → ESRGAN → защищённое наложение. До нескольких кадров в секунду, НЕ 60 FPS и НЕ DLSS 5. Может работать с задержкой, чёрным экраном или быть недоступно в некоторых играх.",14,0xffa8b8cb);
    Button live=makeButton(root,"Включить экспериментальный AI-экран");
    live.setOnClickListener(v->startLive());
    Button stop=makeButton(root,"Остановить AI-экран");
    stop.setOnClickListener(v->{stopService(new Intent(this,NeuralScreenService.class));status.setText("Экранный режим остановлен");});
    status=new TextView(this);status.setTextSize(14);status.setTextColor(0xff7fe5cc);
    status.setText("Готово к обработке изображения");status.setPadding(0,dp(20),0,dp(20));root.addView(status);
    addText(root,"Без root. Снимки обрабатываются на телефоне; не отправляются на сервер. Старый фильтр больше не запускается.",13,0xff8ba0b8);
    setContentView(scroll);
    if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},999);
  }
  private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
  private void addTitle(LinearLayout root,String text,int size,int color){
    TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);
    t.setPadding(0,dp(20),0,dp(10));root.addView(t);
  }
  private void addText(LinearLayout root,String text,int size,int color){
    TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);
    t.setPadding(0,dp(7),0,dp(12));root.addView(t);
  }
  private Button makeButton(LinearLayout root,String text){
    Button b=new Button(this);b.setText(text);b.setAllCaps(false);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(60));p.topMargin=dp(10);root.addView(b,p);return b;
  }
  private void process(Uri uri) {
    if(processing)return;
    processing=true;save.setEnabled(false);status.setText("Загружаю нейросеть ESRGAN и обрабатываю изображение…");
    worker.execute(()->{
      try{
        Bitmap source=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),uri),(decoder,info,src)->{
          decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
          android.util.Size size=info.getSize();
          if(Math.max(size.getWidth(),size.getHeight())>2500)
            decoder.setTargetSampleSize((int)Math.ceil(Math.max(size.getWidth(),size.getHeight())/2500.0));
        });
        Bitmap result;
        try(EsrganEngine engine=new EsrganEngine(this)){ result=engine.enhancePhoto(source); }
        runOnUiThread(()->{
          if(enhanced!=null)enhanced.recycle();
          enhanced=result;
          before.setImageBitmap(source);after.setImageBitmap(result);
          before.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(155)));
          after.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(295)));
          save.setEnabled(true);status.setText("Готово: ESRGAN обработала изображение. Результат: "+result.getWidth()+" × "+result.getHeight()+" пикселей");
        });
      }catch(Exception e){
        runOnUiThread(()->status.setText("Ошибка нейросети: "+e.getClass().getSimpleName()+": "+e.getMessage()));
      }finally{runOnUiThread(()->processing=false);}
    });
  }
  private void saveImage(){
    if(enhanced==null)return;
    Bitmap target=enhanced;
    worker.execute(()->{
      try{
        ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,"GT-SuperRes-AI-"+System.currentTimeMillis()+".png");
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/GT-SuperRes");
        Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IllegalStateException("No output URI");
        try(OutputStream os=getContentResolver().openOutputStream(uri)){
          if(os==null||!target.compress(Bitmap.CompressFormat.PNG,100,os))throw new IllegalStateException("PNG save error");
        }
        runOnUiThread(()->status.setText("Сохранено: Галерея → Pictures/GT-SuperRes"));
      }catch(Exception e){runOnUiThread(()->status.setText("Ошибка сохранения: "+e.getMessage()));}
    });
  }
  private void startLive(){
    if(!Settings.canDrawOverlays(this)) {
      startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),OVERLAY);return;
    }
    MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
    Intent request=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
    startActivityForResult(request,CAPTURE);
  }
  @Override public void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==PICK&&result==RESULT_OK&&data!=null){process(data.getData());return;}
    if(request==OVERLAY){if(Settings.canDrawOverlays(this))startLive();return;}
    if(request==CAPTURE&&result==RESULT_OK&&data!=null){
      Intent i=new Intent(this,NeuralScreenService.class);
      i.putExtra("resultCode",result);i.putExtra("resultData",data);
      startForegroundService(i);status.setText("AI-экран запущен. Остановить можно через уведомление.");
    }
  }
}
