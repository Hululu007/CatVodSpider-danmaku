package com.github.catvod.spider;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import com.github.catvod.spider.entity.Media;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FongMi/OK影视当前版本的反射桥接，避免依赖播放器混淆后的类名。
 */
final class FongMiNativeHelper {

    private static volatile MediaController notificationMediaController;

    private FongMiNativeHelper() {
    }

    static class HistoryState {
        boolean available;
        long opening;
        long ending;
    }

    private static final String SUSPENDED_PREFS = "danmaku_native_skip_paused_v1";
    private static Boolean lastSkipMode;
    private static String lastSkipScope = "";
    private static final String HISTORY_TYPE = "com.fongmi.android.tv.bean.History";
    private static final int HISTORY_SCAN_LIMIT = 240;
    private static final java.util.Map<Class<?>, List<Field>> historyFields =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static List<Object> currentHistories(Activity activity) {
        if (activity == null) return Collections.emptyList();
        List<Field> fields = historyFields.get(activity.getClass());
        if (fields == null) {
            fields = findDirectFieldsByTypeName(activity.getClass(), HISTORY_TYPE);
            historyFields.put(activity.getClass(), fields);
        }
        if (!fields.isEmpty()) {
            for (Field field : fields) {
                try {
                    Object history = field.get(activity);
                    if (history != null) return Collections.singletonList(history);
                } catch (IllegalAccessException ignored) { }
            }
            // Known hosts declare History before detail loading has populated it.
            // Null means "not ready", not "search the entire Activity object graph".
            // Read the field again next tick; never cache a History instance across episodes.
            return Collections.emptyList();
        }
        return findObjectsByTypeName(activity, HISTORY_TYPE, 4);
    }

    static HistoryState readHistory(Activity activity) {
        HistoryState state = new HistoryState();
        if (activity == null) return state;
        runOnMainThread(() -> {
            for (Object history : currentHistories(activity)) {
                try {
                    state.opening = getLong(history, "getOpening");
                    state.ending = getLong(history, "getEnding");
                    state.available = true;
                    break; // Never combine unrelated records by taking their maximum.
                } catch (Throwable ignored) { }
            }
            return state.available;
        });
        return state;
    }

    private static String historyKey(Object history) throws Exception {
        Object key = history.getClass().getMethod("getKey").invoke(history);
        if (key == null || key.toString().isEmpty()) throw new IllegalStateException("Missing History key");
        Object cid = history.getClass().getMethod("getCid").invoke(history);
        return cid + "|" + key;
    }

    private static boolean writeRecord(Object history, long opening, long ending) {
        try {
            long normalizedOpening = Math.max(0, opening), normalizedEnding = Math.max(0, ending);
            if (getLong(history, "getOpening") != normalizedOpening
                    || getLong(history, "getEnding") != normalizedEnding) {
                history.getClass().getMethod("setOpening", long.class).invoke(history, normalizedOpening);
                history.getClass().getMethod("setEnding", long.class).invoke(history, normalizedEnding);
                history.getClass().getMethod("save").invoke(history);
            }
            return getLong(history, "getOpening") == normalizedOpening
                    && getLong(history, "getEnding") == normalizedEnding;
        } catch (Throwable error) {
            DanmakuSpider.log("原生头尾写入失败: " + error.getClass().getSimpleName());
            return false;
        }
    }

    static boolean writeHistory(Activity activity, long opening, long ending) {
        return activity != null && runOnMainThread(() -> {
            List<Object> histories = currentHistories(activity);
            if (histories.isEmpty()) return false;
            return writeRecord(histories.get(0), opening, ending);
        });
    }

