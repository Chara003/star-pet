package me.rerere.pet.ui.theme;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Typeface;

/**
 * UI 设计系统常量 + 小工具（浅灰底 + 纯白卡片 + 深青强调色）。
 *
 * 下面是每个数值的由来，别随手改：
 *   · 页面 #F2F2F7、卡片纯白：靠底色差区分层次，不用阴影。
 *     大 elevation 在 WebGL 桌宠同屏时掉帧明显，所以一律不用。
 *   · 圆角 24dp：卡片/按钮/输入框统一，是本项目识别度最高的特征。
 *   · 强调色 #2A6B6B（深青）：开关 ON、滑块、主按钮。
 *     旧的 #4C6EF5 亮蓝是草稿期的；colors.xml 里保留旧 key 只为不破坏老代码。
 *   · 分割线不要系统 divider：用 0.5dp 的 #EFEFF2。系统 divider 带默认 inset
 *     和颜色，在纯白卡片上显得脏。
 *
 * 本类只放常量 + 纯函数，不持有 Context、不缓存 View。
 */
public final class UiTheme {

    private UiTheme() {}

    /* ---------------- 颜色 ---------------- */

    /** 页面背景（所有设置页统一）。 */
    public static final int BG = 0xFFF2F2F7;
    /** 卡片背景。 */
    public static final int CARD = 0xFFFFFFFF;
    /**
     * 强调色默认值。
     * ★ 实际生效的颜色请用 {@link #accent()} —— 它读用户在「界面」页里改的值。
     *   这个常量只用于"没加载过配置时的兜底"和预设色板比对。
     */
    public static final int DEFAULT_ACCENT = 0xFF2A6B6B;

    /** 当前生效的强调色（运行时可变，别用 static final 缓存它）。 */
    public static int accent() {
        return me.rerere.pet.ui.theme.UiSettings.accent();
    }

    /** 强调色按下/加深态（自动按亮度算，用户换色后也跟着变）。 */
    public static int accentPressed() {
        return darken(accent(), 0.84f);
    }

    /** 卡片圆角（运行时可变，用户可在「界面」页里调）。 */
    public static float radius() {
        return me.rerere.pet.ui.theme.UiSettings.radius();
    }

    /** 颜色变暗（用于按下态/次级强调）。 */
    public static int darken(int color, float factor) {
        int a = android.graphics.Color.alpha(color);
        int r = (int) (android.graphics.Color.red(color) * factor);
        int g = (int) (android.graphics.Color.green(color) * factor);
        int b = (int) (android.graphics.Color.blue(color) * factor);
        r = Math.min(255, Math.max(0, r));
        g = Math.min(255, Math.max(0, g));
        b = Math.min(255, Math.max(0, b));
        return android.graphics.Color.argb(a, r, g, b);
    }
    /** 关闭态 / 禁用。 */
    public static final int OFF = 0xFFE5E5EA;
    /** 关闭态描边（开关轨道、圆角按钮细边）。 */
    public static final int OFF_STROKE = 0xFFC8CCCC;
    /** 主标题。 */
    public static final int TEXT_PRIMARY = 0xFF1A1A1A;
    /** 副标题 / 说明。 */
    public static final int TEXT_SECONDARY = 0xFF7A7A7A;
    /** 极细分割线（0.5dp）。 */
    public static final int DIVIDER = 0xFFEFEFF2;
    /** 分组标题（卡片上方的小字）。 */
    public static final int SECTION = 0xFF5F7C7C;
    /** 次级按钮底（浅灰）。 */
    public static final int SUBTLE = 0xFFEFEFF2;
    /** 信息块底（只读说明）。 */
    public static final int INFO_BG = 0xFFF4F6F6;

    /* ---------------- 尺寸（dp） ---------------- */

    /** 卡片圆角默认值（实际用 radius()）。 */
    public static final float DEFAULT_RADIUS = 24f;
    /** 卡片内边距：水平 20dp / 垂直 16dp（行内使用）。 */
    public static final float PAD_H = 20f;
    public static final float PAD_V = 16f;
    /** 卡片之间的间距。 */
    public static final float GAP_CARD = 12f;
    /** 页面左右边距。 */
    public static final float PAD_PAGE = 16f;

    /* ---------------- 换算 ---------------- */

    public static int dp(Context c, float v) {
        if (c == null) return (int) v;
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()) + 0.5f);
    }

    /** 极细线宽度：至少 1px，避免 0 高线完全不画。 */
    public static int hairline(Context c) {
        return Math.max(1, dp(c, 0.5f));
    }

    public static int sp(Context c, float v) {
        if (c == null) return (int) v;
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics()) + 0.5f);
    }

    /* ---------------- 形状 ---------------- */

    /** 纯色圆角矩形（dp 值直接来自调用方时用这个）。 */
    public static GradientDrawable roundPx(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        return roundPx(color, dp(c, radiusDp));
    }

    /** 圆角 + 描边。 */
    public static GradientDrawable roundStroke(Context c, int fill, int stroke,
                                               float strokeDp, float radiusDp) {
        GradientDrawable d = round(c, fill, radiusDp);
        d.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return d;
    }

    /** 卡片背景：纯白 + 24dp 圆角，无阴影。 */
    public static GradientDrawable cardBg(Context c) {
        return round(c, CARD, radius());
    }

    /**
     * 递归刷新一个 View 树里所有卡片的圆角（用户在「界面」页拖滑块时实时调用）。
     *
     * ★ 为什么用"递归找 CardView2"而不是重建页面：
     *   重建会替换掉正在被拖动的那一行本身，手势立刻中断（滑块卡住不动）。
     *   遍历一次几十个 View 的开销可以忽略，而且不打断任何交互。
     */
    public static void refreshRadiusRecursive(View root) {
        if (root == null) return;
        if (root instanceof me.rerere.pet.ui.widget.CardView2) {
            ((me.rerere.pet.ui.widget.CardView2) root).refreshRadius();
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                refreshRadiusRecursive(g.getChildAt(i));
            }
        }
    }

    /* ---------------- 文字 ---------------- */

    /** 主标题（行标题）。 */
    public static TextView title(Context c, String text) {
        return text(c, text, 17f, TEXT_PRIMARY, true);
    }

    /** 副标题 / 说明。 */
    public static TextView subtitle(Context c, String text) {
        return text(c, text, 13f, TEXT_SECONDARY, false);
    }

    /** 页内大标题。 */
    public static TextView pageTitle(Context c, String text) {
        return text(c, text, 28f, TEXT_PRIMARY, true);
    }

    /** 通用文字构造（不额外加 padding，由调用方布局）。 */
    public static TextView text(Context c, String text, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /* ---------------- 布局小件 ---------------- */

    /** 一个纯色细线 View（MATCH_PARENT 宽）。 */
    public static View line(Context c, int color, int heightPx) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }

    /**
     * 主按钮（深青底、白字、24dp 圆角、48dp 高）。
     * 用 GradientDrawable 而不是 backgroundTint：minSdk 24 上 backgroundTint
     * 会把圆角一起涂掉，且系统 Button 自带最小高度/内边距。
     */
    public static TextView primaryButton(Context c, String text) {
        return button(c, text, accent(), 0xFFFFFFFF);
    }

    /** 次级按钮（浅灰底、深字）。 */
    public static TextView secondaryButton(Context c, String text) {
        return button(c, text, SUBTLE, TEXT_PRIMARY);
    }

    private static TextView button(Context c, String text, int fill, int textColor) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(c, fill, radius()));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(c, 48));
        int ph = dp(c, 20), pv = dp(c, 13);
        b.setPadding(ph, pv, ph, pv);
        return b;
    }
}
