package com.zml.m7208w

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Date

// MARK: - LHPL打印引擎 (完整复刻Mac rastertolhpl: PJL+LHPL块+JBIG)

object PrintEngine {
    private const val CANVAS_W = 5120
    private const val CANVAS_BPL = CANVAS_W / 8

    init { System.loadLibrary("jbigjni") }
    external fun jbigEncode(bitmap: ByteArray, h: Int): ByteArray

    /** 位图Bitmap → 1bpp 5120宽画布(Floyd-Steinberg抖动)
     *  scalePct: 目标占满纸比例(25..400, 100=适应纸面); density: -3..3 浓度; tonerSave: 省墨 */
    private fun bitmapToCanvas(bmp: Bitmap, pageHeight: Int, scalePct: Int = 100,
                               density: Int = 0, tonerSave: Boolean = false): ByteArray {
        val canvas = ByteArray(pageHeight * CANVAS_BPL)
        // 先把bitmap按目标尺寸缩放到画布中央(600dpi等效)
        val scale = minOf(CANVAS_W.toFloat() / bmp.width, pageHeight.toFloat() / bmp.height, 8f) * (scalePct.coerceIn(25, 400) / 100f)
        val tw = (bmp.width * scale).toInt().coerceAtMost(CANVAS_W)
        val th = (bmp.height * (tw.toFloat() / bmp.width)).toInt().coerceAtMost(pageHeight)
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val px = IntArray(tw * th)
        scaled.getPixels(px, 0, tw, 0, 0, tw, th)
        val offX = (CANVAS_W - tw) / 2
        val offY = (pageHeight - th) / 2
        var cur = IntArray(tw)
        var next = IntArray(tw)
        for (y in 0 until th) {
            val rowY = offY + y
            if (rowY < 0 || rowY >= pageHeight) continue
            java.util.Arrays.fill(next, 0)
            for (x in 0 until tw) {
                val p = px[y * tw + x]
                    var g = ((p shr 16 and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                if (density != 0) {  // 浓度±3: gamma式调节
                    val f = 1.0 + 0.12 * density
                    val d = g * f + (255 - 255 * f) / 2
                    g = d.toInt().coerceIn(0, 255)
                }
                val v = g + cur[x]
                val thr = if (tonerSave) 98 else 128   // 省墨=阈值下移→黑点更少
                val bit = v < thr   // 暗→黑
                if (x + 1 < tw) cur[x + 1] += (if (bit) v else v - 255) * 7 / 16
                if (x > 0) next[x - 1] += (if (bit) v else v - 255) * 3 / 16
                next[x] += (if (bit) v else v - 255) * 5 / 16
                if (x + 1 < tw) next[x + 1] += (if (bit) v else v - 255) / 16
                if (bit) {
                    val ax = offX + x
                    canvas[rowY * CANVAS_BPL + ax / 8] = (canvas[rowY * CANVAS_BPL + ax / 8].toInt() or (0x80 shr (ax % 8))).toByte()
                }
            }
            val t = cur; cur = next; next = t
            java.util.Arrays.fill(next, 0)
        }
        return canvas
    }

    /** LHPL 64B块 */
    private fun chunk(cmd: String, payload: ByteArray?): ByteArray {
        val blk = ByteArray(64)
        blk[0] = 0x1b; blk[1] = 'L'.code.toByte(); blk[2] = 'H'.code.toByte(); blk[3] = '@'.code.toByte()
        blk[4] = cmd[0].code.toByte(); blk[5] = cmd[1].code.toByte()
        payload?.copyInto(blk, 6, 0, minOf(payload.size, 58))
        var x = 0
        for (i in 0 until 63) x = x xor (blk[i].toInt() and 0xFF)
        blk[63] = x.toByte()
        return blk
    }

    // MARK: 打印纸张(枚举对齐原版8种; sp纸码实证A4=2/Letter=1, 其余按几何走A4码)
    enum class PrintPaper(val label: String, val h600: Int, val wmm10: Int, val hmm10: Int, val code: Int) {
        A4("A4", 7016, 2100, 2970, 2),
        LETTER("Letter", 6600, 2159, 2794, 1),
        B5("B5", 8300, 1760, 2500, 2),
        A5("A5", 4950, 1480, 2100, 2),
        B6("B6", 4950, 1250, 1760, 2),
        A6("A6", 3490, 1050, 1480, 2),
        K16("16K", 6900, 1930, 2700, 2),
        EXEC("Executive", 6450, 1841, 2667, 2);
    }
    enum class PrintMedia(val label: String, val code: Int) {
        PLAIN("普通纸", 1), RECYCLED("再生纸", 2), THICK("厚纸", 3), THIN("薄纸", 4), LABEL("标签", 5)
    }

    /** N合1合成: pages按 n×n 网格拼到一张白底A4(每格等比缩放) */
    private fun composeNup(pages: List<Bitmap>, n: Int): List<Bitmap> {
        if (n <= 1) return pages
        val grid = when (n) { 2 -> 1; 4 -> 2; 9 -> 3; 16 -> 4; else -> 1 }  // 2合1=上下两格
        val cols = if (n == 2) 1 else grid
        val rows = if (n == 2) 2 else grid
        val out = mutableListOf<Bitmap>()
        for (start in pages.indices step n) {
            val cell = pages.subList(start, minOf(start + n, pages.size))
            val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)  // A4@150dpi合成底
            val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
            val cw = 1240f / cols; val ch = 1754f / rows
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            cell.forEachIndexed { i, b ->
                val s = minOf(cw * 0.92f / b.width, ch * 0.92f / b.height)
                val w = b.width * s; val h = b.height * s
                val x = (i % cols) * cw + (cw - w) / 2
                val y = (i / cols) * ch + (ch - h) / 2
                cv.drawBitmap(b, null, android.graphics.RectF(x, y, x + w, y + h), p)
            }
            out.add(bmp)
        }
        return out
    }

    /** 证件打印: 正/反面两图合成一页A4(身份证比例85.6×54mm居中上下排布) */
    fun composeCertificate(front: Bitmap, back: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        // A4@150dpi: 1240×1754; 身份证 85.6×54mm → 506×319px
        val cw = 506 * 2.2f; val chh = 319 * 2.2f
        for ((idx, b) in listOf(front, back).withIndex()) {
            val s = minOf(cw / b.width, chh / b.height)
            val w = b.width * s; val h = b.height * s
            val x = (1240 - w) / 2
            val y = 1754 * 0.22f + idx * (chh + 80)
            cv.drawBitmap(b, null, android.graphics.RectF(x, y, x + w, y + h), p)
        }
        return bmp
    }

    /** 完整打印作业: 纸张/介质/N合1/缩放/浓度/省墨/份数(份数走协议COPIES原生支持) */
    fun printJob(ip: String, pages: List<Bitmap>, paper: PrintPaper = PrintPaper.A4,
                 media: PrintMedia = PrintMedia.PLAIN, nup: Int = 1, scalePct: Int = 100,
                 density: Int = 0, tonerSave: Boolean = false, copies: Int = 1): Boolean {
        val composed = composeNup(pages, nup)
        val out = ByteArrayOutputStream()
        val hdr = buildString {
            append("\u001b%-12345X@PJL JOB NAME=PRINTER\r\n")
            append("@PJL SET RESOLUTION=600\r\n")
            append("@PJL SET BITSPERPIXEL=1\r\n")
            append("@PJL SET RENDERMODE=GRAYSCALE\r\n")
            append("@PJL SET COPIES=${copies.coerceIn(1, 99)}\r\n")
            append("@PJL SET DUPLEX = OFF\r\n")
            append("@PJL ENTER LANGUAGE=LHPL\r\n")
        }
        out.write(hdr.toByteArray())
        out.write(chunk("sj", byteArrayOf(1, 0, 1, 0)))
        for (bmp in composed) {
            val pageH = paper.h600
            val canvas = bitmapToCanvas(bmp, pageH, scalePct, density, tonerSave)
            val bie = jbigEncode(canvas, pageH)
            val pay = ByteArray(58)
            fun le32(dst: ByteArray, off: Int, v: Int) {
                dst[off] = (v and 0xFF).toByte(); dst[off + 1] = ((v shr 8) and 0xFF).toByte()
                dst[off + 2] = ((v shr 16) and 0xFF).toByte(); dst[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            pay[0] = paper.code.toByte(); pay[1] = media.code.toByte(); pay[3] = 20
            le32(pay, 6, pageH)
            le32(pay, 10, 640 * pageH)
            le32(pay, 14, bie.size)
            le32(pay, 18, bie.size)
            pay[36] = (600 and 0xFF).toByte(); pay[37] = (600 shr 8).toByte()
            pay[38] = (paper.wmm10 and 0xFF).toByte(); pay[39] = (paper.wmm10 shr 8).toByte()
            pay[40] = (paper.hmm10 and 0xFF).toByte(); pay[41] = (paper.hmm10 shr 8).toByte()
            out.write(chunk("sp", pay))
            out.write(bie)
            out.write(chunk("ep", null))
        }
        out.write("\u001b%-12345X@PJL EOJ\r\n".toByteArray())
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(java.net.InetAddress.getByName(ip), 9100), 6000)
            s.getOutputStream().apply { write(out.toByteArray()); flush() }
            s.close()
            true
        } catch (e: Exception) { false }
    }

    /** 打印一张Bitmap(600dpi画布). 返回成功与否 */
    fun print(ip: String, bmp: Bitmap, copies: Int = 1): Boolean {
        return try {
            // A4 @600dpi = 4960x7016, 画布高取页高
            val pageH = 7016
            val canvas = bitmapToCanvas(bmp, pageH)
            val bie = jbigEncode(canvas, pageH)
            val out = ByteArrayOutputStream()
            // PJL头: 固件1.3.00实证配方(t5)=手机模板D极简5行, 多余SET行会被静默拒印
            out.write("\u001b%-12345X@PJL JOB NAME=PRINTER\r\n".toByteArray())
            out.write("@PJL SET RESOLUTION=600\r\n".toByteArray())
            out.write("@PJL SET BITSPERPIXEL=1\r\n".toByteArray())
            out.write("@PJL SET RENDERMODE=GRAYSCALE\r\n".toByteArray())
            out.write("@PJL SET COPIES=$copies\r\n".toByteArray())
            out.write("@PJL SET DUPLEX = OFF\r\n".toByteArray())
            out.write("@PJL ENTER LANGUAGE=LHPL\r\n".toByteArray())
            // sj
            out.write(chunk("sj", byteArrayOf(1, 0, 1, 0)))
            // sp + JBIG (字段语义t5定案: DMA=⌊XD/8⌋×YD, 全LE32, 纸码2=A4, 总长写两遍)
            fun le32(dst: ByteArray, off: Int, v: Int) {
                dst[off] = (v and 0xFF).toByte(); dst[off + 1] = ((v shr 8) and 0xFF).toByte()
                dst[off + 2] = ((v shr 16) and 0xFF).toByte(); dst[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            val pay = ByteArray(58)
            pay[0] = 2; pay[1] = 1; pay[3] = 20   // [6]=纸码2(A4) [7]=介质1(普通纸) [8..11]宽=5120
            le32(pay, 6, pageH)                    // [12..15] 页高
            le32(pay, 10, 640 * pageH)             // [16..19] DMA=640×h(固件按此分配引擎内存)
            le32(pay, 14, bie.size)                // [20..23] JBIG总长
            le32(pay, 18, bie.size)                // [24..27] slot0=总长(手机:总长写两遍)
            pay[36] = (600 and 0xFF).toByte(); pay[37] = (600 shr 8).toByte()
            pay[38] = (2100 and 0xFF).toByte(); pay[39] = (2100 shr 8).toByte()  // A4 210mm
            pay[40] = (2970 and 0xFF).toByte(); pay[41] = (2970 shr 8).toByte()  // A4 297mm
            out.write(chunk("sp", pay))
            out.write(bie)
            out.write(chunk("ep", null))
            out.write("\u001b%-12345X@PJL EOJ\r\n".toByteArray())
            // 发9100
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(java.net.InetAddress.getByName(ip), 9100), 6000)
            s.getOutputStream().apply { write(out.toByteArray()); flush() }
            s.close()
            true
        } catch (e: Exception) { false }
    }

/** PDF每页渲染成位图(600dpi等效) */
    fun pdfToBitmaps(cr: android.content.ContentResolver, uri: android.net.Uri): List<Bitmap> {
        val out = mutableListOf<Bitmap>()
        cr.openFileDescriptor(uri, "r")?.use { fd ->
            val renderer = android.graphics.pdf.PdfRenderer(fd)
            // A4@300dpi渲染(打印引擎内部会缩放到600dpi画布)
            val w = 1240; val h = 1754
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                out.add(bmp)
            }
            renderer.close()
        }
        return out
    }


    /** TXT文件→位图(系统字体逐行绘制, A4@300dpi) */
    fun txtToBitmaps(cr: android.content.ContentResolver, uri: android.net.Uri): List<Bitmap> {
        val text = cr.openInputStream(uri)?.bufferedReader()?.readText() ?: return emptyList()
        val pages = mutableListOf<Bitmap>()
        val w = 1240; val h = 1754  // A4@150dpi等效
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 28f; isAntiAlias = true
        }
        val lineH = 40f
        val maxChars = (((w - 80f) / paint.measureText("汉")).toInt()).coerceAtLeast(10)
        val lines = mutableListOf<String>()
        for (raw in text.lines()) {
            raw.chunked(maxChars).forEach { lines.add(it) }
            if (raw.isEmpty()) lines.add("")
        }
        val linesPerPage = ((h - 120f) / lineH).toInt()
        lines.chunked(linesPerPage).forEach { pageLines ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val cv = Canvas(bmp)
            pageLines.forEachIndexed { i, ln ->
                cv.drawText(ln, 40f, 80f + i * lineH, paint)
            }
            pages.add(bmp)
        }
        return pages
    }

}