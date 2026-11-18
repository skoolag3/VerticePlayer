package com.oneshot.surround;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import java.util.Locale;

public final class ArtworkView extends View {
    private final SpectrumOrbit orbit = new SpectrumOrbit();
    private final Rect srcRect = new Rect();
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float[] silent = new float[24];
    private AudioReactiveAnalyzer analyzer;
    private Bitmap cover;
    private boolean playing, orbital;
    private int accentColor = MainActivity.ACCENT;
    private float phase;
    public ArtworkView(Context context) { super(context); }
    public void setCover(Bitmap value) { cover = value; invalidate(); }
    public void setAccentColor(int value) { accentColor = value; invalidate(); }
    public void setPlaying(boolean value) {
        playing = value;
        if (getContext() instanceof MainActivity) {
            MusicBackground background = ((MainActivity)getContext()).musicBackground;
            if (background != null) background.setPlaying(value);
        }
        invalidate();
    }
    public void setAnalyzer(AudioReactiveAnalyzer value) { analyzer = value; }
    public void setAppearance(int mode, boolean bounce, boolean rotate, boolean blur) {
        orbital = mode == 1; invalidate();
    }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private int withAlpha(int color, int alpha) { return (color & 0xffffff) | (alpha << 24); }
    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), side = Math.min(w,h) * (orbital ? 0.93f : 1f);
        if (side <= 0) return;
        AudioReactiveAnalyzer.Frame frame = playing && analyzer != null ? analyzer.frame() : null;
        float[] bands = frame == null ? silent : frame.bands;
        if (orbital) {
            orbit.prepare(w,h,phase,frame == null ? 0 : frame.balance);
            orbit.draw(canvas,w,h,bands,accentColor,false);
        }
        bounds.set((w-side)/2,(h-side)/2,(w+side)/2,(h+side)/2);
        clip.rewind(); clip.addRoundRect(bounds,dp(14),dp(14),Path.Direction.CW);
        canvas.save(); canvas.clipPath(clip); drawBaseCover(canvas,bounds); canvas.restore();
        if (orbital) {
            orbit.draw(canvas,w,h,bands,accentColor,true);
            if (playing && isShown()) { phase += .035f; postInvalidateDelayed(33); }
        }
    }
    private void drawBaseCover(Canvas canvas, RectF bounds) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            0xFF181C26, 0xFF0A0C12, Shader.TileMode.CLAMP
        ));
        canvas.drawRect(bounds, paint);
        paint.setShader(null);

        if (cover != null && !cover.isRecycled()) {
            int side = Math.min(cover.getWidth(), cover.getHeight());
            int left = (cover.getWidth() - side) / 2;
            int top = (cover.getHeight() - side) / 2;
            srcRect.set(left, top, left + side, top + side);
            canvas.drawBitmap(cover, srcRect, bounds, paint);
            return;
        }

        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float radius = bounds.width() * 0.40f;
        for (int ring = 0; ring < 5; ring++) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ring == 0 ? dp(2.4f) : dp(1.2f));
            paint.setColor(ring == 0 ? accentColor : withAlpha(MainActivity.TECH_CYAN, 95));
            canvas.drawCircle(cx, cy, radius * (0.28f + ring * 0.16f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accentColor);
        canvas.drawCircle(cx, cy, radius * 0.22f, paint);
        paint.setColor(0xFF0A0C12);
        canvas.drawCircle(cx, cy, radius * 0.08f, paint);
    }
}
