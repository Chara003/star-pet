package me.rerere.pet.ui.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 卡片上方的分组小标题（参考图里的「消息显示」「基础设定」等）。
 *
 * 为什么不用系统 22sp 的 category header：
 *   参考图字号很小（13sp）、颜色是压暗的深青（不是纯黑也不是纯灰），
 *   左边与卡片正文对齐（16dp 页边距 + 20dp 行内边距）。
 */
public final class SectionHeader extends LinearLayout {

    private final TextView label;

    public SectionHeader(Context c) {
        this(c, null);
    }

    public SectionHeader(Context c, AttributeSet attrs) {
        super(c, attrs);
        setOrientation(VERTICAL);
        label = new TextView(c);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTextColor(UiTheme.darken(UiTheme.accent(), 0.78f));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.START);
        addView(label);
        applySpacing(18f, 8f);
    }

    public SectionHeader setLabel(String text) {
        label.setText(text);
        return this;
    }

    /** 想调上下留白时用。 */
    public void setSpacing(float topDp, float bottomDp) {
        applySpacing(topDp, bottomDp);
    }

    private void applySpacing(float topDp, float bottomDp) {
        int h = UiTheme.dp(getContext(), UiTheme.PAD_PAGE);
        setPadding(h, UiTheme.dp(getContext(), topDp), h, UiTheme.dp(getContext(), bottomDp));
    }
}
