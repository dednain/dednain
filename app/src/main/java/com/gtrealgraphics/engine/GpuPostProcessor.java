package com.gtrealgraphics.engine;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class GpuPostProcessor implements GLSurfaceView.Renderer {
    private static final String TAG = "GT-GPU";
    private static final String VERTEX_SHADER =
        "#version 300 es\n" +
        "layout(location=0) in vec2 aPosition;\n" +
        "layout(location=1) in vec2 aTexCoord;\n" +
        "out vec2 vTexCoord;\n" +
        "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }\n";

    private static final String FRAGMENT_SHADER =
        "#version 300 es\n" +
        "precision highp float;\n" +
        "in vec2 vTexCoord;\n" +
        "uniform sampler2D uTexture;\n" +
        "uniform vec2 uTexel;\n" +
        "uniform float uSaturation;\n" +
        "uniform float uContrast;\n" +
        "uniform float uExposure;\n" +
        "uniform float uBloom;\n" +
        "uniform float uSharpness;\n" +
        "uniform float uHdrLook;\n" +
        "uniform float uShadowLift;\n" +
        "out vec4 fragColor;\n" +
        "vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0); }\n" +
        "void main(){\n" +
        "  vec3 c=texture(uTexture,vTexCoord).rgb;\n" +
        "  vec3 l=texture(uTexture,vTexCoord-vec2(uTexel.x,0.0)).rgb;\n" +
        "  vec3 r=texture(uTexture,vTexCoord+vec2(uTexel.x,0.0)).rgb;\n" +
        "  vec3 u=texture(uTexture,vTexCoord-vec2(0.0,uTexel.y)).rgb;\n" +
        "  vec3 d=texture(uTexture,vTexCoord+vec2(0.0,uTexel.y)).rgb;\n" +
        "  vec3 average4=(l+r+u+d)*0.25;\n" +
        "  vec3 detail=c-average4;\n" +
        "  vec3 sharpened=c+detail*uSharpness;\n" +
        "  vec3 glowSource=(c+l+r+u+d)*0.2;\n" +
        "  vec3 glow=max(glowSource-vec3(0.66),vec3(0.0));\n" +
        "  vec3 color=sharpened+glow*uBloom;\n" +
        "  float luminance=dot(color,vec3(0.2126,0.7152,0.0722));\n" +
        "  float shadowMask=1.0-smoothstep(0.025,0.42,luminance);\n" +
        "  color+=shadowMask*uShadowLift;\n" +
        "  float gray=dot(color,vec3(0.2126,0.7152,0.0722));\n" +
        "  color=mix(vec3(gray),color,uSaturation);\n" +
        "  color=(color-vec3(0.5))*uContrast+vec3(0.5);\n" +
        "  color*=uExposure;\n" +
        "  color=mix(color,aces(max(color,vec3(0.0))),uHdrLook*0.42);\n" +
        "  fragColor=vec4(clamp(color,0.0,1.0),1.0);\n" +
        "}\n";

    private static final float[] QUAD = {
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f
    };

    public static final class Settings {
        public float saturation = 1.12f;
        public float contrast = 1.08f;
        public float exposure = 1.03f;
        public float bloom = 0.18f;
        public float sharpness = 0.22f;
        public float hdrLook = 0.18f;
        public float shadowLift = 0.08f;

        public static Settings defaults() { return new Settings(); }

        public Settings copy() {
            Settings s = new Settings();
            s.saturation = saturation;
            s.contrast = contrast;
            s.exposure = exposure;
            s.bloom = bloom;
            s.sharpness = sharpness;
            s.hdrLook = hdrLook;
            s.shadowLift = shadowLift;
            return s;
        }
    }

    private static final class Frame {
        final byte[] rgba;
        final int width;
        final int height;
        Frame(byte[] rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }

    private final Context context;
    private final ArrayDeque<byte[]> bufferPool = new ArrayDeque<>();
    private Frame pendingFrame;
    private volatile Settings settings = Settings.defaults();
    private volatile boolean released;
    private int program;
    private int texture;
    private int textureWidth;
    private int textureHeight;
    private int positionLocation;
    private int uvLocation;
    private int samplerLocation;
    private int texelLocation;
    private int saturationLocation;
    private int contrastLocation;
    private int exposureLocation;
    private int bloomLocation;
    private int sharpnessLocation;
    private int hdrLocation;
    private int shadowLocation;
    private FloatBuffer quadBuffer;
    private ByteBuffer uploadBuffer;

    public GpuPostProcessor(Context context) {
        this.context = context.getApplicationContext();
        ByteBuffer vertices = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder());
        quadBuffer = vertices.asFloatBuffer();
        quadBuffer.put(QUAD).position(0);
    }

    public synchronized byte[] obtainFrameBuffer(int byteCount) {
        byte[] candidate = null;
        while (!bufferPool.isEmpty()) {
            byte[] item = bufferPool.removeFirst();
            if (item.length == byteCount) {
                candidate = item;
                break;
            }
        }
        return candidate != null ? candidate : new byte[byteCount];
    }

    public synchronized void submitFrame(byte[] rgba, int width, int height) {
        if (released || rgba == null) {
            recycle(rgba);
            return;
        }
        Frame old = pendingFrame;
        pendingFrame = new Frame(rgba, width, height);
        if (old != null) recycle(old.rgba);
    }

    public void setSettings(Settings newSettings) {
        if (newSettings != null) settings = newSettings.copy();
    }

    public synchronized void release() {
        released = true;
        if (pendingFrame != null) {
            recycle(pendingFrame.rgba);
            pendingFrame = null;
        }
        bufferPool.clear();
    }

    private synchronized void recycle(byte[] bytes) {
        if (bytes != null && !released && bufferPool.size() < 3) bufferPool.addLast(bytes);
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES30.glClearColor(0f, 0f, 0f, 0f);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        texture = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        positionLocation = GLES30.glGetAttribLocation(program, "aPosition");
        uvLocation = GLES30.glGetAttribLocation(program, "aTexCoord");
        samplerLocation = GLES30.glGetUniformLocation(program, "uTexture");
        texelLocation = GLES30.glGetUniformLocation(program, "uTexel");
        saturationLocation = GLES30.glGetUniformLocation(program, "uSaturation");
        contrastLocation = GLES30.glGetUniformLocation(program, "uContrast");
        exposureLocation = GLES30.glGetUniformLocation(program, "uExposure");
        bloomLocation = GLES30.glGetUniformLocation(program, "uBloom");
        sharpnessLocation = GLES30.glGetUniformLocation(program, "uSharpness");
        hdrLocation = GLES30.glGetUniformLocation(program, "uHdrLook");
        shadowLocation = GLES30.glGetUniformLocation(program, "uShadowLift");
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (program == 0 || texture == 0) return;

        Frame frame;
        synchronized (this) {
            frame = pendingFrame;
            pendingFrame = null;
        }
        if (frame != null) {
            upload(frame);
            recycle(frame.rgba);
        }
        if (textureWidth <= 0 || textureHeight <= 0) return;

        Settings s = settings;
        GLES30.glUseProgram(program);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glUniform1i(samplerLocation, 0);
        GLES30.glUniform2f(texelLocation, 1f / textureWidth, 1f / textureHeight);
        GLES30.glUniform1f(saturationLocation, s.saturation);
        GLES30.glUniform1f(contrastLocation, s.contrast);
        GLES30.glUniform1f(exposureLocation, s.exposure);
        GLES30.glUniform1f(bloomLocation, s.bloom);
        GLES30.glUniform1f(sharpnessLocation, s.sharpness);
        GLES30.glUniform1f(hdrLocation, s.hdrLook);
        GLES30.glUniform1f(shadowLocation, s.shadowLift);

        quadBuffer.position(0);
        GLES30.glEnableVertexAttribArray(positionLocation);
        GLES30.glEnableVertexAttribArray(uvLocation);
        GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 16, quadBuffer);
        quadBuffer.position(2);
        GLES30.glVertexAttribPointer(uvLocation, 2, GLES30.GL_FLOAT, false, 16, quadBuffer);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(positionLocation);
        GLES30.glDisableVertexAttribArray(uvLocation);
    }

    private void upload(Frame frame) {
        if (frame.width != textureWidth || frame.height != textureHeight) {
            textureWidth = frame.width;
            textureHeight = frame.height;
            uploadBuffer = ByteBuffer.allocateDirect(frame.rgba.length).order(ByteOrder.nativeOrder());
        }
        uploadBuffer.clear();
        uploadBuffer.put(frame.rgba);
        uploadBuffer.position(0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        if (frame.width != textureWidth || frame.height != textureHeight) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                frame.width, frame.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, uploadBuffer);
        } else if (uploadBuffer.capacity() == frame.rgba.length) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                frame.width, frame.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, uploadBuffer);
        } else {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0,
                frame.width, frame.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, uploadBuffer);
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        int linked = GLES30.glCreateProgram();
        GLES30.glAttachShader(linked, vertex);
        GLES30.glAttachShader(linked, fragment);
        GLES30.glLinkProgram(linked);
        int[] status = new int[1];
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(linked);
            GLES30.glDeleteProgram(linked);
            throw new IllegalStateException("GT shader link failed: " + log);
        }
        GLES30.glDeleteShader(vertex);
        GLES30.glDeleteShader(fragment);
        return linked;
    }

    private int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            Log.e(TAG, log);
            throw new IllegalStateException("GT shader compilation failed: " + log);
        }
        return shader;
    }
}
