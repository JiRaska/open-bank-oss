#!/usr/bin/env python3
"""A grantable delegation capability must have a DECLARED enforcer
(rules.yaml: delegation_capability_enforcement, ADR-0375 D4).

Why this exists
---------------
`DelegationGrant.CAPABILITY_MATRIX` says what a grantor may be offered. Nothing said what the
platform will actually honour, and the two drifted apart in silence: measured against
`origin/main` on 2026-11-12, 9 of 18 `DelegationCapability` values had no production reference
anywhere outside delegation-service itself. A capability compiles, persists, publishes over the
outbox and renders on the grant screen whether or not a single line of code reads it, so the
failure is a promise the bank then refuses — `403` on five of six payment rails — with no signal
to the grantor and nothing red anywhere.

The repo already had this rule, written by hand for exactly one field:
`DelegationService.rejectUnsupportedExposure` refuses an offer carrying `exposure` because
nothing can enforce redaction, `maxViews` or a watermark. ADR-0249 D5 is the same instinct for
one capability (no `ACCOUNT_INITIATE_PAYMENT` without a ceiling). This gate generalises it to the
whole vocabulary.

What this checks
----------------
The subject set is DERIVED from `CAPABILITY_MATRIX` — never hand-kept, because a gate whose scope
is a hand-maintained list of the thing it checks reads as PASSING when the list is short. For
each permitted `(resourceType, capability)` pair, `rules.yaml` must carry one entry, and the
entry is compared against the tree in BOTH directions:

  status: enforced    -> `enforced_in` must exist and must reference the capability name in
                         production Kotlin, else FAIL (the registry is describing code that is
                         not there)
  status: unenforced  -> no production file outside delegation-service may reference the
                         capability; one appearing FAILS, asking for the registry and the ADR
                         row quoting it to be updated

Plus: a pair the matrix permits with no entry at all, an entry for a pair the matrix does not
permit, and an unknown status. A one-way check would let an `unenforced` entry rot into permanent
furniture and would stay silent on the day the gap is closed — the same both-directions idiom as
check-enforcement-reachability.py and check-pact-provider-replay.py.

Deliberate limit, stated rather than papered over
-------------------------------------------------
The evidence scan is CAPABILITY-keyed while the subject is a PAIR, so when one capability is
enforced for one resource type and not another (`OBJECT_READ` is enforced for DOCUMENT and for
nothing else), a reference to it is already explained by the enforced pair and the unenforced
sibling cannot be falsified by scanning. A pair-aware scan requiring the enforcer to also name
the resource type was measured and REJECTED: `DelegatedCardGrant.kt` is the genuine enforcer of
`CARD_VIEW` and never names a standalone `CARD` token, so the rule would cry wolf about correct
code — worth less than nothing (the `entity-column-names` precedent). Instead such an entry must
carry `shared_capability: true`, which makes the weaker guarantee a declared, reviewed act and is
printed on every run, so a green is never read as more than it is.

delegation-service itself is excluded from the evidence scan for every pair EXCEPT those it
legitimately owns (`enforced_in` pointing inside it, e.g. DELEGATION_MANAGE, whose resource owner
IS delegation-service). Otherwise the enum declaration, the DTO and the OpenAPI document would
make every capability look enforced — a presence check goes always-true when a convention
registers the thing everywhere.

Comment and string stripping matters: this repo's KDoc names the very capabilities being counted,
and Kotlin block comments NEST, so a naive strip closes early on a KDoc containing `/*`.

Falsifiability
--------------
`--self-test` drives the matrix parser and the evidence scan over synthetic sources in both
directions, so a "clean" run has been shown capable of reporting dirty.

Usage: check-delegation-capability-enforcement.py [--root .] [--rules ...]
                                                  [--self-test] [--enforce] [--list-unenforced]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - the runner image always has it
    print("::error::pyyaml unavailable — add it to the runner base image", file=sys.stderr)
    raise SystemExit(1) from None

REPO = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_RULES = "openbank-libs/governance/rules.yaml"
MATRIX_FILE = (
    "openbank-delegation-service/src/main/kotlin/com/openbank/delegation/domain/model/DelegationGrant.kt"
)
OWNING_MODULE = "openbank-delegation-service"


def strip_comments(src: str) -> str:
    """Remove Kotlin line comments, NESTING block comments, and string literals."""
    out: list[str] = []
    i, n, depth = 0, len(src), 0
    while i < n:
        two = src[i : i + 2]
        if depth:
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "*/":
                depth -= 1
                i += 2
                continue
            out.append("\n" if src[i] == "\n" else " ")
            i += 1
            continue
        if two == "/*":
            depth = 1
            i += 2
            continue
        if two == "//":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if src[i] in ('"', "'"):
            quote = src[i]
            triple = src[i : i + 3] == quote * 3
            i += 3 if triple else 1
            while i < n:
                if not triple and src[i] == "\\":
                    i += 2
                    continue
                if triple and src[i : i + 3] == quote * 3:
                    i += 3
                    break
                if not triple and (src[i] == quote or src[i] == "\n"):
                    i += 1
                    break
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


# The declared type is `Map<DelegationResourceType, Set<DelegationCapability>>` — nested
# angle brackets, so anything of the form `Map<[^>]*>` truncates at the inner `>` and the
# matrix silently parses to empty. Match up to the `= mapOf(` instead.
MATRIX_START = re.compile(r"CAPABILITY_MATRIX\b[^=]*=\s*mapOf\s*\(")
# ADR-0375 D5+D6. The matrix still lists PAYMENT and STATEMENT so historical grants deserialise,
# but a grantor can only be OFFERED the types in this set, so only those pairs are subjects. Parsed
# rather than listed here, for the same reason the matrix is: a hand-kept copy of the thing being
# checked reads as passing when it falls behind.
OFFERABLE_START = re.compile(r"OFFERABLE_RESOURCE_TYPES\b[^=]*=\s*setOf\s*\(")
RESOURCE_ENTRY = re.compile(r"DelegationResourceType\.([A-Z_]+)\s+to\s+setOf\s*\(")
CAPABILITY_REF = re.compile(r"DelegationCapability\.([A-Z_]+)")


def parse_offerable(src: str) -> set[str]:
    """Parse `OFFERABLE_RESOURCE_TYPES` into a set of resource-type names. Empty = unparseable, and
    every caller fails closed on that rather than treating "no offerable types" as a clean pass."""
    stripped = "\n".join(
        line for line in src.splitlines() if not line.strip().startswith("//") and not line.strip().startswith("*")
    )
    start = OFFERABLE_START.search(stripped)
    if not start:
        return set()
    depth, i = 1, start.end()
    while i < len(stripped) and depth:
        if stripped[i] == "(":
            depth += 1
        elif stripped[i] == ")":
            depth -= 1
        i += 1
    body = stripped[start.end():i - 1]
    return set(re.findall(r"DelegationResourceType\.([A-Z_]+)", body))


def parse_matrix(src: str) -> dict[str, set[str]]:
    """Parse CAPABILITY_MATRIX into {resourceType: {capability, ...}} by paren depth.

    Depth matters: `X to setOf(A, B)` closes its own paren, and a regex that stopped at the first
    `)` would silently truncate every multi-line entry — the #2360 annotation-parser trap.
    """
    stripped = strip_comments(src)
    start = MATRIX_START.search(stripped)
    if not start:
        return {}
    i, depth, n = start.end(), 1, len(stripped)
    body_start = i
    while i < n and depth:
        if stripped[i] == "(":
            depth += 1
        elif stripped[i] == ")":
            depth -= 1
        i += 1
    body = stripped[body_start : i - 1]

    out: dict[str, set[str]] = {}
    for entry in RESOURCE_ENTRY.finditer(body):
        resource = entry.group(1)
        j, d = entry.end(), 1
        while j < len(body) and d:
            if body[j] == "(":
                d += 1
            elif body[j] == ")":
                d -= 1
            j += 1
        out[resource] = {m.group(1) for m in CAPABILITY_REF.finditer(body[entry.end() : j - 1])}
    return out


def production_sources(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(p for p in root.glob("openbank-*/src/main/kotlin/**/*.kt") if p.is_file())


def scan_tree(root: pathlib.Path, capabilities: set[str]) -> dict[str, list[str]]:
    """One pass over production Kotlin, returning {capability: [repo-relative file, ...]}.

    One pass, not one per capability: the per-capability version read and comment-stripped every
    file in the fleet once per subject and took 24 s for 20 subjects, which a gate budget cannot
    carry. Both the stripped and the raw text are consulted — a capability travels as a Kotlin
    enum member AND as a bare string constant (`const val CAP_CARD_VIEW = "CARD_VIEW"`), and the
    string form IS the wire value that gets compared, unlike check-enforcement-reachability.py
    where a string naming a function is only prose about it.
    """
    tokens = {c: re.compile(rf"(?<![A-Za-z0-9_]){re.escape(c)}(?![A-Za-z0-9_])") for c in capabilities}
    hits: dict[str, list[str]] = {c: [] for c in capabilities}
    for path in production_sources(root):
        rel = str(path.relative_to(root))
        try:
            raw = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:  # pragma: no cover
            continue
        if not any(c in raw for c in capabilities):
            continue
        stripped = strip_comments(raw)
        for capability, token in tokens.items():
            if token.search(stripped) or token.search(raw):
                hits[capability].append(rel)
    return hits


def findings(
    root: pathlib.Path, rules_path: pathlib.Path
) -> tuple[list[str], int, list[str], list[str]]:
    matrix_src = (root / MATRIX_FILE).read_text(encoding="utf-8")
    matrix = parse_matrix(matrix_src)
    offerable = parse_offerable(matrix_src)
    if not offerable:
        return (
            [
                f"OFFERABLE_RESOURCE_TYPES could not be parsed from {MATRIX_FILE} — refusing to "
                "report a vacuous pass over zero subjects"
            ],
            0,
            unenforced,
            shared_notes,
        )
    # A pair is a subject only if its resource type can still be offered. A type that cannot be
    # offered needs no enforcer: nothing can create a grant that would reach one.
    pairs = {(r, c) for r, caps in matrix.items() if r in offerable for c in caps}

    rules = yaml.safe_load(rules_path.read_text(encoding="utf-8")) or {}
    declared_list = ((rules.get("delegation_capability_enforcement") or {}).get("declared")) or []
    declared: dict[tuple[str, str], dict] = {}
    out: list[str] = []
    unenforced: list[str] = []
    shared_notes: list[str] = []

    for entry in declared_list:
        key = (entry.get("resource_type"), entry.get("capability"))
        if key in declared:
            out.append(f"{key[0]}/{key[1]}: declared twice in rules.yaml")
        declared[key] = entry

    if not pairs:
        out.append(
            f"CAPABILITY_MATRIX could not be parsed from {MATRIX_FILE} — refusing to report a "
            "vacuous pass over zero subjects"
        )
        return out, 0, unenforced, shared_notes

    tree = scan_tree(root, {c for _, c in pairs})

    for key in sorted(pairs):
        resource, capability = key
        entry = declared.get(key)
        if entry is None:
            out.append(
                f"{resource}/{capability}: permitted by CAPABILITY_MATRIX with no "
                "`delegation_capability_enforcement` entry. Declare its enforcer, or remove the "
                "pair from the matrix (ADR-0375 D4)."
            )
            continue
        status = entry.get("status")
        enforced_in = entry.get("enforced_in") or []
        if status == "enforced":
            if not enforced_in:
                out.append(f"{resource}/{capability}: status `enforced` with no `enforced_in` path")
            for rel in enforced_in:
                path = root / rel
                if not path.exists():
                    out.append(f"{resource}/{capability}: enforced_in path does not exist: {rel}")
                elif not re.search(
                    rf"(?<![A-Za-z0-9_]){re.escape(capability)}(?![A-Za-z0-9_])",
                    path.read_text(encoding="utf-8"),
                ):
                    out.append(
                        f"{resource}/{capability}: enforced_in {rel} does not reference the "
                        "capability — the registry describes code that is not there"
                    )
        elif status == "unenforced":
            unenforced.append(f"{resource}/{capability}")
            owner_enforced = any(rel.startswith(OWNING_MODULE + "/") for rel in enforced_in)
            shared = sorted(
                other for (other, cap), e in declared.items()
                if cap == capability and other != resource and e.get("status") == "enforced"
            )
            if shared:
                if not entry.get("shared_capability"):
                    out.append(
                        f"{resource}/{capability}: declared `unenforced` while {capability} is "
                        f"enforced for {', '.join(shared)}, so the evidence scan cannot falsify "
                        "this entry. Add `shared_capability: true` to declare that limit "
                        "explicitly (ADR-0375 D4)."
                    )
                else:
                    shared_notes.append(
                        f"{resource}/{capability}: unenforced, NOT scan-verified — {capability} is "
                        f"enforced for {', '.join(shared)}"
                    )
                continue
            if entry.get("shared_capability"):
                out.append(
                    f"{resource}/{capability}: carries `shared_capability: true` but {capability} "
                    "is enforced for no other resource type — the scan does verify this entry, so "
                    "the opt-out is stale"
                )
                continue
            hits = [
                rel for rel in tree.get(capability, [])
                if owner_enforced or not rel.startswith(OWNING_MODULE + "/")
            ]
            if hits:
                out.append(
                    f"{resource}/{capability}: declared `unenforced` "
                    f"(issue #{entry.get('issue', '?')}) but is now referenced from "
                    f"{', '.join(hits)}. If the enforcement landed, flip the status and update "
                    "the ADR-0375 delivery row that quotes this entry."
                )
        else:
            out.append(
                f"{resource}/{capability}: status must be `enforced` or `unenforced`, got {status!r}"
            )

    for key in sorted(declared):
        if key not in pairs:
            out.append(
                f"{key[0]}/{key[1]}: declared in rules.yaml but CAPABILITY_MATRIX does not permit "
                "it — a stale registry entry is a finding in its own right"
            )
    return out, len(pairs), unenforced, shared_notes


MATRIX_FIXTURE = """
        val CAPABILITY_MATRIX: Map<DelegationResourceType, Set<DelegationCapability>> = mapOf(
            DelegationResourceType.ACCOUNT to setOf(
                DelegationCapability.ACCOUNT_READ_BALANCES,
                // DelegationCapability.ACCOUNT_VIEW_DETAILS is discussed here but not granted
                DelegationCapability.ACCOUNT_INITIATE_PAYMENT,
            ),
            DelegationResourceType.CARD to setOf(DelegationCapability.CARD_VIEW),
            DelegationResourceType.DOCUMENT to setOf(DelegationCapability.OBJECT_READ),
        )
