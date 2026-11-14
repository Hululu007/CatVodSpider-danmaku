package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Respect the host's display switch and explicit source deselection. Never replace host listeners. */
final class HostDanmakuGate {
    private HostDanmakuGate() { }

    static boolean preferencesAllow(SharedPreferences prefs) {
        return !Boolean.FALSE.equals(prefs.getAll().get("danmaku_show"))
                && !Boolean.FALSE.equals(prefs.getAll().get("danmaku_load"));
    }

    static boolean sourceSelected(List<?> sources) throws Exception {
        if (sources == null || sources.isEmpty()) return true;
        for (Object source : sources) {
            if (source != null && Boolean.TRUE.equals(source.getClass().getMethod("isSelected").invoke(source))) return true;
        }
        return false;
    }

    static boolean allowsAutoPush(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return inspect(activity);
        boolean[] allowed = {false};
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {try {allowed[0]=inspect(activity);} finally {done.countDown();}});
        try { return done.await(1500, TimeUnit.MILLISECONDS) && allowed[0]; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    static void observe(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {activity.runOnUiThread(() -> inspect(activity));return;}
        inspect(activity);
    }

    private static boolean inspect(Activity activity) {
        SharedPreferences prefs=activity.getSharedPreferences(activity.getPackageName()+"_preferences",Context.MODE_PRIVATE);
        boolean allowed=preferencesAllow(prefs);
        // T3 5.5.6 uses an empty selection but erroneously falls back to the first source URI.
        // Exact version/type checks keep other host internals out of this adapter.
        try {
            if (!"com.fongmi.android.tv".equals(activity.getPackageName())
                    || activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode != 556) return allowed;
            Object service=null;
            for(Class<?> c=activity.getClass();c!=null&&service==null;c=c.getSuperclass())
                for(Field f:c.getDeclaredFields()) if(f.getType().getName().equals("com.fongmi.android.tv.service.PlaybackService")) {f.setAccessible(true);service=f.get(activity);break;}
            if(service==null)return allowed;
            Object engine=field(service,"z");
            if(engine==null || !"xe2".equals(engine.getClass().getName()))return allowed;
            Object request=field(engine,"f");
            if(request==null || !"gf0".equals(request.getClass().getName()))return allowed;
            Object value=field(request,"n");
            if(value instanceof List) allowed &= sourceSelected((List<?>)value);
            applyDisplay(activity.getWindow().getDecorView(), allowed);
        } catch (Throwable ignored) { }
        return allowed;
    }

    private static Object field(Object owner,String name)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }

    private static void applyDisplay(View view,boolean enabled) {
        if(view.getClass().getName().equals("androidx.media3.ui.PlayerView")) {
            try {view.getClass().getMethod("setDanmakuEnabled",boolean.class).invoke(view,enabled);}catch(Throwable ignored) { }
            return;
        }
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++)applyDisplay(((ViewGroup)view).getChildAt(i),enabled);
    }
}
