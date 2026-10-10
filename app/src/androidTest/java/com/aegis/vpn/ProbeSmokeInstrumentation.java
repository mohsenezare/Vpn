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
   final Activity checked=activity;runOnMainSync(()->{if(checked.isDestroyed())throw new AssertionError("UI destroyed by worker");});
   report.putString("stream","PROBE_SMOKE_PASSED: activity survives native test, invalid config and worker restart; HTTPS result="+nativeResult+"\n");
   finish(Activity.RESULT_OK,report);
  }catch(Throwable e){report.putString("stream","PROBE_SMOKE_FAILED: "+e+"\n");finish(Activity.RESULT_CANCELED,report);}
  finally{if(client!=null)client.close();}
 }
}
