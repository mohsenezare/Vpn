package com.aegis.vpn;
import java.util.*;
public class FeedParserTest {
 public static void main(String[] args){
  String v="vless://123@example.com:443?security=tls#test";
  check(FeedParser.parse(v+"\n"+v,"x").size()==1,"dedup");
  check(FeedParser.parse(Base64.getEncoder().encodeToString(v.getBytes()),"x").size()==1,"subscription");
  check(FeedParser.parse(Base64.getEncoder().encodeToString(("#profile-title: FAST\\n"+v+"\\n").replace("\\n","\n").getBytes()),"fast/configs_base64.txt").size()==1,"fast base64 feed");
  check(FeedParser.parse("<a href=\"vless://123@example.com:443?security=tls&amp;type=ws\">x</a>","x").get(0).value.contains("&type=ws"),"entities");
  check(FeedParser.parse("tg://proxy?server=x&port=443&secret=abc","x").isEmpty(),"telegram removed");
  check(FeedParser.parse("vless://123@example.com:99999","x").isEmpty(),"port range");
  check(FeedParser.parse("data-post=\"mitivpn/123\"><b>config.npvt</b>","x").isEmpty(),"Napsternet removed");
  check(FeedParser.parse("<html>Unavailable</html>","x").isEmpty(),"empty feed");
  System.out.println("8 parser checks passed");
 }
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
}
