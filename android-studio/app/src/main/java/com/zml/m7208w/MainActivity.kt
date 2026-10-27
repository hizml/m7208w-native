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

    // 当前显示的预览位图(新图上屏后回收旧图, 等效原厂rePrintPreviewImage清全部缓存)
    private var previewBitmap: Bitmap? = null

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

        handleSharedIntent(intent)
    }

    // ==================== 系统分享接收(ACTION_SEND image/* + application/pdf) ====================

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    /** 收到分享: 切到打印页并载入(原厂 ShareIntentParser.parseShareURI 的 EXTRA_STREAM 路径) */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } ?: intent.data
        if (uri == null) return
        // 仅接收白名单(图片/PDF), 其余类型走系统默认打开
        val mime = contentResolver.getType(uri) ?: intent.type ?: ""
        if (!mime.startsWith("image/") && mime != "application/pdf") return
        findViewById<android.widget.RadioGroup>(R.id.tabBar).check(R.id.tabPrint)
        loadPrintPages(uri, queryDisplayName(uri))
        toast("已接收分享, 解析中…")
    }

    /** 查 content uri 显示名(失败退回 uri 串) */
    private fun queryDisplayName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.toString()
        } ?: uri.toString()
    } catch (_: Exception) { uri.toString() }


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
        f<Spinner>(R.id.ppSpinner).adapter =
            adapter(PrintEngine.PrintPaper.values().map { it.label } + "自定义")
        f<Spinner>(R.id.mediaPSpinner).adapter = adapter(PrintEngine.PrintMedia.values().map { it.label })
        f<Spinner>(R.id.nupPSpinner).adapter = adapter(listOf("1合1", "2合1", "4合1", "9合1", "16合1"))
        setupScaleControl()

        // 6入口(对齐原版: 照片/PDF/TXT/证件/Office/其他)
        f<Button>(R.id.entryPhoto).setOnClickListener { pick("image/*", 100, "选择照片") }
        f<Button>(R.id.entryPdf).setOnClickListener { pick("application/pdf", 101, "选择PDF") }
        f<Button>(R.id.entryTxt).setOnClickListener { pick("text/*", 102, "选择文本") }
        f<Button>(R.id.entryCert).setOnClickListener { showCertTypeDialog() }
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

        // 预览翻页(原厂ViewPager逐"纸"翻页) + 份数±(份数只影响打印, 原厂预览强制单份)
        f<Button>(R.id.prevPage).setOnClickListener { showPage(currentSheet() - 1) }
        f<Button>(R.id.nextPage).setOnClickListener { showPage(currentSheet() + 1) }
        f<Button>(R.id.copyMinus).setOnClickListener { setCopies(printCopies - 1) }
        f<Button>(R.id.copyPlus).setOnClickListener { setCopies(printCopies + 1) }

        f<Button>(R.id.printGoBtn).setOnClickListener { goPrint() }
        // 预览全屏放大: 点预览图弹全屏Dialog(点图关闭)
        f<ImageView>(R.id.printPreview).setOnClickListener { showFullscreenPreview() }
        // 参数实时预览: 任一设置变动→重渲染(10-19用户需求)
        val onCfg = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                updateCustomPaperRow()
                schedulePreviewUpdate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        f<Spinner>(R.id.ppSpinner).onItemSelectedListener = onCfg
        f<Spinner>(R.id.nupPSpinner).onItemSelectedListener = onCfg
        f<SeekBar>(R.id.densityPSeek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) { schedulePreviewUpdate() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        f<CheckBox>(R.id.tonerSaveChk).setOnCheckedChangeListener { _, _ -> schedulePreviewUpdate() }
        f<CheckBox>(R.id.fitChk).setOnCheckedChangeListener { _, _ ->
            if (f<CheckBox>(R.id.fitChk).isChecked) f<SeekBar>(R.id.scalePSeek).progress = 100 - SCALE_MIN
            schedulePreviewUpdate()
        }
        // 自定义纸张 mm 输入 → 实时重渲
        val onMm = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { schedulePreviewUpdate() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        f<EditText>(R.id.customWEdit).addTextChangedListener(onMm)
        f<EditText>(R.id.customHEdit).addTextChangedListener(onMm)
    }

    /** 任意缩放控件: SeekBar 25..400%(拖动即解除"适应纸面"), 勾选适应=100% */
    private fun setupScaleControl() {
        val seek = f<SeekBar>(R.id.scalePSeek)
        seek.max = SCALE_MAX - SCALE_MIN
        seek.progress = 100 - SCALE_MIN   // 初始100%
        val text = f<TextView>(R.id.scalePText)
        val showPct = { text.text = "缩放 ${readScalePct()}%" }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) f<CheckBox>(R.id.fitChk).isChecked = false   // 手动拖动=脱离适应模式
                showPct(); schedulePreviewUpdate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        showPct()
    }

    /** 选"自定义"纸型时显示 mm 输入行 */
    private fun updateCustomPaperRow() {
        val isCustom = f<Spinner>(R.id.ppSpinner).selectedItemPosition >= PrintEngine.PrintPaper.values().size
        f<android.widget.LinearLayout>(R.id.customPaperRow).visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    /** 证件类型选择(原厂 showSelectTypeDialog 同款列表) → 记住类型后选正面 */
    private var certTypeIdx = 0
    private fun showCertTypeDialog() {
        val labels = PrintEngine.CERT_TYPES.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择证件类型")
            .setSingleChoiceItems(labels, certTypeIdx) { d, which ->
                certTypeIdx = which
                d.dismiss()
                toast("先选${labels[which]}正面照片")
                pick("image/*", 104, "${labels[which]}正面")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 预览全屏放大: 复用当前预览位图, 黑底居中, 点任意处关闭 */
    private fun showFullscreenPreview() {
        val bmp = previewBitmap ?: return
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageBitmap(bmp)
            setOnClickListener { dlg.dismiss() }
        }
        dlg.setContentView(iv)
        dlg.show()
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

    // ==================== 打印预览(照原厂PrintFeatureFragment预览区重写) ====================
    // 原厂行为: 改参即全量重光栅化(清缓存重渲); 预览强制单份(emulator=4时copy=1, 份数不进预览);
    // 页码"第x/y页"按预览纸计; 翻页箭头首末页自动隐藏(setIvVisible); 渲染中显示加载态。

    /** 打印参数快照: 预览与打印共用同一读取口, 保证所见即所得 */
    private data class PrintConfig(val paper: PrintEngine.PaperSpec, val media: PrintEngine.PrintMedia,
                                   val nup: Int, val scale: Int, val density: Int, val tonerSave: Boolean,
                                   val duplex: Boolean)

    /** 缩放取值域(SeekBar 0..375 → 25%..400%) */
    private val SCALE_MIN = 25
    private val SCALE_MAX = 400

    /** 从控件读全量打印参数(纸张/N合1/缩放/浓度/省墨/双面) */
    private fun readPrintConfig(): PrintConfig {
        return PrintConfig(
            paper = readPaperSpec(),
            media = PrintEngine.PrintMedia.values().getOrNull(f<Spinner>(R.id.mediaPSpinner).selectedItemPosition)
                ?: PrintEngine.PrintMedia.PLAIN,
            nup = listOf(1, 2, 4, 9, 16)[f<Spinner>(R.id.nupPSpinner).selectedItemPosition],
            scale = readScalePct(),
            density = f<SeekBar>(R.id.densityPSeek).progress - 3,
            tonerSave = f<CheckBox>(R.id.tonerSaveChk).isChecked,
            duplex = f<CheckBox>(R.id.duplexChk).isChecked)
    }

    /** 缩放: 勾选"适应纸面"=100%, 否则 SeekBar 任意 25..400% */
    private fun readScalePct(): Int {
        if (f<CheckBox>(R.id.fitChk).isChecked) return 100
        return (f<SeekBar>(R.id.scalePSeek).progress + SCALE_MIN).coerceIn(SCALE_MIN, SCALE_MAX)
    }

    /** 纸张: 8种预置 + 第9项"自定义"(mm输入→600dpi几何) */
    private fun readPaperSpec(): PrintEngine.PaperSpec {
        val pos = f<Spinner>(R.id.ppSpinner).selectedItemPosition
        PrintEngine.PrintPaper.values().getOrNull(pos)?.let { return it.spec() }
        val wmm = f<EditText>(R.id.customWEdit).text.toString().toDoubleOrNull() ?: 210.0
        val hmm = f<EditText>(R.id.customHEdit).text.toString().toDoubleOrNull() ?: 297.0
        return PrintEngine.customPaperSpec(wmm, hmm)
    }

    /** 当前预览纸序号(0起) */
    private fun currentSheet(): Int = printPageIdx / readPrintConfig().nup

    /** 翻预览纸(原厂ViewPager逐纸翻页, 纸内子页由N合1拼版决定) */
    private fun showPage(sheetIdx: Int) {
        if (printPages.isEmpty()) return
        val nup = readPrintConfig().nup
        val sheets = (printPages.size + nup - 1) / nup
        printPageIdx = sheetIdx.coerceIn(0, sheets - 1) * nup
        schedulePreviewUpdate()
    }

    /// 预览实时渲染: 参数一动即全量重光栅化(250ms防抖合并连续拖动)
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val previewRender = Runnable { renderPreview() }
    private fun schedulePreviewUpdate() {
        previewHandler.removeCallbacks(previewRender)
        previewHandler.postDelayed(previewRender, 250)
    }

    /** 显示副本下采样: 原厂UIL按控件尺寸解码入内存(cacheInMemory), 光栅全分辨率不常驻 */
    private fun downsampleForDisplay(raster: Bitmap, targetW: Int): Bitmap {
        if (raster.width <= targetW) return raster
        val targetH = raster.height * targetW / raster.width
        val scaled = Bitmap.createScaledBitmap(raster, targetW, targetH, true)
        raster.recycle()
        return scaled
    }

    /** 原厂两级预览: ①打印级光栅(PrintEngine, 纸种像素表×6, 灰度无抖动)→②按控件下采样上屏(RGB_565居中) */
    private fun renderPreview() {
        if (printPages.isEmpty()) return
        val cfg = readPrintConfig()
        val idx = printPageIdx; val pages = printPages
        val iv = f<ImageView>(R.id.printPreview)
        val targetW = iv.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        f<ProgressBar>(R.id.previewLoading).visibility = View.VISIBLE   // 原厂加载态
        Thread {
            val display = PrintEngine.renderPreview(pages, idx, cfg.paper, cfg.nup,
                cfg.scale, cfg.density, cfg.tonerSave)?.let { downsampleForDisplay(it, targetW) }
            runOnUiThread {
                if (display != null) {
                    val old = previewBitmap
                    iv.setImageBitmap(display)
                    previewBitmap = display
                    old?.recycle()   // 旧光栅即时回收=原厂删整个preview目录
                }
                f<ProgressBar>(R.id.previewLoading).visibility = View.GONE
                updatePageIndicator(cfg.nup)
            }
        }.start()
    }

    /** 页码标题(原厂loading_preview_title_format"第x/y页") + 翻页箭头首末页隐藏(原厂setIvVisible) */
    private fun updatePageIndicator(nup: Int) {
        val sheets = (printPages.size + nup - 1) / nup
        val sheet = printPageIdx / nup + 1
        f<TextView>(R.id.pageIndicator).text = "第 $sheet / $sheets 页"
        f<Button>(R.id.prevPage).visibility = if (sheet <= 1) View.INVISIBLE else View.VISIBLE
        f<Button>(R.id.nextPage).visibility = if (sheet >= sheets) View.INVISIBLE else View.VISIBLE
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
        val cfg = readPrintConfig()   // 与预览同一参数口, 所见即所得
        if (cfg.duplex) { goManualDuplexPrint(cfg); return }
        busy(true)
        statusText.text = "打印处理中… (${printPages.size}页×${printCopies}份)"
        Thread {
            try {
                val ok = PrintEngine.printJob(ip(), printPages, cfg.paper, cfg.media,
                    cfg.nup, cfg.scale, cfg.density, cfg.tonerSave, printCopies)
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

    /** 手动双面: M7208W无双面器, 正反面分两个作业发送(原厂 content.prt/content_b.prt 同构)。
     *  原厂 MDPXS=正面应打页数(总纸数/2向上取整); 我们按纸序奇偶分面, 翻纸时机由弹窗承接。 */
    private fun goManualDuplexPrint(cfg: PrintConfig) {
        busy(true)
        statusText.text = "双面打印: 正面处理中…"
        Thread {
            try {
                val sheets = PrintEngine.composeSheets(printPages, cfg.nup)
                val front = sheets.filterIndexed { i, _ -> i % 2 == 0 }
                val back = sheets.filterIndexed { i, _ -> i % 2 == 1 }
                val okFront = PrintEngine.sendSheets(ip(), front, cfg.paper, cfg.media,
                    cfg.scale, cfg.density, cfg.tonerSave, printCopies)
                runOnUiThread {
                    if (!okFront) {
                        busy(false); statusText.text = "❌ 正面打印失败(检查IP/打印机状态)"; return@runOnUiThread
                    }
                    if (back.isEmpty()) {
                        statusText.text = "✅ 单面内容已发送"; unlockWhenIdle(); return@runOnUiThread
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("双面打印 — 请翻纸")
                        .setMessage("正面共 $printCopies 份已发送。待出纸完成后, 请将纸堆整体翻面(保持页脚方向一致)放回纸盒, 再继续打印背面。")
                        .setCancelable(false)
                        .setPositiveButton("继续打印背面") { _, _ ->
                            statusText.text = "双面打印: 背面处理中…"
                            Thread {
                                try {
                                    val okBack = PrintEngine.sendSheets(ip(), back, cfg.paper, cfg.media,
                                        cfg.scale, cfg.density, cfg.tonerSave, printCopies)
                                    runOnUiThread {
                                        if (okBack) {
                                            statusText.text = "✅ 双面作业全部发送 — 完成前请勿扫描/复印"
                                            unlockWhenIdle()
                                        } else { busy(false); statusText.text = "❌ 背面打印失败" }
                                    }
                                } catch (e: Throwable) {
                                    runOnUiThread { busy(false); statusText.text = "❌ ${e.message}" }
                                }
                            }.start()
                        }
                        .setNegativeButton("只印正面") { _, _ ->
                            busy(false); statusText.text = "已取消背面(正面作业不受影响)"
                        }
                        .show()
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
        // 手动旋转: 90°步进(原图可能已加入PDF队列共享引用, 不回收旧图)
        f<Button>(R.id.rotateBtn).setOnClickListener {
            val bmp = lastBitmap ?: run { toast("先扫描一页"); return@setOnClickListener }
            val m = android.graphics.Matrix().apply { postRotate(90f) }
            lastBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            preview.setImageBitmap(lastBitmap)
            lastSavedUri = null   // 旋转后旧分享目标作废
        }
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

        setupPrinterSettingsSection()
    }

    // ==================== 打印机系统设置区(休眠/热点/微调, 9100设置帧) ====================

    private fun setupPrinterSettingsSection() {
        setupSleepSection()
        setupHotspotSection()
        setupUserConfigSection()
    }

    /** 设备设置动作公共收尾: 后台执行 + 结果写状态条(错误码译中文) */
    private fun runSetting(action: String, block: () -> String) {
        statusText.text = "$action…"
        Thread {
            val msg = try { block() } catch (e: Throwable) { "❌ ${e.message}" }
            runOnUiThread { statusText.text = msg }
        }.start()
    }

    /** 设置帧错误码 → 中文提示(码表来自规格: 0x24/25/26/27/2c/2d/2e) */
    private fun settingErrText(err: Int?): String = when (err) {
        null -> "❌ 通信失败(检查IP/打印机在线)"
        0 -> "✅ 已保存"
        0x26 -> "❌ 参数非法(检查取值范围)"
        0x2e -> "❌ 密码校验未通过(8~64位)"
        else -> "❌ 设备拒绝(错误码 $err / 0x${err.toString(16)})"
    }

    /** 休眠时间: 读 → 填编辑框; 写 → 1~30分钟(原生二次校验, 越界返 0x26) */
    private fun setupSleepSection() {
        f<Button>(R.id.sleepReadBtn).setOnClickListener {
            runSetting("读取休眠时间") {
                val min = SleepPower.get(ip()) ?: return@runSetting "❌ 读取失败(打印机不在线或协议未应答)"
                runOnUiThread { f<EditText>(R.id.sleepEdit).setText(min.toString()) }
                "✅ 当前休眠时间: $min 分钟"
            }
        }
        f<Button>(R.id.sleepSetBtn).setOnClickListener {
            val min = f<EditText>(R.id.sleepEdit).text.toString().toIntOrNull()
            if (min == null || min !in SleepPower.MIN_MINUTES..SleepPower.MAX_MINUTES) {
                toast("休眠时间需 ${SleepPower.MIN_MINUTES}~${SleepPower.MAX_MINUTES} 分钟"); return@setOnClickListener
            }
            runSetting("写入休眠时间") { settingErrText(SleepPower.set(ip(), min)) }
        }
    }

    /** 热点AP: 读 → 回填开关/SSID/密码; 应用 → 开(enable=6)/关(enable=1) 191B帧 */
    private fun setupHotspotSection() {
        f<Button>(R.id.hotReadBtn).setOnClickListener {
            runSetting("读取热点状态") {
                val st = HotspotAp.get(ip()) ?: return@runSetting "❌ 读取失败(打印机不在线或协议未应答)"
                runOnUiThread {
                    f<CheckBox>(R.id.hotChk).isChecked = st.enabled
                    f<EditText>(R.id.hotSSID).setText(st.ssid)
                    f<EditText>(R.id.hotPass).setText(st.password)
                }
                if (st.enabled) "✅ 热点已开启: ${st.ssid}" else "热点当前关闭"
            }
        }
        f<Button>(R.id.hotSetBtn).setOnClickListener {
            val enable = f<CheckBox>(R.id.hotChk).isChecked
            val ssid = f<EditText>(R.id.hotSSID).text.toString().trim()
            val pass = f<EditText>(R.id.hotPass).text.toString()
            HotspotAp.validate(enable, ssid, pass)?.let { toast(it); return@setOnClickListener }
            // 关闭时字段可为空: 用占位填充帧布局(设备只认 enable 码)
            val ssidArg = ssid.ifEmpty { "M7208W" }
            val passArg = pass.ifEmpty { "12345678" }
            runSetting(if (enable) "开启热点" else "关闭热点") {
                settingErrText(HotspotAp.set(ip(), enable, ssidArg, passArg))
            }
        }
    }

    /** 打印微调(userconfig): 上/左偏移-6~+6格(约0.5mm/格), 打印机默认浓度-3~+3, 低湿度开关 */
    private fun setupUserConfigSection() {
        // 负值 SeekBar 映射: max=范围, progress=值-min
        fun bindSignedSeek(seekId: Int, labelId: Int, name: String, min: Int, max: Int) {
            val seek = f<SeekBar>(seekId)
            seek.max = max - min
            seek.progress = 0 - min
            f<TextView>(labelId).text = "$name: 0"
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    f<TextView>(labelId).text = "$name: ${v + min}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bindSignedSeek(R.id.ucLeadingSeek, R.id.ucLeadingLabel, "上边偏移", UserConfig.OFFSET_MIN, UserConfig.OFFSET_MAX)
        bindSignedSeek(R.id.ucSideSeek, R.id.ucSideLabel, "左边偏移", UserConfig.OFFSET_MIN, UserConfig.OFFSET_MAX)
        bindSignedSeek(R.id.ucDensitySeek, R.id.ucDensityLabel, "默认浓度", UserConfig.DENSITY_MIN, UserConfig.DENSITY_MAX)

        f<Button>(R.id.ucReadBtn).setOnClickListener {
            runSetting("读取打印微调") {
                val v = UserConfig.get(ip()) ?: return@runSetting "❌ 读取失败(打印机不在线或协议未应答)"
                runOnUiThread {
                    f<SeekBar>(R.id.ucLeadingSeek).progress = v.leadingEdge - UserConfig.OFFSET_MIN
                    f<SeekBar>(R.id.ucSideSeek).progress = v.sideToSide - UserConfig.OFFSET_MIN
                    f<SeekBar>(R.id.ucDensitySeek).progress = v.imageDensity - UserConfig.DENSITY_MIN
                    f<CheckBox>(R.id.ucHumidityChk).isChecked = v.lowHumidity
                }
                "✅ 偏移(${v.leadingEdge},${v.sideToSide}) 浓度${v.imageDensity} 低湿度${if (v.lowHumidity) "开" else "关"}"
            }
        }
        f<Button>(R.id.ucSetBtn).setOnClickListener {
            val v = UserConfig.Values(
                leadingEdge = f<SeekBar>(R.id.ucLeadingSeek).progress + UserConfig.OFFSET_MIN,
                sideToSide = f<SeekBar>(R.id.ucSideSeek).progress + UserConfig.OFFSET_MIN,
                imageDensity = f<SeekBar>(R.id.ucDensitySeek).progress + UserConfig.DENSITY_MIN,
                lowHumidity = f<CheckBox>(R.id.ucHumidityChk).isChecked,
                plateControl = UserConfig.PLATE_DIRTY_OFF,   // 背面脏污/强制冷却保持中性默认(App未暴露)
                coolMode = 0)
            runSetting("写入打印微调") { settingErrText(UserConfig.set(ip(), v)) }
        }
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
            106 -> { // 证件反面 → 合成(按所选证件类型尺寸, 原厂952×1362上下分面布局)
                try {
                    val back = decodeSampled(uri, 2000)
                    val front = pendingCertFront
                    if (front != null && back != null) {
                        val type = PrintEngine.CERT_TYPES.getOrElse(certTypeIdx) { PrintEngine.CERT_TYPES[0] }
                        printPages = listOf(PrintEngine.composeCertificate(front, back, type))
                        pendingCertFront = null   // 用完即清, 防残留串台(审查L)
                        f<android.widget.LinearLayout>(R.id.previewCard).visibility = View.VISIBLE
                        f<TextView>(R.id.previewTitle).text = "${type.label}打印 (正反合成)"
                        showPage(0); setCopies(1)
                        statusText.text = "${type.label}已合成, 可直接打印"
                    }
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
        }
    }
}
