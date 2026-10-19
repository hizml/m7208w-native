[**English**](README.md) | [中文](README.zh-CN.md)

# Lenovo M7208W Native Driver Suite (Reverse Engineering / arm64)

A complete set of homegrown software that makes the **Lenovo M7208W** laser printer fully work on **Apple Silicon Macs** and **64-bit Android** phones — replacing the long-abandoned Intel-only vendor drivers (which die entirely once Rosetta is retired in macOS 28).

> **Unofficial project.** The printing protocol (an LHPL dialect), scanning, copying and device-management protocols were all obtained via clean-room reverse engineering. No vendor code or firmware is included.

## Components

| Directory | Contents | License |
|---|---|---|
| `driver/` | CUPS print filter (C, native arm64): LHPL protocol + JBIG encoding + queue repair script | GPL-2.0 (statically links jbigkit) |
| `mac-studio/` | M7208W Studio (Swift/SwiftUI): scan / copy / print / Wi-Fi / toner + eSCL system-scanner daemon | MIT |
| `android-studio/` | Android app (Kotlin + NDK jbigkit): feature-parity with the Mac app | MIT (JNI layer GPL-2.0) |

## Protocol at a Glance (details in driver/README.md)

- **Printing**: TCP 9100. A minimal 5-line PJL header, then 64-byte aligned blocks `ESC LH@ sj/sp/ep` (trailing XOR checksum), then JBIG (T.88) compressed raster. Firmware 1.3.00 validates sp-block fields strictly (DMA-size field, 32-bit strip lengths, A4 paper code, JBIG MX=8) and **silently cancels the job** on mismatch — the page counter still increments on acceptance, which makes it notoriously hard to debug
- **Scanning**: TCP 53011 activation (LTGS/LTAT), then a 53010 session (Sl / 168-byte Si / Su); line width 2496; streamed 0x64 data blocks
- **Copy / Wi-Fi / toner / reboot**: TCP 9100 command-frame family starting with `4d3c2b1a ...`
- **Discovery**: SNMP subnet broadcast + fingerprint validation via a 9100 signature frame

## Quick Start (fresh Mac)

1. Build and install `driver/` (two commands — see its README)
2. Copy M7208W Studio.app anywhere and launch it; it auto-discovers the printer on first run
3. Click "修复打印" (Repair Printing) to install the print queue (one admin password); click "系统扫描仪" (System Scanner) to enable Image Capture integration (no password needed)

## Disclaimer

For personal device interoperability only. No warranty regarding printer firmware behavior; use toner/reset maintenance commands at your own risk.

## License

GPL-2.0 (driver, due to jbigkit linkage) · MIT (both apps). See `LICENSE` in each directory.