    /** Persist the pause separately from ChapterData; a host History can span episodes. */
    static boolean syncNativeSkipState(Activity activity, boolean enabled) {
        if (activity == null) return false;
        return runOnMainThread(() -> {
            DanmakuConfig latest = DanmakuConfigManager.getConfig(activity);
            boolean active = latest != null && latest.isAutoSkipEnabled();
            List<Object> current = currentHistories(activity);
            if (current.isEmpty()) return false;
            android.content.SharedPreferences prefs =
                    activity.getSharedPreferences(SUSPENDED_PREFS, Context.MODE_PRIVATE);
            java.util.LinkedHashMap<String, Object> records = new java.util.LinkedHashMap<>();
            try {
                Object first = current.get(0);
                String scope = String.valueOf(first.getClass().getMethod("getCid").invoke(first));
                // On mode changes (and cold starts), suspend/restore saved records too:
                // the host applies opening before the first per-episode polling tick.
                boolean sweep = lastSkipMode == null || lastSkipMode != active || !scope.equals(lastSkipScope);
                if (sweep) {
                    Object all = first.getClass().getMethod("get").invoke(null);
                    if (all instanceof List) for (Object record : (List<?>) all) {
                        if (record != null) records.put(historyKey(record), record);
                    }
                }
                // Prefer the live instance over a database copy of the same History.
                for (Object record : current) records.put(historyKey(record), record);
                boolean success = true;
                for (java.util.Map.Entry<String, Object> item : records.entrySet())
                    success &= syncPausedRecord(prefs, item.getKey(), item.getValue(), active);
                if (success) { lastSkipMode = active; lastSkipScope = scope; }
                return success;
            } catch (Throwable error) {
                // Unsupported optional enumeration must not prevent current-record pausing.
                boolean success = true;
                for (Object record : current) {
                    try { success &= syncPausedRecord(prefs, historyKey(record), record, active); }
                    catch (Throwable ignored) { success = false; }
                }
                return success;
            }
        });
    }

    private static boolean syncPausedRecord(android.content.SharedPreferences prefs,
                                            String key, Object history, boolean enabled) throws Exception {
        long opening = getLong(history, "getOpening"), ending = getLong(history, "getEnding");
        String saved = prefs.getString(key, null);
        long savedOpening = 0, savedEnding = 0;
        if (saved != null) {
            String[] parts = saved.split(",", -1);
            if (parts.length != 2) return false;
            savedOpening = Long.parseLong(parts[0]); savedEnding = Long.parseLong(parts[1]);
        }
        if (!enabled) {
            if (opening <= 0 && ending <= 0) return true;
            // Preserve a new manual value entered while paused, without enabling skipping.
            if (opening > 0) savedOpening = opening;
            if (ending > 0) savedEnding = ending;
            if (!prefs.edit().putString(key, savedOpening + "," + savedEnding).commit()) return false;
            return writeRecord(history, 0, 0);
        }
        if (saved == null) return true;
        // An external nonzero value wins; only restore sides still at our paused zero.
        if (!writeRecord(history, opening > 0 ? opening : savedOpening,
                ending > 0 ? ending : savedEnding)) return false;
        return prefs.edit().remove(key).commit();
    }

    static boolean clearManualSkipMarkers(Activity activity) {
        if (activity == null) return false;
        return runOnMainThread(() -> {
            List<Object> current = currentHistories(activity);
            if (current.isEmpty()) return false;
            Object history = current.get(0);
            try {
                if (!writeRecord(history, 0, 0)) return false;
                // Replace the paused backup with zero before removing it: failed removal
                // must never revive a cleared marker when Auto is enabled again.
                android.content.SharedPreferences prefs =
                        activity.getSharedPreferences(SUSPENDED_PREFS, Context.MODE_PRIVATE);
                String key = historyKey(history);
                if (!prefs.edit().putString(key, "0,0").commit()) return false;
                prefs.edit().remove(key).apply();
                refreshClearedMarkerLabels(activity);
                return true;
            } catch (Throwable error) { return false; }
        });
    }

