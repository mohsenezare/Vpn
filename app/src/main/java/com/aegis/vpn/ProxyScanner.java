package com.aegis.vpn;
import android.net.*;
import android.content.Context;
import java.util.*;
import java.util.concurrent.*;

/** One isolated native test worker; failures cannot abort the application's UI. */
final class ProxyScanner {
 static final ConcurrentHashMap<String,Long> results=new ConcurrentHashMap<>();
 static volatile boolean busy,cancelled;
 static volatile int total,done,working;
 static volatile String best,summary="Update five sources to test",networkId="";
 private static volatile ProbeClient activeClient;
 static String network(Context c){Network n=((ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();return n==null?"":n.toString();}
 static long rank(String link){Long n=results.get(link);return n!=null&&n>0?n:Long.MAX_VALUE;}
 static String label(String link){Long n=results.get(link);return n==null?"Not tested":n==-2?"Unsupported config":n==-3?"Test worker stopped":n<0?"HTTPS test failed":n+" ms · HTTPS";}
 static String progress(){return busy?"Testing "+done+" / "+total+" · "+working+" working":summary;}
 static void sort(List<FeedParser.Entry> entries){Map<String,Long> snapshot=new HashMap<>(results);entries.sort(Comparator.comparingLong(e->{Long n=snapshot.get(e.value);return n!=null&&n>0?n:Long.MAX_VALUE;}));}
 static void cancel(){cancelled=true;ProbeClient c=activeClient;if(c!=null)c.close();}
 static void run(SingVpnService service,List<FeedParser.Entry> entries,Runnable progress){
  busy=true;cancelled=false;results.clear();best=null;done=0;working=0;total=entries.size();networkId=network(service);
  ProbeClient client=new ProbeClient(service);activeClient=client;int crashes=0;boolean workerFailed=false;
  try{
   for(FeedParser.Entry e:entries){
    if(cancelled)break;
    if(!networkId.equals(network(service))){cancelled=true;break;}
    long delay;
    try{delay=client.test(SingBoxConfig.probeConfig(e.value));}catch(Exception invalid){delay=-2;}
    if(cancelled)break;
    results.put(e.value,delay);done++;if(delay>0)working++;
    progress.run();
    if(delay==-3){if(++crashes>=3){workerFailed=true;break;}}else crashes=0;
   }
   if(workerFailed){summary="Test engine stopped · retry update";}
   else if(!cancelled&&networkId.equals(network(service))){
    for(FeedParser.Entry e:entries)if(rank(e.value)<rank(best==null?"":best))best=e.value;
    if(best!=null){new ProfileStore(service).saveNative(best);summary="Best selected · "+rank(best)+" ms · "+working+" working";}
    else{new ProfileStore(service).clearNative();summary="No proxy passed the HTTPS test";}
   }else{best=null;summary="Test cancelled · no automatic selection";}
  }catch(Exception e){summary="Test interrupted · retry";best=null;}
  finally{client.close();activeClient=null;busy=false;progress.run();}
 }
}
