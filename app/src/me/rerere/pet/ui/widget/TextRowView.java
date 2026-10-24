package me.rerere.pet.ui.widget;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 「标题 + 说明 + 输入框」行。
 *
 * 为什么单独做一个组件：旧页面的输入框有三种写法（系统下划线、自定义
 * GradientDrawable、无背景），颜色也不统一（#4C6EF5 / #F2F2F7 混用）。
 * 这里统一成「浅灰圆角块 + 15sp 正文」，与滑块轨道、胶囊同一套色板。
 *
 * 数据约定（和别的行不同，这里必然要读控件内容）：
 *   · setText() 是**渲染**，只用在初始化 / 切换对象时；
 *   · 读取内容只发生在用户显式点「保存」时（由调用方 getUserInput()），
 *     绝不在 onCreate/onResume 里读控件回写配置。
 */
public final class TextRowView extends LinearLayout {

    private final TextView title;
    private final TextView subtitle;
    private final EditText field;

    public TextRowView(Context c) {
        this(c, null);
    }

    /** 必须提供 (Context, AttributeSet)，否则将来在 XML 里声明会 InflateException。 */
    public TextRowView(Context c, AttributeSet attrs) {
        super(c, attrs);
        me.rerere.pet.ui.theme.UiSettings.ensureLoaded(c);
        setOrientation(VERTICAL);
        int ph = UiTheme.dp(c, UiTheme.PAD_H);
        int pv = UiTheme.dp(c, UiTheme.PAD_V);
        setPadding(ph, pv, ph, pv);

        title = UiTheme.title(c, "");
        addView(title);

        subtitle = UiTheme.subtitle(c, "");
        subtitle.setVisibility(GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiTheme.dp(c, 4f);
        addView(subtitle, slp);

        field = new EditText(c);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        field.setTextColor(UiTheme.TEXT_PRIMARY);
        field.setHintTextColor(0xFFA8A8AD);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setBackground(UiTheme.round(c, UiTheme.INFO_BG, 14f));
        int fh = UiTheme.dp(c, 14f);
        field.setPadding(fh, fh, fh, fh);
        field.setLineSpacing(UiTheme.dp(c, 3f), 1f);
        field.setMinLines(3);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        flp.topMargin = UiTheme.dp(c, 10f);
        addView(field, flp);
    }

    /* ---------------- 渲染 ---------------- */

    public TextRowView setTitle(String text) {
        title.setText(text);
        return this;
    }

    public TextRowView setSubtitle(String text) {
        if (text == null || text.isEmpty()) {
            subtitle.setVisibility(GONE);
        } else {
            subtitle.setText(text);
            subtitle.setVisibility(VISIBLE);
        }
        return this;
    }

    public TextRowView setText(String text) {
        field.setText(text == null ? "" : text);
        return this;
    }

    public TextRowView setHint(String hint) {
        field.setHint(hint == null ? "" : hint);
        return this;
    }

    /** 最少行数（默认 3）。输入框会随内容自动变高，不会内部滚动。 */
    public TextRowView setMinLines(int n) {
        field.setMinLines(Math.max(1, n));
        return this;
    }

    /** 单行模式（名字这类短输入）。 */
    public TextRowView setSingleLine(boolean single) {
        if (single) {
            field.setSingleLine(true);
            field.setMinLines(1);
            field.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return this;
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        field.setEnabled(enabled);
        field.setAlpha(enabled ? 1f : 0.5f);
        title.setTextColor(enabled ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY);
        subtitle.setAlpha(enabled ? 1f : 0.6f);
    }

    /* ---------------- 用户输入 ---------------- */

    /** 只在用户点「保存」这类明确动作时调用。 */
    public String getUserInput() {
        return field.getText() == null ? "" : field.getText().toString();
    }

    public EditText field() { return field; }
}
