package com.zml.m7208w

// MARK: - 打印机系统设置帧 (TCP 9100)
// 逆向自原厂 libsockcmd.so: powersave(休眠)/p2p-wifi(热点)/userconfig(打印微调)/
// maintenance(感光鼓复位·定影错误清除·关机)/password(管理员密码)。
// 帧结构: 4d3c2b1a | dev | 01 | cmd | len | 13 | 01 | sub | payload
// 实证规律(六条帧全部吻合): len = 帧总长, cmd = len - 8, sub 偶数=读/奇数=写。
// ACK 判定: resp[4]=dev, resp[5]=01, resp[8]=13, resp[9]=01, resp[10]=sub;
// resp[11] 为错误码(0=成功, 0x26=参数非法, 0x2e=热点密码校验失败等)。

object VopFrame {
    private val MAGIC = byteArrayOf(0x4d, 0x3c, 0x2b, 0x1a)
    /** 读收发超时(规格: SO_RCVTIMEO/SO_SNDTIMEO 20s) */
    private const val IO_TIMEOUT_MS = 20_000
    private const val HEADER_LEN = 11

    /** 按帧规律构帧: dev=设备域, sub=子命令, payload=载荷 */
    fun build(dev: Int, sub: Int, payload: ByteArray): ByteArray {
        val len = HEADER_LEN + payload.size
        val cmd = len - 8
        val f = ByteArray(len)
        MAGIC.copyInto(f, 0)
        f[4] = dev.toByte(); f[5] = 0x01
        f[6] = cmd.toByte(); f[7] = len.toByte()
        f[8] = 0x13; f[9] = 0x01
        f[10] = sub.toByte()
        payload.copyInto(f, HEADER_LEN)
        return f
    }

    /** 发送一帧并读回应答(连接/超时失败返回 null) */
    fun transact(ip: String, frame: ByteArray): ByteArray? {
        val t = Tcp.connect(ip, 9100, 5000) ?: return null
        return try {
            t.send(frame)
            val buf = ByteArray(256)
            val n = t.recv(buf, IO_TIMEOUT_MS)
            if (n > 0) buf.copyOf(n) else null
        } finally { t.rstClose() }
    }

    /** 应答头校验: dev/固定尾巴/子命令全部对上才算设备受理 */
    fun isAck(resp: ByteArray, dev: Int, sub: Int): Boolean =
        resp.size >= HEADER_LEN && resp[4] == dev.toByte() && resp[5] == 0x01.toByte() &&
            resp[8] == 0x13.toByte() && resp[9] == 0x01.toByte() && resp[10] == sub.toByte()

    /** 取应答错误码(resp[11], 0=成功); 应答过短返回 -1 */
    fun errorCode(resp: ByteArray): Int =
        if (resp.size >= 12) resp[11].toInt() and 0xFF else -1
}

// MARK: - 休眠时间设置 (cmd_print_powersave_get/set)
object SleepPower {
    private const val DEV = 0x07
    private const val SUB_GET = 0x00
    private const val SUB_SET = 0x01
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 30
    /** App 侧入参非法(原生层二次校验会返回 0x26, 这里前置拦截) */
    const val ERR_INVALID_PARAM = 0x26

    /** 读休眠分钟数; 通信失败/应答非法返回 null */
    fun get(ip: String): Int? {
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, SUB_GET, ByteArray(0))) ?: return null
        if (!VopFrame.isAck(resp, DEV, SUB_GET)) return null
        return resp.getOrNull(11)?.toInt()?.and(0xFF)
    }

    /** 写休眠分钟数; 返回 null=通信失败, 0=成功, 其他=设备错误码 */
    fun set(ip: String, minutes: Int): Int? {
        if (minutes !in MIN_MINUTES..MAX_MINUTES) return ERR_INVALID_PARAM
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, SUB_SET, byteArrayOf(minutes.toByte())))
            ?: return null
        if (!VopFrame.isAck(resp, DEV, SUB_SET)) return null
        return VopFrame.errorCode(resp)
    }
}

// MARK: - 热点AP开关 (cmd p2p-wifi, 写帧191B实证布局)
object HotspotAp {
    private const val DEV = 0x06
    private const val SUB_GET = 0x10
    private const val SUB_SET = 0x11
    /** App 硬编码: 6=开, 1=关; 应答 status==6||7 判定已开 */
    const val ENABLE_CODE = 6
    const val DISABLE_CODE = 1
    private const val FRAME_LEN = 191
    private const val PAYLOAD_LEN = FRAME_LEN - 11        // 180B
    private const val SSID_OFF = 19 - 11                  // 载荷内偏移8: SSID 32B
    private const val PWD_OFF = 51 - 11                   // 载荷内偏移40: 密码 64B
    const val SSID_LEN = 32
    const val PWD_LEN = 64
    /** 密码校验失败错误码(原生层校验不过返回 0x2e=46) */
    const val ERR_PWD_INVALID = 0x2e

