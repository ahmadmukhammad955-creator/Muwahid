#!/usr/bin/env python3
"""Muwahid 0.13.40: retire all bundled stickers and enable private imports.

Applied *after* the verified family-security and remove-cat patches. Never
delete historic encrypted messages or user-owned assets.
"""
from pathlib import Path

MAIN = Path("app/src/main/java/com/muwahid/app/MainActivity.java")
CATALOG = Path("app/src/main/java/com/muwahid/app/StickerCatalog.java")
TEST = Path("app/src/androidTest/java/com/muwahid/app/SmokeInstrumentation.java")
GRADLE = Path("app/build.gradle")


def replace_once(file, old, new):
    source = file.read_text(encoding="utf-8")
    found = source.count(old)
    if found != 1:
        raise RuntimeError(f"{file}: expected one occurrence, saw {found}: {old[:110]!r}")
    file.write_text(source.replace(old, new), encoding="utf-8")


def replace_block(file, begin, end, new):
    source = file.read_text(encoding="utf-8")
    if source.count(begin) != 1 or source.count(end) != 1:
        raise RuntimeError(f"{file}: block markers not unique: {begin[:70]} / {end[:70]}")
    start = source.index(begin)
    stop = source.index(end, start)
    if start >= stop:
        raise RuntimeError("Inverted block")
    file.write_text(source[:start] + new + source[stop:], encoding="utf-8")


replace_once(CATALOG,
    'public static final String[] PACKS={"","Облачко"};',
    'public static final String[] PACKS={};')
replace_once(CATALOG,
    'public static boolean isAvailable(int id){return id>=6 && id<12;}',
    'public static boolean isAvailable(int id){return false;}')

# The entire retired built-in catalog (0..11) is hidden even in old chats.
replace_once(MAIN, 'if(retiredId>=0&&retiredId<6){bubble("Стикер удалён из Muwahid",mine,when);return;}',
    'if(retiredId>=0&&retiredId<12){bubble("Стикер удалён из Muwahid",mine,when);return;}')

replace_once(MAIN,
    '"⇧  Добавить / импортировать","Выбрать стикер с телефона или файл"',
    '"＋  Добавить мои стикеры","Из телефона или по ссылке · личная коллекция"')
replace_once(MAIN,
    '"⇧  Добавить стикер","PNG, JPG, WebP, GIF, WebM или TGS"',
    '"＋  Добавить мои стикеры","Из файла или интернета по прямой ссылке"')
replace_once(MAIN,
    '"Стикеры Muwahid теперь связаны с Supabase. Новые наборы и загруженные стикеры появляются без отдельной сборки APK."',
    '"Каждый пользователь хранит собственную коллекцию. Добавляйте PNG, JPG, GIF, WebP или анимации из телефона и по HTTPS-ссылке. Чужие наборы не становятся вашими автоматически."')
replace_once(MAIN,
    '"Личный или общий набор стикеров"',
    '"Ваш личный набор · другим пользователям не виден"')
replace_once(MAIN,
    'box.addView(title);box.addView(desc);box.addView(pub);',
    'box.addView(title);box.addView(desc);')
replace_once(MAIN,
    '.put("p_is_public",pub.isChecked())',
    '.put("p_is_public",false)')

