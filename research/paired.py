"""配對比較：同一批盤面上，B 相對 A 的平均差與 95% 信賴區間。
用法：python3 paired.py base.csv other1.csv [other2.csv ...]
CSV 欄位：情境,盤號,combo,首消,步數,達成,ms
"""
import sys, math, csv

def load(p):
    d = {}
    for r in csv.reader(open(p)):
        d[(r[0], int(r[1]))] = (int(r[2]), int(r[3]), int(r[4]), int(r[5]), float(r[6]))
    return d

def ci(xs):
    n = len(xs); m = sum(xs) / n
    sd = math.sqrt(sum((x - m) ** 2 for x in xs) / (n - 1)) if n > 1 else 0
    return m, 1.96 * sd / math.sqrt(n)

base = load(sys.argv[1])
print(f"基準 {sys.argv[1]}: combo={sum(v[0] for v in base.values())/len(base):.3f}")
for p in sys.argv[2:]:
    o = load(p); keys = [k for k in base if k in o]
    out = []
    for i, name in [(0, "combo"), (1, "首消"), (2, "步數"), (3, "達成")]:
        m, h = ci([o[k][i] - base[k][i] for k in keys])
        sig = "*" if abs(m) > h else " "
        out.append(f"{name} {m:+.3f}±{h:.3f}{sig}")
    t = sum(o[k][4] for k in keys) / sum(base[k][4] for k in keys)
    print(f"{p:28s} " + "  ".join(out) + f"  耗時x{t:.2f}")
