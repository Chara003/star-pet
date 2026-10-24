package me.rerere.pet.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.theme.UiSettings;
import me.rerere.pet.ui.theme.UiTheme;

/**
 * 底部悬浮胶囊导航栏。
 *
 * ══════════════════════════════════════════════════════════════
 * 视觉规格（几何数值都在下面的常量里）
 * ══════════════════════════════════════════════════════════════
 *   外层白胶囊   ：高 64dp、全圆角（半径 32dp）、纯白、投影 8dp
 *   页面左右边距 ：24dp（见 MainTabsActivity 的 LayoutParams）
 *   每个 Tab     ：等分，图标 24dp + 文字 11sp（水平居中、垂直居中）
 *   选中胶囊     ：高约 48dp，圆角 = 高度一半，紧贴图标+文字
 *
 *   ★ 关键比例（之前一直做不对的地方）：
 *     参考图里选中胶囊是【横向宽胶囊】（宽:高 ≈ 1.8:1），
 *     不是竖长圆。所以要"更矮 + 更宽"：
 *       白胶囊 64dp - 上下各 8dp = 48dp 高
 *       每格宽约 95dp - 左右各 5dp = 85dp 宽
 *
 * ══════════════════════════════════════════════════════════════
 * ★★★ 为什么 TabBar 比白胶囊高（上下各多 6dp）★★★
 * ══════════════════════════════════════════════════════════════
 *   设计上要求"按住胶囊时的膨胀可以超过底栏大小"。
 *   View.onDraw 的 canvas 会被裁到 View 自身边界 —— 只要白胶囊和 View
 *   一样高，放大后的选中胶囊就必然被削平。
 *   → 白胶囊 64dp，View 76dp，多出的 12dp 就是膨胀余量。
 *     选中胶囊 48dp × 1.16 = 55.7dp，仍在 View 内，视觉上"鼓出白胶囊"。
 *
 * ══════════════════════════════════════════════════════════════
 * ★★★ 一个曾导致【整机白屏】的死循环（务必保留现有写法）★★★
 * ══════════════════════════════════════════════════════════════
 *   旧代码：onSizeChanged -> setupBackdrop() -> post{ setElevation(); }
 *   setElevation 会改变 View 的绘制边界，触发父容器重新布局，
 *   又回调 onSizeChanged …… 主线程 100% 占用、post 队列无限增长，
 *   系统合成器跟着卡死 = "打开任何 App 都白屏"。
 *   现在：同步执行 + 尺寸去重（backdropW/H），且 elevation 只在值变化时设。
 *
 * ══════════════════════════════════════════════════════════════
 * 交互
 * ══════════════════════════════════════════════════════════════
 *   · 可点击、可左右拖动（底块跟手，松手吸附到最近一项）
 *   · 按住底块放大（比例可在「界面」页调），松手缩回
 *   · 阻尼跟随用 1-exp(-rate·dt)，与刷新率无关
 *   · 拖动的第一帧不 snap（否则就是实测到的"闪现"）
 */
public final class TabBar extends View {

    public interface OnTabSelected {
        void onTabSelected(int index);
    }

    /* ---------------- 视觉常量 ---------------- */

    /** 白胶囊视觉高度（附件规范 64dp）。 */
    public static final float CAPSULE_HEIGHT_DP = 64f;
    /**
     * 白胶囊之外的留白（上下各 18dp）。承担两个职责：
     *   ① 让"按住放大"能鼓出胶囊（设计上要求可到 200%）；
     *   ② ★ 给 elevation 阴影留出扩散空间 —— 之前只留 6dp，
     *      而 8dp elevation 的阴影要向外扩散约 20dp，
     *      超出的部分被 View 边界裁掉，看起来就是"阴影被削平"。
     *      View 边界之外的绘制内容会被父容器裁（除非父容器 clipChildren=false，
     *      barWrap 已经设了），但 View 自身 canvas 仍然只到 View 边界，
     *      所以必须在 View 内部留够空间。
     */
    private static final float INFLATE_MARGIN_DP = 18f;
    /** View 总高 = 胶囊 + 上下留白。 */
    private static final float VIEW_HEIGHT_DP = CAPSULE_HEIGHT_DP + INFLATE_MARGIN_DP * 2f;

