package me.rerere.pet.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import me.rerere.pet.MainActivity;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.AdvancedPage;
import me.rerere.pet.ui.page.AiPage;
import me.rerere.pet.ui.page.HomePage;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.page.UiPage;
import me.rerere.pet.ui.theme.UiSettings;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.IconView;
import me.rerere.pet.ui.widget.TabBar;

/**
 * 新主页：底栏四页（主界面 / 高级设置 / AI / 界面）。
 *
 * ══════════════════════════════════════════════════════════════
 * 本阶段（Phase 2）的范围
 * ══════════════════════════════════════════════════════════════
 *   · 骨架 + 切换动画 + 返回处理 已全部实现；
 *   · 「界面」页是完整的（调色/手感）；
 *   · 其余三页暂时是"入口页"：把现有 Activity 的入口搬过来，
 *     等 Phase 3/4/5 再逐页填内容。这样任何时候 App 都是可用的，
 *     不会出现"重构到一半功能没了"。
 *
 * ══════════════════════════════════════════════════════════════
 * 切换动画
 * ══════════════════════════════════════════════════════════════
 *   新页从切换方向偏移 4% 屏宽 + 透明度 0 -> 1（260ms，decelerate）；
 *   旧页反向偏移并淡出（200ms）。方向由下标差决定（往右切就往右滑），
 *   比统一淡入淡出更有"位置感"。
 *
 *   ★ 为什么不用 ViewPager / RecyclerView：项目无 AndroidX，见 UI_KIT.md。
 *   ★ 动画结束要复位 translationX，否则反复切换会累积偏移（踩过）。
 */
