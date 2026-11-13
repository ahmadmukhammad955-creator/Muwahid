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

for name in ("StoryMediaCodec.java","StoriesActivity.java","ProfilePhotoCodec.java","ProfileCropView.java"):
    src=SOURCE/name
    if not src.is_file():raise RuntimeError(f"Missing real source {src}")
    (APP/name).write_bytes(src.read_bytes())

(TEST.parent/"ProfileCropChecks.java").write_bytes((SOURCE/"ProfileCropChecks.java").read_bytes())

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
                if(!notesMode&&"chat".equals(screen)){
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
one(TEST,'            checkProfileQr();','            checkProfileQr();\n            checkStoryMediaEncryption();\n            ProfileCropChecks.run();')

# Preserve the underlying UI exception in CI instead of only its reflection wrapper.
one(TEST, '            result.putString("stream", "MW_SMOKE_FAILED: " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()));', '            result.putString("stream", "MW_SMOKE_FAILED: " + android.util.Log.getStackTraceString(e));')

# Separate signed update candidate. The 0.13.41 baseline must stay unchanged.
# Only the assembled corrected app is marked as 0.13.44 (79) for a safe in-place update.
one(Path("app/build.gradle"),"versionCode 76","versionCode 79")
one(Path("app/build.gradle"),"versionName '0.13.41'","versionName '0.13.44'")
one(TEST,'"0.13.41".equals(BuildConfig.VERSION_NAME)','"0.13.44".equals(BuildConfig.VERSION_NAME)')
one(TEST,'MW_SMOKE_OK: 0.13.41 ','MW_SMOKE_OK: 0.13.44 ')


assert 'StoriesActivity.class' in MAIN.read_text(encoding="utf-8")
assert (APP/"ProfilePhotoCodec.java").is_file()
assert 'uploadProfileImage(String path' in API.read_text(encoding="utf-8")
assert 'uploadStory(String objectPath' in API.read_text(encoding="utf-8")
assert 'android:exported="false"' in MANIFEST.read_text(encoding="utf-8")
print("MW_NATIVE_STORIES_INTEGRATED: encrypted media, contact profile, story navigation, real call routes and security tests")

