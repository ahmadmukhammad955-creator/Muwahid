#!/usr/bin/env python3
"""Offer Muwahid as an APK sticker archive importer, without executing the APK.

This runs after finish-personal-stickers.py on the extracted Android project.
Only WhatsApp-style, file-backed assets/contents.json packs are supported.
"""
from pathlib import Path
import re

main=Path("app/src/main/java/com/muwahid/app/MainActivity.java")
manifest=Path("app/src/main/AndroidManifest.xml")

def one(source, old, new, description):
    count=source.count(old)
    if count!=1:
        raise RuntimeError(f"{description}: expected exactly one match, got {count}")
    return source.replace(old,new,1)

java=main.read_text(encoding="utf-8")
java=one(java,
    'new String[]{"Добавить из телефона","Добавить по ссылке из интернета"},',
    'new String[]{"Добавить из телефона","Добавить по ссылке из интернета","Импортировать набор из APK"},',
    "sticker picker choices")
java=one(java,
    'else showStickerUrlDialog();})',
    'else if(w==1)showStickerUrlDialog();else launchStickerArchivePicker();})',
    "sticker picker dispatch")
java=one(java,
    'else if(requestCode==REQ_STICKER_IMPORT&&resultCode==RESULT_CANCELED){pendingStickerPackId="";}',
    'else if(requestCode==REQ_STICKER_ARCHIVE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){confirmImportStickerArchive(data.getData());}\n        else if(requestCode==REQ_STICKER_IMPORT&&resultCode==RESULT_CANCELED){pendingStickerPackId="";}',
    "APK file picker callback")

methods=r'''
    private static final int REQ_STICKER_ARCHIVE=9142;

    private void launchStickerArchivePicker(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent,REQ_STICKER_ARCHIVE);
    }

    private void confirmImportStickerArchive(Uri archive){
        if(archive==null)return;
        if(api==null||!api.signedIn()){
            notice("Для импорта набора войдите в свой аккаунт Muwahid.");return;
        }
        new AlertDialog.Builder(this).setTitle("Импорт набора стикеров")
          .setMessage("Muwahid прочитает изображения из APK как из архива. APK не будет установлен или запущен. Поддерживаются наборы со стандартным каталогом assets/contents.json. Импортируемые стикеры останутся личными.")
          .setNegativeButton("Отмена",null)
          .setPositiveButton("Импортировать",(d,w)->{
              worker.execute(()->{
                  try{
                      int imported=importStickerArchiveBlocking(archive);
                      handler.post(()->{
                          stickerCloudLoadedAt=0;
                          notice("Импортировано стикеров: "+imported+". Откройте «Мои стикеры».");
                      });
                  }catch(Exception e){handler.post(()->error(e));}
              });
          }).show();
    }

    private byte[] readStickerZipEntry(java.util.zip.ZipFile zip,java.util.zip.ZipEntry entry,int limit) throws Exception{
        if(entry==null||entry.isDirectory()||entry.getSize()>limit)
            throw new Exception("Повреждённый или слишком большой файл набора");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=zip.getInputStream(entry)){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){
                if(out.size()+n>limit)throw new Exception("Превышен размер файла набора");
                out.write(b,0,n);
            }
        }
        return out.toByteArray();
    }

    private int importStickerArchiveBlocking(Uri archive) throws Exception{
        if(api==null||!api.signedIn())throw new Exception("Нужно войти в Muwahid");
        java.io.File tmp=java.io.File.createTempFile("muwahid-stickers-",".apk",getCacheDir());
        final long maxCompressed=100L*1024*1024;
        try{
            long total=0;
            try(java.io.InputStream in=getContentResolver().openInputStream(archive);
                java.io.FileOutputStream out=new java.io.FileOutputStream(tmp)){
                if(in==null)throw new Exception("Невозможно открыть APK");
                byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){
                    total+=n;
                    if(total>maxCompressed)throw new Exception("Максимальный размер APK — 100 МБ");
                    out.write(b,0,n);
                }
            }
            try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(tmp)){
                if(zip.size()>20000)throw new Exception("Слишком много файлов в APK");
                java.util.zip.ZipEntry index=zip.getEntry("assets/contents.json");
                if(index==null)index=zip.getEntry("contents.json");
                if(index==null)throw new Exception("В этом APK не найден поддерживаемый каталог стикеров assets/contents.json");
                org.json.JSONObject content=new org.json.JSONObject(
                    new String(readStickerZipEntry(zip,index,512*1024),java.nio.charset.StandardCharsets.UTF_8));
                org.json.JSONArray packs=content.optJSONArray("sticker_packs");
                if(packs==null||packs.length()==0||packs.length()>40)
                    throw new Exception("В APK нет корректного списка наборов стикеров");
                int imported=0;
                long importedBytes=0;
                for(int p=0;p<packs.length();p++){
                    org.json.JSONObject pack=packs.optJSONObject(p);
                    if(pack==null)continue;
                    String identifier=pack.optString("identifier","");
                    if(!identifier.matches("[A-Za-z0-9_.-]{1,128}")||identifier.contains(".."))continue;
                    org.json.JSONArray stickers=pack.optJSONArray("stickers");
                    if(stickers==null||stickers.length()==0||stickers.length()>200)continue;
                    String title=pack.optString("name","Набор стикеров");
                    if(title.isEmpty())title="Набор стикеров";
                    String personalPackId="";
                    for(int s=0;s<stickers.length();s++){
                        org.json.JSONObject sticker=stickers.optJSONObject(s);
                        if(sticker==null)continue;
                        String file=sticker.optString("image_file","");
                        if(!file.matches("[A-Za-z0-9_.-]{1,180}")||file.contains(".."))continue;
                        java.util.zip.ZipEntry item=zip.getEntry("assets/"+identifier+"/"+file);
                        if(item==null)continue;
                        if(item.getSize()>10L*1024*1024)continue;
                        byte[] bytes;
                        try{bytes=readStickerZipEntry(zip,item,10*1024*1024);}
                        catch(Exception ignored){continue;}
                        if(detectStickerMime(bytes,file)==null)continue;
                        if(imported>=500||importedBytes+bytes.length>150L*1024*1024)
                            throw new Exception("Ограничение импорта: 500 стикеров или 150 МБ");
                        if(personalPackId.isEmpty()){
                            personalPackId=String.valueOf(api.rpc("mw_sticker_create_pack",
                               new org.json.JSONObject().put("p_title",title)
                                   .put("p_description","Импорт из APK · только для владельца")
                                   .put("p_is_public",false)));
                            if(personalPackId.isEmpty()||"null".equals(personalPackId))
                                throw new Exception("Не удалось создать личный набор");
                        }
                        importStickerBytesBlocking(bytes,file,"",personalPackId,"file");
                        imported++;
                        importedBytes+=bytes.length;
                    }
                }
                if(imported==0)throw new Exception("В APK не найдено совместимых PNG, JPG, WebP, GIF, WebM или TGS стикеров");
                return imported;
            }
        }finally{
            if(!tmp.delete())tmp.deleteOnExit();
        }
    }

    private void processIncomingStickerArchive(){
        Intent inbound=getIntent();
        if(inbound==null)return;
        String action=inbound.getAction();
        if(!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_VIEW.equals(action))return;
        Object extra=Intent.ACTION_SEND.equals(action)?inbound.getParcelableExtra(Intent.EXTRA_STREAM):null;
        Uri uri=Intent.ACTION_VIEW.equals(action)?inbound.getData():
            (extra instanceof Uri?(Uri)extra:null);
        if(uri==null)return;
        String type=inbound.getType();
        String segment=uri.getLastPathSegment();
        String suffix=segment==null?"":segment.toLowerCase(java.util.Locale.ROOT);
        boolean archiveType="application/vnd.android.package-archive".equals(type)
            ||"application/zip".equals(type)||"application/octet-stream".equals(type);
        if(!archiveType&&!suffix.endsWith(".apk")&&!suffix.endsWith(".zip"))return;
        inbound.setAction(null); // Prevent repeated dialog when activity resumes.
        confirmImportStickerArchive(uri);
    }
'''
marker='    private void launchStickerImportPicker(){'
java=one(java,marker,methods+'\n'+marker,"native APK importer method anchor")

