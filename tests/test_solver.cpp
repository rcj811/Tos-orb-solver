// 求解核心測試：規則正確性、路徑合法性、結果回歸
// 編譯：g++ -std=c++17 -O2 -Iapp/src/main/cpp app/src/main/cpp/tos_solver.cpp tests/test_solver.cpp -o test_solver
#include "tos_solver.h"

#include <cstdio>
#include <cstring>
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

    // 可組合的符石規則（自訂符石類別）
    {
        Config c5;
        // 必須消除但可以碰（例：化血）：解法要把它消掉
        std::mt19937 r3(91);
        int okMust = 0, okAvoid = 0, okCannot = 0, n = 40, legal = 0;
        for (int k = 0; k < n; k++) {
            do { for (int i = 0; i < 30; i++) b[i] = r3() % 6; } while (countCombos(b, 5) > 0);
            uint8_t bm[MAX_CELLS]; std::memcpy(bm, b, 30); bm[12] |= F_MUST;
            Solution s1 = solve(bm, c5); Outcome o1 = replay(bm, s1.start, s1.dirs, c5);
            if (o1.legal) legal++;
            if (s1.goalsMet) okMust++;
            // 盡量不要消除：標 3 顆，最佳解消掉的數量應該很少
            uint8_t ba[MAX_CELLS]; std::memcpy(ba, b, 30); ba[3] |= F_AVOID; ba[16] |= F_AVOID; ba[27] |= F_AVOID;
            Solution s2 = solve(ba, c5);
            uint8_t after[MAX_CELLS]; std::memcpy(after, ba, 30);
            int p = s2.start;
            for (int d : s2.dirs) { int q = p + DY[d] * 6 + DX[d]; std::swap(after[p], after[q]); p = q; }
            // 只看第一輪有沒有消到 F_AVOID 的珠（近似）
            Rules none; (void)none;
            int avoidLeft = 0; for (int i = 0; i < 30; i++) if (after[i] & F_AVOID) avoidLeft++;
            (void)avoidLeft;
            Outcome o2 = replay(ba, s2.start, s2.dirs, c5);
            if (o2.legal) okAvoid++;
            // 不能消除（可以碰）：標記的格子不會被算成 combo
            uint8_t bc[MAX_CELLS]; std::memcpy(bc, b, 30);
            for (int i = 0; i < 30; i += 5) bc[i] |= F_NODISSOLVE;
            Solution s3 = solve(bc, c5); Outcome o3 = replay(bc, s3.start, s3.dirs, c5);
            if (o3.legal && o3.combos == s3.combos) okCannot++;
        }
        std::printf("自訂規則：必須消除達成 %d / %d，路徑合法 %d / %d\n", okMust, n, legal, n);
        CHECK(legal == n && okMust >= n * 0.8, "必須消除（可碰）：路徑合法、大多能消掉");
        CHECK(okAvoid == n, "盡量不要消除：路徑合法");
        CHECK(okCannot == n, "不能消除（可碰）：路徑合法、結算一致");
        // 不能消除的格子確實不參與 combo
        parse("000123" "123450" "234501" "345012" "450123", b, 30);
        b[1] |= F_NODISSOLVE;
        CHECK(countCombos(b, 5) == 0, "不能消除的珠不參與三連");
        // 可以碰：F_NODISSOLVE 單獨使用時，路徑可以經過
        parse("012345" "123450" "234501" "345012" "450123", b, 30);
        b[1] |= F_NODISSOLVE;
        Outcome ot = replay(b, 0, {0}, c5);
        CHECK(ot.legal, "只有「不能消除」時仍可碰觸");
        b[1] = (b[1] & 7) | F_NOTOUCH;
        CHECK(!replay(b, 0, {0}, c5).legal, "「不能碰」時路徑不能經過");
    }

    {
        // 轉珠時間用滿：同 combo 時選消珠多的解、路徑不超過上限、加寬搜尋不變差
        std::mt19937 rg(77); int n = 60; double cOld = 0, cNew = 0, oOld = 0, oNew = 0; int over = 0, bad = 0;
        for (int k = 0; k < n; k++) {
            uint8_t bb[MAX_CELLS]; do { for (int i = 0; i < 30; i++) bb[i] = rg() % 6; } while (countCombos(bb, 5) > 0);
            Config a; a.maxPath = 35; a.preferOrbs = false;
            Config z; z.maxPath = 49; z.timeBudgetMs = 800;
            Solution sa = solve(bb, a), sz = solve(bb, z);
            Outcome oz = replay(bb, sz.start, sz.dirs, z);
            if (!oz.legal || oz.combos != sz.combos) bad++;
            if ((int)sz.dirs.size() > 49) over++;
            cOld += sa.combos; cNew += sz.combos; oOld += sa.cleared; oNew += sz.cleared;
        }
        std::printf("轉珠時間用滿：combo %.2f → %.2f，消珠 %.1f → %.1f\n", cOld / n, cNew / n, oOld / n, oNew / n);
        CHECK(bad == 0, "長路徑重播一致");
        CHECK(over == 0, "路徑不超過時間上限");
        CHECK(cNew >= cOld && oNew > oOld, "combo 不變差、消珠變多");
    }

    {
        // GO 起點符石：必須從指定格出發（App 端把 GO 格放進規則 [10]）
        uint8_t bg[MAX_CELLS]; parse("454032" "445403" "041514" "435025" "225302", bg, 30);
        int v[RULES_INTS] = {0}; v[0] = 1; v[3] = -1; v[8] = 6; v[10] = 19; v[11] = -1;
        for (int i = 0; i < MAX_CELLS; i++) v[12 + MAX_CELLS + i] = -1;
        Rules rg; CHECK(rulesFromInts(v, RULES_INTS, rg), "GO 規則可解碼");
        Config cg; cg.maxPath = 60;
        Solution sg = solve(bg, cg, rg);
        CHECK(sg.found && sg.start == 19, "GO：路徑從 GO 那格出發");
        CHECK(!replay(bg, 0, {0}, cg, rg).legal, "GO：從別格出發不合法");
    }

    {
        // 首消符石數量盾（NUM 15）：首批消除剛好 15 粒
        uint8_t bn[MAX_CELLS]; parse("131541" "003250" "434225" "312303" "524140", bn, 30);
        int v[RULES_INTS] = {0}; v[0] = 1; v[3] = -1; v[8] = 6; v[10] = -1; v[11] = -1;
        for (int i = 0; i < MAX_CELLS; i++) v[12 + MAX_CELLS + i] = -1;
        v[RULES_INTS_V1 + 1] = 15;
        Rules rn; rulesFromInts(v, RULES_INTS, rn);
        CHECK(rn.firstOrbsExact == 15, "NUM 盾可解碼");
        Config cn; cn.maxPath = 60;
        Solution plain = solve(bn, cn), sn = solve(bn, cn, rn);
        std::printf("NUM15：一般解首消 %d 粒；破盾解首消 %d 粒、%d combo、達成 %d\n", plain.firstOrbs, sn.firstOrbs, sn.combos, sn.goalsMet);
        CHECK(sn.goalsMet && sn.firstOrbs == 15, "NUM 15：首批消除剛好 15 粒");
        { Outcome on = replay(bn, sn.start, sn.dirs, cn, rn); CHECK(on.legal && on.goalsMet, "NUM 15：重播確認剛好 15 粒"); }
        int ok = 0, n = 40; std::mt19937 rq(5);
        for (int k = 0; k < n; k++) {
            uint8_t bb[MAX_CELLS]; for (int i = 0; i < 30; i++) bb[i] = rq() % 6;
            Solution s = solve(bb, cn, rn); if (s.goalsMet && s.firstOrbs == 15) ok++;
        }
        std::printf("NUM15 隨機盤達成 %d / %d\n", ok, n);
        CHECK(ok >= n * 0.9, "NUM 15：隨機盤大多能破");
    }

    {
        // 首消剛好 X Combo、每組只能 3 顆（不能有 4 顆連珠）
        int v[RULES_INTS] = {0}; v[0] = 1; v[3] = 5; v[8] = 6; v[10] = -1; v[11] = -1;
        for (int i = 0; i < MAX_CELLS; i++) v[12 + MAX_CELLS + i] = -1;
        v[RULES_INTS_V1 + 2] = 1;
        Rules r3; rulesFromInts(v, RULES_INTS, r3);
        CHECK(r3.first3Only && r3.firstExact == 5, "首消每組 3 顆可解碼");
        std::mt19937 rq(11); int ok = 0, n = 30;
        for (int k = 0; k < n; k++) {
            uint8_t bb[MAX_CELLS]; for (int i = 0; i < 30; i++) bb[i] = rq() % 6;
            Config c3; c3.maxPath = 60; Solution s = solve(bb, c3, r3);
            if (s.goalsMet && s.firstCombos == 5 && s.firstOrbs == 15) ok++;
        }
        std::printf("首消剛好 5 Combo、每組 3 顆：%d / %d\n", ok, n);
        CHECK(ok >= n * 0.9, "首消剛好 5 Combo、每組 3 顆");
        uint8_t four[MAX_CELLS]; parse("000012" "123450" "234501" "345012" "450123", four, 30);
        Config c4; Rules one; int w[RULES_INTS] = {0}; w[0] = 1; w[3] = 1; w[8] = 6; w[10] = -1; w[11] = -1;
        for (int i = 0; i < MAX_CELLS; i++) w[12 + MAX_CELLS + i] = -1;
        w[RULES_INTS_V1 + 2] = 1; rulesFromInts(w, RULES_INTS, one);
        CHECK(!replay(four, 5, {1}, c4, one).goalsMet, "4 顆連珠不算達成");
    }

    {
        // 拼圖盾要「剛好」那個形狀：盾旁邊同色珠一起消掉（形狀變大）不算破盾
        int v[RULES_INTS] = {0}; v[0] = 1; v[3] = -1; v[8] = 6; v[10] = -1; v[11] = -1;
        for (int i = 0; i < MAX_CELLS; i++) v[12 + MAX_CELLS + i] = -1;
        for (int i : {13, 14, 15}) v[12 + i] |= CF_SHIELD;
        Rules rs; rulesFromInts(v, RULES_INTS, rs);
        uint8_t b4[MAX_CELLS]; parse("123451" "234512" "300001" "451234" "512345", b4, 30);
        uint8_t b3[MAX_CELLS]; parse("123451" "234512" "300021" "451234" "512345", b3, 30);
        Config cs;
        CHECK(!replay(b4, 29, {2}, cs, rs).goalsMet, "拼圖盾：多一顆同色相連不算破盾");
        CHECK(replay(b3, 29, {2}, cs, rs).goalsMet, "拼圖盾：剛好形狀才算破盾");
        // 拼圖盾＋首消剛好 6 combo：兩個都要做到
        v[3] = 6; Rules r6; rulesFromInts(v, RULES_INTS, r6);
        std::mt19937 rq(9); int both = 0, n = 20;
        for (int k = 0; k < n; k++) {
            uint8_t bb[MAX_CELLS]; for (int i = 0; i < 30; i++) bb[i] = rq() % 6;
            Config c6; c6.maxPath = 60; Solution s = solve(bb, c6, r6);
            if (s.goalsMet && s.firstCombos == 6) both++;
        }
        std::printf("拼圖盾＋首消剛好 6 combo：%d / %d\n", both, n);
        CHECK(both >= n * 0.9, "拼圖盾＋首消剛好 6 combo 兩個都做到");
    }

    std::printf(failures ? "\n%d 項失敗\n" : "\n全部通過\n", failures);
    return failures ? 1 : 0;
}
