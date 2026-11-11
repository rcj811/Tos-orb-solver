package com.rcj811.tossolver

// 不依賴 Android API（可在 JVM 單元測試中驗證）

/**
 * 「關」面板上的控制項。面板只負責把它們畫出來，點擊後做什麼都寫在條件模組裡。
 */
sealed class Ctl(val id: String) {
    /** 點一下打開選項列，再點一個選項就設定好（任何數值都只要 2 下）；長按＝回到「無」 */
    class Choice(
        id: String,
        val label: (StageRules) -> String,
        val options: List<String>,
        val selected: (StageRules) -> Int,
        val pick: (StageRules, Int) -> String?,   // 回傳提示（例如自動調整了別的條件），沒有就 null
    ) : Ctl(id)

    /** 開／關 */
    class Toggle(id: String, val label: (StageRules) -> String, val flip: (StageRules) -> Unit) : Ctl(id)

    /** 六色勾選列 */
    class Mask(
        id: String,
        val title: String,
        val get: (StageRules) -> Int,
        val tap: (StageRules, Int) -> String?,   // 點了某一色；回傳提示
    ) : Ctl(id)

    /** 盤面筆刷：選好後點格子（再點一次取消） */
    class Brush(id: String, val name: String, val tap: (StageRules, Int) -> Unit, val count: (StageRules) -> Int) : Ctl(id)
}

/** 這一盤的珠數統計（衝突、做不到的檢查共用） */
class BoardCount(val colors: IntArray, antiMask: Int) {
    /** 每色可消珠數（扣掉首消不能消的顏色） */
    val cnt = IntArray(6).also { for (c in colors) if (c in 0..5 && (antiMask shr c and 1) == 0) it[c]++ }
    /** 每色可消珠數（不扣首消禁） */
    val all = IntArray(6).also { for (c in colors) if (c in 0..5) it[c]++ }
    val ubAll = (0 until 6).sumOf { cnt[it] / 3 }
    val ubAttr = (0 until 5).sumOf { cnt[it] / 3 }
}

/**
 * 一個關卡條件模組：存檔、摘要、合併、衝突與做不到的說明、面板控制項全部在這裡。
 * 新增條件＝在 StageConds.ALL 加一個模組（再在 C++ 的 condActive/condDeficit/condGuide 加一個 case）。
 */
abstract class Cond(val name: String) {
    abstract fun isSet(r: StageRules): Boolean
    abstract fun clear(r: StageRules)
    abstract fun copy(dst: StageRules, src: StageRules)
    /** 讀技能卡＋使用者畫的格子合併時：src 有設定就蓋過 dst */
    open fun merge(dst: StageRules, src: StageRules) { if (isSet(src)) copy(dst, src) }
    /** 摘要（沒設定回傳空清單） */
    abstract fun summary(r: StageRules): List<String>
    /** 存檔用 key=value */
    abstract fun save(r: StageRules): List<String>
    /** 讀檔：認得這個 key 就設定並回傳 true */
    abstract fun load(r: StageRules, k: String, v: String): Boolean
    /** 條件之間互相矛盾（不看盤面；面板上即時顯示） */
    open fun conflicts(r: StageRules): List<String> = emptyList()
    /** 和這一盤矛盾（看盤面格子：不能碰、冰封等） */
    open fun boardConflicts(r: StageRules, cells: IntArray, colors: IntArray): List<String> = emptyList()
    /** 這一盤數學上做不到（首消不受天降影響，上限是精確的）；做得到回傳 null */
    open fun impossible(r: StageRules, b: BoardCount): String? = null
    /** 「關」面板上的控制項 */
    open val controls: List<Ctl> = emptyList()
    /**
     * 需要精準控制第一批（形狀、剛好幾粒／幾組…）：只走上下左右。
     * 斜走要剛好穿過格子的角，手指偏一點遊戲就先經過旁邊那格、多換一次珠，形狀或數量就跑掉。
     */
    open fun precise(r: StageRules) = false
}

object StageConds {
    val ORB = StageRules.ORB_SHORT

    fun maskText(m: Int) = (0 until 6).filter { m shr it and 1 == 1 }.joinToString("") { ORB[it] }

    fun pos(i: Int) = "第 ${i / 6 + 1} 列第 ${i % 6 + 1} 格"

