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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 名称翻译服务 —— 把 MMD 模型的日文配件/表情/动作名翻成中文，
 * 【只用于界面显示】，不修改任何实际数据（morph 名、配件名单都保持原样）。
 *
 * 两个内置服务商：
 *  · siliconflow（硅基流动）—— OpenAI 兼容，走 /chat/completions，能理解 MMD 术语
 *  · microsoft（微软翻译）—— 专用 REST API，免费额度大、响应快、返回与请求一一对应
 *  · custom —— 任意 OpenAI 兼容端点
 *
 * 设计要点：
 *   1) 批量翻译（一次请求翻一批），减少调用次数
 *   2) 只翻译缓存里没有的（调用方负责过滤），已有译文绝不重复翻译
 *   3) 任何失败都静默返回已拿到的部分，不能让翻译把桌宠功能搞挂
 */
public final class TranslationService {

    /* ★ 批次要小、token 要给够 ——
       ---- 事故复盘（实测"只翻到 40 条 / 得到 0 条"）----
       实测某些提供商（如 pollinations 的 stealth/space-bunny-alpha）是【推理模型】：
       它会把大量 token 花在"思考"上，max_tokens 一到就直接截断，
       返回 `finish_reason:"length"` + `content:null` —— 正文一个字都没有。
       25 条 × 2000 tokens 就会稳定触发。
       → 批次降到 15 条、token 给到 4000，给推理模型留足空间。 */
    /**
     * 每批条数。
     * ★ 推理模型对每个名字都要"想"一遍，15 个一批很容易超时/被截断，
     *   实测降到 10 稳定得多（代价只是多几次请求，但总时间反而更短）。
     */
    private static final int BATCH = 10;

    /**
     * 最近一次"硬错误"（服务商/模型级别的失败，重试无意义）。
     *
     * ★ 为什么要单独记录（2026-10-06 实测）：
     *   用户配的模型 `stealth/space-bunny-alpha` 已经下线，
     *   接口每次都返回 404 "No endpoints found"。
     *   旧实现会把这个错误吞掉、继续把剩下的 10 批全发一遍 ——
     *   白白浪费 11 次请求、耗时十几秒、用户还只看到"翻不出东西"。
     *   现在遇到这类错误立刻中断，并把原因带回界面。
     */
    private static volatile String lastFatal = null;

    /* ---------------- 翻译进度（供界面实时显示） ----------------
       ★ 实测：翻译100多条要好几分钟，界面上全是"翻译中…"，
         根本不知道到底有没有翻出来。这里每完成一批就回调一次。 */
    public interface ProgressListener {
        /**
         * @param done  已处理条数
         * @param total 总条数
         * @param got   本次新得到的译文（可直接合并入缓存）
         */
        void onProgress(int done, int total, java.util.Map<String, String> got);
    }

    private static volatile ProgressListener progressListener;

    public static void setProgressListener(ProgressListener l) { progressListener = l; }

