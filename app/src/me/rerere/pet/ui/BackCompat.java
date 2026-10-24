package me.rerere.pet.ui;

import android.app.Activity;
import android.os.Build;
import android.view.View;

/**
 * 返回手势兼容层：普通返回 + **跟手预测性返回**。
 *
 * ══════════════════════════════════════════════════════════════
 * 各级 Android 能做什么（别指望更多）
 * ══════════════════════════════════════════════════════════════
 *   API < 30（Android 10 及以下）
 *     · 只有"返回键/手势触发返回"，App 侧没有任何预览能力。
 *     · 走 onBackPressed。
 *
 *   API 30~32（Android 11~12L）
 *     · 手势返回存在，但**没有**预测性返回框架。
 *     · 仍然走 onBackPressed。
 *     · ★ 你朋友的 Android 11 就属于这一档：系统不支持，
 *       App 侧没有任何 API 能做出"跟手预览"——这是平台限制，不是没做。
 *
 *   API 33（Android 13）
 *     · 有了 OnBackInvokedDispatcher（预测性返回框架第一版）。
 *     · 但只有"开始/完成"两个回调，**没有进度**，做不出跟手动画。
 *     · 系统可以播"退回桌面"的默认动画。
 *
 *   API 34+（Android 14 起）—— 我们的设备是 API 36
 *     · OnBackAnimationCallback 提供 onBackStarted / onBackProgressed /
 *       onBackCancelled / onBackInvoked 四个回调，
 *       onBackProgressed 里的 progress(0..1) 就是手指拖动进度。
 *     · ★ 这才真正能做出"跟手"的预览动画。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么实现要放进静态内部类
 * ══════════════════════════════════════════════════════════════
 *   直接在方法体里引用 android.window.OnBackAnimationCallback 会让
 *   低版本 ART 在类验证阶段就去找这个接口。本项目 minSdk 24，
 *   见过低版本 VerifyError 的真实案例，所以按 SDK 分级隔离。
 */
public final class BackCompat {

    /**
     * 返回事件的处理器。
     *
     * ★ 跟手动画的约定：
     *   · {@link #onStarted()} 手指按下、开始返回动作 → 准备动画（此时不要切页）
     *   · {@link #onProgress(float)} progress 0..1 → 只改视觉，**不要改业务状态**
     *   · {@link #onInvoked()} 用户松手确认 → 真正执行返回
     *   · {@link #onCancelled()} 用户滑回/取消 → 复位视觉
     *   只有 onInvoked 之后才能改 current 页，否则取消后状态就乱了。
     */
    public interface OnBack {
        /** 真正执行返回。 */
        void onBack();

        /** 跟手开始（可选，默认空实现）。 */
        default void onBackStarted() {}

        /** 跟手进度 0..1（可选）。只应改视觉属性。 */
        default void onBackProgressed(float progress) {}

        /** 跟手取消（可选）：把视觉复位。 */
        default void onBackCancelled() {}
    }

    private BackCompat() {}

