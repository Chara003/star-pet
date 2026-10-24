package me.rerere.pet.ui.page;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.List;

import me.rerere.pet.data.McpManager;
import me.rerere.pet.data.McpStore;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.McpEditActivity;
import me.rerere.pet.ui.PetSearchActivity;
import me.rerere.pet.ui.PetSettingsActivity;
import me.rerere.pet.ui.theme.UiTheme;

/**
 * AI 页：提供商 / MCP / 记忆 / 角色设定。
 *
 * ★ 为什么提供商和记忆暂时只放入口：
 *   它们的编辑 UI（模型探测、API Key、记忆增删改）是 MainActivity 里
 *   600+ 行的表单逻辑，包含大量踩过坑的细节（模型列表分页、错误提示、
 *   多提供商切换）。先保证入口可达、数据不丢；等 Phase 5 再逐块搬过来。
 *   这符合"任何时刻 App 都是可用的"这条纪律。
 *
 * ★ MCP 列表是真正搬过来的：它只需要 McpStore + McpManager 的状态查询，
 *   不涉及表单，风险低、收益高（用户最常看的就是"连没连上"）。
 */
public final class AiPage {

    /** 数据变更（切换提供商/删记忆…）后通知宿主重建本页。 */
    public interface Host {
        void onDataChanged();

        /** 请求定位权限（宿主是 Activity，有 requestPermissions）。 */
        void requestLocationPermission();
    }

    /** 内置服务器名（与 McpManager.builtinServers() 的顺序严格一致）。 */
    private static final String[] BUILTIN_NAMES = {
            "记忆", "日期时间", "联网搜索", "位置信息", "表情控制"
    };
    private static final String[] BUILTIN_DESCS = {
            "memory_add / memory_search / memory_list / memory_forget / history_search",
            "datetime_now —— 获取当前日期、时间与星期",
            "search_web —— 联网搜索实时信息",
            "location_get —— 获取当前地理位置（需授权）",
            "play_expression —— 让 3D 模型做表情（仅 MMD 模式）",
    };

    private final Activity activity;
    private final PetStore store;
    private final Host host;

    public AiPage(Activity activity, PetStore store, Host host) {
        this.activity = activity;
        this.store = store;
        this.host = host;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    public View build() {
        SettingsPage page = new SettingsPage(activity);
        page.pageTitle("AI", "助手的大脑：提供商、工具、记忆与人设。");

        /* ---------------- 提供商 ---------------- */
        page.section("AI 提供商");
        List<PetStore.Provider> providers = store.providers();
        PetStore.Provider activeP = store.activeProvider();
        String activeId = (activeP == null) ? "" : activeP.id;
        if (providers.isEmpty()) {
            page.info("还没有配置提供商。点下面的入口添加一个（支持任何 OpenAI 兼容接口）。");
        } else {
            for (PetStore.Provider p : providers) {
                boolean active = p.id != null && p.id.equals(activeId);
                /* ★ 必须是 nav（可点）而不是 valueRowWide：
                   否则"AI 提供商"这一行看起来像设置项却点不动（实测）。
                   点它就进编辑表单（走旧 MainActivity 的公开展示方法，
                   见 showProviderEditorAt）。 */
                StringBuilder sub = new StringBuilder();
                sub.append(p.model == null || p.model.isEmpty() ? "未选择模型" : p.model);
                if (p.baseUrl != null && !p.baseUrl.isEmpty()) {
                    sub.append("　·　").append(p.baseUrl);
                }
                final String pid = p.id;
                page.nav((active ? "● " : "○ ") + displayName(p),
                        (active ? "当前使用中　·　" : "点击切换为当前　·　") + sub,
                        () -> onProviderTap(pid, active));
            }
        }
        page.nav("+ 添加服务商", "支持任何 OpenAI 兼容接口",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.ui.ProviderEditActivity.class)));

        /* ---------------- MCP ---------------- */
        page.section("MCP 服务器");
        /* ★★ 内置服务器：这 5 个【不存在】McpStore 里，是 McpManager 硬编码的。
           旧 MainActivity 是手动把它们拼进列表的；第一版我只读了 McpStore.servers()，
           于是"记忆 / 日期时间 / 联网搜索 / 位置信息 / 表情控制"全部消失
           （实测"内置 mcp 服务器现在怎么不会显示了"）。 */
        try {
            McpManager mgr = new McpManager(activity, store);
            List<McpManager.Status> builtin = mgr.builtinServers();
            for (int i = 0; i < BUILTIN_NAMES.length; i++) {
                McpManager.Status st = (builtin != null && i < builtin.size())
                        ? builtin.get(i) : null;
                String desc = BUILTIN_DESCS[i];
                if ("位置信息".equals(BUILTIN_NAMES[i])
                        && !me.rerere.pet.data.LocationHelper.hasPermission()) {
                    desc = desc + "\n⚠ 未授权定位权限 —— 点这里去授权";
                }
                if (st != null) {
                    desc = desc + "\n" + (st.connected
                            ? "已就绪" + (st.toolCount > 0 ? " · " + st.toolCount + " 个工具" : "")
                            : "⚠ " + (st.lastError == null ? "未就绪" : st.lastError));
                }
                final int idx = i;
                page.nav("内置 · " + BUILTIN_NAMES[i], desc, () -> onBuiltinTap(idx));
            }
        } catch (Throwable t) {
            page.info("读取内置服务器状态失败：" + t.getClass().getSimpleName());
        }

