#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# A required @QueryParam / @HeaderParam / @MatrixParam declared with a NON-NULLABLE Kotlin
# reference type answers 500 when the caller omits it, never 400 (issue #3104).
#
# THE MECHANISM, AND WHY THE OBVIOUS FIX IS DEAD CODE
#   JAX-RS injects `null` for an absent parameter. Kotlin's null-safety is compile-time only, so the
#   declared non-nullable type buys nothing at runtime — it only decides WHERE the failure lands:
#
#     fun f(@QueryParam("p") p: String)          -> Intrinsics.checkNotNullParameter at bytecode
#                                                   offset 0. NPE before the first statement of the
#                                                   body, every time. GenericExceptionMapper -> 500.
#
#     suspend fun f(@QueryParam("p") p: String)  -> NO intrinsic is emitted at all (verified against
#                                                   the 2.3.21 compiler with javap, for suspending
#                                                   and non-suspending bodies alike). `null` flows
#                                                   into the body and fails at the first dereference,
#                                                   usually still a 500 — but if nothing dereferences
#                                                   it, the request silently proceeds with a null the
#                                                   signature promised could not exist. That second
#                                                   outcome is worse than the 500, and is invisible.
#
#   The consequence that traps people: writing `requireNotNull(p)` in the body of a NON-suspend
#   handler compiles to nothing. The intrinsic has already thrown. The parameter MUST be declared
#   nullable for any guard to be reachable:
#
#       @QueryParam("productId") productId: String?,       // then
#       requireNotNull(productId) { "query parameter 'productId' is required" }
#
#   libs-runtime's CommonExceptionMappers maps IllegalArgumentException to 400 with the ApiError
#   envelope and a traceId, so that is the whole fix. Do NOT add a service-local ExceptionMapper for
#   a JDK type — two mappers for one type are selected at random per request (issue #526).
#
# WHY IT MATTERS ENOUGH TO GATE
#   A client cannot tell "I sent a bad request" from "the server is broken", and on a money path that
#   difference decides whether the caller retries. 5xx also drives alerting and burns SLO error
#   budget, so the authenticated-fuzz lane reports these forever while they stay 5xx.
#
# WHAT IS AND IS NOT A FINDING
#   flagged   an INBOUND JAX-RS resource (a `class`) whose param is a non-nullable reference type
#             with no @DefaultValue and no Kotlin default value
#   allowed   nullable type (`String?`)              -- the handler already owns the absent case
#   allowed   @DefaultValue("...")                   -- JAX-RS never injects null
#   allowed   a Kotlin default (`p: String = "x"`)   -- same
#   allowed   a JVM primitive (`Int`, `Long`, ...)   -- JAX-RS supplies 0/false, not null. A
#             different defect class, already mapped by DateTimeExceptionMapper (#3057).
#   allowed   an OUTBOUND MicroProfile REST client (an `interface`) -- the annotation describes the
#             request this service SENDS. The argument comes from our own Kotlin call site, which is
#             compile-time checked, and an abstract method has no body to hold an intrinsic. 52 of
#             the 115 raw matches on main are this, which is why a naive grep over-counts by ~2x.
#
# RATCHET, NOT A FLAT FAIL
#   Remediation is genuinely per-handler — required (400 naming the parameter), defaultable
#   (@DefaultValue), or actually optional (nullable) — and only the handler knows which. The
#   money-path set is fixed; the remainder is BASELINED with its issue so a NEW occurrence fails
#   while the known tail stays visible. A baseline entry that no longer occurs is reported too, so
#   the list cannot rot in either direction.
#
# EXIT CODES
#   0  no new occurrences, no stale baseline entries
#   1  a new occurrence, or a baseline entry that is now fixed and should be removed
#   2  the check could not run, or the self-test failed. Never conflated with 0.
#
# Run:  python3 .github/scripts/check-nonnull-jaxrs-params.py [--root .] [--self-test] [--list]

import argparse
import pathlib
import re
import sys

import gatelib

PARAM_ANNOTATIONS = ("QueryParam", "HeaderParam", "MatrixParam")

# JVM primitives: JAX-RS supplies the zero value, never null, so no NPE is possible here.
KOTLIN_PRIMITIVES = {"Int", "Long", "Double", "Float", "Boolean", "Short", "Byte", "Char"}

