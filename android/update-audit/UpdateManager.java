package com.muwahid.app;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateManager {
    private static final String CHANNEL_ID="muwahid_updates";
    private static final int NOTIFICATION_ID=410001;
    private static final String PREFS="muwahid_update";
    private static final String MANIFEST_URL="https://raw.githubusercontent.com/ahmadmukhammad955-creator/Muwahid/main/updates/latest.json";
    private static final long CHECK_INTERVAL_MS=10L*60L*1000L;
    private static final long FOREGROUND_INTERVAL_MS=60L*1000L;
    private static final long REPEAT_NOTICE_INTERVAL_MS=24L*60L*60L*1000L;
    private static final java.util.concurrent.atomic.AtomicInteger promptedVersion=new java.util.concurrent.atomic.AtomicInteger(-1);
    private static final AtomicBoolean checking=new AtomicBoolean(false);

    private UpdateManager(){}

    /** Check on every fresh launch, even before sign-in; refresh the last seen version. */
    public static void checkOnLaunch(Activity activity){
        if(activity==null||activity.isFinishing())return;
        Context app=activity.getApplicationContext();
        if(!app.getSharedPreferences("muwahid",Context.MODE_PRIVATE).getBoolean("notify_updates",true))return;
        android.content.SharedPreferences p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(!checking.compareAndSet(false,true))return;
        p.edit().putLong("last_foreground_attempt",System.currentTimeMillis()).apply();
        new Thread(()->{
            try{
                Release release=fetchRelease();
                p.edit().putLong("last_check",System.currentTimeMillis()).apply();
                if(release.versionCode>BuildConfig.VERSION_CODE){
                    rememberRelease(p,release);
                    notifyIfNeeded(app,p,release);
                    activity.runOnUiThread(()->{
                        if(promptedVersion.get()!=release.versionCode && !activity.isFinishing()
                            && (Build.VERSION.SDK_INT<17||!activity.isDestroyed())){
                            promptedVersion.set(release.versionCode);
                            showUpdateDialog(activity,release);
                        }
                    });
                } else { clearOldNotification(app,p); }
            }catch(Exception ignored){
                // Internet may be offline. Foreground check will retry after a minute.
            }finally{checking.set(false);}
        },"muwahid-update-launch-check").start();
    }

    /** Called while the messenger is visible: discover a new release without reopening the app. */
    public static void checkInForeground(Activity activity){
        if(activity==null||activity.isFinishing())return;
        android.content.SharedPreferences p=activity.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(System.currentTimeMillis()-p.getLong("last_foreground_attempt",0)<FOREGROUND_INTERVAL_MS)return;
        checkOnLaunch(activity);
    }

    public static void checkAndNotify(Context context,boolean force){
        checkAndNotify(context,force,null);
    }

    /** A completion callback keeps Android JobScheduler informed when network I/O finishes. */
    static void checkAndNotify(Context context,boolean force,Runnable completion){
        Context app=context.getApplicationContext();
        android.content.SharedPreferences p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(!force&&!app.getSharedPreferences("muwahid",Context.MODE_PRIVATE).getBoolean("notify_updates",true)){
            finish(completion);return;
        }
        long now=System.currentTimeMillis();
        if(!force&&now-p.getLong("last_attempt",0)<CHECK_INTERVAL_MS){finish(completion);return;}
        if(!checking.compareAndSet(false,true)){finish(completion);return;}
        p.edit().putLong("last_attempt",now).apply();
        new Thread(()->{
            try{
                Release release=fetchRelease();
                p.edit().putLong("last_check",System.currentTimeMillis()).apply();
                if(release.versionCode>BuildConfig.VERSION_CODE){
                    rememberRelease(p,release);
                    if(app.getSharedPreferences("muwahid",Context.MODE_PRIVATE).getBoolean("notify_updates",true))
                        notifyIfNeeded(app,p,release);
                    if(force)showToast(app,"Доступна новая версия Muwahid "+release.versionName);
                } else {
                    clearOldNotification(app,p);
                    if(force)showToast(app,"У вас уже последняя версия Muwahid");
                }
            }catch(Exception e){
                if(force)showToast(app,"Не удалось проверить обновления. Проверьте интернет.");
            }finally{checking.set(false);finish(completion);}
        },"muwahid-update-check").start();
    }

    private static void finish(Runnable completion){
        if(completion!=null)new Handler(Looper.getMainLooper()).post(completion);
    }

    private static void rememberRelease(android.content.SharedPreferences p,Release release){
        p.edit().putInt("available_code",release.versionCode)
                .putString("available_name",release.versionName).apply();
    }

    private static void notifyIfNeeded(Context app,android.content.SharedPreferences p,Release release){
        long now=System.currentTimeMillis();
        if(p.getInt("notified_code",0)==release.versionCode
                && now-p.getLong("notified_at",0)<REPEAT_NOTICE_INTERVAL_MS)return;
        if(notifyUpdate(app,release))p.edit().putInt("notified_code",release.versionCode)
                .putLong("notified_at",now).apply();
    }

    private static void clearOldNotification(Context app,android.content.SharedPreferences p){
        if(p.getInt("notified_code",0)==0)return;
        try{((NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);}
        catch(Exception ignored){}
        p.edit().remove("notified_code").remove("notified_at")
                .remove("available_code").remove("available_name").apply();
    }

    private static void showUpdateDialog(Activity activity,Release r){
        if(activity==null||activity.isFinishing()||(Build.VERSION.SDK_INT>=17&&activity.isDestroyed()))return;
        try{
            new AlertDialog.Builder(activity)
                    .setTitle("Доступно обновление Muwahid "+r.versionName)
                    .setMessage(r.notes+"\n\nОбновление установится поверх текущей версии. Чаты и настройки сохранятся.")
                    .setNegativeButton("Позже",null)
                    .setPositiveButton("Обновить",(d,w)->activity.startActivity(updateIntent(activity,r)))
                    .show();
        }catch(Exception ignored){}
    }

    private static Intent updateIntent(Context context,Release r){
        return new Intent(context,UpdateActivity.class)
                .putExtra("version_name",r.versionName)
                .putExtra("apk_url",r.apkUrl)
                .putExtra("sha256",r.sha256)
                .putExtra("notes",r.notes)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private static Release fetchRelease() throws Exception{
        HttpsURLConnection c=(HttpsURLConnection)new URL(MANIFEST_URL+"?t="+System.currentTimeMillis()).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestMethod("GET");c.setUseCaches(false);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("Cache-Control","no-cache");
        c.setRequestProperty("User-Agent","Muwahid-Android/"+BuildConfig.VERSION_NAME);
        try{
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] buf=new byte[8192];int n,total=0;
                while((n=in.read(buf))!=-1){total+=n;if(total>65536)throw new IOException("Update manifest too large");out.write(buf,0,n);}
            }
            JSONObject j=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            Release r=new Release();
            r.versionCode=j.getInt("versionCode");
            r.versionName=j.optString("versionName",String.valueOf(r.versionCode));
            r.apkUrl=j.getString("apkUrl");
            r.sha256=j.optString("sha256","").toLowerCase(java.util.Locale.ROOT);
            r.notes=j.optString("notes","Исправления и улучшения Muwahid.");
            if(!r.apkUrl.startsWith("https://github.com/ahmadmukhammad955-creator/Muwahid/releases/download/"))throw new SecurityException("Bad update URL");
            if(!r.sha256.matches("[0-9a-f]{64}"))throw new SecurityException("Bad SHA-256");
            return r;
        }finally{c.disconnect();}
    }

    private static boolean notifyUpdate(Context context,Release r){
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        try{
            NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if(manager==null)return false;
            if(Build.VERSION.SDK_INT>=24&&!manager.areNotificationsEnabled())return false;
            if(Build.VERSION.SDK_INT>=26){
                NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"Обновления Muwahid",NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Новые версии Muwahid");
                manager.createNotificationChannel(ch);
                NotificationChannel currentChannel=manager.getNotificationChannel(CHANNEL_ID);
                if(currentChannel!=null&&currentChannel.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
            }
            Intent open=updateIntent(context,r);
            PendingIntent pi=PendingIntent.getActivity(context,NOTIFICATION_ID,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(context,CHANNEL_ID):new Notification.Builder(context);
            b.setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Доступно обновление Muwahid "+r.versionName)
                    .setContentText("Нажмите, чтобы обновить приложение без удаления")
                    .setStyle(new Notification.BigTextStyle().bigText(r.notes))
                    .setAutoCancel(true).setContentIntent(pi).setCategory(Notification.CATEGORY_STATUS);
            if(Build.VERSION.SDK_INT<26)b.setPriority(Notification.PRIORITY_DEFAULT);
            manager.notify(NOTIFICATION_ID,b.build());
            return true;
        }catch(RuntimeException ignored){return false;}
    }

    private static void showToast(Context context,String text){
        new Handler(Looper.getMainLooper()).post(()->Toast.makeText(context,text,Toast.LENGTH_LONG).show());
    }

    private static final class Release{
        int versionCode;String versionName,apkUrl,sha256,notes;
    }
}
