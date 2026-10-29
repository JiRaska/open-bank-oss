#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""ADR-0352 §9 phase 6 `product-ir-formal-properties`: the test that asserts the five formal properties
of the product interpreter (plus the v1 HALF_UP pin) is PRESENT, enabled and unnarrowed.

This gate does not run the properties and cannot tell a passing assertion from a failing one; it only
checks that each one is still written down. The assertions themselves run as ProductFormalPropertiesTest

(openbank-products) in the module's CI lane. A property test that is deleted, @Disabled, or
quietly narrowed to a hand-picked product list reads exactly like a passing one, so this gate holds
the static half on every PR, over COMMENT-STRIPPED Kotlin (a token in a comment is not a test):

  termination           bounds output by IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION and expects
                        ProductEvaluationException one rule past it
  balance-conservation  calls assertMoneyConserved
  monotone-tiers        assertMonotoneTiers over interpreter.evaluate output, marginal rate per tier
  determinism           compares InstructionCanonical.write of two evaluations
  purity                scans the compiled package (impureReferences), the deposit arithmetic
                        (FinanceProductArithmetic), and has a known-positive
  half-up-rounding      a golden accrual exactly on a half (0.069445; HALF_EVEN gives 0.069444)

Scope: ReferenceProducts.all (every reference product) AND ProductArb.revision (generated
revisions). The test file must not be excluded by openbank-products/build.gradle.kts.

Prints SUBJECTS=<number of properties whose presence is checked>.

Usage: check-product-ir-formal-properties.py [--root DIR] | --self-test      (exit 1 on a finding)
"""

from __future__ import annotations

import argparse
import contextlib
import io
import pathlib
import re
import sys
import tempfile

TEST = "openbank-products/src/test/kotlin/com/openbank/products/ProductFormalPropertiesTest.kt"
BUILD = "openbank-products/build.gradle.kts"
CLASS = "ProductFormalPropertiesTest"

# property -> [(function whose BODY must carry the patterns, [patterns])]. A function is a test named by
# a back-ticked prefix, or a helper named `name(`. Requiring each pattern inside its own function is the
# point: a helper definition or a known-negative test cannot stand in for a deleted property test.
PROPERTIES: dict[str, list[tuple[str, list[str]]]] = {
    "termination": [
        ("`generated revisions terminate within the limit", [r"IrLimits\.MAX_INSTRUCTIONS_PER_EVALUATION"]),
        ("`an evaluation that would exceed the instruction limit is refused", [r"isInstanceOf\(ProductEvaluationException::class\.java\)"]),
    ],
    "balance-conservation": [
        ("`generated revisions terminate within the limit", [r"\bassertMoneyConserved\("]),
        ("`reference products - same input, byte-identical output", [r"\bassertMoneyConserved\("]),
    ],
    "monotone-tiers": [
        ("`reference products - a higher balance never gets a lower tier rate", [r"assertMonotoneTiers\([^)]*checkRates = true"]),
        ("`generated revisions - every revision the monotonicity check passes", [r"ProductMonotonicity\.isMonotone\(", r"assertMonotoneTiers\([^)]*checkRates = true"]),
        ("assertMonotoneTiers(", [r"\binterpreterAccrual\(", r"marginal rate", r"terms\.ruleId"]),
        ("interpreterAccrual(", [r"\binterpreter\.evaluate\("]),
    ],
    "determinism": [
        ("`generated revisions terminate within the limit", [r"InstructionCanonical\.write\(\w+\)\)\.isEqualTo\(InstructionCanonical\.write\("]),
        ("`reference products - same input, byte-identical output", [r"InstructionCanonical\.write\(\w+\)\)\.isEqualTo\(InstructionCanonical\.write\("]),
    ],
    "purity": [
        ("`the compiled interpreter package references no", [r"\bimpureReferences\(", r"FinanceProductArithmetic::class\.java"]),
        ("`the purity scan flags each ambient family", [r"\bimpureReferences\(", r"UUID\.randomUUID", r"ZoneId\.systemDefault"]),
    ],
    "half-up-rounding": [
        ("`v1 accrual and capitalisation round HALF_UP", [r'isEqualByComparingTo\("0\.069445"\)', r'BigDecimal\("0\.13"\)']),
    ],
}
SCOPE = ["ReferenceProducts.all", "ProductArb.revision"]
FUN_START = re.compile(r"^    (?:@Test|@TestFactory|private fun |fun |/\*\*|private object |private companion object)", re.M)


def body_of(code: str, name: str) -> str | None:
    """Text of the function declared as `fun <name>` (a test) or `private fun <name>` (a helper)."""
    m = re.search(r"\bfun " + re.escape(name), code)
    if not m:
        return None
    nxt = FUN_START.search(code, m.end())
    return code[m.end(): nxt.start() if nxt else len(code)]


def strip_comments(text: str) -> str:
    """Remove // and (nesting) /* */ comments; string literals are kept, since the purity list lives in them."""
    out, i, n, depth, in_str = [], 0, len(text), 0, False
    while i < n:
        two = text[i:i + 2]
        if depth == 0 and text[i] == '"' and (i == 0 or text[i - 1] != "\\"):
            in_str = not in_str
            out.append(text[i])
            i += 1
        elif in_str:
            out.append(text[i])
            i += 1
        elif two == "/*":
            depth += 1
            i += 2
        elif two == "*/" and depth:
            depth -= 1
            i += 2
        elif depth:
            i += 1
        elif two == "//":
            while i < n and text[i] != "\n":
                i += 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def run(root: pathlib.Path) -> int:
    findings = []
    test = root / TEST
    print(f"SUBJECTS={len(PROPERTIES)}")
    if not test.is_file():
        print(f"::error::{TEST} does not exist: the ADR-0352 formal properties are asserted nowhere")
        return 1
    code = strip_comments(test.read_text(encoding="utf-8"))
    if re.search(r"@(Disabled|Ignore)\b", code):
        findings.append(f"{TEST} carries @Disabled/@Ignore")
    if not re.search(rf"\bclass\s+{CLASS}\b", code):
        findings.append(f"{TEST} does not declare class {CLASS}")
    for name, sites in PROPERTIES.items():
        for fn, patterns in sites:
            body = body_of(code, fn)
            if body is None:
                findings.append(f"property {name}: function {fn.strip('`(')!r} not found")
                continue
            missing = [p for p in patterns if not re.search(p, body)]
            if missing:
                findings.append(f"property {name}: {fn.strip('`(')!r} has no assertion matching {missing}")
    for s in SCOPE:
        if not re.search(rf"\b{re.escape(s)}\b", code):
            findings.append(f"scope: {TEST} never iterates {s}")
    build = root / BUILD
    if build.is_file() and CLASS in strip_comments(build.read_text(encoding="utf-8")):
        findings.append(f"{BUILD} names {CLASS}; an exclude there would skip every property")
    for f in findings:
        print(f"::error::{f}")
    if not findings:
        print(f"OK: {len(PROPERTIES)} property assertions present over reference and generated revisions")
    return 1 if findings else 0


