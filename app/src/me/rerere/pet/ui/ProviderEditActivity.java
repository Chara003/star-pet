package me.rerere.pet.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.theme.UiTheme;

/**
 * AI 提供商编辑页（新实现，不再跳转旧 MainActivity）。
 *
 * 复刻旧 MainActivity.showProviderEditor() 的全部行为，但换成新视觉与单向数据流：
 *   · 名称 / API Key / Base Url / API 路径 / 模型
 *   · 「从服务端拉取模型列表」——需要先填 Base Url + API Key
 *   · 逐项校验并明确指出缺哪个字段
 *   · 新增时自动设为当前提供商（与旧逻辑一致）
 *
 * ★★ 一个必须保留的坑位（旧代码注释原文）★★
 *   保存必须用 store.saveProvider(p)，**不能**用 saveProviders(store.providers())：
 *   providers() 每次返回的是一批**新建的对象**，把"旧的那一批"写回去
 *   等于把用户刚改的内容丢掉 —— 这正是历史上"改什么都存不上"的根因。
 *
 * ★ 为什么不用 AlertDialog：
 *   旧实现是在对话框里塞 ScrollView 并限制 70% 高度，键盘弹出后字段会被裁掉。
 *   这里改成独立 Activity + 系统滚动，输入体验好得多。
 */
public final class ProviderEditActivity extends Activity {

    public static final String EXTRA_ID = "id";

    private PetStore store;
    /** null = 新增。 */
    private PetStore.Provider editing;
    private boolean isNew;

    private EditText2f name, apiKey, baseUrl, apiPath, model;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new PetStore(this);

        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        if (id != null && !id.isEmpty()) {
            for (PetStore.Provider p : store.providers()) {
                if (id.equals(p.id)) { editing = p; break; }
            }
        }
        isNew = (editing == null);

        SettingsPage page = new SettingsPage(this);
        page.pageTitle(isNew ? "添加服务商" : "编辑服务商",
                "支持任何 OpenAI 兼容接口。填写 Base Url 与 API Key 后可以自动拉取模型列表。");

        page.section("基本信息");
        name = field(page, "名称", isNew ? "" : editing.name, false,
                "给自己看的标签，例如 OpenRouter");
        baseUrl = field(page, "API Base Url",
                isNew ? "https://openrouter.ai/api/v1" : editing.baseUrl, false,
                "例：https://api.openai.com/v1");
        apiKey = field(page, "API Key", isNew ? "" : editing.apiKey, true,
                "只存在本机，不会上传");
        apiPath = field(page, "API 路径",
                isNew ? "/chat/completions" : editing.chatPath, false,
                "默认 /chat/completions，一般不用改");

        page.section("模型");
        model = field(page, "模型", isNew ? "" : editing.model, false,
                "可以先留空，点下面的按钮自动拉取");

        page.nav("从服务端拉取模型列表",
                "需要先填好 Base Url 与 API Key，否则会失败",
                () -> fetchModels());

        /* ---------- 底部按钮 ---------- */
        page.actions("保存", this::save, "取消", this::finish);

        if (!isNew) {
            page.section("危险操作");
            page.nav("删除这个服务商", "删除后需要重新填写 Key 与模型",
                    this::confirmDelete);
        }