    private fun short(b: BoardCount, mask: Int, need: Int, arr: IntArray = b.all) =
        (0 until 6).filter { (mask shr it and 1) == 1 && arr[it] < need }

    private fun shortText(b: BoardCount, cs: List<Int>, arr: IntArray = b.all) = cs.joinToString("、") { "${ORB[it]}只有 ${arr[it]} 顆" }

    /** 整數條件的共用寫法 */
    private abstract class IntCond(
        name: String, val key: String, val none: Int,
        val get: (StageRules) -> Int, val set: (StageRules, Int) -> Unit,
    ) : Cond(name) {
        override fun isSet(r: StageRules) = get(r) != none
        override fun clear(r: StageRules) = set(r, none)
        override fun copy(dst: StageRules, src: StageRules) = set(dst, get(src))
        override fun save(r: StageRules) = listOf("$key=${get(r)}")
        override fun load(r: StageRules, k: String, v: String): Boolean {
            if (k != key) return false
            set(r, v.toIntOrNull() ?: none); return true
        }
    }

    private abstract class BoolCond(name: String, val key: String, val get: (StageRules) -> Boolean, val set: (StageRules, Boolean) -> Unit) : Cond(name) {
        override fun isSet(r: StageRules) = get(r)
        override fun clear(r: StageRules) = set(r, false)
        override fun copy(dst: StageRules, src: StageRules) = set(dst, get(src))
        override fun save(r: StageRules) = listOf("$key=${if (get(r)) 1 else 0}")
        override fun load(r: StageRules, k: String, v: String): Boolean {
            if (k != key) return false
            set(r, v == "1"); return true
        }
        fun onOff(r: StageRules) = if (get(r)) "開" else "關"
    }

    /** 顏色條件（bit = 珠種） */
    private abstract class MaskCond(name: String, val key: String, val get: (StageRules) -> Int, val set: (StageRules, Int) -> Unit) : Cond(name) {
        override fun isSet(r: StageRules) = get(r) != 0
        override fun clear(r: StageRules) = set(r, 0)
        override fun copy(dst: StageRules, src: StageRules) = set(dst, get(src))
        override fun merge(dst: StageRules, src: StageRules) = set(dst, get(dst) or get(src))
        override fun save(r: StageRules) = listOf("$key=${get(r)}")
        override fun load(r: StageRules, k: String, v: String): Boolean {
            if (k != key) return false
            set(r, (v.toIntOrNull() ?: 0) and 0x3F); return true
        }
    }

    // ---------------- 盤面格子（筆刷） ----------------

    private class CellFlag(val flag: Int, val brush: String, val sum: String, val unit: String = "")

    private val CELL_FLAGS = listOf(
        CellFlag(StageRules.SHIELD, "拼圖盾", "拼圖盾", " 格"),
        CellFlag(StageRules.NO_TOUCH, "燃燒(不碰)", "燃燒"),
        CellFlag(StageRules.FLAME_END, "火焰區(終點)", "火焰區"),
        CellFlag(StageRules.BLACK_HOLE, "黑洞(不停)", "黑洞"),
        CellFlag(StageRules.CRACK, "裂痕", "裂痕"),
        CellFlag(StageRules.MUST_ORB, "必消(點珠)", "必消珠", " 顆"),
        CellFlag(StageRules.NO_ORB, "不消(點珠)", "不消珠", " 顆"),
        CellFlag(StageRules.NOTOUCH_ORB, "不碰(點珠)", "不碰珠", " 顆"),
    )

    /** 同一格不能同時有的標記：點新的會拿掉舊的 */
    private val EXCLUSIVE = mapOf(
        StageRules.MUST_ORB to (StageRules.NO_ORB or StageRules.CRACK),
        StageRules.NO_ORB to (StageRules.MUST_ORB or StageRules.SHIELD),
        StageRules.SHIELD to (StageRules.CRACK or StageRules.NO_ORB),
        StageRules.CRACK to (StageRules.SHIELD or StageRules.MUST_ORB),
    )

