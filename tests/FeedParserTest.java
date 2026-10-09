package com.aegis.vpn;
import java.util.*;
public class FeedParserTest {
 public static void main(String[] args){
  String v="vless://123@example.com:443?security=tls#test";
  check(FeedParser.parse(v+"\n"+v,"x").size()==1,"dedup");
  check(FeedParser.parse(Base64.getEncoder().encodeToString(v.getBytes()),"x").size()==1,"subscription");
  check(FeedParser.parse("<a href=\"https://t.me/proxy?server=x&amp;port=443&amp;secret=abc\">x</a>","x").get(0).value.contains("&port="),"entities");
  check(FeedParser.parse("tg://proxy?server=x&port=443","x").isEmpty(),"missing secret");
  check(FeedParser.parse("vless://123@example.com:99999","x").isEmpty(),"port range");
  check(FeedParser.parse("data-post=\"mitivpn/123\"><b>config.npvt</b>","x").get(0).value.equals("https://t.me/mitivpn/123"),"Napsternet document post");
  check(FeedParser.parse("<html>Unavailable</html>","x").isEmpty(),"empty feed");
  String latest="<div data-post=\"npv_iran/19\"></div><div data-post=\"npv_iran/20\"></div>"
     +"<div data-post=\"npv_iran/17\"></div><div data-post=\"npv_iran/18\"></div>";
  List<FeedParser.Entry> recent=FeedParser.latestNapster(latest,"npv_iran",3);
  check(recent.size()==3,"latest three count");
  check(FeedParser.postId(recent.get(0).value)==20,"latest post first");
  check(FeedParser.postId(recent.get(2).value)==18,"latest third");
  System.out.println("10 parser checks passed");
 }
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
}
