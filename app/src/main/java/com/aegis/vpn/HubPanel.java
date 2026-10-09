package com.aegis.vpn;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import java.net.*;
import java.util.*;

/** Native, touch-friendly connection library with manual selection and smooth iOS-like sheets. */
final class HubPanel {
 final MainActivity activity;final SourceHub hub;
 final EndpointProbe probe=new EndpointProbe();
 final SecureLinks vault;
 final int INK=0xff19272c,MUTED=0xff81929a,GREEN=0xff00b979,ORANGE=0xfff67b32;
 HubPanel(MainActivity a,SourceHub h){activity=a;hub=h;vault=new SecureLinks(a);}
 int px(float d){return (int)(d*activity.getResources().getDisplayMetrics().density+.5f);}
 HubPanel openReturn(){return this;}
 GradientDrawable bg(int color,float radius){
  GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(radius));return d;
 }
 LinearLayout col(){LinearLayout v=new LinearLayout(activity);v.setOrientation(1);return v;}
 TextView text(String value,int size,int color,boolean bold){
  TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);
  t.setGravity(Gravity.CENTER_VERTICAL);t.setTypeface(android.graphics.Typeface.create(bold?"sans-serif-medium":"sans-serif",0));return t;
 }
 void pad(View v,int l,int t,int r,int b){v.setPadding(px(l),px(t),px(r),px(b));}
 void space(LinearLayout c,int h){View s=new View(activity);c.addView(s,new LinearLayout.LayoutParams(1,px(h)));}
 void row(LinearLayout body,String title,String subtitle,int tint,Runnable action){
  LinearLayout outer=col();outer.setBackground(bg(0xf4ffffff,22));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=px(9);body.addView(outer,lp);
  LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);pad(line,16,13,15,12);outer.addView(line);
  View dot=new View(activity);dot.setBackground(bg(tint,11));
  LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(px(9),px(9));dl.rightMargin=px(12);line.addView(dot,dl);
  LinearLayout copy=col();line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
  TextView label=text(title,15,INK,true);label.setMaxLines(1);label.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(label);
  if(subtitle!=null&&!subtitle.isEmpty()){
   TextView sub=text(subtitle,11,MUTED,false);sub.setMaxLines(2);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
   LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=px(4);copy.addView(sub,sp);
  }
  TextView chevron=text("›",28,0xff99a7b0,false);pad(chevron,9,0,0,0);line.addView(chevron);
  if(action!=null){outer.setOnClickListener(v->action.run());outer.setClickable(true);}
 }
 /** Wrap-height sheet scrolls instead of overflowing narrow Android displays. */
 final class MaxSheetScroll extends ScrollView {
  MaxSheetScroll(Context c){super(c);}
  @Override protected void onMeasure(int width,int height){
   int limit=(int)(activity.getResources().getDisplayMetrics().heightPixels*.68f);
   super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
  }
 }
 interface SheetContent{void render(LinearLayout body,Dialog dialog);}
 void sheet(String title,String detail,SheetContent render){
  Dialog dialog=new Dialog(activity);
  LinearLayout root=col();root.setBackground(bg(0xfff5f8f7,34));pad(root,18,12,18,18);
  View grab=new View(activity);grab.setBackground(bg(0xffced6d7,5));
  LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(px(44),px(5));gp.gravity=Gravity.CENTER;gp.bottomMargin=px(16);root.addView(grab,gp);
  LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);root.addView(top);
  LinearLayout labels=col();top.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
  labels.addView(text(title,22,INK,true));
  if(detail!=null){TextView d=text(detail,12,MUTED,false);pad(d,0,3,0,0);labels.addView(d);}
  TextView close=text("×",28,MUTED,false);close.setGravity(Gravity.CENTER);close.setBackground(bg(0xffffffff,24));
  top.addView(close,new LinearLayout.LayoutParams(px(38),px(38)));close.setOnClickListener(v->dialog.dismiss());
  space(root,14);
  ScrollView scroller=new MaxSheetScroll(activity);scroller.setFillViewport(false);scroller.setVerticalScrollBarEnabled(false);
  LinearLayout body=col();scroller.addView(body);root.addView(scroller,new LinearLayout.LayoutParams(-1,-2));
  render.render(body,dialog);
  dialog.setContentView(root);
  Window window=dialog.getWindow();
  if(window!=null){
   window.setBackgroundDrawableResource(android.R.color.transparent);
   window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
   WindowManager.LayoutParams p=window.getAttributes();p.gravity=Gravity.BOTTOM;p.width=-1;p.height=-2;p.dimAmount=.31f;window.setAttributes(p);
   window.setNavigationBarColor(0xfff5f8f7);
  }
  dialog.show();
  if(window!=null)window.setLayout(-1,-2);
  root.setTranslationY(px(100));root.setAlpha(.4f);
  root.animate().translationY(0).alpha(1).setDuration(420)
    .setInterpolator(new android.view.animation.OvershootInterpolator(.72f)).start();
 }
 void open(){
  sheet("Connection library","Choose manually, or let Smart Mode decide",(body,d)->{
   row(body,"Smart Mode  ·  automatic","Auto-test and switch among supported servers",0xff0cbb83,()->{
    d.dismiss();activity.chooseSmartMode();
   });
   row(body,"V2Ray / Reality / Hysteria","Browse and select an individual tunnel",0xff815ef3,()->{d.dismiss();list("V2RAY");});
   row(body,"OpenVPN locations","Volunteer nodes · manually selectable",0xfff8863c,()->{d.dismiss();activity.openVpnLocations();});
   row(body,"Telegram MTProto","Add a proxy to Telegram, no channel login needed",0xff29a9ec,()->{d.dismiss();telegramMenu();});
   row(body,"NapsternetV / imported configs","Import readable links or subscriptions in Aegis",0xffe9569c,()->{d.dismiss();napsterMenu();});
   row(body,"Import configurations","Paste share link, file, or HTTPS subscription",0xff4478d6,()->{d.dismiss();importMenu();});
   row(body,"Refresh all sources","Public sources · keeps your manual choice",0xfff67b32,()->{d.dismiss();activity.refreshAll();});
   row(body,"Source health / diagnostics","Show last valid updates and errors",0xff81929a,()->{d.dismiss();activity.info(hub.report());});
   row(body,"Settings","OpenVPN account, app preferences and security",0xff738192,()->{d.dismiss();activity.showSettings();});
  });
 }
 List<FeedParser.Entry> entries(String kind){
  LinkedHashMap<String,FeedParser.Entry> map=new LinkedHashMap<>();
  for(String link:vault.get(kind))map.put(link,new FeedParser.Entry(kind,link,"Saved on this device"));
  for(FeedParser.Entry e:hub.entries(kind))map.putIfAbsent(e.value,e);
  return new ArrayList<>(map.values());
 }
 int tint(String link){
  String scheme=link.toLowerCase(Locale.ROOT);
  if(scheme.startsWith("vless://"))return 0xff8b67f1;
  if(scheme.startsWith("vmess://"))return 0xff39a7ed;
  if(scheme.startsWith("trojan://"))return 0xffe85894;
  if(scheme.startsWith("hysteria")||scheme.startsWith("hy2://"))return 0xffebae37;
  if(scheme.startsWith("ss://"))return 0xff21bdb3;
  if(scheme.startsWith("tg:")||scheme.contains("/proxy?"))return 0xff27a9e9;
  return 0xfff58247;
 }
 String type(String value){
  int k=value.indexOf("://");return k<0?"Config":value.substring(0,k).toUpperCase(Locale.ROOT);
 }
 String host(String link){
  try{
   Uri u=Uri.parse(link);
   String h=u.getHost();
   if(link.startsWith("tg:")||link.contains("/proxy?"))return u.getQueryParameter("server");
   if(h!=null&&!h.isEmpty())return h;
   if(link.startsWith("vmess://"))return "VMess encrypted share";
   if(link.startsWith("ss://"))return "Shadowsocks server";
   if(link.startsWith("tg:")||link.contains("/proxy?"))return u.getQueryParameter("server");
  }catch(Exception ignored){}
  return "Imported configuration";
 }
 String delay(String link){
  long v=probe.latency.getOrDefault(link,Long.MIN_VALUE);
  return v==Long.MIN_VALUE?"Not tested":v<0?"Unreachable":v<120?"●  "+v+" ms · Fast":v<350?"●  "+v+" ms · Fair":"●  "+v+" ms · Slow";
 }
 int latencyColor(String link){
  long v=probe.latency.getOrDefault(link,Long.MIN_VALUE);
  return v==Long.MIN_VALUE?MUTED:v<0?0xffd85e64:v<120?0xff05b981:v<350?0xffd39b2c:0xffe56b40;
 }
 void coloredConfigRow(LinearLayout body,FeedParser.Entry e,boolean selected,Runnable click){
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=px(10);
  LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);pad(line,14,12,12,12);
  line.setBackground(bg(selected?0xffedfff7:0xf6ffffff,22));body.addView(line,lp);
  LinearLayout marker=col();marker.setGravity(Gravity.CENTER);marker.setBackground(bg(0xfff3f6fb,15));
  LinearLayout.LayoutParams markerLp=new LinearLayout.LayoutParams(px(40),px(42));markerLp.rightMargin=px(11);line.addView(marker,markerLp);
  TextView symbol=text("◆",22,tint(e.value),true);symbol.setGravity(Gravity.CENTER);marker.addView(symbol);
  LinearLayout labels=col();line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
  TextView name=text((selected?"✓ ":"")+type(e.value)+" · "+host(e.value),14,INK,true);
  name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(name);
  TextView src=text(e.source.replace("https://t.me/s/","@"),11,MUTED,false);
  src.setMaxLines(1);src.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(src);
  LinearLayout badge=col();badge.setGravity(Gravity.CENTER);
  String delay=delay(e.value);int hue=latencyColor(e.value);
  badge.setBackground(bg(0x11000000|(hue&0x00ffffff),15));
  LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(px(94),px(38));bp.leftMargin=px(8);line.addView(badge,bp);
  TextView b=text(delay.length()>14?delay.substring(0,14):delay,10,hue,true);
  b.setGravity(Gravity.CENTER);pad(b,4,0,4,0);badge.addView(b);
  line.setOnClickListener(v->click.run());
 }
 void list(String kind){
  List<FeedParser.Entry> a=entries(kind);
  a.sort(Comparator.comparingLong(e->probe.rank(e.value)));
  String details=kind.equals("V2RAY")?"Select a config to pin it. Tap Test to measure TCP latency."
      :kind.equals("PROXY")?"Tap to add directly to Telegram":"Import readable configs; .npv encrypted files need a compatible decoder.";
  sheet(kind.equals("V2RAY")?"VPN servers":kind.equals("PROXY")?"Telegram proxies":"NapsternetV library",details,(body,d)->{
   if(kind.equals("V2RAY")){
    row(body,"AUTO · Smart selection","Test multiple servers through sing-box",GREEN,()->{d.dismiss();activity.chooseSmartMode();});
   }
   row(body,"+ Add a configuration","Paste a supported link or import a file",0xff4478d6,()->{d.dismiss();importMenu();});
   row(body,"◌ Test reachability","TCP handshake only — not proof of VPN login",0xffdf972e,()->{
    if(probe.busy){activity.info("Testing is already running.");return;}
    probe.test(a,()->{if(!activity.isFinishing())list(kind);});
    d.dismiss();
   });
   if(a.isEmpty())row(body,"No cached entries","Refresh sources or add a configuration",MUTED,()->{d.dismiss();activity.refreshAll();});
   int limit=Math.min(kind.equals("V2RAY")?120:80,a.size());
   String selected=vault.selected();
   for(int i=0;i<limit;i++){
    FeedParser.Entry e=a.get(i);
    coloredConfigRow(body,e,selected.equals(e.value),()->{d.dismiss();entry(e);});
   }
   if(a.size()>limit)row(body,(a.size()-limit)+" more entries","Showing first "+limit+" to keep scrolling fast",MUTED,null);
  });
 }
 void entry(FeedParser.Entry e){
  if(e.kind.equals("PROXY")){telegramConfirm(e.value);return;}
  if(e.kind.equals("NAPSTERNETV")){napsterEntry(e.value);return;}
  sheet("Connection options",type(e.value)+" · "+host(e.value),(body,d)->{
   row(body,"Use this server · manual","Keep selected when refreshing sources",GREEN,()->{
    d.dismiss();activity.selectManualNative(e.value,false);
   });
   row(body,"Connect now","Use the embedded native VPN tunnel",0xff8b67f1,()->{
    d.dismiss();activity.selectManualNative(e.value,true);
   });
   row(body,"Copy config","Share only with trusted apps",0xff418dd5,()->{
    android.content.ClipboardManager clip=(android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
    clip.setPrimaryClip(ClipData.newPlainText("Aegis config",e.value));d.dismiss();
    Toast.makeText(activity,"Copied",Toast.LENGTH_SHORT).show();
   });
   row(body,"Test TCP delay",delay(e.value),latencyColor(e.value),()->{
    d.dismiss();probe.test(Collections.singletonList(e),()->entry(e));
   });
   if("Saved on this device".equals(e.source))row(body,"Remove saved config","Delete from encrypted storage",0xffd56d76,()->{
    d.dismiss();try{vault.remove("V2RAY",e.value);list("V2RAY");}catch(Exception x){activity.info("Delete failed");}
   });
  });
 }
 void telegramMenu(){
  sheet("Telegram proxies","Add directly to Telegram — no channel access required",(body,d)->{
   row(body,"+ Enter MTProto proxy","Server · port · secret",0xff279fd8,()->{d.dismiss();telegramForm();});
   row(body,"Available public proxies","Select a saved or public proxy",0xff45a3e1,()->{d.dismiss();list("PROXY");});
   row(body,"Import Telegram proxy URL","Accept tg://proxy and t.me/proxy links",0xff799ee1,()->{d.dismiss();paste("PROXY");});
  });
 }
 void telegramForm(){
  LinearLayout view=col();pad(view,20,8,20,4);
  EditText server=new EditText(activity),port=new EditText(activity),secret=new EditText(activity);
  server.setHint("Server hostname or IP");port.setHint("Port (1–65535)");secret.setHint("MTProto secret (hex)");
  port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
  view.addView(server);view.addView(port);view.addView(secret);
  new GlassDialog.Builder(activity).setTitle("Add Telegram proxy").setView(view)
   .setPositiveButton("Add to Telegram",(dialog,w)->{
    try{
     String hostname=server.getText().toString().trim(),s=secret.getText().toString().trim();
     int p=Integer.parseInt(port.getText().toString().trim());
     if(!hostname.matches("[a-zA-Z0-9.:-]{2,253}")||p<1||p>65535||!s.matches("(?i)[a-f0-9]{32,256}"))
        throw new IllegalArgumentException("Check hostname, port and MTProto hex secret");
     String link="tg://proxy?server="+Uri.encode(hostname)+"&port="+p+"&secret="+Uri.encode(s);
     vault.put("PROXY",link);telegramConfirm(link);
    }catch(Exception ex){activity.info("Invalid proxy details: "+ex.getMessage());}
   }).setNegativeButton("Cancel",null).show();
 }
 void telegramConfirm(String link){
  Uri uri=Uri.parse(link);
  String server=uri.getQueryParameter("server"),port=uri.getQueryParameter("port"),secret=uri.getQueryParameter("secret");
  if(server==null||port==null||secret==null){activity.info("Invalid Telegram proxy URL");return;}
  String tg="tg://proxy?server="+Uri.encode(server)+"&port="+Uri.encode(port)+"&secret="+Uri.encode(secret);
  new GlassDialog.Builder(activity).setTitle("Add to Telegram")
   .setMessage("Telegram will open its built-in proxy confirmation. Aegis does not need Telegram account access.")
   .setPositiveButton("Open in Telegram",(d,w)->{
    try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(tg)).setPackage("org.telegram.messenger"));}
    catch(Exception e){
     try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(tg)));}
     catch(Exception x){activity.info("Install Telegram to use this proxy.");}
    }
   }).setNegativeButton("Cancel",null).show();
 }
 void napsterMenu(){
  sheet("NapsternetV configs","Import into Aegis when text links are compatible",(body,d)->{
   row(body,"Paste config or subscription","VLESS, VMess, Trojan, SS, HY2 share links",0xffe75a98,()->{d.dismiss();paste("V2RAY");});
   row(body,"Import from clipboard","Use copied readable share links without Telegram",0xff39b7b1,()->{d.dismiss();clipboard();});
   row(body,"Import local file","Supports plain-text share links and Base64 lists",0xff9f6bed,()->{d.dismiss();activity.pickConfigFile();});
   row(body,"Public NapsternetV posts","Read source references without leaving Aegis",0xffe5a34b,()->{d.dismiss();list("NAPSTERNETV");});
   row(body,"Encrypted .npv/.npv4","Cannot decrypt proprietary files without format support",MUTED,()->activity.info("Encrypted NapsternetV profiles are not interchangeable with sing-box configurations. Import plain-text share links or a supported sing-box JSON instead. No fake conversion is performed."));
  });
 }
 void napsterEntry(String link){
  sheet("NapsternetV · recent post","Public post only · Aegis reads its text without signing into Telegram",(body,d)->{
   row(body,"Read available configuration","Check post for usable VLESS, VMess, Trojan, SS or HY2 links",0xffd850a1,()->{
    d.dismiss();readNapsterPost(link);
   });
   row(body,"Import a local file","Only readable share links and text subscriptions are supported",0xffaf6ced,()->{
    d.dismiss();activity.pickConfigFile();
   });
   row(body,"Copy original post URL","Encrypted proprietary .npv attachments cannot be decoded",MUTED,()->{
    android.content.ClipboardManager clip=(android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
    clip.setPrimaryClip(ClipData.newPlainText("NPV post",link));
    Toast.makeText(activity,"Source link copied",Toast.LENGTH_SHORT).show();
   });
  });
 }
 void readNapsterPost(String link){
  long id=FeedParser.postId(link);
  if(id<0){activity.info("Invalid post ID");return;}
  Toast.makeText(activity,"Checking latest public post text…",Toast.LENGTH_SHORT).show();
  new Thread(()->{
   try{
    // Public Telegram preview. We do not download or execute proprietary attachments.
    Uri post=Uri.parse(link);
    java.util.List<String> segments=post.getPathSegments();
    if(segments.size()<2)throw new Exception("Invalid public post URL");
    String channel=segments.get(0);
    if(!channel.equals("mitivpn")&&!channel.equals("npv_iran"))
      throw new Exception("Unsupported public source channel");
    String page=SourceHub.get("https://t.me/s/"+channel+"?before="+(id+1));
    String mark="data-post=\\\""+channel+"/"+id+"\\\"";
    int start=page.indexOf(mark);
    if(start<0)throw new Exception("The post is not currently available in the public preview.");
    int end=page.indexOf("data-post=",start+mark.length());
    String section=page.substring(start,end<0?Math.min(page.length(),start+45000):end);
    List<FeedParser.Entry> entries=FeedParser.parse(section,link);
    int imported=0;
    for(FeedParser.Entry e:entries){
     if(e.kind.equals("V2RAY")&&SingBoxConfig.supported(e.value)){
      vault.put("V2RAY",e.value);imported++;
     }
    }
    final int count=imported;
    activity.runOnUiThread(()->{
     if(count>0){Toast.makeText(activity,count+" compatible configs added",Toast.LENGTH_LONG).show();list("V2RAY");}
     else activity.info("This recent post contains no readable supported configuration. Proprietary .npv attachments cannot be converted without their decoder.");
    });
   }catch(Exception e){activity.runOnUiThread(()->activity.info("Post preview unavailable: "+e.getMessage()));}
  },"napster-preview").start();
 }
 void importMenu(){
  sheet("Import to Aegis","Locally encrypted · no credentials sent to Aegis servers",(body,d)->{
   row(body,"Paste a config link","Native VLESS, VMess, Trojan, Shadowsocks, HY2",0xff9b6ee8,()->{d.dismiss();paste("V2RAY");});
   row(body,"Import from clipboard","Read copied share links directly in Aegis",0xff39b7b1,()->{d.dismiss();clipboard();});
   row(body,"Paste MTProto proxy","Add directly to Telegram",0xff29a9e9,()->{d.dismiss();paste("PROXY");});
   row(body,"Import local file","Read plain-text links or Base64 lists",0xff40b6a1,()->{d.dismiss();activity.pickConfigFile();});
   row(body,"Add HTTPS subscription URL","Download supported public configurations",0xffed9a4f,()->{d.dismiss();subscription();});
  });
 }
 void paste(String kind){
  EditText input=new EditText(activity);
  input.setHint(kind.equals("PROXY")?"tg://proxy?server=...":"vless://  vmess://  trojan://  ss://  hy2://");
  input.setMinLines(3);input.setMaxLines(7);
  pad(input,12,8,12,8);
  new GlassDialog.Builder(activity).setTitle("Import "+kind).setView(input)
   .setPositiveButton("Save",(d,w)->{
    String body=input.getText().toString().trim();
    List<FeedParser.Entry> values=FeedParser.parse(body,"Manual");
    int count=0;
    for(FeedParser.Entry e:values){
     if(!kind.equals(e.kind))continue;
     try{vault.put(kind,e.value);count++;}catch(Exception ignored){}
    }
    if(count>0){Toast.makeText(activity,count+" saved securely",Toast.LENGTH_LONG).show();list(kind);}
    else activity.info("No supported share link found. Encrypted proprietary files are not decoded.");
   }).setNegativeButton("Cancel",null).show();
 }
 void clipboard(){
  try{
   android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
   if(clipboard==null||!clipboard.hasPrimaryClip())throw new Exception("Clipboard is empty");
   ClipData clip=clipboard.getPrimaryClip();
   if(clip==null||clip.getItemCount()<1)throw new Exception("Clipboard is empty");
   CharSequence contents=clip.getItemAt(0).coerceToText(activity);
   if(contents==null||contents.length()>200000)throw new Exception("Clipboard content exceeds limit");
   List<FeedParser.Entry> items=FeedParser.parse(contents.toString(),"Clipboard");
   int n=0;
   for(FeedParser.Entry e:items)if(e.kind.equals("V2RAY")&&SingBoxConfig.supported(e.value)){
    vault.put("V2RAY",e.value);n++;
   }
   if(n==0)activity.info("Clipboard has no supported VPN share links.");
   else{Toast.makeText(activity,n+" configs securely saved",Toast.LENGTH_LONG).show();list("V2RAY");}
  }catch(Exception e){activity.info("Clipboard import failed: "+e.getMessage());}
 }
 void subscription(){
  EditText input=new EditText(activity);input.setHint("https://example.com/subscription");input.setSingleLine(true);pad(input,12,8,12,8);
  new GlassDialog.Builder(activity).setTitle("HTTPS subscription").setView(input)
    .setPositiveButton("Fetch",(d,w)->{
     String url=input.getText().toString().trim();
     try{
      Uri u=Uri.parse(url);
      if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null)throw new Exception("HTTPS URL required");
      String h=u.getHost().toLowerCase(Locale.ROOT);
      if(h.equals("localhost")||h.endsWith(".local")||h.matches("(?:10|127|0|192\\.168|169\\.254)\\..*"))
       throw new Exception("Private network hosts are not allowed");
      new Thread(()->{
       try{
        java.net.HttpURLConnection c=(java.net.HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(9000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);
        String raw;
        try{
         if(c.getResponseCode()!=200)throw new Exception("HTTP "+c.getResponseCode());
         try(java.io.InputStream stream=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
          byte[] buf=new byte[4096];int n;
          while((n=stream.read(buf))!=-1){if(out.size()+n>800000)throw new Exception("Subscription too large");out.write(buf,0,n);}
          raw=out.toString("UTF-8");
         }
        }finally{c.disconnect();}
        List<FeedParser.Entry> parsed=FeedParser.parse(raw,url);int saved=0;
        for(FeedParser.Entry e:parsed){
         if(e.kind.equals("V2RAY")&&SingBoxConfig.supported(e.value)){vault.put("V2RAY",e.value);saved++;}
        }
        int count=saved;
        activity.runOnUiThread(()->{
         if(count>0){Toast.makeText(activity,count+" configs saved securely",Toast.LENGTH_LONG).show();list("V2RAY");}
         else activity.info("No supported configs in subscription.");
        });
       }catch(Exception ex){activity.runOnUiThread(()->activity.info("Subscription download failed: "+ex.getMessage()));}
      },"aegis-subscription").start();
     }catch(Exception ex){activity.info(ex.getMessage());}
    }).setNegativeButton("Cancel",null).show();
 }
}
