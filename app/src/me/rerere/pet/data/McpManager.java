package me.rerere.pet.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP 管理器：多服务器状态检测、工具聚合、工具调用。
 * 另内置一组"记忆工具"，让记忆也走 MCP 式的调用方式。
 */
public final class McpManager {

    /** 定时状态检测间隔。 */
    public static final long STATUS_POLL_INTERVAL_MS = 30_000L;

    /** 内置服务器 ID（记忆）。 */
    public static final String BUILTIN_MEMORY_ID = "builtin_memory";

    /** 统一工具描述，内置工具与外部 MCP 工具共用。 */
    public static final class ToolSpec {
        public final String qualifiedName;
        public final String description;
        public final String inputSchema;
        public final boolean builtin;
        public final String serverId;
        public final String serverName;
        public final String rawName;

        ToolSpec(String qualifiedName, String description, String inputSchema,
                 boolean builtin, String serverId, String serverName, String rawName) {
            this.qualifiedName = qualifiedName;
            this.description = description;
            this.inputSchema = inputSchema;
            this.builtin = builtin;
            this.serverId = serverId;
            this.serverName = serverName;
            this.rawName = rawName;
        }
    }

    /** 单个服务器的运行状态。 */
    public static final class Status {
        public boolean connected = false;
        public String lastError = null;
        public long lastCheckedAt = 0L;
        public int toolCount = 0;
        public final List<McpClient.Tool> tools = new ArrayList<>();
    }

    public interface Listener {
        void onStatusChanged();
    }

    private final McpStore store;
    private final PetStore petStore;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Status> statuses = new LinkedHashMap<>();

