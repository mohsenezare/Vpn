package com.aegis.vpn;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import io.nekohasekai.libbox.*;
import java.util.concurrent.*;

/** Native tests run in :probe. A native abort must never terminate the UI or VPN. */
public final class ProbeWorkerService extends Service {
 static final int TEST=1,STOP=2;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private boolean initialized;
 private final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper()){
  @Override public void handleMessage(Message m){
   if(m.what==STOP){android.os.Process.killProcess(android.os.Process.myPid());return;}
   if(m.what!=TEST||m.replyTo==null)return;
   final String config=m.getData().getString("config");final int id=m.arg1;final Messenger reply=m.replyTo;
   worker.execute(()->{
    long delay=-1;String reason="HTTPS test failed";BoxService core=null;SingBoxPlatform platform=null;
    try{
     if(!initialized){
      java.io.File dir=new java.io.File(getFilesDir(),"probe");dir.mkdirs();
      SetupOptions options=new SetupOptions();options.setBasePath(dir.getAbsolutePath());options.setWorkingPath(dir.getAbsolutePath());options.setTempPath(getCacheDir().getAbsolutePath());
      Libbox.setup(options);initialized=true;
     }
     platform=new SingBoxPlatform(ProbeWorkerService.this);
     core=Libbox.newService(config,platform);core.start();delay=core.measureOutbound("proxy",6000);reason="";
    }catch(Throwable failure){reason="Core rejected config or HTTPS test failed";}
    finally{
     if(core!=null)try{core.close();}catch(Throwable ignored){}
     if(platform!=null)try{platform.shutdown();}catch(Throwable ignored){}
    }
    Message result=Message.obtain(null,TEST);result.arg1=id;
    Bundle data=new Bundle();data.putLong("delay",delay);data.putString("reason",reason);result.setData(data);
    try{reply.send(result);}catch(RemoteException ignored){}
   });
  }
 });
 @Override public IBinder onBind(Intent intent){return messenger.getBinder();}
 @Override public boolean onUnbind(Intent intent){stopSelf();return false;}
 @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}
}
