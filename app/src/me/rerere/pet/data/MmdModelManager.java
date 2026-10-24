package me.rerere.pet.data;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MMD 模型管理：导入含 .pmx/.pmd 的 .zip，解压到私有目录，
 * 再由 WebViewAssetLoader 的 /models/ 路径暴露给页面。
 *
 * 实现思路来自一个独立的 three.js 验证工程（已跑通后移植过来）。
 * 设计要点：
 *  - 解压在原生做，不引 JSZip（省依赖，zip slip 防护更可控）
 *  - 落盘 getFilesDir()/mmd_models/<名字>/，重启后仍在
 *  - 只接受白名单扩展名，防止任意文件写入
 */
public final class MmdModelManager {

    private static final String TAG = "PetMmd";
    /**
     * zip 内允许解压的扩展名。
     * ★ .glb / .gltf 是 glTF 模型（.gltf + 外置 .bin + 贴图也能一起打包进 zip）。
     */
    private static final String[] ALLOWED_EXT = {
            ".pmx", ".pmd", ".vmd", ".glb", ".gltf", ".bin",
            ".png", ".jpg", ".jpeg", ".bmp", ".tga", ".webp"
    };

    /** 这些扩展名都算"模型文件"。 */
    private static boolean isModelName(String lowerName) {
        return lowerName.endsWith(".pmx") || lowerName.endsWith(".pmd")
                || lowerName.endsWith(".glb") || lowerName.endsWith(".gltf");
    }

    /** 是不是模型文件（供外部判断）。 */
    public static boolean isModelFileByName(String name) {
        return name != null && isModelName(name.toLowerCase(java.util.Locale.ROOT));
    }

    private final Context ctx;

