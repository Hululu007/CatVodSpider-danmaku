package com.github.catvod.spider;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import java.util.function.BooleanSupplier;

/** Returns from a child window only while the original toolbar remains visible. */
final class ToolbarDialogFocus {
    private final Activity activity;
    private final ViewGroup row;
    private final BooleanSupplier available;
    private View target;
    private Runnable restore;
    private boolean windowLost;
    private int generation;

    ToolbarDialogFocus(Activity activity, ViewGroup row, BooleanSupplier available) {
        this.activity = activity;
        this.row = row;
        this.available = available;
    }

    void begin(View from) {
        cancel();
        target = from;
        windowLost = false;
    }

    boolean pending() { return target != null; }

    void onWindowFocusChanged(boolean focused) {
        if (!pending()) return;
        if (!focused) windowLost = true;
        else if (windowLost) onDismiss();
    }

    void onDismiss() {
        if (!pending()) return;
        if (restore != null) row.removeCallbacks(restore);
        final int expected = generation;
        restore = () -> {
            if (expected != generation || !pending()) return;
            if (activity.isFinishing() || activity.isDestroyed() || !available.getAsBoolean()
                    || !row.isAttachedToWindow() || !row.isShown()) {
                cancel();
                return;
            }
            // A menu may dismiss while its nested settings/QR window still owns focus.
            if (!activity.hasWindowFocus()) return;
            View from = target;
            boolean child = false;
            for (View v = from; v != null; v = v.getParent() instanceof View ? (View)v.getParent() : null) {
                if (v == row) { child = true; break; }
            }
            if (!child || !from.isShown() || !from.isEnabled() || !from.isFocusable()) {
                cancel();
                return;
            }
            if (from.requestFocus()) cancel();
        };
        row.post(restore);
        row.postDelayed(restore, 60);
        row.postDelayed(restore, 220);
    }

    void cancel() {
        generation++;
        if (restore != null) row.removeCallbacks(restore);
        restore = null;
        target = null;
        windowLost = false;
    }
}
