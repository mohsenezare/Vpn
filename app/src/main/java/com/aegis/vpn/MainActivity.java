package com.aegis.vpn;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

/** Only the original V5 automatic tunnel and selectable native V2Ray configurations. */
public final class MainActivity extends Activity {
    private static final int PREPARE=8292, INK=0xff163e45, MUTED=0xff51757b;
    private SourceHub hub;
    private SecureLinks vault;
    private final EndpointProbe probe=new EndpointProbe();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Backdrop backdrop;
    private TextView power,status,mode5,mode2,selection,refresh;
    private String selected="",pending;
    private boolean manual=false,starting=false,destroyed=false,waiting=false;
    private final BroadcastReceiver events=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            starting=false;waiting=false;render();
            if(i.getIntExtra("state",0)==SingVpnService.FAILED)info(i.getStringExtra("message"));
        }
    };
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xffdef1ed);
        getWindow().setNavigationBarColor(0xffe5eee9);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        hub=new SourceHub(this);vault=new SecureLinks(this);selected=vault.selected();
        manual=getPreferences(0).getBoolean("two_mode_manual",false);
        // Retire the removed background job, retaining existing V2Ray credentials.
        android.app.job.JobScheduler scheduler=(android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE);
        if(scheduler!=null)scheduler.cancelAll();
        IntentFilter filter=new IntentFilter(SingVpnService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(events,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(events,filter);
        FrameLayout root=new FrameLayout(this);backdrop=new Backdrop();root.addView(backdrop);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout column=new LinearLayout(this);column.setOrientation(1);column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24),dp(30),dp(24),dp(26));scroll.addView(column);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.app_icon);column.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(52)));
        TextView title=text("AEGIS",30,true);title.setLetterSpacing(.16f);add(column,title,12);
        TextView sub=text("ساده، شفاف، متصل",14,false);sub.setTextColor(MUTED);add(column,sub,4);
        LinearLayout modes=new LinearLayout(this);modes.setOrientation(0);modes.setGravity(Gravity.CENTER);
        mode5=button("نسخهٔ ۵",()->choose(false));mode2=button("V2Ray",()->choose(true));
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(62),1);half.setMargins(0,0,dp(6),0);modes.addView(mode5,half);
        LinearLayout.LayoutParams half2=new LinearLayout.LayoutParams(0,dp(62),1);half2.setMargins(dp(6),0,0,0);modes.addView(mode2,half2);add(column,modes,30);
        power=button("⏻",this::toggle);power.setTextSize(64);power.setContentDescription("اتصال یا قطع VPN");
        LinearLayout.LayoutParams circle=new LinearLayout.LayoutParams(dp(176),dp(176));circle.topMargin=dp(38);power.setBackground(new Frost(power,88));column.addView(power,circle);
        status=text("آمادهٔ اتصال",18,true);add(column,status,22);
        TextView caption=text("برای اتصال یا قطع، دکمه را لمس کن",12,false);caption.setTextColor(MUTED);add(column,caption,7);
        selection=button("",()->{if(manual)showConfigs();});selection.setTextSize(14);selection.setMinHeight(dp(86));add(column,selection,32);
        refresh=button("به‌روزرسانی کانفیگ‌ها",this::update);refresh.setTextSize(14);add(column,refresh,14);
        TextView version=text("AEGIS  /  0.13",11,false);version.setTextColor(MUTED);add(column,version,24);
        setContentView(root);render();
        if(hub.stale())update();
    }
    private TextView text(String value,int size,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(INK);t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.create("sans-serif",bold?1:0));return t;
    }
    private TextView button(String label,Runnable action){
        TextView t=text(label,17,true);t.setPadding(dp(16),dp(14),dp(16),dp(14));t.setMinHeight(dp(54));
        t.setBackground(new Frost(t,24));t.setClickable(true);t.setFocusable(true);t.setOnClickListener(v->action.run());return t;
    }
    private void add(LinearLayout column,View child,int top){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(top);column.addView(child,lp);
    }
    private boolean active(){return SingVpnService.state==SingVpnService.STARTING||SingVpnService.state==SingVpnService.TUNNEL_ACTIVE||starting||waiting;}
    private void choose(boolean value){
        if(active()){info("ابتدا اتصال فعلی را قطع کن.");return;}
        manual=value;getPreferences(0).edit().putBoolean("two_mode_manual",value).apply();render();
        if(manual)showConfigs();
    }
    private void render(){
        if(destroyed||power==null)return;
        mode5.setText(manual?"نسخهٔ ۵":"●  نسخهٔ ۵");mode2.setText(manual?"●  V2Ray":"V2Ray");
        mode5.setTextColor(manual?MUTED:INK);mode2.setTextColor(manual?INK:MUTED);
        int s=SingVpnService.state;
        power.setText(active()?"■":"⏻");power.setTextColor(active()?0xff008b78:INK);
        status.setText(waiting?"در حال دریافت کانفیگ…":starting||s==SingVpnService.STARTING?"در حال اتصال…":s==SingVpnService.TUNNEL_ACTIVE?"تونل فعال است":s==SingVpnService.FAILED?"اتصال ناموفق":"آمادهٔ اتصال");
        selection.setText(manual?(selected.isEmpty()?"انتخاب کانفیگ V2Ray  ›":label(selected)+"\nتغییر کانفیگ  ›"):"اتصال خودکار نسخهٔ ۵\nهمان هسته و تنظیمات اتصال اصلی");
        refresh.setText(hub.busy?"در حال به‌روزرسانی…":"به‌روزرسانی کانفیگ‌ها");refresh.setEnabled(!hub.busy);
    }
    private void update(){
        hub.refresh(()->{if(!destroyed){render();Toast.makeText(this,hub.entries("V2RAY").size()+" کانفیگ عمومی موجود",Toast.LENGTH_SHORT).show();}});render();
    }
    private void toggle(){
        if(active()){
            waiting=false;starting=false;pending=null;
            startService(new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_STOP));render();return;
        }
        if(manual){if(selected.isEmpty()){showConfigs();return;}connect(selected);return;}
        if(hub.entries("V2RAY").isEmpty()){
            waiting=true;render();hub.refresh(()->{if(!destroyed&&waiting){waiting=false;connectAuto();}});
        }else connectAuto();
    }
    private void connectAuto(){
        try{
            // Exact build-25 ordering: measured rank first, VLESS preference second.
            ArrayList<FeedParser.Entry> choices=new ArrayList<>(hub.entries("V2RAY"));
            choices.sort((a,b)->{long ra=probe.rank(a.value),rb=probe.rank(b.value);if(ra!=rb)return Long.compare(ra,rb);return Boolean.compare(!a.value.startsWith("vless://"),!b.value.startsWith("vless://"));});
            ArrayList<String> links=new ArrayList<>();
            for(FeedParser.Entry e:choices){if(links.size()>=12)break;if(SingBoxConfig.supported(e.value))links.add(e.value);}
            if(links.isEmpty()){render();info("کانفیگ قابل استفاده دریافت نشد. اینترنت را بررسی و دوباره به‌روزرسانی کن.");return;}
            prepare(SingBoxConfig.buildAuto(links));
        }catch(Exception e){starting=false;render();info(e.getMessage());}
    }
    private void connect(String link){try{prepare(SingBoxConfig.build(link));}catch(Exception e){info(e.getMessage());}}
    private void prepare(String config){
        pending=config;Intent permission=VpnService.prepare(this);
        if(permission!=null){starting=true;render();startActivityForResult(permission,PREPARE);}else launch();
    }
    private void launch(){
        if(pending==null)return;
        Intent i=new Intent(this,SingVpnService.class).setAction(SingVpnService.ACTION_START).putExtra(SingVpnService.EXTRA_CONFIG,pending);
        pending=null;starting=true;startForegroundService(i);render();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PREPARE){if(result==RESULT_OK)launch();else{pending=null;starting=false;render();}}
    }
    private ArrayList<FeedParser.Entry> entries(){
        LinkedHashMap<String,FeedParser.Entry> all=new LinkedHashMap<>();
        for(String v:vault.get("V2RAY"))if(SingBoxConfig.supported(v))all.put(v,new FeedParser.Entry("V2RAY",v,"ذخیره‌شده"));
        for(FeedParser.Entry e:hub.entries("V2RAY"))if(SingBoxConfig.supported(e.value))all.putIfAbsent(e.value,e);
        ArrayList<FeedParser.Entry> list=new ArrayList<>(all.values());list.sort((a,b)->Long.compare(probe.rank(a.value),probe.rank(b.value)));return list;
    }
    private void showConfigs(){
        ArrayList<FeedParser.Entry> list=entries();
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView note=text("کانفیگ‌های عمومی تضمینی نیستند.\nزمان TCP فقط دسترسی به سرور را می‌سنجد.",12,false);box.addView(note);
        TextView test=button(probe.busy?"در حال بررسی…":"بررسی دسترسی سرورها",()->{});box.addView(test);
        ListView lv=new ListView(this);lv.setDividerHeight(dp(8));box.addView(lv,new LinearLayout.LayoutParams(-1,dp(300)));
        Runnable fill=()->{
            list.sort((a,b)->Long.compare(probe.rank(a.value),probe.rank(b.value)));
            ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,new ArrayList<String>()){
                @Override public View getView(int position,View convert,android.view.ViewGroup parent){
                    TextView t=(TextView)super.getView(position,convert,parent);t.setTextColor(INK);t.setTextSize(13);t.setMaxLines(3);return t;
                }
            };
            for(FeedParser.Entry e:list)adapter.add((e.value.equals(selected)?"●  ":"")+label(e.value)+"\n"+probe.label(e.value));lv.setAdapter(adapter);
        };fill.run();
        AlertDialog dialog=new GlassDialog.Builder(this).setTitle("V2Ray · انتخاب کانفیگ").setView(box)
            .setPositiveButton("افزودن کانفیگ",(d,w)->importLink()).setNegativeButton("بستن",null).create();
        lv.setOnItemClickListener((a,v,pos,id)->{
            if(active()){info("ابتدا اتصال فعلی را قطع کن.");return;}
            try{selected=list.get(pos).value;vault.select(selected);manual=true;getPreferences(0).edit().putBoolean("two_mode_manual",true).apply();dialog.dismiss();render();}
            catch(Exception e){info("ذخیرهٔ کانفیگ انجام نشد.");}
        });
        test.setOnClickListener(v->{if(probe.busy)return;test.setText("در حال بررسی…");probe.test(list,()->{if(!destroyed&&dialog.isShowing()){test.setText("بررسی دوباره");fill.run();}});});
        dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(new Frost(box,28));dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*.94),-2);}
    }
    private void importLink(){
        EditText input=new EditText(this);input.setHint("vless://  vmess://  trojan://  ss://");input.setMinLines(3);input.setMaxLines(6);input.setInputType(0x00080001|0x00020000);
        new GlassDialog.Builder(this).setTitle("افزودن کانفیگ شخصی").setView(input).setNegativeButton("لغو",null).setPositiveButton("ذخیره",(d,w)->{
            int n=0;try{
                for(FeedParser.Entry e:FeedParser.parse(input.getText().toString(),"Imported"))if(e.kind.equals("V2RAY")&&SingBoxConfig.supported(e.value)){vault.put("V2RAY",e.value);n++;}
                if(n==0){info("لینک پشتیبانی‌شده‌ای پیدا نشد.");return;}showConfigs();
            }catch(Exception e){info("ذخیرهٔ امن انجام نشد.");}
        }).show();
    }
    private String label(String link){
        try{JSONObject o=SingBoxConfig.outbound(link);return o.getString("type").toUpperCase(Locale.ROOT)+"  ·  "+o.getString("server")+":"+o.getInt("server_port");}
        catch(Exception e){return "V2Ray";}
    }
    private void info(String msg){if(!destroyed)new GlassDialog.Builder(this).setMessage(msg==null?"خطای اتصال":msg).setPositiveButton("باشه",null).show();}
    @Override protected void onResume(){super.onResume();render();}
    @Override protected void onDestroy(){destroyed=true;waiting=false;try{unregisterReceiver(events);}catch(Exception ignored){}handler.removeCallbacksAndMessages(null);super.onDestroy();}

    /** Static backdrop cached once per size; frosted controls sample a blurred copy. */
    private final class Backdrop extends View {
        Bitmap scene,frosted;
        Backdrop(){super(MainActivity.this);}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            if(w<1||h<1)return;
            scene=Bitmap.createBitmap(Math.max(1,w/3),Math.max(1,h/3),Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(scene);float x=scene.getWidth(),y=scene.getHeight();Paint p=new Paint(3);
            p.setShader(new LinearGradient(0,0,x,y,new int[]{0xffd9f0ec,0xfff8e8d6,0xffd6e8e9},null,Shader.TileMode.CLAMP));c.drawPaint(p);
            float[][] orbs={{.9f,.14f,.65f},{.02f,.52f,.57f},{.93f,.84f,.58f}};
            int[] colors={0xff67ccb9,0xffefb180,0xff71bcc9};
            for(int i=0;i<3;i++){float[] o=orbs[i];p.setShader(new RadialGradient(x*o[0],y*o[1],x*o[2],colors[i],colors[i]&0x00ffffff,Shader.TileMode.CLAMP));c.drawRect(0,0,x,y,p);}
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0x64ffffff);
            for(int i=0;i<5;i++)c.drawCircle(x*.92f,y*.34f,x*(.36f+i*.13f),p);
            frosted=blur(scene,9);invalidate();
        }
        @Override protected void onDraw(Canvas c){if(scene!=null)c.drawBitmap(scene,null,new Rect(0,0,getWidth(),getHeight()),new Paint(3));}
    }
    private static Bitmap blur(Bitmap source,int radius){
        int w=source.getWidth(),h=source.getHeight();int[] a=new int[w*h],b=new int[w*h];source.getPixels(a,0,w,0,0,w,h);
        for(int pass=0;pass<2;pass++){
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int r=0,g=0,bl=0,n=0;
                for(int k=-radius;k<=radius;k++){int xx=pass==0?Math.max(0,Math.min(w-1,x+k)):x,yy=pass==1?Math.max(0,Math.min(h-1,y+k)):y;int v=a[yy*w+xx];r+=(v>>16)&255;g+=(v>>8)&255;bl+=v&255;n++;}
                b[y*w+x]=0xff000000|(r/n<<16)|(g/n<<8)|bl/n;
            }int[] swap=a;a=b;b=swap;
        }return Bitmap.createBitmap(a,w,h,Bitmap.Config.ARGB_8888);
    }
    private final class Frost extends Drawable {
        final View owner;final float corner;final Paint paint=new Paint(3);
        Frost(View owner,float corner){this.owner=owner;this.corner=dp(corner);}
        @Override public void draw(Canvas canvas){
            Rect b=getBounds();RectF r=new RectF(b);Path path=new Path();path.addRoundRect(r,corner,corner,Path.Direction.CW);
            canvas.save();canvas.clipPath(path);
            if(backdrop!=null&&backdrop.frosted!=null){
                int[] here=new int[2],base=new int[2];owner.getLocationOnScreen(here);backdrop.getLocationOnScreen(base);
                canvas.drawBitmap(backdrop.frosted,null,new RectF(base[0]-here[0],base[1]-here[1],base[0]-here[0]+backdrop.getWidth(),base[1]-here[1]+backdrop.getHeight()),paint);
            }
            paint.setShader(new LinearGradient(0,0,b.width(),b.height(),0x88ffffff,0x20ffffff,Shader.TileMode.CLAMP));canvas.drawRect(r,paint);paint.setShader(null);
            canvas.restore();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0xddffffff);r.inset(dp(.5f),dp(.5f));canvas.drawRoundRect(r,corner,corner,paint);paint.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int alpha){} @Override public void setColorFilter(ColorFilter f){} @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
