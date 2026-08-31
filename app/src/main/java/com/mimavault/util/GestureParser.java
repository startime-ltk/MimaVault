package com.mimavault.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * 九宫格手势工具，与 PC 端 GesturePanel 编号规则一致
 * 编号 = row * 3 + col + 1（左上 → 右下）：
 *   1 2 3
 *   4 5 6
 *   7 8 9
 * 序列以逗号分隔文本存储（如 "1,4,7,8,9"），不存储重复点
 */
public final class GestureParser {

    private GestureParser() {
    }

    /** 解析序列文本，兼容中文逗号/空格/顿号及紧凑数字串（如 14789）；仅接受 1~9 数字 */
    public static List<Integer> parse(String seq) {
        List<Integer> out = new ArrayList<>();
        if (seq == null || seq.trim().isEmpty()) {
            return out;
        }
        for (String part : seq.split("[,，、\\s]+")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            try {
                int n = Integer.parseInt(part);
                if (n >= 1 && n <= 9) {
                    out.add(n);
                }
                continue;
            } catch (NumberFormatException ignored) {
            }
            // 紧凑数字串（如 "14789"）：逐字符解析
            for (char c : part.toCharArray()) {
                if (c >= '1' && c <= '9') {
                    out.add(c - '0');
                }
            }
        }
        return out;
    }

    /** 序列化（去重、按原始顺序） */
    public static String serialize(List<Integer> points) {
        StringBuilder sb = new StringBuilder();
        List<Integer> seen = new ArrayList<>();
        for (int p : points) {
            if (p >= 1 && p <= 9 && !seen.contains(p)) {
                seen.add(p);
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 点位 → 中心坐标（viewSize 为九宫格视图边长，9 格等分） */
    public static float[] centerOf(int point, float viewSize) {
        int idx = point - 1;
        float cell = viewSize / 3f;
        float x = (idx % 3) * cell + cell / 2f;
        float y = (idx / 3) * cell + cell / 2f;
        return new float[]{x, y};
    }

    /** 坐标 → 点位（若落在九宫格范围内） */
    public static int pointAt(float x, float y, float viewSize) {
        float cell = viewSize / 3f;
        int col = (int) (x / cell);
        int row = (int) (y / cell);
        if (col < 0 || col > 2 || row < 0 || row > 2) {
            return -1;
        }
        return row * 3 + col + 1;
    }

    /** 序列 → 中文描述，如 "1,4,7,8,9" → "已设置：5 点连线"；空序列返回 null */
    public static String describe(String seq) {
        List<Integer> points = parse(seq);
        if (points.isEmpty()) {
            return null;
        }
        return "已设置：" + points.size() + " 点连线";
    }

    /** 序列是否有效（≥4 个不重复点） */
    public static boolean valid(String seq) {
        List<Integer> points = parse(seq);
        return points.size() >= 4;
    }

    /** 绘制九宫格手势静态图（详情页展示用），与录入视图同风格 */
    public static Bitmap render(String seq, int width, int height) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float view = Math.min(width, height);
        float cell = view / 3f;
        float offsetX = (width - view) / 2f;
        float offsetY = (height - view) / 2f;

        float[][] centers = new float[9][2];
        for (int i = 0; i < 9; i++) {
            centers[i][0] = offsetX + (i % 3) * cell + cell / 2f;
            centers[i][1] = offsetY + (i / 3) * cell + cell / 2f;
        }

        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setStyle(Paint.Style.STROKE);
        circle.setStrokeWidth(Math.max(3f, cell * 0.06f));
        circle.setColor(0xFF6C5CE7);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFF6C5CE7);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(5f, cell * 0.10f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(0x556C5CE7);

        List<Integer> points = parse(seq);
        if (points.size() >= 2) {
            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < points.size(); i++) {
                int idx = points.get(i) - 1;
                if (i == 0) {
                    path.moveTo(centers[idx][0], centers[idx][1]);
                } else {
                    path.lineTo(centers[idx][0], centers[idx][1]);
                }
            }
            canvas.drawPath(path, line);
        }

        float r = cell * 0.20f;
        for (int i = 0; i < 9; i++) {
            canvas.drawCircle(centers[i][0], centers[i][1], r, circle);
        }
        for (Integer p : points) {
            int idx = p - 1;
            canvas.drawCircle(centers[idx][0], centers[idx][1], r * 0.45f, dot);
        }
        return bmp;
    }
}
