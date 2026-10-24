package me.rerere.pet.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置 MCP 工具集：日期时间、联网搜索。
 * 这些工具无需外部服务器，直接在本地执行。
 */
public final class BuiltinMcp {

    /** 内置服务器 ID。 */
    public static final String DATE_ID = "builtin_datetime";
    public static final String SEARCH_ID = "builtin_search";
    public static final String LOCATION_ID = "builtin_location";
    public static final String EXPRESSION_ID = "builtin_expression";

    private BuiltinMcp() {}

    /** 由外部注入的配置提供者（避免循环依赖）。 */
    private static PetStore storeRef;

    public static void init(PetStore store) { storeRef = store; }

    // ---------------- 工具定义 ----------------

    public static List<McpManager.ToolSpec> tools() {
        List<McpManager.ToolSpec> out = new ArrayList<>();
        out.add(new McpManager.ToolSpec("datetime_now",
                "获取当前的日期、时间与星期。需要知道今天几号、现在几点时调用。",
                schema("format", "可选：输出格式，如 yyyy-MM-dd HH:mm，留空用默认"),
                true, DATE_ID, "日期时间", "datetime_now"));
        if (LocationHelper.hasPermission()) {
            out.add(new McpManager.ToolSpec("location_get",
                    "获取用户当前的地理位置（经纬度与大致地址）。需要知道用户在哪、查本地信息时调用。",
                    schema("dummy", "无需参数，可留空"),
                    true, LOCATION_ID, "位置信息", "location_get"));
        }
        // 表情控制：仅在 MMD 渲染模式下可用
        if (storeRef != null && "mmd".equals(storeRef.appearance().renderMode)
                && storeRef.appearance().mmdModelUrl != null
                && !storeRef.appearance().mmdModelUrl.isEmpty()) {
            String modelUrl = storeRef.appearance().mmdModelUrl;
            java.util.List<String> names = PetOverlayServiceBridge.expressionNames();
            /* ★★ 按模型类型分说话方式（2026-10-07 实测发现）★★
               glTF 模型的 morph target 全是【嘴型】，没有脸部表情 ——
               所以对它来说"做表情"真正有意义的是**播一个自带动画**。
               而 glTF 下 names 里装的正是动画名（见 PetOverlayService.onGlbClips），
               所以只需要换一套措辞，AI 就会正确地把它当"动作"来用。 */
            final boolean glb = isGlbModel(modelUrl);
            StringBuilder desc = new StringBuilder();
            if (glb) {
                desc.append("让桌宠（3D 模型）表演一个自带动作（如待机、反应、胜利等）。")
                    .append("会暂停自转并转向正前方，动作播放完毕后自动回待机。\n");
            } else {
                desc.append("让桌宠（3D 模型）做一个表情。会暂停自转并转向正前方，")
                    .append("持续指定秒数（默认 3 秒，可传 0.5~10 秒），结束后自动恢复原来的自转状态。\n");
            }
            if (!names.isEmpty()) {
                /* 别名映射：用户配过的优先，没配过则自动编号 1..N（立即可用）。
                   AI 用编号选择最稳，避免它去挑看不懂的日文名/装饰件。 */
                java.util.LinkedHashMap<String, String> eff =
                        effectiveAliases(modelUrl, names);
                desc.append(glb
                        ? "可用动作（必须按「编号=动作名」选择，直接传编号即可）：\n"
                        : "可用表情（必须按「编号=表情名(含义)」选择，直接传编号即可）：\n");
                int limit = Math.min(eff.size(), 40);
                int shown = 0;
                for (java.util.Map.Entry<String, String> e : eff.entrySet()) {
                    if (shown >= limit) break;
                    if (shown > 0) desc.append(" / ");
                    desc.append(e.getKey()).append("=").append(e.getValue());
                    String hint = expressionHint(e.getValue());
                    if (hint != null) desc.append("(").append(hint).append(")");
                    /* 注：这里【不附中文译名】—— AI 多语言自适应足够强，
                       日文原名它能直接理解，加了反而冗长。译文只用于界面显示。 */
                    shown++;
                }
                if (eff.size() > limit) desc.append(" …共 ").append(eff.size()).append(" 个");
                /* ★ 必须明确告诉 AI 优先挑脸部表情。
                   100.00x 这类【装饰件】往往排在最前面，AI 很容易挑到它们，
                   表现就是"AI 做的表情只有光环会动、脸一点不动"（实测）。 */
                desc.append(glb
                        ? ("\n注意：动作名多是英文（Cafe_Idle / Normal_Attack / Victory_End 这类），"
                         + "按字面意思挑即可；用户若配过中文别名，会显示为「编号=中文名」。")
                        : ("\n注意：请优先选择有中文含义标注的项"
                         + "（如 微笑 / 张嘴「啊」 / 闭眼 / 眨眼）；"
                         + "标注为「装饰件/光环，不是脸部表情」的只影响光环或饰品，"
                         + "不会改变表情。"));
            } else {
                desc.append(glb
                        ? "模型自带动作列表尚未就绪（glTF 要等模型加载完）。可先留空。"
                        : "模型表情列表尚未就绪，可先留空清除表情。");
            }
            out.add(new McpManager.ToolSpec("play_expression",
                    desc.toString(),
                    schemaPlayExpr(),
                    true, EXPRESSION_ID, "表情控制", "play_expression"));

            /* ================= 配件控制（独立工具） =================
               ★ 只在【当前模型确实有配件】时才注册 —— 没有配件就自动禁用，
                 避免 AI 拿着一个永远失败的工具反复试。
               ★ 和表情分开：配件是"长期开关状态"，表情是"瞬时表演"。 */
            java.util.List<String> accs = PetOverlayServiceBridge.accessoryNames();
            if (!accs.isEmpty()) {
                StringBuilder ad = new StringBuilder();
                ad.append("开关桌宠（3D 模型）的配件/装备，例如耳朵、尾巴、鞋子、外套、眼镜。")
                  .append("配件是【持续生效】的状态，不会自动关闭；再次调用并传 enabled=false 可关闭。\n");
                ad.append("当前模型可用配件（按「编号=名字」选择）：");
                int alimit = Math.min(accs.size(), 40);
                for (int i = 0; i < alimit; i++) {
                    if (i > 0) ad.append(" / ");
                    ad.append(i + 1).append("=").append(accs.get(i));
                    String h = expressionHint(accs.get(i));
                    if (h != null) ad.append("(").append(h).append(")");
                }
                if (accs.size() > alimit) ad.append(" …共 ").append(accs.size()).append(" 个");
                ad.append("\n可以传编号（1/2/3…）或名字。")
                  .append("注意：这些是模型自带的隐藏部件，开启后会显示出来（例如狐耳、狐尾、鞋）。");
                out.add(new McpManager.ToolSpec("set_accessory",
                        ad.toString(),
                        schemaAccessory(),
                        true, EXPRESSION_ID, "配件控制", "set_accessory"));
            }
        }
        out.add(new McpManager.ToolSpec("search_web",
                "联网搜索。需要最新信息、实时事实、或你不确定的知识时调用，返回标题/链接/摘要。",
                schema2("query", "搜索关键词"),
                true, SEARCH_ID, "联网搜索", "search_web"));
        return out;
    }

