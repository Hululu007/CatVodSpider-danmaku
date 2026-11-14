package com.github.catvod.spider;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Auto 去片头尾：TMDB 识别剧集，IntroDB 优先，TheIntroDB 补缺，动漫另补 AniSkip。
 */
public final class AutoSkipService {

    private static final String PREFS_NAME = "danmaku_auto_skip_cache_v5";
    private static final String EPISODE_TITLE_CACHE_PREFIX = "epname_";
    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final String TMDB_FALLBACK_BASE = "https://api.tmdb.org/3";
    private static final String INTRODB_SEGMENTS = "https://api.introdb.app/segments";
    private static final String THEINTRODB_MEDIA = "https://api.theintrodb.org/v4/media";
    private static final int SEGMENT_CACHE_VERSION = 7;
    private static final String ANILIST_URL = "https://graphql.anilist.co";
    private static final String ANISKIP_BASE = "https://api.aniskip.com/v2/skip-times/";
    private static final long SUCCESS_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long FAILURE_TTL_MS = 15L * 1000;
    private static final int PRELOAD_AHEAD = 2;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DanmakuAutoSkipSource");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, Candidate> EPISODE_TITLE_CANDIDATE_CACHE =
            new ConcurrentHashMap<>();
    private static volatile long lastCachePruneAt;
    private static volatile long theIntroDbRetryAt;

    public static class Markers {
        public long introStartMs = -1;
        public long introEndMs = -1;
        public long outroStartMs = -1;
        public long recapStartMs = -1;
        public long recapEndMs = -1;
        public String imdbId = "";
        public long tmdbId;
        public boolean movie;
        public long durationMs;
        public boolean sourcesComplete = true;
        public List<SkipSegmentSources.Segment> segments = new ArrayList<>();
        public String tmdbTitle = "";
        public String source = "";
        public String error = "";

        public boolean hasIntro() {
            return introEndMs > Math.max(0, introStartMs) && introEndMs > introStartMs;
        }

        public boolean hasOutro() {
            return outroStartMs > 0;
        }

        public boolean hasRecap() {
            return recapStartMs >= 0;
        }

        public boolean hasAny() {
            return hasIntro() || hasOutro() || hasRecap() || !segments.isEmpty();
        }
    }

    public interface Callback {
        void onResult(Markers markers);
    }

    private static class Candidate {
        long id;
        String mediaType;
        String title;
        String originalTitle;
        String date;
        boolean animation;
        boolean exact;
    }

    public static class EpisodeTitleMetadata {
        public final boolean episodic;
        public final boolean animation;
        public final String title;
        public final long tmdbId;

        EpisodeTitleMetadata(boolean episodic, boolean animation, String title) {
            this(episodic, animation, title, 0);
        }

        EpisodeTitleMetadata(boolean episodic, boolean animation, String title, long tmdbId) {
            this.episodic = episodic;
            this.animation = animation;
            this.title = title == null ? "" : title;
            this.tmdbId = tmdbId;
        }
    }

    private AutoSkipService() {
    }

    public static boolean refreshAsync(Context context, ChapterManager.MediaInfo media,
                                       DanmakuConfig config, Callback callback) {
        if (context == null || media == null || config == null) return false;
        String key = makeCacheKey(media);
        if (TextUtils.isEmpty(key) || !IN_FLIGHT.add(key)) return false;

        Context appContext = context.getApplicationContext();
        String tmdbKey = config.getTmdbApiKey();
        String language = config.getTmdbLanguage();
        EXECUTOR.execute(() -> {
            Markers result;
            try {
                pruneExpiredCache(appContext);
                result = readCache(appContext, key);
                if (result == null) {
                    result = fetch(appContext, media, tmdbKey, language);
                    writeCache(appContext, key, result);
                }
            } catch (Throwable e) {
                result = new Markers();
                result.sourcesComplete = false;
                result.error = "自动片头查询异常: " + safeMessage(e);
                DanmakuSpider.log(result.error);
            } finally {
                IN_FLIGHT.remove(key);
            }
            final Markers finalResult = result;
            schedulePrefetch(appContext, media, config, finalResult);
            MAIN.post(() -> {
                if (callback != null) callback.onResult(finalResult);
            });
        });
        return true;
    }

