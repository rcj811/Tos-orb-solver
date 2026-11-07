package com.rcj811.tossolver

// 不依賴 Android API（可在 JVM 單元測試中驗證）

/**
 * 把王的技能說明（長按王出現的視窗，經 OCR 讀成文字）對應成轉珠條件。
 * 對應依據：官方〈敵方技能及常用詞彙〉。讀不懂的技能不猜，列為「不認識」。
 */
object SkillParser {

    class Result(
        val rules: StageRules,
        val skills: List<String>,          // 讀到的技能名稱
        val applied: List<String>,         // 已自動設定成轉珠條件
        val manual: List<String>,          // 需要使用者補充（形狀、位置…）
        val byRecognition: List<String>,   // 由符石狀態辨識處理
        val noEffect: List<String>,        // 不影響轉珠
        val unknown: List<String>,         // 不認識
    ) {
        val found: Boolean get() = skills.isNotEmpty()

        fun summary(): String {
            val p = ArrayList<String>()
            if (applied.isNotEmpty()) p.add("已自動設定：" + applied.joinToString("、"))
            if (manual.isNotEmpty()) p.add("需要你補充：" + manual.joinToString("、"))
            if (byRecognition.isNotEmpty()) p.add("由符石辨識處理：" + byRecognition.joinToString("、"))
            if (noEffect.isNotEmpty()) p.add("不影響轉珠：" + noEffect.joinToString("、"))
            if (unknown.isNotEmpty()) p.add("不認識（未設定）：" + unknown.joinToString("、"))
            return p.joinToString("\n")
        }
    }

    private val COLOR = mapOf('火' to 0, '水' to 1, '木' to 2, '光' to 3, '暗' to 4, '心' to 5)
    private const val C = "[火水木光暗心]"

