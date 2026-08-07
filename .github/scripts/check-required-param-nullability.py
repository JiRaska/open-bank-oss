#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# A missing query/header parameter must answer 400, not 500 (issue #3104).
#
# THE DEFECT
# JAX-RS injects `null` for an absent `@QueryParam`/`@HeaderParam`. Kotlin's null-safety is
# compile-time only, so a non-nullable declared type buys nothing at runtime — it only decides WHERE
# the failure lands, and on a NON-suspend function the compiler emits `Intrinsics.checkNotNullParameter`
# at bytecode offset 0. The NPE is thrown before the first statement of the body, so no guard inside
# the method can ever run, and `GenericExceptionMapper` renders 500. A caller cannot tell "I sent a
# bad request" from "the server is broken", which on a money path decides whether they retry.
#
# WHY ONLY NON-SUSPEND FUNCTIONS
# A `suspend fun` compiles to a different shape and carries no intrinsic at offset 0, so the same
# signature is NOT this defect there. Nor is a parameter carrying `@DefaultValue`: JAX-RS substitutes
# the default, so it is never null however the type reads. Those two exclusions are the whole reason
# this is a gate rather than a sweep — measured on 2026-08-07 the fleet has 49 affected handlers
# against 5 suspend twins and 76 @DefaultValue-protected parameters with an identical-LOOKING
# signature. A mechanical "make every such parameter nullable" pass would touch 130 sites to fix 49,
# changing money-path behaviour for nothing.
#
# The 49 is a CORRECTION. #3104 and #3624 carry the figure 67, published before the @DefaultValue
# exclusion existed; that count was inflated by parameters that can never be null. The gate is the
# authority on the number from here, because it is the only form of the count that is re-derived.
#
# THE FIX SHAPE, ALREADY MERGED AS THE FLEET PRECEDENT (09f32b673, #3625)
#     -  @QueryParam("productId") productId: String,
#     +  @QueryParam("productId") productId: String?,
#     +  requireNotNull(productId) { "query parameter 'productId' is required" }
# `requireNotNull` throws IllegalArgumentException, and `IllegalArgumentExceptionMapper` in
# openbank-libs-runtime already renders that as 400. Nothing new is needed downstream. The commit
# records why the guard has to come with the nullable type: "A guard could only become reachable by
# declaring the parameter nullable — with the old signature it compiled to nothing."
#
# WHY NOT A SHARED ExceptionMapper<NullPointerException> INSTEAD
# It would have to string-match the Kotlin intrinsic's message to avoid swallowing genuine
# null-deref bugs as "bad request". Its correctness would then rest on the text of a compiler
# intrinsic, which nothing here pins and which is free to change between Kotlin versions — silently
# reverting to 500, or worse, answering 400 for real server faults. Evaluated and rejected on #3104.
#
# THE QUARKUS SHORTHAND IS MATCHED TOO
# `@RestQuery` / `@RestHeader` appear ZERO times in the fleet today, so a guard written against the
# JAX-RS spelling alone is complete *now* and silently incomplete the first time someone uses the
# shorthand. Matching both costs one regex alternation.

from __future__ import annotations

import argparse
import pathlib
import re
import sys

PARAM_ANNOTATIONS = ("QueryParam", "HeaderParam", "RestQuery", "RestHeader")
PARAM_RE = re.compile(
    r"@(?:" + "|".join(PARAM_ANNOTATIONS) + r")\s*(?:\(\s*\"(?P<name>[^\"]*)\"\s*\))?"
    # Capture the intervening annotations rather than merely skipping them: @DefaultValue makes the
    # parameter non-null at runtime, so consuming it without inspecting it flags a handler that is
    # already safe. Caught by the self-test before this shipped.
    r"(?P<between>(?:\s*@\w+(?:\([^)]*\))?)*)"
    r"\s*(?P<var>\w+)\s*:\s*(?P<type>[\w.<>]+)(?P<nullable>\?)?\s*(?P<default>=)?"
)
FUN_RE = re.compile(r"(?P<suspend>suspend\s+)?fun\s+(?P<name>\w+)\s*\(")

