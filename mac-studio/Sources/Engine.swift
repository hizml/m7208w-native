import Foundation
import Darwin

// MARK: - Socket 基础

func makeTimeout(_ t: Double) -> timeval {
    timeval(tv_sec: time_t(t), tv_usec: __darwin_suseconds_t(t.truncatingRemainder(dividingBy: 1) * 1_000_000))
}

/// TCP 连接 (带超时)
func ipv4Addr(_ s: String) -> in_addr {
    var a = in_addr()
    let parts = s.split(separator: ".").compactMap { UInt32($0) }
    if parts.count == 4 {
        // 网络序数值: 首段在最高字节 (等价 inet_addr, 勿再加 .bigEndian)
        a.s_addr = parts[0] | (parts[1] << 8) | (parts[2] << 16) | (parts[3] << 24) // 内存字节即网络序, 勿再转换
    }
    return a
}

/// TCP 连接 (阻塞式, 与验证过的 python/nc 同构; 局域网打印机连接毫秒级完成)
func tcpConnect(_ ip: String, _ port: UInt16, timeout: Double) -> Int32? {
    _ = timeout  // 阻塞连接不做超时花活; 不可达地址由系统 TCP 超时兜底
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { return nil }
    var addr = sockaddr_in()
    addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = port.bigEndian
    addr.sin_addr = ipv4Addr(ip)
    let rc = withUnsafePointer(to: &addr) { saIn -> Int32 in
        saIn.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
            connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    if rc != 0 { close(fd); return nil }
    return fd
}

final class Tcp {
    let fd: Int32
    deinit { close(fd) }  // 兜底: 任何路径析构必关
    init?(_ ip: String, _ port: UInt16, timeout: Double = 5) {
        guard let f = tcpConnect(ip, port, timeout: timeout) else { return nil }
        fd = f
    }
    @discardableResult
    func send(_ d: Data) -> Int {
        var total = 0
        d.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var off = 0
            while off < d.count {
                let n = write(fd, raw.baseAddress!.advanced(by: off), d.count - off)
                if n <= 0 { return }
                total += n
                off += n
            }
        }
        return total
    }
    @discardableResult
    func recv(_ buf: inout [UInt8], _ timeout: Double) -> Int {
        var tv = makeTimeout(timeout)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        return read(fd, &buf, buf.count)
    }
    func rstClose() {
        var ling = linger(l_onoff: 1, l_linger: 0)
        setsockopt(fd, SOL_SOCKET, SO_LINGER, &ling, socklen_t(MemoryLayout<linger>.size))
        close(fd)
    }
    /// 等打印机关闭连接 (释放扫描名额)
    func waitPeerClose(_ seconds: Double) {
        var tv = makeTimeout(seconds)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var buf = [UInt8](repeating: 0, count: 4096)
        while read(fd, &buf, buf.count) > 0 {}
    }
}

// MARK: - 远程重启 (逆向自手机 App cmd_print_reboot, 走 9100 打印通道)

enum PrinterControl {
    /// 9100 命令帧: 0x10=签名查询, 0x0d=重启, (0x00=WiFi配置查询)
    static func command(ip: String, cmd: UInt8, timeout: Double = 4) -> Data? {
        guard let t = Tcp(ip, 9100, timeout: timeout) else { return nil }
        defer { t.rstClose() }
        t.send(Data([0x4d, 0x3c, 0x2b, 0x1a, 0x07, 0x01, 0x03, 0x00, 0x13, 0x01, cmd]))
        var buf = [UInt8](repeating: 0, count: 64)
        let n = t.recv(&buf, 3)
        return n > 0 ? Data(buf[0..<n]) : Data()
    }

    static func reboot(ip: String) -> Bool {
        guard let r = command(ip: ip, cmd: 0x0d) else { return false }
        return r.count >= 9 && r[8] == 0x0d
    }

    /// 等打印机重启完成: 固定冷却 + 上线轮询 + 扫描单元初始化余量
    static func waitReboot(ip: String, maxSeconds: Double = 300) -> Bool {
        Thread.sleep(forTimeInterval: 60)          // 重启期(下线窗口短, 不硬等"观察到下线")
        let deadline = Date().addingTimeInterval(maxSeconds - 60)
        while Date() < deadline {
            if PrinterStatus.probe9100(ip: ip) {
                Thread.sleep(forTimeInterval: 30)   // 扫描单元初始化余量
                return true
            }
            Thread.sleep(forTimeInterval: 5)
        }
        return false
    }
}

// MARK: - 设备发现 (SNMP 广播, 手机同款)