    data class State(val enabled: Boolean, val ssid: String, val password: String)

    /** 读热点状态; 应答布局与写帧对称: [11]=status, [19..50]=SSID, [51..114]=密码 */
    fun get(ip: String): State? {
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, SUB_GET, ByteArray(0))) ?: return null
        if (!VopFrame.isAck(resp, DEV, SUB_GET)) return null
        val status = resp.getOrNull(11)?.toInt()?.and(0xFF) ?: return null
        return State(
            enabled = status == ENABLE_CODE || status == 7,
            ssid = cString(resp, SSID_OFF + 11, SSID_LEN),
            password = cString(resp, PWD_OFF + 11, PWD_LEN))
    }

    /** 写热点开关; 返回 null=通信失败, 0=成功, 其他=设备错误码 */
    fun set(ip: String, enable: Boolean, ssid: String, password: String): Int? {
        val payload = ByteArray(PAYLOAD_LEN)
        payload[0] = (if (enable) ENABLE_CODE else DISABLE_CODE).toByte()
        copyCleared(payload, SSID_OFF, SSID_LEN, ssid)
        copyCleared(payload, PWD_OFF, PWD_LEN, password)
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, SUB_SET, payload)) ?: return null
        if (!VopFrame.isAck(resp, DEV, SUB_SET)) return null
        return VopFrame.errorCode(resp)
    }

    /** 入参校验: SSID 1~32 ASCII 可打印, 密码 8~64; 不合法返回中文原因(合法返回 null) */
    fun validate(enable: Boolean, ssid: String, password: String): String? {
        if (!enable) return null   // 关闭动作不校验内容
        if (ssid.isEmpty() || ssid.length > SSID_LEN || ssid.any { it.code < 0x20 || it.code > 0x7e })
            return "SSID 需为 1~32 位可打印字符"
        if (password.length < 8 || password.length > PWD_LEN)
            return "密码需为 8~64 位"
        return null
    }

    /** 从字节数组截 \0 结尾字符串 */
    private fun cString(b: ByteArray, off: Int, max: Int): String {
        if (off >= b.size) return ""
        val end = minOf(off + max, b.size)
        val sb = StringBuilder()
        for (i in off until end) {
            val v = b[i].toInt() and 0xFF
            if (v == 0) break
            sb.append(v.toChar())
        }
        return sb.toString()
    }

    /** ASCII 填充目标区(源超长截断, 不足补 \0) */
    private fun copyCleared(dst: ByteArray, off: Int, len: Int, src: String) {
        val bytes = src.toByteArray(Charsets.US_ASCII)
        val n = minOf(bytes.size, len)
        bytes.copyInto(dst, off, 0, n)
        // ByteArray 默认已是 0, 无需显式清零
    }
}

// MARK: - 打印位置微调/默认浓度/低湿度 (userconfig 帧, 写帧总长27B实证)
object UserConfig {
    private const val DEV = 0x07
    private const val SUB_GET = 0x02
    private const val SUB_SET = 0x03
    /** 载荷 6 参数字节 + 10 字节 0 填充 = 16B(由写帧总长 27B 反推) */
    private const val PAYLOAD_LEN = 16
    private const val PAD_LEN = PAYLOAD_LEN - 6

    /** 偏移/浓度取值范围(原生 sxtb 符号扩展, 偏移约0.5mm/格) */
    const val OFFSET_MIN = -6
    const val OFFSET_MAX = 6
    const val DENSITY_MIN = -3
    const val DENSITY_MAX = 3
    /** 背面脏污模式仅接受 0=开/2=关 */
    const val PLATE_DIRTY_ON = 0
    const val PLATE_DIRTY_OFF = 2

    /** userconfig 六参数(字节序: 上偏|左偏|浓度|低湿度|背面脏污|强制冷却) */
    data class Values(
        val leadingEdge: Int,      // 上边偏移 -6..+6
        val sideToSide: Int,       // 左边偏移 -6..+6
        val imageDensity: Int,     // 打印机默认浓度 -3..+3
        val lowHumidity: Boolean,  // 低湿度模式
        val plateControl: Int,     // 背面脏污 0=开/2=关
        val coolMode: Int)         // 强制冷却 0/1

    fun defaults() = Values(0, 0, 0, false, PLATE_DIRTY_OFF, 0)

