#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Derived inventory of identifier validators, and their spec-conformance matrix (#16748).
#
# The inventory is NOT a hand list. It is derived from code: every Kotlin main source that
# carries the shape of an identifier check (a mod-97 / mod-11 / Luhn computation, an IBAN,
# ISIN or `[A-Z]{3}` currency regex, `Currency.getInstance`) is a CANDIDATE, and each candidate
# must be accounted for in exactly one way:
#
#   implementation  named by a spec vector file (identifier-spec-vectors/*.json
#                   `implementations[].file`), whose module carries IdentifierSpecConformanceTest;
#   ad-hoc-copy     a hand-rolled duplicate still in the shrink-only ratchet baseline
#                   (.github/gates/identifier-validator-baseline.json) — consolidation pending,
#                   reported as UNVERIFIED in the matrix, never as conformant;
#   classified      listed in .github/gates/identifier-validator-inventory.yaml with a class
#                   (generator / masking / consumer / harness / not-identifier) and a reason.
#
# A new validator therefore fails until it has vectors or a stated reason; a classification
# whose file stopped looking like a validator fails as stale.
#
# --matrix <out.json> also writes the conformance matrix Test Intelligence renders:
# validator x {vectors, differential, fuzz oracle, pitest}. Fuzzers are labelled
# `semantic` only when they compare against an independent *Reference; otherwise
# `robustness-only`, so a consistency fuzzer can never read as conformance evidence.
#
# EXIT 0 every candidate accounted for · 1 a finding · 2 could not run / self-test failed

import argparse
import json
import pathlib
import re
import sys
import tempfile

CANDIDATE = re.compile(
    r"(?<![0-9])97\s*\.toBigInteger|BigInteger\.valueOf\(\s*97\s*\)|%\s*97(?![0-9])|\.mod\(\s*97(?![0-9])|"
    r"%\s*11(?![0-9])|\.mod\(\s*11(?![0-9])|(?i:luhn)|mod97|mod11|"
    r"\[A-Z\]\{2\}(?:\[0-9\]|\\\\d)\{2\}|\[A-Z\]\{2\}\[A-Z0-9\]\{9\}|\[A-Z\]\{3\}|"
    r"Currency\.getInstance|getAvailableCurrencies"
)
VECTORS = pathlib.Path("openbank-libs-domain/src/test/resources/identifier-spec-vectors")
BASELINE = pathlib.Path(".github/gates/identifier-validator-baseline.json")
CLASSIFIED = pathlib.Path(".github/gates/identifier-validator-inventory.yaml")
CLASSES = {"generator", "masking", "consumer", "harness", "not-identifier"}
FUZZ = pathlib.Path("fuzz/ossfuzz/src/main/kotlin")
PITEST_LIBS = pathlib.Path(".github/scripts/pitest-libs-advisory.init.gradle")
PITEST_WF = pathlib.Path(".github/workflows/pitest.yml")


def _classified(path: pathlib.Path) -> dict:
    """Minimal parser for the flat `- file: … / class: … / reason: …` list (no PyYAML needed)."""
    out, cur = {}, None
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*-\s*file:\s*(\S+)", line)
        if m:
            cur = out.setdefault(m.group(1), {})
            continue
        m = re.match(r"\s+(class|reason):\s*(.+)", line)
        if m and cur is not None:
            cur[m.group(1)] = m.group(2).strip().strip('"')
    return out


def candidates(root: pathlib.Path):
    found = []
    for p in sorted(root.rglob("src/main/**/*.kt")):
        rel = p.relative_to(root).as_posix()
        if "/build/" in rel or rel.startswith("node_modules"):
            continue
        if CANDIDATE.search(p.read_text(encoding="utf-8", errors="replace")):
            found.append(rel)
    return found


def load_vectors(root: pathlib.Path):
    return [json.loads(f.read_text(encoding="utf-8")) for f in sorted((root / VECTORS).glob("*.json"))]


