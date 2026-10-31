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
    exist, not be disabled, drive `Obp.run` and the lint/compile/simulate/explain MCP handlers, and
    assert raw-byte equality and that MCP `isError` holds exactly when the CLI exits non-zero.
    (c) the Studio form path (ADR-0352 phase 5b): Studio's IR editor validates through the catalog's
    `POST /api/v2/product-ir/lint`, which returns the canonical IR it would store. Its JVM half is
    `IrLintContractTest` (openbank-product-catalog): for every committed `ir/<name>.json` the lint's
    `canonicalIr` must equal the file byte for byte and its `revisionHash` the shared parser's. With
    ReferenceProductsTest (DSL == file) and the CLI/MCP test (CLI == MCP over the same DSL), all three
    surfaces meet at the committed bytes; a second case feeds each IR reordered and pretty-printed, so
    a lint that echoed its input could not pass. Statically this script requires the test to exist, not
    be disabled and ASSERT both comparisons (the assertion, not a token); with --studio-junit-xml it
    requires both cases PASSED in IrLintContractTest's report — a divergence is a finding.

WHERE EACH HALF RUNS. The gate shard (gates.yaml) has no test results, so it runs the static half.
The JUnit halves run in _service-ci.yml ("Product IR authoring parity (JUnit half)") of the
openbank-products (--junit-xml) and openbank-product-catalog (--studio-junit-xml) module jobs.

SCOPE is derived from the filesystem (every *.json under openbank-products/ir/) and from the DSL
source. Prints SUBJECTS=<n committed IR files>.

MODES: advisory (::warning, exit 0) unless --enforce. rules.yaml: product_ir_authoring_parity.

Usage: check-product-ir-authoring-parity.py [--root DIR] [--junit-xml DIR] [--studio-junit-xml DIR] [--enforce]
       | --self-test
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
SURFACE_TOKENS = ("Obp.run(", '"product_compile"', '"product_lint"', '"product_simulate"', '"product_explain"',
                  "isEqualTo(cli.first != 0)", "ReferenceProducts.all")
STUDIO_TEST = "openbank-product-catalog/src/test/kotlin/com/openbank/productcatalog/contract/IrLintContractTest.kt"
STUDIO_TOKENS = ('"/api/v2/product-ir/lint"', '"openbank-products", "ir"', "canonicalIr", "ProductIrParser.parse(")
STUDIO_CLASS = "IrLintContractTest"
# The two dynamic claims the Studio surface rests on: the committed bytes round-trip, and a reordered,
# pretty-printed input still canonicalises to them (so the lint is not merely echoing its input).
STUDIO_CASES = ("every committed reference IR lints to its own bytes and hash",
                "a reordered, pretty-printed IR lints to the committed canonical bytes")
# The COMPARISONS, not their tokens: the lint's canonicalIr asserted equal to the committed text (both
# cases), and its revisionHash asserted equal to the shared parser's.
STUDIO_BYTES_RE = re.compile(r"assertThat\(\s*canonical\w*\s*\)[^\n;]*?\.isEqualTo\(\s*committed\s*\)")
STUDIO_HASH_RE = re.compile(r'body\(\s*"revisionHash"\s*,\s*equalTo\(\s*expected\.revisionHash\s*\)')
STUDIO_COVERED = "surface (c) Studio form: covered by IrLintContractTest (catalog lint canonicalIr == committed IR)"
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
    studio = root / STUDIO_TEST
    if not studio.is_file():
        f.append(f"{STUDIO_TEST}: missing — the Studio surface parity test does not exist")
    else:
        code = strip_kotlin_comments(studio.read_text(encoding="utf-8"))
        if DISABLED_RE.search(code):
            f.append(f"{STUDIO_TEST}: carries @Disabled — the Studio surface half is switched off")
        for token in STUDIO_TOKENS:
            if token not in code:
                f.append(f"{STUDIO_TEST}: no `{token}` — it does not compare the Studio lint with the committed IR")
        if len(STUDIO_BYTES_RE.findall(code)) < len(STUDIO_CASES):
            f.append(f"{STUDIO_TEST}: never asserts the lint's canonicalIr equals the committed file in each of its "
                     f"{len(STUDIO_CASES)} parity cases (assertThat(canonical…).isEqualTo(committed))")
        if not STUDIO_HASH_RE.search(code):
            f.append(f"{STUDIO_TEST}: never asserts revisionHash equals the shared parser's (expected.revisionHash)")
    build = root / MODULE_BUILD
    if build.is_file():
        code = strip_kotlin_comments(build.read_text(encoding="utf-8"))
        if re.search(r"exclude\s*\(?[^\n]*" + TEST_CLASS, code) or re.search(r"filter\s*\{[^}]*" + TEST_CLASS, code, re.S):
            f.append(f"{MODULE_BUILD}: excludes {TEST_CLASS} from the test task — the JVM half never runs")
    return f, names