    private Listener listener;
    private boolean polling = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            refreshAll();
            if (polling) mainHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS);
        }
    };

    public McpManager(Context context, PetStore petStore) {
        this.store = new McpStore(context);
        this.petStore = petStore;
    }

    public McpStore store() { return store; }

    public void setListener(Listener l) { this.listener = l; }

    public void startPolling() {
        if (polling) return;
        polling = true;
        mainHandler.post(pollRunnable);
    }

    public void stopPolling() {
        polling = false;
        mainHandler.removeCallbacks(pollRunnable);
    }

    public Status statusOf(String serverId) {
        Status s = statuses.get(serverId);
        if (s == null) {
            s = new Status();
            statuses.put(serverId, s);
        }
        return s;
    }

    /** 内置服务器状态（记忆/日期时间/联网搜索）。 */
    public List<Status> builtinServers() {
        List<Status> out = new ArrayList<>();
        out.add(builtinStatus());
        out.add(makeBuiltinStatus(BuiltinMcp.DATE_ID, 1));
        List<String> memories = null;
        SearchProvider.Config sc = petStore.searchConfig();
        SearchProvider.Meta sm = SearchProvider.metaOf(sc.providerId);
        String searchErr = SearchProvider.validate(sc);
        Status search = statusOf(BuiltinMcp.SEARCH_ID);
        search.lastCheckedAt = System.currentTimeMillis();
        search.connected = searchErr == null;
        search.toolCount = 1;
        search.lastError = searchErr == null ? null : ("当前：" + sm.label + " · " + searchErr);
        out.add(search);
        // 位置信息：未授权时标记为未连接
        Status loc = statusOf(BuiltinMcp.LOCATION_ID);
        loc.lastCheckedAt = System.currentTimeMillis();
        loc.toolCount = LocationHelper.hasPermission() ? 1 : 0;
        loc.connected = LocationHelper.hasPermission();
        loc.lastError = LocationHelper.hasPermission() ? null : "未授予定位权限";
        out.add(loc);

        // 表情控制：仅在 MMD 模式且已加载模型时可用
        Status expr = statusOf(BuiltinMcp.EXPRESSION_ID);
        expr.lastCheckedAt = System.currentTimeMillis();
        boolean exprOk = "mmd".equals(petStore.appearance().renderMode)
                && petStore.appearance().mmdModelUrl != null
                && !petStore.appearance().mmdModelUrl.isEmpty();
        expr.connected = exprOk;
        expr.toolCount = exprOk ? 1 : 0;
        expr.lastError = exprOk ? null : "需要启用 3D 模型（MMD）渲染";
        out.add(expr);
        return out;
    }

    private Status makeBuiltinStatus(String id, int toolCount) {
        Status s = statusOf(id);
        s.connected = true;
        s.lastError = null;
        s.lastCheckedAt = System.currentTimeMillis();
        s.toolCount = toolCount;
        return s;
    }

    /** 内置记忆服务器的状态展示。 */
    public Status builtinStatus() {
        Status s = statusOf(BUILTIN_MEMORY_ID);
        s.connected = true;
        s.lastError = null;
        s.lastCheckedAt = System.currentTimeMillis();
        s.toolCount = builtinTools().size();
        return s;
    }

    /** 检测所有启用的服务器。 */
    public void refreshAll() {
        List<McpStore.Server> servers = store.servers();
        List<Runnable> tasks = new ArrayList<>();
        for (final McpStore.Server server : servers) {
            if (!server.enabled) {
                Status st = statusOf(server.id);
                st.connected = false;
                st.lastError = null;
                st.lastCheckedAt = System.currentTimeMillis();
                st.toolCount = 0;
                st.tools.clear();
                continue;
            }
            tasks.add(() -> checkOne(server));
        }
        for (Runnable t : tasks) executor.execute(t);
        if (tasks.isEmpty()) notifyChanged();
    }

    /** 检测单个服务器。 */
    public void checkOne(final McpStore.Server server) {
        Status st = statusOf(server.id);
        st.lastCheckedAt = System.currentTimeMillis();
        try {
            List<McpClient.Tool> tools = McpClient.listTools(server);
            synchronized (st) {
                st.tools.clear();
                st.tools.addAll(tools);
                st.toolCount = tools.size();
                st.connected = true;
                st.lastError = null;
            }
        } catch (Exception e) {
            synchronized (st) {
                st.connected = false;
                st.toolCount = 0;
                st.tools.clear();
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
                st.lastError = msg;
            }
        }
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) mainHandler.post(() -> listener.onStatusChanged());
    }

    // ---------------- 工具聚合 ----------------

    /** 内置记忆工具（记忆即一个内置 MCP）。 */
    public List<ToolSpec> builtinTools() {
        List<ToolSpec> out = new ArrayList<>();
        out.add(new ToolSpec("memory_add",
                "记住关于用户的长期事实（喜好、习惯、称呼、重要信息）。当用户透露这类信息时调用。",
                schema("content", "要记住的内容，一句话"), true, BUILTIN_MEMORY_ID, "记忆", "memory_add"));
        out.add(new ToolSpec("memory_search",
                "在长期记忆里检索与关键词相关的条目。回答用户问题前可先检索。",
                schema("query", "检索关键词"), true, BUILTIN_MEMORY_ID, "记忆", "memory_search"));
        out.add(new ToolSpec("memory_list",
                "列出全部长期记忆条目。",
                "{\"type\":\"object\",\"properties\":{}}", true, BUILTIN_MEMORY_ID, "记忆", "memory_list"));
        out.add(new ToolSpec("memory_forget",
                "删除一条不再正确的长期记忆。",
                schema("content", "要删除的记忆原文"), true, BUILTIN_MEMORY_ID, "记忆", "memory_forget"));
        out.add(new ToolSpec("history_search",
                "在过去的对话历史中检索相关内容。用户提到以前聊过的事情时调用。",
                schema("query", "检索关键词"), true, BUILTIN_MEMORY_ID, "记忆", "history_search"));
        return out;
    }

    private static String schema(String key, String desc) {
        try {
            JSONObject props = new JSONObject();
            JSONObject item = new JSONObject();
            item.put("type", "string");
            item.put("description", desc);
            props.put(key, item);
            JSONObject root = new JSONObject();
            root.put("type", "object");
            root.put("properties", props);
            JSONArray required = new JSONArray();
            required.put(key);
            root.put("required", required);
            return root.toString();
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    /** 所有可发给模型的工具：内置记忆 + 已连接且允许共享的 MCP 工具。 */
    public List<ToolSpec> availableTools() {
        List<ToolSpec> out = new ArrayList<>(builtinTools());
        // 内置工具（日期时间 / 联网搜索）
        out.addAll(BuiltinMcp.tools());
        for (McpStore.Server server : store.servers()) {
            if (!server.enabled) continue;
            if (!server.shareTools) continue;
            Status st = statusOf(server.id);
            List<McpClient.Tool> tools;
            synchronized (st) { tools = new ArrayList<>(st.tools); }
            for (McpClient.Tool t : tools) {
                out.add(new ToolSpec(t.qualifiedName(), 
                        (t.description == null || t.description.isEmpty())
                                ? ("来自 " + server.name + " 的工具")
                                : t.description,
                        t.inputSchema, false, server.id, server.name, t.name));
            }
        }
        return out;
    }

    /** 执行工具调用，返回文本结果。 */
    public String invoke(ToolSpec spec, JSONObject args) {
        try {
            if (spec.builtin) {
                // 先尝试内置工具集（日期时间 / 联网搜索）
                String builtinResult = BuiltinMcp.invoke(spec.serverId, spec.rawName, args);
                if (builtinResult != null) return builtinResult;
                return invokeBuiltin(spec.rawName, args);
            }
            McpStore.Server server = null;
            for (McpStore.Server s : store.servers()) {
                if (s.id.equals(spec.serverId)) { server = s; break; }
            }
            if (server == null) return "错误：找不到服务器 " + spec.serverName;
            return McpClient.callToolOnce(server, spec.rawName, args);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
            return "调用失败：" + msg;
        }
    }

    /** 内置记忆工具实现。 */
    private String invokeBuiltin(String name, JSONObject args) {
        switch (name) {
            case "memory_add": {
                String content = args.optString("content", "").trim();
                if (content.isEmpty()) return "错误：content 不能为空";
                petStore.addLongTermMemory(content);
                return "已记住：" + content;
            }
            case "memory_search": {
                String q = args.optString("query", "").trim().toLowerCase();
                List<String> hits = new ArrayList<>();
                for (String m : petStore.longTermMemories()) {
                    if (q.isEmpty() || m.toLowerCase().contains(q)) hits.add(m);
                }
                if (hits.isEmpty()) return "没有找到相关记忆。";
                StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 条：\n");
                for (String h : hits) sb.append("- ").append(h).append('\n');
                return sb.toString();
            }
            case "memory_list": {
                List<String> all = petStore.longTermMemories();
                if (all.isEmpty()) return "当前没有长期记忆。";
                StringBuilder sb = new StringBuilder("共 ").append(all.size()).append(" 条：\n");
                for (String m : all) sb.append("- ").append(m).append('\n');
                return sb.toString();
            }
            case "memory_forget": {
                String content = args.optString("content", "").trim();
                if (content.isEmpty()) return "错误：content 不能为空";
                petStore.removeLongTermMemory(content);
                return "已删除记忆：" + content;
            }
            case "history_search": {
                String q = args.optString("query", "").trim().toLowerCase();
                if (q.isEmpty()) return "错误：query 不能为空";
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for (PetStore.Session s : petStore.sessions()) {
                    for (PetStore.Turn t : s.turns) {
                        String u = t.userInput == null ? "" : t.userInput;
                        String a = t.assistantText == null ? "" : t.assistantText;
                        if (u.toLowerCase().contains(q) || a.toLowerCase().contains(q)) {
                            count++;
                            sb.append("[").append(s.title).append("] 用户：").append(u)
                              .append(" / 桌宠：").append(a).append('\n');
                            if (count >= 10) break;
                        }
                    }
                    if (count >= 10) break;
                }
                if (count == 0) return "历史对话里没有找到相关内容。";
                return "找到 " + count + " 条：\n" + sb;
            }
            default:
                return "错误：未知的内置工具 " + name;
        }
    }
}