    /** 页面左右边距（外层容器 margin，24dp）。 */
    public static final float PAGE_MARGIN_DP = 24f;

    /** 底栏距屏幕底部的距离（px，由 UiSettings 提供，见 bottomMarginPx）。 */
    public static int bottomMarginPx(Context c) {
        return UiTheme.dp(c, UiSettings.barBottomMargin());
    }
    /** Item 区左右内边距 —— 必须与 buildBar 里 row.setPadding 一致。 */
    public static final float ROW_PAD_DP = 8f;

    /** 选中胶囊相对每格的内缩：横向 5dp、纵向 8dp → 约 85×48dp。 */
    private static final float IND_PAD_X_DP = 5f;
    private static final float IND_PAD_Y_DP = 8f;

    /** 判定为"拖动"的位移阈值。 */
    private static final float DRAG_SLOP_DP = 6f;
    /** follow=1 时的跟随速率（1/s）。 */
    private static final float FOLLOW_RATE_SCALE = 30f;

    private static final int COLOR_OFF = 0xFF3A3A3A;
    private static final int INDICATOR = 0xFFE9ECEC;

    /* ---------------- 状态 ---------------- */

    private String[] labels = new String[0];
    private TextView[] texts = new TextView[0];
    private IconView[] iconViews = new IconView[0];

    private int selected = 0;
    private float indicatorPos = 0f;
    private float dragTarget = 0f;
    private float pressScale = 1f;

    private ValueAnimator posAnim;
    private ValueAnimator scaleAnim;

    private boolean dragging = false;
    private boolean pressed = false;
    private float downX = 0f;
    private int downIndex = 0;
    private long lastFrameMs = 0L;
    private boolean followLoopActive = false;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pInd = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rBg = new RectF();
    private final RectF rInd = new RectF();

    private OnTabSelected listener;

    /* ---------------- 构造 ---------------- */

    public TabBar(Context c) { this(c, null); }

    public TabBar(Context c, AttributeSet attrs) {
        super(c, attrs);
        setClickable(true);
        UiSettings.ensureLoaded(c);
        pBg.setColor(UiTheme.CARD);
        pInd.setColor(INDICATOR);
        setupBackdrop();
    }

    /* ---------------- 背景 / 投影 ---------------- */

    private int backdropW = -1;
    private int backdropH = -1;

    /**
     * 配置投影与 outline。
     * ★ 见类注释里那个"整机白屏"的死循环，改动前务必读完。
     */
    private void setupBackdrop() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        /* 尺寸没变就不重做 —— 防自激循环的核心 */
        if (w == backdropW && h == backdropH) return;
        backdropW = w;
        backdropH = h;

        float vpad = UiTheme.dp(getContext(), INFLATE_MARGIN_DP);
        float capsuleH = h - vpad * 2f;
        final int radius = (int) (capsuleH / 2f);

        /* outline 跟着"白胶囊"的形状走（elevation 阴影靠它）。
           ★ 水平方向不内缩（胶囊左右顶到 View 边缘），只上下各让 vpad。 */
        BlurCompat.applyCapsuleOutlineV(this, (int) vpad, radius);

