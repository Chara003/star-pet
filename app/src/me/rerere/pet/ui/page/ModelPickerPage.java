package me.rerere.pet.ui.page;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import java.io.File;
import java.util.List;

import me.rerere.pet.core.CodexPetImporter;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.store.AppearanceVm;
import me.rerere.pet.ui.store.ModelLibraryVm;

/**
 * 素材/模型选择页（3D 模型 + 精灵图素材放在同一页，用分组区分）。
 *
 * ★ 为什么合并成一页：
 *   旧实现里"选 MMD 模型"在 PetMmdActivity、"选精灵图素材"在 MainActivity，
 *   而这两件事在语义上完全对称（都是"换一个能显示的东西"）。
 *
 * ★ 本轮（UI 第二轮）改动：
 *   「选择 / 改名 / 删除 3D 模型」全部改走 {@link ModelLibraryVm} ——
 *   原先这里的实现和 PetMmdActivity 各写一遍，已经出现行为分叉
 *   （删除当前模型后一边重启服务、一边不重启；"使用中"判定条件也不一致）。
 *   现在只有一份实现，两页显示的"使用中"必然一致。
 *
 * ★ 选中即生效：
 *   3D 模型 -> 写 mmdModelUrl + renderMode="mmd"
 *   精灵图  -> 写 packageId + renderMode="sprite"
 *   两者都会触发服务重启（两种渲染控件不是同一个东西，必须重建）。
 */
public final class ModelPickerPage {

    public interface Host {
        /** 选择已生效：需要重建页面（列表要刷新"使用中"标记）。 */
        void onSelectionChanged();
    }

    private final Activity activity;
    private final PetStore store;
    private final AppearanceVm vm;
    private final ModelLibraryVm lib;
    private final Host host;

    public ModelPickerPage(Activity activity, PetStore store, AppearanceVm vm, Host host) {
        this.activity = activity;
        this.store = store;
        this.vm = vm;
        this.lib = new ModelLibraryVm(activity);
        this.host = host;
    }

    public View build() {
        PetStore.Appearance a = store.appearance();
        boolean isMmd = "mmd".equals(a.renderMode);

        SettingsPage page = new SettingsPage(activity);
        page.pageTitle("模型与素材", "点一下就切换，并自动切到对应的渲染模式。");

        /* 当前用的是哪个（用户经常想确认，尤其改过名之后） */
        String curLabel = lib.currentLabel();
        if (!curLabel.isEmpty()) {
            page.valueRowWide("当前 3D 模型",
                    curLabel + (isMmd ? "" : "（当前渲染模式为精灵图）"));
        }
        if (a.packageId != null && !a.packageId.isEmpty()) {
            page.valueRowWide("当前桌宠素材", a.packageId);
        }

        /* ---------------- 3D 模型 ---------------- */
        page.section("3D 模型（.pmx / .pmd / .glb）");

        List<ModelLibraryVm.Item> items = lib.items();
        if (items.isEmpty()) {
            page.info("还没有导入 3D 模型。\n"
                    + "MMD 模型：把 .pmx/.pmd 与贴图打包成 zip 导入；\n"
                    + "glTF 模型：.glb 是单文件，直接选它即可。");
        } else {
            for (final ModelLibraryVm.Item it : items) {
                StringBuilder sub = new StringBuilder();
                if (it.active) sub.append("使用中 · ");
                else if (it.selected) sub.append("已选择 · ");
                sub.append(humanSize(it.size));
                if (it.renamed) sub.append(" · 原名 ").append(it.dirName);
                else sub.append(" · ").append(it.dirName);
                page.nav((it.selected ? "● " : "○ ") + it.displayName, sub.toString(),
                        () -> onPickModel(it));
                /* 注意：这里不弹菜单，点一下就选中 —— 改名/删除在「3D 模型管理」页，
                   那一页有完整的一行操作按钮。以前两页各有一套菜单，维护成本高。 */
            }
        }

        page.nav("管理 3D 模型（导入 / 改名 / 删除）",
                "导入新模型、给模型改名、删除已导入的",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.ui.PetMmdActivity.class)));

        /* ---------------- 精灵图素材 ---------------- */
        page.section("精灵图素材（.codex-pet.zip）");

        List<CodexPetImporter.InstalledInfo> pets = null;
        try {
            pets = new CodexPetImporter(new File(activity.getFilesDir(), "pets")).listInstalled();
        } catch (Throwable t) {
            page.info("读取素材列表失败：" + t.getClass().getSimpleName());
        }

        if (pets == null || pets.isEmpty()) {
            page.info("还没有导入精灵图素材。\n"
                    + "它是一个 zip 包，里面是分成多帧的 spritesheet。");
        } else {
            for (CodexPetImporter.InstalledInfo p : pets) {
                boolean active = !isMmd && p.id != null && p.id.equals(a.packageId);
                String sub = (active ? "使用中 · " : "")
                        + (p.valid ? humanSize(p.sizeBytes) : "⚠ 素材不完整");
                page.nav((active ? "● " : "○ ") + p.displayName, sub,
                        () -> pickSprite(p));
            }
        }

        page.nav("管理桌宠素材库", "导入 / 切换 / 导出 / 删除 .codex-pet.zip",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.ui.SpriteLibraryActivity.class)));

        page.section("说明");
        page.info("· 选 3D 模型会自动把渲染模式切到「3D 模型」。\n"
                + "· 选精灵图素材会自动切到「精灵图」。\n"
                + "· 切换会重启桌宠服务（两种渲染器不是同一个东西，必须重建）。");

        return page.build();
    }

    /* ---------------- 选择 ---------------- */

    /**
     * 选中 3D 模型。
     * ★ 必须回调 host.onSelectionChanged()：本页的"●/使用中"标记是构建时算的，
     *   不重建就看不到变化（用户会以为点了没反应）。
     */
    private void onPickModel(ModelLibraryVm.Item it) {
        if (it.active) { toast("已经在用这个模型了"); return; }
        if (!lib.pick(it)) { toast("切换失败：配置没能保存，请重试"); return; }
        toast("已切换到：" + it.displayName);
        if (host != null) host.onSelectionChanged();
    }

    private void pickSprite(CodexPetImporter.InstalledInfo p) {
        if (p.id == null || p.id.isEmpty()) {
            toast("素材 ID 无效");
            return;
        }
        if (!p.valid) {
            toast("这个素材不完整，建议重新导入");
            return;
        }
        vm.commit(a -> {
            a.packageId = p.id;
            a.renderMode = "sprite";
        });
        vm.restartService();
        toast("已切换到：" + p.displayName);
        if (host != null) host.onSelectionChanged();
    }

    /** 人类可读的文件大小（模型动辄几十 MB，显示字节数没意义）。 */
    static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
