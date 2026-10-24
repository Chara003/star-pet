#!/bin/bash
# ============================================================================
# 星盏桌宠 me.rerere.pet —— 一键构建 + 生成部署清单
#
# 用法（在项目目录里跑）：
#   cd <项目目录> && ./deploy.sh              # 构建 + 生成清单
#   cd <项目目录> && ./deploy.sh --no-build   # 不构建，只刷新清单
#
# 它做的事：
#   1) 调用 build.sh（javac → aapt2 → d8 → zip → zipalign → apksigner）
#   2) 计算 build/pet.apk 的 md5
#   3) 把【APK 绝对路径 + md5】写进 build/deploy.txt
#   4) 打印一条可直接执行的设备侧命令（tools/device-deploy.sh）
#
# ★ 为什么要有这一步：
#   以前每次装机都是手工「pm install → 再 md5sum 比对 /data/app/.../base.apk」，
#   一共踩过 4 次「md5 不一致 = 装到旧包」。这里把期望 md5 落盘，
#   设备侧脚本读它做校验，不一致就直接报错退出，不再靠人眼看。
#
# ★ 纪律（血的教训）：
#   · 同一文件的多处修改必须串行（并行编辑会互相覆盖）
#   · 构建 → 装机 → 验证 也必须串行（并行会装错版本）
#   · 本脚本与 device-deploy.sh 都是串行的，不要并发调用
# ============================================================================
set -e
cd "$(dirname "$0")"
HERE=$PWD
APK=$HERE/build/pet.apk

# ★ APK 在【设备】上的真实路径。
#
#   这一步是给"在手机上构建并装机"的场景用的：某些手机终端 / proot 环境里，
#   项目目录本身就在设备的 app 私有目录下（pm install 可以直接读这个路径，
#   不必先拷到 /data/local/tmp）。
#
#   因为每台机器的目录都不一样，这里【不提供默认值】——
#   必须自己指定，否则脚本不知道怎么找到设备上的 APK：
#
#       export PET_DEV_ROOT=/data/user/0/<你的包名>/files/workspaces/<id>/files
#       ./deploy.sh
#
#   如果你只在电脑上构建、用 adb install 装机，那根本不需要跑这个脚本，
#   直接用 build.sh 就行。
: "${PET_DEV_ROOT:?请设置环境变量 PET_DEV_ROOT（设备上项目根目录，见本文件注释）}"
DEV_ROOT=$PET_DEV_ROOT
# ★ 项目目录名自动推导（不写死名字）：/path/to/pet → DEV_ROOT/pet
#   这样同一个项目的多个副本（pet / pet-re …）能各自独立构建、独立装机。
PROJ=$(basename "$HERE")
DEV_APK=$DEV_ROOT/$PROJ/build/pet.apk

if [ "$1" != "--no-build" ]; then
    echo "=== [1/2] 构建 ==="
    ./build.sh
else
    echo "=== [1/2] 跳过构建（--no-build）==="
fi

if [ ! -f "$APK" ]; then
    echo "!! 找不到 $APK"
    exit 1
fi

echo "=== [2/2] 生成部署清单 ==="
MD5=$(md5sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c%s "$APK")
{
    echo "$DEV_APK"
    echo "$MD5"
    echo "$SIZE"
    echo "$(date '+%Y-%m-%d %H:%M:%S')"
    echo "$APK"
} > "$HERE/build/deploy.txt"

echo "APK  : $APK"
echo "设备 : $DEV_APK"
echo "MD5  : $MD5"
echo "SIZE : $SIZE 字节"
echo
echo "→ 清单已写入 build/deploy.txt"
echo
echo "下一步（在设备侧跑，root）："
echo "  sh $DEV_ROOT/$PROJ/tools/device-deploy.sh"
echo
echo "  可选参数: [apk路径] [期望md5] [启动后等待秒数] [日志关键字]"
echo "  例：sh .../device-deploy.sh \"\" \"\" 15 \"约束闭包|Δq=\""