# Avoid the previous two-step flow (select pack, then choose a file) and expose
# a direct HTTPS link option from the visible sticker picker.
replace_block(MAIN,
    '    private void chooseStickerImportTarget(){',
    '    private void launchStickerImportPicker(){',
    """    private void chooseStickerImportTarget(){
        if(api==null||!api.signedIn()){notice("Войдите в аккаунт, чтобы добавлять личные стикеры.");return;}
        new AlertDialog.Builder(this).setTitle("Мои стикеры")
          .setItems(new String[]{"Добавить из телефона","Добавить по ссылке из интернета"},
             (d,w)->{if(w==0){withPersonalStickerPack(id->{pendingStickerPackId=id;launchStickerImportPicker();});}
                      else showStickerUrlDialog();})
          .setNegativeButton("Отмена",null).show();
    }
    private String ensurePersonalStickerPackBlocking() throws Exception{
        JSONObject cat=(JSONObject)api.rpc("mw_sticker_catalog",new JSONObject());
        String pack=findOwnedPackByTitle(cat,"Мои стикеры");
        if(pack.isEmpty())pack=String.valueOf(api.rpc("mw_sticker_create_pack",
            new JSONObject().put("p_title","Мои стикеры").put("p_description","Личная коллекция")
                            .put("p_is_public",false)));
        if(pack==null||pack.isEmpty()||"null".equalsIgnoreCase(pack))throw new Exception("Не удалось создать личный набор");
        return pack;
    }
    private void withPersonalStickerPack(StringDone done){
        worker.execute(()->{try{String id=ensurePersonalStickerPackBlocking();
            handler.post(()->done.accept(id));
        }catch(Exception e){handler.post(()->error(e));}});
    }
    private void showStickerUrlDialog(){
        EditText input=new EditText(this);
        input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/sticker.webp");
        LinearLayout holder=vertical();holder.setPadding(dp(20),dp(8),dp(20),dp(8));
        holder.addView(input,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle("Добавить стикер по ссылке")
          .setMessage("Вставьте прямую HTTPS-ссылку на PNG, JPG, GIF, WebP или WebM. Размер до 10 МБ.")
          .setView(holder).setNegativeButton("Отмена",null)
          .setPositiveButton("Добавить",(d,w)->{
             String link=input.getText().toString().trim();
             if(!link.startsWith("https://")){notice("Нужна прямая ссылка HTTPS.");return;}
             worker.execute(()->{
                try{String pack=ensurePersonalStickerPackBlocking();
                    importStickerUrlBlocking(link,pack);
                    handler.post(()->{stickerCloudLoadedAt=0;Toast.makeText(this,"Стикер добавлен только в вашу коллекцию",Toast.LENGTH_LONG).show();
                        if("chat".equals(screen))showStickerPicker();else showStickerSettings();});
                }catch(Exception e){handler.post(()->error(e));}
             });
          }).show();
    }
    private String importStickerUrlBlocking(String initial,String pack) throws Exception{
        java.net.URL url=new java.net.URL(initial);
        for(int redirect=0;redirect<=3;redirect++){
            validateStickerUrl(url);
            java.net.HttpURLConnection c=(java.net.HttpURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(18000);
            c.setRequestProperty("Accept","image/png,image/jpeg,image/webp,image/gif,video/webm,application/octet-stream");
            try{
                int code=c.getResponseCode();
                if(code>=300&&code<400){
                    String location=c.getHeaderField("Location");
                    if(location==null||location.isEmpty())throw new Exception("Неверный адрес перенаправления");
                    url=new java.net.URL(url,location);continue;
                }
                if(code!=200)throw new Exception("Не удалось скачать стикер: HTTP "+code);
                if(c.getContentLengthLong()>10L*1024*1024)throw new Exception("Стикер больше 10 МБ");
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
                try(java.io.InputStream in=c.getInputStream()){
                    byte[] buf=new byte[8192];int read;
                    while((read=in.read(buf))!=-1){
                        if(out.size()+read>10*1024*1024)throw new Exception("Стикер больше 10 МБ");
                        out.write(buf,0,read);
                    }
                }
                String file=url.getPath();file=file.substring(file.lastIndexOf('/')+1);
                if(file.isEmpty())file="sticker";
                return importStickerBytesBlocking(out.toByteArray(),file,c.getContentType(),pack,"internet_url");
            }finally{c.disconnect();}
        }
        throw new Exception("Слишком много перенаправлений");
    }
    private void validateStickerUrl(java.net.URL url) throws Exception{
        if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null)
            throw new Exception("Разрешены только прямые HTTPS-ссылки без пароля");
        String host=url.getHost();if(host==null||host.isEmpty()||"localhost".equalsIgnoreCase(host))
            throw new Exception("Недопустимый адрес");
        for(java.net.InetAddress addr:java.net.InetAddress.getAllByName(host))
            if(addr.isAnyLocalAddress()||addr.isLoopbackAddress()||addr.isSiteLocalAddress()
                  ||addr.isLinkLocalAddress()||addr.isMulticastAddress())
                throw new Exception("Нельзя импортировать с локального адреса");
    }
""")

