import Foundation
import AppKit
import CoreGraphics

// MARK: - 扫描历史 (自动存档)

enum ScanArchive {
    static var folder: URL {
        let dir = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("M7208W 扫描")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func save(_ image: NSImage) -> URL? {
        guard let tiff = image.tiffRepresentation,
              let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return nil }
        let name = "扫描_\(stamp()).png"
        let url = folder.appendingPathComponent(name)
        do { try png.write(to: url); return url }
        catch { return nil }
    }

    static func list() -> [(url: URL, date: Date)] {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.contentModificationDateKey])
        else { return [] }
        var out: [(URL, Date)] = []
        for f in files where f.pathExtension.lowercased() == "png" {
            let d = (try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            out.append((f, d))
        }
        return out.sorted { $0.1 > $1.1 }
    }

    static func loadThumbnail(_ url: URL, maxPx: CGFloat = 160) -> NSImage? {
        guard let img = NSImage(contentsOf: url) else { return nil }
        let ratio = min(maxPx / img.size.width, maxPx / img.size.height, 1)
        let newSize = NSSize(width: img.size.width * ratio, height: img.size.height * ratio)
        let thumb = NSImage(size: newSize)
        thumb.lockFocus()
        img.draw(in: NSRect(origin: .zero, size: newSize))
        thumb.unlockFocus()
        return thumb
    }

    private static func stamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f.string(from: Date())
    }
}

// MARK: - 多页 PDF 导出

enum PDFExporter {
    static func export(pages: [NSImage], to url: URL) -> Bool {
        guard let ctx = CGContext(url as CFURL, mediaBox: nil, nil) else { return false }
        for img in pages {
            guard let tiff = img.tiffRepresentation,
                  let rep = NSBitmapImageRep(data: tiff),
                  let cg = rep.cgImage else { continue }
            var box = CGRect(x: 0, y: 0, width: 595, height: 842)  // A4 pt
            // 按图比例适配页面
            let ratio = min(595.0 / CGFloat(cg.width), 842.0 / CGFloat(cg.height))
            let w = CGFloat(cg.width) * ratio, h = CGFloat(cg.height) * ratio
            box = CGRect(x: (595 - w) / 2, y: (842 - h) / 2, width: w, height: h)
            ctx.beginPDFPage(nil)
            ctx.draw(cg, in: box)
            ctx.endPDFPage()
        }
        ctx.closePDF()
        return true
    }
}
