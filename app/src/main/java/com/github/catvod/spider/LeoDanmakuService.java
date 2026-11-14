package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.spider.entity.DanmakuItem;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class LeoDanmakuService {

    // 线程池
    private static final ExecutorService searchExecutor = Executors.newFixedThreadPool(4);
    // 优化防重复推送：针对每个URL记录推送时间
    private static final Map<String, Long> lastPushTimes = new ConcurrentHashMap<>();
    private static final long PUSH_MIN_INTERVAL = 3000; // 3秒内不重复推送
    private static final String PUSH_GUARD_PREFS = "danmaku_push_guard";
    private static final long[] AUTO_EMPTY_RETRY_DELAYS_MS = {
            15000L, 30000L, 60000L, 90000L, 120000L, 180000L
    };
    private static final Map<String, Integer> autoRetryAttempts = new ConcurrentHashMap<>();
    private static final Map<String, ScheduledFuture<?>> autoRetryTasks = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService autoRetryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "DanmakuAutoRetry");
                thread.setDaemon(true);
                return thread;
            });

    // 在 LeoDanmakuService 类中添加缓存相关字段
    private static final long CACHE_EXPIRE_TIME = 30 * 60 * 1000; // 30分钟

    // 新增：搜索结果封装类
    public static class SearchResult {
        public boolean found = false;
        public double similarity = 0.0;
        public DanmakuItem item = null;
        public String matchedName = null; // 实际参与相似度计算的名称

        public SearchResult(boolean found, double similarity, DanmakuItem item) {
            this.found = found;
            this.similarity = similarity;
            this.item = item;
        }

        public SearchResult(boolean found, double similarity, DanmakuItem item, String matchedName) {
            this.found = found;
            this.similarity = similarity;
            this.item = item;
            this.matchedName = matchedName;
        }
    }

    // 执行搜索
    public static List<DanmakuItem> searchDanmaku(EpisodeInfo episodeInfo, Activity activity) {
        return searchDanmaku(episodeInfo, activity, false);
    }

    // 执行搜索（带是否使用集数标识）
    public static List<DanmakuItem> searchDanmaku(EpisodeInfo episodeInfo, Activity activity, boolean useEpisodeNum) {
        if (episodeInfo == null || episodeInfo.getEpisodeNames() == null || episodeInfo.getEpisodeNames().isEmpty()) return new ArrayList<>();
        final EpisodeInfo queryInfo = createSearchInfo(episodeInfo, episodeInfo.getEpisodeNames().get(0));
        String keyword = queryInfo.getEpisodeNames().get(0);
        final String comparisonKeyword = MediaIdentityResolver.normalizeForCompare(keyword);
        if (TextUtils.isEmpty(comparisonKeyword)) return new ArrayList<>();

        final List<DanmakuItem> globalResults = Collections.synchronizedList(new ArrayList<DanmakuItem>());

        try {
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            List<String> targets = new ArrayList<>(config.getApiUrls());
            if (targets.isEmpty()) {
                DanmakuSpider.log("没有配置API地址");
                Utils.safeShowToast(activity, "没有配置API地址");
                return globalResults;
            }

            ExecutorCompletionService<List<DanmakuItem>> completionService =
                    new ExecutorCompletionService<>(searchExecutor);
            int pendingTasks = 0;

            for (final String url : targets) {
                completionService.submit(new Callable<List<DanmakuItem>>() {
                    @Override
                    public List<DanmakuItem> call() throws Exception {
                        return doSearch(url, queryInfo, useEpisodeNum);
                    }
                });
                pendingTasks++;
            }

            // 超时控制
            long endTime = System.currentTimeMillis() + 30000;

            while (pendingTasks > 0) {
                long timeLeft = endTime - System.currentTimeMillis();
                if (timeLeft <= 0) break;

                try {
                    long wait = globalResults.isEmpty() ? 8000 : 50;
                    if (wait > timeLeft) wait = timeLeft;

                    java.util.concurrent.Future<List<DanmakuItem>> future =
                            completionService.poll(wait, TimeUnit.MILLISECONDS);
                    if (future != null) {
                        List<DanmakuItem> res = future.get();
                        pendingTasks--;

                        if (res != null && !res.isEmpty()) {
                            // 过滤结果
                            java.util.Iterator<DanmakuItem> it = res.iterator();
                            while (it.hasNext()) {
                                DanmakuItem item = it.next();
                                String title = MediaIdentityResolver.normalizeForCompare(item.title);
                                if (TextUtils.isEmpty(title) || (!title.contains(comparisonKeyword)
                                        && !comparisonKeyword.contains(title))) {
                                    it.remove();
                                }
                            }

                            if (!res.isEmpty()) {
                                DanmakuSpider.log("找到弹幕结果: " + res.size() + " 个");
                                globalResults.addAll(res);
                            }
                        }
                    } else {
                        if (!globalResults.isEmpty()) break;
                    }
                } catch (Exception e) {
                    pendingTasks--;
                }
            }
        } catch (Exception e) {
            DanmakuSpider.log("搜索异常: " + e.getMessage());
        }

        // 将List转换为ConcurrentMap
        ConcurrentHashMap<Integer, DanmakuItem> resultMap = new ConcurrentHashMap<>();
        for (DanmakuItem item : globalResults) {
            resultMap.put(item.getEpId(), item);
        }
        DanmakuManager.lastDanmakuItemMap = resultMap;

        return globalResults;
    }

    // Each provider task owns a snapshot; alias/fallback searches must not mutate its keyword.
    private static EpisodeInfo createSearchInfo(EpisodeInfo source, String keyword) {
        EpisodeInfo query = new EpisodeInfo();
        query.setEpisodeNames(new ArrayList<>(Collections.singletonList(
                MediaIdentityResolver.cleanSearchText(keyword))));
        query.setEpisodeNum(MediaIdentityResolver.cleanSearchText(source.getEpisodeNum()));
        query.setEpisodeYear(MediaIdentityResolver.cleanSearchText(source.getEpisodeYear()));
        query.setEpisodeSeasonNum(source.getEpisodeSeasonNum());
        query.setSeriesName(source.getSeriesName());
        query.setFileName(source.getFileName());
        query.setOriginalTitle(source.getOriginalTitle());
        query.setEpisodeUrl(source.getEpisodeUrl());
        return query;
    }

    // 执行搜索
    private static List<DanmakuItem> doSearch(String apiBase, EpisodeInfo episodeInfo) {
        return doSearch(apiBase, episodeInfo, false);
    }

    // 执行搜索（带是否使用集数标识）
    private static List<DanmakuItem> doSearch(String apiBase, EpisodeInfo episodeInfo, boolean useEpisodeNum) {
        List<DanmakuItem> list = new ArrayList<>();
        try {
            String keyword = episodeInfo.getEpisodeNames().get(0);
            // 尝试多种API路径
            String searchUrl = apiBase + "/api/v2/search/episodes?anime=" +
                URLEncoder.encode(keyword, "UTF-8");
            // 如果 useEpisodeNum 为 true 且 episodeNum 不为空，拼接 episode 参数
            if (useEpisodeNum && !TextUtils.isEmpty(episodeInfo.getEpisodeNum())) {
                searchUrl += "&episode=" + URLEncoder.encode(episodeInfo.getEpisodeNum(), "UTF-8");
            }
            DanmakuSpider.log("搜索URL: " + searchUrl);

            String json = NetworkUtils.robustHttpGet(searchUrl);

            // 回退到旧API
            if (TextUtils.isEmpty(json)) {
                searchUrl = apiBase + "/search/episodes?anime=" +
                    URLEncoder.encode(keyword, "UTF-8");
                // 如果 useEpisodeNum 为 true 且 episodeNum 不为空，拼接 episode 参数
                if (useEpisodeNum && !TextUtils.isEmpty(episodeInfo.getEpisodeNum())) {
                    searchUrl += "&episode=" + URLEncoder.encode(episodeInfo.getEpisodeNum(), "UTF-8");
                }
                DanmakuSpider.log("回退搜索URL: " + searchUrl);
                json = NetworkUtils.robustHttpGet(searchUrl);
            }

            if (TextUtils.isEmpty(json)) {
                DanmakuSpider.log("搜索响应为空");
                return list;
            }

            // 解析JSON
            JSONArray array = null;
            JSONObject rootOpt = null;

            if (json.trim().startsWith("[")) {
                array = new JSONArray(json);
            } else {
                rootOpt = new JSONObject(json);
                if (rootOpt.has("episodes")) array = rootOpt.optJSONArray("episodes");
                else if (rootOpt.has("animes")) array = rootOpt.optJSONArray("animes");
            }

            if (array == null) {
                DanmakuSpider.log("未找到episodes/animes数组");
                return list;
            }

            // 判断数据结构
            boolean isAnimeList = false;
            if (array.length() > 0) {
                JSONObject first = array.optJSONObject(0);
                if (first != null && first.has("episodes") && !first.has("episodeId")) {
                    isAnimeList = true;
                }
                if (rootOpt != null && rootOpt.has("animes")) {
                    isAnimeList = true;
                }
            }

            if (isAnimeList) {
                // 嵌套结构
                for (int i = 0; i < array.length(); i++) {
                    JSONObject anime = array.optJSONObject(i);
                    String animeTitle = anime.optString("animeTitle");
                    if (TextUtils.isEmpty(animeTitle)) animeTitle = anime.optString("title");

                    JSONArray eps = anime.optJSONArray("episodes");
                    if (eps != null) {
                        for (int j = 0; j < eps.length(); j++) {
                            JSONObject ep = eps.optJSONObject(j);
                            processEpisode(ep, animeTitle, apiBase, list);
                        }
                    }
                }
            } else {
                // 扁平结构
                for (int i = 0; i < array.length(); i++) {
                    JSONObject ep = array.optJSONObject(i);
                    processEpisode(ep, null, apiBase, list);
                }
            }
        } catch (Exception e) {
            DanmakuSpider.log("搜索解析错误: " + e.getMessage());
            e.printStackTrace();
        }

        return list;
    }

    // 处理单集数据
    private static void processEpisode(JSONObject ep, String forcedTitle, String apiBase, List<DanmakuItem> list) {
        String animeTitle = forcedTitle;
        if (TextUtils.isEmpty(animeTitle)) animeTitle = ep.optString("animeTitle");
        if (TextUtils.isEmpty(animeTitle)) animeTitle = ep.optString("title");
        if (TextUtils.isEmpty(animeTitle)) animeTitle = ep.optString("name");

        String epTitle = ep.optString("episodeTitle");
        if (TextUtils.isEmpty(epTitle)) epTitle = ep.optString("epTitle");

        int epId = ep.optInt("episodeId", ep.optInt("epId", ep.optInt("id")));

        if (TextUtils.isEmpty(animeTitle)) {
            return;
        }

        DanmakuItem item = new DanmakuItem();
        item.title = animeTitle;
        item.epTitle = epTitle;
        item.epId = epId;
        item.apiBase = apiBase;

        String[] parts = animeTitle.split("(?i)from"); // 使用不区分大小写的正则表达式
        if (parts.length > 1) {
            String fromPart = parts[1].trim();
            if (!fromPart.isEmpty()) { // 额外检查分割后的部分是否为空
                item.from = fromPart;
                item.animeTitle = parts[0].trim();
            }
        } else {
            item.animeTitle = animeTitle;
        }


        // 清理标题
        String temp = epTitle.replace(animeTitle, "");
        temp = temp.replaceAll("【.*?】", "").replaceAll("\\[.*?\\]", "").trim();
        if (temp.startsWith("-") || temp.startsWith("_")) {
            temp = temp.substring(1).trim();
        }

        item.shortTitle = temp;
        if (TextUtils.isEmpty(item.shortTitle)) {
            item.shortTitle = epTitle;
        }

        list.add(item);
    }

    // 自动搜索 - 已修改为返回 SearchResult
    public static SearchResult autoSearch(String searchKeyword, EpisodeInfo episodeInfo, Activity activity) {
        searchKeyword = MediaIdentityResolver.cleanSearchText(searchKeyword);
        if (TextUtils.isEmpty(searchKeyword) || episodeInfo == null) {
            return new SearchResult(false, 0, null);
        }
        episodeInfo = createSearchInfo(episodeInfo, searchKeyword);

        // 自动搜索时使用集数参数
        List<DanmakuItem> results = filterAutoResults(
                searchDanmaku(episodeInfo, activity, true), episodeInfo);

        // Some providers return episode 1 for any episode parameter. Query the full list,
        // but keep the requested episode constraint throughout every fallback.
        if (results.isEmpty() && !TextUtils.isEmpty(episodeInfo.getEpisodeNum())) {
            DanmakuSpider.log("带集数搜索无有效匹配，查询全集并严格校验集数");
            results = filterAutoResults(searchDanmaku(episodeInfo, activity, false), episodeInfo);
        }

        // 仍然无结果，尝试去掉符号重试（如 铁血战士：杀戮之地 → 铁血战士杀戮之地）
        if (results.isEmpty() && episodeInfo.getEpisodeNames() != null && !episodeInfo.getEpisodeNames().isEmpty()) {
            String rawKeyword = episodeInfo.getEpisodeNames().get(0);
            String cleanedKeyword = rawKeyword.replaceAll("[：*！@#￥%……&*（）_\\-/\\\\]", "");
            if (!cleanedKeyword.equals(rawKeyword) && !TextUtils.isEmpty(cleanedKeyword)) {
                DanmakuSpider.log("未找到结果，尝试去掉符号重试: " + rawKeyword + " -> " + cleanedKeyword);
                String original = episodeInfo.getEpisodeNames().get(0);
                episodeInfo.getEpisodeNames().set(0, cleanedKeyword);
                results = filterAutoResults(searchDanmaku(episodeInfo, activity, false), episodeInfo);
                episodeInfo.getEpisodeNames().set(0, original);
            }
        }

        // 仍然无结果，尝试去掉年份/季数重试（如 耀眼2026 → 耀眼）
        if (results.isEmpty() && episodeInfo.getEpisodeNames() != null && !episodeInfo.getEpisodeNames().isEmpty()) {
            String rawKeyword = episodeInfo.getEpisodeNames().get(0);
            String cleanedKeyword = rawKeyword
                    .replaceAll("(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])", "")
                    .replaceAll("[Ss]\\d+", "")
                    .replaceAll("第\\s*\\d+\\s*季", "")
                    .replaceAll("Season\\s*\\d+", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!cleanedKeyword.equals(rawKeyword) && !TextUtils.isEmpty(cleanedKeyword)) {
                DanmakuSpider.log("未找到结果，尝试去掉年份/季数重试: " + rawKeyword + " -> " + cleanedKeyword);
                String original = episodeInfo.getEpisodeNames().get(0);
                episodeInfo.getEpisodeNames().set(0, cleanedKeyword);
                results = filterAutoResults(searchDanmaku(episodeInfo, activity, false), episodeInfo);
                episodeInfo.getEpisodeNames().set(0, original);
            }
        }

        if (results.isEmpty()) {
            DanmakuSpider.log("自动搜索未找到任何结果 for keyword: " + searchKeyword);
            return new SearchResult(false, 0, null);
        }

        DanmakuItem selectedItem = null;
        double bestSimilarity = -1.0;
        String bestMatchedName = null; // 记录最佳匹配时使用的名称
        String bestSearchKeyword = null; // 记录最佳匹配时的搜索关键词

        // Only verified candidates are eligible; never reintroduce rejected episodes.
        for (DanmakuItem item : results) {
            // 准备用于比较的标题
            String rawTitle = item.getAnimeTitle() != null ? item.getAnimeTitle() : item.getTitle();

            String titleToCompare = rawTitle.split("【")[0].trim();
            String s2 = searchKeyword;
            if (TextUtils.isEmpty(episodeInfo.getEpisodeYear())) {
                // 使用原始 searchKeyword (不带年份) 进行相似度计算
                // "先查找(年份)并替换成空字符串"
                titleToCompare = titleToCompare.replaceAll("\\s*\\(\\d{4}\\)\\s*", "");
            } else {
                    s2 = searchKeyword.replaceAll("\\d{4}", "").trim() + "(" + episodeInfo.getEpisodeYear() + ")";
            }

            double similarity = calculateSimilarity(titleToCompare, s2);

            DanmakuSpider.log("🤔 比较: " + titleToCompare + " vs " + s2 + " (相似度: " + similarity + ")");

            if (item.getAnimeTitle() != null && item.getAnimeTitle().contains("NaN")) {
                similarity -= 0.5; // 惩罚
            }

            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                selectedItem = item;
                bestMatchedName = titleToCompare; // 直接记录计算时使用的名称
                bestSearchKeyword = s2; // 记录搜索关键词
            } else if (similarity == bestSimilarity && similarity > 0 && episodeInfo.getEpisodeNum() != null
                    && item.epTitle != null) {
                // 同分时，优先选集数匹配的项（数字比较，避免 "08" vs "第8集" 不匹配）
                int itemEp = MediaIdentityResolver.parseEpisodeNumber(item.epTitle);
                int targetEp = MediaIdentityResolver.parseEpisodeNumber(
                        episodeInfo.getEpisodeNum());
                if (itemEp > 0 && itemEp == targetEp) {
                    selectedItem = item;
                    bestMatchedName = titleToCompare;
                    bestSearchKeyword = s2;
                }
            }
        }

        if (selectedItem != null) {
            DanmakuSpider.log("🎯 在校验结果中自动搜索选择: " + selectedItem.title + " - " + selectedItem.epTitle + " (相似度: " + bestSimilarity + ")");
            return new SearchResult(true, bestSimilarity, selectedItem, bestMatchedName);
        }

        return new SearchResult(false, 0, null, null);
    }

    private static List<DanmakuItem> filterAutoResults(List<DanmakuItem> results, EpisodeInfo info) {
        List<DanmakuItem> matched = new ArrayList<>();
        int targetEpisode = MediaIdentityResolver.parseEpisodeNumber(info.getEpisodeNum());
        for (DanmakuItem item : results) {
            if (!TextUtils.isEmpty(info.getEpisodeYear())
                    && !MediaIdentityResolver.cleanSearchText(item.title).contains(info.getEpisodeYear())) {
                continue;
            }
            if (targetEpisode > 0) {
                int candidateEpisode = MediaIdentityResolver.parseDanmakuEpisodeNumber(item.epTitle);
                // Unknown episode numbers are not evidence that this is the requested episode.
                if (candidateEpisode != targetEpisode) continue;
            }
            matched.add(item);
        }
        DanmakuSpider.log("弹幕集数校验: 请求=" + info.getEpisodeNum()
                + ", 候选=" + results.size() + ", 有效=" + matched.size());
        return matched;
    }

    private static double calculateSimilarity(String s1, String s2) {
        s1 = MediaIdentityResolver.normalizeForCompare(s1);
        s2 = MediaIdentityResolver.normalizeForCompare(s2);
        String longer = s1, shorter = s2;
        if (s1.length() < s2.length()) {
            longer = s2;
            shorter = s1;
        }
        int longerLength = longer.length();
        if (longerLength == 0) {
            return 0.0;
        }
        return (longerLength - editDistance(longer, shorter)) / (double) longerLength;
    }

    private static int editDistance(String s1, String s2) {
        s1 = s1.toLowerCase();
        s2 = s2.toLowerCase();

        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        }
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) {
                costs[s2.length()] = lastValue;
            }
        }
        return costs[s2.length()];
    }

    // 手动搜索（不使用集数参数，显示全部集数供用户选择）
    public static List<DanmakuItem> manualSearch(EpisodeInfo episodeInfo, Activity activity) {
        List<DanmakuItem> results = new ArrayList<>();

        if (episodeInfo == null || episodeInfo.getEpisodeNames() == null || episodeInfo.getEpisodeNames().isEmpty()) return results;
        episodeInfo = createSearchInfo(episodeInfo, episodeInfo.getEpisodeNames().get(0));

        try {
            // 手动搜索时不使用集数参数，让用户能看到全部集数
            results = searchDanmaku(episodeInfo, activity, false);

            // 回退1：去掉符号重新搜索
            if (results.isEmpty()) {
                String raw = episodeInfo.getEpisodeNames().get(0);
                String cleaned = raw.replaceAll("[：*！@#￥%……&*（）_\\-/\\\\]", "");
                if (!cleaned.equals(raw) && !TextUtils.isEmpty(cleaned)) {
                    DanmakuSpider.log("手动搜索无结果，尝试去掉符号重试: " + raw + " -> " + cleaned);
                    episodeInfo.getEpisodeNames().set(0, cleaned);
                    results = searchDanmaku(episodeInfo, activity, false);
                    episodeInfo.getEpisodeNames().set(0, raw);
                }
            }

            // 回退2：去掉年份/季数重新搜索
            if (results.isEmpty()) {
                String raw = episodeInfo.getEpisodeNames().get(0);
                String cleaned = raw
                        .replaceAll("(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])", "")
                        .replaceAll("[Ss]\\d+", "")
                        .replaceAll("第\\s*\\d+\\s*季", "")
                        .replaceAll("Season\\s*\\d+", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                if (!cleaned.equals(raw) && !TextUtils.isEmpty(cleaned)) {
                    DanmakuSpider.log("手动搜索无结果，尝试去掉年份/季数重试: " + raw + " -> " + cleaned);
                    episodeInfo.getEpisodeNames().set(0, cleaned);
                    results = searchDanmaku(episodeInfo, activity, false);
                    episodeInfo.getEpisodeNames().set(0, raw);
                }
            }
        } catch (Exception e) {
            DanmakuSpider.log("手动搜索失败: " + e.getMessage());
        }

        return results;
    }

    // 直接推送弹幕URL
    public static void pushDanmakuDirect(DanmakuItem danmakuItem, Activity activity, boolean isAuto) {
        String danmakuUrl = danmakuItem.getDanmakuUrl();
        if (TextUtils.isEmpty(danmakuUrl)) {
            DanmakuSpider.log("⚠️ 推送弹幕URL为空，跳过");
            return;
        }
        if (isAuto && !HostDanmakuGate.allowsAutoPush(activity)) { cancelAllAutoRetries(); return; }
        if (!isAuto) clearAutoRetryState(danmakuUrl);

        long currentTime = System.currentTimeMillis();

        // 检查此URL的上次推送时间
        Long lastPush = lastPushTimes.get(danmakuUrl);
        if (lastPush != null && (currentTime - lastPush < PUSH_MIN_INTERVAL)) {
            DanmakuSpider.log("⚠️ 推送过于频繁 (同一URL)，跳过: " + danmakuUrl);
            return;
        }
        if (!reserveProcessPushSlot(danmakuUrl, currentTime)) {
            DanmakuSpider.log("⚠️ 另一个插件实例已推送同一弹幕，跳过: " + danmakuUrl);
            return;
        }

        // 更新推送时间并清理旧记录
        lastPushTimes.put(danmakuUrl, currentTime);
        cleanupOldPushTimes(currentTime);

        // 记录弹幕URL（这个可以在主线程执行）
        DanmakuSpider.recordDanmakuUrl(danmakuItem, isAuto);
        String expectedVideoSignature = DanmakuManager.currentVideoSignature;

        // 在网络请求前检查是否在主线程
        boolean isMainThread = Looper.myLooper() == Looper.getMainLooper();
        if (isMainThread) {
            DanmakuSpider.log("警告：推送弹幕在主线程调用，切换到子线程");
            // 切换到子线程执行
            new Thread(new Runnable() {
                @Override
                public void run() {
                    pushDanmakuInThread(danmakuItem, activity, isAuto, expectedVideoSignature);
                }
            }).start();
        } else {
            // 已经在子线程，直接执行
            DanmakuSpider.log("已经在子线程，直接执行弹幕推送");
            pushDanmakuInThread(danmakuItem, activity, isAuto, expectedVideoSignature);
        }
    }

    // 清理旧的推送记录，防止Map无限增大
    private static void cleanupOldPushTimes(long currentTime) {
        // 清理超过5分钟的记录
        long cleanupThreshold = 5 * 60 * 1000;
        Iterator<Map.Entry<String, Long>> iterator = lastPushTimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (currentTime - entry.getValue() > cleanupThreshold) {
                iterator.remove();
            }
        }
    }

    private static boolean reserveProcessPushSlot(String danmakuUrl, long currentTime) {
        Context context = Init.context();
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(
                PUSH_GUARD_PREFS, Context.MODE_PRIVATE);
        String key = "last_" + Integer.toHexString(danmakuUrl.hashCode());
        synchronized (prefs) {
            long lastPush = prefs.getLong(key, 0L);
            if (lastPush > 0 && currentTime - lastPush < PUSH_MIN_INTERVAL) {
                return false;
            }
            prefs.edit().putLong(key, currentTime).apply();
            return true;
        }
    }

    // 单独的网络推送方法，确保在子线程中执行
    private static void pushDanmakuInThread(DanmakuItem danmakuItem, Activity activity,
                                            boolean isAuto, String expectedVideoSignature) {
        try {
            if (isAuto && !canContinueAutoPush(activity, expectedVideoSignature)) return;
            if (TextUtils.isEmpty(danmakuItem.getDanmakuUrl())) {
                DanmakuSpider.log("推送弹幕URL为空");
                return;
            }

            DanmakuSpider.apiUrl = danmakuItem.getApiBase();

            // 步骤1: 先获取弹幕数据，验证是否有效
            String danmakuData = null;
            int danmakuCount = 0;
            final int maxRetries = 3;

            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    if (isAuto && !canContinueAutoPush(activity, expectedVideoSignature)) return;
                    danmakuData = NetworkUtils.robustHttpGet(danmakuItem.getDanmakuUrl());
                    DanmakuSpider.log("获取弹幕数据 (尝试 " + (attempt + 1) + "/" + maxRetries + ") - URL: " + danmakuItem.getDanmakuUrl());

                    // 直接尝试解析，如果成功（返回-1代表解析异常，0代表无内容，大于0代表成功）
                    danmakuCount = countDanmakuItems(danmakuData);
                    if (danmakuCount > 0) {
                        DanmakuSpider.log("✅ 获取到有效弹幕数据，总数: " + danmakuCount + " 条");
                        break; // 成功获取，跳出重试
                    } else if (danmakuCount == 0) {
                        DanmakuSpider.log("⚠️ 弹幕数据为空或无内容，尝试次数: " + (attempt + 1) + "/" + maxRetries);
                    } else {
                        DanmakuSpider.log("⚠️ 弹幕数据格式错误或解析失败，尝试次数: " + (attempt + 1) + "/" + maxRetries);
                    }

                    // 重试等待
                    if (attempt < maxRetries - 1) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } catch (Exception e) {
                    DanmakuSpider.log("获取弹幕数据异常 (尝试 " + (attempt + 1) + "/" + maxRetries + "): " + e.getMessage());
                    if (attempt < maxRetries - 1) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            // 如果数据验证失败，直接返回
            if (danmakuCount <= 0) {
                DanmakuSpider.log("❌ 无法获取有效的弹幕数据（或弹幕为空），取消推送");
                if (isAuto && scheduleAutoRetry(danmakuItem, activity, expectedVideoSignature)) {
                    return;
                }
                if (activity != null && !activity.isFinishing()) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Utils.safeShowToast(activity, "弹幕数据验证失败，请稍后重试");
                        }
                    });
                }
                return;
            }

            clearAutoRetryState(danmakuItem.getDanmakuUrl());

            // 步骤2: 数据验证成功，开始推送
            String localIp = NetworkUtils.getLocalIpAddress();
            String pushUrl = "http://" + localIp + ":" + Utils.getPort() + "/action?do=refresh&type=danmaku&path=" +
                    URLEncoder.encode(danmakuItem.getDanmakuUrl(), "UTF-8");
            DanmakuSpider.log("推送地址: " + pushUrl);

            String pushResp = "";
            for (int i = 0; i < 3; i++) {
                if (isAuto && !canContinueAutoPush(activity, expectedVideoSignature)) return;
                pushResp = NetworkUtils.robustHttpGet(pushUrl);
                DanmakuSpider.log("推送尝试 " + (i + 1) + "/3: " + (!TextUtils.isEmpty(pushResp) ? "成功" : "失败"));
                if (!TextUtils.isEmpty(pushResp) && pushResp.toLowerCase().contains("ok")) {
                    DanmakuSpider.log("✅ 推送成功");
                    break;
                }
                if (i < 2) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            final int finalDanmakuCount = danmakuCount;
            final String finalPushResp = pushResp;

            // 步骤3: 在主线程显示结果
            if (activity != null && !activity.isFinishing()) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!TextUtils.isEmpty(finalPushResp) && finalPushResp.toLowerCase().contains("ok")) {
                            String message = String.format("弹幕已推送: %s - %s (共%d条)",
                                    danmakuItem.getTitle(),
                                    danmakuItem.getEpTitle(),
                                    finalDanmakuCount);
                            Utils.safeShowToast(activity, message);
                            DanmakuSpider.log(message);
                        } else {
                            Utils.safeShowToast(activity, "推送失败: 无响应或响应异常");
                            DanmakuSpider.log("❌ 推送失败，响应: " + finalPushResp);
                        }
                    }
                });
            }
        } catch (Exception e) {
            DanmakuSpider.log("推送异常: " + e.getMessage());
            e.printStackTrace();
            if (activity != null && !activity.isFinishing()) {
                Utils.safeShowToast(activity, "推送异常: " + e.getMessage());
            }
        }
    }

    private static boolean scheduleAutoRetry(DanmakuItem danmakuItem, Activity activity,
                                             String expectedVideoSignature) {
        if (!canContinueAutoPush(activity, expectedVideoSignature)) {
            DanmakuSpider.log("⚠️ 已换集，取消旧集弹幕重试: " + danmakuItem.getEpId());
            return false;
        }

        String danmakuUrl = danmakuItem.getDanmakuUrl();
        ScheduledFuture<?> existingTask = autoRetryTasks.get(danmakuUrl);
        if (existingTask != null && !existingTask.isDone()) return true;

        int retryIndex = autoRetryAttempts.getOrDefault(danmakuUrl, 0);
        if (retryIndex >= AUTO_EMPTY_RETRY_DELAYS_MS.length) {
            clearAutoRetryState(danmakuUrl);
            DanmakuSpider.log("❌ 自动弹幕重试已用尽: " + danmakuItem.getEpId());
            return false;
        }

        long delayMs = AUTO_EMPTY_RETRY_DELAYS_MS[retryIndex];
        autoRetryAttempts.put(danmakuUrl, retryIndex + 1);
        DanmakuSpider.log("⏳ 弹幕暂未生成，将在 " + (delayMs / 1000)
                + " 秒后自动重试 (" + (retryIndex + 1) + "/"
                + AUTO_EMPTY_RETRY_DELAYS_MS.length + "): " + danmakuItem.getEpId());

        ScheduledFuture<?> retryTask = autoRetryExecutor.schedule(() -> {
            autoRetryTasks.remove(danmakuUrl);
            if (!canContinueAutoPush(activity, expectedVideoSignature)) {
                clearAutoRetryState(danmakuUrl);
                DanmakuSpider.log("⚠️ 重试前已换集，跳过旧集弹幕: " + danmakuItem.getEpId());
                return;
            }
            DanmakuSpider.log("🔄 开始自动重试弹幕: " + danmakuItem.getEpId());
            pushDanmakuInThread(danmakuItem, activity, true, expectedVideoSignature);
        }, delayMs, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> previousTask = autoRetryTasks.put(danmakuUrl, retryTask);
        if (previousTask != null) previousTask.cancel(false);
        return true;
    }

    private static boolean canContinueAutoPush(Activity activity, String signature) {
        boolean allowed = activity != null && !activity.isFinishing()
                && isSameAutoEpisode(signature) && HostDanmakuGate.allowsAutoPush(activity);
        if (!allowed) cancelAllAutoRetries();
        return allowed;
    }

    private static void cancelAllAutoRetries() {
        for (ScheduledFuture<?> task : autoRetryTasks.values()) task.cancel(false);
        autoRetryTasks.clear();autoRetryAttempts.clear();
    }

    private static boolean isSameAutoEpisode(String expectedVideoSignature) {
        if (TextUtils.isEmpty(expectedVideoSignature)) return true;
        return expectedVideoSignature.equals(DanmakuManager.currentVideoSignature);
    }

    private static void clearAutoRetryState(String danmakuUrl) {
        if (TextUtils.isEmpty(danmakuUrl)) return;
        autoRetryAttempts.remove(danmakuUrl);
        ScheduledFuture<?> task = autoRetryTasks.remove(danmakuUrl);
        if (task != null && !task.isDone()) task.cancel(false);
    }

    // 辅助方法：从XML中解析弹幕总数
    private static int countDanmakuItems(String xmlData) {
        try {
            if (TextUtils.isEmpty(xmlData) || !xmlData.trim().startsWith("<")) return 0;

            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.xml.sax.InputSource is = new org.xml.sax.InputSource(new java.io.StringReader(xmlData));
            org.w3c.dom.Document doc = builder.parse(is);

            return doc.getElementsByTagName("d").getLength();
        } catch (Exception e) {
            DanmakuSpider.log("解析弹幕数据异常: " + e.getMessage());
            return -1; // 返回-1表示解析异常
        }
    }
}
