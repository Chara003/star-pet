package me.rerere.pet.ui;

import android.util.Log;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import me.rerere.pet.data.MmdModelManager;

/**
 * MMD 高级设置页。
 *
 * 直接复用 index.html 里的完整控制面板（光照/曝光/饱和度/色相/材质/
 * 相机/自转/表情/眼动/物理），与那个 three.js 验证工程完全一致。
 *
 * 设置通过 localStorage 持久化；由于设置页与桌宠悬浮窗使用同一个
 * origin（appassets.androidplatform.net），localStorage 共享，
 * 因此这里调好的值，桌宠重载后即生效。
 */
public final class MmdSettingsActivity extends Activity {

    private MmdPetView mmdView;
    private boolean panelVisible = true;
    private TextView toggleBtn;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF14141A);

        // WebView（与桌宠同源，共享 localStorage）
        mmdView = new MmdPetView(this, new MmdModelManager(this));
        mmdView.setListener(new MmdPetView.Listener() {
            @Override public void onReady() {
                applyPanelState();
            }
            @Override public void onLog(String message) {}
            @Override public void onError(String message) {}
            /**
             * ★ 关键：把本页调好的饱和度/色相【转发给桌宠悬浮窗】。
             * 设置页和桌宠是两个独立 WebView，共享 localStorage 但不会实时同步；
             * 不转发的话，用户对着桌宠调饱和度会感觉"怎么调都一样"。
             */
            @Override public void onColorGradeChanged(float sat, float hue) {
                PetOverlayService svc = PetOverlayService.current();
                if (svc != null) svc.setColorGrade(sat, hue);
            }
            /* ★ 设置页里也有个锁开关，改动后要同步给悬浮窗 */
            @Override public void onContentAspect(float aspect) {}

            /**
             * ★ 把外观调整（曝光/光照/镜面/toon…）实时同步给桌宠。
             * 两个 WebView 各有一份模型，不同步的话用户会觉得"调了没反应"。
             */
            @Override public void onAppearanceChanged(String key, String value) {
                PetOverlayService svc = PetOverlayService.current();
                if (svc != null) svc.applyAppearanceKV(key, value);
            }
        });
        WebView wv = mmdView.create();
        root.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部工具条
        root.addView(buildToolbar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        setContentView(root);
    }

    /** 顶部原生工具条：标题 + 隐藏面板 + 重置 + 完成。 */
    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6181820);
        bg.setCornerRadius(dp(16));
        bar.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("MMD 设置");
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        toggleBtn = toolButton("隐藏面板");
        toggleBtn.setOnClickListener(v -> {
            panelVisible = !panelVisible;
            applyPanelState();
        });
        bar.addView(toggleBtn);

        TextView reset = toolButton("重置");
        reset.setOnClickListener(v -> confirmReset());
        bar.addView(reset);

        TextView done = toolButton("完成");
        done.setTextColor(0xFF7FD4FF);
        done.setOnClickListener(v -> {
            /* ★ 先强制保存一次再关页 ——
               原来 JS 的自动保存有 800ms 防抖，用户勾完马上点「完成」
               就会丢配置。现在 change 事件已改成立即保存，
               这里再 flush 一次作为双保险。 */
            if (mmdView != null) {
                try { mmdView.flushSettings(); } catch (Throwable ignored) {}
            }
            /* 给 JS 一点时间把 localStorage 写完再 finish */
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::finish, 120);
        });
        bar.addView(done);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(10), dp(10), dp(10), 0);
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private TextView toolButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(0xFFDDDDE6);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /** 同步面板显示状态到页面。 */
    private void applyPanelState() {
        if (mmdView == null) return;
        android.util.Log.i("MmdSettings", "applyPanelState visible=" + panelVisible);
        mmdView.eval("window.__togglePanel && window.__togglePanel(" + panelVisible + ")");
        // 回读面板实际状态，确认 JS 真的执行了
        mmdView.evalState("(function(){try{"
                + "var p=document.getElementById('panel');"
                + "return p ? (p.style.display + '|offsetH=' + p.offsetHeight "
                + "+ '|scrollH=' + p.scrollHeight) : 'no-panel';"
                + "}catch(e){return 'err:'+e.message;}})()",
                value -> android.util.Log.i("MmdSettings", "panel state = " + value));
        if (toggleBtn != null) {
            toggleBtn.setText(panelVisible ? "隐藏面板" : "显示面板");
        }
    }

    private void confirmReset() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("重置 MMD 设置")
                .setMessage("将恢复所有光照/材质/相机/表情/眼动参数为默认值。确定吗？")
                .setPositiveButton("重置", (d, w) -> {
                    // 清掉 localStorage 后重新加载页面
                    mmdView.eval("(function(){try{localStorage.removeItem('mmdtest.settings.v1');}catch(e){}})();"
                            + "setTimeout(function(){location.reload();},80);");
                    toast("已重置，正在重新加载…");
                    // 重载后重新显示面板
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(this::applyPanelState, 2500);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (mmdView != null) mmdView.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        /* ★★★ 这里【故意不】调用 mmdView.onPause() ★★★
           ---- 事故复盘（2026-10-08 曾出现"模型被冻结了"）----
           现象：日志在某一刻完全静止（心跳也不再出现），进程 CPU 0%、sleeping、
                 无 ANR、无异常 —— 典型的 **WebView 被暂停**，不是死循环。
           根因：本 Activity 的 onPause 会 `webView.onPause()`，
                 而 **Chromium 在多 WebView 并存时会把整个渲染进程的调度一起降级** ——
                 桌宠悬浮窗那个 WebView 也跟着停摆（rAF 不再回调）。
                 ★ 而 JS 侧的"渲染看门狗"是 setInterval —— WebView 一暂停，
                   它自己也停，根本救不了自己（这是那个方案的结构性缺陷）。
           为什么可以直接去掉：本页关闭时紧接着就是 onDestroy → mmdView.destroy()，
                 中间这段 pause 没有任何收益，却会把桌宠一起带停。
           （真正的兜底在 MmdPetView 的【原生看门狗】里 —— 它跑在 Android 主线程，
             不受 WebView 暂停影响。） */
        Log.i("PetMmd", "MmdSettingsActivity.onPause：保留 WebView 运行（避免拖停桌宠）");
    }

    @Override protected void onDestroy() {
        if (mmdView != null) {
            mmdView.destroy();
            mmdView = null;
        }
        super.onDestroy();
    }

    @Override public void finish() {
        // 设置已写入 localStorage；重启桌宠悬浮窗以应用
        try {
            stopService(new android.content.Intent(this, PetOverlayService.class));
        } catch (Throwable ignored) {}
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                startService(new android.content.Intent(this, PetOverlayService.class));
            } catch (Throwable ignored) {}
        }, 600);
        super.finish();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