def self_test() -> int:
    real = pathlib.Path(__file__).resolve().parents[2] / TEST
    good = real.read_text(encoding="utf-8") if real.is_file() else None
    failures = []
    if good is None:
        print(f"SELF-TEST FAIL: {TEST} missing, no known-good input")
        return 1

    ran = []

    def case(label, want, text, content, build=""):
        ran.append(label)
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            if content is not None:
                (root / TEST).parent.mkdir(parents=True)
                (root / TEST).write_text(content, encoding="utf-8")
            (root / BUILD).parent.mkdir(parents=True, exist_ok=True)
            (root / BUILD).write_text(build, encoding="utf-8")
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = run(root)
            out = buf.getvalue()
            if rc != want or text not in out:
                failures.append(f"{label}: exit {rc} (want {want}), output:\n{out}")

    def drop_fun(text: str, name: str) -> str:
        """Delete a whole function (its annotation included) — the call site, never the helper."""
        m = re.search(r"\n    @(?:Test|TestFactory)\n    fun " + re.escape(name), text)
        assert m, name
        nxt = FUN_START.search(text, m.end())
        return text[:m.start()] + "\n" + text[nxt.start():]

    case("the real test is green", 0, f"OK: {len(PROPERTIES)} property assertions present", good)
    case("a missing file is red", 1, "asserted nowhere", None)
    case("@Disabled is red", 1, "@Disabled", good.replace("class ProductFormalPropertiesTest", "@Disabled\nclass ProductFormalPropertiesTest"))
    case("@Disabled in a comment is green", 0, "OK", good.replace("class ProductFormalPropertiesTest", "// @Disabled\nclass ProductFormalPropertiesTest"))
    for label, fn, prop in [
        ("deleting the reference monotone factory is red", "`reference products - a higher balance never gets a lower tier rate", "monotone-tiers"),
        ("deleting the generated monotone test is red", "`generated revisions - every revision the monotonicity check passes", "monotone-tiers"),
        ("deleting the generated termination test is red", "`generated revisions terminate within the limit", "termination"),
        ("deleting the over-limit test is red", "`an evaluation that would exceed the instruction limit is refused", "termination"),
        ("deleting the reference determinism factory is red", "`reference products - same input, byte-identical output", "balance-conservation"),
        ("deleting the purity scan is red", "`the compiled interpreter package references no", "purity"),
        ("deleting the purity known-positive is red", "`the purity scan flags each ambient family", "purity"),
        ("deleting the HALF_UP golden is red", "`v1 accrual and capitalisation round HALF_UP", "half-up-rounding"),
    ]:
        case(label, 1, f"property {prop}", drop_fun(good, fn))
    case("the money check only in a comment is red", 1, "property balance-conservation",
         drop_fun(good, "`reference products - same input, byte-identical output") + "\n// assertMoneyConserved(out)\n")
    case("checkRates = false in the reference factory is red", 1, "property monotone-tiers",
         good.replace("SAMPLE_DAYS.forEach { assertMonotoneTiers(rev, it, checkRates = true) }", "SAMPLE_DAYS.forEach { assertMonotoneTiers(rev, it, checkRates = false) }"))
    case("dropping the base-rule filter is red", 1, "property monotone-tiers", good.replace("day, terms.ruleId)", "day)"))
    case("dropping the finance scan is red", 1, "property purity", good.replace("FinanceProductArithmetic::class.java", "String::class.java"))
    case("dropping the capitalisation golden is red", 1, "property half-up-rounding", good.replace('BigDecimal("0.13")', 'BigDecimal("0.12")'))
    case("narrowing to a hand list is red", 1, "ReferenceProducts.all", good.replace("ReferenceProducts.all", "listOf(ReferenceProducts.savingsFixed)"))
    case("dropping generated revisions is red", 1, "ProductArb.revision", good.replace("ProductArb.revision", "ProductArb.today"))
    case("a build exclude is red", 1, "an exclude there", good, 'tasks.test { exclude("**/ProductFormalPropertiesTest*") }')
    for f in failures:
        print(f"SELF-TEST FAIL {f}")
    print(f"self-test: {len(ran) - len(failures)}/{len(ran)} passed")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(pathlib.Path(args.root))


if __name__ == "__main__":
    sys.exit(main())