enum PrinterDiscovery {
    static func discover(timeout: Double = 2.5) -> [String] {
        let query: [UInt8] = [
            0x30,0x2d,0x02,0x01,0x00,0x04,0x06,0x70,0x75,0x62,0x6c,0x69,0x63,
            0xa0,0x20,0x02,0x01,0x05,0x02,0x01,0x00,0x02,0x01,0x00,0x30,0x15,0x30,0x13,0x06,0x0f,
            0x2b,0x06,0x01,0x04,0x01,0x95,0x0b,0x01,0x02,0x01,0x02,0x01,0x01,0x03,0x01,0x05,0x00]
        var found: [String] = []
        guard let bc = broadcastAddress() else { return [] }
        let sock = socket(AF_INET, SOCK_DGRAM, 0)
        guard sock >= 0 else { return [] }
        var yes: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        var tv = makeTimeout(timeout)
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(161).bigEndian
        addr.sin_addr = ipv4Addr(bc)
        query.withUnsafeBufferPointer { qp in
            withUnsafePointer(to: &addr) { saIn in
                saIn.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    _ = sendto(sock, qp.baseAddress, query.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            var from = sockaddr_in()
            var flen = socklen_t(MemoryLayout<sockaddr_in>.size)
            var buf = [UInt8](repeating: 0, count: 2048)
            let n = withUnsafeMutablePointer(to: &from) { saIn -> Int in
                saIn.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(sock, &buf, buf.count, 0, sa, &flen)
                }
            }
            if n > 100 {
                let a = UInt32(from.sin_addr.s_addr).bigEndian
                let ip = "\(a >> 24 & 0xFF).\(a >> 16 & 0xFF).\(a >> 8 & 0xFF).\(a & 0xFF)"
                if ip != "0.0.0.0" && !found.contains(ip) { found.append(ip) }
            }
        }
        close(sock)
        return found
    }

    private static func broadcastAddress() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return nil }
        var ip: UInt32 = 0, mask: UInt32 = 0
        var p: UnsafeMutablePointer<ifaddrs>? = ifaddr
        while let cur = p {
            defer { p = cur.pointee.ifa_next }
            let name = String(cString: cur.pointee.ifa_name)
            let fam = cur.pointee.ifa_addr?.pointee.sa_family
            if (name == "en0" || name == "en1") && fam == UInt8(AF_INET) {
                let sin = cur.pointee.ifa_addr!.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                ip = UInt32(sin.sin_addr.s_addr).bigEndian
                if let nm = cur.pointee.ifa_netmask {
                    mask = nm.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32($0.pointee.sin_addr.s_addr).bigEndian }
                }
            }
        }
        freeifaddrs(ifaddr)
        guard ip != 0, mask != 0 else { return nil }
        let bc = ip | (~mask & 0xFFFF_FFFF)
        return "\(bc >> 24 & 0xFF).\(bc >> 16 & 0xFF).\(bc >> 8 & 0xFF).\(bc & 0xFF)"
    }
}

// MARK: - 打印机状态

struct PrinterStatus {
    var online = false
    var tonerPercent = 0
    var isScannerRunning = false
    var printedPages = 0        // 当前墨粉已印页数
    var standardPages = 0       // 标准页数(总量)

    static func fetch(ip: String) -> PrinterStatus {
        var st = PrinterStatus()
        st.online = probe9100(ip: ip)
        st.isScannerRunning = st.online && snmpScannerAlive(ip: ip)
        if st.online { st.tonerPercent = snmpToner(ip: ip) }
        // Web状态页有完整真值(墨粉%/页数)——HTTPS抓取
        if st.online, let web = webStatus(ip: ip) {
            st.tonerPercent = web.0
            st.printedPages = web.1
            st.standardPages = web.2
        }
        return st
    }