# Some versions already override these Android lifecycle methods. Preserve
# the original method, injecting only our entry point when it exists.
def lifecycle(code, name, signature, instruction):
    pattern=re.compile(r'(\bvoid\s+'+name+r'\s*\(\s*\)\s*\{)')
    matches=list(pattern.finditer(code))
    if len(matches)>1:
        raise RuntimeError(f"Unexpected overloads: {name}")
    if matches:
        match=matches[0]
        return code[:match.end()]+"\n        "+instruction+code[match.end():]
    return one(code,marker,signature+"\n"+marker,f"insert {name}")

java=lifecycle(java,"onPostResume",
    '    @Override protected void onPostResume(){super.onPostResume();processIncomingStickerArchive();}\n',
    'processIncomingStickerArchive();')

new_intent=re.compile(r'(\bvoid\s+onNewIntent\s*\(\s*(?:android\.content\.)?Intent\s+(\w+)\s*\)\s*\{)')
found=list(new_intent.finditer(java))
if len(found)>1:raise RuntimeError("Unexpected onNewIntent overloads")
if found:
    m=found[0]
    java=java[:m.end()]+"\n        setIntent("+m.group(2)+");processIncomingStickerArchive();"+java[m.end():]
else:
    java=one(java,marker,
        '    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);processIncomingStickerArchive();}\n'+marker,
        "incoming APK intent")
assert java.count("importStickerArchiveBlocking(Uri archive)")==1
assert 'assets/contents.json' in java
main.write_text(java,encoding="utf-8")

xml=manifest.read_text(encoding="utf-8")
rx=re.compile(r'<activity\b(?=[^>]*android:name\s*=\s*"[^"]*MainActivity")[^>]*>')
match=rx.search(xml)
if not match:raise RuntimeError("MainActivity manifest entry not found")
filters=r'''
            <!-- File import, NOT APK installation or arbitrary code execution -->
            <intent-filter android:label="Добавить стикеры в Muwahid">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/vnd.android.package-archive" />
                <data android:mimeType="application/zip" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
            <intent-filter android:label="Добавить стикеры в Muwahid">
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/vnd.android.package-archive" />
                <data android:mimeType="application/zip" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
'''
xml=xml[:match.end()]+filters+xml[match.end():]
manifest.write_text(xml,encoding="utf-8")
print("APK_STICKER_IMPORT_READY: VIEW/SEND APK+ZIP, private file-backed sticker pack import, no APK execution")
