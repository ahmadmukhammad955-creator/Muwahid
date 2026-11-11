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
    private static final int ARCHIVE_PAGE_SIZE=50;
    private boolean archiveMode=false,archiveHasMore=false,archiveLoading=false;
    private boolean archiveScreen=false;
    private int archiveOffset=0,archiveSavedScroll=-1,archiveRequestSerial=0;
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
    private TextView sendAction,reactionAction;
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
    private boolean checkingStoryAudience=false;

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
        archiveScreen=profileChatId.isEmpty();
        getWindow().setStatusBarColor(Color.rgb(8,16,29));
        getWindow().setNavigationBarColor(Color.rgb(8,16,29));
        renderFeed();
        reload();
    }
    private int dp(int v){return Math.round(getResources().getDisplayMetrics().density*v);}
    private int white(){return Color.WHITE;}
    private int mutedColor(){return Color.rgb(151,170,191);}
    private int surfaceColor(){return Color.rgb(17,33,51);}
    private int accentColor(){return Color.rgb(29,187,157);}
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
        if(archiveMode){loadArchive(false);return;}
        backgroundWork(()->{
            JSONObject profile=new JSONObject();
            if(!archiveScreen&&!profileChatId.isEmpty()){
                profile=(JSONObject)api.rpc("mw_contact_profile",new JSONObject().put("p_chat",profileChatId));
            }
            String targetPerson=archiveScreen?api.uid():(personId.isEmpty()?profile.optString("id",""):personId);
            JSONObject args=new JSONObject();
            args.put("p_person",targetPerson.isEmpty()?JSONObject.NULL:targetPerson);
            JSONArray entries=(JSONArray)api.rpc("mw_story_feed",args);
            final JSONObject target=profile;
            ui.post(()->{if(destroyed||archiveMode)return;contact=target;feed=entries;
                if(personId.isEmpty()&&!archiveScreen)personId=targetPerson;
                renderFeed();});
        });
    }
    private void loadArchive(boolean append){
        if(!archiveMode||archiveLoading)return;
        archiveLoading=true;
        final int offset=append?archiveOffset:0;
        final int request=++archiveRequestSerial;
        io.execute(()->{
            try{
                JSONArray entries=(JSONArray)api.rpc("mw_story_archive",
                    new JSONObject().put("p_offset",offset).put("p_limit",ARCHIVE_PAGE_SIZE));
                ui.post(()->{
                    if(destroyed||!archiveMode||request!=archiveRequestSerial)return;
                    archiveLoading=false;
                    if(!append)feed=entries;
                    else for(int i=0;i<entries.length();i++)feed.put(entries.optJSONObject(i));
                    archiveOffset=offset+entries.length();
                    archiveHasMore=entries.length()==ARCHIVE_PAGE_SIZE;
                    renderFeed();
                });
            }catch(Exception e){
                ui.post(()->{if(request==archiveRequestSerial){archiveLoading=false;if(!destroyed&&archiveMode)renderFeed();}});
                error(e);
            }
        });
    }
    private void applySafeInsets(FrameLayout root){
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0,bars.top,0,bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }else getWindow().getDecorView().setSystemUiVisibility(0);
    }
    private TextView toolbarIcon(String glyph,String description){
        TextView v=text(glyph,23,white());v.setGravity(Gravity.CENTER);
        v.setContentDescription(description);v.setBackground(rounded(surfaceColor(),14));
        return v;
    }
    private void setRoot(){
        viewing=false;
        stopPlayback();
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.rgb(8,17,30));
        setContentView(frame);applySafeInsets(frame);
    }

    // New native visual layout based on the approved Muwahid profile/archive reference.
    // This screen never manufactures avatars, story media, view counts or contact details.
    private static final int BG=0xFF09111B, PANEL=0xFF1B2633, FG=0xFFF7FAFF;
    private static final int SECONDARY=0xFFB2BECF, BLUE=0xFF1D95D0;

    private TextView visualText(String value,int sp,int color,boolean bold){
        TextView t=text(value,sp,color);
        if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }
    private void openArchiveScreen(){
        archiveScreen=true; archiveMode=false; archiveOffset=0;archiveSavedScroll=-1;
        archiveRequestSerial++;archiveLoading=false;archiveHasMore=false;
        feed=new JSONArray();renderFeed();reload();
    }
    private void selectArchiveTab(boolean archive){
        if(archiveMode==archive)return;
        archiveMode=archive;archiveRequestSerial++;archiveLoading=false;
        archiveOffset=0;archiveHasMore=false;archiveSavedScroll=-1;
        feed=new JSONArray();renderFeed();reload();
    }
    private void returnFromScreen(){
        if(archiveScreen&&!profileChatId.isEmpty()){
            archiveScreen=false;archiveMode=false;feed=new JSONArray();renderFeed();reload();
        }else finish();
    }
    private TextView simpleIcon(String value,String hint){
        TextView t=visualText(value,26,FG,false);
        t.setGravity(Gravity.CENTER);t.setContentDescription(hint);
        return t;
    }
    private void openProfileMenu(View anchor){
        PopupMenu popup=new PopupMenu(this,anchor);
        popup.getMenu().add(0,1,0,"Обновить");
        if(!profileChatId.isEmpty()){
            popup.getMenu().add(0,2,1,"Поделиться");
            if(!api.uid().equals(contact.optString("id",""))){
                popup.getMenu().add(0,3,2,contact.optBoolean("blocked_by_me",false)?"Разблокировать":"Заблокировать");
            }
        }
        popup.setOnMenuItemClickListener(item->{
            if(item.getItemId()==1){reload();return true;}
            if(item.getItemId()==2){shareContact();return true;}
            if(item.getItemId()==3){confirmBlock();return true;}
            return false;
        });
        popup.show();
    }
    private void renderFeed(){
        if(destroyed)return;
        setRoot();
        frame.setBackgroundColor(BG);
        LinearLayout page=vertical();
        frame.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=horizontal();
        bar.setPadding(dp(13),dp(7),dp(13),dp(7));
        TextView back=simpleIcon("‹","Назад");
        bar.addView(back,new LinearLayout.LayoutParams(dp(44),dp(46)));
        back.setOnClickListener(v->returnFromScreen());
        TextView title=visualText(archiveScreen?"Архив историй":"Профиль",21,FG,true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleP=new LinearLayout.LayoutParams(0,dp(46),1);
        titleP.leftMargin=dp(8);bar.addView(title,titleP);
        TextView more=simpleIcon("⋮","Меню");
        bar.addView(more,new LinearLayout.LayoutParams(dp(42),dp(46)));
        more.setOnClickListener(this::openProfileMenu);
        page.addView(bar);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout body=vertical();
        body.setPadding(dp(14),dp(7),dp(14),dp(28));
        scroll.addView(body);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(archiveScreen)renderArchive(body,scroll);
        else renderContact(body);
    }
    private void renderContact(LinearLayout body){
        if(contact.length()==0){
            TextView loading=visualText("Загрузка профиля…",15,SECONDARY,false);
            loading.setPadding(dp(14),dp(24),dp(14),dp(24));body.addView(loading);
            return;
        }
        String displayName=contact.optString("display_name",personName);
        if(displayName==null||displayName.trim().isEmpty())displayName="Контакт";
        final String name=displayName;
        final boolean self=api.uid().equals(contact.optString("id",""));
        FrameLayout hero=new FrameLayout(this);
        hero.setBackground(rounded(PANEL,21));hero.setClipToOutline(true);
        View skyline=new View(this){
            private final Paint paint=new Paint(3);
            @Override protected void onDraw(Canvas c){
                int w=getWidth(),h=getHeight();
                paint.setShader(new LinearGradient(0,0,0,h,
                    new int[]{0xFF15283D,0xFF594657,0xFFBF7562,0xFF111E2B},null,Shader.TileMode.CLAMP));
                c.drawRect(0,0,w,h,paint);paint.setShader(null);
                paint.setColor(0xCDE8E2D8);
                c.drawCircle(w*.83f,h*.24f,dp(10),paint);
                paint.setColor(0xFF101A28);
                c.drawRect(0,h*.84f,w,h,paint);
                float center=w*.48f,ground=h*.87f;
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(center-dp(63),ground-dp(26),center+dp(63),ground,paint);
                c.drawOval(center-dp(56),ground-dp(75),center+dp(56),ground-dp(6),paint);
                for(int side=-1;side<=1;side+=2){
                    float x=center+side*dp(112);
                    c.drawRect(x-dp(6),ground-dp(108),x+dp(6),ground,paint);
                    Path cone=new Path();cone.moveTo(x-dp(7),ground-dp(108));cone.lineTo(x,ground-dp(151));
                    cone.lineTo(x+dp(7),ground-dp(108));cone.close();c.drawPath(cone,paint);
                }
                paint.setColor(0x50101821);c.drawRect(0,h*.81f,w,h,paint);
            }
        };
        FrameLayout.LayoutParams skyP=new FrameLayout.LayoutParams(-1,dp(171),Gravity.TOP);
        hero.addView(skyline,skyP);
        TextView avatar=visualText(name.substring(0,1).toUpperCase(Locale.ROOT),38,FG,true);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
           new int[]{0xFF155176,0xFF128C86});
        circle.setShape(GradientDrawable.OVAL);
        circle.setStroke(dp(3),FG);
        avatar.setBackground(circle);
        FrameLayout.LayoutParams avatarP=new FrameLayout.LayoutParams(dp(102),dp(102),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        avatarP.bottomMargin=dp(7);hero.addView(avatar,avatarP);
        body.addView(hero,new LinearLayout.LayoutParams(-1,dp(233)));

        TextView display=visualText(name,25,FG,true);
        display.setGravity(Gravity.CENTER);display.setSingleLine(true);
        display.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams displayP=new LinearLayout.LayoutParams(-1,-2);
        displayP.topMargin=dp(8);body.addView(display,displayP);
        String username=contact.optString("username","");
        if(!username.isEmpty()){
            TextView handle=visualText("@"+username,16,SECONDARY,false);
            handle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);
            hp.topMargin=dp(3);body.addView(handle,hp);
        }
        if(contact.has("online")&&contact.optBoolean("online",false)){
            TextView online=visualText("●  Онлайн",13,0xFF27D486,false);
            online.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);
            sp.topMargin=dp(8);body.addView(online,sp);
        }
        LinearLayout actions=horizontal();
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(73));
        ap.topMargin=dp(22);body.addView(actions,ap);
        addQuickAction(actions,"chat","Чат",()->finish(),true);
        addQuickAction(actions,"call","Звонок",()->startContactCall("audio"),!self);
        addQuickAction(actions,"video","Видео",()->startContactCall("video"),!self);
        addQuickAction(actions,"share","Поделиться",this::shareContact,true);

        LinearLayout heading=horizontal();
        LinearLayout.LayoutParams headingP=new LinearLayout.LayoutParams(-1,dp(63));
        headingP.topMargin=dp(8);body.addView(heading,headingP);
        TextView storiesLabel=visualText("Истории",20,FG,true);
        heading.addView(storiesLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView archiveLink=visualText("◷  Архив",16,0xFFCFD9E6,false);
        archiveLink.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        heading.addView(archiveLink,new LinearLayout.LayoutParams(dp(96),dp(44)));
        archiveLink.setOnClickListener(v->openArchiveScreen());
        renderStoryStrip(body);
        String bio=contact.optString("bio","");
        LinearLayout details=vertical();details.setBackground(rounded(0xFF131E29,18));
        LinearLayout.LayoutParams detailsP=new LinearLayout.LayoutParams(-1,-2);
        detailsP.topMargin=dp(21);body.addView(details,detailsP);
        if(!bio.isEmpty()){
            addInfoRow(details,"♙","О себе",bio,()->new AlertDialog.Builder(this)
              .setTitle("О себе").setMessage(bio).setPositiveButton("Закрыть",null).show());
        }
        // Show a real action only where the existing backend provides one.
        if(!self)addInfoRow(details,"♢","Конфиденциальность","",this::confirmBlock);
    }
    private void startContactCall(String kind){
        Intent result=new Intent();result.putExtra("contact_call_kind",kind);
        setResult(RESULT_OK,result);finish();
    }
    private void addQuickAction(LinearLayout parent,String symbol,String label,Runnable action,boolean enabled){
        LinearLayout tile=vertical();tile.setGravity(Gravity.CENTER);
        tile.setBackground(rounded(PANEL,15));
        tile.setAlpha(enabled?1f:.35f);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(71),1);
        lp.setMargins(dp(3),0,dp(3),0);parent.addView(tile,lp);
        ImageView icon=new ImageView(this);
        int image="call".equals(symbol)?android.R.drawable.ic_menu_call:
                  "video".equals(symbol)?android.R.drawable.ic_menu_camera:
                  "share".equals(symbol)?android.R.drawable.ic_menu_share:
                  android.R.drawable.ic_dialog_email;
        icon.setImageResource(image);
        icon.setColorFilter(FG);
        icon.setPadding(dp(8),dp(4),dp(8),dp(4));
        tile.addView(icon,new LinearLayout.LayoutParams(-1,dp(34)));
        TextView labelText=visualText(label,12,FG,false);
        labelText.setGravity(Gravity.CENTER);
        labelText.setSingleLine(true);labelText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(labelText,new LinearLayout.LayoutParams(-1,dp(23)));
        tile.setContentDescription(label);
        tile.setEnabled(enabled);
        if(enabled)tile.setOnClickListener(v->action.run());
    }
    private void addInfoRow(LinearLayout parent,String symbol,String title,String summary,Runnable action){
        LinearLayout row=horizontal();row.setPadding(dp(12),dp(13),dp(10),dp(13));
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
        TextView icon=visualText(symbol,23,FG,false);icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(PANEL,30));
        row.addView(icon,new LinearLayout.LayoutParams(dp(43),dp(43)));
        LinearLayout labels=vertical();
        LinearLayout.LayoutParams labelsP=new LinearLayout.LayoutParams(0,-2,1);
        labelsP.leftMargin=dp(11);row.addView(labels,labelsP);
        TextView label=visualText(title,16,FG,false);labels.addView(label);
        if(summary!=null&&!summary.isEmpty()){
            TextView sub=visualText(summary,12,SECONDARY,false);
            sub.setMaxLines(1);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(sub);
        }
        TextView chevron=visualText("›",29,SECONDARY,false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron,new LinearLayout.LayoutParams(dp(25),dp(42)));
        row.setOnClickListener(v->action.run());
        View divider=new View(this);divider.setBackgroundColor(0xFF24303D);
        LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(-1,dp(1));
        d.leftMargin=dp(61);parent.addView(divider,d);
    }
    private void renderStoryStrip(LinearLayout body){
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);scroll.setFillViewport(false);
        LinearLayout row=horizontal();row.setPadding(0,dp(2),dp(5),dp(2));
        scroll.addView(row);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(145)));
        FrameLayout add=new FrameLayout(this);
        add.setBackground(rounded(PANEL,16));
        LinearLayout.LayoutParams addP=new LinearLayout.LayoutParams(dp(95),dp(139));
        addP.rightMargin=dp(8);row.addView(add,addP);
        TextView plus=visualText("+",35,FG,false);plus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams plusP=new FrameLayout.LayoutParams(dp(55),dp(55),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        plusP.topMargin=dp(21);plus.setBackground(rounded(0xFF34414E,48));
        add.addView(plus,plusP);
        TextView addLabel=visualText("Добавить\nисторию",13,FG,false);
        addLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelP=new FrameLayout.LayoutParams(-1,dp(50),Gravity.BOTTOM);
        add.addView(addLabel,labelP);
        add.setOnClickListener(v->chooseStoryFile());
        for(int i=0;i<feed.length();i++){
            JSONObject story=feed.optJSONObject(i);if(story==null)continue;
            final int index=i;
            FrameLayout card=buildStoryTile(story,index,95,139);
            LinearLayout.LayoutParams tileP=new LinearLayout.LayoutParams(dp(95),dp(139));
            tileP.rightMargin=dp(8);row.addView(card,tileP);
        }
    }
    private String storyAge(JSONObject story){
        String ends=story.optString("expires_at","");
        if(!ends.isEmpty()&&!archiveMode)try{
            long millis=java.time.Instant.parse(ends).toEpochMilli()-System.currentTimeMillis();
            if(millis>0)return Math.max(1,(millis+3599999L)/3600000L)+" ч";
        }catch(Exception ignored){}
        String created=story.optString("created_at","");
        if(!created.isEmpty())try{
            long age=Math.max(0,System.currentTimeMillis()-java.time.Instant.parse(created).toEpochMilli());
            if(age<86400000L)return Math.max(1,age/3600000L)+" ч";
            return Math.max(1,age/86400000L)+" д";
        }catch(Exception ignored){}
        return "";
    }
    private FrameLayout buildStoryTile(JSONObject story,int index,int widthDp,int heightDp){
        FrameLayout tile=new FrameLayout(this);
        tile.setBackground(rounded(PANEL,14));
        tile.setClipToOutline(true);
        tile.setContentDescription("Открыть историю "+(index+1));
        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFF213141);
        tile.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView fallback=visualText("▧",27,SECONDARY,false);
        fallback.setGravity(Gravity.CENTER);
        tile.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
           new int[]{0x68061119,0x00061119,0x90061119}));
        tile.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        String age=storyAge(story);
        if(!age.isEmpty()){
            TextView elapsed=visualText(age,12,FG,true);
            elapsed.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(-1,dp(26),Gravity.TOP);
            ep.setMargins(dp(5),dp(1),dp(8),0);tile.addView(elapsed,ep);
        }
        Object views=story.opt("view_count");
        if(!(views instanceof Number))views=story.opt("views_count");
        if(views instanceof Number){
            TextView count=visualText("◉ "+views.toString(),12,FG,true);
            FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,dp(24),Gravity.BOTTOM);
            cp.setMargins(dp(8),0,0,dp(4));tile.addView(count,cp);
        }else if("video".equals(story.optString("media_kind"))){
            TextView play=visualText("▶",15,FG,true);
            FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-2,dp(23),Gravity.BOTTOM|Gravity.LEFT);
            pp.setMargins(dp(7),0,0,dp(4));tile.addView(play,pp);
        }
        tile.setOnClickListener(v->openStory(index));
        loadThumbnail(story,image,fallback,dp(widthDp));
        return tile;
    }
    private void renderArchive(LinearLayout body,ScrollView scroll){
        LinearLayout tabs=horizontal();tabs.setPadding(dp(4),dp(4),dp(4),dp(4));
        tabs.setBackground(rounded(PANEL,18));
        body.addView(tabs,new LinearLayout.LayoutParams(-1,dp(58)));
        for(int i=0;i<2;i++){
            final boolean archived=i==1;
            TextView tab=visualText(i==0?"▣  Мои истории":"▢  Архив",15,
                archiveMode==archived?0xFF60D2FF:FG,archiveMode==archived);
            tab.setGravity(Gravity.CENTER);
            if(archiveMode==archived)tab.setBackground(rounded(0xFF104C6D,15));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);
            tabs.addView(tab,p);tab.setOnClickListener(v->selectArchiveTab(archived));
        }
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);
        gp.topMargin=dp(24);
        if(feed.length()==0){
            TextView empty=visualText(archiveLoading?"Загрузка…":"Историй пока нет",15,SECONDARY,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(42),0,dp(40));
            body.addView(empty,gp);
        }else{
            android.widget.GridLayout grid=new android.widget.GridLayout(this);grid.setColumnCount(3);
            int tile=Math.max(dp(80),(getResources().getDisplayMetrics().widthPixels-dp(46))/3);
            for(int i=0;i<feed.length();i++){
                JSONObject item=feed.optJSONObject(i);if(item==null)continue;
                FrameLayout visual=buildStoryTile(item,i,110,174);
                android.widget.GridLayout.LayoutParams p=new android.widget.GridLayout.LayoutParams();
                p.width=tile;p.height=Math.round(tile*1.67f);
                p.setMargins(dp(3),dp(3),dp(3),dp(3));grid.addView(visual,p);
            }
            body.addView(grid,gp);
        }
        if(!archiveMode){
            TextView add=visualText("＋  Добавить историю",15,FG,true);
            add.setGravity(Gravity.CENTER);
            add.setBackground(rounded(0xFF155C7A,14));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(49));
            p.topMargin=dp(20);body.addView(add,p);
            add.setOnClickListener(v->chooseStoryFile());
        }else if(archiveHasMore){
            TextView more=visualText("Показать ещё",15,FG,true);
            more.setGravity(Gravity.CENTER);more.setBackground(rounded(PANEL,14));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(49));
            p.topMargin=dp(18);body.addView(more,p);
            more.setOnClickListener(v->{
                more.setEnabled(false);archiveSavedScroll=scroll.getScrollY();loadArchive(true);
            });
        }
        if(archiveMode&&archiveSavedScroll>=0){
            final int savedY=archiveSavedScroll;archiveSavedScroll=-1;
            scroll.post(()->scroll.scrollTo(0,savedY));
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
        if(checkingStoryAudience)return;
        checkingStoryAudience=true;
        TextView pending=text("Проверяем доступ проверенных контактов…",15,white());
        pending.setPadding(dp(20),dp(18),dp(20),dp(18));
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("Новая история").setView(pending).setCancelable(false).create();
        dialog.show();
        io.execute(()->{
            try{
                JSONArray chats=(JSONArray)api.rpc("mw_chat_list",new JSONObject());
                String uid=api.uid();int verified=0;
                HashSet<String> added=new HashSet<>();
                for(int i=0;i<chats.length()&&verified<200;i++){
                    JSONObject chat=chats.optJSONObject(i);
                    if(chat==null)continue;
                    String id=chat.optString("id","");
                    String person=chat.optString("person_id","");
                    if(id.isEmpty()||person.isEmpty()||!added.add(person))continue;
                    Object key=api.rpc("mw_e2ee_peer_key",new JSONObject().put("p_chat",id));
                    String publicKey=key instanceof String?(String)key:"";
                    if(!publicKey.isEmpty()&&crypto.isPeerVerified(uid,id,publicKey))verified++;
                }
                final int count=verified;
                ui.post(()->{
                    checkingStoryAudience=false;
                    dialog.dismiss();
                    if(destroyed||isFinishing())return;
                    if(count==0){
                        new AlertDialog.Builder(this)
                            .setTitle("Пока нет получателей")
                            .setMessage("Для защищённой публикации нужен хотя бы один контакт с подтверждённым ключом. Откройте личный чат, сверьте код безопасности с собеседником и подтвердите ключ. После этого можно опубликовать историю.")
                            .setNegativeButton("Закрыть",null)
                            .setPositiveButton("Вернуться в приложение",(d,w)->finish())
                            .show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                        .setTitle("Кто увидит историю")
                        .setMessage("Получатели: "+count+" проверенных контактов. Фото или видео будет зашифровано на телефоне и станет недоступно контактам через 24 часа. Вы сможете открыть его в личном архиве.")
                        .setNegativeButton("Отмена",null)
                        .setPositiveButton("Выбрать файл",(d,w)->openStoryPicker())
                        .show();
                });
            }catch(Exception err){
                ui.post(()->{checkingStoryAudience=false;dialog.dismiss();});
                error(err);
            }
        });
    }
    private void openStoryPicker(){
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
        frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
        setContentView(frame);applySafeInsets(frame);
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
        sendAction=button("➤");bottom.addView(sendAction);sendAction.setOnClickListener(v->sendStoryReply());
        reactionAction=button("❤");bottom.addView(reactionAction);reactionAction.setOnClickListener(v->chooseReaction());
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,dp(69),Gravity.BOTTOM);frame.addView(bottom,bp);
        showCurrent();
    }
    private void showCurrent(){
        if(!viewing||storyIndex<0||storyIndex>=feed.length()){renderFeed();return;}
        JSONObject item=feed.optJSONObject(storyIndex);if(item==null){renderFeed();return;}
        caption.setText(item.optString("display_name","Контакт")+" · "+(storyIndex+1)+"/"+feed.length());
        boolean own=api.uid().equals(item.optString("owner_id"));
        boolean mayReply=!own&&item.optBoolean("allow_replies",true);
        replyInput.setVisibility(mayReply?View.VISIBLE:View.GONE);
        sendAction.setVisibility(mayReply?View.VISIBLE:View.GONE);
        reactionAction.setVisibility(!own&&item.optBoolean("allow_reactions",true)?View.VISIBLE:View.GONE);
        viewerStatus.setText("Расшифровываем историю…");
        viewerStatus.setOnClickListener(null);
        final String storyId=item.optString("id","");
        backgroundWork(()->{
            byte[] raw;
            try{raw=readDecryptedMedia(item);}
            catch(Exception err){
                ui.post(()->{
                    if(destroyed||!viewing||storyIndex>=feed.length()||
                        !storyId.equals(feed.optJSONObject(storyIndex).optString("id")))return;
                    viewerStatus.setText(err instanceof SecurityException
                        ?"Ключ контакта не подтверждён. Проверьте его в чате."
                        :"Не удалось открыть историю. Нажмите, чтобы повторить.");
                    viewerStatus.setOnClickListener(v->showCurrent());
                });
                throw err;
            }
            ui.post(()->{
                if(destroyed||!viewing||storyIndex>=feed.length()||
                    !storyId.equals(feed.optJSONObject(storyIndex).optString("id"))){
                    Arrays.fill(raw,(byte)0);return;
                }
                if(decryptedMedia!=null)Arrays.fill(decryptedMedia,(byte)0);
                decryptedMedia=raw;displayMedia(item,raw);
            });
            if(!own)try{api.rpc("mw_story_mark_viewed",new JSONObject().put("p_story",storyId));}
                catch(Exception ignored){}
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
        LinearLayout holder=vertical();holder.addView(grid);
        TextView other=button("＋  Другой эмодзи");
        other.setPadding(dp(10),dp(12),dp(10),dp(12));holder.addView(other);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Реакция на историю")
            .setView(holder).setNegativeButton("Закрыть",null).create();
        other.setOnClickListener(v->{dialog.dismiss();showCustomReactionDialog(item);});
        for(String e:emoji){
            TextView cell=text(e,26,white());cell.setGravity(Gravity.CENTER);
            android.widget.GridLayout.LayoutParams p=new android.widget.GridLayout.LayoutParams();
            p.width=dp(49);p.height=dp(49);p.setMargins(dp(1),dp(1),dp(1),dp(1));
            grid.addView(cell,p);cell.setOnClickListener(v->{dialog.dismiss();sendReaction(item,e);});
        }
        dialog.show();
    }
    private void showCustomReactionDialog(JSONObject story){
        EditText input=new EditText(this);input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(18)});
        input.setHint("Введите любой эмодзи");
        new AlertDialog.Builder(this).setTitle("Другой эмодзи")
            .setMessage("Можно выбрать эмодзи на клавиатуре Android")
            .setView(input).setNegativeButton("Отмена",null)
            .setPositiveButton("Отправить",(d,w)->{
                String e=input.getText().toString().trim();
                if(e.isEmpty()||e.codePointCount(0,e.length())>8){
                    Toast.makeText(this,"Выберите короткую реакцию",Toast.LENGTH_SHORT).show();return;
                }
                sendReaction(story,e);
            }).show();
    }
    private void playReactionEffect(String emoji){
        if(!viewing||frame==null)return;
        TextView effect=text(emoji,72,white());effect.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pos=new FrameLayout.LayoutParams(dp(148),dp(148),Gravity.CENTER);
        frame.addView(effect,pos);effect.setScaleX(.4f);effect.setScaleY(.4f);
        android.animation.ObjectAnimator scaleX=android.animation.ObjectAnimator.ofFloat(effect,"scaleX",.4f,1.6f);
        android.animation.ObjectAnimator scaleY=android.animation.ObjectAnimator.ofFloat(effect,"scaleY",.4f,1.6f);
        android.animation.ObjectAnimator floatUp=android.animation.ObjectAnimator.ofFloat(effect,"translationY",0f,-dp(115));
        android.animation.ObjectAnimator fade=android.animation.ObjectAnimator.ofFloat(effect,"alpha",1f,0f);
        android.animation.AnimatorSet animation=new android.animation.AnimatorSet();
        animation.playTogether(scaleX,scaleY,floatUp,fade);animation.setDuration(800);
        animation.addListener(new android.animation.AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(android.animation.Animator a){
                if(effect.getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup)effect.getParent()).removeView(effect);
            }
        });
        animation.start();
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
            ui.post(()->{if(!destroyed){
                playReactionEffect(emoji);
                Toast.makeText(this,"Реакция отправлена "+emoji,Toast.LENGTH_SHORT).show();
            }});
        });
    }
    @Override public void onBackPressed(){if(viewing)renderFeed();else returnFromScreen();}
    @Override protected void onDestroy(){destroyed=true;viewing=false;stopPlayback();io.shutdownNow();thumbnailWork.shutdownNow();thumbnailCache.evictAll();super.onDestroy();}
}