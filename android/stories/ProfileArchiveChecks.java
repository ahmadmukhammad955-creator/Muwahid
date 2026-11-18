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
                            String pub=box.publicKey(USER);
                            box.verifyPeer(USER,"story:self",pub);
                            String envelope=box.encryptText(USER,"story:self",pub,
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

    static void run(Instrumentation runner){
        Activity profile=runner.startActivitySync(new Intent(runner.getTargetContext(),StoriesActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            find(runner,profile,"Проверка профиля",false);
            for(String action:new String[]{"Чат","Звонок","Видео","Поделиться","QR-код профиля"})
                find(runner,profile,action,false);
            find(runner,profile,"Открыть архив историй",true);
            find(runner,profile,"◉ 7",false);
            find(runner,profile,"Открыть историю 1",true);
            find(runner,profile,"Фотография истории",false);
            runner.runOnMainSync(profile::onBackPressed);
            find(runner,profile,"▣  Мои истории",true);
            find(runner,profile,"Историй пока нет",false);
            find(runner,profile,"▢  Архив",true);
            find(runner,profile,"◉ 7",false);
        }finally{runner.runOnMainSync(profile::finish);runner.waitForIdleSync();}
    }
}