        /* 用户自己添加的服务器 */
        try {
            McpStore mcpStore = new McpStore(activity);
            List<McpStore.Server> servers = mcpStore.servers();
            for (McpStore.Server sv : servers) {
                final String sid = sv.id;
                String sub = (sv.enabled ? "已启用" : "已停用")
                        + ((sv.url == null || sv.url.isEmpty()) ? "" : "\n" + sv.url);
                page.nav(sv.name == null ? "(未命名)" : sv.name, sub,
                        () -> activity.startActivity(new Intent(activity, McpEditActivity.class)
                                .putExtra("id", sid)));
            }
            if (servers == null || servers.isEmpty()) {
                page.info("还没有自己添加的 MCP 服务器。");
            }
        } catch (Throwable t) {
            page.info("读取 MCP 配置失败：" + t.getClass().getSimpleName());
        }
        page.nav("添加 MCP 服务器", "支持 HTTP / SSE，可自定义请求头",
                () -> activity.startActivity(new Intent(activity, McpEditActivity.class)));

        /* ---------------- 角色设定 ---------------- */
        page.section("角色设定");
        String persona = store.personaPrompt();
        if (persona == null || persona.trim().isEmpty()) {
            page.info("还没有写人设。写一段话，桌宠就会按这个性格说话。");
        } else {
            String preview = persona.trim();
            if (preview.length() > 120) preview = preview.substring(0, 120) + "…";
            page.info(preview);
        }
        page.nav("编辑角色设定", "人设、温度、思考深度",
                () -> activity.startActivity(new Intent(activity, PetSettingsActivity.class)));

        /* ---------------- 记忆 ---------------- */
        page.section("长期记忆");
        List<String> memories = store.longTermMemories();
        if (memories == null || memories.isEmpty()) {
            page.info("还没有记忆。聊一会儿，或者让 AI 自己记下来。");
        } else {
            page.valueRow("已记录", memories.size() + " 条");
            for (int i = 0; i < Math.min(memories.size(), 5); i++) {
                String m = memories.get(i);
                if (m == null) continue;
                if (m.length() > 60) m = m.substring(0, 60) + "…";
                page.info("· " + m);
            }
            if (memories.size() > 5) {
                page.info("…还有 " + (memories.size() - 5) + " 条");
            }
        }
        page.info("记忆会随对话一起发给模型，帮助桌宠记住你。");
        page.nav("管理记忆", "查看 / 添加 / 删除",
                () -> activity.startActivity(new Intent(activity,
                        me.rerere.pet.ui.MemoryActivity.class)));

        /* ---------------- 工具 ---------------- */
        page.section("联网");
        page.nav("联网搜索", "配置搜索服务，让 AI 能查最新信息",
                () -> activity.startActivity(new Intent(activity, PetSearchActivity.class)));

        return page.build();
    }

    /**
     * 点提供商行：未选中 -> 设为当前；已选中 -> 进编辑页。
     *
     * ★ 为什么"点一下就切换"而不是"长按/二级菜单"：
     *   实测"点进去点保存之后不会选中，目前还没有切换提供商的方式"。
     *   提供商是高频切换对象（换模型/换额度时来回切），
     *   最省事的交互就是"点谁用谁"，编辑走行内第二段提示或长按。
     *   已选中的那个再点才进编辑，避免"只想切换却误进表单"。
     */
    private void onProviderTap(String providerId, boolean alreadyActive) {
        if (alreadyActive) {
            openProviderEditor(providerId);
            return;
        }
        store.setActiveProvider(providerId);
        toast("已切换到：" + providerId);
        if (host != null) host.onDataChanged();
    }

    /** 打开某个提供商的编辑页（新页面，不再跳旧 MainActivity）。 */
    private void openProviderEditor(String providerId) {
        Intent i = new Intent(activity, me.rerere.pet.ui.ProviderEditActivity.class);
        i.putExtra(me.rerere.pet.ui.ProviderEditActivity.EXTRA_ID, providerId);
        activity.startActivity(i);
    }

    /**
     * 点内置服务器条目。
     *
     * ★ 位置信息必须【先要权限】再显示说明（旧 MainActivity.onBuiltinClick 的行为）：
     *   没有 ACCESS_FINE_LOCATION 时 location_get 工具是禁用的，
     *   只弹一个"内置工具共 N 个"的说明框会让用户以为它能用。
     *   旧页面有请求逻辑，我第一版漏了（用户一眼看出来"你确定会请求权限吗"）。
     * ★ 权限结果回调在 AiPageHost（由 MainTabsActivity 实现）。
     */
    private void onBuiltinTap(int index) {
        if (index < BUILTIN_NAMES.length && "位置信息".equals(BUILTIN_NAMES[index])) {
            if (me.rerere.pet.data.LocationHelper.hasPermission()) {
                showBuiltinInfo(index);
            } else if (host != null) {
                host.requestLocationPermission();
            } else {
                toast("需要定位权限，请到「位置信息」里授权");
            }
            return;
        }
        showBuiltinInfo(index);
    }

    /** 内置服务器详情（工具清单来自 BuiltinMcp，不是写死的文案）。 */
    private void showBuiltinInfo(int index) {
        StringBuilder sb = new StringBuilder();
        sb.append(BUILTIN_NAMES[index]).append("\n\n").append(BUILTIN_DESCS[index]);
        try {
            java.util.List<me.rerere.pet.data.McpManager.ToolSpec> tools =
                    me.rerere.pet.data.BuiltinMcp.tools();
            sb.append("\n\n内置工具共 ").append(tools.size()).append(" 个。");
        } catch (Throwable ignored) {}
        new android.app.AlertDialog.Builder(activity)
                .setTitle("内置 MCP 服务器")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    private String displayName(PetStore.Provider p) {
        if (p.name != null && !p.name.trim().isEmpty()) return p.name.trim();
        if (p.baseUrl != null && !p.baseUrl.isEmpty()) return p.baseUrl;
        return "(未命名)";
    }
}
