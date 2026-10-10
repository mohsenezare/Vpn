package com.aegis.vpn;
import android.net.Uri;
import java.util.*;
final class HubPanel {
 final MainActivity activity;final SourceHub hub;
 HubPanel(MainActivity a,SourceHub h){activity=a;hub=h;}
 void open(){
  String[] labels={"V2Ray servers / measured delays","Update five sources + test all","Source status / last update","Cancel current tests","Add Quick Settings tile","Enable status notification"};
  new GlassDialog.Builder(activity).setTitle("V2Ray").setItems(labels,(d,w)->{
   if(w==0)list("V2RAY");if(w==1)activity.refreshAll();if(w==2)activity.info(hub.report());
   if(w==3)ProxyScanner.cancel();if(w==4)AegisShortcuts.addQuickTile(activity);if(w==5)AegisShortcuts.enableNotifications(activity);
  }).setNegativeButton("Close",null).show();
 }
 static String name(FeedParser.Entry e){String name=Uri.parse(e.value).getFragment();String protocol=e.value.substring(0,e.value.indexOf(':')).toUpperCase(Locale.ROOT);return protocol+(name==null?"":" · "+name.substring(0,Math.min(22,name.length())));}
 void list(String kind){
  List<FeedParser.Entry> entries=hub.entries("V2RAY");entries.sort(Comparator.comparingLong(e->ProxyScanner.rank(e.value)));
  String[] titles=new String[entries.size()];for(int i=0;i<titles.length;i++)titles[i]=(i+1)+". "+name(entries.get(i))+"\n"+ProxyScanner.label(entries.get(i).value);
  new GlassDialog.Builder(activity).setTitle("V2Ray · "+entries.size()).setItems(titles,(d,w)->entry(entries.get(w)))
   .setNeutralButton("Update + test",(d,w)->activity.refreshAll()).setNegativeButton("Close",null).show();
 }
 void entry(FeedParser.Entry e){new GlassDialog.Builder(activity).setTitle(name(e)).setMessage(ProxyScanner.label(e.value)+"\n\nSource: "+e.source)
  .setPositiveButton("Select + connect",(d,w)->activity.connectNativeEntry(e.value)).setNegativeButton("Close",null).show();}
}
