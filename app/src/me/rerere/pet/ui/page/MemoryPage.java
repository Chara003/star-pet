package me.rerere.pet.ui.page;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.theme.UiTheme;

/**
 * 长期记忆管理页（新实现，不再跳旧 MainActivity）。
 *
 * 记忆是什么：几条短句，会随【每轮对话】一起发给模型，
 * 让桌宠"记得"曾提到过的事（偏好、日程、称呼…）。
 * 所以它必须短 —— 太长会挤占上下文窗口，反而降低回答质量。
 *
 * ★ 数据只有三个操作：读列表、加一条、删一条（PetStore 已有接口）。
 *   这里不做编辑：改内容 = 删掉再加，比做一套"编辑态切换"更不容易出错。
 */
public final class MemoryPage {

    private final Activity activity;
    private final PetStore store;
    /** 内容变化后通知宿主重建（避免跨页数据不一致）。 */
    private final Runnable onChanged;

    public MemoryPage(Activity activity, PetStore store, Runnable onChanged) {
        this.activity = activity;
        this.store = store;
        this.onChanged = onChanged;
    }

    public View build() {
        SettingsPage page = new SettingsPage(activity);
        page.pageTitle("长期记忆",
                "这些内容会随对话一起发给模型，帮助桌宠记住你。\n"
                        + "建议写短句，一件事一条；太长的记忆会挤占上下文，反而让回答变差。");

        List<String> memories = store.longTermMemories();

        page.section("已有记忆（" + memories.size() + " 条）");
        if (memories.isEmpty()) {
            page.info("还没有记忆。点下面的「添加一条记忆」开始。");
        } else {
            for (int i = 0; i < memories.size(); i++) {
                final String text = memories.get(i);
                /* nav 行：点一下进入"删除确认"，右侧箭头表示可操作 */
                page.nav(text,
                        "第 " + (i + 1) + " 条 · 点击可删除",
                        () -> confirmDelete(text));
            }
        }

        page.section("新增");
        page.custom(buildAddRow(), true);
        page.info("例：「叫我小星」「喜欢喝冰美式」「周四有例会」。");

        return page.build();
    }

    /** 输入框 + 添加按钮（不弹对话框：对话框在键盘弹出时容易遮挡输入框）。 */
    private LinearLayout buildAddRow() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(activity, UiTheme.PAD_H);
        int pv = UiTheme.dp(activity, UiTheme.PAD_V);
        box.setPadding(ph, pv, ph, pv);

        box.addView(UiTheme.text(activity, "添加一条记忆", 14f, UiTheme.TEXT_PRIMARY, true));

        final EditText input = new EditText(activity);
        input.setHint("例如：叫我小星");
        input.setHintTextColor(0xFFB0B0B8);
        input.setTextSize(15f);
        input.setTextColor(UiTheme.TEXT_PRIMARY);
        input.setSingleLine(true);
        input.setBackground(UiTheme.round(activity, UiTheme.INFO_BG, 12f));
        int ih = UiTheme.dp(activity, 12f), iv = UiTheme.dp(activity, 11f);
        input.setPadding(ih, iv, ih, iv);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = UiTheme.dp(activity, 7f);
        box.addView(input, ilp);

        TextView add = UiTheme.primaryButton(activity, "添加");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = UiTheme.dp(activity, 10f);
        add.setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            if (text.isEmpty()) { toast("请先输入内容"); return; }
            if (text.length() > 200) { toast("太长了，建议压到 200 字以内"); return; }
            /* 去重：同一条重复添加没有意义，只会浪费上下文 */
            for (String m : store.longTermMemories()) {
                if (m != null && m.equals(text)) { toast("已经记过了"); return; }
            }
            store.addLongTermMemory(text);
            toast("已记住");
            if (onChanged != null) onChanged.run();
        });
        box.addView(add, alp);

        return box;
    }

    private void confirmDelete(final String text) {
        /* ★ 必须用 Activity 的 Context 弹窗（applicationContext 会 BadTokenException） */
        new android.app.AlertDialog.Builder(activity)
                .setTitle("删除这条记忆？")
                .setMessage(text)
                .setPositiveButton("删除", (d, w) -> {
                    store.removeLongTermMemory(text);
                    toast("已删除");
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }
}
