package com.rcj811.tossolver

// 不依賴 Android API（可在 JVM 單元測試中驗證）

/**
 * 這一關的機制（在工作列「關」設定），編碼後傳給 C++ 求解核心。
 * 編碼格式需與 tos_solver.h 的 RULES_INTS 說明一致。
 */
class StageRules {
    companion object {
        const val MAX_CELLS = 48
        const val INTS_V1 = 12 + MAX_CELLS * 2
        const val INTS = INTS_V1 + 8
        val SHAPE_NAMES = arrayOf("", "十字", "T 形", "L 形")          // [108] firstAttrMin

        const val SHIELD = 1        // 拼圖盾格
        const val CRACK = 2         // 裂痕（不能消除）
        const val NO_TOUCH = 4      // 燃燒 / 地獄火 / 黏手之地 / 雷射（不能碰）
        const val FLAME_END = 8     // 火焰區（最後一步要停在其中一格）
        const val BLACK_HOLE = 16   // 黑洞（最後一步不能停在這）
        const val MUST_ORB = 32     // 自點必消：這一格目前的珠子這回合一定要消掉（只用在這一盤）
        const val NO_ORB = 64       // 自點不消：這一格目前的珠子這回合不能消（只用在這一盤）
        const val NOTOUCH_ORB = 128 // 自點不碰：這一格目前的珠子不能碰（路徑不能經過、不能當起點；只用在這一盤）
        const val ORB_MARKS = MUST_ORB or NO_ORB or NOTOUCH_ORB

        val ORB_SHORT = arrayOf("火", "水", "木", "光", "暗", "心")
    }

    var comboMin = 0       // Combo 盾（≥X）
    var firstMin = 0       // 首消 Combo 盾（≥X）
    var firstExact = -1    // 固定首消 X Combo（-1 = 無）
    var firstAttrMin = 0   // 首消 ≥X 組屬性符石盾（不含心珠）
    var first3Only = false   // 首消每組只能剛好 3 顆（不能有 4 顆連珠）
    var unsureElec = false   // 這關有電擊／觸電符石：認不出的珠（被特效蓋住）當電擊符石處理（不碰、必須消）
    var firstOrbsExact = 0   // 首消符石數量盾（NUM X）：首批消除「剛好」X 粒（疊珠不算）
    var antiMask = 0       // 首消不能消的珠種（bit = 珠種）
    var elemMask = 0       // 多屬性盾：本回合這些珠種每種至少消一組（讀技能卡設定）
    var shapeType = 0      // 形狀盾：0 無、1 十字、2 T 形、3 L 形（首批以這個形狀消除 5 粒同色）
    var shapeMin = 0       // 形狀盾：至少幾組
    var allMask = 0        // 首消必消（全部消光，第一批就要消掉）：這些珠種盤面上的珠全部要消掉（3 的倍數每組 3 顆，不是就用 4、5 顆一組）
    var sticky = false     // 黏手路徑：碰過的格不能再碰
    var firePath = false   // 火焰路徑：不要重踩剛走過的格
    var green = -1         // 綠漩渦：起點
    var red = -1           // 紅漩渦：終點
    val cellFlags = IntArray(MAX_CELLS)

    fun isEmpty(): Boolean = comboMin == 0 && firstMin == 0 && firstExact < 0 && firstAttrMin == 0 && firstOrbsExact == 0 && !first3Only && !unsureElec && antiMask == 0 && elemMask == 0 && allMask == 0 && shapeMin == 0 &&
        !sticky && !firePath && green < 0 && red < 0 && cellFlags.all { it == 0 }

    fun clear() {
        comboMin = 0; firstMin = 0; firstExact = -1; firstAttrMin = 0; firstOrbsExact = 0; first3Only = false; unsureElec = false; antiMask = 0; elemMask = 0; allMask = 0; shapeType = 0; shapeMin = 0
        sticky = false; firePath = false; green = -1; red = -1
        cellFlags.fill(0)
    }

    fun count(flag: Int) = cellFlags.count { it and flag != 0 }

