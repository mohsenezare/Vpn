package com.aegis.vpn;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/**
 * iOS-inspired interface based on the user's approved reference.
 * Status is supplied by a real external OpenVPN tunnel, never a demo timer.
 */
public final class MainActivity extends Activity {
    private static final int PICK_OVPN = 813;
    private static final int PICK_NATIVE = 815;
    final int INK=0xff162025, MUTED=0xff7f8b98, ORANGE=0xffff7528, GREEN=0xff08bb78;
    private VpnController vpn;
    private ProfileStore profiles;
    private SecureLinks vault;
    private String selectedNativeCache="";
    private boolean smartNative=true;
    private FreeDirectory directory;
    private OpenVpnProbe openProbe;
    private Screen screen;
    private SourceHub hub;
    private HubPanel hubPanel;
    private int tab=0, selectedIndex=0;
    private boolean paidMode=false;
    private boolean preferNative=true;
    private int openVpnRetries=0;
    private boolean openProbeQueued=false;
    private String pendingNativeConfig;
    private static final int PREPARE_NATIVE=8292;
    private final BroadcastReceiver nativeEvents=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(screen!=null)screen.invalidate();
            if(intent.getIntExtra("state",0)==SingVpnService.FAILED)
                info("Embedded VPN error: "+intent.getStringExtra("message"));
        }
    };
    boolean updatingAll=false;
    private String error="";
    private ArrayList<FreeDirectory.Node> servers=new ArrayList<>();
    private Handler handler=new Handler(Looper.getMainLooper());
    // Android UID counters include the app's own background fetches, so rate is approximate.
    private long lastRx=-1,lastTx=-1,lastSample=0;
    private long startedAt=0;
    private boolean priorActive=false;
    private volatile long sampledRx=-1,sampledTx=-1;
    private volatile double downMbps=-1,upMbps=-1;
    private final Runnable meterTick=new Runnable(){
        @Override public void run(){
            boolean active=isTunnelOn()||isConnecting();
            if(active&&!priorActive)startedAt=android.os.SystemClock.elapsedRealtime();
            if(!active)startedAt=0;
            priorActive=active;
            if(SingVpnService.state==SingVpnService.TUNNEL_ACTIVE){
                long rx=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
                long tx=android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
                long now=android.os.SystemClock.elapsedRealtime();
                if(rx>=0&&tx>=0&&lastRx>=0&&now>lastSample){
                    downMbps=Math.max(0,(rx-lastRx)*8.0/(now-lastSample)/1000.0);
                    upMbps=Math.max(0,(tx-lastTx)*8.0/(now-lastSample)/1000.0);
                }
                sampledRx=rx;sampledTx=tx;
                lastRx=rx;lastTx=tx;lastSample=now;
            }else{downMbps=-1;upMbps=-1;lastRx=-1;lastTx=-1;lastSample=0;
                sampledRx=-1;sampledTx=-1;}
            if(screen!=null&&(active||tab==2))screen.invalidate();
            handler.postDelayed(this,1400);
        }
    };
    String rate(double n){return n<0?"—":String.format(java.util.Locale.US,"%.2f",n);}
    String uptime(){
        if(startedAt==0)return "—";
        long seconds=Math.max(0,(android.os.SystemClock.elapsedRealtime()-startedAt)/1000);
        return String.format(java.util.Locale.US,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(meterTick);handler.post(meterTick);}
    @Override protected void onPause(){handler.removeCallbacks(meterTick);super.onPause();}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xfffafafa);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(0xfff9fafb);
        profiles=new ProfileStore(this);
        vault=new SecureLinks(this);
        selectedNativeCache=vault.selected();
        directory=new FreeDirectory(this);
        openProbe=new OpenVpnProbe();
        servers=directory.load();
        paidMode=getPreferences(MODE_PRIVATE).getBoolean("paid_mode",false);
        preferNative=getPreferences(MODE_PRIVATE).getBoolean("prefer_native",true);
        smartNative=getPreferences(MODE_PRIVATE).getBoolean("smart_native",true);
        selectedIndex=getPreferences(MODE_PRIVATE).getInt("selected",0);
        vpn=new VpnController(this, () -> screen.invalidate(),this::handleOpenVpnFailure);
        hub=new SourceHub(this);hubPanel=new HubPanel(this,hub);
        screen=new Screen();
        setContentView(screen);
        if(!servers.isEmpty())openProbe.run(servers,this::reorderFreeServers);
        IntentFilter nativeFilter=new IntentFilter(SingVpnService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(nativeEvents,nativeFilter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(nativeEvents,nativeFilter);
        RefreshJob.schedule(this);
        if(hub.stale())hub.refresh(()->screen.invalidate());
        if(directory.isStale()) refresh(false);
    }
    @Override protected void onDestroy(){
        try{unregisterReceiver(nativeEvents);}catch(Exception ignored){}
        handler.removeCallbacks(meterTick);vpn.close();super.onDestroy();
    }
    @Override @Deprecated protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PREPARE_NATIVE){
            if(result==RESULT_OK&&pendingNativeConfig!=null){
                String config=pendingNativeConfig;pendingNativeConfig=null;startNativeService(config);
            }else{pendingNativeConfig=null;info("VPN permission was not granted.");}
            return;
        }
        if(request==PICK_NATIVE && result==RESULT_OK && data!=null && data.getData()!=null){
            importNativeFile(data.getData());return;
        }
        if(request==PICK_OVPN && result==RESULT_OK && data!=null && data.getData()!=null) {
            try{
                String ovpn=readLimited(data.getData());
                if(!ovpn.contains("client") || !ovpn.matches("(?s).*\\bremote\\s+[^\\s]+.*"))
                    throw new Exception("Select a valid .ovpn file containing client and remote directives.");
                showPaidCredentials(ovpn);
            } catch(Exception e){ info(e.getMessage());}
        } else vpn.onActivityResult(request,result);
    }
    void pickConfigFile(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick,PICK_NATIVE);
    }
    void importNativeFile(Uri uri){
        try{
            String name=uri.getLastPathSegment();
            if(name!=null&&name.toLowerCase(Locale.ROOT).matches(".*\\.(npv|npv4|npvt)$")){
                info("Encrypted NapsternetV files require their original decoder. Aegis supports readable share links and subscriptions instead.");
                return;
            }
            String body=readLimited(uri);
            if(body.indexOf(0)>=0)throw new IllegalArgumentException("Encrypted or binary input is unsupported");
            List<FeedParser.Entry> parsed=FeedParser.parse(body,"Imported file");
            int n=0;
            for(FeedParser.Entry e:parsed)if(e.kind.equals("V2RAY")&&SingBoxConfig.supported(e.value)){
                vault.put("V2RAY",e.value);n++;
            }
            if(n==0)info("No readable VLESS, VMess, Trojan, Shadowsocks or Hysteria2 links in file. Proprietary encrypted .npv files are not directly supported.");
            else{Toast.makeText(this,n+" VPN configurations imported securely",Toast.LENGTH_LONG).show();hubPanel.list("V2RAY");}
        }catch(Exception e){info("Could not import file: "+e.getMessage());}
    }
    void chooseSmartMode(){
        if(isTunnelOn()||isConnecting()){info("Disconnect the current tunnel before changing connection mode.");return;}
        smartNative=true;paidMode=false;preferNative=true;persist();screen.invalidate();
        Toast.makeText(this,"Smart Mode selected · swipe to connect",Toast.LENGTH_SHORT).show();
    }
    void selectManualNative(String link,boolean connectNow){
        try{
            if(!SingBoxConfig.supported(link))throw new IllegalArgumentException("Unsupported native configuration");
            String config=SingBoxConfig.build(link);
            vault.select(link);selectedNativeCache=link;smartNative=false;preferNative=true;paidMode=false;
            persist();screen.invalidate();
            if(connectNow){
                // Android supports one active VPN: close the old OpenVPN tunnel first.
                if(vpn.state!=VpnController.State.OFF){
                    vpn.disconnect();
                    handler.postDelayed(()->startManual(config),450);
                }else startManual(config);
            }else Toast.makeText(this,"Server selected · swipe to connect",Toast.LENGTH_SHORT).show();
        }catch(Exception e){info("Server config invalid: "+e.getMessage());}
    }
    private void startManual(String config){
        if(SingVpnService.state==SingVpnService.STARTING||SingVpnService.state==SingVpnService.TUNNEL_ACTIVE){
            // START with a different config reuses the native foreground service,
            // swapping after the old core is closed by the worker.
            startNativeService(config);
        }else connectNative(config);
    }
    /** Tries up to three distinct relays after confirmed failure, never a fake success.
     * Skip hosts whose TCP port was demonstrably unreachable on this device.
     * A paid account is never silently replaced with a volunteer relay. */
    void handleOpenVpnFailure(String why){
        if(paidMode||preferNative||servers.isEmpty()||
           why.startsWith("Install")||why.contains("permission")||
           why.contains("authorization")||why.contains("Could not start")){
            info(why);return;
        }
        if(openVpnRetries>=2){info("Free OpenVPN did not connect after 3 attempts. "+
            "Try a different network or use a working native configuration. Last error: "+why);return;}
        int next=-1;
        for(int i=selectedIndex+1;i<servers.size();i++){
            if(openProbe.ms(servers.get(i))!=-1){next=i;break;}
        }
        if(next<0){info("No additional reachable free OpenVPN ports. Last error: "+why);return;}
        openVpnRetries++;
        selectedIndex=next;persist();screen.invalidate();
        final String nextConfig=servers.get(next).config;
        Toast.makeText(this,"OpenVPN retry "+(openVpnRetries+1)+"/3 · next server",Toast.LENGTH_SHORT).show();
        handler.postDelayed(()->{
            if(!paidMode&&!preferNative&&vpn.state==VpnController.State.OFF)
                vpn.connect(nextConfig);
        },500);
    }
    void selectOpenVpnServer(int index){
        if(index<0||index>=servers.size())return;
        openVpnRetries=0;
        selectedIndex=index;paidMode=false;preferNative=false;persist();
        String ovpn=servers.get(index).config;
        if(SingVpnService.state==SingVpnService.TUNNEL_ACTIVE||SingVpnService.state==SingVpnService.STARTING){
            stopNative();
            handler.postDelayed(()->vpn.replace(ovpn),650);
        }else vpn.replace(ovpn);
        tab=0;screen.invalidate();
    }
    void quickBackup(){
        // Cloudflare's official Android app owns and manages its own tunnel.
        final String warp="com.cloudflare.onedotonedotonedotone";
        try{
            Intent launch=getPackageManager().getLaunchIntentForPackage(warp);
            if(launch!=null){startActivity(launch);return;}
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id="+warp)));
        }catch(Exception e){info("Could not open official Cloudflare WARP. Install its app from a trusted store.");}
    }
    void openBackup(){tab=3;screen.invalidate();}
    void openVpnLocations(){
        screen.locationMode=0;screen.listOffset=0;tab=1;screen.invalidate();
    }
    String chosenNative(){return selectedNativeCache;}
    void countryPicker(){
        final String[] codes={"","US","CA","FR","CH","DE","GB","NL","JP","SG","AU"};
        final String[] labels={"All available servers","United States · US","Canada · CA",
            "France · FR","Switzerland · CH","Germany · DE","United Kingdom · GB",
            "Netherlands · NL","Japan · JP","Singapore · SG","Australia · AU"};
        new GlassDialog.Builder(this).setTitle("V2Ray source country")
            .setSingleChoiceItems(labels,0,(d,index)->{
                screen.countryFilter=codes[index];
                screen.listCacheAt=0;screen.listOffset=0;screen.invalidate();d.dismiss();
            }).setNegativeButton("Close",null).show();
    }
    private String readLimited(Uri uri)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>524288) throw new Exception("OpenVPN profile exceeds 512 KB.");}
            return out.toString("UTF-8");
        }
    }
    void info(String msg){new GlassDialog.Builder(this).setMessage(msg==null?"Unknown error":msg).setPositiveButton("OK",null).show();}
    /** Reachable TCP handshakes rank first, unknown probes next, failed handshakes last.
     * None of these preflight tests proves OpenVPN authentication or data routing. */
    void reorderFreeServers(){
        if(servers.isEmpty())return;
        String current=servers.get(Math.min(selectedIndex,servers.size()-1)).config;
        boolean keepCurrent=isTunnelOn()||isConnecting();
        servers.sort((a,b)->{
            long am=openProbe.ms(a),bm=openProbe.ms(b);
            int at=am>=0?0:am==-2?1:am==-3?2:3;
            int bt=bm>=0?0:bm==-2?1:bm==-3?2:3;
            if(at!=bt)return Integer.compare(at,bt);
            if(at==0&&am!=bm)return Long.compare(am,bm);
            return Double.compare(FreeDirectory.rating(b),FreeDirectory.rating(a));
        });
        selectedIndex=0;
        if(keepCurrent){
            for(int i=0;i<servers.size();i++)
                if(servers.get(i).config.equals(current)){selectedIndex=i;break;}
        }
        persist();
        if(screen!=null)screen.invalidate();
        if(openProbeQueued){
            openProbeQueued=false;
            measureOpenVpn();
        }
    }
    void measureOpenVpn(){
        if(openProbe.busy){openProbeQueued=true;return;}
        openProbe.run(new ArrayList<>(servers),this::reorderFreeServers);
    }
    void refresh(boolean notify){
        if(notify) Toast.makeText(this,"Updating free VPN Gate servers...",Toast.LENGTH_SHORT).show();
        directory.update(list -> {
            servers=list;
            selectedIndex=0;
            screen.invalidate();
            measureOpenVpn();
            if(notify) Toast.makeText(this,list.size()+" free profiles; testing real TCP reachability",Toast.LENGTH_SHORT).show();
        }, err -> {if(notify)info("Directory refresh failed. Last saved servers retained.\n"+err);});
    }
    /** Update public sources and rerank free TCP endpoints using measurements on device.
     * No directory-provided ping is mistaken for successful VPN authentication. */
    void refreshAll(){
        if(updatingAll){info("A source update is already running.");return;}
        updatingAll=true;screen.invalidate();
        final int[] outstanding={2};
        final String[] openVpnResult={"OpenVPN: update pending"};
        final String[] sourceResult={"Sources: update pending"};
        Runnable finished=()->{
            outstanding[0]--;
            if(outstanding[0]!=0)return;
            updatingAll=false;screen.invalidate();
            info("Update complete.\n"+openVpnResult[0]+"\n"+sourceResult[0]+
                "\nTCP ports are sorted by measured on-device latency. VPN login and routing still require a connection.");
        };
        directory.update(list->{
            servers=list;
            selectedIndex=0;
            persist();screen.invalidate();
            measureOpenVpn();
            openVpnResult[0]="OpenVPN: "+list.size()+" free profiles updated"+
                (paidMode?" (paid profile preserved)":"; TCP reachability test started");
            finished.run();
        }, err->{
            openVpnResult[0]="OpenVPN: update failed; "+servers.size()+" cached. "+err;
            finished.run();
        });
        hub.refresh(()->{
            nativeCacheAt=0;screen.listCacheAt=0;
            sourceResult[0]="V2Ray: "+hub.entries("V2RAY").size()+
                " | Telegram proxies: "+hub.entries("PROXY").size();
            List<FeedParser.Entry> measured=hub.entries("V2RAY");
            if(measured.isEmpty()||hubPanel.probe.busy){finished.run();return;}
            hubPanel.probe.test(measured,()->{screen.listCacheAt=0;screen.invalidate();finished.run();});
        });
    }
    void showPaidCredentials(String ovpn){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(40,10,40,0);
        TextView hint=new TextView(this);
        hint.setText("Optional paid OpenVPN username and password. Saved encrypted only on this device.\nIf blank, the external client can request credentials.");
        hint.setTextSize(13);form.addView(hint);
        EditText user=new EditText(this);user.setSingleLine(true);user.setHint("Username (optional)");form.addView(user);
        EditText pass=new EditText(this);pass.setSingleLine(true);pass.setHint("Password (optional)");
        pass.setInputType(129);form.addView(pass);
        new GlassDialog.Builder(this).setTitle("Paid OpenVPN account").setView(form)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save securely",(d,w)->{
                String username=user.getText().toString().trim(),password=pass.getText().toString();
                if(username.contains("\n")||password.contains("\n")||username.contains("\r")||password.contains("\r")){
                    info("Credentials cannot contain line breaks.");return;
                }
                try{profiles.save(ovpn,username,password);paidMode=true;persist();screen.invalidate();
                    Toast.makeText(this,"Paid profile imported",Toast.LENGTH_LONG).show();}
                catch(Exception ex){info("Profile encryption failed: "+ex.getMessage());}
            }).show();
    }
    void persist(){getPreferences(MODE_PRIVATE).edit().putBoolean("paid_mode",paidMode)
        .putBoolean("prefer_native",preferNative).putBoolean("smart_native",smartNative)
        .putInt("selected",selectedIndex).apply();}
    void selectServer(){
        if(servers.isEmpty()){info("No cached free OpenVPN relay. Try embedded V2Ray or refresh the public mirror.");return;}
        String[] items=new String[Math.min(servers.size(),75)];
        for(int i=0;i<items.length;i++)
            items[i]=servers.get(i).country+" · "+openProbe.label(servers.get(i))+" · "+servers.get(i).host;
        new GlassDialog.Builder(this).setTitle("Free OpenVPN · measured latency")
            .setSingleChoiceItems(items,Math.min(selectedIndex,items.length-1),(d,which)->{
                d.dismiss();selectOpenVpnServer(which);
            }).setNegativeButton("Close",null).show();
    }
    private boolean hasNativeCache;
    private long nativeCacheAt;
    boolean hasNativeCandidates(){
        if(hub==null)return false;
        long now=android.os.SystemClock.elapsedRealtime();
        if(nativeCacheAt!=0&&now-nativeCacheAt<30000)return hasNativeCache;
        hasNativeCache=false;
        for(FeedParser.Entry e:hubPanel.entries("V2RAY"))if(SingBoxConfig.supported(e.value)){
            hasNativeCache=true;break;
        }
        nativeCacheAt=now;
        return hasNativeCache;
    }
    void connectNativeEntry(String link){
        try{
            String config=SingBoxConfig.build(link);
            paidMode=false;preferNative=true;persist();
            if(vpn.state!=VpnController.State.OFF)vpn.disconnect();
            connectNative(config);
        }catch(Exception ex){info("Unsupported or incomplete config: "+ex.getMessage());}
    }
    void connectNative(String config){
        if(SingVpnService.state==SingVpnService.STARTING
            ||SingVpnService.state==SingVpnService.TUNNEL_ACTIVE)return;
        Intent auth=android.net.VpnService.prepare(this);
        if(auth!=null){pendingNativeConfig=config;startActivityForResult(auth,PREPARE_NATIVE);}
        else startNativeService(config);
    }
    void startNativeService(String config){
        try{
            Intent intent=new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_START)
                .putExtra(SingVpnService.EXTRA_CONFIG,config);
            startForegroundService(intent);
        }catch(Exception ex){info("Embedded VPN service could not start: "+ex.getMessage());}
    }
    void stopNative(){
        pendingNativeConfig=null;
        try{startService(new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_STOP));}
        catch(Exception ex){android.util.Log.w("Aegis","VPN stop request failed",ex);}
    }
    void disconnectImmediately(){
        // STOP works during service initialization as well as after connection.
        pendingNativeConfig=null;
        if(SingVpnService.state!=SingVpnService.OFF)stopNative();
        if(vpn.state!=VpnController.State.OFF)vpn.disconnect();
        if(screen!=null){screen.dragOffset=0;screen.invalidate();}
    }
    boolean isTunnelOn(){return vpn.state==VpnController.State.ON||SingVpnService.state==SingVpnService.TUNNEL_ACTIVE;}
    boolean isConnecting(){return vpn.state==VpnController.State.CONNECTING||SingVpnService.state==SingVpnService.STARTING;}
    void startVpn(){
        if(isTunnelOn()||isConnecting())return;
        String config=null;
        if(paidMode){
            try{config=profiles.getOpenVpnConfig();}
            catch(Exception e){info("Could not decrypt paid profile.");return;}
            if(config==null){info("Import your paid .ovpn file first.");return;}
        }else if(preferNative&&!smartNative&&!chosenNative().isEmpty()){
            try{connectNative(SingBoxConfig.build(chosenNative()));}
            catch(Exception e){info("Saved server could not be used: "+e.getMessage());}
            return;
        }else if(preferNative&&hasNativeCandidates()){
            // v0.5.0 baseline: the original community feeds, 12 outbound candidates,
            // three-minute sing-box URLTest. Do not let massive country lists or
            // device TCP preflight crowd out the trusted-in-practice v0.5 pool.
            ArrayList<FeedParser.Entry> legacy=new ArrayList<>(hub.entries("V2RAY"));
            legacy.removeIf(e->!SourceHub.preferred(e.source));
            // If the original sources are blocked, retain the current supplementary feeds
            // as a fallback only. Manual choices are kept in the encrypted vault.
            ArrayList<FeedParser.Entry> choices=legacy.isEmpty()
                ?new ArrayList<>(hubPanel.entries("V2RAY")):legacy;
            if(legacy.isEmpty())choices.removeIf(e->!SingBoxConfig.supported(e.value));
            // Same v0.5 comparator: measured reachable TCP first when available,
            // then VLESS first. No unverified assumption that TCP means VPN usable.
            choices.sort((a,b)->{
                long ra=hubPanel.probe.rank(a.value),rb=hubPanel.probe.rank(b.value);
                if(ra!=rb)return Long.compare(ra,rb);
                return Boolean.compare(!a.value.startsWith("vless://"),!b.value.startsWith("vless://"));
            });
            java.util.LinkedHashSet<String> selected=new java.util.LinkedHashSet<>();
            for(FeedParser.Entry e:choices){
                if(selected.size()>=12)break;
                if(SingBoxConfig.supported(e.value))selected.add(e.value);
            }
            if(selected.isEmpty()){info("No usable v0.5-format links; update original sources or select a manual server.");return;}
            try{connectNative(SingBoxConfig.buildAuto(new ArrayList<>(selected)));}
            catch(Exception ex){info("No supported native proxy config: "+ex.getMessage());}
            return;
        }else if(!servers.isEmpty()){
            config=servers.get(Math.min(selectedIndex,servers.size()-1)).config;
        }else if(hasNativeCandidates()){
            preferNative=true;persist();startVpn();return;
        }else{
            info("No available configuration. Tap Smart update. Public config counts are not evidence that nodes work.");return;
        }
        if(!paidMode)openVpnRetries=0;
        vpn.connect(config);screen.invalidate();
    }
    void showSettings(){
        final String[] actions={"Smart update all · choose best free server",
            "Refresh free OpenVPN servers","Choose free OpenVPN server",
            "Import paid .ovpn account","Use purchased OpenVPN account",
            "Use free VPN Gate servers","Delete saved paid account",
            "About / security","V2Ray · Telegram proxies","Use embedded V2Ray VPN"};
        new GlassDialog.Builder(this).setTitle("VPN Settings").setItems(actions,(dlg,which)->{
            if(which==8)hubPanel.open();
            if(which==9){paidMode=false;preferNative=true;persist();screen.invalidate();}
            if(which==0)refreshAll();
            if(which==1)refresh(true);
            if(which==2)selectServer();
            if(which==3){
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(pick,PICK_OVPN);
            }
            if(which==4){if(!profiles.exists())info("Import a .ovpn file first.");
                else{paidMode=true;persist();screen.invalidate();}}
            if(which==5){paidMode=false;preferNative=false;persist();screen.invalidate();}
            if(which==6)new GlassDialog.Builder(this).setMessage("Delete encrypted paid OpenVPN profile?")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{profiles.clear();paidMode=false;persist();screen.invalidate();}).show();
            if(which==7)info("Connection requires the separate free 'OpenVPN for Android' app (de.blinkt.openvpn).\n"+
              "Free VPN Gate volunteer relays can monitor traffic metadata and disconnect unexpectedly.\n"+
              "Updates refer to the server directory, not APK updates.\n"+
              "Embedded sing-box supports selected VLESS, VMess, Trojan, Shadowsocks and Hysteria2 formats. AmneziaWG and encrypted .npv are not supported.");
        }).show();
    }
    /** Responsive, animated Canvas UI, with touchable cards and paged scrollable locations. */
    private class Screen extends View {
        final Paint p=new Paint(3),t=new Paint(3);
        float density=1,W,H,downX,downY,dragOffset,phase,listOffset,scrollStart;
        int listCacheMode=-1;
        long listCacheAt=0;
        List<FeedParser.Entry> listCache=new ArrayList<>();
        int locationMode=0;String countryFilter="";boolean dragging,scrolling,wasOn;
        float knobProgress=0,pageAlpha=1;
        final android.animation.ValueAnimator ambient=android.animation.ValueAnimator.ofFloat(0,1);
        private long lastAmbientFrame=0;
        private Shader warmShader,greenShader,footerShader,sliderWarmShader,sliderGreenShader;
        private Bitmap frostBitmap;private BitmapShader frostShader;
        private float cachedW=-1,cachedH=-1;
        private boolean cachedConnected=false;
        android.animation.ValueAnimator slide,page;
        Bitmap shield;
        Screen(){
            super(MainActivity.this);
            // Use GPU-accelerated Canvas. Software layers caused full-screen redraw jank.
            shield=BitmapFactory.decodeResource(getResources(),R.drawable.app_icon);
            ambient.setDuration(2400);
            ambient.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            ambient.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            ambient.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            ambient.addUpdateListener(a->{
                phase=(float)a.getAnimatedValue();
                long now=android.os.SystemClock.uptimeMillis();
                if(tab==0&&(isConnecting()||isTunnelOn())&&now-lastAmbientFrame>33){
                    lastAmbientFrame=now;postInvalidateOnAnimation();
                }
            });
            wasOn=isTunnelOn();knobProgress=wasOn?1:0;
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();syncAmbient();}
        @Override protected void onDetachedFromWindow(){
            ambient.cancel();
            if(slide!=null)slide.cancel();if(page!=null)page.cancel();
            if(frostBitmap!=null){frostBitmap.recycle();frostBitmap=null;frostShader=null;}
            cachedW=-1;cachedH=-1;
            super.onDetachedFromWindow();
        }
        @Override protected void onWindowVisibilityChanged(int v){
            super.onWindowVisibilityChanged(v);syncAmbient();
        }
        void syncAmbient(){
            boolean active=getWindowVisibility()==VISIBLE&&(isTunnelOn()||isConnecting());
            if(active){
                if(!ambient.isStarted())ambient.start();
                else if(ambient.isPaused())ambient.resume();
            }else if(ambient.isStarted())ambient.cancel();
        }
        void animateState(){
            boolean current=isTunnelOn();
            if(current==wasOn)return;
            wasOn=current;
            if(slide!=null)slide.cancel();
            slide=android.animation.ValueAnimator.ofFloat(knobProgress,current?1:0);
            slide.setDuration(470);
            slide.setInterpolator(new android.view.animation.PathInterpolator(.18f,.78f,.22f,1f));
            slide.addUpdateListener(a->{knobProgress=(float)a.getAnimatedValue();invalidate();});
            slide.start();
        }
        void setTab(int next){
            if(next==tab)return;
            tab=next;listOffset=0;
            if(page!=null)page.cancel();
            page=android.animation.ValueAnimator.ofFloat(0,1);
            page.setDuration(280);
            page.setInterpolator(new android.view.animation.PathInterpolator(.2f,.8f,.2f,1f));
            page.addUpdateListener(a->{pageAlpha=(float)a.getAnimatedValue();invalidate();});
            page.start();
        }
        void ink(Canvas c,String string,float x,float y,float size,int color,boolean weight){
            t.reset();t.setAntiAlias(true);t.setColor(color);t.setTextSize(size);
            t.setTypeface(Typeface.create(weight?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
            c.drawText(string,x,y,t);
        }
        void center(Canvas c,String s,float x,float y,float size,int color,boolean bold){
            t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setTextSize(size);
            ink(c,s,x-t.measureText(s)/2,y,size,color,bold);
        }
        String cut(String s,int width,float size){
            if(s==null)return "";
            t.setTextSize(size);t.setTypeface(Typeface.create("sans-serif-medium",0));
            if(t.measureText(s)<=width)return s;
            while(s.length()>3&&t.measureText(s+"…")>width)s=s.substring(0,s.length()-1);
            return s+"…";
        }
        /**
         * Real frosted glass: each card samples a TWO-PASS-BLURRED copy of
         * the very same ambient canvas directly behind its position.
         * Its optical white wash is deliberately only ~30% opacity;
         * older builds painted an opaque 68%-white slab that hid the blur.
         * The expensive blur is precomputed at low resolution once per size/state,
         * not on every animation frame.
         */
        void card(Canvas c,float x,float y,float w,float h,float radius,int color){
            p.setShader(null);p.setStyle(Paint.Style.FILL);p.setAlpha(255);
            // Soft floating separation from the luminous background; cheap to redraw.
            p.setColor(0x102a5663);
            c.drawRoundRect(x+1.5f,y+3.3f,x+w+1.5f,y+h+4.8f,radius,radius,p);
            if(frostShader!=null){
                p.setColor(0xffffffff);
                p.setShader(frostShader);
                p.setAlpha(239);
                c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);
                p.setShader(null);p.setAlpha(255);
            }
            // Preserve visible peach/mint/lilac behind the glass.
            int wash=android.graphics.Color.argb(
                Math.min(93,Math.max(58,android.graphics.Color.alpha(color)/3)),
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color));
            p.setColor(wash);
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);
            // A two-dimensional refractive highlight, without a per-frame shader allocation.
            p.setColor(0x26ffffff);
            c.drawRoundRect(x+2,y+2,x+w-2,y+h*.54f,
                Math.max(5,radius-2),Math.max(5,radius-2),p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.35f);
            p.setColor(0xebffffff);
            c.drawRoundRect(x+.7f,y+.7f,x+w-.7f,y+h-.7f,radius,radius,p);
            p.setColor(0x40ffffff);p.setStrokeWidth(2.2f);
            c.drawArc(x+4,y+3,x+w-4,y+h*.40f,194,148,false,p);
            p.setStyle(Paint.Style.FILL);p.setAlpha(255);
        }
        /** The offscreen blur uses exactly the same gradients AND flowing waves
         * that the visible home screen paints. Sampling any other drawing is a
         * flat overlay, not backdrop blur.
         */
        void drawAmbientBackground(Canvas c){
            c.drawColor(0xfffcfcfa);
            p.setStyle(Paint.Style.FILL);p.setAlpha(255);
            p.setShader(isTunnelOn()?greenShader:warmShader);
            c.drawRect(0,0,W,H,p);
            p.setShader(footerShader);
            c.drawRect(0,0,W,H,p);
            p.setShader(null);
            for(int i=0;i<4;i++){
                Path wave=new Path();
                float offset=i*66+phase*9;
                wave.moveTo(W+30,-120+offset);
                wave.cubicTo(W*.45f,100+offset,W*.96f,245+offset,-80,H*.67f+offset);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.4f);p.setColor(0x9cffffff);
                c.drawPath(wave,p);
                p.setStrokeWidth(14);p.setColor(0x17ffffff);
                c.drawPath(wave,p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        void prepareFrostedBackdrop(){
            int bw=Math.max(76,(int)(W/5)),bh=Math.max(125,(int)(H/5));
            if(frostBitmap!=null)frostBitmap.recycle();
            frostBitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888);
            Canvas bg=new Canvas(frostBitmap);
            bg.save();
            bg.scale(bw/W,bh/H);
            drawAmbientBackground(bg);
            bg.restore();
            // Separable 2-pass 20-25dp optical blur, only on a tiny cached image.
            int[] pixels=new int[bw*bh],scratch=new int[pixels.length];
            frostBitmap.getPixels(pixels,0,bw,0,0,bw,bh);
            int radius=5;
            for(int y=0;y<bh;y++)for(int x=0;x<bw;x++){
                int ar=0,rr=0,gg=0,bb=0,count=0;
                for(int dx=-radius;dx<=radius;dx++){
                    int argb=pixels[y*bw+Math.max(0,Math.min(bw-1,x+dx))];
                    ar+=argb>>>24;rr+=(argb>>16)&255;gg+=(argb>>8)&255;bb+=argb&255;count++;
                }
                scratch[y*bw+x]=((ar/count)<<24)|((rr/count)<<16)|((gg/count)<<8)|(bb/count);
            }
            for(int y=0;y<bh;y++)for(int x=0;x<bw;x++){
                int ar=0,rr=0,gg=0,bb=0,count=0;
                for(int dy=-radius;dy<=radius;dy++){
                    int argb=scratch[Math.max(0,Math.min(bh-1,y+dy))*bw+x];
                    ar+=argb>>>24;rr+=(argb>>16)&255;gg+=(argb>>8)&255;bb+=argb&255;count++;
                }
                pixels[y*bw+x]=((ar/count)<<24)|((rr/count)<<16)|((gg/count)<<8)|(bb/count);
            }
            frostBitmap.setPixels(pixels,0,bw,0,0,bw,bh);
            frostShader=new BitmapShader(frostBitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            Matrix matrix=new Matrix();
            matrix.setScale(W/bw,H/bh);
            frostShader.setLocalMatrix(matrix);
        }
        void gradient(Canvas c,float x,float y,float w,float h,float r,int first,int last){
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(x,y,x+w,y+h,first,last,Shader.TileMode.CLAMP));
            c.drawRoundRect(x,y,x+w,y+h,r,r,p);p.setShader(null);
        }
        void circle(Canvas c,float x,float y,float radius,int color){
            p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(color);c.drawCircle(x,y,radius,p);
        }
        void drawShield(Canvas c,float x,float y,float size){
            if(shield==null){ink(c,"◆",x+12,y+size*.7f,size*.6f,ORANGE,true);return;}
            // Crop the original square artwork into the white circular knob.
            // A squared bitmap must never escape the round slider outline.
            float cx=x+size/2,cy=y+size/2,r=size/2-1;
            c.save();
            Path mask=new Path();
            mask.addCircle(cx,cy,r,Path.Direction.CW);
            c.clipPath(mask);
            // Zoom slightly into the shield artwork to hide the square icon tile,
            // preserving the center logo while keeping the outer knob perfectly round.
            int side=(int)(Math.min(shield.getWidth(),shield.getHeight())*.78f);
            int sx=(shield.getWidth()-side)/2,sy=(shield.getHeight()-side)/2;
            p.setFilterBitmap(true);p.setAlpha(255);
            c.drawBitmap(shield,new Rect(sx,sy,sx+side,sy+side),
                new RectF(x,y,x+size,y+size),p);
            c.restore();
            p.setShader(null);p.setStyle(Paint.Style.STROKE);
            p.setColor(0x85ffffff);p.setStrokeWidth(1.5f);
            c.drawCircle(cx,cy,r,p);
            p.setStyle(Paint.Style.FILL);
        }
        @Override protected void onDraw(Canvas raw){
            density=Math.min(getResources().getDisplayMetrics().density,getHeight()/745f);
            W=getWidth()/density;H=getHeight()/density;
            animateState();
            syncAmbient();
            raw.save();raw.scale(density,density);
            Canvas c=raw;c.drawColor(0xfffcfcfa);
            boolean on=isTunnelOn();
            if(cachedW!=W||cachedH!=H||cachedConnected!=on){
                cachedW=W;cachedH=H;cachedConnected=on;
                warmShader=new RadialGradient(W*.88f,H*.34f,W*.84f,
                    new int[]{0xdfffad61,0x56ffdec0,0x00ffffff},null,Shader.TileMode.CLAMP);
                greenShader=new RadialGradient(W*.86f,H*.39f,W*.85f,
                    new int[]{0xd755efb2,0x5ae3fff0,0x00ffffff},null,Shader.TileMode.CLAMP);
                footerShader=new RadialGradient(W*.01f,H*.82f,W*.94f,
                    new int[]{0x9bf8bbc8,0x00ffffff},null,Shader.TileMode.CLAMP);
                float sx=24,sy=sliderY(),width=W-48;
                sliderWarmShader=new LinearGradient(sx,sy,sx+width,sy+92,
                    0xffffbd50,0xffff6039,Shader.TileMode.CLAMP);
                sliderGreenShader=new LinearGradient(sx,sy,sx+width,sy+92,
                    0xff00efbf,0xff04ad74,Shader.TileMode.CLAMP);
                // Initialize the shader before sampling the real ambient canvas.
                prepareFrostedBackdrop();
            }
            drawAmbientBackground(c);
            header(c);
            c.save();c.translate(0,(1-pageAlpha)*16);c.saveLayerAlpha(0,94,W,H-74,(int)(255*Math.max(0,Math.min(1,pageAlpha))));
            if(tab==0)home(c);else if(tab==1)locations(c);else if(tab==2)stats(c);else backup(c);
            c.restore();c.restore();
            navbar(c);raw.restore();
        }
        void header(Canvas c){
            // Keep the approved reference header: library on left, VPN in center, settings on right.
            card(c,19,36,46,46,23,0xb6ffffff);
            center(c,"⠿",42,65,22,0xff596670,true);
            center(c,"VPN",W/2,66,19,INK,true);
            card(c,W-65,36,46,46,23,0xb6ffffff);
            center(c,"⚙",W-42,66,23,0xff596670,true);
        }
        float sliderY(){return H*.409f;}
        float serverY(){return Math.min(Math.max(sliderY()+175,H*.655f),H-250);}
        String countryFlag(String country){
            String name=country.toLowerCase(java.util.Locale.ROOT);
            if(name.contains("united states")||name.equals("usa"))return "🇺🇸";
            if(name.contains("japan"))return "🇯🇵";
            if(name.contains("canada"))return "🇨🇦";
            if(name.contains("germany"))return "🇩🇪";
            if(name.contains("france"))return "🇫🇷";
            if(name.contains("singapore"))return "🇸🇬";
            if(name.contains("united kingdom")||name.equals("uk"))return "🇬🇧";
            if(name.contains("netherlands"))return "🇳🇱";
            if(name.contains("australia"))return "🇦🇺";
            if(name.contains("korea"))return "🇰🇷";
            if(name.contains("india"))return "🇮🇳";
            return "🌐";
        }
        void home(Canvas c){
            float titleY=Math.max(151,H*.198f);
            ink(c,"Private.",27,titleY,36,INK,true);
            ink(c,"Secure.",27,titleY+39,36,INK,true);
            ink(c,"Everywhere.",27,titleY+78,36,0xff87919f,true);
            float sy=sliderY(),x=24,sw=W-48,sh=92;
            boolean on=isTunnelOn(),connecting=isConnecting();
            int main=on?0xff05db8b:0xffff962d,deep=on?0xff008d63:0xffe45212;
            float cx=W/2,cy=sy+sh/2;
            p.setStyle(Paint.Style.STROKE);
            for(int ring=0;ring<(connecting||on?3:2);ring++){
                p.setColor((on?0x12a9edcf:0x19ffbb8b)+(int)(phase*8)*0x1000000);
                p.setStrokeWidth(1.5f);
                c.drawCircle(cx,cy,95+ring*17+((connecting||on)?phase*7:0),p);
            }
            p.setStyle(Paint.Style.FILL);
            card(c,x,sy,sw,sh,48,0x9effffff);
            p.setShader(on?sliderGreenShader:sliderWarmShader);
            p.setAlpha(192);p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(x+2,sy+2,x+sw-2,sy+sh-2,46,46,p);
            p.setShader(null);p.setAlpha(255);
            // iOS-like inner specular glass glow.
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);p.setColor(0xb9ffffff);
            c.drawRoundRect(x+2,sy+2,x+sw-2,sy+sh-2,46,46,p);
            p.setStrokeWidth(6);p.setColor(on?0x5005ffbf:0x55fff0af);
            c.drawRoundRect(x+7,sy+7,x+sw-7,sy+sh-7,43,43,p);p.setStyle(Paint.Style.FILL);
            p.setColor(0x2dffffff);
            c.drawRoundRect(x+10,sy+7,x+sw-10,sy+sh*.47f,45,45,p);
            float travel=sw-90;
            float logical=Math.max(0,Math.min(1,knobProgress));
            float offset=Math.max(-travel,Math.min(travel,dragOffset));
            float knobX=x+45+logical*travel+offset;
            if(connecting)knobX+=Math.sin(phase*Math.PI*2)*3;
            p.setColor(0x220c2824);c.drawCircle(knobX,cy+3,40,p);
            p.setColor(0xfffcfffd);c.drawCircle(knobX,cy,40,p);
            p.setStyle(Paint.Style.STROKE);p.setColor(0xc9ffffff);p.setStrokeWidth(1);c.drawCircle(knobX,cy,38,p);p.setStyle(Paint.Style.FILL);
            if(on)ink(c,"✓",knobX-20,cy+16,51,GREEN,true);
            else drawShield(c,knobX-30,cy-30,60);
            if(on)ink(c,(vpn.state==VpnController.State.ON||SingVpnService.verifiedRoute)?"Connected":"Tunnel active",x+24,cy+6,20,0xffffffff,true);
            else ink(c,connecting?"Connecting…":"Slide to connect",x+107,cy+6,18,0xffffffff,true);
            if(connecting){
                p.setStrokeWidth(3);p.setStyle(Paint.Style.STROKE);p.setColor(0xdfffffff);
                c.drawArc(x-4,sy-4,x+sw+4,sy+sh+4,phase*360,115,false,p);
                p.setStyle(Paint.Style.FILL);
            }
            center(c,on?(vpn.state==VpnController.State.ON||SingVpnService.verifiedRoute?
                "✓  Your connection is protected":"Tunnel active · verifying route"):
                connecting?"Establishing a secure connection":"Slide to connect securely",
                W/2,sy+sh+32,11.5f,on?0xff288e6e:MUTED,false);
            // Reference image has no extra quick-action cards between the switch and country.
            // Smart Update and manual selection stay available in Settings and Locations.
            float y=serverY();
            card(c,21,y,W-42,78,25,0xa4ffffff);
            circle(c,61,y+44,26,0xfff2f8fa);
            if(!paidMode&&preferNative)ink(c,"🌐",41,y+54,31,0xff8b67f1,true);
            else if(paidMode)ink(c,"🔒",42,y+54,28,0xfff59440,true);
            else {
                FreeDirectory.Node countryNode=servers.isEmpty()?null:servers.get(Math.min(selectedIndex,servers.size()-1));
                ink(c,countryNode==null?"🌐":countryFlag(countryNode.country),41,y+54,31,INK,true);
            }
            String title;
            String subtitle;
            if(paidMode){title="Private OpenVPN account";subtitle="Purchased .ovpn profile";}
            else if(preferNative){
                title=smartNative?"Smart · automatic selection":"Manual · "+hubPanel.type(chosenNative());
                subtitle=smartNative?"Up to 12 native servers · auto test":hubPanel.host(chosenNative());
            }else{
                FreeDirectory.Node node=servers.isEmpty()?null:servers.get(Math.min(selectedIndex,servers.size()-1));
                title=node==null?"OpenVPN · no nodes yet":node.country;
                subtitle=node==null?"Use Settings to refresh":node.host+" · "+openProbe.label(node);
            }
            ink(c,cut(title,(int)(W-145),16),101,y+35,16,INK,true);
            ink(c,cut(subtitle,(int)(W-147),12),101,y+58,12,MUTED,false);
            ink(c,"›",W-51,y+56,31,0xff99a8b0,false);
            float statY=y+92;
            card(c,21,statY,W-42,80,24,0xa4ffffff);
            p.setColor(0xffe6eeee);c.drawRect(W/2-1,statY+12,W/2,statY+69,p);
            ink(c,"↓",41,statY+55,35,on?GREEN:ORANGE,true);
            ink(c,"Download",82,statY+31,12,MUTED,false);
            ink(c,rate(downMbps)+" Mbps",82,statY+57,16,INK,true);
            ink(c,"↑",W/2+17,statY+55,34,on?GREEN:ORANGE,true);
            ink(c,"Upload",W/2+56,statY+31,12,MUTED,false);
            ink(c,rate(upMbps)+" Mbps",W/2+56,statY+57,16,INK,true);
        }
        List<FeedParser.Entry> chosenEntries(){
            long now=android.os.SystemClock.elapsedRealtime();
            if(listCacheMode==locationMode&&now-listCacheAt<5000)return listCache;
            String k=locationMode==1?"V2RAY":"PROXY";
            ArrayList<FeedParser.Entry> data=new ArrayList<>(hubPanel.entries(k));
            if(locationMode==1){
                if(!countryFilter.isEmpty())data.removeIf(e->!countryFilter.equals(SourceHub.country(e.source)));
                data.sort(Comparator.comparingLong(e->hubPanel.probe.rank(e.value)));
            }
            listCache=data;listCacheMode=locationMode;listCacheAt=now;
            return data;
        }
        int rowCount(){return locationMode==0?servers.size():chosenEntries().size();}
        void locations(Canvas c){
            ink(c,"Locations",25,135,30,INK,true);
            ink(c,"Choose a connection · swipe to see more",25,159,12,MUTED,false);
            String[] names={"OpenVPN","V2Ray","Telegram"};
            float chipY=178,chipW=(W-46)/3;
            for(int i=0;i<3;i++){
                float x=21+i*chipW;
                card(c,x,chipY,chipW-5,37,16,locationMode==i?0xffe1f6ef:0xdfffffff);
                center(c,names[i],x+(chipW-5)/2,chipY+23,11.5f,locationMode==i?0xff009c70:MUTED,true);
            }
            card(c,21,222,W-42,44,19,0xeaffffff);
            ink(c,locationMode==0?(openProbe.busy?"◌  Checking live TCP delays…":"◌  Test OpenVPN TCP latency"):
                (updatingAll?"◌  Updating sources…":"↻  Smart update all sources"),39,249,14,ORANGE,true);
            if(locationMode==1){
                card(c,21,274,W-42,36,17,0xdfffffff);
                ink(c,"Country source: "+(countryFilter.isEmpty()?"All":countryFilter)+"    ▼",38,297,13,INK,true);
            }
            final float top=locationMode==1?320:280,bottom=H-96,step=80;
            c.save();c.clipRect(0,top,W,bottom);
            int count=rowCount();
            if(count==0){
                ink(c,"No saved servers in this category",32,locationMode==1?373:323,16,INK,true);
                ink(c,"Tap update or import a configuration",32,locationMode==1?397:347,12,MUTED,false);
            }else{
                List<FeedParser.Entry> entries=locationMode==0?Collections.emptyList():chosenEntries();
                int first=Math.max(0,(int)(listOffset/step)-1);
                int last=Math.min(count,first+(int)Math.ceil((bottom-top)/step)+3);
                for(int i=first;i<last;i++){
                    float y=top+i*step-listOffset;
                    if(y+73<top||y>bottom)continue;
                    card(c,21,y+3,W-42,72,20,0xf2ffffff);
                    int color;String title,subtitle,latency="";
                    boolean selected=false;
                    if(locationMode==0){
                        FreeDirectory.Node n=servers.get(i);
                        color=0xffed904b;title=n.country;subtitle=n.host;
                        latency=openProbe.label(n);
                        selected=!paidMode&&!preferNative&&selectedIndex==i;
                    }else{
                        FeedParser.Entry e=entries.get(i);
                        color=hubPanel.tint(e.value);
                        title=hubPanel.type(e.value)+"  ·  "+hubPanel.host(e.value);
                        subtitle=e.source.replace("https://t.me/s/","@");
                        latency=locationMode==1?hubPanel.delay(e.value):"Telegram";
                        selected=locationMode==1&&!smartNative&&preferNative&&chosenNative().equals(e.value);
                    }
                    p.setColor(color);c.drawRoundRect(29,y+17,35,y+62,4,4,p);
                    ink(c,cut(title,(int)(W-154),15),48,y+32,15,INK,true);
                    ink(c,cut(subtitle,(int)(W-160),11),48,y+54,11,MUTED,false);
                    int latencyColor=locationMode==1?hubPanel.latencyColor(entries.get(i).value):
                        locationMode==0?(openProbe.ms(servers.get(i))>=0?GREEN:MUTED):color;
                    ink(c,cut(latency,86,11),W-111,y+33,11,latencyColor,true);
                    if(selected)ink(c,"✓",W-45,y+57,23,GREEN,true);
                    else ink(c,"›",W-47,y+61,23,MUTED,false);
                }
                if(count*step>bottom-top){
                    float track=bottom-top,visible=track/Math.max(track,count*step)*track;
                    float left=top+(listOffset/Math.max(1,count*step-track))*(track-visible);
                    p.setColor(0x55a3b8b0);c.drawRoundRect(W-4,left,W-2,left+visible,2,2,p);
                }
            }
            c.restore();
            ink(c,locationMode==0?"TCP measured on phone; UDP latency unknown":locationMode==1?"TCP ping is not VPN authentication":"Telegram requires a separate protocol handshake",
                26,H-88,10,MUTED,false);
        }
        void stats(Canvas c){
            ink(c,"Statistics",25,137,30,INK,true);
            ink(c,"Tunnel diagnostics · accurate state",25,165,12,MUTED,false);
            card(c,21,191,W-42,129,24,0xf2ffffff);
            ink(c,"Current status",43,231,13,MUTED,false);
            ink(c,isConnecting()?"Connecting":isTunnelOn()?
                (SingVpnService.verifiedRoute?"VPN route checked":"Tunnel active"):"Disconnected",
                43,265,21,isTunnelOn()?GREEN:INK,true);
            ink(c,"Session "+uptime()+"  ·  "+(preferNative?"Native VPN":"OpenVPN client"),43,296,11,MUTED,false);
            card(c,21,336,W-42,135,24,0xf2ffffff);
            ink(c,"Downstream",43,372,13,MUTED,false);ink(c,rate(downMbps)+" Mbps",W-156,372,18,INK,true);
            ink(c,"Upstream",43,424,13,MUTED,false);ink(c,rate(upMbps)+" Mbps",W-156,424,18,INK,true);
            ink(c,vpn.state==VpnController.State.ON?
                "External OpenVPN: traffic counters unavailable":
                "Approximate app UID traffic · not a speed test",30,500,12,MUTED,false);
            card(c,21,526,W-42,65,18,0xe9ffffff);
            ink(c,"Measured latency",42,552,14,INK,true);
            String ping="Select a server in Locations";
            if(!paidMode&&!preferNative&&!servers.isEmpty()){
                FreeDirectory.Node node=servers.get(Math.min(selectedIndex,servers.size()-1));
                ping=openProbe.label(node);
            }else if(preferNative&&!smartNative&&!chosenNative().isEmpty())
                ping=hubPanel.delay(chosenNative())+" (TCP only)";
            ink(c,cut(ping,(int)(W-90),12),42,576,12,MUTED,false);
        }
        void backup(Canvas c){
            ink(c,"Backup connection",24,137,28,INK,true);
            ink(c,"Independent provider option · no server is guaranteed",24,165,12,MUTED,false);
            card(c,21,193,W-42,116,25,0xdfffffff);
            ink(c,"Cloudflare WARP",43,236,20,INK,true);
            ink(c,"Free official app · opens separately",43,261,12,MUTED,false);
            ink(c,"›",W-60,268,33,0xff12a87e,true);
            card(c,21,328,W-42,112,24,0xdfffffff);
            ink(c,"Your own OpenVPN profile",43,370,18,INK,true);
            ink(c,"Import an .ovpn config from your provider",43,395,12,MUTED,false);
            ink(c,"›",W-60,402,32,ORANGE,true);
            card(c,21,463,W-42,102,22,0xdfffffff);
            ink(c,"Connection verification",43,499,16,INK,true);
            ink(c,"A VPN icon or TCP ping alone does not confirm",43,522,12,MUTED,false);
            ink(c,"that a proxy passes real Internet traffic.",43,541,12,MUTED,false);
        }
        void navbar(Canvas c){
            float y=H-75;
            card(c,0,y,W,86,27,0xc9ffffff);
            String[] labels={"Home","Locations","Stats"};
            String[] symbols={"⌂","◎","▥"};
            for(int i=0;i<3;i++){
                float x=W*(i+.5f)/3;
                int color=tab==i?ORANGE:MUTED;
                center(c,symbols[i],x,y+34,27,color,true);
                center(c,labels[i],x,y+60,12,color,tab==i);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX()/density,y=e.getY()/density,sy=sliderY();
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                downX=x;downY=y;scrollStart=listOffset;dragging=tab==0&&y>sy&&y<sy+96;
                scrolling=tab==1&&y>(locationMode==1?320:280)&&y<H-95;return true;
            }
            if(e.getAction()==MotionEvent.ACTION_CANCEL){
                dragging=false;scrolling=false;dragOffset=0;invalidate();return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                if(dragging){
                    float range=W-140;
                    dragOffset=isTunnelOn()?Math.max(-range,Math.min(0,x-downX)):Math.min(range,Math.max(0,x-downX));
                    invalidate();return true;
                }
                if(scrolling){
                    listOffset=Math.max(0,Math.min(Math.max(0,rowCount()*80-(H-96-(locationMode==1?320:280))),scrollStart+downY-y));
                    invalidate();return true;
                }
            }
            if(e.getAction()==MotionEvent.ACTION_UP){
                if(dragging){
                    dragging=false;dragOffset=0;invalidate();
                    // Tap the middle connect control to stop IMMEDIATELY in
                    // both CONNECTING and CONNECTED states (no slide required).
                    if(isConnecting()||isTunnelOn())disconnectImmediately();
                    else if(x-downX>Math.min(58,W*.19f)||Math.abs(x-downX)<14)startVpn();
                    return true;
                }
                if(y>H-82){setTab(Math.min(2,(int)(x/W*3)));return true;}
                if(y<91){
                    if(x<85)hubPanel.open();
                    else if(x>W-85)showSettings();
                    return true;
                }
                if(tab==3){
                    if(y>=193&&y<309){quickBackup();return true;}
                    if(y>=328&&y<440){
                        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(pick,PICK_OVPN);return true;
                    }
                }
                if(tab==0){
                    if(y>serverY()&&y<serverY()+81){openVpnLocations();return true;}
                }else if(tab==1){
                    if(y>178&&y<215){
                        int next=Math.max(0,Math.min(2,(int)((x-21)/((W-46)/3))));
                        if(next!=locationMode){
                            locationMode=next;listOffset=0;listCacheAt=0;invalidate();
                            if(next==2&&hubPanel.entries("PROXY").isEmpty()){
                                hub.refreshCategory("PROXY",()->{
                                    listCacheAt=0;invalidate();
                                    if(hubPanel.entries("PROXY").isEmpty())
                                        Toast.makeText(MainActivity.this,"Telegram feeds unavailable; use manual MTProto add",Toast.LENGTH_LONG).show();
                                });
                            }
                        }
                        return true;
                    }
                    if(y>222&&y<271){
                        if(locationMode==0){
                            measureOpenVpn();
                            Toast.makeText(MainActivity.this,"Checking OpenVPN TCP ports…",Toast.LENGTH_SHORT).show();
                        }else refreshAll();
                        return true;
                    }
                    if(locationMode==1&&y>274&&y<315){countryPicker();return true;}
                    if(scrolling&&Math.abs(y-downY)>12){scrolling=false;return true;}
                    if(y>=(locationMode==1?320:280)&&y<H-95){
                        int index=(int)((y-(locationMode==1?320:280)+listOffset)/80);
                        if(index>=0&&index<rowCount()){
                            if(locationMode==0){
                                selectOpenVpnServer(index);
                            }else{
                                FeedParser.Entry entry=chosenEntries().get(index);
                                if(locationMode==1){
                                    selectManualNative(entry.value,true);
                                    setTab(0);
                                }else hubPanel.telegramConfirm(entry.value);
                            }
                        }return true;
                    }
                }
                scrolling=false;
            }
            return true;
        }
    }
}
