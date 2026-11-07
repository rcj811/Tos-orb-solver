package com.rcj811.tossolver

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** 新增 / 刪除自訂符石類別的對話框（App 設定頁與遊戲中的校正畫面共用） */
object OrbTypeDialog {

    private val DISSOLVE_HINT = arrayOf(
        "照常消除（只是外觀不同，例：種族符石）",
        "必須消除（沒消掉就失敗，例：觸電、化血、引爆）",
        "盡量消除（消掉有好處，例：風化）",
        "盡量不要消除（消掉有壞處，例：弱化、邪霧）",
        "不能消除（例：冰封、石化）",
    )

    private fun dialogTheme() = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /** overlay = true：從無障礙服務（遊戲畫面上）開啟；existing 不為 null 時是修改 */
    fun showAdd(ctx: Context, overlay: Boolean, existing: OrbType? = null, onSaved: (OrbType) -> Unit) {
        val themed = ContextThemeWrapper(ctx, dialogTheme())
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val box = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(4))
        }
        val name = EditText(themed).apply {
            hint = "類別名稱（例：刺血符石）"
            setSingleLine()
        }
        box.addView(name)
        box.addView(TextView(themed).apply { text = "\n能不能碰？"; textSize = 15f })
        val touch = RadioGroup(themed).apply { orientation = RadioGroup.HORIZONTAL }
        val canTouch = RadioButton(themed).apply { text = "可以碰"; id = 1 }
        val noTouch = RadioButton(themed).apply { text = "不能碰（碰到會停止或受傷）"; id = 2 }
        touch.addView(canTouch)
        touch.addView(noTouch)
        touch.check(if (existing?.noTouch == true) 2 else 1)
        box.addView(touch)
        val start = android.widget.CheckBox(themed).apply {
            text = "必須從這顆開始轉（例：GO 起點符石）"
            isChecked = existing?.mustStart == true
        }
        box.addView(start)
        box.addView(TextView(themed).apply { text = "\n消除方式？"; textSize = 15f })
        val dissolve = RadioGroup(themed).apply { orientation = RadioGroup.VERTICAL }
        for (k in DISSOLVE_HINT.indices) dissolve.addView(RadioButton(themed).apply { text = DISSOLVE_HINT[k]; id = 100 + k })
        dissolve.check(100 + (existing?.dissolve ?: 0))
        if (existing != null) name.setText(existing.name)
        box.addView(dissolve)
        box.addView(TextView(themed).apply {
            text = "\n新增後，到校正畫面「長按」那顆珠子選這個類別，再按「儲存並學習」，之後就會自動辨識。"
            textSize = 13f
        })

        val dlg = AlertDialog.Builder(ctx, dialogTheme())
            .setTitle(if (existing == null) "新增符石類別" else "修改「${existing.name}」")
            .setView(ScrollView(themed).apply { addView(box) })
            .setPositiveButton(if (existing == null) "新增" else "儲存", null)
            .setNegativeButton("取消", null)
            .create()
        if (overlay) dlg.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) {
                    name.error = "請輸入名稱"
                    return@setOnClickListener
                }
                val store = OrbTypeStore(ctx)
                val noT = touch.checkedRadioButtonId == 2
                val dis = dissolve.checkedRadioButtonId - 100
                val st = start.isChecked && !noT   // 不能碰的珠不可能當起點
                val t = if (existing == null) store.add(n, noT, dis, st)
                else { store.update(existing.id, n, noT, dis, st); OrbTypes.get(existing.id) }
                dlg.dismiss()
                onSaved(t)
            }
        }
        dlg.show()
    }

    private fun confirm(ctx: Context, msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx, dialogTheme()).setMessage(msg)
            .setPositiveButton("刪除") { _, _ -> onYes() }
            .setNegativeButton("取消", null).show()
    }

    /** 自訂類別：選一個 → 修改規則 / 只清除學過的樣子 / 刪除 */
    fun showManageTypes(ctx: Context, onChanged: () -> Unit) {
        val list = OrbTypeStore(ctx).load()
        if (list.isEmpty()) return
        val cs = ColorStore(ctx)
        AlertDialog.Builder(ctx, dialogTheme())
            .setTitle("自訂符石類別")
            .setItems(list.map { "${it.name}（${it.describe()}，學過 ${cs.stateCount(it.id)} 個樣子）" }.toTypedArray()) { _, which ->
                val t = list[which]
                AlertDialog.Builder(ctx, dialogTheme())
                    .setTitle(t.name)
                    .setItems(arrayOf("修改規則（保留學過的樣子）", "只清除學過的樣子（重新標記）", "刪除這個類別")) { _, op ->
                        when (op) {
                            0 -> showAdd(ctx, overlay = false, existing = t) { onChanged() }
                            1 -> confirm(ctx, "清除「${t.name}」學過的樣子？之後要在校正畫面重新長按標記。") {
                                cs.forgetState(t.id); onChanged()
                            }
                            2 -> confirm(ctx, "刪除「${t.name}」？它學過的樣子也會一起刪除。") {
                                OrbTypeStore(ctx).delete(t.id); cs.forgetState(t.id); onChanged()
                            }
                        }
                    }
                    .setNegativeButton("返回", null).show()
            }
            .setNegativeButton("關閉", null).show()
    }

    /** 學習資料管理：一筆一筆檢視、刪除（設定錯誤時用） */
    fun showLearningData(ctx: Context, onChanged: () -> Unit) {
        val cs = ColorStore(ctx)
        val ps = StagePresetStore(ctx)
        val rs = RuleStore(ctx)
        val counts = cs.protoCounts()
        val special = OrbTypes.all().filter { it.id != OrbState.NORMAL }
        val items = arrayOf(
            "珠子顏色範例（你改過顏色的珠子）：" + (0 until 6).joinToString(" ") { "${StageRules.ORB_SHORT[it]}${counts[it]}" },
            "特殊符石樣子：" + special.joinToString(" ") { "${it.name}${cs.stateCount(it.id)}" },
            "記住的關卡：${ps.count} 個",
            "點擊規則：${rs.count} 條",
        )
        AlertDialog.Builder(ctx, dialogTheme())
            .setTitle("管理學習資料")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(ctx, dialogTheme())
                        .setTitle("清除哪種顏色的範例？（例如把木珠誤標成火，就清除「火」）")
                        .setItems((0 until 6).map { "${StageRules.ORB_SHORT[it]}：${counts[it]} 顆範例" }.toTypedArray()) { _, c ->
                            confirm(ctx, "清除「${StageRules.ORB_SHORT[c]}」的範例？之後在校正畫面重新改一顆即可。") { cs.clearProtos(c); onChanged() }
                        }.setNegativeButton("返回", null).show()
                    1 -> AlertDialog.Builder(ctx, dialogTheme())
                        .setTitle("清除哪種符石學過的樣子？")
                        .setItems(special.map { "${it.name}：${cs.stateCount(it.id)} 個樣子" }.toTypedArray()) { _, k ->
                            val t = special[k]
                            confirm(ctx, "清除「${t.name}」學過的樣子？") { cs.forgetState(t.id); onChanged() }
                        }.setNegativeButton("返回", null).show()
                    2 -> {
                        val l = ps.load()
                        if (l.isEmpty()) return@setItems
                        AlertDialog.Builder(ctx, dialogTheme())
                            .setTitle("刪除哪一關？")
                            .setItems(l.mapIndexed { i, p -> "關卡 ${i + 1}：${p.rules.summary()}（${p.seen} 回合）" }.toTypedArray()) { _, i ->
                                confirm(ctx, "刪除關卡 ${i + 1}？之後遇到會重新判斷。") { ps.delete(i); onChanged() }
                            }.setNegativeButton("返回", null).show()
                    }
                    3 -> {
                        val l = rs.load()
                        if (l.isEmpty()) return@setItems
                        AlertDialog.Builder(ctx, dialogTheme())
                            .setTitle("刪除哪一條規則？")
                            .setItems(l.mapIndexed { i, r ->
                                "規則 ${i + 1}：" + (if (r.action == TapRule.ACTION_STOP) "看到就停止" else "看到就點") +
                                    "（位置 ${r.x.toInt()}, ${r.y.toInt()}）"
                            }.toTypedArray()) { _, i ->
                                confirm(ctx, "刪除規則 ${i + 1}？") { rs.delete(i); onChanged() }
                            }.setNegativeButton("返回", null).show()
                    }
                }
            }
            .setNegativeButton("關閉", null).show()
    }
}
