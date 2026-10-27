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
*LanguageEncoding: ISOLatin1
*PSVersion: "(3010.000) 0"
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
*OpenUI *ColorModel/Color Mode: PickOne
*OrderDependency: 10 AnySetup *ColorModel
*DefaultColorModel: Gray
*ColorModel Gray/Grayscale: "<</cupsColorSpace 0/cupsBitsPerColor 8/cupsColorOrder 0>>setpagedevice"
*CloseUI: *ColorModel
*OpenUI *Resolution/Resolution: PickOne
*OrderDependency: 20 AnySetup *Resolution
*DefaultResolution: 600dpi
*Resolution 600dpi/600 dpi: "<</HWResolution[600 600]>>setpagedevice"
*CloseUI: *Resolution
*OpenUI *PageSize/Media Size: PickOne
*OrderDependency: 30 AnySetup *PageSize
*DefaultPageSize: A4
*PageSize A4/A4: "<</PageSize[595 842]/ImagingBBox null>>setpagedevice"
*PageSize Letter/US Letter: "<</PageSize[612 792]/ImagingBBox null>>setpagedevice"
*CloseUI: *PageSize
*DefaultImageableArea: A4
*ImageableArea A4: "18 14 577 828"
*ImageableArea Letter: "18 14 594 778"
*DefaultPaperDimension: A4
*PaperDimension A4: "595 842"
*PaperDimension Letter: "612 792"
*OpenUI *PageRegion/Page Region: PickOne
*OrderDependency: 35 AnySetup *PageRegion
*DefaultPageRegion: A4
*PageRegion A4/A4: "<</PageSize[595 842]/ImagingBBox null>>setpagedevice"
*PageRegion Letter/US Letter: "<</PageSize[612 792]/ImagingBBox null>>setpagedevice"
*CloseUI: *PageRegion
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
*OpenUI *Density/Density: PickOne
*OrderDependency: 60 AnySetup *Density
*DefaultDensity: 0
*Density -3/Lightest (-3): ""
*Density -2/-2: ""
*Density -1/-1: ""
*Density 0/Normal (0): ""
*Density 1/1: ""
*Density 2/2: ""
*Density 3/Darkest (3): ""
*CloseUI: *Density
*OpenUI *TonerSave/Toner Save: Boolean
*OrderDependency: 70 AnySetup *TonerSave
*DefaultTonerSave: False
*TonerSave True/On: ""
*TonerSave False/Off: ""
*CloseUI: *TonerSave
*OpenUI *Nup/N-up: PickOne
*OrderDependency: 80 AnySetup *Nup
*DefaultNup: 1
*Nup 1/Off: ""
*Nup 2/2-up (1x2 portrait): ""
*Nup 4/4-up (2x2): ""
*Nup 9/9-up (3x3): ""
*Nup 16/16-up (4x4): ""
*CloseUI: *Nup
*DefaultOutputOrder: Normal
PPD
chown root:_lp "/etc/cups/ppd/${Q}.ppd"; chmod 644 "/etc/cups/ppd/${Q}.ppd"
# 建队列
lpadmin -p "$Q" -v "socket://${PRINTER_IP:=192.168.1.100}:9100" -P "/etc/cups/ppd/${Q}.ppd" -D "Lenovo M7208W" -o printer-error-policy=retry-job
cupsenable "$Q"
cupsaccept "$Q"
killall -HUP cupsd 2>/dev/null || true
sleep 2
echo "ULTIMATE_DONE"
