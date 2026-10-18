import Foundation
import AppKit
import Network

// MARK: - eSCL/AirScan 代理 (系统级扫描集成, 零特批签名)
// 在本机宣告 _uscan._tcp, macOS图像捕捉/预览会直接出现这台"扫描仪"

final class ESCLProxy {
    static let shared = ESCLProxy()
    private var listener: NWListener?
    private(set) var running = false
    var printerIP = "192.168.1.100"

    private let queue = DispatchQueue(label: "escl.proxy")

    func start(ip: String) {
        printerIP = ip
        guard !running else { return }
        // eSCL固定用8090端口 + tls=0(明文)
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            listener = try NWListener(using: params, on: 8090)
        } catch { return }
        listener?.newConnectionHandler = { [weak self] conn in
            self?.handle(conn)
        }
        listener?.start(queue: queue)
        running = true
        registerBonjour()
    }

    func stop() {
        listener?.cancel()
        listener = nil
        running = false
    }

    private var bonjour: NetService?
    private func registerBonjour() {
        // _uscan._tcp + eSCL TXT (Apple标准字段)
        bonjour = NetService(domain: "local.", type: "_uscan._tcp.", name: "Lenovo M7208W (Studio)", port: 8090)
        let txt: [String: String] = [
            "txtvers": "1",
            "vers": "2.0",            // eSCL版本
            "pdl": "image/jpeg,image/pwg-raster",  // 支持的输出格式
            "ty": "Lenovo M7208W",
            "rs": "eSCL",             // 扫描服务类型
            "note": "",
            "uri": "http://local./eSCL/",
            "uuid": "6ba71f2c-1e4d-5e2b-9a3c-002000000000"  // 任意稳定UUID
        ]
        let txtData = txt.mapValues { Data($0.utf8) }
        bonjour?.setTXTRecord(NetService.data(fromTXTRecord: txtData))
        bonjour?.publish()
    }

    // MARK: HTTP 处理

    private func handle(_ conn: NWConnection) {
        conn.start(queue: queue)
        recvRequest(conn) { data in
            guard let data, let req = String(data: data, encoding: .utf8) else {
                conn.cancel(); return
            }
            self.respond(conn, req)
        }
    }

    private func recvRequest(_ conn: NWConnection, done: @escaping (Data?) -> Void) {
        var buf = Data()
        func readMore() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { chunk, _, isComplete, error in
                if let c = chunk { buf.append(c) }
                if error != nil { done(buf.isEmpty ? nil : buf); return }
                if isComplete || buf.range(of: Data("\r\n\r\n".utf8)) != nil {
                    // 头收齐; POST /ScanJobs还需读body(Content-Length)
                    if let cl = self.contentLength(of: buf), buf.count < cl + self.headerLen(buf) {
                        readMore(); return
                    }
                    done(buf)
                } else { readMore() }
            }
        }
        readMore()
    }

    private func contentLength(of d: Data) -> Int? {
        let s = String(data: d, encoding: .utf8) ?? ""
        for line in s.components(separatedBy: "\r\n") {
            if line.lowercased().hasPrefix("content-length:") {
                return Int(line.split(separator: ":")[1].trimmingCharacters(in: .whitespaces))
            }
        }
        return nil
    }
    private func headerLen(_ d: Data) -> Int {
        d.range(of: Data("\r\n\r\n".utf8))?.lowerBound ?? 0
    }

    private func respond(_ conn: NWConnection, _ raw: String) {
        let lines = raw.components(separatedBy: "\r\n")
        guard let request = lines.first else { conn.cancel(); return }
        let parts = request.components(separatedBy: " ")
        guard parts.count >= 2 else { conn.cancel(); return }
        let method = parts[0], path = parts[1]
        // 请求日志(排障用)
        let logLine = "[eSCL] \(method) \(path)\n"
        FileHandle.standardError.write(logLine.data(using: .utf8)!)
        // 提取body(XML)
        let bodyStart = raw.range(of: "\r\n\r\n").map { raw.index($0.upperBound, offsetBy: 0) } ?? raw.endIndex
        let body = String(raw[bodyStart...])

        switch (method, path) {
        case ("GET", "/eSCL/ScannerCapabilities"):
            send(conn, status: "200 OK", type: "text/xml", body: capabilitiesXML)
        case ("POST", "/eSCL/ScanJobs"):
            pendingOptions = parseScanSettings(body)
            let jobLocation = "/eSCL/ScanJobs/1"
            send(conn, status: "201 Created", type: "text/plain", body: Data(),
                 extra: ["Location: \(jobLocation)"])
            // 扫描由NextDocument请求驱动(AirScan标准时序)
        case ("GET", "/eSCL/ScanJobs/1/NextDocument"):
            // AirScan等得起: 阻塞到扫描完成(最多90s), 完成前不回404
            var img: NSImage? = nil
            if img == nil {
                img = performScan(options: pendingOptions)
            }
            if img == nil && isOverviewJob {
                // 预览无缓存: 占位白图(扫描头不动; 用户首次真扫后缓存自动生效)
                send(conn, status: "200 OK", type: "image/jpeg", body: blankJpeg())
            } else if let img = img {
                if isOverviewJob {
                    send(conn, status: "200 OK", type: "image/jpeg", body: smallJpeg(img))
                } else {
                    // 正式扫: 永远返回本次实扫结果(用户要的就是当前玻璃上的内容)
                    send(conn, status: "200 OK", type: "image/jpeg", body: jpegData(from: img))
                }
            } else {
                send(conn, status: "503 Service Unavailable", type: "text/plain",
                     body: Data("ScannerUnavailable".utf8))
            }
        case ("DELETE", "/eSCL/ScanJobs/1"):
            send(conn, status: "200 OK", type: "text/plain", body: Data())
        case ("GET", "/eSCL/ScannerStatus"):
            send(conn, status: "200 OK", type: "text/xml", body: statusXML)
        default:
            send(conn, status: "404 Not Found", type: "text/plain", body: Data())
        }
    }

    private var pendingOptions = ScanOptions()
    private var scanLock = NSLock()
    private var cachedFullScan: NSImage?   // 全幅缓存(预览和正式扫共用, 避免扫两次)
    private var cacheTime: Date = .distantPast
    private var lastRealScanAt = Date.distantPast   // 实扫节流

    private func performScan(options: ScanOptions? = nil) -> NSImage? {
        return performScan(options: options, formal: !isOverviewJob)
    }

    /// formal=true(用户点了扫描): 强制真扫, 不吃缓存
    /// formal=false(系统预览/后台轮询): 缓存优先+60s节流, 打印机零成本
    private func performScan(options: ScanOptions?, formal: Bool) -> NSImage? {
        scanLock.lock(); defer { scanLock.unlock() }
        if !formal {
            // 预览/系统轮询: 只吃缓存, 永不触发实扫——扫描头只在用户点扫描时动
            return cachedFullScan
        }
        // ① 忙等待(打印机正在打印): 每15s重试,最多5次(75s)——绝不动打印机; 死锁立即转恢复
        for _ in 0..<5 {
            switch tryScanOnce(options ?? ScanOptions()) {
            case .image(let i):
                cachedFullScan = i; cacheTime = Date(); lastRealScanAt = Date()
                return i
            case .busy:
                FileHandle.standardError.write("[eSCL] 打印机忙(打印中),等待15s\n".data(using: .utf8)!)
                Thread.sleep(forTimeInterval: 15)
            case .dead:
                return recoverFromDead(options ?? ScanOptions())
            }
        }
        // ② 忙完5次仍忙: 打印机在持续干活, 返回空让系统稍后再试(不重启)
        FileHandle.standardError.write("[eSCL] 持续忙75s(长打印任务),让路\n".data(using: .utf8)!)
        return nil
    }

    private func recoverFromDead(_ o: ScanOptions) -> NSImage? {
        FileHandle.standardError.write("[eSCL] 进入恢复流程\n".data(using: .utf8)!)
        switch tryScanOnce(o) {
        case .image(let i):
            cachedFullScan = i; cacheTime = Date(); lastRealScanAt = Date()
            return i
        case .busy:
            return nil  // 打印机一直在忙=它活着在干活,返回空(系统显示稍后再试),绝不停机
        case .dead:
            PrinterControl.reboot(ip: printerIP)
            _ = PrinterControl.waitReboot(ip: printerIP)  // 等3分钟上线窗口
            if case .image(let i) = tryScanOnce(o) {
                cachedFullScan = i; cacheTime = Date(); lastRealScanAt = Date()
                return i
            }
            return nil
        }
    }

    /// 扫描结果: image=成功 / busy=打印机正忙(打印中,不许动它) / dead=真死锁
    private enum ScanOutcome {
        case image(NSImage)
        case busy
        case dead
    }

    private func tryScanOnce(_ o: ScanOptions) -> ScanOutcome {
        let session = ScanSession(ip: printerIP)
        do {
            let r = try session.scanNoRetry(o)
            FileHandle.standardError.write("[eSCL] 单发成功 \(r.pixels.count)B\n".data(using: .utf8)!)
            let px = ScanDecoder.postProcess(r.pixels, width: r.width, mode: .mixed, density: 0)
            guard let cg = ScanDecoder.makeImage(px, width: r.width) else { return .dead }
            return .image(NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height)))
        } catch let e as ScanError {
            FileHandle.standardError.write("[eSCL] \(e.retryable ? "忙(打印中)" : "死锁")\n".data(using: .utf8)!)
            return e.retryable ? .busy : .dead
        } catch {
            return .dead
        }
        // 注: refused/timeout目前与dead同路径; 恢复流程的reboot有consent保护(macOS GUI侧),
        // eSCL侧无UI, 若误重启率实测偏高再收紧
    }

    /// 占位白图(预览缓存未就绪时用, 扫描头不动)
    private func blankJpeg() -> Data {
        let img = NSImage(size: NSSize(width: 600, height: 848))
        img.lockFocus()
        NSColor.white.setFill()
        NSRect(x: 0, y: 0, width: 600, height: 848).fill()
        img.unlockFocus()
        guard let tiff = img.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let jpg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.5])
        else { return Data() }
        return jpg
    }

    private func smallJpeg(_ img: NSImage) -> Data {
        // 等比降到600px宽
        let ratio = 600.0 / img.size.width
        let newSize = NSSize(width: 600, height: img.size.height * ratio)
        let thumb = NSImage(size: newSize)
        thumb.lockFocus()
        img.draw(in: NSRect(origin: .zero, size: newSize))
        thumb.unlockFocus()
        guard let tiff = thumb.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let jpg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.7])
        else { return Data() }
        return jpg
    }

    private func jpegData(from img: NSImage) -> Data {
        guard let tiff = img.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let jpg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85])
        else { return Data() }
        return jpg
    }

    private func send(_ conn: NWConnection, status: String, type: String, body: Data, extra: [String] = []) {
        var head = "HTTP/1.1 \(status)\r\nServer: M7208W-Studio\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\n"
        for e in extra { head += e + "\r\n" }
        head += "\r\n"
        var out = Data(head.utf8); out.append(body)
        conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
    }

    /// 解析图像捕捉发来的ScanSettings XML → 我们的ScanOptions
    private var isOverviewJob = false

    private func parseScanSettings(_ xml: String) -> ScanOptions {
        var o = ScanOptions()
        // 预览扫判定: Intent=Preview 或 显式低DPI(系统预览常用75/96/150)
        isOverviewJob = xml.contains("Preview") || xml.contains("<scan:Intent>Document</scan:Intent>") == false && xml.contains("Overview")
        if let r = xml.range(of: "<scan:Resolution>(\\d+)</scan:Resolution>", options: .regularExpression) {
            let num = Int(xml[r].replacingOccurrences(of: "\\D", with: "")) ?? 300
            if num <= 150 { isOverviewJob = true }
        }
        // 分辨率
        if let r = xml.range(of: "<scan:Resolution>(\\d+)</scan:Resolution>", options: .regularExpression) {
            let num = Int(xml[r].replacingOccurrences(of: "\\D", with: "")) ?? 300
            o.dpi = num >= 600 ? 600 : (num >= 300 ? 300 : (num >= 200 ? 200 : (num >= 150 ? 150 : (num >= 100 ? 100 : 75))))
        }
        // 色彩模式: Grayscale8→灰度, BlackAndWhite1→文本
        if xml.contains("BlackAndWhite1") { o.mode = .text }
        else if xml.contains("Grayscale8") { o.mode = ifPaperTexture(xml) }
        // 区域: eSCL的x/y/width/height单位=1/300英寸 → 我们的px(同300dpi,直接用)
        func intOf(_ tag: String) -> Int? {
            let pat = "<scan:\(tag)>(\\d+)</scan:\(tag)>"
            guard let r = xml.range(of: pat, options: .regularExpression) else { return nil }
            return Int(xml[r].replacingOccurrences(of: "\\D", with: ""))
        }
        if let w = intOf("Width"), let h = intOf("Height"), w > 100, h > 100 {
            // 设备实际输出行宽是2496(比请求宽), 高度按比例放大
            let realW = 2496
            o.customW = min(w, realW)
            o.customH = Int(Double(h) * Double(realW) / Double(w))
        }
        return o
    }
    private func ifPaperTexture(_ xml: String) -> ScanMode { xml.contains("Photograph") ? .photo : .mixed }

    // MARK: eSCL XML

    private var capabilitiesXML: Data {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <scan:ScannerCapabilities xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
          <scan:Platen>
            <scan:PlatenInputCaps>
              <scan:MinWidth>50</scan:MinWidth><scan:MaxWidth>2550</scan:MaxWidth>
              <scan:MinHeight>50</scan:MinHeight><scan:MaxHeight>3508</scan:MaxHeight>
              <scan:MaxScanRegions>1</scan:MaxScanRegions>
              <scan:SettingProfiles>
                <scan:SettingProfile><scan:ColorModes><scan:ColorMode>Grayscale8</scan:ColorMode></scan:ColorModes>
                <scan:Resolutions><scan:Resolution>300</scan:Resolution><scan:Resolution>600</scan:Resolution></scan:Resolutions>
                <scan:CDTRegions><scan:CDTRegion><scan:Height><scan:Min>1</scan:Min><scan:Max>2970</scan:Max><scan:Default>2970</scan:Default><scan:Step>1</scan:Step></scan:Height>
                <scan:Width><scan:Min>1</scan:Min><scan:Max>2100</scan:Max><scan:Default>2100</scan:Default><scan:Step>1</scan:Step></scan:Width>
                <scan:XYOffset><scan:XMin>0</scan:XMin><scan:YMin>0</scan:YMin></scan:XYOffset></scan:CDTRegion></scan:CDTRegions></scan:SettingProfile>
              </scan:SettingProfiles>
            </scan:PlatenInputCaps>
          </scan:Platen>
          <scan:DeviceSettings>
            <scan:DuplexSupported>false</scan:DuplexSupported>
          </scan:DeviceSettings>
        </scan:ScannerCapabilities>
        """
        return Data(xml.utf8)
    }

    private var statusXML: Data {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <scan:ScannerStatus xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
          <scan:ScannerState>Idle</scan:ScannerState>
          <scan:AdfState>ScannerAdfEmpty</scan:AdfState>
        </scan:ScannerStatus>
        """
        return Data(xml.utf8)
    }
}