def junit_verdicts(junit_dir: pathlib.Path, cls: str) -> dict[str, str] | None:
    """Verdict per test case of `cls` in a Gradle JUnit dir: `passed`, `failure`, `error` or `skipped`
    (a skip is not a pass); None when there is no report for the class. ONE reader for both halves."""
    reports = list(junit_dir.glob(f"TEST-*{cls}.xml"))
    if not reports:
        return None
    verdicts: dict[str, str] = {}
    for r in reports:
        for case in ET.parse(r).getroot().iter("testcase"):
            bad = next((tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None), None)
            verdicts[case.get("name", "")] = bad or "passed"
    return verdicts


def verdict_of(verdicts: dict[str, str], wanted: str) -> str | None:
    """EXACT match: Gradle names a dynamic test by its display name (`savings-fixed`) and a method test
    `<display name>()`; a renamed, prefixed or suffixed case (`savings-fixed-v2`) is not this one."""
    return verdicts.get(wanted, verdicts.get(f"{wanted}()"))


def junit_findings(junit_dir: pathlib.Path, names: list[str]) -> list[str]:
    verdicts = junit_verdicts(junit_dir, TEST_CLASS)
    if verdicts is None:
        return [f"{junit_dir}: no JUnit report for {TEST_CLASS} — the JVM half did not run"]
    return [f"{TEST_CLASS}: product '{n}' {verdict_of(verdicts, n) or 'absent'} — no passed dynamic case"
            for n in names if verdict_of(verdicts, n) != "passed"]


def studio_junit_findings(junit_dir: pathlib.Path) -> list[str]:
    """The Studio half's JUnit report: both byte-parity cases present and PASSED."""
    verdicts = junit_verdicts(junit_dir, STUDIO_CLASS)
    if verdicts is None:
        return [f"{junit_dir}: no JUnit report for {STUDIO_CLASS} — the Studio half did not run"]
    return [f"{STUDIO_CLASS}: '{w}' {verdict_of(verdicts, w) or 'absent'} — the Studio lint's canonical bytes "
            "are not proven equal to the committed IR" for w in STUDIO_CASES if verdict_of(verdicts, w) != "passed"]


def run(root: pathlib.Path, junit: pathlib.Path | None, enforce: bool,
        studio_junit: pathlib.Path | None = None) -> int:
    f, names = findings(root)
    if junit is not None:
        f += junit_findings(junit, names)
    if studio_junit is not None:
        f += studio_junit_findings(studio_junit)
    print(f"SUBJECTS={len(names)}")
    print(f"product-ir-authoring-parity: {STUDIO_COVERED}")
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
GOOD_STUDIO = (
    "class IrLintContractTest {\n  val dir = Path.of(\"..\", \"openbank-products\", \"ir\")\n"
    "  fun x() { val expected = ProductIrParser.parse(committed); post(\"/api/v2/product-ir/lint\");\n"
    "    body(\"revisionHash\", equalTo(expected.revisionHash))\n"
    "    val canonical = getString(\"canonicalIr\")\n"
    "    assertThat(canonical).describedAs(file.fileName.toString()).isEqualTo(committed) }\n"
    "  fun y() { val canonical = getString(\"canonicalIr\")\n"
    "    assertThat(canonical).describedAs(name).isEqualTo(committed) }\n}\n"
)
GOOD_SURFACE = (
    "class ProductIrAuthoringParityTest {\n  fun x() = ReferenceProducts.all.map { Obp.run(listOf()); "
    "mcp(\"product_compile\"); mcp(\"product_lint\"); mcp(\"product_simulate\"); mcp(\"product_explain\"); "
    "assertThat(mcp.first).isEqualTo(cli.first != 0) }\n}\n"
)


