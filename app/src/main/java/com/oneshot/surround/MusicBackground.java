package com.oneshot.surround;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Translucent ribbons follow the decoded spectrum across the viewport. */
final class MusicBackground extends View {
    private static final int[] COLORS = {0xff0a0b10,0xff101e35,0xff28172c,0xff10292b,0xff30231e};
    private final MainActivity host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ribbon = new Path();
    private int color;
    private int scene;
    private boolean playing;
    private float phase;

    MusicBackground(MainActivity host) { super(host); this.host = host; }
    static int previousColor(int index) { return COLORS[Math.max(0, Math.min(index, COLORS.length-1))]; }
    void configure(int rgb, int scene) {
        color = rgb;
        this.scene = scene;
        invalidate();
    }
    void setPlaying(boolean value) {
        if (playing != value) { playing = value; invalidate(); }
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(color);
        if (scene == 0 || host.player == null) return;
        AudioReactiveAnalyzer.Frame frame = host.player.getVisualAnalyzer().frame();
        float w = getWidth(), h = getHeight();
        if (scene == 2) {
            for (int star = 0; star < 100; star++) {
                float energy = playing ? frame.bands[star % 24] : 0;
                float depth = (phase * .05f + star * .137f) % 1f;
                double angle = star * 2.39996;
                float x = w * (.5f + (float)Math.cos(angle) * depth * .76f);
                float y = h * (.5f + (float)Math.sin(angle) * depth * .76f);
                paint.setColor(host.dynamicAccent);
                paint.setAlpha((int)(45 + 160 * depth));
                canvas.drawCircle(x,y,(1 + energy * 4 + depth * 2) * getResources().getDisplayMetrics().density,paint);
            }
        } else if (scene == 3) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2);
            for (int ring = 0; ring < 24; ring++) {
                float distance = (ring / 24f + phase * .035f) % 1f;
                float energy = playing ? frame.bands[ring] : 0;
                paint.setColor(host.dynamicAccent);
                paint.setAlpha((int)(40 + 95 * Math.min(1,energy)));
                canvas.drawCircle(w * .45f,h * .60f,distance * Math.max(w,h),paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else for (int layer = 0; layer < 12; layer++) {
            ribbon.rewind();
            for (int step = 0; step <= 64; step++) {
                float u = step / 64f;
                float energy = playing ? frame.bands[(step / 3 + layer) % 24] : 0;
                double wave = Math.sin(u * 5 + phase + layer * .17)
                    + .42 * Math.sin(u * 8 - phase * .7 + layer * .11);
                float y = h * (.40f + layer * .023f + (float)wave * .16f + energy * .055f);
                if (step == 0) ribbon.moveTo(0,y); else ribbon.lineTo(w*u,y);
            }
            ribbon.lineTo(w,h); ribbon.lineTo(0,h); ribbon.close();
            paint.setColor((host.dynamicAccent & 0xffffff) | (9 << 24));
            canvas.drawPath(ribbon,paint);
        }
        if (playing && isShown()) {
            phase += .012f + frame.bass * .033f;
            postInvalidateDelayed(40);
        }
    }
}
