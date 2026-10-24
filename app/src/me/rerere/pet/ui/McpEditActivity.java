package me.rerere.pet.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

import me.rerere.pet.data.McpStore;

/**
 * MCP 服务器编辑页（仿参考图：基础设置 / 工具 两个 Tab）。
 */
public final class McpEditActivity extends Activity {

    public static final String EXTRA_ID = "mcp_id";

    private McpStore store;
    private McpStore.Server editing;
    private boolean isNew;

    private LinearLayout pageBasic;
    private LinearLayout pageTools;
    private TextView tabBasic;
    private TextView tabTools;

    private Switch enabledSwitch;
    private EditText nameField;
    private int transportIndex = 0;
    private EditText urlField;
    private LinearLayout headerList;
    private Switch shareToolsSwitch;

    private static final String[] TRANSPORTS = {"Streamable HTTP", "SSE"};

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new McpStore(this);

        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        isNew = (id == null || id.isEmpty());
        if (isNew) {
            editing = new McpStore.Server("mcp" + System.currentTimeMillis(), "", "");
        } else {
            for (McpStore.Server s : store.servers()) {
                if (s.id.equals(id)) { editing = s; break; }
            }
            if (editing == null) {
                toast("找不到该服务器");
                finish();
                return;
            }
        }
        transportIndex = McpStore.TRANSPORT_SSE.equals(editing.transport) ? 1 : 0;

        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF8F7FC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        // 顶部 Tab
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackgroundColor(0xFFFFFFFF);
        tabBasic = tab("基础设置");
        tabTools = tab("工具");
        tabRow.addView(tabBasic, new LinearLayout.LayoutParams(0, dp(52), 1f));
        tabRow.addView(tabTools, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(tabRow);

        pageBasic = new LinearLayout(this);
        pageBasic.setOrientation(LinearLayout.VERTICAL);
        pageBasic.setPadding(dp(20), dp(8), dp(20), dp(24));
        buildBasicPage(pageBasic);
        root.addView(pageBasic);

        pageTools = new LinearLayout(this);
        pageTools.setOrientation(LinearLayout.VERTICAL);
        pageTools.setPadding(dp(20), dp(8), dp(20), dp(24));
        buildToolsPage(pageTools);
        pageTools.setVisibility(View.GONE);
        root.addView(pageTools);

        tabBasic.setOnClickListener(v -> selectTab(0));
        tabTools.setOnClickListener(v -> selectTab(1));
        selectTab(0);

        return scroll;
    }

    private void selectTab(int index) {
        pageBasic.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        pageTools.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        styleTab(tabBasic, index == 0);
        styleTab(tabTools, index == 1);
    }

    private TextView tab(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(0xFF1B1B1F);
        v.setPadding(0, dp(14), 0, dp(14));
        return v;
    }

    private void styleTab(TextView v, boolean active) {
        v.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        v.setTextColor(active ? 0xFF1B1B1F : 0xFF7A7A85);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        v.setBackground(bg);
        // 底部指示线
        GradientDrawable indicator = new GradientDrawable();
        indicator.setColor(active ? 0xFF2F7E7E : 0x00000000);
        // 用 padding 底部模拟下划线
        v.setCompoundDrawablePadding(0);
    }

    private void buildBasicPage(LinearLayout page) {
        // 启用
        enabledSwitch = new Switch(this);
        enabledSwitch.setChecked(editing.enabled);
        page.addView(switchRow("启用", enabledSwitch));

        // 名称
        page.addView(fieldLabel("名称"));
        page.addView(fieldHint("MCP 服务器的显示名称"));
        nameField = input(editing.name, "名称");
        page.addView(nameField);

        // 传输类型
        page.addView(fieldLabel("传输类型"));
        page.addView(fieldHint("选择 MCP 服务器的传输协议类型"));
        LinearLayout transportRow = new LinearLayout(this);
        transportRow.setOrientation(LinearLayout.HORIZONTAL);
        transportRow.setBackground(boxBackground());
        for (int i = 0; i < TRANSPORTS.length; i++) {
            final int idx = i;
            final TextView t = new TextView(this);
            t.setText(TRANSPORTS[i]);
            t.setTextSize(14);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(14), 0, dp(14));
            t.setOnClickListener(v -> {
                transportIndex = idx;
                refreshTransportTabs(transportRow);
            });
            transportRow.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        page.addView(transportRow, withTopMargin(dp(4)));
        refreshTransportTabs(transportRow);

        // 服务器地址
        page.addView(fieldLabel("服务器地址"));
        page.addView(fieldHint(McpStore.TRANSPORT_SSE.equals(editing.transport)
                ? "SSE 服务器的 URL 地址" : "流式 HTTP 服务器的 URL 地址"));
        urlField = input(editing.url, "URL");
        page.addView(urlField);

        // 自定义请求头
        page.addView(fieldLabel("自定义请求头"));
        page.addView(fieldHint("为 MCP 服务器请求添加自定义 HTTP 头"));

        headerList = new LinearLayout(this);
        headerList.setOrientation(LinearLayout.VERTICAL);
        page.addView(headerList);
        for (Map.Entry<String, String> e : editing.headers.entrySet()) {
            addHeaderRow(e.getKey(), e.getValue());
        }

        Button addHeader = new Button(this);
        addHeader.setText("＋ 添加请求头");
        addHeader.setTextSize(15);
        addHeader.setTextColor(0xFFFFFFFF);
        addHeader.setBackground(solid(0xFF2F7E7E, dp(24)));
        addHeader.setOnClickListener(v -> addHeaderRow("", ""));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        page.addView(addHeader, lp);

        // 保存
        Button save = new Button(this);
        save.setText("保存");
        save.setTextSize(16);
        save.setTextColor(0xFFFFFFFF);
        save.setBackground(solid(0xFF2F7E7E, dp(24)));
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(24);
        page.addView(save, saveLp);
    }

