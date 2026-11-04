// 神魔之塔離線轉珠求解器 v3（研究版：新特徵、beam 多樣性、集成、路徑縮短、平行評估、CMA 式調參）
// - AutoDora 式 beam search（播種 → 8/4 向展開 → 結算評分 → 淘汰）
// - 可調權重評分（v1 自動調參結果）
// - 版面 6x5 ~ 6x8
// - 依官方「敵人技能與常用名詞」實作會影響轉珠路徑的機制
// 不含天降補珠（消除後空格留空）
#include <bits/stdc++.h>
using namespace std;

const int C = 6, MAXR = 8, MAXN = MAXR * C;
int R = 5, N = 30;
float ELEMW = 600, SHW = 150;
const int DX[8] = {1, 0, -1, 0, 1, -1, -1, 1};
const int DY[8] = {0, 1, 0, -1, 1, 1, -1, -1};
const int REV[8] = {2, 3, 0, 1, 6, 7, 4, 5};

// 珠子編碼：低 3 bits 珠種（火0 水1 木2 光3 暗4 心5），高位為隨珠移動的狀態
const uint8_t EMPTY = 0xFF;
const uint8_t F_ELEC = 0x10;     // 觸電：不可觸碰；回合結束仍有殘留 → 攻擊為 0
const uint8_t F_FROZEN = 0x20;   // 冰封：不可觸碰、不可消除
const uint8_t F_WEATHER = 0x40;  // 風化：不可觸碰；被消除有加分（AutoDora）
const uint8_t F_NOTOUCH = F_ELEC | F_FROZEN | F_WEATHER;
inline int col(uint8_t v) { return v & 7; }

struct Board { uint8_t c[MAXN]; };

// ---------------- 關卡規則 ----------------
struct Rules {
    // 位置類（不隨珠移動）
    bool crack[MAXN] = {};      // 裂痕：此位置的珠不能被消除
    bool noTouch[MAXN] = {};    // 燃燒 / 地獄火 / 黏手之地 / 雷射陷阱：保守視為不可進入
    bool flameEnd[MAXN] = {};   // 火焰區：最後一步須停在其中之一
    bool blackHole[MAXN] = {};  // 黑洞：最後一步不可停在此
    int meta[MAXN];             // 變形魔球：拿著的珠經過即變成該屬性（-1 = 無）
    int green = -1, red = -1;   // 綠紅漩渦：須從綠出發、停在紅
    bool anyFlame = false;
    // 路徑類
    bool stickyPath = false;    // 黏手路徑：碰過的格不能再碰
    bool firePath = false;      // 火焰路徑：短時間內重踩會受傷（AutoDora）
    int fireSize = 6, maxHurt = 0;
    // 盾 / 條件
    int comboMin = 0;           // Combo 盾（≥X）
    int firstMin = 0;           // 首消 Combo 盾（≥X）
    int firstExact = -1;        // 固定 X Combo 盾（首消剛好 X）
    int antiMask = 0;           // 反屬性盾：首消不可消除這些珠種
    int elemMask = 0;           // 多屬性盾：本回合須消除這些珠種
    bool shield[MAXN] = {};     // 拼圖盾格
    int shieldN = 0;
    Rules() { fill(meta, meta + MAXN, -1); }
};
thread_local Rules RU;   // 每個執行緒各自一份，方便多盤平行評估

