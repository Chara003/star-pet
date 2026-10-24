package me.rerere.pet.ui.widget;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 24dp 圆角纯白卡片（替代旧的 glass() 玻璃卡片）。
 *
 * 为什么不用 elevation：
 *   用户明确禁止大阴影（同屏还有 WebGL 桌宠，阴影通道 + 离屏合成会掉帧）。
 *   层次靠"页面 #F2F2F7 底 + 卡片纯白 + 12dp 间距"就够。
 *
 * 为什么卡片自身不留水平内边距：
 *   参考图里分割线是通栏（贯通整张卡片）的。如果卡片自己加 20dp 内边距，
 *   分割线就会被缩进去，视觉上像"浮在中间的一条短线"。
 *   -> 内边距交给每一行（行自己 padding 20/16），分割线由本卡片加，
 *      自然就是通栏的。
 */
public final class CardView2 extends LinearLayout {

    public CardView2(Context c) {
        this(c, null);
    }

    public CardView2(Context c, AttributeSet attrs) {
        super(c, attrs);
        setOrientation(VERTICAL);
        me.rerere.pet.ui.theme.UiSettings.ensureLoaded(c);
        GradientDrawable bg = UiTheme.cardBg(c);
        setBackground(bg);
        /* 圆角裁剪：子 View（如整行高亮底色）不会溢出到圆角外 */
        setClipToOutline(true);
    }

    /** 追加一行（自动在两行之间插 0.5dp 分割线）。 */
    public void addRow(View row) {
        if (getChildCount() > 0) addDivider();
        addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 追加一行，且不插分割线（用于自己带背景的块）。 */
    public void addRowNoDivider(View row) {
        addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 0.5dp 通栏分割线。 */
    public void addDivider() {
        View d = new View(getContext());
        d.setBackgroundColor(UiTheme.DIVIDER);
        addView(d, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.hairline(getContext())));
    }

    /** 竖直空白，用来在卡片内分区（不画线）。 */
    public void addGap(float dp) {
        View v = new View(getContext());
        addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(getContext(), dp)));
    }

    /**
     * 重新套用当前圆角。
     * ★ 用户的"卡片圆角"滑块要【实时】看到变化，但重建整个页面会断掉滑块手势，
     *   所以拖动过程中只更新已有卡片的背景，不重建控件树。
     */
    public void refreshRadius() {
        setBackground(UiTheme.cardBg(getContext()));
        invalidate();
    }

    /** 一次性给整张卡片加统一内边距（少数"整块内容"卡片用）。 */
    public void setCardPadding(float hDp, float vDp) {
        int h = UiTheme.dp(getContext(), hDp);
        int v = UiTheme.dp(getContext(), vDp);
        setPadding(h, v, h, v);
    }
}
