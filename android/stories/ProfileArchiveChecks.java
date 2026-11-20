package com.muwahid.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the production layouts, clicks and crypto with an isolated test transport. */
public final class ProfileArchiveChecks {
    private static final String USER="12345678-1234-4321-8123-123456789abc";
    static StoriesActivity fixture() {
        return new StoriesActivity() {
            @Override Api profileApi() {
                return new Api(new SecureStore(this)) {
                    JSONObject item;
                    byte[] encrypted;
                    @Override boolean signedIn(){return true;}
                    @Override String uid(){return USER;}
                    @Override synchronized Object rpc(String name,JSONObject args) throws Exception {
                        if(name.equals("mw_self_profile"))return new JSONObject().put("id",USER)
                            .put("display_name","Проверка профиля").put("username","archive_test");
                        if(name.equals("mw_story_feed"))return new JSONArray();
                        if(!name.equals("mw_story_archive"))throw new AssertionError(name);
                        if(item==null){
                            Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
                            bitmap.eraseColor(0xff1588aa);
                            ByteArrayOutputStream stream=new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);
                            bitmap.recycle();
                            byte[] key=StoryMediaCodec.newKey();
                            encrypted=StoryMediaCodec.encrypt(stream.toByteArray(),key);
                            CryptoBox box=new CryptoBox(getApplicationContext());
                            String envelope=box.encryptOwnStoryKey(USER,
                                android.util.Base64.encodeToString(key,android.util.Base64.NO_WRAP));
                            item=new JSONObject().put("id","expired-story").put("owner_id",USER)
                                .put("display_name","Проверка профиля").put("media_kind","image")
                                .put("media_path","test.bin").put("view_count",7).put("key_envelope",envelope);
                        }
                        return new JSONArray().put(item);
                    }
                    @Override synchronized byte[] downloadStory(String path){
                        if(!"test.bin".equals(path))throw new AssertionError(path);
                        return encrypted.clone();
                    }
                };
            }
        };
    }

    private static View match(View root,String text){
        if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof TextView && text.contentEquals(((TextView)root).getText()))return root;
        if(root instanceof ViewGroup){
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++){
                View found=match(group.getChildAt(i),text);
                if(found!=null)return found;
            }
        }
        return null;
    }

    private static void find(Instrumentation runner,Activity screen,String text,boolean click){
        long deadline=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<deadline){
            boolean[] found={false};
            runner.runOnMainSync(()->{
                View target=match(screen.getWindow().getDecorView(),text);
                if(target!=null)found[0]=click?target.performClick():true;
            });
            if(found[0])return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Profile/archive view missing: "+text);
    }

    private static void assertSelected(Instrumentation runner,Activity screen,String tab){
        runner.runOnMainSync(()->{
            View view=match(screen.getWindow().getDecorView(),tab);
            if(view==null||!view.isSelected())
                throw new AssertionError("Archive tab not selected: "+tab);
        });
    }

    private static boolean hasThreeColumnGrid(View root){
        if(root instanceof android.widget.GridLayout
            && ((android.widget.GridLayout)root).getColumnCount()==3)return true;
        if(root instanceof ViewGroup){
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++)
                if(hasThreeColumnGrid(group.getChildAt(i)))return true;
        }
        return false;
    }
    private static void assertThreeColumnGrid(Instrumentation runner,Activity screen){
        runner.runOnMainSync(()->{
            if(!hasThreeColumnGrid(screen.getWindow().getDecorView()))
                throw new AssertionError("Archive is not rendered in three columns");
        });
    }

    /** Saves fixture-only UI screenshots to app-specific storage for CI artifacts. */
    private static void capture(Instrumentation runner,Activity screen,String filename){
        runner.runOnMainSync(()->{
            View root=screen.getWindow().getDecorView();
            if(root.getWidth()<1||root.getHeight()<1)
                throw new AssertionError("Screenshot has an empty viewport");
            Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            try{
                root.draw(new android.graphics.Canvas(bitmap));
                java.io.File folder=screen.getExternalFilesDir(null);
                if(folder==null)throw new AssertionError("No app-specific screenshot directory");
                try(java.io.FileOutputStream stream=new java.io.FileOutputStream(
                        new java.io.File(folder,filename))){
                    if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))
                        throw new AssertionError("Could not encode "+filename);
                }
            }catch(java.io.IOException error){
                throw new AssertionError("Could not save "+filename,error);
            }finally{bitmap.recycle();}
        });
    }

    static void run(Instrumentation runner){
        Activity profile=runner.startActivitySync(new Intent(runner.getTargetContext(),StoriesActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            find(runner,profile,"Проверка профиля",false);
            for(String action:new String[]{"Чат","Звонок","Видео","Поделиться","QR-код профиля"})
                find(runner,profile,action,false);
            capture(runner,profile,"profile_screen.png");
            find(runner,profile,"Открыть архив историй",true);
            find(runner,profile,"Мои истории",false);
            assertSelected(runner,profile,"Мои истории");
            find(runner,profile,"Историй пока нет",false);
            capture(runner,profile,"stories_my_stories.png");
            find(runner,profile,"Архив",true);
            find(runner,profile,"◉ 7",false);
            assertSelected(runner,profile,"Архив");
            assertThreeColumnGrid(runner,profile);
            capture(runner,profile,"stories_archive_screen.png");
            find(runner,profile,"Открыть историю 1",true);
            find(runner,profile,"Фотография истории",false);
            runner.runOnMainSync(profile::onBackPressed);
            find(runner,profile,"Мои истории",true);
            assertSelected(runner,profile,"Мои истории");
            find(runner,profile,"Историй пока нет",false);
            find(runner,profile,"Архив",true);
            find(runner,profile,"◉ 7",false);
            assertThreeColumnGrid(runner,profile);
        }finally{runner.runOnMainSync(profile::finish);runner.waitForIdleSync();}
    }
}
