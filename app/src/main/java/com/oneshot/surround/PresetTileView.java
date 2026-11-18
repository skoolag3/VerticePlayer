package com.oneshot.surround;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Small vector scene for each sound preset; no bitmap downloads required. */
public final class PresetTileView extends View {
    private final Paint paint = new Paint(3);
    private final String name;
    private final int preset;
    public PresetTileView(Context context, String name, int preset) {
        super(context); this.name = name; this.preset = preset;
        setContentDescription("Preset " + name); setClickable(true);
    }
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        paint.setShader(new RadialGradient(w*.5f,h*.35f,w*.7f,
            new int[]{preset == 2 ? 0xff8e3755 : 0xff7139a1, 0xff171321, 0xff101017}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(2,2,w-2,h-2,12,12,paint); paint.setShader(null);
        paint.setColor(0xffc397f5); paint.setStrokeWidth(1.4f); paint.setStyle(Paint.Style.STROKE);
        if (preset == 5) {
            for (int ring = 1; ring < 5; ring++) canvas.drawCircle(w/2,h*.4f,ring*w*.065f,paint);
            canvas.drawLine(w*.15f,h*.4f,w*.85f,h*.4f,paint);
        } else {
            Path scene = new Path(); scene.moveTo(w*.08f,h*.57f);
            for (int i = 0; i <= 20; i++) {
                float x = w*(.08f+.84f*i/20);
                float y = h*(.39f + .15f*(float)Math.sin(i*(preset+2)*.65));
                scene.lineTo(x,y);
            }
            canvas.drawPath(scene,paint);
            for (int bar = 0; bar < 14; bar++) {
                float x = w*(.1f+bar*.06f), height = h*(.05f+.19f*(float)Math.abs(Math.sin(bar*1.7+preset)));
                canvas.drawLine(x,h*.55f,x,h*.55f-height,paint);
            }
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xffefedf4);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(getResources().getDisplayMetrics().density*10);
        canvas.drawText(name,w/2,h-12*getResources().getDisplayMetrics().density,paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(isSelected()?3:1);
        paint.setColor(isSelected()?0xffca98fb:0xff393241);
        canvas.drawRoundRect(2,2,w-2,h-2,12,12,paint); paint.setStyle(Paint.Style.FILL);
    }
    public void setSelected(boolean selected) { super.setSelected(selected); invalidate(); }
}
