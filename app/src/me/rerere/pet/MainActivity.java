package me.rerere.pet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import me.rerere.pet.core.CodexPetImporter;
import me.rerere.pet.core.PetContracts;
import me.rerere.pet.data.McpManager;
import me.rerere.pet.data.McpStore;
import me.rerere.pet.data.PetAiClient;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.McpEditActivity;
import me.rerere.pet.ui.PetMmdActivity;
import me.rerere.pet.ui.PetSearchActivity;
import me.rerere.pet.ui.PetSettingsActivity;
import me.rerere.pet.ui.PetOverlayService;

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  ⛔ 禁止在本文件新增功能（2026-10-17 UI 重构后）                      ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * 本文件已从"主设置页"降级为【旧代码历史参考 + 尚未迁移表单的宿主】。
 * 所有用户可见的页面都已在 ui/page/* 里重建（主界面 / 高级设置 / AI / 界面）。
 *
 * ▸ 加新功能请写在 ui/page/*.java，用 SettingsPage 构建器，一行一个开关：
 *       page.section("分组");
 *       page.toggle("标题", "说明", 当前值, (row, on) -> vm.xxx(on));
 *   详见 UI_KIT.md。
 *
 * ▸ 本文件只允许【减少】（把功能迁走），不允许增加。
 *   tools/guard-main-activity.sh 会在 build.sh 里硬拦截：
 *   检测到【新增方法】或【行数增长】就拒绝编译。
 *
 * ▸ 确实必须临时改这里 → 强制编译：
 *       ./build.sh -force            # 或 FORCE_MAIN_ACTIVITY=1 ./deploy.sh
 *   强制后请同步基线，否则下次仍会被拦：
 *       ./tools/guard-main-activity.sh --update
 *
 * 原始职责（重构前）：桌宠开关、外观滑块、AI 提供商、记忆、素材导入导出。
 * 这些绝大部分已迁走；留着是为了可回滚、可对照旧行为。
 */
public final class MainActivity extends Activity {

    private static final int REQ_IMPORT = 1001;

    private PetStore store;

    private SeekBar headSeek;
    private SeekBar bodySeek;
    private SeekBar scaleSeek;
    private SeekBar fpsSeek;
    private TextView headLabel;
    private TextView bodyLabel;
    private TextView scaleLabel;
    private TextView fpsLabel;
    private TextView petNameLabel;

