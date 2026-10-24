package me.rerere.pet.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;

import me.rerere.pet.data.MmdModelManager;

/**
 * MMD 桌宠视图：透明 WebView 渲染 PMX 模型。
 *
 * 关键实现（移植自 three.js 验证工程）：
 *  1) WebViewAssetLoader —— 不用 file:///android_asset/，
 *     把 assets 挂到 https://appassets.androidplatform.net/，
 *     得到真正的 https origin，ES module / wasm 不会被跨域拦。
 *  2) 三层透明：DOM(transparent) + WebView(setBackgroundColor) + 硬件层
 *  3) 必须 LAYER_TYPE_HARDWARE，否则软件合成 → 全黑/全白
 */
public final class MmdPetView {

    private static final String TAG = "PetMmd";
    /** WebViewAssetLoader 提供的虚拟域名。 */
    private static final String ASSET_HOST = "https://appassets.androidplatform.net";
    static final String ENTRY_URL = ASSET_HOST + "/assets/index.html";

    /**
     * GPU 加速（高性能 WebGL 上下文）。
     *
     * ★ 必须走 URL 参数、而不是运行时下发：
     *   WebGL 的 antialias / powerPreference 只能在【创建上下文时】决定，
     *   运行中改不了。所以切换设置时由原生重载一次页面。
     */
    private boolean gpuAccel = true;

    /** 设置 GPU 加速（必须在 create() 之前调用才会生效于本次加载）。 */
    public void setGpuAccel(boolean on) { this.gpuAccel = on; }

    /** 实际加载的入口 URL（带上 GPU 参数）。 */
    private String entryUrl() {
        return ENTRY_URL + "?gpu=" + (gpuAccel ? "1" : "0");
    }

    /** 模型就绪 / 日志回调。 */
    public interface Listener {
        void onReady();
        void onLog(String message);
        void onError(String message);
        /** 轮廓掩码首次就绪（可用于按内容裁剪窗口）。 */
        default void onMaskReady() {}
        /** 模型切换/加载完成（用于重新按内容裁剪窗口）。 */
        default void onModelChanged() {}
        /**
         * 饱和度/色相变化（由页面里的滑块触发）。
         * ★ 存在的意义：设置页与桌宠悬浮窗是【两个独立 WebView】，
         *   在设置页调饱和度不会影响桌宠，必须靠原生转发。
         */
        default void onColorGradeChanged(float sat, float hue) {}
        /**
         * 模型的真实宽高比（由画面轮廓掩码量出来的）。
         * ★ 原生只在【锁定取景】时用它把窗口形状调成与模型一致，
         *   以消除横向留白；未锁定时忽略。
         */
        default void onContentAspect(float aspect) {}
        /** 面板里「锁定缩放与相机距离」的状态变化（原生据此决定是否自动适配窗口）。 */
        default void onViewLockChanged(boolean locked) {}
        /**
         * 单个外观参数变化（曝光/光照/镜面/toon…）。
         * ★ 用于把设置页的调整【实时同步到桌宠】——
         *   两个 WebView 各有一份模型，不同步的话用户会觉得"调了没反应"。
         */
        default void onAppearanceChanged(String key, String value) {}
        /**
         * 走动期间请求放宽帧率上限。
         * ★ 走路动画是 30fps 录的；帧率上限设成 20 时渲染会跳帧，
         *   表现就是"走路一卡一卡的"（2026-10-06）。
         */
        default void onWalkFpsRequest(boolean walking) {}
        /**
         * glTF（.glb）模型【自带的动作】清单（JSON 数组的字符串）。
         * ★ GLB 与 MMD 的动作来源不同：MMD 用导入的 VMD 文件，
         *   GLB 用它内嵌的 AnimationClip。这里上报给原生，供动作库展示。
         */
        default void onGlbClips(String json) {}
        /**
         * 模型可用的表情清单（JSON 数组字符串），**页面主动上报**。
         * ★ 与 queryExpressions 的区别：那个是原生"问"，这个是页面"报"。
         *   GLB 的表情要等模型加载完才知道，而原生问的时机更早（页面 ready）
         *   → 实测永远拿到空。所以必须由页面在本就知道时主动报一次。
         */
        default void onExpressions(String json) {}
    }

    private final Context context;
    private final MmdModelManager models;
    private WebView webView;
    private Listener listener;
    private boolean pageLoaded = false;
    /** 模型在视图内的归一化包围盒（0..1），由 JS 上报。 */
    private volatile float bMinX = 0.5f, bMinY = 0.5f, bMaxX = 0.5f, bMaxY = 0.5f;
    private volatile boolean hasBounds = false;
    private long lastBoundsLogAt = 0L;
    /** 轮廓命中掩码：每像素 1 bit，top-down 行序。 */
    private volatile byte[] hitMask = null;
    private volatile int hitMaskN = 0;
    /**
     * 当前【没有任何模型】。
     *
     * ★ 为什么要单独一个标志（不能用 hasBounds 反推）：
     *   hasBounds=false 有两种情况：
     *     ① 模型正在加载（1~2 秒内就会有）→ 应该放行触摸，否则用户以为卡死；
     *     ② 压根没有模型（用户还没导入）→ 必须【不】消费触摸，
     *        否则那块 40% 屏高的透明窗口会把桌面点击全吃掉。
     *   这两种必须区分，所以由 Service 显式告知。
     */
    private volatile boolean modelMissing = false;
    private long lastMaskLogAt = 0L;
    /** 窗口裁剪信息：渲染区内对应内容区的归一化上下界（-1 表示未裁剪）。 */
    private volatile float contentCropTop = -1f;
    private volatile float contentCropBottom = -1f;
    private volatile float contentCropLeft = -1f;
    private volatile float contentCropRight = -1f;
    /** 页面加载完成后待执行的 JS 队列（单条会互相覆盖，必须用队列）。 */
    private final java.util.List<String> pendingJs = new java.util.ArrayList<>();

