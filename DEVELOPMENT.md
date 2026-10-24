# 开发笔记

给要改这个项目的人（包括下一个 AI）。这里只保留**通用的工程结论**，
不含任何个人环境、设备、密钥信息。

---

## 1. 代码结构

```
app/src/me/rerere/pet/
├── MainActivity.java          旧主页（已降级为"兜底宿主"，★ 禁止新增功能）
├── PetApp.java                Application：崩溃兜底、首启准备（复制预置动作）
├── core/
│   ├── PetContracts.java      动作语义、三区边界、图集常量
│   ├── CodexPetAtlas.java     精灵图集：单帧尺寸、列数、帧数自动探测
│   └── CodexPetImporter.java  .codex-pet.zip 导入（含 zip slip 防护）
├── data/
│   ├── PetStore.java          ★ 配置中枢（提供商/会话/记忆/外观/MCP/配件/翻译/别名）
│   ├── PetAiClient.java       OpenAI 兼容客户端（流式 SSE + function calling）
│   ├── TranslationService.java 名称翻译（多服务商 + 关思考回退 + 诊断日志）
│   ├── McpStore / McpClient / McpManager / BuiltinMcp   MCP 服务器与内置工具
│   ├── SearchProvider.java    联网搜索服务商
│   ├── LocationHelper.java    定位（不依赖 GMS）
│   ├── MmdModelManager.java   ★ 3D 模型导入 / 列举 / 删除
│   ├── VmdManager.java        ★ VMD 动作导入 / 列举 / 配对合并
│   └── PetOverlayServiceBridge.java   界面 ↔ 悬浮窗服务的轻量桥
└── ui/
    ├── MainTabsActivity.java  ★ 底栏四页宿主（主界面/高级设置/AI/界面）
    ├── page/                  各页内容（SettingsPage 构建器 + 每页一个类）
    ├── store/                 ViewModel（AppearanceVm / ModelLibraryVm / AccessoryVm）
    ├── widget/                自绘控件（TabBar / SwitchView2 / SeekRowView / …）
    ├── theme/                 颜色尺寸（UiTheme）/ 用户可调参数（UiSettings）
    ├── PetOverlayService.java ★ 悬浮窗服务（唯一真正"显示桌宠"的地方）
    ├── MmdPetView.java        ★ MMD 的 WebView 壳（JS 桥 + 命中测试 + 渲染进程兜底）
    ├── PetSpriteView.java     精灵图渲染
    └── PetDialogueController.java  对话面板（气泡 + 输入 + 流式显示）
```

前端全部逻辑在 **`app/assets/index.html`**（three.js 场景、模型加载、物理、描边、
表情、配件、动作播放、跨 WebView 外观同步）。它通过 `window.MmdBridge` 与 Java 通信。

---

## 2. 构建

```bash
export PET_KS_PASS='...'      # 密钥库口令（脚本里没有硬编码）
./build.sh                    # → build/pet.apk
```

关键约束（每条都踩过）：

| 约束 | 原因 |
|---|---|
| assets 必须在 `app/` 下用**相对路径** `assets/xxx` 打包 | 否则资源会落在 APK 根目录，`file:///android_asset/` 找不到 |
| `.wasm` / `.so` 必须 `zip -0`（STORED） | 压缩后无法加载 |
| `lib/*.jar` 必须**同时**进 `javac -cp` 和 `d8` 输入 | 只给 `-cp` 会运行期 `NoClassDefFoundError` |
| `javac` 用 `-cp $ANDROID_JAR`，不要用 `-bootclasspath` | 与 `-target 17` 冲突 |
| `d8` 输出必须是**目录** | 传 `.dex` 文件会报 Invalid output |
| javac 失败必须 `exit 1` | 否则会带着旧 class 出包，问题延后到装机才爆 |
| 构建后必须做**产物自检** | 曾出现"dex 静默失败、签名成功"，装机才发现 |
| 密钥库不进仓库 | 泄露 = 别人能签同包名覆盖安装。`build.sh` 缺 key 时**直接停止**并打印两种做法，绝不静默重新生成（那样每次构建签名都变，等于废掉"可覆盖安装"） |
| `SKIP_SIGN=1` 只为自检 | 产出 `build/pet-unsigned.apk`，**装不上**（Android 要求签名），不要拿它发布 |

### build.sh 里的 `2>/dev/null || true` 是危险写法

如果 `/dev/null` 本身不可访问（proot 环境损坏），命令根本没执行却报成功。
产物必须逐项确认存在，而不是相信退出码。

### 旧主页守卫

`MainActivity` 只剩兜底作用，`build.sh` 会跑 `tools/guard-main-activity.sh`，
检测到**新增方法或行数增长**就拒绝编译。

