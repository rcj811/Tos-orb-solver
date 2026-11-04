package com.rcj811.tossolver

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Random

/**
 * M1：手動輸入盤面 → 求解 → 顯示 / 播放路徑
 * 求解在背景執行緒進行，避免卡住畫面。
 */
class MainActivity : Activity() {

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
    private var beam = 450
    private var dirs = 8
    private var solving = false
    private val rng = Random()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#1C2329"))
        }

        root.addView(TextView(this).apply {
            text = "神魔之塔轉珠求解器"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

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

        // 調色盤：點選顏色後，在盤面上點擊或拖曳上色
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

        // 設定列
        rowsBtn = button("版面 6×5") { cycleRows() }
        qualityBtn = button("標準") { toggleQuality() }
        dirsBtn = button("8 方向") { toggleDirs() }
        root.addView(row(rowsBtn, qualityBtn, dirsBtn))

        // 動作列
        solveBtn = button("求解") { solve() }
        playBtn = button("播放路徑") { togglePlay() }
        root.addView(row(button("隨機盤") { randomBoard() }, solveBtn, playBtn))

        pathText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#ECEFF1"))
            setPadding(0, dp(12), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(pathText)

        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#78909C"))
            text = "使用方式：選顏色後在盤面點擊或拖曳上色，照遊戲盤面排好後按「求解」。" +
                "白圈是起點，箭頭是終點；「播放路徑」會一步步示範拖曳過程。\n" +
                "「高品質」搜尋更廣、多約 0.2 combo，但約慢 3.5 倍。"
        })

        setContentView(ScrollView(this).apply { addView(root) })
        selectColor(0)
        randomBoard()
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

    private fun selectColor(c: Int) {
        board.selectedColor = c
        for (i in paletteViews.indices) {
            val d = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(BoardView.ORB_COLORS[i])
                if (i == c) setStroke(dp(4), Color.WHITE)
            }
            paletteViews[i].background = d
        }
    }

    private fun cycleRows() {
        if (solving) return
        rows = if (rows >= 8) 5 else rows + 1
        rowsBtn.text = "版面 6×$rows"
        board.rows = rows
        randomBoard()
    }

    private fun toggleQuality() {
        beam = if (beam == 450) 1500 else 450
        qualityBtn.text = if (beam == 450) "標準" else "高品質"
    }

    private fun toggleDirs() {
        dirs = if (dirs == 8) 4 else 8
        dirsBtn.text = "$dirs 方向"
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
        status.text = "隨機盤面（無初始三連）。可直接求解，或改成遊戲中的盤面。"
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
        val b = beam
        val d = dirs
        Thread {
            val raw = NativeSolver.solve(cells, r, b, 25 + r * 2, d)
            val res = SolveResult.from(raw)
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
        val sr = res.start / 6 + 1
        val sc = res.start % 6 + 1
        val arrows = StringBuilder()
        for (k in res.dirs.indices) {
            arrows.append(Dirs.ARROW[res.dirs[k]])
            if (k % 10 == 9) arrows.append(' ')
        }
        pathText.text = "起點：第 $sr 列第 $sc 行\n路徑：$arrows"
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
