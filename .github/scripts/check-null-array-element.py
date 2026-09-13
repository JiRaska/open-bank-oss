#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# A null JSON array element is a 500, not a 400 — the body-entity arm of the null-safety class
# (issue #7867).
#
# THE MECHANISM
#   Jackson's Kotlin module null-checks a data class's CONSTRUCTOR PARAMETERS. It does NOT check
#   the ELEMENTS of a collection. So a request DTO declared
#
#       data class PostJournalRequest(val lines: List<PostJournalLineRequest>)
#
#   deserialises `{"lines": [null]}` into a List holding a null; Kotlin's non-null element type is
#   a compile-time promise nothing keeps. The handler dereferences the element, throws
#   NullPointerException, and GenericExceptionMapper renders a 500 where a 400 belongs (ADR-0080).
#   A `requireNotNull` written against a non-nullable element type is DEAD CODE — the element must
#   be declared nullable (`List<X?>`) for any guard to be reachable, with libs-runtime's
#   CommonExceptionMappers mapping the IllegalArgumentException to 400.
#
#   This is the same mechanism as check-nonnull-jaxrs-params.py (#3104, #3624), one level up: that
#   gate covers @QueryParam/@HeaderParam/@MatrixParam and has never looked at body entities or
#   collection elements, which is exactly how 23 occurrences of this class spread underneath a
#   green gate (#7867). 21 of the 23 were also invisible to the fuzz lane that was specifically
#   looking for them — detection here is structural or it is nothing.
#
# WHAT IS AND IS NOT A FINDING
#   flagged   a property of type List<E> / Set<E> / Collection<E> / MutableList<E> / Map<K, V>
#             with a NON-NULLABLE element/value type E (resp. V), on a data class transitively
#             reachable from the body parameter of an INBOUND JAX-RS resource method
#             (@POST/@PUT/@PATCH on a `class` carrying @Path)
#   allowed   a nullable element type (`List<X?>`) — the guard can be written and reached
#   allowed   DTOs not reachable from any inbound body parameter (outbound clients, events,
#             entities) — never deserialised from a caller's JSON
#   allowed   an outbound REST-client `interface` — never scanned as a resource: the annotation
#             describes the request this service SENDS, compile-time checked at our own call site
#
# RATCHET, NOT A FLAT FAIL
#   The 23 confirmed occurrences from #7867 are all fixed (nullable elements, guards, red-first
#   tests). What remains reachable-but-cleared (element never read on the request path, or a null
#   silently dropped — latent, not clean) sits in BASELINE with its reason, so a NEW occurrence
#   fails while the known ones stay visible; a baseline entry that no longer occurs is reported
#   too, so the list cannot rot in either direction (the KNOWN_UNCOVERED shape used by
#   check-pact-provider-replay.py).
#
# KNOWN LIMITS (recorded so a green run is not misread as a census)
#   * DTO properties declared in a class BODY rather than the primary constructor are not parsed
#     (none were observed among request DTOs in the #7867 inventory).
#   * Endpoints taking a raw Map<String, Any?> body are outside this class (already nullable) and
#     are a separate untyped-input surface.
#   * A simple-name collision of two data classes INSIDE one module makes that name ambiguous; it
#     is skipped and counted in the summary, never silently resolved.
#   * Cross-module DTO references are not resolved: a body DTO defined in another module (rare;
#     the #7867 inventory had none) is invisible here, not cleared.
#
# Run `--self-test` to falsify the parser against the shapes that must and must not flag.
#
# Usage:
#     check-null-array-element.py [--root .] [--self-test]
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # noqa: E402  (path shim above must run first)

