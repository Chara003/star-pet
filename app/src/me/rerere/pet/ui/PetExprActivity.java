package me.rerere.pet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.rerere.pet.data.BuiltinMcp;
import me.rerere.pet.data.PetOverlayServiceBridge;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.CardView2;

/**
 * 表情映射编辑页：给每个表情指定一个短编号，供 AI 通过 MCP 精准选择。
 *
 * ★ 为什么需要这一页：
 *   MMD 的表情名是日文或纯数字（100.001 / あ / はぅ / Close），AI 无法理解；
 *   而 100.00x 这类【装饰件（光环/头饰）】在 morph 列表里往往排在最前面，
 *   AI 很容易顺手挑到它们 —— 表现就是「AI 做的表情只有光环在动、脸一点不动」。
 *   把表情映射成 1 / 2 / 3 这样的短编号后，AI 就能稳定选中你想要的那个。
 *
 * 用法：默认已经按顺序自动编号（1..N），只需要改你想改的；留空表示该项没有编号。
 *
 * ── 本轮重构要点 ──
 *   ① 骨架换成 SettingsPage（统一外观），列表用 CardView2.addRow 自动插分割线。
 *   ② ★ 名单不变时**不重建行**，只就地更新译文/提示 ——
 *      旧版翻译轮询每 1.2s 重建一次，用户正在输入的编号会被静默清空。
 *   ③ 翻译中显示「⟳ 翻译中…」，完成后自动消失；离开页面即停轮询。
 */
public final class PetExprActivity extends Activity {

    private PetStore store;
    private String modelUrl = "";

    private SettingsPage page;
    private CardView2 listShell;
    private CardView2 emptyShell;
    private TextView emptyText;

    /** 一行 = 编号输入框 + 名字/译文/说明 + 试播按钮。 */
    private static final class ExprRow {
        String realName;
        LinearLayout view;
        EditText field;
        TextView nameView;
        TextView zhView;
        TextView hintView;
    }

    private final List<ExprRow> rows = new ArrayList<>();
    /** 当前已构建行对应的名单签名（名单变了才重建）。 */
    private String builtSignature = "";

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PetStore(this);

        Bundle extra = getIntent() == null ? null : getIntent().getExtras();
        if (extra != null) {
            String u = extra.getString("modelUrl", "");
            if (u != null) modelUrl = u;
        }
        if (modelUrl.isEmpty()) modelUrl = store.appearance().mmdModelUrl;
        if (modelUrl == null) modelUrl = "";

        final boolean glb = isGlbModel();

        page = new SettingsPage(this);
        page.pageTitle(glb ? "动作映射" : "表情映射",
                "给每个" + (glb ? "动作" : "表情") + "指定一个短编号（如 1、2、3），"
                        + "AI 就能精准选中它。默认已按顺序自动编号，只改你想改的；"
                        + "留空表示该项没有编号。");

        /* ---------------- 当前模型 ---------------- */
        page.section("当前模型");
        page.valueRowWide("模型文件", modelUrl.isEmpty() ? "（未选择）" : modelUrl);

        page.info(glb
                ? ("★ 为什么这里是动作而不是表情：这类 GLB 的 morph target 全是"
                   + "【嘴型】（Mouth_200/300…），并没有脸部表情 —— 嘴型本来就由动画自动驱动，"
                   + "所以对 glTF 模型来说「做个表演」= 播一个自带动画。"
                   + "右侧「试播」可以直接看效果。")
                : ("提示「装饰件/光环」的项只影响光环或饰品，不是脸部表情。"
                   + "右侧「试播」可以立刻看这个表情长什么样。"));

        /* ---------------- 列表 ---------------- */
        page.section(glb ? "动作列表" : "表情列表");

        emptyShell = page.currentCard();
        emptyText = page.info("");
        emptyShell.setVisibility(View.GONE);

        listShell = page.newCard();
        listShell.setVisibility(View.GONE);

        /* ---------------- 操作 ---------------- */
        page.actions(new String[]{"保存", "自动编号", "全部清空"},
                new Runnable[]{this::save, this::applyAutoNumbering, this::clearAll});

