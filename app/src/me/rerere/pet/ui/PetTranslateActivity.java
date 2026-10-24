package me.rerere.pet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.rerere.pet.data.PetOverlayServiceBridge;
import me.rerere.pet.data.PetStore;
import me.rerere.pet.data.TranslationService;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.ChoiceRowView;
import me.rerere.pet.ui.widget.TextRowView;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * 名称翻译设置。
 *
 * 配件/表情名都是日文（きつね耳 / ★胸上げ / まばたき），看不懂。
 * 开启翻译后，界面显示「中文(原名)」，但【存储与 AI 调用仍用原名】——
 * 只改 UI，不动任何实际数据。
 *
 * 内置两家：
 *  · 硅基流动 —— OpenAI 兼容，能理解 MMD 术语，模型默认 Qwen2.5-7B
 *  · 微软翻译 —— 免费额度大、返回与请求精确对应
 * 译文会缓存到本地，已有译文不会重复翻译；每次启动只补"没翻译过的"。
 *
 * ── 本轮重构（UI 第二轮，逐字保留原有行为）──
 *   ★ 保留「翻译进度实时刷新」：翻译进行中每 1.2s 刷新状态行与缓存条数，
 *     离开页面（onPause）立刻停轮询 —— 旧版没有这个，用户看不到进度。
 *   ★ 保留「复用某个 AI 提供商」的自定义模式：字段显示的是借来的只读值，
 *     保存时不能回写（否则会留下陈旧配置）。
 *   ★ 保留测试翻译的完整过程日志（TranslationService.lastDiagnostic()），
 *     翻不出时能看出卡在哪一步。
 *   视觉上：服务商从"三段自绘按钮"换成 ChoiceRowView，微软全球/中国版
 *   从"一行可点文字"换成两段式选择（填错区域会 401，不该要求用户记住）。
 */
public final class PetTranslateActivity extends Activity {

    private PetStore store;
    private PetStore.TranslateConfig cfg;

    private static final String[] IDS = {"siliconflow", "microsoft", "custom"};
    private static final String[] NAMES = {"硅基流动", "微软翻译", "自定义"};

    private ToggleRowView enableRow;
    private TextView cacheView;
    private ChoiceRowView providerRow;
    private TextRowView keyRow, modelRow, endpointRow, regionRow, promptRow;
    private ChoiceRowView msRegionRow;
    private TextView applyHint;
    private TextView statusView;

    /** 翻译进行中时轮询，保证"已缓存 N 条"实时变化。 */
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int pollTicks = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PetStore(this);
        cfg = store.translateConfig();

        SettingsPage page = new SettingsPage(this);
        page.pageTitle("名称翻译",
                "把日文的配件/表情名翻成中文显示，方便辨认。"
                        + "只影响界面显示，不会修改任何实际数据"
                        + "（模型内部名字、AI 调用都用原文）。译文会缓存，翻过的不再重复翻译。");

        /* ---------------- 开关 ---------------- */
        page.section("名称翻译");

        enableRow = page.toggle("启用名称翻译",
                "开启后立刻开始补翻没有译文的名称，不用重启桌宠",
                cfg.enabled,
                (row, on) -> {
                    cfg.enabled = on;
                    store.saveTranslateConfig(cfg);
                    /* ★ 开启/关闭都立刻生效：
                       开启 → 马上触发翻译（不用等重启桌宠）
                       关闭 → 服务会清掉"翻译中"标记 */
                    PetOverlayServiceBridge.requestTranslate(false);
                    renderState();
                    startPolling();
                });

        cacheView = page.valueRow("已缓存译文", store.translations().size() + " 条");

        /* ---------------- 服务商 ---------------- */
        page.section("服务商");

        providerRow = page.choice("使用哪家翻译",
                "自定义 = 借用你已经配好的某个 AI 提供商（OpenAI 兼容），"
                        + "也可以手动填端点",
                NAMES, indexOf(IDS, cfg.providerId), 3,
                (row, index) -> onProviderSelected(index));

        /* ---------------- 凭证与参数 ---------------- */
        page.section("凭证与参数");

