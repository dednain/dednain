package com.gtrealgraphics.engine;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** GPU-native capture and multi-pass post-processing. Frames stay on the GPU. */
public final class GpuPostProcessor implements GLSurfaceView.Renderer {
    private static final String TAG="GT-GPU";
    public interface SurfaceReadyListener { void onSurfaceReady(Surface surface); }
    public interface FrameAvailableListener { void onFrameAvailable(); }

    private static final String VERTEX=
        "#version 300 es\nlayout(location=0) in vec2 aPosition;\nlayout(location=1) in vec2 aTexCoord;\nout vec2 vUv;\nvoid main(){gl_Position=vec4(aPosition,0.0,1.0);vUv=aTexCoord;}\n";
    private static final String SCENE=
        "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float;\nin vec2 vUv;\nuniform samplerExternalOES uInput;\nuniform mat4 uTexMatrix;\nuniform vec2 uTexel;\nuniform float uExposure,uContrast,uSaturation,uShadowLift,uLocalContrast,uTemperature,uHdrLook;\nout vec4 fragColor;\n"+
        "vec3 aces(vec3 x){return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0.0,1.0);}\n"+
        "vec3 toLinear(vec3 c){return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));}\n"+
        "vec3 src(vec2 p){return toLinear(texture(uInput,(uTexMatrix*vec4(p,0.0,1.0)).xy).rgb);}\n"+
        "void main(){vec3 c=src(vUv);vec3 n=src(vUv+vec2(0.0,uTexel.y));vec3 s=src(vUv-vec2(0.0,uTexel.y));vec3 e=src(vUv+vec2(uTexel.x,0.0));vec3 w=src(vUv-vec2(uTexel.x,0.0));vec3 avg=(n+s+e+w)*0.25;float lum=dot(c,vec3(0.2126,0.7152,0.0722));float sh=1.0-smoothstep(0.02,0.38,lum);c+=sh*uShadowLift;c+=(c-avg)*uLocalContrast;c*=uExposure;c=(c-0.5)*uContrast+0.5;c.r*=1.0+uTemperature*0.10;c.b*=1.0-uTemperature*0.10;float gray=dot(c,vec3(0.2126,0.7152,0.0722));c=mix(vec3(gray),c,uSaturation);c=mix(c,aces(max(c,vec3(0.0))),uHdrLook);fragColor=vec4(clamp(c,0.0,1.0),1.0);}\n";
    private static final String BRIGHT=
        "#version 300 es\nprecision highp float;in vec2 vUv;out vec4 fragColor;uniform sampler2D uScene;uniform vec2 uTexel;uniform float uThreshold;\n"+
        "void main(){vec3 c=(texture(uScene,vUv+uTexel*vec2(-0.5,-0.5)).rgb+texture(uScene,vUv+uTexel*vec2(0.5,-0.5)).rgb+texture(uScene,vUv+uTexel*vec2(-0.5,0.5)).rgb+texture(uScene,vUv+uTexel*vec2(0.5,0.5)).rgb)*0.25;float l=max(max(c.r,c.g),c.b);float k=max(uThreshold*0.5,0.001);float soft=clamp((l-uThreshold+k)/(2.0*k),0.0,1.0);soft=soft*soft*k;float contribution=max(l-uThreshold,0.0)+soft;fragColor=vec4(c*(contribution/max(l,0.0001)),1.0);}\n";
    private static final String BLUR=
        "#version 300 es\nprecision highp float;in vec2 vUv;out vec4 fragColor;uniform sampler2D uTexture;uniform vec2 uDirection;\n"+
        "void main(){vec3 c=texture(uTexture,vUv).rgb*0.227027;c+=texture(uTexture,vUv+uDirection*1.384615).rgb*0.316216;c+=texture(uTexture,vUv-uDirection*1.384615).rgb*0.316216;c+=texture(uTexture,vUv+uDirection*3.230769).rgb*0.070270;c+=texture(uTexture,vUv-uDirection*3.230769).rgb*0.070270;fragColor=vec4(c,1.0);}\n";
    private static final String FINAL=
        "#version 300 es\nprecision highp float;in vec2 vUv;out vec4 fragColor;uniform sampler2D uScene,uBloom;uniform vec2 uTexel;uniform float uBloomStrength,uSharpness,uVignette;\n"+
        "vec3 toSrgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,vec3(0.0)),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));}\n"+
        "void main(){vec3 c=texture(uScene,vUv).rgb;vec3 n=texture(uScene,vUv+vec2(0.0,uTexel.y)).rgb;vec3 s=texture(uScene,vUv-vec2(0.0,uTexel.y)).rgb;vec3 e=texture(uScene,vUv+vec2(uTexel.x,0.0)).rgb;vec3 w=texture(uScene,vUv-vec2(uTexel.x,0.0)).rgb;c+=(c-(n+s+e+w)*0.25)*uSharpness;c+=texture(uBloom,vUv).rgb*uBloomStrength;float v=1.0-uVignette*smoothstep(0.25,0.78,distance(vUv,vec2(0.5)));c=clamp(c*v,0.0,1.0);fragColor=vec4(clamp(toSrgb(c),0.0,1.0),1.0);}\n";

