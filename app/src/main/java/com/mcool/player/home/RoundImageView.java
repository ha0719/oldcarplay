package com.mcool.player.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * 圆角封面：按 centerCrop 铺满后裁成圆角（半径与卡片一致，20dp）。
 * 用 BitmapShader 画，边缘抗锯齿，不需要离屏缓冲；非位图图片退回普通绘制。
 */
public class RoundImageView extends ImageView {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final float radius;
    private Bitmap shaderBmp;
    private int sw = -1, sh = -1;

    public RoundImageView(Context c) { this(c, null); }

    public RoundImageView(Context c, AttributeSet a) {
        super(c, a);
        radius = 20 * c.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        int w = getWidth(), h = getHeight();
        if (!(d instanceof BitmapDrawable) || w == 0 || h == 0) {
            super.onDraw(canvas);
            return;
        }
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        if (b == null || b.isRecycled() || b.getWidth() == 0 || b.getHeight() == 0) {
            super.onDraw(canvas);
            return;
        }
        if (b != shaderBmp || w != sw || h != sh) {
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            matrix.reset();
            matrix.setScale(s, s);
            matrix.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            shaderBmp = b;
            sw = w;
            sh = h;
        }
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }
}
