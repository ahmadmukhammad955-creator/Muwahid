#!/usr/bin/env python3
"""Bump LIVE release after the existing family/sticker patches and add upgrade checks."""
from pathlib import Path

def replace(path,old,new):
 p=Path(path);s=p.read_text()
 if s.count(old)!=1:raise RuntimeError(f'{path}: expected one {old!r}')
 p.write_text(s.replace(old,new))
replace('app/build.gradle','versionCode 75','versionCode 76')
replace('app/build.gradle',"versionName '0.13.40'","versionName '0.13.41'")
replace('app/src/androidTest/java/com/muwahid/app/SmokeInstrumentation.java','"0.13.40".equals(BuildConfig.VERSION_NAME)','"0.13.41".equals(BuildConfig.VERSION_NAME)')
replace('app/src/androidTest/java/com/muwahid/app/SmokeInstrumentation.java','MW_SMOKE_OK: 0.13.40 ','MW_SMOKE_OK: 0.13.41 ')
Path('app/src/androidTest/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <instrumentation android:name="com.muwahid.app.UpgradeInstrumentation" android:targetPackage="com.muwahid.app" />
</manifest>''')
Path('app/src/androidTest/java/com/muwahid/app/UpgradeInstrumentation.java').write_text('''package com.muwahid.app;
import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Uses platform APIs only, so it can run against the previous obfuscated release. */
public final class UpgradeInstrumentation extends Instrumentation {
    private String phase;
    @Override public void onCreate(Bundle args){super.onCreate(args);phase=args.getString("phase");start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context ctx=getTargetContext();SharedPreferences prefs=ctx.getSharedPreferences("muwahid",0);
        SharedPreferences session=ctx.getSharedPreferences("session",0);
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        String alias="muwahid.session";
        if("seed".equals(phase)){
            String marker=UUID.randomUUID().toString();
            if(!ks.containsAlias(alias)){
                KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
                gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
            }
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,(SecretKey)ks.getKey(alias,null));
            if(!prefs.edit().putString("upgrade_test_marker",marker).commit())throw new Exception("Preference write failed");
            if(!session.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("data",Base64.encodeToString(cipher.doFinal(marker.getBytes("UTF-8")),Base64.NO_WRAP)).commit())throw new Exception("Session write failed");
            result.putString("stream","UPGRADE_SEED_OK");
        }else if("verify".equals(phase)){
            String marker=prefs.getString("upgrade_test_marker","");if(marker.isEmpty())throw new Exception("App data lost");
            if(!ks.containsAlias(alias))throw new Exception("Keystore key lost");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,(SecretKey)ks.getKey(alias,null),
                new GCMParameterSpec(128,Base64.decode(session.getString("iv",""),Base64.NO_WRAP)));
            String decoded=new String(cipher.doFinal(Base64.decode(session.getString("data",""),Base64.NO_WRAP)),"UTF-8");
            if(!marker.equals(decoded))throw new Exception("Encrypted session data lost");
            result.putString("stream","UPGRADE_DATA_OK");
        }else throw new Exception("Unknown upgrade phase");
        finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","UPGRADE_FAILED: "+failure.getClass().getSimpleName());finish(Activity.RESULT_CANCELED,result);}}
}
''')
print('MUWAHID_LIVE_RELEASE_READY: 0.13.41 (76)')
