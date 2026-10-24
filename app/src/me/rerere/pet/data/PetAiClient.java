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
import java.util.concurrent.TimeUnit;

import me.rerere.pet.core.PetContracts;

/**
 * OpenAI 兼容客户端。
 * 关键：stream=false；不回传思考过程（不请求也不解析 reasoning_content）。
 */
public final class PetAiClient {

    public static final class Result {
        public final boolean ok;
        public final String text;
        public final PetContracts.VisualHint hint;
        public final String errorCode;
        public final String rawContent;
        /** 模型要求记住的内容（可为空）。 */
        public final String memory;
        /** 本轮实际调用的工具名（用于界面展示，证明 MCP 生效）。 */
        public final List<String> toolCalls = new ArrayList<>();
        /** token 用量，-1 表示服务端未返回。 */
        public int promptTokens = -1;
        public int completionTokens = -1;
        /** 请求耗时（毫秒）。 */
        public long elapsedMs = 0L;
        /** 模型思考过程。 */
        public String reasoning = "";
        /** 结构化工具事件。 */
        public final List<PetStore.ToolEvent> toolEvents = new ArrayList<>();

        public Result(boolean ok, String text, PetContracts.VisualHint hint,
                      String errorCode, String rawContent) {
            this(ok, text, hint, errorCode, rawContent, null);
        }

        public Result(boolean ok, String text, PetContracts.VisualHint hint,
                      String errorCode, String rawContent, String memory) {
            this.ok = ok; this.text = text; this.hint = hint;
            this.errorCode = errorCode; this.rawContent = rawContent;
            this.memory = memory;
        }
    }

    private PetAiClient() {}

    /**
     * 生成桌宠回应。
     * @param history 最近对话轮次（保留上下文）
     * @param longTermMemory 长期记忆文本
     */
    public static Result chat(PetStore.Provider provider, String persona,
                              List<PetStore.Turn> history, String longTermMemory,
                              String input) throws Exception {
        if (provider == null || !provider.isUsable())
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_provider_unavailable", null);
        if (input != null && input.codePointCount(0, input.length()) > PetContracts.MAX_PET_INPUT_CODE_POINTS)
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_input_too_long", null);

        JSONObject body = new JSONObject();
        body.put("model", provider.model);
        body.put("temperature", safeTemperature(provider.temperature));
        body.put("max_tokens", provider.maxTokens);
        body.put("stream", false);
        applyReasoning(body, provider);

        JSONArray messages = new JSONArray();
        messages.put(buildSystemMessage(persona, longTermMemory));

        // 保留上下文：最近若干轮
        int start = Math.max(0, history.size() - PetContracts.MAX_PET_DIALOGUE_ROUNDS);
        for (int i = start; i < history.size(); i++) {
            PetStore.Turn t = history.get(i);
            if (t.userInput != null && !t.userInput.trim().isEmpty()) {
                JSONObject u = new JSONObject();
                u.put("role", "user");
                u.put("content", t.userInput);
                messages.put(u);
            }
            if (t.assistantText != null && !t.assistantText.trim().isEmpty()) {
                JSONObject a = new JSONObject();
                a.put("role", "assistant");
                a.put("content", t.assistantText);
                messages.put(a);
            }
        }
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", input);
        messages.put(userMsg);
        body.put("messages", messages);

        String raw = post(provider.chatEndpoint(), provider.apiKey, body.toString());
        return parseResponse(raw);
    }

    /** 工具调用最大轮数，防止无限循环。 */
    private static final int MAX_TOOL_ROUNDS = 6;

