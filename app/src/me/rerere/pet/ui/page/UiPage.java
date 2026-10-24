package me.rerere.pet.ui.page;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.theme.UiSettings;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.SeekRowView;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * 「界面」页：让用户自己调 UI 的颜色与手感。
 *
 * 可调项与生效方式：
 *   强调色        -> 所有开关 ON / 滑块 / 主按钮 / 底栏选中态（改完要重建页面）
 *   卡片圆角      -> 所有 CardView2（改完要重建页面）
 *   底栏跟手      -> TabBar 实时读，不用重建
 *   底栏回弹      -> 同上
 *   底栏按压缩放  -> 同上
 *
 * ★ 为什么颜色/圆角要"重建页面"而手感不用：
 *   颜色和圆角在控件构造时被烘焙进了 TextView.setTextColor / GradientDrawable，
 *   改完必须重新构造控件。手感参数是在 onDraw / 每帧回调里现读的，所以立即生效。
 *
 * ★ 拖动中不重建：只在 onSeekFinished（手指抬起）时回调，否则滑块的 View
 *   会在拖动过程中被替换掉，直接断掉手势。
 */
public final class UiPage {

    public interface Host {
        /** 颜色/圆角变了：重建全部页面（保留当前 Tab 与滚动位置）。 */
        void onThemeChanged();

        /** 只影响手感的参数变了：让底栏重绘即可。 */
        void onFeelChanged();
    }

    private final Activity activity;
    private final Host host;

    public UiPage(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        UiSettings.ensureLoaded(activity);
    }