    private object Cells : Cond("盤面格子") {
        override fun precise(r: StageRules) = r.count(StageRules.SHIELD) > 0
        override fun isSet(r: StageRules) = r.cellFlags.any { it != 0 }
        override fun clear(r: StageRules) = r.cellFlags.fill(0)
        override fun copy(dst: StageRules, src: StageRules) { src.cellFlags.copyInto(dst.cellFlags) }
        override fun merge(dst: StageRules, src: StageRules) { for (i in 0 until StageRules.MAX_CELLS) dst.cellFlags[i] = dst.cellFlags[i] or src.cellFlags[i] }
        override fun summary(r: StageRules) = CELL_FLAGS.mapNotNull { f -> r.count(f.flag).takeIf { it > 0 }?.let { "${f.sum} $it${f.unit}" } }
        override fun save(r: StageRules) = listOf("cells=" + r.cellFlags.joinToString(","))
        override fun load(r: StageRules, k: String, v: String): Boolean {
            if (k != "cells") return false
            v.split(',').forEachIndexed { j, t -> if (j < StageRules.MAX_CELLS) r.cellFlags[j] = t.toIntOrNull() ?: 0 }
            return true
        }

        override fun conflicts(r: StageRules): List<String> {
            val out = ArrayList<String>()
            for (i in 0 until StageRules.MAX_CELLS) {
                val f = r.cellFlags[i]
                if (f and StageRules.SHIELD != 0 && f and StageRules.CRACK != 0) out.add("${pos(i)} 同時是拼圖盾和裂痕：拼圖盾要消掉，裂痕不能消")
                if (f and StageRules.SHIELD != 0 && f and StageRules.NO_ORB != 0) out.add("${pos(i)} 同時是拼圖盾和「不消」的珠")
                if (f and StageRules.MUST_ORB != 0 && f and StageRules.CRACK != 0) out.add("${pos(i)} 標了必消，但那格是裂痕（不能消）")
            }
            // 拼圖盾要剛好那個形狀：每一格都要在盾裡的「直或橫連續 3 格」上，否則不可能剛好消掉
            val rows = StageRules.MAX_CELLS / 6
            fun sh(x: Int, y: Int) = x in 0 until 6 && y in 0 until rows && r.cellFlags[y * 6 + x] and StageRules.SHIELD != 0
            val bad = (0 until StageRules.MAX_CELLS).filter { i ->
                if (r.cellFlags[i] and StageRules.SHIELD == 0) return@filter false
                val x = i % 6; val y = i / 6
                val h = (-2..0).any { d -> sh(x + d, y) && sh(x + d + 1, y) && sh(x + d + 2, y) }
                val vv = (-2..0).any { d -> sh(x, y + d) && sh(x, y + d + 1) && sh(x, y + d + 2) }
                !h && !vv
            }
            if (bad.isNotEmpty()) out.add(
                "拼圖盾的形狀不可能剛好消掉：" + bad.joinToString("、") { pos(it) } +
                    " 不在盾裡任何一條直或橫連續 3 格上（三連至少要 3 格）。請照遊戲裡盾的形狀把格子標完整"
            )
            if (r.count(StageRules.FLAME_END) > 0 && (0 until StageRules.MAX_CELLS).none {
                    val f = r.cellFlags[it]
                    f and StageRules.FLAME_END != 0 && f and (StageRules.NO_TOUCH or StageRules.BLACK_HOLE or StageRules.NOTOUCH_ORB) == 0
                }) out.add("火焰區的格子都不能碰或是黑洞，最後一步沒有地方可以停")
            return out
        }

        override fun boardConflicts(r: StageRules, cells: IntArray, colors: IntArray): List<String> {
            val out = ArrayList<String>()
            val n = minOf(cells.size, StageRules.MAX_CELLS)
            for (i in 0 until n)
                if (r.cellFlags[i] and StageRules.SHIELD != 0 && cells[i] and 0x60 == 0x60) out.add("${pos(i)} 是拼圖盾，但那顆珠不能碰也不能消（冰封／石化）")
            val shieldN = r.count(StageRules.SHIELD)
            if (shieldN > 0) {
                val b = BoardCount(colors, r.antiMask)
                val most = b.cnt.maxOrNull() ?: 0
                if (most < maxOf(3, shieldN)) out.add("拼圖盾有 $shieldN 格，要同一種顏色鋪滿，但這盤同色可消的珠最多只有 $most 顆")
            }
            return out
        }

