package me.rerere.pet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.store.AppearanceVm;
import me.rerere.pet.ui.store.ModelLibraryVm;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.CardView2;

/**
 * 3D 模型管理：导入 / 选择 / 改名 / 删除，加上几个子页入口。
 *
 * ══════════════════════════════════════════════════════════════
 * 本轮重构（UI 第二轮）做了什么
 * ══════════════════════════════════════════════════════════════
 * ① **删掉与「高级设置」重复的 8 组参数**
 *    旧页面同时有：渲染模式 / 显示大小 / 抗锯齿 / GPU / 动态分辨率 / 渲染倍率 /
 *    帧率上限 / 动态模糊 / 自动漫游 / 物理。这些在 AdvancedPage 里全都有一份，
 *    同一项两处可改、不知道以哪个为准（用户明确提出过）。
 *    现在本页只做"模型库"，参数在「高级设置」；上方的「调整参数」入口直达那里。
 *
 * ② **选择 / 改名 / 删除 / 导入 收拢到 {@link ModelLibraryVm}**
 *    这四件事原来在本页和「模型与素材」页各写一遍，行为已经分叉：
 *    删除当前模型后本页不重启服务（桌宠白屏到手动重开）；
 *    两页的"使用中"判定也不一致。现在只有一份实现。
 *
 * ③ **导入放到后台线程 + 进度框**
 *    旧实现直接在 onActivityResult（主线程）里解压几十 MB 的 zip，
 *    导入重模型时界面会假死甚至 ANR。
 *
 * ④ 模型行改成"点一下即选中 + 右侧改名/删除"，比原来的 ⋮ 菜单少一次点击。
 *
 * ★ 纪律：本页所有写入都经 ModelLibraryVm，不出现 store.saveAppearance。
 */
public final class PetMmdActivity extends Activity {

    private static final int REQ_IMPORT = 4001;

    private ModelLibraryVm lib;
    private AppearanceVm appearance;

    private SettingsPage page;
    private CardView2 listShell;
    private CardView2 emptyShell;
    private TextView statusView;
    private TextView modeView;

    /** 当前列表签名（目录名单 + 别名 + 选中项变了才重建行）。 */
    private String builtSignature = "";

    private AlertDialog importing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lib = new ModelLibraryVm(this);
        appearance = new AppearanceVm(this);

        page = new SettingsPage(this);
        page.pageTitle("3D 模型管理",
                "导入、选择、改名、删除模型。渲染 / 画质 / 性能 / 漫游等参数统一在「高级设置」里，"
                        + "本页不再重复放一份。");

        /* ---------------- 当前状态 ---------------- */
        page.section("当前状态");
        modeView = page.valueRow("渲染模式", "");
        statusView = page.valueRowWide("当前模型", "");

        page.nav("调整渲染参数", "大小 / 画质 / 性能 / 物理 / 动态模糊 / 漫游",
                () -> startActivity(MainTabsActivity.intentForTab(this, 1)));

        /* ---------------- 模型库 ---------------- */
        page.section("已导入模型");

        emptyShell = page.currentCard();
        page.info("还没有导入模型。\n"
                + "· MMD 模型：把 .pmx / .pmd 与贴图打包成 zip 导入\n"
                + "· glTF 模型：.glb 是单文件，直接选它即可");

        listShell = page.newCard();
        listShell.setVisibility(View.GONE);

        page.actions("导入模型（.zip / .glb）", this::pickFile, null, null);

        /* ---------------- 子页 ---------------- */
        page.section("更多");

        page.nav("MMD 参数调整（高级）",
                "光照 / 曝光 / 饱和度 / 材质 / 相机 / 眼动 / 物理（改了立刻能看效果）",
                () -> startActivity(new Intent(this, MmdSettingsActivity.class)));

        page.nav("表情映射", "给每个表情指定编号，AI 就能精准选中",
                () -> {
                    Intent it = new Intent(this, PetExprActivity.class);
                    it.putExtra("modelUrl", lib.currentUrl());
                    startActivity(it);
                });

        page.nav("配件 / 装备", "开启模型自带的隐藏部件：耳朵 / 尾巴 / 鞋子 / 外套",
                () -> startActivity(new Intent(this, PetAccessoryActivity.class)));

        page.nav("VMD 动画", "导入 .vmd 动作，绑定待机 / 走路 / 摸头等",
                () -> startActivity(new Intent(this, PetVmdActivity.class)));

        page.nav("名称翻译", "把日文的配件/表情名翻成中文（只改显示）",
                () -> startActivity(new Intent(this, PetTranslateActivity.class)));

        /* ---------------- 诊断 ---------------- */
        page.section("诊断");

        final TextView diag = page.info("点下面的按钮查询运行中的桌宠状态。");
        diag.setTextIsSelectable(true);

