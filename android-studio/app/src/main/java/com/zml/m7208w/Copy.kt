package com.zml.m7208w

// MARK: - 网络复印 (逆向自 libsockcmd cmd_net_copy, 2026-10-17 真机验证)

enum class CopyPaper(val v: Int, val label: String) {
    A4(0, "A4"), LETTER(3, "信纸"), A5(2, "A5"), A6(13, "A6"),
    B5(5, "B5"), B6(14, "B6"), EXEC(6, "Executive"), K16(16, "16K")
}
enum class CopyMedia(val v: Int, val label: String) {
    PLAIN(0, "普通纸"), RECYCLED(1, "再生纸"), THICK(2, "厚纸"), THIN(3, "薄纸"), LABEL(4, "标签")
}
enum class CopyDpi(val v: Int, val label: String) { D300(0, "300 DPI"), D600(1, "600 DPI") }
enum class NupMode(val v: Int, val label: String) { OFF(0, "关"), TWO_UP(1, "2合1"), ID_CARD(4, "身份证") }

data class CopyOptions(
    var copies: Int = 1,
    var scale: Int = 100,
    var textMode: Boolean = true,
    var src: CopyPaper = CopyPaper.A4,
    var dst: CopyPaper = CopyPaper.A4,
    var nup: NupMode = NupMode.OFF,
    var dpi: CopyDpi = CopyDpi.D300,
    var media: CopyMedia = CopyMedia.PLAIN,
    var density: Int = 3
)

object CopyEngine {
    fun run(ip: String, o: CopyOptions): Boolean {
        val hdr = byteArrayOf(0x4d, 0x3c, 0x2b, 0x1a, 0x09, 0x01, 0x83.toByte(), 0x00, 0x13, 0x01, 0x01)
        val p = ByteArray(128)
        p[0] = o.density.toByte()
        p[1] = o.copies.toByte()
        p[2] = (if (o.textMode) 1 else 0).toByte()
        p[3] = o.src.v.toByte()
        p[4] = o.dst.v.toByte()
        p[5] = o.nup.v.toByte()
        p[6] = o.dpi.v.toByte()
        p[7] = o.media.v.toByte()
        p[8] = (o.scale and 0xFF).toByte()
        p[9] = ((o.scale shr 8) and 0xFF).toByte()
        val t = Tcp.connect(ip, 9100, 5000) ?: return false
        try {
            t.send(hdr + p)
            val buf = ByteArray(64)
            val n = t.recv(buf, 10000)
            return n >= 10 && buf[8] == 0x0d.toByte()
        } finally { t.rstClose() }
    }
}

object WiFiReader {
    /// 9100类型6帧: SSID@19, 密码@50 (实测偏移)
    fun read(ip: String): Pair<String, String>? {
        val t = Tcp.connect(ip, 9100, 5000) ?: return null
        try {
            t.send(byteArrayOf(0x4d,0x3c,0x2b,0x1a,0x06,0x01,0x03,0x00,0x13,0x01,0x00))
            val buf = ByteArray(512)
            val n = t.recv(buf, 4000)
            if (n < 60) return null
            fun field(off: Int, len: Int): String {
                val sb = StringBuilder()
                for (i in off until minOf(off + len, n)) {
                    if (buf[i] == 0.toByte()) break
                    sb.append((buf[i].toInt() and 0xFF).toChar())
                }
                return sb.toString()
            }
            return field(19, 32) to field(50, 34)
        } finally { t.rstClose() }
    }
}


object WiFiScanner {
    /// 扫描周围WiFi (帧: 4d3c2b1a 06 01 03 00 13 01 07, ~3s返回)
    fun scan(ip: String): List<String>? {
        val t = Tcp.connect(ip, 9100, 5000) ?: return null
        try {
            t.send(byteArrayOf(0x4d, 0x3c, 0x2b, 0x1a, 0x06, 0x01, 0x03, 0x00, 0x13, 0x01, 0x07))
            val buf = ByteArray(4096)
            val n = t.recv(buf, 30000)
            if (n < 40) return null
            val out = mutableListOf<String>()
            var i = 11
            while (i + 4 < n) {
                val sb = StringBuilder()
                for (j in i until minOf(i + 32, n)) {
                    if (buf[j] == 0.toByte()) break
                    sb.append((buf[j].toInt() and 0xFF).toChar())
                }
                if (sb.length >= 2 && sb.all { it.code in 32..126 }) out.add(sb.toString())
                i += 32
            }
            return if (out.isEmpty()) null else out
        } finally { t.rstClose() }
    }
}

object WiFiWriter {
    /// WiFi写入(逆向自手机驱动cmd_wifi_set, 191B帧走9100). ⚠️写错SSID打印机会失联需USB重配
    /// encryption: 0=开放 1=WEP64 2=WEP128 3=WPA2
    fun write(ip: String, ssid: String, password: String, encryption: Int = 3): Boolean {
        val f = ByteArray(191)
        f[0] = 0x4d; f[1] = 0x3c; f[2] = 0x2b; f[3] = 0x1a
        f[4] = 0x06; f[5] = 0x01; f[6] = 0xb7.toByte(); f[7] = 0x00
        f[8] = 0x13; f[9] = 0x01
        f[11] = 0x01          // enable
        f[15] = 0x01          // wifi enable
        f[16] = encryption.toByte()
        val s = ssid.toByteArray(Charsets.US_ASCII).copyOf(31) + byteArrayOf(0)
        for (i in s.indices) f[19 + i] = s[i]
        val p = password.toByteArray(Charsets.US_ASCII).copyOf(33) + byteArrayOf(0)
        for (i in p.indices) f[51 + i] = p[i]
        val t = Tcp.connect(ip, 9100, 5000) ?: return false
        try {
            t.send(f)
            val buf = ByteArray(64)
            val n = t.recv(buf, 10000)
            return n >= 10 && buf[4] == 0x06.toByte()   // 受理回执: 06回显
        } finally { t.rstClose() }
    }
}
