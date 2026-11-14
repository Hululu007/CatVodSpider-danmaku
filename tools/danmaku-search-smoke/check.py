"""Run production search code on the JVM with deterministic provider responses.

Usage: python tools/danmaku-search-smoke/check.py [--live-fixtures PATH]
Android UI and network effects are stubbed; nothing is sent to a player.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "danmaku-search-smoke"
JAVA_SRC = ROOT / "app/src/main/java/com/github/catvod/spider"
ANDROID = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-36/android.jar"

STUBS = r'''
package com.github.catvod.spider;
import android.app.Activity;
import android.content.Context;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import com.github.catvod.spider.entity.DanmakuItem;

class DanmakuConfig {
    Set<String> getApiUrls() { return Collections.singleton("http://fixture"); }
}
class DanmakuConfigManager {
    static DanmakuConfig getConfig(Activity activity) { return new DanmakuConfig(); }
}
class DanmakuSpider {
    static String apiUrl;
    static void log(String value) { if (Boolean.getBoolean("search.debug")) System.out.println(value); }
    static void recordDanmakuUrl(DanmakuItem item, boolean auto) {
        throw new AssertionError("Unexpected push");
    }
}
class Utils {
    static void safeShowToast(Activity activity, String value) { }
    static int getPort() { throw new AssertionError("Unexpected push"); }
}
class Init { static Context context() { return null; } }
class HostDanmakuGate { static boolean allowsAutoPush(Activity activity) { return false; } }
class NetworkUtils {
    static final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    static String episodeResponse, allResponse, expectedKeyword;
    static void reset(String keyword, String episode, String all) {
        requests.clear(); expectedKeyword = keyword;
        episodeResponse = episode; allResponse = all;
    }
    static String robustHttpGet(String url) {
        requests.add(url);
        try {
            Map<String,String> query = new HashMap<>();
            for (String pair : URI.create(url).getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], URLDecoder.decode(parts[1], "UTF-8"));
            }
            if (!expectedKeyword.equals(query.get("anime"))) return "{\"animes\":[]}";
            return query.containsKey("episode") ? episodeResponse : allResponse;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    static String getLocalIpAddress() { throw new AssertionError("Unexpected push"); }
}
'''

TEST = r'''
package com.github.catvod.spider;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import com.github.catvod.spider.entity.DanmakuItem;

public class SearchSmoke {
    static int passed;
    static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        passed++;
    }
    static EpisodeInfo info(String title, String episode) {
        EpisodeInfo info = new EpisodeInfo();
        info.setEpisodeNames(new ArrayList<>(Arrays.asList(title)));
        info.setEpisodeNum(episode);
        return info;
    }
    static JSONObject ep(int id, String title) {
        return new JSONObject().put("episodeId", id).put("episodeTitle", title);
    }
    static String response(String title, JSONObject... episodes) {
        JSONArray eps = new JSONArray();
        for (JSONObject ep : episodes) eps.put(ep);
        return new JSONObject().put("animes", new JSONArray().put(new JSONObject()
                .put("animeTitle", title).put("episodes", eps))).toString();
    }
    static void noMatch(EpisodeInfo info, String response, String label) {
        NetworkUtils.reset(info.getEpisodeNames().get(0), response, response);
        check(!LeoDanmakuService.autoSearch(info.getEpisodeNames().get(0), info, null).found, label);
    }
    public static void main(String[] args) {
        try { run(args); System.out.println("ALL PASS: " + passed); System.exit(0); }
        catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        String title = "尚公主(2026)【电视剧】from tencent";
        String wrong = response(title, ep(193496, "【qq】 尚公主 第1集"));
        String all = response(title, ep(193495, "【qq】 先导片"),
                ep(193496, "【qq】 尚公主 第1集"), ep(193497, "【qq】 尚公主 第2集"),
                ep(193532, "【qq】 采访2"), ep(193535, "【qq】 尚公主 彩蛋2"));
        EpisodeInfo dirty = info("\u200B尚公主\u200B", "02");
        NetworkUtils.reset("尚公主", wrong, all);
        LeoDanmakuService.SearchResult result = LeoDanmakuService.autoSearch(
                dirty.getEpisodeNames().get(0), dirty, null);
        check(result.found && result.item.epId == 193497, "wrong episode -> full list -> episode 2");
        check(result.similarity == 1.0, "clean title meets automatic push threshold");
        check(NetworkUtils.requests.size() == 2, "only one full-list fallback");
        check(NetworkUtils.requests.get(0).contains("episode=02"), "first query targets episode");
        check(!NetworkUtils.requests.get(1).contains("episode="), "second query fetches full list");
        check(!NetworkUtils.requests.get(0).contains("%E2%80%8B"), "zero-width removed before HTTP");
        check(dirty.getEpisodeNames().get(0).equals("\u200B尚公主\u200B"), "caller metadata unchanged");

        NetworkUtils.reset("尚公主", wrong, all);
        check(LeoDanmakuService.manualSearch(dirty, null).size() == 5, "manual search retains episodes and extras");
        noMatch(info("尚公主", "02"), wrong, "wrong episode never reintroduced");
        noMatch(info("尚公主", "02"), response(title, ep(9, "先导片")), "unknown episode rejected");
        noMatch(info("尚公主", "02"), response(title, ep(9, "采访2")), "numbered interview rejected");
        noMatch(info("尚公主", "02"), response(title, ep(9, "第2集预告")), "trailer rejected");
        noMatch(info("尚公主", "99"), all, "missing episode does not fall back to first");
        noMatch(info("尚公主", "02"), "{\"animes\":[]}", "empty provider response");
        EpisodeInfo year = info("尚公主", "02"); year.setEpisodeYear("2025");
        noMatch(year, all, "wrong year stays rejected");

        EpisodeInfo alias = info("旧译名", "02");
        NetworkUtils.reset("尚公主", wrong, all);
        result = LeoDanmakuService.autoSearch("尚公主", alias, null);
        check(result.found && result.item.epId == 193497, "selected alias is used in provider query");
        check(alias.getEpisodeNames().get(0).equals("旧译名"), "alias query does not mutate source");

        String normalized = response("伟大的长征(2026)【电视剧】from tencent", ep(7, "第2集"));
        NetworkUtils.reset("伟大 的长征", normalized, normalized);
        result = LeoDanmakuService.autoSearch("伟大 的长征", info("伟大 的长征", "2"), null);
        check(result.found && result.similarity == 1.0, "internal spaces do not lower confidence");

        String symbolTitle = "铁血战士：杀戮之地";
        String movie = response("铁血战士杀戮之地(2025)【电影】from tencent", ep(5, "正片"));
        NetworkUtils.reset("铁血战士杀戮之地", movie, movie);
        EpisodeInfo movieInfo = info(symbolTitle, "");
        result = LeoDanmakuService.autoSearch(symbolTitle, movieInfo, null);
        check(result.found && result.similarity == 1.0, "movie without episode and punctuation fallback");
        check(movieInfo.getEpisodeNames().get(0).equals(symbolTitle), "fallback leaves original keyword intact");

        String[][] cases = {{"E04.249 4K", "04"}, {"S02E11.1080p.mkv", "11"},
                {"第十二集[4.86G]", "12"}, {"[1.50G] 164", "164"}, {"4K 164", "164"},
                {"尚公主 2026", ""}, {"特别篇", ""},
                {"[26.88G] 2026.2160p.HQ.WEB-DL.H265.10bit.DTS.5.1.2Audio", ""},
                {"DTS.5.1.2Audio", ""}, {"DTS-HD.MA.7.1", ""},
                {"DDP5.1", ""}, {"TrueHD7.1.Atmos", ""}, {"AAC2.0", ""},
                {"5.1", ""}, {"7.1.4", ""}, {"10bit", ""}, {"2Audio", ""},
                {"24bit.48kHz.60FPS.6CH", ""},
                {"E04.249 4K DTS.5.1.2Audio", "04"},
                {"S02E11.1080p.DDP5.1.10bit.mkv", "11"},
                {"第十二集[4.86G].AAC2.0", "12"},
                {"[1.50G] 164 DTS.5.1", "164"}, {"4K 164 10bit", "164"},
                {"05.1080p.DTS.5.1", "05"}, {"第5集 5.1声道", "5"}};
        for (String[] c : cases) check(MediaIdentityResolver.extractEpisodeToken(c[0]).equals(c[1]), "parse " + c[0]);
        check(MediaIdentityResolver.parseEpisodeNumber("第02集") == 2, "leading zero numeric equivalence");
        check(MediaIdentityResolver.parseDanmakuEpisodeNumber("【qq】 第2集 主角接受采访") == 2,
                "interview mentioned in synopsis remains a normal episode");
        check(MediaIdentityResolver.parseDanmakuEpisodeNumber("【qq】 EP02") == 2, "western provider episode");
        check(MediaIdentityResolver.cleanSearchText("\uFEFF\u200B尚\u200B公主\u2060\u00A0").equals("尚公主"), "invisible cleanup");
        check(MediaIdentityResolver.cleanSearchText("\u00A0Great\u3000Journey ").equals("Great Journey"), "Unicode spaces preserved as separators");
        MediaIdentityResolver.Identity identity = MediaIdentityResolver.resolve(
                "\u200B尚公主\u200B", "第02集[4.89G]", "https://example.test/proxy");
        check(identity.displayTitle.equals("尚公主") && identity.episode == 2, "production identity from TV metadata");
        check(identity.searchNames.stream().noneMatch(s -> s.contains("\u200B")), "clean aliases");
        check(MediaIdentityResolver.resolve("剧名", "S02E11.mkv", "").season == 2, "season preserved");

        MediaIdentityResolver.Identity movieIdentity = MediaIdentityResolver.resolve(
                "空枪", "[26.88G] 2026.2160p.HQ.WEB-DL.H265.10bit.DTS.5.1.2Audio", "");
        check(movieIdentity.episode == 0 && movieIdentity.episodeToken.isEmpty(),
                "movie audio metadata does not create episode 2");
        check(movieIdentity.year.equals("2026") && movieIdentity.canonicalTitle.equals("空枪"),
                "movie title and year survive technical metadata cleanup");
        EpisodeInfo kongqiang = info(movieIdentity.getPrimarySearchName(), movieIdentity.episodeToken);
        kongqiang.setEpisodeYear(movieIdentity.year);
        String kongqiangResponse = response("空枪(2026)【电影】from iqiyi", ep(193886, "【qiyi】 第1集 正片"));
        NetworkUtils.reset("空枪", kongqiangResponse, kongqiangResponse);
        result = LeoDanmakuService.autoSearch("空枪", kongqiang, null);
        check(result.found && result.item.epId == 193886 && result.similarity == 1.0,
                "movie metadata reaches a valid feature-film match");
        check(NetworkUtils.requests.size() == 1 && !NetworkUtils.requests.get(0).contains("episode="),
                "movie lookup does not request a spurious episode");

        for (String url : new String[]{"https://example.test/video/abc_5_def0123456789abcdef01234567",
                "https://example.test/video/abc_5_def0123456789abcdef01234567.mp4?token=ignored",
                "https://example.test/video/12345", "https://example.test/segment/5"}) {
            check(MediaIdentityResolver.resolve("空枪", movieIdentity.rawArtist, url).episode == 0,
                    "opaque resource URL does not create a movie episode");
        }
        check(MediaIdentityResolver.resolve("剧名", "正片", "https://example.test/S02E11.mkv").episode == 11,
                "real URL filename remains an episode fallback");
        check(MediaIdentityResolver.resolve("剧名", "正片", "https://example.test/E04").episode == 4,
                "explicit extensionless URL episode remains supported");
        check(MediaIdentityResolver.resolve("剧名", "第2集", "https://example.test/E04.mkv").episode == 2,
                "metadata episode remains preferred over URL filename");

        String mediaFixture = System.getProperty("media.fixture");
        if (mediaFixture != null) {
            Path mediaDir = Paths.get(mediaFixture);
            MediaIdentityResolver.Identity current = MediaIdentityResolver.resolve(
                    new String(Files.readAllBytes(mediaDir.resolve("current-title.txt")), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(mediaDir.resolve("current-artist.txt")), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(mediaDir.resolve("current-url.txt")), StandardCharsets.UTF_8));
            check(current.episode == 0 && current.episodeToken.isEmpty(),
                    "full live movie metadata and playback URL contain no episode");
            kongqiang = info(current.getPrimarySearchName(), current.episodeToken);
            kongqiang.setEpisodeYear(current.year);
        }

        String movieFixture = System.getProperty("movie.fixture");
        if (movieFixture != null) {
            String captured = new String(Files.readAllBytes(Paths.get(movieFixture)), StandardCharsets.UTF_8);
            NetworkUtils.reset("空枪", captured, captured);
            result = LeoDanmakuService.autoSearch("空枪", kongqiang, null);
            check(result.found && result.item.epId == 193886 && result.similarity == 1.0,
                    "captured movie results select the verified feature film");
            check(NetworkUtils.requests.size() == 1 && !NetworkUtils.requests.get(0).contains("episode="),
                    "captured movie lookup omits episode constraint");
        }

        if (args.length > 0) {
            Path dir = Paths.get(args[0]);
            String liveWrong = new String(Files.readAllBytes(dir.resolve("episode2.json")), StandardCharsets.UTF_8);
            String liveAll = new String(Files.readAllBytes(dir.resolve("all.json")), StandardCharsets.UTF_8);
            NetworkUtils.reset("尚公主", liveWrong, liveAll);
            result = LeoDanmakuService.autoSearch(dirty.getEpisodeNames().get(0), dirty, null);
            System.out.println("Live match: found=" + result.found + ", score=" + result.similarity
                    + ", id=" + (result.item == null ? "none" : result.item.epId));
            check(result.found && result.item.epId == 193497 && result.similarity == 1.0,
                    "captured live API responses select episode 2");
            check(NetworkUtils.requests.size() == 2, "live response fallback bounded");
            NetworkUtils.reset("尚公主", liveWrong, liveAll);
            check(LeoDanmakuService.manualSearch(dirty, null).size() == 45, "45 live search results survive title filter");
        }
    }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live-fixtures", type=Path)
    parser.add_argument("--movie-fixture", type=Path)
    parser.add_argument("--media-fixture", type=Path)
    args = parser.parse_args()
    src = OUT / "src"
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    jar = OUT / "json-20240303.jar"
    if not jar.exists():
        urllib.request.urlretrieve(
            "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar", jar)
    files = {
        "android/text/TextUtils.java": 'package android.text; public class TextUtils { public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; } }',
        "com/github/catvod/spider/Stubs.java": STUBS,
        "com/github/catvod/spider/SearchSmoke.java": TEST,
    }
    sources = []
    for name, content in files.items():
        path = src / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        sources.append(str(path))
    sources += [str(JAVA_SRC / name) for name in (
        "MediaIdentityResolver.java", "LeoDanmakuService.java", "EpisodeInfo.java",
        "DanmakuManager.java", "entity/DanmakuItem.java")]
    cp = os.pathsep.join(map(str, (jar, ANDROID)))
    subprocess.run([shutil.which("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                    "-cp", cp, "-d", str(classes), *sources], check=True)
    command = [shutil.which("java"), "-cp", str(classes) + os.pathsep + cp,
               "com.github.catvod.spider.SearchSmoke"]
    if args.movie_fixture:
        command.insert(1, "-Dmovie.fixture=" + str(args.movie_fixture.resolve()))
    if args.media_fixture:
        command.insert(1, "-Dmedia.fixture=" + str(args.media_fixture.resolve()))
    if args.live_fixtures:
        command.append(str(args.live_fixtures.resolve()))
    subprocess.run(command, check=True, timeout=60)


if __name__ == "__main__":
    main()
