package com.github.catvod.spider;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Four smooth edge fades follow the fitted artwork bounds, including letterboxed images. */
final class FeatheredPosterView extends ImageView {
    private final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF artwork = new RectF();
    private final RectF previous = new RectF();
    private final Shader[] gradients = new Shader[4];
    private float feather;
    private static final int[] COLORS = {0xFF000000, 0xE0000000, 0x80000000, 0x28000000, 0};
    private static final float[] STOPS = {0, .25f, .5f, .75f, 1};

    FeatheredPosterView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
        setColorFilter(0xFF666666, PorterDuff.Mode.MULTIPLY);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        artwork.set(drawable.getBounds());
        getImageMatrix().mapRect(artwork);
        artwork.offset(getPaddingLeft(), getPaddingTop());
        if (artwork.width() <= 0 || artwork.height() <= 0) return;
        if (!artwork.equals(previous) || gradients[0] == null) {
            previous.set(artwork);
            feather = Math.min(artwork.width(), artwork.height()) * .14f;
            gradients[0] = gradient(artwork.left, 0, artwork.left + feather, 0);
            gradients[1] = gradient(artwork.right, 0, artwork.right - feather, 0);
            gradients[2] = gradient(0, artwork.top, 0, artwork.top + feather);
            gradients[3] = gradient(0, artwork.bottom, 0, artwork.bottom - feather);
        }
        fade.setShader(gradients[0]);
        canvas.drawRect(artwork.left, artwork.top, artwork.left + feather, artwork.bottom, fade);
        fade.setShader(gradients[1]);
        canvas.drawRect(artwork.right - feather, artwork.top, artwork.right, artwork.bottom, fade);
        fade.setShader(gradients[2]);
        canvas.drawRect(artwork.left, artwork.top, artwork.right, artwork.top + feather, fade);
        fade.setShader(gradients[3]);
        canvas.drawRect(artwork.left, artwork.bottom - feather, artwork.right, artwork.bottom, fade);
    }

    private static Shader gradient(float x1, float y1, float x2, float y2) {
        return new LinearGradient(x1, y1, x2, y2, COLORS, STOPS, Shader.TileMode.CLAMP);
    }
}
