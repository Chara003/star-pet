package me.rerere.pet.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import me.rerere.pet.data.PetStore;
import me.rerere.pet.ui.page.SpriteLibraryPage;
import me.rerere.pet.ui.store.AppearanceVm;

/**
 * 桌宠素材库的宿主 Activity。
 *
 * 需要 onActivityResult，所以素材导入必须由 Activity 发起 ——
 * 页面类只负责 UI 与数据，文件选择器的生命周期归 Activity 管。
 */
public final class SpriteLibraryActivity extends Activity implements SpriteLibraryPage.Host {

    private static final int REQ_IMPORT = 2001;

    private SpriteLibraryPage page;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        rebuild();
    }

    private void rebuild() {
        page = new SpriteLibraryPage(this, new PetStore(this), new AppearanceVm(this), this);
        setContentView(page.build());
    }

    /* ---------------- SpriteLibraryPage.Host ---------------- */

    @Override public void rebuildLibrary() {
        rebuild();
    }

    @Override public void requestImport() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (page != null) page.handleImportResult(uri);
    }
}
