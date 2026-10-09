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
    ArrayList<Node> fetch() throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(URL).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(12000);conn.setReadTimeout(18000);
        try{
            if(conn.getResponseCode()!=200)throw new IOException("HTTP "+conn.getResponseCode());
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=conn.getInputStream()){
                byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){
                    if(out.size()+n>12000000)throw new IOException("Directory too large");
                    out.write(b,0,n);
                }
            }
            ArrayList<Node> nodes=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for(String row:out.toString("UTF-8").split("\\r?\\n")){
                if(row.startsWith("#")||row.startsWith("*"))continue;
                String[] col=row.split(",",15);if(col.length!=15)continue;
                String host=col[1].trim();
                if(!host.matches("[0-9a-fA-F.:]{7,48}")||!seen.add(host))continue;
                try{
                    String ovpn=new String(Base64.decode(col[14].trim(),Base64.DEFAULT),java.nio.charset.StandardCharsets.UTF_8);
                    if(ovpn.length()<80||ovpn.length()>60000||!ovpn.contains("remote ")||!ovpn.contains("client"))continue;
                    nodes.add(new Node(col[5].trim(),host,ovpn,parsePing(col[3].trim())));
                }catch(Exception ignored){}
                if(nodes.size()>=LIMIT)break;
            }
            if(nodes.isEmpty())throw new IOException("No valid OpenVPN profiles received.");
            nodes.sort(Comparator.comparingInt(n->n.ping>0?n.ping:999999));
            JSONArray json=new JSONArray();
            for(Node n:nodes){
                JSONObject j=new JSONObject();j.put("country",n.country);j.put("host",n.host);j.put("ovpn",n.config);j.put("ping",n.ping);json.put(j);
            }
            context.getSharedPreferences("nodes",0).edit()
                .putString("data",json.toString()).putLong("last",System.currentTimeMillis()).apply();
            return nodes;
        }finally{conn.disconnect();}
    }
}
