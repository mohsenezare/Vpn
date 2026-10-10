package com.aegis.vpn;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.util.Log;
import io.nekohasekai.libbox.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Actual Android VpnService using an in-APK libbox native engine.
 * TUN established is not proof an upstream proxy is reachable.
 * Never report connectivity from the count of downloaded configuration URLs.
 */
public final class SingVpnService extends VpnService {
    public static final String ACTION_SCAN="com.aegis.vpn.SCAN";
    public static final String ACTION_START="com.aegis.vpn.SING_START";
    public static final String ACTION_STOP="com.aegis.vpn.SING_STOP";
    public static final String ACTION_STATUS="com.aegis.vpn.SING_STATUS";
    public static final String EXTRA_CONFIG="config";
    private static final String CHANNEL="aegis-native-tunnel";
    public static final int OFF=0,STARTING=1,TUNNEL_ACTIVE=2,FAILED=3;
    public static volatile int state=OFF;
    public static volatile String lastError="";
    private ExecutorService worker=Executors.newSingleThreadExecutor();
    private ParcelFileDescriptor tun;
    private BoxService core;
    private SingBoxPlatform platform;
    private volatile boolean stopping;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Aegis VPN tunnel",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_STOP.equals(intent.getAction())){
            ProxyScanner.cancel();stopping=true;worker.execute(()->{cleanup();state=OFF;broadcast();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
            return START_NOT_STICKY;
        }
        if(ACTION_SCAN.equals(intent.getAction())){
            if(ProxyScanner.busy)return START_NOT_STICKY;
            ProxyScanner.busy=true;
            startForeground(91,notification("Testing V2Ray configurations…"));
            worker.execute(()->{
                try{
                    ProxyScanner.run(this,new SourceHub(this).entries("V2RAY"),()->{
                        Intent update=new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("scan",true);
                        sendBroadcast(update);
                    });
                }catch(Exception e){ProxyScanner.busy=false;ProxyScanner.summary="Unable to start tests";sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("scan",true));}
                if(core==null&&state!=STARTING){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}
                else updateNotification("VPN tunnel active");
            });
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(intent.getAction()))return START_NOT_STICKY;
        final String config=intent.getStringExtra(EXTRA_CONFIG);
        if(config==null||config.length()<50||config.length()>128000){
            lastError="Invalid or missing sing-box config";state=FAILED;broadcast();stopSelf();return START_NOT_STICKY;
        }
        startForeground(91,notification("Starting encrypted tunnel…"));
        state=STARTING;lastError="";stopping=false;broadcast();
        worker.execute(()->{
            try{
                if(VpnService.prepare(this)!=null)throw new IllegalStateException("VPN permission not granted");
                cleanup();
                setupCore();
                platform=new SingBoxPlatform(this);
                core=Libbox.newService(config,platform);
                core.start();
                if(stopping){cleanup();return;}
                if(tun==null)throw new IllegalStateException("Core started but VPN TUN was not created");
                state=TUNNEL_ACTIVE;broadcast();updateNotification("V2Ray tunnel active");
            }catch(Throwable e){
                Log.e("AegisSingBox","Core start failed",e);
                lastError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
                state=FAILED;broadcast();cleanup();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            }
        });
        return START_NOT_STICKY;
    }
    private void setupCore()throws Exception {
                java.io.File working=new java.io.File(getFilesDir(),"singbox");
                working.mkdirs();
                SetupOptions options=new SetupOptions();
                options.setBasePath(getFilesDir().getAbsolutePath());
                options.setWorkingPath(working.getAbsolutePath());
                options.setTempPath(getCacheDir().getAbsolutePath());
                Libbox.setup(options);
    }
    private android.app.Notification notification(String status){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,SingVpnService.class).setAction(ACTION_STOP);
        PendingIntent action=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new android.app.Notification.Builder(this,CHANNEL)
            .setContentTitle("Aegis VPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_aegis_status)
            .setOngoing(true)
            .setContentIntent(content)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Disconnect",action)
            .build();
    }
    private void updateNotification(String msg){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(91,notification(msg));
    }
    private void broadcast(){
        AegisShortcuts.nativeStatusChanged(this,state);
        Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra("state",state);i.putExtra("message",lastError);
        sendBroadcast(i);
    }
    public int openTun(TunOptions opts)throws Exception{
        VpnService.Builder b=new Builder().setSession("Aegis VPN").setMtu(Math.max(1280,opts.getMTU()));
        RoutePrefixIterator ip4=opts.getInet4Address();
        boolean got4=false;
        while(ip4.hasNext()){RoutePrefix p=ip4.next();b.addAddress(p.address(),p.prefix());got4=true;}
        RoutePrefixIterator ip6=opts.getInet6Address();
        boolean got6=false;
        while(ip6.hasNext()){RoutePrefix p=ip6.next();b.addAddress(p.address(),p.prefix());got6=true;}
        if(opts.getAutoRoute()){
            try{
                String dns=opts.getDNSServerAddress().getValue();
                if(dns!=null&&!dns.isEmpty())b.addDnsServer(dns);
            }catch(Exception ignored){b.addDnsServer("1.1.1.1");}
            // Route ranges are precomputed by libbox to avoid excluded routes.
            RoutePrefixIterator inet4=opts.getInet4RouteRange();
            boolean routed4=false;
            while(inet4.hasNext()){RoutePrefix p=inet4.next();b.addRoute(p.address(),p.prefix());routed4=true;}
            if(got4&&!routed4)b.addRoute("0.0.0.0",0);
            RoutePrefixIterator inet6=opts.getInet6RouteRange();
            boolean routed6=false;
            while(inet6.hasNext()){RoutePrefix p=inet6.next();b.addRoute(p.address(),p.prefix());routed6=true;}
            if(got6&&!routed6)b.addRoute("::",0);
        }
        StringIterator excluded=opts.getExcludePackage();
        while(excluded.hasNext())try{b.addDisallowedApplication(excluded.next());}catch(Exception ignored){}
        StringIterator included=opts.getIncludePackage();
        while(included.hasNext())try{b.addAllowedApplication(included.next());}catch(Exception ignored){}
        // Preserve the control plane: requests made by Aegis (e.g. config updates)
        // do not recursively re-enter its own tunnel.
        try{b.addDisallowedApplication(getPackageName());}catch(Exception ignored){}
        if(Build.VERSION.SDK_INT>=29)b.setMetered(false);
        ParcelFileDescriptor descriptor=b.establish();
        if(descriptor==null)throw new IllegalStateException("Android rejected VPN TUN creation");
        tun=descriptor;
        return descriptor.getFd();
    }
    private void cleanup(){
        try{if(core!=null)core.close();}catch(Exception ignored){}
        core=null;
        if(platform!=null){platform.shutdown();platform=null;}
        try{if(tun!=null)tun.close();}catch(Exception ignored){}
        tun=null;
    }
    @Override public void onRevoke(){
        ProxyScanner.cancel();stopping=true;
        worker.execute(()->{cleanup();state=OFF;broadcast();stopSelf();});
        super.onRevoke();
    }
    @Override public void onDestroy(){
        ProxyScanner.cancel();stopping=true;cleanup();state=OFF;broadcast();
        worker.shutdownNow();super.onDestroy();
    }
}
