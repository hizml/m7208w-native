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

    private companion object {
        // 相机拍照回调码(G3/D2): 相机Intent结果在extras["data"], 与相册选图的data uri路径分流
        const val REQ_CERT_CAM_FRONT = 110   // 证件拍照-身份证正面
        const val REQ_CERT_CAM_BACK = 111    // 证件拍照-身份证反面
        const val REQ_CERT_CAM_ONE = 112     // 证件拍照-单面证件
        const val REQ_WIFI_QR_CAM = 113      // 扫码配网-拍WiFi二维码

        // 加密下拉位(WPA2/WEP64/WEP128/开放) → WiFiWriter帧加密码(3/1/2/0), 双向共用
        val encFrameBySpinnerPos = intArrayOf(3, 1, 2, 0)
    }

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

    // 取消扫描(C8): 会话引用+运行标志, scanBtn扫描中切换为"取消"
    @Volatile private var scanRunning = false
    private var scanSession: ScanSession? = null

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
    /// 忙=状态表state=="Busy"; Ready/Sleep/Error=完成; 卡纸等错误(码>=0xBC)状态条亮错;
    /// 超时5分钟兜底解锁
    private fun unlockWhenIdle() {
        val target = ip()
        Thread {
            Thread.sleep(10_000)   // 给任务起飞
            val deadline = System.currentTimeMillis() + 290_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5_000)
                val st = StatusPage.deviceStatus(target) ?: continue   // 查询失败继续等
                val info = PrinterStateTable.fromStatusText(st)
                if (info.code >= 0xBC) showDeviceState(info)           // 卡纸/错误/过热: 状态条显错
                showJamGuideIfNeeded(info.code)                        // 卡纸张(0xBC..0xC0): 原厂GIF引导
                if (info.state != "Busy") break
            }
            busy(false)
        }.start()
    }

    /// 卡纸状态码 → 原厂引导GIF(三组动画按卡纸位置归类: 整张取出/缺纸加纸/后端处理)
    private val jamGuideGifs = mapOf(
        0xBC to R.raw.jam_pager_full_1,     // 纸残留 → 前盖抬杆取整张
        0xBD to R.raw.jam_pager_empty_1,    // 未进纸 → 纸盒加纸
        0xBE to R.raw.jam_pager_full_1,     // 对位卡纸 → 前盖取整张
        0xBF to R.raw.jam_pager_backend_1,  // 内部卡纸 → 后端处理
        0xC0 to R.raw.jam_pager_backend_1)  // 出口卡纸 → 后端处理

    /** 命中卡纸张(0xBC..0xC0)→主线程弹对应原厂GIF引导; 其余状态码不弹 */
    private fun showJamGuideIfNeeded(code: Int) {
        val gif = jamGuideGifs[code] ?: return
        val info = PrinterStateTable.lookup(code)
        runOnUiThread {
            GifDialog.show(this, gif, "卡纸处理引导",
                "${info?.zh ?: "卡纸"} — 请按动画步骤处理后重试")
        }
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
        f<Spinner>(R.id.qualityPSpinner).adapter = adapter(PrintEngine.PrintQuality.values().map { it.label })
        setupScaleControl()
        setupPrintProfiles()

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
        f<Spinner>(R.id.qualityPSpinner).onItemSelectedListener = onCfg
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

    // ==================== 我的打印档(A9: SharedPreferences存3组打印参数) ====================

    private val profilePrefs by lazy { getSharedPreferences("print_profiles", MODE_PRIVATE) }
    private val profileLabels get() = (1..3).map { "我的打印$it" }
    /** 初始布局会触发一次onItemSelected, 用armed标志跳过(避免启动即覆写当前设置) */
    private var profileSpinnerArmed = false

    private fun setupPrintProfiles() {
        f<Spinner>(R.id.profilePSpinner).adapter = adapter(profileLabels)
        f<Button>(R.id.saveProfileBtn).setOnClickListener { savePrintProfile() }
        f<Spinner>(R.id.profilePSpinner).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!profileSpinnerArmed) { profileSpinnerArmed = true; return }
                loadPrintProfile(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 保存当前打印参数(纸张/N合1/缩放/浓度/省墨)到所选档位 */
    private fun savePrintProfile() {
        val slot = f<Spinner>(R.id.profilePSpinner).selectedItemPosition
        profilePrefs.edit()
            .putBoolean("saved_$slot", true)
            .putInt("paper_$slot", f<Spinner>(R.id.ppSpinner).selectedItemPosition)
            .putString("customW_$slot", f<EditText>(R.id.customWEdit).text.toString())
            .putString("customH_$slot", f<EditText>(R.id.customHEdit).text.toString())
            .putInt("nup_$slot", f<Spinner>(R.id.nupPSpinner).selectedItemPosition)
            .putInt("scale_$slot", readScalePct())
            .putInt("density_$slot", f<SeekBar>(R.id.densityPSeek).progress)
            .putBoolean("tonerSave_$slot", f<CheckBox>(R.id.tonerSaveChk).isChecked)
            .apply()
        toast("已保存到 ${profileLabels[slot]}")
    }

    /** 载入档位参数到控件(联动触发预览重渲) */
    private fun loadPrintProfile(slot: Int) {
        if (!profilePrefs.getBoolean("saved_$slot", false)) {
            toast("${profileLabels.getOrNull(slot) ?: "档位"} 未保存"); return
        }
        f<Spinner>(R.id.ppSpinner).setSelection(profilePrefs.getInt("paper_$slot", 0))
        f<EditText>(R.id.customWEdit).setText(profilePrefs.getString("customW_$slot", "210"))
        f<EditText>(R.id.customHEdit).setText(profilePrefs.getString("customH_$slot", "297"))
        f<Spinner>(R.id.nupPSpinner).setSelection(profilePrefs.getInt("nup_$slot", 0))
        val pct = profilePrefs.getInt("scale_$slot", 100).coerceIn(SCALE_MIN, SCALE_MAX)
        f<CheckBox>(R.id.fitChk).isChecked = pct == 100
        if (pct != 100) f<SeekBar>(R.id.scalePSeek).progress = pct - SCALE_MIN
        f<SeekBar>(R.id.densityPSeek).progress = profilePrefs.getInt("density_$slot", 3).coerceIn(0, 6)
        f<CheckBox>(R.id.tonerSaveChk).isChecked = profilePrefs.getBoolean("tonerSave_$slot", false)
        toast("已载入 ${profileLabels[slot]}")
    }

    /** 证件类型选择(原厂 showSelectTypeDialog: 列表取certificate_selector,
     *  仅requestCode==1身份证走正反两面对拍, 其余单面裁剪直接打印)。
     *  选完类型再选来源: 拍照(系统相机Intent, G3)或从相册选(原有路径) */
    private var certTypeIdx = 0
    private fun showCertTypeDialog() {
        val labels = CertTable.ALL.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择证件类型")
            .setSingleChoiceItems(labels, certTypeIdx) { d, which ->
                certTypeIdx = which
                d.dismiss()
                showCertSourceDialog(CertTable.ALL[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 证件图片来源: 拍照(零依赖系统相机Intent) / 相册 */
    private fun showCertSourceDialog(cert: CertType) {
        android.app.AlertDialog.Builder(this)
            .setTitle(cert.title)
            .setItems(arrayOf("📷 拍照", "🖼 从相册选")) { _, which ->
                if (which == 0) launchCertCamera(cert) else pickCertFromGallery(cert)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 系统相机拍照(G3): 不带EXTRA_OUTPUT, 缩略图Bitmap经extras返回, 直接进裁剪/合成流程 */
    private fun launchCertCamera(cert: CertType) {
        val code = if (cert.requestCode == 1) REQ_CERT_CAM_FRONT else REQ_CERT_CAM_ONE
        if (!launchCamera(code)) return
        if (cert.requestCode == 1) toast("先拍${cert.name}正面")
    }

    /** 相册选证件(原有路径): 身份证两面对选, 其余单面 */
    private fun pickCertFromGallery(cert: CertType) {
        if (cert.requestCode == 1) {
            toast("先选${cert.name}正面照片")
            pick("image/*", 104, "${cert.name}正面")
        } else {
            pick("image/*", 107, cert.title)
        }
    }

    /** 启动系统相机(公共入口): 无可用相机应用时不发Intent直接提示 */
    private fun launchCamera(requestCode: Int): Boolean {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (i.resolveActivity(packageManager) == null) {
            toast("无可用相机应用"); return false
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, requestCode)
        return true
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
                                   val duplex: Boolean, val quality: PrintEngine.PrintQuality)

    /** 缩放取值域(SeekBar 0..375 → 25%..400%) */
    private val SCALE_MIN = 25
    private val SCALE_MAX = 400

    /** 从控件读全量打印参数(纸张/N合1/缩放/浓度/省墨/双面/质量) */
    private fun readPrintConfig(): PrintConfig {
        return PrintConfig(
            paper = readPaperSpec(),
            media = PrintEngine.PrintMedia.values().getOrNull(f<Spinner>(R.id.mediaPSpinner).selectedItemPosition)
                ?: PrintEngine.PrintMedia.PLAIN,
            nup = listOf(1, 2, 4, 9, 16)[f<Spinner>(R.id.nupPSpinner).selectedItemPosition],
            scale = readScalePct(),
            density = f<SeekBar>(R.id.densityPSeek).progress - 3,
            tonerSave = f<CheckBox>(R.id.tonerSaveChk).isChecked,
            duplex = f<CheckBox>(R.id.duplexChk).isChecked,
            quality = PrintEngine.PrintQuality.values().getOrNull(f<Spinner>(R.id.qualityPSpinner).selectedItemPosition)
                ?: PrintEngine.PrintQuality.FINE)
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

    /** 大图切页后的逻辑页数(A10): 一张超高源图按页高分段后占多页, 预览/页码与打印同公式 */
    private fun logicalPageCount(cfg: PrintConfig): Int =
        printPages.sumOf { PrintEngine.pageSegments(it, cfg.paper.h600, cfg.scale).size }

    /** 当前预览纸序号(0起, 按逻辑页计) */
    private fun currentSheet(cfg: PrintConfig = readPrintConfig()): Int = printPageIdx / cfg.nup

    /** 翻预览纸(原厂ViewPager逐纸翻页, 纸内子页由N合1拼版+大图切页共同决定) */
    private fun showPage(sheetIdx: Int) {
        if (printPages.isEmpty()) return
        val cfg = readPrintConfig()
        val sheets = (logicalPageCount(cfg) + cfg.nup - 1) / cfg.nup
        printPageIdx = sheetIdx.coerceIn(0, sheets - 1) * cfg.nup
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

    /** 页码标题(原厂loading_preview_title_format"第x/y页") + 翻页箭头首末页隐藏(原厂setIvVisible);
     *  页数按大图切页后的逻辑页计(A10) */
    private fun updatePageIndicator(nup: Int) {
        val cfg = readPrintConfig()
        val sheets = (logicalPageCount(cfg) + nup - 1) / nup
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
        // 手动双面发作业前: 原厂第一步GIF引导(放纸), 弹窗不阻塞作业
        if (cfg.duplex) {
            GifDialog.show(this, R.raw.print_double_step_1, "双面打印", "请先放入纸张")
            goManualDuplexPrint(cfg); return
        }
        busy(true)
        statusText.text = "打印处理中… (${logicalPageCount(cfg)}页×${printCopies}份)"
        Thread {
            try {
                val ok = PrintEngine.printJob(ip(), printPages, cfg.paper, cfg.media,
                    cfg.nup, cfg.scale, cfg.density, cfg.tonerSave, printCopies, cfg.quality)
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
                    cfg.scale, cfg.density, cfg.tonerSave, printCopies, cfg.quality)
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
                                        cfg.scale, cfg.density, cfg.tonerSave, printCopies, cfg.quality)
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
            // 证件复印: 原厂第一步GIF引导(放置正面), 弹窗不阻塞命令
            if (nup == NupMode.ID_CARD)
                GifDialog.show(this, R.raw.copy_idcard_step_1, "身份证复印", "请放置正面 — 提示后翻面再放背面")
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
        scanBtn.setOnClickListener { if (scanRunning) cancelScan() else runScan() }
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
        scanRunning = true
        scanBtn.text = "✖ 取消"   // 扫描中变为取消(C8), 保持可点击
        progress.visibility = View.VISIBLE
        statusText.text = "扫描中…"
        lastLiveRows = 0
        val target = ip()
        val session = ScanSession(target)
        scanSession = session
        Thread {
            try {
                var result: Pair<ByteArray, Int>? = null
                try {
                    result = session.scan(options) { n, rawChunk ->
                        runOnUiThread { statusText.text = "扫描中… ${n / 1024} KB · ${n / 2496} 行" }
                        livePreview(rawChunk)
                    }
                } catch (e: ScanException) {
                    // 用户取消: 直接抛出收尾, 不触发"扫描服务休眠→远程重启"流程
                    if (session.isCancelled) throw ScanException(ScanException.Kind.CANCELLED)
                    if (e.kind == ScanException.Kind.DEAD) {
                        runOnUiThread { statusText.text = "扫描服务休眠(打印后固病), 远程重启打印机…约3分钟" }
                        if (!PrinterControl.reboot(target) || !waitReboot(target) { session.isCancelled })
                            throw ScanException(ScanException.Kind.DEAD)
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
                    resetScanUi()
                    preview.setImageBitmap(bmp)
                    preview.visibility = View.VISIBLE
                    statusText.text = "扫描完成 (${width}×${processed.size / width})"
                    saveBtn.isEnabled = true
                }
            } catch (e: Throwable) {   // 含OOM等Error, 防deviceBusy永久锁死(审查M1)
                runOnUiThread {
                    busy(false)
                    resetScanUi()
                    statusText.text = if (session.isCancelled) "已取消扫描" else "失败: ${friendly(e)}"
                    saveBtn.isEnabled = lastBitmap != null
                }
            }
        }.start()
    }

    /** 取消扫描(C8): 断开53010连接(依赖设备侧超时自复位), deviceBusy解锁, UI恢复 */
    private fun cancelScan() {
        scanSession?.cancel()
        busy(false)
        resetScanUi()
        statusText.text = "已取消扫描(打印机侧稍后自动复位)"
    }

    /** 扫描控件恢复初始态(完成/失败/取消三路共用) */
    private fun resetScanUi() {
        scanRunning = false
        scanSession = null
        scanBtn.text = "🔍 开始扫描"
        scanBtn.isEnabled = true
        progress.visibility = View.GONE
    }

    private fun waitReboot(ip: String, maxSec: Int = 300, isCancelled: () -> Boolean = { false }): Boolean {
        val deadline = System.currentTimeMillis() + maxSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) throw ScanException(ScanException.Kind.CANCELLED)
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
            ScanException.Kind.CANCELLED -> "已取消扫描"
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
        // 扫码配网(D2): 系统相机拍WiFi二维码 → ZXing core解析 → 自动填入SSID/密码/加密方式
        f<Button>(R.id.wifiQrBtn).setOnClickListener { launchCamera(REQ_WIFI_QR_CAM) }
        f<Button>(R.id.wifiWriteBtn).setOnClickListener {
            val ssid = f<EditText>(R.id.newSSID).text.toString().trim()
            val pass = f<EditText>(R.id.newPass).text.toString()
            if (ssid.isEmpty()) { toast("填SSID"); return@setOnClickListener }
            android.app.AlertDialog.Builder(this)
                .setTitle("写入WiFi配置")
                .setMessage("打印机将连接 \"$ssid\" 并重启, IP可能变化。写错需USB线重配。确认?")
                .setPositiveButton("确认写入") { _, _ ->
                    Thread {
                        val enc = encFrameBySpinnerPos[f<Spinner>(R.id.wifiEncSpinner).selectedItemPosition]
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

    // ==================== 打印机系统设置区(休眠/热点/微调/感光鼓定影维护, 9100设置帧) ====================

    private fun setupPrinterSettingsSection() {
        setupSleepSection()
        setupHotspotSection()
        setupUserConfigSection()
        setupMaintenanceSection()
    }

    /** 感光鼓/定影维护: drum_reset(sub=0x1a 带确认弹窗) / fusererror_reset(sub=0x0b) */
    private fun setupMaintenanceSection() {
        f<Button>(R.id.drumResetBtn).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("复位感光鼓")
                .setMessage("将清零感光鼓计数(换新硒鼓后执行)。确认复位?")
                .setPositiveButton("复位") { _, _ ->
                    runSetting("复位感光鼓") { settingErrText(DeviceMaintenance.drumReset(ip())) }
                }
                .setNegativeButton("取消", null).show()
        }
        f<Button>(R.id.fuserResetBtn).setOnClickListener {
            runSetting("清除定影错误") { settingErrText(DeviceMaintenance.fuserReset(ip())) }
        }
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
                    // 隐藏项回填: plateControl 0=开/2=关(反码语义), coolMode 1=开
                    f<CheckBox>(R.id.ucBackDirtyChk).isChecked = v.plateControl == UserConfig.PLATE_DIRTY_ON
                    f<CheckBox>(R.id.ucCoolChk).isChecked = v.coolMode != 0
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
                plateControl = if (f<CheckBox>(R.id.ucBackDirtyChk).isChecked)
                    UserConfig.PLATE_DIRTY_ON else UserConfig.PLATE_DIRTY_OFF,
                coolMode = if (f<CheckBox>(R.id.ucCoolChk).isChecked) 1 else 0)
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

    // 状态条配色(原厂状态色 Black/Orange/Red → App内呈现)
    private val colorTextNormal = Color.BLACK                        // 原厂Black: 正常态文字
    private val colorTextWarning = Color.parseColor("#f59e0b")      // 原厂Orange: 注意态(与busyText同橙)
    private val colorTextOnline = Color.parseColor("#1a7f37")       // 文字: 在线但未知状态
    private val colorLightOk = Color.parseColor("#22c55e")          // 指示灯: 在线/正常
    private val colorLightErr = Color.parseColor("#ef4444")         // 指示灯: 离线/错误

    private fun setLight(color: Int) {
        (statusLight.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
    }

    /** 原厂状态色(Black/Orange/Red) → 状态条文字颜色 */
    private fun stateTextColor(factoryColor: String): Int = when (factoryColor) {
        "Orange" -> colorTextWarning
        "Red" -> Color.RED
        else -> colorTextNormal
    }

    /** 指示灯颜色: 原厂Orange→橙, Red→红, 其余(Black正常)→绿 */
    private fun stateLightColor(factoryColor: String): Int = when (factoryColor) {
        "Orange" -> colorTextWarning
        "Red" -> colorLightErr
        else -> colorLightOk
    }

    /** 墨粉条颜色随状态: Error红(含卡纸/硬件故障), Orange注意, 其余绿 */
    private fun tonerBarColor(info: PrinterStateInfo?): Int = when (info?.color) {
        "Red" -> Color.RED
        "Orange" -> colorTextWarning
        else -> colorTextOnline
    }

    /** 把状态表项渲染到状态条(文案+原厂色)与指示灯; 墨粉条同步变色; 可从任意线程调用 */
    private fun showDeviceState(info: PrinterStateInfo, prefix: String = "⚠ ") {
        runOnUiThread {
            statusText.text = "$prefix${info.zh}"
            statusText.setTextColor(stateTextColor(info.color))
            setLight(stateLightColor(info.color))
            tonerBar.progressTintList = android.content.res.ColorStateList.valueOf(tonerBarColor(info))
        }
    }

    private fun refreshStatus() {
        statusText.text = "检查打印机…"
        val target = ip()
        getPreferences(MODE_PRIVATE).edit().putString("ip", target).apply()
        Thread {
            val alive = PrinterControl.isAlive(target)
            var toner: Triple<Int, Int, Int>? = null
            var state: PrinterStateInfo? = null
            if (alive) {
                toner = TonerStatus.fetch(target)
                // Device Status 文本 → 56态表(未知码也回退为带原文的表项)
                state = StatusPage.deviceStatus(target)?.let { PrinterStateTable.fromStatusText(it) }
            }
            runOnUiThread { renderStatus(target, alive, state, toner) }
        }.start()
    }

    /** 底部状态条渲染: 在线=状态表中文文案(原厂色), 离线=红; 指示灯/墨粉条随状态变色 */
    private fun renderStatus(target: String, alive: Boolean, state: PrinterStateInfo?, toner: Triple<Int, Int, Int>?) {
        if (!alive) {
            statusText.text = "● 离线 $target"
            statusText.setTextColor(Color.RED)
            setLight(colorLightErr)
        } else if (state != null) {
            statusText.text = "● 在线 $target · ${state.zh}"
            statusText.setTextColor(stateTextColor(state.color))
            setLight(stateLightColor(state.color))
        } else {
            statusText.text = "● 在线 $target"
            statusText.setTextColor(colorTextOnline)
            setLight(colorLightOk)
        }
        if (toner != null) {
            tonerLabel.text = "墨粉 ${toner.first}%"
            tonerBar.progress = toner.first
        } else { tonerLabel.text = "墨粉 --"; tonerBar.progress = 0 }
        tonerBar.progressTintList = android.content.res.ColorStateList.valueOf(tonerBarColor(state))
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
                } else {
                    statusText.text = "❌ 没找到(确认手机和打印机同WiFi)"
                    showConnectGuideDialog()
                }
            }
        }.start()
    }

    /** 连接失败三步引导(D5): 文案取自原厂strings.xml
     *  (search_printer_one_step_content / print_pro_frist_step_content / print_pro_second_step_content,
     *   步骤标签沿用print_pro_frist/second_step; "第三步:"原厂无对应串, 按"第一步/第二步"格式续写) */
    private fun showConnectGuideDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("搜索失败，请重新搜索")   // 原厂 research_printer
            .setMessage("第一步: 确认打印机电源、打印机Wi-Fi是否开启？\n\n" +
                "第二步: 请确认电源是否开启，并确认本设备与打印机是否在同一无线网络。\n\n" +
                "第三步: 1秒内连续按机器电源按钮三次，自动打印出移动设备连接打印机指南，按指南处理。")
            .setPositiveButton("重新搜索") { _, _ -> findPrinter() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 退出确认(F5) ====================
    /** 有扫描结果时退出需确认(文案取原厂strings.xml: quit_dialog_text/quit_btn_yes/quit_btn_no) */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (lastBitmap == null && pdfQueue.isEmpty()) {
            @Suppress("DEPRECATION") super.onBackPressed(); return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("提示")   // 原厂 prompt
            .setMessage("应用关闭后，扫描图片将会被删除，是否关闭，请确认!")
            .setPositiveButton("是") { _, _ -> @Suppress("DEPRECATION") finish() }
            .setNegativeButton("否", null)
            .show()
    }

    // ==================== 文件选择/相机回调 ====================
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        // 相机拍照(G3/D2): 缩略图在extras["data"], 无data uri, 先于相册路径分流
        when (requestCode) {
            REQ_CERT_CAM_FRONT, REQ_CERT_CAM_BACK, REQ_CERT_CAM_ONE -> {
                handleCertCamera(requestCode, data); return
            }
            REQ_WIFI_QR_CAM -> { handleWifiQrCamera(data); return }
        }
        val uri = data?.data ?: return
        val name = uri.toString()
        when (requestCode) {
            100, 101, 102, 103, 105 -> loadPrintPages(uri, name)
            104 -> { // 身份证正面(requestCode==1) → 继续选反面
                try {
                    pendingCertFront = decodeSampled(uri, 2000)
                    toast("再选反面照片")
                    pick("image/*", 106, "证件反面")
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
            106 -> { // 身份证反面 → 正反合成(原厂saveIdCardImage: 952×1362上下分面布局)
                try {
                    val back = decodeSampled(uri, 2000)
                    val front = pendingCertFront
                    if (front != null && back != null) {
                        val type = CertTable.ALL.getOrElse(certTypeIdx) { CertTable.ALL[0] }
                        pendingCertFront = null   // 用完即清, 防残留串台(审查L)
                        showCertPages(PrintEngine.composeCertificate(front, back, type),
                            "${type.name}打印 (正反合成)", "${type.name}已合成, 可直接打印")
                    }
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
            107 -> { // 非身份证证件: 单面按CropSize裁剪(aspect比裁剪+outX×outY缩放)直接入打印页
                try {
                    val src = decodeSampled(uri, 2000) ?: throw IllegalStateException("图片解码失败")
                    val type = CertTable.ALL.getOrElse(certTypeIdx) { CertTable.ALL[0] }
                    showCertPages(PrintEngine.cropCertFace(src, type),
                        "${type.name}打印", "${type.name}已裁剪(${type.outX}×${type.outY}), 可直接打印")
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
        }
    }

    /** 证件照片入版(拍照/相册三路共用): 单页入打印队列+预览卡显示 */
    private fun showCertPages(composed: Bitmap, title: String, status: String) {
        printPages = listOf(composed)
        f<android.widget.LinearLayout>(R.id.previewCard).visibility = View.VISIBLE
        f<TextView>(R.id.previewTitle).text = title
        showPage(0); setCopies(1)
        statusText.text = status
    }

    /** 相机拍照返回的缩略图(不带EXTRA_OUTPUT时系统塞进extras["data"]) */
    private fun cameraBitmap(data: Intent?): Bitmap? = data?.extras?.get("data") as? Bitmap

    /** 证件拍照结果(G3): 正面→续拍反面→合成; 单面证件→裁剪直接入版 */
    private fun handleCertCamera(requestCode: Int, data: Intent?) {
        val bmp = cameraBitmap(data)
        if (bmp == null) { statusText.text = "❌ 未获取到照片"; return }
        val type = CertTable.ALL.getOrElse(certTypeIdx) { CertTable.ALL[0] }
        when (requestCode) {
            REQ_CERT_CAM_FRONT -> {
                pendingCertFront = bmp
                toast("再拍${type.name}反面")
                if (!launchCamera(REQ_CERT_CAM_BACK)) pendingCertFront = null
            }
            REQ_CERT_CAM_BACK -> {
                val front = pendingCertFront
                pendingCertFront = null
                if (front == null) { statusText.text = "❌ 正面照片丢失, 请重新拍照"; return }
                try {
                    showCertPages(PrintEngine.composeCertificate(front, bmp, type),
                        "${type.name}打印 (正反合成)", "${type.name}已合成, 可直接打印")
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
            REQ_CERT_CAM_ONE -> {
                try {
                    showCertPages(PrintEngine.cropCertFace(bmp, type),
                        "${type.name}打印", "${type.name}已裁剪(${type.outX}×${type.outY}), 可直接打印")
                } catch (e: Exception) { statusText.text = "❌ ${e.message}" }
            }
        }
    }

    /** WiFi二维码拍照结果(D2): 后台ZXing解码 → 解析WIFI:串 → 自动填入配网表单;
     *  写入有失联风险, 仍由用户核对后点"写入WiFi配置"确认 */
    private fun handleWifiQrCamera(data: Intent?) {
        val bmp = cameraBitmap(data)
        if (bmp == null) { toast("未获取到照片"); return }
        statusText.text = "解析二维码…"
        Thread {
            val content = WifiQr.decodeText(bmp)?.let { WifiQr.parseWifi(it) }
            runOnUiThread {
                if (content == null) {
                    statusText.text = "❌ 未识别到WiFi二维码(请靠近重拍, 或改手动输入)"
                    return@runOnUiThread
                }
                f<EditText>(R.id.newSSID).setText(content.ssid)
                f<EditText>(R.id.newPass).setText(content.password)
                f<Spinner>(R.id.wifiEncSpinner).setSelection(encSpinnerPos(content.encryption))
                statusText.text = "✅ 已填入\"${content.ssid}\", 核对后点写入WiFi配置"
            }
        }.start()
    }

    /** 帧加密码 → 加密下拉位(-1兜底WPA2) */
    private fun encSpinnerPos(encryption: Int): Int =
        encFrameBySpinnerPos.indexOf(encryption).takeIf { it >= 0 } ?: 0
}
