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

PROPERTIES: dict[str, list[str]] = {
    "termination": [r"IrLimits\.MAX_INSTRUCTIONS_PER_EVALUATION", r"isInstanceOf\(ProductEvaluationException::class\.java\)"],
    "balance-conservation": [r"\bassertMoneyConserved\("],
    "monotone-tiers": [r"\bassertMonotoneTiers\(", r"\binterpreter\.evaluate\(", r"marginal rate"],
    "determinism": [r"InstructionCanonical\.write\(\w+\)\)\.isEqualTo\(InstructionCanonical\.write\("],
    "purity": [r"\bimpureReferences\(", r"java/time/Clock", r"FinanceProductArithmetic::class\.java"],
    "half-up-rounding": [r'isEqualByComparingTo\("0\.069445"\)'],
}
SCOPE = ["ReferenceProducts.all", "ProductArb.revision"]


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
    for name, patterns in PROPERTIES.items():
        missing = [p for p in patterns if not re.search(p, code)]
        if missing:
            findings.append(f"property {name}: no assertion matching {missing}")
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

    case("the real test is green", 0, f"OK: {len(PROPERTIES)} property assertions present", good)
    case("a missing file is red", 1, "asserted nowhere", None)
    case("@Disabled is red", 1, "@Disabled", good.replace("class ProductFormalPropertiesTest", "@Disabled\nclass ProductFormalPropertiesTest"))
    case("@Disabled in a comment is green", 0, "OK", good.replace("class ProductFormalPropertiesTest", "// @Disabled\nclass ProductFormalPropertiesTest"))
    case("dropping money conservation is red", 1, "property balance-conservation", good.replace("assertMoneyConserved(", "noop("))
    case("money conservation only in a comment is red", 1, "property balance-conservation",
         good.replace("assertMoneyConserved(", "noop(") + "\n// assertMoneyConserved(out)\n")
    case("dropping the run limit is red", 1, "property termination", good.replace("IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION", "Int.MAX_VALUE"))
    case("dropping the tier check is red", 1, "property monotone-tiers", good.replace("assertMonotoneTiers(", "noop("))
    case("dropping the over-limit refusal is red", 1, "property termination",
         good.replace("isInstanceOf(ProductEvaluationException::class.java)", "isNotNull()"))
    case("dropping the finance scan is red", 1, "property purity", good.replace("FinanceProductArithmetic::class.java", "String::class.java"))
    case("dropping the HALF_UP golden is red", 1, "property half-up-rounding", good.replace('"0.069445"', '"0.069444"'))
    case("dropping determinism is red", 1, "property determinism", good.replace(").isEqualTo(InstructionCanonical.write(", ").isNotNull().isEqualTo(listOf("))
    case("dropping purity is red", 1, "property purity", good.replace("impureReferences(", "noRefs("))
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
