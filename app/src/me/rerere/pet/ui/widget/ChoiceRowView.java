package me.rerere.pet.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 「标题 + 说明 + 一排可选胶囊」行。
 *
 * 用途：选项数量少、彼此互斥、需要一眼看到全部候选的场合 ——
 *   性格预设（傲娇/黏人/高冷/元气）、思考深度（关闭/低/中/高/最高）、
 *   抗锯齿档位这类。比下拉框少一次点击，比 SeekBar 语义更准确
 *   （SeekBar 表达的是"程度"，这里表达的是"选哪个"）。
 *
 * 单向数据流（与 ToggleRowView / SeekRowView 同一套约定）：
 *   setSelectedIndex() = 渲染，绝不回调；
 *   点击胶囊          = 唯一的意图来源，通过 OnSelectListener 抛给调用方。
 *   ★ 初始化阶段绝不会因为 setSelectedIndex 触发保存。
 *
 * 布局：按 perLine 分行、每格等宽（weight=1），间距 10dp。
 *   —— 不做流式换行（需要自己写 onMeasure），等宽网格在设置页里更整齐；
 *      选项文本过长时自动换行（不截断）。
 */
public final class ChoiceRowView extends LinearLayout {

    public interface OnSelectListener {
        /** 用户点了第 index 个选项（0 起）。 */
        void onSelect(ChoiceRowView row, int index);
    }

    private final TextView title;
    private final TextView subtitle;
    private final LinearLayout grid;

    private final List<TextView> chips = new ArrayList<>();
    private String[] options = new String[0];
    private int perLine = 2;
    private int selected = -1;
    private boolean rowEnabled = true;
    private OnSelectListener listener;

    public ChoiceRowView(Context c) {
        this(c, null);
    }

    /** 必须提供 (Context, AttributeSet)，否则将来在 XML 里声明会 InflateException。 */
    public ChoiceRowView(Context c, AttributeSet attrs) {
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

        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        glp.topMargin = UiTheme.dp(c, 12f);
        addView(grid, glp);
    }

    /* ---------------- 渲染 ---------------- */

    public ChoiceRowView setTitle(String text) {
        title.setText(text);
        return this;
    }

    public ChoiceRowView setSubtitle(String text) {
        if (text == null || text.isEmpty()) {
            subtitle.setVisibility(GONE);
        } else {
            subtitle.setText(text);
            subtitle.setVisibility(VISIBLE);
        }
        return this;
    }

    /** 每行几个（默认 2）。会在下次 setOptions 时生效。 */
    public ChoiceRowView setPerLine(int n) {
        if (n >= 1) perLine = n;
        return this;
    }

    /** 设置候选并重建胶囊（只在选项集合变化时调用）。 */
    public ChoiceRowView setOptions(String[] opts) {
        options = opts == null ? new String[0] : opts;
        rebuild();
        return this;
    }

    /** 当前选中下标（-1 = 都没选）。 */
    public int selectedIndex() { return selected; }

    /**
     * 渲染选中态：只改外观，不回调、不动画。
     * 传 -1 表示"没有选中任何项"（例如随机模式下的性格预设）。
     */
    public ChoiceRowView setSelectedIndex(int index) {
        selected = index;
        restyle();
        return this;
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        rowEnabled = enabled;
        restyle();
    }

    public ChoiceRowView setOnSelectListener(OnSelectListener l) {
        this.listener = l;
        return this;
    }

    /* ---------------- 内部 ---------------- */

    private void rebuild() {
        grid.removeAllViews();
        chips.clear();
        if (options.length == 0) return;

        LinearLayout line = null;
        for (int i = 0; i < options.length; i++) {
            if (i % perLine == 0) {
                line = new LinearLayout(getContext());
                line.setOrientation(HORIZONTAL);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                if (i > 0) llp.topMargin = UiTheme.dp(getContext(), 10f);
                grid.addView(line, llp);
            }

            final int index = i;
            TextView chip = new TextView(getContext());
            chip.setText(options[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(UiTheme.dp(getContext(), 6f), UiTheme.dp(getContext(), 11f),
                    UiTheme.dp(getContext(), 6f), UiTheme.dp(getContext(), 11f));
            chip.setMinHeight(UiTheme.dp(getContext(), 44f));
            chip.setOnClickListener(v -> {
                if (!rowEnabled) return;
                /* 只通知，不改自己的状态 —— 由调用方决定是否真的生效，
                   再把新快照写回来（这样"保存失败/被拒绝"也能正确回滚）。 */
                if (listener != null) listener.onSelect(this, index);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1f);
            if (i % perLine != 0) clp.leftMargin = UiTheme.dp(getContext(), 10f);
            line.addView(chip, clp);
            chips.add(chip);
        }
        restyle();
    }

    private void restyle() {
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            boolean on = rowEnabled && i == selected;
            chip.setTextColor(on ? 0xFFFFFFFF : UiTheme.TEXT_PRIMARY);
            chip.setTypeface(null, on
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            chip.setBackground(UiTheme.round(getContext(),
                    on ? UiTheme.accent() : UiTheme.SUBTLE, 16f));
            chip.setAlpha(rowEnabled ? 1f : 0.45f);
        }
        title.setTextColor(rowEnabled ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY);
        subtitle.setAlpha(rowEnabled ? 1f : 0.6f);
    }
}
