package com.github.catvod.spider;
import android.app.Activity;import android.content.Context;import java.util.*;
class DanmakuSpider{static void log(String s){android.util.Log.i("ChapterSmoke",s);}}
class Utils{static int getPort(){return 9978;}static void invalidatePort(){}static void safeShowToast(Context c,String s){}}
class ToolbarHostMap{final Map<String,android.view.View> actions=new HashMap<>();static ToolbarHostMap resolve(Activity a){return null;}}
class AutoSkipService {
 static Callback pending;static int queries;
 static class Markers{long introStartMs=-1,introEndMs=-1,outroStartMs=-1,recapStartMs=-1,recapEndMs=-1,durationMs;boolean sourcesComplete=true;List<SkipSegmentSources.Segment> segments=new ArrayList<>();String source="fixture",error="",imdbId="";boolean hasIntro(){return introEndMs>Math.max(0,introStartMs);}boolean hasOutro(){return outroStartMs>0;}boolean hasRecap(){return recapStartMs>=0;}boolean hasAny(){return hasIntro()||hasOutro()||hasRecap()||!segments.isEmpty();}}
 interface Callback{void onResult(Markers m);}
 static boolean refreshAsync(Context c,ChapterManager.MediaInfo m,DanmakuConfig config,Callback cb){pending=cb;queries++;return true;}
}
