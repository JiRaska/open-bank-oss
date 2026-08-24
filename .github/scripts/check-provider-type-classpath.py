#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""
Assert no shared-library `@Provider` names a type its consumers may not have.

A `@Provider` in openbank-libs-* is registered in EVERY service that depends on the module.
Quarkus/ArC loads each provider bean's type closure at init, so if the type in its supertype —
`ExceptionMapper<T>`, `MessageBodyReader<T>` — comes from a dependency the library declares
`compileOnly`, every consumer WITHOUT that dependency crashes before serving a request:

    ClassNotFoundException: org.hibernate.exception.ConstraintViolationException

That is not hypothetical. #6240 added `DataExceptionMapper : ExceptionMapper<DataException>` and
`ConstraintViolationExceptionMapper` to openbank-libs-runtime with `@Provider`, against a
`compileOnly` Hibernate. agent-service, analytics-sink and ap2-service all failed at ArC init and
d24c84374 had to strip the annotations.

The failure was already documented. A comment five lines above the insertion point said, about
`jakarta.validation.ConstraintViolationException`:

    Auto-registering it here would crash every other service at ArC init with a
    ClassNotFoundException.

The prose was correct, sat exactly where the mistake would be made, and prevented nothing — the
author of #6240 read it and added the same defect below it. That is the case for a check: a rule
this specific, this consequential and this easy to walk past is not a comment's job.

WHAT IS CHECKED
---------------
For every `@Provider` class in a shared library, the type argument of its JAX-RS supertype must
resolve to a package that is on EVERY consumer's runtime classpath:

  * `java.` / `javax.`      — the JDK
  * `kotlin.`               — the Kotlin stdlib, a hard dependency of every module here
  * `jakarta.ws.rs.`        — JAX-RS itself, which the provider mechanism already requires
  * `com.openbank.`         — this repo's own code, shipped in the same jar or a required sibling

Anything else — Hibernate, `jakarta.validation`, Jackson, a Vert.x type — is a package some
service may not carry, and a `@Provider` naming it is a latent boot failure for that service.

WHAT IS NOT CHECKED, AND WHY
----------------------------
Types used only INSIDE a method body. ArC loads the bean's type closure, not every symbol its
code mentions, and a body reference fails at call time in one service rather than at init in all
of them. Widening to bodies would flag `GenericExceptionMapper`'s name-based classification, which
is the SANCTIONED fix for this very problem: it names Hibernate classes as strings precisely so no
type is loaded.

Usage:
    check-provider-type-classpath.py             # gate (exit 1 on a finding)
    check-provider-type-classpath.py --self-test # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REPO = Path(__file__).resolve().parents[2]

# Packages guaranteed present wherever a shared library is.
SAFE_ROOTS = ("java.", "javax.", "kotlin.", "jakarta.ws.rs.", "com.openbank.")

# JAX-RS supertypes ArC resolves the type argument of.
PROVIDER_SUPERTYPES = ("ExceptionMapper", "MessageBodyReader", "MessageBodyWriter", "ParamConverter")

PROVIDER_CLASS = re.compile(
    r"@Provider\s*(?:\n\s*@\w+[^\n]*)*\s*\n\s*(?:open\s+|internal\s+)?class\s+(\w+)[^\n:]*:\s*"
    r"(" + "|".join(PROVIDER_SUPERTYPES) + r")<([^>]+)>",
    re.M,
)
IMPORT = re.compile(r"^import\s+([\w.]+)$", re.M)


def resolve(type_name: str, imports: dict[str, str]) -> str:
    """A written type argument -> its fully-qualified name, as far as the file reveals it."""
    bare = type_name.strip().split("<")[0].strip().removesuffix("?")
    if "." in bare:
        return bare
    return imports.get(bare, bare)


def audit(repo: Path) -> tuple[list[str], int]:
    findings: list[str] = []
    providers = 0
    for module in sorted(repo.glob("openbank-libs-*")):
        src = module / "src" / "main"
        if not src.is_dir():
            continue
        for f in sorted(src.rglob("*.kt")):
            text = gatelib.read_text(f)
            if "@Provider" not in text:
                continue
            imports = {fq.rsplit(".", 1)[-1]: fq for fq in IMPORT.findall(text)}
            for cls, supertype, arg in PROVIDER_CLASS.findall(text):
                providers += 1
                fq = resolve(arg, imports)
                if fq.startswith(SAFE_ROOTS):
                    continue
                if "." not in fq:
                    # Same-package type in this repo's own module — safe by construction.
                    continue
                findings.append(
                    f"{f.relative_to(repo)}: @Provider {cls} : {supertype}<{fq}> — `{fq}` is not on "
                    f"every consumer's runtime classpath, so ArC will fail at init in services "
                    f"without it. Drop @Provider and let services with the dependency register it, "
                    f"or classify by class NAME in GenericExceptionMapper (no type is loaded)."
                )
    return findings, providers


def self_test() -> int:
    cases = [
        ("Hibernate type in an ExceptionMapper -> MUST fire",
         "import jakarta.ws.rs.ext.ExceptionMapper\nimport org.hibernate.exception.DataException\n\n"
         "@Provider\nclass M : ExceptionMapper<DataException> {\n}\n", True),
        ("jakarta.validation shares a simple name and is NOT safe -> MUST fire",
         "import jakarta.ws.rs.ext.ExceptionMapper\nimport jakarta.validation.ConstraintViolationException\n\n"
         "@Provider\nclass M : ExceptionMapper<ConstraintViolationException> {\n}\n", True),
        ("a JDK type -> silent",
         "import jakarta.ws.rs.ext.ExceptionMapper\nimport java.io.CharConversionException\n\n"
         "@Provider\nclass M : ExceptionMapper<CharConversionException> {\n}\n", False),
        ("this repo's own type -> silent",
         "import jakarta.ws.rs.ext.ExceptionMapper\nimport com.openbank.libs.authz.PolicyDecisionException\n\n"
         "@Provider\nclass M : ExceptionMapper<PolicyDecisionException> {\n}\n", False),
        ("the SAME risky type without @Provider -> silent (this is the sanctioned shape)",
         "import jakarta.ws.rs.ext.ExceptionMapper\nimport org.hibernate.exception.DataException\n\n"
         "class M : ExceptionMapper<DataException> {\n}\n", False),
        ("a fully-qualified risky type written inline -> MUST fire",
         "import jakarta.ws.rs.ext.ExceptionMapper\n\n"
         "@Provider\nclass M : ExceptionMapper<org.hibernate.exception.DataException> {\n}\n", True),
    ]
    failures = 0
    for label, body, want in cases:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            d = repo / "openbank-libs-runtime" / "src" / "main" / "kotlin"
            d.mkdir(parents=True)
            (d / "P.kt").write_text(body)
            got, _ = audit(repo)
            ok = bool(got) == want
            print(f"  {'ok  ' if ok else 'FAIL'}  {label}")
            if not ok:
                failures += 1
                for g in got:
                    print(f"        got: {g}")
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s)", file=sys.stderr)
        return 1
    print("self-test: PASS — fires on an absent-classpath type, silent on JDK/own/unregistered")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    findings, providers = audit(REPO)
    gatelib.subjects(providers, "@Provider classes with a typed JAX-RS supertype in openbank-libs-*")
    if findings:
        print("shared-library @Provider naming a type consumers may not have:\n", file=sys.stderr)
        for f in findings:
            print(f"  {f}", file=sys.stderr)
        return 1
    print("OK: every shared-library @Provider names a type present on all consumers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