LINE_COMMENT = re.compile(r"//[^\n]*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')
DATA_CLASS = re.compile(r"\bdata class\s+(\w+)\s*\(")
RESOURCE_CLASS = re.compile(r"\bclass\s+\w+")
INTERFACE = re.compile(r"\binterface\s+\w+")
PATH_ANNOTATION = re.compile(r"@Path\b")
HTTP_METHOD = re.compile(r"@(POST|PUT|PATCH)\b")
FUN_DECL = re.compile(r"\b(?:suspend\s+)?fun\s+\w+\s*\(")
COLL_TYPE = re.compile(r"^(?:Mutable)?(?:List|Set|Collection|Map)\s*<(.+)>$", re.S)

# Reachable-but-cleared properties, each with the reason from its #7867 inspection.
# Format: "service:ClassName.property" -> reason.
# Two honest categories, and they are NOT equivalent (#7867 names all three patterns):
#   * ALREADY-CORRECT-400 — a `collection.all { it in allowed }` guard: Set/List.contains(null)
#     is false, `require` throws IllegalArgumentException, libs-runtime renders 400 today.
#   * LATENT — the element is never dereferenced on the request path (stored wholesale, or a
#     null silently dropped/persisted). No 500, but the request can succeed having quietly
#     ignored or persisted input the caller sent. Not clean; cleared for THIS gate's defect.
BASELINE: dict[str, str] = {
    # --- already correct 400 (contains-based guard; verified against the use) ---
    "openbank-consent-service:CreateConsentRequest.scopes":
        "#7867 — already 400: ConsentService requires `scopes.all { it in GDPR_ONLY_SCOPES }`; "
        "contains(null) is false, so a null element fails the require as a client error.",
    "openbank-delegation-service:DelegationRolePresetRequest.capabilities":
        "#7867 — already 400: DelegationRolePreset requires `capabilities.all { it in allowed }`.",
    "openbank-delegation-service:OfferDelegationRequest.capabilities":
        "#7867 — already 400: DelegationGrant requires `capabilities.all { it in allowed }`.",
    "openbank-delegation-service:PreviewDelegationRequest.capabilities":
        "#7867 — already 400: same DelegationGrant guard on the preview path.",
    "openbank-flaky-test-hunter:TestIntelligenceComponentInput.declaredInfrastructure":
        "#7867 — already 400: FlakyTestHunterService requires "
        "`declaredInfrastructure.all { it in INFRASTRUCTURE }`.",
    "openbank-sdd-service:DebtorControlsDto.blockedCreditors":
        "#7867 — no dereference of the ELEMENT: the only read is "
        "`instruction.creditorIdentifier in controls.blockedCreditors`; contains(null) is false, "
        "no NPE, and a null entry can never match a real creditor.",
    # --- latent (never dereferenced on the request path; a null is silently kept/dropped) ---
    "openbank-campaign-service:StepRequest.variables":
        "#7867 — latent: forwarded wholesale into the notification send request; a null map "
        "value travels onward rather than failing here.",
    "openbank-delegation-service:ExposureDto.redactionRules":
        "#7867 — latent: stored wholesale onto DelegationGrantEntity.redactionRules.",
    "openbank-incentive-service:CreateOfferRequest.productScope":
        "#7867 — latent: joinToString(\"\\u001f\") with no selector; a null element persists as "
        "the literal \"null\" segment.",
    "openbank-mcp-service:CreateSessionRequest.roleCeiling":
        "#7867 — latent: persisted as a JSON array string; parseCeiling on read drops what it "
        "cannot use. A null is silently dropped, never rejected.",
    "openbank-pid-service:TransitionCaseRequest.metadata":
        "#7867 — latent: stored wholesale; a null map value is persisted.",
    "openbank-pid-service:ResolvePartyRequest.nationalities":
        "#7867 — latent: joinToString on persist; a null element persists as \"null\".",
    "openbank-pid-service:CreatePartyRequest.nationalities":
        "#7867 — latent: same joinToString persistence path.",
    "openbank-pid-service:RegisterIdentityRequest.nationalities":
        "#7867 — latent: same joinToString persistence path.",
    "openbank-pid-service:SyncFromBankIdRequest.nationalities":
        "#7867 — latent: same joinToString persistence path.",
    "openbank-product-catalog:CardConfig.networks":
        "#7867 — latent: stored wholesale, never dereferenced on the request path.",
    "openbank-product-catalog:CardConfig.tiers":
        "#7867 — latent: stored wholesale.",
    "openbank-product-catalog:CardConfig.eligibilitySegments":
        "#7867 — latent: stored wholesale.",
    "openbank-sanctions-service:ScreenEntityCommand.identifiers":
        "#7867 — latent: serialized wholesale into the jsonb column; a null map VALUE persists "
        "and poisons the typed read-back, but nothing on the request path dereferences it.",
    "openbank-security-scanner:ReportIncidentRequest.affectedServices":
        "#7867 — latent: joinToString into a TEXT column; a null element persists as \"null\".",
}


def strip_noise(text: str) -> str:
    """Comments and string literals out — KDoc commas otherwise corrupt the constructor split
    (a measured defect of the #7867 scanner), and a string default can hide a `)`."""
    return STRING_LITERAL.sub('""', LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text)))


def _balanced(text: str, open_paren: int) -> str:
    """The substring between the `(` at `open_paren` and its match (exclusive)."""
    depth = 0
    for i in range(open_paren, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : i]
    return ""


def _split_top_level(body: str) -> list[str]:
    """Split a parameter list on top-level commas (angle-bracket and paren aware)."""
    out, depth, cur = [], 0, []
    for c in body:
        if c in "(<":
            depth += 1
        elif c in ")>":
            depth -= 1
        if c == "," and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    tail = "".join(cur).strip()
    if tail:
        out.append("".join(cur))
    return out


