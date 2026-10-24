package me.rerere.pet.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import me.rerere.pet.ui.theme.UiTheme;

/**
 * 极简线性图标（自绘，不依赖任何图片资源 / vector drawable）。
 *
 * 为什么不用 drawable / vector：
 *   底栏三个图标如果走资源，就得到 res/drawable 里维护 3 份 pathData，
 *   以后加一个 Tab 又要加一份资源 + 一份 state list。这里用 Canvas 画，
 *   加图标只是在 switch 里加一个 case，颜色也能跟着主题一起插值。
 *
 * 只用 stroke，2dp 线宽、圆头圆角，整体是细线条的观感。
 */
public final class IconView extends View {

    public static final int HOME = 0;
    public static final int SLIDERS = 1;
    public static final int SPARK = 2;
    public static final int CHAT = 3;
    public static final int GRID = 4;
    public static final int PALETTE = 5;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int type = HOME;
    private int color = UiTheme.TEXT_PRIMARY;

    public IconView(Context c) { this(c, null); }

    public IconView(Context c, AttributeSet attrs) {
        super(c, attrs);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    public IconView setType(int type) { this.type = type; invalidate(); return this; }

    public IconView setIconColor(int color) { this.color = color; invalidate(); return this; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int size = UiTheme.dp(getContext(), 22f);
        setMeasuredDimension(resolveSize(size, wSpec), resolveSize(size, hSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float stroke = UiTheme.dp(getContext(), 1.8f);
        p.setColor(color);
        p.setStrokeWidth(stroke);
        p.setStyle(Paint.Style.STROKE);

        float inset = stroke;
        float l = inset, t = inset, r = w - inset, b = h - inset;
        float cx = w / 2f;
        path.reset();

        switch (type) {
            case SLIDERS: {
                /* 三条横线 + 各一个滑钮（"设置/参数"语义） */
                float[] ys = {t + (b - t) * 0.18f, (t + b) / 2f, b - (b - t) * 0.18f};
                float[] kx = {l + (r - l) * 0.68f, l + (r - l) * 0.34f, l + (r - l) * 0.6f};
                for (int i = 0; i < 3; i++) {
                    canvas.drawLine(l, ys[i], r, ys[i], p);
                    p.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(kx[i], ys[i], stroke * 1.5f, p);
                    p.setStyle(Paint.Style.STROKE);
                }
                break;
            }
            case SPARK: {
                /* 四角星：向内收的菱形，代表 AI */
                float k = (r - l) * 0.34f;
                float x0 = cx, y0 = t, x1 = r, y1 = (t + b) / 2f;
                float x2 = cx, y2 = b, x3 = l, y3 = (t + b) / 2f;
                path.moveTo(x0, y0);
                path.quadTo(cx + k * 0.2f, (t + b) / 2f - k * 0.2f, x1, y1);
                path.quadTo(cx + k * 0.2f, (t + b) / 2f + k * 0.2f, x2, y2);
                path.quadTo(cx - k * 0.2f, (t + b) / 2f + k * 0.2f, x3, y3);
                path.quadTo(cx - k * 0.2f, (t + b) / 2f - k * 0.2f, x0, y0);
                path.close();
                canvas.drawPath(path, p);
                break;
            }
            case CHAT: {
                float rr = (r - l) * 0.16f;
                path.addRoundRect(l, t, r, b - (b - t) * 0.18f, rr, rr, Path.Direction.CW);
                canvas.drawPath(path, p);
                path.reset();
                path.moveTo(l + (r - l) * 0.3f, b - (b - t) * 0.18f);
                path.lineTo(l + (r - l) * 0.3f, b);
                path.lineTo(l + (r - l) * 0.5f, b - (b - t) * 0.18f);
                canvas.drawPath(path, p);
                break;
            }
            case GRID: {
                float gap = (r - l) * 0.12f;
                float cell = ((r - l) - gap) / 2f;
                float rr2 = cell * 0.25f;
                for (int iy = 0; iy < 2; iy++) {
                    for (int ix = 0; ix < 2; ix++) {
                        float x = l + ix * (cell + gap);
                        float y = t + iy * (cell + gap);
                        path.reset();
                        path.addRoundRect(x, y, x + cell, y + cell, rr2, rr2,
                                Path.Direction.CW);
                        canvas.drawPath(path, p);
                    }
                }
                break;
            }
            case PALETTE: {
                /* 主题图标：外圈 + 右半实心（明暗各半，调色/外观的通用符号）。
                   之前画"外圈+3 个点"在 22dp 下看起来像笑脸，已换。 */
                float cr = (r - l) / 2f;
                float ccy = (t + b) / 2f;
                canvas.drawCircle(cx, ccy, cr, p);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.addArc(new android.graphics.RectF(cx - cr, ccy - cr, cx + cr, ccy + cr),
                        -90f, 180f);
                path.close();
                canvas.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                break;
            }
            case HOME:
            default: {
                /* 屋顶 */
                path.moveTo(l, t + (b - t) * 0.44f);
                path.lineTo(cx, t);
                path.lineTo(r, t + (b - t) * 0.44f);
                canvas.drawPath(path, p);
                /* 屋身 + 门 */
                path.reset();
                float bx0 = l + (r - l) * 0.16f;
                float bx1 = r - (r - l) * 0.16f;
                path.moveTo(bx0, t + (b - t) * 0.34f);
                path.lineTo(bx0, b);
                path.lineTo(bx1, b);
                path.lineTo(bx1, t + (b - t) * 0.34f);
                canvas.drawPath(path, p);
                path.reset();
                float dw = (bx1 - bx0) * 0.3f;
                path.moveTo((bx0 + bx1) / 2f - dw / 2f, b);
                path.lineTo((bx0 + bx1) / 2f - dw / 2f, b - (b - t) * 0.24f);
                path.lineTo((bx0 + bx1) / 2f + dw / 2f, b - (b - t) * 0.24f);
                path.lineTo((bx0 + bx1) / 2f + dw / 2f, b);
                canvas.drawPath(path, p);
                break;
            }
        }
    }
}
