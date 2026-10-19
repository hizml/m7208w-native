[English](README.md) | **中文**

# Lenovo M7208W 原生驱动套件（逆向工程 / arm64）

让联想 M7208W 打印机在 **Apple Silicon Mac** 与 **安卓 64 位手机** 上满血工作的全套自研软件，替代早已停更的 Intel-only 原厂驱动（macOS 28 起 Rosetta 退役即全灭）。

> 非联想官方项目。打印协议（LHPL 方言）与扫描/复印/设备管理协议均由 clean-room 逆向获得，不含任何原厂代码与固件。

## 组件

| 目录 | 内容 | 许可证 |
|---|---|---|
| `driver/` | CUPS 打印过滤器（C，arm64 原生）：LHPL 协议 + JBIG 编码 + 队列修复脚本 | GPL-2.0（静态链接 jbigkit） |
| `mac-studio/` | M7208W Studio（Swift/SwiftUI）：扫描/复印/打印/WiFi/墨粉 + eSCL 系统扫描仪守护 | MIT |
| `android-studio/` | 安卓 App（Kotlin + NDK jbigkit）：同 Mac 全功能 | MIT（JNI 层 GPL-2.0） |

## 协议速览（详见 driver/README.md）

- **打印**：TCP 9100，PJL 头（5 行极简变体）→ 64B 对齐块 `ESC LH@ sj/sp/ep`（尾部 XOR 校验）→ JBIG(T.88) 压缩光栅。固件 1.3.00 对 sp 块字段严格校验（DMA 尺寸字段、32 位条带长度、A4 纸张码、JBIG MX=8），不满足则**静默取消作业**——页计数仍 +1（受理即计），极难排查
- **扫描**：TCP 53011 激活（LTGS/LTAT）→ 53010 会话（Sl/Si 168B/Su），行宽 2496，0x64 数据块流
- **复印/WiFi/墨粉/重启**：TCP 9100 命令帧族 `4d3c2b1a ...`
- **发现**：SNMP 子网广播 + 9100 签名帧指纹校验

## 从零部署（新 Mac）

1. 编译并安装 `driver/`（两条命令，见其 README）
2. 拷贝 M7208W Studio.app，首次启动自动发现打印机
3. 点「修复打印」装好打印队列（输一次密码）；点「系统扫描仪」启用图像捕捉集成（免密码）

## 免责声明

仅用于个人设备互操作。不对打印机固件行为作任何保证；墨粉/重置等维护命令请自担风险。