    /** 读六参数; 应答 payload 为同序 6 字节(有符号) */
    fun get(ip: String): Values? {
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, SUB_GET, ByteArray(0))) ?: return null
        if (!VopFrame.isAck(resp, DEV, SUB_GET) || resp.size < 17) return null
        fun sb(i: Int) = resp[11 + i].toInt()   // Byte 已是符号扩展值
        return Values(sb(0), sb(1), sb(2), sb(3) != 0, sb(4), sb(5))
    }

    /** 写六参数; 返回 null=通信失败, 0=成功, 其他=设备错误码 */
    fun set(ip: String, v: Values): Int? {
        if (v.leadingEdge !in OFFSET_MIN..OFFSET_MAX || v.sideToSide !in OFFSET_MIN..OFFSET_MAX ||
            v.imageDensity !in DENSITY_MIN..DENSITY_MAX) return SleepPower.ERR_INVALID_PARAM
        if (v.plateControl != PLATE_DIRTY_ON && v.plateControl != PLATE_DIRTY_OFF) return SleepPower.ERR_INVALID_PARAM
        val payload = ByteArray(PAYLOAD_LEN)
        payload[0] = v.leadingEdge.toByte()
        payload[1] = v.sideToSide.toByte()
        payload[2] = v.imageDensity.toByte()
        payload[3] = if (v.lowHumidity) 1 else 0
        payload[4] = v.plateControl.toByte()
        payload[5] = if (v.coolMode in 0..1) v.coolMode.toByte() else 0
        // payload[6..15] 保持 0 填充
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, SUB_SET, payload)) ?: return null
        if (!VopFrame.isAck(resp, DEV, SUB_SET)) return null
        return VopFrame.errorCode(resp)
    }
}

// MARK: - 感光鼓复位/定影错误清除/关机 (dev=07 单字节载荷写帧族, 逆向 cmd_print_drum_reset 等)
object DeviceMaintenance {
    private const val DEV = 0x07
    /** sub 值(反汇编 cmd_print_* 函数 [sp+0x2e] 字节): 定影错误清除 0x0b, 感光鼓复位 0x1a, 关机 0x0f */
    private const val SUB_FUSER_RESET = 0x0b
    private const val SUB_DRUM_RESET = 0x1a
    private const val SUB_POWEROFF_SET = 0x0f
    /** 原厂 JNI SetPrinterPowerOffState 以 `and v,#1` 掩码入参, 取值仅 0/1 */
    private const val ACTION_ON = 1

    /** 复位感光鼓计数(换新硒鼓后执行); 返回 null=通信失败, 0=成功, 其他=设备错误码 */
    fun drumReset(ip: String): Int? = writeOne(ip, SUB_DRUM_RESET)

    /** 清除定影错误状态; 返回 null=通信失败, 0=成功, 其他=设备错误码 */
    fun fuserReset(ip: String): Int? = writeOne(ip, SUB_FUSER_RESET)

    /** 关机(poweroff_set, 载荷 1=执行); 返回 null=通信失败, 0=成功, 其他=设备错误码 */
    fun powerOff(ip: String): Int? = writeOne(ip, SUB_POWEROFF_SET)

    /** 单字节载荷写帧公共路径 */
    private fun writeOne(ip: String, sub: Int): Int? {
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, sub, byteArrayOf(ACTION_ON.toByte()))) ?: return null
        if (!VopFrame.isAck(resp, DEV, sub)) return null
        return VopFrame.errorCode(resp)
    }
}

// MARK: - 管理员密码 (password_set sub=0x06 / password_check sub=0x08, 43B帧, 逆向 cmd_print_password_*)
object AdminPassword {
    private const val DEV = 0x07
    private const val SUB_SET = 0x06
    private const val SUB_CHECK = 0x08
    /** 载荷为 32B 密码区(原生 strncpy 语义: 拷贝 min(len,32) 字节, 余下补 0), 帧总长 43B */
    private const val PWD_LEN = 32

    /** 校验密码(原厂 checkAminPassword 同款帧); 返回 null=通信失败, 0=通过, 其他=设备错误码 */
    fun check(ip: String, password: String): Int? = transactPwd(ip, SUB_CHECK, password)

    /** 改密码: 先发 check 帧验旧密码, 通过才发 set 帧写新密码(原厂 App 层流程: 线上无旧密码字段) */
    fun setAdminPassword(ip: String, oldPassword: String, newPassword: String): Int? =
        when (val checkErr = check(ip, oldPassword)) {
            null -> null
            0 -> transactPwd(ip, SUB_SET, newPassword)
            else -> checkErr
        }

    /** 密码帧公共路径: 32B 零填充 ASCII 载荷 */
    private fun transactPwd(ip: String, sub: Int, password: String): Int? {
        val payload = ByteArray(PWD_LEN)
        val bytes = password.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(payload, 0, 0, minOf(bytes.size, PWD_LEN))
        val resp = VopFrame.transact(ip, VopFrame.build(DEV, sub, payload)) ?: return null
        if (!VopFrame.isAck(resp, DEV, sub)) return null
        return VopFrame.errorCode(resp)
    }
}
