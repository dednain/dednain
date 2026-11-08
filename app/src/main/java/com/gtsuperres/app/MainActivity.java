package com.gtsuperres.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  private static final int PICK_IMAGE=101;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final Object imageLock=new Object();
  private ImageView before,after;
  private TextView status,strengthLabel;
  private Button select,save,share;
  private Bitmap result;
  private Uri savedUri;
  private int aiPercent=35;
  private boolean processing=false;

  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
  private TextView label(LinearLayout layout,String value,int size,int color){
    TextView t=new TextView(this);
    t.setText(value);t.setTextColor(color);t.setTextSize(size);
    t.setPadding(0,dp(9),0,dp(9));layout.addView(t);return t;
  }
  private Button button(LinearLayout root,String text){
    Button b=new Button(this);b.setText(text);b.setAllCaps(false);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56));
    p.topMargin=dp(8);root.addView(b,p);return b;
  }
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    ScrollView scroll=new ScrollView(this);
    LinearLayout root=new LinearLayout(this);
    root.setPadding(dp(22),dp(24),dp(22),dp(30));root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(9,19,33));scroll.addView(root);
    label(root,"GT SUPERRES AI",28,0xffe9fbff);
    label(root,"Версия 3 • Исправлены скриншоты и задержки",13,0xff56dbc1);
    label(root,"Никакого захвата экрана, защищённого слоя или 5 FPS поверх игр. Улучшение работает для выбранных изображений, НЕ для рендеринга всех игр.",14,0xffb4c3d0);
    select=button(root,"Выбрать скриншот из галереи");
    select.setOnClickListener(v->openPicker());
    strengthLabel=label(root,"Сила нейросети: 35% (сохраняет исходные детали)",15,0xfff2f7fb);
    SeekBar strength=new SeekBar(this);
    strength.setMax(100);strength.setProgress(aiPercent);
    root.addView(strength,new LinearLayout.LayoutParams(-1,dp(48)));
    strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      @Override public void onProgressChanged(SeekBar s,int n,boolean fromUser){
        aiPercent=n;strengthLabel.setText("Сила нейросети: "+n+"% (0% — исходное масштабирование)");
      }
      @Override public void onStartTrackingTouch(SeekBar s){}
      @Override public void onStopTrackingTouch(SeekBar s){}
    });
    label(root,"Оригинал",14,0xffadbcc9);
    before=new ImageView(this);before.setAdjustViewBounds(true);
    before.setScaleType(ImageView.ScaleType.FIT_CENTER);
    before.setBackgroundColor(0xff1c2938);
    root.addView(before,new LinearLayout.LayoutParams(-1,dp(145)));
    label(root,"Результат нейросети",14,0xffadbcc9);
    after=new ImageView(this);after.setAdjustViewBounds(true);
    after.setScaleType(ImageView.ScaleType.FIT_CENTER);
    after.setBackgroundColor(0xff1c2938);
    root.addView(after,new LinearLayout.LayoutParams(-1,dp(210)));
    save=button(root,"Сохранить в Галерею");
    share=button(root,"Отправить скриншот");
    save.setEnabled(false);share.setEnabled(false);
    save.setOnClickListener(v->saveResult(false));
    share.setOnClickListener(v->saveResult(true));
    status=label(root,"Сделай обычный скриншот кнопками телефона и выбери его здесь.",14,0xff7be6c9);
    label(root,"Важно: нейросеть не создаёт настоящую HDR-графику, тени или дополнительные игровые FPS. Защитные предупреждения Android не отключаются — мы полностью удалили требовавший их экранный режим.",13,0xff90a8b8);
    setContentView(scroll);
    handleIncomingIntent(getIntent());
  }
  @Override protected void onNewIntent(Intent i){
    super.onNewIntent(i);setIntent(i);handleIncomingIntent(i);
  }
  private void handleIncomingIntent(Intent i){
    if(i==null||!Intent.ACTION_SEND.equals(i.getAction())||i.getType()==null||!i.getType().startsWith("image/"))return;
    Uri uri=null;
    if(android.os.Build.VERSION.SDK_INT>=33)uri=i.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);
    else uri=i.getParcelableExtra(Intent.EXTRA_STREAM);
    if(uri!=null)processImage(uri);
  }
  private void openPicker(){
    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.setType("image/*");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(intent,PICK_IMAGE);
  }
  @Override protected void onActivityResult(int req,int code,Intent data){
    super.onActivityResult(req,code,data);
    if(req==PICK_IMAGE&&code==RESULT_OK&&data!=null&&data.getData()!=null)
      processImage(data.getData());
  }
  private void processImage(Uri uri){
    if(processing){status.setText("Дождись завершения обработки предыдущего изображения");return;}
    processing=true;select.setEnabled(false);save.setEnabled(false);share.setEnabled(false);
    final int percent=aiPercent;
    status.setText("Нейросеть обрабатывает изображение. Это может занять несколько секунд.");
    worker.execute(()->{
      try{
        Bitmap original=ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(),uri),
          (decoder,info,source)->{
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            android.util.Size size=info.getSize();
            int longest=Math.max(size.getWidth(),size.getHeight());
            if(longest>2500)decoder.setTargetSampleSize((int)Math.ceil(longest/2500.0));
          });
        Bitmap enhanced;
        try(EsrganEngine ai=new EsrganEngine(this)){
          enhanced=ai.enhancePhoto(original,percent/100f);
        }
        runOnUiThread(()->{
          synchronized(imageLock) {
            result=enhanced;savedUri=null;
          }
          before.setImageBitmap(original);
          after.setImageBitmap(enhanced);
          save.setEnabled(true);share.setEnabled(true);
          status.setText("Обработано: "+enhanced.getWidth()+" × "+enhanced.getHeight()+" пикселей. Теперь можно сохранить или отправить.");
        });
      }catch(Throwable error){
        runOnUiThread(()->status.setText("Ошибка обработки: "+error.getClass().getSimpleName()+" — "+error.getMessage()));
      }finally{
        runOnUiThread(()->{processing=false;select.setEnabled(true);});
      }
    });
  }
  private void saveResult(boolean shareAfter){
    final Bitmap image;
    synchronized(imageLock){image=result;}
    if(image==null)return;
    save.setEnabled(false);share.setEnabled(false);
    status.setText("Сохраняю изображение…");
    worker.execute(()->{
      try{
        Uri uri;
        synchronized(imageLock){uri=savedUri;}
        if(uri==null){
          ContentValues cv=new ContentValues();
          cv.put(MediaStore.Images.Media.DISPLAY_NAME,"GT-SuperRes-AI-"+System.currentTimeMillis()+".png");
          cv.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
          cv.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/GT-SuperRes");
          uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);
          if(uri==null)throw new IOException("Не удалось создать изображение");
          try(OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null||!image.compress(Bitmap.CompressFormat.PNG,100,out))
              throw new IOException("Ошибка записи PNG");
          }
          synchronized(imageLock){savedUri=uri;}
        }
        final Uri ready=uri;
        runOnUiThread(()->{
          save.setEnabled(true);share.setEnabled(true);
          status.setText("Изображение сохранено в Pictures/GT-SuperRes");
          if(shareAfter){
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM,ready);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send,"Отправить изображение"));
          }
        });
      }catch(Exception error){
        runOnUiThread(()->{
          save.setEnabled(true);share.setEnabled(true);
          status.setText("Не удалось сохранить: "+error.getMessage());
        });
      }
    });
  }
}
