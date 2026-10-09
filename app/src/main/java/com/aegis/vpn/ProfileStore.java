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
    void save(String config,String username,String password) throws Exception{
        JSONObject data=new JSONObject();
        data.put("config",config);data.put("username",username);data.put("password",password);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=c.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ctx.getSharedPreferences("paid",0).edit()
          .putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP))
          .putString("blob",Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();
    }
    boolean exists(){return ctx.getSharedPreferences("paid",0).contains("blob");}
    String getOpenVpnConfig() throws Exception{
        android.content.SharedPreferences p=ctx.getSharedPreferences("paid",0);
        String iv=p.getString("iv",null),blob=p.getString("blob",null);
        if(iv==null||blob==null)return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        JSONObject data=new JSONObject(new String(c.doFinal(Base64.decode(blob,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
        String config=data.getString("config");
        String username=data.optString("username"),password=data.optString("password");
        if(!username.isEmpty()){
            config=config.replaceAll("(?ms)<auth-user-pass>.*?</auth-user-pass>","");
            config=config.replaceAll("(?m)^\\s*auth-user-pass(?:[ \\t]+[^\\r\\n]+)?\\s*$","");
            config+="\n<auth-user-pass>\n"+username+"\n"+password+"\n</auth-user-pass>\n";
        }
        return config;
    }
    void clear(){ctx.getSharedPreferences("paid",0).edit().clear().apply();}
}
