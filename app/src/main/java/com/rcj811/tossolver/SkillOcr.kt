package com.rcj811.tossolver

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** 手機上的中文文字辨識（Google ML Kit，模型內建在 App 裡，不需網路、不上傳） */
object SkillOcr {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /**
     * 讀敵人身上的 NUM 盾數字（每回合會變，例如 15 → 12 → 6）。
     * 只讀盤面上方、隊伍頭像以上的敵人區域；讀不到回傳 null。
     */
    fun readNum(frame: Frame, geo: BoardGeometry, cb: (Int?) -> Unit) {
        val h = (geo.top - geo.cell * 1.4f).toInt().coerceIn(1, frame.height)
        val bmp = try {
            Bitmap.createBitmap(frame.pixels, 0, frame.width, frame.width, h, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            cb(null); return
        }
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cb(SkillParser.parseNum(it.text)) }
                .addOnFailureListener { cb(null) }
                .addOnCompleteListener { bmp.recycle() }
        } catch (_: Throwable) {
            bmp.recycle()
            cb(null)
        }
    }

    /** 讀出整張截圖的文字；失敗回傳 null。結果在主執行緒回呼 */
    fun read(frame: Frame, cb: (String?) -> Unit) {
        val bmp = try {
            Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            cb(null); return
        }
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cb(it.text) }
                .addOnFailureListener { cb(null) }
                .addOnCompleteListener { bmp.recycle() }
        } catch (_: Throwable) {
            bmp.recycle()
            cb(null)
        }
    }
}
