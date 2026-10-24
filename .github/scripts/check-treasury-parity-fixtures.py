#!/usr/bin/env python3
"""Guard: the treasury golden parity fixtures are well-formed, complete and oracle-true (ADR-0344 D9).

WHY
  ADR-0344 makes golden-fixture parity the acceptance evidence for the treasury cash-flow
  ledger. A fixture is only evidence if its `expected` block was computed INDEPENDENTLY of the
  code it checks. The easiest way to lose that is silent: someone sees the Kotlin replay
  disagree, "fixes" the fixture to match the code, and the gate now certifies the bug. So for
  every fixture whose `source` is `independent-oracle`, this gate re-runs the Python oracle
  (openbank-treasury-service/src/test/parity-oracle/oracle.py) and demands byte-equal output.

WHAT IT CHECKS
  1. format: required keys, `product` in the closed list and equal to `deal.product`, `source`
     in the closed vocabulary, ISO dates, decimal STRINGS (a JSON number is refused — floats are
     how 2.675 becomes 2.67), `tolerance.amount` present;
  2. completeness: every product type in REQUIRED_PRODUCTS has at least one fixture. ADR-0344
     delivers products in phases; the set holds the products whose cash-flow ledger has landed
     (phase 0: the five pre-existing products) and each later phase widens it with its products;
  3. uniqueness: no two fixtures share an `id`, and the file name is `<id>.json`;
  4. truth: `expected` == oracle.compute(fixture) for `independent-oracle` fixtures;
  5. replay: the Kotlin `TreasuryParityReplayTest` exists, is not @Disabled, replays the whole
     `/parity` resource directory (so a fixture can never sit there unreplayed) and its
     MIN_FIXTURES floor does not exceed the fixture count. The test itself runs in the treasury
     service build on every PR that touches the service, fixtures included.

Exit codes: 0 clean, 1 findings, 2 cannot run (no fixture directory / oracle import failed).

    python3 .github/scripts/check-treasury-parity-fixtures.py [--root .] [--self-test]
"""

from __future__ import annotations

import argparse
import copy
import datetime
import importlib.util
import json
import pathlib
import re
import sys
import tempfile

FIXTURE_DIR = "openbank-treasury-service/src/test/resources/parity"
ORACLE = "openbank-treasury-service/src/test/parity-oracle/oracle.py"
REPLAY = "openbank-treasury-service/src/test/kotlin/com/openbank/treasury/parity/TreasuryParityReplayTest.kt"
HELPER = "openbank-treasury-service/src/test/kotlin/com/openbank/treasury/parity/ParityFixtures.kt"
SOURCES = {"independent-oracle", "hand-computed", "published-example"}
# ADR-0344 delivery phases: the products whose cash-flow ledger is on main. Every one needs a
# fixture; a later phase adds its products here in the same PR that adds their fixtures.
REQUIRED_PRODUCTS = ("MM_PLACEMENT", "MM_BORROWING", "CNB_DEPOSIT_FACILITY", "CNB_LOMBARD", "FX_SPOT")
REQUIRED = ("id", "product", "source", "sourceNote", "deal", "expected", "tolerance")
FLOW_KEYS = ("legIndex", "periodIndex", "kind", "paymentDate", "notional", "rate", "amount")
DATE_KEYS = {"accrualStart", "accrualEnd", "fixingDate", "paymentDate", "tradeDate", "valueDate",
             "maturityDate", "date", "spotDate", "farDate"}
DECIMAL_RE = re.compile(r"^-?\d+(\.\d+)?$")
NUMERIC_KEYS = {"notional", "rate", "amount", "spread", "cap", "floor", "rateUntilFirstFixing",
                "nominal", "yield", "cleanPrice", "spotRate", "baseAmount", "baseRate", "quoteRate",
                "haircutPercent", "dirtyPricePercent", "forwardRate", "pricePer100",
                "accruedInterest", "cashAmount", "contractRate"}


def load_oracle(root: pathlib.Path):
    path = root / ORACLE
    spec = importlib.util.spec_from_file_location("treasury_parity_oracle", path)
    if spec is None or not path.exists():
        return None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _walk(node, where, findings):
    """Dates must be ISO, money/rate fields decimal strings — anywhere in the document."""
    if isinstance(node, dict):
        for k, v in node.items():
            here = f"{where}.{k}"
            if isinstance(v, float) or (isinstance(v, int) and not isinstance(v, bool) and k in NUMERIC_KEYS):
                findings.append(f"{here}: JSON number {v!r} — write decimal values as strings")
            elif k in DATE_KEYS and v is not None:
                if not isinstance(v, str):
                    findings.append(f"{here}: date must be an ISO string")
                else:
                    try:
                        datetime.date.fromisoformat(v)
                    except ValueError:
                        findings.append(f"{here}: {v!r} is not an ISO date")
            elif k in NUMERIC_KEYS and not isinstance(v, (dict, list)) and v is not None and not (isinstance(v, str) and DECIMAL_RE.match(v)):
                findings.append(f"{here}: {v!r} is not a decimal string")
            _walk(v, here, findings)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            _walk(v, f"{where}[{i}]", findings)


