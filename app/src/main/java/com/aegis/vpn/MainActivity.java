package com.aegis.vpn;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
 final int INK=0xff162025,MUTED=0xff7f8b98,ORANGE=0xffff7528,GREEN=0xff08bb78;
 private ProfileStore profiles;
 private Screen screen;
 private SourceHub hub;
 private HubPanel hubPanel;
 private int tab;
 private String selected;
 private String pendingNativeConfig;
 private boolean pendingScan;
 private static final int PREPARE_NATIVE=8292;
 boolean updatingAll;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final TrafficMeter trafficMeter=new TrafficMeter();
 private List<FeedParser.Entry> servers=new ArrayList<>();
 private final Runnable trafficPolling=new Runnable(){public void run(){
  trafficMeter.sample(SystemClock.elapsedRealtime(),android.net.TrafficStats.getTotalRxBytes(),android.net.TrafficStats.getTotalTxBytes(),isTunnelOn());
  screen.invalidate();handler.postDelayed(this,1000);
 }};
 private final BroadcastReceiver nativeEvents=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
  if(intent.getBooleanExtra("scan",false)&&!ProxyScanner.busy){
   reload();updatingAll=false;
  }
  screen.invalidate();AegisShortcuts.publish(MainActivity.this,isTunnelOn(),isConnecting());
  if(intent.getIntExtra("state",0)==SingVpnService.FAILED)info("VPN error: "+intent.getStringExtra("message"));
 }};
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);getWindow().setStatusBarColor(0xfffafafa);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);getWindow().setNavigationBarColor(0xfff9fafb);
  profiles=new ProfileStore(this);hub=new SourceHub(this);hubPanel=new HubPanel(this,hub);
  if(!getPreferences(0).getBoolean("five_feeds_migrated",false)){
   profiles.clearNative();getSharedPreferences("paid",0).edit().clear().apply();getSharedPreferences("hub",0).edit().clear().apply();
   getPreferences(0).edit().clear().putBoolean("five_feeds_migrated",true).apply();
  }
  reload();screen=new Screen();setContentView(screen);handler.post(trafficPolling);
  IntentFilter filter=new IntentFilter(SingVpnService.ACTION_STATUS);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(nativeEvents,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(nativeEvents,filter);
  RefreshJob.schedule(this);
  if(hub.stale())hub.refresh(()->{reload();screen.invalidate();});
  handler.post(()->{AegisShortcuts.maybeAskNotificationPermission(this);handleQuickAction(getIntent());});
 }
 void reload(){
  servers=hub.entries("V2RAY");ProxyScanner.sort(servers);
  try{selected=profiles.getNative();}catch(Exception e){selected=null;}
  boolean found=false;for(FeedParser.Entry e:servers)if(e.value.equals(selected)){found=true;break;}
  if(!found){selected=null;profiles.clearNative();}
 }
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleQuickAction(intent);}
 void handleQuickAction(Intent intent){if(intent!=null&&AegisShortcuts.ACTION_TOGGLE.equals(intent.getAction())){intent.setAction(Intent.ACTION_MAIN);if(isTunnelOn())stopNative();else if(!isConnecting())startVpn();}}
 @Override public void onRequestPermissionsResult(int c,String[] p,int[] r){super.onRequestPermissionsResult(c,p,r);AegisShortcuts.publish(this,isTunnelOn(),isConnecting());}
 @Override protected void onDestroy(){try{unregisterReceiver(nativeEvents);}catch(Exception ignored){}handler.removeCallbacksAndMessages(null);super.onDestroy();}
 @Override @Deprecated protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==PREPARE_NATIVE){
   if(result==RESULT_OK){if(pendingScan){pendingScan=false;scan();}else if(pendingNativeConfig!=null){String config=pendingNativeConfig;pendingNativeConfig=null;startNativeService(config);}}
   else{pendingScan=false;pendingNativeConfig=null;updatingAll=false;info("VPN permission was not granted.");}
  }
 }
 void info(String msg){if(!isFinishing()&&!isDestroyed())new GlassDialog.Builder(this).setMessage(msg==null?"Unknown error":msg).setPositiveButton("OK",null).show();}
 void refreshAll(){
  if(updatingAll||ProxyScanner.busy){info(ProxyScanner.progress());return;}
  updatingAll=true;screen.invalidate();
  hub.refresh(()->{if(isDestroyed())return;reload();if(servers.isEmpty()){updatingAll=false;info("No configurations downloaded.\n"+hub.report());return;}scan();});
 }
 void scan(){
  if(ProxyScanner.busy){info(ProxyScanner.progress());return;}
  Intent permission=android.net.VpnService.prepare(this);
  if(permission!=null){pendingScan=true;startActivityForResult(permission,PREPARE_NATIVE);return;}
  try{startForegroundService(new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_SCAN));}
  catch(Exception e){updatingAll=false;info("Cannot start tests: "+e.getMessage());}
 }
 void connectNativeEntry(String link){
  if(ProxyScanner.busy||updatingAll){info("Wait for tests to finish or cancel them first.");return;}
  try{String config=SingBoxConfig.build(link);profiles.saveNative(link);selected=link;replaceNative(config);screen.invalidate();}
  catch(Exception e){info("Unsupported configuration: "+e.getMessage());}
 }
 void replaceNative(String config){
  if(!isTunnelOn()&&!isConnecting()){connectNative(config);return;}
  stopNative();handler.postDelayed(new Runnable(){int tries;public void run(){if(isTunnelOn()||isConnecting()){if(++tries<30)handler.postDelayed(this,200);else info("Previous tunnel is still stopping.");}else connectNative(config);}},200);
 }
 void connectNative(String config){Intent permission=android.net.VpnService.prepare(this);if(permission!=null){pendingNativeConfig=config;startActivityForResult(permission,PREPARE_NATIVE);}else startNativeService(config);}
 void startNativeService(String config){try{startForegroundService(new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_START).putExtra(SingVpnService.EXTRA_CONFIG,config));}catch(Exception e){info(e.getMessage());}}
 void stopNative(){startService(new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_STOP));}
 boolean isTunnelOn(){return SingVpnService.state==SingVpnService.TUNNEL_ACTIVE;}
 boolean isConnecting(){return SingVpnService.state==SingVpnService.STARTING;}
 void startVpn(){
  if(isTunnelOn()||isConnecting())return;
  if(ProxyScanner.busy||updatingAll){info("Testing configurations. The fastest successful result will be selected when complete.");return;}
  reload();
  if(selected==null){refreshAll();return;}
  try{connectNative(SingBoxConfig.build(selected));}catch(Exception e){info(e.getMessage());}
 }
 void showSettings(){hubPanel.open();}
    private class Screen extends View {
        final Paint p=new Paint(3), text=new Paint(3);
        final FrostGlass frost=new FrostGlass();
        float pageAlpha=1f;
        android.animation.ValueAnimator sliderAnimator;
        float density,W,H,downX,downY,dragOffset=0,phase=0;boolean dragging=false;
        final android.animation.ValueAnimator motion=android.animation.ValueAnimator.ofFloat(0,1);
        Bitmap icon;
        Screen(){
            super(MainActivity.this);setLayerType(View.LAYER_TYPE_HARDWARE,null);
            icon=BitmapFactory.decodeResource(getResources(),R.drawable.app_icon);
            density=getResources().getDisplayMetrics().density;
            motion.setDuration(3700);motion.setInterpolator(new android.view.animation.PathInterpolator(.25f,.05f,.25f,1f));
            motion.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            motion.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            motion.addUpdateListener(a->{phase=(float)a.getAnimatedValue();invalidate();});
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();motion.start();}
        @Override protected void onDetachedFromWindow(){motion.cancel();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(motion!=null){if(visibility==VISIBLE)motion.resume();else motion.pause();}}
        void fill(Canvas c,int color){c.drawColor(color);}
        void changeTab(int next){
            if(next==tab)return;
            tab=next;pageAlpha=.78f;
            android.animation.ValueAnimator show=android.animation.ValueAnimator.ofFloat(.78f,1f);
            show.setDuration(320);
            show.setInterpolator(new android.view.animation.PathInterpolator(.18f,.8f,.22f,1f));
            show.addUpdateListener(a->{pageAlpha=(float)a.getAnimatedValue();invalidate();});
            show.start();invalidate();
        }
        void settleSlider(){
            if(sliderAnimator!=null)sliderAnimator.cancel();
            sliderAnimator=android.animation.ValueAnimator.ofFloat(dragOffset,0f);
            sliderAnimator.setDuration(390);
            sliderAnimator.setInterpolator(new android.view.animation.OvershootInterpolator(.72f));
            sliderAnimator.addUpdateListener(a->{dragOffset=(float)a.getAnimatedValue();invalidate();});
            sliderAnimator.start();
        }
        void card(Canvas c,float x,float y,float w,float h,float r,int bg,int border){
            frost.ensure(W,H,isTunnelOn());
            frost.draw(c,x,y,w,h,r,bg,border,W,H);
            p.reset();p.setAntiAlias(true);p.setStyle(Paint.Style.FILL);
        }
        void gradient(Canvas c,float x,float y,float w,float h,float radius,int a,int b){
            p.setShader(new LinearGradient(x,y,x+w,y+h,a,b,Shader.TileMode.CLAMP));
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);p.setShader(null);
        }
        void txt(Canvas c,String s,float x,float y,float size,int color,boolean bold){
            text.reset();text.setAntiAlias(true);text.setColor(color);text.setTextSize(size);
            text.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
            c.drawText(s,x,y,text);
        }
        void center(Canvas c,String s,float x,float y,float size,int col,boolean bold){
            text.setTextSize(size);text.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
            txt(c,s,x-text.measureText(s)/2,y,size,col,bold);
        }
        void icon(Canvas c,float x,float y,float size){
            if(icon!=null){
                c.save();Path path=new Path();path.addRoundRect(x,y,x+size,y+size,size*.22f,size*.22f,Path.Direction.CW);
                c.clipPath(path);p.setColor(0xffffffff);c.drawBitmap(icon,null,new RectF(x,y,x+size,y+size),p);c.restore();
            } else {txt(c,"◆",x+size*.1f,y+size*.8f,size*.7f,ORANGE,true);}
        }
        void roundedCircle(Canvas c,float x,float y,float r,int color){
            p.setColor(color);p.setShader(null);c.drawCircle(x,y,r,p);
        }
        // Native iOS-style activity indicator: 12 softly fading capsules inside
        // the existing white knob. Runs only while a real connection is starting.
        // A monotonic clock gives smooth clockwise motion without new animators.
        void drawIosConnectingSpinner(Canvas c,float cx,float cy){
            final int count=12;
            float head=(android.os.SystemClock.uptimeMillis()%1080L)*(count/1080f);
            c.save();
            c.translate(cx,cy);
            p.setShader(null);p.setAntiAlias(true);p.setStyle(Paint.Style.FILL);
            for(int i=0;i<count;i++){
                float age=(head-i+count)%count;
                float fade=(count-age)/count;
                int alpha=44+(int)(191f*fade*fade);
                p.setColor((alpha<<24)|0x006b7682);
                c.save();
                c.rotate(i*30f);
                c.drawRoundRect(-1.75f,-19f,1.75f,-10.5f,1.75f,1.75f,p);
                c.restore();
            }
            c.restore();
        }
        @Override protected void onDraw(Canvas raw){
            density=Math.min(getResources().getDisplayMetrics().density,getHeight()/720f);
            W=getWidth()/density;H=getHeight()/density;
            raw.save();raw.scale(density,density);
            Canvas c=raw;
            fill(c,0xfffbfcfc);
            int active=isTunnelOn()?GREEN:ORANGE;
            p.setShader(new RadialGradient(W*(.78f+phase*.17f),H*.30f,W*.87f,
                new int[]{(isTunnelOn()?0xc02ce39a:0xc0ffad47),0x00ffffff},null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,H,p);p.setShader(null);
            p.setShader(new RadialGradient(-W*.20f,H*.76f,W*.85f,new int[]{0x46dceee8,0x00ffffff},null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,W,H,p);p.setShader(null);
            // Translucent curved glass ribbons, inspired by the approved reference.
            for(int j=0;j<3;j++){
                Path ribbon=new Path();float yy=H*(.20f+j*.10f)+phase*18;
                ribbon.moveTo(W+50,yy-140);ribbon.cubicTo(W*.20f,yy+90,W*1.25f,yy+150,-50,yy+320);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.3f);p.setColor(0xafffffff);c.drawPath(ribbon,p);
                p.setStrokeWidth(24);p.setColor(0x17ffffff);c.drawPath(ribbon,p);p.setStyle(Paint.Style.FILL);
            }
            c.saveLayerAlpha(0,0,W,H,Math.min(255,Math.round(255*pageAlpha)));
            if(tab==0)home(c,active);else if(tab==1)locations(c);else stats(c);
            c.restore();
            navbar(c);
            raw.restore();
        }
        void header(Canvas c){
            roundedCircle(c,42,48,22,0xdfffffff);
            txt(c,"⠿",32,56,23,0xff525e67,true);
            center(c,"VPN",W/2,55,17,INK,true);
            roundedCircle(c,W-42,48,22,0xeaffffff);
            txt(c,"⚙",W-52,55,22,0xff52616a,false);
        }
        float sliderY(){return H*.435f;}
        float serverY(){return Math.min(Math.max(sliderY()+144,H*.69f),H-294);}
        void home(Canvas c,int active){
            header(c);
            float ty=Math.min(148,H*.185f);
            txt(c,"Private.",26,ty,33,INK,true);
            txt(c,"Secure.",26,ty+36,33,INK,true);
            txt(c,"Everywhere.",26,ty+72,33,0xff87929f,true);
            float sy=sliderY(),sw=W-48,sh=90,x=24;
            p.setStyle(Paint.Style.STROKE);p.setColor(active==GREEN?0x2393edc5:0x28ffb978);
            p.setStrokeWidth(1);c.drawCircle(W/2,sy+45,98,p);c.drawCircle(W/2,sy+45,112,p);
            p.setStyle(Paint.Style.FILL);
            // Compact live speed display in the existing gap above the mode selector.
            card(c,18,sy-106,W-36,37,18,0x90ffffff,0xffffffff);
            txt(c,"↓",34,sy-81,18,GREEN,true);
            txt(c,trafficMeter.download(),56,sy-81,14,INK,true);
            txt(c,"↑",W/2+7,sy-81,18,ORANGE,true);
            txt(c,trafficMeter.upload(),W/2+29,sy-81,14,INK,true);
            // Dedicated mode selector above the unchanged connection slider.
            card(c,24,sy-58,W-48,44,19,0xbbe4fff2,0xff6dd4aa);
            center(c,"V2Ray · Five sources",W/2,sy-31,14,0xff13875a,true);
            card(c,x-2,sy-3,sw+4,sh+6,52,0x90ffffff,0x99ffffff);
            gradient(c,x,sy,sw,sh,48,
                isTunnelOn()?0xff00c77d: isConnecting()?0xffffac53:0xffffab43,
                isTunnelOn()?0xff03a773: isConnecting()?0xffff6c24:0xffff6f18);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(0xcaffffff);
            c.drawRoundRect(x+1,sy+1,x+sw-1,sy+sh-1,48,48,p);p.setStyle(Paint.Style.FILL);
            float knobX=isTunnelOn()?x+sw-45+dragOffset:x+45+dragOffset;
            roundedCircle(c,knobX,sy+45,39,0xfffefefe);
            if(isTunnelOn()){
                txt(c,"✓",knobX-17,sy+60,48,GREEN,true);
                txt(c,"Tunnel active",x+28,sy+53,18,0xffffffff,true);
            }else{
                if(isConnecting())drawIosConnectingSpinner(c,knobX,sy+45);
                else icon(c,knobX-27,sy+18,54);
                txt(c,isConnecting()?"Starting tunnel…":"Slide to connect",
                    x+106,sy+53,16,0xffffffff,true);
            }
            String secondary=isTunnelOn()?"Tunnel active · V2Ray":
                isConnecting()?"Starting VPN engine…":"Slide the button to connect";
            center(c,secondary,W/2,sy+sh+37,12,isTunnelOn()?0xff17895e:MUTED,false);
            float cy=serverY();
            card(c,18,cy,W-36,88,25,0xeaffffff,0xffffffff);
            roundedCircle(c,62,cy+44,23,0xffeef7f4);
            txt(c,"🌐",47,cy+54,27,ORANGE,true);
            String name=selected==null?"V2Ray · select best":"V2Ray · selected server";
            String subtitle=selected==null?servers.size()+" configs · update and test":ProxyScanner.label(selected);
            txt(c,name,98,cy+38,15,INK,true);
            txt(c,subtitle.length()>32?subtitle.substring(0,31)+"…":subtitle,98,cy+61,11,MUTED,false);
            txt(c,"›",W-49,cy+56,30,MUTED,false);
            card(c,18,cy+98,W-36,62,20,0xeaffffff,0xffffffff);
            txt(c,ProxyScanner.busy?"↻ Testing "+ProxyScanner.done+" / "+ProxyScanner.total:updatingAll?"↻ Updating five sources…":"↻ Update · test · select best",35,cy+126,16,ORANGE,true);
            txt(c,ProxyScanner.progress(),35,cy+146,11,MUTED,false);
            card(c,18,cy+168,W-36,42,18,0xcfffffff,0xffffffff);
            center(c,"V2Ray  ·  Server library  ›",W/2,cy+195,12,INK,true);
        }
        void locations(Canvas c){
            header(c);txt(c,"V2Ray servers",24,140,32,INK,true);
            txt(c,servers.size()+" configs · five sources · HTTPS delay",24,167,12,MUTED,false);
            card(c,18,185,W-36,57,20,0xeaffffff,0xffffffff);
            txt(c,"↻ Update, test and select best",37,222,16,ORANGE,true);
            int visible=Math.min(servers.size(),Math.max(0,(int)((H-335)/76)));
            for(int i=0;i<visible;i++){
                float y=260+i*76;FeedParser.Entry node=servers.get(i);
                card(c,18,y,W-36,68,20,0xeeffffff,0xffffffff);
                txt(c,HubPanel.name(node),38,y+28,14,INK,true);
                txt(c,ProxyScanner.label(node.value),38,y+49,11,MUTED,false);
                if(node.value.equals(selected))txt(c,"✓",W-60,y+40,24,GREEN,true);
            }
        }
        void stats(Canvas c){
            header(c);txt(c,"Statistics",24,140,32,INK,true);
            card(c,18,177,W-36,130,26,0xeaffffff,0xffffffff);
            txt(c,"Tunnel status",40,221,13,MUTED,false);
            txt(c,isTunnelOn()?"Tunnel active":
                isConnecting()?"Connecting...":"Disconnected",
                40,257,22,isTunnelOn()?GREEN:INK,true);
            card(c,18,323,W-36,150,26,0xeaffffff,0xffffffff);
            txt(c,"Download",40,368,16,MUTED,false);txt(c,trafficMeter.download(),W-145,368,17,INK,true);
            txt(c,"Upload",40,423,16,MUTED,false);txt(c,trafficMeter.upload(),W-145,423,17,INK,true);
            txt(c,"Live device traffic · not VPN-only · active tunnel",24,506,12,MUTED,false);
        }
        void navbar(Canvas c){
            float top=H-74;
            card(c,0,top,W,80,23,0xf7ffffff,0xffeeeeee);
            String[] labels={"Home","Servers","Stats"};
            String[] icons={"⌂","◎","▥"};
            for(int i=0;i<3;i++){
                float cx=W*(i+.5f)/3;
                center(c,icons[i],cx,top+35,25,tab==i?ORANGE:MUTED,true);
                center(c,labels[i],cx,top+57,11,tab==i?ORANGE:MUTED,false);
            }
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            float x=e.getX()/density,y=e.getY()/density,sy=sliderY();
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                if(sliderAnimator!=null)sliderAnimator.cancel();
                downX=x;downY=y;dragging=tab==0&&y>sy&&y<sy+94;
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_CANCEL){dragging=false;settleSlider();return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE&&dragging){dragOffset=isTunnelOn()?Math.max(-(W-138),Math.min(0,x-downX)):Math.min(W-138,Math.max(0,x-downX));invalidate();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){
                if(dragging){
                    dragging=false;settleSlider();performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    if(!isTunnelOn()&&!isConnecting() && x-downX>Math.min(70,W*.25f))startVpn();
                    else if(isTunnelOn()&&downX-x>Math.min(70,W*.25f)){
                        if(SingVpnService.state==SingVpnService.TUNNEL_ACTIVE)stopNative();

                    }
                    return true;
                }
                if(y>H-85){changeTab(Math.min(2,(int)(x/W*3)));return true;}
                if(y<85){if(x>W-85)showSettings();else if(x<85)hubPanel.open();return true;}
                if(tab==0&&y>=sy-58&&y<=sy-14&&x>=24&&x<=W-24){
                    hubPanel.list("V2RAY");return true;
                }
                if(tab==0&&y>serverY()+168&&y<serverY()+210){hubPanel.open();return true;}
                if(tab==0&&y>serverY()+98&&y<serverY()+160){refreshAll();return true;}
                if(tab==0&&y>serverY()&&y<serverY()+88){hubPanel.list("V2RAY");return true;}
                if(tab==1){
                    if(y>185&&y<247){refreshAll();return true;}
                    if(y>=260){int i=(int)((y-260)/76);if(i>=0&&i<servers.size()){
                        hubPanel.entry(servers.get(i));return true;}}
                }
            }
            return true;
        }
    }
}
