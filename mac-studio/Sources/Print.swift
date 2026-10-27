import SwiftUI
import AppKit
import PDFKit

// MARK: - 打印参数 (方案A: 参数经 lp -o 直传CUPS过滤器 rastertolhpl)

enum PrintPaper: String, CaseIterable, Identifiable {
    case A4, letter
    var id: String { rawValue }
    var label: String { self == .A4 ? "A4" : "Letter" }
    /// 预览基准像素(150dpi, 与安卓 composeNup 合成底同规格)
    var previewW: Int { self == .A4 ? 1240 : 1275 }
    var previewH: Int { self == .A4 ? 1754 : 1650 }
}

struct PrintOptions: Equatable {
    var paper: PrintPaper = .A4
    var nup = 1                 // 1/2/4/9/16
    var scalePct = 100          // 25...400
    var density = 0             // -3...3
    var tonerSave = false
    var copies = 1

    /// lp -o 参数: 键名与 rastertolhpl 的 parse_job_options 一一对应
    var lpOptions: [String] {
        ["PageSize=\(paper.rawValue)", "Density=\(density)",
         "TonerSave=\(tonerSave ? "True" : "False")", "Nup=\(nup)"]
            .flatMap { ["-o", $0] }
    }
}

// MARK: - 打印预览引擎 (翻译自安卓 PrintEngine: renderPreview + bitmapToCanvas 同公式)
// 管线: 灰度(30/59/11) → N合1拼版(0.92留边) → 浓度曲线(f=1+0.12d) → FS抖动 → 阈值(省墨98/标准128)

struct GrayPage {
    let gray: [UInt8]
    let w: Int
    let h: Int
}

enum PrintPreviewEngine {
    private static let nupCellMargin = 0.92
    private static let tonerSaveThreshold = 98
    private static let standardThreshold = 128
    /// N合1网格: 2=上下两格, 4/9/16=n×n (安卓 nupGrid 同规则)
    private static let nupGrids: [Int: (cols: Int, rows: Int)] =
        [2: (1, 2), 4: (2, 2), 9: (3, 3), 16: (4, 4)]

    static func nupGrid(_ n: Int) -> (cols: Int, rows: Int) { nupGrids[n] ?? (1, 1) }

    /// 整链路渲染第一张纸的网点预览 (灰度页集合 → CGImage)
    static func render(pages: [GrayPage], opts: PrintOptions) -> CGImage? {
        let pw = opts.paper.previewW, ph = opts.paper.previewH
        guard !pages.isEmpty, pw > 0, ph > 0 else { return nil }
        var sheet = [UInt8](repeating: 255, count: pw * ph)
        let grid = nupGrid(opts.nup)
        let cw = max(1, pw / grid.cols), ch = max(1, ph / grid.rows)
        for (i, page) in pages.prefix(opts.nup).enumerated() {
            composeCell(page, into: &sheet, sheetW: pw,
                        cellX: (i % grid.cols) * cw, cellY: (i / grid.cols) * ch,
                        cellW: cw, cellH: ch, scalePct: opts.scalePct)
        }
        return ScanDecoder.makeImage(dither(sheet, width: pw,
                                            density: opts.density,
                                            tonerSave: opts.tonerSave), width: pw)
    }

    /// 子页按面积平均缩放进格子(居中, 0.92留边; 放大退化为最近邻) — 安卓 renderPreview 同布局
    private static func composeCell(_ page: GrayPage, into sheet: inout [UInt8], sheetW: Int,
                                    cellX: Int, cellY: Int, cellW: Int, cellH: Int,
                                    scalePct: Int) {
        let fit = min(Double(cellW) * nupCellMargin / Double(page.w),
                      Double(cellH) * nupCellMargin / Double(page.h))
        let scale = fit * Double(min(max(scalePct, 25), 400)) / 100
        let tw = max(1, min(cellW, Int(Double(page.w) * scale)))
        let th = max(1, min(cellH, Int(Double(page.h) * scale)))
        let offX = cellX + (cellW - tw) / 2
        let offY = cellY + (cellH - th) / 2
        for dy in 0..<th {
            let y0 = dy * page.h / th
            let y1 = max(y0 + 1, (dy + 1) * page.h / th)
            for dx in 0..<tw {
                let x0 = dx * page.w / tw
                let x1 = max(x0 + 1, (dx + 1) * page.w / tw)
                var sum = 0, n = 0
                for y in y0..<y1 {
                    for x in x0..<x1 { sum += Int(page.gray[y * page.w + x]); n += 1 }
                }
                sheet[(offY + dy) * sheetW + offX + dx] = UInt8((sum + n / 2) / n)
            }
        }
    }

