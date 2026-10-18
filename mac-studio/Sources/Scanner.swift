import Foundation
import CoreGraphics

// MARK: - 扫描选项

enum PaperSize: String, CaseIterable, Identifiable {
    case A4 = "A4", A5 = "A5", B5 = "B5", letter = "信纸", A6 = "A6", B6 = "B6", executive = "Executive", k16 = "16K"
    var id: String { rawValue }
    var mm: (Double, Double) {
        switch self {
        case .A4: return (210, 297)
        case .A5: return (148, 210)
        case .B5: return (176, 250)
        case .letter: return (215.9, 279.4)
        case .A6: return (105, 148)
        case .B6: return (128, 182)
        case .executive: return (184.2, 266.7)
        case .k16: return (195, 270)
        }
    }
}

enum ScanMode: String, CaseIterable, Identifiable {
    case text = "文本 (锐化)"
    case photo = "照片 (平滑)"
    case mixed = "混合"
    var id: String { rawValue }
}

struct ScanOptions {
    var dpi = 300
    /// eSCL透传的自定义区域(可空=默认整页)
    var customW = 0
    var customH = 0
    /// 亮度/对比度(Si字段[36..43]/[48..55]直通)
    var brightness = 0
    var contrast = 0  // 75/100/150/200/300/600
    var paper: PaperSize = .A4
    var mode: ScanMode = .text
    var density = 0
    var dims: (Int, Int) {
        let (wmm, hmm) = paper.mm
        return (Int((wmm / 25.4 * Double(dpi)).rounded()), Int((hmm / 25.4 * Double(dpi)).rounded()))
    }
}

// MARK: - Si 参数包 (168B, 基于已验证灰度模板)

enum SiPacket {
    /// 2026-10-16 真机验证可出图的 si_gray 模板原字节 (零抄写风险)
    static let template: [UInt8] = [
        0x53, 0x69, 0x00, 0x00, 0xa0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0xea, 0x07, 0x0a, 0x0f, 0x14, 0x1e, 0xe0, 0x01, 0x80, 0x20, 0x00, 0x00,
        0xad, 0x2d, 0x00, 0x00, 0x81, 0xff, 0xff, 0xff, 0x7f, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
        0x81, 0xff, 0xff, 0xff, 0x7f, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 0x00, 0x00, 0x00, 0xb0, 0x09, 0x00, 0x00, 0xb0, 0x09, 0x00, 0x00, 0xb4, 0x0d, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x2c, 0x01, 0x00, 0x00, 0x2c, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xb0, 0x09, 0x00, 0x00, 0xb4, 0x0d, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    ]

    static func build(_ o: ScanOptions) -> Data {
        var si = template
        // 时间戳
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: Date())
        if let y = c.year, let mo = c.month, let d = c.day, let h = c.hour, let mi = c.minute {
            si[20] = UInt8(y & 0xFF); si[21] = UInt8((y >> 8) & 0xFF)
            si[22] = UInt8(mo); si[23] = UInt8(d); si[24] = UInt8(h); si[25] = UInt8(mi)
        }
        // 可变项: 幅面/分辨率 (默认A4@300 时与模板一致)
        // 亮度/对比度: 模板[36..43]=(-127,127) 中心平移±40
        let bOff = o.brightness * 40 / 127
        put32(&si, 36, UInt32(bitPattern: Int32(-127 + bOff)))
        put32(&si, 40, UInt32(bitPattern: Int32(127 + bOff)))
        let cOff = o.contrast * 40 / 127
        put32(&si, 48, UInt32(bitPattern: Int32(-127 + cOff)))
        put32(&si, 52, UInt32(bitPattern: Int32(127 + cOff)))
        let (w, h) = (o.customW > 0 && o.customH > 0) ? (o.customW, o.customH) : o.dims
        put32(&si, 68, UInt32(w))   // 宽
        put32(&si, 72, UInt32(w))   // 行字节宽
        put32(&si, 76, UInt32(h))   // 高
        put32(&si, 96, UInt32(o.dpi))
        put32(&si, 100, UInt32(o.dpi))
        put32(&si, 112, UInt32(w))
        put32(&si, 116, UInt32(h))
        return Data(si)
    }

    private static func put32(_ a: inout [UInt8], _ o: Int, _ v: UInt32) {
        a[o] = UInt8(v & 0xFF); a[o+1] = UInt8(v >> 8 & 0xFF)
        a[o+2] = UInt8(v >> 16 & 0xFF); a[o+3] = UInt8(v >> 24 & 0xFF)
    }
    private static func put16(_ a: inout [UInt8], _ o: Int, _ v: UInt16) {
        a[o] = UInt8(v & 0xFF); a[o+1] = UInt8(v >> 8)
    }
}

