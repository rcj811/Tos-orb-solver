package com.rcj811.tossolver

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import java.io.File

/**
 * 自動化測試用的「假遊戲畫面」：全螢幕顯示測試腳本放進來的盤面圖片。
 * 只在模擬器開了測試旗標時作用；使用者手機上打開會直接關閉。
 */
class DemoScreenActivity : Activity() {
    private lateinit var img: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TestHooks.enabled(this)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.BLACK) }
        setContentView(img)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(i: Intent?) {
        val name = i?.getStringExtra("img") ?: return
        val f = File(filesDir, "demo/$name.jpg")   // 測試腳本用 run-as 放進 App 自己的資料夾
        val bm = BitmapFactory.decodeFile(f.path)
        TestHooks.log("畫面 $name ${if (bm != null) "${bm.width}x${bm.height}" else "讀不到 ${f.path}"}")
        img.setImageBitmap(bm)
    }
}
