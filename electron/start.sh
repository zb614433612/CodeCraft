#!/bin/bash
# CodeCraft 桌面应用启动器 (Linux/Mac)
cd "$(dirname "$0")"

# 检查 JAR 是否存在
if [ ! -f "../target/codecraft-1.0.1.jar" ]; then
    echo "错误: 未找到后端 JAR 文件！"
    echo "请先执行: mvn clean package -DskipTests"
    exit 1
fi

# 检查 Electron 是否安装
if [ ! -d "node_modules" ]; then
    echo "正在安装 Electron..."
    npm install
fi

echo "启动桌面应用..."
npx electron .