# Both file and URL imports run through the same bounded, signed-in upload.
replace_block(MAIN,
    '    private String importStickerUriBlocking(Uri uri,String packId,String sourceKind) throws Exception{',
    '    private String guessStickerMime(String name){',
    """    private String importStickerUriBlocking(Uri uri,String packId,String sourceKind) throws Exception{
        String mime=getContentResolver().getType(uri);
        String name=fileName(uri);
        return importStickerBytesBlocking(readUri(uri,10*1024*1024),name,mime,packId,sourceKind);
    }
    private String importStickerBytesBlocking(byte[] raw,String name,String mime,String packId,String sourceKind) throws Exception{
        if(raw==null||raw.length==0||raw.length>10*1024*1024)throw new Exception("Размер стикера: от 1 байта до 10 МБ");
        String detected=detectStickerMime(raw,name);
        if(detected==null)throw new Exception("Выберите PNG, JPG, GIF, WebP, WebM или TGS");
        if(name==null||name.isEmpty())name="sticker."+stickerExtension("",detected);
        if(!api.signedIn())throw new Exception("Для сохранения стикера нужна авторизация");
        String storage=api.uid()+"/"+UUID.randomUUID()+"."+stickerExtension(name,detected);
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
        if(bounds.outWidth>4096||bounds.outHeight>4096)throw new Exception("Максимальный размер изображения 4096×4096");
        boolean animated=detected.equals("image/gif")||detected.equals("image/webp")
                         ||detected.equals("video/webm")||detected.equals("application/x-tgsticker");
        api.uploadSticker(storage,raw);
        String asset=String.valueOf(api.rpc("mw_sticker_register_asset",
          new JSONObject().put("p_storage_path",storage).put("p_original_name",name)
            .put("p_mime_type",detected).put("p_file_size_bytes",raw.length)
            .put("p_width",bounds.outWidth>0?bounds.outWidth:JSONObject.NULL)
            .put("p_height",bounds.outHeight>0?bounds.outHeight:JSONObject.NULL)
            .put("p_animated",animated).put("p_source_kind",sourceKind)
            .put("p_source_ref","").put("p_sha256",sha256(raw))));
        api.rpc("mw_sticker_add_to_pack",new JSONObject().put("p_pack",packId)
            .put("p_sticker",asset).put("p_sort_order",0));
        return asset;
    }
    private String detectStickerMime(byte[] b,String name){
        if(b.length>=8&&(b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71&&b[4]==13&&b[5]==10&&b[6]==26&&b[7]==10)return "image/png";
        if(b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return "image/jpeg";
        if(b.length>=6&&b[0]=='G'&&b[1]=='I'&&b[2]=='F'&&b[3]=='8'&&(b[4]=='7'||b[4]=='9')&&b[5]=='a')return "image/gif";
        if(b.length>=12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'
          &&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P')return "image/webp";
        if(b.length>=4&&(b[0]&255)==26&&(b[1]&255)==69&&(b[2]&255)==223&&(b[3]&255)==163)return "video/webm";
        if(b.length>=2&&(b[0]&255)==31&&(b[1]&255)==139&&name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".tgs"))return "application/x-tgsticker";
        return null;
    }
""")

# Recipients can SAVE A COPY of the encrypted sticker from their own chat,
# never gain access to the sender's private Storage folder.
replace_once(MAIN,
    'new String[]{"Сохранить в избранное","Скачать файл"},(d,w)->{if(w==0)setCloudStickerFavoriteById(assetId,true);else downloadMedia(meta);}',
    'new String[]{"Сохранить себе","Скачать файл"},(d,w)->{if(w==0)saveReceivedStickerToMyCollection(meta);else downloadMedia(meta);}')
replace_once(MAIN,
    'if(!assetId.isEmpty())new AlertDialog.Builder(this).setTitle("Стикер")',
    'new AlertDialog.Builder(this).setTitle("Стикер")')
replace_block(MAIN,
    '    private String guessStickerMime(String name){',
    '    private String stickerExtension(String name,String mime){',
    """    private void saveReceivedStickerToMyCollection(JSONObject meta){
        if(api==null||!api.signedIn()||!e2eeBackendReady||peerE2eeKey.isEmpty()){
            notice("Для сохранения откройте защищённый чат.");return;
        }
        final String owner=api.uid(),id=chatId,peer=peerE2eeKey;
        worker.execute(()->{try{
            byte[] encrypted=api.downloadMedia(meta.getString("path"));
            byte[] raw=crypto.decryptBytes(owner,id,peer,encrypted);
            String pack=ensurePersonalStickerPackBlocking();
            importStickerBytesBlocking(raw,meta.optString("name","sticker.webp"),
                meta.optString("mime","image/webp"),pack,"received_chat");
            handler.post(()->{stickerCloudLoadedAt=0;
                Toast.makeText(this,"Стикер сохранён в «Мои стикеры»",Toast.LENGTH_LONG).show();});
        }catch(Exception e){handler.post(()->error(e));}});
    }
    private String guessStickerMime(String name){String n=name==null?"":name.toLowerCase(Locale.ROOT);if(n.endsWith(".png"))return "image/png";if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return "image/jpeg";if(n.endsWith(".gif"))return "image/gif";if(n.endsWith(".webp"))return "image/webp";if(n.endsWith(".webm"))return "video/webm";if(n.endsWith(".tgs"))return "application/x-tgsticker";return "application/octet-stream";}
""")

# If a file-picker was cancelled, ensure stale target cannot be applied to
# a later pick; preserve the existing Android persistable permission handling.
replace_once(MAIN,
    'else if(requestCode==REQ_STICKER_IMPORT&&resultCode==RESULT_OK&&data!=null){',
    'else if(requestCode==REQ_STICKER_IMPORT&&resultCode==RESULT_CANCELED){pendingStickerPackId="";}\n        else if(requestCode==REQ_STICKER_IMPORT&&resultCode==RESULT_OK&&data!=null){')
