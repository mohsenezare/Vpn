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
 static final String[] COUNTRIES={"US","CA","FR","CH","DE","GB","NL","JP","SG","AU"};
 static String country(String source){
  for(String code:COUNTRIES)if(source.contains("/sub-"+code+".txt"))return code;
  return "";
 }
 final Context context;
 final Handler main=new Handler(Looper.getMainLooper());
 volatile boolean busy;
 private final ArrayList<Runnable> completionListeners=new ArrayList<>();
 SourceHub(Context c){context=c.getApplicationContext();}
 List<String> sources(){
  ArrayList<String> s=new ArrayList<>();
  for(String c:CHANNELS)s.add("https://t.me/s/"+c);
  s.add("https://raw.githubusercontent.com/mahdibland/V2RayAggregator/master/Eternity.txt");
  // Additional independently refreshed protocol feeds, including HY2 profiles.
  // A downloaded link is not assumed to be reachable until the client tests it.
  s.add("https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/mini.txt");
  s.add("https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/lite.txt");
  s.add("https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/hysteria2.txt");
  for(String code:COUNTRIES)s.add("https://raw.githubusercontent.com/Mokafela/Config-Finder/master/split/sub-"+code+".txt");
  s.add("https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt");
  return s;
 }
 static String get(String address)throws Exception{
  URL u=new URL(address);if(!u.getProtocol().equals("https"))throw new IOException("HTTPS required");
  HttpURLConnection c=(HttpURLConnection)u.openConnection();
  c.setConnectTimeout(7000);c.setReadTimeout(9000);c.setInstanceFollowRedirects(false);
  c.setRequestProperty("User-Agent","Mozilla/5.0 AegisVPN/0.6");
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
  if(done!=null)completionListeners.add(done);
  if(busy)return;
  busy=true;
  new Thread(()->{
   try{fetch();}
   finally{
    List<Runnable> callbacks;
    synchronized(this){
     busy=false;
     callbacks=new ArrayList<>(completionListeners);
     completionListeners.clear();
    }
    main.post(()->{for(Runnable callback:callbacks){
     try{callback.run();}catch(Exception ignored){}
    }});
   }
  },"source-hub").start();
 }
 void fetch(){
  ExecutorService pool=Executors.newFixedThreadPool(4);
  List<Callable<Void>> jobs=new ArrayList<>();
  for(String s:sources())jobs.add(()->{
   try{
    List<FeedParser.Entry> entries;
    long retrievedAt=System.currentTimeMillis();
    try{
     String body=get(s);
     if(s.startsWith("https://t.me/s/")&&!java.util.regex.Pattern.compile("data-post=\""+java.util.regex.Pattern.quote(s.substring(s.lastIndexOf('/')+1))+"/[0-9]+\"",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(body).find())throw new IOException("Preview does not match requested channel");
     entries=s.endsWith("/npv_iran")?FeedParser.latestNapster(body,"npv_iran",3):FeedParser.parse(body,s);
     if(entries.isEmpty())throw new IOException("No matching public configs or channel posts");
    }catch(Exception directError){
     if(!s.startsWith("https://t.me/s/"))throw directError;
     JSONObject mirror=new JSONObject(get("https://raw.githubusercontent.com/mohsenezare/Vpn/main/feeds/"+s.substring(s.lastIndexOf('/')+1)+".json"));
     retrievedAt=mirror.getLong("updated");
     if(System.currentTimeMillis()-retrievedAt>259200000L)throw new IOException("Public mirror is older than 72 hours");
     JSONArray data=mirror.getJSONArray("entries");entries=new ArrayList<>();
     for(int i=0;i<Math.min(350,data.length());i++){
      JSONObject o=data.getJSONObject(i);String kind=o.getString("k"),v=o.getString("v");
      if(kind.equals("NAPSTERNETV")&&v.matches("https://t\\.me/[A-Za-z0-9_]+/[0-9]+"))entries.add(new FeedParser.Entry(kind,v,s));
      else entries.addAll(FeedParser.parse(v,s));
     }
     if(s.endsWith("/npv_iran")){
      entries.removeIf(e->!e.kind.equals("NAPSTERNETV"));
      entries.sort((a,b)->Long.compare(FeedParser.postId(b.value),FeedParser.postId(a.value)));
      entries=new ArrayList<>(entries.subList(0,Math.min(3,entries.size())));
     }
     if(entries.isEmpty())throw new IOException("No valid configs in public mirror");
    }
    if(s.endsWith("/npv_iran")){
     entries.sort((a,b)->Long.compare(FeedParser.postId(b.value),FeedParser.postId(a.value)));
     entries=new ArrayList<>(entries.subList(0,Math.min(3,entries.size())));
    }
    JSONArray a=new JSONArray();
    for(FeedParser.Entry e:entries){JSONObject o=new JSONObject();o.put("k",e.kind);o.put("v",e.value);a.put(o);}
    context.getSharedPreferences("hub",0).edit().putString(s,a.toString()).putLong(s+"time",retrievedAt).putString(s+"error","").apply();
   }catch(Exception e){context.getSharedPreferences("hub",0).edit().putString(s+"error",e.getMessage()==null?"Network error":e.getMessage()).apply();}
   return null;
  });
  try{pool.invokeAll(jobs,90,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{pool.shutdownNow();context.getSharedPreferences("hub",0).edit().putLong("attempt",System.currentTimeMillis()).apply();}
 }
 List<FeedParser.Entry> entries(String kind){
  LinkedHashMap<String,FeedParser.Entry> result=new LinkedHashMap<>();
  for(String s:sources()){
   // Keep the last successful cache for 72 h, including during feed failures.
   if(System.currentTimeMillis()-context.getSharedPreferences("hub",0).getLong(s+"time",0)>259200000L)continue;
   try{JSONArray a=new JSONArray(context.getSharedPreferences("hub",0).getString(s,"[]"));
    for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(o.getString("k").equals(kind)&&(!kind.equals("NAPSTERNETV")||s.endsWith("/npv_iran")))
     result.put(o.getString("v"),new FeedParser.Entry(kind,o.getString("v"),s));}
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
