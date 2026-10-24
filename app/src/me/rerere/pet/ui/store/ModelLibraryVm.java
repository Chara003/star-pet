package me.rerere.pet.ui.store;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import me.rerere.pet.data.MmdModelManager;
import me.rerere.pet.data.PetStore;

/**
 * 3D 模型库的唯一数据入口：列表 / 选择 / 改名 / 删除 / 导入。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么要把这四件事收拢到一个类
 * ══════════════════════════════════════════════════════════════
 *   「选择 / 改名 / 删除」原来在【两处】各写了一遍：
 *     · ui/PetMmdActivity（3D 模型管理页）
 *     · ui/page/ModelPickerPage（模型与素材页）
 *   两边行为已经出现分叉：
 *     · 删除当前使用中的模型后，PetMmdActivity 只清了 URL、**不重启服务**
 *       → 桌宠还挂着已删除的文件，白屏/花屏直到手动重启；
 *     · ModelPickerPage 的"使用中"判定多了一个 `renderMode=="mmd"`，
 *       于是精灵图模式下两个页面显示的"使用中"不一样；
 *     · 改名后 PetMmdActivity 只 refreshModels()，ModelPickerPage 走 Host 重建，
 *       两处都可能丢滚动位置。
 *   现在只有这一份实现，页面只负责画。
 *
 * ★ 别名只是【显示层】
 *   webUrl 是 /models/<文件夹名>/<文件>，改文件夹名会让已保存的 mmdModelUrl 失效
 *   （表现为桌宠白屏）。所以"改名"只写 PetStore 的别名表，一个字节都不动文件系统。
 */
public final class ModelLibraryVm {

    /** 列表里的一项（纯快照，页面不可变）。 */
    public static final class Item {
        public final String dirName;       // 文件夹名（别名的 key）
        public final String displayName;   // 别名或文件夹名
        public final String webUrl;
        public final long size;
        /** 是否就是 mmdModelUrl 指向的那个（与当前渲染模式无关）。 */
        public final boolean selected;
        /** 是否真的正在显示（selected 且渲染模式是 3D）。 */
        public final boolean active;
        public final boolean renamed;

        Item(MmdModelManager.ImportedModel m, String alias, String currentUrl, boolean mmdMode) {
            dirName = m.name;
            boolean hasAlias = alias != null && !alias.isEmpty();
            displayName = hasAlias ? alias : m.name;
            webUrl = m.webUrl == null ? "" : m.webUrl;
            size = m.size;
            renamed = hasAlias;
            selected = webUrl.equals(currentUrl == null ? "" : currentUrl);
            active = selected && mmdMode;
        }
    }

    /** 导入结果（成功带 webUrl 与文件名，失败带可读原因）。 */
    public static final class ImportOutcome {
        public final boolean ok;
        public final String webUrl;
        public final String displayName;
        public final String error;
        public final int filesExtracted;
        public final int filesSkipped;

        private ImportOutcome(boolean ok, String webUrl, String displayName,
                              String error, int filesExtracted, int filesSkipped) {
            this.ok = ok;
            this.webUrl = webUrl;
            this.displayName = displayName;
            this.error = error;
            this.filesExtracted = filesExtracted;
            this.filesSkipped = filesSkipped;
        }

        static ImportOutcome fail(String why) {
            return new ImportOutcome(false, "", "", why, 0, 0);
        }
    }

    private final Context app;
    private final PetStore store;
    private final AppearanceVm vm;

    public ModelLibraryVm(Context c) {
        app = c.getApplicationContext();
        store = new PetStore(app);
        vm = new AppearanceVm(app);
    }

    public PetStore store() { return store; }

    /* ---------------- 读 ---------------- */

    /** 当前渲染模式是不是 3D。 */
    public boolean isMmdMode() {
        return "mmd".equals(store.appearance().renderMode);
    }

    public String currentUrl() {
        String u = store.appearance().mmdModelUrl;
        return u == null ? "" : u;
    }

