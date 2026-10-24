package me.rerere.pet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import me.rerere.pet.data.PetOverlayServiceBridge;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.data.VmdManager;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.CardView2;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * VMD 动作（动画）页 —— 第三轮 UI 迁移。
 *
 * 干三件事：
 *  ① 导入 .vmd（或含 vmd 的 .zip，保留目录层级；Motion+Morph 自动配对合并）
 *  ② 把动作绑定到各种触发时机：待机 / 走路 / 摸头 / 摸身 / 摸脚 / 见面 / 拖拽
 *  ③ 待机随机表演：隔一段随机时间抽一个动作播一次（可勾选允许用哪些）
 *
 * ══════════════════════════════════════════════════════════════
 * 本轮（UI 第三轮）只改"长什么样"，业务逻辑一行没动
 * ══════════════════════════════════════════════════════════════
 *   ★ 必须保留的东西（改之前先看这里）：
 *     · 插槽是【两套】的：MMD 用 slot()，glTF 用 slotGlb()，互不覆盖。
 *       `slotFor(key, glbMode())` 是唯一正确的取法，永远别直接写 slot(key)。
 *     · 多选对话框里勾选顺序 = 播放顺序。AlertDialog 的
 *       getCheckedItemPositions() **不含预置勾选**，所以必须自己维护
 *       `sel[]` + `order` 两个独立数组（旧版踩过 → "绑定自己没了"）。
 *     · 动作库列表必须【搜索 + 分页】。165 条约 660 个 View，
 *       一次性塞进 ScrollView 会卡到像死机（实测过"列表消失"）。
 *     · 翻译状态要三种标记：⟳ 翻译中 / ✓ 已翻 / ○ 待翻，
 *       并且翻译进行中每 1.2s 刷新一次，离开页面停止。
 *     · 兜底翻译：桌宠服务没跑时本页自己直连翻（否则"翻译完全没反应"）。
 *     · glTF 模式下：动作来自模型本身 → 不显示导入/删除；
 *       插槽里若绑的是 VMD 动作要明确报"对当前模型无效"。
 *
 *   视觉上的变化：整页改用 SettingsPage（统一卡片/内边距/开关），
 *   插槽行改成"标题+状态+试播"的单行，顺序编辑放在行下方；
 *   高级项 4 个开关从手搓 Switch 换成 ToggleRowView。
 */
public final class PetVmdActivity extends Activity {

    private static final int REQ_PICK = 0x51;
    /** 动作库分页：默认先渲染这么多条，其余用「显示更多」。 */
    private static final int PAGE_SIZE = 60;

    private PetStore store;
    private VmdManager vmd;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchDebounce = new Runnable() {
        @Override public void run() { renderFiles(); }
    };

    /* ---------------- 视图 ---------------- */
    private LinearLayout slotBox;
    private LinearLayout fileBox;
    private LinearLayout randomBox;
    private TextView fileHint;
    private TextView modelView;
    private ToggleRowView enableRow;

    /* 翻译进度（横幅 + 进度条） */
    private TextView transBanner;
    private ProgressBar transProgress;

    /* ---------------- 数据 ---------------- */
    private volatile List<VmdManager.VmdFile> files = new ArrayList<>();
    private volatile boolean scanning = false;
    /** 扫描/导入失败时的原因（显示给用户，不要静默伪装成"没有动作"）。 */
    private volatile String scanError = null;

    /**
     * 本页自己发起的翻译（兜底通道）。
     *
     * ★★ 为什么需要兜底（实测"自动翻译呢?"）★★
     *   翻译原本完全挂在 PetOverlayService 上：界面调的 requestTranslate()
     *   只是转给服务，而服务没启动时那个转发是空调用 ——
     *   表现为"翻译一直不生效"。
     */
    private volatile boolean localTranslating = false;
    private volatile java.util.Set<String> localTranslatingNames = new java.util.HashSet<>();
    private volatile int transDone = -1;
    private volatile int transTotal = 0;

    /* 搜索 / 分页 */
    private String fileQuery = "";
    private int fileShown = PAGE_SIZE;
    private EditText fileSearch;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PetStore(this);
        vmd = new VmdManager(this);

        final boolean glb0 = glbMode();

        SettingsPage page = new SettingsPage(this);
        page.pageTitle(glb0 ? "模型动画" : "VMD 动画",
                glb0
                        ? "当前是 glTF 模型，动作库用它【自带】的动作（不用 VMD 文件）。"
                          + "把动作绑到待机 / 走路 / 摸头等时机即可 —— 和 MMD 模型完全一样的用法。"
                        : "导入 MMD 动作文件（.vmd / .zip），绑定到待机、走路、摸头等时机。"
                          + "需要把渲染模式切成「3D 模型」才会生效。");

        /* ---------------- 当前模型 ---------------- */
        page.section("当前模型");
        modelView = page.valueRowWide("模型", "");

        /* ---------------- 总开关 ---------------- */
        page.section("开关");

        enableRow = page.toggle(glb0 ? "启用模型动画" : "启用 VMD 动画",
                "关闭后回到内置的程序化待机（呼吸 / 眨眼 / 轻微摇摆）",
                store.vmdConfig().enabled,
                (row, on) -> {
                    PetStore.VmdConfig c = store.vmdConfig();
                    c.enabled = on;
                    store.saveVmdConfig(c);
                    push();
                    toast(on ? "已启用动画" : "已关闭动画（回到程序化待机）");
                });

        /* ---------------- 动作绑定 ---------------- */
        page.section("动作绑定");
        page.info("点一行选择动作，可多选。"
                + "★ 勾选的先后顺序 = 播放顺序（多选时生效）；"
                + "选多个时的「叠加 / 依次播放」在下面的「高级」里切换。");
        slotBox = new LinearLayout(this);
        slotBox.setOrientation(LinearLayout.VERTICAL);
        page.custom(slotBox, false);

        /* ---------------- 待机随机表演 ---------------- */
        page.section("待机随机表演");
        randomBox = new LinearLayout(this);
        randomBox.setOrientation(LinearLayout.VERTICAL);
        page.custom(randomBox, false);

        /* ---------------- 高级 ---------------- */
        page.section("高级");

        page.toggle("锁定根骨骼位移（原地播放）",
                "走路动作自带「往前走」的位移。开着 → 模型原地走，由窗口移动产生位移；"
                        + "关掉 → 能看到动作原版效果（会在窗口里走偏）",
                store.vmdConfig().rootMotionLock,
                (row, on) -> saveVmd(c -> c.rootMotionLock = on));

        page.toggle("走动时侧身朝行进方向",
                "往右走转 +90°、往左走转 -90°，走完自动转回正面（用 Y 轴旋转，不是镜像）",
                store.vmdConfig().faceLeftOnWalk,
                (row, on) -> saveVmd(c -> c.faceLeftOnWalk = on));

