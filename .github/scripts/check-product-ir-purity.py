#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""ADR-0352 §3/§8 `product-ir-purity` (ENFORCED from phase 1): the product interpreter package is pure.

The real control is the detekt rule `ProductIrPurityRule` (openbank-libs-detekt-rules), which runs
inside every module's detekt task and sees the PSI. detekt runs per module in the service CI lane,
so this gate adds the two things a per-module linter cannot give the manifest:

  1. WIRING: the rule set is registered (META-INF/services) and ACTIVE in config/detekt/detekt.yml.
     A custom detekt rule that is not `active: true` in the overlay never runs — buildUponDefaultConfig
     activates only detekt's own rule sets — and nothing else would notice it going quiet.
  2. A CHEAP MIRROR over the sources: no import of java.io / java.nio.file / java.net / java.time.Clock /
     java.lang.System / logging / Micrometer / OpenTelemetry under com.openbank.libs.product, and no
     `.now()` / `System.` / `Clock.system*` call. This is the ADR's own delivery check, run on every
     PR in seconds; the detekt rule remains the authority (it also sees calls the regex does not).

Scope is derived: every *.kt under src/main whose package is com.openbank.libs.product or a
sub-package, in any module. Prints SUBJECTS=<n files>.

Usage: check-product-ir-purity.py [--root DIR] | --self-test      (always enforced: exit 1 on a finding)
"""

from __future__ import annotations

import argparse
import contextlib
import io
import pathlib
import re
import sys
import tempfile

PACKAGE = "com.openbank.libs.product"
DETEKT_CONFIG = "config/detekt/detekt.yml"
SERVICES = "openbank-libs-detekt-rules/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider"
PROVIDER = "com.openbank.libs.detekt.purity.ProductIrPurityRuleSetProvider"
RULE_SET_ID = "openbank-product-ir-purity"
RULE_NAME = "ProductIrPurityRule"
SKIP_DIRS = {"build", "node_modules", ".git", ".gradle"}

FORBIDDEN_IMPORT = re.compile(
    r"^\s*import\s+(java\.io\.|java\.nio\.file\.|java\.net\.|java\.time\.Clock\b|java\.lang\.System\b|java\.util\.logging\.|"
    r"org\.jboss\.logging\.|org\.slf4j\.|io\.micrometer\.|io\.opentelemetry\.|kotlin\.io\.)",
    re.M,
)
AMBIENT_CALL = re.compile(r"\b(System\.\w+\(|Clock\.system\w*\(|[A-Z]\w*\.now\(|println\(|print\()")
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.M)


def strip_comments_and_strings(text: str) -> str:
    out, i, n, depth = [], 0, len(text), 0
    while i < n:
        two = text[i:i + 2]
        if depth == 0 and text[i] == '"':
            # skip string (handles """ crudely by skipping to the next quote run)
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                i = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and text[j] != '"':
                    j += 2 if text[j] == "\\" else 1
                i = j + 1
            out.append('""')
        elif two == "/*":
            depth += 1
            i += 2
        elif two == "*/" and depth:
            depth -= 1
            i += 2
        elif depth:
            if text[i] == "\n":
                out.append("\n")
            i += 1
        elif two == "//":
            while i < n and text[i] != "\n":
                i += 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def wiring_findings(root: pathlib.Path) -> list[str]:
    f: list[str] = []
    services = root / SERVICES
    if not services.is_file() or PROVIDER not in services.read_text(encoding="utf-8").split():
        f.append(f"{SERVICES}: {PROVIDER} is not registered — the detekt rule set is never loaded")
    cfg = root / DETEKT_CONFIG
    if not cfg.is_file():
        f.append(f"{DETEKT_CONFIG}: missing")
    else:
        text = cfg.read_text(encoding="utf-8")
        block = re.search(rf"^{re.escape(RULE_SET_ID)}:\n((?:[ \t]+.*\n?)*)", text, re.M)
        if not block:
            f.append(f"{DETEKT_CONFIG}: no '{RULE_SET_ID}:' block — a custom rule set is inactive unless the overlay activates it")
        else:
            body = block.group(1)
            if not re.search(r"^\s{2}active:\s*true", body, re.M):
                f.append(f"{DETEKT_CONFIG}: {RULE_SET_ID} is not 'active: true'")
            if not re.search(rf"^\s{{2}}{RULE_NAME}:\n\s{{4}}active:\s*true", body, re.M):
                f.append(f"{DETEKT_CONFIG}: {RULE_NAME} is not 'active: true' under {RULE_SET_ID}")
    return f


def source_findings(root: pathlib.Path) -> tuple[list[str], int]:
    f: list[str] = []
    count = 0
    for path in sorted(root.rglob("*.kt")):
        rel = path.relative_to(root)
        parts = rel.parts
        if any(p in SKIP_DIRS for p in parts) or "src" not in parts or "main" not in parts:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        pkg = PACKAGE_RE.search(text)
        if not pkg or not (pkg.group(1) == PACKAGE or pkg.group(1).startswith(PACKAGE + ".")):
            continue
        count += 1
        clean = strip_comments_and_strings(text)
        for m in FORBIDDEN_IMPORT.finditer(clean):
            line = clean.count("\n", 0, m.start()) + 1
            f.append(f"{rel}:{line}: forbidden import under {PACKAGE}: {m.group(0).strip()}")
        for m in AMBIENT_CALL.finditer(clean):
            line = clean.count("\n", 0, m.start()) + 1
            f.append(f"{rel}:{line}: ambient read or console I/O under {PACKAGE}: {m.group(1)}...)")
    return f, count


