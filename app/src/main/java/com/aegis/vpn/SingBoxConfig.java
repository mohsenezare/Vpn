package com.aegis.vpn;

import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Convert validated public proxy links into a self-contained sing-box 1.12 TUN config.
 * Never disable TLS certificate validation for unknown volunteer nodes.
 */
final class SingBoxConfig {
    static String build(String link)throws Exception {
        if(link==null||link.length()>8192)throw new IllegalArgumentException("Invalid config size");
        JSONObject proxy=outbound(link);
        JSONObject dns=new JSONObject();
        dns.put("servers",new JSONArray().put(new JSONObject()
            .put("tag","remote").put("address","tls://1.1.1.1").put("detour","proxy")));
        dns.put("final","remote");
        JSONObject tun=new JSONObject().put("type","tun").put("tag","tun-in")
            .put("inet4_address",new JSONArray().put("172.19.0.1/30"))
            .put("mtu",1400).put("auto_route",true).put("strict_route",true).put("stack","mixed");
        JSONObject cfg=new JSONObject();
        cfg.put("log",new JSONObject().put("level","warn"));
        cfg.put("dns",dns);
        cfg.put("inbounds",new JSONArray().put(tun));
        cfg.put("outbounds",new JSONArray().put(proxy).put(new JSONObject().put("type","direct").put("tag","direct")));
        cfg.put("route",new JSONObject().put("final","proxy"));
        return cfg.toString();
    }
    /**
     * Multi-server configuration. The actual sing-box urltest outbound measures
     * HTTP request latency through each remote proxy and switches to a viable one.
     * This is stronger than VPN Gate's advertised ping or a bare TCP port probe.
     */
    static String buildAuto(List<String> links)throws Exception {
        if(links==null||links.isEmpty())throw new IllegalArgumentException("No candidate nodes");
        LinkedHashMap<String,JSONObject> candidates=new LinkedHashMap<>();
        for(String link:links){
            if(candidates.size()>=12)break;
            if(link==null||candidates.containsKey(link))continue;
            try{candidates.put(link,outbound(link));}
            catch(Exception ignored){}
        }
        if(candidates.isEmpty())throw new IllegalArgumentException("No supported public nodes");
        if(candidates.size()==1)return build(candidates.keySet().iterator().next());
        JSONObject configuration=new JSONObject(build(candidates.keySet().iterator().next()));
        JSONArray outputs=new JSONArray(),names=new JSONArray();
        int i=0;
        for(JSONObject node:candidates.values()){
            String tag="node-"+i++;
            node.put("tag",tag);
            outputs.put(node);names.put(tag);
        }
        JSONObject tester=new JSONObject().put("type","urltest").put("tag","proxy")
            .put("outbounds",names)
            .put("url","https://www.gstatic.com/generate_204")
            .put("interval","20s")
            .put("tolerance",100);
        outputs.put(tester).put(new JSONObject().put("type","direct").put("tag","direct"));
        configuration.put("outbounds",outputs);
        return configuration.toString();
    }
    static JSONObject outbound(String link)throws Exception {
        if(link.startsWith("vmess://"))return vmess(link.substring(8));
        if(link.startsWith("ss://"))return shadowsocks(link.substring(5));
        Uri uri=Uri.parse(link);
        String scheme=uri.getScheme();
        if(scheme==null)throw new IllegalArgumentException("Missing protocol");
        scheme=scheme.toLowerCase(Locale.ROOT);
        if(!Arrays.asList("vless","trojan","hysteria2","hy2").contains(scheme))
            throw new IllegalArgumentException("Unsupported protocol "+scheme);
        String host=uri.getHost();int port=uri.getPort();
        validateHost(host,port);
        JSONObject outbound=new JSONObject().put("type",scheme.equals("hy2")?"hysteria2":scheme)
            .put("tag","proxy").put("server",host).put("server_port",port);
        String user=uri.getUserInfo();
        if(user==null||user.isEmpty())throw new IllegalArgumentException("Missing proxy account");
        String credentials=Uri.decode(user);
        if(scheme.equals("vless")){
            if(!credentials.matches("[0-9a-fA-F-]{32,36}"))throw new IllegalArgumentException("Malformed VLESS UUID");
            outbound.put("uuid",credentials);
            String flow=param(uri,"flow");
            if(!flow.isEmpty())outbound.put("flow",flow);
        }else outbound.put("password",credentials);
        String security=param(uri,"security");
        if(scheme.equals("trojan")||scheme.equals("hysteria2")||scheme.equals("hy2")
            ||security.equals("tls")||security.equals("reality")){
            JSONObject tls=new JSONObject().put("enabled",true);
            String sni=param(uri,"sni","serverName");
            if(!sni.isEmpty())tls.put("server_name",sni);
            String fp=param(uri,"fp","fingerprint");
            // REALITY requires a uTLS client hello even when fp is absent in a share link.
            // Prevent a single malformed REALITY node from killing the entire URLTest group.
            if(security.equals("reality")){
                String[] known={"chrome","firefox","edge","safari","ios","android","random","randomized"};
                boolean accepted=false;
                for(String value:known)if(value.equalsIgnoreCase(fp))accepted=true;
                tls.put("utls",new JSONObject().put("enabled",true)
                    .put("fingerprint",accepted?fp.toLowerCase(Locale.ROOT):"chrome"));
            }else if(!fp.isEmpty())tls.put("utls",new JSONObject()
                .put("enabled",true).put("fingerprint",fp));
            if(security.equals("reality")){
                String key=param(uri,"pbk","publicKey");
                if(key.isEmpty()||!key.matches("[a-zA-Z0-9_-]{42,44}={0,2}"))
                    throw new IllegalArgumentException("Reality public key invalid");
                String padded=key.replace('-','+').replace('_','/');
                int remainder=padded.length()%4;
                if(remainder>0)padded+="====".substring(remainder);
                if(Base64.decode(padded,Base64.DEFAULT).length!=32)
                    throw new IllegalArgumentException("Reality public key length invalid");
                JSONObject reality=new JSONObject().put("enabled",true).put("public_key",key);
                String sid=param(uri,"sid","shortId");
                if(!sid.matches("(?i)[a-f0-9]{0,16}")||sid.length()%2!=0)
                    throw new IllegalArgumentException("Reality short ID invalid");
                reality.put("short_id",sid);
                tls.put("reality",reality);
            }
            String alpn=param(uri,"alpn");
            if(!alpn.isEmpty()){
                JSONArray protocols=new JSONArray();
                for(String v:alpn.split(","))if(!v.isEmpty())protocols.put(v);
                tls.put("alpn",protocols);
            }
            outbound.put("tls",tls);
        }
        String net=param(uri,"type");
        if(!net.isEmpty()&&!Arrays.asList("tcp","ws","grpc","http","h2").contains(net))
            throw new IllegalArgumentException("Unsupported transport "+net);
        String path=param(uri,"path");
        if(net.equals("ws")){
            JSONObject transport=new JSONObject().put("type","ws").put("path",path.isEmpty()?"/":path);
            String wsHost=param(uri,"host");
            if(!wsHost.isEmpty())transport.put("headers",new JSONObject().put("Host",wsHost));
            outbound.put("transport",transport);
        }else if(net.equals("grpc")){
            outbound.put("transport",new JSONObject().put("type","grpc")
                .put("service_name",param(uri,"serviceName")));
        }else if(net.equals("http")||net.equals("h2")){
            JSONObject transport=new JSONObject().put("type","http");
            if(!path.isEmpty())transport.put("path",path);
            outbound.put("transport",transport);
        }
        if(scheme.equals("hysteria2")||scheme.equals("hy2")){
            String obfs=param(uri,"obfs");
            if(obfs.equals("salamander")){
                outbound.put("obfs",new JSONObject().put("type","salamander")
                    .put("password",param(uri,"obfs-password","obfsPassword")));
            }
        }
        return outbound;
    }
    private static JSONObject vmess(String encoded)throws Exception {
        JSONObject v=new JSONObject(decodeBase64(encoded));
        String host=v.optString("add");int port=Integer.parseInt(v.optString("port","0"));
        validateHost(host,port);
        String uuid=v.optString("id");
        if(!uuid.matches("[0-9a-fA-F-]{32,36}"))throw new IllegalArgumentException("VMess account invalid");
        JSONObject out=new JSONObject().put("type","vmess").put("tag","proxy").put("server",host)
            .put("server_port",port).put("uuid",uuid).put("security",v.optString("scy","auto"))
            .put("alter_id",v.optInt("aid",0));
        String network=v.optString("net"),path=v.optString("path"),sni=v.optString("sni");
        if(!network.isEmpty()&&!Arrays.asList("tcp","ws","grpc").contains(network))
            throw new IllegalArgumentException("Unsupported VMess transport "+network);
        if(v.optString("tls").equalsIgnoreCase("tls")){
            JSONObject tls=new JSONObject().put("enabled",true);
            if(!sni.isEmpty())tls.put("server_name",sni);
            out.put("tls",tls);
        }
        if(network.equals("ws")){
            JSONObject ws=new JSONObject().put("type","ws").put("path",path.isEmpty()?"/":path);
            if(!v.optString("host").isEmpty())
                ws.put("headers",new JSONObject().put("Host",v.optString("host")));
            out.put("transport",ws);
        }else if(network.equals("grpc")){
            out.put("transport",new JSONObject().put("type","grpc").put("service_name",path));
        }
        return out;
    }
    private static JSONObject shadowsocks(String encoded)throws Exception {
        String raw=encoded.split("#",2)[0];
        String head,host;int port;
        if(raw.contains("@")){
            String[] pieces=raw.split("@",2);
            head=pieces[0].contains(":")?Uri.decode(pieces[0]):decodeBase64(pieces[0]);
            Uri uri=Uri.parse("ss://"+pieces[1]);
            host=uri.getHost();port=uri.getPort();
        }else{
            String decoded=decodeBase64(raw);
            int at=decoded.lastIndexOf('@');
            if(at<1)throw new IllegalArgumentException("Invalid SS share");
            head=decoded.substring(0,at);
            Uri uri=Uri.parse("ss://"+decoded.substring(at+1));
            host=uri.getHost();port=uri.getPort();
        }
        validateHost(host,port);
        int sep=head.indexOf(':');
        if(sep<1)throw new IllegalArgumentException("Shadowsocks cipher or password missing");
        return new JSONObject().put("type","shadowsocks").put("tag","proxy")
            .put("server",host).put("server_port",port)
            .put("method",head.substring(0,sep)).put("password",head.substring(sep+1));
    }
    /**
     * Remove only a failing auto-group node from an invalid generated config.
     * Engine errors use outbound[zeroBasedIndex]. Do not modify manual configs
     * or remove urltest/direct; fail rather than silently bypassing the proxy.
     */
    static String dropInvalidAutoNode(String config,String error){
        try{
            if(error==null)return null;
            java.util.regex.Matcher m=java.util.regex.Pattern
                .compile("(?i)outbound\\[([0-9]+)\\]").matcher(error);
            if(!m.find())return null;
            int index=Integer.parseInt(m.group(1));
            JSONObject root=new JSONObject(config);
            JSONArray outputs=root.getJSONArray("outbounds");
            if(index<0||index>=outputs.length())return null;
            JSONObject bad=outputs.getJSONObject(index);
            String tag=bad.optString("tag","");
            if(!tag.startsWith("node-"))return null;
            JSONArray remaining=new JSONArray();
            for(int i=0;i<outputs.length();i++)if(i!=index)remaining.put(outputs.getJSONObject(i));
            for(int i=0;i<remaining.length();i++){
                JSONObject o=remaining.getJSONObject(i);
                if(!o.optString("type").equals("urltest"))continue;
                JSONArray candidates=o.getJSONArray("outbounds"),valid=new JSONArray();
                for(int j=0;j<candidates.length();j++)
                    if(!tag.equals(candidates.getString(j)))valid.put(candidates.getString(j));
                if(valid.length()==0)return null;
                o.put("outbounds",valid);
            }
            root.put("outbounds",remaining);
            return root.toString();
        }catch(Exception ignored){return null;}
    }
    static boolean supported(String value) {
        try{outbound(value);return true;}catch(Exception e){return false;}
    }
    private static String decodeBase64(String value){
        String cleaned=Uri.decode(value.replaceAll("[\\r\\n\\s]",""));
        cleaned=cleaned.replace('-','+').replace('_','/');
        int rem=cleaned.length()%4;
        if(rem>0)cleaned+="====".substring(rem);
        return new String(Base64.decode(cleaned,Base64.DEFAULT),StandardCharsets.UTF_8);
    }
    private static String param(Uri uri,String... names){
        for(String name:names){
            try{String v=uri.getQueryParameter(name);if(v!=null&&!v.isEmpty())return v;}
            catch(Exception ignored){}
        }
        return "";
    }
    private static void validateHost(String host,int port){
        if(host==null||host.length()>253||host.isEmpty()||host.indexOf(' ')>=0
            ||port<1||port>65535)throw new IllegalArgumentException("Invalid endpoint");
        if(host.contains("/")||host.contains("\\"))throw new IllegalArgumentException("Invalid hostname");
    }
}