    private void refreshTransportTabs(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView t = (TextView) row.getChildAt(i);
            boolean active = (i == transportIndex);
            t.setTextColor(active ? 0xFF1B1B1F : 0xFF7A7A85);
            t.setBackground(solid(active ? 0xFFDCEEF0 : 0x00000000, dp(24)));
            t.setText(active ? "✓ " + TRANSPORTS[i] : TRANSPORTS[i]);
        }
    }

    private void buildToolsPage(LinearLayout page) {
        shareToolsSwitch = new Switch(this);
        shareToolsSwitch.setChecked(editing.shareTools);
        page.addView(switchRow("把工具列表发送给 AI", shareToolsSwitch));
        page.addView(fieldHint("关闭后，该服务器的工具不会出现在模型的可用工具里，仅作展示。"));
        page.addView(fieldHint("连接状态与工具数量请在列表页查看。"));
    }

    private void addHeaderRow(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText k = input(key, "Header 名");
        k.setTag("header_key");
        EditText v = input(value, "值");
        v.setTag("header_value");

        row.addView(k, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = dp(8);
        row.addView(v, vlp);

        TextView remove = new TextView(this);
        remove.setText("✕");
        remove.setTextSize(16);
        remove.setGravity(Gravity.CENTER);
        remove.setPadding(dp(10), dp(8), dp(4), dp(8));
        remove.setOnClickListener(x -> headerList.removeView(row));
        row.addView(remove);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        headerList.addView(row, rlp);
    }

    private void save() {
        String name = nameField.getText().toString().trim();
        String url = urlField.getText().toString().trim();
        if (name.isEmpty()) { toast("请填写名称"); return; }
        if (url.isEmpty()) { toast("请填写服务器地址"); return; }

        editing.name = name;
        editing.url = url;
        editing.enabled = enabledSwitch.isChecked();
        editing.transport = TRANSPORTS[transportIndex].equals("SSE")
                ? McpStore.TRANSPORT_SSE : McpStore.TRANSPORT_HTTP;
        if (shareToolsSwitch != null) editing.shareTools = shareToolsSwitch.isChecked();

        editing.headers.clear();
        for (int i = 0; i < headerList.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) headerList.getChildAt(i);
            EditText k = null, v = null;
            for (int j = 0; j < row.getChildCount(); j++) {
                View child = row.getChildAt(j);
                if (child instanceof EditText) {
                    if ("header_key".equals(child.getTag())) k = (EditText) child;
                    else if ("header_value".equals(child.getTag())) v = (EditText) child;
                }
            }
            if (k == null || v == null) continue;
            String key = k.getText().toString().trim();
            String val = v.getText().toString().trim();
            if (!key.isEmpty()) editing.headers.put(key, val);
        }

        store.upsert(editing);
        toast(isNew ? "已添加" : "已保存");
        finish();
    }

    // ---------- 小工具 ----------

    private LinearLayout switchRow(String label, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(0xFF1B1B1F);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);
        return row;
    }

    private TextView fieldLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(16);
        v.setTextColor(0xFF1B1B1F);
        v.setPadding(0, dp(18), 0, dp(2));
        return v;
    }

    private TextView fieldHint(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(0xFF8A8A93);
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private EditText input(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setBackground(boxBackground());
        e.setPadding(dp(14), dp(14), dp(14), dp(14));
        return e;
    }

    private GradientDrawable boxBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), 0xFFB8B8C0);
        return d;
    }

    private GradientDrawable solid(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout.LayoutParams withTopMargin(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        return lp;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