def run(root: pathlib.Path) -> int:
    findings = wiring_findings(root)
    src, count = source_findings(root)
    findings += src
    print(f"SUBJECTS={count}")
    if count == 0:
        print(f"::error::no source under package {PACKAGE} found — refusing to report a pass")
        return 1
    for line in findings:
        print(f"::error::{line}")
    if findings:
        print(f"product-ir-purity: {len(findings)} finding(s) over {count} file(s)")
        return 1
    print(f"product-ir-purity: OK — {count} file(s) under {PACKAGE} import no I/O, clock, logging or metrics; detekt rule wired and active")
    return 0


# --------------------------------------------------------------------------------------------
# Self-test: negatives first.
# --------------------------------------------------------------------------------------------

GOOD_CONFIG = f"{RULE_SET_ID}:\n  active: true\n  {RULE_NAME}:\n    active: true\n"


def _fixture(root: pathlib.Path, sources: dict[str, str], config: str = GOOD_CONFIG, provider: str = PROVIDER) -> None:
    (root / SERVICES).parent.mkdir(parents=True)
    (root / SERVICES).write_text(provider + "\n", encoding="utf-8")
    (root / DETEKT_CONFIG).parent.mkdir(parents=True)
    (root / DETEKT_CONFIG).write_text("build:\n  maxIssues: 0\n\n" + config, encoding="utf-8")
    for rel, body in sources.items():
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")


PURE = "package com.openbank.libs.product.ir\n\nimport java.math.BigDecimal\nimport java.time.Instant\n\nclass Interp(val now: Instant) { fun f(b: BigDecimal) = b.add(BigDecimal.ONE) }\n"
MAIN = "openbank-libs-domain/src/main/kotlin/com/openbank/libs/product/ir/Interp.kt"


def self_test() -> int:
    ok = True

    def case(label, want_exit, text, sources, **kw):
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            _fixture(root, sources, **kw)
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                got = run(root)
            good = got == want_exit and text in buf.getvalue()
            ok &= good
            print(f"  [{'ok' if good else 'WRONG'}] {label}: exit {got} (want {want_exit})")
            if not good:
                print(buf.getvalue())

    case("java.io import is red", 1, "forbidden import", {MAIN: PURE.replace("import java.math.BigDecimal", "import java.io.File\nimport java.math.BigDecimal")})
    case("java.time.Clock import is red", 1, "forbidden import", {MAIN: PURE.replace("import java.time.Instant", "import java.time.Clock\nimport java.time.Instant")})
    case("Micrometer import is red", 1, "forbidden import", {MAIN: PURE + "import io.micrometer.core.instrument.MeterRegistry\n"})
    case("Instant.now() call is red", 1, "ambient read or console I/O", {MAIN: PURE.replace("b.add(BigDecimal.ONE)", "b.add(BigDecimal.ONE).also { Instant.now() }")})
    case("System.getenv call is red", 1, "ambient read or console I/O", {MAIN: PURE.replace("b.add(BigDecimal.ONE)", "b.add(BigDecimal(System.getenv(\"X\")))")})
    case("println is red", 1, "ambient read or console I/O", {MAIN: PURE.replace("b.add(BigDecimal.ONE)", "b.add(BigDecimal.ONE).also { println(it) }")})
    case("the same import in a COMMENT is not a finding", 0, "product-ir-purity: OK", {MAIN: PURE.replace("class Interp", "// import java.io.File is forbidden here; Instant.now() too\nclass Interp")})
    case("the same import outside the package is not a finding", 0, "product-ir-purity: OK", {MAIN: PURE, "openbank-libs-domain/src/main/kotlin/com/openbank/libs/audit/A.kt": "package com.openbank.libs.audit\nimport java.io.File\nclass A"})
    case("a test source is out of scope", 0, "product-ir-purity: OK", {MAIN: PURE, "openbank-libs-domain/src/test/kotlin/com/openbank/libs/product/ir/T.kt": "package com.openbank.libs.product.ir\nimport java.io.File\nclass T"})
    case("unregistered provider is red", 1, "is not registered", {MAIN: PURE}, provider="com.openbank.libs.detekt.contact.ContactPolicyRuleSetProvider")
    case("rule set missing from detekt.yml is red", 1, "no 'openbank-product-ir-purity:' block", {MAIN: PURE}, config="")
    case("rule set present but inactive is red", 1, "is not 'active: true'", {MAIN: PURE}, config=GOOD_CONFIG.replace("  active: true\n", "  active: false\n", 1))
    case("rule inactive is red", 1, f"{RULE_NAME} is not 'active: true'", {MAIN: PURE}, config=GOOD_CONFIG.replace("    active: true", "    active: false"))
    case("no source in the package is red, never a vacuous pass", 1, "no source under package", {"openbank-libs-domain/src/main/kotlin/com/openbank/libs/audit/A.kt": "package com.openbank.libs.audit\nclass A"})
    case("a pure file with the wiring in place is green", 0, "product-ir-purity: OK — 1 file(s)", {MAIN: PURE})

    print("self-test " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return run(pathlib.Path(args.root).resolve())


if __name__ == "__main__":
    sys.exit(main())
