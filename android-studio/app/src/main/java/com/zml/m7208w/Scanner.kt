package com.zml.m7208w

import java.io.ByteArrayOutputStream
import java.util.Calendar

// MARK: - 扫描选项

enum class PaperSize(val label: String, val wmm: Double, val hmm: Double) {
    A4("A4", 210.0, 297.0),
    A5("A5", 148.0, 210.0),
    B5("B5", 176.0, 250.0),
    LETTER("信纸", 215.9, 279.4),
    A6("A6", 105.0, 148.0);
}

enum class ScanMode(val label: String) { TEXT("文本(锐化)"), PHOTO("照片(平滑)"), MIXED("混合") }

class ScanOptions(
    var dpi: Int = 300,
    var paper: PaperSize = PaperSize.A4,
    var mode: ScanMode = ScanMode.TEXT,
    var density: Int = 0,
    var brightness: Int = 0,
    var contrast: Int = 0
) {
    val w: Int get() = (paper.wmm / 25.4 * dpi).toInt()
    val h: Int get() = (paper.hmm / 25.4 * dpi).toInt()
}

// MARK: - Si 参数包 (168B, 基于已验证模板)

object SiPacket {
    /// 2026-10-16 真机验证可出图的 si_gray 模板原字节
    val template = byteArrayOf(
        0x53,0x69,0x00,0x00,0xa0.toByte(),0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0xea.toByte(),0x07,0x0a,0x0f,0x14,0x1e,0xe0.toByte(),0x01,0x80.toByte(),0x20,0x00,0x00,
        0xad.toByte(),0x2d,0x00,0x00,0x81.toByte(),0xff.toByte(),0xff.toByte(),0xff.toByte(),0x7f,0x00,0x00,0x00,0x01,0x00,0x00,0x00,
        0x81.toByte(),0xff.toByte(),0xff.toByte(),0xff.toByte(),0x7f,0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x07,0x00,0x00,0x00,0xb0.toByte(),0x09,0x00,0x00,0xb0.toByte(),0x09,0x00,0x00,0xb4.toByte(),0x0d,0x00,0x00,
        0x00,0x00,0x00,0x00,0x08,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x2c,0x01,0x00,0x00,0x2c,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0xb0.toByte(),0x09,0x00,0x00,0xb4.toByte(),0x0d,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x01,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
    )

    fun build(o: ScanOptions): ByteArray {
        val si = template.copyOf()
        val c = Calendar.getInstance()
        si[20] = (c.get(Calendar.YEAR) and 0xFF).toByte()
        si[21] = ((c.get(Calendar.YEAR) shr 8) and 0xFF).toByte()
        si[22] = (c.get(Calendar.MONTH) + 1).toByte()
        si[23] = c.get(Calendar.DAY_OF_MONTH).toByte()
        si[24] = c.get(Calendar.HOUR_OF_DAY).toByte()
        si[25] = c.get(Calendar.MINUTE).toByte()
        // 亮度/对比度: 模板[36..43]=(-127,127)中心平移±40 (Si字段实测在36/40亮度,48/52对比度)
        val bOff = o.brightness * 40 / 127
        put32(si, 36, -127 + bOff); put32(si, 40, 127 + bOff)
        val cOff = o.contrast * 40 / 127
        put32(si, 48, -127 + cOff); put32(si, 52, 127 + cOff)
        put32(si, 68, o.w)
        put32(si, 72, o.w)
        put32(si, 76, o.h)
        put32(si, 96, o.dpi); put32(si, 100, o.dpi)
        put32(si, 112, o.w); put32(si, 116, o.h)
        return si
    }

    private fun put32(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v shr 8) and 0xFF).toByte()
        a[off + 2] = ((v shr 16) and 0xFF).toByte()
        a[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
    private fun put16(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}

// MARK: - 扫描会话 (已验证生命周期: LTGS→53010→Sl→Si→数据→Su→等打印机关连接)

class ScanException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { BUSY, REFUSED, TIMEOUT, DEAD, CANCELLED }
}

class ScanSession(private val ip: String) {

    // 取消扫描(C8): 持有53010数据连接, 取消时RST断开(依赖设备侧超时自复位)
    @Volatile private var dataSocket: Tcp? = null
    @Volatile var isCancelled = false; private set

    /** 取消: 断开53010连接, 收流线程随即异常退出; 打印机侧超时自复位, 无需额外善后 */
    fun cancel() {
        isCancelled = true
        dataSocket?.rstClose()
    }

    fun scan(o: ScanOptions, onProgress: (Int, ByteArray) -> Unit = { _, _ -> }): Pair<ByteArray, Int> {
        var last: Exception = ScanException(ScanException.Kind.TIMEOUT)
        repeat(4) {
            if (isCancelled) throw ScanException(ScanException.Kind.CANCELLED)
            try { return oneScan(o, onProgress) }
            catch (e: ScanException) {
                if (e.kind == ScanException.Kind.CANCELLED) throw e
                if (e.kind == ScanException.Kind.BUSY) {
                    last = e; Thread.sleep(20000)
                } else throw e
            }
        }
        throw last
    }

