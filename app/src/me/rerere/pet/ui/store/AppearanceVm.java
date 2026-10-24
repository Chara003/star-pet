package me.rerere.pet.ui.store;

import android.content.Context;
import android.content.Intent;

import java.io.File;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.PetOverlayService;

/**
 * 桌宠外观 / 位置的唯一数据入口（单向数据流，Phase 3 起所有页面都走它）。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么需要这一层（而不是页面里直接 store.saveAppearance）
 * ══════════════════════════════════════════════════════════════
 *   旧 MainActivity 的每个滑块都是这样写的：
 *     onProgressChanged(...) { a.headBoundary = v/100f; store.saveAppearance(a);
 *                              if (fromUser) scheduleAppearanceApply(); }
 *   它有三个问题：
 *     1) **每次进度变化都写盘**（拖动一次几十次 IO）；
 *     2) `store.appearance()` 是"读-改-写"，多个滑块同时拖动时会互相覆盖（§17.7）；
 *     3) 初始化时 setProgress 也可能触发回调，把默认值写回去（§17.9）。
 *
 *   现在统一成：
 *     · {@link #snapshot()}  纯读，永远不写；
 *     · {@link #edit(Edits)} 一次"读-改-写"，改完立刻下发；
 *     · 拖动中用 {@link #preview(Edits)}（只下发不落盘），松手用 {@link #commit(Edits)}；
 *     · 所有写入都经过 {@link #edit} 这一个入口，页面再也碰不到 saveAppearance。
 *
 * ★ 下发（applyAppearance）永远走 PetOverlayService.current()，服务没跑就启动它。
 */
public final class AppearanceVm {

    /** 一次编辑意图：在最新快照上改完再整体写回，避免读-改-写互相覆盖。 */
    public interface Edits {
        void apply(PetStore.Appearance a);
    }

    /** 只读快照（不可变，渲染只依赖它）。 */
    public static final class Snapshot {
        public final boolean enabled;
        public final String renderMode;
        public final float scale;
        public final int fps;
        public final boolean idleRandom;
        public final float headBoundary;
        public final float bodyBoundary;
        public final String mmdModelUrl;
        public final float mmdScale;
        public final int mmdAntiAlias;
        public final boolean roamEnabled;
        public final int roamSpeed;
        public final int roamChance;
        public final boolean gpuAccel;
        public final float renderScale;
        public final boolean perfAuto;
        public final int fpsCap;
        public final boolean physics;
        public final boolean mbMmdOn;
        public final int mbMmdLen;
        public final int mbMmdQual;
        public final boolean mbGlbOn;
        public final int mbGlbLen;
        public final int mbGlbQual;
        public final String packageId;
        public final int bubbleHeightPct;
        public final int bubbleMinPct;

        Snapshot(PetStore.Appearance a) {
            enabled = a.enabled;
            renderMode = a.renderMode == null ? "sprite" : a.renderMode;
            scale = a.scale;
            fps = a.fps;
            idleRandom = a.idleRandomEnabled;
            headBoundary = a.headBoundary;
            bodyBoundary = a.bodyBoundary;
            mmdModelUrl = a.mmdModelUrl == null ? "" : a.mmdModelUrl;
            mmdScale = a.mmdScale;
            mmdAntiAlias = a.mmdAntiAliasLevel;
            roamEnabled = a.roamEnabled;
            roamSpeed = a.roamSpeed;
            roamChance = a.roamChance;
            gpuAccel = a.gpuAccel;
            renderScale = a.renderScale;
            perfAuto = a.perfAuto;
            fpsCap = a.fpsCap;
            physics = a.physicsEnabled;
            mbMmdOn = a.mbMmdOn;
            mbMmdLen = a.mbMmdLen;
            mbMmdQual = a.mbMmdQual;
            mbGlbOn = a.mbGlbOn;
            mbGlbLen = a.mbGlbLen;
            mbGlbQual = a.mbGlbQual;
            packageId = a.packageId == null ? "" : a.packageId;
            bubbleHeightPct = a.bubbleHeightPct;
            bubbleMinPct = a.bubbleMinPct;
        }

        /** 是否 3D 模式（首页据此切换整套内容）。 */
        public boolean isMmd() { return "mmd".equals(renderMode); }
    }

    private final Context app;
    private final PetStore store;
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public AppearanceVm(Context c) {
        app = c.getApplicationContext();
        store = new PetStore(app);
    }