        setContentView(page.build());
    }

    /* ---------------- 表单控件 ---------------- */

    /** 带标签 + 浅灰圆角底 + 提示文字的输入行。 */
    private EditText2f field(SettingsPage page, String label, String value,
                             boolean password, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        int pv = UiTheme.dp(this, UiTheme.PAD_V);
        box.setPadding(ph, pv, ph, pv);

        box.addView(UiTheme.text(this, label, 14f, UiTheme.TEXT_PRIMARY, true));

        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextSize(15f);
        e.setTextColor(UiTheme.TEXT_PRIMARY);
        e.setHintTextColor(0xFFB0B0B8);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        if (password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        e.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
        int eh = UiTheme.dp(this, 12f), ev = UiTheme.dp(this, 11f);
        e.setPadding(eh, ev, eh, ev);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = UiTheme.dp(this, 7f);
        box.addView(e, elp);

        page.custom(box);
        return new EditText2f(e);
    }

    /** 薄包装，省得每处都写 .view。 */
    private static final class EditText2f {
        final android.widget.EditText view;
        EditText2f(android.widget.EditText v) { view = v; }
        String text() {
            return view.getText() == null ? "" : view.getText().toString().trim();
        }
    }

    /* ---------------- 拉取模型 ---------------- */

    private void fetchModels() {
        String url = baseUrl.text();
        String key = apiKey.text();
        if (url.isEmpty()) { toast("请先填写 API Base Url"); return; }
        if (key.isEmpty()) { toast("请先填写 API Key"); return; }
        toast("正在拉取模型列表…");

        final PetStore.Provider probe = new PetStore.Provider("probe", "probe",
                url, key, model.text());
        new Thread(() -> {
            java.util.List<String> got = null;
            String err = null;
            try {
                got = me.rerere.pet.data.PetAiClient.fetchModels(probe);
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final java.util.List<String> models = got;
            final String error = err;
            runOnUiThread(() -> {
                if (models == null || models.isEmpty()) {
                    toast("没有拿到模型列表" + (error == null ? "" : "：" + error));
                    return;
                }
                showModelPicker(models);
            });
        }).start();
    }

    /**
     * 模型列表选择器。
     *
     * ★ 必须带搜索框：服务端常常返回几百个模型（OpenRouter 上千），
     *   纯列表滚起来根本找不到（旧实现也是带搜索的）。
     * ★ 用 Activity 的 Context 弹窗；applicationContext 会 BadTokenException。
     */
    private void showModelPicker(final java.util.List<String> models) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiTheme.dp(this, 16f);
        box.setPadding(pad, UiTheme.dp(this, 8f), pad, pad);

        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("搜索模型名…");
        search.setSingleLine(true);
        search.setTextSize(15f);
        search.setBackground(UiTheme.round(this, UiTheme.INFO_BG, 12f));
        int ih = UiTheme.dp(this, 12f), iv = UiTheme.dp(this, 10f);
        search.setPadding(ih, iv, ih, iv);
        box.addView(search);

        android.widget.ListView list = new android.widget.ListView(this);
        final java.util.List<String> filtered = new java.util.ArrayList<>(models);
        final android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, filtered);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = UiTheme.dp(this, 8f);
        box.addView(list, llp);

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("选择模型（共 " + models.size() + " 个）")
                .setView(box)
                .setNegativeButton("取消", null)
                .create();

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase();
                filtered.clear();
                for (String m : models) {
                    if (q.isEmpty() || m.toLowerCase().contains(q)) filtered.add(m);
                }
                adapter.notifyDataSetChanged();
            }
        });

        list.setOnItemClickListener((parent, view, pos, id) -> {
            String picked = filtered.get(pos);
            model.view.setText(picked);
            toast("已选择：" + picked);
            dialog.dismiss();
        });

        dialog.show();
        /* 高度限制在 65% 屏高，否则键盘一弹列表就没地方了 */
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.65f));
        }
    }

    /* ---------------- 保存 / 删除 ---------------- */

    private void save() {
        String n = name.text();
        String u = baseUrl.text();
        String k = apiKey.text();
        String m = model.text();
        String path = apiPath.text();

        /* 逐项校验，明确指出缺哪个字段（照抄旧实现的行为） */
        if (n.isEmpty()) { toast("请填写名称"); return; }
        if (u.isEmpty()) { toast("请填写 API Base Url"); return; }
        if (m.isEmpty()) { toast("请填写模型，或点「从服务端拉取模型列表」"); return; }
        if (path.isEmpty()) path = "/chat/completions";

        PetStore.Provider p = isNew
                ? new PetStore.Provider("p" + System.currentTimeMillis(), n, u, k, m)
                : editing;
        p.name = n;
        p.baseUrl = u;
        p.apiKey = k;
        p.model = m;
        p.chatPath = path;

        /* ★ 必须用 saveProvider（单对象）：见类注释里那个"改什么都存不上"的坑 */
        store.saveProvider(p);
        /* ★ 新增的提供商【默认设为当前】：用户刚填完 Key 和模型，
           下一步必然是想用它对话。旧实现只在"原来没有当前提供商"时才设置，
           于是新建完之后还得再回列表手动点一下才生效
           （实测"点保存之后不会选中"）。
           编辑已有项时不动当前选择 —— 那会打断用户正在用的那一个。 */
        if (isNew || store.activeProvider() == null) {
            store.setActiveProvider(p.id);
        }

        toast("已保存");
        finish();
    }

    private void confirmDelete() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除服务商")
                .setMessage("确定删除“" + (editing.name == null ? editing.id : editing.name)
                        + "”？这个操作不可撤销。")
                .setPositiveButton("删除", (d, w) -> {
                    java.util.List<PetStore.Provider> all = store.providers();
                    java.util.List<PetStore.Provider> keep = new java.util.ArrayList<>();
                    for (PetStore.Provider p : all) {
                        if (!p.id.equals(editing.id)) keep.add(p);
                    }
                    store.saveProviders(keep);
                    PetStore.Provider act = store.activeProvider();
                    if (act != null && editing.id.equals(act.id)) {
                        store.setActiveProvider(keep.isEmpty() ? "" : keep.get(0).id);
                    }
                    toast("已删除");
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
