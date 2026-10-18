#!/bin/bash
set -e
Q="M7208W"
lpadmin -x "$Q" 2>/dev/null || true
# 直接用完整自制PPD(这次带全部CUPS光栅必需字段, 参考已验证的Intel PPD字段)
cat > "/etc/cups/ppd/${Q}.ppd" <<'PPD'
*PPD-Adobe: "4.3"
*FileVersion: "1.0"
*FormatVersion: "4.3"
*LanguageVersion: English
*Manufacturer: "Lenovo"
*ModelName: "Lenovo M7208W"
*ShortNickName: "Lenovo M7208W"
*NickName: "Lenovo M7208W LHPL v2 (ARM)"
*PCFileName: "LNVM7208.PPD"
*Product: "(Lenovo M7208W)"
*cupsVersion: 1.4
*cupsManualCopies: True
*cupsFilter: "application/vnd.cups-raster 0 LNTHR1Z"
*ColorDevice: False
*DefaultColorSpace: Gray
*CUPSBytesPerPixel: 1
*OpenUI *ColorModel/Color Mode: PickOne
*OrderDependency: 10 AnySetup *ColorModel
*DefaultColorModel: Gray
*ColorModel Gray/Grayscale: "<</cupsColorSpace 0/cupsBitsPerColor 8/cupsColorOrder 0>>setpagedevice"
*CloseUI: *ColorModel
*OpenUI *Resolution/Resolution: PickOne
*OrderDependency: 20 AnySetup *Resolution
*DefaultResolution: 600dpi
*Resolution 300dpi/300 dpi: "<</HWResolution[300 300]>>setpagedevice"
*Resolution 600dpi/600 dpi: "<</HWResolution[600 600]>>setpagedevice"
*CloseUI: *Resolution
*OpenUI *PageSize/Media Size: PickOne
*OrderDependency: 30 AnySetup *PageSize
*DefaultPageSize: A4
*PageSize A4/A4: "<</PageSize[595 842]/ImagingBBox null>>setpagedevice"
*PageSize Letter/US Letter: "<</PageSize[612 792]/ImagingBBox null>>setpagedevice"
*CloseUI: *PageSize"
*DefaultImageableArea: A4
*ImageableArea A4: "18 14 577 828"
*ImageableArea Letter: "18 14 594 778"
*DefaultPaperDimension: A4
*PaperDimension A4: "595 842"
*PaperDimension Letter: "612 792"
*OpenUI *InputSlot/Paper Source: PickOne
*OrderDependency: 40 AnySetup *InputSlot
*DefaultInputSlot: Auto
*InputSlot Auto/Auto: ""
*CloseUI: *InputSlot
*OpenUI *MediaType/Media Type: PickOne
*OrderDependency: 50 AnySetup *MediaType
*DefaultMediaType: Plain
*MediaType Plain/Plain Paper: ""
*CloseUI: *MediaType
*OpenUI *Copies/Copies: PickOne
*OrderDependency: 60 AnySetup *Copies
*DefaultCopies: 1
*Copies 1/1: ""
*CloseUI: *Copies
*DefaultOutputOrder: Normal
PPD
# 修PPD里一个手误(引号)
sed -i '' 's|\*CloseUI: \*PageSize"|\*CloseUI: \*PageSize|' "/etc/cups/ppd/${Q}.ppd"
chown root:_lp "/etc/cups/ppd/${Q}.ppd"; chmod 644 "/etc/cups/ppd/${Q}.ppd"
# 建队列
lpadmin -p "$Q" -v "socket://192.168.1.100:9100" -P "/etc/cups/ppd/${Q}.ppd" -D "Lenovo M7208W" -o printer-error-policy=retry-job
cupsenable "$Q"
cupsaccept "$Q"
killall -HUP cupsd 2>/dev/null || true
sleep 2
echo "ULTIMATE_DONE"
