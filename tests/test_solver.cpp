// 求解核心測試：規則正確性、路徑合法性、結果回歸
// 編譯：g++ -std=c++17 -O2 -Iapp/src/main/cpp app/src/main/cpp/tos_solver.cpp tests/test_solver.cpp -o test_solver
#include "tos_solver.h"

#include <cstdio>
#include <cstdlib>
#include <random>

using namespace tos;

static int failures = 0;
#define CHECK(cond, msg)                                       \
    do {                                                       \
        if (!(cond)) { std::printf("FAIL: %s\n", msg); failures++; } \
        else std::printf("ok:   %s\n", msg);                   \
    } while (0)

static void parse(const char* s, uint8_t* b, int n) {
    for (int i = 0; i < n; i++) b[i] = (uint8_t)(s[i] - '0');
}

int main() {
    uint8_t b[MAX_CELLS];

    // 規則
    parse("012345" "012345" "000345" "123450" "234501", b, 30);
    CHECK(countCombos(b, 5) == 4, "L 型 5 顆算 1 combo，加上 3 組直三連 = 4");

    parse("000123" "123450" "234501" "345012" "450123", b, 30);
    b[1] |= F_FROZEN;
    CHECK(countCombos(b, 5) == 0, "冰封珠不參與消除");

    parse("000123" "123450" "234501" "345012" "450123", b, 30);
    {
        Rules r;
        r.crack[2] = true;
        CHECK(countCombos(b, 5, r) == 0, "裂痕位置不參與消除");
    }

    // 連鎖：直 000 消掉後上方的 1 落下，與右側湊成橫 111
    parse("134534" "045345" "053453" "011232" "232323", b, 30);
    {
        Config cfg;
        Outcome o = replay(b, 0, {}, cfg);   // 不移動，直接結算
        CHECK(o.legal && o.firstCombos == 1 && o.combos == 2, "落下連鎖：首消 1、總 2 combo");
    }

    // 隨機盤：求解結果必須可合法重播且一致
    std::mt19937 rng(2026);
    Config cfg;
    long totalCombos = 0;
    int legal = 0;
    double ms = 0;
    const int boards = 100;
    for (int k = 0; k < boards; k++) {
        do { for (int i = 0; i < 30; i++) b[i] = rng() % 6; } while (countCombos(b, 5) > 0);
        Solution s = solve(b, cfg);
        Outcome o = replay(b, s.start, s.dirs, cfg);
        if (s.found && o.legal && o.combos == s.combos && o.firstCombos == s.firstCombos) legal++;
        totalCombos += s.combos;
        ms += s.ms;
    }
    std::printf("100 盤：平均 %.2f combo，平均 %.0f ms\n", totalCombos / (double)boards, ms / boards);
    CHECK(legal == boards, "所有解都能合法重播");
    CHECK(totalCombos >= 740, "平均 combo ≥ 7.4（驗證值約 7.6）");

    // 機制：觸電珠不可被路徑觸碰，風化珠不可當起點
    {
        do { for (int i = 0; i < 30; i++) b[i] = rng() % 6; } while (countCombos(b, 5) > 0);
        b[7] |= F_ELEC;
        b[20] |= F_WEATHER;
        Solution s = solve(b, cfg);
        Outcome o = replay(b, s.start, s.dirs, cfg);
        CHECK(s.found && o.legal && s.start != 7 && s.start != 20, "觸電 / 風化珠不可觸碰");
    }

    // 版面 6x8
    {
        Config c8;
        c8.rows = 8;
        c8.maxPath = defaultMaxPath(8);
        do { for (int i = 0; i < 48; i++) b[i] = rng() % 6; } while (countCombos(b, 8) > 0);
        Solution s = solve(b, c8);
        Outcome o = replay(b, s.start, s.dirs, c8);
        CHECK(s.found && o.legal && s.combos >= 9, "6x8 版面可求解（≥ 9 combo）");
    }

    std::printf(failures ? "\n%d 項失敗\n" : "\n全部通過\n", failures);
    return failures ? 1 : 0;
}
