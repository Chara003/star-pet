package me.rerere.pet.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.rerere.pet.data.McpManager;
import me.rerere.pet.data.PetAiClient;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.theme.UiSettings;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.core.PetContracts;

/**
 * 对话面板：上方可滚动的历史消息，下方大输入框。
 * 每条回复下方带详细信息 / 重新生成 / 朗读 / 更多菜单。
 */
public final class PetDialogueController {

    public interface CompletionListener {
        void onCompleted(PetContracts.VisualHint hint);
    }

    /** 整个对话面板高度占屏幕比例。 */
    /** 面板高度以"最大高度"为准（内容少时内部布局自然更矮，但窗口按上限给）。 */

    private final Context context;
    private final PetStore store;
    private final CompletionListener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private LinearLayout panel;
    private View historyPanel;
    private View actionPanel;

    private ScrollView messageScroll;
    private LinearLayout messageList;
    private EditText inputField;
    private TextView contextLabel;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String speakingText = null;

    private McpManager mcpManager;

    /** 发送/停止按钮。 */
    private TextView sendButton;
    /** 当前进行中的请求，用于停止与防丢失。 */
    private volatile PetAiClient.CancelToken activeToken;
    /** 生成中的实时视图引用，面板重开后仍能续写。 */
    private TextView streamingText;
    /** 用户是否停在底部（决定是否自动跟随）。 */
    private boolean stickToBottom = true;
    private StringBuilder streamingBuffer;
    private String pendingUserInput;
    private PetStore.Turn pendingTurn;