# Today's affected handlers, measured 2026-08-07 and baselined per service so the gate can be
# ENFORCED now: a NEW one cannot be added quietly while the existing set is worked down per service.
# The numbers are counts, not names, so fixing any handler in a service reduces its count and the
# entry only needs deleting when it reaches zero — a name list would churn on every partial fix.
BASELINE = {
    "openbank-agent-service": 4,
    "openbank-aml-service": 1,
    "openbank-balance-service": 1,
    "openbank-billing-service": 1,
    "openbank-campaign-service": 1,
    "openbank-control-liveness-sentinel": 2,
    "openbank-copilot-service": 1,
    "openbank-customer-edge": 5,
    "openbank-devops-agent": 2,
    "openbank-dispute-service": 2,
    "openbank-document-service": 1,
    "openbank-domestic-payment": 4,
    "openbank-engagement-service": 1,
    "openbank-finops-agent": 2,
    "openbank-finrep-service": 1,
    "openbank-fx-service": 1,
    "openbank-interest-service": 1,
    "openbank-ledger-service": 1,
    "openbank-mcp-service": 1,
    "openbank-notification-service": 1,
    "openbank-party-service": 3,
    "openbank-psd2-service": 1,
    "openbank-sepa-instant": 2,
    "openbank-sepa-payment": 3,
    "openbank-standing-order-service": 1,
    "openbank-statement-service": 5,
}


def strip_comments(src: str) -> str:
    """Remove strings, line comments and NESTED block comments.

    Kotlin block comments nest, so a non-greedy regex would close at the first `*/` and leave the
    tail of a KDoc as live code — the shape that has bitten this repo before (#2450, #3072).
    """
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    src = re.sub(r"//[^\n]*", "", src)
    out: list[str] = []
    depth = i = 0
    while i < len(src):
        if src.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if src.startswith("*/", i) and depth:
            depth -= 1
            i += 2
            continue
        if not depth:
            out.append(src[i])
        i += 1
    return "".join(out)


