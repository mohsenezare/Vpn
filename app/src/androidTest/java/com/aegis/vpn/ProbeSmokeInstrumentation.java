package com.aegis.vpn;
import android.app.*;
import android.content.*;
import android.os.*;

/** No external test dependencies; exercises real Binder and native worker lifecycle. */
public final class ProbeSmokeInstrumentation extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){
  Bundle report=new Bundle();ProbeClient client=null;Activity activity=null;
  try{
   getTargetContext().getSharedPreferences("hub_five_v1",0).edit().putLong("attempt",System.currentTimeMillis()).commit();
   activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   client=new ProbeClient(getTargetContext());
   long invalid=client.test("{invalid JSON");
   if(invalid!=-1)throw new AssertionError("Native worker must reject invalid input without dying: "+invalid);
   String direct="{\"log\":{\"disabled\":true},\"outbounds\":[{\"type\":\"direct\",\"tag\":\"proxy\"}],\"route\":{\"final\":\"proxy\"}}";
   long nativeResult=client.test(direct);
   // External HTTPS can be unavailable in CI; process death is never acceptable.
   if(nativeResult==-3)throw new AssertionError("Native start/test/close killed worker");
   client.close();
   // Drain unbind before creating a new worker, then verify real process restart.
   waitForIdleSync();SystemClock.sleep(750);
   long afterRestart=client.test("{invalid JSON");
   if(afterRestart!=-1)throw new AssertionError("Worker did not restart: "+afterRestart);
   client.close();waitForIdleSync();SystemClock.sleep(750);
   SourceHub hub=new SourceHub(getTargetContext());
   org.json.JSONArray fixture=new org.json.JSONArray().put(new org.json.JSONObject().put("k","V2RAY")
    .put("v","vless://11111111-1111-4111-8111-111111111111@127.0.0.1:1#closed-local-test"));
   getTargetContext().getSharedPreferences("hub_five_v1",0).edit().clear()
    .putLong("attempt",System.currentTimeMillis()).putString(hub.sources().get(0),fixture.toString())
    .putLong(hub.sources().get(0)+"time",System.currentTimeMillis()).commit();
   getTargetContext().startForegroundService(new Intent(getTargetContext(),SingVpnService.class).setAction(SingVpnService.ACTION_SCAN));
   long deadline=SystemClock.elapsedRealtime()+35000;
   while(ProxyScanner.done==0&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
   while(ProxyScanner.busy&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
   if(ProxyScanner.done!=1||ProxyScanner.busy)throw new AssertionError("Foreground scan did not finish");
   if(ProxyScanner.best!=null)throw new AssertionError("Closed localhost port selected as working");
   final Activity checked=activity;runOnMainSync(()->{if(checked.isDestroyed())throw new AssertionError("UI destroyed by worker");});
   report.putString("stream","PROBE_SMOKE_PASSED: activity survives foreground scan, native test, invalid config and worker restart; HTTPS result="+nativeResult+"\n");
   finish(Activity.RESULT_OK,report);
  }catch(Throwable e){report.putString("stream","PROBE_SMOKE_FAILED: "+e+"\n");finish(Activity.RESULT_CANCELED,report);}
  finally{if(client!=null)client.close();}
 }
}