def _props_of(body: str) -> dict[str, str]:
    """`val name: Type` pairs from a primary-constructor parameter list."""
    props: dict[str, str] = {}
    for part in _split_top_level(body):
        pm = re.search(r"\bval\s+(\w+)\s*:\s*(.+)$", part.strip(), re.S)
        if pm:
            props[pm.group(1)] = pm.group(2).split("=")[0].strip()
    return props


class DataClassInfo:
    def __init__(self, name: str, props: dict[str, str]):
        self.name = name
        self.props = props  # property name -> declared type (noise-stripped)


def index_data_classes_text(text: str) -> dict[str, DataClassInfo | None]:
    """Simple-name -> data class (None marks an ambiguous name, colliding within the input)."""
    index: dict[str, DataClassInfo | None] = {}
    for m in DATA_CLASS.finditer(text):
        name = m.group(1)
        if name in index:
            index[name] = None
        else:
            index[name] = DataClassInfo(name, _props_of(_balanced(text, m.end() - 1)))
    return index


def index_data_classes(module_root: Path) -> tuple[dict[str, DataClassInfo], int]:
    index: dict[str, DataClassInfo | None] = {}
    ambiguous = 0
    for kt in sorted(module_root.rglob("*.kt")):
        for name, dc in index_data_classes_text(
            strip_noise(kt.read_text(encoding="utf-8", errors="replace"))
        ).items():
            if name in index:
                if index[name] is not None:
                    ambiguous += 1
                index[name] = None
            elif dc is not None:
                index[name] = dc
    return {k: v for k, v in index.items() if v is not None}, ambiguous


def body_param_types_text(text: str) -> set[str]:
    """Simple type names of unannotated parameters on @POST/@PUT/@PATCH methods of an inbound
    resource class. The HTTP-method annotation must precede the fun with no other fun between —
    enforced by re-checking the window after the previous `fun `.

    A file can hold BOTH an outbound client (`@Path interface SanctionsServiceClient`) and plain
    data classes, so "the file mentions @Path and some class" is not enough (measured: fx's
    SanctionsServiceClient.kt false-positived exactly that way). Each fun is owned by the nearest
    preceding type declaration, and is in scope only when that declaration is a `class` preceded
    by @Path.
    """
    out: set[str] = set()
    if "@Path" not in text:
        return out
    # Type declarations with their positions and (kind, has-@Path).
    decls = []  # (pos, is_class, has_path)
    for tm in re.finditer(r"\b(class|interface)\s+\w+", text):
        head = text[max(0, tm.start() - 300) : tm.start()]
        decls.append((tm.start(), tm.group(1) == "class", bool(PATH_ANNOTATION.search(head))))
    for fm in FUN_DECL.finditer(text):
        owner = None
        for pos, is_class, has_path in decls:
            if pos < fm.start():
                owner = (is_class, has_path)
            else:
                break
        if owner is None or owner != (True, True):
            continue
        window = text[max(0, fm.start() - 600) : fm.start()]
        # Nothing between the HTTP annotation and this fun except other annotations/comments:
        # cut the window at the last `fun ` so a stale @POST from an earlier method can't leak in.
        window = window[window.rfind("fun ") + 4 :] if "fun " in window else window
        if not HTTP_METHOD.search(window):
            continue
        for part in _split_top_level(_balanced(text, fm.end() - 1)):
            part = part.strip()
            if not part or part.startswith("@"):
                continue
            pm = re.search(r"\b\w+\s*:\s*([\w.]+)\??", part)
            if pm:
                out.add(pm.group(1).split(".")[-1])
    return out


def body_param_types(module_root: Path) -> set[str]:
    out: set[str] = set()
    for kt in sorted(module_root.rglob("*.kt")):
        raw = kt.read_text(encoding="utf-8", errors="replace")
        if "@Path" not in raw:
            continue
        out |= body_param_types_text(strip_noise(raw))
    return out


def collection_findings(
    service: str, index: dict[str, DataClassInfo], roots: set[str]
) -> list[str]:
    """Finding keys for non-nullable collection element/value types reachable from `roots`."""
    findings: list[str] = []
    seen: set[str] = set()
    queue = [r for r in roots if r in index]
    while queue:
        dc = index[queue.pop()]
        if dc.name in seen:
            continue
        seen.add(dc.name)
        for prop, typ in dc.props.items():
            cm = COLL_TYPE.match(typ)
            if cm:
                args = _split_top_level(cm.group(1))
                element = args[-1].strip()  # element for List/Set, the VALUE for Map
                if not element.endswith("?"):
                    findings.append(f"{service}:{dc.name}.{prop}")
                inner = re.fullmatch(r"(\w+)\??", element)
                if inner and inner.group(1) in index:
                    queue.append(inner.group(1))
            else:
                tm = re.fullmatch(r"(\w+)\??", typ.strip())
                if tm and tm.group(1) in index:
                    queue.append(tm.group(1))
    return findings


