package com.aegis.vpn;

import android.net.*;
import android.content.Context;
import io.nekohasekai.libbox.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Fresh HTTPS measurements through each proxy; no TCP ping or fabricated values. */
final class ProxyScanner {
 static final ConcurrentHashMap<String,Long> results=new ConcurrentHashMap<>();
 static volatile boolean busy,cancelled;
 static volatile int total,done,working;
 static volatile String best,summary="Update five sources to test",networkId="";
 static String network(Context c){Network n=((ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();return n==null?"":n.toString();}
 static long rank(String link){Long n=results.get(link);return n!=null&&n>0?n:Long.MAX_VALUE;}
 static String label(String link){Long n=results.get(link);return n==null?"Not tested":n==-2?"Unsupported config":n<0?"HTTPS test failed":n+" ms · HTTPS";}
 static String progress(){return busy?"Testing "+done+" / "+total+" · "+working+" working":summary;}
 static void cancel(){cancelled=true;}
 static void run(SingVpnService service,List<FeedParser.Entry> entries,Runnable progress){
  busy=true;cancelled=false;results.clear();best=null;done=0;working=0;total=entries.size();networkId=network(service);
  ExecutorService pool=Executors.newFixedThreadPool(6);
  AtomicInteger cursor=new AtomicInteger();
  try{
   List<Callable<Void>> jobs=new ArrayList<>();
   for(int t=0;t<6;t++)jobs.add(()->{
    while(!cancelled){
     int i=cursor.getAndIncrement();if(i>=entries.size())break;
     if(!networkId.equals(network(service))){cancelled=true;break;}
     String link=entries.get(i).value;
     BoxService core=null;SingBoxPlatform platform=null;long delay=-1;
     try{
      String config;
      try{config=SingBoxConfig.probeConfig(link);Libbox.checkConfig(config);}catch(Exception invalid){results.put(link,-2L);continue;}
      platform=new SingBoxPlatform(service);core=Libbox.newService(config,platform);core.start();
      delay=core.measureOutbound("proxy",6000);
     }catch(Exception failure){delay=-1;}
     finally{
      if(core!=null)try{core.close();}catch(Exception ignored){}
      if(platform!=null)platform.shutdown();
      results.putIfAbsent(link,delay);
      synchronized(ProxyScanner.class){done++;if(delay>0)working++;}
      progress.run();
     }
    }
    return null;
   });
   for(Future<Void> f:pool.invokeAll(jobs))f.get();
   if(!cancelled&&networkId.equals(network(service))){
    for(FeedParser.Entry e:entries)if(rank(e.value)<rank(best==null?"":best))best=e.value;
    if(best!=null){new ProfileStore(service).saveNative(best);summary="Best selected · "+rank(best)+" ms · "+working+" working";}
    else{new ProfileStore(service).clearNative();summary="No proxy passed the HTTPS test";}
   }else {best=null;summary="Test cancelled · no automatic selection";}
  }catch(Exception e){summary="Test interrupted · retry";best=null;}
  finally{pool.shutdownNow();busy=false;progress.run();}
 }
}