    public MmdModelManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 解压根目录，WebViewAssetLoader.InternalStoragePathHandler 需要它。 */
    public File modelsRoot() {
        File d = new File(ctx.getFilesDir(), "mmd_models");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 已导入模型：显示名 -> 模型绝对路径。 */
    public static final class ImportedModel {
        public final String name;
        public final String absPath;
        public final String webUrl;
        public final long size;

        ImportedModel(String name, String absPath, String webUrl, long size) {
            this.name = name;
            this.absPath = absPath;
            this.webUrl = webUrl;
            this.size = size;
        }
    }

    /** 导入结果：成功带路径，失败带可读原因。 */
    public static final class ImportResult {
        public final boolean ok;
        public final String modelAbsPath;
        public final String error;
        /** 解压出的文件数（便于判断 zip 结构）。 */
        public final int filesExtracted;
        public final int filesSkipped;

        ImportResult(boolean ok, String modelAbsPath, String error,
                     int filesExtracted, int filesSkipped) {
            this.ok = ok;
            this.modelAbsPath = modelAbsPath;
            this.error = error;
            this.filesExtracted = filesExtracted;
            this.filesSkipped = filesSkipped;
        }

        static ImportResult fail(String error, int extracted, int skipped) {
            return new ImportResult(false, null, error, extracted, skipped);
        }
    }

    /**
     * 导入一个 zip。
     * 失败时返回带原因的结果，不再静默返回 null
     * （文档 §11.6 警告的"静默降级"会让排查成本极高）。
     */
    public ImportResult importZipDetailed(File zip) {
        return importZipDetailed(zip, null);
    }

    /**
     * 导入一个 zip，并指定一个【可读的目录名】。
     *
     * ★ 为什么需要这个重载（"导入后列表里全是 mmd_import_1791…"）：
     *   调用方必须先落一个临时 zip，文件名是 `mmd_import_<时间戳>.zip`
     *   （原文件名拿不到 / 也可能和别的临时文件冲突）。旧实现直接用这个
     *   临时名当目录名，于是导入完之后模型列表里显示的全是时间戳，
     *   用户只能一个个手动改名。
     *   现在把用户选择时的原名传进来，目录名 = mmd_import_<时间戳>_<原名>：
     *   既保留唯一性（同一份 zip 可以重复导入两份），又能一眼认出来。
     *
     * @param preferredName 用户选文件时的显示名（可含扩展名，可为 null）
     */
    public ImportResult importZipDetailed(File zip, String preferredName) {
        if (zip == null || !zip.isFile()) {
            return ImportResult.fail("文件不存在", 0, 0);
        }
        String base = sanitize(zip.getName());
        String pretty = preferredName == null ? "" : preferredName.trim();
        if (!pretty.isEmpty()) {
            /* 去掉扩展名（目录名里再带个 .zip 很怪） */
            int dot = pretty.lastIndexOf('.');
            if (dot > 0) pretty = pretty.substring(0, dot);
            pretty = sanitizeDirName(pretty);
            if (!pretty.isEmpty()) {
                base = "mmd_import_" + System.currentTimeMillis() + "_" + pretty;
            }
        }
        File dest = new File(modelsRoot(), base);
        deleteRecursively(dest);
        if (!dest.mkdirs() && !dest.isDirectory()) {
            return ImportResult.fail("无法创建目录 " + dest.getAbsolutePath(), 0, 0);
        }

        String modelAbsPath = null;
        String bestEntryName = null;
        int extracted = 0, skipped = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;

                // zip slip 防护
                File out = new File(dest, e.getName());
                String canonical = dest.getCanonicalPath() + File.separator;
                if (!out.getCanonicalPath().startsWith(canonical)) {
                    Log.w(TAG, "skip zip-slip: " + e.getName());
                    skipped++;
                    continue;
                }
                if (!hasAllowedExt(e.getName())) {
                    Log.i(TAG, "skip ext: " + e.getName());
                    skipped++;
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Log.w(TAG, "mkdir failed for " + parent.getAbsolutePath());
                    skipped++;
                    continue;
                }
                try {
                    copy(zf.getInputStream(e), out);
                    extracted++;
                } catch (Throwable io) {
                    Log.w(TAG, "extract failed: " + e.getName(), io);
                    skipped++;
                    continue;
                }

                String ln = e.getName().toLowerCase();
                if (isModelName(ln)) {
                    // ★ 用 entry 名（不是绝对路径）比层级深浅，
                    //   之前拿 e.getName().length() 与绝对路径长度比，判断是错的
                    if (bestEntryName == null || e.getName().length() < bestEntryName.length()) {
                        bestEntryName = e.getName();
                        modelAbsPath = out.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "import failed", t);
            deleteRecursively(dest);
            String msg = t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
            return ImportResult.fail("解压失败：" + msg, extracted, skipped);
        }

        if (modelAbsPath == null) {
            deleteRecursively(dest);
            String hint = extracted == 0
                    ? "压缩包里没有可解压的文件（检查是否为有效 zip）"
                    : ("解压了 " + extracted + " 个文件，但没有模型文件"
                       + "（支持 .pmx / .pmd / .glb / .gltf）");
            return ImportResult.fail(hint, extracted, skipped);
        }

        // 校验模型文件确实存在且有内容
        File mf = new File(modelAbsPath);
        if (!mf.isFile() || mf.length() <= 0) {
            deleteRecursively(dest);
            return ImportResult.fail("模型文件为空或无法读取", extracted, skipped);
        }

        Log.i(TAG, "imported: " + modelAbsPath + " (extracted=" + extracted
                + " skipped=" + skipped + ")");
        return new ImportResult(true, modelAbsPath, null, extracted, skipped);
    }

    /** 兼容旧调用：失败返回 null。 */
    public String importZip(File zip) {
        ImportResult r = importZipDetailed(zip);
        return r.ok ? r.modelAbsPath : null;
    }

    /** 绝对路径转 /models/xxx URL（供 WebViewAssetLoader 用）。 */
    public String toWebUrl(String absPath) {
        if (absPath == null) return "";
        String root = modelsRoot().getAbsolutePath();
        if (!absPath.startsWith(root)) return "";
        String rel = absPath.substring(root.length() + 1);
        // 路径分隔符统一为正斜杠（URL 需要）
        return "/models/" + rel.replace(File.separatorChar, '/');
    }

    /** 列出已导入模型。 */
    public List<ImportedModel> listModels() {
        List<ImportedModel> out = new ArrayList<>();
        File[] kids = modelsRoot().listFiles();
        if (kids == null) return out;
        for (File dir : kids) {
            if (!dir.isDirectory()) continue;
            String model = findModelFile(dir);
            if (model == null) continue;
            long size = dirSize(dir);
            out.add(new ImportedModel(dir.getName(), model, toWebUrl(model), size));
        }
        java.util.Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** 删除一个已导入模型。 */
    public boolean delete(String name) {
        if (name == null || name.isEmpty()) return false;
        File root = modelsRoot();
        File dir = new File(root, name);
        try {
            if (!dir.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        if (!dir.exists()) return false;
        deleteRecursively(dir);
        return true;
    }

    /** 找目录里的 pmx/pmd（可含子目录）。 */
    /**
     * 直接导入一个【单独的模型文件】（.glb / .gltf）—— GLB 是自包含的，不必打 zip。
     * @return 成功时返回导入结果（带 webUrl），失败带原因
     */
    public ImportResult importSingleModel(File src, String displayName) {
        if (src == null || !src.isFile() || src.length() <= 0) {
            return ImportResult.fail("模型文件为空或无法读取", 0, 0);
        }
        /* ★★ 扩展名判定要宽松：src 可能是【无后缀的临时文件】★★
           ---- 事故复盘（用户实测："导入 .glb 提示不是支持的模型格式"）----
           调用方先把文件拷到 `mmd_pick_<时间戳>`（无扩展名）再用魔数识别格式，
           然后把这个临时文件交给这里 —— 而这里原来只看 `src.getName()` 的后缀
           → 必然误报（日志 `kind=glb size=8431112` 却仍然失败）。
           现在：**src 或 displayName 任一像模型文件就放行**，
                 并且要求调用方已按魔数确认过格式。
           （调用方现在也会给临时文件补上扩展名，这是双保险 ——
             将来临时文件命名再变也不会再栽。） */
        String srcLower = src.getName().toLowerCase(java.util.Locale.ROOT);
        String dispLower = (displayName == null ? "" : displayName).toLowerCase(java.util.Locale.ROOT);
        if (!isModelName(srcLower) && !isModelName(dispLower)) {
            return ImportResult.fail("不是支持的模型格式（支持 .pmx / .pmd / .glb / .gltf）", 0, 0);
        }
        try {
            String base = displayName == null || displayName.isEmpty()
                    ? src.getName() : displayName;
            /* ★ 目标文件名一定要带模型扩展名，否则 findModelFile() 下次找不到它，
               模型会"导入成功但列表里没有/切不过去"。 */
            if (!isModelName(base.toLowerCase(java.util.Locale.ROOT))) {
                int d2 = src.getName().lastIndexOf('.');
                String ext = (d2 > 0) ? src.getName().substring(d2) : ".glb";
                base = base + ext;
            }
            /* 去掉扩展名当目录名，避免同名冲突 */
            String dirName = base;
            int dot = dirName.lastIndexOf('.');
            if (dot > 0) dirName = dirName.substring(0, dot);
            dirName = sanitizeDirName(dirName);
            if (dirName.isEmpty()) dirName = "model";

            File dest = new File(modelsRoot(), "mmd_import_" + System.currentTimeMillis()
                    + "_" + dirName);
            if (!dest.exists() && !dest.mkdirs()) {
                return ImportResult.fail("无法创建目录：" + dest.getName(), 0, 0);
            }
            File dst = new File(dest, sanitizeDirName(base));
            copy(new java.io.FileInputStream(src), dst);
            Log.i(TAG, "importSingleModel ok: " + dst.getAbsolutePath()
                    + " (" + dst.length() + " bytes)");
            return new ImportResult(true, dst.getAbsolutePath(), null, 1, 0);
        } catch (Throwable t) {
            Log.e(TAG, "importSingleModel failed", t);
            String msg = t.getMessage();
            return ImportResult.fail("导入失败：" + (msg == null ? t.getClass().getSimpleName() : msg),
                    0, 0);
        }
    }

    /** 目录名安全化（去掉路径分隔符等）。 */
    private static String sanitizeDirName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?'
                    || ch == '"' || ch == '<' || ch == '>' || ch == '|' || ch < 0x20) {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String r = sb.toString().trim();
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    private String findModelFile(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File f : kids) {
            if (f.isFile()) {
                String ln = f.getName().toLowerCase();
                if (isModelName(ln)) return f.getAbsolutePath();
            }
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                String r = findModelFile(f);
                if (r != null) return r;
            }
        }
        return null;
    }

    private long dirSize(File dir) {
        long total = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File f : kids) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    /** 内部存储中的 models 目录（供 WebViewAssetLoader 使用）。 */
    public File internalModelsRoot() {
        return modelsRoot();
    }

    private static String sanitize(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^A-Za-z0-9._\\-]", "_");
        if (base.isEmpty()) base = "model_" + System.currentTimeMillis();
        return base;
    }

    private static boolean hasAllowedExt(String name) {
        String ln = name.toLowerCase();
        for (String e : ALLOWED_EXT) if (ln.endsWith(e)) return true;
        return false;
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (InputStream i = in; OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
