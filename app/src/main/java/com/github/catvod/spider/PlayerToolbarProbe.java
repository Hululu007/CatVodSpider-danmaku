package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Read-only player toolbar diagnostics. This class must never mutate a View.
 */
public final class PlayerToolbarProbe {
    private static final String TAG = "ToolbarProbe";
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 1500;
    private static final long MIN_PROBE_INTERVAL_MS = 750L;
    private static final String[] CANDIDATE_IDS = {
            "control", "actionLayout", "player", "decode", "scale", "danmu",
            "text", "opening", "ending", "audio", "video", "speed", "reset", "repeat"
    };

    private static WeakReference<Activity> lastActivity = new WeakReference<>(null);
    private static WeakReference<View> lastRoot = new WeakReference<>(null);
    private static String lastSignature = "";
    private static long lastProbeAt;

    private PlayerToolbarProbe() {
    }

    public static boolean isDiagnose(Context context) {
        if (context == null) return false;
        File externalDir = context.getExternalFilesDir(null);
        if (externalDir != null
                && new File(externalDir, "toolbar_diagnose.flag").isFile()) {
            return true;
        }
        DanmakuConfig config = DanmakuConfigManager.getConfig(context);
        return config != null
                && DanmakuConfig.PLAYER_TOOLBAR_MODE_DIAGNOSE.equals(config.getPlayerToolbarMode());
    }

    public static void probe(Activity activity, String reason) {
        if (activity == null || activity.isFinishing() || !isDiagnose(activity)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> probe(activity, reason));
            return;
        }

        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;

        long now = System.currentTimeMillis();
        String signature = buildSignature(activity, root);
        if (lastActivity.get() == activity
                && lastRoot.get() == root
                && signature.equals(lastSignature)
                && now - lastProbeAt < MIN_PROBE_INTERVAL_MS) {
            return;
        }
        lastActivity = new WeakReference<>(activity);
        lastRoot = new WeakReference<>(root);
        lastSignature = signature;
        lastProbeAt = now;

        Log.i(TAG, "BEGIN reason=" + safe(reason)
                + " activity=" + activity.getClass().getName()
                + " package=" + activity.getPackageName()
                + " sdk=" + Build.VERSION.SDK_INT
                + " root=" + root.getClass().getName()
                + " screen=" + root.getWidth() + "x" + root.getHeight()
                + " density=" + activity.getResources().getDisplayMetrics().density
                + " signature=" + safe(signature));

