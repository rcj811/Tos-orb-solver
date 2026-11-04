// 求解核心測試：規則正確性、路徑合法性、結果回歸
// 編譯：g++ -std=c++17 -O2 -Iapp/src/main/cpp app/src/main/cpp/tos_solver.cpp tests/test_solver.cpp -o test_solver
#include "tos_solver.h"

#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

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

    // 關卡機制：經由 App 使用的整數編碼（rulesFromInts）傳入，確認達成率與路徑合法
    {
        struct Case { const char* name; double minRate; void (*setup)(std::vector<int>&); };
        auto blank = [] {
            std::vector<int> v(RULES_INTS, 0);
            v[0] = 1; v[3] = -1; v[8] = 6; v[10] = -1; v[11] = -1;
            for (int i = 0; i < MAX_CELLS; i++) v[12 + MAX_CELLS + i] = -1;
            return v;
        };
        Case cases[] = {
            {"拼圖盾（十字）", 0.75, [](std::vector<int>& v) {
                 for (int i : {8, 13, 14, 15, 20}) v[12 + i] |= CF_SHIELD; }},
            {"拼圖盾（橫 5）", 0.75, [](std::vector<int>& v) {
                 for (int i : {13, 14, 15, 16, 17}) v[12 + i] |= CF_SHIELD; }},
            {"Combo 盾 ≥7", 0.75, [](std::vector<int>& v) { v[1] = 7; }},
            {"首消盾 ≥5", 0.90, [](std::vector<int>& v) { v[2] = 5; }},
            {"固定首消 3", 0.90, [](std::vector<int>& v) { v[3] = 3; }},
            {"三屬性盾（火水木）", 0.60, [](std::vector<int>& v) { v[5] = 0b000111; }},
            {"首消禁心", 0.90, [](std::vector<int>& v) { v[4] = 1 << HEART; }},
            {"火焰區（終點）", 0.90, [](std::vector<int>& v) {
                 for (int i : {3, 16, 27}) v[12 + i] |= CF_FLAME_END; }},
            {"黑洞（不可停）", 0.90, [](std::vector<int>& v) {
                 for (int i : {2, 14, 25}) v[12 + i] |= CF_BLACK_HOLE; }},
            {"綠紅漩渦", 0.90, [](std::vector<int>& v) { v[10] = 0; v[11] = 29; }},
            {"首消 ≥5 組屬性符石（不含心）", 0.85, [](std::vector<int>& v) { v[RULES_INTS_V1] = 5; }},
            {"首消 ≥6 組屬性符石（不含心）", 0.50, [](std::vector<int>& v) { v[RULES_INTS_V1] = 6; }},
            {"首消 ≥8 組屬性符石＋水連擊零化（截圖的王）", 0.0, [](std::vector<int>& v) {
                 v[RULES_INTS_V1] = 8; v[4] = 1 << WATER; }},
            {"燃燒＋黏手路徑", 0.0, [](std::vector<int>& v) {
                 v[6] = 1; for (int i : {9, 20}) v[12 + i] |= CF_NO_TOUCH; }},
        };
        for (auto& cs : cases) {
            std::vector<int> v = blank();
            cs.setup(v);
            Rules ru;
            bool decoded = rulesFromInts(v.data(), (int)v.size(), ru);
            std::mt19937 r2(77);
            int met = 0, ok = 0, feasible = 0;
            const int n = 60;
            for (int k = 0; k < n; k++) {
                do { for (int i = 0; i < 30; i++) b[i] = r2() % 6; } while (countCombos(b, 5) > 0);
                // 必要條件：多屬性盾需每色 ≥3 顆
                int cnt[6] = {};
                for (int i = 0; i < 30; i++) cnt[b[i]]++;
                bool f = true;
                for (int c = 0; c < 6; c++) if ((ru.elemMask >> c & 1) && cnt[c] < 3) f = false;
                feasible += f;
                Solution s = solve(b, cfg, ru);
                Outcome o = replay(b, s.start, s.dirs, cfg, ru);
                if (s.found && o.legal && o.combos == s.combos && o.goalsMet == s.goalsMet) ok++;
                if (s.goalsMet && f) met++;
            }
            double rate = feasible ? (double)met / feasible : 0;
            std::printf("%s：可行盤 %d / %d，達成 %.0f%%\n", cs.name, feasible, n, rate * 100);
            char msg[128];
            std::snprintf(msg, sizeof msg, "%s：解碼成功、路徑合法、可行盤達成率 ≥ %.0f%%", cs.name, cs.minRate * 100);
            CHECK(decoded && ok == n && rate >= cs.minRate, msg);
        }
        // 黏手路徑：路徑不得重複經過同一格
        {
            std::vector<int> v = blank();
            v[6] = 1;
            Rules ru;
            rulesFromInts(v.data(), (int)v.size(), ru);
            do { for (int i = 0; i < 30; i++) b[i] = rng() % 6; } while (countCombos(b, 5) > 0);
            Solution s = solve(b, cfg, ru);
            bool seen[MAX_CELLS] = {};
            bool dup = false;
            int p = s.start;
            seen[p] = true;
            for (int d : s.dirs) { p += DY[d] * 6 + DX[d]; if (seen[p]) dup = true; seen[p] = true; }
            CHECK(s.found && !dup, "黏手路徑：不重複經過同一格");
        }
        // 舊格式（長度 108，沒有 firstAttrMin）仍可解碼
        {
            std::vector<int> v = blank();
            v.resize(RULES_INTS_V1);
            v[1] = 7;
            Rules ru;
            CHECK(rulesFromInts(v.data(), (int)v.size(), ru) && ru.comboMin == 7 && ru.firstAttrMin == 0, "舊格式規則可解碼");
        }
        std::vector<int> bad(5, 0);
        Rules ru;
        CHECK(!rulesFromInts(bad.data(), (int)bad.size(), ru) && ru.shieldN == 0, "長度不足的規則視為一般盤");
    }

    std::printf(failures ? "\n%d 項失敗\n" : "\n全部通過\n", failures);
    return failures ? 1 : 0;
}
