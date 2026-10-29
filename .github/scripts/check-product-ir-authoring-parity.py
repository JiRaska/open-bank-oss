#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""ADR-0352 §5/§11 `product-ir-authoring-parity`: the committed IR JSON under openbank-products/ir/
is byte-for-byte the canonical form of the DSL product that claims to author it.

TWO HALVES, AND WHERE EACH RUNS
    The JVM half is `ReferenceProductsTest` in openbank-products (`:openbank-products:test`, the
    module's CI lane): for every `ReferenceProducts.all` entry it compiles the DSL, canonicalises the
    result and asserts equality with `ir/<name>.json`; it also re-parses the committed file and
    asserts the same `revisionHash`. That is the parity claim. This script cannot run a JVM inside
    the gate budget, so it holds the STATIC invariants that make the test mean what it says:

      * every `ir/<name>.json` names a DSL product (`product("<name>", …)` in ReferenceProducts.kt)
        and every DSL product has a committed `ir/<name>.json` — a file with no author, or an author
        with no artifact, is parity about nothing;
      * every committed IR parses as JSON and carries a top-level `irVersion` and `hooks`;
      * ReferenceProductsTest exists, is not @Disabled, iterates `ReferenceProducts.all`, and
        compares `canonicalJson()` with the committed file;
      * with --junit-xml <dir>: the test's JUnit report lists one PASSED dynamic case per product.

    SURFACES (ADR-0352 phase 5a). The same fixture compiled through (a) the `obp` CLI and (b) the
    agent-service MCP `product_compile` handler must give identical canonical bytes and hash. The
    JVM half is `ProductIrAuthoringParityTest` (openbank-agent-service); this script requires it to
    exist, not be disabled, drive BOTH `Obp.run` and `product_compile`, and assert byte equality.
    (c) the Studio form path (product-catalog draft-from-form, phase 6) does not exist on main yet:
    it is declared PENDING below and printed on every run, so its absence is never silent. When it
    lands it joins SURFACE_TEST and STUDIO_PENDING goes.

SCOPE is derived from the filesystem (every *.json under openbank-products/ir/) and from the DSL
source. Prints SUBJECTS=<n committed IR files>.

MODES: advisory (::warning, exit 0) unless --enforce. rules.yaml: product_ir_authoring_parity.

Usage: check-product-ir-authoring-parity.py [--root DIR] [--junit-xml DIR] [--enforce] | --self-test
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

IR_DIR = "openbank-products/ir"
DSL_SOURCE = "openbank-products/src/main/kotlin/com/openbank/products/catalog/ReferenceProducts.kt"
TEST_CLASS = "ReferenceProductsTest"
TEST_FILE = f"openbank-products/src/test/kotlin/com/openbank/products/{TEST_CLASS}.kt"
PRODUCT_RE = re.compile(r'product\(\s*"([a-z][a-z0-9-]*)"')
MODULE_BUILD = "openbank-products/build.gradle.kts"
SURFACE_TEST = "openbank-agent-service/src/test/kotlin/com/openbank/agent/application/ProductIrAuthoringParityTest.kt"
SURFACE_TOKENS = ("Obp.run(", '"product_compile"', "isEqualTo(cliBytes)", "ReferenceProducts.all")
STUDIO_PENDING = "surface (c) Studio form: PENDING — no product-catalog draft-from-form path on main (ADR-0352 phase 6)"
DISABLED_RE = re.compile(r"^\s*@(?:org\.junit\.jupiter\.api\.)?Disabled\b", re.M)


def strip_kotlin_comments(text: str) -> str:
    """Kotlin source with comments blanked and string literals kept, so a token in a KDoc or a `//`
    line can never satisfy a check about code. Block comments NEST in Kotlin; this mirrors that."""
    out: list[str] = []
    i, depth, n = 0, 0, len(text)
    in_str = False
    while i < n:
        two = text[i:i + 2]
        if depth == 0 and not in_str and two == "//":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if not in_str and two == "/*":
            depth += 1
            i += 2
            continue
        if depth > 0:
            if two == "*/":
                depth -= 1
                i += 2
                continue
            if text[i] == "\n":
                out.append("\n")
            i += 1
            continue
        c = text[i]
        if c == '"' and (i == 0 or text[i - 1] != "\\"):
            in_str = not in_str
        out.append(c)
        i += 1
    return "".join(out)


def dsl_products(root: pathlib.Path) -> set[str] | None:
    src = root / DSL_SOURCE
    if not src.is_file():
        return None
    return set(PRODUCT_RE.findall(strip_kotlin_comments(src.read_text(encoding="utf-8"))))


def findings(root: pathlib.Path) -> tuple[list[str], list[str]]:
    """Returns (findings, product names with a committed IR)."""
    f: list[str] = []
    ir_dir = root / IR_DIR
    files = sorted(ir_dir.glob("*.json")) if ir_dir.is_dir() else []
    names = [p.stem for p in files]
    for p in files:
        try:
            doc = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            f.append(f"{p.relative_to(root)}: not valid JSON ({e})")
            continue
        if not isinstance(doc, dict) or "irVersion" not in doc or "hooks" not in doc:
            f.append(f"{p.relative_to(root)}: not a product IR document (no top-level irVersion + hooks)")
    declared = dsl_products(root)
    if declared is None:
        f.append(f"{DSL_SOURCE}: missing — no DSL products to compare the committed IR with")
    else:
        for n in names:
            if n not in declared:
                f.append(f"{IR_DIR}/{n}.json: no DSL product named '{n}' in ReferenceProducts.kt — an artifact with no author")
        for n in sorted(declared - set(names)):
            f.append(f"ReferenceProducts.kt: product '{n}' has no committed {IR_DIR}/{n}.json — run `obp compile all --out ir`")
    test = root / TEST_FILE
    if not test.is_file():
        f.append(f"{TEST_FILE}: missing — the JVM half of this gate does not exist")
    else:
        text = strip_kotlin_comments(test.read_text(encoding="utf-8"))
        if DISABLED_RE.search(text):
            f.append(f"{TEST_FILE}: carries @Disabled — the JVM half is switched off")
        if "ReferenceProducts.all" not in text:
            f.append(f"{TEST_FILE}: does not iterate ReferenceProducts.all — its scope is not the DSL product set")
        if "canonicalJson()" not in text or 'Path.of("ir"' not in text:
            f.append(f"{TEST_FILE}: does not compare canonicalJson() with the committed ir/<name>.json")
    surface = root / SURFACE_TEST
    if not surface.is_file():
        f.append(f"{SURFACE_TEST}: missing — the CLI/MCP surface parity test does not exist")
    else:
        code = strip_kotlin_comments(surface.read_text(encoding="utf-8"))
        if DISABLED_RE.search(code):
            f.append(f"{SURFACE_TEST}: carries @Disabled — the surface parity half is switched off")
        for token in SURFACE_TOKENS:
            if token not in code:
                f.append(f"{SURFACE_TEST}: no `{token}` — it does not compare the CLI and MCP surfaces byte for byte")
    build = root / MODULE_BUILD
    if build.is_file():
        code = strip_kotlin_comments(build.read_text(encoding="utf-8"))
        if re.search(r"exclude\s*\(?[^\n]*" + TEST_CLASS, code) or re.search(r"filter\s*\{[^}]*" + TEST_CLASS, code, re.S):
            f.append(f"{MODULE_BUILD}: excludes {TEST_CLASS} from the test task — the JVM half never runs")
    return f, names


def junit_findings(junit_dir: pathlib.Path, names: list[str]) -> list[str]:
    f: list[str] = []
    reports = list(junit_dir.glob(f"TEST-*{TEST_CLASS}.xml"))
    if not reports:
        return [f"{junit_dir}: no JUnit report for {TEST_CLASS} — the JVM half did not run"]
    passed: set[str] = set()
    for r in reports:
        for case in ET.parse(r).getroot().iter("testcase"):
            if case.find("failure") is None and case.find("error") is None and case.find("skipped") is None:
                passed.add(case.get("name", ""))
    for n in names:
        if not any(n in p for p in passed):
            f.append(f"{TEST_CLASS}: no passed dynamic case for product '{n}'")
    return f


def run(root: pathlib.Path, junit: pathlib.Path | None, enforce: bool) -> int:
    f, names = findings(root)
    if junit is not None:
        f += junit_findings(junit, names)
    print(f"SUBJECTS={len(names)}")
    print(f"product-ir-authoring-parity: {STUDIO_PENDING}")
    if not names:
        for line in f:
            print(f"::error::product-ir-authoring-parity: {line}")
        print("::error::no committed IR under openbank-products/ir — refusing to report a pass")
        return 1
    if f:
        level = "error" if enforce else "warning"
        for line in f:
            print(f"::{level}::product-ir-authoring-parity: {line}")
        if not enforce:
            print("product-ir-authoring-parity: ADVISORY mode — not failing the build")
        return 1 if enforce else 0
    print(f"product-ir-authoring-parity: OK — {len(names)} committed IR file(s) each authored by a DSL product and covered by {TEST_CLASS}")
    return 0


# --- self-test -----------------------------------------------------------------------------------

GOOD_TEST = (
    "class ReferenceProductsTest {\n  fun x() = ReferenceProducts.all.map { p -> p.canonicalJson(); Path.of(\"ir\", p.name) }\n}\n"
)
GOOD_SURFACE = (
    "class ProductIrAuthoringParityTest {\n  fun x() = ReferenceProducts.all.map { Obp.run(listOf()); "
    "registry.call(\"product_compile\"); assertThat(mcpBytes).isEqualTo(cliBytes) }\n}\n"
)


def _fixture(root: pathlib.Path, irs: dict[str, dict], dsl_names: list[str] | None, test_text: str | None = GOOD_TEST,
             dsl_text: str | None = None, build_text: str | None = None, surface_text: str | None = GOOD_SURFACE) -> None:
    if surface_text is not None:
        sp = root / SURFACE_TEST
        sp.parent.mkdir(parents=True, exist_ok=True)
        sp.write_text(surface_text, encoding="utf-8")
    for name, doc in irs.items():
        p = root / IR_DIR / f"{name}.json"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(doc), encoding="utf-8")
    if dsl_names is not None or dsl_text is not None:
        s = root / DSL_SOURCE
        s.parent.mkdir(parents=True, exist_ok=True)
        body = dsl_text if dsl_text is not None else "\n".join(
            f'val x{i} = product("{n}", "C", "EUR") {{ }}' for i, n in enumerate(dsl_names or [])
        )
        s.write_text(body, encoding="utf-8")
    if build_text is not None:
        b = root / MODULE_BUILD
        b.parent.mkdir(parents=True, exist_ok=True)
        b.write_text(build_text, encoding="utf-8")
    if test_text is not None:
        t = root / TEST_FILE
        t.parent.mkdir(parents=True, exist_ok=True)
        t.write_text(test_text, encoding="utf-8")


