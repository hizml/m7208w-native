import SwiftUI
let plusIconName = "rectangle.stack.badge.plus"
import AppKit

@main
struct M7208WApp: App {
    @StateObject private var model = AppModel()
    init() {
        signal(SIGPIPE, SIG_IGN)   // 网段扫描/打印机RST时send()会SIGPIPE杀进程(10-18实测退出码141)
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .frame(minWidth: 760, minHeight: 580)
        }
    }
}

// MARK: - 状态模型

final class AppModel: ObservableObject {
    @Published var ip: String = UserDefaults.standard.string(forKey: "printerIP") ?? "192.168.1.100"
    @Published var discovering = false
    @Published var esclAlive = false
    @Published var options = ScanOptions()
    @Published var status = PrinterStatus()
    @Published var scanning = false
    @Published var progressBytes = 0
    @Published var resultImage: NSImage?
    @Published var message: String?
    @Published var cliMode = false
    @Published var copies = 1
    @Published var showRecovery = false
    @Published var recovering = false
    @Published var copyOptions = CopyOptions()
    @Published var autoRotate = true
    @Published var wifiInfo: (String, String)?
    @Published var newSSID = ""
    @Published var newPass = ""
    @Published var showWifiConfirm = false
    @Published var foundNetworks: [String] = []
    @Published var wifiEnc = 3
    @Published var pdfQueue: [NSImage] = []
    @Published var livePreview: NSImage?
    private var lastLiveRows = 0
    /// 设备互斥锁: 打印/扫描/复印同一时刻只允许一个占用打印机
    @Published var deviceBusy = false
    @Published var pdfPages: [[UInt8]] = []
    @Published var pdfWidths: [Int] = []
    @Published var historyVersion = 0

