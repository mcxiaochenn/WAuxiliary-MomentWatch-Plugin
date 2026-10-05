#!/usr/bin/env bash
# ============================================================
# 插件静态与逻辑验证脚本（不依赖真机）
#
# 用法:
#   bash tools/verify/run.sh
#
# 前置条件:
#   - JDK（javac + java，8 及以上）
#   - Python 3
#   - 首次运行会自动从 GitHub Releases 下载 bsh-3.0.0b1.jar
#     （也可用环境变量 BSH_JAR 指向本地已有的 BeanShell jar）
#
# 覆盖范围:
#   1) 解析校验：用 BeanShell 解析器逐个解析插件脚本，确认语法与 WA 运行时一致
#   2) 逻辑校验：以桩类替代 Android / WA 接口，在本地真实执行
#      「工具函数 / 配置读写 / 命中判定 / 去重 / 时效 / 参数解析 / 跳转组装 / Hook 生命周期」
#   说明：涉及真实 Android UI 的模块（Notifier / SettingsUi）只做解析校验。
# ============================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PLUGIN="$ROOT/plugins/v127/mcxiaochen/MomentWatch"
BSH_VER="3.0.0b1"
BSH_JAR="${BSH_JAR:-$HERE/bsh-$BSH_VER.jar}"
BSH_URL="https://github.com/beanshell/beanshell/releases/download/$BSH_VER/bsh-$BSH_VER.jar"

OUT="$HERE/out"
LINT_OUT="$OUT/lint"
STUB_OUT="$OUT/stubs"

# Windows(Git Bash) 下 Java 用 ; 分隔，其它平台用 :
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CP_SEP=';' ;;
    *)                    CP_SEP=':' ;;
esac

# Python 在 Windows 上不认 /d/... 这类 MSYS 路径，需要转成原生路径
winpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

echo "==> 插件目录: $PLUGIN"
if [ ! -d "$PLUGIN" ]; then
    echo "错误: 插件目录不存在（插件运行部分可能尚未释放）"
    exit 1
fi

if [ ! -f "$BSH_JAR" ]; then
    echo "==> 下载 BeanShell $BSH_VER"
    curl -sSL -o "$BSH_JAR" "$BSH_URL"
fi

rm -rf "$OUT"
mkdir -p "$LINT_OUT" "$STUB_OUT"

echo "==> 编译解析校验工具"
javac -encoding UTF-8 -cp "$BSH_JAR" -d "$LINT_OUT" "$HERE/BshCheck.java"

FILES=(
    "$PLUGIN/main.java"
    "$PLUGIN/src/Util.java"
    "$PLUGIN/src/Config.java"
    "$PLUGIN/src/Jumper.java"
    "$PLUGIN/src/Notifier.java"
    "$PLUGIN/src/SnsHook.java"
    "$PLUGIN/src/SettingsUi.java"
    "$PLUGIN/src/Service.java"
)

echo
echo "==> [1/2] BeanShell 解析校验"
LINT_FILES=()
for f in "${FILES[@]}"; do LINT_FILES+=("$(winpath "$f")"); done
java -Dfile.encoding=UTF-8 -cp "$(winpath "$BSH_JAR")$CP_SEP$(winpath "$LINT_OUT")" BshCheck "${LINT_FILES[@]}"

echo
echo "==> 编译测试桩"
STUB_SRC=$(find "$HERE/stubs" -name '*.java')
javac -encoding UTF-8 -d "$STUB_OUT" $STUB_SRC

echo "==> 拼装测试脚本"
python "$(winpath "$HERE/assemble.py")" "$(winpath "$PLUGIN")" "$(winpath "$HERE")"

echo
echo "==> [2/2] 核心逻辑校验"
java -Dfile.encoding=UTF-8 -cp "$(winpath "$BSH_JAR")$CP_SEP$(winpath "$STUB_OUT")" bsh.Interpreter "$(winpath "$HERE/combined.bsh")"
