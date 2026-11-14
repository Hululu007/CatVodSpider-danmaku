package com.github.catvod.spider;

import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** Keeps list rebuilds from stranding TV focus on an inert scroll container. */
final class SearchDialogFocus {
    private static final class Navigation {
        final ViewGroup tabs;
        final View selected;
        Navigation(ViewGroup tabs, View selected) { this.tabs = tabs; this.selected = selected; }
    }
    private SearchDialogFocus() { }

    static void bindNavigation(ViewGroup tabs, ViewGroup results, View selected) {
        results.setTag(new Navigation(tabs, selected));
        refreshNavigation(results);
    }

    static void refreshNavigation(ViewGroup results) {
        if (!(results.getTag() instanceof Navigation)) return;
        Navigation navigation = (Navigation) results.getTag();
        List<View> headers = new ArrayList<>();
        for (int i = 0; i < results.getChildCount(); i++) {
            View card = results.getChildAt(i);
            if (card instanceof ViewGroup && ((ViewGroup) card).getChildCount() > 0) {
                View header = ((ViewGroup) card).getChildAt(0);
                if (header.isFocusable() && header.isClickable()) headers.add(header);
            }
        }
        View first = headers.isEmpty() ? null : headers.get(0);
        for (int i = 0; i < navigation.tabs.getChildCount(); i++) {
            route(navigation.tabs.getChildAt(i), null, first);
        }
        for (int i = 0; i < headers.size(); i++) {
            View header = headers.get(i);
            View previous = i == 0 ? navigation.selected : headers.get(i - 1);
            View next = i + 1 < headers.size() ? headers.get(i + 1) : null;
            ViewGroup card = (ViewGroup) header.getParent();
            List<View> episodes = new ArrayList<>();
            if (card.getChildCount() > 1 && card.getChildAt(1) instanceof ViewGroup
                    && card.getChildAt(1).getVisibility() == View.VISIBLE) {
                for (View candidate : ((ViewGroup) card.getChildAt(1)).getFocusables(View.FOCUS_FORWARD)) {
                    if (candidate.isClickable()) episodes.add(candidate);
                }
            }
            route(header, previous, episodes.isEmpty() ? next : episodes.get(0));
            // Let Android retain geometric navigation between episode cells.
            if (!episodes.isEmpty()) {
                int columns = card.getChildAt(1) instanceof android.widget.GridLayout
                        ? ((android.widget.GridLayout) card.getChildAt(1)).getColumnCount() : 1;
                for (int j = 0; j < episodes.size(); j++) {
                    route(episodes.get(j), j < columns ? header : null,
                            j >= ((episodes.size() - 1) / columns) * columns ? next : null);
                }
            }
        }
    }

    private static void route(View from, View up, View down) {
        if (from.getId() == View.NO_ID) from.setId(View.generateViewId());
        if (up != null && up.getId() == View.NO_ID) up.setId(View.generateViewId());
        if (down != null && down.getId() == View.NO_ID) down.setId(View.generateViewId());
        from.setNextFocusUpId(up == null ? View.NO_ID : up.getId());
        from.setNextFocusDownId(down == null ? View.NO_ID : down.getId());
        from.setOnKeyListener((view, key, event) -> {
            View target = key == KeyEvent.KEYCODE_DPAD_UP ? up
                    : key == KeyEvent.KEYCODE_DPAD_DOWN ? down : null;
            if (target == null || !target.isAttachedToWindow() || !target.isShown()) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) return true;
            if (event.getAction() != KeyEvent.ACTION_DOWN || !target.requestFocus()) return false;
            target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
            return true;
        });
    }

    static void restore(ViewGroup root, View preferred) {
        root.post(() -> restoreNow(root, preferred));
        root.postDelayed(() -> restoreNow(root, preferred), 60);
        root.postDelayed(() -> restoreNow(root, preferred), 220);
    }

    static boolean recoverKey(ViewGroup root, View preferred, int code, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN
                || (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
                && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER)) return false;
        if (actionable(root, root.findFocus())) return false;
        restoreNow(root, preferred);
        View target = root.findFocus();
        if (target == null) return false;
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) {
            target.dispatchKeyEvent(event);
        }
        return true;
    }

    static void restoreNow(ViewGroup root, View preferred) {
        if (!root.isAttachedToWindow() || !root.isShown()) return;
        View focused = root.findFocus();
        if (actionable(root, focused)) return;
        if (actionable(root, preferred) && preferred.requestFocus()) return;
        for (View candidate : root.getFocusables(View.FOCUS_FORWARD)) {
            if (actionable(root, candidate) && candidate.requestFocus()) return;
        }
    }

    private static boolean actionable(ViewGroup root, View view) {
        if (view == null || !view.isShown() || !view.isEnabled()
                || !view.isFocusable() || !view.isClickable()) return false;
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == root) return true;
        }
        return false;
    }
}
