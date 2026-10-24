package me.rerere.pet.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 桌宠旁的对话气泡。
 * 用于互动反馈等一次性短消息，不展示思考过程与工具调用。
 * 位置跟随桌宠，显示若干秒后自动淡出。
 */
public final class PetSpeechBubble {

    /** 连点停止回调。 */
    public interface StopListener {
        void onStopRequested();
    }

    /** 连点阈值：2.5 秒内五次。 */
    private static final int STOP_TAP_COUNT = 5;
    private static final long STOP_TAP_WINDOW_MS = 2_500L;

    private StopListener stopListener;
    private int stopTaps = 0;
    private long firstTapAt = 0L;

    /** 最短显示时长。 */
    private static final long MIN_DURATION_MS = 5_000L;
    /** 每个字符额外停留时间（估算阅读速度）。 */
    private static final long PER_CHAR_MS = 180L;
    /** 最长显示时长。 */
    private static final long MAX_DURATION_MS = 20_000L;

    private final Context context;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View bubbleView;
    private TextView textView;
    /** 气泡外壳（负责背景+padding）。高度限制要同时作用在它和 textView 上。 */
    private LinearLayout boxView;
    private int petX, petY, petW, petH;
    private final Runnable hideRunnable = this::hide;

    public PetSpeechBubble(Context context, WindowManager windowManager) {
        this.context = context.getApplicationContext();
        this.windowManager = windowManager;
    }

    /** 显示"正在思考"。 */
    public void setStopListener(StopListener l) { this.stopListener = l; }

    public void showThinking() {
        showText("…", 0L);
    }

    /** 流式更新内容（不加隐藏计时，避免输出未完成就消失）。 */
    public void update(String text) {
        handler.removeCallbacks(hideRunnable);
        showTextInternal(text);
    }

    /**
     * 显示已完成文本，按内容长度自适应停留时长后自动隐藏。
     * 输出完成才调用，保证内容不会被中途截断。
     */
    public void show(String text) {
        long duration = durationFor(text);
        showText(text, duration);
    }