    /**
     * 带工具调用的对话：把工具列表发给模型，模型可自主调用，
     * 执行结果回填后再次请求，直到模型给出最终回复。
     */
    public static Result chatWithTools(PetStore.Provider provider, String persona,
                                       List<PetStore.Turn> history, String longTermMemory,
                                       String input, List<McpManager.ToolSpec> tools,
                                       McpManager mcp) throws Exception {
        if (provider == null || !provider.isUsable())
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_provider_unavailable", null);
        if (input != null && input.codePointCount(0, input.length()) > PetContracts.MAX_PET_INPUT_CODE_POINTS)
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_input_too_long", null);

        JSONArray messages = new JSONArray();
        messages.put(buildSystemMessage(persona, longTermMemory));

        int start = Math.max(0, history.size() - PetContracts.MAX_PET_DIALOGUE_ROUNDS);
        for (int i = start; i < history.size(); i++) {
            PetStore.Turn t = history.get(i);
            if (t.userInput != null && !t.userInput.trim().isEmpty()) {
                messages.put(msg("user", t.userInput));
            }
            if (t.assistantText != null && !t.assistantText.trim().isEmpty()) {
                messages.put(msg("assistant", t.assistantText));
            }
        }
        messages.put(msg("user", input));

        JSONArray toolSchemas = buildToolSchemas(tools);
        String url = provider.chatEndpoint();

        long startedAt = System.currentTimeMillis();
        List<String> calledTools = new ArrayList<>();
        List<PetStore.ToolEvent> events = new ArrayList<>();
        StringBuilder reasoningBuf = new StringBuilder();
        String raw = null;
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject body = new JSONObject();
            body.put("model", provider.model);
            body.put("temperature", safeTemperature(provider.temperature));
            body.put("max_tokens", provider.maxTokens);
            body.put("stream", false);
            applyReasoning(body, provider);
            body.put("messages", messages);
            if (toolSchemas.length() > 0) {
                body.put("tools", toolSchemas);
                body.put("tool_choice", "auto");
            }

            android.util.Log.i("PetAiClient", "round#" + round
                    + " tools=" + toolSchemas.length() + " msgs=" + messages.length());
            raw = post(url, provider.apiKey, body.toString());

            JSONObject message = firstMessage(raw);
            if (message == null)
                return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_empty_response", null);

            // 捕获思考过程（不同服务商字段名不同）
            String reason = message.optString("reasoning_content", "");
            if (reason.isEmpty()) reason = message.optString("reasoning", "");
            if (!reason.isEmpty()) reasoningBuf.append(reason);

            JSONArray toolCalls = message.optJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.length() == 0) break;

            // 记录助手发起的工具调用
            messages.put(message);

            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String callId = tc.optString("id", "call_" + i);
                JSONObject fn = tc.optJSONObject("function");
                String fnName = fn == null ? "" : fn.optString("name", "");
                String fnArgs = fn == null ? "{}" : fn.optString("arguments", "{}");

                String resultText;
                McpManager.ToolSpec spec = findTool(tools, fnName);
                if (spec == null) {
                    resultText = "错误：未知工具 " + fnName;
                } else {
                    JSONObject args;
                    try { args = new JSONObject(fnArgs.isEmpty() ? "{}" : fnArgs); }
                    catch (Exception e) { args = new JSONObject(); }
                    resultText = mcp.invoke(spec, args);
                }
                if (resultText == null) resultText = "";
                if (resultText.length() > 4000) resultText = resultText.substring(0, 4000);
                calledTools.add(fnName);
                events.add(new PetStore.ToolEvent(fnName, toolTitle(fnName, spec),
                        brief(resultText, 400), brief(fnArgs, 300)));
                android.util.Log.i("PetAiClient", "tool#" + round + " " + fnName
                        + " -> " + brief(resultText, 120));

