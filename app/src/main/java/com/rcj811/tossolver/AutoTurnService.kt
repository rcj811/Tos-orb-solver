package com.rcj811.tossolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 自動轉珠服務（無障礙服務）＋懸浮工作列
 *
 * 工作列按鈕：
 *  ⠿  拖曳移動；點一下收合 / 展開
 *  ▶  自動執行（全自動）：戰鬥中自動轉珠；戰鬥外依「點擊規則」自動點擊；再按一次（■）停止
 *  轉  只轉這一回合
 *  ＋  新增點擊規則：點一下畫面上要自動按的位置（例如「再次挑戰」）
 *  校  校正盤面位置與顏色
 *
 * 截圖只在手機上即時處理，不儲存、不上傳。
 */
class AutoTurnService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoTurnService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, AutoTurnService::class.java)
            return s.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        private const val HOLD_START_MS = 80L
        private const val HOLD_END_MS = 60L
        private const val RULE_COOLDOWN_MS = 3000L    // 同一條規則兩次點擊的最短間隔
        private const val MAX_SAME_RULE_TAPS = 6       // 同一條規則連續點這麼多次、中間都沒轉珠 → 視為卡住
        private const val IDLE_STOP_MS = 180_000L      // 3 分鐘沒有任何進展 → 停止
    }

    private lateinit var wm: WindowManager
    private lateinit var settings: AppSettings
    private lateinit var colorStore: ColorStore
    private lateinit var ruleStore: RuleStore
    private lateinit var model: ColorModel
    private var rules: List<TapRule> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    // 工作列
    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var playBtn: TextView? = null
    private var onceBtn: TextView? = null
    private val foldable = ArrayList<View>()
    private var collapsed = false

    private var pathView: PathOverlayView? = null
    private var recordView: View? = null
    private var calibRoot: FrameLayout? = null
    private var calibView: CalibrationView? = null
    private var calibInfo: TextView? = null
    private var calibFrame: Frame? = null

    // 執行狀態
    private var busy = false
    private var running = false
    private var lastSeen: IntArray? = null
    private var stableCount = 0
    private var lastActed: IntArray? = null
    private var lastActedAt = 0L
    private var lastProgressAt = 0L
    private val ruleTapAt = HashMap<Int, Long>()
    private var lastRule = -1
    private var sameRuleTaps = 0
    private var turnsDone = 0
    private var rulesTapped = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------- 生命週期 ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = AppSettings(this)
        colorStore = ColorStore(this)
        ruleStore = RuleStore(this)
        model = colorStore.load()
        rules = ruleStore.load()
        addPathView()
        addToolbar()
        if (Build.VERSION.SDK_INT < 30) toast("自動截圖需要 Android 11 以上")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() = stopAuto(null)

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (!::wm.isInitialized) return
        stopAuto(null)
        closeCalibration()
        closeRecord()
        for (v in listOf<View?>(bar, pathView)) v?.let { runCatching { wm.removeView(it) } }
        bar = null
        pathView = null
        if (instance === this) instance = null
    }

    /** 設定頁修改顏色學習或點擊規則後呼叫 */
    fun reloadModel() {
        model = colorStore.load()
        rules = ruleStore.load()
    }

    // ---------------- 懸浮視窗 ----------------

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun addPathView() {
        val v = PathOverlayView(this)
        wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false))
        pathView = v
    }

    private fun barButton(label: String, color: String, onClick: (() -> Unit)?): TextView {
        val size = dp(44)
        return TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color))
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, dp(3), 0, dp(3)) }
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun addToolbar() {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.argb(200, 25, 32, 38))
                setStroke(dp(1), Color.argb(160, 255, 255, 255))
            }
        }
        val handle = barButton("⠿", "#455A64", null)
        handle.setOnTouchListener(HandleTouch())
        val play = barButton("▶", "#2E7D32") { toggleAuto() }
        val once = barButton("轉", "#C62828") { onOnceTap() }
        val add = barButton("＋", "#1565C0") { startRecordRule() }
        val cal = barButton("校", "#6A1B9A") { openCalibration() }
        b.addView(handle)
        b.addView(play)
        b.addView(once)
        b.addView(add)
        b.addView(cal)
        foldable.clear()
        foldable.addAll(listOf(once, add, cal))
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            x = resources.displayMetrics.widthPixels - dp(66)
            y = (resources.displayMetrics.heightPixels * 0.14f).toInt()
        }
        wm.addView(b, lp)
        bar = b
        barLp = lp
        playBtn = play
        onceBtn = once
        updateBar()
    }

    private fun setColor(v: TextView?, color: String) {
        (v?.background as? GradientDrawable)?.setColor(Color.parseColor(color))
    }

    private fun updateBar(onceLabel: String? = null) {
        playBtn?.text = if (running) "■" else "▶"
        setColor(playBtn, if (running) "#D32F2F" else "#2E7D32")
        onceBtn?.text = onceLabel ?: "轉"
        setColor(onceBtn, if (busy && !running) "#F57C00" else "#C62828")
        for (v in foldable) v.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    private inner class HandleTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = barLp ?: return false
            val root = bar ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > dp(10)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    collapsed = !collapsed
                    updateBar()
                }
            }
            return true
        }
    }

    private fun checkReady(): Boolean {
        if (calibRoot != null || recordView != null) return false
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return false }
        if (!settings.hasGeometry) {
            toast("第一次使用：先校正盤面位置")
            openCalibration()
            return false
        }
        return true
    }

    // ---------------- 截圖 ----------------

    private fun capture(cb: (Frame?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        pathView?.clear()
        main.postDelayed({ takeShot(cb) }, 60)   // 等路徑從畫面上消失
    }

    @TargetApi(30)
    private fun takeShot(cb: (Frame?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    var frame: Frame? = null
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                        if (sw != null) {
                            val px = IntArray(sw.width * sw.height)
                            sw.getPixels(px, 0, sw.width, 0, 0, sw.width, sw.height)
                            frame = Frame(sw.width, sw.height, px)
                            sw.recycle()
                        }
                    } catch (_: Throwable) {
                    }
                    cb(frame)
                }

                override fun onFailure(errorCode: Int) = cb(null)
            })
        } catch (_: Throwable) {
            cb(null)
        }
    }

    // ---------------- 只轉這一回合 ----------------

    private fun onOnceTap() {
        if (running || busy || !checkReady()) return
        busy = true
        updateBar("…")
        capture { frame ->
            if (frame == null) { finishOnce("截圖失敗"); return@capture }
            val geo = settings.geometry
            val rec = OrbRecognizer.recognize(frame, geo, model)
            val unknown = rec.unknownCount()
            if (unknown > geo.cells / 3) {
                finishOnce("看不出盤面（$unknown 格無法辨識）。請在戰鬥中珠子靜止時再按，或按「校」重新校正")
                return@capture
            }
            if (unknown > 0) toast("$unknown 格無法辨識，路徑會避開它們")
            solveAndAct(rec, geo) { ok -> finishOnce(if (ok) null else "拖曳被中斷") }
        }
    }

    private fun finishOnce(msg: String?) {
        busy = false
        updateBar()
        if (msg != null) toast(msg)
    }

    // ---------------- 自動執行（全自動） ----------------

    private fun toggleAuto() {
        if (running) { stopAuto("已停止自動執行（轉珠 $turnsDone 回合、點擊 $rulesTapped 次）"); return }
        if (busy || !checkReady()) return
        rules = ruleStore.load()
        running = true
        lastSeen = null; stableCount = 0; lastActed = null
        ruleTapAt.clear(); lastRule = -1; sameRuleTaps = 0
        turnsDone = 0; rulesTapped = 0
        lastProgressAt = SystemClock.uptimeMillis()
        updateBar()
        toast(if (rules.isEmpty()) "自動執行：開始（只會轉珠；按「＋」新增點擊規則可自動過關卡）" else "自動執行：開始（${rules.size} 條點擊規則）")
        main.post(autoTick)
    }

    private fun stopAuto(msg: String?) {
        if (!running) return
        running = false
        main.removeCallbacks(autoTick)
        updateBar()
        if (msg != null) toast(msg)
    }

    private val autoTick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (busy) { main.postDelayed(this, 400); return }
            busy = true
            capture { frame -> onAutoFrame(frame) }
        }
    }

    private fun next(delay: Long) {
        busy = false
        updateBar()
        if (running) main.postDelayed(autoTick, delay)
    }

    private fun onAutoFrame(frame: Frame?) {
        if (!running) { busy = false; updateBar(); return }
        if (frame == null) { next(800); return }
        val now = SystemClock.uptimeMillis()

        // 1) 戰鬥中：盤面靜止就轉珠
        val geo = settings.geometry
        val rec = OrbRecognizer.recognize(frame, geo, model)
        val board = rec.colors.copyOf(geo.cells)
        val ready = rec.minConfidence() >= OrbRecognizer.READY_CONF &&
            NativeSolver.countCombos(board, geo.rows) == 0
        val alreadyDone = OrbRecognizer.sameBoard(board, lastActed) && now - lastActedAt < 5000
        if (ready && !alreadyDone) {
            if (OrbRecognizer.sameBoard(board, lastSeen)) stableCount++ else { stableCount = 1; lastSeen = board }
            if (stableCount >= 2) {
                lastActed = board
                lastActedAt = now
                lastProgressAt = now
                stableCount = 0
                sameRuleTaps = 0
                solveAndAct(rec, geo) { ok ->
                    if (ok) turnsDone++
                    next(if (ok) 2500 else 1000)
                }
                return
            }
            next(500)
            return
        }
        if (!ready) { stableCount = 0; lastSeen = null }

        // 2) 戰鬥外：依點擊規則自動點擊
        if (!ready && rules.isNotEmpty()) {
            val idx = TapMatcher.firstMatch(frame, rules) { i -> now - (ruleTapAt[i] ?: 0L) < RULE_COOLDOWN_MS }
            if (idx >= 0) {
                sameRuleTaps = if (idx == lastRule) sameRuleTaps + 1 else 1
                lastRule = idx
                if (sameRuleTaps > MAX_SAME_RULE_TAPS) {
                    busy = false
                    stopAuto("畫面好像卡住了（規則 ${idx + 1} 連續點了 $MAX_SAME_RULE_TAPS 次），已停止")
                    return
                }
                ruleTapAt[idx] = now
                lastProgressAt = now
                val r = rules[idx]
                tap(r.x, r.y) { ok ->
                    if (ok) rulesTapped++
                    next(1500)
                }
                return
            }
        }

        if (now - lastProgressAt > IDLE_STOP_MS) {
            busy = false
            stopAuto("3 分鐘沒有可轉的盤面或可點的畫面，已停止自動執行")
            return
        }
        next(600)
    }

    // ---------------- 求解、拖曳、點擊 ----------------

    private fun solveAndAct(rec: Recognition, geo: BoardGeometry, done: (Boolean) -> Unit) {
        // 無法辨識的格子標成「冰封」：求解時不碰、也不算消除
        val cells = IntArray(geo.cells) { i -> if (rec.isUnknown(i)) rec.colors[i] or 0x20 else rec.colors[i] }
        val beam = settings.beam
        val maxPath = settings.maxPathFor(geo.rows)
        val dirs = settings.dirs
        worker.execute {
            val res = SolveResult.from(NativeSolver.solve(cells, geo.rows, beam, maxPath, dirs))
            main.post {
                if (!res.found || res.dirs.isEmpty()) {
                    toast("找不到可行路徑")
                    done(false)
                    return@post
                }
                val pts = res.cellsOnPath().map { PointF(geo.centerX(it), geo.centerY(it)) }
                pathView?.show(pts)
                updateBar("${res.combos}")
                if (settings.autoDrag) {
                    main.postDelayed({
                        drag(pts, settings.stepMs.toLong()) { ok ->
                            pathView?.clear()
                            done(ok)
                        }
                    }, settings.previewMs)
                } else {
                    main.postDelayed({ pathView?.clear() }, 8000)
                    done(true)
                }
            }
        }
    }

    /** 連續拖曳：按住起點 → 依序經過每格中心 → 在終點停一下再放開 */
    private fun drag(points: List<PointF>, stepMs: Long, done: (Boolean) -> Unit) {
        if (points.size < 2) { done(false); return }
        val start = points[0]
        val hold = Path().apply { moveTo(start.x, start.y); lineTo(start.x + 1f, start.y) }
        val s1 = GestureDescription.StrokeDescription(hold, 0, HOLD_START_MS, true)
        dispatch(s1) { ok1 ->
            if (!ok1) { done(false); return@dispatch }
            val move = Path().apply {
                moveTo(start.x + 1f, start.y)
                for (k in 1 until points.size) lineTo(points[k].x, points[k].y)
            }
            val s2 = s1.continueStroke(move, 0, stepMs * (points.size - 1), true)
            dispatch(s2) { ok2 ->
                if (!ok2) { done(false); return@dispatch }
                val end = points.last()
                val release = Path().apply { moveTo(end.x, end.y); lineTo(end.x, end.y + 1f) }
                dispatch(s2.continueStroke(release, 0, HOLD_END_MS, false), done)
            }
        }
    }

    private fun tap(x: Float, y: Float, done: (Boolean) -> Unit) {
        val p = Path().apply { moveTo(x, y) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 60), done)
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription, cb: (Boolean) -> Unit) {
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = cb(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = cb(false)
        }, null)
        if (!accepted) cb(false)
    }

    // ---------------- 新增點擊規則 ----------------

    private fun startRecordRule() {
        if (calibRoot != null || recordView != null || busy) return
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return }
        stopAuto(null)
        busy = true
        capture { frame ->
            busy = false
            updateBar()
            if (frame == null) { toast("截圖失敗"); return@capture }
            showRecordOverlay(frame)
        }
    }

    private fun showRecordOverlay(frame: Frame) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(70, 0, 0, 0)) }
        val hint = TextView(this).apply {
            text = "點一下要自動按的位置（例如「再次挑戰」「確定」「出戰」）\n之後只要畫面上出現一樣的東西，自動執行時就會幫你點。\n點這段文字可取消。"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, 21, 101, 192))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { closeRecord(); toast("已取消") }
        }
        root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            topMargin = dp(40)
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val size = TapMatcher.defaultSize(frame)
                val sig = TapMatcher.signature(frame, e.rawX, e.rawY, size)
                closeRecord()
                if (sig == null) {
                    toast("太靠近螢幕邊緣，請點按鈕中間")
                } else {
                    val n = ruleStore.add(TapRule(e.rawX, e.rawY, size, sig))
                    rules = ruleStore.load()
                    toast("已新增點擊規則 $n。現在請自己按一下那個按鈕，到下一個畫面再繼續新增")
                }
            }
            true
        }
        wm.addView(root, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
        recordView = root
    }

    private fun closeRecord() {
        recordView?.let { runCatching { wm.removeView(it) } }
        recordView = null
    }

    // ---------------- 校正 ----------------

    private fun openCalibration() {
        if (calibRoot != null || recordView != null || busy) return
        stopAuto(null)
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return }
        busy = true
        updateBar("…")
        capture { frame ->
            busy = false
            updateBar()
            if (frame == null) { toast("截圖失敗"); return@capture }
            calibFrame = frame
            showCalibration(frame)
        }
    }

    private fun showCalibration(frame: Frame) {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val root = FrameLayout(themed)
        val view = CalibrationView(themed)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 20, 26, 31))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val info = TextView(themed).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        fun btn(label: String, onClick: (Button) -> Unit) = Button(themed).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setOnClickListener { onClick(this) }
        }
        val rowA = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        val rowB = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        val wlp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val startGeo = if (settings.hasGeometry) settings.geometry else defaultGeometry(frame, 5)
        rowA.addView(btn("自動偵測") { autoDetectInCalibration() }, wlp())
        rowA.addView(btn("列數 ${startGeo.rows}") { b -> cycleRows(b) }, wlp())
        rowA.addView(btn("重新截圖") { recaptureCalibration() }, wlp())
        rowB.addView(btn("儲存並學習") { saveCalibration() }, wlp())
        rowB.addView(btn("關閉") { closeCalibration() }, wlp())
        panel.addView(info)
        panel.addView(rowA)
        panel.addView(rowB)
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            topMargin = dp(40)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })

        view.onGeometryChanged = { recognizeInCalibration() }
        view.onLabelsChanged = { updateCalibInfo() }
        view.geo = startGeo

        wm.addView(root, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
        calibRoot = root
        calibView = view
        calibInfo = info
        if (!settings.hasGeometry) autoDetectInCalibration() else recognizeInCalibration()
    }

    private fun defaultGeometry(frame: Frame, rows: Int): BoardGeometry {
        val cell = frame.width / 6f
        return BoardGeometry(0f, frame.height - frame.height * 0.04f - cell * rows, cell, rows)
    }

    private fun recognizeInCalibration() {
        val v = calibView ?: return
        val f = calibFrame ?: return
        val rec = OrbRecognizer.recognize(f, v.geo, model)
        v.resetLabels()
        for (i in 0 until v.geo.cells) {
            v.labels[i] = rec.colors[i]
            v.conf[i] = rec.confidence[i]
        }
        v.invalidate()
        updateCalibInfo()
    }

    private fun updateCalibInfo() {
        val v = calibView ?: return
        val n = v.geo.cells
        val unknown = (0 until n).count { !v.edited[it] && v.conf[it] < OrbRecognizer.UNKNOWN_CONF }
        val fixed = (0 until n).count { v.edited[it] }
        calibInfo?.text = "把格線對齊盤面（拖曳移動、右下圓點縮放），點格子可修正顏色。\n" +
            "無法辨識 $unknown 格・已修正 $fixed 格・累計學習 ${colorStore.learnedSamples} 格"
    }

    private fun autoDetectInCalibration() {
        val v = calibView ?: return
        val f = calibFrame ?: return
        val g = OrbRecognizer.autoDetect(f, v.geo.rows, model)
        if (g == null) {
            toast("找不到盤面：請在戰鬥畫面（珠子靜止時）再試，或手動拖曳格線")
            recognizeInCalibration()
            return
        }
        v.geo = g
        recognizeInCalibration()
        toast("已偵測到盤面")
    }

    private fun cycleRows(b: Button) {
        val v = calibView ?: return
        val r = if (v.geo.rows >= 8) 5 else v.geo.rows + 1
        v.geo = v.geo.withRows(r)
        b.text = "列數 $r"
        recognizeInCalibration()
    }

    private fun recaptureCalibration() {
        val root = calibRoot ?: return
        root.visibility = View.INVISIBLE
        main.postDelayed({
            capture { frame ->
                root.visibility = View.VISIBLE
                if (frame != null) {
                    calibFrame = frame
                    recognizeInCalibration()
                } else {
                    toast("截圖失敗")
                }
            }
        }, 120)
    }

    private fun saveCalibration() {
        val v = calibView ?: return
        val f = calibFrame ?: return
        settings.geometry = v.geo
        // 學習：使用者修正過的格子，加上原本就有信心的格子
        val rec = OrbRecognizer.recognize(f, v.geo, model)
        val feats = ArrayList<FloatArray>()
        val labels = ArrayList<Int>()
        for (i in 0 until v.geo.cells) {
            val k = v.labels[i]
            if (k !in 0..5) continue
            if (!v.edited[i] && v.conf[i] < OrbRecognizer.READY_CONF) continue
            feats.add(rec.features[i])
            labels.add(k)
        }
        val used = model.learn(feats, labels)
        colorStore.save(model, used)
        toast("已儲存盤面位置，學習了 $used 格顏色")
        closeCalibration()
    }

    private fun closeCalibration() {
        calibRoot?.let { runCatching { wm.removeView(it) } }
        calibRoot = null
        calibView = null
        calibInfo = null
        calibFrame = null
    }
}
