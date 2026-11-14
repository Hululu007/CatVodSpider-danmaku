package com.github.catvod.spider;
import android.app.Activity;import android.content.Context;import android.os.Handler;import android.os.Looper;
class DanmakuSpider {static void log(String value){}}
class PlayerToolbarProbe {static boolean isDiagnose(Context c){return false;}}
class Init {static void run(Runnable r){new Handler(Looper.getMainLooper()).post(r);}}
class Utils {static void safeShowToast(Context c,String s){}}
class DanmakuScanner {static Object lastEpisodeInfo; static void showLeoButtonMenu(Activity a){} static void showLeoButtonMenu(Activity a,Runnable dismiss){}}
class DanmakuUIHelper {static int qrCount,searchCount;static boolean hiddenBeforeSearch,realSearch;static android.app.AlertDialog searchDialog;static String qrUrl;static void showQRCodeDialog(Activity a,String url){qrCount++;qrUrl=url;}static void showQRCodeDialog(Activity a,String url,Runnable dismiss){showQRCodeDialog(a,url);}static void showChapterDialog(Activity a,android.view.View v){} static void showSearchDialog(Activity a,Object e){searchCount++;hiddenBeforeSearch=!a.findViewById(a.getResources().getIdentifier("control","id",a.getPackageName())).isShown();if(realSearch){searchDialog=new android.app.AlertDialog.Builder(a).setTitle("Search lifecycle check").setPositiveButton("Select",(d,w)->{}).create();searchDialog.show();}}}
class NetworkUtils {static String getLocalIpAddress(){return "127.0.0.1";}}

class ChapterManager {static int clears; static boolean clearManualSkipMarkers(Activity a){clears++;return true;} static void onAutoSkipChanged(boolean enabled){}}
class FongMiNativeHelper {static int toggles; static boolean togglePlayback(Activity a){toggles++;return true;}}
