package com.zml.m7208w

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

// M7208W Studio v6.0 — 按原厂VOP App架构复刻: 顶部4-Tab(打印/复印/扫描/设置)+底部打印机状态条
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statusLight: View
    private lateinit var tonerLabel: TextView
    private lateinit var tonerBar: ProgressBar
    private lateinit var ipEdit: EditText
    private lateinit var progress: ProgressBar
    private lateinit var scanBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var preview: ImageView
    private val options = ScanOptions()

    @Volatile private var deviceBusy = false
    private var lastBitmap: Bitmap? = null

    // 打印状态
    private var printPages: List<Bitmap> = emptyList()
    private var printPageIdx = 0
    private var printCopies = 1
    private var pendingCertFront: Bitmap? = null

    // WiFi扫描结果缓存(设置页点击填入SSID)
    private var scannedNets: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 全面屏适配(targetSdk35强制边到边): 顶部让开状态栏, 底部让开手势条/导航栏
        val root = findViewById<android.view.View>(R.id.rootLayout)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                android.view.WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets.consumeSystemWindowInsets()
            }
        }

        statusText = findViewById(R.id.statusText)
        statusLight = findViewById(R.id.statusLight)
        tonerLabel = findViewById(R.id.tonerLabel)
        tonerBar = findViewById(R.id.tonerBar)
        progress = findViewById(R.id.progress)
        scanBtn = findViewById(R.id.scanBtn)
        saveBtn = findViewById(R.id.saveBtn)
        preview = findViewById(R.id.preview)
        ipEdit = findViewById(R.id.ipEdit)

        val prefs = getPreferences(MODE_PRIVATE)
        ipEdit.setText(prefs.getString("ip", ""))
        findViewById<Button>(R.id.findBtn).setOnClickListener { findPrinter() }

        setupTabs()
        setupPrintPage()
        setupCopyPage()
        setupScanPage()
        setupSettingsPage()

        saveBtn.isEnabled = false
        if (ipEdit.text.isBlank()) findPrinter() else refreshStatus()
    }


    /// 大图降采样解码(边>maxSide按2的幂降, 防多页打印OOM; 审查M2缓解)
    private fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? {
        return try {
            val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opt) }
            var sample = 1
            while (opt.outWidth / sample > maxSide || opt.outHeight / sample > maxSide) sample *= 2
            val d = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, d) }
        } catch (e: Exception) { null }
    }

    private fun ip(): String = ipEdit.text.toString().trim().ifEmpty { "192.168.1.100" }

    /// 任务锁统一入口: 置位+底部指示灯 (10-19: 原来裸写deviceBusy无任何可见状态)
    private fun busy(on: Boolean) {
        deviceBusy = on
        runOnUiThread {
            f<TextView>(R.id.busyText).visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    /// 轮询打印机真实状态, 干完活才解锁 (替代写死的90秒锁——打印早结束也白锁一分半)
    /// 忙=Printing/Copying/Busy/Warming; Ready/Power Saving=完成; 超时5分钟兜底解锁
    private fun unlockWhenIdle() {
        val target = ip()
        Thread {
            Thread.sleep(10_000)   // 给任务起飞
            val deadline = System.currentTimeMillis() + 290_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5_000)
                val st = StatusPage.deviceStatus(target)
                if (st == null) continue                    // 查询失败继续等
                val busyNow = st.contains("Print", true) || st.contains("Copy", true)
                        || st.contains("Warm", true) || st.contains("Busy", true)
                if (!busyNow) break
            }
            busy(false)
        }.start()
    }

    // ==================== Tab 切换 ====================
    private fun setupTabs() {
        val pages: Map<Int, View> = mapOf(
            R.id.tabPrint to findViewById<View>(R.id.pagePrint),
            R.id.tabCopy to findViewById<View>(R.id.pageCopy),
            R.id.tabScan to findViewById<View>(R.id.pageScan),
            R.id.tabSettings to findViewById<View>(R.id.pageSettings)
        )
        val group = findViewById<android.widget.RadioGroup>(R.id.tabBar)
        group.setOnCheckedChangeListener { _, checkedId ->
            for ((tab, page) in pages) page.visibility = if (tab == checkedId) View.VISIBLE else View.GONE
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun <T : View> f(id: Int): T = findViewById(id)

    // ==================== 打印页 ====================
    private fun setupPrintPage() {
        f<Spinner>(R.id.ppSpinner).adapter = adapter(PrintEngine.PrintPaper.values().map { it.label })
        f<Spinner>(R.id.mediaPSpinner).adapter = adapter(PrintEngine.PrintMedia.values().map { it.label })
        f<Spinner>(R.id.nupPSpinner).adapter = adapter(listOf("1合1", "2合1", "4合1", "9合1", "16合1"))
        f<Spinner>(R.id.scalePSpinner).adapter =
            adapter(listOf("缩放 100%", "缩放 50%", "缩放 75%", "缩放 150%", "缩放 200%", "适应纸面"))

        // 6入口(对齐原版: 照片/PDF/TXT/证件/Office/其他)
        f<Button>(R.id.entryPhoto).setOnClickListener { pick("image/*", 100, "选择照片") }
        f<Button>(R.id.entryPdf).setOnClickListener { pick("application/pdf", 101, "选择PDF") }
        f<Button>(R.id.entryTxt).setOnClickListener { pick("text/*", 102, "选择文本") }
        f<Button>(R.id.entryCert).setOnClickListener {
            toast("先选证件正面照片")
            pick("image/*", 104, "证件正面")
        }
        f<Button>(R.id.entryOffice).setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(i, "选择Office文档"), 105)
        }
        f<Button>(R.id.entryAny).setOnClickListener {
            pick("*/*", 103, "选择文件")
        }

        // 预览翻页 + 份数±
        f<Button>(R.id.prevPage).setOnClickListener { showPage(printPageIdx - 1) }
        f<Button>(R.id.nextPage).setOnClickListener { showPage(printPageIdx + 1) }
        f<Button>(R.id.copyMinus).setOnClickListener { setCopies(printCopies - 1) }
        f<Button>(R.id.copyPlus).setOnClickListener { setCopies(printCopies + 1) }

        f<Button>(R.id.printGoBtn).setOnClickListener { goPrint() }
    }

    private fun pick(mime: String, code: Int, title: String) {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, title), code)
    }

    private fun setCopies(n: Int) {
        printCopies = n.coerceIn(1, 99)
        f<TextView>(R.id.copyCount).text = printCopies.toString()
    }

    private fun showPage(idx: Int) {
        if (printPages.isEmpty()) return
        printPageIdx = idx.coerceIn(0, printPages.size - 1)
        f<ImageView>(R.id.printPreview).setImageBitmap(printPages[printPageIdx])
        f<TextView>(R.id.pageIndicator).text = "${printPageIdx + 1}/${printPages.size}"
    }

    private fun loadPrintPages(uri: Uri, name: String) {
        statusText.text = "解析文件…"
        Thread {
            try {
                val mime = contentResolver.getType(uri) ?: ""
                val lower = name.lowercase()
                val pages: List<Bitmap> = when {
                    mime == "application/pdf" || lower.endsWith(".pdf") ->
                        PrintEngine.pdfToBitmaps(contentResolver, uri)
                    mime.startsWith("text/") || lower.endsWith(".txt") ->
                        PrintEngine.txtToBitmaps(contentResolver, uri)
                    mime.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ->
                        listOfNotNull(decodeSampled(uri, 3200))
                    else -> listOfNotNull(runCatching { MediaStore.Images.Media.getBitmap(contentResolver, uri) }.getOrNull())
                }
                runOnUiThread {
                    printPages = pages.filterNotNull()
                    f<android.widget.LinearLayout>(R.id.previewCard).visibility = View.VISIBLE
                    f<TextView>(R.id.previewTitle).text = name.substringAfterLast('/') +
                        if (pages.size > 1) " (${pages.size}页)" else ""
                    showPage(0)
                    setCopies(1)
                    statusText.text = "已载入 ${pages.size} 页, 可翻页预览与设置"
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "❌ 解析失败: ${e.message}" }
            }
        }.start()
    }

    private fun goPrint() {
        if (printPages.isEmpty()) { toast("先选择要打印的内容"); return }
        if (deviceBusy) { statusText.text = "⏳ 打印机正忙, 请等当前任务完成"; return }
        val paper = PrintEngine.PrintPaper.values().getOrNull(f<Spinner>(R.id.ppSpinner).selectedItemPosition)
            ?: PrintEngine.PrintPaper.A4
        val media = PrintEngine.PrintMedia.values().getOrNull(f<Spinner>(R.id.mediaPSpinner).selectedItemPosition)
            ?: PrintEngine.PrintMedia.PLAIN
        val nup = listOf(1, 2, 4, 9, 16)[f<Spinner>(R.id.nupPSpinner).selectedItemPosition]
        val scale = when (f<Spinner>(R.id.scalePSpinner).selectedItemPosition) {
            1 -> 50; 2 -> 75; 3 -> 150; 4 -> 200; else -> 100
        }
        val density = f<SeekBar>(R.id.densityPSeek).progress - 3
        val tonerSave = f<CheckBox>(R.id.tonerSaveChk).isChecked
        busy(true)
        statusText.text = "打印处理中… (${printPages.size}页×${printCopies}份)"
        Thread {
            try {
                val ok = PrintEngine.printJob(ip(), printPages, paper, media, nup, scale, density, tonerSave, printCopies)
                runOnUiThread {
                    if (ok) {
                        statusText.text = "✅ 已发送打印 — 完成前请勿扫描/复印"
                        unlockWhenIdle()   // 轮询真实状态解锁(替代90秒死锁)
                    } else {
                        busy(false)
                        statusText.text = "❌ 打印失败(检查IP/打印机状态)"
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { busy(false); statusText.text = "❌ ${e.message}" }
            }
        }.start()
    }

    // ==================== 复印页 ====================
    private fun setupCopyPage() {
        var copies = 1
        f<Button>(R.id.copyMinus2).setOnClickListener {
            copies = (copies - 1).coerceAtLeast(1); f<TextView>(R.id.copyCount2).text = copies.toString() }
        f<Button>(R.id.copyPlus2).setOnClickListener {
            copies = (copies + 1).coerceAtMost(99); f<TextView>(R.id.copyCount2).text = copies.toString() }
        f<Spinner>(R.id.copyDpiSpinner).adapter = adapter(CopyDpi.values().map { it.label })
        f<Spinner>(R.id.srcPaperSpinner).adapter = adapter(CopyPaper.values().map { it.label })
        f<Spinner>(R.id.dstPaperSpinner).adapter = adapter(CopyPaper.values().map { it.label })
        f<Spinner>(R.id.mediaSpinner).adapter = adapter(CopyMedia.values().map { it.label })
        f<Spinner>(R.id.nupSpinner).adapter = adapter(NupMode.values().map { it.label })
        f<Spinner>(R.id.copyModeSpinner).adapter = adapter(listOf("文本", "照片"))
        f<Button>(R.id.copyGoBtn).setOnClickListener {
            val scale = f<EditText>(R.id.scaleEdit).text.toString().toIntOrNull() ?: 100
            val dpi = CopyDpi.values().getOrNull(f<Spinner>(R.id.copyDpiSpinner).selectedItemPosition) ?: CopyDpi.D300
            val src = CopyPaper.values().getOrNull(f<Spinner>(R.id.srcPaperSpinner).selectedItemPosition) ?: CopyPaper.A4
            val dst = CopyPaper.values().getOrNull(f<Spinner>(R.id.dstPaperSpinner).selectedItemPosition) ?: CopyPaper.A4
            val media = CopyMedia.values().getOrNull(f<Spinner>(R.id.mediaSpinner).selectedItemPosition) ?: CopyMedia.PLAIN
            val nupSel = f<Spinner>(R.id.nupSpinner).selectedItemPosition
            // 证件复印复选框 → 身份证模式(两步扫描在打印机侧自动完成)
            val nup = if (f<CheckBox>(R.id.certCopyChk).isChecked) NupMode.ID_CARD
                      else NupMode.values().getOrNull(nupSel) ?: NupMode.OFF
            val textMode = f<Spinner>(R.id.copyModeSpinner).selectedItemPosition == 0
            if (deviceBusy) { statusText.text = "⏳ 打印机正忙, 请等当前任务完成"; return@setOnClickListener }
            busy(true)
            val o = CopyOptions(copies = copies, scale = scale.coerceIn(25, 400), dpi = dpi,
                src = src, dst = dst, nup = nup, media = media, textMode = textMode,
                density = f<SeekBar>(R.id.copyDensitySeek).progress)
            if (nup == NupMode.ID_CARD) toast("证件复印: 先放正面按开始, 提示后翻面再按一次")
            statusText.text = "复印命令发送中…"
            Thread {
                try {
                    val ok = CopyEngine.run(ip(), o)
                    runOnUiThread {
                        if (ok) {
                            statusText.text = "✅ 复印已受理 (${o.copies}份) — 完成前请勿扫描"
                            unlockWhenIdle()   // 轮询真实状态解锁(替代90秒死锁)
                        } else { busy(false); statusText.text = "❌ 复印被拒绝" }
                    }
                } catch (e: Throwable) {
                    runOnUiThread { busy(false); statusText.text = "❌ ${e.message}" }
                }
            }.start()
        }
    }

    // ==================== 扫描页 ====================
    private fun setupScanPage() {
        f<Spinner>(R.id.paperSpinner).adapter = adapter(PaperSize.values().map { it.label })
        f<Spinner>(R.id.modeSpinner).adapter = adapter(ScanMode.values().map { it.label })
        f<Spinner>(R.id.dpiSpinner).adapter = adapter(listOf("300 DPI", "600 DPI"))
        f<Spinner>(R.id.scanFmtSpinner).adapter = adapter(listOf("PNG", "JPG"))
        f<SeekBar>(R.id.densitySeek).setOnSeekBarChangeListener(simpleSeek(R.id.densityLabel, "浓度", 127))
        f<SeekBar>(R.id.brightSeek).setOnSeekBarChangeListener(simpleSeek(R.id.brightLabel, "亮度", 127))
        f<SeekBar>(R.id.contrastSeek).setOnSeekBarChangeListener(simpleSeek(R.id.contrastLabel, "对比度", 127))
        scanBtn.setOnClickListener { runScan() }
        saveBtn.setOnClickListener { saveGallery(f<Spinner>(R.id.scanFmtSpinner).selectedItemPosition == 1) }
        f<Button>(R.id.addPdfBtn).setOnClickListener {
            lastBitmap?.let { pdfQueue.add(it); statusText.text = "已加入PDF队列 (${pdfQueue.size}页)" }
        }
        f<Button>(R.id.exportPdfBtn).setOnClickListener { Thread { exportPdf() }.start() }
        f<Button>(R.id.shareScanBtn).setOnClickListener { shareScan() }
    }

    private fun simpleSeek(labelId: Int, name: String, mid: Int) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
            f<TextView>(labelId).text = "$name: ${v - mid}"
            when (name) {
                "浓度" -> options.density = v - mid
                "亮度" -> options.brightness = v - mid
                else -> options.contrast = v - mid
            }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun runScan() {
        options.paper = PaperSize.values()[f<Spinner>(R.id.paperSpinner).selectedItemPosition]
        options.mode = ScanMode.values()[f<Spinner>(R.id.modeSpinner).selectedItemPosition]
        options.dpi = listOf(300, 600)[f<Spinner>(R.id.dpiSpinner).selectedItemPosition]
        getPreferences(MODE_PRIVATE).edit().putString("ip", ip()).apply()
        if (deviceBusy) { statusText.text = "⏳ 打印机正忙, 请等当前任务完成"; return }
        busy(true)
        scanBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = "扫描中…"
        lastLiveRows = 0
        val target = ip()
        Thread {
            try {
                val session = ScanSession(target)
                var result: Pair<ByteArray, Int>? = null
                try {
                    result = session.scan(options) { n, rawChunk ->
                        runOnUiThread { statusText.text = "扫描中… ${n / 1024} KB · ${n / 2496} 行" }
                        livePreview(rawChunk)
                    }
                } catch (e: ScanException) {
                    if (e.kind == ScanException.Kind.DEAD) {
                        runOnUiThread { statusText.text = "扫描服务休眠(打印后固病), 远程重启打印机…约3分钟" }
                        if (!PrinterControl.reboot(target) || !waitReboot(target)) throw ScanException(ScanException.Kind.DEAD)
                        runOnUiThread { statusText.text = "打印机已恢复, 重新扫描…" }
                        result = session.scan(options) { n, rawChunk ->
                            runOnUiThread { statusText.text = "扫描中… ${n / 1024} KB" }
                            livePreview(rawChunk)
                        }
                    } else throw e
                }
                val (raw, width) = result!!
                val processed = Decoder.postProcess(raw, width, options.mode, options.density)
                val bmp = toBitmap(processed, width)
                lastBitmap = bmp
                lastSavedUri = null   // 新扫描作废旧分享目标(审查M6)
                runOnUiThread {
                    busy(false)
                    preview.setImageBitmap(bmp)
                    preview.visibility = View.VISIBLE
                    statusText.text = "扫描完成 (${width}×${processed.size / width})"
                    scanBtn.isEnabled = true
                    saveBtn.isEnabled = true
                    progress.visibility = View.GONE
                }
            } catch (e: Throwable) {   // 含OOM等Error, 防deviceBusy永久锁死(审查M1)
                runOnUiThread {
                    busy(false)
                    statusText.text = "失败: ${friendly(e)}"
                    scanBtn.isEnabled = true
                    saveBtn.isEnabled = lastBitmap != null
                    progress.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun waitReboot(ip: String, maxSec: Int = 300): Boolean {
        val deadline = System.currentTimeMillis() + maxSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (PrinterControl.isAlive(ip)) { Thread.sleep(20000); return true }
            Thread.sleep(5000)
        }
        return false
    }

    private fun friendly(e: Throwable): String = when (e) {
        is ScanException -> when (e.kind) {
            ScanException.Kind.BUSY -> "打印机忙, 稍候重试"
            ScanException.Kind.REFUSED -> "无法连接扫描服务, 检查IP或重启打印机"
            ScanException.Kind.TIMEOUT -> "等待数据超时"
            ScanException.Kind.DEAD -> "扫描服务休眠且远程重启失败, 请手动重启打印机"
        }
        else -> e.message ?: "未知错误"
    }

    private fun toBitmap(px: ByteArray, width: Int): Bitmap {
        val h = px.size / width
        val colors = IntArray(width * h)
        for (i in colors.indices) {
            val v = px[i].toInt() and 0xFF
            colors[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(colors, width, h, Bitmap.Config.ARGB_8888)
    }

    private var lastLiveRows = 0
    private fun livePreview(raw: ByteArray) {
        val (px, w) = Decoder.extract(raw, 0, 300)
        val rows = px.size / w
        if (rows - lastLiveRows < 300 || rows < 20) return
        lastLiveRows = rows
        try {
            val bmp = toBitmap(px.copyOf(rows * w), w)
            runOnUiThread { preview.setImageBitmap(bmp); preview.visibility = View.VISIBLE }
        } catch (_: Exception) {}
    }

    private fun saveGallery(jpg: Boolean): Boolean {
        val bmp = lastBitmap ?: return false
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,
                    "M7208W_扫_${System.currentTimeMillis()}.${if (jpg) "jpg" else "png"}")
                put(MediaStore.Images.Media.MIME_TYPE, if (jpg) "image/jpeg" else "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/M7208W扫描")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("无法创建相册条目")
            contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, out)
            }
            lastSavedUri = uri
            toast("已保存到 相册/M7208W扫描")
            return true
        } catch (e: Exception) {
            try {
                val dir = java.io.File(getExternalFilesDir(null), "scans").apply { mkdirs() }
                java.io.File(dir, "${System.currentTimeMillis()}.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                toast("已保存 ${dir.path}")
            } catch (_: Exception) {}
        }
        return false
    }

    private var lastSavedUri: Uri? = null
    private fun shareScan() {
        val bmp = lastBitmap ?: run { toast("先扫描一页"); return }
        if (lastSavedUri == null) {
            if (!saveGallery(f<Spinner>(R.id.scanFmtSpinner).selectedItemPosition == 1)) { toast("保存失败"); return }
        }
        val send = Intent(Intent.ACTION_SEND)
        send.type = "image/*"
        send.putExtra(Intent.EXTRA_STREAM, lastSavedUri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "分享扫描件"))
    }

    private val pdfQueue = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())
    private fun exportPdf() {
        if (pdfQueue.isEmpty()) return
        try {
            val doc = android.graphics.pdf.PdfDocument()
            for ((idx, bmp) in pdfQueue.withIndex()) {
                val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, idx + 1).create()
                val page = doc.startPage(info)
                val ratio = minOf(595f / bmp.width, 842f / bmp.height)
                val w = bmp.width * ratio; val h = bmp.height * ratio
                page.canvas.drawBitmap(bmp, null,
                    android.graphics.RectF((595f - w) / 2, (842f - h) / 2, (595f + w) / 2, (842f + h) / 2), null)
                doc.finishPage(page)
            }
            val cv = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "M7208W_PDF_${System.currentTimeMillis()}.pdf")
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/M7208W扫描")
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
                ?: throw IllegalStateException()
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close(); pdfQueue.clear()
            runOnUiThread { statusText.text = "✅ PDF已导出到 文档/M7208W扫描" }
        } catch (e: Exception) {
            runOnUiThread { statusText.text = "PDF导出失败: ${e.message}" }
        }
    }

    // ==================== 设置页 ====================
    private fun setupSettingsPage() {
        f<Spinner>(R.id.wifiEncSpinner).adapter = adapter(listOf("WPA2", "WEP64", "WEP128", "开放"))
        f<Button>(R.id.wifiReadBtn).setOnClickListener {
            statusText.text = "读取打印机WiFi…"
            Thread {
                val r = WiFiReader.read(ip())
                runOnUiThread {
                    f<TextView>(R.id.wifiInfoText).text = if (r != null)
                        "当前: ${r.first}  密码: ${r.second}" else "读取失败"
                }
            }.start()
        }
        f<Button>(R.id.wifiScanBtn).setOnClickListener {
            statusText.text = "扫描周围WiFi… (~3s)"
            Thread {
                val nets = WiFiScanner.scan(ip())
                runOnUiThread {
                    if (nets == null) { statusText.text = "扫描失败"; return@runOnUiThread }
                    scannedNets = nets
                    android.app.AlertDialog.Builder(this)
                        .setTitle("发现 ${nets.size} 个网络 (点击填入)")
                        .setItems(nets.toTypedArray()) { _, which ->
                            f<EditText>(R.id.newSSID).setText(nets[which])
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }.start()
        }
        f<Button>(R.id.wifiWriteBtn).setOnClickListener {
            val ssid = f<EditText>(R.id.newSSID).text.toString().trim()
            val pass = f<EditText>(R.id.newPass).text.toString()
            if (ssid.isEmpty()) { toast("填SSID"); return@setOnClickListener }
            android.app.AlertDialog.Builder(this)
                .setTitle("写入WiFi配置")
                .setMessage("打印机将连接 \"$ssid\" 并重启, IP可能变化。写错需USB线重配。确认?")
                .setPositiveButton("确认写入") { _, _ ->
                    Thread {
                        val enc = listOf(3, 1, 2, 0)[f<Spinner>(R.id.wifiEncSpinner).selectedItemPosition]
                        val ok = WiFiWriter.write(ip(), ssid, pass, enc)
                        runOnUiThread { statusText.text = if (ok) "✅ 已写入, 打印机重启中(约3分钟)" else "❌ 写入被拒" }
                    }.start()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        f<Button>(R.id.tonerReadBtn).setOnClickListener { tonerAction(0) }
        f<Button>(R.id.tonerContBtn).setOnClickListener { tonerAction(1) }
        f<Button>(R.id.tonerResetBtn).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("重置墨粉计数")
                .setMessage("重置后打印机将按新硒鼓计数。确认执行?")
                .setPositiveButton("重置") { _, _ -> tonerAction(2) }
                .setNegativeButton("取消", null).show()
        }
        f<Button>(R.id.rebootBtn).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("重启打印机")
                .setMessage("约3分钟恢复(可救活打印后扫描死)。确认?")
                .setPositiveButton("重启") { _, _ ->
                    Thread {
                        val ok = PrinterControl.reboot(ip())
                        runOnUiThread { statusText.text = if (ok) "重启指令已发, 约3分钟" else "❌ 发送失败" }
                    }.start()
                }
                .setNegativeButton("取消", null).show()
        }
        f<Button>(R.id.refreshBtn).setOnClickListener { refreshStatus() }
        // 关于区: 运行时读真实版本号(10-19用户要求, 版本不再写死)
        val ver = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName}"
        } catch (_: Exception) { "" }
        f<TextView>(R.id.aboutText).text = "M7208W Studio $ver · arm64原生 · 独立复刻\n打印协议: LHPL逆向 · 扫描: 53010/53011 · 非联想官方"
    }

    private fun tonerAction(which: Int) {
        val ip2 = ip()
        Thread {
            val msg = when (which) {
                0 -> {
                    val ts = TonerStatus.fetch(ip2)
                    val endSt = TonerEndControl.get(ip2)
                    if (ts != null) "墨粉: ${ts.first}%  (已印${ts.second}/${ts.third}页)\n开关状态码: $endSt (02=未耗尽/锁定)"
                    else "状态码: ${endSt ?: "读取失败"}"
                }
                1 -> if (TonerEndControl.set(ip2, true)) "✅ 已开启(尽粉后可继续印)" else "❌ 被拒(墨粉未尽时锁定)"
                else -> { TonerEndControl.resetCounter(ip2); "重置命令已发" }
            }
            runOnUiThread { statusText.text = msg }
        }.start()
    }

    // ==================== 状态/发现 ====================
    private fun setLight(color: Int) {
        (statusLight.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
    }

    private fun refreshStatus() {
        statusText.text = "检查打印机…"
        val target = ip()
        getPreferences(MODE_PRIVATE).edit().putString("ip", target).apply()
        Thread {
            val alive = PrinterControl.isAlive(target)
            var toner: Triple<Int, Int, Int>? = null
            if (alive) toner = TonerStatus.fetch(target)
            runOnUiThread {
                statusText.text = if (alive) "● 在线 $target" else "● 离线 $target"
                statusText.setTextColor(if (alive) Color.parseColor("#1a7f37") else Color.RED)
                setLight(if (alive) Color.parseColor("#22c55e") else Color.parseColor("#ef4444"))
                if (toner != null) {
                    tonerLabel.text = "墨粉 ${toner.first}%"
                    tonerBar.progress = toner.first
                } else { tonerLabel.text = "墨粉 --"; tonerBar.progress = 0 }
            }
        }.start()
    }

    private fun findPrinter() {
        statusText.text = "🔍 扫描局域网找打印机…"
        Thread {
            var hit: String? = null
            for (cand in PrinterDiscovery.discover(timeoutMs = 2500)) {
                if (PrinterDiscovery.probe(cand)) { hit = cand; break }
            }
            runOnUiThread {
                if (hit != null) {
                    ipEdit.setText(hit)
                    statusText.text = "✅ 找到打印机 $hit"
                    refreshStatus()
                } else statusText.text = "❌ 没找到(确认手机和打印机同WiFi)"
            }
        }.start()
    }

    // ==================== 文件选择回调 ====================
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        val name = uri.toString()
        when (requestCode) {
            100, 101, 102, 103, 105 -> loadPrintPages(uri, name)
            104 -> { // 证件正面 → 继续选反面
                try {
                    pendingCertFront = decodeSampled(uri, 2000)
                    toast("再选反面照片")
                    pick("image/*", 106, "证件反面")
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
            106 -> { // 证件反面 → 合成
                try {
                    val back = decodeSampled(uri, 2000)
                    val front = pendingCertFront
                    if (front != null && back != null) {
                        printPages = listOf(PrintEngine.composeCertificate(front, back))
                        pendingCertFront = null   // 用完即清, 防残留串台(审查L)
                        f<android.widget.LinearLayout>(R.id.previewCard).visibility = View.VISIBLE
                        f<TextView>(R.id.previewTitle).text = "证件打印 (正反合成)"
                        showPage(0); setCopies(1)
                        statusText.text = "证件已合成, 可直接打印"
                    }
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
        }
    }
}
