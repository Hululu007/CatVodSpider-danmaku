package com.github.catvod.spider;

import android.app.Activity;
import java.lang.reflect.Field;
import java.util.List;

/** Read the same source beans used by the host's selection dialog; never change host state. */
final class HostDanmakuSelection {
    private HostDanmakuSelection() { }

    static boolean selected(Activity activity, String target) {
        try {
            if (!"com.fongmi.android.tv".equals(activity.getPackageName())) return false;
            int version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
            return anySelected(sources(activity, version, target));
        } catch (Throwable ignored) { return false; }
    }

    static List<?> sources(Object host, int version, String target) throws Exception {
        Object value;
        if (version == 516 && "T1".equals(target)) {
            // Current TV APK: VideoActivity.h0 (C3.e), whose s is shared with O3.k's dialog.
            Object engine = typedField(host, "C3.e");
            if (engine == null) return null;
            value = field(engine, "s");
        } else if (version == 516 && "T2".equals(target)) {
            // Archived phone APK: VideoActivity.c0 (F3.f) keeps the same selected source list.
            Object engine = typedField(host, "F3.f");
            if (engine == null) return null;
            value = field(engine, "s");
        } else if (version == 556 && "T3".equals(target)) {
            Object service = typedField(host, "com.fongmi.android.tv.service.PlaybackService");
            if (service == null) return null;
            Object engine = field(service, "z");
            if (engine == null || !"xe2".equals(engine.getClass().getName())) return null;
            Object request = field(engine, "f");
            if (request == null || !"gf0".equals(request.getClass().getName())) return null;
            value = field(request, "n");
        } else return null;
        return value instanceof List ? (List<?>) value : null;
    }

    static boolean anySelected(List<?> sources) throws Exception {
        if (sources == null) return false;
        for (Object source : sources) {
            if (source != null && Boolean.TRUE.equals(source.getClass().getMethod("isSelected").invoke(source)))
                return true;
        }
        return false;
    }

    private static Object typedField(Object owner, String type) throws Exception {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) if (type.equals(f.getType().getName())) {
                f.setAccessible(true);
                return f.get(owner);
            }
        return null;
    }

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
}
