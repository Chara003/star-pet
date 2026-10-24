package me.rerere.pet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.ModelPickerPage;
import me.rerere.pet.ui.store.AppearanceVm;

/**
 * 模型与素材选择页的宿主。
 *
 * 选择生效后要重建页面（刷新"● 使用中"标记），
 * 所以实现 ModelPickerPage.Host 并在这里重建。
 */
public final class ModelPickerActivity extends Activity implements ModelPickerPage.Host {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        rebuild(0);
    }

    @Override public void onSelectionChanged() {
        /* ★ 保留滚动位置：模型多的时候用户在第 5 个点了一下，
           重建后跳回顶部会以为"点错了/列表变了"（旧实现就是每次回顶部）。 */
        int y = 0;
        View cur = findViewById(android.R.id.content);
        if (cur instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) cur;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) instanceof ScrollView) {
                    y = ((ScrollView) g.getChildAt(i)).getScrollY();
                    break;
                }
            }
        }
        rebuild(y);
    }

    private void rebuild(int scrollY) {
        View v = new ModelPickerPage(this, new PetStore(this),
                new AppearanceVm(this), this).build();
        setContentView(v);
        if (scrollY > 0 && v instanceof ScrollView) {
            final ScrollView sv = (ScrollView) v;
            final int y = scrollY;
            sv.post(() -> sv.scrollTo(0, y));
        }
    }
}
