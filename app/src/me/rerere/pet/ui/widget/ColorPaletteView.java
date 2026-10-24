package me.rerere.pet.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 同心环调色板：所有圆点【边长一致、间距一致】，圆大小统一。
 *
 * ══════════════════════════════════════════════════════════════
 * 间距公式（这是本次重写的重点）
 * ══════════════════════════════════════════════════════════════
 * 上一版用的是「黄金角螺旋 + 半径按 √t 展开」，结果是：
 *   中心挤成一团、外圈稀稀拉拉 —— 因为面积均分不等于间距均分
 *   （面积 ∝ r²，半径线性增长时周长也跟着涨，点自然越来越疏）。
 *
 * 现在改成同心环，并用公式把间距钉死：
 *
 *   d        = 归一化圆心距（所有相邻点的目标间距，环内弧长与环间距都等于它）
 *   r_k      = k · d              第 k 环的半径
 *   n_k      = round(2π k)        第 k 环的点数  ← 让弧长 2π·r_k/n_k ≈ d
 *   dotR     = 0.34 · d           所有点统一半径
 *   画布约束  RINGS·d + dotR = 0.5  →  d = 0.5 / (RINGS + 0.34)
 *
 * 这样环内相邻点的弦长、以及相邻两环之间的径向距离都 ≈ d，
 * 视觉上就是均匀密铺（近似蜂窝），不会再有的地方挤、有的地方空。
 *
 * ══════════════════════════════════════════════════════════════
 * 颜色映射
 * ══════════════════════════════════════════════════════════════
 *   角度 -> 色相（绕一圈走完 360°）
 *   环号 -> 饱和度（最内圈接近灰白，最外圈最鲜艳）
 *   全局明暗滑杆 -> HSV 的 V
 *
 * ★ 只负责"选色"，不写任何配置。选中结果通过 OnColorPicked 抛给调用方。
 */
public final class ColorPaletteView extends View {

    public interface OnColorPicked {
        void onPicked(int color);
    }

    /** 环数（不含中心点）。7 环 + 中心 → 1+6+13+19+25+31+38 = 133 个点。 */
    private static final int RINGS = 7;
    /** 点半径 / 圆心距。0.34 让点与点之间留出约 32% 的缝隙。 */
    private static final float DOT_R_RATIO = 0.34f;

    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 点的归一化坐标（0..1，相对控件短边）与颜色参数。 */
    private float[] nx;
    private float[] ny;
    private float[] hue;
    private float[] sat;
    /** 统一半径（归一化）。 */
    private float dotRNorm;

    /** 整体明度（用户拖滑杆调整）。 */
    private float value = 0.80f;
    private int selected = -1;
    private int selectedColor = UiTheme.DEFAULT_ACCENT;

    private OnColorPicked listener;

    public ColorPaletteView(Context c) { this(c, null); }

    public ColorPaletteView(Context c, AttributeSet attrs) {
        super(c, attrs);
        setClickable(true);
        buildRings();
    }

    /**
     * 按同心环公式生成所有点。
     * 只在这里算一次，之后 onDraw 只做乘法 —— 133 个点每帧重算三角函数没必要。
     */
    private void buildRings() {
        /* 圆心距：最外环半径 + 点半径 正好等于 0.5（贴边不溢出） */
        float d = 0.5f / (RINGS + DOT_R_RATIO);
        dotRNorm = DOT_R_RATIO * d;

        int total = 1;
        for (int k = 1; k <= RINGS; k++) total += ringCount(k);
        nx = new float[total];
        ny = new float[total];
        hue = new float[total];
        sat = new float[total];

        int i = 0;
        /* 中心点：饱和度最低（近灰），色相无所谓 */
        nx[i] = 0.5f;
        ny[i] = 0.5f;
        hue[i] = 0f;
        sat[i] = 0.06f;
        i++;

        for (int k = 1; k <= RINGS; k++) {
            int n = ringCount(k);
            float radius = k * d;
            float satK = 0.10f + 0.90f * ((float) k / RINGS);
            /* ★ 相邻环错开半格：如果每环都从角度 0 开始，相邻两环的点会
               排在同一条射线上，形成突兀的"辐条"，且某些地方点会几乎相切、
               另一些地方缝隙很大。偏移半格后近似蜂窝密铺，观感均匀得多。 */
            double stagger = (k % 2 == 0) ? 0.0 : Math.PI / n;
            for (int j = 0; j < n; j++) {
                double angle = 2.0 * Math.PI * j / n + stagger;
                /* 屏幕坐标 y 向下：angle 0 = 右、90 = 下。
                   色相跟着角度走 -> 右边红、下边黄绿、左边青、上边紫。 */
                nx[i] = 0.5f + radius * (float) Math.cos(angle);
                ny[i] = 0.5f + radius * (float) Math.sin(angle);
                float deg = (float) Math.toDegrees(angle);
                hue[i] = deg % 360f;
                sat[i] = satK;
                i++;
            }
        }
    }

