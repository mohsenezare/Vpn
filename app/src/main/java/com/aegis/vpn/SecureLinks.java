package com.aegis.vpn;
import android.content.Context;
import android.util.Base64;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.*;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Keystore-encrypted local config and subscription storage. Never logs secret links. */
final class SecureLinks {
    private static final String ALIAS="aegis-config-v1";
    private final Context context;
    SecureLinks(Context c){context=c.getApplicationContext();}
    private SecretKey key() throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        SecretKey k=(SecretKey)store.getKey(ALIAS,null);
        if(k!=null)return k;
        KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return gen.generateKey();
    }
    private synchronized JSONObject readData(){
        try{
            String enc=context.getSharedPreferences("vault",0).getString("cipher",null);
            String iv=context.getSharedPreferences("vault",0).getString("iv",null);
            if(enc==null||iv==null)return new JSONObject();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            return new JSONObject(new String(cipher.doFinal(Base64.decode(enc,Base64.NO_WRAP)),StandardCharsets.UTF_8));
        }catch(Exception e){return new JSONObject();}
    }
    private synchronized void writeData(JSONObject data)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] result=cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        boolean ok=context.getSharedPreferences("vault",0).edit()
            .putString("cipher",Base64.encodeToString(result,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).commit();
        if(!ok)throw new IllegalStateException("Failed to save encrypted configuration");
    }
    synchronized void put(String kind,String value)throws Exception{
        if(value==null||value.length()>8192||value.length()<8)throw new IllegalArgumentException("Invalid input size");
        JSONObject data=readData();
        JSONArray arr=data.optJSONArray(kind);if(arr==null)arr=new JSONArray();
        LinkedHashSet<String> current=new LinkedHashSet<>();
        current.add(value);
        for(int i=0;i<arr.length();i++)if(current.size()<60)current.add(arr.optString(i));
        JSONArray out=new JSONArray();for(String x:current)out.put(x);
        data.put(kind,out);writeData(data);
    }
    synchronized List<String> get(String kind){
        ArrayList<String> list=new ArrayList<>();JSONArray arr=readData().optJSONArray(kind);
        if(arr!=null)for(int i=0;i<arr.length();i++)if(!arr.optString(i).isEmpty())list.add(arr.optString(i));
        return list;
    }
    synchronized void select(String value)throws Exception{
        JSONObject data=readData();data.put("selected",value==null?"":value);writeData(data);
    }
    synchronized String selected(){return readData().optString("selected","");}
    synchronized void clearSelected()throws Exception{select("");}
    synchronized void remove(String kind,String value)throws Exception{
        JSONObject data=readData();JSONArray a=data.optJSONArray(kind),out=new JSONArray();
        if(a!=null)for(int i=0;i<a.length();i++)if(!value.equals(a.optString(i)))out.put(a.optString(i));
        data.put(kind,out);writeData(data);
    }
}