        applyHint = page.info("");

        keyRow = textField(page, "API Key", null, cfg.apiKey(), true);
        modelRow = textField(page, "模型（OpenAI 兼容）", null, cfg.model, true);
        endpointRow = textField(page, "端点 / API Base Url", null, cfg.endpoint(), true);
        regionRow = textField(page, "微软区域（如 eastasia）", null, cfg.region, true);
        promptRow = textField(page, "自定义翻译提示词（留空用默认）",
                "会替换内置的翻译风格/术语说明；输出必须是 JSON 的格式要求会自动保留",
                cfg.customPrompt, false);

        msRegionRow = new ChoiceRowView(this);
        msRegionRow.setTitle("微软端点版本")
                .setSubtitle("两版区域不同，填错会 401")
                .setPerLine(2)
                .setOptions(new String[]{"全球版 (eastasia)", "中国版 (chinaeast2)"})
                .setSelectedIndex(isMsCn() ? 1 : 0)
                .setOnSelectListener((row, index) -> switchMsEndpoint(index == 1));
        page.custom(msRegionRow, false);

        page.nav("获取 API Key / 申请页面", applyUrlLabel(),
                () -> openUrl(applyUrl(), applyHint));

        /* ---------------- 运行状态 ---------------- */
        page.section("运行状态");
        statusView = page.info("");

        /* ---------------- 操作 ---------------- */
        page.actions(new String[]{"保存并立即翻译", "测试翻译", "清除全部翻译"},
                new Runnable[]{() -> saveAll(true), this::doTest, this::confirmClear});

        setContentView(page.build());
        loadFields();
        renderState();
        startPolling();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    /* ================= 渲染 ================= */

    /**
     * 按当前配置刷新整页状态。
     * ★ 只改渲染，不写盘；字段内容由 loadFields() 负责（会覆盖用户输入，
     *   所以只在初始化 / 切换服务商 / 保存后调用）。
     */
    private void renderState() {
        boolean on = cfg.enabled;
        enableRow.setChecked(on);

        boolean translating = PetOverlayServiceBridge.isTranslating();
        cacheView.setText(store.translations().size() + " 条"
                + (translating ? "  ·  ⟳ 翻译中…" : ""));

        providerRow.setSelectedIndex(indexOf(IDS, cfg.providerId));
        providerRow.setEnabled(true);

        /* 关掉总开关时，下面的参数全部变灰（保留可见，用户能看到都配了什么） */
        keyRow.setEnabled(on);
        modelRow.setEnabled(on);
        endpointRow.setEnabled(on);
        regionRow.setEnabled(on);
        promptRow.setEnabled(on);
        msRegionRow.setEnabled(on);

        applyHint.setText(providerHint());

        if (statusView.getText() == null || statusView.getText().length() == 0) {
            statusView.setText(BridgeStatus.initialHint(cfg));
        }
    }

    /** 翻译进行中每 1.2s 刷新一次（有上限，避免服务异常时无限轮询）。 */
    private void startPolling() {
        handler.removeCallbacksAndMessages(null);
        pollTicks = 0;
        step();
    }

    private void step() {
        if (isFinishing() || isDestroyed()) return;
        boolean translating = PetOverlayServiceBridge.isTranslating();
        cacheView.setText(store.translations().size() + " 条"
                + (translating ? "  ·  ⟳ 翻译中…" : ""));

        /* 翻译中：一直刷；结束后再刷几次（服务落库有一点延迟）然后就停。 */
        if (!translating && pollTicks > 3) return;
        pollTicks++;
        handler.postDelayed(this::step, 1200);
    }