    /** 把自點的必消 / 不消標記加到這一盤的珠子上（跟著珠子走：0x10 必須消除、0x20 不能消除） */
    fun applyOrbMarks(cells: IntArray) {
        for (i in cells.indices) {
            if (i >= MAX_CELLS) break
            // 必消：相信使用者的標記，就算顏色沒把握也照最可能的顏色消（拿掉「不能消除」）；原本不能碰的仍然不碰
            if (cellFlags[i] and MUST_ORB != 0) cells[i] = (cells[i] and 0x20.inv()) or 0x10
            if (cellFlags[i] and NO_ORB != 0) cells[i] = cells[i] or 0x20
            if (cellFlags[i] and NOTOUCH_ORB != 0) cells[i] = cells[i] or 0x40
        }
    }

    /** 存成關卡記錄時去掉點珠標記（每盤珠子位置都不同，不能沿用） */
    fun withoutOrbMarks(): StageRules = StageRules().also { r ->
        r.copyFrom(this)
        for (i in 0 until MAX_CELLS) r.cellFlags[i] = r.cellFlags[i] and ORB_MARKS.inv()
    }

    fun toggle(cell: Int, flag: Int) {
        if (cell !in 0 until MAX_CELLS) return
        cellFlags[cell] = cellFlags[cell] xor flag
    }

    /** forceStart ≥ 0：本回合盤面上有 GO 起點珠，強制從這格出發（覆蓋綠漩渦設定） */
    fun toInts(forceStart: Int = -1): IntArray {
        if (isEmpty() && forceStart < 0) return IntArray(0)
        val v = IntArray(INTS)
        v[0] = 1
        v[1] = comboMin; v[2] = firstMin; v[3] = firstExact
        v[4] = antiMask; v[5] = elemMask
        v[6] = if (sticky) 1 else 0
        v[7] = if (firePath) 1 else 0
        v[8] = 6; v[9] = 0
        v[10] = if (forceStart >= 0) forceStart else green; v[11] = red
        for (i in 0 until MAX_CELLS) {
            v[12 + i] = cellFlags[i] and ORB_MARKS.inv()   // 點珠標記改由珠子旗標傳入
            v[12 + MAX_CELLS + i] = -1
        }
        v[INTS_V1] = firstAttrMin
        v[INTS_V1 + 1] = firstOrbsExact
        v[INTS_V1 + 2] = if (first3Only) 1 else 0
        v[INTS_V1 + 3] = allMask
        v[INTS_V1 + 4] = if (shapeMin > 0) shapeType else 0
        v[INTS_V1 + 5] = shapeMin
        return v
    }

    /** 必消（全部消光）的顏色名稱 */
    fun allMaskText() = maskText(allMask)

    private fun maskText(m: Int) = (0 until 6).filter { m shr it and 1 == 1 }.joinToString("") { ORB_SHORT[it] }

    fun summary(): String {
        if (isEmpty()) return "一般盤（沒有設定機制）"
        val parts = ArrayList<String>()
        if (count(SHIELD) > 0) parts.add("拼圖盾 ${count(SHIELD)} 格")
        if (comboMin > 0) parts.add("Combo≥$comboMin")
        if (firstMin > 0) parts.add("首消Combo≥$firstMin")
        if (firstExact >= 0) parts.add("首消剛好 $firstExact Combo")
        if (first3Only) parts.add("首消每組 3 顆")
        if (unsureElec) parts.add("電擊符石（不碰、要消）")
        if (firstAttrMin > 0) parts.add("首消屬性≥$firstAttrMin")
        if (firstOrbsExact > 0) parts.add("NUM盾 首消剛好${firstOrbsExact}粒")
        if (elemMask != 0) parts.add("多屬性盾 ${maskText(elemMask)}")
        if (shapeMin > 0) parts.add("${SHAPE_NAMES[shapeType]}限盾 ≥$shapeMin 組")
        if (allMask != 0) parts.add("首消必消 ${maskText(allMask)}（第一批全部消光）")
        if (antiMask != 0) parts.add("首消禁 ${maskText(antiMask)}")
        if (count(NO_TOUCH) > 0) parts.add("燃燒 ${count(NO_TOUCH)}")
        if (count(FLAME_END) > 0) parts.add("火焰區 ${count(FLAME_END)}")
        if (count(BLACK_HOLE) > 0) parts.add("黑洞 ${count(BLACK_HOLE)}")
        if (count(MUST_ORB) > 0) parts.add("必消珠 ${count(MUST_ORB)} 顆")
        if (count(NO_ORB) > 0) parts.add("不消珠 ${count(NO_ORB)} 顆")
        if (count(NOTOUCH_ORB) > 0) parts.add("不碰珠 ${count(NOTOUCH_ORB)} 顆")
        if (count(CRACK) > 0) parts.add("裂痕 ${count(CRACK)}")
        if (green >= 0 || red >= 0) parts.add("漩渦")
        if (sticky) parts.add("黏手路徑")
        if (firePath) parts.add("火焰路徑")
        return parts.joinToString("・")
    }

