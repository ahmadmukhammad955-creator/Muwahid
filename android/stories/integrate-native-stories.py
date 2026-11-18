#!/usr/bin/env python3
"""Integrate native, encrypted stories into Muwahid Android source after 0.13.41 patches.

Preserves E2EE chat logic and avoids WebViews, fake placeholders, and external
media players. Copy stable sources and wire only tested entry points.
"""
from pathlib import Path
import re

APP=Path("app/src/main/java/com/muwahid/app")
MANIFEST=Path("app/src/main/AndroidManifest.xml")
MAIN=APP/"MainActivity.java"
API=APP/"Api.java"
TEST=Path("app/src/androidTest/java/com/muwahid/app/SmokeInstrumentation.java")
SOURCE=Path(__file__).resolve().parent


def one(path,old,new):
    s=path.read_text(encoding="utf-8")
    n=s.count(old)
    if n!=1:
        raise RuntimeError(f"Expected exactly one occurrence of {old[:130]!r} in {path}, got {n}")
    path.write_text(s.replace(old,new),encoding="utf-8")

for name in ("ProfileAttachments.java","StoryMediaCodec.java","StoriesActivity.java","ProfilePhotoCodec.java"):
    src=SOURCE/name
    if not src.is_file():raise RuntimeError(f"Missing real source {src}")
    (APP/name).write_bytes(src.read_bytes())

# Storage transport uses the existing authenticated API session and private
# bucket. Stories always upload AES-GCM ciphertext as application/octet-stream.
one(API,
    '    synchronized void uploadSticker(String objectPath, byte[] data) throws Exception {',
    '''    synchronized void uploadProfileImage(String path,byte[] jpeg) throws Exception {
        uploadBucketObject("mw-profile-images",path,jpeg);
    }
    synchronized byte[] downloadProfileImage(String path) throws Exception {
        return downloadBucketObject("mw-profile-images",path);
    }
    synchronized void uploadStory(String objectPath,byte[] encrypted) throws Exception {
        uploadBucketObject("mw-stories",objectPath,encrypted);
    }
    synchronized byte[] downloadStory(String objectPath) throws Exception {
        return downloadBucketObject("mw-stories",objectPath);
    }
    synchronized void deleteStory(String objectPath) throws Exception {
        ensureFresh();
        javax.net.ssl.HttpsURLConnection c=(javax.net.ssl.HttpsURLConnection)
            new java.net.URL(BuildConfig.SERVER_URL+"/storage/v1/object/mw-stories/"+encodeObjectPath(objectPath)).openConnection();
        c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
        c.setRequestMethod("DELETE");
        c.setRequestProperty("apikey",BuildConfig.PUBLIC_KEY);
        c.setRequestProperty("Authorization","Bearer "+session.optString("access_token"));
        try{
            int status=c.getResponseCode();
            if(status==401){c.disconnect();refresh();deleteStory(objectPath);return;}
            if(status>=300)throw new Exception("Не удалось удалить историю из хранилища ("+status+")");
        }finally{c.disconnect();}
    }

    synchronized void uploadSticker(String objectPath, byte[] data) throws Exception {''')

# Installed app menu exposes stories without asking users to paste a URL or
# install an unrelated APK. Contact chat title opens the real contact profile.
one(MAIN,
    'menuRow(card,"◯","Мой профиль",this::showAccountSettings);',
    'menuRow(card,"◯","Мой профиль",()->startActivityForResult(new Intent(this,StoriesActivity.class),REQ_CONTACT_STORIES));'
    'menuRow(card,"▧","Истории",()->startActivityForResult(new Intent(this,StoriesActivity.class).putExtra("archive",true),REQ_CONTACT_STORIES));'
    'menuRow(card,"⚙","Настройки аккаунта",this::showAccountSettings);')

# Route both the menu card and the old profile tab to the same owner activity.
one(MAIN,'profileCard.setOnClickListener(v->showAccountSettings());',
    'profileCard.setOnClickListener(v->showMyProfile());')