    private String providerHint() {
        String p = cfg.providerId;
        if ("microsoft".equals(p)) {
            return "微软翻译：在 Azure 创建「翻译工具」资源后，把「密钥」和「区域」填到下面。"
                    + "免费层 F0 每月 200 万字符。";
        }
        if ("custom".equals(p)) {
            boolean reuse = cfg.aiProviderId != null && !cfg.aiProviderId.isEmpty();
            return reuse
                    ? ("当前复用：" + customSummary()
                       + "（下面的 Key / 端点 / 模型是只读展示，保存不会写回自定义槽位）")
                    : "手动填写：任意 OpenAI 兼容的 /chat/completions 服务（Key 可留空，"
                      + "适用于本地/内网服务）。";
        }
        return "硅基流动：注册后在「API 密钥」里新建。有免费额度。"
                + "翻译结果不理想可以把模型换成更大的（如 Qwen/Qwen2.5-72B-Instruct）。";
    }

    /* ================= 服务商 ================= */

    private void onProviderSelected(int index) {
        if (index < 0 || index >= IDS.length) return;

        /* ★ 自定义：用和「添加提供商」一致的对话框界面
           （先选一个已配置的 AI 提供商，或手动填写端点）。 */
        if ("custom".equals(IDS[index])) {
            showCustomDialog();
            return;
        }
        cfg.providerId = IDS[index];
        if (!"microsoft".equals(cfg.providerId)) {
            /* 从微软切走时不要留着微软端点，否则会被当成自定义端点发给别家 */
            if (isMsEndpoint(cfg.endpoint())) cfg.setEndpoint("");
        }
        store.saveTranslateConfig(cfg);
        loadFields();
        renderState();
    }

    private boolean isMsEndpoint(String url) {
        if (url == null) return false;
        return url.trim().equalsIgnoreCase(TranslationService.MS_ENDPOINT_CN)
                || url.trim().equalsIgnoreCase(TranslationService.MS_ENDPOINT_GLOBAL);
    }

    private boolean isMsCn() {
        String e = cfg.endpoint() == null ? "" : cfg.endpoint().trim();
        return TranslationService.MS_ENDPOINT_CN.equalsIgnoreCase(e);
    }

    private void switchMsEndpoint(boolean cn) {
        cfg.setEndpoint(cn ? TranslationService.MS_ENDPOINT_CN
                : TranslationService.MS_ENDPOINT_GLOBAL);
        cfg.region = cn ? "chinaeast2" : "eastasia";
        endpointRow.setText(cfg.endpoint());
        regionRow.setText(cfg.region);
        saveAll(false);
        toast(cn ? "已切到中国版端点" : "已切到全球版端点");
    }

