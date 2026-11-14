package com.github.catvod.spider;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resource names from the three archived D01 dumps, scoped to the actual control row. */
final class ToolbarHostMap {
    final String target;
    final ViewGroup control;
    final ViewGroup panelParent;
    final HorizontalScrollView scroll;
    final ViewGroup row;
    final Map<String, View> actions = new LinkedHashMap<>();
    final List<View> originalChildren = new ArrayList<>();
    final boolean television;

    private ToolbarHostMap(String target, ViewGroup control, ViewGroup panelParent,
                           HorizontalScrollView scroll, ViewGroup row) {
        this.target = target;
        this.control = control;
        this.panelParent = panelParent;
        this.scroll = scroll;
        this.row = row;
        television = "T1".equals(target);
        for (int i = 0; i < row.getChildCount(); i++) originalChildren.add(row.getChildAt(i));
        for (String name : new String[]{"prev", "next", "episodes", "player", "decode",
                "scale", "speed", "reset", "replay", "text", "audio", "video",
                "opening", "ending", "parse", "edition"}) {
            View node = direct(row, name);
            // IDs named player/video/text elsewhere in the window are unrelated.
            if (node instanceof TextView && node.hasOnClickListeners()) actions.put(name, node);
        }
        put("danmaku", direct(row, television ? "danmu" : "danmaku"));
        put("repeat", direct(row, "T3".equals(target) ? "repeat" : "loop"));
        put("nativeChapter", direct(row, "T3".equals(target) ? "chapter" : "title"));
        if (!television) {
            put("prev", uniqueOutsideRow(control, row, "prev"));
            put("next", uniqueOutsideRow(control, row, "next"));
        }
    }

    private void put(String key, View view) {
        if (view != null && view.hasOnClickListeners()) actions.put(key, view);
    }

    static ToolbarHostMap resolve(Activity activity) {
        try {
            if (!"com.fongmi.android.tv".equals(activity.getPackageName())) return null;
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return resolveTree(activity.getWindow().getDecorView(), info.versionCode);
        } catch (Throwable e) {
            return null;
        }
    }

    static ToolbarHostMap resolveTree(View root, int version) {
        try {
            if (version != 516 && version != 556) return null;
            List<View> controls = new ArrayList<>();
            collect(root, "control", controls, 0);
            ToolbarHostMap found = null;
            for (View node : controls) {
                if (!(node instanceof ViewGroup)) continue;
                ViewGroup control = (ViewGroup) node;
                ToolbarHostMap candidate = null;
                if (version == 516 && control instanceof LinearLayout) {
                    List<View> rows = new ArrayList<>();
                    collect(control, "actionLayout", rows, 0);
                    if (rows.size() == 1 && rows.get(0) instanceof LinearLayout
                            && rows.get(0).getParent() instanceof HorizontalScrollView) {
                        HorizontalScrollView scroll = (HorizontalScrollView) rows.get(0).getParent();
                        if (scroll.getParent() == control) candidate = new ToolbarHostMap("T1",
                                control, (LinearLayout) control, scroll, (LinearLayout) rows.get(0));
                    }
                } else if (control instanceof RelativeLayout) {
                    View bottom = direct(control, "bottom");
                    View action = bottom instanceof ViewGroup ? direct((ViewGroup) bottom, "action") : null;
                    if (isLinearContainer(bottom) && action instanceof HorizontalScrollView) {
                        HorizontalScrollView scroll = (HorizontalScrollView) action;
                        if (scroll.getChildCount() == 1 && isLinearContainer(scroll.getChildAt(0))) {
                            ViewGroup row = (ViewGroup) scroll.getChildAt(0);
                            boolean third = version == 556 && direct(row, "repeat") != null
                                    && direct(row, "chapter") != null && direct(row, "replay") != null;
                            boolean second = version == 516 && direct(row, "loop") != null
                                    && direct(row, "title") != null;
                            if (third || second) candidate = new ToolbarHostMap(third ? "T3" : "T2",
                                    control, (ViewGroup) bottom, scroll, row);
                        }
                    }
                }
                if (candidate == null || !candidate.hasRequiredNodes()) continue;
                // Ambiguous simultaneous players are left untouched.
                if (found != null) return null;
                found = candidate;
            }
            return found;
        } catch (Throwable e) {
            return null;
        }
    }

    static boolean isLinearContainer(View view) {
        if(view instanceof LinearLayout)return true;
        if(!(view instanceof ViewGroup))return false;
        for(Class<?> type=view.getClass();type!=null;type=type.getSuperclass())
            if("androidx.appcompat.widget.LinearLayoutCompat".equals(type.getName()))return true;
        return false;
    }

    static int linearProperty(View view,String getter) {
        try{return (Integer)view.getClass().getMethod(getter).invoke(view);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Unsupported linear container "+getter,error);}
    }

    static void linearProperty(View view,String setter,int value) {
        try{view.getClass().getMethod(setter,int.class).invoke(view,value);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Unsupported linear container "+setter,error);}
    }

    private boolean hasRequiredNodes() {
        for (String key : new String[]{"player", "decode", "scale", "speed", "danmaku",
                "repeat", "opening", "ending", "episodes", "text", "audio", "video"}) {
            if (!actions.containsKey(key)) return false;
        }
        for (View child : originalChildren) {
            // Unknown interactive slots must remain reachable, so decline the entire layout.
            if (child.hasOnClickListeners() && !actions.containsValue(child)
                    && !"danmu_button".equals(child.getTag())
                    && !"chapter_button".equals(child.getTag())
                    && !"auto_button".equals(child.getTag())) return false;
        }
        return true;
    }

    static String name(View view) {
        if (view == null || view.getId() == View.NO_ID || view.getId() == 0) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Exception ignored) { return ""; }
    }

    static View direct(ViewGroup parent, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (name.equals(name(child))) return child;
        }
        return null;
    }

    private static View uniqueOutsideRow(View root, View row, String name) {
        List<View> matches = new ArrayList<>();
        collect(root, name, matches, 0);
        View result = null;
        for (View view : matches) {
            if (view.getParent() == row || !view.hasOnClickListeners()) continue;
            if (result != null) return null;
            result = view;
        }
        return result;
    }

    private static void collect(View view, String name, List<View> result, int depth) {
        if (view == null || depth > 24) return;
        if (name.equals(name(view))) result.add(view);
        if (!(view instanceof ViewGroup) || view.getClass().getName().contains("RecyclerView")) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), name, result, depth + 1);
    }
}
