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
  StringBuilder many=new StringBuilder();
  for(int i=0;i<650;i++)many.append("vless://123@node").append(i).append(".example.com:443\n");
  check(FeedParser.parse(many.toString(),"large").size()==650,"large subscriptions must not truncate at 300");
  check(FeedParser.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes()),"x").size()==1,"URL-safe subscription");
  System.out.println("10 parser checks passed");
 }
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
}
