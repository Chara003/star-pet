package me.rerere.pet.core;

import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * .codex-pet.zip 导入器。
 * 包结构：pet.json + spritesheet.webp（允许嵌套一层目录）。
 * 安全：仅允许 json/png/webp，限制条目数与体积，拒绝路径穿越。
 */
public final class CodexPetImporter {

    public static final class PetPackageException extends IllegalArgumentException {
        public final String code;
        public PetPackageException(String code) { super(code); this.code = code; }
    }

    public static final class InstalledPackage {
        public final String id;
        public final String displayName;
        public final String description;
        public final String kind;
        public final File directory;
        public final File spritesheet;
        public final int width;
        public final int height;
        public final PetContracts.CodexPetVersion version;

        InstalledPackage(String id, String displayName, String description, String kind,
                         File directory, File spritesheet, int width, int height,
                         PetContracts.CodexPetVersion version) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.kind = kind;
            this.directory = directory;
            this.spritesheet = spritesheet;
            this.width = width;
            this.height = height;
            this.version = version;
        }
    }

    private static final int MAX_FILES = 32;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final long MAX_SINGLE_FILE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    private final File petsRoot;

    public CodexPetImporter(File petsRoot) { this.petsRoot = petsRoot; }

    public InstalledPackage importFrom(File zipFile, boolean replaceExisting) throws IOException {
        try (InputStream in = new FileInputStream(zipFile)) {
            return importFrom(in, replaceExisting);
        }
    }

    public InstalledPackage importFrom(InputStream input, boolean replaceExisting) throws IOException {
        if (!petsRoot.exists() && !petsRoot.mkdirs()) throw new IOException("pets_root_unavailable");
        File staging = new File(petsRoot, ".staging-" + System.currentTimeMillis());
        if (!staging.mkdir()) throw new IOException("pet_staging_unavailable");
        try {
            extractSafely(input, staging);
            File contentRoot = resolveContentRoot(staging);

            File manifestFile = new File(contentRoot, "pet.json");
            if (!manifestFile.isFile()) throw new PetPackageException("pet_manifest_missing");
            byte[] raw = readAllBytes(manifestFile);
            if (raw.length > MAX_JSON_BYTES) throw new PetPackageException("pet_manifest_too_large");
            JSONObject manifest = parseJson(raw);

            String id = optString(manifest, "id");
            String displayName = optString(manifest, "displayName");
            String description = optString(manifest, "description");
            String kind = optString(manifest, "kind");
            String sheetPath = optString(manifest, "spritesheetPath");
            if (sheetPath == null || sheetPath.trim().isEmpty()) sheetPath = "spritesheet.webp";

            if (id == null || !PetContracts.isValidPackageId(id))
                throw new PetPackageException("pet_manifest_id_invalid");
            if (displayName == null || displayName.trim().isEmpty() || displayName.length() > 80)
                throw new PetPackageException("pet_manifest_name_invalid");

            int versionNum = manifest.optInt("spriteVersionNumber", manifest.optInt("version", 1));
            PetContracts.CodexPetVersion version;
            try {
                version = PetContracts.CodexPetVersion.of(versionNum);
            } catch (Exception e) {
                throw new PetPackageException("pet_manifest_version_unsupported");
            }

            String normalizedSheet = normalizeEntryName(sheetPath);
            String ext = extensionOf(normalizedSheet);
            if (!ext.equals("png") && !ext.equals("webp"))
                throw new PetPackageException("pet_spritesheet_type_invalid");
            File sheet = safeChild(contentRoot, normalizedSheet);
            if (!sheet.isFile()) throw new PetPackageException("pet_spritesheet_missing");

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(sheet.getAbsolutePath(), opts);
            if (opts.outWidth <= 0) throw new PetPackageException("pet_spritesheet_decode_failed");

            int expectW = PetContracts.CODEX_FRAME_WIDTH * PetContracts.CODEX_ATLAS_COLUMNS;
            int expectH = PetContracts.CODEX_FRAME_HEIGHT * version.rows();
            if (opts.outWidth != expectW || opts.outHeight != expectH)
                throw new PetPackageException("pet_spritesheet_dimensions_invalid");

            File target = new File(petsRoot, id);
            if (target.exists() && !replaceExisting) throw new PetPackageException("pet_id_exists");
            File backup = null;
            if (target.exists()) {
                backup = new File(petsRoot, ".backup-" + id + "-" + System.currentTimeMillis());
                if (!target.renameTo(backup)) throw new PetPackageException("pet_replace_backup_failed");
            }
            File source = contentRoot.equals(staging) ? staging : contentRoot;
            try {
                moveDirectory(source, target);
            } catch (IOException failure) {
                if (backup != null) backup.renameTo(target);
                throw failure;
            }
            if (backup != null) deleteRecursively(backup);

            File finalSheet = safeChild(target, normalizedSheet);
            return new InstalledPackage(id, displayName, description, kind, target, finalSheet,
                    opts.outWidth, opts.outHeight, version);
        } finally {
            deleteRecursively(staging);
        }
    }

    /** 已安装素材的摘要信息（用于列表展示）。 */
    public static final class InstalledInfo {
        public final String id;
        public final String displayName;
        public final String version;
        public final long sizeBytes;
        public final boolean valid;

        InstalledInfo(String id, String displayName, String version, long sizeBytes, boolean valid) {
            this.id = id;
            this.displayName = displayName;
            this.version = version;
            this.sizeBytes = sizeBytes;
            this.valid = valid;
        }
    }

    /**
     * 列出 petsRoot 下所有已安装素材。
     * 忽略 .staging-/.backup- 这类内部临时目录。
     */
    public List<InstalledInfo> listInstalled() {
        List<InstalledInfo> out = new ArrayList<>();
        File[] dirs = petsRoot.listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String name = dir.getName();
            if (name.startsWith(".")) continue;
            out.add(describe(dir));
        }
        // 按名称排序，便于查找
        java.util.Collections.sort(out, (a, b) ->
                a.displayName.compareToIgnoreCase(b.displayName));
        return out;
    }

    /** 读取单个素材目录的摘要。 */
    public InstalledInfo describe(File dir) {
        String id = dir.getName();
        String displayName = id;
        String version = "V1";
        long size = 0;
        boolean valid = false;
        try {
            File manifestFile = new File(dir, "pet.json");
            if (manifestFile.isFile()) {
                byte[] raw = readAllBytes(manifestFile);
                JSONObject o = parseJson(raw);
                String dn = optString(o, "displayName");
                if (dn != null && !dn.trim().isEmpty()) displayName = dn.trim();
                int v = o.optInt("spriteVersionNumber", o.optInt("version", 1));
                version = "V" + v;
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    size += f.length();
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".webp") || n.endsWith(".png")) valid = true;
                }
            }
        } catch (Throwable ignored) {}
        return new InstalledInfo(id, displayName, version, size, valid);
    }

    /** 删除一个已安装素材。 */
    public boolean delete(String id) throws IOException {
        if (!PetContracts.isValidPackageId(id)) throw new IOException("pet_id_invalid");
        File dir = new File(petsRoot, id);
        if (!dir.exists()) return false;
        File canonicalRoot = petsRoot.getCanonicalFile();
        File canonicalDir = dir.getCanonicalFile();
        // 安全校验：只允许删除 petsRoot 下的直接子目录
        if (!canonicalDir.getParentFile().equals(canonicalRoot)) {
            throw new IOException("pet_delete_out_of_root");
        }
        deleteRecursively(dir);
        return true;
    }

    private void extractSafely(InputStream input, File staging) throws IOException {
        int files = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                files++;
                if (files > MAX_FILES) throw new PetPackageException("pet_zip_too_many_files");
                String normalized = normalizeEntryName(entry.getName());
                String ext = extensionOf(normalized);
                if (!ext.equals("json") && !ext.equals("png") && !ext.equals("webp"))
                    throw new PetPackageException("pet_zip_file_type_forbidden");
                File dest = safeChild(staging, normalized);
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs())
                    throw new IOException("pet_staging_unavailable");
                long entryBytes = 0;
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        entryBytes += n;
                        total += n;
                        if (entryBytes > MAX_SINGLE_FILE_BYTES)
                            throw new PetPackageException("pet_zip_entry_too_large");
                        if (total > MAX_TOTAL_BYTES)
                            throw new PetPackageException("pet_zip_too_large");
                        out.write(buf, 0, n);
                    }
                }
            }
        }
        if (files == 0) throw new PetPackageException("pet_zip_empty");
    }

    private File resolveContentRoot(File staging) throws PetPackageException {
        if (new File(staging, "pet.json").isFile()) return staging;
        File[] children = staging.listFiles();
        if (children != null && children.length == 1 && children[0].isDirectory()
                && new File(children[0], "pet.json").isFile()) return children[0];
        throw new PetPackageException("pet_package_layout_invalid");
    }

    private static File safeChild(File root, String relative) throws PetPackageException {
        File child;
        File canonicalRoot;
        try {
            child = new File(root, relative).getCanonicalFile();
            canonicalRoot = root.getCanonicalFile();
        } catch (IOException e) {
            throw new PetPackageException("pet_zip_path_escape");
        }
        if (!child.getPath().equals(canonicalRoot.getPath())
                && !child.getPath().startsWith(canonicalRoot.getPath() + File.separator))
            throw new PetPackageException("pet_zip_path_escape");
        return child;
    }

    private static String normalizeEntryName(String name) throws PetPackageException {
        String clean = name.replace('\\', '/');
        if (clean.startsWith("/")) throw new PetPackageException("pet_zip_absolute_path");
        if (clean.matches("^[a-zA-Z]:.*")) throw new PetPackageException("pet_zip_absolute_path");
        String[] rawParts = clean.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : rawParts) {
            if (part.isEmpty()) continue;
            if (part.equals(".") || part.equals(".."))
                throw new PetPackageException("pet_zip_path_invalid");
            if (sb.length() > 0) sb.append(File.separatorChar);
            sb.append(part);
        }
        if (sb.length() == 0) throw new PetPackageException("pet_zip_path_invalid");
        return sb.toString();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static JSONObject parseJson(byte[] raw) throws PetPackageException {
        int off = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) off = 3;
        String text = new String(raw, off, raw.length - off, StandardCharsets.UTF_8);
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new PetPackageException("pet_manifest_invalid");
        }
    }

    private static String optString(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        Object v = o.opt(key);
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    private static void moveDirectory(File source, File target) throws IOException {
        if (source.renameTo(target)) return;
        copyDirectory(source, target);
        deleteRecursively(source);
    }

    private static void copyDirectory(File src, File dst) throws IOException {
        if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("pet_move_failed");
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File target = new File(dst, f.getName());
            if (f.isDirectory()) copyDirectory(f, target);
            else copyFile(f, target);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] files = f.listFiles();
        if (files != null) for (File c : files) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
