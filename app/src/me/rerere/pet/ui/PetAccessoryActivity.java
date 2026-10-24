package me.rerere.pet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

import me.rerere.pet.data.BuiltinMcp;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.store.AccessoryVm;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.CardView2;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * 配件（装备）开关页 —— 单向数据流 + 新视觉（Phase 0/1 试点）。
 *
 * 什么是配件：模型里默认隐藏、靠材质表情开启的部件，
 *   例如 きつね耳(狐耳) / きつね尻尾(狐尾) / 靴(鞋) / 上着(外套) / めがね(眼镜)。
 *   它们的 PMX 基础 alpha 是 0.00，必须打开才显示。
 *
 * 本页只做两件事（整个重构的核心纪律）：
 *   1) 渲染：从 AccessoryVm 取快照 -> 画控件（永不写数据）
 *   2) 发意图：用户真的点了 -> vm.toggle / vm.setAll -> 用返回的新快照回写
 * 没有任何一处"读控件值去保存"。旧版的 suppress / currentOn 两个补丁已删除
 * （它们本来就是为压住"初始化误触发保存"而存在的）。
 *
 * 页面骨架走 SettingsPage（统一规格），只有"动态配件列表"是手工搭的。
 */
public final class PetAccessoryActivity extends Activity {

    private AccessoryVm vm;
    private SettingsPage page;

    /** 配件名 -> 行控件。只用于回写已有控件，绝不用于读取业务状态。 */
    private final LinkedHashMap<String, ToggleRowView> rows = new LinkedHashMap<>();
    /** 当前已构建的行对应的配件签名（名单变了才重建）。 */
    private String builtSignature = "";

    private LinearLayout listBox;
    private CardView2 listShell;
    private CardView2 emptyShell;
    private TextView emptyText;
    private TextView modelView;
    private TextView allOn;
    private TextView allOff;

    /** 配件列表为空的轮询计数（桌宠服务重启后需要等模型加载完）。 */
    private int emptyTicks = 0;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vm = new AccessoryVm(this);

        page = new SettingsPage(this);
        page.pageTitle("配件 / 装备",
                "模型自带的隐藏部件（耳朵、尾巴、鞋子、外套、眼镜等），打开即可显示。"
                        + "配件是持续状态，会一直保留；设置按模型分别保存。");

        /* ---------- 当前模型 ---------- */
        page.section("当前模型");
        modelView = page.info("");

        /* ---------- 可用配件 ---------- */
        page.section("可用配件");

        /* 空状态卡（没有配件 / 桌宠没跑时显示） */
        emptyShell = page.currentCard();
        emptyText = page.info("");
        /* page.info 会把文字塞进一张行容器；这里要的是能整体隐藏，
           所以直接对卡片本身做可见性切换。 */
        emptyShell.setVisibility(View.GONE);

        /* 真正的列表卡（不重复分组标题） */
        listShell = page.newCard();
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listShell.addRowNoDivider(listBox);
        /* 注意：这里【不能】再调 page.custom(listBox)，同一个 View 加两次
           会把它从原父容器移走（CardView 里就空了）。 */

        /* ---------- 批量操作 ---------- */
        LinearLayout actions = page.actions("全部开启", () -> setAll(true),
                "全部关闭", () -> setAll(false));
        allOn = (TextView) actions.getChildAt(0);
        allOff = (TextView) actions.getChildAt(1);

        setContentView(page.build());

