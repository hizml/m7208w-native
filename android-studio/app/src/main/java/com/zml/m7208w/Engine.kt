package com.zml.m7208w

import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

// MARK: - TCP 工具

class Tcp private constructor(val socket: Socket) {
    companion object {
        fun connect(ip: String, port: Int, timeoutMs: Int = 5000): Tcp? {
            return try {
                val s = Socket()
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
                Tcp(s)
            } catch (e: Exception) { null }
        }
    }
    fun send(data: ByteArray) {
        val out = socket.getOutputStream()
        out.write(data); out.flush()
    }
    fun recv(buf: ByteArray, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs
        return try { socket.getInputStream().read(buf) } catch (e: Exception) { -1 }
    }
    fun rstClose() {
        try {
            socket.setSoLinger(true, 0)
            socket.close()
        } catch (_: Exception) {}
    }
    fun waitPeerClose(seconds: Int) {
        socket.soTimeout = seconds * 1000
        val buf = ByteArray(4096)
        try { while (socket.getInputStream().read(buf) > 0) {} } catch (_: Exception) {}
    }
}

// MARK: - 9100 命令帧 (逆向自手机App libsockcmd)

object PrinterControl {
    // 帧: 4d3c2b1a 07 01 03 00 13 01 [cmd]  cmd: 0x10=签名 0x0d=重启 0x00=WiFi查询
    fun command(ip: String, cmd: Int): ByteArray? {
        val t = Tcp.connect(ip, 9100, 4000) ?: return null
        t.send(byteArrayOf(0x4d, 0x3c, 0x2b, 0x1a, 0x07, 0x01, 0x03, 0x00, 0x13, 0x01, cmd.toByte()))
        val buf = ByteArray(75)
        val n = t.recv(buf, 3000)
        t.rstClose()
        return if (n > 0) buf.copyOf(n) else ByteArray(0)
    }

    fun isAlive(ip: String): Boolean {
        val r = command(ip, 0x10) ?: return false
        return r.size >= 8 && r[0] == 0x4d.toByte()
    }

    fun reboot(ip: String): Boolean {
        val r = command(ip, 0x0d) ?: return false
        return r.size >= 9 && r[8] == 0x0d.toByte()
    }
}

// MARK: - SNMP 发现 (手机同款广播)

object PrinterDiscovery {
    private val query = byteArrayOf(
        0x30, 0x2d, 0x02, 0x01, 0x00, 0x04, 0x06, 0x70, 0x75, 0x62, 0x6c, 0x69, 0x63,
        0xa0.toByte(), 0x20, 0x02, 0x01, 0x05, 0x02, 0x01, 0x00, 0x02, 0x01, 0x00, 0x30, 0x15, 0x30, 0x13, 0x06, 0x0f,
        0x2b, 0x06, 0x01, 0x04, 0x01, 0x95.toByte(), 0x0b, 0x01, 0x02, 0x01, 0x02, 0x01, 0x01, 0x03, 0x01, 0x05, 0x00
    )

    /** 9100签名帧指纹校验(回包头=4d3c2b1a且[10]=0x10) */
    fun probe(ip: String, timeoutMs: Int = 800): Boolean {
        return try {
            val s = java.net.Socket()
            s.tcpNoDelay = true
            s.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName(ip), 9100), timeoutMs)
            s.soTimeout = timeoutMs
            val frame = byteArrayOf(0x4d, 0x3c, 0x2b, 0x1a, 0x07, 0x01, 0x03, 0x00, 0x13, 0x01, 0x10)
            s.getOutputStream().apply { write(frame); flush() }
            val buf = ByteArray(16)
            val n = s.getInputStream().read(buf)
            s.close()
            n >= 11 && buf[0] == 0x4d.toByte() && buf[1] == 0x3c.toByte() &&
                buf[2] == 0x2b.toByte() && buf[3] == 0x1a.toByte() && buf[10] == 0x10.toByte()
        } catch (e: Exception) { false }
    }

    fun discover(timeoutMs: Int = 3000): List<String> {
        val found = mutableListOf<String>()
        var sock: java.net.DatagramSocket? = null
        return try {
            val s = java.net.DatagramSocket()
            sock = s
            s.broadcast = true
            s.soTimeout = 500
            val bcast = InetAddress.getByName("255.255.255.255")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                s.send(java.net.DatagramPacket(query, query.size, bcast, 161))
                val buf = ByteArray(2048)
                val pkt = java.net.DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                    if (pkt.length > 100) {
                        val ip = pkt.address.hostAddress ?: continue
                        if (ip !in found) found.add(ip)
                    }
                } catch (_: Exception) {}
            }
            s.close()
            found
        } catch (e: Exception) {
            try { sock?.close() } catch (_: Exception) {}
            return found
        }
    }
}


// MARK: - 墨粉尽继续打印开关 (逆向 cmd_print_tonerend_get/set)
object TonerEndControl {
    private fun cmd(ip: String, sub: Int, value: Int): ByteArray? {
        val t = Tcp.connect(ip, 9100, 5000) ?: return null
        try {
            t.send(byteArrayOf(0x4d, 0x3c, 0x2b, 0x1a, 0x07, 0x01, 0x04, 0x00, 0x13, 0x01, sub.toByte(), value.toByte()))
            val buf = ByteArray(32)
            val n = t.recv(buf, 6000)
            return if (n > 0) buf.copyOf(n) else null
        } finally { t.rstClose() }
    }
    /// 读状态码 (02=墨粉未尽/选项锁定)
    fun get(ip: String): Int? = cmd(ip, 0x11, 0)?.getOrNull(11)?.toInt()?.and(0xFF)
    /// 设开关 (回执末位00=受理 04=拒绝)
    fun set(ip: String, on: Boolean): Boolean {
        val r = cmd(ip, 0x12, if (on) 1 else 0) ?: return false
        return r.size >= 12 && r[11] == 0x00.toByte()
    }
    /// 重置墨粉计数 (帧发出即算)
    fun resetCounter(ip: String) { cmd(ip, 0x19, 0) }
}


// MARK: - 墨粉状态 (Web状态页抓取, 信任自签证书)

object TonerStatus {
    /// 返回 (墨粉%, 已印页, 标准页) 或 null; 信任打印机自签证书
    fun fetch(ip: String): Triple<Int, Int, Int>? {
        return try {
            val trustAll = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val sc = javax.net.ssl.SSLContext.getInstance("TLS")
            sc.init(null, arrayOf(trustAll), java.security.SecureRandom())
            val factory = sc.socketFactory
            val conn = java.net.URL("https://$ip/status.asp").openConnection() as javax.net.ssl.HttpsURLConnection
            conn.sslSocketFactory = factory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val parts = html.replace(Regex("<[^>]+>"), "|")
                .split("|").map { it.trim() }.filter { it.isNotEmpty() }
            fun valueAfter(kw: String): Int {
                for (i in parts.indices) if (parts[i].contains(kw)) {
                    for (j in i + 1 until minOf(i + 4, parts.size)) {
                        val digits = parts[j].filter { it.isDigit() }
                        if (digits.isNotEmpty()) return digits.toInt()
                    }
                }
                return 0
            }
            val t = valueAfter("Toner Volume")
            if (t == 0) null
            else Triple(t, valueAfter("Print Page Number of Current"), valueAfter("Standard Page Number of Current"))
        } catch (_: Exception) { null }
    }
}
