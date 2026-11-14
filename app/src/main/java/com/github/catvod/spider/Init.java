package com.github.catvod.spider;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.*;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class Init {

    private static final long POST_HOME_BOOTSTRAP_DELAY_MS = 350L;
    private static final AtomicBoolean LIFECYCLE_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean BACKGROUND_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean PLAYBACK_HOOK_REQUESTED = new AtomicBoolean(false);

    private final ExecutorService executor;
    final Handler handler;
    private Application app;

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }

    public static Init get() {
        return Loader.INSTANCE;
    }

    public Init() {
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(5);
    }

    public static Application context() {
        return get().app;
    }

    public static void init(Context context) {
        Application application = (Application) context;
        get().app = application;
        registerLifecycle(application);
        DanmakuSpider.log("[启动] JAR 轻量初始化完成");

        // 兼容 JAR 在播放页热加载的情况；正常启动时不会在这里启动 Hook。
        get().handler.postDelayed(() -> maybeStartPlaybackHook(Utils.getTopActivity()), 500);
    }

    public static void scheduleAfterHomeBootstrap() {
        if (!BACKGROUND_STARTED.compareAndSet(false, true)) return;
        DanmakuSpider.log("[启动] 首页数据已返回，安排后台服务");
        get().handler.postDelayed(Init::launchBackgroundServices,
                POST_HOME_BOOTSTRAP_DELAY_MS);
    }

    public static void startBackgroundServicesNow() {
        if (!BACKGROUND_STARTED.compareAndSet(false, true)) return;
        DanmakuSpider.log("[启动] 内容入口已触发，立即启动后台服务");
        get().handler.post(Init::launchBackgroundServices);
    }

    private static void launchBackgroundServices() {
        final Application application = get().app;
        if (application == null) return;
        execute(() -> {
            try {
                DanmakuSpider.clearCache(application);
                DanmakuSpider.doInitWork(application, "");
            } catch (Throwable e) {
                DanmakuSpider.log("[启动] 弹幕配置初始化失败: " + e.getMessage());
            }
            try {
                Proxy.init();
                ProxyManager.initialize(application);
            } catch (Throwable e) {
                DanmakuSpider.log("[启动] 代理初始化失败: " + e.getMessage());
            }
        });
    }

    private static void registerLifecycle(Application application) {
        if (application == null
                || !LIFECYCLE_REGISTERED.compareAndSet(false, true)) return;
        application.registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                        LoadingPosterController.attach(activity);
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                        LoadingPosterController.attach(activity);
                        maybeStartPlaybackHook(activity);
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        LoadingPosterController.rememberSelection(activity);
                        PlayerToolbarController.onPause(activity);
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                        ChapterManager.onPlaybackStopped(activity);
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                        LoadingPosterController.release(activity);
                        HostLoadingGuard.release(activity);
                        ChapterManager.onPlaybackStopped(activity);
                        PlayerToolbarController.release(activity);
                    }
                });
    }

    private static void maybeStartPlaybackHook(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String className = activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (!DanmakuScanner.isPlayerActivity(className)) return;
        HostLoadingGuard.attach(activity);
        startBackgroundServicesNow();
        if (!PLAYBACK_HOOK_REQUESTED.compareAndSet(false, true)) return;
        get().handler.postDelayed(() -> {
            DanmakuScanner.startHookMonitor();
            DanmakuSpider.log("Leo弹幕监控已启动");
        }, 250);
    }
    public static void execute(Runnable runnable) {
        get().executor.execute(runnable);
    }

    public static void run(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void run(Runnable runnable, int delay) {
        get().handler.postDelayed(runnable, delay);
    }

    public static void checkPermission() {
        try {
            Activity activity = Init.getActivity();
            if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
                return;
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 9999);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Activity getActivity() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
        activitiesField.setAccessible(true);
        Map<?, ?> activities = (Map<?, ?>) activitiesField.get(activityThread);
        for (Object activityRecord : activities.values()) {
            Class<?> activityRecordClass = activityRecord.getClass();
            Field pausedField = activityRecordClass.getDeclaredField("paused");
            pausedField.setAccessible(true);
            if (!pausedField.getBoolean(activityRecord)) {
                Field activityField = activityRecordClass.getDeclaredField("activity");
                activityField.setAccessible(true);
                return (Activity) activityField.get(activityRecord);
            }
        }
        return null;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, int delay) {
        get().handler.postDelayed(runnable, delay);
    }
}
