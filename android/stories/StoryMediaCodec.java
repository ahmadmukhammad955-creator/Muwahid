package com.muwahid.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Story files are encrypted on the Android device BEFORE Supabase upload.
 * Stored bytes: ASCII MWS1, 12-byte unique IV, AES-256-GCM ciphertext+16-byte tag.
 * The 32-byte story key is never uploaded in plaintext. Each authorized contact
 * receives a separate E2EE mw1 envelope sealed with the verified chat key.
 */
final class StoryMediaCodec {
    private static final byte[] MAGIC = {'M','W','S','1'};
    private static final byte[] AAD = "Muwahid:E2EE:story:media:v1".getBytes(StandardCharsets.UTF_8);
    static final int MAX_CIPHER_BYTES = 26_214_400;
    static final int HEADER_BYTES = 4+12+16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private StoryMediaCodec(){}

    static byte[] newKey(){
        byte[] key=new byte[32];RANDOM.nextBytes(key);return key;
    }
    static byte[] encrypt(byte[] plain,byte[] key) throws Exception {
        checkKey(key);
        if(plain==null||plain.length<1||plain.length>MAX_CIPHER_BYTES-HEADER_BYTES)
            throw new IOException("История слишком большая (максимум 25 МБ)");
        byte[] iv=new byte[12];RANDOM.nextBytes(iv);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        aes.updateAAD(AAD);
        byte[] cipher=aes.doFinal(plain);
        byte[] result=new byte[4+iv.length+cipher.length];
        System.arraycopy(MAGIC,0,result,0,4);
        System.arraycopy(iv,0,result,4,12);
        System.arraycopy(cipher,0,result,16,cipher.length);
        if(result.length>MAX_CIPHER_BYTES)throw new IOException("Зашифрованный файл слишком большой");
        return result;
    }
    static byte[] decrypt(byte[] encrypted,byte[] key) throws Exception {
        checkKey(key);
        if(encrypted==null||encrypted.length<HEADER_BYTES||encrypted.length>MAX_CIPHER_BYTES
                ||!Arrays.equals(MAGIC,Arrays.copyOf(encrypted,4)))
            throw new SecurityException("Неизвестный формат зашифрованной истории");
        byte[] iv=Arrays.copyOfRange(encrypted,4,16);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        aes.updateAAD(AAD);
        return aes.doFinal(encrypted,16,encrypted.length-16);
    }
}