package com.aegis.vpn;
import android.app.*;
import android.content.*;
import android.os.*;
import de.blinkt.openvpn.api.*;
/** Remote AIDL controller. Owns no VPN tunnel; status is emitted by external client. */
public final class VpnController {
    enum State { OFF, CONNECTING, ON }
    State state=State.OFF;
    private static final String PACKAGE="de.blinkt.openvpn";
    private static final int ASK_APP=8841,ASK_VPN=8842;
    private final Activity activity;
    private final Runnable changed;
    private final java.util.function.Consumer<String> message;
    private IOpenVPNAPIService remote;
    private String pending;
    private boolean bound=false;
    private boolean requesting=false;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final Runnable timeout=this::onTimeout;
    private void onTimeout(){if(state==State.CONNECTING){disconnect();message.accept("The server did not establish a tunnel within 60 seconds. Try another server or network; importing a profile does not prove it is reachable.");}}
    VpnController(Activity activity,Runnable changed,java.util.function.Consumer<String> message){
        this.activity=activity;this.changed=changed;this.message=message;
    }
    private final IOpenVPNStatusCallback callback=new IOpenVPNStatusCallback.Stub(){
        @Override public void newStatus(String uuid,String status,String text,String level){
            activity.runOnUiThread(()->{
                String s=status==null?"":status.toUpperCase(java.util.Locale.ROOT);
                if(s.equals("CONNECTED")){timer.removeCallbacks(timeout);state=State.ON;changed.run();}
                else if(s.equals("AUTH_FAILED")){
                    state=State.OFF;changed.run();message.accept("OpenVPN authentication failed.");
                }else if(s.equals("NOPROCESS")||s.equals("EXITING")||s.equals("DISCONNECTED")){
                    state=State.OFF;changed.run();
                }else if(s.equals("CONNECTING")||s.equals("WAIT")||s.equals("AUTH")||
                         s.equals("GET_CONFIG")||s.equals("ASSIGN_IP")||s.equals("RECONNECTING")){
                    state=State.CONNECTING;changed.run();
                }
            });
        }
    };
    private final ServiceConnection service=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            remote=IOpenVPNAPIService.Stub.asInterface(binder);
            beginAuthorized();
        }
        @Override public void onServiceDisconnected(ComponentName name){
            remote=null;state=State.OFF;activity.runOnUiThread(changed);
        }
    };
    void connect(String ovpn){
        if(state!=State.OFF||ovpn==null||ovpn.isEmpty())return;
        state=State.CONNECTING;changed.run();pending=ovpn;requesting=true;
        try{
            if(remote==null){
                Intent i=new Intent("de.blinkt.openvpn.api.IOpenVPNAPIService").setPackage(PACKAGE);
                if(!activity.bindService(i,service,Context.BIND_AUTO_CREATE))
                    fail("Install the free 'OpenVPN for Android' app to connect (package de.blinkt.openvpn).");
                else bound=true;
            } else beginAuthorized();
        }catch(Exception e){fail("Could not start OpenVPN: "+e.getClass().getSimpleName());}
    }
    @SuppressWarnings("deprecation")
    private void beginAuthorized(){
        if(!requesting||remote==null)return;
        try{
            Intent a=remote.prepare(activity.getPackageName());
            if(a!=null){activity.startActivityForResult(a,ASK_APP);return;}
            Intent b=remote.prepareVPNService();
            if(b!=null){activity.startActivityForResult(b,ASK_VPN);return;}
            remote.registerStatusCallback(callback);
            remote.startVPN(pending);
            timer.removeCallbacks(timeout);timer.postDelayed(timeout,60000);
            pending=null;requesting=false;
        }catch(Exception e){fail("OpenVPN service authorization/start failed: "+e.getMessage());}
    }
    void onActivityResult(int code,int result){
        if(code!=ASK_APP&&code!=ASK_VPN)return;
        if(result!=Activity.RESULT_OK)fail("VPN permission not granted.");
        else beginAuthorized();
    }
    void disconnect(){
        timer.removeCallbacks(timeout);
        try{if(remote!=null)remote.disconnect();}catch(Exception ignored){}
        requesting=false;pending=null;state=State.OFF;changed.run();
    }
    void fail(String why){pending=null;requesting=false;state=State.OFF;changed.run();message.accept(why);}
    void close(){
        timer.removeCallbacks(timeout);
        try{if(remote!=null)remote.unregisterStatusCallback(callback);}catch(Exception ignored){}
        if(bound){try{activity.unbindService(service);}catch(Exception ignored){}}
        remote=null;bound=false;
    }
}