        override fun impossible(r: StageRules, b: BoardCount): String? {
            // 自點必消：那顆珠的顏色在盤面上（扣掉不能消的）至少要 3 顆
            val colors = b.colors
            val avail = IntArray(6)
            for (i in colors.indices) if (colors[i] in 0..5 && (i >= StageRules.MAX_CELLS || r.cellFlags[i] and StageRules.NO_ORB == 0)) avail[colors[i]]++
            for (i in colors.indices) {
                if (i >= StageRules.MAX_CELLS || r.cellFlags[i] and StageRules.MUST_ORB == 0) continue
                val c = colors[i]
                if (c !in 0..5) return "${pos(i)}標了必消，但那顆珠不能消除"
                if (avail[c] < 3) return "${pos(i)}的${ORB[c]}珠標了必消，但能消的${ORB[c]}只有 ${avail[c]} 顆"
            }
            return null
        }

        override val controls: List<Ctl> = CELL_FLAGS.map { f ->
            Ctl.Brush("cell${f.flag}", f.brush, { r, i ->
                if (i in 0 until StageRules.MAX_CELLS) {
                    r.toggle(i, f.flag)
                    if (r.cellFlags[i] and f.flag != 0) r.cellFlags[i] = r.cellFlags[i] and (EXCLUSIVE[f.flag] ?: 0).inv()
                }
            }, { it.count(f.flag) })
        }
    }

    /** 綠漩渦（起點）／紅漩渦（終點） */
    private object Vortex : Cond("漩渦") {
        override fun isSet(r: StageRules) = r.green >= 0 || r.red >= 0
        override fun clear(r: StageRules) { r.green = -1; r.red = -1 }
        override fun copy(dst: StageRules, src: StageRules) { dst.green = src.green; dst.red = src.red }
        override fun merge(dst: StageRules, src: StageRules) {
            if (src.green >= 0) dst.green = src.green
            if (src.red >= 0) dst.red = src.red
        }
        override fun summary(r: StageRules) = listOfNotNull(
            if (r.green >= 0) "起點 ${pos(r.green)}" else null,
            if (r.red >= 0) "終點 ${pos(r.red)}" else null,
        )
        override fun save(r: StageRules) = listOf("g=${r.green}", "r=${r.red}")
        override fun load(r: StageRules, k: String, v: String): Boolean = when (k) {
            "g" -> { r.green = v.toIntOrNull() ?: -1; true }
            "r" -> { r.red = v.toIntOrNull() ?: -1; true }
            else -> false
        }

        override fun conflicts(r: StageRules): List<String> {
            val out = ArrayList<String>()
            fun f(i: Int) = r.cellFlags[i]
            if (r.green in 0 until StageRules.MAX_CELLS && f(r.green) and (StageRules.NO_TOUCH or StageRules.NOTOUCH_ORB) != 0)
                out.add("起點（綠）設在${pos(r.green)}，但那格不能碰，無法從那裡出發")
            if (r.red in 0 until StageRules.MAX_CELLS) {
                if (f(r.red) and (StageRules.NO_TOUCH or StageRules.NOTOUCH_ORB) != 0) out.add("終點（紅）設在${pos(r.red)}，但那格不能碰，無法停在那裡")
                if (f(r.red) and StageRules.BLACK_HOLE != 0) out.add("終點（紅）設在${pos(r.red)}，但那格是黑洞（不能停）")
                if (r.count(StageRules.FLAME_END) > 0 && f(r.red) and StageRules.FLAME_END == 0) out.add("終點（紅）設在${pos(r.red)}，但最後一步必須停在火焰區，兩個條件矛盾")
            }
            if (r.green >= 0 && r.green == r.red && r.sticky) out.add("起點和終點同一格，但黏手路徑不能再回到走過的格")
            return out
        }

        override fun boardConflicts(r: StageRules, cells: IntArray, colors: IntArray): List<String> {
            val out = ArrayList<String>()
            val n = minOf(cells.size, StageRules.MAX_CELLS)
            if (r.green >= n || r.red >= n) out.add("起點或終點超出盤面")
            if (r.green in 0 until n && cells[r.green] and 0x40 != 0) out.add("起點（綠）設在${pos(r.green)}，但那顆珠不能碰（電擊／冰封等），無法從那裡出發")
            if (r.red in 0 until n && cells[r.red] and 0x40 != 0) out.add("終點（紅）設在${pos(r.red)}，但那顆珠不能碰，無法停在那裡")
            return out
        }

        override val controls: List<Ctl> = listOf(
            Ctl.Brush("green", "起點(綠)", { r, i -> r.green = if (r.green == i) -1 else i }, { if (it.green >= 0) 1 else 0 }),
            Ctl.Brush("red", "終點(紅)", { r, i -> r.red = if (r.red == i) -1 else i }, { if (it.red >= 0) 1 else 0 }),
        )
    }

