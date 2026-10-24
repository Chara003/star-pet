package me.rerere.pet.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;

import me.rerere.pet.core.CodexPetAtlas;
import me.rerere.pet.core.PetContracts;

/**
 * 桌宠精灵视图：按 fps 播放帧动画，支持当前动作/帧查询与 alpha 命中。
 */
public final class PetSpriteView extends View {

    public interface ActionListener {
        void onActionFinished(PetContracts.PetAction action);
    }

    private CodexPetAtlas atlas;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destRect = new RectF();

    private PetContracts.PetAction action = PetContracts.PetAction.IDLE;
    private int frameIndex = 0;
    private int fps = 6;
    private float scale = 1.0f;
    /** 辅助线：-1 表示不显示；否则为 0..1 归一化高度。 */
    private float guideLineA = -1f;
    private float guideLineB = -1f;
    private boolean mirrorX = false;
    private long actionStartedAtNanos = 0;
    private boolean looping = true;
    private ActionListener actionListener;

    private final Choreographer.FrameCallback ticker = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            // 统一用 Choreographer 的纳秒时间轴，避免与 currentTimeMillis 混用导致定格。
            advanceNanos(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private boolean running = false;

    public PetSpriteView(Context context) {
        this(context, null);
    }

    public PetSpriteView(Context context, android.util.AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PetSpriteView(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        shadowPaint.setColor(0x22000000);
    }

    /**
     * 当前有没有可画的图集。
     *
     * ★ 用途：没有素材时不该消费触摸。
     *   公开仓库版不带任何 .codex-pet.zip 素材，首次启动就是这个状态；
     *   虽然精灵图窗口不大，但让一块点不动的透明区域浮在桌面上很莫名。
     */
    public boolean hasContent() {
        return atlas != null && atlas.isValid();
    }

    public void setAtlas(CodexPetAtlas atlas) {
        this.atlas = atlas;
        if (atlas != null) {
            this.frameIndex = 0;
            this.actionStartedAtNanos = System.nanoTime();
        }
        invalidate();
    }

    public void setAction(PetContracts.PetAction action, boolean loop) {
        if (this.action != action || frameIndex == 0) {
            this.action = action;
            this.looping = loop;
            this.frameIndex = 0;
            this.actionStartedAtNanos = System.nanoTime();
            invalidate();
        }
    }

    public void setScale(float scale) {
        this.scale = Math.max(0.05f, Math.min(3.0f, scale));
        requestLayout();
    }

    /** 按图集单帧比例测量，避免被容器拉伸变形。 */
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int base = (int) (me.rerere.pet.core.PetContracts.CODEX_FRAME_WIDTH * scale);
        int w = resolveSize(base, widthMeasureSpec);
        int h = (int) ((float) w * me.rerere.pet.core.PetContracts.CODEX_FRAME_HEIGHT
                / me.rerere.pet.core.PetContracts.CODEX_FRAME_WIDTH);
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec));
    }

    public void setFps(int fps) {
        this.fps = Math.max(1, Math.min(30, fps));
    }

    public void setMirrorX(boolean mirror) {
        if (this.mirrorX != mirror) { this.mirrorX = mirror; invalidate(); }
    }

    public void setActionListener(ActionListener l) { this.actionListener = l; }

    public PetContracts.PetAction currentAction() { return action; }
    public int currentFrame() { return frameIndex; }
    public boolean isLooping() { return looping; }
    public long actionElapsedMs() { return (System.nanoTime() - actionStartedAtNanos) / 1_000_000L; }

    public void resume() {
        if (running) return;
        running = true;
        Choreographer.getInstance().postFrameCallback(ticker);
    }

    public void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(ticker);
    }

    private void advanceNanos(long nowNanos) {
        if (atlas == null || !atlas.isValid()) return;
        int count = atlas.frameCount(action);
        long elapsedMs = (nowNanos - actionStartedAtNanos) / 1_000_000L;
        if (elapsedMs < 0) elapsedMs = 0;
        long frameDuration = Math.max(1L, 1000L / fps);
        int idx = (int) (elapsedMs / frameDuration);
        if (looping) {
            frameIndex = Math.floorMod(idx, count);
        } else if (idx >= count) {
            frameIndex = count - 1;
            if (actionListener != null) {
                actionListener.onActionFinished(action);
                actionListener = null;
            }
        } else {
            frameIndex = Math.max(0, Math.min(idx, count - 1));
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        /* 绘制前必须校验图集仍可用：换素材时旧 bitmap 会被 recycle，
           而这中间系统可能已经排了一帧绘制（崩溃栈见 CodexPetAtlas.isValid 注释）。 */
        if (atlas == null || !atlas.isValid()) return;
        destRect.set(0f, 0f, getWidth(), getHeight());

        atlas.drawRow(canvas, CodexPetAtlas.ACTION_ROWS[action.ordinal()][0],
                frameIndex, atlas.frameCount(action), destRect, mirrorX);
        drawGuides(canvas);
    }

    /** 用醒目的红色虚线标出头部/身体分界，方便用户校准分区。 */
    private void drawGuides(Canvas canvas) {
        if (guideLineA < 0f && guideLineB < 0f) return;
        float w = getWidth();
        float h = getHeight();
        Paint p = guidePaint();
        if (guideLineA >= 0f) drawDashLine(canvas, p, 0f, h * guideLineA, w, h * guideLineA);
        if (guideLineB >= 0f) drawDashLine(canvas, p, 0f, h * guideLineB, w, h * guideLineB);
    }

    private void drawDashLine(Canvas canvas, Paint p, float x1, float y1, float x2, float y2) {
        android.graphics.DashPathEffect effect =
                new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0f);
        p.setPathEffect(effect);
        p.setStrokeWidth(dp(2));
        canvas.drawLine(x1, y1, x2, y2, p);
        p.setPathEffect(null);
        p.setStrokeWidth(dp(1));
        // 实线叠加，保证在任何背景上都清晰
        canvas.drawLine(x1, y1, x2, y2, p);
    }

    private Paint guidePaint() {
        if (guidePaint == null) {
            guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            guidePaint.setColor(0xFFE53935);
            guidePaint.setStyle(Paint.Style.STROKE);
        }
        return guidePaint;
    }

    private Paint guidePaint;

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 显示/隐藏分区辅助线（传 -1 隐藏）。 */
    public void setGuideLines(float a, float b) {
        this.guideLineA = a;
        this.guideLineB = b;
        invalidate();
    }

    public void clearGuideLines() {
        this.guideLineA = -1f;
        this.guideLineB = -1f;
        invalidate();
    }

    /** 把视图内 y 坐标换算成精灵图内的 0..1 归一化高度，用于头/身/脚分区判定。 */
    public float normalizedYFor(float viewY) {
        float height = getHeight();
        if (height <= 0f) return 0f;
        return viewY / height;
    }

    /** 按当前帧 alpha 判定是否命中实体（透明像素穿透）。 */
    public boolean hitTest(float x, float y) {
        if (atlas == null) return false;
        RectF r = new RectF(0f, 0f, getWidth(), getHeight());
        if (!r.contains(x, y)) return false;
        int row = CodexPetAtlas.ACTION_ROWS[action.ordinal()][0];
        int count = atlas.frameCount(action);
        int column = Math.floorMod(frameIndex, count);

        int sx = column * PetContracts.CODEX_FRAME_WIDTH;
        int sy = row * PetContracts.CODEX_FRAME_HEIGHT;
        float fx = (x - r.left) / r.width();
        float fy = (y - r.top) / r.height();
        if (mirrorX) fx = 1f - fx;
        int px = sx + (int) (fx * PetContracts.CODEX_FRAME_WIDTH);
        int py = sy + (int) (fy * PetContracts.CODEX_FRAME_HEIGHT);
        px = Math.max(sx, Math.min(sx + PetContracts.CODEX_FRAME_WIDTH - 1, px));
        py = Math.max(sy, Math.min(sy + PetContracts.CODEX_FRAME_HEIGHT - 1, py));
        return atlas.isOpaqueAt(action, frameIndex,
                x - r.left, y - r.top, r.width(), r.height(), 16);
    }

    @Override protected void onDetachedFromWindow() {
        pause();
        super.onDetachedFromWindow();
    }
}
