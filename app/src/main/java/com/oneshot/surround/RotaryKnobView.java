package com.oneshot.surround;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Custom studio rotary potentiometer control drawn on Canvas.
 * Supports unipolar (0..max) and bipolar (-X..+X from 12 o'clock) arcs,
 * smooth vertical drag, and double-tap to reset to default value.
 */
public final class RotaryKnobView extends View {
    public interface ValueFormatter {
        String format(int value);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final String title;
    private final int minimum;
    private final int maximum;
    private final int defaultValue;
    private final boolean bipolar;
    private int accentColor = MainActivity.ACCENT;
    private int currentValue;
    private ValueFormatter formatter;
    private IntConsumer onRawProgressChanged;

    private float downY;
    private int downValue;
    private boolean dragging;
    private long lastTapTime;
    private final int touchSlop;

    public RotaryKnobView(Context context, String title, int minimum, int maximum, int initial, int defaultValue) {
        super(context);
        this.title = title;
        this.minimum = minimum;
        this.maximum = Math.max(minimum + 1, maximum);
        this.defaultValue = clampValue(defaultValue);
        this.currentValue = clampValue(initial);
        this.bipolar = minimum < 0 && maximum > 0;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);
        setContentDescription(title);
    }

    public void setAccentColor(int color) {
        this.accentColor = color;
        invalidate();
    }

    public void setFormatter(ValueFormatter formatter) {
        this.formatter = formatter;
        invalidate();
    }

    public void setOnRawProgressChanged(IntConsumer listener) {
        this.onRawProgressChanged = listener;
    }

    public int getRawProgress() {
        return currentValue - minimum;
    }

    public void setRawProgress(int rawProgress) {
        int next = clampValue(minimum + rawProgress);
        if (next != currentValue) {
            currentValue = next;
            invalidate();
        }
    }

    public int getValue() {
        return currentValue;
    }

    public void setValue(int value) {
        int next = clampValue(value);
        if (next != currentValue) {
            currentValue = next;
            invalidate();
        }
    }

    private int clampValue(int val) {
        return Math.max(minimum, Math.min(maximum, val));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private String formattedValue() {
        if (formatter != null) return formatter.format(currentValue);
        if (bipolar && currentValue > 0) return "+" + currentValue;
        return String.valueOf(currentValue);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredW = Math.round(dp(96));
        int desiredH = Math.round(dp(112));
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float labelHeight = dp(20);
        float dialAreaH = Math.max(dp(56), h - labelHeight);
        float cx = w * 0.5f;
        float cy = dialAreaH * 0.5f + dp(2);
        float outerR = Math.min(w, dialAreaH) * 0.37f;
        float trackStroke = dp(3.8f);

        // 1. Calibrated tick marks around 270 deg sweep (135 deg to 405 deg)
        float tickInner = outerR + dp(5f);
        float tickOuter = outerR + dp(8.5f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int t = 0; t <= 10; t++) {
            double rad = Math.toRadians(135.0 + t * 27.0);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            boolean centerTick = bipolar && t == 5;
            paint.setStrokeWidth(centerTick ? dp(1.8f) : dp(1.1f));
            paint.setColor(centerTick ? (0xCCFFFFFF) : 0xFF343A4D);
            canvas.drawLine(cx + cos * tickInner, cy + sin * tickInner,
                cx + cos * tickOuter, cy + sin * (centerTick ? tickOuter + dp(1.5f) : tickOuter), paint);
        }

        // 2. Background arc (270 deg)
        arcRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        paint.setStrokeWidth(trackStroke);
        paint.setColor(0xFF202432);
        canvas.drawArc(arcRect, 135f, 270f, false, paint);

        // 3. Active luminous arc (unipolar or bipolar)
        float fraction = (currentValue - minimum) / (float) (maximum - minimum);
        float angle = 135f + fraction * 270f;
        float startAngle = bipolar ? 270f : 135f;
        float sweepAngle = bipolar ? (angle - 270f) : (fraction * 270f);

        if (Math.abs(sweepAngle) > 0.5f) {
            paint.setColor((0x38 << 24) | (accentColor & 0x00FFFFFF));
            paint.setStrokeWidth(trackStroke + dp(5f));
            canvas.drawArc(arcRect, startAngle, sweepAngle, false, paint);

            paint.setColor(accentColor);
            paint.setStrokeWidth(trackStroke);
            canvas.drawArc(arcRect, startAngle, sweepAngle, false, paint);
        }

        // 4. Inner brushed metallic studio cap
        float capR = outerR - dp(6.5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx - capR * 0.2f, cy - capR * 0.25f, capR * 1.4f,
            new int[]{0xFF232838, 0xFF141720, 0xFF0C0E14}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, capR, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0xFF33394E);
        canvas.drawCircle(cx, cy, capR, paint);

        // 5. Indicator notch on outer cap rim
        double indRad = Math.toRadians(angle);
        float indStart = capR * 0.62f;
        float indEnd = capR * 0.88f;
        paint.setStrokeWidth(dp(2.4f));
        paint.setColor(dragging ? 0xFFFFFFFF : accentColor);
        canvas.drawLine(
            cx + (float) Math.cos(indRad) * indStart,
            cy + (float) Math.sin(indRad) * indStart,
            cx + (float) Math.cos(indRad) * indEnd,
            cy + (float) Math.sin(indRad) * indEnd,
            paint
        );

        // 6. Center readout (Monospace, scaled to stay strictly inside metallic cap)
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.min(dp(11.5f), capR * 0.41f));
        paint.setColor(0xFFF2F5FA);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(formattedValue(), cx, textY, paint);

        // 7. Bottom uppercase studio label
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(dp(10f));
        paint.setColor(dragging ? accentColor : 0xFF8B93A7);
        canvas.drawText(title.toUpperCase(Locale.ROOT), cx, h - dp(4.5f), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downY = event.getY();
                downValue = currentValue;
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dy = downY - event.getY();
                if (!dragging && Math.abs(dy) > touchSlop) {
                    dragging = true;
                    invalidate();
                }
                if (dragging) {
                    float dragDistance = dp(135f);
                    int range = maximum - minimum;
                    int delta = Math.round((dy / dragDistance) * range);
                    int next = clampValue(downValue + delta);
                    if (next != currentValue) {
                        currentValue = next;
                        invalidate();
                        if (onRawProgressChanged != null) {
                            onRawProgressChanged.accept(currentValue - minimum);
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapTime < 320L) {
                        // Double-tap resets to default studio detent
                        if (currentValue != defaultValue) {
                            currentValue = defaultValue;
                            invalidate();
                            if (onRawProgressChanged != null) {
                                onRawProgressChanged.accept(currentValue - minimum);
                            }
                        }
                        lastTapTime = 0;
                    } else {
                        lastTapTime = now;
                        performClick();
                    }
                }
                dragging = false;
                invalidate();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(event);
    }
}
