package com.github.catvod.spider;

import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Relabels individual source URLs without monopolizing the Android UI thread. */
final class DanmakuMenuRelabeler implements Runnable {
    static final String BUILD = "danmaku-menu-20261114-r1";
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_STEPS_PER_BATCH = 64;
    private static final long BATCH_NANOS = 4_000_000L;
    private static final long NEXT_BATCH_MS = 16;
    private static final Pattern SOURCE_URL = Pattern.compile(
            "(?i)^(?:https?://)?[^\\s?#]+/comment/([0-9]+)(?:[/?#][^\\s]*)?$");

    interface LabelResolver { String label(String episodeId); }

    private static final class Entry {
        final View view;
        int nextChild = -1;
        Entry(View view) { this.view = view; }
    }

    private final Handler handler;
    private final LabelResolver resolver;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private final IdentityHashMap<View, Boolean> visited = new IdentityHashMap<>();
    private Runnable completion;

    DanmakuMenuRelabeler(Handler handler, LabelResolver resolver) {
        this.handler = handler;
        this.resolver = resolver;
    }

    void start(List<View> roots, Runnable completion) {
        cancel();
        this.completion = completion;
        for (int i = roots.size() - 1; i >= 0; i--) {
            View root = roots.get(i);
            if (root != null) pending.push(new Entry(root));
        }
        run();
    }

    void cancel() {
        handler.removeCallbacks(this);
        pending.clear();
        visited.clear();
        Runnable done = completion;
        completion = null;
        if (done != null) done.run();
    }

    @Override public void run() {
        long deadline = System.nanoTime() + BATCH_NANOS;
        int steps = 0;
        try {
            while (!pending.isEmpty() && steps++ < MAX_STEPS_PER_BATCH
                    && System.nanoTime() < deadline) {
                Entry entry = pending.peek();
                View view = entry.view;
                if (entry.nextChild < 0) {
                    if (visited.put(view, Boolean.TRUE) != null
                            || !view.isAttachedToWindow() || !view.isShown()
                            || view.getAlpha() <= 0f) {
                        pending.pop();
                        continue;
                    }
                    if (view instanceof TextView && !(view instanceof EditText)) {
                        relabel((TextView) view);
                    }
                    entry.nextChild = 0;
                }
                if (view instanceof ViewGroup && view.isAttachedToWindow() && view.isShown()) {
                    ViewGroup group = (ViewGroup) view;
                    if (entry.nextChild < group.getChildCount()) {
                        View child = group.getChildAt(entry.nextChild++);
                        if (child != null) pending.push(new Entry(child));
                        continue;
                    }
                }
                pending.pop();
            }
            if (pending.isEmpty()) cancel();
            else if (!handler.postDelayed(this, NEXT_BATCH_MS)) cancel();
        } catch (RuntimeException e) {
            cancel();
            DanmakuSpider.log("弹幕菜单扫描异常: " + e.getMessage());
        }
    }

    private void relabel(TextView view) {
        String episodeId = episodeId(view.getText());
        if (episodeId == null) return;
        String label = resolver.label(episodeId);
        if (label != null && !label.contentEquals(view.getText())) {
            view.setText(label);
        }
    }

    static String episodeId(CharSequence text) {
        // Check length before copying: log viewers can contain hundreds of KB.
        if (text == null || text.length() == 0 || text.length() > MAX_URL_LENGTH) return null;
        String url = text.toString().trim();
        if (url.indexOf('/') < 0) return null;
        Matcher matcher = SOURCE_URL.matcher(url);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
