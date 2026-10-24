package me.rerere.pet.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;

/**
 * Codex 精灵图集：单帧 192x208，8 列，V1=9 行 / V2=11 行。
 * 行序固定：0待机 1向右跑 2向左跑 3挥手 4跳跃 5失败 6等待 7工作 8审阅。
 * 帧数按"连续非空列"自动探测，兼容不满 8 帧的图集。
 */
public final class CodexPetAtlas {

    /** 动作 -> 行号 的固定映射。 */
    public static final int[][] ACTION_ROWS = {
            {0}, // IDLE
            {1}, // MOVE_RIGHT
            {2}, // MOVE_LEFT
            {3}, // WAVE
            {4}, // JUMP
            {5}, // FAILURE
            {6}, // WAIT
            {7}, // WORK
            {8}, // REVIEW
    };

    private static final int[] DEFAULT_FRAMES = {6, 8, 8, 4, 5, 8, 6, 6, 6};

    private final Bitmap bitmap;
    private final PetContracts.CodexPetVersion version;
    private final int[] frameCounts = new int[9];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private CodexPetAtlas(Bitmap bitmap, PetContracts.CodexPetVersion version, boolean strictCodex) {
        this.bitmap = bitmap;
        this.version = version;
        for (int i = 0; i < 9; i++) {
            int detected = strictCodex ? DEFAULT_FRAMES[i] : detectContinuousFrames(i);
            frameCounts[i] = detected > 0 ? detected : DEFAULT_FRAMES[i];
        }
    }

    public int frameCount(PetContracts.PetAction action) {
        return frameCounts[action.ordinal()];
    }

    public int frameCountAtRow(int row) {
        if (row >= 0 && row < 9) return frameCounts[row];
        int detected = detectContinuousFrames(row);
        return detected > 0 ? detected : 1;
    }

    public PetContracts.CodexPetVersion version() { return version; }

    public Rect sourceRect(PetContracts.PetAction action, int frame) {
        int row = ACTION_ROWS[action.ordinal()][0];
        return rectAt(row, frame, frameCount(action));
    }

    public Rect rectAt(int row, int frame, int count) {
        int n = Math.max(1, count);
        int column = Math.floorMod(frame, n);
        int left = column * PetContracts.CODEX_FRAME_WIDTH;
        int top = row * PetContracts.CODEX_FRAME_HEIGHT;
        return new Rect(left, top,
                left + PetContracts.CODEX_FRAME_WIDTH,
                top + PetContracts.CODEX_FRAME_HEIGHT);
    }

    public void draw(Canvas canvas, PetContracts.PetAction action, int frame, RectF dest) {
        if (!isValid()) return;
        canvas.drawBitmap(bitmap, sourceRect(action, frame), dest, paint);
    }

    public void drawRow(Canvas canvas, int row, int frame, int count, RectF dest, boolean mirrorX) {
        /* 双保险：即使调用方忘了判 isValid()，也绝不会把 recycled bitmap 交给 Canvas */
        if (!isValid()) return;
        Rect src = rectAt(row, frame, count);
        if (mirrorX) {
            int save = canvas.save();
            canvas.scale(-1f, 1f, dest.centerX(), dest.centerY());
            canvas.drawBitmap(bitmap, src, dest, paint);
            canvas.restoreToCount(save);
        } else {
            canvas.drawBitmap(bitmap, src, dest, paint);
        }
    }

    /** 命中测试用当前帧的 alpha；透明像素穿透。 */
    public boolean isOpaqueAt(PetContracts.PetAction action, int frame,
                               float localX, float localY, float renderedW, float renderedH,
                               int alphaThreshold) {
        if (renderedW <= 0f || renderedH <= 0f
                || localX < 0f || localX >= renderedW
                || localY < 0f || localY >= renderedH) return false;
        Rect s = sourceRect(action, frame);
        int x = s.left + (int) (localX / renderedW * s.width());
        x = Math.max(s.left, Math.min(s.left + s.width() - 1, x));
        int y = s.top + (int) (localY / renderedH * s.height());
        y = Math.max(s.top, Math.min(s.top + s.height() - 1, y));
        return (bitmap.getPixel(x, y) >>> 24) >= alphaThreshold;
    }

    private int detectContinuousFrames(int row) {
        int count = 0;
        for (int column = 0; column < PetContracts.CODEX_ATLAS_COLUMNS; column++) {
            if (!hasVisiblePixel(column, row)) break;
            count++;
        }
        return count;
    }

    private boolean hasVisiblePixel(int column, int row) {
        int left = column * PetContracts.CODEX_FRAME_WIDTH;
        int top = row * PetContracts.CODEX_FRAME_HEIGHT;
        if (left + PetContracts.CODEX_FRAME_WIDTH > bitmap.getWidth()) return false;
        if (top + PetContracts.CODEX_FRAME_HEIGHT > bitmap.getHeight()) return false;
        int[] pixels = new int[PetContracts.CODEX_FRAME_WIDTH * PetContracts.CODEX_FRAME_HEIGHT];
        bitmap.getPixels(pixels, 0, PetContracts.CODEX_FRAME_WIDTH,
                left, top, PetContracts.CODEX_FRAME_WIDTH, PetContracts.CODEX_FRAME_HEIGHT);
        for (int p : pixels) if ((p >>> 24) >= 8) return true;
        return false;
    }

    /**
     * 图集是否还能画。
     *
     * ★★ 为什么需要这个判断（2026-10-17 实战崩溃）★★
     *   崩溃栈：
     *     java.lang.RuntimeException: Canvas: trying to use a recycled bitmap
     *       at CodexPetAtlas.drawRow(CodexPetAtlas.java:88)
     *       at PetSpriteView.onDraw(PetSpriteView.java:150)
     *   原因：loadPet() 里先 atlas.close()（recycle 掉旧 bitmap）再解码新图集，
     *   而 PetSpriteView 仍持有旧图集引用；这中间只要系统绘制一帧就崩。
     *   （切换渲染模式会重启服务 -> 立刻走一次 loadPet，所以必现。）
     *   → 绘制前必须校验，绝不能假设"我持有引用就一定能画"。
     */
    public boolean isValid() {
        return bitmap != null && !bitmap.isRecycled();
    }

    public void close() {
        try {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        } catch (Throwable ignored) {}
    }

    /** 解码并严格校验尺寸。 */
    public static CodexPetAtlas decode(File file, PetContracts.CodexPetVersion version, boolean strict) {
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp == null) throw new IllegalStateException("pet_spritesheet_decode_failed");
        int expectW = PetContracts.CODEX_FRAME_WIDTH * PetContracts.CODEX_ATLAS_COLUMNS;
        int expectH = PetContracts.CODEX_FRAME_HEIGHT * version.rows();
        if (bmp.getWidth() != expectW || bmp.getHeight() != expectH) {
            bmp.recycle();
            throw new IllegalStateException("pet_spritesheet_dimensions_invalid");
        }
        return new CodexPetAtlas(bmp, version, strict);
    }
}