def _fixture(root: pathlib.Path, irs: dict[str, dict], dsl_names: list[str] | None, test_text: str | None = GOOD_TEST,
             dsl_text: str | None = None, build_text: str | None = None, surface_text: str | None = GOOD_SURFACE,
             studio_text: str | None = GOOD_STUDIO) -> None:
    if studio_text is not None:
        st = root / STUDIO_TEST
        st.parent.mkdir(parents=True, exist_ok=True)
        st.write_text(studio_text, encoding="utf-8")
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
    ran = 0

    def case(label: str, want_exit: int, text: str, irs, dsl_names, test_text=GOOD_TEST, enforce=True,
             dsl_text=None, build_text=None, surface_text=GOOD_SURFACE, studio_text=GOOD_STUDIO) -> None:
        nonlocal failures, ran
        ran += 1
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            _fixture(root, irs, dsl_names, test_text, dsl_text, build_text, surface_text, studio_text)
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
         surface_text="// Obp.run( \"product_compile\" \"product_lint\" \"product_simulate\" \"product_explain\" isEqualTo(cli.first != 0) ReferenceProducts.all\nclass ProductIrAuthoringParityTest {}")
    case("a surface test that never compares failure status is red", 1, "isEqualTo(cli.first != 0)", {"a": _ir()}, ["a"],
         surface_text=GOOD_SURFACE.replace("isEqualTo(cli.first != 0)", "isEqualTo(true)"))
    case("a missing Studio surface test is red", 1, "Studio surface parity test does not exist", {"a": _ir()}, ["a"],
         studio_text=None)
    case("a disabled Studio surface test is red", 1, "Studio surface half is switched off", {"a": _ir()}, ["a"],
         studio_text="@Disabled\n" + GOOD_STUDIO)
    case("a Studio test that never compares the canonical bytes is red", 1, "`canonicalIr`", {"a": _ir()}, ["a"],
         studio_text=GOOD_STUDIO.replace("canonicalIr", "valid"))
    case("a Studio test not scoped to the committed IR set is red", 1, '`"openbank-products", "ir"`', {"a": _ir()}, ["a"],
         studio_text=GOOD_STUDIO.replace('"openbank-products", "ir"', '"fixtures"'))
    case("Studio tokens only in a comment are red", 1, '`"/api/v2/product-ir/lint"`', {"a": _ir()}, ["a"],
         studio_text="/* " + GOOD_STUDIO + " */")
    case("the Studio surface is no longer reported pending", 0, "Studio form: covered", {"a": _ir()}, ["a"])
    # The comparison itself, not its tokens: dropping the byte assertion while keeping every token is red.
    case("a Studio test that reads canonicalIr but never asserts it equals the file is red", 1,
         "never asserts the lint's canonicalIr equals the committed file", {"a": _ir()}, ["a"],
         studio_text=GOOD_STUDIO.replace(".isEqualTo(committed)", ".isNotNull()"))
    case("a Studio test that asserts the bytes once but drops the reordered-input case is red", 1,
         "never asserts the lint's canonicalIr equals the committed file", {"a": _ir()}, ["a"],
         studio_text=GOOD_STUDIO.replace("describedAs(name).isEqualTo(committed)", "describedAs(name).isNotNull()"))
    case("a Studio test that never compares the hash to the parser's is red", 1, "never asserts revisionHash", {"a": _ir()}, ["a"],
         studio_text=GOOD_STUDIO.replace("equalTo(expected.revisionHash)", "notNullValue()"))
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
        # Exact names: a passing `savings-fixed-v2` is not evidence for `savings-fixed`.
        (junit / f"TEST-com.openbank.products.{TEST_CLASS}.xml").write_text(
            '<testsuite><testcase name="savings-fixed-v2"/></testsuite>', encoding="utf-8"
        )
        if not junit_findings(junit, ["savings-fixed"]):
            failures += 1
            print("SELF-TEST FAIL: a product's case must match by exact name, not substring", file=sys.stderr)
    # The Studio JUnit half: a missing report, a failed byte case and a skipped one are all findings.
    with tempfile.TemporaryDirectory() as d:
        junit = pathlib.Path(d)
        if not studio_junit_findings(junit):
            failures += 1
            print("SELF-TEST FAIL: a missing IrLintContractTest report must be a finding", file=sys.stderr)
        report = junit / f"TEST-com.openbank.productcatalog.contract.{STUDIO_CLASS}.xml"
        ok = "".join(f'<testcase name="{c}()"/>' for c in STUDIO_CASES)
        report.write_text(f"<testsuite>{ok}</testsuite>", encoding="utf-8")
        if studio_junit_findings(junit):
            failures += 1
            print("SELF-TEST FAIL: a passing IrLintContractTest report must be clean", file=sys.stderr)
        prefixed = "".join(f'<testcase name="old {c}()"/>' for c in STUDIO_CASES)
        report.write_text(f"<testsuite>{prefixed}</testsuite>", encoding="utf-8")
        if len(studio_junit_findings(junit)) != len(STUDIO_CASES):
            failures += 1
            print("SELF-TEST FAIL: a Studio case must match by exact name, not substring", file=sys.stderr)
        for verdict in ("<failure/>", "<skipped/>"):
            bad = ok.replace(f'<testcase name="{STUDIO_CASES[1]}()"/>', f'<testcase name="{STUDIO_CASES[1]}()">{verdict}</testcase>')
            report.write_text(f"<testsuite>{bad}</testsuite>", encoding="utf-8")
            if not any("canonical bytes" in f for f in studio_junit_findings(junit)):
                failures += 1
                print(f"SELF-TEST FAIL: a {verdict} byte-parity case must be a finding", file=sys.stderr)
    # REAL Gradle reports (fixtures/product-ir-parity, produced by `:openbank-products:test` and
    # `:openbank-product-catalog:test`, hostname scrubbed): the reader must find every committed product
    # and both Studio cases PASSED in them, by the exact names Gradle actually writes, and nothing by a
    # prefix. Synthetic XML above can only agree with this function's own idea of a report.
    real = pathlib.Path(__file__).resolve().parent / "fixtures" / "product-ir-parity"
    repo = pathlib.Path(__file__).resolve().parents[2]
    products = sorted(p.stem for p in (repo / IR_DIR).glob("*.json"))
    verdicts = junit_verdicts(real, TEST_CLASS) or {}
    checks = [
        (bool(products) and junit_findings(real, products) == [],
         f"every committed product {products} must read PASSED from the real {TEST_CLASS} report"),
        (all(verdict_of(verdicts, n) == "passed" for n in products), "verdict_of must resolve each dynamic case"),
        (verdict_of(verdicts, "savings") is None, "a prefix of a real case name must not resolve"),
        (verdict_of(verdicts, "the library carries the ADR-0352 reference deposit products with distinct hashes")
         == "passed", "a method case must resolve by its display name plus ()"),
        (studio_junit_findings(real) == [], f"both Studio cases must read PASSED from the real {STUDIO_CLASS} report"),
    ]
    for ok, label in checks:
        ran += 1
        if not ok:
            failures += 1
            print(f"SELF-TEST FAIL: {label}", file=sys.stderr)
    if failures:
        print(f"self-test: {failures} failure(s)")
        return 1
    print(f"self-test: OK ({ran} cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--junit-xml", default=None, help="openbank-products test results (ReferenceProductsTest)")
    ap.add_argument("--studio-junit-xml", default=None,
                    help="openbank-product-catalog test results (IrLintContractTest)")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    return run(pathlib.Path(a.root).resolve(), pathlib.Path(a.junit_xml) if a.junit_xml else None, a.enforce,
               pathlib.Path(a.studio_junit_xml) if a.studio_junit_xml else None)


if __name__ == "__main__":
    sys.exit(main())
