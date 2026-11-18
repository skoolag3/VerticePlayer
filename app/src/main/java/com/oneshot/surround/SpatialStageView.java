package com.oneshot.surround;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Lightweight vector artwork; its rings represent the selected spatial strength. */
public final class SpatialStageView extends View {
    private final Paint paint = new Paint(3);
    private int strength = 80;
    public SpatialStageView(Context context) { super(context); setContentDescription("Campo espacial estéreo"); }
    public void setStrength(int value) { strength = value; invalidate(); }
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h * .52f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, w * .48f,
            new int[]{0x553e176a, 0x1726193f, 0x00101018}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        for (int ring = 0; ring < 4; ring++) {
            paint.setColor((45 + strength) << 24 | 0x9e63e7); paint.setStrokeWidth(1.2f);
            float rx = w * (.27f + ring * .065f), ry = h * (.19f + ring * .035f);
            canvas.drawOval(cx-rx,cy-ry,cx+rx,cy+ry,paint);
        }
        float headW = w * .145f, headH = h * .25f;
        paint.setColor(0xffc99af6); paint.setStrokeWidth(w * .008f);
        canvas.drawOval(cx-headW,cy-headH,cx+headW,cy+headH,paint);
        for (int line = -4; line <= 4; line++) {
            paint.setColor(0x557f58ad); paint.setStrokeWidth(1);
            canvas.drawOval(cx-headW,cy-headH*Math.abs(line)/5,cx+headW,cy+headH*Math.abs(line)/5,paint);
        }
        paint.setColor(0xffb58ae8); paint.setStrokeWidth(w * .014f);
        canvas.drawArc(cx-headW*1.35f,cy-headH*1.2f,cx+headW*1.35f,cy+headH*.8f,180,180,false,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(cx-headW*1.48f,cy-headH*.25f,cx-headW*1.06f,cy+headH*.55f,8,8,paint);
        canvas.drawRoundRect(cx+headW*1.06f,cy-headH*.25f,cx+headW*1.48f,cy+headH*.55f,8,8,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
        Path shoulders = new Path(); shoulders.moveTo(cx-w*.27f,h*.91f);
        shoulders.cubicTo(cx-w*.24f,h*.70f,cx-w*.08f,h*.85f,cx-w*.07f,h*.72f);
        shoulders.moveTo(cx+w*.27f,h*.91f);
        shoulders.cubicTo(cx+w*.24f,h*.70f,cx+w*.08f,h*.85f,cx+w*.07f,h*.72f);
        canvas.drawPath(shoulders,paint); paint.setStyle(Paint.Style.FILL);
    }
}
