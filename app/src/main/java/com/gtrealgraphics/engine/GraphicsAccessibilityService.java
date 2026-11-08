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
    private WindowManager.LayoutParams attachedParams;

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
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
        p.alpha=targetGameForeground?1.0f:0.0f;
        p.setTitle("GT Real Graphics Engine");
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try{
            windowManager.addView(view,p);
            attachedOverlay=view;
            attachedParams=p;
            view.setAlpha(targetGameForeground?1.0f:0.0f);
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
        attachedParams=null;
    }

    public static void setOverlayVisible(boolean visible){
        GraphicsAccessibilityService s=instance;
        if(s==null||s.attachedOverlay==null||s.attachedParams==null)return;
        s.runOnMainThread(()->{
            if(s.attachedOverlay==null||s.attachedParams==null)return;
            float alpha=visible?1.0f:0.0f;
            s.attachedParams.alpha=alpha;
            s.attachedOverlay.setAlpha(alpha);
            try{s.windowManager.updateViewLayout(s.attachedOverlay,s.attachedParams);}
            catch(Exception error){android.util.Log.w("GT-Accessibility","Could not update overlay window alpha",error);}
        });
    }
    private void runOnMainThread(Runnable action){
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action.run();
        else new android.os.Handler(android.os.Looper.getMainLooper()).post(action);
    }

    @Override public void onServiceConnected(){
        super.onServiceConnected();
        windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
        instance=this;
        targetGameForeground=false;
        android.util.Log.i("GT-Accessibility","Connected; overlay is non-touchable and visible only for Standoff 2");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null)return;
        int type=event.getEventType();
        if(type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&type!=AccessibilityEvent.TYPE_WINDOWS_CHANGED)return;
        CharSequence name=event.getPackageName();if(name==null)return;
        String packageName=name.toString();if(packageName.isEmpty())return;
        if(getPackageName().equals(packageName))return; // Our own overlay must not hide itself.
        updateGameForeground(TARGET_GAME_PACKAGE.equals(packageName),"window event: "+packageName);
    }
    private void updateGameForeground(boolean foreground,String reason){
        if(targetGameForeground==foreground)return;targetGameForeground=foreground;
        android.util.Log.i("GT-Accessibility","Game foreground="+foreground+"; reason="+reason);
        GraphicsCaptureService.setGameForeground(foreground);
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