    /**
     * 自定义翻译服务 —— ★ 主路径是「直接选一个已配置的 AI 提供商」，
     * 沿用它的 baseUrl / Key / 模型 / 路径，不必重填（和「添加提供商」同一套数据）。
     * 需要单独指定端点时可进「手动填写」。
     */
    private void showCustomDialog() {
        List<PetStore.Provider> all = store.providers();
        final List<String> labels = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        for (PetStore.Provider p : all) {
            labels.add(p.name + "  ·  " + (p.model == null ? "" : p.model));
            ids.add(p.id);
        }
        labels.add("手动填写端点…");
        ids.add("");

        int cur = ids.indexOf(cfg.aiProviderId);
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择用于翻译的 AI 提供商")
                .setSingleChoiceItems(labels.toArray(new String[0]),
                        cur < 0 ? labels.size() - 1 : cur,
                        (d, which) -> {
                            String id = ids.get(which);
                            d.dismiss();
                            if (id.isEmpty()) {
                                showManualDialog();
                            } else {
                                cfg.providerId = "custom";
                                cfg.aiProviderId = id;
                                store.saveTranslateConfig(cfg);
                                loadFields();
                                renderState();
                                toast("已选用该提供商翻译");
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 手动填写端点（界面与「添加提供商」保持一致：
     * 顶部灰色提示 + 虚线下划线输入框 + 限高 ScrollView）。
     */
    private void showManualDialog() {
        final LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));

        TextView notice = new TextView(this);
        notice.setText("目前仅支持 OpenAI 格式（Key 可留空，适用于本地/内网服务）");
        notice.setTextSize(12);
        notice.setTextColor(0xFF9A9AA2);
        notice.setPadding(dp(4), dp(4), dp(4), dp(10));
        form.addView(notice);

        final EditText apiKey = field(form, "API Key（可留空）", cfg.apiKey(), true);
        final EditText baseUrl = field(form, "API Base Url",
                cfg.endpoint().isEmpty() ? "https://your-host/v1" : cfg.endpoint(), false);
        final EditText model = field(form, "模型", cfg.model, false);
        final EditText prompt = field(form, "自定义翻译提示词（留空用默认）",
                cfg.customPrompt, false);
        prompt.setSingleLine(false);
        prompt.setMinLines(3);
        prompt.setGravity(Gravity.TOP | Gravity.START);

        TextView pvHint = new TextView(this);
        pvHint.setTextSize(12);
        pvHint.setTextColor(0xFF9A9AA2);
        pvHint.setPadding(dp(4), 0, dp(4), dp(8));
        pvHint.setText("这段会替换内置的翻译风格/术语说明。\n"
                + "输出必须是 JSON 的格式要求会自动保留，不用担心写坏。");
        form.addView(pvHint);

        android.widget.ScrollView formScroll = new android.widget.ScrollView(this);
        formScroll.setFillViewport(true);
        formScroll.addView(form, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("自定义翻译服务")
                .setView(formScroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setOnShowListener(d -> {
            int maxFormH = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
            formScroll.getLayoutParams().height = maxFormH;
            formScroll.requestLayout();

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String b = baseUrl.getText().toString().trim();
                    String m = model.getText().toString().trim();
                    if (b.isEmpty()) { toast("请填写 API Base Url"); return; }
                    if (m.isEmpty()) { toast("请填写模型"); return; }
                    cfg.providerId = "custom";
                    cfg.aiProviderId = "";          // ✓ 手动模式：不再复用提供商
                    cfg.setApiKey(apiKey.getText().toString().trim());
                    cfg.setEndpoint(b);
                    cfg.model = m;
                    cfg.customPrompt = prompt.getText().toString().trim();
                    store.saveTranslateConfig(cfg);
                    loadFields();
                    renderState();
                    toast("已保存自定义服务");
                    dialog.dismiss();
                });
        });
        dialog.show();
    }

    /** 带虚线下划线标签的输入框（对话框内款式，与「添加提供商」一致）。 */
    private EditText field(LinearLayout parent, String label, String value, boolean isPassword) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(6), 0, dp(6));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(0xFF9A9AA2);
        group.addView(t);

        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(0, dp(6), 0, dp(6));
        if (isPassword) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        group.addView(e);

        View line = new View(this);
        line.setBackgroundColor(0xFFDDDDE3);
        group.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        parent.addView(group);
        return e;
    }

    /* ================= 字段读写 ================= */

    /** 加一个"无分割线"的输入行（输入框之间用留白分隔，不画线更干净）。 */
    private TextRowView textField(SettingsPage page, String title, String subtitle,
                                  String value, boolean singleLine) {
        TextRowView row = new TextRowView(this);
        row.setTitle(title).setSubtitle(subtitle).setText(value);
        if (singleLine) row.setSingleLine(true); else row.setMinLines(3);
        page.custom(row, false);
        return row;
    }