"""


def self_test() -> int:
    failures = 0

    parsed = parse_matrix(MATRIX_FIXTURE)
    cases: list[tuple[bool, str]] = [
        (set(parsed) == {"ACCOUNT", "CARD", "DOCUMENT"}, "every resource type is parsed"),
        (
            parsed.get("ACCOUNT") == {"ACCOUNT_READ_BALANCES", "ACCOUNT_INITIATE_PAYMENT"},
            "a capability named only in a comment is NOT counted as granted",
        ),
        (parsed.get("CARD") == {"CARD_VIEW"}, "a single-line setOf( ... ) entry is parsed whole"),
        (parsed.get("DOCUMENT") == {"OBJECT_READ"}, "the last entry is not truncated"),
        (parse_matrix("object X { val other = mapOf<String, String>() }") == {}, "no matrix parses to empty, which the caller must treat as a refusal"),
        # ADR-0375 D5+D6: subjects are the OFFERABLE pairs, so this parser decides the scope.
        (
            parse_offerable(
                "val OFFERABLE_RESOURCE_TYPES = setOf(\n"
                "    DelegationResourceType.ACCOUNT,\n"
                "    // DelegationResourceType.STATEMENT, // cannot be delivered\n"
                "    DelegationResourceType.DOCUMENT,\n"
                ")\n"
            ) == {"ACCOUNT", "DOCUMENT"},
            "offerable types parse, and a type named only in a comment is NOT offerable",
        ),
        (
            parse_offerable("val OTHER = setOf(DelegationResourceType.ACCOUNT)") == set(),
            "an absent OFFERABLE_RESOURCE_TYPES parses to empty, which the caller fails closed on",
        ),
    ]

    token = re.compile(r"(?<![A-Za-z0-9_])CARD_VIEW(?![A-Za-z0-9_])")
    evidence: list[tuple[str, bool, str]] = [
        ("if (capability == CARD_VIEW) allow()", True, "a real reference"),
        ('const val CAP_CARD_VIEW = "CARD_VIEW"', True, "the bare string form is evidence"),
        ("val x = CARD_VIEW_TRANSACTIONS in caps", False, "a longer identifier is not a reference"),
        ("val y = CAP_CARD_VIEW in caps", False, "a constant NAMED after it is not, on its own, a reference"),
    ]
    for src, expected, label in evidence:
        cases.append((bool(token.search(src)) == expected, f"evidence scan: {label}"))

    for ok, label in cases:
        failures += 0 if ok else 1
        print(f"  [{'ok' if ok else 'FAIL'}] {label}")
    if failures:
        print(f"::error::delegation-capability-enforcement self-test: {failures} case(s) failed")
        return 1
    print(
        f"delegation-capability-enforcement self-test: {len(cases)} cases pass (both directions)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=str(REPO))
    parser.add_argument("--rules", default=DEFAULT_RULES)
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--list-unenforced", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    problems, subjects, unenforced, shared_notes = findings(root, root / args.rules)

    if args.list_unenforced:
        for pair in unenforced:
            print(pair)
        return 0

    print(f"SUBJECTS={subjects}")
    for note in shared_notes:
        print(f"::notice::delegation-capability-enforcement: {note}")
    for p in problems:
        print(f"::{'error' if args.enforce else 'warning'}::delegation-capability-enforcement: {p}")
    if not problems:
        print(
            f"delegation-capability-enforcement: {subjects} grantable pair(s), every one with a "
            "declared enforcer matching the tree"
        )
    return 1 if (problems and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
