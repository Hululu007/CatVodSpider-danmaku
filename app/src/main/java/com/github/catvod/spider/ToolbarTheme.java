package com.github.catvod.spider;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

/** Basic glass uses native drawing only; it never samples or blurs the video surface. */
final class ToolbarTheme {
    static final int TEXT = 0xFFF4F7FC;
    static final int SECONDARY = 0xFFB8C1D1;
    static final int BLUE = 0xFF65ACFF;
    static final int YELLOW = 0xFFFFD54F;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable glass(Context context, boolean panel) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                panel ? new int[]{0xF21E293B, 0xF0161E2C} : new int[]{0xDC192334, 0xDD121826});
        shape.setCornerRadius(dp(context, panel ? 20 : 21));
        shape.setStroke(dp(context, 1), 0x26FFFFFF);
        return shape;
    }

    static Drawable button(Context context) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{android.R.attr.state_focused}, tile(context, 0x302585FF, BLUE));
        result.addState(new int[]{android.R.attr.state_pressed}, tile(context, 0x402585FF, 0x804A97FF));
        result.addState(new int[]{android.R.attr.state_selected}, tile(context, 0x302585FF, 0x664A97FF));
        result.addState(new int[]{android.R.attr.state_activated}, tile(context, 0x302585FF, 0x664A97FF));
        result.addState(new int[]{}, tile(context, 0x08FFFFFF, 0x00FFFFFF));
        return result;
    }

    private static Drawable tile(Context context, int color, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 12));
        shape.setStroke(dp(context, 2), stroke);
        return shape;
    }

    static void text(TextView view, boolean tv) {
        view.setTextColor(new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {}
        }, new int[]{0x667F899B, TEXT, SECONDARY}));
        view.setTextSize(tv ? 15 : 13);
        view.setGravity(android.view.Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setBackground(button(view.getContext()));
        view.setPadding(dp(view.getContext(), 8), dp(view.getContext(), 8),
                dp(view.getContext(), 8), dp(view.getContext(), 8));
        view.setMinimumWidth(0);
        view.setMinimumHeight(0);
    }

    static void danmakuState(TextView view) {
        danmakuSelection(view, false);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, tile(view.getContext(), 0x302585FF, BLUE));
        background.addState(new int[]{android.R.attr.state_pressed}, tile(view.getContext(), 0x402585FF, 0x804A97FF));
        background.addState(new int[]{}, tile(view.getContext(), 0x08FFFFFF, 0x00FFFFFF));
        view.setBackground(background);
    }

    static void danmakuSelection(TextView view, boolean selected) {
        view.setTextColor(new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {}
        }, new int[]{0x667F899B, selected ? YELLOW : TEXT, selected ? YELLOW : SECONDARY}));
    }

    static Drawable icon(Context context, String name) {
        Icon result = new Icon(name);
        int size = dp(context, 23);
        result.setBounds(0, 0, size, size);
        return result;
    }

    private static final class Icon extends Drawable {
        final String name;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int alpha = 255;
        Icon(String name) { this.name = name; }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            int save = canvas.save();
            canvas.translate(b.left, b.top);
            canvas.scale(b.width() / 24f, b.height() / 24f);
            paint.setColor(SECONDARY); paint.setAlpha(alpha); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if ("prev".equals(name) || "next".equals(name)) {
                boolean prev = "prev".equals(name);
                if (prev) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                line(canvas,19,5,19,19); path(canvas,new float[]{5,5,16,12,5,19,5,5});
            } else if ("episodes".equals(name)) {
                for(int y=3;y<=14;y+=11)for(int x=3;x<=14;x+=11)canvas.drawRoundRect(x,y,x+7,y+7,1.5f,1.5f,paint);
            } else if ("playback".equals(name)) {
                for(int y=6;y<=18;y+=6)line(canvas,4,y,20,y);
                canvas.drawCircle(9,6,2,paint);canvas.drawCircle(15,12,2,paint);canvas.drawCircle(8,18,2,paint);
            } else if ("danmaku".equals(name) || "search".equals(name)) {
                if ("search".equals(name)) {canvas.drawCircle(10,10,6,paint);line(canvas,15,15,21,21);}
                else {canvas.drawRoundRect(2,4,22,19,3,3,paint);line(canvas,7,9,17,9);line(canvas,7,13,13,13);}
            } else if ("subtitle".equals(name)) {
                canvas.drawRoundRect(2,5,22,19,3,3,paint);line(canvas,5,11,9,11);line(canvas,13,11,19,11);line(canvas,5,15,12,15);line(canvas,15,15,19,15);
            } else if ("tracks".equals(name)) {
                for(int x=4;x<=20;x+=4){int h=10-Math.abs(12-x);line(canvas,x,12-h,x,12+h);}
            } else if ("chapters".equals(name)) {
                path(canvas,new float[]{5,4,19,4,19,21,12,17,5,21,5,4});line(canvas,8,8,16,8);line(canvas,8,12,13,12);
            } else if ("scale".equals(name)) {
                canvas.drawRoundRect(2,5,22,19,2,2,paint);
                line(canvas,6,9,9,9);line(canvas,6,9,6,12);
                line(canvas,18,15,15,15);line(canvas,18,15,18,12);
            } else if ("close".equals(name)) {line(canvas,6,6,18,18);line(canvas,6,18,18,6);}
            canvas.restoreToCount(save);
        }
        private void line(Canvas c,float x,float y,float xx,float yy){c.drawLine(x,y,xx,yy,paint);}
        private void path(Canvas c,float[] p){Path path=new Path();path.moveTo(p[0],p[1]);for(int i=2;i<p.length;i+=2)path.lineTo(p[i],p[i+1]);c.drawPath(path,paint);}
        @Override public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