    private void loadFields() {
        String p = cfg.providerId;
        boolean ms = "microsoft".equals(p);
        boolean custom = "custom".equals(p);
        boolean reuse = custom && cfg.aiProviderId != null && !cfg.aiProviderId.isEmpty();

        /* ★ 复用提供商时：把那家的实际设置显示出来（只读），
           让用户清楚"现在用的是谁的端点/模型"，而不是一堆空字段。 */
        if (reuse) {
            PetStore.Provider rp = null;
            for (PetStore.Provider x : store.providers()) {
                if (x != null && cfg.aiProviderId.equals(x.id)) { rp = x; break; }
            }
            if (rp != null) {
                keyRow.setText(rp.apiKey == null ? "" : rp.apiKey);
                endpointRow.setText(rp.baseUrl == null ? "" : rp.baseUrl);
                modelRow.setText(rp.model == null ? "" : rp.model);
                regionRow.setText(cfg.region);
                promptRow.setText(cfg.customPrompt == null ? "" : cfg.customPrompt);

                setRowVisible(keyRow, true);
                setRowVisible(modelRow, true);
                setRowVisible(endpointRow, true);
                setRowVisible(regionRow, false);
                setRowVisible(promptRow, true);
                setRowVisible(msRegionRow, false);

                /* 只读：这几个值属于那个提供商，不应该在这里改 */
                keyRow.setEnabled(false);
                modelRow.setEnabled(false);
                endpointRow.setEnabled(false);
                return;
            }
        }

        keyRow.setText(cfg.apiKey());
        modelRow.setText(cfg.model);
        endpointRow.setText(cfg.endpoint());
        regionRow.setText(cfg.region);
        promptRow.setText(cfg.customPrompt == null ? "" : cfg.customPrompt);

        /* 各家需要的字段不同：
           微软 = Key + 区域 + 端点（全球版/中国版地址不同）
           OpenAI 兼容 = Key + 模型；自定义还要端点 */
        setRowVisible(keyRow, true);
        setRowVisible(modelRow, true);
        setRowVisible(endpointRow, ms || custom);
        setRowVisible(regionRow, ms);
        setRowVisible(promptRow, true);
        setRowVisible(msRegionRow, ms);
        if (ms) msRegionRow.setSelectedIndex(isMsCn() ? 1 : 0);
    }

    private void setRowVisible(View row, boolean v) {
        row.setVisibility(v ? View.VISIBLE : View.GONE);
    }

    /** 自定义按钮上的副标题：显示当前选的是哪个提供商。 */
    private String customSummary() {
        if (cfg.aiProviderId != null && !cfg.aiProviderId.isEmpty()) {
            for (PetStore.Provider x : store.providers()) {
                if (x != null && cfg.aiProviderId.equals(x.id)) {
                    return "用 " + x.name;
                }
            }
            return "提供商已失效";
        }
        return "手动填写";
    }

    /* ================= 保存 / 测试 / 清除 ================= */

    private void saveAll(boolean showToast) {
        /* ★ 复用提供商时不要回写那几个字段！
           界面上显示的 Key/端点/模型是【借来展示】的，
           回写进 custom 槽会在换提供商后留下陈旧值，反而出错。
           复用模式下只需要保存 customPrompt 和 region。 */
        boolean reuse = "custom".equals(cfg.providerId)
                && cfg.aiProviderId != null && !cfg.aiProviderId.isEmpty();
        if (!reuse) {
            cfg.setApiKey(keyRow.getUserInput().trim());
            cfg.model = modelRow.getUserInput().trim();
            cfg.setEndpoint(endpointRow.getUserInput().trim());
            if (cfg.model == null || cfg.model.isEmpty()) cfg.model = "Qwen/Qwen2.5-7B-Instruct";
        }
        cfg.customPrompt = promptRow.getUserInput().trim();
        cfg.region = regionRow.getUserInput().trim();
        store.saveTranslateConfig(cfg);

        /* ★ 保存后立刻触发一次翻译 ——
           否则用户开了开关也得等下次桌宠重启才会翻（这正是"没有自动翻译"的原因）。 */
        PetOverlayServiceBridge.requestTranslate(false);
        renderState();
        startPolling();
        if (showToast) toast("已保存");
    }

