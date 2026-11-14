package com.github.catvod.spider;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Device-local, bounded PNG cache. Network/decode/file work never runs on the UI thread. */
final class SeriesLogoCache {
    static final String BUILD = "series-logo-20261113-r1";
    private static final long MB = 1024L * 1024L;
    private static final long MISSING_TTL = TimeUnit.HOURS.toMillis(24);
    private static final Object DISK_LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static int epoch;

    interface Callback { void onResult(Bitmap bitmap); }
    interface UsageCallback { void onResult(long bytes); }

    static void request(Context context, long tmdbId, Callback callback) {
        final Context app = context.getApplicationContext();
        final int generation;
        synchronized (DISK_LOCK) { generation = epoch; }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try { if (tmdbId > 0) bitmap = load(app, tmdbId, generation); }
            catch (Exception error) { android.util.Log.w("SeriesLogo", "Logo unavailable: " + error.getClass().getSimpleName()); }
            final Bitmap result = bitmap;
            MAIN.post(() -> {
                synchronized (DISK_LOCK) { if (generation != epoch) { callback.onResult(null); return; } }
                callback.onResult(result);
            });
        });
    }

    static void usageAsync(Context context, UsageCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            long bytes;
            synchronized (DISK_LOCK) { bytes = usage(directory(app)); }
            MAIN.post(() -> callback.onResult(bytes));
        });
    }

    static void trimAsync(Context context, UsageCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            long bytes;
            synchronized (DISK_LOCK) {
                prune(directory(app), limit(app));
                bytes = usage(directory(app));
            }
            MAIN.post(() -> callback.onResult(bytes));
        });
    }

    static void clearAsync(Context context, UsageCallback callback) {
        Context app = context.getApplicationContext();
        synchronized (DISK_LOCK) { epoch++; MEMORY.evictAll(); }
        EXECUTOR.execute(() -> {
            long bytes;
            synchronized (DISK_LOCK) {
                for (File file : files(directory(app))) if (file.isFile()) file.delete();
                bytes = usage(directory(app));
            }
            MAIN.post(() -> callback.onResult(bytes));
        });
    }

    private static Bitmap load(Context context, long tmdbId, int generation) throws Exception {
        File dir = directory(context);
        String key = "tv_" + tmdbId + "_zh";
        File cached = new File(dir, key + ".png");
        File missing = new File(dir, key + ".missing");
        synchronized (DISK_LOCK) {
            if (generation != epoch) return null;
            prune(dir, limit(context));
            Bitmap memory = MEMORY.get(key);
            if (memory != null) {
                if (cached.exists()) cached.setLastModified(System.currentTimeMillis());
                return memory;
            }
            if (cached.isFile()) {
                Bitmap result = decode(cached);
                if (result != null) {
                    cached.setLastModified(System.currentTimeMillis());
                    MEMORY.put(key, result);
                    android.util.Log.i("SeriesLogo", "disk cache hit tv=" + tmdbId);
                    return result;
                }
                cached.delete();
            }
            if (missing.isFile() && System.currentTimeMillis() - missing.lastModified() < MISSING_TTL) return null;
        }
        JSONArray images = AutoSkipService.fetchSeriesLogoImages(context, tmdbId);
        if (images == null) return null; // failures are retried; only confirmed missing logos are cached
        List<String> paths = chinesePngPaths(images);
        if (paths.isEmpty()) {
            synchronized (DISK_LOCK) {
                if (generation == epoch && ensureDirectory(dir)) {
                    try (FileOutputStream out = new FileOutputStream(missing)) { out.write(0); }
                    prune(dir, limit(context));
                }
            }
            return null;
        }
        Bitmap bitmap = null;
        for (int i = 0; i < Math.min(3, paths.size()); i++) {
            try { bitmap = download(paths.get(i)); } catch (Exception ignored) {}
            if (bitmap != null) break;
        }
        if (bitmap == null) return null;
        synchronized (DISK_LOCK) {
            if (generation != epoch) return null;
            MEMORY.put(key, bitmap);
            if (ensureDirectory(dir)) {
                File temporary = new File(dir, key + ".tmp");
                try {
                    try (FileOutputStream out = new FileOutputStream(temporary)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
                    }
                    if (!temporary.renameTo(cached)) throw new IOException("Cache rename failed");
                    missing.delete();
                } finally { temporary.delete(); }
                prune(dir, limit(context));
            }
        }
        android.util.Log.i("SeriesLogo", BUILD + " Chinese logo ready tv=" + tmdbId
                + " pixels=" + bitmap.getWidth() + "x" + bitmap.getHeight());
        return bitmap;
    }

    static List<String> chinesePngPaths(JSONArray images) {
        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < images.length(); i++) {
            JSONObject logo = images.optJSONObject(i);
            if (logo == null || !"zh".equalsIgnoreCase(logo.optString("iso_639_1"))) continue;
            String path = logo.optString("file_path");
            if (path.matches("/[A-Za-z0-9]+[.]png") && logo.optInt("width") > 0 && logo.optInt("height") > 0) candidates.add(logo);
        }
        Collections.sort(candidates, (a, b) -> Double.compare(score(b), score(a)));
        List<String> result = new ArrayList<>();
        for (JSONObject item : candidates) result.add(item.optString("file_path"));
        return result;
    }

    private static double score(JSONObject logo) {
        double aspect = logo.optDouble("width") / Math.max(1, logo.optDouble("height"));
        return (aspect >= 2 && aspect <= 8 ? 100 : 0) + logo.optDouble("vote_average")
                + Math.min(logo.optInt("width"), 1500) / 15000.0;
    }

    private static Bitmap download(String path) throws Exception {
        try (okhttp3.Response response = OkHttp.client().newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                .newCall(new okhttp3.Request.Builder().url("https://image.tmdb.org/t/p/w500" + path).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = response.body().byteStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 4 * MB) throw new IOException("Logo too large");
                out.write(buffer, 0, count);
            }
            byte[] bytes = out.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 1024 || bounds.outHeight / options.inSampleSize > 1024) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            return bitmap == null ? null : compact(bitmap);
        }
    }

    static Bitmap compact(Bitmap original) {
        // Remove transparent padding so oversized canvases do not shrink the actual lettering.
        int width = original.getWidth(), height = original.getHeight();
        int[] pixels = new int[width * height];
        original.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width, right = -1, top = height, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if ((pixels[y * width + x] >>> 24) > 8) {
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        if (right < left) return null;
        Bitmap cropped = Bitmap.createBitmap(original, left, top, right - left + 1, bottom - top + 1);
        float ratio = Math.min(1f, Math.min(640f / cropped.getWidth(), 256f / cropped.getHeight()));
        return ratio < 1 ? Bitmap.createScaledBitmap(cropped, Math.max(1, Math.round(cropped.getWidth() * ratio)),
                Math.max(1, Math.round(cropped.getHeight() * ratio)), true) : cropped;
    }

    private static Bitmap decode(File file) {
        if (file.length() > 4 * MB) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 640 || bounds.outHeight > 256) return null;
        return BitmapFactory.decodeFile(file.getPath());
    }

    private static File directory(Context context) { return new File(context.getCacheDir(), "series_logos"); }
    private static long limit(Context context) { return DanmakuConfigManager.getConfig(context).getSeriesLogoCacheMb() * MB; }
    private static boolean ensureDirectory(File dir) { return dir.isDirectory() || dir.mkdirs(); }
    private static File[] files(File dir) { File[] files = dir.listFiles(); return files == null ? new File[0] : files; }

    static long usage(File dir) {
        long size = 0;
        for (File file : files(dir)) if (file.isFile()) size += file.length();
        return size;
    }

    static void prune(File dir, long limit) {
        File[] files = files(dir);
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long bytes = usage(dir);
        for (File file : files) {
            if (bytes <= limit) break;
            long size = file.length();
            if (file.isFile() && file.delete()) bytes -= size;
        }
    }
}