        Traversal traversal = new Traversal(activity);
        traversal.visit(root, 0, "0");
        Log.i(TAG, "END reason=" + safe(reason)
                + " nodes=" + traversal.nodes
                + " truncated=" + traversal.truncated);
    }

    private static String buildSignature(Activity activity, View root) {
        StringBuilder result = new StringBuilder(160);
        result.append("root=").append(System.identityHashCode(root));
        result.append(",children=").append(root instanceof ViewGroup
                ? ((ViewGroup) root).getChildCount() : -1);
        View focused = activity.getCurrentFocus();
        result.append(",focus=").append(viewIdentity(focused));

        Resources resources = activity.getResources();
        String packageName = activity.getPackageName();
        for (String name : CANDIDATE_IDS) {
            int id = resources.getIdentifier(name, "id", packageName);
            View view = id == View.NO_ID ? null : root.findViewById(id);
            result.append(',').append(name).append('=');
            if (view == null) {
                result.append("missing");
                continue;
            }
            result.append(view.getVisibility())
                    .append('/').append(view.isShown() ? 1 : 0)
                    .append('/').append(formatFloat(view.getAlpha()))
                    .append('/').append(formatFloat(view.getTranslationX()))
                    .append('/').append(formatFloat(view.getTranslationY()));
        }
        return result.toString();
    }

    private static final class Traversal {
        private final Activity activity;
        private final Rect visibleRect = new Rect();
        private int nodes;
        private boolean truncated;

        private Traversal(Activity activity) {
            this.activity = activity;
        }

        private void visit(View view, int depth, String path) {
            if (view == null) return;
            if (depth > MAX_DEPTH || nodes >= MAX_NODES) {
                truncated = true;
                return;
            }
            nodes++;
            Log.i(TAG, formatNode(view, depth, path, visibleRect));

            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                if (nodes >= MAX_NODES) {
                    truncated = true;
                    return;
                }
                visit(group.getChildAt(i), depth + 1, path + "/" + i);
            }
        }
    }

    private static String formatNode(View view, int depth, String path, Rect rect) {
        StringBuilder out = new StringBuilder(420);
        out.append("n=").append(depth).append('/').append(path)
                .append(" cls=").append(view.getClass().getName());

        int id = view.getId();
        out.append(" id=").append(id == View.NO_ID
                ? "none" : "0x" + Integer.toHexString(id));
        out.append(" name=").append(resourceName(view, id));

        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            out.append(" text=\"").append(safe(textView.getText())).append('"')
                    .append(" textSize=").append(formatFloat(textView.getTextSize()))
                    .append(" textColor=0x").append(Integer.toHexString(textView.getCurrentTextColor()))
                    .append(" gravity=").append(textView.getGravity());
        }
        out.append(" desc=\"").append(safe(view.getContentDescription())).append('"');

        Object tag = view.getTag();
        out.append(" tagType=").append(tag == null ? "null" : tag.getClass().getName())
                .append(" tag=\"").append(safeTag(tag)).append('"');
        out.append(" vis=").append(visibility(view.getVisibility()))
                .append(" shown=").append(view.isShown() ? 1 : 0)
                .append(" alpha=").append(formatFloat(view.getAlpha()))
                .append(" enabled=").append(view.isEnabled() ? 1 : 0)
                .append(" selected=").append(view.isSelected() ? 1 : 0)
                .append(" activated=").append(view.isActivated() ? 1 : 0)
                .append(" clickable=").append(view.isClickable() ? 1 : 0)
                .append(" longClickable=").append(view.isLongClickable() ? 1 : 0)
                .append(" hasClick=").append(view.hasOnClickListeners() ? 1 : 0);

        out.append(" focusable=").append(view.isFocusable() ? 1 : 0)
                .append(" touchFocus=").append(view.isFocusableInTouchMode() ? 1 : 0)
                .append(" focused=").append(view.isFocused() ? 1 : 0)
                .append(" focus=").append(viewIdentity(view.findFocus()))
                .append(" nextUp=").append(focusIdName(view, view.getNextFocusUpId()))
                .append(" nextDown=").append(focusIdName(view, view.getNextFocusDownId()))
                .append(" nextLeft=").append(focusIdName(view, view.getNextFocusLeftId()))
                .append(" nextRight=").append(focusIdName(view, view.getNextFocusRightId()))
                .append(" nextForward=").append(focusIdName(view, view.getNextFocusForwardId()));

        ViewGroup.LayoutParams params = view.getLayoutParams();
        out.append(" lp=").append(params == null ? "null" : params.getClass().getName())
                .append(' ').append(params == null ? "?" : params.width)
                .append('x').append(params == null ? "?" : params.height);
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            out.append(" margins=").append(margins.leftMargin).append(',')
                    .append(margins.topMargin).append(',')
                    .append(margins.rightMargin).append(',')
                    .append(margins.bottomMargin);
        }
        out.append(" padding=").append(view.getPaddingLeft()).append(',')
                .append(view.getPaddingTop()).append(',')
                .append(view.getPaddingRight()).append(',')
                .append(view.getPaddingBottom());
        out.append(" measured=").append(view.getMeasuredWidth()).append('x')
                .append(view.getMeasuredHeight())
                .append(" scroll=").append(view.getScrollX()).append(',')
                .append(view.getScrollY());

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        out.append(" bounds=[").append(location[0]).append(',').append(location[1])
                .append("][").append(location[0] + view.getWidth()).append(',')
                .append(location[1] + view.getHeight()).append(']');
        rect.setEmpty();
        boolean globallyVisible = view.getGlobalVisibleRect(rect);
        out.append(" visibleRect=").append(globallyVisible ? 1 : 0).append('[')
                .append(rect.left).append(',').append(rect.top).append("][")
                .append(rect.right).append(',').append(rect.bottom).append(']');
        out.append(" attached=").append(view.isAttachedToWindow() ? 1 : 0)
                .append(" elevation=").append(formatFloat(view.getElevation()))
                .append(" translation=").append(formatFloat(view.getTranslationX())).append(',')
                .append(formatFloat(view.getTranslationY()));
        return out.toString();
    }

    private static String focusIdName(View view, int id) {
        if (id == View.NO_ID) return "none";
        return resourceName(view, id);
    }

    private static String resourceName(View view, int id) {
        if (id == View.NO_ID || view == null) return "none";
        try {
            return view.getResources().getResourceName(id);
        } catch (Resources.NotFoundException ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String viewIdentity(View view) {
        if (view == null) return "none";
        return resourceName(view, view.getId()) + '@'
                + Integer.toHexString(System.identityHashCode(view));
    }

    private static String visibility(int visibility) {
        if (visibility == View.VISIBLE) return "V";
        if (visibility == View.INVISIBLE) return "I";
        if (visibility == View.GONE) return "G";
        return String.valueOf(visibility);
    }

    private static String safeTag(Object tag) {
        if (tag == null) return "null";
        if (tag instanceof CharSequence
                || tag instanceof Number
                || tag instanceof Boolean
                || tag instanceof Character
                || tag instanceof Enum) {
            return safe(tag.toString());
        }
        return "<" + tag.getClass().getName() + ">";
    }

    private static String safe(Object value) {
        if (value == null) return "";
        String text = value.toString().replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replace('"', '\'');
        if (text.length() > 240) text = text.substring(0, 240) + "...";
        return text;
    }

    private static String formatFloat(float value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
