package me.rerere.pet.ui;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;

import me.rerere.pet.core.CodexPetAtlas;
import me.rerere.pet.core.CodexPetImporter;
import me.rerere.pet.R;
import me.rerere.pet.core.PetContracts;
import me.rerere.pet.data.McpManager;
import me.rerere.pet.data.MmdModelManager;
import me.rerere.pet.data.PetAiClient;
import me.rerere.pet.data.PetStore;

/**
 * 桌宠悬浮窗服务。
 * 交互：拖拽移动 / 单击-单击-单击（三击）唤出输入框 / 头身脚分区触摸。
 */
public final class PetOverlayService extends Service {

    /** 点击三下唤出输入框。 */
    private static final int TRIPLE_TAP_WINDOW_MS = 1500;
    private static final long FEEDBACK_DURATION_MS = 900;

    private PetStore store;
    private WindowManager windowManager;
    private View rootView;
    private PetSpriteView spriteView;

    private CodexPetAtlas atlas;
    private int baseFrameWidthPx;

    private int tapCount = 0;
    private long lastTapAt = 0;
    private float lastTapX = 0f;
    private float lastTapY = 0f;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tripleTapRunnable = this::onTripleTap;
    private final Runnable revertActionRunnable = () -> {
        if (spriteView != null) spriteView.setAction(PetContracts.PetAction.IDLE, true);
    };

    /** 待机随机动作：无人触碰一段时间后自动播一个随机动作。 */
    private static final long IDLE_RANDOM_MIN_MS = 12_000L;
    private static final long IDLE_RANDOM_MAX_MS = 30_000L;
    private final java.util.Random random = new java.util.Random();

    /** 最近一次触摸时间，用于判断是否"无人触碰"。 */
    private long lastTouchAt = System.currentTimeMillis();

    private final Runnable idleRandomRunnable = new Runnable() {
        @Override public void run() {
            if (spriteView == null) return;
            PetStore.Appearance a = store.appearance();
            boolean untouched = System.currentTimeMillis() >= lastTouchAt;
            if (a.idleRandomEnabled && untouched) {
                playRandomIdleAction();
            }
            scheduleNextIdleRandom();
        }
    };

    private float downRawX, downRawY;
    private int startX, startY;
    /** 本次拖动的起点（按下瞬间的窗口位置），避免累加偏移。 */
    private int dragBaseX, dragBaseY;
    private boolean dragging = false;
    private int touchSlop;

    private PetDialogueController dialogueController;
    private McpManager mcpManager;
    private PetSpeechBubble speechBubble;
    /** MMD 渲染（renderMode=mmd 时启用）。 */
    private MmdPetView mmdView;
    private MmdModelManager mmdModels;
    private me.rerere.pet.data.VmdManager vmds;

    /** 下一次「待机随机表演」的时间戳（0 = 还没排）。 */
    private long vmdRandomNextAt = 0L;
    /** 最近一次任何 VMD 一次性动作的开始时间（避免随机表演和触摸动作撞车）。 */
    private long vmdLastOneShotAt = 0L;
    /** MMD 模式的分区辅助线层。 */
    private GuideLineOverlay mmdGuides;
    /** 当前互动请求，可被强制停止。 */
    private volatile PetAiClient.CancelToken interactToken;
    /** 强制停止：2.5 秒内连点五次。 */
    private static final int INTERACT_STOP_TAP_COUNT = 5;
    private static final long INTERACT_STOP_WINDOW_MS = 2_500L;
    private int interactStopTaps = 0;
    private long interactFirstTapAt = 0L;

    /** 当前运行的实例，供设置页联动辅助线。 */
    private static PetOverlayService current;

    public static PetOverlayService current() { return current; }

    public interface InteractionListener {
        void onTripleTap();
        void onTouchRegion(PetContracts.PetBodyRegion region);
    }

    private InteractionListener interactionListener;

