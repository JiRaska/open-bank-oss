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
    r"%\s*\(?[A-Za-z_]*97\b|\b(?:MOD|MODULUS)_?97\b|"
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
ORACLE = pathlib.Path(".github/scripts/identifier-differential.py")


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
        # the reference must be CALLED inside an assertion: a file that merely mentions it, or
        # computes it and never compares, is consistency-only
        semantic = re.search(r"\b(?:check|require|assert\w*)\s*\([^\n]*\b\w+Reference\.\w+\(", text) is not None
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
    # the all-zero property: every identifier the oracle judges must state whether an all-zero payload is
    # rejected or exempt (with a reason); the policy is parsed from the oracle script, not re-listed here
    oracle_text = (root / ORACLE).read_text(encoding="utf-8") if (root / ORACLE).is_file() else None
    if oracle_text is not None:
        for name, kinds, what in (
            ("ZERO_POLICY", "reject|exempt", "all-zero payload: reject, or exempt with a reason"),
            ("DATE_POLICY", "future-reject|no-date", "embedded date: future-reject, or no-date with a reason"),
        ):
            block = re.search(r"^" + name + r" = \{(.*?)^\}", oracle_text, re.S | re.M)
            policy = dict(re.findall(r'^\s+"([a-z0-9-]+)":\s*\("(' + kinds + r')"', block.group(1), re.M)) if block else {}
            for v in vectors:
                if v.get("differential") and v["identifier"] not in policy:
                    problems.append(f"{v['identifier']}: no {name} entry in {ORACLE} ({what}): the property must "
                                    f"cover every identifier")
    baseline = json.loads((root / BASELINE).read_text()) if (root / BASELINE).is_file() else {}
    adhoc = {f for cat in baseline.values() for f in cat}
    classified = _classified(root / CLASSIFIED) if (root / CLASSIFIED).is_file() else {}
    fuzz = fuzz_oracles(root)
    claimed = {}
    for v in vectors:
        # every value is synthetic or published-with-a-source: the gitleaks allowlist covers test files,
        # so this is the check that a vector file cannot quietly carry a real person's or firm's number
        for c in v["cases"]:
            where = f"{v['identifier']} {c.get('value')!r}"
            if c.get("origin") not in ("synthetic", "published"):
                problems.append(f"{where}: origin must be 'synthetic' or 'published'")
            elif c["origin"] == "published" and not str(c.get("cite", "")).startswith("https://"):
                problems.append(f"{where}: a published value needs an https `cite`")
            if c.get("policy") not in ("spec", "local") or (c.get("policy") == "local" and not c.get("policy_reason")):
                problems.append(f"{where}: policy must be 'spec', or 'local' with a policy_reason")
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
            else:
                # the vector's declared implementation must be what the test's validator map invokes:
                # EVERY identifier-like token of the symbol must appear in that entry (or in the
                # helper it points at with `::name`), so a SK vector cannot ride on a CZ lambda
                src = test[0].read_text(encoding="utf-8")
                line = re.search(r'"' + re.escape(v["identifier"]) + r'"\s+to\s+([^\n]*)', src)
                if not line:
                    problems.append(f"{v['identifier']}: {test[0].name} has no validator entry for it")
                else:
                    body = line.group(1)
                    helper = re.search(r"::(\w+)", body)
                    if helper:
                        hm = re.search(r"fun " + helper.group(1) + r"\b.*?\n        }\n", src, re.S)
                        body += hm.group(0) if hm else ""
                    key = [t for t in re.findall(r"[A-Za-z0-9_]+", impl["symbol"]) if t[:1].isupper()]
                    missing = [k for k in key if k not in body]
                    if missing:
                        problems.append(f"{v['identifier']}: vector names {impl['symbol']!r} but the test's lambda "
                                        f"{line.group(1).strip()!r} does not invoke {missing}")
            claimed.setdefault(f, []).append(v["identifier"])
            for extra in impl.get("also_files", []):
                if not (root / extra).is_file():
                    problems.append(f"{v['identifier']}: also_files entry {extra!r} does not exist")
                claimed.setdefault(extra, []).append(v["identifier"])
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
                "status": "declared" if not defects else "known-defect",
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


def _junit(path: pathlib.Path):
    """(tests, skipped, failures+errors, text) of one JUnit XML, or None."""
    text = path.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', text)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)) + int(m.group(4)), text) if m else None


def _verdict(parsed) -> str:
    """pass only for a run that executed tests, skipped none and failed none; anything else is not a pass."""
    if parsed is None:
        return "not-run"
    tests, skipped, failed, _ = parsed
    if failed:
        return "fail"
    return "pass" if tests > 0 and skipped == 0 else "invalid"


