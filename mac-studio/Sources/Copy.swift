import Foundation

// MARK: - 网络复印引擎 (逆向自 libsockcmd cmd_net_copy, 10-17 真机验证)

enum CopyPaper: Int, CaseIterable, Identifiable {
    case A4 = 0, letter = 3, A5 = 2, A6 = 13, B5 = 5, B6 = 14, executive = 6, k16 = 16
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .A4: return "A4"; case .letter: return "信纸"; case .A5: return "A5"
        case .A6: return "A6"; case .B5: return "B5"; case .B6: return "B6"
        case .executive: return "Executive"; case .k16: return "16K"
        }
    }
}

enum CopyMedia: Int, CaseIterable, Identifiable {
    case plain = 0, recycled = 1, thick = 2, thin = 3, label = 4
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .plain: return "普通纸"; case .recycled: return "再生纸"
        case .thick: return "厚纸"; case .thin: return "薄纸"; case .label: return "标签"
        }
    }
}

enum CopyDpi: Int, CaseIterable, Identifiable {
    case d300 = 0, d600 = 1
    var id: Int { rawValue }
    var label: String { self == .d300 ? "300 DPI" : "600 DPI" }
}

enum NupMode: Int, CaseIterable, Identifiable {
    case off = 0, twoUp = 1, idCard = 4
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .off: return "关"; case .twoUp: return "2合1"; case .idCard: return "身份证复印"
        }
    }
}

struct CopyOptions {
    var copies = 1
    var scale = 100          // 25...400
    var scanModeText = true  // true=文本(1) false=照片(0)
    var src: CopyPaper = .A4
    var dst: CopyPaper = .A4
    var nup: NupMode = .off
    var dpi: CopyDpi = .d300
    var media: CopyMedia = .plain
    var density = 3          // 0...6, 默认3
}

enum CopyEngine {
    /// 复印帧: 4d3c2b1a 09 01 83 00 13 01 01 + 128B参数区 = 139B, 9100口
    static func run(ip: String, _ o: CopyOptions) -> Bool {
        let hdr: [UInt8] = [0x4d,0x3c,0x2b,0x1a,0x09,0x01,0x83,0x00,0x13,0x01,0x01]
        var p = [UInt8](repeating: 0, count: 128)
        p[0] = UInt8(o.density)
        p[1] = UInt8(o.copies)
        p[2] = UInt8(o.scanModeText ? 1 : 0)
        p[3] = UInt8(o.src.rawValue)
        p[4] = UInt8(o.dst.rawValue)
        p[5] = UInt8(o.nup.rawValue)
        p[6] = UInt8(o.dpi.rawValue)
        p[7] = UInt8(o.media.rawValue)
        p[8] = UInt8(o.scale & 0xFF)
        p[9] = UInt8((o.scale >> 8) & 0xFF)
        guard let t = Tcp(ip, 9100, timeout: 5) else { return false }
        defer { t.rstClose() }
        t.send(Data(hdr + p))
        var buf = [UInt8](repeating: 0, count: 64)
        let n = t.recv(&buf, 10)
        return n >= 10 && buf[8] == 0x0d
    }
}

// MARK: - WiFi 读取 (9100 类型6帧, 已验证)

enum WiFiReader {
    /// 返回 (ssid, password)
    static func read(ip: String) -> (String, String)? {
        guard let t = Tcp(ip, 9100, timeout: 5) else { return nil }
        defer { t.rstClose() }
        t.send(Data([0x4d,0x3c,0x2b,0x1a,0x06,0x01,0x03,0x00,0x13,0x01,0x00]))
        var buf = [UInt8](repeating: 0, count: 512)
        let n = t.recv(&buf, 4)
        guard n > 60 else { return nil }
        // 布局(实测2026-10-17): SSID@24, 密码@62, IP@末4B
        func field(_ off: Int, _ len: Int) -> String {
            var bytes: [UInt8] = []
            for i in off..<min(off + len, n) where buf[i] != 0 {
                bytes.append(buf[i])
            }
            return String(bytes: bytes, encoding: .ascii) ?? ""
        }
        let ssid = field(19, 32)
        let pass = field(50, 34)
        return (ssid, pass)
    }
}


// MARK: - WiFi 写入 (逆向自 cmd_wifi_set, 191B帧走9100)

