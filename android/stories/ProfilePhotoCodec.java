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

    static byte[] prepare(ContentResolver resolver, Uri uri, boolean cover) throws IOException {
        byte[] source;
        try(InputStream in=resolver.openInputStream(uri)){
            if(in==null)throw new IOException("Не удалось открыть фотографию");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] chunk=new byte[16384];
            int n;
            while((n=in.read(chunk))>=0){
                if(n==0)continue;
                if(bytes.size()+n>MAX_SOURCE_BYTES)throw new IOException("Фотография больше 12 МБ");
                bytes.write(chunk,0,n);
            }
            source=bytes.toByteArray();
        }
        try{
            BitmapFactory.Options size=new BitmapFactory.Options();
            size.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(source,0,source.length,size);
            if(size.outWidth<1||size.outHeight<1||size.outWidth>12000||size.outHeight>12000)
                throw new IOException("Не поддерживается размер изображения");
            final int outW=cover?1440:512, outH=cover?670:512;
            BitmapFactory.Options options=new BitmapFactory.Options();
            int maxSide=cover?2048:1400;
            options.inSampleSize=1;
            while(Math.max(size.outWidth,size.outHeight)/options.inSampleSize>maxSide*2)
                options.inSampleSize*=2;
            Bitmap original=BitmapFactory.decodeByteArray(source,0,source.length,options);
            if(original==null)throw new IOException("Формат фотографии не поддерживается");
            Bitmap cropped=null;
            try{
                cropped=Bitmap.createBitmap(outW,outH,Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(cropped);
                canvas.drawColor(Color.rgb(20,28,38));
                float aspect=(float)outW/outH;
                int w=original.getWidth(),h=original.getHeight();
                Rect src;
                if((float)w/h>aspect){
                    int cw=Math.max(1,Math.round(h*aspect));
                    src=new Rect((w-cw)/2,0,(w+cw)/2,h);
                }else{
                    int ch=Math.max(1,Math.round(w/aspect));
                    src=new Rect(0,(h-ch)/2,w,(h+ch)/2);
                }
                Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
                canvas.drawBitmap(original,src,new RectF(0,0,outW,outH),paint);
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                if(!cropped.compress(Bitmap.CompressFormat.JPEG,86,out))
                    throw new IOException("Не удалось подготовить фотографию");
                byte[] jpeg=out.toByteArray();
                if(jpeg.length>MAX_RESULT_BYTES)throw new IOException("Подготовленная фотография слишком большая");
                return jpeg;
            }finally{
                if(cropped!=null)cropped.recycle();
                original.recycle();
            }
        }finally{Arrays.fill(source,(byte)0);}
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
