package com.gtrealgraphics.engine;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int REQUEST_OVERLAY = 4101;
    private static final int REQUEST_CAPTURE = 4102;
    private GpuPostProcessor.Settings settings = GpuPostProcessor.Settings.defaults();
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8, 16, 29));
        getWindow().setNavigationBarColor(Color.rgb(8, 16, 29));
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(8, 16, 29));
        scroll.addView(root);

        TextView title = text("GT REAL GRAPHICS ENGINE", 25, 0xfff0f6ff);
        title.setTypeface(null, 1);
        root.addView(title);
        TextView subtitle = text("GPU SCREEN POST-PROCESSOR • ANDROID 14+", 11, 0xff55dfc2);
        subtitle.setPadding(0, dp(5), 0, dp(14));
        root.addView(subtitle);

        TextView description = text(
            "Отдельный GPU-конвейер для обработки выбранного окна игры. Игровые файлы Standoff 2 не меняются.",
            14, 0xffb8c6d8);
        root.addView(description);

        Button start = button("ЗАПУСТИТЬ ДЛЯ STANDOFF 2");
        root.addView(start);
        start.setOnClickListener(v -> startCaptureFlow());

        Button stop = button("ОСТАНОВИТЬ ОБРАБОТКУ");
        root.addView(stop);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, GraphicsCaptureService.class));
            status.setText("Обработка остановлена.");
        });

        heading(root, "НАСТРОЙКИ GPU");
        addSlider(root, "Насыщенность", 50, 160, 112,
            v -> settings.saturation = v / 100f);
        addSlider(root, "Контраст", 70, 140, 108,
            v -> settings.contrast = v / 100f);
        addSlider(root, "Экспозиция", 70, 140, 103,
            v -> settings.exposure = v / 100f);
        addSlider(root, "HDR LOOK (тональная стилизация)", 0, 100, 18,
            v -> settings.hdrLook = v / 100f);
        addSlider(root, "Bloom / свечение", 0, 80, 18,
            v -> settings.bloom = v / 100f);
        addSlider(root, "Чёткость деталей", 0, 80, 22,
            v -> settings.sharpness = v / 100f);
        addSlider(root, "Осветление деталей в тенях", 0, 30, 8,
            v -> settings.shadowLift = v / 100f);

        TextView note = text(
            "Важно: в системном окне захвата выбери именно Standoff 2 (одно приложение), а не весь экран. " +
            "Тогда наше наложение не должно попадать обратно в захват.",
            13, 0xffffd28a);
        note.setPadding(0, dp(16), 0, dp(8));
        root.addView(note);

        TextView limits = text(
            "Это GPU-постобработка изображения, а не замена игрового движка. Она создаёт HDR-подобную тональную стилизацию, " +
            "свечение и повышение локальной чёткости. Настоящие новые 3D-тени, геометрические отражения и 144 обработанных FPS " +
            "не гарантируются: Android не передаёт приложению глубину, нормали и игровые источники света.",
            12, 0xff8fa3ba);
        root.addView(limits);

        status = text("Готово. Для запуска нужны разрешение на отображение поверх других приложений и системное разрешение захвата.", 13, 0xff69e1c4);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);
        setContentView(scroll);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(6));
        return t;
    }

    private void heading(LinearLayout root, String value) {
        TextView h = text(value, 17, 0xffeaf2ff);
        h.setTypeface(null, 1);
        h.setPadding(0, dp(20), 0, dp(5));
        root.addView(h);
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xfff4fbff);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff176e78));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.topMargin = dp(9);
        b.setLayoutParams(p);
        return b;
    }

    private void addSlider(LinearLayout root, String label, int min, int max, int initial, IntConsumer onChange) {
        TextView caption = text(label + ": " + initial, 13, 0xffc6d3e2);
        caption.setPadding(0, dp(12), 0, 0);
        root.addView(caption);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        root.addView(seek, new LinearLayout.LayoutParams(-1, dp(38)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = min + progress;
                caption.setText(label + ": " + value);
                onChange.accept(value);
                persistAndApplySettings();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void persistAndApplySettings() {
        getSharedPreferences("gt_graphics", MODE_PRIVATE).edit()
            .putFloat("saturation", settings.saturation)
            .putFloat("contrast", settings.contrast)
            .putFloat("exposure", settings.exposure)
            .putFloat("hdrLook", settings.hdrLook)
            .putFloat("bloom", settings.bloom)
            .putFloat("sharpness", settings.sharpness)
            .putFloat("shadowLift", settings.shadowLift)
            .apply();
        GraphicsCaptureService.updateSettings(settings);
    }

    private void startCaptureFlow() {
        if (Build.VERSION.SDK_INT < 34) {
            status.setText("Для безопасного захвата одного приложения требуется Android 14 или новее.");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            status.setText("Разреши отображение GT поверх других приложений.");
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(permission, REQUEST_OVERLAY);
            return;
        }
        showCaptureInstructions();
    }

    private void showCaptureInstructions() {
        new AlertDialog.Builder(this)
            .setTitle("Выбери Standoff 2")
            .setMessage("В следующем системном окне выбери захват ОДНОГО ПРИЛОЖЕНИЯ и укажи Standoff 2. Не выбирай весь экран: иначе Android может захватить и наше графическое наложение.")
            .setNegativeButton("Отмена", (d, w) -> {})
            .setPositiveButton("Продолжить", (d, w) -> requestCapture())
            .show();
    }

    private void requestCapture() {
        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        MediaProjectionConfig config = MediaProjectionConfig.createConfigForUserChoice();
        startActivityForResult(manager.createScreenCaptureIntent(config), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) showCaptureInstructions();
            else status.setText("Разрешение на отображение поверх приложений не выдано.");
            return;
        }
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                status.setText("Захват экрана отменён.");
                return;
            }
            Intent service = new Intent(this, GraphicsCaptureService.class);
            service.putExtra(GraphicsCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(GraphicsCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("GT запущен. Проверь FPS и изображение в Standoff 2. Остановка — кнопкой выше или через уведомление.");
        }
    }
}