def check_fixture(name, fx, products, oracle):
    out = []
    missing = [k for k in REQUIRED if k not in fx]
    if missing:
        return [f"{name}: missing required key(s) {missing}"]
    if fx["product"] not in products:
        out.append(f"{name}: product {fx['product']!r} is not in the closed list")
    if fx["deal"].get("product") != fx["product"]:
        out.append(f"{name}: product {fx['product']!r} != deal.product {fx['deal'].get('product')!r}")
    if fx["source"] not in SOURCES:
        out.append(f"{name}: source {fx['source']!r} not in {sorted(SOURCES)}")
    if not str(fx.get("sourceNote", "")).strip():
        out.append(f"{name}: sourceNote is empty — state the conventions the case depends on")
    if name != f"{fx['id']}.json":
        out.append(f"{name}: file name must be '<id>.json' (id is {fx['id']!r})")
    tol = fx["tolerance"].get("amount") if isinstance(fx["tolerance"], dict) else None
    if not (isinstance(tol, str) and DECIMAL_RE.match(tol)):
        out.append(f"{name}: tolerance.amount must be a decimal string")
    flows = fx["expected"].get("flows") if isinstance(fx["expected"], dict) else None
    if not isinstance(flows, list) or not flows:
        out.append(f"{name}: expected.flows must be a non-empty list")
    else:
        for i, fl in enumerate(flows):
            gone = [k for k in FLOW_KEYS if k not in fl]
            if gone:
                out.append(f"{name}: expected.flows[{i}] missing {gone}")
    _walk(fx, name, out)
    if not out and fx["source"] == "independent-oracle":
        try:
            recomputed = oracle.compute(copy.deepcopy(fx))
        except Exception as exc:  # noqa: BLE001 — any oracle failure is a finding about this fixture
            return [f"{name}: oracle cannot evaluate the deal: {exc}"]
        if json.loads(json.dumps(recomputed)) != fx["expected"]:
            out.append(f"{name}: expected differs from the oracle output — an independent-oracle "
                       "fixture is regenerated (generate_fixtures.py), never hand-edited")
    return out


def run(root: pathlib.Path):
    """Returns (exit_code, findings, subjects)."""
    oracle = load_oracle(root)
    fdir = root / FIXTURE_DIR
    if oracle is None or not fdir.is_dir():
        return 2, [f"cannot run: oracle at {ORACLE} or fixtures at {FIXTURE_DIR} missing"], 0
    products = set(oracle.PRODUCTS)
    findings, seen, covered = [], {}, set()
    files = sorted(fdir.glob("*.json"))
    for path in files:
        try:
            fx = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            findings.append(f"{path.name}: invalid JSON: {exc}")
            continue
        if not isinstance(fx, dict):
            findings.append(f"{path.name}: top level must be an object")
            continue
        findings.extend(check_fixture(path.name, fx, products, oracle))
        fid = fx.get("id")
        if fid in seen:
            findings.append(f"{path.name}: duplicate id {fid!r} (also in {seen[fid]})")
        seen[fid] = path.name
        if fx.get("product") in products:
            covered.add(fx["product"])
    for p in sorted(set(REQUIRED_PRODUCTS) - covered):
        findings.append(f"product {p} has no parity fixture (ADR-0344 D9: at least one per product type)")
    findings.extend(check_replay(root, len(files)))
    return (1 if findings else 0), findings, len(files)


def check_replay(root: pathlib.Path, fixtures: int):
    path = root / REPLAY
    if not path.exists():
        return [f"{REPLAY} is missing: no fixture is replayed against the service (ADR-0344 D9)"]
    text = path.read_text(encoding="utf-8")
    out = []
    if re.search(r"@Disabled\b", text):
        out.append(f"{REPLAY} is @Disabled: the fixtures are not replayed")
    helper = root / HELPER
    helper_text = helper.read_text(encoding="utf-8") if helper.exists() else ""
    whole_dir = 'getResource("/parity")' in text or (
        "ParityFixtures.fixtures()" in text and 'getResource("/parity")' in helper_text)
    if not whole_dir:
        out.append(f"{REPLAY} does not replay the whole /parity resource directory")
    m = re.search(r"MIN_FIXTURES\s*=\s*(\d+)", text)
    if not m:
        out.append(f"{REPLAY} declares no MIN_FIXTURES floor")
    elif int(m.group(1)) > fixtures:
        out.append(f"{REPLAY} expects at least {m.group(1)} fixtures, the directory has {fixtures}")
    return out


