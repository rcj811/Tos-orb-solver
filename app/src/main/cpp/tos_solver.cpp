// 轉珠求解核心實作（移植自 research/solver3.cpp，已移除驗證無效的選項）
#include "tos_solver.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <unordered_set>

namespace tos {

const int DX[8] = {1, 0, -1, 0, 1, -1, -1, 1};
const int DY[8] = {0, 1, 0, -1, 1, 1, -1, -1};

namespace {

const int REV[8] = {2, 3, 0, 1, 6, 7, 4, 5};
const int C = COLS;
const int MAXN = MAX_CELLS;
inline int col(uint8_t v) { return v & 7; }

struct Board { uint8_t c[MAXN]; };

// 每個執行緒各自一份求解情境，可同時在多執行緒求解
// 關卡條件模組編號（每個條件：有沒有設定、差多少、引導加分、加重倍數）
enum CondId { K_END, K_ELEC, K_COMBO, K_FIRST, K_ATTR, K_NUM, K_3ONLY, K_FEXACT, K_ANTI, K_ELEM, K_ALL, K_SHAPE, K_SHIELD, K_GROUP, NCOND };

struct Ctx {
    int R = 5, N = 30;
    Rules ru;
    Config cfg;
    float boost[NCOND];   // 各條件的引導倍數（1 = 一般；沒湊到時加重再找）
    int act[NCOND], nAct = 0;   // 有設定的條件（每次 solve 開始時算一次，評分時只跑這些）
    bool condFirst = false;     // 條件優先：做到條件後走最短的路，不追求最大 combo（首消必消）
    Ctx() { for (float& v : boost) v = 1.0f; }
};
thread_local Ctx X;

int findCombos(const Board& b, bool mark[MAXN], int* typeMask = nullptr, int* attrCombos = nullptr) {
    const int R = X.R, N = X.N;
    const Rules& RU = X.ru;
    std::memset(mark, 0, MAXN);
    auto ok = [&](int i) { return b.c[i] != EMPTY && !(b.c[i] & F_NODISSOLVE) && !RU.crack[i]; };
    for (int y = 0; y < R; y++)
        for (int x = 0; x + 2 < C; x++) {
            int i = y * C + x;
            if (ok(i) && ok(i + 1) && ok(i + 2) && col(b.c[i]) == col(b.c[i + 1]) && col(b.c[i]) == col(b.c[i + 2]))
                mark[i] = mark[i + 1] = mark[i + 2] = true;
        }
    for (int x = 0; x < C; x++)
        for (int y = 0; y + 2 < R; y++) {
            int i = y * C + x;
            if (ok(i) && ok(i + C) && ok(i + 2 * C) && col(b.c[i]) == col(b.c[i + C]) &&
                col(b.c[i]) == col(b.c[i + 2 * C]))
                mark[i] = mark[i + C] = mark[i + 2 * C] = true;
        }
    bool seen[MAXN] = {};
    int combos = 0, stk[MAXN], tm = 0, attr = 0;
    for (int i = 0; i < N; i++) {
        if (!mark[i] || seen[i]) continue;
        combos++;
        if (col(b.c[i]) != HEART) attr++;
        tm |= 1 << col(b.c[i]);
        int sp = 0;
        stk[sp++] = i;
        seen[i] = true;
        while (sp) {
            int p = stk[--sp], px = p % C, py = p / C;
            for (int d = 0; d < 4; d++) {
                int nx = px + DX[d], ny = py + DY[d];
                if (nx < 0 || nx >= C || ny < 0 || ny >= R) continue;
                int q = ny * C + nx;
                if (mark[q] && !seen[q] && col(b.c[q]) == col(b.c[i])) { seen[q] = true; stk[sp++] = q; }
            }
        }
    }
    if (typeMask) *typeMask = tm;
    if (attrCombos) *attrCombos = attr;
    return combos;
}

void removeAndDrop(Board& b, const bool mark[MAXN]) {
    const int R = X.R, N = X.N;
    for (int i = 0; i < N; i++) if (mark[i]) b.c[i] = EMPTY;
    for (int x = 0; x < C; x++) {
        int w = R - 1;
        for (int y = R - 1; y >= 0; y--)
            if (b.c[y * C + x] != EMPTY) {
                uint8_t v = b.c[y * C + x];
                b.c[y * C + x] = EMPTY;
                b.c[w * C + x] = v;
                w--;
            }
    }
}

struct Info {
    int combos = 0, first = 0, firstOrbs = 0, firstAttr = 0, firstMask = 0, mask = 0, cleared = 0;
    int allLeft = 0;
    int shapes = 0;    // 形狀盾：首批中剛好是指定形狀（5 粒同色）的組數
    int grp[6] = {}, grp3[6] = {};   // 同色組數盾：首批每種顏色消了幾組、其中剛好 3 顆的幾組
    int elecLeft = 0, weatherCleared = 0, avoidCleared = 0, shieldCover = 0, shieldHit = 0, shieldLeak = 0;
    bool shieldBroken = false;
    Board after;
};

/** 5 個格子（座標 xs, ys）是不是指定形狀：1 十字、2 T 形、3 L 形（可旋轉） */
static bool isShape(const int* xs, const int* ys, int type) {
    auto has = [&](int x, int y) { for (int k = 0; k < 5; k++) if (xs[k] == x && ys[k] == y) return true; return false; };
    const int dx[4] = {1, 0, -1, 0}, dy[4] = {0, 1, 0, -1};
    for (int k = 0; k < 5; k++) {
        const int x = xs[k], y = ys[k];
        if (type == 1) {  // 十字：中心上下左右都在
            if (has(x + 1, y) && has(x - 1, y) && has(x, y + 1) && has(x, y - 1)) return true;
        } else if (type == 2) {  // T：中心兩側（一條線的兩端），再往另一方向延伸 2 格
            for (int d = 0; d < 4; d++) {
                const int px = dx[d], py = dy[d];          // 垂直延伸方向
                const int ax = py, ay = px;                 // 橫槓方向
                if (has(x + ax, y + ay) && has(x - ax, y - ay) && has(x + px, y + py) && has(x + 2 * px, y + 2 * py)) return true;
            }
        } else if (type == 3) {  // L：轉角往兩個垂直方向各延伸 2 格
            for (int d = 0; d < 4; d++) {
                const int ex = dx[d], ey = dy[d], fx = dx[(d + 1) % 4], fy = dy[(d + 1) % 4];
                if (has(x + ex, y + ey) && has(x + 2 * ex, y + 2 * ey) && has(x + fx, y + fy) && has(x + 2 * fx, y + 2 * fy)) return true;
            }
        }
    }
    return false;
}

Info resolve(Board b) {
    const int N = X.N;
    const Rules& RU = X.ru;
    Info in;
    // 第一批一個 combo 都沒有時，必消顏色全部算沒消（第一批有消時再重新計算）
    if (RU.allMask) for (int i = 0; i < N; i++)
        if (b.c[i] != EMPTY && !(b.c[i] & F_NODISSOLVE) && (RU.allMask >> col(b.c[i]) & 1)) in.allLeft++;
    bool mark[MAXN];
    if (RU.shieldN) {
        int cnt[8] = {};
        for (int i = 0; i < N; i++) if (RU.shield[i] && b.c[i] != EMPTY) cnt[col(b.c[i])]++;
        in.shieldCover = X.cfg.shieldColor >= 0 ? cnt[X.cfg.shieldColor] : *std::max_element(cnt, cnt + 8);
    }
    for (int round = 0;; round++) {
        int tm = 0, attr = 0, c = findCombos(b, mark, &tm, &attr);
        if (!c) break;
        int orbs = 0;
        for (int i = 0; i < N; i++)
            if (mark[i]) {
                orbs++;
                if (b.c[i] & F_PREFER) in.weatherCleared++;
                if (b.c[i] & F_AVOID) in.avoidCleared++;
            }
        if (round == 0) {
            in.first = c;
            in.firstOrbs = orbs;
            // 首批每一組相連的同色珠：形狀盾（剛好 5 粒排成指定形狀）、同色組數盾（每色幾組、幾組剛好 3 顆）
            if ((RU.shapeType && RU.shapeMin) || RU.groupMask) {
                bool seen[MAXN] = {};
                int stk[MAXN];
                for (int i = 0; i < N; i++) {
                    if (!mark[i] || seen[i]) continue;
                    int xs[MAXN], ys[MAXN], n = 0, sp = 0;
                    stk[sp++] = i; seen[i] = true;
                    while (sp) {
                        const int p = stk[--sp];
                        xs[n] = p % C; ys[n] = p / C; n++;
                        for (int d = 0; d < 4; d++) {
                            const int nx = p % C + DX[d], ny = p / C + DY[d];
                            if (nx < 0 || nx >= C || ny < 0 || ny >= X.R) continue;
                            const int q = ny * C + nx;
                            if (mark[q] && !seen[q] && col(b.c[q]) == col(b.c[p])) { seen[q] = true; stk[sp++] = q; }
                        }
                    }
                    const int k = col(b.c[i]);
                    if (k < 6) { in.grp[k]++; if (n == 3) in.grp3[k]++; }
                    if (RU.shapeType && n == 5 && isShape(xs, ys, RU.shapeType)) in.shapes++;
                }
            }
            // 首消必消（全部消光）：這些顏色的珠要在第一批就全部消掉，沒被消的都算剩下（疊珠不算）
            if (RU.allMask) {
                in.allLeft = 0;
                for (int i = 0; i < N; i++)
                    if (b.c[i] != EMPTY && !mark[i] && !(b.c[i] & F_NODISSOLVE) && (RU.allMask >> col(b.c[i]) & 1)) in.allLeft++;
            }
            in.firstAttr = attr;
            in.firstMask = tm;
            // 盾格上（指定顏色）的珠在第一批被消掉的數量：引導搜尋把盾格的珠真的消掉
            if (RU.shieldN) for (int i = 0; i < N; i++)
                if (RU.shield[i] && mark[i] && (X.cfg.shieldColor < 0 || col(b.c[i]) == X.cfg.shieldColor)) in.shieldHit++;
            // 拼圖盾要「剛好」那個形狀：和盾格連在一起、一起消掉的同色珠不能超出盾的範圍
            if (RU.shieldN) {
                bool seen[MAXN] = {};
                int stk[MAXN];
                for (int i = 0; i < N; i++) {
                    if (!RU.shield[i] || !mark[i] || seen[i]) continue;
                    int sp = 0; stk[sp++] = i; seen[i] = true;
                    while (sp) {
                        int p = stk[--sp];
                        if (!RU.shield[p]) in.shieldLeak++;
                        for (int d = 0; d < 4; d++) {
                            int nx = p % C + DX[d], ny = p / C + DY[d];
                            if (nx < 0 || nx >= C || ny < 0 || ny >= X.R) continue;
                            int q = ny * C + nx;
                            if (mark[q] && !seen[q] && col(b.c[q]) == col(b.c[p])) { seen[q] = true; stk[sp++] = q; }
                        }
                    }
                }
            }
            if (RU.shieldN && in.shieldCover == RU.shieldN) {
                in.shieldBroken = in.shieldLeak == 0;
                for (int i = 0; i < N; i++) if (RU.shield[i] && !mark[i]) { in.shieldBroken = false; break; }
            }
        }
        in.combos += c;
        in.mask |= tm;
        in.cleared += orbs;
        removeAndDrop(b, mark);
    }
    for (int i = 0; i < N; i++) if (b.c[i] != EMPTY && (b.c[i] & F_MUST)) in.elecLeft++;
    in.after = b;
    return in;
}

void features(const Info& in, int len, float f[NUM_FEATURES]) {
    const int R = X.R;
    const Board& a = in.after;
    int adj = 0, gap = 0, diag = 0, cnt[8] = {};
    for (int y = 0; y < R; y++)
        for (int x = 0; x < C; x++) {
            uint8_t v = a.c[y * C + x];
            if (v == EMPTY) continue;
            int k = col(v);
            cnt[k]++;
            auto same = [&](int xx, int yy) {
                if (xx < 0 || xx >= C || yy >= R) return false;
                uint8_t u = a.c[yy * C + xx];
                return u != EMPTY && col(u) == k;
            };
            adj += same(x + 1, y) + same(x, y + 1);
            gap += same(x + 2, y) + same(x, y + 2);
            diag += same(x + 1, y + 1) + same(x - 1, y + 1);
        }
    int c3 = 0;
    for (int k = 0; k < 6; k++) c3 += cnt[k] >= 3;
    f[0] = adj; f[1] = gap; f[2] = diag; f[3] = in.cleared; f[4] = len; f[5] = in.first; f[6] = c3;
}

bool endOk(int pos) {
    const Rules& RU = X.ru;
    if (RU.anyFlame && !RU.flameEnd[pos]) return false;
    if (RU.blackHole[pos]) return false;
    if (RU.red >= 0 && pos != RU.red) return false;
    return true;
}

struct State {
    Board b;
    int8_t pos, last, start;
    uint8_t len;
    float weight;
    int combos;
    int cleared;
    int first;
    int goals;     // 做到的條件數
    int deficit;   // 所有條件總共還差多少（0 = 全部做到）
    bool ok;
    uint64_t visited;
    uint8_t used[MAXN];
    uint8_t hurt;
    uint8_t path[MAX_PATH + 4];
};

// ===================== 關卡條件模組 =====================
// 每個條件是一個模組：active（有沒有設定）、deficit（離做到還差多少，0 = 做到）、guide（引導搜尋的加分）。
// 多個條件同時設定時：先比做到幾項，再比總共還差多少，最後才比 combo。
// 沒全部做到時，只把「還沒做到的那幾個條件」的引導加重再找（cfg.timeBudgetMs 內）。

bool condActive(int k) {
    const Rules& RU = X.ru;
    switch (k) {
        case K_END: return true;                                  // 終點限制（火焰區／黑洞／紅漩渦；沒有設定時永遠做到）
        case K_ELEC: return true;                                 // 電擊／觸電（沒有時永遠做到）
        case K_COMBO: return RU.comboMin > 0;
        case K_FIRST: return RU.firstMin > 0;
        case K_ATTR: return RU.firstAttrMin > 0;
        case K_NUM: return RU.firstOrbsExact > 0;
        case K_3ONLY: return RU.first3Only;
        case K_FEXACT: return RU.firstExact >= 0;
        case K_ANTI: return RU.antiMask != 0;
        case K_ELEM: return RU.elemMask != 0;
        case K_ALL: return RU.allMask != 0;
        case K_SHAPE: return RU.shapeType && RU.shapeMin;
        case K_SHIELD: return RU.shieldN > 0;
        case K_GROUP: return RU.groupMask && RU.groupK > 0;
    }
    return false;
}

int groupDeficit(const Info& in) {
    const Rules& RU = X.ru;
    int d = 0;
    for (int c = 0; c < 6; c++)
        if (RU.groupMask >> c & 1) d += std::abs(in.grp[c] - RU.groupK) + (in.grp[c] - in.grp3[c]);
    return d;
}

int condDeficit(int k, const Info& in, int pos) {
    const Rules& RU = X.ru;
    switch (k) {
        case K_END: return !endOk(pos);
        case K_ELEC: return in.elecLeft;
        case K_COMBO: return std::max(0, RU.comboMin - in.combos);
        case K_FIRST: return std::max(0, RU.firstMin - in.first);
        case K_ATTR: return std::max(0, RU.firstAttrMin - in.firstAttr);
        case K_NUM: return std::abs(in.firstOrbs - RU.firstOrbsExact);
        case K_3ONLY: return in.firstOrbs - 3 * in.first;
        case K_FEXACT: return std::abs(in.first - RU.firstExact);
        case K_ANTI: return __builtin_popcount(in.firstMask & RU.antiMask);
        case K_ELEM: return __builtin_popcount(RU.elemMask & ~in.mask);
        case K_ALL: return in.allLeft;
        case K_SHAPE: return std::max(0, RU.shapeMin - in.shapes);
        case K_SHIELD:
            return in.shieldBroken ? 0 : std::max(1, RU.shieldN - in.shieldHit + in.shieldLeak);
        case K_GROUP: return groupDeficit(in);
    }
    return 0;
}

/** 十字形狀盾還沒湊齊：盤面上最接近十字的幾個中心（中心與上下左右同色的格數）加分 */
float crossHint(const Board& b) {
    const Rules& RU = X.ru;
    const int N = X.N, R = X.R;
    int best[4] = {0, 0, 0, 0};
    for (int i = 0; i < N; i++) {
        const int x = i % C, y = i / C;
        if (x == 0 || x == C - 1 || y == 0 || y == R - 1 || b.c[i] == EMPTY) continue;
        const int c0 = col(b.c[i]);
        int m = 1;
        for (int d = 0; d < 4; d++) {
            const int q = (y + DY[d]) * C + x + DX[d];
            if (b.c[q] != EMPTY && col(b.c[q]) == c0) m++;
        }
        for (int k = 0; k < 4; k++) if (m > best[k]) { for (int j = 3; j > k; j--) best[j] = best[j - 1]; best[k] = m; break; }
    }
    float w = 0;
    for (int k = 0; k < std::min(RU.shapeMin, 4); k++) w += best[k] * best[k] * 120.0f;
    return w;
}

/** 條件 k 的引導加分（乘上 X.boost[k]） */
float condGuide(int k, const Info& in, const State& s, int deficit) {
    const Rules& RU = X.ru;
    const Config& cfg = X.cfg;
    switch (k) {
        case K_ELEC: return -in.elecLeft * 1500.0f;
        case K_COMBO: return -deficit * 800.0f;
        case K_FIRST: return -deficit * 800.0f + in.first * 400.0f;
        case K_ATTR: return -deficit * 800.0f + in.firstAttr * 400.0f;
        case K_NUM: return -deficit * 300.0f + (deficit == 0 ? 1500.0f : 0.0f);   // NUM：剛好 X 粒，多或少都扣
        case K_3ONLY: return -deficit * 600.0f;
        case K_FEXACT: return -deficit * 1500.0f;
        case K_ANTI: return -deficit * 3000.0f;
        case K_ELEM: return __builtin_popcount(in.mask & RU.elemMask) * cfg.elemGuide;
        case K_ALL: return -in.allLeft * 400.0f;
        case K_SHAPE: {
            float w = std::min(in.shapes, RU.shapeMin) * 1500.0f;
            if (RU.shapeType == 1 && in.shapes < RU.shapeMin) w += crossHint(s.b);
            return w;
        }
        case K_SHIELD:
            return (in.shieldCover == RU.shieldN ? 2800 + RU.shieldN * 300
                                                 : in.shieldCover * in.shieldCover * cfg.shieldGuide)
                   + in.shieldHit * 250 - in.shieldLeak * 500;
        case K_GROUP: {
            // 每個指定顏色剛好 K 組 3 顆：做到的顏色加分，差的組數／多出來的珠扣分
            int okc = 0;
            for (int c = 0; c < 6; c++)
                if ((RU.groupMask >> c & 1) && in.grp[c] == RU.groupK && in.grp3[c] == RU.groupK) okc++;
            return okc * 900.0f - deficit * 700.0f;
        }
        case K_END: {
            if (!deficit) return 300.0f;
            // 還沒停在終點：離紅色漩渦（或最近的火焰區）越近越好，引導路徑往終點走
            const Rules& R2 = X.ru;
            const int x = s.pos % C, y = s.pos / C;
            auto dist = [&](int q) {
                const int dx = std::abs(q % C - x), dy = std::abs(q / C - y);
                return X.cfg.dirs == 8 ? std::max(dx, dy) : dx + dy;
            };
            int d = 0;
            if (R2.red >= 0) d = dist(R2.red);
            else if (R2.anyFlame) { d = 99; for (int q = 0; q < X.N; q++) if (R2.flameEnd[q]) d = std::min(d, dist(q)); }
            return -d * 250.0f;
        }
    }
    return 0;
}

void initConds() {
    X.nAct = 0;
    X.condFirst = X.ru.allMask != 0;
    for (int k = 0; k < NCOND; k++) { X.boost[k] = 1.0f; if (condActive(k)) X.act[X.nAct++] = k; }
}

bool goalsOk(const Info& in, int pos) {
    for (int i = 0; i < X.nAct; i++) if (condDeficit(X.act[i], in, pos)) return false;
    return true;
}

void score(State& s) {
    const Config& cfg = X.cfg;
    Info in = resolve(s.b);
    s.combos = in.combos;
    s.cleared = in.cleared;
    s.first = in.first;
    float f[NUM_FEATURES];
    features(in, s.len, f);
    float w = in.combos * 1000.0f;
    for (int i = 0; i < NUM_FEATURES; i++) w += cfg.weights[i] * f[i];
    w += in.weatherCleared * 200;
    w -= in.avoidCleared * 500;   // 盡量不要消除：兩顆約抵一個 combo
    int goals = 0, def = 0;
    for (int i = 0; i < X.nAct; i++) {
        const int k = X.act[i];
        const int d = condDeficit(k, in, s.pos);
        if (d == 0) goals++;
        def += std::min(d, 6);    // 每個條件最多算差 6，避免單一條件蓋過其他條件
        w += condGuide(k, in, s, d) * X.boost[k];
    }
    s.goals = goals;
    s.deficit = def;
    s.ok = def == 0;
    if (s.ok) w += 20000;
    s.weight = w;
}

bool better(const State& a, const State& b) {
    if (a.ok != b.ok) return a.ok;
    // 兩個都沒完全符合：先比做到幾項條件，再比總共還差多少（例如 2 組十字做不到時至少 1 組；拼圖盾＋首消 8 combo 不能為了 combo 放棄拼圖盾）
    if (!a.ok && a.goals != b.goals) return a.goals > b.goals;
    if (!a.ok && a.deficit != b.deficit) return a.deficit < b.deficit;
    // 條件優先（首消必消）：先把要消的珠排好，做到後走越少步越好，不追求最大 combo
    if (a.ok && X.condFirst && a.len != b.len) return a.len < b.len;
    if (a.combos != b.combos) return a.combos > b.combos;
    if (X.cfg.prefer3) {
        // 同 combo：每組剛好 3 顆最好（多出來的珠越少越好），其次首消 combo 越多
        const int ea = a.cleared - 3 * a.combos, eb = b.cleared - 3 * b.combos;
        if (ea != eb) return ea < eb;
        if (a.first != b.first) return a.first > b.first;
    }
    return a.len < b.len;
}

bool step(const State& s, int d, State& n) {
    const int R = X.R, N = X.N;
    const Rules& RU = X.ru;
    int x = s.pos % C, y = s.pos / C, nx = x + DX[d], ny = y + DY[d];
    if (nx < 0 || nx >= C || ny < 0 || ny >= R) return false;
    int np = ny * C + nx;
    if (s.b.c[np] == EMPTY || (s.b.c[np] & F_NOTOUCH) || RU.noTouch[np]) return false;
    if (RU.stickyPath && (s.visited >> np & 1)) return false;
    if (s.len >= MAX_PATH) return false;
    n = s;
    if (RU.firePath) {
        for (int i = 0; i < N; i++) if (n.used[i]) n.used[i]--;
        if (n.used[np]) n.hurt++;
        if (n.hurt > RU.maxHurt) return false;
        n.used[np] = RU.fireSize;
    }
    std::swap(n.b.c[s.pos], n.b.c[np]);
    if (RU.meta[np] >= 0) n.b.c[np] = (n.b.c[np] & ~7) | RU.meta[np];
    n.pos = np;
    n.last = d;
    n.path[n.len++] = d;
    n.visited |= 1ULL << np;
    return true;
}

uint64_t hashState(const State& s) {
    const int N = X.N;
    const Rules& RU = X.ru;
    uint64_t h = 1469598103934665603ULL;
    for (int i = 0; i < N; i++) { h ^= s.b.c[i]; h *= 1099511628211ULL; }
    h ^= (uint64_t)s.pos * 0x9E3779B97F4A7C15ULL;
    if (RU.stickyPath) h ^= s.visited * 0xC2B2AE3D27D4EB4FULL;
    if (RU.firePath) {
        for (int i = 0; i < N; i++) { h ^= s.used[i]; h *= 1099511628211ULL; }
        h ^= s.hurt;
    }
    return h;
}

bool initState(const Board& init, int i, State& s) {
    const Rules& RU = X.ru;
    if (init.c[i] == EMPTY || (init.c[i] & F_NOTOUCH) || RU.noTouch[i]) return false;
    if (RU.green >= 0 && i != RU.green) return false;
    s.b = init;
    s.pos = i; s.last = -1; s.len = 0; s.start = i;
    s.visited = 1ULL << i;
    std::memset(s.used, 0, sizeof s.used);
    s.hurt = 0;
    if (RU.firePath) s.used[i] = RU.fireSize;
    if (RU.meta[i] >= 0) s.b.c[i] = (s.b.c[i] & ~7) | RU.meta[i];
    return true;
}

bool beamSearch(const Board& init, State& bestOut) {
    const int N = X.N;
    const Config& cfg = X.cfg;
    const int beam = std::max(1, cfg.beam);
    const int maxPath = std::min(cfg.maxPath, MAX_PATH);
    std::vector<State> pool, next;
    pool.reserve(beam * 8);
    next.reserve(beam * 8);
    for (int i = 0; i < N; i++) {
        State s;
        if (!initState(init, i, s)) continue;
        score(s);
        pool.push_back(s);
    }
    if (pool.empty()) return false;
    State best = pool[0];
    for (auto& s : pool) if (better(s, best)) best = s;
    std::unordered_set<uint64_t> seen;
    std::vector<int> perPos(MAXN);
    auto cmp = [](const State& a, const State& b) { return a.weight > b.weight; };
    for (int st = 0; st < maxPath && !pool.empty(); st++) {
        next.clear();
        seen.clear();
        for (auto& s : pool)
            for (int d = 0; d < cfg.dirs; d++) {
                if (s.last >= 0 && d == REV[s.last]) continue;
                State n;
                if (!step(s, d, n)) continue;
                if (!seen.insert(hashState(n)).second) continue;   // 狀態去重
                score(n);
                if (better(n, best)) best = n;
                next.push_back(n);
            }
        if ((int)next.size() > beam) {
            if (cfg.divK > 0) {   // 多樣性：限制同一手指位置的狀態數
                std::sort(next.begin(), next.end(), cmp);
                int cap = std::max(1, (int)std::ceil(beam * cfg.divK / N));
                std::fill(perPos.begin(), perPos.end(), 0);
                std::vector<State> keep, rest;
                keep.reserve(beam);
                for (auto& s : next) {
                    if ((int)keep.size() < beam && perPos[s.pos] < cap) { perPos[s.pos]++; keep.push_back(s); }
                    else rest.push_back(s);
                }
                for (size_t i = 0; i < rest.size() && (int)keep.size() < beam; i++) keep.push_back(rest[i]);
                next.swap(keep);
            } else {
                std::nth_element(next.begin(), next.begin() + beam, next.end(), cmp);
                next.resize(beam);
            }
        }
        std::swap(pool, next);
        if (X.condFirst && best.ok) break;   // 條件優先：已經做到，最短的就是這一步，不用再往下找
    }
    bestOut = best;
    return true;
}

void initConds();

void setup(const uint8_t* cells, int rows, const Rules& rules, Board& b) {
    X.R = std::max(3, std::min(MAX_ROWS, rows));
    X.N = X.R * C;
    X.ru = rules;
    initConds();
    std::memset(b.c, EMPTY, sizeof b.c);
    std::memcpy(b.c, cells, X.N);
}

}  // namespace

Rules::Rules() { std::fill(meta, meta + MAX_CELLS, -1); }

bool rulesFromInts(const int* v, int n, Rules& out) {
    out = Rules();
    if (!v || n < RULES_INTS_V1 || v[0] != 1) return false;
    out.comboMin = std::max(0, v[1]);
    out.firstMin = std::max(0, v[2]);
    out.firstExact = v[3] >= 0 ? v[3] : -1;
    out.antiMask = v[4] & 0x3F;
    out.elemMask = v[5] & 0x3F;
    out.stickyPath = v[6] != 0;
    out.firePath = v[7] != 0;
    out.fireSize = std::max(1, std::min(20, v[8]));
    out.maxHurt = std::max(0, std::min(20, v[9]));
    out.green = (v[10] >= 0 && v[10] < MAX_CELLS) ? v[10] : -1;
    out.red = (v[11] >= 0 && v[11] < MAX_CELLS) ? v[11] : -1;
    for (int i = 0; i < MAX_CELLS; i++) {
        int f = v[12 + i];
        if (f & CF_SHIELD) { out.shield[i] = true; out.shieldN++; }
        out.crack[i] = (f & CF_CRACK) != 0;
        out.noTouch[i] = (f & CF_NO_TOUCH) != 0;
        if (f & CF_FLAME_END) { out.flameEnd[i] = true; out.anyFlame = true; }
        out.blackHole[i] = (f & CF_BLACK_HOLE) != 0;
        int m = v[12 + MAX_CELLS + i];
        out.meta[i] = (m >= 0 && m < 6) ? m : -1;
    }
    if (n >= RULES_INTS_V1 + 4) out.firstAttrMin = std::max(0, v[RULES_INTS_V1]);
    if (n >= RULES_INTS_V1 + 4) out.firstOrbsExact = std::max(0, v[RULES_INTS_V1 + 1]);
    if (n >= RULES_INTS_V1 + 4) out.first3Only = v[RULES_INTS_V1 + 2] != 0;
    if (n >= RULES_INTS_V1 + 4) out.allMask = v[RULES_INTS_V1 + 3] & 0x3F;
    if (n >= RULES_INTS) {
        out.shapeType = std::clamp(v[RULES_INTS_V1 + 4], 0, 3);
        out.shapeMin = std::max(0, v[RULES_INTS_V1 + 5]);
        out.groupMask = v[RULES_INTS_V1 + 6] & 0x3F;
        out.groupK = std::clamp(v[RULES_INTS_V1 + 7], 0, 10);
    }
    return true;
}

Solution solve(const uint8_t* cells, const Config& cfg, const Rules& rules) {
    auto t0 = std::chrono::steady_clock::now();
    Board b;
    setup(cells, cfg.rows, rules, b);
    X.cfg = cfg;
    Solution r;
    State best;
    if (!beamSearch(b, best)) return r;
    // ---- 之後的加強搜尋全部放在同一個時間上限內（timeBudgetMs；0 = 不限時間）----
    auto elapsed = [&] { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(); };
    const double perBeam = std::max(elapsed(), 1.0) / std::max(cfg.beam, 1);   // 每 1 單位 beam 約花多少毫秒
    auto fits = [&](int beam, int times) {
        return cfg.timeBudgetMs <= 0 || elapsed() + perBeam * beam * times * 1.15 <= cfg.timeBudgetMs;
    };
    // 1) 拼圖盾還沒破：逐一指定顏色（盤面上該色夠多的）再找
    if (rules.shieldN && !best.ok && cfg.shieldColor < 0) {
        int cnt[8] = {};
        for (int i = 0; i < cfg.rows * COLS; i++)
            if (cells[i] != EMPTY && !(cells[i] & F_NODISSOLVE)) cnt[orbColor(cells[i])]++;
        const int sb = std::max(cfg.beam, 900);
        X.cfg.beam = sb;
        for (int c = 0; c < 6 && !best.ok; c++) {
            if (cnt[c] < std::max(3, rules.shieldN)) continue;
            if (!fits(sb, 1)) break;
            X.cfg.shieldColor = c;
            State cand;
            if (beamSearch(b, cand) && better(cand, best)) best = cand;
        }
        X.cfg.shieldColor = -1;
        X.cfg.beam = cfg.beam;
    }
    // 還有條件沒做到：只把沒做到的條件引導加重再找（最多用到 timeBudget 的 1.5 倍，不讓思考太久）。
    // 必消（全部消光）3 色以上不加重（沒湊到由 App 提示使用者自己處理）
    if (!best.ok) {
        Info bi = resolve(best.b);
        bool weak[NCOND] = {};
        bool any = false;
        for (int k = 0; k < NCOND; k++) {
            if (!condActive(k) || k == K_END || condDeficit(k, bi, best.pos) == 0) continue;
            if (k == K_ALL && __builtin_popcount(rules.allMask) > 2) continue;
            if (k == K_SHIELD) continue;   // 拼圖盾已經用換色再找處理
            weak[k] = any = true;
        }
        // 形狀盾、必消（全部消光）跟上一版一樣最多到 1.5 倍時間；其他條件不多花時間
        const double cap = cfg.timeBudgetMs <= 0 ? 0 : cfg.timeBudgetMs * (weak[K_SHAPE] || weak[K_ALL] ? 1.5 : 1.0);
        auto fitsB = [&](int beam) { return cap <= 0 || elapsed() + perBeam * beam * 1.15 <= cap; };
        const float boosts[] = {3.0f, 6.0f};
        const int beams[] = {900, 1500};
        for (int r2 = 0; any && r2 < 2 && !best.ok; r2++) {
            const int bm = std::max(cfg.beam, beams[r2]);
            if (!fitsB(bm)) break;
            for (int k = 0; k < NCOND; k++) X.boost[k] = weak[k] ? boosts[r2] : 1.0f;
            X.cfg.beam = bm;
            State cand;
            if (beamSearch(b, cand)) {
                // 用一般倍數重新評分後再比較，才公平
                for (int k = 0; k < NCOND; k++) X.boost[k] = 1.0f;
                score(cand);
                if (better(cand, best)) best = cand;
            }
        }
        for (int k = 0; k < NCOND; k++) X.boost[k] = 1.0f;
        // 全部消光（1～2 色）還沒湊到：不湊到就整回合不轉，值得多花一點時間：不加重、只把搜尋加寬再找
        if (weak[K_ALL] && !best.ok) {
            const double capAll = cfg.timeBudgetMs <= 0 ? 0 : cfg.timeBudgetMs * 3.0;   // 最多用到 3 倍時間（約 4.5 秒）
            for (int bm = cfg.beam * 3; bm <= 20000 && !best.ok; bm *= 3) {
                if (capAll > 0 && elapsed() + perBeam * bm * 1.15 > capAll) break;
                X.cfg.beam = bm;
                State cand;
                if (beamSearch(b, cand)) { score(cand); if (better(cand, best)) best = cand; }
            }
        }
        X.cfg.beam = cfg.beam;
    }
    // 條件優先而且已經做到：時間內用更寬的搜尋找「更少步」的轉法（先排好要消的珠，剩下不用多轉）
    if (X.condFirst && best.ok && cfg.timeBudgetMs > 0) {
        const double capS = cfg.timeBudgetMs * 1.5;   // 縮短最多用到 1.5 倍時間
        // 先試加寬（一般引導），再試加重條件引導
        const int bms[] = {cfg.beam * 3, cfg.beam * 2};
        const float bos[] = {1.0f, 3.0f};
        for (int r = 0; r < 2; r++) {
            const int bm = bms[r];
            if (best.len <= 1 || elapsed() + perBeam * bm * 1.15 > capS) break;
            X.cfg.maxPath = best.len - 1;
            X.cfg.beam = bm;
            for (int i = 0; i < X.nAct; i++) X.boost[X.act[i]] = bos[r];
            State cand;
            const bool got = beamSearch(b, cand);
            for (int k = 0; k < NCOND; k++) X.boost[k] = 1.0f;
            if (got) score(cand);
            if (got && cand.ok && cand.len < best.len) best = cand;
        }
        X.cfg.maxPath = cfg.maxPath;
        X.cfg.beam = cfg.beam;
    }
    // 2) 時間還夠：beam ×3 重算（更多 combo／更接近條件）；條件優先而且已經做到就不用再算
    if (cfg.timeBudgetMs > 0 && !(X.condFirst && best.ok)) {
        int beam = cfg.beam;
        for (int round = 0; round < 4; round++) {
            if (beam * 3 > 20000 || !fits(beam * 3, 1)) break;
            beam *= 3;
            X.cfg.beam = beam;
            State cand;
            if (beamSearch(b, cand) && better(cand, best)) best = cand;
        }
        X.cfg.beam = cfg.beam;
    }
    Info in = resolve(best.b);
    r.found = true;
    r.combos = in.combos;
    r.firstCombos = in.first;
    r.firstOrbs = in.firstOrbs;
    r.cleared = in.cleared;
    r.goalsMet = best.ok;
    r.start = best.start;
    r.dirs.assign(best.path, best.path + best.len);
    r.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return r;
}

Outcome replay(const uint8_t* cells, int start, const std::vector<int>& dirs, const Config& cfg, const Rules& rules) {
    Board b;
    setup(cells, cfg.rows, rules, b);
    X.cfg = cfg;
    Outcome o;
    if (start < 0 || start >= X.N) return o;
    State s;
    if (!initState(b, start, s)) return o;
    for (int d : dirs) {
        if (d < 0 || d >= 8) return o;
        State n;
        if (!step(s, d, n)) return o;
        s = n;
    }
    Info in = resolve(s.b);
    o.legal = true;
    o.combos = in.combos;
    o.firstCombos = in.first;
    o.goalsMet = goalsOk(in, s.pos);
    return o;
}

int countCombos(const uint8_t* cells, int rows, const Rules& rules) {
    Board b;
    setup(cells, rows, rules, b);
    bool mark[MAXN];
    return findCombos(b, mark);
}

}  // namespace tos
