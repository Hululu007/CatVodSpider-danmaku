package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Normalizes the two public databases without mixing their response formats. */
final class SkipSegmentSources {
    static final String BUILD = "introdb-primary-20261114-r1";
    static final long DURATION_TOLERANCE_MS = 5000;

    static final class Segment {
        String type;
        long startMs;
        long endMs;
        String source;
        boolean durationMatched;

        Segment(String type, long startMs, long endMs, String source) {
            this.type = type;
            this.startMs = startMs;
            this.endMs = endMs;
            this.source = source;
        }
    }

    static List<Segment> introDb(JSONObject json, long durationMs) {
        List<Segment> result = new ArrayList<>();
        add(result, json.optJSONObject("intro"), "intro", "IntroDB", durationMs, false);
        add(result, json.optJSONObject("recap"), "recap", "IntroDB", durationMs, false);
        add(result, json.optJSONObject("outro"), "credits", "IntroDB", durationMs, false);
        add(result, json.optJSONObject("post_credits"), "post_credits", "IntroDB", durationMs, false);
        return result;
    }

    static List<Segment> theIntroDb(JSONObject json, long durationMs, int season, int episode, boolean movie) {
        List<Segment> result = new ArrayList<>();
        if (!((movie ? "movie" : "tv").equals(json.optString("type")))) return result;
        if (!movie && (json.optInt("season", -1) != season
                || json.optInt("episode", -1) != episode)) return result;
        long groupDuration = json.optLong("group_duration_ms", 0);
        // The API falls back to its most popular cut when duration matching fails.
        if (durationMs > 0 && groupDuration > 0 && Math.abs(durationMs - groupDuration) > 300000) return result;
        for (String type : new String[] {"intro", "recap", "credits", "preview"}) {
            JSONArray segments = json.optJSONArray(type);
            if (segments == null) continue;
            for (int i = 0; i < segments.length(); i++) {
                add(result, segments.optJSONObject(i), type, "TheIntroDB", durationMs, true);
            }
        }
        for (Segment segment : result) segment.durationMatched = durationMs > 0 && groupDuration > 0
                && Math.abs(durationMs - groupDuration) <= DURATION_TOLERANCE_MS;
        Collections.sort(result, (left, right) -> Long.compare(left.startMs, right.startMs));
        return result;
    }

    private static void add(List<Segment> result, JSONObject json, String type, String source,
                            long durationMs, boolean optionalStart) {
        if (json == null) return;
        long start = timestamp(json, "start");
        long end = timestamp(json, "end");
        // TheIntroDB permits null intro/recap starts to mean the beginning of the video.
        if (start < 0 && optionalStart && json.has("start_ms") && json.isNull("start_ms")
                && ("intro".equals(type) || "recap".equals(type)) && end > 0) start = 0;
        if (start < 0 || (end >= 0 && end <= start)) return;
        if (("intro".equals(type) || "recap".equals(type)) && end <= start) return;
        if (durationMs > 0 && (start >= durationMs || end > durationMs + DURATION_TOLERANCE_MS)) return;
        if (durationMs > 0 && end > durationMs) end = durationMs;
        result.add(new Segment(type, start, end, source));
    }

    private static long timestamp(JSONObject json, String part) {
        String millis = part + "_ms", seconds = part + "_sec";
        double value = !json.isNull(millis) ? json.optDouble(millis, -1)
                : json.optDouble(seconds, -1) * 1000;
        return Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > Long.MAX_VALUE
                ? -1 : Math.round(value);
    }

    static void merge(AutoSkipService.Markers result, List<Segment> segments, long durationMs) {
        List<Segment> candidates = new ArrayList<>(result.segments);
        candidates.addAll(segments);
        result.segments.clear();
        result.introStartMs = -1; result.introEndMs = -1;
        result.recapStartMs = -1; result.recapEndMs = -1;
        result.outroStartMs = -1; result.source = "";
        // Select a provider per type before deriving either chapters or automatic skips.
        for (Segment segment : candidates) {
            if (!"IntroDB".equals(segment.source) && hasPrimaryType(candidates, segment.type)) continue;
            boolean duplicate = false;
            for (Segment existing : result.segments) {
                if (existing.type.equals(segment.type) && Math.abs(existing.startMs - segment.startMs) <= 3000
                        && (existing.endMs < 0 || segment.endMs < 0 || existing.endMs == segment.endMs
                        || (existing.endMs >= 0 && segment.endMs >= 0
                        && Math.abs(existing.endMs - segment.endMs) <= 3000))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.segments.add(segment);
                addSource(result, segment.source);
            }
        }
        Collections.sort(result.segments, (left, right) -> Long.compare(left.startMs, right.startMs));
        for (Segment segment : result.segments) {
            if ("intro".equals(segment.type) && !result.hasIntro()) {
                result.introStartMs = segment.startMs;
                result.introEndMs = segment.endMs;
            } else if ("recap".equals(segment.type) && !result.hasRecap()) {
                result.recapStartMs = segment.startMs;
                result.recapEndMs = segment.endMs;
            } else if ("credits".equals(segment.type) && !result.hasOutro() && segment.startMs > 0
                    && ("IntroDB".equals(segment.source) || segment.endMs < 0
                    || (durationMs > 0 && segment.endMs >= durationMs - 3000))
                    && !hasLaterScene(result.segments, segment.startMs)) {
                // A bounded credits block can be followed by a scene; only terminal credits advance.
                result.outroStartMs = segment.startMs;
            }
        }
        if (result.hasAny()) result.error = "";
    }

    private static boolean hasPrimaryType(List<Segment> segments, String type) {
        for (Segment segment : segments)
            if ("IntroDB".equals(segment.source) && type.equals(segment.type)) return true;
        return false;
    }

    static boolean hasCreditsOrFollowingScene(AutoSkipService.Markers result) {
        for (Segment segment : result.segments) {
            if ("credits".equals(segment.type) || "preview".equals(segment.type)
                    || "post_credits".equals(segment.type)) return true;
        }
        return false;
    }

    private static boolean hasLaterScene(List<Segment> segments, long positionMs) {
        for (Segment segment : segments) {
            if (("post_credits".equals(segment.type) || "preview".equals(segment.type))
                    && segment.startMs > positionMs) return true;
        }
        return false;
    }

    static void addSource(AutoSkipService.Markers result, String source) {
        for (String existing : result.source.split(java.util.regex.Pattern.quote(" + ")))
            if (source.equals(existing)) return;
        result.source = result.source.isEmpty() ? source : result.source + " + " + source;
    }

    static List<ChapterManager.Chapter> chapters(List<Segment> segments) {
        List<ChapterManager.Chapter> result = new ArrayList<>();
        for (Segment segment : segments) {
            String start, end;
            switch (segment.type) {
                case "intro": start = "片头开始"; end = "片头结束"; break;
                case "recap": start = "前情回顾"; end = "回顾结束"; break;
                case "credits": start = "片尾"; end = "片尾结束"; break;
                case "preview": start = "下集预告"; end = "预告结束"; break;
                case "post_credits": start = "片尾彩蛋"; end = "彩蛋结束"; break;
                default: continue;
            }
            result.add(new ChapterManager.Chapter("provider-" + segment.type + "-" + segment.startMs,
                    start, segment.startMs, true));
            if (segment.endMs > segment.startMs) {
                result.add(new ChapterManager.Chapter("provider-" + segment.type + "-end-" + segment.endMs,
                        end, segment.endMs, true));
            }
        }
        Collections.sort(result, (left, right) -> Long.compare(left.startMs, right.startMs));
        return result;
    }

}