    /**
     * 安装返回回调。调用后 **不要再依赖 onBackPressed()**（33+ 不会走到）。
     */
    public static void install(Activity activity, OnBack handler) {
        if (activity == null || handler == null) return;
        synchronized (SELF_INSTALLED) { SELF_INSTALLED.put(activity, Boolean.TRUE); }

        android.util.Log.i("PetBack", "install: sdk=" + Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Api34.install(activity, handler);
                android.util.Log.i("PetBack", "installed via Api34 (跟手预览可用)");
                return;
            } catch (Throwable t) {
                android.util.Log.w("PetBack", "Api34 failed, fallback", t);
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Api33.install(activity, handler);
                android.util.Log.i("PetBack", "installed via Api33 (仅回调)");
                return;
            } catch (Throwable t) {
                android.util.Log.w("PetBack", "Api33 failed", t);
            }
        }
        /* 低版本：保持 onBackPressed 路径 */
    }

    /** 是否支持"跟手"的预测性返回（API 34+）。 */
    public static boolean predictiveBackSupported() {
        return Build.VERSION.SDK_INT >= 34;
    }

    /** 当前系统的返回手势说明文案（给设置页展示，如实反映能力）。 */
    public static String backHint() {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 34) {
            return "当前系统（Android " + sdk + "）支持跟手预测性返回："
                    + "从边缘滑动时页面会跟着手指走，松手才真正返回。"
                    + "\n在非首个标签页时返回会先回到主界面。";
        }
        if (sdk >= 33) {
            return "当前系统（Android " + sdk + "）支持返回手势回调，"
                    + "但跟手预览动画需要 Android 14 及以上。";
        }
        if (sdk >= 30) {
            return "当前系统（Android " + sdk + "）只有普通返回手势。"
                    + "跟手预测性返回是 Android 14 起才有的系统能力"
                    + "（Android 13 只有回调、没有进度）。";
        }
        return "当前系统（Android " + sdk + "）只有普通返回手势 —— "
                + "预测性返回是 Android 13 才引入的系统框架，"
                + "Android 11 上无法实现。";
    }

    /**
     * ★★★ 全局自动安装：一次调用，之后【所有 Activity】自动接上 ★★★
     *
     * ══════════════════════════════════════════════════════════════
     * 为什么必须有这个（而不是在每个 Activity 里手写一行）
     * ══════════════════════════════════════════════════════════════
     *   manifest 开了 enableOnBackInvokedCallback="true" 之后，
     *   系统【不再调用 onBackPressed】—— 每个 Activity 都必须自己
     *   注册 OnBackInvokedCallback，否则**返回键在这个页面直接失效**。
     *   手写的话，以后每加一个新 Activity 都要记得加一行，忘一次就出 bug
     *   （而且症状很隐蔽：只有那个页面返回没反应）。
     *
     *   用 Application.registerActivityLifecycleCallbacks 在 onActivityCreated
     *   里统一注册，新 Activity 从创建起就自动带上，不需要任何额外代码。
     *
     * ★ 通过 Activity 的"是否已注册"标记避免重复注册：
     *   某些页面（如 MainTabsActivity）有自己的返回逻辑，会先自行安装，
     *   我们用 getOnBackInvokedDispatcher 无法查询已注册的回调，
     *   所以用一个 WeakHashMap 记录已处理的 Activity。
     */
    private static final java.util.WeakHashMap<Activity, Boolean> INSTALLED =
            new java.util.WeakHashMap<>();
    /** 已被页面自己安装过的 Activity（全局回调要跳过它们）。 */
    private static final java.util.WeakHashMap<Activity, Boolean> SELF_INSTALLED =
            new java.util.WeakHashMap<>();

    /**
     * @deprecated ★★ 不要用！★★
     *
     * 官方文档：注册 PRIORITY_DEFAULT 的 OnBackInvokedCallback 会让
     * 系统不再播放预测性返回动画（变成"应用自己负责"）。
     * 普通页面请【什么都不注册】，让系统自动 finish + 播动画。
     */
    @Deprecated
    public static void installGlobal(android.app.Application app) {
        if (app == null) return;
        app.registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityCreated(Activity a,
                                                             android.os.Bundle b) {
                        if (a == null) return;
                        synchronized (SELF_INSTALLED) {
                            /* ★ 页面自己接管了（例如 MainTabsActivity 有自己的
                               返回逻辑）→ 不覆盖，否则会把它的回调顶掉。 */
                            if (SELF_INSTALLED.containsKey(a)) return;
                        }
                        synchronized (INSTALLED) {
                            if (INSTALLED.containsKey(a)) return;
                            INSTALLED.put(a, Boolean.TRUE);
                        }
                        /* 默认行为：返回 = finish。
                           想自定义的页面（如 MainTabsActivity）在 onCreate 里
                           调 install(...) 覆盖即可 —— 后注册的优先级更高。 */
                        installFinishOnBack(a);
                    }

                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityResumed(Activity a) {}
                    @Override public void onActivityPaused(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a,
                                                                      android.os.Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {
                        synchronized (INSTALLED) { INSTALLED.remove(a); }
                        synchronized (SELF_INSTALLED) { SELF_INSTALLED.remove(a); }
                    }
                });
    }

    /**
     * 给【普通二级页面】用：注册"直接 finish"的返回回调，**让系统播动画**。
     *
     * ══════════════════════════════════════════════════════════════
     * ★★★ 这里有一个反直觉的机制（我踩过，务必看完再改）★★★
     * ══════════════════════════════════════════════════════════════
     *   注册哪种回调，决定了谁负责动画：
     *
     *   ┌─ registerOnBackInvokedCallback(普通 OnBackInvokedCallback)
     *   │    → 系统**自己**播放跟手预测性返回动画（页面跟着手指滑出）
     *   │    → 我们只在松手后收到 onBackInvoked()
     *   │    → ✅ 普通页面要用这个
     *   │
     *   └─ registerOnBackInvokedCallback(OnBackAnimationCallback)
     *        → 系统认为"应用要自己做动画"，**不再播系统动画**
     *        → 我们会收到 onBackStarted / onBackProgressed
     *        → 必须自己每帧改 translationX，否则就是"什么都没发生"
     *        → 只有真要自定义动画时才用
     *
     *   ---- 我犯的错 ----
     *   一开始想"跟手就要用 AnimationCallback"，于是所有页面都注册了
     *   AnimationCallback，但又没实现逐帧动画 → 系统把动画权交给我们、
     *   我们没做 → 表现就是"预测性返回手势全都没有"
     *   （日志证据：只有 onBackStarted → onBackInvoked，没有 onBackProgressed）。
     *
     *   正解：普通页面注册**普通回调**，把动画交还给系统。
     */
    /**
     * @deprecated ★★ 不要用！★★ 原因同 installGlobal ——
     * 注册回调会关闭系统动画。普通页面请什么都不做。
     */
    @Deprecated
    public static void installFinishOnBack(final Activity activity) {
        if (activity == null) return;
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(activity)) return;   // 已装过，别重复注册
            INSTALLED.put(activity, Boolean.TRUE);
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                /* ★ 用普通回调 => 系统播跟手动画 */
                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new android.window.OnBackInvokedCallback() {
                            @Override public void onBackInvoked() {
                                try { activity.finish(); } catch (Throwable ignored) {}
                            }
                        });
                android.util.Log.i("PetBack", "installed finish-on-back (system animates)");
                return;
            }
        } catch (Throwable t) {
            android.util.Log.w("PetBack", "installFinishOnBack failed", t);
        }
        /* 低版本：onBackPressed 仍然有效 */
    }

    /* ==================== API 33：只有回调，没有进度 ==================== */

    /** 只在 API 33+ 加载。 */
    private static final class Api33 {
        static void install(Activity activity, final OnBack handler) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    new android.window.OnBackInvokedCallback() {
                        @Override public void onBackInvoked() {
                            handler.onBack();
                        }
                    });
        }
    }

    /* ==================== API 34：跟手 ==================== */

    /**
     * 只在 API 34+ 加载。
     *
     * ★★★ 为什么用反射 + 动态代理（而不是直接实现接口）★★★
     *   本项目编译用的 android.jar 只有 **android-33**，
     *   里面【没有】 OnBackAnimationCallback 和 BackEvent
     *   （它们是 API 34 才加的）。直接写会编译失败。
     *   设备上到 API 34+ 运行时是有的，所以：
     *     · 用 Class.forName 拿接口与类；
     *     · 用 Proxy.newProxyInstance 造一个实现；
     *     · 用反射调用 getProgress()。
     *   这段代码只在 SDK>=34 时被调用，老系统连类都不会加载。
     *
     * ★ 回调顺序（实测）：
     *     onBackStarted → onBackProgressed × N（progress 递增）
     *       → 松手：onBackInvoked
     *       → 滑回：onBackCancelled
     */
    private static final class Api34 {
        static void install(Activity activity, final OnBack handler) {
            try {
                Class<?> cbCls = Class.forName("android.window.OnBackAnimationCallback");
                Object callback = java.lang.reflect.Proxy.newProxyInstance(
                        cbCls.getClassLoader(),
                        new Class<?>[]{cbCls},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            try {
                                if ("onBackStarted".equals(name)) {
                                    android.util.Log.i("PetBack", "onBackStarted");
                                    handler.onBackStarted();
                                } else if ("onBackProgressed".equals(name)) {
                                    handler.onBackProgressed(progressOf(args));
                                } else if ("onBackCancelled".equals(name)) {
                                    handler.onBackCancelled();
                                } else if ("onBackInvoked".equals(name)) {
                                    android.util.Log.i("PetBack", "onBackInvoked");
                                    handler.onBack();
                                } else if ("toString".equals(name)) {
                                    return "PetBackAnimationCallback";
                                } else if ("hashCode".equals(name)) {
                                    return System.identityHashCode(proxy);
                                } else if ("equals".equals(name)) {
                                    return proxy == (args != null && args.length > 0
                                            ? args[0] : null);
                                }
                            } catch (Throwable ignored) {}
                            return null;
                    });

                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        (android.window.OnBackInvokedCallback) callback);
            } catch (Throwable t) {
                android.util.Log.w("PetBack", "Api34 reflect failed: "
                        + t.getClass().getSimpleName() + " " + t.getMessage(), t);
                /* 拿不到 API（ROM 裁剪）：退到 API 33 的纯回调 */
                Api33.install(activity, handler);
            }
        }

        /** 从 BackEvent 里反射取 progress（0..1）。 */
        private static float progressOf(Object[] args) {
            if (args == null || args.length == 0 || args[0] == null) return 0f;
            try {
                java.lang.reflect.Method m =
                        args[0].getClass().getMethod("getProgress");
                Object v = m.invoke(args[0]);
                return v instanceof Float ? (Float) v : 0f;
            } catch (Throwable t) {
                return 0f;
            }
        }
    }
}
