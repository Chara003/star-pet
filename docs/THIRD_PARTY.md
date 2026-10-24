# 第三方组件与许可

本项目的**应用代码**（`app/src/`、`app/res/`、构建脚本）以 MIT 许可发布（见根目录 `LICENSE`）。
下面是它依赖的第三方组件，各自遵循原作者许可。

---

## 1. 前端运行时（`app/assets/lib/`）

| 组件 | 版本 | 许可 | 来源 |
|---|---|---|---|
| three.js | r150（`REVISION = '150'`） | MIT | https://github.com/mrdoob/three.js |
| MMDLoader.js | 随 three.js examples | MIT | three.js `examples/jsm/loaders/MMDLoader.js` |
| MMDAnimationHelper.js | 随 three.js examples | MIT | three.js `examples/jsm/animation/MMDAnimationHelper.js` |
| MMDPhysics.js | 随 three.js examples | MIT | three.js `examples/jsm/animation/MMDPhysics.js` |
| CCDIKSolver.js | 随 three.js examples | MIT | three.js `examples/jsm/animation/CCDIKSolver.js` |
| GLTFLoader.js | 随 three.js examples | MIT | three.js `examples/jsm/loaders/GLTFLoader.js` |
| TGALoader.js | 随 three.js examples | MIT | three.js `examples/jsm/loaders/TGALoader.js` |
| BufferGeometryUtils.js | 随 three.js examples | MIT | three.js `examples/jsm/utils/BufferGeometryUtils.js` |
| MMDToonShader.js | 随 three.js examples | MIT | three.js `examples/jsm/shaders/MMDToonShader.js` |
| AmmoPhysics.js | 随 three.js examples | zlib | three.js `examples/jsm/physics/AmmoPhysics.js`（是 Ammo.js 的封装） |
| mmdparser.module.js | 随 three.js examples | MIT | three.js `examples/jsm/libs/mmdparser.module.js` |
| ammo.wasm.js / ammo.wasm.wasm | Ammo.js（Bullet 物理引擎的 Emscripten 构建） | **zlib** | https://github.com/kripken/ammo.js |

> ⚠ **关于 MMDLoader / mmdparser**：它们解析的是 **MMD 文件格式**（PMX/PMD/VMD），
> 代码本身是 MIT。仓库里**不包含任何模型或动作文件**（见 README 的素材说明）。

---

## 2. Android 侧依赖（`lib/*.jar`）

| 文件 | 内容 | 许可 | 说明 |
|---|---|---|---|
| `webkit-1.8.0-classes.jar` | `androidx.webkit`（剥离出的 classes） | Apache-2.0 | AndroidX 项目。本项目用它做 `WebViewAssetLoader` / `WebViewCompat` 的低版本兼容 |
| `core-1.12.0-classes.jar` | `androidx.core`（剥离出的 classes） | Apache-2.0 | AndroidX 项目，`webkit` 的传递依赖 |
| `annotation-1.5.0.jar` | `androidx.annotation` | Apache-2.0 | AndroidX 项目 |

> 这三个 jar 是从 AAR 里剥出的 classes（没有 `META-INF` 里的许可声明）。
> 它们的许可证随 AndroidX 原项目，均为 Apache License 2.0。
> 如果你要重新分发，建议按 Apache-2.0 的要求附上 NOTICE 文本：
> https://www.apache.org/licenses/LICENSE-2.0

---

## 3. 构建工具（不随源码分发）

`aapt2`、`zipalign`、`apksigner`（Android SDK，Apache-2.0）、
`d8`/`r8`（`r8.jar`，BSD-3-Clause）、`javac`（GPLv2+CE）。
这些只是构建期依赖，仓库里不包含，也不需要提交。

---

## 4. 应用图标

**本仓库不附带图标素材。** 用的是 `app/res/drawable/ic_launcher_placeholder.xml`
——一个用矢量路径画的抽象占位图（深青底 + 圆头 + 小底座 + 两个点），
纯代码绘制，不含任何第三方角色元素，随 MIT 许可一起发布。

换成自己的图标见 [`ICON.md`](ICON.md)。
