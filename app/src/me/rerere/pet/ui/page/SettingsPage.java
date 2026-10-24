package me.rerere.pet.ui.page;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.CardView2;
import me.rerere.pet.ui.widget.ChoiceRowView;
import me.rerere.pet.ui.widget.SeekRowView;
import me.rerere.pet.ui.widget.SectionHeader;
import me.rerere.pet.ui.widget.TextRowView;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * 设置页构建器 —— 以后加一个开关就一行代码。
 *
 * 为什么要有这个类（规格统一 + 一次写对）
 *   本项目没有 AndroidX / RecyclerView，所有页面都是 addView 堆出来的。
 *   如果每个页面各自拼 LinearLayout + TextView + 开关 + 分割线，重复代码
 *   上千行，而且特别容易出现两类历史 bug：
 *     1) 有人图省事写 `switch.isChecked()` 去保存 -> 页面一加载就覆盖配置；
 *     2) 有人忘了加 0.5dp 分割线 / 忘了 20dp 内边距 -> 页面之间长得不一样。
 *   这里把"长什么样"和"怎么读写数据"都固化下来。
 *
 * 典型用法（新增一个开关，就这一行）：
 *   page.toggle("显示用户头像", "是否在聊天消息中显示用户头像",
 *               vm.showUserAvatar(),           // 渲染值（来自 ViewModel 快照）
 *               v -> vm.setShowUserAvatar(v)); // 用户意图（只在用户操作时回调）
 *
 * 关键性质：toggle() 在创建时只 setChecked（渲染），绝不会回调；
 *   回调只由用户的真实点击触发。所以不存在"初始化把配置刷成默认值"。
 *
 * 结构（section 自动切卡，行自动插分割线）：
 *   pageTitle(...)   -> 大标题
 *   section("xxx")   -> 分组小标题 + 新卡片
 *   toggle/seek/nav  -> 自动进当前卡片
 */
public final class SettingsPage {

    private final Activity host;
    private final ScrollView scroll;
    private final LinearLayout root;

    /** 当前正在填充的卡片；遇到新的 section 就换一张。 */
    private CardView2 card;

    /** 所有 toggle 行，便于批量刷新（比如"全部开启"后统一回写）。 */
    private final List<ToggleRowView> toggles = new ArrayList<>();

    public SettingsPage(Activity host) {
        this.host = host;
        scroll = new ScrollView(host);
        scroll.setBackgroundColor(UiTheme.BG);
        scroll.setFillViewport(true);
        /* ★ 阻止"一进页面就滚到输入框"：
           页面里只要有 EditText，窗口第一次布局时它会自动拿焦点并
           scrollIntoView —— 表现就是打开设置页却停在页面中间（实测）。
           让 ScrollView 自己在触摸模式下可获焦，初始焦点就落在它身上，
           EditText 仍然可以点一下再输入。 */
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);

        root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        int h = UiTheme.dp(host, UiTheme.PAD_PAGE);
        /* 底部多留一截：给底栏留空间，滚到底时最后一行不被压住 */
        root.setPadding(h, UiTheme.dp(host, 28f), h, UiTheme.dp(host, 96f));

        /* ★★ 焦点吸附块（必须放在最前面）★★
           页面里只要有 EditText，窗口第一次拿到焦点时它会自动成为焦点
           → 系统弹出输入法、并把 ScrollView 滚到输入框那里。
           表现就是"打开设置页先看到键盘、页面停在中间"（实测两次）。
           加一个 0 尺寸但可获焦的 View 放在 root 第 0 位，
           初始焦点就落在它身上，页面停在顶部、不弹键盘；
           用户点输入框仍然正常输入。 */
        View focusCatcher = new View(host);
        focusCatcher.setFocusable(true);
        focusCatcher.setFocusableInTouchMode(true);
        root.addView(focusCatcher, new LinearLayout.LayoutParams(0, 0));

