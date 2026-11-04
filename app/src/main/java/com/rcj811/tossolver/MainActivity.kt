package com.rcj811.tossolver

import android.app.Activity
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
    private lateinit var rulesText: TextView
    private lateinit var undoRuleBtn: Button
    private lateinit var clearRulesBtn: Button
    private lateinit var stepBtn: Button
    private lateinit var timeBtn: Button
    private lateinit var resetColorBtn: Button

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
            setBackgroundColor(Color.parseColor("#1C2329"))
        }

        root.addView(title("神魔之塔自動轉珠", 22f))

        // ---------- 自動轉珠 ----------
        autoStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(autoStatus)

        enableBtn = button("開啟無障礙設定") { openAccessibilitySettings() }
        root.addView(row(enableBtn, button("允許受限設定") { openAppDetails() }))

        modeBtn = button("") { settings.autoDrag = !settings.autoDrag; refreshAuto() }
        root.addView(modeBtn)

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

        root.addView(note(
            "開啟步驟（只需一次）：\n" +
                "1. 按「開啟無障礙設定」→ 已下載的應用程式 →「轉珠助手」→ 開啟。\n" +
                "　若開關是灰色、顯示「受限設定」：先按「允許受限設定」→ 右上角 ⋮ →「允許受限設定」，再回去開啟。\n" +
                "2. 畫面右側會出現懸浮工作列：⠿ 拖曳／收合、▶ 自動執行、轉 轉一回合、＋ 新增點擊規則、校 校正。\n" +
                "3. 進入戰鬥，珠子靜止時按「校」→「自動偵測」對齊盤面，顏色不對就點格子修正 →「儲存並學習」。\n" +
                "4. 教它過關卡（使用者設定）：在結算、再次挑戰、選戰友、出戰等畫面，按「＋」再點一下要按的按鈕，" +
                "然後自己按那個按鈕進到下一個畫面，重複到回到戰鬥為止。\n" +
                "5. 按 ▶ 自動執行：戰鬥中自動轉珠，戰鬥外看到記住的畫面就自動點，可一直重複刷關。按 ■ 停止。\n" +
                "　畫面卡住（同一個按鈕連點 6 次）或 3 分鐘沒事做會自動停止。\n\n" +
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
    }

    // ---------- 自動轉珠 ----------

    private fun refreshAuto() {
        val on = AutoTurnService.isEnabled(this)
        val geo = settings.hasGeometry
        autoStatus.text = when {
            Build.VERSION.SDK_INT < 30 -> "⚠ 自動轉珠需要 Android 11 以上（目前 Android ${Build.VERSION.RELEASE}），仍可使用手動模式"
            !on -> "● 轉珠助手：未開啟（照下方步驟 1 開啟）"
            !geo -> "● 轉珠助手：已開啟。下一步：在戰鬥中按工作列的「校」校正盤面"
            else -> "● 轉珠助手：已開啟、盤面已校正（6×${settings.geometry.rows}），按工作列 ▶ 開始自動執行"
        }
        autoStatus.setTextColor(Color.parseColor(if (on && geo) "#69F0AE" else "#FFD54F"))
        enableBtn.text = if (on) "無障礙設定（已開啟）" else "開啟無障礙設定"
        modeBtn.text = if (settings.autoDrag) "模式：自動拖曳" else "模式：只顯示路徑"
        stepBtn.text = "每步 ${settings.stepMs} ms"
        timeBtn.text = "轉珠時間 ${settings.timeLimitSec} 秒"
        qualityBtn.text = if (settings.beam == 450) "品質：標準" else "品質：高（較慢）"
        dirsBtn.text = "${settings.dirs} 方向"
        val learned = colorStore.learnedSamples
        resetColorBtn.text = if (learned > 0) "重設顏色學習（已學 $learned 格）" else "顏色學習：尚未校正"
        resetColorBtn.isEnabled = learned > 0
        val nRules = ruleStore.count
        rulesText.text = if (nRules == 0) "點擊規則：尚未設定（▶ 自動執行只會轉珠，不會自動過關卡）"
        else "點擊規則：$nRules 條（自動執行時，看到記住的畫面就會自動點）"
        undoRuleBtn.isEnabled = nRules > 0
        clearRulesBtn.isEnabled = nRules > 0
    }

    private fun next(choices: IntArray, cur: Int): Int {
        val i = choices.indexOf(cur)
        return choices[if (i < 0) 0 else (i + 1) % choices.size]
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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
        Thread {
            val res = SolveResult.from(NativeSolver.solve(cells, r, b, 25 + r * 2, d))
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
        status.text = "${res.combos} combo（首消 ${res.firstCombos}）・${res.dirs.size} 步・${res.ms} ms"
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