    /** OCR 常見誤差與簡體字統一 */
    fun normalize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            sb.append(
                when (ch) {
                    in '０'..'９' -> '0' + (ch - '０')
                    '≧', '＞', '>' -> '≥'
                    '〔', '〖', '［', '[', '「', '『' -> '【'
                    '〕', '〗', '］', ']', '」', '』' -> '】'
                    '撃' -> '擊'
                    else -> ch
                }
            )
        }
        var t = sb.toString()
        t = t.replace("≥=", "≥").replace("＝", "").replace("=", "")
        t = t.replace(Regex("\\s+"), "")
        val simp = mapOf(
            "连击" to "連擊", "颗" to "顆", "数量" to "數量", "連击" to "連擊", "组" to "組", "属性" to "屬性", "燃烧" to "燃燒", "轨" to "軌",
            "电击" to "電擊", "风化" to "風化", "冻结" to "凍結", "黏腐之轨" to "黏腐之軌", "盾牌" to "盾",
            "零化" to "零化", "固定" to "固定", "五属" to "五屬", "三属" to "三屬", "四属" to "四屬", "二属" to "二屬",
        )
        for ((a, b) in simp) t = t.replace(a, b)
        return t
    }

    /** 從敵人區域的文字找出 NUM 盾數字（OCR 常把 M 讀成 N/W/Ⅲ）；合理範圍 3~30 */
    fun parseNum(raw: String?): Int? {
        if (raw == null) return null
        val t = normalize(raw).uppercase()
        val m = Regex("N[UV][MNWⅢ][^0-9]{0,2}(\\d{1,2})").find(t) ?: return null
        return m.groupValues[1].toInt().takeIf { it in 3..30 }
    }

    fun extractSkills(norm: String): List<String> =
        Regex("【([^【】]{2,24})】").findAll(norm).map { it.groupValues[1] }.distinct().toList()

    private val MANUAL = listOf(
        "指定形狀盾" to "形狀盾（關掉技能視窗後按「關」，用筆刷畫出形狀）",
        "拼圖盾" to "拼圖盾（關掉技能視窗後按「關」，用筆刷畫出形狀）",
        "形狀盾" to "形狀盾（關掉技能視窗後按「關」，用筆刷畫出形狀）",
        "起結有序" to "起結有序（按「關」標出起點、終點）",
        "火焰地型" to "火焰地型（位置每回合不同）",
        "黑洞" to "黑洞（位置不同關卡不同）",
        "燃燒之地" to "燃燒之地（位置每回合不同）",
        "碎裂" to "碎裂（位置不同）",
        "煉獄之火" to "煉獄之火（位置不同）",
        "黏腐之地" to "黏腐之地",
        "幻界" to "幻界",
    )
    private val BY_RECOGNITION = listOf("電擊", "觸電", "風化", "凍結", "冰封", "石化", "化血", "引爆", "弱化", "邪霧", "隱藏", "枯藤", "泡沫", "泡沬", "黑白")
    private val NO_EFFECT = listOf(
        "攻前盾", "終結倒數", "倒數", "疲憊", "意志", "減傷", "自我回復", "回復", "攻擊力提升", "攻擊提升", "防禦",
        "技能封鎖", "技能鎖", "封鎖", "反擊", "一擊必殺", "血鐮", "中毒", "吸收", "增傷", "召喚", "延遲", "即死",
        "免疫", "無敵", "控制", "狂暴", "連擊攻擊", "暴怒", "屬性減傷", "傷害上限",
    )

    fun parse(raw: String): Result {
        val norm = normalize(raw)
        val skills = extractSkills(norm)
        val r = StageRules()
        val applied = ArrayList<String>()
        val manual = ArrayList<String>()
        val byRec = ArrayList<String>()
        val noEff = ArrayList<String>()
        val unknown = ArrayList<String>()

        for (s in skills) {
            var hit = false
            fun ok(label: String) { applied.add(label); hit = true }

            // 首消符石數量盾（盾牌圖示 NUM X）：首批消除 X 粒或以上符石
            Regex("首(?:消|批消除?)≥?(\\d{1,2})(?:粒|顆|個)(?:或以上)?(?:符石)?(?:數量)?盾?").find(s)?.let {
                r.firstOrbsExact = it.groupValues[1].toInt(); ok("首消剛好${r.firstOrbsExact}粒符石")
            }
            if (!hit) Regex("(?:首消)?符石數量盾≥?(\\d{1,2})|首消(\\d{1,2})粒").find(s)?.let {
                r.firstOrbsExact = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt(); ok("首消剛好${r.firstOrbsExact}粒符石")
            }
            if (!hit) Regex("首消≥?(\\d{1,2})組?屬性符石盾").find(s)?.let {
                r.firstAttrMin = it.groupValues[1].toInt(); ok("首消≥${r.firstAttrMin}組屬性符石（心不算）")
            }
            if (!hit) Regex("首消≥(\\d{1,2})(?:連擊|combo|Combo|COMBO|組)?盾").find(s)?.let {
                r.firstMin = it.groupValues[1].toInt(); ok("首消≥${r.firstMin} combo")
            }
            if (!hit) Regex("固定(\\d{1,2})連擊盾").find(s)?.let {
                r.firstExact = it.groupValues[1].toInt(); ok("首消剛好 ${r.firstExact} combo")
            }
            if (!hit) Regex("≥(\\d{1,2})連擊盾").find(s)?.let {
                r.comboMin = it.groupValues[1].toInt(); ok("≥${r.comboMin} combo")
            }
            if (!hit) Regex("($C)連擊零化").find(s)?.let {
                val c = COLOR.getValue(it.groupValues[1][0])
                r.antiMask = r.antiMask or (1 shl c); ok("首消不能消${it.groupValues[1]}（${it.groupValues[1]}連擊零化）")
            }
            if (!hit) Regex("反首消($C+)符石").find(s)?.let {
                for (ch in it.groupValues[1]) r.antiMask = r.antiMask or (1 shl COLOR.getValue(ch))
                ok("首消不能消${it.groupValues[1]}")
            }
            if (!hit && s.contains("五屬盾")) {
                r.elemMask = 0b11111; ok("同時消除五屬")
            }
            if (!hit) Regex("([火水木光暗]{2,4})(?:二|三|四)?屬盾").find(s)?.let {
                for (ch in it.groupValues[1]) r.elemMask = r.elemMask or (1 shl COLOR.getValue(ch))
                ok("同時消除${it.groupValues[1]}")
            }
            if (!hit && Regex("[二三四]屬盾").containsMatchIn(s)) {
                manual.add("$s（請按「關」選要同時消除的屬性）"); hit = true
            }
            if (!hit && s.contains("黏腐之軌")) { r.sticky = true; ok("黏腐之軌（路徑不重複）") }
            if (!hit && s.contains("燃燒之軌")) { r.firePath = true; ok("燃燒之軌（不重踩）") }
            if (!hit) MANUAL.firstOrNull { s.contains(it.first) }?.let { manual.add(it.second); hit = true }
            if (!hit && BY_RECOGNITION.any { s.contains(it) }) { byRec.add(s); hit = true }
            if (!hit && NO_EFFECT.any { s.contains(it) }) { noEff.add(s); hit = true }
            if (!hit) unknown.add(s)
        }
        return Result(r, skills, applied, manual.distinct(), byRec, noEff, unknown)
    }
}
