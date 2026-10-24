package me.rerere.pet.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

/**
 * 分区辅助线覆盖层。
 * 用于 MMD 模式——那里没有 PetSpriteView 可以画线，
 * 需要额外叠一层 View 来显示头部/身体分界。
 */
public final class GuideLineOverlay extends View {

    /** -1 表示不显示。 */
    private float lineA = -1f;
    private float lineB = -1f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GuideLineOverlay(Context context) {
        super(context);
        paint.setColor(0xFFE53935);
        paint.setStyle(Paint.Style.STROKE);
        solidPaint.setColor(0xFFE53935);
        solidPaint.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    public void setLines(float a, float b) {
        this.lineA = a;
        this.lineB = b;
        invalidate();
    }

    public void clear() {
        this.lineA = -1f;
        this.lineB = -1f;
        invalidate();
    }

    public boolean isShowing() { return lineA >= 0f || lineB >= 0f; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lineA < 0f && lineB < 0f) return;

        float w = getWidth();
        float h = getHeight();
        float stroke = Math.max(2f, dp(2f));

        paint.setStrokeWidth(stroke);
        paint.setPathEffect(new DashPathEffect(new float[]{dp(7), dp(5)}, 0f));
        solidPaint.setStrokeWidth(stroke);
        solidPaint.setPathEffect(null);

        if (lineA >= 0f) drawLine(canvas, h * lineA, w);
        if (lineB >= 0f) drawLine(canvas, h * lineB, w);
    }

    /** 虚线 + 实线叠加，保证在任何背景上都清晰。 */
    private void drawLine(Canvas canvas, float y, float w) {
        canvas.drawLine(0f, y, w, y, paint);
        canvas.drawLine(0f, y, w, y, solidPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