    /** 全部已导入模型（含别名解析与"使用中"标记）。 */
    public List<Item> items() {
        List<Item> out = new ArrayList<>();
        String cur = currentUrl();
        boolean mmd = isMmdMode();
        try {
            for (MmdModelManager.ImportedModel m : new MmdModelManager(app).listModels()) {
                out.add(new Item(m, store.modelAlias(m.name), cur, mmd));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 当前模型显示名（没选模型返回空串）。 */
    public String currentLabel() {
        String cur = currentUrl();
        if (cur.isEmpty()) return "";
        for (Item it : items()) if (it.selected) return it.displayName;
        return cur;
    }

    /* ---------------- 选择 ---------------- */

    /**
     * 选中某个模型：写配置 + 切到 3D 模式 + 重启服务。
     *
     * ★ 为什么必须重启服务，而不是只 applyAppearance()：
     *   精灵图与 3D 用的完全是两个渲染控件，参数下发再多也换不过来。
     */
    public boolean pick(Item item) {
        if (item == null || item.webUrl.isEmpty()) return false;
        vm.commit(a -> {
            a.mmdModelUrl = item.webUrl;
            a.renderMode = "mmd";
        });
        /* 回读校验：写盘失败时能立刻发现，而不是让用户以为切了 */
        if (!item.webUrl.equals(currentUrl())) return false;
        vm.restartService();
        return true;
    }

    /* ---------------- 改名 ---------------- */

    /** 改名（只改显示层）。传空字符串等于恢复原名。 */
    public void rename(Item item, String alias) {
        if (item == null) return;
        String v = alias == null ? "" : alias.trim();
        /* 和文件夹名完全一样就没有意义，直接当"恢复原名" */
        if (v.equals(item.dirName)) v = "";
        store.saveModelAlias(item.dirName, v);
    }

    /* ---------------- 删除 ---------------- */

    /**
     * 删除模型。
     *
     * ★ 删除当前选中的模型后必须【重启服务】：
     *   桌宠的 WebView 还挂着那个目录里的文件，删掉后再去读会白屏/花屏，
     *   直到手动重开桌宠。旧 PetMmdActivity 只清了 URL，没重启。
     *
     * @return 失败原因；成功返回 null
     */
    public String delete(Item item) {
        if (item == null) return "模型为空";
        boolean wasSelected = item.selected;
        boolean ok;
        try {
            ok = new MmdModelManager(app).delete(item.dirName);
        } catch (Throwable t) {
            return "删除失败：" + t.getClass().getSimpleName();
        }
        if (!ok) return "删除失败（目录可能已被系统清理）";

        /* ★ 顺手清掉别名 —— 否则同名文件夹再次导入会继承旧名字 */
        store.removeModelAlias(item.dirName);

        if (wasSelected) {
            vm.commit(a -> a.mmdModelUrl = "");
            vm.restartService();
        }
        return null;
    }

    /* ---------------- 导入 ---------------- */

    /**
     * 从系统文件选择器的 Uri 导入模型。**会阻塞**（大 zip 解压可达数秒到数十秒），
     * 调用方必须放到后台线程。
     *
     * ★★ 按【文件头魔数】判断类型，不看扩展名 ★★
     *   踩过两次的坑：
     *     ① 某些文件提供方返回的 DISPLAY_NAME 没有扩展名 → 判成 zip → 解压报
     *        「zip END header not found」；
     *     ② 临时文件叫 `mmd_pick_<时间戳>` 也没有扩展名，而 importSingleModel
     *        原来按文件名校验 → 永远「不是支持的模型格式」。
     *   所以：先落临时文件 → 读魔数 → 给临时文件补正确扩展名 → 再交给下游。
     */
    public ImportOutcome importFromUri(Uri uri) {
        if (uri == null) return ImportOutcome.fail("没有拿到文件");

        String pickName = displayNameOf(uri);
        File tmpAll = new File(app.getCacheDir(),
                "mmd_pick_" + System.currentTimeMillis());
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmpAll)) {
            if (in == null) throw new java.io.IOException("open failed");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable t) {
            tmpAll.delete();
            return ImportOutcome.fail("读取文件失败：" + t);
        }

        String kind = sniffKind(tmpAll);
        android.util.Log.i("PetMmd", "import: name=[" + pickName + "] kind=" + kind
                + " size=" + tmpAll.length());

        if ("unknown".equals(kind)) {
            tmpAll.delete();
            return ImportOutcome.fail("这个文件既不是 zip，也不是 3D 模型"
                    + "（支持 .zip / .glb / .gltf / .pmx / .pmd）"
                    + (pickName == null || pickName.isEmpty() ? "" : "\n文件名：" + pickName));
        }

        String ext = ".zip";
        if ("glb".equals(kind)) ext = ".glb";
        else if ("gltf".equals(kind)) ext = ".gltf";
        else if ("pmx".equals(kind)) ext = ".pmx";
        else if ("pmd".equals(kind)) ext = ".pmd";

        /* 给临时文件补扩展名（下游 importSingleModel 也会兜底，这是双保险） */
        String tmpBase = tmpAll.getAbsolutePath();
        if (!tmpBase.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
            File renamed = new File(tmpBase + ext);
            if (tmpAll.renameTo(renamed)) tmpAll = renamed;
            else android.util.Log.w("PetMmd", "临时文件改名失败，继续用原名：" + tmpAll);
        }

        /* DISPLAY_NAME 可能没有扩展名 → 按真实类型补，否则落盘后 findModelFile 认不出 */
        if (pickName != null && !pickName.isEmpty()
                && !MmdModelManager.isModelFileByName(pickName)
                && !"zip".equals(kind)) {
            pickName = pickName + ext;
            android.util.Log.i("PetMmd", "pickName 无扩展名，补成：" + pickName);
        }

        MmdModelManager mgr = new MmdModelManager(app);
        MmdModelManager.ImportResult res;

        if ("zip".equals(kind)) {
            File tmp = new File(app.getCacheDir(),
                    "mmd_import_" + System.currentTimeMillis() + ".zip");
            try {
                java.nio.file.Files.move(tmpAll.toPath(), tmp.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                if (!tmpAll.renameTo(tmp)) {
                    tmpAll.delete();
                    return ImportOutcome.fail("准备导入失败：" + t);
                }
            }
            res = mgr.importZipDetailed(tmp, pickName);
            tmp.delete();
        } else {
            /* 单文件（glb / gltf / pmx / pmd）→ 直接导入，不打 zip */
            res = mgr.importSingleModel(tmpAll, pickName);
            tmpAll.delete();
        }

        if (!res.ok) {
            String detail = res.error == null ? "未知原因" : res.error;
            android.util.Log.e("PetMmd", "import failed: " + detail
                    + " (extracted=" + res.filesExtracted + " skipped=" + res.filesSkipped + ")");
            return new ImportOutcome(false, "", "", detail,
                    res.filesExtracted, res.filesSkipped);
        }

        String webUrl = mgr.toWebUrl(res.modelAbsPath);
        if (webUrl == null || webUrl.isEmpty()) {
            return ImportOutcome.fail("导入失败：无法生成访问路径");
        }

        return new ImportOutcome(true, webUrl, pickName, null,
                res.filesExtracted, res.filesSkipped);
    }

    /**
     * 导入成功后选中它（写配置 + 切 3D + 重启）。
     * @return 配置写入是否成功
     */
    public boolean selectAfterImport(String webUrl) {
        if (webUrl == null || webUrl.isEmpty()) return false;
        vm.commit(a -> {
            a.mmdModelUrl = webUrl;
            a.renderMode = "mmd";
        });
        boolean ok = webUrl.equals(currentUrl());
        if (ok) vm.restartService();
        return ok;
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 按文件头魔数判断类型。
     * @return "glb" / "gltf" / "zip" / "pmx" / "pmd" / "unknown"
     */
    private static String sniffKind(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[16];
            int n = in.read(h);
            if (n < 4) return "unknown";
            /* glTF 二进制：'g' 'l' 'T' 'F' */
            if (h[0] == 0x67 && h[1] == 0x6C && h[2] == 0x54 && h[3] == 0x46) return "glb";
            /* ZIP：'P' 'K' 0x03/0x05/0x07 */
            if (h[0] == 0x50 && h[1] == 0x4B
                    && (h[2] == 0x03 || h[2] == 0x05 || h[2] == 0x07)) return "zip";
            /* 文本 glTF：以 '{' 开头（JSON） */
            if (h[0] == 0x7B) return "gltf";
            /* PMX：'P' 'M' 'X' ' '；PMD：'P' 'm' 'd' */
            if (h[0] == 0x50 && h[1] == 0x4D && h[2] == 0x58 && h[3] == 0x20) return "pmx";
            if (h[0] == 0x50 && h[1] == 0x6D && h[2] == 0x64) return "pmd";
        } catch (Throwable ignored) {}
        return "unknown";
    }

    /** 从 uri 取显示用的文件名。 */
    private String displayNameOf(Uri uri) {
        try (android.database.Cursor c = app.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String v = c.getString(i);
                    if (v != null && !v.isEmpty()) return v;
                }
            }
        } catch (Throwable ignored) { }
        String p = uri.getLastPathSegment();
        if (p == null) return "";
        int k = p.lastIndexOf('/');
        return k >= 0 ? p.substring(k + 1) : p;
    }
}
