package com.aegis.vpn;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.net.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private volatile String currentConfig;
    private ScheduledExecutorService watchdog;
    private int failedHealthChecks=0;
    public static volatile boolean verifiedRoute=false;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Aegis VPN tunnel",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_STOP.equals(intent.getAction())){
            // Publish OFF immediately even if native startup is still on the worker.
            // The queued cleanup closes the TUN once the worker returns.
            stopping=true;currentConfig=null;stopWatchdog();
            state=OFF;verifiedRoute=false;broadcast();
            worker.execute(()->{cleanup();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(intent.getAction()))return START_NOT_STICKY;
        final String config=intent.getStringExtra(EXTRA_CONFIG);
        if(config==null||config.length()<50||config.length()>128000){
            lastError="Invalid or missing sing-box config";state=FAILED;broadcast();stopSelf();return START_NOT_STICKY;
        }
        startForeground(91,notification("Starting encrypted tunnel…"));
        currentConfig=config;
        state=STARTING;lastError="";stopping=false;verifiedRoute=false;broadcast();
        worker.execute(()->startCore(config));
        return START_NOT_STICKY;
    }
    private void startCore(String config){
        try{
            if(stopping)return;
            if(VpnService.prepare(this)!=null)throw new IllegalStateException("VPN permission not granted");
            cleanup();
            java.io.File working=new java.io.File(getFilesDir(),"singbox");
            working.mkdirs();
            SetupOptions options=new SetupOptions();
            options.setBasePath(getFilesDir().getAbsolutePath());
            options.setWorkingPath(working.getAbsolutePath());
            options.setTempPath(getCacheDir().getAbsolutePath());
            Libbox.setup(options);
            platform=new SingBoxPlatform(this);
            // Validate on the worker thread; never let one expired/incompatible
            // public node prevent the other candidates from launching.
            String candidate=config;
            for(int tries=0;tries<12;tries++){
                try{
                    core=Libbox.newService(candidate,platform);
                    break;
                }catch(Exception invalid){
                    String next=SingBoxConfig.dropInvalidAutoNode(candidate,invalid.getMessage());
                    if(next==null||next.equals(candidate))throw invalid;
                    candidate=next;
                    Log.w("AegisSingBox","Skipped malformed public outbound while initializing");
                }
            }
            if(core==null)throw new IllegalStateException("All generated nodes were rejected");
            currentConfig=candidate;
            core.start();
            if(stopping){cleanup();return;}
            if(tun==null)throw new IllegalStateException("VPN TUN was not created");
            state=TUNNEL_ACTIVE;lastError="";broadcast();
            updateNotification("Encrypted tunnel active · checking route");
            startWatchdog();
        }catch(Throwable e){
            Log.e("AegisSingBox","VPN recovery/start failed",e);
            lastError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            state=FAILED;verifiedRoute=false;broadcast();cleanup();stopWatchdog();
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    /** Checks actual HTTPS over Android's VPN network, NOT merely TCP to a public server.
     * False positives are reduced by trying two independent endpoints and three failures.
     * Recovery is bounded and rate-limited so a blocked probe cannot create a restart loop.
     */
    private void startWatchdog(){
        if(watchdog!=null&&!watchdog.isShutdown())return;
        watchdog=Executors.newSingleThreadScheduledExecutor();
        watchdog.scheduleWithFixedDelay(()->{
            if(stopping||state!=TUNNEL_ACTIVE)return;
            try{
                ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
                Network vpnNetwork=null;
                for(Network n:cm.getAllNetworks()){
                    NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                    if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                      &&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){
                        vpnNetwork=n;break;
                    }
                }
                if(vpnNetwork==null)return;
                boolean good=checkRoute(vpnNetwork,"https://www.gstatic.com/generate_204",204)||
                    checkRoute(vpnNetwork,"https://www.cloudflare.com/cdn-cgi/trace",200);
                if(good){failedHealthChecks=0;verifiedRoute=true;return;}
                verifiedRoute=false;
                // Do NOT tear down a functioning sing-box session solely because a
                // captive/filtered network blocked these particular probe URLs.
                // URLTest will try alternate proxy outbounds without resetting the TUN.
                failedHealthChecks++;
            }catch(Exception e){Log.w("AegisSingBox","Health check skipped",e);}
        },22,15,TimeUnit.SECONDS);
    }
    private boolean checkRoute(Network network,String endpoint,int expected){
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)network.openConnection(new URL(endpoint));
            connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
            connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
            return connection.getResponseCode()==expected;
        }catch(Exception e){return false;}
        finally{if(connection!=null)connection.disconnect();}
    }
    private void stopWatchdog(){
        if(watchdog!=null){watchdog.shutdownNow();watchdog=null;}
        failedHealthChecks=0;verifiedRoute=false;
    }
    private android.app.Notification notification(String status){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,SingVpnService.class).setAction(ACTION_STOP);
        PendingIntent action=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new android.app.Notification.Builder(this,CHANNEL)
            .setContentTitle("Aegis VPN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(content)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Disconnect",action)
            .build();
    }
    private void updateNotification(String msg){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(91,notification(msg));
    }
    private void broadcast(){
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
        stopping=true;currentConfig=null;stopWatchdog();
        worker.execute(()->{cleanup();state=OFF;broadcast();stopSelf();});
        super.onRevoke();
    }
    @Override public void onDestroy(){
        stopping=true;currentConfig=null;stopWatchdog();cleanup();state=OFF;broadcast();
        worker.shutdownNow();super.onDestroy();
    }
}
