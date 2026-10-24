package me.rerere.pet.ui.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 「标题 + 说明 + 右侧开关」行（参考图里绝大多数设置项都是这个形状）。
 *
 * 单向数据流约定（本次重构的核心，别破坏）：
 *   setChecked()  = 渲染，绝不回调；
 *   用户点击      = 唯一的意图来源，通过 OnToggledListener 抛给调用方；
 *   调用方拿不到"控件当前值"以外的状态，也必须先问 ViewModel 再 setChecked()。
 *   -> 所以这里【没有】任何 store/save 相关代码，也永远不会自己改数据。
 */
public final class ToggleRowView extends LinearLayout {

    public interface OnToggledListener {
        /** 用户真的点了（fromUser 恒为 true；保留参数是为了和 SeekRow 接口一致）。 */
        void onToggled(ToggleRowView row, boolean isChecked);
    }

    private final TextView title;
    private final TextView subtitle;
    private final SwitchView2 toggle;
    private OnToggledListener listener;

    public ToggleRowView(Context c) {
        this(c, null);
    }

    /** 必须提供 (Context, AttributeSet)，否则将来在 XML 里声明会 InflateException。 */
    public ToggleRowView(Context c, AttributeSet attrs) {
        super(c, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(0x00000000);
        int ph = UiTheme.dp(c, UiTheme.PAD_H);
        int pv = UiTheme.dp(c, UiTheme.PAD_V);
        setPadding(ph, pv, ph, pv);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);

        title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTextColor(UiTheme.TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        subtitle = new TextView(c);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        subtitle.setTextColor(UiTheme.TEXT_SECONDARY);
        subtitle.setLineSpacing(UiTheme.dp(c, 2f), 1f);
        subtitle.setVisibility(GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiTheme.dp(c, 4f);
        col.addView(subtitle, slp);

        addView(col, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        toggle = new SwitchView2(c);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = UiTheme.dp(c, 12f);
        addView(toggle, tlp);

        toggle.setOnCheckedChangeListener((v, checked) -> {
            if (listener != null) listener.onToggled(this, checked);
        });

        /* 整行可点：手指落在文字区也能切换（系统设置页的习惯）。
           开关自己会消费触摸，所以不会出现"点一次触发两次"。 */
        setOnClickListener(v -> toggle.toggle());
    }

    /* ---------------- 渲染 ---------------- */

    public ToggleRowView setTitle(String text) {
        title.setText(text);
        return this;
    }

    public ToggleRowView setSubtitle(String text) {
        if (text == null || text.isEmpty()) {
            subtitle.setVisibility(GONE);
        } else {
            subtitle.setText(text);
            subtitle.setVisibility(VISIBLE);
        }
        return this;
    }

    /** 渲染状态：不回调、不动画（列表重建时不希望一堆开关一起做动画）。 */
    public ToggleRowView setChecked(boolean on) {
        toggle.setChecked(on, false);
        return this;
    }

    public boolean isChecked() { return toggle.isChecked(); }

    /* ---------------- 意图 ---------------- */

    public ToggleRowView setOnToggledListener(OnToggledListener l) {
        this.listener = l;
        return this;
    }

    /** 整行禁用（例如模型没有配件时）。 */
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        toggle.setEnabled(enabled);
        setOnClickListener(enabled ? v -> toggle.toggle() : null);
        title.setTextColor(enabled ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY);
        subtitle.setTextColor(enabled ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_SECONDARY);
    }

    /** 想给副标题单独上色（比如"形状变化"这种提示色）。 */
    public void setSubtitleColor(int color) {
        subtitle.setTextColor(color);
    }
}
