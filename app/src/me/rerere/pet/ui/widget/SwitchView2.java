package me.rerere.pet.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 深青色自绘开关（规范见 UiTheme）。
 *
 * 为什么不 tint 系统 Switch（明确要求的外观）
 *   参考图的开关不是系统 Switch 的样子：
 *     ON  ：深青轨 + 大号白圆（几乎顶满轨道高度），靠右；
 *     OFF ：浅灰轨（带细描边）+ 小号深灰圆，靠左。
 *   也就是说圆点在两种状态下的直径不同 —— 这是系统 Switch 做不到的
 *   （系统 thumb 由 xml 固定尺寸，切状态不会改厚度）。
 *   所以只能自绘：半径、位置、颜色都跟随同一个 0..1 进度插值。
 *
 * 只负责"画"和"报告用户点击"，不读不写任何业务数据。
 *   数据方向永远是：调用方 setChecked() 渲染 -> 用户点了 -> 回调调用方。
 *   setChecked() 不会触发回调（否则又变成自激写入）。
 */
public final class SwitchView2 extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(SwitchView2 v, boolean isChecked);
    }

    private static final float TRACK_W_DP = 52f;
    private static final float TRACK_H_DP = 32f;
    /** 圆点与轨道边缘的留白。 */
    private static final float EDGE_DP = 2.5f;
    /** ON 圆点半径（相对轨道半高：16 - 2.5）。 */
    private static final float R_ON_DP = 13.5f;
    /** OFF 圆点半径 —— 明显比 ON 小，这是用户明确要的"打开变大、关闭变小"。 */
    private static final float R_OFF_DP = 9f;

    /* ★ 轨道 ON 色不能写成 static final —— 那样在类加载那一刻就把颜色固化了，
       用户改主题色后永远不生效（踩过）。改为绘制时现读 UiTheme.accent()。 */
    private static final int TRACK_OFF_FILL = 0xFFE8EAEA;
    private static final int TRACK_OFF_STROKE = 0xFFC6CACA;
    private static final int THUMB_ON = Color.WHITE;
    private static final int THUMB_OFF = 0xFF6E7276;

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    private boolean checked = false;
    /** 0 = 完全关闭，1 = 完全打开（动画进度）。 */
    private float t = 0f;
    private ValueAnimator anim;
    private OnCheckedChangeListener listener;
    private boolean pressed = false;

    public SwitchView2(Context c) {
        this(c, null);
    }

    /** 必须提供 (Context, AttributeSet)：将来若在 XML 里声明不会 InflateException。 */
    public SwitchView2(Context c, AttributeSet attrs) {
        super(c, attrs);
        me.rerere.pet.ui.theme.UiSettings.ensureLoaded(c);
        setClickable(true);
        setFocusable(true);
        pStroke.setStyle(Paint.Style.STROKE);
        pStroke.setStrokeWidth(UiTheme.dp(c, 1f));
    }

    /* ---------------- 状态 ---------------- */

    public boolean isChecked() { return checked; }

    /** 渲染用：设置状态，不回调。 */
    public void setChecked(boolean on) { setChecked(on, false); }

    public void setChecked(boolean on, boolean animate) {
        boolean changed = this.checked != on;
        this.checked = on;
        float target = on ? 1f : 0f;
        if (animate && changed) {
            animateTo(target);
        } else {
            stopAnim();
            t = target;
            invalidate();
        }
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    /** 用户点击触发的切换（会回调）。 */
    public void toggle() {
        setChecked(!checked, true);
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    private void animateTo(float target) {
        stopAnim();
        anim = ValueAnimator.ofFloat(t, target);
        anim.setDuration(200);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private void stopAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopAnim();
        super.onDetachedFromWindow();
    }

    /* ---------------- 测量 ---------------- */

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(UiTheme.dp(getContext(), TRACK_W_DP), wSpec);
        int h = resolveSize(UiTheme.dp(getContext(), TRACK_H_DP), hSpec);
        setMeasuredDimension(w, h);
    }

    /* ---------------- 绘制 ---------------- */

    /** 两个颜色按进度线性插值（ARGB）。 */
    private static int lerpColor(int a, int b, float f) {
        float g = Math.max(0f, Math.min(1f, f));
        int aa = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * g);
        int rr = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * g);
        int gg = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * g);
        int bb = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * g);
        return Color.argb(aa, rr, gg, bb);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float edge = UiTheme.dp(getContext(), EDGE_DP);
        /* 轨道：描边会往里占 0.5dp，所以内缩半个线宽 */
        float strokeInset = UiTheme.dp(getContext(), 0.5f);
        track.set(strokeInset, strokeInset, w - strokeInset, h - strokeInset);
        float radius = track.height() / 2f;

        /* 1) 轨道底色（OFF 填充 -> ON 深青） */
        pTrack.setStyle(Paint.Style.FILL);
        int trackOn = UiTheme.accent();
        pTrack.setColor(lerpColor(TRACK_OFF_FILL, trackOn, t));
        canvas.drawRoundRect(track, radius, radius, pTrack);

        /* 2) OFF 的细描边（ON 时渐隐，不然深青边上有一圈灰线） */
        pStroke.setColor(lerpColor(TRACK_OFF_STROKE, trackOn, t));
        canvas.drawRoundRect(track, radius, radius, pStroke);

        /* 3) 圆点：半径/位置/颜色都随 t 插值 —— 这就是"打开变大、关闭变小" */
        float rOff = UiTheme.dp(getContext(), R_OFF_DP);
        float rOn = UiTheme.dp(getContext(), R_ON_DP);
        float r = rOff + (rOn - rOff) * t;
        /* 两端留白统一按【ON 半径】算：
           这样 OFF 的小圆点离左边也会留出同样的视觉间距，
           不会贴在轨道边缘（实测"关闭时白点太靠左"）。 */
        float cxOff = edge + rOn;
        float cxOn = w - edge - rOn;
        float cx = cxOff + (cxOn - cxOff) * t;
        float cy = h / 2f;

        pThumb.setStyle(Paint.Style.FILL);
        pThumb.setColor(lerpColor(THUMB_OFF, THUMB_ON, t));
        /* 按下时圆点缩一点点做反馈（不影响规范里的两档大小） */
        float drawR = pressed ? r * 0.94f : r;
        canvas.drawCircle(cx, cy, drawR, pThumb);
    }

    /* ---------------- 触摸 ---------------- */

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                pressed = false;
                invalidate();
                if (e.getX() >= 0 && e.getX() <= getWidth()
                        && e.getY() >= 0 && e.getY() <= getHeight()) {
                    toggle();
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }
}
