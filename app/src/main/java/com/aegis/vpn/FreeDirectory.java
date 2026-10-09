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
        final long speed,uptime;
        Node(String country,String host,String config,int ping){
            this(country,host,config,ping,0,0);
        }
        Node(String country,String host,String config,int ping,long speed,long uptime){
            this.country=country;this.host=host;this.config=config;
            this.ping=ping;this.speed=speed;this.uptime=uptime;
        }
        String title(){return country+" · "+host;}
    }
    FreeDirectory(Context c){context=c.getApplicationContext();}
    boolean isStale(){return System.currentTimeMillis()-context.getSharedPreferences("nodes",0).getLong("last",0)>21600000L;}
    ArrayList<Node> load(){
        ArrayList<Node> list=new ArrayList<>();
        try{
            JSONArray arr=new JSONArray(context.getSharedPreferences("nodes",0).getString("data","[]"));
            for(int i=0;i<Math.min(LIMIT,arr.length());i++){
                JSONObject j=arr.getJSONObject(i);
                list.add(new Node(j.getString("country"),j.getString("host"),j.getString("ovpn"),
                    j.optInt("ping"),j.optLong("speed"),j.optLong("uptime")));
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
    static long number(String value){try{return Long.parseLong(value.trim());}catch(Exception e){return 0L;}}
    static boolean tcp(String config){
        return java.util.regex.Pattern.compile("(?im)^\\s*proto\\s+tcp").matcher(config).find();
    }
    static java.net.InetSocketAddress tcpEndpoint(Node node){
        if(!tcp(node.config))return null;
        java.util.regex.Matcher m=java.util.regex.Pattern
            .compile("(?im)^\\s*remote\\s+([a-zA-Z0-9.:-]+)\\s+([0-9]{2,5})").matcher(node.config);
        if(!m.find())return null;
        int port=parsePing(m.group(2));
        if(port<1||port>65535)return null;
        return new java.net.InetSocketAddress(m.group(1),port);
    }
    static double rating(Node n){
        // Publisher speed is in bit/s; neither this nor publisher ping
        // guarantees an authenticated OpenVPN connection.
        double speed=Math.log1p(Math.max(0,n.speed)/1_000_000.0);
        double hours=Math.log1p(Math.max(0,n.uptime)/3600000.0);
        double ping=n.ping>0?Math.min(n.ping,600):300;
        return speed*2.4+hours*.50+(tcp(n.config)?2.0:0.0)-ping*.004;
    }
    static ArrayList<Node> diverse(List<Node> nodes,int limit){
        ArrayList<Node> sorted=new ArrayList<>(nodes);
        sorted.sort((a,b)->Double.compare(rating(b),rating(a)));
        ArrayList<Node> result=new ArrayList<>();
        HashMap<String,Integer> countries=new HashMap<>();
        // Prevent dozens of claimed 2ms Japanese relays crowding out choices.
        for(Node n:sorted){
            int count=countries.getOrDefault(n.country,0);
            if(count>=Math.max(9,limit/5))continue;
            result.add(n);countries.put(n.country,count+1);
            if(result.size()>=limit)break;
        }
        // Refill for sparse days when only a few countries report profiles.
        for(Node n:sorted)if(result.size()<limit&&!result.contains(n))result.add(n);
        return result;
    }
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
        if(!config.contains("client")||!config.matches("(?s).*\\bremote\\s+[^\\s]+.*"))return false;
        // Public configurations must not execute scripts on the client.
        return !java.util.regex.Pattern.compile("(?im)^\\s*(?:script-security|up|down|client-connect|client-disconnect|plugin)\\s+").matcher(config).find();
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
                if(valid(ovpn))result.add(new Node(col[5].trim(),host,ovpn,
                parsePing(col[3].trim()),number(col[4]),number(col[8])));
            }catch(Exception ignored){}
            if(result.size()>=600)break;
        }
        return diverse(result,limit);
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
            list.add(new Node(node.optString("country","Unknown"),host,config,
                node.optInt("ping"),node.optLong("speed"),node.optLong("uptime")));
        }
        return diverse(list,LIMIT);
    }
    private void save(ArrayList<Node> nodes)throws Exception {
        JSONArray json=new JSONArray();
        for(Node n:nodes){
            JSONObject j=new JSONObject();
            j.put("country",n.country);j.put("host",n.host);
            j.put("ovpn",n.config);j.put("ping",n.ping);
            j.put("speed",n.speed);j.put("uptime",n.uptime);json.put(j);
        }
        context.getSharedPreferences("nodes",0).edit()
           .putString("data",json.toString()).putLong("last",System.currentTimeMillis()).apply();
    }
}