    private LinearLayout providerContainer;
    private LinearLayout mcpContainer;
    private LinearLayout memoryList;
    private LinearLayout petListContainer;
    private CodexPetImporter petImporter;
    private McpManager mcpManager;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PetStore(this);
        petImporter = new CodexPetImporter(new File(getFilesDir(), "pets"));
        mcpManager = new McpManager(this, store);
        mcpManager.setListener(() -> runOnUiThread(this::refreshMcpList));
        setContentView(buildUi());
        /* ★ 从新主页（AiPage）点某个提供商进来时直接打开它的编辑表单。
           AiPage 自己没有实现那套表单（模型探测、API Key、增删改），
           与其复制一遍踩过的坑，不如把它作为"编辑器的宿主"复用。 */
        if (getIntent() != null) {
            String editId = getIntent().getStringExtra("edit_provider");
            if (editId != null && !editId.isEmpty()) {
                PetStore.Provider target = null;
                for (PetStore.Provider p : store.providers()) {
                    if (editId.equals(p.id)) { target = p; break; }
                }
                showProviderEditor(target);
            }
        }
        if (getIntent() != null && getIntent().getBooleanExtra("open_history", false)) {
            // 历史面板由桌宠气泡承载，这里仅回到主界面
        }
        ensureOverlayPermission();
        startOverlayService();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPetLabel();
        mcpManager.startPolling();
        mcpManager.refreshAll();
        refreshMcpList();
        // AI 通过工具写入记忆后，回到设置页立刻可见
        if (memoryList != null) refreshMemories(memoryList);
        refreshPetList();
        refreshPetLabel();
    }

    @Override protected void onPause() {
        super.onPause();
        mcpManager.stopPolling();
    }

    /** 渲染 MCP 卡片列表（圆形图标 + 标题 + 状态点 + 传输类型 + 错误信息 + ⋮ 菜单）。 */
    private void refreshMcpList() {
        if (mcpContainer == null) return;
        mcpContainer.removeAllViews();

        List<McpStore.Server> servers = mcpManager.store().servers();

        // 内置服务器：记忆 / 日期时间 / 联网搜索
        List<McpManager.Status> builtinList = mcpManager.builtinServers();
        String[] builtinNames = {"记忆", "日期时间", "联网搜索", "位置信息", "表情控制"};
        String[] builtinDescriptions = {
                "memory_add / memory_search / memory_list / memory_forget / history_search",
                "datetime_now —— 获取当前日期、时间与星期",
                "search_web —— 联网搜索实时信息",
                "location_get —— 获取当前地理位置（需授权）",
                "play_expression —— 让 3D 模型做表情（仅 MMD 模式）",
        };
        for (int i = 0; i < builtinNames.length; i++) {
            McpManager.Status st = i < builtinList.size() ? builtinList.get(i) : null;
            if (st == null) {
                st = new McpManager.Status();
                st.connected = false;
            }
            mcpContainer.addView(mcpCard(builtinNames[i], st, true, null));
        }

        if (servers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有配置 MCP 服务器");
            empty.setTextSize(14);
            empty.setTextColor(0xFF8A8A93);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            mcpContainer.addView(empty);
            return;
        }
        for (McpStore.Server server : servers) {
            McpManager.Status st = mcpManager.statusOf(server.id);
            mcpContainer.addView(mcpCard(server.name, st, false, server));
        }
    }

    /** 单个 MCP 卡片。 */
    private View mcpCard(String name, McpManager.Status status, boolean builtin, McpStore.Server server) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(16), dp(14), dp(12), dp(14));
        card.setBackground(glass(dp(18), 0xFFFFFFFF, 0x14000000));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(6);
        card.setLayoutParams(cardLp);

        // 左侧圆形图标
        TextView icon = new TextView(this);
        icon.setText(builtinIconText(name));
        icon.setTextSize(builtin ? 17 : 16);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(0xFFFFFFFF);
        icon.setBackground(glass(dp(24),
                builtin ? builtinIconColor(name) : 0xFF4C6EF5, 0x00000000));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        iconLp.rightMargin = dp(12);
        card.addView(icon, iconLp);

        // 中间内容
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(17);
        title.setTextColor(0xFF1B1B1F);
        titleRow.addView(title);

        // 状态点
        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(11);
        dot.setTextColor(status.connected ? 0xFF2E9E4F : 0xFFB3261E);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotLp.leftMargin = dp(6);
        titleRow.addView(dot, dotLp);
        col.addView(titleRow);

        // 传输类型标签
        TextView badge = new TextView(this);
        badge.setText(builtin ? "内置" : (McpStore.TRANSPORT_SSE.equals(server.transport)
                ? "SSE" : "Streamable HTTP"));
        badge.setTextSize(11);
        badge.setTextColor(0xFF2E7D32);
        badge.setPadding(dp(10), dp(3), dp(10), dp(3));
        badge.setBackground(glass(dp(10), 0xFFDDF3DC, 0x00000000));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(6);
        col.addView(badge, badgeLp);

        // 工具数 / 错误信息
        if (status.connected) {
            TextView meta = new TextView(this);
            meta.setText("已连接 · " + status.toolCount + " 个工具");
            meta.setTextSize(12);
            meta.setTextColor(0xFF6B6B74);
            meta.setPadding(0, dp(6), 0, 0);
            col.addView(meta);
        } else if (status.lastError != null && !status.lastError.isEmpty()) {
            TextView err = new TextView(this);
            err.setText("Error while sending message: " + status.lastError);
            err.setTextSize(12);
            err.setTextColor(0xFFB3261E);
            err.setPadding(0, dp(6), 0, 0);
            col.addView(err);
        } else if (!builtin) {
            TextView meta = new TextView(this);
            meta.setText("未启用");
            meta.setTextSize(12);
            meta.setTextColor(0xFF8A8A93);
            meta.setPadding(0, dp(6), 0, 0);
            col.addView(meta);
        }
        card.addView(col);

        // 右侧 ⋮ 菜单
        TextView menu = new TextView(this);
        menu.setText("⋮");
        menu.setTextSize(20);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        menuLp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(menu, menuLp);

        if (builtin) {
            menu.setOnClickListener(v -> onBuiltinClick(name));
            card.setOnClickListener(v -> onBuiltinClick(name));
        } else {
            final McpStore.Server srv = server;
            card.setOnClickListener(v -> openMcpEditor(srv.id));
            menu.setOnClickListener(v -> showMcpMenu(srv));
        }
        return card;
    }

    /** 内置服务器的图标符号。 */
    private String builtinIconText(String name) {
        if ("记忆".equals(name)) return "🧠";
        if ("日期时间".equals(name)) return "🕐";
        if ("联网搜索".equals(name)) return "🔍";
        if ("位置信息".equals(name)) return "📍";
        if ("表情控制".equals(name)) return "😊";
        return name.isEmpty() ? "M" : name.substring(0, 1);
    }

    /** 内置服务器的图标底色。 */
    private int builtinIconColor(String name) {
        if ("记忆".equals(name)) return 0xFF7E57C2;      // 紫
        if ("日期时间".equals(name)) return 0xFF2F7E7E;  // 青
        if ("联网搜索".equals(name)) return 0xFF2E7D32;  // 绿
        if ("位置信息".equals(name)) return 0xFFE07B39;  // 橙
        if ("表情控制".equals(name)) return 0xFFD81B60;  // 玫红
        return 0xFF2F7E7E;
    }

    private void openMcpEditor(String id) {
        Intent i = new Intent(this, McpEditActivity.class);
        i.putExtra(McpEditActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void showMcpMenu(final McpStore.Server server) {
        String[] options = {"编辑", "立即检测", "删除", "取消"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(server.name)
                .setItems(options, (d, which) -> {
                    if (which == 0) openMcpEditor(server.id);
                    else if (which == 1) {
                        toast("正在检测…");
                        new Thread(() -> {
                            mcpManager.checkOne(server);
                            runOnUiThread(this::refreshMcpList);
                        }).start();
                    } else if (which == 2) {
                        mcpManager.store().remove(server.id);
                        refreshMcpList();
                        toast("已删除");
                    }
                })
                .show();
    }

    private void showBuiltinMemoryInfo() {
        List<String> memories = store.longTermMemories();
        new android.app.AlertDialog.Builder(this)
                .setTitle("记忆（内置 MCP）")
                .setMessage("这是内置服务器，提供 " + mcpManager.builtinTools().size() + " 个工具：\n"
                        + "memory_add / memory_search / memory_list / memory_forget / history_search\n\n"
                        + "当前长期记忆 " + memories.size() + " 条。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private static final int REQ_LOCATION = 3001;

    /** 点击内置服务器卡片。 */
    private void onBuiltinClick(String name) {
        if ("位置信息".equals(name)) {
            if (me.rerere.pet.data.LocationHelper.hasPermission()) {
                showBuiltinInfo(name);
            } else {
                requestLocationPermission();
            }
            return;
        }
        showBuiltinInfo(name);
    }

    /** 请求定位权限（未授权则位置工具不启用）。 */
    private void requestLocationPermission() {
        try {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        } catch (Throwable t) {
            toast("无法请求定位权限");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) return;
        if (me.rerere.pet.data.LocationHelper.hasPermission()) {
            toast("已授权，位置工具已启用");
        } else {
            toast("未授权，位置工具保持关闭");
        }
        refreshMcpList();
    }

    /** 内置服务器的说明（按名称）。 */
    private void showBuiltinInfo(String name) {
        String title;
        String message;
        if ("日期时间".equals(name)) {
            title = "日期时间（内置 MCP）";
            message = "无需配置、无需联网，提供 1 个工具：\n\n"
                    + "datetime_now\n"
                    + "获取当前日期、时间与星期。AI 需要知道「今天几号」「现在几点」时会自动调用。";
        } else if ("联网搜索".equals(name)) {
            // 搜索需要选择服务商，直接跳到配置页
            startActivity(new android.content.Intent(this, PetSearchActivity.class));
            return;
        } else if ("表情控制".equals(name)) {
            title = "表情控制（内置 MCP）";
            message = "让 3D 模型做表情，提供 1 个工具：\n\n"
                    + "play_expression\n"
                    + "参数：name（表情名，如 笑い / ウィンク / なごみ / あ）\n\n"
                    + "行为：暂停自转 → 转向正前方 → 播放表情 3 秒 → "
                    + "恢复原来的自转状态。\n\n"
                    + "仅在「3D 模型」渲染模式下可用。";
        } else if ("位置信息".equals(name)) {
            if (!me.rerere.pet.data.LocationHelper.hasPermission()) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("位置信息（内置 MCP）")
                        .setMessage("需要定位权限才能使用。\n\n授权后提供 1 个工具：\n"
                                + "location_get —— 获取当前经纬度与大致地址。\n\n"
                                + "未授权时该工具不会出现在 AI 的可用工具里。")
                        .setPositiveButton("去授权", (d, w) -> requestLocationPermission())
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
            title = "位置信息（内置 MCP）";
            message = "已启用，提供 1 个工具：\n\n"
                    + "location_get\n"
                    + "获取当前经纬度与大致地址（反向地理编码）。\n\n"
                    + "需要系统定位服务开启，且已授予定位权限。";
        } else {
            showBuiltinMemoryInfo();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------- UI 构建 ----------------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF8F7FC);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));

        // 标题行：左侧标题，右侧 ⚙ 高级设置
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(title("星盏桌宠"));
        titleCol.addView(subtitle("悬浮在桌面上的 AI 伙伴"));
        titleRow.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView settingsBtn = new TextView(this);
        settingsBtn.setText("⚙");
        settingsBtn.setTextSize(24);
        settingsBtn.setTextColor(0xFF1B1B1F);
        settingsBtn.setGravity(Gravity.CENTER);
        settingsBtn.setBackground(glass(dp(22), 0xFFFFFFFF, 0x1A000000));
        settingsBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        settingsBtn.setContentDescription("高级设置");
        settingsBtn.setOnClickListener(v -> {
            if (store.activeProvider() == null) {
                toast("请先添加一个 AI 提供商");
                return;
            }
            startActivity(new Intent(this, PetSettingsActivity.class));
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        sbLp.bottomMargin = dp(16);
        titleRow.addView(settingsBtn, sbLp);

        root.addView(titleRow);

        // 桌宠状态
        LinearLayout statusCard = card();
        petNameLabel = body(statusCard);
        statusCard.addView(petNameLabel);

        Switch enabledSwitch = new Switch(this);
        PetStore.Appearance appearance = store.appearance();
        enabledSwitch.setChecked(appearance.enabled);
        enabledSwitch.setOnCheckedChangeListener((btn, checked) -> {
            PetStore.Appearance a = store.appearance();
            a.enabled = checked;
            store.saveAppearance(a);
            if (checked) startOverlayService();
            else getSystemService(android.app.Service.class).stopService(
                    new Intent(this, PetOverlayService.class));
        });
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView statusText = body(statusRow);
        statusText.setText("启用桌宠");
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(enabledSwitch);
        statusCard.addView(statusRow);
        root.addView(statusCard);

        // 触控分区
        root.addView(sectionTitle("触摸分区"));
        LinearLayout zoneCard = card();

        headSeek = seek(5, 90, Math.round(appearance.headBoundary * 100));
        headLabel = label("头部区域：34%");
        zoneCard.addView(headLabel);
        zoneCard.addView(headSeek);
        headSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                headLabel.setText("头部区域：" + v + "%");
                PetStore.Appearance a = store.appearance();
                a.headBoundary = v / 100f;
                store.saveAppearance(a);
                if (fromUser) showGuideLines();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { showGuideLines(); }
            @Override public void onStopTrackingTouch(SeekBar sb) { hideGuideLines(); }
        });

        bodySeek = seek(10, 99, Math.round(appearance.bodyBoundary * 100));
        bodyLabel = label("身体区域结束：76%");
        zoneCard.addView(bodyLabel);
        zoneCard.addView(bodySeek);
        bodySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                bodyLabel.setText("身体区域结束：" + v + "%");
                PetStore.Appearance a = store.appearance();
                a.bodyBoundary = v / 100f;
                store.saveAppearance(a);
                if (fromUser) showGuideLines();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { showGuideLines(); }
            @Override public void onStopTrackingTouch(SeekBar sb) { hideGuideLines(); }
        });

        root.addView(zoneCard);
        root.addView(hint("拖动滑块时，桌宠上会显示红色分界线帮助你校准：第一条线以上是头，两条线之间是身体，以下是脚。"));

        // 外观
        root.addView(sectionTitle("外观"));
        LinearLayout lookCard = card();

        scaleSeek = seek(10, 200, Math.round(appearance.scale * 100));
        scaleLabel = label("大小：100%");
        lookCard.addView(scaleLabel);
        lookCard.addView(scaleSeek);
        scaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                scaleLabel.setText("大小：" + v + "%");
                PetStore.Appearance a = store.appearance();
                a.scale = v / 100f;
                store.saveAppearance(a);
                // 防抖：连续拖动时每 250ms 只更新一次，且不重启服务
                if (fromUser) scheduleAppearanceApply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                applyAppearanceNow();
            }
        });

        fpsSeek = seek(4, 12, appearance.fps);
        fpsLabel = label("帧率：" + appearance.fps + " FPS");
        lookCard.addView(fpsLabel);
        lookCard.addView(fpsSeek);
        fpsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                fpsLabel.setText("帧率：" + v + " FPS");
                PetStore.Appearance a = store.appearance();
                a.fps = v;
                store.saveAppearance(a);
                if (fromUser) scheduleAppearanceApply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                applyAppearanceNow();
            }
        });

        Switch idleSwitch = new Switch(this);
        idleSwitch.setChecked(appearance.idleRandomEnabled);
        idleSwitch.setOnCheckedChangeListener((btn, checked) -> {
            PetStore.Appearance a = store.appearance();
            a.idleRandomEnabled = checked;
            store.saveAppearance(a);
            // 开关无需重启服务，Service 每轮读取最新配置
        });
        LinearLayout idleRow = new LinearLayout(this);
        idleRow.setOrientation(LinearLayout.HORIZONTAL);
        idleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView idleText = new TextView(this);
        idleText.setText("待机时随机动作");
        idleText.setTextSize(14);
        idleText.setTextColor(0xFF2A2A30);
        idleText.setPadding(dp(14), dp(14), dp(14), dp(14));
        idleRow.addView(idleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        idleRow.addView(idleSwitch);
        lookCard.addView(idleRow);
        root.addView(hint("开启后，桌宠一段时间没人碰就会自己做随机动作。"));

        root.addView(lookCard);

        // 提供商
        root.addView(sectionTitle("AI 提供商"));
        providerContainer = new LinearLayout(this);
        providerContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(providerContainer);
        root.addView(primaryButton("+ 添加服务商", v -> showProviderEditor(null)));

        // MCP 服务器
        root.addView(sectionTitle("MCP 服务器"));
        mcpContainer = new LinearLayout(this);
        mcpContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mcpContainer);
        root.addView(primaryButton("+ 添加 MCP 服务器", v -> {
            startActivity(new Intent(this, McpEditActivity.class));
        }));
        root.addView(hint("每 30 秒自动检测一次连接状态。在服务器编辑页可设置是否把工具列表发送给 AI。"));
        refreshProviderList();

        // 角色
        root.addView(sectionTitle("角色设定"));
        EditText persona = new EditText(this);
        persona.setText(store.personaPrompt());
        persona.setTextSize(14);
        persona.setMinLines(3);
        persona.setGravity(Gravity.TOP | Gravity.START);
        persona.setBackground(glass(dp(16), 0xFFFFFFFF, 0x14000000));
        persona.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(persona);
        root.addView(primaryButton("保存角色设定", v -> {
            store.setPersonaPrompt(persona.getText().toString().trim());
            toast("已保存");
        }));

        // 记忆
        root.addView(sectionTitle("长期记忆"));
        LinearLayout memoryCard = card();
        memoryList = new LinearLayout(this);
        memoryList.setOrientation(LinearLayout.VERTICAL);
        refreshMemories(memoryList);
        memoryCard.addView(memoryList);
        root.addView(memoryCard);
        root.addView(primaryButton("+ 添加记忆", v -> showMemoryEditor()));
        root.addView(hint("记忆会随对话一起发给模型，帮助桌宠记住你。"));

        // 导入导出
        root.addView(sectionTitle("桌宠素材"));
        petListContainer = new LinearLayout(this);
        petListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(petListContainer);
        refreshPetList();
        root.addView(primaryButton("+ 导入 .codex-pet.zip", v -> pickImport()));
        root.addView(secondaryButton("导出当前桌宠", v -> exportCurrent()));
        root.addView(hint("导入过的素材会保留在这里，可以直接切换或删除，不用重复导入。"));

        // 3D 模型（MMD）
        root.addView(sectionTitle("3D 模型"));
        LinearLayout mmdCard = card();
        LinearLayout mmdRow = new LinearLayout(this);
        mmdRow.setOrientation(LinearLayout.HORIZONTAL);
        mmdRow.setGravity(Gravity.CENTER_VERTICAL);
        mmdRow.setPadding(0, dp(8), 0, dp(8));

        LinearLayout mmdCol = new LinearLayout(this);
        mmdCol.setOrientation(LinearLayout.VERTICAL);
        TextView mmdTitle = new TextView(this);
        mmdTitle.setText("MMD 模型");
        mmdTitle.setTextSize(16);
        mmdTitle.setTextColor(0xFF1B1B1F);
        mmdCol.addView(mmdTitle);
        TextView mmdSub = new TextView(this);
        mmdSub.setText(store.appearance().renderMode.equals("mmd")
                ? "当前使用 3D 模型"
                : "支持 .pmx / .pmd 模型，可导入自己的模型");
        mmdSub.setTextSize(12);
        mmdSub.setTextColor(0xFF8A8A93);
        mmdCol.addView(mmdSub);
        mmdRow.addView(mmdCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView mmdArrow = new TextView(this);
        mmdArrow.setText("›");
        mmdArrow.setTextSize(20);
        mmdArrow.setTextColor(0xFF9A9AA2);
        mmdRow.addView(mmdArrow);

        mmdRow.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, PetMmdActivity.class)));
        mmdCard.addView(mmdRow);
        root.addView(mmdCard);

        root.addView(sectionTitle("其他"));
        root.addView(secondaryButton("授予悬浮窗权限", v -> openOverlaySettings()));

        scroll.addView(root);
        return scroll;
    }

    // ---------------- 组件 ----------------

    private TextView title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(28);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setTextColor(0xFF1B1B1F);
        return v;
    }

    private TextView subtitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setTextColor(0xFF6B6B74);
        v.setPadding(0, dp(4), 0, dp(20));
        return v;
    }

    private TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(16);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setTextColor(0xFF1B1B1F);
        v.setPadding(0, dp(26), 0, dp(10));
        return v;
    }

    private TextView body(LinearLayout parent) {
        TextView v = new TextView(this);
        v.setTextSize(14);
        v.setTextColor(0xFF2A2A30);
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        return v;
    }

    private TextView hint(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(12);
        v.setTextColor(0xFF8A8A93);
        v.setPadding(dp(4), dp(8), dp(4), 0);
        return v;
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setTextColor(0xFF2A2A30);
        v.setPadding(dp(14), dp(12), dp(14), dp(4));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(glass(dp(20), 0xFFFFFFFF, 0x14000000));
        c.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4);
        p.bottomMargin = dp(4);
        c.setLayoutParams(p);
        return c;
    }

    private SeekBar seek(int min, int max, int value) {
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(Math.max(0, Math.min(max - min, value - min)));
        s.setPadding(dp(14), dp(4), dp(14), dp(8));
        return s;
    }

    private SeekBar.OnSeekBarChangeListener onSeek(TextView display, String prefix, OnSeekValue v) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                display.setText(prefix + (progress + sb.getMin() - sb.getMin()) + "%");
                v.onValue(sb.getProgress());
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
    }

    private interface OnSeekValue { void onValue(int rawProgress); }

    private Button primaryButton(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(15);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(glass(dp(16), 0xFF4C6EF5, 0x00000000));
        b.setOnClickListener(l);
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private Button secondaryButton(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(15);
        b.setTextColor(0xFF1B1B1F);
        b.setBackground(glass(dp(16), 0xFFFFFFFF, 0x1A000000));
        b.setOnClickListener(l);
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private GradientDrawable glass(int radius, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------- 提供商 ----------------

    private void refreshProviderList() {
        providerContainer.removeAllViews();
        List<PetStore.Provider> all = store.providers();
        PetStore.Provider active = store.activeProvider();
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有配置服务商");
            empty.setTextSize(14);
            empty.setTextColor(0xFF8A8A93);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            providerContainer.addView(empty);
            return;
        }
        for (PetStore.Provider p : all) {
            LinearLayout card = card();
            TextView name = new TextView(this);
            name.setText(p.name + (active != null && p.id.equals(active.id) ? "  ✓ 使用中" : ""));
            name.setTextSize(15);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setTextColor(0xFF1B1B1F);
            name.setPadding(dp(14), dp(14), dp(14), dp(2));
            card.addView(name);

            TextView detail = new TextView(this);
            detail.setText(p.model + "\n" + p.normalizedBaseUrl());
            detail.setTextSize(12);
            detail.setTextColor(0xFF6B6B74);
            detail.setPadding(dp(14), 0, dp(14), dp(10));
            card.addView(detail);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(dp(8), 0, dp(8), dp(8));

            actions.addView(miniButton("使用", v -> {
                store.setActiveProvider(p.id);
                refreshProviderList();
                toast("已切换到 " + p.name);
            }));
            actions.addView(miniButton("编辑", v -> showProviderEditor(p)));
            actions.addView(miniButton("删除", v -> confirmDeleteProvider(p)));

            card.addView(actions);
            providerContainer.addView(card);
        }
    }

    private Button miniButton(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(13);
        b.setTextColor(0xFF1B1B1F);
        b.setBackground(glass(dp(12), 0xFFF2F2F7, 0x00000000));
        b.setOnClickListener(l);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    /** 支持的 API 格式。当前仅 OpenAI 兼容格式落地。 */
    private static final String[] API_FORMATS = {"OpenAI", "Google", "Claude"};

    private void showProviderEditor(PetStore.Provider existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));

        // 顶部灰色小字提示
        TextView notice = new TextView(this);
        notice.setText("目前仅支持 OpenAI 格式");
        notice.setTextSize(12);
        notice.setTextColor(0xFF9A9AA2);
        notice.setPadding(dp(4), dp(4), dp(4), dp(10));
        form.addView(notice);

        // 格式 Tab（仅 OpenAI 可选，其余置灰）
        final int[] selectedFormat = {0};
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(0, 0, 0, dp(14));
        for (int i = 0; i < API_FORMATS.length; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(API_FORMATS[i]);
            tab.setTextSize(14);
            tab.setGravity(Gravity.CENTER);
            tab.setEnabled(i == 0);
            tab.setTextColor(i == 0 ? 0xFF1B1B1F : 0xFFB8B8C0);
            tab.setBackground(glass(dp(24),
                    i == 0 ? 0xFFDCEEF0 : 0x00000000,
                    i == 0 ? 0x33000000 : 0x00000000));
            tab.setOnClickListener(v -> {
                if (idx != 0) {
                    toast("该格式暂未支持");
                    return;
                }
                selectedFormat[0] = idx;
                toast("已选择 OpenAI 格式");
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            tabRow.addView(tab, tp);
        }
        form.addView(tabRow);

        // 预填 OpenAI 示例：key 留空
        final EditText name = field(form, "名称",
                existing == null ? "OpenRouter" : existing.name, false);
        final EditText apiKey = field(form, "API Key",
                existing == null ? "" : existing.apiKey, true);
        final EditText baseUrl = field(form, "API Base Url",
                existing == null ? "https://openrouter.ai/api/v1" : existing.baseUrl, false);
        final EditText apiPath = field(form, "API 路径",
                existing == null ? "/chat/completions" : existing.chatPath, false);

        final EditText model = field(form, "模型", existing == null ? "" : existing.model, false);

        // 拉取模型列表按钮
        Button fetchModels = new Button(this);
        fetchModels.setText("从服务端拉取模型列表");
        fetchModels.setTextSize(14);
        fetchModels.setTextColor(0xFF1B1B1F);
        fetchModels.setBackground(glass(dp(14), 0xFFF2F2F7, 0x00000000));
        fetchModels.setOnClickListener(v -> {
            PetStore.Provider probe = new PetStore.Provider("probe", "probe",
                    baseUrl.getText().toString().trim(),
                    apiKey.getText().toString().trim(),
                    model.getText().toString().trim());
            showModelListPage(probe, selected -> {
                model.setText(selected);
                toast("已选择模型：" + selected);
            });
        });
        LinearLayout.LayoutParams fetchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fetchLp.topMargin = dp(12);
        fetchModels.setLayoutParams(fetchLp);
        form.addView(fetchModels);

        // 表单较高，必须自建单一 ScrollView 并限高：
        // 直接把 LinearLayout 交给 setView 时，底部字段会被对话框窗口裁掉而无法输入。
        android.widget.ScrollView formScroll = new android.widget.ScrollView(this);
        formScroll.setFillViewport(true);
        formScroll.addView(form, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加服务商" : "编辑服务商")
                .setView(formScroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(d -> {
            // 限制高度为屏幕 70%，其余靠滚动
            int maxFormH = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
            formScroll.getLayoutParams().height = maxFormH;
            formScroll.requestLayout();

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String n = name.getText().toString().trim();
                    String b = baseUrl.getText().toString().trim();
                    String k = apiKey.getText().toString().trim();
                    String m = model.getText().toString().trim();
                    String path = apiPath.getText().toString().trim();
                    // 便于排查：把实际读到的值打到日志
                    android.util.Log.i("PetProvider",
                            "save name=[" + n + "] baseUrl=[" + b + "] model=[" + m + "]");
                    // 逐项提示，明确指出缺哪个字段
                    if (n.isEmpty()) { toast("请填写名称"); return; }
                    if (b.isEmpty()) { toast("请填写 API Base Url"); return; }
                    if (m.isEmpty()) { toast("请填写模型（可点下方按钮从服务端拉取）"); return; }
                    PetStore.Provider p = existing == null
                            ? new PetStore.Provider("p" + System.currentTimeMillis(), n, b, k, m)
                            : existing;
                    p.name = n;
                    p.baseUrl = b;
                    p.apiKey = k;
                    p.model = m;
                    if (!path.isEmpty()) p.chatPath = path;
                    /* ★★ 必须用 saveProvider(p)，不能用 saveProviders(store.providers())！
                       providers() 每次新建一批对象，而我们要保存的是 p ——
                       把"旧的那一批"写回去等于把编辑内容丢掉。
                       这正是"AI 提供商里改什么都存不上"的根因（2026-10-07 修复）。 */
                    store.saveProvider(p);
                    if (store.activeProvider() == null) store.setActiveProvider(p.id);
                    refreshProviderList();
                    toast("已保存");
                    dialog.dismiss();
                });
        });
        dialog.show();
    }

    /** 带虚线下划线标签的输入框，贴近截图里的样式。 */
    private EditText field(LinearLayout parent, String label, String value, boolean isPassword) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(6), 0, dp(6));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(0xFF6B6B74);
        l.setPadding(dp(2), dp(4), dp(2), dp(2));
        group.addView(l);

        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(15);
        e.setSingleLine(true);
        if (isPassword) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        e.setBackground(underlineDrawable());
        e.setPadding(dp(2), dp(8), dp(2), dp(8));
        group.addView(e);

        parent.addView(group);
        return e;
    }

    /** 底部一条细线的背景。 */
    private android.graphics.drawable.Drawable underlineDrawable() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(dp(1), 0xFFB8B8C0);
        d.setCornerRadius(0f);
        return d;
    }

    /**
     * 模型列表页面：后台拉取 /models，带搜索框过滤，点击回填。
     * 注意：AlertDialog 自带 ScrollView，这里不能再嵌套，否则测量抛异常。
     */
    private void showModelListPage(PetStore.Provider probe, java.util.function.Consumer<String> onPick) {
        TextView loading = new TextView(this);
        loading.setText("正在拉取模型列表…");
        loading.setTextSize(14);
        loading.setTextColor(0xFF6B6B74);
        loading.setPadding(dp(24), dp(28), dp(24), dp(28));

        final android.app.AlertDialog loadingDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("模型列表")
                .setView(loading)
                .setNegativeButton("关闭", null)
                .setCancelable(false)
                .create();
        loadingDialog.show();

        new Thread(() -> {
            List<String> models = new ArrayList<>();
            String error = null;
            try {
                models = PetAiClient.fetchModels(probe);
                if (models.isEmpty()) error = "服务端未返回模型";
            } catch (Exception e) {
                error = "拉取失败：" + e.getClass().getSimpleName();
            }
            final List<String> finalModels = models;
            final String finalError = error;
            runOnUiThread(() -> {
                try {
                    if (loadingDialog.isShowing()) loadingDialog.dismiss();
                    showModelListContent(finalModels, finalError, onPick);
                } catch (Throwable t) {
                    android.util.Log.e("PetModelList", "show failed", t);
                    toast("模型列表显示失败");
                }
            });
        }).start();
    }

    /**
     * 渲染模型列表内容（含搜索框）。
     * 关键：显式构造单一 ScrollView 作为根，并限制最大高度；
     * 交给 AlertDialog 自动包裹会出现高度不裁剪、无法滚动的问题。
     */
    private void showModelListContent(List<String> models, String error,
                                     java.util.function.Consumer<String> onPick) {
        // 根 = 单一 ScrollView，搜索框与列表同在其中，保证整体可滚动
        android.widget.ScrollView scrollRoot = new android.widget.ScrollView(this);
        scrollRoot.setFillViewport(true);
        // 最多占屏幕 70%，其余靠滚动
        scrollRoot.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        scrollRoot.addView(container);

        // 搜索行
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), dp(8), dp(16), dp(4));

        final EditText searchBox = new EditText(this);
        searchBox.setHint("搜索模型…");
        searchBox.setTextSize(15);
        searchBox.setSingleLine(true);
        searchBox.setBackground(underlineDrawable());
        searchBox.setPadding(dp(2), dp(8), dp(2), dp(8));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView counter = new TextView(this);
        counter.setTextSize(12);
        counter.setTextColor(0xFF9A9AA2);
        counter.setPadding(dp(8), 0, 0, 0);
        searchRow.addView(counter);
        container.addView(searchRow);

        final LinearLayout resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        container.addView(resultBox);

        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];

        java.util.function.Consumer<String> filter = keyword -> {
            resultBox.removeAllViews();
            String kw = keyword == null ? "" : keyword.trim().toLowerCase();
            int shown = 0;
            for (final String m : models) {
                if (!kw.isEmpty() && !m.toLowerCase().contains(kw)) continue;
                shown++;
                TextView row = new TextView(this);
                row.setText(m);
                row.setTextSize(15);
                row.setTextColor(0xFF2A2A30);
                row.setPadding(dp(16), dp(14), dp(16), dp(14));
                row.setOnClickListener(v -> {
                    onPick.accept(m);
                    if (holder[0] != null) holder[0].dismiss();
                });
                resultBox.addView(row);
            }
            if (shown == 0) {
                TextView none = new TextView(this);
                none.setText(kw.isEmpty() ? (error != null ? error : "没有模型") : "没有匹配的模型");
                none.setTextSize(14);
                none.setTextColor(kw.isEmpty() && error != null ? 0xFFB3261E : 0xFF9A9AA2);
                none.setPadding(dp(16), dp(24), dp(16), dp(24));
                resultBox.addView(none);
            }
            counter.setText(shown + "/" + models.size());
            // 内容变短时把滚动位置复位
            scrollRoot.post(() -> scrollRoot.scrollTo(0, 0));
        };

        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int c2) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                filter.accept(e.toString());
            }
        });

        android.app.AlertDialog listDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("模型列表")
                .setView(scrollRoot)
                .setNegativeButton("关闭", null)
                .create();
        holder[0] = listDialog;
        listDialog.setOnShowListener(d -> {
            // 限制最大高度为屏幕 70%，超出即可滚动
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
            scrollRoot.getLayoutParams().height = maxH;
            scrollRoot.requestLayout();
            filter.accept("");
        });
        listDialog.show();
    }

    private void confirmDeleteProvider(PetStore.Provider p) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除服务商")
                .setMessage("确定删除 " + p.name + " 吗？")
                .setPositiveButton("删除", (d, w) -> {
                    List<PetStore.Provider> all = store.providers();
                    all.remove(p);
                    store.saveProviders(all);
                    refreshProviderList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 记忆 ----------------

    private void refreshMemories(LinearLayout list) {
        list.removeAllViews();
        List<String> all = store.longTermMemories();
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有长期记忆");
            empty.setTextSize(13);
            empty.setTextColor(0xFF8A8A93);
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            list.addView(empty);
            return;
        }
        for (final String m : all) {
            TextView row = new TextView(this);
            row.setText("· " + m);
            row.setTextSize(14);
            row.setTextColor(0xFF2A2A30);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("删除这条记忆？")
                        .setMessage(m)
                        .setPositiveButton("删除", (d, w) -> {
                            store.removeLongTermMemory(m);
                            refreshMemories(list);
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
            list.addView(row);
        }
    }

    private void showMemoryEditor() {
        final EditText field = new EditText(this);
        field.setHint("例如：我喜欢简洁的回答");
        field.setTextSize(15);
        field.setMinLines(2);
        field.setPadding(dp(16), dp(12), dp(16), dp(12));
        new android.app.AlertDialog.Builder(this)
                .setTitle("添加长期记忆")
                .setView(field)
                .setPositiveButton("添加", (d, w) -> {
                    store.addLongTermMemory(field.getText().toString().trim());
                    toast("已添加");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 导入导出 ----------------

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        File petsRoot = new File(getFilesDir(), "pets");
        CodexPetImporter importer = new CodexPetImporter(petsRoot);
        try {
            CodexPetImporter.InstalledPackage pkg =
                    importer.importFrom(getContentResolver().openInputStream(uri), true);
            PetStore.Appearance a = store.appearance();
            a.packageId = pkg.id;
            store.saveAppearance(a);
            refreshPetLabel();
            restartOverlay();
            toast("已导入：" + pkg.displayName);
        } catch (CodexPetImporter.PetPackageException e) {
            toast("导入失败：" + e.code);
        } catch (Throwable t) {
            // 低版本设备可能抛 Error（如 NoSuchMethodError），必须挡住避免崩溃
            android.util.Log.e("PetImport", "import failed", t);
            toast("导入失败：" + t.getClass().getSimpleName());
        }
    }

    private void exportCurrent() {
        PetStore.Appearance a = store.appearance();
        if (a.packageId.isEmpty() || !PetContracts.isValidPackageId(a.packageId)) {
            toast("还没有导入桌宠");
            return;
        }
        File dir = new File(new File(getFilesDir(), "pets"), a.packageId);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            toast("桌宠素材不存在");
            return;
        }
        File out = new File(getExternalFilesDir(null), a.packageId + ".codex-pet.zip");
        boolean ok = zipDirectory(dir, out);
        toast(ok ? "已导出到：" + out.getAbsolutePath() : "导出失败");
    }

    private boolean zipDirectory(File dir, File out) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (File f : files) {
                if (f.getName().startsWith(".")) continue;
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 桌宠素材列表 ----------------

    /** 渲染已导入素材列表（卡片：缩略图 + 名称 + 版本 + 使用中标记 + ⋮ 菜单）。 */
    private void refreshPetList() {
        if (petListContainer == null || petImporter == null) return;
        petListContainer.removeAllViews();

        List<CodexPetImporter.InstalledInfo> list = petImporter.listInstalled();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有导入桌宠素材");
            empty.setTextSize(13);
            empty.setTextColor(0xFF8A8A93);
            empty.setPadding(dp(16), dp(14), dp(16), dp(14));
            petListContainer.addView(empty);
            return;
        }

        String currentId = store.appearance().packageId;
        for (CodexPetImporter.InstalledInfo info : list) {
            petListContainer.addView(petCard(info, info.id.equals(currentId)));
        }
    }

    /** 单个素材卡片。 */
    private View petCard(final CodexPetImporter.InstalledInfo info, final boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(glass(dp(18), 0xFFFFFFFF, active ? 0x554C6EF5 : 0x14000000));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(6);
        card.setLayoutParams(cardLp);

        // 缩略图：取精灵图首帧
        card.addView(petThumbnail(info.id, dp(48)));

        // 文字区
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = dp(12);
        col.setLayoutParams(colLp);

        TextView name = new TextView(this);
        name.setText(info.displayName + (active ? "  ✓ 使用中" : ""));
        name.setTextSize(15);
        name.setTextColor(active ? 0xFF4C6EF5 : 0xFF1B1B1F);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(name);

        TextView meta = new TextView(this);
        meta.setText(info.version + " · " + formatSize(info.sizeBytes)
                + (info.valid ? "" : " · 素材缺失"));
        meta.setTextSize(12);
        meta.setTextColor(info.valid ? 0xFF8A8A93 : 0xFFB3261E);
        col.addView(meta);

        card.addView(col);

        // ⋮ 菜单
        TextView menu = new TextView(this);
        menu.setText("⋮");
        menu.setTextSize(20);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(12), dp(8), dp(12), dp(8));
        menu.setOnClickListener(v -> showPetMenu(info, active));
        card.addView(menu);

        // 点击卡片直接切换
        card.setOnClickListener(v -> {
            if (!active) usePet(info);
        });
        return card;
    }

    /** 读取精灵图首帧作为缩略图。 */
    private View petThumbnail(String packageId, int size) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setBackground(glass(dp(12), 0xFFF2F2F7, 0x00000000));
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

        File dir = new File(new File(getFilesDir(), "pets"), packageId);
        File sheet = null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".webp") || n.endsWith(".png")) { sheet = f; break; }
            }
        }
        if (sheet == null) return iv;

        try {
            // 只读首帧矩形，避免把整张 1536x1872 图解码进内存
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(sheet.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= PetContracts.CODEX_FRAME_WIDTH) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            android.graphics.Bitmap full = android.graphics.BitmapFactory.decodeFile(
                    sheet.getAbsolutePath(), opts);
            if (full != null) {
                int fw = PetContracts.CODEX_FRAME_WIDTH / sample;
                int fh = PetContracts.CODEX_FRAME_HEIGHT / sample;
                fw = Math.min(fw, full.getWidth());
                fh = Math.min(fh, full.getHeight());
                android.graphics.Bitmap first = android.graphics.Bitmap.createBitmap(full, 0, 0, fw, fh);
                iv.setImageBitmap(first);
            }
        } catch (Throwable ignored) {}
        return iv;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /** 切换使用某个素材。 */
    private void usePet(final CodexPetImporter.InstalledInfo info) {
        if (!info.valid) {
            toast("该素材文件缺失，无法使用");
            return;
        }
        PetStore.Appearance a = store.appearance();
        a.packageId = info.id;
        store.saveAppearance(a);
        refreshPetList();
        refreshPetLabel();
        // 让正在运行的桌宠立刻换上，无需重启 App
        PetOverlayService service = PetOverlayService.current();
        if (service != null) {
            service.reloadPet();
        } else {
            startOverlayService();
        }
        toast("已切换到：" + info.displayName);
    }

    /** 素材卡片的 ⋮ 菜单。 */
    private void showPetMenu(final CodexPetImporter.InstalledInfo info, boolean active) {
        String[] options = {"使用", "导出", "删除", "取消"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(info.displayName)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        usePet(info);
                    } else if (which == 1) {
                        exportPet(info.id);
                    } else if (which == 2) {
                        confirmDeletePet(info, active);
                    }
                })
                .show();
    }

    private void confirmDeletePet(final CodexPetImporter.InstalledInfo info, final boolean active) {
        String msg = active
                ? "这是当前正在使用的桌宠，删除后桌面上将不再显示角色。确定删除吗？"
                : "删除后需要重新导入才能恢复，确定吗？";
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除「" + info.displayName + "」")
                .setMessage(msg)
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        petImporter.delete(info.id);
                        if (active) {
                            // 当前素材被删 -> 清空选择并移除桌宠
                            PetStore.Appearance a = store.appearance();
                            a.packageId = "";
                            store.saveAppearance(a);
                            PetOverlayService service = PetOverlayService.current();
                            if (service != null) service.clearPet();
                        }
                        refreshPetList();
                        refreshPetLabel();
                        toast("已删除");
                    } catch (Exception e) {
                        toast("删除失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出指定素材为 .codex-pet.zip。 */
    private void exportPet(String packageId) {
        File dir = new File(new File(getFilesDir(), "pets"), packageId);
        File out = new File(getExternalFilesDir(null), packageId + ".codex-pet.zip");
        boolean ok = zipDirectory(dir, out);
        toast(ok ? "已导出到：" + out.getAbsolutePath() : "导出失败");
    }

    /** 拖动分区滑块时，在桌宠上显示红色分界线；同时临时把桌宠挪到屏幕中间方便观察。 */
    private void showGuideLines() {
        PetOverlayService service = PetOverlayService.current();
        if (service == null) return;
        PetStore.Appearance a = store.appearance();
        service.setGuideLines(a.headBoundary, a.bodyBoundary);
    }

    private void hideGuideLines() {
        PetOverlayService service = PetOverlayService.current();
        if (service == null) return;
        service.setGuideLines(-1f, -1f);
    }

    private void refreshPetLabel() {
        if (petNameLabel == null) return;
        PetStore.Appearance a = store.appearance();
        if (a.packageId.isEmpty()) {
            petNameLabel.setText("当前桌宠：未导入\n点击下方「导入 .codex-pet.zip」添加角色");
            return;
        }
        File dir = new File(new File(getFilesDir(), "pets"), a.packageId);
        File sheet = null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".webp") || n.endsWith(".png")) { sheet = f; break; }
            }
        }
        petNameLabel.setText(sheet == null
                ? "当前桌宠：" + a.packageId + "（素材缺失）"
                : "当前桌宠：" + a.packageId + "\n" + sheet.getName());
    }

    // ---------------- 服务 ----------------

    private void ensureOverlayPermission() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    private void startOverlayService() {
        if (!store.appearance().enabled) return;
        try {
            startService(new Intent(this, PetOverlayService.class));
        } catch (Exception e) {
            toast("启动桌宠失败，请先授予悬浮窗权限");
        }
    }

    private final android.os.Handler appearanceHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable appearanceRunnable = this::applyAppearanceNow;

    /** 防抖：250ms 内的连续拖动只应用一次。 */
    private void scheduleAppearanceApply() {
        appearanceHandler.removeCallbacks(appearanceRunnable);
        appearanceHandler.postDelayed(appearanceRunnable, 250);
    }

    /** 立即把外观应用到运行中的桌宠（不重启服务）。 */
    private void applyAppearanceNow() {
        appearanceHandler.removeCallbacks(appearanceRunnable);
        PetOverlayService service = PetOverlayService.current();
        if (service == null) {
            // 服务未运行则启动它
            startOverlayService();
            return;
        }
        service.applyAppearance();
    }

    @Override protected void onDestroy() {
        appearanceHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 重启标志：防止快速连续重启导致服务在销毁过程中重复添加悬浮窗。 */
    private boolean restarting = false;

    /**
     * 安全重启悬浮窗服务。
     * 频繁调stopService/startService 会在 onDestroy 未完成时再次 addView 而崩溃，
     * 因此这里加防抖与重入保护，并等待更长时间确保旧实例已完全销毁。
     */
    private void restartOverlay() {
        if (restarting) return;
        restarting = true;
        try {
            stopService(new Intent(this, PetOverlayService.class));
        } catch (Throwable ignored) {}
        appearanceHandler.postDelayed(() -> {
            restarting = false;
            startOverlayService();
        }, 600);
    }
}
