package me.rerere.pet.ui.page;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.ModelPickerActivity;
import me.rerere.pet.ui.PetMmdActivity;
import me.rerere.pet.ui.store.AppearanceVm;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.SeekRowView;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * 主界面：按 renderMode 显示两套完全不同的内容。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么要按渲染模式分叉
 * ══════════════════════════════════════════════════════════════
 *   精灵图模式（sprite）和 3D 模型模式（mmd）的"可调项"几乎不重叠：
 *     sprite: 当前素材、大小、帧率、待机随机动作、触摸分区
 *     mmd   : 模型入口、大小、画质/性能、物理、动态模糊、AI表情、漫游
 *   放在同一页会有 6~8 行"对当前模式无效"的控件，用户根本分不清哪个起作用。
 *   所以按模式**立即重建列表**（不重启 Activity）。
 *
 * ★ 所有数据读写都走 {@link AppearanceVm}，页面里没有任何 store.save*。
 */
public final class HomePage {

    public interface Host {
        /** 渲染模式切换了：需要重建整页（两套内容完全不同）。 */
        void onRenderModeChanged();

        /** 需要提示用户（Toast）。 */
        void toast(String msg);

        /** 跳到「高级设置」那一页（不是新开 Activity，是切底栏 Tab）。 */
        void switchToAdvanced();

        /** 素材/模型换了：需要重建主界面（"当前模型"那行要更新）。 */
        void onSelectionChanged();
    }

    private final Activity activity;
    private final Host host;
    private final AppearanceVm vm;

    /**
     * @param scrollY 恢复的滚动位置（重建时保留）
     */
    public HomePage(Activity activity, Host host, AppearanceVm vm) {
        this.activity = activity;
        this.host = host;
        this.vm = vm;
    }

