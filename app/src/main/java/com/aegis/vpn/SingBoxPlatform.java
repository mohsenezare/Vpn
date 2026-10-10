package com.aegis.vpn;

import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import android.system.OsConstants;
import android.util.Base64;
import android.util.Log;
import io.nekohasekai.libbox.*;
import java.net.*;
import java.security.KeyStore;
import java.util.*;

/** Implements the upstream sing-box libbox v1.12.22 Android platform contract. */
final class SingBoxPlatform implements PlatformInterface {
    private final Context context;
    private final SingVpnService vpn;
    private final ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    SingBoxPlatform(Context service){
        context=service;
        vpn=service instanceof SingVpnService?(SingVpnService)service:null;
        connectivity=(ConnectivityManager)service.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
    @Override public boolean usePlatformAutoDetectInterfaceControl(){return vpn!=null;}
    @Override public void autoDetectInterfaceControl(int fd){
        if(vpn!=null&&!vpn.protect(fd)) Log.w("AegisSingBox","Failed to protect outbound socket "+fd);
    }
    @Override public int openTun(TunOptions options) throws Exception{if(vpn==null)throw new IllegalStateException("Probe cannot open a VPN tunnel");return vpn.openTun(options);}
    @Override public boolean useProcFS(){return Build.VERSION.SDK_INT<29;}
    @Override public int findConnectionOwner(int protocol,String src,int sPort,String dst,int dPort){
        if(Build.VERSION.SDK_INT>=29)try{
            return connectivity.getConnectionOwnerUid(protocol,new InetSocketAddress(src,sPort),new InetSocketAddress(dst,dPort));
        }catch(Exception ignored){}
        return -1;
    }
    @Override public String packageNameByUid(int uid){
        String[] packages=context.getPackageManager().getPackagesForUid(uid);
        return packages==null||packages.length==0?"":packages[0];
    }
    @Override public int uidByPackageName(String name){
        try{return context.getPackageManager().getApplicationInfo(name,0).uid;}
        catch(PackageManager.NameNotFoundException e){return -1;}
    }
    @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener){
        if(callback!=null)return;
        callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){updateDefault(listener,n);}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties lp){updateDefault(listener,n);}
            @Override public void onLost(Network n){updateDefault(listener,connectivity.getActiveNetwork());}
        };
        connectivity.registerDefaultNetworkCallback(callback);
        updateDefault(listener,connectivity.getActiveNetwork());
    }
    @Override public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener){
        if(callback!=null){
            try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}
            callback=null;
        }
    }
    private void updateDefault(InterfaceUpdateListener listener,Network network){
        if(network==null){listener.updateDefaultInterface("",-1,false,false);return;}
        LinkProperties lp=connectivity.getLinkProperties(network);
        NetworkCapabilities caps=connectivity.getNetworkCapabilities(network);
        String name=lp==null?"":lp.getInterfaceName();
        if(name==null)name="";
        int index=-1;
        try{
            java.net.NetworkInterface ni=java.net.NetworkInterface.getByName(name);
            if(ni!=null)index=ni.getIndex();
        }catch(Exception ignored){}
        boolean metered=caps==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        listener.updateDefaultInterface(name,index,metered,false);
    }
    @Override public NetworkInterfaceIterator getInterfaces(){
        ArrayList<io.nekohasekai.libbox.NetworkInterface> list=new ArrayList<>();
        for(Network network:connectivity.getAllNetworks()){
            LinkProperties lp=connectivity.getLinkProperties(network);
            NetworkCapabilities caps=connectivity.getNetworkCapabilities(network);
            if(lp==null||caps==null||lp.getInterfaceName()==null)continue;
            try{
                java.net.NetworkInterface ni=java.net.NetworkInterface.getByName(lp.getInterfaceName());
                if(ni==null)continue;
                io.nekohasekai.libbox.NetworkInterface n=new io.nekohasekai.libbox.NetworkInterface();
                n.setName(ni.getName());
                n.setIndex(ni.getIndex());
                n.setMTU(ni.getMTU());
                n.setType(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?Libbox.InterfaceTypeWIFI:
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?Libbox.InterfaceTypeCellular:
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?Libbox.InterfaceTypeEthernet:Libbox.InterfaceTypeOther);
                int flags=0;
                if(ni.isUp())flags|=OsConstants.IFF_UP|OsConstants.IFF_RUNNING;
                if(ni.isLoopback())flags|=OsConstants.IFF_LOOPBACK;
                if(ni.isPointToPoint())flags|=OsConstants.IFF_POINTOPOINT;
                if(ni.supportsMulticast())flags|=OsConstants.IFF_MULTICAST;
                n.setFlags(flags);
                n.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                ArrayList<String> addresses=new ArrayList<>();
                for(InterfaceAddress a:ni.getInterfaceAddresses()){
                    if(a.getAddress()!=null){
                        String addr=a.getAddress().getHostAddress();
                        if(addr.contains("%"))addr=addr.substring(0,addr.indexOf('%'));
                        addresses.add(addr+"/"+a.getNetworkPrefixLength());
                    }
                }
                ArrayList<String> dns=new ArrayList<>();
                for(InetAddress a:lp.getDnsServers())dns.add(a.getHostAddress());
                n.setAddresses(new Strings(addresses));
                n.setDNSServer(new Strings(dns));
                list.add(n);
            }catch(Exception e){Log.w("AegisSingBox","Skipping network interface",e);}
        }
        return new NetworkInterfaceIterator(){
            int i=0;
            @Override public boolean hasNext(){return i<list.size();}
            @Override public io.nekohasekai.libbox.NetworkInterface next(){return list.get(i++);}
        };
    }
    @Override public boolean underNetworkExtension(){return false;}
    @Override public boolean includeAllNetworks(){return false;}
    @Override public WIFIState readWIFIState(){return null;}
    @Override public void clearDNSCache(){}
    @Override public LocalDNSTransport localDNSTransport(){
        return new LocalDNSTransport(){
            @Override public boolean raw(){return false;}
            @Override public void lookup(ExchangeContext ctx,String network,String domain){
                try{
                    InetAddress[] results=InetAddress.getAllByName(domain);
                    ArrayList<String> ips=new ArrayList<>();
                    for(InetAddress ip:results)ips.add(ip.getHostAddress());
                    ctx.success(android.text.TextUtils.join("\n",ips));
                }catch(Exception e){ctx.errorCode(3);}
            }
            @Override public void exchange(ExchangeContext ctx,byte[] packet){ctx.errorCode(0);}
        };
    }
    @Override public void sendNotification(io.nekohasekai.libbox.Notification msg){}
    @Override public void writeLog(String msg){
        Log.d("AegisSingBox",msg.replaceAll("(?i)(password|uuid|token)[=:]\\S+","$1=[hidden]"));
    }
    @Override public StringIterator systemCertificates(){
        ArrayList<String> pem=new ArrayList<>();
        try{
            KeyStore store=KeyStore.getInstance("AndroidCAStore");store.load(null);
            Enumeration<String> aliases=store.aliases();
            while(aliases.hasMoreElements()){
                java.security.cert.Certificate c=store.getCertificate(aliases.nextElement());
                if(c!=null)pem.add("-----BEGIN CERTIFICATE-----\n"+
                    Base64.encodeToString(c.getEncoded(),Base64.NO_WRAP)+"\n-----END CERTIFICATE-----");
            }
        }catch(Exception e){Log.w("AegisSingBox","Could not list Android CA store",e);}
        return new Strings(pem);
    }
    void shutdown(){closeDefaultInterfaceMonitor(null);}
    private static final class Strings implements StringIterator{
        private final List<String> items;private int cursor;
        Strings(List<String> values){items=values;}
        @Override public int len(){return items.size();}
        @Override public boolean hasNext(){return cursor<items.size();}
        @Override public String next(){return items.get(cursor++);}
    }
}
