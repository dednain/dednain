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
    public static void forceHideOverlay(){
        targetGameForeground=false;
        setOverlayVisible(false);
        GraphicsCaptureService.setGameForeground(false);
    }
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
        if(attachedOverlay==view){
            view.setVisibility(targetGameForeground?View.VISIBLE:View.INVISIBLE);
            return true;
        }
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
        // GLSurfaceView owns a separate Surface. Visibility, not View alpha, is the
        // reliable way to remove that Surface from composition while another app is open.
        view.setAlpha(1.0f);
        view.setVisibility(targetGameForeground?View.VISIBLE:View.INVISIBLE);
        try{
            windowManager.addView(view,p);
            attachedOverlay=view;
            attachedParams=p;
            return true;
        }catch(Throwable error){
            android.util.Log.e("GT-Accessibility","Could not attach trusted graphics overlay",error);
            try{view.setVisibility(View.INVISIBLE);}catch(Exception ignored){}
            return false;
        }
    }

    private void detach(View view){
        if(view==null||windowManager==null||attachedOverlay!=view)return;
        try{view.setVisibility(View.INVISIBLE);}catch(Exception ignored){}
        try{windowManager.removeViewImmediate(view);}catch(Exception ignored){}
        attachedOverlay=null;
        attachedParams=null;
    }

    public static void setOverlayVisible(boolean visible){
        GraphicsAccessibilityService s=instance;
        if(s==null)return;
        s.runOnMainThread(()->{
            View view=s.attachedOverlay;
            WindowManager.LayoutParams params=s.attachedParams;
            if(view==null||params==null)return;
            // A queued show request must not resurrect the overlay after a newer hide.
            if(visible&&!targetGameForeground)return;
            try{
                if(!visible){
                    // Hide the SurfaceView itself first; alpha alone can leave its
                    // separately-composited last frame visible over other apps.
                    view.setVisibility(View.INVISIBLE);
                }else{
                    view.setAlpha(1.0f);
                    view.setVisibility(View.VISIBLE);
                }
                params.alpha=1.0f;
                s.windowManager.updateViewLayout(view,params);
            }catch(Exception error){
                android.util.Log.e("GT-Accessibility","Overlay visibility update failed; detaching for safety",error);
                s.detach(view);
            }
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
        android.util.Log.i("GT-Accessibility","Connected; overlay starts hidden and is shown only while Standoff 2 is foreground");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(event==null||event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        CharSequence name=event.getPackageName();
        if(name==null)return;
        String packageName=name.toString();
        if(packageName.isEmpty()||getPackageName().equals(packageName))return;
        // Fail closed: any confirmed non-game foreground activity (including
        // System UI, launcher, notification shade and TikTok) hides the Surface.
        // The effect is shown again only after Standoff 2 becomes active.
        boolean game=TARGET_GAME_PACKAGE.equals(packageName);
        updateGameForeground(game,game?"Standoff 2 became active":"non-game activity: "+packageName);
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