    // ---------------- 數值條件 ----------------

    private val COMBO_VALUES = intArrayOf(0, 5, 6, 7, 8, 9, 10, 12)

    private object Combo : IntCond("Combo 盾", "c", 0, { it.comboMin }, { r, v -> r.comboMin = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("Combo≥${r.comboMin}") else emptyList()
        override val controls: List<Ctl> = listOf(Ctl.Choice("combo",
            { r -> if (r.comboMin == 0) "Combo盾：無" else "Combo盾 ≥${r.comboMin}" },
            COMBO_VALUES.map { if (it == 0) "無" else "≥$it" },
            { r -> COMBO_VALUES.indexOf(r.comboMin) },
            { r, k -> r.comboMin = COMBO_VALUES[k]; null }))
    }

    /** 首消 Combo ≥X（讀技能卡設定；面板上用「首消剛好」） */
    private object FirstMin : IntCond("首消 Combo 盾", "f", 0, { it.firstMin }, { r, v -> r.firstMin = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("首消Combo≥${r.firstMin}") else emptyList()
        override fun impossible(r: StageRules, b: BoardCount) =
            if (r.firstMin > 0 && b.ubAll < r.firstMin) "首消要 ≥${r.firstMin} combo${ban(r)}，這盤最多只能 ${b.ubAll} combo" else null
    }

    private fun ban(r: StageRules) = if (r.antiMask != 0) "（首消不能消 ${maskText(r.antiMask)}）" else ""

    private object FirstExact : IntCond("首消剛好 Combo", "x", -1, { it.firstExact }, { r, v -> r.firstExact = v }) {
        override fun precise(r: StageRules) = isSet(r)
        val values = intArrayOf(-1) + (1..10).toList()
        override fun summary(r: StageRules) = if (isSet(r)) listOf("首消剛好 ${r.firstExact} Combo") else emptyList()
        override fun impossible(r: StageRules, b: BoardCount) =
            if (r.firstExact > 0 && b.ubAll < r.firstExact) "首消要剛好 ${r.firstExact} combo${ban(r)}，這盤最多只能 ${b.ubAll} combo" else null
        override val controls: List<Ctl> = listOf(Ctl.Choice("first",
            { r -> if (r.firstExact < 0) "首消Combo：無" else "首消Combo：剛好 ${r.firstExact}" },
            values.map { if (it < 0) "無" else "$it" },
            { r -> values.indexOf(r.firstExact) },
            { r, k ->
                val was = r.firstExact
                r.firstExact = values[k]
                r.firstMin = 0
                // 設定首消 Combo 時預設「每組只能 3 顆」（不能有 4 顆連珠）；可再按「首消每組3顆」關掉
                when {
                    was < 0 && r.firstExact > 0 && !r.first3Only -> { r.first3Only = true; "已一起打開「首消每組3顆」（不要可以再點一下關掉）" }
                    r.firstExact < 0 && r.first3Only -> { r.first3Only = false; "已一起關掉「首消每組3顆」" }
                    else -> null
                }
            }))
    }

    private object First3 : BoolCond("首消每組 3 顆", "f3", { it.first3Only }, { r, v -> r.first3Only = v }) {
        override fun precise(r: StageRules) = isSet(r)
        override fun summary(r: StageRules) = if (isSet(r)) listOf("首消每組 3 顆") else emptyList()
        fun mismatch(r: StageRules) =
            if (r.first3Only && r.firstExact > 0 && r.firstOrbsExact > 0 && r.firstOrbsExact != r.firstExact * 3)
                "首消剛好 ${r.firstExact} Combo、每組 3 顆＝${r.firstExact * 3} 粒，跟 NUM 盾 ${r.firstOrbsExact} 粒互相矛盾"
            else null
        override fun conflicts(r: StageRules) = listOfNotNull(mismatch(r))
        override fun impossible(r: StageRules, b: BoardCount) = mismatch(r)
        override val controls: List<Ctl> = listOf(Ctl.Toggle("three", { r -> "首消每組3顆：${onOff(r)}" }, { r -> r.first3Only = !r.first3Only }))
    }

    private object Elec : BoolCond("電擊符石", "ue", { it.unsureElec }, { r, v -> r.unsureElec = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("電擊符石（不碰、要消）") else emptyList()
    }

    private object Attr : IntCond("首消屬性盾", "fa", 0, { it.firstAttrMin }, { r, v -> r.firstAttrMin = v }) {
        val values = intArrayOf(0, 4, 5, 6, 7, 8)
        override fun summary(r: StageRules) = if (isSet(r)) listOf("首消屬性≥${r.firstAttrMin}") else emptyList()
        override fun impossible(r: StageRules, b: BoardCount) =
            if (r.firstAttrMin > 0 && b.ubAttr < r.firstAttrMin) "首消要 ≥${r.firstAttrMin} 組屬性符石${ban(r)}，這盤最多只能 ${b.ubAttr} 組" else null
        override val controls: List<Ctl> = listOf(Ctl.Choice("attr",
            { r -> if (r.firstAttrMin == 0) "首消屬性盾：無" else "首消屬性 ≥${r.firstAttrMin}" },
            values.map { if (it == 0) "無" else "≥$it" },
            { r -> values.indexOf(r.firstAttrMin) },
            { r, k -> r.firstAttrMin = values[k]; null }))
    }

    private object Num : IntCond("NUM 盾", "fo", 0, { it.firstOrbsExact }, { r, v -> r.firstOrbsExact = v }) {
        override fun precise(r: StageRules) = isSet(r)
        val values = intArrayOf(0) + (10..24).toList()
        override fun summary(r: StageRules) = if (isSet(r)) listOf("NUM盾 首消剛好${r.firstOrbsExact}粒") else emptyList()
        override fun impossible(r: StageRules, b: BoardCount): String? {
            val x = r.firstOrbsExact
            if (x <= 0) return null
            // 每種顏色首批可消 0 或 3~該色顆數，看能不能湊出剛好 X 粒
            var can = BooleanArray(x + 1).also { it[0] = true }
            for (k in 0 until 6) {
                val nx = can.copyOf()
                for (s in 0..x) if (can[s]) for (t in 3..b.cnt[k]) if (s + t <= x) nx[s + t] = true
                can = nx
            }
            return if (!can[x]) "首消要剛好 $x 粒符石${ban(r)}，這盤湊不出來" else null
        }
        override val controls: List<Ctl> = listOf(Ctl.Choice("num",
            { r -> if (r.firstOrbsExact == 0) "NUM盾：無" else "NUM盾：剛好 ${r.firstOrbsExact} 粒" },
            values.map { if (it == 0) "無" else "$it" },
            { r -> values.indexOf(r.firstOrbsExact) },
            { r, k -> r.firstOrbsExact = values[k]; null }))
    }

    /** 多屬性盾（讀技能卡設定：這些顏色本回合每種至少消一組） */
    private object Elem : MaskCond("多屬性盾", "e", { it.elemMask }, { r, v -> r.elemMask = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("多屬性盾 ${maskText(r.elemMask)}") else emptyList()
        override fun impossible(r: StageRules, b: BoardCount): String? {
            val s = short(b, r.elemMask, 3)
            return if (s.isNotEmpty()) "必消 ${maskText(r.elemMask)}，但這盤 ${shortText(b, s)}，湊不出三連" else null
        }
    }

    private object Shape : Cond("形狀盾") {
        override fun precise(r: StageRules) = isSet(r)
        // 無 → 十字≥1、≥2、≥3 → T 形≥1… → L 形≥1…
        val choices = listOf(0 to 0) + (1..3).flatMap { t -> (1..3).map { t to it } }
        override fun isSet(r: StageRules) = r.shapeMin > 0
        override fun clear(r: StageRules) { r.shapeType = 0; r.shapeMin = 0 }
        override fun copy(dst: StageRules, src: StageRules) { dst.shapeType = src.shapeType; dst.shapeMin = src.shapeMin }
        override fun summary(r: StageRules) = if (isSet(r)) listOf("${StageRules.SHAPE_NAMES[r.shapeType]}限盾 ≥${r.shapeMin} 組") else emptyList()
        override fun save(r: StageRules) = listOf("sh=${r.shapeType}", "sm=${r.shapeMin}")
        override fun load(r: StageRules, k: String, v: String): Boolean = when (k) {
            "sh" -> { r.shapeType = (v.toIntOrNull() ?: 0).coerceIn(0, 3); true }
            "sm" -> { r.shapeMin = v.toIntOrNull() ?: 0; true }
            else -> false
        }
        override fun impossible(r: StageRules, b: BoardCount): String? {
            if (r.shapeMin <= 0) return null
            val most = b.all.sumOf { it / 5 }
            return if (most < r.shapeMin) "${StageRules.SHAPE_NAMES[r.shapeType]}限盾要 ${r.shapeMin} 組（每組 5 粒同色），這盤最多只能湊 $most 組" else null
        }
        override val controls: List<Ctl> = listOf(Ctl.Choice("shape",
            { r -> if (r.shapeMin == 0) "形狀盾：無" else "${StageRules.SHAPE_NAMES[r.shapeType]}限盾 ≥${r.shapeMin} 組" },
            choices.map { (t, m) -> if (m == 0) "無" else "${StageRules.SHAPE_NAMES[t]}≥$m" },
            { r -> choices.indexOf(r.shapeType to r.shapeMin) },
            { r, k -> val (t, m) = choices[k]; r.shapeType = t; r.shapeMin = m; null }))
    }

    /** 同色組數盾：選 2～4 色，第一批每色各剛好 K 組、每組剛好 3 顆 */
    private object Group : Cond("同色組數盾") {
        override fun precise(r: StageRules) = r.groupActive()
        override fun isSet(r: StageRules) = r.groupMask != 0
        override fun clear(r: StageRules) { r.groupMask = 0; r.groupK = 1 }
        override fun copy(dst: StageRules, src: StageRules) { dst.groupMask = src.groupMask; dst.groupK = src.groupK }
        override fun summary(r: StageRules) = if (!isSet(r)) emptyList() else listOf(if (r.groupActive()) r.groupText() else "同色組數盾（要選 2～4 色）")
        override fun save(r: StageRules) = listOf("gm=${r.groupMask}", "gk=${r.groupK}")
        override fun load(r: StageRules, k: String, v: String): Boolean = when (k) {
            "gm" -> { r.groupMask = (v.toIntOrNull() ?: 0) and 0x3F; true }
            "gk" -> { r.groupK = (v.toIntOrNull() ?: 1).coerceIn(1, 3); true }
            else -> false
        }
        override fun conflicts(r: StageRules): List<String> {
            if (r.groupMask == 0) return emptyList()
            val n2 = Integer.bitCount(r.groupMask)
            if (n2 !in 2..4) return listOf("同色組數盾要選 2～4 種顏色（現在選了 $n2 種）")
            val out = ArrayList<String>()
            if (r.groupMask and r.antiMask != 0) out.add("同色組數盾要消 ${maskText(r.groupMask and r.antiMask)}，但首消禁 ${maskText(r.antiMask)}，兩個條件矛盾")
            if (r.firstExact in 0 until n2 * r.groupK) out.add("同色組數盾第一批至少 ${n2 * r.groupK} 組，但首消剛好 ${r.firstExact} Combo，兩個條件矛盾")
            if (r.firstOrbsExact in 1 until n2 * r.groupK * 3) out.add("同色組數盾第一批至少 ${n2 * r.groupK * 3} 粒，但 NUM 盾剛好 ${r.firstOrbsExact} 粒，兩個條件矛盾")
            return out
        }
        override fun impossible(r: StageRules, b: BoardCount): String? {
            if (!r.groupActive()) return null
            val need = 3 * r.groupK
            val s = short(b, r.groupMask, need)
            return if (s.isNotEmpty()) "${r.groupText()}，每色至少要 $need 顆，但這盤 ${shortText(b, s)}" else null
        }
        override val controls: List<Ctl> = listOf(
            Ctl.Choice("groupK", { r -> "同色組數：各 ${r.groupK} 組" }, listOf("各 1 組", "各 2 組", "各 3 組"),
                { r -> r.groupK - 1 }, { r, k -> r.groupK = k + 1; null }),
            Ctl.Mask("groupMask", "同色組數(選2~4色)", { it.groupMask }) { r, c ->
                val bit = 1 shl c
                if (r.groupMask and bit == 0 && Integer.bitCount(r.groupMask) >= 4) "同色組數盾最多選 4 色"
                else {
                    r.groupMask = r.groupMask xor bit
                    if (r.groupMask and bit != 0 && r.antiMask and bit != 0) { r.antiMask = r.antiMask and bit.inv(); "已把${ORB[c]}從「首消不能消」拿掉（兩個條件不能同一色）" } else null
                }
            },
        )
    }

    /** 首消必消（全部消光） */
    private object AllClear : MaskCond("首消必消（全部消光）", "am", { it.allMask }, { r, v -> r.allMask = v }) {
        override fun precise(r: StageRules) = isSet(r)
        override fun summary(r: StageRules) = if (isSet(r)) listOf("首消必消 ${maskText(r.allMask)}（第一批全部消光）") else emptyList()
        override fun conflicts(r: StageRules): List<String> =
            if (r.allMask and r.antiMask != 0) listOf("首消必消 ${maskText(r.allMask and r.antiMask)}，但首消禁同一色，兩個條件矛盾") else emptyList()
        override fun impossible(r: StageRules, b: BoardCount): String? {
            // 那個顏色盤面上能消的珠要 ≥3 顆（3、4、5 顆一組可以湊出任何 ≥3 的數量）
            val s = (0 until 6).filter { (r.allMask shr it and 1) == 1 && b.all[it] in 1..2 }
            return if (s.isNotEmpty()) "首消必消 ${maskText(r.allMask)}（第一批全部消光），但這盤 ${shortText(b, s)}，湊不出三連" else null
        }
        override val controls: List<Ctl> = listOf(Ctl.Mask("allMask", "首消必消(全部消光)", { it.allMask }) { r, c ->
            val bit = 1 shl c
            r.allMask = r.allMask xor bit
            if (r.allMask and bit != 0 && r.antiMask and bit != 0) { r.antiMask = r.antiMask and bit.inv(); "已把${ORB[c]}從「首消不能消」拿掉（兩個條件不能同一色）" } else null
        })
    }

    /** 首消不能消 */
    private object Anti : MaskCond("首消不能消", "a", { it.antiMask }, { r, v -> r.antiMask = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("首消禁 ${maskText(r.antiMask)}") else emptyList()
        override val controls: List<Ctl> = listOf(Ctl.Mask("antiMask", "首消不能消(第一批)", { it.antiMask }) { r, c ->
            val bit = 1 shl c
            r.antiMask = r.antiMask xor bit
            val from = if (r.antiMask and bit == 0) emptyList() else listOfNotNull(
                if (r.allMask and bit != 0) "首消必消" else null,
                if (r.groupMask and bit != 0) "同色組數" else null,
            )
            if (r.antiMask and bit != 0) { r.allMask = r.allMask and bit.inv(); r.groupMask = r.groupMask and bit.inv() }
            if (from.isEmpty()) null else "已把${ORB[c]}從「${from.joinToString("、")}」拿掉（兩個條件不能同一色）"
        })
    }

    private object Sticky : BoolCond("黏手路徑", "s", { it.sticky }, { r, v -> r.sticky = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("黏手路徑") else emptyList()
        override val controls: List<Ctl> = listOf(Ctl.Toggle("sticky", { r -> "黏手路徑：${onOff(r)}" }, { r -> r.sticky = !r.sticky }))
    }

    private object FirePath : BoolCond("火焰路徑", "p", { it.firePath }, { r, v -> r.firePath = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("火焰路徑") else emptyList()
        override val controls: List<Ctl> = listOf(Ctl.Toggle("fire", { r -> "火焰路徑：${onOff(r)}" }, { r -> r.firePath = !r.firePath }))
    }

    /** 問號盤：珠子被蓋成問號時，先按住一顆移一格（顏色會出現），不放手、截圖規劃後接著轉完 */
    private object Reveal : BoolCond("問號盤", "rv", { it.revealMove }, { r, v -> r.revealMove = v }) {
        override fun summary(r: StageRules) = if (isSet(r)) listOf("問號盤（先移一格看顏色再轉）") else emptyList()
        override val controls: List<Ctl> = listOf(Ctl.Toggle("reveal", { r -> "問號盤先移一格：${onOff(r)}" }, { r -> r.revealMove = !r.revealMove }))
    }

    /** 全部條件模組（順序＝摘要順序、做不到檢查順序） */
    val ALL: List<Cond> = listOf(
        Cells, Combo, FirstMin, FirstExact, First3, Elec, Attr, Num, Elem, Shape, Group, AllClear, Anti, Vortex, Sticky, FirePath, Reveal,
    )

    /** 面板上全部控制項 */
    val CONTROLS: List<Ctl> get() = ALL.flatMap { it.controls }
}
