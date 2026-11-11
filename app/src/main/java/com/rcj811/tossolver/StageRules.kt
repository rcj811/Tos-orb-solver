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
        val SHAPE_NAMES = arrayOf("", "十字", "T 形", "L 形")

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
    var groupMask = 0      // 同色組數盾：這些珠種（2～4 種）在第一批各消剛好 groupK 組、每組剛好 3 顆；其他珠種不限
    var groupK = 1
    var revealMove = false // 問號盤：先按住一顆移一格讓顏色出現，再規劃（不送給求解器，App 自己處理）
    var sticky = false     // 黏手路徑：碰過的格不能再碰
    var firePath = false   // 火焰路徑：不要重踩剛走過的格
    var green = -1         // 綠漩渦：起點
    var red = -1           // 紅漩渦：終點
    val cellFlags = IntArray(MAX_CELLS)

    fun isEmpty(): Boolean = StageConds.ALL.none { it.isSet(this) }

    fun clear() = StageConds.ALL.forEach { it.clear(this) }

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
        v[10] = if (green >= 0) green else forceStart; v[11] = red   // 使用者設定的起點優先
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
        v[INTS_V1 + 6] = if (groupActive()) groupMask else 0
        v[INTS_V1 + 7] = if (groupActive()) groupK else 0
        return v
    }

    /** 同色組數盾有效：選了 2～4 色 */
    fun groupActive() = Integer.bitCount(groupMask) in 2..4 && groupK > 0

    fun groupText() = "同色組數盾 ${StageConds.maskText(groupMask)} 各 $groupK 組（每組 3 顆）"

    /** 必消（全部消光）的顏色名稱 */
    fun allMaskText() = StageConds.maskText(allMask)

    fun summary(): String =
        if (isEmpty()) "一般盤（沒有設定機制）" else StageConds.ALL.flatMap { it.summary(this) }.joinToString("・")

    fun copyFrom(o: StageRules) = StageConds.ALL.forEach { it.copy(this, o) }

    /** 把 o 中有設定的條件併入（OCR 讀到的條件 + 使用者畫的格子） */
    fun mergeFrom(o: StageRules) = StageConds.ALL.forEach { it.merge(this, o) }

    /** 條件之間互相矛盾（不看盤面；「關」面板上即時顯示） */
    /** 有需要精準控制第一批的條件：轉珠只走上下左右（不斜走） */
    fun precise(): Boolean = StageConds.ALL.any { it.precise(this) }

    fun ruleConflicts(): List<String> = StageConds.ALL.flatMap { it.conflicts(this) }.distinct()

    /**
     * 條件之間（或條件和這一盤）真的衝突、不可能同時做到的地方；沒有衝突回傳空清單。
     * cells：求解器用的格子（低 3 bits 珠種，0x40 = 不能碰、0x20 = 不能消）；colors：可消的珠種（不能消給 -1）。
     */
    fun conflicts(cells: IntArray, colors: IntArray): List<String> {
        val out = ArrayList(ruleConflicts())
        for (c in StageConds.ALL) out.addAll(c.boardConflicts(this, cells, colors))
        firstBatchImpossible(colors)?.let { out.add(it) }
        return out.distinct()
    }

    /**
     * 首消條件在這個盤面「數學上」是否可能達成（首消不受天降影響，所以上限是精確的）。
     * colors：每格珠種，不能消除的格子給 -1。回傳 null = 可能；否則回傳原因。
     */
    fun firstBatchImpossible(colors: IntArray): String? {
        val b = BoardCount(colors, antiMask)
        for (c in StageConds.ALL) c.impossible(this, b)?.let { return it }
        return null
    }

    /** 簡單文字格式（存檔用）：key=value;... */
    fun serialize(): String = StageConds.ALL.flatMap { it.save(this) }.joinToString(";")

    fun deserialize(s: String) {
        clear()
        for (kv in s.split(';')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val k = kv.substring(0, i)
            val v = kv.substring(i + 1)
            StageConds.ALL.firstOrNull { it.load(this, k, v) }
        }
    }
}
