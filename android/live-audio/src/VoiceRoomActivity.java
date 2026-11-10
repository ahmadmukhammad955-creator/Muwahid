package com.muwahid.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.os.Environment;
import android.content.ContentValues;
import android.util.Base64;
import androidx.core.content.FileProvider;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class VoiceRoomActivity extends Activity {
    private static final int REQ_MIC=7301, REQ_ROOM_IMAGE=7401, REQ_ROOM_VIDEO=7402, REQ_ROOM_BOOK=7403, REQ_ROOM_AUDIO=7404, REQ_BACKGROUND_IMAGE=7410, REQ_BACKGROUND_VIDEO=7411;
    private WebView web;
    private Api api;
    private final ExecutorService worker=Executors.newFixedThreadPool(4);
    private final ExecutorService recorderWorker=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String,File> bookCache=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,File> musicCache=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> musicMime=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,File> recordingFiles=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,File> backgroundFiles=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> backgroundMimes=new ConcurrentHashMap<>();
    private final Set<String> backgroundLoading=ConcurrentHashMap.newKeySet();
    private String roomId,roomTitle,roomMode="audio",roomAccess="open";
    private int roomCapacity=8;
    private AudioManager audioManager;
    private volatile boolean joined;

    private void clearSavedLiveRoom(){
        getSharedPreferences("muwahid",MODE_PRIVATE).edit()
            .putBoolean("live_active",false).remove("live_room_id")
            .remove("live_room_title").remove("live_room_mode")
            .remove("live_room_access").remove("live_room_capacity").apply();
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);if(AppLock.guard(this))return;
        getWindow().setStatusBarColor(Color.parseColor("#06131F"));
        getWindow().setNavigationBarColor(Color.parseColor("#06131F"));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        api=new Api(new SecureStore(this));audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        Intent i=getIntent();roomId=i.getStringExtra("room_id");roomTitle=i.getStringExtra("room_title");roomMode=i.getStringExtra("room_mode");roomAccess=i.getStringExtra("room_access");roomCapacity=i.getIntExtra("room_capacity",8);
        if(roomTitle==null||roomTitle.trim().isEmpty())roomTitle="LIVE";if(roomMode==null||(!"audio".equals(roomMode)&&!"video".equals(roomMode)))roomMode="audio";if(roomAccess==null||(!"open".equals(roomAccess)&&!"closed".equals(roomAccess)))roomAccess="open";roomCapacity=Math.max(2,Math.min(12,roomCapacity));
        if(!api.signedIn()||roomId==null||roomId.isEmpty()){finish();return;}
        ArrayList<String> need=new ArrayList<>();if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)need.add(android.Manifest.permission.RECORD_AUDIO);if("video".equals(roomMode)&&checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)need.add(android.Manifest.permission.CAMERA);
        if(need.isEmpty())setup();else requestPermissions(need.toArray(new String[0]),REQ_MIC);
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ_MIC){
            boolean mic=checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            boolean cam=!"video".equals(roomMode)||checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
            if(mic&&cam)setup();
            else {clearSavedLiveRoom();new AlertDialog.Builder(this).setMessage("video".equals(roomMode)?"Для видеоэфира нужен доступ к микрофону и камере.":"Для аудиоэфира нужен доступ к микрофону.").setPositiveButton("Закрыть",(d,w)->finish()).show();}
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled") private void setup(){
        if(web!=null)return;
        getSharedPreferences("muwahid",MODE_PRIVATE).edit()
            .putBoolean("live_active",true).putString("live_room_id",roomId)
            .putString("live_room_title",roomTitle).putString("live_room_mode",roomMode)
            .putString("live_room_access",roomAccess).putInt("live_room_capacity",roomCapacity).apply();
        WebView.setWebContentsDebuggingEnabled(false);web=new WebView(this);web.setBackgroundColor(Color.parseColor("#06131F"));
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setMediaPlaybackRequiresUserGesture(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setDomStorageEnabled(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest r){runOnUiThread(()->{
            if(!trustedOrigin(r.getOrigin())){r.deny();return;}
            ArrayList<String> allow=new ArrayList<>();
            for(String x:r.getResources()){
                if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x)&&checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)allow.add(x);
                else if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(x)&&"video".equals(roomMode)&&checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)allow.add(x);
            }
            if(allow.isEmpty())r.deny();else r.grant(allow.toArray(new String[0]));
        });}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return request==null||!trustedPage(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){try{return !trustedPage(Uri.parse(url));}catch(Exception e){return true;}}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                return interceptLocal(request==null?null:request.getUrl(),request==null?null:request.getRequestHeaders());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){
                try{return interceptLocal(url==null?null:Uri.parse(url),null);}catch(Exception e){return blocked();}
            }
        });
        web.addJavascriptInterface(new Bridge(),"MuwahidNative");setContentView(web);
        try{
            String html=readAsset("voice_room.html");
            String init="window.MW={roomId:"+JSONObject.quote(roomId)+",roomTitle:"+JSONObject.quote(roomTitle)+",selfId:"+JSONObject.quote(api.uid())+",roomMode:"+JSONObject.quote(roomMode)+",roomCapacity:"+roomCapacity+",roomAccess:"+JSONObject.quote(roomAccess)+"};";
            html=html.replace("/*__MW_INIT__*/",init);
            web.loadDataWithBaseURL("https://muwahid.local/",html,"text/html","UTF-8",null);
        }catch(Exception e){clearSavedLiveRoom();new AlertDialog.Builder(this).setMessage("Не удалось открыть голосовую комнату.").setPositiveButton("Закрыть",(d,w)->finish()).show();}
    }

    private boolean trustedOrigin(Uri u){return u!=null&&"https".equalsIgnoreCase(u.getScheme())&&"muwahid.local".equalsIgnoreCase(u.getHost());}
    private boolean trustedPage(Uri u){return u!=null&&(("about".equalsIgnoreCase(u.getScheme())&&"blank".equalsIgnoreCase(u.getSchemeSpecificPart()))||trustedOrigin(u));}
    private WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    private WebResourceResponse interceptLocal(Uri uri,Map<String,String> headers){
        if(uri==null)return blocked();
        String scheme=uri.getScheme();
        if(("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme))&&!trustedOrigin(uri))return blocked();
        if(!trustedOrigin(uri))return null;
        try{
            if("/live-background".equals(uri.getPath())){
                String id=uri.getQueryParameter("id");
                File f=id==null?null:backgroundFiles.get(id);
                if(f==null||!f.isFile()||f.length()==0)return blocked();
                String mime=backgroundMimes.get(id);
                if(mime==null)return blocked();
                long start=0,end=f.length()-1;
                String range=headers==null?null:headers.get("Range");
                if(range!=null&&range.matches("bytes=[0-9]+-[0-9]*")){
                    String[] r=range.substring(6).split("-",-1);
                    start=Long.parseLong(r[0]);
                    if(!r[1].isEmpty())end=Math.min(end,Long.parseLong(r[1]));
                    if(start>=f.length()||end<start)return blocked();
                }
                FileInputStream in=new FileInputStream(f);
                long remaining=start;
                while(remaining>0){long skipped=in.skip(remaining);if(skipped<=0){in.close();return blocked();}remaining-=skipped;}
                final long length=end-start+1;
                InputStream limited=new FilterInputStream(in){long left=length;
                    @Override public int read() throws IOException {if(left<=0)return -1;int b=super.read();if(b>=0)left--;return b;}
                    @Override public int read(byte[] b,int off,int len) throws IOException {if(left<=0)return -1;int n=super.read(b,off,(int)Math.min(left,len));if(n>0)left-=n;return n;}
                };
                WebResourceResponse resp=new WebResourceResponse(mime,null,limited);
                Map<String,String> map=new HashMap<>();map.put("Cache-Control","no-store");map.put("Accept-Ranges","bytes");map.put("Content-Length",String.valueOf(length));
                if(range!=null){resp.setStatusCodeAndReasonPhrase(206,"Partial Content");map.put("Content-Range","bytes "+start+"-"+end+"/"+f.length());}
                resp.setResponseHeaders(map);return resp;
            }
            if("/book-page".equals(uri.getPath())){
                String path=uri.getQueryParameter("path");
                int page=Integer.parseInt(uri.getQueryParameter("page"));
                byte[] image=renderBookPage(path,page);
                WebResourceResponse r=new WebResourceResponse("image/jpeg",null,new ByteArrayInputStream(image));
                Map<String,String> h=new HashMap<>();h.put("Cache-Control","no-store");r.setResponseHeaders(h);return r;
            }
            if("/music-file".equals(uri.getPath())){
                String id=uri.getQueryParameter("id");File f=id==null?null:musicCache.get(id);
                if(f==null||!f.exists()||f.length()==0)return blocked();
                String mime=musicMime.get(id);if(mime==null||mime.isEmpty())mime="audio/mpeg";
                WebResourceResponse r=new WebResourceResponse(mime,null,new FileInputStream(f));
                Map<String,String> h=new HashMap<>();h.put("Cache-Control","no-store");h.put("Accept-Ranges","none");r.setResponseHeaders(h);return r;
            }
            return blocked();
        }catch(Exception e){return blocked();}
    }

    private String readAsset(String name) throws Exception{
        StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(getAssets().open(name),"UTF-8"))){String line;while((line=r.readLine())!=null)b.append(line).append('\n');}return b.toString();
    }

    private final class Bridge {
        private final Set<String> allowed=new HashSet<>(Arrays.asList(
            "mw_room_join","mw_room_leave","mw_room_state","mw_room_raise_hand","mw_room_mic_set_self","mw_room_mic_moderate",
            "mw_room_kick","mw_room_end","mw_room_signal_send","mw_room_signal_poll","mw_room_background_get",
            "mw_room_theme_get","mw_room_theme_set","mw_app_config",
            "mw_room_moderator_set","mw_room_admit","mw_room_message_send","mw_room_messages_get",
            "mw_room_book_start","mw_room_book_page","mw_room_book_controller","mw_room_book_stop","mw_room_book_state"
        ));
        @JavascriptInterface public void rpc(String requestId,String function,String argsJson){
            if(!allowed.contains(function)){deliver(requestId,null,"Недоступная операция");return;}
            worker.execute(()->{try{
                Object result=api.rpc(function,new JSONObject(argsJson));
                if("mw_room_join".equals(function))joined=true;
                if("mw_room_leave".equals(function)||"mw_room_end".equals(function)){
                    joined=false;
                    // A server-confirmed manual exit must not be reopened by the launcher.
                    clearSavedLiveRoom();
                }
                deliver(requestId,result==null?"null":result.toString(),null);
            }catch(Exception e){deliver(requestId,null,e.getMessage()==null?"Ошибка связи":e.getMessage());}});
        }

        @JavascriptInterface public void pickBackground(String kind){runOnUiThread(()->{
            if(!"image".equals(kind)&&!"video".equals(kind))return;
            try{
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image".equals(kind)?"image/*":"video/*");
                startActivityForResult(intent,"image".equals(kind)?REQ_BACKGROUND_IMAGE:REQ_BACKGROUND_VIDEO);
            }catch(Exception e){toast("Не удалось выбрать фон");}
        });}

        @JavascriptInterface public void cacheBackground(String path,String kind){
            if(path==null||(!"image".equals(kind)&&!"video".equals(kind))||
                    !path.startsWith(roomId+"/background/")||path.contains(".."))return;
            if(!backgroundLoading.add(path))return;
            worker.execute(()->{try{
                String key=sha256(path),mime=backgroundMime(path,kind);
                File file=backgroundFiles.get(key);
                if(file==null||!file.exists()){
                    byte[] bytes=api.downloadRoomMedia(path);
                    File directory=new File(getCacheDir(),"live_background");directory.mkdirs();
                    file=new File(directory,key+".media");
                    try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}
                    backgroundFiles.put(key,file);backgroundMimes.put(key,mime);
                }
                final String uri="https://muwahid.local/live-background?id="+Uri.encode(key);
                runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.mwBackgroundReady&&window.mwBackgroundReady("+JSONObject.quote(uri)+","+JSONObject.quote(kind)+","+JSONObject.quote(path)+");",null);});
            }catch(Exception e){runOnUiThread(()->toast("Не удалось загрузить фон эфира: "+e.getMessage()));}
            finally{backgroundLoading.remove(path);}});
        }

        @JavascriptInterface public void pickMedia(String kind){runOnUiThread(()->{
            try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
                if("image".equals(kind)){i.setType("image/*");startActivityForResult(i,REQ_ROOM_IMAGE);}
                else if("video".equals(kind)){i.setType("video/*");startActivityForResult(i,REQ_ROOM_VIDEO);}
            }catch(Exception e){toast("Не удалось открыть выбор файла");}
        });}

        @JavascriptInterface public void pickAudio(){runOnUiThread(()->{
            try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");startActivityForResult(i,REQ_ROOM_AUDIO);}
            catch(Exception e){toast("Не удалось открыть выбор музыки");}
        });}

        @JavascriptInterface public void recordingChunk(String session,String base64,boolean finish){
            if(session==null||!session.matches("[A-Za-z0-9_-]{6,80}"))return;
            recorderWorker.execute(()->{try{
                File dir=new File(getCacheDir(),"room_recordings");dir.mkdirs();
                File f=recordingFiles.computeIfAbsent(session,k->new File(dir,"Muwahid_"+k+".webm"));
                if(base64!=null&&!base64.isEmpty()){byte[] data=Base64.decode(base64,Base64.DEFAULT);try(FileOutputStream out=new FileOutputStream(f,true)){out.write(data);}}
                if(finish){recordingFiles.remove(session);String saved=publishRecording(f);runOnUiThread(()->{toast("Запись сохранена: "+saved);if(web!=null)web.evaluateJavascript("window.mwRecordingSaved&&window.mwRecordingSaved("+JSONObject.quote(saved)+");",null);});}
            }catch(Exception e){runOnUiThread(()->toast(e.getMessage()==null?"Не удалось сохранить запись":e.getMessage()));}});
        }

        @JavascriptInterface public void pickBook(){runOnUiThread(()->{
            try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/pdf");startActivityForResult(i,REQ_ROOM_BOOK);}
            catch(Exception e){toast("Не удалось открыть выбор PDF");}
        });}

        @JavascriptInterface public void openMedia(String path,String mime,String name){
            worker.execute(()->{try{byte[] raw=api.downloadRoomMedia(path);File dir=new File(getCacheDir(),"room_media");dir.mkdirs();String safe=(name==null?"file":name).replaceAll("[^A-Za-zА-Яа-я0-9._ -]","_");File f=new File(dir,UUID.randomUUID()+"_"+safe);try(FileOutputStream out=new FileOutputStream(f)){out.write(raw);}Uri u=FileProvider.getUriForFile(VoiceRoomActivity.this,getPackageName()+".files",f);Intent open=new Intent(Intent.ACTION_VIEW);open.setDataAndType(u,mime==null?"application/octet-stream":mime);open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);runOnUiThread(()->{try{startActivity(open);}catch(Exception ex){toast("На телефоне нет приложения для открытия этого файла");}});}catch(Exception e){runOnUiThread(()->toast(e.getMessage()==null?"Не удалось открыть файл":e.getMessage()));}});
        }

        @JavascriptInterface public void invite(){runOnUiThread(()->{
            try{
                Uri link=new Uri.Builder().scheme("muwahid").authority("live")
                    .appendQueryParameter("room_id",roomId)
                    .appendQueryParameter("title",roomTitle)
                    .appendQueryParameter("mode",roomMode)
                    .appendQueryParameter("capacity",String.valueOf(roomCapacity))
                    .appendQueryParameter("access",roomAccess).build();
                Uri httpsLink=link.buildUpon().scheme("https").authority("muwahid.app").path("/live").build();
                StringBuilder text=new StringBuilder();
                text.append("Присоединяйся к эфиру «").append(roomTitle)
                    .append("» в Muwahid\n").append(httpsLink.toString())
                    .append("\nОткрыть в приложении: ").append(link.toString());
                if("closed".equals(roomAccess))text.append("\nЗакрытый эфир: ведущий подтвердит вход.");
                String invitation=text.toString();
                new AlertDialog.Builder(VoiceRoomActivity.this)
                    .setTitle("Поделиться эфиром")
                    .setItems(new String[]{"Контактам Muwahid","Через другие приложения","Скопировать ссылку"},(dialog,choice)->{
                        if(choice==0)showInviteContacts(invitation);
                        else if(choice==1)shareToOtherApps(invitation);
                        else{
                            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                            if(clipboard!=null){clipboard.setPrimaryClip(ClipData.newPlainText("Приглашение в эфир",invitation));toast("Приглашение скопировано");}
                        }
                    }).show();
            }catch(Exception e){toast("Не удалось открыть отправку приглашения");}
        });}
        @JavascriptInterface public void setSpeaker(boolean enabled){runOnUiThread(()->{try{if(audioManager!=null){audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);audioManager.setSpeakerphoneOn(enabled);}}catch(Exception ignored){}});}
        @JavascriptInterface public void close(){runOnUiThread(()->{
            clearSavedLiveRoom();
            finish();
        });}
    }

    private void shareToOtherApps(String invitation){
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,"Приглашение в эфир Muwahid");
        send.putExtra(Intent.EXTRA_TEXT,invitation);
        try{startActivity(Intent.createChooser(send,"Отправить приглашение через"));}
        catch(Exception e){toast("Не удалось открыть приложения для отправки");}
    }

    private boolean inviteBusy;
    private void showInviteContacts(String invitation){
        if(inviteBusy)return;
        inviteBusy=true;
        toast("Загружаем контакты…");
        worker.execute(()->{try{
            JSONArray contacts=(JSONArray)api.rpc("mw_chat_list",new JSONObject());
            runOnUiThread(()->{
                inviteBusy=false;if(isFinishing()||isDestroyed())return;
                if(contacts.length()==0){
                    new AlertDialog.Builder(this).setTitle("Контактов Muwahid пока нет")
                        .setMessage("Можно отправить приглашение через другие приложения.")
                        .setPositiveButton("Поделиться",(d,w)->shareToOtherApps(invitation))
                        .setNegativeButton("Отмена",null).show();return;
                }
                String[] names=new String[contacts.length()];boolean[] selected=new boolean[names.length];
                for(int i=0;i<names.length;i++)names[i]=contacts.optJSONObject(i).optString("display_name","Контакт");
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Пригласить контакты в эфир")
                    .setMultiChoiceItems(names,selected,(d,index,checked)->selected[index]=checked)
                    .setNegativeButton("Отмена",null).setPositiveButton("Отправить",null).create();
                dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                    ArrayList<JSONObject> recipients=new ArrayList<>();
                    for(int i=0;i<selected.length;i++)if(selected[i])recipients.add(contacts.optJSONObject(i));
                    if(recipients.isEmpty()){toast("Выберите хотя бы один контакт");return;}
                    dialog.dismiss();sendLiveInvitations(recipients,invitation);
                }));dialog.show();
            });
        }catch(Exception e){runOnUiThread(()->{inviteBusy=false;toast("Не удалось загрузить контакты. Попробуйте снова.");});}});
    }
    private void sendLiveInvitations(ArrayList<JSONObject> recipients,String invitation){
        if(inviteBusy)return;inviteBusy=true;toast("Отправляем приглашения…");
        worker.execute(()->{
            int sent=0;ArrayList<String> failed=new ArrayList<>();
            CryptoBox crypto=new CryptoBox(this);
            for(JSONObject contact:recipients){try{
                String id=contact.getString("id"),uid=api.uid();
                api.rpc("mw_e2ee_set_key",new JSONObject().put("p_public_key",crypto.publicKey(uid)));
                Object value=api.rpc("mw_e2ee_peer_key",new JSONObject().put("p_chat",id));
                if(!(value instanceof String)||((String)value).isEmpty())throw new Exception("E2EE unavailable");
                String payload=crypto.encryptText(uid,id,(String)value,invitation);
                api.rpc("mw_send",new JSONObject().put("p_chat",id).put("p_body",payload).put("p_nonce",UUID.randomUUID().toString()));sent++;
            }catch(Exception e){failed.add(contact.optString("display_name","Контакт"));}}
            final int count=sent;
            runOnUiThread(()->{inviteBusy=false;if(isFinishing()||isDestroyed())return;
                String result="Отправлено приглашений: "+count;
                if(failed.isEmpty())toast(result);
                else new AlertDialog.Builder(this).setTitle("Приглашения в эфир").setMessage(result+"\nНе подтверждена отправка: "+android.text.TextUtils.join(", ",failed)+". Проверьте чат и соединение. Для отправки нужен активный E2EE у получателя.").setPositiveButton("Понятно",null).show();
            });
        });
    }

    private void deliver(String requestId,String json,String error){runOnUiThread(()->{
        if(web==null)return;
        String js="window.mwResult("+JSONObject.quote(requestId)+","+(json==null?"null":JSONObject.quote(json))+","+(error==null?"null":JSONObject.quote(error))+");";
        web.evaluateJavascript(js,null);
    });}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        final Uri uri=data.getData();
        if(requestCode==REQ_BACKGROUND_IMAGE||requestCode==REQ_BACKGROUND_VIDEO){
            handleBackgroundSelected(uri,requestCode==REQ_BACKGROUND_VIDEO);return;
        }
        if(requestCode==REQ_ROOM_AUDIO){handleAudioSelected(uri);return;}
        if(requestCode==REQ_ROOM_BOOK){handleBookSelected(uri);return;}
        if(requestCode!=REQ_ROOM_IMAGE&&requestCode!=REQ_ROOM_VIDEO)return;
        final String kind=requestCode==REQ_ROOM_IMAGE?"image":"video";
        worker.execute(()->{try{
            byte[] raw=readUri(uri,24*1024*1024);String mime=getContentResolver().getType(uri);if(mime==null)mime=kind.equals("image")?"image/jpeg":"video/mp4";
            String name=kind.equals("image")?"Фото":"Видео";String path=roomId+"/"+UUID.randomUUID()+".bin";api.uploadRoomMedia(path,raw);
            JSONObject media=new JSONObject().put("path",path).put("mime",mime).put("name",name).put("size",raw.length);
            api.rpc("mw_room_message_send",new JSONObject().put("p_room",roomId).put("p_kind",kind).put("p_body","").put("p_media",media));
            runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.mwRoomMediaSent&&window.mwRoomMediaSent();",null);});
        }catch(Exception e){runOnUiThread(()->toast(e.getMessage()==null?"Не удалось отправить файл":e.getMessage()));}});
    }

    private static String backgroundMime(String path,String kind){
        if("image".equals(kind))return path.endsWith(".png")?"image/png":path.endsWith(".webp")?"image/webp":"image/jpeg";
        return path.endsWith(".webm")?"video/webm":"video/mp4";
    }
    private void handleBackgroundSelected(Uri uri,boolean video){
        worker.execute(()->{try{
            String mime=getContentResolver().getType(uri);
            if(mime==null)throw new Exception("Неизвестный тип файла");
            if(video&&!Arrays.asList("video/mp4","video/webm").contains(mime))throw new Exception("Видео должно быть MP4 или WebM");
            if(!video&&!Arrays.asList("image/jpeg","image/png","image/webp").contains(mime))throw new Exception("Фото должно быть JPG, PNG или WebP");
            File dir=new File(getCacheDir(),"live_background");dir.mkdirs();
            File tmp=new File(dir,UUID.randomUUID()+".tmp");
            try{
                copyUriToFile(uri,tmp,video?24L*1024L*1024L:10L*1024L*1024L);
                long durationSeconds=0;
                if(video){
                    MediaMetadataRetriever mmr=new MediaMetadataRetriever();
                    try{mmr.setDataSource(tmp.getAbsolutePath());String duration=mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if(duration==null)throw new Exception("Продолжительность видео недоступна");
                        long ms=Long.parseLong(duration);if(ms<15000||ms>60000)throw new Exception("Длительность видео должна быть от 15 до 60 секунд");
                        durationSeconds=(ms+999)/1000;
                    }finally{mmr.release();}
                }
                String ext=video?("video/webm".equals(mime)?".webm":".mp4"):("image/png".equals(mime)?".png":"image/webp".equals(mime)?".webp":".jpg");
                String path=roomId+"/background/"+UUID.randomUUID()+ext;
                byte[] data=readUri(uri,video?24*1024*1024:10*1024*1024);
                api.uploadRoomMedia(path,data);
                JSONObject media=new JSONObject().put("path",path).put("mime",mime).put("name",video?"Фон: видео":"Фон: фото")
                    .put("size",data.length).put("live_background",true).put("duration_seconds",durationSeconds);
                api.rpc("mw_room_message_send",new JSONObject().put("p_room",roomId).put("p_kind",video?"video":"image").put("p_body","").put("p_media",media));
                runOnUiThread(()->{
                    toast("Фон эфира установлен");
                    if(web!=null)web.evaluateJavascript("window.mwBackgroundPublished&&window.mwBackgroundPublished("+JSONObject.quote(path)+","+JSONObject.quote(video?"video":"image")+");",null);
                });
            }finally{tmp.delete();}
        }catch(Exception e){runOnUiThread(()->toast("Не удалось установить фон: "+e.getMessage()));}});
    }

    private void handleAudioSelected(Uri uri){
        worker.execute(()->{try{
            String name=queryDisplayName(uri);if(name==null||name.trim().isEmpty())name="Музыка";name=name.trim();if(name.length()>140)name=name.substring(0,140);
            String mime=getContentResolver().getType(uri);if(mime==null||!mime.startsWith("audio/"))mime="audio/mpeg";
            String id=UUID.randomUUID().toString();File dir=new File(getCacheDir(),"room_music");dir.mkdirs();File f=new File(dir,id+".audio");
            copyUriToFile(uri,f,96L*1024L*1024L);musicCache.put(id,f);musicMime.put(id,mime);
            final String url="https://muwahid.local/music-file?id="+Uri.encode(id),title=name;
            runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.mwMusicSelected&&window.mwMusicSelected("+JSONObject.quote(url)+","+JSONObject.quote(title)+");",null);});
        }catch(Exception e){runOnUiThread(()->toast(e.getMessage()==null?"Не удалось открыть музыку":e.getMessage()));}});
    }

    private void handleBookSelected(Uri uri){
        worker.execute(()->{try{
            byte[] raw=readUri(uri,48*1024*1024);
            String name=queryDisplayName(uri);if(name==null||name.trim().isEmpty())name="Книга.pdf";name=name.trim();if(name.length()>180)name=name.substring(0,180);
            File tmp=new File(getCacheDir(),"selected_"+UUID.randomUUID()+".pdf");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(raw);}
            int pages=pdfPageCount(tmp);if(pages<1)throw new Exception("PDF не содержит страниц");if(pages>5000)throw new Exception("В книге слишком много страниц");
            String objectPath=roomId+"/books/"+UUID.randomUUID()+".pdf";
            api.uploadRoomBook(objectPath,raw);
            bookCache.put(objectPath,tmp);
            Object result=api.rpc("mw_room_book_start",new JSONObject().put("p_room",roomId).put("p_path",objectPath).put("p_file_name",name).put("p_total_pages",pages));
            final String json=result==null?"{}":result.toString();
            runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.mwBookStarted&&window.mwBookStarted("+json+");",null);});
        }catch(Exception e){runOnUiThread(()->toast(e.getMessage()==null?"Не удалось открыть книгу":e.getMessage()));}});
    }

    private String queryDisplayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return null;
    }

    private int pdfPageCount(File file) throws Exception{
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer r=new PdfRenderer(fd)){
            return r.getPageCount();
        }
    }

    private byte[] renderBookPage(String path,int pageIndex) throws Exception{
        if(path==null||!path.startsWith(roomId+"/books/")||!path.endsWith(".pdf"))throw new Exception("Некорректная книга");
        File file=ensureBookFile(path);
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            if(pageIndex<0||pageIndex>=renderer.getPageCount())throw new Exception("Страница не существует");
            try(PdfRenderer.Page page=renderer.openPage(pageIndex)){
                int baseW=Math.max(1,page.getWidth()),baseH=Math.max(1,page.getHeight());
                float scale=Math.min(2.4f,1280f/baseW);
                if(baseH*scale>2100f)scale=2100f/baseH;
                int w=Math.max(1,Math.round(baseW*scale)),h=Math.max(1,Math.round(baseH*scale));
                Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bmp.eraseColor(Color.WHITE);
                Matrix matrix=new Matrix();matrix.setScale(scale,scale);
                page.render(bmp,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream out=new ByteArrayOutputStream();bmp.compress(Bitmap.CompressFormat.JPEG,90,out);bmp.recycle();return out.toByteArray();
            }
        }
    }

    private File ensureBookFile(String path) throws Exception{
        File cached=bookCache.get(path);if(cached!=null&&cached.exists()&&cached.length()>0)return cached;
        File dir=new File(getCacheDir(),"room_books");dir.mkdirs();
        File f=new File(dir,sha256(path)+".pdf");
        if(!f.exists()||f.length()==0){byte[] raw=api.downloadRoomBook(path);File t=new File(dir,f.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(t)){out.write(raw);}if(!t.renameTo(f)){try(FileInputStream in=new FileInputStream(t);FileOutputStream out=new FileOutputStream(f)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}t.delete();}}
        bookCache.put(path,f);return f;
    }

    private String sha256(String s) throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=d.digest(s.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.US,"%02x",x&255));return out.toString();
    }

    private void copyUriToFile(Uri uri,File outFile,long max) throws Exception{
        long total=0;try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(outFile)){
            if(in==null)throw new Exception("Не удалось открыть файл");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){total+=n;if(total>max){outFile.delete();throw new Exception("Аудиофайл слишком большой");}out.write(b,0,n);}
        }
    }

    private String publishRecording(File source) throws Exception{
        if(source==null||!source.exists()||source.length()==0)throw new Exception("Запись пустая");
        String name="Muwahid_room_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".webm";
        if(Build.VERSION.SDK_INT>=29){
            ContentValues v=new ContentValues();v.put(MediaStore.Audio.Media.DISPLAY_NAME,name);v.put(MediaStore.Audio.Media.MIME_TYPE,"audio/webm");v.put(MediaStore.Audio.Media.RELATIVE_PATH,Environment.DIRECTORY_MUSIC+"/Muwahid");v.put(MediaStore.Audio.Media.IS_PENDING,1);
            Uri u=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception("Не удалось создать файл записи");
            try(OutputStream out=getContentResolver().openOutputStream(u);InputStream in=new FileInputStream(source)){if(out==null)throw new Exception("Не удалось записать файл");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            ContentValues done=new ContentValues();done.put(MediaStore.Audio.Media.IS_PENDING,0);getContentResolver().update(u,done,null,null);source.delete();return "Music/Muwahid/"+name;
        }
        File base=getExternalFilesDir(Environment.DIRECTORY_MUSIC);if(base==null)base=getFilesDir();File dir=new File(base,"Muwahid");dir.mkdirs();File outFile=new File(dir,name);
        try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(outFile)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        source.delete();return outFile.getAbsolutePath();
    }

    private byte[] readUri(Uri uri,int max) throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new Exception("Не удалось открыть файл");byte[] b=new byte[16384];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>max)throw new Exception("Файл слишком большой");out.write(b,0,n);}}return out.toByteArray();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    @Override public void onBackPressed(){
        if(web!=null)web.evaluateJavascript("if(!(window.handleLiveBack&&window.handleLiveBack())){if(window.requestExitRoom){window.requestExitRoom();}else{MuwahidNative.close();}}",null);
        else super.onBackPressed();
    }

    @Override protected void onResume(){super.onResume();if(!isFinishing())AppLock.guard(this);}
    @Override public void onDestroy(){
        // Do not leave or end the live room here. Android may destroy a paused
        // Activity while an external sharing app is in front. Explicit leave and
        // end are handled by the LIVE page via mw_room_leave/mw_room_end.
        if(web!=null){web.loadUrl("about:blank");web.destroy();web=null;}
        for(File f:musicCache.values())try{if(f!=null)f.delete();}catch(Exception ignored){}musicCache.clear();musicMime.clear();
        for(File f:backgroundFiles.values())try{if(f!=null)f.delete();}catch(Exception ignored){}backgroundFiles.clear();backgroundMimes.clear();
        try{if(audioManager!=null){audioManager.setSpeakerphoneOn(false);audioManager.setMode(AudioManager.MODE_NORMAL);}}catch(Exception ignored){}
        worker.shutdownNow();recorderWorker.shutdown();super.onDestroy();
    }
}