main_source=MAIN.read_text(encoding="utf-8")
start=main_source.index("    private void showMyProfile(){")
end=main_source.index("    private void showAccountSettings(){",start)
one(MAIN,main_source[start:end],"""    private void showMyProfile(){
        startActivityForResult(new Intent(this,StoriesActivity.class),REQ_CONTACT_STORIES);
    }
""")

one(MAIN,
    'bar.addView(words,new LinearLayout.LayoutParams(0,dp(48),1));',
    'bar.addView(words,new LinearLayout.LayoutParams(0,dp(48),1));'
    'if(!notesMode)words.setOnClickListener(v->openContactStories());')

one(MAIN,
    '            }else showAccountSettings();\n        }).show();',
    '            }else openContactStories();\n        }).show();')

# The composer must open immediately, even with slow/offline cloud sticker data.
# Cache the optional cloud reaction catalog for future launches; never wait for
# a network callback before showing the standard Android emoji grid.
one(MAIN,
    '    private void showPlainEmojiPicker(){loadStickerCloud(false,c->showPlainEmojiPickerWithCatalog(c),()->showPlainEmojiPickerWithCatalog(new JSONObject()));}',
    '''    private JSONObject instantEmojiCatalog=new JSONObject();
    private void showPlainEmojiPicker(){
        showPlainEmojiPickerWithCatalog(instantEmojiCatalog);
        loadStickerCloud(false,c->{if(c!=null)instantEmojiCatalog=c;},()->{});
    }''')

# Toolbar chatID is real, from mw_chats, and has a real profile/author.
anchor='    private void showChat(String id,String name){'
append='''    private static final int REQ_CONTACT_STORIES=6237;
    private void openContactStories(){
        if(notesMode||chatId==null||chatId.isEmpty())return;
        Intent i=new Intent(this,StoriesActivity.class);
        i.putExtra("chat_id",chatId);
        i.putExtra("person_name",chatName);
        for(int k=0;k<chats.length();k++){
            org.json.JSONObject c=chats.optJSONObject(k);
            if(c!=null&&chatId.equals(c.optString("id",""))){
                i.putExtra("person_id",c.optString("person_id",""));break;
            }
        }
        startActivityForResult(i,REQ_CONTACT_STORIES);
    }

'''
one(MAIN,anchor,append+anchor)

# Calls selected in contact profile go through the existing LIVE/telephony
# flow; they do not merely display a button.
needle='super.onActivityResult(requestCode,resultCode,data);'
one(MAIN,needle,needle+'''if(requestCode==REQ_CONTACT_STORIES){
            if(resultCode==RESULT_OK&&data!=null){
                if(data.getBooleanExtra("open_profile_settings",false)){
                    showAccountSettings();return;
                }
                if(!notesMode&&"chat".equals(screen)&&chatId!=null&&chatId.equals(data.getStringExtra("chat_id"))){
                    String mode=data.getStringExtra("contact_call_kind");
                    if("audio".equals(mode)||"video".equals(mode))startCall(mode);
                }
            }
            return;
        }''')

# Registered Android activity is internal only; no arbitrary external intents.
s=MANIFEST.read_text(encoding="utf-8")
if s.count("<application")!=1:raise RuntimeError("Manifest structure changed")
insert='<activity android:name=".StoriesActivity" android:exported="false" android:screenOrientation="portrait" />'
if insert in s:raise RuntimeError("StoriesActivity duplicate")
s=s.replace('<application','<application',1)
match=re.search(r"<application\b[^>]*>",s)
if not match:raise RuntimeError("Missing application element")
s=s[:match.end()]+"\n        "+insert+s[match.end():]
MANIFEST.write_text(s,encoding="utf-8")

