#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Detector: does the ADR-0031 D9 phase-3 development agent's GitHub writer still confine itself
# to ONE non-money-path module's test tree, and still lack any merge/approve surface? (issue #5281)
#
# WHY THIS EXISTS
#   flaky-test-hunter is the only chartered agent with a real GitHub write path. Two properties
#   make that acceptable under ADR-0031 D9 phase 3, and today BOTH hold only because a human read
#   a Kotlin constant:
#
#     1. The write is confined to `openbank-flaky-test-hunter/src/test/kotlin/` — the agent's own
#        test sources, in a module that is NOT in rules.yaml: money_path_services. Phase 4 (money
#        path) explicitly needs 2 approvals + a threat model; phase 3 must not reach it.
#     2. The agent opens a PR and stops. It never merges, never approves, never requests review
#        away from a human. That is the "agent proposes, human disposes" invariant.
#
#   Neither property is currently enforced by anything a change can trip over. The unit tests pin
#   the CURRENT prefix (a ledger path is refused), so re-pointing the writer at, say,
#   openbank-settlement-service would keep every test green — and money_path_services is a list
#   that GROWS: settlement, sanctions, sdd and interest were all added to it after the fact, so a
#   module that is safe today can become money-path tomorrow with no code change at all. Likewise
#   nothing anywhere would go red if someone added `PUT /pulls/{n}/merge` to the adapter.
#
#   So the binding is asserted here, at build time, against rules.yaml itself rather than against
#   a second hand-kept list — the failure mode CLAUDE.md calls out for gates whose scope is a
#   hand-maintained copy of the thing they check.
#
# WHAT IT CHECKS (against the adapter source, not against prose)
#   - the bounded prefix constant still exists and is a string literal;
#   - it confines to a `src/test/` tree (a `src/main/` prefix would let the agent write shipped
#     code, which is a different decision entirely);
#   - its leading path segment names a module that is NOT in rules.yaml: money_path_services;
#   - the adapter source contains no merge / approve / review-request GitHub endpoint literal.
#
#   It does NOT try to prove the agent "behaves"; a static file check cannot. It proves the two
#   structural bounds the charter and ADR-0031 D9 phase 3 rest on are still the ones in the code.
#
# Run:
#   python3 .github/scripts/check-agent-bounded-write-surface.py [--self-test]

import argparse
import pathlib
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    print("::warning::PyYAML not available — skipping bounded write-surface check")
    sys.exit(0)

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES_YAML = REPO / "openbank-libs" / "governance" / "rules.yaml"
MAIN_SRC = REPO / "openbank-flaky-test-hunter" / "src" / "main" / "kotlin"
ADAPTER = (
    REPO
    / "openbank-flaky-test-hunter"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "openbank"
    / "flakytest"
    / "infrastructure"
    / "adapter"
    / "GitHubProposalAdapter.kt"
)

CONFIG = (
    REPO
    / "openbank-flaky-test-hunter"
    / "src"
    / "main"
    / "kotlin"
    / "com"
    / "openbank"
    / "flakytest"
    / "infrastructure"
    / "config"
    / "FlakyTestHunterConfig.kt"
)

# ADR-0031 D9 amendment 2026-11-14: the ONLY refs the writer may read from or open a PR against.
# `main` is production; the qualification branch is the protected, seeded target that proves the
# live write path (the one eligible repair is a defect test-runblocking-unit-guard keeps off main).
# Widening this set is a write-surface decision, so it lives here and not in Kotlin alone.
ALLOWED_BASE_REFS = {"main", "qualification/flaky-test-hunter"}
BASE_REF_ENUM = re.compile(r"enum\s+class\s+WriterBaseRef\b[^{]*\{(.*?)\}", re.S)
BASE_REF_VALUE = re.compile(r'\b[A-Z][A-Z0-9_]*\s*\(\s*"([^"]*)"\s*\)')
# A ref spelled directly in a GitHub path bypasses WriterBaseRef: the writer must take its ref
# from configuration, or a hard-coded literal could diverge from the declared set unseen.
HARDCODED_REF_PATH = re.compile(r"(/git/ref/heads/[A-Za-z0-9]|[?&]ref=[A-Za-z0-9])")

MANIFEST = REPO / "openbank-infra" / "gitops" / "components" / "flaky-test-hunter" / "flaky-test-hunter.yaml"
ENV_VALUE = r"-\s*name:\s*{name}\s*\n\s*value:\s*\"?([^\"\n]+?)\"?\s*$"


