#!/usr/bin/env bash
# ============================================================
# sync-version.sh — 版本号同步脚本
# 用法: sync-version.sh [版本号]
#   - 有参数：直接使用传入的版本号（CI 环境推荐，CI 传 tag 名去掉 v 前缀）
#   - 无参数：从 pom.xml 自动提取（本地开发用）
#
# 同步范围：
#   ① electron/package.json          version + extraResources 的 jar 路径
#   ② electron/package-lock.json     顶层 version（前两处）
#   ③ frontend/package.json          version
#   ④ frontend/package-lock.json      顶层 version（前两处）
#   ⑤ frontend-v3/package.json       version
#   ⑥ frontend-v3/package-lock.json   顶层 version（前两处）
#
# 注意：src/main/java/.../CodeCraftMcpServer.SERVER_VERSION 是 Java 常量，
#      不在本脚本范围内，升版本时需手工同步（见 CHANGELOG）。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
POM="$PROJECT_ROOT/pom.xml"
PKG="$PROJECT_ROOT/electron/package.json"
PKG_LOCK="$PROJECT_ROOT/electron/package-lock.json"
FE_PKG="$PROJECT_ROOT/frontend/package.json"
FE_LOCK="$PROJECT_ROOT/frontend/package-lock.json"
FE3_PKG="$PROJECT_ROOT/frontend-v3/package.json"
FE3_LOCK="$PROJECT_ROOT/frontend-v3/package-lock.json"

# -------------------- 获取版本号 --------------------
if [ -n "${1:-}" ]; then
    # CI 环境：直接使用传入的版本号（最可靠）
    VERSION="$1"
    echo "📦 使用传入版本: $VERSION"
else
    # 本地开发：从 pom.xml 提取项目版本（跳过 parent 中的 version）
    # 策略：找 <artifactId>code-craft</artifactId> 后面的 <version>
    # 注意 artifactId 是 "code-craft"（带连字符），不是 "codecraft"
    VERSION=$(sed -n '/<artifactId>code-craft<\/artifactId>/{n;s/.*<version>\(.*\)<\/version>.*/\1/p;q;}' "$POM" | tr -d '\r')
    if [ -z "$VERSION" ]; then
        echo "❌ 无法从 pom.xml 提取版本号（找不到 <artifactId>code-craft</artifactId> 后的 <version>）"
        exit 1
    fi
    echo "📦 从 pom.xml 提取版本: $VERSION"
fi

# -------------------- 跨平台 sed -i --------------------
if [[ "$OSTYPE" == "darwin"* ]]; then
    SED_INPLACE=(sed -i '')
else
    SED_INPLACE=(sed -i)
fi

# -------------------- ① electron/package.json --------------------
# 更新顶层 "version" 字段
"${SED_INPLACE[@]}" -E 's/"version": "[0-9]+\.[0-9]+\.[0-9]+"/"version": "'"$VERSION"'"/' "$PKG"
# 更新 extraResources 中的 JAR 路径（code-craft-x.x.x.jar → code-craft-新版本.jar）
"${SED_INPLACE[@]}" -E 's/code-craft-[0-9]+\.[0-9]+\.[0-9]+\.jar/code-craft-'"$VERSION"'.jar/g' "$PKG"
echo "✅ electron/package.json: version=$VERSION, JAR=code-craft-$VERSION.jar"

# -------------------- ②~⑥ 其余 package.json / lock --------------------
# 只改「顶层 version」：package.json 只有一处顶层 version 形如 `"version": "x.y.z",`；
# lock 文件前两处分别是根节点 version 与 packages[""].version。
for LOCK in "$PKG_LOCK" "$FE_LOCK" "$FE3_LOCK"; do
    [ -f "$LOCK" ] || continue
    # 仅替换前两处（其余是 node_modules 里各依赖的版本，不能动）
    awk -v ver="$VERSION" '
        { if (n < 2 && $0 ~ /^[[:space:]]*"version": "[0-9]+\.[0-9]+\.[0-9]+",?$/) {
              sub(/"version": "[0-9]+\.[0-9]+\.[0-9]+"/, "\"version\": \"" ver "\""); n++
          } print }
    ' "$LOCK" > "$LOCK.tmp" && mv "$LOCK.tmp" "$LOCK"
    echo "✅ $(basename "$(dirname "$LOCK")")/$(basename "$LOCK"): 顶层 version -> $VERSION"
done

for P in "$FE_PKG" "$FE3_PKG"; do
    [ -f "$P" ] || continue
    "${SED_INPLACE[@]}" -E 's/"version": "[0-9]+\.[0-9]+\.[0-9]+"/"version": "'"$VERSION"'"/' "$P"
    echo "✅ $(basename "$(dirname "$P")")/$(basename "$P"): version -> $VERSION"
done

echo ""
echo "⚠️  仍需手工同步："
echo "   - src/main/java/com/example/agentdeepseek/mcp/server/CodeCraftMcpServer.java 的 SERVER_VERSION"
echo "   - README(_EN) 版本徽章 / docs 版本标注"
echo ""
echo "  建议 git diff 确认变更"
