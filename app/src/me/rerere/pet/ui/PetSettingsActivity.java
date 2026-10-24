package me.rerere.pet.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.store.AppearanceVm;
import me.rerere.pet.ui.theme.UiTheme;
import me.rerere.pet.ui.widget.ChoiceRowView;
import me.rerere.pet.ui.widget.SeekRowView;
import me.rerere.pet.ui.widget.TextRowView;

/**
 * AI 身份与温度：名字 / 头像 / 温度 / 思考深度 / 待机随机动作 / 角色设定。
 *
 * ── 本轮重构（UI 第二轮）──
 * 旧版是手工堆卡片 + 系统 SeekBar（温度、思考深度各一条），
 * 两处都在 `onProgressChanged` 里直接改 provider 并落库 —— 拖动一次写几十次盘。
 * 现在：
 *   · 温度 / 思考深度用 SeekRowView / ChoiceRowView，松手/点击才落库；
 *   · 温度步进 0.1，显示两位以外自动取一位小数（与 API 期望一致）；
 *   · 待机随机动作改走 AppearanceVm（会顺手下发到运行中的桌宠，
 *     旧版只写盘，桌宠要重启才生效）；
 *   · 所有行沿用 SettingsPage 规范，不再有第二个色板。
 */
public final class PetSettingsActivity extends Activity {

    private static final int REQ_AVATAR = 2001;

    /** 思考深度档位。 */
    private static final String[] REASONING_LABELS = {
            "关闭", "低", "中", "高", "最高"
    };

    private PetStore store;
    private AppearanceVm appearanceVm;
    private PetStore.Provider provider;

    private ImageView avatarPreview;
    private TextRowView nameRow;
    private SeekRowView tempRow;
    private ChoiceRowView reasonRow;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PetStore(this);
        appearanceVm = new AppearanceVm(this);
        provider = store.activeProvider();

        SettingsPage page = new SettingsPage(this);

        if (provider == null) {
            page.pageTitle("AI 身份", null);
            page.section("还没有提供商");
            page.info("请先添加一个 AI 提供商，再回来设置身份与温度。");
            page.actions("去添加提供商", () -> startActivity(
                    new Intent(this, me.rerere.pet.MainActivity.class)), null, null);
            setContentView(page.build());
            return;
        }

        page.pageTitle("AI 身份与温度", "名字、头像、温度与思考深度。温度对回答风格影响很大，默认值最稳。");

        /* ---------------- 当前提供商 ---------------- */
        page.section("当前提供商");
        page.valueRowWide("提供商", provider.name == null ? "（未命名）" : provider.name);

        /* ---------------- 身份 ---------------- */
        page.section("身份");

        page.custom(avatarRow());
        if (hasAvatar()) page.custom(removeAvatarRow());

        nameRow = page.textField("名字", "桌宠在对话里显示的名字；留空会恢复成「桌宠」。",
                store.displayName(), 1, true);
        page.actions("保存名字", this::saveName, null, null);

        /* ---------------- 互动入口 ---------------- */
        page.section("互动");
        page.nav("互动反馈", interactSummary(),
                () -> startActivity(new Intent(this, PetInteractActivity.class)));

        /* ---------------- 温度 ---------------- */
        page.section("温度");

