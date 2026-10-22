#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""ADR-0352 §8 `product-ir-determinism`: every golden scenario runs twice in one JVM and produces
byte-identical canonical instruction lists; the set is then compared across every supported
`irVersion` against the documented equivalence table.

TWO HALVES, AND WHERE EACH RUNS
    The JVM half is `GoldenDeterminismTest` in openbank-libs-testing (`:openbank-libs-testing:test`,
    run by the module's CI lane): it loads every scenario the committed index names, evaluates it
    twice through the one pure interpreter, and asserts the canonical bytes — hash included — are
    identical. This script cannot run a JVM inside the gate budget, so it holds the STATIC
    invariants that make that test mean what it says, and (with --junit-xml) reads its verdict:

      * every scenario file under product-golden/ is listed in index.txt and vice versa — a scenario
        the index does not name is one the determinism test never opened;
      * every scenario parses as JSON and its revision's irVersion is a supported version;
      * GoldenDeterminismTest exists, is not @Disabled, and the module's test task does not exclude it;
      * with --junit-xml <dir>: the test's JUnit report lists one PASSED dynamic test per scenario.

    CROSS-irVersion HALF: with exactly one supported version there is nothing to compare, and this
    script SAYS SO on every run (`cross-irVersion equivalence: NO-OP (one supported irVersion)`)
    instead of passing silently. The day a second version ships, the equivalence table and its
    comparison are added here and the no-op line disappears.

SCOPE is derived from the filesystem (every *.json under a product-golden/ directory) and from
IrVersion.SUPPORTED in the Kotlin source. Prints SUBJECTS=<n scenarios>.

MODES: advisory (::warning, exit 0) unless --enforce. rules.yaml: product_ir_determinism.

Usage: check-product-ir-determinism.py [--root DIR] [--junit-xml DIR] [--enforce] | --self-test
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import pathlib
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

GOLDEN_DIR_NAME = "product-golden"
INDEX = "index.txt"
TEST_CLASS = "GoldenDeterminismTest"
TEST_FILE = f"openbank-libs-testing/src/test/kotlin/com/openbank/libs/testing/product/{TEST_CLASS}.kt"
TESTING_BUILD = "openbank-libs-testing/build.gradle.kts"
IR_VERSION_SOURCE = "openbank-libs-domain/src/main/kotlin/com/openbank/libs/product/ir/ProductIr.kt"
SKIP_DIRS = {"build", "node_modules", ".git", ".gradle"}
SUPPORTED_RE = re.compile(r"val SUPPORTED:\s*Set<Int>\s*=\s*setOf\(([^)]*)\)")


def golden_dirs(root: pathlib.Path) -> list[pathlib.Path]:
    out = []
    for d in sorted(root.rglob(GOLDEN_DIR_NAME)):
        if d.is_dir() and not any(p in SKIP_DIRS for p in d.relative_to(root).parts):
            out.append(d)
    return out


def supported_versions(root: pathlib.Path) -> set[int] | None:
    src = root / IR_VERSION_SOURCE
    if not src.is_file():
        return None
    m = SUPPORTED_RE.search(src.read_text(encoding="utf-8"))
    if not m:
        return None
    text = src.read_text(encoding="utf-8")
    consts = {k: int(v) for k, v in re.findall(r"const val (\w+): Int = (\d+)", text)}
    out: set[int] = set()
    for tok in (x.strip() for x in m.group(1).split(",") if x.strip()):
        if tok.isdigit():
            out.add(int(tok))
        elif tok in consts:
            out.add(consts[tok])
        else:
            return None
    return out


def static_findings(root: pathlib.Path) -> tuple[list[str], list[str], list[str]]:
    """Return (findings, notices, scenario names)."""
    findings: list[str] = []
    notices: list[str] = []
    names: list[str] = []
    supported = supported_versions(root)
    if supported is None:
        findings.append(f"{IR_VERSION_SOURCE}: cannot read IrVersion.SUPPORTED — the cross-version half has no input")
    elif len(supported) == 1:
        notices.append(f"cross-irVersion equivalence: NO-OP (one supported irVersion: {sorted(supported)}) — nothing to compare until a second version ships")
    else:
        findings.append(f"{IR_VERSION_SOURCE}: {len(supported)} supported irVersions but this script carries no equivalence table — add it before a second version ships")
    dirs = golden_dirs(root)
    if not dirs:
        findings.append(f"no {GOLDEN_DIR_NAME}/ directory found — the determinism test has nothing to run")
    for d in dirs:
        rel = d.relative_to(root)
        files = sorted(p.name for p in d.glob("*.json"))
        index = d / INDEX
        if not index.is_file():
            findings.append(f"{rel}: no {INDEX} — the determinism test discovers scenarios through it")
            listed: list[str] = []
        else:
            listed = [ln.strip() for ln in index.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.strip().startswith("#")]
        for f in files:
            if f not in listed:
                findings.append(f"{rel}/{f}: scenario file not listed in {INDEX} — the determinism test never opens it")
        for f in listed:
            if f not in files:
                findings.append(f"{rel}/{INDEX}: lists {f!r}, which does not exist")
        for f in files:
            try:
                doc = json.loads((d / f).read_text(encoding="utf-8"))
            except ValueError as e:
                findings.append(f"{rel}/{f}: not valid JSON ({e})")
                continue
            rev = doc.get("revision") if isinstance(doc, dict) else None
            if not isinstance(rev, dict) or "steps" not in doc:
                findings.append(f"{rel}/{f}: not a golden scenario (needs 'revision' and 'steps')")
                continue
            if supported is not None and rev.get("irVersion") not in supported:
                findings.append(f"{rel}/{f}: revision irVersion {rev.get('irVersion')!r} is not a supported version {sorted(supported)}")
            if not isinstance(doc.get("name"), str) or doc["name"] != f.removesuffix(".json"):
                findings.append(f"{rel}/{f}: 'name' must equal the file name without .json (it is the test id)")
            names.append(f)
    test = root / TEST_FILE
    if not test.is_file():
        findings.append(f"{TEST_FILE}: missing — the JVM half of this gate does not exist")
    else:
        text = test.read_text(encoding="utf-8")
        if "@Disabled" in text:
            findings.append(f"{TEST_FILE}: carries @Disabled — the JVM half is switched off")
        if "GoldenScenarios.names()" not in text:
            findings.append(f"{TEST_FILE}: does not iterate GoldenScenarios.names() — its scope is not the committed index")
    build = root / TESTING_BUILD
    if build.is_file() and re.search(rf"exclude[^\n]*{TEST_CLASS}", build.read_text(encoding="utf-8")):
        findings.append(f"{TESTING_BUILD}: excludes {TEST_CLASS} from the test task")
    return findings, notices, names


def junit_findings(junit_dir: pathlib.Path, names: list[str]) -> list[str]:
    findings: list[str] = []
    reports = sorted(junit_dir.rglob(f"TEST-*{TEST_CLASS}.xml"))
    if not reports:
        return [f"{junit_dir}: no JUnit report for {TEST_CLASS} — the JVM half did not run"]
    passed: set[str] = set()
    for rep in reports:
        for tc in ET.parse(rep).getroot().iter("testcase"):
            name = tc.get("name", "")
            if tc.find("failure") is None and tc.find("error") is None and tc.find("skipped") is None:
                passed.add(name)
            else:
                findings.append(f"{rep.name}: {name} did not pass")
    for n in names:
        if n not in passed:
            findings.append(f"{TEST_CLASS}: no passed test case for scenario {n}")
    return findings


def run(root: pathlib.Path, junit: pathlib.Path | None, enforce: bool) -> int:
    findings, notices, names = static_findings(root)
    print(f"SUBJECTS={len(names)}")
    if junit is not None:
        findings += junit_findings(junit, names)
    for n in notices:
        print(f"::notice::{n}")
    for f in findings:
        print(f"::{'error' if enforce else 'warning'}::{f}")
    if not names and not findings:
        findings.append("no golden scenario found — refusing to report a pass")
        print(f"::error::{findings[-1]}")
        return 1
    if findings:
        print(f"product-ir-determinism: {len(findings)} finding(s)" + ("" if enforce else " — ADVISORY mode, not failing the build"))
        return 1 if enforce else 0
    print(f"product-ir-determinism: OK — {len(names)} scenario(s) indexed and well-formed, {TEST_CLASS} present" + (", JUnit verdict green" if junit else ""))
    return 0


# --------------------------------------------------------------------------------------------
# Self-test: negatives first.
# --------------------------------------------------------------------------------------------

def _fixture(root: pathlib.Path, scenarios: dict[str, dict], index: list[str] | None, supported: str = "V1", test_text: str | None = None) -> None:
    g = root / "openbank-libs-testing/src/main/resources" / GOLDEN_DIR_NAME
    g.mkdir(parents=True)
    for name, doc in scenarios.items():
        (g / name).write_text(json.dumps(doc), encoding="utf-8")
    if index is not None:
        (g / INDEX).write_text("\n".join(index) + "\n", encoding="utf-8")
    src = root / IR_VERSION_SOURCE
    src.parent.mkdir(parents=True)
    src.write_text(f"object IrVersion {{ const val V1: Int = 1\n const val V2: Int = 2\n val SUPPORTED: Set<Int> = setOf({supported}) }}\n", encoding="utf-8")
    t = root / TEST_FILE
    t.parent.mkdir(parents=True)
    t.write_text(test_text if test_text is not None else "class GoldenDeterminismTest { fun x() = GoldenScenarios.names().map { it } }\n", encoding="utf-8")
    (root / TESTING_BUILD).parent.mkdir(parents=True, exist_ok=True)
    (root / TESTING_BUILD).write_text("tasks.test { useJUnitPlatform() }\n", encoding="utf-8")


def _scenario(name: str, version: int = 1) -> dict:
    return {"name": name.removesuffix(".json"), "revision": {"irVersion": version, "hooks": {}}, "state": {}, "steps": []}


def _junit(root: pathlib.Path, cases: dict[str, bool]) -> pathlib.Path:
    d = root / "junit"
    d.mkdir()
    body = "".join(
        f'<testcase name="{n}" classname="x.{TEST_CLASS}" time="0.1">{"" if ok else "<failure message=\"boom\"/>"}</testcase>'
        for n, ok in cases.items()
    )
    (d / f"TEST-x.{TEST_CLASS}.xml").write_text(f'<testsuite name="x.{TEST_CLASS}" tests="{len(cases)}">{body}</testsuite>', encoding="utf-8")
    return d


def self_test() -> int:
    ok = True

    def case(label, want_exit, text, scenarios, index, junit_cases=None, **kw):
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _fixture(root, scenarios, index, **kw)
            junit = _junit(root, junit_cases) if junit_cases is not None else None
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                got = run(root, junit, enforce=True)
            good = got == want_exit and text in buf.getvalue()
            ok &= good
            print(f"  [{'ok' if good else 'WRONG'}] {label}: exit {got} (want {want_exit})")
            if not good:
                print(buf.getvalue())

    a, b = "a.json", "b.json"
    case("a scenario file missing from the index is red", 1, "b.json: scenario file not listed", {a: _scenario(a), b: _scenario(b)}, [a])
    case("an index entry with no file is red", 1, "lists 'ghost.json', which does not exist", {a: _scenario(a)}, [a, "ghost.json"])
    case("no index at all is red", 1, "no index.txt", {a: _scenario(a)}, None)
    case("an unsupported irVersion in a scenario is red", 1, "irVersion 2 is not a supported version [1]", {a: _scenario(a, 2)}, [a])
    case("a name that is not the file name is red", 1, "'name' must equal the file name", {a: {**_scenario(a), "name": "other"}}, [a])
    case("a @Disabled determinism test is red", 1, "carries @Disabled", {a: _scenario(a)}, [a], test_text="@Disabled class GoldenDeterminismTest { val n = GoldenScenarios.names() }")
    case("a determinism test not scoped to the index is red", 1, "does not iterate GoldenScenarios.names()", {a: _scenario(a)}, [a], test_text="class GoldenDeterminismTest { fun x() = 1 }")
    case("two supported versions without an equivalence table is red", 1, "2 supported irVersions but this script carries no equivalence table", {a: _scenario(a)}, [a], supported="V1, V2")
    case("a failed JUnit case is red", 1, "a.json did not pass", {a: _scenario(a)}, [a], junit_cases={a: False})
    case("a scenario absent from the JUnit report is red", 1, "no passed test case for scenario b.json", {a: _scenario(a), b: _scenario(b)}, [a, b], junit_cases={a: True})
    case("green static run says the cross-version half is a no-op", 0, "cross-irVersion equivalence: NO-OP", {a: _scenario(a), b: _scenario(b)}, [a, b])
    case("green static run reports SUBJECTS=2", 0, "SUBJECTS=2", {a: _scenario(a), b: _scenario(b)}, [a, b])
    case("green JUnit run is green", 0, "JUnit verdict green", {a: _scenario(a)}, [a], junit_cases={a: True, "one supported irVersion makes the cross-version half a no-op()": True})

    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        _fixture(root, {a: _scenario(a), b: _scenario(b)}, [a])
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got = run(root, None, enforce=False)
        good = got == 0 and "::warning::" in buf.getvalue() and "not listed" in buf.getvalue()
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] advisory mode prints the finding and exits 0")
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        _fixture(root, {}, [])
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got = run(root, None, enforce=False)
        good = got == 1 and "no golden scenario found" in buf.getvalue()
        ok &= good
        print(f"  [{'ok' if good else 'WRONG'}] an empty golden set is red even in advisory mode")

    print("self-test " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--junit-xml", help="directory holding the openbank-libs-testing JUnit XML reports")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    junit = pathlib.Path(args.junit_xml) if args.junit_xml else None
    if junit is not None and not junit.is_dir():
        print(f"::error::{junit} is not a directory — the JVM half produced no report; refusing to report a pass")
        return 1
    return run(pathlib.Path(args.root).resolve(), junit, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