    /**
     * 翻译过程诊断（设计上要求：失败时直接把日志显示出来）。
     *
     * ★ 为什么需要（2026-10-07）：
     *   翻译有【4 条静默返回路径】（没开开关 / 复用的提供商不存在 /
     *   没配完整 / 配置不可用）。任何一条命中都只是"翻出 0 条"，
     *   界面只显示"未翻出"，用户和我们都无从判断到底卡在哪一步。
     *   这里把每一步都记下来，界面直接展示。
     */
    private static final java.util.List<String> DIAG =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    private static void diag(String s) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + "  " + s;
        android.util.Log.i("PetTranslate", "diag: " + s);
        synchronized (DIAG) {
            DIAG.add(line);
            while (DIAG.size() > 40) DIAG.remove(0);
        }
    }

    /** 最近一次翻译的完整诊断文本（可直接显示给用户）。 */
    public static String lastDiagnostic() {
        synchronized (DIAG) {
            if (DIAG.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String l : DIAG) sb.append(l).append('\n');
            return sb.toString().trim();
        }
    }

    public static void clearDiagnostic() {
        synchronized (DIAG) { DIAG.clear(); }
    }

    /** 最近一次硬错误的原因（null = 没有）。 */
    public static String lastFatalError() { return lastFatal; }

    public static void clearFatalError() { lastFatal = null; }

    /** 判断是不是"再试也没用"的错误。 */
    private static boolean isFatal(int code, String body) {
        if (code == 401 || code == 403 || code == 404 || code == 400) return true;
        if (body == null) return false;
        String b = body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("no endpoints found")
                || b.contains("model_not_found")
                || b.contains("does not exist")
                || b.contains("invalid api key")
                || b.contains("insufficient")
                || b.contains("unauthorized");
    }
    /**
     * 上游是否拒收"关闭思考"字段。
     *
     * ★ 实测（2026-10-07，pollinations/gen → NVIDIA 上游）：
     *   带 `enable_thinking` / `disable_reasoning` 会直接
     *   400 "Validation: Unsupported parameter(s)"。
     *   旧实现只在【单个批次内】重试，于是每一批都要先撞一次 400 ——
     *   152 个名字 = 11 批 = 11 次无用请求 + 明显变慢。
     *   这里把结论记成静态标记，后续批次直接不带这些字段。
     */
    private static volatile boolean thinkingFieldsRejected = false;

    /**
     * 并发闸门。
     *
     * ★★ 为什么必须有（2026-10-06 实测日志）★★
     *   打开 VMD 页会【两次】触发翻译：onResume 一次、扫描完成一次。
     *   两遍并发打到同一个提供商，日志里能看到同一批号出现两次，
     *   结果是互相挤占、大面积 SocketTimeout —— 看起来像"翻译全失败"。
     *   这里用一个原子标记保证同一时刻只有一轮翻译在跑。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 单个 HTTP 请求超时。
     * ★ 推理模型翻译 10~15 个名字可能要几十秒（token 全花在思考上），
     *   40s 实测不够（大量 Read timed out）。放宽到 90s。
     */
    private static final int TIMEOUT_MS = 90000;
    private static final int MAX_TOKENS = 4000;

    /* 微软翻译有两个版本，端点不同 —— 必须让用户能选，否则填错直接 401 */
    /** 全球版 */
    public static final String MS_ENDPOINT_GLOBAL =
            "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=ja&to=zh-Hans";
    /** Azure 中国版（由世纪互联运营） */
    public static final String MS_ENDPOINT_CN =
            "https://api.translator.azure.cn/translate?api-version=3.0&from=ja&to=zh-Hans";

    private TranslationService() {}

    /** 翻译一批名字（不做缓存判断，调用方负责只传"还没翻译的"）。 */
    public static Map<String, String> translate(PetStore.TranslateConfig cfg,
                                                List<String> names) {
        return translate(null, cfg, names);
    }

    /**
     * 翻译一批名字。
     * ★ 传了 store 时，如果配置里选了"复用某个 AI 提供商"（aiProviderId），
     *   就直接沿用那个提供商的 baseUrl / Key / 模型 / 路径 ——
     *   用户不必再手填一遍，也不会和提供商设置不一致。
     */
    public static Map<String, String> translate(PetStore store,
                                                PetStore.TranslateConfig cfg,
                                                List<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        /* ★ 同一时刻只跑一轮（见 RUNNING 的说明）。
           重复进来时直接返回空集合，调用方那边本来就是"没有就下次再翻"。 */
        if (!RUNNING.compareAndSet(false, true)) {
            android.util.Log.i("PetTranslate", "已有翻译在跑，跳过这次重复请求");
            return out;
        }
        try {
            return translateInner(store, cfg, names);
        } finally {
            RUNNING.set(false);
        }
    }

    private static Map<String, String> translateInner(PetStore store,
                                                      PetStore.TranslateConfig cfg,
                                                      List<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        clearDiagnostic();
        if (cfg == null) { diag("配置为空"); return out; }
        if (names == null || names.isEmpty()) { diag("没有待翻译的名字"); return out; }
        diag("配置：enabled=" + cfg.enabled + " providerId=" + cfg.providerId
                + " aiProviderId=" + (cfg.aiProviderId == null ? "" : cfg.aiProviderId)
                + " model=" + cfg.model + " 待翻 " + names.size() + " 条");
        if (!cfg.enabled) { diag("★ 总开关未启用（名称翻译里的开关）"); return out; }

        /* 解析"复用提供商"：把它的设置临时套进配置，后续逻辑不用改 */
        PetStore.TranslateConfig use = cfg;
        String resolvedPath = null;
        if (store != null && cfg.aiProviderId != null && !cfg.aiProviderId.isEmpty()) {
            PetStore.Provider p = null;
            for (PetStore.Provider x : store.providers()) {
                if (x != null && cfg.aiProviderId.equals(x.id)) { p = x; break; }
            }
            if (p == null) {
                diag("★ 复用的提供商不存在：id=" + cfg.aiProviderId
                        + "（存档里现有 " + store.providers().size() + " 个提供商）");
                return out;
            }
            if (!p.isUsable()) {
                diag("★ 复用的提供商配置不完整：" + p.name
                        + " baseUrl=[" + p.baseUrl + "] model=[" + p.model + "]");
                return out;
            }
            diag("复用提供商：" + p.name + " endpoint=" + p.chatEndpoint()
                    + " model=" + p.model);
            use = new PetStore.TranslateConfig();
            use.enabled = true;
            use.providerId = "custom";
            use.model = p.model;
            use.customPrompt = cfg.customPrompt;
            use.setApiKey(p.apiKey == null ? "" : p.apiKey);
            use.setEndpoint(p.baseUrl == null ? "" : p.baseUrl);
            resolvedPath = p.chatPath;
        }

        if (!use.isUsable()) {
            diag("★ 配置不可用：providerId=" + use.providerId
                    + " model=[" + use.model + "] endpoint=[" + use.endpoint() + "]");
            return out;
        }

        lastFatal = null;                     // 新一轮开始，清掉上次的错误
        for (int i = 0; i < names.size(); i += BATCH) {
            /* ★ 已经是"模型不存在 / 没权限"这种硬错误 → 别再浪费请求了 */
            if (lastFatal != null) {
                android.util.Log.e("PetTranslate", "已中断："
                        + lastFatal + "（还剩 " + (names.size() - i) + " 条未翻）");
                break;
            }
            List<String> part = new ArrayList<>(
                    names.subList(i, Math.min(names.size(), i + BATCH)));
            /* ★★ 重试必须包在 try 【外面】★★
               ---- 踩过的坑（2026-10-06）----
               translateOpenAi 在超时时会【抛异常】而不是返回空集合。
               重试循环如果写在 try 里面，
               第一次超时就直接跳到 catch，【永远不会重试】。 */
            Map<String, String> r = new LinkedHashMap<>();
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    if (attempt > 1) {
                        diag("  重试第 " + (i / BATCH + 1) + " 批（第 " + attempt + " 次）");
                        try { Thread.sleep(1500L * attempt); } catch (InterruptedException ignored) {}
                    }
                    diag("第 " + (i / BATCH + 1) + " 批（第 " + attempt + " 次）：请求 " + part.size() + " 条");
                    r = "microsoft".equals(use.providerId)
                            ? translateMicrosoft(use, part)
                            : translateOpenAi(use, part, resolvedPath);
                    if (r != null && !r.isEmpty()) break;      // 成功
                    if (lastFatal != null) break;               // 硬错误（模型/密钥）重试无意义
                } catch (Throwable t) {
                    android.util.Log.w("PetTranslate",
                            "batch " + (i / BATCH + 1) + " 第 " + attempt + " 次失败: " + t);
                    diag("  第 " + attempt + " 次失败：" + t);
                    r = new LinkedHashMap<>();
                }
            }
            int n = (r == null) ? 0 : r.size();
            android.util.Log.i("PetTranslate", "batch " + (i / BATCH + 1)
                    + " 请求 " + part.size() + " 条 -> 得到 " + n + " 条");
            if (!r.isEmpty()) out.putAll(r);

            /* ★ 每完成一批就通知界面：已翻多少、这批新得到什么。
               界面拿到后可以立即写缓存 + 刷新列表（不用等全部跑完）。 */
            ProgressListener pl = progressListener;
            if (pl != null) {
                try {
                    pl.onProgress(Math.min(names.size(), i + BATCH),
                            names.size(), new LinkedHashMap<>(r));
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    // ---------------- 微软翻译 ----------------

    private static Map<String, String> translateMicrosoft(PetStore.TranslateConfig cfg,
                                                          List<String> names) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        JSONArray body = new JSONArray();
        for (String n : names) {
            JSONObject o = new JSONObject();
            o.put("Text", n);
            body.put(o);
        }
        /* ★ 端点可配置：微软有【全球版】和【Azure 中国版】两个不同的地址，
           区域也要跟着配套（全球版用 eastasia 等，中国版用 chinaeast2 等）。
           填错会直接 401，所以这里以配置优先、默认走中国版。 */
        String msUrl = (cfg.endpoint() == null || cfg.endpoint().trim().isEmpty())
                ? MS_ENDPOINT_CN : cfg.endpoint().trim();
        HttpURLConnection conn = (HttpURLConnection) new URL(msUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Ocp-Apim-Subscription-Key", cfg.apiKey().trim());
            if (cfg.region != null && !cfg.region.trim().isEmpty()) {
                conn.setRequestProperty("Ocp-Apim-Subscription-Region", cfg.region.trim());
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream()
                    : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                android.util.Log.w("PetTranslate", "ms http " + code + ": " + resp);
                return out;
            }
            /* 微软返回数组，顺序与请求一致 -> 可按索引一一对应（最可靠） */
            JSONArray arr = new JSONArray(resp);
            for (int i = 0; i < arr.length() && i < names.size(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                JSONArray tr = item.optJSONArray("translations");
                if (tr == null || tr.length() == 0) continue;
                String text = tr.optJSONObject(0).optString("text", "").trim();
                if (!text.isEmpty()) out.put(names.get(i), cleanup(text));
            }
        } finally {
            conn.disconnect();
        }
        return out;
    }

    // ---------------- OpenAI 兼容（硅基流动 / 自定义） ----------------

    private static Map<String, String> translateOpenAi(PetStore.TranslateConfig cfg,
                                                       List<String> names,
                                                       String pathOverride) throws Exception {
        return translateOpenAi(cfg, names, pathOverride, false);
    }

    /**
     * @param noThinkingRetry 内部用：true 表示这是"撤回关思考字段后"的重试，不再加那些字段
     */
    private static Map<String, String> translateOpenAi(PetStore.TranslateConfig cfg,
                                                       List<String> names,
                                                       String pathOverride,
                                                       boolean noThinkingRetry) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();

        String base = cfg.endpoint() == null ? "" : cfg.endpoint().trim();
        if (base.isEmpty()) base = "https://api.siliconflow.cn/v1";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        /* ★ 路径优先用提供商自己的设置（复用提供商时它可能是别的路径） */
        String path = (pathOverride == null || pathOverride.trim().isEmpty())
                ? "/chat/completions" : pathOverride.trim();
        if (!path.startsWith("/")) path = "/" + path;
        String url = base + path;

        StringBuilder list = new StringBuilder();
        for (String n : names) {
            if (list.length() > 0) list.append('\n');
            list.append(n);
        }

        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        /* ★ 提示词分两段：
           ① 风格/术语部分 —— 用户可以自定义（customPrompt）
           ② 输出格式要求 —— 固定保留，否则模型不返回 JSON、解析必失败 */
        String stylePart;
        if (cfg.customPrompt != null && !cfg.customPrompt.trim().isEmpty()) {
            stylePart = cfg.customPrompt.trim();
        } else {
            stylePart =
                  "你是 MMD（MikuMikuDance）模型术语翻译器。"
                + "把用户给出的日文/英文名称翻译成【简短中文】，用于软件界面显示。\n"
                + "规则：\n"
                + "1. JSON 的键必须是【原文原样】，值是最多 8 个汉字的译名。\n"
                + "2. 常见术语参考："
                + "きつね耳=狐耳，尻尾=尾巴，靴=鞋，靴下=袜子，上着/ジャケット=外套，"
                + "ネクタイ=领带，スカート=裙子，めがね=眼镜，リボン=蝴蝶结，"
                + "ニプレス=乳贴，まばたき=眨眼，照れ/赤面=脸红，"
                + "胸上げ=抬胸，髪=头发，ヘイロー=光环。\n"
                + "3. ON_ 开头表示【显示该部件】，OFF_ 开头表示【隐藏该部件】，译名要体现。\n"
                /* ★ 动作（VMD）名字的术语表 —— 用户导入的动作包全是日文，
                   没有这些提示模型会把「歩き」直译成「步行」之类不统一的词。
                   另外那串 _(40f_前移動20) 是元信息，要求保留在译名后面。 */
                + "4. 动作/动画类术语：歩き=走路，歩き出し=起步，走り=跑步，ダッシュ=冲刺，"
                + "待機=待机，呼吸=呼吸，ジャンプ=跳跃，落下=下落，着地=落地，"
                + "階段=楼梯，上る=上楼，下りる=下楼，止る=停下，カーブ=转弯，"
                + "回転=旋转，前移動=前移，ターン=转身，アクション=动作，アクロバット=特技，"
                + "前宙=前空翻，バク宙=后空翻，お辞儀=鞠躬，敬礼=敬礼，手を振る=挥手，"
                + "拍手=鼓掌，手拍子=打拍子，声援=加油，手招き=招手，寂しがる=闹别扭，"
                + "注意喚起=提醒，落ち着かせる=安抚，揉み手=搓手，モーション=动作。\n"
                + "5. 名字里形如 _(40f_前移動20) 的括号是元信息，"
                + "译名里保留成 (40帧/前移20) 这种简写即可，不要丢掉。\n"
                + "6. 实在看不懂的条目直接跳过，不要输出。";
        }
        /* ★★ 自定义提示词只负责"风格/术语"，不能改掉"必须翻成中文"这条 ★★
           ---- 实测（2026-10-06）----
           用户把 customPrompt 写成"你现在是一个翻译机器人，你目前需要翻译mmd模型的动作/功能名字"，
           而它【整段替换】了默认提示 —— 里面没有"译成中文"，
           于是模型输出英文："Start Running (1"、"Running start"…
           界面看起来就像"翻译乱掉了"。
           这里在自定义提示词后面补一段硬性要求（输出格式那段本来就是固定的）。 */
        String hardRule = "\n\n【硬性要求（无论上面怎么写都必须遵守）】\n"
                + "1. 译名必须是【简体中文】，不要输出英文、日文或拼音。\n"
                + "2. 译名尽量简短（最多 12 个字），必要时保留 (40帧/前移20) 这类数字备注。\n"
                + "3. 看不懂的条目直接跳过（不要输出该键）。";
        sys.put("content", stylePart + hardRule
                + "\n\n【输出格式（必须严格遵守）】\n"
                + "只输出一个 JSON 对象，不要有任何解释文字、不要用代码块包裹。"
                + "格式：{\"原文1\":\"译名1\",\"原文2\":\"译名2\"}");

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", list.toString());

        JSONArray messages = new JSONArray();
        messages.put(sys);
        messages.put(user);

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("messages", messages);
        body.put("temperature", 0.1);
        body.put("max_tokens", MAX_TOKENS);
        body.put("stream", false);
        /* ★★ 尝试关闭"思考"，让 token 全用在正文上。
           ---- 为什么要这么做 ----
           实测某些提供商是【推理模型】：把大量 token 花在思考上，
           到 max_tokens 就截断，返回 finish_reason=length + content=null，
           正文一个字都没有 → "翻译得到 0 条"。
           ★ 各家字段名不同，这里把常见几种都发一遍
             （不认识的字段绝大多数 OpenAI 兼容服务会忽略，是安全的）。
           ★ 但如果端点【强制要求推理】（实测 pollinations 会返回
             400 "Reasoning is mandatory ... cannot be disabled"），
             下面会捕获 400 并撤掉这些字段重试。 */
        List<String> disableThinkingFields = new ArrayList<>();
        if (!noThinkingRetry && !thinkingFieldsRejected) {
            try { body.put("reasoning_effort", "none"); disableThinkingFields.add("reasoning_effort"); } catch (Exception ignored) {}
            try { body.put("enable_thinking", false); disableThinkingFields.add("enable_thinking"); } catch (Exception ignored) {}
            try {
                JSONObject th = new JSONObject();
                th.put("type", "disabled");
                body.put("thinking", th);
                disableThinkingFields.add("thinking");
            } catch (Exception ignored) {}
            try {
                JSONObject ctk = new JSONObject();
                ctk.put("enable_thinking", false);
                body.put("chat_template_kwargs", ctk);
                disableThinkingFields.add("chat_template_kwargs");
            } catch (Exception ignored) {}
            try { body.put("disable_reasoning", true); disableThinkingFields.add("disable_reasoning"); } catch (Exception ignored) {}
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            /* ★ Key 可能为空（本地/内网服务不开鉴权），为空就不带这个头 */
            if (!cfg.apiKey().trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey().trim());
            }
            /* POST 的 URL 会由下面的 diag() 一并记录 */
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream()
                    : conn.getErrorStream();
            String resp = readAll(is);
            diag("  POST " + url + "  ->  HTTP " + code
                    + (code >= 200 && code < 300 ? "" : ("  响应: " + shortOf(resp))));

            /* ★★ 有些端点【强制要求推理】，关不掉并直接 400：
                  "Reasoning is mandatory for this endpoint and cannot be disabled."
               这时把关闭思考的字段全部撤掉，并加大 token 重试一次（只重试一次）。
               这样既能在支持的端点上省 token 加快速度，
               又不会在"强制推理"的端点上彻底翻译失败。 */
            if (code == 400 && !disableThinkingFields.isEmpty()) {
                android.util.Log.i("PetTranslate",
                        "端点不接受关闭思考，撤回这些字段并加大 token 重试: " + disableThinkingFields);
                for (String k : disableThinkingFields) body.remove(k);
                disableThinkingFields.clear();
                /* 记住这次拒绝：后面所有批次都不再带这些字段 */
                thinkingFieldsRejected = true;
                diag("  ⚠ 上游不接受「关闭思考」字段，已剔除并加大 token 重试"
                        + "（后续批次将直接不带）");
                try { body.put("max_tokens", MAX_TOKENS * 3); } catch (Exception ignored) {}
                conn.disconnect();
                return translateOpenAi(cfg, names, pathOverride, true);
            }

            if (code < 200 || code >= 300) {
                android.util.Log.w("PetTranslate", "openai http " + code + ": " + shortOf(resp));
                if (isFatal(code, resp)) {
                    lastFatal = "HTTP " + code + "：" + shortOf(resp);
                    android.util.Log.e("PetTranslate",
                            "★ 硬错误，后续批次将直接跳过: " + lastFatal);
                }
                return out;
            }
            JSONObject root = new JSONObject(resp);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                android.util.Log.w("PetTranslate", "无 choices，原始响应: " + shortOf(resp));
                return out;
            }
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            String content = (msg == null) ? "" : msg.optString("content", "");
            /* ★ 推理模型常把正文放进 reasoning_content / reasoning，
               content 为 null。这时退回去试这两个字段
               （extractJsonObject 会从长文本里把 JSON 抠出来）。 */
            if (content == null || content.trim().isEmpty()) {
                if (msg != null) {
                    String rc = msg.optString("reasoning_content", "");
                    if (rc.trim().isEmpty()) rc = msg.optString("reasoning", "");
                    if (!rc.trim().isEmpty()) {
                        android.util.Log.i("PetTranslate",
                                "content 为空，改用 reasoning 字段（推理模型）");
                        content = rc;
                    }
                }
            }
            /* ★ 用"预期名感知"的解析：模型输出格式五花八门
               （单个 JSON 对象 / 每行一个对象 / 原文·译名结构 / 纯文本行），
               只认一种就会大面积"未翻出"。 */
            Map<String, String> got = extractTranslations(content, names);
            if (got.isEmpty()) {
                /* ★ 解析失败时把 finish_reason 和原始内容打出来 ——
                   否则只会看到"得到 0 条"，无从下手。
                   finish_reason=length 就说明是 token 不够被截断了。 */
                String fr = choices.optJSONObject(0).optString("finish_reason", "?");
                android.util.Log.w("PetTranslate", "JSON 解析失败 finish_reason=" + fr
                        + " content: " + shortOf(content)
                        + " | 原始响应: " + shortOf(resp));
                diag("  ★ 解析不出译文 finish_reason=" + fr
                        + "  模型输出: " + shortOf(content));
                if (content == null || content.trim().isEmpty()) {
                    diag("  ★ 模型没返回正文（content 为空）—— 多半是推理模型"
                            + "把 token 花在思考上，或模型名/端点不对");
                }
                return out;
            }
            for (java.util.Map.Entry<String, String> e : got.entrySet()) {
                if (!e.getKey().isEmpty() && !e.getValue().isEmpty()) {
                    out.put(e.getKey(), cleanup(e.getValue()));
                }
            }
            diag("  本批得到 " + out.size() + " 条");
        } finally {
            conn.disconnect();
        }
        return out;
    }

    /**
     * ★★ 从模型输出里抽出【译文映射】—— 必须容忍多种格式 ★★
     *
     * ---- 为什么不能只认一种（2026-10-07 实测）----
     * 用户选的模型返回的是 NDJSON（每行一个对象）：
     *     {"原文":"きつね耳","译名":"狐耳"}
     *     {"原文":"まばたき","译名":"眨眼"}
     * 旧实现先 `new JSONObject(整段)` → 失败；再截取第一个 { 到最后一个 }
     * → 拼出来也不是合法 JSON → 直接返回 null → 界面全是"（未翻出）"。
     *
     * 现在按可靠性依次尝试：
     *   ① 整段是一个 JSON 对象（扁平 map）—— 提示词里要求的格式
     *   ② NDJSON：每行一个对象；对象里带 "原文"/"译名"（或 text/translation）时按名字对，
     *      否则把该对象仅有的两个字符串当 "原文→译名"
     *   ③ 正则逐名匹配：在文本里找 "名字" 后面的第一个字符串值
     *   ④ 纯文本行："名字 → 译名" / "名字: 译名" / "名字=译名"
     * 任何一步拿到非空结果就返回。
     *
     * @param expected 本次请求的名字（用于"知道该找什么"——这是最关键的上下文）
     */
    static Map<String, String> extractTranslations(String s, List<String> expected) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null || s.trim().isEmpty()) return out;

        /* ① 整段一个 JSON 对象 */
        JSONObject whole = extractJsonObject(s);
        if (whole != null) {
            fillFlatMap(whole, out, expected);
            if (!out.isEmpty()) return out;
            /* ★ 这条诊断很重要：区分"模型没返回正文"
               与"正文有但键对不上"——后者是匹配问题，
               不看键样根本猜不到（本次就是这么发现的）。 */
            diag("  ★ JSON 解析成功但没匹配上（模型返回的键: "
                    + firstKeys(whole, 3) + "）");
        }

        /* ② NDJSON / 每行一个对象 */
        String body = stripCodeFence(s);
        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.charAt(0) != '{') continue;
            JSONObject o = extractJsonObject(line);
            if (o == null) continue;
            fillFlatMap(o, out, expected);
        }
        if (!out.isEmpty()) return out;

        /* ③ 逐名正则兜底：找 "名字" 之后的第一个字符串值 */
        for (String name : expected) {
            if (out.containsKey(name)) continue;
            String v = findValueAfter(body, name);
            if (v != null && !v.isEmpty()) out.put(name, v);
        }
        if (!out.isEmpty()) return out;

        /* ④ 纯文本行："名字 → 译名" / "名字: 译名" / "名字=译名" */
        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            for (String name : expected) {
                if (out.containsKey(name)) continue;
                if (!line.contains(name)) continue;
                String rest = line.substring(line.indexOf(name) + name.length());
                String[] seps = {"→", "->", "=>", "：", ":", "=", "，" , ","};
                for (String sep : seps) {
                    int i = rest.indexOf(sep);
                    if (i < 0) continue;
                    String v = rest.substring(i + sep.length()).trim();
                    v = stripQuotes(v);
                    if (!v.isEmpty() && !v.equals(name)) { out.put(name, v); }
                    break;
                }
            }
        }
        return out;
    }

    /** 去掉 ```json 代码块包裹。 */
    static String stripCodeFence(String s) {
        String t = s.trim();
        if (!t.startsWith("```")) return t;
        int nl = t.indexOf('\n');
        if (nl > 0) t = t.substring(nl + 1);
        int end = t.lastIndexOf("```");
        if (end >= 0) t = t.substring(0, end);
        return t.trim();
    }

    /**
     * 把一个 JSON 对象里的"原文→译名"收进 out。
     * 兼容两种形态：
     *   · 扁平 map：{"きつね耳":"狐耳", ...}
     *   · 结构化：{"原文":"きつね耳","译名":"狐耳"}
     */
    static void fillFlatMap(JSONObject o, Map<String, String> out, List<String> expected) {
        /* 结构化形态：取出"原文"和"译名"两个字段 */
        String src = firstString(o, "原文", "text", "source", "name", "key", "word", "日文", "原");
        String dst = firstString(o, "译名", "translation", "译文", "value", "result", "中文", "zh", "译");
        if (src != null && dst != null) {
            /* 原文必须是我们请求过的名字之一（避免把解释文字也收进来） */
            String hit = matchExpected(src, expected);
            if (hit != null) out.put(hit, dst);
            return;
        }
        /* 扁平 map：逐个键判断 */
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = o.optString(k, "").trim();
            if (k.isEmpty() || v.isEmpty()) continue;
            String hit = matchExpected(k, expected);
            if (hit != null) out.put(hit, v);
        }
    }

    /** 去掉首尾的引号/反引号/空白/常见中文标点。★ 用字符判断，不用正则。 */
    static String stripQuotes(String s) {
        String t = (s == null) ? "" : s.trim();
        while (!t.isEmpty()) {
            char c = t.charAt(0);
            if (c == C_DQ || c == C_SQ || c == C_BT || c == '\u201C' || c == '\u201D'
                    || c == '\u300C' || c == '\u300D') { t = t.substring(1).trim(); continue; }
            break;
        }
        while (!t.isEmpty()) {
            char c = t.charAt(t.length() - 1);
            if (c == C_DQ || c == C_SQ || c == C_BT || c == '\u201C' || c == '\u201D'
                    || c == '\u300C' || c == '\u300D' || c == ',' || c == '\uFF0C'
                    || c == '\u3002' || c == '\u3001') {
                t = t.substring(0, t.length() - 1).trim();
                continue;
            }
            break;
        }
        return t;
    }

    /** 取前 n 个键（诊断用）。 */
    static String firstKeys(JSONObject o, int n) {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = o.keys();
        int c = 0;
        while (it.hasNext() && c < n) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(it.next());
            c++;
        }
        return sb.toString();
    }

    /** 取第一个非空的字符串字段值。 */
    static String firstString(JSONObject o, String... names) {
        for (String n : names) {
            String v = o.optString(n, "");
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** 常用字符常量（避开字符串转义，前面已经被它坑过两次）。 */
    private static final char C_DQ = (char) 34;    // 双引号 "
    private static final char C_SQ = (char) 39;    // 单引号 '
    private static final char C_BT = (char) 96;    // 反引号 `

    /**
     * 在预期名里找到与模型返回的键相对应的那一个。
     *
     * ★★ 必须模糊匹配（实测 2026-10-06）★★
     *   我们发出去的名字带层级和编号，例如：
     *       user / 移動モーション配布v1.3 / 1.歩く(女) / 2.歩き10L_(40f_前移動20)
     *   而模型会自己"精简"成叶子名（砍掉目录、去掉行首编号）：
     *       歩き10L_(40f_前移動20)
     *   只认"完全相等"的话会一个都匹配不上 → 整批被丢弃（用户只看到"未翻出"）。
     */
    static String matchExpected(String s, List<String> expected) {
        if (s == null) return null;
        String raw = s.trim();
        if (raw.isEmpty()) return null;
        if (expected == null || expected.isEmpty()) return raw;
        for (String n : expected) {
            if (n != null && n.equals(raw)) return n;          // ① 原样
        }
        String t = normKey(raw);
        if (t.isEmpty()) return null;
        for (String n : expected) {                            // ② 规范化后相等
            if (n == null) continue;
            if (normKey(n).equals(t)) return n;
        }
        /* ③ 后缀匹配兜底（取最长的一个，避免短名误伤）。
           ★ 模型经常只回"文件名"，而我们发的是"上级目录 / 文件名"这类前缀更长的形式。 */
        String best = null;
        int bestLen = -1;
        for (String n : expected) {
            if (n == null || n.isEmpty()) continue;
            if (n.endsWith(raw) || raw.endsWith(n)) {
                if (n.length() > bestLen) { best = n; bestLen = n.length(); }
            }
        }
        return best;
    }

    /**
     * 规范化名字：去前导符号 → 取叶子名 → 去行首编号。
     * ★ 全程手写扫描，不用正则（避开转义）。
     */
    static String normKey(String s) {
        if (s == null) return "";
        String t = s.trim();
        /* 去前导装饰符号与空白 */
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '\u2605' || c == '\u2606' || c == '\u25CF' || c == '\u25CB'
                    || c == '\u25C6' || c == '\u25A0' || c == '\u25A1'
                    || Character.isWhitespace(c)) { i++; continue; }
            break;
        }
        t = t.substring(i).trim();
        /* 取叶子名（支持 "/" 分隔的层级） */
        int k = t.lastIndexOf('/');
        if (k >= 0) t = t.substring(k + 1).trim();
        /* 去行首编号：数字 + . / ) / ） / 、 */
        int j = 0;
        while (j < t.length() && Character.isDigit(t.charAt(j))) j++;
        if (j > 0 && j < t.length()) {
            char d = t.charAt(j);
            if (d == '.' || d == ')' || d == '\uFF09' || d == '\u3001') {
                t = t.substring(j + 1).trim();
            }
        }
        return t;
    }

    /** 在文本里找到键后面跟着的第一个值（供逐名兜底用）。 */
    static String findValueAfter(String text, String name) {
        try {
            String v = valueAfterRaw(text, name);
            if (v != null) return v;
            String leaf = normKey(name);
            if (!leaf.isEmpty() && !leaf.equals(name)) return valueAfterRaw(text, leaf);
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 找 key 后面紧跟的值：支持 "带引号的 key" 与不带引号两种，
     * 冒号也支持中英文；值可以带引号也可以不带。
     */
    static String valueAfterRaw(String text, String key) {
        if (text == null || key == null || key.isEmpty()) return null;
        int from = 0;
        while (true) {
            int p = text.indexOf(key, from);
            if (p < 0) return null;
            from = p + key.length();
            int q = from;
            if (q < text.length() && (text.charAt(q) == C_DQ || text.charAt(q) == C_SQ)) q++;
            while (q < text.length() && Character.isWhitespace(text.charAt(q))) q++;
            if (q >= text.length()) return null;
            char c = text.charAt(q);
            if (c != ':' && c != '\uFF1A') continue;
            q++;
            while (q < text.length() && Character.isWhitespace(text.charAt(q))) q++;
            if (q >= text.length()) return null;
            char v0 = text.charAt(q);
            if (v0 == C_DQ || v0 == C_SQ) {
                int end = text.indexOf(v0, q + 1);
                if (end < 0) return null;
                String v = text.substring(q + 1, end).trim();
                if (!v.isEmpty() && !v.equals(key)) return v;
            } else {
                int e = q;
                while (e < text.length()) {
                    char d = text.charAt(e);
                    if (d == ',' || d == '\uFF0C' || d == '\n' || d == '\r' || d == '}') break;
                    e++;
                }
                String v = text.substring(q, e).trim();
                if (!v.isEmpty() && !v.equals(key)) return v;
            }
        }
    }

    /**
     * 从模型输出里抽出 JSON 对象。
     * 必须容错：模型常把结果包在代码块里，或前后带一句说明。
     */
    static JSONObject extractJsonObject(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
            t = t.trim();
        }
        try { return new JSONObject(t); } catch (Exception ignored) {}
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try { return new JSONObject(t.substring(a, b + 1)); } catch (Exception ignored) {}
        }
        return null;
    }

    /** 清理译名：去掉包裹引号、前缀符号、过长结果。 */
    static String cleanup(String v) {
        String s = v.trim();
        while (s.length() > 1
                && ((s.startsWith("\"") && s.endsWith("\""))
                    || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        s = s.replaceAll("^[\\s\\u2605\\u2606\\u25CF\\u25CB\\u25C6\\u25A0\\u25A1\\u25AA\\u25AB]+", "").trim();
        /* ★ 原来截到 16 字符，英文长译名会被切成"Start Running (1"这种半截话，
           看着像乱码。放宽到 40（中文 40 字足够，英文也能留整句）。 */
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    /** 日志里截断超长文本（避免刷爆 logcat）。 */
    static String shortOf(String s) {        if (s == null) return "null";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 300 ? (t.substring(0, 300) + "…") : t;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
