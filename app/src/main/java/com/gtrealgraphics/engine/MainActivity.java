package com.gtrealgraphics.engine;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE=4102;
    private GpuPostProcessor.Settings settings=GpuPostProcessor.Settings.defaults();
    private TextView status;
    private LinearLayout root;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7,13,24));
        getWindow().setNavigationBarColor(Color.rgb(7,13,24));
        settings=loadSettings();
        buildUi();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},77);
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(6));return t;}
    private void heading(String s){TextView t=text(s,16,0xffedf4ff);t.setTypeface(null,1);t.setPadding(0,dp(20),0,dp(7));root.addView(t);}
    private Button button(String s,boolean primary){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(0xfff4fbff);
        b.setBackgroundTintList(ColorStateList.valueOf(primary?0xff147b83:0xff26384c));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.topMargin=dp(7);b.setLayoutParams(p);return b;
    }
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(19),dp(22),dp(19),dp(32));root.setBackgroundColor(0xff07101e);scroll.addView(root);
        TextView title=text("GT REAL GRAPHICS ENGINE 0.3.2",24,0xfff2f7ff);title.setTypeface(null,1);root.addView(title);
        TextView tag=text("REAL-TIME GPU PIPELINE  •  GLES 3.0  •  ANDROID 14+",10,0xff58e1c4);root.addView(tag);
        TextView intro=text("GPU-native захват выбранного окна и полноэкранная обработка. Доверенное системное наложение не принимает касания: Android направляет их в Standoff 2. Сервис проверяет только имя активного пакета, чтобы скрывать графический слой вне игры; содержимое окон не читается, жесты не выполняются.",13,0xffb7c8dc);intro.setPadding(0,dp(12),0,dp(8));root.addView(intro);

        Button start=button("ЗАПУСТИТЬ ОБРАБОТКУ",true);root.addView(start);start.setOnClickListener(v->startCaptureFlow());
        Button stop=button("ОСТАНОВИТЬ",false);root.addView(stop);stop.setOnClickListener(v->{stopService(new Intent(this,GraphicsCaptureService.class));setStatus("Обработка остановлена.");});

        heading("ПРОФИЛИ ИЗОБРАЖЕНИЯ");
        LinearLayout presets=new LinearLayout(this);presets.setOrientation(LinearLayout.HORIZONTAL);root.addView(presets);
        Button natural=smallButton("NATURAL");Button cinematic=smallButton("CINEMATIC");Button competitive=smallButton("COMPETITIVE");
        presets.addView(natural,new LinearLayout.LayoutParams(0,dp(46),1));presets.addView(cinematic,new LinearLayout.LayoutParams(0,dp(46),1));presets.addView(competitive,new LinearLayout.LayoutParams(0,dp(46),1));
        natural.setOnClickListener(v->applyPreset(GpuPostProcessor.Settings.defaults(),"Натуральный профиль"));
        cinematic.setOnClickListener(v->applyPreset(GpuPostProcessor.Settings.cinematic(),"Кинематографический профиль"));
        competitive.setOnClickListener(v->applyPreset(GpuPostProcessor.Settings.competitive(),"Соревновательный профиль"));

        heading("КАЧЕСТВО / ПРОИЗВОДИТЕЛЬНОСТЬ");
        slider("Разрешение GPU-проходов",50,100,Math.round(settings.renderScale*100),v->settings.renderScale=v/100f);

        heading("ТОНАЛЬНАЯ СТИЛИЗАЦИЯ");
        slider("Экспозиция",70,135,Math.round(settings.exposure*100),v->settings.exposure=v/100f);
        slider("Контраст",75,145,Math.round(settings.contrast*100),v->settings.contrast=v/100f);
        slider("Насыщенность",70,150,Math.round(settings.saturation*100),v->settings.saturation=v/100f);
        slider("HDR look / filmic roll-off",0,70,Math.round(settings.hdrLook*100),v->settings.hdrLook=v/100f);
        slider("Цветовая температура", -20,20,Math.round(settings.temperature*100),v->settings.temperature=v/100f);

        heading("СВЕТОВЫЕ ЭФФЕКТЫ");
        slider("Bloom strength",0,80,Math.round(settings.bloom*100),v->settings.bloom=v/100f);
        slider("Порог свечения",40,100,Math.round(settings.bloomThreshold*100),v->settings.bloomThreshold=v/100f);
        slider("Vignette",0,40,Math.round(settings.vignette*100),v->settings.vignette=v/100f);

        heading("ДЕТАЛИ И ЛОКАЛЬНЫЙ КОНТРАСТ");
        slider("Резкость",0,50,Math.round(settings.sharpness*100),v->settings.sharpness=v/100f);
        slider("Локальный контраст",0,40,Math.round(settings.localContrast*100),v->settings.localContrast=v/100f);
        slider("Подъём деталей в тенях",0,20,Math.round(settings.shadowLift*100),v->settings.shadowLift=v/100f);

        heading("ТЕХНИЧЕСКАЯ ИНФОРМАЦИЯ");
        TextView note=text("Захват передаётся прямо в SurfaceTexture и обрабатывается на GPU. Bloom построен как отдельный quarter-resolution pass с Gaussian blur по двум осям. В уведомлении отдельно показаны входной FPS захвата и частота отправки обработанных кадров на экран; это не измерение завершения GPU-команд и не гарантия частоты дисплея.",12,0xffaabbd0);root.addView(note);
        TextView limits=text("Это улучшает уже отрисованную картинку. Настоящие 3D-тени, ray-traced reflections и геометрическую глубину нельзя восстановить из обычного экранного изображения. Shadow lift и локальный контраст — приближённые image-space эффекты, не новые игровые тени.",12,0xffffd08c);limits.setPadding(0,dp(8),0,0);root.addView(limits);
        status=text("Статус: готов к запуску. Разреши отображение поверх приложений и выбери Standoff 2 в системном окне захвата.",12,0xff62e2c5);status.setPadding(0,dp(18),0,0);root.addView(status);
        setContentView(scroll);
    }
    private Button smallButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(10);b.setAllCaps(false);b.setTextColor(0xfff0f6ff);b.setBackgroundTintList(ColorStateList.valueOf(0xff26384c));return b;}
    private void slider(String label,int min,int max,int initial,IntConsumer update){
        int value=Math.max(min,Math.min(max,initial));
        TextView caption=text(label+": "+value,12,0xffc8d5e4);caption.setPadding(0,dp(10),0,0);root.addView(caption);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(value-min);root.addView(bar,new LinearLayout.LayoutParams(-1,dp(35)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int p,boolean fromUser){int v=min+p;caption.setText(label+": "+v);update.accept(v);persistAndApply();}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private GpuPostProcessor.Settings loadSettings(){
        android.content.SharedPreferences p=getSharedPreferences("gt_graphics",MODE_PRIVATE);
        GpuPostProcessor.Settings s=GpuPostProcessor.Settings.defaults();
        if(p.getInt("settings_version",0)<2){
            p.edit().putInt("settings_version",2)
                .putFloat("saturation",s.saturation).putFloat("contrast",s.contrast)
                .putFloat("exposure",s.exposure).putFloat("hdrLook",s.hdrLook)
                .putFloat("bloom",s.bloom).putFloat("sharpness",s.sharpness)
                .putFloat("shadowLift",s.shadowLift).putFloat("localContrast",s.localContrast)
                .putFloat("temperature",s.temperature).putFloat("vignette",s.vignette)
                .putFloat("bloomThreshold",s.bloomThreshold).putFloat("renderScale",s.renderScale).apply();
            return s;
        }
        s.saturation=p.getFloat("saturation",s.saturation);s.contrast=p.getFloat("contrast",s.contrast);s.exposure=p.getFloat("exposure",s.exposure);
        s.hdrLook=p.getFloat("hdrLook",s.hdrLook);s.bloom=p.getFloat("bloom",s.bloom);s.sharpness=p.getFloat("sharpness",s.sharpness);
        s.shadowLift=p.getFloat("shadowLift",s.shadowLift);s.localContrast=p.getFloat("localContrast",s.localContrast);s.temperature=p.getFloat("temperature",s.temperature);
        s.vignette=p.getFloat("vignette",s.vignette);s.bloomThreshold=p.getFloat("bloomThreshold",s.bloomThreshold);s.renderScale=p.getFloat("renderScale",s.renderScale);return s;
    }
    private void applyPreset(GpuPostProcessor.Settings s,String label){settings=s;persistAndApply();buildUi();setStatus(label+" применён.");}
    private void persistAndApply(){
        getSharedPreferences("gt_graphics",MODE_PRIVATE).edit()
            .putInt("settings_version",2)
            .putFloat("saturation",settings.saturation).putFloat("contrast",settings.contrast).putFloat("exposure",settings.exposure)
            .putFloat("hdrLook",settings.hdrLook).putFloat("bloom",settings.bloom).putFloat("sharpness",settings.sharpness)
            .putFloat("shadowLift",settings.shadowLift).putFloat("localContrast",settings.localContrast).putFloat("temperature",settings.temperature)
            .putFloat("vignette",settings.vignette).putFloat("bloomThreshold",settings.bloomThreshold).putFloat("renderScale",settings.renderScale).apply();
        GraphicsCaptureService.updateSettings(settings);
    }
    private void setStatus(String message){if(status!=null)status.setText("Статус: "+message);}
    private void startCaptureFlow(){
        if(Build.VERSION.SDK_INT<34){setStatus("Для захвата одного приложения требуется Android 14 или новее.");return;}
        if(!GraphicsAccessibilityService.isEnabled(this)){
            new AlertDialog.Builder(this)
                .setTitle("Нужно включить доверенное наложение")
                .setMessage("Android 12+ блокирует касания сквозь обычные непрозрачные окна. Поэтому движок использует системный тип окна Accessibility Overlay. Сервис нужен только для показа обработанной картинки и пропуска касаний в Standoff 2: он не читает содержимое окон, не нажимает кнопки и не выполняет жесты. Включи GT Real Graphics Engine в настройках специальных возможностей.")
                .setNegativeButton("Отмена",(d,w)->{})
                .setPositiveButton("Открыть настройки",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
            return;
        }
        if(!GraphicsAccessibilityService.isConnected()){
            setStatus("Специальные возможности включены. Подожди пару секунд и нажми запуск ещё раз.");
            return;
        }
        showCaptureInstructions();
    }
    private void showCaptureInstructions(){
        new AlertDialog.Builder(this).setTitle("Выбери Standoff 2")
            .setMessage("В системном окне выбери захват ОДНОГО ПРИЛОЖЕНИЯ и укажи Standoff 2. Не выбирай весь экран: иначе обработчик может захватить собственное наложение.")
            .setNegativeButton("Отмена",(d,w)->{}).setPositiveButton("Продолжить",(d,w)->requestCapture()).show();
    }
    private void requestCapture(){
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()),REQUEST_CAPTURE);
    }
    @Override protected void onResume(){
        super.onResume();
        if(status!=null&&GraphicsAccessibilityService.isEnabled(this)){
            setStatus(GraphicsAccessibilityService.isConnected()
                ?"Доверенное наложение подключено. Можно запускать захват Standoff 2."
                :"Специальные возможности включены; ожидаю подключения системного сервиса.");
        }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQUEST_CAPTURE){
            if(resultCode!=RESULT_OK||data==null){setStatus("Системный захват отменён.");return;}
            Intent service=new Intent(this,GraphicsCaptureService.class);service.putExtra(GraphicsCaptureService.EXTRA_RESULT_CODE,resultCode);service.putExtra(GraphicsCaptureService.EXTRA_RESULT_DATA,data);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
            setStatus("GPU-захват запускается. FPS будет показан в уведомлении.");
        }
    }
}