enum WiFiWriter {
    /// 写WiFi配置. ⚠️ 写错SSID打印机会失联(需USB重配), 调用方必须先弹窗确认
    /// encryption: 1=WEP64 2=WEP128 3=WPA2 0=开放
    static func write(ip: String, ssid: String, password: String, encryption: Int = 3) -> Bool {
        var f = [UInt8](repeating: 0, count: 191)
        f[0] = 0x4d; f[1] = 0x3c; f[2] = 0x2b; f[3] = 0x1a
        f[4] = 0x06; f[5] = 0x01; f[6] = 0xb7; f[7] = 0x00
        f[8] = 0x13; f[9] = 0x01
        f[11] = 0x01                     // enable
        f[15] = 0x01                     // wifi enable
        f[16] = UInt8(encryption)        // 3=WPA2
        // SSID@19(32B), 密码@51(34B)
        let s = Array(ssid.utf8.prefix(31)) + [0]
        for (i, b) in s.enumerated() { f[19 + i] = b }
        let p = Array(password.utf8.prefix(33)) + [0]
        for (i, b) in p.enumerated() { f[51 + i] = b }
        guard let t = Tcp(ip, 9100, timeout: 5) else { return false }
        defer { t.rstClose() }
        t.send(Data(f))
        var buf = [UInt8](repeating: 0, count: 64)
        let n = t.recv(&buf, 10)
        // 受理回执: 06回显
        return n >= 10 && buf[4] == 0x06
    }
}


// MARK: - WiFi 搜网 (逆向自 cmd_wifi_aplist_get, 2026-10-17 验证)

enum WiFiScanner {
    /// 扫描周围WiFi, 返回SSID列表. 约3秒返回
    static func scan(ip: String) -> [String]? {
        guard let t = Tcp(ip, 9100, timeout: 5) else { return nil }
        defer { t.rstClose() }
        t.send(Data([0x4d,0x3c,0x2b,0x1a,0x06,0x01,0x03,0x00,0x13,0x01,0x07]))
        var buf = [UInt8](repeating: 0, count: 4096)
        let n = t.recv(&buf, 30)
        guard n > 40 else { return nil }
        // 布局: 头11B + 若干32B槽(SSID零填充) + 末尾加密标记
        var out: [String] = []
        var i = 11
        while i + 4 < n {
            let seg = buf[i..<min(i + 32, n)]
            let name = seg.prefix(while: { $0 != 0 })
            if name.count >= 2, let s = String(bytes: name, encoding: .utf8), s.allSatisfy({ $0.isASCII }) {
                out.append(s)
            }
            i += 32
        }
        return out.isEmpty ? nil : out
    }
}


// MARK: - 墨粉控制 (逆向 cmd_print_tonerend_get/set + toner_reset)
// 帧: 4d3c2b1a 07 01 04 00 13 01 [子命令][参数]
//   0x11=读状态(回执[11]=状态码) 0x12=设开关(回执[11]=00受理/04拒绝) 0x19=重置计数
enum TonerControl {
    static func cmd(ip: String, sub: UInt8, value: UInt8) -> [UInt8]? {
        guard let t = Tcp(ip, 9100, timeout: 5) else { return nil }
        defer { t.rstClose() }
        t.send(Data([0x4d,0x3c,0x2b,0x1a,0x07,0x01,0x04,0x00,0x13,0x01,sub,value]))
        var buf = [UInt8](repeating: 0, count: 32)
        let n = t.recv(&buf, 6)
        return n > 0 ? Array(buf[0..<n]) : nil
    }

    /// 读"墨粉尽继续打印"状态码 (02=墨粉未尽/选项锁定)
    static func getState(ip: String) -> Int? {
        guard let r = cmd(ip: ip, sub: 0x11, value: 0) else { return nil }
        return r.count > 11 ? Int(r[11]) : nil
    }

    /// 设"墨粉尽继续打印" (回执00=受理)
    static func setContinue(ip: String, on: Bool) -> Bool {
        guard let r = cmd(ip: ip, sub: 0x12, value: on ? 1 : 0) else { return false }
        return r.count > 11 && r[11] == 0x00
    }

    /// 重置墨粉计数 (固件可能无回执, 发出即算)
    static func resetCounter(ip: String) {
        _ = cmd(ip: ip, sub: 0x19, value: 0)
    }
}