    fun copyFrom(o: StageRules) {
        comboMin = o.comboMin; firstMin = o.firstMin; firstExact = o.firstExact; firstAttrMin = o.firstAttrMin; firstOrbsExact = o.firstOrbsExact; first3Only = o.first3Only; unsureElec = o.unsureElec
        antiMask = o.antiMask; elemMask = o.elemMask; allMask = o.allMask; shapeType = o.shapeType; shapeMin = o.shapeMin; sticky = o.sticky; firePath = o.firePath
        green = o.green; red = o.red
        o.cellFlags.copyInto(cellFlags)
    }

    /** 把 o 中有設定的條件併入（OCR 讀到的條件 + 使用者畫的格子） */
    fun mergeFrom(o: StageRules) {
        if (o.comboMin > 0) comboMin = o.comboMin
        if (o.firstMin > 0) firstMin = o.firstMin
        if (o.firstExact >= 0) firstExact = o.firstExact
        if (o.first3Only) first3Only = true
        if (o.unsureElec) unsureElec = true
        if (o.firstAttrMin > 0) firstAttrMin = o.firstAttrMin
        if (o.firstOrbsExact > 0) firstOrbsExact = o.firstOrbsExact
        antiMask = antiMask or o.antiMask
        elemMask = elemMask or o.elemMask
        allMask = allMask or o.allMask
        if (o.shapeMin > 0) { shapeType = o.shapeType; shapeMin = o.shapeMin }
        sticky = sticky || o.sticky
        firePath = firePath || o.firePath
        if (o.green >= 0) green = o.green
        if (o.red >= 0) red = o.red
        for (i in 0 until MAX_CELLS) cellFlags[i] = cellFlags[i] or o.cellFlags[i]
    }

    /**
     * 首消條件在這個盤面「數學上」是否可能達成（首消不受天降影響，所以上限是精確的）。
     * colors：每格珠種，不能消除的格子給 -1。回傳 null = 可能；否則回傳原因。
     */
    /**
     * 條件之間（或條件和這一盤）真的衝突、不可能同時做到的地方；沒有衝突回傳空清單。
     * cells：求解器用的格子（低 3 bits 珠種，0x40 = 不能碰、0x20 = 不能消）；colors：可消的珠種（不能消給 -1）。
     */
    fun conflicts(cells: IntArray, colors: IntArray): List<String> {
        val out = ArrayList<String>()
        val n = minOf(cells.size, MAX_CELLS)
        fun pos(i: Int) = "第 ${i / 6 + 1} 列第 ${i % 6 + 1} 格"
        fun noTouch(i: Int) = i in 0 until n && (cellFlags[i] and NO_TOUCH != 0 || cells[i] and 0x40 != 0)
        if (green >= n || red >= n) out.add("起點或終點超出盤面")
        if (green in 0 until n && noTouch(green)) out.add("起點（綠）設在${pos(green)}，但那格不能碰（燃燒地形或不能碰的珠），無法從那裡出發")
        if (red in 0 until n) {
            if (noTouch(red)) out.add("終點（紅）設在${pos(red)}，但那格不能碰，無法停在那裡")
            if (cellFlags[red] and BLACK_HOLE != 0) out.add("終點（紅）設在${pos(red)}，但那格是黑洞（不能停）")
            if (count(FLAME_END) > 0 && cellFlags[red] and FLAME_END == 0) out.add("終點（紅）設在${pos(red)}，但最後一步必須停在火焰區，兩個條件矛盾")
        }
        if (count(FLAME_END) > 0 && (0 until n).none { cellFlags[it] and FLAME_END != 0 && !noTouch(it) && cellFlags[it] and BLACK_HOLE == 0 })
            out.add("火焰區的格子都不能碰或是黑洞，最後一步沒有地方可以停")
        for (i in 0 until n) {
            val f = cellFlags[i]
            if (f and SHIELD != 0) {
                if (f and CRACK != 0) out.add("${pos(i)} 同時是拼圖盾和裂痕：拼圖盾要消掉，裂痕不能消")
                if (f and NO_ORB != 0) out.add("${pos(i)} 同時是拼圖盾和「不消」的珠")
                if (cells[i] and 0x60 == 0x60) out.add("${pos(i)} 是拼圖盾，但那顆珠不能碰也不能消（冰封／石化）")
            }
            if (f and MUST_ORB != 0 && f and CRACK != 0) out.add("${pos(i)} 標了必消，但那格是裂痕（不能消）")
        }
        // 拼圖盾要剛好那個形狀：每一格都要在盾裡的「直或橫連續 3 格」上，否則不可能剛好消掉
        run {
            val rows = n / 6
            fun sh(x: Int, y: Int) = x in 0 until 6 && y in 0 until rows && cellFlags[y * 6 + x] and SHIELD != 0
            val bad = (0 until n).filter { i ->
                if (cellFlags[i] and SHIELD == 0) return@filter false
                val x = i % 6; val y = i / 6
                val h = (-2..0).any { d -> sh(x + d, y) && sh(x + d + 1, y) && sh(x + d + 2, y) }
                val v = (-2..0).any { d -> sh(x, y + d) && sh(x, y + d + 1) && sh(x, y + d + 2) }
                !h && !v
            }
            if (bad.isNotEmpty()) out.add(
                "拼圖盾的形狀不可能剛好消掉：" + bad.joinToString("、") { pos(it) } +
                    " 不在盾裡任何一條直或橫連續 3 格上（三連至少要 3 格）。請照遊戲裡盾的形狀把格子標完整"
            )
        }
        // 拼圖盾：要用同一種顏色鋪滿所有盾格，盤面上至少要有那麼多顆同色、可以消的珠
        val shieldN = count(SHIELD)
        if (shieldN > 0) {
            val cnt = IntArray(6)
            for (c in colors) if (c in 0..5 && (antiMask shr c and 1) == 0) cnt[c]++
            val most = cnt.maxOrNull() ?: 0
            if (most < maxOf(3, shieldN)) out.add("拼圖盾有 $shieldN 格，要同一種顏色鋪滿，但這盤同色可消的珠最多只有 $most 顆")
        }
        firstBatchImpossible(colors)?.let { out.add(it) }
        return out
    }