    private static final float[] QUAD={-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0};
    public static final class Settings {
        public float saturation=1.12f,contrast=1.08f,exposure=1.03f,bloom=0.28f,sharpness=0.18f,hdrLook=0.32f,shadowLift=0.05f,localContrast=0.12f,temperature=0f,vignette=0.10f,bloomThreshold=0.72f,renderScale=1.0f;
        public static Settings defaults(){return new Settings();}
        public Settings copy(){Settings s=new Settings();s.saturation=saturation;s.contrast=contrast;s.exposure=exposure;s.bloom=bloom;s.sharpness=sharpness;s.hdrLook=hdrLook;s.shadowLift=shadowLift;s.localContrast=localContrast;s.temperature=temperature;s.vignette=vignette;s.bloomThreshold=bloomThreshold;s.renderScale=renderScale;return s;}
        public static Settings cinematic(){Settings s=new Settings();s.saturation=1.18f;s.contrast=1.12f;s.exposure=1.05f;s.bloom=0.38f;s.sharpness=0.12f;s.hdrLook=0.48f;s.shadowLift=0.045f;s.localContrast=0.16f;s.temperature=0.08f;s.vignette=0.16f;s.bloomThreshold=0.68f;s.renderScale=1.0f;return s;}
        public static Settings competitive(){Settings s=new Settings();s.saturation=1.10f;s.contrast=1.16f;s.exposure=1.06f;s.bloom=0.08f;s.sharpness=0.32f;s.hdrLook=0.12f;s.shadowLift=0.09f;s.localContrast=0.22f;s.vignette=0f;s.bloomThreshold=0.85f;return s;}
    }

    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final AtomicBoolean framePending=new AtomicBoolean(false);
    private volatile Settings settings=Settings.defaults();
    private volatile int requestedWidth=1080,requestedHeight=2400;
    private volatile boolean released;
    private Runnable renderRequest;
    private SurfaceReadyListener surfaceReadyListener;
    private FrameAvailableListener frameAvailableListener;
    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private int externalTexture;
    private final float[] textureMatrix=new float[16];
    private final FloatBuffer quadBuffer;
    private int sceneProgram,brightProgram,blurProgram,finalProgram;
    private int sceneTexture,sceneFbo,bloomTextureA,bloomFboA,bloomTextureB,bloomFboB;
    private int inputWidth,inputHeight,captureWidth,captureHeight,bloomWidth,bloomHeight,viewWidth,viewHeight;\n    private float targetScale=1.0f;
    private boolean targetsReady;

