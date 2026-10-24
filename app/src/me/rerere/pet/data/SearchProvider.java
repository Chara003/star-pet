package me.rerere.pet.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索服务商。
 * 每个服务商有独立的请求格式与结果结构；API Key 可为空（部分服务无需密钥）。
 * 各服务的请求/响应格式见下面对应的 Meta 与请求构造代码。
 */
public final class SearchProvider {

    private SearchProvider() {}

    /** 服务商元信息。 */
    public static final class Meta {
        public final String id;
        public final String label;
        /** 申请地址（供用户获取 Key）。 */
        public final String keyUrl;
        /** Key 是否必需。 */
        public final boolean keyRequired;
        /** 补充说明。 */
        public final String note;

        Meta(String id, String label, String keyUrl, boolean keyRequired, String note) {
            this.id = id;
            this.label = label;
            this.keyUrl = keyUrl;
            this.keyRequired = keyRequired;
            this.note = note;
        }
    }

    /** 全部可选服务商。 */
    public static final List<Meta> ALL = new ArrayList<>();
    static {
        ALL.add(new Meta("duckduckgo", "DuckDuckGo",
                "", false, "免密钥，直接可用（HTML 解析）"));
        ALL.add(new Meta("searxng", "SearXNG",
                "https://searx.space/", false, "免密钥。填自建实例地址，留空用公共实例"));
        ALL.add(new Meta("tavily", "Tavily",
                "https://app.tavily.com/home", true, "专为 AI 设计，质量好，有免费额度"));
        ALL.add(new Meta("zhipu", "智谱 Web Search",
                "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", true, "中文效果好，可复用智谱 API Key"));
        ALL.add(new Meta("bocha", "博查 Bocha",
                "https://open.bochaai.com/", true, "中文搜索质量高"));
        ALL.add(new Meta("brave", "Brave Search",
                "https://api.search.brave.com/", true, "需订阅 Token"));
        ALL.add(new Meta("bing", "Bing（免密钥）",
                "", false, "抓取网页，质量一般，无需配置"));
    }

    public static Meta metaOf(String id) {
        for (Meta m : ALL) if (m.id.equals(id)) return m;
        return ALL.get(0);
    }

    /** 搜索配置。 */
    public static final class Config {
        public String providerId = "duckduckgo";
        /** 各服务商独立的 API Key：providerId -> key。 */
        public final java.util.Map<String, String> apiKeys = new java.util.HashMap<>();
        /** 各服务商独立的实例地址：providerId -> endpoint。 */
        public final java.util.Map<String, String> endpoints = new java.util.HashMap<>();
        public int resultCount = 6;

        public Config() {}

        /** 当前服务商的 API Key。 */
        public String apiKey() {
            String v = apiKeys.get(providerId);
            return v == null ? "" : v;
        }

        public void setApiKey(String key) {
            apiKeys.put(providerId, key == null ? "" : key);
        }

        /** 当前服务商的实例地址。 */
        public String endpoint() {
            String v = endpoints.get(providerId);
            return v == null ? "" : v;
        }

        public void setEndpoint(String ep) {
            endpoints.put(providerId, ep == null ? "" : ep);
        }
    }

    /** 单条结果。 */
    public static final class Item {
        public final String title;
        public final String url;
        public final String text;

        Item(String title, String url, String text) {
            this.title = title;
            this.url = url;
            this.text = text;
        }
    }

    /** 搜索结果。 */
    public static final class Result {
        public String answer;
        public final List<Item> items = new ArrayList<>();
    }

    // ---------------- 统一入口 ----------------

    /** 执行搜索。失败抛异常，消息可直接展示。 */
    public static Result search(Config cfg, String query) throws Exception {
        if (query == null || query.trim().isEmpty()) throw new IllegalArgumentException("关键词不能为空");
        String q = query.trim();
        switch (cfg.providerId) {
            case "duckduckgo": return duckduckgo(cfg, q);
            case "searxng": return searxng(cfg, q);
            case "tavily": return tavily(cfg, q);
            case "zhipu": return zhipu(cfg, q);
            case "bocha": return bocha(cfg, q);
            case "brave": return brave(cfg, q);
            case "bing": default: return bing(cfg, q);
        }
    }

    /** 预检：配置是否可用（Key 缺失等）。 */
    public static String validate(Config cfg) {
        Meta m = metaOf(cfg.providerId);
        if (m.keyRequired && cfg.apiKey().trim().isEmpty()) {
            return m.label + " 需要 API Key";
        }
        return null;
    }