        scroll.addView(root);
    }

    public Context ctx() { return host; }

    public Activity activity() { return host; }

    /** 页面大标题 + 可选说明（说明传 null 则不加）。 */
    public SettingsPage pageTitle(String title, String intro) {
        TextView t = UiTheme.pageTitle(host, title);
        root.addView(t);
        if (intro != null && !intro.isEmpty()) {
            TextView s = UiTheme.text(host, intro, 14f, UiTheme.TEXT_SECONDARY, false);
            s.setLineSpacing(UiTheme.dp(host, 3f), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiTheme.dp(host, 8f);
            root.addView(s, lp);
        }
        return this;
    }

    /** 开一个新分组（自动结束上一张卡片）。 */
    public SettingsPage section(String label) {
        root.addView(new SectionHeader(host).setLabel(label));
        card = new CardView2(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiTheme.dp(host, UiTheme.GAP_CARD);
        root.addView(card, lp);
        return this;
    }

    /** 新开一张卡片，但**不加分组标题**（同一分组里需要两张卡时用）。 */
    public CardView2 newCard() {
        card = new CardView2(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiTheme.dp(host, UiTheme.GAP_CARD);
        root.addView(card, lp);
        return card;
    }

    /** 确保有卡片可用（section 之前就 add 内容时自动补一张）。 */
    private CardView2 card() {
        if (card == null) section("");
        return card;
    }

    /* ---------------- 行类型 ---------------- */

    /**
     * 开关行。
     *
     * @param checked 渲染值（从 ViewModel 快照来，不能读控件）
     * @param onUser  用户点击后回调（唯一的数据写入入口）
     */
    public ToggleRowView toggle(String title, String subtitle, boolean checked,
                                ToggleRowView.OnToggledListener onUser) {
        ToggleRowView row = new ToggleRowView(host);
        row.setTitle(title).setSubtitle(subtitle).setChecked(checked);
        row.setOnToggledListener(onUser);
        card().addRow(row);
        toggles.add(row);
        return row;
    }

    /** 开关行（不需要副标题的简写）。 */
    public ToggleRowView toggle(String title, boolean checked,
                                ToggleRowView.OnToggledListener onUser) {
        return toggle(title, null, checked, onUser);
    }

    /**
     * 滑块行。
     *
     * @param suffix 数值后缀（如 "%"），传 null 表示不带单位
     * @param onUser 拖动结束才建议落库（拖动中可只做实时预览）
     */
    public SeekRowView seek(String title, String subtitle, float value,
                            float min, float max, final String suffix,
                            SeekRowView.OnSeekListener onUser) {
        SeekRowView row = new SeekRowView(host);
        row.setTitle(title).setSubtitle(subtitle);
        row.setRange(min, max);
        if (suffix != null) {
            row.setFormatter(v -> Math.round(v) + suffix);
        }
        row.setValue(value);
        row.setOnSeekListener(onUser);
        card().addRow(row);
        return row;
    }

    /**
     * 单选胶囊行（选项少、互斥时用）。
     *
     * @param selected 渲染值（来自快照/配置），-1 表示都不选中
     * @param onUser   用户点击后回调（唯一写入入口）
     */
    public ChoiceRowView choice(String title, String subtitle, String[] options,
                                int selected, int perLine,
                                ChoiceRowView.OnSelectListener onUser) {
        ChoiceRowView row = new ChoiceRowView(host);
        row.setTitle(title).setSubtitle(subtitle).setPerLine(perLine);
        row.setOptions(options);
        row.setSelectedIndex(selected);
        row.setOnSelectListener(onUser);
        card().addRow(row);
        return row;
    }

    /** 单选胶囊行（默认每行 2 个）。 */
    public ChoiceRowView choice(String title, String subtitle, String[] options,
                                int selected, ChoiceRowView.OnSelectListener onUser) {
        return choice(title, subtitle, options, selected, 2, onUser);
    }

    /**
     * 文本输入行。★ 渲染用 setText；只有用户在页面上点「保存」时才读内容。
     */
    public TextRowView textField(String title, String subtitle, String value,
                                 int minLines, boolean singleLine) {
        TextRowView row = new TextRowView(host);
        row.setTitle(title).setSubtitle(subtitle);
        row.setText(value);
        if (singleLine) row.setSingleLine(true); else row.setMinLines(minLines);
        card().addRow(row);
        return row;
    }

    /** 可点击的导航行（进入子页）。 */    public LinearLayout nav(String title, String subtitle, Runnable onClick) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = UiTheme.dp(host, UiTheme.PAD_H), pv = UiTheme.dp(host, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);

        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiTheme.title(host, title));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = UiTheme.subtitle(host, subtitle);
            s.setLineSpacing(UiTheme.dp(host, 2f), 1f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = UiTheme.dp(host, 4f);
            col.addView(s, slp);
        }
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = UiTheme.text(host, "\u203A", 24f, 0xFF9AA0A6, false);
        chev.setGravity(Gravity.CENTER);
        row.addView(chev);

        if (onClick != null) {
            row.setClickable(true);
            row.setOnClickListener(v -> onClick.run());
        }
        card().addRow(row);
        return row;
    }

    /** 纯说明文字块（只读，不可点）。 */
    public TextView info(String text) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(host, UiTheme.PAD_H), pv = UiTheme.dp(host, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);
        TextView t = UiTheme.text(host, text, 14f, UiTheme.TEXT_SECONDARY, false);
        t.setLineSpacing(UiTheme.dp(host, 3f), 1f);
        row.addView(t);
        card().addRow(row);
        return t;
    }

    /**
     * 数值/状态行（左标题，右值）。
     *
     * ★ 长值必须走 {@link #valueRowWide}：左右排布时右边的值只有约一半宽度，
     *   长路径（/models/mmd_import_1791294297234_Kurumi (1)/...）会被压成一列
     *   一个字换行，看起来像竖排文字（实测踩过）。
     */
    public TextView valueRow(String title, String value) {
        if (value != null && value.length() > 14) return valueRowWide(title, value);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = UiTheme.dp(host, UiTheme.PAD_H), pv = UiTheme.dp(host, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);
        row.addView(UiTheme.title(host, title),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = UiTheme.text(host, value, 15f, UiTheme.TEXT_SECONDARY, false);
        v.setGravity(Gravity.END);
        row.addView(v);
        card().addRow(row);
        return v;
    }

    /** 长值行：标题在上、值在下（值可以正常换行，不会挤成竖排）。 */
    public TextView valueRowWide(String title, String value) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(host, UiTheme.PAD_H), pv = UiTheme.dp(host, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);
        row.addView(UiTheme.title(host, title));
        TextView v = UiTheme.text(host, value, 14f, UiTheme.TEXT_SECONDARY, false);
        v.setLineSpacing(UiTheme.dp(host, 2f), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTheme.dp(host, 5f);
        row.addView(v, lp);
        card().addRow(row);
        return v;
    }

    /** 把自定义行插进当前卡片（会自己处理分割线）。 */
    public SettingsPage custom(View row) {
        card().addRow(row);
        return this;
    }

    /** 把自定义 View 直接插到当前卡片内。 */
    public SettingsPage custom(View row, boolean withDivider) {
        if (withDivider) card().addRow(row); else card().addRowNoDivider(row);
        return this;
    }

    /**
     * 底部一组按钮（可 1~3 个，等宽排列）。
     * 三个按钮时中间那个仍是主按钮样式 —— 这里不区分主次，避免视觉过重。
     */
    public LinearLayout actions(String[] labels, Runnable[] handlers) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTheme.dp(host, 4f);
        root.addView(row, lp);

        if (labels == null) return row;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            TextView b = (i == 0)
                    ? UiTheme.primaryButton(host, labels[i])
                    : UiTheme.secondaryButton(host, labels[i]);
            final Runnable action = (handlers != null && i < handlers.length) ? handlers[i] : null;
            if (action != null) b.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (row.getChildCount() > 0) blp.leftMargin = UiTheme.dp(host, 10f);
            row.addView(b, blp);
        }
        return row;
    }

    /** 底部一组按钮（主 + 次，任一传 null 就只显示另一个）。 */
    public LinearLayout actions(String primaryText, Runnable onPrimary,
                                String secondaryText, Runnable onSecondary) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiTheme.dp(host, 4f);
        root.addView(row, lp);

        if (primaryText != null) {
            TextView p = UiTheme.primaryButton(host, primaryText);
            p.setOnClickListener(v -> onPrimary.run());
            row.addView(p, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (secondaryText != null) {
            TextView s = UiTheme.secondaryButton(host, secondaryText);
            s.setOnClickListener(v -> onSecondary.run());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (primaryText != null) slp.leftMargin = UiTheme.dp(host, 12f);
            row.addView(s, slp);
        }
        return row;
    }

    /** 当前卡片（需要往卡片里塞动态列表时用）。 */
    public CardView2 currentCard() { return card(); }

    /** 根容器（极少用）。 */
    public LinearLayout root() { return root; }

    /** 成品 View，交给 setContentView。 */
    public View build() { return scroll; }

    /** 所有开关行（批量回写用）。 */
    public List<ToggleRowView> toggles() { return toggles; }
}
