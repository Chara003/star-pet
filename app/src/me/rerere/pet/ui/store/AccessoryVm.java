package me.rerere.pet.ui.store;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.rerere.pet.data.PetOverlayServiceBridge;
import me.rerere.pet.data.PetStore;

/**
 * 配件开关的唯一数据入口（UI 重构 Phase 0 试点）。
 *
 * ★★ 它解决什么问题（HANDOVER §17.7 / §17.9）★★
 *   旧实现是"双向绑定 + 到处撒 save"：
 *       setChecked(历史值) → 触发 onCheckedChanged → 逐次 读-改-写 持久化
 *   于是有两条独立的坏路径：
 *     ① 初始化阶段 setChecked 就会写盘 —— 页面一加载、还没等数据到手，
 *        默认值就把历史配置覆盖了（§17.9）；
 *     ② "全部开启/关闭"循环 setChecked → 几十次 读-改-写，
 *        SharedPreferences 的异步 apply 会互相覆盖 → 关闭丢失（§17.7）。
 *
 *   现在改成单向数据流：
 *       Activity ──(意图事件 toggle/setAll)──► AccessoryVm ──► Store / OverlayBridge
 *       Activity ◄──(Snapshot 渲染)────────── AccessoryVm
 *
 *   ★ 铁律（本类就是这条规则的载体）：
 *     · 只有 toggle() / setAll() 会写数据，它们【只能被用户操作】调用；
 *     · snapshot() 是纯读，永远不写 —— 所以渲染多少次都不会改配置；
 *     · 批量操作是一次写入（列表整体覆盖），不是循环单条写入。
 *
 *   ★ 为什么 Snapshot 每次现算、不缓存：
 *     配件状态还可能被 MCP 工具（set_accessory）或桌宠服务内部改动，
 *     缓存就会读到过期值。共享偏好本来就在内存里，JSON 也很小，
 *     这点开销远小于"状态不一致"的代价。
 */
public final class AccessoryVm {

    /** 一帧完整的配件状态快照（不可变，渲染只依赖它）。 */
    public static final class Snapshot {
        /** 当前模型 URL（空 = 还没选模型）。 */
        public final String modelUrl;
        /** 当前模型全部可用配件名（顺序稳定，来自模型加载时探测的结果）。 */
        public final List<String> names;
        /** 已启用的配件名。 */
        public final Set<String> enabled;
        /** 名称译文（原名 -> 中文），只用于显示。 */
        public final Map<String, String> translations;
        /** 配件种类标签（原名 -> 显示部件/姿势动作/形状变化），只用于显示。 */
        public final Map<String, String> kinds;

        Snapshot(String modelUrl, List<String> names, Set<String> enabled,
                 Map<String, String> translations, Map<String, String> kinds) {
            this.modelUrl = modelUrl;
            this.names = Collections.unmodifiableList(names);
            this.enabled = Collections.unmodifiableSet(enabled);
            this.translations = Collections.unmodifiableMap(translations);
            this.kinds = Collections.unmodifiableMap(kinds);
        }

        public boolean isOn(String name) {
            return name != null && enabled.contains(name);
        }

        /** 没有可用配件（模型未加载 / 该模型没有配件）。 */
        public boolean isEmpty() { return names.isEmpty(); }

        public String translation(String name) {
            String v = (name == null) ? null : translations.get(name);
            return v == null ? "" : v;
        }

        public String kind(String name) {
            String v = (name == null) ? null : kinds.get(name);
            return v == null ? "" : v;
        }
    }

    private final PetStore store;

    public AccessoryVm(Context context) {
        this.store = new PetStore(context);
    }

    /** 当前模型 URL（每次都现读，模型可能在别的页面被切换）。 */
    private String currentModelUrl() {
        String u = store.appearance().mmdModelUrl;
        return (u == null) ? "" : u;
    }

    /**
     * 纯读：生成当前状态快照。**绝不会写任何数据**。
     * 渲染可以无限次调用它。
     */
    public Snapshot snapshot() {
        String url = currentModelUrl();
        List<String> names = new ArrayList<>();
        List<String> fromBridge = PetOverlayServiceBridge.accessoryNames();
        if (fromBridge != null) {
            for (String n : fromBridge) {
                if (n != null && !n.isEmpty()) names.add(n);
            }
        }

        Set<String> enabled = new LinkedHashSet<>();
        Map<String, String> translations = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        if (!url.isEmpty()) {
            for (String n : store.accessories(url)) {
                if (n != null && !n.isEmpty()) enabled.add(n);
            }
            Map<String, String> tr = store.translations();
            if (tr != null) translations.putAll(tr);
            for (String n : names) {
                String k = PetOverlayServiceBridge.accessoryKind(n);
                if (k != null && !k.isEmpty()) kinds.put(n, k);
            }
        }
        return new Snapshot(url, names, enabled, translations, kinds);
    }

    /** 单个配件开关（用户点击）。返回写入后的新快照。 */
    public Snapshot toggle(String name, boolean on) {
        String url = currentModelUrl();
        if (name == null || name.isEmpty() || url.isEmpty()) return snapshot();
        if (!PetOverlayServiceBridge.accessoryAvailable()) {
            /* 桌宠没在跑：没有任何"即时下发"的必要，直接落盘即可。
               下次模型加载时 PetOverlayService 会读这份名单并恢复。 */
            writeDirect(url, name, on);
            return snapshot();
        }
        /* 交给服务：它内部是"读-改-写 + commit()"一次完成（同步落盘），
           并且会立刻下发到 WebView。 */
        PetOverlayServiceBridge.setAccessory(name, on);
        return snapshot();
    }

    /**
     * 全部开启 / 全部关闭（一次原子写入）。
     *
     * ★ 不要循环调用 toggle()：那就是 §17.7 记录过的竞态（几十次读改写互相覆盖）。
     *   这里构造完整名单后【只写一次】。
     */
    public Snapshot setAll(boolean on) {
        String url = currentModelUrl();
        Snapshot s = snapshot();
        if (url.isEmpty() || s.isEmpty()) return s;

        List<String> list = new ArrayList<>();
        if (on) list.addAll(s.names);

        if (!PetOverlayServiceBridge.accessoryAvailable()) {
            store.saveAccessories(url, list);
            return snapshot();
        }
        /* 服务侧：JSON 一次性下发 + saveAccessories 一次 commit。 */
        PetOverlayServiceBridge.setAllAccessories(list);
        return snapshot();
    }

    /** 服务不可用时的直接落盘（读-改-写一次）。 */
    private void writeDirect(String url, String name, boolean on) {
        List<String> cur = new ArrayList<>(store.accessories(url));
        if (on) {
            if (!cur.contains(name)) cur.add(name);
        } else {
            cur.remove(name);
        }
        store.saveAccessories(url, cur);
    }

    /** 该名称是否正在翻译（只影响显示标记）。 */
    public boolean isTranslatingName(String name) {
        return PetOverlayServiceBridge.isTranslatingName(name);
    }

    /** 是否有翻译任务在进行（决定要不要轮询刷新）。 */
    public boolean translating() {
        return PetOverlayServiceBridge.isTranslating();
    }

    /** 打开本页时请求补齐译文（桌宠在跑才有意义）。 */
    public void requestTranslate() {
        PetOverlayServiceBridge.requestTranslate(false);
    }
}
