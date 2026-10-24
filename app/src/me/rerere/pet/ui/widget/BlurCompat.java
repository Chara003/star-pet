package me.rerere.pet.ui.widget;

import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 背景模糊兼容层（底栏"毛玻璃"效果）。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么不是随便加个 Blur
 * ══════════════════════════════════════════════════════════════
 * 用户之前明确要求「不要液态玻璃（低版本安卓兼容问题）」，现在又说
 * "底栏真的挺难看清的"—— 这两件事其实不冲突：
 *   · 当初反对的是【靠 RenderEffect 自己画模糊】，那玩意 API 31+ 才有，
 *     低版本直接什么都不显示，而且同屏还有 WebGL 桌宠，掉帧明显。
 *   · 现在要的是【看得清】。真正的做法是让系统替我们做背景模糊：
 *     API 31+ 的 View.setBackgroundBlurRadius() 会把该 View 背后的
 *     窗口内容做高斯模糊，代价在合成器那边，不占 App 主线程。
 *
 * ══════════════════════════════════════════════════════════════
 * 降级策略（必须无损）
 * ══════════════════════════════════════════════════════════════
 *   API 31+ ：半透明白胶囊 + 系统背景模糊（能看见底下内容在动，但看不清细节）
 *   API <31 ：几乎不透明的白胶囊（0xF7FFFFFF），仍然清晰可读，
 *             只是没有"透过去"的观感。
 *   两条路都不会出现「模糊算不出来 → 一片灰」的情况。
 *
 * ★ 为什么模糊调用要放进静态内部类
 *   直接在主类方法里调 setBackgroundBlurRadius 会让低版本 ART 在类验证阶段
 *   就去找这个方法。放进只在 SDK>=31 时才加载的内部类里最保险
 *   （本项目见过低版本 VerifyError 的真实案例）。
 */
public final class BlurCompat {

    private BlurCompat() {}

    /** 是否支持真正的背景模糊。 */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    /**
     * 给一个 View 套上圆角 + 背景模糊。
     *
     * @param radiusPx        模糊半径（像素，越大越糊）
     * @param cornerRadiusPx  View 的圆角（用于裁剪模糊区域，避免方角溢出）
     */
    /**
     * 给一个 View 套上圆角 + 背景模糊。
     *
     * ★★ 几何必须用「胶囊实际画在哪儿」，不能用 View 全尺寸 ★★
     *   ---- 事故（实测"底栏被拉长了，和胶囊的弧度对不上"）----
     *   旧实现：
     *       outline.setRoundRect(0, 0, getWidth(), getHeight(), h/2)
     *   而胶囊实际画在 inset 5dp 之内（58dp 高、半径 29dp），
     *   View 本身是 68dp 高、圆角却只有 29dp —— 两者差 5dp，
     *   setClipToOutline(true) 一裁，胶囊上下就被削掉一块，
     *   并且圆角比例不对，看起来"被拉长、弧度变方"。
     *   → outline 必须和 onDraw 里那个 RectF 完全一致。
     *
     * @param insetPx        胶囊相对 View 的内缩（与 onDraw 的 BG_INSET 相同）
     * @param cornerRadiusPx 胶囊圆角（= (高度 - 2*inset) / 2）
     */
    public static void applyRoundedBlur(final View v, final int radiusPx,
                                        final int insetPx, final int cornerRadiusPx) {
        if (v == null) return;
        try {
            /* outline 无论是否支持模糊都要设对：它同时用于 clipToOutline 裁剪 */
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(insetPx, insetPx,
                            Math.max(insetPx + 1, view.getWidth() - insetPx),
                            Math.max(insetPx + 1, view.getHeight() - insetPx),
                            cornerRadiusPx);
                }
            });
            v.setClipToOutline(true);

            if (!supported()) {
                /* 低版本：没有背景模糊，但裁剪/圆角仍然要正确 */
                return;
            }
            if (!Api31.setBlur(v, radiusPx)) {
                /* 厂商 ROM 裁掉了 API：保持裁剪（形状正确），只是没有模糊 */
            }
        } catch (Throwable ignored) {
            /* 任何异常都不影响可用性：最差就是没有模糊 */
        }
    }

    /**
     * 胶囊 outline（只上下内缩、左右顶满）。
     *
     * ★ 底栏用这个：白胶囊左右顶到 View 边缘，只有上下留出膨胀余量。
     *   如果水平也内缩，胶囊会变窄（实测"被拉长、弧度对不上"）。
     *
     * @param vpadPx   上下内缩（像素）
     * @param radiusPx 圆角半径（= 胶囊高/2）
     */
    public static void applyCapsuleOutlineV(final View v, final int vpadPx,
                                            final int radiusPx) {
        if (v == null) return;
        try {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    outline.setRoundRect(0, vpadPx, Math.max(1, w),
                            Math.max(vpadPx + 1, h - vpadPx), radiusPx);
                }
            });
            /* ★ 不裁剪：elevation 阴影要能画到胶囊之外（四周发散） */
            v.setClipToOutline(false);
        } catch (Throwable ignored) {}
    }

    /** 仅设置正确的胶囊 outline（不启用模糊），用于阴影/裁剪。 */
    public static void applyCapsuleOutline(final View v, final int insetPx,
                                           final int cornerRadiusPx) {
        if (v == null) return;
        try {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(insetPx, insetPx,
                            Math.max(insetPx + 1, view.getWidth() - insetPx),
                            Math.max(insetPx + 1, view.getHeight() - insetPx),
                            cornerRadiusPx);
                }
            });
            v.setClipToOutline(true);
        } catch (Throwable ignored) {}
    }

    /**
     * 只在 API 31+ 加载，避免低版本类验证失败。
     *
     * ★ 为什么用反射而不是直接调 View.setBackgroundBlurRadius：
     *   本项目编译用的 android.jar 是精简过的（只有 743 个 View 方法），
     *   里面没有这个 API —— 直接调用会编译失败。
     *   设备上 Android 12+ 是有的，反射调用最稳，也顺便兼容了
     *   厂商 ROM 把这个方法裁掉的情况（拿不到就返回 false，退化为不透明底）。
     */
    private static final class Api31 {
        private static java.lang.reflect.Method m;
        private static boolean looked = false;

        static boolean setBlur(View v, int radiusPx) {
            try {
                if (!looked) {
                    looked = true;
                    m = View.class.getMethod("setBackgroundBlurRadius", int.class);
                }
                if (m == null) return false;
                m.invoke(v, Math.max(1, radiusPx));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