def fuzz_oracles(root: pathlib.Path):
    """validator simple name -> fuzz label, derived from the fuzz targets' own source."""
    labels = {}
    base = root / FUZZ
    if not base.is_dir():
        return labels
    for f in base.rglob("*Fuzzer.kt"):
        text = f.read_text(encoding="utf-8")
        semantic = re.search(r"\b\w+Reference\.\w+\(", text) is not None
        for imp in re.findall(r"^import com\.openbank\.[\w.]+\.(\w+)$", text, re.M):
            labels[imp] = {"fuzzer": f.stem, "label": "semantic" if semantic else "robustness-only"}
    return labels


def pitest_for(root: pathlib.Path, module: str, symbol: str) -> str:
    if module.startswith("openbank-libs-"):
        text = (root / PITEST_LIBS).read_text(encoding="utf-8") if (root / PITEST_LIBS).is_file() else ""
        for glob in re.findall(r"'(com\.openbank\.libs\.[\w.]+)\.\*'", text):
            if symbol.startswith(glob + "."):
                return "advisory"
        return "none"
    wf = (root / PITEST_WF).read_text(encoding="utf-8") if (root / PITEST_WF).is_file() else ""
    return "service-lane" if module in wf else "none"


def build(root: pathlib.Path):
    problems, rows = [], []
    vectors = load_vectors(root)
    baseline = json.loads((root / BASELINE).read_text()) if (root / BASELINE).is_file() else {}
    adhoc = {f for cat in baseline.values() for f in cat}
    classified = _classified(root / CLASSIFIED) if (root / CLASSIFIED).is_file() else {}
    fuzz = fuzz_oracles(root)
    claimed = {}
    for v in vectors:
        if not v.get("implementations"):
            problems.append(f"vector file {v['identifier']} names no implementation")
        for impl in v.get("implementations", []):
            f = impl.get("file", "")
            if not (root / f).is_file():
                problems.append(f"{v['identifier']}: implementation file {f!r} does not exist")
                continue
            test = list((root / impl["module"]).rglob("src/test/**/IdentifierSpecConformanceTest.kt"))
            if not test:
                problems.append(f"{v['identifier']}: {impl['module']} has no IdentifierSpecConformanceTest")
            claimed.setdefault(f, []).append(v["identifier"])
            simple = impl["symbol"].split("(")[0].split(".")[-2] if "." in impl["symbol"] else impl["symbol"]
            defects = sorted({c["known_defect"]["issue"] for c in v["cases"] if c.get("known_defect")})
            rows.append({
                "identifier": v["identifier"],
                "module": impl["module"],
                "symbol": impl["symbol"],
                "file": f,
                "vectors": len(v["cases"]),
                "sources": v.get("sources", []),
                "reference": v.get("reference"),
                "differential": bool(v.get("differential")),
                "known_defects": defects,
                "fuzz": fuzz.get(simple, {"fuzzer": None, "label": "none"}),
                "pitest": pitest_for(root, impl["module"], impl["symbol"]),
                "status": "conformance-tested" if not defects else "known-defect",
            })
    cands = candidates(root)
    for f in cands:
        if f in claimed:
            continue
        c = classified.get(f)
        if f in adhoc and c is None:
            rows.append({"identifier": None, "module": f.split("/")[0], "symbol": None, "file": f,
                         "vectors": 0, "differential": False, "known_defects": [],
                         "fuzz": {"fuzzer": None, "label": "none"}, "pitest": "none",
                         "status": "unverified-ad-hoc-copy"})
            continue
        if c is None:
            problems.append(f"UNACCOUNTED {f}: looks like an identifier validator but has no spec vectors, "
                            f"is not a baselined ad-hoc copy, and is not classified in {CLASSIFIED}")
        elif c.get("class") not in CLASSES or not c.get("reason"):
            problems.append(f"{f}: classification needs class in {sorted(CLASSES)} and a reason")
    for f in classified:
        if f not in cands:
            problems.append(f"STALE classification {f}: no longer matches a validator shape — remove it")
    return problems, rows, len(cands)


