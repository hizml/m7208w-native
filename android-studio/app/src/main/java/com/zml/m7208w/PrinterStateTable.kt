package com.zml.m7208w

/// 原厂打印机状态码全表(从base.apk resources.arsc提取, 2026-10-28)
/// 格式: 0xNN|STATE|Message|Color
data class PrinterStateInfo(
    val code: Int,
    val state: String,      // Ready/Busy/Error
    val message: String,
    val color: String       // Black/Orange/Red
)

object PrinterStateTable {
    val ALL = listOf(
        PrinterStateInfo(0x00, "Ready", "Ready", "Black"),
        PrinterStateInfo(0x7F, "Ready", "Please replace a new toner cartridge", "Orange"),
        PrinterStateInfo(0x80, "Ready", "Please replace a new toner cartridge", "Orange"),
        PrinterStateInfo(0x81, "Ready", "Toner Near End", "Orange"),
        PrinterStateInfo(0xEF, "Ready", "Print Data Error", "Orange"),
        PrinterStateInfo(0x02, "Sleep", "", "Black"),
        PrinterStateInfo(0x01, "Busy", "Printing", "Black"),
        PrinterStateInfo(0x03, "Busy", "WarmingUp", "Black"),
        PrinterStateInfo(0x04, "Busy", "Print Cancelling", "Black"),
        PrinterStateInfo(0x07, "Busy", "Processing", "Black"),
        PrinterStateInfo(0x60, "Busy", "Copying", "Black"),
        PrinterStateInfo(0x61, "Busy", "Place Next Page@Turn card over to copy the reverse.", "Black"),
        PrinterStateInfo(0x62, "Busy", "Copying", "Black"),
        PrinterStateInfo(0x63, "Busy", "Copy Cancelling", "Black"),
        PrinterStateInfo(0x64, "Busy", "ID Card Mode", "Black"),
        PrinterStateInfo(0x6A, "Busy", "Scanning", "Black"),
        PrinterStateInfo(0x6B, "Busy", "Scanning", "Black"),
        PrinterStateInfo(0x6C, "Busy", "Scan Cancelling", "Black"),
        PrinterStateInfo(0x6D, "Busy", "Scanner Busy", "Black"),
        PrinterStateInfo(0x85, "Busy", "Waiting 2nd pages when print manual duplex job", "Black"),
        PrinterStateInfo(0x86, "Ready", "OPC Near End", "Orange"),
        PrinterStateInfo(0xC7, "Busy", "FW Updating", "Black"),
        PrinterStateInfo(0xC8, "Busy", "Overheat", "Orange"),
        PrinterStateInfo(0xBC, "Error", "Paper Jam: Paper Remained", "Red"),
        PrinterStateInfo(0xBD, "Error", "Paper Jam: Nofeed", "Red"),
        PrinterStateInfo(0xBE, "Error", "Paper Jam: Regist", "Red"),
        PrinterStateInfo(0xBF, "Error", "Paper Jam: Inner", "Red"),
        PrinterStateInfo(0xC0, "Error", "Paper Jam: Exit", "Red"),
        PrinterStateInfo(0xC1, "Error", "Cover Open", "Red"),
        PrinterStateInfo(0xC5, "Error", "No Toner Cartridge", "Red"),
        PrinterStateInfo(0xC6, "Error", "Please Replace Toner", "Red"),
        PrinterStateInfo(0xCC, "Error", "Please replace a new toner cartridge", "Red"),
        PrinterStateInfo(0xCD, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Polygon Motor on timeout error", "Red"),
        PrinterStateInfo(0xCE, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Polygon Motor off timeout error", "Red"),
        PrinterStateInfo(0xCF, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Polygon Motor lock signal error", "Red"),
        PrinterStateInfo(0xD1, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Beam synchronize error", "Red"),
        PrinterStateInfo(0xD2, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Bias leak", "Red"),
        PrinterStateInfo(0xD3, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Plate action error", "Red"),
        PrinterStateInfo(0xD4, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Main Motor error", "Red"),
        PrinterStateInfo(0xD5, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Fan Motor error", "Red"),
        PrinterStateInfo(0xD6, "Error", "Please contact customer support:Error message:Fuser thermistor error", "Red"),
        PrinterStateInfo(0xD7, "Error", "Please contact customer support:Error message:Fuser reload error", "Red"),
        PrinterStateInfo(0xD8, "Error", "Please contact customer support:Error message:High temperature soft check error", "Red"),
        PrinterStateInfo(0xD9, "Error", "Please contact customer support:Error message:High temperature hard check error", "Red"),
        PrinterStateInfo(0xDA, "Error", "Please contact customer support:Error message:Fuser full heater error", "Red"),
        PrinterStateInfo(0xDB, "Error", "Please contact customer support:Error message:Fuser 3times jam error", "Red"),
        PrinterStateInfo(0xDC, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Low voltage fuser reload error", "Red"),
        PrinterStateInfo(0xDD, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Machine thermistor error", "Red"),
        PrinterStateInfo(0xDE, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated", "Red"),
        PrinterStateInfo(0xDF, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Print request signal does not come", "Red"),
        PrinterStateInfo(0xE5, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Scanner not found home position", "Red"),
        PrinterStateInfo(0xE8, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Wireless Dongle Config Fail", "Red"),
        PrinterStateInfo(0xE9, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:Scan Calibration Error", "Red"),
        PrinterStateInfo(0xEF, "Error", "Turn off the printer, and turn it on again. Contact customer support if this failure is repeated:Error message:DMA Error", "Red"),
        PrinterStateInfo(0x17F, "Ready", "Please replace a new toner cartridge and click the＂Reset Toner Cartridge” button of Advanced Settings", "Orange"),
        PrinterStateInfo(0x180, "Ready", "Please replace a new toner cartridge and click the＂Reset Toner Cartridge” button of Advanced Settings", "Orange"),
        PrinterStateInfo(0x1CC, "Error", "Please replace a new drum cartridge and click the＂Reset Drum Cartridge” button of Advanced Settings", "Red"),
    )
    
    fun lookup(code: Int): PrinterStateInfo? = ALL.find { it.code == code }
    fun lookupOrUnknown(code: Int): PrinterStateInfo = lookup(code) ?: 
        PrinterStateInfo(code, "Unknown", "Unknown state 0x%02x".format(code), "Black")
}
