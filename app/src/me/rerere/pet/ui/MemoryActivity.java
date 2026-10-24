package me.rerere.pet.ui;

import android.app.Activity;
import android.os.Bundle;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.MemoryPage;

/**
 * 长期记忆管理的宿主 Activity。
 *
 * ★ 为什么单独开一个 Activity 而不是做成分页：
 *   记忆是"低频但需要专注"的操作（写句话、删条记录），
 *   塞进底栏某一页会把那个页面撑得很长，而且和 AI 页其它内容混在一起。
 *   独立页还有个好处：编辑时不会误触底栏切走。
 */
public final class MemoryActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PetStore store = new PetStore(this);
        /* 内容变化后重建本页（保持滚动位置没意义，直接重画最省事） */
        setContentView(new MemoryPage(this, store, this::rebuild).build());
    }

    private void rebuild() {
        PetStore store = new PetStore(this);
        setContentView(new MemoryPage(this, store, this::rebuild).build());
    }
}