    init() {
        let args = CommandLine.arguments
        if args.contains("--reboot-test") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [self] in
                print("诊断: probe9100=\(PrinterStatus.probe9100(ip: ip))")
            let raw = PrinterControl.command(ip: ip, cmd: 0x0d)
            print("诊断: 原始回执=\(raw?.map { String(format: "%02x", $0) }.joined(separator: " ") ?? "nil")")
            print("① reboot() = \(PrinterControl.reboot(ip: ip))")
                print("② waitReboot() = \(PrinterControl.waitReboot(ip: ip))")
                print("③ 重扫...")
                do {
                    let r = try ScanSession(ip: ip).scan(options)
                    print("   \(r.pixels.count)B 宽\(r.width) \(r.pixels.count > 100000 ? "✅全链路" : "❌")")
                } catch { print("   ❌ \(error.localizedDescription)") }
                exit(0)
            }
            return
        }
        if let idx = args.firstIndex(of: "--scan-test"), args.count > idx + 1 {
            cliMode = true
            let out = args[idx + 1]
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [self] in
                runScan { ok in
                    if ok, let img = resultImage,
                       let tiff = img.tiffRepresentation,
                       let rep = NSBitmapImageRep(data: tiff),
                       let png = rep.representation(using: .png, properties: [:]) {
                        try? png.write(to: URL(fileURLWithPath: out))
                        print("SAVED \(out)")
                        exit(0)
                    }
                    print("FAIL \(message ?? "unknown")")
                    exit(1)
                }
            }
        }
    }

    func persist() { UserDefaults.standard.set(ip, forKey: "printerIP") }

    /// 自动发现打印机: ①SNMP广播(手机同款)+指纹校验 ②兜底全网段9100指纹扫描; 找到自动填IP
    func discoverPrinter(auto: Bool = false) {
        if discovering { return }
        discovering = true
        if !auto { message = "🔍 扫描局域网找打印机…" }
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            var hit: String? = nil
            for cand in PrinterDiscovery.discover(timeout: 2.0) {
                if LanScanner.probe(cand, timeoutMs: 600) { hit = cand; break }
            }
            if hit == nil { hit = LanScanner.scan(timeoutMs: 450) }
            DispatchQueue.main.async {
                discovering = false
                if let hit = hit {
                    ip = hit; persist()
                    message = auto ? "已自动找到打印机 \(hit)" : "✅ 找到打印机 \(hit)，已填入"
                    refreshStatus()
                } else if !auto {
                    message = "❌ 没找到(确认打印机和电脑同网段、打印机已开机)"
                }
            }
        }
    }

    /// 一键启用系统扫描仪: 部署eSCL守护(内置二进制)+装LaunchAgent开机自启+拉起+验证
    /// 用户域LaunchAgent, 无需sudo
    func installSystemScanner() {
        message = "安装系统扫描仪…"
        DispatchQueue.global().async { [self] in
            let fm = FileManager.default
            // ① 守护二进制: App内置资源 → Application Support
            var src = Bundle.module.path(forResource: "M7208WeSCL", ofType: nil)
                ?? Bundle.main.path(forResource: "M7208WeSCL", ofType: nil)
            let dstDir = NSHomeDirectory() + "/Library/Application Support/M7208WStudio"
            let dst = dstDir + "/M7208WeSCL"
            if src == nil || !fm.fileExists(atPath: src!) {
                src = dst  // 兜底: 已部署过的旧二进制原地复用
            }
            guard let src = src, fm.fileExists(atPath: src) else {
                DispatchQueue.main.async { message = "❌ 找不到守护二进制 M7208WeSCL" }
                return
            }
            do {
                try fm.createDirectory(atPath: dstDir, withIntermediateDirectories: true)
                if fm.fileExists(atPath: dst) { try fm.removeItem(atPath: dst) }
                if src != dst { try fm.copyItem(atPath: src, toPath: dst) }
                chmod(dst, 0o755)
            } catch {
                DispatchQueue.main.async { message = "❌ 复制守护失败: \(error.localizedDescription)" }
                return
            }
            // ② LaunchAgent plist(用当前用户home生成, 可移植)
            let plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key><string>com.zml.m7208w.escl</string>
                <key>ProgramArguments</key>
                <array>
                    <string>\(dst)</string>
                </array>
                <key>RunAtLoad</key><true/>
                <key>KeepAlive</key><true/>
                <key>StandardOutPath</key><string>/tmp/m7208w_escl.log</string>
                <key>StandardErrorPath</key><string>/tmp/m7208w_escl_err.log</string>
            </dict>
            </plist>
            """
            let plistPath = NSHomeDirectory() + "/Library/LaunchAgents/com.zml.m7208w.escl.plist"
            try? fm.createDirectory(atPath: (plistPath as NSString).deletingLastPathComponent,
                                    withIntermediateDirectories: true)
            fm.createFile(atPath: plistPath, contents: plist.data(using: .utf8))
            // ③ 重启服务(bootout旧+bootstrap新), 无需root(用户域)
            let uid = getuid()
            let boot = Process(); boot.executableURL = URL(fileURLWithPath: "/bin/launchctl")
            boot.arguments = ["bootout", "gui/\(uid)/com.zml.m7208w.escl"]
            boot.standardError = FileHandle.nullDevice; boot.standardOutput = FileHandle.nullDevice
            try? boot.run(); boot.waitUntilExit()
            sleep(1)
            let load = Process(); load.executableURL = URL(fileURLWithPath: "/bin/launchctl")
            load.arguments = ["bootstrap", "gui/\(uid)", plistPath]
            load.standardError = FileHandle.nullDevice; load.standardOutput = FileHandle.nullDevice
            do { try load.run(); load.waitUntilExit() } catch {}
            // ④ 验证: 8090端口应监听
            var alive = false
            for _ in 0..<5 {
                if LanScanner.daemonAlive() { alive = true; break }
                usleep(600_000)
            }
            DispatchQueue.main.async {
                esclAlive = alive
                message = alive ? "✅ 系统扫描仪已启用(图像捕捉/预览可见, 开机自启)"
                                : "❌ 守护未起来, 看 /tmp/m7208w_escl_err.log"
            }
        }
    }

    /// 一键修复打印队列: 部署内置arm64驱动+队列重指向当前IP+重装PPD (原桌面"修复打印.command"收编, 2026-10-18)
    func repairPrintQueue() {
        persist()
        let target = ip
        message = "修复中…(会弹密码框)"
        DispatchQueue.global().async { [self] in
            // 驱动二进制: SPM开发态取build目录, 部署态取.app内置资源包
            let bin = Bundle.module.path(forResource: "LNTHR1Z", ofType: "universal")
                ?? Bundle.main.path(forResource: "LNTHR1Z", ofType: "universal")
                ?? ""
            let ok: Bool
            let detail: String
            if !FileManager.default.fileExists(atPath: bin) {
                ok = false; detail = "找不到驱动二进制 LNTHR1Z.universal"
            } else {
                let script = fixScript(bin: bin, ip: target)
                let path = "/tmp/m7208w_fix_\(Int.random(in: 100...999)).sh"
                try? script.write(toFile: path, atomically: true, encoding: .utf8)
                let p = Process()
                p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
                p.arguments = ["-e", "do shell script \"bash \(path)\" with administrator privileges"]
                let out = Pipe(); let err = Pipe()
                p.standardOutput = out; p.standardError = err
                do {
                    try p.run(); p.waitUntilExit()
                    let o = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
                    if p.terminationStatus == 0 && o.contains("FIX_DONE") {
                        ok = true; detail = "驱动已部署, 队列已指向 \(target):9100"
                    } else {
                        ok = false; detail = "退出码\(p.terminationStatus) \(o.prefix(200))"
                    }
                } catch { ok = false; detail = "提权取消或失败: \(error.localizedDescription)" }
                try? FileManager.default.removeItem(atPath: path)
            }
            DispatchQueue.main.async {
                message = ok ? "✅ \(detail)" : "❌ \(detail)"
            }
        }
    }

    /// 修复脚本(值已烘焙, 无参数传递避免引号地狱); PPD=已验证的合法版本(fix-print-queue.sh 同源)
    private func fixScript(bin: String, ip: String) -> String {
        #"""
        #!/bin/bash
        Q="M7208W"
        BIN="#(bin)"
        IP="#(ip)"
        cp "#(bin)" /usr/libexec/cups/filter/LNTHR1Z || exit 1
        chown root:wheel /usr/libexec/cups/filter/LNTHR1Z; chmod 755 /usr/libexec/cups/filter/LNTHR1Z
        cancel -a "$Q" 2>/dev/null
        if ! lpstat -p "$Q" >/dev/null 2>&1; then
          mkdir -p /etc/cups/ppd
          cat > "/etc/cups/ppd/${Q}.ppd" <<'PPD'
        *PPD-Adobe: "4.3"
        *FileVersion: "1.0"
        *FormatVersion: "4.3"
        *LanguageVersion: English
        *Manufacturer: "Lenovo"
        *ModelName: "Lenovo M7208W"
        *ShortNickName: "Lenovo M7208W"
        *NickName: "Lenovo M7208W LHPL (ARM)"
        *PCFileName: "LNVM7208.PPD"
        *Product: "(Lenovo M7208W)"
        *cupsVersion: 1.4
        *cupsManualCopies: True
        *cupsFilter: "application/vnd.cups-raster 0 LNTHR1Z"
        *ColorDevice: False
        *DefaultColorSpace: Gray
        *CUPSBytesPerPixel: 1
        *OpenUI *ColorModel/Color Mode: PickOne
        *OrderDependency: 10 AnySetup *ColorModel
        *DefaultColorModel: Gray
        *ColorModel Gray/Grayscale: "<</cupsColorSpace 0/cupsBitsPerColor 8/cupsColorOrder 0>>setpagedevice"
        *CloseUI: *ColorModel
        *OpenUI *Resolution/Resolution: PickOne
        *OrderDependency: 20 AnySetup *Resolution
        *DefaultResolution: 600dpi
        *Resolution 300dpi/300 dpi: "<</HWResolution[300 300]>>setpagedevice"
        *Resolution 600dpi/600 dpi: "<</HWResolution[600 600]>>setpagedevice"
        *CloseUI: *Resolution
        *OpenUI *PageSize/Media Size: PickOne
        *OrderDependency: 30 AnySetup *PageSize
        *DefaultPageSize: A4
        *PageSize A4/A4: "<</PageSize[595 842]/ImagingBBox null>>setpagedevice"
        *PageSize Letter/US Letter: "<</PageSize[612 792]/ImagingBBox null>>setpagedevice"
        *CloseUI: *PageSize
        *DefaultImageableArea: A4
        *ImageableArea A4: "18 14 577 828"
        *ImageableArea Letter: "18 14 594 778"
        *DefaultPaperDimension: A4
        *PaperDimension A4: "595 842"
        *PaperDimension Letter: "612 792"
        *OpenUI *InputSlot/Paper Source: PickOne
        *OrderDependency: 40 AnySetup *InputSlot
        *DefaultInputSlot: Auto
        *InputSlot Auto/Auto: ""
        *CloseUI: *InputSlot
        *OpenUI *MediaType/Media Type: PickOne
        *OrderDependency: 50 AnySetup *MediaType
        *DefaultMediaType: Plain
        *MediaType Plain/Plain Paper: ""
        *CloseUI: *MediaType
        *OpenUI *Copies/Copies: PickOne
        *OrderDependency: 60 AnySetup *Copies
        *DefaultCopies: 1
        *Copies 1/1: ""
        *CloseUI: *Copies
        *DefaultOutputOrder: Normal
        PPD
          chown root:_lp "/etc/cups/ppd/${Q}.ppd"; chmod 644 "/etc/cups/ppd/${Q}.ppd"
          lpadmin -p "$Q" -v "socket://${IP}:9100" -P "/etc/cups/ppd/${Q}.ppd" -D "Lenovo M7208W" -o printer-error-policy=retry-job
        else
          lpadmin -p "$Q" -v "socket://${IP}:9100"
        fi
        cupsenable "$Q" 2>/dev/null; cupsaccept "$Q" 2>/dev/null
        killall -HUP cupsd 2>/dev/null
        echo FIX_DONE
        """#
    }

    func tonerGet() {
        let t = ip
        DispatchQueue.global().async { [self] in
            let s = TonerControl.getState(ip: t)
            DispatchQueue.main.async { message = s != nil ? "状态码: \(s!) (02=墨粉未尽/开关锁定)" : "读取失败" }
        }
    }
    func tonerSetContinue() {
        let t = ip
        DispatchQueue.global().async { [self] in
            let ok = TonerControl.setContinue(ip: t, on: true)
            DispatchQueue.main.async { message = ok ? "✅ 已开启(墨粉尽后可继续印)" : "❌ 被拒(墨粉未尽时固件锁定此开关)" }
        }
    }
    func tonerReset() {
        let t = ip
        DispatchQueue.global().async { [self] in
            TonerControl.resetCounter(ip: t)
            DispatchQueue.main.async { message = "重置命令已发(看状态页计数变化)" }
        }
    }

    func scanNetworks() {
        let target = ip
        DispatchQueue.global().async { [self] in
            let list = WiFiScanner.scan(ip: target)
            DispatchQueue.main.async {
                foundNetworks = list ?? []
                message = list != nil ? "发现 \(list!.count) 个网络 (点击填充SSID)" : "扫描无结果"
            }
        }
    }

    func writeWiFi() {
        let target = ip
        let s = newSSID, p = newPass
        DispatchQueue.global().async { [self] in
            let ok = WiFiWriter.write(ip: target, ssid: s, password: p, encryption: wifiEnc)
            DispatchQueue.main.async {
                message = ok ? "✅ WiFi配置已写入 (打印机会重启并连新网络)" : "❌ 写入被拒绝"
            }
        }
    }

    func startCopy() {
        guard !deviceBusy, !scanning else {
            message = "⏳ 打印机正忙 (打印/复印/扫描进行中), 请等当前任务完成"
            return
        }
        deviceBusy = true
        message = nil
        let target = ip
        let opts = copyOptions
        DispatchQueue.global().async { [self] in
            let ok = CopyEngine.run(ip: target, opts)
            DispatchQueue.main.async {
                // 复印=机器自己扫+印, 受理后继续锁到打印物理完成(约60s)
                message = ok ? "✅ 复印已受理 (\(opts.copies)份) — 打印完成前请勿扫描" : "❌ 复印被拒绝"
                if ok { lockDeviceForPrint(seconds: 90) } else { deviceBusy = false }
            }
        }
    }

    /// 流式预览: 扫描进行中实时出图(每~150行刷新)
    private func liveRender(raw: Data) {
        // 用解码器安全提取(与最终成图同路径, 绝不越界)
        let (px, w) = ScanDecoder.extract(raw, expectedH: 0, dpi: 300)
        let rows = px.count / w
        guard rows - lastLiveRows >= 150, rows > 10 else { return }
        lastLiveRows = rows
        guard let cg = ScanDecoder.makeImage(Array(px[0..<(rows * w)]), width: w) else { return }
        let img = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
        DispatchQueue.main.async { self.livePreview = img }
    }

    func addToPdfQueue() {
        if let img = resultImage { pdfQueue.append(img) }
        message = "已加入PDF队列 (\(pdfQueue.count)页)"
    }

    func exportPdf() {
        guard !pdfQueue.isEmpty else { return }
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.pdf]
        panel.nameFieldStringValue = "扫描合集_\(Date().formatted(.dateTime.month().day().hour().minute())).pdf"
        if panel.runModal() == .OK, let url = panel.url {
            let ok = PDFExporter.export(pages: pdfQueue, to: url)
            if ok {
                pdfQueue = []
                message = "✅ PDF已导出"
            }
        }
    }

    func readWiFi() {
        let target = ip
        DispatchQueue.global().async { [self] in
            let r = WiFiReader.read(ip: target)
            DispatchQueue.main.async { wifiInfo = r }
        }
    }

    /// 用户同意后的恢复流程: 远程重启 → 等待 → 重扫
    func userApprovedRecovery() {
        guard !recovering else { return }
        guard !deviceBusy else { message = "⏳ 设备占用中"; return }
        deviceBusy = true
        recovering = true
        message = "正在远程重启打印机… (约3分钟)"
        let target = ip
        let opts = options
        DispatchQueue.global().async { [self] in
            guard PrinterControl.reboot(ip: target),
                  PrinterControl.waitReboot(ip: target) else {
                DispatchQueue.main.async {
                    recovering = false
                    deviceBusy = false
                    message = "❌ 恢复失败, 请手动重启打印机"
                }
                return
            }
            DispatchQueue.main.async { message = "打印机已恢复, 重新扫描…" }
            do {
                let r = try ScanSession(ip: target).scan(opts) { [self] n, chunk in
                    DispatchQueue.main.async { progressBytes = n }
                    liveRender(raw: chunk)
                }
                let processed = ScanDecoder.postProcess(r.pixels, width: r.width, mode: opts.mode, density: opts.density)
                if let cg = ScanDecoder.makeImage(processed, width: r.width) {
                    let ns = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
                    DispatchQueue.main.async {
                        resultImage = ns
                        recovering = false
                        deviceBusy = false
                        message = "✅ 已恢复并扫描完成"
                        if let saved = ScanArchive.save(ns) { historyVersion += 1; _ = saved }
                    }
                } else {
                    // makeImage失败(恢复后0像素): 必须解锁, 否则永久"设备占用中"
                    DispatchQueue.main.async {
                        recovering = false
                        deviceBusy = false
                        message = "❌ 恢复后仍无图像, 请手动重启打印机"
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    recovering = false
                    deviceBusy = false
                    message = "❌ 恢复后扫描仍失败: \(error.localizedDescription)"
                }
            }
        }
    }

    func refreshStatus() {
        let target = ip
        DispatchQueue.global().async { [self] in
            let st = PrinterStatus.fetch(ip: target)
            DispatchQueue.main.async { status = st }
        }
    }

    func runScan(copies: Int = 0, completion: ((Bool) -> Void)? = nil) {
        guard !scanning else { return }
        guard !deviceBusy else {
            message = "⏳ 打印机正忙 (打印/复印/扫描进行中), 请等当前任务完成"
            return
        }
        deviceBusy = true
        defer { DispatchQueue.main.async { self.deviceBusy = false } }  // 兜底: 任何路径必解锁
        scanning = true
        progressBytes = 0
        message = nil
        livePreview = nil
        lastLiveRows = 0
        persist()
        let target = ip
        let opts = options
        DispatchQueue.global().async { [self] in
            do {
                let session = ScanSession(ip: target)
                var raw: [UInt8] = []
                var width = 0
                do {
                    let r = try session.scan(opts) { [self] n, chunk in
                        DispatchQueue.main.async { progressBytes = n }
                        liveRender(raw: chunk)
                    }
                    raw = r.pixels; width = r.width
                } catch let e as ScanError where e.isDead {
                    // 死锁: 弹窗征求用户同意后再远程重启恢复 (不静默操作打印机)
                    DispatchQueue.main.async {
                        scanning = false
                        deviceBusy = false
                        message = "扫描服务休眠 (打印后固病)"
                        showRecovery = true
                    }
                    completion?(false)
                    return
                }
                var processed = ScanDecoder.postProcess(raw, width: width, mode: opts.mode, density: opts.density)
                var w = width
                if autoRotate && w == 2496 && processed.count / w < 2496 {
                    // 180°旋转(横放页面翻正)
                    processed = ScanDecoder.rotate180(processed, width: w)
                }
                _ = w
                guard let cg = ScanDecoder.makeImage(processed, width: width) else {
                    throw ScanError.timeout
                }
                let ns = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
                DispatchQueue.main.async {
                    resultImage = ns
                    scanning = false
                    deviceBusy = false
                    message = "扫描完成 (\(width)×\(processed.count / width))"
                    if let saved = ScanArchive.save(ns) {
                        message = "扫描完成, 已存 \(saved.deletingPathExtension().lastPathComponent)"
                        historyVersion += 1
                    }
                    if copies > 0 { printResult(copies: copies) }
                    completion?(true)
                }
            } catch {
                DispatchQueue.main.async {
                    scanning = false
                    deviceBusy = false
                    message = "❌ \(error.localizedDescription)"
                    completion?(false)
                }
            }
        }
    }

    func printResult(copies: Int) {
        guard let img = resultImage,
              let tiff = img.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return }
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("m7208w-copy-\(Int(Date().timeIntervalSince1970)).png")
        try? png.write(to: tmp)
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/lp")
        // 队列名: 10-18重建后固定为 M7208W(socket直连); 旧名兜底防万一
        p.arguments = ["-d", "M7208W", "-n", "\(copies)", tmp.path]
        try? p.run()
        message = "已发送打印 (\(copies) 份) — 打印完成前请勿扫描"
        lockDeviceForPrint(seconds: 90)
    }

    /// 提交打印后锁定设备一段时间(略长于物理打印), 避开"打印后忙窗口"固病
    private func lockDeviceForPrint(seconds: Double) {
        deviceBusy = true
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { [self] in
            deviceBusy = false
        }
    }

    func saveResult() {
        guard let img = resultImage,
              let tiff = img.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return }
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.png]
        panel.nameFieldStringValue = "扫描_\(Date().formatted(.dateTime.month().day().hour().minute())).png"
        if panel.runModal() == .OK, let url = panel.url {
            try? png.write(to: url)
            message = "已保存 \(url.lastPathComponent)"
        }
    }
}

// MARK: - 主界面

struct ContentView: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        VStack(spacing: 0) {
            HeaderBar()
            Divider()
            TabView {
                ScanPane().tabItem { Label("扫描", systemImage: "doc.text.viewfinder") }
                CopyPane().tabItem { Label("复印", systemImage: "copier") }
                HistoryPane().tabItem { Label("历史", systemImage: "clock.arrow.circlepath") }
                StatusPane().tabItem { Label("状态", systemImage: "info.circle") }
            }
            .padding()
        }
        .alert("写入WiFi配置", isPresented: $m.showWifiConfirm) {
            Button("确认写入", role: .destructive) { m.writeWiFi() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("写入后打印机将连接新网络并重启, IP可能变化。如果写错, 需要USB线重新配置。确认继续?")
        }
        .onAppear {
            m.refreshStatus()
            // 首次使用: 没存过IP就自动发现
            if UserDefaults.standard.string(forKey: "printerIP") == nil {
                m.discoverPrinter(auto: true)
            }
            m.esclAlive = LanScanner.daemonAlive()
        }
    }
}

struct HeaderBar: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "printer.fill").foregroundStyle(m.status.online ? .green : .red)
            Text(m.status.online ? "在线" : "离线").font(.callout)
            if m.deviceBusy {
                Label("设备占用中", systemImage: "lock.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(.orange.opacity(0.15), in: Capsule())
            }
            Text("·").foregroundStyle(.tertiary)
            Text("墨粉 \(m.status.tonerPercent > 0 ? "\(m.status.tonerPercent)%" : "未知")").font(.callout)
            Spacer()
            Text("IP").foregroundStyle(.secondary)
            TextField("", text: $m.ip)
                .textFieldStyle(.roundedBorder)
                .frame(width: 120)
                .onSubmit { m.refreshStatus() }
            Button("刷新") { m.refreshStatus() }
            Button {
                m.discoverPrinter()
            } label: {
                if m.discovering { ProgressView().controlSize(.small) }
                else { Label("找打印机", systemImage: "magnifyingglass") }
            }
            .help("扫描局域网自动找到打印机IP(约3秒)")
            Button {
                m.installSystemScanner()
            } label: {
                Label("系统扫描仪", systemImage: m.esclAlive ? "scanner.fill" : "scanner")
            }
            .help("启用macOS系统级扫描仪(图像捕捉/预览直接可见, 开机自启, 无需密码)。重复点击=重新安装")
            Button {
                m.repairPrintQueue()
            } label: {
                Label("修复打印", systemImage: "wrench.and.screwdriver")
            }
            .help("部署arm64打印驱动+把队列指向当前IP(弹密码框)。换IP/驱动丢失/队列异常时点一下即可")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.bar)
    }
}

// MARK: 扫描页

struct ScanPane: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        HSplitView {
            controlPanel.frame(width: 290).padding(.trailing, 8)
            previewPane.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var controlPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker("纸张", selection: $m.options.paper) {
                ForEach(PaperSize.allCases) { Text($0.rawValue).tag($0) }
            }
            Picker("分辨率", selection: $m.options.dpi) {
                ForEach([75, 100, 150, 200, 300, 600], id: \.self) { d in
                    Text("\(d) DPI").tag(d)
                }
            }
            Picker("模式", selection: $m.options.mode) {
                ForEach(ScanMode.allCases) { Text($0.rawValue).tag($0) }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("浓度 \(m.options.density)")
                Slider(value: densityBinding, in: -127...127)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("亮度 \(m.options.brightness)")
                Slider(value: Binding(get: { Double(m.options.brightness) },
                                      set: { m.options.brightness = Int($0) }), in: -127...127)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("对比度 \(m.options.contrast)")
                Slider(value: Binding(get: { Double(m.options.contrast) },
                                      set: { m.options.contrast = Int($0) }), in: -127...127)
            }
            Toggle("自动旋正 (180°)", isOn: $m.autoRotate)
            if m.scanning {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("扫描中 \(m.progressBytes / 1024) KB").foregroundStyle(.secondary)
                }
                .padding(.top, 8)
            } else {
                Button {
                    m.runScan()
                } label: {
                    Label(m.deviceBusy ? "设备占用中…" : "开始扫描", systemImage: "doc.text.viewfinder")
                        .frame(maxWidth: .infinity)
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                .disabled(m.deviceBusy)
                .padding(.top, 8)
            }
            if let msg = m.message {
                Text(msg).font(.callout).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding()
        .background(Color(nsColor: .controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var densityBinding: Binding<Double> {
        Binding(
            get: { Double(m.options.density) },
            set: { m.options.density = Int($0) }
        )
    }

    private var previewPane: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            if m.scanning, let live = m.livePreview {
                GeometryReader { geo in
                    Image(nsImage: live)
                        .interpolation(.none)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                }
                .overlay(alignment: .bottom) {
                    Text("实时预览 \(m.progressBytes / 1024) KB · \(m.progressBytes / 2496) 行")
                        .font(.caption.monospaced())
                        .padding(6)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .padding(.bottom, 8)
                }
            } else if let img = m.resultImage {
                GeometryReader { geo in
                    Image(nsImage: img)
                        .interpolation(.medium)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                }
            } else {
                emptyPreview
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if m.resultImage != nil && !m.scanning {
                HStack {
                    Button { m.saveResult() } label: {
                        Label("存 PNG", systemImage: "square.and.arrow.down")
                    }
                    Button { m.addToPdfQueue() } label: {
                        Label("加入PDF(\(m.pdfQueue.count))", systemImage: plusIconName)
                    }
                    if !m.pdfQueue.isEmpty {
                        Button { m.exportPdf() } label: {
                            Label("导出PDF", systemImage: "doc.richtext")
                        }
                    }
                }
                .padding(12)
            }
        }
        .alert("扫描服务休眠", isPresented: $m.showRecovery) {
            Button("远程重启恢复 (约3分钟)") { m.userApprovedRecovery() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("打印机刚打印完会出现此固病。远程重启打印机约3分钟即可恢复, 是否继续?")
        }
    }

    private var emptyPreview: some View {
        VStack(spacing: 8) {
            Image(systemName: "doc.text.viewfinder")
                .font(.system(size: 42))
                .foregroundStyle(.tertiary)
            Text("扫描结果将显示在这里").foregroundStyle(.tertiary)
        }
    }
}

// MARK: 复印页

struct CopyPane: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                HStack(spacing: 16) {
                    Picker("份数", selection: $m.copyOptions.copies) {
                        ForEach(1...20, id: \.self) { Text("\($0)").tag($0) }
                    }.frame(width: 90)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("缩放 \(m.copyOptions.scale)%")
                        Slider(value: Binding(
                            get: { Double(m.copyOptions.scale) },
                            set: { m.copyOptions.scale = Int($0) }), in: 25...400, step: 5)
                    }
                }
                HStack(spacing: 16) {
                    Picker("原稿", selection: $m.copyOptions.src) {
                        ForEach(CopyPaper.allCases) { Text($0.label).tag($0) }
                    }
                    Picker("输出纸", selection: $m.copyOptions.dst) {
                        ForEach(CopyPaper.allCases) { Text($0.label).tag($0) }
                    }
                }
                HStack(spacing: 16) {
                    Picker("分辨率", selection: $m.copyOptions.dpi) {
                        ForEach(CopyDpi.allCases) { Text($0.label).tag($0) }
                    }.pickerStyle(.segmented)
                    Picker("介质", selection: $m.copyOptions.media) {
                        ForEach(CopyMedia.allCases) { Text($0.label).tag($0) }
                    }
                }
                HStack(spacing: 16) {
                    Picker("N合1", selection: $m.copyOptions.nup) {
                        ForEach(NupMode.allCases) { Text($0.label).tag($0) }
                    }
                    Picker("模式", selection: $m.copyOptions.scanModeText) {
                        Text("文本").tag(true); Text("照片").tag(false)
                    }.pickerStyle(.segmented)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("浓度 \(m.copyOptions.density)")
                    Slider(value: Binding(
                        get: { Double(m.copyOptions.density) },
                        set: { m.copyOptions.density = Int($0) }), in: 0...6, step: 1)
                }
                Button {
                    m.startCopy()
                } label: {
                    Label(m.deviceBusy ? "设备占用中…" : "开始复印 (机器自动扫+印)", systemImage: "arrow.triangle.2.circlepath")
                        .frame(minWidth: 240)
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                .disabled(m.deviceBusy)
                if let msg = m.message {
                    Text(msg).font(.callout).foregroundStyle(.secondary)
                }
            }
            .padding()
        }
    }
}

// MARK: 历史页

struct HistoryPane: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 14)], spacing: 14) {
                ForEach(ScanArchive.list(), id: \.url) { item in
                    HistoryCard(url: item.url, date: item.date)
                }
            }
            .padding()
        }
        .id(m.historyVersion)
        .overlay {
            if ScanArchive.list().isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "clock.arrow.circlepath").font(.system(size: 38)).foregroundStyle(.tertiary)
                    Text("暂无扫描历史 (存于 图片/M7208W 扫描)").foregroundStyle(.tertiary)
                }
            }
        }
    }
}

final class ThumbBox: ObservableObject {
    @Published var image: NSImage?
}

struct HistoryCard: View {
    let url: URL
    let date: Date
    @ObservedObject var box = ThumbBox()

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(Color(nsColor: .controlBackgroundColor))
                if let t = box.image {
                    Image(nsImage: t).resizable().aspectRatio(contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    ProgressView().controlSize(.small)
                }
            }
            .frame(height: 170)
            Text(url.deletingPathExtension().lastPathComponent)
                .font(.caption).lineLimit(1).foregroundStyle(.secondary)
            Text(date.formatted(.dateTime.month().day().hour().minute().second()))
                .font(.caption2).foregroundStyle(.tertiary)
        }
        .onAppear {
            DispatchQueue.global().async {
                let t = ScanArchive.loadThumbnail(url)
                DispatchQueue.main.async { box.image = t }
            }
        }
        .onTapGesture { NSWorkspace.shared.activateFileViewerSelecting([url]) }
    }
}


// MARK: 状态页

struct StatusPane: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        Form {
            LabeledContent("打印机") {
                Text(m.status.online ? "在线" : "离线")
                    .foregroundStyle(m.status.online ? .green : .red)
            }
            LabeledContent("扫描单元") {
                Text(m.status.isScannerRunning ? "正常" : "未知")
            }
            LabeledContent("墨粉") {
                Text(m.status.tonerPercent > 0 ? "\(m.status.tonerPercent)%" : "…")
            }
            if m.status.standardPages > 0 {
                LabeledContent("墨粉页数") {
                    Text("\(m.status.printedPages) / \(m.status.standardPages) 页")
                }
            }
            LabeledContent("打印驱动") { Text("原生 arm64 ✅") }
            LabeledContent("Web 管理") {
                if let u = URL(string: "https://\(m.ip)/status.asp") {
                    Link("打开状态页", destination: u)
                }
            }
            Section("系统级扫描 (eSCL)") {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 8, height: 8)
                        Text("后台守护进程运行中 (开机自启)")
                        Spacer()
                        Button("重启服务") {
                            let p = Process()
                            p.executableURL = URL(fileURLWithPath: "/bin/launchctl")
                            p.arguments = ["kickstart", "-k", "gui/\(getuid())/com.zml.m7208w.escl"]
                            try? p.run()
                        }
                    }
                    Text("图像捕捉/预览/任意App可直接使用 \"Lenovo M7208W (Studio)\"")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("墨粉维护") {
                HStack {
                    Button("读状态") { m.tonerGet() }
                    Button("开启·尽粉继续印") { m.tonerSetContinue() }
                    Button("重置计数") { m.tonerReset() }
                }
                Text("墨粉耗尽打印机罢工时: 点「开启·尽粉继续印」解锁继续用")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("WiFi 配置") {
                HStack {
                    Button("读取") { m.readWiFi() }
                    Button("扫描周围网络") { m.scanNetworks() }
                    if let w = m.wifiInfo {
                        VStack(alignment: .leading) {
                            Text("SSID: \(w.0)").font(.callout.monospaced())
                            Text("密码: \(w.1.isEmpty ? "(固件已隐藏)" : w.1)").font(.callout.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                }
                HStack {
                    TextField("新SSID", text: $m.newSSID).textFieldStyle(.roundedBorder)
                    SecureField("新密码", text: $m.newPass).textFieldStyle(.roundedBorder)
                    Picker("", selection: $m.wifiEnc) {
                        Text("WPA2").tag(3); Text("WEP64").tag(1); Text("WEP128").tag(2); Text("开放").tag(0)
                    }.labelsHidden().frame(width: 90)
                }
                if !m.foundNetworks.isEmpty {
                    ScrollView(.horizontal) {
                        HStack {
                            ForEach(m.foundNetworks, id: \.self) { n in
                                Button(n) { m.newSSID = n }
                                    .buttonStyle(.bordered).controlSize(.small)
                            }
                        }
                    }
                }
                Button(role: .destructive) { m.showWifiConfirm = true } label: {
                    Text("写入WiFi配置 ⚠️")
                }
                .disabled(m.newSSID.isEmpty || m.newPass.isEmpty)
            }
        }
        .formStyle(.grouped)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