    private fun oneScan(o: ScanOptions, onProgress: (Int, ByteArray) -> Unit): Pair<ByteArray, Int> {
        // ① 53011 激活
        val act = Tcp.connect(ip, 53011, 5000) ?: throw ScanException(ScanException.Kind.REFUSED)
        act.send("LTGS".toByteArray() + byteArrayOf(0, 0, 0, 0x53))
        act.recv(ByteArray(8), 4000)
        act.rstClose()
        // ② 53010 会话
        val s = Tcp.connect(ip, 53010, 5000) ?: throw ScanException(ScanException.Kind.REFUSED)
        dataSocket = s
        try {
            s.send(byteArrayOf(0x53, 0x6c) + ByteArray(6)) // Sl
            Thread.sleep(400)
            s.send(SiPacket.build(o))
            // ③ 收流(跨块同步状态机, 数据负载中的73 00不会误判)
            val raw = ByteArrayOutputStream()
            val buf = ByteArray(1 shl 20)
            var seenData = false
            var done = false
            var pos = 0
            val t0 = System.currentTimeMillis()
            while (!done && raw.size() < 50_000_000 &&
                   System.currentTimeMillis() - t0 < 40_000) {
                if (isCancelled) throw ScanException(ScanException.Kind.CANCELLED)
                val n = s.recv(buf, 40000)
                if (n == 0) break
                if (n < 0) throw ScanException(
                    if (raw.size() == 0) ScanException.Kind.DEAD else ScanException.Kind.TIMEOUT)
                raw.write(buf, 0, n)
                onProgress(raw.size(), raw.toByteArray())
                val arr = raw.toByteArray()
                val total = arr.size
                while (pos < total - 1) {
                    if (arr[pos] == 0x64.toByte()) {
                        if (pos + 8 > total) break  // 帧头未收全: 等(不许把d当垃圾跳过)
                        val len = (arr[pos+4].toInt() and 0xFF) or
                                  ((arr[pos+5].toInt() and 0xFF) shl 8) or
                                  ((arr[pos+6].toInt() and 0xFF) shl 16) or
                                  ((arr[pos+7].toInt() and 0xFF) shl 24)
                        if (pos + 8 + len > total) break
                        seenData = true
                        pos += 8 + len
                    } else if (arr[pos] == 0x73.toByte()) {
                        val code = arr[pos+1].toInt() and 0xFF
                        if (code == 3 && !seenData) throw ScanException(ScanException.Kind.BUSY)
                        if (code == 0 && seenData) { done = true; break }
                        pos += 2
                    } else pos++
                }
            }
            // ④ Su + 等打印机关连接
            try { s.send(byteArrayOf(0x53, 0x75) + ByteArray(6)) } catch (_: Exception) {}
            s.waitPeerClose(5)
            if (!seenData && raw.size() < 32) {
                throw ScanException(ScanException.Kind.DEAD) // 打印后固病
            }
            val (px, w) = Decoder.extract(raw.toByteArray(), o.h, o.dpi)
            return px to w
        } finally {
            dataSocket = null   // 先摘引用再关: cancel与finally双重rstClose均幂等
            s.rstClose()
        }
    }
}

// MARK: - 解码与后处理

object Decoder {
    fun extract(raw: ByteArray, expectedH: Int, dpi: Int = 300): Pair<ByteArray, Int> {
        val w = if (dpi >= 600) 4992 else 2496  // 设备原生行宽(实测)
        val out = ByteArrayOutputStream()
        var i = 16  // 跳过开头两个s+0就绪帧
        while (i + 8 <= raw.size) {
            if (raw[i] == 0x64.toByte()) {
                val len = (raw[i+4].toInt() and 0xFF) or
                          ((raw[i+5].toInt() and 0xFF) shl 8) or
                          ((raw[i+6].toInt() and 0xFF) shl 16) or
                          ((raw[i+7].toInt() and 0xFF) shl 24)
                val end = minOf(i + 8 + len, raw.size)
                out.write(raw, i + 8, end - (i + 8))
                i += 8 + len
            } else break  // 失步即停
        }
        return out.toByteArray() to w
    }

    fun postProcess(src: ByteArray, width: Int, mode: ScanMode, density: Int): ByteArray {
        val h = src.size / width
        if (h < 3 || width < 3) return src
        var out = src
        if (mode == ScanMode.TEXT || mode == ScanMode.PHOTO) {
            val dst = ByteArray(src.size)
            for (y in 1 until h - 1) {
                for (x in 1 until width - 1) {
                    val c = src[y * width + x].toInt() and 0xFF
                    val v = if (mode == ScanMode.TEXT) {
                        val blur = (
                            (src[(y-1)*width + x-1].toInt() and 0xFF) + (src[(y-1)*width + x].toInt() and 0xFF) +
                            (src[(y-1)*width + x+1].toInt() and 0xFF) + (src[y*width + x-1].toInt() and 0xFF) +
                            (src[y*width + x+1].toInt() and 0xFF) + (src[(y+1)*width + x-1].toInt() and 0xFF) +
                            (src[(y+1)*width + x].toInt() and 0xFF) + (src[(y+1)*width + x+1].toInt() and 0xFF)) / 8
                        c + ((c - blur) * 8) / 5
                    } else {
                        ((src[y*width + x-1].toInt() and 0xFF) + c * 2 + (src[y*width + x+1].toInt() and 0xFF) +
                         (src[(y-1)*width + x].toInt() and 0xFF) * 2 + (src[(y+1)*width + x].toInt() and 0xFF) * 2) / 8
                    }
                    dst[y * width + x] = coerce255(v)
                }
            }
            out = dst
        }
        if (density != 0) {
            val d = density * 80 / 127
            for (i in out.indices) out[i] = coerce255((out[i].toInt() and 0xFF) + d)
        }
        return out
    }

    private fun coerce255(v: Int): Byte =
        if (v < 0) 0.toByte() else if (v > 255) 255.toByte() else v.toByte()
}
