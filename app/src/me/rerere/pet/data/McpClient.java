package me.rerere.pet.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议客户端。
 * 主要实现 Streamable HTTP：POST JSON-RPC，响应可能是 JSON 或 SSE 流。
 * SSE 旧协议尽力支持（先 GET 发现 endpoint，再 POST）。
 */
public final class McpClient {

    /** 协议版本。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int DEFAULT_TIMEOUT_MS = 20_000;

    /** 一个 MCP 工具。 */
    public static final class Tool {
        public final String serverId;
        public final String serverName;
        public final String name;
        public final String description;
        public final String inputSchema;

        public Tool(String serverId, String serverName, String name,
                    String description, String inputSchema) {
            this.serverId = serverId;
            this.serverName = serverName;
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        /** 发给模型时使用的全名，避免不同服务器同名工具冲突。 */
        public String qualifiedName() {
            String safeServer = serverId.replaceAll("[^a-zA-Z0-9_]", "_");
            String safeTool = name.replaceAll("[^a-zA-Z0-9_]", "_");
            return "mcp__" + safeServer + "__" + safeTool;
        }
    }

    /** 会话状态（保存 Mcp-Session-Id）。 */
    public static final class Session {
        private String sessionId;
        private String endpoint;

        public String id() { return sessionId; }
    }

    public static final class McpException extends Exception {
        public McpException(String message) { super(message); }
    }

    private McpClient() {}

