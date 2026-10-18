# M7208W LHPL CUPS 打印过滤器（arm64 原生）

替代 Intel-only 的 `/usr/libexec/cups/filter/LNTHR1Z`。逆向自：
- 原厂 Windows/手机驱动数据流（行为分析）
- 打印机固件内嵌样本（校验规则）
- 实机出纸验证（真实出纸计数器 oracle）

## 编译

```bash
cd jbigkit-2.1 && ./configure && make   # 产出 libjbig/libjbig.a
cd ..
cc -O2 -arch arm64 -o rastertolhpl rastertolhpl.c \
   -I jbigkit-2.1/libjbig jbigkit-2.1/libjbig/libjbig.a -lcups
```

## 安装（打印队列一键修复）

```bash
sudo cp rastertolhpl /usr/libexec/cups/filter/LNTHR1Z
sudo bash fix-print-queue.sh   # 重建 M7208W 队列(socket://<IP>:9100 + PPD)
```

## 数据流格式（固件 1.3.00 实证版）

```
\x1b%-12345X@PJL JOB NAME=PRINTER\r\n     ← UEL；行尾必须 \r\n
@PJL SET RESOLUTION=600\r\n
@PJL SET BITSPERPIXEL=1\r\n
@PJL SET RENDERMODE=GRAYSCALE\r\n
@PJL SET COPIES=1\r\n
@PJL SET DUPLEX = OFF\r\n                  ← 注意等号两侧空格
@PJL ENTER LANGUAGE=LHPL\r\n
[64B sj 块][每页: 64B sp 块 + JBIG 数据 + 64B ep 块]\x1b%-12345X@PJL EOJ\r\n
```

**头必须极简**：多余的 SET 行（MEDIASOURCE/MDPXS/JOBATTR 等）会导致固件 1.3.00 静默拒印。头总长 ≤1024 字节。

### 64B 块布局（块内偏移）

| 偏移 | 字段 |
|---|---|
| 0..5 | `1B 4C 48 40` + 2 字符命令（sj/sp/ep） |
| 6..62 | 57B 载荷（不足补零） |
| 63 | 前 63 字节 XOR（全零 ep 块 = 0x4a，即仅头部异或） |

### sp 块关键字段（载荷偏移）

| 载荷偏移 | 字段 | 值 |
|---|---|---|
| 0 | 纸张码 | 2 = A4（1 = Letter） |
| 1 | 介质码 | 1 = 普通纸 |
| 2..5 | LE32 | 5120（600dpi 满幅宽） |
| 6..9 | LE32 | 页高（光栅行数） |
| 10..13 | LE32 | **⌊5120/8⌋×页高**（引擎 DMA 缓冲字节数；固件按此校验/分配，错值 → DMA Error → 静默取消） |
| 14..17 | LE32 | JBIG 总长 |
| 18..21 | LE32 | slot0 = JBIG 总长（单平面） |
| 22..33 | LE32×3 | 其余槽 = 0 |
| 36..37 | LE16 | 600（分辨率，非 600 会被按 1200dpi 解析→丢页） |
| 38..41 | LE16×2 | 纸张几何 0.1mm（A4 = 2100×2970） |

### JBIG

jbigkit T.88：XD=5120、L0=128、**MX=8**、单平面全高 BIE。

## 验证出纸

状态页 `https://<IP>/status.asp` 的 **`Print Total` 才是真实出纸计数**；"Toner 页计数"是受理即增的虚计数，不可作为出纸依据。