// MARK: - 扫描会话 (已验证生命周期: Sl→Si→数据→Su→等打印机关连接)

enum ScanError: LocalizedError {
    case busy, refused, timeout, dead
    var errorDescription: String? {
        switch self {
        case .busy: return "打印机忙 (刚打印过? 稍候自动重试)"
        case .refused: return "无法连接扫描服务"
        case .timeout: return "等待扫描数据超时"
        case .dead: return "扫描服务休眠 (打印后固病), 需重启打印机恢复"
        }
    }
    var retryable: Bool { if case .busy = self { return true }; return false }
    var isDead: Bool { if case .dead = self { return true }; return false }
}

final class ScanSession {
    let ip: String
    init(ip: String) { self.ip = ip }

    /// 单发无重试版(eSCL用)
    func scanNoRetry(_ o: ScanOptions) throws -> (pixels: [UInt8], width: Int) {
        try oneScan(o, nil)
    }

    func scan(_ o: ScanOptions, progress: ((Int, Data) -> Void)? = nil) throws -> (pixels: [UInt8], width: Int) {
        var last: Error = ScanError.timeout
        for i in 0..<4 {
            do { return try oneScan(o, progress) }
            catch let e as ScanError where e.retryable {
                last = e
                // 忙=瞬态(实测8-20s窗口), 等待20s再试, 最多4次; 避免秒级连戳
                Thread.sleep(forTimeInterval: 20)
                _ = i
            }
        }
        throw last
    }

    private func oneScan(_ o: ScanOptions, _ progress: ((Int, Data) -> Void)?) throws -> (pixels: [UInt8], width: Int) {
        guard let act = Tcp(ip, 53011, timeout: 5) else { throw ScanError.refused }
        act.send(Data("LTGS".utf8) + Data([0, 0, 0, 0x53]))
        var hdr = [UInt8](repeating: 0, count: 8)
        _ = act.recv(&hdr, 4)
        act.rstClose()

        guard let s = Tcp(ip, 53010, timeout: 5) else { throw ScanError.refused }
        defer { s.rstClose() }
        s.send(Data([0x53, 0x6c]) + Data(repeating: 0, count: 6))
        Thread.sleep(forTimeInterval: 0.4)
        s.send(SiPacket.build(o))

        var raw = Data()
        var buf = [UInt8](repeating: 0, count: 1 << 20)
        var seenData = false
        var done = false
        // 状态机在整条raw上同步推进(跨recv块), 只在块边界判定帧——数据负载里的0x73 0x00不会被误读
        var pos = 0
        while !done && raw.count < 50_000_000 {
            let n = s.recv(&buf, 40)
            if n == 0 { break }
            if n < 0 {
                // 静默超时: 没收到任何字节=扫描服务死锁(打印后固病), 直接归死锁立即弹恢复框
                // 收到过部分数据才中断=真超时
                throw raw.isEmpty ? ScanError.dead : ScanError.timeout
            }
            raw.append(contentsOf: buf[0..<n])
            progress?(raw.count, Data(raw))
            let total = raw.count
            let arr = [UInt8](raw)
            while pos < total - 1 {
                if arr[pos] == 0x64 {
                    guard pos + 8 <= total else { break }  // 帧头未收全: 等下一段(不许把'd'当垃圾跳过!)
                    let len = Int(arr[pos+4]) | Int(arr[pos+5]) << 8 | Int(arr[pos+6]) << 16 | Int(arr[pos+7]) << 24
                    if pos + 8 + len > total { break }  // 帧体未收全: 等
                    seenData = true
                    pos += 8 + len
                } else if arr[pos] == 0x73 {
                    let code = arr[pos+1]
                    if code == 3 && !seenData { throw ScanError.busy }
                    if code == 0 && seenData { done = true; break }
                    pos += 2
                } else {
                    pos += 1
                }
            }
        }
        s.send(Data([0x53, 0x75]) + Data(repeating: 0, count: 6))
        s.waitPeerClose(5)
        if !seenData && raw.count < 32 {
            throw ScanError.dead   // 静默 = 打印后扫描死 (固病)
        }
        return ScanDecoder.extract(raw, expectedH: o.dims.1, dpi: o.dpi)
    }
}

