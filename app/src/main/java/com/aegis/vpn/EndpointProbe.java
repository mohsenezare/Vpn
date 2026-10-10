package com.aegis.vpn;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Physical TCP port checks, not a proxy login or proof of tunnel usability. */
final class EndpointProbe {
    final Map<String, Long> latency = new ConcurrentHashMap<>();
    volatile boolean busy;
    private static final int MAX_ENDPOINTS=160;

    private static final class Group {
        final String host;
        final int port;
        final ArrayList<String> configs=new ArrayList<>();
        Group(String host,int port){this.host=host;this.port=port;}
    }

    void test(List<FeedParser.Entry> entries, Runnable done) {
        if(busy)return;
        busy=true;
        final ArrayList<FeedParser.Entry> snapshot=new ArrayList<>(entries);
        new Thread(()->{
            ExecutorService pool=Executors.newFixedThreadPool(10);
            LinkedHashMap<String,Group> unique=new LinkedHashMap<>();
            try{
                for(FeedParser.Entry entry:snapshot){
                    try{
                        String host;int port;
                        if(entry.kind.equals("PROXY")){
                            Uri uri=Uri.parse(entry.value);
                            host=uri.getQueryParameter("server");
                            port=Integer.parseInt(uri.getQueryParameter("port"));
                        }else{
                            JSONObject outbound=SingBoxConfig.outbound(entry.value);
                            host=outbound.getString("server");
                            port=outbound.getInt("server_port");
                        }
                        if(host==null||host.isEmpty()||port<1||port>65535)continue;
                        String key=host.toLowerCase(java.util.Locale.ROOT)+":"+port;
                        Group group=unique.get(key);
                        if(group==null){
                            if(unique.size()>=MAX_ENDPOINTS)continue;
                            group=new Group(host,port);
                            unique.put(key,group);
                        }
                        group.configs.add(entry.value);
                    }catch(Exception ignored){}
                }
                latency.clear();
                ArrayList<Callable<Void>> jobs=new ArrayList<>();
                for(Group group:unique.values())jobs.add(()->{
                    long elapsed=-1L;
                    try{
                        InetAddress address=InetAddress.getByName(group.host);
                        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||
                           address.isSiteLocalAddress()||address.isLinkLocalAddress()||
                           address.isMulticastAddress())throw new IllegalArgumentException("Private endpoint");
                        long start=SystemClock.elapsedRealtimeNanos();
                        try(Socket socket=new Socket()){
                            socket.connect(new InetSocketAddress(address,group.port),1600);
                            elapsed=Math.max(1L,TimeUnit.NANOSECONDS.toMillis(
                                SystemClock.elapsedRealtimeNanos()-start));
                        }
                    }catch(Exception ignored){elapsed=-1L;}
                    for(String config:group.configs)latency.put(config,elapsed);
                    return null;
                });
                List<Future<Void>> results=pool.invokeAll(jobs,35,TimeUnit.SECONDS);
                int index=0;
                for(Group group:unique.values()){
                    if(results.get(index++).isCancelled())
                        for(String config:group.configs)latency.put(config,-1L);
                }
            }catch(InterruptedException ex){Thread.currentThread().interrupt();}
            finally{
                pool.shutdownNow();busy=false;
                new Handler(Looper.getMainLooper()).post(()->{if(done!=null)done.run();});
            }
        },"native-device-reachability").start();
    }

    String label(String value){
        Long measured=latency.get(value);
        return measured==null?"Not tested":measured<0?"TCP unreachable":"TCP "+measured+" ms";
    }
    long rank(String value){
        Long measured=latency.get(value);
        return measured==null?Long.MAX_VALUE-1:measured<0?Long.MAX_VALUE:measured;
    }
}
