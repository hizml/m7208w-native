#!/bin/bash
# 构建App所需的两个资源文件(均自编译, 不入git):
#   Sources/LNTHR1Z.universal — CUPS打印驱动(仅arm64自编切片; 勿打包原厂x86_64)
#   Sources/M7208WeSCL        — eSCL系统扫描仪守护
set -e
cd "$(dirname "$0")"
# ① 驱动: 编译 ../driver 的 C 源码 → arm64 单架构
cc -O2 -arch arm64 -o /tmp/rastertolhpl ../driver/rastertolhpl.c \
   -I ../driver/jbigkit-2.1/libjbig ../driver/jbigkit-2.1/libjbig/libjbig.a -lcups
cp /tmp/rastertolhpl Sources/LNTHR1Z.universal
# ② eSCL守护: 用escl-daemon源码构建(结构与主App共享引擎代码, 复制式)
mkdir -p /tmp/escl-build/Sources
cp Sources/*.swift /tmp/escl-build/Sources/
cp escl-daemon/main.swift /tmp/escl-build/Sources/Main.swift
sed 's/Sources/tmp\/escl-build\/Sources/' Package.swift > /tmp/escl-build/Package.swift
(cd /tmp/escl-build && swift build -c release 2>/dev/null || swiftc -O Sources/*.swift -o ../M7208WeSCL)
cp /tmp/escl-build/.build/release/M7208WeSCL Sources/M7208WeSCL 2>/dev/null || cp /tmp/M7208WeSCL Sources/M7208WeSCL
chmod +x Sources/M7208WeSCL Sources/LNTHR1Z.universal
echo "✅ 资源就绪: Sources/LNTHR1Z.universal + Sources/M7208WeSCL"
echo "   之后: swift build -c release 即可构建App"
