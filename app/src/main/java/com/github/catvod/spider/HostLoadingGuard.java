package com.github.catvod.spider;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;

/** Work around the empty-URL immediate repost in OK 5.1.6's CustomSeekView. */
final class HostLoadingGuard {
    private static final java.util.Map<Activity, State> states = new java.util.IdentityHashMap<>();

    static void attach(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (states.containsKey(activity)) return;
        try {
            if (!"com.fongmi.android.tv".equals(activity.getPackageName())
                    || activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode != 516) return;
            View view = findSeek(activity.getWindow().getDecorView());
            if (view == null) return;
            String playerType = view.getClass().getDeclaredField("v").getType().getName();
            if (!"C3.e".equals(playerType) && !"F3.f".equals(playerType)) return;
            State state = new State(view);
            states.put(activity, state);
            state.start();
            Log.i("HostLoadingGuard", "empty-URL guard bound " + playerType);
        } catch (Exception error) {
            Log.w("HostLoadingGuard", "Unsupported seek mapping", error);
        }
    }

    static void release(Activity activity) {
        State state = states.remove(activity);
        if (state != null) state.close(false);
    }

    private static View findSeek(View view) {
        if (view.getClass().getName().equals("com.fongmi.android.tv.ui.custom.CustomSeekView")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSeek(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // Package-visible so the real Android event loop can exercise the adapter in isolation.
    static final class State implements Runnable, View.OnAttachStateChangeListener {
        final View view;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Field attached, player, url;
        final Runnable update;
        boolean paused, closed;

        State(View view) throws ReflectiveOperationException {
            this.view = view;
            attached = view.getClass().getDeclaredField("u");
            player = view.getClass().getDeclaredField("v");
            Field callback = view.getClass().getDeclaredField("p");
            url = player.getType().getDeclaredField("D");
            if (attached.getType() != boolean.class || url.getType() != String.class
                    || !Runnable.class.isAssignableFrom(callback.getType()))
                throw new NoSuchFieldException("Seek adapter type mismatch");
            attached.setAccessible(true); player.setAccessible(true);
            callback.setAccessible(true); url.setAccessible(true);
            update = (Runnable) callback.get(view);
            if (update == null) throw new NoSuchFieldException("Missing update callback");
        }

        void start() {
            view.addOnAttachStateChangeListener(this);
            run();
        }

        @Override public void run() {
            handler.removeCallbacks(this);
            if (closed || !view.isAttachedToWindow()) return;
            try {
                Object value = player.get(view);
                boolean loading = value != null && TextUtils.isEmpty((String) url.get(value));
                if (loading) {
                    // u is solely the host's attached/update gate (verified on T1/T2).
                    // Block both the queued callback and direct host refreshes during loading.
                    attached.setBoolean(view, false);
                    view.removeCallbacks(update);
                    paused = true;
                } else if (paused) {
                    paused = false;
                    attached.setBoolean(view, true);
                    view.removeCallbacks(update);
                    update.run(); // Native 200-1000ms scheduling takes over once URL exists.
                }
                handler.postDelayed(this, 200);
            } catch (ReflectiveOperationException error) {
                close();
            }
        }

        @Override public void onViewAttachedToWindow(View view) {
            // Run after the host's onAttachedToWindow has restored its update gate.
            handler.removeCallbacks(this);
            handler.post(this);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            handler.removeCallbacks(this);
            view.removeCallbacks(update);
        }

        void close() {
            close(true);
        }

        void close(boolean resume) {
            closed = true;
            handler.removeCallbacks(this);
            view.removeOnAttachStateChangeListener(this);
            if (!paused) return;
            paused = false;
            try { attached.setBoolean(view, resume && view.isAttachedToWindow()); }
            catch (IllegalAccessException ignored) { }
            view.removeCallbacks(update);
            if (resume && view.isAttachedToWindow()) view.post(update);
        }
    }
}
