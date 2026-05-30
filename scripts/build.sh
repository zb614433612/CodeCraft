#!/usr/bin/env bash
# ============================================================
# build.sh — CodeCraft 一键构建打包脚本 (Linux / macOS)
# 用法: ./scripts/build.sh [版本号]  (版本号可选，默认从 pom.xml 读取)
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ELECTRON_DIR="$PROJECT_ROOT/electron"
RELEASE_DIR="$ELECTRON_DIR/release"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ==================== Step 0: 检测 OS ====================
OS="$(uname -s)"
case "$OS" in
    Darwin)  PLATFORM="mac"  ;;
    Linux)   PLATFORM="linux";;
    *)       log_error "不支持的操作系统: $OS"; exit 1 ;;
esac
log_info "检测到平台: $PLATFORM"

# ==================== Step 1: 版本号 ====================
if [ $# -ge 1 ]; then
    VERSION="$1"
    # 去掉 v 前缀
    VERSION="${VERSION#v}"
    log_info "使用指定版本: $VERSION"
    # 更新 pom.xml 版本号
    sed -i.bak -E 's|<version>[0-9]+\.[0-9]+\.[0-9]+</version>|<version>'"$VERSION"'</version>|' "$PROJECT_ROOT/pom.xml"
    rm -f "$PROJECT_ROOT/pom.xml.bak"
else
    VERSION=$(grep -oPm1 '<version>\K[^<]+' "$PROJECT_ROOT/pom.xml" | head -1)
    log_info "从 pom.xml 读取版本: $VERSION"
fi

# ==================== Step 2: 版本同步 ====================
log_info "Step 2/6: 同步版本号到 package.json..."
bash "$SCRIPT_DIR/sync-version.sh"

# ==================== Step 3: Maven 构建 ====================
log_info "Step 3/6: Maven 构建后端 JAR..."
cd "$PROJECT_ROOT"
mvn clean package -DskipTests
log_info "Maven 构建完成"

# 确认 JAR 存在
JAR_FILE="$PROJECT_ROOT/target/codecraft-$VERSION.jar"
if [ ! -f "$JAR_FILE" ]; then
    log_error "JAR 文件不存在: $JAR_FILE"
    exit 1
fi
log_info "JAR 产物: $JAR_FILE ($(du -h "$JAR_FILE" | cut -f1))"

# ==================== Step 4: JRE 裁剪 ====================
log_info "Step 4/6: jlink 裁剪内置 JRE..."
cd "$ELECTRON_DIR"

JRE_DIR="$ELECTRON_DIR/jre"
JAVA_BIN=""

if [ "$PLATFORM" = "mac" ]; then
    JAVA_HOME_CANDIDATE="$(/usr/libexec/java_home -v 17 2>/dev/null || echo '')"
    if [ -n "$JAVA_HOME_CANDIDATE" ]; then
        export JAVA_HOME="$JAVA_HOME_CANDIDATE"
    fi
fi

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN="$(command -v java || echo '')"
fi

if [ -z "$JAVA_BIN" ]; then
    log_warn "未找到 JDK 17+，跳过热裁剪。如需裁剪请安装 JDK 17 并设置 JAVA_HOME"
else
    JLINK="$(dirname "$JAVA_BIN")/jlink"
    if [ -x "$JLINK" ] || [ -f "$JLINK" ]; then
        # 如果 JRE 已存在且 JDK 版本未变，跳过
        if [ -d "$JRE_DIR/bin" ]; then
            log_info "内置 JRE 已存在，跳过裁剪（如需重新裁剪请删除 electron/jre 目录）"
        else
            log_info "正在裁剪 JRE（约 30 秒）..."
            rm -rf "$JRE_DIR"
            "$JLINK" \
                --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi \
                --strip-debug --compress 2 --no-header-files --no-man-pages \
                --output "$JRE_DIR"
            log_info "JRE 裁剪完成 ($(du -sh "$JRE_DIR" | cut -f1))"
        fi
    else
        log_warn "jlink 不可用，跳过热裁剪"
    fi
fi

# ==================== Step 5: macOS 图标生成 ====================
if [ "$PLATFORM" = "mac" ]; then
    ICON_PNG="$ELECTRON_DIR/icon.png"
    ICON_ICNS="$ELECTRON_DIR/icon.icns"

    if [ ! -f "$ICON_PNG" ]; then
        log_warn "icon.png 不存在，跳过图标生成"
    elif [ -f "$ICON_ICNS" ] && [ "$ICON_ICNS" -nt "$ICON_PNG" ]; then
        log_info "icon.icns 已是最新，跳过"
    else
        log_info "Step 5/6: 生成 macOS 图标..."
        ICONSET="$(mktemp -d)/icon.iconset"
        mkdir -p "$ICONSET"

        sips -z 16 16     "$ICON_PNG" --out "$ICONSET/icon_16x16.png"        >/dev/null 2>&1
        sips -z 32 32     "$ICON_PNG" --out "$ICONSET/icon_16x16@2x.png"     >/dev/null 2>&1
        sips -z 32 32     "$ICON_PNG" --out "$ICONSET/icon_32x32.png"        >/dev/null 2>&1
        sips -z 64 64     "$ICON_PNG" --out "$ICONSET/icon_32x32@2x.png"     >/dev/null 2>&1
        sips -z 128 128   "$ICON_PNG" --out "$ICONSET/icon_128x128.png"      >/dev/null 2>&1
        sips -z 256 256   "$ICON_PNG" --out "$ICONSET/icon_128x128@2x.png"   >/dev/null 2>&1
        sips -z 256 256   "$ICON_PNG" --out "$ICONSET/icon_256x256.png"      >/dev/null 2>&1
        sips -z 512 512   "$ICON_PNG" --out "$ICONSET/icon_256x256@2x.png"   >/dev/null 2>&1
        sips -z 512 512   "$ICON_PNG" --out "$ICONSET/icon_512x512.png"      >/dev/null 2>&1
        sips -z 1024 1024 "$ICON_PNG" --out "$ICONSET/icon_512x512@2x.png"   >/dev/null 2>&1

        iconutil -c icns "$ICONSET" -o "$ICON_ICNS"
        rm -rf "$(dirname "$ICONSET")"
        log_info "icon.icns 生成完成"
    fi
else
    log_info "Step 5/6: 跳过（非 macOS）"
fi

# ==================== Step 6: Electron 打包 ====================
log_info "Step 6/6: Electron 打包 ($PLATFORM)..."
cd "$ELECTRON_DIR"

# 安装依赖（如果需要）
if [ ! -d "node_modules" ]; then
    log_info "安装 Electron 依赖..."
    npm install
fi

case "$PLATFORM" in
    mac)   npm run dist:mac   ;;
    linux) npm run dist:linux ;;
esac

# ==================== 汇总 ====================
echo ""
echo "============================================"
log_info "🎉 打包完成！产物:"
echo ""

if [ -d "$RELEASE_DIR" ]; then
    ls -lh "$RELEASE_DIR"/*.exe "$RELEASE_DIR"/*.dmg "$RELEASE_DIR"/*.AppImage "$RELEASE_DIR"/*.deb 2>/dev/null || true
    echo ""
    # MD5 校验
    if [ "$PLATFORM" = "mac" ]; then
        md5 -r "$RELEASE_DIR"/*.dmg 2>/dev/null || true
    else
        md5sum "$RELEASE_DIR"/*.AppImage "$RELEASE_DIR"/*.deb 2>/dev/null || true
    fi
fi
echo ""
log_info "版本: $VERSION | 平台: $PLATFORM"
echo "============================================"
