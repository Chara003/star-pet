package me.rerere.pet.ui.page;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import me.rerere.pet.core.CodexPetImporter;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.PetOverlayService;
import me.rerere.pet.ui.store.AppearanceVm;

/**
 * 桌宠素材库：导入 / 切换 / 导出 / 删除 .codex-pet.zip。
 *
 * 从旧 MainActivity 迁移过来的完整实现（含所有踩过的坑）：
 *   · 导入走 ACTION_GET_CONTENT + onActivityResult（不能用 openDocumentTree，
 *     用户选的是 zip 文件不是目录）；
 *   · 导入成功后立刻设为当前素材并重载桌宠，不用重启 App；
 *   · 删除"正在使用"的素材要特别提示（桌面会没角色）；
 *   · 导出到 getExternalFilesDir，路径要告诉用户（否则找不到文件）。
 *
 * ★ 数据写入一律走 AppearanceVm；素材文件操作走 CodexPetImporter。
 */
public final class SpriteLibraryPage {

    public interface Host {
        /** 素材列表 / 当前选择变了：重建页面。 */
        void rebuildLibrary();

        /** 需要弹文件选择器（由 Activity 处理 onActivityResult）。 */
        void requestImport();

        /** 提示。 */
        void toast(String msg);
    }

    private final Activity activity;
    private final PetStore store;
    private final AppearanceVm vm;
    private final Host host;

    public SpriteLibraryPage(Activity activity, PetStore store, AppearanceVm vm, Host host) {
        this.activity = activity;
        this.store = store;
        this.vm = vm;
        this.host = host;
    }

    private CodexPetImporter importer() {
        return new CodexPetImporter(new File(activity.getFilesDir(), "pets"));
    }

    public View build() {
        PetStore.Appearance a = store.appearance();
        SettingsPage page = new SettingsPage(activity);
        page.pageTitle("桌宠素材库",
                "一个 .codex-pet.zip 就是一个桌宠。导入后会保留在本机，随时可以切回去。");

        /* ---------------- 已导入 ---------------- */
        List<CodexPetImporter.InstalledInfo> pets = null;
        String err = null;
        try {
            pets = importer().listInstalled();
        } catch (Throwable t) {
            err = t.getClass().getSimpleName();
        }

        page.section("已导入（" + (pets == null ? 0 : pets.size()) + " 个）");

        if (err != null) {
            page.info("读取素材目录失败：" + err);
        } else if (pets == null || pets.isEmpty()) {
            page.info("还没有导入任何桌宠素材。点下面的「导入 .codex-pet.zip」开始。");
        } else {
            for (CodexPetImporter.InstalledInfo p : pets) {
                final CodexPetImporter.InstalledInfo info = p;
                final boolean active = p.id != null && p.id.equals(a.packageId);
                String sub = (active ? "使用中 · " : "")
                        + (p.valid ? ModelPickerPage.humanSize(p.sizeBytes)
                                   : "⚠ 素材文件缺失");
                /* 点一下 = 切换；右侧箭头暗示可进入操作菜单 */
                page.nav((active ? "● " : "○ ") + safeName(p), sub, () -> {
                    if (active) showMenu(info, true);
                    else usePet(info);
                });
            }
        }

        /* ---------------- 导入 ---------------- */
        page.section("导入 / 导出");
        page.nav("导入 .codex-pet.zip",
                "从文件选择器里选一个 zip 包，导入后自动设为当前桌宠",
                () -> host.requestImport());

        page.nav("导出当前桌宠",
                a.packageId == null || a.packageId.isEmpty()
                        ? "还没有选择桌宠" : (a.packageId + ".codex-pet.zip"),
                () -> exportCurrent());

        page.info("导入过的素材会保留在这里，可以直接切换或删除，不用重复导入。");

        return page.build();
    }

    private String safeName(CodexPetImporter.InstalledInfo p) {
        if (p.displayName != null && !p.displayName.trim().isEmpty()) return p.displayName;
        return p.id == null ? "(未命名)" : p.id;
    }

    /* ---------------- 切换 ---------------- */

