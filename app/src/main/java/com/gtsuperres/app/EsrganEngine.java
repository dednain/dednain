package com.gtsuperres.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Color;
import org.tensorflow.lite.Interpreter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Closeable;

/** Actual compressed, trained 4x ESRGAN inference. Not HDR10 or frame generation. */
public final class EsrganEngine implements Closeable {
  private static final int IN_W=320, IN_H=180, OUT_W=1280, OUT_H=720;
  private final Interpreter interpreter;
  public EsrganEngine(Context ctx) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream is=ctx.getAssets().open("esrgan.tflite")) {
      byte[] b=new byte[8192];int n;
      while((n=is.read(b))!=-1)out.write(b,0,n);
    }
    byte[] bytes=out.toByteArray();
    if(bytes.length<10000)throw new IllegalStateException("The trained model is missing");
    Interpreter.Options opts=new Interpreter.Options().setNumThreads(4);
    ByteBuffer model=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
    model.put(bytes);
    model.rewind();
    interpreter=new Interpreter(model,opts);
    int[] input=interpreter.getInputTensor(0).shape();
    int[] output=interpreter.getOutputTensor(0).shape();
    if(input.length!=4||input[1]!=IN_H||input[2]!=IN_W||input[3]!=3 ||
      output.length!=4||output[1]!=OUT_H||output[2]!=OUT_W||output[3]!=3)
      throw new IllegalStateException("Unsupported ESRGAN shapes: "+java.util.Arrays.toString(input)+" / "+java.util.Arrays.toString(output));
  }
  public synchronized Bitmap enhance(Bitmap source) {
    if(source==null)throw new IllegalArgumentException("Empty image");
    boolean portrait=source.getHeight()>source.getWidth();
    Bitmap rotated=source;
    if(portrait){
      Matrix r=new Matrix();r.postRotate(90);
      rotated=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),r,true);
    }
    Bitmap small=Bitmap.createScaledBitmap(rotated,IN_W,IN_H,true);
    int[] pixels=new int[IN_W*IN_H];small.getPixels(pixels,0,IN_W,0,0,IN_W,IN_H);
    ByteBuffer input=ByteBuffer.allocateDirect(IN_W*IN_H*3*4).order(ByteOrder.nativeOrder());
    for(int pixel:pixels){input.putFloat((pixel>>16)&255);input.putFloat((pixel>>8)&255);input.putFloat(pixel&255);}
    input.rewind();
    ByteBuffer output=ByteBuffer.allocateDirect(OUT_W*OUT_H*3*4).order(ByteOrder.nativeOrder());
    interpreter.run(input,output);
    output.rewind();
    int[] dst=new int[OUT_W*OUT_H];
    for(int i=0;i<dst.length;i++) {
      int r=clip(output.getFloat()),g=clip(output.getFloat()),b=clip(output.getFloat());
      dst[i]=Color.rgb(r,g,b);
    }
    Bitmap result=Bitmap.createBitmap(OUT_W,OUT_H,Bitmap.Config.ARGB_8888);
    result.setPixels(dst,0,OUT_W,0,0,OUT_W,OUT_H);
    if(portrait){Matrix r=new Matrix();r.postRotate(-90);Bitmap corrected=Bitmap.createBitmap(result,0,0,OUT_W,OUT_H,r,true);result.recycle();result=corrected;}
    if(rotated!=source)rotated.recycle();
    small.recycle();
    return result;
  }
  /**
   * Process image in overlapping-scene tiles rather than shrinking
   * an entire 1080p screenshot into a 320x180 input. Tiled 4x AI
   * maintains substantially more scene detail on high-res photos.
   */
  public synchronized Bitmap enhancePhoto(Bitmap source){
    if(source==null)throw new IllegalArgumentException("Missing photo");
    boolean portrait=source.getHeight()>source.getWidth();
    Bitmap rotated=source;
    if(portrait){
      Matrix r=new Matrix();r.postRotate(90);
      rotated=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),r,true);
    }
    int w=rotated.getWidth(),h=rotated.getHeight();
    int columns=Math.max(1,Math.min(4,(w+599)/600));
    int rows=Math.max(1,Math.min(3,(h+359)/360));
    Bitmap combined=Bitmap.createBitmap(columns*OUT_W,rows*OUT_H,Bitmap.Config.ARGB_8888);
    android.graphics.Canvas canvas=new android.graphics.Canvas(combined);
    android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    for(int row=0;row<rows;row++){
      for(int col=0;col<columns;col++){
        int x0=w*col/columns,x1=w*(col+1)/columns;
        int y0=h*row/rows,y1=h*(row+1)/rows;
        Bitmap tile=Bitmap.createBitmap(rotated,x0,y0,x1-x0,y1-y0);
        Bitmap enhancedTile=enhance(tile);
        canvas.drawBitmap(enhancedTile,col*OUT_W,row*OUT_H,paint);
        enhancedTile.recycle();tile.recycle();
      }
    }
    if(rotated!=source)rotated.recycle();
    if(portrait){
      Matrix r=new Matrix();r.postRotate(-90);
      Bitmap upright=Bitmap.createBitmap(combined,0,0,combined.getWidth(),combined.getHeight(),r,true);
      combined.recycle();
      return upright;
    }
    return combined;
  }
  private static int clip(float v){return Math.max(0,Math.min(255,Math.round(v)));}
  @Override public void close(){interpreter.close();}
}
