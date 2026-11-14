package com.github.catvod.spider;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.github.catvod.net.OkHttp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;

/** Trial adapter for the archived OK TV 516 layout. All host input stays native. */
final class LoadingPosterController {
    static final String BUILD = "loading-poster-20261113-r3-feather";
    private static final String TAG = "LoadingPoster";
    private static final Map<Activity, Session> SESSIONS = new LinkedHashMap<>();
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> entry) { return size() > 6; }
    };
    private static String selectedName = "";
    private static Bitmap selectedBitmap;

    private LoadingPosterController() {}

    static void rememberSelection(Activity activity) {
        if (activity == null || !activity.getClass().getName().endsWith("CollectActivity")) return;
        selectedName = "";
        selectedBitmap = null;
        View focus = activity.getWindow().getDecorView().findFocus();
        for (int i = 0; focus != null && i < 4; i++) {
            View image = find(focus, "image");
            View name = find(focus, "name");
            if (image instanceof ImageView && name instanceof TextView) {
                Drawable drawable = ((ImageView) image).getDrawable();
                if (drawable instanceof BitmapDrawable) {
                    selectedName = ((TextView) name).getText().toString();
                    selectedBitmap = ((BitmapDrawable) drawable).getBitmap();
                    return;
                }
            }
            focus = focus.getParent() instanceof View ? (View) focus.getParent() : null;
        }
    }

    static void attach(Activity activity) {
        if (activity == null || activity.isFinishing() || SESSIONS.containsKey(activity)) return;
        try {
            if (!"com.fongmi.android.tv".equals(activity.getPackageName())
                    || activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode != 516
                    || !activity.getClass().getName().endsWith("VideoActivity")) return;
            View root = activity.getWindow().getDecorView();
            View control = find(root, "control");
            View video = find(root, "video");
            View detail = find(root, "progressLayout");
            if (!(control instanceof LinearLayout) || !(video instanceof FrameLayout)
                    || !(detail instanceof RelativeLayout)
                    || !"com.fongmi.android.tv.ui.custom.ProgressLayout".equals(detail.getClass().getName())) return;
            Session session = new Session(activity, (RelativeLayout) detail, (FrameLayout) video);
            SESSIONS.put(activity, session);
            session.start();
            Log.i(TAG, BUILD + " bound TV516");
        } catch (Throwable error) { Log.w(TAG, "Poster adapter unavailable", error); release(activity); }
    }

    static void release(Activity activity) {
        Session session = SESSIONS.remove(activity);
        if (session != null) session.close();
    }

    private static final class Session implements Runnable {
        final Activity activity;
        final RelativeLayout detail;
        final FrameLayout video;
        final Handler handler = new Handler(Looper.getMainLooper());
        final FrameLayout detailPoster;
        final FrameLayout videoPoster;
        final ImageView detailImage;
        final ImageView videoImage;
        final View detailLoading;
        final View detailError;
        final View videoLoading;
        Object exo;
        Object ijk;
        Object exoListener;
        Object ijkListener;
        Object originalIjkListener;
        String signature = "";
        String picture = "";
        int generation;
        boolean closed;
        boolean rendered;
        boolean sawLoading;
        boolean hooksInstalled;

        Session(Activity activity, RelativeLayout detail, FrameLayout video) throws Exception {
            this.activity = activity;
            this.detail = detail;
            this.video = video;
            detailLoading = (View) field(detail, "n");
            detailError = (View) field(detail, "o");
            videoLoading = find(video, "progress");
            detailPoster = poster(activity, true);
            detailPoster.setTag("ProgressLayout.TAG_PROGRESS"); // excluded from host content animation
            detailImage = (ImageView) detailPoster.getChildAt(0);
            detail.addView(detailPoster, 0, new RelativeLayout.LayoutParams(-1, -1));
            videoPoster = poster(activity, false);
            videoPoster.setTag("aurora_loading_poster");
            videoImage = (ImageView) videoPoster.getChildAt(0);
            // Above both renderers, below native danmaku/control/progress layers.
            int index = 0;
            for (int i = 0; i < video.getChildCount(); i++) {
                String id = name(video.getChildAt(i));
                if ("exo".equals(id) || "ijk".equals(id)) index = i + 1;
            }
            video.addView(videoPoster, index, new FrameLayout.LayoutParams(-1, -1));
        }

        void start() { updateIdentity(); run(); }

        void updateIdentity() {
            String current = activity.getIntent().getStringExtra("key") + "|"
                    + activity.getIntent().getStringExtra("id");
            String pic = activity.getIntent().getStringExtra("pic");
            String title = activity.getIntent().getStringExtra("name");
            if (TextUtils.equals(signature, current) && TextUtils.equals(picture, pic)) return;
            signature = current;
            picture = pic;
            generation++;
            rendered = false;
            sawLoading = false;
            detailImage.setImageDrawable(null);
            videoImage.setImageDrawable(null);
            Bitmap bitmap = CACHE.get(pic);
            if (bitmap == null && !TextUtils.isEmpty(title) && title.equals(selectedName)) {
                bitmap = selectedBitmap;
                if (bitmap != null && !TextUtils.isEmpty(pic)) CACHE.put(pic, bitmap);
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                setBitmap(bitmap);
                Log.i(TAG, "poster ready from memory");
            } else if (!TextUtils.isEmpty(pic) && (pic.startsWith("http://") || pic.startsWith("https://"))) {
                final int request = generation;
                final String url = pic;
                Init.execute(() -> {
                    Bitmap result = null;
                    try (okhttp3.Response response = OkHttp.client().newBuilder()
                            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                            .newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            java.io.InputStream in = response.body().byteStream();
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (out.size() + n > 4 * 1024 * 1024) throw new java.io.IOException("image too large");
                                out.write(buffer, 0, n);
                            }
                            byte[] bytes = out.toByteArray();
                            BitmapFactory.Options bounds = new BitmapFactory.Options();
                            bounds.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inSampleSize = 1;
                            while (bounds.outHeight / options.inSampleSize > 1080
                                    || bounds.outWidth / options.inSampleSize > 1080) options.inSampleSize *= 2;
                            result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                        }
                    } catch (Throwable ignored) {}
                    final Bitmap ready = result;
                    handler.post(() -> {
                        if (closed || generation != request) return;
                        if (ready != null) { CACHE.put(url, ready); setBitmap(ready); Log.i(TAG, "poster downloaded"); }
                    });
                });
            }
        }

        void setBitmap(Bitmap bitmap) { detailImage.setImageBitmap(bitmap); videoImage.setImageBitmap(bitmap); }

        @Override public void run() {
            if (closed) return;
            if (activity.isDestroyed() || activity.isFinishing()) { release(activity); return; }
            try {
                updateIdentity();
                if (!hooksInstalled) installHooks();
                boolean waiting = detailLoading.getVisibility() == View.VISIBLE;
                boolean failed = detailError.getVisibility() == View.VISIBLE;
                visible(detailPoster, waiting && !failed);
                if (!waiting && !failed && video.getVisibility() == View.VISIBLE) {
                    if (videoLoading != null && videoLoading.getVisibility() == View.VISIBLE) sawLoading = true;
                    visible(videoPoster, !rendered && hooksInstalled);
                } else visible(videoPoster, false);
            } catch (Throwable error) { Log.w(TAG, "Poster released after host mismatch", error); release(activity); return; }
            handler.postDelayed(this, 150);
        }

        void installHooks() throws Exception {
            View exoView = find(video, "exo");
            ijk = find(video, "ijk");
            if (exoView == null || ijk == null) return;
            exo = field(exoView, "E");
            if (exo == null) return;
            if (!"B0.K".equals(exo.getClass().getName())) throw new IllegalStateException("unknown Exo type");
            ClassLoader loader = activity.getClassLoader();
            Class<?> listenerType = Class.forName("o0.M", false, loader);
            Object listeners = field(exo, "z");
            CopyOnWriteArraySet<Object> set = (CopyOnWriteArraySet<Object>) field(listeners, "d");
            Class<?> holderType = Class.forName("r0.o", false, loader);
            exoListener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return BUILD;
                // U1.A.A() is the archived PlayerView first-frame callback (hides shutter).
                if ("A".equals(method.getName()) && method.getParameterTypes().length == 0) onFirstFrame("Exo");
                return null;
            });
            java.lang.reflect.Constructor<?> constructor = holderType.getDeclaredConstructor(Object.class);
            constructor.setAccessible(true);
            set.add(constructor.newInstance(exoListener));
            Field ijkField = getField(ijk.getClass(), "mListener");
            originalIjkListener = ijkField.get(ijk);
            Class<?> ijkType = Class.forName("tv.danmaku.ijk.media.player.IMediaPlayer$Listener", false, loader);
            ijkListener = Proxy.newProxyInstance(loader, new Class<?>[]{ijkType}, (proxy, method, args) -> {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return BUILD;
                Object result = originalIjkListener == null ? null : method.invoke(originalIjkListener, args);
                if ("onInfo".equals(method.getName()) && ((Integer) args[1]) == 3) onFirstFrame("IJK");
                if ("onError".equals(method.getName())) onFirstFrame("IJK error");
                return method.getReturnType() == boolean.class && result == null ? false : result;
            });
            ijkField.set(ijk, ijkListener);
            hooksInstalled = true;
            Log.i(TAG, "first-frame listeners installed");
        }

        void onFirstFrame(String engine) {
            handler.post(() -> {
                if (closed) return;
                rendered = true;
                visible(videoPoster, false);
                Log.i(TAG, "first frame " + engine + "; poster hidden");
            });
        }

        void close() {
            closed = true;
            generation++;
            handler.removeCallbacksAndMessages(null);
            try { if (exoListener != null) exo.getClass().getMethod("d0", exoListener.getClass().getInterfaces()[0]).invoke(exo, exoListener); } catch (Throwable ignored) {}
            try {
                if (ijkListener != null && field(ijk, "mListener") == ijkListener) getField(ijk.getClass(), "mListener").set(ijk, originalIjkListener);
            } catch (Throwable ignored) {}
            detail.removeView(detailPoster);
            video.removeView(videoPoster);
            detailImage.setImageDrawable(null);
            videoImage.setImageDrawable(null);
        }
    }

    private static FrameLayout poster(Activity activity, boolean full) {
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(0xFF000000);
        frame.setFocusable(false);
        frame.setClickable(false);
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ImageView image = new FeatheredPosterView(activity);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        int padding = (int) (activity.getResources().getDisplayMetrics().density * (full ? 54 : 12));
        lp.topMargin = padding;
        lp.bottomMargin = padding;
        frame.addView(image, lp);
        frame.setVisibility(View.GONE);
        return frame;
    }

    private static void visible(View view, boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) {
            view.setVisibility(visibility);
            Log.i(TAG, (show ? "show " : "hide ") + ("aurora_loading_poster".equals(view.getTag()) ? "video poster" : "detail poster"));
        }
    }

    private static String name(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()); } catch (Throwable ignored) { return ""; }
    }

    private static View find(View root, String name) {
        if (name.equals(name(root))) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), name);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static Field getField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field field = c.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static Object field(Object object, String name) throws Exception { return getField(object.getClass(), name).get(object); }
}