def evaluate_manifest(manifest: str, config_source: str) -> list[str]:
    """The scan snapshot and the writer must name the SAME ref, or scan and write disagree."""
    snap = re.search(ENV_VALUE.format(name="SNAPSHOT_REF"), manifest, re.M)
    writer = re.search(ENV_VALUE.format(name="FLAKY_TEST_HUNTER_WRITER_BASE_REF"), manifest, re.M)
    if not snap or not writer:
        return ["the flaky-test-hunter manifest must set both SNAPSHOT_REF and FLAKY_TEST_HUNTER_WRITER_BASE_REF"]
    enum = BASE_REF_ENUM.search(config_source)
    names = dict(re.findall(r'\b([A-Z][A-Z0-9_]*)\s*\(\s*"([^"]*)"\s*\)', enum.group(1))) if enum else {}
    ref = names.get(writer.group(1).strip().upper().replace("-", "_"))
    if ref is None:
        return [f"FLAKY_TEST_HUNTER_WRITER_BASE_REF={writer.group(1)!r} names no WriterBaseRef value"]
    if ref != snap.group(1).strip():
        return [
            f"SNAPSHOT_REF={snap.group(1)!r} but the writer targets {ref!r} — the agent would scan one "
            "tree and repair another"
        ]
    return []


PREFIX_CONST = re.compile(
    r'\bOWN_TEST_SOURCE_PREFIX\s*(?::\s*String\s*)?=\s*"([^"]*)"'
)

# GitHub REST surfaces that would turn "propose" into "dispose". Matched as substrings of any
# string literal in the adapter, so a path assembled from one of these constants is still caught.
FORBIDDEN_ENDPOINTS = {
    "/merge": "merges its own pull request",
    "/reviews": "approves (or dismisses review on) its own pull request",
    "/requested_reviewers": "assigns its own reviewers",
    "/branches/main/protection": "edits branch protection",
}

STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')

# Any literal that looks like a module-scoped source-tree prefix. The bound must be asserted in
# exactly ONE place (BoundedTestPath); a second copy elsewhere in the agent's main sources is the
# hand-kept-duplicate shape where relaxing one half leaves the two disagreeing with nothing red.
# LlmDiagnosisAdapter carried exactly such a copy until #5281.
SOURCE_TREE_PREFIX = re.compile(r"^openbank-[a-z0-9-]+/src/(main|test)/kotlin/")


def evaluate(source: str, money_path: set[str]) -> list[str]:
    """Return a list of findings. Empty list == the bounded write surface still holds."""
    findings: list[str] = []

    match = PREFIX_CONST.search(source)
    if not match:
        findings.append(
            "the adapter declares no OWN_TEST_SOURCE_PREFIX string literal — the bounded write "
            "surface has been removed or renamed, so nothing confines the agent's writes"
        )
    else:
        prefix = match.group(1)
        segments = [s for s in prefix.split("/") if s]
        module = segments[0] if segments else ""
        if "src/test/" not in prefix:
            findings.append(
                f"OWN_TEST_SOURCE_PREFIX {prefix!r} does not confine to a src/test/ tree — "
                "phase 3 permits test-only changes, not shipped code"
            )
        if not module:
            findings.append(f"OWN_TEST_SOURCE_PREFIX {prefix!r} names no module")
        elif module in money_path:
            findings.append(
                f"OWN_TEST_SOURCE_PREFIX {prefix!r} points at {module!r}, which rules.yaml lists "
                "in money_path_services — ADR-0031 D9 phase 4 (money path) requires 2 approvals "
                "and a threat model, and is not what this writer is chartered for"
            )

    for literal in STRING_LITERAL.findall(source):
        for endpoint, what in FORBIDDEN_ENDPOINTS.items():
            if endpoint in literal:
                findings.append(
                    f"the adapter references the GitHub endpoint {endpoint!r} (in {literal!r}): it "
                    f"{what}. The agent proposes; a human disposes (ADR-0031 D9)."
                )

    return findings


def evaluate_base_refs(config_source: str, adapter_source: str) -> list[str]:
    """The writer's refs come from one closed enum whose values are all in ALLOWED_BASE_REFS."""
    findings: list[str] = []
    enum = BASE_REF_ENUM.search(config_source)
    if not enum:
        findings.append(
            "FlakyTestHunterConfig declares no `enum class WriterBaseRef` — the writer's target ref "
            "is no longer a closed set"
        )
    else:
        values = BASE_REF_VALUE.findall(enum.group(1))
        if not values:
            findings.append("WriterBaseRef declares no ref values — the bound would pass vacuously")
        if "main" not in values:
            findings.append("WriterBaseRef no longer offers `main` — production would have no target")
        for value in values:
            if value not in ALLOWED_BASE_REFS:
                findings.append(
                    f"WriterBaseRef offers {value!r}, which is not in ALLOWED_BASE_REFS "
                    f"{sorted(ALLOWED_BASE_REFS)} — widening the writer's target refs needs an ADR-0031 "
                    "D9 decision and an edit to this gate, not a Kotlin enum value alone"
                )
    for literal in STRING_LITERAL.findall(adapter_source):
        if HARDCODED_REF_PATH.search(literal):
            findings.append(
                f"the adapter spells a ref directly in {literal!r} — take it from WriterBaseRef"
            )
    return findings