    public PetDialogueController(Context context, PetStore store, CompletionListener listener) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.listener = listener;
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        initTts();
    }

    public void setMcpManager(McpManager mcpManager) { this.mcpManager = mcpManager; }

    private void initTts() {
        try {
            tts = new TextToSpeech(context, status -> {
                ttsReady = (status == TextToSpeech.SUCCESS);
                if (ttsReady) {
                    try { tts.setLanguage(Locale.getDefault()); } catch (Exception ignored) {}
                }
            });
        } catch (Exception ignored) {
            tts = null;
        }
    }

    // ---------------- 面板 ----------------

    public void showInput() {
        if (panel != null) {
            inputField.requestFocus();
            return;
        }
        buildPanel();
    }

    /**
     * 关闭面板。注意：进行中的请求不会被取消，
     * 结果仍会写入会话，重开面板即可看到（避免消息丢失）。
     */
    public void close() {
        stopSpeaking();
        closeActionPanel();
        closeHistoryPanel();
        if (panel != null) {
            try { windowManager.removeView(panel); } catch (Exception ignored) {}
        }
        panel = null;
        messageScroll = null;
        messageList = null;
        inputField = null;
        sendButton = null;
        streamingText = null;
    }

    /** 面板是否已关闭（生成结束回写时用于判断是否还需要更新 UI）。 */
    private boolean panelAlive() {
        return panel != null && messageList != null;
    }

    /** 对话面板当前是否显示中（漫游需要知道"是否在对话"，好停下来）。 */
    public boolean isShowing() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }

    private void buildPanel() {
        int screenH = context.getResources().getDisplayMetrics().heightPixels;

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(0xFFFFFFFF, dp(24), 0x22000000, dp(1)));
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));

        // 顶栏：历史 | 标题 | 新建 | 关闭
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView historyBtn = iconButton("≡");
        historyBtn.setOnClickListener(v -> showHistoryDialog());
        topBar.addView(historyBtn, lp(dp(40), dp(40), 0f));

        contextLabel = new TextView(context);
        contextLabel.setTextSize(15);
        contextLabel.setTextColor(0xFF1B1B1F);
        contextLabel.setSingleLine(true);
        contextLabel.setGravity(Gravity.CENTER);
        contextLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(contextLabel, lp(0, dp(40), 1f));

        TextView newBtn = iconButton("＋");
        newBtn.setOnClickListener(v -> {
            stopSpeaking();
            store.createSession();
            refreshContextLabel();
            renderMessages();
            toast("已新建对话");
        });
        topBar.addView(newBtn, lp(dp(40), dp(40), 0f));

        TextView closeBtn = iconButton("✕");
        closeBtn.setOnClickListener(v -> close());
        topBar.addView(closeBtn, lp(dp(40), dp(40), 0f));

        panel.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 消息区（可滚动，占据剩余空间）
        messageScroll = new ScrollView(context);
        messageScroll.setFillViewport(true);
        messageList = new LinearLayout(context);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(0, dp(6), 0, dp(6));
        messageScroll.addView(messageList);
        // 监听滚动：只有用户停在底部时才自动跟随，滚上去看历史时不打扰
        messageScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (messageScroll == null) return;
            View child = messageScroll.getChildAt(0);
            if (child == null) return;
            int diff = child.getBottom() - (messageScroll.getHeight() + messageScroll.getScrollY());
            stickToBottom = diff <= dp(48);
        });
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panel.addView(messageScroll, scrollLp);

        // 输入区（固定 40% 屏高）
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);

        inputField = new EditText(context);
        inputField.setHint("说点什么…");
        inputField.setTextSize(15);
        inputField.setGravity(Gravity.TOP | Gravity.START);
        inputField.setMinLines(1);
        inputField.setMaxLines(4);
        inputField.setBackground(rounded(0xFFF4F4F8, dp(16), 0x00000000, 0));
        inputField.setPadding(dp(14), dp(12), dp(14), dp(12));
        inputRow.addView(inputField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = new TextView(context);
        sendButton.setText("发送");
        sendButton.setTextSize(15);
        sendButton.setTextColor(0xFFFFFFFF);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(rounded(0xFF4C6EF5, dp(16), 0x00000000, 0));
        sendButton.setPadding(dp(18), dp(14), dp(18), dp(14));
        sendButton.setOnClickListener(v -> onSendButtonClicked());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.leftMargin = dp(8);
        inputRow.addView(sendButton, sendLp);

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(8);
        panel.addView(inputRow, inputLp);

        refreshContextLabel();
        renderMessages();

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O)
            type = WindowManager.LayoutParams.TYPE_PHONE;
        /* 面板高度由「界面」页的两个滑块控制（最小/最大高度）。
           窗口给"最大高度"，内容少时内部 LinearLayout 不会撑满，
           背景只包住实际内容 —— 视觉上就是"高度随内容在 min~max 之间"。 */
        int panelMinPx = (int) (screenH * UiSettings.panelMinPct() / 100f);
        int panelMaxPx = (int) (screenH * UiSettings.panelMaxPct() / 100f);
        if (panelMaxPx < panelMinPx) panelMaxPx = panelMinPx;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                panelMaxPx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        /* ★★ gravity 用 TOP|START，不用 BOTTOM ★★
           ---- 闪烁根因（实测"输入框在两个位置之间跳"）----
           旧写法是 gravity=BOTTOM + 手动设 params.y。
           这两个是【互斥】的定位方式：
             · BOTTOM 表示"窗口底边贴父容器底边"，y 是相对底边的偏移；
             · 我们又每帧把 y 改成 imeHeightPx（想抬到键盘上方）。
           系统先按 gravity 贴底、再叠加 y，而我按"从屏幕顶算"的值去设 y，
           两套坐标系混用 → 窗口在"原位"和"键盘上方"之间来回跳。
           现在统一用 TOP|START + 从屏幕顶部算的 y，只有一个坐标系。 */
        /* ★★★ 这里必须用 BOTTOM 重力 ★★★
           ---- 前几版之所以闪烁，不是 gravity 选错，而是【改了它的语义】----
           面板本来就该贴在屏幕底部（高度 40% 屏高）。
           用 Gravity.BOTTOM 时，params.y 天然表示"离屏幕底边多远"——
                y = 0        -> 贴底
                y = 键盘高度  -> 被抬到键盘上方
           这才是同一个坐标系，一个变量搞定，不需要减来减去。
           我前几版改成 TOP 重力 + 自己算绝对坐标，反而引入了两套坐标系，
           系统按 gravity 算一次、我又按 TOP 算一次 → 闪。 */
        params.gravity = Gravity.BOTTOM;
        params.y = 0;
        /* ★★ softInputMode 对 overlay 窗口【基本无效】★★
           ---- 事故复盘（实测"打开输入法时气泡还在原位，看不见输入框"）----
           ADJUST_RESIZE / ADJUST_PAN 是给 Activity 窗口用的；TYPE_APPLICATION_OVERLAY
           的窗口不在 Activity 的任务栈里，系统不会替它腾出键盘空间。
           所以必须自己监听键盘高度并抬升窗口：
             · API 30+ 用 WindowInsets.Type.ime() 取精确高度；
             · 更早的版本没有可靠 API，退回"读可用屏高变化"的近似值。
           两个值都是"窗口底部需要让出多少像素"。 */
        /* ★ 必须显式 ADJUST_NOTHING：虽然 overlay 窗口本来就不吃 ADJUST_RESIZE，
           但部分 ROM 会据此做一次"好心"的位移，
           与我们自己的抬升叠加 = 两次移动 = 用户看到的闪烁。
           明确声明"别动我的窗口"，把控制权收回自己手里。 */
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        panel.setFitsSystemWindows(false);
        installImeWatcher();
        try {
            windowManager.addView(panel, params);
            panelParams = params;
        } catch (Exception e) {
            panel = null;
            toast("无法显示对话窗口");
        }
    }

    /* ---------------- 输入法高度监听 ---------------- */

    /** 面板窗口参数（键盘出现时要改它的 y / height）。 */
    private WindowManager.LayoutParams panelParams;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener imeListener;

    /**
     * 监听键盘高度并抬升面板。
     *
     * 用的是"可视区域变化"这个最可靠的信号：
     *   getWindowVisibleDisplayFrame 给出当前**未被键盘遮挡**的矩形。
     *   屏幕高 - 可见区底部 = 键盘高度。这个办法在 API 24+ 都有效，
     *   而且不依赖 Activity（overlay 里也没有 Activity 可依赖）。
     */
    /**
     * 监听键盘高度并抬升面板。
     *
     * ══════════════════════════════════════════════════════════════
     * ★★★ 这里曾经导致【CPU 打满 + 面板疯狂闪烁】★★★
     * ══════════════════════════════════════════════════════════════
     *   旧写法：
     *     OnGlobalLayoutListener {
     *         if (covered == imeHeightPx) return;   // 看似有去重
     *         applyImeOffset() ->  updateViewLayout()  // ← 触发布局
     *                          ->  scrollToBottom()    // ← 又触发布局/滚动
     *     }
     *   问题：
     *     ① updateViewLayout 会同步触发一次布局 → 布局回调 → 再进来；
     *        虽然 covered 去重挡住了重复抬升，但 scrollToBottom 每轮都跑，
     *        而滚动又会改变可见区域判定 → covered 来回抖动 → 死循环。
     *     ② 每次抖动都调 updateViewLayout，窗口在"原位"和"键盘上方"之间
     *        来回跳 —— 用户看到的"对话框在两个位置闪"就是这个。
     *     ③ 主线程被这个循环占满，系统合成器跟着掉帧，
     *        桌宠的 WebGL 那条线也抢不到 GPU 时间 → "GPU 严重性能问题"。
     *
     *   现在的三条防线：
     *     ① 用【屏幕高度】而不是可见区域算键盘高度，不会因滚动而抖动；
     *     ② 只有高度变化超过阈值(24dp)才真的动窗口，且抬升后不再碰它；
     *     ③ 滚动只在"键盘刚弹出/收起"时做一次，不在布局回调里反复做。
     */
    /**
     * 监听键盘高度并抬升面板。
     *
     * ══════════════════════════════════════════════════════════════
     * ★★★ 闪烁的三次修复过程（前两次都没修对）★★★
     * ══════════════════════════════════════════════════════════════
     *   ① 一开始：ADJUST_RESIZE + 每帧按可见区域算 → 键盘动画期间
     *      可见区域连续变化几十次，逐次 updateViewLayout → 疯狂抖。
     *   ② 改成 gravity=TOP + 阈值去重 → 抖动少了，但仍会闪一下。
     *      原因：键盘弹出时可见区域会先报一个"中间值"，再报最终值，
     *      两者差超过阈值，于是窗口先移到中间位置再移到位 = 闪。
     *   ③ 现在：状态机。
     *      只认两个稳定状态（键盘完全收起 / 完全弹出），
     *      中间所有过渡值一律忽略。键盘动画期间窗口一次都不动，
     *      动画结束后才归位 —— 视觉上是"键盘升起，面板跟着上移一次"。
     *
     *   另外把 softInputMode 设为 ADJUST_NOTHING：
     *   某些 ROM 会依据 ADJUST_RESIZE 自己做一次位移，与我们的抬升叠加
     *   就成了"闪"。明确禁止系统插手。
     */
    /**
     * 键盘高度监听：把面板整体抬到键盘上方。
     *
     * ══════════════════════════════════════════════════════════════
     * 坐标系（想清楚这个就不会闪了）
     * ══════════════════════════════════════════════════════════════
     *   面板窗口：gravity = BOTTOM，height = 40% 屏高。
     *   在 BOTTOM 重力下，params.y 的含义是【窗口底边离屏幕底边的距离】：
     *       y = 0          → 贴底（键盘挡住输入框）
     *       y = 键盘高度    → 窗口整体上移，输入框正好在键盘上方
     *   所以抬升就是一句话：params.y = covered。不需要任何 baseY 换算，
     *   也就不会出现"两套坐标打架"导致的闪烁。
     *
     * ══════════════════════════════════════════════════════════════
     * 防抖：只在"键盘完全弹出/完全收起"两个稳态动作
     * ══════════════════════════════════════════════════════════════
     *   键盘升起动画期间 getWindowVisibleDisplayFrame 会连续报几十个中间值。
     *   逐次 updateViewLayout 就是用户看到的"疯狂闪"。
     *   → 用状态机：只有跨越阈值那一刻才动窗口，中间值全部忽略。
     */
    /**
     * 让面板避开键盘。
     *
     * ══════════════════════════════════════════════════════════════
     * ★★★ 为什么前四版全都没生效 ★★★
     * ══════════════════════════════════════════════════════════════
     *   一直在用 ViewTreeObserver.OnGlobalLayoutListener 监听键盘。
     *   但那个回调的触发条件是【本窗口自己有布局变化】。
     *   而我们的面板：
     *       · 窗口 size 固定（不随键盘变）
     *       · softInputMode = ADJUST_NOTHING（明确告诉系统别动我）
     *   → 键盘弹出时本窗口完全不变 → onGlobalLayout 根本不回调
     *   → 监听器里的抬升代码一次都没执行过。
     *   这是"怎么改都没反应"的真正原因，跟阈值、坐标系都无关。
     *
     * ══════════════════════════════════════════════════════════════
     * 现在：用输入框焦点 + 固定抬升
     * ══════════════════════════════════════════════════════════════
     *   overlay 窗口拿不到 IME insets（系统只派发给有焦点的 Activity 窗口），
     *   也无法可靠测量键盘高度。既然测不准，就**不去测**：
     *     · 输入框获得焦点 → 立刻把面板抬到"屏幕上半部"
     *       （面板底边距屏幕底 = 屏高的 45%，比绝大多数键盘都高）
     *     · 输入框失焦   → 放回底部
     *   这样不管键盘多高（35%~48%），输入框一定在它上方。
     *   代价是位置是"一步到位"而非跟随动画 —— 但至少看得见、可用。
     */
    /**
     * 让面板避开键盘（含"键盘收起后归位"）。
     *
     * ══════════════════════════════════════════════════════════════
     * 演进过程（每一版都真实踩过）
     * ══════════════════════════════════════════════════════════════
     *   ① OnGlobalLayoutListener + 可见区域差
     *      → overlay 窗口尺寸不变，系统不回调，一次都没执行。
     *   ② 同上 + 各种阈值
     *      → 还是不回调，白改。
     *   ③ 输入框焦点：聚焦时抬升
     *      → 抬升有效了！但"键盘收起后输入框仍保持焦点"，
     *        没有失焦事件 → 面板留在半空（用户实测）。
     *
     *   ④ 现在：焦点负责"何时该抬"，WindowInsets 负责"键盘还在不在"。
     *      · 输入框聚焦   → 抬升
     *      · 输入框失焦   → 归位
     *      · 输入框仍有焦点但键盘消失（用户点了收起键盘 / 返回键）
     *        → 靠 insets 检测到 ime 不可见，归位。
     *
     * ★ 为什么用 setOnApplyWindowInsetsListener 而不是 insetsController：
     *   overlay 窗口没有 Activity 的 InsetsController；
     *   但 View 层的 onApplyWindowInsets 在 API 20+ 就能收到系统派发的 insets，
     *   包括 ime（API 30+ 有 WindowInsets.Type.ime()）。
     *   设备是 API 36，完全支持。
     * ★ 低版本（<30）没有 ime 类型，退化为"只靠焦点"——
     *   那些版本上点收起键盘通常会让输入框失焦，够用。
     */
    /**
     * 让面板避开键盘（兼容 Android 11 及以上）。
     *
     * ══════════════════════════════════════════════════════════════
     * 演进过程（每一版都真实踩过，别再往回改）
     * ══════════════════════════════════════════════════════════════
     *   ① OnGlobalLayoutListener + 可见区域差
     *      → overlay 窗口自身尺寸不变，系统根本不回调，一次都没执行。
     *   ② 各种阈值 + 状态机
     *      → 还是不回调，白改（阈值本身没问题，是回调没触发）。
     *   ③ 输入框焦点：聚焦时抬升
     *      → 抬升有效了，但"键盘收起后输入框仍保持焦点"，
     *        没有失焦事件 → 面板留在半空。
     *   ④ WindowInsets.Type.ime()
     *      → API 30+ 才有，而且 overlay 窗口拿不到 Activity 那套
     *        InsetsController，不同 ROM 派发情况不一致（朋友是 Android 11）。
     *
     * ══════════════════════════════════════════════════════════════
     * ⑤ 现在：焦点 + 轻量轮询（最土但最可靠）
     * ══════════════════════════════════════════════════════════════
     *   getWindowVisibleDisplayFrame 在【所有版本】都能反映键盘占用的高度，
     *   只是它不会主动通知我们。既然通知不到，那就主动问——
     *   只在输入框有焦点期间每 250ms 问一次，失焦立即停止。
     *
     *   开销：有焦点时 4 次/秒，一次 Rect 读取，可忽略。
     *   收起键盘会怎样：covered 变回 ~0 → 自动归位（这就是④没解决的）。
     */
    /**
     * 让面板避开键盘。
     *
     * ══════════════════════════════════════════════════════════════
     * ★★★ 诊断日志给出的决定性证据（别再走回头路）★★★
     * ══════════════════════════════════════════════════════════════
     *  键盘确实弹出了，但：
     *     poll: screenH=2568 visBottom=2720 covered=0
     *  visBottom(2720) 比 screenH(2568) 还大 152px（=状态栏高度）。
     *  说明 getWindowVisibleDisplayFrame() 在这个 overlay 窗口上返回的
     *  是【完全不可信的值】—— 该 API 对非 Activity 窗口在 Android 11+
     *  已被系统弃用，永远不会扣掉键盘高度。
     *  → 所以"轮询可见区域"这条路从根上就是死的，前面几版都白改了。
     *
     *  另一份证据来自 dumpsys window：
     *     mControlTarget=Window{... me.rerere.pet}
     *     mInsetsHint=Insets{... bottom=1100}      ← 键盘高 1100px
     *  说明【系统已经在把 IME insets 派发给我们的窗口】，
     *  只是我之前把监听加在 inputField 上（子 View），它收不到。
     *
     * ══════════════════════════════════════════════════════════════
     * 正解：监听窗口根 View 的 insets
     * ══════════════════════════════════════════════════════════════
     *   · 监听对象必须是 panel（窗口根），不是 inputField；
     *   · API 30+ 用 WindowInsets.Type.ime() 拿精确高度；
     *   · API 30 以下没有 ime 类型，退回"systemWindowInsets.bottom 变化"
     *     （Android 11 就是 API 30，正好有 ime 类型，朋友设备没问题）；
     *   · 每次 insets 变化都重新设置监听，否则只能收到一次。
     */
    private void installImeWatcher() {
        if (panel == null) return;
        /* ★ 保留这两条诊断日志（低频，不会刷屏）：
           排查输入法问题时直接 logcat -s PetIme 就能看出
           insets 通道是否通、面板到底移了几次。
           曾经的死循环（y 每 208ms 在 1048/0 之间跳 55 次）
           就是靠这行日志定位的。 */
        android.util.Log.i("PetIme", "installImeWatcher: panel=" + (panel != null));

        /* ★ 必须监听窗口根 View：insets 是派发给窗口的，子 View 收不到。
           ★★ 防抖是必须的（用户实测"位置对了但闪"）★★
           键盘升起动画大约 200~250ms，这期间系统会派发几十次 insets，
           每次高度都不一样。如果每次都 updateViewLayout，窗口就跟动画
           逐帧对齐 —— 而我们的窗口和系统键盘不是同一个动画曲线，
           两套动画叠加 = 用户看到的"面板在原位和上方之间闪"。
           做法：每次 insets 变化只【重新计时】，200ms 内不再有新变化
           才真正移动窗口一次。动画期间一次都不动。 */
        panel.setOnApplyWindowInsetsListener((v, insets) -> {
            /* ★★★ 自激循环的根治（日志证据）★★★
               y=1048 与 y=0 每 208ms 精确交替 —— 这不是"抖"，是死循环：
                 ① 我们 updateViewLayout 移动窗口
                 ② 窗口位置变了 → 系统重新派发 insets
                 ③ 监听器又读到 imeBottom（这次有时是 0，因为窗口位移
                    影响到了 insets 的计算基线）
                 ④ 又决定移动 → 回到 ①
               关键：**移动窗口本身会触发新的 insets 派发**。
               所以必须在我们自己造成的那次派发里"闭嘴"：
                 · 移动窗口前置 suppress=true；
                 · 监听器见到 suppress 就只更新"已知高度"，不重新排程；
                 · 250ms 后（足够一次派发往返）才允许再次响应。
               同时只在 imeBottom 真正变化时才重启防抖计时，
               否则纯重复派发也会不断把计时往后推，让它永远不收敛。 */
            int imeBottom = 0;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    imeBottom = insets.getInsets(
                            android.view.WindowInsets.Type.ime()).bottom;
                }
                if (imeBottom <= 0) {
                    imeBottom = insets.getSystemWindowInsets().bottom;
                }
            } catch (Throwable ignored) {}

            if (imeSuppress) {
                /* 这是我们自己移动窗口引发的派发：只记录，不排程 */
                return insets;
            }
            if (imeBottom == pendingImeBottom) {
                /* 高度没变（纯重复派发）：不重启计时 */
                return insets;
            }
            pendingImeBottom = imeBottom;
            panel.removeCallbacks(imeApply);
            panel.postDelayed(imeApply, 220);
            return insets;
        });
        panel.requestApplyInsets();
    }

    /** 自己移动窗口期间置 true，屏蔽由此引发的 insets 派发。 */
    private boolean imeSuppress = false;

    /** 待应用的键盘高度（防抖期间不断更新）。 */
    private int pendingImeBottom = 0;
    /** 上一次真正应用的键盘高度（用于幂等判断，避免重复 updateViewLayout）。 */
    private int lastImeBottom = -1;
    /** 当前是否处于"抬升"状态。 */
    private boolean imeLifted = false;

    /** 防抖结束后真正移动窗口（一次）。 */
    private final Runnable imeApply = new Runnable() {
        @Override public void run() {
            if (panel == null || panelParams == null) return;
            int target = pendingImeBottom;
            boolean lift = target > 0;
            if (imeLifted == lift && lastImeBottom == target) return;
            imeLifted = lift;
            lastImeBottom = target;
            try {
                /* 移动窗口前屏蔽 insets，避免自激（见监听器注释） */
                imeSuppress = true;
                panelParams.y = target;
                windowManager.updateViewLayout(panel, panelParams);
                android.util.Log.i("PetIme", "lift -> y=" + target);
            } catch (Throwable t) {
                android.util.Log.w("PetIme", "updateViewLayout failed", t);
            } finally {
                /* 250ms 后再放开：足够让这次派发往返完成 */
                if (panel != null) {
                    panel.postDelayed(() -> imeSuppress = false, 250);
                } else {
                    imeSuppress = false;
                }
            }
        }
    };

    /** 收起面板时移除监听（避免泄漏）。 */
    private void removeImeWatcher() {
        try {
            if (inputField != null) {
                inputField.setOnFocusChangeListener(null);
                inputField.setOnApplyWindowInsetsListener(null);
            }
        } catch (Throwable ignored) {}
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private TextView iconButton(String glyph) {
        TextView b = new TextView(context);
        b.setText(glyph);
        b.setTextSize(20);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xFF1B1B1F);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private static GradientDrawable rounded(int fill, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    private void refreshContextLabel() {
        if (contextLabel == null) return;
        PetStore.Session s = store.activeSession();
        contextLabel.setText(s == null ? "新对话" : s.title);
    }

    private void toast(String msg) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show(); }

    // ---------------- 消息渲染 ----------------

    private void renderMessages() {
        if (messageList == null) return;
        messageList.removeAllViews();

        PetStore.Session session = store.activeSession();
        if (session == null) return;

        if (session.turns.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("还没有对话，说点什么吧。");
            empty.setTextSize(13);
            empty.setTextColor(0xFF9A9AA2);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            messageList.addView(empty);
            return;
        }

        for (int i = 0; i < session.turns.size(); i++) {
            PetStore.Turn turn = session.turns.get(i);
            addUserBubble(turn.userInput);
            addPetBubble(turn, i);
        }
        // 打开面板或切换会话时，直接定位到最新消息
        forceScrollToBottom();
    }

    private void addUserBubble(String text) {
        if (text == null || text.isEmpty()) return;
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(15);
        v.setTextColor(0xFF1B1B1F);
        v.setBackground(rounded(0xFFE8ECFB, dp(16), 0x00000000, 0));
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(10);
        lp.leftMargin = dp(48);
        messageList.addView(v, lp);
    }

    /** 桌宠消息块：名字头像 → 思考过程 → 工具卡片 → 正文 → 操作栏 → 详细信息。 */
    private void addPetBubble(PetStore.Turn turn, int index) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        block.setLayoutParams(blp);

        // ---- 表头：头像 + 名字 ----
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(avatarView(dp(28)));
        TextView nameView = new TextView(context);
        nameView.setText(store.displayName());
        nameView.setTextSize(15);
        nameView.setTextColor(0xFF1B1B1F);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(8);
        header.addView(nameView, nlp);
        block.addView(header);

        String text = turn.assistantText == null ? "" : turn.assistantText;

        // ---- 思考过程（默认折叠） ----
        if (turn.reasoning != null && !turn.reasoning.trim().isEmpty()) {
            block.addView(reasoningCard(turn.reasoning, turn.elapsedMs));
        }

        // ---- 工具调用卡片 ----
        if (turn.toolEvents != null) {
            for (PetStore.ToolEvent ev : turn.toolEvents) {
                block.addView(toolCard(ev));
            }
        }

        // ---- 正文 ----
        if (!text.isEmpty()) {
            TextView v = new TextView(context);
            v.setText(text);
            v.setTextSize(15);
            v.setTextColor(0xFF2A2A30);
            v.setPadding(dp(2), dp(6), dp(2), dp(2));
            block.addView(v);
        }

        // ---- 操作栏 ----
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(2), dp(2), 0, 0);

        TextView regen = smallAction("↻");
        regen.setOnClickListener(x -> regenerate(index));
        actions.addView(regen);

        TextView speak = smallAction("🔊");
        speak.setOnClickListener(x -> speakOut(text, speak));
        actions.addView(speak);

        TextView more = smallAction("⋮");
        more.setOnClickListener(x -> showMessageMenu(index, text));
        actions.addView(more);

        block.addView(actions);

        // ---- 详细信息 ----
        TextView detail = new TextView(context);
        detail.setText(detailText(turn));
        detail.setTextSize(11);
        detail.setTextColor(0xFF9A9AA2);
        detail.setPadding(dp(2), dp(2), 0, dp(2));
        block.addView(detail);

        messageList.addView(block);
    }

    /** 圆形头像；没有自定义头像时用名字首字。 */
    private View avatarView(int size) {
        String path = store.avatarPath();
        if (path != null && !path.isEmpty()) {
            try {
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path);
                if (bmp != null) {
                    android.widget.ImageView iv = new android.widget.ImageView(context);
                    iv.setImageBitmap(circleCrop(bmp));
                    iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                    return iv;
                }
            } catch (Exception ignored) {}
        }
        TextView fallback = new TextView(context);
        fallback.setText("🐾");
        fallback.setTextSize(14);
        fallback.setGravity(Gravity.CENTER);
        fallback.setBackground(rounded(0xFFE8ECFB, size / 2, 0x00000000, 0));
        fallback.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return fallback;
    }

    /** 把位图裁成圆形。 */
    private android.graphics.Bitmap circleCrop(android.graphics.Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        android.graphics.Path path = new android.graphics.Path();
        path.addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW);
        canvas.clipPath(path);
        int left = (src.getWidth() - size) / 2;
        int top = (src.getHeight() - size) / 2;
        canvas.drawBitmap(src, new android.graphics.Rect(left, top, left + size, top + size),
                new android.graphics.Rect(0, 0, size, size), paint);
        return out;
    }

    /** 思考过程卡片：默认折叠，点击展开。 */
    private View reasoningCard(String reasoning, long elapsedMs) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(0xFFF4F4F8, dp(14), 0x00000000, 0));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView bulb = new TextView(context);
        bulb.setText("💡");
        bulb.setTextSize(14);
        headerRow.addView(bulb);

        TextView title = new TextView(context);
        title.setText("思考了 " + String.format(Locale.US, "%.1f", Math.max(0.1, elapsedMs / 1000.0)) + " 秒");
        title.setTextSize(14);
        title.setTextColor(0xFF6B6B74);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        headerRow.addView(title, tlp);

        TextView arrow = new TextView(context);
        arrow.setText("⌄");
        arrow.setTextSize(16);
        arrow.setTextColor(0xFF6B6B74);
        headerRow.addView(arrow);

        card.addView(headerRow);

        TextView body = new TextView(context);
        body.setText(reasoning);
        body.setTextSize(13);
        body.setTextColor(0xFF6B6B74);
        body.setPadding(0, dp(8), 0, 0);
        body.setVisibility(View.GONE);
        card.addView(body);

        headerRow.setOnClickListener(v -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            animateToggle(card, body, !expanded);
            arrow.setText(expanded ? "⌄" : "⌃");
        });
        return card;
    }

    /** 工具调用卡片：图标 + 标题 + 内容预览 + 箭头，点击展开全文。 */
    private View toolCard(final PetStore.ToolEvent ev) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(0xFFF7F7FB, dp(14), 0x00000000, 0));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(context);
        icon.setText(toolIcon(ev.name));
        icon.setTextSize(14);
        headerRow.addView(icon);

        TextView title = new TextView(context);
        title.setText(ev.title == null || ev.title.isEmpty() ? "调用了工具" : ev.title);
        title.setTextSize(14);
        title.setTextColor(0xFF2F7E7E);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        headerRow.addView(title, tlp);

        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextSize(16);
        arrow.setTextColor(0xFF9A9AA2);
        headerRow.addView(arrow);

        card.addView(headerRow);

        // 预览正文
        final String full = (ev.preview == null ? "" : ev.preview);
        final TextView body = new TextView(context);
        body.setText(full);
        body.setTextSize(13);
        body.setTextColor(0xFF4A4A54);
        body.setPadding(0, dp(6), 0, 0);
        body.setMaxLines(3);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(body);

        headerRow.setOnClickListener(v -> {
            boolean collapsed = body.getMaxLines() != Integer.MAX_VALUE;
            android.transition.TransitionManager.beginDelayedTransition(
                    card, new android.transition.AutoTransition()
                            .setDuration(180));
            if (collapsed) {
                body.setMaxLines(Integer.MAX_VALUE);
                body.setEllipsize(null);
            } else {
                body.setMaxLines(3);
                body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
            arrow.setText(collapsed ? "⌄" : "›");
        });
        return card;
    }

    /**
     * 展开/收起动画：用 TransitionManager 平滑过渡高度变化。
     * 直接切 setVisibility 会突兀跳变，这里让它渐变收缩。
     */
    private void animateToggle(View parent, View content, boolean expand) {
        try {
            android.transition.TransitionSet set = new android.transition.TransitionSet();
            set.addTransition(new android.transition.Fade().setDuration(150));
            set.addTransition(new android.transition.ChangeBounds().setDuration(200));
            android.transition.TransitionManager.beginDelayedTransition(
                    (android.view.ViewGroup) parent, set);
        } catch (Exception ignored) {}
        content.setVisibility(expand ? View.VISIBLE : View.GONE);
    }

    /** 按工具名给个直观图标。 */
    private String toolIcon(String name) {
        if (name == null) return "🔧";
        if (name.contains("memory_add")) return "🌱";
        if (name.contains("memory_search")) return "🔍";
        if (name.contains("memory_list")) return "📋";
        if (name.contains("memory_forget")) return "🗑";
        if (name.contains("history_search")) return "🕘";
        return "🔧";
    }

    private String detailText(PetStore.Turn turn) {
        StringBuilder sb = new StringBuilder();
        if (turn.promptTokens >= 0) {
            sb.append("↑ ").append(turn.promptTokens).append(" tokens");
        }
        if (turn.completionTokens >= 0) {
            if (sb.length() > 0 && turn.promptTokens >= 0) sb.append("  ");
            sb.append("↓ ").append(turn.completionTokens).append(" tokens");
        }
        if (turn.elapsedMs > 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("⏱ ").append(String.format(Locale.US, "%.1f", turn.elapsedMs / 1000.0)).append("s");
        }
        if (sb.length() == 0) sb.append("—");
        sb.append("   ").append(formatTime(turn.at));
        return sb.toString();
    }

    private String shortToolName(String qualified) {
        if (qualified == null) return "";
        String s = qualified;
        if (s.startsWith("mcp__")) {
            String[] parts = s.split("__");
            if (parts.length >= 3) return parts[parts.length - 1];
        }
        return s;
    }

    private String formatTime(long millis) {
        if (millis <= 0) return "";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return fmt.format(new java.util.Date(millis));
    }

    private TextView smallAction(String glyph) {
        TextView v = new TextView(context);
        v.setText(glyph);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(0xFF6B6B74);
        v.setPadding(dp(10), dp(4), dp(10), dp(4));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /** 滚到底部（仅在用户未上滑查看历史时生效）。 */
    private void scrollToBottom() {
        if (messageScroll == null) return;
        if (!stickToBottom) return;
        messageScroll.post(() -> {
            if (messageScroll != null && stickToBottom) {
                messageScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** 强制滚到底部（发送新消息时使用，此时应无条件跟随）。 */
    private void forceScrollToBottom() {
        stickToBottom = true;
        if (messageScroll == null) return;
        messageScroll.post(() -> {
            if (messageScroll != null) messageScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    // ---------------- 朗读 ----------------

    private void speakOut(String text, TextView button) {
        if (text == null || text.trim().isEmpty()) return;
        if (tts == null || !ttsReady) {
            toast("朗读服务不可用");
            return;
        }
        try {
            if (text.equals(speakingText)) {
                stopSpeaking();
                button.setText("🔊");
                return;
            }
            stopSpeaking();
            speakingText = text;
            button.setText("⏹");
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pet_speech");
            // 播完后恢复图标
            int estimate = Math.max(2000, text.length() * 220);
            mainHandler.postDelayed(() -> {
                if (text.equals(speakingText)) {
                    speakingText = null;
                    button.setText("🔊");
                }
            }, estimate);
        } catch (Exception e) {
            toast("朗读失败");
        }
    }

    private void stopSpeaking() {
        speakingText = null;
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) {}
    }

    // ---------------- 消息菜单 ----------------

    private void showMessageMenu(final int index, final String text) {
        closeActionPanel();
        actionPanel = buildActionPanel("消息操作",
                new String[]{"选择复制", "删除", "取消"}, which -> {
                    closeActionPanel();
                    if (which == 0) {
                        copyToClipboard(text);
                    } else if (which == 1) {
                        deleteTurn(index);
                    }
                });
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("pet", text));
                toast("已复制");
            }
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    private void deleteTurn(int index) {
        PetStore.Session session = store.activeSession();
        if (session == null) return;
        List<PetStore.Turn> turns = session.turns;
        if (index < 0 || index >= turns.size()) return;
        turns.remove(index);
        store.saveSessions(store.sessions());
        // 重新写回（saveSessions 接受列表，这里直接用当前会话覆盖）
        List<PetStore.Session> all = store.sessions();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(session.id)) {
                all.set(i, session);
                break;
            }
        }
        store.saveSessions(all);
        renderMessages();
        toast("已删除");
    }

    // ---------------- 发送 / 重新生成 ----------------

    /** 发送 / 停止 按钮统一入口。 */
    private void onSendButtonClicked() {
        if (activeToken != null) {
            // 正在生成 -> 停止
            activeToken.cancel();
            toast("已停止");
            return;
        }
        if (inputField == null) return;
        String text = inputField.getText().toString().trim();
        if (text.isEmpty()) return;
        inputField.setText("");
        dispatch(text);
    }

    /** 生成中：按钮变红并显示"停止"。 */
    private void enterGeneratingState() {
        if (sendButton == null) return;
        sendButton.setText("停止");
        sendButton.setBackground(rounded(0xFFE53935, dp(16), 0x00000000, 0));
    }

    /** 生成结束：恢复发送按钮。 */
    private void exitGeneratingState() {
        if (sendButton == null) return;
        sendButton.setText("发送");
        sendButton.setBackground(rounded(0xFF4C6EF5, dp(16), 0x00000000, 0));
    }

    private void regenerate(int index) {
        if (activeToken != null) {
            toast("正在生成中，请先停止");
            return;
        }
        PetStore.Session session = store.activeSession();
        if (session == null) return;
        if (index < 0 || index >= session.turns.size()) return;
        String userInput = session.turns.get(index).userInput;
        if (userInput == null || userInput.trim().isEmpty()) {
            toast("这一轮没有可重发的输入");
            return;
        }
        deleteTurnQuiet(index);
        renderMessages();
        dispatch(userInput);
    }

    private void deleteTurnQuiet(int index) {
        PetStore.Session session = store.activeSession();
        if (session == null) return;
        List<PetStore.Session> all = store.sessions();
        for (PetStore.Session s : all) {
            if (s.id.equals(session.id)) {
                if (index >= 0 && index < s.turns.size()) s.turns.remove(index);
                break;
            }
        }
        store.saveSessions(all);
    }

    /**
     * 实际发送：流式接收，边收边渲染。
     * 面板中途被关闭也不会丢消息——结果始终写回会话。
     */
    private void dispatch(String text) {
        final PetStore.Session session = store.activeSession();
        final PetStore.Provider provider = store.activeProvider();
        if (provider == null || !provider.isUsable()) {
            toast("请先在设置里配置 AI 提供商");
            return;
        }
        if (session == null) return;

        final String sessionId = session.id;
        final PetAiClient.CancelToken token = new PetAiClient.CancelToken();
        activeToken = token;
        pendingUserInput = text;
        streamingBuffer = new StringBuilder();
        enterGeneratingState();

        // 先显示用户气泡 + 空的桌宠消息块
        final PetStore.Turn liveTurn = new PetStore.Turn();
        liveTurn.userInput = text;
        liveTurn.at = System.currentTimeMillis();
        pendingTurn = liveTurn;

        if (panelAlive()) {
            addUserBubble(text);
            streamingText = createStreamingBubble(liveTurn);
        }

        final List<PetStore.Turn> history = new java.util.ArrayList<>(session.turns);
        final String persona = store.personaPrompt();
        final String memory = store.longTermMemoryForPrompt();

        executor.execute(() -> {
            PetAiClient.Result result;
            try {
                List<McpManager.ToolSpec> tools =
                        mcpManager == null ? new ArrayList<>() : mcpManager.availableTools();
                PetAiClient.StreamCallback cb = new PetAiClient.StreamCallback() {
                    @Override public void onReasoningDelta(String delta) {
                        liveTurn.reasoning = (liveTurn.reasoning == null ? "" : liveTurn.reasoning) + delta;
                        mainHandler.post(() -> {
                            if (panelAlive() && streamingText != null) {
                                streamingText.setText("思考中…\n" + liveTurn.reasoning);
                                // 思考过程同样需要跟随滚动，否则长文本会超出可视区
                                scrollToBottom();
                            }
                        });
                    }
                    @Override public void onContentDelta(String delta) {
                        streamingBuffer.append(delta);
                        mainHandler.post(() -> {
                            if (panelAlive() && streamingText != null) {
                                streamingText.setText(streamingBuffer.toString());
                                scrollToBottom();
                            }
                        });
                    }
                    @Override public void onToolCallStarted(String toolName, String title) {
                        mainHandler.post(() -> {
                            if (panelAlive() && streamingText != null) {
                                streamingText.setText("正在" + title + "…");
                                scrollToBottom();
                            }
                        });
                    }
                    @Override public void onToolCallFinished(String toolName, String title, String preview) {
                        // 工具卡片在最终渲染时统一呈现
                    }
                };
                result = PetAiClient.chatStream(provider, persona, history, memory,
                        text, tools, mcpManager, cb, token);
            } catch (Exception e) {
                String detail = e.getMessage();
                if (detail == null || detail.isEmpty()) detail = e.getClass().getSimpleName();
                android.util.Log.e("PetDialogue", "chat failed: " + detail, e);
                result = new PetAiClient.Result(false, "", PetContracts.VisualHint.NEUTRAL, detail, null);
            }
            final PetAiClient.Result res = result;
            mainHandler.post(() -> finishGeneration(sessionId, liveTurn, res, token));
        });
    }

    private String briefText(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(t.length() - max);
    }

    /** 生成收尾：写回会话并刷新界面（即使面板已关闭也保存）。 */
    private void finishGeneration(String sessionId, PetStore.Turn liveTurn,
                                  PetAiClient.Result res, PetAiClient.CancelToken token) {
        if (activeToken == token) {
            activeToken = null;
            exitGeneratingState();
        }

        // 无论面板是否打开，都写回会话，避免消息丢失
        boolean hasContent = res.ok && res.text != null && !res.text.trim().isEmpty();
        boolean hasPartial = streamingBuffer != null && streamingBuffer.length() > 0;

        if (hasContent || hasPartial) {
            PetStore.Turn turn = new PetStore.Turn();
            turn.userInput = liveTurn.userInput;
            turn.assistantText = hasContent ? res.text : streamingBuffer.toString();
            turn.visualHint = res.hint == null ? "NEUTRAL" : res.hint.name();
            turn.at = liveTurn.at;
            if (res.toolCalls != null) turn.toolCalls.addAll(res.toolCalls);
            if (res.toolEvents != null) turn.toolEvents.addAll(res.toolEvents);
            if (res.reasoning != null && !res.reasoning.isEmpty()) turn.reasoning = res.reasoning;
            else if (liveTurn.reasoning != null) turn.reasoning = liveTurn.reasoning;
            turn.promptTokens = res.promptTokens;
            turn.completionTokens = res.completionTokens;
            turn.elapsedMs = res.elapsedMs;
            store.appendTurn(sessionId, turn);

            if (res.memory != null && !res.memory.isEmpty()) {
                store.addLongTermMemory(res.memory);
            }
        }

        if (panelAlive()) {
            renderMessages();
            if (!hasContent && !hasPartial) {
                TextView err = new TextView(context);
                err.setText("调用失败：" + errorMessage(res.errorCode));
                err.setTextSize(13);
                err.setTextColor(0xFFB3261E);
                err.setPadding(dp(14), dp(10), dp(14), dp(10));
                messageList.addView(err);
                scrollToBottom();
            } else if (res.hint != null && listener != null) {
                listener.onCompleted(res.hint);
            }
        }

        streamingText = null;
        streamingBuffer = null;
        pendingTurn = null;
        pendingUserInput = null;
    }

    /** 创建正在流式输出的消息块，返回可更新的正文视图。 */
    private TextView createStreamingBubble(PetStore.Turn liveTurn) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        block.setLayoutParams(blp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(avatarView(dp(28)));
        TextView nameView = new TextView(context);
        nameView.setText(store.displayName());
        nameView.setTextSize(15);
        nameView.setTextColor(0xFF1B1B1F);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(8);
        header.addView(nameView, nlp);
        block.addView(header);

        TextView body = new TextView(context);
        body.setText("…");
        body.setTextSize(15);
        body.setTextColor(0xFF2A2A30);
        body.setPadding(dp(2), dp(6), dp(2), dp(2));
        block.addView(body);

        messageList.addView(block);
        forceScrollToBottom();
        return body;
    }

    private String errorMessage(String code) {
        if (code == null) return "请重试";
        switch (code) {
            case "pet_provider_unavailable": return "请先配置 AI 提供商";
            case "pet_input_too_long": return "输入内容过长";
            case "pet_empty_response": return "模型返回了空回应";
            case "pet_response_invalid": return "模型回应格式无效";
            default: return code;
        }
    }

    // ---------------- 历史面板 ----------------

    private void showHistoryDialog() {
        closeHistoryPanel();

        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(12), dp(12), dp(12), dp(12));
        shell.setBackground(rounded(0xFFFFFFFF, dp(24), 0x1A000000, dp(1)));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(6), dp(10), dp(6));
        scroll.addView(list);

        final List<PetStore.Session> all = store.sessions();
        TextView caption = new TextView(context);
        caption.setText("长按对话可更改标题或删除");
        caption.setTextSize(12);
        caption.setTextColor(0xFF9A9AA2);
        caption.setPadding(0, 0, 0, dp(8));
        list.addView(caption);

        if (all.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("还没有历史对话");
            empty.setTextSize(14);
            empty.setTextColor(0xFF8A8A93);
            empty.setPadding(0, dp(20), 0, dp(20));
            list.addView(empty);
        }

        String activeId = store.activeSessionId();
        for (final PetStore.Session s : all) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(rounded(0xFFF6F6FA, dp(14), 0x00000000, 0));

            TextView icon = new TextView(context);
            icon.setText("💬");
            icon.setTextSize(15);
            icon.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
            ilp.rightMargin = dp(10);
            row.addView(icon, ilp);

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            String title = (s.title == null || s.title.isEmpty()) ? "新对话" : s.title;
            TextView nameView = new TextView(context);
            nameView.setText((s.id.equals(activeId) ? "● " : "") + title);
            nameView.setTextSize(15);
            nameView.setTextColor(s.id.equals(activeId) ? 0xFF4C6EF5 : 0xFF2A2A30);
            col.addView(nameView);

            TextView meta = new TextView(context);
            meta.setText(s.turns.size() + " 条消息");
            meta.setTextSize(11);
            meta.setTextColor(0xFF9A9AA2);
            col.addView(meta);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final String sessionId = s.id;
            row.setOnClickListener(v -> {
                stopSpeaking();
                store.setActiveSessionId(sessionId);
                refreshContextLabel();
                renderMessages();
                closeHistoryPanel();
                toast("已切换对话：" + title);
            });
            row.setOnLongClickListener(v -> {
                showSessionOptions(sessionId);
                return true;
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(6);
            list.addView(row, rlp);
        }

        Button closeBtn = new Button(context);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(15);
        closeBtn.setTextColor(0xFF1B1B1F);
        closeBtn.setBackground(rounded(0xFFF2F2F7, dp(16), 0x00000000, 0));
        closeBtn.setOnClickListener(v -> closeHistoryPanel());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        list.addView(closeBtn, clp);

        shell.addView(scroll);
        int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.6f);
        shell.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O)
            type = WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM;
        params.y = dp(12);
        try {
            historyPanel = shell;
            windowManager.addView(historyPanel, params);
        } catch (Exception e) {
            historyPanel = null;
            toast("无法显示历史面板");
        }
    }

    private void closeHistoryPanel() {
        if (historyPanel != null) {
            try { windowManager.removeView(historyPanel); } catch (Exception ignored) {}
            historyPanel = null;
        }
    }

    // ---------------- 会话管理 ----------------

    private void showSessionOptions(final String sessionId) {
        closeActionPanel();
        actionPanel = buildActionPanel("管理对话",
                new String[]{"更改标题", "删除对话", "取消"}, which -> {
                    closeActionPanel();
                    if (which == 0) showRenamePanel(sessionId);
                    else if (which == 1) showDeleteConfirmPanel(sessionId);
                });
    }

    private View buildActionPanel(String title, String[] options,
                                  java.util.function.IntConsumer onPick) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        box.setBackground(rounded(0xFFFFFFFF, dp(24), 0x1A000000, dp(1)));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF1B1B1F);
        titleView.setPadding(0, 0, 0, dp(6));
        box.addView(titleView);

        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView item = new TextView(context);
            item.setText(options[i]);
            item.setTextSize(15);
            item.setTextColor(idx == options.length - 1 ? 0xFF8A8A93 : 0xFF2A2A30);
            item.setPadding(0, dp(14), 0, dp(14));
            item.setOnClickListener(v -> onPick.accept(idx));
            box.addView(item);
        }
        return attachOverlay(box);
    }

    private void showRenamePanel(final String sessionId) {
        closeActionPanel();

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        box.setBackground(rounded(0xFFFFFFFF, dp(24), 0x1A000000, dp(1)));

        TextView titleView = new TextView(context);
        titleView.setText("更改标题");
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF1B1B1F);
        box.addView(titleView);

        String current = "";
        for (PetStore.Session item : store.sessions()) {
            if (item.id.equals(sessionId)) current = item.title;
        }

        final EditText field = new EditText(context);
        field.setText(current);
        field.setHint("默认标题：新对话");
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setPadding(dp(4), dp(12), dp(4), dp(12));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        box.addView(field, flp);

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);
        btnRow.addView(textButton("取消", 0xFF6B6B74, v -> closeActionPanel()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(textButton("保存", 0xFF4C6EF5, v -> {
            String t = field.getText().toString().trim();
            store.renameSession(sessionId, t.isEmpty() ? "新对话" : t);
            closeActionPanel();
            refreshContextLabel();
            toast("已保存");
            closeHistoryPanel();
            showHistoryDialog();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(btnRow);

        actionPanel = attachOverlay(box);
        field.requestFocus();
    }

    private void showDeleteConfirmPanel(final String sessionId) {
        closeActionPanel();

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        box.setBackground(rounded(0xFFFFFFFF, dp(24), 0x1A000000, dp(1)));

        TextView titleView = new TextView(context);
        titleView.setText("删除对话");
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF1B1B1F);
        box.addView(titleView);

        TextView msg = new TextView(context);
        msg.setText("删除后无法恢复，确定吗？");
        msg.setTextSize(14);
        msg.setTextColor(0xFF6B6B74);
        msg.setPadding(0, dp(8), 0, 0);
        box.addView(msg);

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);
        btnRow.addView(textButton("取消", 0xFF6B6B74, v -> closeActionPanel()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(textButton("删除", 0xFFB3261E, v -> {
            store.deleteSession(sessionId);
            closeActionPanel();
            closeHistoryPanel();
            refreshContextLabel();
            renderMessages();
            toast("已删除");
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(btnRow);

        actionPanel = attachOverlay(box);
    }

    private TextView textButton(String text, int color, View.OnClickListener l) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(color);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setOnClickListener(l);
        return v;
    }

    private View attachOverlay(View content) {
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O)
            type = WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM;
        params.y = dp(12);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        android.widget.FrameLayout wrap = new android.widget.FrameLayout(context);
        wrap.setPadding(dp(12), dp(12), dp(12), dp(12));
        wrap.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        try {
            windowManager.addView(wrap, params);
            return wrap;
        } catch (Exception e) {
            return null;
        }
    }

    private void closeActionPanel() {
        if (actionPanel != null) {
            try { windowManager.removeView(actionPanel); } catch (Exception ignored) {}
            actionPanel = null;
        }
    }
}