public final class MainTabsActivity extends Activity
        implements UiPage.Host, HomePage.Host, AdvancedPage.Host, AiPage.Host {

    private static final String[] LABELS = {"主界面", "高级设置", "AI", "界面"};
    private static final int[] ICONS = {
            IconView.HOME, IconView.SLIDERS, IconView.SPARK, IconView.PALETTE
    };
    private static final int TAB_UI = 3;
    private static final int REQ_LOCATION = 3001;

    /**
     * 直接打开第几页（0=主界面 1=高级设置 2=AI 3=界面）。
     * 传进来时底栏选中态会同步 —— 用户点"调整渲染参数"进来应该落在高级设置，
     * 而不是回主界面自己再点一次。
     */
    public static final String EXTRA_TAB = "tab";

    /** 便捷构造：从别的页面跳到某一页。 */
    public static Intent intentForTab(android.content.Context c, int tab) {
        Intent it = new Intent(c, MainTabsActivity.class);
        it.putExtra(EXTRA_TAB, tab);
        /* ★ 不要 FLAG_ACTIVITY_CLEAR_TOP：我们只是要切页，不希望把已有实例重建。 */
        it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return it;
    }


    private FrameLayout contentHost;
    private TabBar barView;
    private FrameLayout barWrap;

    /** 每页的缓存（懒构建）。 */
    private final View[] pages = new View[LABELS.length];
    private int current = 0;
    /** 「界面」页重建时保留滚动位置。 */
    private int uiPageScrollY = 0;
    /** 主界面重建时保留滚动位置（切换渲染模式会重建）。 */
    private int homeScrollY = 0;

    private UiPage uiPage;
    private HomePage homePage;
    private AdvancedPage advancedPage;
    private AiPage aiPage;
    private me.rerere.pet.ui.store.AppearanceVm appearanceVm;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiSettings.ensureLoaded(this);
        uiPage = new UiPage(this, this);
        appearanceVm = new me.rerere.pet.ui.store.AppearanceVm(this);
        homePage = new HomePage(this, this, appearanceVm);
        advancedPage = new AdvancedPage(this, appearanceVm, this);
        aiPage = new AiPage(this, new PetStore(this), this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiTheme.BG);
        /* ★ 负 bottomMargin（底栏下沉）时，底栏会画到屏幕边缘之外，
           根容器默认会裁剪，导致它被切掉一截。这里关掉裁剪。 */
        root.setClipChildren(false);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        /* ---------- 底栏 ---------- */
        barWrap = new FrameLayout(this);
        /* ★ 按住时胶囊会放大到超出底栏本身，
           父容器默认 clipChildren=true 会把它切掉（看起来"放大到一半被削平"）。
           这里关掉裁剪，让膨胀能溢出边界。 */
        barWrap.setClipChildren(false);
        barWrap.setClipToPadding(false);
        final TabBar.OnTabSelected cb = this::switchTo;
        FrameLayout built = TabBar.buildBar(this, LABELS, ICONS, cb);
        /* buildBar 返回的是"胶囊+文字"的组合，里面的 TabBar 用来做动画/重绘 */
        barWrap.addView(built, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        barView = findTabBar(built);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        /* 附件规范：外层容器 margin 24dp */
        blp.leftMargin = UiTheme.dp(this, TabBar.PAGE_MARGIN_DP);
        blp.rightMargin = UiTheme.dp(this, TabBar.PAGE_MARGIN_DP);
        blp.bottomMargin = TabBar.bottomMarginPx(this);
        root.addView(barWrap, blp);

        setContentView(root);
        /* ★ targetSdk 35 起系统强制 edge-to-edge：内容会画到状态栏下面，
           "星盏桌宠"标题被状态栏盖住（实测截图）。
           这里给根容器补上状态栏高度的 top padding。
           ★ 用 WindowInsets 而不是写死数值 —— 不同机型状态栏高度不同。 */
        applySystemBarInsets(root);

        showPage(0, false);

        /* ★ 支持"直接打开某一页"：给外部页面一个回到"高级设置"的入口
           （例如 3D 模型管理页的「调整渲染参数」）。
           —— 那边点进来如果落回主界面，用户还得自己再点底栏，容易以为按钮坏了。 */
        applyRequestedTab(getIntent());

        /* ★★★ 打开 App 就要把桌宠服务拉起来 ★★★
           ---- 事故复盘（实测"打开软件后不会加载模型，要开一次旧页面才行"）★─
           旧 MainActivity.onCreate() 里有一句 startOverlayService()；
           我新建 MainTabsActivity 时漏了这一步 ——
           于是冷启动只看到设置界面，桌宠根本不显示、模型也不加载，
           直到进一次旧页面把它顺带启动起来。
           这里补上，并且放在 showPage 之后（确保 UI 已就绪再起服务）。 */
        if (appearanceVm.snapshot().enabled) {
            appearanceVm.startService();
        }

        /* 没有悬浮窗权限时提示一次（用户可能刚装完 App） */
        if (!android.provider.Settings.canDrawOverlays(this)) {
            appearanceVm.startService();   // 尝试一次，失败会静默
        }

        /* 13+ 打开预测性返回开关后，onBackPressed 不再被调用，必须注册回调；
           12 及以下继续走 onBackPressed。 */
        /* ★★★ 不要在这里注册任何返回回调 ★★★
           ---- 官方文档原文 ----
           "If your app enables an OnBackPressedCallback or an
            OnBackInvokedCallback with PRIORITY_DEFAULT or PRIORITY_OVERLAY,
            the predictive back animations do NOT run and you must handle
            the back event."
           → 只要注册了回调，系统就一定不播动画。
           → 什么都不注册时，系统会【自动 finish 当前 Activity 并播放
             跟手预测性返回动画】，这正是我们要的效果。
           底栏四页返回 = 退出 App，与系统默认行为完全一致。 */
    }

    /** 从 buildBar 的组合里找出 TabBar（用于 setSelected / refreshTheme）。 */
    private static TabBar findTabBar(View root) {
        if (root instanceof TabBar) return (TabBar) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TabBar t = findTabBar(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    /* ---------------- 切页 ---------------- */

    /**
     * 处理 EXTRA_TAB。
     * ★ 必须也处理 onNewIntent：本 Activity 是 singleTask，
     *   从 3D 模型管理页再点「调整渲染参数」不会走 onCreate，只走 onNewIntent
     *   （实测漏掉这一步会"点了没反应"）。
     */
    private void applyRequestedTab(Intent it) {
        if (it == null) return;
        int want = it.getIntExtra(EXTRA_TAB, -1);
        if (want < 0 || want >= pages.length || want == current) return;
        showPage(want, false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyRequestedTab(intent);
    }

    private void switchTo(int index) {
        if (index == current) return;
        showPage(index, true);
    }

    private void showPage(int index, boolean animate) {
        int from = current;
        current = index;

        View in = pages[index];
        if (in == null) {
            in = buildPage(index);
            pages[index] = in;
            contentHost.addView(in, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        final View incoming = in;
        if (barView != null) barView.setSelected(index, animate);

        if (!animate) {
            for (int i = 0; i < pages.length; i++) {
                if (pages[i] != null) {
                    boolean on = i == index;
                    pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
                    pages[i].setAlpha(1f);
                    pages[i].setTranslationX(0f);
                }
            }
            incoming.setVisibility(View.VISIBLE);
            return;
        }

        /* ★ 切换动画的取舍（实测过一版"交叉淡入淡出"但效果不好）：
             两页同时半透明 = 两页文字都看得见 -> 糊成一片，用户会以为界面坏了。
             所以改成：旧页【立即隐藏】，新页从一个略微偏移的位置淡入。
             这样任何时刻只有一页在画，绝不会重叠，
             同时仍有"往右切就从右边进来"的方向感。 */
        final int dir = index > from ? 1 : -1;
        final int width = Math.max(1, contentHost.getWidth());
        final float offset = width * 0.055f;

        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == null) continue;
            if (i == index) continue;
            pages[i].animate().cancel();
            pages[i].setVisibility(View.GONE);
            pages[i].setAlpha(1f);
            pages[i].setTranslationX(0f);
        }

        incoming.animate().cancel();
        incoming.setVisibility(View.VISIBLE);
        incoming.setAlpha(0.15f);
        incoming.setTranslationX(dir * offset);
        incoming.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .start();
    }

    private View buildPage(int index) {
        switch (index) {
            case TAB_UI:
                return uiPage.build(uiPageScrollY);
            case 0:
                return homePage.build(homeScrollY);
            case 1:
                return advancedPage.build();
            default:
                return aiPage.build();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        /* 从其它 Activity 返回（比如导入完模型、改完提供商）时：
           1) 桌宠服务可能被系统回收 -> 补一次启动；
           2) AI/主界面内容可能已变 -> 刷新当前页缓存。 */
        if (appearanceVm.snapshot().enabled && PetOverlayService.current() == null) {
            appearanceVm.startService();
        }
        refreshCurrentPageIfStale();
    }

    /**
     * 当前页在"离开期间可能被改过"时重建（只重建当前页，其它页懒加载）。
     * 例：从 ProviderEditActivity 返回 -> AI 页的提供商列表必须更新。
     */
    private void refreshCurrentPageIfStale() {
        if (current == 0 || current == 1 || current == 2) {
            /* 这三页的数据都可能被外部页面改过；重建当前页最省事也最不容易漏 */
            if (pages[current] != null) {
                rebuildPage(current);
            }
        }
    }

    /* ---------------- HomePage.Host ---------------- */

    /** 渲染模式变了：两张页内容完全不同，必须重建主界面那一页。 */
    @Override public void onRenderModeChanged() {
        homeScrollY = captureScroll(0);
        rebuildPage(0);
    }

    @Override public void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** AI 页数据变更（切换提供商/删记忆/授权完成）后重建该页。 */
    @Override public void onDataChanged() {
        rebuildPage(2);
    }

    /** AI 页点"位置信息"时请求定位权限（权限回调也在这里处理）。 */
    @Override public void requestLocationPermission() {
        try {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        } catch (Throwable t) {
            toast("无法请求定位权限");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) return;
        toast(me.rerere.pet.data.LocationHelper.hasPermission()
                ? "已授权，位置工具已启用" : "未授权，位置工具保持关闭");
        /* 刷新 AI 页，让"位置信息"那行的授权提示更新 */
        rebuildPage(2);
    }

    /** 参数改完需要刷新选中态（例如抗锯齿三档的高亮）。 */
    @Override public void rebuildAdvanced() {
        int keep = captureScroll(1);
        rebuildPage(1);
        View v = pages[1];
        if (v instanceof android.widget.ScrollView) {
            final int y = keep;
            v.post(() -> ((android.widget.ScrollView) v).scrollTo(0, y));
        }
    }

    /** 主界面"调整这些参数" -> 切到高级设置 Tab（不新开 Activity）。 */
    @Override public void switchToAdvanced() {
        showPage(1, true);
    }

    /** 素材/模型换了：主界面的"当前模型/当前桌宠"要更新。 */
    @Override public void onSelectionChanged() {
        homeScrollY = captureScroll(0);
        rebuildPage(0);
    }

    /* ---------------- UiPage.Host ---------------- */

    /**
     * 颜色 / 圆角变了：必须重建所有页面（这些值在控件构造时被烘焙进去）。
     * 保留当前 Tab，并尽量保留「界面」页的滚动位置。
     */
    @Override public void onThemeChanged() {
        int keep = current;
        int scroll = captureUiScroll();

        contentHost.removeAllViews();
        for (int i = 0; i < pages.length; i++) pages[i] = null;

        /* 底栏也要重建：胶囊底色/文字色都是构造时设的 */
        rebuildBar();

        uiPageScrollY = scroll;
        current = keep;
        showPage(keep, false);
    }

    /** 只影响手感（跟手/回弹/放大）：底栏重绘即可，不用重建页面。 */
    @Override public void onFeelChanged() {
        if (barView != null) {
            barView.refreshTheme();
            barView.refreshBackdrop();
        }
        /* ★ 底部距离改的是 LayoutParams，必须重新设一遍 ——
           只刷新 View 内部是不够的（用户拖滑块会看到底栏不动）。 */
        if (barWrap != null && barWrap.getLayoutParams()
                instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) barWrap.getLayoutParams();
            lp.bottomMargin = TabBar.bottomMarginPx(this);
            barWrap.setLayoutParams(lp);
        }
    }

    private int captureUiScroll() {
        return captureScroll(TAB_UI);
    }

    /** 取某一页当前的滚动位置（不是 ScrollView 就返回上次记住的值）。 */
    private int captureScroll(int index) {
        View v = pages[index];
        if (v instanceof android.widget.ScrollView) {
            return ((android.widget.ScrollView) v).getScrollY();
        }
        return index == TAB_UI ? uiPageScrollY : homeScrollY;
    }

    /**
     * 只重建某一页（其它页保持不动）。
     * ★ 必须先把旧 View 从容器里移除再加新的 —— 同一个 View 不能 addView 两次，
     *   而且直接覆盖会造成旧 View 残留在 contentHost 里吃触摸。
     */
    /**
     * 重建某一页（保留滚动位置）。
     *
     * ★★★ 为什么必须在这里处理滚动（实测过）★★★
     *   onResume 会调 refreshCurrentPageIfStale() 重建当前页
     *   （为了让"从子页面改完数据回来"能刷新列表）。
     *   而重建 = 移除旧 View + 构建新 View，ScrollView 的 scrollY 天然归零
     *   → 用户点进高级设置的二级页面、退回来，就得重新滑到底。
     *
     *   修法：重建前记下 scrollY，构建后 post 恢复。
     *   同时把值同步回 homeScrollY / uiPageScrollY ——
     *   因为 buildPage 内部（HomePage.build(y) / UiPage.build(y)）
     *   也会用这两个字段恢复，两边必须一致，否则会互相覆盖。
     */
    private void rebuildPage(int index) {
        /* ① 先记下当前滚动位置 */
        final int savedY = captureScroll(index);
        if (index == 0) homeScrollY = savedY;
        else if (index == TAB_UI) uiPageScrollY = savedY;

        View old = pages[index];
        if (old != null) {
            contentHost.removeView(old);
            pages[index] = null;
        }
        View fresh = buildPage(index);
        pages[index] = fresh;
        contentHost.addView(fresh, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fresh.setVisibility(View.VISIBLE);

        /* ② 再恢复（post 到下一帧，等布局完成才有效） */
        if (savedY > 0 && fresh instanceof android.widget.ScrollView) {
            fresh.post(() -> {
                if (pages[index] == fresh) {
                    ((android.widget.ScrollView) fresh).scrollTo(0, savedY);
                }
            });
        }
    }

    private void rebuildBar() {
        if (barWrap == null) return;
        barWrap.removeAllViews();
        FrameLayout built = TabBar.buildBar(this, LABELS, ICONS, this::switchTo);
        barWrap.addView(built, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        barView = findTabBar(built);
    }

    /* ---------------- 返回 ---------------- */

    /**
     * 返回。
     *
     * ★★ 底栏四页是【同级】，不是层级关系 ★★
     *   之前写成"非首屏就回主界面"，这是错的（实测发现）：
     *     在 AI 页按返回 → 不是"回到主界面"，而是"退出 App"，
     *     因为 AI 页和主界面是平级的 Tab，不存在"返回上一层"。
     *   → 现在一律 finish()，把返回交给系统处理（14+ 会播
     *     "退回桌面"的预测性动画）。
     *
     * ★ 二级页面（ProviderEditActivity / MemoryActivity 等）**不需要**
     *   在这里处理 —— 它们是独立 Activity，系统会自动做返回动画，
     *   而且那些页面才有真正的"层级"，跟手预览由系统原生提供。
     */
    private void handleBack() {
        finish();
    }

    /** 12 及以下走这里。 */
    @Override public void onBackPressed() {
        handleBack();
    }

    /**
     * 处理 edge-to-edge：给根容器补上状态栏 / 导航栏高度的内边距。
     *
     * ★ 为什么需要：targetSdk >= 35 起系统强制 edge-to-edge，
     *   页面会一直画到状态栏和手势条下面。
     *   （我们本来想用 windowOptOutEdgeToEdgeEnforcement 豁免，
     *     但那个属性需要 API 35 的 android.jar，本地只有 android-33，编不过。）
     *   所以改用官方的推荐做法：主动消费 insets。
     *
     * ★ 用 setOnApplyWindowInsetsListener 而不是 getInsets()：
     *   前者会在旋转屏幕 / 显示挖孔时自动更新。
     */
    private void applySystemBarInsets(View root) {
        if (root == null) return;
        final int basePadL = root.getPaddingLeft();
        final int basePadR = root.getPaddingRight();
        final int basePadB = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(basePadL, top, basePadR, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