    /**
     * 清除全部翻译缓存。
     * ★ 二次确认 —— 清掉后下次启动会重新联网翻译全部名称（要花额度）。
     */
    private void confirmClear() {
        final int cnt = store.translationCount();
        new android.app.AlertDialog.Builder(this)
                .setTitle("清除全部翻译？")
                .setMessage("将删除已缓存的 " + cnt + " 条译文。\n"
                        + "下次打开桌宠时会重新联网翻译所有配件/表情名"
                        + "（会消耗翻译额度，也可能因换服务商而得到不同译名）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (d, w) -> {
                    store.clearTranslations();
                    statusView.setText("已清除 " + cnt + " 条译文。重启桌宠后会重新翻译。");
                    renderState();
                    toast("已清除 " + cnt + " 条译文");
                }).show();
    }

    private void doTest() {
        saveAll(false);
        /* ★ 用 isUsable() 判断，别单独查 Key ——
           复用提供商时那个服务本来就可能不需要 Key（本地 Ollama / 内网网关），
           硬查 apiKey() 会误报"请先填 API Key"。 */
        if (!cfg.isUsable()) {
            boolean reuse = cfg.aiProviderId != null && !cfg.aiProviderId.isEmpty();
            statusView.setText(reuse
                    ? "复用的提供商配置不完整（请检查它的 BaseUrl / 模型）"
                    : "请先填写必要配置（Base Url / 模型，部分服务还需要 Key）");
            return;
        }
        statusView.setText("翻译中…");
        final List<String> sample = Arrays.asList(
                "きつね耳", "★胸上げ", "まばたき", "OFF_シャツ", "ネクタイ");
        new Thread(() -> {
            java.util.Map<String, String> r =
                    TranslationService.translate(store, cfg, sample);
            StringBuilder sb = new StringBuilder();
            sb.append("成功 ").append(r.size()).append(" / ").append(sample.size()).append(" 条\n");
            for (String k : sample) {
                sb.append(k).append(" → ").append(r.containsKey(k) ? r.get(k) : "（未翻出）").append('\n');
            }
            if (!r.isEmpty()) store.mergeTranslations(r);   // 顺手进缓存

            /* ★★ 翻不出时直接把过程日志显示出来 ★★
               否则只看到"（未翻出）"，无从判断是哪一步卡住
               （没开开关 / 提供商失效 / HTTP 错误 / 模型没返回正文…）。 */
            String diag = TranslationService.lastDiagnostic();
            if (diag != null && !diag.isEmpty()) {
                sb.append("\n──────── 过程日志 ────────\n").append(diag);
            }
            final String text = sb.toString().trim();
            android.util.Log.i("PetTranslate", "测试结果:\n" + text);
            runOnUiThread(() -> {
                statusView.setText(text);
                statusView.setTextIsSelectable(true);   // 方便复制出去反馈
                renderState();
            });
        }, "pet-trans-test").start();
    }

    /* ================= 小工具 ================= */

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 当前服务商的申请页面。 */
    private String applyUrl() {
        switch (cfg.providerId) {
            case "microsoft":
                /* 微软翻译：在 Azure 创建「翻译工具」资源，拿 Key + 区域。
                   有免费层（F0，每月 200 万字符）。 */
                return "https://portal.azure.com/#create/Microsoft.CognitiveServicesTextTranslation";
            case "custom":
                return "";
            default:
                return "https://cloud.siliconflow.cn/account/ak";
        }
    }

    private String applyUrlLabel() {
        switch (cfg.providerId) {
            case "microsoft":
                return " Azure 创建页 → 创建「翻译工具」→ 复制「密钥」和「区域」";
            case "custom":
                return "自定义端点：填自己的 OpenAI 兼容服务地址、模型与 Key";
            default:
                return " 密钥页面 → 注册后在「API 密钥」里新建";
        }
    }

    /** 打开浏览器；没有可打开的应用时退化为把网址显示出来。 */
    private void openUrl(String url, TextView hintView) {
        if (url == null || url.isEmpty()) {
            if (hintView != null) hintView.setText("自定义服务没有固定的申请页面，填写你自己的端点即可。");
            return;
        }
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable t) {
            /* 没有浏览器时至少让用户能看到网址 */
            if (hintView != null) hintView.setText("申请页面：\n" + url);
        }
    }

    /** 初始状态文案（新页面打开时给一句人话，而不是空白）。 */
    private static final class BridgeStatus {
        static String initialHint(PetStore.TranslateConfig cfg) {
            if (!cfg.enabled) return "名称翻译已关闭。开启后会自动补翻没有译文的名称。";
            if (!cfg.isUsable()) return "配置不完整（Base Url / 模型），暂时无法翻译。";
            return "准备就绪。首次翻译会在后台进行，进度可在上方「已缓存译文」看到。";
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