def _param_list(src: str, open_paren: int) -> str:
    """The text between a function's parentheses, brace-matched (a default value may contain `(`)."""
    depth = 0
    for i in range(open_paren, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[open_paren:i]
    return ""


def offending_handlers(source: str) -> list[str]:
    """Names of NON-suspend functions taking a non-nullable, defaultless param annotation.

    Takes RAW source and strips it here: a seam where the caller is trusted to sanitise is a seam
    where one forgetful caller counts a KDoc as code.
    """
    src = strip_comments(source)
    found: list[str] = []
    for m in FUN_RE.finditer(src):
        params = _param_list(src, m.end() - 1)
        for p in PARAM_RE.finditer(params):
            if p.group("nullable") or p.group("default"):
                continue  # `String?` is handled, and `= x` supplies a value
            if "@DefaultValue" in (p.group("between") or ""):
                continue  # JAX-RS substitutes the default, so the parameter is never null
            if m.group("suspend"):
                continue  # different codegen: no intrinsic at offset 0, so not this defect
            found.append(m.group("name"))
            break
    return found


def scan(root: pathlib.Path) -> dict[str, int]:
    """service -> number of affected handlers."""
    counts: dict[str, int] = {}
    for svc in sorted(p for p in root.glob("openbank-*") if p.is_dir()):
        main = svc / "src" / "main" / "kotlin"
        if not main.is_dir():
            continue
        n = 0
        for kt in main.rglob("*.kt"):
            try:
                n += len(offending_handlers(kt.read_text(encoding="utf-8", errors="replace")))
            except OSError:
                continue
        if n:
            counts[svc.name] = n
    return counts


def self_test() -> int:
    fails = 0

    def expect(name, got, want):
        nonlocal fails
        if got == want:
            print(f"  ok   {name}")
        else:
            print(f"  FAIL {name}: want {want}, got {got}")
            fails = 1

    # The known-positive: InterestResource.capitalize exactly as it read before 09f32b673 fixed it.
    known_positive = """
    @POST
    @Authorize(action = "interest.create", resource = "#accountId")
    fun capitalize(
        @PathParam("accountId") accountId: UUID,
        @QueryParam("productId") productId: String,
        @QueryParam("toDate") toDate: String?,
    ): Uni<Response> = use.capitalize(accountId, productId)
    """
    expect("the #3104 worked example is flagged", offending_handlers(known_positive), ["capitalize"])

    fixed = known_positive.replace('productId: String,', 'productId: String?,')
    expect("…and its merged fix is not", offending_handlers(fixed), [])

    expect("a suspend fun with the same signature is NOT this defect",
           offending_handlers("suspend fun f(@QueryParam(\"a\") a: String) {}"), [])
    expect("a nullable parameter is fine",
           offending_handlers("fun f(@QueryParam(\"a\") a: String?) {}"), [])
    expect("@DefaultValue supplies a value, so it is fine",
           offending_handlers('fun f(@QueryParam("a") @DefaultValue("1") a: String) {}'), [])
    expect("a Kotlin default supplies a value too",
           offending_handlers('fun f(@QueryParam("a") a: String = "x") {}'), [])
    expect("@HeaderParam counts the same as @QueryParam",
           offending_handlers('fun f(@HeaderParam("X-A") a: String) {}'), ["f"])
    expect("the Quarkus @RestQuery shorthand is matched (zero uses today, not zero forever)",
           offending_handlers('fun f(@RestQuery a: String) {}'), ["f"])
    expect("@PathParam is NOT in scope — a path param cannot be absent from a matched route",
           offending_handlers('fun f(@PathParam("id") id: UUID) {}'), [])
    expect("a KDoc showing the bad shape is not a handler",
           offending_handlers('/** fun f(@QueryParam("a") a: String) */\nfun g() {}'), [])
    expect("a default value containing a paren does not truncate the parameter list",
           offending_handlers('fun f(@QueryParam("a") a: String = def(1), @QueryParam("b") b: String) {}'),
           ["f"])
    expect("one function is reported once even with two offending params",
           offending_handlers('fun f(@QueryParam("a") a: String, @QueryParam("b") b: String) {}'), ["f"])

    if fails:
        print("check-required-param-nullability: self-test FAIL")
        return 1
    print("check-required-param-nullability: self-test PASS")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    counts = scan(pathlib.Path(args.root))
    total = sum(counts.values())
    regressions = {s: n for s, n in counts.items() if n > BASELINE.get(s, 0)}
    stale = {s: n for s, n in BASELINE.items() if counts.get(s, 0) < n}

    for svc, n in sorted(regressions.items()):
        base = BASELINE.get(svc, 0)
        print(
            f"::error::{svc}: {n} handler(s) take a non-nullable @QueryParam/@HeaderParam on a "
            f"non-suspend function (baseline {base}). A missing parameter throws "
            f"Intrinsics.checkNotNullParameter before the body runs, so the caller gets 500 instead "
            f"of 400. Declare it nullable and requireNotNull it — see 09f32b673 (#3104)."
        )
    for svc, was in sorted(stale.items()):
        print(
            f"::error::{svc}: baselined at {was} affected handler(s) but now has "
            f"{counts.get(svc, 0)}. Lower or remove its BASELINE entry so the list keeps meaning "
            f"something."
        )

    print(
        f"check-required-param-nullability: {total} affected handler(s) across {len(counts)} "
        f"service(s); {sum(BASELINE.values())} baselined, {len(regressions)} over baseline, "
        f"{len(stale)} stale baseline entr{'y' if len(stale) == 1 else 'ies'}."
    )
    return 1 if (regressions or stale) else 0


if __name__ == "__main__":
    sys.exit(main())
