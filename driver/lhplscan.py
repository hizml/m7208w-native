#!/usr/bin/env python3
"""lhplscan v2 — 大端 Si 参数版"""
import socket, struct, time, sys
IP = sys.argv[1] if len(sys.argv)>1 else "192.168.1.100"
def BE(v): return struct.pack('>i', v)  # 大端 signed int32

# --- ① 53011 激活 ---
s=socket.create_connection((IP,53011),timeout=5); s.settimeout(8)
s.sendall(bytes([0x4c,0x54,0x47,0x53,0x00,0x00,0x00,0x53]))
r=b''
try:
    while len(r)<8:
        c=s.recv(8-len(r))
        if not c: break
        r+=c
except socket.timeout: pass
s.close()
if r[:4]!=b'LTAT': print("激活失败"); sys.exit(1)
print(f"① 激活 OK")

# --- ② 53010: Sl → Si(大端) → 收数据 ---
s=socket.create_connection((IP,53010),timeout=5); s.settimeout(45)
s.sendall(b"Sl"+b"\x00"*6); time.sleep(0.2)
print("② Sl OK")

# Si: 完全按 ARM 代码栈布局, 大端 int
si = bytearray(0xA8)  # 168B
si[0]=0x53; si[1]=0x69; si[4]=0xA0
now=time.localtime()
si[16]=now.tm_year & 0xFF; si[17]=(now.tm_year>>8)&0xFF
si[18]=now.tm_mon; si[19]=now.tm_mday; si[20]=now.tm_hour; si[21]=now.tm_min
si[22]=0xE0; si[23]=1
# A4 300dpi: W=2480 H=3508 (从 DAT 表 0xcd008 读出)
W,H,DPI=2480,3508,300
si[24:28]=BE(int(W*1000/DPI))    # x 0.1mm = 2100
si[28:32]=BE(int(H*1000/DPI))    # y 0.1mm = 2970
si[32:36]=BE(-127); si[36:40]=BE(127)  # brightness
si[40]=1
si[41:45]=BE(-127); si[45:49]=BE(127)  # contrast
si[50]=1
si[55]=7
si[56:60]=BE(W); si[60:64]=BE(W*1); si[64:68]=BE(H)
si[68]=2  # 灰度
si[72]=8  # bytes/line factor? (local_80=8)
si[80:84]=BE(0); si[84:88]=BE(0)
si[88:92]=BE(DPI); si[92:96]=BE(DPI)
si[96:100]=BE(W)
si[104:108]=BE(W); si[108:112]=BE(H)
si[148]=1; si[156]=1
s.sendall(bytes(si)); print(f"③ Si OK(大端, {len(si)}B)")

# 收数据
buf=b''; t0=time.time()
try:
    while time.time()-t0<45:
        c=s.recv(65536)
        if not c: break
        buf+=c
        print(f"   +{len(c)}B @{time.time()-t0:.1f}s 首8B={c[:8].hex(' ')}")
except socket.timeout: pass
print(f"④ 共收 {len(buf)}B")
if buf:
    open("scan_raw.bin","wb").write(buf)
    print(f"   首64B: {buf[:64].hex(' ')}")
s.sendall(b"Su"+b"\x00"*6); s.close()
print("⑤ 完成")
