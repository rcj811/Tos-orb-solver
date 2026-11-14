package com.rcj811.tossolver

// 不依賴 Android API（可在 JVM 單元測試中模擬點擊流程）

/**
 * 「關」面板的點擊邏輯。Android 面板只把這裡的狀態畫出來，點擊都交給這裡處理。
 * 規則：
 *  - 筆刷：選一種筆刷，點盤面格子（再點一次取消）。
 *  - 數值條件：點按鈕 → 下方出現全部選項 → 點一個就設定好（任何數值都 2 下）；再點同一顆按鈕收起；長按＝回到「無」。
 *  - 開關：點一下切換。
 *  - 顏色列：點一色切換；跟別的條件同色會自動處理並提示。
 */
class StagePanelModel(val edit: StageRules) {
    companion object {
        // 面板排列順序（跟之前的版面一樣）；沒列到的新條件自動排在最後
        val BRUSH_ORDER = listOf("cell1", "cell4", "cell8", "cell16", "cell2", "green", "red", "cell32", "cell64", "cell128")
        val BRUSH_ROWS = intArrayOf(4, 3, 3)
        val BUTTON_ORDER = listOf("combo", "first", "three", "attr", "num", "shape", "shapeColor", "sticky", "fire", "groupK", "reveal")
        val MASK_ORDER = listOf("allMask", "antiMask", "groupMask")
        /** 條件分組：相關的按鈕和顏色列放在同一區（沒列到的新條件放「其他」） */
        val GROUPS = listOf(
            "Combo／消除" to listOf("combo", "attr", "num"),
            "首消" to listOf("first", "three", "allMask", "antiMask"),
            "形狀盾" to listOf("shape", "shapeColor"),
            "同色組" to listOf("groupK", "groupMask"),
            "路徑／盤面" to listOf("sticky", "fire", "reveal"),
        )
        private fun <T : Ctl> ordered(all: List<T>, order: List<String>) =
            all.sortedBy { c -> order.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    val brushes: List<Ctl.Brush> = ordered(StageConds.CONTROLS.filterIsInstance<Ctl.Brush>(), BRUSH_ORDER)
    val buttons: List<Ctl> = ordered(StageConds.CONTROLS.filter { it is Ctl.Choice || it is Ctl.Toggle }, BUTTON_ORDER)
    val masks: List<Ctl.Mask> = ordered(StageConds.CONTROLS.filterIsInstance<Ctl.Mask>(), MASK_ORDER)

    class Group(val title: String, val buttons: List<Ctl>, val masks: List<Ctl.Mask>)

    /** 分組後的條件（按鈕在上、顏色列在下） */
    val groups: List<Group> = run {
        val all = buttons + masks
        val used = HashSet<String>()
        val out = GROUPS.map { (t, ids) ->
            val cs = ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
            used.addAll(cs.map { it.id })
            Group(t, cs.filter { it !is Ctl.Mask }, cs.filterIsInstance<Ctl.Mask>())
        }.toMutableList()
        val rest = all.filter { it.id !in used }
        if (rest.isNotEmpty()) out.add(Group("其他", rest.filter { it !is Ctl.Mask }, rest.filterIsInstance<Ctl.Mask>()))
        out.filter { it.buttons.isNotEmpty() || it.masks.isNotEmpty() }
    }

    /** 這個條件有沒有設定（有設定的按鈕換顏色） */
    fun isActive(c: Ctl): Boolean = when (c) {
        is Ctl.Choice -> c.selected(edit) > 0
        is Ctl.Toggle -> toggleOn(c)
        is Ctl.Brush -> c.count(edit) > 0
        is Ctl.Mask -> c.get(edit) != 0
    }

    fun groupActive(g: Group) = (g.buttons + g.masks).any { isActive(it) }

    /** 筆刷分列（照之前 4、3、3；多出來的再每列 4 顆） */
    fun brushRows(): List<List<Ctl.Brush>> {
        val out = ArrayList<List<Ctl.Brush>>()
        var i = 0
        for (n in BRUSH_ROWS) { if (i >= brushes.size) break; out.add(brushes.subList(i, minOf(brushes.size, i + n))); i += n }
        while (i < brushes.size) { out.add(brushes.subList(i, minOf(brushes.size, i + 4))); i += 4 }
        return out
    }

    var brush = 0
        private set
    /** 目前打開選項列的數值條件（null = 沒打開） */
    var open: Ctl.Choice? = null
        private set
    /** 最近一次自動調整的提示（下一次點擊會清掉） */
    var notice: String? = null
        private set
    /** 點擊次數（測試用） */
    var taps = 0
        private set

    private fun tap() { taps++; notice = null }

    fun selectBrush(k: Int) { tap(); if (k in brushes.indices) brush = k; open = null }

    fun tapCell(i: Int) { tap(); brushes[brush].tap(edit, i) }

    /** 點數值條件或開關按鈕 */
    fun tapButton(c: Ctl) {
        tap()
        when (c) {
            is Ctl.Choice -> open = if (open === c) null else c
            is Ctl.Toggle -> { c.flip(edit); open = null }
            else -> {}
        }
    }

    /** 長按數值條件＝回到「無」（第一個選項） */
    fun longPressButton(c: Ctl) {
        tap()
        if (c is Ctl.Choice) { notice = c.pick(edit, 0); if (open === c) open = null }
        if (c is Ctl.Toggle && toggleOn(c)) c.flip(edit)
    }

    /** 在打開的選項列點第 k 個選項 */
    fun pickOption(k: Int) {
        val c = open ?: return
        tap()
        if (k in c.options.indices) notice = c.pick(edit, k)
        open = null
    }

    fun tapMask(m: Ctl.Mask, color: Int) { tap(); open = null; notice = m.tap(edit, color) }

    fun clearAll() { tap(); edit.clear(); open = null; brush = 0 }

    fun label(c: Ctl): String = when (c) {
        is Ctl.Choice -> c.label(edit) + if (open === c) " ▲" else ""
        is Ctl.Toggle -> c.label(edit)
        is Ctl.Brush -> (if (brushes.indexOf(c) == brush) "● " else "") + c.name + c.count(edit).let { if (it > 0) "($it)" else "" }
        is Ctl.Mask -> c.title
    }

    fun toggleOn(c: Ctl.Toggle) = c.label(edit).endsWith("開")

    fun find(id: String): Ctl = StageConds.CONTROLS.first { it.id == id }

    /** 面板上方的說明文字 */
    fun status(): String {
        val sb = StringBuilder("目前：").append(edit.summary())
        val bad = edit.ruleConflicts()
        if (bad.isNotEmpty()) sb.append("\n⚠ 衝突（照這樣不會轉）：").append(bad.joinToString("；"))
        notice?.let { sb.append("\n✓ ").append(it) }
        sb.append('\n').append(
            when {
                open != null -> "點下面一個選項設定「${open!!.label(edit)}」；長按按鈕＝回到「無」"
                else -> "筆刷「${brushes[brush].name}」：點盤面格子（再點一次取消）。按「完成」＝照這些條件只轉這一回合"
            }
        )
        return sb.toString()
    }

    fun hasConflicts() = edit.ruleConflicts().isNotEmpty()
}
