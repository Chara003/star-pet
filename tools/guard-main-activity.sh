#!/system/bin/sh
# 注意：本脚本只用 POSIX 语法（会被 sh 调用），不要用 <(...) / [[ ]] 等 bash 扩展。
# ============================================================================
# 旧主页（MainActivity）改动守卫
#
# 背景：UI 重构已把所有功能迁到新的 ui/page/* 页面，MainActivity 只保留为
#       "旧代码历史参考 + 兜底宿主"。它【禁止新增功能】。
#
# 为什么需要硬拦截：旧主页有 1600 行，任何"顺手在这里加个开关"的行为
#   都会让迁移成果倒退、让新旧两套 UI 长期并存。
#
# 判断依据：以 tools/main-activity.lock（行数 + 方法签名指纹）为基线，
#   只要出现【新增方法】或【行数增长】，就判定为"往旧主页加东西"→ 拒绝编译。
#
# 允许的改动（不算违规）：
#   · 删除方法 / 缩短代码（迁移走）
#   · 修改方法内部实现（行数不增长时）
#   · 纯注释/格式调整
#
# 强制编译（确实需要时）：
#   ./build.sh -force        或        FORCE_MAIN_ACTIVITY=1 ./deploy.sh
#   强制后应同步更新基线：./tools/guard-main-activity.sh --update
# ============================================================================
set -e
cd "$(dirname "$0")/.."
MAIN=app/src/me/rerere/pet/MainActivity.java
LOCK=tools/main-activity.lock

if [ ! -f "$MAIN" ]; then
    echo "!! 找不到 $MAIN"
    exit 1
fi

# 收集当前状态：行数 + 所有方法签名（排序后做指纹）
LINES=$(grep -c "" "$MAIN")
SIGS=$(grep -oE '^[[:space:]]*(public|private|protected|static|final|synchronized|abstract|native)[^;{]*\(' "$MAIN" \
        | sed 's/^[[:space:]]*//' | sort)

if [ "$1" = "--update" ]; then
    {
        echo "# MainActivity 基线（禁止新增功能，只允许删除/精简）"
        echo "# 由 tools/guard-main-activity.sh --update 生成"
        echo "LINES=$LINES"
        echo "---SIGS---"
        echo "$SIGS"
    } > "$LOCK"
    echo "✓ 基线已更新：$LOCK（行数 $LINES）"
    exit 0
fi

if [ ! -f "$LOCK" ]; then
    echo "!! 缺少基线文件 $LOCK，正在创建…"
    "$0" --update
    exit 0
fi

BASE_LINES=$(grep -E '^LINES=' "$LOCK" | cut -d= -f2)
BASE_SIGS=$(sed -n '/^---SIGS---$/,$p' "$LOCK" | tail -n +2)

# ---------- ① 新增方法？ ----------
# ★ 不用 bash 的 <(...) 进程替换：本脚本会被 sh 调用，而 dash/busybox sh
#   不支持进程替换，会报 "Syntax error: ( unexpected"（踩过）。
TMP1=$(mktemp); TMP2=$(mktemp)
echo "$BASE_SIGS" > "$TMP1"
echo "$SIGS" > "$TMP2"
ADDED=$(comm -13 "$TMP1" "$TMP2" 2>/dev/null || true)
rm -f "$TMP1" "$TMP2"
if [ -n "$ADDED" ]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  ✗ 编译已阻止：检测到往旧主页 MainActivity 里【新增方法】        ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "新增的方法："
    echo "$ADDED" | sed 's/^/    + /'
    echo ""
    echo "▸ 正确做法：把新功能写到 ui/page/*.java（配合 SettingsPage 构建器），"
    echo "  那里一行就能加一个开关："
    echo ""
    echo "      page.section(\"分组\");"
    echo "      page.toggle(\"标题\", \"说明\", 当前值, (row, on) -> vm.xxx(on));"
    echo ""
    echo "  详见 UI_KIT.md。"
    echo ""
    echo "▸ 确实必须改旧主页时（例如临时验证），用强制模式编译："
    echo "      ./build.sh -force"
    echo "      # 或  FORCE_MAIN_ACTIVITY=1 ./deploy.sh"
    echo "  强制编译后请同步基线，否则下次还会拦："
    echo "      ./tools/guard-main-activity.sh --update"
    echo ""
    exit 1
fi

# ---------- ② 行数增长？（允许不变或减少） ----------
if [ "$LINES" -gt "$BASE_LINES" ]; then
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  ✗ 编译已阻止：旧主页 MainActivity 行数增加了                    ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "  基线：$BASE_LINES 行"
    echo "  现在：$LINES 行  （+$((LINES - BASE_LINES))）"
    echo ""
    echo "  MainActivity 只允许【减少】（把功能迁到 ui/page/*）。"
    echo "  如果是删代码顺带整理了格式导致行数上升，或确实要临时加东西："
    echo "      ./build.sh -force"
    echo "      ./tools/guard-main-activity.sh --update"
    echo ""
    exit 1
fi

echo "✓ 旧主页守卫通过（$LINES / $BASE_LINES 行，无新增方法）"