def _ir() -> dict:
    return {"irVersion": 1, "productCode": "X", "currency": "EUR", "hooks": {}}


def self_test() -> int:
    failures = 0

    def case(label: str, want_exit: int, text: str, irs, dsl_names, test_text=GOOD_TEST, enforce=True,
             dsl_text=None, build_text=None, surface_text=GOOD_SURFACE) -> None:
        nonlocal failures
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            _fixture(root, irs, dsl_names, test_text, dsl_text, build_text, surface_text)
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                got = run(root, None, enforce)
            out = buf.getvalue()
            if got != want_exit or text not in out:
                failures += 1
                print(f"SELF-TEST FAIL: {label}: exit {got} (want {want_exit}); output:\n{out}", file=sys.stderr)

    case("the consistent shape is green", 0, "OK — 2 committed", {"a": _ir(), "b": _ir()}, ["a", "b"])
    case("a committed IR with no DSL product is red", 1, "no DSL product named 'b'", {"a": _ir(), "b": _ir()}, ["a"])
    case("a DSL product with no committed IR is red", 1, "has no committed", {"a": _ir()}, ["a", "c"])
    case("a non-IR JSON file is red", 1, "not a product IR document", {"a": {"foo": 1}}, ["a"])
    case("a disabled parity test is red", 1, "carries @Disabled", {"a": _ir()}, ["a"], "@Disabled\n" + GOOD_TEST)
    case("a parity test not scoped to the DSL set is red", 1, "does not iterate ReferenceProducts.all", {"a": _ir()}, ["a"], "class ReferenceProductsTest { fun x() = 1 }")
    # Tokens that only appear in COMMENTS must not satisfy the check (review finding M4).
    case("required tokens only in a comment are red", 1, "does not iterate ReferenceProducts.all", {"a": _ir()}, ["a"],
         "// ReferenceProducts.all canonicalJson() Path.of(\"ir\"\nclass ReferenceProductsTest { fun x() = 1 }")
    case("@Disabled mentioned in a KDoc is NOT a disabled test", 0, "OK — 1 committed", {"a": _ir()}, ["a"],
         "/** never put @Disabled here */\n" + GOOD_TEST)
    case("@Disabled on its own line above the class is red", 1, "carries @Disabled", {"a": _ir()}, ["a"],
         "@Disabled(\"later\")\n" + GOOD_TEST)
    case("a DSL product named only in a comment does not count", 1, "no DSL product named 'a'", {"a": _ir()}, None, GOOD_TEST, dsl_text='// product("a", "C", "EUR")\n')
    case("no committed IR at all is red, never a vacuous pass", 1, "refusing to report a pass", {}, None, None)
    case("advisory mode reports the finding and exits 0", 0, "ADVISORY mode", {"a": _ir()}, [], GOOD_TEST, enforce=False)
    case("a build that excludes the parity test is red", 1, "excludes ReferenceProductsTest", {"a": _ir()}, ["a"], GOOD_TEST,
         build_text='tasks.test { filter { excludeTestsMatching("*ReferenceProductsTest") } }')
    case("a build that only MENTIONS the test in a comment is fine", 0, "OK — 1 committed", {"a": _ir()}, ["a"], GOOD_TEST,
         build_text='// ReferenceProductsTest is the JVM half\ntasks.test { useJUnitPlatform() }')
    # The CLI/MCP surface half (phase 5a): missing, disabled, one-sided or comment-only is red.
    case("a missing surface parity test is red", 1, "surface parity test does not exist", {"a": _ir()}, ["a"], surface_text=None)
    case("a disabled surface parity test is red", 1, "surface parity half is switched off", {"a": _ir()}, ["a"],
         surface_text="@Disabled\n" + GOOD_SURFACE)
    case("a surface test that never drives the MCP handler is red", 1, '`"product_compile"`', {"a": _ir()}, ["a"],
         surface_text=GOOD_SURFACE.replace('"product_compile"', '"product_lint"'))
    case("surface tokens only in a comment are red", 1, "`Obp.run(`", {"a": _ir()}, ["a"],
         surface_text="// Obp.run( \"product_compile\" isEqualTo(cliBytes) ReferenceProducts.all\nclass ProductIrAuthoringParityTest {}")
    case("the Studio surface is reported pending on every run, never silently", 0, "Studio form: PENDING", {"a": _ir()}, ["a"])
    # The JUnit half: a missing report and a report with no case for a product are both findings.
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        _fixture(root, {"a": _ir()}, ["a"])
        junit = root / "junit"
        junit.mkdir()
        if not junit_findings(junit, ["a"]):
            failures += 1
            print("SELF-TEST FAIL: a missing JUnit report must be a finding", file=sys.stderr)
        (junit / f"TEST-com.openbank.products.{TEST_CLASS}.xml").write_text(
            '<testsuite><testcase name="a"/><testcase name="z"><failure/></testcase></testsuite>', encoding="utf-8"
        )
        if junit_findings(junit, ["a"]) or not junit_findings(junit, ["z"]):
            failures += 1
            print("SELF-TEST FAIL: JUnit pass/fail per product misread", file=sys.stderr)
    if failures:
        print(f"self-test: {failures} failure(s)")
        return 1
    print("self-test: OK (22 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--junit-xml", default=None)
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return run(pathlib.Path(a.root).resolve(), pathlib.Path(a.junit_xml) if a.junit_xml else None, a.enforce)


if __name__ == "__main__":
    sys.exit(main())