def self_test() -> int:
    """The shapes that MUST flag and MUST NOT. A gate that has only ever passed is unfalsified."""
    failures = 0

    def check(name: str, ok: bool) -> None:
        nonlocal failures
        print(("  ok    " if ok else "  FAIL  ") + name)
        failures += 0 if ok else 1

    index = {
        k: v
        for k, v in index_data_classes_text(strip_noise(
            """
            data class PostJournalRequest(val lines: List<PostJournalLineRequest>, val note: String = "")
            data class PostJournalLineRequest(val accountId: String, val tags: List<String?>)
            data class Wrapped(val inner: Inner, val id: String)
            data class Inner(val codes: List<CodeDto>, val when_: String)
            data class CodeDto(val code: String)
            data class WithMap(val vars: Map<String, String>)
            data class KdocComma(
              /** pairs like (a, b) MUST NOT split this constructor */
              val xs: List<X>
            )
            data class X(val v: String)
            """
        )).items()
        if v is not None
    }

    got = set(collection_findings("svc", index, {"PostJournalRequest"}))
    check("a non-nullable element is flagged", got == {"svc:PostJournalRequest.lines"})
    got = set(collection_findings("svc", index, {"Wrapped"}))
    check("a transitively reachable collection is flagged", got == {"svc:Inner.codes"})
    check("a nullable element is NOT flagged",
          not collection_findings("svc", index, {"PostJournalLineRequest"}))
    got = set(collection_findings("svc", index, {"WithMap"}))
    check("a non-nullable map VALUE is flagged", got == {"svc:WithMap.vars"})
    check("a KDoc comma does not corrupt the constructor split",
          set(collection_findings("svc", index, {"KdocComma"})) == {"svc:KdocComma.xs"})
    check("a scalar-only DTO is clean",
          not collection_findings("svc", index, {"CodeDto"}))
    check("an unreachable DTO is not scanned at all",
          not collection_findings("svc", index, set()))

    resource = strip_noise(
        """
        @Path("/x")
        class R {
            @POST
            fun create(@HeaderParam("Idempotency-Key") k: String?, body: PostJournalRequest) = 1
            @GET
            fun get(q: GetQuery) = 2
            @PUT
            suspend fun put(body: Wrapped) = 3
        }
        """
    )
    got = body_param_types_text(resource)
    check("unannotated body params of @POST/@PUT are found, @GET is not",
          got == {"PostJournalRequest", "Wrapped"})
    client = strip_noise(
        """
        @Path("/x")
        interface Client {
            @POST
            fun send(body: PostJournalRequest): String
        }
        """
    )
    check("an outbound REST-client interface is never scanned",
          not body_param_types_text(client))
    mixed = strip_noise(
        """
        @Path("/sanctions")
        interface SanctionsServiceClient {
            @POST
            fun screen(request: ScreenRequest): Uni<String>
        }
        data class ScreenRequest(val aliases: List<String>)
        """
    )
    check("a @Path interface is outbound even with data classes in the same file",
          not body_param_types_text(mixed))

    print("self-test: " + ("FAILED" if failures else "passed"))
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    repo = Path(args.root).resolve()
    findings: list[str] = []
    subjects = 0
    ambiguous_total = 0
    for module in sorted(repo.glob("openbank-*/src/main/kotlin")):
        service = module.parts[len(repo.parts)]
        index, ambiguous = index_data_classes(module)
        ambiguous_total += ambiguous
        roots = body_param_types(module)
        subjects += len(roots)
        findings.extend(collection_findings(service, index, roots))

    gatelib.subjects(subjects, "inbound request-body parameter(s)")
    if ambiguous_total:
        print(f"note: {ambiguous_total} ambiguous simple name(s) skipped (collision inside one module)")

    new = [f for f in findings if f not in BASELINE]
    for f in new:
        print(
            f"::error::{f}: a request DTO collection has a NON-NULLABLE element type — a null "
            f"array element from the caller deserialises fine and NPEs at the first dereference "
            f"(500 where a 400 belongs, #7867). Declare the element nullable (`List<X?>`) and "
            f"guard with `requireNotNull` naming the index."
        )
    stale = sorted(set(BASELINE) - set(findings))
    for k in stale:
        print(f"::error::baseline entry `{k}` no longer occurs — drop it from BASELINE.")

    if not new and not stale:
        print(
            f"null-array-element: {len(findings)} reachable collection propert(ies) with "
            f"non-nullable elements, all baselined; no new occurrences."
        )
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
