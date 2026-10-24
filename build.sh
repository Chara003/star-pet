#!/bin/bash
# AI 桌宠 me.rerere.pet 构建脚本
# 工具链: javac 17 -> aapt2 -> d8(r8.jar) -> zip(assets+dex) -> zipalign -> apksigner
#
# MMD 相关注意（踩坑记录见 DEVELOPMENT.md）:
#   坑#19/#20: lib/*.jar 必须【同时】进 javac classpath 和 d8 输入，
#              只给 -cp 会导致运行期 NoClassDefFoundError
#   坑#23    : 打包 assets 要用相对路径 assets/xxx，否则资源落在 APK 根目录
#   坑#24    : .wasm/.so 必须 zip -0（STORED），否则无法加载
set -e
cd "$(dirname "$0")"

# ★★ 密钥库口令从环境变量读，不写进脚本 ★★
#   仓库是公开的，任何写死的口令等于把签名密钥一起送人
#   （别人就能签一个同包名、能覆盖安装的假包）。
#   用法：
#       export PET_KS_PASS='你的密钥库口令'
#       ./build.sh
#   放在最前面是为了【快速失败】—— 别等编译完 2 分钟才说没口令。
: "${PET_KS_PASS:?请先设置环境变量 PET_KS_PASS（密钥库口令）：export PET_KS_PASS='...'}"

# ★ 工具链路径都可以用环境变量覆盖（不同机器上 Android SDK / r8.jar 位置不一样）：
#     ANDROID_JAR=/path/to/android-33/android.jar ./build.sh
#     R8_JAR=/path/to/r8.jar ./build.sh
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-33/android.jar}

# r8.jar（含 d8）的位置各人不同，按顺序找第一个存在的：
#   ① 环境变量 R8_JAR   ② 项目根目录的 ./r8.jar   ③ SDK build-tools 里的 d8.jar
if [ -n "$R8_JAR" ]; then
    R8=$R8_JAR
elif [ -f ./r8.jar ]; then
    R8=./r8.jar
else
    # 在 Android SDK 的 build-tools 里找 d8.jar（各版本路径不同，用通配）
    R8=""
    for c in "${ANDROID_HOME:-/usr/lib/android-sdk}"/build-tools/*/lib/d8.jar \
             "${ANDROID_SDK_ROOT:-/usr/lib/android-sdk}"/build-tools/*/lib/d8.jar; do
        if [ -f "$c" ]; then R8=$c; break; fi
    done
    [ -z "$R8" ] && R8=./r8.jar
fi
if [ ! -f "$R8" ]; then
    echo "!! 找不到 r8.jar（d8 在它里面）：$R8"
    echo "   下载：https://maven.google.com/web/index.html → com.android.tools → r8"
    echo "   或直接用 Android SDK build-tools 里的 d8"
    echo "   然后：export R8_JAR=/path/to/r8.jar"
    exit 1
fi
BUILD=build
# ★ 密钥库必须放在【不会被清理】的目录！
#   原来写在 build/pet.jks，而脚本开头会 rm -rf build ——
#   于是每次构建都重新生成一个新密钥，导致【每次签名都不同】，
#   用户无法覆盖安装（报"签名不一致"）。
#   现在放 keys/ 并加保护：缺失时直接报错而不是静默重新生成。
KS=keys/pet.jks

# ★★★ 旧主页守卫（编译前硬拦截）★★★
#   MainActivity 已只剩"旧代码参考 + 兜底宿主"，禁止往里加功能。
#   一旦检测到新增方法或行数增长，直接拒绝编译，并提示正确做法。
#   强制编译：./build.sh -force   （或 FORCE_MAIN_ACTIVITY=1 ./build.sh）
if [ "$1" != "-force" ] && [ "$FORCE_MAIN_ACTIVITY" != "1" ]; then
    sh tools/guard-main-activity.sh || exit 1
else
    echo "⚠ 已跳过旧主页守卫（-force / FORCE_MAIN_ACTIVITY=1）"
    echo "  改完记得同步基线：./tools/guard-main-activity.sh --update"
fi

echo "[1/7] 清理"
rm -rf $BUILD
mkdir -p $BUILD/c $BUILD/gen $BUILD/dex $BUILD/res

echo "[2/7] 编译资源"
aapt2 compile --dir app/res -o $BUILD/res.zip
aapt2 link -o $BUILD/base.apk \
    -I $ANDROID_JAR \
    --manifest app/AndroidManifest.xml \
    --java $BUILD/gen \
    --min-sdk-version 24 --target-sdk-version 35 \
    $BUILD/res.zip

