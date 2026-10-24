package me.rerere.pet.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import me.rerere.pet.core.PetContracts;

/**
 * 全局配置中枢：提供商、会话、桌宠外观与触控分区。
 * 使用 SharedPreferences + JSON，避免引入 Room 依赖。
 */
public final class PetStore {

    private static final String PREFS = "pet_store";
    private static final String KEY_PROVIDERS = "providers";
    private static final String KEY_ACTIVE_PROVIDER = "active_provider";
    private static final String KEY_PERSONA = "persona";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_AVATAR_PATH = "avatar_path";
    private static final String KEY_INTERACT_ENABLED = "interact_enabled";
    private static final String KEY_INTERACT_CHANCE = "interact_chance";
    private static final String KEY_INTERACT_RANDOM_PERSONA = "interact_random_persona";
    private static final String KEY_INTERACT_PERSONA = "interact_persona";
    private static final String KEY_PERSONA_OVERRIDE = "persona_override_";
    private static final String KEY_SEARCH = "search_config";
    private static final String KEY_SESSIONS = "sessions";
    private static final String KEY_ACTIVE_SESSION = "active_session";
    private static final String KEY_LONGTERM = "longterm_memory";

    private final SharedPreferences prefs;
    private final Context context;

    public PetStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateTranslationsOnce();
    }

    private static final String KEY_TRANS_MIGRATED = "trans_migrated_v1";

    /**
     * 一次性清理：丢掉翻译缓存里【不含中文】的译文。
     *
     * ★★ 为什么需要（2026-10-06 实测）★★
     *   早期版本把用户的 customPrompt 【整段替换】了默认提示词，
     *   而用户那段里没写"译成中文" → 模型输出英文：
     *       "1. Start Walking"、"2. Walk Action L"、"3. Curve 90° Rig..."
     *   而且它们是按【叶子名】存的（当时还没做模糊匹配），
     *   永远匹配不上实际名字 → 纯垃圾数据，还会在界面上出现。
     *   裁定标准：译文里没有常用汉字 → 丢。
     *   （代价：极少数本来就不含汉字的译名，如 "ω"，丢了也无所谓。）
     */
    private void migrateTranslationsOnce() {
        try {
            if (prefs.getBoolean(KEY_TRANS_MIGRATED, false)) return;
            String raw = prefs.getString(KEY_TRANS_CACHE, null);
            if (raw != null && !raw.isEmpty()) {
                JSONObject o = new JSONObject(raw);
                JSONObject keep = new JSONObject();
                int drop = 0;
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = o.optString(k, "");
                    if (hasCjk(v)) keep.put(k, v);
                    else drop++;
                }
                if (drop > 0) {
                    prefs.edit().putString(KEY_TRANS_CACHE,
                            keep.toString().replace("\\/", "/")).commit();
                    android.util.Log.i("PetStore",
                            "翻译缓存清理：丢弃 " + drop + " 条非中文译文，"
                                    + "保留 " + keep.length() + " 条");
                }
            }
            prefs.edit().putBoolean(KEY_TRANS_MIGRATED, true).commit();
        } catch (Throwable t) {
            android.util.Log.w("PetStore", "migrateTranslationsOnce failed", t);
        }
    }

    /** 是否含常用汉字（用于判断译文是不是中文）。 */
    private static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    public Context appContext() { return context; }

    // ---------------- Provider ----------------

    /** OpenAI 兼容服务商：自定义 baseUrl / apiKey / model。 */
    public static final class Provider {
        public String id;
        public String name;
        public String baseUrl;
        public String apiKey;
        public String model;
        public float temperature = 1.0f;
        public int maxTokens = 800;
        /** 对话补全路径，默认 /chat/completions。 */
        public String chatPath = "/chat/completions";
        /** 思考深度 0~4（关闭/低/中/高/最高）。 */
        public int reasoningLevel = 0;

        public Provider() {}

        public Provider(String id, String name, String baseUrl, String apiKey, String model) {
            this.id = id; this.name = name; this.baseUrl = baseUrl;
            this.apiKey = apiKey; this.model = model;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("baseUrl", baseUrl);
            o.put("apiKey", apiKey);
            o.put("model", model);
            o.put("temperature", formatTemp(temperature));
            o.put("maxTokens", maxTokens);
            o.put("chatPath", chatPath);
            o.put("reasoningLevel", reasoningLevel);
            return o;
        }

        static Provider fromJson(JSONObject o) {
            Provider p = new Provider();
            p.id = o.optString("id", "");
            p.name = o.optString("name", "");
            p.baseUrl = o.optString("baseUrl", "");
            p.apiKey = o.optString("apiKey", "");
            p.model = o.optString("model", "");
            p.temperature = parseTemp(o.opt("temperature"));
            p.maxTokens = o.optInt("maxTokens", 800);
            p.chatPath = o.optString("chatPath", "/chat/completions");
            p.reasoningLevel = Math.max(0, Math.min(4, o.optInt("reasoningLevel", 0)));
            return p;
        }

        /** 补全 /v1 结尾并去掉尾部斜杠。 */
        public String normalizedBaseUrl() {
            String b = baseUrl == null ? "" : baseUrl.trim();
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            return b;
        }

        /**
         * 只要地址和模型非空即可使用。
         * 部分提供商（本地 Ollama、内网网关）不需要 API Key，不能强制要求。
         */
        /** 温度：强制保留 1 位小数，范围 0.1~3.0。 */
        public float normalizedTemperature() {
            float t = Math.max(0.1f, Math.min(3.0f, temperature));
            return Math.round(t * 10f) / 10f;
        }

        /** 完整对话端点：baseUrl + chatPath。 */
        public String chatEndpoint() {
            String base = normalizedBaseUrl();
            String path = (chatPath == null || chatPath.trim().isEmpty())
                    ? "/chat/completions" : chatPath.trim();
            if (!path.startsWith("/")) path = "/" + path;
            return base + path;
        }

        public boolean isUsable() {
            return !normalizedBaseUrl().isEmpty()
                    && model != null && !model.trim().isEmpty();
        }
    }

    private static String formatTemp(float t) {
        float v = Math.max(0.1f, Math.min(3.0f, t));
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private static float parseTemp(Object raw) {
        if (raw == null) return 1.0f;
        try {
            float v = Float.parseFloat(String.valueOf(raw));
            if (Float.isNaN(v) || Float.isInfinite(v)) return 1.0f;
            return Math.max(0.1f, Math.min(3.0f, Math.round(v * 10f) / 10f));
        } catch (Exception e) {
            return 1.0f;
        }
    }

    public List<Provider> providers() {
        List<Provider> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_PROVIDERS, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Provider.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public void saveProviders(List<Provider> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Provider p : list) arr.put(p.toJson());
            /* ★ 用 commit()（同步）：以前是 apply()，紧接着读回来可能拿到旧值 */
            prefs.edit().putString(KEY_PROVIDERS, arr.toString()).commit();
        } catch (Exception ignored) {}
    }

    /**
     * 保存【单个】服务商（新增或更新）。
     *
     * ★★ 为什么必须用这个方法（严重 bug，2026-10-07 修复）★★
     *   `providers()` 每次都从 JSON 【新建】一批 Provider 对象。
     *   调用方如果持有的是【另一个】实例（比如编辑对话框里的 existing），
     *   改完它再 `saveProviders(store.providers())` —— 写回去的是
     *   【同一批旧对象】，编辑内容被静默丢弃。
     *   用户看到的现象就是"AI 提供商里改什么都存不上，只能用之前的"。
     *   正确做法：取一份最新列表，按 id 把目标对象【替换】进去再整体保存。
     */
    public void saveProvider(Provider p) {
        if (p == null || p.id == null || p.id.isEmpty()) return;
        List<Provider> all = providers();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (p.id.equals(all.get(i).id)) {
                all.set(i, p);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(p);
        saveProviders(all);
    }

    /** 按 id 取一个服务商（返回新对象，直接改它不会影响存档，改完要 saveProvider）。 */
    public Provider findProvider(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Provider p : providers()) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public Provider activeProvider() {
        List<Provider> all = providers();
        if (all.isEmpty()) return null;
        String id = prefs.getString(KEY_ACTIVE_PROVIDER, "");
        for (Provider p : all) if (p.id.equals(id)) return p;
        return all.get(0);
    }

    public void setActiveProvider(String id) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, id).apply();
    }

    // ---------------- Persona ----------------

    public String personaPrompt() {
        return prefs.getString(KEY_PERSONA, "你是一只温柔俏皮的桌面宠物，回答简短口语化，一句话以内。");
    }

    public void setPersonaPrompt(String v) {
        prefs.edit().putString(KEY_PERSONA, v).apply();
    }

    /** 桌宠在对话里显示的名字。 */
    public String displayName() {
        return prefs.getString(KEY_DISPLAY_NAME, "桌宠");
    }

    public void setDisplayName(String v) {
        prefs.edit().putString(KEY_DISPLAY_NAME,
                (v == null || v.trim().isEmpty()) ? "桌宠" : v.trim()).apply();
    }

    /** 自定义头像文件路径；空表示用默认图标。 */
    public String avatarPath() {
        return prefs.getString(KEY_AVATAR_PATH, "");
    }

    public void setAvatarPath(String path) {
        prefs.edit().putString(KEY_AVATAR_PATH, path == null ? "" : path).apply();
    }

    // ---------------- 互动反馈 ----------------

    /** 预设性格。 */
    public static final String[] PERSONA_PRESETS = {"傲娇", "黏人", "高冷", "元气"};

    /** 各性格对应的提示词。 */
    public static String personaPrompt(String preset) {
        if (preset == null) return "";
        switch (preset) {
            case "傲娇":
                return "你有点傲娇，嘴上不承认但其实很在意对方。被摸头时会别扭地抱怨，"
                        + "比如「哼、才不是给你摸的」，但语气要可爱不做作。";
            case "黏人":
                return "你很黏人，喜欢撒娇和亲近对方。被摸头时会开心地蹭过来，"
                        + "希望对方多陪陪你，语气亲昵。";
            case "高冷":
                return "你性格高冷寡言，话不多，但并非冷漠。被摸头时反应平淡克制，"
                        + "偶尔流露一点在意，用简短的话回应。";
            case "元气":
                return "你元气满满，说话活泼有干劲，喜欢用感叹号。"
                        + "被摸头时会精神十足地回应，带动气氛。";
            default:
                return "";
        }
    }

    /** 是否启用交互反馈（点击时按概率触发一次模型对话）。 */
    public boolean interactEnabled() {
        return prefs.getBoolean(KEY_INTERACT_ENABLED, false);
    }

    public void setInteractEnabled(boolean v) {
        prefs.edit().putBoolean(KEY_INTERACT_ENABLED, v).apply();
    }

    /** 触发概率 1~100（百分比）。 */
    public int interactChance() {
        int v = prefs.getInt(KEY_INTERACT_CHANCE, 15);
        return Math.max(1, Math.min(100, v));
    }

    public void setInteractChance(int v) {
        prefs.edit().putInt(KEY_INTERACT_CHANCE, Math.max(1, Math.min(100, v))).apply();
    }

    /** 是否启用随机性格。 */
    public boolean interactRandomPersona() {
        return prefs.getBoolean(KEY_INTERACT_RANDOM_PERSONA, false);
    }

    public void setInteractRandomPersona(boolean v) {
        prefs.edit().putBoolean(KEY_INTERACT_RANDOM_PERSONA, v).apply();
    }

    /** 当前选中的性格。 */
    public String interactPersona() {
        return prefs.getString(KEY_INTERACT_PERSONA, "元气");
    }

    public void setInteractPersona(String v) {
        prefs.edit().putString(KEY_INTERACT_PERSONA, v == null ? "元气" : v).apply();
    }

    /** 保存某个性格的提示词覆盖。 */
    public void setPersonaOverride(String preset, String prompt) {
        if (preset == null) return;
        prefs.edit().putString(KEY_PERSONA_OVERRIDE + preset, prompt == null ? "" : prompt).apply();
    }

    /** 读取性格的提示词（优先用户覆盖值，否则用预设）。 */
    public String personaPromptResolved(String preset) {
        if (preset == null) return "";
        String override = prefs.getString(KEY_PERSONA_OVERRIDE + preset, null);
        if (override != null && !override.trim().isEmpty()) return override;
        return personaPrompt(preset);
    }

    /** 本次交互应使用的性格提示词（随机性格时每次随机挑一个）。 */
    public String resolveInteractPersonaPrompt() {
        if (interactRandomPersona()) {
            int idx = (int) (Math.random() * PERSONA_PRESETS.length);
            return personaPromptResolved(PERSONA_PRESETS[idx]);
        }
        return personaPromptResolved(interactPersona());
    }

    /** 外部触发时使用的随机源（便于测试）。 */
    public boolean rollInteract() {
        return Math.random() * 100.0 < interactChance();
    }

    // ---------------- 联网搜索配置 ----------------

    /** 读取搜索配置（含旧格式迁移）。 */
    public SearchProvider.Config searchConfig() {
        SearchProvider.Config c = new SearchProvider.Config();
        try {
            String raw = prefs.getString(KEY_SEARCH, null);
            if (raw != null) {
                org.json.JSONObject o = new org.json.JSONObject(raw);
                c.providerId = o.optString("providerId", "duckduckgo");
                c.resultCount = o.optInt("resultCount", 6);

                // 新格式：按服务商分别存储
                org.json.JSONObject keys = o.optJSONObject("apiKeys");
                if (keys != null) {
                    java.util.Iterator<String> it = keys.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        c.apiKeys.put(k, keys.optString(k, ""));
                    }
                }
                org.json.JSONObject eps = o.optJSONObject("endpoints");
                if (eps != null) {
                    java.util.Iterator<String> it = eps.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        c.endpoints.put(k, eps.optString(k, ""));
                    }
                }

                // 旧格式迁移：顶层单一 apiKey 归属到当时的 providerId
                String legacyKey = o.optString("apiKey", "");
                if (!legacyKey.isEmpty() && !c.apiKeys.containsKey(c.providerId)) {
                    c.apiKeys.put(c.providerId, legacyKey);
                }
                String legacyEp = o.optString("endpoint", "");
                if (!legacyEp.isEmpty() && !c.endpoints.containsKey(c.providerId)) {
                    c.endpoints.put(c.providerId, legacyEp);
                }
            }
        } catch (Throwable ignored) {}
        if (c.resultCount < 1 || c.resultCount > 20) c.resultCount = 6;
        return c;
    }

    public void saveSearchConfig(SearchProvider.Config c) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("providerId", c.providerId);
            o.put("resultCount", c.resultCount);
            org.json.JSONObject keys = new org.json.JSONObject();
            for (java.util.Map.Entry<String, String> e : c.apiKeys.entrySet()) {
                keys.put(e.getKey(), e.getValue());
            }
            o.put("apiKeys", keys);
            org.json.JSONObject eps = new org.json.JSONObject();
            for (java.util.Map.Entry<String, String> e : c.endpoints.entrySet()) {
                eps.put(e.getKey(), e.getValue());
            }
            o.put("endpoints", eps);
            prefs.edit().putString(KEY_SEARCH, o.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** 头像保存目录。 */
    public java.io.File avatarDir() {
        java.io.File dir = new java.io.File(context.getFilesDir(), "avatar");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ---------------- Session ----------------

    /** 对话会话，默认标题为"新对话"。 */
    public static final class Session {
        public String id;
        public String title = "新对话";
        public long createdAt;
        public long updatedAt;
        public final List<Turn> turns = new ArrayList<>();

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            JSONArray arr = new JSONArray();
            for (Turn t : turns) arr.put(t.toJson());
            o.put("turns", arr);
            return o;
        }

        static Session fromJson(JSONObject o) throws Exception {
            Session s = new Session();
            s.id = o.optString("id", "");
            s.title = o.optString("title", "新对话");
            s.createdAt = o.optLong("createdAt", 0);
            s.updatedAt = o.optLong("updatedAt", 0);
            JSONArray arr = o.optJSONArray("turns");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) s.turns.add(Turn.fromJson(arr.getJSONObject(i)));
            }
            return s;
        }
    }

    /** 单轮对话。 */
    /** 一次工具调用的展示数据。 */
    public static final class ToolEvent {
        public String name = "";
        public String title = "";
        public String preview = "";
        public String args = "";

        ToolEvent() {}

        public ToolEvent(String name, String title, String preview, String args) {
            this.name = name;
            this.title = title;
            this.preview = preview;
            this.args = args;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("title", title);
            o.put("preview", preview);
            o.put("args", args);
            return o;
        }

        static ToolEvent fromJson(JSONObject o) {
            ToolEvent e = new ToolEvent();
            e.name = o.optString("name", "");
            e.title = o.optString("title", "");
            e.preview = o.optString("preview", "");
            e.args = o.optString("args", "");
            return e;
        }
    }

    public static final class Turn {
        public String userInput = "";
        public String assistantText = "";
        public String visualHint = "NEUTRAL";
        public long at;
        /** 本轮调用的工具名。 */
        public final List<String> toolCalls = new ArrayList<>();
        /** 结构化的工具事件（用于卡片展示）。 */
        public final List<ToolEvent> toolEvents = new ArrayList<>();
        /** 模型思考过程（默认折叠展示）。 */
        public String reasoning = "";
        public int promptTokens = -1;
        public int completionTokens = -1;
        public long elapsedMs = 0L;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("userInput", userInput);
            o.put("assistantText", assistantText);
            o.put("visualHint", visualHint);
            o.put("at", at);
            o.put("promptTokens", promptTokens);
            o.put("completionTokens", completionTokens);
            o.put("elapsedMs", elapsedMs);
            JSONArray tc = new JSONArray();
            for (String t : toolCalls) tc.put(t);
            o.put("toolCalls", tc);
            JSONArray te = new JSONArray();
            for (ToolEvent e : toolEvents) te.put(e.toJson());
            o.put("toolEvents", te);
            o.put("reasoning", reasoning);
            return o;
        }

        static Turn fromJson(JSONObject o) throws Exception {
            Turn t = new Turn();
            t.userInput = o.optString("userInput", "");
            t.assistantText = o.optString("assistantText", "");
            t.visualHint = o.optString("visualHint", "NEUTRAL");
            t.at = o.optLong("at", 0);
            t.promptTokens = o.optInt("promptTokens", -1);
            t.completionTokens = o.optInt("completionTokens", -1);
            t.elapsedMs = o.optLong("elapsedMs", 0);
            JSONArray tc = o.optJSONArray("toolCalls");
            if (tc != null) {
                for (int i = 0; i < tc.length(); i++) t.toolCalls.add(tc.optString(i, ""));
            }
            JSONArray te = o.optJSONArray("toolEvents");
            if (te != null) {
                for (int i = 0; i < te.length(); i++) t.toolEvents.add(ToolEvent.fromJson(te.optJSONObject(i)));
            }
            t.reasoning = o.optString("reasoning", "");
            return t;
        }
    }

    public List<Session> sessions() {
        List<Session> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_SESSIONS, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Session.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public void saveSessions(List<Session> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Session s : list) arr.put(s.toJson());
            prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public String activeSessionId() {
        return prefs.getString(KEY_ACTIVE_SESSION, "");
    }

    public void setActiveSessionId(String id) {
        prefs.edit().putString(KEY_ACTIVE_SESSION, id).apply();
    }

    /** 新建会话，默认标题"新对话"。 */
    public Session createSession() {
        Session s = new Session();
        s.id = "s" + System.currentTimeMillis();
        s.title = "新对话";
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        List<Session> all = sessions();
        all.add(0, s);
        saveSessions(all);
        setActiveSessionId(s.id);
        return s;
    }

    public Session activeSession() {
        List<Session> all = sessions();
        String id = activeSessionId();
        for (Session s : all) if (s.id.equals(id)) return s;
        if (!all.isEmpty()) return all.get(0);
        return createSession();
    }

    public void deleteSession(String id) {
        List<Session> all = sessions();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) { all.remove(i); break; }
        }
        saveSessions(all);
        if (id.equals(activeSessionId())) setActiveSessionId(all.isEmpty() ? "" : all.get(0).id);
    }

    public void renameSession(String id, String title) {
        List<Session> all = sessions();
        for (Session s : all) {
            if (s.id.equals(id)) { s.title = title; break; }
        }
        saveSessions(all);
    }

    public void appendTurn(String sessionId, Turn turn) {
        List<Session> all = sessions();
        for (Session s : all) {
            if (s.id.equals(sessionId)) {
                s.turns.add(turn);
                s.updatedAt = System.currentTimeMillis();
                break;
            }
        }
        saveSessions(all);
    }

    // ---------------- Long-term memory ----------------

    /** 长期记忆条目列表。 */
    public List<String> longTermMemories() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_LONGTERM, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    public void addLongTermMemory(String text) {
        List<String> all = longTermMemories();
        if (text == null || text.trim().isEmpty()) return;
        String t = text.trim();
        if (all.contains(t)) return;
        all.add(t);
        if (all.size() > 200) all = all.subList(all.size() - 200, all.size());
        try {
            JSONArray arr = new JSONArray();
            for (String s : all) arr.put(s);
            prefs.edit().putString(KEY_LONGTERM, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void removeLongTermMemory(String text) {
        List<String> all = longTermMemories();
        all.remove(text);
        try {
            JSONArray arr = new JSONArray();
            for (String s : all) arr.put(s);
            prefs.edit().putString(KEY_LONGTERM, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public String longTermMemoryForPrompt() {
        List<String> all = longTermMemories();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : all) sb.append("- ").append(s).append('\n');
        return sb.toString();
    }

    // ---------------- Appearance ----------------

    public static final class Appearance {
        public String packageId = "";
        public float scale = 1.0f;
        public int fps = 6;
        /** 头/身体 两条分界（0..1 百分比）。 */
        public float headBoundary = 0.34f;
        public float bodyBoundary = 0.76f;
        public float normalizedX = 0.8f;
        public float normalizedY = 0.5f;
        public boolean enabled = true;
        public boolean idleRandomEnabled = true;
        /** 渲染模式：sprite（精灵图）或 mmd（3D 模型）。 */
        public String renderMode = "sprite";
        /** 当前 MMD 模型的 webUrl（/models/xxx/a.pmx）。 */
        public String mmdModelUrl = "";
        /** MMD 显示大小（独立于精灵图 scale，1.0 = 占屏高 40%）。 */
        public float mmdScale = 1.0f;
        /** 抗锯齿档位：0=关，2=两倍，4=四倍分辨率渲染。 */
        public int mmdAntiAliasLevel = 0;
        /** 自动漫游：桌宠自己在屏幕上随机走动。 */
        public boolean roamEnabled = false;
        /** 漫游速度：每个移动步进的像素数（1~12）。 */
        public int roamSpeed = 4;
        /**
         * 走动概率（0~100，%）。
         * 每隔 30 秒掷一次骰子：掷中才走一段，没掷中就再等 30 秒。
         * 这样"多久动一次"由这个滑块控制，而不是靠很短的停顿时长
         * （旧做法等于一直在动，实测"频率太高"）。
         */
        public int roamChance = 40;
        /**
         * GPU 加速（高性能 WebGL 上下文）。
         * ★ 切换后需要重载模型页面才生效 —— antialias / powerPreference
         *   只能在创建 WebGL 上下文时决定，运行中改不了。
         */
        public boolean gpuAccel = true;
        /** 渲染倍率 0.35~1.0（1.0 = 按设备像素比原生渲染）。大模型卡就调低。 */
        public float renderScale = 1.0f;
        /** 动态分辨率：FPS 低时自动降渲染分辨率，恢复后慢慢升回。 */
        public boolean perfAuto = false;
        /** 帧率上限：0=不限，30 / 20（省电降温）。 */
        public int fpsCap = 0;
        /**
         * MMD 物理开关。
         * ★ 重模型的最大 CPU 开销来源：实测 Kei_Uniform 有 270 刚体 + 372 关节，
         *   每帧要在 ammo(wasm) 里解算 642 个约束。关掉后头发/裙子不再摆动，
         *   但帧率通常明显改善。
         */
        public boolean physicsEnabled = true;

        /* ★★★ 动态模糊（2026-10-07 设计上要求）★★★
           ---- 为什么 MMD / glTF 各存一套 ----
           两个模型的尺寸、动作速度、件数都不一样，合适的"拖影长度/质量"也不同
           （和配件、表情别名、相机距离一样，这类视觉参数天然应该按模型存）。
           本页的"高级设置"是全局页，所以按【当前模型类型】读写对应那套。 */
        /* ★ 默认值说明：长度 35（平方映射后拖影约 0.06 秒，轻微拖影，最耐看）；
             质量 80（内部缓冲约 87% 分辨率，够清晰又不费）。
             长度用【平方映射】—— len=50 就已经是 0.125 秒的明显拖影了。 */
        public boolean mbMmdOn = false;
        public int mbMmdLen = 35;
        public int mbMmdQual = 80;
        public boolean mbGlbOn = false;
        public int mbGlbLen = 35;
        public int mbGlbQual = 80;

        /* ★ 对话气泡的最大高度（占屏幕高度的百分比，20~70）。
           ---- 为什么需要它 ----
           气泡原来写死 maxLines=4 + 固定宽度，模型偶尔吐长句就会盖住半个屏幕；
           用户也可能反过来嫌气泡太小看不清。给出一个滑块最省事。 */
        public int bubbleHeightPct = 32;

        /* ★ 气泡最小高度（占屏幕高度 %）。
           ---- 为什么需要两个值 ----
           只有一个"最大高度"时，普通回复（2~5 行）永远碰不到上限，
           滑块怎么拖都看不出变化（实测过）。
           加一个"最小高度"后：短句也会撑到 min，长句封顶到 max，
           两头都能看见效果。 */
        public int bubbleMinPct = 12;

        public Appearance normalized() {
            scale = clamp(scale, PetContracts.MIN_SCALE, PetContracts.MAX_SCALE);
            fps = Math.max(PetContracts.MIN_FPS, Math.min(PetContracts.MAX_FPS, fps));
            headBoundary = clamp(headBoundary, PetContracts.MIN_HEAD_BOUNDARY, PetContracts.MAX_HEAD_BOUNDARY);
            // bodyBoundary 必须晚于 headBoundary 一点，但也要能被上界钳住
            // （两界拉大后，headBoundary 可能接近甚至超过上界，需要保护）
            float bodyMin = Math.min(headBoundary + 0.05f, PetContracts.MAX_BODY_BOUNDARY - 0.01f);
            bodyBoundary = clamp(bodyBoundary, bodyMin, PetContracts.MAX_BODY_BOUNDARY);
            // MMD 尺寸：0.05 ~ 3.0（与滑块范围一致）
            mmdScale = clamp(mmdScale, 0.05f, 3.0f);
            if (mmdAntiAliasLevel != 2 && mmdAntiAliasLevel != 4) mmdAntiAliasLevel = 0;
            bubbleMinPct = Math.max(6, Math.min(40, bubbleMinPct));
            bubbleHeightPct = Math.max(bubbleMinPct + 4, Math.min(70, bubbleHeightPct));
            roamSpeed = Math.max(1, Math.min(12, roamSpeed));
            roamChance = Math.max(0, Math.min(100, roamChance));
            renderScale = clamp(renderScale, 0.35f, 1.0f);
            /* ★★★ 帧率上限的合法性校验（2026-10-07 修正）★★★
               ---- 事故复盘（曾出现"怎么感觉就只有20帧和不限帧"）----
               这里原来是一条**旧白名单**：
                   if (fpsCap != 30 && fpsCap != 20) fpsCap = 0;
               那是上一版只支持「不限 / 30 / 20」三档时写的 ——
               它会把**任何不是 20 或 30 的值一律改成 0（不限）**。
               而帧率滑块早就是 20~91 连续可调了（91 = 不限，内部存 0），
               于是：写 60 → 读回 0；写 90 → 读回 0；只有 20 和 30 能存活
               → 用户看到的就是"只有 20 帧和不限帧"。

               ★ 我在改滑块时修了 JS 侧**一模一样**的那条白名单
                 （`FPS_CAP = (f === 30 || f === 20) ? f : 0`），
                 却漏了 Java 侧 PetStore 里这条 —— **同一个模式只改了一处**。

               现在改成范围校验（0 = 不限；20~240 为有效上限），
               并对低于滑杆下限的旧值做一次向上归一（3/15 这类历史值 → 20）。 */
            if (fpsCap < 0) fpsCap = 0;
            else if (fpsCap > 0 && fpsCap < 20) fpsCap = 20;   // 旧存档里的小值 → 抬到滑块下限
            else if (fpsCap > 240) fpsCap = 240;
            normalizedX = clamp(normalizedX, 0f, 1f);
            normalizedY = clamp(normalizedY, 0f, 1f);
            return this;
        }
    }

    private static final String KEY_APPEARANCE = "appearance";

    public Appearance appearance() {
        Appearance a = new Appearance();
        try {
            String raw = prefs.getString(KEY_APPEARANCE, null);
            if (raw != null) {
                JSONObject o = new JSONObject(raw);
                a.packageId = o.optString("packageId", "");
                a.scale = (float) o.optDouble("scale", 1.0);
                a.fps = o.optInt("fps", 6);
                a.headBoundary = (float) o.optDouble("headBoundary", 0.34);
                a.bodyBoundary = (float) o.optDouble("bodyBoundary", 0.76);
                a.normalizedX = (float) o.optDouble("normalizedX", 0.8);
                a.normalizedY = (float) o.optDouble("normalizedY", 0.5);
                a.enabled = o.optBoolean("enabled", true);
                a.idleRandomEnabled = o.optBoolean("idleRandomEnabled", true);
                a.renderMode = o.optString("renderMode", "sprite");
                a.mmdModelUrl = o.optString("mmdModelUrl", "");
                a.mmdScale = (float) o.optDouble("mmdScale", 1.0);
                // 兼容旧字段（布尔）到新档位
                if (o.has("mmdAntiAliasLevel")) {
                    a.mmdAntiAliasLevel = o.optInt("mmdAntiAliasLevel", 0);
                } else if (o.optBoolean("mmdAntiAlias", false)) {
                    a.mmdAntiAliasLevel = 2;
                }
                a.roamEnabled = o.optBoolean("roamEnabled", false);
                a.roamSpeed = o.optInt("roamSpeed", 4);
                a.roamChance = o.optInt("roamChance", 40);
                a.gpuAccel = o.optBoolean("gpuAccel", true);
                a.renderScale = (float) o.optDouble("renderScale", 1.0);
                a.perfAuto = o.optBoolean("perfAuto", false);
                a.fpsCap = o.optInt("fpsCap", 0);
                a.physicsEnabled = o.optBoolean("physicsEnabled", true);
                a.mbMmdOn = o.optBoolean("mbMmdOn", false);
                a.mbMmdLen = Math.max(0, Math.min(100, o.optInt("mbMmdLen", 35)));
                a.mbMmdQual = Math.max(0, Math.min(100, o.optInt("mbMmdQual", 80)));
                a.mbGlbOn = o.optBoolean("mbGlbOn", false);
                a.mbGlbLen = Math.max(0, Math.min(100, o.optInt("mbGlbLen", 35)));
                a.mbGlbQual = Math.max(0, Math.min(100, o.optInt("mbGlbQual", 80)));
                a.bubbleMinPct = Math.max(6, Math.min(40, o.optInt("bubbleMinPct", 12)));
                a.bubbleHeightPct = Math.max(20, Math.min(70, o.optInt("bubbleHeightPct", 32)));
            }
        } catch (Exception ignored) {}
        return a.normalized();
    }

    public void saveAppearance(Appearance a) {
        try {
            // 诊断：检测 mmdModelUrl 被回退（切换模型失败时的典型症状）
            try {
                String oldRaw = prefs.getString(KEY_APPEARANCE, null);
                if (oldRaw != null) {
                    String oldUrl = new org.json.JSONObject(oldRaw).optString("mmdModelUrl", "");
                    if (oldUrl != null && !oldUrl.isEmpty()
                            && a.mmdModelUrl != null && !a.mmdModelUrl.isEmpty()
                            && !oldUrl.equals(a.mmdModelUrl)) {
                        android.util.Log.w("PetStore", "mmdModelUrl changed: " + oldUrl
                                + " -> " + a.mmdModelUrl + " | caller="
                                + android.util.Log.getStackTraceString(new Throwable()));
                    }
                }
            } catch (Throwable ignored) {}

            a.normalized();
            JSONObject o = new JSONObject();
            o.put("packageId", a.packageId);
            o.put("scale", a.scale);
            o.put("fps", a.fps);
            o.put("headBoundary", a.headBoundary);
            o.put("bodyBoundary", a.bodyBoundary);
            o.put("normalizedX", a.normalizedX);
            o.put("normalizedY", a.normalizedY);
            o.put("enabled", a.enabled);
            o.put("idleRandomEnabled", a.idleRandomEnabled);
            o.put("renderMode", a.renderMode);
            o.put("mmdModelUrl", a.mmdModelUrl);
            o.put("mmdScale", a.mmdScale);
            o.put("mmdAntiAliasLevel", a.mmdAntiAliasLevel);
            o.put("roamEnabled", a.roamEnabled);
            o.put("roamSpeed", a.roamSpeed);
            o.put("roamChance", a.roamChance);
            o.put("gpuAccel", a.gpuAccel);
            o.put("renderScale", a.renderScale);
            o.put("perfAuto", a.perfAuto);
            o.put("fpsCap", a.fpsCap);
            o.put("physicsEnabled", a.physicsEnabled);
            o.put("mbMmdOn", a.mbMmdOn);
            o.put("mbMmdLen", a.mbMmdLen);
            o.put("mbMmdQual", a.mbMmdQual);
            o.put("mbGlbOn", a.mbGlbOn);
            o.put("mbGlbLen", a.mbGlbLen);
            o.put("mbGlbQual", a.mbGlbQual);
            o.put("bubbleMinPct", a.bubbleMinPct);
            o.put("bubbleHeightPct", a.bubbleHeightPct);
            // org.json 默认会把 "/" 转义成 "\/"，虽然读回时会还原，
            // 但存储里不该出现反斜杠，这里去掉。
            prefs.edit().putString(KEY_APPEARANCE,
                    o.toString().replace("\\/", "/")).apply();
        } catch (Exception ignored) {}
    }

    // ---------------- 表情别名映射（AI 精准选择表情用） ----------------
    /**
     * 表情别名 → 真实表情名，按模型分别保存。
     *
     * ★ 为什么需要：
     *   MMD 的表情名是日文或纯数字（100.001 / あ / はぅ / Close），AI 无法理解；
     *   而 100.00x 这类【装饰件（光环/头饰）】在 morph 列表里往往排在最前面，
     *   AI 很容易顺手挑到它们 —— 表现就是「AI 做的表情只有光环在动、脸一点不动」。
     *   让用户把表情映射成 1 / 2 / 3 这样的短编号后，AI 就能精准选择。
     */
    private static final String KEY_EXPR_ALIASES = "expr_aliases";

    /* ================= 名称翻译（只影响 UI 显示） =================
       ★ 目的：配件/表情/动作的名字都是日文（きつね耳 / ★胸上げ / まばたき），
         用户看不懂。翻译后只用于【显示】，不改动任何实际数据。
       ★ 缓存按【原文】全局存：同一个词在不同模型里翻译一致，可复用。 */
    private static final String KEY_TRANSLATE = "translate_config";
    private static final String KEY_TRANS_CACHE = "translate_cache";

    /** 翻译配置。 */
    public static final class TranslateConfig {
        /** 总开关（关闭则完全不翻译、也不联网）。 */
        public boolean enabled = false;
        /** 服务商：siliconflow / microsoft / custom。 */
        public String providerId = "siliconflow";
        /** 各服务商独立的 API Key：providerId -> key。 */
        public final java.util.Map<String, String> apiKeys = new java.util.HashMap<>();
        /** OpenAI 兼容服务用的模型名。 */
        public String model = "Qwen/Qwen2.5-7B-Instruct";
        /**
         * 微软翻译的区域。
         * ★ 默认填 Azure 中国版的 chinaeast2（配合中国版端点）。
         *   如果你用的是【全球版】，请改成 eastasia，并把端点换成
         *   https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=ja&to=zh-Hans
         *   填错区域会直接 401。
         */
        public String region = "chinaeast2";
        /**
         * 自定义翻译提示词（可留空 = 用内置默认）。
         * ★ 只替换"翻译风格/术语"那部分；输出必须是 JSON 的硬性要求会保留，
         *   否则模型可能不返回 JSON，解析会失败。
         */
        public String customPrompt = "";
        /** 各服务商独立的端点：providerId -> endpoint。 */
        public final java.util.Map<String, String> endpoints = new java.util.HashMap<>();

        /**
         * ★ 复用一个已配置的 AI 提供商来翻译（providerId=custom 时生效）。
         * 填了它就不必再手填 baseUrl/Key/模型 —— 直接沿用该提供商的设置。
         * 有些提供商（本地 Ollama / 内网网关）不需要 Key，这里也能正常用。
         */
        public String aiProviderId = "";

        public String apiKey() {
            String v = apiKeys.get(providerId);
            return v == null ? "" : v;
        }
        public void setApiKey(String key) { apiKeys.put(providerId, key == null ? "" : key); }

        /**
         * 当前服务商的端点。
         * ★ 必须按服务商分开存：否则从微软切到硅基流动时，
         *   端点里还留着微软的地址，请求会发到错误的地方。
         */
        public String endpoint() {
            String v = endpoints.get(providerId);
            return v == null ? "" : v;
        }
        public void setEndpoint(String ep) {
            endpoints.put(providerId, ep == null ? "" : ep);
        }
        /** 是否配置齐全、可以发起翻译。 */
        public boolean isUsable() {
            if (!enabled) return false;
            /* ★ 复用 AI 提供商时，可用性交给那个提供商自己判断
               （它允许空 Key，本地/内网服务也能用）。 */
            if (!aiProviderId.isEmpty()) return true;
            /* 微软/硅基流动必须有 Key（这两家都是云端收费服务） */
            if ("microsoft".equals(providerId) || "siliconflow".equals(providerId)) {
                return !apiKey().isEmpty();
            }
            /* ★ 自定义：允许空 Key —— 有些自建网关/本地服务不开鉴权 */
            return !model.isEmpty();
        }
    }

    public TranslateConfig translateConfig() {
        TranslateConfig c = new TranslateConfig();
        try {
            String raw = prefs.getString(KEY_TRANSLATE, null);
            if (raw == null) return c;
            JSONObject o = new JSONObject(raw);
            c.enabled = o.optBoolean("enabled", false);
            c.providerId = o.optString("providerId", "siliconflow");
            c.model = o.optString("model", "Qwen/Qwen2.5-7B-Instruct");
            c.region = o.optString("region", "chinaeast2");
            c.customPrompt = o.optString("customPrompt", "");
            c.aiProviderId = o.optString("aiProviderId", "");
            /* 端点按服务商分开存（见 endpoint() 的说明） */
            JSONObject eps = o.optJSONObject("endpoints");
            if (eps != null) {
                java.util.Iterator<String> eit = eps.keys();
                while (eit.hasNext()) {
                    String k = eit.next();
                    c.endpoints.put(k, eps.optString(k, ""));
                }
            }
            /* 兼容旧版单端点字段 */
            String legacyEp = o.optString("endpoint", "");
            if (!legacyEp.isEmpty() && !c.endpoints.containsKey(c.providerId)) {
                c.endpoints.put(c.providerId, legacyEp);
            }
            JSONObject keys = o.optJSONObject("apiKeys");
            if (keys != null) {
                java.util.Iterator<String> it = keys.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    c.apiKeys.put(k, keys.optString(k, ""));
                }
            }
        } catch (Exception ignored) {}
        return c;
    }

    public void saveTranslateConfig(TranslateConfig c) {
        if (c == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", c.enabled);
            o.put("providerId", c.providerId);
            o.put("model", c.model);
            o.put("region", c.region);
            o.put("customPrompt", c.customPrompt);
            o.put("aiProviderId", c.aiProviderId);
            JSONObject eps = new JSONObject();
            for (java.util.Map.Entry<String, String> e : c.endpoints.entrySet()) {
                eps.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
            o.put("endpoints", eps);
            JSONObject keys = new JSONObject();
            for (java.util.Map.Entry<String, String> e : c.apiKeys.entrySet()) {
                keys.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
            o.put("apiKeys", keys);
            prefs.edit().putString(KEY_TRANSLATE,
                    o.toString().replace("\\/", "/")).apply();
        } catch (Exception ignored) {}
    }

    /** 读取全部翻译缓存（原文 -> 译文）。 */
    public java.util.Map<String, String> translations() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        try {
            String raw = prefs.getString(KEY_TRANS_CACHE, null);
            if (raw == null) return out;
            JSONObject o = new JSONObject(raw);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 合并写入翻译缓存（只增不改，已有翻译不会被覆盖）。 */
    /** ★ 清除全部翻译缓存（用于换服务商后重新翻译）。 */
    public void clearTranslations() {
        try {
            prefs.edit().remove(KEY_TRANS_CACHE).apply();
        } catch (Exception ignored) {}
    }

    /** 翻译缓存条数。 */
    public int translationCount() {
        return translations().size();
    }

    public void mergeTranslations(java.util.Map<String, String> add) {
        if (add == null || add.isEmpty()) return;
        try {
            String raw = prefs.getString(KEY_TRANS_CACHE, null);
            JSONObject o = (raw == null) ? new JSONObject() : new JSONObject(raw);
            for (java.util.Map.Entry<String, String> e : add.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                /* ★ 已有翻译就跳过，不重复翻译也不覆盖 */
                if (k.isEmpty() || v.isEmpty() || o.has(k)) continue;
                o.put(k, v);
            }
            prefs.edit().putString(KEY_TRANS_CACHE,
                    o.toString().replace("\\/", "/")).apply();
        } catch (Exception ignored) {}
    }

    /* ---------------- 配件开关（按模型分别保存） ----------------
       ★ 配件天然是"每个模型不一样"的（不同模型有不同的耳朵/尾巴/鞋），
         所以必须按模型存 —— 这也是唯一合理的做法。 */
    private static final String KEY_ACCESSORIES = "accessories";

    /* ---------------------------------------------------------------- 模型别名
       ★★ 给已导入的模型起个自己看得懂的名字（2026-10-07 设计上要求）★★

       ---- 为什么不直接改文件夹名 ----
       模型的 webUrl 是 `/models/<文件夹名>/<文件>`，而用户已选中的模型
       存在 appearance.mmdModelUrl 里 —— 改文件夹名会让 URL 变化，
       导致"当前模型指向一个不存在的路径"（桌宠白屏）。
       而且文件夹名里常带日文/空格，改起来更容易出错。
       → 用一层**显示名映射**（文件夹名 → 自定义名），文件系统一个字节都不动。
       key 用【文件夹名】（= ImportedModel.name = MmdModelManager.delete() 的参数），
       与列表/删除天然一一对应。
       ★ 删除模型时必须顺手清掉别名，否则同名文件夹再导入会继承旧名字。 */
    private static final String KEY_MODEL_NAMES = "model_names";

    /** 读全部别名映射（文件夹名 → 自定义名）。 */
    private org.json.JSONObject modelAliasMap() {
        try {
            String raw = prefs.getString(KEY_MODEL_NAMES, null);
            return (raw == null) ? new org.json.JSONObject() : new org.json.JSONObject(raw);
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    /** 取某个模型的显示名（没设过就返回原名）。 */
    public String modelAlias(String dirName) {
        if (dirName == null || dirName.isEmpty()) return "";
        try {
            String v = modelAliasMap().optString(dirName, "");
            return (v == null) ? "" : v.trim();
        } catch (Exception e) { return ""; }
    }

    /** 设置别名；传空字符串 = 恢复原名。 */
    public void saveModelAlias(String dirName, String alias) {
        if (dirName == null || dirName.isEmpty()) return;
        try {
            org.json.JSONObject all = modelAliasMap();
            String v = (alias == null) ? "" : alias.trim();
            if (v.isEmpty()) all.remove(dirName);
            else all.put(dirName, v);
            /* ★ commit() 同步写入 —— 与 saveAccessories 同样的理由：
               apply() 是异步的，紧接着重建列表可能读到旧值。 */
            prefs.edit().putString(KEY_MODEL_NAMES, all.toString()).commit();
        } catch (Exception ignored) {}
    }

    /** 删除模型时清掉它的别名。 */
    public void removeModelAlias(String dirName) {
        saveModelAlias(dirName, "");
    }

    /** 读取某模型已启用的配件名单。 */
    public java.util.List<String> accessories(String modelUrl) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (modelUrl == null || modelUrl.isEmpty()) return out;
        try {
            String raw = prefs.getString(KEY_ACCESSORIES, null);
            if (raw == null) return out;
            org.json.JSONArray arr = new org.json.JSONObject(raw).optJSONArray(modelUrl);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                String n = arr.optString(i, "").trim();
                if (!n.isEmpty()) out.add(n);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 保存某模型已启用的配件名单。 */
    public void saveAccessories(String modelUrl, java.util.List<String> list) {
        if (modelUrl == null || modelUrl.isEmpty()) return;
        try {
            String raw = prefs.getString(KEY_ACCESSORIES, null);
            org.json.JSONObject all = (raw == null) ? new org.json.JSONObject() : new org.json.JSONObject(raw);
            org.json.JSONArray arr = new org.json.JSONArray();
            if (list != null) {
                for (String n : list) {
                    String v = n == null ? "" : n.trim();
                    if (!v.isEmpty()) arr.put(v);
                }
            }
            all.put(modelUrl, arr);
            /* ★★ 必须用 commit()（同步），不能用 apply()（异步）！
               ---- 事故复盘（实测"耳朵/尾巴/鞋子关不掉"）----
               "全部关闭"会连续调用本方法很多次，每次都是"读-改-写"。
               而 apply() 是【异步】的，下一次读到的可能还是未落盘的旧值
               → 多次更新互相覆盖 → 后面几次的删除【丢失】。
               结果：关掉了却没保存，下次恢复又被带回来 = "关不掉"。
               commit() 是同步写，保证每次读-改-写看到最新值。
               （单次开关的性能差异可忽略；批量场景另见 setAllAccessories。） */
            prefs.edit().putString(KEY_ACCESSORIES,
                    all.toString().replace("\\/", "/")).commit();
        } catch (Exception e) {
            android.util.Log.e("PetStore", "saveAccessories failed", e);
        }
    }

    /** 读取某模型的表情别名映射（可能为空 = 用户还没配过）。 */
    public java.util.Map<String, String> exprAliases(String modelUrl) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (modelUrl == null || modelUrl.isEmpty()) return out;
        try {
            String raw = prefs.getString(KEY_EXPR_ALIASES, null);
            if (raw == null) return out;
            JSONObject one = new JSONObject(raw).optJSONObject(modelUrl);
            if (one == null) return out;
            java.util.Iterator<String> it = one.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = one.optString(k, "").trim();
                if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 保存某模型的表情别名映射（空 map 也会写，用于"恢复成自动编号"）。 */
    public void saveExprAliases(String modelUrl, java.util.Map<String, String> map) {
        if (modelUrl == null || modelUrl.isEmpty()) return;
        try {
            String raw = prefs.getString(KEY_EXPR_ALIASES, null);
            JSONObject all = (raw == null) ? new JSONObject() : new JSONObject(raw);
            JSONObject one = new JSONObject();
            if (map != null) {
                for (java.util.Map.Entry<String, String> e : map.entrySet()) {
                    String k = e.getKey() == null ? "" : e.getKey().trim();
                    String v = e.getValue() == null ? "" : e.getValue().trim();
                    if (!k.isEmpty() && !v.isEmpty()) one.put(k, v);
                }
            }
            all.put(modelUrl, one);
            // 与 saveAppearance 一致：去掉 org.json 自动加的 \/ 转义
            prefs.edit().putString(KEY_EXPR_ALIASES,
                    all.toString().replace("\\/", "/")).apply();
        } catch (Exception ignored) {}
    }

    /* ================================================================
       VMD 动画（导入的动作文件 → 绑定到各种触发时机）
       ================================================================ */

    private static final String KEY_VMD = "vmd_config";

    /** 插槽键：触发时机 → 要播的 VMD 列表。 */
    public static final String[] VMD_SLOTS = {
            "idle", "walk", "touchHead", "touchBody", "touchFeet", "greet", "drag"
    };

    public static String vmdSlotLabel(String slot) {
        switch (slot == null ? "" : slot) {
            case "idle":      return "待机动画";
            case "walk":      return "走路动画";
            case "touchHead": return "摸头反应";
            case "touchBody": return "摸身体反应";
            case "touchFeet": return "摸脚反应";
            case "greet":     return "见面动画";
            case "drag":      return "拖动时动画";
            default:          return slot;
        }
    }

    public static String vmdSlotHint(String slot) {
        switch (slot == null ? "" : slot) {
            case "idle":      return "没在走路、也没在做动作时循环播放（留空 = 用内置的程序化呼吸）";
            case "walk":      return "自动漫游时会循环播放（留空 = 不播动作，只有窗口在移动）";
            case "touchHead": return "点桌宠头部时播一次，播完回到待机";
            case "touchBody": return "点桌宠身体时播一次，播完回到待机";
            case "touchFeet": return "点桌宠脚部时播一次，播完回到待机";
            case "greet":     return "桌宠启动 / 切换模型后播一次";
            case "drag":      return "手指拖着桌宠移动时播一次。这类\"被拎起来\"的动作末帧通常就是悬空姿态，"
                                    + "会一直保持到你松手（松手立刻回待机）。"
                                    + "glTF 模型可试 Formation_Pickup / Cafe_Reaction 这类";
            default:          return "";
        }
    }

    /** VMD 动画配置。 */
    public static final class VmdConfig {
        /** 总开关：关掉后完全不播 VMD，回到原来的程序化待机。 */
        public boolean enabled = true;
        /** 每个插槽选中的 VMD id 列表（一个插槽可以叠多个，比如「动作 + 表情」两个文件）。 */
        public final java.util.LinkedHashMap<String, java.util.List<String>> slots =
                new java.util.LinkedHashMap<>();
        /** 待机随机表演：隔一段时间随机从动作池里抽一个播一次。 */
        public boolean randomIdle = false;
        public int randomMinSec = 25;
        public int randomMaxSec = 70;
        /** 随机表演单次播放秒数（动作时长不足时会自动延长一点）。 */
        /** 随机表演允许使用的 VMD id（用户勾选的「可以用哪些动作」）。 */
        public final List<String> randomPool = new ArrayList<>();
        /**
         * 锁定根骨骼（センター等）的水平位移 —— 默认开。
         * 走路动作里自带「往前走」的位移，不锁的话模型会在窗口里一路走偏。
         * 关掉可以看到动作的原版效果（会走偏，仅供预览）。
         */
        public boolean rootMotionLock = true;
        /** 往左走时把模型水平转 180° 面朝左。 */
        public boolean faceLeftOnWalk = true;

        /**
         * 同一个插槽选了多个动作时：
         *   true （默认）= 叠加播放（同时播、按权重混合）—— 适合「动作 + 表情」
         *   false      = 顺序播放（按列表顺序依次播）—— 适合「跳起来 → 落地」
         */
        public boolean overlayOneShot = true;

        /**
         * 一次性动作自动裁到「一圈」。
         *
         * ★★ 为什么需要（2026-10-06 实测）★★
         *   很多动作文件本身就是【循环动作】（拍手/声援/挥手…），
         *   里面烘焙了多圈。实测 emote_cheer.vmd：
         *   第 81 帧和第 162 帧都回到起始姿态 → 179 帧 = 2 圈。
         *   作为"点一下播一次"的反应时，看起来就是"重复了两次"。
         *   开启后：自动检测第一个循环点，把剪辑时长裁到那里。
         */
        public boolean trimLoop = true;

        public List<String> slot(String key) { return slotIn(slots, key); }

        /**
         * ★★★ glTF 模型专用的插槽表（2026-10-07 新增）★★★
         *
         * ---- 为什么必须分开存 ----
         * 曾出现"我之前配置 mmd 的动作，用完 glb 再切回 mmd 就会消失"。
         * 根因：MMD 与 glTF 的动作**共用同一份 slots**，而选动作对话框确定时是
         * `l.clear(); l.addAll(picked)` —— **整个插槽被替换**；
         * 而 GLB 模式下动作库只列出 GLB 自带动作 →
         * 一点"确定"就把该插槽里原来的 VMD 动作全删了。切回 MMD 自然"消失"。
         *
         * → 两套动作各存一份，UI 按当前模型类型读写对应那份，互不覆盖。
         *   （老存档里混在 slots 里的 `glb:` 项会在 vmdConfig() 里自动迁移过来。）
         */
        public final java.util.LinkedHashMap<String, java.util.List<String>> slotsGlb =
                new java.util.LinkedHashMap<>();

        public List<String> slotGlb(String key) { return slotIn(slotsGlb, key); }

        /** 按模型类型取插槽（glb=true → glTF 那份）。UI 层一律用这个。 */
        public List<String> slotFor(String key, boolean glb) {
            return glb ? slotGlb(key) : slot(key);
        }

        private static List<String> slotIn(
                java.util.LinkedHashMap<String, java.util.List<String>> m, String key) {
            List<String> l = m.get(key);
            if (l == null) { l = new ArrayList<>(); m.put(key, l); }
            return l;
        }

        public int slotCount(String key) {
            List<String> l = slots.get(key);
            return l == null ? 0 : l.size();
        }
    }

    public VmdConfig vmdConfig() {
        VmdConfig c = new VmdConfig();
        try {
            String raw = prefs.getString(KEY_VMD, null);
            /* ★ 没有存档时用「预置默认」：开箱就有待机/走路/摸头动作可用。
               用户一旦改过任何一项，就以存档为准（不会再被默认值覆盖）。 */
            if (raw == null) return defaultVmdConfig(c);
            JSONObject o = new JSONObject(raw);
            c.enabled = o.optBoolean("enabled", true);
            c.randomIdle = o.optBoolean("randomIdle", false);
            c.randomMinSec = Math.max(5, Math.min(600, o.optInt("randomMinSec", 25)));
            c.randomMaxSec = Math.max(c.randomMinSec + 5,
                    Math.min(900, o.optInt("randomMaxSec", 70)));
            c.rootMotionLock = o.optBoolean("rootMotionLock", true);
            c.faceLeftOnWalk = o.optBoolean("faceLeftOnWalk", true);
            c.overlayOneShot = o.optBoolean("overlayOneShot", true);
            c.trimLoop = o.optBoolean("trimLoop", true);
            JSONObject s = o.optJSONObject("slots");
            if (s != null) {
                for (String k : VMD_SLOTS) {
                    JSONArray arr = s.optJSONArray(k);
                    List<String> l = c.slot(k);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String v = arr.optString(i, "").trim();
                        if (!v.isEmpty()) l.add(v);
                    }
                }
            }
            /* ★★ 迁移：老存档里 `glb:` 动作是混在 slots 里的，挪到 slotsGlb ★★
               必须在读 slotsGlb 之前做，否则顺序反过来会把刚读进来的又搬走。 */
            for (String k : VMD_SLOTS) {
                for (java.util.Iterator<String> it = c.slot(k).iterator(); it.hasNext(); ) {
                    String v = it.next();
                    if (v != null && v.startsWith("glb:")) {
                        it.remove();
                        if (!c.slotGlb(k).contains(v)) c.slotGlb(k).add(v);
                    }
                }
            }
            JSONObject sg = o.optJSONObject("slotsGlb");
            if (sg != null) {
                for (String k : VMD_SLOTS) {
                    JSONArray arr = sg.optJSONArray(k);
                    if (arr == null) continue;
                    List<String> l = c.slotGlb(k);
                    for (int i = 0; i < arr.length(); i++) {
                        String v = arr.optString(i, "").trim();
                        if (!v.isEmpty() && !l.contains(v)) l.add(v);
                    }
                }
            }
            JSONArray rp = o.optJSONArray("randomPool");
            if (rp != null) {
                for (int i = 0; i < rp.length(); i++) {
                    String v = rp.optString(i, "").trim();
                    if (!v.isEmpty()) c.randomPool.add(v);
                }
            }
        } catch (Exception ignored) {
            return defaultVmdConfig(c);
        }
        return c;
    }

    /** 首次运行（没有存档）时的默认绑定：用随 APK 预置的那几个动作。 */
    private VmdConfig defaultVmdConfig(VmdConfig c) {
        c.slot("idle").add("_builtin/idle_breath.vmd");
        c.slot("walk").add("_builtin/walk_a.vmd");
        c.slot("touchHead").add("_builtin/emote_lonely.vmd");
        c.slot("touchBody").add("_builtin/emote_cheer.vmd");
        c.slot("touchFeet").add("_builtin/emote_clap.vmd");
        c.slot("greet").add("_builtin/emote_wave.vmd");
        c.randomPool.add("_builtin/emote_wave.vmd");
        c.randomPool.add("_builtin/emote_bow.vmd");
        c.randomPool.add("_builtin/emote_salute.vmd");
        c.randomPool.add("_builtin/emote_clap.vmd");
        c.randomPool.add("_builtin/emote_beckon.vmd");
        c.randomPool.add("_builtin/emote_cheer.vmd");
        c.randomPool.add("_builtin/emote_lonely.vmd");
        return c;
    }

    public void saveVmdConfig(VmdConfig c) {
        if (c == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", c.enabled);
            o.put("randomIdle", c.randomIdle);
            o.put("randomMinSec", c.randomMinSec);
            o.put("randomMaxSec", c.randomMaxSec);
            o.put("rootMotionLock", c.rootMotionLock);
            o.put("faceLeftOnWalk", c.faceLeftOnWalk);
            o.put("overlayOneShot", c.overlayOneShot);
            o.put("trimLoop", c.trimLoop);
            JSONObject s = new JSONObject();
            for (String k : VMD_SLOTS) {
                JSONArray arr = new JSONArray();
                for (String v : c.slot(k)) if (!v.isEmpty()) arr.put(v);
                s.put(k, arr);
            }
            o.put("slots", s);
            JSONObject sg = new JSONObject();
            for (String k : VMD_SLOTS) {
                JSONArray arr = new JSONArray();
                for (String v : c.slotGlb(k)) if (!v.isEmpty()) arr.put(v);
                sg.put(k, arr);
            }
            o.put("slotsGlb", sg);
            JSONArray rp = new JSONArray();
            for (String v : c.randomPool) if (!v.isEmpty()) rp.put(v);
            o.put("randomPool", rp);
            prefs.edit().putString(KEY_VMD,
                    o.toString().replace("\\/", "/")).apply();
        } catch (Exception ignored) {}
    }

    /** 供页面用的精简 JSON（只含它需要的字段）。
     *  ★ 这里把 id 转成【可直接加载的 URL】—— 路径里的日文/空格必须
     *    百分号编码，交给页面自己拼容易出错（两边编码规则不一致）。 */
    /**
     * 把 id 转成给页面用的 URL。
     *
     * ★★ glb: 前缀【不能】编码（实测踩过）★★
     *   VMD 是文件路径，必须 Uri.encode（日文/空格）。
     *   但 glTF 自带动作的 id 形如 `glb:Cafe_Idle`，它也走了一遍
     *   Uri.encode → 变成 `/vmd/glb%3ACafe_Idle` →
     *   JS 那边 `url.indexOf('glb:') === 0` 判定失败 →
     *   插槽等于没有动作 → 点"试播"报 "VMD 未就绪"（实测点试播没用）。
     *   → glb: 原样透传。
     */
    private static String jsUrlOf(String id) {
        if (id == null) return "";
        if (id.startsWith("glb:")) return id;
        return VmdManager.webUrlOf(id);
    }

    public String vmdConfigJson() {
        VmdConfig c = vmdConfig();
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", c.enabled);
            o.put("rootLock", c.rootMotionLock);
            o.put("faceLeft", c.faceLeftOnWalk);
            o.put("overlay", c.overlayOneShot);
            o.put("trimLoop", c.trimLoop);
            JSONObject s = new JSONObject();
            for (String k : VMD_SLOTS) {
                JSONArray arr = new JSONArray();
                for (String v : c.slot(k)) arr.put(jsUrlOf(v));
                s.put(k, arr);
            }
            o.put("slots", s);
            /* ★ glTF 那套插槽单独发一份 —— JS 按自己的模型类型选（见 vmdSlotUrls）。
               ★ 不在 Java 侧按"当前模型类型"二选一：PetStore 是 data 层，
                 并不知道桌宠页面此刻加载的是 pmx 还是 glb，两套都发最稳。 */
            JSONObject sg2 = new JSONObject();
            for (String k : VMD_SLOTS) {
                JSONArray arr = new JSONArray();
                for (String v : c.slotGlb(k)) arr.put(jsUrlOf(v));
                sg2.put(k, arr);
            }
            o.put("slotsGlb", sg2);
            JSONArray pool = new JSONArray();
            for (String v : c.randomPool) pool.put(jsUrlOf(v));
            o.put("pool", pool);
            return o.toString().replace("\\/", "/");
        } catch (Exception e) {
            return "{}";
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
