package com.oneshot.surround;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.opengl.Matrix;

/** A tilted ring of spectrum-driven prisms, projected through a perspective camera. */
final class SpectrumOrbit {
    private static final int COUNT = 48;
    private final float[] camera = new float[16], projection = new float[16], transform = new float[16];
    private final float[] point = new float[4], clip = new float[4];
    private final float[] x = new float[8], y = new float[8];
    private final float[] depth = new float[COUNT];
    private final int[] order = new int[COUNT];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path face = new Path();
    private float angle;

    void prepare(float width, float height, float phase, float stereo) {
        angle = phase * 0.13f;
        Matrix.setLookAtM(camera, 0, stereo * 0.2f, 1.75f, 4.8f, 0, 0, 0, 0, 1, 0);
        Matrix.perspectiveM(projection, 0, 40, width / height, 0.1f, 12);
        Matrix.multiplyMM(transform, 0, projection, 0, camera, 0);
        for (int i = 0; i < COUNT; i++) {
            double a = angle + i * Math.PI * 2 / COUNT;
            depth[i] = (float)(Math.sin(a) + stereo * 0.04 * Math.cos(a));
            int j = i;
            while (j > 0 && depth[order[j-1]] > depth[i]) {
                order[j] = order[j-1];
                j--;
            }
            order[j] = i;
        }
    }

    void draw(Canvas canvas, float w, float h, float[] bands, int tint, boolean front) {
        for (int i : order) {
            if ((depth[i] >= 0) != front) continue;
            float rad = Math.min(1.63f, 1.55f * w / h);
            float level = Math.min(1, bands[i < 24 ? i : 47-i]);
            for (int k = 0; k < 8; k++) {
                double a = angle + (i + ((k & 1) == 0 ? -0.37 : 0.37)) * Math.PI * 2 / COUNT;
                float radius = rad + ((k & 2) == 0 ? -0.045f : 0.045f);
                point[0] = (float)Math.cos(a) * radius;
                point[1] = -0.78f + ((k & 4) == 0 ? 0 : 0.07f + 0.46f * level);
                point[2] = (float)Math.sin(a) * radius;
                point[3] = 1;
                Matrix.multiplyMV(clip, 0, transform, 0, point, 0);
                x[k] = w * (1 + clip[0] / clip[3]) / 2;
                y[k] = h * (1 - clip[1] / clip[3]) / 2;
            }
            facet(canvas, 0, 1, 5, 4, tint, 0.5f);
            facet(canvas, 2, 3, 7, 6, tint, front ? 0.88f : 0.43f);
            int edge = x[0] < x[2] ? 0 : 1;
            facet(canvas, edge, edge+2, edge+6, edge+4, tint, 0.64f);
            facet(canvas, 4, 5, 7, 6, tint, 1.15f);
        }
    }

    private void facet(Canvas canvas, int a, int b, int c, int d, int tint, float light) {
        paint.setColor(Color.rgb(Math.min(255,(int)(Color.red(tint)*light)),
            Math.min(255,(int)(Color.green(tint)*light)), Math.min(255,(int)(Color.blue(tint)*light))));
        face.rewind();
        face.moveTo(x[a], y[a]); face.lineTo(x[b], y[b]); face.lineTo(x[c], y[c]); face.lineTo(x[d], y[d]); face.close();
        canvas.drawPath(face, paint);
    }
}