def relative(path: pathlib.Path) -> str:
    try:
        return str(path.relative_to(REPO))
    except ValueError:
        return path.name


def duplicate_bounds(main_src: pathlib.Path) -> list[str]:
    """Every module-scoped source-tree prefix literal outside the one canonical declaration."""
    findings: list[str] = []
    for kt in sorted(main_src.rglob("*.kt")):
        text = kt.read_text()
        canonical = kt.name == "GitHubProposalAdapter.kt"
        for literal in STRING_LITERAL.findall(text):
            if not SOURCE_TREE_PREFIX.match(literal):
                continue
            if canonical and PREFIX_CONST.search(text) and PREFIX_CONST.search(text).group(1) == literal:
                continue
            findings.append(
                f"{relative(kt)} repeats the bounded-path prefix {literal!r} as its own "
                "string literal — the bound must come from BoundedTestPath alone, or the two "
                "copies can be widened independently with nothing going red"
            )
    return findings


def money_path_services() -> set[str]:
    doc = yaml.safe_load(RULES_YAML.read_text()) or {}
    return set(doc.get("money_path_services") or [])


def self_test() -> int:
    """Falsify the comparison in BOTH directions against fixtures."""
    fails: list[str] = []
    money = {"openbank-ledger-service", "openbank-settlement-service"}

    def case(label: str, source: str, want_findings: bool) -> None:
        got = evaluate(source, money)
        if bool(got) != want_findings:
            fails.append(f"{label}: expected findings={want_findings}, got {got}")

    clean = (
        'private const val OWN_TEST_SOURCE_PREFIX = "openbank-flaky-test-hunter/src/test/kotlin/"\n'
        'val r = send("POST", "/pulls", body)\n'
    )
    case("the chartered bounded prefix and no merge surface is clean", clean, False)

    # THE DEFECT this gate exists for: re-pointing the writer at a money-path module. Every
    # existing unit test stays green, because they only pin that a LEDGER path is refused.
    case(
        "a money-path module prefix is FLAGGED",
        'private const val OWN_TEST_SOURCE_PREFIX = "openbank-settlement-service/src/test/kotlin/"\n',
        True,
    )
    case(
        "the ledger prefix is FLAGGED",
        'private const val OWN_TEST_SOURCE_PREFIX = "openbank-ledger-service/src/test/kotlin/"\n',
        True,
    )
    # Widening from test sources to shipped code.
    case(
        "a src/main prefix is FLAGGED",
        'private const val OWN_TEST_SOURCE_PREFIX = "openbank-flaky-test-hunter/src/main/kotlin/"\n',
        True,
    )
    # Deleting the bound must not read as "no violation found".
    case("a removed prefix constant is FLAGGED", 'val r = send("POST", "/pulls", body)\n', True)
    case("an empty source is FLAGGED", "", True)
    # The proposes-not-disposes invariant, one case per forbidden endpoint.
    for endpoint in FORBIDDEN_ENDPOINTS:
        case(f"a {endpoint!r} call is FLAGGED", clean + f'send("PUT", "/pulls/1{endpoint}", null)\n', True)
    # A non-money module that is merely different is NOT a finding — this gate binds to
    # money_path_services, not to one hardcoded module name.
    case(
        "a different non-money module is clean",
        'private const val OWN_TEST_SOURCE_PREFIX = "openbank-docs-truth-agent/src/test/kotlin/"\n',
        False,
    )

    def ref_case(label: str, config_src: str, adapter_src: str, want_findings: bool) -> None:
        got = evaluate_base_refs(config_src, adapter_src)
        if bool(got) != want_findings:
            fails.append(f"{label}: expected findings={want_findings}, got {got}")

    good_enum = 'enum class WriterBaseRef(val ref: String) {\n MAIN("main"),\n QUALIFICATION("qualification/flaky-test-hunter"),\n}\n'
    good_adapter = 'send("GET", "/git/ref/heads/${baseRef.ref}", null)\nsend("GET", "/contents/$path?ref=${baseRef.ref}", null)\n'
    ref_case("the two declared refs, taken from config, are clean", good_enum, good_adapter, False)
    ref_case(
        "an extra ref value is FLAGGED",
        good_enum.replace("}\n", ' RELEASE("release/x"),\n}\n'),
        good_adapter,
        True,
    )
    ref_case("a removed enum is FLAGGED", "", good_adapter, True)
    ref_case("an enum without main is FLAGGED", 'enum class WriterBaseRef(val ref: String) { Q("qualification/flaky-test-hunter") }', good_adapter, True)
    ref_case("a hard-coded heads ref is FLAGGED", good_enum, 'send("GET", "/git/ref/heads/main", null)\n', True)
    ref_case("a hard-coded contents ref is FLAGGED", good_enum, 'send("GET", "/contents/$path?ref=release", null)\n', True)

    def manifest(snap: str, writer: str) -> str:
        return (
            f"          env:\n            - name: SNAPSHOT_REF\n              value: {snap}\n"
            f"            - name: FLAKY_TEST_HUNTER_WRITER_BASE_REF\n              value: {writer}\n"
        )

    for label, snap, writer, want in [
        ("main/main is clean", "main", "main", False),
        ("qualification on both sides is clean", "qualification/flaky-test-hunter", "qualification", False),
        ("snapshot main, writer qualification is FLAGGED", "main", "qualification", True),
        ("snapshot qualification, writer main is FLAGGED", "qualification/flaky-test-hunter", "main", True),
        ("an unknown writer value is FLAGGED", "main", "release", True),
    ]:
        got = evaluate_manifest(manifest(snap, writer), good_enum)
        if bool(got) != want:
            fails.append(f"manifest {label}: expected findings={want}, got {got}")
    if evaluate_manifest("", good_enum) == []:
        fails.append("a manifest with neither env var is not flagged")

    # A fixture-only self-test cannot notice that this script's subjects stopped existing.
    if not ADAPTER.exists():
        fails.append(f"the adapter this gate checks is missing: {ADAPTER}")
    elif not MAIN_SRC.exists():
        fails.append(f"the agent main sources are missing: {MAIN_SRC}")
    else:
        # Falsify duplicate_bounds against a fixture tree, both directions.
        import tempfile
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            (root / "GitHubProposalAdapter.kt").write_text(
                'private const val OWN_TEST_SOURCE_PREFIX = "openbank-flaky-test-hunter/src/test/kotlin/"\n'
            )
            if duplicate_bounds(root):
                fails.append("duplicate_bounds flags the single canonical declaration")
            (root / "Other.kt").write_text('val p = "openbank-flaky-test-hunter/src/test/kotlin/"\n')
            if not duplicate_bounds(root):
                fails.append("duplicate_bounds does NOT flag a second copy of the bound")
    if not RULES_YAML.exists():
        fails.append(f"rules.yaml not found at {RULES_YAML}")
    elif not money_path_services():
        fails.append("rules.yaml: money_path_services resolved empty — this gate would pass vacuously")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(
        f"self-test ok: bounded write surface is falsifiable "
        f"({20 + len(FORBIDDEN_ENDPOINTS)} cases + a live read of the adapter and rules.yaml)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not ADAPTER.exists():
        print(f"::error::{ADAPTER} not found — this check must not pass vacuously.")
        return 1
    if not RULES_YAML.exists():
        print(f"::error::{RULES_YAML} not found — this check must not pass vacuously.")
        return 1

    money = money_path_services()
    if not money:
        print("::error::rules.yaml: money_path_services is empty — refusing to pass vacuously.")
        return 1

    gatelib.subjects(len(money), "money_path_services entries the bound is checked against")

    if not CONFIG.exists():
        print(f"::error::{CONFIG} not found — this check must not pass vacuously.")
        return 1
    findings = (
        evaluate(ADAPTER.read_text(), money)
        + duplicate_bounds(MAIN_SRC)
        + evaluate_base_refs(CONFIG.read_text(), ADAPTER.read_text())
        + (
            evaluate_manifest(MANIFEST.read_text(), CONFIG.read_text())
            if MANIFEST.exists()
            else [f"{relative(MANIFEST)} not found — the snapshot/writer ref agreement cannot be checked"]
        )
    )
    if not findings:
        print(
            "agent bounded write surface: flaky-test-hunter's GitHub writer is still confined to "
            f"its own src/test tree, outside all {len(money)} money_path_services, with no "
            "merge/approve/review-request endpoint, declared in exactly one place, targeting only "
            f"{sorted(ALLOWED_BASE_REFS)}."
        )
        return 0

    for f in findings:
        print(f"::error::check-agent-bounded-write-surface: {f}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
