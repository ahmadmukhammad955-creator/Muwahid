package com.muwahid.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** In-memory crop. The same source rectangle drives the preview and JPEG export. */
final class ProfileCropView extends View {
    private final Bitmap source;
    private final boolean cover;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector detector;
    private float zoom=1f,cx=.5f,cy=.5f,lastX,lastY;
    private final RectF viewport=new RectF();
    ProfileCropView(Context context,Bitmap source,boolean cover){
        super(context);this.source=source;this.cover=cover;
        setContentDescription("Обрезка фотографии: переместите изображение и измените масштаб двумя пальцами");
        detector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){setZoom(zoom*d.getScaleFactor());return true;}
        });
    }
    void setZoom(float value){zoom=Math.max(1f,Math.min(5f,value));invalidate();}
    void moveBy(float x,float y){cx=Math.max(0,Math.min(1,cx+x));cy=Math.max(0,Math.min(1,cy+y));invalidate();}
    void reset(){zoom=1;cx=.5f;cy=.5f;invalidate();}
    RectF crop(){return ProfilePhotoCodec.cropRect(source.getWidth(),source.getHeight(),cover,zoom,cx,cy);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(0xFF09111B);
        float ratio=cover?1440f/670f:1f;
        float w=Math.min(getWidth(),getHeight()*ratio),h=w/ratio;
        viewport.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
        Matrix matrix=new Matrix();matrix.setRectToRect(crop(),viewport,Matrix.ScaleToFit.FILL);
        canvas.save();canvas.clipRect(viewport);canvas.drawBitmap(source,matrix,paint);canvas.restore();
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setColor(Color.WHITE);
        if(cover)canvas.drawRect(viewport,paint);else canvas.drawOval(viewport,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event){
        if(!isEnabled())return false;
        detector.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=event.getX();lastY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
            if(!detector.isInProgress()&&event.getPointerCount()==1&&viewport.width()>0){
                RectF r=crop();
                // Clamp the previous centre before moving to avoid a dead zone at the edges.
                cx=r.centerX()/source.getWidth();cy=r.centerY()/source.getHeight();
                moveBy(-(event.getX()-lastX)*r.width()/viewport.width()/source.getWidth(),
                       -(event.getY()-lastY)*r.height()/viewport.height()/source.getHeight());
            }
            lastX=event.getX();lastY=event.getY();return true;
        }
        if(event.getActionMasked()==MotionEvent.ACTION_POINTER_UP){int next=event.getActionIndex()==0?1:0;lastX=event.getX(next);lastY=event.getY(next);return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_UP){getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