def attach_results(root: pathlib.Path, rows: list, run_url: str | None) -> None:
    """Fold the conformance tests' MEASURED results in. Absence of a result is `not-run`, never
    `pass`; a run with zero tests or any skipped test is `invalid`, never `pass`. The status,
    pitest and fuzz columns are derived from results here, not from configuration: a validator
    is `conformance-tested` only when its vectors (and its differential, where a reference
    exists) passed in this run (ADR-0273: missing evidence never reads green)."""
    import datetime
    now = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    fuzz_xml = list((root / FUZZ.parent.parent / "build/test-results/test").glob("TEST-*Reference*Test.xml"))
    fuzz_verdict = _verdict(_junit(fuzz_xml[0])) if fuzz_xml else "not-run"
    for r in rows:
        r["result"] = {"vectors": "not-run", "differential": "not-run", "run": run_url, "at": None,
                       "fuzz": "not-run", "pitest": "not-run"}
        if r["status"] == "unverified-ad-hoc-copy":
            continue
        xml = list((root / r["module"]).glob("build/test-results/test/TEST-*IdentifierSpecConformanceTest.xml"))
        parsed = _junit(xml[0]) if xml else None
        r["result"]["vectors"] = _verdict(parsed)
        if r["differential"]:
            ran = re.search(r"IDENTIFIER_DIFFERENTIAL module=\S+ rows=(\d+) disagreements=0", parsed[3]) if parsed else None
            r["result"]["differential"] = ("fail" if r["result"]["vectors"] == "fail"
                                           else "pass" if ran and r["result"]["vectors"] == "pass" else "not-run")
            if ran:
                r["result"]["rows"] = int(ran.group(1))
        if r["fuzz"]["label"] == "semantic":
            r["result"]["fuzz"] = fuzz_verdict
        mut = root / r["module"] / "build/reports/pitest/mutations.xml"
        if mut.is_file():
            cls = (r["symbol"] or "").split("(")[0].rsplit(".", 1)[0]
            tags = re.findall(r"<mutation [^>]*status='(\w+)'[^>]*>(?:(?!</mutation>).)*?<mutatedClass>" + re.escape(cls) +
                              r"</mutatedClass>", mut.read_text(encoding="utf-8", errors="replace"), re.S)
            r["result"]["pitest"] = (f"{sum(t == 'KILLED' for t in tags)}/{len(tags)} killed" if tags else "not-run")
        done = r["result"]["vectors"] == "pass" and r["result"]["differential"] in ("pass", "not-run") \
            and (not r["differential"] or r["result"]["differential"] == "pass")
        if parsed is not None:
            r["result"]["at"] = now
        if r["status"] == "declared":
            r["status"] = ("conformance-tested" if done else
                           "failed" if "fail" in (r["result"]["vectors"], r["result"]["differential"]) else "not-measured")


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
    tested = sum(r["status"] in ("conformance-tested", "declared") for r in rows)
    print(f"identifier inventory: {n} candidate file(s); {tested} vectored validator(s)"
          f"{'' if results else ' (declared, not measured)'}, "
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
        test.write_text('class IdentifierSpecConformanceTest {\n    private val validators = mapOf(\n        "x" to V::ok,\n    )\n}\n')
        (root / VECTORS).mkdir(parents=True)
        (root / VECTORS / "x.json").write_text(json.dumps({"identifier": "x", "cases": [{"value": "1", "valid": True, "origin": "synthetic", "policy": "spec"}],
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
        # 4. the vector's implementation must be what the test's lambda invokes
        test.write_text('class IdentifierSpecConformanceTest {\n    private val validators = mapOf(\n        "x" to Other::ok,\n    )\n}\n')
        ok &= run(root, None) == 1
        test.write_text('class IdentifierSpecConformanceTest {\n    private val validators = mapOf(\n        "x" to V::ok,\n    )\n}\n')
        ok &= run(root, None) == 0
        # the all-zero property: a differential identifier with no ZERO_POLICY entry is refused
        (root / ORACLE).parent.mkdir(parents=True, exist_ok=True)
        (root / ORACLE).write_text('ZERO_POLICY = {\n    "x": ("exempt", "r"),\n}\nDATE_POLICY = {\n    "x": ("no-date", "r"),\n}\n')
        (root / VECTORS / "x.json").write_text(
            (root / VECTORS / "x.json").read_text().replace('"cases"', '"differential": true, "cases"', 1))
        ok &= run(root, None) == 0
        (root / ORACLE).write_text('ZERO_POLICY = {\n    "y": ("exempt", "r"),\n}\nDATE_POLICY = {\n    "x": ("no-date", "r"),\n}\n')
        ok &= run(root, None) == 1
        # a date policy is required as well: zero policy present, date policy missing
        (root / ORACLE).write_text('ZERO_POLICY = {\n    "x": ("exempt", "r"),\n}\nDATE_POLICY = {\n    "y": ("no-date", "r"),\n}\n')
        ok &= run(root, None) == 1
        (root / ORACLE).unlink()
        (root / VECTORS / "x.json").write_text(
            (root / VECTORS / "x.json").read_text().replace('"differential": true, ', "", 1))
        # 17. a vector with no origin, or a published one with no source, is refused
        good = (root / VECTORS / "x.json").read_text()
        (root / VECTORS / "x.json").write_text(good.replace('"origin": "synthetic", ', ""))
        ok &= run(root, None) == 1
        (root / VECTORS / "x.json").write_text(good.replace('"synthetic"', '"published"'))
        ok &= run(root, None) == 1
        (root / VECTORS / "x.json").write_text(good)
        # 3. a fuzzer is `semantic` only when the reference is called inside an assertion
        fz = root / FUZZ
        fz.mkdir(parents=True)
        (fz / "AFuzzer.kt").write_text("import com.openbank.x.V\nobject AFuzzer { fun f(s: String) { val r = VReference.ok(s); println(r) } }\n")
        (fz / "BFuzzer.kt").write_text("import com.openbank.x.W\nobject BFuzzer { fun f(s: String) { check(W.ok(s) == WReference.ok(s)) { s } } }\n")
        labels = fuzz_oracles(root)
        ok &= labels.get("V", {}).get("label") == "robustness-only" and labels.get("W", {}).get("label") == "semantic"
        # 6. a run that executed no test, or skipped one, is never a pass
        def x(t, s, f):
            q = root / "r.xml"
            q.write_text(f'<testsuite tests="{t}" skipped="{s}" failures="{f}" errors="0">')
            return _verdict(_junit(q))
        ok &= (x(5, 0, 0), x(0, 0, 0), x(5, 1, 0), x(5, 0, 1)) == ("pass", "invalid", "invalid", "fail")
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
