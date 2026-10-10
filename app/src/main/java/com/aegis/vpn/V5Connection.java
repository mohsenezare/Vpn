package com.aegis.vpn;
import android.app.*;
import android.content.*;
import java.util.*;

/** V5 build-25 connection orchestration. UI-independent; no new retry/failover policy. */
final class V5Connection {
    static final int PREPARE_NATIVE=8293;
    final MainActivity activity;
    final Runnable changed;
    final SourceHub hub;
    final EndpointProbe probe=new EndpointProbe();
    final VpnController vpn;
    final ProfileStore profiles;
    final FreeDirectory directory;
    ArrayList<FreeDirectory.Node> servers;
    int selectedIndex;
    boolean paidMode,preferNative,updatingAll;
    String pendingNativeConfig;
    V5Connection(MainActivity activity,SourceHub hub,Runnable changed){
        this.activity=activity;this.hub=hub;this.changed=changed;
        profiles=new ProfileStore(activity);directory=new FreeDirectory(activity);servers=directory.load();
        android.content.SharedPreferences prefs=activity.getPreferences(0);
        paidMode=prefs.getBoolean("paid_mode",false);
        preferNative=prefs.getBoolean("prefer_native",true);
        selectedIndex=prefs.getInt("selected",0);
        vpn=new VpnController(activity,changed,this::info);
        RefreshJob.schedule(activity);
        if(hub.stale())hub.refresh(changed);
        if(directory.isStale())directory.update(list->{servers=list;if(selectedIndex>=servers.size())selectedIndex=0;changed.run();},error->{});
    }
    void info(String message){activity.info(message);}
    void persist(){activity.getPreferences(0).edit().putBoolean("paid_mode",paidMode).putBoolean("prefer_native",preferNative).putInt("selected",selectedIndex).apply();}
    void result(int request,int result){
        if(request==PREPARE_NATIVE){
            if(result==Activity.RESULT_OK&&pendingNativeConfig!=null){String config=pendingNativeConfig;pendingNativeConfig=null;startNativeService(config);}
            else{pendingNativeConfig=null;info("VPN permission was not granted.");}
        }else vpn.onActivityResult(request,result);
    }
    void stop(){pendingNativeConfig=null;stopNative();vpn.disconnect();}
    void close(){vpn.close();}
    void refreshAll(){
        if(updatingAll){info("A source update is already running.");return;}
        updatingAll=true;changed.run();
        final int[] outstanding={2};
        final String[] openVpnResult={"OpenVPN: update pending"};
        final String[] sourceResult={"Sources: update pending"};
        Runnable finished=()->{
            outstanding[0]--;
            if(outstanding[0]!=0)return;
            updatingAll=false;changed.run();
            info("Update complete.\n"+openVpnResult[0]+"\n"+sourceResult[0]+
                "\nSelection uses directory-reported ping, not a verified VPN connection.");
        };
        directory.update(list->{
            servers=list;
            if(!paidMode)selectedIndex=0;
            else if(selectedIndex>=servers.size())selectedIndex=0;
            persist();changed.run();
            openVpnResult[0]="OpenVPN: "+list.size()+" free servers updated"+
                (paidMode?" (paid profile preserved)":"; best advertised ping selected");
            finished.run();
        }, err->{
            openVpnResult[0]="OpenVPN: update failed; "+servers.size()+" cached. "+err;
            finished.run();
        });
        hub.refresh(()->{
            nativeCacheAt=0;
            sourceResult[0]="V2Ray: "+hub.entries("V2RAY").size()+
                "";
            List<FeedParser.Entry> measured=hub.entries("V2RAY");
            if(measured.isEmpty()||probe.busy){finished.run();return;}
            probe.test(measured,finished);
        });
    }
    private boolean hasNativeCache;
    private long nativeCacheAt;
    boolean hasNativeCandidates(){
        if(hub==null)return false;
        long now=android.os.SystemClock.elapsedRealtime();
        if(nativeCacheAt!=0&&now-nativeCacheAt<30000)return hasNativeCache;
        hasNativeCache=false;
        for(FeedParser.Entry e:hub.entries("V2RAY"))if(SingBoxConfig.supported(e.value)){
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
        Intent auth=android.net.VpnService.prepare(activity);
        if(auth!=null){pendingNativeConfig=config;activity.startActivityForResult(auth,PREPARE_NATIVE);}
        else startNativeService(config);
    }
    void startNativeService(String config){
        try{
            Intent intent=new Intent(activity,SingVpnService.class).setAction(SingVpnService.ACTION_START)
                .putExtra(SingVpnService.EXTRA_CONFIG,config);
            activity.startForegroundService(intent);
        }catch(Exception ex){info("Embedded VPN service could not start: "+ex.getMessage());}
    }
    void stopNative(){activity.startService(new Intent(activity,SingVpnService.class).setAction(SingVpnService.ACTION_STOP));}
    boolean isTunnelOn(){return vpn.state==VpnController.State.ON||SingVpnService.state==SingVpnService.TUNNEL_ACTIVE;}
    boolean isConnecting(){return vpn.state==VpnController.State.CONNECTING||SingVpnService.state==SingVpnService.STARTING;}
    void startVpn(){
        if(isTunnelOn()||isConnecting())return;
        String config=null;
        if(paidMode){
            try{config=profiles.getOpenVpnConfig();}
            catch(Exception e){info("Could not decrypt paid profile.");return;}
            if(config==null){info("Import your paid .ovpn file first.");return;}
        }else if(preferNative&&hasNativeCandidates()){
            ArrayList<FeedParser.Entry> choices=new ArrayList<>(hub.entries("V2RAY"));
            choices.sort((a,b)->{
                long ra=probe.rank(a.value),rb=probe.rank(b.value);
                if(ra!=rb)return Long.compare(ra,rb);
                return Boolean.compare(!a.value.startsWith("vless://"),!b.value.startsWith("vless://"));
            });
            ArrayList<String> candidates=new ArrayList<>();
            for(FeedParser.Entry e:choices){
                if(candidates.size()>=12)break;
                if(SingBoxConfig.supported(e.value))candidates.add(e.value);
            }
            try{connectNative(SingBoxConfig.buildAuto(candidates));}
            catch(Exception ex){info("No supported native proxy config: "+ex.getMessage());}
            return;
        }else if(!servers.isEmpty()){
            config=servers.get(Math.min(selectedIndex,servers.size()-1)).config;
        }else if(hasNativeCandidates()){
            preferNative=true;persist();startVpn();return;
        }else{
            info("No available configuration. Tap Smart update. Public config counts are not evidence that nodes work.");return;
        }
        vpn.connect(config);changed.run();
    }

}
