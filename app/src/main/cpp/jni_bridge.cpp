// Kotlin ↔ C++ 橋接（對應 com.rcj811.tossolver.NativeSolver）
#include <jni.h>

#include <vector>

#include "tos_solver.h"

namespace {

bool readCells(JNIEnv* env, jintArray cells, int rows, uint8_t* out) {
    int n = rows * tos::COLS;
    if (rows < 3 || rows > tos::MAX_ROWS || env->GetArrayLength(cells) < n) return false;
    std::vector<jint> buf(n);
    env->GetIntArrayRegion(cells, 0, n, buf.data());
    for (int i = 0; i < n; i++) out[i] = (uint8_t)(buf[i] & 0xFF);
    return true;
}

}  // namespace

extern "C" {

// 回傳 [found, combos, firstCombos, start, ms(整數), 步數, 方向...]
JNIEXPORT jintArray JNICALL Java_com_rcj811_tossolver_NativeSolver_solve(JNIEnv* env, jobject /*thiz*/,
                                                                          jintArray cells, jint rows, jint beam,
                                                                          jint maxPath, jint dirs) {
    uint8_t b[tos::MAX_CELLS];
    std::vector<jint> out(6, 0);
    if (readCells(env, cells, rows, b)) {
        tos::Config cfg;
        cfg.rows = rows;
        cfg.beam = beam;
        cfg.maxPath = maxPath;
        cfg.dirs = dirs == 4 ? 4 : 8;
        tos::Solution s = tos::solve(b, cfg);
        out[0] = s.found ? 1 : 0;
        out[1] = s.combos;
        out[2] = s.firstCombos;
        out[3] = s.start;
        out[4] = (jint)s.ms;
        out[5] = (jint)s.dirs.size();
        for (int d : s.dirs) out.push_back(d);
    }
    jintArray arr = env->NewIntArray((jsize)out.size());
    env->SetIntArrayRegion(arr, 0, (jsize)out.size(), out.data());
    return arr;
}

JNIEXPORT jint JNICALL Java_com_rcj811_tossolver_NativeSolver_countCombos(JNIEnv* env, jobject /*thiz*/,
                                                                          jintArray cells, jint rows) {
    uint8_t b[tos::MAX_CELLS];
    if (!readCells(env, cells, rows, b)) return -1;
    return tos::countCombos(b, rows);
}

}  // extern "C"
