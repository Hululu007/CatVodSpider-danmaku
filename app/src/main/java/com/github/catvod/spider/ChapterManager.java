package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.spider.entity.Media;
import com.google.gson.Gson;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 章节与片头片尾管理。原生章节来自 FongMi 当前播放器，用户标记保存在插件本地。
 */
public final class ChapterManager {

    private static final String PREFS_NAME = "danmaku_chapter_data";
    private static final String KEY_PREFIX = "chapter_";
    private static final int AUTO_MARKER_VERSION = 5;
    private static final long AUTO_QUERY_RETRY_MS = 15000L;
    private static volatile long playbackGeneration;
    private static volatile String sessionMediaKey = "";
    private static volatile String sessionMediaUrl = "";
    private static final Object AUTO_QUERY_LOCK = new Object();
    private static final Gson GSON = new Gson();
    private static final ScheduledExecutorService AUTO_SKIP_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "DanmakuChapterAutoSkip");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static volatile boolean autoSkipStarted;
    // Memory only. A completed action belongs to this playback, never the next one.
    static final class SkipSession {
        volatile boolean introDone;
        volatile boolean outroDone;
        long lastAttemptAt;
    }

    private static volatile SkipSession skipSession = new SkipSession();
    private static volatile String observedMediaKey = "";
    private static volatile long suppressAutoSkipUntil;
    private static volatile MediaInfo lastPlaybackInfo;
    private static volatile String lastAutoQueryKey = "";
    private static volatile long lastAutoQueryAt;

    private ChapterManager() {
    }

    public static class Chapter {
        public String id;
        public String title;
        public long startMs;
        public boolean nativeChapter;

        public Chapter() {
        }

        public Chapter(String id, String title, long startMs, boolean nativeChapter) {
            this.id = id;
            this.title = title;
            this.startMs = Math.max(0, startMs);
            this.nativeChapter = nativeChapter;
        }
    }

    public static class ChapterData {
        public int automaticOnlyVersion;
        public boolean autoIntro;
        public boolean autoEnding;
        public boolean manualIntroOverride;
        public boolean manualOutroOverride;
        public boolean autoIntroResolved;
        public boolean autoOutroResolved;
        public int autoMarkerVersion;
        public boolean nativeMarkerStateInitialized;
        public long introStartMs = -1;
        public long introEndMs = -1;
        public long outroStartMs = -1;
        public long recapStartMs = -1;
        public long recapEndMs = -1;
        public long nativeOpeningWrittenMs = -1;
        public long nativeEndingWrittenMs = -1;
        public List<Chapter> chapters = new ArrayList<>();
        public List<Chapter> providerChapters = new ArrayList<>();
        public long markerDurationMs;
        public long markerRetryAtMs;
    }

    public static class MediaInfo {
        public String key;
        public String legacyKey;
        public String fallbackKey;
        public String title;
        public String artist;
        public String url;
        public long positionMs;
        public long durationMs;
        public float speed = 1f;
        public boolean playing;
        public String positionSource = "host";

        public String getDisplayTitle() {
            if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(artist)) return title + " · " + artist;
            if (!TextUtils.isEmpty(title)) return title;
            return TextUtils.isEmpty(artist) ? "当前视频" : artist;
        }
    }

    public static MediaInfo getMediaInfo() {
        try {
            String json = readMediaJson();
            if (TextUtils.isEmpty(json)) return getNativeMediaInfo();
            Media media = GSON.fromJson(json, Media.class);
            if (media == null || (TextUtils.isEmpty(media.getTitle())
                    && TextUtils.isEmpty(media.getArtist()))) {
                return getNativeMediaInfo();
            }
            MediaInfo info = fromMedia(media);
            if (info != null) lastPlaybackInfo = info;
            return info;
        } catch (Exception e) {
            DanmakuSpider.log("读取章节播放状态失败: " + e.getMessage());
            return null;
        }
    }

    private static MediaInfo getNativeMediaInfo() {
        MediaInfo info = FongMiNativeHelper.getMediaInfo(activityRef.get());
        if (info != null) { info.positionSource = "session"; lastPlaybackInfo = info; }
        return info;
    }

    private static String readMediaJson() {
        String json = fetchMediaJson();
        if (isMediaJson(json)) return json;
        Utils.invalidatePort();
        json = fetchMediaJson();
        return isMediaJson(json) ? json : "";
    }

    private static String fetchMediaJson() {
        try {
            return OkHttp.string(
                    "http://127.0.0.1:" + Utils.getPort() + "/media", 2500);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isMediaJson(String json) {
        if (TextUtils.isEmpty(json)) return false;
        String trimmed = json.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    public static MediaInfo fromMedia(Media media) {
        if (media == null) return null;
        MediaInfo info = fromPlaybackValues(media.getTitle(), media.getArtist(), media.getUrl(),
                media.getPosition(), media.getDuration());
        if (info != null) {
            info.speed = media.getSpeed() > 0 ? media.getSpeed() : 1f;
            info.playing = media.getState() != null && media.getState() == 3;
        }
        return info;
    }

    public static MediaInfo fromPlaybackValues(
            String title, String artist, String url, long position, long duration) {
        MediaInfo info = new MediaInfo();
        info.title = nullToEmpty(title);
        info.artist = nullToEmpty(artist);
        info.url = nullToEmpty(url);
        info.positionMs = Math.max(0, position);
        info.durationMs = Math.max(0, duration);
        if (TextUtils.isEmpty(info.title) && TextUtils.isEmpty(info.artist)) return null;
        String identity = nullToEmpty(info.title) + "|" + nullToEmpty(info.artist);
        info.key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        info.legacyKey = UUID.nameUUIDFromBytes(
                (identity + "|" + nullToEmpty(info.url))
                        .getBytes(StandardCharsets.UTF_8)).toString();
        info.fallbackKey = UUID.nameUUIDFromBytes(
                (identity + "|mediasession://current")
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return info;
    }

    public static ChapterData loadData(Context context, MediaInfo media) {
        ChapterData data = null;
        boolean migrated = false;
        if (context != null && media != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_PREFIX + media.key, null);
            if (TextUtils.isEmpty(json) && !TextUtils.equals(media.key, media.legacyKey)) {
                json = prefs.getString(KEY_PREFIX + media.legacyKey, null);
                migrated = !TextUtils.isEmpty(json);
            }
            if (TextUtils.isEmpty(json) && !TextUtils.equals(media.key, media.fallbackKey)
                    && !TextUtils.equals(media.legacyKey, media.fallbackKey)) {
                json = prefs.getString(KEY_PREFIX + media.fallbackKey, null);
                migrated = !TextUtils.isEmpty(json);
            }
            if (!TextUtils.isEmpty(json)) {
                try {
                    data = GSON.fromJson(json, ChapterData.class);
                } catch (Exception ignored) {
                }
            }
        }
        if (data == null) data = new ChapterData();
        if (data.chapters == null) data.chapters = new ArrayList<>();
        if (data.providerChapters == null) data.providerChapters = new ArrayList<>();
        if (media != null && data.markerDurationMs > 0 && media.durationMs > 0
                && Math.abs(data.markerDurationMs - media.durationMs) > SkipSegmentSources.DURATION_TOLERANCE_MS) {
            data.introStartMs = -1; data.introEndMs = -1; data.outroStartMs = -1;
            data.recapStartMs = -1; data.recapEndMs = -1;
            data.autoIntro = false; data.autoEnding = false;
            data.autoIntroResolved = false; data.autoOutroResolved = false;
            data.autoMarkerVersion = 0; data.markerDurationMs = 0;
            data.providerChapters.clear();
            migrated = true;
        }
        migrated |= migrateAutomaticOnly(data);
        if (migrated && context != null && media != null) {
            saveData(context, media, data);
        }
        return data;
    }

    public static void saveData(Context context, MediaInfo media, ChapterData data) {
        if (context == null || media == null || data == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PREFIX + media.key, GSON.toJson(data)).apply();
    }

    /** Old versions imported manual History values into provider fields. Discard only those sides. */
    static boolean migrateAutomaticOnly(ChapterData data) {
        if (data.automaticOnlyVersion >= 1) return false;
        if (data.manualIntroOverride) {
            data.introStartMs = -1; data.introEndMs = -1;
            data.autoIntro = false; data.autoIntroResolved = false;
        }
        if (data.manualOutroOverride) {
            data.outroStartMs = -1; data.autoEnding = false; data.autoOutroResolved = false;
        }
        data.manualIntroOverride = false; data.manualOutroOverride = false;
        data.automaticOnlyVersion = 1;
        return true;
    }

    /** Toolbar-only action. Provider markers are never erased by a manual clear. */
    public static boolean clearManualSkipMarkers(Activity activity) {
        if (activity == null) return false;
        boolean cleared = FongMiNativeHelper.clearManualSkipMarkers(activity);
        if (cleared) {
            MediaInfo media = lastPlaybackInfo;
            if (media != null) loadData(activity, media); // Migrate an old mixed record, if present.
            suppressAutoSkipUntil = System.currentTimeMillis() + 10000;
            DanmakuSpider.log("已清除原生手动头尾；自动章节保留");
        }
        return cleared;
    }

    public static void onAutoSkipChanged(boolean enabled) {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing()) return;
        suppressAutoSkipUntil = System.currentTimeMillis() + 10000;
        if (!FongMiNativeHelper.syncNativeSkipState(activity, enabled))
            DanmakuSpider.log("Auto 总开关原生同步待重试");
    }

    static boolean isAutoSkipActive() {
        Activity activity = activityRef.get();
        DanmakuConfig config = activity == null ? null : DanmakuConfigManager.getConfig(activity);
        return config != null && config.isAutoSkipEnabled();
    }

    static boolean isAutoSkipActive(SkipSession session) {
        return session == skipSession && isAutoSkipActive();
    }

    public static void addCustomChapter(Context context, MediaInfo media, ChapterData data, String title, long positionMs) {
        if (data == null || media == null) return;
        String name = TextUtils.isEmpty(title) ? "自定义章节" : title.trim();
        data.chapters.add(new Chapter(UUID.randomUUID().toString(), name, positionMs, false));
        Collections.sort(data.chapters, (left, right) -> Long.compare(left.startMs, right.startMs));
        saveData(context, media, data);
    }

    public static void deleteCustomChapter(Context context, MediaInfo media, ChapterData data, String id) {
        if (data == null) return;
        for (int i = data.chapters.size() - 1; i >= 0; i--) {
            if (TextUtils.equals(data.chapters.get(i).id, id)) data.chapters.remove(i);
        }
        saveData(context, media, data);
    }

    /**
     * 读取 FongMi 当前媒体章节。新版方法名为 getCurrentMediaChapters，5.5.x 混淆后为 I0。
     * 读取失败时返回空列表，不影响本地章节功能。
     */
    public static List<Chapter> getNativeChapters(Activity activity) {
        if (activity == null || activity.isFinishing()) return new ArrayList<>();
        try {
            Object player = findChapterPlayer(activity);
            if (player == null) return new ArrayList<>();
            List<?> raw = null;
            for (Method method : player.getClass().getMethods()) {
                if (method.getParameterTypes().length != 0) continue;
                if (!List.class.isAssignableFrom(method.getReturnType())) continue;
                String name = method.getName();
                if (!name.equals("I0") && !name.equals("getCurrentMediaChapters")
                        && !name.toLowerCase().contains("chapter")) continue;
                try {
                    Object value = method.invoke(player);
                    if (value instanceof List && !((List<?>) value).isEmpty()) {
                        raw = (List<?>) value;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (raw == null) return new ArrayList<>();
            List<Chapter> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object item : raw) {
                Chapter chapter = parseNativeChapter(item);
                if (chapter == null || chapter.startMs < 0) continue;
                String key = chapter.startMs + "|" + chapter.title;
                if (seen.add(key)) result.add(chapter);
            }
            Collections.sort(result, (left, right) -> Long.compare(left.startMs, right.startMs));
            return result;
        } catch (Throwable e) {
            DanmakuSpider.log("读取原生章节失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static boolean seekTo(long positionMs) {
        long target = Math.max(0, positionMs);
        if (FongMiNativeHelper.seekTo(target)) return true;
        return control("seek", target);
    }

    public static boolean seekToManual(long positionMs) {
        return seekTo(positionMs);
    }

    public static boolean nextEpisode() {
        if (FongMiNativeHelper.nextEpisode()) return true;
        return control("next", 0);
    }

    private static boolean control(String type, long positionMs) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("type", type);
            if ("seek".equals(type)) params.put("position", String.valueOf(Math.max(0, positionMs)));
            String body = OkHttp.post("http://127.0.0.1:" + Utils.getPort() + "/remote", params);
            boolean ok = !TextUtils.isEmpty(body)
                    && !body.contains("Playback unavailable")
                    && !body.contains("Invalid control")
                    && !body.contains("Same origin required");
            if (!ok) DanmakuSpider.log("播放控制失败: " + type + " " + body);
            return ok;
        } catch (Exception e) {
            DanmakuSpider.log("播放控制异常: " + e.getMessage());
            return false;
        }
    }

    public static void startAutoSkip(Activity activity) {
        if (activity != null && activityRef.get() != activity) {
            Activity previous = activityRef.get();
            if (previous != null) onPlaybackStopped(previous);
            activityRef = new WeakReference<>(activity);
            resetPlaybackSession(activity);
        }
        if (autoSkipStarted) return;
        autoSkipStarted = true;
        AUTO_SKIP_EXECUTOR.scheduleWithFixedDelay(ChapterManager::autoSkipTick, 500, 1000, TimeUnit.MILLISECONDS);
    }

    public static void onPlaybackStopped(Activity activity) {
        if (activityRef.get() != activity) return;
        resetPlaybackSession(activity);
        activityRef.clear();
        lastPlaybackInfo = null;
    }

    private static void resetPlaybackSession(Activity activity) {
        playbackGeneration++;
        skipSession = new SkipSession();
        suppressAutoSkipUntil = 0;
        sessionMediaKey = ""; sessionMediaUrl = "";
        observedMediaKey = "";
        // Keep global Auto and stored markers; renew only the per-playback allowance.
        FongMiNativeHelper.syncNativeSkipState(activity, true);
    }

    private static void observePlaybackSession(Activity activity, MediaInfo media) {
        boolean changed = !TextUtils.isEmpty(sessionMediaKey)
                && (!TextUtils.equals(sessionMediaKey, media.key)
                || (isRealMediaUrl(sessionMediaUrl) && isRealMediaUrl(media.url)
                && !TextUtils.equals(sessionMediaUrl, media.url)));
        if (changed) resetPlaybackSession(activity);
        sessionMediaKey = media.key;
        if (isRealMediaUrl(media.url)) sessionMediaUrl = media.url;
    }

    private static boolean isRealMediaUrl(String url) {
        return !TextUtils.isEmpty(url) && !url.startsWith("mediasession:");
    }

    static Activity getCurrentActivity() {
        return activityRef.get();
    }

    public static MediaInfo getCachedMediaInfo() {
        return lastPlaybackInfo;
    }

    public static void requestAutoMarkers(Activity activity, Media media) {
        if (activity == null || media == null) return;
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
        if (config == null || !config.isAutoSkipEnabled()) return;
        MediaInfo info = fromMedia(media);
        if (info == null) return;
        observePlaybackSession(activity, info);
        lastPlaybackInfo = info;
        ChapterData data = loadData(activity, info);
        boolean needsData = needsAutoMarkerRefresh(data, info)
                || (!data.autoIntroResolved && data.introEndMs < 0)
                || (!data.autoOutroResolved && data.outroStartMs < 0);
        if (!needsData) return;
        requestAutoMarkersOnce(activity, info, config);
    }

    private static void autoSkipTick() {
        try {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            boolean autoEnabled = config != null && config.isAutoSkipEnabled();
            long generation = playbackGeneration;
            MediaInfo media = getMediaInfo();
            if (activityRef.get() != activity || generation != playbackGeneration) return;
            if (media == null) {
                FongMiNativeHelper.syncNativeSkipState(activity, autoEnabled);
                return;
            }
            observePlaybackSession(activity, media);
            SkipSession session = skipSession;
            FongMiNativeHelper.syncNativeSkipState(activity, autoEnabled);
            lastPlaybackInfo = media;
            long now = System.currentTimeMillis();
            if (!TextUtils.equals(observedMediaKey, media.key)) {
                observedMediaKey = media.key;
                suppressAutoSkipUntil = Math.max(suppressAutoSkipUntil, now + 10000);
            }
            ChapterData data = loadData(activity, media);
            if (!autoEnabled || !isAutoSkipActive(session)) return;
            FongMiNativeHelper.HistoryState nativeHistory = FongMiNativeHelper.readHistory(activity);
            // Native manual values stay entirely in History; they take priority on that side.
            boolean autoIntroEnabled = data.autoIntro && nativeHistory.opening <= 0;
            boolean autoEndingEnabled = data.autoEnding && nativeHistory.ending <= 0;
            boolean needsAutoData = needsAutoMarkerRefresh(data, media)
                    || (!data.autoIntroResolved && data.introEndMs < 0)
                    || (!data.autoOutroResolved && data.outroStartMs < 0);
            if (needsAutoData) {
                requestAutoMarkersOnce(activity, media, config);
            }
            if (now < suppressAutoSkipUntil) return;
            if (!session.introDone && autoIntroEnabled
                    && data.introStartMs >= 0 && data.introEndMs > data.introStartMs
                    && media.positionMs >= data.introStartMs && media.positionMs < data.introEndMs) {
                if (now - session.lastAttemptAt >= 3000) {
                    session.lastAttemptAt = now;
                    if (seekToAutomatically(session, data.introEndMs)) {
                        session.introDone = true;
                        DanmakuSpider.log("自动跳过片头（本次播放仅一次）: "
                                + data.introStartMs + " -> " + data.introEndMs);
                    }
                }
                return;
            }

            if (!session.outroDone && autoEndingEnabled
                    && data.outroStartMs >= 0 && media.positionMs >= data.outroStartMs) {
                if (now - session.lastAttemptAt < 3000 || !isAutoSkipActive(session)) return;
                session.lastAttemptAt = now;
                boolean handled = FongMiNativeHelper.nextEpisodeAutomatically(session);
                if (!handled && isAutoSkipActive(session)) handled = control("next", 0);
                if (!handled && media.durationMs > 0)
                    handled = seekToAutomatically(session, media.durationMs);
                if (handled) {
                    session.outroDone = true;
                    DanmakuSpider.log("自动跳过片尾（本次播放仅一次）: " + data.outroStartMs);
                }
            }
        } catch (Throwable e) {
            DanmakuSpider.log("章节自动跳过异常: " + e.getMessage());
        }
    }

    private static boolean seekToAutomatically(SkipSession session, long positionMs) {
        if (!isAutoSkipActive(session)) return false;
        boolean success = FongMiNativeHelper.seekToAutomatically(session, positionMs);
        if (!success && isAutoSkipActive(session)) success = control("seek", positionMs);
        return success;
    }

    private static void applyAutoMarkers(Activity activity, MediaInfo media,
                                         AutoSkipService.Markers markers) {
        if (activity == null || media == null || markers == null || !isAutoSkipActive()) return;
        if (!markers.hasAny()) {
            if (!TextUtils.isEmpty(markers.error)) {
                DanmakuSpider.log("Auto 片头查询: " + markers.error);
            }
            if (!markers.sourcesComplete) return;
        }
        MediaInfo current = lastPlaybackInfo;
        if (!isSamePlayback(current, media)) {
            DanmakuSpider.log("Auto 忽略过期集标记: " + media.getDisplayTitle());
            return;
        }
        if (markers.durationMs > 0 && current.durationMs > 0
                && Math.abs(markers.durationMs - current.durationMs) > SkipSegmentSources.DURATION_TOLERANCE_MS) return;
        ChapterData data = loadData(activity, current);
        boolean changed = false;
        boolean markersChanged = false;
        long retryAt = markers.sourcesComplete && markers.hasAny() ? 0
                : System.currentTimeMillis() + AUTO_QUERY_RETRY_MS;
        if (data.markerRetryAtMs != retryAt) { data.markerRetryAtMs = retryAt; changed = true; }
        List<Chapter> providerChapters = SkipSegmentSources.chapters(markers.segments);
        if (!GSON.toJson(data.providerChapters).equals(GSON.toJson(providerChapters))) {
            data.providerChapters = providerChapters;
            changed = true;
            markersChanged = true;
        }
        if (data.markerDurationMs != markers.durationMs) {
            data.markerDurationMs = markers.durationMs;
            changed = true;
        }
        if (markers.hasIntro()) {
            long introStart = Math.max(0, markers.introStartMs);
            if (data.introStartMs != introStart || data.introEndMs != markers.introEndMs
                    || !data.autoIntro) {
                data.introStartMs = introStart;
                data.introEndMs = markers.introEndMs;
                data.autoIntro = true;
                changed = true;
                markersChanged = true;
            }
        } else if (markers.sourcesComplete && (data.introStartMs >= 0 || data.introEndMs >= 0 || data.autoIntro)) {
            data.introStartMs = -1; data.introEndMs = -1; data.autoIntro = false;
            changed = true; markersChanged = true;
        }
        if (markers.hasOutro()) {
            if (data.outroStartMs != markers.outroStartMs || !data.autoEnding) {
                data.outroStartMs = markers.outroStartMs;
                data.autoEnding = true;
                changed = true;
                markersChanged = true;
            }
        } else if ((markers.sourcesComplete || SkipSegmentSources.hasCreditsOrFollowingScene(markers))
                && (data.outroStartMs >= 0 || data.autoEnding)) {
            data.outroStartMs = -1;
            data.autoEnding = false;
            changed = true;
            markersChanged = true;
        }
        if (markers.hasRecap()) {
            if (data.recapStartMs != markers.recapStartMs
                    || data.recapEndMs != markers.recapEndMs) {
                data.recapStartMs = markers.recapStartMs;
                data.recapEndMs = markers.recapEndMs;
                changed = true;
                markersChanged = true;
            }
        } else if (markers.sourcesComplete && (data.recapStartMs >= 0 || data.recapEndMs >= 0)) {
            data.recapStartMs = -1; data.recapEndMs = -1;
            changed = true; markersChanged = true;
        }
        if (!data.autoIntroResolved) {
            data.autoIntroResolved = true;
            changed = true;
        }
        if (!data.autoOutroResolved) {
            data.autoOutroResolved = true;
            changed = true;
        }
        if (data.autoMarkerVersion < AUTO_MARKER_VERSION) {
            data.autoMarkerVersion = AUTO_MARKER_VERSION;
            changed = true;
        }
        if (!changed) return;
        saveData(activity, current, data);

        if (markersChanged) {
            DanmakuSpider.log("Auto 已写入片头片尾: source=" + markers.source
                    + " intro=" + formatTime(data.introStartMs) + "->" + formatTime(data.introEndMs)
                    + " outro=" + formatTime(data.outroStartMs)
                    + " recap=" + formatTime(data.recapStartMs) + "->" + formatTime(data.recapEndMs)
                    + " imdb=" + markers.imdbId + " native=false");
        }
    }

    private static boolean needsAutoMarkerRefresh(ChapterData data, MediaInfo media) {
        return data != null && (data.autoMarkerVersion < AUTO_MARKER_VERSION
                || (media != null && media.durationMs > 0 && data.markerDurationMs <= 0)
                || (data.markerRetryAtMs > 0 && System.currentTimeMillis() >= data.markerRetryAtMs));
    }

    private static void requestAutoMarkersOnce(
            Activity activity, MediaInfo media, DanmakuConfig config) {
        if (activity == null || media == null || config == null) return;
        String key = media.key;
        long now = System.currentTimeMillis();
        synchronized (AUTO_QUERY_LOCK) {
            if (TextUtils.equals(lastAutoQueryKey, key)
                    && now - lastAutoQueryAt < AUTO_QUERY_RETRY_MS) {
                return;
            }
        }
        boolean started = AutoSkipService.refreshAsync(activity, media, config,
                markers -> applyAutoMarkers(activity, media, markers));
        if (started) {
            synchronized (AUTO_QUERY_LOCK) {
                lastAutoQueryKey = key;
                lastAutoQueryAt = now;
            }
        }
    }

    private static boolean isSamePlayback(MediaInfo current, MediaInfo target) {
        if (current == null || target == null) return false;
        return TextUtils.equals(playbackTitle(current), playbackTitle(target))
                && TextUtils.equals(playbackArtist(current), playbackArtist(target));
    }

    private static String playbackTitle(MediaInfo media) {
        return nullToEmpty(media.title).trim();
    }

    private static String playbackArtist(MediaInfo media) {
        String artist = nullToEmpty(media.artist).trim();
        return artist.isEmpty() ? playbackTitle(media) : artist;
    }

    public static String formatTime(long ms) {
        if (ms < 0) return "--:--";
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static Object findChapterPlayer(Activity activity) {
        Object service = findFieldByTypeName(activity, "PlaybackService");
        Object start = service != null ? service : activity;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return findChapterPlayer(start, 0, visited, 0);
    }

    private static Object findChapterPlayer(Object object, int depth, Set<Object> visited, int count) {
        if (object == null || depth > 4 || count > 120 || visited.contains(object)) return null;
        visited.add(object);
        Class<?> type = object.getClass();
        if (isSimpleType(type)) return null;
        if (hasChapterMethod(type)) return object;
        int nextCount = count;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive() || isSimpleType(fieldType)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value == null || visited.contains(value)) continue;
                    Object found = findChapterPlayer(value, depth + 1, visited, ++nextCount);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean hasChapterMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            if (!List.class.isAssignableFrom(method.getReturnType())) continue;
            String name = method.getName();
            if (name.equals("I0") || name.equals("getCurrentMediaChapters")
                    || name.toLowerCase().contains("chapter")) return true;
        }
        return false;
    }

    private static Object findFieldByTypeName(Object owner, String typeName) {
        if (owner == null) return null;
        for (Class<?> current = owner.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null && value.getClass().getName().contains(typeName)) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Chapter parseNativeChapter(Object item) {
        if (item == null || isSimpleType(item.getClass())) return null;
        long time = Long.MIN_VALUE;
        String title = null;
        int index = -1;
        for (Class<?> current = item.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value instanceof Long && (Long) value >= 0 && time == Long.MIN_VALUE) time = (Long) value;
                    else if (value instanceof String && title == null) title = (String) value;
                    else if (value instanceof Integer && index < 0) index = (Integer) value;
                } catch (Throwable ignored) {
                }
            }
        }
        if (time == Long.MIN_VALUE || TextUtils.isEmpty(title)) return null;
        long startMs = time;
        if (startMs > 10_000_000L) startMs /= 1000;
        return new Chapter("native-" + index + "-" + startMs, title, startMs, true);
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class
                || type.getName().startsWith("java.util.")
                || type.getName().startsWith("android.view.")
                || type.getName().startsWith("android.content.")
                || type.getName().startsWith("android.os.");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
