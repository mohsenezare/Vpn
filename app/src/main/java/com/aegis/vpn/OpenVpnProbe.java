package com.aegis.vpn;
import android.os.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual on-device TCP connect timing. UDP OpenVPN latency cannot be safely
 * inferred from a TCP handshake, and a successful TCP handshake is NOT
 * evidence that the OpenVPN protocol authenticated. */
final class OpenVpnProbe {
    final ConcurrentHashMap<String,Long> elapsed=new ConcurrentHashMap<>();
    volatile boolean busy;
    String key(FreeDirectory.Node n){
        InetSocketAddress address=FreeDirectory.tcpEndpoint(n);
        return address==null?"":address.getHostString()+":"+address.getPort();
    }
    long ms(FreeDirectory.Node n){
        if(key(n).isEmpty())return -3;
        Long t=elapsed.get(key(n));
        return t==null?-2:t;
    }
    String label(FreeDirectory.Node n){
        long nms=ms(n);
        return nms==-3?"UDP · unknown":nms==-2?"Tap test":nms<0?"Unreachable":nms+" ms TCP";
    }
    void run(List<FreeDirectory.Node> list,Runnable finished){
        if(busy)return;busy=true;
        new Thread(()->{
            ExecutorService pool=Executors.newFixedThreadPool(9);
            ArrayList<Callable<Void>> jobs=new ArrayList<>();
            LinkedHashMap<String,InetSocketAddress> addresses=new LinkedHashMap<>();
            for(FreeDirectory.Node n:list){
                String k=key(n);
                if(!k.isEmpty())addresses.putIfAbsent(k,FreeDirectory.tcpEndpoint(n));
                if(addresses.size()>=60)break;
            }
            for(Map.Entry<String,InetSocketAddress> item:addresses.entrySet())jobs.add(()->{
                try{
                    InetSocketAddress addr=item.getValue();
                    InetAddress ip=InetAddress.getByName(addr.getHostString());
                    if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isSiteLocalAddress()||
                        ip.isLinkLocalAddress()||ip.isMulticastAddress())
                        throw new Exception("Not a public endpoint");
                    long before=SystemClock.elapsedRealtime();
                    try(Socket socket=new Socket()){
                        socket.connect(new InetSocketAddress(ip,addr.getPort()),1700);
                        elapsed.put(item.getKey(),SystemClock.elapsedRealtime()-before);
                    }
                }catch(Exception e){elapsed.put(item.getKey(),-1L);}
                return null;
            });
            try{pool.invokeAll(jobs,22,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally{
                pool.shutdownNow();busy=false;
                new Handler(Looper.getMainLooper()).post(()->{if(finished!=null)finished.run();});
            }
        },"openvpn-tcp-probes").start();
    }
}
