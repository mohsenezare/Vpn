package com.aegis.vpn;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** One request at a time, with a deadline and explicit Binder-death recovery. */
final class ProbeClient implements AutoCloseable {
 private final Context context;
 private final Handler main=new Handler(Looper.getMainLooper());
 private volatile Messenger remote;
 private volatile CountDownLatch connected,pending;
 private ServiceConnection connection;
 private volatile long result=-3;
 private volatile int request;
 private final Messenger replies=new Messenger(new Handler(Looper.getMainLooper()){
  @Override public void handleMessage(Message m){if(m.arg1==request&&pending!=null){result=m.getData().getLong("delay",-1);pending.countDown();}}
 });
 ProbeClient(Context c){context=c.getApplicationContext();}
 private void connect()throws Exception{
  if(remote!=null)return;
  CountDownLatch ready=new CountDownLatch(1);connected=ready;
  ServiceConnection next=new ServiceConnection(){
   public void onServiceConnected(ComponentName n,IBinder binder){if(connection!=this)return;remote=new Messenger(binder);ready.countDown();}
   public void onServiceDisconnected(ComponentName n){lost();}
   public void onBindingDied(ComponentName n){lost();}
   public void onNullBinding(ComponentName n){lost();}
   void lost(){if(connection!=this)return;remote=null;ready.countDown();CountDownLatch p=pending;if(p!=null)p.countDown();}
  };
  connection=next;
  main.post(()->{if(connection!=next){ready.countDown();return;}try{if(!context.bindService(new Intent(context,ProbeWorkerService.class),next,Context.BIND_AUTO_CREATE))ready.countDown();}catch(Exception e){ready.countDown();}});
  if(!ready.await(8,TimeUnit.SECONDS)||remote==null)throw new IllegalStateException("Test worker unavailable");
 }
 long test(String config){
  try{
   connect();result=-3;CountDownLatch done=new CountDownLatch(1);pending=done;
   Message m=Message.obtain(null,ProbeWorkerService.TEST);m.arg1=++request;m.replyTo=replies;
   Bundle b=new Bundle();b.putString("config",config);m.setData(b);remote.send(m);
   if(!done.await(25,TimeUnit.SECONDS)){close();return -3;}
   if(result==-3)close();return result;
  }catch(Exception e){close();return -3;}
  finally{pending=null;}
 }
 public void close(){
  CountDownLatch waiting=pending;if(waiting!=null)waiting.countDown();
  Messenger old=remote;remote=null;
  if(old!=null)try{old.send(Message.obtain(null,ProbeWorkerService.STOP));}catch(Exception ignored){}
  ServiceConnection oldConnection=connection;connection=null;
  if(oldConnection!=null)main.post(()->{try{context.unbindService(oldConnection);}catch(Exception ignored){}});
 }
}
