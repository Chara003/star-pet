package me.rerere.pet.data;

/**
 * 从 data 层访问 ui 层桌宠服务的桥。
 * 避免 data -> ui 的直接依赖，保持分层清晰。
 */
public final class PetOverlayServiceBridge {

    /** 由 ui 层在初始化时注入的具体实现。 */
    public interface Impl {
        void playExpression(String name, float seconds);
    }

    private static Impl impl;
    /** 当前模型是否有眼球骨骼（false = 眼动不可用）。 */
    private static volatile boolean eyeBonesAvailable = true;
    private static volatile boolean eyeBonesKnown = false;

    public static void setEyeBonesAvailable(boolean available) {
        eyeBonesAvailable = available;
        eyeBonesKnown = true;
    }

    public static boolean eyeBonesAvailable() { return eyeBonesAvailable; }
    public static boolean eyeBonesKnown() { return eyeBonesKnown; }

    /** 当前模型可用的表情名（由 ui 层在模型加载后填充）。 */
    private static volatile java.util.List<String> expressionNames =
            java.util.Collections.emptyList();

    /**
     * 当前模型可用的配件名（由 ui 层在模型加载后填充）。
     * ★ 为空表示该模型没有任何配件 → 配件页与配件工具**自动禁用**。
     */
    private static volatile java.util.List<String> accessoryNames =
            java.util.Collections.emptyList();

    public static void setAccessoryNames(java.util.List<String> names) {
        accessoryNames = names == null
                ? java.util.Collections.<String>emptyList()
                : new java.util.ArrayList<>(names);
    }

    public static java.util.List<String> accessoryNames() { return accessoryNames; }

    /**
     * 每个配件的"种类"标签（显示部件 / 姿势动作 / 形状变化）。
     * ★ 用户观察过"有的装备开了一点效果都没有" —— 其实那类是【顶点形状变化】，
     *   观感本来就比"显示/隐藏部件"弱得多。标注出来能避免误解。
     */
    private static volatile java.util.Map<String, String> accessoryKinds =
            java.util.Collections.emptyMap();

    public static void setAccessoryKinds(java.util.Map<String, String> kinds) {
        accessoryKinds = (kinds == null)
                ? java.util.Collections.<String, String>emptyMap()
                : new java.util.HashMap<>(kinds);
    }

    public static String accessoryKind(String name) {
        String k = (name == null) ? null : accessoryKinds.get(name);
        return k == null ? "" : k;
    }

    /**
     * 当前 glTF 模型【自带的动作】名（由 ui 层在 GLB 加载后填充）。
     * ★ 为空表示"当前不是 GLB 模型"，或该 GLB 没有内嵌动作
     *   —— 两种情况动作库都应回退到 VMD 文件列表。
     */
    private static volatile java.util.List<String> glbClipNames =
            java.util.Collections.emptyList();

    public static void setGlbClipNames(java.util.List<String> names) {
        glbClipNames = names == null
                ? java.util.Collections.<String>emptyList()
                : new java.util.ArrayList<>(names);
    }

    public static java.util.List<String> glbClipNames() { return glbClipNames; }

    /** 该模型是否有配件（没有则配件相关功能应禁用）。 */
    public static boolean hasAccessories() { return !accessoryNames.isEmpty(); }

    /* ---------------- 翻译进行中的状态（供界面显示标记） ----------------
       ★ 界面据此给"还没翻译完的名称"打上标记；
         翻译成功（进了缓存）或关闭翻译开关后，标记自然消失。 */

    /** 由 ui 层注入：查询正在翻译的名称。 */
    public interface TranslateStatusImpl {
        boolean isTranslating();
        java.util.Set<String> translatingNames();
        void requestTranslate(boolean manual);
    }

    private static TranslateStatusImpl transImpl;

    public static void installTranslateStatus(TranslateStatusImpl impl) { transImpl = impl; }

    public static boolean isTranslating() {
        return transImpl != null && transImpl.isTranslating();
    }

    /** 正在翻译的名称集合（可能为空）。 */
    public static java.util.Set<String> translatingNames() {
        if (transImpl == null) return java.util.Collections.emptySet();
        java.util.Set<String> s = transImpl.translatingNames();
        return s == null ? java.util.Collections.<String>emptySet() : s;
    }

    /** 该名称是否正在翻译中（界面据此显示标记）。 */
    public static boolean isTranslatingName(String name) {
        return name != null && translatingNames().contains(name);
    }

