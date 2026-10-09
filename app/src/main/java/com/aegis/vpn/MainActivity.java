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
    // Android UID counters include the app's own background fetches, so rate is approximate.
    private long lastRx=-1,lastTx=-1,lastSample=0;
    private volatile double downMbps=-1,upMbps=-1;
    private final Runnable meterTick=new Runnable(){
        @Override public void run(){
            if(SingVpnService.state==SingVpnService.TUNNEL_ACTIVE){
                long rx=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
                long tx=android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
                long now=android.os.SystemClock.elapsedRealtime();
                if(rx>=0&&tx>=0&&lastRx>=0&&now>lastSample){
                    downMbps=Math.max(0,(rx-lastRx)*8.0/(now-lastSample)/1000.0);
                    upMbps=Math.max(0,(tx-lastTx)*8.0/(now-lastSample)/1000.0);
                }
                lastRx=rx;lastTx=tx;lastSample=now;
            }else{downMbps=-1;upMbps=-1;lastRx=-1;lastTx=-1;lastSample=0;}
            if(screen!=null&&SingVpnService.state==SingVpnService.TUNNEL_ACTIVE)screen.invalidate();
            handler.postDelayed(this,1400);
        }
    };
    String rate(double n){return n<0?"—":String.format(java.util.Locale.US,"%.2f",n);}
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
        servers=directory.load();
        paidMode=getPreferences(MODE_PRIVATE).getBoolean("paid_mode",false);
        preferNative=getPreferences(MODE_PRIVATE).getBoolean("prefer_native",true);
        smartNative=getPreferences(MODE_PRIVATE).getBoolean("smart_native",true);
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
        if(isTunnelOn()||isConnecting()){info("Disconnect the current tunnel before changing server.");return;}
        try{
            if(!SingBoxConfig.supported(link))throw new IllegalArgumentException("Unsupported native configuration");
            vault.select(link);selectedNativeCache=link;smartNative=false;preferNative=true;paidMode=false;persist();screen.invalidate();
            if(connectNow)connectNativeEntry(link);
            else Toast.makeText(this,"Server selected · swipe to connect",Toast.LENGTH_SHORT).show();
        }catch(Exception e){info(e.getMessage());}
    }
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
            if(selectedIndex>=servers.size())selectedIndex=0;
            persist();screen.invalidate();
            openVpnResult[0]="OpenVPN: "+list.size()+" free servers updated"+
                (paidMode?" (paid profile preserved)":"; best advertised ping selected");
            finished.run();
        }, err->{
            openVpnResult[0]="OpenVPN: update failed; "+servers.size()+" cached. "+err;
            finished.run();
        });
        hub.refresh(()->{
            nativeCacheAt=0;screen.listCacheAt=0;
            sourceResult[0]="V2Ray: "+hub.entries("V2RAY").size()+
                " | Proxies: "+hub.entries("PROXY").size()+
                ";
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
        .putBoolean("prefer_native",preferNative).putBoolean("smart_native",smartNative)
        .putInt("selected",selectedIndex).apply();}
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
            ArrayList<FeedParser.Entry> choices=new ArrayList<>(hubPanel.entries("V2RAY"));
            // V5 priority: its working community sources first, tested TCP second.
            // Do not put arbitrary large country feed lists ahead of the original pool.
            choices.sort((a,b)->{
                boolean pa=SourceHub.preferred(a.source),pb=SourceHub.preferred(b.source);
                if(pa!=pb)return pa?-1:1;
                long ra=hubPanel.probe.rank(a.value),rb=hubPanel.probe.rank(b.value);
                if(ra!=rb)return Long.compare(ra,rb);
                return Boolean.compare(!a.value.startsWith("vless://"),!b.value.startsWith("vless://"));
            });
            ArrayList<String> candidates=new ArrayList<>();
            // Restore v0.5 selection: no hostname cap that accidentally excludes
            // two working credentials on the same Reality/CDN endpoint.
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
        private float cachedW=-1,cachedH=-1;
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
        @Override protected void onDetachedFromWindow(){ambient.cancel();if(slide!=null)slide.cancel();if(page!=null)page.cancel();super.onDetachedFromWindow();}
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
        void card(Canvas c,float x,float y,float w,float h,float radius,int color){
            p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);
            c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.8f);p.setColor(0xcaffffff);
            c.drawRoundRect(x+.5f,y+.5f,x+w-.5f,y+h-.5f,radius,radius,p);p.setStyle(Paint.Style.FILL);
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
            if(cachedW!=W||cachedH!=H){
                cachedW=W;cachedH=H;
                warmShader=new RadialGradient(W*.84f,H*.32f,W*.84f,
                    new int[]{0x82ffb965,0x15fff5dc,0x00ffffff},null,Shader.TileMode.CLAMP);
                greenShader=new RadialGradient(W*.84f,H*.32f,W*.84f,
                    new int[]{0x954ef3b4,0x15f7fff5,0x00ffffff},null,Shader.TileMode.CLAMP);
                footerShader=new RadialGradient(W*.01f,H*.84f,W*.94f,
                    new int[]{0x40ffcbaa,0x00ffffff},null,Shader.TileMode.CLAMP);
                float sx=24,sy=sliderY(),width=W-48;
                sliderWarmShader=new LinearGradient(sx,sy,sx+width,sy+92,
                    0xffffb34c,0xffee651f,Shader.TileMode.CLAMP);
                sliderGreenShader=new LinearGradient(sx,sy,sx+width,sy+92,
                    0xff09dfa0,0xff069363,Shader.TileMode.CLAMP);
            }
            p.setShader(on?greenShader:warmShader);c.drawRect(0,0,W,H,p);
            p.setShader(footerShader);c.drawRect(0,0,W,H,p);p.setShader(null);
            for(int i=0;i<3;i++){
                Path path=new Path();float offset=i*75+phase*12;
                path.moveTo(W+30,-95+offset);
                path.cubicTo(W*.52f,110+offset,W*.88f,250+offset,-80,H*.66f+offset);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.3f);p.setColor(0x87ffffff);
                c.drawPath(path,p);p.setStrokeWidth(21);p.setColor(0x10ffffff);c.drawPath(path,p);
            }
            p.setStyle(Paint.Style.FILL);
            header(c);
            c.save();c.translate(0,(1-pageAlpha)*16);c.saveLayerAlpha(0,94,W,H-74,(int)(255*Math.max(0,Math.min(1,pageAlpha))));
            if(tab==0)home(c);else if(tab==1)locations(c);else stats(c);
            c.restore();c.restore();
            navbar(c);raw.restore();
        }
        void header(Canvas c){
            card(c,19,36,46,46,23,0xcaffffff);
            center(c,"⠿",42,65,22,0xff596670,true);
            center(c,"VPN",W/2,66,19,INK,true);
            // The approved header keeps just the library affordance; settings live there.
        }
        float sliderY(){return H*.409f;}
        float serverY(){return Math.min(Math.max(sliderY()+175,H*.655f),H-250);}
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
            p.setShader(on?sliderGreenShader:sliderWarmShader);
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(x,sy,x+sw,sy+sh,48,48,p);
            p.setShader(null);
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
            if(on)ink(c,SingVpnService.verifiedRoute?"Connected":"Tunnel active",x+24,cy+6,20,0xffffffff,true);
            else ink(c,connecting?"Connecting…":"Slide to connect",x+107,cy+6,18,0xffffffff,true);
            if(connecting){
                p.setStrokeWidth(3);p.setStyle(Paint.Style.STROKE);p.setColor(0xdfffffff);
                c.drawArc(x-4,sy-4,x+sw+4,sy+sh+4,phase*360,115,false,p);
                p.setStyle(Paint.Style.FILL);
            }
            center(c,on?(SingVpnService.verifiedRoute?"Live route passed HTTP check":"Tunnel active · route check pending"):
                connecting?"Establishing your secure connection":"Slide or tap the glowing button",
                W/2,sy+sh+32,11.5f,on?0xff288e6e:MUTED,false);
            // Clear separation between Smart refresh and manual selection.
            float quickY=sy+sh+39;
            card(c,24,quickY,(W-56)/2,36,18,0xdbffffff);
            card(c,W/2+4,quickY,(W-56)/2,36,18,0xdbffffff);
            center(c,"↻ Smart update",24+(W-56)/4f,quickY+23,12.5f,ORANGE,true);
            center(c,"☷ Choose server",W*.75f+1,quickY+23,12.5f,INK,true);
            float y=serverY();
            card(c,21,y,W-42,78,25,0xeefeffff);
            circle(c,61,y+44,26,0xfff2f8fa);
            if(!paidMode&&preferNative)ink(c,"◈",46,y+55,33,0xff8b67f1,true);
            else ink(c,"◉",46,y+54,29,0xfff59440,true);
            String title;
            String subtitle;
            if(paidMode){title="Private OpenVPN account";subtitle="Purchased .ovpn profile";}
            else if(preferNative){
                title=smartNative?"Smart · automatic selection":"Manual · "+hubPanel.type(chosenNative());
                subtitle=smartNative?"Up to 12 native servers · auto test":hubPanel.host(chosenNative());
            }else{
                FreeDirectory.Node node=servers.isEmpty()?null:servers.get(Math.min(selectedIndex,servers.size()-1));
                title=node==null?"OpenVPN · no nodes yet":node.country;
                subtitle=node==null?"Use Smart update to refresh":node.host+" · "+(node.ping>0?node.ping+"ms reported":"No ping reported");
            }
            ink(c,cut(title,(int)(W-145),16),101,y+35,16,INK,true);
            ink(c,cut(subtitle,(int)(W-147),12),101,y+58,12,MUTED,false);
            ink(c,"›",W-51,y+56,31,0xff99a8b0,false);
            float statY=y+92;
            card(c,21,statY,W-42,80,24,0xeefeffff);
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
            ink(c,updatingAll?"◌  Updating sources…":"↻  Smart update all sources",39,249,14,ORANGE,true);
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
                        latency=n.ping>0?n.ping+" ms*":"— ms";
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
                    int latencyColor=locationMode==1?hubPanel.latencyColor(entries.get(i).value):color;
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
            ink(c,locationMode==0?"* VPN Gate directory-reported delay":locationMode==1?"Source country is unverified; delay is TCP":"Telegram confirms MTProto proxy after selection",
                26,H-88,10,MUTED,false);
        }
        void stats(Canvas c){
            ink(c,"Statistics",25,137,30,INK,true);
            ink(c,"Tunnel diagnostics · accurate state",25,165,12,MUTED,false);
            card(c,21,191,W-42,129,24,0xf2ffffff);
            ink(c,"Current status",43,231,13,MUTED,false);
            ink(c,isConnecting()?"Starting secure tunnel":isTunnelOn()?
                (SingVpnService.verifiedRoute?"Route verified":"Tunnel active"):"Disconnected",
                43,265,22,isTunnelOn()?GREEN:INK,true);
            ink(c,"A tunnel alone does not guarantee a reachable proxy.",43,296,11,MUTED,false);
            card(c,21,336,W-42,135,24,0xf2ffffff);
            ink(c,"Downstream",43,372,13,MUTED,false);ink(c,rate(downMbps)+" Mbps",W-156,372,18,INK,true);
            ink(c,"Upstream",43,424,13,MUTED,false);ink(c,rate(upMbps)+" Mbps",W-156,424,18,INK,true);
            ink(c,"Approximate app UID traffic · not a speed test",30,500,12,MUTED,false);
            card(c,21,526,W-42,65,18,0xe9ffffff);
            ink(c,"Network checks",42,553,14,INK,true);
            ink(c,"Refresh  ·  Test reachability from Locations",42,575,11,MUTED,false);
        }
        void navbar(Canvas c){
            float y=H-75;
            card(c,0,y,W,86,27,0xf5ffffff);
            String[] labels={"Home","Locations","Stats"};
            String[] symbols={"⌂","◎","▥"};
            for(int i=0;i<3;i++){
                float x=W*(i+.5f)/3;
                int color=tab==i?ORANGE:MUTED;
                center(c,symbols[i],x,y+34,27,color,true);
                center(c,labels[i],x,y+60,12,color,tab==i);
                if(i==tab){p.setColor(ORANGE);c.drawRoundRect(x-12,y+5,x+12,y+8,2,2,p);}
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
                if(y<91){if(x<85)hubPanel.open();return true;}
                if(tab==0){
                    float quickY=sliderY()+92+39;
                    if(y>quickY&&y<quickY+42){
                        if(x<W/2)refreshAll();else hubPanel.open();return true;
                    }
                    if(y>serverY()&&y<serverY()+81){hubPanel.open();return true;}
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
                    if(y>222&&y<271){refreshAll();return true;}
                    if(locationMode==1&&y>274&&y<315){countryPicker();return true;}
                    if(scrolling&&Math.abs(y-downY)>12){scrolling=false;return true;}
                    if(y>=(locationMode==1?320:280)&&y<H-95){
                        int index=(int)((y-(locationMode==1?320:280)+listOffset)/80);
                        if(index>=0&&index<rowCount()){
                            if(locationMode==0){
                                if(isTunnelOn()||isConnecting())info("Disconnect first to select an OpenVPN relay.");
                                else{selectedIndex=index;paidMode=false;preferNative=false;persist();setTab(0);invalidate();}
                            }else{
                                FeedParser.Entry entry=chosenEntries().get(index);
                                if(locationMode==1)hubPanel.entry(entry);
                                else hubPanel.telegramConfirm(entry.value);
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