def self_test(root: pathlib.Path) -> int:
    real = root / FIXTURE_DIR
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        (t / ORACLE).parent.mkdir(parents=True)
        (t / ORACLE).write_text((root / ORACLE).read_text(encoding="utf-8"), encoding="utf-8")
        (t / REPLAY).parent.mkdir(parents=True)
        replay_text = (root / REPLAY).read_text(encoding="utf-8")
        (t / REPLAY).write_text(replay_text, encoding="utf-8")
        helper_text = (root / HELPER).read_text(encoding="utf-8")
        (t / HELPER).write_text(helper_text, encoding="utf-8")
        fdir = t / FIXTURE_DIR
        fdir.mkdir(parents=True)
        originals = {p.name: p.read_text(encoding="utf-8") for p in real.glob("*.json")}

        def reset():
            for p in fdir.glob("*.json"):
                p.unlink()
            for n, txt in originals.items():
                (fdir / n).write_text(txt, encoding="utf-8")

        def mutate(name, fn):
            fx = json.loads((fdir / name).read_text(encoding="utf-8"))
            fn(fx)
            (fdir / name).write_text(json.dumps(fx), encoding="utf-8")

        reset()
        rc, f, _ = run(t)
        cases.append(("known-negative: the shipped fixture set is clean", rc == 0 and not f))

        mutate("mm-placement-czk-holiday-001.json",
               lambda fx: fx["expected"]["flows"][1].__setitem__("amount", "140972.23"))
        rc, f, _ = run(t)
        cases.append(("known-positive: a hand-edited amount differs from the oracle", rc == 1 and any("differs from the oracle" in x for x in f)))

        reset()
        for p in fdir.glob("cnb-lombard-*.json"):
            p.unlink()
        rc, f, _ = run(t)
        cases.append(("known-positive: a product with zero fixtures fails", rc == 1 and any("product CNB_LOMBARD has no parity fixture" in x for x in f)))

        reset()
        (fdir / "copy.json").write_text(originals["mm-borrowing-czk-001.json"], encoding="utf-8")
        rc, f, _ = run(t)
        cases.append(("known-positive: a duplicate id fails", rc == 1 and any("duplicate id" in x for x in f)))

        reset()
        mutate("mm-borrowing-czk-001.json", lambda fx: fx["deal"].__setitem__("amount", 2500000.0))
        rc, f, _ = run(t)
        cases.append(("known-positive: a JSON float fails", rc == 1 and any("JSON number" in x for x in f)))

        reset()
        mutate("mm-borrowing-czk-001.json", lambda fx: fx["deal"].__setitem__("valueDate", "2026-13-02"))
        rc, f, _ = run(t)
        cases.append(("known-positive: a non-ISO date fails", rc == 1 and any("not an ISO date" in x for x in f)))

        reset()
        mutate("mm-borrowing-czk-001.json", lambda fx: (fx.__setitem__("product", "SWAPTION"), fx["deal"].__setitem__("product", "SWAPTION")))
        rc, f, _ = run(t)
        cases.append(("known-positive: a product outside the closed list fails", rc == 1 and any("not in the closed list" in x for x in f)))

        reset()
        mutate("mm-borrowing-czk-001.json", lambda fx: fx.pop("tolerance"))
        rc, f, _ = run(t)
        cases.append(("known-positive: a missing required key fails", rc == 1 and any("missing required key" in x for x in f)))

        reset()
        mutate("mm-placement-czk-holiday-001.json",
               lambda fx: (fx.__setitem__("source", "hand-computed"), fx["expected"]["flows"][1].__setitem__("amount", "140972.23")))
        rc, f, _ = run(t)
        cases.append(("known-negative: a hand-computed fixture is not re-derived by the oracle", rc == 0))

        reset()
        (t / REPLAY).unlink()
        rc, f, _ = run(t)
        cases.append(("known-positive: a missing replay test fails", rc == 1 and any("is missing" in x for x in f)))

        (t / REPLAY).write_text(replay_text.replace("class TreasuryParityReplayTest", "@Disabled\nclass TreasuryParityReplayTest"), encoding="utf-8")
        rc, f, _ = run(t)
        cases.append(("known-positive: a @Disabled replay test fails", rc == 1 and any("@Disabled" in x for x in f)))
        (t / REPLAY).write_text(replay_text, encoding="utf-8")

        (t / HELPER).write_text(helper_text.replace('getResource("/parity")', 'getResource("/parity/irs")'), encoding="utf-8")
        rc, f, _ = run(t)
        cases.append(("known-positive: a replay narrowed to a sub-directory fails", rc == 1 and any("whole /parity" in x for x in f)))
        (t / HELPER).write_text(helper_text, encoding="utf-8")

        for p in fdir.glob("*.json"):
            p.unlink()
        fdir.rmdir()
        rc, f, _ = run(t)
        cases.append(("cannot-run: a missing fixture directory is exit 2, not clean", rc == 2))

    failed = 0
    for label, ok in cases:
        print(f"{'PASS' if ok else 'FAIL'}  {label}")
        failed += 0 if ok else 1
    print(f"self-test: {len(cases) - failed}/{len(cases)} passed")
    return 1 if failed else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    root = pathlib.Path(args.root).resolve()
    if args.self_test:
        return self_test(root)
    rc, findings, n = run(root)
    print(f"SUBJECTS={n}  # treasury parity fixtures")
    for f in findings:
        print(f"::error::{f}")
    print(f"{n} fixture(s), {len(findings)} finding(s)")
    return rc


if __name__ == "__main__":
    sys.exit(main())
