package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native Aurora navigation. Host controls keep their identity, listeners, tags and key dispatch.
 * No Window.Callback replacement, global input interceptor or independent hide timer.
 */
public final class PlayerToolbarController {
    public static final String BUILD = "aurora-toolbar-20261114-danmaku-selection-r2";
    private static final String OWNER_TAG = "aurora_native_toolbar_v1";
    private static final String PREFS = "danmaku_toolbar_ui";
    private static WeakReference<Activity> failedActivity = new WeakReference<>(null);
    private static Session current;

    private PlayerToolbarController() {}

    public static String mode(Context context) {
        SharedPreferences local = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String override = local.getString("mode", "");
        if ("legacy".equals(override) || "custom".equals(override)) return override;
        DanmakuConfig config = DanmakuConfigManager.getConfig(context);
        return config == null ? "custom" : config.getPlayerToolbarMode();
    }

    public static void setMode(Context context, String mode) {
        if (!"legacy".equals(mode) && !"custom".equals(mode)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode).apply();
        Init.run(() -> { failedActivity.clear(); if (current != null) release(current.activity); });
    }

    /** Returns true only when this controller owns the row (or another JAR already owns it). */
    static boolean sync(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("UI thread required");
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (!"custom".equals(mode(activity)) || PlayerToolbarProbe.isDiagnose(activity)) {
            release(activity);
            return false;
        }
        if (current != null && current.activity != activity) release(current.activity);
        if (current != null) {
            if (current.map.row.isAttachedToWindow() && current.valid()) {
                current.refresh();
                return true;
            }
            release(activity);
            failedActivity = new WeakReference<>(activity);
            return false;
        }
        if (failedActivity.get() == activity) return false;
        View existing = activity.getWindow().getDecorView().findViewWithTag(OWNER_TAG);
        if (existing != null) return true;
        ToolbarHostMap map = ToolbarHostMap.resolve(activity);
        if (map == null) return false;
        Session session = new Session(activity, map);
        try {
            session.install();
            current = session;
            Log.i("AuroraToolbar", BUILD + " bound " + map.target + " nodes=" + map.actions.keySet());
            return true;
        } catch (Throwable error) {
            session.restore();
            failedActivity = new WeakReference<>(activity);
            Log.e("AuroraToolbar", "Install rolled back: " + map.target, error);
            return false;
        }
    }

    static boolean owns(Context context) {
        return current != null && current.activity == context;
    }

    static boolean suppressLegacy(Activity activity) {
        return current != null && current.activity == activity
                || activity.getWindow().getDecorView().findViewWithTag(OWNER_TAG) != null;
    }

    static void release(Activity activity) {
        if (current == null || current.activity != activity) return;
        Session session = current;
        current = null;
        session.restore();
        Log.i("AuroraToolbar", "restored " + session.map.target);
    }

    static void onPause(Activity activity) {
        if (current != null && current.activity == activity) {
            current.dialogFocus.cancel();
            current.closePanel(false);
        }
    }

    static void onMediaChanged(Activity activity) {
        Init.run(() -> {
            if (current != null && current.activity == activity) {
                current.dialogFocus.cancel();
                current.closePanel(false);
            }
        });
    }

    static ViewGroup toolbarRow(Activity activity) {
        return current != null && current.activity == activity ? current.map.row : null;
    }

    static String status() {
        return current == null ? BUILD + " / 尚未绑定播放页" : BUILD + " / " + current.map.target;
    }

    private static final class Session {
        final Activity activity;
        final ToolbarHostMap map;
        final ToolbarDialogFocus dialogFocus;
        final Map<View, Snapshot> snapshots = new IdentityHashMap<>();
        final Map<String, TextView> entry = new LinkedHashMap<>();
        final List<View> managed = new ArrayList<>();
        final List<View> panelNative = new ArrayList<>();
        final Map<View, TextView> unavailableLabels = new IdentityHashMap<>();
        final Map<View, InteractionHooks> interactionHooks = new IdentityHashMap<>();
        Runnable hostIdleReset;
        long lastTouchMoveReset;
        final Map<TextView,android.text.TextWatcher> markerObservers = new IdentityHashMap<>();
        LinearLayout parking;
        Panel panel;
        FrameLayout panelHost;
        LinearLayout panelBody;
        LinearLayout gridRow;
        int gridCells;
        ScrollView panelScroll;
        TextView panelTitle;
        String activePanel;
        View source;
        ViewTreeObserver.OnGlobalFocusChangeListener focusObserver;
        ViewTreeObserver.OnWindowFocusChangeListener windowFocusObserver;
        View.OnLayoutChangeListener layoutObserver;
        ViewTreeObserver.OnGlobalLayoutListener visibilityObserver;
        View tvSeek;
        View tvTimeBar;
        TimelineConfirmKeyListener timelineKeys;
        View hostTop, hostTitleLayout, hostClock;
        boolean searchAwaitingReturn, searchWindowLost;
        ViewTreeObserver.OnPreDrawListener searchReturnDraw;
        int tvSeekIndex = -1;
        int panelGeneration;
        boolean wasShown;
        boolean toolbarFocusPending;
        int toolbarFocusGeneration;
        View lastEntryFocus;
        View lastPanelFocus;
        boolean restoring;
        boolean rendering;
        Boolean danmakuSelection;

        Session(Activity activity, ToolbarHostMap map) {
            this.activity = activity; this.map = map;
            dialogFocus = new ToolbarDialogFocus(activity, map.row,
                    () -> !restoring && activePanel == null && map.scroll.isShown()
                            && (map.television || !map.row.isInTouchMode()));
        }
        int dp(float value) { return ToolbarTheme.dp(activity, value); }

        void save(View view) { if (!snapshots.containsKey(view)) snapshots.put(view, new Snapshot(view)); }

