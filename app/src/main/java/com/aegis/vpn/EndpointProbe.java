package com.aegis.vpn;
import android.net.Uri;
import org.json.JSONObject;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Device-side TCP reachability sampling. Real proxy health still uses sing-box urltest. */
final class EndpointProbe {
 final Map<String,Long> latency=new ConcurrentHashMap<>();
 volatile boolean busy;

 // Fair sample across distinct feeds: otherwise the first 40 rows from one
 // publisher crowd out the user's FAST subscription entirely.
 private static List<FeedParser.Entry> sample(List<FeedParser.Entry> all){
  LinkedHashMap<String,ArrayDeque<FeedParser.Entry>> groups=new LinkedHashMap<>();
  HashSet<String> seen=new HashSet<>();
  for(FeedParser.Entry e:all){
   if(!seen.add(e.value))continue;
   groups.computeIfAbsent(e.source,k->new ArrayDeque<>()).addLast(e);
  }
  ArrayList<FeedParser.Entry> chosen=new ArrayList<>();
  boolean added=true;
  while(added&&chosen.size()<120){
   added=false;
   for(ArrayDeque<FeedParser.Entry> group:groups.values()){
    FeedParser.Entry e=group.pollFirst();
    if(e==null)continue;
    chosen.add(e);added=true;
    if(chosen.size()>=120)break;
   }
  }
  return chosen;
 }

 void test(List<FeedParser.Entry> entries,Runnable done){
  if(busy)return;busy=true;
  final ArrayList<FeedParser.Entry> selected=new ArrayList<>(sample(entries));
  new Thread(()->{
   ExecutorService pool=Executors.newFixedThreadPool(8);
   List<Callable<Void>> jobs=new ArrayList<>();
   for(FeedParser.Entry e:selected)jobs.add(()->{
    try{
     String host;int port;
     if(e.kind.equals("PROXY")){
      Uri uri=Uri.parse(e.value);
      host=uri.getQueryParameter("server");
      port=Integer.parseInt(uri.getQueryParameter("port"));
     }else{
      // Works for VLESS, VMess, Trojan, SS and Hysteria 2; Uri.getHost()
      // alone is insufficient for Base64-encoded VMess and Shadowsocks.
      JSONObject proxy=SingBoxConfig.outbound(e.value);
      host=proxy.getString("server");
      port=proxy.getInt("server_port");
     }
     if(host==null||host.isEmpty()||port<1||port>65535){
      latency.put(e.value,-1L);return null;
     }
     InetAddress address=InetAddress.getByName(host);
     if(address.isAnyLocalAddress()||address.isLoopbackAddress()
        ||address.isLinkLocalAddress()||address.isSiteLocalAddress()
        ||address.isMulticastAddress()){
      latency.put(e.value,-1L);return null;
     }
     long start=android.os.SystemClock.elapsedRealtime();
     try(Socket socket=new Socket()){
      socket.connect(new InetSocketAddress(address,port),1600);
      latency.put(e.value,Math.max(1L,android.os.SystemClock.elapsedRealtime()-start));
     }
    }catch(Exception ex){latency.put(e.value,-1L);}
    return null;
   });
   try{pool.invokeAll(jobs,35,TimeUnit.SECONDS);}
   catch(InterruptedException ex){Thread.currentThread().interrupt();}
   finally{
    pool.shutdownNow();
    busy=false;
    if(done!=null)new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
   }
  },"endpoint-probe").start();
 }
 String label(String value){
  Long ms=latency.get(value);
  return ms==null?"Not tested":ms<0?"TCP unreachable":"TCP "+ms+" ms";
 }
 long rank(String value){
  Long ms=latency.get(value);
  return ms==null?Long.MAX_VALUE-1:ms<0?Long.MAX_VALUE:ms;
 }
}