        setContentView(page.build());
    }

    @Override protected void onResume() {
        super.onResume();
        /* ★ 打开本页也触发一次补齐译文 + 自动刷新（同配件页） */
        PetOverlayServiceBridge.requestTranslate(false);
        refreshWithAutoUpdate();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * 当前编辑的是不是 glTF 模型（.glb / .gltf）。
     * glTF 的"表情"= 自带动画（见类头说明与 PetOverlayService 的注册处）。
     */
    private boolean isGlbModel() {
        String u = modelUrl == null ? "" : modelUrl.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        return u.endsWith(".glb") || u.endsWith(".gltf");
    }

    /** 列表里显示的短名（有译文就用译文）。 */
    private String displayNameOf(String real) {
        if (real == null) return "";
        try {
            String zh = store.translations().get(real);
            if (zh != null && !zh.isEmpty() && !zh.equals(real)) return zh;
        } catch (Throwable ignored) {}
        return real;
    }

    /* ---------------- 翻译轮询 ---------------- */

    private void refreshWithAutoUpdate() {
        reloadList();
        if (!PetOverlayServiceBridge.isTranslating()) return;
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshWithAutoUpdate();
        }, 1200);
    }

    /* ---------------- 列表构建 ---------------- */

    /**
     * 列表情 / 动作。
     * 名单来自 PetOverlayService 在模型加载后填充的列表；桌宠没开时为空，
     * 这里给出明确提示（而不是显示一片空白）。
     */
    private void reloadList() {
        List<String> names = PetOverlayServiceBridge.expressionNames();
        if (names == null || names.isEmpty()) {
            showEmpty();
            return;
        }

        emptyShell.setVisibility(View.GONE);
        listShell.setVisibility(View.VISIBLE);

        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(n).append((char) 1);
        String sig = sb.toString();

        if (!sig.equals(builtSignature)) {
            rebuildRows(names);
            builtSignature = sig;
        } else {
            /* 名单没变：只刷新文字，保住用户正在输入的编号 */
            for (int i = 0; i < rows.size() && i < names.size(); i++) {
                updateRowText(rows.get(i), names.get(i));
            }
        }
    }

    private void showEmpty() {
        builtSignature = "";
        rows.clear();
        listShell.removeAllViews();
        listShell.setVisibility(View.GONE);
        emptyShell.setVisibility(View.VISIBLE);
        emptyText.setText(isGlbModel()
                ? ("暂时拿不到动作列表。\n"
                   + "glTF 的自带动作要等模型加载完才拿得到 —— "
                   + "请先在主界面开启桌宠、切到「3D 模型」并选好这个 .glb，再回到这一页。")
                : ("暂时拿不到表情列表。\n"
                   + "请先在主界面开启桌宠、并把渲染模式切到「3D 模型」"
                   + "（加载模型后表情列表才会就绪），再回到这一页。"));
    }

    private void rebuildRows(List<String> names) {
        listShell.removeAllViews();
        rows.clear();

        /* 已保存的映射（可能为空 → 用自动编号做初值） */
        Map<String, String> saved = store.exprAliases(modelUrl);
        Map<String, String> byName = new LinkedHashMap<>();   // 真实名 -> 别名
        for (Map.Entry<String, String> e : saved.entrySet()) byName.put(e.getValue(), e.getKey());

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String alias = byName.get(name);
            if (alias == null && saved.isEmpty()) alias = String.valueOf(i + 1);

            ExprRow r = buildRow(name, alias);
            rows.add(r);
            listShell.addRow(r.view);
        }
    }

    /** 创建一行（只在名单变化时调用）。 */
    private ExprRow buildRow(final String realName, String alias) {
        final ExprRow r = new ExprRow();
        r.realName = realName;

        r.view = new LinearLayout(this);
        r.view.setOrientation(LinearLayout.HORIZONTAL);
        r.view.setGravity(Gravity.CENTER_VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        int pv = UiTheme.dp(this, UiTheme.PAD_V);
        r.view.setPadding(ph, pv, ph, pv);

        /* 编号输入框 */
        r.field = new EditText(this);
        r.field.setText(alias == null ? "" : alias);
        r.field.setHint("编号");
        r.field.setTextSize(15f);
        r.field.setTextColor(UiTheme.TEXT_PRIMARY);
        r.field.setHintTextColor(0xFFA8A8AD);
        r.field.setGravity(Gravity.CENTER);
        r.field.setInputType(InputType.TYPE_CLASS_NUMBER);
        r.field.setSingleLine(true);
        r.field.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
        r.field.setPadding(UiTheme.dp(this, 6f), UiTheme.dp(this, 10f),
                UiTheme.dp(this, 6f), UiTheme.dp(this, 10f));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                UiTheme.dp(this, 64f), ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.rightMargin = UiTheme.dp(this, 12f);
        r.view.addView(r.field, flp);

        /* 名字 / 译文 / 说明 */
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        r.nameView = UiTheme.text(this, realName, 15f, UiTheme.TEXT_PRIMARY, true);
        col.addView(r.nameView);

        r.zhView = UiTheme.text(this, "", 13f, UiTheme.accent(), false);
        r.zhView.setVisibility(View.GONE);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zlp.topMargin = UiTheme.dp(this, 3f);
        col.addView(r.zhView, zlp);

        r.hintView = UiTheme.text(this, "", 12f, UiTheme.TEXT_SECONDARY, false);
        r.hintView.setLineSpacing(UiTheme.dp(this, 2f), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiTheme.dp(this, 3f);
        col.addView(r.hintView, hlp);

        r.view.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        /* ★★ 试播按钮（设计上要求：每个表情右边一个）★★
           · glTF → 播这个自带动画（走 playGlbClip）
           · MMD  → 应用这个 morph 表情（走 playExpression，3 秒后自动恢复）
           先检查"桌宠在不在跑"，否则点了没反应会以为是坏的。 */
        TextView playBtn = UiTheme.text(this, "试播", 13f, UiTheme.accent(), true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setPadding(UiTheme.dp(this, 10f), UiTheme.dp(this, 10f),
                UiTheme.dp(this, 10f), UiTheme.dp(this, 10f));
        playBtn.setOnClickListener(v -> play(realName));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = UiTheme.dp(this, 8f);
        r.view.addView(playBtn, plp);

        updateRowText(r, realName);
        return r;
    }

    /** 就地更新一行的文字（不动输入框内容）。 */
    private void updateRowText(ExprRow r, String realName) {
        r.nameView.setText(realName);

        Map<String, String> trans = store.translations();
        String zh = trans == null ? null : trans.get(realName);
        boolean translatingNow = PetOverlayServiceBridge.isTranslatingName(realName);

        if (translatingNow) {
            r.zhView.setText("⟳ 翻译中…");
            r.zhView.setVisibility(View.VISIBLE);
        } else if (zh != null && !zh.isEmpty() && !zh.equals(realName)) {
            r.zhView.setText(zh);
            r.zhView.setVisibility(View.VISIBLE);
        } else {
            r.zhView.setVisibility(View.GONE);
        }

        String hint = BuiltinMcp.expressionHint(realName);
        r.hintView.setText(hint == null ? "（无说明）" : hint);
        /* 装饰件用提示色标出来，方便一眼分辨"不是脸部表情"的那些 */
        r.hintView.setTextColor(hint != null && hint.contains("装饰件")
                ? 0xFFB26A00 : UiTheme.TEXT_SECONDARY);
    }

    /* ---------------- 用户意图 ---------------- */

    private void play(String realName) {
        if (PetOverlayService.current() == null) {
            toast("桌宠没在运行 —— 先在主界面开启桌宠、并切到「3D 模型」");
            return;
        }
        if (isGlbModel()) {
            PetOverlayServiceBridge.playGlbClip(realName);
            toast("试播动作：" + displayNameOf(realName));
        } else {
            PetOverlayServiceBridge.playExpression(realName, 3f);
            toast("试播表情：" + displayNameOf(realName));
        }
    }

    private void applyAutoNumbering() {
        int i = 1;
        for (ExprRow r : rows) r.field.setText(String.valueOf(i++));
        toast("已按顺序编号，记得点保存");
    }

    private void clearAll() {
        for (ExprRow r : rows) r.field.setText("");
        toast("已清空，记得点保存");
    }

    private void save() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        HashSet<String> used = new HashSet<>();
        for (ExprRow r : rows) {
            String alias = r.field.getText() == null ? "" : r.field.getText().toString().trim();
            if (alias.isEmpty()) continue;
            if (!used.add(alias)) {
                toast("编号「" + alias + "」重复了，请改掉再保存");
                return;
            }
            map.put(alias, r.realName);
        }
        store.saveExprAliases(modelUrl, map);
        toast("已保存 " + map.size() + " 条映射");
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