    /** 拉取工具列表（含 initialize 握手）。 */
    public static List<Tool> listTools(McpStore.Server server) throws Exception {
        Session session = new Session();
        initialize(server, session);
        JSONObject result = call(server, session, "tools/list", new JSONObject());
        List<Tool> out = new ArrayList<>();
        JSONArray tools = result.optJSONArray("tools");
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.getJSONObject(i);
                String name = t.optString("name", "");
                if (name.isEmpty()) continue;
                String desc = t.optString("description", "");
                JSONObject schema = t.optJSONObject("inputSchema");
                out.add(new Tool(server.id, server.name, name, desc,
                        schema == null ? "{\"type\":\"object\",\"properties\":{}}" : schema.toString()));
            }
        }
        return out;
    }

    /** 调用工具，返回文本结果。 */
    public static String callTool(McpStore.Server server, Session session,
                                  String toolName, JSONObject args) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", toolName);
        params.put("arguments", args == null ? new JSONObject() : args);
        JSONObject result = call(server, session, "tools/call", params);
        return extractText(result);
    }

    /** 组合调用：握手 + 调用 + 返回文本（供 AI 工具循环使用）。 */
    public static String callToolOnce(McpStore.Server server, String toolName, JSONObject args)
            throws Exception {
        Session session = new Session();
        initialize(server, session);
        return callTool(server, session, toolName, args);
    }

    private static void initialize(McpStore.Server server, Session session) throws Exception {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "rerere-pet");
        clientInfo.put("version", "1.0");
        params.put("clientInfo", clientInfo);

        call(server, session, "initialize", params);
        // 通知已初始化（无响应）
        try {
            notify(server, session, "notifications/initialized", new JSONObject());
        } catch (Exception ignored) {}
    }

    /** 发起一次 JSON-RPC 调用并返回 result。 */
    private static JSONObject call(McpStore.Server server, Session session,
                                   String method, JSONObject params) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("jsonrpc", "2.0");
        payload.put("id", System.nanoTime() & 0x7FFFFFFF);
        payload.put("method", method);
        payload.put("params", params);

        String body = post(server, session, payload.toString(), DEFAULT_TIMEOUT_MS);
        if (body == null || body.trim().isEmpty()) return new JSONObject();

        JSONObject response = parseRpcResponse(body, payload.opt("id"));
        if (response == null) throw new McpException("响应格式无效");
        if (response.has("error")) {
            JSONObject err = response.optJSONObject("error");
            String msg = err == null ? response.optString("error") : err.optString("message", "调用失败");
            throw new McpException(msg);
        }
        return response.optJSONObject("result") == null
                ? new JSONObject() : response.getJSONObject("result");
    }

    private static void notify(McpStore.Server server, Session session,
                               String method, JSONObject params) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("params", params);
        post(server, session, payload.toString(), 5_000);
    }

    /** POST 请求，返回响应文本（已处理 SSE 与 JSON 两种情况）。 */
    private static String post(McpStore.Server server, Session session,
                               String json, int timeoutMs) throws Exception {
        String endpoint = resolveEndpoint(server, session);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (session.id() != null) {
                conn.setRequestProperty("Mcp-Session-Id", session.id());
            }
            for (Map.Entry<String, String> e : server.headers.entrySet()) {
                if (e.getKey() == null || e.getKey().trim().isEmpty()) continue;
                try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Exception ignored) {}
            }

            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            String newSession = conn.getHeaderField("Mcp-Session-Id");
            if (newSession != null && !newSession.isEmpty()) {
                session.sessionId = newSession;
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            if (code < 200 || code >= 300) {
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP ").append(code);
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(' ').append(trim(text, 160));
                }
                throw new McpException(sb.toString());
            }
            String ctype = conn.getContentType();
            if (ctype != null && ctype.toLowerCase().contains("text/event-stream")) {
                return extractSseData(text);
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    /** SSE 传输需要先 GET 拿到 POST endpoint。 */
    private static String resolveEndpoint(McpStore.Server server, Session session) throws Exception {
        if (!McpStore.TRANSPORT_SSE.equals(server.transport)) return server.url.trim();
        if (session.endpoint != null) return session.endpoint;
        // 打开 SSE 流，读取首个 endpoint 事件
        HttpURLConnection conn = (HttpURLConnection) new URL(server.url.trim()).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("Accept", "text/event-stream");
            for (Map.Entry<String, String> e : server.headers.entrySet()) {
                if (e.getKey() == null || e.getKey().trim().isEmpty()) continue;
                try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Exception ignored) {}
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.startsWith("data:")) {
                        String data = t.substring(5).trim();
                        if (data.startsWith("/") || data.startsWith("http")) {
                            String base = server.url.trim();
                            session.endpoint = data.startsWith("http") ? data
                                    : base.replaceAll("/[^/]*$", "") + data;
                            return session.endpoint;
                        }
                    }
                    if (t.startsWith("event:") && t.contains("endpoint")) {
                        // 下一行是 data
                    }
                }
            }
        } catch (Exception e) {
            // 回退：直接当普通端点用
        } finally {
            conn.disconnect();
        }
        session.endpoint = server.url.trim();
        return session.endpoint;
    }

    /** 从 SSE 文本里取出第一条 data 的 JSON。 */
    private static String extractSseData(String text) {
        if (text == null) return "";
        StringBuilder data = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("data:")) {
                String d = line.substring(5).trim();
                if (d.startsWith("{")) data.append(d);
            }
        }
        return data.length() > 0 ? data.toString() : text;
    }

    /** 响应可能是 JSON 或 SSE 拼接文本，取出匹配 id 的那条 JSON-RPC 响应。 */
    private static JSONObject parseRpcResponse(String body, Object id) {
        String trimmed = body.trim();
        // 直接 JSON
        JSONObject direct = tryParse(trimmed);
        if (direct != null && direct.has("jsonrpc")) return direct;

        // SSE 可能有多条 data
        int idx = 0;
        while (idx < trimmed.length()) {
            int next = trimmed.indexOf("data:", idx);
            if (next < 0) break;
            int end = trimmed.indexOf('\n', next);
            String chunk = (end < 0 ? trimmed.substring(next + 5)
                    : trimmed.substring(next + 5, end)).trim();
            JSONObject o = tryParse(chunk);
            if (o != null) {
                if (id == null || !o.has("id") || String.valueOf(o.opt("id")).equals(String.valueOf(id))
                        || o.has("result") || o.has("error")) {
                    return o;
                }
            }
            idx = end < 0 ? trimmed.length() : end + 1;
        }
        return tryParse(trimmed);
    }

    private static JSONObject tryParse(String text) {
        if (text == null || text.isEmpty()) return null;
        try { return new JSONObject(text); } catch (Exception e) { return null; }
    }

    /** 从 tools/call 结果里抽取文本。 */
    private static String extractText(JSONObject result) {
        if (result == null) return "";
        JSONArray content = result.optJSONArray("content");
        if (content != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c == null) continue;
                String type = c.optString("type", "");
                if ("text".equals(type)) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.optString("text", ""));
                } else if (c.has("text")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.optString("text", ""));
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        if (result.has("structuredContent")) {
            return result.opt("structuredContent").toString();
        }
        return result.toString();
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