# EMPTY, and it stays that way — #3624 closed the tail (aml, party, pid ×3, tpp-registry ×2), the
# last of which were `suspend` handlers where no `Intrinsics.checkNotNullParameter` is emitted, so
# the null flowed into the body instead of failing at offset 0. Each was fixed by declaring the
# parameter nullable and either widening its existing guard to `isNullOrBlank()` (preserving the
# service's own error envelope) or adding `requireNotNull` — libs-runtime maps
# IllegalArgumentException to 400; never a service-local mapper (#526).
#
# An entry added here needs a reason and an issue. The check reports a baseline key that no longer
# occurs as well as a new occurrence, so the list cannot rot in either direction — do not add one
# to silence a finding you have not read.
#
# Each entry would be "<service>|<Class>|<param>".
BASELINE: set[str] = set()


def strip_comments(src: str) -> str:
    """Remove Kotlin comments while preserving line numbers and string literals.

    Kotlin block comments NEST, so a KDoc containing `/*` does not end at the first `*/`. Getting
    this wrong closes the comment early and leaks prose into the scan — which is how a guard ends up
    flagging the very KDoc that explains the bug it exists to catch.
    """
    out = []
    i, n, depth = 0, len(src), 0
    while i < n:
        if depth:
            if src.startswith("/*", i):
                depth += 1
                i += 2
            elif src.startswith("*/", i):
                depth -= 1
                i += 2
            else:
                if src[i] == "\n":
                    out.append("\n")
                i += 1
            continue
        if src.startswith("/*", i):
            depth = 1
            i += 2
        elif src.startswith("//", i):
            j = src.find("\n", i)
            if j == -1:
                break
            out.append("\n")
            i = j + 1
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j == -1 else j + 3
            out.append(src[i:j])
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1])
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


ANNOTATION_RE = re.compile(
    r"@(" + "|".join(PARAM_ANNOTATIONS) + r")\s*\(\s*\"([^\"]+)\"\s*\)",
)
# TOP-LEVEL declarations only — the leading `(?![ \t])` is load-bearing, not tidiness.
#
# The enclosing declaration supplies the middle field of the BASELINE key, so getting the NAME
# wrong silently rekeys a handler. The old pattern let `[\w@\s]*?` swallow indentation, so a
# nested type declared inside a resource became the enclosing declaration for every handler
# BELOW it. Measured on openbank-customer-edge: a `private sealed interface DelegatedCardParse`
# with a `data class Bad` member re-keyed `listDisputes` from
# `openbank-customer-edge|CustomerEdgeResource|accountId` (baselined) to
# `openbank-customer-edge|Bad|accountId`, which the gate then reported as a NEW occurrence on a
# PR that had not touched a single JAX-RS parameter.
#
# Both directions are possible and the quiet one is worse: a false NEW is at least loud, while a
# handler re-keyed onto some OTHER baselined key would be waved through. Restricting to column 0
# keeps the resource-plus-client case working (both are top-level, so "last wins" still resolves
# correctly) and makes nested types invisible, which is what the key wants.
DECL_RE = re.compile(r"^(?![ \t])(?:[\w@]+[ \t]+)*?\b(interface|class|object)[ \t]+(\w+)", re.M)


def enclosing_type(src: str, offset: int):
    """(kind, name) of the class/interface/object lexically enclosing `offset`.

    `kind` is the discriminator that matters: an `interface` is an outbound MicroProfile REST
    client, a `class` is an inbound resource. Files that hold both (a resource plus its client)
    resolve correctly because the LAST declaration before the offset wins.

    Callers must exclude on `== "interface"`, NOT on `!= "class"`. A `companion object` declared
    above a handler inside a resource class resolves to `object`, and excluding everything that is
    not a `class` would then skip that handler silently — a false GREEN, which is the direction
    that never announces itself. There are 0 such sites today; the point is that adding one must
    not disarm the gate.
    """
    last = None
    for m in DECL_RE.finditer(src, 0, offset):
        last = m
    return (last.group(1), last.group(2)) if last else ("?", "?")


def scan_source(src: str, service: str, path: str):
    """Yield findings for one already-comment-stripped Kotlin source."""
    for m in ANNOTATION_RE.finditer(src):
        kind, param = m.group(1), m.group(2)
        tail = src[m.end():]

        # Consume any further annotations on the same parameter; @DefaultValue among them means
        # JAX-RS never injects null.
        j, has_default_ann = 0, False
        while True:
            nxt = re.match(r"\s*@(\w+)\s*(\([^()]*\))?", tail[j:])
            if not nxt:
                break
            if nxt.group(1) == "DefaultValue":
                has_default_ann = True
            j += nxt.end()

        decl = re.match(r"\s*(?:vararg\s+)?(?:va[lr]\s+)?(\w+)\s*:\s*([^,)=]+)", tail[j:])
        if not decl:
            continue
        declared_type = decl.group(2).strip()
        has_kotlin_default = tail[j + decl.end():].lstrip().startswith("=")

        if declared_type.endswith("?") or declared_type in KOTLIN_PRIMITIVES:
            continue
        if has_default_ann or has_kotlin_default:
            continue

        encl_kind, encl_name = enclosing_type(src, m.start())
        if encl_kind == "interface":
            continue  # outbound REST client — the caller supplies the argument

        yield {
            "key": f"{service}|{encl_name}|{param}",
            "file": path,
            "line": src.count("\n", 0, m.start()) + 1,
            "annotation": kind,
            "param": param,
            "type": declared_type,
            "kotlin_name": decl.group(1),
        }


