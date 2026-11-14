package com.github.catvod.spider;

import android.view.View;
import android.view.ViewGroup;

/** Restores a chapter action after a list rebuild without retaining focus on an inert container. */
final class ChapterDialogFocus {
    private ChapterDialogFocus() { }

    static void restore(ViewGroup container) {
        if (container == null) return;
        container.post(() -> restoreNow(container));
        container.postDelayed(() -> restoreNow(container), 60);
        container.postDelayed(() -> restoreNow(container), 220);
    }

    static void restoreNow(ViewGroup container) {
        if (!container.isShown() || container.getWindowVisibility() != View.VISIBLE) return;
        View focused = container.findFocus();
        if (focused != null && focused != container && focused.isShown()
                && focused.isEnabled() && focused.isClickable()) return;
        for (View child : container.getFocusables(View.FOCUS_FORWARD)) {
            if (child != container && child.isShown() && child.isEnabled()
                    && child.isClickable() && child.requestFocus()) return;
        }
    }
}
