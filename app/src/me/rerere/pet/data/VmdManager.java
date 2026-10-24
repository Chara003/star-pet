package me.rerere.pet.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * VMD（MMD 动作）库：导入 / 列举 / 删除。
 *
 * 目录结构（都在 getFilesDir()/mmd_vmd 下，由 WebViewAssetLoader 挂到 /vmd/）：
 *   _builtin/   随 APK 预置的动作（首启从 assets/vmd 复制过来）
 *   user/       用户自己导入的（保留 zip 里的相对目录层级）
 *
 * ★ 两个必须踩对的点（都是实测踩过的）：
 *  1) 日文 zip 的条目名是 Shift-JIS，而 java.util.zip 会用 UTF-8 解 → 全变成乱码。
 *     这里用 ZipFile(File, Charset) 指定 MS932/Shift_JIS，
 *     并显式避开「看起来像已被 UTF-8 解码过的替换字符 U+FFFD」。
 *  2) 有些动作包把「骨骼动作 Motion」和「面部表情 Morph」拆成两个文件，
 *     必须【合成一个】才好看。导入后自动配对合并（见 mergePairs）。
 */
public final class VmdManager {

    private static final String TAG = "PetVmd";
    private static final String DIR_ROOT = "mmd_vmd";
    private static final String DIR_BUILTIN = "_builtin";
    private static final String DIR_USER = "user";
    private static final String PREFS = "vmd_meta";
    private static final String KEY_PRESET_VERSION = "__preset_version";
    /** 预置资源的版本：改了 assets/vmd 的内容就 +1，会让 App 重新复制一遍。 */
    private static final int PRESET_VERSION = 1;

    /** 单个 VMD 上限（防手滑导入巨大文件把内存吃光）。 */
    private static final long MAX_VMD_BYTES = 32L * 1024 * 1024;
    private static final int MAX_FILES_PER_IMPORT = 3000;

    private final Context ctx;

    public VmdManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ---------------------------------------------------------------- 描述

    /** 一个 VMD 动作文件。 */
    public static final class VmdFile {
        /** 相对 root 的路径，同时作为稳定 id。例：user/歩く/2.歩き10L.vmd */
        public final String id;
        /** 显示名（去掉 .vmd、把 / 换成 " / "）。 */
        public final String name;
        /** 给 WebView 的地址：/vmd/... */
        public final String webUrl;
        public final long size;
        public final int bones;
        public final int morphs;
        public final float duration;
        public final boolean preset;
        /**
         * 旧版本的长名（完整路径、空格连接）。
         * ★ 用于兼容旧翻译缓存 —— 旧缓存是按长名存的，
         *   改成短名后直接查不到，得多查一个键。
         */
        public final String legacyName;
        /** 只有表情（Morph）没有骨骼动作 —— 单独用一个看起来会「人不动」。 */
        public final boolean morphOnly;

        VmdFile(String id, String name, String legacy, String webUrl, long size,
                int bones, int morphs, float duration, boolean preset) {
            this.id = id;
            this.name = name;
            this.legacyName = legacy;
            this.webUrl = webUrl;
            this.size = size;
            this.bones = bones;
            this.morphs = morphs;
            this.duration = duration;
            this.preset = preset;
            this.morphOnly = bones == 0 && morphs > 0;
        }
    }

    /** 导入结果汇总。 */
    public static final class ImportResult {
        public int added;
        public int merged;
        public int skipped;
        public final List<String> errors = new ArrayList<>();
        public boolean ok() { return added > 0 || merged > 0; }
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("导入 ").append(added).append(" 个动作");
            if (merged > 0) sb.append("，自动合并 ").append(merged).append(" 组动作+表情");
            if (skipped > 0) sb.append("，跳过 ").append(skipped).append(" 个");
            if (!errors.isEmpty()) sb.append("；").append(errors.get(0));
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 路径