    public MmdPetView(Context context, MmdModelManager models) {
        /**
         * ★ 必须保留原始 Context，不能换成 applicationContext。
         *
         * WebView 里的 <select> 下拉依赖宿主的 window token；
         * 用 applicationContext 创建时，SelectPopup.show() 会失败
         * （logcat: org.chromium.content.browser.input.SelectPopup.show + WindowManager 报错），
         * 表现就是"下拉框点不开"。
         *
         * 悬浮窗场景传 Service（本身就是 Context，无 window token 需求的问题，
         * 因为那种场景下不显示面板）；设置页场景传 Activity。
         */
        this.context = context;
        this.models = models;
    }

    public void setListener(Listener l) { this.listener = l; }

    public WebView view() { return webView; }

    @SuppressLint("SetJavaScriptEnabled")
    public WebView create() {
        webView = new WebView(context);

        // ① WebView 层透明
        webView.setBackgroundColor(Color.TRANSPARENT);
        // ② 硬件层（必须，否则拿不到正常 GL 上下文）
        /* ★ 这里【永远】用 HARDWARE，不要跟随 GPU 加速开关！
           ---- 事故复盘（2026-10-05）----
           我曾写成 `gpuAccel ? HARDWARE : NONE`，想着"关掉加速更省电"。
           但原始注释就写着"必须 LAYER_TYPE_HARDWARE，否则软件合成 → 全黑/全白"，
           而且【设置页从不调 setGpuAccel（默认 true），悬浮窗会读用户设置】——
           结果一旦关掉 GPU 加速，悬浮窗立刻变透明/白屏，而设置页还正常，
           表现为"只有 MMD 设置里能看见模型"。
           ★ GPU 加速只应该影响 WebGL 上下文（走 URL 参数），不该动 View 层。 */
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage 持久化要用
        // 资源全部走 WebViewAssetLoader，file/content 通路一律关闭
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 注：故意不设 setAllowFileAccessFromFileURLs / setAllowUniversalAccessFromFileURLs，
        //     用 WebViewAssetLoader 就不需要它们，开了反而扩大攻击面。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);   // 否则夜间模式压暗画面
        }

