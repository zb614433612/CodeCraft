#!/usr/bin/env bash
# ============================================================
# sync-version.sh — 版本号同步脚本
# 用法: sync-version.sh [版本号]
#   - 有参数：直接使用传入的版本号（CI 环境推荐）
#   - 无参数：从 pom.xml 自动提取（本地开发用）
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
POM="$PROJECT_ROOT/pom.xml"
PKG="$PROJECT_ROOT/electron/package.json"

# -------------------- 获取版本号 --------------------
if [ -n "${1:-}" ]; then
    # CI 环境：直接使用传入的版本号（最可靠）
    VERSION="$1"
    echo "📦 使用传入版本: $VERSION"
else
    # 本地开发：从 pom.xml 提取项目版本（跳过 parent 中的 version）
    # 策略：找 <artifactId>codecraft</artifactId> 后面的 <version>
    VERSION=$(sed -n '/<artifactId>codecraft<\/artifactId>/{n;s/.*<version>\(.*\)<\/version>.*/\1/p;q;}' "$POM")
    if [ -z "$VERSION" ]; then
        echo "❌ 无法从 pom.xml 提取版本号"
        exit 1
    fi
    echo "📦 从 pom.xml 提取版本: $VERSION"
fi

# -------------------- 更新 package.json --------------------
# ① 更新顶层的 "version" 字段
sed -i.bak -E 's/"version": "[0-9]+\.[0-9]+\.[0-9]+"/"version": "'"$VERSION"'"/' "$PKG"

# ② 更新 extraResources 中的 JAR 路径（codecraft-x.x.x.jar → codecraft-新版本.jar）
sed -i.bak -E 's/codecraft-[0-9]+\.[0-9]+\.[0-9]+\.jar/codecraft-'"$VERSION"'.jar/g' "$PKG"

# 清理备份文件
rm -f "$PKG.bak"

echo "✅ package.json 已同步: version=$VERSION, JAR=codecraft-$VERSION.jar"
echo ""
echo "  建议 git diff electron/package.json 确认变更"
