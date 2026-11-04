# 神魔之塔轉珠求解器

Beam search 轉珠求解核心（C++）＋ Android App。目前是 **M1：手動輸入盤面 → 求解 → 顯示 / 播放路徑**。

## 下載 APK

**最新版直接下載：<https://github.com/rcj811/Tos-orb-solver/releases/latest/download/tos-orb-solver.apk>**

每次推送到 `main`，GitHub Actions 會自動測試、編譯，並把 APK 發布到 **Releases**（最新一版標記為 Latest），上面的連結永遠指向最新版。
首次安裝需允許「安裝未知來源 App」。簽章固定，新版可直接覆蓋安裝。

## 使用方式

1. 點選下方顏色（火水木光暗心），在盤面上點擊或拖曳上色，排成遊戲中的盤面
2. 按「求解」：白圈是起點、箭頭是終點，下方列出方向序列
3. 「播放路徑」會一步步示範拖曳過程
4. 設定：版面 6×5～6×8、標準 / 高品質（beam 450 / 1500）、8 / 4 方向

## 求解核心（`app/src/main/cpp`）

- AutoDora 式 beam search：每格播種 → 8 方向展開（禁止立即回頭）→ 完整結算（消除、落下、連鎖）→ 評分 → 淘汰
- 狀態去重 ＋ beam 多樣性（同一手指位置限量保留）
- 權重經自動調參，並以「訓練 / 驗證 / 測試」分開的盤面驗證
- 已支援關卡機制（核心 API 已實作，App 介面尚未開放）：觸電、風化、冰封、裂痕、燃燒、黏手路徑、火焰路徑、火焰區、黑洞、綠紅漩渦、變形魔球、Combo 盾、首消盾、固定 Combo、反屬性盾、多屬性盾、拼圖盾

驗證結果（2000 個未參與調參的盤面，6×5）：

| 設定 | 平均 combo | 首消 combo |
|---|---|---|
| 最初手寫權重 | 7.48 | 3.91 |
| 最終（調參權重 ＋ 多樣性，beam 450） | 7.62 | 4.47 |
| 最終 ＋ 高品質（beam 1500） | 7.82 | 4.44 |

細節見 `research/FINAL_CONFIG.md`。

## 專案結構

```
app/src/main/cpp/        求解核心（tos_solver.*）與 JNI 橋接
app/src/main/java/...    Kotlin App（MainActivity、BoardView、NativeSolver）
tests/test_solver.cpp    核心測試（CI 先跑測試，通過才編 APK）
research/                研究版求解器、調參與驗證紀錄
.github/workflows/       自動編譯與發布 APK
```

本機測試求解核心：

```
g++ -std=c++17 -O2 -Iapp/src/main/cpp app/src/main/cpp/tos_solver.cpp tests/test_solver.cpp -o test_solver && ./test_solver
```

## 路線圖

- [x] M1 求解核心 App（手動輸入盤面）
- [ ] M2 截圖匯入與符石辨識（低信心標為「未知」）
- [ ] M3 即時懸浮提示（螢幕擷取 ＋ 懸浮視窗畫路徑）
- [ ] M4 自動拖曳（無障礙服務；違反遊戲條款，需自負風險）
- [ ] M5 自我學習（辨識修正回收、影片分析新珠、分關卡調參）

## 聲明

本專案與 Mad Head 及《神魔之塔》無關。自動操作遊戲違反其使用條款，可能導致帳號受罰。
