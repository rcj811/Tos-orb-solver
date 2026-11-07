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
import android.os.VibrationEffect
import android.os.Vibrator
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 自動轉珠服務（無障礙服務）＋懸浮工作列
 *
 * 工作列按鈕：
 *  （橫向工作列，預設在畫面上方中央，位置會記住）
 *  ⠿  拖曳移動；點一下收合 / 展開
 *  ▶  自動執行（全自動）：戰鬥中自動轉珠；戰鬥外依「點擊規則」自動點擊；再按一次（■）停止
 *  轉  只轉這一回合
 *  ＋  新增點擊規則：點一下畫面上要自動按的位置（例如「再次挑戰」）
 *  校  校正盤面位置與顏色
 *  關  設定並記住這一關的機制（拼圖盾、Combo 盾…）；之後遇到同一關會自動判斷套用
 *  ✕  連按兩下關閉自轉珠
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

        private const val HOLD_START_MS = 60L
        private const val HOLD_END_MS = 40L
        private const val RULE_COOLDOWN_MS = 3000L    // 同一條規則兩次點擊的最短間隔
        private const val MAX_SAME_RULE_TAPS = 6       // 同一條規則連續點這麼多次、中間都沒轉珠 → 視為卡住
        private const val IDLE_STOP_MS = 180_000L      // 3 分鐘沒有任何進展 → 停止
    }

    private lateinit var wm: WindowManager
    private lateinit var settings: AppSettings
    private lateinit var colorStore: ColorStore
    private lateinit var ruleStore: RuleStore
    private lateinit var presetStore: StagePresetStore
    private var presets: MutableList<StagePreset> = ArrayList()
    private var stage = StageRules()        // 目前套用的機制（依畫面自動判斷）
    private var activePreset = -1           // 目前判斷出的關卡（-1 = 一般關卡）
    private var lastGoalsOk = true
    private var unmetStreak = 0
    private var onlineLearned = 0        // 自我學習：累積但還沒存檔的顏色樣本數
    private var rulesDirty = 0
    private var lastNoMove = false        // 上一次求解：整盤都不能碰
    private val bossReadTried = ArrayList<FloatArray>()   // 這次執行已嘗試讀過技能的關卡（敵人畫面特徵）
    private var lastActionAt = 0L     // 上次轉珠或點擊的時間（用來提示「在等什麼」）
    private var lastHintAt = 0L
    private lateinit var model: ColorModel
    private var stateModel = StateModel()
    private var rules: List<TapRule> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    // 工作列
    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var playBtn: TextView? = null
    private var onceBtn: TextView? = null
    private var stageBtn: TextView? = null
    private var stageRoot: FrameLayout? = null
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
    private var lastBoardAt = 0L      // 上次看到轉珠盤面的時間
    private val ruleTapAt = HashMap<Int, Long>()
    private var lastRule = -1
    private var sameRuleTaps = 0
    private var turnsDone = 0
    private var rulesTapped = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, if (msg.length > 24) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // ---------------- 生命週期 ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = AppSettings(this)
        colorStore = ColorStore(this)
        ruleStore = RuleStore(this)
        presetStore = StagePresetStore(this)
        presets = presetStore.load()
        model = colorStore.load()
        stateModel = colorStore.loadStates()
        OrbTypeStore(this).apply()
        rules = ruleStore.load()
        addPathView()
        Toast.makeText(this, "自轉珠 ${appVersion(this)} 已啟動", Toast.LENGTH_LONG).show()
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
        closeStagePanel()
        for (v in listOf<View?>(bar, pathView)) v?.let { runCatching { wm.removeView(it) } }
        bar = null
        pathView = null
        if (instance === this) instance = null
    }

    /** 設定頁修改顏色學習或點擊規則後呼叫 */
    fun reloadModel() {
        model = colorStore.load()
        stateModel = colorStore.loadStates()
        OrbTypeStore(this).apply()
        rules = ruleStore.load()
        presets = presetStore.load()
        if (activePreset >= presets.size) { activePreset = -1; stage = StageRules() }
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
        val size = dp(40)
        return TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color))
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(dp(3), 0, dp(3), 0) }
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun addToolbar() {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        val stg = barButton("關", "#546E7A") { openStagePanel() }
        val close = barButton("✕", "#37474F") { onCloseTap() }
        b.addView(handle)
        b.addView(play)
        b.addView(once)
        b.addView(add)
        b.addView(cal)
        b.addView(stg)
        b.addView(close)
        foldable.clear()
        foldable.addAll(listOf(once, add, cal, stg))
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            // 預設：畫面上方中央（7 顆按鈕約 332dp 寬）；拖曳過就用上次的位置
            val dm = resources.displayMetrics
            x = if (settings.barX >= 0) settings.barX else maxOf(0, (dm.widthPixels - dp(332)) / 2)
            y = if (settings.barY >= 0) settings.barY else (dm.heightPixels * 0.05f).toInt()
        }
        wm.addView(b, lp)
        bar = b
        barLp = lp
        playBtn = play
        onceBtn = once
        stageBtn = stg
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
        setColor(stageBtn, if (activePreset >= 0 && !stage.isEmpty()) "#F9A825" else "#546E7A")
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
                } else {
                    settings.barX = maxOf(0, lp.x)
                    settings.barY = maxOf(0, lp.y)
                }
            }
            return true
        }
    }

    private fun checkReady(): Boolean {
        if (calibRoot != null || recordView != null || stageRoot != null) return false
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

    /** 暫時隱藏工作列再截圖（判斷關卡、錄製規則、校正用；每回合最多一次，避免閃爍） */
    private fun captureClean(cb: (Frame?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        pathView?.clear()
        val b = bar
        b?.visibility = View.INVISIBLE
        main.postDelayed({
            takeShot { f ->
                b?.visibility = View.VISIBLE
                cb(f)
            }
        }, 90)
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

    // ---------------- 關卡自動判斷 ----------------

    /** 依敵人區畫面判斷是哪一關，套用記住的機制；沒記過的關卡照一般盤打 */
    private fun applyStageFor(frame: Frame?, geo: BoardGeometry) {
        val prev = activePreset
        val sig = if (frame == null) null else StageMatcher.signature(frame, geo)
        val (idx, _) = StageMatcher.best(sig, presets)
        activePreset = idx
        if (idx >= 0 && sig != null) presets[idx].learn(sig)   // 自我學習：記住這關這次的樣子
        stage = if (idx >= 0) StageRules().apply { copyFrom(presets[idx].rules) } else StageRules()
        if (idx != prev) {
            unmetStreak = 0
            if (idx >= 0) toast("自動判斷：記住的關卡 ${idx + 1}（${stage.summary()}）")
            else if (prev >= 0) toast("自動判斷：一般關卡")
        }
        updateBar()
    }

    /** 真的過不了：停止並震動提醒使用者手動處理 */
    private fun alertManual(msg: String) {
        stopAuto(null)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        runCatching {
            @Suppress("DEPRECATION")
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250, 150, 400), -1))
        }
    }

    /** 把自我學習的結果存檔 */
    private fun flushLearning() {
        if (onlineLearned > 0) { colorStore.save(model, onlineLearned); onlineLearned = 0 }
        if (rulesDirty > 0) { ruleStore.save(rules); rulesDirty = 0 }
        if (presets.isNotEmpty()) presetStore.save(presets)
    }

    // ---------------- 讀取王的技能（OCR） ----------------

    private fun alreadyTriedRead(sig: FloatArray): Boolean =
        StageMatcher.best(sig, bossReadTried.map { StagePreset(it, StageRules()) }).first >= 0

    /** 王在畫面上的位置：敵人區中央 */
    private fun bossPoint(frame: Frame, geo: BoardGeometry): PointF {
        val top = frame.height * 0.06f
        val bottom = maxOf(top + geo.cell, geo.top - geo.cell * 1.3f)
        return PointF(frame.width / 2f, (top + bottom) / 2f)
    }

    /** 自動執行：長按王 → 截圖讀文字 → 點一下關掉視窗 → 記住這關的條件 */
    private fun autoReadBoss(frame: Frame, geo: BoardGeometry, sig: FloatArray) {
        val p = bossPoint(frame, geo)
        toast("新關卡：讀取王的技能中…")
        val press = Path().apply { moveTo(p.x, p.y) }
        dispatch(GestureDescription.StrokeDescription(press, 0, 850)) {
            main.postDelayed({
                captureClean { shot ->
                    val finish = { text: String? ->
                        // 點一下盤面中央關掉技能視窗（沒有視窗時，點一下盤面也不會移動珠子）
                        tap(frame.width / 2f, geo.top + geo.cell * geo.rows / 2f) {
                            main.postDelayed({ onBossRead(sig, SkillParser.parse(text ?: ""), auto = true) }, 700)
                        }
                    }
                    if (shot == null) finish(null) else SkillOcr.read(shot) { finish(it) }
                }
            }, 900)
        }
    }

    /** 讀到技能後：記住關卡（合併使用者畫過的格子），並決定能不能繼續自動 */
    private fun onBossRead(sig: FloatArray, parse: SkillParser.Result, auto: Boolean) {
        if (!parse.found) {
            if (auto) { toast("沒讀到王的技能，這關先照一般盤打"); next(300) }
            return
        }
        val list = presetStore.load()
        val (idx, _) = StageMatcher.best(sig, list)
        val rules = if (idx >= 0) StageRules().apply { copyFrom(list[idx].rules); mergeFrom(parse.rules) } else parse.rules
        if (idx >= 0) list[idx] = list[idx].withRules(rules, sig) else list.add(StagePreset(sig, rules))
        presetStore.save(list)
        presets = list
        activePreset = -1
        stage = StageRules()
        updateBar()
        Toast.makeText(this, "讀到王的技能：${parse.skills.joinToString("、")}\n${parse.summary()}", Toast.LENGTH_LONG).show()
        if (!auto) return
        // 需要補充的機制：提示，但先照已讀到的條件繼續轉，不停住
        if (parse.manual.isNotEmpty()) toast("這個王有需要你補充的機制：${parse.manual.joinToString("、")}（可之後按「關」補充），先照已讀到的條件轉")
        next(400)   // 重新截圖：這次就會套用剛記住的條件
    }

    // ---------------- ✕ 關閉 ----------------

    private fun onCloseTap() {
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("關閉自轉珠？")
            .setMessage("會停止自動執行、關掉工作列，並完全結束自轉珠程式（學過的資料會保留）。\n要再使用，打開「自轉珠」App 重新開啟無障礙設定即可。")
            .setPositiveButton("確認關閉") { _, _ -> shutdown() }
            .setNegativeButton("取消", null)
            .create()
        dlg.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dlg.show()
    }

    /** 真正關閉：存好學習資料 → 關掉所有畫面 → 停用無障礙服務 → 關掉 App 畫面 → 結束程式 */
    private fun shutdown() {
        stopAuto(null)
        flushLearning()
        cleanup()
        runCatching { worker.shutdownNow() }
        runCatching {
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach { it.finishAndRemoveTask() }
        }
        Toast.makeText(this, "已完全關閉自轉珠", Toast.LENGTH_SHORT).show()
        runCatching { disableSelf() }
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
    }

    // ---------------- 只轉這一回合 ----------------

    /** forced 不為 null：直接用這組條件（關卡面板按「完成」時），不重新比對關卡 */
    private fun onOnceTap(forced: StageRules? = null) {
        if (running || busy || !checkReady()) return
        busy = true
        updateBar("…")
        captureClean { frame ->
            if (frame == null) { finishOnce("截圖失敗"); return@captureClean }
            val geo = settings.geometry
            if (forced != null) stage = StageRules().apply { copyFrom(forced) } else applyStageFor(frame, geo)
            val rec = OrbRecognizer.recognize(frame, geo, model, stateModel)
            val unknown = rec.unknownCount()
            if (unknown > geo.cells / 2) {
                finishOnce("看不出盤面（$unknown 格無法辨識）。請在戰鬥中珠子靜止時再按，或按「校」重新校正")
                return@captureClean
            }
            if (unknown > 0) toast("$unknown 格無法辨識，路徑會避開它們")
            val go = {
                val why = if (stage.isEmpty()) null else stage.firstBatchImpossible(feasColors(rec))
                // 條件在這盤做不到：照樣轉，用最接近條件的轉法（不要停住不動）
                if (why != null) toast("$why，照最接近的轉法轉")
                solveAndAct(rec, geo) { ok -> finishOnce(if (ok) null else "拖曳被中斷") }
            }
            // 面板按「完成」：照你設定的數字；按「轉」：自動讀敵人身上的 NUM 數字
            if (forced != null && forced.firstOrbsExact > 0) go() else withNum(frame, geo) { go() }
        }
    }

    /** 讀 NUM 盾數字；讀到就套用到這一回合（不存進關卡記錄，因為每回合都會變） */
    private fun withNum(frame: Frame, geo: BoardGeometry, then: () -> Unit) {
        SkillOcr.readNum(frame, geo) { n ->
            if (n != null) {
                if (stage.firstOrbsExact != n) toast("讀到 NUM 盾：首消剛好 $n 粒")
                stage = StageRules().apply { copyFrom(stage); firstOrbsExact = n }
            } else if (stage.firstOrbsExact > 0) {
                // 沒有 NUM 盾（或讀不到）：不沿用舊數字，避免打錯
                stage = StageRules().apply { copyFrom(stage); firstOrbsExact = 0 }
            }
            updateBar()
            then()
        }
    }

    /** 判斷條件可不可能時用的顏色：標了必消的珠用辨識出的顏色、標了不消的當不能消 */
    private fun feasColors(rec: Recognition): IntArray {
        val c = rec.dissolvableColors()
        for (i in c.indices) {
            if (i >= StageRules.MAX_CELLS) break
            if (stage.cellFlags[i] and StageRules.MUST_ORB != 0) c[i] = rec.colors[i]
            if (stage.cellFlags[i] and StageRules.NO_ORB != 0) c[i] = -1
        }
        return c
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
        turnsDone = 0; rulesTapped = 0; unmetStreak = 0
        bossReadTried.clear()
        lastActionAt = SystemClock.uptimeMillis(); lastHintAt = 0L
        presets = presetStore.load()
        lastProgressAt = SystemClock.uptimeMillis()
        lastBoardAt = SystemClock.uptimeMillis()
        updateBar()
        val stageText = if (!stage.isEmpty()) "\n照關卡條件轉珠：${stage.summary()}" else ""
        toast("[${appVersion(this)}] " + (if (rules.isEmpty()) "自動執行：開始（只會轉珠；按「＋」新增點擊規則可自動過關卡）" else "自動執行：開始（${rules.size} 條點擊規則）") + stageText)
        main.post(autoTick)
    }

    private fun stopAuto(msg: String?) {
        if (!running) return
        running = false
        flushLearning()
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
        //    和「轉」一樣容許少數格子認不出來（當障礙避開）；靜止判斷只比較認得出來的格子
        val geo = settings.geometry
        val rec = OrbRecognizer.recognize(frame, geo, model, stateModel)
        val board = OrbRecognizer.maskedBoard(rec)
        val unknown = rec.unknownCount()
        val solveCells = rec.solverCells()
        val pending = NativeSolver.countCombos(solveCells, geo.rows)
        // 遊戲的盤面本來就可能有現成的三連（回合結束才一起消），不能當成「還在落珠」。
        // 改成只看是否靜止；有現成三連時多確認一張截圖，避免抓到消除動畫中間的畫面
        val ready = unknown <= OrbRecognizer.maxUnknown(geo.cells)
        if (ready) lastBoardAt = now
        // 全部認得出來（沒有問號）→ 連續兩張一樣就馬上轉；有認不出的格子又有現成三連時多確認一張
        val needStable = if (unknown > 0 && pending > 0) 3 else 2
        val alreadyDone = OrbRecognizer.sameMasked(board, lastActed) && now - lastActedAt < 5000
        val waitReason = when {
            unknown > OrbRecognizer.maxUnknown(geo.cells) -> "看不到盤面（$unknown 格認不出來）"
            alreadyDone -> "剛轉過這盤，等新盤面"
            else -> "等盤面靜止"
        }
        if (now - lastActionAt > 8000 && now - lastHintAt > 12000) {
            lastHintAt = now
            toast("自動執行中，等待：$waitReason" +
                if (!ready && rules.isEmpty()) "（戰鬥外沒有點擊規則可用）" else "")
        }
        if (ready && !alreadyDone) {
            if (OrbRecognizer.sameMasked(board, lastSeen)) stableCount++ else { stableCount = 1 }
            lastSeen = board
            if (stableCount >= needStable) {
                lastActed = board
                lastActedAt = now
                lastProgressAt = now
                lastActionAt = now
                stableCount = 0
                sameRuleTaps = 0
                // 自我學習：用非常有把握的普通珠微調顏色
                run {
                    val fs = ArrayList<FloatArray>()
                    val ls = ArrayList<Int>()
                    for (i in 0 until geo.cells) {
                        if (rec.confidence[i] >= 0.85f && rec.states[i] == OrbState.NORMAL) { fs.add(rec.features[i]); ls.add(rec.colors[i]) }
                    }
                    onlineLearned += model.learnOnline(fs, ls)
                    if (onlineLearned >= 200) { colorStore.save(model, onlineLearned); onlineLearned = 0 }
                }
                // 出手前用乾淨截圖判斷關卡、套用機制
                captureClean { clean ->
                    if (!running) { busy = false; updateBar(); return@captureClean }
                    applyStageFor(clean ?: frame, geo)
                    // 沒記過的關卡：自動長按王讀取技能，記住後重新判斷這盤
                    if (activePreset < 0 && settings.autoReadBoss && clean != null) {
                        val sig = StageMatcher.signature(clean, geo)
                        if (sig != null && !alreadyTriedRead(sig)) {
                            bossReadTried.add(sig)
                            autoReadBoss(clean, geo, sig)
                            return@captureClean
                        }
                    }
                    // NUM 盾數字每回合會變：每回合都重新讀敵人身上的數字
                    withNum(clean ?: frame, geo) num@{
                    if (!running) { busy = false; updateBar(); return@num }
                    // 條件在這盤數學上做不到：照樣轉，用最接近條件的轉法（不要停住不動）
                    val why = if (stage.isEmpty()) null else stage.firstBatchImpossible(feasColors(rec))
                    if (why != null) toast("$why，照最接近的轉法轉")
                    solveAndAct(rec, geo) { ok ->
                        if (lastNoMove) {
                            busy = false
                            alertManual("盤面全部是不能碰的珠子，自動執行已停止，請手動處理（或到設定把這類珠子改成「可以碰」）")
                            return@solveAndAct
                        }
                        if (ok) turnsDone++
                        unmetStreak = if (!lastGoalsOk) unmetStreak + 1 else 0
                        next(if (ok) 2500 else 1000)
                    }
                    }
                }
                return
            }
            next(500)
            return
        }
        if (!ready) { stableCount = 0; lastSeen = null }

        // 2) 戰鬥外：先看停止畫面（戰敗、體力不足…），再依點擊規則自動點擊
        if (!ready && rules.isNotEmpty()) {
            val stopIdx = TapMatcher.firstMatch(frame, rules) { i -> rules[i].action != TapRule.ACTION_STOP }
            if (stopIdx >= 0) {
                busy = false
                alertManual("看到停止畫面（規則 ${stopIdx + 1}），已停止自動執行，請手動處理")
                return
            }
            val idx = TapMatcher.firstMatch(frame, rules) { i ->
                rules[i].action != TapRule.ACTION_TAP || now - (ruleTapAt[i] ?: 0L) < RULE_COOLDOWN_MS
            }
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
                lastActionAt = now
                val r = rules[idx]
                TapMatcher.learn(frame, r)                    // 自我學習：按鈕外觀小幅變化也認得
                if (++rulesDirty >= 5) { ruleStore.save(rules); rulesDirty = 0 }
                tap(r.x, r.y) { ok ->
                    if (ok) rulesTapped++
                    next(1500)
                }
                return
            }
        }

        // 偵測不到轉珠盤面：自動暫停（沒有點擊規則 8 秒；有點擊規則時給戰鬥外畫面 30 秒）
        val noBoardMs = if (rules.isEmpty()) 8_000L else 30_000L
        if (!ready && now - lastBoardAt > noBoardMs && now - lastActionAt > noBoardMs) {
            busy = false
            stopAuto("沒有偵測到轉珠盤面，自動轉珠已暫停（回到戰鬥後按 ▶ 繼續）")
            return
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
        // 符石狀態轉成求解旗標（風化/黏腐、觸電、冰封/石化）；無法辨識的格子當冰封：不碰、也不算消除
        val cells = rec.solverCells()
        stage.applyOrbMarks(cells)   // 自點必消 / 不消（只有按「完成」轉這一盤時才會有）
        val presetIdx = activePreset
        val hardStage = presetIdx in presets.indices && presets[presetIdx].hard
        // 自我學習：這關常常破不了 → 自動提高搜尋品質
        val beam = if (hardStage) maxOf(settings.beam, 1500) else settings.beam
        val maxPath = settings.maxPathFor(geo.rows)
        val dirs = settings.dirs
        val budget = settings.solveBudgetMs
        val goCell = rec.startCell()
        val ruleInts = stage.toInts(goCell)
        val hasStage = !stage.isEmpty()
        worker.execute {
            var res = SolveResult.from(NativeSolver.solve(cells, geo.rows, beam, maxPath, dirs, ruleInts, budget))
            // 關卡條件沒全部符合：用更大的搜尋範圍再找一次
            if (res.found && !res.goalsMet && hasStage) {
                val again = SolveResult.from(NativeSolver.solve(cells, geo.rows, maxOf(1500, beam * 2), maxPath, dirs, ruleInts, 0))
                if (again.goalsMet) res = again
            }
            main.post {
                lastNoMove = false
                if (!res.found || res.dirs.isEmpty()) {
                    // 消不出任何 combo（例如整盤冰封）：移動一步結束回合；連一步都不能動 → 交給使用者
                    val pass = passMove(cells, geo.rows, goCell)
                    if (pass == null) {
                        lastNoMove = true
                        toast("盤面全部是不能碰的珠子，沒辦法轉")
                        done(false)
                        return@post
                    }
                    toast("這盤消不出 combo，移動一步結束回合")
                    res = pass
                }
                lastGoalsOk = !hasStage || res.goalsMet
                // 自我學習：記錄這關的遊玩結果（破盾成功 / 失敗），作為之後調整依據
                if (presetIdx in presets.indices) {
                    val p = presets[presetIdx]
                    val wasHard = p.hard
                    p.seen++
                    if (hasStage) { if (res.goalsMet) p.met++ else p.unmet++ }
                    presetStore.save(presets)
                    if (!wasHard && p.hard) toast("這關常常破不了，之後會自動提高搜尋品質")
                }
                // 找不到完全符合條件的轉法：照樣轉最接近的（求解器已把條件差距算進分數），只提示不停住
                if (!lastGoalsOk) toast("這盤沒找到完全符合「${stage.summary()}」的轉法，照最接近的轉")
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

    /** 找一步合法的移動（兩顆都能碰的相鄰珠子），用來在消不出 combo 時結束回合 */
    private fun passMove(cells: IntArray, rows: Int, goCell: Int = -1): SolveResult? {
        val n = rows * 6
        for (i in (if (goCell >= 0) goCell..goCell else 0 until n)) {
            if (cells[i] and 0x40 != 0) continue
            for (d in 0 until 4) {
                val x = i % 6 + Dirs.DX[d]
                val y = i / 6 + Dirs.DY[d]
                if (x !in 0 until 6 || y !in 0 until rows) continue
                if (cells[y * 6 + x] and 0x40 != 0) continue
                return SolveResult(true, 0, 0, i, 0, intArrayOf(d))
            }
        }
        return null
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

    /** 等技能視窗關掉、盤面靜止（連續兩張一樣）後，照 rules 轉這一回合；30 秒內沒看到盤面就放棄 */
    private fun waitBoardThenTurn(rules: StageRules, since: Long, last: IntArray?) {
        if (running || calibRoot != null || recordView != null || stageRoot != null) return
        if (SystemClock.uptimeMillis() - since > 30_000) { toast("30 秒內沒看到盤面，已取消自動轉珠（可按「轉」）"); return }
        main.postDelayed({
            if (busy || running) { waitBoardThenTurn(rules, since, last); return@postDelayed }
            captureClean { frame ->
                if (frame == null) { waitBoardThenTurn(rules, since, null); return@captureClean }
                val geo = settings.geometry
                val rec = OrbRecognizer.recognize(frame, geo, model, stateModel)
                val board = OrbRecognizer.maskedBoard(rec)
                val ready = rec.unknownCount() <= OrbRecognizer.maxUnknown(geo.cells)
                if (ready && last != null && OrbRecognizer.sameMasked(board, last)) onOnceTap(rules)
                else waitBoardThenTurn(rules, since, if (ready) board else null)
            }
        }, 700)
    }

    private fun startRecordRule() {
        if (calibRoot != null || recordView != null || stageRoot != null || busy) return
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return }
        stopAuto(null)
        busy = true
        captureClean { frame ->
            busy = false
            updateBar()
            if (frame == null) { toast("截圖失敗"); return@captureClean }
            val geo = if (settings.hasGeometry) settings.geometry else null
            val sig = geo?.let { StageMatcher.signature(frame, it) }
            if (sig == null) { showRecordOverlay(frame); return@captureClean }
            // 畫面上有王的技能卡（長按王打開）：讀卡片、自動配置過關條件，關掉卡片後自動轉這一回合
            busy = true
            updateBar("…")
            SkillOcr.read(frame) { text ->
                busy = false
                updateBar()
                val parse = SkillParser.parse(text ?: "")
                if (!parse.found) { showRecordOverlay(frame); return@read }
                onBossRead(sig, parse, auto = false)
                val list = presetStore.load()
                val (idx, _) = StageMatcher.best(sig, list)
                val rules = if (idx >= 0) StageRules().apply { copyFrom(list[idx].rules) } else parse.rules
                toast("已依技能卡配置：${rules.summary()}\n關掉技能視窗後會自動轉珠")
                waitBoardThenTurn(rules, SystemClock.uptimeMillis(), null)
            }
        }
    }

    private fun showRecordOverlay(frame: Frame) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(70, 0, 0, 0)) }
        var mode = TapRule.ACTION_TAP
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 21, 101, 192))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val hint = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        val modeBtn = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        }
        fun paintMode() {
            hint.text = if (mode == TapRule.ACTION_TAP)
                "點一下要自動按的位置（例如「再次挑戰」「確定」「出戰」）。之後畫面出現一樣的東西，自動執行就會幫你點。"
            else
                "點一下這個畫面上的特徵位置（例如「戰敗」「體力不足」的字樣）。之後看到這個畫面，自動執行會停止並震動提醒你。"
            modeBtn.text = "模式：" + (if (mode == TapRule.ACTION_TAP) "● 看到就點　○ 看到就停止" else "○ 看到就點　● 看到就停止") + "（點這裡切換）"
        }
        paintMode()
        modeBtn.setOnClickListener {
            mode = if (mode == TapRule.ACTION_TAP) TapRule.ACTION_STOP else TapRule.ACTION_TAP
            paintMode()
        }
        val cancel = TextView(this).apply {
            text = "✕ 取消"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { closeRecord(); toast("已取消") }
        }
        box.addView(hint)
        box.addView(modeBtn)
        box.addView(cancel)
        root.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
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
                    val n = ruleStore.add(TapRule(e.rawX, e.rawY, size, sig, mode))
                    rules = ruleStore.load()
                    toast(if (mode == TapRule.ACTION_TAP) "已新增點擊規則 $n。現在請自己按一下那個按鈕，到下一個畫面再繼續新增"
                    else "已新增停止規則 $n：之後看到這個畫面會停止並提醒你")
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


    // ---------------- 關卡機制 ----------------

    private fun openStagePanel() {
        if (calibRoot != null || recordView != null || stageRoot != null || busy) return
        if (!settings.hasGeometry) { toast("請先按「校」校正盤面位置"); return }
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return }
        stopAuto(null)
        busy = true
        captureClean { frame ->
            busy = false
            updateBar()
            if (frame == null) { toast("截圖失敗"); return@captureClean }
            val geo = settings.geometry
            val sig = StageMatcher.signature(frame, geo)
            if (sig == null) { toast("盤面上方空間不足，無法記住這一關"); return@captureClean }
            // 畫面上有王的技能視窗（長按王打開）：讀文字、自動對應轉珠條件
            busy = true
            updateBar("…")
            SkillOcr.read(frame) { text ->
                busy = false
                updateBar()
                val parse = SkillParser.parse(text ?: "")
                if (parse.found) {
                    onBossRead(sig, parse, auto = false)
                    if (parse.manual.isNotEmpty()) toast("需要補充：${parse.manual.joinToString("、")}。關掉技能視窗後再按一次「關」")
                    return@read
                }
                val (idx, _) = StageMatcher.best(sig, presets)
                // 每次按「關」都從空白開始，照眼前這隻王重新設定（按完成會覆蓋這關原本記住的條件）
                val initial = StageRules()
                toast("條件已清空，請照這隻王的盾設定後按「完成」，會照條件轉這一回合")
                showStagePanel(sig, idx, initial)
            }
        }
    }

    private fun showStagePanel(sig: FloatArray, presetIdx: Int, initial: StageRules) {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val edit = StageRules().apply { copyFrom(initial) }
        val root = FrameLayout(themed).apply { setBackgroundColor(Color.argb(60, 0, 0, 0)) }
        val board = StageRulesView(themed).apply {
            geo = settings.geometry
            rules = edit
        }
        root.addView(board, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 20, 26, 31))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val summary = TextView(themed).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        fun refresh() {
            summary.text = "目前：${edit.summary()}　［${appVersion(this)}］\n選一種筆刷後點下方盤面格子（再點一次取消）。按「完成」＝照這些條件只轉這一回合。"
            board.invalidate()
        }
        fun btn(label: String, onClick: (Button) -> Unit) = Button(themed).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(2), dp(6), dp(2), dp(6))
            setOnClickListener { onClick(this) }
        }
        fun rowOf(vararg vs: View) = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in vs) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        // 筆刷：點盤面格子時要設定的機制
        val brushNames = arrayOf("拼圖盾", "燃燒(不碰)", "火焰區(終點)", "黑洞(不停)", "裂痕", "起點(綠)", "終點(紅)", "必消(點珠)", "不消(點珠)", "不碰(點珠)")
        val brushButtons = ArrayList<Button>()
        var brush = 0
        fun paintBrushes() {
            for (k in brushButtons.indices) brushButtons[k].text = (if (k == brush) "● " else "") + brushNames[k]
        }
        for (k in brushNames.indices) brushButtons.add(btn(brushNames[k]) { brush = k; paintBrushes() })
        paintBrushes()
        board.onCellTap = { i ->
            when (brush) {
                0 -> edit.toggle(i, StageRules.SHIELD)
                1 -> edit.toggle(i, StageRules.NO_TOUCH)
                2 -> edit.toggle(i, StageRules.FLAME_END)
                3 -> edit.toggle(i, StageRules.BLACK_HOLE)
                4 -> edit.toggle(i, StageRules.CRACK)
                5 -> edit.green = if (edit.green == i) -1 else i
                6 -> edit.red = if (edit.red == i) -1 else i
                7 -> { edit.toggle(i, StageRules.MUST_ORB); if (edit.cellFlags[i] and StageRules.MUST_ORB != 0) edit.cellFlags[i] = edit.cellFlags[i] and StageRules.NO_ORB.inv() }
                9 -> edit.toggle(i, StageRules.NOTOUCH_ORB)
                8 -> { edit.toggle(i, StageRules.NO_ORB); if (edit.cellFlags[i] and StageRules.NO_ORB != 0) edit.cellFlags[i] = edit.cellFlags[i] and StageRules.MUST_ORB.inv() }
            }
            refresh()
        }

        // 盾 / 條件
        val comboChoices = intArrayOf(0, 5, 6, 7, 8, 9, 10, 12)
        fun cycle(a: IntArray, cur: Int): Int { val i = a.indexOf(cur); return a[if (i < 0) 0 else (i + 1) % a.size] }
        fun comboLabel() = if (edit.comboMin == 0) "Combo盾：無" else "Combo盾 ≥${edit.comboMin}"
        // 首消 Combo：剛好設定的數量（取代舊的「≥」與「固定首消」兩顆按鈕）
        val firstExactChoices = intArrayOf(-1) + (1..10).toList().toIntArray()
        fun firstLabel() = if (edit.firstExact < 0) "首消Combo：無" else "首消Combo：剛好 ${edit.firstExact}"
        fun threeLabel() = "首消每組3顆：" + if (edit.first3Only) "開" else "關"
        val comboBtn = btn(comboLabel()) { b -> edit.comboMin = cycle(comboChoices, edit.comboMin); b.text = comboLabel(); refresh() }
        lateinit var threeBtn: Button
        val firstBtn = btn(firstLabel()) { b ->
            val was = edit.firstExact
            edit.firstExact = cycle(firstExactChoices, edit.firstExact)
            edit.firstMin = 0
            // 設定首消 Combo 時預設「每組只能 3 顆」（不能有 4 顆連珠）；可再按右邊按鈕關掉
            if (was < 0 && edit.firstExact > 0) edit.first3Only = true
            if (edit.firstExact < 0) edit.first3Only = false
            b.text = firstLabel(); threeBtn.text = threeLabel(); refresh()
        }
        threeBtn = btn(threeLabel()) { b -> edit.first3Only = !edit.first3Only; b.text = threeLabel(); refresh() }
        val attrChoices = intArrayOf(0, 4, 5, 6, 7, 8)
        fun attrLabel() = if (edit.firstAttrMin == 0) "首消屬性盾：無" else "首消屬性 ≥${edit.firstAttrMin}"
        val attrBtn = btn(attrLabel()) { b -> edit.firstAttrMin = cycle(attrChoices, edit.firstAttrMin); b.text = attrLabel(); refresh() }
        val orbsChoices = intArrayOf(0) + (10..20).toList().toIntArray()
        fun orbsLabel() = if (edit.firstOrbsExact == 0) "NUM盾：無" else "NUM盾：剛好 ${edit.firstOrbsExact} 粒"
        val orbsBtn = btn(orbsLabel()) { b -> edit.firstOrbsExact = cycle(orbsChoices, edit.firstOrbsExact); b.text = orbsLabel(); refresh() }
        fun stickyLabel() = "黏手路徑：" + if (edit.sticky) "開" else "關"
        fun fireLabel() = "火焰路徑：" + if (edit.firePath) "開" else "關"
        val stickyBtn = btn(stickyLabel()) { b -> edit.sticky = !edit.sticky; b.text = stickyLabel(); refresh() }
        val fireBtn = btn(fireLabel()) { b -> edit.firePath = !edit.firePath; b.text = fireLabel(); refresh() }

        // 屬性勾選：多屬性盾（必消）與反屬性（首消禁）
        fun maskRow(title: String, get: () -> Int, set: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(themed).apply { text = title; setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(4), 0, dp(4), 0) })
            for (c in 0 until 6) {
                val chip = TextView(themed).apply {
                    text = StageRules.ORB_SHORT[c]
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(Color.WHITE)
                }
                fun paint() {
                    val on = get() shr c and 1 == 1
                    chip.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (on) BoardView.ORB_COLORS[c] else Color.argb(90, 255, 255, 255))
                        if (on) setStroke(dp(2), Color.WHITE)
                    }
                }
                paint()
                chip.setOnClickListener { set(get() xor (1 shl c)); paint(); refresh() }
                row.addView(chip, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
            }
            return row
        }

        panel.addView(summary)
        panel.addView(rowOf(brushButtons[0], brushButtons[1], brushButtons[2], brushButtons[3]))
        panel.addView(rowOf(brushButtons[4], brushButtons[5], brushButtons[6]))
        panel.addView(rowOf(brushButtons[7], brushButtons[8], brushButtons[9]))
        panel.addView(rowOf(comboBtn, firstBtn, threeBtn))
        panel.addView(rowOf(attrBtn, orbsBtn))
        panel.addView(rowOf(stickyBtn, fireBtn))
        panel.addView(maskRow("必消(這回合要消)", { edit.elemMask }, { edit.elemMask = it }))
        panel.addView(maskRow("首消不能消(第一批)", { edit.antiMask }, { edit.antiMask = it }))
        panel.addView(rowOf(
            btn("清除全部") {
                closeStagePanel()
                showStagePanel(sig, presetIdx, StageRules())
            },
            btn("取消") { closeStagePanel() },
            btn("完成") {
                closeStagePanel()
                saveStagePreset(sig, presetIdx, edit)
                // 存好條件後照這組條件「只轉這一回合」（等面板消失再截圖）
                val forced = StageRules().apply { copyFrom(edit) }
                if (!forced.isEmpty()) toast("照條件轉一次：${forced.summary()}")
                main.postDelayed({ if (!running && !busy) onOnceTap(forced) }, 400)
            },
        ))
        refresh()

        val scroll = ScrollView(themed).apply { addView(panel) }
        val maxH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        val top = dp(32)
        // 面板不可蓋到盤面：高度上限 = 盤面頂端 − 上邊距
        val boardTop = settings.geometry.top.toInt()
        val h = maxOf(dp(160), minOf(maxH, boardTop - top - dp(4)))
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h).apply {
            gravity = Gravity.TOP
            topMargin = top
            leftMargin = dp(6)
            rightMargin = dp(6)
        })
        wm.addView(root, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true))
        stageRoot = root
    }

    /** 記住（或更新、刪除）這一關：敵人畫面特徵 ＋ 機制 */
    private fun saveStagePreset(sig: FloatArray, presetIdx: Int, edit: StageRules) {
        val list = presetStore.load()
        val idx = if (presetIdx in list.indices) presetIdx else -1
        if (edit.withoutOrbMarks().isEmpty()) {
            if (idx >= 0) {
                list.removeAt(idx)
                toast("已刪除記住的關卡 ${idx + 1}，這關會照一般盤打")
            } else {
                toast("沒有設定機制，這關會照一般盤打")
            }
        } else if (idx >= 0) {
            list[idx] = list[idx].withRules(edit.withoutOrbMarks(), sig)
            toast("已更新關卡 ${idx + 1}：${edit.summary()}")
        } else {
            list.add(StagePreset(sig, edit.withoutOrbMarks()))
            toast("已記住關卡 ${list.size}：${edit.summary()}。之後遇到會自動套用")
        }
        presetStore.save(list)
        presets = list
        activePreset = -1
        applyStageFor(null, settings.geometry)
        val (now, _) = StageMatcher.best(sig, presets)
        if (now >= 0) { activePreset = now; stage = StageRules().apply { copyFrom(presets[now].rules) } }
        updateBar()
    }

    private fun closeStagePanel() {
        stageRoot?.let { runCatching { wm.removeView(it) } }
        stageRoot = null
    }

    // ---------------- 校正 ----------------

    private fun openCalibration() {
        if (calibRoot != null || recordView != null || stageRoot != null || busy) return
        stopAuto(null)
        if (Build.VERSION.SDK_INT < 30) { toast("自動截圖需要 Android 11 以上"); return }
        busy = true
        updateBar("…")
        captureClean { frame ->
            busy = false
            updateBar()
            if (frame == null) { toast("截圖失敗"); return@captureClean }
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
        rowB.addView(btn("＋類別") {
            OrbTypeDialog.showAdd(this, overlay = true) { t -> toast("已新增「${t.name}」：長按格子切換到它") }
        }, wlp())
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
        view.onLabelsChanged = { relabelFromEdits(); updateCalibInfo() }
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
        val rec = OrbRecognizer.recognize(f, v.geo, model, stateModel)
        v.resetLabels()
        for (i in 0 until v.geo.cells) {
            v.labels[i] = rec.colors[i]
            v.conf[i] = rec.confidence[i]
            v.states[i] = rec.states[i]
        }
        v.invalidate()
        updateCalibInfo()
    }

    /**
     * 使用者改了某幾顆的顏色 → 把它們當範例珠，立刻重新判斷其他沒改過的格子。
     * 同種珠子會自動跟著更新；和任何範例都不像的格子顯示「?」，提示下一個要標的種類。
     */
    private fun relabelFromEdits() {
        val v = calibView ?: return
        val f = calibFrame ?: return
        val base = OrbRecognizer.recognize(f, v.geo, model, stateModel)
        val work = model.copy()
        for (i in 0 until v.geo.cells) if (v.edited[i] && v.labels[i] in 0..5 && v.states[i] == OrbState.NORMAL) {
            work.addProto(base.features[i], v.labels[i])
        }
        val rec = OrbRecognizer.recognize(f, v.geo, work, stateModel)
        for (i in 0 until v.geo.cells) {
            if (!v.edited[i]) { v.labels[i] = rec.colors[i]; v.conf[i] = rec.confidence[i] }
            if (!v.stateEdited[i]) v.states[i] = rec.states[i]
        }
        v.invalidate()
    }

    private fun updateCalibInfo() {
        val v = calibView ?: return
        val n = v.geo.cells
        val unknown = (0 until n).count { !v.edited[it] && v.conf[it] < OrbRecognizer.UNKNOWN_CONF }
        val fixed = (0 until n).count { v.edited[it] }
        calibInfo?.text = "把格線對齊盤面（按住不放、震一下後再移動＝拖整個格線；右下圓點＝縮放）。點有「?」的格子＝確認判斷正確（同顏色的問號一起消失）；再點＝改顏色（每種顏色改一顆，同種的會自動跟著更新），長按放開＝切換符石類別（出現標籤後直接點標籤＝換下一個）（強化／風化黏腐／觸電／冰封石化／自訂）；沒有合適的按「＋類別」新增。\n" +
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
        val rec = OrbRecognizer.recognize(f, v.geo, model, stateModel)
        val feats = ArrayList<FloatArray>()
        val labels = ArrayList<Int>()
        val sDescs = ArrayList<FloatArray>()
        val sLabels = ArrayList<Int>()
        for (i in 0 until v.geo.cells) {
            val k = v.labels[i]
            val sure = v.edited[i] || v.stateEdited[i] || v.conf[i] >= OrbRecognizer.READY_CONF
            if (!sure) continue
            // 狀態：每個確定的格子都當作樣本（包含普通珠，讓模型分得出「普通」和特殊符石）
            rec.stateDesc?.let { sDescs.add(it[i]); sLabels.add(v.states[i]) }
            // 顏色：只用普通珠學習（強化、風化等外觀不同，不混進顏色中心）
            if (k in 0..5 && v.states[i] == OrbState.NORMAL) {
                feats.add(rec.features[i]); labels.add(k)
                // 使用者親手改過的格子 → 存成範例珠（之後同種珠子都以它為準）
                if (v.edited[i]) model.addProto(rec.features[i], k)
            }
        }
        val used = model.learn(feats, labels)
        colorStore.save(model, used)
        // 只有標過特殊狀態（或已經學過）才建立狀態模型，避免只學普通珠時把怪樣子都歸成普通
        val anySpecial = sLabels.any { it != OrbState.NORMAL } || stateModel.labels.any { it != OrbState.NORMAL }
        val sUsed = if (anySpecial) stateModel.learn(sDescs, sLabels) else 0
        if (anySpecial) colorStore.saveStates(stateModel)
        val special = sLabels.count { it != OrbState.NORMAL }
        toast("已儲存盤面位置，學習了 $used 格顏色" + if (special > 0) "、$special 格特殊符石" else if (sUsed > 0) "、更新符石狀態" else "")
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