        void install() {
            hostIdleReset = resolveHostIdleReset(activity, map.target);
            save(map.row); save(map.scroll);
            moveTvTimelineAbove();
            for (View child : map.originalChildren) save(child);
            // Remove old plugin buttons, not host slots or their listeners.
            for (View child : map.originalChildren) detach(child);
            parking = new LinearLayout(activity);
            parking.setVisibility(View.GONE);
            parking.setTag(OWNER_TAG);
            map.row.addView(parking, new LinearLayout.LayoutParams(0, 0));
            for (View child : map.originalChildren) parking.addView(child);
            ToolbarHostMap.linearProperty(map.row,"setOrientation",LinearLayout.HORIZONTAL);
            ToolbarHostMap.linearProperty(map.row,"setGravity",Gravity.CENTER_VERTICAL);
            map.row.setPadding(dp(12), dp(8), dp(12), dp(8));
            map.row.setBackground(ToolbarTheme.glass(activity, false));
            map.row.setElevation(dp(8));
            map.row.setClipToPadding(false);
            map.row.setClipChildren(false);
            map.row.setMinimumWidth(0);
            FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
            map.row.setLayoutParams(rowParams);
            map.scroll.setHorizontalScrollBarEnabled(false);
            map.scroll.setFillViewport(false);
            map.scroll.setMinimumWidth(0);
            map.scroll.setPadding(0,0,0,0);
            map.scroll.setBackground(null);
            ViewGroup.LayoutParams scrollParams = map.scroll.getLayoutParams();
            scrollParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            map.scroll.setLayoutParams(scrollParams);

            addPrimary("prev", "上一集", "prev");
            addPrimary("next", "下一集", "next");
            addEpisodesEntry();
            TextView playback = addEntry("playback", "播放设置", "playback");
            playback.setOnClickListener(v -> togglePanel("playback", v));
            TextView danmaku = addEntry("danmaku", "弹幕", "danmaku");
            danmaku.setOnClickListener(v -> togglePanel("danmaku", v));
            danmaku.setOnLongClickListener(v -> showRemoteQr());
            addPrimary("text", "字幕", "subtitle");
            TextView tracks = addEntry("tracks", "音视频", "tracks");
            tracks.setOnClickListener(v -> togglePanel("tracks", v));
            addPrimary("speed", "倍速", null);
            TextView chapters = addEntry("chapters", "章节", "chapters");
            chapters.setOnClickListener(v -> {
                closePanel(false);
                DanmakuUIHelper.showChapterDialog(activity, v);
            });
            addPrimary("scale", "画面比例", "scale");
            lastEntryFocus=entry.get("next");
            if(map.television) {
                int timeBarId=activity.getResources().getIdentifier("timeBar","id",activity.getPackageName());
                tvTimeBar=timeBarId==0?null:map.control.findViewById(timeBarId);
                if(tvTimeBar!=null) {
                    save(tvTimeBar);
                    try {
                        timelineKeys=new TimelineConfirmKeyListener(readKeyListener(tvTimeBar), () -> {
                            if(!restoring && activePanel==null)
                                Log.i("AuroraToolbar", "timeline confirm playback="
                                        + FongMiNativeHelper.togglePlayback(activity));
                        });
                        tvTimeBar.setOnKeyListener(timelineKeys);
                    } catch (Throwable error) {
                        Log.w("AuroraToolbar", "Timeline key listener unavailable", error);
                    }
                }
            }
            createPanel();
            if (!map.television) {
                for (View view : map.actions.values()) watchInteraction(view);
                for (View view : entry.values()) watchInteraction(view);
            }
            for(String key:new String[]{"opening","ending"}) {
                View original=map.actions.get(key);
                if(!(original instanceof TextView))continue;
                TextView text=(TextView)original;
                android.text.TextWatcher watcher=new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                    public void onTextChanged(CharSequence s,int start,int before,int count){}
                    public void afterTextChanged(android.text.Editable value) {
                        DanmakuConfig config=DanmakuConfigManager.getConfig(activity);
                        if(!restoring && config!=null && !config.isAutoSkipEnabled())
                            ChapterManager.onAutoSkipChanged(false);
                    }
                };
                text.addTextChangedListener(watcher);markerObservers.put(text,watcher);
            }
            bindHostChrome();
            layoutObserver = (v,l,t,r,b,ol,ot,or,ob) -> {
                if (r-l != or-ol || b-t != ob-ot) fit();
            };
            map.control.addOnLayoutChangeListener(layoutObserver);
            focusObserver = (oldFocus, newFocus) -> {
                if(oldFocus==tvTimeBar && timelineKeys!=null)timelineKeys.clearPress();
                if (restoring || rendering || newFocus == null) return;
                if(activePanel!=null) {
                    if(isWithin(newFocus,panel))lastPanelFocus=newFocus;
                    else if(activity.hasWindowFocus() && (map.television || !panel.isInTouchMode())) {
                        int generation=panelGeneration;
                        panel.post(() -> {
                            if(restoring || generation!=panelGeneration || activePanel==null || !activity.hasWindowFocus())return;
                            if(lastPanelFocus!=null && lastPanelFocus.isShown() && lastPanelFocus.isEnabled())lastPanelFocus.requestFocus();
                            else focusPanelContent(generation);
                        });
                        return;
                    }
                }
                if (isWithin(newFocus, map.row) && (!map.television
                        || (wasShown && map.scroll.isShown() && !toolbarFocusPending))) {
                    lastEntryFocus = newFocus;
                    newFocus.post(() -> {
                    if (!restoring && newFocus.isAttachedToWindow()) newFocus.requestRectangleOnScreen(
                            new android.graphics.Rect(0,0,newFocus.getWidth(),newFocus.getHeight()), false);
                    });
                }
            };
            map.control.getViewTreeObserver().addOnGlobalFocusChangeListener(focusObserver);
            windowFocusObserver = hasFocus -> {
                dialogFocus.onWindowFocusChanged(hasFocus);
                if(searchAwaitingReturn) {
                    if(!hasFocus)searchWindowLost=true;
                    else if(searchWindowLost)finishSearchReturn();
                }
                if (hasFocus) map.row.post(() -> {
                    if(restoring)return;
                    syncDanmakuSelection();
                    if(activePanel!=null)focusPanelContent(panelGeneration);else recoverEntryFocus();
                });
            };
            map.control.getViewTreeObserver().addOnWindowFocusChangeListener(windowFocusObserver);
            visibilityObserver = () -> {
                if (restoring) return;
                observeToolbarVisibility();
                fit();
                wireFocus();
            };
            map.control.getViewTreeObserver().addOnGlobalLayoutListener(visibilityObserver);
            fit(); refresh();
            View previous = null;
            for (Snapshot snapshot : snapshots.values()) if (snapshot.wasFocused) previous = snapshot.view;
            if (!map.television && previous != null && previous.isShown()) previous.requestFocus();
        }

        void observeToolbarVisibility() {
            boolean shown=map.scroll.isShown();
            if(!shown)dialogFocus.cancel();
            if(!shown && activePanel!=null)closePanel(false);
            if(shown==wasShown)return;
            wasShown=shown;
            int generation=++toolbarFocusGeneration;
            toolbarFocusPending=shown && map.television;
            if(toolbarFocusPending)map.row.post(() -> restoreToolbarEntryFocus(generation));
        }

        void restoreToolbarEntryFocus(int generation) {
            if(restoring || generation!=toolbarFocusGeneration || !toolbarFocusPending)return;
            if(activePanel==null && map.scroll.isShown() && activity.hasWindowFocus()) {
                View target=entry.get("next");
                if(target==null || !target.isShown() || !target.isEnabled())target=entry.get("playback");
                if(target!=null && target.isShown())target.requestFocus();
            }
            toolbarFocusPending=false;
        }

