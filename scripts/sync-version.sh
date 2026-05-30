#!/usr/bin/env bash
# ============================================================
# sync-version.sh — 版本号同步脚本
# 从 pom.xml 提取版本号，自动同步到 electron/package.json
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
POM="$PROJECT_ROOT/pom.xml"
PKG="$PROJECT_ROOT/electron/package.json"

# -------------------- 提取 pom.xml 版本号 --------------------
# 匹配 <version>1.2.3</version>（取第一个，即项目版本）
# 注意：macOS 自带 BSD grep 不支持 -P，用 sed 替代
VERSION=$(sed -n '/<version>/{s/.*<version>\(.*\)<\/version>.*/\1/p;q}' "$POM")

if [ -z "$VERSION" ]; then
    echo "❌ 无法从 pom.xml 提取版本号"
    exit 1
fi

echo "📦 当前版本: $VERSION"

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
