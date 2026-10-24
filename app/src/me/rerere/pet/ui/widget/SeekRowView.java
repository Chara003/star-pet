package me.rerere.pet.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 「标题 + 右侧数值 + 深青细轨道滑块」行。
 *
 * 外观按用户参考图：
 *   · 轨道是一条细圆角条（10dp），左段深青、右段浅青灰；
 *   · 轨道里有小圆点（已填充段是白点，未填充段是淡青点）；
 *   · 手柄不是圆点，是一根 4dp 宽的竖条，比轨道高（26dp vs 10dp）。
 *   系统 SeekBar 的默认样子（圆点 + Material 主题色）完全不是这个形状，
 *   所以轨道与手柄都自绘。
 *
 * 单向数据流（和 ToggleRowView 同一套约定）：
 *   setValue()  = 渲染，不回调；
 *   拖动/点击   = 唯一的意图来源，通过 OnSeekListener 抛给调用方。
 *   ★ 初始化阶段绝不因为 setValue 触发一次保存（旧版就是这样把配置刷成默认值的）。
 */
public final class SeekRowView extends LinearLayout {

    public interface OnSeekListener {
        /** 用户操作触发（拖动中会连续回调，方便实时预览）。 */
        void onSeek(SeekRowView row, float value);

        /** 手指抬起（一次交互结束，适合在这里落库）。 */
        void onSeekFinished(SeekRowView row, float value);
    }

    public interface Formatter {
        String format(float value);
    }

    private final TextView title;
    private final TextView subtitle;
    private final TextView valueLabel;
    private final Bar bar;

    private OnSeekListener listener;

    public SeekRowView(Context c) {
        this(c, null);
    }