    /**
     * 给表情名加一个中文含义提示，供 AI 理解。
     *
     * ★ 为什么需要：MMD 模型的表情名是日文或纯数字（100.001 / あ / はぅ / Close），
     *    AI 光看名字无法理解。而 100.00x 这类【装饰件（光环/头饰）】在
     *    morph 列表里往往排在【最前面】，AI 很容易顺手挑到它们 ——
     *    表现就是"AI 做的表情只有光环在动、脸一点不动"（实测）。
     *    这里对常见命名做映射，认不出的返回 null（不显示提示）。
     */
    public static String expressionHint(String name) {
        if (name == null || name.isEmpty()) return null;
        String n = name;
        // 装饰件：数字编号（100.001 等）一般是光环/头饰/挂件
        if (n.matches("^\\d+(\\.\\d+)?$")) return "装饰件/光环，不是脸部表情";
        if (n.matches("^\\d+\\.\\d+.*")) return "装饰件/光环，不是脸部表情";
        if (n.equals("Close")) return "闭眼";
        if (n.equals("Off")) return "闭眼（另一种）";
        if (n.contains("笑")) return "微笑（闭眼笑）";
        if (n.contains("なごみ") || n.contains("和み")) return "柔和/微笑";
        if (n.contains("はぅ") || n.contains("はう")) return "害羞脸红";
        if (n.contains("ウィンク") || n.contains("ウインク")) return "眨眼（单眼）";
        if (n.contains("まばたき") || n.contains("瞬き")) return "眨眼（双眼）";
        if (n.contains("怒")) return "生气";
        if (n.contains("泣") || n.contains("悲")) return "哭泣/难过";
        if (n.contains("照")) return "害羞";
        if (n.contains("驚") || n.contains("おどろ")) return "惊讶";
        if (n.equals("あ") || n.startsWith("あ")) return "张嘴「啊」";
        if (n.equals("い") || n.startsWith("い")) return "咧嘴「一」";
        if (n.equals("え") || n.startsWith("え")) return "张嘴「诶」";
        if (n.equals("お") || n.startsWith("お")) return "张嘴「哦」";
        if (n.equals("う") || n.startsWith("う")) return "张嘴「呜」";
        return null;
    }

