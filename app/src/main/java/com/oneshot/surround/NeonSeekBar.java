package com.oneshot.surround;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Audiophile Timeline SeekBar with smooth animated overlays (no ugly jagged lightning):
 * - Effect 0: Normal (clean studio bar)
 * - Effect 1: Aurora Dual-Tone Glow (breathing violet + cyan studio gradient)
 * - Effect 2: Onda Plasma (smooth harmonic double-helix sine wave)
 * - Effect 3: Halo Pulse (expanding sub-bass energy ring on playhead + floating micro-sparks)
 */
public final class NeonSeekBar extends androidx.appcompat.widget.AppCompatSeekBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path waveA = new Path();
    private final Path waveB = new Path();
    private int effect;
    private float phase;

    public NeonSeekBar(Context context) {
        super(context);
        setMinimumHeight(dp(42));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public void setEffect(int value) {
        effect = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (effect == 0) return;

        float start = getPaddingLeft();
        float usable = getWidth() - getPaddingLeft() - getPaddingRight();
        float ratio = getProgress() / Math.max(1f, (float) getMax());
        float end = start + usable * ratio;
        float y = getHeight() * 0.5f;

        if (end <= start + dp(2)) {
            if (isShown() && isEnabled()) {
                phase += 0.09f;
                postInvalidateDelayed(36);
            }
            return;
        }

        // Soft studio ambient under-glow along the active progress segment
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float pulse = 0.72f + 0.28f * (float) Math.sin(phase * 1.6f);

        paint.setShader(new LinearGradient(
            start, y, end, y,
            new int[]{0x559D7BFF, 0x8800E5FF, 0xAA9D7BFF},
            new float[]{0f, 0.65f, 1f},
            Shader.TileMode.CLAMP
        ));
        paint.setStrokeWidth(dp(6f * pulse));
        canvas.drawLine(start, y, end, y, paint);
        paint.setShader(null);

        if (effect == 1) {
            // Effect 1: Aurora Dual-Tone Studio Beam + Breathing Playhead Ring
            paint.setShader(new LinearGradient(
                start, y, end, y,
                MainActivity.ACCENT, MainActivity.TECH_CYAN, Shader.TileMode.CLAMP
            ));
            paint.setStrokeWidth(dp(2.4f));
            canvas.drawLine(start, y, end, y, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x4400E5FF);
            canvas.drawCircle(end, y, dp(7f + 2.5f * pulse), paint);
        } else if (effect == 2) {
            // Effect 2: Onda Plasma (Smooth Double-Helix Harmonic Wave — replaces old "Raios")
            waveA.reset();
            waveB.reset();
            waveA.moveTo(start, y);
            waveB.moveTo(start, y);
            float span = Math.max(1f, end - start);
            int steps = Math.max(16, Math.min(90, Math.round(span / dp(4))));
            for (int i = 1; i <= steps; i++) {
                float t = i / (float) steps;
                float x = start + span * t;
                // Envelope tapers smoothly to 0 at both ends so it locks onto the thumb cleanly
                float env = (float) Math.sin(t * Math.PI);
                float amp = dp(3.2f) * env;
                float offsetA = (float) Math.sin(t * 10f - phase * 2.2f) * amp;
                float offsetB = (float) Math.sin(t * 10f - phase * 2.2f + Math.PI) * amp * 0.75f;
                waveA.lineTo(x, y + offsetA);
                waveB.lineTo(x, y + offsetB);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.8f));
            paint.setColor(0xCC00E5FF);
            canvas.drawPath(waveA, paint);

            paint.setStrokeWidth(dp(1.4f));
            paint.setColor(0xBB9D7BFF);
            canvas.drawPath(waveB, paint);
        } else if (effect == 3) {
            // Effect 3: Halo Sub-Grave (Expanding sonar ring at the thumb + flowing micro-orbs)
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 6; i++) {
                float p = (phase * 0.08f + i / 6f) % 1f;
                float x = start + (end - start) * p;
                int alpha = Math.round((float) Math.sin(p * Math.PI) * 210);
                paint.setColor((alpha << 24) | (i % 2 == 0 ? 0x00E5FF : 0xE2C5FF));
                canvas.drawCircle(x, y, dp(1.8f), paint);
            }
            float ringPhase = (phase * 0.25f) % 1f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f * (1f - ringPhase)));
            paint.setColor((Math.round((1f - ringPhase) * 190) << 24) | 0x00E5FF);
            canvas.drawCircle(end, y, dp(5f + ringPhase * 11f), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        if (isShown() && isEnabled()) {
            phase += 0.10f;
            postInvalidateDelayed(32);
        }
    }
}
