package com.github.catvod.net;
import java.util.Map;
public class OkHttp {
 public static String media="{}";
 public static int controls;
 public static boolean controlSuccess=true;
 public static Runnable onControl;
 public static String string(String url,int timeout){return media;}
 public static String post(String url,Map<String,String> args){
  controls++;
  Runnable callback=onControl;onControl=null;
  if(callback!=null)callback.run();
  return controlSuccess?"ok":"Playback unavailable";
 }
}