    public GpuPostProcessor(android.content.Context context){ByteBuffer b=ByteBuffer.allocateDirect(QUAD.length*4).order(ByteOrder.nativeOrder());quadBuffer=b.asFloatBuffer();quadBuffer.put(QUAD).position(0);}
    public void setRenderRequest(Runnable r){renderRequest=r;}
    public void setSurfaceReadyListener(SurfaceReadyListener l){surfaceReadyListener=l;}
    public void setFrameAvailableListener(FrameAvailableListener l){frameAvailableListener=l;}
    public void setSettings(Settings s){if(s!=null)settings=s.copy();}
    public void setCaptureSize(int w,int h){if(w<=0||h<=0)return;requestedWidth=w;requestedHeight=h;SurfaceTexture st=inputTexture;if(st!=null){try{st.setDefaultBufferSize(w,h);}catch(Exception ignored){}}Runnable r=renderRequest;if(r!=null)r.run();}
    public void releaseGlResources(){released=true;if(inputTexture!=null){try{inputTexture.setOnFrameAvailableListener(null);inputTexture.release();}catch(Exception ignored){}inputTexture=null;}if(inputSurface!=null){try{inputSurface.release();}catch(Exception ignored){}inputSurface=null;}deleteTexture(externalTexture);deleteTarget(sceneTexture,sceneFbo);deleteTarget(bloomTextureA,bloomFboA);deleteTarget(bloomTextureB,bloomFboB);if(sceneProgram!=0)GLES30.glDeleteProgram(sceneProgram);if(brightProgram!=0)GLES30.glDeleteProgram(brightProgram);if(blurProgram!=0)GLES30.glDeleteProgram(blurProgram);if(finalProgram!=0)GLES30.glDeleteProgram(finalProgram);sceneProgram=brightProgram=blurProgram=finalProgram=0;}
    private void deleteTexture(int id){if(id!=0){int[] a={id};GLES30.glDeleteTextures(1,a,0);}}
    private void deleteTarget(int t,int f){deleteTexture(t);if(f!=0){int[] a={f};GLES30.glDeleteFramebuffers(1,a,0);}}

    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        released=false;GLES30.glClearColor(0,0,0,1);GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        sceneProgram=program(VERTEX,SCENE);brightProgram=program(VERTEX,BRIGHT);blurProgram=program(VERTEX,BLUR);finalProgram=program(VERTEX,FINAL);
        int[] ids=new int[1];GLES30.glGenTextures(1,ids,0);externalTexture=ids[0];GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,externalTexture);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
        inputTexture=new SurfaceTexture(externalTexture);inputTexture.setDefaultBufferSize(requestedWidth,requestedHeight);
        inputTexture.setOnFrameAvailableListener(st->{framePending.set(true);FrameAvailableListener fl=frameAvailableListener;if(fl!=null)fl.onFrameAvailable();Runnable r=renderRequest;if(r!=null)r.run();},mainHandler);
        inputSurface=new Surface(inputTexture);targetsReady=false;ensureTargets(requestedWidth,requestedHeight);
        SurfaceReadyListener l=surfaceReadyListener;Surface s=inputSurface;if(l!=null)mainHandler.post(()->{if(!released&&surfaceReadyListener!=null)surfaceReadyListener.onSurfaceReady(s);});
    }
    @Override public void onSurfaceChanged(GL10 gl,int w,int h){viewWidth=w;viewHeight=h;GLES30.glViewport(0,0,w,h);ensureTargets(requestedWidth,requestedHeight);}
    private void ensureTargets(int w,int h){if(w<=0||h<=0)return;float scale=Math.max(0.5f,Math.min(1.0f,settings.renderScale));int rw=Math.max(1,Math.round(w*scale)),rh=Math.max(1,Math.round(h*scale));if(targetsReady&&w==captureWidth&&h==captureHeight&&Math.abs(scale-targetScale)<0.001f)return;deleteTarget(sceneTexture,sceneFbo);deleteTarget(bloomTextureA,bloomFboA);deleteTarget(bloomTextureB,bloomFboB);captureWidth=w;captureHeight=h;inputWidth=rw;inputHeight=rh;targetScale=scale;bloomWidth=Math.max(1,rw/4);bloomHeight=Math.max(1,rh/4);int[] t=new int[1],f=new int[1];GLES30.glGenTextures(1,t,0);sceneTexture=t[0];allocTexture(sceneTexture,w,h);GLES30.glGenFramebuffers(1,f,0);sceneFbo=f[0];attach(sceneFbo,sceneTexture);GLES30.glGenTextures(1,t,0);bloomTextureA=t[0];allocTexture(bloomTextureA,bloomWidth,bloomHeight);GLES30.glGenFramebuffers(1,f,0);bloomFboA=f[0];attach(bloomFboA,bloomTextureA);GLES30.glGenTextures(1,t,0);bloomTextureB=t[0];allocTexture(bloomTextureB,bloomWidth,bloomHeight);GLES30.glGenFramebuffers(1,f,0);bloomFboB=f[0];attach(bloomFboB,bloomTextureB);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);targetsReady=true;}
    private void allocTexture(int id,int w,int h){GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null);}
    private void attach(int f,int t){GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,f);GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,t,0);int status=GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);if(status!=GLES30.GL_FRAMEBUFFER_COMPLETE)Log.e(TAG,"FBO incomplete: "+Integer.toHexString(status));}

    @Override public void onDrawFrame(GL10 gl){
        if(released||inputTexture==null||sceneProgram==0)return;
        if(framePending.getAndSet(false)){try{inputTexture.updateTexImage();inputTexture.getTransformMatrix(textureMatrix);}catch(Exception e){Log.w(TAG,"updateTexImage",e);}}
        if(captureWidth!=requestedWidth||captureHeight!=requestedHeight||Math.abs(targetScale-settings.renderScale)>0.001f)ensureTargets(requestedWidth,requestedHeight);
        if(!targetsReady)return;Settings s=settings;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,sceneFbo);GLES30.glViewport(0,0,inputWidth,inputHeight);GLES30.glUseProgram(sceneProgram);bindExternal();
        GLES30.glUniformMatrix4fv(loc(sceneProgram,"uTexMatrix"),1,false,textureMatrix,0);u2(sceneProgram,"uTexel",1f/captureWidth,1f/captureHeight);u1(sceneProgram,"uExposure",s.exposure);u1(sceneProgram,"uContrast",s.contrast);u1(sceneProgram,"uSaturation",s.saturation);u1(sceneProgram,"uShadowLift",s.shadowLift);u1(sceneProgram,"uLocalContrast",s.localContrast);u1(sceneProgram,"uTemperature",s.temperature);u1(sceneProgram,"uHdrLook",s.hdrLook);draw();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,bloomFboA);GLES30.glViewport(0,0,bloomWidth,bloomHeight);GLES30.glUseProgram(brightProgram);bind2D(0,sceneTexture,brightProgram,"uScene");u2(brightProgram,"uTexel",1f/inputWidth,1f/inputHeight);u1(brightProgram,"uThreshold",s.bloomThreshold);draw();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,bloomFboB);GLES30.glUseProgram(blurProgram);bind2D(0,bloomTextureA,blurProgram,"uTexture");u2(blurProgram,"uDirection",1f/bloomWidth,0);draw();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,bloomFboA);GLES30.glUseProgram(blurProgram);bind2D(0,bloomTextureB,blurProgram,"uTexture");u2(blurProgram,"uDirection",0,1f/bloomHeight);draw();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,viewWidth,viewHeight);GLES30.glUseProgram(finalProgram);bind2D(0,sceneTexture,finalProgram,"uScene");bind2D(1,bloomTextureA,finalProgram,"uBloom");u2(finalProgram,"uTexel",1f/inputWidth,1f/inputHeight);u1(finalProgram,"uBloomStrength",s.bloom);u1(finalProgram,"uSharpness",s.sharpness);u1(finalProgram,"uVignette",s.vignette);draw();
    }
    private void bindExternal(){GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,externalTexture);GLES30.glUniform1i(loc(sceneProgram,"uInput"),0);}
    private void bind2D(int unit,int texture,int p,String sampler){GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);GLES30.glUniform1i(loc(p,sampler),unit);}
    private int loc(int p,String n){return GLES30.glGetUniformLocation(p,n);}
    private void u1(int p,String n,float v){GLES30.glUniform1f(loc(p,n),v);}
    private void u2(int p,String n,float x,float y){GLES30.glUniform2f(loc(p,n),x,y);}
    private void draw(){quadBuffer.position(0);GLES30.glEnableVertexAttribArray(0);GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,16,quadBuffer);quadBuffer.position(2);GLES30.glVertexAttribPointer(1,2,GLES30.GL_FLOAT,false,16,quadBuffer);GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4);GLES30.glDisableVertexAttribArray(0);GLES30.glDisableVertexAttribArray(1);}
    private int program(String v,String f){int vs=compile(GLES30.GL_VERTEX_SHADER,v),fs=compile(GLES30.GL_FRAGMENT_SHADER,f);int p=GLES30.glCreateProgram();GLES30.glAttachShader(p,vs);GLES30.glAttachShader(p,fs);GLES30.glLinkProgram(p);int[] ok=new int[1];GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);GLES30.glDeleteShader(vs);GLES30.glDeleteShader(fs);if(ok[0]==0){String log=GLES30.glGetProgramInfoLog(p);GLES30.glDeleteProgram(p);throw new IllegalStateException("Shader link: "+log);}return p;}
    private int compile(int type,String src){int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,src);GLES30.glCompileShader(s);int[] ok=new int[1];GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){String log=GLES30.glGetShaderInfoLog(s);GLES30.glDeleteShader(s);throw new IllegalStateException("Shader compile: "+log);}return s;}
}