        /* 首次渲染：只画，不写。 */
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        /* 打开本页就请求补齐译文（这正是"自动翻译"的触发点之一），
           并启动一个轻量轮询：翻译完成后自动刷新列表，标记随之消失。
           注意：轮询里的重建同样只"渲染"，不会写任何配置。 */
        vm.requestTranslate();
        emptyTicks = 0;
        refreshListWithAutoUpdate();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    /** 翻译进行中时每 1.2 秒重建一次列表，翻译完成后停止。 */
    private void refreshListWithAutoUpdate() {
        render();
        boolean needMore = vm.translating();
        /* 配件列表为空时也要继续等：桌宠服务重启后要加载完模型
           才会探测出配件名（实测约 7 秒）。以前本页只在 onResume 渲染一次，
           服务还没就绪 -> 用户看到"当前模型没有配件"，必须手动退出再进。
           这里加有限次数的等待（最多约 24 秒），拿到就停。 */
        if (rows.isEmpty() && emptyTicks < 20) {
            emptyTicks++;
            needMore = true;
        }
        if (!needMore) return;
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshListWithAutoUpdate();
        }, 1200);
    }

    /* ---------------- 渲染 ---------------- */

    /** 全量渲染（只读）：按当前快照重建/更新列表。 */
    private void render() {
        AccessoryVm.Snapshot s = vm.snapshot();

        modelView.setText(s.modelUrl.isEmpty() ? "（未选择模型）" : s.modelUrl);

        if (s.isEmpty()) {
            emptyText.setText("当前模型没有配件，或列表尚未就绪。\n"
                    + "请先在主界面开启桌宠、并把渲染模式切到「3D 模型」"
                    + "（加载模型后配件列表才会就绪），再回到这一页。");
            emptyShell.setVisibility(View.VISIBLE);
            listShell.setVisibility(View.GONE);
            rows.clear();
            builtSignature = "";
            listBox.removeAllViews();
            setActionsEnabled(false);
            return;
        }

        emptyShell.setVisibility(View.GONE);
        listShell.setVisibility(View.VISIBLE);
        setActionsEnabled(true);

        /* 用 StringBuilder 拼签名，不用 String.join：那是 API 26+，
           本项目 minSdk 24，低版本会 NoSuchMethodError。 */
        StringBuilder sb = new StringBuilder();
        for (String n : s.names) sb.append(n).append((char) 1);
        String sig = sb.toString();

        if (!sig.equals(builtSignature)) {
            rebuildRows(s);
            builtSignature = sig;
        }
        /* 状态与译文：原地更新，不重建（不丢滚动位置、不闪） */
        for (Map.Entry<String, ToggleRowView> e : rows.entrySet()) {
            String name = e.getKey();
            ToggleRowView row = e.getValue();
            row.setTitle(titleFor(s, name));
            row.setSubtitle(subtitleFor(s, name));
            row.setSubtitleColor(subtitleColorFor(s, name));
            row.setChecked(s.isOn(name));
        }
    }

    private void setActionsEnabled(boolean on) {
        if (allOn != null) { allOn.setEnabled(on); allOn.setAlpha(on ? 1f : 0.5f); }
        if (allOff != null) { allOff.setEnabled(on); allOff.setAlpha(on ? 1f : 0.5f); }
    }

    /** 重建行控件（只在配件名单变化时调用）。 */
    private void rebuildRows(AccessoryVm.Snapshot s) {
        listBox.removeAllViews();
        rows.clear();
        boolean first = true;
        for (String name : s.names) {
            if (!first) {
                View d = new View(this);
                d.setBackgroundColor(UiTheme.DIVIDER);
                listBox.addView(d, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.hairline(this)));
            }
            first = false;

            ToggleRowView row = new ToggleRowView(this);
            final String accName = name;
            row.setOnToggledListener((r, checked) -> onUserToggle(accName, checked));
            listBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rows.put(name, row);
        }
    }

    /**
     * 显示用标题：翻译中 -> 打标记；有译文 -> "译文  原名"。
     * 存进设置的一直是原名，不然重启后对不上模型里的真实 morph 名。
     */
    private String titleFor(AccessoryVm.Snapshot s, String name) {
        String zh = s.translation(name);
        if (vm.isTranslatingName(name)) return "⟳ 翻译中…  " + name;
        return (zh != null && !zh.isEmpty()) ? (zh + "  " + name) : name;
    }

    /**
     * 副标题：种类标签 + 说明。
     * 种类标签让用户知道该期待什么效果 —— “显示部件/脱下装备”一眼可见，
     * “形状变化/姿势动作”观感较弱，不标注容易被误以为“开了没反应”。
     */
    private String subtitleFor(AccessoryVm.Snapshot s, String name) {
        String kind = s.kind(name);
        String hint = BuiltinMcp.expressionHint(name);
        String body = (hint == null || hint.isEmpty()) ? "模型自带部件" : hint;
        return kind.isEmpty() ? body : ("【" + kind + "】" + body);
    }

    private int subtitleColorFor(AccessoryVm.Snapshot s, String name) {
        String kind = s.kind(name);
        return (kind.startsWith("形状") || kind.startsWith("姿势"))
                ? 0xFFB26A00 : UiTheme.TEXT_SECONDARY;
    }

    /* ---------------- 用户意图 ---------------- */

    /** 用户点了某个开关：交给 VM 落库 + 下发，再用返回的真值回写控件。 */
    private void onUserToggle(String name, boolean wanted) {
        AccessoryVm.Snapshot s = vm.toggle(name, wanted);
        ToggleRowView row = rows.get(name);
        boolean actual = s.isOn(name);
        if (row != null) row.setChecked(actual);
        toast(name + (actual ? " 已开启" : " 已关闭"));
        if (actual != wanted) toast("保存失败，已恢复原状");
    }

    /**
     * 全部开启 / 全部关闭 —— 一次原子写入。
     * 绝不循环 setChecked：那会造成几十次 读-改-写 互相覆盖（丢失更新）。
     */
    private void setAll(boolean on) {
        AccessoryVm.Snapshot before = vm.snapshot();
        if (before.isEmpty()) { toast("当前模型没有配件"); return; }

        AccessoryVm.Snapshot s = vm.setAll(on);
        for (Map.Entry<String, ToggleRowView> e : rows.entrySet()) {
            e.getValue().setChecked(s.isOn(e.getKey()));
        }
        toast(on
                ? ("已开启全部配件（" + s.enabled.size() + " 个）")
                : "已关闭全部配件");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
