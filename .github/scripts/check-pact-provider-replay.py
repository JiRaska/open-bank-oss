#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every committed pact must be REPLAYED by a provider test that actually runs on a pull request.

Issue #2338. A consumer pact cannot catch a wrong request path: the Pact mock server answers
whatever path the client asks for, so pointing a client at a route that does not exist leaves the
consumer test green. Only the provider replay goes red. That is how finrep-service shipped a call
to `/api/v1/ledger/trial-balance`, a ledger route that has never existed (#2269).

`CLAUDE.md` states the rule -- never add a consumer pact without wiring the provider's `@PactFolder`
replay -- and until this script nothing enforced it. The result, measured 2026-07-25: 16 of 27
committed pacts had no replay that runs before merge (#2327). Their only verification class is
`@PactBroker`-sourced and `@EnabledIfSystemProperty(named = "pactbroker.url")`-gated, and on a pull
request that property is empty (`_service-ci.yml` puts the PR lane on `ubuntu-latest` and blanks
`PACT_BROKER_URL` off main-push -- the broker has no public ingress, ADR-0056), so the class skips
and the contract is replayed only AFTER the merge.

WHAT COUNTS AS COVERAGE: a `@Provider("<name>")` class that is `@PactFolder`-sourced, carries no
gating annotation, and is not excluded by its module's build.gradle.kts. A class that exists but
never runs is the failure mode this checks for -- it is not evidence of anything.

DERIVED, NOT LISTED: the covered set is computed from `pacts/*.json` (the `provider.name` field, not
the filename -- both halves contain dashes) and from the annotations themselves. A gate whose
coverage set is maintained separately from the artifacts it covers reads as *passing* when the list
is short, which is exactly what #2318 had to remove from the drift gate one layer down.

    python3 .github/scripts/check-pact-provider-replay.py            # enforce
    python3 .github/scripts/check-pact-provider-replay.py --selftest # prove the gate can fail
"""

from __future__ import annotations

import fnmatch
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACTS = ROOT / "pacts"

# Pacts with no pre-merge provider replay TODAY. Each line is a debt, not a decision: #2327 empties
# this list one provider at a time, and an entry that becomes covered fails as stale, so the list
# cannot quietly outlive the problem.
#
# Kept here rather than in rules.yaml on purpose: 25 of the 26 gen-*opa-bundle*.sh hash rules.yaml
# into every service's OPA bundle checksum, so each line removed from this backlog would restamp
# ~44 generated files and give the PR a short shelf life. The list belongs next to the code that
# reads it.
KNOWN_UNCOVERED = {
    "pacts/openbank-consent-service-openbank-sca-service.json",
    # Landed 2026-07-25, after the #2327 audit counted 16 of 27: same provider
    # (balance-service), so the same debt, not a new class of it. The estate went 27 -> 33 pacts in
    # a day, which is the argument for a gate rather than another audit.
    "pacts/openbank-transaction-service-openbank-swift-service.json",
}

# Annotations that can stop a test class from running. @EnabledIf* is the live one here; the others
# are listed so a future "temporarily disabled" class cannot be read as coverage either.
GATING = (
    "@EnabledIfSystemProperty",
    "@EnabledIfEnvironmentVariable",
    "@DisabledIfSystemProperty",
    "@DisabledIfEnvironmentVariable",
    "@Disabled",
)

errors: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def strip_comments(src: str) -> str:
    """Drop // and /* */ so prose about @Provider is never mistaken for an annotation (#2291)."""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


@dataclass
class VerificationClass:
    path: str
    provider: str
    source: str  # "folder" | "broker" | "none"
    gates: list[str] = field(default_factory=list)
    excluded_by_build: str = ""

    @property
    def runs_on_pr(self) -> bool:
        return self.source == "folder" and not self.gates and not self.excluded_by_build

    def why_not(self) -> str:
        if self.source == "broker":
            return "@PactBroker-sourced, so it needs a broker the PR lane cannot reach"
        if self.source == "none":
            return "neither @PactFolder nor @PactBroker — it verifies nothing"
        if self.gates:
            return f"gated by {', '.join(self.gates)}"
        if self.excluded_by_build:
            return f"excluded by {self.excluded_by_build}"
        return "runs"


def module_of(path: Path) -> str:
    return path.relative_to(ROOT).parts[0]


def build_exclusions(module: str) -> list[str]:
    """Test-name globs a module's build.gradle.kts drops (exclude / excludeTestsMatching)."""
    build = ROOT / module / "build.gradle.kts"
    if not build.is_file():
        return []
    src = strip_comments(build.read_text(encoding="utf-8"))
    return re.findall(r'(?:excludeTestsMatching|exclude)\(\s*"([^"]+)"\s*\)', src)


def excluded_by(class_file: Path, patterns: list[str]) -> str:
    name = class_file.stem
    for pat in patterns:
        # Gradle's exclude() takes a path glob, excludeTestsMatching() a class-name glob. Test both
        # readings: a pattern that matches either is one that stops this class from running.
        if fnmatch.fnmatch(name, pat) or fnmatch.fnmatch(f"x/{name}.class", pat):
            return f'build.gradle.kts exclude("{pat}")'
    return ""


def scan_verification_classes() -> list[VerificationClass]:
    found: list[VerificationClass] = []
    for path in sorted(ROOT.glob("*/src/test/kotlin/**/*.kt")):
        src = strip_comments(path.read_text(encoding="utf-8"))
        m = re.search(r'@Provider\(\s*"([^"]+)"\s*\)', src)
        if not m:
            continue
        # Require the pact library import, not just the annotation text. openbank-flaky-test-hunter
        # carries a Kotlin sample *inside a string literal* — `@Provider("openbank-sample-service")`
        # in a fixture it scans — which reads as a verification class to any text match. Detect the
        # artifact (the dependency), never the text (#2291).
        if "au.com.dius.pact" not in src:
            continue
        source = "folder" if "@PactFolder" in src else "broker" if "@PactBroker" in src else "none"
        gates = [g for g in GATING if g in src]
        found.append(
            VerificationClass(
                path=str(path.relative_to(ROOT)),
                provider=m.group(1),
                source=source,
                gates=gates,
                excluded_by_build=excluded_by(path, build_exclusions(module_of(path))),
            )
        )
    return found


def scan_pacts() -> dict[str, str]:
    """{'pacts/<file>.json': '<provider name>'} read from the JSON, never from the filename."""
    out: dict[str, str] = {}
    for path in sorted(PACTS.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        provider = (data.get("provider") or {}).get("name")
        rel = str(path.relative_to(ROOT))
        if not provider:
            fail(f"{rel} has no provider.name — cannot tell which service must replay it")
            continue
        out[rel] = provider
    return out


def check_coverage(pacts: dict[str, str], classes: list[VerificationClass], allow: set[str]) -> None:
    replaying = {c.provider for c in classes if c.runs_on_pr}
    for pact, provider in sorted(pacts.items()):
        covered = provider in replaying
        if covered and pact in allow:
            fail(
                f"{pact} is listed in KNOWN_UNCOVERED but {provider} now has an always-running "
                "@PactFolder replay — delete the stale entry (#2327 is what empties this list)"
            )
            continue
        if covered or pact in allow:
            continue
        candidates = [c for c in classes if c.provider == provider]
        if candidates:
            detail = "; ".join(f"{c.path} ({c.why_not()})" for c in candidates)
            fail(
                f"{pact} has no provider replay that runs on a PR. Existing classes for "
                f"{provider}: {detail}. Add a @PactFolder class alongside the broker one — "
                "openbank-ledger-service carries exactly that pair."
            )
        else:
            fail(
                f"{pact} names provider {provider}, and NO @Provider(\"{provider}\") verification "
                "class exists anywhere. Nothing can show this contract still holds."
            )


def check_no_orphan_providers(pacts: dict[str, str], classes: list[VerificationClass]) -> None:
    named = set(pacts.values())
    for c in classes:
        if c.provider not in named:
            fail(
                f"{c.path} declares @Provider(\"{c.provider}\") but no pacts/*.json names that "
                "provider — a typo in the provider name reads exactly like coverage"
            )


def check_allowlist_is_live(pacts: dict[str, str], allow: set[str]) -> None:
    for pact in sorted(allow):
        if pact not in pacts:
            fail(f"KNOWN_UNCOVERED lists {pact}, which is not a committed pact — drop the stale entry")


def selftest() -> int:
    """Feed every check an input it MUST flag. A gate whose failure path never ran is unfalsified.

    In-memory only; no file in the working tree is read or written by these cases.
    """
    print("== selftest: each check must reject a known-bad input ==")
    results: list[tuple[str, bool]] = []

    def run(name: str, fn) -> None:
        global errors
        saved, errors = errors, []
        try:
            fn()
            caught = errors
        finally:
            errors = saved
        results.append((name, bool(caught)))
        print(f"  {name}: {'PASS (rejected)' if caught else 'FAIL (accepted bad input!)'}")
        if caught:
            print(f"      first message: {caught[0][:150]}")

    folder = VerificationClass("a/FolderTest.kt", "p", "folder")
    broker = VerificationClass("a/BrokerTest.kt", "p", "broker", gates=["@EnabledIfSystemProperty"])
    gated_folder = VerificationClass("a/GatedTest.kt", "p", "folder", gates=["@Disabled"])
    build_excluded = VerificationClass("a/ExcludedTest.kt", "p", "folder", excluded_by_build='exclude("**/ExcludedTest*")')
    one_pact = {"pacts/c-p.json": "p"}

    run("pact whose only replay is broker-gated", lambda: check_coverage(one_pact, [broker], set()))
    run("pact whose replay is @Disabled", lambda: check_coverage(one_pact, [gated_folder], set()))
    run("pact whose replay is excluded by build.gradle.kts", lambda: check_coverage(one_pact, [build_excluded], set()))
    run("pact with no @Provider class at all", lambda: check_coverage(one_pact, [], set()))
    run("covered pact still in KNOWN_UNCOVERED", lambda: check_coverage(one_pact, [folder], {"pacts/c-p.json"}))
    run("KNOWN_UNCOVERED names a pact that no longer exists", lambda: check_allowlist_is_live(one_pact, {"pacts/gone.json"}))
    run("@Provider name matches no pact", lambda: check_no_orphan_providers(one_pact, [VerificationClass("a/T.kt", "typo", "folder")]))
    # The parser itself, both directions. Detect the artifact, never the prose (#2291): grepping
    # src/test for the word "contract" once scored three services as verified off comment lines.
    def assert_(name: str, ok: bool, good: str, bad: str) -> None:
        results.append((name, ok))
        print(f"  {name}: {'PASS (' + good + ')' if ok else 'FAIL (' + bad + ')'}")

    commented = strip_comments('// @Provider("openbank-ghost") in a comment\n/* @PactFolder("../pacts") */\nclass X\n')
    assert_(
        "prose about @Provider is not read as a class",
        not re.search(r'@Provider\(\s*"[^"]+"\s*\)', commented) and "@PactFolder" not in commented,
        "ignored", "a comment would count as coverage!",
    )
    real = strip_comments(
        'import au.com.dius.pact.provider.junit5.PactVerificationContext\n'
        '@Provider("openbank-real")\n@PactFolder("../pacts")\nclass Y\n'
    )
    assert_(
        "real annotation survives comment stripping",
        bool(re.search(r'@Provider\(\s*"[^"]+"\s*\)', real)) and "@PactFolder" in real,
        "still seen", "stripped too much!",
    )
    # A @Provider inside a string-literal code sample, in a file that never imports pact, must not
    # count. openbank-flaky-test-hunter has exactly that, and it read as a verification class until
    # the import precondition was added.
    sample = '"""\npackage com.openbank.sample\n@Provider("openbank-sample-service")\nclass SamplePactProviderTest\n"""'
    assert_(
        "code sample with no pact import is not a class",
        "au.com.dius.pact" not in strip_comments(sample),
        "ignored", "a fixture would count as coverage!",
    )

    ok = all(flagged for _, flagged in results)
    print()
    print("selftest: ALL CHECKS CAN FAIL" if ok else "selftest: SOME CHECK IS UNFALSIFIED")
    return 0 if ok else 1


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()

    pacts = scan_pacts()
    classes = scan_verification_classes()

    check_coverage(pacts, classes, KNOWN_UNCOVERED)
    check_no_orphan_providers(pacts, classes)
    check_allowlist_is_live(pacts, KNOWN_UNCOVERED)

    replaying = {c.provider for c in classes if c.runs_on_pr}
    covered = sum(1 for p in pacts.values() if p in replaying)
    print(f"Committed pacts: {len(pacts)}")
    print(f"Replayed on a PR by an always-running @PactFolder class: {covered}")
    print(f"Declared uncovered (backlog, #2327): {len(KNOWN_UNCOVERED)}")
    print()
    for c in sorted(classes, key=lambda c: c.path):
        print(f"  {'RUNS ' if c.runs_on_pr else 'SKIP '} {c.provider:38s} {c.path}  [{c.why_not()}]")
    print()

    if errors:
        for e in errors:
            print(f"::error::{e}")
        print(f"\nFAIL: {len(errors)} provider-replay problem(s).")
        return 1
    print("OK — every committed pact is either replayed on a PR or a declared, still-accurate exception.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