    /// 浓度曲线 + Floyd–Steinberg 误差扩散 + 阈值 → 0/255 网点图 (安卓 bitmapToCanvas 同公式)
    private static func dither(_ gray: [UInt8], width: Int,
                               density: Int, tonerSave: Bool) -> [UInt8] {
        let h = gray.count / width
        guard h > 0 else { return gray }
        var out = [UInt8](repeating: 255, count: gray.count)
        var cur = [Int](repeating: 0, count: width)
        var next = [Int](repeating: 0, count: width)
        let f = 1.0 + 0.12 * Double(density)
        let bias = (255.0 - 255.0 * f) / 2
        let thr = tonerSave ? tonerSaveThreshold : standardThreshold
        for y in 0..<h {
            for x in 0..<width {
                var g = Int(gray[y * width + x])
                if density != 0 { g = min(255, max(0, Int(Double(g) * f + bias))) }
                let v = g + cur[x]
                let isBlack = v < thr
                let err = isBlack ? v : v - 255
                if x + 1 < width { cur[x + 1] += err * 7 / 16 }
                if x > 0 { next[x - 1] += err * 3 / 16 }
                next[x] += err * 5 / 16
                if x + 1 < width { next[x + 1] += err / 16 }
                if isBlack { out[y * width + x] = 0 }
            }
            swap(&cur, &next)
            for i in 0..<width { next[i] = 0 }
        }
        return out
    }
}

// MARK: - 待打印文档 (图片/PDF → 统一灰度页集合)

enum PrintDocLoader {
    /// 输入源降采样上限(手机千万像素照片/PDF大页直接进预览会拖慢重渲)
    private static let prepareMaxW = 1600
    /// PDF页渲染基准宽(点阵足够预览文字网点)
    private static let pdfRenderW = 1240.0

    static func load(_ url: URL) -> [GrayPage] {
        url.pathExtension.lowercased() == "pdf" ? loadPdf(url) : loadImage(url)
    }

    private static func loadImage(_ url: URL) -> [GrayPage] {
        guard let img = NSImage(contentsOf: url),
              let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return [] }
        return [grayPage(from: cg)]
    }

    private static func loadPdf(_ url: URL) -> [GrayPage] {
        guard let doc = PDFDocument(url: url) else { return [] }
        var out: [GrayPage] = []
        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            let box = page.bounds(for: .mediaBox)
            let scale = pdfRenderW / max(box.width, 1)
            let thumb = page.thumbnail(of: CGSize(width: box.width * scale,
                                                  height: box.height * scale), for: .mediaBox)
            guard let cg = thumb.cgImage(forProposedRect: nil, context: nil, hints: nil) else { continue }
            out.append(grayPage(from: cg))
        }
        return out
    }

    /// CGImage → 灰度(30/59/11由图形栈完成) → 宽度上限降采样
    private static func grayPage(from cg: CGImage) -> GrayPage {
        let scale = min(1, Double(prepareMaxW) / Double(cg.width))
        let w = max(1, Int(Double(cg.width) * scale))
        let h = max(1, Int(Double(cg.height) * scale))
        var buf = [UInt8](repeating: 255, count: w * h)
        buf.withUnsafeMutableBytes { raw in
            guard let ctx = CGContext(data: raw.baseAddress, width: w, height: h,
                                      bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(),
                                      bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return }
            ctx.interpolationQuality = .high
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return GrayPage(gray: buf, w: w, h: h)
    }
}

// MARK: - 打印面板状态 (参数变化 → 250ms防抖整链路重渲)

final class PrintPanelModel: ObservableObject {
    @Published var opts = PrintOptions()
    @Published var docURL: URL?
    @Published var pageCount = 0
    @Published var preview: NSImage?
    @Published var rendering = false
    private var pages: [GrayPage] = []
    private var renderWork: DispatchWorkItem?

    var sheetCount: Int { pageCount == 0 ? 0 : (pageCount + opts.nup - 1) / opts.nup }

    func load(url: URL) {
        let loaded = PrintDocLoader.load(url)
        guard !loaded.isEmpty else { return }
        pages = loaded
        docURL = url
        pageCount = loaded.count
        renderNow()
    }