    fun firstBatchImpossible(colors: IntArray): String? {
        val cnt = IntArray(6)
        for (c in colors) if (c in 0..5 && (antiMask shr c and 1) == 0) cnt[c]++
        val ubAll = (0 until 6).sumOf { cnt[it] / 3 }
        val ubAttr = (0 until 5).sumOf { cnt[it] / 3 }
        val ban = if (antiMask != 0) "（首消不能消 ${maskText(antiMask)}）" else ""
        // 自點必消：那顆珠的顏色在盤面上（扣掉不能消的）至少要 3 顆
        run {
            val avail = IntArray(6)
            for (i in colors.indices) if (colors[i] in 0..5 && (i >= MAX_CELLS || cellFlags[i] and NO_ORB == 0)) avail[colors[i]]++
            for (i in colors.indices) {
                if (i >= MAX_CELLS || cellFlags[i] and MUST_ORB == 0) continue
                val c = colors[i]
                val r = i / 6 + 1; val col = i % 6 + 1
                if (c !in 0..5) return "第 $r 列第 $col 格標了必消，但那顆珠不能消除"
                if (avail[c] < 3) return "第 $r 列第 $col 格的${ORB_SHORT[c]}珠標了必消，但能消的${ORB_SHORT[c]}只有 ${avail[c]} 顆"
            }
        }
        // 形狀盾：每組要 5 粒同色，盤面上同色 5 粒以上才湊得出
        if (shapeMin > 0) {
            val cnt = IntArray(6)
            for (c in colors) if (c in 0..5) cnt[c]++
            val most = cnt.sumOf { it / 5 }
            if (most < shapeMin) return "${SHAPE_NAMES[shapeType]}限盾要 $shapeMin 組（每組 5 粒同色），這盤最多只能湊 $most 組"
        }
        // 必消（全部消光）：那個顏色盤面上能消的珠要 ≥3 顆（3、4、5 顆一組可以湊出任何 ≥3 的數量）
        if (allMask != 0) {
            val all = IntArray(6)
            for (c in colors) if (c in 0..5) all[c]++
            val short = (0 until 6).filter { (allMask shr it and 1) == 1 && all[it] in 1..2 }
            if (short.isNotEmpty())
                return "首消必消 ${maskText(allMask)}（第一批全部消光），但這盤 " + short.joinToString("、") { "${ORB_SHORT[it]}只有 ${all[it]} 顆" } + "，湊不出三連"
        }
        // 多屬性盾：每種顏色，盤面上能消的至少要 3 顆
        if (elemMask != 0) {
            val all = IntArray(6)
            for (c in colors) if (c in 0..5) all[c]++
            val short = (0 until 6).filter { (elemMask shr it and 1) == 1 && all[it] < 3 }
            if (short.isNotEmpty())
                return "必消 ${maskText(elemMask)}，但這盤 " + short.joinToString("、") { "${ORB_SHORT[it]}只有 ${all[it]} 顆" } + "，湊不出三連"
        }
        if (firstAttrMin > 0 && ubAttr < firstAttrMin)
            return "首消要 ≥$firstAttrMin 組屬性符石$ban，這盤最多只能 $ubAttr 組"
        // NUM 盾：每種顏色首批可消 0 或 3~該色顆數，看能不能湊出剛好 X 粒
        if (firstOrbsExact > 0) {
            var can = BooleanArray(firstOrbsExact + 1).also { it[0] = true }
            for (k in 0 until 6) {
                val nx = can.copyOf()
                for (s in 0..firstOrbsExact) if (can[s]) for (t in 3..cnt[k]) if (s + t <= firstOrbsExact) nx[s + t] = true
                can = nx
            }
            if (!can[firstOrbsExact]) return "首消要剛好 $firstOrbsExact 粒符石$ban，這盤湊不出來"
        }
        if (firstMin > 0 && ubAll < firstMin)
            return "首消要 ≥$firstMin combo$ban，這盤最多只能 $ubAll combo"
        if (first3Only && firstExact > 0 && firstOrbsExact > 0 && firstOrbsExact != firstExact * 3)
            return "首消剛好 $firstExact Combo、每組 3 顆＝${firstExact * 3} 粒，跟 NUM 盾 $firstOrbsExact 粒互相矛盾"
        if (firstExact > 0 && ubAll < firstExact)
            return "首消要剛好 $firstExact combo$ban，這盤最多只能 $ubAll combo"
        return null
    }