        tempRow = page.seek("温度",
                "低 = 稳定克制；高 = 发散有创意。多数模型 0.7~1.0 最自然。",
                provider.normalizedTemperature(), 0.1f, 3.0f, null,
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        /* 拖动中只更新行内数值，不写盘 */
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        float t = Math.round(v * 10f) / 10f;
                        provider.temperature = t;
                        persistProvider();
                        toast("温度已设为 " + formatTemp(t));
                    }
                });
        tempRow.setFormatter(v -> formatTemp(Math.round(v * 10f) / 10f));

        TextView warn = page.info("⚠ 默认为 1，除非你知道你在做什么，否则不要更改。");
        warn.setTextColor(0xFFB3261E);
        warn.setTypeface(null, android.graphics.Typeface.BOLD);

        /* ---------------- 思考深度 ---------------- */
        page.section("思考深度");

        reasonRow = page.choice("思考深度",
                "档位越高，模型思考越充分，但响应更慢。部分模型不支持调节。",
                REASONING_LABELS, clampLevel(provider.reasoningLevel), 3,
                (row, index) -> {
                    provider.reasoningLevel = index;
                    persistProvider();
                    row.setSelectedIndex(index);
                    toast("思考深度：" + reasoningLabel(index));
                });

        /* ---------------- 其他 ---------------- */
        page.section("其他");

        page.toggle("待机随机动作",
                "开启后，桌宠一段时间没人碰就会自己做随机动作",
                store.appearance().idleRandomEnabled,
                (row, on) -> appearanceVm.commit(a -> a.idleRandomEnabled = on));

        TextRowView personaRow = page.textField("角色设定",
                "桌宠的默认人设，会拼进每次对话的系统提示词",
                store.personaPrompt(), 4, false);

        page.actions("保存角色设定",
                () -> {
                    store.setPersonaPrompt(personaRow.getUserInput().trim());
                    toast("已保存");
                },
                null, null);

        setContentView(page.build());
    }

    /* ---------------- 身份：头像行 ---------------- */

    private LinearLayout avatarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        int pv = UiTheme.dp(this, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);

        avatarPreview = new ImageView(this);
        LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(
                UiTheme.dp(this, 56f), UiTheme.dp(this, 56f));
        row.addView(avatarPreview, avlp);
        renderAvatar();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = UiTheme.dp(this, 14f);
        row.addView(col, clp);

        col.addView(UiTheme.title(this, "自定义头像"));
        TextView sub = UiTheme.subtitle(this, hasAvatar()
                ? "显示在对话消息旁 · 点击更换"
                : "显示在对话消息旁 · 点击选择图片");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiTheme.dp(this, 4f);
        col.addView(sub, slp);

        TextView tail = UiTheme.text(this, hasAvatar() ? "更换" : "选择",
                14f, UiTheme.accent(), true);
        tail.setGravity(Gravity.CENTER);
        tail.setPadding(UiTheme.dp(this, 6f), UiTheme.dp(this, 6f),
                UiTheme.dp(this, 6f), UiTheme.dp(this, 6f));
        row.addView(tail);

        row.setClickable(true);
        row.setOnClickListener(v -> pickAvatar());
        return row;
    }

    /** 「移除头像」行（只在已有头像时插入）。 */
    private LinearLayout removeAvatarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int ph = UiTheme.dp(this, UiTheme.PAD_H);
        int pv = UiTheme.dp(this, UiTheme.PAD_V);
        row.setPadding(ph, pv, ph, pv);
        row.addView(UiTheme.text(this, "移除头像", 15f, 0xFFB3261E, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            store.setAvatarPath("");
            recreate();
            toast("已移除头像");
        });
        return row;
    }

    private boolean hasAvatar() {
        String p = store.avatarPath();
        return p != null && !p.isEmpty();
    }

    private void renderAvatar() {
        if (avatarPreview == null) return;
        String path = store.avatarPath();
        if (path != null && !path.isEmpty()) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(path);
                if (bmp != null) {
                    avatarPreview.setImageBitmap(circleCrop(bmp));
                    return;
                }
            } catch (Exception ignored) {}
        }
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFE8ECFB);
        d.setShape(GradientDrawable.OVAL);
        avatarPreview.setBackground(d);
        avatarPreview.setImageDrawable(null);
    }

    private Bitmap circleCrop(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path path = new Path();
        path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
        canvas.clipPath(path);
        int left = (src.getWidth() - size) / 2;
        int top = (src.getHeight() - size) / 2;
        canvas.drawBitmap(src, new Rect(left, top, left + size, top + size),
                new Rect(0, 0, size, size), paint);
        return out;
    }

    private void pickAvatar() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_AVATAR);
        } catch (Exception e) {
            toast("无法打开图片选择器");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_AVATAR || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            File out = new File(store.avatarDir(), "avatar.png");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                if (in == null) throw new java.io.IOException("open failed");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            store.setAvatarPath(out.getAbsolutePath());
            renderAvatar();
            /* 头像换了要整页重建： subtitle 文案（"点击更换"）与是否存在"移除"行都变了。
               重建比手工维护可见性更不容易漏。 */
            recreate();
            toast("头像已更新");
        } catch (Exception e) {
            toast("头像保存失败");
        }
    }

    /* ---------------- 身份：名字 ---------------- */

    private void saveName() {
        store.setDisplayName(nameRow.getUserInput());
        nameRow.setText(store.displayName());
        toast("已保存");
    }

    /* ---------------- 数据 ---------------- */

    private void persistProvider() {
        /* 统一走 PetStore.saveProvider：按 id 替换后整体保存。
           （旧 MainActivity 就曾写成 saveProviders(store.providers()) 而丢了编辑内容。） */
        if (provider == null) return;
        store.saveProvider(provider);
    }

    private String interactSummary() {
        if (!store.interactEnabled()) return "已关闭（会增加 token 消耗）";
        StringBuilder sb = new StringBuilder();
        sb.append("已启用 · 触发概率 ").append(store.interactChance()).append('%');
        sb.append(store.interactRandomPersona()
                ? " · 随机性格" : (" · " + store.interactPersona()));
        return sb.toString();
    }

    private static int clampLevel(int level) {
        if (level < 0) return 0;
        if (level >= REASONING_LABELS.length) return REASONING_LABELS.length - 1;
        return level;
    }

    private String reasoningLabel(int level) {
        return REASONING_LABELS[clampLevel(level)];
    }

    private String formatTemp(float t) {
        return String.format(Locale.US, "%.1f", t);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
