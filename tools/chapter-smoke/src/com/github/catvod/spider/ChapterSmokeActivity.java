package com.github.catvod.spider;
import android.app.*;import android.os.*;import android.widget.*;import android.content.*;import java.lang.reflect.*;import java.lang.ref.*;import com.fongmi.android.tv.bean.History;import com.github.catvod.net.OkHttp;import com.google.gson.Gson;
public class ChapterSmokeActivity extends Activity {
 public static class Source {final boolean selected; public Source(boolean selected){this.selected=selected;}public boolean isSelected(){return selected;}}
 public History history;int checks;ChapterManager.MediaInfo media;
 public Object loadingDecoy;
 static class HistoryNode { Object a,b,c,d; }
 static HistoryNode historyTree(int depth) {
  HistoryNode node=new HistoryNode();
  if(depth>0){node.a=historyTree(depth-1);node.b=historyTree(depth-1);node.c=historyTree(depth-1);node.d=historyTree(depth-1);}
  return node;
 }
 void historyLoadingChecks()throws Exception {
  History decoy=new History("unrelated-loading-record",12345,6789);
  HistoryNode holder=new HistoryNode();holder.a=decoy;
  main(()->{history=null;loadingDecoy=holder;});
  for(int i=0;i<3;i++) check(!FongMiNativeHelper.readHistory(this).available,"null live History does not scan or select an unrelated record");
  History first=new History("loaded-record",1000,2000);
  main(()->history=first);
  check(FongMiNativeHelper.readHistory(this).opening==1000,"loaded History becomes available after null");
  History next=new History("next-record",3000,4000);
  main(()->history=next);
  check(FongMiNativeHelper.readHistory(this).opening==3000,"cached field reads the replacement History");
  main(()->history=null);
  check(!FongMiNativeHelper.readHistory(this).available,"returning to loading never reuses the previous History");
  Method scan=FongMiNativeHelper.class.getDeclaredMethod("findObjectsByTypeName",Object.class,String.class,int.class);scan.setAccessible(true);
  String type="com.fongmi.android.tv.bean.History";
  check(((java.util.List<?>)scan.invoke(null,holder,type,4)).contains(decoy),"unknown host nested History fallback remains available");
  holder.b=holder;
  check(((java.util.List<?>)scan.invoke(null,holder,type,4)).size()==1,"fallback handles cycles without duplicates");
  HistoryNode wide=historyTree(4);((HistoryNode)((HistoryNode)((HistoryNode)wide.d).d).d).d=decoy;
  check(((java.util.List<?>)scan.invoke(null,wide,type,4)).isEmpty(),"fallback budget counts inspected objects rather than History matches");
  main(()->{history=null;loadingDecoy=null;});History.records.clear();
 }
 protected void onCreate(Bundle b){super.onCreate(b);LinearLayout root=new LinearLayout(this);for(String n:new String[]{"opening","ending"}){TextView t=new TextView(this);t.setId(getResources().getIdentifier(n,"id",getPackageName()));t.setText("wrong time");root.addView(t);}setContentView(root);new Thread(()->{try{runChecks();android.util.Log.i("ChapterSmoke","ALL PASS checks="+checks);}catch(Throwable t){android.util.Log.e("ChapterSmoke","FAIL checks="+checks,t);}}).start();}
 void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
 void field(Class<?> c,String n,Object value)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(null,value);}
 Object call(String n,Object...args)throws Exception{for(Method m:ChapterManager.class.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterTypes().length==args.length){m.setAccessible(true);try{return m.invoke(null,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}throw new NoSuchMethodException(n);}
 void main(Runnable r)throws Exception{java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);Throwable[] error={null};runOnUiThread(()->{try{r.run();}catch(Throwable t){error[0]=t;}finally{latch.countDown();}});if(!latch.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("main timeout");if(error[0]!=null)throw new RuntimeException(error[0]);}
 void config(boolean enabled){DanmakuConfig c=DanmakuConfigManager.getConfig(this);c.setAutoSkipEnabled(enabled);DanmakuConfigManager.saveConfig(this,c);}
 ChapterManager.ChapterData data(){return ChapterManager.loadData(this,media);}
 ChapterManager.SkipSession session()throws Exception{
  Field f=ChapterManager.class.getDeclaredField("skipSession");f.setAccessible(true);
  return (ChapterManager.SkipSession)f.get(null);
 }
 void tick(long position)throws Exception{
  OkHttp.media="{\"title\":\"Fixture\",\"artist\":\"E01\",\"url\":\"http://local\",\"duration\":300000,\"position\":"+position+",\"state\":3}";
  call("autoSkipTick");
 }
 void ready()throws Exception{field(ChapterManager.class,"suppressAutoSkipUntil",0L);session().lastAttemptAt=0;}
 void replay()throws Exception{
  ChapterManager.onPlaybackStopped(this);
  field(ChapterManager.class,"activityRef",new WeakReference<Activity>(this));
  call("observePlaybackSession",this,media);
 }
 void runChecks()throws Exception{
  historyLoadingChecks();
  android.content.SharedPreferences hostPrefs=getSharedPreferences("host-danmaku-test",0);hostPrefs.edit().clear().commit();
  check(HostDanmakuGate.preferencesAllow(hostPrefs),"unset native preference does not disable initial auto push");
  hostPrefs.edit().putBoolean("danmaku_show",false).commit();check(!HostDanmakuGate.preferencesAllow(hostPrefs),"host display off blocks auto push");
  hostPrefs.edit().putBoolean("danmaku_show",true).putBoolean("danmaku_load",false).commit();check(!HostDanmakuGate.preferencesAllow(hostPrefs),"explicit host loading off blocks auto push");
  hostPrefs.edit().putBoolean("danmaku_load",true).commit();check(HostDanmakuGate.preferencesAllow(hostPrefs),"host switches reenabled permit auto push");
  check(HostDanmakuGate.sourceSelected(java.util.Collections.emptyList()),"no source yet permits first automatic result");
  check(HostDanmakuGate.sourceSelected(java.util.Arrays.asList(new Source(true),new Source(false))),"selected source remains enabled");
  check(!HostDanmakuGate.sourceSelected(java.util.Arrays.asList(new Source(false),new Source(false))),"all sources deselected block automatic resurrection");
  check(HostDanmakuGate.sourceSelected(java.util.Arrays.asList(new Source(false),new Source(true))),"manual reselect restores permission");

  for(String n:new String[]{"danmaku_config","danmaku_chapter_data","danmaku_native_skip_paused_v1","danmaku_skip_pause_v1"})getSharedPreferences(n,0).edit().clear().commit();
  DanmakuConfigManager.getConfig(this);
  field(ChapterManager.class,"activityRef",new WeakReference<Activity>(this));
  media=ChapterManager.fromPlaybackValues("Fixture","E01","http://local",40000,300000);
  field(ChapterManager.class,"lastPlaybackInfo",media);
  main(()->history=new History("series",90000,20000));
  History other=new History("other",60000,10000);
  ChapterManager.ChapterData automatic=new ChapterManager.ChapterData();automatic.automaticOnlyVersion=1;automatic.autoMarkerVersion=3;automatic.introStartMs=30000;automatic.introEndMs=80000;automatic.outroStartMs=270000;automatic.autoIntro=true;automatic.autoEnding=true;automatic.autoIntroResolved=true;automatic.autoOutroResolved=true;automatic.chapters.add(new ChapterManager.Chapter("custom","Existing",10000,false));
  ChapterManager.saveData(this,media,automatic);
  config(false);
  check(!ChapterManager.isAutoSkipActive(),"master off persisted");
  check(history.getOpening()==0&&history.getEnding()==0,"live History disabled immediately");
  check(other.getOpening()==0&&other.getEnding()==0,"saved History disabled before switching sources");
  check(data().introEndMs==80000&&data().outroStartMs==270000,"provider data retained on off");
  check(getSharedPreferences("danmaku_native_skip_paused_v1",0).getAll().size()==2,"durable backups per host History");
  field(FongMiNativeHelper.class,"lastSkipMode",null);
  FongMiNativeHelper.syncNativeSkipState(this,false);
  check(getSharedPreferences("danmaku_native_skip_paused_v1",0).getString("1|series","").equals("90000,20000"),"restart cannot overwrite backup with zero");
  config(true);
  check(history.getOpening()==90000&&history.getEnding()==20000,"reenable restores manual values");
  check(other.getOpening()==60000&&other.getEnding()==10000,"reenable restores inactive records");
  check(getSharedPreferences("danmaku_native_skip_paused_v1",0).getAll().isEmpty(),"restored backups retired");
  main(()->{history.setOpening(0);history.setEnding(0);});
  FongMiNativeHelper.syncNativeSkipState(this,true);
  check(history.getOpening()==0&&history.getEnding()==0,"native manual clear never refilled by provider");
  main(()->{history.setOpening(110000);history.setEnding(15000);});
  config(false);
  main(()->history.setOpening(120000));
  FongMiNativeHelper.syncNativeSkipState(this,false);
  check(history.getOpening()==0,"new manual mark paused while off");
  config(true);check(history.getOpening()==120000&&history.getEnding()==15000,"new paused mark restores");
  config(false);
  check(ChapterManager.clearManualSkipMarkers(this),"clear succeeds while paused");
  config(true);
  check(history.getOpening()==0&&history.getEnding()==0,"cleared marks cannot resurrect on on");
  check(other.getOpening()==60000&&other.getEnding()==10000,"clear preserves other series");
  check(data().introEndMs==80000&&data().outroStartMs==270000&&data().chapters.size()==1,"clear preserves provider/custom chapter data");
  main(()->check(((TextView)findViewById(getResources().getIdentifier("opening","id",getPackageName()))).getText().toString().equals("片头"),"clear resets visible manual label"));
  ChapterManager.ChapterData mixed=new ChapterManager.ChapterData();mixed.manualIntroOverride=true;mixed.introEndMs=123000;mixed.introStartMs=0;mixed.autoIntro=true;mixed.outroStartMs=270000;mixed.autoEnding=true;mixed.autoOutroResolved=true;mixed.chapters.add(new ChapterManager.Chapter("keep","Keep",3000,false));
  ChapterManager.saveData(this,media,mixed);
  check(data().introEndMs==-1&&!data().manualIntroOverride&&!data().autoIntroResolved,"old manual intro migrated out");
  check(data().outroStartMs==270000&&data().chapters.size()==1,"unmixed provider side preserved");
  check(data().automaticOnlyVersion==1,"migration durable");
  mixed=new ChapterManager.ChapterData();mixed.manualOutroOverride=true;mixed.outroStartMs=120000;mixed.introStartMs=30000;mixed.introEndMs=80000;mixed.autoIntro=true;ChapterManager.saveData(this,media,mixed);
  check(data().outroStartMs==-1&&data().introEndMs==80000,"old manual outro migrated independently");
  ChapterManager.saveData(this,media,automatic);
  config(false);int queries=AutoSkipService.queries;int controls=OkHttp.controls;
  OkHttp.media="{\"title\":\"Fixture\",\"artist\":\"E01\",\"duration\":300000,\"position\":50000,\"state\":3}";
  call("autoSkipTick");
  check(OkHttp.controls==controls&&AutoSkipService.queries==queries,"off tick neither seeks nor queries");
  check(!FongMiNativeHelper.seekToAutomatically(session(),80000)&&!FongMiNativeHelper.nextEpisodeAutomatically(session()),"main-thread auto dispatch rechecks off");
  AutoSkipService.Markers late=new AutoSkipService.Markers();late.introStartMs=0;late.introEndMs=140000;
  call("applyAutoMarkers",this,media,late);
  check(data().introEndMs==80000,"late result after off discarded");
  config(true);
  ChapterManager.MediaInfo previous=ChapterManager.fromPlaybackValues("Fixture","E00","",1,300000);
  call("applyAutoMarkers",this,previous,late);
  check(data().introEndMs==80000,"late previous episode discarded");
  call("applyAutoMarkers",this,media,late);
  check(data().introEndMs==140000&&history.getOpening()==0,"provider result never writes native History");
  ChapterManager.saveData(this,media,automatic);

  // Native manual controls remain independent of provider once-per-playback state.
  main(()->{history.setOpening(90000);history.setEnding(5000);});
  controls=OkHttp.controls;ready();tick(50000);
  check(OkHttp.controls==controls,"native manual side takes priority without importing");
  check(data().introEndMs==80000&&data().outroStartMs==270000,"manual polling never pollutes chapters");
  check(!session().introDone&&!session().outroDone,"native priority does not consume Auto allowance");
  History.failEnumeration=true;field(FongMiNativeHelper.class,"lastSkipMode",null);config(false);
  check(history.getOpening()==0&&history.getEnding()==0,"enumeration fallback still disables live record");
  History.failEnumeration=false;config(true);
  History.failWrite=true;
  check(!ChapterManager.clearManualSkipMarkers(this),"failed host write never reports clear success");
  History.failWrite=false;check(ChapterManager.clearManualSkipMarkers(this),"clear recovers after transient write failure");
  config(false);FongMiNativeHelper.syncNativeSkipState(this,true);
  check(history.getOpening()==0&&history.getEnding()==0,"stale queued enable cannot override latest config");
  config(true);

  // Actual poll/control path: first success consumes only its own side.
  replay();controls=OkHttp.controls;tick(50000);
  check(OkHttp.controls==controls&&!session().introDone,"new playback retains initial ten second guard");
  ready();tick(50000);
  check(OkHttp.controls==controls+1&&session().introDone&&!session().outroDone,"intro succeeds once, outro allowance remains");
  check(ChapterManager.isAutoSkipActive(),"successful Auto action does not pause global Auto");
  controls=OkHttp.controls;
  for(long position:new long[]{80000,160000,0,50000,30000,79000}){
   ready();tick(position);
   check(OkHttp.controls==controls,"rewind and replay never repeat completed intro at "+position);
  }
  config(false);config(true);ready();tick(50000);
  check(OkHttp.controls==controls&&session().introDone,"Auto off then on retains completed intro");
  check(ChapterManager.clearManualSkipMarkers(this),"clear manual markers still available");
  ready();tick(50000);
  check(OkHttp.controls==controls&&session().introDone,"clear manual markers does not reset Auto allowance");
  late.introStartMs=20000;late.introEndMs=85000;
  call("applyAutoMarkers",this,media,late);ready();tick(50000);
  check(OkHttp.controls==controls&&session().introDone,"provider refresh does not rearm completed intro");
  ChapterManager.saveData(this,media,automatic);
  ready();tick(275000);
  check(OkHttp.controls==controls+1&&session().introDone&&session().outroDone,"outro has its own first execution after intro");
  controls=OkHttp.controls;
  for(long position:new long[]{275000,100000,50000,280000,299000}){
   ready();tick(position);
   check(OkHttp.controls==controls,"neither completed side repeats at "+position);
  }
  config(false);config(true);ready();tick(275000);
  check(OkHttp.controls==controls&&session().outroDone,"Auto toggle also retains completed outro");

  // Every manual seek size leaves Auto active and never rearms a completed side.
  for(long target:new long[]{0,9000,9001,50000,180000,275000}){
   check(ChapterManager.seekToManual(target),"manual seek works at "+target);
   check(ChapterManager.isAutoSkipActive()&&session().introDone&&session().outroDone,
     "manual seek has no pause and retains once flags at "+target);
  }
  controls=OkHttp.controls;ready();tick(50000);
  check(OkHttp.controls==controls,"manual chapter seek cannot cause repeated Auto intro");

  // Reenter the same episode: both flags are renewed, not loaded from ChapterData.
  ChapterManager.SkipSession oldSession=session();
  replay();tick(150000);ready();
  check(session()!=oldSession&&!session().introDone&&!session().outroDone,"same episode reentry renews both allowances");
  check(data().introEndMs==80000&&data().outroStartMs==270000,"reentry retains provider markers");
  check(ChapterManager.seekToManual(50000)&&ChapterManager.isAutoSkipActive(),"manual seek before first Auto never pauses");
  controls=OkHttp.controls;tick(50000);
  check(OkHttp.controls==controls+1&&session().introDone,"unused intro still executes after manual seek");
  ready();check(ChapterManager.seekToManual(275000),"manual jump to unused outro works");
  controls=OkHttp.controls;tick(275000);
  check(OkHttp.controls==controls+1&&session().outroDone,"unused outro still executes after manual seek");

  // Watching from near the end must not consume the intro allowance.
  replay();tick(150000);ready();controls=OkHttp.controls;tick(275000);
  check(OkHttp.controls==controls+1&&session().outroDone&&!session().introDone,"outro first leaves intro unused");
  ready();tick(50000);
  check(OkHttp.controls==controls+2&&session().introDone,"intro may execute once after outro");
  ready();tick(275000);
  check(OkHttp.controls==controls+2,"intro execution cannot rearm outro");

  // Failed controls are retryable; successful ones are never repeated.
  replay();tick(150000);ready();OkHttp.controlSuccess=false;controls=OkHttp.controls;tick(50000);
  check(OkHttp.controls==controls+1&&!session().introDone,"failed intro does not consume allowance");
  tick(50000);check(OkHttp.controls==controls+1,"failed intro retry is throttled for three seconds");
  OkHttp.controlSuccess=true;ready();tick(50000);
  check(OkHttp.controls==controls+2&&session().introDone,"intro succeeds after retry window");
  ready();OkHttp.controlSuccess=false;controls=OkHttp.controls;tick(275000);
  check(OkHttp.controls==controls+2&&!session().outroDone,"failed next and seek fallback preserve outro allowance");
  tick(275000);check(OkHttp.controls==controls+2,"failed outro retry is throttled");
  OkHttp.controlSuccess=true;ready();tick(275000);
  check(OkHttp.controls==controls+3&&session().outroDone,"outro succeeds after retry window");

  // A late completion belongs to its old playback and cannot consume a new one.
  replay();tick(150000);ready();oldSession=session();
  OkHttp.onControl=()->{try{replay();}catch(Exception e){throw new RuntimeException(e);}};
  tick(50000);
  check(oldSession.introDone&&!session().introDone,"late successful intro cannot mark new playback done");
  check(!FongMiNativeHelper.seekToAutomatically(oldSession,80000)
    &&!FongMiNativeHelper.nextEpisodeAutomatically(oldSession),"stale session rejected on main thread");
  tick(150000);ready();oldSession=session();
  OkHttp.onControl=()->{try{replay();}catch(Exception e){throw new RuntimeException(e);}};
  tick(275000);
  check(oldSession.outroDone&&!session().outroDone,"late successful outro cannot mark new playback done");

  // Exit, episode/source changes reset; temporary metadata fallback does not.
  tick(150000);ready();tick(50000);ready();tick(275000);
  ChapterManager.SkipSession completed=session();
  ChapterManager.MediaInfo fallback=ChapterManager.fromPlaybackValues("Fixture","E01","mediasession://current",50000,300000);
  call("observePlaybackSession",this,fallback);
  check(session()==completed&&session().introDone&&session().outroDone,"metadata fallback retains both completed sides");
  call("observePlaybackSession",this,media);
  check(session()==completed,"real metadata recovery is not a replay");
  ChapterManager.onPlaybackStopped(this);
  check(ChapterManager.getCurrentActivity()==null&&!ChapterManager.isAutoSkipActive(),"exit stops Auto and releases activity");
  check(!session().introDone&&!session().outroDone,"exit clears both flags");
  field(ChapterManager.class,"activityRef",new WeakReference<Activity>(this));
  call("observePlaybackSession",this,media);
  check(ChapterManager.isAutoSkipActive(),"reenter preserves global enabled choice");
  session().introDone=true;session().outroDone=true;
  ChapterManager.MediaInfo next=ChapterManager.fromPlaybackValues("Fixture","E02","http://next",50000,300000);
  call("observePlaybackSession",this,next);
  check(!session().introDone&&!session().outroDone,"new episode resets both flags");
  session().introDone=true;session().outroDone=true;
  ChapterManager.MediaInfo changedSource=ChapterManager.fromPlaybackValues("Fixture","E02","http://replay",0,300000);
  call("observePlaybackSession",this,changedSource);
  check(!session().introDone&&!session().outroDone,"new real source resets both flags");
  config(false);ChapterManager.onPlaybackStopped(this);
  field(ChapterManager.class,"activityRef",new WeakReference<Activity>(this));
  check(!ChapterManager.isAutoSkipActive(),"exit and reentry preserve global off choice");
  config(true);
  main(()->{
   LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setFocusableInTouchMode(true);rows.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);setContentView(rows);
   for(int i=0;i<2;i++){TextView row=new TextView(this);row.setText("action"+i);row.setFocusableInTouchMode(true);row.setClickable(true);rows.addView(row,new LinearLayout.LayoutParams(400,100));}
  });
  Thread.sleep(350);
  main(()->{
   LinearLayout rows=(LinearLayout)((android.view.ViewGroup)findViewById(android.R.id.content)).getChildAt(0);
   rows.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);rows.requestFocus();
   check(rows.findFocus()==rows,"reproduced inert list container holding focus");
   rows.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);ChapterDialogFocus.restoreNow(rows);
   check(rows.findFocus()==rows.getChildAt(0),"focus recovers from inert container to first action");
   rows.getChildAt(1).requestFocus();ChapterDialogFocus.restoreNow(rows);
   check(rows.findFocus()==rows.getChildAt(1),"existing actionable focus retained");
   rows.removeAllViews();TextView row=new TextView(this);row.setText("Auto off");row.setFocusableInTouchMode(true);row.setClickable(true);rows.addView(row,new LinearLayout.LayoutParams(400,100));ChapterDialogFocus.restore(rows);
  });
  Thread.sleep(400);
  main(()->{LinearLayout rows=(LinearLayout)((android.view.ViewGroup)findViewById(android.R.id.content)).getChildAt(0);check(rows.findFocus()==rows.getChildAt(0),"actual toggle-style rebuild retains actionable focus");});
  HostLoadingSmoke.run(this);
 }
}