    public View build(int scrollY) {
        SettingsPage page = new SettingsPage(activity);
        page.pageTitle("界面", "这里改的是这个 App 自己的外观与手感，桌宠模型不受影响。");

        /* ---------- 强调色 ---------- */
        page.section("强调色");
        page.custom(buildSwatches(), true);
        page.info("颜色会用在：开关打开态、滑块、主按钮、底栏选中项。");

        /* ---------- 形状 ---------- */
        page.section("形状");
        page.seek("卡片圆角", "调大更软，调小更硬朗（拖动时实时预览）", UiSettings.radius(),
                UiSettings.MIN_RADIUS, UiSettings.MAX_RADIUS, "dp",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        /* ★ 拖动中：只落内存 + 递归刷新已有卡片的背景。
                           绝不能在这里重建页面 —— 那会把正在拖动的这个行替换掉，
                           手势当场断掉（踩过：滑块拖到一半就卡住）。 */
                        UiSettings.setRadius(activity, v);
                        UiTheme.refreshRadiusRecursive(activity.getWindow().getDecorView());
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        UiSettings.setRadius(activity, v);
                        /* 松手后统一重建一次，让所有页面都对齐（含未显示的那些） */
                        host.onThemeChanged();
                    }
                });

        /* ---------- 底栏手感 ---------- */
        page.section("底栏手感");
        page.seek("跟手程度", "越小越软：手指拖快时胶囊会落后一点再追上来",
                UiSettings.follow() * 100f, UiSettings.MIN_FOLLOW * 100f,
                UiSettings.MAX_FOLLOW * 100f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) { }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        UiSettings.setFollow(activity, v / 100f);
                        host.onFeelChanged();
                    }
                });
        page.seek("回弹力度", "松手后胶囊多弹出一小截再回来",
                UiSettings.overshoot() / 0.9f * 100f, 0f, 100f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) { }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        UiSettings.setOvershoot(activity, v / 100f * 0.9f);
                        host.onFeelChanged();
                    }
                });
        page.seek("选中胶囊大小",
                "未按住时那个灰色胶囊有多大（100% = 占满整格；>100% 会顶到邻格）",
                UiSettings.barCapsuleScale() * 100f,
                UiSettings.MIN_CAPSULE_SCALE * 100f,
                UiSettings.MAX_CAPSULE_SCALE * 100f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        UiSettings.setBarCapsuleScale(activity, v / 100f);
                        host.onFeelChanged();
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        UiSettings.setBarCapsuleScale(activity, v / 100f);
                        host.onFeelChanged();
                    }
                });

        page.seek("距屏幕底部",
                "底栏离屏幕下边缘的距离。负值 = 往下沉（可压住手势条区域）",
                UiSettings.barBottomMargin(),
                UiSettings.MIN_BOTTOM_MARGIN,
                UiSettings.MAX_BOTTOM_MARGIN, "dp",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        UiSettings.setBarBottomMargin(activity, v);
                        host.onFeelChanged();
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        UiSettings.setBarBottomMargin(activity, v);
                        host.onFeelChanged();
                    }
                });

        /* ★ 按住放大：1.00 ~ 2.00（设计上要求上限 200%）。
           滑块直接是百分比，读数即倍数，不用换算。 */
        page.seek("按住放大", "按住时胶囊膨胀到原来的几倍",
                UiSettings.pressScale() * 100f, 100f, 200f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        UiSettings.setPressScale(activity, v / 100f);
                        host.onFeelChanged();
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        UiSettings.setPressScale(activity, v / 100f);
                        host.onFeelChanged();
                    }
                });
        /* ★ 底栏背景：默认纯白不透明（最清晰）。
           模糊效果依赖 Android 12+ 的系统背景模糊，低版本直接禁用并说明原因。 */
        final boolean blurOk = me.rerere.pet.ui.widget.BlurCompat.supported();
        final boolean blurOn = UiSettings.barBg() == UiSettings.BAR_BG_BLUR;

        ToggleRowView glassRow = page.toggle("液态玻璃底栏",
                blurOk
                        ? "底栏变成半透明玻璃：背后内容被模糊，加上折射高光与边缘反光。\n"
                          + "★ 内部内容很花时可能影响可读性，默认关闭，可以随时关掉"
                        : "需要 Android 12（API 31）及以上的系统背景模糊。\n"
                          + "当前系统是 Android " + android.os.Build.VERSION.SDK_INT
                          + "，无法启用 —— 底栏会保持纯白不透明。",
                blurOn && blurOk,
                (row, on) -> {
                    if (!blurOk) return;
                    UiSettings.setBarBg(activity, on
                            ? UiSettings.BAR_BG_BLUR : UiSettings.BAR_BG_SOLID);
                    host.onFeelChanged();
                });
        if (!blurOk) {
            /* 低版本：整行变灰 + 不可点（设计上要求"变灰然后强制禁用"）。
               ★ 不用 setEnabled(false) —— 那会把文字也变灰，
                 而这里需要的是"标题保持可读、只有开关失效"，
                 所以另外单独压透明度并锁住整行点击。 */
            glassRow.setEnabled(false);
            glassRow.setAlpha(0.55f);
            glassRow.setOnClickListener(null);
            glassRow.setOnToggledListener(null);
        }

        page.info("提示：底栏那几项调完立刻生效，可以直接在底部来回拖一拖试手感。");

        /* ---------- 对话面板 ---------- */
        page.section("对话面板");

        page.seek("对话面板高度",
                "对话窗口最高能到屏幕的多高，消息多了内部滚动",
                UiSettings.panelMaxPct(),
                UiSettings.MIN_PANEL_PCT, UiSettings.MAX_PANEL_PCT, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        UiSettings.setPanelMaxPct(activity, v);
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        UiSettings.setPanelMaxPct(activity, v);
                    }
                });

        page.info("提示：改完要重新打开对话窗口才会生效（窗口尺寸在创建时确定）。");

        /* ---------- 重置 ---------- */
        page.section("恢复");
        page.valueRow("当前状态", UiSettings.isDefault() ? "全是默认值" : "已自定义");
        page.actions("全部恢复默认", () -> {
            UiSettings.resetAll(activity);
            host.onThemeChanged();
        }, null, null);

        View v = page.build();
        final int y = scrollY;
        v.post(() -> { if (v instanceof android.widget.ScrollView) ((android.widget.ScrollView) v).scrollTo(0, y); });
        return v;
    }

    /**
     * 颜色圆点（选中态加一圈深色描边）。
     *
     * ★ 必须分行：7 个预设 + 文字标签在 1280px 宽屏上排一行会溢出，
     *   最后一个"松绿"直接被裁掉（实测）。这里按每行 4 个换行。
     */
    /** 是否是预设色之一（用于判断"调色板"入口是否处于选中态）。 */
    private static boolean isPreset(int color) {
        for (int c : UiSettings.PRESET_ACCENTS) if (c == color) return true;
        return false;
    }

    private LinearLayout buildSwatches() {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(activity, UiTheme.PAD_H);
        int pv = UiTheme.dp(activity, UiTheme.PAD_V);
        col.setPadding(ph, pv, ph, pv);

        int size = UiTheme.dp(activity, 32f);
        int gap = UiTheme.dp(activity, 12f);
        int cur = UiSettings.accent();
        final int perRow = 4;

        LinearLayout row = null;
        /* 先放预设色，最后追加一个"调色板"入口（设计上要求） */
        final int total = UiSettings.PRESET_ACCENTS.length + 1;
        for (int i = 0; i < total; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = UiTheme.dp(activity, 14f);
                col.addView(row, rlp);
            }
            boolean custom = (i == UiSettings.PRESET_ACCENTS.length);
            final int color = custom ? cur : UiSettings.PRESET_ACCENTS[i];
            final String name = custom ? "调色板" : UiSettings.PRESET_NAMES[i];
            boolean selected = custom ? !isPreset(cur) : (color == cur);

            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);

            if (custom) {
                /* 调色板入口：彩虹渐变圆 + 中间挖白（用 FrameLayout 叠两层，
                   不用 layer-list 资源，也不依赖 API 级别）。 */
                android.widget.FrameLayout bubble = new android.widget.FrameLayout(activity);
                View ring = new View(activity);
                GradientDrawable rg = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFFE05252, 0xFFE0B24B, 0xFF57B85C,
                                0xFF4B9BD8, 0xFF8B5CD6});
                rg.setShape(GradientDrawable.OVAL);
                ring.setBackground(rg);
                bubble.addView(ring, new android.widget.FrameLayout.LayoutParams(size, size));

                View hole = new View(activity);
                GradientDrawable hg = new GradientDrawable();
                hg.setShape(GradientDrawable.OVAL);
                hg.setColor(0xFFFFFFFF);
                hole.setBackground(hg);
                int holeSize = size - UiTheme.dp(activity, 12f);
                android.widget.FrameLayout.LayoutParams hlp =
                        new android.widget.FrameLayout.LayoutParams(holeSize, holeSize);
                hlp.gravity = Gravity.CENTER;
                bubble.addView(hole, hlp);

                if (selected) {
                    /* 当前用的是自定义色：外面加一圈描边提示 */
                    GradientDrawable sel = new GradientDrawable();
                    sel.setShape(GradientDrawable.OVAL);
                    sel.setColor(0x00000000);
                    sel.setStroke(UiTheme.dp(activity, 2f), 0xFF1A1A1A);
                    bubble.setBackground(sel);
                }
                cell.addView(bubble, new LinearLayout.LayoutParams(size, size));
            } else {
                View dot = new View(activity);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(color);
                if (selected) g.setStroke(UiTheme.dp(activity, 2f), 0xFF1A1A1A);
                dot.setBackground(g);
                cell.addView(dot, new LinearLayout.LayoutParams(size, size));
            }


            TextView label = new TextView(activity);
            label.setText(name);
            label.setTextSize(10f);
            label.setTextColor(selected ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_SECONDARY);
            label.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiTheme.dp(activity, 5f);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            cell.addView(label, lp);

            cell.setClickable(true);
            cell.setOnClickListener(v -> {
                if (custom) {
                    activity.startActivity(new android.content.Intent(activity,
                            me.rerere.pet.ui.ColorPaletteActivity.class));
                } else {
                    UiSettings.setAccent(activity, color);
                    host.onThemeChanged();
                }
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(size, 
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = gap;
            row.addView(cell, clp);
        }
        return col;
    }
}
