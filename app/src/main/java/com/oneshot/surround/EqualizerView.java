package com.oneshot.surround;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Ten independently draggable bands with a shared dB scale. */
public final class EqualizerView extends View {
    public interface Listener { void onChange(int band, int gain); }
    private final Paint paint = new Paint(3);
    private final double[] gains;
    private final Listener listener;
    private int active = -1;
    private final String[] names = {"31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
    public EqualizerView(Context context, double[] gains, Listener listener) {
        super(context); this.gains = gains; this.listener = listener;
        setContentDescription("Equalizador de dez bandas, de menos doze a mais doze decibéis");
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = dp(28), bottom = getHeight() - dp(38), left = dp(34);
        paint.setTextSize(dp(9)); paint.setStrokeWidth(dp(1));
        for (int mark = 0; mark < 3; mark++) {
            float y = top + (bottom - top) * mark / 2;
            paint.setColor(0xff30303e); canvas.drawLine(left, y, getWidth() - dp(8), y, paint);
            paint.setColor(0xff9694a5); canvas.drawText(new String[]{"+12", "0 dB", "−12"}[mark], 0, y + dp(3), paint);
        }
        float step = (getWidth() - left - dp(8)) / 10;
        for (int band = 0; band < 10; band++) {
            float x = left + step * (band + .5f);
            float y = top + (float)(12 - gains[band]) / 24 * (bottom - top);
            paint.setColor(0xff373441); paint.setStrokeWidth(dp(2)); canvas.drawLine(x, top, x, bottom, paint);
            paint.setColor(0xffb58ae8); canvas.drawLine(x, y, x, bottom, paint);
            paint.setColor(0xffe0c3ff); canvas.drawCircle(x, y, dp(6), paint);
            paint.setColor(0xffaaa6b8); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(names[band], x, bottom + dp(24), paint); paint.setTextAlign(Paint.Align.LEFT);
        }
    }
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            active = Math.max(0, Math.min(9, (int)((event.getX() - dp(34)) / ((getWidth() - dp(42)) / 10))));
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (active >= 0) {
            int gain = Math.max(-12, Math.min(12, Math.round(12 - (event.getY() - dp(28)) / (getHeight() - dp(66)) * 24)));
            gains[active] = gain; listener.onChange(active, gain); invalidate();
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            active = -1; getParent().requestDisallowInterceptTouchEvent(false); performClick();
        }
        return true;
    }
    public boolean performClick() { super.performClick(); return true; }
}
