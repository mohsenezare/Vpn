package com.aegis.vpn;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import java.util.*;
final class HubPanel {
 final MainActivity activity;final SourceHub hub;
 final EndpointProbe probe=new EndpointProbe();
 HubPanel(MainActivity a,SourceHub h){activity=a;hub=h;}
 void open(){
  String[] labels={"V2Ray / VLESS / VMess", "Telegram proxies", "NapsternetV files", activity.updatingAll?"Updating all sources…":"Smart update all · best OpenVPN", "Source status / last update", "OpenVPN: install connection engine"};
  new GlassDialog.Builder(activity).setTitle("Connection library").setItems(labels,(d,w)->{
   if(w==0)list("V2RAY");if(w==1)list("PROXY");if(w==2)list("NAPSTERNETV");
   if(w==3)activity.refreshAll();
   if(w==4)activity.info(hub.report());
   if(w==5)launch("https://play.google.com/store/apps/details?id=de.blinkt.openvpn");
  }).setNegativeButton("Close",null).show();
 }
 void list(String kind){
  List<FeedParser.Entry> entries=hub.entries(kind);
  if(entries.isEmpty()){
   new GlassDialog.Builder(activity).setTitle(kind).setMessage("No recent configs cached. Update sources or open the source channels. Public previews may be blocked on your network.")
    .setPositiveButton("Channels",(d,w)->channels()).setNegativeButton("Close",null).show();return;
  }
  entries.sort(java.util.Comparator.comparingLong(e->probe.rank(e.value)));
  String[] titles=new String[entries.size()];
  for(int i=0;i<titles.length;i++){
   FeedParser.Entry e=entries.get(i);String label=e.value.substring(0,e.value.indexOf(":")).toUpperCase(java.util.Locale.ROOT);
   Uri u=Uri.parse(e.value);String name=u.getFragment();
   titles[i]=(i+1)+". "+label+(name==null?"":" · "+name.substring(0,Math.min(45,name.length())))+"\n"+(kind.equals("NAPSTERNETV")?"File post":probe.label(e.value))+" · "+e.source.replace("https://t.me/s/","@");
  }
  AlertDialog.Builder b=new GlassDialog.Builder(activity).setTitle(kind+" · "+entries.size()).setItems(titles,(d,w)->entry(entries.get(w))).setNegativeButton("Close",null);
  if(!kind.equals("NAPSTERNETV"))b.setNeutralButton("Test TCP (40)",(d,w)->{
   if(probe.busy){activity.info("Testing is already running.");return;}
   Toast.makeText(activity,"Testing endpoint reachability, not VPN authentication…",Toast.LENGTH_LONG).show();
   probe.test(entries,()->{if(!activity.isFinishing()&&!activity.isDestroyed())list(kind);});
  });b.show();
 }
 void entry(FeedParser.Entry e){
  if(e.kind.equals("PROXY")){launch(e.value);return;}
  if(e.kind.equals("NAPSTERNETV")){launch(e.value);return;}
  AlertDialog.Builder dialog=new GlassDialog.Builder(activity).setTitle("V2Ray connection")
   .setMessage("Use the Aegis built-in sing-box engine. A public configuration may be offline; starting the TUN alone does not prove upstream connectivity.")
   .setPositiveButton("Connect in Aegis",(d,w)->activity.connectNativeEntry(e.value))
   .setNeutralButton("Copy for v2rayNG",(d,w)->{
    ((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE))
      .setPrimaryClip(ClipData.newPlainText("VPN configuration",e.value));
    Intent intent=activity.getPackageManager().getLaunchIntentForPackage("com.v2ray.ang");
    if(intent!=null)activity.startActivity(intent);
    else launch("https://github.com/2dust/v2rayNG/releases");
   }).setNegativeButton("Close",null);
  dialog.show();
 }
 void channels(){new GlassDialog.Builder(activity).setTitle("Source channels").setItems(SourceHub.CHANNELS,(d,w)->launch("https://t.me/"+SourceHub.CHANNELS[w])).setNegativeButton("Close",null).show();}
 void launch(String url){try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(ActivityNotFoundException e){activity.info("Install an app that can open this link.");}}
}
