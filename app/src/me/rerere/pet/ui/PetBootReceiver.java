package me.rerere.pet.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import me.rerere.pet.data.PetStore;

/** 开机自启：仅在用户已启用桌宠时拉起悬浮窗服务。 */
public final class PetBootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;

        PetStore store = new PetStore(context);
        if (!store.appearance().enabled) return;

        try {
            Intent service = new Intent(context, PetOverlayService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {}
    }
}
