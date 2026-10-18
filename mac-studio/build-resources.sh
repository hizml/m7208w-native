#!/bin/bash
# 构建App所需两个资源(均自编译; 不入git):
#   Sources/LNTHR1Z.universal — CUPS打印驱动(arm64自编)
#   Sources/M7208WeSCL        — eSCL系统扫描仪守护(独立SPM包, 产物名M7208WESCL)
set -e
cd "$(dirname "$0")"
echo "① 驱动..."
make -s -C ../driver/jbigkit-2.1/libjbig
cc -O2 -arch arm64 -o Sources/LNTHR1Z.universal ../driver/rastertolhpl.c \
   -I ../driver/jbigkit-2.1/libjbig ../driver/jbigkit-2.1/libjbig/libjbig.a -lcups
echo "② eSCL守护..."
( cd escl-daemon && swift build -c release )
cp escl-daemon/.build/release/M7208WESCL Sources/M7208WeSCL
chmod +x Sources/LNTHR1Z.universal Sources/M7208WeSCL
echo "✅ 资源就绪"
