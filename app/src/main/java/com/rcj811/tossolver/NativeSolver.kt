package com.rcj811.tossolver

/** C++ 求解核心（app/src/main/cpp）的 Kotlin 介面 */
object NativeSolver {
    init {
        System.loadLibrary("tossolver")
    }

    /** 回傳 [found, combos, firstCombos, start, ms, 步數, 方向...] */
    external fun solve(cells: IntArray, rows: Int, beam: Int, maxPath: Int, dirs: Int): IntArray

    /** 盤面原樣第一輪可消 combo 數；參數錯誤回傳 -1 */
    external fun countCombos(cells: IntArray, rows: Int): Int
}

/** 方向：0右 1下 2左 3上 4右下 5左下 6左上 7右上 */
object Dirs {
    val DX = intArrayOf(1, 0, -1, 0, 1, -1, -1, 1)
    val DY = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)
    val ARROW = arrayOf("→", "↓", "←", "↑", "↘", "↙", "↖", "↗")
}

class SolveResult(
    val found: Boolean,
    val combos: Int,
    val firstCombos: Int,
    val start: Int,
    val ms: Int,
    val dirs: IntArray,
) {
    companion object {
        fun from(raw: IntArray): SolveResult {
            if (raw.size < 6) return SolveResult(false, 0, 0, -1, 0, IntArray(0))
            val len = raw[5].coerceIn(0, raw.size - 6)
            return SolveResult(raw[0] == 1, raw[1], raw[2], raw[3], raw[4], raw.copyOfRange(6, 6 + len))
        }
    }

    /** 路徑經過的格子（含起點） */
    fun cellsOnPath(): IntArray {
        if (start < 0) return IntArray(0)
        val out = IntArray(dirs.size + 1)
        var x = start % 6
        var y = start / 6
        out[0] = start
        for (i in dirs.indices) {
            x += Dirs.DX[dirs[i]]
            y += Dirs.DY[dirs[i]]
            out[i + 1] = y * 6 + x
        }
        return out
    }
}
