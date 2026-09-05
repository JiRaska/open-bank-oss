#!/usr/bin/env python3
"""Guard: a JAX-RS `@Path` class annotation must not be hijacked by a top-level function.

WHY THIS EXISTS: Kotlin binds an annotation to the NEXT declaration. A top-level
function placed between `@Path` and its class steals the annotation:

    @Path("/mcp")
    private fun String?.sanitizeForLog(): String = ...

    class McpEndpoint { ... }

The `@Path` binds to the *function*; the class carries none, RESTEasy never registers
the resource, and every call to the endpoint answers 404 on a running pod — while
everything else looks healthy: it compiles, the bean is still in the CDI graph, unit
tests that call the class directly stay green, and sibling endpoints serve normally.
This is not hypothetical: `McpEndpoint` answered 404 for the whole life of the
endpoint (#3371), and admin-ui rendered the healthy service as "not deployed".

A unit test cannot see it (calling a resource class cannot tell a served route from
an unserved one — only real HTTP can), so this is a guard, not a lesson in a doc.

WHAT IT CHECKS: every `openbank-*/src/main/kotlin/**.kt`. Walking top-level
(column-0) declarations only, a `@Path` annotation line opens a pending block that
annotations, blank lines and comments extend. If the declaration that CLOSES the
block is a top-level `fun` (any modifiers), that is a finding: the annotation is
bound to the function and the resource class below it is unregistered. A block
closed by `class` / `object` / `interface` is fine, as is a top-level function that
comes AFTER the class. Method-level `@Path` is indented, so it never enters the
scan; `@ApplicationScoped`-style stacked annotations between `@Path` and its class
are the normal case and stay silent.

ENFORCED: findings are ::error:: annotations and exit 1.

Usage: check-jaxrs-path-hijack.py [--root .]
       check-jaxrs-path-hijack.py --self-test   # prove the gate can fail
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

REPO = Path(__file__).resolve().parents[2]

PATH_ANN = re.compile(r"^@Path\b")
TOPLEVEL_FUN = re.compile(r"^(?:[a-z]+\s+)*fun\b")
TOPLEVEL_TYPE = re.compile(r"^(?:[a-z]+\s+)*(?:class|object|interface)\b")
ANNOTATION = re.compile(r"^@\w")
COMMENT = re.compile(r"^(//|/\*|\*)")


def audit(root: Path) -> tuple[list[str], int]:
    findings: list[str] = []
    examined = 0
    for kt in gatelib.rglob(root, "openbank-*/src/main/kotlin/**/*.kt"):
        examined += 1
        pending_path_line: int | None = None
        for lineno, line in enumerate(gatelib.read_text(kt).splitlines(), start=1):
            if line.startswith((" ", "\t")):
                continue  # inside a declaration — never the annotation's target line
            if PATH_ANN.match(line):
                pending_path_line = lineno
                continue
            if not line.strip() or ANNOTATION.match(line) or COMMENT.match(line):
                continue  # the pending block extends through these
            if TOPLEVEL_FUN.match(line):
                if pending_path_line is not None:
                    findings.append(
                        f"{kt.relative_to(root)}:{pending_path_line}: @Path is followed by the "
                        f"top-level function on line {lineno}, so it binds to the FUNCTION and "
                        f"the resource class below is never registered (the endpoint 404s on a "
                        f"running pod — #3371). Move the function below the class or into it."
                    )
                pending_path_line = None
                continue
            # Any other top-level declaration closes the block.
            pending_path_line = None
    return findings, examined


DEFECT = """package com.openbank.probe

import jakarta.ws.rs.Path

@Path("/mcp")
private fun String?.sanitizeForLog(): String = this ?: ""

class McpEndpoint
"""

CORRECT = """package com.openbank.probe

import jakarta.ws.rs.Path

@Path("/mcp")
class McpEndpoint

private fun String?.sanitizeForLog(): String = this ?: ""
"""

STACKED = """package com.openbank.probe

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Path

@ApplicationScoped
@Path("/things")
class ThingsResource
"""

METHOD_LEVEL = """package com.openbank.probe

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

@Path("/things")
class ThingsResource {
    @GET
    @Path("/{id}")
    fun get() = Unit
}
"""


def self_test() -> int:
    cases = [
        ("@Path stolen by a top-level fun -> MUST fire", DEFECT, True),
        ("fun AFTER the class -> silent", CORRECT, False),
        ("stacked annotations before the class -> silent", STACKED, False),
        ("method-level @Path inside the class -> silent", METHOD_LEVEL, False),
    ]
    failures = 0
    for label, source, want_finding in cases:
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "openbank-probe-service" / "src" / "main" / "kotlin"
            src.mkdir(parents=True)
            (src / "Endpoint.kt").write_text(source)
            got, examined = audit(Path(tmp))
            ok = bool(got) == want_finding and examined == 1
            print(f"  {'ok  ' if ok else 'FAIL'}  {label}")
            if not ok:
                failures += 1
                for g in got:
                    print(f"        got: {g}")
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s)", file=sys.stderr)
        return 1
    print("self-test: PASS — the gate fires on the hijack and is silent on the correct shapes")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    findings, examined = audit(REPO)
    gatelib.subjects(examined, "Kotlin main source files")
    if findings:
        print("JAX-RS @Path annotations bound to a top-level function instead of their class:\n",
              file=sys.stderr)
        for f in findings:
            print(f"::error::{f}", file=sys.stderr)
        print(f"\n{len(findings)} finding(s).", file=sys.stderr)
        return 1
    print("OK: every class-level @Path binds to its class, not to a top-level function.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