    /** 第 k 环的点数：让环内弧长 ≈ 圆心距 d。 */
    private static int ringCount(int k) {
        return Math.max(1, Math.round((float) (2 * Math.PI * k)));
    }

    public void setOnColorPicked(OnColorPicked l) { this.listener = l; }

    /** 明度（0..1），影响所有点的配色。 */
    public void setValue(float v) {
        value = Math.max(0.35f, Math.min(1f, v));
        invalidate();
    }

    public float getValue() { return value; }

    public int currentColor() { return selectedColor; }

    private int colorOf(int i) {
        return Color.HSVToColor(new float[]{hue[i], sat[i], value});
    }

    /** 用外部颜色反选最接近的点（进入页面时定位当前强调色）。 */
    public void selectClosestTo(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < nx.length; i++) {
            double dh = Math.abs(hue[i] - hsv[0]);
            if (dh > 180) dh = 360 - dh;
            double ds = Math.abs(sat[i] - hsv[1]);
            /* 色相权重高：颜色"种类"比"浓淡"更影响观感 */
            double d = (dh / 180.0) * 2.0 + ds;
            if (d < bestD) { bestD = d; best = i; }
        }
        selected = best;
        selectedColor = color;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int wSize = MeasureSpec.getSize(wSpec);
        int hSize = MeasureSpec.getSize(hSpec);
        int modeW = MeasureSpec.getMode(wSpec);
        int modeH = MeasureSpec.getMode(hSpec);

        int size;
        if (modeW == MeasureSpec.EXACTLY) size = wSize;
        else if (modeH == MeasureSpec.EXACTLY) size = hSize;
        else size = UiTheme.dp(getContext(), 300f);

        /* 高度受限时以更小的那个为准（保证是正圆、且不超出容器） */
        if (modeH == MeasureSpec.EXACTLY && hSize < size) size = hSize;
        if (modeH == MeasureSpec.AT_MOST && hSize < size) size = hSize;
        if (modeW == MeasureSpec.AT_MOST && wSize < size) size = wSize;

        setMeasuredDimension(size, size);
    }

    private float unit() { return Math.min(getWidth(), getHeight()); }

    @Override protected void onDraw(Canvas canvas) {
        float u = unit();
        if (u <= 0 || nx == null) return;
        float offX = (getWidth() - u) / 2f;
        float offY = (getHeight() - u) / 2f;
        float dotR = dotRNorm * u;

        /* 中心一个小灰点做视觉锚点（下面第一圈点画在它外面） */
        pCenter.setColor(Color.argb(22, 128, 128, 128));
        canvas.drawCircle(offX + u / 2f, offY + u / 2f, dotR * 0.75f, pCenter);

        for (int i = 0; i < nx.length; i++) {
            float cx = offX + nx[i] * u;
            float cy = offY + ny[i] * u;

            if (i == selected) {
                /* 选中：白色内衬环 + 深色外描边，任何底色上都清晰 */
                pRing.setStyle(Paint.Style.STROKE);
                pRing.setStrokeWidth(Math.max(UiTheme.dp(getContext(), 2f), dotR * 0.30f));
                pRing.setColor(0xFFFFFFFF);
                canvas.drawCircle(cx, cy, dotR * 1.45f, pRing);
                pRing.setColor(0xFF1A1A1A);
                pRing.setStrokeWidth(Math.max(1f, UiTheme.dp(getContext(), 1.2f)));
                canvas.drawCircle(cx, cy, dotR * 1.62f, pRing);
                pRing.setStyle(Paint.Style.FILL);
            }

            pDot.setColor(colorOf(i));
            canvas.drawCircle(cx, cy, dotR, pDot);
        }
    }

    /* ---------------- 触摸 ---------------- */

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
            return true;
        }
        float u = unit();
        if (u <= 0 || nx == null) return true;
        float offX = (getWidth() - u) / 2f;
        float offY = (getHeight() - u) / 2f;
        float px = (e.getX() - offX) / u;
        float py = (e.getY() - offY) / u;

        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < nx.length; i++) {
            float dx = nx[i] - px, dy = ny[i] - py;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD) { bestD = d2; best = i; }
        }
        if (best < 0) return true;

        /* 命中阈值：圆心距的 0.55 倍（约等于点的半径 + 一点余量）。
           超出就不响应，避免在空白缝隙里乱滑时颜色乱跳。 */
        float thr = dotRNorm * 1.6f;
        if (bestD > thr * thr) return true;

        if (best != selected || action == MotionEvent.ACTION_DOWN) {
            selected = best;
            selectedColor = colorOf(best);
            invalidate();
            if (listener != null) listener.onPicked(selectedColor);
        }
        return true;
    }
}