    /** 必须提供 (Context, AttributeSet)，否则将来在 XML 里声明会 InflateException。 */
    public SeekRowView(Context c, AttributeSet attrs) {
        super(c, attrs);
        me.rerere.pet.ui.theme.UiSettings.ensureLoaded(c);
        setOrientation(VERTICAL);
        int ph = UiTheme.dp(c, UiTheme.PAD_H);
        int pv = UiTheme.dp(c, UiTheme.PAD_V);
        setPadding(ph, pv, ph, pv);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTextColor(UiTheme.TEXT_PRIMARY);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        valueLabel = new TextView(c);
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        valueLabel.setTextColor(UiTheme.TEXT_PRIMARY);
        valueLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        valueLabel.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = UiTheme.dp(c, 12f);
        valueLabel.setMinWidth(UiTheme.dp(c, 52f));
        head.addView(valueLabel, vlp);
        addView(head);

        subtitle = new TextView(c);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        subtitle.setTextColor(UiTheme.TEXT_SECONDARY);
        subtitle.setLineSpacing(UiTheme.dp(c, 2f), 1f);
        subtitle.setVisibility(GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiTheme.dp(c, 4f);
        addView(subtitle, slp);

        bar = new Bar(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiTheme.dp(c, 12f);
        addView(bar, blp);

        bar.setOnBarListener(new Bar.Listener() {
            @Override public void onMove(float fraction) {
                valueLabel.setText(formatLabel());
                if (listener != null) listener.onSeek(SeekRowView.this, bar.value());
            }
            @Override public void onCommit(float fraction) {
                valueLabel.setText(formatLabel());
                if (listener != null) listener.onSeekFinished(SeekRowView.this, bar.value());
            }
        });

        setValueLabelInternal();
    }

    /* ---------------- 渲染 ---------------- */

    public SeekRowView setTitle(String text) {
        title.setText(text);
        return this;
    }

    public SeekRowView setSubtitle(String text) {
        if (text == null || text.isEmpty()) {
            subtitle.setVisibility(GONE);
        } else {
            subtitle.setText(text);
            subtitle.setVisibility(VISIBLE);
        }
        return this;
    }

    /** 量程（默认 0..1）。 */
    public SeekRowView setRange(float min, float max) {
        bar.setRange(min, max);
        setValueLabelInternal();
        return this;
    }

    /** 显示格式（默认按量程取整 + "%" 由调用方决定）。 */
    public SeekRowView setFormatter(Formatter f) {
        bar.formatter = f;
        setValueLabelInternal();
        /* ★ 必须在下一帧再写一次：某些情况下（构造刚完成、尚未 measure）
           TextView 还没有最终宽度，第一次 setText 的内容可能被后续
           seek() 内部的 setValue 覆盖掉。加一次 post 兜底最稳。 */
        valueLabel.post(this::setValueLabelInternal);
        return this;
    }

    /** 渲染值：不回调。 */
    public SeekRowView setValue(float v) {
        bar.setValueInternal(v);
        setValueLabelInternal();
        return this;
    }

    public float getValue() { return bar.value(); }

    /**
     * 禁用 / 启用：整行变暗且不再响应触摸。
     * ★ 只是 setEnabled(false) 不够 —— 轨道是自绘 View，会照样吃事件；
     *   必须让 Bar 自己拒绝触摸，否则用户以为"拖了没反应"。
     */
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        bar.setInteractable(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }

    /** 用户操作回调（唯一意图入口）。 */
    public SeekRowView setOnSeekListener(OnSeekListener l) {
        this.listener = l;
        return this;
    }

    private void setValueLabelInternal() {
        valueLabel.setText(formatLabel());
    }

    private String formatLabel() {
        return bar.formatter == null
                ? String.valueOf(Math.round(bar.value()))
                : bar.formatter.format(bar.value());
    }

    /* ================= 自绘轨道 ================= */

    /**
     * 细轨道 + 竖条手柄。
     * 高度 34dp：轨道 10dp 居中，手柄 26dp，上下各留 4dp 余量。
     */
    static final class Bar extends View {

        interface Listener {
            void onMove(float fraction);
            void onCommit(float fraction);
        }

        private static final float HEIGHT_DP = 34f;
        private static final float TRACK_H_DP = 10f;
        private static final float HANDLE_W_DP = 4f;
        private static final float HANDLE_H_DP = 26f;
        private static final float DOT_R_DP = 1.6f;
        private static final float DOT_STEP_DP = 24f;

        private static final int TRACK_BG = 0xFFE1EAEA;
        private static final int DOT_ON = 0xFFFFFFFF;
        private static final int DOT_OFF = 0xFF6F8E8E;

        private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rTrack = new RectF();
        private final RectF rHandle = new RectF();

        private float min = 0f, max = 1f, value = 0f;
        /** 禁用时不消费触摸（自绘 View 的 setEnabled 不会自动生效）。 */
        private boolean interactable = true;
        private Listener listener;
        Formatter formatter;
        private boolean dragging = false;
        /** 已判定为"横向拖滑块"：独占事件，祖先不许截。 */
        private boolean locked = false;
        private float downX = 0f;
        private float downY = 0f;

        Bar(Context c) {
            super(c);
            me.rerere.pet.ui.theme.UiSettings.ensureLoaded(c);
            setClickable(true);
            /* 现读，不缓存：用户改主题色后滑块要立刻跟着变 */
            pFill.setColor(UiTheme.accent());
            pBg.setColor(TRACK_BG);
            pHandle.setColor(UiTheme.accent());
        }

        void setListener(Listener l) { this.listener = l; }
        void setOnBarListener(Listener l) { this.listener = l; }

        /** 禁用：不再消费触摸（否则用户会拖一个"看起来灰但会动"的滑块）。 */
        void setInteractable(boolean on) {
            interactable = on;
            setClickable(on);
            invalidate();
        }

        void setRange(float mn, float mx) {
            if (mx <= mn) mx = mn + 1f;
            this.min = mn;
            this.max = mx;
            clamp();
            invalidate();
        }

        /** 渲染设值：不回调。 */
        void setValueInternal(float v) {
            this.value = v;
            clamp();
            invalidate();
        }

        float value() { return value; }

        private void clamp() {
            if (value < min) value = min;
            if (value > max) value = max;
        }

        private float fraction() {
            return (max - min) <= 0f ? 0f : (value - min) / (max - min);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int h = resolveSize(UiTheme.dp(getContext(), HEIGHT_DP), hSpec);
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, h);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            int accent = UiTheme.accent();
            pFill.setColor(accent);
            pHandle.setColor(accent);
            float trackH = UiTheme.dp(getContext(), TRACK_H_DP);
            float top = (h - trackH) / 2f;
            float trackR = trackH / 2f;

            rTrack.set(0, top, w, top + trackH);

            /* 1) 整条浅色底 */
            canvas.drawRoundRect(rTrack, trackR, trackR, pBg);

            /* 2) 左段填充（到手柄中心） */
            float f = Math.max(0f, Math.min(1f, fraction()));
            float hx = w * f;
            if (hx > 0.5f) {
                RectF fill = new RectF(0, top, Math.max(hx, trackR * 2f), top + trackH);
                canvas.drawRoundRect(fill, trackR, trackR, pFill);
            }

            /* 3) 轨道里的小圆点：已填充段白点，未填充段淡青点 */
            float dotR = UiTheme.dp(getContext(), DOT_R_DP);
            float step = UiTheme.dp(getContext(), DOT_STEP_DP);
            float cy = h / 2f;
            for (float x = step * 0.42f; x < w - dotR; x += step) {
                pDot.setColor(x < hx ? DOT_ON : DOT_OFF);
                pDot.setAlpha(x < hx ? 255 : 110);
                canvas.drawCircle(x, cy, dotR, pDot);
            }
            pDot.setAlpha(255);

            /* 4) 竖条手柄（盖住轨道，两端圆头） */
            float hw = UiTheme.dp(getContext(), HANDLE_W_DP);
            float hh = UiTheme.dp(getContext(), HANDLE_H_DP);
            float left = Math.max(hw / 2f, Math.min(w - hw / 2f, hx)) - hw / 2f;
            rHandle.set(left, (h - hh) / 2f, left + hw, (h + hh) / 2f);
            float hr = hw / 2f;
            canvas.drawRoundRect(rHandle, hr, hr, pHandle);
        }

        /* ---------------- 触摸 ---------------- */

        private void applyTouch(float x) {
            float w = getWidth();
            if (w <= 0) return;
            float f = Math.max(0f, Math.min(1f, x / w));
            value = min + (max - min) * f;
            clamp();
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!interactable) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    locked = false;
                    downX = e.getX();
                    downY = e.getY();
                    /* 先【不】抢事件：还不知道用户是要横拖滑块还是竖滚页面。 */
                    applyTouch(e.getX());
                    if (listener != null) listener.onMove(fraction());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    float dx = Math.abs(e.getX() - downX);
                    float dy = Math.abs(e.getY() - downY);
                    float slop = UiTheme.dp(getContext(), 6f);
                    if (!locked) {
                        /* 方向判定：横向为主 -> 这是拖滑块，从此独占事件；
                           纵向为主 -> 用户想滚页面，放手让 ScrollView 截走。 */
                        if (dx > slop && dx >= dy) {
                            locked = true;
                            ViewParent p = getParent();
                            if (p != null) p.requestDisallowInterceptTouchEvent(true);
                        } else if (dy > slop && dy > dx) {
                            dragging = false;
                            return false;
                        } else {
                            return true;
                        }
                    }
                    applyTouch(e.getX());
                    if (listener != null) listener.onMove(fraction());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    dragging = false;
                    if (locked) {
                        ViewParent p2 = getParent();
                        if (p2 != null) p2.requestDisallowInterceptTouchEvent(false);
                        applyTouch(e.getX());
                    }
                    locked = false;
                    /* 点一下轨道（没有横向拖动）也要提交，这是"点哪跳哪"的预期行为 */
                    if (listener != null) listener.onCommit(fraction());
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }
}