    /// 绑定控件变化入口: 停掉未触发的重渲, 250ms 后再渲
    func paramsChanged() {
        guard pageCount > 0 else { return }
        renderWork?.cancel()
        let work = DispatchWorkItem { [self] in renderNow() }
        renderWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: work)
    }

    private func renderNow() {
        let pages = self.pages
        let opts = self.opts
        rendering = true
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let cg = PrintPreviewEngine.render(pages: pages, opts: opts)
            DispatchQueue.main.async { [self] in
                rendering = false
                if let cg = cg {
                    preview = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
                }
            }
        }
    }
}

// MARK: - 打印面板

struct PrintPane: View {
    @EnvironmentObject var m: AppModel
    @StateObject private var pm = PrintPanelModel()

    var body: some View {
        HSplitView {
            controlPanel.frame(width: 290).padding(.trailing, 8)
            previewPane.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var controlPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Button { pickDocument() } label: {
                    Label(pm.docURL == nil ? "选文件 (图片/PDF)" : "换文件", systemImage: "folder")
                }
                if let url = pm.docURL {
                    Text(url.lastPathComponent).lineLimit(1).truncationMode(.middle)
                        .foregroundStyle(.secondary)
                }
            }
            if pm.pageCount > 0 {
                Text("\(pm.pageCount) 页 → \(pm.sheetCount) 张纸").font(.callout).foregroundStyle(.secondary)
            }
            Picker("纸张", selection: $pm.opts.paper) {
                ForEach(PrintPaper.allCases) { Text($0.label).tag($0) }
            }
            Picker("N合1", selection: $pm.opts.nup) {
                ForEach([1, 2, 4, 9, 16], id: \.self) { n in
                    Text(n == 1 ? "关" : "\(n)合1").tag(n)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("缩放 \(pm.opts.scalePct)%")
                Slider(value: Binding(get: { Double(pm.opts.scalePct) },
                                      set: { pm.opts.scalePct = Int($0) }), in: 25...400, step: 5)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("浓度 \(pm.opts.density)")
                Slider(value: Binding(get: { Double(pm.opts.density) },
                                      set: { pm.opts.density = Int($0) }), in: -3...3, step: 1)
            }
            Toggle("省墨模式", isOn: $pm.opts.tonerSave)
            Picker("份数", selection: $pm.opts.copies) {
                ForEach(1...20, id: \.self) { Text("\($0)").tag($0) }
            }.frame(width: 90)
            Button {
                sendPrint()
            } label: {
                Label(m.deviceBusy ? "设备占用中…" : "发送打印",
                      systemImage: "printer")
                    .frame(maxWidth: .infinity)
            }
            .controlSize(.large)
            .buttonStyle(.borderedProminent)
            .disabled(m.deviceBusy || pm.docURL == nil)
            if let msg = m.message {
                Text(msg).font(.callout).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding()
        .background(Color(nsColor: .controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .onChange(of: pm.opts) { _ in pm.paramsChanged() }
    }

    private var previewPane: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            if let img = pm.preview {
                GeometryReader { geo in
                    Image(nsImage: img)
                        .interpolation(.none)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                }
                .overlay(alignment: .bottom) {
                    Text(pm.rendering ? "重渲中…" : "网点预览 (浓度/省墨/N合1实时模拟)")
                        .font(.caption.monospaced())
                        .padding(6)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .padding(.bottom, 8)
                }
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "printer")
                        .font(.system(size: 42))
                        .foregroundStyle(.tertiary)
                    Text("选择图片或 PDF 后显示打印预览").foregroundStyle(.tertiary)
                }
            }
        }
    }

    private func pickDocument() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image, .pdf]
        panel.canChooseDirectories = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        pm.load(url: url)
    }

    private func sendPrint() {
        guard let url = pm.docURL else { return }
        guard !m.deviceBusy else { m.message = "⏳ 打印机正忙, 请等当前任务完成"; return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/lp")
        // 队列名与 printResult 一致; 参数经 -o 直传 rastertolhpl (Density/TonerSave/Nup)
        p.arguments = ["-d", "M7208W"] + pm.opts.lpOptions
                    + ["-n", "\(pm.opts.copies)", url.path]
        do {
            try p.run()
            m.message = "已发送打印 (\(pm.opts.copies) 份 · \(pm.sheetCount) 张/份) — 打印完成前请勿扫描"
            m.lockDeviceForPrint(seconds: 90)
        } catch {
            m.message = "❌ 发送失败: \(error.localizedDescription)"
        }
    }
}