def attach_results(root: pathlib.Path, rows: list, run_url: str | None) -> None:
    """Fold the conformance tests' JUnit results in: per module, did the vectors pass and did the
    differential run (its IDENTIFIER_DIFFERENTIAL line) with zero disagreements. Absence of a
    result is `not-run`, never `pass`."""
    import datetime
    now = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    for r in rows:
        r.setdefault("result", {"vectors": "not-run", "differential": "not-run", "run": run_url, "at": None})
        xml = list((root / r["module"]).glob("build/test-results/test/TEST-*IdentifierSpecConformanceTest.xml"))
        if not xml or r["status"] == "unverified-ad-hoc-copy":
            continue
        text = xml[0].read_text(encoding="utf-8", errors="replace")
        m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', text)
        failed = not m or int(m.group(3)) + int(m.group(4)) > 0
        r["result"]["vectors"] = "fail" if failed else "pass"
        if r["differential"]:
            ran = re.search(r"IDENTIFIER_DIFFERENTIAL module=\S+ rows=(\d+) disagreements=0", text)
            r["result"]["differential"] = "fail" if failed else ("pass" if ran else "not-run")
            if ran:
                r["result"]["rows"] = int(ran.group(1))
        r["result"]["at"] = now


def run(root: pathlib.Path, matrix: pathlib.Path | None, results: bool = False, run_url: str | None = None) -> int:
    problems, rows, n = build(root)
    if results:
        attach_results(root, rows, run_url)
    if n == 0:
        print("::error::no candidate sources found — wrong --root?")
        return 2
    for p in problems:
        print(f"::error::{p}")
    if matrix:
        matrix.write_text(json.dumps({"schema": "identifier-conformance/v1", "validators": rows}, indent=2) + "\n")
    tested = sum(r["status"] == "conformance-tested" for r in rows)
    print(f"identifier inventory: {n} candidate file(s); {tested} conformance-tested validator(s), "
          f"{sum(r['status'] == 'known-defect' for r in rows)} with known defects, "
          f"{sum(r['status'] == 'unverified-ad-hoc-copy' for r in rows)} unverified ad-hoc copies; {len(problems)} problem(s)")
    print(f"SUBJECTS={n}  # candidate validator sources derived from code")
    return 1 if problems else 0


def self_test() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        impl = root / "openbank-x/src/main/kotlin/V.kt"
        impl.parent.mkdir(parents=True)
        impl.write_text("fun ok(s: String) = s.toBigInteger().mod(97.toBigInteger())\n")
        test = root / "openbank-x/src/test/kotlin/IdentifierSpecConformanceTest.kt"
        test.parent.mkdir(parents=True)
        test.write_text("class IdentifierSpecConformanceTest\n")
        (root / VECTORS).mkdir(parents=True)
        (root / VECTORS / "x.json").write_text(json.dumps({"identifier": "x", "cases": [{"value": "1", "valid": True}],
            "implementations": [{"module": "openbank-x", "symbol": "a.V.ok", "file": "openbank-x/src/main/kotlin/V.kt"}]}))
        (root / BASELINE).parent.mkdir(parents=True)
        (root / BASELINE).write_text("{}")
        (root / CLASSIFIED).write_text("validators: []\n")
        ok &= run(root, None) == 0
        new = root / "openbank-y/src/main/kotlin/W.kt"
        new.parent.mkdir(parents=True)
        new.write_text('val C = Regex("[A-Z]{3}")\n')
        ok &= run(root, None) == 1                      # new validator, no vectors
        (root / CLASSIFIED).write_text("- file: openbank-y/src/main/kotlin/W.kt\n  class: masking\n  reason: r\n")
        ok &= run(root, None) == 0                      # classified with a reason
        new.write_text("val nothing = 1\n")
        ok &= run(root, None) == 1                      # stale classification
        (root / CLASSIFIED).write_text("validators: []\n")
        test.unlink()
        ok &= run(root, None) == 1                      # implementation module lost its test
    print("self-test", "ok" if ok else "FAILED")
    return 0 if ok else 2


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--matrix", type=pathlib.Path)
    ap.add_argument("--with-results", action="store_true", help="fold in IdentifierSpecConformanceTest JUnit results")
    ap.add_argument("--run-url")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return run(pathlib.Path(a.root), a.matrix, a.with_results, a.run_url)


if __name__ == "__main__":
    sys.exit(main())