        webView.addJavascriptInterface(new JsBridge(), "MmdBridge");
        installAssetLoader();
        startWatchdog();          // ★ 原生看门狗（不受 WebView 暂停影响）
        return webView;
    }

    /** WebViewAssetLoader：挂载 assets 与导入的模型目录。 */
    private void installAssetLoader() {
        WebViewAssetLoader.Builder b = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(context));
        try {
            File root = models.internalModelsRoot();
            if (root != null) {
                b.addPathHandler("/models/",
                        new WebViewAssetLoader.InternalStoragePathHandler(context, root));
            }
        } catch (Throwable t) {
            Log.w(TAG, "InternalStoragePathHandler unavailable", t);
        }
        /* ★ VMD 动作目录：和 /models/ 同理，用 InternalStoragePathHandler 暴露。
           导入的动作（含子目录）都挂在 /vmd/ 下，页面直接 loadAnimation('/vmd/xx.vmd')。 */
        try {
            File vroot = new me.rerere.pet.data.VmdManager(context).root();
            if (vroot != null) {
                b.addPathHandler("/vmd/",
                        new WebViewAssetLoader.InternalStoragePathHandler(context, vroot));
            }
        } catch (Throwable t) {
            Log.w(TAG, "VMD path handler unavailable", t);
        }
        final WebViewAssetLoader loader = b.build();

        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                        WebResourceRequest request) {
                WebResourceResponse r = loader.shouldInterceptRequest(request.getUrl());
                if (r == null) {
                    // 404 第一现场：页面全白且无任何 JS 日志时看这里
                    Log.w(TAG, "404(intercept) " + request.getUrl());
                }
                return r;
            }

            /**
             * ★★ 渲染进程崩溃处理 —— 这是"悬浮窗透明、只有设置页能看见"的关键。
             *
             * ---- 为什么必须实现（2026-10-05）----
             * 这个重模型（8.5MB / 152 个顶点 morph / 70378 顶点）的 morph 贴图
             * 约 128MB，很容易触发渲染进程被系统回收。
             * 如果不实现 onRenderProcessGone，渲染进程一死【系统会连整个 App 一起杀掉】
             * （或让 WebView 变成不渲染的死块 → 画面全透明）。
             * 悬浮窗跑在后台 Service 里、优先级低，最容易中招；
             * 而设置页在前台，所以出现"只有 MMD 设置里能看见"的现象。
             * ★ 返回 true = 我们自己处理，不要杀 App；然后重新加载页面即可恢复。
             */
            @Override public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                Log.e(TAG, "RENDERER GONE didCrash=" + (detail == null ? "?" : detail.didCrash())
                        + " priorityAtExit=" + (detail == null ? "?" : detail.rendererPriorityAtExit()));
                try {
                    jsReady = false;
                    pageLoaded = false;
                    /* 渲染进程会被系统重启，重新加载页面即可恢复画面 */
                    view.loadUrl(entryUrl());
                } catch (Throwable t) {
                    Log.w(TAG, "reload after renderer gone failed", t);
                }
                return true;      // ★ 关键：不要让系统因为渲染进程崩溃而杀掉 App
            }

            @Override public void onPageFinished(WebView view, String url) {                Log.i(TAG, "page finished: " + url);
                /**
                 * ★ 不能在这里立刻执行 __loadModel。
                 * 页面 onload 后 boot() 仍在【异步】加载默认模型，
                 * 此时切入的模型会被 boot 的后续逻辑覆盖掉
                 * （表现：导入成功但模型没变）。
                 * 这里轮询等待页面真正就绪（MmdBridge.ready 已被调用）。
                 */
                pageLoaded = true;
                waitForPageReadyThenFlush(0);
            }
        });

        webView.loadUrl(entryUrl());
    }

    /** 执行 JS（页面未就绪时暂存，避免丢失）。 */
    public void eval(final String js) {
        if (webView == null || js == null) return;
        synchronized (pendingJs) {
            if (!pageLoaded) {
                pendingJs.add(js);
                return;
            }
        }
        postEval(js);
    }

    private void postEval(final String js) {
        webView.post(new Runnable() {
            @Override public void run() {
                try {
                    Log.i(TAG, "eval: " + (js.length() > 80 ? js.substring(0, 80) + "…" : js));
                    webView.evaluateJavascript(js, null);
                } catch (Throwable t) { Log.w(TAG, "eval failed", t); }
            }
        });
    }

    /** 页面 ready 标志（由 JS 的 MmdBridge.ready() 置位）。 */
    private volatile boolean jsReady = false;
    private int readyPollCount = 0;
    /** 模型真实宽高比（由轮廓掩码量出，供锁定取景时适配窗口形状）。 */
    private volatile float contentAspect = 0f;

    private void waitForPageReadyThenFlush(int attempt) {
        if (webView == null) return;
        if (jsReady) {
            Log.i(TAG, "page js ready, flushing " + pendingJs.size() + " pending");
            flushPending();
            return;
        }
        if (attempt > 40) {   // 最多等 ~10 秒
            Log.w(TAG, "page js not ready after timeout, flushing anyway");
            flushPending();
            return;
        }
        webView.postDelayed(() -> {
            // 主动查询页面是否已渲染完成
            evalState("(function(){try{return (window.__modelBounds && window.__modelBounds()) ? 'y' : 'n';}"
                    + "catch(e){return 'n';}})()", v -> {
                if (v != null && v.contains("y")) {
                    jsReady = true;
                    waitForPageReadyThenFlush(0);
                } else {
                    waitForPageReadyThenFlush(attempt + 1);
                }
            });
        }, 250);
    }

    private void flushPending() {
        java.util.List<String> queued;
        synchronized (pendingJs) {
            queued = new java.util.ArrayList<>(pendingJs);
            pendingJs.clear();
        }
        Log.i(TAG, "flushPending: " + queued.size() + " item(s)");
        for (String js : queued) postEval(js);
    }

    /** 执行 JS 并取回字符串结果（用于诊断/回读状态）。 */
    public void evalState(final String js, final java.util.function.Consumer<String> onResult) {
        if (webView == null || js == null) return;
        webView.post(new Runnable() {
            @Override public void run() {
                try {
                    webView.evaluateJavascript(js, value -> {
                        if (onResult != null) onResult.accept(value);
                    });
                } catch (Throwable t) {
                    if (onResult != null) onResult.accept("\"err\"");
                }
            }
        });
    }

    /** 查询当前模型是否有眼球骨骼（决定眼动功能是否可用）。 */
    public void queryEyeBones(java.util.function.Consumer<Boolean> cb) {
        evalState("(window.__hasEyeBones && window.__hasEyeBones()) ? 'y' : 'n'", v -> {
            if (cb != null) cb.accept(v != null && v.contains("y"));
        });
    }

    /** 查询当前模型的表情名列表（JSON 数组字符串）。 */
    public void queryExpressions(java.util.function.Consumer<String> cb) {
        evalState("window.__listExpressions ? window.__listExpressions() : '[]'", cb);
    }

    /** 手指按下/抬起：控制视线是"跟手"还是"回正"。 */
    public void setLookHeld(boolean held) {
        eval("window.__setLookHeld && window.__setLookHeld(" + held + ")");
    }

    /** 喂入视线坐标（全屏归一化 0..1），用于全屏眼动追踪。 */
    public void setLook(float nx, float ny) {
        eval("window.setLook && window.setLook(" + nx + "," + ny + ")");
    }

    /** 播放表情（由 AI/MCP 触发）。 */
    public void playExpression(String name, float seconds) {
        eval("window.__playExpression && window.__playExpression("
                + jsQuote(name) + "," + seconds + ")");
    }

    /** 开启/关闭抗锯齿（分辨率超采样）。 */
    public void setAntiAliasLevel(int level) {
        eval("window.__setAntiAliasLevel && window.__setAntiAliasLevel(" + level + ")");
    }

    /**
     * 下发性能设置（运行时生效，无需重载）。
     * @param scale  渲染倍率 0.35~1.0
     * @param auto   动态分辨率
     * @param fpsCap 帧率上限（0/30/20）
     */
    /**
     * ★ 下发动态模糊设置（JSON：{"mmd":{on,len,qual},"glb":{on,len,qual}}）。
     *
     * ---- 两个模型各一套 ----
     * MMD 与 glTF 的尺寸/动作速度/件数都不同，合适的拖影长度与质量也不同，
     * 所以页面会按【当前模型类型】挑一套（见 JS 的 applyMotionBlurForModel）。
     */
    public void setMotionBlur(final String json) {
        eval("window.__setMotionBlur && window.__setMotionBlur(" + jsQuote(json) + ")");
    }

    public void setPerf(float scale, boolean auto, int fpsCap) {
        eval("window.__setPerf && window.__setPerf("
                + scale + "," + auto + "," + fpsCap + ")");
    }

    /**
     * 重新加载页面（切换 GPU 加速后必须重载才生效）。
     * 重载后 JS 会从 localStorage 恢复设置，所以不会丢配置。
     */
    public void reloadForGpuAccel() {
        if (webView == null) return;
        jsReady = false;
        pageLoaded = false;
        Log.i(TAG, "reload for gpuAccel=" + gpuAccel + " -> " + entryUrl());
        try {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            webView.loadUrl(entryUrl());
        } catch (Throwable t) {
            Log.w(TAG, "reload failed", t);
        }
    }

    /** 开关某个配件。 */
    public void setAccessory(String name, boolean on) {
        eval("window.__setAccessory && window.__setAccessory("
                + jsQuote(name) + "," + on + ")");
    }

    /** 批量设置配件（启动时恢复持久化状态用）。 */
    public void setAccessories(String jsonArray) {
        eval("window.__setAccessories && window.__setAccessories("
                + jsQuote(jsonArray == null ? "[]" : jsonArray) + ")");
    }

    /** 读取当前配件列表 + 开关状态（JSON: [{name,on}]）。 */
    public void queryAccessories(java.util.function.Consumer<String> cb) {
        evalState("window.__listAccessories ? window.__listAccessories() : '[]'", cb);
    }

    /** 读取"已启用配件"名单（供持久化）。 */
    public void queryActiveAccessories(java.util.function.Consumer<String> cb) {
        evalState("window.__activeAccessories ? window.__activeAccessories() : '[]'", cb);
    }

    /** 设置饱和度/色相（跨页面同步用）。 */
    public void setColorGrade(float sat, float hue) {
        eval("window.__setColorGrade && window.__setColorGrade(" + sat + "," + hue + ")");
    }

    /** 下发名称译文表（名称 -> 中文），只用于界面显示。 */
    public void setNameTranslations(String json) {
        eval("window.__setNameTranslations && window.__setNameTranslations("
                + jsQuote(json == null ? "{}" : json) + ")");
    }

    /** 下发"锁定取景"：禁用面板里的缩放/相机距离滑块。 */
    public void setViewLock(boolean on) {
        eval("window.__setViewLock && window.__setViewLock(" + on + ")");
    }

    /** 应用单个外观参数（跨页面同步用）。 */
    public void applyAppearanceKV(String key, String value) {
        eval("window.__applyAppearanceKV && window.__applyAppearanceKV("
                + jsQuote(key) + "," + jsQuote(value) + ")");
    }

    /** 强制把页面里的设置写进 localStorage（"完成"前调用）。 */
    public void flushSettings() {
        eval("window.__flushSettings && window.__flushSettings()");
    }

    // ---------------------------------------------------------------- VMD 动画

    /** 下发 VMD 配置（插槽绑定 / 根骨骼锁定 / 性能无关的播放参数）。 */
    public void setVmdConfig(String json) {
        eval("window.__setVmdConfig && window.__setVmdConfig("
                + jsQuote(json == null ? "{}" : json) + ")");
    }

    /** 播某个插槽：idle / walk / touchHead / touchBody / touchFeet / greet。 */
    public void playVmdSlot(String slot) {
        eval("window.__playVmdSlot && window.__playVmdSlot(" + jsQuote(slot) + ")");
    }

    /**
     * ★ 拖动结束：让"被拎起来"的动画收尾、淡出回待机（2026-10-07）。
     *
     * drag 槽用的是一次性动作 + clampWhenFinished —— 拖拽期间模型会一直保持
     * "被拿起"的末帧姿态（这正是想要的手感），但松手后必须主动收尾，
     * 否则模型会永远悬着。JS 侧 __vmdDragEnd() 会判断"是不是真的在播 drag"，
     * 没在播就是无害的空操作。
     */
    public void dragEnd() {
        eval("window.__vmdDragEnd && window.__vmdDragEnd()");
    }

    /**
     * ★ 试播一个 glTF 自带动作（按名字，一次性）。
     *
     * 这个 GLB 里的 morph target 全是【嘴型】，没有任何脸部表情 ——
     * 所以 glTF 模型下「表情」对应的是**自带动画**，试播也走动画。
     */
    public void playGlbClip(final String name) {
        eval("window.__playGlbClip && window.__playGlbClip(" + jsQuote(name) + ")");
    }

    /** 随机待机表演（从页面已经收到的 randomPool 里抽一个）。 */
    public void playVmdRandom() {
        eval("window.__playVmdRandom && window.__playVmdRandom()");
    }

    /** 漫游状态：走路时切走路动作；dir = +1 往右 / -1 往左 / 0 不转身。
     *  ★ 侧身 90°，不是 180° 掉头。 */
    public void setVmdWalking(boolean walking, int dir) {
        eval("window.__setVmdWalking && window.__setVmdWalking("
                + walking + "," + dir + ")");
    }

    /** 查询 VMD 加载情况（诊断）。 */
    public void queryVmdInfo(java.util.function.Consumer<String> cb) {
        evalState("window.__vmdInfo ? window.__vmdInfo() : '{}'", cb);
    }

    /** 读取渲染信息（GPU 名字 / 分辨率 / draw call 等）。 */
    public void queryRenderDetail(java.util.function.Consumer<String> cb) {
        evalState("window.__renderInfo ? window.__renderInfo() : '{}'", cb);
    }

    /** 开关物理（关掉可显著降低 CPU 开销）。 */
    public void setPhysics(boolean on) {
        eval("window.__setPhysics && window.__setPhysics(" + on + ")");
    }

    /** 查询物理刚体数量（用于提示关掉能省多少）。 */
    public void queryPhysInfo(java.util.function.Consumer<String> cb) {
        evalState("window.__physInfo ? window.__physInfo() : '{}'", cb);
    }

    /** 读取渲染信息（诊断用）。 */
    public void queryRenderInfo(java.util.function.Consumer<String> cb) {
        evalState("window.__renderInfo ? window.__renderInfo() : '{}'", cb);
    }

    /** 加载指定模型（webUrl 形如 /models/xxx/a.pmx）。 */
    public void loadModel(String webUrl) {
        if (webUrl == null || webUrl.isEmpty()) return;
        Log.i(TAG, "loadModel request: " + webUrl + " pageLoaded=" + pageLoaded
                + " jsReady=" + jsReady);
        eval("window.__loadModel(" + jsQuote(webUrl) + ")");
        // 模型加载是异步的，稍后通知（Service 会重新按内容收紧窗口）
        if (listener != null && webView != null) {
            webView.postDelayed(() -> {
                if (listener != null) listener.onModelChanged();
            }, 2500);
        }
    }

    /** 全屏捕获模式：窗口覆盖全屏，但只有模型区域消费触摸。 */
    private boolean fullscreenCapture = false;

    public void setFullscreenCapture(boolean on) {
        this.fullscreenCapture = on;
    }

    public boolean isFullscreenCapture() { return fullscreenCapture; }

    /**
     * 命中测试：判断视图内某点是否落在模型上。
     * @param x 视图内 x（像素）
     * @param y 视图内 y（像素）
     * @param w 视图宽（像素）
     * @param h 视图高（像素）
     */
    public boolean hitTest(float x, float y, float w, float h) {
        /* 没有任何模型 → 不消费触摸（否则透明大窗会把桌面点击全吃掉） */
        if (modelMissing) return false;
        if (w <= 0f || h <= 0f) return false;

        float nx = x / w;
        float ny = y / h;
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return false;
        // 若窗口已按内容裁剪，先换算回渲染区的归一化坐标
        if (contentCropTop >= 0f && contentCropBottom > contentCropTop) {
            ny = contentCropTop + ny * (contentCropBottom - contentCropTop);
        }
        if (contentCropLeft >= 0f && contentCropRight > contentCropLeft) {
            nx = contentCropLeft + nx * (contentCropRight - contentCropLeft);
        }

        // 优先用轮廓掩码（精确到人形轮廓，四角空白不会误判）
        byte[] mask = hitMask;
        int n = hitMaskN;
        if (mask != null && n > 0) {
            int gx = (int) (nx * n);
            int gy = (int) (ny * n);
            gx = Math.max(0, Math.min(n - 1, gx));
            gy = Math.max(0, Math.min(n - 1, gy));
            // 邻域容差：手指较粗，周围 1 格有模型也算命中
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int px = gx + dx, py = gy + dy;
                    if (px < 0 || py < 0 || px >= n || py >= n) continue;
                    int bit = py * n + px;
                    int idx = bit >> 3;
                    if (idx >= mask.length) continue;
                    if ((mask[idx] & (1 << (bit & 7))) != 0) return true;
                }
            }
            return false;
        }

        // 掩码未就绪时退回矩形包围盒
        if (!hasBounds) return true;   // 什么都还没收到 -> 放行，避免完全点不动
        float pad = 0.02f;
        return nx >= bMinX - pad && nx <= bMaxX + pad
                && ny >= bMinY - pad && ny <= bMaxY + pad;
    }

    /** 设置窗口裁剪映射（由 Service 按掩码内容裁窗口时调用）。 */
    public void setContentCrop(float top, float bottom) {
        this.contentCropTop = top;
        this.contentCropBottom = bottom;
    }

    /**
     * 告知"当前有没有模型"。
     *
     * ★ 为什么需要（公开仓库版没有内置模型）：
     *   没有模型时 hasBounds 永远是 false，而原来的兜底是"放行触摸"。
     *   那块 40% 屏高的透明窗会把桌面点击全吃掉 —— 用户表现为
     *   "装了 App 之后屏幕中间点不动了"。所以必须显式区分
     *   「模型正在加载」和「压根没有模型」。
     */
    public void setModelMissing(boolean missing) {
        this.modelMissing = missing;
        if (missing) {
            /* 顺带清掉旧模型的命中数据，避免上一个模型的轮廓还在生效 */
            hitMask = null;
            hitMaskN = 0;
            hasBounds = false;
        }
    }

    /** 设置横向裁剪映射（窗口宽度也按内容收紧时使用）。 */
    public void setContentCropX(float left, float right) {
        this.contentCropLeft = left;
        this.contentCropRight = right;
    }

    /**
     * 掩码内容的归一化范围 {top, bottom, left, right}，用于按实际内容裁窗口。
     * 返回 null 表示未就绪。
     */
    public float[] contentRange() {
        byte[] mask = hitMask;
        int n = hitMaskN;
        if (mask == null || n <= 0) return null;
        int firstRow = -1, lastRow = -1, firstCol = -1, lastCol = -1;
        for (int py = 0; py < n; py++) {
            for (int px = 0; px < n; px++) {
                int bit = py * n + px;
                int idx = bit >> 3;
                if (idx >= mask.length) continue;
                if ((mask[idx] & (1 << (bit & 7))) == 0) continue;
                if (firstRow < 0) firstRow = py;
                lastRow = py;
                if (firstCol < 0 || px < firstCol) firstCol = px;
                if (px > lastCol) lastCol = px;
            }
        }
        if (firstRow < 0 || lastRow < firstRow || firstCol < 0) return null;
        return new float[]{
                (float) firstRow / n, (float) (lastRow + 1) / n,
                (float) firstCol / n, (float) (lastCol + 1) / n
        };
    }

    /** 兼容旧调用：只取行范围。 */
    public float[] contentRowRange() {
        float[] r = contentRange();
        return r == null ? null : new float[]{ r[0], r[1] };
    }

    /** 轮廓掩码是否已就绪。 */
    public boolean hasMask() { return hitMask != null && hitMaskN > 0; }

    /** 包围盒是否已就绪（调试用）。 */
    public boolean hasBounds() { return hasBounds; }

    /** 包围盒归一化高度中心，用于头/身/脚分区。 */
    public float normalizedYFor(float viewY, float viewH) {
        if (viewH <= 0f) return 0.5f;
        float ny = viewY / viewH;
        // 窗口已裁剪时先映射回渲染区
        if (contentCropTop >= 0f && contentCropBottom > contentCropTop) {
            ny = contentCropTop + ny * (contentCropBottom - contentCropTop);
        }
        // 用掩码内容范围归一化，头身脚分区才贴合模型
        float[] range = contentRowRange();
        if (range != null) {
            float span = range[1] - range[0];
            if (span > 0.001f) {
                return Math.max(0f, Math.min(1f, (ny - range[0]) / span));
            }
        }
        if (!hasBounds) return ny;
        float span = bMaxY - bMinY;
        if (span <= 0.001f) return 0.5f;
        return Math.max(0f, Math.min(1f, (ny - bMinY) / span));
    }

    /** 滚动/拖拽穿透：MMD 模式下桌宠拖动由外层容器处理。 */
    public void setTouchable(boolean touchable) {
        if (webView == null) return;
        webView.setClickable(touchable);
        webView.setFocusable(touchable);
        webView.setFocusableInTouchMode(touchable);
    }

    public void onResume() { if (webView != null) { webView.onResume(); webView.resumeTimers(); } }

    /* ==================================================================
       ★★★ 原生渲染看门狗（2026-10-08）★★★
       ----------------------------------------------------------------
       ---- 为什么必须有它（曾出现"模型被冻结了"）----
       现象：日志整段静止、心跳也消失、进程 CPU 0% / sleeping、无 ANR、无异常
             → **WebView 被暂停**（不是死循环）。
       结构性缺陷：JS 侧的看门狗是 `setInterval` —— WebView 一被暂停，
             它自己也不跑了，**根本救不了自己**。
       → 所以看门狗必须跑在 **Android 主线程**（不受 WebView 暂停影响）。

       做法：每 3.5 秒回读一次页面的 `_frameCount`：
         · 1.5 秒内没有回调  → WebView 卡/被暂停 → 强制 onResume + resumeTimers
         · 有回调但帧数没变  → 渲染循环停了 → 同上 + 让页面重启 setAnimationLoop
       救活后打日志，连续 3 次救不动就再加重载（保底）。
       ================================================================== */
    private final android.os.Handler wdHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private long wdLastFrames = -1;
    private int wdStuck = 0;
    private boolean wdRunning = false;

    /* ★★★ 重载预算（2026-10-17 整机白屏事故）★★★
       ---- 事故复盘 ----
       场景：`pm install -r` 替换了**正在运行**进程的 base.apk。
       之后 WebView 再也取不到 assets（旧 APK 的路径已经失效）→ 页面永远
       `pageLoaded=false jsReady=false` → 上面这段看门狗判"卡住" → **无限重载**。
       每次重载都会新建一个 WebView 页面 + 一个 WebGL 上下文，
       几十秒后就把整机的 GPU / 渲染资源吃光 → **所有 App 打开都是白屏**，
       只能重启手机才恢复（用户实测）。
       ---- 修法 ----
       ① **重载总量预算**：整个进程生命周期最多 3 次，用完就彻底放弃
          （宁可桌宠不显示，也不能把系统拖白屏）；
       ② **重载退避**：第 1/2/3 次分别等 15s / 45s / 120s，
          不再每 3.5 秒撞一次；
       ③ 预算用尽后**停掉看门狗**，只留一行日志，不再做任何自愈动作。
       ★ 这是"自愈机制必须自带熔断"的通用教训：
         任何自动重试都要有上限 + 退避，否则故障会被放大成灾难。 */
    private static final int WD_MAX_RELOAD = 3;
    private static final long[] WD_RELOAD_BACKOFF_MS = { 15000L, 45000L, 120000L };
    private int wdReloads = 0;
    private long wdNextReloadAt = 0L;
    private boolean wdGaveUp = false;

    private final Runnable wdTick = new Runnable() {
        @Override public void run() {
            if (!wdRunning || webView == null) return;
            final long[] frames = new long[]{-1};
            final boolean[] replied = new boolean[]{false};
            try {
                webView.evaluateJavascript(
                        "(window.__getFrameCount ? window.__getFrameCount() : -1)", v -> {
                    replied[0] = true;
                    try { frames[0] = Long.parseLong(v == null ? "-1" : v.trim()); }
                    catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
            wdHandler.postDelayed(() -> {
                if (!wdRunning) return;
                boolean bad = false;
                String why = null;
                if (!replied[0]) {
                    bad = true; why = "查询无响应（WebView 被暂停？）";
                } else if (frames[0] >= 0 && frames[0] == wdLastFrames) {
                    bad = true; why = "帧计数停滞 @ " + frames[0];
                }
                if (bad) {
                    wdStuck++;
                    Log.w(TAG, "[wd] " + why + " → 第 " + wdStuck + " 次强制唤醒");
                    try { webView.onResume(); webView.resumeTimers(); } catch (Throwable ignored) {}
                    try { eval("window.__forceResumeRender && window.__forceResumeRender()"); }
                    catch (Throwable ignored) {}
                    if (wdStuck >= 3 && !wdGaveUp) {
                        long nowMs = android.os.SystemClock.elapsedRealtime();
                        if (nowMs < wdNextReloadAt) {
                            /* 还在退避窗口里：这一次不重载，只继续软唤醒 */
                            Log.w(TAG, "[wd] 重载退避中，还剩 "
                                    + ((wdNextReloadAt - nowMs) / 1000) + "s");
                        } else if (wdReloads >= WD_MAX_RELOAD) {
                            /* ★ 预算用尽：彻底停手，避免把系统拖白屏 */
                            wdGaveUp = true;
                            Log.e(TAG, "[wd] 已重载 " + wdReloads + " 次仍未恢复 → **放弃自愈**"
                                    + "（防止无限重载耗尽 GPU；请检查 APK 是否完整 / 手动重启 App）");
                            stopWatchdog();
                            return;
                        } else {
                            wdReloads++;
                            wdStuck = 0;
                            wdNextReloadAt = nowMs + WD_RELOAD_BACKOFF_MS[
                                    Math.min(wdReloads - 1, WD_RELOAD_BACKOFF_MS.length - 1)];
                            Log.w(TAG, "[wd] 连续 3 次未恢复 → 重载页面（第 " + wdReloads
                                    + "/" + WD_MAX_RELOAD + " 次，下次最早 "
                                    + (WD_RELOAD_BACKOFF_MS[Math.min(wdReloads - 1,
                                        WD_RELOAD_BACKOFF_MS.length - 1)] / 1000) + "s 后）");
                            try { reloadForGpuAccel(); } catch (Throwable ignored) {}
                        }
                    }
                } else if (wdStuck > 0) {
                    Log.i(TAG, "[wd] 已恢复（frames=" + frames[0] + "）");
                    wdStuck = 0;
                }
                wdLastFrames = frames[0];
            }, 1500);
            wdHandler.postDelayed(this, 3500);
        }
    };

    private void startWatchdog() {
        if (wdRunning) return;
        /* ★ 放弃自愈后不再重启看门狗（否则相当于把熔断又合上了） */
        if (wdGaveUp) { Log.w(TAG, "[wd] 已放弃自愈，不再启动看门狗"); return; }
        wdRunning = true;
        wdStuck = 0;
        wdLastFrames = -1;
        wdHandler.postDelayed(wdTick, 6000);       // 等页面稳定再开始盯
        Log.i(TAG, "[wd] 原生渲染看门狗已启动");
    }

    private void stopWatchdog() {
        wdRunning = false;
        try { wdHandler.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
    }

    public void onPause() { if (webView != null) webView.onPause(); }

    /**
     * ★ 彻底销毁（进设置页重建模型时用）。
     * 注意：onPause/onResume 是成对的；本 App 有【两个 WebView】
     * （桌宠悬浮窗 + MMD 参数调整页），Chromium 的可见性处理在两者并存时
     * 并不总是对称恢复 —— 所以 JS 侧还加了"渲染看门狗"负责兜底唤醒。
     */

    public void destroy() {
        stopWatchdog();
        if (webView != null) {
            try {
                webView.loadUrl("about:blank");
                webView.removeJavascriptInterface("MmdBridge");
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
    }

    static String jsQuote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** JS -> Java 桥。所有方法必须加 @JavascriptInterface。 */
    private final class JsBridge {

        /** JS 上报模型屏幕包围盒（归一化 0..1）。 */
        @JavascriptInterface
        public void setModelBounds(float x0, float y0, float x1, float y1) {
            bMinX = x0;
            bMinY = y0;
            bMaxX = x1;
            bMaxY = y1;
            hasBounds = true;
            // 节流日志：每 5 秒最多一条，便于确认上报链路通了
            long now = System.currentTimeMillis();
            if (now - lastBoundsLogAt > 5000L) {
                lastBoundsLogAt = now;
                Log.i(TAG, String.format(java.util.Locale.US,
                        "bounds=[%.2f,%.2f..%.2f,%.2f]", x0, y0, x1, y1));
            }
        }

        /** JS 上报轮廓掩码（hex 字符串，每像素 1 bit，top-down 行序）。 */
        @JavascriptInterface
        public void setHitMask(String hex, int n) {
            try {
                if (hex == null || n <= 0) return;
                int bytesLen = (n * n + 7) / 8;
                byte[] out = new byte[bytesLen];
                int hexLen = Math.min(hex.length(), bytesLen * 2);
                for (int i = 0; i < hexLen; i += 2) {
                    int hi = Character.digit(hex.charAt(i), 16);
                    int lo = (i + 1 < hexLen) ? Character.digit(hex.charAt(i + 1), 16) : 0;
                    if (hi < 0) hi = 0;
                    if (lo < 0) lo = 0;
                    out[i / 2] = (byte) ((hi << 4) | lo);
                }
                boolean first = (hitMask == null);
                hitMask = out;
                hitMaskN = n;
                if (first && listener != null) {
                    webView.post(() -> listener.onMaskReady());
                }
                long now = System.currentTimeMillis();
                if (now - lastMaskLogAt > 10000L) {
                    lastMaskLogAt = now;
                    Log.i(TAG, "hit mask updated n=" + n);
                }
            } catch (Throwable t) {
                Log.w(TAG, "setHitMask failed", t);
            }
        }

        /** JS 上报饱和度/色相变化（设置页 → 原生 → 桌宠 WebView）。 */
        @JavascriptInterface
        public void colorGrade(float sat, float hue) {
            if (listener != null) {
                try { listener.onColorGradeChanged(sat, hue); } catch (Throwable ignored) {}
            }
        }

        /**
         * JS 上报"模型的真实宽高比"。
         * ★ 仅供原生在【锁定取景】时把窗口形状调成与模型一致，消除横向留白。
         *   未锁定时原生直接忽略。
         */
        @JavascriptInterface
        public void setContentAspect(float aspect) {
            contentAspect = aspect;
            if (listener != null) {
                try { listener.onContentAspect(aspect); } catch (Throwable ignored) {}
            }
        }

        /** JS 上报"锁定缩放与相机距离"的开关状态。 */
        @JavascriptInterface
        public void setViewLock(boolean locked) {
            if (listener != null) {
                try { listener.onViewLockChanged(locked); } catch (Throwable ignored) {}
            }
        }

        /**
         * JS 上报单个外观参数变化（曝光/光照/镜面/toon 等）。
         * ★ 设置页与桌宠是两个独立 WebView、各有一份模型，
         *   必须靠原生转发才能同步（否则"调曝光只有设置页生效"）。
         */
        @JavascriptInterface
        public void appearance(String key, String value) {
            if (listener != null) {
                try { listener.onAppearanceChanged(key, value); } catch (Throwable ignored) {}
            }
        }

        @JavascriptInterface
        public void log(String msg) {            Log.i(TAG, "[js] " + msg);
            if (listener != null) listener.onLog(msg);
        }

        /**
         * ★★ 渲染看门狗请原生"叫醒" WebView（2026-10-07）★★
         *
         * ---- 为什么需要 ----
         * 曾出现"模型有的时候会莫名其妙定格，播动画也没用"——播动画没用意味着
         * 画布根本没在重绘，即 requestAnimationFrame 不再回调。
         * 已知成因之一：Android 的 `WebView.onPause()` 会暂停 WebView 的处理，
         * rAF 随之停摆。本 App 有两个 WebView（桌宠悬浮窗 + MMD 参数调整页），
         * 设置页在 onPause 时调它自己的 `webView.onPause()`；
         * Chromium 在多 WebView 并存时，可见性/定时器并不总是对称恢复 ——
         * 桌宠那边可能醒来后 rAF 仍然不回调，而且【永远不会自己恢复】。
         *
         * JS 侧的看门狗检测到"8 秒没有任何帧回调"就调这里，
         * 我们重新 onResume + resumeTimers 把它拽回前台状态。
         *
         * ★ 必须 post 到主线程：WebView 的这些方法都只能在创建它的线程调用。
         * ★ 不动 visibility：是否会显示由悬浮窗自己管，这里碰了会打架。
         */
        @JavascriptInterface
        public void requestResume() {
            final WebView wv = webView;
            if (wv == null) return;
            Log.i(TAG, "[js] requestResume -> WebView.onResume() + resumeTimers()");
            wv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        wv.onResume();
                        wv.resumeTimers();
                        Log.i(TAG, "requestResume: done");
                    } catch (Throwable t) {
                        Log.w(TAG, "requestResume failed: " + t);
                    }
                }
            });
        }

        @JavascriptInterface
        public void error(String msg) {
            Log.e(TAG, "[js] " + msg);
            if (listener != null) listener.onError(msg);
        }

        @JavascriptInterface
        public void ready() {
            Log.i(TAG, "model ready");
            if (listener != null) listener.onReady();
        }

        /** 页面脚本已初始化（boot 开始前调用），用于原生侧判断可否下指令。 */
        @JavascriptInterface
        public void pageReady() {
            Log.i(TAG, "page js ready (from JS)");
            MmdPetView.this.jsReady = true;
            MmdPetView.this.flushPending();
        }

        @JavascriptInterface
        public void eval(String js) {
            MmdPetView.this.eval(js);
        }

        /**
         * 走动期间请求放宽帧率上限。
         *
         * ★ 走路动作是 30fps 录制的；帧率上限设成 20 时渲染会跳帧，
         *   表现就是"走路一卡一卡的"（2026-10-06）。
         *   走动只有几秒，临时放宽到 30 的代价可以接受，走完立刻恢复。
         */
        @JavascriptInterface
        public void setWalkFps(boolean walking) {
            if (listener != null) listener.onWalkFpsRequest(walking);
        }

        /** glTF 自带动作清单（页面加载完 GLB 后主动上报）。 */
        @JavascriptInterface
        public void glbClips(String json) {
            if (listener != null) listener.onGlbClips(json);
        }

        /** 表情清单（页面在收集完之后主动上报；见 onExpressions 的说明）。 */
        @JavascriptInterface
        public void expressions(String json) {
            if (listener != null) listener.onExpressions(json);
        }

        /**
         * 已导入模型列表。
         *
         * ★ 格式必须与前端一致：前端 refreshModelList() 按
         *   ArrayList.toString() 的形式解析 —— 外层方括号、逗号分隔、
         *   每项为 "webUrl\t显示名"。
         *   返回 JSON 会让前端解析失败，模型列表永远为空。
         */
        @JavascriptInterface
        public String listModels() {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (MmdModelManager.ImportedModel m : models.listModels()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(m.webUrl).append('\t').append(m.name);
            }
            return sb.append(']').toString();
        }

        @JavascriptInterface
        public void refreshModelList() {
            MmdPetView.this.eval("window.__refreshModelList && window.__refreshModelList()");
        }
    }
}
