// 轉珠求解核心
// Beam search（AutoDora 式：播種 → 展開 → 結算評分 → 淘汰）＋ beam 多樣性
// 權重與參數皆經離線驗證，詳見 research/FINAL_CONFIG.md
#pragma once
#include <cstdint>
#include <vector>

namespace tos {

constexpr int COLS = 6;
constexpr int MAX_ROWS = 8;
constexpr int MAX_CELLS = COLS * MAX_ROWS;
constexpr int MAX_PATH = 120;   // 路徑上限（步），實際長度依轉珠時間計算

// 珠子編碼：低 3 bits 為珠種，高位為隨珠移動的狀態旗標
enum Orb : uint8_t { FIRE = 0, WATER = 1, WOOD = 2, LIGHT = 3, DARK = 4, HEART = 5 };
constexpr uint8_t EMPTY = 0xFF;
// 符石規則旗標（可自由組合，對應 App 的符石類別設定）
constexpr uint8_t F_PREFER = 0x08;      // 盡量消除（消掉有加分）
constexpr uint8_t F_MUST = 0x10;        // 必須消除（回合結束仍殘留 → 未達成條件，例：觸電攻擊力為 0）
constexpr uint8_t F_NODISSOLVE = 0x20;  // 不能消除
constexpr uint8_t F_NOTOUCH = 0x40;     // 不能碰（路徑不能經過、不能當起點）
constexpr uint8_t F_AVOID = 0x80;       // 盡量不要消除（消掉會扣分，例：弱化符石）
// 內建符石狀態
constexpr uint8_t F_ELEC = F_NOTOUCH | F_MUST;          // 觸電：不能碰、必須消除
constexpr uint8_t F_FROZEN = F_NOTOUCH | F_NODISSOLVE;  // 冰封 / 石化：不能碰、不能消除
constexpr uint8_t F_WEATHER = F_NOTOUCH | F_PREFER;     // 風化 / 黏腐：不能碰、消掉有加分
inline int orbColor(uint8_t v) { return v & 7; }

// 方向：0右 1下 2左 3上 4右下 5左下 6左上 7右上
extern const int DX[8];
extern const int DY[8];

// 關卡規則（預設為一般盤，無任何限制）
struct Rules {
    bool crack[MAX_CELLS] = {};      // 裂痕：此位置的珠不能被消除
    bool noTouch[MAX_CELLS] = {};    // 燃燒 / 地獄火 / 黏手之地 / 雷射：不可進入
    bool flameEnd[MAX_CELLS] = {};   // 火焰區：最後一步須停在其中之一
    bool blackHole[MAX_CELLS] = {};  // 黑洞：最後一步不可停在此
    int meta[MAX_CELLS];             // 變形魔球：經過即變成該珠種（-1 = 無）
    int green = -1, red = -1;        // 綠紅漩渦：須從綠出發、停在紅
    bool anyFlame = false;
    bool stickyPath = false;         // 黏手路徑：碰過的格不能再碰
    bool firePath = false;           // 火焰路徑：短時間內重踩會受傷
    int fireSize = 6, maxHurt = 0;
    int comboMin = 0;                // Combo 盾（≥X）
    int firstMin = 0;                // 首消 Combo 盾（≥X）
    int firstExact = -1;             // 固定 X Combo 盾（首消剛好 X）
    int firstAttrMin = 0;            // 首消 ≥X 組屬性符石盾（首消中不含心珠的 combo 數）
    bool first3Only = false;         // 首消每組只能剛好 3 顆（不能有 4 顆以上連成一組）
    int firstOrbsExact = 0;            // 首消符石數量盾（NUM X）：首批消除「剛好」X 粒符石（疊珠不算）
    int antiMask = 0;                // 反屬性盾：首消不可消除這些珠種（bit = 珠種）
    int elemMask = 0;                // 多屬性盾：本回合須消除這些珠種
    bool shield[MAX_CELLS] = {};     // 拼圖盾格
    int shieldN = 0;
    Rules();
};

// 關卡規則的整數編碼（App 端 StageRules.toInts() 產生），長度 RULES_INTS：
//  [0] 版本(1) [1] comboMin [2] firstMin [3] firstExact(-1=無) [4] antiMask [5] elemMask
//  [6] stickyPath [7] firePath [8] fireSize [9] maxHurt [10] green [11] red
//  [12 .. 12+48) 每格旗標  [60 .. 60+48) 每格變形魔球珠種（-1=無）
//  [108] firstAttrMin  [109] firstOrbsExact  [110] first3Only  [111] 保留（長度 108 的舊格式仍可讀）
constexpr int RULES_INTS_V1 = 12 + MAX_CELLS * 2;
constexpr int RULES_INTS = RULES_INTS_V1 + 4;
enum CellFlag { CF_SHIELD = 1, CF_CRACK = 2, CF_NO_TOUCH = 4, CF_FLAME_END = 8, CF_BLACK_HOLE = 16 };

// 解碼；長度不足或版本不符回傳 false（out 維持一般盤）
bool rulesFromInts(const int* v, int n, Rules& out);

constexpr int NUM_FEATURES = 7;

struct Config {
    int rows = 5;
    int beam = 450;          // 450 = 標準；1500 = 高品質（約 3.5 倍時間）
    int maxPath = 30;        // 建議 defaultMaxPath(rows)
    int dirs = 8;            // 8 = 含斜轉；4 = 僅上下左右
    float divK = 0.75f;      // beam 多樣性（驗證後最佳值）
    // 評分權重：相鄰同色、隔格同色、斜向同色、已消珠數、步數（無作用）、首消 combo、仍 ≥3 顆的顏色數
    float weights[NUM_FEATURES] = {24.55f, 12.73f, 12.02f, 23.49f, 2.07f, 21.83f, 61.91f};
    float shieldGuide = 150; // 拼圖盾引導強度
    float elemGuide = 600;   // 多屬性盾引導強度
    bool preferOrbs = true;  // combo 相同時，選消除珠數較多（傷害較高）的解，而不是較短的路徑
    int timeBudgetMs = 0;
    int shieldColor = -1;    // 拼圖盾：指定用哪一種顏色鋪盾（-1 = 自動，取盾格上最多的顏色）    // >0：時間內逐步加寬 beam（×3）重算，取最好的解（不佔遊戲轉珠時間）
};

inline int defaultMaxPath(int rows) { return 25 + rows * 2; }

struct Solution {
    bool found = false;
    int combos = 0;
    int firstCombos = 0;
    int firstOrbs = 0;       // 首批消除的珠數
    int cleared = 0;         // 消除的珠數
    bool goalsMet = false;   // 所有關卡條件都達成
    int start = -1;          // 起點格（row * 6 + col）
    std::vector<int> dirs;   // 方向序列
    double ms = 0;
};

// cells：rows * 6 格，由上而下、由左而右
Solution solve(const uint8_t* cells, const Config& cfg, const Rules& rules = Rules());

struct Outcome {
    bool legal = false;      // 路徑每一步都合法
    int combos = 0;
    int firstCombos = 0;
    bool goalsMet = false;
};

// 獨立重播一條路徑（用於驗證與動畫）
Outcome replay(const uint8_t* cells, int start, const std::vector<int>& dirs, const Config& cfg,
               const Rules& rules = Rules());

// 盤面「原樣」第一輪可消 combo 數（產生隨機盤時用來避開初始三連）
int countCombos(const uint8_t* cells, int rows, const Rules& rules = Rules());

}  // namespace tos
