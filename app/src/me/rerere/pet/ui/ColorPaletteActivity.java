package me.rerere.pet.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.rerere.pet.ui.theme.UiSettings;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.ColorPaletteView;
import me.rerere.pet.ui.widget.SeekRowView;

/**
 * 调色板页：螺旋色板 + 明暗滑杆 + 十六进制预览。
 *
 * ★ 结果怎么回传：直接写 UiSettings 然后 finish。
 *   不用 startActivityForResult —— 因为它要走 onActivityResult 链路，
 *   而调用方（MainTabsActivity）在 onResume 里本来就要检查主题是否变了
 *   （用户可能从任何地方改），两条路径合并成一条更不容易漏。
 */
public final class ColorPaletteActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        UiSettings.ensureLoaded(this);

        /* ★ 整页放进 ScrollView：色板是正方形且边长由宽/高共同决定，
           小屏上光靠压缩高度会让色板小到不好点。宁可让页面能滚。 */
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(UiTheme.BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiTheme.BG);
        int h = UiTheme.dp(this, UiTheme.PAD_PAGE);
        root.setPadding(h, UiTheme.dp(this, 20f), h, UiTheme.dp(this, 28f));
        scroll.addView(root);

        /* ---------- 顶部：返回 + 标题 ---------- */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = UiTheme.text(this, "\u2039", 30f, UiTheme.TEXT_PRIMARY, false);
        back.setGravity(Gravity.CENTER);
        int backSize = UiTheme.dp(this, 40f);
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(backSize, backSize));

        TextView title = UiTheme.pageTitle(this, "强调色");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = UiTheme.dp(this, 8f);
        head.addView(title, tlp);
        root.addView(head);

        TextView hint = UiTheme.text(this,
                "点一个圆点即选中。越靠中心越灰，越靠外越鲜艳。",
                13f, UiTheme.TEXT_SECONDARY, false);
        hint.setLineSpacing(UiTheme.dp(this, 2f), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiTheme.dp(this, 6f);
        root.addView(hint, hlp);

        /* ---------- 色板 ---------- */
        final ColorPaletteView palette = new ColorPaletteView(this);
        /* ★ 色板高度必须封顶：如果直接 MATCH_PARENT 宽 + WRAP_CONTENT 高，
           在窄屏上它会按宽度撑成正方形，把下面的「完成/恢复默认」挤出屏幕
           （第一版实测就是这样，用户要滚动才能点到按钮）。
           这里取 min(可用宽, 46% 屏高)。 */
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int pad = UiTheme.dp(this, UiTheme.PAD_PAGE) * 2;
        /* 42% 屏高：再大就会把「从壁纸取色 / 完成」挤出屏幕（实测过）；
           再小则色板上的点太小不好点。 */
        int side = Math.min(screenW - pad, (int) (screenH * 0.42f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                side, side);
        plp.topMargin = UiTheme.dp(this, 10f);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(palette, plp);

        final int initial = UiSettings.accent();
        palette.selectClosestTo(initial);

        /* ---------- 预览色块 + hex ---------- */
        final FrameLayout preview = new FrameLayout(this);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 52f));
        vp.topMargin = UiTheme.dp(this, 16f);
        final TextView hex = UiTheme.text(this, hexOf(initial), 15f,
                0xFFFFFFFF, true);
        hex.setGravity(Gravity.CENTER);
        preview.addView(hex, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setPreviewColor(preview, initial);
        root.addView(preview, vp);

        /* ---------- 明暗 ---------- */
        SeekRowView valueRow = new SeekRowView(this);
        valueRow.setTitle("明暗")
                .setSubtitle("调亮了整个色板都会跟着变浅")
                .setRange(35f, 100f)
                .setFormatter(v -> Math.round(v) + "%")
                .setValue(palette.getValue() * 100f);
        valueRow.setOnSeekListener(new SeekRowView.OnSeekListener() {
            @Override public void onSeek(SeekRowView row, float v) {
                palette.setValue(v / 100f);
                /* 明暗变了，当前选中的颜色也按新明度重算 */
                int c = palette.currentColor();
                hex.setText(hexOf(c));
                setPreviewColor(preview, c);
            }
            @Override public void onSeekFinished(SeekRowView row, float v) {
                /* 松手后才真正落库，拖动中只是预览 */
                UiSettings.setAccent(ColorPaletteActivity.this, palette.currentColor());
                hex.setText(hexOf(palette.currentColor()));
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiTheme.dp(this, 12f);
        root.addView(valueRow, slp);

        palette.setOnColorPicked(color -> {
            hex.setText(hexOf(color));
            setPreviewColor(preview, color);
            UiSettings.setAccent(ColorPaletteActivity.this, color);
        });

        /* ---------- 从壁纸取色（莫奈） ---------- */
        TextView monet = UiTheme.secondaryButton(this, "从壁纸取色");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = UiTheme.dp(this, 12f);
        monet.setOnClickListener(v -> {
            int c = WallpaperColors.pick(this);
            if (c == 0) {
                android.widget.Toast.makeText(this,
                        "取不到壁纸颜色（系统限制或无权限），可以手动在色板上挑",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            palette.selectClosestTo(c);
            hex.setText(hexOf(c));
            setPreviewColor(preview, c);
            UiSettings.setAccent(this, c);
            android.widget.Toast.makeText(this,
                    "已从壁纸取色 " + hexOf(c), android.widget.Toast.LENGTH_SHORT).show();
        });
        root.addView(monet, mlp);

        /* ---------- 底部按钮 ---------- */
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = UiTheme.dp(this, 18f);
        root.addView(actions, alp);

        TextView done = UiTheme.primaryButton(this, "完成");
        done.setOnClickListener(v -> finish());
        actions.addView(done, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reset = UiTheme.secondaryButton(this, "恢复默认");
        reset.setOnClickListener(v -> {
            UiSettings.setAccent(this, UiSettings.DEFAULT_ACCENT);
            finish();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = UiTheme.dp(this, 12f);
        actions.addView(reset, rlp);

        setContentView(scroll);
    }

    private void setPreviewColor(View v, int color) {
        v.setBackground(UiTheme.round(this, color, 18f));
    }

    /**
     * 从系统壁纸取色（莫奈 / Material You 的简化版）。
     *
     * ★ 这里只用系统给的 WallpaperColors，没有实现完整的 HCT 色调算法：
     *   完整 Material You 要跑 quantizer + 色调映射（几百行，还要处理
     *   孟塞尔色差），收益不明显 —— 用户要的是"取到壁纸的主色/强调色"，
     *   系统已经算好了 primary/secondary/tertiary，直接挑一个最"像样"的即可。
     *
     * ★ API 级别：getWallpaperColors 需要 27+，本项目 minSdk 24，
     *   所以整段包在版本判断里；取不到就返回 0，由调用方提示用户手选。
     * ★ 需要 READ_EXTERNAL_STORAGE 吗？不需要 —— 读壁纸颜色是系统级 API，
     *   不需要存储权限（跑在 8.1+ 上实测通过）。
     */
    static final class WallpaperColors {
        static int pick(Activity a) {
            if (android.os.Build.VERSION.SDK_INT < 27) return 0;
            try {
                android.app.WallpaperManager wm =
                        android.app.WallpaperManager.getInstance(a);
                android.app.WallpaperColors wc =
                        wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                if (wc == null) return 0;
                /* 优先用"主要颜色"，它通常就是壁纸里面积最大、最有代表性的色 */
                android.graphics.Color primary = wc.getPrimaryColor();
                android.graphics.Color secondary = wc.getSecondaryColor();
                android.graphics.Color tertiary = wc.getTertiaryColor();

                int best = 0;
                float bestScore = -1f;
                for (android.graphics.Color c : new android.graphics.Color[]{primary, secondary, tertiary}) {
                    if (c == null) continue;
                    int argb = c.toArgb();
                    float[] hsv = new float[3];
                    android.graphics.Color.colorToHSV(argb, hsv);
                    /* 评分：中等饱和度 + 不太暗不太亮的颜色最适合当强调色。
                       过灰（s 很小）会显得没精神；过亮（v 很高）在白色卡片上看不清。 */
                    float score = hsv[1] * 1.4f - Math.abs(hsv[2] - 0.55f) * 0.8f;
                    if (score > bestScore) { bestScore = score; best = argb; }
                }
                if (best == 0) return 0;
                /* 强制不透明并压一点亮度，保证在白底上可读 */
                float[] hsv = new float[3];
                android.graphics.Color.colorToHSV(best, hsv);
                if (hsv[1] < 0.18f) hsv[1] = 0.35f;
                if (hsv[2] > 0.72f) hsv[2] = 0.62f;
                if (hsv[2] < 0.30f) hsv[2] = 0.42f;
                return android.graphics.Color.HSVToColor(hsv);
            } catch (Throwable t) {
                /* 某些 ROM 的壁纸服务会直接抛异常；返回 0 让用户手选，不要崩 */
                return 0;
            }
        }
    }

    private static String hexOf(int color) {
        return String.format("#%08X", color);
    }
}
