#!/bin/bash
# CodeCraft 桌面应用启动器 (Linux/Mac)
cd "$(dirname "$0")"

# 检查 JAR 是否存在（动态匹配 target 下最新的 code-craft-*.jar，与 main.js 逻辑一致，避免版本号写死漂移）
JAR_FILE=$(ls -t ../target/code-craft-*.jar 2>/dev/null | head -n 1)
if [ -z "$JAR_FILE" ]; then
    echo "错误: 未找到后端 JAR 文件！"
    echo "请先执行: mvn clean package -DskipTests"
    exit 1
fi
echo "检测到后端 JAR: $JAR_FILE"

# 检查 Electron 是否安装
if [ ! -d "node_modules" ]; then
    echo "正在安装 Electron..."
    npm install
fi

echo "启动桌面应用..."
npx electron .
