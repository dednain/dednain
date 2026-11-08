package com.gtrealgraphics.engine;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.view.Surface;

public final class GraphicsOverlay extends GLSurfaceView {
    private final GpuPostProcessor processor;

    public GraphicsOverlay(Context context) {
        super(context);
        getHolder().setFormat(PixelFormat.OPAQUE);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        // Keep the SurfaceView inside the accessibility window's normal layer;
        // forcing it above the window can leave a stale surface visible.
        setAlpha(1.0f);
        setVisibility(android.view.View.INVISIBLE);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        processor = new GpuPostProcessor(context);
        processor.setRenderRequest(this::requestRender);
        setRenderer(processor);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
    }

    public void setInputSurfaceReadyListener(GpuPostProcessor.SurfaceReadyListener listener) {
        processor.setSurfaceReadyListener(listener);
    }

    public void setCaptureSize(int width, int height) {
        processor.setCaptureSize(width, height);
    }

    public void setFrameAvailableListener(GpuPostProcessor.FrameAvailableListener listener) {
        processor.setFrameAvailableListener(listener);
    }

    public void setFrameRenderedListener(GpuPostProcessor.FrameRenderedListener listener) {
        processor.setFrameRenderedListener(listener);
    }

    public void setRendererErrorListener(GpuPostProcessor.RendererErrorListener listener) {
        processor.setRendererErrorListener(listener);
    }

    public void setRendererInfoListener(GpuPostProcessor.RendererInfoListener listener) {
        processor.setRendererInfoListener(listener);
    }

    public void setSettings(GpuPostProcessor.Settings settings) {
        processor.setSettings(settings);
        requestRender();
    }

    public void releaseRenderer() {
        try {
            queueEvent(processor::releaseGlResources);
            onPause();
        } catch (Exception ignored) {
            // The EGL context may already have been destroyed by Android.
        }
    }
}