        page.toggle("多个动作叠加播放",
                "开启（默认）：选中的动作同时播放并按权重混合 —— 适合「动作 + 表情」。\n"
                        + "关闭：按你设定的顺序依次播放 —— 摸头/摸身/摸脚/见面播一遍就结束；"
                        + "走路/待机播完再回第一个，无限循环",
                store.vmdConfig().overlayOneShot,
                (row, on) -> {
                    saveVmd(c -> c.overlayOneShot = on);
                    toast(on ? "已改为【叠加播放】" : "已改为【顺序播放】");
                });

        page.toggle("一次性动作只播一圈",
                "很多动作文件本身就是循环动作，里面烘焙了好几圈（实测「声援」装了 2 圈）——"
                        + "当「点一下播一次」用时看着像重复了两次。开启后自动裁到一圈",
                store.vmdConfig().trimLoop,
                (row, on) -> {
                    saveVmd(c -> c.trimLoop = on);
                    toast(on ? "已开启（循环类动作只播一圈）" : "已关闭（播完整文件）");
                });

        /* ---------------- 动作库 ---------------- */
        page.section(glb0 ? "模型自带动作" : "动作库");

        CardView2 fileCard = page.currentCard();
        fileHint = page.info("");
        /* page.info 已经把它挂进卡片了，这里拿到引用即可 */

