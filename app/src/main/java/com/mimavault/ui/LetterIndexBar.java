package com.mimavault.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.mimavault.R;

import java.util.HashSet;
import java.util.Set;

/**
 * 右侧竖向字母索引条：A-Z + #。
 * 支持点击 / 按住滑动：命中字母变化时回调 onLetterSelected；
 * 手指抬起时回调 onTouchEnd（用于恢复列表滚动联动）。
 * 列表滚动时可通过 setActiveLetter 联动高亮当前可见组字母。
 */
public class LetterIndexBar extends View {

    public interface Listener {
        /** 触摸滑动 / 点击命中某字母（可能不存在该组，调用方自行就近跳转） */
        void onLetterSelected(String letter);

        /** 手指抬起，恢复列表滚动联动 */
        void onTouchEnd();
    }

    private static final int LETTER_COUNT = 27; // A-Z + #
    /** 可视字母带宽度（居中绘制）；外层其余宽度为透明热区，用于扩大手指命中面积 */
    private static final float VISUAL_WIDTH_DP = 28f;
    private final String[] letters = new String[LETTER_COUNT];

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Set<String> available = new HashSet<>();
    private String activeLetter = null;
    /** 手指按下时锁定显示的高亮字母（不受列表滚动干扰），抬起后回落到 activeLetter */
    private String shownActiveLetter = null;

    private Listener listener;
    private boolean touching = false;
    private int lastTouchIndex = -1;

    private int colorEnabled;
    private int colorDisabled;
    private int colorActiveText;
    private int colorActiveBg;

    public LetterIndexBar(Context context) {
        this(context, null);
    }

    public LetterIndexBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        for (int i = 0; i < 26; i++) {
            letters[i] = String.valueOf((char) ('A' + i));
        }
        letters[26] = "#";
        colorEnabled = ContextCompat.getColor(context, R.color.primary);
        colorDisabled = ContextCompat.getColor(context, R.color.text_hint);
        colorActiveText = ContextCompat.getColor(context, R.color.white);
        colorActiveBg = ContextCompat.getColor(context, R.color.primary);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(13));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 设置本列表实际存在的分组字母（用于深色显示）；null/空表示全部灰显 */
    public void setAvailableLetters(Set<String> letters) {
        available.clear();
        if (letters != null) {
            available.addAll(letters);
        }
        invalidate();
    }

    /** 外部（列表滚动）同步当前应高亮的字母；null 表示清除 */
    public void setActiveLetter(@Nullable String letter) {
        this.activeLetter = letter;
        if (!touching) {
            shownActiveLetter = letter;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = getHeight();
        if (h <= 0) {
            return;
        }
        float w = getWidth();
        // 可视带宽度：不超过 VISUAL_WIDTH_DP，居中绘制；外层仍属热区（不画背景）
        float visualW = Math.min(w, dp(VISUAL_WIDTH_DP));
        float drawLeft = (w - visualW) / 2f;
        float slot = h / LETTER_COUNT;
        float cx = drawLeft + visualW / 2f;
        float circleRadius = Math.min(slot * 0.40f, visualW * 0.40f);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baselineShift = (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < LETTER_COUNT; i++) {
            float cy = slot * i + slot / 2f;
            String letter = letters[i];
            if (letter.equals(shownActiveLetter)) {
                activeBgPaint.setColor(colorActiveBg);
                canvas.drawCircle(cx, cy, circleRadius, activeBgPaint);
                textPaint.setColor(colorActiveText);
            } else if (available.contains(letter)) {
                textPaint.setColor(colorEnabled);
            } else {
                textPaint.setColor(colorDisabled);
            }
            canvas.drawText(letter, cx, cy - baselineShift, textPaint);
        }
    }

    private int indexOfLetter(String letter) {
        if (letter == null) {
            return -1;
        }
        for (int i = 0; i < LETTER_COUNT; i++) {
            if (letters[i].equals(letter)) {
                return i;
            }
        }
        return -1;
    }

    private void handleTouch(float y) {
        float h = getHeight();
        if (h <= 0) {
            return;
        }
        int idx = (int) (y / h * LETTER_COUNT);
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= LETTER_COUNT) {
            idx = LETTER_COUNT - 1;
        }
        if (idx != lastTouchIndex) {
            lastTouchIndex = idx;
            String letter = letters[idx];
            shownActiveLetter = letter;
            invalidate();
            if (listener != null) {
                listener.onLetterSelected(letter);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                lastTouchIndex = -1;
                handleTouch(event.getY());
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                handleTouch(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                lastTouchIndex = -1;
                if (listener != null) {
                    listener.onTouchEnd();
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                // 抬起后高亮回落为列表当前组
                shownActiveLetter = activeLetter;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(int v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