def scan(root: str):
    """(findings, files_walked). The walked count is returned, not just the findings, so the
    caller can tell 'no handler declares a non-nullable param' from 'no handler was read'."""
    base = pathlib.Path(root)
    findings = []
    walked = 0
    for p in sorted(base.rglob("*.kt")):
        s = str(p)
        if "/src/test/" in s or "/build/" in s or "/src/nativeTest/" in s:
            continue
        walked += 1
        raw = p.read_text(encoding="utf-8", errors="replace")
        if not any(a in raw for a in PARAM_ANNOTATIONS):
            continue
        rel = str(p.relative_to(base)) if p.is_relative_to(base) else s
        service = rel.split("/")[0]
        findings.extend(scan_source(strip_comments(raw), service, rel))
    return findings, walked


# --- self-test ---------------------------------------------------------------------------------
# Every branch of the classifier gets a case, and the must-NOT-flag half is the half that matters:
# a guard that flags every param annotation is noise, and one that cannot tell an inbound resource
# from an outbound client over-counts by roughly 2x on this repo.
SELF_TEST_SOURCE = '''
package com.openbank.demo

/**
 * Prose that names @QueryParam("prose_only") p: String deliberately — a guard that greps the file
 * instead of the construct flags this KDoc. Block comments NEST: /* like this */ and the scan must
 * still be inside a comment right here, where @HeaderParam("nested_prose") h: String appears.
 */
@Path("/api/v1/demo")
class DemoResource {
    fun flagged(@QueryParam("flag_plain") a: String): Response = TODO()

    suspend fun flaggedSuspend(@HeaderParam("flag_header") b: String): Response = TODO()

    fun flaggedEnum(@QueryParam("flag_enum") c: DemoKind): Response = TODO()

    fun allowedNullable(@QueryParam("ok_nullable") d: String?): Response = TODO()

    fun allowedDefaultAnn(@QueryParam("ok_default_ann") @DefaultValue("x") e: String): Response = TODO()

    fun allowedKotlinDefault(@QueryParam("ok_kotlin_default") f: String = "x"): Response = TODO()

    fun allowedPrimitive(@QueryParam("ok_primitive") g: Int): Response = TODO()

    // A NAMED nested object above a handler must not disarm the check for handlers below it. With
    // the exclusion written as `!= "class"` this site resolves to `object` and is silently skipped
    // — a false GREEN, the direction that never announces itself. Excluding only `interface` keeps
    // it flagged. (An unnamed `companion object` is NOT the hazard: DECL_RE requires a name after
    // the keyword, so it never becomes the enclosing declaration. Measured — the first version of
    // this fixture used `companion object` and passed against the broken exclusion too, i.e. it
    // proved nothing.)
    object Headers {
        const val OPERATOR = "X-Operator-Id"
    }

    fun flaggedAfterNestedObject(@QueryParam("flag_after_object") i: String): Response = TODO()

    // A nested TYPE must not become the enclosing declaration for handlers below it. Being flagged
    // is not enough here: the enclosing NAME is the middle field of the BASELINE key, so a handler
    // re-keyed onto a nested type reads as a NEW occurrence forever (or, worse, silently collides
    // with some other baselined key). SELF_TEST_EXPECTED_KEYS below asserts the key, which is why
    // the pre-existing `object Headers` fixture could not catch this: it only ever checked which
    // params were flagged.
    private sealed interface Parse {
        data class Bad(val response: Response) : Parse
    }

    fun flaggedAfterNestedType(@QueryParam("flag_after_nested_type") j: String): Response = TODO()

    // A commented-out declaration must not count:
    // fun commentedOut(@QueryParam("comment_only") z: String): Response = TODO()
    fun stringLiteralIsNotADecl(): String = "@QueryParam(\\"literal_only\\") y: String"
}

@RegisterRestClient
@Path("/api/v1/other")
interface DemoClient {
    fun outbound(@QueryParam("ok_outbound") h: String): Uni<String>
}
'''