    @Override public void onCreate() {
        super.onCreate();
        store = new PetStore(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        current = this;
        // 注册桥接，供 MCP 工具触发表情
        me.rerere.pet.data.PetOverlayServiceBridge.install((name, seconds) -> {
            if (mmdView == null) return;
            /* ★★ glTF 模型：「表情」= 自带动画 ★★
               ---- 起因（用户 2026-10-07 指出）----
               这个 GLB 里 14 个 morph target **全部是嘴型**（Mouth_200..Mouth_607），
               没有任何眼/眉/脸的表情。所以对 glTF 来说，
               "让桌宠做个表情"真正有意义的是**播一个自带动画**。
               → 这里按模型类型分派：glTF 走 playGlbClip（动画），MMD 走 playExpression（morph）。
               （表情清单也在 onGlbClips 里换成了动画名，所以 AI 收到的候选就是动画。） */
            if (isGlbModel()) mmdView.playGlbClip(name);
            else mmdView.playExpression(name, seconds);
        });
        vmds = new me.rerere.pet.data.VmdManager(this);
        /* 注册 VMD 桥：「VMD 动画」页改完绑定后，由这里把新配置推给页面。 */
        me.rerere.pet.data.PetOverlayServiceBridge.installVmd(
                new me.rerere.pet.data.PetOverlayServiceBridge.VmdImpl() {
            @Override public void pushVmdConfig() {
                PetOverlayService.this.pushVmdConfig();
                PetOverlayService.this.scheduleVmdRandom();
            }
            @Override public void playGlbClip(String name) {
                /* 试播 glTF 自带动画（表情映射页的"试播"按钮） */
                try {
                    if (mmdView != null) mmdView.playGlbClip(name);
                } catch (Throwable t) {
                    Log.w("PetOverlayService", "playGlbClip failed " + name, t);
                }
            }

            @Override public void playVmdSlot(String slot) {
                PetOverlayService.this.playVmdSlot(slot);
            }
        });
        /* 注册翻译状态桥：界面据此显示"翻译中"标记，并可请求立即翻译 */
        me.rerere.pet.data.PetOverlayServiceBridge.installTranslateStatus(
                new me.rerere.pet.data.PetOverlayServiceBridge.TranslateStatusImpl() {
            @Override public boolean isTranslating() {
                return PetOverlayService.this.isTranslating();
            }
            @Override public java.util.Set<String> translatingNames() {
                return PetOverlayService.this.translatingNames();
            }
            @Override public void requestTranslate(boolean manual) {
                PetOverlayService.this.translateNow(manual);
            }
        });

        /* 注册配件桥接：MCP 工具与配件页都通过它开关配件 */
        me.rerere.pet.data.PetOverlayServiceBridge.installAccessory(
                new me.rerere.pet.data.PetOverlayServiceBridge.AccessoryImpl() {
            @Override public void setAccessory(String name, boolean on) {
                if (mmdView != null) mmdView.setAccessory(name, on);
                /* 立即持久化（配件是长期状态，掉电/重启也该保留）。
                   ★ 不要用 `catch (Throwable ignored)` 静默吞掉 ——
                     一旦保存失败，用户就会遇到"关掉了又被自动打开"
                     （因为恢复时读到的还是旧列表）。这里把错误打出来。 */
                try {
                    PetStore.Appearance a = store.appearance();
                    java.util.List<String> cur =
                            new java.util.ArrayList<>(store.accessories(a.mmdModelUrl));
                    if (on) {
                        if (!cur.contains(name)) cur.add(name);
                    } else {
                        cur.remove(name);
                    }
                    store.saveAccessories(a.mmdModelUrl, cur);
                    java.util.List<String> after = store.accessories(a.mmdModelUrl);
                    Log.i("PetOverlayService", "accessory persist: " + name + " = " + on
                            + " | 保存后实际读到 " + after.size() + " 个: " + after);
                    Log.i("PetOverlayService", "  modelUrl=[" + a.mmdModelUrl + "]");
                } catch (Throwable t) {
                    Log.e("PetOverlayService", "accessory persist FAILED for " + name, t);
                }
            }
            @Override public String listAccessories() {
                /* JS 是异步的，这里没法同步返回；
                   真正的列表查询走 queryAccessories（异步回调）。
                   保留此方法仅为满足接口，返回空数组。 */
                return "[]";
            }
            @Override public void setAllAccessories(java.util.List<String> onList) {
                /* ★ 批量设置：JS 侧一次设好整份名单，本地只持久化【一次】。
                   避免"逐个 setChecked → 逐次读改写"导致的并发丢更新
                   （那正是"全部关闭关不掉"的原因）。 */
                try {
                    PetStore.Appearance a = store.appearance();
                    java.util.List<String> list = (onList == null)
                            ? new java.util.ArrayList<>()
                            : new java.util.ArrayList<>(onList);
                    if (mmdView != null) {
                        String json = new org.json.JSONArray(list).toString();
                        mmdView.setAccessories(json);
                    }
                    store.saveAccessories(a.mmdModelUrl, list);
                    Log.i("PetOverlayService", "setAllAccessories: " + list.size() + " 个 -> " + list);
                } catch (Throwable t) {
                    Log.e("PetOverlayService", "setAllAccessories failed", t);
                }
            }
        });
        mmdModels = new MmdModelManager(this);
        speechBubble = new PetSpeechBubble(this, windowManager);
        /* 气泡高度比例来自用户设置（主页滑块）。必须在创建后立刻应用一次，
           否则第一次气泡会按默认 32% 排，改过设置的用户会看到"闪一下"。 */
        try {
            speechBubble.setHeightPct(store.appearance().bubbleHeightPct / 100f);
            speechBubble.setMinPct(store.appearance().bubbleMinPct / 100f);
        } catch (Throwable ignored) {}
        speechBubble.setStopListener(this::forceStopInteraction);
        mcpManager = new McpManager(this, store);
        dialogueController = new PetDialogueController(this, store, this::onDialogueCompleted);
        dialogueController.setMcpManager(mcpManager);
        mcpManager.startPolling();
        loadPet();
        scheduleNextIdleRandom();
        restartRoam();          // 若上次开着漫游，服务起来就继续走
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (rootView == null) showOverlay();
            /* ★ 漫游必须在【悬浮窗建好之后】才能启动：
               onCreate 里调 restartRoam() 时 rootView 还是 null，
               它会直接 return，之后再没人调 —— 表现为"开了漫游但一直不走"。
               放在这里（无论本次是新建还是已有悬浮窗）都能生效。 */
            restartRoam();
        } catch (Throwable t) {
            // 悬浮窗失败不应拖垮整个进程；主界面仍可使用并提示原因。
            android.util.Log.e("PetOverlayService", "showOverlay failed", t);
            stopSelf();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public void setInteractionListener(InteractionListener l) { this.interactionListener = l; }

    // ---------------- 加载桌宠 ----------------

    private void loadPet() {
        File petsRoot = new File(getFilesDir(), "pets");
        String packageId = store.appearance().packageId;
        if (packageId.isEmpty() || !PetContracts.isValidPackageId(packageId)) return;
        File dir = new File(petsRoot, packageId);
        File sheet = findSheet(dir);
        if (sheet == null) return;
        try {
            /* ★★ 顺序极其重要（崩溃过）★★
               错误写法：
                   if (atlas != null) atlas.close();       ← 立刻 recycle 旧 bitmap
                   atlas = CodexPetAtlas.decode(...);      ← 这中间可能被 draw 一帧 → 崩
               正确写法：
                   1) 先把 View 上的引用摘掉（View 就不会再画它）
                   2) 解码新图集（失败则保留旧的，画面不黑）
                   3) 成功后才回收旧图集
               三者顺序颠倒任何一个都会留下"持有已回收 bitmap"的窗口。 */
            CodexPetAtlas old = atlas;
            if (spriteView != null) {
                spriteView.setAtlas(null);
                spriteView.invalidate();
            }
            PetContracts.CodexPetVersion version = readVersion(dir);
            CodexPetAtlas fresh = CodexPetAtlas.decode(sheet, version, false);
            atlas = fresh;
            if (old != null && old != fresh) old.close();
        } catch (Throwable t) {
            // 必须捕 Throwable：低版本设备可能抛 NoSuchMethodError 等 Error，
            // 只捕 Exception 会漏过并导致启动即崩。
            android.util.Log.e("PetOverlayService", "loadPet failed", t);
            atlas = null;
        }
    }

    /**
     * 读取整个文件。
     * 注意：不能用 FileInputStream.readAllBytes()，那是 Java 9 API，
     * 在 Android 上需要 API 33，低版本设备会抛 NoSuchMethodError 直接崩溃。
     */
    private static byte[] readFileBytes(File file) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private PetContracts.CodexPetVersion readVersion(File dir) {
        try {
            File f = new File(dir, "pet.json");
            if (f.isFile()) {
                byte[] raw = readFileBytes(f);
                org.json.JSONObject o = new org.json.JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
                return PetContracts.CodexPetVersion.of(o.optInt("spriteVersionNumber", o.optInt("version", 1)));
            }
        } catch (Throwable ignored) {}
        return PetContracts.CodexPetVersion.V1;
    }

    private File findSheet(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".webp") || n.endsWith(".png")) return f;
        }
        return null;
    }

    // ---------------- 悬浮窗 ----------------

    private void showOverlay() {
        PetStore.Appearance appearance = store.appearance();
        if ("mmd".equals(appearance.renderMode)) {
            showMmdOverlay(appearance);
            return;
        }
        showSpriteOverlay(appearance);
    }

    /**
     * MMD 模式：透明 WebView 渲染 3D 模型。
     * 外层用容器拦截全部触摸——WebView 会吞掉拖动事件，
     * 必须由容器统一处理拖拽与三击。
     */
    private void showMmdOverlay(PetStore.Appearance appearance) {
        mmdCropCount = 0;
        resetAspectAdapt();       // 重建视图 = 新的一次适配机会（同上）
        mmdBaseW = mmdBaseH = 0;
        final int screenWFull = getResources().getDisplayMetrics().widthPixels;
        final int screenHFull = getResources().getDisplayMetrics().heightPixels;
        // 模型显示区的尺寸（与窗口是否全屏无关，全屏时用作内嵌视口）
        final int viewW = mmdWidthPx(appearance);
        final int viewH = (int) (viewW * MMD_ASPECT);

        final android.widget.FrameLayout container = new android.widget.FrameLayout(this) {
            /**
             * 窗口尺寸 == 模型尺寸，所以窗口内全部交由 PetTouchListener 处理。
             * 窗口之外的触摸本来就不属于本窗口，天然穿透给其他 App。
             */
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
                return true;
            }
        };
        container.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        mmdView = new MmdPetView(this, mmdModels);
        /* ★ GPU 加速必须在 create() 之前设置：它走 URL 参数，
           而 WebGL 的 antialias / powerPreference 只能在创建上下文时决定。 */
        mmdView.setGpuAccel(appearance.gpuAccel);
        mmdView.setListener(new MmdPetView.Listener() {
            /**
             * ★★ 自动适配窗口形状（仅在【锁定取景】时生效 —— 设计上要求）。
             *
             * ---- 为什么需要 ----
             * 窗口固定 1:1.35，而模型内容约 1:2.4 → 填入限制维度后另一维必然留白。
             * 把窗口宽高比调成与模型一致，横向留白才会消失，模型真正填满矩形。
             *
             * ---- 为什么必须限流 + 限次数 ----
             * 改窗口会触发 WebView resize → 重新取景 → 内容宽高比又变
             * → 再触发本回调……容易形成循环（之前踩过：窗口被连续收窄直到模型消失）。
             * 所以：
             *   ① 只在锁定取景时执行
             *   ② 两次调整至少间隔 1.5 秒
             *   ③ 每次模型加载最多调整 4 次
             *   ④ 宽高比差异小于 8% 就不动（避免无意义的抖动）
             */
            @Override public void onContentAspect(final float aspect) {
                if (rootView != null) maybeAdaptWindowAspect(aspect);
            }

            @Override public void onViewLockChanged(boolean locked) {
                viewLocked = locked;
                if (locked) resetAspectAdapt();   // 刚锁上 → 允许重新适配几次
                Log.i("PetOverlayService", "viewLock = " + locked);
            }

            @Override public void onReady() {
                Log.i("PetOverlayService", "mmd ready");
                /* ★ 页面就绪 = 可以把 VMD 配置交给它了（页面收到后才加载动作文件）。
                   注意页面还可能通过 MmdBridge.pageReady() 也推一次；
                   JS 侧有配置指纹去重，重复推是安全的。 */
                pushVmdConfig();
                scheduleVmdRandom();
                scheduleGreet("ready");
                /* ★ 动态模糊也必须在这里补一次 ★
                   ---- 为什么（实测发现）----
                   applyAppearance() 开头有 `if (rootView == null) return;`，
                   而它在启动流程里可能跑在 rootView 就绪【之前】——
                   于是那次 applyMotionBlur() 根本没执行，
                   用户"开了动态模糊、重启桌宠却不生效"（要再进一次设置页才生效）。
                   → 页面 ready 是"JS 一定能收"的时刻，在这里无条件补发一次。 */
                applyMotionBlur();
                // 查询眼球骨骼：没有则眼动功能不可用（设置页会禁用开关）
                mmdView.queryEyeBones(has -> {
                    me.rerere.pet.data.PetOverlayServiceBridge.setEyeBonesAvailable(has);
                    Log.i("PetOverlayService", "eye bones available = " + has);
                });

                // 拉取表情清单，供 AI 的 MCP 工具描述使用
                mmdView.queryExpressions(json -> {
                    try {
                        /**
                         * evaluateJavascript 会把字符串结果再包一层 JSON 引号
                         * （返回形如 "\"[\\\"a\\\",\\\"b\\\"]\""），
                         * 直接用 JSONArray 解析会失败。先用 JSONTokener 解出真实字符串。
                         */
                        String decoded = json;
                        try {
                            Object v = new org.json.JSONTokener(json).nextValue();
                            if (v instanceof String) decoded = (String) v;
                        } catch (Throwable ignored) {}
                        org.json.JSONArray arr = new org.json.JSONArray(decoded);
                        java.util.List<String> names = new java.util.ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            String n = arr.optString(i, "").trim();
                            if (!n.isEmpty()) names.add(n);
                        }
                        /* ★★ 不要用空列表覆盖已有的 ★★
                           ---- 实测时序（GLB）----
                             19:05:55.723  expressions(主动上报 14)   ← 页面报了 14 个
                             19:05:55.790  expressions(0): []        ← 原生问的时候
                           GLB 的表情要等模型加载完才有，而原生 queryExpressions
                           的时机（页面 ready）更早 —— 所以这个"0"是【过期的空结果】，
                           它把刚上报的 14 个覆盖掉了，表情映射页又变空。
                           → 只有确实拿到非空时才覆盖；空的时候保留已有的。 */
                        /* ★ glTF：这里的 names 是页面报来的 14 个【嘴型】，
                           不该覆盖"表情清单 = 自带动画"的约定（见 onGlbClips）。
                           直接跳过，保持动画清单。 */
                        if (isGlbModel()) {
                            Log.i("PetOverlayService",
                                    "glTF：queryExpressions 拿到 " + names.size()
                                    + " 个嘴型 morph，忽略（表情清单保持为动画）");
                        } else if (names.isEmpty()
                                && !me.rerere.pet.data.PetOverlayServiceBridge
                                        .expressionNames().isEmpty()) {
                            Log.i("PetOverlayService",
                                    "expressions(0) 是过期结果，保留已有的 "
                                    + me.rerere.pet.data.PetOverlayServiceBridge
                                        .expressionNames().size() + " 个");
                        } else {
                            me.rerere.pet.data.PetOverlayServiceBridge.setExpressionNames(names);
                            Log.i("PetOverlayService", "expressions(" + names.size() + "): " + names);
                        }

                        /* ★ 查询配件列表，并把它交给桥（供 MCP/配件页使用）。
                           没有配件时列表为空 → 配件功能会自动禁用。 */
                        if (mmdView != null) {
                            mmdView.queryAccessories(accJson -> {
                                try {
                                    String dec = accJson;
                                    try {
                                        Object v = new org.json.JSONTokener(accJson).nextValue();
                                        if (v instanceof String) dec = (String) v;
                                    } catch (Throwable ignored) {}
                                    org.json.JSONArray accArr = new org.json.JSONArray(dec);
                                    java.util.List<String> accs = new java.util.ArrayList<>();
                                    java.util.Map<String, String> accKinds = new java.util.HashMap<>();
                                    for (int i = 0; i < accArr.length(); i++) {
                                        org.json.JSONObject o = accArr.optJSONObject(i);
                                        String n = (o == null) ? "" : o.optString("name", "");
                                        if (!n.isEmpty()) {
                                            accs.add(n);
                                            /* ★ 种类标签：显示部件 / 姿势动作 / 形状变化 */
                                            String k = o.optString("kind", "");
                                            if (!k.isEmpty()) accKinds.put(n, k);
                                        }
                                    }
                                    me.rerere.pet.data.PetOverlayServiceBridge.setAccessoryNames(accs);
                                    me.rerere.pet.data.PetOverlayServiceBridge.setAccessoryKinds(accKinds);
                                    Log.i("PetOverlayService", "accessories(" + accs.size() + "): " + accs);

                                    /* ★ 名称翻译：补齐"还没有译文"的条目（开关开启时才联网）。
                                       只影响显示，不动任何实际数据。 */
                                    scheduleTranslate();

                                    /* ★ 恢复该模型上次启用的配件（持久化）。
                                       只有确实存在于本模型的才恢复，避免残留无效状态。 */
                                    PetStore.Appearance ap = store.appearance();
                                    java.util.List<String> saved = store.accessories(ap.mmdModelUrl);
                                    java.util.List<String> valid = new java.util.ArrayList<>();
                                    for (String s : saved) if (accs.contains(s)) valid.add(s);
                                    if (mmdView != null && !valid.isEmpty()) {
                                        String accJsonStr = new org.json.JSONArray(valid).toString();
                                        mmdView.setAccessories(accJsonStr);
                                        Log.i("PetOverlayService", "restored accessories: " + valid);
                                    }
                                } catch (Throwable t) {
                                    Log.w("PetOverlayService", "parse accessories failed", t);
                                }
                            });
                        }
                    } catch (Throwable t) {
                        Log.w("PetOverlayService", "parse expressions failed", t);
                    }
                });
                // 模型就绪后掩码才是当前模型的，重新收紧一次
                mmdCropCount = 0;
                handler.postDelayed(PetOverlayService.this::tryCropMmdWindow, 1500);
            }
            @Override public void onLog(String message) {}
            @Override public void onError(String message) {
                Log.e("PetOverlayService", "mmd error: " + message);
            }
            @Override public void onMaskReady() {
                // 掩码首次就绪 -> 稍后收紧窗口
                handler.postDelayed(PetOverlayService.this::tryCropMmdWindow, 400);
            }

            /**
             * 走动期间临时放宽帧率上限。
             * ★ 走路动画是 30fps 录的；帧率上限设成 20 时渲染会跳帧，
             *   表现就是"走路一卡一卡的"（2026-10-06）。
             *   走动只有几秒，代价可接受，走完立刻恢复。
             */
            @Override public void onWalkFpsRequest(boolean walking) {
                PetOverlayService.this.applyWalkFps(walking);
            }

            /**
             * glTF 自带动作清单。
             * ★ 只在 GLB 模型时非空；MMD 模型会收到空数组（页面会清空）。
             */
            /**
             * 表情清单（页面主动上报）。
             * ★ glTF 的 morph target 要等模型加载完才知道，而原生
             *   queryExpressions 的时机（页面 ready）更早 → 实测拿到空列表。
             *   所以这里以页面报的为准。
             */
            @Override public void onExpressions(String json) {
                try {
                    String dec = json;
                    try {
                        Object v = new org.json.JSONTokener(json).nextValue();
                        if (v instanceof String) dec = (String) v;
                    } catch (Throwable ignored) {}
                    org.json.JSONArray arr = new org.json.JSONArray(
                            dec == null || dec.isEmpty() ? "[]" : dec);
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String nm = arr.optString(i, "").trim();
                        if (!nm.isEmpty()) names.add(nm);
                    }
                    /* ★ glTF：忽略页面报来的 mouth morph 清单 —— 那是 14 个【嘴型】，
                       交给动画自动驱动即可；此刻的"表情清单"应当保持为【动画名】。
                       否则这份嘴型列表会把刚设好的动画名覆盖掉。 */
                    if (isGlbModel()) {
                        Log.i("PetOverlayService",
                                "glTF：忽略 " + names.size() + " 个嘴型 morph（表情清单保持为动画）");
                        return;
                    }
                    me.rerere.pet.data.PetOverlayServiceBridge.setExpressionNames(names);
                    Log.i("PetOverlayService", "expressions(主动上报 " + names.size() + "): " + names);
                } catch (Throwable t) {
                    Log.w("PetOverlayService", "parse expressions failed: " + json, t);
                }
            }

            @Override public void onGlbClips(String json) {
                try {
                    String dec = json;
                    try {
                        Object v = new org.json.JSONTokener(json).nextValue();
                        if (v instanceof String) dec = (String) v;
                    } catch (Throwable ignored) {}
                    org.json.JSONArray arr = new org.json.JSONArray(
                            dec == null || dec.isEmpty() ? "[]" : dec);
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String nm = arr.optString(i, "").trim();
                        if (!nm.isEmpty()) names.add(nm);
                    }
                    me.rerere.pet.data.PetOverlayServiceBridge.setGlbClipNames(names);
                    Log.i("PetOverlayService", "GLB 自带动作(" + names.size() + "): " + names);
                    /* ★★ glTF：「表情清单」= 自带动画名 ★★
                       这个模型的 morph 全是嘴型（见 install 处的说明），
                       对用户和 AI 来说"做表情"= 播一个动画才有意义。
                       → 喂给 expressionNames，这样【表情映射页】和【MCP 表情工具】
                         会一起指向动画（嘴型本来就由动画自动驱动）。 */
                    if (isGlbModel()) {
                        me.rerere.pet.data.PetOverlayServiceBridge.setExpressionNames(names);
                        Log.i("PetOverlayService",
                                "glTF：表情清单改用自带动画 " + names.size() + " 个");
                    }
                    /* ★★ 自动预填「拖动时动画」（2026-10-07 明确要求）★★
                       ---- 为什么 ----
                       用户指名要用 glTF 自带的 Formation_Pickup 做"拖放模型时的动画"。
                       但若只加一个空插槽，用户拖了半天毫无反应，会以为功能没做
                       （这正是本项目反复踩过的"静默失效"）。
                       → 检测到该动作存在、且 drag 插槽**为空**时，自动绑上。
                       ★ 只在【为空】时才填：用户清空/改成别的动作后不会被再次覆盖。 */
                    try {
                        /* ★ 注意 store.vmdConfig() 每次返回的是【反序列化出来的新副本】，
                           必须改完再 saveVmdConfig 写回，否则改动会被静默丢弃
                           （本项目踩过的"改副本存原件"坑）。 */
                        PetStore.VmdConfig vc = store.vmdConfig();
                        /* ★ 走 slotFor(..., true)：这是【glTF 那份】插槽 */
                        if (names.contains("Formation_Pickup")
                                && vc.slotFor("drag", true).isEmpty()) {
                            vc.slotFor("drag", true).add("glb:Formation_Pickup");
                            store.saveVmdConfig(vc);
                            Log.i("PetOverlayService",
                                    "拖动时动画：自动预填 glb:Formation_Pickup");
                            pushVmdConfig();
                        }
                    } catch (Throwable t) {
                        Log.w("PetOverlayService", "drag autofill failed", t);
                    }
                } catch (Throwable t) {
                    Log.w("PetOverlayService", "parse glbClips failed: " + json, t);
                }
            }

            @Override public void onModelChanged() {
                /* ★ 换模型 → 类型（MMD/glTF）可能变了，动态模糊要换成对应那套 */
                handler.postDelayed(PetOverlayService.this::applyMotionBlur, 900);
                // 切换/加载完新模型 -> 重置裁剪计数并重新收紧
                mmdCropCount = 0;
                /* ★ 换模型必须重置"窗口形状适配"的状态 ★
                   否则 mmdModelAspect 会沿用【上一个模型】的宽高比：
                   由于 applyAppearance 现在按它定宽，新模型的窗口形状会一直是旧的；
                   而 aspectAdjustCount 还会保留（有 3 次上限）→ 再也适配不过来。 */
                resetAspectAdapt();
                handler.postDelayed(PetOverlayService.this::tryCropMmdWindow, 1200);
                /* ★ 换模型后动作剪辑要重算（VMD 的轨道是按模型骨骼拟合的），
                   所以让页面重新按新模型构建一次，再播一次见面动画。 */
                handler.postDelayed(() -> pushVmdConfig(), 1800L);
                scheduleGreet("model-changed");
            }
        });
        android.webkit.WebView wv = mmdView.create();
        container.addView(wv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 辅助线层：叠在 WebView 之上，不接收触摸（穿透到容器）
        mmdGuides = new GuideLineOverlay(this);
        mmdGuides.setClickable(false);
        mmdGuides.setFocusable(false);
        container.addView(mmdGuides, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootView = container;
        rootView.setOnTouchListener(new PetTouchListener());

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        /**
         * 全屏眼动追踪：窗口铺满整个屏幕，这样手指在任意位置滑动
         * 都会落在窗口内，WebView 能收到完整轨迹。
         * 模型以外的区域由命中测试返回 false 不消费事件，
         * 因此不会挡住其他 App 的点击（但滑动会被捕获，这是预期代价）。
         */
        // 窗口始终等于模型大小：这样窗口外的触摸天然穿透给其他 App
        // （Android 无法做到"同一窗口内部分区域穿透"）
        int winW = viewW;
        int winH = viewH;
        // 记录未裁剪的原始尺寸作为裁剪基准
        mmdBaseW = viewW;
        mmdBaseH = viewH;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                winW, winH,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        startX = (int) (appearance.normalizedX * Math.max(1, screenW - viewW));
        startY = (int) (appearance.normalizedY * Math.max(1, screenH - viewH));
        params.x = startX;
        params.y = startY;
        mmdBaseX = startX;
        mmdBaseY = startY;

        try {
            windowManager.addView(rootView, params);
            /* ★ 启动即校正一次（存档来自别的分辨率/别的设备时会越界） */
            rootView.post(new Runnable() { @Override public void run() {
                ensureVisible("startup");
                handler.post(keepVisibleRunnable);
            } } );
            // 页面就绪后加载模型
            // 应用抗锯齿设置（在页面就绪后下发，MmdPetView 内部会排队）
            mmdView.setAntiAliasLevel(appearance.mmdAntiAliasLevel);
            // 性能设置：渲染倍率 / 动态分辨率 / 帧率上限
            mmdView.setPerf(appearance.renderScale, appearance.perfAuto, appearance.fpsCap);
            mmdView.setPhysics(appearance.physicsEnabled);
            /* ★ 告诉视图"现在有没有模型"。
               没有模型时 hitTest 一律返回 false，否则这块 40% 屏高的透明窗
               会把桌面点击全吃掉（公开仓库版首次启动就是这个状态）。 */
            boolean hasModel = appearance.mmdModelUrl != null
                    && !appearance.mmdModelUrl.isEmpty();
            mmdView.setModelMissing(!hasModel);
            if (hasModel) {
                mmdView.loadModel(appearance.mmdModelUrl);
            } else {
                Log.i("PetOverlayService",
                        "MMD 模式但没有模型 —— 只显示提示，不消费触摸");
            }
        } catch (Throwable t) {
            Log.e("PetOverlayService", "showMmdOverlay failed", t);
            stopSelf();
        }
    }

    /** MMD 视窗宽高比（模型通常竖长）。 */
    private static final float MMD_ASPECT = 1.35f;
    /** mmdScale = 1.0 时占屏幕高度的比例。 */
    private static final float MMD_HEIGHT_RATIO = 0.40f;

    /**
     * MMD 视窗宽度。
     * 用屏幕【高度】比例算，不直接复用精灵图的 scale（两者量级差很多）；
     * mmdScale=1.0 时占屏幕高度 40%。
     */
    private int mmdWidthPx(PetStore.Appearance appearance) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float sc = appearance.mmdScale <= 0f ? 1.0f : appearance.mmdScale;
        int targetH = (int) (screenH * MMD_HEIGHT_RATIO * sc);
        int w = (int) (targetH / MMD_ASPECT);
        /**
         * ★ 下界不能用固定 dp 值。
         * 之前写 Math.max(dp(120), ...)，在 density=2.75 的设备上 120dp=330px，
         * 而 small scale 算出来才 100 多 px，全被抬到 330 → 表现为
         * "20% 以下调节失效，4% 和 20% 一样大"（实测低于 62% 就开始失效）。
         * 改成按屏幕宽度的一个很小比例（2%），保证小尺寸也能真实生效。
         */
        int minW = Math.max(1, (int) (screenW * 0.02f));
        return Math.max(minW, Math.min(w, screenW));
    }

    private void showSpriteOverlay(PetStore.Appearance appearance) {
        float density = getResources().getDisplayMetrics().density;
        baseFrameWidthPx = (int) (PetContracts.CODEX_FRAME_WIDTH * density * appearance.scale);

        rootView = LayoutInflater.from(this).inflate(R.layout.overlay_pet, null);
        if (rootView == null) { stopSelf(); return; }
        spriteView = rootView.findViewById(R.id.pet_sprite);
        if (spriteView == null) { stopSelf(); return; }
        spriteView.setAtlas(atlas);
        spriteView.setScale(appearance.scale);
        spriteView.setFps(appearance.fps);
        spriteView.setOnTouchListener(new PetTouchListener());

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        int viewW = petWidthPx(appearance);
        int viewH = (int) ((float) viewW * PetContracts.CODEX_FRAME_HEIGHT / PetContracts.CODEX_FRAME_WIDTH);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                viewW, viewH,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = petWidthPx(appearance);
        startX = (int) (appearance.normalizedX * (screenW - w));
        startY = (int) (appearance.normalizedY * (screenH - w));
        params.x = startX;
        params.y = startY;

        try {
            windowManager.addView(rootView, params);
            spriteView.resume();
        } catch (Exception e) {
            stopSelf();
        }
    }

    /** 桌宠显示宽度：图集单帧宽度 × 缩放（像素），并按屏幕限制兜底。 */
    private int petWidthPx(PetStore.Appearance appearance) {
        float density = getResources().getDisplayMetrics().density;
        int px = (int) (PetContracts.CODEX_FRAME_WIDTH * density * appearance.scale);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(48), Math.min(px, screenW / 2));
    }

    private void scheduleNextIdleRandom() {
        handler.removeCallbacks(idleRandomRunnable);
        long delay = IDLE_RANDOM_MIN_MS
                + (long) (random.nextFloat() * (IDLE_RANDOM_MAX_MS - IDLE_RANDOM_MIN_MS));
        handler.postDelayed(idleRandomRunnable, delay);
    }

    // ================= VMD 动画 =================

    /** 把 VMD 配置推给页面（页面收到后才加载动作文件）。 */
    public void pushVmdConfig() {
        try {
            if (mmdView == null) return;
            String json = store.vmdConfigJson();
            mmdView.setVmdConfig(json);
            Log.i("PetOverlayService", "vmd config -> " + json);
        } catch (Throwable t) {
            Log.e("PetOverlayService", "pushVmdConfig failed", t);
        }
    }

    /** 播某个插槽（idle/walk/touchHead/touchBody/touchFeet/greet）。 */
    public void playVmdSlot(String slot) {
        try {
            if (!"idle".equals(slot) && !"walk".equals(slot)) {
                vmdLastOneShotAt = System.currentTimeMillis();
            }
            if (mmdView != null) mmdView.playVmdSlot(slot);
        } catch (Throwable t) {
            Log.w("PetOverlayService", "playVmdSlot failed " + slot, t);
        }
    }

    /** 给页面发一段 JS（设置页需要直接控制页面时用）。 */
    public void evalJs(String js) {
        try {
            if (mmdView != null) mmdView.eval(js);
        } catch (Throwable t) {
            Log.w("PetOverlayService", "evalJs failed", t);
        }
    }

    /**
     * 当前加载的是不是 glTF 模型（.glb / .gltf）。
     * 用来决定读哪一套动作插槽 —— MMD 与 glTF 的动作是分开存的
     * （见 PetStore.VmdConfig.slotsGlb 的说明）。
     */
    private boolean isGlbModel() {
        try {
            String m = store.appearance().mmdModelUrl;
            if (m == null || m.isEmpty()) return false;
            String s = m.toLowerCase(java.util.Locale.ROOT);
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            int h = s.indexOf('#');
            if (h >= 0) s = s.substring(0, h);
            return s.endsWith(".glb") || s.endsWith(".gltf");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上次播见面动画的时间 + 当时的模型（用于去重）。 */
    private long greetLastAt = 0L;
    private String greetLastModel = null;

    /** 走动时的临时帧率上限覆盖（0 = 没覆盖）。 */
    private int walkFpsOverride = 0;

    /**
     * 走动期间临时放宽帧率上限。
     *
     * ★ 为什么要这么做（实测"走路一卡一卡的"）：
     *   走路动作是 30fps 录制的（VMD 40 帧 = 1.33 秒一圈）。
     *   如果用户的「帧率上限」设成 20，渲染循环会跳帧 ——
     *   动画按 dt 走但渲染不连续，看起来就是一顿一顿。
     *   走动只有几秒，短暂放宽到 30 的代价可以接受，走完立刻恢复。
     */
    /**
     * ★ 只把帧率上限下发给页面（滑块拖动时实时调用）。
     *
     * ---- 为什么不能直接用 applyAppearance() ----
     * 它还会顺带 setAntiAliasLevel / setPhysics / updateBubblePosition /
     * **restartRoam(true)**（重排漫游），而且会检查窗口尺寸。
     * 滑块一次拖动会触发几十次 → 这些副作用跟着跑几十次，
     * 轻则浪费，重则打断漫游节奏。
     * 这里只 eval 一条 setPerf，是"实时预览帧率"最干净的做法。
     */
    /**
     * 只下发物理开关（不碰窗口尺寸/取景/漫游）。
     * 同理 applyFpsCapNow：物理开关只有一行 JS 调用，没必要跑整套 applyAppearance。
     */
    public void applyPhysicsNow(boolean on) {
        try {
            if (mmdView == null) return;
            mmdView.setPhysics(on);
        } catch (Throwable t) {
            Log.w("PetOverlayService", "applyPhysicsNow failed", t);
        }
    }

    public void applyFpsCapNow() {
        try {
            if (mmdView == null) return;
            PetStore.Appearance a = store.appearance();
            mmdView.setPerf(a.renderScale, a.perfAuto, a.fpsCap);
            walkFpsOverride = -1;      // 用户手动改了，作废走动期间的临时值
        } catch (Throwable t) {
            Log.w("PetOverlayService", "applyFpsCapNow failed", t);
        }
    }

    /**
     * ★ 下发动态模糊设置（MMD / glTF 各一套）。
     *
     * 只 eval 一条 setMotionBlur —— 不碰窗口/取景/漫游，所以滑块拖动时可以实时调用
     * （同 applyFpsCapNow 的思路）。
     */
    public void applyMotionBlur() {
        try {
            if (mmdView == null) return;
            PetStore.Appearance a = store.appearance();
            String json = "{\"mmd\":{\"on\":" + a.mbMmdOn
                    + ",\"len\":" + a.mbMmdLen + ",\"qual\":" + a.mbMmdQual + "},"
                    + "\"glb\":{\"on\":" + a.mbGlbOn
                    + ",\"len\":" + a.mbGlbLen + ",\"qual\":" + a.mbGlbQual + "}}";
            mmdView.setMotionBlur(json);
        } catch (Throwable t) {
            Log.w("PetOverlayService", "applyMotionBlur failed", t);
        }
    }

    private void applyWalkFps(boolean walking) {
        try {
            if (mmdView == null) return;
            PetStore.Appearance a = store.appearance();
            int want;
            if (walking) {
                /* ★★ 走动时把上限抬到「至少 30」★★
                   ---- 为什么改成 max() 而不是写死 30 ----
                   帧率上限现在是 20~90 的滑块（原来只有 不限/30/20 三档），
                   写死 30 会在用户本来就设了 60/90 时【反而降帧】
                   （走路动作 30fps，60fps 渲染同样顺，降到 30 纯属倒退）。
                   现在：用户设 20 → 抬到 30（够顺）；设 60 → 保持 60。
                   `fpsCap == 0` 表示"不限"，继续保持不限。 */
                want = (a.fpsCap == 0) ? 0 : Math.max(a.fpsCap, 30);
            } else {
                want = a.fpsCap;
            }
            if (want == walkFpsOverride) return;
            walkFpsOverride = want;
            mmdView.setPerf(a.renderScale, a.perfAuto, want);
            Log.i("PetOverlayService", "walkFps: fpsCap -> " + want
                    + (walking ? "（走动中临时放宽）" : "（恢复用户设定）"));
        } catch (Throwable t) {
            Log.w("PetOverlayService", "applyWalkFps failed", t);
        }
    }

    /**
     * 播一次见面动画。
     *
     * ★★ 为什么要去重（2026-10-06："见面动画会重复做两次"）★★
     *   onReady()（页面就绪）和 onModelChanged()（模型换好）都会触发，
     *   而这两个事件在启动时是【连着来的】→ 排了两次 setTimeout → 播两遍。
     *   现在：同一模型 15 秒内只播一次。
     *
     * @param why 触发来源（只用于日志）
     */
    private void scheduleGreet(String why) {
        PetStore.VmdConfig vc = store.vmdConfig();
        /* ★ 按当前模型类型取插槽（glTF 与 MMD 各存一份，见 VmdConfig.slotsGlb） */
        if (!vc.enabled || vc.slotFor("greet", isGlbModel()).isEmpty()) return;
        String model = store.appearance().mmdModelUrl;
        long now = System.currentTimeMillis();
        if (model != null && model.equals(greetLastModel) && now - greetLastAt < 15_000L) {
            Log.i("PetOverlayService", "见面动画已播过（" + why + "），跳过");
            return;
        }
        greetLastModel = model;
        greetLastAt = now;
        /* 稍微等一下再播：等取景/掩码稳定，否则动作会被随后的取景重置盖掉。
           换模型时等久一点（页面还在重建动作剪辑）。 */
        long delay = "model-changed".equals(why) ? 2600L : 2500L;
        handler.postDelayed(() -> playVmdSlot("greet"), delay);
        Log.i("PetOverlayService", "见面动画已排程（" + why + "，+" + delay + "ms）");
    }

    /**
     * 重排「待机随机表演」的下一次时间。
     * ★ 交给原生调度（而不是页面自己计时）的原因：
     *   原生才知道「现在该不该播」—— 拖拽中 / 对话打开 / 正在互动 / 正在漫游
     *   都要跳过去。页面拿不到这些状态。
     */
    public void scheduleVmdRandom() {
        handler.removeCallbacks(vmdRandomRunnable);
        PetStore.VmdConfig c = store.vmdConfig();
        /* ★ 漫游开着时不要插播随机表演：
           走路动作会占着同一个混合器，而且"边走边突然鞠躬"很怪。 */
        PetStore.Appearance ap = store.appearance();
        if (!c.enabled || !c.randomIdle || c.randomPool.isEmpty() || ap.roamEnabled) {
            vmdRandomNextAt = 0L;
            return;
        }
        int lo = Math.max(5, c.randomMinSec);
        int hi = Math.max(lo + 5, c.randomMaxSec);
        long delay = (lo + (long) (random.nextFloat() * (hi - lo))) * 1000L;
        vmdRandomNextAt = System.currentTimeMillis() + delay;
        handler.postDelayed(vmdRandomRunnable, delay);
        Log.i("PetOverlayService", "vmd random idle scheduled in " + (delay / 1000) + "s");
    }

    /** 现在适合播随机表演吗？（拖拽/对话/互动/漫游中一律不播） */
    private boolean vmdRandomAllowedNow() {
        if (rootView == null) return false;
        if (dragging) return false;
        if (dialogueController != null && dialogueController.isShowing()) return false;
        if (interactToken != null) return false;
        /* ★ 漫游开着时不插播随机表演（设计上要求）：
           走路动作会占着混合器，而且"边走边突然鞠躬"观感很怪。 */
        if (store.appearance().roamEnabled) return false;
        if (roamState == ROAM_WALK) return false;
        // 刚播过触摸动作就别马上又插一个，避免动作打架
        if (System.currentTimeMillis() - vmdLastOneShotAt < 1500L) return false;
        return true;
    }

    private final Runnable vmdRandomRunnable = new Runnable() {
        @Override public void run() {
            try {
                PetStore.VmdConfig c = store.vmdConfig();
                PetStore.Appearance ap = store.appearance();
                if (!c.enabled || !c.randomIdle || c.randomPool.isEmpty()) return;
                if (ap.roamEnabled) return;      // 漫游中不插播（见 scheduleVmdRandom 说明）
                if (vmdRandomAllowedNow() && mmdView != null) {
                    mmdView.playVmdRandom();
                    Log.i("PetOverlayService", "vmd random idle fired");
                }
            } catch (Throwable t) {
                Log.w("PetOverlayService", "vmd random failed", t);
            } finally {
                scheduleVmdRandom();
            }
        }
    };

    /** 漫游：通知页面切走路动作 + 侧身朝向。
     *  @param dir +1 = 往右走，-1 = 往左走，0 = 不转身
     *             ★ 是【90° 侧身】不是 180° 掉头 —— 见 JS 侧 __setVmdWalking 的说明 */
    private void pushVmdWalkState(boolean walking, int dir) {
        try {
            if (mmdView != null) mmdView.setVmdWalking(walking, dir);
        } catch (Throwable ignored) {}
    }

    /**
     * 随机播放一个非移动类动作，播完回到待机。
     */
    private void playRandomIdleAction() {
        if (spriteView == null) return;
        /* 漫游期间不要插播其他动作：会和左右跑动画抢帧 */
        if (roamState == ROAM_WALK) return;
        PetContracts.PetAction[] pool = {
                PetContracts.PetAction.WAVE,
                PetContracts.PetAction.JUMP,
                PetContracts.PetAction.REVIEW,
                PetContracts.PetAction.WAIT,
                PetContracts.PetAction.WORK,
        };
        PetContracts.PetAction act = pool[random.nextInt(pool.length)];
        spriteView.setAction(act, false);
        handler.removeCallbacks(revertActionRunnable);
        handler.postDelayed(revertActionRunnable, FEEDBACK_DURATION_MS + 800L);
    }

    // ================= 自动漫游 =================
    /**
     * 桌宠自己在屏幕上走动。
     *
     * 设计：
     *   · 状态机 停住(ROAM_IDLE) → 走(ROAM_WALK) → 停住 …… 循环
     *   · 走的时候每 ROAM_STEP_MS 移动 roamSpeed 像素（改窗口 x）
     *   · 碰到屏幕边缘就掉头（不允许走出屏幕）
     *   · 精灵图模式：走动时播 MOVE_LEFT / MOVE_RIGHT 跑步动画
     *     MMD 模式：模型本身不会走，只移动窗口（视觉上就是整体位移）
     *   · 只在"待机"时走：拖拽中、对话打开、互动生成中一律暂停
     *
     * ★ 四个坑（都踩过）：
     *   1) 位移必须用【当前位置 + 增量】，不能用 startX + 累计位移 ——
     *      用户拖动过之后 startX 会变，累加会跳。
     *   2) 不能用固定 dp 做边界，否则小模型在屏幕边缘会被反弹得很怪。
     *   3) ★ 朝向是【±90° 侧身】而不是 180° 掉头。
     *      历史弯路：先做「往左转 180°」（实测：往左走会背对屏幕），
     *      又改成「往右转 180°」（实测：往右走会背对屏幕）——
     *      两次都不对，因为用户要的本来就是侧身朝行进方向。
     *   4) 走完一段 / 停下时【必须复位朝向】，否则模型会一直侧着身像坏了。
     */
    private static final int ROAM_IDLE = 0;
    private static final int ROAM_WALK = 1;
    private static final long ROAM_STEP_MS = 33L;          // ≈30fps
    private static final long ROAM_WALK_MIN_MS = 2_500L;
    private static final long ROAM_WALK_MAX_MS = 7_000L;
    /**
     * 两次「是否走路」的判定间隔（设计上要求：每隔 30 秒随机一次是否走路）。
     * ★ 原来用的是"停 3~9 秒就再走"，等于几乎一直在动 ——
     *   实测"走路频率太高了"。现在改成到点掷骰子：
     *   掷中才走，没掷中就再等 30 秒。
     */
    private static final long ROAM_SLOT_MS = 30_000L;

    private int roamState = ROAM_IDLE;
    private int roamDir = 1;              // +1 向右，-1 向左
    private long roamWalkStart = 0L;
    private long roamWalkDuration = 0L;

    private final Runnable roamStepRunnable = new Runnable() {
        @Override public void run() {
            try {
                roamTick();
            } catch (Throwable t) {
                Log.e("PetOverlayService", "roamTick failed", t);
                stopRoam();
            }
        }
    };

    /** 重新开始漫游（设置变化 / 启动 / 恢复时调用）。
     *  ★ 幂等：已经在排程中就不重排。
     *  ---- 为什么必须幂等（实测 2026-10-06）----
     *  Service 的 onStartCommand 会被反复调用（主界面 resume 等），
     *  每次都调本方法 → 每次都 postDelayed(30s) → **计时被反复重置**，
     *  日志实测每 15~20 秒就重启一次排程，"到点掷骰子"永远到不了。 */
    public void restartRoam() {
        restartRoam(false);
    }

    /**
     * @param force true = 强制重新排程（用户改了概率等设置时用；
     *              否则会被"幂等保护"挡掉，改了设置不生效）
     */
    public void restartRoam(boolean force) {
        PetStore.Appearance a = store.appearance();
        if (!a.roamEnabled || rootView == null) {
            stopRoam();
            return;
        }
        if (!force && handler.hasCallbacks(roamStepRunnable)) {
            Log.i("PetOverlayService", "roam restart: 已在排程中，跳过（不重置计时）");
            return;
        }
        stopRoam();
        roamState = ROAM_IDLE;
        roamDir = random.nextBoolean() ? 1 : -1;
        /* 每 ROAM_SLOT_MS 到点掷一次骰子决定要不要走（见 roamTick）。 */
        handler.postDelayed(roamStepRunnable, ROAM_SLOT_MS);
        Log.i("PetOverlayService", "roam restart: enabled=" + a.roamEnabled
                + " chance=" + a.roamChance
                + (force ? " (force)" : ""));
    }

    /**
     * 停止漫游并回到待机动作。
     * @param keepFacing true = 保留当前朝向（一次走动结束时不复位，
     *                   免得"走完立刻转回正面"这种突兀动作；
     *                   用户拖拽等交互时才复位）
     */
    public void stopRoam(boolean keepFacing) {
        handler.removeCallbacks(roamStepRunnable);
        if (roamState == ROAM_WALK && spriteView != null) {
            spriteView.setAction(PetContracts.PetAction.IDLE, true);
        }
        roamState = ROAM_IDLE;
        pushVmdWalkState(false, 0);
        /* ★ 停下时把朝向复位成 0（面向镜头）。
           否则模型会一直侧着身（往左/往右走的 90° 留着），看着像坏了。
           保留 keepFacing 参数是为了以后想做"保持朝向"时不用改调用方。 */
        if (!keepFacing) {
            try {
                if (mmdView != null) {
                    mmdView.eval("window.__setVmdFacing && window.__setVmdFacing(0)");
                }
            } catch (Throwable ignored) {}
        }
    }

    public void stopRoam() {
        stopRoam(false);
    }

    /** 漫游是否处于"可走"的时机（拖拽/对话/互动时不允许走）。 */
    private boolean roamAllowedNow() {
        if (rootView == null) return false;
        if (dragging) return false;
        if (dialogueController != null && dialogueController.isShowing()) return false;
        if (interactToken != null) return false;
        return true;
    }

    private void roamTick() {
        PetStore.Appearance a = store.appearance();
        if (!a.roamEnabled) { stopRoam(); return; }
        if (rootView == null) { stopRoam(); return; }

        WindowManager.LayoutParams p = (WindowManager.LayoutParams) rootView.getLayoutParams();
        if (p == null) { stopRoam(); return; }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int w = p.width > 0 ? p.width : petWidthPx(a);
        int h = p.height > 0 ? p.height : w;
        int maxX = Math.max(0, screenW - w);
        int maxY = Math.max(0, screenH - h);

        if (roamState == ROAM_IDLE) {
            /* ★★ 每 ROAM_SLOT_MS（30 秒）到点掷一次骰子决定要不要走。
               ---- 为什么这么改（2026-10-06）----
               原来：停 3~7 秒 → 走 2.5~7 秒 → 停 → …… 一直在动，
               用户觉得"走路频率太高了"。而且用户要的是
               「每隔 30 秒随机一下是否走路 + 一个概率滑块」。
               ★ 不该走的时机（拖拽/对话/互动）【不消耗这次机会】，
                 等 3 秒再试，保证"到点必掷骰"这件事不会被悄悄跳过。 */
            if (!roamAllowedNow()) {
                handler.postDelayed(roamStepRunnable, 3000L);
                return;
            }
            int chance = Math.max(0, Math.min(100, a.roamChance));
            if (random.nextInt(100) >= chance) {
                Log.i("PetOverlayService", "roam: 本次不走（概率 " + chance + "%），"
                        + (ROAM_SLOT_MS / 1000) + " 秒后再掷");
                handler.postDelayed(roamStepRunnable, ROAM_SLOT_MS);
                return;
            }
            roamState = ROAM_WALK;
            roamWalkStart = System.currentTimeMillis();
            roamWalkDuration = ROAM_WALK_MIN_MS
                    + (long) (random.nextFloat() * (ROAM_WALK_MAX_MS - ROAM_WALK_MIN_MS));
            /* 摇到边缘附近时优先往屏幕里走 */
            if (p.x <= 2) roamDir = 1;
            else if (p.x >= maxX - 2) roamDir = -1;
            else roamDir = random.nextBoolean() ? 1 : -1;
            Log.i("PetOverlayService", "roam: 开始走动 dir=" + roamDir
                    + " 走 " + (roamWalkDuration / 1000) + "s（概率 " + chance + "%）");
            if (spriteView != null) {
                spriteView.setAction(roamDir > 0
                        ? PetContracts.PetAction.MOVE_RIGHT
                        : PetContracts.PetAction.MOVE_LEFT, true);
            }
            /* ★ 3D 模式：切走路动作 + 侧身朝向。
               ★★ dir 直接传 roamDir（+1 往右 / -1 往左）→ JS 侧映射成 ±90°。
                  不是 180° 掉头！两次做错的记录见上面常量区的注释。 */
            pushVmdWalkState(true, roamDir);
            handler.postDelayed(roamStepRunnable, ROAM_STEP_MS);
            return;
        }

        // ---- ROAM_WALK ----
        /* 时机不对（用户拖了 / 打开对话）→ 原地停下待机 */
        if (!roamAllowedNow()) {
            endWalk();
            handler.postDelayed(roamStepRunnable, 800L);
            return;
        }

        int step = Math.max(1, Math.min(12, a.roamSpeed));
        int nx = p.x + roamDir * step;

        /* 碰边缘掉头（不允许走出屏幕） */
        if (nx <= 0) { nx = 0; roamDir = 1; syncWalkAnimation(); }
        else if (nx >= maxX) { nx = maxX; roamDir = -1; syncWalkAnimation(); }

        /* 用"当前位置 + 增量"，不要用 startX 累加 */
        p.x = nx;
        // 顺便做一点点上下漂移，避免完全水平移动显得呆板
        try { windowManager.updateViewLayout(rootView, p); } catch (Exception ignored) {}
        updateBubblePosition(p.x, p.y, p.width, p.height);

        startX = p.x;
        startY = p.y;

        if (System.currentTimeMillis() - roamWalkStart >= roamWalkDuration) {
            endWalk();
            /* ★ 走完回到"等下一次判定"：等 ROAM_SLOT_MS 后再掷骰子。
               不再用"停 3~9 秒就再走"——那是"一直在动"的根源。
               注意这里【不调用 playRandomIdleAction】，
               因为待机动作现在由 VMD 的待机动画负责（有配就不会静止）。 */
            Log.i("PetOverlayService", "roam: 走完一段，"
                    + (ROAM_SLOT_MS / 1000) + " 秒后再判定");
            handler.postDelayed(roamStepRunnable, ROAM_SLOT_MS);
            return;
        }
        handler.postDelayed(roamStepRunnable, ROAM_STEP_MS);
    }

    /** 走动结束：回到待机并记住位置。 */
    private void endWalk() {
        roamState = ROAM_IDLE;
        if (spriteView != null) {
            spriteView.setAction(PetContracts.PetAction.IDLE, true);
        }
        pushVmdWalkState(false, 0);
        /* ★ 走完一段要把朝向复位成 0（面向镜头）。
           否则模型会一直侧着身，看着像坏了。 */
        try {
            if (mmdView != null) mmdView.eval("window.__setVmdFacing && window.__setVmdFacing(0)");
        } catch (Throwable ignored) {}
        persistRoamPosition();
    }

    /** 掉头时同步跑步动画方向。 */
    /**
     * 掉头时同步跑步动画方向。
     *
     * ★★ 注意：这里【不能】用 `if (spriteView == null) return;` 提前返回 ——
     *   MMD 模式下 spriteView 恒为 null，一 return 就再也不会执行下面的
     *   pushVmdWalkState，表现就是"碰到边缘掉头了但朝向没跟着变"（2026-10-06）。
     *   这是文档 §17.6「守卫里的 return 跳过了必须执行的初始化」的同款问题。
     */
    private void syncWalkAnimation() {
        if (spriteView != null) {
            spriteView.setAction(roamDir > 0
                    ? PetContracts.PetAction.MOVE_RIGHT
                    : PetContracts.PetAction.MOVE_LEFT, true);
        }
        /* ★ 边缘掉头也要同步转身（否则会"倒着走"）。
           dir 直接传 roamDir：+1 往右 → 转 +90°，-1 往左 → 转 -90°。 */
        pushVmdWalkState(true, roamDir);
    }

    /** 把漫游后的位置写回配置（和手动拖动一致，保证重启后位置不跳）。 */
    private void persistRoamPosition() {
        try {
            PetStore.Appearance a = store.appearance();
            WindowManager.LayoutParams p =
                    (WindowManager.LayoutParams) rootView.getLayoutParams();
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w = p.width > 0 ? p.width : 1;
            int h = p.height > 0 ? p.height : 1;
            a.normalizedX = clamp01((float) p.x / Math.max(1, screenW - w));
            a.normalizedY = clamp01((float) p.y / Math.max(1, screenH - h));
            store.saveAppearance(a);
        } catch (Throwable ignored) {}
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------------- 触摸 ----------------

    private final class PetTouchListener implements View.OnTouchListener {
        private final GestureDetector gestureDetector;

        PetTouchListener() {
            gestureDetector = new GestureDetector(PetOverlayService.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(MotionEvent e) { return true; }
                        @Override public void onLongPress(MotionEvent e) {
                            performHapticFeedbackSafe();
                        }
                    });
        }

        @Override public boolean onTouch(View v, MotionEvent event) {
            /* ★ 精灵图模式但没有素材 → 不消费触摸。
               公开仓库版不带素材包，首次启动就是这个状态；
               让一块点不动的透明区域浮在桌面上很莫名（虽然窗口不大）。 */
            if (spriteView != null && !spriteView.hasContent()
                    && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 全屏眼动：无论点在哪，坐标都喂给视线
                    feedLook(event.getRawX(), event.getRawY());
                    if (mmdView != null) mmdView.setLookHeld(true);
                    /**
                     * MMD 模式：只有点在模型实际范围内才响应，
                     * 否则整个透明矩形都会触发互动/三击（透明区域"太大"）。
                     *
                     * 全屏捕获模式下窗口铺满屏幕，模型以外的区域直接返回 false，
                     * 让事件继续传递给下层窗口；模型范围内才消费。
                     */
                    if (mmdView != null && rootView != null) {
                        boolean hit = mmdView.hitTest(event.getX(), event.getY(),
                                rootView.getWidth(), rootView.getHeight());
                        if (!hit) {
                            dragging = false;
                            return false;   // 不消费，交给下层
                        }
                    }
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    // 关键：以当前真实窗口位置为基准，否则第二次拖动会跳回初始位置
                    if (rootView != null && rootView.getLayoutParams() instanceof WindowManager.LayoutParams) {
                        WindowManager.LayoutParams cur =
                                (WindowManager.LayoutParams) rootView.getLayoutParams();
                        dragBaseX = cur.x;
                        dragBaseY = cur.y;
                    }
                    dragging = false;
                    lastTouchAt = System.currentTimeMillis();
                    scheduleNextIdleRandom();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    feedLook(event.getRawX(), event.getRawY());
                    /**
                     * ★ 全屏眼动模式下禁止拖动窗口。
                     *   窗口本身就是整个屏幕，一旦移动窗口，
                     *   模型会被立刻推出屏幕 —— 用户看到的
                     *   "点一下模型就不见了、滑动又从屏幕底下冒出来"
                     *   就是这个原因。
                     */
                    if (isFullscreenGaze()) {
                        return true;
                    }
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging = true;
                        // 开始拖拽即取消本次点击序列，避免拖完误触发对话框
                        cancelTapSequence();
                        /* 用户接管了位置 → 暂停漫游，别和手指抢窗口 */
                        stopRoam();
                        /* ★★ 拖动动画（2026-10-07 需求）★★
                           「VMD 动画」页的【拖动时动画】插槽（id = drag）配了就在这一刻播。
                           这类"被拎起来"的动作末帧通常是悬空姿态，而 drag 是
                           一次性槽位（LoopOnce + clampWhenFinished）→ 模型会在
                           【整个拖拽期间】保持那个姿态跟着手走，手感很自然。
                           没配动作时 JS 侧只打一行日志就返回，无害。 */
                        if (mmdView != null) mmdView.playVmdSlot("drag");
                    }
                    if (dragging && rootView != null) {
                        updatePosition(dx, dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    // 松手 -> 眼睛开始回正
                    if (mmdView != null) mmdView.setLookHeld(false);
                    if (isFullscreenGaze()) {
                        dragging = false;
                        return true;
                    }
                    if (dragging) {
                        /* ★ 先夹回屏幕内再落盘 —— 用户可能把桌宠拖到一半在屏幕外松手 */
                        clampWindowInto(dp(2));
                        persistPosition();
                        dragging = false;
                        /* ★ 拖动结束 → 让"被拎起来"的动画收尾、淡出回待机。
                           必须主动收尾：clampWhenFinished 会让模型一直悬在末帧，
                           不等它自然播完（用户放下时希望它立刻站好）。 */
                        if (mmdView != null) mmdView.dragEnd();
                        /* 用户放好了位置 → 恢复漫游（会从新位置附近继续） */
                        restartRoam(true);   // 用户刚放下 → 重新开始计时
                    } else {
                        // 直接在 UP 计数，响应最快，且不会被 GestureDetector 的双击超时吞掉
                        handleSingleTap(event.getY());
                    }
                    return true;
                case MotionEvent.ACTION_OUTSIDE:
                    // 手指在桌宠窗口之外 -> 只驱动视线（内部已含开关与有效性过滤）
                    feedLookAbsolute(event.getRawX(), event.getRawY());
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    if (mmdView != null) mmdView.setLookHeld(false);
                    /* ★ 手势被系统打断（来电/切走）也要把拖动动画收尾，
                       否则模型会一直悬在"被拎起来"的末帧。 */
                    if (dragging && mmdView != null) mmdView.dragEnd();
                    dragging = false;
                    return true;
            }
            return true;
        }

        private void updatePosition(float dx, float dy) {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) rootView.getLayoutParams();
            p.x = dragBaseX + (int) dx;
            p.y = dragBaseY + (int) dy;
            try { windowManager.updateViewLayout(rootView, p); } catch (Exception ignored) {}
            updateBubblePosition(p.x, p.y, p.width, p.height);
        }

        private void persistPosition() {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) rootView.getLayoutParams();
            startX = p.x;
            startY = p.y;
            PetStore.Appearance a = store.appearance();
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w = petWidthPx(a);
            a.normalizedX = clamp01((float) p.x / Math.max(1, screenW - w));
            a.normalizedY = clamp01((float) p.y / Math.max(1, screenH - w));
            store.saveAppearance(a);
        }
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /* ====================================================================
       ★★★ 越界回收：保证桌宠永远停在屏幕内（2026-10-17 需求）★★★
       ---- 为什么需要 ----
       曾出现"猪总会出现在屏幕外"。已知的几条越界路径：
         · 换了分辨率/折叠屏展开/分屏 → 旧像素坐标直接超出新屏幕
         · 用户拖到边上再松手（拖拽过程中没有夹取）
         · 模型宽度变化（MMD 缩放、切 2D/3D）后窗口变宽 → 右侧被裁掉
         · 漫游/外部调用直接把 x/y 写成负数或超大值
       ---- 关键要求 ----
       "不要硬编码手机的宽高像素，因为也是要发给朋友的"
       → 一切以**运行时**的 `getResources().getDisplayMetrics()` 与
         **实际窗口尺寸** (`rootView.getLayoutParams().width/height`) 为准，
         屏幕尺寸变化时重新夹一次即可，不需要任何常量。
       ---- 实现要点 ----
       · `usableScreen()` 取真实的可用宽高（含刘海/导航栏影响）
       · `clampWindowInto()` 就地夹取并把结果写回 LayoutParams
       · `ensureVisible()` 给外部（设置页/漫游/启动）统一调用，
         返回是否发生了移动（用来决定要不要落盘）
       · 定时巡检：屏幕尺寸变化 / 窗口尺寸变化都会被"顺手"纠正
       ==================================================================== */

    /** 运行时可用的屏幕尺寸（像素）。绝不硬编码，任何设备都用这一套。 */
    private int[] usableScreen() {
        int w, h;
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            Object wm = getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm instanceof android.view.WindowManager) {
                android.view.WindowManager manager = (android.view.WindowManager) wm;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowMetrics m = manager.getCurrentWindowMetrics();
                    android.graphics.Rect b = m.getBounds();
                    w = b.width(); h = b.height();
                } else {
                    manager.getDefaultDisplay().getRealMetrics(dm);
                    w = dm.widthPixels; h = dm.heightPixels;
                }
            } else {
                dm = getResources().getDisplayMetrics();
                w = dm.widthPixels; h = dm.heightPixels;
            }
        } catch (Throwable t) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels; h = dm.heightPixels;
        }
        return new int[]{ Math.max(1, w), Math.max(1, h) };
    }

    /**
     * 把窗口夹进屏幕内。
     * @param marginPx 四周留白（避免贴着边缘完全看不见阴影/描边）
     * @return 是否发生了移动
     */
    private boolean clampWindowInto(int marginPx) {
        if (rootView == null) return false;
        WindowManager.LayoutParams p =
                (WindowManager.LayoutParams) rootView.getLayoutParams();
        if (p == null) return false;
        int[] scr = usableScreen();
        int screenW = scr[0], screenH = scr[1];
        int w = p.width > 0 ? p.width : rootView.getWidth();
        int h = p.height > 0 ? p.height : rootView.getHeight();
        if (w <= 0 || h <= 0) return false;
        /* ★ 窗口比屏幕还大时，上限要让给"至少能看到一部分"：
           直接把 max 变成负数会让 clamp 把窗口推到屏幕外，所以取 Math.max(0, ...)。 */
        int maxX = Math.max(0, screenW - w);
        int maxY = Math.max(0, screenH - h);
        int m = Math.max(0, marginPx);
        int minX = Math.min(m, maxX);
        int minY = Math.min(m, maxY);
        int nx = Math.max(minX, Math.min(maxX, p.x));
        int ny = Math.max(minY, Math.min(maxY, p.y));
        if (nx == p.x && ny == p.y) return false;
        p.x = nx; p.y = ny;
        try { windowManager.updateViewLayout(rootView, p); } catch (Throwable ignored) {}
        updateBubblePosition(nx, ny, w, h);
        startX = nx; startY = ny;
        Log.i("PetOverlayService", "越界回收: (" + p.x + "," + p.y + ") -> (" + nx + "," + ny
                + ")  屏幕=" + screenW + "x" + screenH + " 窗口=" + w + "x" + h);
        return true;
    }

    /**
     * 对外统一入口：确保桌宠在屏幕内，并把位置落盘（归一化存，跨设备/换分辨率安全）。
     * ★ 用归一化坐标存（normalizedX/Y），所以"发给朋友 + 朋友手机分辨率不同"也不会跑出去。
     */
    public void ensureVisible(String reason) {
        try {
            boolean moved = clampWindowInto(dp(2));
            if (moved) {
                persistCurrentPosition();
                Log.i("PetOverlayService", "ensureVisible(" + reason + "): 已把桌宠拉回屏幕内");
            }
        } catch (Throwable t) {
            Log.w("PetOverlayService", "ensureVisible failed", t);
        }
    }

    /** 把当前窗口位置按归一化坐标存起来（供下次启动/换设备恢复）。 */
    public void persistCurrentPosition() {
        try {
            if (rootView == null) return;
            WindowManager.LayoutParams p =
                    (WindowManager.LayoutParams) rootView.getLayoutParams();
            if (p == null) return;
            int[] scr = usableScreen();
            int w = p.width > 0 ? p.width : rootView.getWidth();
            int h = p.height > 0 ? p.height : rootView.getHeight();
            PetStore.Appearance a = store.appearance();
            a.normalizedX = clamp01((float) p.x / Math.max(1, scr[0] - w));
            a.normalizedY = clamp01((float) p.y / Math.max(1, scr[1] - h));
            store.saveAppearance(a);
        } catch (Throwable t) {
            Log.w("PetOverlayService", "persistCurrentPosition failed", t);
        }
    }

