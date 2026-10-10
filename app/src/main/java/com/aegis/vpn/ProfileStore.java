package com.aegis.vpn;
import android.content.Context;
import android.util.Base64;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class ProfileStore {
    private final Context ctx;
    private static final String ALIAS="aegis-profile-key";
    ProfileStore(Context c){ctx=c;}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey key=(SecretKey)ks.getKey(ALIAS,null);
        if(key!=null)return key;
        KeyGenerator kg=KeyGenerator.getInstance("AES","AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
          KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256).build());
        return kg.generateKey();
    }
    // V2Ray shares include credentials; store a pinned link under AndroidKeyStore AES-GCM.
    void saveNative(String link) throws Exception {
        if(link==null || link.length()>8192)throw new IllegalArgumentException("Invalid share link");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=c.doFinal(link.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ctx.getSharedPreferences("manual_native",0).edit()
          .putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP))
          .putString("blob",Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();
    }
    String getNative() throws Exception {
        android.content.SharedPreferences p=ctx.getSharedPreferences("manual_native",0);
        String iv=p.getString("iv",null),blob=p.getString("blob",null);
        if(iv==null||blob==null)return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(blob,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
    void clearNative(){ctx.getSharedPreferences("manual_native",0).edit().clear().apply();}
    boolean nativeSelected(){return ctx.getSharedPreferences("manual_native",0).contains("blob");}
}
