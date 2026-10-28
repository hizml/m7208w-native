package com.zml.m7208w

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/// WiFi配网二维码(D2): 系统相机拍照得Bitmap → ZXing core解码 → 解析 WIFI: 标准串。
/// 二维码内容格式(ZXING WIFI Network Config):
///   WIFI:T:WPA;S:<ssid>;P:<password>;H:<hidden>;;  (值内 \; \, \" \\ 反斜杠转义)
object WifiQr {

    /** 二维码 T 字段 → 打印机 WiFi 帧加密码 (WiFiWriter.write: 0开放 1WEP64 2WEP128 3WPA2) */
    private val encByToken = mapOf("WPA" to 3, "WEP" to 1, "nopass" to 0)

    /** 解析出的配网信息(encryption 已映射为 WiFiWriter 帧码) */
    data class WifiQrContent(val ssid: String, val password: String, val encryption: Int)

    /** 位图 → 二维码文本; 相机缩略图可能上下颠倒, 原图失败后再试180°旋转 */
    fun decodeText(bmp: Bitmap): String? {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val reader = MultiFormatReader().apply {
            setHints(mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true))
        }
        for (px in sequenceOf(pixels, rotate180(pixels))) {
            val src = RGBLuminanceSource(w, h, px)
            try {
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
            } catch (_: Exception) {
                reader.reset()   // NotFoundException 为常态, 重置后试下一朝向
            }
        }
        return null
    }

    /** 像素序列倒序 = 180°旋转(行列同时翻转), 二维码无方向性但采样网格需要正立 */
    private fun rotate180(px: IntArray): IntArray {
        return IntArray(px.size) { px[px.size - 1 - it] }
    }

    private val WIFI_PREFIX = "WIFI:"

    /** WIFI: 标准串 → 配网信息; 非该格式或缺 SSID 返回 null。
     *  字段扫描直接推进位置(值内 \; 转义的分号不会切断字段) */
    fun parseWifi(text: String): WifiQrContent? {
        if (!text.startsWith(WIFI_PREFIX)) return null
        var ssid: String? = null
        var password = ""
        var encryption = 3   // 缺省按WPA2(当前主流)
        var i = WIFI_PREFIX.length
        while (i < text.length) {
            val colon = text.indexOf(':', i)
            if (colon < 0) break
            val key = text.substring(i, colon)
            val sb = StringBuilder()
            var j = colon + 1
            while (j < text.length && text[j] != ';') {
                if (text[j] == '\\' && j + 1 < text.length) { sb.append(text[j + 1]); j += 2 }
                else { sb.append(text[j]); j++ }
            }
            when (key) {
                "S" -> ssid = sb.toString()
                "P" -> password = sb.toString()
                "T" -> encryption = encByToken[sb.toString()] ?: encryption
            }
            i = j + 1   // 跳过字段结尾的未转义';'
        }
        if (ssid.isNullOrEmpty()) return null
        return WifiQrContent(ssid, password, encryption)
    }
}
