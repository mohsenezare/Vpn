package com.aegis.vpn;
import android.content.*;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
/** VPN Gate volunteer-directory access; records can go offline at any time. */
final class FreeDirectory {
    private static final String URL="https://www.vpngate.net/api/iphone/";
    private static final int LIMIT=80;
    private static final String MIRROR="https://raw.githubusercontent.com/mohsenezare/Vpn/main/feeds/openvpn.json";
    final Context context;
    final Handler handler=new Handler(Looper.getMainLooper());
    static final class Node {
        final String country,host,config;final int ping;
        Node(String country,String host,String config,int ping){
            this.country=country;this.host=host;this.config=config;this.ping=ping;
        }
        String title(){return country+" · "+host+(ping>0?" · "+ping+"ms*":"");}
    }
    FreeDirectory(Context c){context=c.getApplicationContext();}
    boolean isStale(){return System.currentTimeMillis()-context.getSharedPreferences("nodes",0).getLong("last",0)>21600000L;}
    ArrayList<Node> load(){
        ArrayList<Node> list=new ArrayList<>();
        try{
            JSONArray arr=new JSONArray(context.getSharedPreferences("nodes",0).getString("data","[]"));
            for(int i=0;i<Math.min(LIMIT,arr.length());i++){
                JSONObject j=arr.getJSONObject(i);
                list.add(new Node(j.getString("country"),j.getString("host"),j.getString("ovpn"),j.optInt("ping")));
            }
        }catch(Exception ignored){}
        return list;
    }
    interface Success {void got(ArrayList<Node> list);}
    interface Failure {void failed(String msg);}
    void update(Success success,Failure failure){
        new Thread(()->{
            try{ArrayList<Node> list=fetch();handler.post(()->success.got(list));}
            catch(Exception e){handler.post(()->failure.failed(e.getMessage()));}
        },"free-directory").start();
    }
    static int parsePing(String value){try{return Integer.parseInt(value);}catch(Exception e){return 0;}}
    /** Fetch volunteer profiles; GitHub mirror serves as the bootstrap when VPN Gate is DNS-blocked. */
    ArrayList<Node> fetch() throws Exception {
        Exception primaryError=null;
        try {
            ArrayList<Node> live=parseCsv(readBounded(URL,12000000),LIMIT);
            if(live.isEmpty())throw new IOException("VPN Gate returned no usable profiles");
            save(live);return live;
        }catch(Exception e){primaryError=e;}
        try {
            ArrayList<Node> mirrored=parseMirror(readBounded(MIRROR,3500000));
            if(mirrored.isEmpty())throw new IOException("Public fallback contains no valid servers");
            save(mirrored);return mirrored;
        }catch(Exception fallback){
            throw new IOException("VPN Gate unavailable ("+primaryError.getMessage()+
                "). Public GitHub mirror unavailable ("+fallback.getMessage()+
                "). Previously saved profiles are preserved.",fallback);
        }
    }
    private static byte[] readBounded(String url,int maxBytes) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(8500);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept-Encoding","identity");
        try {
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){
                    if(out.size()+n>maxBytes)throw new IOException("Directory too large");
                    out.write(b,0,n);
                }
            }
            return out.toByteArray();
        }finally{c.disconnect();}
    }
    private static boolean valid(String config){
        if(config==null||config.length()<80||config.length()>75000)return false;
        if(!config.contains("client")||!config.matches("(?s).*\\\\bremote\\\\s+[^\\\\s]+.*"))return false;
        // Public configurations must not execute scripts on the client.
        return !java.util.regex.Pattern.compile("(?im)^\\\\s*(?:script-security|up|down|client-connect|client-disconnect|plugin)\\\\s+").matcher(config).find();
    }
    private ArrayList<Node> parseCsv(byte[] raw,int limit) throws Exception {
        ArrayList<Node> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        String csv=new String(raw,java.nio.charset.StandardCharsets.UTF_8);
        for(String row:csv.split("\\r?\\n")){
            if(row.startsWith("#")||row.startsWith("*"))continue;
            String[] col=row.split(",",15);if(col.length!=15)continue;
            String host=col[1].trim();
            if(!host.matches("[0-9a-fA-F.:]{7,48}")||!seen.add(host))continue;
            try {
                String ovpn=new String(Base64.decode(col[14].trim(),Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8);
                if(valid(ovpn))result.add(new Node(col[5].trim(),host,ovpn,parsePing(col[3].trim())));
            }catch(Exception ignored){}
            if(result.size()>=limit)break;
        }
        result.sort(Comparator.comparingInt(n->n.ping>0?n.ping:999999));
        return result;
    }
    private ArrayList<Node> parseMirror(byte[] raw) throws Exception {
        JSONObject root=new JSONObject(new String(raw,java.nio.charset.StandardCharsets.UTF_8));
        long updated=root.optLong("updated");
        long age=System.currentTimeMillis()-updated;
        if(updated<=0||age< -3600000L||age>172800000L)
            throw new IOException("Mirror outdated or its clock is invalid");
        JSONArray arr=root.getJSONArray("nodes");ArrayList<Node> list=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<Math.min(arr.length(),LIMIT);i++){
            JSONObject node=arr.getJSONObject(i);
            String host=node.optString("host"),config=node.optString("ovpn");
            if(!host.matches("[0-9a-fA-F.:]{7,48}")||!seen.add(host)||!valid(config))continue;
            list.add(new Node(node.optString("country","Unknown"),host,config,node.optInt("ping")));
        }
        list.sort(Comparator.comparingInt(n->n.ping>0?n.ping:999999));
        return list;
    }
    private void save(ArrayList<Node> nodes)throws Exception {
        JSONArray json=new JSONArray();
        for(Node n:nodes){
            JSONObject j=new JSONObject();
            j.put("country",n.country);j.put("host",n.host);
            j.put("ovpn",n.config);j.put("ping",n.ping);json.put(j);
        }
        context.getSharedPreferences("nodes",0).edit()
           .putString("data",json.toString()).putLong("last",System.currentTimeMillis()).apply();
    }
}
