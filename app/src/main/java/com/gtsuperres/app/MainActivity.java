package com.gtsuperres.app;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.Manifest;
import android.media.projection.*;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
public class MainActivity extends Activity {
  private static final int CAPTURE=101, OVERLAY=102;
  private TextView status;
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout layout=new LinearLayout(this);
    layout.setOrientation(1); layout.setPadding(38,70,38,30); layout.setBackgroundColor(0xff101825);
    TextView title=new TextView(this); title.setText("GT SuperRes"); title.setTextSize(30); title.setTextColor(-1);
    layout.addView(title);
    status=new TextView(this); status.setTextColor(0xffb7cbeb);
    status.setText("GPU-фильтр поверх экрана. Не является NVIDIA DLSS и не увеличивает игровой FPS.\nДля работы нужен доступ к захвату экрана.");
    status.setTextSize(16); status.setPadding(0,30,0,30); layout.addView(status);
    Button start=new Button(this);start.setText("Включить фильтр");layout.addView(start);
    start.setOnClickListener(v -> startFilter());
    Button stop=new Button(this);stop.setText("Выключить фильтр");layout.addView(stop);
    stop.setOnClickListener(v -> {stopService(new Intent(this,FilterService.class));status.setText("Фильтр остановлен");});
    TextView info=new TextView(this);info.setTextColor(0xffa0b5ca);
    info.setText("\nAndroid может показать эффект повторного захвата экрана или задержки. При проблемах останови фильтр через уведомление.");
    layout.addView(info);
    setContentView(layout);
    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},110);
  }
  private void startFilter() {
    if(!Settings.canDrawOverlays(this)){
      startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())), OVERLAY);
      return;
    }
    MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
    startActivityForResult(m.createScreenCaptureIntent(),CAPTURE);
  }
  @Override protected void onActivityResult(int request,int code,Intent data){
    super.onActivityResult(request,code,data);
    if(request==OVERLAY){ if(Settings.canDrawOverlays(this))startFilter();return;}
    if(request==CAPTURE&&code==RESULT_OK&&data!=null){
      Intent intent=new Intent(this,FilterService.class);
      intent.setAction("START");intent.putExtra("resultCode",code);intent.putExtra("resultData",data);
      if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
      status.setText("Фильтр запущен. Можно открыть другую программу.");
    }
  }
}