        void moveTvTimelineAbove() {
            if (!map.television) return;
            View seek = ToolbarHostMap.direct(map.control, "seek");
            if (seek == null || seek == map.scroll) return;
            int index = map.control.indexOfChild(seek);
            int toolbarIndex = map.control.indexOfChild(map.scroll);
            if (index <= toolbarIndex) return;
            tvSeek = seek; tvSeekIndex = index; save(seek);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams((LinearLayout.LayoutParams)seek.getLayoutParams());
            int gap = p.topMargin; p.topMargin = p.bottomMargin; p.bottomMargin = gap;
            map.control.removeView(seek);
            map.control.addView(seek, toolbarIndex, p);
        }

        void addEpisodesEntry() {
            if(!map.television) {
                addPrimary("episodes", "选集", "episodes");
                return;
            }
            // T1 hides its episodes view on every toolbar reveal in one remote mode.
            // Keep that original view/state intact and expose its existing action here.
            TextView button=addEntry("episodes", "选集", "episodes");
            View nativeView=map.actions.get("episodes");
            button.setEnabled(nativeView!=null && nativeView.isEnabled());
            button.setOnClickListener(v -> invokeEpisodes(false));
            if(nativeView!=null && nativeView.isLongClickable())
                button.setOnLongClickListener(v -> invokeEpisodes(true));
        }

        boolean invokeEpisodes(boolean longClick) {
            View nativeView=map.actions.get("episodes");
            if(nativeView==null || !nativeView.isAttachedToWindow() || !nativeView.isEnabled()
                    || !map.scroll.isShown())return false;
            closePanel(false);
            lastEntryFocus=entry.get("episodes");
            // Visibility is a host presentation preference, not absence of the selector.
            return longClick?nativeView.performLongClick():nativeView.performClick();
        }

        void addPrimary(String key, String title, String glyph) {
            View nativeView = map.actions.get(key);
            if (nativeView instanceof TextView && nativeView.getParent() == parking) {
                TextView text = (TextView) nativeView;
                detach(text);
                ToolbarTheme.text(text, map.television);
                if (glyph != null) text.setCompoundDrawables(null, ToolbarTheme.icon(activity, glyph), null, null);
                text.setCompoundDrawablePadding(dp(7));
                // Dynamic native text (notably speed) is not overwritten.
                map.row.addView(text, tileParams());
                entry.put(key, text);
                managed.add(text);
            } else {
                TextView button = addEntry(key, title, glyph);
                // T2/T3 central prev/next remain in their original parent, identity and focus route.
                button.setOnClickListener(v -> invokeVisible(key, false));
                if (nativeView != null && nativeView.isLongClickable()) {
                    button.setOnLongClickListener(v -> invokeVisible(key, true));
                }
            }
        }

        TextView addEntry(String key, String title, String glyph) {
            TextView button = new TextView(activity);
            button.setId(View.generateViewId());
            button.setText(title);
            button.setContentDescription(title);
            button.setFocusable(true);
            button.setFocusableInTouchMode(false);
            ToolbarTheme.text(button, map.television);
            if (glyph != null) button.setCompoundDrawables(null, ToolbarTheme.icon(activity, glyph), null, null);
            button.setCompoundDrawablePadding(dp(7));
            map.row.addView(button, tileParams());
            View reference = map.actions.get("speed");
            if (reference != null) {
                button.setFocusableInTouchMode(reference.isFocusableInTouchMode());
                button.setNextFocusUpId(reference.getNextFocusUpId());
                button.setNextFocusDownId(reference.getNextFocusDownId());
            }
            entry.put(key, button); managed.add(button);
            return button;
        }

