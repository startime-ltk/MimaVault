package com.mimavault.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 人脸取景引导框：中央竖椭圆比例方框 + 四角括号。
 */
public class FaceOverlayView extends View {

    private final Paint framePaint;
    private final RectF box = new RectF();

    public FaceOverlayView(Context context) {
        this(context, null);
    }

    public FaceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setColor(Color.parseColor("#33FFFFFF"));
        framePaint.setStrokeWidth(dp(2));
        framePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float bw = Math.min(w * 0.72f, dp(300));
        float bh = bw * 1.35f;
        float cx = w / 2f;
        float cy = h / 2f - dp(20);
        box.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float c = dp(24);
        float r = dp(6);
        drawCorner(canvas, box.left, box.top, c, 1, 1, r);
        drawCorner(canvas, box.right, box.top, c, -1, 1, r);
        drawCorner(canvas, box.left, box.bottom, c, 1, -1, r);
        drawCorner(canvas, box.right, box.bottom, c, -1, -1, r);
    }

    private void drawCorner(Canvas canvas, float x, float y, float len, int sx, int sy, float r) {
        canvas.drawLine(x, y + sy * r, x, y + sy * len, framePaint);
        canvas.drawLine(x + sx * r, y, x + sx * len, y, framePaint);
    }
}
