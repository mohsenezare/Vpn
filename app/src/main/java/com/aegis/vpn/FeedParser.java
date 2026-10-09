package com.aegis.vpn;
import java.util.*;
import java.util.regex.*;
import java.net.URI;
/** Pure parser: no network or execution of retrieved data. */
final class FeedParser {
 static final class Entry {
  final String kind, value, source;
  Entry(String k,String v,String s){kind=k;value=v;source=s;}
 }
 static String decode(String s){return s.replace("&amp;","&").replace("&#38;","&").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">");}
 static List<Entry> parse(String body,String source){
  LinkedHashMap<String,Entry> result=new LinkedHashMap<>();
  String text=decode(body);
  if(!text.contains("://")&&text.trim().matches("[A-Za-z0-9+/=_\\s-]+")){
   try{text=new String(Base64.getDecoder().decode(text.replaceAll("\\s+","")),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception ignored){}
  }
  Matcher m=Pattern.compile("(?i)(?:vless|vmess|trojan|ss|hysteria2|hy2)://[^\\s<>\"']{8,8192}|(?:tg://proxy\\?|https://t\\.me/proxy\\?)[^\\s<>\"']{8,4096}").matcher(text);
  while(m.find()&&result.size()<300){
   String v=m.group();String kind=v.startsWith("tg:")||v.startsWith("https:")?"PROXY":"V2RAY";
   if(kind.equals("PROXY")&&!(v.contains("server=")&&v.contains("port=")&&v.contains("secret=")))continue;
   try{if(kind.equals("V2RAY")&&!v.startsWith("vmess://")&&!v.startsWith("ss://")){
    URI u=new URI(v);if(u.getHost()==null||u.getPort()<1||u.getPort()>65535)continue;
   }}catch(Exception e){continue;}
   result.put(v,new Entry(kind,v,source));
  }
  return new ArrayList<>(result.values());
 }
}
