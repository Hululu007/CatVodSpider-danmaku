package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.net.LocalServerSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.github.catvod.spider.entity.DanmakuItem;
import com.github.catvod.spider.entity.Media;
import com.github.catvod.spider.danmu.SharedPreferencesService;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanmakuScanner {

    public static String lastDetectedTitle = "";

    public static EpisodeInfo lastEpisodeInfo = null;

    private static String currentSeriesName = "";
    public static String currentEpisodeNum = "";
    private static long lastEpisodeChangeTime = 0;
    private static final long MIN_EPISODE_CHANGE_INTERVAL = 1000;

    // 视频播放状态
    private static boolean isVideoPlaying = false;
    private static int stopConfirmCount = 0;
    private static long videoPlayStartTime = 0;
    private static final long MIN_PLAY_DURATION_BEFORE_PUSH = 0; // 至少播放0秒再推送
    private static final long YSC_MIN_PLAY_DURATION_BEFORE_PUSH = 3000; // 至少播放0秒再推送
    private static final long FORCE_PUSH_TIMEOUT = 10000; // 10秒后强制推送
    private static final long MAX_WAIT_FOR_PLAYBACK = 15000; // 最多等待15秒

    // View ID 生成器（兼容低版本Android）
    private static int nextViewId = 10000;

    // 延迟推送队列
    private static class PendingPush {
        DanmakuItem danmakuItem;
        Activity activity;
        String title;
        long scheduleTime;
        boolean isForcedPush = false; // 标记是否为强制推送

        PendingPush(DanmakuItem danmakuItem, Activity activity, String title, long scheduleTime) {
            this.danmakuItem = danmakuItem;
            this.activity = activity;
            this.title = title;
            this.scheduleTime = scheduleTime;
        }
    }

    private static final Map<String, PendingPush> pendingPushes = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastPushTime = new ConcurrentHashMap<>();

    private static boolean isFirstDetection = true;

    // Hook相关
    private static Timer hookTimer;
    private static volatile boolean isMonitoring = false;
    private static Timer playbackCheckTimer;
    private static LocalServerSocket hookProcessSocket;
    private static boolean hookProcessLockDeniedLogged;
    
    // 用于确保run方法串行执行的锁
    private static final Object runLock = new Object();
    private static final Gson MEDIA_GSON = new Gson();

    // 主线程Handler
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Runnable delayedPushTask = null;

    private static boolean isLeoButtonInjected = false;
    private static volatile long lastMenuScanLogAt;
    private static final AtomicBoolean menuScanPending = new AtomicBoolean();
    private static final DanmakuMenuRelabeler menuRelabeler =
            new DanmakuMenuRelabeler(mainHandler, DanmakuScanner::buildDanmakuLabel);
    private static final Runnable MENU_SCAN_TASK = () -> {
        try {
            Activity activity = Utils.getTopActivity();
            if (isMonitoring && activity != null && !activity.isFinishing()
                    && !activity.isDestroyed() && !PlayerToolbarProbe.isDiagnose(activity)) {
                relabelDanmakuMenu(activity);
            } else {
                menuScanPending.set(false);
            }
        } catch (Exception e) {
            menuRelabeler.cancel();
            menuScanPending.set(false);
            DanmakuSpider.log("❌ 弹幕菜单重标记异常: " + e.getMessage());
        }
    };
    private static final String EPISODE_TITLE_TAG = "episode_title_overlay";
    private static volatile WeakReference<TextView> episodeTitleOverlay =
            new WeakReference<>(null);
    private static volatile String episodeTitleText = "";
    private static android.graphics.Bitmap episodeTitleLogo;
    private static volatile String episodeTitleRequestSignature = "";
    private static volatile long episodeTitleRetryAt;
    private static volatile boolean episodeTitleRequestPending;
    private static volatile boolean episodeTitleEligible = false;
    private static volatile boolean episodeTitleToolbarVisible = false;
    private static volatile boolean episodeTitleToolbarSuppressed = false;
    private static volatile boolean episodeTitlePaused = false;
    private static volatile long episodeTitleHideAt = 0;
    private static volatile WeakReference<View> episodeTitleControl =
            new WeakReference<>(null);
    private static volatile boolean episodeTitleVisibilityWatcherRunning = false;
    private static volatile WeakReference<Activity> playerToolbarActivity =
            new WeakReference<>(null);
    private static volatile WeakReference<ViewGroup> playerToolbarParent =
            new WeakReference<>(null);
    private static volatile WeakReference<View> playerToolbarAnchor =
            new WeakReference<>(null);
    private static volatile WeakReference<View[]> playerToolbarArrangement =
            new WeakReference<>(null);
    private static volatile int playerToolbarChildCount = -1;
    private static final long EPISODE_TITLE_VISIBLE_MS = 5000;
    private static final long EPISODE_TITLE_TOOLBAR_POLL_MS = 150;
    private static final float EPISODE_TITLE_BASE_SIZE_SP = 40.5f;
    private static final float EPISODE_TITLE_LINE_SCALE = 0.80f;
    private static final float EPISODE_TITLE_NAME_SCALE = 0.5265f;
    private static final float EPISODE_TITLE_ALPHA = 0.90f;
    private static final int EPISODE_TITLE_LOGO_HEIGHT_PX = 140;
    private static final int EPISODE_TITLE_LOGO_WIDTH_PX = 470;
    private static final Runnable HIDE_EPISODE_TITLE_TASK = () -> {
        episodeTitleHideAt = 0;
        updateEpisodeTitleVisibility();
    };
    private static final Runnable EPISODE_TITLE_VISIBILITY_WATCHER = new Runnable() {
        @Override
        public void run() {
            TextView title = episodeTitleOverlay.get();
            View control = episodeTitleControl.get();
            if (title == null || control == null) {
                episodeTitleVisibilityWatcherRunning = false;
                return;
            }
            boolean visible = isEpisodeTitleToolbarVisible(control);
            updateEpisodeTitleToolbarVisibility(visible);
            mainHandler.postDelayed(this, EPISODE_TITLE_TOOLBAR_POLL_MS);
        }
    };

    // 启动Hook监控
    public static void startHookMonitor() {
        if (hookTimer != null || isMonitoring) {
            DanmakuSpider.log("⚠️ Hook监控已在运行中");
            return;
        }
        if (!acquireHookProcessLock()) {
            if (!hookProcessLockDeniedLogged) {
                hookProcessLockDeniedLogged = true;
                DanmakuSpider.log("Hook监控已由另一个插件实例启动，跳过重复实例");
            }
            return;
        }

        DanmakuSpider.log("🚀 启动Hook监控");
        DanmakuSpider.log("弹幕菜单扫描 BUILD " + DanmakuMenuRelabeler.BUILD);
        isMonitoring = true;
        isFirstDetection = true;

        hookTimer = new Timer("DanmakuHookTimer", true);
        hookTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (runLock) {
                    try {
                        Activity act = Utils.getTopActivity();
                        if (act != null && !act.isFinishing()) {
                            boolean diagnoseMode = PlayerToolbarProbe.isDiagnose(act);
                            // 重标记播放器弹幕菜单项（用剧集名替换URL显示），任意界面都扫描
                            if (!diagnoseMode && menuScanPending.compareAndSet(false, true)) {
                                if (!mainHandler.post(MENU_SCAN_TASK)) menuScanPending.set(false);
                            }

                            // 检查是否是播放界面
                            String className = act.getClass().getName().toLowerCase();
                            if (isPlayerActivity(className)) {
//                            DanmakuSpider.log("[Monitor] 检测到播放界面: " + className);

                                ChapterManager.startAutoSkip(act);
                                if (diagnoseMode) {
                                    mainHandler.post(() -> {
                                        PlayerToolbarController.release(act);
                                        PlayerToolbarProbe.probe(act, "player-scan");
                                    });
                                } else {
                                    mainHandler.post(() -> {
                                        try {
                                            HostDanmakuGate.observe(act);
                                            syncPlayerToolbar(act);
                                        } catch (Exception e) {
                                            DanmakuSpider.log("播放器功能栏同步异常: " + e.getMessage());
                                        }
                                    });

                                    // 注入Leo弹幕按钮0
                                    if (!isLeoButtonInjected) {
                                        mainHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    injectLeoButton(act);
                                                } catch (Exception e) {
                                                    DanmakuSpider.log("❌ 按钮注入异常: " + e.getMessage());
                                                }
                                            }
                                        });
                                    }
                                }

                                // 检查播放状态
//                            checkPlaybackStatus(act);

                                // Hook获取标题
//                            String newTitle = extractTitleFromView(act.getWindow().getDecorView());
                                Media media = getMedia();
                                if (media == null) return;

                                if (TextUtils.isEmpty(media.getUrl())) {
                                    return;
                                }

                                isVideoPlaying = media.isPlaying();
                                updateEpisodeTitlePlaybackState(media);
                                // Display metadata is independent of automatic danmaku search,
                                // including when playback is paused before the first scan.
                                lastEpisodeInfo = getEpisodeInfo(media, act);
                                String titleSignature = lastEpisodeInfo.getSeriesName() + "|"
                                        + lastEpisodeInfo.getEpisodeSeasonNum() + "|"
                                        + lastEpisodeInfo.getEpisodeNum() + "|" + media.getUrl();
                                if (!titleSignature.equals(episodeTitleRequestSignature)) {
                                    PlayerToolbarController.onMediaChanged(act);
                                }
                                updateEpisodeTitleAsync(act, lastEpisodeInfo, titleSignature);

                                if (isVideoPlaying) {
                                    stopConfirmCount = 0;
                                    // 获取媒体信息
                                    ChapterManager.requestAutoMarkers(act, media);

                                    // 视频开始播放
                                    videoPlayStartTime = System.currentTimeMillis();
//                                    DanmakuSpider.log("▶️ 检测到视频开始播放");
                                } else {
                                    if (stopConfirmCount < 4) stopConfirmCount++;
                                    if (stopConfirmCount < 3) {
                                        return;
                                    }
                                    // 视频停止播放
                                    if (stopConfirmCount == 3)
                                        DanmakuSpider.log("⏸️ 检测到视频停止播放");

                                    // 清空缓存和队列
                                    pendingPushes.clear();
                                    lastPushTime.clear();

                                    return;
                                }

                                DanmakuConfig config = DanmakuConfigManager.getConfig(act);

                                // 检测是否开启自动查询或者已经手动查询过
                                if (!config.isAutoPushEnabled() && TextUtils.isEmpty(DanmakuManager.lastManualDanmakuUrl)) {
                                    return;
                                }

                                processDetectedTitle(act);
                            } else {
                                // 不在播放界面，重置播放状态
                                resetPlaybackStatus();

//                            DanmakuSpider.log("不在播放界面，重置播放状态");
                            }
                        }
                    } catch (Exception e) {
                        DanmakuSpider.log("❌ Hook监控异常: " + e.getMessage());
                    }
                }
            }
        }, 2000, 1000);

        // 启动播放状态检查定时器
        startPlaybackCheckTimer();
    }

    private static boolean acquireHookProcessLock() {
        if (hookProcessSocket != null) return true;
        Context context = Init.context();
        if (context == null) {
            Activity activity = Utils.getTopActivity();
            context = activity == null ? null : activity.getApplicationContext();
        }
        if (context == null) return false;
        String name = context.getPackageName() + ".danmaku.hook";
        try {
            hookProcessSocket = new LocalServerSocket(name);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static EpisodeInfo getEpisodeInfo(Media media, Activity act) {
        MediaIdentityResolver.Identity identity = MediaIdentityResolver.resolve(
                media.getTitle(), media.getArtist(), media.getUrl());
        String episodeNum = TextUtils.isEmpty(identity.episodeToken)
                ? (identity.episode > 0 ? String.valueOf(identity.episode) : "")
                : identity.episodeToken;
        String seasonNum = identity.season > 0 ? String.valueOf(identity.season) : "";
        List<String> episodeNames = new ArrayList<>();
        String cachedName = SharedPreferencesService.getSearchKeywordCache(
                act, identity.displayTitle);

        if (!TextUtils.isEmpty(cachedName) && !cachedName.equals(media.getTitle())) {
            episodeNames.add(cachedName);
        }
        for (String name : identity.searchNames) {
            if (!TextUtils.isEmpty(name) && !episodeNames.contains(name)) {
                episodeNames.add(name);
            }
        }
        if (!TextUtils.isEmpty(media.getTitle()) && !episodeNames.contains(media.getTitle())) {
            episodeNames.add(media.getTitle());
        }

        EpisodeInfo episodeInfo = new EpisodeInfo();
        episodeInfo.setEpisodeNum(episodeNum);
        episodeInfo.setEpisodeNames(episodeNames);
        episodeInfo.setEpisodeYear(identity.year);
        episodeInfo.setEpisodeSeasonNum(seasonNum);
        episodeInfo.setSeriesName(identity.seriesTitle);
        episodeInfo.setFileName(identity.fileName);
        episodeInfo.setOriginalTitle(identity.originalTitle);
        episodeInfo.setEpisodeUrl(media.getUrl());

        return episodeInfo;
    }

    public static String extractEpisodeNum(String title) {
        return MediaIdentityResolver.extractEpisodeToken(title);
    }

    @Nullable
    private static Media getMedia() {
        String mediaJson = fetchMediaJson();
        if (!isMediaJson(mediaJson)) {
            Utils.invalidatePort();
            mediaJson = fetchMediaJson();
        }

        if (!isMediaJson(mediaJson) || mediaJson.trim().equals("{}")) {
            return FongMiNativeHelper.getMedia(Utils.getTopActivity());
        }
//                            DanmakuSpider.log("[Monitor] mediaJson: " + mediaJson);

        Media media = MEDIA_GSON.fromJson(mediaJson, Media.class);

        // 兼容影视仓
        if (TextUtils.isEmpty(media.getArtist())) {
            media.setArtist(media.getTitle());
        }

        return media;
    }

    private static String fetchMediaJson() {
        try {
            return OkHttp.string("http://127.0.0.1:" + Utils.getPort() + "/media");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isMediaJson(String json) {
        if (TextUtils.isEmpty(json)) return false;
        String trimmed = json.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    // 启动播放状态检查定时器
    private static void startPlaybackCheckTimer() {
        if (playbackCheckTimer != null) {
            playbackCheckTimer.cancel();
        }

        playbackCheckTimer = new Timer("PlaybackCheckTimer", true);
        playbackCheckTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    // 检查是否有待推送的任务
//                    DanmakuSpider.log("检查待推送的任务数量：" + pendingPushes.size());
                    if (!pendingPushes.isEmpty()) {
                        checkAndExecutePendingPushes();
                    }
                } catch (Exception e) {
                    DanmakuSpider.log("❌ 播放检查定时器异常: " + e.getMessage());
                }
            }
        }, 2000, 2000); // 2秒后开始，每2秒检查一次
    }

    // 检查播放状态
    private static void checkPlaybackStatus(Activity activity) {
        try {
            // 这里可以尝试多种方法检测播放状态
            View root = activity.getWindow().getDecorView();
            boolean wasPlaying = isVideoPlaying;

            // 方法1：检查播放器控件
            isVideoPlaying = checkIfVideoIsPlaying(root);

            // 方法2：如果没有找到播放控件，假设视频在播放（避免无法检测的情况）
//            if (!isVideoPlaying && !pendingPushes.isEmpty()) {
//                // 如果有待推送任务且未检测到播放状态，假设视频在播放
//                // 这样可以避免因播放状态检测失败而卡住
//                DanmakuSpider.log("⚠️ 未检测到播放控件，假设视频在播放");
//                isVideoPlaying = true;
//                videoPlayStartTime = System.currentTimeMillis();
//            }

            if (isVideoPlaying && !wasPlaying) {
                stopConfirmCount = 0;
                // 视频开始播放
                videoPlayStartTime = System.currentTimeMillis();
                DanmakuSpider.log("▶️ 检测到视频开始播放");
            } else if (!isVideoPlaying && wasPlaying) {
                stopConfirmCount = 0;
                // 视频停止播放
                DanmakuSpider.log("⏸️ 检测到视频停止播放");
                DanmakuManager.currentVideoSignature = "";
                DanmakuManager.lastVideoDetectedTime = 0;
                DanmakuManager.lastDanmakuId = -1;
                DanmakuSpider.resetAutoSearch();
                currentSeriesName = "";
                currentEpisodeNum = "";
                lastEpisodeChangeTime = 0;
                videoPlayStartTime = 0;

                // 清空缓存和队列
                pendingPushes.clear();
                lastPushTime.clear();
            }
        } catch (Exception e) {
            // 忽略检查错误，假设视频在播放
            if (!pendingPushes.isEmpty()) {
                DanmakuSpider.log("⚠️ 播放状态检查异常，假设视频在播放");
                isVideoPlaying = true;
                videoPlayStartTime = System.currentTimeMillis();
            }
        }
    }

    // 检查视频是否在播放
    private static boolean checkIfVideoIsPlaying(View view) {
        // 查找播放器控制UI元素
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText().toString().toLowerCase();
            // 播放器可能显示当前播放时间，如"01:23 / 45:67"
            if (text.matches("\\d{1,2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}") ||
                    text.matches("\\d{1,2}:\\d{2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}:\\d{2}")) {
                return true;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (checkIfVideoIsPlaying(group.getChildAt(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    // 重置播放状态
    private static void resetPlaybackStatus() {
        DanmakuManager.currentVideoSignature = "";
        DanmakuManager.lastVideoDetectedTime = 0;
        DanmakuManager.lastDanmakuId = -1;
        DanmakuManager.lastManualDanmakuUrl = "";
        DanmakuManager.lastAutoDanmakuUrl = "";

        currentSeriesName = "";
        currentEpisodeNum = "";
        lastEpisodeChangeTime = 0;
        isVideoPlaying = false;
        stopConfirmCount = 0;
        videoPlayStartTime = 0;
        isLeoButtonInjected = false;
        hideEpisodeTitleOverlay();
    }

    // 检查并执行待推送任务
    private static void checkAndExecutePendingPushes() {
        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, PendingPush> entry : pendingPushes.entrySet()) {
            PendingPush push = entry.getValue();
            String key = entry.getKey();

            // 计算等待时间
            long waitTime = currentTime - push.scheduleTime;

            // 检查是否超时（超过最大等待时间）
            if (waitTime > MAX_WAIT_FOR_PLAYBACK) {
                DanmakuSpider.log("⏰ 推送任务超时（" + waitTime + "ms > " + MAX_WAIT_FOR_PLAYBACK + "ms），取消: " + key);
                pendingPushes.remove(key);
                continue;
            }

            // 检查是否达到强制推送时间（10秒）
            boolean shouldForcePush = waitTime >= FORCE_PUSH_TIMEOUT && !push.isForcedPush;

            if (shouldForcePush) {
                DanmakuSpider.log("🚨 等待" + waitTime + "ms未检测到播放状态，强制推送: " + key);
                push.isForcedPush = true;
                executePendingPush(push, true);
                pendingPushes.remove(key);
                continue;
            }

            Media media = getMedia();
            if (media == null) {
                DanmakuSpider.log("❌ 无法获取当前播放媒体信息，取消推送: " + key);
                pendingPushes.remove(key);
                continue;
            }
            // 检查视频是否在播放
            if (media.isPlaying()) {
                if (media.getState() != null) {
                    // 检查是否播放了足够长时间
                    long playDuration = currentTime - videoPlayStartTime;
                    if (playDuration >= MIN_PLAY_DURATION_BEFORE_PUSH) {
                        DanmakuSpider.log("✅ 视频已播放" + playDuration + "ms，执行推送: " + key);
                        executePendingPush(push, false);
                        pendingPushes.remove(key);
                    } else {
                        DanmakuSpider.log("⏳ 视频播放中(" + playDuration + "ms)，等待达到" + MIN_PLAY_DURATION_BEFORE_PUSH + "ms");
                    }
                } else {
                    // 检查是否播放了足够长时间
                    long playDuration = currentTime - videoPlayStartTime;
                    if (playDuration >= YSC_MIN_PLAY_DURATION_BEFORE_PUSH) {
                        DanmakuSpider.log("✅ 视频已播放" + playDuration + "ms，执行推送: " + key);
                        executePendingPush(push, false);
                        pendingPushes.remove(key);
                    } else {
                        DanmakuSpider.log("⏳ 视频播放中(" + playDuration + "ms)，等待达到" + YSC_MIN_PLAY_DURATION_BEFORE_PUSH + "ms");
                    }
                }
            } else {
                DanmakuSpider.log("⏸️ 视频未播放，已等待" + waitTime + "ms，还剩" + (FORCE_PUSH_TIMEOUT - waitTime) + "ms将强制推送");
            }
        }
    }

    // 执行待推送任务
    private static void executePendingPush(PendingPush push, boolean isForced) {
        if (push.activity == null || push.activity.isFinishing()) {
            DanmakuSpider.log("⚠️ Activity无效，取消推送");
            return;
        }

        DanmakuSpider.log("🚀 开始执行" + (isForced ? "强制" : "") + "推送: " + push.danmakuItem.getDanmakuUrl());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LeoDanmakuService.pushDanmakuDirect(push.danmakuItem, push.activity, true);

                    // 记录推送时间，防止重复推送
                    lastPushTime.put(push.danmakuItem.getDanmakuUrl(), System.currentTimeMillis());
                } catch (Exception e) {
                    DanmakuSpider.log("❌ 推送失败: " + e.getMessage());
                }
            }
        }).start();
    }

    // 停止Hook监控
    public static void stopHookMonitor() {
        isMonitoring = false;
        mainHandler.removeCallbacks(MENU_SCAN_TASK);
        mainHandler.post(() -> {
            menuRelabeler.cancel();
            menuScanPending.set(false);
        });

        // 取消延迟任务
        if (delayedPushTask != null) {
            mainHandler.removeCallbacks(delayedPushTask);
            delayedPushTask = null;
        }

        if (hookTimer != null) {
            hookTimer.cancel();
            hookTimer = null;
        }

        if (playbackCheckTimer != null) {
            playbackCheckTimer.cancel();
            playbackCheckTimer = null;
        }

        // 清空缓存和队列
        pendingPushes.clear();
        lastPushTime.clear();
        isLeoButtonInjected = false;
        hideEpisodeTitleOverlay();

        DanmakuSpider.log("🛑 Hook监控已停止");
    }

    // 判断是否为播放界面
    static boolean isPlayerActivity(String className) {
        return className.contains("videoactivity") || className.contains("detailactivity");

//        DanmakuSpider.log("className: " + className);

//        return className.contains("videoactivity") ||
//                className.contains("playeractivity") ||
//                className.contains("ijkplayer") ||
//                className.contains("exoplayer");

//        return className.contains("player") || className.contains("video") ||
//                className.contains("detail") || className.contains("play") ||
//                className.contains("media") || className.contains("movie") ||
//                className.contains("tv") || className.contains("film");
    }

    // 处理检测到的标题
    private static void processDetectedTitle(Activity activity) {
        // 生成视频签名
        String newSignature = generateSignature(lastEpisodeInfo);

//        DanmakuSpider.log("🔑 视频签名: " + newSignature);

        // 检查是否为同一个视频
        boolean isSameVideo = isSameVideo(DanmakuManager.currentVideoSignature, newSignature);;

        if (!isSameVideo) {
            // 不同的视频
            PlayerToolbarController.onMediaChanged(activity);
            DanmakuManager.currentVideoSignature = newSignature;
            DanmakuManager.lastVideoDetectedTime = System.currentTimeMillis();

            handleEpisodeChange(activity);
        } else {
            // 同一个视频，忽略重复触发
//            DanmakuSpider.log("✅ 检测到同一个视频，忽略重复触发");
        }
    }

    // 生成签名
    private static String generateSignature(EpisodeInfo episodeInfo) {
        return episodeInfo.getEpisodeUrl();
    }

    // 判断是否为同一个视频
    private static boolean isSameVideo(String sig1, String sig2) {
        if (TextUtils.isEmpty(sig1) || TextUtils.isEmpty(sig2)) {
            return false;
        }
        return sig1.equals(sig2);
    }

    // 处理换集检测
    private static void handleEpisodeChange(Activity activity) {
        long currentTime = System.currentTimeMillis();

        // 检查是否有有效的剧集名
        if (lastEpisodeInfo.getEpisodeNames() == null || lastEpisodeInfo.getEpisodeNames().isEmpty()) {
            DanmakuSpider.log("⚠️ 未检测到剧集名，不进行换集检测");
            return;
        }
        boolean hasEpisode = !TextUtils.isEmpty(lastEpisodeInfo.getEpisodeNum());
        if (!hasEpisode) {
            DanmakuSpider.log("⚠️ 未检测到集数，尝试不带集数搜索");
        }

        // 检查是否是同一个剧集系列
        boolean isSameSeries = isSameSeries(currentSeriesName, lastEpisodeInfo.getSeriesName());

        if (isSameSeries) {
            long timeSinceLastChange = currentTime - lastEpisodeChangeTime;

            DanmakuSpider.log("🔄 检测到同系列换集: " + currentEpisodeNum + " -> " + lastEpisodeInfo.getEpisodeNum());
            DanmakuSpider.log("⏰ 距离上次换集: " + timeSinceLastChange + "ms");
            videoPlayStartTime = System.currentTimeMillis();

            // 更新记录
            currentEpisodeNum = lastEpisodeInfo.getEpisodeNum();
            lastEpisodeChangeTime = currentTime;

            iterativeAutoSearch(lastEpisodeInfo, activity);
        } else {
            // 不同的剧集系列，更新记录
            DanmakuSpider.log("🎬 剧集名: " + lastEpisodeInfo.getEpisodeNames().get(0) + ", 年份: " + lastEpisodeInfo.getEpisodeYear() + ", 季数: " + lastEpisodeInfo.getEpisodeSeasonNum() + ", 集数: " + lastEpisodeInfo.getEpisodeNum());

            currentSeriesName = lastEpisodeInfo.getSeriesName();
            currentEpisodeNum = lastEpisodeInfo.getEpisodeNum();
            lastEpisodeChangeTime = currentTime;

            startAutoSearch(lastEpisodeInfo, activity);
        }
    }

    // 判断是否为同一个剧集系列
    private static boolean isSameSeries(String series1, String series2) {
        if (TextUtils.isEmpty(series1) || TextUtils.isEmpty(series2)) {
            return false;
        }

        // 简单的相似性检查
        String s1 = MediaIdentityResolver.normalizeForCompare(series1);
        String s2 = MediaIdentityResolver.normalizeForCompare(series2);

        // 如果完全相同
        if (s1.equals(s2)) {
            return true;
        }

        // 检查一个是否包含另一个
        if (s1.contains(s2) || s2.contains(s1)) {
            return true;
        }

        // 计算编辑距离（简化版）
        int minLength = Math.min(s1.length(), s2.length());
        int maxLength = Math.max(s1.length(), s2.length());

        if (minLength == 0) return false;

        // 计算相同字符比例
        int sameChars = 0;
        for (int i = 0; i < Math.min(s1.length(), s2.length()); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                sameChars++;
            }
        }

        double similarity = (double) sameChars / maxLength;
        return similarity > 0.7; // 相似度超过70%认为是同一系列
    }

    // 安排延迟推送
    private static void scheduleDelayedPush(DanmakuItem item, Activity activity, String title, String pushKey) {
        DanmakuSpider.log("⏰ 安排延迟推送: " + pushKey);
        DanmakuSpider.log("   item: " + item.toString());
        DanmakuSpider.log("   等待视频播放后再推送（最多等待" + FORCE_PUSH_TIMEOUT/1000 + "秒）...");

        // 添加到待推送队列
        PendingPush pendingPush = new PendingPush(item, activity, title, System.currentTimeMillis());
        pendingPushes.put(pushKey, pendingPush);
    }

    // === 修改后的按钮注入逻辑 ===

    // 重标记播放器弹幕菜单项：将弹幕源URL文本替换为"剧名 集标题"
    private static void relabelDanmakuMenu(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (PlayerToolbarProbe.isDiagnose(activity)) return;
        java.util.List<View> collected = new java.util.ArrayList<>();
        String routes = "";

        // 路A: WindowManagerGlobal.getWindowViews()
        try {
            Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = wmgCls.getMethod("getInstance").invoke(null);
            java.util.List<View> views = (java.util.List<View>) wmgCls.getMethod("getWindowViews").invoke(wmg);
            if (views != null && !views.isEmpty()) {
                collected.addAll(views);
                routes += "A";
            }
        } catch (Exception e) {
            logMenuScanThrottled("[弹幕菜单扫描] 路A失败: " + e);
        }

        // A already returns all windows; alternate routes are fallbacks, not extra scans.
        // 路B: 反射 WindowManagerGlobal.mViews 字段
        if (collected.isEmpty()) try {
            Class<?> wmgCls = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = wmgCls.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmgCls.getDeclaredField("mViews");
            f.setAccessible(true);
            java.util.List<View> views = (java.util.List<View>) f.get(wmg);
            if (views != null && !views.isEmpty()) {
                collected.addAll(views);
                routes += "B";
            }
        } catch (Exception e) {
            logMenuScanThrottled("[弹幕菜单扫描] 路B失败: " + e);
        }

        // 路C: WindowManagerImpl.getDefault().mGlobal.mViews
        if (collected.isEmpty()) try {
            Class<?> wmCls = Class.forName("android.view.WindowManagerImpl");
            Object wm = wmCls.getMethod("getDefault").invoke(null);
            java.lang.reflect.Field globalF = wmCls.getDeclaredField("mGlobal");
            globalF.setAccessible(true);
            Object global = globalF.get(wm);
            java.lang.reflect.Field viewsF = global.getClass().getDeclaredField("mViews");
            viewsF.setAccessible(true);
            java.util.List<View> views = (java.util.List<View>) viewsF.get(global);
            if (views != null && !views.isEmpty()) {
                collected.addAll(views);
                routes += "C";
            }
        } catch (Exception e) {
            logMenuScanThrottled("[弹幕菜单扫描] 路C失败: " + e);
        }

        // 路D: 反射遍历顶层Activity的DialogFragment弹窗（FongMi系 DanmakuDialog 是 BottomSheetDialogFragment）
        if (collected.isEmpty()) try {
            java.lang.reflect.Method getSfm;
            try {
                getSfm = activity.getClass().getMethod("getSupportFragmentManager");
            } catch (NoSuchMethodException nsme) {
                getSfm = null;
            }
            if (getSfm != null) {
                Object fm = getSfm.invoke(activity);
                if (fm != null) {
                    java.lang.reflect.Method getFragments = fm.getClass().getMethod("getFragments");
                    Object fragList = getFragments.invoke(fm);
                    if (fragList instanceof java.util.List) {
                        for (Object f : (java.util.List<?>) fragList) {
                            if (f == null) continue;
                            java.lang.reflect.Method getDialog;
                            try {
                                getDialog = f.getClass().getMethod("getDialog");
                            } catch (NoSuchMethodException nsme2) {
                                getDialog = (f.getClass().getSuperclass() != null) ? f.getClass().getSuperclass().getMethod("getDialog") : null;
                            }
                            if (getDialog == null) continue;
                            Object dialog = getDialog.invoke(f);
                            if (dialog instanceof android.app.Dialog) {
                                android.app.Dialog d = (android.app.Dialog) dialog;
                                if (d.getWindow() != null) {
                                    View decor = d.getWindow().getDecorView();
                                    if (decor != null) {
                                        collected.add(decor);
                                        routes += "D";
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logMenuScanThrottled("[弹幕菜单扫描] 路D失败: " + e);
        }

        // 路E: 兜底Activity根视图
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor != null) {
                collected.add(decor);
                routes += "E";
            }
        } catch (Exception ignore) {
        }

        // 按身份去重（IdentityHashMap）
        java.util.Map<View, Boolean> uniq = new java.util.IdentityHashMap<>();
        for (View v : collected) if (v != null) uniq.put(v, Boolean.TRUE);
        java.util.List<View> windowViews = new java.util.ArrayList<>(uniq.keySet());

        logMenuScanThrottled("🔍 [弹幕菜单扫描] 窗口数: "
                + windowViews.size() + " (路由: " + routes + ")");
        for (View root : windowViews) {
            logMenuScanThrottled("[弹幕菜单扫描] 窗口类: " + (root.getClass().getName())
                    + " 子数: " + (root instanceof ViewGroup
                    ? ((ViewGroup) root).getChildCount() : 0));
        }
        menuRelabeler.start(windowViews, () -> menuScanPending.set(false));
    }

    private static void logMenuScanThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastMenuScanLogAt < 60000) return;
        lastMenuScanLogAt = now;
        DanmakuSpider.log(message);
    }

    // 根据epId生成友好的剧集标签
    private static String buildDanmakuLabel(String epIdStr) {
        try {
            int epId = Integer.parseInt(epIdStr);
            DanmakuItem item = DanmakuManager.lastDanmakuItemMap.get(epId);
            if (item != null) {
                String anime = !TextUtils.isEmpty(item.getAnimeTitle()) ? item.getAnimeTitle()
                        : (!TextUtils.isEmpty(item.getTitle())
                        ? (item.getTitle().contains("from")
                        ? item.getTitle().substring(0, item.getTitle().indexOf("from")).trim()
                        : item.getTitle().trim())
                        : "");
                String ep = !TextUtils.isEmpty(item.getEpTitle()) ? item.getEpTitle() : ("第" + epId + "集");
                return TextUtils.isEmpty(anime) ? ep : anime + " " + ep;
            }
            EpisodeInfo info = lastEpisodeInfo;
            if (info != null) {
                StringBuilder sb = new StringBuilder();
                if (!TextUtils.isEmpty(info.getSeriesName())) sb.append(info.getSeriesName());
                String season = info.getEpisodeSeasonNum();
                String ep = info.getEpisodeNum();
                if (!TextUtils.isEmpty(season) && !TextUtils.isEmpty(ep)) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append("S").append(season).append("E").append(ep);
                } else if (!TextUtils.isEmpty(ep)) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append("第").append(ep).append("集");
                }
                if (sb.length() > 0) return sb.toString();
            }
        } catch (Exception e) {
            // 忽略解析异常
        }
        return null;
    }

    private static void injectLeoButton(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (PlayerToolbarProbe.isDiagnose(activity)) return;
        if (PlayerToolbarController.suppressLegacy(activity)) return;

//        DanmakuSpider.log("[按钮注入] 开始尝试注入按钮");

        try {
            // 先检查是否已经有Leo弹幕按钮存在
            View root = activity.getWindow().getDecorView();
            View existing = root.findViewWithTag("danmu_button");
            if (existing != null) {
//                DanmakuSpider.log("[按钮注入] 按钮已存在，跳过");
                return;
            }

            TextView nativeDanmaku = findNativeDanmakuButton(root, 0);
            if (nativeDanmaku != null && nativeDanmaku.getParent() instanceof ViewGroup) {
                injectButton((ViewGroup) nativeDanmaku.getParent(), nativeDanmaku);
            } else {
                traverseForButton(root, 0);
            }
        } catch (Exception e) {
            DanmakuSpider.log("[按钮注入] 异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 轻量级遍历寻找按钮锚点
    private static void traverseForButton(View view, int depth) {
        if (view == null || depth > 15) {
            DanmakuSpider.log("[按钮注入] 遍历结束，没有找到按钮锚点");
            return; // 增加深度限制，播放器UI可能比较深
        }

        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0) {
                String text = cs.toString();
                // 寻找合适的锚点按钮
                if (text.equals("硬解") || text.equals("软解")) {
//                if (text.equals("硬解") || text.equals("软解") || text.equals("字幕") || text.equals("视轨") || text.equals("音轨")) {
                    DanmakuSpider.log("[按钮注入] 找到按钮锚点: " + text + " 深度: " + depth);
                    if (view.getParent() instanceof ViewGroup) {
                        injectButton((ViewGroup) view.getParent(), tv);
                        return; // 找到一个就返回
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // 修剪：跳过列表控件
            if (view instanceof android.widget.ListView ||
                    view.getClass().getName().contains("RecyclerView")) {
                return;
            }

            for (int i = 0; i < group.getChildCount(); i++) {
                traverseForButton(group.getChildAt(i), depth + 1);
            }
        }
    }

    // 注入Leo弹幕按钮（原版逻辑）
    private static void injectButton(ViewGroup parent, TextView anchor) {
        if (parent == null || anchor == null || PlayerToolbarProbe.isDiagnose(parent.getContext())) return;
        if (PlayerToolbarController.owns(parent.getContext())) return;
        try {
            View existing = parent.findViewWithTag("danmu_button");
            String anchorText = anchor.getText().toString();
            boolean isTargetAnchor = anchorText.contains("音轨") || "弹幕".equals(anchorText.trim());

            // 逻辑：如果已存在按钮
            if (existing != null) {
                // 如果当前锚点是"音轨"，我们重新定位按钮
                if (isTargetAnchor) {
                    ((ViewGroup) existing.getParent()).removeView(existing);
                    DanmakuSpider.log("[按钮注入] 移除旧按钮，准备重新注入");
                } else {
//                    DanmakuSpider.log("[按钮注入] 按钮已存在，跳过");
                    return; // 否则不重复添加
                }
            }

            if (isInRecyclerView(parent)) {
                DanmakuSpider.log("[按钮注入] 在RecyclerView中，跳过");
                return;
            }

            TextView btn;
            if (existing != null && isTargetAnchor) {
                btn = (TextView) existing; // 复用
            } else {
                // 创建新的Leo弹幕按钮
                btn = new TextView(parent.getContext());
                btn.setText("弹幕搜索");
                btn.setTag("danmu_button");
                btn.setTextColor(anchor.getTextColors());
                btn.setTextSize(0, anchor.getTextSize());
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(20, 10, 20, 10);
                btn.setSingleLine(true);

                // 修复焦点问题 - 添加必要的焦点设置
                btn.setFocusable(true);
                btn.setFocusableInTouchMode(false);
                btn.setClickable(true);

                // 设置背景（使用锚点的背景）
                if (anchor.getBackground() != null && anchor.getBackground().getConstantState() != null) {
                    btn.setBackground(anchor.getBackground().getConstantState().newDrawable());
                } else {
                    btn.setBackgroundColor(Color.parseColor("#4CAF50"));
                }
                applyToolbarFocusEffect(btn);

                // 按钮点击事件 - 这是核心注册逻辑
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (parent.getContext() instanceof Activity) {
                            Activity activity = (Activity) parent.getContext();
                            // 添加防抖动检查
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - DanmakuSpider.lastButtonClickTime < 500) {
                                DanmakuSpider.log("[按钮点击] 防抖动：点击过于频繁");
                                return;
                            }
                            DanmakuSpider.lastButtonClickTime = currentTime;

                            DanmakuSpider.log("[按钮点击] 打开搜索对话框");
                            DanmakuUIHelper.showSearchDialog(activity, lastEpisodeInfo);
                        }
                    }
                });

                // 设置长按事件（可选）
                btn.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (parent.getContext() instanceof Activity) {
                            Activity activity = (Activity) parent.getContext();
                            // 添加防抖动检查
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - DanmakuSpider.lastButtonClickTime < 500) {
                                DanmakuSpider.log("[按钮长按] 防抖动：操作过于频繁");
                                return true;
                            }
                            DanmakuSpider.lastButtonClickTime = currentTime;

                            DanmakuSpider.log("[按钮长按] 打开远程搜索二维码");
                            DanmakuUIHelper.showQRCodeDialog((Activity) parent.getContext(), "http://" + NetworkUtils.getLocalIpAddress() + ":9810");
                        }
                        return true;
                    }
                });
            }

            // 布局参数设置
            ViewGroup.LayoutParams anchorLp = anchor.getLayoutParams();
            ViewGroup.LayoutParams params = null;

            if (anchorLp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) anchorLp);
                llp.weight = 0;
                params = llp;
            } else if (anchorLp instanceof ViewGroup.MarginLayoutParams) {
                params = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) anchorLp);
            } else {
                params = new ViewGroup.LayoutParams(anchorLp);
            }

            // 插入位置逻辑
            int insertIndex = -1;
            boolean isInsertBefore = anchorText.equals("弹幕搜索") || anchorText.contains("搜索") || isTargetAnchor;
            int anchorIndex = parent.indexOfChild(anchor);

            if (isInsertBefore) {
                insertIndex = anchorIndex;
            } else {
                insertIndex = anchorIndex + 1;
            }

            // 如果按钮已经在正确位置，直接返回
            if (existing != null && parent.indexOfChild(existing) == insertIndex) {
                if (existing.getVisibility() != View.VISIBLE) existing.setVisibility(View.VISIBLE);
                return;
            }

            // 为RelativeLayout设置特殊规则
            if (parent instanceof android.widget.RelativeLayout) {
                android.widget.RelativeLayout.LayoutParams rlp = new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (anchor.getId() == View.NO_ID) {
                    // 使用兼容方式生成View ID
                    anchor.setId(generateViewId());
                }
                if (isInsertBefore) {
                    rlp.addRule(android.widget.RelativeLayout.LEFT_OF, anchor.getId());
                } else {
                    rlp.addRule(android.widget.RelativeLayout.RIGHT_OF, anchor.getId());
                }
                rlp.alignWithParent = true;
                rlp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
                params = rlp;
            }

            // 设置边距
            if (params instanceof ViewGroup.MarginLayoutParams) {
                int existingLeft = 0;
                int existingRight = 0;
                if (anchorLp instanceof ViewGroup.MarginLayoutParams) {
                    existingLeft = ((ViewGroup.MarginLayoutParams) anchorLp).leftMargin;
                    existingRight = ((ViewGroup.MarginLayoutParams) anchorLp).rightMargin;
                }

                // 如果原始边距太小，使用默认值
                if (existingLeft < 5 && existingRight < 5) {
                    existingLeft = 20;
                    existingRight = 20;
                }

                ((ViewGroup.MarginLayoutParams) params).leftMargin = existingLeft;
                ((ViewGroup.MarginLayoutParams) params).rightMargin = existingRight > 0 ? existingRight : existingLeft;
                ((ViewGroup.MarginLayoutParams) params).topMargin = 0;
                ((ViewGroup.MarginLayoutParams) params).bottomMargin = 0;
            }

            // 确保按钮在最顶层显示
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                btn.setElevation(10f); // 设置阴影层级，确保按钮在顶层
            }
            btn.bringToFront(); // 将按钮置于最前

            try {
                if (insertIndex >= 0 && insertIndex <= parent.getChildCount()) {
                    parent.addView(btn, insertIndex, params);
                } else {
                    parent.addView(btn, params);
                }

                // 重新请求布局
                parent.requestLayout();
                parent.post(new Runnable() {
                    @Override
                    public void run() {
                        // 确保按钮正确显示
                        btn.setVisibility(View.VISIBLE);
                        btn.setClickable(true);
                    }
                });

                DanmakuSpider.log("✅ Leo弹幕按钮注入成功");
                isLeoButtonInjected = true;
            } catch (Exception e) {
                DanmakuSpider.log("❌ 添加按钮失败: " + e.getMessage());
                parent.addView(btn);
            }
        } catch (Exception e) {
            DanmakuSpider.log("❌ 注入按钮异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void syncPlayerToolbar(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (PlayerToolbarProbe.isDiagnose(activity)) return;
        if (PlayerToolbarController.sync(activity)) {
            ViewGroup toolbar = PlayerToolbarController.toolbarRow(activity);
            if (toolbar != null) ensureEpisodeTitleOverlay(toolbar);
            return;
        }
        View root = activity.getWindow().getDecorView();
        if (root == null) return;

        ViewGroup cachedParent = playerToolbarParent.get();
        View cachedAnchor = playerToolbarAnchor.get();
        View[] cachedArrangement = playerToolbarArrangement.get();
        if (playerToolbarActivity.get() == activity
                && cachedParent != null
                && cachedAnchor != null
                && cachedArrangement != null
                && cachedParent.isAttachedToWindow()
                && cachedParent.getRootView() == root
                && cachedParent.getChildCount() == playerToolbarChildCount
                && cachedAnchor.getParent() == cachedParent) {
            boolean stillArranged = true;
            for (View view : cachedArrangement) {
                if (view == null || view.getParent() != cachedParent) {
                    stillArranged = false;
                    break;
                }
            }
            if (stillArranged) {
                ensureEpisodeTitleOverlay(cachedParent);
                return;
            }
        }

        removeInjectedView(root, "auto_button");
        removeInjectedView(root, "intro_marker_button");
        removeInjectedView(root, "outro_marker_button");
        ensureChapterButton(root);
        arrangePlayerButtons(root);
    }

    private static void ensureChapterButton(View root) {
        if (root == null || PlayerToolbarProbe.isDiagnose(root.getContext())) return;
        if (root == null || root.findViewWithTag("chapter_button") != null) return;
        View danmuButton = root.findViewWithTag("danmu_button");
        if (danmuButton != null && danmuButton.getParent() instanceof ViewGroup) {
            injectChapterButton((ViewGroup) danmuButton.getParent(), danmuButton);
            return;
        }
        TextView nativeDanmaku = findNativeDanmakuButton(root, 0);
        if (nativeDanmaku != null && nativeDanmaku.getParent() instanceof ViewGroup) {
            injectChapterButton((ViewGroup) nativeDanmaku.getParent(), nativeDanmaku);
        }
    }

    private static void injectChapterButton(ViewGroup parent, View anchor) {
        if (parent == null || anchor == null || parent.findViewWithTag("chapter_button") != null) return;
        if (PlayerToolbarProbe.isDiagnose(parent.getContext())) return;
        if (isInRecyclerView(parent)) return;
        try {
            TextView chapterButton = createToolbarButton(parent, anchor, "chapter_button", "章节");
            chapterButton.setOnClickListener(v -> {
                if (v.getContext() instanceof Activity) {
                    DanmakuUIHelper.showChapterDialog((Activity) v.getContext(), v);
                }
            });

            ViewGroup.LayoutParams params = anchor.getLayoutParams() != null
                    ? new ViewGroup.LayoutParams(anchor.getLayoutParams())
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            View resetButton = findDirectToolbarChildByIdName(parent, "reset");
            int anchorIndex = parent.indexOfChild(anchor);
            int insertIndex = resetButton != null
                    ? parent.indexOfChild(resetButton)
                    : anchorIndex + 1;
            parent.addView(chapterButton,
                    Math.min(Math.max(0, insertIndex), parent.getChildCount()), params);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                chapterButton.setElevation(10f);
            }
            parent.requestLayout();
            DanmakuSpider.log("章节按钮注入成功");
        } catch (Exception e) {
            DanmakuSpider.log("章节按钮注入失败: " + e.getMessage());
        }
    }

    private static void removeInjectedView(View root, String tag) {
        View view = root == null ? null : root.findViewWithTag(tag);
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static TextView findNativeDanmakuButton(View view, int depth) {
        if (view == null || depth > 15) return null;
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            Object tag = textView.getTag();
            if ("danmu_button".equals(tag) || "auto_button".equals(tag)
                    || "chapter_button".equals(tag)) return null;
            CharSequence value = textView.getText();
            if ("弹幕".equals(value == null ? "" : value.toString().trim())) return textView;
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (group.getClass().getName().contains("RecyclerView")) return null;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findNativeDanmakuButton(group.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView createToolbarButton(ViewGroup parent, View anchor,
                                                String tag, String text) {
        TextView button = new TextView(parent.getContext());
        button.setText(text);
        button.setTag(tag);
        if (anchor instanceof TextView) {
            TextView textAnchor = (TextView) anchor;
            button.setTextColor(textAnchor.getTextColors());
            button.setTextSize(0, textAnchor.getTextSize());
        } else {
            button.setTextColor(Color.WHITE);
            button.setTextSize(14);
        }
        button.setGravity(Gravity.CENTER);
        button.setPadding(20, 10, 20, 10);
        button.setSingleLine(true);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setClickable(true);
        if (anchor.getBackground() != null && anchor.getBackground().getConstantState() != null) {
            button.setBackground(anchor.getBackground().getConstantState().newDrawable());
        }
        applyToolbarFocusEffect(button);
        return button;
    }

    private static void applyToolbarFocusEffect(TextView button) {
        if (button == null) return;
        button.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.08f : 1f)
                    .scaleY(hasFocus ? 1.08f : 1f)
                    .setDuration(120).start();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                v.setElevation(hasFocus ? 14f : 8f);
            }
        });
    }

    private static void arrangePlayerButtons(View root) {
        if (root == null || PlayerToolbarProbe.isDiagnose(root.getContext())) return;
        TextView nativeDanmaku = findNativeDanmakuButton(root, 0);
        if (nativeDanmaku == null || !(nativeDanmaku.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) nativeDanmaku.getParent();
        if (parent.getClass().getName().contains("RecyclerView")
                || parent.getClass().getName().contains("RelativeLayout")) return;
        ensureEpisodeTitleOverlay(parent);

        List<View> ordered = new ArrayList<>();
        addToolbarButtonById(parent, ordered, "player", "IJK");
        addToolbarButtonById(parent, ordered, "decode", "硬解");
        addToolbarButtonById(parent, ordered, "scale", "原始");
        addToolbarButton(parent, ordered, null, "danmu_button");
        addToolbarButtonById(parent, ordered, "danmaku", "弹幕");
        addToolbarButtonById(parent, ordered, "text", "字幕");
        addToolbarButtonById(parent, ordered, "opening", "片头");
        addToolbarButtonById(parent, ordered, "ending", "片尾");
        addToolbarButtonById(parent, ordered, "audio", "音轨");
        addToolbarButtonById(parent, ordered, "video", "视轨");
        addToolbarButtonById(parent, ordered, "speed", "1.00");
        addToolbarButton(parent, ordered, null, "chapter_button");
        addToolbarButtonById(parent, ordered, "reset", "刷新");
        addToolbarButtonById(parent, ordered, "repeat", "循环");
        ensureTimeButtonWidth(findDirectToolbarChildByIdName(parent, "opening"));
        ensureTimeButtonWidth(findDirectToolbarChildByIdName(parent, "ending"));
        if (ordered.size() < 5) return;
        for (View view : ordered) {
            view.setFocusableInTouchMode(false);
        }

        int groupStart = Integer.MAX_VALUE;
        for (View view : ordered) {
            if (view.getParent() == parent) groupStart = Math.min(groupStart, parent.indexOfChild(view));
        }
        if (groupStart == Integer.MAX_VALUE) groupStart = parent.indexOfChild(nativeDanmaku);

        int horizontalGap = Math.max(1, Math.round(
                4f * parent.getResources().getDisplayMetrics().density));
        int expected = groupStart;
        boolean alreadyOrdered = true;
        for (View view : ordered) {
            if (view.getParent() != parent || parent.indexOfChild(view) != expected++) {
                alreadyOrdered = false;
                break;
            }
        }
        if (alreadyOrdered) {
            boolean spacingChanged = false;
            for (View view : ordered) {
                spacingChanged |= applyToolbarSpacing(view, horizontalGap);
            }
            if (spacingChanged) parent.requestLayout();
            cachePlayerToolbar(parent, nativeDanmaku, ordered);
            return;
        }

        for (View view : ordered) {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }
        int insertIndex = Math.min(Math.max(0, groupStart), parent.getChildCount());
        for (View view : ordered) {
            ViewGroup.LayoutParams params = copyButtonParams(view, nativeDanmaku);
            applyToolbarSpacing(params, horizontalGap);
            parent.addView(view, insertIndex++, params);
        }
        parent.requestLayout();
        cachePlayerToolbar(parent, nativeDanmaku, ordered);
    }

    private static void cachePlayerToolbar(ViewGroup parent, View anchor, List<View> ordered) {
        Activity activity = parent.getContext() instanceof Activity
                ? (Activity) parent.getContext() : null;
        playerToolbarActivity = new WeakReference<>(activity);
        playerToolbarParent = new WeakReference<>(parent);
        playerToolbarAnchor = new WeakReference<>(anchor);
        playerToolbarArrangement =
                new WeakReference<>(ordered.toArray(new View[0]));
        playerToolbarChildCount = parent.getChildCount();
    }

    private static void updateEpisodeTitleAsync(Activity activity, EpisodeInfo info,
                                                String signature) {
        if (activity == null || info == null) return;

        String baseText = buildEpisodeTitleText(info);
        if (TextUtils.isEmpty(signature)) return;
        if (signature.equals(episodeTitleRequestSignature)
                && (episodeTitleRequestPending || episodeTitleRetryAt == 0
                || System.currentTimeMillis() < episodeTitleRetryAt)) return;
        episodeTitleRequestSignature = signature;
        episodeTitleRequestPending = true;
        episodeTitleRetryAt = 0;
        episodeTitleEligible = false;
        episodeTitleHideAt = 0;
        episodeTitleText = "";
        episodeTitleLogo = null;
        mainHandler.removeCallbacks(HIDE_EPISODE_TITLE_TASK);
        mainHandler.post(() -> {
            if (!signature.equals(episodeTitleRequestSignature)) return;
            applyEpisodeTitleText();
            updateEpisodeTitleVisibility();
        });

        String title = !TextUtils.isEmpty(info.getSeriesName())
                ? info.getSeriesName()
                : (info.getEpisodeNames() != null && !info.getEpisodeNames().isEmpty()
                ? info.getEpisodeNames().get(0) : "");
        int episode = MediaIdentityResolver.parseEpisodeNumber(info.getEpisodeNum());
        int season = 1;
        try {
            if (!TextUtils.isEmpty(info.getEpisodeSeasonNum())) {
                season = Integer.parseInt(info.getEpisodeSeasonNum().trim());
            }
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(title) || episode <= 0) {
            episodeTitleRequestPending = false;
            mainHandler.post(() -> {
                if (!signature.equals(episodeTitleRequestSignature)) return;
                episodeTitleEligible = false;
                episodeTitleHideAt = 0;
                episodeTitleText = "";
                mainHandler.removeCallbacks(HIDE_EPISODE_TITLE_TASK);
                applyEpisodeTitleText();
                updateEpisodeTitleVisibility();
            });
            return;
        }

        final int requestSeason = season;
        final int requestEpisode = episode;
        final String requestSignature = signature;
        final String requestBaseText = baseText;
        android.content.Context appContext = activity.getApplicationContext();
        Thread thread = new Thread(() -> {
            AutoSkipService.EpisodeTitleMetadata result = null;
            try {
                result = AutoSkipService.fetchEpisodeTitleMetadata(
                        appContext, title, requestSeason, requestEpisode);
            } catch (Exception e) {
                android.util.Log.w("AuroraTitle", "Episode metadata request failed", e);
            }
            final AutoSkipService.EpisodeTitleMetadata metadata = result;
            mainHandler.post(() -> {
                if (!requestSignature.equals(episodeTitleRequestSignature)) return;
                episodeTitleRequestPending = false;
                episodeTitleRetryAt = metadata == null ? System.currentTimeMillis() + 30000 : 0;
                if (metadata == null || !metadata.episodic) {
                    android.util.Log.i("AuroraTitle", metadata == null
                            ? "Metadata unavailable; retry in 30s" : "Movie: episode overlay omitted");
                    episodeTitleEligible = false;
                    episodeTitleHideAt = 0;
                    episodeTitleText = "";
                    mainHandler.removeCallbacks(HIDE_EPISODE_TITLE_TASK);
                    applyEpisodeTitleText();
                    updateEpisodeTitleVisibility();
                    return;
                }
                episodeTitleEligible = true;
                android.util.Log.i("AuroraTitle", "Episode title ready S" + requestSeason + "E" + requestEpisode);
                episodeTitleText = TextUtils.isEmpty(metadata.title)
                        ? requestBaseText : requestBaseText + " " + metadata.title;
                applyEpisodeTitleText();
                showEpisodeTitleAfterChange();
                if (metadata.tmdbId > 0) {
                    WeakReference<Activity> owner = new WeakReference<>(activity);
                    SeriesLogoCache.request(appContext, metadata.tmdbId, bitmap -> {
                        Activity current = owner.get();
                        if (current == null || current.isFinishing() || current.isDestroyed()
                                || !requestSignature.equals(episodeTitleRequestSignature)) return;
                        episodeTitleLogo = bitmap;
                        applyEpisodeTitleText();
                        updateEpisodeTitleVisibility();
                        android.util.Log.i("AuroraTitle", bitmap == null
                                ? "Chinese logo unavailable; keep series text" : "Chinese series logo applied");
                    });
                }
            });
        }, "DanmakuEpisodeTitle");
        thread.setDaemon(true);
        thread.start();
    }

    private static String buildEpisodeTitleText(EpisodeInfo info) {
        if (info == null) return "";
        String series = !TextUtils.isEmpty(info.getSeriesName())
                ? info.getSeriesName()
                : (info.getEpisodeNames() != null && !info.getEpisodeNames().isEmpty()
                ? info.getEpisodeNames().get(0) : "");
        int episodeNumber = MediaIdentityResolver.parseEpisodeNumber(info.getEpisodeNum());
        String episode = episodeNumber > 0
                ? "第" + episodeNumber + "集"
                : (TextUtils.isEmpty(info.getEpisodeNum()) ? "" : info.getEpisodeNum().trim());
        if (TextUtils.isEmpty(series)) return episode;
        if (TextUtils.isEmpty(episode)) return series;
        return series + "\n           " + episode;
    }

    private static void applyEpisodeTitleText() {
        TextView view = episodeTitleOverlay.get();
        if (view == null) return;
        if (TextUtils.isEmpty(episodeTitleText)) {
            view.setText("");
            return;
        }
        SpannableStringBuilder styledText = new SpannableStringBuilder(episodeTitleText);
        int titleLineStart = episodeTitleText.indexOf('\n');
        if (titleLineStart < 0) {
            styledText.setSpan(new RelativeSizeSpan(EPISODE_TITLE_LINE_SCALE),
                    0, styledText.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            if (episodeTitleLogo != null) {
                android.graphics.drawable.BitmapDrawable logo = new android.graphics.drawable.BitmapDrawable(
                        view.getResources(), episodeTitleLogo);
                // Fixed pixel box; preserve lettering proportions and align it to the left.
                int maxWidth = Math.min(EPISODE_TITLE_LOGO_WIDTH_PX,
                        Math.max(1, view.getResources().getDisplayMetrics().widthPixels * 60 / 100));
                int availableWidth = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
                if (availableWidth > 0) maxWidth = Math.min(maxWidth, availableWidth);
                int maxHeight = EPISODE_TITLE_LOGO_HEIGHT_PX;
                float scale = Math.min((float) maxWidth / episodeTitleLogo.getWidth(),
                        (float) maxHeight / episodeTitleLogo.getHeight());
                int width = Math.max(1, Math.min(maxWidth, Math.round(episodeTitleLogo.getWidth() * scale)));
                int height = Math.max(1, Math.min(maxHeight, Math.round(episodeTitleLogo.getHeight() * scale)));
                int top = (maxHeight - height) / 2;
                android.graphics.drawable.InsetDrawable box = new android.graphics.drawable.InsetDrawable(
                        logo, 0, top, maxWidth - width, maxHeight - height - top);
                box.setBounds(0, 0, maxWidth, maxHeight);
                styledText.setSpan(new ImageSpan(box, ImageSpan.ALIGN_BOTTOM),
                        0, titleLineStart, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                styledText.setSpan(new RelativeSizeSpan(EPISODE_TITLE_LINE_SCALE),
                        0, titleLineStart, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            styledText.setSpan(new RelativeSizeSpan(EPISODE_TITLE_NAME_SCALE),
                    titleLineStart + 1, styledText.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(styledText);
    }

    private static void updateEpisodeTitlePlaybackState(Media media) {
        if (media == null || media.getState() == null) return;
        boolean paused = media.getState() == 2;
        if (paused == episodeTitlePaused) return;
        episodeTitlePaused = paused;
        if (paused) episodeTitleToolbarSuppressed = false;
        mainHandler.post(DanmakuScanner::updateEpisodeTitleVisibility);
    }

    private static void showEpisodeTitleAfterChange() {
        if (!episodeTitleEligible || TextUtils.isEmpty(episodeTitleText)) {
            updateEpisodeTitleVisibility();
            return;
        }
        episodeTitleToolbarSuppressed = false;
        episodeTitleHideAt = System.currentTimeMillis() + EPISODE_TITLE_VISIBLE_MS;
        mainHandler.removeCallbacks(HIDE_EPISODE_TITLE_TASK);
        updateEpisodeTitleVisibility();
        mainHandler.postDelayed(HIDE_EPISODE_TITLE_TASK, EPISODE_TITLE_VISIBLE_MS);
    }

    private static void updateEpisodeTitleVisibility() {
        TextView view = episodeTitleOverlay.get();
        if (view == null) return;
        boolean visible = episodeTitleEligible
                && !TextUtils.isEmpty(episodeTitleText)
                && !episodeTitleToolbarSuppressed
                && (episodeTitleToolbarVisible
                || episodeTitlePaused
                || System.currentTimeMillis() < episodeTitleHideAt);
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) {
            view.setVisibility(visibility);
            if (visible && view.getParent() instanceof ViewGroup) {
                view.bringToFront();
            }
        }
    }

    private static boolean isEpisodeTitleToolbarVisible(View control) {
        if (control == null || !control.isShown()) return false;
        View current = control;
        while (current != null) {
            if (current.getVisibility() != View.VISIBLE || current.getAlpha() <= 0.01f) {
                return false;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        android.graphics.Rect visibleRect = new android.graphics.Rect();
        return control.getGlobalVisibleRect(visibleRect)
                && visibleRect.width() > 0 && visibleRect.height() > 0;
    }

    private static void startEpisodeTitleVisibilityWatcher(View control) {
        episodeTitleControl = new WeakReference<>(control);
        updateEpisodeTitleToolbarVisibility(isEpisodeTitleToolbarVisible(control));
        if (episodeTitleVisibilityWatcherRunning) return;
        episodeTitleVisibilityWatcherRunning = true;
        mainHandler.post(EPISODE_TITLE_VISIBILITY_WATCHER);
    }

    private static void updateEpisodeTitleToolbarVisibility(boolean visible) {
        if (visible == episodeTitleToolbarVisible) return;
        episodeTitleToolbarVisible = visible;
        episodeTitleToolbarSuppressed = !visible;
        updateEpisodeTitleVisibility();
    }

    private static void hideEpisodeTitleOverlay() {
        episodeTitleEligible = false;
        episodeTitleToolbarVisible = false;
        episodeTitleToolbarSuppressed = false;
        episodeTitlePaused = false;
        episodeTitleHideAt = 0;
        episodeTitleText = "";
        episodeTitleRequestSignature = "";
        episodeTitleLogo = null;
        episodeTitleRetryAt = 0;
        episodeTitleRequestPending = false;
        episodeTitleControl = new WeakReference<>(null);
        episodeTitleVisibilityWatcherRunning = false;
        mainHandler.removeCallbacks(HIDE_EPISODE_TITLE_TASK);
        mainHandler.removeCallbacks(EPISODE_TITLE_VISIBILITY_WATCHER);
        mainHandler.post(() -> {
            TextView view = episodeTitleOverlay.get();
            if (view != null) view.setVisibility(View.GONE);
        });
    }

    private static void ensureEpisodeTitleOverlay(ViewGroup toolbarRow) {
        if (toolbarRow == null) return;
        Context titleContext = toolbarRow.getContext();
        while (titleContext instanceof android.content.ContextWrapper && !(titleContext instanceof Activity)) {
            Context base = ((android.content.ContextWrapper)titleContext).getBaseContext();
            if (base == titleContext) break;
            titleContext = base;
        }
        if (!(titleContext instanceof Activity)) return;
        final Activity titleActivity = (Activity)titleContext;
        try {
            ViewParent scrollParent = toolbarRow.getParent();
            View scroll = scrollParent instanceof View ? (View) scrollParent : toolbarRow;
            ViewParent controlParent = scroll instanceof ViewGroup
                    ? ((ViewGroup) scroll).getParent() : null;
            View control = controlParent instanceof View ? (View) controlParent : toolbarRow;
            View parent = null;
            ViewParent outerParent = control.getParent();
            while (outerParent instanceof View) {
                View ancestor = (View) outerParent;
                if (ancestor instanceof FrameLayout) {
                    parent = ancestor;
                    break;
                }
                outerParent = ancestor.getParent();
            }
            if (!(parent instanceof FrameLayout)) return;

            final ViewGroup overlayHost = (ViewGroup) parent;
            final View controlFrame = control;
            final View toolbarBar = scroll instanceof ViewGroup ? scroll : toolbarRow;
            TextView cached = episodeTitleOverlay.get();
            if (cached != null && cached.getParent() == overlayHost) {
                startEpisodeTitleVisibilityWatcher(controlFrame);
                updateEpisodeTitleVisibility();
                return;
            }
            View root = toolbarRow.getRootView();
            View existing = root == null ? null : root.findViewWithTag(EPISODE_TITLE_TAG);

            TextView titleView;
            if (existing instanceof TextView && existing.getParent() == overlayHost) {
                titleView = (TextView) existing;
            } else {
                if (existing != null && existing.getParent() instanceof ViewGroup) {
                    ((ViewGroup) existing.getParent()).removeView(existing);
                }
                Activity activity = titleActivity;
                titleView = new TextView(activity);
                titleView.setTag(EPISODE_TITLE_TAG);
                titleView.setTextColor(Color.WHITE);
                titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        EPISODE_TITLE_BASE_SIZE_SP);
                titleView.setTypeface(null, android.graphics.Typeface.BOLD);
                titleView.setSingleLine(false);
                titleView.setMaxLines(2);
                titleView.setEllipsize(TextUtils.TruncateAt.END);
                titleView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                titleView.setPadding(0, 0, 0, 0);
                titleView.setAlpha(EPISODE_TITLE_ALPHA);
                titleView.setShadowLayer(dpToPx(activity, 4), 0, dpToPx(activity, 2),
                        0xDD000000);
                titleView.setFocusable(false);
                titleView.setClickable(false);
                titleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                titleView.setBackground(null);
                titleView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    if (r - l != or - ol && episodeTitleOverlay.get() == v
                            && episodeTitleLogo != null) applyEpisodeTitleText();
                });

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.BOTTOM | Gravity.START;
                overlayHost.addView(titleView, params);
                controlFrame.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                        oldLeft, oldTop, oldRight, oldBottom) ->
                        updateEpisodeTitleOverlayPosition(
                                overlayHost, titleView, controlFrame, toolbarBar));
                if (toolbarBar != controlFrame) toolbarBar.addOnLayoutChangeListener(
                        (v, l, t, r, b, ol, ot, or, ob) -> updateEpisodeTitleOverlayPosition(
                                overlayHost, titleView, controlFrame, toolbarBar));
            }

            episodeTitleOverlay = new WeakReference<>(titleView);
            applyEpisodeTitleText();
            startEpisodeTitleVisibilityWatcher(controlFrame);
            updateEpisodeTitleVisibility();
            updateEpisodeTitleOverlayPosition(overlayHost, titleView, controlFrame, toolbarBar);
        } catch (Exception e) {
            DanmakuSpider.log("集标题浮层创建失败: " + e.getMessage());
        }
    }

    private static void updateEpisodeTitleOverlayPosition(ViewGroup host, TextView titleView,
                                                          View controlFrame, View toolbarBar) {
        host.post(() -> {
            try {
                if (titleView.getParent() != host || controlFrame.getWidth() <= 0
                        || host.getHeight() <= 0 || toolbarBar.getWidth() <= 0) return;

                int[] hostLocation = new int[2];
                int[] controlLocation = new int[2];
                int[] barLocation = new int[2];
                host.getLocationOnScreen(hostLocation);
                controlFrame.getLocationOnScreen(controlLocation);
                toolbarBar.getLocationOnScreen(barLocation);

                int width = Math.max(0, toolbarBar.getWidth());
                int leftMargin = Math.max(0, barLocation[0] - hostLocation[0]);
                int controlTop = controlLocation[1] - hostLocation[1];
                int bottomMargin = Math.max(dpToPx(host.getContext(), 6),
                        host.getHeight() - controlTop + dpToPx(host.getContext(), 6));
                FrameLayout.LayoutParams current = titleView.getLayoutParams()
                        instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams) titleView.getLayoutParams() : null;
                if (current == null || current.width != width
                        || current.leftMargin != leftMargin
                        || current.bottomMargin != bottomMargin
                        || current.gravity != (Gravity.BOTTOM | Gravity.START)) {
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            width, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.gravity = Gravity.BOTTOM | Gravity.START;
                    params.leftMargin = leftMargin;
                    params.bottomMargin = bottomMargin;
                    titleView.setLayoutParams(params);
                }

                updateEpisodeTitleVisibility();
            } catch (Exception ignored) {
            }
        });
    }

    private static int dpToPx(android.content.Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static void addToolbarButton(ViewGroup parent, List<View> ordered,
                                         String text, String tag) {
        View found = tag != null ? findDirectToolbarChildByTag(parent, tag)
                : findDirectToolbarChildByText(parent, text);
        if (found != null && !ordered.contains(found)) ordered.add(found);
    }

    private static void addToolbarButtonById(ViewGroup parent, List<View> ordered,
                                             String idName, String fallbackText) {
        View found = findDirectToolbarChildByIdName(parent, idName);
        if (found == null && !TextUtils.isEmpty(fallbackText)) {
            found = findDirectToolbarChildByText(parent, fallbackText);
        }
        if (found != null && !ordered.contains(found)) ordered.add(found);
    }

    private static View findDirectToolbarChildByIdName(ViewGroup parent, String idName) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (hasResourceId(child, idName)) return child;
        }
        return null;
    }

    private static boolean hasResourceId(View view, String idName) {
        try {
            if (view.getId() != View.NO_ID
                    && idName.equals(view.getResources().getResourceEntryName(view.getId()))) {
                return true;
            }
        } catch (Exception ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasResourceId(group.getChildAt(i), idName)) return true;
            }
        }
        return false;
    }

    private static void ensureTimeButtonWidth(View view) {
        TextView textView = findTextView(view);
        if (textView == null) return;
        CharSequence value = textView.getText();
        String text = value == null ? "" : value.toString();
        if (!text.matches("\\d{1,2}:\\d{2}(?::\\d{2})?")) return;
        String sample = text.indexOf(':', text.indexOf(':') + 1) >= 0 ? "00:00:00" : "00:00";
        int textWidth = (int) Math.ceil(Math.max(
                textView.getPaint().measureText(text),
                textView.getPaint().measureText(sample)));
        int desired = textWidth + textView.getPaddingLeft() + textView.getPaddingRight()
                + Math.round(8 * textView.getResources().getDisplayMetrics().density);
        textView.setSingleLine(true);
        textView.setEllipsize(null);
        textView.setMinWidth(desired);
        textView.setMinimumWidth(desired);
        ViewGroup.LayoutParams params = textView.getLayoutParams();
        if (params != null && params.width > 0 && params.width < desired) {
            params.width = desired;
            textView.setLayoutParams(params);
        }
    }

    private static TextView findTextView(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView textView = findTextView(group.getChildAt(i));
                if (textView != null) return textView;
            }
        }
        return null;
    }

    private static View findDirectToolbarChildByTag(ViewGroup parent, String tag) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (tag.equals(child.getTag())) return child;
        }
        View root = parent.getRootView();
        return root == null ? null : root.findViewWithTag(tag);
    }

    private static View findDirectToolbarChildByText(ViewGroup parent, String target) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (target.equals(extractToolbarText(child))) return child;
        }
        return null;
    }

    private static String extractToolbarText(View view) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            return value == null ? "" : value.toString().trim();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String text = extractToolbarText(group.getChildAt(i));
                if (!TextUtils.isEmpty(text)) return text;
            }
        }
        return "";
    }

    private static ViewGroup.LayoutParams copyButtonParams(View view, View fallback) {
        ViewGroup.LayoutParams source = view.getLayoutParams();
        if (source == null) source = fallback.getLayoutParams();
        if (source instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) source);
        }
        if (source instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
        }
        if (source != null) return new ViewGroup.LayoutParams(source);
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static boolean applyToolbarSpacing(View view, int horizontalGap) {
        if (view == null) return false;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null || !applyToolbarSpacing(params, horizontalGap)) return false;
        view.setLayoutParams(params);
        return true;
    }

    private static boolean applyToolbarSpacing(ViewGroup.LayoutParams params, int horizontalGap) {
        boolean changed = false;
        if (params instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams linearParams = (LinearLayout.LayoutParams) params;
            if (linearParams.leftMargin != horizontalGap
                    || linearParams.rightMargin != horizontalGap
                    || linearParams.topMargin != 0
                    || linearParams.bottomMargin != 0) {
                linearParams.setMargins(horizontalGap, 0, horizontalGap, 0);
                changed = true;
            }
            int gravity = (linearParams.gravity & Gravity.HORIZONTAL_GRAVITY_MASK)
                    | Gravity.CENTER_VERTICAL;
            if (linearParams.gravity != gravity) {
                linearParams.gravity = gravity;
                changed = true;
            }
        } else if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            if (margins.leftMargin != horizontalGap
                    || margins.rightMargin != horizontalGap
                    || margins.topMargin != 0
                    || margins.bottomMargin != 0) {
                margins.setMargins(horizontalGap, 0, horizontalGap, 0);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 显示Leo按钮菜单
     * @param activity
     */
    public static void showLeoButtonMenu(final Activity activity) {
        showLeoButtonMenu(activity, null);
    }

    public static void showLeoButtonMenu(final Activity activity, Runnable onDismiss) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
            builder.setTitle("弹幕设置");

            // 构建菜单选项列表
            java.util.List<String> optionsList = new java.util.ArrayList<>();
            optionsList.add("📱 远程搜索/输入");
            optionsList.add("🔄 Auto 推送开关");
            optionsList.add("🔇 静默模式");
            optionsList.add("💬 弹幕配置");
            optionsList.add("🎨 布局配置");
            optionsList.add("✨ 弹幕 UI 风格");
            optionsList.add("📝 查看日志");

            // 代理操作 - 始终显示
            String proxyType = ProxyManager.getProxyTypeName();
            String proxyStatus = ProxyManager.getProxyStatusText();
            optionsList.add("🔌 代理状态 [" + proxyType + " | " + proxyStatus + "]");
            optionsList.add("🔄 切换代理");
            optionsList.add("🔁 重启代理");

            String[] options = optionsList.toArray(new String[0]);

            builder.setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case 0: // 远程搜索/输入
                            DanmakuSpider.log("[菜单] 打开远程搜索二维码");
                            String qrUrl = "http://" + NetworkUtils.getLocalIpAddress() + ":9810";
                            DanmakuUIHelper.showQRCodeDialog(activity, qrUrl);
                            break;

                        case 1: // 自动推送开关
                            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                            // 切换自动推送状态
                            config.setAutoPushEnabled(!config.isAutoPushEnabled());
                            DanmakuConfigManager.saveConfig(activity, config);
                        
                            // 更新 UI 显示
                            DanmakuSpider.log("自动推送弹幕状态切换：" + config.isAutoPushEnabled());
                            Utils.safeShowToast(activity,
                                    config.isAutoPushEnabled() ? "自动推送弹幕已开启" : "自动推送弹幕已关闭");
                        
                            // 如果关闭了自动推送，可以停止相关监控
                            if (!config.isAutoPushEnabled()) {
                                pendingPushes.clear();
                                DanmakuSpider.log("[菜单] 已清空待推送队列");
                            }
                        
                            break;
                        
                        case 2: // 静默模式
                            DanmakuConfig configSilent = DanmakuConfigManager.getConfig(activity);
                            // 切换静默模式状态
                            configSilent.setSilentMode(!configSilent.isSilentMode());
                            DanmakuConfigManager.saveConfig(activity, configSilent);
                        
                            // 更新 UI 显示
                            DanmakuSpider.log("静默模式状态切换：" + configSilent.isSilentMode());
                            Utils.safeShowToast(activity,
                                    configSilent.isSilentMode() ? "静默模式已开启" : "静默模式已关闭");
                        
                            break;

                        case 3: // 弹幕设置
                            DanmakuSpider.log("[菜单] 打开弹幕设置");
                            DanmakuUIHelper.showConfigDialog(activity);
                            break;
                        case 4: // 布局配置
                            DanmakuSpider.log("[菜单] 打开布局配置");
                            DanmakuUIHelper.showLpConfigDialog(activity);
                            break;
                        case 5: // 弹幕 UI 风格
                            DanmakuSpider.log("[菜单] 打开弹幕 UI 风格");
                            DanmakuUIHelper.showDanmakuStyleDialog(activity);
                            break;
                        case 6: // 查看日志（统一日志查看器）
                            DanmakuSpider.log("[菜单] 打开统一日志查看器");
                            DanmakuUIHelper.showUnifiedLogDialog(activity);
                            break;

                        case 7: // 代理状态
                            {
                                String st = ProxyManager.getProxyStatusText();
                                String tp = ProxyManager.getProxyTypeName();
                                String msg = "代理类型: " + tp + "\n状态: " + st;
                                Utils.safeShowToast(activity, msg);
                                DanmakuSpider.log("[菜单] 查看代理状态: " + msg);
                            }
                            break;

                        case 8: // 切换代理
                            {
                                int currentType = ProxyManager.getActiveProxyType();
                                if (currentType == ProxyManager.PROXY_TYPE_GO) {
                                    ProxyManager.switchToJavaProxy(activity.getApplicationContext());
                                    Utils.safeShowToast(activity, "切换到Java代理中...");
                                    DanmakuSpider.log("[菜单] 切换到Java代理");
                                } else {
                                    if (ProxyManager.canSwitchToGoProxy()) {
                                        ProxyManager.switchToGoProxy(activity.getApplicationContext());
                                        Utils.safeShowToast(activity, "切换到Go代理中...");
                                        DanmakuSpider.log("[菜单] 切换到Go代理");
                                    } else {
                                        Utils.safeShowToast(activity, "Go代理资源不存在");
                                        DanmakuSpider.log("[菜单] Go代理资源不存在");
                                    }
                                }
                            }
                            break;

                        case 9: // 重启代理
                            DanmakuSpider.log("[菜单] 用户触发代理重启");
                            ProxyManager.restartProxy(activity.getApplicationContext());
                            Utils.safeShowToast(activity, "代理重启中，请稍候...");
                            break;
                    }
                }
            });

            builder.setNegativeButton("取消", null);

            try {
                android.app.AlertDialog menu = builder.create();
                menu.setOnDismissListener(ignored -> {
                    if (onDismiss != null) onDismiss.run();
                });
                menu.show();
            } catch (Exception e) {
                DanmakuSpider.log("显示菜单失败: " + e.getMessage());
                if (onDismiss != null) onDismiss.run();
            }
        } catch (Exception e) {
            DanmakuSpider.log("创建菜单失败: " + e.getMessage());
            if (onDismiss != null) onDismiss.run();
        }
    }

    // 兼容低版本的View ID生成方法
    private static int generateViewId() {
        // 确保ID为正数且不与其他ID冲突
        nextViewId++;
        // 确保ID不会超过Android允许的最大值（0xFFFFFF，因为高8位有特殊用途）
        if (nextViewId > 0x00FFFFFF) {
            nextViewId = 10000;
        }
        return nextViewId;
    }

    // 检查是否在RecyclerView中
    private static boolean isInRecyclerView(View view) {
        View p = view;
        while (p != null) {
            if (p.getClass().getName().contains("RecyclerView")) return true;
            if (p.getParent() instanceof View) {
                p = (View) p.getParent();
            } else {
                break;
            }
        }
        return false;
    }
    // === 按钮注入逻辑结束 ===

    // 提取标题的辅助方法
    private static String extractTitleFromView(View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText().toString().trim();
            if (isPotentialTitle(text)) {
                return text;
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE) {
                    String result = extractTitleFromView(child);
                    if (!TextUtils.isEmpty(result)) {
                        return result;
                    }
                }
            }
        }
        return "";
    }

    private static boolean isPotentialTitle(String text) {
        if (TextUtils.isEmpty(text) || text.length() < 2 || text.length() > 200) return false;

        if (text.matches(".*\\d{1,2}:\\d{1,2}(:\\d{1,2})?.*")) return false;
        if (text.matches("\\d+")) return false;

        String lower = text.toLowerCase();
        if (lower.contains("loading") || lower.contains("buffering") ||
                lower.contains("error") || lower.contains("fail")) {
            return false;
        }

        return true;
    }

    private static void startAutoSearch(EpisodeInfo episodeInfo, final Activity activity) {
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);

        // 检查自动推送状态
        if (!config.isAutoPushEnabled()) {
            DanmakuSpider.log("❌ 自动推送已关闭，跳过自动搜索");
            return;
        }

        iterativeAutoSearch(episodeInfo, activity);
    }

    private static void iterativeAutoSearch(EpisodeInfo episodeInfo, final Activity activity) {
        new Thread(() -> {
            List<String> episodeNames = episodeInfo.getEpisodeNames();
            if (episodeNames == null || episodeNames.isEmpty()) {
                DanmakuSpider.log("❌ 剧集名称列表为空，无法执行迭代搜索");
                return;
            }

            LeoDanmakuService.SearchResult bestResult = null;

            DanmakuSpider.log("迭代列表：" + episodeNames);

            for (String name : episodeNames) {
                if (TextUtils.isEmpty(name)) continue;

                DanmakuSpider.log("🔍 迭代搜索中，尝试名称: " + name);
                LeoDanmakuService.SearchResult currentResult = LeoDanmakuService.autoSearch(name, episodeInfo, activity);

                if (currentResult.found) {
                    if (currentResult.similarity == 1.0) {
                        DanmakuSpider.log("✅ 找到完全匹配结果，立即推送并停止搜索");
                        LeoDanmakuService.pushDanmakuDirect(currentResult.item, activity, true);
                        return; // 找到完全匹配结果，结束方法
                    }

                    if (bestResult == null || currentResult.similarity > bestResult.similarity) {
                        bestResult = currentResult;
                    }
                }
            }

            if (bestResult != null && bestResult.similarity >= 0.85) {
                DanmakuSpider.log("🏁 迭代搜索结束，未找到完全匹配结果。推送最佳匹配项 (相似度: " + bestResult.similarity + ")");
                LeoDanmakuService.pushDanmakuDirect(bestResult.item, activity, true);
            } else {
                // 输出分析对比信息
                String inputName = episodeInfo.getEpisodeNames().get(0);
                if (bestResult != null && bestResult.item != null) {
                    DanmakuSpider.log("📊 分析对比 - 输入剧名: " + inputName + ", 最佳匹配: " + bestResult.item.getTitle() + ", 相似度: " + bestResult.similarity + " (低于0.85阈值)");
                } else {
                    DanmakuSpider.log("📊 分析对比 - 输入剧名: " + inputName + ", 未找到任何匹配结果");
                }
                DanmakuSpider.log("🤷‍♂️ 迭代搜索结束，未找到任何有效弹幕");
                if (activity != null && !activity.isFinishing()) {
//                    activity.runOnUiThread(() -> Utils.safeShowToast(activity, "Leo弹幕获取失败，请手动搜索"));
                    DanmakuSpider.log("Leo弹幕获取失败，请手动搜索");
                }
            }
        }).start();
    }
}
