package com.muwahid.app;

import android.graphics.*;

/** Run on Android 10 and 15; validates the actual codec, not a duplicate formula. */
final class ProfileCropChecks {
    static void run() throws Exception {
        for(boolean cover:new boolean[]{false,true}){
            for(int[] size:new int[][]{{40,400},{400,40},{512,512},{2048,1365}}){
                for(float zoom:new float[]{1,2,5}){
                    for(float x:new float[]{-1,0,.5f,1,2})for(float y:new float[]{-1,0,.5f,1,2}){
                        RectF crop=ProfilePhotoCodec.cropRect(size[0],size[1],cover,zoom,x,y);
                        if(crop.left<-.001||crop.top<-.001||crop.right>size[0]+.001||crop.bottom>size[1]+.001)
                            throw new AssertionError("Crop exceeds source bounds");
                        float ratio=cover?1440f/670f:1f;
                        if(Math.abs(crop.width()/crop.height()-ratio)>.001)
                            throw new AssertionError("Crop aspect ratio changed");
                    }
                }
            }
        }
        Bitmap source=Bitmap.createBitmap(1000,500,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(source);c.drawColor(Color.RED);Paint p=new Paint();p.setColor(Color.BLUE);
        c.drawRect(500,0,1000,500,p);
        try{
            for(float x:new float[]{0,1}){
                Bitmap result=ProfilePhotoCodec.decode(ProfilePhotoCodec.encode(source,false,
                    ProfilePhotoCodec.cropRect(1000,500,false,1,x,.5f)));
                try{
                    if(result.getWidth()!=512||result.getHeight()!=512)throw new AssertionError("Avatar dimensions");
                    int pixel=result.getPixel(256,256);
                    if(x==0&&Color.red(pixel)<220)throw new AssertionError("Left crop not exported");
                    if(x==1&&Color.blue(pixel)<220)throw new AssertionError("Right crop not exported");
                }finally{result.recycle();}
            }
        }finally{source.recycle();}
        System.out.println("MW_PROFILE_CROP_CHECKS_OK");
    }
}