// MARK: - 解码与后处理

enum ScanDecoder {
    static func extract(_ raw: Data, expectedH: Int, dpi: Int = 300) -> (pixels: [UInt8], width: Int) {
        // 设备原生行宽: 300dpi=2496, 600dpi=4992 (实测; Si里的W会被无视)
        let w = dpi >= 600 ? 4992 : 2496
        var pixels = [UInt8]()
        pixels.reserveCapacity(w * expectedH)
        let arr = [UInt8](raw)
        var i = 16  // 跳过开头两个 s+0 就绪帧 (python验证版同款)
        while i + 8 <= arr.count {
            if arr[i] == 0x64 {
                let len = Int(arr[i+4]) | Int(arr[i+5]) << 8 | Int(arr[i+6]) << 16 | Int(arr[i+7]) << 24
                let end = min(i + 8 + len, arr.count)
                pixels.append(contentsOf: arr[(i+8)..<end])
                i = i + 8 + len
            } else if arr[i] == 0x73 {
                break  // 尾部 s+0 完成帧, 收工
            } else {
                break  // 失步即停 (保留已对齐部分)
            }
        }
        return (pixels, w)
    }

    static func postProcess(_ src: [UInt8], width: Int, mode: ScanMode, density: Int) -> [UInt8] {
        let h = src.count / width
        guard h > 2, width > 2 else { return src }
        var out = src
        let at: (Int, Int) -> Int = { x, y in Int(src[y * width + x]) }
        if mode == .text || mode == .photo {
            var dst = [UInt8](repeating: 0, count: src.count)
            for y in 1..<(h-1) {
                for x in 1..<(width-1) {
                    let c = at(x, y)
                    var v: Int
                    if mode == .text {
                        let blur = (at(x-1,y-1) + at(x,y-1) + at(x+1,y-1) + at(x-1,y) + at(x+1,y) + at(x-1,y+1) + at(x,y+1) + at(x+1,y+1)) / 8
                        v = c + ((c - blur) * 8) / 5
                    } else {
                        v = (at(x-1,y) + c * 2 + at(x+1,y) + at(x,y-1) * 2 + at(x,y+1) * 2) / 8
                    }
                    dst[y * width + x] = UInt8(max(0, min(255, v)))
                }
            }
            out = dst
        }
        if density != 0 {
            let d = density * 80 / 127
            for i in out.indices {
                let v = Int(out[i]) + d
                out[i] = UInt8(max(0, min(255, v)))
            }
        }
        return out
    }

    static func makeImage(_ pixels: [UInt8], width: Int) -> CGImage? {
        let h = pixels.count / width
        guard h > 0, width > 0 else { return nil }
        var data = [UInt8](pixels[0..<(width * h)])
        let provider = CGDataProvider(data: NSData(bytes: &data, length: data.count))
        guard let prov = provider else { return nil }
        return CGImage(width: width, height: h,
                       bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: width,
                       space: CGColorSpaceCreateDeviceGray(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                       provider: prov, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}


extension ScanDecoder {
    /// 180°旋转
    static func rotate180(_ px: [UInt8], width: Int) -> [UInt8] {
        let h = px.count / width
        guard h > 0 else { return px }
        var out = [UInt8](repeating: 0, count: h * width)
        for y in 0..<h {
            for x in 0..<width {
                out[(h - 1 - y) * width + (width - 1 - x)] = px[y * width + x]
            }
        }
        return out
    }
}
