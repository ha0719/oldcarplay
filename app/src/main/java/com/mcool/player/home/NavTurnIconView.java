package com.mcool.player.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 轻量 2D Canvas 转向箭头控件，兼容 Android 4.4（API 19），
 * 按高德车机版标准广播 ICON 枚举绘制 CarPlay 风格白色引导图标。
 */
public class NavTurnIconView extends View {

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private int iconType = 9; // 默认直行

    public NavTurnIconView(Context context) {
        super(context);
        init();
    }

    public NavTurnIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NavTurnIconView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setColor(0xFFFFFFFF);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setIconType(int type) {
        if (this.iconType != type) {
            this.iconType = type;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        if (size <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = size * 0.36f;
        float sw = size * 0.11f;
        strokePaint.setStrokeWidth(sw);

        canvas.save();
        switch (iconType) {
            case 2: // 左转
                drawTurn90(canvas, cx, cy, r, sw, true);
                break;
            case 3: // 右转
                drawTurn90(canvas, cx, cy, r, sw, false);
                break;
            case 4: // 左前方
                drawDiagonal(canvas, cx, cy, r, sw, -45f);
                break;
            case 5: // 右前方
                drawDiagonal(canvas, cx, cy, r, sw, 45f);
                break;
            case 6: // 左后方
                drawSharpTurn(canvas, cx, cy, r, sw, true);
                break;
            case 7: // 右后方
                drawSharpTurn(canvas, cx, cy, r, sw, false);
                break;
            case 8: // 左转掉头
                drawUTurn(canvas, cx, cy, r, sw, true);
                break;
            case 19: // 右转掉头
                drawUTurn(canvas, cx, cy, r, sw, false);
                break;
            case 11: // 进入环岛
            case 12: // 驶出环岛
            case 17: // 环岛（右侧通行）
            case 18:
                drawRoundabout(canvas, cx, cy, r, sw);
                break;
            case 10: // 途经点
            case 15: // 目的地
                drawDestination(canvas, cx, cy, r, sw);
                break;
            case 9:  // 直行
            case 20: // 顺行
            default:
                drawStraight(canvas, cx, cy, r, sw);
                break;
        }
        canvas.restore();
    }

    private void drawStraight(Canvas canvas, float cx, float cy, float r, float sw) {
        canvas.drawLine(cx, cy + r * 0.9f, cx, cy - r * 0.45f, strokePaint);
        drawArrowHead(canvas, cx, cy - r * 0.95f, -90f, sw * 2.1f);
    }

    private void drawTurn90(Canvas canvas, float cx, float cy, float r, float sw, boolean left) {
        float dir = left ? -1f : 1f;
        float startX = cx - dir * r * 0.25f;
        float cornerY = cy - r * 0.2f;
        path.reset();
        path.moveTo(startX, cy + r * 0.9f);
        path.lineTo(startX, cornerY + r * 0.25f);
        path.quadTo(startX, cornerY, startX + dir * r * 0.25f, cornerY);
        path.lineTo(cx + dir * r * 0.42f, cornerY);
        canvas.drawPath(path, strokePaint);
        drawArrowHead(canvas, cx + dir * r * 0.92f, cornerY, left ? 180f : 0f, sw * 2.1f);
    }

    private void drawDiagonal(Canvas canvas, float cx, float cy, float r, float sw, float angleDeg) {
        float dir = angleDeg < 0 ? -1f : 1f;
        float startX = cx - dir * r * 0.2f;
        path.reset();
        path.moveTo(startX, cy + r * 0.9f);
        path.lineTo(startX, cy + r * 0.1f);
        float endX = cx + dir * r * 0.45f;
        float endY = cy - r * 0.5f;
        path.lineTo(endX, endY);
        canvas.drawPath(path, strokePaint);
        drawArrowHead(canvas, endX + dir * r * 0.35f, endY - r * 0.35f, angleDeg - 90f, sw * 2.0f);
    }

    private void drawSharpTurn(Canvas canvas, float cx, float cy, float r, float sw, boolean left) {
        float dir = left ? -1f : 1f;
        float startX = cx - dir * r * 0.35f;
        path.reset();
        path.moveTo(startX, cy + r * 0.9f);
        path.lineTo(startX, cy - r * 0.45f);
        path.quadTo(startX, cy - r * 0.8f, startX + dir * r * 0.3f, cy - r * 0.45f);
        float endX = cx + dir * r * 0.45f;
        float endY = cy + r * 0.25f;
        path.lineTo(endX, endY);
        canvas.drawPath(path, strokePaint);
        drawArrowHead(canvas, endX + dir * r * 0.28f, endY + r * 0.28f, left ? 135f : 45f, sw * 2.0f);
    }

    private void drawUTurn(Canvas canvas, float cx, float cy, float r, float sw, boolean left) {
        float dir = left ? -1f : 1f;
        float x1 = cx - dir * r * 0.4f;
        float x2 = cx + dir * r * 0.4f;
        float topY = cy - r * 0.35f;
        path.reset();
        path.moveTo(x1, cy + r * 0.9f);
        path.lineTo(x1, topY);
        oval.set(Math.min(x1, x2), topY - r * 0.45f, Math.max(x1, x2), topY + r * 0.45f);
        path.arcTo(oval, left ? 0f : 180f, left ? -180f : 180f);
        path.lineTo(x2, cy + r * 0.35f);
        canvas.drawPath(path, strokePaint);
        drawArrowHead(canvas, x2, cy + r * 0.85f, 90f, sw * 2.0f);
    }

    private void drawRoundabout(Canvas canvas, float cx, float cy, float r, float sw) {
        float ringR = r * 0.48f;
        canvas.drawCircle(cx, cy - r * 0.05f, ringR, strokePaint);
        canvas.drawLine(cx, cy + r * 0.9f, cx, cy - r * 0.05f + ringR, strokePaint);
        canvas.drawLine(cx + ringR, cy - r * 0.05f, cx + r * 0.72f, cy - r * 0.05f, strokePaint);
        drawArrowHead(canvas, cx + r * 1.05f, cy - r * 0.05f, 0f, sw * 1.8f);
    }

    private void drawDestination(Canvas canvas, float cx, float cy, float r, float sw) {
        float pinR = r * 0.48f;
        float pinCy = cy - r * 0.25f;
        path.reset();
        path.moveTo(cx, cy + r * 0.9f);
        path.lineTo(cx - pinR * 0.88f, pinCy + pinR * 0.42f);
        path.lineTo(cx + pinR * 0.88f, pinCy + pinR * 0.42f);
        path.close();
        canvas.drawCircle(cx, pinCy, pinR, fillPaint);
        canvas.drawPath(path, fillPaint);

        int oldColor = fillPaint.getColor();
        fillPaint.setColor(0xFF1E7E4E);
        canvas.drawCircle(cx, pinCy, pinR * 0.42f, fillPaint);
        fillPaint.setColor(oldColor);
    }

    private void drawArrowHead(Canvas canvas, float tipX, float tipY, float angleDeg, float len) {
        canvas.save();
        canvas.translate(tipX, tipY);
        canvas.rotate(angleDeg);
        path.reset();
        path.moveTo(0f, 0f);
        path.lineTo(-len, -len * 0.68f);
        path.lineTo(-len, len * 0.68f);
        path.close();
        canvas.drawPath(path, fillPaint);
        canvas.restore();
    }
}
