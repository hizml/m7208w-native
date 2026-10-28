package com.zml.m7208w

/// 原厂打印机状态码全表(从base.apk resources.arsc提取, 2026-10-28)
/// 格式: 0xNN|STATE|Message|Color|中文文案
data class PrinterStateInfo(
    val code: Int,
    val state: String,      // Ready/Busy/Error
    val message: String,
    val color: String,      // Black/Orange/Red
    val zh: String          // 状态条中文文案
)

object PrinterStateTable {
    val ALL = listOf(
        PrinterStateInfo(0x00, "Ready", "Ready", "Black", "就绪"),
        PrinterStateInfo(0x7F, "Ready", "Please replace a new toner cartridge", "Orange", "请更换新墨粉盒"),
        PrinterStateInfo(0x80, "Ready", "Please replace a new toner cartridge", "Orange", "请更换新墨粉盒"),
        PrinterStateInfo(0x81, "Ready", "Toner Near End", "Orange", "墨粉将尽, 建议备新墨粉盒"),
        PrinterStateInfo(0xEF, "Ready", "Print Data Error", "Orange", "打印数据错误"),
        PrinterStateInfo(0x02, "Sleep", "", "Black", "休眠中(省电模式)"),
        PrinterStateInfo(0x01, "Busy", "Printing", "Black", "打印中"),
        PrinterStateInfo(0x03, "Busy", "WarmingUp", "Black", "预热中"),
        PrinterStateInfo(0x04, "Busy", "Print Cancelling", "Black", "正在取消打印"),
        PrinterStateInfo(0x07, "Busy", "Processing", "Black", "数据处理中"),
        PrinterStateInfo(0x60, "Busy", "Copying", "Black", "复印中"),
        PrinterStateInfo(0x61, "Busy", "Place Next Page@Turn card over to copy the reverse.", "Black", "请放下一页@翻面复印证件背面"),
        PrinterStateInfo(0x62, "Busy", "Copying", "Black", "复印中"),
        PrinterStateInfo(0x63, "Busy", "Copy Cancelling", "Black", "正在取消复印"),
        PrinterStateInfo(0x64, "Busy", "ID Card Mode", "Black", "证件复印模式"),
        PrinterStateInfo(0x6A, "Busy", "Scanning", "Black", "扫描中"),
        PrinterStateInfo(0x6B, "Busy", "Scanning", "Black", "扫描中"),
        PrinterStateInfo(0x6C, "Busy", "Scan Cancelling", "Black", "正在取消扫描"),
        PrinterStateInfo(0x6D, "Busy", "Scanner Busy", "Black", "扫描器忙"),
        PrinterStateInfo(0x85, "Busy", "Waiting 2nd pages when print manual duplex job", "Black", "手动双面: 请将纸放回纸盒待印背面"),
        PrinterStateInfo(0x86, "Ready", "OPC Near End", "Orange", "硒鼓(OPC)将尽"),
        PrinterStateInfo(0xC7, "Busy", "FW Updating", "Black", "固件升级中, 请勿断电"),
        PrinterStateInfo(0xC8, "Busy", "Overheat", "Orange", "打印机过热, 请稍候"),
        PrinterStateInfo(0xBC, "Error", "Paper Jam: Paper Remained", "Red", "卡纸: 机内有残留纸张, 请取出"),
        PrinterStateInfo(0xBD, "Error", "Paper Jam: Nofeed", "Red", "卡纸: 未进纸, 请检查纸盒"),
        PrinterStateInfo(0xBE, "Error", "Paper Jam: Regist", "Red", "卡纸: 对位区卡纸, 请取出"),
        PrinterStateInfo(0xBF, "Error", "Paper Jam: Inner", "Red", "卡纸: 内部卡纸, 请取出"),
        PrinterStateInfo(0xC0, "Error", "Paper Jam: Exit", "Red", "卡纸: 出纸口卡纸, 请取出"),
        PrinterStateInfo(0xC1, "Error", "Cover Open", "Red", "盖板未关, 请合上"),
        PrinterStateInfo(0xC5, "Error", "No Toner Cartridge", "Red", "未安装墨粉盒"),
        PrinterStateInfo(0xC6, "Error", "Please Replace Toner", "Red", "请更换墨粉盒"),
        PrinterStateInfo(0xCC, "Error", "Please replace a new toner cartridge", "Red", "墨粉尽: 请更换新墨粉盒"),
        PrinterStateInfo(0xCD, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Polygon Motor on timeout error", "Red", "多面镜电机启动超时: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xCE, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Polygon Motor off timeout error", "Red", "多面镜电机停止超时: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xCF, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Polygon Motor lock signal error", "Red", "多面镜电机锁定信号错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xD1, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Beam synchronize error", "Red", "光束同步错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xD2, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Bias leak", "Red", "偏压泄漏: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xD3, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Plate action error", "Red", "显影板动作错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xD4, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Main Motor error", "Red", "主电机错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xD5, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Fan Motor error", "Red", "风扇电机错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xD6, "Error", "Please contact customer support:Error message:Fuser thermistor error", "Red", "定影热敏电阻故障: 请联系售后"),
        PrinterStateInfo(0xD7, "Error", "Please contact customer support:Error message:Fuser reload error", "Red", "定影重载错误: 请联系售后"),
        PrinterStateInfo(0xD8, "Error", "Please contact customer support:Error message:High temperature soft check error", "Red", "高温软检出错误: 请联系售后"),
        PrinterStateInfo(0xD9, "Error", "Please contact customer support:Error message:High temperature hard check error", "Red", "高温硬检出错误: 请联系售后"),
        PrinterStateInfo(0xDA, "Error", "Please contact customer support:Error message:Fuser full heater error", "Red", "定影加热器故障: 请联系售后"),
        PrinterStateInfo(0xDB, "Error", "Please contact customer support:Error message:Fuser 3times jam error", "Red", "定影连续卡纸: 请联系售后"),
        PrinterStateInfo(0xDC, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Low voltage fuser reload error", "Red", "低压定影重载错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xDD, "Error", "Please contact customer support:Error message:Machine thermistor error", "Red", "机体热敏电阻错误: 请联系售后"),
        PrinterStateInfo(0xDE, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated", "Red", "内部错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xDF, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Print request signal does not come", "Red", "打印请求信号异常: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xE5, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Scanner not found home position", "Red", "扫描器未找到归位位置: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xE8, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Wireless Dongle Config Fail", "Red", "无线模块配置失败: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xE9, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Scan Calibration Error", "Red", "扫描校准错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0xEF, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:DMA Error", "Red", "DMA错误: 请关机重启, 反复出现请联系售后"),
        PrinterStateInfo(0x17F, "Ready", "Please replace a new toner cartridge and click the＂Reset Toner Cartridge” button of Advanced Settings", "Orange", "请更换新墨粉盒, 并在高级设置中点＂重置墨粉＂"),
        PrinterStateInfo(0x180, "Ready", "Please replace a new toner cartridge and click the＂Reset Toner Cartridge” button of Advanced Settings", "Orange", "请更换新墨粉盒, 并在高级设置中点＂重置墨粉＂"),
        PrinterStateInfo(0x1CC, "Error", "Please replace a new drum cartridge and click the＂Reset Drum Cartridge” button of Advanced Settings", "Red", "请更换新硒鼓, 并在高级设置中点＂重置硒鼓＂"),
    )

    /// 状态页用词 → 状态码 别名(状态页 Device Status 文本与表内 message 不一致时)
    private val aliases = mapOf(
        "power saving" to 0x02,   // 状态页休眠显示
        "sleep" to 0x02,
        "warming up" to 0x03,     // 带空格变体
        "idle" to 0x00
    )

    fun lookup(code: Int): PrinterStateInfo? = ALL.find { it.code == code }
    fun lookupOrUnknown(code: Int): PrinterStateInfo = lookup(code) ?:
        PrinterStateInfo(code, "Unknown", "Unknown state 0x%02x".format(code), "Black", "未知状态 0x%02x".format(code))

    /** 状态页 Device Status 文本 → 状态项: 精确匹配message → 别名 → 前缀匹配(状态页只给类别短语如"Paper Jam"), 未识别返回携带原文的Unknown */
    fun fromStatusText(text: String): PrinterStateInfo {
        val t = text.trim()
        ALL.firstOrNull { it.message.equals(t, ignoreCase = true) }?.let { return it }
        aliases[t.lowercase()]?.let { return lookupOrUnknown(it) }
        ALL.firstOrNull { t.isNotEmpty() && it.message.startsWith(t, ignoreCase = true) }?.let { return it }
        return PrinterStateInfo(-1, "Unknown", t, "Black", "未知状态: $t")
    }
}
