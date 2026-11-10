package com.muwahid.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Real contact profile and 24-hour E2EE stories. No WebView or external media player. */
public final class StoriesActivity extends Activity {
    private static final int PICK_STORY=6991;
    private static final int MAX_BYTES=StoryMediaCodec.MAX_CIPHER_BYTES-StoryMediaCodec.HEADER_BYTES;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailWork=Executors.newSingleThreadExecutor();
    private Api api;
    private CryptoBox crypto;
    private JSONArray feed=new JSONArray();
    private final android.util.LruCache<String,Bitmap> thumbnailCache=
        new android.util.LruCache<String,Bitmap>(12*1024*1024){
            @Override protected int sizeOf(String key,Bitmap value){return value==null?0:value.getByteCount();}
        };
    private JSONObject contact=new JSONObject();
    private String profileChatId="",personId="",personName="";
    private FrameLayout frame,mediaLayer;
    private TextView caption,viewerStatus;
    private ProgressBar storyProgress;
    private EditText replyInput;
    private MediaPlayer videoPlayer;
    private byte[] decryptedMedia;
    private int storyIndex=0;
    private boolean viewing=false,muted=false,paused=false;
    private long imageEndsAt=0,imageRemaining=7500;
    private Runnable imageAdvance;
    private final Runnable progressTicker=new Runnable(){
        @Override public void run(){
            if(!viewing||storyProgress==null||destroyed)return;
            if(videoPlayer!=null){
                try{int total=videoPlayer.getDuration();
                    if(total>0)storyProgress.setProgress(Math.min(1000,(int)(1000L*videoPlayer.getCurrentPosition()/total)));
                }catch(Exception ignored){}
            }else if(imageEndsAt>0){
                long remain=paused?imageRemaining:Math.max(0,imageEndsAt-SystemClock.uptimeMillis());
                storyProgress.setProgress((int)Math.max(0,Math.min(1000,1000*(7500-remain)/7500)));
            }
            ui.postDelayed(this,250);
        }
    };
    private boolean destroyed=false;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        api=new Api(new SecureStore(this));crypto=new CryptoBox(this);
        if(!api.signedIn()){Toast.makeText(this,"Войдите в аккаунт Muwahid",Toast.LENGTH_LONG).show();finish();return;}
        Intent in=getIntent();
        profileChatId=in.getStringExtra("chat_id");
        personId=in.getStringExtra("person_id");
        personName=in.getStringExtra("person_name");
        if(profileChatId==null)profileChatId="";
        if(personId==null)personId="";
        if(personName==null)personName="";
        getWindow().setStatusBarColor(Color.rgb(8,16,29));
        getWindow().setNavigationBarColor(Color.rgb(8,16,29));
        renderFeed();
        reload();
    }
    private int dp(int v){return Math.round(getResources().getDisplayMetrics().density*v);}
    private int white(){return Color.WHITE;}
    private int mutedColor(){return Color.rgb(163,179,195);}
    private TextView text(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);return t;
    }
    private GradientDrawable rounded(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView button(String s){
        TextView v=text(s,15,white());v.setGravity(Gravity.CENTER);
        v.setPadding(dp(15),dp(12),dp(15),dp(12));v.setBackground(rounded(Color.rgb(27,46,66),15));return v;
    }
    private void backgroundWork(Work work){
        io.execute(()->{try{work.run();}catch(Exception e){error(e);}});
    }
    private interface Work {void run() throws Exception;}
    private void error(Exception err){
        ui.post(()->{if(!destroyed){String s=err.getMessage();if(s==null||s.trim().isEmpty())s="Не удалось выполнить действие";
            new AlertDialog.Builder(this).setTitle("Muwahid").setMessage(s).setPositiveButton("Понятно",null).show();}});
    }
    private void reload(){
        backgroundWork(()->{
            JSONObject profile=new JSONObject();
            if(!profileChatId.isEmpty()){
                profile=(JSONObject)api.rpc("mw_contact_profile",new JSONObject().put("p_chat",profileChatId));
            }
            String targetPerson=personId.isEmpty()?profile.optString("id",""):personId;
            JSONObject args=new JSONObject();
            args.put("p_person",targetPerson.isEmpty()?JSONObject.NULL:targetPerson);
            JSONArray entries=(JSONArray)api.rpc("mw_story_feed",args);
            final JSONObject target=profile;
            ui.post(()->{if(destroyed)return;contact=target;feed=entries;
                if(personId.isEmpty())personId=targetPerson;
                renderFeed();});
        });
    }
    private void setRoot(){
        viewing=false;
        stopPlayback();
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.rgb(8,16,29));setContentView(frame);
    }
    private void renderFeed(){
        if(destroyed)return;
        setRoot();
        LinearLayout page=vertical();
        frame.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=horizontal();bar.setPadding(dp(15),dp(8),dp(15),dp(9));
        TextView back=button("‹");bar.addView(back);back.setOnClickListener(v->finish());
        TextView title=text(profileChatId.isEmpty()?"Истории":"Профиль и истории",21,white());title.setTypeface(null,1);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(52),1);tp.leftMargin=dp(13);bar.addView(title,tp);
        TextView refresh=button("↻");bar.addView(refresh);refresh.setOnClickListener(v->reload());
        page.addView(bar);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=vertical();body.setPadding(dp(16),dp(6),dp(16),dp(30));scroll.addView(body);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        if(!profileChatId.isEmpty())renderContact(body);
        TextView section=text(profileChatId.isEmpty()?"Истории контактов":"Публикации · 24 часа",17,white());
        section.setTypeface(null,1);section.setPadding(0,dp(16),0,dp(10));body.addView(section);
        if(feed.length()==0){
            TextView empty=text("Пока нет доступных историй. Публикации видны только разрешённым контактам, а содержимое защищено сквозным шифрованием.",14,mutedColor());
            empty.setPadding(dp(14),dp(22),dp(14),dp(22));empty.setBackground(rounded(Color.rgb(18,33,48),18));body.addView(empty);
        }else if(!profileChatId.isEmpty()){
            renderStoryGrid(body);
        }else{
            for(int i=0;i<feed.length();i++){
                final int idx=i;JSONObject story=feed.optJSONObject(i);if(story==null)continue;
                LinearLayout item=horizontal();item.setPadding(dp(12),dp(12),dp(8),dp(12));item.setBackground(rounded(Color.rgb(18,33,49),17));
                TextView art=text("◉",29,story.optBoolean("viewed",false)?mutedColor():Color.rgb(69,217,182));
                art.setGravity(Gravity.CENTER);item.addView(art,new LinearLayout.LayoutParams(dp(55),dp(60)));
                LinearLayout info=vertical();info.setGravity(Gravity.CENTER_VERTICAL);
                TextView who=text(story.optString("display_name","Контакт"),16,white());who.setTypeface(null,1);info.addView(who);
                TextView meta=text(("video".equals(story.optString("media_kind"))?"▶ Видео":"▧ Фото")+" · "+(story.optBoolean("visible_on_profile",false)?" · 📌 В профиле":"")+" · "+story.optString("created_at","").replace("T"," ").replace("Z",""),12,mutedColor());
                info.addView(meta);item.addView(info,new LinearLayout.LayoutParams(0,dp(58),1));
                TextView go=button("›");item.addView(go);
                item.setOnClickListener(v->openStory(idx));body.addView(item);
                LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(1,dp(8));body.addView(new View(this),space);
            }
        }
        TextView add=button("＋  Опубликовать фото или видео");
        add.setBackground(rounded(Color.rgb(15,123,115),16));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(55));ap.setMargins(0,dp(16),0,0);
        body.addView(add,ap);add.setOnClickListener(v->chooseStoryFile());
        TextView small=text("До 60 секунд · до 25 МБ · только проверенные контакты · исчезает из просмотра через 24 часа",12,mutedColor());
        small.setPadding(dp(4),dp(12),dp(4),0);body.addView(small);
    }
    private void renderContact(LinearLayout body){
        LinearLayout header=vertical();header.setPadding(dp(17),dp(22),dp(17),dp(17));header.setBackground(rounded(Color.rgb(22,41,57),21));
        String name=contact.optString("display_name",personName);
        if(name==null||name.trim().isEmpty())name="Контакт";
        TextView avatar=text(name.substring(0,1).toUpperCase(Locale.ROOT),39,white());avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(Color.rgb(27,125,158),66));LinearLayout.LayoutParams avp=new LinearLayout.LayoutParams(dp(110),dp(110));avp.gravity=Gravity.CENTER_HORIZONTAL;header.addView(avatar,avp);
        TextView display=text(name,22,white());display.setTypeface(null,1);display.setGravity(Gravity.CENTER);display.setPadding(0,dp(12),0,dp(4));header.addView(display);
        String username=contact.optString("username","");
        if(!username.isEmpty()){TextView user=text("@"+username,14,mutedColor());user.setGravity(Gravity.CENTER);header.addView(user);}
        String status=contact.optBoolean("online",false)?"в сети":"был(а) недавно";
        TextView state=text(status,13,mutedColor());state.setGravity(Gravity.CENTER);state.setPadding(0,dp(4),0,dp(8));header.addView(state);
        String bio=contact.optString("bio","");
        if(!bio.isEmpty()){TextView about=text(bio,14,white());about.setPadding(dp(8),dp(12),dp(8),dp(10));header.addView(about);}
        LinearLayout actions=horizontal();actions.setGravity(Gravity.CENTER);
        String[] labels={"Чат","Звонок","Видео"};
        for(int i=0;i<labels.length;i++){int which=i;TextView b=button(labels[i]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(52),1);p.setMargins(dp(2),0,dp(2),0);actions.addView(b,p);
            b.setOnClickListener(v->{if(which==0)finish();else{Intent result=new Intent();result.putExtra("contact_call_kind",which==1?"audio":"video");setResult(RESULT_OK,result);finish();}});}
        header.addView(actions);
        LinearLayout opts=horizontal();opts.setPadding(0,dp(12),0,0);
        TextView share=button("Поделиться");opts.addView(share,new LinearLayout.LayoutParams(0,dp(49),1));share.setOnClickListener(v->shareContact());
        TextView block=button(contact.optBoolean("blocked_by_me",false)?"Разблокировать":"Заблокировать");
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(49),1);bp.leftMargin=dp(9);opts.addView(block,bp);
        block.setOnClickListener(v->confirmBlock());
        header.addView(opts);
        body.addView(header);
    }
    private void renderStoryGrid(LinearLayout body){
        android.widget.GridLayout gallery=new android.widget.GridLayout(this);
        gallery.setColumnCount(3);
        int tile=Math.max(dp(88),(getResources().getDisplayMetrics().widthPixels-dp(52))/3);
        int count=Math.min(feed.length(),48);
        for(int i=0;i<count;i++){
            final int index=i;
            JSONObject story=feed.optJSONObject(i);if(story==null)continue;
            FrameLayout tileView=new FrameLayout(this);
            tileView.setBackground(rounded(Color.rgb(24,43,59),9));
            ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackground(rounded(Color.rgb(29,48,64),9));
            tileView.addView(preview,new FrameLayout.LayoutParams(-1,-1));
            TextView fallback=text("🔒",22,white());fallback.setGravity(Gravity.CENTER);
            tileView.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
            String meta=("video".equals(story.optString("media_kind"))?"▶ ":"")
                +(story.optBoolean("visible_on_profile",false)?"📌 ":"")
                +story.optString("created_at","").substring(0,Math.min(10,story.optString("created_at","").length()));
            TextView label=text(meta,11,white());label.setPadding(dp(6),dp(3),dp(4),dp(3));
            label.setBackgroundColor(0x99081220);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(27),Gravity.BOTTOM);
            tileView.addView(label,lp);
            android.widget.GridLayout.LayoutParams gp=new android.widget.GridLayout.LayoutParams();
            gp.width=tile;gp.height=tile*5/4;gp.setMargins(dp(2),dp(2),dp(2),dp(2));
            gallery.addView(tileView,gp);
            tileView.setOnClickListener(v->openStory(index));
            if(i<30)loadThumbnail(story,preview,fallback,tile);
        }
        body.addView(gallery);
        if(feed.length()>48){
            TextView note=text("Показаны последние 48 публикаций",12,mutedColor());
            note.setPadding(dp(4),dp(10),dp(4),dp(5));body.addView(note);
        }
    }
    private void loadThumbnail(JSONObject story,ImageView preview,TextView fallback,int size){
        String id=story.optString("id","");
        Bitmap cached=thumbnailCache.get(id);
        if(cached!=null){preview.setImageBitmap(cached);fallback.setVisibility(View.GONE);return;}
        thumbnailWork.execute(()->{
            byte[] original=null;Bitmap thumbnail=null;
            try{
                original=readDecryptedMedia(story);
                if("video".equals(story.optString("media_kind"))){
                    MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                    final byte[] video=original;
                    try{
                        retriever.setDataSource(new MediaDataSource(){
                            @Override public int readAt(long at,byte[] buffer,int offset,int length){
                                if(at<0||at>=video.length)return -1;
                                int n=(int)Math.min(length,video.length-at);
                                System.arraycopy(video,(int)at,buffer,offset,n);return n;
                            }
                            @Override public long getSize(){return video.length;}
                            @Override public void close(){}
                        });
                        thumbnail=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    }finally{retriever.release();}
                }else{
                    BitmapFactory.Options sample=new BitmapFactory.Options();sample.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(original,0,original.length,sample);
                    if(sample.outWidth>4096||sample.outHeight>4096||sample.outWidth<1||sample.outHeight<1)
                        throw new IOException("Неподдерживаемое разрешение");
                    sample.inJustDecodeBounds=false;sample.inSampleSize=1;
                    while(Math.max(sample.outWidth,sample.outHeight)/sample.inSampleSize>size*2)sample.inSampleSize*=2;
                    thumbnail=BitmapFactory.decodeByteArray(original,0,original.length,sample);
                }
                if(thumbnail==null)throw new IOException("Нет миниатюры");
                Bitmap scaled=android.media.ThumbnailUtils.extractThumbnail(thumbnail,size,size*5/4);
                if(scaled!=thumbnail)thumbnail.recycle();
                thumbnailCache.put(id,scaled);
                ui.post(()->{if(destroyed)return;preview.setImageBitmap(scaled);fallback.setVisibility(View.GONE);});
            }catch(Exception err){ui.post(()->{if(!destroyed)fallback.setText("🔒");});}
            finally{if(original!=null)Arrays.fill(original,(byte)0);}
        });
    }

    private void shareContact(){
        String u=contact.optString("username","");
        if(u.isEmpty()){Toast.makeText(this,"У контакта нет публичного имени",Toast.LENGTH_SHORT).show();return;}
        Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT,"Контакт Muwahid: @"+u);
        startActivity(Intent.createChooser(send,"Поделиться контактом"));
    }
    private void confirmBlock(){
        boolean was=contact.optBoolean("blocked_by_me",false);
        new AlertDialog.Builder(this).setTitle(was?"Разблокировать контакт?":"Заблокировать контакт?")
          .setMessage(was?"Разрешить снова общаться с контактом?":"Контакт не сможет просматривать новые истории и обращаться к вам.")
          .setNegativeButton("Отмена",null).setPositiveButton(was?"Разблокировать":"Заблокировать",(d,w)->backgroundWork(()->{
              api.rpc("mw_contact_set_block",new JSONObject().put("p_chat",profileChatId).put("p_block",!was));reload();
          })).show();
    }
    private void chooseStoryFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp","video/mp4","video/webm"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(i,PICK_STORY);}catch(Exception e){error(e);}
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req!=PICK_STORY||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri source=data.getData();
        TextView state=text("Подготавливаем зашифрованную публикацию…",15,white());state.setPadding(dp(24),dp(18),dp(24),dp(18));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Публикация истории").setView(state).setCancelable(false).create();dialog.show();
        io.execute(()->{try{publish(source,state);ui.post(()->{dialog.dismiss();if(!destroyed){Toast.makeText(this,"История опубликована для разрешённых контактов",Toast.LENGTH_LONG).show();reload();}});}
            catch(Exception e){ui.post(dialog::dismiss);error(e);}
        });
    }
    private byte[] readUriBounded(Uri uri) throws Exception{
        try(InputStream in=getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("Файл недоступен");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("Размер истории больше 25 МБ");out.write(buf,0,n);}
            if(out.size()<4)throw new IOException("Пустой файл");return out.toByteArray();
        }
    }
    private int mediaDuration(Uri uri) throws Exception {
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try {r.setDataSource(this,uri);String val=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(val==null)throw new IOException("Не удалось определить длительность видео");
            long millis=Long.parseLong(val);if(millis<1||millis>60000)throw new IOException("Видео должно быть не длиннее 60 секунд");
            return (int)Math.max(1,(millis+999)/1000);
        } finally{r.release();}
    }
    private void publish(Uri source,TextView status) throws Exception{
        String mime=getContentResolver().getType(source);
        if(mime==null)throw new IOException("Тип файла неизвестен");
        String kind=mime.startsWith("image/")?"image":mime.startsWith("video/")?"video":"";
        if(kind.isEmpty()||!Arrays.asList("image/jpeg","image/png","image/webp","video/mp4","video/webm").contains(mime))throw new IOException("Выберите фото или MP4/WebM");
        byte[] raw=readUriBounded(source);
        int seconds="video".equals(kind)?mediaDuration(source):0;
        if("image".equals(kind)){
            BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(raw,0,raw.length,b);
            if(b.outWidth<1||b.outHeight<1||b.outWidth>4096||b.outHeight>4096)throw new IOException("Фото должно быть не более 4096×4096");
        }
        JSONArray chats=(JSONArray)api.rpc("mw_chat_list",new JSONObject());
        String uid=api.uid();
        byte[] mediaKey=StoryMediaCodec.newKey();
        String encoded=android.util.Base64.encodeToString(mediaKey,android.util.Base64.NO_WRAP);
        String ownPublic=crypto.publicKey(uid);
        crypto.verifyPeer(uid,"story:self",ownPublic);
        String myEnvelope=crypto.encryptText(uid,"story:self",ownPublic,encoded);
        JSONArray grants=new JSONArray();
        Set<String> added=new HashSet<>();
        for(int i=0;i<chats.length()&&grants.length()<200;i++){
            JSONObject c=chats.optJSONObject(i);if(c==null)continue;
            String chat=c.optString("id",""),person=c.optString("person_id","");
            if(chat.isEmpty()||person.isEmpty()||!added.add(person))continue;
            Object key=api.rpc("mw_e2ee_peer_key",new JSONObject().put("p_chat",chat));
            String pub=key instanceof String?(String)key:"";
            if(pub.isEmpty()||!crypto.isPeerVerified(uid,chat,pub))continue;
            String wrapped=crypto.encryptText(uid,chat,pub,encoded);
            grants.put(new JSONObject().put("viewer_id",person).put("chat_id",chat).put("key_envelope",wrapped));
        }
        if(grants.length()==0)throw new IOException("Нет подтверждённых контактов. Сначала сверьте код безопасности в личном чате.");
        ui.post(()->status.setText("Шифруем фото или видео на телефоне…"));
        byte[] cipher=StoryMediaCodec.encrypt(raw,mediaKey);
        Arrays.fill(raw,(byte)0);Arrays.fill(mediaKey,(byte)0);
        String storage=uid+"/"+UUID.randomUUID()+".bin";
        ui.post(()->status.setText("Загружаем зашифрованную историю…"));
        boolean uploaded=false;
        try{
            api.uploadStory(storage,cipher);uploaded=true;
            ui.post(()->status.setText("Открываем доступ "+grants.length()+" проверенным контактам…"));
            JSONObject args=new JSONObject().put("p_path",storage).put("p_kind",kind).put("p_mime",mime)
                .put("p_encrypted_size",cipher.length).put("p_own_key_envelope",myEnvelope)
                .put("p_grants",grants).put("p_duration_seconds","video".equals(kind)?seconds:JSONObject.NULL)
                .put("p_allow_replies",true).put("p_allow_reactions",true);
            api.rpc("mw_story_publish",args);
        }catch(Exception e){if(uploaded)try{api.deleteStory(storage);}catch(Exception ignored){}throw e;}
        finally{Arrays.fill(cipher,(byte)0);}
    }
    private void openStory(int idx){
        if(idx<0||idx>=feed.length())return;
        viewing=true;storyIndex=idx;renderViewer();
    }
    private void renderViewer(){
        stopPlayback();viewing=true;paused=false;
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);setContentView(frame);
        mediaLayer=new FrameLayout(this);frame.addView(mediaLayer,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=horizontal();top.setPadding(dp(9),dp(10),dp(9),dp(5));
        TextView back=button("‹");top.addView(back);back.setOnClickListener(v->renderFeed());
        storyProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        storyProgress.setMax(1000);storyProgress.setProgress(0);
        LinearLayout.LayoutParams pr=new LinearLayout.LayoutParams(0,dp(7),1);pr.setMargins(dp(9),0,dp(9),0);top.addView(storyProgress,pr);
        TextView sound=button(muted?"🔇":"🔊");top.addView(sound);sound.setOnClickListener(v->{muted=!muted;sound.setText(muted?"🔇":"🔊");if(videoPlayer!=null)videoPlayer.setVolume(muted?0f:1f,muted?0f:1f);});
        TextView menu=button("⋮");top.addView(menu);menu.setOnClickListener(v->storyOptions());
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,dp(63),Gravity.TOP);frame.addView(top,tp);
        caption=text("Загрузка истории…",14,white());caption.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,dp(66),Gravity.TOP);cp.topMargin=dp(66);frame.addView(caption,cp);
        viewerStatus=text("Расшифровываем…",14,white());viewerStatus.setGravity(Gravity.CENTER);mediaLayer.addView(viewerStatus,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bottom=horizontal();bottom.setPadding(dp(9),dp(8),dp(9),dp(14));
        replyInput=new EditText(this);replyInput.setSingleLine(true);replyInput.setHint("Ответить на историю…");
        replyInput.setHintTextColor(mutedColor());replyInput.setTextColor(Color.WHITE);replyInput.setTextSize(14);
        replyInput.setBackground(rounded(Color.rgb(33,43,58),23));replyInput.setPadding(dp(12),dp(8),dp(12),dp(8));
        bottom.addView(replyInput,new LinearLayout.LayoutParams(0,dp(47),1));
        TextView send=button("➤");bottom.addView(send);send.setOnClickListener(v->sendStoryReply());
        TextView heart=button("❤");bottom.addView(heart);heart.setOnClickListener(v->chooseReaction());
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,dp(69),Gravity.BOTTOM);frame.addView(bottom,bp);
        showCurrent();
    }
    private void showCurrent(){
        if(!viewing||storyIndex<0||storyIndex>=feed.length()){renderFeed();return;}
        JSONObject item=feed.optJSONObject(storyIndex);if(item==null){renderFeed();return;}
        caption.setText(item.optString("display_name","Контакт")+" · "+(storyIndex+1)+"/"+feed.length());
        boolean own=api.uid().equals(item.optString("owner_id"));
        replyInput.setVisibility(own||!item.optBoolean("allow_replies",true)?View.GONE:View.VISIBLE);
        viewerStatus.setText("Расшифровываем историю…");
        final String storyId=item.optString("id","");
        backgroundWork(()->{
            byte[] raw=readDecryptedMedia(item);
            ui.post(()->{if(destroyed||!viewing||(storyIndex>=feed.length()||feed.optJSONObject(storyIndex)==null||!storyId.equals(feed.optJSONObject(storyIndex).optString("id"))))return;
                if(decryptedMedia!=null)Arrays.fill(decryptedMedia,(byte)0);
                decryptedMedia=raw;displayMedia(item,raw);
            });
            if(!own)api.rpc("mw_story_mark_viewed",new JSONObject().put("p_story",storyId));
        });
    }
    private byte[] readDecryptedMedia(JSONObject item) throws Exception{
        byte[] ciphertext=api.downloadStory(item.getString("media_path"));
        String owner=item.getString("owner_id"),uid=api.uid(),chat=item.optString("chat_id","");
        String otherKey,scope;
        if(owner.equals(uid)){otherKey=crypto.publicKey(uid);scope="story:self";crypto.verifyPeer(uid,scope,otherKey);}
        else{
            if(chat.isEmpty())throw new SecurityException("Отсутствует чат с автором истории");
            Object pub=api.rpc("mw_e2ee_peer_key",new JSONObject().put("p_chat",chat));
            otherKey=pub instanceof String?(String)pub:"";
            if(otherKey.isEmpty()||!crypto.isPeerVerified(uid,chat,otherKey))
                throw new SecurityException("Проверьте ключ собеседника, чтобы открыть историю");
            scope=chat;
        }
        String encoded=crypto.decryptText(uid,scope,otherKey,item.getString("key_envelope"));
        byte[] key=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);
        try{return StoryMediaCodec.decrypt(ciphertext,key);}
        finally{Arrays.fill(key,(byte)0);Arrays.fill(ciphertext,(byte)0);}
    }
    private void displayMedia(JSONObject item,byte[] bytes){
        mediaLayer.removeAllViews();
        if("video".equals(item.optString("media_kind"))){playVideo(bytes);return;}
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<1||options.outHeight<1||options.outWidth>4096||options.outHeight>4096){
            viewerStatus.setText("Некорректное изображение");mediaLayer.addView(viewerStatus);return;}
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        if(bitmap==null){viewerStatus.setText("Не удалось открыть фото");mediaLayer.addView(viewerStatus);return;}
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);mediaLayer.addView(image,new FrameLayout.LayoutParams(-1,-1));
        installTouchNav(image);
        paused=false;imageRemaining=7500;imageEndsAt=SystemClock.uptimeMillis()+imageRemaining;
        imageAdvance=()->{if(viewing&&!paused)next();};ui.postDelayed(imageAdvance,imageRemaining);
        tickProgress();
    }
    private void playVideo(byte[] raw){
        SurfaceView surface=new SurfaceView(this);mediaLayer.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        installTouchNav(surface);
        videoPlayer=new MediaPlayer();
        final byte[] data=raw;
        try{
            videoPlayer.setDataSource(new MediaDataSource(){
                @Override public int readAt(long at,byte[] b,int offset,int size){
                    if(at<0||at>=data.length)return -1;int n=(int)Math.min(size,data.length-at);System.arraycopy(data,(int)at,b,offset,n);return n;
                }
                @Override public long getSize(){return data.length;}
                @Override public void close(){}
            });
            videoPlayer.setOnPreparedListener(mp->{mp.setVolume(muted?0f:1f,muted?0f:1f);mp.start();tickProgress();});
            videoPlayer.setOnCompletionListener(mp->next());
            videoPlayer.setOnErrorListener((mp,what,extra)->{viewerStatus.setText("Не удалось воспроизвести видео");if(viewerStatus.getParent()==null)mediaLayer.addView(viewerStatus,new FrameLayout.LayoutParams(-1,-1));return true;});
            surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback(){
                public void surfaceCreated(android.view.SurfaceHolder h){if(videoPlayer!=null)videoPlayer.setDisplay(h);}
                public void surfaceChanged(android.view.SurfaceHolder h,int format,int w,int height){}
                public void surfaceDestroyed(android.view.SurfaceHolder h){if(videoPlayer!=null)videoPlayer.setDisplay(null);}
            });
            videoPlayer.prepareAsync();
        }catch(Exception e){viewerStatus.setText("Ошибка видео: "+e.getMessage());mediaLayer.addView(viewerStatus);}
    }
    private void installTouchNav(View view){
        view.setOnTouchListener(new View.OnTouchListener(){
            long down=0;boolean held=false;
            @Override public boolean onTouch(View v,android.view.MotionEvent event){
                if(event.getAction()==MotionEvent.ACTION_DOWN){down=SystemClock.uptimeMillis();held=false;
                    pauseStory();return true;}
                if(event.getAction()==MotionEvent.ACTION_UP){
                    held=SystemClock.uptimeMillis()-down>250;
                    if(held){resumeStory();return true;}
                    float x=event.getX()/Math.max(1,view.getWidth());
                    if(x<.33f)previous();else next();return true;
                }
                if(event.getAction()==MotionEvent.ACTION_CANCEL){resumeStory();return true;}
                return true;
            }
        });
    }
    private void tickProgress(){
        ui.removeCallbacks(progressTicker);
        ui.post(progressTicker);
    }
    private void pauseStory(){
        if(paused)return;paused=true;
        if(imageEndsAt>0){imageRemaining=Math.max(0,imageEndsAt-SystemClock.uptimeMillis());if(imageAdvance!=null)ui.removeCallbacks(imageAdvance);}
        if(videoPlayer!=null)try{videoPlayer.pause();}catch(Exception ignored){}
    }
    private void resumeStory(){
        if(!paused)return;paused=false;
        if(imageEndsAt>0){imageEndsAt=SystemClock.uptimeMillis()+imageRemaining;if(imageAdvance!=null)ui.postDelayed(imageAdvance,imageRemaining);}
        if(videoPlayer!=null)try{videoPlayer.start();}catch(Exception ignored){}
    }
    private void next(){if(storyIndex+1<feed.length()){storyIndex++;renderViewer();}else renderFeed();}
    private void previous(){if(storyIndex>0){storyIndex--;renderViewer();}else renderViewer();}
    private void stopPlayback(){
        ui.removeCallbacks(progressTicker);
        if(imageAdvance!=null)ui.removeCallbacks(imageAdvance);imageAdvance=null;imageEndsAt=0;
        if(videoPlayer!=null){try{videoPlayer.stop();}catch(Exception ignored){}videoPlayer.release();videoPlayer=null;}
        if(decryptedMedia!=null){Arrays.fill(decryptedMedia,(byte)0);decryptedMedia=null;}
    }
    private void storyOptions(){
        JSONObject item=feed.optJSONObject(storyIndex);if(item==null)return;
        boolean own=item.optString("owner_id").equals(api.uid());
        String[] options=own
            ?new String[]{"Кто просмотрел",item.optBoolean("visible_on_profile",false)?"Убрать из профиля":"Оставить в профиле","Удалить историю","Скорость видео"}
            :new String[]{"Проверить данные автора","Ответить","Поставить реакцию","Скорость видео"};
        new AlertDialog.Builder(this).setTitle("История").setItems(options,(d,n)->{
            if(own){
                if(n==0)showStoryViewers(item);
                else if(n==1)setPinned(item,!item.optBoolean("visible_on_profile",false));
                else if(n==2)confirmDelete(item);
                else showSpeedDialog();
            }else{
                if(n==0)renderFeed();
                else if(n==1){replyInput.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(replyInput,0);}
                else if(n==2)chooseReaction();
                else showSpeedDialog();
            }
        }).show();
    }
    private void showSpeedDialog(){
        if(videoPlayer==null){Toast.makeText(this,"Скорость доступна для видеоисторий",Toast.LENGTH_SHORT).show();return;}
        final float[] rates={.5f,1f,1.5f,2f};
        final String[] names={"0,5×","1× · обычно","1,5×","2×"};
        new AlertDialog.Builder(this).setTitle("Скорость воспроизведения")
            .setItems(names,(d,w)->{
                try{videoPlayer.setPlaybackParams(videoPlayer.getPlaybackParams().setSpeed(rates[w]));}
                catch(Exception e){error(e);}
            }).show();
    }
    private void setPinned(JSONObject story,boolean pinned){
        backgroundWork(()->{
            api.rpc("mw_story_set_profile",new JSONObject().put("p_story",story.getString("id")).put("p_visible",pinned));
            ui.post(()->{if(!destroyed){Toast.makeText(this,pinned?"История сохранена в профиле":"История убрана из профиля",Toast.LENGTH_SHORT).show();reload();}});
        });
    }
    private void showStoryViewers(JSONObject story){
        backgroundWork(()->{
            JSONArray viewers=(JSONArray)api.rpc("mw_story_viewers",new JSONObject().put("p_story",story.getString("id")));
            StringBuilder content=new StringBuilder();
            for(int i=0;i<viewers.length();i++){
                JSONObject item=viewers.getJSONObject(i);
                String reaction=item.optString("encrypted_reaction","");
                String symbol="";
                if(!reaction.isEmpty())try{
                    String chat=item.getString("chat_id"),uid=api.uid();
                    Object val=api.rpc("mw_e2ee_peer_key",new JSONObject().put("p_chat",chat));
                    String peer=val instanceof String?(String)val:"";
                    if(!peer.isEmpty())symbol=" "+crypto.decryptText(uid,chat,peer,reaction);
                }catch(Exception ignored){}
                content.append(item.optString("display_name","Контакт")).append(symbol).append("\n");
            }
            if(content.length()==0)content.append("Пока нет просмотров и реакций");
            String value=content.toString();ui.post(()->{if(!destroyed)new AlertDialog.Builder(this).setTitle("Просмотры и реакции").setMessage(value).setPositiveButton("Закрыть",null).show();});
        });
    }
    private void confirmDelete(JSONObject story){
        new AlertDialog.Builder(this).setTitle("Удалить историю?").setMessage("Публикация исчезнет для ваших контактов.")
            .setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->backgroundWork(()->{
                String object=String.valueOf(api.rpc("mw_story_delete",new JSONObject().put("p_story",story.getString("id"))));
                try{api.deleteStory(object);}catch(Exception ignored){}
                ui.post(()->{if(!destroyed){renderFeed();reload();}});
            })).show();
    }
    private void sendStoryReply(){
        JSONObject item=feed.optJSONObject(storyIndex);if(item==null||replyInput==null)return;
        String message=replyInput.getText().toString().trim();if(message.isEmpty())return;
        String chat=item.optString("chat_id","");
        if(chat.isEmpty()){Toast.makeText(this,"Ответить можно только контакту",Toast.LENGTH_SHORT).show();return;}
        replyInput.setEnabled(false);
        backgroundWork(()->{
            String uid=api.uid();
            Object val=api.rpc("mw_e2ee_peer_key",new JSONObject().put("p_chat",chat));
            String peer=val instanceof String?(String)val:"";
            if(peer.isEmpty()||!crypto.isPeerVerified(uid,chat,peer))throw new SecurityException("Сначала подтвердите ключ собеседника");
            String encrypted=crypto.encryptText(uid,chat,peer,"↪ Ответ на историю: "+message);
            api.rpc("mw_send",new JSONObject().put("p_chat",chat).put("p_body",encrypted).put("p_nonce",UUID.randomUUID().toString()));
            ui.post(()->{if(!destroyed){replyInput.setEnabled(true);replyInput.setText("");Toast.makeText(this,"Ответ отправлен в защищённый чат",Toast.LENGTH_SHORT).show();}});
        });
    }
    private void chooseReaction(){
        JSONObject item=feed.optJSONObject(storyIndex);if(item==null)return;
        if(api.uid().equals(item.optString("owner_id"))||!item.optBoolean("allow_reactions",true))return;
        String[] emoji={"❤️","👍","👎","🔥","🥰","👏","😁","🤔","😮","😢","🎉","😍","😂","😎","😱","🙏","💯","💋","✨","🤩","💐","🥳","😇","👀"};
        android.widget.GridLayout grid=new android.widget.GridLayout(this);grid.setColumnCount(6);
        grid.setPadding(dp(10),dp(10),dp(10),dp(10));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Реакция на историю")
            .setView(grid).setNegativeButton("Закрыть",null).create();
        for(String e:emoji){
            TextView cell=text(e,26,white());cell.setGravity(Gravity.CENTER);
            android.widget.GridLayout.LayoutParams p=new android.widget.GridLayout.LayoutParams();
            p.width=dp(49);p.height=dp(49);p.setMargins(dp(1),dp(1),dp(1),dp(1));
            grid.addView(cell,p);cell.setOnClickListener(v->{dialog.dismiss();sendReaction(item,e);});
        }
        dialog.show();
    }
    private void sendReaction(JSONObject story,String emoji){
        backgroundWork(()->{
            String chat=story.optString("chat_id",""),uid=api.uid();
            if(chat.isEmpty())throw new SecurityException("Сначала откройте личный чат");
            Object obj=api.rpc("mw_e2ee_peer_key",new JSONObject().put("p_chat",chat));
            String peer=obj instanceof String?(String)obj:"";
            if(peer.isEmpty()||!crypto.isPeerVerified(uid,chat,peer))throw new SecurityException("Сверьте ключ безопасности контакта");
            String encrypted=crypto.encryptText(uid,chat,peer,emoji);
            api.rpc("mw_story_react",new JSONObject().put("p_story",story.getString("id")).put("p_ciphertext",encrypted));
            ui.post(()->{if(!destroyed)Toast.makeText(this,"Реакция отправлена "+emoji,Toast.LENGTH_SHORT).show();});
        });
    }
    @Override public void onBackPressed(){if(viewing)renderFeed();else super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;viewing=false;stopPlayback();io.shutdownNow();thumbnailWork.shutdownNow();thumbnailCache.evictAll();super.onDestroy();}
}