    /**
     * 生成某模型的有效别名映射（别名 → 真实表情名）。
     * · 用户保存过的映射优先（完全交给用户控制，不再自动编号）
     * · 还没配过 → 按表情顺序自动编号 1..N，保证开箱即可用
     */
    static java.util.LinkedHashMap<String, String> effectiveAliases(
            String modelUrl, java.util.List<String> names) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (storeRef != null) {
            /* ★ 必须按【当前表情列表】过滤已保存的映射。
               ---- 为什么（2026-10-05）----
               配件已从表情列表移出（改由配件工具管理），
               但用户之前保存的映射表里可能还留着配件项。
               原来这里是"映射非空就原样返回"，于是那些配件会从
               MCP 的表情工具描述里【漏回去】，AI 又会在表情里挑到 ON_靴 之类。
               → 只保留"当前确实是表情"的条目。 */
            java.util.Map<String, String> saved = storeRef.exprAliases(modelUrl);
            java.util.Set<String> valid = (names == null)
                    ? java.util.Collections.<String>emptySet()
                    : new java.util.HashSet<>(names);
            for (java.util.Map.Entry<String, String> e : saved.entrySet()) {
                if (valid.contains(e.getValue())) out.put(e.getKey(), e.getValue());
            }
        }
        if (!out.isEmpty()) return out;
        if (names != null) {
            for (int i = 0; i < names.size(); i++) {
                out.put(String.valueOf(i + 1), names.get(i));
            }
        }
        return out;
    }

    /**
     * 配件别名映射（辅助函数）：当前模型配件的编号表。
     * 配件数量通常不多，这里直接按顺序编号，便于 AI 用短编号开关。
     */
    static java.util.LinkedHashMap<String, String> effectiveAccessoryAliases(
            java.util.List<String> accs) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (accs != null) {
            for (int i = 0; i < accs.size(); i++) {
                out.put(String.valueOf(i + 1), accs.get(i));
            }
        }
        return out;
    }

    /** 这个模型 URL 是不是 glTF（.glb/.gltf）—— 决定"表情"还是"动作"的措辞。 */
    private static boolean isGlbModel(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        return u.endsWith(".glb") || u.endsWith(".gltf");
    }

    /** play_expression 的参数 schema：name 必填，seconds 可选（0.5~10 秒）。 */
    private static String schemaPlayExpr() {
        try {
            JSONObject props = new JSONObject();
            JSONObject nameIt = new JSONObject();
            nameIt.put("type", "string");
            nameIt.put("description",
                    "编号（工具描述里列出的 1 / 2 / 3 …）或真实名字。"
                    + "MMD 模型是【表情】名，glTF 模型是【自带动作】名。留空表示清除");
            props.put("name", nameIt);
            JSONObject secIt = new JSONObject();
            secIt.put("type", "number");
            secIt.put("description", "持续秒数，0.5~10，默认 3。想让表演久一点就调大");
            props.put("seconds", secIt);
            JSONObject root = new JSONObject();
            root.put("type", "object");
            root.put("properties", props);
            JSONArray required = new JSONArray();
            required.put("name");
            root.put("required", required);
            return root.toString();
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    /** set_accessory 的参数 schema：name 必填，enabled 可选（默认 true）。 */
    private static String schemaAccessory() {
        try {
            JSONObject props = new JSONObject();
            JSONObject nameIt = new JSONObject();
            nameIt.put("type", "string");
            nameIt.put("description", "配件名（工具描述里列出的那个，如 ON_きつね耳）");
            props.put("name", nameIt);
            JSONObject onIt = new JSONObject();
            onIt.put("type", "boolean");
            onIt.put("description", "true=显示该配件，false=隐藏。默认 true");
            props.put("enabled", onIt);
            JSONObject root = new JSONObject();
            root.put("type", "object");
            root.put("properties", props);
            JSONArray required = new JSONArray();
            required.put("name");
            root.put("required", required);
            return root.toString();
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
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
            return root.toString();
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    private static String schema2(String key, String desc) {
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

    // ---------------- 执行 ----------------

    /** 执行内置工具；返回 null 表示不是内置工具。 */
    public static String invoke(String serverId, String toolName, JSONObject args) {
        if (DATE_ID.equals(serverId) || "datetime_now".equals(toolName)) {
            return now(args);
        }
        if (SEARCH_ID.equals(serverId) || "search_web".equals(toolName)) {
            return search(args);
        }
        if (LOCATION_ID.equals(serverId) || "location_get".equals(toolName)) {
            return LocationHelper.describe();
        }
        if (EXPRESSION_ID.equals(serverId) || "play_expression".equals(toolName)) {
            String raw = args == null ? "" : args.optString("name", "").trim();
            /* 时长：0.5 ~ 10 秒，默认 3 秒（AI 可自己指定） */
            float secs = (float) (args == null ? 3.0 : args.optDouble("seconds", 3.0));
            if (!(secs > 0) || Float.isNaN(secs)) secs = 3f;      // 非法输入兜底
            if (secs < 0.5f) secs = 0.5f;
            if (secs > 10f) secs = 10f;

            /* 解析别名（编号）→ 真实表情名。
               传的是真实名时原样使用，所以两种写法都能工作。 */
            String modelUrl = (storeRef == null) ? "" : storeRef.appearance().mmdModelUrl;
            String resolved = raw;
            if (!raw.isEmpty()) {
                String hit = effectiveAliases(modelUrl,
                        PetOverlayServiceBridge.expressionNames()).get(raw);
                if (hit != null) resolved = hit;
            }
            PetOverlayServiceBridge.playExpression(resolved, secs);
            if (raw.isEmpty()) return "已清除表情。";
            String shown = resolved.equals(raw) ? raw : (raw + " → " + resolved);
            return "已开始表情「" + shown + "」，持续 "
                    + (secs == Math.floor(secs) ? String.valueOf((int) secs) : String.valueOf(secs))
                    + " 秒，之后自转会恢复。";
        }

        /* ================= 配件开关 ================= */
        if ("set_accessory".equals(toolName)) {
            String raw = args == null ? "" : args.optString("name", "").trim();
            boolean on = args == null || !args.has("enabled") || args.optBoolean("enabled", true);
            if (raw.isEmpty()) {
                return "请提供配件名。可用配件：" + PetOverlayServiceBridge.accessoryNames();
            }
            /* 支持传编号（1/2/3）—— 与表情一致的体验 */
            String resolved = raw;
            String hit = effectiveAccessoryAliases(
                    PetOverlayServiceBridge.accessoryNames()).get(raw);
            if (hit != null) resolved = hit;

            PetOverlayServiceBridge.setAccessory(resolved, on);
            String shown = resolved.equals(raw) ? raw : (raw + " → " + resolved);
            return (on ? "已开启配件「" : "已关闭配件「") + shown + "」。"
                    + "（配件会持续生效，不会自动关闭）";
        }
        return null;
    }

    public static boolean isBuiltinServer(String serverId) {
        return DATE_ID.equals(serverId) || SEARCH_ID.equals(serverId)
                || LOCATION_ID.equals(serverId) || EXPRESSION_ID.equals(serverId);
    }

    /** 当前日期时间。 */
    private static String now(JSONObject args) {
        try {
            String format = args == null ? "" : args.optString("format", "").trim();
            if (format.isEmpty()) format = "yyyy-MM-dd HH:mm:ss";
            String weekday;
            try {
                weekday = new SimpleDateFormat("EEEE", Locale.CHINA).format(new Date());
            } catch (Exception e) {
                weekday = "";
            }
            String formatted;
            try {
                formatted = new SimpleDateFormat(format, Locale.getDefault()).format(new Date());
            } catch (Exception e) {
                formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date());
            }
            return "当前时间：" + formatted + (weekday.isEmpty() ? "" : "（" + weekday + "）");
        } catch (Throwable t) {
            return "获取时间失败：" + t.getClass().getSimpleName();
        }
    }

    /** 联网搜索：走用户选择的服务商。 */
    private static String search(JSONObject args) {
        String query = args == null ? "" : args.optString("query", "").trim();
        if (query.isEmpty()) return "错误：query 不能为空";

        SearchProvider.Config cfg = storeRef == null
                ? new SearchProvider.Config() : storeRef.searchConfig();

        String invalid = SearchProvider.validate(cfg);
        if (invalid != null) {
            return "搜索未配置：" + invalid + "。请在设置里选择搜索服务或填入 API Key。";
        }
        try {
            SearchProvider.Result res = SearchProvider.search(cfg, query);
            if (res.items.isEmpty()) {
                return "没有搜索到「" + query + "」的相关结果。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("搜索「").append(query).append("」的结果：\n");
            if (res.answer != null && !res.answer.trim().isEmpty()) {
                sb.append("摘要：").append(res.answer.trim()).append("\n\n");
            }
            int n = 0;
            for (SearchProvider.Item it : res.items) {
                n++;
                sb.append(n).append(". ").append(it.title).append('\n');
                if (it.text != null && !it.text.isEmpty()) {
                    sb.append("   ").append(it.text).append('\n');
                }
                if (it.url != null && !it.url.isEmpty()) {
                    sb.append("   ").append(it.url).append('\n');
                }
                if (n >= 6) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
            return "搜索失败：" + msg;
        }
    }

    /**
     * 解析 Bing 结果页。
     * 关键：b_algo 内部还有嵌套的 li，用正则 .*?</li> 会在第一个 </li> 处截断，
     * 导致抓到错误的文字（如「10（自然数之一）」）。这里按标记位置切分。
     */
    private static List<String[]> parseBing(String html) {
        List<String[]> out = new ArrayList<>();
        try {
            final String marker = "<li class=\"b_algo\"";
            int idx = 0;
            while (out.size() < 8) {
                int startPos = html.indexOf(marker, idx);
                if (startPos < 0) break;
                int nextPos = html.indexOf(marker, startPos + marker.length());
                int endPos = nextPos < 0 ? Math.min(html.length(), startPos + 8000) : nextPos;
                idx = nextPos < 0 ? html.length() : nextPos;

                String seg = html.substring(startPos, endPos);

                // 标题与链接：<h2><a href="...">标题</a></h2>
                String link = extractAttr(seg, "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"");
                String title = strip(extract(seg, "<h2[^>]*>(.*?)</h2>"));

                // 摘要：优先 b_caption，其次任意 <p>
                String snippet = strip(extract(seg, "<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>"));
                if (snippet.isEmpty()) {
                    snippet = strip(extract(seg, "<p[^>]*>(.*?)</p>"));
                }
                if (snippet.isEmpty()) {
                    snippet = strip(extract(seg, "<div[^>]*class=\"[^\"]*b_caption[^\"]*\"[^>]*>(.*?)</div>"));
                }

                if (title.isEmpty()) continue;
                // 过滤明显的无关噪声
                if (title.length() < 2) continue;
                out.add(new String[]{title, link == null ? "" : link, snippet});
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String extract(String src, String regex) {
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(src);
            return m.find() ? m.group(1) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String extractAttr(String src, String regex) {
        try {
            Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(src);
            return m.find() ? m.group(1) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 去 HTML 标签与实体。 */
    private static String strip(String html) {
        if (html == null) return "";
        String t = html.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            InputStream is = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
