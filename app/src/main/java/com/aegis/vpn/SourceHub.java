package com.aegis.vpn;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
final class SourceHub {
 static final String[] CHANNELS={"net_azad","proxyplus","irovpn","mtproto021","netmeli_ir","npv_iran","proxy_netmeli","onevpn","iproxy2","myconfig","miticonfig","proxyrp","mitivpn"};
 final Context context;
 final Handler main=new Handler(Looper.getMainLooper());
 volatile boolean busy;
 SourceHub(Context c){context=c.getApplicationContext();}
 List<String> sources(){
  ArrayList<String> s=new ArrayList<>();
  for(String c:CHANNELS)s.add("https://t.me/s/"+c);
  s.add("https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/Eternity.txt");
  return s;
 }
 static String get(String address)throws Exception{
  URL u=new URL(address);if(!u.getProtocol().equals("https"))throw new IOException("HTTPS required");
  HttpURLConnection c=(HttpURLConnection)u.openConnection();
  c.setConnectTimeout(7000);c.setReadTimeout(9000);c.setInstanceFollowRedirects(false);
  c.setRequestProperty("User-Agent","Mozilla/5.0 AegisVPN/0.4");
  try{
   int status=c.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>3000000)throw new IOException("Feed exceeds 3 MB");out.write(b,0,n);}
    return out.toString("UTF-8");
   }
  }finally{c.disconnect();}
 }
 boolean stale(){return System.currentTimeMillis()-context.getSharedPreferences("hub",0).getLong("attempt",0)>3600000L;}
 synchronized void refresh(Runnable done){
  if(busy)return;busy=true;
  new Thread(()->{try{fetch();}finally{busy=false;main.post(done);}},"source-hub").start();
 }
 void fetch(){
  ExecutorService pool=Executors.newFixedThreadPool(4);
  List<Callable<Void>> jobs=new ArrayList<>();
  for(String s:sources())jobs.add(()->{
   try{
    List<FeedParser.Entry> entries=FeedParser.parse(get(s),s);
    if(entries.isEmpty())throw new IOException("No supported configs in public preview");
    JSONArray a=new JSONArray();
    for(FeedParser.Entry e:entries){JSONObject o=new JSONObject();o.put("k",e.kind);o.put("v",e.value);a.put(o);}
    context.getSharedPreferences("hub",0).edit().putString(s,a.toString()).putLong(s+"time",System.currentTimeMillis()).putString(s+"error","").apply();
   }catch(Exception e){context.getSharedPreferences("hub",0).edit().putString(s+"error",e.getMessage()==null?"Network error":e.getMessage()).apply();}
   return null;
  });
  try{pool.invokeAll(jobs,55,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{pool.shutdownNow();context.getSharedPreferences("hub",0).edit().putLong("attempt",System.currentTimeMillis()).apply();}
 }
 List<FeedParser.Entry> entries(String kind){
  LinkedHashMap<String,FeedParser.Entry> result=new LinkedHashMap<>();
  for(String s:sources()){
   // Keep the last successful cache for 72 h, including during feed failures.
   if(System.currentTimeMillis()-context.getSharedPreferences("hub",0).getLong(s+"time",0)>259200000L)continue;
   try{JSONArray a=new JSONArray(context.getSharedPreferences("hub",0).getString(s,"[]"));
    for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(o.getString("k").equals(kind))result.put(o.getString("v"),new FeedParser.Entry(kind,o.getString("v"),s));}
   }catch(Exception ignored){}
  }return new ArrayList<>(result.values());
 }
 String report(){
  StringBuilder b=new StringBuilder();
  for(String s:sources()){
   long t=context.getSharedPreferences("hub",0).getLong(s+"time",0);
   String e=context.getSharedPreferences("hub",0).getString(s+"error","");
   b.append(s.replace("https://t.me/s/","@")).append("\n").append(t==0?"No successful update":android.text.format.DateFormat.format("MM-dd HH:mm",t)).append(e.isEmpty()?"":" · "+e).append("\n\n");
  }return b.toString();
 }
}
