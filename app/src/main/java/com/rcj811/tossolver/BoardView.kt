package com.rcj811.tossolver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 盤面：點擊 / 拖曳上色，並畫出求解路徑與播放動畫 */
class BoardView(context: Context) : View(context) {

    companion object {
        val ORB_COLORS = intArrayOf(
            Color.parseColor("#E53935"), // 火
            Color.parseColor("#1E88E5"), // 水
            Color.parseColor("#43A047"), // 木
            Color.parseColor("#FDD835"), // 光
            Color.parseColor("#8E24AA"), // 暗
            Color.parseColor("#EC407A"), // 心
        )
        val ORB_NAMES = arrayOf("火", "水", "木", "光", "暗", "心")
        const val COLS = 6
    }

    var rows = 5
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }
    val cells = IntArray(48)
    var selectedColor = 0
    var onCellEdited: (() -> Unit)? = null

    var solution: SolveResult? = null
        set(value) {
            field = value
            stopAnimation()
            invalidate()
        }

    // 播放動畫狀態
    private var animBoard: IntArray? = null
    private var animStep = -1
    private var animPos = -1
    private val animRunnable = object : Runnable {
        override fun run() = advanceAnimation()
    }
    var onAnimationEnd: (() -> Unit)? = null

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pathInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#263238")
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * rows / COLS)
    }

    private fun cellSize() = width / COLS.toFloat()
    private fun cx(i: Int) = (i % COLS + 0.5f) * cellSize()
    private fun cy(i: Int) = (i / COLS + 0.5f) * cellSize()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = cellSize()
        val board = animBoard ?: cells
        for (i in 0 until rows * COLS) {
            val x = i % COLS
            val y = i / COLS
            bgPaint.color = if ((x + y) % 2 == 0) Color.parseColor("#37474F") else Color.parseColor("#455A64")
            canvas.drawRect(x * s, y * s, (x + 1) * s, (y + 1) * s, bgPaint)
            val c = board[i]
            if (c in 0..5) {
                orbPaint.color = ORB_COLORS[c]
                canvas.drawCircle(cx(i), cy(i), s * 0.42f, orbPaint)
                textPaint.textSize = s * 0.34f
                canvas.drawText(ORB_NAMES[c], cx(i), cy(i) + s * 0.12f, textPaint)
            }
        }
        drawPath(canvas, s)
    }

    private fun drawPath(canvas: Canvas, s: Float) {
        val sol = solution ?: return
        if (!sol.found || sol.start < 0) return
        val pts = sol.cellsOnPath()
        val last = if (animStep >= 0) animStep.coerceAtMost(pts.size - 1) else pts.size - 1
        pathOuter.strokeWidth = s * 0.16f
        pathInner.strokeWidth = s * 0.08f
        if (last > 0) {
            val p = Path()
            p.moveTo(cx(pts[0]), cy(pts[0]))
            for (k in 1..last) p.lineTo(cx(pts[k]), cy(pts[k]))
            canvas.drawPath(p, pathOuter)
            canvas.drawPath(p, pathInner)
            drawArrowHead(canvas, pts[last - 1], pts[last], s)
        }
        // 起點：白圈
        markPaint.style = Paint.Style.STROKE
        markPaint.strokeWidth = s * 0.08f
        markPaint.color = Color.WHITE
        canvas.drawCircle(cx(pts[0]), cy(pts[0]), s * 0.45f, markPaint)
        // 動畫中目前手指位置：黃圈
        if (animStep >= 0 && animPos >= 0) {
            markPaint.color = Color.parseColor("#FFEB3B")
            canvas.drawCircle(cx(animPos), cy(animPos), s * 0.47f, markPaint)
        }
    }

    private fun drawArrowHead(canvas: Canvas, from: Int, to: Int, s: Float) {
        val ang = atan2(cy(to) - cy(from), cx(to) - cx(from))
        val len = s * 0.32f
        val tipX = cx(to)
        val tipY = cy(to)
        val p = Path()
        p.moveTo(tipX, tipY)
        p.lineTo(tipX - len * cos(ang - 0.5f), tipY - len * sin(ang - 0.5f))
        p.lineTo(tipX - len * cos(ang + 0.5f), tipY - len * sin(ang + 0.5f))
        p.close()
        markPaint.style = Paint.Style.FILL
        markPaint.color = Color.WHITE
        canvas.drawPath(p, markPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (animStep >= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val s = cellSize()
                val x = (event.x / s).toInt()
                val y = (event.y / s).toInt()
                if (x in 0 until COLS && y in 0 until rows) {
                    val i = y * COLS + x
                    if (cells[i] != selectedColor) {
                        cells[i] = selectedColor
                        solution = null
                        onCellEdited?.invoke()
                        invalidate()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 依路徑一步步交換珠子，重現轉珠過程 */
    fun startAnimation(stepMs: Long) {
        val sol = solution ?: return
        if (!sol.found || sol.start < 0) return
        stopAnimation()
        animBoard = cells.copyOf()
        animStep = 0
        animPos = sol.start
        animDelay = stepMs
        invalidate()
        postDelayed(animRunnable, stepMs)
    }

    private var animDelay = 150L

    private fun advanceAnimation() {
        val sol = solution ?: return stopAnimation()
        val board = animBoard ?: return
        if (animStep >= sol.dirs.size) {
            stopAnimation()
            onAnimationEnd?.invoke()
            return
        }
        val d = sol.dirs[animStep]
        val next = (animPos / COLS + Dirs.DY[d]) * COLS + animPos % COLS + Dirs.DX[d]
        val t = board[animPos]
        board[animPos] = board[next]
        board[next] = t
        animPos = next
        animStep++
        invalidate()
        postDelayed(animRunnable, animDelay)
    }

    fun stopAnimation() {
        removeCallbacks(animRunnable)
        animBoard = null
        animStep = -1
        animPos = -1
        invalidate()
    }

    val isAnimating: Boolean get() = animStep >= 0
}
