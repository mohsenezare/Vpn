package com.aegis.vpn;
import android.net.Uri;
import java.net.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
final class EndpointProbe {
 final Map<String,Long> latency=new ConcurrentHashMap<>();
 volatile boolean busy;
 void test(List<FeedParser.Entry> entries,Runnable done){
  if(busy)return;busy=true;
  new Thread(()->{
   ExecutorService pool=Executors.newFixedThreadPool(8);
   List<Callable<Void>> jobs=new ArrayList<>();
   // Probe a broad sample. This only checks a TCP handshake, not VPN auth.
   // Smart mode will still use sing-box URLTest for real outbound HTTP reachability.
   LinkedHashMap<String,FeedParser.Entry> unique=new LinkedHashMap<>();
   for(FeedParser.Entry e:entries){
    try{
     String host;
     if(e.kind.equals("PROXY"))host=Uri.parse(e.value).getQueryParameter("server");
     else host=SingBoxConfig.outbound(e.value).getString("server");
     if(host!=null&&!host.isEmpty())unique.putIfAbsent(host+":"+e.kind,e);
    }catch(Exception ignored){}
   }
   for(FeedParser.Entry e:new ArrayList<>(unique.values()).subList(0,Math.min(120,unique.size())))jobs.add(()->{
    try{
     String host;int port;
     if(e.kind.equals("PROXY")){
      Uri uri=Uri.parse(e.value);
      host=uri.getQueryParameter("server");port=Integer.parseInt(uri.getQueryParameter("port"));
     }else{
      JSONObject config=SingBoxConfig.outbound(e.value);
      host=config.getString("server");port=config.getInt("server_port");
     }
     if(host==null||port<1||port>65535)return null;
     InetAddress address=InetAddress.getByName(host);
     if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return null;
     long start=android.os.SystemClock.elapsedRealtime();
     try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(address,port),1500);latency.put(e.value,android.os.SystemClock.elapsedRealtime()-start);}
    }catch(Exception ex){latency.put(e.value,-1L);}return null;
   });
   try{pool.invokeAll(jobs,32,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
   finally{pool.shutdownNow();busy=false;new android.os.Handler(android.os.Looper.getMainLooper()).post(done);}
  },"endpoint-probe").start();
 }
 String label(String value){Long ms=latency.get(value);return ms==null?"Not tested":ms<0?"TCP unreachable":"TCP "+ms+" ms";}
 long rank(String value){Long ms=latency.get(value);return ms==null?Long.MAX_VALUE-1:ms<0?Long.MAX_VALUE:ms;}
}