    /** 按字数估算停留时长，长文本看得更久。 */
    private static long durationFor(String text) {
        int len = text == null ? 0 : text.codePointCount(0, text.length());
        long ms = MIN_DURATION_MS + (long) len * PER_CHAR_MS;
        return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, ms));
    }

    private void showText(String text, long autoHideMs) {
        showTextInternal(text);
        if (autoHideMs > 0 && bubbleView != null) {
            handler.postDelayed(hideRunnable, autoHideMs);
        }
    }

    private void showTextInternal(String text) {
        try {
            handler.removeCallbacks(hideRunnable);
            if (bubbleView == null) build();
            if (bubbleView == null) return;
            textView.setText(text == null ? "" : text);

            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) bubbleView.getLayoutParams();
            applyPosition(params);
            windowManager.updateViewLayout(bubbleView, params);
        } catch (Throwable t) {
            android.util.Log.e("PetSpeechBubble", "show failed", t);
            bubbleView = null;
        }
    }

    /** 桌宠移动/缩放时同步气泡位置。 */
    public void followPet(int x, int y, int w, int h) {
        this.petX = x;
        this.petY = y;
        this.petW = w;
        this.petH = h;
        if (bubbleView == null) return;
        try {
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) bubbleView.getLayoutParams();
            applyPosition(params);
            windowManager.updateViewLayout(bubbleView, params);
        } catch (Throwable ignored) {}
    }

    /**
     * 气泡最大高度（占屏幕高度的比例）。
     * ★ 必须能调：写死 maxLines=4 时模型偶尔吐长句会盖住半个屏幕，
     *   而这个值又没法对所有屏幕/字号都合适，所以交给用户（主页滑块）。
     */
    private float heightPct = 0.32f;
    /** 最小高度比例（短句也会撑到这个高度，让滑块两头都看得见）。 */
    private float minPct = 0.12f;

    /**
     * 用户改了"气泡高度"滑块后调用。
     *
     * ★ 关键：即使当前没有气泡（bubbleView == null）也必须记住这个值 ——
     *   下次 build() 时会用它。旧版这里直接 return，于是"没气泡时调滑块"
     *   看起来完全没反应（实测"气泡高度没正确应用"）。
     */
    /** 已废弃：气泡高度改为固定行为（回滚到最初实现）。保留接口避免调用方改动。 */
    public void setHeightPct(float pct) {
        this.heightPct = pct;
    }

    /** 已废弃：气泡不再有最小高度。 */
    public void setMinPct(float pct) {
        this.minPct = pct;
    }

    /**
     * 把气泡放到桌宠上方居中。
     *
     * ══════════════════════════════════════════════════════════════
     * 气泡高度为什么以前调不动
     * ══════════════════════════════════════════════════════════════
     *   旧做法只在 TextView 上 setMaxHeight，而窗口是 WRAP_CONTENT。
     *   问题：TextView 的宽度只有 240dp-28dp，长句会先折成很高，
     *   再被 maxHeight 裁 —— 看起来"高度上限失灵"，实际是：
     *     · 短句时高度由文字决定，滑块调大完全没变化；
     *     · 长句时被裁得很难看（从中间截断，没有省略号提示）。
     *
     *   现在改成：
     *     ① 窗口用【固定最大高度】(params.height = maxH)，内容超出时
     *        TextView 自己滚动 —— 高度严格受滑块控制；
     *     ② 气泡定位用真实高度算，能正确放在桌宠上方；
     *     ③ 宽度也放宽（240dp → 屏宽的 62%），长句少折行，
     *        同样的高度能显示更多内容。
     */
    /** 把气泡放到桌宠上方居中（尺寸/行数维持原样，不再受滑块影响）。 */
    private void applyPosition(WindowManager.LayoutParams params) {
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(120), Math.min(screenW - dp(32), dp(240)));
        params.width = width;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;

        // 相对桌宠水平居中
        int x = petX + (petW - width) / 2;
        params.x = Math.max(dp(8), Math.min(x, screenW - width - dp(8)));
        // 放在桌宠上方，若顶部空间不足则放到下方
        int above = petY - dp(56);
        params.y = above > dp(40) ? above : petY + petH + dp(8);
    }

    private void build() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF7FFFFFF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x1A000000);
        box.setBackground(bg);

        textView = new TextView(context);
        textView.setTextSize(14);
        textView.setTextColor(0xFF1B1B1F);
        /* 不再用固定 maxLines：改由 setHeightPct 换算成像素上限，
           这样"字号 + 屏幕"变化时表现一致（maxLines 在超大字号下会溢出）。 */
        textView.setMaxLines(4);
        textView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(textView);

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O)
            type = WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // 不可触摸，避免挡住桌宠的点击与拖动
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        applyPosition(params);   // ★ 首帧就按用户设置的高度排好

        box.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                registerStopTap();
            }
            return true;
        });

        boxView = box;
        try {
            windowManager.addView(box, params);
            bubbleView = box;
        } catch (Throwable t) {
            android.util.Log.e("PetSpeechBubble", "addView failed", t);
            bubbleView = null;
        }
    }

    /** 记录一次点击；2.5 秒内累计五次则请求停止。 */
    private void registerStopTap() {
        long now = System.currentTimeMillis();
        if (stopTaps == 0 || now - firstTapAt > STOP_TAP_WINDOW_MS) {
            stopTaps = 1;
            firstTapAt = now;
        } else {
            stopTaps++;
        }
        if (stopTaps >= STOP_TAP_COUNT) {
            stopTaps = 0;
            firstTapAt = 0L;
            if (stopListener != null) stopListener.onStopRequested();
        }
    }

    /** 重置连点计数（新一次互动开始时调用）。 */
    public void resetStopTaps() {
        stopTaps = 0;
        firstTapAt = 0L;
    }

    /** 隐藏气泡。 */
    public void hide() {
        handler.removeCallbacks(hideRunnable);
        if (bubbleView != null) {
            try { windowManager.removeView(bubbleView); } catch (Throwable ignored) {}
            bubbleView = null;
            textView = null;
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }
}
