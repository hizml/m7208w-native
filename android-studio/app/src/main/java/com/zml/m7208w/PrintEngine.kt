package com.zml.m7208w

import android.graphics.Bitmap
// 证件表改用CertTable(原厂14种, 2026-10-28)
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
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
        val scale = minOf(CANVAS_W.toFloat() / bmp.width, pageHeight.toFloat() / bmp.height, 8f) * (scalePct.coerceIn(25, 400) / 100f)
        val tw = (bmp.width * scale).toInt().coerceAtMost(CANVAS_W)
        val th = (bmp.height * (tw.toFloat() / bmp.width)).toInt().coerceAtMost(pageHeight)
        val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val offX = (CANVAS_W - tw) / 2
        val offY = (pageHeight - th) / 2
        // 逐行取像素+行缓冲复用(峰值内存: 1行像素+误差行, 修OOM审查H1)
        val rowBuf = IntArray(tw)
        var cur = IntArray(tw)
        var next = IntArray(tw)
        for (y in 0 until th) {
            scaled.getPixels(rowBuf, 0, tw, 0, y, tw, 1)
            val rowY = offY + y
            java.util.Arrays.fill(next, 0)
            for (x in 0 until tw) {
                val p = rowBuf[x]
                var g = ((p shr 16 and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                if (density != 0) {
                    val f = 1.0 + 0.12 * density
                    val d = g * f + (255 - 255 * f) / 2
                    g = d.toInt().coerceIn(0, 255)
                }
                val v = g + cur[x]
                val thr = if (tonerSave) 98 else 128
                val bit = v < thr
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
        }
        scaled.recycle()   // 及时回收缩放位图
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

    /** 纸张几何规格(自定义纸张运行时构造; 字段语义同原 PrintPaper 枚举) */
    data class PaperSpec(val label: String, val h600: Int, val wmm10: Int, val hmm10: Int,
                         val code: Int, val baseW96: Int, val baseH96: Int)

    // MARK: 打印纸张(枚举对齐原版8种; sp纸码实证A4=2/Letter=1, 其余按几何走A4码)
    // baseW96/baseH96 = 原厂预览像素表基准(约96dpi, PrtDrv.DefaultPagePixelSize):
    // A4=793x1135, Letter=816x1066, B5=659x784, A5=551x785(renderParams.PREVIEW_PIXEL_BASE),
    // 其余4种原厂表未载, 按96dpi几何换算(mm×96/25.4)
    enum class PrintPaper(val label: String, val h600: Int, val wmm10: Int, val hmm10: Int, val code: Int,
                          val baseW96: Int, val baseH96: Int) {
        A4("A4", 7016, 2100, 2970, 2, 793, 1135),
        LETTER("Letter", 6600, 2159, 2794, 1, 816, 1066),
        B5("B5", 5906, 1760, 2500, 2, 659, 784),
        A5("A5", 4950, 1480, 2100, 2, 551, 785),
        B6("B6", 4157, 1250, 1760, 2, 472, 665),
        A6("A6", 3490, 1050, 1480, 2, 397, 559),
        K16("16K", 6378, 1930, 2700, 2, 730, 1021),
        EXEC("Executive", 6298, 1841, 2667, 2, 696, 1008);

        fun spec() = PaperSpec(label, h600, wmm10, hmm10, code, baseW96, baseH96)
    }

    /** 自定义纸张几何(mm→600dpi像素/纸码走A4几何码): 原厂 customW/H = inch×600, PageSize=17 */
    fun customPaperSpec(wmm: Double, hmm: Double): PaperSpec {
        val w = wmm.coerceIn(50.0, 330.0)
        val h = hmm.coerceIn(50.0, 1200.0)
        val inch = 25.4
        return PaperSpec(
            label = "自定义 ${w.toInt()}×${h.toInt()}mm",
            h600 = (h / inch * 600).toInt(),
            wmm10 = (w * 10).toInt(),
            hmm10 = (h * 10).toInt(),
            code = 2,   // 非照片通道 pageSize>11 强制回 0, 自定义仅照片/预览通道: 几何走A4码
            baseW96 = (w / inch * 96).toInt(),
            baseH96 = (h / inch * 96).toInt())
    }
    enum class PrintMedia(val label: String, val code: Int) {
        PLAIN("普通纸", 1), RECYCLED("再生纸", 2), THICK("厚纸", 3), THIN("薄纸", 4), LABEL("标签", 5)
    }

    /** N合1合成: pages按 n×n 网格拼到一张白底A4(每格等比缩放); 公开给手动双面分面用 */
    fun composeSheets(pages: List<Bitmap>, n: Int): List<Bitmap> = composeNup(pages, n)

    /** N合1合成实现: pages按 n×n 网格拼到一张白底A4(每格等比缩放) */
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

    /** 证件类型表: 原厂 R.array.certificate_crop_size 数值未提取(notFound),
     *  身份证 85.6×54 为原厂同款实证, 其余为国内卡式/本式证件公开标准规格(护照 ID-3 125×88) */
    data class CertType(val label: String, val wmm: Double, val hmm: Double)

    val CERT_TYPES = listOf(
        CertType("身份证", 85.6, 54.0),
        CertType("驾驶证", 85.6, 54.0),
        CertType("社保卡", 85.6, 54.0),
        CertType("居住证", 85.6, 54.0),
        CertType("港澳通行证", 85.6, 54.0),
        CertType("台湾通行证", 85.6, 54.0),
        CertType("回乡证", 85.6, 54.0),
        CertType("护照", 125.0, 88.0))

    // 原厂 PrintFetchFileUtils 合成常量(已抽查证实): 画布952×1362, 中线681, 圆角10px
    private const val CERT_CANVAS_W = 952
    private const val CERT_CANVAS_H = 1362
    private const val CERT_MIDLINE = 681
    private const val CERT_CORNER_PX = 10f
    /** 画布952px 对应纸宽210mm → 像素/毫米换算 */
    private const val CERT_PX_PER_MM = CERT_CANVAS_W / 210.0f

    /** 证件打印合成(复刻原厂布局): 正面上半 y=(中线-h)/2×1.25, 背面下半 y=(中线-h)/2÷1.25+中线,
     *  水平居中, 目标尺寸=证件mm×像素/mm, 圆角10px */
    fun composeCertificate(front: Bitmap, back: Bitmap, type: CertType): Bitmap {
        val bmp = Bitmap.createBitmap(CERT_CANVAS_W, CERT_CANVAS_H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val tw = (type.wmm * CERT_PX_PER_MM).toFloat()
        val th = (type.hmm * CERT_PX_PER_MM).toFloat()
        drawCertFace(cv, p, front, tw, th, frontFace = true)
        drawCertFace(cv, p, back, tw, th, frontFace = false)
        return bmp
    }

    /** 单面绘制: 等比缩放进目标框 + 圆角裁剪 + 原厂Y公式 */
    private fun drawCertFace(cv: Canvas, p: Paint, src: Bitmap, tw: Float, th: Float, frontFace: Boolean) {
        val s = minOf(tw / src.width, th / src.height)
        val w = src.width * s; val h = src.height * s
        val x = (CERT_CANVAS_W - w) / 2f
        val y = if (frontFace) (CERT_MIDLINE - h) / 2f * 1.25f
                else (CERT_MIDLINE - h) / 2f / 1.25f + CERT_MIDLINE
        val dst = RectF(x, y, x + w, y + h)
        cv.save()
        val clip = android.graphics.Path().apply {
            addRoundRect(dst, CERT_CORNER_PX, CERT_CORNER_PX, android.graphics.Path.Direction.CW) }
        cv.clipPath(clip)
        cv.drawBitmap(src, null, dst, p)
        cv.restore()
    }

    /** 完整打印作业: 纸张/介质/N合1/缩放/浓度/省墨/份数(份数走协议COPIES原生支持) */
    fun printJob(ip: String, pages: List<Bitmap>, paper: PaperSpec = PrintPaper.A4.spec(),
                 media: PrintMedia = PrintMedia.PLAIN, nup: Int = 1, scalePct: Int = 100,
                 density: Int = 0, tonerSave: Boolean = false, copies: Int = 1): Boolean =
        sendSheets(ip, composeNup(pages, nup), paper, media, scalePct, density, tonerSave, copies)

    /** 发送已合成的"纸"集合(手动双面分两次调用: 正面集→翻纸→背面集) */
    fun sendSheets(ip: String, sheets: List<Bitmap>, paper: PaperSpec,
                   media: PrintMedia, scalePct: Int, density: Int,
                   tonerSave: Boolean, copies: Int): Boolean {
        val composed = sheets
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

    // MARK: - 预览管线(照原厂规格重写)
    // 原厂(renderParams/howItWorks): 预览与打印同一条光栅管线(仅换模拟器标志4),
    // 灰度化/浓度/省墨全部在native内完成, Java层无任何抖动/ColorMatrix代码;
    // 可证实的是 colorMode=0(单色) + 显示RGB_565 → 预览=灰度位图(无1bpp网点抖动),
    // 改参即全量重光栅化(无叠加滤镜)。

    /** 原厂quality=5(RES600)预览光栅系数(renderParams.QUALITY: ×6.0) */
    private const val PREVIEW_QUALITY_FACTOR = 6.0f
    /** 省墨档视觉等效: 着墨量降至75%(原厂值在native内不可见, 取打印机省墨常见档) */
    private const val TONER_SAVE_INK_RATIO = 0.75f
    /** N合1子页留边(与composeNup打印合成一致) */
    private const val NUP_CELL_MARGIN = 0.92f

    /** 单色管线滤镜: 灰度(30/59/11, 与bitmapToCanvas同权重)×浓度×省墨, 合成单次线性矩阵
     *  浓度公式与打印侧一致(f=1+0.12·density, g'=f·g+(255-255f)/2); 省墨=黑能量乘s */
    private fun monoColorFilter(density: Int, tonerSave: Boolean): ColorMatrixColorFilter {
        val f = 1.0 + 0.12 * density.coerceIn(-3, 3)
        val s: Double = if (tonerSave) TONER_SAVE_INK_RATIO.toDouble() else 1.0
        val gain = (s * f).toFloat()
        // v' = s·f·(0.30R+0.59G+0.11B) + [255-255s + s·(255-255f)/2], 结果自动clamp到0..255
        val offset = (255 - 255 * s + s * (255 - 255 * f) / 2).toFloat()
        val lum = floatArrayOf(0.30f * gain, 0.59f * gain, 0.11f * gain)
        val row = floatArrayOf(lum[0], lum[1], lum[2], 0f, offset)
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            row[0], row[1], row[2], row[3], row[4],
            row[0], row[1], row[2], row[3], row[4],
            row[0], row[1], row[2], row[3], row[4],
            0f, 0f, 0f, 1f, 0f)))
    }

    /** N合1网格(列to行; 与composeNup/printJob同分片规则: 2合1=上下, 4/9/16=n×n) */
    private fun nupGrid(nup: Int): Pair<Int, Int> = when (nup) {
        2 -> 1 to 2; 4 -> 2 to 2; 9 -> 3 to 3; 16 -> 4 to 4; else -> 1 to 1
    }

    /** 预览渲染(原厂两级结构的光栅级): 页光栅=纸种96dpi基准×6.0, RGB_565白纸,
     *  灰度+浓度+省墨经ColorMatrix在绘制时一次完成, N合1/缩放/居中/超纸裁剪同打印规则 */
    fun renderPreview(pages: List<Bitmap>, pageIdx: Int, paper: PaperSpec,
                      nup: Int, scalePct: Int, density: Int, tonerSave: Boolean): Bitmap? {
        if (pages.isEmpty()) return null
        // 页光栅尺寸对齐原厂: DefaultPagePixelSize(96dpi基准)×quality=5系数6.0
        val w = (paper.baseW96 * PREVIEW_QUALITY_FACTOR).toInt()
        val h = (paper.baseH96 * PREVIEW_QUALITY_FACTOR).toInt()
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val cv = Canvas(page); cv.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = monoColorFilter(density, tonerSave)
        }
        // 当前预览纸上的子页(分片规则与composeNup一致, 原厂CheckPreviewPage凑齐nUp子页输出一张)
        val (cols, rows) = nupGrid(nup)
        val cells = pages.drop((pageIdx / nup) * nup).take(nup)
        val cw = w.toFloat() / cols; val ch = h.toFloat() / rows
        val scale = scalePct.coerceIn(25, 400) / 100f
        cells.forEachIndexed { i, b ->
            val s = minOf(cw * NUP_CELL_MARGIN / b.width, ch * NUP_CELL_MARGIN / b.height) * scale
            val bw = b.width * s; val bh = b.height * s
            val x = (i % cols) * cw + (cw - bw) / 2
            val y = (i / cols) * ch + (ch - bh) / 2
            cv.drawBitmap(b, null, RectF(x, y, x + bw, y + bh), paint)  // 超出纸面=光栅边界自动裁
        }
        return page
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