        /* 投影：系统绘制，四周发散，不受 View 边界限制 */
        int targetElev = UiTheme.dp(getContext(), 8f);
        if (getElevation() != targetElev) setElevation(targetElev);
    }

    /** 设置改了之后刷新（界面页调完调用）。 */
    public void refreshBackdrop() {
        backdropW = -1;
        backdropH = -1;
        setupBackdrop();
        paintLabels();
        invalidate();
    }

    /** 强制重绘（改完主题色后调用）。 */
    public void refreshTheme() {
        pInd.setColor(INDICATOR);
        paintLabels();
        invalidate();
    }

    /* ---------------- 配置 ---------------- */

    public void setOnTabSelected(OnTabSelected l) { this.listener = l; }

    void setLabelViews(String[] labels, TextView[] texts, IconView[] icons) {
        this.labels = labels == null ? new String[0] : labels;
        this.texts = texts == null ? new TextView[0] : texts;
        this.iconViews = icons == null ? new IconView[0] : icons;
        if (selected >= this.labels.length) selected = 0;
        indicatorPos = selected;
        dragTarget = selected;
        paintLabels();
        invalidate();
    }

    public int getSelected() { return selected; }

    /** 程序化切换：只改 UI，不回调（避免调用方在回调里再调回来形成环）。 */
    public void setSelected(int index) { applySelected(index, false); }

    public void setSelected(int index, boolean animate) { applySelected(index, animate); }

    private void applySelected(int index, boolean animate) {
        if (labels.length == 0) return;
        int idx = Math.max(0, Math.min(labels.length - 1, index));
        selected = idx;
        dragTarget = idx;
        if (animate) animateSpringPos(idx); else {
            stopPosAnim();
            indicatorPos = idx;
            invalidate();
        }
        paintLabels();
    }

    /* ---------------- 动画 ---------------- */

    /** 松手吸附：带轻微过冲（过冲量由用户在「界面」页调）。 */
    private void animateSpringPos(float target) {
        stopPosAnim();
        final float start = indicatorPos;
        float dist = Math.abs(target - start);
        int dur = (int) Math.min(400, 170 + dist * 100);
        posAnim = ValueAnimator.ofFloat(0f, 1f);
        posAnim.setDuration(dur);
        posAnim.setInterpolator(new OvershootInterpolator(UiSettings.overshoot()));
        final ValueAnimator self = posAnim;
        posAnim.addUpdateListener(a -> {
            if (posAnim != self) return;
            float k = (float) a.getAnimatedValue();
            indicatorPos = start + (target - start) * k;
            invalidate();
        });
        posAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                if (posAnim != self) return;
                /* 结束时钉死目标位置（浮点误差会让它差一丁点） */
                indicatorPos = target;
                invalidate();
            }
        });
        posAnim.start();
    }

    private void animateScale(float target) {
        if (scaleAnim != null) { scaleAnim.cancel(); scaleAnim = null; }
        scaleAnim = ValueAnimator.ofFloat(pressScale, target);
        scaleAnim.setDuration(target > pressScale ? 130 : 200);
        scaleAnim.setInterpolator(new DecelerateInterpolator(1.4f));
        scaleAnim.addUpdateListener(a -> {
            pressScale = (float) a.getAnimatedValue();
            invalidate();
        });
        scaleAnim.start();
    }

    private void stopPosAnim() {
        if (posAnim != null) { posAnim.cancel(); posAnim = null; }
    }

    @Override protected void onDetachedFromWindow() {
        stopPosAnim();
        if (scaleAnim != null) { scaleAnim.cancel(); scaleAnim = null; }
        removeCallbacks(followLoop);
        followLoopActive = false;
        super.onDetachedFromWindow();
    }

    /* ---------------- 阻尼跟随（帧率无关） ---------------- */

    private final Runnable followLoop = new Runnable() {
        @Override public void run() {
            if (!pressed) { followLoopActive = false; return; }
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameMs == 0L ? 0.016f
                    : Math.min(0.05f, (now - lastFrameMs) / 1000f);
            lastFrameMs = now;
            float rate = UiSettings.follow() * FOLLOW_RATE_SCALE;
            float k = 1f - (float) Math.exp(-rate * dt);
            indicatorPos += (dragTarget - indicatorPos) * k;
            invalidate();
            postOnAnimation(this);
        }
    };

    /* ---------------- 测量 ---------------- */

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int h = UiTheme.dp(getContext(), VIEW_HEIGHT_DP);
        int w = MeasureSpec.getSize(wSpec);
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.EXACTLY) w = UiTheme.dp(getContext(), 360f);
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        /* ★ 必须同步 + 去重（见类注释里那个死循环） */
        setupBackdrop();
    }

    private float rowPadPx() { return UiTheme.dp(getContext(), ROW_PAD_DP); }

    private float cellWidth() {
        int n = Math.max(1, labels.length);
        return Math.max(1f, (getWidth() - rowPadPx() * 2f) / n);
    }

    /** 白胶囊的矩形（上下各留 INFLATE_MARGIN，左右顶满）。 */
    private void capsuleRect(RectF out) {
        float vpad = UiTheme.dp(getContext(), INFLATE_MARGIN_DP);
        out.set(0f, vpad, getWidth(), getHeight() - vpad);
    }

    /* ---------------- 绘制 ---------------- */

    @Override protected void onDraw(Canvas canvas) {
        capsuleRect(rBg);
        float capR = rBg.height() / 2f;

        /* 1) 白胶囊底 */
        pBg.setColor(UiTheme.CARD);
        canvas.drawRoundRect(rBg, capR, capR, pBg);

        if (labels.length == 0) return;

        /* 2) 选中胶囊：与真实 Item 同一套坐标 */
        float cw = cellWidth();
        float pad = rowPadPx();
        float cellLeft = pad + cw * indicatorPos;
        /* ★ 选中胶囊大小可调（「界面」页滑块）。
           这里用"缩放 + 居中"而不是改内缩量：内缩量改大会把胶囊压得很窄，
           而缩放能同时改变宽高，且始终以格子中心为轴，不会偏移。 */
        float capCx = cellLeft + cw / 2f;
        float capCy = rBg.centerY();
        float fullW = Math.max(1f, cw - UiTheme.dp(getContext(), IND_PAD_X_DP) * 2f);
        float fullH = Math.max(1f, rBg.height() - UiTheme.dp(getContext(), IND_PAD_Y_DP) * 2f);
        float k = UiSettings.barCapsuleScale();
        float halfW = fullW * k / 2f;
        float halfH = fullH * k / 2f;
        rInd.set(capCx - halfW, capCy - halfH, capCx + halfW, capCy + halfH);

        pInd.setColor(INDICATOR);
        float ir = rInd.height() / 2f;

        if (pressScale != 1f) {
            /* 按住放大：以中心为轴缩放。
               ★ 能超出白胶囊是因为 View 本身比胶囊高（上下各 6dp），
                 不是靠 canvas.clipRect（那个无效，canvas 已被 View 边界裁住）。 */
            float cx = rInd.centerX(), cy = rInd.centerY();
            canvas.save();
            canvas.scale(pressScale, pressScale, cx, cy);
            canvas.drawRoundRect(rInd, ir, ir, pInd);
            canvas.restore();
        } else {
            canvas.drawRoundRect(rInd, ir, ir, pInd);
        }

        /* 3) 文字/图标由上层 row 负责绘制（不能在这里画，会被 Z 序盖住） */
    }

    /* ---------------- 触摸 ---------------- */

    /** 手指 x -> 连续位置（0..n-1 浮点）。 */
    private float posFromX(float x) {
        int n = Math.max(1, labels.length);
        float cw = cellWidth();
        float pad = rowPadPx();
        float raw = (x - pad) / cw - 0.5f;
        if (raw < 0f) raw = 0f;
        if (raw > n - 1) raw = n - 1;
        return raw;
    }

    private int indexFromX(float x) {
        return Math.round(posFromX(x));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (labels.length == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downIndex = selected;
                pressed = true;
                dragging = false;
                dragTarget = posFromX(e.getX());
                stopPosAnim();
                animateScale(UiSettings.pressScale());
                /* 按住即开始跟随：手指不动，胶囊也会自己滑过来 */
                if (!followLoopActive) {
                    followLoopActive = true;
                    lastFrameMs = 0L;
                    postOnAnimation(followLoop);
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!pressed) return false;
                if (!dragging && Math.abs(e.getX() - downX)
                        > UiTheme.dp(getContext(), DRAG_SLOP_DP)) {
                    dragging = true;
                }
                dragTarget = posFromX(e.getX());
                if (!followLoopActive) {
                    followLoopActive = true;
                    lastFrameMs = 0L;
                    postOnAnimation(followLoop);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (!pressed) return false;
                pressed = false;
                followLoopActive = false;
                removeCallbacks(followLoop);
                animateScale(1f);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);

                int target = dragging ? Math.round(dragTarget) : indexFromX(e.getX());
                target = Math.max(0, Math.min(labels.length - 1, target));
                boolean changed = target != downIndex;
                selected = target;
                dragTarget = target;
                animateSpringPos(target);
                paintLabels();
                dragging = false;
                if (changed && listener != null) listener.onTabSelected(target);
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                if (!pressed) return false;
                pressed = false;
                followLoopActive = false;
                removeCallbacks(followLoop);
                animateScale(1f);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                dragging = false;
                animateSpringPos(selected);
                return true;
            }

            default:
                return super.onTouchEvent(e);
        }
    }

    /* ---------------- 文字/图标着色 ---------------- */

    private void paintLabels() {
        int on = UiTheme.accent();
        for (int i = 0; i < texts.length; i++) {
            boolean sel = i == selected;
            texts[i].setTextColor(sel ? on : COLOR_OFF);
            texts[i].setTypeface(Typeface.DEFAULT, sel ? Typeface.BOLD : Typeface.NORMAL);
            if (i < iconViews.length) iconViews[i].setIconColor(sel ? on : COLOR_OFF);
        }
    }

    /* ================= 组合式用法 ================= */

    /**
     * 生成"白胶囊 + 图标文字"的整体。
     *
     *   FrameLayout bar = TabBar.buildBar(c, labels, icons, idx -> switchPage(idx));
     *   // 自己给 LayoutParams（gravity=BOTTOM，左右 24dp，底 24dp）后 addView
     *
     * Item 本身不 clickable —— 触摸全部交给下面的 TabBar，否则拖动会被 Item 吃掉。
     */
    public static FrameLayout buildBar(Context c, String[] labels, int[] icons,
                                       final OnTabSelected cb) {
        UiSettings.ensureLoaded(c);
        FrameLayout wrap = new FrameLayout(c);
        /* 放大后的胶囊要能画出 wrap 边界之外 */
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);

        final TabBar bar = new TabBar(c);
        wrap.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        int n = labels == null ? 0 : labels.length;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClickable(false);
        int padH = UiTheme.dp(c, ROW_PAD_DP);
        row.setPadding(padH, 0, padH, 0);

        TextView[] texts = new TextView[n];
        IconView[] iconViews = new IconView[n];

        for (int i = 0; i < n; i++) {
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(false);
            item.setFocusable(false);

            IconView ic = new IconView(c);
            if (icons != null && i < icons.length) ic.setType(icons[i]);
            item.addView(ic, new LinearLayout.LayoutParams(
                    UiTheme.dp(c, 24f), UiTheme.dp(c, 24f)));
            iconViews[i] = ic;

            TextView t = new TextView(c);
            t.setText(labels[i]);
            t.setTextSize(11f);
            t.setClickable(false);
            t.setFocusable(false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = UiTheme.dp(c, 2f);
            item.addView(t, tlp);
            texts[i] = t;

            /* 高度 = 白胶囊高度（内容垂直居中在胶囊里） */
            row.addView(item, new LinearLayout.LayoutParams(
                    0, UiTheme.dp(c, CAPSULE_HEIGHT_DP), 1f));
        }

        /* ★ Z 序（踩过"字全都没了"）：TabBar 有 elevation 会盖住文字，
           给 row 更高 elevation 顶回最上层；row 无背景，不会多画阴影。
           同时上下各留 INFLATE_MARGIN，让文字和胶囊垂直对齐。 */
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        int vpad = UiTheme.dp(c, INFLATE_MARGIN_DP);
        rowLp.topMargin = vpad;
        rowLp.bottomMargin = vpad;
        wrap.addView(row, rowLp);
        row.setElevation(UiTheme.dp(c, 12f));

        bar.setOnTabSelected(cb);
        bar.setLabelViews(labels, texts, iconViews);
        return wrap;
    }
}