    private static void refreshClearedMarkerLabels(Activity activity) {
        // Only the explicit Clear action updates labels; Auto mode never rewrites button text.
        ToolbarHostMap map = ToolbarHostMap.resolve(activity);
        android.view.View root = activity.getWindow().getDecorView();
        for (String name : new String[]{"opening", "ending"}) {
            android.view.View view = map == null ? null : map.actions.get(name);
            if (view == null) {
                int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
                if (id != 0) view = root.findViewById(id);
            }
            if (view instanceof android.widget.TextView)
                ((android.widget.TextView)view).setText(name.equals("opening") ? "片头" : "片尾");
        }
    }

    private static List<Field> findDirectFieldsByTypeName(Class<?> ownerType, String typeName) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = ownerType; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().getName().equals(typeName)) continue;
                try {
                    field.setAccessible(true);
                    result.add(field);
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    static boolean seekTo(long positionMs) {
        final long target = Math.max(0, positionMs);
        return runOnMainThread(() -> {
            if (seekDirect(target)) return true;
            MediaController.TransportControls controls = getTransportControls();
            if (controls == null) return false;
            try {
                controls.seekTo(target);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    private static boolean seekDirect(long positionMs) {
        try {
            Class<?> service = Class.forName("com.fongmi.android.tv.service.PlaybackService");
            Object player = service.getField("i").get(null);
            if (player == null) return false;
            Method fallback = null;
            for (Method method : player.getClass().getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0] == long.class
                        && method.getReturnType() == void.class) {
                    String name = method.getName();
                    if (name.equals("i0") || name.equals("seekTo")
                            || name.toLowerCase().contains("seek")) {
                        method.invoke(player, Math.max(0, positionMs));
                        return true;
                    }
                    if (fallback == null) fallback = method;
                }
            }
            if (fallback != null) {
                fallback.invoke(player, Math.max(0, positionMs));
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean seekToAutomatically(ChapterManager.SkipSession session, long positionMs) {
        return runOnMainThread(() -> ChapterManager.isAutoSkipActive(session) && seekTo(positionMs));
    }

    static boolean nextEpisodeAutomatically(ChapterManager.SkipSession session) {
        return runOnMainThread(() -> ChapterManager.isAutoSkipActive(session) && nextEpisode());
    }

    static boolean nextEpisode() {
        return runOnMainThread(() -> {
            MediaController.TransportControls controls = getTransportControls();
            if (controls == null) return false;
            try {
                controls.skipToNext();
                return true;
            } catch (Throwable e) {
                return false;
            }
        });
    }

    static ChapterManager.MediaInfo getMediaInfo(Activity activity) {
        Media media = getMedia(activity);
        return media == null ? null : ChapterManager.fromMedia(media);
    }

    static Media getMedia(Activity activity) {
        MediaController controller = getMediaController(activity);
        if (controller == null) return null;
        try {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            if (metadata == null) return null;
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String url = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = state == null ? 0 : state.getPosition();
            if (title == null && artist == null) return null;
            Media media = new Media();
            media.setTitle(title);
            media.setArtist(artist);
            media.setUrl(url == null || url.isEmpty()
                    ? "mediasession://current" : url);
            media.setDuration(duration);
            media.setPosition(position);
            media.setState(state == null ? 3 : state.getState());
            media.setSpeed(state == null ? 1f : state.getPlaybackSpeed());
            return media;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private interface MainAction {
        boolean run();
    }

    private static boolean runOnMainThread(MainAction action) {
        if (action == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return action.run();
        final boolean[] result = {false};
        CountDownLatch latch = new CountDownLatch(1);
        boolean posted = new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = action.run();
            } finally {
                latch.countDown();
            }
        });
        if (!posted) return false;
        try {
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    static boolean togglePlayback(Activity activity) {
        return runOnMainThread(() -> {
            MediaController controller=getMediaController(activity);
            if(controller==null)return false;
            try {
                PlaybackState state=controller.getPlaybackState();
                if(state==null)return false;
                switch(state.getState()) {
                    case PlaybackState.STATE_PAUSED:
                        controller.getTransportControls().play();
                        return true;
                    case PlaybackState.STATE_PLAYING:
                    case PlaybackState.STATE_BUFFERING:
                    case PlaybackState.STATE_CONNECTING:
                    case PlaybackState.STATE_FAST_FORWARDING:
                    case PlaybackState.STATE_REWINDING:
                        controller.getTransportControls().pause();
                        return true;
                    default:return false;
                }
            } catch(Throwable error) {return false;}
        });
    }

    private static MediaController.TransportControls getTransportControls() {
        MediaController controller = getMediaController(ChapterManagerActivityHolder.get());
        return controller == null ? null : controller.getTransportControls();
    }

    private static MediaController getMediaController(Activity activity) {
        if (activity == null) return null;
        Object playbackService = findFieldByTypeName(activity, "PlaybackService");
        Object session = playbackService == null ? null
                : findObjectByType(playbackService, MediaSession.class, 0,
                Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (!(session instanceof MediaSession)) {
            session = findObjectByType(activity, MediaSession.class, 0,
                    Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }
        if (session instanceof MediaSession) {
            try {
                return ((MediaSession) session).getController();
            } catch (Throwable ignored) {
            }
        }
        return getNotificationMediaController(activity);
    }

    private static MediaController getNotificationMediaController(Activity activity) {
        MediaController cached = notificationMediaController;
        if (cached != null) return cached;
        try {
            NotificationManager manager = (NotificationManager) activity.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (manager == null) return null;
            String packageName = activity.getPackageName();
            for (StatusBarNotification status : manager.getActiveNotifications()) {
                if (status == null || !packageName.equals(status.getPackageName())) continue;
                Bundle extras = status.getNotification().extras;
                if (extras == null) continue;
                Object value = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (!(value instanceof MediaSession.Token)) continue;
                MediaController controller = new MediaController(
                        activity.getApplicationContext(), (MediaSession.Token) value);
                notificationMediaController = controller;
                return controller;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static long getLong(Object target, String methodName) throws Exception {
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static List<Object> findObjectsByTypeName(Object owner, String typeName, int maxDepth) {
        List<Object> result = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectObjectsByTypeName(owner, typeName, 0, maxDepth, visited, result);
        return result;
    }

    private static void collectObjectsByTypeName(Object object, String typeName, int depth,
                                                int maxDepth, Set<Object> visited,
                                                List<Object> result) {
        if (object == null || depth > maxDepth || visited.size() >= HISTORY_SCAN_LIMIT
                || !visited.add(object)) {
            return;
        }
        Class<?> type = object.getClass();
        if (type.getName().equals(typeName)) {
            result.add(object);
            return;
        }
        if (isSimpleType(type)) return;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (isSimpleType(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value == null || visited.contains(value)) continue;
                    collectObjectsByTypeName(value, typeName, depth + 1, maxDepth,
                            visited, result);
                    if (visited.size() >= HISTORY_SCAN_LIMIT) return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Object findFieldByTypeName(Object owner, String typeName) {
        if (owner == null) return null;
        for (Class<?> current = owner.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null && value.getClass().getName().contains(typeName)) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object findObjectByType(Object object, Class<?> targetType, int depth,
                                           Set<Object> visited, int count) {
        if (object == null || depth > 7 || count > 320 || visited.contains(object)) return null;
        visited.add(object);
        Class<?> type = object.getClass();
        if (targetType.isInstance(object)) return object;
        if (isSimpleType(type)) return null;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (isSimpleType(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value == null || visited.contains(value)) continue;
                    Object found = findObjectByType(value, targetType, depth + 1,
                            visited, count + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class
                || type.getName().startsWith("java.util.")
                || type.getName().startsWith("android.view.")
                || type.getName().startsWith("android.content.")
                || type.getName().startsWith("android.os.")
                || type.getName().startsWith("android.media.session.MediaController$");
    }

    private static final class ChapterManagerActivityHolder {
        static Activity get() {
            return ChapterManager.getCurrentActivity();
        }
    }
}