                JSONObject toolMsg = new JSONObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                toolMsg.put("content", resultText);
                messages.put(toolMsg);
            }
        }

        // 若因轮数耗尽仍停在工具调用，补一次禁用工具的请求以取回文本
        try {
            JSONObject last = firstMessage(raw);
            if (last != null && last.optJSONArray("tool_calls") != null
                    && last.optJSONArray("tool_calls").length() > 0) {
                JSONObject body = new JSONObject();
                body.put("model", provider.model);
                body.put("temperature", safeTemperature(provider.temperature));
                body.put("max_tokens", provider.maxTokens);
                body.put("stream", false);
                applyReasoning(body, provider);
                body.put("messages", messages);
                body.put("tools", toolSchemas);
                body.put("tool_choice", "none");
                raw = post(url, provider.apiKey, body.toString());
            }
        } catch (Exception ignored) {}

        if (raw == null) {
            Result r = new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_empty_response", null);
            r.elapsedMs = System.currentTimeMillis() - startedAt;
            return r;
        }
        Result r = parseResponse(raw);
        r.toolCalls.addAll(calledTools);
        r.toolEvents.addAll(events);
        r.reasoning = reasoningBuf.toString();
        r.elapsedMs = System.currentTimeMillis() - startedAt;
        applyUsage(r, raw);
        return r;
    }

    /** 读取服务端返回的 usage。 */
    private static void applyUsage(Result r, String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject usage = root.optJSONObject("usage");
            if (usage == null) return;
            r.promptTokens = usage.optInt("prompt_tokens", -1);
            r.completionTokens = usage.optInt("completion_tokens", -1);
        } catch (Exception ignored) {}
    }

    /** 工具调用的人类可读标题（用于卡片）。 */
    private static String toolTitle(String fnName, McpManager.ToolSpec spec) {
        String raw = spec != null ? spec.rawName : fnName;
        switch (raw) {
            case "memory_add": return "创建了记忆";
            case "memory_search": return "检索了记忆";
            case "memory_list": return "查看了记忆";
            case "memory_forget": return "删除了一条记忆";
            case "history_search": return "检索了历史对话";
            default:
                if (spec != null && !spec.builtin) {
                    return "调用了 " + spec.serverName + " · " + raw;
                }
                return "调用了 " + raw;
        }
    }

    private static McpManager.ToolSpec findTool(List<McpManager.ToolSpec> tools, String name) {
        for (McpManager.ToolSpec t : tools) {
            if (t.qualifiedName.equals(name)) return t;
        }
        return null;
    }

    private static JSONObject msg(String role, String content) throws Exception {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content);
        return o;
    }

    private static JSONArray buildToolSchemas(List<McpManager.ToolSpec> tools) throws Exception {
        JSONArray arr = new JSONArray();
        if (tools == null) return arr;
        for (McpManager.ToolSpec t : tools) {
            JSONObject fn = new JSONObject();
            fn.put("name", t.qualifiedName);
            fn.put("description", t.description == null ? "" : t.description);
            JSONObject params;
            try { params = new JSONObject(t.inputSchema); }
            catch (Exception e) { params = new JSONObject("{\"type\":\"object\",\"properties\":{}}"); }
            fn.put("parameters", params);
            JSONObject wrapper = new JSONObject();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            arr.put(wrapper);
        }
        return arr;
    }

    private static JSONObject firstMessage(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            return choices.getJSONObject(0).optJSONObject("message");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 温度安全化：float 提升为 double 会产生 0.800000011920929 这类长小数，
     * 部分服务商（错误码 1210）限制小数点后 2 位，故统一保留 1 位。
     */
    private static double safeTemperature(float t) {
        float clamped = Math.max(0.1f, Math.min(3.0f, t));
        try {
            return new java.math.BigDecimal(Float.toString(clamped))
                    .setScale(1, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
        } catch (Exception e) {
            return Math.round(clamped * 10f) / 10.0;
        }
    }

    /** 思考深度：0 表示不发送该参数；其余映射为 reasoning_effort。 */
    private static void applyReasoning(JSONObject body, PetStore.Provider provider) {
        int level = provider.reasoningLevel;
        if (level <= 0) return;
        String[] efforts = {"", "low", "medium", "high", "high"};
        String effort = efforts[Math.min(level, efforts.length - 1)];
        try {
            body.put("reasoning_effort", effort);
        } catch (Exception ignored) {}
    }

    // ==================== 流式 ====================

    /** 取消令牌：供停止按钮使用。 */
    public static final class CancelToken {
        private volatile boolean cancelled = false;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    /** 流式回调。 */
    public interface StreamCallback {
        void onReasoningDelta(String delta);
        void onContentDelta(String delta);
        void onToolCallStarted(String toolName, String title);
        void onToolCallFinished(String toolName, String title, String preview);
    }

    /** 一轮流式请求的累积结果。 */
    private static final class StreamRound {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final List<ToolCallFragment> toolCalls = new ArrayList<>();
        String finishReason = null;
    }

    private static final class ToolCallFragment {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 流式对话（含工具调用循环）。
     * 边收边回调，支持中途取消。
     */
    public static Result chatStream(PetStore.Provider provider, String persona,
                                    List<PetStore.Turn> history, String longTermMemory,
                                    String input, List<McpManager.ToolSpec> tools,
                                    McpManager mcp, StreamCallback callback,
                                    CancelToken token) throws Exception {
        if (provider == null || !provider.isUsable())
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_provider_unavailable", null);
        if (input != null && input.codePointCount(0, input.length()) > PetContracts.MAX_PET_INPUT_CODE_POINTS)
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_input_too_long", null);

        long startedAt = System.currentTimeMillis();
        JSONArray messages = new JSONArray();
        messages.put(buildSystemMessage(persona, longTermMemory));

        int start = Math.max(0, history.size() - PetContracts.MAX_PET_DIALOGUE_ROUNDS);
        for (int i = start; i < history.size(); i++) {
            PetStore.Turn t = history.get(i);
            if (t.userInput != null && !t.userInput.trim().isEmpty())
                messages.put(msg("user", t.userInput));
            if (t.assistantText != null && !t.assistantText.trim().isEmpty())
                messages.put(msg("assistant", t.assistantText));
        }
        messages.put(msg("user", input));

        JSONArray toolSchemas = buildToolSchemas(tools);
        String url = provider.chatEndpoint();

        List<String> calledTools = new ArrayList<>();
        List<PetStore.ToolEvent> events = new ArrayList<>();
        StringBuilder allReasoning = new StringBuilder();
        String finalContent = null;
        int promptTokens = -1, completionTokens = -1;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (token != null && token.isCancelled()) {
                return cancelledResult(finalContent, allReasoning.toString(), calledTools, events,
                        startedAt, promptTokens, completionTokens);
            }

            JSONObject body = new JSONObject();
            body.put("model", provider.model);
            body.put("temperature", safeTemperature(provider.temperature));
            body.put("max_tokens", provider.maxTokens);
            body.put("stream", true);
            body.put("stream_options", new JSONObject().put("include_usage", true));
            applyReasoning(body, provider);
            body.put("messages", messages);
            if (toolSchemas.length() > 0) {
                body.put("tools", toolSchemas);
                body.put("tool_choice", "auto");
            }

            StreamRound roundResult = postStream(url, provider.apiKey, body.toString(), callback, token);
            if (roundResult == null) {
                // 被取消
                return cancelledResult(finalContent, allReasoning.toString(), calledTools, events,
                        startedAt, promptTokens, completionTokens);
            }

            if (roundResult.reasoning.length() > 0) {
                allReasoning.append(roundResult.reasoning);
            }

            // 结构化调用为空时，尝试解析模型以文本形式输出的工具调用
            List<ToolCallFragment> textCalls = new ArrayList<>();
            boolean textMode = false;
            if (roundResult.toolCalls.isEmpty()) {
                textCalls = parseTextToolCalls(roundResult.content.toString());
                if (textCalls.isEmpty()) {
                    finalContent = stripTextToolCalls(roundResult.content.toString());
                    break;
                }
                textMode = true;
            }

            final List<ToolCallFragment> effectiveCalls =
                    textMode ? textCalls : roundResult.toolCalls;

            if (textMode) {
                // 文本形式没有 tool_call_id，用 assistant + user 回填结果
                JSONObject assistantMsg = new JSONObject();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", stripTextToolCalls(roundResult.content.toString()));
                messages.put(assistantMsg);

                for (ToolCallFragment f : effectiveCalls) {
                    if (token != null && token.isCancelled()) {
                        return cancelledResult(finalContent, allReasoning.toString(),
                                calledTools, events, startedAt, promptTokens, completionTokens);
                    }
                    McpManager.ToolSpec spec = findTool(tools, f.name);
                    String title = toolTitle(f.name, spec);
                    calledTools.add(f.name);
                    if (callback != null) callback.onToolCallStarted(f.name, title);

                    String resultText;
                    if (spec == null) {
                        resultText = "错误：未知工具 " + f.name;
                    } else {
                        JSONObject args;
                        try { args = new JSONObject(f.arguments.length() == 0
                                ? "{}" : f.arguments.toString()); }
                        catch (Exception e) { args = new JSONObject(); }
                        resultText = mcp.invoke(spec, args);
                    }
                    if (resultText == null) resultText = "";
                    if (resultText.length() > 4000) resultText = resultText.substring(0, 4000);
                    events.add(new PetStore.ToolEvent(f.name, title, brief(resultText, 400),
                            brief(f.arguments.toString(), 300)));
                    if (callback != null) callback.onToolCallFinished(f.name, title, brief(resultText, 400));

                    JSONObject userMsg = new JSONObject();
                    userMsg.put("role", "user");
                    userMsg.put("content", "工具 " + f.name + " 返回结果：\n" + resultText
                            + "\n请基于以上结果直接回答，不要再输出任何 <tool_call> 标记。");
                    messages.put(userMsg);
                }
                continue;
            }

            // 组装带 tool_calls 的助手消息，供下一轮上下文使用
            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", roundResult.content.toString());
            JSONArray tcs = new JSONArray();
            for (ToolCallFragment f : roundResult.toolCalls) {
                JSONObject fn = new JSONObject();
                fn.put("name", f.name);
                fn.put("arguments", f.arguments.length() == 0 ? "{}" : f.arguments.toString());
                JSONObject tc = new JSONObject();
                tc.put("id", f.id);
                tc.put("type", "function");
                tc.put("function", fn);
                tcs.put(tc);
            }
            assistantMsg.put("tool_calls", tcs);
            messages.put(assistantMsg);

            for (ToolCallFragment f : roundResult.toolCalls) {
                if (token != null && token.isCancelled()) {
                    return cancelledResult(finalContent, allReasoning.toString(), calledTools, events,
                            startedAt, promptTokens, completionTokens);
                }
                McpManager.ToolSpec spec = findTool(tools, f.name);
                String title = toolTitle(f.name, spec);
                calledTools.add(f.name);
                if (callback != null) callback.onToolCallStarted(f.name, title);

                String resultText;
                if (spec == null) {
                    resultText = "错误：未知工具 " + f.name;
                } else {
                    JSONObject args;
                    try { args = new JSONObject(f.arguments.length() == 0 ? "{}" : f.arguments.toString()); }
                    catch (Exception e) { args = new JSONObject(); }
                    resultText = mcp.invoke(spec, args);
                }
                if (resultText == null) resultText = "";
                if (resultText.length() > 4000) resultText = resultText.substring(0, 4000);
                events.add(new PetStore.ToolEvent(f.name, title, brief(resultText, 400),
                        brief(f.arguments.toString(), 300)));
                if (callback != null) callback.onToolCallFinished(f.name, title, brief(resultText, 400));

                JSONObject toolMsg = new JSONObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", f.id);
                toolMsg.put("content", resultText);
                messages.put(toolMsg);
            }
        }

        // 若轮数耗尽仍停在工具调用，用禁用工具的方式补一次拿文本
        if (finalContent == null) {
            try {
                JSONObject body = new JSONObject();
                body.put("model", provider.model);
                body.put("temperature", safeTemperature(provider.temperature));
                body.put("max_tokens", provider.maxTokens);
                body.put("stream", false);
                applyReasoning(body, provider);
                body.put("messages", messages);
                body.put("tools", toolSchemas);
                body.put("tool_choice", "none");
                String raw = post(url, provider.apiKey, body.toString());
                JSONObject m = firstMessage(raw);
                if (m != null) finalContent = m.optString("content", "");
                promptTokens = extractUsage(raw, "prompt_tokens", promptTokens);
                completionTokens = extractUsage(raw, "completion_tokens", completionTokens);
            } catch (Exception ignored) {}
        }

        Result r = parseFromContent(stripTextToolCalls(finalContent == null ? "" : finalContent));
        r.toolCalls.addAll(calledTools);
        r.toolEvents.addAll(events);
        r.reasoning = allReasoning.toString();
        r.elapsedMs = System.currentTimeMillis() - startedAt;
        r.promptTokens = promptTokens;
        r.completionTokens = completionTokens;
        return r;
    }

    private static Result cancelledResult(String content, String reasoning, List<String> tools,
                                          List<PetStore.ToolEvent> events, long startedAt,
                                          int promptTokens, int completionTokens) {
        Result r = parseFromContent(content == null ? "" : content);
        r.toolCalls.addAll(tools);
        r.toolEvents.addAll(events);
        r.reasoning = reasoning;
        r.elapsedMs = System.currentTimeMillis() - startedAt;
        r.promptTokens = promptTokens;
        r.completionTokens = completionTokens;
        return r;
    }

    private static int extractUsage(String raw, String key, int fallback) {
        try {
            JSONObject usage = new JSONObject(raw).optJSONObject("usage");
            return usage == null ? fallback : usage.optInt(key, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 发起一次流式请求并解析 SSE。
     * 返回 null 表示被取消。
     */
    private static StreamRound postStream(String url, String apiKey, String json,
                                          StreamCallback callback, CancelToken token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            if (apiKey != null && !apiKey.trim().isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(conn.getErrorStream());
                throw new IllegalStateException("HTTP " + code + " " + truncate(err, 200));
            }

            StreamRound acc = new StreamRound();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (token != null && token.isCancelled()) return null;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (!trimmed.startsWith("data:")) continue;
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;

                    JSONObject obj;
                    try { obj = new JSONObject(data); } catch (Exception e) { continue; }

                    JSONObject usage = obj.optJSONObject("usage");
                    if (usage != null) {
                        acc.finishReason = acc.finishReason; // 占位，usage 单独存
                    }

                    JSONArray choices = obj.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject choice = choices.optJSONObject(0);
                    if (choice == null) continue;
                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta == null) continue;

                    // 思考过程
                    String reason = delta.optString("reasoning_content", "");
                    if (reason.isEmpty()) reason = delta.optString("reasoning", "");
                    if (!reason.isEmpty()) {
                        acc.reasoning.append(reason);
                        if (callback != null) callback.onReasoningDelta(reason);
                    }

                    // 正文
                    String content = delta.optString("content", "");
                    if (!content.isEmpty()) {
                        acc.content.append(content);
                        if (callback != null) callback.onContentDelta(content);
                    }

                    // 工具调用分片
                    JSONArray tcs = delta.optJSONArray("tool_calls");
                    if (tcs != null) {
                        for (int i = 0; i < tcs.length(); i++) {
                            JSONObject t = tcs.optJSONObject(i);
                            if (t == null) continue;
                            int idx = t.optInt("index", i);
                            while (acc.toolCalls.size() <= idx) acc.toolCalls.add(new ToolCallFragment());
                            ToolCallFragment frag = acc.toolCalls.get(idx);
                            String id = t.optString("id", "");
                            if (!id.isEmpty()) frag.id = id;
                            JSONObject fn = t.optJSONObject("function");
                            if (fn != null) {
                                String nm = fn.optString("name", "");
                                if (!nm.isEmpty()) frag.name = nm;
                                String args = fn.optString("arguments", "");
                                if (!args.isEmpty()) frag.arguments.append(args);
                            }
                        }
                    }
                }
            }
            return acc;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 解析模型以文本形式输出的工具调用。
     * 部分模型（如智谱 GLM）不走标准 function calling，而是输出：
     *   &lt;tool_call&gt;search_web
     *   &lt;arg_key&gt;query&lt;/arg_key&gt;
     *   &lt;arg_value&gt;中国天气&lt;/arg_value&gt;
     *   &lt;/tool_call&gt;
     * 这里把它解析成结构化调用，避免整段 XML 直接显示给用户。
     */
    static List<ToolCallFragment> parseTextToolCalls(String content) {
        List<ToolCallFragment> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;
        if (!content.contains("<tool_call>")) return out;
        try {
            java.util.regex.Matcher block = java.util.regex.Pattern
                    .compile("<tool_call>(.*?)</tool_call>", java.util.regex.Pattern.DOTALL)
                    .matcher(content);
            while (block.find()) {
                String body = block.group(1).trim();
                if (body.isEmpty()) continue;

                // 工具名：第一个非标签行
                String name = "";
                java.util.regex.Matcher nm = java.util.regex.Pattern
                        .compile("^\\s*([A-Za-z_][A-Za-z0-9_.]*)").matcher(body);
                if (nm.find()) name = nm.group(1);
                if (name.isEmpty()) continue;

                // 参数：成对的 arg_key / arg_value
                JSONObject args = new JSONObject();
                java.util.regex.Matcher kv = java.util.regex.Pattern
                        .compile("<arg_key>(.*?)</arg_key>\\s*<arg_value>(.*?)</arg_value>",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(body);
                while (kv.find()) {
                    String k = kv.group(1) == null ? "" : kv.group(1).trim();
                    String v = kv.group(2) == null ? "" : kv.group(2).trim();
                    if (!k.isEmpty()) args.put(k, v);
                }

                ToolCallFragment frag = new ToolCallFragment();
                frag.id = "textcall_" + System.nanoTime();
                frag.name = name;
                frag.arguments.append(args.toString());
                out.add(frag);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 去掉文本里的工具调用标记（避免把 XML 显示给用户）。 */
    static String stripTextToolCalls(String content) {
        if (content == null) return "";
        String cleaned = content
                .replaceAll("<tool_call>.*?</tool_call>", "")
                .replaceAll("<tool_call>", "")
                .replaceAll("</tool_call>", "");
        return cleaned.trim();
    }

    /** 从模型返回的文本内容解析出结构化结果（流式与非流式共用）。 */
    private static Result parseFromContent(String content) {
        if (content == null) content = "";
        String trimmed = content.trim();
        if (trimmed.isEmpty())
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_empty_response", content, null);
        String text = extractText(trimmed);
        if (text.isEmpty())
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_response_invalid", content, null);
        return new Result(true, text, extractHint(trimmed), null, content, extractRemember(trimmed));
    }

    /** 互动流式回调。 */
    public interface InteractCallback {
        void onDelta(String accumulatedText);
        void onDone(String finalText);
        void onError(String code);
    }

    /**
     * 互动反馈：流式短请求。
     * 不带工具、不带历史、不解析思考过程；边收边回调，便于气泡实时更新。
     * token 可取消。
     */
    public static void interactStream(PetStore.Provider provider, String personaPrompt,
                                      String userAction, InteractCallback callback,
                                      CancelToken token) {
        if (provider == null || !provider.isUsable()) {
            if (callback != null) callback.onError("pet_provider_unavailable");
            return;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", provider.model);
            body.put("temperature", safeTemperature(provider.temperature));
            body.put("max_tokens", 200);
            body.put("stream", true);

            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            StringBuilder sb = new StringBuilder();
            sb.append("你是桌面宠物。用户在触摸你，请给出一句非常简短的口语化反应。\n");
            sb.append("要求：\n");
            sb.append("- 只输出这一句话本身，不要 JSON、不要引号、不要解释、不要动作描述。\n");
            sb.append("- 不超过 30 个字。\n");
            if (personaPrompt != null && !personaPrompt.trim().isEmpty()) {
                sb.append("性格设定：\n").append(personaPrompt.trim()).append("\n");
            }
            sys.put("role", "system");
            sys.put("content", sb.toString());
            messages.put(sys);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userAction);
            messages.put(user);
            body.put("messages", messages);

            conn = (HttpURLConnection) new URL(provider.chatEndpoint()).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            if (provider.apiKey != null && !provider.apiKey.trim().isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey.trim());

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(conn.getErrorStream());
                if (callback != null) callback.onError("HTTP " + code + " " + brief(err, 120));
                return;
            }

            StringBuilder acc = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (token != null && token.isCancelled()) {
                        // 被用户强制停止：保留已收到的内容
                        if (callback != null) callback.onDone(cleanInteractText(acc.toString()));
                        return;
                    }
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) continue;
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JSONObject obj;
                    try { obj = new JSONObject(data); } catch (Exception e) { continue; }
                    JSONArray choices = obj.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;

                    // 思考过程只累积、不外显；互动气泡不展示思考
                    String reason = delta.optString("reasoning_content", "");
                    if (reason.isEmpty()) reason = delta.optString("reasoning", "");
                    if (!reason.isEmpty()) reasoning.append(reason);

                    String content = delta.optString("content", "");
                    if (!content.isEmpty()) {
                        acc.append(content);
                        if (callback != null) callback.onDelta(cleanInteractText(acc.toString()));
                    }
                }
            }
            String finalText = cleanInteractText(acc.toString());
            if (finalText.isEmpty()) {
                if (callback != null) callback.onError("pet_empty_response");
            } else if (callback != null) {
                callback.onDone(finalText);
            }
        } catch (Throwable t) {
            if (callback != null) {
                String msg = t.getMessage();
                if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
                callback.onError(msg);
            }
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 清洗互动文本：去 JSON 包装、去引号、压平空白、限长。 */
    private static String cleanInteractText(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("{")) {
            String extracted = extractText(text);
            if (!extracted.isEmpty()) text = extracted;
        }
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        text = text.replace("\n", " ").replace("\r", " ").trim();
        if (text.codePointCount(0, text.length()) > 60) {
            text = text.substring(0, text.offsetByCodePoints(0, 60));
        }
        return text;
    }

    private static JSONObject buildSystemMessage(String persona, String longTermMemory) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("你是桌面宠物角色，只做简短的日常闲聊。\n");
        sb.append("严格只输出 JSON，不要任何解释或多余文字：\n");
        sb.append("{\"text\":\"回复内容\",\"visual_hint\":\"NEUTRAL\",\"remember\":\"\"}\n");
        sb.append("要求：text 不超过 ").append(PetContracts.MAX_PET_RESPONSE_CODE_POINTS)
          .append(" 个字符，口语化、简短可爱。\n");
        sb.append("visual_hint 只能是 NEUTRAL / GREETING / PLAYFUL / CURIOUS / COMFORTING 之一，")
          .append("它只表示情绪，不代表动作。\n");
        sb.append("remember：当用户透露了值得长期记住的个人信息（喜好、习惯、称呼、重要事实）时，")
          .append("在这里写一句简短记录；没有则留空字符串。绝不要记录密码、令牌等敏感信息。\n");
        sb.append("你拥有工具，必须主动使用：\n");
        sb.append("- 用户提到过去聊过的事、或问你是否记得 -> 先调用 history_search 或 memory_search 检索，不要凭空回答。\n");
        sb.append("- 用户透露了喜好、习惯、称呼、重要事实 -> 调用 memory_add 记下来。\n");
        sb.append("- 需要调用时直接发起工具调用；拿到结果后再按上面的 JSON 格式给出最终回复。\n");
        sb.append("不要向用户描述工具调用过程，只给自然的口语化回复。\n");
        sb.append("角色设定：\n").append(persona == null ? "" : persona).append("\n");
        if (longTermMemory != null && !longTermMemory.trim().isEmpty()) {
            sb.append("你记得这些关于用户的事：\n").append(longTermMemory).append("\n");
        }
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", sb.toString());
        return sys;
    }

    private static String post(String url, String apiKey, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " " + truncate(text, 160));
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    /** 截断并压平空白，用于日志。 */
    private static String brief(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 解析响应：只取 content，不处理 reasoning_content。 */
    private static Result parseResponse(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0)
                return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_empty_response", null);
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null)
                return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_empty_response", null);

            String content = message.optString("content", "").trim();
            if (content.isEmpty())
                return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_empty_response", null);

            String text = extractText(content);
            PetContracts.VisualHint hint = extractHint(content);
            if (text.isEmpty())
                return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_response_invalid", content, null);
            return new Result(true, text, hint, null, content, extractRemember(content));
        } catch (Exception e) {
            return new Result(false, "", PetContracts.VisualHint.NEUTRAL, "pet_response_invalid", null);
        }
    }

    private static String extractText(String content) {
        String cleaned = content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        String jsonObj = extractFirstJsonObject(cleaned);
        if (jsonObj == null) return truncateCodePoints(cleaned, PetContracts.MAX_PET_RESPONSE_CODE_POINTS);
        try {
            JSONObject o = new JSONObject(jsonObj);
            String t = o.optString("text", "").trim();
            return truncateCodePoints(t, PetContracts.MAX_PET_RESPONSE_CODE_POINTS);
        } catch (Exception e) {
            return truncateCodePoints(cleaned, PetContracts.MAX_PET_RESPONSE_CODE_POINTS);
        }
    }

    /** 提取模型要求记住的内容；过滤敏感信息与过长文本。 */
    private static String extractRemember(String content) {
        String jsonObj = extractFirstJsonObject(content.trim());
        if (jsonObj == null) return null;
        try {
            String r = new JSONObject(jsonObj).optString("remember", "").trim();
            if (r.isEmpty()) return null;
            // 简单防泄漏：拒绝看起来像凭据的内容
            String lower = r.toLowerCase();
            if (lower.contains("password") || lower.contains("api key")
                    || lower.contains("apikey") || lower.contains("token")
                    || lower.contains("secret") || lower.contains("密码")) return null;
            if (r.codePointCount(0, r.length()) > 200) {
                r = r.substring(0, r.offsetByCodePoints(0, 200));
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static PetContracts.VisualHint extractHint(String content) {
        String jsonObj = extractFirstJsonObject(content.trim());
        if (jsonObj == null) return PetContracts.VisualHint.NEUTRAL;
        try {
            return PetContracts.VisualHint.parse(new JSONObject(jsonObj).optString("visual_hint", "NEUTRAL"));
        } catch (Exception e) {
            return PetContracts.VisualHint.NEUTRAL;
        }
    }

    /** 提取配对平衡的 JSON 对象，跳过字符串内部的括号。 */
    static String extractFirstJsonObject(String raw) {
        int start = -1, depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (start < 0) {
                if (c == '{') { start = i; depth = 1; }
                continue;
            }
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String truncateCodePoints(String s, int max) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max));
    }

    /** 连接测试：列出模型。 */
    public static boolean testProvider(PetStore.Provider p) {
        if (p == null || !p.isUsable()) return false;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(p.normalizedBaseUrl() + "/models").openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setRequestProperty("Accept", "application/json");
                if (p.apiKey != null && !p.apiKey.trim().isEmpty())
                    conn.setRequestProperty("Authorization", "Bearer " + p.apiKey.trim());
                int code = conn.getResponseCode();
                return code >= 200 && code < 300;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 拉取可用模型列表。 */
    public static List<String> fetchModels(PetStore.Provider p) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        if (p == null || !p.isUsable()) return out;
        HttpURLConnection conn = (HttpURLConnection) new URL(p.normalizedBaseUrl() + "/models").openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            if (p.apiKey != null && !p.apiKey.trim().isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + p.apiKey.trim());
            String raw = readAll(conn.getInputStream());
            JSONObject root = new JSONObject(raw);
            JSONArray data = root.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    String id = data.getJSONObject(i).optString("id", "");
                    if (!id.isEmpty()) out.add(id);
                }
            }
        } finally {
            conn.disconnect();
        }
        return out;
    }
}