    public static void clearCache(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static void pruneExpiredCache(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastCachePruneAt < 24L * 60 * 60 * 1000) return;
        lastCachePruneAt = now;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if ((!entry.getKey().startsWith("seg_")
                    && !entry.getKey().startsWith(EPISODE_TITLE_CACHE_PREFIX))
                    || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject json = new JSONObject((String) entry.getValue());
                if (json.optLong("expires", 0) <= now) {
                    if (editor == null) editor = prefs.edit();
                    editor.remove(entry.getKey());
                }
            } catch (Exception ignored) {
                if (editor == null) editor = prefs.edit();
                editor.remove(entry.getKey());
            }
        }
        if (editor != null) editor.apply();
    }

    public static boolean isValidKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        String value = key.trim();
        return value.matches("(?i)[0-9a-f]{32}") || value.startsWith("eyJ");
    }

    public static String getKeyTypeLabel(String key) {
        if (TextUtils.isEmpty(key)) return "未配置";
        return key.trim().startsWith("eyJ") ? "v4 Bearer Token" : "v3 API Key";
    }

    public static String maskKey(String key) {
        if (TextUtils.isEmpty(key)) return "未配置";
        String value = key.trim();
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    public static String testApiKey(String key) {
        if (!isValidKey(key)) return "Key 格式不正确";
        try {
            String body = tmdbGet("/authentication", key, null);
            JSONObject json = new JSONObject(body);
            if (json.optBoolean("success")) return "TMDB 连接成功";
            int statusCode = json.optInt("status_code", 0);
            if (statusCode == 7 || statusCode == 3) return "TMDB Key 无效或已过期";
            String message = json.optString("status_message", "");
            return TextUtils.isEmpty(message) ? "TMDB 验证失败" : message;
        } catch (Exception e) {
            return "TMDB 验证失败: " + safeMessage(e);
        }
    }

    public static EpisodeTitleMetadata fetchEpisodeTitleMetadata(
            Context context, String title, int season, int episode) {
        if (context == null || TextUtils.isEmpty(title) || season < 0 || episode <= 0) {
            return null;
        }
        DanmakuConfig config = DanmakuConfigManager.getConfig(context);
        String apiKey = config.getTmdbApiKey();
        String language = config.getTmdbLanguage();
        if (!isValidKey(apiKey)) return null;

        Candidate candidate = resolveEpisodeTitleCandidate(title, apiKey, language);
        if (candidate == null || candidate.id <= 0) return null;
        if (!"tv".equals(candidate.mediaType)) {
            return new EpisodeTitleMetadata(false, false, "");
        }

        String cached = readEpisodeTitleCache(
                context, candidate.id, season, episode, language);
        if (cached != null) {
            return new EpisodeTitleMetadata(true, candidate.animation, cached, candidate.id);
        }
        if ("en-US".equalsIgnoreCase(language)) {
            return new EpisodeTitleMetadata(true, candidate.animation, "", candidate.id);
        }

        String result = fetchAndCacheEpisodeTitle(
                context, candidate, season, episode, apiKey, language);
        if (TextUtils.isEmpty(result) && !"en-US".equalsIgnoreCase(language)) {
            result = fetchAndCacheEpisodeTitle(
                    context, candidate, season, episode, apiKey, "en-US");
        }
        return new EpisodeTitleMetadata(true, candidate.animation, result, candidate.id);
    }

    static JSONArray fetchSeriesLogoImages(Context context, long tmdbId) throws Exception {
        if (context == null || tmdbId <= 0) return null;
        String apiKey = DanmakuConfigManager.getConfig(context).getTmdbApiKey();
        if (!isValidKey(apiKey)) return null;
        String body = tmdbGet("/tv/" + tmdbId + "/images", apiKey, "include_image_language=zh");
        JSONObject json = new JSONObject(body);
        if (!json.has("logos")) return null;
        return json.getJSONArray("logos");
    }

    private static Candidate resolveEpisodeTitleCandidate(
            String title, String apiKey, String language) {
        String cacheKey = normalizeForCompare(title) + "|" + language;
        Candidate cached = EPISODE_TITLE_CANDIDATE_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Candidate tv = searchTmdbType(title, apiKey, language, "tv");
        Candidate movie = searchTmdbType(title, apiKey, language, "movie");
        Candidate candidate;
        if (movie != null && movie.exact && (tv == null || !tv.exact)) {
            candidate = movie;
        } else if (tv != null) {
            candidate = tv;
        } else {
            candidate = movie;
        }
        if (candidate != null) {
            EPISODE_TITLE_CANDIDATE_CACHE.put(cacheKey, candidate);
        }
        return candidate;
    }

    public static String fetchEpisodeTitle(Context context, String title,
                                           int season, int episode) {
        EpisodeTitleMetadata metadata = fetchEpisodeTitleMetadata(
                context, title, season, episode);
        return metadata == null || !metadata.episodic ? "" : metadata.title;
    }

    private static String fetchAndCacheEpisodeTitle(Context context, Candidate candidate,
                                                     int season, int episode,
                                                     String apiKey, String language) {
        try {
            String body = tmdbGet("/tv/" + candidate.id + "/season/" + season,
                    apiKey, "language=" + encode(language));
            if (TextUtils.isEmpty(body)) return "";

            JSONArray episodes = new JSONObject(body).optJSONArray("episodes");
            if (episodes == null) return "";
            String currentTitle = "";
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject item = episodes.optJSONObject(i);
                if (item == null) continue;
                int number = item.optInt("episode_number", 0);
                if (number <= 0) continue;
                String name = item.optString("name", "").trim();
                if ("null".equalsIgnoreCase(name)) name = "";
                if (isPlaceholderEpisodeName(name)) name = "";
                if (number == episode) currentTitle = name;
                writeEpisodeTitleCache(context, candidate.id, season, number, language, name);
            }
            if (TextUtils.isEmpty(currentTitle)) {
                writeEpisodeTitleCache(context, candidate.id, season, episode, language, "");
            }
            return currentTitle;
        } catch (Exception e) {
            DanmakuSpider.log("TMDB 集标题查询失败: " + safeMessage(e));
            writeEpisodeTitleCache(context, candidate.id, season, episode, language, "");
            return "";
        }
    }

    private static String readEpisodeTitleCache(Context context, long tmdbId,
                                                int season, int episode, String language) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = episodeTitleCacheKey(tmdbId, season, episode, language);
        String raw = prefs.getString(key, null);
        if (TextUtils.isEmpty(raw)) return null;
        try {
            JSONObject json = new JSONObject(raw);
            if (json.optLong("expires", 0) <= System.currentTimeMillis()) {
                prefs.edit().remove(key).apply();
                return null;
            }
            String cachedName = json.optString("name", "");
            if (isPlaceholderEpisodeName(cachedName)) {
                prefs.edit().remove(key).apply();
                return null;
            }
            return cachedName;
        } catch (Exception e) {
            prefs.edit().remove(key).apply();
            return null;
        }
    }

    private static void writeEpisodeTitleCache(Context context, long tmdbId, int season,
                                               int episode, String language, String title) {
        if (context == null || episode <= 0) return;
        try {
            long ttl = TextUtils.isEmpty(title) ? FAILURE_TTL_MS : SUCCESS_TTL_MS;
            JSONObject json = new JSONObject()
                    .put("expires", System.currentTimeMillis() + ttl)
                    .put("name", title == null ? "" : title);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(episodeTitleCacheKey(tmdbId, season, episode, language),
                            json.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static boolean isPlaceholderEpisodeName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String compact = name.replaceAll("\\s+", "");
        if (compact.matches("第\\d+[集话期]")) return true;
        return compact.matches("(?i)(?:episode|ep|e)\\d+");
    }

    private static String episodeTitleCacheKey(long tmdbId, int season,
                                               int episode, String language) {
        return EPISODE_TITLE_CACHE_PREFIX + tmdbId + "_" + season + "_" + episode
                + "_" + Integer.toHexString((language == null ? "" : language).hashCode());
    }

    private static Markers fetch(Context context, ChapterManager.MediaInfo media,
                                 String apiKey, String language) {
        Markers result = new Markers();
        result.sourcesComplete = false;
        if (!isValidKey(apiKey)) {
            result.error = "TMDB Key 未配置或格式错误";
            return result;
        }
        MediaIdentityResolver.Identity identity = MediaIdentityResolver.resolve(
                media.title, media.artist, media.url);
        String title = identity.getPrimarySearchName();
        if (TextUtils.isEmpty(title)) {
            result.error = "无法识别当前片名";
            return result;
        }

        int[] seasonEpisode = identity.getSeasonEpisode();
        int season = seasonEpisode[0];
        int episode = seasonEpisode[1];
        Candidate candidate = searchTmdb(identity, apiKey, language, episode <= 0);
        if (candidate == null || candidate.id <= 0) {
            String keyStatus = testApiKey(apiKey);
            result.error = keyStatus.contains("成功")
                    ? "TMDB 未匹配到: " + title : keyStatus;
            return result;
        }
        result.tmdbTitle = candidate.title;
        result.tmdbId = candidate.id;
        result.movie = "movie".equals(candidate.mediaType);
        result.durationMs = media.durationMs;

        String imdbId = getImdbId(candidate, apiKey);
        result.imdbId = imdbId;
        if (TextUtils.isEmpty(imdbId)) {
            String keyStatus = testApiKey(apiKey);
            if (!keyStatus.contains("成功")) {
                result.error = keyStatus;
                return result;
            }
        }
        readSegmentSources(result, title, season, episode, media.durationMs);
        if (!result.hasAny()) {
            if (!TextUtils.isEmpty(result.error)) return result;
            result.error = "没有可用的片头片尾数据";
        }
        return result;
    }

    private static void schedulePrefetch(Context context, ChapterManager.MediaInfo media,
                                         DanmakuConfig config, Markers current) {
        if (context == null || media == null || config == null || current == null
                || (TextUtils.isEmpty(current.imdbId) && current.tmdbId <= 0) || current.movie) return;
        if (!config.isAutoSkipEnabled()) return;

        MediaIdentityResolver.Identity identity = MediaIdentityResolver.resolve(
                media.title, media.artist, media.url);
        String title = identity.getPrimarySearchName();
        int[] seasonEpisode = identity.getSeasonEpisode();
        final int season = seasonEpisode[0];
        final int currentEpisode = seasonEpisode[1];
        if (TextUtils.isEmpty(title) || currentEpisode <= 0) return;

        for (int offset = 1; offset <= PRELOAD_AHEAD; offset++) {
            final int episode = currentEpisode + offset;
            final String titleForTask = title;
            // The next episode's duration is unknown; do not reuse this episode's cut selection.
            String cacheKey = makeCacheKey(title, season, episode, 0);
            if (TextUtils.isEmpty(cacheKey) || readCache(context, cacheKey) != null) continue;
            if (!IN_FLIGHT.add(cacheKey)) continue;
            EXECUTOR.execute(() -> {
                try {
                    if (readCache(context, cacheKey) != null) return;
                    Markers prefetched = new Markers();
                    prefetched.imdbId = current.imdbId;
                    prefetched.tmdbId = current.tmdbId;
                    prefetched.tmdbTitle = current.tmdbTitle;
                    readSegmentSources(prefetched, titleForTask, season, episode, 0);
                    if (!prefetched.hasAny() && TextUtils.isEmpty(prefetched.error)) {
                        prefetched.error = "预加载无可用数据";
                    }
                    writeCache(context, cacheKey, prefetched);
                    if (prefetched.hasAny()) {
                        DanmakuSpider.log("Auto 预加载完成: S" + season + "E" + episode
                                + " " + prefetched.source);
                    }
                } catch (Throwable e) {
                    DanmakuSpider.log("Auto 预加载失败: S" + season + "E" + episode
                            + " " + safeMessage(e));
                } finally {
                    IN_FLIGHT.remove(cacheKey);
                }
            });
        }
    }

    private static Candidate searchTmdb(MediaIdentityResolver.Identity identity,
                                        String apiKey, String language,
                                        boolean movieOnly) {
        Candidate fallback = null;
        int attempts = 0;
        for (String title : identity.searchNames) {
            if (attempts >= 4) break;
            if (TextUtils.isEmpty(title)) continue;
            attempts++;
            Candidate candidate = searchTmdb(title, apiKey, language, movieOnly);
            if (candidate == null || candidate.id <= 0) continue;
            if (candidate.exact) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    private static Candidate searchTmdb(String title, String apiKey, String language,
                                        boolean movieOnly) {
        Candidate candidate = searchTmdbType(title, apiKey, language, movieOnly ? "movie" : "tv");
        if (candidate == null && !movieOnly) {
            candidate = searchTmdbType(title, apiKey, language, "movie");
        }
        return candidate;
    }

    private static Candidate searchTmdbType(String title, String apiKey, String language,
                                            String type) {
        try {
            String body = tmdbGet("/search/" + type,
                    apiKey, "query=" + encode(title) + "&language=" + encode(language)
                            + "&include_adult=false&page=1");
            JSONArray results = new JSONObject(body).optJSONArray("results");
            if (results == null || results.length() == 0) return null;

            String normalizedQuery = normalizeForCompare(title);
            Candidate best = null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null || item.optLong("id") <= 0) continue;
                Candidate candidate = new Candidate();
                candidate.id = item.optLong("id");
                candidate.mediaType = type;
                candidate.title = "movie".equals(type)
                        ? item.optString("title") : item.optString("name");
                candidate.originalTitle = "movie".equals(type)
                        ? item.optString("original_title") : item.optString("original_name");
                candidate.date = "movie".equals(type)
                        ? item.optString("release_date") : item.optString("first_air_date");
                JSONArray genres = item.optJSONArray("genre_ids");
                if (genres != null) {
                    for (int j = 0; j < genres.length(); j++) {
                        if (genres.optInt(j) == 16) {
                            candidate.animation = true;
                            break;
                        }
                    }
                }
                if (best == null) best = candidate;
                String normalizedName = normalizeForCompare(candidate.title);
                String normalizedOriginal = normalizeForCompare(candidate.originalTitle);
                if (normalizedQuery.equals(normalizedName)
                        || normalizedQuery.equals(normalizedOriginal)) {
                    candidate.exact = true;
                    return candidate;
                }
            }
            return best;
        } catch (Exception e) {
            DanmakuSpider.log("TMDB 搜索失败: " + safeMessage(e));
            return null;
        }
    }

    private static String getImdbId(Candidate candidate, String apiKey) {
        try {
            String body = tmdbGet("/" + candidate.mediaType + "/" + candidate.id
                    + "/external_ids", apiKey, null);
            String imdbId = new JSONObject(body).optString("imdb_id", "").trim();
            if ("null".equalsIgnoreCase(imdbId) || !imdbId.matches("tt\\d+")) return "";
            return imdbId;
        } catch (Exception e) {
            DanmakuSpider.log("TMDB 外部 ID 查询失败: " + safeMessage(e));
            return "";
        }
    }

    private static void readSegmentSources(Markers result, String title, int season, int episode, long durationMs) {
        result.sourcesComplete = true;
        if (!TextUtils.isEmpty(result.imdbId)) {
            String url = INTRODB_SEGMENTS + "?imdb_id=" + encode(result.imdbId)
                    + (result.movie ? "&is_movie=true" : "&season=" + Math.max(0, season)
                    + "&episode=" + Math.max(1, episode) + "&is_movie=false");
            JSONObject json = segmentGet(url, false);
            if (json == null) result.sourcesComplete = false;
            if (json != null) SkipSegmentSources.merge(result, SkipSegmentSources.introDb(json, durationMs), durationMs);
        }
        if (result.tmdbId > 0 || !TextUtils.isEmpty(result.imdbId)) {
            JSONObject json = segmentGet(theIntroDbUrl(result, season, episode, durationMs), true);
            if (json == null) result.sourcesComplete = false;
            if (json != null) SkipSegmentSources.merge(result,
                    SkipSegmentSources.theIntroDb(json, durationMs, Math.max(0, season), Math.max(1, episode), result.movie),
                    durationMs);
        }
        if (!result.movie && !result.hasIntro() && !result.hasOutro()) {
            readAniSkip(result, title, season, episode, durationMs);
        }
        if (result.hasAny()) result.error = "";
    }

    static String theIntroDbUrl(Markers result, int season, int episode, long durationMs) {
        StringBuilder url = new StringBuilder(THEINTRODB_MEDIA);
        if (result.tmdbId > 0) url.append("?tmdb_id=").append(result.tmdbId);
        else url.append("?imdb_id=").append(encode(result.imdbId));
        if (!result.movie) url.append("&season=").append(Math.max(0, season))
                .append("&episode=").append(Math.max(1, episode));
        if (durationMs > 0) url.append("&duration_ms=").append(durationMs);
        return url.append("&details=true&merge_unknown=false").toString();
    }

    private static JSONObject segmentGet(String url, boolean theIntroDb) {
        if (theIntroDb && System.currentTimeMillis() < theIntroDbRetryAt) return null;
        try (okhttp3.Response response = OkHttp.client().newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                .newCall(new okhttp3.Request.Builder().url(url).header("Accept", "application/json").build()).execute()) {
            if (response.code() == 429 && theIntroDb) {
                long seconds = 60;
                try { seconds = Long.parseLong(response.header("Retry-After", "60")); } catch (Exception ignored) {}
                if (response.body() != null) {
                    try { seconds = new JSONObject(response.body().string()).optLong("retry_after_seconds", seconds); }
                    catch (Exception ignored) {}
                }
                theIntroDbRetryAt = System.currentTimeMillis() + Math.max(10, Math.min(seconds, 86400)) * 1000;
                DanmakuSpider.log("TheIntroDB 限流，稍后重试");
                return null;
            }
            if (response.code() == 404) return new JSONObject();
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject json = new JSONObject(response.body().string());
            return json.has("error") ? null : json;
        } catch (Exception error) {
            DanmakuSpider.log((theIntroDb ? "TheIntroDB" : "IntroDB")
                    + " 标记查询失败: " + error.getClass().getSimpleName());
            return null;
        }
    }

    private static void readAniSkip(Markers result, String title, int season,
                                    int episode, long durationMs) {
        try {
            int malId = searchMalId(title);
            if (malId <= 0 || episode <= 0) return;
            int length = durationMs > 0 ? (int) Math.max(1, Math.round(durationMs / 1000.0)) : 0;
            String url = ANISKIP_BASE + malId + "/" + episode
                    + "?types%5B%5D=op&types%5B%5D=ed&types%5B%5D=mixed-op"
                    + "&types%5B%5D=mixed-ed&episodeLength=" + length;
            JSONObject json = new JSONObject(OkHttp.string(url, 12000));
            if (!json.optBoolean("found")) return;
            JSONArray results = json.optJSONArray("results");
            if (results == null) return;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                JSONObject interval = item == null ? null : item.optJSONObject("interval");
                if (interval == null) continue;
                String skipType = item.optString("skipType", "");
                long start = Math.round(interval.optDouble("startTime", -1) * 1000);
                long end = Math.round(interval.optDouble("endTime", -1) * 1000);
                if (end <= start) continue;
                if ("op".equals(skipType) || "mixed-op".equals(skipType)) {
                    result.introStartMs = start;
                    result.introEndMs = end;
                    SkipSegmentSources.addSource(result, "AniSkip");
                } else if ("ed".equals(skipType) || "mixed-ed".equals(skipType)) {
                    result.outroStartMs = start;
                    SkipSegmentSources.addSource(result, "AniSkip");
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static int searchMalId(String title) {
        try {
            JSONObject variables = new JSONObject().put("search", title);
            JSONObject payload = new JSONObject()
                    .put("query", "query($search:String){Page(perPage:5){media(search:$search,type:ANIME){idMal title{romaji english native} startDate{year}}}}")
                    .put("variables", variables);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            String body = OkHttp.post(ANILIST_URL, payload.toString(), headers).getBody();
            JSONArray media = new JSONObject(body).optJSONObject("data")
                    .optJSONObject("Page").optJSONArray("media");
            if (media == null) return 0;
            for (int i = 0; i < media.length(); i++) {
                int id = media.optJSONObject(i).optInt("idMal", 0);
                if (id > 0) return id;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String tmdbGet(String path, String key, String extraQuery) {
        boolean bearer = key != null && key.trim().startsWith("eyJ");
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (bearer) headers.put("Authorization", "Bearer " + key.trim());

        String body = OkHttp.string(buildTmdbUrl(TMDB_BASE, path, key, extraQuery),
                null, headers, 12000);
        if (!TextUtils.isEmpty(body) && !body.trim().equals("{}")) return body;
        return OkHttp.string(buildTmdbUrl(TMDB_FALLBACK_BASE, path, key, extraQuery),
                null, headers, 12000);
    }

    private static String buildTmdbUrl(String base, String path, String key, String extraQuery) {
        boolean bearer = key != null && key.trim().startsWith("eyJ");
        StringBuilder url = new StringBuilder(base).append(path);
        boolean hasQuery = extraQuery != null && !extraQuery.isEmpty();
        if (!bearer || hasQuery) url.append('?');
        if (!bearer) {
            url.append("api_key=").append(encode(key));
            if (hasQuery) url.append('&');
        }
        if (hasQuery) url.append(extraQuery);
        return url.toString();
    }

    private static Markers readCache(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(cachePrefKey(key), null);
        if (TextUtils.isEmpty(raw)) return null;
        try {
            JSONObject json = new JSONObject(raw);
            if (json.optInt("version", 0) != SEGMENT_CACHE_VERSION
                    || json.optLong("expires", 0) <= System.currentTimeMillis()) {
                prefs.edit().remove(cachePrefKey(key)).apply();
                return null;
            }
            Markers result = new Markers();
            result.introStartMs = json.optLong("introStart", -1);
            result.introEndMs = json.optLong("introEnd", -1);
            result.outroStartMs = json.optLong("outroStart", -1);
            result.recapStartMs = json.optLong("recapStart", -1);
            result.recapEndMs = json.optLong("recapEnd", -1);
            result.imdbId = json.optString("imdbId", "");
            result.tmdbId = json.optLong("tmdbId", 0);
            result.movie = json.optBoolean("movie", false);
            result.durationMs = json.optLong("durationMs", 0);
            result.sourcesComplete = json.optBoolean("sourcesComplete", false);
            JSONArray segments = json.optJSONArray("segments");
            if (segments != null) for (int i = 0; i < segments.length(); i++) {
                JSONObject segment = segments.optJSONObject(i);
                if (segment != null) {
                    SkipSegmentSources.Segment saved = new SkipSegmentSources.Segment(
                            segment.optString("type"), segment.optLong("start", -1),
                            segment.optLong("end", -1), segment.optString("source"));
                    saved.durationMatched = segment.optBoolean("durationMatched", false);
                    result.segments.add(saved);
                }
            }
            result.tmdbTitle = json.optString("tmdbTitle", "");
            result.source = json.optString("source", "");
            result.error = json.optString("error", "");
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context context, String key, Markers value) {
        if (context == null || value == null) return;
        try {
            long ttl = value.hasAny() && value.sourcesComplete ? SUCCESS_TTL_MS : FAILURE_TTL_MS;
            JSONArray segments = new JSONArray();
            for (SkipSegmentSources.Segment segment : value.segments) {
                segments.put(new JSONObject().put("type", segment.type).put("start", segment.startMs)
                        .put("end", segment.endMs).put("source", segment.source)
                        .put("durationMatched", segment.durationMatched));
            }
            JSONObject json = new JSONObject()
                    .put("version", SEGMENT_CACHE_VERSION)
                    .put("expires", System.currentTimeMillis() + ttl)
                    .put("introStart", value.introStartMs)
                    .put("introEnd", value.introEndMs)
                    .put("outroStart", value.outroStartMs)
                    .put("recapStart", value.recapStartMs)
                    .put("recapEnd", value.recapEndMs)
                    .put("imdbId", value.imdbId)
                    .put("tmdbId", value.tmdbId)
                    .put("movie", value.movie)
                    .put("durationMs", value.durationMs)
                    .put("sourcesComplete", value.sourcesComplete)
                    .put("segments", segments)
                    .put("tmdbTitle", value.tmdbTitle)
                    .put("source", value.source)
                    .put("error", value.error);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(cachePrefKey(key), json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String makeCacheKey(ChapterManager.MediaInfo media) {
        MediaIdentityResolver.Identity identity = MediaIdentityResolver.resolve(
                media.title, media.artist, media.url);
        String title = identity.getPrimarySearchName();
        int[] seasonEpisode = identity.getSeasonEpisode();
        return makeCacheKey(title, seasonEpisode[0], seasonEpisode[1], media.durationMs);
    }

    private static String makeCacheKey(String title, int season, int episode, long durationMs) {
        if (TextUtils.isEmpty(title)) return "";
        return MediaIdentityResolver.normalizeForCompare(title)
                + "|" + season + "|" + episode + "|d" + Math.max(0, Math.round(durationMs / 1000.0));
    }

    private static String cachePrefKey(String key) {
        return "seg_" + Integer.toHexString(key.hashCode());
    }

    private static String normalizeForCompare(String value) {
        return MediaIdentityResolver.normalizeForCompare(value);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message) ? throwable.getClass().getSimpleName() : message;
    }
}
