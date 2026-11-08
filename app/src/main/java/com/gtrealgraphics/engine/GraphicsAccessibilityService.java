package com.gtrealgraphics.engine;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Hosts the opaque graphics surface as a trusted accessibility overlay.
 * It does not inspect window content, perform gestures, or consume touch input.
 */
public final class GraphicsAccessibilityService extends AccessibilityService {
    private static final String TARGET_GAME_PACKAGE="com.axlebolt.standoff2";
    private static volatile GraphicsAccessibilityService instance;
    private static volatile boolean targetGameForeground;
    private WindowManager windowManager;
    private View attachedOverlay;

    public static boolean isEnabled(Context context) {
        String enabled=Settings.Secure.getString(
            context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(enabled==null)return false;
        String expected=new ComponentName(context,GraphicsAccessibilityService.class).flattenToString();
        for(String entry:enabled.split(":"))if(expected.equalsIgnoreCase(entry))return true;
        return false;
    }

    public static boolean isConnected(){return instance!=null;}
    public static boolean isTargetGameForeground(){return targetGameForeground;}
    public static Context activeContext(){GraphicsAccessibilityService s=instance;return s;}

    public static boolean showOverlay(View view){
        GraphicsAccessibilityService s=instance;
        return s!=null&&s.attach(view);
    }

    public static void hideOverlay(View view){
        GraphicsAccessibilityService s=instance;
        if(s!=null)s.detach(view);
    }

    private boolean attach(View view){
        if(view==null||windowManager==null)return false;
        view.setClickable(false);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        if(attachedOverlay==view)return true;
        if(attachedOverlay!=null)detach(attachedOverlay);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE);
        p.gravity=Gravity.TOP|Gravity.START;
        p.alpha=1.0f;
        p.setTitle("GT Real Graphics Engine");
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try{
            windowManager.addView(view,p);
            attachedOverlay=view;
            return true;
        }catch(Throwable error){
            android.util.Log.e("GT-Accessibility","Could not attach trusted graphics overlay",error);
            return false;
        }
    }

    private void detach(View view){
        if(view==null||windowManager==null||attachedOverlay!=view)return;
        try{windowManager.removeViewImmediate(view);}catch(Exception ignored){}
        attachedOverlay=null;
    }

    @Override public void onServiceConnected(){
        super.onServiceConnected();
        windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
        instance=this;
        targetGameForeground=false;
        android.util.Log.i("GT-Accessibility","Trusted non-touchable overlay service connected; accessibility window allows pass-through");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null||event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        CharSequence packageName=event.getPackageName();
        if(packageName==null)return;
        String foregroundPackage=packageName.toString();
        if(foregroundPackage.isEmpty())return;
        if(getPackageName().equals(foregroundPackage)){
            CharSequence className=event.getClassName();
            if(className!=null&&className.toString().endsWith("MainActivity")){
                targetGameForeground=false;
                GraphicsCaptureService.setGameForeground(false);
            }
            return;
        }
        // Read only the foreground package name; never retrieve window text or view content.
        targetGameForeground=TARGET_GAME_PACKAGE.equals(foregroundPackage);
        GraphicsCaptureService.setGameForeground(targetGameForeground);
    }

    @Override public void onInterrupt(){}

    @Override public boolean onUnbind(Intent intent){
        detach(attachedOverlay);
        if(instance==this)instance=null;
        GraphicsCaptureService.stopActiveCapture();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy(){
        detach(attachedOverlay);
        if(instance==this)instance=null;
        super.onDestroy();
    }
}
