package me.rerere.pet.ui.page;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.McpEditActivity;
import me.rerere.pet.ui.PetAccessoryActivity;
import me.rerere.pet.ui.PetExprActivity;
import me.rerere.pet.ui.PetInteractActivity;
import me.rerere.pet.ui.PetMmdActivity;
import me.rerere.pet.ui.PetTranslateActivity;
import me.rerere.pet.ui.PetVmdActivity;
import me.rerere.pet.ui.store.AppearanceVm;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.SeekRowView;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * 高级设置：把旧 PetMmdActivity（3D 参数）+ PetSettingsActivity（AI 侧参数）
 * 里属于"渲染 / 画质 / 性能 / 物理 / 外观组件"的部分合并到这里。
 *
 * ★ 迁移原则：
 *   · 只搬"纯参数"（滑块/开关），这些能用 AppearanceVm 统一管好；
 *   · 复杂交互（导入模型、AI 表情编辑、VMD 绑定）保留独立 Activity，
 *     这里只放入口 —— 一次性重写它们风险远大于收益（都是踩过坑才改对的）。
 */
public final class AdvancedPage {

    public interface Host {
        /** 参数改完需要重建本页（刷新选中态/读数）。 */
        void rebuildAdvanced();
    }

    private final Activity activity;
    private final AppearanceVm vm;
    private final Host host;

    public AdvancedPage(Activity activity, AppearanceVm vm, Host host) {
        this.activity = activity;
        this.vm = vm;
        this.host = host;
    }

    /** 当前模型是否 glTF（.glb/.gltf）—— 动态模糊两套参数按它区分。 */
    private static boolean isGltf(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(java.util.Locale.ROOT);
        return l.endsWith(".glb") || l.endsWith(".gltf");
    }

    /** 长度值 -> 人类可读的拖影时长 + 强度提示（照搬旧 PetMmdActivity.fmtTrail）。 */
    private static String fmtTrail(int len) {
        double t = Math.pow(Math.max(0, Math.min(100, len)) / 100.0, 2) * 0.125;
        String tip = (t < 0.006) ? "几乎看不出" : (t < 0.02 ? "很轻微"
                : (t < 0.045 ? "轻微（推荐）" : (t < 0.085 ? "明显" : "最长")));
        return String.format(java.util.Locale.ROOT, "%.3f 秒 · %s", t, tip);
    }

