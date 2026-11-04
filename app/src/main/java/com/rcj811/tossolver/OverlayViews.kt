package com.rcj811.tossolver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max

/** 疊在遊戲上的路徑（不攔截觸控） */
class PathOverlayView(context: Context) : View(context) {
    private var pts: List<PointF> = emptyList()
    private val off = IntArray(2)
    private val density = resources.displayMetrics.density
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(220, 255, 255, 255)
        strokeWidth = 12 * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#FF6D00")
        strokeWidth = 6 * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(p: List<PointF>) { pts = p; invalidate() }
    fun clear() { if (pts.isNotEmpty()) { pts = emptyList(); invalidate() } }

    override fun onDraw(canvas: Canvas) {
        if (pts.size < 2) return
        getLocationOnScreen(off)
        canvas.save()
        canvas.translate(-off[0].toFloat(), -off[1].toFloat())
        val p = Path()
        p.moveTo(pts[0].x, pts[0].y)
        for (k in 1 until pts.size) p.lineTo(pts[k].x, pts[k].y)
        canvas.drawPath(p, outer)
        canvas.drawPath(p, inner)
        dot.color = Color.WHITE
        canvas.drawCircle(pts[0].x, pts[0].y, 14 * density, dot)
        dot.color = Color.parseColor("#FF6D00")
        canvas.drawCircle(pts.last().x, pts.last().y, 12 * density, dot)
        canvas.restore()
    }
}

/**
 * 校正畫面：格線疊在遊戲盤面上。
 * 拖曳格線內部 = 移動；拖曳右下圓點 = 調整大小；點一下格子 = 修正該格顏色（之後用來學習）。
 */
class CalibrationView(context: Context) : View(context) {
    var geo = BoardGeometry(0f, 0f, 100f, 5)
        set(v) { field = v; invalidate() }
    val labels = IntArray(48) { -1 }
    val conf = FloatArray(48)
    val edited = BooleanArray(48)
    val states = IntArray(48)
    val stateEdited = BooleanArray(48)
    var onGeometryChanged: (() -> Unit)? = null
    var onLabelsChanged: (() -> Unit)? = null

    private val off = IntArray(2)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2 * density
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#00E5FF"); strokeWidth = 4 * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private var mode = 0   // 0 無；1 點擊或移動；2 移動；3 縮放
    private var downX = 0f
    private var downY = 0f
    private var startGeo = geo

    fun resetLabels() { labels.fill(-1); conf.fill(0f); edited.fill(false); states.fill(0); stateEdited.fill(false) }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(off)
        canvas.save()
        canvas.translate(-off[0].toFloat(), -off[1].toFloat())
        val g = geo
        val s = g.cell
        for (i in 0 until g.cells) {
            val x = g.left + (i % 6) * s
            val y = g.top + (i / 6) * s
            canvas.drawRect(x, y, x + s, y + s, grid)
            val k = labels[i]
            val cx = x + s * 0.24f
            val cy = y + s * 0.24f
            val r = s * 0.17f
            if (k in 0..5) {
                fill.color = BoardView.ORB_COLORS[k]
                canvas.drawCircle(cx, cy, r, fill)
                ring.color = if (edited[i]) Color.YELLOW else Color.WHITE
                canvas.drawCircle(cx, cy, r, ring)
                text.textSize = s * 0.2f
                canvas.drawText(BoardView.ORB_NAMES[k], cx, cy + s * 0.07f, text)
            }
            val st = states[i]
            if (st != OrbState.NORMAL) {   // 狀態標籤（強／風／電／冰）
                fill.color = when (st) {
                    OrbState.ENHANCED -> Color.parseColor("#FFB300")
                    OrbState.NO_TOUCH -> Color.parseColor("#8D6E63")
                    OrbState.ELECTRIC -> Color.parseColor("#FDD835")
                    else -> Color.parseColor("#4FC3F7")
                }
                canvas.drawCircle(x + s * 0.76f, y + s * 0.76f, s * 0.17f, fill)
                ring.color = if (stateEdited[i]) Color.YELLOW else Color.WHITE
                canvas.drawCircle(x + s * 0.76f, y + s * 0.76f, s * 0.17f, ring)
                text.textSize = s * 0.2f
                text.color = Color.BLACK
                canvas.drawText(OrbState.BADGE[st], x + s * 0.76f, y + s * 0.83f, text)
                text.color = Color.WHITE
            }
            if (!edited[i] && !stateEdited[i] && st <= OrbState.ENHANCED && conf[i] < OrbRecognizer.UNKNOWN_CONF) {
                fill.color = Color.parseColor("#D50000")
                canvas.drawCircle(x + s * 0.76f, y + s * 0.24f, s * 0.14f, fill)
                text.textSize = s * 0.22f
                canvas.drawText("?", x + s * 0.76f, y + s * 0.32f, text)
            }
        }
        canvas.drawRect(g.left, g.top, g.right, g.bottom, frame)
        fill.color = Color.parseColor("#00E5FF")
        canvas.drawCircle(g.right, g.bottom, max(14 * density, s * 0.22f), fill)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.rawX
        val y = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; startGeo = geo
                mode = when {
                    hypot(x - geo.right, y - geo.bottom) < max(28 * density, geo.cell * 0.6f) -> 3
                    x in geo.left..geo.right && y in geo.top..geo.bottom -> 1
                    else -> 0
                }
                return mode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1 && hypot(x - downX, y - downY) > slop) mode = 2
                if (mode == 2) geo = startGeo.moved(x - downX, y - downY)
                if (mode == 3) {
                    val g = startGeo
                    val c = max(20f, ((x - g.left) / 6f + (y - g.top) / g.rows) / 2f)
                    geo = BoardGeometry(g.left, g.top, c, g.rows)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 1) {
                    val i = geo.cellAt(x, y)
                    if (i >= 0) {
                        if (e.eventTime - e.downTime >= 450) {
                            // 長按：切換符石狀態（普通 → 強化 → 風化/黏腐 → 觸電 → 冰封/石化）
                            states[i] = (states[i] + 1) % OrbState.COUNT
                            stateEdited[i] = true
                        } else {
                            // 點一下：切換顏色
                            labels[i] = if (labels[i] !in 0..5) 0 else (labels[i] + 1) % 6
                            edited[i] = true
                            conf[i] = 1f
                        }
                        invalidate()
                        onLabelsChanged?.invoke()
                    }
                } else if (mode == 2 || mode == 3) {
                    onGeometryChanged?.invoke()
                }
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }
}

