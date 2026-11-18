package com.oneshot.surround;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Interactive A–Z + # Alphabet Fast-Scroll Bar for Library lists (Tracks, Artists, Albums).
 * Dragging or tapping along the right rail jumps instantly to the matching letter section
 * and shows a floating studio badge with the active letter.
 */
public final class AlphabetIndexView extends View {
    public interface OnLetterSelectListener {
        void onLetterSelected(char letter);
    }

    private static final char[] LETTERS = {
        '#', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
        'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF railBounds = new RectF();
    private final RectF bubbleBounds = new RectF();
    private int selectedIndex = -1;
    private boolean touching;
    private OnLetterSelectListener listener;

    public AlphabetIndexView(Context context) {
        super(context);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnLetterSelectListener(OnLetterSelectListener value) {
        this.listener = value;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float railW = dp(22);
        float railLeft = w - railW - dp(2);
        float padY = dp(6);
        float usableH = Math.max(dp(180), h - padY * 2f);
        railBounds.set(railLeft, padY, railLeft + railW, padY + usableH);

        // Subtle pill background on the right rail
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(touching ? 0xEE181C28 : 0xB812141C);
        canvas.drawRoundRect(railBounds, dp(11), dp(11), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(touching ? MainActivity.ACCENT : MainActivity.BORDER);
        canvas.drawRoundRect(railBounds, dp(11), dp(11), paint);

        paint.setStyle(Paint.Style.FILL);
        float step = usableH / LETTERS.length;
        float textSize = Math.min(dp(10.2f), step * 0.78f);
        paint.setTextSize(textSize);
        float cx = railBounds.centerX();

        for (int i = 0; i < LETTERS.length; i++) {
            float cy = padY + step * (i + 0.5f);
            boolean active = (i == selectedIndex && touching);
            if (active) {
                paint.setColor(0x4400E5FF);
                canvas.drawCircle(cx, cy, dp(8.5f), paint);
                paint.setColor(MainActivity.TECH_CYAN);
            } else {
                paint.setColor(i == selectedIndex ? MainActivity.ACCENT : MainActivity.MUTED);
            }
            Paint.FontMetrics fm = paint.getFontMetrics();
            float textY = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(String.valueOf(LETTERS[i]), cx, textY, paint);
        }

        // Floating HUD letter bubble to the left of the finger while dragging
        if (touching && selectedIndex >= 0 && selectedIndex < LETTERS.length) {
            float cy = padY + step * (selectedIndex + 0.5f);
            float bubbleSize = dp(42);
            float bx = Math.max(dp(4), railLeft - bubbleSize - dp(10));
            float by = Math.max(dp(4), Math.min(h - bubbleSize - dp(4), cy - bubbleSize * 0.5f));
            bubbleBounds.set(bx, by, bx + bubbleSize, by + bubbleSize);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xF21A1D28);
            canvas.drawRoundRect(bubbleBounds, dp(14), dp(14), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(MainActivity.TECH_CYAN);
            canvas.drawRoundRect(bubbleBounds, dp(14), dp(14), paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(19));
            paint.setColor(MainActivity.TEXT);
            Paint.FontMetrics bfm = paint.getFontMetrics();
            float bTextY = bubbleBounds.centerY() - (bfm.ascent + bfm.descent) * 0.5f;
            canvas.drawText(String.valueOf(LETTERS[selectedIndex]), bubbleBounds.centerX(), bTextY, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        if (action == MotionEvent.ACTION_DOWN) {
            // Only intercept touches on the right rail area
            if (x < getWidth() - dp(32)) {
                return false;
            }
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            touching = true;
            float padY = dp(6);
            float usableH = Math.max(dp(180), getHeight() - padY * 2f);
            int idx = Math.round(((y - padY) / usableH) * (LETTERS.length - 1));
            idx = Math.max(0, Math.min(LETTERS.length - 1, idx));
            if (idx != selectedIndex || action == MotionEvent.ACTION_DOWN) {
                selectedIndex = idx;
                if (listener != null) {
                    listener.onLetterSelected(LETTERS[idx]);
                }
            }
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touching = false;
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }
}
