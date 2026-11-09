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
struct Ctx {
    int R = 5, N = 30;
    Rules ru;
    Config cfg;
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
    int elecLeft = 0, weatherCleared = 0, avoidCleared = 0, shieldCover = 0, shieldHit = 0, shieldLeak = 0;
    bool shieldBroken = false;
    Board after;
};

Info resolve(Board b) {
    const int N = X.N;
    const Rules& RU = X.ru;
    Info in;
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

/** 有設定的條件中，做到了幾項（全部做不到時，用來挑「最接近條件」的轉法） */
int goalsCount(const Info& in, int pos) {
    const Rules& RU = X.ru;
    int g = 0;
    g += endOk(pos);
    g += in.elecLeft == 0;
    g += in.combos >= RU.comboMin && in.first >= RU.firstMin;
    g += in.firstAttr >= RU.firstAttrMin;
    g += !RU.firstOrbsExact || in.firstOrbs == RU.firstOrbsExact;
    g += !RU.first3Only || in.firstOrbs == 3 * in.first;
    g += RU.firstExact < 0 || in.first == RU.firstExact;
    g += !(in.firstMask & RU.antiMask);
    g += (in.mask & RU.elemMask) == RU.elemMask;
    g += !RU.shieldN || in.shieldBroken;
    return g;
}

bool goalsOk(const Info& in, int pos) {
    const Rules& RU = X.ru;
    if (!endOk(pos)) return false;
    if (in.elecLeft) return false;
    if (in.combos < RU.comboMin || in.first < RU.firstMin) return false;
    if (in.firstAttr < RU.firstAttrMin) return false;
    if (RU.firstOrbsExact && in.firstOrbs != RU.firstOrbsExact) return false;
    if (RU.first3Only && in.firstOrbs != 3 * in.first) return false;   // 每組都 3 顆 ⇔ 總數 = 3 × 組數
    if (RU.firstExact >= 0 && in.first != RU.firstExact) return false;
    if (in.firstMask & RU.antiMask) return false;
    if ((in.mask & RU.elemMask) != RU.elemMask) return false;
    if (RU.shieldN && !in.shieldBroken) return false;
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
    bool ok;
    uint64_t visited;
    uint8_t used[MAXN];
    uint8_t hurt;
    uint8_t path[MAX_PATH + 4];
};

void score(State& s) {
    const Rules& RU = X.ru;
    const Config& cfg = X.cfg;
    Info in = resolve(s.b);
    s.combos = in.combos;
    s.cleared = in.cleared;
    s.first = in.first;
    s.goals = goalsCount(in, s.pos);
    float f[NUM_FEATURES];
    features(in, s.len, f);
    float w = in.combos * 1000.0f;
    for (int i = 0; i < NUM_FEATURES; i++) w += cfg.weights[i] * f[i];
    w -= in.elecLeft * 1500;
    w += in.weatherCleared * 200;
    w -= in.avoidCleared * 500;   // 盡量不要消除：兩顆約抵一個 combo
    if (RU.comboMin && in.combos < RU.comboMin) w -= (RU.comboMin - in.combos) * 800;
    if (RU.firstMin && in.first < RU.firstMin) w -= (RU.firstMin - in.first) * 800;
    if (RU.firstMin) w += in.first * 400;
    if (RU.firstAttrMin && in.firstAttr < RU.firstAttrMin) w -= (RU.firstAttrMin - in.firstAttr) * 800;
    if (RU.firstAttrMin) w += in.firstAttr * 400;
    // 首消符石數量盾：每差 1 粒扣分，引導搜尋把同色珠集中在第一批消除
    // NUM 盾：首批要剛好 X 粒，多或少都扣分（距離越遠扣越多），剛好再加分
    if (RU.firstOrbsExact) {
        int d = std::abs(in.firstOrbs - RU.firstOrbsExact);
        w -= d * 300;
        if (d == 0) w += 1500;
    }
    if (RU.firstExact >= 0) w -= std::abs(in.first - RU.firstExact) * 1500;
    if (RU.first3Only) w -= (in.firstOrbs - 3 * in.first) * 600;
    w -= __builtin_popcount(in.firstMask & RU.antiMask) * 3000;
    w += __builtin_popcount(in.mask & RU.elemMask) * cfg.elemGuide;
    if (RU.shieldN)
        w += (in.shieldCover == RU.shieldN ? 2800 + RU.shieldN * 300
                                           : in.shieldCover * in.shieldCover * cfg.shieldGuide)
           + in.shieldHit * 250 - in.shieldLeak * 500;
    if (endOk(s.pos)) w += 300;
    s.ok = goalsOk(in, s.pos);
    if (s.ok) w += 20000;
    s.weight = w;
}

bool better(const State& a, const State& b) {
    if (a.ok != b.ok) return a.ok;
    // 兩個都沒完全符合：先比做到幾項條件（例如拼圖盾＋首消 8 combo，不能為了 combo 放棄拼圖盾）
    if (!a.ok && a.goals != b.goals) return a.goals > b.goals;
    if (a.combos != b.combos) return a.combos > b.combos;
    if (X.cfg.preferOrbs) {
        // 同 combo：消越多珠傷害越高，其次首消越多，最後才比路徑短
        if (a.cleared != b.cleared) return a.cleared > b.cleared;
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
    }
    bestOut = best;
    return true;
}

void setup(const uint8_t* cells, int rows, const Rules& rules, Board& b) {
    X.R = std::max(3, std::min(MAX_ROWS, rows));
    X.N = X.R * C;
    X.ru = rules;
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
    if (n >= RULES_INTS) out.firstAttrMin = std::max(0, v[RULES_INTS_V1]);
    if (n >= RULES_INTS) out.firstOrbsExact = std::max(0, v[RULES_INTS_V1 + 1]);
    if (n >= RULES_INTS) out.first3Only = v[RULES_INTS_V1 + 2] != 0;
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
    // 2) 時間還夠：beam ×3 重算（更多 combo／更接近條件）
    if (cfg.timeBudgetMs > 0) {
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
    // 3) 整盤清光：每種顏色都是 0 顆或 ≥3 顆時才可能。用「重視消珠數」的權重再找，
    //    只有 combo 不變少、消的珠更多時才換（better() 先比條件、再比 combo、再比消珠數）
    if (cfg.tryFullClear && cfg.preferOrbs) {
        int cnt[8] = {}, total = 0;
        for (int i = 0; i < cfg.rows * COLS; i++)
            if (cells[i] != EMPTY && !(cells[i] & F_NODISSOLVE)) { cnt[orbColor(cells[i])]++; total++; }
        bool possible = true;
        for (int c = 0; c < 6; c++) if (cnt[c] == 1 || cnt[c] == 2) possible = false;
        if (possible && resolve(best.b).cleared < total) {
            float w3 = X.cfg.weights[3];
            const int fb = std::max(cfg.beam, 900);
            for (float mul : {3.0f, 6.0f}) {
                if (!fits(fb, 1)) break;
                X.cfg.weights[3] = w3 * mul;
                X.cfg.beam = fb;
                State cand;
                if (beamSearch(b, cand) && better(cand, best)) best = cand;
                if (resolve(best.b).cleared >= total) break;
            }
            X.cfg.weights[3] = w3;
            X.cfg.beam = cfg.beam;
        }
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
