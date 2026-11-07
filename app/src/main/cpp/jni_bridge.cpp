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

// rules：StageRules.toInts()；空陣列 = 一般盤
// budgetMs：>0 時在此計算時間內逐步加寬 beam 重算（解題在拖曳之前，不佔遊戲轉珠時間）
// 回傳 [found, combos, firstCombos, start, ms, 步數, goalsMet, 消珠數, 方向...]
JNIEXPORT jintArray JNICALL Java_com_rcj811_tossolver_NativeSolver_solve(JNIEnv* env, jobject /*thiz*/,
                                                                          jintArray cells, jint rows, jint beam,
                                                                          jint maxPath, jint dirs, jintArray rules,
                                                                          jint budgetMs) {
    uint8_t b[tos::MAX_CELLS];
    std::vector<jint> out(8, 0);
    if (readCells(env, cells, rows, b)) {
        tos::Config cfg;
        cfg.rows = rows;
        cfg.beam = beam;
        cfg.maxPath = maxPath;
        cfg.dirs = dirs == 4 ? 4 : 8;
        cfg.timeBudgetMs = budgetMs;
        tos::Rules ru;
        if (rules != nullptr) {
            jsize n = env->GetArrayLength(rules);
            if (n >= tos::RULES_INTS_V1) {
                std::vector<jint> rv(n);
                env->GetIntArrayRegion(rules, 0, n, rv.data());
                std::vector<int> iv(rv.begin(), rv.end());
                tos::rulesFromInts(iv.data(), (int)iv.size(), ru);
            }
        }
        tos::Solution s = tos::solve(b, cfg, ru);
        out[0] = s.found ? 1 : 0;
        out[1] = s.combos;
        out[2] = s.firstCombos;
        out[3] = s.start;
        out[4] = (jint)s.ms;
        out[5] = (jint)s.dirs.size();
        out[6] = s.goalsMet ? 1 : 0;
        out[7] = s.cleared;
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
