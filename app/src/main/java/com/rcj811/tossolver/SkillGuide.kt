package com.rcj811.tossolver

/**
 * 敵人技能的破解說明（依官方「敵方技能及常用詞彙」整理），按「＋」讀技能卡後顯示。
 * 每一條：技能名稱關鍵字 → 規則意思、破解方法、自轉珠怎麼處理。
 * 比對時從長的關鍵字開始，避免「五屬盾」搶走「五屬傷害盾」。
 */
object SkillGuide {

    class Tip(val meaning: String, val howToBreak: String, val auto: String)

    private val TIPS: Map<String, Tip> = linkedMapOf(
        "五屬傷害盾" to Tip(
            "敵人受到火、水、木、光、暗五種屬性的傷害後，才會受到傷害，否則傷害變 1。無屬性傷害不受影響。",
            "這一回合五種屬性的隊員都要打到敵人：隊伍要有五屬成員，並且五種屬性符石都要消到（疊珠消到的也算）。也可以用「無視敵技」或無屬性傷害的技能。",
            "自動把火水木光暗都設成「必消」，找五色都有消到的轉法；這盤某色不到 3 顆時照最接近的轉。"),
        "五屬盾" to Tip(
            "同時消除五屬符石，才會對敵人造成傷害，否則傷害變 1。",
            "同一回合把火、水、木、光、暗五種符石都消掉。某色不足時先用技能轉珠補色。",
            "自動把火水木光暗都設成「必消」。"),
        "電擊符石" to Tip(
            "每回合隨機把幾粒符石變成電擊符石；有電擊符石沒消掉，全隊攻擊力變 0；一碰到電擊符石就立刻停止移動。",
            "轉珠路徑完全不要碰電擊符石，改把旁邊同色的珠移過去，讓電擊符石跟同色珠連成三顆一起消掉。也可以用「無視敵技」，或把電擊符石轉走、消除的技能。",
            "電擊符石當成「不能碰、必須消」：路徑繞開它，用旁邊的珠湊三連把它消掉。被閃電特效蓋住、認不出顏色的珠，也當成電擊符石處理。"),
        "觸電" to Tip(
            "被觸電的符石不能碰，而且要消掉。",
            "路徑繞開觸電符石，用旁邊同色珠把它湊成三連消掉。",
            "當成「不能碰、必須消」。"),
        "NUM" to Tip(
            "首批消除的符石數量要剛好等於盾上的數字（疊珠不算）。數字每回合可能改變。",
            "數清楚第一批會消幾粒，用 3 顆、4 顆、5 顆一組去湊到剛好的數字。",
            "每回合讀敵人身上的 NUM 數字，找首批剛好那個粒數的轉法。"),
        "首消" to Tip(
            "跟首批消除有關的盾（combo 數、屬性組數或粒數）。",
            "照盾的要求控制第一批消除，疊珠不算。",
            "照讀到的數字設定首消條件。"),
        "連擊零化" to Tip(
            "指定屬性的符石在首批被消除時，combo 不計算。",
            "第一批不要消那個屬性，留給疊珠或下一回合。",
            "設成「首消不能消」該屬性。"),
        "Combo盾" to Tip(
            "整回合 combo 數要達到指定數量才會造成傷害。",
            "盡量多疊 combo，可以用增加 combo 的技能。",
            "設成 Combo 盾，找 combo 最多的轉法。"),
        "風化" to Tip(
            "風化符石不能碰，消掉有好處。",
            "路徑繞開，盡量用旁邊同色珠把它消掉。",
            "在「校」標成風化／黏腐後自動辨識：不碰、盡量消。"),
        "冰封" to Tip(
            "冰封符石不能移動，也不能消除。",
            "繞開它，用其他珠子湊 combo。",
            "在「校」標成冰封／石化後自動辨識：不碰、不消。"),
        "石化" to Tip(
            "石化符石不能移動，也不能消除。",
            "繞開它，用其他珠子湊 combo。",
            "在「校」標成冰封／石化後自動辨識：不碰、不消。"),
        "十字限盾" to Tip(
            "首批要以「十」字形消除 5 粒同色符石，達到指定組數，才會造成傷害，否則傷害變 1。",
            "把同色 5 粒排成十字（中間 1 粒、上下左右各 1 粒）在第一批消掉；要 2 組就準備兩種顏色各 5 粒、或同色 10 粒。",
            "設成「十字限盾 ≥X 組」，找第一批有 X 組十字的轉法。"),
        "T形限盾" to Tip(
            "首批要以「T」字形消除 5 粒同色符石，達到指定組數。",
            "把同色 5 粒排成 T 字（一橫 3 粒，中間往下 2 粒，可旋轉）在第一批消掉。",
            "設成「T 形限盾 ≥X 組」。"),
        "L形限盾" to Tip(
            "首批要以「L」字形消除 5 粒同色符石，達到指定組數。",
            "把同色 5 粒排成 L 字（轉角往兩邊各 2 粒，可旋轉）在第一批消掉。",
            "設成「L 形限盾 ≥X 組」。"),
        "拼圖盾" to Tip(
            "要用同一種屬性把盾的形狀一次消掉。",
            "看清楚形狀，用同色珠排成那個形狀在第一批消除。",
            "在「關」用「拼圖盾」筆刷標出形狀。"),
    )

    /** 讀到的每個技能 → (技能名稱, 說明) */
    fun explain(skills: List<String>): List<Pair<String, Tip>> {
        val keys = TIPS.keys.sortedByDescending { it.length }
        val out = ArrayList<Pair<String, Tip>>()
        for (s in skills) {
            val k = keys.firstOrNull { s.contains(it, ignoreCase = true) } ?: continue
            if (out.none { it.first == s }) out.add(s to TIPS.getValue(k))
        }
        return out
    }

    /** 給對話框用的文字 */
    fun text(skills: List<String>, rules: StageRules): String {
        val sb = StringBuilder()
        val list = explain(skills)
        for ((name, t) in list) {
            sb.append("【").append(name.trim('【', '】')).append("】\n")
            sb.append("・規則：").append(t.meaning).append('\n')
            sb.append("・破解：").append(t.howToBreak).append('\n')
            sb.append("・自轉珠：").append(t.auto).append("\n\n")
        }
        val unknown = skills.filter { s -> list.none { it.first == s } }
        if (unknown.isNotEmpty()) sb.append("其他技能（不影響轉珠或還不認識）：").append(unknown.joinToString("、")).append("\n\n")
        sb.append("這回合的轉珠條件：").append(if (rules.isEmpty()) "一般盤" else rules.summary())
        return sb.toString()
    }
}