    public View build() {
        final AppearanceVm.Snapshot s = vm.snapshot();
        SettingsPage page = new SettingsPage(activity);
        page.pageTitle("高级设置", "3D 模型的渲染、画质、性能与外观组件。");

        /* ---------------- 画质 ---------------- */
        page.section("画质");

        page.custom(aaRow(s), true);
        page.info("两倍 = 4 倍像素，四倍 = 16 倍像素。四倍仅建议在小尺寸桌宠上开启。");

        page.seek("渲染倍率", "1.0 = 按设备像素比原生渲染。大模型卡就调低",
                s.renderScale * 100f, 35f, 100f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        vm.preview(a -> a.renderScale = v / 100f);
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.renderScale = v / 100f);
                    }
                });

        /* ---------------- 性能 ---------------- */
        page.section("性能");

        page.toggle("GPU 加速（高性能渲染）",
                "开启后使用高性能 WebGL 上下文。修改后会自动重新加载模型页面",
                s.gpuAccel,
                (row, on) -> {
                    vm.commit(a -> a.gpuAccel = on);
                    toast("已切换，模型页面会自动重新加载");
                });

        page.toggle("动态分辨率（自动）",
                "卡的时候自动降低渲染分辨率、流畅后慢慢升回来",
                s.perfAuto,
                (row, on) -> vm.commit(a -> a.perfAuto = on));

        page.toggle("物理模拟",
                "关掉可省掉每帧的刚体解算，MMD 重模型卡顿时最有效。\n"
                        + "头发/裙子不再摆动，但帧率通常明显改善。",
                s.physics,
                (row, on) -> {
                    vm.commit(a -> a.physicsEnabled = on);
                    /* 物理是热切换，走轻量接口，不整窗重算 */
                    vm.applyPhysicsNow();
                });

        /* ★ 帧率上限：20~91，91 = 不限帧（内部存 0）。
           ---- 为什么是 91 而不是"0 表示不限" ----
           滑块只有一根轴，需要一个"比 90 再往右一格"的位置表达"不限"。
           91 就是那一格：拖到最右 = 帧率无限制。
           ★ 旧存档里 fpsCap=0 要映射到 91（而不是被归一到 90，那等于偷偷降帧）。 */
        SeekRowView fpsRow = page.seek("帧率上限",
                "最右一格 = 不限帧。发热明显时压到 30",
                s.fpsCap <= 0 ? 91f : s.fpsCap, 20f, 91f, null,
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        int cap = Math.round(v) >= 91 ? 0 : Math.round(v);
                        /* 拖动中实时生效，且只 eval 一条 setPerf（不碰窗口/取景） */
                        vm.commit(a -> a.fpsCap = cap);
                        vm.applyFpsCapNow();
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        int cap = Math.round(v) >= 91 ? 0 : Math.round(v);
                        vm.commit(a -> a.fpsCap = cap);
                        vm.applyFpsCapNow();
                    }
                });
        /* ★ 91 只是一个"滑块哨兵值"，绝不能把它当 fps 显示出来。
           拖到最右要显示"不限帧"（旧 PetMmdActivity 也是这么做的）。 */
        fpsRow.setFormatter(v -> Math.round(v) >= 91 ? "不限帧" : (Math.round(v) + " fps"));

        page.info("MMD 重模型最常见的性能瓶颈是物理（每个刚体/约束都要在 wasm 里解算）。"
                + "掉帧时先关物理，再降渲染倍率，最后才降帧率上限。");

        /* ---------------- 动态模糊 ---------------- */
        buildMotionBlurSection(page, s);

        /* ---------------- 显示大小 ---------------- */
        page.section("显示");

        page.seek("3D 模型大小", "占屏幕高度的比例（拖动时实时生效）",
                s.mmdScale * 100f, 5f, 300f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        vm.preview(a -> a.mmdScale = v / 100f);
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.mmdScale = v / 100f);
                    }
                });

        /* ---------------- 自动漫游 ---------------- */
        page.section("自动漫游");

        page.toggle("让桌宠自己走动",
                "每 30 秒掷一次骰子：掷中才走一段（2.5~7 秒）。"
                        + "3D 模式会播绑定的走路动画并侧身朝行进方向；"
                        + "启用期间会强制关闭相机自转",
                s.roamEnabled,
                (row, on) -> {
                    /* ★ 必须走 setRoamEnabled：它除了写配置，
                       还会重排计时器 + 关掉相机自转（否则"开了但不动/打架"）。 */
                    vm.setRoamEnabled(on);
                    toast(on ? "已开启自动漫游（自转已强制关闭）" : "已关闭自动漫游");
                });
        page.seek("走动速度", "每秒移动的像素数（越大走得越快）", s.roamSpeed, 1f, 12f, "",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        vm.preview(a -> a.roamSpeed = Math.round(v));
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.roamSpeed = Math.round(v));
                    }
                });
        page.seek("走动概率", "每 30 秒掷一次骰子：掷中才走一段（2.5~7 秒）",
                s.roamChance, 0f, 100f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        vm.preview(a -> a.roamChance = Math.round(v));
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        vm.commit(a -> a.roamChance = Math.round(v));
                        /* ★ 松手才重排计时：拖动中每帧重启会让它一直不走 */
                        vm.restartRoamNow();
                    }
                });

        /* ---------------- 模型 ---------------- */
        page.section("模型与动作");
        page.nav("3D 模型管理", "导入 / 选择 / 改名 / 删除模型",
                () -> activity.startActivity(new Intent(activity, PetMmdActivity.class)));

        /* ★ 这个入口在第一版重构里丢了：它是通往 MmdSettingsActivity 的
           （光照 / 曝光 / 饱和度 / 材质 / 相机 / 自转 / 眼动 / 物理）。
           注意它和「3D 模型管理」不是一回事：
             · 3D 模型管理 = 选模型、导入、性能参数（原生页面）
             · MMD 参数调整 = WebView 里的实时渲染面板（改了立刻能看效果）
           用户当时就是找不到这个按钮。 */
        page.nav("MMD 参数调整（高级）",
                "光照 / 曝光 / 饱和度 / 材质 / 相机 / 眼动 / 物理",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.ui.MmdSettingsActivity.class)));
        page.nav("表情映射", "给模型表情起中文名、调触发",
                () -> activity.startActivity(new Intent(activity, PetExprActivity.class)));
        page.nav("配件装备", "耳朵 / 尾巴 / 鞋子等隐藏部件",
                () -> activity.startActivity(new Intent(activity, PetAccessoryActivity.class)));
        page.nav("VMD 动画", "待机 / 走路 / 摸头等动作绑定",
                () -> activity.startActivity(new Intent(activity, PetVmdActivity.class)));
        page.nav("名称翻译", "把日文部件名翻成中文",
                () -> activity.startActivity(new Intent(activity, PetTranslateActivity.class)));

        /* ---------------- 交互 ---------------- */
        page.section("交互");
        page.nav("互动设置", "点击 / 拖拽 / 随机对话的触发方式",
                () -> activity.startActivity(new Intent(activity, PetInteractActivity.class)));

        /* ---------------- 其他 ---------------- */
        page.section("其他");
        page.nav("AI 身份与温度", "名称 / 头像 / 温度 / 思考深度",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.ui.PetSettingsActivity.class)));

        return page.build();
    }

    /** 抗锯齿三档（关闭 / 两倍 / 四倍）。 */
    private LinearLayout aaRow(final AppearanceVm.Snapshot s) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(activity, UiTheme.PAD_H);
        int pv = UiTheme.dp(activity, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);

        row.addView(UiTheme.title(activity, "抗锯齿（分辨率）"));
        TextView sub = UiTheme.subtitle(activity, "以显示大小的倍数渲染，边缘更平滑；倍数越高越耗电");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiTheme.dp(activity, 4f);
        row.addView(sub, slp);

        LinearLayout seg = new LinearLayout(activity);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.topMargin = UiTheme.dp(activity, 12f);
        row.addView(seg, segLp);

        final int[] levels = {0, 2, 4};
        final String[] labels = {"关闭", "两倍", "四倍"};
        for (int i = 0; i < levels.length; i++) {
            final int level = levels[i];
            boolean active = s.mmdAntiAlias == level;
            TextView b = UiTheme.text(activity, labels[i], 14f,
                    active ? 0xFFFFFFFF : UiTheme.TEXT_PRIMARY, active);
            b.setGravity(Gravity.CENTER);
            b.setBackground(UiTheme.round(activity,
                    active ? UiTheme.accent() : UiTheme.SUBTLE, 14f));
            b.setPadding(UiTheme.dp(activity, 6f), UiTheme.dp(activity, 11f),
                    UiTheme.dp(activity, 6f), UiTheme.dp(activity, 11f));
            b.setClickable(true);
            b.setOnClickListener(v -> {
                vm.commit(a -> a.mmdAntiAliasLevel = level);
                /* ★ applyAppearance() 内部就是 mmdView.setAntiAliasLevel(...)，热切换。
                   ★★ 绝对不能调 activity.recreate()：那会重建整个 MainTabsActivity，
                      用户在"高级设置"页点一下就被弹回"主界面"（实测反馈的 bug）。
                   ★ 但也不能什么都不做：三个按钮的选中态是构建时算的，
                     不重建页面就会"看起来没反应"（实测"点不动"）。
                     所以只重建【这一页】(rebuildAdvanced)，不碰 Activity。 */
                vm.applyToService();
                toast(level == 0 ? "已关闭抗锯齿"
                        : (level == 2 ? "抗锯齿：两倍分辨率渲染" : "抗锯齿：四倍分辨率渲染"));
                if (host != null) host.rebuildAdvanced();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = UiTheme.dp(activity, 8f);
            seg.addView(b, lp);
        }
        return row;
    }

    /**
     * 动态模糊。
     *
     * ★★ 为什么要"按模型类型分两套"（用户曾专门提过）★★
     *   MMD 与 glTF 的尺寸、动作速度、面数都不一样，合适的拖影长度/质量也不同。
     *   所以字段分成 mbMmd* 与 mbGlb* 两组，这里按当前模型类型读写对应的那套
     *   （和旧 PetMmdActivity 的做法完全一致）。
     *
     * ★ 长度用平方映射：len=50 已经是 0.125s 的明显拖影，
     *   线性映射会让滑杆前半段几乎无效（见 fmtTrail）。
     */
    private void buildMotionBlurSection(final SettingsPage page, final AppearanceVm.Snapshot s) {
        final boolean glb = isGltf(s.mmdModelUrl);

        page.section("动态模糊");

        page.info("当前为「" + (glb ? "GLB 模型" : "MMD 模型") + "」参数"
                + " —— 两套各自独立保存，切模型会自动切换。\n"
                + "拖影是时域累积：长度 = 拖影持续时间，质量 = 内部缓冲分辨率"
                + "（越高越清晰、越费 GPU）。");

        page.toggle("启用动态模糊", "移动时拖出残影，快速动作看起来更流畅",
                glb ? s.mbGlbOn : s.mbMmdOn,
                (row, on) -> {
                    vm.commit(a -> {
                        if (glb) a.mbGlbOn = on; else a.mbMmdOn = on;
                    });
                    vm.applyMotionBlurNow();
                });

        SeekRowView lenRow = page.seek("长度", null,
                glb ? s.mbGlbLen : s.mbMmdLen, 0f, 100f, null,
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        int len = Math.round(v);
                        vm.preview(a -> {
                            if (glb) a.mbGlbLen = len; else a.mbMmdLen = len;
                        });
                        vm.applyMotionBlurNow();
                        row.setFormatter(x -> fmtTrail(Math.round(x)));
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        int len = Math.round(v);
                        vm.commit(a -> {
                            if (glb) a.mbGlbLen = len; else a.mbMmdLen = len;
                        });
                        vm.applyMotionBlurNow();
                    }
                });
        lenRow.setFormatter(v -> fmtTrail(Math.round(v)));

        page.seek("质量", "内部缓冲分辨率。越高越清晰、越费 GPU",
                glb ? s.mbGlbQual : s.mbMmdQual, 20f, 100f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        int q = Math.round(v);
                        vm.preview(a -> {
                            if (glb) a.mbGlbQual = q; else a.mbMmdQual = q;
                        });
                        vm.applyMotionBlurNow();
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        int q = Math.round(v);
                        vm.commit(a -> {
                            if (glb) a.mbGlbQual = q; else a.mbMmdQual = q;
                        });
                        vm.applyMotionBlurNow();
                    }
                });

        page.info("提示：拖影是「上一帧」的累积，帧率越低每帧位移越大，"
                + "同样的长度看起来会更夸张。");
    }

    private void toast(String m) {
        android.widget.Toast.makeText(activity, m, android.widget.Toast.LENGTH_SHORT).show();
    }
}