replace_once(MAIN,
    '"Создать свой набор в Supabase"',
    '"Только для вашего аккаунта"')
replace_once(MAIN,
    '"Избранное, эмодзи и настройки Supabase"',
    '"Мои наборы и избранное"')

# Android integration tests: confirm all old artwork is now unavailable,
# import affordance is visible, E2EE still round-trips a PNG, and legacy
# sticker messages render a non-sensitive placeholder.
replace_once(TEST,
    'for(String label:new String[]{"Облачко","Стикеры и эмодзи"})',
    'for(String label:new String[]{"Стикеры и эмодзи","＋  Добавить мои стикеры"})')
replace_once(TEST,
    'if(findText(decor,"Кот Мува")!=null)throw new AssertionError("Retired cat pack remains visible");',
    'if(findText(decor,"Кот Мува")!=null||findText(decor,"Облачко")!=null)throw new AssertionError("Retired built-in sticker packs remain visible");')
replace_block(TEST,
    '            java.util.HashSet<Integer> hashes=new java.util.HashSet<>();',
    '            checkedOnMain(() -> invoke("openConversation",',
    """            if(StickerCatalog.PACKS.length!=0)throw new AssertionError("Retired built-in sticker packs remain");
            for(int id=0;id<12;id++){
                if(StickerCatalog.isAvailable(id))throw new AssertionError("Retired sticker still available");
                org.json.JSONObject old=new org.json.JSONObject().put("sticker",true).put("sticker_id",id).put("sticker_animation","muwahid-vector-v1");
                if(AnimatedStickerView.catalogId(old)!=-1)throw new AssertionError("Retired sticker can still animate");
                try{StickerCatalog.png(id);throw new AssertionError("Retired sticker can still be sent");}
                catch(IllegalArgumentException expected){}
            }
            CryptoBox cb=new CryptoBox(getTargetContext());
            String alice="sticker-test-alice",bob="sticker-test-bob";
            byte[] sample=new byte[]{(byte)0x89,'P','N','G',13,10,26,10,0,0,0,0};
            byte[] cipher=cb.encryptBytes(alice,"sticker-test-chat",cb.publicKey(bob),sample);
            if(!java.util.Arrays.equals(sample,cb.decryptBytes(bob,"sticker-test-chat",cb.publicKey(alice),cipher)))
                throw new AssertionError("Encrypted sticker round-trip failed");

""")
replace_block(TEST,
    '            checkedOnMain(()->{\n                try{\n                    org.json.JSONObject meta=new org.json.JSONObject().put("mw_kind","media")',
    '            checkedOnMain(() -> activity.finish());',
    """            checkedOnMain(()->{
                try{
                    org.json.JSONObject meta=new org.json.JSONObject().put("mw_kind","media")
                        .put("media_type","image").put("sticker",true).put("sticker_id",11)
                        .put("sticker_animation","muwahid-vector-v1").put("name","retired.png");
                    org.json.JSONObject message=new org.json.JSONObject().put("sender_id","smoke-peer")
                        .put("body",meta.toString()).put("created_at","2026-10-06T00:00:00Z");
                    invoke("drawMessage",new Class<?>[]{org.json.JSONObject.class},message);
                    if(findText(activity.getWindow().getDecorView(),"Стикер удалён из Muwahid")==null)
                        throw new AssertionError("Retired cloud sticker is still visible in chat");
                }catch(org.json.JSONException e){throw new RuntimeException(e);}
            });
            capture("retired_sticker_chat.png");
""")

replace_once(GRADLE, "versionCode 74", "versionCode 75")
replace_once(GRADLE, "versionName '0.13.39'", "versionName '0.13.40'")
replace_once(TEST, '"0.13.39".equals(BuildConfig.VERSION_NAME)', '"0.13.40".equals(BuildConfig.VERSION_NAME)')
replace_once(TEST, "MW_SMOKE_OK: 0.13.39 ", "MW_SMOKE_OK: 0.13.40 ")

main=MAIN.read_text(encoding="utf-8")
assert 'if(retiredId>=0&&retiredId<12)' in main
assert 'importStickerUrlBlocking' in main
assert 'saveReceivedStickerToMyCollection(meta)' in main
assert 'String pack=ensurePersonalStickerPackBlocking()' in main
assert 'if(!StickerCatalog.isAvailable(id))continue;' in main
print("MUWAHID_PERSONAL_STICKERS_READY: all bundled stickers retired; file/URL private import; recipient save enabled")
