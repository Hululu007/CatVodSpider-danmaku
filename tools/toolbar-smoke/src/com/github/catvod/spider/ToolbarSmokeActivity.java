package com.github.catvod.spider;
import android.app.Activity;import android.os.*;import android.view.*;import android.widget.*;import android.util.Log;import java.lang.reflect.*;import java.util.*;
public class ToolbarSmokeActivity extends Activity {
 Handler handler=new Handler(Looper.getMainLooper());int target=1,checks=0,clicks=0,longs=0,originalKeys=0;FrameLayout root;ViewGroup row;ViewGroup control;View timeline;HorizontalScrollView scroll;Map<String,View> nodes;Object session;Class<?> sessionClass;Throwable failure;
 View.OnKeyListener originalTimelineKeys=(v,k,e)->{originalKeys++;return false;};
 public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);try{verifyMode();runTarget();}catch(Throwable t){fail(t);}}
 void verifyMode(){
  android.content.SharedPreferences prefs=getSharedPreferences("danmaku_config",0),local=getSharedPreferences("danmaku_toolbar_ui",0);
  prefs.edit().clear().commit();local.edit().clear().commit();
  check("custom".equals(new DanmakuConfig().getPlayerToolbarMode()),"fresh default custom");
  check("custom".equals(PlayerToolbarController.mode(this)),"fresh install mode custom");
  prefs.edit().clear().putString("config_json","{\"playerToolbarMode\":\"legacy\",\"autoSkipEnabled\":false,\"lpWidth\":0.77}").putBoolean("auto_enabled_migrated_v2",true).commit();
  DanmakuConfig migrated=DanmakuConfigManager.loadConfig(this);
  check("custom".equals(migrated.getPlayerToolbarMode()),"old default migrates custom");check(!migrated.isAutoSkipEnabled()&&Math.abs(migrated.getLpWidth()-.77f)<.001,"unrelated settings preserved");
  migrated.setPlayerToolbarMode("legacy");DanmakuConfigManager.saveConfig(this,migrated);check("legacy".equals(DanmakuConfigManager.loadConfig(this).getPlayerToolbarMode()),"migration one time only");
  local.edit().putString("mode","legacy").commit();migrated.setPlayerToolbarMode("custom");DanmakuConfigManager.saveConfig(this,migrated);check("legacy".equals(PlayerToolbarController.mode(this)),"local rollback overrides config");
  local.edit().clear().commit();prefs.edit().clear().putString("config_json","{\"playerToolbarMode\":\"diagnose\"}").commit();check("diagnose".equals(DanmakuConfigManager.loadConfig(this).getPlayerToolbarMode()),"explicit diagnosis preserved");
  prefs.edit().clear().commit();local.edit().clear().commit();
 }
 int id(String n){return getResources().getIdentifier(n,"id",getPackageName());}
 void check(boolean v,String reason){if(!v)throw new AssertionError("T"+target+": "+reason);checks++;}
 View button(String name){CustomText v=new CustomText(this);v.setId(id(name));v.setText(name);v.setFocusable(true);v.setFocusableInTouchMode(target==1);v.setOnClickListener(w->{clicks++;});v.setOnLongClickListener(w->{longs++;return true;});if(name.equals("text"))v.setTag("3");if(name.equals("audio"))v.setTag("1");if(name.equals("video"))v.setTag("2");return v;}
 void add(String name){View b=button(name);row.addView(b,new LinearLayout.LayoutParams(90,70));nodes.put(name,b);}
 ClassLoader hostLoader;java.util.Map<View,ViewGroup.LayoutParams> originalParams;
 ViewGroup linearContainer()throws Exception{
  if(target!=3)return new LinearLayout(this);
  if(hostLoader==null){java.io.File apk=new java.io.File(getFilesDir(),"host-t3.apk");try(java.io.InputStream in=getAssets().open("host-t3.apk");java.io.FileOutputStream out=new java.io.FileOutputStream(apk)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}hostLoader=new dalvik.system.DexClassLoader(apk.getPath(),getCodeCacheDir().getPath(),null,getClassLoader());}
  return (ViewGroup)hostLoader.loadClass("androidx.appcompat.widget.LinearLayoutCompat").getConstructor(android.content.Context.class).newInstance(this);
 }
 void runTarget(){try{
  nodes=new LinkedHashMap<>();root=new FrameLayout(this);root.setBackgroundColor(0xff233447);setContentView(root);row=linearContainer();row.setMinimumWidth(2500);ToolbarHostMap.linearProperty(row,"setOrientation",LinearLayout.HORIZONTAL);scroll=new HorizontalScrollView(this);scroll.setFillViewport(true);scroll.addView(row);
  if(target==1){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);control=c;row.setId(id("actionLayout"));c.addView(scroll);root.addView(c,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));}
  else{RelativeLayout c=new RelativeLayout(this);control=c;ViewGroup bottom=linearContainer();ToolbarHostMap.linearProperty(bottom,"setOrientation",LinearLayout.VERTICAL);bottom.setId(id("bottom"));scroll.setId(id("action"));bottom.addView(scroll);RelativeLayout.LayoutParams lp=new RelativeLayout.LayoutParams(-1,-2);lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);c.addView(bottom,lp);for(String n:new String[]{"prev","next","danmaku"}){View b=button(n);RelativeLayout.LayoutParams bp=new RelativeLayout.LayoutParams(80,60);bp.leftMargin=n.equals("prev")?0:n.equals("next")?100:200;c.addView(b,bp);}root.addView(c,new FrameLayout.LayoutParams(-1,-1));}
  control.setId(id("control"));
  RelativeLayout display=new RelativeLayout(this);display.setId(id("display"));LinearLayout titleLayout=new LinearLayout(this);titleLayout.setId(id("titleLayout"));display.addView(titleLayout,new RelativeLayout.LayoutParams(200,40));TextView clock=new TextView(this);clock.setId(id("clock"));clock.setText("12:34");display.addView(clock,new RelativeLayout.LayoutParams(100,40));root.addView(display,new FrameLayout.LayoutParams(-1,50,Gravity.TOP));FrameLayout widget=new FrameLayout(this);widget.setId(id("widget"));LinearLayout top=new LinearLayout(this);top.setId(id("top"));widget.addView(top,new FrameLayout.LayoutParams(-1,40));root.addView(widget,new FrameLayout.LayoutParams(-1,50,Gravity.TOP));
  FrameLayout seekContainer=new FrameLayout(this);timeline=seekContainer;timeline.setId(id("seek"));
  SeekBar timeBar=new SeekBar(this);timeBar.setId(id("timeBar"));timeBar.setFocusableInTouchMode(true);timeBar.setNextFocusUpId(id("next"));timeBar.setNextFocusDownId(id("timeBar"));timeBar.setNextFocusLeftId(id("timeBar"));timeBar.setNextFocusRightId(id("timeBar"));seekContainer.addView(timeBar,new FrameLayout.LayoutParams(-1,-1));
  timeBar.setProgress(50);timeBar.setOnKeyListener(originalTimelineKeys);
  ViewGroup bottom=(ViewGroup)scroll.getParent();
  bottom.addView(timeline,target==1?bottom.getChildCount():0,new LinearLayout.LayoutParams(-1,60));
  for(String n:new String[]{"player","decode","scale","speed","episodes","text","audio","video","opening","ending","reset"})add(n);
  add(target==1?"danmu":"danmaku");add(target==3?"repeat":"loop");add(target==3?"chapter":"title");if(target==1){add("prev");add("next");}if(target==3){add("replay");add("parse");add("edition");}
  if(target==1)nodes.get("episodes").setVisibility(View.GONE);
  handler.postDelayed(()->guard(this::install),200);
 }catch(Throwable t){fail(t);}}
 void install()throws Exception{
  originalParams=new java.util.IdentityHashMap<>();originalParams.put(scroll,scroll.getLayoutParams());for(View v:nodes.values())originalParams.put(v,v.getLayoutParams());
  if(target==3){check(!(row instanceof LinearLayout),"real T3 compat row is not platform LinearLayout");check(row.getClass().getName().equals("androidx.appcompat.widget.LinearLayoutCompat"),"real T3 APK class loaded");}
  ToolbarHostMap m=ToolbarHostMap.resolveTree(root,target==3?556:516);check(m!=null,"mapping found");check(m.target.equals("T"+target),"target");check(m.actions.get("danmaku")==nodes.get(target==1?"danmu":"danmaku"),"scoped duplicate danmaku");check(ToolbarHostMap.resolveTree(root,999)==null,"unknown version");
  sessionClass=Class.forName("com.github.catvod.spider.PlayerToolbarController$Session");Constructor<?> ctor=sessionClass.getDeclaredConstructor(Activity.class,ToolbarHostMap.class);ctor.setAccessible(true);session=ctor.newInstance(this,m);call("install");handler.postDelayed(()->guard(()->verifyTimelineKey(0)),250);
 }
 void verifyTimelineKey(int index)throws Exception{
  if(target!=1||index==4){if(target==1)nodes.get("next").requestFocus();verify();return;}
  View bar=findViewById(id("timeBar"));check(bar.requestFocus(),"timeline accepts remote focus");
  int code=index==0?KeyEvent.KEYCODE_DPAD_UP:index==1?KeyEvent.KEYCODE_DPAD_DOWN:KeyEvent.KEYCODE_DPAD_CENTER;
  int toggles=FongMiNativeHelper.toggles;
  new Thread(()->{try{new android.app.Instrumentation().sendKeyDownUpSync(code);handler.post(()->guard(()->{Map<String,TextView> entries=(Map<String,TextView>)field("entry");if(index<2)check(entries.get("next").hasFocus(),"injected timeline key returns next "+code);else{check(FongMiNativeHelper.toggles==toggles+1,"injected OK toggles exactly once");check(bar.hasFocus(),"injected OK keeps timeline focus");}verifyTimelineKey(index+1);}));}catch(Throwable error){handler.post(()->fail(error));}}).start();
 }
 void verifyTimelineConfirm()throws Exception{
  SeekBar bar=findViewById(id("timeBar"));bar.requestFocus();int toggles=FongMiNativeHelper.toggles,keys=originalKeys;
  int progress=bar.getProgress();bar.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));check(bar.getProgress()>progress,"right still seeks forward");
  bar.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));check(bar.getProgress()==progress,"left still seeks backward");check(originalKeys==keys+2,"arrows reach original listener");
  for(int code:new int[]{KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER}){
   long now=SystemClock.uptimeMillis();int before=FongMiNativeHelper.toggles;
   bar.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0));
   bar.dispatchKeyEvent(new KeyEvent(now,now+10,KeyEvent.ACTION_DOWN,code,1));check(FongMiNativeHelper.toggles==before,"hold does not repeatedly toggle");
   bar.dispatchKeyEvent(new KeyEvent(now,now+20,KeyEvent.ACTION_UP,code,0));check(FongMiNativeHelper.toggles==before+(target==1?1:0),"confirm toggles TV only");
  }
  if(target==1){
   long now=SystemClock.uptimeMillis();int before=FongMiNativeHelper.toggles;
   bar.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,23,0));
   bar.dispatchKeyEvent(new KeyEvent(now,now+20,KeyEvent.ACTION_UP,23,0,0,0,0,KeyEvent.FLAG_CANCELED));check(FongMiNativeHelper.toggles==before,"canceled OK does not toggle");
   bar.dispatchKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,23,0));nodes.get("next").requestFocus();bar.requestFocus();
   bar.dispatchKeyEvent(new KeyEvent(now,now+20,KeyEvent.ACTION_UP,23,0));check(FongMiNativeHelper.toggles==before,"focus loss cancels pending OK");
  }else check(FongMiNativeHelper.toggles==toggles,"touch host playback unchanged");
 }
 Object call(String name,Object...args)throws Exception{for(Method method:sessionClass.getDeclaredMethods()){if(method.getName().equals(name)&&method.getParameterTypes().length==args.length){method.setAccessible(true);try{return method.invoke(session,args);}catch(InvocationTargetException e){throw new RuntimeException(name,e.getCause());}}}throw new NoSuchMethodException(name);}
 Object field(String n)throws Exception{Field f=sessionClass.getDeclaredField(n);f.setAccessible(true);return f.get(session);}
 void verify()throws Exception{
  check((Boolean)call("valid"),"session valid");Map<String,TextView> entries=(Map<String,TextView>)field("entry");check(entries.size()==10,"ten entries");check(entries.get("speed")==nodes.get("speed"),"speed identity");check(entries.get("text")==nodes.get("text"),"subtitle identity");check("3".equals(nodes.get("text").getTag()),"subtitle tag");check(nodes.get("speed").isFocusableInTouchMode()==(target==1),"touch focus preserved");
  check(entries.get("scale")==nodes.get("scale"),"native scale identity");check(row.indexOfChild(nodes.get("scale"))==row.indexOfChild(entries.get("chapters"))+1,"scale follows chapters");
  if(target==1){View nativeEpisodes=nodes.get("episodes"),entryEpisodes=entries.get("episodes");check(nativeEpisodes!=entryEpisodes&&nativeEpisodes.getVisibility()==View.GONE,"hidden native selector state preserved");check(entryEpisodes.isShown(),"TV episodes entry always visible");int count=clicks;entryEpisodes.performClick();check(clicks==count+1,"TV selector invokes original hidden native action");nativeEpisodes.setEnabled(false);call("refresh");check(!entryEpisodes.isEnabled(),"TV selector follows native disabled state");nativeEpisodes.setEnabled(true);call("refresh");nativeEpisodes.setVisibility(View.INVISIBLE);call("refresh");check(entryEpisodes.isShown(),"host rehide cannot remove TV selector");nativeEpisodes.setVisibility(View.GONE);}else check(entries.get("episodes")==nodes.get("episodes"),"touch selector retains native identity");
  call("fit");layoutNow();check(y(timeline)+timeline.getHeight()<=y(scroll),"timeline above toolbar on every target");verifyShell();
  nodes.get("text").setVisibility(View.GONE);call("fit");layoutNow();check(visibleEntries(entries)==9,"exactly nine visible entries");verifyShell();
  nodes.get("text").setVisibility(View.INVISIBLE);call("fit");layoutNow();check(nodes.get("text").getWidth()==0,"invisible entry leaves no empty slot");verifyShell();
  nodes.get("episodes").setVisibility(View.GONE);nodes.get("text").setVisibility(View.GONE);call("fit");layoutNow();verifyShell();
  nodes.get("episodes").setVisibility(View.VISIBLE);nodes.get("text").setVisibility(View.VISIBLE);call("fit");layoutNow();verifyShell();
  if(target==1){call("restoreToolbarEntryFocus",field("toolbarFocusGeneration"));check(entries.get("next").hasFocus(),"initial TV focus on next episode");entries.get("danmaku").requestFocus();scroll.setVisibility(View.GONE);call("refresh");layoutNow();scroll.setVisibility(View.VISIBLE);entries.get("playback").requestFocus();call("refresh");layoutNow();call("restoreToolbarEntryFocus",field("toolbarFocusGeneration"));check(entries.get("next").hasFocus(),"toolbar recall focuses next episode");}
  View timeBar=findViewById(id("timeBar"));if(target==1){for(int direction:new int[]{View.FOCUS_UP,View.FOCUS_DOWN})check(timeBar.focusSearch(direction)==entries.get("next"),"timeline up/down route returns toolbar");check(timeBar.getNextFocusLeftId()==id("timeBar")&&timeBar.getNextFocusRightId()==id("timeBar"),"timeline left/right route preserved");}else check(timeBar.getNextFocusDownId()==id("timeBar"),"touch timeline focus unchanged");
  verifyTimelineConfirm();
  CustomText speed=(CustomText)nodes.get("speed");speed.setText("1.75");call("refresh");check(speed.getText().toString().equals("1.75"),"dynamic speed kept");int c=clicks;speed.performClick();check(clicks==c+1,"original click");int l=longs;speed.performLongClick();check(longs==l+1,"original long click");speed.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));check(speed.ups==1,"custom up listener");
  View from=entries.get("playback");call("togglePanel","playback",from);check(nodes.get("opening").getParent()!=row,"marker moved to panel");check(nodes.get("opening").hasOnClickListeners(),"marker listener");c=clicks;nodes.get("opening").performClick();check(clicks==c+1,"marker click");l=longs;nodes.get("ending").performLongClick();check(longs==l+1,"marker long click");
  View clear=findText((View)field("panelBody"),"清除头尾");
  check(clear!=null,"manual clear button exists");
  int clearCount=ChapterManager.clears;clear.performClick();check(ChapterManager.clears==clearCount+1,"clear delegates without marker clicks");
  LinearLayout pb=(LinearLayout)field("panelBody");java.util.List<View> ordered=new java.util.ArrayList<>();for(int ri=0;ri<pb.getChildCount();ri++){ViewGroup gr=(ViewGroup)pb.getChildAt(ri);for(int ci=0;ci<gr.getChildCount();ci++){ViewGroup cell=(ViewGroup)gr.getChildAt(ci);if(cell.getChildCount()>0)ordered.add(cell.getChildAt(0));}}
  check(ordered.indexOf(clear)==ordered.indexOf(nodes.get("ending"))+1,"clear immediately after native ending");
  View nativeChapter=nodes.get(target==3?"chapter":"title");nativeChapter.setEnabled(false);call("refresh");check(!nativeChapter.isShown()&&findText((View)field("panelBody"),"宿主章节\n不可用")==null,"disabled chapter hidden without placeholder");
  nativeChapter.setEnabled(true);call("refresh");check(nativeChapter.isShown(),"available chapter restored");
  nativeChapter.setVisibility(View.GONE);call("refresh");check(!((java.util.List)field("panelNative")).contains(nativeChapter),"hidden native chapter has no grid slot");
  nativeChapter.setVisibility(View.VISIBLE);call("refresh");check(nativeChapter.isShown(),"native chapter reappears dynamically");
  check(nodes.get("scale").getParent()==row,"scale excluded from settings");
  call("closePanel",false);layoutNow();int fixedY=y(timeline),fixedControlHeight=control.getHeight();
  for(String key:new String[]{"playback","danmaku","tracks"}) {
   call("togglePanel",key,entries.get(key));layoutNow();call("fit");layoutNow();
   check(y(timeline)==fixedY,"timeline position fixed for "+key);check(control.getHeight()==fixedControlHeight,"host control height fixed for "+key);
   View panel=(View)field("panel");check(panel.getHeight()>0&&y(panel)+panel.getHeight()<=y((View)scroll.getParent()),"overlay above original bottom for "+key);
   LinearLayout body=(LinearLayout)field("panelBody");for(int i=0;i<body.getChildCount();i++){ViewGroup grid=(ViewGroup)body.getChildAt(i);check(grid.getChildCount()==(key.equals("tracks")?2:4),"content column count "+key);}
   if(key.equals("tracks")){int padding=panel.getPaddingLeft()+panel.getPaddingRight();int full=ToolbarTheme.dp(this,target==1?420:390);check(panel.getWidth()==padding+(full-padding)/2,"two track buttons shrink outer frame");ViewGroup grid=(ViewGroup)body.getChildAt(0);check(grid.getChildCount()==2&&x(grid.getChildAt(1))+grid.getChildAt(1).getWidth()<=x(panel)+panel.getWidth()-panel.getPaddingRight(),"track buttons fit compact frame");}
   if(key.equals("danmaku")) {
    View search=findText(body,"弹幕搜索");ViewGroup firstRow=(ViewGroup)body.getChildAt(0);
    check(((ViewGroup)firstRow.getChildAt(0)).getChildAt(0)==search,"search first slot");check(((ViewGroup)firstRow.getChildAt(1)).getChildAt(0)==nodes.get(target==1?"danmu":"danmaku"),"native danmaku second slot");
    call("focusPanelContent",field("panelGeneration"));check(search.hasFocus(),"focus enters first search button");
   }
   for(View f:body.getFocusables(View.FOCUS_FORWARD))for(int direction:new int[]{View.FOCUS_LEFT,View.FOCUS_RIGHT,View.FOCUS_UP,View.FOCUS_DOWN}){View dest=((ViewGroup)panel).focusSearch(f,direction);check(dest==null||inside(dest,panel),"directional focus trapped "+key);}
   for(View f:body.getFocusables(View.FOCUS_FORWARD))if(f.requestFocus())for(int code:new int[]{KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_TAB}){panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));check(panel.hasFocus()&&inside(getCurrentFocus(),panel),"dispatched key stays in panel "+key);}
   if(key.equals("playback")){CustomText player=(CustomText)nodes.get("player");if(player.requestFocus()){int ups=player.ups;panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP));check(player.ups==ups,"panel contains native up callback");}}
   if(key.equals("playback")){check(y(nodes.get("player"))==y(nodes.get("reset")),"first row aligned");check(nodes.get("player").getWidth()<panel.getWidth()/3,"compact button width");}
   if(key.equals("tracks")){Log.i("ToolbarSmoke","tracks active="+field("activePanel")+" panel="+panel.getVisibility()+" nativeCount="+((List)field("panelNative")).size()+" audio="+ancestry(nodes.get("audio"))+" video="+ancestry(nodes.get("video")));check(nodes.get("audio").isShown()&&nodes.get("video").isShown(),"both track buttons directly visible");c=clicks;nodes.get("audio").performClick();nodes.get("video").performClick();check(clicks==c+2,"direct native selectors");}
   panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));panel.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));check(field("activePanel")==null,"Back closes panel "+key);layoutNow();check(y(timeline)==fixedY,"timeline fixed after close "+key);if(target==1)check(entries.get(key).hasFocus(),"focus returns to "+key);
  }
  int qr=DanmakuUIHelper.qrCount;entries.get("danmaku").performLongClick();check(DanmakuUIHelper.qrCount==qr+1,"main danmaku long press QR");
  call("togglePanel","danmaku",entries.get("danmaku"));View search=findText((View)field("panelBody"),"弹幕搜索");check(search!=null,"search entry");search.performLongClick();check(DanmakuUIHelper.qrCount==qr+2&&"http://127.0.0.1:9810".equals(DanmakuUIHelper.qrUrl),"search long press original QR");check(field("activePanel")==null,"QR closes custom panel");
  call("closePanel",false);call("togglePanel","tracks",entries.get("tracks"));check("1".equals(nodes.get("audio").getTag()),"audio tag");call("closePanel",false);
  call("togglePanel","danmaku",entries.get("danmaku"));int searches=DanmakuUIHelper.searchCount;findText((View)field("panelBody"),"弹幕搜索").performClick();check(DanmakuUIHelper.searchCount==searches+1&&DanmakuUIHelper.hiddenBeforeSearch,"toolbar hidden before original search opens");check(field("activePanel")==null&&!scroll.isShown(),"search hides panel and toolbar");control.setVisibility(View.VISIBLE);call("refresh");layoutNow();check(scroll.isShown(),"host can reveal toolbar after search");
  scroll.setVisibility(View.GONE);call("refresh");check(!row.isShown(),"detail/hidden visibility");scroll.setVisibility(View.VISIBLE);
  for(int i=0;i<20;i++){call("togglePanel","playback",from);call("closePanel",false);}check((Boolean)call("valid"),"repeated panels stable");
  verifyIdleReset();
 }
 int idleResets; Runnable hideForIdle;
 void verifyIdleReset()throws Exception{
  if(target==1){check(((Map)field("interactionHooks")).isEmpty(),"TV listeners unchanged by touch fix");verifySearchDialog(0);return;}
  Field reset=sessionClass.getDeclaredField("hostIdleReset");reset.setAccessible(true);
  hideForIdle=()->{control.setVisibility(View.GONE);guard(()->call("refresh"));};
  reset.set(session,(Runnable)()->{idleResets++;handler.removeCallbacks(hideForIdle);handler.postDelayed(hideForIdle,1200);});
  setHostChromeVisible();call("refresh");layoutNow();
  Map<String,TextView> entries=(Map<String,TextView>)field("entry");
  handler.postDelayed(hideForIdle,100);entries.get("playback").performClick();
  handler.postDelayed(()->guard(()->{
   check(control.isShown()&&"playback".equals(field("activePanel")),"near-expiry click cancels old hide task");
   int before=idleResets;call("refresh");layoutNow();check(idleResets==before,"refresh/layout do not renew idle deadline");
   int c=clicks;nodes.get("opening").performClick();check(clicks==c+1&&idleResets==before+1,"native panel click renews once and delegates once");
   before=idleResets;int l=longs;nodes.get("ending").performLongClick();check(longs==l+1&&idleResets==before+1,"native long press renews once and delegates once");
   before=idleResets;findText((View)field("panelBody"),"清除头尾").performClick();check(idleResets==before+1,"custom panel click renews idle");
   View panel=(View)field("panel");long now=SystemClock.uptimeMillis();
   for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_CANCEL}){MotionEvent e=MotionEvent.obtain(now,now,action,4,4,0);panel.dispatchTouchEvent(e);e.recycle();}
   check(idleResets>=before+3,"panel touch and canceled scroll renew idle");
   call("closePanel",true);before=idleResets;c=clicks;nodes.get("speed").performClick();check(clicks==c+1&&idleResets==before+1,"native main button renews idle");
   entries.get("playback").performClick();
   handler.postDelayed(()->guard(()->{
    check(!control.isShown()&&field("activePanel")==null,"no input eventually hides toolbar and panel");
    int count=idleResets;call("resetHostIdle");check(idleResets==count&&!control.isShown(),"hidden toolbar cannot restart timer or reveal itself");
    setHostChromeVisible();call("refresh");layoutNow();call("togglePanel","danmaku",entries.get("danmaku"));findText((View)field("panelBody"),"弹幕搜索").performClick();check(!control.isShown(),"explicit search hide wins over reset");
    handler.removeCallbacks(hideForIdle);reset.set(session,null);verifySearchDialog(0);
   }),1400);
  }),300);
 }
 void setHostChromeVisible(){control.setVisibility(View.VISIBLE);for(String name:new String[]{"top","titleLayout","clock"})findViewById(id(name)).setVisibility(View.VISIBLE);}
 void checkHostChromeHidden(String path){check(!control.isShown(),path+" hides control");for(String name:new String[]{"top","titleLayout","clock"})check(!findViewById(id(name)).isShown(),path+" hides "+name);}
 void verifySearchDialog(int path)throws Exception{
  if(path==2){verifyRestore();return;}
  setHostChromeVisible();call("refresh");layoutNow();DanmakuUIHelper.realSearch=true;call("openDanmakuSearch");checkHostChromeHidden("search open");
  handler.postDelayed(()->guard(()->{check(!hasWindowFocus(),"real search owns window focus");setHostChromeVisible();if(path==0)DanmakuUIHelper.searchDialog.getButton(-1).performClick();else DanmakuUIHelper.searchDialog.cancel();handler.postDelayed(()->guard(()->{check(hasWindowFocus(),"host regains focus after search");checkHostChromeHidden(path==0?"selection closes search":"cancel closes search");check(!(Boolean)field("searchAwaitingReturn")&&field("searchReturnDraw")==null,"search return cleanup finishes before next input");setHostChromeVisible();call("refresh");layoutNow();check(control.isShown()&&findViewById(id("top")).isShown()&&findViewById(id("clock")).isShown(),"later user reveal remains available");verifySearchDialog(path+1);}),150);}),100);
 }
 void verifyRestore()throws Exception{
  DanmakuUIHelper.realSearch=false;
  CustomText speed=(CustomText)nodes.get("speed");View timeBar=findViewById(id("timeBar"));
  call("restore");layoutNow();check(row.getChildCount()==nodes.size(),"original child count restored");for(View node:nodes.values())check(node.getParent()==row,"original parent restored");check(speed.getText().toString().equals("1.75"),"restore preserves current business state");check(root.findViewWithTag("aurora_native_toolbar_v1")==null,"owner marker removed");check(scroll.isFillViewport()&&row.getMinimumWidth()==2500,"host original width policy restored");if(target==1)check(y(timeline)>=y(scroll)+scroll.getHeight(),"TV original timeline order restored");
  for(View v:originalParams.keySet())check(v.getLayoutParams()==originalParams.get(v),"restore original host LayoutParams identity");
  check(timeBar.getNextFocusUpId()==id("next")&&timeBar.getNextFocusDownId()==id("timeBar"),"original timeline focus restored");
  Method read=PlayerToolbarController.class.getDeclaredMethod("readKeyListener",View.class);read.setAccessible(true);check(read.invoke(null,timeBar)==originalTimelineKeys,"original key listener identity restored");
  Log.i("ToolbarSmoke","PASS T"+target+" checks="+checks);target++;if(target<=3)handler.postDelayed(this::runTarget,100);else{Log.i("ToolbarSmoke","ALL PASS checks="+checks);finish();}
 }
 void layoutNow(){int w=root.getWidth(),h=root.getHeight();root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}
 int y(View v){int[] xy=new int[2];v.getLocationOnScreen(xy);return xy[1];}
 int visibleEntries(Map<String,TextView> entries){int count=0;for(View v:entries.values())if(v.getVisibility()==View.VISIBLE)count++;return count;}
 int x(View v){int[] xy=new int[2];v.getLocationOnScreen(xy);return xy[0];}
 boolean inside(View v,View ancestor){while(v!=null){if(v==ancestor)return true;v=v.getParent() instanceof View?(View)v.getParent():null;}return false;}
 void verifyShell(){int expected=row.getPaddingLeft()+row.getPaddingRight();for(int i=0;i<row.getChildCount();i++){View v=row.getChildAt(i);if(v.getVisibility()==View.GONE)continue;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();expected+=v.getWidth()+p.leftMargin+p.rightMargin;}check(row.getWidth()==expected,"shell fits actual visible content");View parent=(View)scroll.getParent();check(Math.abs(2*x(scroll)+scroll.getWidth()-(2*x(parent)+parent.getWidth()))<=2,"shell centered");check(scroll.getWidth()<=parent.getWidth(),"shell within screen");}
 String ancestry(View v){String s="";while(v!=null){s+=v.getClass().getSimpleName()+":"+v.getVisibility()+"/"+v.isAttachedToWindow()+">";v=v.getParent() instanceof View?(View)v.getParent():null;}return s;}
 View findText(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=findText(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
 interface Test {void run()throws Exception;}
 void guard(Test test){try{test.run();}catch(Throwable t){fail(t);}}
 void fail(Throwable t){failure=t;Log.e("ToolbarSmoke","FAILED T"+target,t);finish();}
 static class CustomText extends TextView{int ups;CustomText(Activity a){super(a);}public boolean dispatchKeyEvent(KeyEvent e){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP){ups++;return true;}return super.dispatchKeyEvent(e);}}
}