# Instrumentation checks that the format is E2EE and tampering is rejected.
snippet='''    private void checkStoryMediaEncryption() throws Exception{
        byte[] key=StoryMediaCodec.newKey();
        byte[] original="Muwahid private story media".getBytes("UTF-8");
        byte[] cipher=StoryMediaCodec.encrypt(original,key);
        if(java.util.Arrays.equals(cipher,original))
            throw new AssertionError("Story uploaded without encryption");
        if(!java.util.Arrays.equals(original,StoryMediaCodec.decrypt(cipher,key)))
            throw new AssertionError("Story AES-256-GCM roundtrip failed");
        cipher[cipher.length-1]^=1;
        try{StoryMediaCodec.decrypt(cipher,key);throw new AssertionError("Tampered story decrypted");}
        catch(javax.crypto.AEADBadTagException expected){}
        byte[] different=StoryMediaCodec.newKey();
        try{StoryMediaCodec.decrypt(StoryMediaCodec.encrypt(original,key),different);
            throw new AssertionError("Other identity decrypted the story");}
        catch(javax.crypto.AEADBadTagException expected){}
    }

'''
one(TEST,'    private Activity activity;',snippet+'    private Activity activity;')
one(TEST,'            checkProfileQr();','            checkProfileQr();\n            checkStoryMediaEncryption();')
# Preserve the full causal stack when an instrumentation smoke test fails.
# Without this, Android 15 reports only InvocationTargetException and hides
# the failing operation. The app itself is not changed by this diagnostic.
one(TEST,
    'result.putString("stream", "MW_SMOKE_FAILED: " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()));',
    'result.putString("stream", "MW_SMOKE_FAILED: " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()) + "\\n" + android.util.Log.getStackTraceString(e));')

# Separate signed update candidate. The 0.13.41 baseline must stay unchanged.
# Only the assembled corrected app is marked as 0.13.46 (81) for a safe in-place update.
one(Path("app/build.gradle"),"versionCode 76","versionCode 81")
one(Path("app/build.gradle"),"versionName '0.13.41'","versionName '0.13.46'")
one(TEST,'"0.13.41".equals(BuildConfig.VERSION_NAME)','"0.13.46".equals(BuildConfig.VERSION_NAME)')
one(TEST,'MW_SMOKE_OK: 0.13.41 ','MW_SMOKE_OK: 0.13.46 ')


assert 'StoriesActivity.class' in MAIN.read_text(encoding="utf-8")
assert (APP/"ProfilePhotoCodec.java").is_file()
assert 'uploadProfileImage(String path' in API.read_text(encoding="utf-8")
assert 'uploadStory(String objectPath' in API.read_text(encoding="utf-8")
assert 'android:exported="false"' in MANIFEST.read_text(encoding="utf-8")
print("MW_NATIVE_STORIES_INTEGRATED: encrypted media, contact profile, story navigation, real call routes and security tests")

# Instrumentation supplies an in-memory transport in its own APK only.
one(API,"final class Api {","class Api {")
(TEST.parent/"ProfileArchiveChecks.java").write_bytes((SOURCE/"ProfileArchiveChecks.java").read_bytes())
one(TEST,"    private Activity activity;", """    private boolean checkingProfile;
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)
            throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        if(checkingProfile && StoriesActivity.class.getName().equals(name))return ProfileArchiveChecks.fixture();
        return super.newActivity(loader,name,intent);
    }
    private Activity activity;""")
one(TEST,"            checkStoryMediaEncryption();","""            checkStoryMediaEncryption();
            checkingProfile=true;
            try{ProfileArchiveChecks.run(this);}finally{checkingProfile=false;}""")

# Author envelopes use this account's own local identity; peer verification stays
# mandatory for every recipient envelope and every direct message.
marker="    private String sealText("
one(APP/"CryptoBox.java",marker,"""    synchronized String encryptOwnStoryKey(String uid,String encoded) throws Exception {
        return sealText(uid,"story:self",publicKey(uid),"",encoded);
    }
    synchronized String decryptOwnStoryKey(String uid,String envelope) throws Exception {
        if(envelope==null||!envelope.startsWith(FORMAT))throw new SecurityException("Повреждён ключ истории");
        return openText(uid,"story:self",publicKey(uid),"",envelope);
    }

"""+marker)