# MMD 依赖 jar（webkit + core），javac 与 d8 都要用
JARS=""
if [ -d lib ]; then
    for j in lib/*.jar; do
        [ -f "$j" ] && JARS="$JARS:$j"
    done
fi

echo "[3/7] 编译 Java"
find app/src -name "*.java" > $BUILD/sources.txt
find $BUILD/gen -name "*.java" >> $BUILD/sources.txt
if ! javac -nowarn -source 17 -target 17 -encoding UTF-8 \
    -cp "$ANDROID_JAR$JARS" -d $BUILD/c @$BUILD/sources.txt 2> $BUILD/javac.log; then
    echo "!! javac FAILED:"
    grep -E "error:|错误:" $BUILD/javac.log | head -40
    exit 1
fi
grep -E "warning:" $BUILD/javac.log | head -5 || true

echo "[4/7] 转 dex"
# 坑#19/#20: jar 里的 class 必须一起进 dex，仅 -cp 不够
D8_INPUTS="$(find $BUILD/c -name '*.class')"
if [ -n "$JARS" ]; then
    # 展开所有 jar 路径
    JARLIST=$(echo "$JARS" | tr ':' '\n' | grep -v '^$')
    D8_INPUTS="$D8_INPUTS $JARLIST"
fi
java -cp $R8 com.android.tools.r8.D8 --release --min-api 24 \
    --lib $ANDROID_JAR --output $BUILD/dex \
    $D8_INPUTS

echo "[5/7] 打包 dex"
cp $BUILD/base.apk $BUILD/unsigned.apk
# ★★★ 不要用 `2>/dev/null`（2026-10-17 事故）★★★
#   proot 环境的 /dev/null 一旦权限异常，`2>/dev/null` 本身会失败，
#   再叠加 `|| true` → **zip 静默不执行**，APK 里没有 classes.dex，
#   而后面签名/对齐全部"成功"。装机时才暴露：
#       INSTALL_FAILED_INVALID_APK: Scanning Failed.: code is missing
#   症状更迷惑的是 APK 体积只小了 60 万字节，肉眼很难发现。
#   → 现在：不重定向 + 不吞错 + 打包后逐项校验（见下面的产物自检）。
if [ ! -f "$BUILD/dex/classes.dex" ]; then
    echo "!! 没有 dex 产物（$BUILD/dex/classes.dex 不存在），d8 那步失败了？"
    exit 1
fi
(cd $BUILD/dex && zip -q ../unsigned.apk classes.dex)
# 可能有多个 dex（jar 依赖较多时）
for d in $BUILD/dex/classes*.dex; do
    [ -f "$d" ] || continue
    bn=$(basename "$d")
    if [ "$bn" != "classes.dex" ]; then
        (cd $BUILD/dex && zip -q ../unsigned.apk "$bn")
    fi
done

echo "[6/7] 打包 assets"
if [ -d app/assets ]; then
    cnt=0
    # 坑#23: 在 app/ 目录下用相对路径 assets/xxx 打包，
    #         否则文件会落在 APK 根目录而不是 assets/ 下
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        case "$f" in
            *.wasm|*.so|*.woff|*.woff2)
                # 坑#24: 这些必须 STORED，不能压缩
                (cd app && zip -0 -q "../$BUILD/unsigned.apk" "$f")
                ;;
            *)
                (cd app && zip -q "../$BUILD/unsigned.apk" "$f")
                ;;
        esac
        cnt=$((cnt + 1))
    done < <(cd app && find assets -type f | sort)
    echo "    assets: $cnt 个文件"
else
    echo "    (无 assets)"
fi

echo "[7/7] 对齐 + 签名"
zipalign -f 4 $BUILD/unsigned.apk $BUILD/aligned.apk

# ★ SKIP_SIGN=1：只对齐、不签名。
#   用途：编译自检（确认能编过）、看产物结构。出来的 APK【装不上】，
#   因为 Android 要求 APK 必须签名才能安装。
if [ "$SKIP_SIGN" = "1" ]; then
    cp -f $BUILD/aligned.apk $BUILD/pet-unsigned.apk
    echo "   ⚠ 已跳过签名（SKIP_SIGN=1）→ $BUILD/pet-unsigned.apk"
    echo "     这个包不能安装，只能用来检查产物内容。"
    APK=$BUILD/pet-unsigned.apk
else

if [ ! -f "$KS" ]; then
    echo
    echo "!! 找不到密钥库：$KS"
    echo
    echo "   本仓库【不包含】签名密钥（.gitignore 已排除，这是有意的）。"
    echo "   原因：密钥一旦泄露，任何人都能签出同包名的「官方更新包」覆盖安装。"
    echo
    echo "   ① 已经发布过 APK（有备份）：把那个 pet.jks 放回 keys/ 目录。"
    echo "      ★ 必须用原来那一把 —— 换钥匙老用户就无法覆盖安装。"
    echo
    echo "   ② 第一次构建（从没发布过）：自己生成一把，然后【立刻备份到别处】："
    echo "      mkdir -p keys"
    echo "      keytool -genkeypair -v -keystore keys/pet.jks -alias pet \\"
    echo "              -keyalg RSA -keysize 2048 -validity 10000 \\"
    echo "              -storepass \"\$PET_KS_PASS\" -keypass \"\$PET_KS_PASS\" \\"
    echo "              -dname \"CN=StarLamp Pet\""
    echo
    echo "   ③ 只想确认「能不能编过」："
    echo "      SKIP_SIGN=1 PET_KS_PASS=x ./build.sh"
    echo
    exit 1
fi

apksigner sign --ks "$KS" --ks-pass "pass:$PET_KS_PASS" --key-pass "pass:$PET_KS_PASS" \
    --out $BUILD/pet.apk $BUILD/aligned.apk
apksigner verify $BUILD/pet.apk
APK=$BUILD/pet.apk

fi

# ★★★ 产物自检：把"构建成功但内容不全"变成硬错误 ★★★
#   以前的坑：dex 那步静默失败后，签名/对齐都报成功，直到装机才炸。
need_fail=0
for e in classes.dex AndroidManifest.xml assets/index.html; do
    if unzip -l "$APK" | grep -q " $e\$"; then
        echo "  ✓ $e"
    else
        echo "  ✗ 缺少 $e  ← 这个 APK 装机必失败"
        need_fail=1
    fi
done
if [ "$need_fail" != "0" ]; then
    echo "!! 产物不完整，已中止（不要拿这个 APK 去装机）"
    exit 1
fi
echo "=== 完成: $APK ($(stat -c%s "$APK") 字节) ==="
