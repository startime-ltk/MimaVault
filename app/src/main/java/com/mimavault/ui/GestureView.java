package com.mimavault.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 九宫格手势 View（与 PC 端一致：1-9 编号、行优先）
 * <p>
 * 1 2 3
 * 4 5 6
 * 7 8 9
 */
public class GestureView extends View {

    public interface Callback {
        void onPatternFinished(String seq);
    }

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Integer> selected = new ArrayList<>();
    private final float[][] centers = new float[9][2];
    private Callback callback;
    private float cellSize;
    private float currentX = -1;
    private float currentY = -1;

    public GestureView(Context context) {
        super(context);
        init();
    }

    public GestureView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(4f);
        circlePaint.setColor(0xFF6C5CE7);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFF6C5CE7);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(8f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(0x556C5CE7);
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void reset() {
        selected.clear();
        currentX = -1;
        currentY = -1;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cellSize = Math.min(w, h) / 3f;
        float offsetX = (w - cellSize * 3) / 2f;
        float offsetY = (h - cellSize * 3) / 2f;
        for (int i = 0; i < 9; i++) {
            int row = i / 3;
            int col = i % 3;
            centers[i][0] = offsetX + col * cellSize + cellSize / 2f;
            centers[i][1] = offsetY + row * cellSize + cellSize / 2f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < 9; i++) {
            boolean sel = selected.contains(i + 1);
            float r = cellSize * 0.22f;
            canvas.drawCircle(centers[i][0], centers[i][1], r, circlePaint);
            if (sel) {
                canvas.drawCircle(centers[i][0], centers[i][1], r * 0.45f, dotPaint);
            }
        }
        Path path = new Path();
        for (int i = 0; i < selected.size(); i++) {
            int idx = selected.get(i) - 1;
            if (i == 0) {
                path.moveTo(centers[idx][0], centers[idx][1]);
            } else {
                path.lineTo(centers[idx][0], centers[idx][1]);
            }
        }
        if (!selected.isEmpty() && currentX >= 0 && currentY >= 0) {
            int last = selected.get(selected.size() - 1) - 1;
            path.lineTo(currentX, currentY);
            path.moveTo(centers[last][0], centers[last][1]);
        }
        canvas.drawPath(path, linePaint);
    }

    private int hit(float x, float y) {
        for (int i = 0; i < 9; i++) {
            float dx = x - centers[i][0];
            float dy = y - centers[i][1];
            if (dx * dx + dy * dy <= (cellSize * 0.30f) * (cellSize * 0.30f)) {
                return i + 1;
            }
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                selected.clear();
                currentX = x;
                currentY = y;
                int first = hit(x, y);
                if (first != -1) {
                    selected.add(first);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                currentX = x;
                currentY = y;
                int cur = hit(x, y);
                if (cur != -1 && !selected.contains(cur)) {
                    selected.add(cur);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                currentX = -1;
                currentY = -1;
                invalidate();
                if (callback != null && selected.size() >= 4) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < selected.size(); i++) {
                        if (i > 0) {
                            sb.append(",");
                        }
                        sb.append(selected.get(i));
                    }
                    callback.onPatternFinished(sb.toString());
                } else if (callback != null) {
                    callback.onPatternFinished("");
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
