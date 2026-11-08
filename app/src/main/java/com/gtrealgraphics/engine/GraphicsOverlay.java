package com.gtrealgraphics.engine;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;

public final class GraphicsOverlay extends GLSurfaceView {
    private final GpuPostProcessor processor;

    public GraphicsOverlay(Context context) {
        super(context);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setZOrderOnTop(true);
        processor = new GpuPostProcessor(context);
        setRenderer(processor);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);
    }

    public byte[] obtainFrameBuffer(int byteCount) {
        return processor.obtainFrameBuffer(byteCount);
    }

    public void submitFrame(byte[] rgba, int width, int height) {
        processor.submitFrame(rgba, width, height);
        requestRender();
    }

    public void setSettings(GpuPostProcessor.Settings settings) {
        processor.setSettings(settings);
        requestRender();
    }

    public void releaseRenderer() {
        try { onPause(); } catch (Exception ignored) {}
        processor.release();
    }
}