/** 定时巡检（屏幕旋转/分屏/窗口尺寸变化时自动纠正）。 */
    private final Runnable keepVisibleRunnable = new Runnable() {
        @Override public void run() {
            try { ensureVisible("tick"); } catch (Throwable ignored) {}
            handler.postDelayed(this, 4000L);
        }
    };

    private void performHapticFeedbackSafe() {
        try { rootView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); }
        catch (Exception ignored) {}
    }

    /** 连续单击三次：累积计数，窗口内触发输入框。 */
    private void handleSingleTap(float localY) {
        // 每次单击立刻播分区动画（头/身/脚不同动作）
        float ny = 0.5f;
        if (spriteView != null) {
            ny = Math.max(0f, Math.min(1f, spriteView.normalizedYFor(localY)));
        } else if (mmdView != null && rootView != null && rootView.getHeight() > 0) {
            // MMD 模式：用模型包围盒换算归一化高度，分区才准确
            ny = mmdView.normalizedYFor(localY, rootView.getHeight());
        }
        PetContracts.PetBodyRegion region = regionFor(ny);
        playTouchReaction(region);
        if (interactionListener != null) interactionListener.onTouchRegion(region);
        maybeTriggerInteraction(region);

        long now = System.currentTimeMillis();
        // 超过窗口则重新计数
        if (now - lastTapAt > TRIPLE_TAP_WINDOW_MS) tapCount = 0;
        lastTapAt = now;
        tapCount++;
        handler.removeCallbacks(tripleTapRunnable);
        if (tapCount >= 3) {
            tapCount = 0;
            onTripleTap();
        }
        // 不再 post 延迟触发：只有真正攒够 3 次才开对话框
    }

    private void cancelTapSequence() {
        tapCount = 0;
        handler.removeCallbacks(tripleTapRunnable);
    }


    private void onTripleTap() {
        // 分区动画已在每次单击时播放，这里只负责唤出输入框。
        if (interactionListener != null) interactionListener.onTripleTap();
        else dialogueController.showInput();
    }

    /** 头部/身体/脚部区域判定，边界来自可调百分比。 */
    public PetContracts.PetBodyRegion regionFor(float normalizedY) {
        PetStore.Appearance a = store.appearance();
        if (normalizedY < a.headBoundary) return PetContracts.PetBodyRegion.HEAD;
        if (normalizedY < a.bodyBoundary) return PetContracts.PetBodyRegion.BODY;
        return PetContracts.PetBodyRegion.FEET;
    }

    /**
     * 互动反馈。
     * 概率触发；已在互动中时，连续点击达到阈值会强制停止这次互动。
     */
    private void maybeTriggerInteraction(PetContracts.PetBodyRegion region) {
        try {
            tryCropMmdWindow();
            // 互动进行中：桌宠上的点击照常走三击逻辑（用于打开对话框），
            // 不用来停止互动——停止只由气泡承担（气泡可触摸且不与桌宠重叠）。
            if (interactToken != null) {
                return;
            }

            if (!store.interactEnabled()) return;
            if (!store.rollInteract()) return;
            final PetStore.Provider provider = store.activeProvider();
            if (provider == null || !provider.isUsable()) return;

            final String action = touchActionText(region);
            final String personaPrompt = store.resolveInteractPersonaPrompt();

            final PetAiClient.CancelToken token = new PetAiClient.CancelToken();
            interactToken = token;
            interactStopTaps = 0;
            interactFirstTapAt = 0L;
            if (speechBubble != null) {
                speechBubble.resetStopTaps();
                speechBubble.showThinking();
            }

            new Thread(() -> {
                PetAiClient.interactStream(provider, personaPrompt, action,
                        new PetAiClient.InteractCallback() {
                    @Override public void onDelta(String accumulatedText) {
                        handler.post(() -> {
                            if (speechBubble != null && interactToken == token) {
                                speechBubble.update(accumulatedText);
                            }
                        });
                    }
                    @Override public void onDone(String finalText) {
                        handler.post(() -> {
                            if (interactToken == token) {
                                interactToken = null;
                                interactStopTaps = 0;
                            }
                            if (speechBubble != null) {
                                // 输出完成后再开始计时，时长随文本长度自适应
                                speechBubble.show(finalText);
                                playAction(PetContracts.PetAction.WAVE, false);
                                handler.removeCallbacks(revertActionRunnable);
                                handler.postDelayed(revertActionRunnable, FEEDBACK_DURATION_MS);
                            }
                        });
                    }
                    @Override public void onError(String code) {
                        handler.post(() -> {
                            if (interactToken == token) {
                                interactToken = null;
                                interactStopTaps = 0;
                            }
                            if (speechBubble != null) speechBubble.hide();
        if (mmdView != null) mmdView.destroy();
        mmdGuides = null;
                        });
                    }
                }, token);
            }, "pet-interact").start();
        } catch (Throwable t) {
            android.util.Log.e("PetOverlayService", "maybeTriggerInteraction failed", t);
            interactToken = null;
            interactStopTaps = 0;
        }
    }

    /** 强制停止当前互动（气泡连点或桌宠连点都会走这里）。 */
    private void forceStopInteraction() {
        try {
            if (interactToken != null) {
                interactToken.cancel();
            }
        } catch (Throwable ignored) {}
        interactToken = null;
        interactStopTaps = 0;
        interactFirstTapAt = 0L;
        if (speechBubble != null) speechBubble.hide();
        if (mmdView != null) mmdView.destroy();
        mmdGuides = null;
        android.widget.Toast.makeText(this, "已停止互动",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 已按掩码内容裁剪的次数。
     * 不能只做一次：切换模型后内容范围会变（不同体型的模型头顶空白不同），
     * 而且掩码首次上报时可能还是上一个模型的，需要允许后续再收紧。
     */
    private int mmdCropCount = 0;

    /**
     * ★★ 当前模型"适配后"的宽高比（0 = 还没适配过）★★
     *
     * ---- 为什么需要它（2026-10-07 曾出现"模型有时会缩水"，这是第二半根因）----
     * `maybeAdaptWindowAspect` 会把窗口宽度改成 `高度 × 模型宽高比`（去掉左右留白），
     * **但 `applyAppearance()` 不知道这件事** —— 它每次都会把宽度重算成
     * `mmdWidthPx(a)`（由"显示大小"滑块决定的配置尺寸），把适配结果覆盖掉。
     * 实测日志（两者来回拉锯，窗口 175→112→175… 每秒跳一次）：
     *     21:51:55 onResize(175x236)
     *     21:51:56 onResize(112x236)
     *     21:51:57 onResize(175x236)
     * 每次宽度变化都触发一次 resize/取景 → 画面一直在抖（且落在窄的一侧就是"缩水"）。
     *
     * → 两边必须用【同一套算法】：applyAppearance 也在有适配值时按同一个宽高比定宽。
     *   高度不变（仍由"显示大小"滑块决定），所以模型大小不会变。
     */
    private float mmdModelAspect = 0f;
    /**
     * 裁剪基准尺寸（未按内容收紧前的窗口大小）。
     * ★ 每次裁剪都必须基于基准算，不能在结果上叠乘 ——
     *   否则多次收紧会指数收缩（实测 456→234→127→63）。
     */
    private int mmdBaseW = 0, mmdBaseH = 0;
    private int mmdBaseX = 0, mmdBaseY = 0;
    private static final int MMD_CROP_MAX = 3;

    /**
     * 按掩码实测内容收紧 MMD 窗口高度。
     * 掩码显示模型内容常只占中间一段（实测 Arona 只占 23~59 行），
     * 上方大片空白会让"可触摸区域"显得过大；这里直接裁掉。
     * 只裁剪窗口，不动相机，因此不会干扰用户的相机距离设置。
     */
    /**
     * 按实际内容【宽度】收窄窗口（只做一次）。
     *
     * 背景：窗口宽高比固定为 MMD_ASPECT(1.35)，而模型实际渲染是细长一条，
     *       两侧会留下大片空白（即使关掉自转也存在）。
     * 做法：量出掩码的列范围，只收窄【宽度】并水平居中。
     * ★ 只动宽度不动高度：高度变化会触发 WebView resize → 重新取景，
     *   导致留白又回来（之前踩过这个坑）。
     */
    /**
     * 窗口尺寸调整已停用。
     *
     * 教训：改窗口尺寸会触发 WebView resize → 相机重新取景 → 内容变化
     * → 再次触发调整，形成**恶性循环**（实测窗口被连续收窄
     * 608→318→289→263，模型被裁出视口后消失）。
     *
     * 现在留白（横向/纵向）统一由 JS 侧的相机取景解决
     * （fitCameraToBox + correctCameraFromMask），窗口尺寸保持不变。
     */
    private void tryCropMmdWindow() {
        // 故意留空（保留方法便于以后需要时恢复）
    }

    /**
     * 全屏眼动模式已移除（Android 悬浮窗无法"部分区域穿透"，
     * 全屏窗口必然挡住其他 App）。保留方法恒返回 false。
     */
    private boolean isFullscreenGaze() {
        return false;
    }

    /** 把触摸区域转成一句给模型的动作描述。 */
    private String touchActionText(PetContracts.PetBodyRegion region) {
        switch (region) {
            case HEAD: return "用户摸了摸你的头";
            case BODY: return "用户戳了戳你的身体";
            case FEET: return "用户碰了碰你的脚";
            default: return "用户点了点你";
        }
    }

    /** 气泡跟随桌宠位置。 */
    private void updateBubblePosition(int petX, int petY, int petW, int petH) {
        if (speechBubble != null) speechBubble.followPet(petX, petY, petW, petH);
    }

    /**
     * 触摸反馈：头/身/脚 各自不同动画，播完回到待机。
     * 头=挥手(3) 身=跳跃(4) 脚=思考/审阅(8)，三区动作互不相同。
     */
    public void playTouchReaction(PetContracts.PetBodyRegion region) {
        /* ★ 3D 模式：优先播 VMD 动作（用户绑定的摸头/摸身/摸脚动作）。
           页面没配或没加载成功时它自己会 fallback 到内置程序化反应。 */
        switch (region) {
            case HEAD: playVmdSlot("touchHead"); break;
            case BODY: playVmdSlot("touchBody"); break;
            case FEET: playVmdSlot("touchFeet"); break;
            default: break;
        }
        if (spriteView == null) return;
        PetContracts.PetAction act;
        long duration;
        switch (region) {
            case HEAD:
                act = PetContracts.PetAction.WAVE;
                duration = 1000;
                break;
            case BODY:
                act = PetContracts.PetAction.JUMP;
                duration = 1100;
                break;
            case FEET:
                act = PetContracts.PetAction.REVIEW;
                duration = 1400;
                break;
            default:
                act = PetContracts.PetAction.IDLE;
                duration = FEEDBACK_DURATION_MS;
        }
        spriteView.setAction(act, false);
        handler.removeCallbacks(revertActionRunnable);
        handler.postDelayed(revertActionRunnable, duration);
    }

    /**
     * 重新加载素材（切换桌宠后调用），不重启服务。
     * 无素材时清空显示，有素材则重新解码并刷新尺寸。
     */
    public void reloadPet() {
        try {
            mmdCropCount = 0;
            loadPet();
            if (spriteView == null) return;
            spriteView.setAtlas(atlas);
            spriteView.setAction(PetContracts.PetAction.IDLE, true);
            applyAppearance();
        } catch (Throwable t) {
            android.util.Log.e("PetOverlayService", "reloadPet failed", t);
        }
    }

    /** 清除桌宠显示（素材被删除后调用）。 */
    public void clearPet() {
        try {
            if (atlas != null) {
                atlas.close();
                atlas = null;
            }
            if (spriteView != null) {
                spriteView.setAtlas(null);
                spriteView.invalidate();
            }
        } catch (Throwable t) {
            android.util.Log.e("PetOverlayService", "clearPet failed", t);
        }
    }

    /**
     * 切换 GPU 加速后重载 MMD 页面（设置页调用）。
     *
     * ★ 为什么必须重载而不是热切换：
     *   WebGL 的 antialias / powerPreference 只能在创建上下文时决定，
     *   运行中改不了。重载后 JS 会从 localStorage 恢复设置，配置不会丢。
     */
    public void reloadMmd() {
        try {
            if (mmdView == null) return;
            PetStore.Appearance a = store.appearance();
            mmdView.setGpuAccel(a.gpuAccel);
            mmdView.reloadForGpuAccel();
            /* 页面重载后要重新下发各项设置：
               reloadForGpuAccel 会把 pageLoaded 置 false，
               这些 eval 会先入队，等页面 ready 后自动 flush。 */
            handler.postDelayed(() -> {
                try {
                    mmdView.setAntiAliasLevel(a.mmdAntiAliasLevel);
                    mmdView.setPerf(a.renderScale, a.perfAuto, a.fpsCap);
                    mmdView.setPhysics(a.physicsEnabled);
                    if (a.mmdModelUrl != null && !a.mmdModelUrl.isEmpty()) {
                        mmdView.loadModel(a.mmdModelUrl);
                    }
                } catch (Throwable ignored) {}
            }, 1200);
        } catch (Throwable t) {
            Log.e("PetOverlayService", "reloadMmd failed", t);
        }
    }

    /**
     * 原地更新桌宠尺寸与帧率，不重启服务。
     * 频繁启停服务会在销毁过程中重复添加悬浮窗而崩溃，故必须原地更新。
     */
    public void applyAppearance() {
        if (rootView == null) return;
        PetStore.Appearance a = store.appearance();
        try {
            boolean isMmd = "mmd".equals(a.renderMode);
            int w = isMmd ? mmdWidthPx(a) : petWidthPx(a);
            int h = isMmd
                    ? (int) (w * MMD_ASPECT)
                    : (int) ((float) w * PetContracts.CODEX_FRAME_HEIGHT / PetContracts.CODEX_FRAME_WIDTH);
            /* ★★ 与 maybeAdaptWindowAspect 保持同一套算法（见 mmdModelAspect 的说明）★★
               高度不变，宽度按"模型宽高比"算 —— 两者算出来的宽度必须一致，
               否则会每秒来回覆盖，画面持续抖动。 */
            if (isMmd && mmdModelAspect > 0.05f) {
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int w2 = Math.round(h * mmdModelAspect);
                w2 = Math.max(1, Math.min(w2, screenW));
                w = w2;
            }
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) rootView.getLayoutParams();
            /* ★★ 只有尺寸【真的变了】才更新窗口。
               ---- 事故复盘（实测"模型会突然拉长，然后很快又连着缩水两次"）----
               applyAppearance() 会被调用多次（模型就绪 / 性能设置下发 / 物理开关下发…），
               原来每次都无条件 updateViewLayout。
               而改窗口尺寸会让 WebView 派发 resize → 触发重新取景，
               于是短时间内连做几轮取景 —— 用户看到的就是"连着缩两次"。
               ★ 而且模型加载后窗口尺寸几乎不变，这些 resize 纯属无效触发。 */
            if (params.width != w || params.height != h) {
                params.width = w;
                params.height = h;
                windowManager.updateViewLayout(rootView, params);
            }
            if (spriteView != null) {
                spriteView.setScale(a.scale);
                spriteView.setFps(a.fps);
            }
            if (speechBubble != null) {
                speechBubble.setHeightPct(a.bubbleHeightPct / 100f);
                speechBubble.setMinPct(a.bubbleMinPct / 100f);
            }
            if (isMmd && mmdView != null) {
                /* ★ 模型可能刚被删除/导入 —— 命中测试的"有无模型"状态要跟着变，
                   否则删掉模型后那块窗口还在吃触摸，或者导入后点不动。 */
                mmdView.setModelMissing(a.mmdModelUrl == null || a.mmdModelUrl.isEmpty());
                // 抗锯齿可热切换（无需重启）
                mmdView.setAntiAliasLevel(a.mmdAntiAliasLevel);
                /* 性能设置也可热切换。
                   GPU 加速不行（要重建 WebGL 上下文）→ 由设置页负责重载。 */
                mmdView.setPerf(a.renderScale, a.perfAuto, a.fpsCap);
                mmdView.setPhysics(a.physicsEnabled);
                applyMotionBlur();
            }
            updateBubblePosition(params.x, params.y, params.width, params.height);
            /* 尺寸变了要按新尺寸重新计算漫游边界 */
            restartRoam(true);   // 边界变了，必须重排
        } catch (Throwable t) {
            android.util.Log.e("PetOverlayService", "applyAppearance failed", t);
        }
    }

    /**
     * 把触摸坐标喂给 MMD 页面做全屏眼动追踪。
     *
     * 实现说明：
     *   悬浮窗里 WebView 收不到 pointermove（外层容器拦截了触摸），
     *   必须由原生转发；窗口外的滑动通过
     *   FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE 事件获得。
     *   但 ACTION_OUTSIDE 只给按下那一次坐标，没有连续的移动轨迹，
     *   因此这里额外做「视线保持」：最后一次坐标会保留，眼睛持续看向那个方向。
     */
    private void feedLook(float rawX, float rawY) {
        // 仅在开启全屏眼动时喂；否则保持眼睛静止
        if (!isFullscreenGaze()) return;
        feedLookAbsolute(rawX, rawY);
    }

    /**
     * 用绝对坐标（相对屏幕）喂视线。
     *
     * ★ 两处过滤（否则会出现「点其他 App 时眼睛抽到左上角」）：
     *   1) 关闭全屏眼动时不喂 —— 否则残留的 ACTION_OUTSIDE 仍会驱动视线；
     *   2) 过滤掉 (0,0) 附近的无效坐标 ——
     *      FLAG_WATCH_OUTSIDE_TOUCH 在窗口外点击时只给一次坐标，
     *      某些 ROM 会填 0,0，直接被当成"看向左上角"。
     */
    private void feedLookAbsolute(float rawX, float rawY) {
        if (mmdView == null) return;
        if (!isFullscreenGaze()) return;   // 未开启全屏眼动 -> 不喂
        try {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            if (sw <= 0 || sh <= 0) return;
            // 无效坐标过滤（0,0 附近视为无效）
            if (rawX <= 1f && rawY <= 1f) return;
            float nx = rawX / sw;
            float ny = rawY / sh;
            if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return;
            mmdView.setLook(nx, ny);
        } catch (Throwable ignored) {}
    }

    /** 查询渲染信息（诊断用）。 */
    public void queryRenderInfo(java.util.function.Consumer<String> cb) {
        if (mmdView != null) {
            mmdView.queryRenderInfo(cb);
        } else if (cb != null) {
            cb.accept("非 MMD 模式");
        }
    }

    /** 查询详细渲染信息（含真实 GPU 名字、是否软件渲染、draw call 等）。 */
    public void queryRenderDetail(java.util.function.Consumer<String> cb) {
        if (mmdView != null) {
            mmdView.queryRenderDetail(cb);
        } else if (cb != null) {
            cb.accept("{}");
        }
    }

    /** 窗口形状自动适配的限流状态。 */
    private long lastAspectAdjustAt = 0L;
    private int aspectAdjustCount = 0;
    /**
     * 是否锁定取景（由 JS 面板里的复选框上报）。
     * ★ 不放在 PetStore 里 —— 那个开关是 WebView 面板的设置，存在 localStorage；
     *   原生只需要镜像它的状态，单一数据源仍是那个复选框。
     */
    private volatile boolean viewLocked = false;

    /**
     * 把窗口宽高比调成与模型一致（消除横向留白）。
     *
     * ---- ★ 已停用（2026-10-06，实测"两个模型不同步"）----
     * 这个功能会【改变桌宠窗口的宽高比】，于是：
     *   · 桌宠悬浮窗按模型比例变窄了
     *   · 而 MMD 参数调整页仍是原窗口尺寸
     * 两边视图看起来就不一致（"不同步"）。
     * 用户明确表示这个额外功能不需要 → 直接停用。
     * 保留实现只为以后想启用时方便（把下面的 return 去掉即可）。
     * ★ 注意：横向留白是【窗口固定 1:1.35】造成的，属正常现象，
     *   用户可用「显示大小」调整整体占屏比例。
     */
    private void maybeAdaptWindowAspect(float aspect) {
        /* ★ 已重新启用（实测"模型加载了，但大小不会自适应"）★
           ---- 之前为什么停用 ----
           它会改变桌宠窗口形状，而当时的改动比较激进（每次宽高比差
           一点点就调），用户觉得"窗口自己变来变去"。现已加足限流：
             · 差 < 12% 不动
             · 两次间隔 ≥ 2s，每次加载最多 3 次
           ---- 现在为什么需要 ----
           窗口固定 1:1.35，而 GLB 模型普遍是 1:1（正方形）
           → 竖长窗口里模型只能按高度填 → 上下被切（实测 bounds y 超出）。
           按模型真实宽高比调窗口，才能"刚刚好"。 */
        try {
            /* ★ 不再要求"锁定取景"★
               ---- 为什么去掉这个门槛 ----
               实测"模型加载了，但大小不会自适应" ——
               而当时自适应被 viewLocked 挡着（默认 false）。
               窗口固定 1:1.35，GLB 模型普遍是 1:1 → 竖长窗口里只能按
               高度填，横向/纵向总有被切的一边。按模型真实宽高比调窗口
               才是"刚刚好"。限流仍然保留（差 <12% 不动 / 间隔 2s / 最多 3 次）。 */
            if (Float.isNaN(aspect) || Float.isInfinite(aspect)) return;
            if (Float.isNaN(aspect) || Float.isInfinite(aspect)
                    || aspect <= 0.05f || aspect > 5f) return;
            if (rootView == null) return;

            long now = System.currentTimeMillis();
            if (now - lastAspectAdjustAt < 2000) return;    // ② 限流（放宽到 2s）
            if (aspectAdjustCount >= 3) return;             // ③ 次数上限

            WindowManager.LayoutParams p =
                    (WindowManager.LayoutParams) rootView.getLayoutParams();
            if (p == null || p.height <= 0) return;

            /* 保持【高度】不变（模型在屏上的高度不变），只改宽度去匹配模型宽高比 */
            int wantW = Math.round(p.height * aspect);
            int curW = p.width;
            if (curW <= 0) return;
            float diff = Math.abs(wantW - curW) / (float) curW;
            if (diff < 0.12f) return;                       // ④ 差异太小不动（放宽到 12%）

            /* 边界保护：宽度不能超过屏幕，也不能太小 */
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int minW = Math.max(1, (int) (screenW * 0.02f));
            wantW = Math.max(minW, Math.min(wantW, screenW));

            lastAspectAdjustAt = now;
            aspectAdjustCount++;
            mmdModelAspect = aspect;      // ★ 让 applyAppearance 用同一个比例（否则来回覆盖）
            /* ★★★ 改宽度之前，先让页面【跳过这一次 resize 的重取景】★★★
               ---- 为什么（2026-10-07 曾出现"模型有时会缩水"）----
               本方法的目的是"去掉左右留白"，**不该改变模型大小**。
               但它改宽度会触发 WebView 的 resize → 页面重新取景；
               而窗口变窄后【横向反而成了取景约束】（模型 0.86 宽 × 1.6 高，
               窗口比例从 0.74 变成 0.51 时横向先顶住）→ 取景只能把相机往后拉
               → 实测 camZ 3.567→3.892，**用户看到的就是缩水**。
               而且新视口又会重新上报比例 → 再改窗口 → 来回振荡，
               所以症状是"**有时**会缩水"。
               → 页面侧的 __skipResizeFitOnce() 会消费掉这一次 resize：
                 camera.aspect 照常同步（不会变形），但不重新取景
                 → 相机距离不变 = 模型大小不变，只有留白消失。 */
            try {
                if (mmdView != null) {
                    mmdView.eval("window.__skipResizeFitOnce && window.__skipResizeFitOnce()");
                }
            } catch (Throwable ignored) {}
            p.width = wantW;
            windowManager.updateViewLayout(rootView, p);
            Log.i("PetOverlayService", "窗口形状适配 #" + aspectAdjustCount
                    + ": 宽 " + curW + " -> " + wantW + "（高保持 " + p.height
                    + "，模型宽高比 " + aspect + "，已抑制该次重取景）");
        } catch (Throwable t) {
            Log.w("PetOverlayService", "maybeAdaptWindowAspect failed", t);
        }
    }

    /** 换模型/解锁时重置适配计数（允许新模型重新适配几次）。 */
    private void resetAspectAdapt() {
        lastAspectAdjustAt = 0L;
        aspectAdjustCount = 0;
        mmdModelAspect = 0f;      // 换模型/重新锁定时要让 applyAppearance 先回到配置尺寸
    }

    /** 把单个外观参数应用到桌宠（由设置页同步过来）。 */
    public void applyAppearanceKV(String key, String value) {
        try {
            if (mmdView != null) mmdView.applyAppearanceKV(key, value);
        } catch (Throwable t) {
            android.util.Log.w("PetOverlayService", "applyAppearanceKV failed", t);
        }
    }

    /** 设置桌宠的饱和度/色相（由设置页跨页面同步过来）。 */
    public void setColorGrade(float sat, float hue) {
        try {
            if (mmdView != null) mmdView.setColorGrade(sat, hue);
        } catch (Throwable t) {
            Log.w("PetOverlayService", "setColorGrade failed", t);
        }
    }

    /**
     * 补齐"还没有译文"的名称。
     *
     * ★ 只翻译缓存里没有的；已有译文绝不重复翻译（省额度、秒完成）。
     * ★ 只影响显示，不动任何实际数据。
     *
     * ★★ 必须在【多个时机】都能触发，不能只在模型就绪时跑一次。
     * ---- 事故复盘（实测"没有在自动翻译啊"）----
     * 原来只在"模型就绪"那一刻调用一次。
     * 但用户通常是【桌宠已经在跑之后】才去设置页开启翻译的 ——
     * 那时早就过了那个时机，之后再没有任何东西重新触发它，
     * 于是"开了开关也一直不翻译"。
     * 现在：模型就绪 / 保存翻译设置 / 打开配件或表情页 / 手动点按钮
     * 都会触发（内部有并发保护，重复调用不会重复翻）。
     */
    private void scheduleTranslate() { translateNow(false); }

    /** 翻译中的名称（供界面显示"翻译中"标记）。 */
    private volatile java.util.Set<String> translatingNames =
            java.util.Collections.emptySet();
    /** 是否正在翻译（并发保护 + 供 UI 查询）。 */
    private volatile boolean translating = false;

    /** 立即补齐缺失译文。manual=true 时打更详细的日志。 */
    /**
     * 需要翻译的 VMD 动作名。
     *
     * ★ 只收【用户导入的】：内置动作本来就是中文名（"呼吸（待机·经典）"之类），
     *   送去翻译纯属浪费额度。
     * ★ 跳过纯 ASCII/数字的名字（比如 `walk_a`）—— 没有可翻译的内容。
     */
    private java.util.List<String> vmdTranslateCandidates() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            if (vmds == null) return out;
            for (me.rerere.pet.data.VmdManager.VmdFile f : vmds.list()) {
                if (f.preset) continue;
                if (!needsTranslate(f.name)) continue;
                out.add(f.name);
            }
            Log.i("PetOverlayService", "translate: VMD 动作名候选 " + out.size() + " 个");
        } catch (Throwable t) {
            Log.w("PetOverlayService", "vmdTranslateCandidates failed", t);
        }
        return out;
    }

    /** 名字里是否有需要翻译的非 ASCII 字符（日文假名/汉字等）。 */
    private static boolean needsTranslate(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return true;
        }
        return false;
    }

    public void translateNow(boolean manual) {
        try {
            if (translating) {
                Log.i("PetOverlayService", "translate: 正在翻译中，跳过重复请求");
                return;
            }
            final PetStore.TranslateConfig cfg = store.translateConfig();
            if (!cfg.isUsable()) {
                /* ★ 关掉开关时要清掉"翻译中"标记
                   （明确要求：关闭启用翻译名称就去除标记） */
                translatingNames = java.util.Collections.emptySet();
                if (manual) Log.i("PetOverlayService", "translate: 未启用或未配置完整，跳过");
                return;
            }

            /* 收集需要翻译的名字：配件 + 表情 + VMD 动作（都是日文，用户看不懂） */
            final java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            all.addAll(me.rerere.pet.data.PetOverlayServiceBridge.accessoryNames());
            all.addAll(me.rerere.pet.data.PetOverlayServiceBridge.expressionNames());
            all.addAll(vmdTranslateCandidates());

            final java.util.Map<String, String> cached = store.translations();
            final java.util.List<String> todo = new java.util.ArrayList<>();
            for (String n : all) if (!cached.containsKey(n)) todo.add(n);
            /* ★ 单次别提交太多：VMD 动作可能有上百个，
               一次全扔给接口容易超时/被限流。剩下的下次继续（结果有缓存）。 */
            final int MAX_PER_RUN = 150;
            if (todo.size() > MAX_PER_RUN) {
                Log.i("PetOverlayService", "translate: 本次只提交前 " + MAX_PER_RUN
                        + " 条（共 " + todo.size() + " 条，其余下次继续）");
                todo.subList(MAX_PER_RUN, todo.size()).clear();
            }
            if (todo.isEmpty()) {
                translatingNames = java.util.Collections.emptySet();
                Log.i("PetOverlayService", "translate: 全部已有译文(共 " + all.size() + ")，无需翻译");
                pushTranslations();
                return;
            }
            Log.i("PetOverlayService", "translate: 需要翻译 " + todo.size() + " / " + all.size()
                    + (manual ? "（手动触发）" : ""));

            /* ★ 标记为"翻译中"，界面会据此显示标记 */
            translating = true;
            translatingNames = new java.util.LinkedHashSet<>(todo);

            new Thread(() -> {
                try {
                    java.util.Map<String, String> got =
                            me.rerere.pet.data.TranslationService.translate(store, cfg, todo);
                    if (!got.isEmpty()) {
                        store.mergeTranslations(got);     // ★ 只增不改
                    }
                    Log.i("PetOverlayService", "translate: 本次得到 " + got.size() + " 条");
                    pushTranslations();                   // 刷 UI + 推给 JS
                } catch (Throwable t) {
                    Log.w("PetOverlayService", "translate failed", t);
                } finally {
                    /* ★ 结束（成功或失败）都要清掉标记 ——
                       成功的那些已有译文，界面自然显示译文；
                       失败的回到原名，不再一直挂着"翻译中"。 */
                    translating = false;
                    translatingNames = java.util.Collections.emptySet();
                }
            }, "pet-translate").start();
        } catch (Throwable t) {
            translating = false;
            translatingNames = java.util.Collections.emptySet();
            Log.w("PetOverlayService", "translateNow failed", t);
        }
    }

    /** 当前是否正在翻译。 */
    public boolean isTranslating() { return translating; }

    /** 正在翻译的名称集合。 */
    public java.util.Set<String> translatingNames() { return translatingNames; }

    /** 把译文推给 MMD 页面（用于表情下拉的显示），并刷新配件页。 */
    private void pushTranslations() {
        try {
            final java.util.Map<String, String> map = store.translations();
            if (mmdView != null) {
                mmdView.setNameTranslations(new org.json.JSONObject(map).toString());
            }
        } catch (Throwable t) {
            Log.w("PetOverlayService", "pushTranslations failed", t);
        }
    }

    /**
     * 查询物理刚体/关节数量（用于提示关掉物理能省多少）。
     */
    public void queryPhysInfo(java.util.function.Consumer<String> cb) {
        if (mmdView != null) {
            mmdView.queryPhysInfo(cb);
        } else if (cb != null) {
            cb.accept("{}");
        }
    }

    /** 设置/清除分区辅助线（供设置页滑块拖动时调用）。 */
    public void setGuideLines(float headBoundary, float bodyBoundary) {
        // 两种渲染模式都要能显示辅助线
        if (headBoundary < 0f && bodyBoundary < 0f) {
            if (spriteView != null) spriteView.clearGuideLines();
            if (mmdGuides != null) mmdGuides.clear();
            return;
        }
        if (spriteView != null) {
            spriteView.setGuideLines(headBoundary, bodyBoundary);
        }
        if (mmdGuides != null) {
            mmdGuides.setLines(headBoundary, bodyBoundary);
        }
    }

    public void playAction(PetContracts.PetAction action, boolean loop) {
        if (spriteView != null) spriteView.setAction(action, loop);
    }

    public void onDialogueCompleted(PetContracts.VisualHint hint) {
        playAction(PetContracts.visualHintToAction(hint), false);
        handler.removeCallbacks(revertActionRunnable);
        handler.postDelayed(revertActionRunnable, FEEDBACK_DURATION_MS);
        /* 对话结束 → 恢复漫游 */
        handler.postDelayed(this::restartRoam, FEEDBACK_DURATION_MS + 500L);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        handler.removeCallbacks(idleRandomRunnable);
        handler.removeCallbacks(vmdRandomRunnable);
        stopRoam();
        if (mcpManager != null) mcpManager.stopPolling();
        if (speechBubble != null) speechBubble.hide();
        if (mmdView != null) mmdView.destroy();
        mmdGuides = null;
        if (current == this) current = null;
        if (spriteView != null) spriteView.pause();
        if (atlas != null) atlas.close();
        if (rootView != null) {
            try { windowManager.removeView(rootView); } catch (Exception ignored) {}
            rootView = null;
        }
        super.onDestroy();
    }
}