SELF_TEST_EXPECTED_FLAGGED = {
    "flag_plain", "flag_header", "flag_enum", "flag_after_object", "flag_after_nested_type",
}

# Every flagged handler in the fixture belongs to the top-level resource, whatever nested types
# sit above it. Asserting the KEY and not just the param is the whole point — the key is what
# BASELINE matches on.
SELF_TEST_EXPECTED_KEYS = {f"demo|DemoResource|{p}" for p in SELF_TEST_EXPECTED_FLAGGED}
SELF_TEST_EXPECTED_ALLOWED = {
    "prose_only", "nested_prose", "ok_nullable", "ok_default_ann", "ok_kotlin_default",
    "ok_primitive", "comment_only", "literal_only", "ok_outbound",
}


def self_test() -> int:
    found = list(scan_source(strip_comments(SELF_TEST_SOURCE), "demo", "Demo.kt"))
    flagged = {f["param"] for f in found}
    failures = 0
    for want in sorted(SELF_TEST_EXPECTED_FLAGGED):
        ok = want in flagged
        print(f"{'pass' if ok else 'FAIL'}  must flag   {want}")
        failures += 0 if ok else 1
    for want in sorted(SELF_TEST_EXPECTED_ALLOWED):
        ok = want not in flagged
        print(f"{'pass' if ok else 'FAIL'}  must allow  {want}")
        failures += 0 if ok else 1

    unexpected = flagged - SELF_TEST_EXPECTED_FLAGGED
    if unexpected:
        print(f"FAIL  flagged something the fixture did not declare: {sorted(unexpected)}")
        failures += 1
    else:
        print("pass  no findings outside the declared fixture set")

    keys = {f["key"] for f in found}
    if keys == SELF_TEST_EXPECTED_KEYS:
        print("pass  every finding is keyed to the top-level resource, not a nested type")
    else:
        print(f"FAIL  wrong BASELINE keys: {sorted(keys - SELF_TEST_EXPECTED_KEYS)} "
              f"(missing {sorted(SELF_TEST_EXPECTED_KEYS - keys)})")
        failures += 1

    total = len(SELF_TEST_EXPECTED_FLAGGED) + len(SELF_TEST_EXPECTED_ALLOWED) + 2
    print(f"\nself-test: {total - failures} passed, {failures} failed")
    return 0 if failures == 0 else 2


def main() -> int:
    ap = argparse.ArgumentParser(description="Guard non-nullable required JAX-RS params (#3104)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list", action="store_true", help="print every finding, baselined or not")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    if not root.is_dir():
        print(f"::error::--root {args.root} is not a directory — the check could not run. NOT a pass.")
        return 2

    findings, walked = scan(args.root)
    gatelib.subjects(walked, "non-test .kt files walked")
    if args.list:
        for f in sorted(findings, key=lambda x: (x["file"], x["line"])):
            mark = "baselined" if f["key"] in BASELINE else "NEW"
            print(f"{mark:9} {f['file']}:{f['line']} @{f['annotation']}(\"{f['param']}\") "
                  f"{f['kotlin_name']}: {f['type']}")
        print(f"\n{len(findings)} finding(s), {len(BASELINE)} baseline key(s)")
        return 0

    seen = {f["key"] for f in findings}
    new = [f for f in findings if f["key"] not in BASELINE]
    stale = sorted(BASELINE - seen)

    for f in sorted(new, key=lambda x: (x["file"], x["line"])):
        print(
            f"::error file={f['file']},line={f['line']}::"
            f"@{f['annotation']}(\"{f['param']}\") is declared {f['kotlin_name']}: {f['type']} — "
            f"non-nullable with no @DefaultValue, so an absent value answers 500, not 400. "
            f"Declare it nullable and guard it "
            f"(requireNotNull(...) {{ \"...\" }}); libs-runtime maps that to 400. "
            f"A guard in the body of a NON-suspend handler is dead code — the Kotlin intrinsic "
            f"throws first. See issue #3104.",
        )
    for k in stale:
        print(
            f"::error::STALE baseline entry {k} no longer occurs — it is fixed, or the resource is "
            f"gone. Remove it from BASELINE in this script so the list keeps meaning something.",
        )

    if new or stale:
        print(f"\n{len(new)} new occurrence(s), {len(stale)} stale baseline entr(ies).")
        return 1
    print(
        f"non-nullable JAX-RS params: OK — {len(findings)} baselined occurrence(s), no new ones. "
        f"The money-path set was fixed in #3104; the tail is per-handler work tracked there.",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