    private void usePet(CodexPetImporter.InstalledInfo info) {
        if (!info.valid) {
            host.toast("该素材文件缺失，无法使用");
            return;
        }
        vm.commit(x -> x.packageId = info.id);
        /* 让正在运行的桌宠立刻换上，不重启服务（旧实现就是热重载） */
        PetOverlayService svc = PetOverlayService.current();
        if (svc != null) {
            try { svc.reloadPet(); } catch (Throwable ignored) {}
        } else {
            vm.startService();
        }
        host.toast("已切换到：" + safeName(info));
        host.rebuildLibrary();
    }

    /* ---------------- 菜单 ---------------- */

    private void showMenu(final CodexPetImporter.InstalledInfo info, boolean active) {
        final String[] options = {"导出这个素材", "删除", "取消"};
        /* ★ 必须用 Activity 的 Context 弹窗（applicationContext 会 BadTokenException） */
        new android.app.AlertDialog.Builder(activity)
                .setTitle(safeName(info))
                .setItems(options, (d, which) -> {
                    if (which == 0) exportPet(info.id);
                    else if (which == 1) confirmDelete(info, active);
                })
                .show();
    }

    private void confirmDelete(final CodexPetImporter.InstalledInfo info, final boolean active) {
        String msg = active
                ? "这是当前正在使用的桌宠，删除后桌面上将不再显示角色。确定删除吗？"
                : "删除后需要重新导入才能恢复，确定吗？";
        new android.app.AlertDialog.Builder(activity)
                .setTitle("删除素材")
                .setMessage(msg)
                .setPositiveButton("删除", (d, w) -> doDelete(info, active))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(CodexPetImporter.InstalledInfo info, boolean active) {
        try {
            importer().delete(info.id);
        } catch (Throwable t) {
            host.toast("删除失败：" + t.getClass().getSimpleName());
            return;
        }
        /* 删掉的是当前正在用的那个：清空选择，并让桌宠显示消失 */
        if (active) {
            vm.commit(a -> a.packageId = "");
            PetOverlayService svc = PetOverlayService.current();
            if (svc != null) {
                try { svc.clearPet(); } catch (Throwable ignored) {}
            }
        }
        host.toast("已删除");
        host.rebuildLibrary();
    }

    /* ---------------- 导出 ---------------- */

    private void exportCurrent() {
        PetStore.Appearance a = store.appearance();
        if (a.packageId == null || a.packageId.isEmpty()) {
            host.toast("还没有导入桌宠");
            return;
        }
        exportPet(a.packageId);
    }

    private void exportPet(String packageId) {
        File dir = new File(new File(activity.getFilesDir(), "pets"), packageId);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            host.toast("素材文件不存在");
            return;
        }
        File out = new File(activity.getExternalFilesDir(null), packageId + ".codex-pet.zip");
        boolean ok = zipDirectory(dir, out);
        host.toast(ok ? "已导出到：" + out.getAbsolutePath() : "导出失败");
    }

    /**
     * 把素材目录打成 zip。
     * ★ 跳过以 . 开头的条目：导入器会在目录里放 .staging-/.backup- 之类临时目录，
     *   打进去会让产出的 zip 无法再次导入（旧实现就踩过这个）。
     */
    private boolean zipDirectory(File dir, File out) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (File f : files) {
                if (f.getName().startsWith(".")) continue;
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- 导入结果处理（由 Activity 调用） ---------------- */

    /** 从 Activity.onActivityResult 调用：导入 URI 指向的 zip。 */
    public void handleImportResult(Uri uri) {
        if (uri == null) return;
        try {
            CodexPetImporter.InstalledPackage pkg = importer().importFrom(
                    activity.getContentResolver().openInputStream(uri), true);
            vm.commit(a -> a.packageId = pkg.id);
            PetOverlayService svc = PetOverlayService.current();
            if (svc != null) {
                try { svc.reloadPet(); } catch (Throwable ignored) {}
            } else {
                vm.startService();
            }
            host.toast("已导入：" + pkg.displayName);
            host.rebuildLibrary();
        } catch (CodexPetImporter.PetPackageException e) {
            host.toast("导入失败：" + e.code);
        } catch (Throwable t) {
            /* 低版本设备可能抛 Error（NoSuchMethodError 之类），必须挡 Throwable */
            android.util.Log.e("SpriteLibrary", "import failed", t);
            host.toast("导入失败：" + t.getClass().getSimpleName());
        }
    }
}