    /// 信任自签证书的session(打印机HTTPS是自签的)
    final class TrustSelfSigned: NSObject, URLSessionDelegate {
        func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                       completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
            if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
               let trust = challenge.protectionSpace.serverTrust {
                completionHandler(.useCredential, URLCredential(trust: trust))
            } else {
                completionHandler(.performDefaultHandling, nil)
            }
        }
        static let session = URLSession(configuration: .ephemeral, delegate: TrustSelfSigned(), delegateQueue: nil)
    }

    /// 抓Web状态页: (墨粉%, 已印页, 标准页)
    static func webStatus(ip: String) -> (Int, Int, Int)? {
        var request = URLRequest(url: URL(string: "https://\(ip)/status.asp")!)
        request.timeoutInterval = 5
        let sem = DispatchSemaphore(value: 0)
        var out: (Int, Int, Int)? = nil
        TrustSelfSigned.session.dataTask(with: request) { data, _, _ in
            defer { sem.signal() }
            guard let d = data, let s = String(data: d, encoding: .utf8) else { return }
            let plain = s.replacingOccurrences(of: "<[^>]+>", with: "|", options: .regularExpression)
            let parts = plain.components(separatedBy: "|").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            var toner = 0, printed = 0, std = 0
            // HTML实体解码+跳过空白项取值(实测: 值在标签后第2个非空项)
            let decoded = parts.map {
                $0.replacingOccurrences(of: "&#37;", with: "%")
                 .replacingOccurrences(of: "&amp;", with: "&")
            }
            func valueAfter(_ keyword: String) -> Int {
                for (i, p) in decoded.enumerated() where p.contains(keyword) {
                    for j in (i+1)..<min(i+4, decoded.count) {
                        let digits = decoded[j].filter { $0.isNumber }
                        if !digits.isEmpty { return Int(digits) ?? 0 }
                    }
                }
                return 0
            }
            toner = valueAfter("Toner Volume")
            printed = valueAfter("Print Page Number of Current")
            std = valueAfter("Standard Page Number of Current")
            out = (toner, printed, std)
        }.resume()
        sem.wait()
        return out
    }

    static func probe9100(ip: String) -> Bool {
        guard let t = Tcp(ip, 9100, timeout: 3) else { return false }
        defer { t.rstClose() }
        t.send(Data([0x4d,0x3c,0x2b,0x1a,0x07,0x01,0x03,0x00,0x13,0x01,0x10]))
        var buf = [UInt8](repeating: 0, count: 128)
        let n = t.recv(&buf, 2)
        return n >= 8 && buf[0] == 0x4d
    }

    static func snmpToner(ip: String) -> Int {
        guard let r = snmpGet(ip: ip, oid: [1,3,6,1,4,1,2699,1,2,1,2,1,1,3,1,5,0]) else { return 0 }
        guard let st = r.range(of: Data("STS:".utf8)) else { return 0 }
        var end = r.endIndex
        if let semi = r.range(of: Data(";".utf8), in: st.upperBound..<r.endIndex) { end = semi.lowerBound }
        guard let blob = Data(base64Encoded: r.subdata(in: st.upperBound..<end)) else { return 0 }
        return blob.count > 100 ? Int(blob[80]) : 0
    }

    static func snmpScannerAlive(ip: String) -> Bool {
        guard let r = snmpGet(ip: ip, oid: [1,3,6,1,2,1,25,3,2,1,5,102]) else { return false }
        return r.suffix(12).contains(Data([0x02, 0x01, 0x02]))
    }

    static func snmpGet(ip: String, oid: [Int]) -> Data? {
        func encOID(_ parts: [Int]) -> [UInt8] {
            var out = [UInt8()]
            out[0] = UInt8(parts[0] * 40 + parts[1])
            for p in parts.dropFirst(2) {
                var v = p, rev = [UInt8]()
                repeat { rev.append(UInt8(v & 0x7F)); v >>= 7 } while v > 0
                for (i, b) in rev.enumerated() { out.append(i == rev.count - 1 ? b : b | 0x80) }
            }
            return out
        }
        let oidB = encOID(oid)
        var vb: [UInt8] = [0x30, UInt8(oidB.count + 2), 0x06, UInt8(oidB.count)]
        vb += oidB
        let vbl: [UInt8] = [0x30, UInt8(vb.count)] + vb
        let comm: [UInt8] = Array("public".utf8)
        var pdu: [UInt8] = [0xa0, UInt8(vbl.count + 9), 0x02, 0x01, 0x61, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00]
        pdu += vbl
        var msg: [UInt8] = [0x30, UInt8(comm.count + 5 + pdu.count), 0x02, 0x01, 0x00, 0x04, UInt8(comm.count)]
        msg += comm
        msg += pdu
        guard msg.count < 128 else { return nil }
        let sock = socket(AF_INET, SOCK_DGRAM, 0)
        guard sock >= 0 else { return nil }
        defer { close(sock) }
        var tv = makeTimeout(1.5)
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(161).bigEndian
        addr.sin_addr = ipv4Addr(ip)
        var sent = -1
        msg.withUnsafeBufferPointer { mp in
            withUnsafePointer(to: &addr) { saIn in
                saIn.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    sent = sendto(sock, mp.baseAddress, msg.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent > 0 else { return nil }
        var buf = [UInt8](repeating: 0, count: 2048)
        let n = read(sock, &buf, buf.count)
        guard n > 10 else { return nil }
        return Data(buf[0..<n])
    }
}

// MARK: - 局域网指纹扫描器 (9100签名帧指纹=4d3c2b1a..[10]=0x10, 75B回包含序列号; SNMP广播发现的校验与兜底)

enum LanScanner {
    /// 扫本机所有en接口的/24段, 找M7208W. 返回IP或nil
    static func find(timeoutMs: Int32 = 500, done: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            done(scan(timeoutMs: timeoutMs))
        }
    }

    static func subnets() -> [String] {
        var out = Set<String>()
        var ifa: UnsafeMutablePointer<ifaddrs>? = nil
        guard getifaddrs(&ifa) == 0, let first = ifa else { return [] }
        defer { freeifaddrs(ifa) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            defer { p = cur.pointee.ifa_next }
            guard let sa = cur.pointee.ifa_addr, sa.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let name = String(cString: cur.pointee.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var addr = sockaddr_in()
            memcpy(&addr, sa, MemoryLayout<sockaddr_in>.size)
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &addr.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN)) != nil else { continue }
            let parts = String(cString: buf).split(separator: ".")
            guard parts.count == 4 else { continue }
            out.insert("\(parts[0]).\(parts[1]).\(parts[2])")
        }
        return Array(out)
    }

    /// 兜底发现: 广播ping填ARP表 → 只对活邻居逐个验指纹(避开TUN下批量SYN被吞的坑, 10-18实测)
    static func scan(timeoutMs: Int32) -> String? {
        let bases = subnets()
        guard !bases.isEmpty else { return nil }
        // ① 广播ping(错误忽略, 目的只是填ARP表)
        for base in bases {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/sbin/ping")
            p.arguments = ["-c", "1", "-t", "1", "\(base).255"]
            p.standardOutput = FileHandle.nullDevice
            p.standardError = FileHandle.nullDevice
            try? p.run()
        }
        Thread.sleep(forTimeInterval: 1.2)
        // ② 解析 arp -a
        let arp = Process()
        arp.executableURL = URL(fileURLWithPath: "/usr/sbin/arp")
        arp.arguments = ["-a"]
        let pipe = Pipe(); arp.standardOutput = pipe
        try? arp.run(); arp.waitUntilExit()
        let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        var cands: [String] = []
        for line in out.split(separator: "\n") {
            // 形如: ? (192.168.1.100) at 00:11:22:33:44:55 on en0 ifscope [ethernet]
            guard let open = line.firstIndex(of: "("), let close = line.firstIndex(of: ")") else { continue }
            let ip = String(line[line.index(after: open)..<close])
            guard line.contains("ethernet"), !line.contains("incomplete") else { continue }
            let parts = ip.split(separator: ".")
            guard parts.count == 4, bases.contains("\(parts[0]).\(parts[1]).\(parts[2])") else { continue }
            if !cands.contains(ip) { cands.append(ip) }
        }
        // 网关也加进去; ARP表万一读空(受管控环境), 补扫常见打印机地址段
        for base in bases where !cands.contains("\(base).1") { cands.append("\(base).1") }
        if cands.count < 3 {
            for h in 100...149 { cands.append("\(bases[0]).\(h)") }
        }
        // ③ 逐个验指纹(活邻居一般<30个, 顺序探测)
        for ip in cands {
            if probe(ip, timeoutMs: timeoutMs) { return ip }
        }
        return nil
    }

    /// 非阻塞连9100 → 发11B签名帧 → 验回包指纹
    static func probe(_ ip: String, timeoutMs: Int32) -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(9100).bigEndian
        addr.sin_addr = ipv4Addr(ip)
        let rc = withUnsafePointer(to: &addr) { saIn in
            saIn.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if rc != 0 {
            var pfd = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
            guard poll(&pfd, 1, timeoutMs) > 0 else { return false }
        }
        var err: Int32 = 0; var len = socklen_t(MemoryLayout<Int32>.size)
        guard getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len) == 0, err == 0 else { return false }
        let frame: [UInt8] = [0x4d,0x3c,0x2b,0x1a,0x07,0x01,0x03,0x00,0x13,0x01,0x10]
        _ = frame.withUnsafeBufferPointer { ptr -> Bool in
            var sent = 0
            while sent < 11 {
                let n = send(fd, ptr.baseAddress! + sent, 11 - sent, 0)
                if n <= 0 { return false }
                sent += n
            }
            return true
        }
        var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        guard poll(&pfd, 1, timeoutMs) > 0 else { return false }
        var buf = [UInt8](repeating: 0, count: 16)
        let n = recv(fd, &buf, buf.count, 0)
        guard n >= 11 else { return false }
        return buf[0] == 0x4d && buf[1] == 0x3c && buf[2] == 0x2b && buf[3] == 0x1a && buf[10] == 0x10
    }

    /// 本机eSCL守护存活否(系统扫描仪健康检查)
    static func daemonAlive() -> Bool {
        guard let fd = tcpConnect("127.0.0.1", 8090, timeout: 1.5) else { return false }
        close(fd)
        return true
    }
}