    /* ---------------- 读 ---------------- */

    public Snapshot snapshot() { return new Snapshot(store.appearance()); }

    /** 当前桌宠素材的显示信息（"当前桌宠：xxx" 那两行）。 */
    public String petLabel() {
        PetStore.Appearance a = store.appearance();
        if (a.packageId == null || a.packageId.isEmpty()) {
            return "未导入桌宠素材";
        }
        File dir = new File(new File(app.getFilesDir(), "pets"), a.packageId);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".webp") || n.endsWith(".png")) {
                    return a.packageId + "  ·  " + f.getName();
                }
            }
        }
        return a.packageId + "（素材缺失）";
    }

    /**
     * 当前 3D 模型的显示名。
     *
     * ★ 为什么要走这一层：模型可以有自定义名（store.modelAlias），
     *   而 mmdModelUrl 里存的是文件夹路径 —— 直接把 URL 显示给用户
     *   既难看（/models/mmd_import_1791294297234_Kurumi (1)/...）又认不出是谁。
     */
    public String currentModelLabel() {
        String url = store.appearance().mmdModelUrl;
        if (url == null || url.isEmpty()) return "";
        try {
            for (me.rerere.pet.data.MmdModelManager.ImportedModel m
                    : new me.rerere.pet.data.MmdModelManager(app).listModels()) {
                if (url.equals(m.webUrl)) {
                    String alias = store.modelAlias(m.name);
                    return (alias != null && !alias.isEmpty()) ? alias : m.name;
                }
            }
        } catch (Throwable ignored) {}
        return url;
    }

    /* ---------------- 写 ---------------- */

    /**
     * 拖动中的"实时同步"。
     *
     * ★ 这里必须真的写进 store 再下发，不能只调 applyAppearance()：
     *   applyAppearance() 内部是现读 store.appearance() 的，
     *   不落盘就等于下发了个旧值 —— 滑块拖了半天画面不动（踩过）。
     *
     * ★ 为什么不做防抖：旧 MainActivity 的滑块就是每一格都 saveAppearance()，
     *   实测不卡。SharedPreferences 的 apply() 是"内存立即生效 + 后台异步落盘"，
     *   连续几十次调用的真实成本只是内存 map 更新，不是几十次磁盘 IO。
     *   反而是我第一版加的 250ms 防抖让手感变差（松手后才动）。
     */
    public synchronized void preview(Edits edits) {
        PetStore.Appearance a = store.appearance();
        edits.apply(a);
        store.saveAppearance(a);
        applyToService();
    }

    /** 落盘 + 下发（松手 / 点开关时用）。 */
    public synchronized void commit(Edits edits) {
        PetStore.Appearance a = store.appearance();
        edits.apply(a);
        store.saveAppearance(a);
        applyToService();
    }

    /** 防抖版 commit：连续调用只在 250ms 后执行一次（连续拖动场景）。 */
    public synchronized void commitDebounced(final Edits edits) {
        pending = edits;
        handler.removeCallbacks(flush);
        handler.postDelayed(flush, 250);
    }

    private Edits pending;
    private final Runnable flush = new Runnable() {
        @Override public void run() {
            Edits e = pending;
            pending = null;
            if (e == null) return;
            PetStore.Appearance a = store.appearance();
            e.apply(a);
            store.saveAppearance(a);
            applyToService();
        }
    };

    /** 立即落盘（例如离开页面 / Activity onPause 时）。 */
    public void flushNow() {
        handler.removeCallbacks(flush);
        flush.run();
    }

    /* ---------------- 服务控制 ---------------- */

    public void setEnabled(boolean on) {
        commit(a -> a.enabled = on);
        if (on) startService();
        else stopService();
    }

    public void startService() {
        try {
            app.startService(new Intent(app, PetOverlayService.class));
        } catch (Throwable t) {
            /* 没有悬浮窗权限时会抛，调用方负责提示用户 */
        }
    }

    public void stopService() {
        try {
            app.stopService(new Intent(app, PetOverlayService.class));
        } catch (Throwable ignored) {}
    }

    /** 把当前配置下发给运行中的桌宠；服务没跑就顺手启动。 */
    public void applyToService() {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) {
            if (store.appearance().enabled) startService();
            return;
        }
        try {
            svc.applyAppearance();
        } catch (Throwable ignored) {}
    }

    /**
     * 只下发帧率上限（不碰窗口尺寸/取景/漫游）。
     *
     * ★ 为什么单独一条：
     *   applyAppearance() 会算窗口宽高、改 LayoutParams、重置漫游边界。
     *   帧率滑块拖动时每一格都跑一遍那套，会看到画面抖、模型反复取景。
     *   旧 PetMmdActivity 专门拆了 applyFpsCapNow() 就为这个（HANDOVER 记过）。
     */
    public void applyFpsCapNow() {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) return;
        try { svc.applyFpsCapNow(); } catch (Throwable ignored) {}
    }

    /** 只下发物理开关（同理，避免整窗重算）。 */
    public void applyPhysicsNow() {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) return;
        try {
            PetStore.Appearance a = store.appearance();
            svc.applyPhysicsNow(a.physicsEnabled);
        } catch (Throwable ignored) {}
    }

    /** 只下发动态模糊（拖滑块时实时调用，不整窗重算）。 */
    public void applyMotionBlurNow() {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) return;
        try { svc.applyMotionBlur(); } catch (Throwable ignored) {}
    }

    /**
     * 开关自动漫游。
     *
     * ★★ 不能只 commit 一个布尔值 ★★
     *   旧 PetMmdActivity 在这里做了两件额外的事，合并到高级设置时必须一起搬过来，
     *   否则表现是"开关打开了但桌宠根本不动"：
     *     ① 开启时调 restartRoam(true) —— 光写配置不会重排计时器（它有幂等保护，
     *        不 force 的话那次排程会被挡掉）；
     *     ② 开启时必须【关掉相机自转】—— 自转在改 model.rotation.y，
     *        而漫游的「往左走转身」也在改同一个值，两者会互相打架。
     */
    public void setRoamEnabled(boolean on) {
        commit(a -> a.roamEnabled = on);
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) return;
        try {
            if (on) {
                svc.restartRoam(true);
                svc.evalJs("window.__setSpinForRoam && window.__setSpinForRoam(true)");
            } else {
                svc.stopRoam();
                svc.evalJs("window.__setSpinForRoam && window.__setSpinForRoam(false)");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 重排漫游计时器（概率/速度改完必须调用）。
     * 拖动过程中不要调 —— 每帧重启会让它一直不走，只在松手时调一次。
     */
    public void restartRoamNow() {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) return;
        try { svc.restartRoam(true); } catch (Throwable ignored) {}
    }

    /** 校准辅助线（拖动触摸分区滑块时显示，松手隐藏）。 */
    public void showGuideLines() {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) return;
        PetStore.Appearance a = store.appearance();
        try {
            svc.setGuideLines(a.headBoundary, a.bodyBoundary);
        } catch (Throwable ignored) {}
    }

    public void hideGuideLines() {
        PetOverlayService svc = PetOverlayService.current();
        if (svc == null) return;
        try {
            svc.setGuideLines(-1f, -1f);
        } catch (Throwable ignored) {}
    }

    /**
     * 切换渲染模式：精灵图 <-> 3D。
     *
     * ★★ 必须【重启服务】，不能只 applyAppearance() ★★
     *   实测（实测"切换了但画面没变，手动开关一次桌宠才生效"）：
     *   applyAppearance() 只调整窗口尺寸、下发参数，
     *   它不会销毁旧的 spriteView / mmdView 再建新的 ——
     *   两种模式的渲染控件根本不是同一个东西，参数下发再多也换不过来。
     *   旧 PetMmdActivity 的做法就是 stopService + 600ms 后 startService。
     */
    public void setRenderMode(String mode) {
        commit(a -> a.renderMode = mode);
        restartService();
    }

    /**
     * 安全重启桌宠服务。
     * ★ 600ms 延迟不是拍脑袋：stopService 后系统要一段时间才真正走完 onDestroy，
     *   紧接着 startService 会在旧实例还没拆完时又 addView 悬浮窗 → 崩过。
     *   另外这里加了重入保护，防止连点两次。
     */
    public void restartService() {
        if (restarting) return;
        restarting = true;
        stopService();
        handler.postDelayed(() -> {
            restarting = false;
            PetStore.Appearance a = store.appearance();
            if (a.enabled) startService();
        }, 600);
    }

    private boolean restarting = false;
}
