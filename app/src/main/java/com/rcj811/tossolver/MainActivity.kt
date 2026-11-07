package com.rcj811.tossolver

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Random

/**
 * 上半部：自動轉珠（無障礙服務）的開啟步驟與設定
 * 下半部：手動模式（自己排盤面 → 求解 → 看路徑）
 */
class MainActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var colorStore: ColorStore
    private lateinit var ruleStore: RuleStore

    // 自動轉珠
    private lateinit var autoStatus: TextView
    private lateinit var enableBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var readBossBtn: Button
    private lateinit var rulesText: TextView
    private lateinit var undoRuleBtn: Button
    private lateinit var clearRulesBtn: Button
    private lateinit var stepBtn: Button
    private lateinit var timeBtn: Button
    private lateinit var resetColorBtn: Button
    private lateinit var typesText: TextView
    private lateinit var delTypeBtn: Button

    // 手動模式
    private lateinit var board: BoardView
    private lateinit var status: TextView
    private lateinit var pathText: TextView
    private lateinit var solveBtn: Button
    private lateinit var playBtn: Button
    private lateinit var rowsBtn: Button
    private lateinit var qualityBtn: Button
    private lateinit var dirsBtn: Button
    private val paletteViews = ArrayList<TextView>()

    private var rows = 5
    private var solving = false
    private val rng = Random()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        colorStore = ColorStore(this)
        ruleStore = RuleStore(this)
        OrbTypeStore(this).apply()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
            setBackgroundColor(Color.parseColor("#1C2329"))
        }

        root.addView(title("自轉珠", 24f))
        root.addView(note("神魔之塔自動轉珠").apply { setPadding(0, 0, 0, 0) })

        // ---------- 自動轉珠 ----------
        autoStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(autoStatus)

        enableBtn = button("開啟無障礙設定") { goEnable() }
        root.addView(row(enableBtn, button("允許受限設定") { goRestricted() }))

        modeBtn = button("") { settings.autoDrag = !settings.autoDrag; refreshAuto() }
        readBossBtn = button("") { settings.autoReadBoss = !settings.autoReadBoss; refreshAuto() }
        root.addView(row(modeBtn, readBossBtn))

        stepBtn = button("") { settings.stepMs = next(AppSettings.STEP_CHOICES, settings.stepMs); refreshAuto() }
        timeBtn = button("") { settings.timeLimitSec = next(AppSettings.TIME_CHOICES, settings.timeLimitSec); refreshAuto() }
        root.addView(row(stepBtn, timeBtn))

        qualityBtn = button("") { settings.beam = if (settings.beam == 450) 1500 else 450; refreshAuto() }
        dirsBtn = button("") { settings.dirs = if (settings.dirs == 8) 4 else 8; refreshAuto() }
        root.addView(row(qualityBtn, dirsBtn))

        resetColorBtn = button("") {
            colorStore.reset()
            AutoTurnService.instance?.reloadModel()
            refreshAuto()
        }
        root.addView(resetColorBtn)

        // 符石類別（內建＋自訂）
        typesText = note("")
        root.addView(typesText)
        delTypeBtn = button("修改／刪除自訂類別") {
            OrbTypeDialog.showManageTypes(this) { AutoTurnService.instance?.reloadModel(); refreshAuto() }
        }
        root.addView(row(button("＋新增符石類別") {
            OrbTypeDialog.showAdd(this, overlay = false) { AutoTurnService.instance?.reloadModel(); refreshAuto() }
        }, delTypeBtn))

        rulesText = note("")
        root.addView(rulesText)
        undoRuleBtn = button("刪除最後一條規則") {
            ruleStore.removeLast()
            AutoTurnService.instance?.reloadModel()
            refreshAuto()
        }
        clearRulesBtn = button("清除全部規則") {
            ruleStore.clear()
            AutoTurnService.instance?.reloadModel()
            refreshAuto()
        }
        root.addView(row(undoRuleBtn, clearRulesBtn))
        root.addView(button("管理學習資料（一筆一筆刪除設定錯的）") {
            OrbTypeDialog.showLearningData(this) { AutoTurnService.instance?.reloadModel(); refreshAuto() }
        })
        root.addView(button("清除所有記住的關卡") {
            StagePresetStore(this).clear()
            AutoTurnService.instance?.reloadModel()
            refreshAuto()
        })

        root.addView(note(
            "開啟步驟（只需一次）：\n" +
                "1. 按「開啟無障礙設定」→ 已下載的應用程式 →「自轉珠」→ 開啟。\n" +
                "　若開關是灰色、顯示「受限設定」：先按「允許受限設定」→ 右上角 ⋮ →「允許受限設定」，再回去開啟。\n" +
                "2. 畫面上方會出現橫向懸浮工作列：⠿ 拖曳／收合、▶ 自動執行、轉 轉一回合、＋ 新增點擊規則、校 校正。\n" +
                "3. 進入戰鬥，珠子靜止時按「校」→「自動偵測」對齊盤面，顏色不對就點格子修正 →「儲存並學習」。\n" +
                "4. 教它過關卡（使用者設定）：在結算、再次挑戰、選戰友、出戰等畫面，按「＋」再點一下要按的按鈕，" +
                "然後自己按那個按鈕進到下一個畫面，重複到回到戰鬥為止。\n" +
                "4-1. 特殊符石（強化、風化/黏腐、觸電、冰封/石化）：在校正畫面「長按」格子標記類別，儲存後就會自動辨識。" +
                "遇到程式不認識的珠子，按「＋新增符石類別」自己定義（能不能碰、必須消除／不能消除…），再到校正畫面標記。\n" +
                "4-2. 讀王的破解條件：長按王打開技能視窗，再按工作列「關」，會自動讀文字並設定轉珠條件；" +
                "自動執行遇到沒記過的關卡，也會自己長按王去讀（可在上方關閉）。\n" +
                "5. 有特殊機制的關卡（拼圖盾、Combo 盾、首消盾、多屬性盾、燃燒、黑洞、火焰區、漩渦、黏手路徑…）：" +
                "第一次遇到時按工作列「關」，選筆刷後點盤面格子畫出拼圖盾形狀等，按「完成」。" +
                "程式會記住這關的敵人畫面，之後遇到同一關會自動判斷並套用（「關」按鈕變黃）；沒記過的關卡照一般盤打。\n" +
                "　過不了才交給你：套用機制後連續 3 回合破不了、或看到「停止畫面」（用「＋」切到「看到就停止」錄製，例如戰敗、體力不足），會停止並震動提醒。\n" +
                "6. 按 ▶ 自動執行：戰鬥中自動轉珠，戰鬥外看到記住的畫面就自動點，可一直重複刷關。按 ■ 停止。\n" +
                "　畫面卡住（同一個按鈕連點 6 次）或 3 分鐘沒事做會自動停止。\n" +
                "7. 工作列 ✕ 連按兩下可關閉自轉珠；要再使用時打開本 App，會自動詢問是否重新開啟。\n\n" +
                "小提醒：遊戲沒跟上拖曳就把「每步」調慢；斜轉常失敗就改成 4 方向。體力用完的畫面不要錄規則，免得一直點。\n" +
                "自動操作違反遊戲使用條款，有被封帳號的風險，請自行斟酌。"
        ))

        // ---------- 手動模式 ----------
        root.addView(title("手動模式", 18f).apply { setPadding(0, dp(22), 0, 0) })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(status)

        board = BoardView(this).apply {
            onCellEdited = { onBoardChanged() }
            onAnimationEnd = { playBtn.text = "播放路徑" }
        }
        root.addView(board, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        val palette = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        for (c in 0 until 6) {
            val v = TextView(this).apply {
                text = BoardView.ORB_NAMES[c]
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { selectColor(c) }
            }
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            palette.addView(v, lp)
            paletteViews.add(v)
        }
        root.addView(palette)

        rowsBtn = button("版面 6×5") { cycleRows() }
        solveBtn = button("求解") { solve() }
        playBtn = button("播放路徑") { togglePlay() }
        root.addView(row(rowsBtn, button("隨機盤") { randomBoard() }))
        root.addView(row(solveBtn, playBtn))

        pathText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#ECEFF1"))
            setPadding(0, dp(12), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(pathText)

        setContentView(ScrollView(this).apply { addView(root) })
        selectColor(0)
        randomBoard()
        refreshAuto()
    }

    override fun onResume() {
        super.onResume()
        refreshAuto()
        main.postDelayed({ askPermissionsIfNeeded() }, 350)
    }

    // ---------- 開啟權限的引導流程（自動跳到正確的設定頁，並提示要按哪裡） ----------

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var askedThisLaunch = false      // 按「稍後」後，這次開啟 App 就不再追問
    private var wasEnabled = false

    /** 引導目前走到哪一步（從設定頁回來時決定下一步） */
    private enum class Step { NONE, ACCESSIBILITY, RESTRICTED }
    private var step = Step.NONE

    /**
     * Android 13 以上：自己下載安裝的 App，要先「允許受限設定」才能開無障礙服務。
     * 回傳 true = 已允許（或系統沒有這個限制），false = 還沒允許，null = 無法判斷
     */
    private fun restrictedAllowed(): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return true
        return runCatching {
            val aom = getSystemService(android.app.AppOpsManager::class.java)
            val mode = aom.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    private fun askPermissionsIfNeeded() {
        if (isFinishing || dialog?.isShowing == true) return
        if (Build.VERSION.SDK_INT < 30) return
        val on = AutoTurnService.isEnabled(this)
        if (on) {
            if (!wasEnabled && step != Step.NONE) {
                step = Step.NONE
                showDialog("自轉珠已開啟 ✓",
                    "畫面上方會出現橫向懸浮工作列（可拖曳到你習慣的位置）。\n\n下一步：進入遊戲戰鬥，珠子靜止時按工作列的「校」→「自動偵測」對齊盤面 →「儲存並學習」。之後按 ▶ 就會自動執行。",
                    "知道了", {}, null, null)
            }
            wasEnabled = true
            return
        }
        wasEnabled = false
        when (step) {
            Step.ACCESSIBILITY -> {
                // 從無障礙設定回來但還沒開成
                if (Build.VERSION.SDK_INT >= 33 && restrictedAllowed() != true) {
                    autoJumpDialog("還差一步：允許受限設定",
                        "因為自轉珠是自己下載安裝的，Android 需要你先允許「受限設定」。\n\n" +
                            "等一下會自動跳到「應用程式資訊」頁：\n" +
                            "① 點右上角「⋮」\n② 點「允許受限設定」\n③ 輸入手機密碼或指紋確認\n④ 按返回，回到自轉珠\n\n" +
                            "回來後會自動帶你去打開開關。",
                        "前往") { goRestricted() }
                } else {
                    showDialog("還沒打開開關",
                        "在無障礙設定裡找到「自轉珠」（可能在「已安裝的應用程式」或「已下載的應用程式」裡），把開關打開，" +
                            "跳出的確認視窗按「允許」。",
                        "再試一次", { goAccessibility() }, "稍後", { step = Step.NONE })
                }
            }
            Step.RESTRICTED -> {
                // 從應用程式資訊回來
                if (restrictedAllowed() == false) {
                    showDialog("受限設定還沒允許",
                        "在「應用程式資訊」頁：點右上角「⋮」→「允許受限設定」→ 輸入密碼確認。\n\n" +
                            "如果「⋮」裡沒有這個選項：請先按「前往開啟」去打開一次自轉珠的開關（會被擋下），再回來就會出現。",
                        "再試一次", { goRestricted() }, "前往開啟", { goAccessibility() })
                } else {
                    autoJumpDialog("已允許受限設定 ✓",
                        "最後一步：等一下會自動跳到無障礙設定，把「自轉珠」的開關打開，確認視窗按「允許」。",
                        "前往") { goAccessibility() }
                }
            }
            Step.NONE -> {
                if (askedThisLaunch) return
                askedThisLaunch = true
                showDialog("開啟「自轉珠」權限",
                    "自動轉珠需要開啟無障礙服務「自轉珠」，才能：\n" +
                        "• 顯示懸浮工作列\n• 截取遊戲畫面辨識盤面與按鈕\n• 代替你拖曳轉珠、點擊按鈕\n\n" +
                        "截圖只在手機上即時處理，不會儲存或上傳。\n\n" +
                        "按「開始設定」後會自動跳到設定頁，畫面下方會提示你要按哪裡；每做完一步回到這個 App，就會自動帶你到下一步。",
                    "開始設定", { goAccessibility() },
                    "稍後", {})
            }
        }
    }

    private fun showDialog(title: String, msg: String,
                           posLabel: String, onPos: () -> Unit,
                           negLabel: String?, onNeg: (() -> Unit)?) {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(posLabel) { _, _ -> onPos() }
        if (negLabel != null) b.setNegativeButton(negLabel) { _, _ -> onNeg?.invoke() }
        dialog = b.show()
    }

    /** 倒數幾秒後自動前往；也可以馬上按或取消 */
    private fun autoJumpDialog(title: String, msg: String, label: String, action: () -> Unit) {
        var left = 4
        var done = false
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("$label（$left）") { _, _ -> if (!done) { done = true; action() } }
            .setNegativeButton("稍後") { _, _ -> done = true; step = Step.NONE }
            .create()
        dialog = d
        d.show()
        val tick = object : Runnable {
            override fun run() {
                if (done || !d.isShowing) return
                left--
                if (left <= 0) { done = true; d.dismiss(); action(); return }
                d.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "$label（$left）"
                main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(tick, 1000)
    }

    /** 在設定頁上方用提示告訴使用者要按哪裡（每則約 3.5 秒，依序顯示兩輪） */
    private fun guide(vararg lines: String) {
        var t = 600L
        repeat(2) {
            for (line in lines) {
                main.postDelayed({ Toast.makeText(applicationContext, line, Toast.LENGTH_LONG).show() }, t)
                t += 3700L
            }
        }
    }

    private fun goAccessibility() {
        step = Step.ACCESSIBILITY
        openAccessibilitySettings()
        guide("找到「自轉珠」→ 把開關打開 → 確認視窗按「允許」",
            "如果跳出「受限設定」：按「確定」，然後按返回回到自轉珠 App，會帶你處理")
    }

    private fun goRestricted() {
        step = Step.RESTRICTED
        openAppDetails()
        guide("點右上角「⋮」→「允許受限設定」",
            "輸入手機密碼確認後，按返回回到自轉珠 App")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun goEnable() = goAccessibility()

    // ---------- 自動轉珠 ----------

    private fun refreshAuto() {
        val on = AutoTurnService.isEnabled(this)
        val geo = settings.hasGeometry
        autoStatus.text = when {
            Build.VERSION.SDK_INT < 30 -> "⚠ 自動轉珠需要 Android 11 以上（目前 Android ${Build.VERSION.RELEASE}），仍可使用手動模式"
            !on -> "● 自轉珠：未開啟（照下方步驟 1 開啟）"
            !geo -> "● 自轉珠：已開啟。下一步：在戰鬥中按工作列的「校」校正盤面"
            else -> "● 自轉珠：已開啟、盤面已校正（6×${settings.geometry.rows}），按工作列 ▶ 開始自動執行"
        }
        autoStatus.setTextColor(Color.parseColor(if (on && geo) "#69F0AE" else "#FFD54F"))
        enableBtn.text = if (on) "無障礙設定（已開啟）" else "開啟無障礙設定"
        modeBtn.text = if (settings.autoDrag) "模式：自動拖曳" else "模式：只顯示路徑"
        readBossBtn.text = if (settings.autoReadBoss) "自動讀王技能：開" else "自動讀王技能：關"
        stepBtn.text = "每步 ${settings.stepMs} ms"
        timeBtn.text = "轉珠時間 ${settings.timeLimitSec} 秒"
        qualityBtn.text = if (settings.beam == 450) "品質：標準" else "品質：高（較慢）"
        dirsBtn.text = "${settings.dirs} 方向"
        val learned = colorStore.learnedSamples
        resetColorBtn.text = (if (learned > 0) "重設顏色與符石學習（已學 $learned 格）" else "顏色學習：尚未校正") +
            "\n" + colorStore.stateSummary()
        resetColorBtn.isEnabled = learned > 0
        typesText.text = "符石類別（遇到程式認不出的珠子，可以自己新增）：\n" +
            OrbTypes.all().joinToString("\n") { t -> "• ${t.name}：${t.describe()}" + if (t.builtin) "" else "（自訂）" }
        delTypeBtn.isEnabled = OrbTypes.custom.isNotEmpty()
        val nRules = ruleStore.count
        rulesText.text = (if (nRules == 0) "點擊規則：尚未設定（▶ 自動執行只會轉珠，不會自動過關卡）"
        else "點擊規則：$nRules 條（自動執行時，看到記住的畫面就會自動點）") +
            "\n" + StagePresetStore(this).summary() + "\n（自我學習：每玩一回合都會記下關卡畫面、破盾結果、珠子顏色，玩越多判斷越準）"
        undoRuleBtn.isEnabled = nRules > 0
        clearRulesBtn.isEnabled = nRules > 0
    }

    private fun next(choices: IntArray, cur: Int): Int {
        val i = choices.indexOf(cur)
        return choices[if (i < 0) 0 else (i + 1) % choices.size]
    }

    /** 盡量直接打開「自轉珠」的開關頁；手機不支援時打開無障礙清單並標出它 */
    private fun openAccessibilitySettings() {
        val comp = ComponentName(this, AutoTurnService::class.java).flattenToString()
        val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, comp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(direct) }.isSuccess) return
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", comp)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", comp) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(list) }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ---------- 共用 UI ----------

    private fun title(t: String, size: Float) = TextView(this).apply {
        text = t
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.parseColor("#90A4AE"))
        setPadding(0, dp(8), 0, 0)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ---------- 手動模式 ----------

    private fun selectColor(c: Int) {
        board.selectedColor = c
        for (i in paletteViews.indices) {
            paletteViews[i].background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(BoardView.ORB_COLORS[i])
                if (i == c) setStroke(dp(4), Color.WHITE)
            }
        }
    }

    private fun cycleRows() {
        if (solving) return
        rows = if (rows >= 8) 5 else rows + 1
        rowsBtn.text = "版面 6×$rows"
        board.rows = rows
        randomBoard()
    }

    private fun onBoardChanged() {
        pathText.text = ""
        val n = NativeSolver.countCombos(board.cells.copyOf(rows * 6), rows)
        status.text = if (n > 0) "注意：盤面原本就有 $n 組可消（遊戲開盤不會這樣）" else "盤面已更新，按「求解」"
    }

    private fun randomBoard() {
        if (solving) return
        val n = rows * 6
        val tmp = IntArray(n)
        var tries = 0
        do {
            for (i in 0 until n) tmp[i] = rng.nextInt(6)
            tries++
        } while (NativeSolver.countCombos(tmp, rows) > 0 && tries < 1000)
        for (i in 0 until n) board.cells[i] = tmp[i]
        board.solution = null
        board.invalidate()
        pathText.text = ""
        status.text = "選顏色後在盤面點擊或拖曳上色，排成遊戲中的盤面，再按「求解」。"
    }

    private fun solve() {
        if (solving) return
        solving = true
        board.stopAnimation()
        playBtn.text = "播放路徑"
        solveBtn.isEnabled = false
        status.text = "求解中…"
        val cells = board.cells.copyOf(rows * 6)
        val r = rows
        val b = settings.beam
        val d = settings.dirs
        val mp = settings.maxPathFor(r)
        Thread {
            val res = SolveResult.from(NativeSolver.solve(cells, r, b, mp, d, IntArray(0), settings.solveBudgetMs))
            runOnUiThread { showResult(res) }
        }.start()
    }

    private fun showResult(res: SolveResult) {
        solving = false
        solveBtn.isEnabled = true
        if (!res.found) {
            status.text = "找不到可行路徑"
            return
        }
        board.solution = res
        val sec = (100 + res.dirs.size * settings.stepMs) / 1000.0
        status.text = "${res.combos} combo（首消 ${res.firstCombos}）・消 ${res.cleared} 顆・${res.dirs.size} 步（約 %.1f 秒）・計算 ${res.ms} ms".format(sec)
        val arrows = StringBuilder()
        for (k in res.dirs.indices) {
            arrows.append(Dirs.ARROW[res.dirs[k]])
            if (k % 10 == 9) arrows.append(' ')
        }
        pathText.text = "起點：第 ${res.start / 6 + 1} 列第 ${res.start % 6 + 1} 行\n路徑：$arrows"
    }

    private fun togglePlay() {
        if (board.isAnimating) {
            board.stopAnimation()
            playBtn.text = "播放路徑"
        } else if (board.solution != null) {
            board.startAnimation(180L)
            playBtn.text = "停止"
        }
    }
}