// ---------------- 結算 ----------------
static int findCombos(const Board& b, bool mark[MAXN], int* typeMask = nullptr) {
    memset(mark, 0, MAXN);
    auto ok = [&](int i) { return b.c[i] != EMPTY && !(b.c[i] & F_FROZEN) && !RU.crack[i]; };
    for (int y = 0; y < R; y++)
        for (int x = 0; x + 2 < C; x++) {
            int i = y * C + x;
            if (ok(i) && ok(i + 1) && ok(i + 2) && col(b.c[i]) == col(b.c[i + 1]) && col(b.c[i]) == col(b.c[i + 2]))
                mark[i] = mark[i + 1] = mark[i + 2] = true;
        }
    for (int x = 0; x < C; x++)
        for (int y = 0; y + 2 < R; y++) {
            int i = y * C + x;
            if (ok(i) && ok(i + C) && ok(i + 2 * C) && col(b.c[i]) == col(b.c[i + C]) && col(b.c[i]) == col(b.c[i + 2 * C]))
                mark[i] = mark[i + C] = mark[i + 2 * C] = true;
        }
    bool seen[MAXN] = {};
    int combos = 0, stk[MAXN], tm = 0;
    for (int i = 0; i < N; i++) {
        if (!mark[i] || seen[i]) continue;
        combos++; tm |= 1 << col(b.c[i]);
        int sp = 0; stk[sp++] = i; seen[i] = true;
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
    return combos;
}

static void removeAndDrop(Board& b, const bool mark[MAXN]) {
    for (int i = 0; i < N; i++) if (mark[i]) b.c[i] = EMPTY;
    for (int x = 0; x < C; x++) {
        int w = R - 1;
        for (int y = R - 1; y >= 0; y--)
            if (b.c[y * C + x] != EMPTY) { uint8_t v = b.c[y * C + x]; b.c[y * C + x] = EMPTY; b.c[w * C + x] = v; w--; }
    }
}

struct Info {
    int combos = 0, first = 0, firstOrbs = 0, firstMask = 0, mask = 0, cleared = 0;
    int elecLeft = 0, weatherCleared = 0, shieldCover = 0; bool shieldBroken = false;
    Board after;
};

static Info resolve(Board b) {
    Info in; bool mark[MAXN];
    if (RU.shieldN) {   // 拼圖盾：盾格內最多同色數
        int cnt[8] = {};
        for (int i = 0; i < N; i++) if (RU.shield[i] && b.c[i] != EMPTY) cnt[col(b.c[i])]++;
        in.shieldCover = *max_element(cnt, cnt + 8);
    }
    for (int round = 0;; round++) {
        int tm = 0, c = findCombos(b, mark, &tm);
        if (!c) break;
        int orbs = 0;
        for (int i = 0; i < N; i++) if (mark[i]) { orbs++; if (b.c[i] & F_WEATHER) in.weatherCleared++; }
        if (round == 0) {
            in.first = c; in.firstOrbs = orbs; in.firstMask = tm;
            if (RU.shieldN && in.shieldCover == RU.shieldN) {
                in.shieldBroken = true;
                for (int i = 0; i < N; i++) if (RU.shield[i] && !mark[i]) { in.shieldBroken = false; break; }
            }
        }
        in.combos += c; in.mask |= tm; in.cleared += orbs;
        removeAndDrop(b, mark);
    }
    for (int i = 0; i < N; i++) if (b.c[i] != EMPTY && (b.c[i] & F_ELEC)) in.elecLeft++;
    in.after = b;
    return in;
}

// ---------------- 評分 ----------------
// 特徵 0~6 同 v2；7~9 為研究後新增：
// 7 remUB：殘盤各色 floor(數量/3) 總和（還能再湊幾組的上限）
// 8 dead：殘盤中所屬顏色已不足 3 顆的「死珠」數
// 9 near3：橫/直任意連續 3 格中恰有 2 顆同色的窗口數（差一步成三連）
const int NF = 10;
const char* FNAME[NF] = {"adj", "gap", "diag", "cleared", "len", "first", "colors3", "remUB", "dead", "near3"};
const float W_V2[NF] = {24.55f, 12.73f, 12.02f, 23.49f, 2.07f, 21.83f, 61.91f, 0, 0, 0};   // v1 調參結果
const float W_V1[NF] = {10, 10, 0, 0, -1, 0, 0, 0, 0, 0};                                   // 最初手寫
struct Cfg {
    int beam = 450, maxPath = 30, dirs = 8, dedup = 1;
    float w[NF];
    float divK = 0;      // >0：beam 多樣性，同一個手指位置最多保留 beam/N*divK 個狀態
    int ens = 0;         // 1：另用 w2 跑第二支 beam（各用一半寬度），取較好者
    float w2[NF];
    int shorten = 0;     // 1：找到最佳 combo 後，用強路徑懲罰再搜一次，只接受同等結果但更短的路徑
    float shortLen = -150;
    Cfg() { memcpy(w, W_V2, sizeof w); memcpy(w2, W_V1, sizeof w2); }
};

static void features(const Info& in, int len, float f[NF]) {
    const Board& a = in.after;
    int adj = 0, gap = 0, diag = 0, near3 = 0, cnt[8] = {};
    for (int y = 0; y < R; y++) for (int x = 0; x < C; x++) {
        uint8_t v = a.c[y * C + x]; if (v == EMPTY) continue;
        int k = col(v); cnt[k]++;
        auto same = [&](int xx, int yy) { if (xx < 0 || xx >= C || yy >= R) return false; uint8_t u = a.c[yy * C + xx]; return u != EMPTY && col(u) == k; };
        adj += same(x + 1, y) + same(x, y + 1);
        gap += same(x + 2, y) + same(x, y + 2);
        diag += same(x + 1, y + 1) + same(x - 1, y + 1);
    }
    auto win = [&](int i0, int st) {   // 3 格窗口內是否恰 2 顆同色
        uint8_t p = a.c[i0], q = a.c[i0 + st], r = a.c[i0 + 2 * st];
        int e = (p == EMPTY) + (q == EMPTY) + (r == EMPTY); if (e > 1) return 0;
        int cp = p == EMPTY ? -1 : col(p), cq = q == EMPTY ? -2 : col(q), cr = r == EMPTY ? -3 : col(r);
        int s = (cp == cq) + (cq == cr) + (cp == cr);
        return s == 1 ? 1 : 0;
    };
    for (int y = 0; y < R; y++) for (int x = 0; x + 2 < C; x++) near3 += win(y * C + x, 1);
    for (int x = 0; x < C; x++) for (int y = 0; y + 2 < R; y++) near3 += win(y * C + x, C);
    int c3 = 0, ub = 0, dead = 0;
    for (int k = 0; k < 6; k++) { c3 += cnt[k] >= 3; ub += cnt[k] / 3; if (cnt[k] < 3) dead += cnt[k]; }
    f[0] = adj; f[1] = gap; f[2] = diag; f[3] = in.cleared; f[4] = len; f[5] = in.first; f[6] = c3;
    f[7] = ub; f[8] = dead; f[9] = near3;
}

static thread_local float WCUR[NF];   // 目前這支 beam 使用的權重

bool endOk(int pos) {
    if (RU.anyFlame && !RU.flameEnd[pos]) return false;
    if (RU.blackHole[pos]) return false;
    if (RU.red >= 0 && pos != RU.red) return false;
    return true;
}

// 所有關卡條件是否達成（決定「過關可打傷害」）
bool goalsOk(const Info& in, int pos) {
    if (!endOk(pos)) return false;
    if (in.elecLeft) return false;
    if (in.combos < RU.comboMin || in.first < RU.firstMin) return false;
    if (RU.firstExact >= 0 && in.first != RU.firstExact) return false;
    if (in.firstMask & RU.antiMask) return false;
    if ((in.mask & RU.elemMask) != RU.elemMask) return false;
    if (RU.shieldN && !in.shieldBroken) return false;
    return true;
}

bool hasGoals() {
    return RU.anyFlame || RU.red >= 0 || RU.comboMin || RU.firstMin || RU.firstExact >= 0 || RU.antiMask ||
           RU.elemMask || RU.shieldN || any_of(RU.blackHole, RU.blackHole + MAXN, [](bool b) { return b; });
}

struct State {
    Board b; int8_t pos, last, start; uint8_t len; float weight; int combos; bool ok;
    uint64_t visited; uint8_t used[MAXN]; uint8_t hurt; uint8_t path[64];
};

static void scoreW(State& s, const Cfg& cfg) {
    Info in = resolve(s.b);
    s.combos = in.combos;
    float f[NF]; features(in, s.len, f);
    float w = in.combos * 1000.0f;
    for (int i = 0; i < NF; i++) w += WCUR[i] * f[i];
    // 條件引導：殘留觸電、首消不符、反屬性等給重罰；接近目標給加分
    w -= in.elecLeft * 1500;
    w += in.weatherCleared * 200;
    if (RU.comboMin && in.combos < RU.comboMin) w -= (RU.comboMin - in.combos) * 800;
    if (RU.firstMin && in.first < RU.firstMin) w -= (RU.firstMin - in.first) * 800;
    if (RU.firstMin) w += in.first * 400;
    if (RU.firstExact >= 0) w -= abs(in.first - RU.firstExact) * 1500;
    w -= __builtin_popcount(in.firstMask & RU.antiMask) * 3000;
    w += __builtin_popcount(in.mask & RU.elemMask) * ELEMW;
    // 拼圖盾引導：盾越大，每多覆蓋一顆的加成越高（9 格盾不加強會被 combo 分數蓋過）
    if (RU.shieldN) w += (in.shieldCover == RU.shieldN ? 2800 + RU.shieldN * 300 : in.shieldCover * in.shieldCover * SHW);
    if (endOk(s.pos)) w += 300;
    s.ok = goalsOk(in, s.pos);
    if (s.ok) w += 20000;
    s.weight = w;
}

static bool better(const State& a, const State& b) {
    if (a.ok != b.ok) return a.ok;
    if (a.combos != b.combos) return a.combos > b.combos;
    return a.len < b.len;
}

// 能否從 s 往 d 走一步；可以的話寫入 n
static bool step(const State& s, int d, State& n) {
    int x = s.pos % C, y = s.pos / C, nx = x + DX[d], ny = y + DY[d];
    if (nx < 0 || nx >= C || ny < 0 || ny >= R) return false;
    int np = ny * C + nx;
    if (s.b.c[np] == EMPTY || (s.b.c[np] & F_NOTOUCH) || RU.noTouch[np]) return false;
    if (RU.stickyPath && (s.visited >> np & 1)) return false;
    n = s;
    if (RU.firePath) {
        for (int i = 0; i < N; i++) if (n.used[i]) n.used[i]--;
        if (n.used[np]) n.hurt++;
        if (n.hurt > RU.maxHurt) return false;
        n.used[np] = RU.fireSize;
    }
    swap(n.b.c[s.pos], n.b.c[np]);
    if (RU.meta[np] >= 0) n.b.c[np] = (n.b.c[np] & ~7) | RU.meta[np];   // 經過變形魔球
    n.pos = np; n.last = d; n.path[n.len++] = d; n.visited |= 1ULL << np;
    return true;
}

static uint64_t hashState(const State& s) {
    uint64_t h = 1469598103934665603ULL;
    for (int i = 0; i < N; i++) { h ^= s.b.c[i]; h *= 1099511628211ULL; }
    h ^= (uint64_t)s.pos * 0x9E3779B97F4A7C15ULL;
    if (RU.stickyPath) h ^= s.visited * 0xC2B2AE3D27D4EB4FULL;
    if (RU.firePath) { for (int i = 0; i < N; i++) { h ^= s.used[i]; h *= 1099511628211ULL; } h ^= s.hurt; }
    return h;
}

struct Result { int combos = 0, first = 0, len = 0, start = -1; bool ok = false; vector<int> path; double ms = 0; };

// 單支 beam search，回傳最佳狀態（w 為評分權重、beam 為寬度）
static bool beamSearch(const Board& init, const Cfg& cfg, const float* w, int beam, State& bestOut) {
    memcpy(WCUR, w, sizeof WCUR);
    vector<State> pool, next;
    pool.reserve(beam * 8); next.reserve(beam * 8);
    for (int i = 0; i < N; i++) {
        if (init.c[i] == EMPTY || (init.c[i] & F_NOTOUCH) || RU.noTouch[i]) continue;
        if (RU.green >= 0 && i != RU.green) continue;
        State s; s.b = init; s.pos = i; s.last = -1; s.len = 0; s.start = i;
        s.visited = 1ULL << i; memset(s.used, 0, sizeof s.used); s.hurt = 0;
        if (RU.firePath) s.used[i] = RU.fireSize;
        if (RU.meta[i] >= 0) s.b.c[i] = (s.b.c[i] & ~7) | RU.meta[i];
        scoreW(s, cfg); pool.push_back(s);
    }
    if (pool.empty()) return false;
    State best = pool[0];
    for (auto& s : pool) if (better(s, best)) best = s;
    unordered_set<uint64_t> seen;
    vector<int> perPos(MAXN);
    for (int st = 0; st < cfg.maxPath && !pool.empty(); st++) {
        next.clear(); seen.clear();
        for (auto& s : pool)
            for (int d = 0; d < cfg.dirs; d++) {
                if (s.last >= 0 && d == REV[s.last]) continue;
                State n;
                if (!step(s, d, n)) continue;
                if (cfg.dedup && !seen.insert(hashState(n)).second) continue;
                scoreW(n, cfg);
                if (better(n, best)) best = n;
                next.push_back(n);
            }
        if ((int)next.size() > beam) {
            auto cmp = [](const State& a, const State& b) { return a.weight > b.weight; };
            if (cfg.divK > 0) {   // 多樣性：限制同一手指位置的狀態數，避免 beam 擠在同一區
                sort(next.begin(), next.end(), cmp);
                int cap = max(1, (int)ceil(beam * cfg.divK / N));
                fill(perPos.begin(), perPos.end(), 0);
                vector<State> keep, rest; keep.reserve(beam);
                for (auto& s : next) {
                    if ((int)keep.size() < beam && perPos[s.pos] < cap) { perPos[s.pos]++; keep.push_back(s); }
                    else rest.push_back(s);
                }
                for (size_t i = 0; i < rest.size() && (int)keep.size() < beam; i++) keep.push_back(rest[i]);
                next.swap(keep);
            } else {
                nth_element(next.begin(), next.begin() + beam, next.end(), cmp);
                next.resize(beam);
            }
        }
        swap(pool, next);
    }
    bestOut = best;
    return true;
}

Result solve(const Board& init, const Cfg& cfg) {
    auto t0 = chrono::steady_clock::now();
    Result r;
    State best;
    if (cfg.ens) {          // 集成：兩組權重各跑半寬，取較好者（總運算量不變）
        State a, b;
        bool oa = beamSearch(init, cfg, cfg.w, cfg.beam / 2, a), ob = beamSearch(init, cfg, cfg.w2, cfg.beam / 2, b);
        if (!oa && !ob) return r;
        best = !ob ? a : !oa ? b : (better(b, a) ? b : a);
    } else if (!beamSearch(init, cfg, cfg.w, cfg.beam, best)) return r;
    if (cfg.shorten) {      // 路徑縮短：強路徑懲罰再搜一次，結果不變差才採用
        float w2[NF]; memcpy(w2, cfg.w, sizeof w2); w2[4] = cfg.shortLen;
        State s;
        bool okk = beamSearch(init, cfg, w2, cfg.beam, s);
        if (getenv("DIAG")) fprintf(stderr, "p1 c=%d l=%d | p2 c=%d l=%d\n", best.combos, best.len, s.combos, s.len);
        if (okk && s.ok == best.ok && s.combos >= best.combos && s.len < best.len) best = s;
    }
    Info in = resolve(best.b);
    r.combos = in.combos; r.first = in.first; r.len = best.len; r.start = best.start; r.ok = best.ok;
    for (int i = 0; i < best.len; i++) r.path.push_back(best.path[i]);
    r.ms = chrono::duration<double, milli>(chrono::steady_clock::now() - t0).count();
    return r;
}

// 獨立重播：重新走一遍路徑並檢查每一步是否合法、結果是否一致
bool replayCheck(const Board& init, const Result& r) {
    if (r.start < 0) return true;
    State s; s.b = init; s.pos = r.start; s.last = -1; s.len = 0; s.start = r.start;
    s.visited = 1ULL << r.start; memset(s.used, 0, sizeof s.used); s.hurt = 0;
    if (RU.firePath) s.used[r.start] = RU.fireSize;
    if ((init.c[r.start] & F_NOTOUCH) || RU.noTouch[r.start]) return false;
    if (RU.green >= 0 && r.start != RU.green) return false;
    if (RU.meta[r.start] >= 0) s.b.c[r.start] = (s.b.c[r.start] & ~7) | RU.meta[r.start];
    for (int d : r.path) { State n; if (!step(s, d, n)) return false; s = n; }
    Info in = resolve(s.b);
    return in.combos == r.combos && goalsOk(in, s.pos) == r.ok;
}

// ---------------- 盤面與情境產生 ----------------
Board randomBoard(mt19937& rng) {
    Board b; bool mark[MAXN];
    memset(b.c, EMPTY, sizeof b.c);
    do { for (int i = 0; i < N; i++) b.c[i] = rng() % 6; } while (findCombos(b, mark) > 0);
    return b;
}

vector<int> pickCells(mt19937& rng, int k, const vector<int>& avoid = {}) {
    vector<int> v; for (int i = 0; i < N; i++) if (find(avoid.begin(), avoid.end(), i) == avoid.end()) v.push_back(i);
    shuffle(v.begin(), v.end(), rng); v.resize(min<int>(k, v.size())); return v;
}

void setShieldShape(const string& shape) {
    vector<pair<int, int>> cells;
    if (shape == "sq4")    cells = {{2, 1}, {3, 1}, {2, 2}, {3, 2}};
    if (shape == "line5")  cells = {{1, 2}, {2, 2}, {3, 2}, {4, 2}, {5, 2}};
    if (shape == "L5")     cells = {{1, 0}, {1, 1}, {1, 2}, {2, 2}, {3, 2}};
    if (shape == "cross5") cells = {{2, 1}, {1, 2}, {2, 2}, {3, 2}, {2, 3}};
    if (shape == "sq9")    cells = {{1, 1}, {2, 1}, {3, 1}, {1, 2}, {2, 2}, {3, 2}, {1, 3}, {2, 3}, {3, 3}};
    for (auto [x, y] : cells) { RU.shield[y * C + x] = true; RU.shieldN++; }
}

// 依情境名稱，為一個盤面設定規則（隨機位置每盤不同）
struct Scenario { string name; map<string, int> p; };

Board setupBoard(const Scenario& sc, mt19937& rng) {
    RU = Rules();
    Board b = randomBoard(rng);
    auto get = [&](const char* k, int def = 0) { auto it = sc.p.find(k); return it == sc.p.end() ? def : it->second; };
    vector<int> used;
    auto place = [&](int k) { auto v = pickCells(rng, k, used); used.insert(used.end(), v.begin(), v.end()); return v; };
    for (int i : place(get("elec"))) b.c[i] |= F_ELEC;
    for (int i : place(get("weather"))) b.c[i] |= F_WEATHER;
    for (int i : place(get("frozen"))) b.c[i] |= F_FROZEN;
    for (int i : place(get("crack"))) RU.crack[i] = true;
    for (int i : place(get("burn"))) RU.noTouch[i] = true;
    for (int i : place(get("flame"))) { RU.flameEnd[i] = true; RU.anyFlame = true; }
    for (int i : place(get("hole"))) RU.blackHole[i] = true;
    for (int i : place(get("meta"))) RU.meta[i] = rng() % 6;
    if (get("vortex")) { auto v = place(2); RU.green = v[0]; RU.red = v[1]; }
    RU.stickyPath = get("sticky");
    if (get("fire")) { RU.firePath = true; RU.fireSize = get("firesize", 6); RU.maxHurt = get("maxhurt", 0); }
    RU.comboMin = get("combo"); RU.firstMin = get("first"); RU.firstExact = get("exact", -1);
    if (sc.p.count("anti")) RU.antiMask = 1 << get("anti");
    RU.elemMask = get("elem");
    if (get("shape")) {
        static const char* S[] = {"", "sq4", "line5", "L5", "cross5", "sq9"};
        setShieldShape(S[get("shape")]);
    }
    // 凍結 / 觸電加上去後可能產生初始三連以外的問題：確保開盤沒有可消組合
    bool mark[MAXN];
    while (findCombos(b, mark) > 0) {
        for (int i = 0; i < N; i++) if (mark[i]) b.c[i] = (b.c[i] & ~7) | (rng() % 6);
    }
    return b;
}

// 必要條件檢查（不滿足一定做不到；滿足不保證做得到）
bool feasible(const Board& b) {
    int cnt[8] = {};
    for (int i = 0; i < N; i++) if (b.c[i] != EMPTY && !(b.c[i] & F_FROZEN) && !RU.crack[i]) cnt[col(b.c[i])]++;
    int ub = 0; for (int k = 0; k < 6; k++) ub += cnt[k] / 3;
    if (ub < RU.comboMin || ub < RU.firstMin) return false;
    for (int k = 0; k < 6; k++) if ((RU.elemMask >> k & 1) && cnt[k] < 3) return false;
    if (RU.shieldN && *max_element(cnt, cnt + 6) < RU.shieldN) return false;
    for (int i = 0; i < N; i++) if (b.c[i] != EMPTY && (b.c[i] & F_ELEC) && cnt[col(b.c[i])] < 3) return false;
    return true;
}

Board parse(const char* s) { Board b; memset(b.c, EMPTY, sizeof b.c); for (int i = 0; i < N; i++) b.c[i] = s[i] - '0'; return b; }

void selfTest() {
    RU = Rules(); bool m[MAXN];
    assert(findCombos(parse("012345" "012345" "000345" "123450" "234501"), m) == 4);
    { Info in = resolve(parse("134534" "045345" "053453" "011232" "232323")); assert(in.first == 1 && in.combos == 2); }
    // 冰封珠不參與消除
    { Board b = parse("000123" "123450" "234501" "345012" "450123"); b.c[1] |= F_FROZEN; assert(findCombos(b, m) == 0); }
    // 裂痕位置不參與消除
    { Board b = parse("000123" "123450" "234501" "345012" "450123"); RU.crack[2] = true; assert(findCombos(b, m) == 0); RU = Rules(); }
    // 觸電殘留計數
    { Board b = parse("000123" "123450" "234501" "345012" "450123"); b.c[3] |= F_ELEC; Info in = resolve(b); assert(in.elecLeft == 1); }
    { Board b = parse("000123" "123450" "234501" "345012" "450123"); b.c[1] |= F_ELEC; Info in = resolve(b); assert(in.elecLeft == 0); }
    // 各情境求解結果都可合法重播
    mt19937 rng(7);
    vector<Scenario> scs = {{"elec", {{"elec", 2}}}, {"sticky", {{"sticky", 1}}}, {"fire", {{"fire", 1}}}, {"meta", {{"meta", 3}}},
                            {"vortex", {{"vortex", 1}}}, {"shield", {{"shape", 3}}}};
    for (auto& sc : scs) for (int k = 0; k < 5; k++) { Board b = setupBoard(sc, rng); Result r = solve(b, Cfg()); assert(replayCheck(b, r)); }
    puts("selftest OK");
}

// ---------------- 平行評估 ----------------
struct Case { Board b; Rules ru; };
int THREADS = max(1u, thread::hardware_concurrency());

vector<Case> makeCases(const Scenario& sc, unsigned seed, int n) {
    mt19937 rng(seed); vector<Case> v;
    for (int i = 0; i < n; i++) { Case c; c.b = setupBoard(sc, rng); c.ru = RU; v.push_back(c); }
    return v;
}

vector<Result> evalCases(const vector<Case>& cs, const Cfg& cfg, int* bad = nullptr) {
    vector<Result> out(cs.size()); atomic<int> nb{0}; atomic<size_t> idx{0};
    vector<thread> th;
    for (int t = 0; t < THREADS; t++) th.emplace_back([&] {
        size_t i;
        while ((i = idx++) < cs.size()) {
            RU = cs[i].ru; out[i] = solve(cs[i].b, cfg);
            if (!replayCheck(cs[i].b, out[i])) nb++;
        }
    });
    for (auto& x : th) x.join();
    if (bad) *bad = nb;
    return out;
}

double fitness(const vector<Result>& rs, bool goals) {
    double s = 0;
    for (auto& r : rs) s += r.combos + (goals ? 3.0 * r.ok : 0) - 0.001 * r.len;
    return s / rs.size();
}

// 對角共變異數演化策略（CMA-ES 的簡化版）：
// 每代所有候選共用同一批新盤面（配對比較），依排名加權重組，並以成功率自動調整步長
void tune(Cfg base, const Scenario& sc, int gens, int lambda, int nb, unsigned seed, const vector<int>& idxs) {
    mt19937 rng(seed); normal_distribution<float> g(0, 1);
    int D = idxs.size();
    vector<float> mean(D), sig(D);
    for (int j = 0; j < D; j++) { mean[j] = base.w[idxs[j]]; sig[j] = mean[j] == 0 ? 30.0f : max(3.0f, fabs(mean[j]) * 0.4f); }
    int mu = lambda / 2; vector<double> rw(mu); double sw = 0;
    for (int k = 0; k < mu; k++) { rw[k] = log(mu + 0.5) - log(k + 1.0); sw += rw[k]; }
    for (auto& x : rw) x /= sw;
    bool goals = false;
    for (int gen = 0; gen < gens; gen++) {
        auto cs = makeCases(sc, seed * 7919 + gen, nb);
        { RU = cs[0].ru; goals = hasGoals() || sc.p.count("elec"); }
        auto mk = [&](const vector<float>& v) { Cfg c = base; for (int j = 0; j < D; j++) c.w[idxs[j]] = v[j]; return c; };
        double fc = fitness(evalCases(cs, mk(mean)), goals);
        vector<pair<double, vector<float>>> pop;
        for (int k = 0; k < lambda; k++) {
            vector<float> z(D), v(D);
            for (int j = 0; j < D; j++) { z[j] = g(rng); v[j] = mean[j] + sig[j] * z[j]; }
            pop.push_back({fitness(evalCases(cs, mk(v)), goals), v});
        }
        sort(pop.begin(), pop.end(), [](auto& a, auto& b) { return a.first > b.first; });
        int succ = 0; for (auto& p : pop) succ += p.first > fc;
        vector<float> nm(D, 0);
        for (int k = 0; k < mu; k++) for (int j = 0; j < D; j++) nm[j] += rw[k] * pop[k].second[j];
        for (int j = 0; j < D; j++) {    // 各維步長依「好候選的分散程度」調整
            double v = 0; for (int k = 0; k < mu; k++) v += rw[k] * pow(pop[k].second[j] - mean[j], 2);
            sig[j] = 0.7 * sig[j] + 0.3 * sqrt(v);
            sig[j] *= succ > lambda / 5 ? 1.05 : 0.9;   // 1/5 成功率法則
            sig[j] = max(sig[j], 0.5f);
        }
        mean = nm;
        printf("gen %2d | center=%.3f best=%.3f succ=%d/%d | ", gen, fc, pop[0].first, succ, lambda);
        for (int j = 0; j < D; j++) printf("%s:%.1f ", FNAME[idxs[j]], mean[j]);
        puts(""); fflush(stdout);
    }
    printf("FINAL w=");
    Cfg c = base; for (int j = 0; j < D; j++) c.w[idxs[j]] = mean[j];
    for (int i = 0; i < NF; i++) printf("%.2f%s", c.w[i], i + 1 < NF ? "," : "\n");
}

int main(int argc, char** argv) {
    Cfg cfg; int boards = 200; unsigned seed = 42; string outFile; int gens = 0, lambda = 12;
    vector<int> tidx = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    vector<Scenario> scs;
    auto parseW = [](const string& v, float* w) { stringstream ss(v); string t; int j = 0; while (getline(ss, t, ',') && j < NF) w[j++] = stof(t); };
    for (int i = 1; i < argc; i++) {
        string a = argv[i];
        if (a == "test") { selfTest(); return 0; }
        auto eq = a.find('=');
        if (eq == string::npos) continue;
        string k = a.substr(0, eq), v = a.substr(eq + 1);
        if (k == "beam") cfg.beam = stoi(v); else if (k == "maxpath") cfg.maxPath = stoi(v);
        else if (k == "dirs") cfg.dirs = stoi(v); else if (k == "boards") boards = stoi(v);
        else if (k == "seed") seed = stoi(v); else if (k == "elemw") ELEMW = stof(v); else if (k == "shw") SHW = stof(v);
        else if (k == "rows") { R = max(5, min(MAXR, stoi(v))); N = R * C; }
        else if (k == "w") { if (v == "v1") memcpy(cfg.w, W_V1, sizeof cfg.w); else parseW(v, cfg.w); }
        else if (k == "w2") parseW(v, cfg.w2);
        else if (k == "divk") cfg.divK = stof(v); else if (k == "ens") cfg.ens = stoi(v);
        else if (k == "shorten") cfg.shorten = stoi(v); else if (k == "shortlen") cfg.shortLen = stof(v);
        else if (k == "threads") THREADS = stoi(v); else if (k == "out") outFile = v;
        else if (k == "tune") gens = stoi(v); else if (k == "lambda") lambda = stoi(v);
        else if (k == "tidx") { tidx.clear(); stringstream ss(v); string t; while (getline(ss, t, ',')) tidx.push_back(stoi(t)); }
        else if (k == "sc") {   // sc=名稱:key=val;key=val
            Scenario s; auto c = v.find(':'); s.name = v.substr(0, c);
            if (c != string::npos) { stringstream ss(v.substr(c + 1)); string kv; while (getline(ss, kv, ';')) { auto e = kv.find('='); s.p[kv.substr(0, e)] = e == string::npos ? 1 : stoi(kv.substr(e + 1)); } }
            scs.push_back(s);
        }
    }
    if (scs.empty()) scs.push_back({"plain", {}});
    if (gens > 0) { tune(cfg, scs[0], gens, lambda, boards, seed, tidx); return 0; }
    if (getenv("SHOW")) {   // 印出測試盤與解，供網站模擬器對照
        const char* E[] = {"🔴", "🔵", "🟢", "🟡", "🟣", "🩷"};
        const char* Dn[] = {"→", "↓", "←", "↑", "↘", "↙", "↖", "↗"};
        auto cs = makeCases(scs[0], seed, atoi(getenv("SHOW")));
        for (size_t k = 0; k < cs.size(); k++) {
            RU = cs[k].ru; Result r = solve(cs[k].b, cfg);
            printf("盤 %zu：combo=%d 首消=%d 步數=%d 起點=第%d列第%d行\n", k + 1, r.combos, r.first, r.len, r.start / C + 1, r.start % C + 1);
            for (int y = 0; y < R; y++) { for (int x = 0; x < C; x++) printf("%s", E[col(cs[k].b.c[y * C + x])]); puts(""); }
            for (int d : r.path) printf("%s", Dn[d]);
            puts("\n");
        }
        return 0;
    }
    FILE* fo = outFile.empty() ? nullptr : fopen(outFile.c_str(), "w");
    for (auto& sc : scs) {
        auto cs = makeCases(sc, seed, boards);
        int bad = 0;
        auto t0 = chrono::steady_clock::now();
        auto rs = evalCases(cs, cfg, &bad);
        double wall = chrono::duration<double>(chrono::steady_clock::now() - t0).count();
        double sc_ = 0, sl = 0, sms = 0, sf = 0; int okN = 0, feas = 0, okFeas = 0;
        for (size_t k = 0; k < cs.size(); k++) {
            RU = cs[k].ru; auto& r = rs[k];
            sc_ += r.combos; sl += r.len; sms += r.ms; okN += r.ok; sf += r.first;
            bool f = feasible(cs[k].b); feas += f; okFeas += f && r.ok;
            if (r.ok && !f) bad++;
            if (fo) fprintf(fo, "%s,%zu,%d,%d,%d,%d,%.1f\n", sc.name.c_str(), k, r.combos, r.first, r.len, (int)r.ok, r.ms);
        }
        RU = cs[0].ru;
        printf("%-14s rows=%d | combo=%.3f first=%.2f len=%.1f ms=%.0f wall=%.0fs", sc.name.c_str(), R, sc_ / boards, sf / boards, sl / boards, sms / boards, wall);
        if (hasGoals() || sc.p.count("elec"))
            printf(" | 達成率=%.0f%% | 可行盤中達成=%.0f%%", 100.0 * okN / boards, feas ? 100.0 * okFeas / feas : 0.0);
        if (bad) printf(" | 重播失敗=%d", bad);
        puts(""); fflush(stdout);
    }
    if (fo) fclose(fo);
}
