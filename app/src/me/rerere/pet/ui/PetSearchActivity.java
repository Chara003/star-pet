package me.rerere.pet.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import me.rerere.pet.data.SearchProvider;
import me.rerere.pet.data.PetStore;

/**
 * 联网搜索设置：选择服务商、填写 API Key、测试连接。
 * Key 可为空——部分服务商无需密钥。
 */
public final class PetSearchActivity extends Activity {

    private PetStore store;
    private SearchProvider.Config config;

    private LinearLayout providerList;
    private LinearLayout keySection;
    private EditText keyField;
    private EditText endpointField;
    private TextView hintView;
    private TextView statusView;
    private TextView keyLabel;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PetStore(this);
        config = store.searchConfig();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF8F7FC);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("联网搜索");
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1B1B1F);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("选择搜索服务。未标注「需密钥」的服务可直接使用。");
        sub.setTextSize(13);
        sub.setTextColor(0xFF8A8A93);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // 服务商列表
        root.addView(sectionTitle("搜索服务"));
        providerList = new LinearLayout(this);
        providerList.setOrientation(LinearLayout.VERTICAL);
        root.addView(providerList);

        // 配置区
        keySection = new LinearLayout(this);
        keySection.setOrientation(LinearLayout.VERTICAL);
        buildKeySection(keySection);
        root.addView(keySection);

        // 结果条数
        root.addView(sectionTitle("结果数量"));
        LinearLayout countCard = card();
        final TextView countLabel = new TextView(this);
        countLabel.setTextSize(15);
        countLabel.setTextColor(0xFF1B1B1F);
        countLabel.setText("返回 " + config.resultCount + " 条");
        countLabel.setPadding(dp(2), dp(4), dp(2), dp(4));
        countCard.addView(countLabel);
        SeekBar seek = new SeekBar(this);
        seek.setMax(14); // 1~15
        seek.setProgress(Math.max(0, Math.min(14, config.resultCount - 1)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                int n = v + 1;
                countLabel.setText("返回 " + n + " 条");
                config.resultCount = n;
                store.saveSearchConfig(config);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        countCard.addView(seek);
        root.addView(countCard);

        // 测试
        Button test = new Button(this);
        test.setText("测试搜索");
        test.setTextSize(15);
        test.setTextColor(0xFFFFFFFF);
        test.setBackground(solid(0xFF2F7E7E, dp(22)));
        test.setOnClickListener(v -> runTest());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(20);
        root.addView(test, tlp);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(0xFF6B6B74);
        statusView.setPadding(dp(2), dp(12), dp(2), dp(2));
        root.addView(statusView);

        renderProviders();
        // 回填已保存的配置（此前漏了这一步，导致重进页面时 Key 显示为空）
        refreshKeySection();
        setContentView(scroll);
    }

    /** 渲染服务商选项。 */
    private void renderProviders() {
        providerList.removeAllViews();
        List<SearchProvider.Meta> all = SearchProvider.ALL;
        for (final SearchProvider.Meta m : all) {
            boolean active = m.id.equals(config.providerId);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(solid(0xFFFFFFFF, dp(16)));
            card.setBackground(glass(dp(16), 0xFFFFFFFF,
                    active ? 0x554C6EF5 : 0x14000000));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            card.setLayoutParams(lp);

            LinearLayout headRow = new LinearLayout(this);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(this);
            name.setText((active ? "✓ " : "") + m.label);
            name.setTextSize(15);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setTextColor(active ? 0xFF4C6EF5 : 0xFF1B1B1F);
            headRow.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView badge = new TextView(this);
            badge.setText(m.keyRequired ? "需密钥" : "免密钥");
            badge.setTextSize(11);
            badge.setTextColor(m.keyRequired ? 0xFFB26A00 : 0xFF2E7D32);
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            badge.setBackground(solid(m.keyRequired ? 0xFFFFF3E0 : 0xFFDDF3DC, dp(8)));
            headRow.addView(badge);
            card.addView(headRow);

            TextView note = new TextView(this);
            note.setText(m.note);
            note.setTextSize(12);
            note.setTextColor(0xFF8A8A93);
            note.setPadding(0, dp(4), 0, 0);
            card.addView(note);

            card.setOnClickListener(v -> {
                // 先把当前输入框内容收进 config，避免切换时丢失未保存的编辑
                if (keyField != null) {
                    config.setApiKey(keyField.getText().toString().trim());
                    config.setEndpoint(endpointField.getText().toString().trim());
                }
                if (!config.apiKey().isEmpty()) {
                    // 记住该服务商已有 Key
                }
                config.providerId = m.id;
                store.saveSearchConfig(config);
                renderProviders();
                refreshKeySection();
            });
            providerList.addView(card);
        }
    }

    /** Key / 地址输入区。 */
    private void buildKeySection(LinearLayout parent) {
        parent.addView(sectionTitle("配置"));
        LinearLayout card = card();

        hintView = new TextView(this);
        hintView.setTextSize(12);
        hintView.setTextColor(0xFF8A8A93);
        hintView.setPadding(dp(2), dp(2), dp(2), dp(8));
        card.addView(hintView);

        keyLabel = new TextView(this);
        keyLabel.setText("API Key");
        keyLabel.setTextSize(14);
        keyLabel.setTextColor(0xFF1B1B1F);
        keyLabel.setPadding(dp(2), dp(6), dp(2), dp(4));
        card.addView(keyLabel);

        keyField = new EditText(this);
        keyField.setTextSize(14);
        keyField.setSingleLine(true);
        keyField.setHint("可留空（免密钥服务）");
        keyField.setBackground(boxBackground());
        keyField.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(keyField);

        endpointField = new EditText(this);
        endpointField.setTextSize(14);
        endpointField.setSingleLine(true);
        endpointField.setHint("实例地址，如 https://searx.be");
        endpointField.setBackground(boxBackground());
        endpointField.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(endpointField);

        // 获取 Key 按钮
        TextView getKey = new TextView(this);
        getKey.setText("→ 如何获取 API Key");
        getKey.setTextSize(14);
        getKey.setTextColor(0xFF4C6EF5);
        getKey.setPadding(dp(2), dp(12), dp(2), dp(4));
        getKey.setOnClickListener(v -> openKeyUrl());
        card.addView(getKey);

        // 保存
        Button save = new Button(this);
        save.setText("保存");
        save.setTextSize(15);
        save.setTextColor(0xFFFFFFFF);
        save.setBackground(solid(0xFF4C6EF5, dp(22)));
        save.setOnClickListener(v -> saveConfig());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(14);
        card.addView(save, slp);

        parent.addView(card);
    }

    /** 按所选服务商刷新输入区。 */
    private void refreshKeySection() {
        SearchProvider.Meta m = SearchProvider.metaOf(config.providerId);

        // 每个服务商有独立的 Key / 地址，切换时显示各自的值
        keyField.setText(config.apiKey());
        endpointField.setText(config.endpoint());

        // SearXNG 用地址而非 Key
        boolean searxng = "searxng".equals(m.id);
        endpointField.setVisibility(searxng ? View.VISIBLE : View.GONE);
        keyLabel.setVisibility(m.keyRequired ? View.VISIBLE : View.GONE);
        keyField.setVisibility(m.keyRequired ? View.VISIBLE : View.GONE);

        String hint;
        if (searxng) {
            hint = "SearXNG 无需密钥。可填自建实例地址；留空则使用公共实例 searx.be。";
        } else if (m.keyRequired) {
            hint = m.label + " 需要 API Key，点击下方链接申请。";
        } else {
            hint = m.label + " 无需任何配置，选中即可使用。";
        }
        hintView.setText(hint);
    }

    private void openKeyUrl() {
        SearchProvider.Meta m = SearchProvider.metaOf(config.providerId);
        if (m.keyUrl == null || m.keyUrl.isEmpty()) {
            toast("该服务无需申请密钥");
            return;
        }
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(m.keyUrl)));
        } catch (Throwable t) {
            // 无法打开浏览器时，把链接显示出来让用户复制
            new android.app.AlertDialog.Builder(this)
                    .setTitle("申请地址")
                    .setMessage(m.keyUrl)
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private void saveConfig() {
        config.setApiKey(keyField.getText().toString().trim());
        config.setEndpoint(endpointField.getText().toString().trim());
        store.saveSearchConfig(config);
        refreshKeySection();
        String k = config.apiKey();
        toast("已保存到「" + SearchProvider.metaOf(config.providerId).label + "」"
                + (k.isEmpty() ? "" : "（Key 长度 " + k.length() + "）"));
    }

    /** 测试搜索：跑一次真实请求。 */
    private void runTest() {
        config.setApiKey(keyField.getText().toString().trim());
        config.setEndpoint(endpointField.getText().toString().trim());
        store.saveSearchConfig(config);

        SearchProvider.Meta m = SearchProvider.metaOf(config.providerId);
        String invalid = SearchProvider.validate(config);
        if (invalid != null) {
            statusView.setTextColor(0xFFB3261E);
            statusView.setText("✗ " + invalid);
            return;
        }

        statusView.setTextColor(0xFF6B6B74);
        statusView.setText("正在搜索…");

        new Thread(() -> {
            String message;
            boolean ok;
            try {
                SearchProvider.Result res = SearchProvider.search(config, "今天有什么新闻");
                if (res.items.isEmpty()) {
                    ok = false;
                    message = "没有返回结果";
                } else {
                    ok = true;
                    StringBuilder sb = new StringBuilder();
                    sb.append("✓ ").append(m.label).append(" 搜索成功，返回 ")
                      .append(res.items.size()).append(" 条：\n");
                    int n = 0;
                    for (SearchProvider.Item it : res.items) {
                        n++;
                        sb.append(n).append(". ").append(it.title).append('\n');
                        if (n >= 3) break;
                    }
                    message = sb.toString().trim();
                }
            } catch (Throwable t) {
                ok = false;
                String msg = t.getMessage();
                if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
                message = "✗ 失败：" + msg;
            }
            final boolean fOk = ok;
            final String fMsg = message;
            runOnUiThread(() -> {
                statusView.setTextColor(fOk ? 0xFF2E7D32 : 0xFFB3261E);
                statusView.setText(fMsg);
            });
        }).start();
    }

    // ---------------- UI 小工具 ----------------

    private TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(16);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setTextColor(0xFF1B1B1F);
        v.setPadding(0, dp(22), 0, dp(8));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(solid(0xFFFFFFFF, dp(18)));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        c.setLayoutParams(lp);
        return c;
    }

    private GradientDrawable boxBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFF2F2F7);
        d.setCornerRadius(dp(12));
        return d;
    }

    private GradientDrawable solid(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable glass(int radius, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
