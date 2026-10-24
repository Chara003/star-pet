#!/system/bin/sh
# ============================================================================
# 星盏桌宠 me.rerere.pet —— 设备侧一键装机 + md5 校验 + 重启 + 抓日志
#
# ★ 跑在【设备】上（需要 root）。
#   某些精简的构建环境里没有 /system/bin/pm，所以「安装」这一半必须在设备侧执行。
#
# 用法（$PET_DEV_ROOT = 设备上项目根目录，先在 deploy.sh 那边设好）：
#   sh $PET_DEV_ROOT/<项目名>/tools/device-deploy.sh
#        → 读 ../build/deploy.txt（APK 路径 + 期望 md5），安装 → 校验 → 重启 → 抓日志
#
#   sh device-deploy.sh log            → 只重启 + 抓 10 秒日志（不装）
#   sh device-deploy.sh log 20         → 只重启 + 抓 20 秒日志
#   sh device-deploy.sh install        → 只安装 + 校验（不重启）
#   sh device-deploy.sh full 15 "Δq=|约束闭包"
#        → 安装 + 校验 + 重启 + 抓 15 秒、按关键字过滤
#
# ---- 为什么必须有它（踩过 4 次的坑）----
#   `pm install` 返回 Success **不代表装的是你刚构建的那个包**：
#   路径写错 / 旧包还在 / 构建没成功，都会让你"以为装了新的"。
#   唯一可靠的判据是：
#       本地 build/pet.apk 的 md5  ==  设备 /data/app/*/me.rerere.pet*/base.apk 的 md5
#   本脚本把这条判据做成【硬校验】：不一致就报错退出，绝不静默继续。
#
# ---- 纪律 ----
#   · 与 deploy.sh（构建侧）串行执行，不要并发
#   · 校验失败时先看 build.sh 有没有真的 `=== 完成 ===`，再看路径
# ============================================================================

SELF_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
TXT="$SELF_DIR/../build/deploy.txt"
PKG=me.rerere.pet
RESTART_LOG_TAG="PetMmd PetOverlayService"

MODE=full
WAIT=12
KEYW='约束闭包|刚体筛选|物理几何|Δq=|physics|Error|error'
APK=""
WANT=""

case "$1" in
    log)      MODE=log;     [ -n "$2" ] && WAIT=$2 ;;
    install)  MODE=install; [ -n "$3" ] && KEYW=$3 ;;
    full|"")  MODE=full;    [ -n "$2" ] && WAIT=$2; [ -n "$3" ] && KEYW=$3 ;;
    *)        APK=$1; WANT=$2; [ -n "$3" ] && WAIT=$3; [ -n "$4" ] && KEYW=$4 ;;
esac

say() { echo ">>> $*"; }
die() { echo "!!! $*"; exit 1; }

# ---------- 权限 ----------
[ "$(id -u)" = "0" ] || die "需要 root 执行（当前 uid=$(id -u)）"

# ---------- 从 deploy.txt 取 APK / 期望 md5 ----------
if [ -z "$APK" ] && [ "$MODE" != "log" ]; then
    [ -f "$TXT" ] || die "找不到部署清单 $TXT（先在构建侧跑 ./deploy.sh）"
    APK=$(sed -n 1p "$TXT")
    [ -z "$WANT" ] && WANT=$(sed -n 2p "$TXT")
    say "清单: $TXT"
fi

# ---------- 取设备上真正被装进去的 base.apk ----------
dev_apk() {
    ls -t /data/app/*/$PKG-*/base.apk 2>/dev/null | head -1
}

if [ "$MODE" != "log" ]; then
    [ -n "$APK" ] || die "APK 路径为空"
    [ -f "$APK" ] || die "APK 不存在: $APK"
    [ -n "$WANT" ] || die "缺少期望 md5（deploy.txt 第二行）"

    LOCAL_MD5=$(md5sum "$APK" | cut -d' ' -f1)
    say "APK     : $APK"
    say "本地MD5 : $LOCAL_MD5"
    say "期望MD5 : $WANT"
    if [ "$LOCAL_MD5" != "$WANT" ]; then
        die "★ 本地 APK 与清单不符（deploy.txt 过期？重跑 ./deploy.sh）"
    fi

    # ---------- ★ 先停进程，再安装 ----------
    # ---- 为什么必须先停（2026-10-17 整机白屏事故）----
    # 在**进程还活着**的时候替换 base.apk，运行中的 WebView 会拿着旧 APK 的
    # 资源路径去加载 assets → 永远取不到 → 页面空跑。
    # 我们的原生看门狗见状会不停"重载页面"，每个新页面都建一个 WebGL 上下文，
    # 几十秒就能把整机 GPU 资源吃光 → **所有 App 打开都是白屏，只能重启手机**。
    # 所以：force-stop → kill -9 → 等 1 秒 → 再装。
    say "先停掉运行中的进程（避免替换运行中的 base.apk）…"
    am force-stop $PKG >/dev/null 2>&1
    P=$(pidof $PKG)
    if [ -n "$P" ]; then kill -9 $P 2>/dev/null; fi
    sleep 1
    P=$(pidof $PKG)
    if [ -n "$P" ]; then
        say "进程仍在（pid=$P），再等 2 秒"
        sleep 2
    fi

    # ---------- 安装 ----------
    say "安装中…"
    OUT=$(pm install -r "$APK" 2>&1)
    echo "$OUT" | tail -3
    case "$OUT" in
        *Success*) ;;
        *)
            # 兜底：个别路径 pm 读不到 → 先拷到 /data/local/tmp 再装
            say "直接安装失败，回退：拷到 /data/local/tmp 再装"
            cp -f "$APK" /data/local/tmp/pet_deploy.apk || die "拷贝失败"
            chmod 644 /data/local/tmp/pet_deploy.apk
            OUT=$(pm install -r /data/local/tmp/pet_deploy.apk 2>&1)
            echo "$OUT" | tail -3
            echo "$OUT" | grep -q Success || die "安装失败（见上面的输出）"
            ;;
    esac

    # ---------- ★ 硬校验：设备上的 md5 必须等于本地 ----------
    say "校验设备上的 base.apk…"
    i=0; GOT=""; ok=0
    while [ $i -lt 8 ]; do
        D=$(dev_apk)
        if [ -n "$D" ]; then
            GOT=$(md5sum "$D" | cut -d' ' -f1)
            [ "$GOT" = "$WANT" ] && { ok=1; break; }
        fi
        sleep 1
        i=$((i+1))
    done
    if [ "$ok" != "1" ]; then
        echo
        echo "  本地 : $WANT"
        echo "  设备 : $GOT"
        echo "  路径 : $(dev_apk)"
        die "★ md5 不一致 —— 装的不是刚构建的包（这就是那个踩了 4 次的坑）"
    fi
    say "✓ md5 一致: $GOT"
fi

# ---------- 重启 ----------
if [ "$MODE" != "install" ]; then
    say "重启 $PKG…"
    am force-stop $PKG >/dev/null 2>&1
    P=$(pidof $PKG)
    [ -n "$P" ] && { kill -9 $P 2>/dev/null; sleep 1; }
    logcat -c 2>/dev/null
    monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 1
    NP=$(pidof $PKG)
    if [ -z "$NP" ]; then
        die "进程没起来（monkey 启动失败）"
    fi
    say "已启动 pid=$NP，等 ${WAIT}s 收日志…"
    sleep "$WAIT"
fi

# ---------- 日志 ----------
echo
echo "================ $RESTART_LOG_TAG ================"
logcat -d -s $RESTART_LOG_TAG 2>/dev/null | grep -E "$KEYW" | tail -60
echo "================================================="
