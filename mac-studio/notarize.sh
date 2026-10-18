#!/bin/bash
# M7208W Studio 公证+装订 一键脚本
# 前置(只需做一次): 在 https://appleid.apple.com 登录 → 登录与安全 → App专用密码 → 生成
# 然后执行(会存进钥匙串, 之后不再要密码):
#   xcrun notarytool store-credentials m7208w-notary \
#     --apple-id "你的AppleID邮箱" --team-id <你的TEAM_ID> --password "刚生成的专用密码"
set -e
ZIP=~/Downloads/M7208W_Studio_Mac_v0.3_签名版.zip
echo "① 提交公证(几分钟到半小时)..."
xcrun notarytool submit "$ZIP" --keychain-profile m7208w-notary --wait
echo "② 装订公证票据..."
xcrun stapler staple "/Applications/M7208W Studio.app"
ditto -c -k --sequesterRsrc --keepParent "/Applications/M7208W Studio.app" "$ZIP"
echo "✅ 完成: $ZIP 已可分发(别人下载后直接双击打开, 无Gatekeeper拦截)"
spctl -a -v "/Applications/M7208W Studio.app"