    /**
     * 造一个【虚拟条目】代表 glTF 模型自带的一个动作。
     * id 用 `glb:<动作名>` 前缀 —— 页面凭这个前缀去 glbClips 里取动作，
     * 与 VMD 的文件路径形式并存于同一套插槽。
     */
    public static VmdFile virtualGlb(String clipName) {
        String id = "glb:" + clipName;
        return new VmdFile(id, clipName, null, "", 0L, 0, 0, 0f, false);
    }

    public File root() {
        File d = new File(ctx.getFilesDir(), DIR_ROOT);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File builtinDir() {
        File d = new File(root(), DIR_BUILTIN);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File userDir() {
        File d = new File(root(), DIR_USER);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ---------------------------------------------------------------- 预置

    /**
     * 把 assets/vmd 里的预置动作复制到 _builtin/（只做一次，用 PREFS 里的版本号标记）。
     * 用户删掉预置后不会被反复复活，除非升级 PRESET_VERSION。
     */
    public void ensurePresets() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getInt(KEY_PRESET_VERSION, 0) >= PRESET_VERSION) return;
        int n = 0;
        try {
            String[] names = ctx.getAssets().list("vmd");
            if (names == null || names.length == 0) {
                sp.edit().putInt(KEY_PRESET_VERSION, PRESET_VERSION).apply();
                return;
            }
            File dir = builtinDir();
            for (String n2 : names) {
                if (!n2.toLowerCase(Locale.ROOT).endsWith(".vmd")) continue;
                File dst = new File(dir, n2);
                if (dst.exists() && dst.length() > 0) continue;
                InputStream in = ctx.getAssets().open("vmd/" + n2);
                copy(in, dst);
                n++;
            }
            sp.edit().putInt(KEY_PRESET_VERSION, PRESET_VERSION).apply();
            Log.i(TAG, "presets copied: " + n);
        } catch (Throwable t) {
            Log.w(TAG, "ensurePresets failed", t);
        }
    }

    /** 预置动作的显示名（来自 assets/vmd/index.json，读失败就退回文件名）。 */
    private Map<String, String> presetNames() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            InputStream in = ctx.getAssets().open("vmd/index.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) bos.write(buf, 0, k);
            in.close();
            org.json.JSONArray arr = new org.json.JSONArray(new String(bos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                m.put(o.optString("file", ""), o.optString("name", ""));
            }
        } catch (Throwable t) {
            Log.w(TAG, "preset index unavailable", t);
        }
        return m;
    }

    public String presetDisplayName(String fileName) {
        String n = presetNames().get(fileName);
        return (n == null || n.isEmpty()) ? null : n;
    }

    // ---------------------------------------------------------------- 列举

    /** 列出全部 VMD（预置在前、用户导入在后），按名字排序。 */
    public List<VmdFile> list() {
        List<VmdFile> out = new ArrayList<>();
        Map<String, String> pn = presetNames();
        collect(builtinDir(), out, true, pn);
        collect(userDir(), out, false, pn);
        return out;
    }

    private void collect(File dir, List<VmdFile> out, boolean preset, Map<String, String> pn) {
        collectRec(dir, root(), out, preset, pn);
    }

    private void collectRec(File dir, File base, List<VmdFile> out,
                            boolean preset, Map<String, String> pn) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                collectRec(f, base, out, preset, pn);
                continue;
            }
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".vmd")) continue;

            String id = relative(base, f);
            int[] meta = cachedProbe(f, id);
            String legacy = stripExt(id).replace(File.separatorChar, ' ');
            String display;
            if (preset && f.getParentFile() != null
                    && DIR_BUILTIN.equals(f.getParentFile().getName())) {
                String n = pn.get(f.getName());
                display = (n == null || n.isEmpty()) ? stripExt(f.getName()) : n;
            } else {
                display = shortNameOf(id);
            }
            out.add(new VmdFile(id, display, legacy, webUrlOf(id), f.length(),
                    meta[0], meta[1], meta[2] / 30f, preset));
        }
    }

    private static String relative(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.startsWith(b)) {
            p = p.substring(b.length());
            if (p.startsWith(File.separator)) p = p.substring(1);
        }
        return p.replace(File.separatorChar, '/');
    }

    /**
     * 短名：「上级目录 / 文件名」。
     *
     * ★★ 为什么用这个形式（2026-10-06）★★
     *   1) 列表一行能放下（完整路径太长，用户抱怨过）。
     *   2) 模型翻译时返回的键【恰好就是文件名】，
     *      用 "上级目录 / 文件名"当缓存键也能被后缀匹配到。
     *   例：user/移動.../1.歩く(女)/2.歩き10L_(40f_前移動20).vmd
     *       ->  "1.歩く(女) / 2.歩き10L_(40f_前移動20)"
     */
    public static String shortNameOf(String id) {
        if (id == null) return "";
        String t = stripExt(id);
        int k = t.lastIndexOf('/');
        if (k < 0) return t;
        String leaf = t.substring(k + 1);
        String head = t.substring(0, k);
        int k2 = head.lastIndexOf('/');
        String parent = (k2 < 0) ? head : head.substring(k2 + 1);
        if (parent.isEmpty()) return leaf;
        return parent + " / " + leaf;
    }

    private static String stripExt(String s) {
        return s.toLowerCase(Locale.ROOT).endsWith(".vmd")
                ? s.substring(0, s.length() - 4) : s;
    }

    /** 把 id 转成给页面的 URL（路径里的日文/空格必须百分号编码）。 */
    public static String webUrlOf(String id) {
        return "/vmd/" + Uri.encode(id, "/");
    }

    // ---------------------------------------------------------------- 元信息缓存

    /**
     * 读 VMD 的骨架/表情关键帧数与总帧数。
     * 结果缓存进 SharedPreferences：否则每次进设置页都要重读几十 MB。
     * @return {bones, morphs, maxFrame}
     */
    private int[] cachedProbe(File f, String id) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = sp.getString(id, null);
        if (cached != null) {
            String[] parts = cached.split(",");
            if (parts.length == 4) {
                try {
                    int[] r = new int[3];
                    r[0] = Integer.parseInt(parts[0]);
                    r[1] = Integer.parseInt(parts[1]);
                    r[2] = Integer.parseInt(parts[2]);
                    long sz = Long.parseLong(parts[3]);
                    if (sz == f.length()) return r;
                } catch (Throwable ignored) {}
            }
        }
        int[] r = probe(f);
        if (r != null) {
            sp.edit().putString(id, r[0] + "," + r[1] + "," + r[2] + "," + f.length()).apply();
        }
        return r == null ? new int[]{0, 0, 0} : r;
    }

    /** 解析 VMD 头部与关键帧数组。失败返回 null（不是 VMD 就返回 {0,0,0}）。 */
    public static int[] probe(File f) {
        if (f.length() < 54 || f.length() > MAX_VMD_BYTES) return null;
        byte[] d;
        try {
            d = readAll(f);
        } catch (Throwable t) {
            return null;
        }
        return probeBytes(d);
    }

    /** @return {bones, morphs, maxFrame}，结构不对返回 null。 */
    public static int[] probeBytes(byte[] d) {
        if (d.length < 54) return null;
        if (!startsWith(d, "Vocaloid Motion Data")) return null;
        int pos = 50;                       // 30 header + 20 model name
        if (pos + 4 > d.length) return null;
        int nb = le32(d, pos);
        pos += 4;
        if (nb < 0 || nb > (d.length / 111) + 1) return null;
        int maxBone = 0;
        for (int i = 0; i < nb; i++) {
            if (pos + 111 > d.length) return null;
            int fr = le32(d, pos + 15);
            if (fr > maxBone) maxBone = fr;
            pos += 111;
        }
        int nm = 0, maxMorph = 0;
        if (pos + 4 <= d.length) {
            nm = le32(d, pos);
            pos += 4;
            if (nm < 0 || nm > (d.length / 23) + 1) return null;
            for (int i = 0; i < nm; i++) {
                if (pos + 23 > d.length) return null;
                int fr = le32(d, pos + 15);
                if (fr > maxMorph) maxMorph = fr;
                pos += 23;
            }
        }
        return new int[]{nb, nm, Math.max(maxBone, maxMorph)};
    }

    private static boolean startsWith(byte[] d, String s) {
        byte[] b;
        try { b = s.getBytes("US-ASCII"); } catch (Throwable t) { return false; }
        if (d.length < b.length) return false;
        for (int i = 0; i < b.length; i++) if (d[i] != b[i]) return false;
        return true;
    }

    private static int le32(byte[] d, int o) {
        if (o + 4 > d.length) return 0;
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    // ---------------------------------------------------------------- 删除

    public boolean delete(String id) {
        try {
            File f = new File(root(), id);
            if (!f.exists()) return false;
            // 防目录穿越
            if (!f.getCanonicalPath().startsWith(root().getCanonicalPath())) return false;
            boolean ok = f.delete();
            if (ok) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().remove(id).apply();
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "delete failed " + id, t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 导入

    /** 从 SAF 选中的 uri 导入（.vmd 单文件 或 含 vmd 的 .zip）。 */
    public ImportResult importUris(List<Uri> uris) {
        ImportResult r = new ImportResult();
        List<File> touchedDirs = new ArrayList<>();
        for (Uri u : uris) {
            if (r.added >= MAX_FILES_PER_IMPORT) break;
            try {
                String name = displayName(u);
                if (name == null || name.isEmpty()) name = "import.vmd";
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip")) {
                    importZip(u, name, r, touchedDirs);
                } else if (lower.endsWith(".vmd")) {
                    importSingle(u, name, r);
                } else {
                    r.skipped++;
                    r.errors.add("只支持 .vmd 或 .zip：" + name);
                }
            } catch (Throwable t) {
                Log.w(TAG, "import failed " + u, t);
                r.errors.add(String.valueOf(t.getMessage()));
            }
        }
        /* 配对合并：<base>_Motion.vmd + <base>_Morph.vmd → <base>.vmd */
        try {
            r.merged = mergePairs(userDir());
        } catch (Throwable t) {
            Log.w(TAG, "mergePairs failed", t);
        }
        return r;
    }

    private void importSingle(Uri u, String name, ImportResult r) throws IOException {
        File dst = uniqueTarget(userDir(), safeName(name));
        copy(ctx.getContentResolver().openInputStream(u), dst);
        r.added++;
    }

    private void importZip(Uri u, String zipName, ImportResult r, List<File> dirs)
            throws IOException {
        // 先落到临时文件：ZipFile 需要可随机访问的文件，且要指定字符集
        File tmp = new File(ctx.getCacheDir(), "vmd_import.zip");
        copy(ctx.getContentResolver().openInputStream(u), tmp);

        Charset cs = zipCharset();
        ZipFile zf = null;
        try {
            zf = new ZipFile(tmp, cs);
            Enumeration<? extends ZipEntry> en = zf.entries();
            int n = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String en2 = e.getName();
                if (en2 == null) continue;
                if (!en2.toLowerCase(Locale.ROOT).endsWith(".vmd")) continue;
                if (n >= MAX_FILES_PER_IMPORT) { r.skipped++; continue; }
                if (e.getSize() > MAX_VMD_BYTES) { r.skipped++; continue; }
                n++;
                String rel = safeRelPath(en2);
                if (rel == null) { r.skipped++; continue; }
                File dst = new File(userDir(), rel);
                if (dst.exists()) { r.skipped++; continue; }
                File parent = dst.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                copy(zf.getInputStream(e), dst);
                r.added++;
            }
            if (n == 0) r.errors.add(zipName + " 里没有 .vmd 文件");
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) {}
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * 日文 zip 的条目名是 Shift-JIS，而 Java 默认按 UTF-8 解 → 乱码。
     * 这里依次尝试几个常见名字，取系统支持的第一个。
     */
    private static Charset zipCharset() {
        String[] cands = {"MS932", "windows-31j", "Shift_JIS", "SJIS", "x-SJIS"};
        for (String c : cands) {
            try {
                if (Charset.isSupported(c)) return Charset.forName(c);
            } catch (Throwable ignored) {}
        }
        return Charset.forName("UTF-8");
    }

    // ---------------------------------------------------------------- 合并 Motion + Morph

    private static final Pattern PAIR = Pattern.compile("^(.*)_(Motion|Morph)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 把同一目录下的 <base>_Motion.vmd 与 <base>_Morph.vmd 合并成 <base>.vmd。
     * VMD 是「头部 + 骨骼关键帧段 + 表情关键帧段 + 4 个空段」，
     * 合并 = 骨骼段拼接 + 表情段拼接，段尾 16 字节（相机/灯光/阴影/IK，通常全 0）保留一份。
     *
     * @return 合并成功的组数
     */
    public int mergePairs(File dir) {
        int merged = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        Map<String, List<File>> groups = new LinkedHashMap<>();
        for (File f : fs) {
            if (f.isDirectory()) {
                merged += mergePairs(f);
                continue;
            }
            String base = stripExt(f.getName());
            Matcher m = PAIR.matcher(base);
            if (!m.matches()) continue;
            String key = f.getParent() + "\u0000" + m.group(1);
            List<File> l = groups.get(key);
            if (l == null) { l = new ArrayList<>(); groups.put(key, l); }
            l.add(f);
        }
        for (Map.Entry<String, List<File>> e : groups.entrySet()) {
            List<File> l = e.getValue();
            if (l.size() < 2) continue;
            File motion = null, morph = null;
            for (File f : l) {
                if (stripExt(f.getName()).toLowerCase(Locale.ROOT).endsWith("_motion")) motion = f;
                else morph = f;
            }
            if (motion == null || morph == null) continue;
            String base = stripExt(motion.getName());
            base = base.substring(0, base.length() - "_Motion".length());
            File dst = new File(motion.getParentFile(), base + ".vmd");
            if (dst.exists()) continue;
            try {
                byte[] mb = readAll(motion);
                byte[] pb = readAll(morph);
                byte[] out = mergeVmd(mb, pb);
                if (out == null) continue;
                FileOutputStream fo = new FileOutputStream(dst);
                fo.write(out);
                fo.close();
                //noinspection ResultOfMethodCallIgnored
                motion.delete();
                //noinspection ResultOfMethodCallIgnored
                morph.delete();
                merged++;
                Log.i(TAG, "merged " + motion.getName() + " + " + morph.getName()
                        + " -> " + dst.getName() + " (" + out.length + "B)");
            } catch (Throwable t) {
                Log.w(TAG, "merge failed for " + base, t);
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
        }
        return merged;
    }

    /** @return 合并后的字节；结构不对返回 null。 */
    static byte[] mergeVmd(byte[] a, byte[] b) {
        if (a == null || b == null || a.length < 54 || b.length < 54) return null;
        if (!startsWith(a, "Vocaloid Motion Data")) return null;
        if (!startsWith(b, "Vocaloid Motion Data")) return null;

        int[] sa = sections(a);
        int[] sb = sections(b);
        if (sa == null || sb == null) return null;

        byte[] model = new byte[20];
        System.arraycopy(a, 30, model, 0, 20);

        int bonesA = sa[1] - sa[0], bonesB = sb[1] - sb[0];
        int morA = sa[3] - sa[2], morB = sb[3] - sb[2];
        int boneBytesA = bonesA * 111, boneBytesB = bonesB * 111;
        int morBytesA = morA * 23, morBytesB = morB * 23;

        byte[] tail = new byte[16];      // 相机/灯光/阴影/IK：一般为 0，取 a 的尾段
        if (a.length - sa[3] >= 16) System.arraycopy(a, sa[4], tail, 0, 16);

        int total = 30 + 20 + 4 + boneBytesA + boneBytesB + 4 + morBytesA + morBytesB + 16;
        byte[] out = new byte[total];
        int p = 0;
        System.arraycopy(a, 0, out, p, 30); p += 30;
        System.arraycopy(model, 0, out, p, 20); p += 20;
        p = put32(out, p, bonesA + bonesB);
        System.arraycopy(a, sa[0], out, p, boneBytesA); p += boneBytesA;
        System.arraycopy(b, sb[0], out, p, boneBytesB); p += boneBytesB;
        p = put32(out, p, morA + morB);
        System.arraycopy(a, sa[2], out, p, morBytesA); p += morBytesA;
        System.arraycopy(b, sb[2], out, p, morBytesB); p += morBytesB;
        System.arraycopy(tail, 0, out, p, 16);
        return out;
    }

    /** @return {boneStart, boneEnd, morphStart, morphEnd, tailStart}；结构不对返回 null */
    private static int[] sections(byte[] d) {
        int pos = 50;
        if (pos + 4 > d.length) return null;
        int nb = le32(d, pos); pos += 4;
        if (nb < 0 || pos + nb * 111 > d.length) return null;
        int boneStart = pos, boneEnd = pos + nb * 111;
        pos = boneEnd;
        if (pos + 4 > d.length) return null;
        int nm = le32(d, pos); pos += 4;
        if (nm < 0 || pos + nm * 23 > d.length) return null;
        int morphStart = pos, morphEnd = pos + nm * 23;
        return new int[]{boneStart, boneEnd, morphStart, morphEnd, morphEnd};
    }

    private static int put32(byte[] d, int o, int v) {
        d[o] = (byte) (v & 0xFF);
        d[o + 1] = (byte) ((v >>> 8) & 0xFF);
        d[o + 2] = (byte) ((v >>> 16) & 0xFF);
        d[o + 3] = (byte) ((v >>> 24) & 0xFF);
        return o + 4;
    }

    // ---------------------------------------------------------------- 工具

    public String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        String p = uri.getLastPathSegment();
        if (p == null) return null;
        int s = p.lastIndexOf('/');
        return s >= 0 ? p.substring(s + 1) : p;
    }

    /** 去掉 zip slip 与非法字符，保留目录层级。 */
    private static String safeRelPath(String name) {
        String n = name.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        for (String seg : n.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(sanitize(seg));
        }
        String s = sb.toString();
        if (s.length() > 180) {
            // 太长就只留文件名
            int k = s.lastIndexOf('/');
            s = (k >= 0 ? s.substring(k + 1) : s);
            if (s.length() > 180) s = s.substring(s.length() - 180);
        }
        return s.isEmpty() ? null : s;
    }

    private static String safeName(String name) {
        return sanitize(name.replace('\\', '/').replace('/', '_'));
    }

    private static String sanitize(String seg) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.length(); i++) {
            char ch = seg.charAt(i);
            if (ch < 0x20 || ch == ':' || ch == '*' || ch == '?' || ch == '"'
                    || ch == '<' || ch == '>' || ch == '|') {
                sb.append('_');
            } else if (ch == 0xFFFD) {
                // ★ 已被上层用错误字符集解码过的替换字符：用 '_' 顶掉，
                //   至少不会出现一堆一模一样的 \"�\" 文件互相覆盖。
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        String s = sb.toString().trim();
        // 结尾的点和空格在部分文件系统上有坑
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static File uniqueTarget(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = stripExt(name);
        String ext = name.substring(base.length());
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    static void copy(InputStream in, File dst) throws IOException {
        if (in == null) throw new IOException("input stream is null");
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    static byte[] readAll(File f) throws IOException {
        InputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
