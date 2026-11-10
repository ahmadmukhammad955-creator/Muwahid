package com.muwahid.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

public final class UpdateActivity extends Activity {
    private Button update;
    private TextView status;
    private ProgressBar progress;
    private String url, versionName, expectedSha256;
    private volatile boolean busy;
    private File pendingApk;
    private static final int REQ_UNKNOWN_SOURCES=4107;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        versionName=getIntent().getStringExtra("version_name");
        url=getIntent().getStringExtra("apk_url");
        expectedSha256=getIntent().getStringExtra("sha256");
        String notes=getIntent().getStringExtra("notes");
        if(versionName==null)versionName="новая версия";
        if(notes==null||notes.trim().isEmpty())notes="Исправления и улучшения Muwahid.";

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24),dp(28),dp(24),dp(24));
        root.setBackgroundColor(Color.rgb(7,19,33));

        TextView title=text("Обновление Muwahid",25,Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        gap(root,10);
        root.addView(text("Версия "+versionName,17,Color.rgb(16,214,171)));
        gap(root,18);
        TextView n=text(notes,15,Color.rgb(210,222,234));
        n.setLineSpacing(dp(4),1f);
        root.addView(n);
        gap(root,18);
        status=text("APK будет скачан внутри Muwahid. Вход в GitHub не нужен. После проверки файла откроется системное окно Android для установки.",13,Color.rgb(143,166,188));
        root.addView(status);
        gap(root,18);

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);progress.setProgress(0);progress.setVisibility(View.GONE);
        root.addView(progress,new LinearLayout.LayoutParams(-1,dp(14)));
        gap(root,18);

        update=new Button(this);
        update.setText("Скачать и установить");
        update.setAllCaps(false);
        update.setTextSize(16);
        root.addView(update,new LinearLayout.LayoutParams(-1,dp(54)));
        update.setOnClickListener(v->downloadUpdate());

        gap(root,12);
        Button later=new Button(this);
        later.setText("Позже");
        later.setAllCaps(false);
        root.addView(later,new LinearLayout.LayoutParams(-1,dp(50)));
        later.setOnClickListener(v->{if(!busy)finish();});
        setContentView(root);
    }

    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private void gap(LinearLayout l,int h){l.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(h)));}

    private void downloadUpdate(){
        if(busy)return;
        if(!validReleaseUrl(url)||expectedSha256==null||!expectedSha256.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")){
            toast("Некорректные данные обновления");return;
        }
        busy=true;update.setEnabled(false);progress.setVisibility(View.VISIBLE);status.setText("Скачиваем обновление внутри Muwahid…");
        new Thread(()->{
            File tmp=null;
            try{
                File dir=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if(dir==null)throw new IOException("Папка загрузок недоступна");
                if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не удалось создать папку загрузок");
                tmp=new File(dir,"Muwahid-"+safe(versionName)+".apk.part");
                File apk=new File(dir,"Muwahid-"+safe(versionName)+".apk");
                download(url,tmp);
                String actual=sha256(tmp);
                if(!actual.equalsIgnoreCase(expectedSha256))throw new SecurityException("Контрольная сумма APK не совпала");
                if(apk.exists()&&!apk.delete())throw new IOException("Не удалось заменить старый APK");
                if(!tmp.renameTo(apk)){copy(tmp,apk);tmp.delete();}
                final File ready=apk;
                runOnUiThread(()->{status.setText("APK скачан и проверен. Открываем системную установку Android…");progress.setProgress(1000);openInstaller(ready);});
            }catch(Exception e){
                if(tmp!=null)tmp.delete();
                String message=e.getMessage()==null?"Не удалось скачать обновление":e.getMessage();
                runOnUiThread(()->{busy=false;update.setEnabled(true);progress.setVisibility(View.GONE);status.setText("Ошибка: "+message);toast(message);});
            }
        },"muwahid-update-download").start();
    }

    private void download(String firstUrl,File target) throws Exception{
        String current=firstUrl;
        for(int redirects=0;redirects<6;redirects++){
            URL parsed=new URL(current);
            if(!"https".equalsIgnoreCase(parsed.getProtocol()))throw new SecurityException("Разрешены только HTTPS-ссылки");
            if(redirects==0&&!validReleaseUrl(current))throw new SecurityException("Недоверенная ссылка обновления");
            HttpsURLConnection c=(HttpsURLConnection)parsed.openConnection();
            c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            c.setRequestProperty("Accept","application/vnd.android.package-archive,application/octet-stream,*/*");
            c.setRequestProperty("User-Agent","Muwahid-Android/"+BuildConfig.VERSION_NAME);
            int code=c.getResponseCode();
            if(code>=300&&code<400){String next=c.getHeaderField("Location");c.disconnect();if(next==null)throw new IOException("Сервер не вернул адрес загрузки");current=new URL(parsed,next).toString();continue;}
            if(code!=HttpURLConnection.HTTP_OK){c.disconnect();throw new IOException("Ошибка загрузки HTTP "+code);}
            long total=c.getContentLengthLong(),done=0;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(target)){
                byte[] buf=new byte[32768];int n;
                while((n=in.read(buf))!=-1){done+=n;if(done>80L*1024L*1024L)throw new IOException("APK слишком большой");out.write(buf,0,n);if(total>0){final int p=(int)Math.min(950,done*950/total);runOnUiThread(()->progress.setProgress(p));}}
                out.getFD().sync();
            }finally{c.disconnect();}
            if(done<1024)throw new IOException("Получен пустой APK");
            return;
        }
        throw new IOException("Слишком много перенаправлений");
    }

    private void openInstaller(File apk){
        busy=false;
        update.setEnabled(true);
        pendingApk=apk;
        try{
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()){
                status.setText("APK скачан и проверен. Разрешите Muwahid устанавливать обновления на системном экране Android.");
                Intent allow=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()));
                startActivityForResult(allow,REQ_UNKNOWN_SOURCES);
                return;
            }
            launchInstaller(apk);
        }catch(Exception e){
            status.setText("APK скачан, но Android не смог открыть системный установщик. Нажмите «Скачать и установить» ещё раз.");
            toast("Не удалось открыть системный установщик");
        }
    }

    private void launchInstaller(File apk){
        try{
            Uri content=FileProvider.getUriForFile(this,getPackageName()+".files",apk);
            Intent i=new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(content);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            status.setText("APK проверен. Открыт системный установщик Android.");
        }catch(Exception first){
            try{
                Uri content=FileProvider.getUriForFile(this,getPackageName()+".files",apk);
                Intent fallback=new Intent(Intent.ACTION_VIEW);
                fallback.setDataAndType(content,"application/vnd.android.package-archive");
                fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
                status.setText("APK проверен. Открыт системный установщик Android.");
            }catch(Exception second){
                status.setText("APK скачан, но Android не смог открыть установщик. Нажмите кнопку ещё раз.");
                toast("Не удалось открыть системный установщик");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_UNKNOWN_SOURCES && pendingApk!=null){
            if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()){
                launchInstaller(pendingApk);
            }else{
                status.setText("Android не разрешил установку обновления. Разрешение можно включить на системном экране и вернуться в Muwahid.");
                toast("Нужно разрешение Android на установку обновлений");
            }
        }
    }

    @Override protected void onResume(){
        super.onResume();
        if(pendingApk!=null && Build.VERSION.SDK_INT>=Build.VERSION_CODES.O && getPackageManager().canRequestPackageInstalls()){
            File ready=pendingApk;
            pendingApk=null;
            launchInstaller(ready);
        }
    }

    private boolean validReleaseUrl(String value){
        if(value==null)return false;
        final String prefix="https://github.com/ahmadmukhammad955-creator/Muwahid/releases/download/";
        if(!value.startsWith(prefix))return false;
        String tail=value.substring(prefix.length());int slash=tail.indexOf('/');
        return slash>1&&tail.substring(0,slash).matches("v[0-9A-Za-z._-]+")&&tail.substring(slash+1).matches("Muwahid-[0-9A-Za-z._-]+\\.apk");
    }
    private String safe(String s){return s==null?"update":s.replaceAll("[^0-9A-Za-z._-]","_");}
    private String sha256(File file) throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte x:d.digest())out.append(String.format(Locale.US,"%02x",x&255));return out.toString();}
    private void copy(File from,File to) throws Exception{try(InputStream in=new FileInputStream(from);OutputStream out=new FileOutputStream(to)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