```bash
./build.sh -force              # 强制编译
./tools/guard-main-activity.sh --update   # 改完后同步基线
```

---

## 3. 关键实现要点

### 3.1 透明 WebView 叠在桌面上

- 窗口类型 `TYPE_APPLICATION_OVERLAY`，`PixelFormat.TRANSLUCENT`
- WebView 必须用**原始 Context（Activity/Service）**创建，不能用 applicationContext
- 透明要三层同时满足：DOM `background: transparent` + `setBackgroundColor(TRANSPARENT)`
  + `setLayerType(LAYER_TYPE_HARDWARE)`
- **必须实现 `onRenderProcessGone` 并返回 `true`**，否则渲染进程一死整个 App 被系统杀掉
- 悬浮窗不能用 `applicationContext` 建 `AlertDialog`（`BadTokenException`）

### 3.2 资源加载走 `WebViewAssetLoader`

`/models/<dir>/<file>` 映射到 app 私有目录，`/assets/...` 映射到 APK 内的 assets。
不要用 `file://` —— 会被同源策略和 `setAllowFileAccess` 的各种坑缠住。

### 3.3 相机取景（`FIT_RATIO`）

模型加载后自动取景一次。**取景参数必须在第一次取景之前就恢复**，
否则会出现"先按默认值取景、700ms 后又用存档值重新取景" → 用户看到"模型先大后小"。

判据：日志里出现两次 `camFit:` 且目标值不同，就是这个 bug。

### 3.4 命中测试（点击穿透）

悬浮窗是矩形，但模型是人形。`MmdPetView.hitTest()` 依次用：

1. 轮廓掩码（JS 侧把模型光栅化成 128×128 的位图上报）→ 精确到人形轮廓
2. 包围盒（掩码还没到时）
3. 都没有 → 放行（避免完全点不动）

★ 但"**压根没有模型**"必须和"模型正在加载"区分开：
前者一律 `return false`（不消费触摸），否则一块透明大窗会把桌面点击全吃掉。
这就是 `setModelMissing()` 存在的原因。

### 3.5 模型切换的竞态

`index.html` 里用 `_loadSeq` 序号防竞态：每次加载领一个号，`await` 回来若已被更新的
加载取代就自我放弃。没有它会出现 `Cannot read properties of null (reading 'traverse')`。

### 3.6 配置持久化

- **UI 不读控件值去保存**：禁止 `switch.isChecked() → store.save*`
- 初始化只渲染，不写盘；批量操作合并成**一次**写入
- 恢复设置期间必须**禁止保存**（`_restoring` / `_canSave`），
  否则"还没恢复到的字段"会用默认值把真配置覆盖掉
- 保存竞态三件套：`canSave` 闸门 + `restoring` 保护 + 超时兜底

### 3.7 自愈机制必须带熔断

任何"看门狗自动重载 WebView"都要有：**总次数上限 + 退避 + 用完彻底停手**。

事故实况：用 `pm install -r` 替换**运行中**的 base.apk → WebView 取不到 assets →
页面永远不 ready → 看门狗无限重载 → 每次重载新建一个 WebGL 上下文 →
几十秒耗尽整机 GPU → 所有 App 打开白屏，只能重启手机。

**推论：装机脚本必须先 force-stop 再 install，绝不能替换运行中的 base.apk。**

### 3.8 换方案后要删掉旧方案的昂贵计算

功能改用了新实现，但为旧实现服务的昂贵计算还留在调用链里，而且因为"功能看起来正常"
极难发现。典型症状：**心跳停止更新**（主线程被同步占满）。

判据：如果新方案"不需要某个数据"，就要把算那个数据的整条链路从热路径里摘掉，
不能只是"算了但不用"。

### 3.9 前端（index.html）里的几个硬坑

| 坑 | 现象 |
|---|---|
| `dt` 不封顶 | 卡顿恢复那一帧把物理算飞成 NaN |
| 半透明材质没设 `depthWrite=false` | 能看穿到下层 |
| 靠贴图 alpha 镂空却没 `alphaTest` | 出现黑色/灰色缺口 |
| `camera.aspect` 用来"贴合内容宽高比" | 与视口比例正反馈振荡，模型被拉伸 |
| 渲染循环写在有提前 `return` 的函数末尾 | 一帧都不跑（帧数 0、异常数 0） |
| `setAnimationLoop` 回调抛一次异常 | 之后再也不被调度 |
| `webglcontextlost` 没处理 | 画面永久定格（必须 `e.preventDefault()`） |

---

## 4. 调试方法论

### 4.1 同一个功能改 5 版都失败 → 停止推理，去加日志

