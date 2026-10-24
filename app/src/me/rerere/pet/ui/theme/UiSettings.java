package me.rerere.pet.ui.theme;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * UI 外观的用户可调参数（第 4 个 Tab「界面」里的那些滑块）。
 *
 * 设计要点：
 *   · 数值本身极小（5 个标量），用独立的 SharedPreferences 文件 "pet_ui"，
 *     不塞进 pet_store（那个文件已经有 70KB 的业务配置，混在一起排查很痛苦）。
 *   · 所有组件（SwitchView2 / SeekRowView / TabBar / CardView2）在
 *     onDraw 或构造时【实时读】这里的静态镜像，所以改完参数立即生效，
 *     不需要重启 Activity。
 *   · 静态镜像由 {@link #ensureLoaded(Context)} 填充；PetApp 启动时调一次，
 *     各 Activity 的 onCreate 也调一次（幂等，代价是一次 getBoolean 判断）。
 *
 * ★ 为什么不用 static final 常量：Phase 1 时颜色/手感都是编译期常量，
 *   一旦要让用户改就必须改成"运行时读取"。这里踩过的具体坑是
 *   SwitchView2 里的 `static final int TRACK_ON = UiTheme.ACCENT;` ——
 *   static final 在类加载那一刻就把颜色固化了，改配置永远不生效。
 */
public final class UiSettings {

    private static final String PREFS = "pet_ui";
    private static final String K_ACCENT = "accent";
    private static final String K_RADIUS = "card_radius";
    private static final String K_FOLLOW = "tab_follow";
    private static final String K_OVERSHOOT = "tab_overshoot";
    private static final String K_PRESS = "tab_press_scale";
    private static final String K_BAR_BG = "tab_bar_bg";
    private static final String K_CAPSULE_SCALE = "bar_capsule_scale";
    private static final String K_BOTTOM_MARGIN = "bar_bottom_margin";
    private static final String K_PANEL_MIN = "panel_min_pct";
    private static final String K_PANEL_MAX = "panel_max_pct";

    /* ---------------- 默认值 / 取值范围 ---------------- */

    public static final int DEFAULT_ACCENT = 0xFF2A6B6B;
    public static final float DEFAULT_RADIUS = 24f;
    /** 底栏跟手比例：越小越"软"（手拉快了底块会落后）。 */
    public static final float DEFAULT_FOLLOW = 0.34f;
    /** 底栏松手回弹过冲：0 = 不弹，0.9 = 弹得很明显。 */
    public static final float DEFAULT_OVERSHOOT = 0.55f;
    /** 按住底栏底块的放大比例。 */
    public static final float DEFAULT_PRESS = 1.07f;

    /* ★ 底栏背景模式
       ---- 为什么默认是"不透明" ----
       用户先反馈"底栏真的挺难看清的"，我加了背景模糊；结果实际开起来
       （半透明白 0xCC + 模糊）反而更糊了 —— 因为底下是深色卡片/文字，
       透上来以后和白字混在一起。
       所以默认改成"纯白不透明"（最清晰），模糊作为可选项让用户自己决定。
       低版本（API<31）没有系统背景模糊，这个选项显示为灰色并禁用。 */
    public static final int BAR_BG_SOLID = 0;
    public static final int BAR_BG_BLUR = 1;
    public static final int DEFAULT_BAR_BG = BAR_BG_SOLID;

    /* ★ 选中胶囊尺寸（未按住时的静止大小，相对格子可用空间的百分比）。
       100% = 原来的满格大小；调小会变成更紧凑的胶囊。 */
    public static final float DEFAULT_CAPSULE_SCALE = 1.0f;
    public static final float MIN_CAPSULE_SCALE = 0.45f;
    /* 上限 140%：再大就会顶到相邻格子，看起来像两个胶囊粘在一起 */
    public static final float MAX_CAPSULE_SCALE = 1.40f;

    /* ★ 底栏距屏幕底部的距离（dp）。
       有人喜欢贴底，有人喜欢留一点"悬浮感"。 */
    public static final float DEFAULT_BOTTOM_MARGIN = 24f;
    /* ★ 允许负值：用户可能想让底栏"压住"屏幕底边（-20dp = 下沉 20dp）。
       配手势条时有用 —— 正 margin 会把它顶高，负 margin 反而更贴底。 */
    public static final float MIN_BOTTOM_MARGIN = -20f;
    public static final float MAX_BOTTOM_MARGIN = 72f;

    /* ★ 对话面板高度（占屏幕高度 %）
       ---- 为什么需要两个值 ----
       面板是"消息列表 + 输入框"的整体，固定 40% 屏高时：
         · 屏幕小的手机，40% 只够两三条消息；
         · 屏幕大的平板，40% 又太大、挡住桌宠。
       给一个最小/最大区间：内容少时按最小高度，消息多了长到最大高度后
       内部滚动。两头都能看见效果。 */
    public static final float DEFAULT_PANEL_MIN = 30f;
    public static final float DEFAULT_PANEL_MAX = 60f;
    public static final float MIN_PANEL_PCT = 15f;
    public static final float MAX_PANEL_PCT = 85f;

    /* ★ 按住放大上限：设计上要求到 200%。 */
    public static final float MIN_PRESS = 1f;
    public static final float MAX_PRESS = 2.0f;

    public static final float MIN_RADIUS = 10f;
    public static final float MAX_RADIUS = 32f;
    public static final float MIN_FOLLOW = 0.15f;
    public static final float MAX_FOLLOW = 0.85f;
    public static final float MIN_OVERSHOOT = 0f;
    public static final float MAX_OVERSHOOT = 0.9f;

    /** 预设强调色（用户可直接点选）。 */
    public static final int[] PRESET_ACCENTS = {
            0xFF2A6B6B, // 深青（默认）
            0xFF3B62C9, // 靛蓝
            0xFF6E4BC6, // 紫
            0xFFC24A7B, // 玫红
            0xFFB4661F, // 琥珀
            0xFF3F4A4A, // 石墨
            0xFF2E7A4F, // 松绿
    };
    public static final String[] PRESET_NAMES = {
            "深青", "靛蓝", "紫罗兰", "玫红", "琥珀", "石墨", "松绿"
    };

    /* ---------------- 运行时镜像 ---------------- */

    private static volatile boolean loaded = false;
    private static volatile int accent = DEFAULT_ACCENT;
    private static volatile float radius = DEFAULT_RADIUS;
    private static volatile float follow = DEFAULT_FOLLOW;
    private static volatile float overshoot = DEFAULT_OVERSHOOT;
    private static volatile float pressScale = DEFAULT_PRESS;
    private static volatile int barBg = DEFAULT_BAR_BG;
    private static volatile float capsuleScale = DEFAULT_CAPSULE_SCALE;
    private static volatile float bottomMargin = DEFAULT_BOTTOM_MARGIN;
    private static volatile float panelMinPct = DEFAULT_PANEL_MIN;
    private static volatile float panelMaxPct = DEFAULT_PANEL_MAX;

    private UiSettings() {}

    /** 幂等加载（App 启动 / 每个 Activity onCreate 都调用）。 */
    public static void ensureLoaded(Context c) {
        if (loaded || c == null) return;
        synchronized (UiSettings.class) {
            if (loaded) return;
            try {
                SharedPreferences p = c.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                accent = p.getInt(K_ACCENT, DEFAULT_ACCENT);
                radius = clamp(p.getFloat(K_RADIUS, DEFAULT_RADIUS), MIN_RADIUS, MAX_RADIUS);
                follow = clamp(p.getFloat(K_FOLLOW, DEFAULT_FOLLOW), MIN_FOLLOW, MAX_FOLLOW);
                overshoot = clamp(p.getFloat(K_OVERSHOOT, DEFAULT_OVERSHOOT),
                        MIN_OVERSHOOT, MAX_OVERSHOOT);
                pressScale = clamp(p.getFloat(K_PRESS, DEFAULT_PRESS), MIN_PRESS, MAX_PRESS);
                barBg = p.getInt(K_BAR_BG, DEFAULT_BAR_BG);
                capsuleScale = clamp(p.getFloat(K_CAPSULE_SCALE, DEFAULT_CAPSULE_SCALE),
                        MIN_CAPSULE_SCALE, MAX_CAPSULE_SCALE);
                bottomMargin = clamp(p.getFloat(K_BOTTOM_MARGIN, DEFAULT_BOTTOM_MARGIN),
                        MIN_BOTTOM_MARGIN, MAX_BOTTOM_MARGIN);
                panelMinPct = clamp(p.getFloat(K_PANEL_MIN, DEFAULT_PANEL_MIN),
                        MIN_PANEL_PCT, MAX_PANEL_PCT);
                panelMaxPct = clamp(p.getFloat(K_PANEL_MAX, DEFAULT_PANEL_MAX),
                        MIN_PANEL_PCT, MAX_PANEL_PCT);
            } catch (Throwable t) {
                /* 配置坏了就用默认值，绝不因为读配置失败而让界面起不来 */
                accent = DEFAULT_ACCENT;
                radius = DEFAULT_RADIUS;
                follow = DEFAULT_FOLLOW;
                overshoot = DEFAULT_OVERSHOOT;
                pressScale = DEFAULT_PRESS;
                barBg = DEFAULT_BAR_BG;
                capsuleScale = DEFAULT_CAPSULE_SCALE;
                bottomMargin = DEFAULT_BOTTOM_MARGIN;
                panelMinPct = DEFAULT_PANEL_MIN;
                panelMaxPct = DEFAULT_PANEL_MAX;
            }
            loaded = true;
        }
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    /* ---------------- 读取（组件在 onDraw 里实时调用） ---------------- */

    public static int accent() { return accent; }

    public static float radius() { return radius; }

    public static float follow() { return follow; }

    public static float overshoot() { return overshoot; }

    public static float pressScale() { return pressScale; }

    /** 底栏背景模式（BAR_BG_SOLID / BAR_BG_BLUR）。 */
    public static int barBg() { return barBg; }

    /** 选中胶囊静止时的缩放（0.45~1.15）。 */
    public static float barCapsuleScale() { return capsuleScale; }

    /** 底栏距屏幕底部的距离（dp）。 */
    public static float barBottomMargin() { return bottomMargin; }

    /** 对话面板最小高度（占屏幕高度的 %）。 */
    public static float panelMinPct() { return panelMinPct; }

    /** 对话面板最大高度（占屏幕高度的 %）。 */
    public static float panelMaxPct() { return panelMaxPct; }

    /* ---------------- 写入（只在用户操作时调用） ---------------- */

    public static void setAccent(Context c, int color) {
        accent = color;
        prefs(c).edit().putInt(K_ACCENT, color).apply();
    }

    public static void setRadius(Context c, float v) {
        radius = clamp(v, MIN_RADIUS, MAX_RADIUS);
        prefs(c).edit().putFloat(K_RADIUS, radius).apply();
    }

    public static void setFollow(Context c, float v) {
        follow = clamp(v, MIN_FOLLOW, MAX_FOLLOW);
        prefs(c).edit().putFloat(K_FOLLOW, follow).apply();
    }

    public static void setOvershoot(Context c, float v) {
        overshoot = clamp(v, MIN_OVERSHOOT, MAX_OVERSHOOT);
        prefs(c).edit().putFloat(K_OVERSHOOT, overshoot).apply();
    }

    public static void setPressScale(Context c, float v) {
        pressScale = clamp(v, MIN_PRESS, MAX_PRESS);
        prefs(c).edit().putFloat(K_PRESS, pressScale).apply();
    }

    public static void setBarBg(Context c, int mode) {
        barBg = mode;
        prefs(c).edit().putInt(K_BAR_BG, mode).apply();
    }

    public static void setBarCapsuleScale(Context c, float v) {
        capsuleScale = clamp(v, MIN_CAPSULE_SCALE, MAX_CAPSULE_SCALE);
        prefs(c).edit().putFloat(K_CAPSULE_SCALE, capsuleScale).apply();
    }

    public static void setBarBottomMargin(Context c, float dp) {
        bottomMargin = clamp(dp, MIN_BOTTOM_MARGIN, MAX_BOTTOM_MARGIN);
        prefs(c).edit().putFloat(K_BOTTOM_MARGIN, bottomMargin).apply();
    }

    public static void setPanelMinPct(Context c, float v) {
        panelMinPct = clamp(v, MIN_PANEL_PCT, MAX_PANEL_PCT);
        prefs(c).edit().putFloat(K_PANEL_MIN, panelMinPct).apply();
    }

    public static void setPanelMaxPct(Context c, float v) {
        panelMaxPct = clamp(v, MIN_PANEL_PCT, MAX_PANEL_PCT);
        prefs(c).edit().putFloat(K_PANEL_MAX, panelMaxPct).apply();
    }

    /** 恢复全部默认（调试 / 用户点"重置"）。 */
    public static void resetAll(Context c) {
        accent = DEFAULT_ACCENT;
        radius = DEFAULT_RADIUS;
        follow = DEFAULT_FOLLOW;
        overshoot = DEFAULT_OVERSHOOT;
        pressScale = DEFAULT_PRESS;
        barBg = DEFAULT_BAR_BG;
        capsuleScale = DEFAULT_CAPSULE_SCALE;
        bottomMargin = DEFAULT_BOTTOM_MARGIN;
        panelMinPct = DEFAULT_PANEL_MIN;
        panelMaxPct = DEFAULT_PANEL_MAX;
        prefs(c).edit()
                .putInt(K_ACCENT, accent)
                .putFloat(K_RADIUS, radius)
                .putFloat(K_FOLLOW, follow)
                .putFloat(K_OVERSHOOT, overshoot)
                .putFloat(K_PRESS, pressScale)
                .putInt(K_BAR_BG, barBg)
                .putFloat(K_CAPSULE_SCALE, capsuleScale)
                .putFloat(K_BOTTOM_MARGIN, bottomMargin)
                .putFloat(K_PANEL_MIN, panelMinPct)
                .putFloat(K_PANEL_MAX, panelMaxPct)
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 是否与默认值一致（UI 页显示"已修改"用）。 */
    public static boolean isDefault() {
        return accent == DEFAULT_ACCENT
                && radius == DEFAULT_RADIUS
                && follow == DEFAULT_FOLLOW
                && overshoot == DEFAULT_OVERSHOOT
                && pressScale == DEFAULT_PRESS
                && barBg == DEFAULT_BAR_BG
                && capsuleScale == DEFAULT_CAPSULE_SCALE
                && bottomMargin == DEFAULT_BOTTOM_MARGIN
                && panelMinPct == DEFAULT_PANEL_MIN
                && panelMaxPct == DEFAULT_PANEL_MAX;
    }
}
