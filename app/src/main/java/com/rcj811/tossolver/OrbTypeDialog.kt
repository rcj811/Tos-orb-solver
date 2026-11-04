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

    /** overlay = true：從無障礙服務（遊戲畫面上）開啟 */
    fun showAdd(ctx: Context, overlay: Boolean, onSaved: (OrbType) -> Unit) {
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
        touch.check(1)
        box.addView(touch)
        box.addView(TextView(themed).apply { text = "\n消除方式？"; textSize = 15f })
        val dissolve = RadioGroup(themed).apply { orientation = RadioGroup.VERTICAL }
        for (k in DISSOLVE_HINT.indices) dissolve.addView(RadioButton(themed).apply { text = DISSOLVE_HINT[k]; id = 100 + k })
        dissolve.check(100)
        box.addView(dissolve)
        box.addView(TextView(themed).apply {
            text = "\n新增後，到校正畫面「長按」那顆珠子選這個類別，再按「儲存並學習」，之後就會自動辨識。"
            textSize = 13f
        })

        val dlg = AlertDialog.Builder(ctx, dialogTheme())
            .setTitle("新增符石類別")
            .setView(ScrollView(themed).apply { addView(box) })
            .setPositiveButton("新增", null)
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
                val t = OrbTypeStore(ctx).add(n, touch.checkedRadioButtonId == 2, dissolve.checkedRadioButtonId - 100)
                dlg.dismiss()
                onSaved(t)
            }
        }
        dlg.show()
    }

    fun showDelete(ctx: Context, onDeleted: () -> Unit) {
        val store = OrbTypeStore(ctx)
        val list = store.load()
        if (list.isEmpty()) return
        AlertDialog.Builder(ctx, dialogTheme())
            .setTitle("刪除哪個自訂類別？")
            .setItems(list.map { "${it.name}（${it.describe()}）" }.toTypedArray()) { _, which ->
                val t = list[which]
                store.delete(t.id)
                ColorStore(ctx).forgetState(t.id)
                onDeleted()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
