package com.zml.m7208w

/// 原厂证件打印数据表(从base.apk resources.arsc逐字节提取, 2026-10-28)
/// CropSize格式: scaleX|scaleY|outX|outY (原厂PrintFragment.CropSize解析规则)
data class CertType(
    val name: String,           // 显示名
    val requestCode: Int,       // request_code(=1走两面对拍流程)
    val scaleX: Int, val scaleY: Int,
    val outX: Int, val outY: Int,
    val title: String           // 选择提示文案
)

object CertTable {
    val ALL = listOf(
        CertType("身份证", 1, 214, 135, 320, 203, "请选择身份证"),
        CertType("户口本", 2, 2, 3, 529, 793, "请选择户口本"),
        CertType("结婚证", 3, 128, 187, 485, 704, "请选择结婚证"),
        CertType("护照", 4, 125, 176, 475, 661, "请选择护照"),
        CertType("房产证", 5, 166, 239, 630, 895, "请选择房产证"),
        CertType("驾照", 6, 74, 206, 279, 778, "请选择驾照"),
        CertType("毕业证", 7, 25, 35, 624, 880, "请选择毕业证"),
        CertType("学生证", 8, 73, 105, 274, 395, "请选择学生证"),
        CertType("出生证明", 9, 130, 189, 482, 712, "请选择出生证明"),
        CertType("银行卡/社保卡", 10, 54, 85, 205, 321, "请选择银行卡/社保卡"),
        CertType("一寸", 11, 27, 38, 125, 177, "请选择一寸"),
        CertType("中国护照", 12, 33, 48, 153, 222, "请选择中国护照"),
        CertType("欧洲/东南亚/日本签证", 13, 7, 9, 163, 209, "请选择欧洲/东南亚/日本签证"),
        CertType("美国签证", 14, 5, 5, 232, 232, "请选择美国签证"),
    )
}