        /* 搜索：动作多了以后手翻不现实（实测 165 条） */
        fileSearch = new EditText(this);
        fileSearch.setHint("搜索动作名（日文 / 中文都可以）");
        fileSearch.setTextSize(14f);
        fileSearch.setTextColor(UiTheme.TEXT_PRIMARY);
        fileSearch.setHintTextColor(0xFFA8A8AD);
        fileSearch.setSingleLine(true);
        fileSearch.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
        int sh = UiTheme.dp(this, 11f);
        fileSearch.setPadding(sh, sh, sh, sh);
        LinearLayout searchWrap = new LinearLayout(this);
        searchWrap.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        int pv = UiTheme.dp(this, UiTheme.PAD_V);
        searchWrap.setPadding(ph, pv, ph, pv);
        searchWrap.addView(fileSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fileCard.addRow(searchWrap);

        fileSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                fileQuery = e == null ? "" : e.toString().trim();
                fileShown = PAGE_SIZE;          // 换了搜索词就回到第一页
                /* 防抖 300ms：否则每敲一个字都要重建一批 View */
                handler.removeCallbacks(searchDebounce);
                handler.postDelayed(searchDebounce, 300L);
            }
        });

        /* 翻译进度横幅：实时显示"已翻 N / 共 M" + 进度条。
           ★ 翻 152 条要好几分钟，之前界面上全是"翻译中…"，
             完全看不出有没有在进展。 */
        LinearLayout bannerWrap = new LinearLayout(this);
        bannerWrap.setOrientation(LinearLayout.VERTICAL);
        bannerWrap.setPadding(ph, UiTheme.dp(this, 10f), ph, UiTheme.dp(this, 12f));

        transBanner = UiTheme.text(this, "", 13f, UiTheme.accent(), false);
        transBanner.setBackground(UiTheme.round(this, 0xFFEAF2F2, 12f));
        int bh = UiTheme.dp(this, 11f);
        transBanner.setPadding(bh, bh, bh, bh);
        transBanner.setLineSpacing(UiTheme.dp(this, 2f), 1f);
        transBanner.setVisibility(View.GONE);
        bannerWrap.addView(transBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        transProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        transProgress.setMax(100);
        transProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 6f));
        plp.topMargin = UiTheme.dp(this, 8f);
        bannerWrap.addView(transProgress, plp);

        bannerCardRow = bannerWrap;
        bannerWrap.setVisibility(View.GONE);   // 没在翻译时不留空白
        fileCard.addRow(bannerWrap);

        /* 列表本体（分页渲染在这里） */
        fileBox = new LinearLayout(this);
        fileBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setPadding(ph, UiTheme.dp(this, 4f), ph, UiTheme.dp(this, 6f));
        listWrap.addView(fileBox);
        fileCard.addRow(listWrap);

        /* 底部操作：翻译 + 导入 */
        page.info(glb0
                ? "这些动作来自模型文件本身，不能新增或删除。"
                  + "名字是英文/日文时，可以用下面的「翻译动作名」翻成中文。"
                  + "长按动作名可以看它的原始 id。"
                : ".zip 里的目录层级会保留；<名字>_Motion.vmd 与 <名字>_Morph.vmd 会自动合并成一个动作。"
                  + "长按动作名可以看它的原文件名与路径。");

        page.actions(glb0 ? "翻译动作名（英文 → 中文）" : "翻译动作名（日文 → 中文）",
                this::onTranslateClicked,
                glb0 ? null : "导入 .vmd / .zip",
                glb0 ? null : this::pickFiles);

        setContentView(page.build());

        modelView.setText(currentModelLine());
        buildSlots();
        buildRandomBox();
        scanFiles();
    }

    /** 进度横幅那一行的容器（需要整块显示/隐藏）。 */
    private LinearLayout bannerCardRow;

    @Override protected void onResume() {
        super.onResume();
        if (enableRow != null) enableRow.setChecked(store.vmdConfig().enabled);
        /* 模型可能在别处换过：刷新一次当前模型行 */
        if (modelView != null) modelView.setText(currentModelLine());
        buildSlots();
        buildRandomBox();
        /* ★ 打开本页就请求补齐 VMD 名称的译文（和配件页同样的做法）；
           翻译中每 1.2 秒刷新一次列表，翻完自动停。 */
        requestVmdTranslate();
        scheduleRefreshWhileTranslating();
        scheduleGlbWait();          // GLB 动作清单可能还没上报，等一下
    }

    @Override protected void onPause() {
        super.onPause();
        /* ★ 必须停：这个轮询每 1.2s 重建一次列表，留着会一直吃 CPU */
        handler.removeCallbacksAndMessages(null);
    }

    /* ================= 小工具 ================= */

    /** 修改 VmdConfig 并落库 + 下发（唯一的写入入口）。 */
    private void saveVmd(EditCallback edit) {
        PetStore.VmdConfig c = store.vmdConfig();
        edit.apply(c);
        store.saveVmdConfig(c);
        push();
    }

    private interface EditCallback {
        void apply(PetStore.VmdConfig c);
    }

    private String currentModelLine() {
        String u = store.appearance().mmdModelUrl;
        if (u == null || u.isEmpty()) return "（未选择模型 —— 先在主界面选一个 3D 模型）";
        int k = u.lastIndexOf('/');
        String leaf = k >= 0 ? u.substring(k + 1) : u;
        return leaf + (glbMode() ? "\n· glTF 模型：动作来自模型本身，不能增删" : "");
    }

    /* ================= 翻译 ================= */

    private void onTranslateClicked() {
        PetStore.TranslateConfig cfg = store.translateConfig();
        if (!cfg.isUsable()) {
            toast("还没配置翻译服务，先去「3D 模型 → 名称翻译」");
            return;
        }
        int pending = pendingTranslateNames().size();
        if (pending == 0) { toast("没有需要翻译的动作名"); return; }
        /* 手动触发：服务在跑时走服务，否则本页自己翻 */
        PetOverlayServiceBridge.requestTranslate(true);
        if (!PetOverlayServiceBridge.translateAvailable()) runLocalTranslate();
        else toast("已请求翻译 " + pending + " 个");
    }

    /** 刷新翻译进度横幅（每批完成都会调到）。 */
    private void updateTransStatus() {
        try {
            if (transBanner == null || transProgress == null) return;
            boolean active = localTranslating || PetOverlayServiceBridge.isTranslating();
            if (!active || transTotal <= 0) {
                transBanner.setVisibility(View.GONE);
                transProgress.setVisibility(View.GONE);
                if (bannerCardRow != null) bannerCardRow.setVisibility(View.GONE);
                return;
            }
            int pct = Math.max(0, Math.min(100, transDone * 100 / Math.max(1, transTotal)));
            if (bannerCardRow != null) bannerCardRow.setVisibility(View.VISIBLE);
            transBanner.setVisibility(View.VISIBLE);
            transBanner.setText("⟳ 正在翻译动作名：已完成 " + Math.max(0, transDone)
                    + " / " + transTotal + "（" + pct + "%）");
            transProgress.setVisibility(View.VISIBLE);
            transProgress.setProgress(pct);
        } catch (Throwable ignored) {}
    }

    /**
     * GLB 动作清单是异步上报的（WebView 加载完模型才有）→ 空的时候轮询等它。
     * ★ 没有这个的话，用户从设置页直接进这个页面会看到"没有自带动作"。
     */
    private void scheduleGlbWait() {
        if (isFinishing()) return;
        if (!glbMode() || !actionList().isEmpty()) return;
        handler.postDelayed(() -> {
            if (isFinishing()) return;
            if (!glbMode()) return;
            renderFiles();
            buildSlots();
            scheduleGlbWait();
        }, 1500L);
    }

    /** 翻译进行中时定期刷新列表（译文到达后自动显示中文）。 */
    private void scheduleRefreshWhileTranslating() {
        if (isFinishing()) return;
        renderFiles();
        updateTransStatus();
        if (!PetOverlayServiceBridge.isTranslating() && !localTranslating) {
            updateTransStatus();
            return;
        }
        handler.postDelayed(this::scheduleRefreshWhileTranslating, 1200);
    }

    /**
     * 请求翻译 VMD 动作名。
     * ★ 优先走桌宠服务（统一的"翻译中"状态、能推给 JS）；
     *   服务没启动就自己直连翻译 —— 否则用户会看到"翻译完全没反应"。
     */
    private void requestVmdTranslate() {
        PetOverlayServiceBridge.requestTranslate(false);
        if (!PetOverlayServiceBridge.translateAvailable()) {
            runLocalTranslate();
        }
    }

    /** 需要翻的动作名（跳过内置的、纯 ASCII 的、已有译文的）。 */
    private List<String> pendingTranslateNames() {
        List<String> todo = new ArrayList<>();
        java.util.Map<String, String> cached = store.translations();
        for (VmdManager.VmdFile f : actionList()) {
            if (f.preset) continue;
            if (!needsTranslate(f.name)) continue;
            if (cachedZh(f) != null) continue;      // 新旧缓存都算
            todo.add(f.name);
        }
        return todo;
    }

    /** 名字里是否有需要翻译的非 ASCII 字符（日文假名/汉字等）。 */
    private static boolean needsTranslate(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return true;
        }
        return false;
    }

    /**
     * 兜底：本页自己跑翻译（不依赖桌宠服务）。
     * 只增不改地写进全局译文缓存，所以和服务那条通道不会互相覆盖。
     */
    private void runLocalTranslate() {
        if (localTranslating) return;
        final List<String> todo = pendingTranslateNames();
        if (todo.isEmpty()) return;
        PetStore.TranslateConfig cfg = store.translateConfig();
        if (!cfg.isUsable()) {
            toast("还没配置翻译服务（3D 模型 → 名称翻译）");
            return;
        }
        localTranslating = true;
        localTranslatingNames = new java.util.LinkedHashSet<>(todo);
        transDone = 0;
        transTotal = todo.size();
        android.util.Log.i("PetVmd", "本地翻译开始: " + todo.size() + " 个");
        /* ★ 每完成一批就：写进缓存 + 刷新列表 + 更新进度，用户能看着它一条条变中文。
           （以前是全部跑完才写一次，中途界面毫无变化 —— 用户以为卡死了。） */
        me.rerere.pet.data.TranslationService.setProgressListener((done, total, gotBatch) -> {
            if (gotBatch != null && !gotBatch.isEmpty()) {
                store.mergeTranslations(gotBatch);       // 立刻落盘
            }
            transDone = done;
            transTotal = total;
            handler.post(() -> {
                if (isFinishing()) return;
                renderFiles();                            // 列表实时更新
                updateTransStatus();
            });
        });
        new Thread(() -> {
            try {
                java.util.Map<String, String> got =
                        me.rerere.pet.data.TranslationService.translate(store, cfg, todo);
                if (got != null && !got.isEmpty()) {
                    store.mergeTranslations(got);        // ★ 只增不改
                }
                final int n = (got == null) ? 0 : got.size();
                final String fatal = me.rerere.pet.data.TranslationService.lastFatalError();
                android.util.Log.i("PetVmd", "本地翻译完成: " + n + " / " + todo.size()
                        + (fatal == null ? "" : ("  硬错误=" + fatal)));
                handler.post(() -> {
                    localTranslating = false;
                    localTranslatingNames = new java.util.HashSet<>();
                    transDone = -1;
                    me.rerere.pet.data.TranslationService.setProgressListener(null);
                    renderFiles();
                    updateTransStatus();
                    /* ★ 把失败原因直接说出来 —— 只说"没翻出结果"用户无从下手。 */
                    if (n == 0) {
                        String diag = me.rerere.pet.data.TranslationService.lastDiagnostic();
                        StringBuilder msg = new StringBuilder();
                        if (fatal != null) {
                            msg.append("翻译服务返回了错误：\n").append(fatal).append("\n\n");
                        }
                        msg.append("去「名称翻译」检查服务商 / 模型 / Key。");
                        if (diag != null && !diag.isEmpty()) {
                            msg.append("\n\n──────── 过程日志 ────────\n").append(diag);
                        }
                        TextView tv = new TextView(this);
                        tv.setText(msg.toString());
                        tv.setTextSize(12f);
                        tv.setLineSpacing(UiTheme.dp(this, 2f), 1f);
                        tv.setTextIsSelectable(true);
                        int p20 = UiTheme.dp(this, 16f);
                        tv.setPadding(p20, UiTheme.dp(this, 8f), p20, UiTheme.dp(this, 8f));
                        ScrollView sv = new ScrollView(this);
                        sv.addView(tv);
                        new AlertDialog.Builder(this)
                                .setTitle("翻译失败")
                                .setView(sv)
                                .setPositiveButton("知道了", null)
                                .show();
                    } else {
                        toast("翻译完成：" + n + " 个");
                    }
                });
            } catch (Throwable t) {
                android.util.Log.e("PetVmd", "本地翻译失败", t);
                handler.post(() -> {
                    localTranslating = false;
                    localTranslatingNames = new java.util.HashSet<>();
                    transDone = -1;
                    me.rerere.pet.data.TranslationService.setProgressListener(null);
                    renderFiles();
                    updateTransStatus();
                    toast("翻译失败：" + t);
                });
            }
        }, "vmd-translate").start();
    }

    /* ================= 插槽 ================= */

    private void buildSlots() {
        if (slotBox == null) return;
        slotBox.removeAllViews();
        PetStore.VmdConfig c = store.vmdConfig();
        boolean first = true;

        for (final String slot : PetStore.VMD_SLOTS) {
            if (!first) {
                View d = new View(this);
                d.setBackgroundColor(UiTheme.DIVIDER);
                slotBox.addView(d, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.hairline(this)));
            }
            first = false;

            /* ★ 按当前模型类型取插槽（glTF 与 MMD 各存一份，互不覆盖） */
            final List<String> sel = c.slotFor(slot, glbMode());
            /* ★ glTF 模型下，插槽里若绑的是 VMD 动作 → 对他无效（会被跳过），
                 必须明确提示，否则用户点"试播"毫无反应、完全不知道原因。 */
            int badCount = 0;
            if (glbMode()) {
                for (String id : sel) if (id.indexOf("glb:") != 0) badCount++;
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int ph = UiTheme.dp(this, UiTheme.PAD_H);
            int pv = UiTheme.dp(this, UiTheme.PAD_V);
            row.setPadding(ph, pv, ph, pv);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);

            /* 标题 = 插槽名 + 状态（用颜色区分：未设置灰 / 已绑青 / 有问题橙） */
            TextView t = UiTheme.text(this, PetStore.vmdSlotLabel(slot), 17f,
                    UiTheme.TEXT_PRIMARY, true);
            col.addView(t);

            TextView status = UiTheme.text(this, slotStatusText(slot, sel, badCount), 13f,
                    badCount > 0 ? 0xFFB26A00
                            : (sel.isEmpty() ? UiTheme.TEXT_SECONDARY : UiTheme.accent()),
                    false);
            status.setLineSpacing(UiTheme.dp(this, 2f), 1f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = UiTheme.dp(this, 4f);
            col.addView(status, slp);

            /* 说明：告诉用户"这个时机是什么"（比旧的整段 hint 短一半） */
            TextView h = UiTheme.text(this, PetStore.vmdSlotHint(slot), 12f,
                    UiTheme.TEXT_SECONDARY, false);
            h.setLineSpacing(UiTheme.dp(this, 2f), 1f);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = UiTheme.dp(this, 4f);
            col.addView(h, hlp);

            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            /* 试播（只有真的播得出来才显示） */
            if (canPlay(slot, sel)) {
                TextView play = UiTheme.text(this, "试播", 13f, UiTheme.accent(), true);
                play.setGravity(Gravity.CENTER);
                play.setPadding(UiTheme.dp(this, 10f), UiTheme.dp(this, 8f),
                        UiTheme.dp(this, 10f), UiTheme.dp(this, 8f));
                play.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
                final String slotId = slot;
                play.setOnClickListener(v -> tryPlay(slotId));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.rightMargin = UiTheme.dp(this, 8f);
                row.addView(play, plp);
            }

            row.setClickable(true);
            row.setOnClickListener(v -> pickSlot(slot));
            slotBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            /* 多选时：每个动作一行，带 ①② 序号 + ↑↓ 调整 + ✕ 移除
               （实测"我不知道播放的顺序" → 顺序必须看得见、调得动） */
            if (sel.size() > 1) {
                slotBox.addView(orderedList(slot, new ArrayList<>(sel)),
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    /** 插槽状态文字（"未设置" / "N 个动作 · 依次播放" / "⚠ N 个 VMD 对此模型无效"）。 */
    private String slotStatusText(String slot, List<String> sel, int badCount) {
        if (badCount > 0) {
            return "⚠ 绑了 " + badCount + " 个 VMD 动作 —— 对当前 glTF 模型无效，请点这里重新选择";
        }
        if (sel.isEmpty()) return "未设置（用内置的程序化动作）";
        if (sel.size() == 1) return "已绑定 1 个动作";
        PetStore.VmdConfig c = store.vmdConfig();
        /* 循环类插槽（待机/走路）播完回到第一个；其余播完就结束 */
        boolean loopSlot = PetStore.VMD_SLOTS[0].equals(slot) || "walk".equals(slot);
        return "已绑定 " + sel.size() + " 个动作 · "
                + (c.overlayOneShot ? "叠加播放"
                   : (loopSlot ? "依次循环播放" : "依次播放"));
    }

    /** 这个插槽现在点「试播」能不能真的播出东西。 */
    private boolean canPlay(String slot, List<String> sel) {
        if (sel.isEmpty()) return false;
        if (!glbMode()) return true;
        for (String id : sel) if (id.indexOf("glb:") == 0) return true;
        return false;
    }

    private void tryPlay(String slot) {
        /* ★ 先判断这次试播【是否真的播得出来】——
           旧版无论有没有动作都提示"已请求试播"，用户点了没反应还以为坏了。 */
        if (glbMode()) {
            List<String> ids = store.vmdConfig().slotFor(slot, true);
            boolean hasGlb = false;
            for (String id : ids) if (id.indexOf("glb:") == 0) hasGlb = true;
            if (!hasGlb) {
                toast("这个插槽绑的是 VMD 动作，glTF 模型播不了 —— 点这里重新选");
                return;
            }
        } else if (store.vmdConfig().slotFor(slot, false).isEmpty()) {
            toast("这个插槽还没选动作");
            return;
        }
        if (PetOverlayService.current() == null) {
            toast("桌宠没在运行，先在主界面开启桌宠");
            return;
        }
        PetOverlayServiceBridge.playVmdSlot(slot);
        toast("已请求试播：" + PetStore.vmdSlotLabel(slot));
    }

    /** 对话框里显示的名字：有译文就用译文，没有就用原文（不显示"翻译中"）。 */
    private String dialogLabel(VmdManager.VmdFile f) {
        if (f.preset) return f.name;
        String zh = cachedZh(f);
        if (zh != null && !zh.isEmpty() && !zh.equals(f.name)) return zh;
        return f.name;
    }

    /** ①②③… 序号标记。 */
    private static String ordMark(int i) {
        String CIR = "\u2460\u2461\u2462\u2463\u2464\u2465\u2466\u2467\u2468\u2469"
                   + "\u246A\u246B\u246C\u246D\u246E\u246F\u2470\u2471\u2472\u2473";
        if (i < CIR.length()) return String.valueOf(CIR.charAt(i));
        return (i + 1) + ".";
    }

    /** 插槽里的动作顺序表（带上下移/移除）。 */
    private LinearLayout orderedList(final String slot, final List<String> ids) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        box.setPadding(ph, 0, ph, UiTheme.dp(this, 12f));

        TextView head = UiTheme.text(this, "播放顺序（↑↓ 调整，✕ 移除）", 12f,
                UiTheme.TEXT_SECONDARY, false);
        head.setPadding(0, 0, 0, UiTheme.dp(this, 4f));
        box.addView(head);

        for (int i = 0; i < ids.size(); i++) {
            final int idx = i;
            final String id = ids.get(i);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, UiTheme.dp(this, 2f), 0, UiTheme.dp(this, 2f));

            String nm = displayOf(id);
            for (VmdManager.VmdFile vf : actionList()) {
                if (vf.id.equals(id)) { nm = dialogLabel(vf); break; }
            }
            TextView name = UiTheme.text(this, ordMark(i) + " " + nm, 13f,
                    UiTheme.accent(), false);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            line.addView(ordBtn("\u2191", idx > 0 ? () -> moveSlot(slot, idx, -1) : null));
            line.addView(ordBtn("\u2193", idx < ids.size() - 1
                    ? () -> moveSlot(slot, idx, 1) : null));
            line.addView(ordBtn("\u2715", () -> removeSlot(slot, id)));
            box.addView(line);
        }
        return box;
    }

    /** 小按钮（传 null 表示禁用/置灰）。 */
    private TextView ordBtn(String text, final Runnable onClick) {
        TextView t = UiTheme.text(this, text, 14f,
                onClick == null ? 0xFFCFCFD6 : UiTheme.accent(), false);
        t.setPadding(UiTheme.dp(this, 10f), UiTheme.dp(this, 4f),
                UiTheme.dp(this, 10f), UiTheme.dp(this, 4f));
        if (onClick != null) t.setOnClickListener(v -> onClick.run());
        return t;
    }

    /** 调整插槽里某个动作的位置。 */
    private void moveSlot(String slot, int idx, int delta) {
        PetStore.VmdConfig c = store.vmdConfig();
        List<String> l = c.slotFor(slot, glbMode());
        int to = idx + delta;
        if (idx < 0 || idx >= l.size() || to < 0 || to >= l.size()) return;
        java.util.Collections.swap(l, idx, to);
        store.saveVmdConfig(c);
        buildSlots();
        push();
    }

    /** 把某个动作从插槽里移除。 */
    private void removeSlot(String slot, String id) {
        PetStore.VmdConfig c = store.vmdConfig();
        c.slotFor(slot, glbMode()).remove(id);
        store.saveVmdConfig(c);
        buildSlots();
        push();
    }

    private String displayOf(String id) {
        for (VmdManager.VmdFile f : actionList()) if (f.id.equals(id)) return f.name;
        /* ★ glTF 动作的 id 是 "glb:<动作名>"：
           模型还没加载完 / 换了模型时 list 里没有它，但绝不能把
           "glb:Victory_Start" 这种内部 id 直接怼给用户看
           （第一版就这样，列表里显示成 "glb:Victory_Start_Random (文件已不存在)"）。 */
        if (id != null && id.indexOf("glb:") == 0) {
            return id.substring(4) + "（模型里没有）";
        }
        // 文件可能已被删除：显示最后一段名字，并打个提示
        int k = id.lastIndexOf('/');
        String base = k >= 0 ? id.substring(k + 1) : id;
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".vmd")) {
            base = base.substring(0, base.length() - 4);
        }
        return base + "（文件已不存在）";
    }

    private void pickSlot(String slot) {
        List<VmdManager.VmdFile> all = actionList();
        if (all.isEmpty()) {
            toast(scanning ? "正在扫描动作库…"
                    : (glbMode() ? "这个 GLB 没有自带动作"
                                 : "动作库是空的，先导入 VMD"));
            return;
        }
        PetStore.VmdConfig c = store.vmdConfig();
        List<String> current = new ArrayList<>(c.slotFor(slot, glbMode()));

        CharSequence[] items = new CharSequence[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            VmdManager.VmdFile f = all.get(i);
            items[i] = dialogLabel(f) + "   （" + fmtDur(f.duration)
                    + (f.morphOnly ? "，仅表情" : "") + "）";
            checked[i] = current.contains(f.id);
        }

        /* ★★ 必须用 listener 自己维护勾选状态（踩过的大坑）★★
           Android 的 AlertDialog.getCheckedItemPositions() 【只记录用户点过的项】，
           不包含 setMultiChoiceItems(items, checked, ...) 预置的勾选。
           所以"打开对话框 → 直接点确定"会读到全空 → 【把绑定清空】。
           用户看到的现象就是"选不了动作 / 绑定自己没了"。 */
        final boolean[] sel = checked.clone();
        /* ★★ 用"勾选顺序"决定播放顺序 ★★
           以前是遍历动作库列表收集 → 顺序 = 列表顺序，用户完全没法决定。 */
        final List<String> order = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (checked[i]) order.add(all.get(i).id);      // 初始按原顺序
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(PetStore.vmdSlotLabel(slot) + "（可多选）");
        /* ★★ 绝对不要在这里用 setMessage()！★★
           AlertDialog 的 setMessage 和 setMultiChoiceItems 会抢同一个内容区，
           实测结果是【复选框列表整个不显示】，用户看到的就是一个空对话框。 */
        b.setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
            if (which < 0 || which >= sel.length) return;
            sel[which] = isChecked;
            String id = all.get(which).id;
            if (isChecked) {
                if (!order.contains(id)) order.add(id);     // 追加到顺序末尾
            } else {
                order.remove(id);
            }
        });
        b.setPositiveButton("确定", (d, w) -> {
            List<String> picked = new ArrayList<>(order);
            PetStore.VmdConfig cc = store.vmdConfig();
            /* ★ 只替换【当前模型类型】那一份 —— 另一份原样保留。
               这就是"用完 glb 切回 mmd，MMD 的动作还在"的关键。 */
            List<String> l = cc.slotFor(slot, glbMode());
            l.clear();
            l.addAll(picked);
            store.saveVmdConfig(cc);
            buildSlots();
            push();
            toast(picked.isEmpty()
                    ? PetStore.vmdSlotLabel(slot) + " 已清空"
                    : PetStore.vmdSlotLabel(slot) + " 已设为 " + picked.size() + " 个动作");
        });
        b.setNegativeButton("取消", null);
        b.setNeutralButton("清空", (d, w) -> {
            PetStore.VmdConfig cc = store.vmdConfig();
            cc.slotFor(slot, glbMode()).clear();
            store.saveVmdConfig(cc);
            buildSlots();
            push();
        });
        b.show();
    }

    /* ================= 随机表演 ================= */

    private void buildRandomBox() {
        if (randomBox == null) return;
        randomBox.removeAllViews();
        final PetStore.VmdConfig c = store.vmdConfig();

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        ToggleRowView sw = new ToggleRowView(this);
        sw.setTitle("待机时随机表演动作")
          .setSubtitle("桌宠闲着的时候，隔一段时间随机抽一个动作播一次。"
                  + "拖拽中 / 对话打开 / 正在互动时不会播")
          .setChecked(c.randomIdle);
        sw.setOnToggledListener((row, on) -> {
            PetStore.VmdConfig cc = store.vmdConfig();
            cc.randomIdle = on;
            store.saveVmdConfig(cc);
            push();
            buildRandomBox();
        });
        wrap.addView(sw);

        if (c.randomIdle) {
            wrap.addView(divider());

            /* 间隔 */
            wrap.addView(navRow("间隔：每隔 " + c.randomMinSec + " ~ " + c.randomMaxSec
                            + " 秒随机表演一次",
                    "点一下修改（有常用节奏预设）",
                    () -> editInterval()));
            wrap.addView(divider());

            /* 可用动作池 */
            String poolText = c.randomPool.isEmpty()
                    ? "可用动作：（还没选，随机表演不会播）"
                    : "可用动作：" + c.randomPool.size() + " 个";
            wrap.addView(navRow(poolText,
                    "只有勾选的动作会被随机抽到；建议只挑「表演类」"
                            + "（挥手/鞠躬/鼓掌…），不要放走路/跑步这类循环动作",
                    this::pickRandomPool));
        }
        randomBox.addView(wrap);
    }

    /** 0.5dp 通栏分割线。 */
    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(UiTheme.DIVIDER);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.hairline(this)));
        return d;
    }

    /** 一行可点的"标题 + 说明 + ›"。 */
    private LinearLayout navRow(String title, String subtitle, final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        int pv = UiTheme.dp(this, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(UiTheme.title(this, title));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = UiTheme.subtitle(this, subtitle);
            s.setLineSpacing(UiTheme.dp(this, 2f), 1f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = UiTheme.dp(this, 4f);
            col.addView(s, slp);
        }
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = UiTheme.text(this, "\u203A", 24f, 0xFF9AA0A6, false);
        chev.setGravity(Gravity.CENTER);
        row.addView(chev);

        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    /**
     * 随机表演间隔设置。
     *
     * ★ 参数只留 2 个（实测：原来那三个数字看不懂）。
     *   原来的第三个"单次播放秒数"已删除 ——
     *   动作的时长是【文件里自带的】（VMD 帧数），播放时改不了。
     */
    private void editInterval() {
        final PetStore.VmdConfig c = store.vmdConfig();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p20 = UiTheme.dp(this, 20f);
        box.setPadding(p20, UiTheme.dp(this, 8f), p20, UiTheme.dp(this, 4f));

        TextView desc = UiTheme.subtitle(this,
                "桌宠闲着的时候，每隔一段随机的时间就抽一个动作播一次。"
                        + "两次表演之间的间隔会在下面两个数之间随机取。");
        desc.setLineSpacing(UiTheme.dp(this, 2f), 1f);
        box.addView(desc);

        final EditText min = addNumField(box, "① 最短间隔", "秒 —— 至少等这么久才考虑下一次",
                c.randomMinSec, 5, 600);
        final EditText max = addNumField(box, "② 最长间隔", "秒 —— 最多等这么久（要 ≥ 上面的数）",
                c.randomMaxSec, 5, 900);

        /* 常用节奏预设：省得自己算"多少秒合适" */
        TextView presetTitle = UiTheme.subtitle(this, "常用节奏（点一下直接填进去）");
        presetTitle.setPadding(0, UiTheme.dp(this, 14f), 0, UiTheme.dp(this, 6f));
        box.addView(presetTitle);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        int[][] presets = {{15, 40}, {30, 90}, {60, 180}, {120, 300}};
        String[] presetText = {"勤快 15~40", "常用 30~90", "悠闲 1~3分", "很少 2~5分"};
        for (int i = 0; i < presets.length; i++) {
            final int a = presets[i][0], b = presets[i][1];
            TextView pv = UiTheme.text(this, presetText[i], 12f, UiTheme.accent(), false);
            pv.setGravity(Gravity.CENTER);
            pv.setPadding(UiTheme.dp(this, 6f), UiTheme.dp(this, 9f),
                    UiTheme.dp(this, 6f), UiTheme.dp(this, 9f));
            pv.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
            pv.setOnClickListener(v -> {
                min.setText(String.valueOf(a));
                max.setText(String.valueOf(b));
            });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) plp.leftMargin = UiTheme.dp(this, 6f);
            presetRow.addView(pv, plp);
        }
        box.addView(presetRow);

        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("随机表演间隔")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    int a = parseInt(min.getText().toString(), c.randomMinSec);
                    int b = parseInt(max.getText().toString(), c.randomMaxSec);
                    a = Math.max(5, Math.min(600, a));
                    b = Math.max(a + 5, Math.min(900, b));
                    PetStore.VmdConfig cc = store.vmdConfig();
                    cc.randomMinSec = a;
                    cc.randomMaxSec = b;
                    store.saveVmdConfig(cc);
                    buildRandomBox();
                    push();
                    toast("已保存：每 " + a + " ~ " + b + " 秒随机表演一次");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 一行"标题 + 说明 + 数字输入框"，返回输入框。 */
    private EditText addNumField(LinearLayout parent, String title, String desc,
                                 int value, int lo, int hi) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, UiTheme.dp(this, 14f), 0, 0);

        box.addView(UiTheme.text(this, title + "（" + lo + "~" + hi + "）",
                15f, UiTheme.TEXT_PRIMARY, true));

        TextView d = UiTheme.text(this, desc, 11f, UiTheme.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = UiTheme.dp(this, 3f);
        box.addView(d, dlp);

        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(value));
        et.setTextSize(16f);
        et.setTextColor(UiTheme.TEXT_PRIMARY);
        et.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
        int p = UiTheme.dp(this, 12f);
        et.setPadding(p, p, p, p);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = UiTheme.dp(this, 8f);
        box.addView(et, elp);

        parent.addView(box);
        return et;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    private void pickRandomPool() {
        List<VmdManager.VmdFile> all = actionList();
        if (all.isEmpty()) { toast("动作库是空的"); return; }
        PetStore.VmdConfig c = store.vmdConfig();

        CharSequence[] items = new CharSequence[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            VmdManager.VmdFile f = all.get(i);
            items[i] = dialogLabel(f) + "   （" + fmtDur(f.duration) + "）";
            checked[i] = c.randomPool.contains(f.id);
        }
        /* ★ 同上：不能用 getCheckedItemPositions()（预置勾选不在里面） */
        final boolean[] sel2 = checked.clone();
        new AlertDialog.Builder(this)
                .setTitle("随机表演可用的动作")
                .setMultiChoiceItems(items, checked, (d, w, on) -> {
                    if (w >= 0 && w < sel2.length) sel2[w] = on;
                })
                .setPositiveButton("确定", (d, w) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < all.size() && i < sel2.length; i++) {
                        if (sel2[i]) picked.add(all.get(i).id);
                    }
                    PetStore.VmdConfig cc = store.vmdConfig();
                    cc.randomPool.clear();
                    cc.randomPool.addAll(picked);
                    store.saveVmdConfig(cc);
                    buildRandomBox();
                    toast("已选 " + picked.size() + " 个动作");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ================= 动作库 ================= */

    private void scanFiles() {
        if (scanning) return;
        scanning = true;
        if (fileHint != null) {
            fileHint.setTextColor(UiTheme.TEXT_SECONDARY);
            fileHint.setText("正在扫描动作库…");
        }
        new Thread(() -> {
            List<VmdManager.VmdFile> list = null;
            String err = null;
            try {
                vmd.ensurePresets();
                list = vmd.list();
                android.util.Log.i("PetVmd", "scan ok: " + list.size() + " 个动作");
            } catch (Throwable t) {
                /* ★ 不要把错误伪装成"没有动作"（实测"列表直接消失"）。
                   这里保留旧列表 + 记下原因，界面会明确显示出来。 */
                android.util.Log.e("PetVmd", "scanFiles failed", t);
                err = String.valueOf(t);
            }
            final List<VmdManager.VmdFile> f = list;
            final String e2 = err;
            handler.post(() -> {
                scanning = false;
                scanError = e2;
                if (f != null) {
                    files = f;
                    fileShown = PAGE_SIZE;      // 重新扫描后回到第一页
                }
                renderFiles();
                buildSlots();
                /* ★ 扫描顺带做了 ensurePresets()（首启复制内置动作）。
                   复制完必须让桌宠重新下发一次配置，否则页面还在对着
                   404 的路径（表现为"内置动作一个都播不了"）。 */
                push();
                /* ★ 顺便请求翻译：用户导入的动作名是日文，翻成中文更好挑 */
                requestVmdTranslate();
                scheduleRefreshWhileTranslating();
            });
        }, "vmd-scan").start();
    }

    /** 当前模型是不是 glTF（.glb / .gltf）。 */
    private boolean glbMode() {
        String u = store.appearance().mmdModelUrl;
        if (u == null) return false;
        String l = u.toLowerCase(java.util.Locale.ROOT);
        return l.endsWith(".glb") || l.endsWith(".gltf");
    }

    /**
     * 动作库（唯一入口）。
     *
     * ★★ 为什么要有这层抽象 ★★
     *   模型分两类，动作来源完全不同：
     *     · MMD（.pmx/.pmd）→ 用户导入的 VMD 文件
     *     · glTF（.glb）   → 模型【自带】的 AnimationClip（不依赖 VMD）
     *   但下面所有 UI（渲染 / 选择 / 顺序播放 / 翻译）只关心"有个 id 和名字"，
     *   所以这里统一成同一个 VmdFile 列表，其余逻辑一行都不用改。
     */
    private List<VmdManager.VmdFile> actionList() {
        if (!glbMode()) return files;
        List<String> names = PetOverlayServiceBridge.glbClipNames();
        List<VmdManager.VmdFile> out = new ArrayList<>();
        for (String n : names) {
            if (n != null && !n.trim().isEmpty()) out.add(VmdManager.virtualGlb(n.trim()));
        }
        return out;
    }

    /** 查这个动作的译文（新键 → 旧长名键 → 叶子名，依次尝试）。 */
    private String cachedZh(VmdManager.VmdFile f) {
        java.util.Map<String, String> t = store.translations();
        String v = t.get(f.name);
        if (v != null && !v.isEmpty()) return v;
        /* 兼容旧缓存：以前按完整路径（空格连接）存的 */
        if (f.legacyName != null) {
            v = t.get(f.legacyName);
            if (v != null && !v.isEmpty()) return v;
            int k = f.legacyName.lastIndexOf(' ');
            if (k >= 0) {
                v = t.get(f.legacyName.substring(k + 1));
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return null;
    }

    private String labelOf(VmdManager.VmdFile f) {
        if (f.preset) return f.name;                 // 内置动作本来就是中文名
        String zh = cachedZh(f);
        if (zh == null || zh.isEmpty() || zh.equals(f.name)) {
            boolean pending = localTranslatingNames.contains(f.name)
                    || PetOverlayServiceBridge.isTranslatingName(f.name)
                    || (f.legacyName != null && (localTranslatingNames.contains(f.legacyName)
                        || PetOverlayServiceBridge.isTranslatingName(f.legacyName)));
            if (pending) return "⟳ 翻译中…";
            return f.name;
        }
        return zh;
    }

    /** 按搜索词过滤（译文也参与匹配，中/日文都能搜）。 */
    private List<VmdManager.VmdFile> filteredFiles() {
        String q = (fileQuery == null) ? "" : fileQuery.toLowerCase(java.util.Locale.ROOT);
        List<VmdManager.VmdFile> out = new ArrayList<>();
        for (VmdManager.VmdFile f : actionList()) {
            if (q.isEmpty()) { out.add(f); continue; }
            if (f.name.toLowerCase(java.util.Locale.ROOT).contains(q)
                    || labelOf(f).toLowerCase(java.util.Locale.ROOT).contains(q)
                    || f.id.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 渲染动作库。
     *
     * ★★ 两个必须遵守的约束（都是实测踩出来的）★★
     *   1) 不能一次性渲染全部条目 —— 165 条 ≈ 660 个 View 会把主线程卡死，
     *      用户看到的表现是"列表消失了"。所以分页 + 「显示更多」。
     *   2) 整个方法必须包 try/catch —— 它跑在主线程的 post 里，
     *      抛异常会直接崩掉 Activity（同样是"界面没了"）。
     */
    private void renderFiles() {
        if (fileBox == null) return;
        try {
            fileBox.removeAllViews();

            /* 扫描/导入出错时明确告诉用户（旧实现把错误伪装成"没有动作"） */
            if (scanError != null && fileHint != null) {
                fileHint.setText("⚠ " + scanError);
                fileHint.setTextColor(0xFFB26A00);
            } else if (fileHint != null) {
                fileHint.setTextColor(UiTheme.TEXT_SECONDARY);
            }

            final List<VmdManager.VmdFile> src = actionList();
            int presetCount = 0, userCount = 0;
            for (VmdManager.VmdFile f : src) {
                if (f.preset) presetCount++; else userCount++;
            }
            final List<VmdManager.VmdFile> shown = filteredFiles();
            if (fileHint != null && scanError == null) {
                if (glbMode()) {
                    /* ★ 空的时候别急着说"没有动作"★
                       动作清单是【页面上报】的：桌宠服务刚起来时 WebView
                       还在加载 GLB，这里拿到的就是空。 */
                    if (!src.isEmpty()) {
                        fileHint.setText("GLB 自带动作 " + src.size() + " 个"
                               + (fileQuery.isEmpty() ? "" : ("，匹配 " + shown.size() + " 个")));
                    } else {
                        fileHint.setText(PetOverlayServiceBridge.available()
                                ? "正在读取模型自带动作…（模型加载完会自动出现）"
                                : "桌宠未启动。开启桌宠后这里会列出该模型自带的动作。");
                        fileHint.setTextColor(0xFFB26A00);
                    }
                } else if (files.isEmpty()) {
                    fileHint.setText("动作库是空的。点下面按钮导入 .vmd 或 .zip。");
                } else if (!fileQuery.isEmpty()) {
                    fileHint.setText("匹配 " + shown.size() + " / " + files.size() + " 个动作");
                } else {
                    fileHint.setText("共 " + files.size() + " 个动作（内置 "
                            + presetCount + " · 导入 " + userCount + "）");
                }
            }

            int limit = Math.min(shown.size(), Math.max(PAGE_SIZE, fileShown));
            String lastGroup = null;
            boolean firstRow = true;
            for (int i = 0; i < limit; i++) {
                VmdManager.VmdFile f = shown.get(i);
                String group = glbMode() ? "GLB 自带动作"
                        : (f.preset ? "内置动作" : "导入的动作");
                if (!group.equals(lastGroup)) {
                    lastGroup = group;
                    fileBox.addView(groupLabel(group));
                    firstRow = true;
                }
                if (!firstRow) fileBox.addView(divider());
                firstRow = false;
                fileBox.addView(fileRow(f));
            }

            if (limit < shown.size()) {
                TextView more = UiTheme.text(this, "显示更多（还有 "
                        + (shown.size() - limit) + " 个）", 14f, UiTheme.accent(), true);
                more.setGravity(Gravity.CENTER);
                more.setPadding(UiTheme.dp(this, 8f), UiTheme.dp(this, 16f),
                        UiTheme.dp(this, 8f), UiTheme.dp(this, 16f));
                more.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
                more.setOnClickListener(v -> {
                    fileShown += PAGE_SIZE * 2;      // 翻页时一次多给一点
                    renderFiles();
                });
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.topMargin = UiTheme.dp(this, 8f);
                fileBox.addView(more, mlp);
            }
        } catch (Throwable t) {
            android.util.Log.e("PetVmd", "renderFiles failed", t);
            try {
                if (fileHint != null) {
                    fileHint.setText("列表渲染失败：" + t);
                    fileHint.setTextColor(0xFFC0392B);
                }
            } catch (Throwable ignored) {}
        }
    }

    private TextView groupLabel(String text) {
        TextView g = UiTheme.text(this, text, 13f, UiTheme.TEXT_SECONDARY, false);
        g.setPadding(UiTheme.dp(this, 2f), UiTheme.dp(this, 12f),
                UiTheme.dp(this, 2f), UiTheme.dp(this, 4f));
        return g;
    }

    /** 一行动作：名称 + 元信息 + 删除。 */
    private LinearLayout fileRow(final VmdManager.VmdFile f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiTheme.dp(this, 2f), UiTheme.dp(this, 8f),
                UiTheme.dp(this, 2f), UiTheme.dp(this, 8f));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        /* ★★ 列表只占一行（设计上要求 2026-10-06）★★
           原来显示"译文 + 换行 + 原文"两行，165 个动作时列表长得没法看。
           现在：有译文就【只显示译文】，没有才显示原文；原文长按可看。 */
        TextView n = new TextView(this);
        String label = labelOf(f);
        boolean translatingNow = "\u27f3 \u7ffb\u8bd1\u4e2d\u2026".equals(label);
        /* ★ 行内状态标记：一眼看出哪些已经翻好、哪些还在等 */
        String mark;
        if (f.preset) mark = "";
        else if (translatingNow) mark = "\u27f3 ";
        else if (!label.equals(f.name)) mark = "\u2713 ";
        else if (transDone >= 0) mark = "\u25cb ";
        else mark = "";
        n.setText(mark + (translatingNow ? (label + "  " + f.name) : label));
        n.setTextSize(14f);
        n.setTextColor(translatingNow ? 0xFF4C6EF5 : UiTheme.TEXT_PRIMARY);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(n);

        /* 长按显示原文（便于核对是哪个文件） */
        final String origName = f.name;
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("动作原文")
                    .setMessage(origName + "\n\n" + f.id)
                    .setPositiveButton("知道了", null)
                    .show();
            return true;
        });

        /* ★ GLB 自带动作没有"文件大小/骨骼关键帧"这些概念（我们只拿到名字）
           → 直接不显示这行，避免出现 "0.0s ｜ 骨骼关键帧 0 ｜ 0 B" 这种误导信息。 */
        if (glbMode()) {
            TextView tag = UiTheme.text(this, "模型自带动作", 11f,
                    UiTheme.TEXT_SECONDARY, false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = UiTheme.dp(this, 2f);
            col.addView(tag, tlp);
            /* ★★ 必须先把 col 挂进 row 再 return ★★
               ---- 事故（实测"动作库里的名字消失了"）----
               上一版在这里直接 return row，而 col 是在下面才 addView 的 ——
               于是名字整个没进布局树，列表里只剩一个空的 row。 */
            row.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }

        TextView meta = UiTheme.text(this,
                fmtDur(f.duration) + " ｜ 骨骼关键帧 " + f.bones
                + (f.morphs > 0 ? (" ｜ 表情关键帧 " + f.morphs) : "")
                + " ｜ " + fmtSize(f.size)
                + (f.morphOnly ? " ｜ ⚠ 仅表情（人物不会动）" : ""),
                11f, f.morphOnly ? 0xFFB26A00 : UiTheme.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = UiTheme.dp(this, 2f);
        col.addView(meta, mlp);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = UiTheme.text(this, "删除", 13f, 0xFF9B3B34, true);
        del.setPadding(UiTheme.dp(this, 10f), UiTheme.dp(this, 8f),
                UiTheme.dp(this, 4f), UiTheme.dp(this, 8f));
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("删除动作")
                .setMessage(labelOf(f) + "\n\n引用它的绑定会变成「文件已不存在」。")
                .setPositiveButton("删除", (d, w) -> {
                    vmd.delete(f.id);
                    /* 顺手把它从各插槽和随机池里摘掉，避免留下死引用 */
                    PetStore.VmdConfig c = store.vmdConfig();
                    /* ★ 两套插槽都要清（否则另一套里会留下"文件已不存在"的死引用） */
                    for (String s : PetStore.VMD_SLOTS) {
                        while (c.slot(s).remove(f.id)) { /* repeat */ }
                        while (c.slotGlb(s).remove(f.id)) { /* repeat */ }
                    }
                    while (c.randomPool.remove(f.id)) { /* repeat */ }
                    store.saveVmdConfig(c);
                    scanFiles();
                    push();
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show());
        row.addView(del);
        return row;
    }

    private void pickFiles() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(it, REQ_PICK);
        } catch (Throwable t) {
            toast("打不开文件选择器：" + t.getMessage());
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        try {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        } catch (Throwable ignored) {}
        if (uris.isEmpty()) return;

        toast("正在导入 " + uris.size() + " 个文件…");
        new Thread(() -> {
            VmdManager.ImportResult r;
            try {
                r = vmd.importUris(uris);
            } catch (Throwable t) {
                handler.post(() -> toast("导入失败：" + t));
                return;
            }
            final VmdManager.ImportResult rr = r;
            handler.post(() -> {
                toast(rr.summary());
                scanFiles();
            });
        }, "vmd-import").start();
    }

    private void push() {
        PetOverlayServiceBridge.pushVmdConfig();
    }

    /* ================= 小工具 ================= */

    private static String fmtDur(float sec) {
        return String.format(java.util.Locale.ROOT, "%.1fs", sec);
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
