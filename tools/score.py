#!/usr/bin/env python3
"""Evaluate parsed statements against the fixtures, by row position. No AWS.

Reads the Scala-produced parses (results/<lang>_<name>.parsed.json, same shape as
the fixtures) and the fixtures (statements/<lang>/<name>.json), compares row i to
row i (the fixture is in statement order, so a correct parse reproduces it), and
reports:
  - rows parsed vs expected
  - date / amount correct at position i
  - description INCLUSION at position i: fraction of the fixture description's
    words present in the parse (extra words like a city are not penalised)
results/rungs.json supplies the winning ladder rung per statement.

    python3 score.py        # uses this dir, or $EVAL_DIR
"""
import glob
import json
import os
from decimal import Decimal

EVAL = os.environ.get("EVAL_DIR", os.path.dirname(os.path.abspath(__file__)))


def norm(s: str) -> str:
    return " ".join(s.upper().split())


def inclusion(fixture: str, parsed: str) -> float:
    fw = norm(fixture).split()
    if not fw:
        return 1.0
    pool = norm(parsed).split()
    found = 0
    for w in fw:
        if w in pool:
            pool.remove(w)
            found += 1
    return found / len(fw)


def amt(x) -> Decimal:
    return Decimal(str(x))


rows = []
all_incl = []
mismatches = []

for parsed_path in sorted(glob.glob(f"{EVAL}/results/*.parsed.json")):
    base = os.path.basename(parsed_path)[: -len(".parsed.json")]  # e.g. en_charge_card
    lang, name = base.split("_", 1)
    stmt = f"{lang}/{name}"
    fx = json.load(open(f"{EVAL}/statements/{lang}/{name}.json"))["transactions"]
    pdoc = json.load(open(parsed_path))
    pr = pdoc["transactions"]
    n = len(fx)
    date_ok = amount_ok = full = 0
    incl_sum = 0.0
    for i in range(n):
        g = fx[i]
        p = pr[i] if i < len(pr) else None
        if p is None:
            mismatches.append((stmt, i, "MISSING ROW"))
            all_incl.append(0.0)
            continue
        d_ok = p["date"] == g["date"]
        a_ok = amt(p["amount"]) == amt(g["amount"])
        date_ok += d_ok
        amount_ok += a_ok
        if not (d_ok and a_ok):
            mismatches.append((stmt, i, f"date {p['date']}~{g['date']} amt {p['amount']}~{g['amount']}"))
        inc = inclusion(g["description"], p["description"])
        incl_sum += inc
        all_incl.append(inc)
        if inc == 1.0:
            full += 1
    rung = pdoc.get("producedBy", "?")
    rows.append((stmt, len(pr), n, date_ok, amount_ok, full, incl_sum / n if n else 0.0, rung))

print(f"{'statement':22}{'rows':>8}  {'date@pos':>9}  {'amt@pos':>9}  {'descIncl':>9}  rung")
print("-" * 86)
for stmt, got, n, d, a, full, inc, rung in rows:
    print(f"{stmt:22}{f'{got}/{n}':>8}  {f'{d}/{n}':>9}  {f'{a}/{n}':>9}  {inc * 100:7.1f}%  {rung}")
# (overall is already a percentage)

tn = sum(r[2] for r in rows)
td = sum(r[3] for r in rows)
ta = sum(r[4] for r in rows)
tf = sum(r[5] for r in rows)
overall = sum(all_incl) / len(all_incl) * 100 if all_incl else 0.0
print("-" * 86)
print(f"{'TOTAL':22}{'':>8}  {f'{td}/{tn}':>9}  {f'{ta}/{tn}':>9}  {overall:7.1f}%")
print(f"\nOrder preserved (date AND amount correct at every position): {'YES' if td == ta == tn else 'NO'}")
print(f"Fixture description fully included: {tf}/{tn} rows")
if mismatches:
    print(f"\n{len(mismatches)} position mismatch(es):")
    for s, i, w in mismatches[:20]:
        print(f"  {s} row {i}: {w}")
