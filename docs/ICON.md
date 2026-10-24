# 应用图标 / 如何换成自己的

> **本仓库不附带任何美术素材。** 现在用的是 `app/res/drawable/ic_launcher_placeholder.xml`
> ——一个用矢量路径画的抽象占位图（深青底 + 圆头 + 小底座），
> 不含任何第三方角色元素，可以直接用于任何用途。

---

## 为什么没有图标

原作者的图标是一张 AI 生成的图，但**图中角色设计另有版权归属**
（浅蓝发 + 光环 + 水手服 + 鱼形发饰的联想很具体）。
AI 生成不等于没有版权风险，随源码公开分发不太合适，所以换成了占位图。

---

## 换成自己的图标：三种方式

### 方式 A：改矢量图（最省事，不用准备多尺寸 PNG）

直接编辑 `app/res/drawable/ic_launcher_placeholder.xml`，改里面的
`android:pathData`。这是标准 SVG path 语法，可以用
[SVG Path Editor](https://yqnn.github.io/svg-path-editor/) 或 Figma / Illustrator
导出 path 后粘进去。

坐标系是 `viewportWidth/Height = 108×108`（Android 自适应图标的标准画布）。

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#2A6B6B" android:pathData="M0,0h108v108h-108z"/>
    <!-- 你的图形 -->
</vector>
```

优点：任意分辨率都清晰，文件不到 2KB。

### 方式 B：放 PNG（想要精细插画时用）

1. **自己建目录**（现在 `app/res/` 下没有 mipmap 目录，因为占位图是矢量的）并放图：

   ```bash
   mkdir -p app/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}
   ```

   | 目录 | 尺寸 | 用途 |
   |---|---|---|
   | `app/res/mipmap-mdpi/` | 48×48 | 低密度屏 |
   | `app/res/mipmap-hdpi/` | 72×72 | |
   | `app/res/mipmap-xhdpi/` | 96×96 | |
   | `app/res/mipmap-xxhdpi/` | 144×144 | 主流机型 |
   | `app/res/mipmap-xxxhdpi/` | 192×192 | 高分屏 |

   文件名统一 `ic_launcher.png`。

2. 改 manifest 指过去：

   ```xml
   <application
       android:icon="@mipmap/ic_launcher"
       android:roundIcon="@mipmap/ic_launcher"
       ...>
   ```

   如果只放一个尺寸，也可以把图放 `app/res/drawable/` 然后写
   `@drawable/my_icon`（系统会缩放，但可能糊）。

### 方式 C：自适应图标（Android 8.0+ 的圆形/异形裁切）

做两层 PNG 放进 `app/res/mipmap-*/`：

- `ic_launcher_background.png`（底色或底纹，108×108dp）
- `ic_launcher_foreground.png`（主体，注意留 **18dp 安全边距**，因为会被裁成圆形/水滴形）

然后建 `app/res/mipmap-anydpi-v26/ic_launcher.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
```

`ic_launcher_round.xml` 复制一份即可。manifest 仍写 `@mipmap/ic_launcher`。

---

## 检查清单

改完图标后：

```bash
# 1. 确认 manifest 指向的资源存在
grep -n "android:icon" app/AndroidManifest.xml

# 2. 编译（图标是资源，编译期就会报错，比装机后才发现好）
SKIP_SIGN=1 PET_KS_PASS=x ./build.sh

# 3. 确认图标进了包
unzip -l build/pet-unsigned.apk | grep -i "ic_launcher\|mipmap"
```

---

## 一个容易踩的坑

`aapt2 link` 用的 `--manifest app/AndroidManifest.xml`，
**图标路径写错在编译期就会失败**（`resource mipmap/xxx not found`），
不会拖到运行期。所以改完直接编译一次就能确认对不对，不用装机。