    // ---------------- 各服务商实现 ----------------

    /** Tavily：POST JSON + Bearer。 */
    private static Result tavily(Config cfg, String q) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", q);
        body.put("max_results", cfg.resultCount);
        body.put("search_depth", "advanced");
        body.put("topic", "general");
        body.put("include_answer", true);

        JSONObject root = new JSONObject(postJson("https://api.tavily.com/search",
                body.toString(), "Bearer " + cfg.apiKey().trim()));
        Result r = new Result();
        r.answer = root.optString("answer", "");
        if (r.answer.isEmpty()) r.answer = null;
        JSONArray arr = root.optJSONArray("results");
        if (arr != null) {
            for (int i = 0; i < arr.length() && i < cfg.resultCount; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                r.items.add(new Item(o.optString("title", ""), o.optString("url", ""),
                        o.optString("content", "")));
            }
        }
        return r;
    }

    /** 智谱 Web Search。 */
    private static Result zhipu(Config cfg, String q) throws Exception {
        JSONObject body = new JSONObject();
        body.put("search_query", q);
        body.put("search_engine", "search_std");
        body.put("count", cfg.resultCount);

        JSONObject root = new JSONObject(postJson(
                "https://open.bigmodel.cn/api/paas/v4/web_search",
                body.toString(), "Bearer " + cfg.apiKey().trim()));
        Result r = new Result();
        JSONArray arr = root.optJSONArray("search_result");
        if (arr != null) {
            for (int i = 0; i < arr.length() && i < cfg.resultCount; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                r.items.add(new Item(o.optString("title", ""), o.optString("link", ""),
                        o.optString("content", "")));
            }
        }
        if (r.items.isEmpty()) {
            String err = root.optString("error", "");
            if (!err.isEmpty()) throw new IllegalStateException("智谱搜索失败：" + err);
        }
        return r;
    }

    /** 博查 Bocha。 */
    private static Result bocha(Config cfg, String q) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", q);
        body.put("summary", true);
        body.put("count", cfg.resultCount);

        JSONObject root = new JSONObject(postJson("https://api.bochaai.com/v1/web-search",
                body.toString(), "Bearer " + cfg.apiKey().trim()));
        int code = root.optInt("code", 200);
        if (code != 200) {
            throw new IllegalStateException("博查返回错误：" + root.optString("msg", "code=" + code));
        }
        Result r = new Result();
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            JSONArray arr = data.optJSONArray("webPages") != null
                    ? data.optJSONArray("webPages") : data.optJSONArray("value");
            JSONObject pages = data.optJSONObject("webPages");
            if (pages != null) arr = pages.optJSONArray("value");
            if (arr != null) {
                for (int i = 0; i < arr.length() && i < cfg.resultCount; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String text = o.optString("summary", "");
                    if (text.isEmpty()) text = o.optString("snippet", "");
                    r.items.add(new Item(o.optString("name", ""), o.optString("url", ""), text));
                }
            }
        }
        return r;
    }

    /** Brave Search。 */
    private static Result brave(Config cfg, String q) throws Exception {
        String url = "https://api.search.brave.com/res/v1/web/search?count="
                + cfg.resultCount + "&q=" + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection conn = open(url, "GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Subscription-Token", cfg.apiKey().trim());
        JSONObject root = new JSONObject(readResponse(conn));
        Result r = new Result();
        JSONObject web = root.optJSONObject("web");
        if (web != null) {
            JSONArray arr = web.optJSONArray("results");
            if (arr != null) {
                for (int i = 0; i < arr.length() && i < cfg.resultCount; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    r.items.add(new Item(o.optString("title", ""), o.optString("url", ""),
                            o.optString("description", "")));
                }
            }
        }
        return r;
    }

    /** SearXNG：GET JSON。 */
    private static Result searxng(Config cfg, String q) throws Exception {
        String base = cfg.endpoint();
        if (base.isEmpty()) base = "https://searx.be";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/search?q=" + URLEncoder.encode(q, "UTF-8") + "&format=json";

        HttpURLConnection conn = open(url, "GET");
        conn.setRequestProperty("Accept", "application/json");
        JSONObject root = new JSONObject(readResponse(conn));
        Result r = new Result();
        JSONArray arr = root.optJSONArray("results");
        if (arr != null) {
            for (int i = 0; i < arr.length() && i < cfg.resultCount; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                r.items.add(new Item(o.optString("title", ""), o.optString("url", ""),
                        o.optString("content", "")));
            }
        }
        if (r.items.isEmpty()) {
            throw new IllegalStateException("SearXNG 无结果（实例可能未开启 JSON 输出）");
        }
        return r;
    }

    /** DuckDuckGo：HTML 端点解析（免密钥）。 */
    private static Result duckduckgo(Config cfg, String q) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection conn = open(url, "GET");
        conn.setRequestProperty("Accept", "text/html");
        String html = readResponse(conn);

        Result r = new Result();
        // 结果块 class="result__body"
        int idx = 0;
        final String marker = "class=\"result__body\"";
        while (r.items.size() < cfg.resultCount) {
            int pos = html.indexOf(marker, idx);
            if (pos < 0) break;
            int next = html.indexOf(marker, pos + marker.length());
            String seg = html.substring(pos, next < 0
                    ? Math.min(html.length(), pos + 6000) : next);
            idx = next < 0 ? html.length() : next;

            String title = strip(extract(seg, "class=\"result__a\"[^>]*>(.*?)</a>"));
            String link = decodeDdg(extractAttr(seg, "class=\"result__a\"[^>]*href=\"([^\"]+)\""));
            String snippet = strip(extract(seg, "class=\"result__snippet\"[^>]*>(.*?)</a>"));
            if (snippet.isEmpty()) {
                snippet = strip(extract(seg, "class=\"result__snippet\"[^>]*>(.*?)</div>"));
            }
            if (title.isEmpty()) continue;
            r.items.add(new Item(title, link, snippet));
        }
        if (r.items.isEmpty()) {
            throw new IllegalStateException("DuckDuckGo 无结果（可能被限流，请稍后重试或换服务商）");
        }
        return r;
    }

    /** Bing：HTML 解析（免密钥，兜底）。 */
    private static Result bing(Config cfg, String q) throws Exception {
        String url = "https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection conn = open(url, "GET");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        String html = readResponse(conn);

        Result r = new Result();
        final String marker = "<li class=\"b_algo\"";
        int idx = 0;
        while (r.items.size() < cfg.resultCount) {
            int pos = html.indexOf(marker, idx);
            if (pos < 0) break;
            int next = html.indexOf(marker, pos + marker.length());
            String seg = html.substring(pos, next < 0
                    ? Math.min(html.length(), pos + 8000) : next);
            idx = next < 0 ? html.length() : next;

            String title = strip(extract(seg, "<h2[^>]*>(.*?)</h2>"));
            String link = extractAttr(seg, "<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"");
            String snippet = strip(extract(seg, "<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>"));
            if (snippet.isEmpty()) snippet = strip(extract(seg, "<p[^>]*>(.*?)</p>"));
            if (title.isEmpty()) continue;
            r.items.add(new Item(title, link, snippet));
        }
        if (r.items.isEmpty()) {
            throw new IllegalStateException("Bing 无结果（页面结构可能已变化）");
        }
        return r;
    }

    // ---------------- 网络与解析工具 ----------------

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return conn;
    }

    private static String postJson(String url, String json, String auth) throws Exception {
        HttpURLConnection conn = open(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        if (auth != null) conn.setRequestProperty("Authorization", auth);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }
        return readResponse(conn);
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
        }
        if (code < 200 || code >= 300) {
            String detail = sb.toString().replaceAll("\\s+", " ").trim();
            if (detail.length() > 160) detail = detail.substring(0, 160);
            throw new IllegalStateException("HTTP " + code + (detail.isEmpty() ? "" : " " + detail));
        }
        return sb.toString();
    }

    private static String extract(String src, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(regex, java.util.regex.Pattern.DOTALL).matcher(src);
            return m.find() ? (m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : "") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String extractAttr(String src, String regex) {
        return extract(src, regex);
    }

    /** 去 HTML 标签与实体。 */
    private static String strip(String html) {
        if (html == null) return "";
        String t = html.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
             .replace("&hellip;", "…");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** DuckDuckGo 的跳转链接还原。 */
    private static String decodeDdg(String link) {
        if (link == null || link.isEmpty()) return "";
        try {
            if (link.contains("uddg=")) {
                String v = link.substring(link.indexOf("uddg=") + 5);
                int amp = v.indexOf('&');
                if (amp >= 0) v = v.substring(0, amp);
                return java.net.URLDecoder.decode(v, "UTF-8");
            }
            if (link.startsWith("//")) return "https:" + link;
        } catch (Throwable ignored) {}
        return link;
    }
}