    public View build(int scrollY) {
        final AppearanceVm.Snapshot s = vm.snapshot();
        final SettingsPage page = new SettingsPage(activity);
        page.pageTitle("星盏桌宠", "桌宠开关、外观与触摸分区。");

        /* ---------------- 桌宠状态 ---------------- */
        page.section("桌宠状态");
        page.valueRowWide("当前桌宠", vm.petLabel());

        page.toggle("启用桌宠", s.enabled
                        ? "正在桌面上显示" : "已关闭，桌面不显示",
                s.enabled,
                (row, on) -> {
                    vm.setEnabled(on);
                    /* ★ 副标题描述的是【当前状态】，切换后必须跟着改。
                       不写这一行的话，用户关掉开关后副标题还写着"正在桌面上显示"，
                       要退出重进本页才更新（截图里就是这样，一眼看着像坏了）。 */
                    row.setSubtitle(on ? "正在桌面上显示" : "已关闭，桌面不显示");
                    host.toast(on ? "桌宠已开启" : "桌宠已关闭");
                });

        /* 渲染模式：两个 Tab 式按钮比 Switch 语义更清楚 */
        page.custom(renderModeRow(s), true);

        /* ---------------- 触摸分区（两种模式共用） ----------------
           ★ 别被"精灵图/3D"误导：触摸分区是【悬浮窗坐标的相对位置】，
             判断头/身/脚靠的是点击点落在窗口高度的哪个百分比，
             跟用什么渲染没关系 —— 3D 模型同样吃这两条边界线
             （PetOverlayService 里 setGuideLines 也是按窗口百分比画的）。
             所以它必须放在通用区，不能塞进 sprite 分支（实测确认过）。 */
        page.section("触摸分区");
        page.info("拖动滑块时，桌宠上会显示红色分界线帮助你校准："
                + "第一条线以上是头，两条线之间是身体，以下是脚。");
        page.seek("头部区域", "这条线以上算摸头",
                s.headBoundary * 100f, 5f, 90f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        vm.preview(a -> a.headBoundary = v / 100f);
                        vm.showGuideLines();
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.headBoundary = v / 100f);
                        vm.hideGuideLines();
                    }
                });
        page.seek("身体区域结束", "这条线以下算摸脚",
                s.bodyBoundary * 100f, 10f, 99f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        vm.preview(a -> a.bodyBoundary = v / 100f);
                        vm.showGuideLines();
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.bodyBoundary = v / 100f);
                        vm.hideGuideLines();
                    }
                });

        /* ---------------- 按模式分叉 ---------------- */
        if (s.isMmd()) buildMmdSection(page, s);
        else buildSpriteSection(page, s);

        /* ---------------- 其他 ---------------- */
        page.section("其他");

        /* ★ 旧版主页（MainActivity）只保留这一个入口，位置就在"授予悬浮窗权限"上面。
           原因：它是 AI 提供商/记忆/素材导入这些还没迁移完的表单的宿主，
           留一个兜底入口就够了 —— 每个功能各放一个跳转会把新页面变成"链接大全"。 */
        page.nav("旧版设置页（兜底）",
                "AI 提供商、桌宠素材导入导出等尚未迁移的旧界面",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.MainActivity.class)));

        page.nav("授予悬浮窗权限", "没有这个权限桌宠无法显示在桌面上",
                () -> {
                    try {
                        activity.startActivity(new Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + activity.getPackageName())));
                    } catch (Exception ignored) {}
                });

        View v = page.build();
        final int y = scrollY;
        v.post(() -> {
            if (v instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) v).scrollTo(0, y);
            }
        });
        return v;
    }

    /* ================= 渲染模式切换行 ================= */

    private LinearLayout renderModeRow(final AppearanceVm.Snapshot s) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(activity, UiTheme.PAD_H);
        int pv = UiTheme.dp(activity, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);

        TextView title = UiTheme.title(activity, "渲染模式");
        row.addView(title);
        TextView sub = UiTheme.subtitle(activity, "精灵图省电；3D 模型支持动作、物理与配件");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiTheme.dp(activity, 4f);
        row.addView(sub, slp);

        /* 两个分段按钮 */
        LinearLayout seg = new LinearLayout(activity);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.topMargin = UiTheme.dp(activity, 12f);
        row.addView(seg, segLp);

        final TextView sprite = segmentButton("精灵图", !s.isMmd());
        final TextView mmd = segmentButton("3D 模型", s.isMmd());
        seg.addView(sprite, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = UiTheme.dp(activity, 10f);
        seg.addView(mmd, mlp);

        sprite.setOnClickListener(v -> {
            if (!s.isMmd()) return;
            vm.setRenderMode("sprite");
            host.onRenderModeChanged();
        });
        mmd.setOnClickListener(v -> {
            if (s.isMmd()) return;
            vm.setRenderMode("mmd");
            host.onRenderModeChanged();
        });
        return row;
    }

    /** 分段按钮：选中 = 深青底白字，未选中 = 浅灰底深字。 */
    private TextView segmentButton(String text, boolean selected) {
        TextView b = UiTheme.text(activity, text, 15f,
                selected ? 0xFFFFFFFF : UiTheme.TEXT_PRIMARY, true);
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackground(UiTheme.round(activity,
                selected ? UiTheme.accent() : UiTheme.SUBTLE, 16f));
        b.setPadding(UiTheme.dp(activity, 14f), UiTheme.dp(activity, 11f),
                UiTheme.dp(activity, 14f), UiTheme.dp(activity, 11f));
        b.setClickable(true);
        return b;
    }

    /* ================= 精灵图模式 ================= */

    private void buildSpriteSection(final SettingsPage page, final AppearanceVm.Snapshot s) {
        page.section("外观（精灵图）");

        page.seek("大小", "占屏幕高度的比例", s.scale * 100f, 10f, 200f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        /* 拖动中：只下发预览，不落盘（避免几十次 IO） */
                        vm.commitDebounced(a -> a.scale = v / 100f);
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.scale = v / 100f);
                    }
                });

        page.seek("帧率", "精灵图每秒刷新次数。越高越流畅，也越费电。",
                s.fps, 4f, 12f, " FPS",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        vm.preview(a -> a.fps = Math.round(v));
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.fps = Math.round(v));
                    }
                });

        page.toggle("待机时随机动作",
                "开启后，桌宠一段时间没人碰就会自己做随机动作。",
                s.idleRandom,
                (row, on) -> vm.commit(a -> a.idleRandomEnabled = on));

        /* ---------------- 素材 ---------------- */
        page.section("桌宠素材");
        page.nav("切换桌宠素材", "这台机上已导入的 .codex-pet.zip",
                () -> activity.startActivity(new Intent(activity, ModelPickerActivity.class)));
        page.nav("管理素材库", "导入 / 导出 / 删除 .codex-pet.zip",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.ui.SpriteLibraryActivity.class)));
    }

    /* ================= 3D 模型模式 ================= */
    /**
     * 主界面在 3D 模式下【只放"用哪个"和"开没开"】，具体参数全部在高级设置。
     *
     * ★ 去重原则（明确要求"把重复的开关删掉"）：
     *   之前这里和高级设置都有 物理 / GPU / 渲染倍率 / 帧率上限 / 大小 / 漫游，
     *   同一项在两页各有一个控件，改完不知道以哪个为准，纯噪音。
     *   现在分工：
     *     主界面  = 换模型、换素材、开关桌宠、看当前状态
     *     高级设置 = 所有参数（大小/画质/性能/物理/漫游）
     */
    private void buildMmdSection(final SettingsPage page, final AppearanceVm.Snapshot s) {
        page.section("模型");

        page.nav("当前模型", s.mmdModelUrl.isEmpty()
                        ? "还没有选择模型，点这里去导入"
                        : vm.currentModelLabel(),
                () -> activity.startActivity(new Intent(activity, ModelPickerActivity.class)));

        page.nav("模型与素材", "在这台机上已导入的 3D 模型 / 精灵图素材",
                () -> activity.startActivity(new Intent(activity, ModelPickerActivity.class)));

        page.section("当前状态");
        page.valueRow("物理模拟", s.physics ? "已开启" : "已关闭");
        page.valueRow("GPU 加速", s.gpuAccel ? "已开启" : "已关闭");
        page.valueRow("渲染倍率", Math.round(s.renderScale * 100) + "%");
        page.valueRow("帧率上限", s.fpsCap <= 0 ? "不限帧" : (s.fpsCap + " fps"));
        page.valueRow("显示大小", Math.round(s.mmdScale * 100) + "%");
        page.valueRow("自动漫游", s.roamEnabled
                ? ("已开启 · 速度 " + s.roamSpeed) : "已关闭");

        page.nav("调整这些参数", "大小 / 画质 / 性能 / 物理 / 动态模糊 / 漫游",
                () -> host.switchToAdvanced());
    }

}
