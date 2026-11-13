package com.muwahid.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Bounded, centrally cropped JPEG profile images; used off the UI thread. */
final class ProfilePhotoCodec {
    private static final int MAX_SOURCE_BYTES=12*1024*1024;
    private static final int MAX_RESULT_BYTES=6*1024*1024;
    private ProfilePhotoCodec(){}

    static Bitmap readSource(android.content.Context context, Uri uri) throws IOException {
        byte[] source;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("Не удалось открыть фотографию");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[16384];int n;
            while((n=in.read(chunk))!=-1){
                if(bytes.size()+n>MAX_SOURCE_BYTES)throw new IOException("Фотография больше 12 МБ");
                bytes.write(chunk,0,n);
            }
            source=bytes.toByteArray();
        }
        java.io.File temp=null;
        Bitmap original=null;
        try{
            BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(source,0,source.length,size);
            if(size.outWidth<1||size.outHeight<1||size.outWidth>12000||size.outHeight>12000)
                throw new IOException("Не поддерживается размер изображения");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
            while(Math.max(size.outWidth,size.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
            original=BitmapFactory.decodeByteArray(source,0,source.length,options);
            if(original==null)throw new IOException("Формат фотографии не поддерживается");
            // File-based ExifInterface also works on API 23. The private temporary file
            // is deleted immediately; the exported JPEG contains no source EXIF/GPS.
            temp=java.io.File.createTempFile("profile_orientation_",".image",context.getCacheDir());
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){out.write(source);}
            int orientation=1;
            try{orientation=new android.media.ExifInterface(temp.getAbsolutePath())
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
            android.graphics.Matrix m=new android.graphics.Matrix();
            switch(orientation){
                case 2:m.setScale(-1,1);break;
                case 3:m.setRotate(180);break;
                case 4:m.setScale(1,-1);break;
                case 5:m.setRotate(90);m.postScale(-1,1);break;
                case 6:m.setRotate(90);break;
                case 7:m.setRotate(-90);m.postScale(-1,1);break;
                case 8:m.setRotate(-90);break;
            }
            Bitmap result=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),m,true);
            if(result!=original)original.recycle();original=null;return result;
        }finally{
            if(original!=null)original.recycle();
            if(temp!=null&&!temp.delete())temp.deleteOnExit();
            Arrays.fill(source,(byte)0);
        }
    }

    static RectF cropRect(int w,int h,boolean cover,float zoom,float cx,float cy){
        float aspect=cover?1440f/670f:1f;
        zoom=Math.max(1,Math.min(5,zoom));
        float cw=Math.min(w,h*aspect)/zoom,ch=cw/aspect;
        float left=Math.max(0,Math.min(w-cw,cx*w-cw/2));
        float top=Math.max(0,Math.min(h-ch,cy*h-ch/2));
        return new RectF(left,top,left+cw,top+ch);
    }
    static byte[] encode(Bitmap original,boolean cover,RectF crop) throws IOException {
        int w=cover?1440:512,h=cover?670:512;
        Bitmap output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(output);canvas.drawColor(Color.rgb(20,28,38));
            android.graphics.Matrix m=new android.graphics.Matrix();
            m.setRectToRect(crop,new RectF(0,0,w,h),android.graphics.Matrix.ScaleToFit.FILL);
            canvas.drawBitmap(original,m,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!output.compress(Bitmap.CompressFormat.JPEG,86,bytes))throw new IOException("Не удалось подготовить фотографию");
            byte[] jpeg=bytes.toByteArray();
            if(jpeg.length>MAX_RESULT_BYTES)throw new IOException("Фотография слишком большая");
            return jpeg;
        }finally{output.recycle();}
    }

    static Bitmap decode(byte[] data) throws IOException {
        if(data==null||data.length<30||data.length>MAX_RESULT_BYTES)
            throw new IOException("Повреждённые данные фотографии");
        BitmapFactory.Options check=new BitmapFactory.Options();
        check.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,check);
        if(check.outWidth<1||check.outHeight<1||check.outWidth>2048||check.outHeight>2048)
            throw new IOException("Недопустимое изображение профиля");
        Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
        if(bitmap==null)throw new IOException("Не удалось загрузить фотографию");
        return bitmap;
    }
}