        LinearLayout.LayoutParams tileParams() {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(map.television ? 84 : 72), dp(map.television ? 72 : 64));
            p.setMargins(dp(2),0,dp(2),0);
            return p;
        }

        boolean invokeVisible(String key, boolean longClick) {
            View target = map.actions.get(key);
            if (target == null || !target.isShown() || !target.isEnabled()) {
                Utils.safeShowToast(activity, "当前不可用"); return false;
            }
            return longClick ? target.performLongClick() : target.performClick();
        }

        void createPanel() {
            // A sibling overlay never participates in the host bottom/control measurement.
            // Inserting a panel into that vertical row used to move the original timeline.
            for (android.view.ViewParent parent = map.control.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof FrameLayout) { panelHost = (FrameLayout) parent; break; }
            }
            if (panelHost == null) throw new IllegalStateException("No stable panel overlay host");
            panel = new Panel(activity, () -> closePanel(true), this::resetHostIdle, this::onToolbarTouch);
            panel.setClickable(true);
            panel.setFocusableInTouchMode(!map.television);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(ToolbarTheme.glass(activity, true));
            panel.setPadding(dp(16),dp(12),dp(16),dp(12));
            panel.setElevation(dp(10));
            panel.setVisibility(View.GONE);
            LinearLayout header = new LinearLayout(activity);
            header.setGravity(Gravity.CENTER_VERTICAL);
            panelTitle = new TextView(activity);
            panelTitle.setTextColor(ToolbarTheme.TEXT);panelTitle.setTextSize(map.television?18:16);
            header.addView(panelTitle,new LinearLayout.LayoutParams(0,dp(48),1));
            TextView close = controlButton("关闭");
            close.setContentDescription("关闭面板");
            close.setCompoundDrawables(null,ToolbarTheme.icon(activity,"close"),null,null);
            close.setText("");close.setOnClickListener(v -> closePanel(true));
            watchInteraction(close);
            header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
            panel.addView(header);
            panelScroll = new ScrollView(activity);
            panelScroll.setFillViewport(false);
            panelBody = new LinearLayout(activity);panelBody.setOrientation(LinearLayout.VERTICAL);
            panelScroll.addView(panelBody);
            panel.addView(panelScroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
            panelHost.addView(panel, new FrameLayout.LayoutParams(dp(390), dp(180), Gravity.TOP | Gravity.LEFT));
        }

        TextView controlButton(String label) {
            TextView button = new TextView(activity);
            button.setId(View.generateViewId());button.setText(label);
            button.setContentDescription(label);button.setFocusable(true);button.setFocusableInTouchMode(false);
            ToolbarTheme.text(button,map.television);
            return button;
        }

        void togglePanel(String key, View from) {
            dialogFocus.cancel();
            resetHostIdle();
            if (key.equals(activePanel)) { closePanel(true); return; }
            closePanel(false);
            source=from;activePanel=key;from.setSelected(true);
            lastEntryFocus=from;
            lastPanelFocus=null;
            int generation=++panelGeneration;
            panel.setVisibility(View.VISIBLE);
            renderPanel();fit();
            focusPanelContent(generation);
            panel.post(() -> focusPanelContent(generation));
        }

        void focusPanelContent(int generation) {
            if(restoring || generation!=panelGeneration || activePanel==null || !panel.isShown())return;
            View focused=panel.findFocus();
            if(focused!=null && focused!=panel && focused.isShown() && focused.isEnabled())return;
            for(View focus:panelBody.getFocusables(View.FOCUS_FORWARD)) {
                if(focus.isShown() && focus.isEnabled() && (map.television || !panel.isInTouchMode() || focus.isFocusableInTouchMode())) {
                    if(focus.requestFocus())return;
                }
            }
            if(!map.television)panel.requestFocus();
        }

        void recoverEntryFocus() {
            if(restoring || dialogFocus.pending() || activePanel!=null || !map.scroll.isShown() || !activity.hasWindowFocus())return;
            if(!map.television && map.row.isInTouchMode())return;
            View focused=activity.getCurrentFocus();
            if(focused!=null && focused.isShown() && focused.isEnabled() && focused.isFocusable())return;
            View target=lastEntryFocus;
            if(target!=null && target.isShown() && target.isEnabled())target.requestFocus();
        }

        void renderPanel() {
            rendering = true;
            returnNative();panelBody.removeAllViews();
            gridRow = null; gridCells = 0;
            if ("playback".equals(activePanel)) {
                panelTitle.setText("播放设置");
                nativeRow("player","内核");nativeRow("decode","解码");
                nativeRow("reset","刷新");nativeRow("replay","重播");nativeRow("repeat","循环");
                nativeRow("parse","解析");nativeRow("edition","片源与版本");
                nativeRow("opening","片头");nativeRow("ending","片尾");
                TextView clear=controlButton("清除头尾");
                clear.setContentDescription("清除手动片头片尾");
                clear.setOnClickListener(v -> {
                    boolean ok=ChapterManager.clearManualSkipMarkers(activity);
                    Utils.safeShowToast(activity,ok?"已清除手动片头片尾":"清除失败，请重新进入播放页后重试");
                });
                gridCell(clear);
                nativeRow("nativeChapter","宿主章节");
            } else if ("danmaku".equals(activePanel)) {
                panelTitle.setText("弹幕");
                TextView search=controlButton("弹幕搜索");
                search.setFocusableInTouchMode(true);
                search.setOnClickListener(v -> openDanmakuSearch());
                search.setOnLongClickListener(v -> showRemoteQr());
                gridCell(search);
                nativeRow("danmaku","弹幕显示与设置");
                TextView settings=controlButton("工具设置");
                settings.setOnClickListener(v -> {
                    dialogFocus.begin(entry.get("danmaku"));
                    closePanel(false);
                    DanmakuScanner.showLeoButtonMenu(activity, dialogFocus::onDismiss);
                });
                gridCell(settings);
                TextView remote=controlButton("扫码配置");
                remote.setOnClickListener(v -> showRemoteQr());
                gridCell(remote);
            } else if ("tracks".equals(activePanel)) {
                panelTitle.setText("音视频");
                // The actual host buttons directly open their selectors, preserving tags 1/2.
                nativeRow("audio","音轨");
                nativeRow("video","视轨");
            }
            if(gridRow!=null && gridCells<4)
                while(gridRow.getChildCount()>Math.max(1,gridCells))gridRow.removeViewAt(gridRow.getChildCount()-1);
            panelScroll.scrollTo(0,0);
            rendering = false;
        }

        void openDanmakuSearch() {
            dialogFocus.cancel();
            closePanel(false);
            cancelSearchReturnDraw();
            searchAwaitingReturn=true;searchWindowLost=false;
            hideHostChromeForSearch();
            DanmakuUIHelper.showSearchDialog(activity,DanmakuScanner.lastEpisodeInfo);
        }

        void bindHostChrome() {
            // These are scoped siblings from the dumps, not the toolbar's native title action.
            View widget=ToolbarHostMap.direct(panelHost,"widget");
            hostTop=widget instanceof ViewGroup?ToolbarHostMap.direct((ViewGroup)widget,"top"):null;
            View display=ToolbarHostMap.direct(panelHost,"display");
            if(display instanceof ViewGroup) {
                hostTitleLayout=ToolbarHostMap.direct((ViewGroup)display,"titleLayout");
                hostClock=ToolbarHostMap.direct((ViewGroup)display,"clock");
            }
        }

        void hideHostChromeForSearch() {
            if(restoring)return;
            // Verified T1/516 native hide routine: resets its shown flag, top and hide timer.
            // Other hosts and the fallback use only the mapped presentation containers.
            if(map.television && "com.fongmi.android.tv.ui.activity.VideoActivity".equals(activity.getClass().getName())) {
                try {activity.getClass().getMethod("o0",boolean.class).invoke(activity,true);}
                catch(ReflectiveOperationException ignored) { }
            }
            map.control.setVisibility(View.GONE);
            for(View view:new View[]{hostTop,hostTitleLayout,hostClock})
                if(view!=null && view.getVisibility()!=View.GONE)view.setVisibility(View.GONE);
            observeToolbarVisibility();
        }

        void finishSearchReturn() {
            searchAwaitingReturn=false;searchWindowLost=false;
            hideHostChromeForSearch();
            // The host can update its top/clock in the same window-focus dispatch.
            // Clear that update before the next frame; do not keep suppressing normal controls.
            cancelSearchReturnDraw();
            searchReturnDraw=() -> {
                cancelSearchReturnDraw();
                hideHostChromeForSearch();
                return true;
            };
            map.control.getViewTreeObserver().addOnPreDrawListener(searchReturnDraw);
            map.control.getRootView().invalidate();
        }

        void cancelSearchReturnDraw() {
            if(searchReturnDraw!=null && map.control.getViewTreeObserver().isAlive())
                map.control.getViewTreeObserver().removeOnPreDrawListener(searchReturnDraw);
            searchReturnDraw=null;
        }

        boolean showRemoteQr() {
            dialogFocus.begin(entry.get("danmaku"));
            closePanel(false);
            DanmakuUIHelper.showQRCodeDialog(activity, "http://" + NetworkUtils.getLocalIpAddress() + ":9810",
                    dialogFocus::onDismiss);
            return true;
        }

        FrameLayout gridCell(View content) {
            watchInteraction(content);
            int column = gridCells++ % 4;
            if (column == 0) {
                gridRow = new LinearLayout(activity);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                panelBody.addView(gridRow, new LinearLayout.LayoutParams(-1, dp(56)));
                for (int i = 0; i < 4; i++) {
                    FrameLayout cell = new FrameLayout(activity);
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
                    p.setMargins(dp(3), dp(4), dp(3), dp(4));
                    gridRow.addView(cell, p);
                }
            }
            FrameLayout cell = (FrameLayout) gridRow.getChildAt(column);
            cell.addView(content, new FrameLayout.LayoutParams(-1, -1));
            return cell;
        }

        void nativeRow(String key,String label) {
            View view=map.actions.get(key);
            if(view==null)return;
            if("nativeChapter".equals(key) && !nativeChapterAvailable())return;
            if(view.getParent()!=parking)return;
            detach(view);
            if (map.television) {
                view.setNextFocusLeftId(View.NO_ID);
                view.setNextFocusRightId(View.NO_ID);
            }
            if(view instanceof TextView) {
                TextView text = (TextView) view;
                ToolbarTheme.text(text,map.television);
                if("danmaku".equals(key)) {
                    ToolbarTheme.danmakuState(text);
                    danmakuSelection = null;
                }
                text.setCompoundDrawables(null,null,null,null);
                text.setPadding(dp(4),dp(4),dp(4),dp(4));
            }
            // Original visibility/enabled and all listeners remain authoritative.
            FrameLayout cell=gridCell(view);panelNative.add(view);
            if("danmaku".equals(key))syncDanmakuSelection();
            TextView empty=controlButton(label+"\n不可用");
            empty.setSingleLine(false);empty.setMaxLines(2);empty.setTextSize(11);
            empty.setPadding(dp(2),0,dp(2),0);empty.setEnabled(false);empty.setFocusable(false);
            empty.setContentDescription(label+"，当前片源不可用");
            empty.setVisibility(view.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
            cell.addView(empty,new FrameLayout.LayoutParams(-1,-1));unavailableLabels.put(view,empty);
        }

        void returnNative() {
            releasePanelInteractions();
            for(View view:new ArrayList<>(panelNative)) {detach(view);parking.addView(view);}
            panelNative.clear();
            unavailableLabels.clear();
        }

        void resetHostIdle() {
            if (restoring || map.television || hostIdleReset == null || !map.scroll.isShown()) return;
            try { hostIdleReset.run(); }
            catch (RuntimeException error) {
                hostIdleReset=null;
                Log.w("AuroraToolbar", "Host idle reset unavailable", error);
            }
        }

        void onToolbarTouch(MotionEvent event) {
            int action=event.getActionMasked();
            if (action==MotionEvent.ACTION_MOVE) {
                long now=android.os.SystemClock.uptimeMillis();
                if (now-lastTouchMoveReset<250) return;
                lastTouchMoveReset=now;
            }
            if (action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_UP
                    || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_MOVE) resetHostIdle();
        }

        void watchInteraction(View view) {
            if (map.television || interactionHooks.containsKey(view)) return;
            try { interactionHooks.put(view, new InteractionHooks(view, this::resetHostIdle, this::onToolbarTouch)); }
            catch (ReflectiveOperationException error) { Log.w("AuroraToolbar", "Button interaction hook unavailable", error); }
        }

        void releasePanelInteractions() {
            if (panelBody==null) return;
            for (View view : new ArrayList<>(interactionHooks.keySet())) {
                if (isWithin(view,panelBody) && !map.actions.containsValue(view))
                    interactionHooks.remove(view).restore();
            }
        }

        void closePanel(boolean restoreFocus) {
            if(activePanel==null)return;
            activePanel=null;
            panelGeneration++;
            View returnTo=source;
            if(returnTo!=null)lastEntryFocus=returnTo;
            if(source!=null)source.setSelected(false);
            returnNative();panelBody.removeAllViews();panel.setVisibility(View.GONE);
            source=null;
            lastPanelFocus=null;
            if(!restoring && restoreFocus && returnTo!=null && returnTo.isShown()
                    && (map.television || !map.row.isInTouchMode()))returnTo.requestFocus();
            if(!restoring)map.row.post(this::recoverEntryFocus);
        }

        void fit() {
            if(restoring)return;
            int width=map.panelParent.getWidth()-map.panelParent.getPaddingLeft()-map.panelParent.getPaddingRight();
            LinearLayout.LayoutParams scrollParams=(LinearLayout.LayoutParams)map.scroll.getLayoutParams();
            width-=scrollParams.leftMargin+scrollParams.rightMargin;
            if(width>0) {
                int padding=map.row.getPaddingLeft()+map.row.getPaddingRight(),margin=dp(2),gap=2*margin;
                int count=0;
                for(TextView button:entry.values())if(button.getVisibility()==View.VISIBLE)count++;
                count=Math.max(1,count);
                int available=width-padding;
                int tile=Math.max(dp(map.television?70:56),Math.min(dp(map.television?84:80),(available-count*gap)/count));
                for(TextView button:entry.values()) {
                    LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)button.getLayoutParams();
                    boolean visible=button.getVisibility()==View.VISIBLE;
                    int itemWidth=visible?tile:0,itemMargin=visible?margin:0;
                    if(p.width!=itemWidth || p.leftMargin!=itemMargin || p.rightMargin!=itemMargin){
                        p.width=itemWidth;p.leftMargin=itemMargin;p.rightMargin=itemMargin;button.setLayoutParams(p);
                    }
                }
                int contentWidth=padding+count*(tile+gap);
                FrameLayout.LayoutParams rowParams=(FrameLayout.LayoutParams)map.row.getLayoutParams();
                if(rowParams.width!=contentWidth){rowParams.width=contentWidth;map.row.setLayoutParams(rowParams);}
                int outerWidth=Math.min(width,contentWidth);
                if(scrollParams.width!=outerWidth || scrollParams.weight!=0 || scrollParams.gravity!=Gravity.CENTER_HORIZONTAL) {
                    scrollParams.width=outerWidth;scrollParams.weight=0;scrollParams.gravity=Gravity.CENTER_HORIZONTAL;
                    map.scroll.setLayoutParams(scrollParams);
                }
                if(contentWidth<=outerWidth && map.scroll.getScrollX()!=0)map.scroll.scrollTo(0,0);
            }
            if(panel!=null && activePanel!=null && panelHost.getWidth()>0) {
                int[] hostXY=new int[2],anchorXY=new int[2];
                panelHost.getLocationOnScreen(hostXY);map.panelParent.getLocationOnScreen(anchorXY);
                int bottom=Math.min(panelHost.getHeight()-panelHost.getPaddingBottom(),anchorXY[1]-hostXY[1])-dp(10);
                int top=panelHost.getPaddingTop()+dp(12);
                int maxHeight=Math.max(0,bottom-top);
                int wanted=dp(24+48+56*((gridCells+3)/4));
                int height=Math.min(wanted,maxHeight);
                int columns=Math.max(1,Math.min(4,gridCells));
                int panelPadding=panel.getPaddingLeft()+panel.getPaddingRight();
                int naturalWidth=panelPadding+(dp(map.television?420:390)-panelPadding)*columns/4;
                int panelWidth=Math.min(naturalWidth,Math.max(0,panelHost.getWidth()-panelHost.getPaddingLeft()-panelHost.getPaddingRight()-dp(24)));
                FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)panel.getLayoutParams();
                int left=panelHost.getWidth()-panelHost.getPaddingLeft()-panelHost.getPaddingRight()-panelWidth-dp(12);
                int y=Math.max(top,bottom-height)-panelHost.getPaddingTop();
                if(p.width!=panelWidth||p.height!=height||p.leftMargin!=left||p.topMargin!=y){
                    p.width=panelWidth;p.height=height;p.leftMargin=left;p.topMargin=y;panel.setLayoutParams(p);
                }
            }
        }

        void wireFocus() {
            if (!map.television) return;
            View seekReturn=entry.get("next");
            if(seekReturn==null || !seekReturn.isEnabled() || seekReturn.getVisibility()!=View.VISIBLE)
                seekReturn=entry.get("playback");
            if(tvTimeBar!=null && seekReturn!=null) {
                int targetId=seekReturn.getId();
                if(tvTimeBar.getNextFocusUpId()!=targetId)tvTimeBar.setNextFocusUpId(targetId);
                if(tvTimeBar.getNextFocusDownId()!=targetId)tvTimeBar.setNextFocusDownId(targetId);
            }
            List<TextView> visible = new ArrayList<>();
            for (TextView button : entry.values()) {
                if (button.getVisibility() == View.VISIBLE && button.isEnabled()) visible.add(button);
            }
            // The T1 host uses an explicit horizontal ring. Keep that navigation behavior
            // while connecting the new positions; up/down listeners remain native.
            for (int i = 0; i < visible.size(); i++) {
                TextView button = visible.get(i);
                int left = visible.get((i + visible.size() - 1) % visible.size()).getId();
                int right = visible.get((i + 1) % visible.size()).getId();
                if (button.getNextFocusLeftId() != left) button.setNextFocusLeftId(left);
                if (button.getNextFocusRightId() != right) button.setNextFocusRightId(right);
            }
        }

        boolean valid() {
            if(parking.getParent()!=map.row||panel.getParent()!=panelHost)return false;
            if(map.row.getChildCount()!=managed.size()+1)return false;
            for(View view:managed)if(view.getParent()!=map.row)return false;
            for(View view:map.actions.values()) {
                if(map.originalChildren.contains(view)&&!isWithin(view,map.row)&&!isWithin(view,panel))return false;
            }
            return true;
        }

        boolean nativeChapterAvailable() {
            View view=map.actions.get("nativeChapter");
            return view!=null && view.getVisibility()==View.VISIBLE && view.isEnabled();
        }

        void syncDanmakuSelection() {
            View view = map.actions.get("danmaku");
            if(restoring || !"danmaku".equals(activePanel) || !(view instanceof TextView)
                    || !panelNative.contains(view)) return;
            boolean selected = HostDanmakuSelection.selected(activity, map.target);
            if(danmakuSelection != null && danmakuSelection == selected) return;
            danmakuSelection = selected;
            ToolbarTheme.danmakuSelection((TextView)view, selected);
        }

        void refresh() {
            observeToolbarVisibility();
            syncDanmakuSelection();
            if("playback".equals(activePanel) && !rendering) {
                View nativeChapter=map.actions.get("nativeChapter");
                if(panelNative.contains(nativeChapter)!=nativeChapterAvailable()) {
                    View focused=panel.findFocus();
                    renderPanel();
                    if(focused!=null && focused.isShown() && focused.isEnabled())focused.requestFocus();
                    else focusPanelContent(panelGeneration);
                }
            }
            if(map.television) {
                View nativeEpisodes=map.actions.get("episodes");
                TextView episodes=entry.get("episodes");
                boolean enabled=nativeEpisodes!=null && nativeEpisodes.isEnabled();
                if(episodes.isEnabled()!=enabled)episodes.setEnabled(enabled);
            }
            for(Map.Entry<View,TextView> item:unavailableLabels.entrySet()) {
                int visibility=item.getKey().getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE;
                if(item.getValue().getVisibility()!=visibility)item.getValue().setVisibility(visibility);
            }
            for(String key:new String[]{"prev","next"}) {
                View target=map.actions.get(key);TextView button=entry.get(key);
                if(button!=target) {
                    boolean enabled=target!=null&&target.isShown()&&target.isEnabled();
                    if(button.isEnabled()!=enabled)button.setEnabled(enabled);
                }
            }
            fit();
            wireFocus();
            recoverEntryFocus();
        }

        void restore() {
            restoring=true;
            dialogFocus.cancel();
            for (InteractionHooks hooks : interactionHooks.values()) hooks.restore();
            interactionHooks.clear();
            hostIdleReset=null;
            if(timelineKeys!=null) {
                timelineKeys.clearPress();
                try {
                    if(readKeyListener(tvTimeBar)==timelineKeys)
                        tvTimeBar.setOnKeyListener(timelineKeys.original);
                } catch(Throwable error) {Log.w("AuroraToolbar","Timeline listener restore failed",error);}
            }
            for(Map.Entry<TextView,android.text.TextWatcher> observer:markerObservers.entrySet())
                observer.getKey().removeTextChangedListener(observer.getValue());
            markerObservers.clear();
            searchAwaitingReturn=false;searchWindowLost=false;cancelSearchReturnDraw();
            try {
                if(layoutObserver!=null)map.control.removeOnLayoutChangeListener(layoutObserver);
                if(focusObserver!=null&&map.control.getViewTreeObserver().isAlive())map.control.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusObserver);
                if(windowFocusObserver!=null&&map.control.getViewTreeObserver().isAlive())map.control.getViewTreeObserver().removeOnWindowFocusChangeListener(windowFocusObserver);
                if(visibilityObserver!=null&&map.control.getViewTreeObserver().isAlive())map.control.getViewTreeObserver().removeOnGlobalLayoutListener(visibilityObserver);
                closePanel(false);detach(panel);
                List<View> addedByHost=new ArrayList<>();
                for(int i=0;i<map.row.getChildCount();i++) {
                    View child=map.row.getChildAt(i);
                    if(child!=parking&&!managed.contains(child)&&!map.originalChildren.contains(child))addedByHost.add(child);
                }
                for(View child:map.originalChildren)detach(child);
                map.row.removeAllViews();
                for(View child:map.originalChildren) {
                    Snapshot snapshot=snapshots.get(child);
                    snapshot.restoreStyle();
                    map.row.addView(child,snapshot.params);
                }
                for(View child:addedByHost)map.row.addView(child);
                Snapshot row=snapshots.get(map.row),scroll=snapshots.get(map.scroll);
                if(row!=null)row.restoreStyle();if(scroll!=null)scroll.restoreStyle();
                if(tvTimeBar!=null)snapshots.get(tvTimeBar).restoreStyle();
                if(tvSeek!=null && tvSeek.getParent()==map.control) {
                    map.control.removeView(tvSeek);
                    snapshots.get(tvSeek).restoreStyle();
                    map.control.addView(tvSeek,Math.min(tvSeekIndex,map.control.getChildCount()),tvSeek.getLayoutParams());
                }
                map.row.requestLayout();
            } catch(Throwable error) {Log.e("AuroraToolbar","Restore failure",error);}
        }
    }

    private static Object readListener(View view, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field infoField=View.class.getDeclaredField("mListenerInfo");
        infoField.setAccessible(true);
        Object info=infoField.get(view);
        if(info==null)return null;
        java.lang.reflect.Field keyField=info.getClass().getDeclaredField(name);
        keyField.setAccessible(true);
        return keyField.get(info);
    }

    private static View.OnKeyListener readKeyListener(View view) throws Exception {
        return (View.OnKeyListener)readListener(view,"mOnKeyListener");
    }

    /** T2/516 S0 and T3/556 P1 reschedule the existing native hide runnable only.
     * P1 also keeps the host's lock-state guard. Never invoke the TV show/hide methods here. */
    static Runnable resolveHostIdleReset(Activity activity, String target) {
        if (!"com.fongmi.android.tv".equals(activity.getPackageName())
                || !"com.fongmi.android.tv.ui.activity.VideoActivity".equals(activity.getClass().getName())) return null;
        String method="T2".equals(target)?"S0":"T3".equals(target)?"P1":null;
        if (method==null) return null;
        try {
            int version=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode;
            if (version!=("T2".equals(target)?516:556)) return null;
            java.lang.reflect.Method reset=activity.getClass().getMethod(method);
            Log.i("AuroraToolbar", "Host idle reset bound "+target+"/"+method);
            return () -> {
                try { reset.invoke(activity); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            };
        } catch (Exception error) {
            Log.w("AuroraToolbar", "No verified host idle method for "+target,error);
            return null;
        }
    }

    interface TouchObserver { void onTouch(MotionEvent event); }

    /** Observe only managed buttons, before native actions which may intentionally hide the bar. */
    static final class InteractionHooks {
        final View view;
        final View.OnClickListener originalClick, click;
        final View.OnLongClickListener originalLongClick, longClick;
        final View.OnTouchListener originalTouch, touch;
        InteractionHooks(View view, Runnable reset, TouchObserver observer) throws ReflectiveOperationException {
            this.view=view;
            originalClick=(View.OnClickListener)readListener(view,"mOnClickListener");
            originalLongClick=(View.OnLongClickListener)readListener(view,"mOnLongClickListener");
            originalTouch=(View.OnTouchListener)readListener(view,"mOnTouchListener");
            click=originalClick==null?null:v->{reset.run();originalClick.onClick(v);};
            longClick=originalLongClick==null?null:v->{reset.run();return originalLongClick.onLongClick(v);};
            touch=(v,event)->{observer.onTouch(event);return originalTouch!=null && originalTouch.onTouch(v,event);};
            if(click!=null)view.setOnClickListener(click);
            if(longClick!=null)view.setOnLongClickListener(longClick);
            view.setOnTouchListener(touch);
        }
        void restore() {
            try {
                if(click!=null && readListener(view,"mOnClickListener")==click)view.setOnClickListener(originalClick);
                if(longClick!=null && readListener(view,"mOnLongClickListener")==longClick)view.setOnLongClickListener(originalLongClick);
                if(readListener(view,"mOnTouchListener")==touch)view.setOnTouchListener(originalTouch);
            } catch (ReflectiveOperationException error) { Log.w("AuroraToolbar","Button hook restore failed",error); }
        }
    }

    /** Local to the TV time bar: arrows and all other keys retain their native dispatch. */
    static final class TimelineConfirmKeyListener implements View.OnKeyListener {
        final View.OnKeyListener original;
        final Runnable toggle;
        int pressedKey=KeyEvent.KEYCODE_UNKNOWN;

        TimelineConfirmKeyListener(View.OnKeyListener original,Runnable toggle) {
            this.original=original;this.toggle=toggle;
        }

        void clearPress() {pressedKey=KeyEvent.KEYCODE_UNKNOWN;}

        @Override public boolean onKey(View view,int keyCode,KeyEvent event) {
            boolean confirm=keyCode==KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode==KeyEvent.KEYCODE_ENTER || keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER;
            if(!confirm || !view.isFocused() || !view.isShown() || !view.isEnabled()) {
                if(confirm)clearPress();
                return original!=null && original.onKey(view,keyCode,event);
            }
            if(event.getAction()==KeyEvent.ACTION_DOWN) {
                if(event.getRepeatCount()==0) {
                    pressedKey=keyCode;
                    // DefaultTimeBar commits any pending left/right scrub on key down.
                    // Keep that native commit before toggling playback on release.
                    if(original==null || !original.onKey(view,keyCode,event))
                        view.onKeyDown(keyCode,event);
                }
                return true;
            }
            if(event.getAction()==KeyEvent.ACTION_UP) {
                boolean activate=pressedKey==keyCode && !event.isCanceled();
                clearPress();
                if(original==null || !original.onKey(view,keyCode,event))
                    view.onKeyUp(keyCode,event);
                if(activate)toggle.run();
                return true;
            }
            return original!=null && original.onKey(view,keyCode,event);
        }
    }

    /** Back handling is restricted to this new panel, after the host Activity's dispatch. */
    private static final class Panel extends LinearLayout {
        final Runnable close;
        final Runnable interaction;
        final TouchObserver touchObserver;
        Panel(Context context,Runnable close,Runnable interaction,TouchObserver touchObserver){
            super(context);this.close=close;this.interaction=interaction;this.touchObserver=touchObserver;
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            // Local observation retains child clicks, long presses and ScrollView gestures.
            touchObserver.onTouch(event);
            return super.dispatchTouchEvent(event);
        }
        @Override public View focusSearch(View focused,int direction) {
            // Limit only this panel's D-pad/keyboard traversal; never replace host dispatch.
            View next=android.view.FocusFinder.getInstance().findNextFocus(this,focused,direction);
            return next!=null && isWithin(next,this) ? next : focused;
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            interaction.run();
            if(event.getKeyCode()==KeyEvent.KEYCODE_BACK) {
                if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled())close.run();
                return true;
            }
            int direction;
            switch(event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: direction=View.FOCUS_LEFT;break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: direction=View.FOCUS_RIGHT;break;
                case KeyEvent.KEYCODE_DPAD_UP: direction=View.FOCUS_UP;break;
                case KeyEvent.KEYCODE_DPAD_DOWN: direction=View.FOCUS_DOWN;break;
                case KeyEvent.KEYCODE_TAB: direction=event.isShiftPressed()?View.FOCUS_BACKWARD:View.FOCUS_FORWARD;break;
                default:return super.dispatchKeyEvent(event);
            }
            // Native TV buttons can execute their own up/down callbacks before focusSearch.
            // While mounted here, traverse this panel first; their click/long-click stays native.
            if(event.getAction()==KeyEvent.ACTION_DOWN) {
                View focused=findFocus();
                View next=focusSearch(focused,direction);
                if(next!=null && next.requestFocus(direction))next.requestRectangleOnScreen(
                        new android.graphics.Rect(0,0,next.getWidth(),next.getHeight()),false);
            }
            return true;
        }
    }

    private static void detach(View view) {
        if(view!=null&&view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
    }

    private static boolean isWithin(View view, View ancestor) {
        for (View current = view; current != null;) {
            if (current == ancestor) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    /** UI-only snapshot: never restores old speed/text/selected/activated/visibility/business data. */
    private static final class Snapshot {
        final View view;
        final ViewGroup.LayoutParams params;
        final ViewGroup.LayoutParams layoutValues;
        final Drawable background;
        final int left,top,right,bottom,minWidth,minHeight;
        final float elevation;
        final boolean wasFocused;
        final android.content.res.ColorStateList colors;
        final float textSize;
        final Drawable[] compounds;
        final int drawablePadding,gravity,maxLines,orientation;
        final TextUtils.TruncateAt ellipsize;
        final boolean clipChildren,clipPadding,scrollbar,fillViewport;
        final int nextLeft,nextRight,nextUp,nextDown;
        Snapshot(View view) {
            this.view=view;params=view.getLayoutParams();layoutValues=copyParams(params);background=view.getBackground();
            left=view.getPaddingLeft();top=view.getPaddingTop();right=view.getPaddingRight();bottom=view.getPaddingBottom();
            elevation=view.getElevation();minWidth=view.getMinimumWidth();minHeight=view.getMinimumHeight();wasFocused=view.hasFocus();
            TextView text=view instanceof TextView?(TextView)view:null;
            colors=text==null?null:text.getTextColors();textSize=text==null?0:text.getTextSize();
            compounds=text==null?null:text.getCompoundDrawables();drawablePadding=text==null?0:text.getCompoundDrawablePadding();
            gravity=text!=null?text.getGravity():ToolbarHostMap.isLinearContainer(view)?ToolbarHostMap.linearProperty(view,"getGravity"):0;
            maxLines=text==null?0:text.getMaxLines();ellipsize=text==null?null:text.getEllipsize();
            orientation=ToolbarHostMap.isLinearContainer(view)?ToolbarHostMap.linearProperty(view,"getOrientation"):0;
            clipChildren=view instanceof ViewGroup&&((ViewGroup)view).getClipChildren();
            clipPadding=view instanceof ViewGroup&&((ViewGroup)view).getClipToPadding();
            scrollbar=view.isHorizontalScrollBarEnabled();
            fillViewport=view instanceof HorizontalScrollView && ((HorizontalScrollView)view).isFillViewport();
            nextLeft=view.getNextFocusLeftId();nextRight=view.getNextFocusRightId();
            nextUp=view.getNextFocusUpId();nextDown=view.getNextFocusDownId();
        }
        void restoreStyle() {
            view.animate().cancel();view.setBackground(background);view.setPadding(left,top,right,bottom);
            view.setElevation(elevation);view.setMinimumWidth(minWidth);view.setMinimumHeight(minHeight);
            // Keep the original host LayoutParams subtype (T3 uses an obfuscated subclass).
            params.width=layoutValues.width;params.height=layoutValues.height;
            if(params instanceof ViewGroup.MarginLayoutParams && layoutValues instanceof ViewGroup.MarginLayoutParams){
                ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)params,s=(ViewGroup.MarginLayoutParams)layoutValues;
                p.setMargins(s.leftMargin,s.topMargin,s.rightMargin,s.bottomMargin);
            }
            if(params instanceof LinearLayout.LayoutParams){
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)params,s=(LinearLayout.LayoutParams)layoutValues;
                p.weight=s.weight;p.gravity=s.gravity;
            }
            if(params instanceof FrameLayout.LayoutParams)((FrameLayout.LayoutParams)params).gravity=((FrameLayout.LayoutParams)layoutValues).gravity;
            view.setLayoutParams(params);view.setHorizontalScrollBarEnabled(scrollbar);
            if(view instanceof HorizontalScrollView)((HorizontalScrollView)view).setFillViewport(fillViewport);
            view.setNextFocusLeftId(nextLeft);view.setNextFocusRightId(nextRight);
            view.setNextFocusUpId(nextUp);view.setNextFocusDownId(nextDown);
            if(view instanceof ViewGroup){((ViewGroup)view).setClipChildren(clipChildren);((ViewGroup)view).setClipToPadding(clipPadding);}
            if(ToolbarHostMap.isLinearContainer(view)){ToolbarHostMap.linearProperty(view,"setOrientation",orientation);ToolbarHostMap.linearProperty(view,"setGravity",gravity);}
            if(view instanceof TextView){TextView t=(TextView)view;t.setTextColor(colors);t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,textSize);
                t.setCompoundDrawables(compounds[0],compounds[1],compounds[2],compounds[3]);t.setCompoundDrawablePadding(drawablePadding);
                t.setGravity(gravity);t.setSingleLine(maxLines==1);t.setMaxLines(maxLines);t.setEllipsize(ellipsize);}
        }
        static ViewGroup.LayoutParams copyParams(ViewGroup.LayoutParams p) {
            if(p instanceof LinearLayout.LayoutParams)return new LinearLayout.LayoutParams((LinearLayout.LayoutParams)p);
            if(p instanceof FrameLayout.LayoutParams)return new FrameLayout.LayoutParams((FrameLayout.LayoutParams)p);
            if(p instanceof android.widget.RelativeLayout.LayoutParams)return new android.widget.RelativeLayout.LayoutParams((android.widget.RelativeLayout.LayoutParams)p);
            if(p instanceof ViewGroup.MarginLayoutParams)return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams)p);
            return p==null?new ViewGroup.LayoutParams(-2,-2):new ViewGroup.LayoutParams(p);
        }
    }
}