最真实的一次：Android overlay 窗口抬升输入法，改了 5 版
（OnGlobalLayoutListener → 阈值 → 焦点 → 轮询 → 子 View insets）全部失败。
加一行日志立刻看到 `screenH=2568 visBottom=2720` ——
**visBottom 比屏幕还大**，说明该 API 对非 Activity 窗口早已弃用，方向从一开始就错了。

### 4.2 抓"值对了但画面不对"

给目标对象的方法装 wrapper 打调用栈，一眼看出"是谁、在哪一行、用什么值"覆盖的：

```js
const orig = material.color.setRGB.bind(material.color);
material.color.setRGB = function () {
  console.log(new Error().stack);
  return orig.apply(null, arguments);
};
```

### 4.3 `catch (e) {}` 里必须打日志

曾把 `toOutlineShade is not defined` 埋在空 catch 里，只检查颜色值没检查异常，
误判成「UV 坐标错了」，白改三版。

### 4.4 诊断代码必须默认关闭

留过一段"每 1.2s 把描边刷成红色"的自检代码，覆盖了正常结果，
导致用户看到"变红一下"、多排查两轮。诊断开关一律 `window.__xxx = false`。

### 4.5 改用户配置前必须问

看到某个参数值不像是默认值，先假定是用户自己调的，不要"顺手修正"。

### 4.6 装机验证要看 md5，不要看 `Success`

`pm install` 返回 `Success` 不代表装的是你刚构建的那个包。
唯一可靠的判据是：本地 `build/pet.apk` 的 md5 == 设备上 `base.apk` 的 md5。
`deploy.sh` + `tools/device-deploy.sh` 就是把这个判据做成了硬校验。

---

## 5. 改 UI 的注意事项

页面统一用 `ui/page/SettingsPage` 构建器，加一个设置项只要一行：

```java
page.section("分组");
page.toggle("标题", "说明", vm.snapshot().字段,
            (row, on) -> vm.commit(a -> a.字段 = on));
```

- 外观规范集中在 `ui/theme/UiTheme`（颜色/圆角/内边距）
- 用户可调参数在 `ui/theme/UiSettings`（强调色、卡片圆角、底栏手感）
- **不要用 `static final` 缓存颜色/圆角** —— 会在类加载时固化，用户改配置后不生效
- 自定义 View 只要可能被 XML 声明，就必须提供 `(Context, AttributeSet)` 构造器
- 同一个 View **不能** `addView` 两次（第二次会把它从原父容器移走）
- `String.join` / `List.copyOf` / `Map.of` 需要 API 26+，本项目 minSdk 24

底栏 `TabBar` 有三个曾导致严重故障的坑（白屏死循环 / 文字被 elevation 盖住 /
阴影被裁成方框），改它之前先看代码里那段注释。

---

## 6. 无素材时的行为（本仓库默认状态）

公开仓库不带任何模型与动作，所以这条路径必须永远可跑通：

| 位置 | 行为 |
|---|---|
| `index.html` | `MODEL_URL = ''` → `boot()` 提前返回，只显示「还没有 3D 模型」提示 |
| `MmdPetView.hitTest()` | `setModelMissing(true)` 时一律 `false`，**不消费触摸** |
| `PetSpriteView.hasContent()` | 无图集时不消费触摸 |
| `VmdManager.ensurePresets()` | `assets/vmd` 为空 → 直接标记完成，不报错 |
| `VmdManager.presetNames()` | `index.json` 是 `[]` → 返回空 map |
| `PetVmdActivity` | 动作库为空 → 显示「动作库是空的，点下面按钮导入」 |
| `PetMmdActivity` / `ModelPickerPage` | 模型列表为空 → 显示「还没有导入 3D 模型」 |

改这块要特别注意：**"没有素材"不是错误**，不该弹红框，也不该写 error 日志。

---

## 7. 发布前检查清单

```bash
# 1. 没有明文口令
grep -rn "pass:.*[0-9a-zA-Z]\{6,\}" build.sh deploy.sh tools/ || echo OK
# 2. 没有私有路径 / UUID
grep -rn "/data/user/0/\|/workspace/" --include=*.sh --include=*.java --include=*.js . || echo OK
# 3. 没有版权素材
ls app/assets/models 2>/dev/null; ls app/assets/vmd/*.vmd 2>/dev/null
# 4. 密钥库不会被提交（且发布目录里不应存在）
git check-ignore -v keys/pet.jks
ls keys/ 2>/dev/null && echo "⚠ keys/ 还存在，确认这是你自己的本地环境"
# 5. 能构建
PET_KS_PASS='...' ./build.sh
```