    /**
     * 翻译通道是否可用（＝桌宠服务在跑，已注册 impl）。
     * ★ 界面据此决定要不要自己跑兜底翻译：
     *   翻译功能原本完全挂在桌宠服务上，服务没启动时
     *   requestTranslate() 就是个空调用 —— 用户会看到"翻译一直不生效"。
     */
    public static boolean translateAvailable() { return transImpl != null; }

    /** 请求后台补齐译文（界面打开时调用）。 */
    public static void requestTranslate(boolean manual) {
        if (transImpl != null) {
            try { transImpl.requestTranslate(manual); } catch (Throwable ignored) {}
        }
    }

    private PetOverlayServiceBridge() {}

    public static void install(Impl implementation) {
        impl = implementation;
    }

    public static void playExpression(String name, float seconds) {
        if (impl != null) {
            try { impl.playExpression(name, seconds); } catch (Throwable ignored) {}
        }
    }

    /**
     * ★ 试播一个 glTF 自带动作（2026-10-07）。
     *
     * ---- 为什么单独开一条而不是复用 playExpression ----
     * 这个 GLB 的 morph target 全是【嘴型】，没有任何脸部表情 ——
     * 所以 glTF 模型下「表情」在语义上就是**自带动画**，
     * 试播也得播动画（不能走 morph 那条路）。实现挂在 VmdImpl 上
     * （动作播放本来就归它管），这里只做一个空安全转发。
     */
    public static void playGlbClip(String name) {
        if (vmdImpl != null) {
            try { vmdImpl.playGlbClip(name); } catch (Throwable ignored) {}
        }
    }

    /** 开关某个配件（由 MCP 工具 / 配件页调用）。 */
    public interface AccessoryImpl {
        void setAccessory(String name, boolean on);
        /** 批量设置（"全部开启/关闭"用）：只持久化一次，避免并发丢更新。 */
        void setAllAccessories(java.util.List<String> onList);
        String listAccessories();       // JSON: [{name, on}]
    }

    private static AccessoryImpl accImpl;

    public static void installAccessory(AccessoryImpl implementation) {
        accImpl = implementation;
    }

    /**
     * 配件通道是否可用（＝桌宠服务在跑并注册了 impl）。
     * ★ 供 UI 的 ViewModel 判断：服务在跑就"交给服务一次写完+下发"，
     *   服务没跑就直接落盘（等模型下次加载时由服务恢复），
     *   而不是静默什么都不发生。
     */
    public static boolean accessoryAvailable() { return accImpl != null; }

    public static void setAccessory(String name, boolean on) {
        if (accImpl != null) {
            try { accImpl.setAccessory(name, on); } catch (Throwable ignored) {}
        }
    }

    /** 批量设置配件开关（"全部开启/关闭"）。只持久化一次。 */
    public static void setAllAccessories(java.util.List<String> onList) {
        if (accImpl != null) {
            try { accImpl.setAllAccessories(onList); } catch (Throwable ignored) {}
        }
    }

    public static String listAccessories() {
        if (accImpl != null) {
            try { return accImpl.listAccessories(); } catch (Throwable ignored) {}
        }
        return "[]";
    }

    public static boolean available() { return impl != null; }

    /* ---------------- VMD 动画（动作绑定） ---------------- */

    /** 由 ui 层注入：VMD 配置变化后重新下发给页面 / 试播某个插槽。 */
    public interface VmdImpl {
        /** 配置变了：重新下发给页面（并重排随机表演计时）。 */
        void pushVmdConfig();
        /** 试播某个插槽（idle/walk/touchHead/touchBody/touchFeet/greet/drag）。 */
        void playVmdSlot(String slot);
        /** 试播一个 glTF 自带动作（按名字，一次性）—— 见 playGlbClip 的说明。 */
        void playGlbClip(String name);
    }

    private static VmdImpl vmdImpl;

    public static void installVmd(VmdImpl implementation) { vmdImpl = implementation; }

    public static void pushVmdConfig() {
        if (vmdImpl != null) {
            try { vmdImpl.pushVmdConfig(); } catch (Throwable ignored) {}
        }
    }

    public static void playVmdSlot(String slot) {
        if (vmdImpl != null) {
            try { vmdImpl.playVmdSlot(slot); } catch (Throwable ignored) {}
        }
    }

    public static boolean vmdAvailable() { return vmdImpl != null; }

    public static void setExpressionNames(java.util.List<String> names) {
        expressionNames = names == null
                ? java.util.Collections.<String>emptyList()
                : new java.util.ArrayList<>(names);
    }

    public static java.util.List<String> expressionNames() {
        return expressionNames;
    }
}