/**
 * 「關」設定畫面的盤面層：顯示已設定的格子機制，點格子交給 onCellTap 處理（依目前選的筆刷）。
 */
class StageRulesView(context: Context) : View(context) {
    var geo = BoardGeometry(0f, 0f, 100f, 5)
        set(v) { field = v; invalidate() }
    var rules = StageRules()
    var onCellTap: ((Int) -> Unit)? = null

    private val off = IntArray(2)
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(200, 255, 255, 255); strokeWidth = 1.5f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(off)
        canvas.save()
        canvas.translate(-off[0].toFloat(), -off[1].toFloat())
        val g = geo
        val s = g.cell
        for (i in 0 until g.cells) {
            val x = g.left + (i % 6) * s
            val y = g.top + (i / 6) * s
            val f = rules.cellFlags[i]
            if (f and StageRules.SHIELD != 0) {
                fill.color = Color.argb(150, 255, 214, 0)
                canvas.drawRect(x, y, x + s, y + s, fill)
            }
            if (f and StageRules.NO_TOUCH != 0) {
                fill.color = Color.argb(150, 229, 57, 53)
                canvas.drawRect(x + s * 0.1f, y + s * 0.1f, x + s * 0.9f, y + s * 0.9f, fill)
            }
            if (f and StageRules.FLAME_END != 0) {
                stroke.color = Color.parseColor("#FF6D00")
                canvas.drawCircle(x + s / 2, y + s / 2, s * 0.38f, stroke)
            }
            if (f and StageRules.BLACK_HOLE != 0) {
                fill.color = Color.argb(220, 0, 0, 0)
                canvas.drawCircle(x + s / 2, y + s / 2, s * 0.3f, fill)
            }
            if (f and StageRules.CRACK != 0) {
                stroke.color = Color.LTGRAY
                canvas.drawLine(x + s * 0.2f, y + s * 0.2f, x + s * 0.8f, y + s * 0.8f, stroke)
                canvas.drawLine(x + s * 0.8f, y + s * 0.2f, x + s * 0.2f, y + s * 0.8f, stroke)
            }
            text.textSize = s * 0.3f
            if (i == rules.green) {
                fill.color = Color.parseColor("#00C853")
                canvas.drawCircle(x + s / 2, y + s / 2, s * 0.25f, fill)
                canvas.drawText("起", x + s / 2, y + s / 2 + s * 0.1f, text)
            }
            if (i == rules.red) {
                fill.color = Color.parseColor("#D50000")
                canvas.drawCircle(x + s / 2, y + s / 2, s * 0.25f, fill)
                canvas.drawText("終", x + s / 2, y + s / 2 + s * 0.1f, text)
            }
            canvas.drawRect(x, y, x + s, y + s, line)
        }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = geo.cellAt(e.rawX, e.rawY)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return i >= 0
        if (e.actionMasked == MotionEvent.ACTION_UP && i >= 0) {
            onCellTap?.invoke(i)
            invalidate()
        }
        return true
    }
}
