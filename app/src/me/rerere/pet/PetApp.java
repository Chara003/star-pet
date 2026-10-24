package me.rerere.pet;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import me.rerere.pet.data.PetStore;

/**
 * 应用入口：安装全局未捕获异常兜底。
 * 低版本设备上任何启动路径的异常都会导致"每次打开都崩溃"，
 * 这里把崩溃写入文件，并在连续崩溃时自动禁用桌宠悬浮窗，保证界面仍可进入。
 */
public final class PetApp extends Application {

    private static final String TAG = "PetApp";
    private static final String CRASH_FILE = "last_crash.txt";
    /** 连续崩溃阈值：达到后自动关闭悬浮窗。 */
    private static final int CRASH_LIMIT = 2;

    @Override public void onCreate() {
        super.onCreate();
        installCrashGuard();
        /* UI 外观参数（强调色/圆角/底栏手感）在控件构造时就会被读取，
           所以必须在任何 Activity 之前加载。 */
        try {
            me.rerere.pet.ui.theme.UiSettings.ensureLoaded(this);
        } catch (Throwable t) {
            Log.w(TAG, "UiSettings load failed", t);
        }
        me.rerere.pet.data.LocationHelper.init(this);
        me.rerere.pet.data.BuiltinMcp.init(new PetStore(this));
        /* ★ 随 APK 预置的 VMD 动作必须【尽早】落盘：
           桌宠悬浮窗一启动就会去加载默认绑定的那几个动作
           （/vmd/_builtin/*.vmd），晚一步就是一堆 404。
           这里只做一次（内部有版本号标记），开销可忽略。 */
        try {
            new me.rerere.pet.data.VmdManager(this).ensurePresets();
        } catch (Throwable t) {
            Log.w(TAG, "ensurePresets failed", t);
        }
    }

    private void installCrashGuard() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                writeCrashLog(error);
                int count = bumpCrashCount();
                if (count >= CRASH_LIMIT) {
                    // 自动降级：关闭桌宠悬浮窗，避免开机即崩的死循环
                    PetStore store = new PetStore(this);
                    PetStore.Appearance a = store.appearance();
                    a.enabled = false;
                    store.saveAppearance(a);
                    resetCrashCount();
                }
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void writeCrashLog(Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String header = "time: "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\nandroid: " + android.os.Build.VERSION.SDK_INT
                    + "\ndevice: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + "\n\n";
            File f = new File(getFilesDir(), CRASH_FILE);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write((header + sw).getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {}
    }

    private int bumpCrashCount() {
        try {
            File f = new File(getFilesDir(), "crash_count");
            int count = 0;
            if (f.isFile()) {
                byte[] raw = readAll(f);
                try { count = Integer.parseInt(new String(raw, "UTF-8").trim()); }
                catch (Exception ignored) {}
            }
            count++;
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(String.valueOf(count).getBytes("UTF-8"));
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void resetCrashCount() {
        try {
            File f = new File(getFilesDir(), "crash_count");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write("0".getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {}
    }

    /** 兼容低版本：不用 FileInputStream.readAllBytes（需要 API 33）。 */
    private static byte[] readAll(File file) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