    /** 簡單文字格式（存檔用）：key=value;... */
    fun serialize(): String = listOf(
        "c=$comboMin", "f=$firstMin", "x=$firstExact", "fa=$firstAttrMin", "fo=$firstOrbsExact", "f3=${if (first3Only) 1 else 0}", "ue=${if (unsureElec) 1 else 0}", "a=$antiMask", "e=$elemMask", "am=$allMask", "sh=$shapeType", "sm=$shapeMin",
        "s=${if (sticky) 1 else 0}", "p=${if (firePath) 1 else 0}", "g=$green", "r=$red",
        "cells=" + cellFlags.joinToString(","),
    ).joinToString(";")

    fun deserialize(s: String) {
        clear()
        for (kv in s.split(';')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val k = kv.substring(0, i)
            val v = kv.substring(i + 1)
            when (k) {
                "c" -> comboMin = v.toIntOrNull() ?: 0
                "f" -> firstMin = v.toIntOrNull() ?: 0
                "x" -> firstExact = v.toIntOrNull() ?: -1
                "f3" -> first3Only = v == "1"
                "ue" -> unsureElec = v == "1"
                "fa" -> firstAttrMin = v.toIntOrNull() ?: 0
                "fo" -> firstOrbsExact = v.toIntOrNull() ?: 0
                "a" -> antiMask = v.toIntOrNull() ?: 0
                "e" -> elemMask = v.toIntOrNull() ?: 0
                "am" -> allMask = v.toIntOrNull() ?: 0
                "sh" -> shapeType = (v.toIntOrNull() ?: 0).coerceIn(0, 3)
                "sm" -> shapeMin = v.toIntOrNull() ?: 0
                "s" -> sticky = v == "1"
                "p" -> firePath = v == "1"
                "g" -> green = v.toIntOrNull() ?: -1
                "r" -> red = v.toIntOrNull() ?: -1
                "cells" -> v.split(',').forEachIndexed { j, t -> if (j < MAX_CELLS) cellFlags[j] = t.toIntOrNull() ?: 0 }
            }
        }
    }
}
