#!/bin/bash
# 在模擬器上把自轉珠所有功能操作一遍，檢查會不會崩潰（工具列消失）。
# 用法：bash ci/emulator-test.sh <apk>
set -u
APK="$1"
PKG=com.rcj811.tossolver
OUT=emu-out
mkdir -p "$OUT"
step=0

say() { echo "=== $*"; }
show() { adb shell am start -n $PKG/.DemoScreenActivity --es img "$1" > /dev/null 2>&1; sleep 1; }
cmd() { adb shell am broadcast -a com.rcj811.tossolver.TEST --es cmd "$1" > /dev/null 2>&1; }
shot() { step=$((step + 1)); adb exec-out screencap -p > "$OUT/$(printf %02d $step)-$1.png" 2> /dev/null; }
alive() {
  local pid
  pid=$(adb shell pidof $PKG | tr -d '\r')
  if [ -z "$pid" ]; then echo "!!! 程式已經不在執行（$1）"; return 1; fi
  echo "    程式執行中 pid=$pid（$1）"
}

say "安裝"
adb install -r -g "$APK"
adb shell settings put global tos_orb_test 1
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 4
# 測試畫面放進 App 自己的資料夾（debug 版可以用 run-as）
adb shell mkdir -p /data/local/tmp/demo
adb push ci/demo/. /data/local/tmp/demo/ > /dev/null
adb shell chmod 644 /data/local/tmp/demo/*
adb shell run-as $PKG mkdir -p files/demo
for f in ci/demo/*.jpg; do adb shell run-as $PKG cp /data/local/tmp/demo/$(basename $f) files/demo/; done
adb shell run-as $PKG ls -la files/demo/

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!

say "開啟無障礙服務"
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.AutoTurnService
adb shell settings put secure accessibility_enabled 1
sleep 6
show normal1; sleep 2
cmd geo:62,922,180,5
cmd state; shot start; alive 啟動

say "1. ▶ 自動轉珠：一般盤／變暗／過場／問號盤／結算／黑畫面輪流"
cmd play
for img in normal1 normal1 normal2 dim dim normal3 transition hidden hidden hidden normal1 result result black normal2 normal3 dim normal1; do
  show $img; sleep 4; cmd state
done
shot auto; alive 自動轉珠
cmd play; sleep 2

say "2. 轉：每種畫面各按一次"
for img in normal1 normal2 hidden dim result normal3; do
  show $img; cmd once; sleep 9; shot "once-$img"
done
alive 轉

say "3. ＋：讀技能卡（沒有卡片的畫面）"
show normal2; cmd plus; sleep 8; shot plus
adb shell input keyevent 4; sleep 1
show normal2; sleep 2
alive ＋

say "4. 校：點格子、長按、各種按鈕"
show normal1; cmd calib; sleep 4; shot calib
cmd calibtap:40; sleep 2
cmd calibtap:40; sleep 2; shot calib-taps
cmd monkey:25:calib; sleep 3
adb shell input keyevent 4; sleep 1
cmd click:關閉; sleep 1
show normal1; cmd calib; sleep 3
cmd calibtap:30; sleep 1
cmd click:儲存並學習; sleep 2
cmd click:關閉; sleep 1
alive 校

say "5a. 關：分組版面與選中變色"
L0=$(wc -l < "$OUT/logcat.txt")
show normal3; cmd stage; sleep 4
cmd click:形狀盾; sleep 1
cmd click:十字; sleep 1
cmd click:黏手路徑; sleep 1
cmd click:形狀顏色; sleep 1
cmd stagedump; sleep 2
sleep 2
tail -n +"$L0" "$OUT/logcat.txt" | grep -E "TOSTEST|AndroidRuntime" | sed 's/.*TOSTEST[^:]*: //' | head -120
adb shell input keyevent 4; sleep 1
cmd click:取消; sleep 1

say "5. 關：每顆按鈕、選項、顏色、筆刷、盤面格子"
for round in 1 2 3; do
  show normal3; cmd stage; sleep 3; shot "stage-$round"
  cmd monkey:120:stage; sleep 1
  cmd stagecell:30; sleep 1
  cmd monkey:120:stage; sleep 1
  cmd click:完成; sleep 9
  adb shell input keyevent 4; sleep 1
done
alive 關

say "6. 自動轉珠中狂點全部按鈕"
show normal1; cmd play
for i in 1 2 3 4 5 6 7 8; do
  for img in normal2 dim hidden normal1; do show $img; cmd monkey:15:all; sleep 1; done
  adb shell input keyevent 4
  cmd state
done
shot monkey
cmd state
alive 狂點

say "7. ⚙ 訊息列開關"
cmd gear; sleep 1; cmd gear; sleep 1
alive ⚙

say "7b. 匯出／匯入資料"
cmd backup; sleep 3
grep "備份測試" "$OUT/logcat.txt" | tail -1
alive 備份

say "8. 長時間自動轉珠（3 分鐘）"
show normal1; cmd state
cmd play
for i in $(seq 1 18); do
  case $((i % 6)) in 0) show normal1;; 1) show dim;; 2) show normal2;; 3) show transition;; 4) show hidden;; 5) show normal3;; esac
  sleep 10
done
shot long; cmd state
alive 長時間

sleep 3
kill $LOGPID 2> /dev/null
adb shell run-as $PKG cat files/crash_log.txt > "$OUT/crash_log.txt" 2> /dev/null
adb shell dumpsys accessibility > "$OUT/accessibility.txt" 2> /dev/null

say "ANR／崩潰細節"
ANRLINE=$(grep -n "ANR in $PKG" "$OUT/logcat.txt" | head -1 | cut -d: -f1)
if [ -n "$ANRLINE" ]; then
  echo "--- ANR 之前的 30 個測試步驟"
  head -n "$ANRLINE" "$OUT/logcat.txt" | grep "TOSTEST" | tail -30
  echo "--- ANR 訊息"
  sed -n "${ANRLINE},$((ANRLINE + 25))p" "$OUT/logcat.txt"
  echo "--- 主執行緒卡在哪裡（/data/anr）"
  adb root > /dev/null 2>&1; sleep 3
  for f in $(adb shell ls /data/anr/ 2> /dev/null | tr -d '\r'); do
    echo "檔案 $f"
    adb shell cat "/data/anr/$f" 2> /dev/null | awk '/Cmd line: com.rcj811.tossolver/{on=1} on' | awk '/^"main"/{p=1} p{print; n++} n>45{exit}'
  done
fi

say "結果"
grep -n "TOSTEST" "$OUT/logcat.txt" | tail -5
if grep -q "備份測試 不一致" "$OUT/logcat.txt"; then echo "!!! 匯出匯入資料不一致"; exit 1; fi
FATAL=$(grep -nE "FATAL EXCEPTION|Fatal signal|ANR in $PKG|$PKG.*has died|Process: $PKG" "$OUT/logcat.txt" | head -40)
if [ -n "$FATAL" ]; then
  echo "!!! 發現崩潰："
  echo "$FATAL"
  grep -nA40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -120
  exit 1
fi
if [ -s "$OUT/crash_log.txt" ]; then
  echo "!!! 有記錄到錯誤："; head -60 "$OUT/crash_log.txt"; exit 1
fi
alive 結束 || exit 1
echo "沒有崩潰"
