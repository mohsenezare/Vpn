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
    final int INK=0xff162025, MUTED=0xff7f8b98, ORANGE=0xffff7528, GREEN=0xff08bb78;
    private VpnController vpn;
    private ProfileStore profiles;
    private FreeDirectory directory;
    private Screen screen;
    private SourceHub hub;
    private HubPanel hubPanel;
    private int tab=0, selectedIndex=0;
    private boolean paidMode=false;
    private boolean preferNative=true;
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

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xfffafafa);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(0xfff9fafb);
        profiles=new ProfileStore(this);
        directory=new FreeDirectory(this);
        servers=directory.load();
        paidMode=getPreferences(MODE_PRIVATE).getBoolean("paid_mode",false);
        preferNative=getPreferences(MODE_PRIVATE).getBoolean("prefer_native",true);
        selectedIndex=getPreferences(MODE_PRIVATE).getInt("selected",0);
        vpn=new VpnController(this, () -> screen.invalidate(), msg -> {
            new GlassDialog.Builder(this).setTitle("OpenVPN").setMessage(msg).setPositiveButton("OK",null).show();
            screen.invalidate();
        });
        screen=new Screen();
        setContentView(screen);
        hub=new SourceHub(this);hubPanel=new HubPanel(this,hub);
        IntentFilter nativeFilter=new IntentFilter(SingVpnService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(nativeEvents,nativeFilter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(nativeEvents,nativeFilter);
        RefreshJob.schedule(this);
        if(hub.stale())hub.refresh(()->screen.invalidate());
        if(directory.isStale()) refresh(false);
    }
    @Override protected void onDestroy(){
        try{unregisterReceiver(nativeEvents);}catch(Exception ignored){}
        vpn.close();super.onDestroy();
    }
    @Override @Deprecated protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PREPARE_NATIVE){
            if(result==RESULT_OK&&pendingNativeConfig!=null){
                String config=pendingNativeConfig;pendingNativeConfig=null;startNativeService(config);
            }else{pendingNativeConfig=null;info("VPN permission was not granted.");}
            return;
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
    private String readLimited(Uri uri)throws Exception{
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>524288) throw new Exception("OpenVPN profile exceeds 512 KB.");}
            return out.toString("UTF-8");
        }
    }
    void info(String msg){new GlassDialog.Builder(this).setMessage(msg==null?"Unknown error":msg).setPositiveButton("OK",null).show();}
    void refresh(boolean notify){
        if(notify) Toast.makeText(this,"Updating free VPN Gate servers...",Toast.LENGTH_SHORT).show();
        directory.update(list -> {
            servers=list;
            if(selectedIndex>=servers.size()) selectedIndex=0;
            screen.invalidate();
            if(notify) Toast.makeText(this, list.size()+" free servers available",Toast.LENGTH_SHORT).show();
        }, err -> {if(notify)info("Directory refresh failed. Last saved servers retained.\n"+err);});
    }
    /** Update OpenVPN and all public config sources, then pick the lowest directory-reported
     * ping for free mode. This is not proof of a successful VPN handshake. */
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
                "\nSelection uses directory-reported ping, not a verified VPN connection.");
        };
        directory.update(list->{
            servers=list;
            if(!paidMode)selectedIndex=0;
            else if(selectedIndex>=servers.size())selectedIndex=0;
            persist();screen.invalidate();
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
                " | Proxies: "+hub.entries("PROXY").size()+
                " | NapsternetV: "+hub.entries("NAPSTERNETV").size();
            List<FeedParser.Entry> measured=hub.entries("V2RAY");
            if(measured.isEmpty()||hubPanel.probe.busy){finished.run();return;}
            hubPanel.probe.test(measured,finished);
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
        .putBoolean("prefer_native",preferNative).putInt("selected",selectedIndex).apply();}
    void selectServer(){
        if(servers.isEmpty()){info("No cached free OpenVPN relay. Try embedded V2Ray or refresh the public mirror.");return;}
        String[] items=new String[Math.min(servers.size(),75)];
        for(int i=0;i<items.length;i++)items[i]=servers.get(i).title();
        new GlassDialog.Builder(this).setTitle("Free OpenVPN servers")
            .setSingleChoiceItems(items,Math.min(selectedIndex,items.length-1),(d,which)->{
                selectedIndex=which;paidMode=false;preferNative=false;persist();d.dismiss();screen.invalidate();
            }).setNegativeButton("Close",null).show();
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
    void stopNative(){startService(new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_STOP));}
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
                long ra=hubPanel.probe.rank(a.value),rb=hubPanel.probe.rank(b.value);
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
        vpn.connect(config);screen.invalidate();
    }
    void showSettings(){
        final String[] actions={"Smart update all · choose best free server",
            "Refresh free OpenVPN servers","Choose free OpenVPN server",
            "Import paid .ovpn account","Use purchased OpenVPN account",
            "Use free VPN Gate servers","Delete saved paid account",
            "About / security","V2Ray · Proxies · NapsternetV","Use embedded V2Ray VPN", "Choose V2Ray server"};
        new GlassDialog.Builder(this).setTitle("VPN Settings").setItems(actions,(dlg,which)->{
            if(which==10)hubPanel.list("V2RAY");
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
              "VLESS, Hysteria 2 and AmneziaWG engines are not bundled yet.");
        }).show();
    }
    private class Screen extends View {
        final Paint p=new Paint(3), text=new Paint(3);
        float density,W,H,downX,downY,dragOffset=0,phase=0;boolean dragging=false;
        final android.animation.ValueAnimator motion=android.animation.ValueAnimator.ofFloat(0,1);
        Bitmap icon,glassBackdrop;
        final Path glassPath=new Path(),ribbonPath=new Path();
        final RectF glassRect=new RectF();
        final Matrix ambientMatrix=new Matrix();
        final Typeface regular=Typeface.create("sans-serif",Typeface.NORMAL);
        final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
        RadialGradient ambientGlow,lowerGlow;
        float backdropW=-1,backdropH=-1,tabReveal=1;
        boolean backdropActive;
        android.animation.ValueAnimator settle,tabMotion,pressMotion;
        android.graphics.RenderNode liveGlass;
        float pressAmount=0,pressX=-1,pressY=-1;
        final RectF fullBackdrop=new RectF();
        final android.animation.TimeInterpolator spring=t->{
            if(t>=1)return 1f;
            return (float)(1-(1+10*t)*Math.exp(-10*t));
        };

        Screen(){
            super(MainActivity.this);
            icon=BitmapFactory.decodeResource(getResources(),R.drawable.app_icon);
            density=getResources().getDisplayMetrics().density;
            motion.setInterpolator(new android.view.animation.LinearInterpolator());motion.setDuration(9000);motion.setRepeatCount(android.animation.ValueAnimator.INFINITE);motion.setRepeatMode(android.animation.ValueAnimator.RESTART);motion.addUpdateListener(a->{phase=(float)(.5-.5*Math.cos(2*Math.PI*(float)a.getAnimatedValue()));invalidate();});
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();motion.start();}
        @Override protected void onDetachedFromWindow(){motion.cancel();if(settle!=null)settle.cancel();if(tabMotion!=null)tabMotion.cancel();if(pressMotion!=null)pressMotion.cancel();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(motion!=null){if(visibility==VISIBLE)motion.resume();else motion.pause();}}
        void fill(Canvas c,int color){c.drawColor(color);}
        void card(Canvas c,float x,float y,float w,float h,float r,int bg,int border){
            p.reset();p.setAntiAlias(true);p.setFilterBitmap(true);
            glassRect.set(x,y,x+w,y+h);glassPath.reset();glassPath.addRoundRect(glassRect,r,r,Path.Direction.CW);
            c.save();c.clipPath(glassPath);
            if(Build.VERSION.SDK_INT>=31&&c.isHardwareAccelerated()&&liveGlass!=null){
                c.drawRenderNode(liveGlass);
            }else if(glassBackdrop!=null){p.setColor(Color.WHITE);fullBackdrop.set(0,0,W,H);c.drawBitmap(glassBackdrop,null,fullBackdrop,p);}
            p.setColor(0x26ffffff);c.drawRect(glassRect,p);
            if(glassRect.contains(pressX,pressY)&&pressAmount>0){p.setColor(((int)(pressAmount*36)<<24)|0xffffff);c.drawRect(glassRect,p);}

            c.restore();
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);
            p.setColor(0xdfffffff);c.drawRoundRect(x+.5f,y+.5f,x+w-.5f,y+h-.5f,r,r,p);
            p.setColor(0x120e2530);p.setStrokeWidth(.6f);
            c.drawRoundRect(x+1.5f,y+1.5f,x+w-1.5f,y+h-1.5f,r-1,r-1,p);
            p.setStyle(Paint.Style.FILL);
        }
        void recordLiveGlass(Canvas target){
            if(Build.VERSION.SDK_INT<31||!target.isHardwareAccelerated())return;
            if(liveGlass==null){
                liveGlass=new android.graphics.RenderNode("V5 live frosted backdrop");
                liveGlass.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(16,16,Shader.TileMode.CLAMP));
            }
            liveGlass.setPosition(0,0,(int)Math.ceil(W),(int)Math.ceil(H));
            Canvas recording=liveGlass.beginRecording();drawBackdrop(recording);liveGlass.endRecording();
        }
        void press(float amount){
            if(pressMotion!=null)pressMotion.cancel();
            pressMotion=android.animation.ValueAnimator.ofFloat(pressAmount,amount);
            pressMotion.setDuration(amount>0?120:380);pressMotion.setInterpolator(spring);
            pressMotion.addUpdateListener(v->{pressAmount=(float)v.getAnimatedValue();invalidate();});pressMotion.start();
        }
        void ensureBackdrop(){
            boolean active=isTunnelOn();
            if(glassBackdrop!=null&&backdropW==W&&backdropH==H&&backdropActive==active)return;
            backdropW=W;backdropH=H;backdropActive=active;
            ambientGlow=new RadialGradient(W*.865f,H*.30f,W*.87f,new int[]{active?0xc02ce39a:0xc0ffad47,0x00ffffff},null,Shader.TileMode.CLAMP);
            lowerGlow=new RadialGradient(-W*.20f,H*.76f,W*.85f,new int[]{0x46dceee8,0x00ffffff},null,Shader.TileMode.CLAMP);
            Bitmap small=Bitmap.createBitmap(Math.max(1,(int)(W/3)),Math.max(1,(int)(H/3)),Bitmap.Config.ARGB_8888);
            Canvas sample=new Canvas(small);sample.scale(small.getWidth()/W,small.getHeight()/H);
            float saved=phase;phase=.5f;drawBackdrop(sample);phase=saved;
            glassBackdrop=blur(small,7);
        }
        Bitmap blur(Bitmap source,int radius){
            int w=source.getWidth(),h=source.getHeight();int[] a=new int[w*h],b=new int[w*h];source.getPixels(a,0,w,0,0,w,h);
            for(int pass=0;pass<2;pass++){
                for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                    int red=0,green=0,blue=0,n=0;
                    for(int k=-radius;k<=radius;k++){
                        int xx=pass==0?Math.max(0,Math.min(w-1,x+k)):x;
                        int yy=pass==1?Math.max(0,Math.min(h-1,y+k)):y;
                        int v=a[yy*w+xx];red+=(v>>16)&255;green+=(v>>8)&255;blue+=v&255;n++;
                    }b[y*w+x]=0xff000000|(red/n<<16)|(green/n<<8)|blue/n;
                }int[] swap=a;a=b;b=swap;
            }
            return Bitmap.createBitmap(a,w,h,Bitmap.Config.ARGB_8888);
        }
        void springBack(){
            if(settle!=null)settle.cancel();
            settle=android.animation.ValueAnimator.ofFloat(dragOffset,0);
            settle.setDuration(460);settle.setInterpolator(spring);
            settle.addUpdateListener(v->{dragOffset=(float)v.getAnimatedValue();invalidate();});settle.start();
        }
        void animateTab(){
            if(tabMotion!=null)tabMotion.cancel();
            tabMotion=android.animation.ValueAnimator.ofFloat(0,1);tabMotion.setDuration(440);
            tabMotion.setInterpolator(spring);
            tabMotion.addUpdateListener(v->{tabReveal=(float)v.getAnimatedValue();invalidate();});tabMotion.start();
        }
        void gradient(Canvas c,float x,float y,float w,float h,float radius,int a,int b){
            p.setColor(Color.WHITE);p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(x,y,x+w,y+h,(a&0xffffff)|0x99000000,(b&0xffffff)|0x99000000,Shader.TileMode.CLAMP));
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);p.setShader(null);
        }
        void txt(Canvas c,String s,float x,float y,float size,int color,boolean bold){
            text.reset();text.setAntiAlias(true);text.setColor(color);text.setTextSize(size);
            text.setTypeface(bold?medium:regular);
            c.drawText(s,x,y,text);
        }
        void center(Canvas c,String s,float x,float y,float size,int col,boolean bold){
            text.setTextSize(size);text.setTypeface(bold?medium:regular);
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
        @Override protected void onDraw(Canvas raw){
            density=Math.min(getResources().getDisplayMetrics().density,getHeight()/720f);
            W=getWidth()/density;H=getHeight()/density;
            raw.save();raw.scale(density,density);
            Canvas c=raw;
            ensureBackdrop();drawBackdrop(c);recordLiveGlass(c);
            int active=isTunnelOn()?GREEN:ORANGE;
            c.save();c.translate(0,(1-tabReveal)*18);
            if(tab==0)home(c,active);else if(tab==1)locations(c);else stats(c);
            c.restore();navbar(c);
            raw.restore();
        }
        void drawBackdrop(Canvas c){
            p.reset();p.setAntiAlias(true);
            fill(c,0xfffbfcfc);
            ambientMatrix.setTranslate(W*(phase-.5f)*.17f,0);ambientGlow.setLocalMatrix(ambientMatrix);
            p.setShader(ambientGlow);c.drawRect(0,0,W,H,p);p.setShader(lowerGlow);
            c.drawRect(0,0,W,H,p);p.setShader(null);
            // Translucent curved glass ribbons, inspired by the approved reference.
            for(int j=0;j<3;j++){
                Path ribbon=ribbonPath;ribbon.reset();float yy=H*(.20f+j*.10f)+phase*18;
                ribbon.moveTo(W+50,yy-140);ribbon.cubicTo(W*.20f,yy+90,W*1.25f,yy+150,-50,yy+320);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.3f);p.setColor(0xafffffff);c.drawPath(ribbon,p);
                p.setStrokeWidth(24);p.setColor(0x17ffffff);c.drawPath(ribbon,p);p.setStyle(Paint.Style.FILL);
            }
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
                icon(c,knobX-27,sy+18,54);
                txt(c,isConnecting()?"Starting tunnel…":"Slide to connect",
                    x+106,sy+53,16,0xffffffff,true);
            }
            if(isConnecting()){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0xffffffff);c.drawArc(x-5,sy-5,x+sw+5,sy+sh+5,phase*360,85,false,p);p.setStyle(Paint.Style.FILL);}
            String secondary=isTunnelOn()?"Tunnel established · server unverified":
                isConnecting()?"Starting VPN engine…":"Slide the button to connect";
            center(c,secondary,W/2,sy+sh+37,12,isTunnelOn()?0xff17895e:MUTED,false);
            float cy=serverY();
            card(c,18,cy,W-36,88,25,0xeaffffff,0xffffffff);
            roundedCircle(c,62,cy+44,23,0xffeef7f4);
            txt(c,paidMode?"★":"🌐",47,cy+54,27,ORANGE,true);
            String name=paidMode?"Private OpenVPN account":
                preferNative&&hasNativeCandidates()?"Embedded VPN · V2Ray":
                (servers.isEmpty()?"No free OpenVPN relay":servers.get(Math.min(selectedIndex,servers.size()-1)).country);
            String subtitle=paidMode?"Imported .ovpn profile":
                preferNative&&hasNativeCandidates()?(hub.entries("V2RAY").size()+" config candidates · not validated"):
                (servers.isEmpty()?"Tap Smart update to refresh":servers.get(Math.min(selectedIndex,servers.size()-1)).host);
            txt(c,name,98,cy+38,15,INK,true);
            txt(c,subtitle.length()>32?subtitle.substring(0,31)+"…":subtitle,98,cy+61,11,MUTED,false);
            txt(c,"›",W-49,cy+56,30,MUTED,false);
            card(c,18,cy+98,W-36,62,20,0xeaffffff,0xffffffff);
            txt(c,updatingAll?"↻ Updating all sources…":"↻ Smart update · select best",35,cy+126,16,ORANGE,true);
            txt(c,"OpenVPN + V2Ray + Telegram + NapsternetV",35,cy+146,11,MUTED,false);
            card(c,18,cy+168,W-36,42,18,0xcfffffff,0xffffffff);
            center(c,"V2Ray  ·  Telegram Proxy  ·  NapsternetV  ›",W/2,cy+195,12,INK,true);
        }
        void locations(Canvas c){
            header(c);txt(c,"Locations",24,140,32,INK,true);
            txt(c,"Free VPN Gate volunteers · updated automatically",24,167,12,MUTED,false);
            card(c,18,185,W-36,57,20,0xeaffffff,0xffffffff);
            txt(c,"↻ Smart update all sources",37,222,17,ORANGE,true);
            if(servers.isEmpty())txt(c,"No servers cached. Tap Refresh.",24,290,15,MUTED,false);
            int visible=Math.min(servers.size(),Math.max(0,(int)((H-335)/72)));
            for(int i=0;i<visible;i++){
                float y=260+i*76;
                card(c,18,y,W-36,68,20,0xeeffffff,0xffffffff);
                FreeDirectory.Node node=servers.get(i);
                txt(c,node.country,38,y+28,15,INK,true);
                txt(c,node.host,38,y+49,11,MUTED,false);
                if(!paidMode&&selectedIndex==i)txt(c,"✓",W-60,y+40,24,GREEN,true);
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
            txt(c,"Download",40,368,16,MUTED,false);txt(c,"— Mbps",W-130,368,17,INK,true);
            txt(c,"Upload",40,423,16,MUTED,false);txt(c,"— Mbps",W-130,423,17,INK,true);
            txt(c,"Speed values require real tunnel telemetry.",24,506,12,MUTED,false);
        }
        void navbar(Canvas c){
            float top=H-74;
            card(c,0,top,W,80,23,0xf7ffffff,0xffeeeeee);
            String[] labels={"Home","Locations","Stats"};
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
                if(settle!=null)settle.cancel();pressX=x;pressY=y;press(1);downX=x;downY=y;dragging=tab==0&&y>sy&&y<sy+94;
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_CANCEL){press(0);dragging=false;springBack();return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE&&dragging){dragOffset=vpn.state==VpnController.State.ON?Math.max(-(W-138),Math.min(0,x-downX)):Math.min(W-138,Math.max(0,x-downX));invalidate();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){
                press(0);
                if(dragging){
                    dragging=false;springBack();
                    if(!isTunnelOn()&&!isConnecting() && x-downX>Math.min(70,W*.25f))startVpn();
                    else if(isTunnelOn()&&downX-x>Math.min(70,W*.25f)){
                        if(SingVpnService.state==SingVpnService.TUNNEL_ACTIVE)stopNative();
                        if(vpn.state==VpnController.State.ON)vpn.disconnect();
                    }
                    return true;
                }
                if(y>H-85){tab=Math.min(2,(int)(x/W*3));animateTab();invalidate();return true;}
                if(y<85){if(x>W-85)showSettings();else if(x<85)hubPanel.open();return true;}
                if(tab==0&&y>serverY()+168&&y<serverY()+210){hubPanel.open();return true;}
                if(tab==0&&y>serverY()+98&&y<serverY()+160){refreshAll();return true;}
                if(tab==0&&y>serverY()&&y<serverY()+88){selectServer();return true;}
                if(tab==1){
                    if(y>185&&y<247){refreshAll();return true;}
                    if(y>=260){int i=(int)((y-260)/76);if(i>=0&&i<servers.size()){
                        selectedIndex=i;paidMode=false;preferNative=false;persist();tab=0;animateTab();invalidate();return true;}}
                }
            }
            return true;
        }
    }
}
