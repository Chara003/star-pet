package me.rerere.pet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.SettingsPage;
import me.rerere.pet.ui.widget.ChoiceRowView;
import me.rerere.pet.ui.widget.SeekRowView;
import me.rerere.pet.ui.widget.TextRowView;
import me.rerere.pet.ui.widget.ToggleRowView;

/**
 * 互动设置页：启用开关、触发概率、随机性格、性格预设与提示词。
 * 这是不针对单个模型的全局设置。
 *
 * ── 本轮重构（UI 第二轮）──
 * 旧版是手工堆 LinearLayout + 系统 Switch/SeekBar，并且用
 * `enabledSwitch.isChecked()` 驱动"从属项置灰"，属于"UI 读控件值"。
 * 现在：
 *   · 骨架走 SettingsPage（统一 20/16 内边距、24dp 白卡片、深青开关）；
 *   · 状态只有一个来源（store），每次用户操作后 readFromStore() + render()；
 *   · 从属项用 setEnabled 收起，不再靠读控件猜当前状态；
 *   · 百分比滑块松手才落库（旧版每移动一格写一次 SharedPreferences）。
 *
 * ★ 提示词输入框的文本【只在】创建页面和切换性格时写入，
 *   普通重渲染不碰它 —— 否则用户正在打字时点一下别的开关，输入就没了。
 */
public final class PetInteractActivity extends Activity {

    private PetStore store;

    /* 唯一状态来源：都是从 store 读出来的快照 */
    private boolean enabled;
    private boolean random;
    private int chance;
    private String persona;

    private ToggleRowView enabledRow;
    private ToggleRowView randomRow;
    private SeekRowView chanceRow;
    private ChoiceRowView personaRow;
    private TextRowView promptRow;
    private TextView statusView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PetStore(this);
        readFromStore();

        SettingsPage page = new SettingsPage(this);
        page.pageTitle("互动反馈",
                "点击桌宠时，按概率让 AI 回一句。互动消息不带工具、不进入对话历史。");

        /* ---------------- 开关与概率 ---------------- */
        page.section("开关与概率");

        enabledRow = page.toggle("启用互动反馈",
                "会增加 token 消耗，关闭时完全不请求 AI",
                enabled,
                (row, on) -> {
                    store.setInteractEnabled(on);
                    readFromStore();
                    render();
                });
        enabledRow.setSubtitleColor(0xFFB3261E);

        page.info("开启后，点击桌宠时会按概率把你的触摸告诉 AI，"
                + "并在旁边显示一句回应。互动只是\"反应\"，不会写进聊天记录。");

        chanceRow = page.seek("触发概率",
                "每次点击有多大机会真的触发一次对话；其余点击仍会播动作，只是不说话",
                chance, 1f, 100f, "%",
                new SeekRowView.OnSeekListener() {
                    @Override public void onSeek(SeekRowView row, float v) {
                        /* 拖动中只更新行内数值（组件自己会做），不写盘 */
                    }
                    @Override public void onSeekFinished(SeekRowView row, float v) {
                        store.setInteractChance(Math.round(v));
                        readFromStore();
                        render();
                    }
                });

        randomRow = page.toggle("随机互动性格",
                "每次互动随机挑一个性格，而不是固定用下面选中的那个",
                random,
                (row, on) -> {
                    store.setInteractRandomPersona(on);
                    readFromStore();
                    render();
                });

        /* ---------------- 当前生效 ---------------- */
        page.section("当前生效");
        statusView = page.valueRowWide("互动将以以下方式运行", "");

        /* ---------------- 性格 ---------------- */
        page.section("互动性格");

        personaRow = page.choice("性格预设",
                "点一下切换；切换前会把当前这段提示词的修改保存到原来那个性格上",
                PetStore.PERSONA_PRESETS, indexOf(persona), 4,
                (row, index) -> onPersonaSelected(index));

        /* 提示词：真正的"内容源"是用户自己写的这段文字 */
        promptRow = page.textField("提示词",
                "这段文字会作为互动时的性格设定，可以改成你想要的口吻。"
                        + "清空则恢复该性格的预设文案。",
                store.personaPromptResolved(persona), 4, false);

        page.actions("保存性格设置", this::savePersona,
                "恢复预设提示词", this::resetPrompt);

        setContentView(page.build());
        render();
    }

    /* ---------------- 数据 ---------------- */

    private void readFromStore() {
        enabled = store.interactEnabled();
        random = store.interactRandomPersona();
        chance = store.interactChance();
        persona = store.interactPersona();
        if (indexOf(persona) < 0) persona = PetStore.PERSONA_PRESETS[0];
    }

    private static int indexOf(String preset) {
        if (preset == null) return -1;
        for (int i = 0; i < PetStore.PERSONA_PRESETS.length; i++) {
            if (preset.equals(PetStore.PERSONA_PRESETS[i])) return i;
        }
        return -1;
    }

    /* ---------------- 渲染（只读 store） ---------------- */

    private void render() {
        enabledRow.setChecked(enabled);
        randomRow.setChecked(random);
        chanceRow.setValue(chance);

        /* 从属项：主开关关掉时整行变暗且不响应触摸 */
        chanceRow.setEnabled(enabled);
        randomRow.setEnabled(enabled);
        personaRow.setEnabled(enabled && !random);
        promptRow.setEnabled(enabled && !random);

        /* 随机模式下不指定具体性格，所以四颗胶囊都不选中 */
        personaRow.setSelectedIndex(random ? -1 : indexOf(persona));

        statusView.setText(summary());
    }

    private String summary() {
        if (!enabled) return "已关闭 —— 点击桌宠只会播动作，不会请求 AI。";
        StringBuilder sb = new StringBuilder();
        sb.append("每次点击 ").append(chance).append("% 概率触发");
        if (random) {
            sb.append(" · 随机性格（").append(PetStore.PERSONA_PRESETS.length).append(" 选 1）");
        } else {
            sb.append(" · 固定性格：").append(persona);
        }
        return sb.toString();
    }

    /* ---------------- 用户意图 ---------------- */

    /**
     * 切换性格。
     * ★ 先把「当前性格」的编辑落库再切 —— 旧版直接换输入框内容，
     *   用户改了一半的提示词会静默丢失。
     */
    private void onPersonaSelected(int index) {
        if (!enabled || random) return;
        if (index < 0 || index >= PetStore.PERSONA_PRESETS.length) return;
        String next = PetStore.PERSONA_PRESETS[index];
        if (next.equals(persona)) return;

        commitCurrentPromptIfEdited();
        persona = next;
        store.setInteractPersona(persona);

        readFromStore();
        applyPersonaToField();
        render();
    }

    /** 只有真的改过才写覆盖值（避免给没动过的性格凭空写入一份拷贝）。 */
    private void commitCurrentPromptIfEdited() {
        String cur = promptRow.getUserInput().trim();
        if (!cur.equals(store.personaPromptResolved(persona))) {
            store.setPersonaOverride(persona, cur);
        }
    }

    private void applyPersonaToField() {
        promptRow.setText(store.personaPromptResolved(persona));
    }

    private void savePersona() {
        store.setInteractPersona(persona);
        store.setPersonaOverride(persona, promptRow.getUserInput().trim());
        toast("已保存「" + persona + "」");
        readFromStore();
        render();
    }

    private void resetPrompt() {
        promptRow.setText(PetStore.personaPrompt(persona));
        toast("已填入预设文案，记得点保存");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
