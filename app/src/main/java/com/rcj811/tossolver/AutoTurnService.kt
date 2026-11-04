package com.rcj811.tossolver

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.accessibilityservice.GestureDescription
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
 * 自動轉珠服務（無障礙服務）
 *  - 懸浮圓鈕：點一下 = 截圖 → 辨識 → 求解 → 自動拖曳（或只畫路徑）；長按 = 校正盤面
 *  - 連續模式：盤面穩定就自動出手，再點一次圓鈕停止
 * 截圖只在手機上處理，不儲存、不上傳。
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
    }

    private lateinit var wm: WindowManager
    private lateinit var settings: AppSettings
    private lateinit var colorStore: ColorStore
    private lateinit var model: ColorModel
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var pathView: PathOverlayView? = null
    private var calibRoot: FrameLayout? = null
    private var calibView: CalibrationView? = null
    private var calibInfo: TextView? = null
    private var calibFrame: Frame? = null

    private var busy = false
    private var looping = false
    private var lastSeen: IntArray? = null
    private var stableCount = 0
    private var lastActed: IntArray? = null
    private var lastActedAt = 0L
    private var lastProgressAt = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------- 生命週期 ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = AppSettings(this)
        colorStore = ColorStore(this)
        model = colorStore.load()
        addPathView()
        addBubble()
        if (Build.VERSION.SDK_INT < 30) toast("自動截圖需要 Android 11 以上")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() = stopLoop()

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
        stopLoop()
        closeCalibration()
        for (v in listOf<View?>(bubble, pathView)) v?.let { runCatching { wm.removeView(it) } }
        bubble = null
        pathView = null
        if (instance === this) instance = null
    }

    /** 設定頁修改後呼叫，讓服務讀到新的顏色學習結果 */
    fun reloadModel() { model = colorStore.load() }

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

    private fun addBubble() {
        val size = dp(56)
        val v = TextView(this).apply {
            text = "轉"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            elevation = dp(6).toFloat()
        }
        val lp = overlayParams(size, size, true).apply {
            x = resources.displayMetrics.widthPixels - size - dp(8)
            y = (resources.displayMetrics.heightPixels * 0.22f).toInt()
        }
        v.setOnTouchListener(BubbleTouch())
        wm.addView(v, lp)
        bubble = v
        bubbleLp = lp
        updateBubble()
    }

    private fun updateBubble(label: String? = null) {
        val v = bubble ?: return
        v.text = label ?: if (looping) "停" else "轉"
        val color = when {
            looping -> "#2E7D32"
            busy -> "#F57C00"
            else -> "#C62828"
        }
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
            setStroke(dp(2), Color.WHITE)
        }
        v.alpha = 0.92f
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleLp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    downAt = SystemClock.uptimeMillis(); dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > dp(10)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        if (SystemClock.uptimeMillis() - downAt > 600) openCalibration() else onBubbleTap()
                    }
                }
            }
            return true
        }
    }

    private fun onBubbleTap() {
        if (calibRoot != null) return
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return }
        if (!settings.hasGeometry) {
            toast("第一次使用：先校正盤面位置")
            openCalibration()
            return
        }
        if (settings.continuous) {
            if (looping) stopLoop() else startLoop()
        } else {
            runOnce()
        }
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

    // ---------------- 單次 / 連續 ----------------

    private fun runOnce() {
        if (busy) return
        busy = true
        updateBubble("…")
        capture { frame ->
            if (frame == null) { finishTurn("截圖失敗"); return@capture }
            val geo = settings.geometry
            val rec = OrbRecognizer.recognize(frame, geo, model)
            val unknown = rec.unknownCount()
            if (unknown > geo.cells / 3) {
                finishTurn("看不出盤面（$unknown 格無法辨識）。請確認在戰鬥畫面，或長按圓鈕重新校正")
                return@capture
            }
            if (unknown > 0) toast("$unknown 格無法辨識，路徑會避開它們")
            solveAndAct(rec, geo) { ok -> finishTurn(if (ok) null else "拖曳被中斷") }
        }
    }

    private fun finishTurn(msg: String?) {
        busy = false
        updateBubble()
        if (msg != null) toast(msg)
    }

    private fun startLoop() {
        looping = true
        lastSeen = null; stableCount = 0; lastActed = null
        lastProgressAt = SystemClock.uptimeMillis()
        updateBubble()
        toast("連續自動：開始（再點一次圓鈕停止）")
        main.post(loopTick)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        main.removeCallbacks(loopTick)
        updateBubble()
    }

    private val loopTick: Runnable = object : Runnable {
        override fun run() {
            if (!looping) return
            if (busy) { main.postDelayed(this, 400); return }
            busy = true
            capture { frame -> onLoopFrame(frame) }
        }
    }

    private fun scheduleLoop(delay: Long) {
        busy = false
        updateBubble()
        if (looping) main.postDelayed(loopTick, delay)
    }

    private fun onLoopFrame(frame: Frame?) {
        if (!looping) { busy = false; updateBubble(); return }
        if (frame == null) { scheduleLoop(800); return }
        val now = SystemClock.uptimeMillis()
        val geo = settings.geometry
        val rec = OrbRecognizer.recognize(frame, geo, model)
        val board = rec.colors.copyOf(geo.cells)
        // 出手條件：每格都有信心、盤面沒有現成可消（動畫已結束）、連續兩次截圖相同
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
                updateBubble("…")
                solveAndAct(rec, geo) { ok -> scheduleLoop(if (ok) 2500 else 1000) }
                return
            }
        } else if (!ready) {
            stableCount = 0
            lastSeen = null
        }
        if (now - lastProgressAt > 120_000) {
            busy = false
            stopLoop()
            toast("2 分鐘沒有可轉的盤面，已停止連續模式")
            return
        }
        scheduleLoop(600)
    }

    // ---------------- 求解與拖曳 ----------------

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
                updateBubble("${res.combos}")
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

    private fun dispatch(stroke: GestureDescription.StrokeDescription, cb: (Boolean) -> Unit) {
        val g = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = cb(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = cb(false)
        }, null)
        if (!accepted) cb(false)
    }

    // ---------------- 校正 ----------------

    private fun openCalibration() {
        if (calibRoot != null || busy) return
        stopLoop()
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return }
        busy = true
        updateBubble("…")
        capture { frame ->
            busy = false
            updateBubble()
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
        rowA.addView(btn("自動偵測") { autoDetectInCalibration() }, wlp())
        rowA.addView(btn("列數 ${settings.geometry.rows}") { b -> cycleRows(b) }, wlp())
        rowA.addView(btn("重新截圖") { recaptureCalibration() }, wlp())
        rowB.addView(btn("儲存並學習") { saveCalibration() }, wlp())
        rowB.addView(btn("關閉") { closeCalibration() }, wlp())
        panel.addView(info)
        panel.addView(rowA)
        panel.addView(rowB)
        val plp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            topMargin = dp(40)
            leftMargin = dp(8)
            rightMargin = dp(8)
        }
        root.addView(panel, plp)

        view.onGeometryChanged = { recognizeInCalibration() }
        view.onLabelsChanged = { updateCalibInfo() }
        view.geo = if (settings.hasGeometry) settings.geometry else defaultGeometry(frame, settings.geometry.rows)

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
