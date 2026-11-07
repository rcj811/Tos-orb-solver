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
        const val INTS = INTS_V1 + 4          // [108] firstAttrMin

        const val SHIELD = 1        // 拼圖盾格
        const val CRACK = 2         // 裂痕（不能消除）
        const val NO_TOUCH = 4      // 燃燒 / 地獄火 / 黏手之地 / 雷射（不能碰）
        const val FLAME_END = 8     // 火焰區（最後一步要停在其中一格）
        const val BLACK_HOLE = 16   // 黑洞（最後一步不能停在這）

        val ORB_SHORT = arrayOf("火", "水", "木", "光", "暗", "心")
    }

    var comboMin = 0       // Combo 盾（≥X）
    var firstMin = 0       // 首消 Combo 盾（≥X）
    var firstExact = -1    // 固定首消 X Combo（-1 = 無）
    var firstAttrMin = 0   // 首消 ≥X 組屬性符石盾（不含心珠）
    var firstOrbsExact = 0   // 首消符石數量盾（NUM X）：首批消除「剛好」X 粒（疊珠不算）
    var antiMask = 0       // 首消不能消的珠種（bit = 珠種）
    var elemMask = 0       // 本回合必須消的珠種（多屬性盾）
    var sticky = false     // 黏手路徑：碰過的格不能再碰
    var firePath = false   // 火焰路徑：不要重踩剛走過的格
    var green = -1         // 綠漩渦：起點
    var red = -1           // 紅漩渦：終點
    val cellFlags = IntArray(MAX_CELLS)

    fun isEmpty(): Boolean = comboMin == 0 && firstMin == 0 && firstExact < 0 && firstAttrMin == 0 && firstOrbsExact == 0 && antiMask == 0 && elemMask == 0 &&
        !sticky && !firePath && green < 0 && red < 0 && cellFlags.all { it == 0 }

    fun clear() {
        comboMin = 0; firstMin = 0; firstExact = -1; firstAttrMin = 0; firstOrbsExact = 0; antiMask = 0; elemMask = 0
        sticky = false; firePath = false; green = -1; red = -1
        cellFlags.fill(0)
    }

    fun count(flag: Int) = cellFlags.count { it and flag != 0 }

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
            v[12 + i] = cellFlags[i]
            v[12 + MAX_CELLS + i] = -1
        }
        v[INTS_V1] = firstAttrMin
        v[INTS_V1 + 1] = firstOrbsExact
        return v
    }

    private fun maskText(m: Int) = (0 until 6).filter { m shr it and 1 == 1 }.joinToString("") { ORB_SHORT[it] }

    fun summary(): String {
        if (isEmpty()) return "一般盤（沒有設定機制）"
        val parts = ArrayList<String>()
        if (count(SHIELD) > 0) parts.add("拼圖盾 ${count(SHIELD)} 格")
        if (comboMin > 0) parts.add("Combo≥$comboMin")
        if (firstMin > 0) parts.add("首消Combo≥$firstMin")
        if (firstExact >= 0) parts.add("首消剛好 $firstExact Combo")
        if (firstAttrMin > 0) parts.add("首消屬性≥$firstAttrMin")
        if (firstOrbsExact > 0) parts.add("NUM盾 首消剛好${firstOrbsExact}粒")
        if (elemMask != 0) parts.add("必消 ${maskText(elemMask)}")
        if (antiMask != 0) parts.add("首消禁 ${maskText(antiMask)}")
        if (count(NO_TOUCH) > 0) parts.add("燃燒 ${count(NO_TOUCH)}")
        if (count(FLAME_END) > 0) parts.add("火焰區 ${count(FLAME_END)}")
        if (count(BLACK_HOLE) > 0) parts.add("黑洞 ${count(BLACK_HOLE)}")
        if (count(CRACK) > 0) parts.add("裂痕 ${count(CRACK)}")
        if (green >= 0 || red >= 0) parts.add("漩渦")
        if (sticky) parts.add("黏手路徑")
        if (firePath) parts.add("火焰路徑")
        return parts.joinToString("・")
    }

    fun copyFrom(o: StageRules) {
        comboMin = o.comboMin; firstMin = o.firstMin; firstExact = o.firstExact; firstAttrMin = o.firstAttrMin; firstOrbsExact = o.firstOrbsExact
        antiMask = o.antiMask; elemMask = o.elemMask; sticky = o.sticky; firePath = o.firePath
        green = o.green; red = o.red
        o.cellFlags.copyInto(cellFlags)
    }

    /** 把 o 中有設定的條件併入（OCR 讀到的條件 + 使用者畫的格子） */
    fun mergeFrom(o: StageRules) {
        if (o.comboMin > 0) comboMin = o.comboMin
        if (o.firstMin > 0) firstMin = o.firstMin
        if (o.firstExact >= 0) firstExact = o.firstExact
        if (o.firstAttrMin > 0) firstAttrMin = o.firstAttrMin
        if (o.firstOrbsExact > 0) firstOrbsExact = o.firstOrbsExact
        antiMask = antiMask or o.antiMask
        elemMask = elemMask or o.elemMask
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
    fun firstBatchImpossible(colors: IntArray): String? {
        val cnt = IntArray(6)
        for (c in colors) if (c in 0..5 && (antiMask shr c and 1) == 0) cnt[c]++
        val ubAll = (0 until 6).sumOf { cnt[it] / 3 }
        val ubAttr = (0 until 5).sumOf { cnt[it] / 3 }
        val ban = if (antiMask != 0) "（首消不能消 ${maskText(antiMask)}）" else ""
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
        if (firstExact > 0 && ubAll < firstExact)
            return "首消要剛好 $firstExact combo$ban，這盤最多只能 $ubAll combo"
        return null
    }

    /** 簡單文字格式（存檔用）：key=value;... */
    fun serialize(): String = listOf(
        "c=$comboMin", "f=$firstMin", "x=$firstExact", "fa=$firstAttrMin", "fo=$firstOrbsExact", "a=$antiMask", "e=$elemMask",
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
                "fa" -> firstAttrMin = v.toIntOrNull() ?: 0
                "fo" -> firstOrbsExact = v.toIntOrNull() ?: 0
                "a" -> antiMask = v.toIntOrNull() ?: 0
                "e" -> elemMask = v.toIntOrNull() ?: 0
                "s" -> sticky = v == "1"
                "p" -> firePath = v == "1"
                "g" -> green = v.toIntOrNull() ?: -1
                "r" -> red = v.toIntOrNull() ?: -1
                "cells" -> v.split(',').forEachIndexed { j, t -> if (j < MAX_CELLS) cellFlags[j] = t.toIntOrNull() ?: 0 }
            }
        }
    }
}