        page.actions("查询渲染信息", () -> queryInfo(diag), null, null);

        setContentView(page.build());
        refresh();
    }

    /* ================= 渲染 ================= */

    private void refresh() {
        boolean mmd = lib.isMmdMode();
        modeView.setText(mmd ? "3D 模型" : "精灵图");

        String label = lib.currentLabel();
        statusView.setText(lib.currentUrl().isEmpty()
                ? "（还没有选择模型 —— 从下面的列表里点一个）"
                : (label.isEmpty() ? lib.currentUrl() : label)
                  + (mmd ? "" : "\n★ 当前渲染模式是「精灵图」，选中模型后会自动切到 3D"));

        List<ModelLibraryVm.Item> items = lib.items();
        if (items.isEmpty()) {
            emptyShell.setVisibility(View.VISIBLE);
            listShell.setVisibility(View.GONE);
            listShell.removeAllViews();
            builtSignature = "";
            return;
        }
        emptyShell.setVisibility(View.GONE);
        listShell.setVisibility(View.VISIBLE);

        StringBuilder sb = new StringBuilder();
        for (ModelLibraryVm.Item it : items) {
            sb.append(it.dirName).append((char) 1)
              .append(it.displayName).append((char) 1)
              .append(it.selected ? '1' : '0').append((char) 1)
              .append(it.active ? '1' : '0').append((char) 2);
        }
        String sig = sb.toString();
        if (sig.equals(builtSignature)) return;   // 没变化就不重建（保滚动位置）

        builtSignature = sig;
        rebuildRows(items);
    }

    private void rebuildRows(List<ModelLibraryVm.Item> items) {
        listShell.removeAllViews();
        for (final ModelLibraryVm.Item it : items) {
            listShell.addRow(buildRow(it));
        }
    }

    /**
     * 一行模型：点整行 = 选中；第二行的「改名」「删除」。
     *
     * ★ 为什么按钮不放在第一行右边（第一版就是这么做的）：
     *   模型名里常带很长的文件夹名（arona_mmd_unchangable / mmd_import_1791…），
     *   右边被两个按钮吃掉 140dp 后，名字会在单词中间断行
     *   （"arona_mmd_unchang / able"），看起来像乱码。
     *   现在名字独占一行、按钮放到 meta 那一行的右侧 —— 名字有完整宽度，
     *   列表高度也只多一点点。
     */
    private LinearLayout buildRow(final ModelLibraryVm.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        int pv = UiTheme.dp(this, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);

        /* 第一行：状态点 + 名字 */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        /* 使用中标记用一个小圆点，比"✓ 使用中"更省空间也更不抢眼 */
        TextView dot = UiTheme.text(this, it.selected ? "●" : "○", 15f,
                it.selected ? UiTheme.accent() : 0xFFC8CCCC, false);
        dot.setPadding(0, 0, UiTheme.dp(this, 8f), 0);
        head.addView(dot);

        TextView name = UiTheme.text(this, it.displayName, 16f,
                UiTheme.TEXT_PRIMARY, true);
        head.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(head);

        /* 第二行：大小/原名 + 两个操作（右对齐） */
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = UiTheme.dp(this, 6f);
        row.addView(foot, flp);

        StringBuilder meta = new StringBuilder();
        if (it.active) meta.append("使用中 · ");
        else if (it.selected) meta.append("已选择（当前是精灵图模式） · ");
        meta.append(humanSize(it.size));
        if (it.renamed) meta.append(" · 原名 ").append(it.dirName);
        TextView sub = UiTheme.text(this, meta.toString(), 12f,
                it.active ? UiTheme.accent() : UiTheme.TEXT_SECONDARY, false);
        sub.setLineSpacing(UiTheme.dp(this, 2f), 1f);
        foot.addView(sub, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rename = actionChip("改名", 0xFF3F6B6B);
        rename.setOnClickListener(v -> renameDialog(it));
        foot.addView(rename);

        TextView del = actionChip("删除", 0xFF9B3B34);
        del.setOnClickListener(v -> confirmDelete(it));
        foot.addView(del);

        row.setClickable(true);
        row.setOnClickListener(v -> onPick(it));
        return row;
    }

    private TextView actionChip(String text, int color) {
        TextView t = UiTheme.text(this, text, 13f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(UiTheme.dp(this, 11f), UiTheme.dp(this, 7f),
                UiTheme.dp(this, 11f), UiTheme.dp(this, 7f));
        t.setBackground(UiTheme.round(this, UiTheme.SUBTLE, 12f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = UiTheme.dp(this, 8f);
        t.setLayoutParams(lp);
        return t;
    }

    /* ================= 选择 ================= */

    private void onPick(ModelLibraryVm.Item it) {
        if (it.active) {
            toast("已经在用这个模型了");
            return;
        }
        if (!lib.pick(it)) {
            toast("切换失败：配置没能保存，请重试");
            return;
        }
        toast("正在切换到：" + it.displayName);
        refresh();
    }

    /* ================= 改名 ================= */

    /**
     * 改名（只改显示名）。
     *
     * ★ 不用 setMessage()：AlertDialog 的 setMessage 会和 setView 抢内容区
     *   （本项目在多选列表上踩过 → 复选框完全不显示）。说明文字放进 view 里。
     */
    private void renameDialog(final ModelLibraryVm.Item it) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(it.displayName);
        input.setSelectAllOnFocus(true);
        input.setHint("给这个模型起个名字");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextSize(16f);
        input.setPadding(UiTheme.dp(this, 4f), UiTheme.dp(this, 10f),
                UiTheme.dp(this, 4f), UiTheme.dp(this, 10f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = UiTheme.dp(this, 20f);
        box.setPadding(padH, UiTheme.dp(this, 8f), padH, 0);

        TextView tip = UiTheme.text(this,
                "只改显示名，不动文件（原文件夹：" + it.dirName + "）",
                12f, 0xFF8A8A93, false);
        tip.setLineSpacing(UiTheme.dp(this, 2f), 1f);
        tip.setPadding(0, 0, 0, UiTheme.dp(this, 6f));
        box.addView(tip);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText() == null ? "" : input.getText().toString().trim();
                    lib.rename(it, v);
                    builtSignature = "";        // 强制重建（名字变了）
                    refresh();
                    toast(v.isEmpty() || v.equals(it.dirName)
                            ? "已恢复原名" : ("已改名为：" + v));
                })
                .setNeutralButton("恢复原名", (d, w) -> {
                    lib.rename(it, "");
                    builtSignature = "";
                    refresh();
                    toast("已恢复原名");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ================= 删除 ================= */

    private void confirmDelete(final ModelLibraryVm.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("删除「" + it.displayName + "」？")
                .setMessage(it.selected
                        ? "这是当前选中的模型。删除后桌宠会暂时没有模型可显示，需要重新导入或另选一个。\n\n"
                          + "目录：" + it.dirName
                        : "删除后需要重新导入。\n\n目录：" + it.dirName)
                .setPositiveButton("删除", (d, w) -> {
                    String err = lib.delete(it);
                    if (err != null) { toast(err); return; }
                    builtSignature = "";
                    refresh();
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ================= 导入 ================= */

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;

        showImporting();

        /* ★ 必须放后台：大 zip 解压 + 逐文件拷贝要好几秒，
           放主线程会假死甚至 ANR（旧实现就是在 onActivityResult 里同步做的）。 */
        new Thread(() -> {
            final ModelLibraryVm.ImportOutcome out = lib.importFromUri(uri);
            runOnUiThread(() -> {
                dismissImporting();
                if (isFinishing() || isDestroyed()) return;
                onImported(out);
            });
        }, "pet-mmd-import").start();
    }

    private void onImported(ModelLibraryVm.ImportOutcome out) {
        if (!out.ok) {
            new AlertDialog.Builder(this)
                    .setTitle("导入失败")
                    .setMessage((out.error == null ? "未知原因" : out.error)
                            + (out.filesExtracted > 0
                               ? ("\n\n已解压 " + out.filesExtracted
                                  + " 个文件，跳过 " + out.filesSkipped + " 个。")
                               : ""))
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        boolean saved = lib.selectAfterImport(out.webUrl);
        builtSignature = "";
        refresh();

        if (!saved) {
            toast("导入成功，但配置保存失败 —— 请到列表里手动点一下它");
            return;
        }
        toast("导入成功（解压 " + out.filesExtracted + " 个文件），已切换过去");
    }

    private void showImporting() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiTheme.dp(this, 22f);
        box.setPadding(pad, pad, pad, pad);
        TextView t = UiTheme.text(this, "正在导入…\n大模型（几十 MB）可能需要几十秒。", 14f,
                UiTheme.TEXT_SECONDARY, false);
        t.setLineSpacing(UiTheme.dp(this, 3f), 1f);
        box.addView(t);

        importing = new AlertDialog.Builder(this)
                .setTitle("导入模型")
                .setView(box)
                .setCancelable(false)
                .create();
        importing.show();
    }

    private void dismissImporting() {
        try {
            if (importing != null && importing.isShowing()) importing.dismiss();
        } catch (Throwable ignored) {}
        importing = null;
    }

    /* ================= 诊断 ================= */

    private void queryInfo(final TextView target) {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) {
            target.setText("桌宠未运行 —— 先在主界面开启桌宠。");
            return;
        }
        target.setText("查询中…");
        svc.queryRenderInfo(info -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            target.setText(info == null ? "无数据"
                    : ("渲染信息\n" + info.replace(",", "\n")));
        }));
    }

    /* ================= 小工具 ================= */

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
