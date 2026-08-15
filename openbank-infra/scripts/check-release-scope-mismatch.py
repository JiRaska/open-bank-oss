#!/usr/bin/env python3
"""Release-scope-mismatch gate (rules.yaml: change_requirements.release_scope_mismatch).

CLAUDE.md rule 2 says a `<service>/version.txt` bump is only warranted when a change
touches `<service>/src/main/**` (the deployable artifact). But release-please attributes
a commit to a component by the FILES IT TOUCHES under that component's directory,
REGARDLESS of the commit's typed scope (RELEASE.md) — so a `feat`/`fix`/`perf`/`security`
(or breaking-marked) commit that only touches `<service>/src/test/**`, a root file like
`<service>/openapi.yaml`, or gitops/docs outside the service dir entirely still proposes a
release, because release-please only ever looks at "was *a* file under this dir touched"
and "what was the commit type", never the sub-path.

Observed live: PR #547 (`feat(finrep): register ArgoCD Application ...`) touched only a new
`src/test/**` boot-smoke test inside `openbank-finrep-service/` plus gitops/docs files
outside it — no `src/main/**` change at all — and release-please still opened PR #551
proposing finrep-service 0.4.0. Harmless (the rebuilt artifact is byte-identical to 0.3.3,
just re-tagged) but noisy, and the same mechanism misfires for any test-only `fix:` or an
`openapi.yaml`-only editorial `fix:`.

This gate flags that pattern on the ORIGINATING PR (not the release-please PR, which is
generated and shouldn't be hand-edited per rule 3): a release-triggering commit type whose
diff touches a package directory without touching that package's release-worthy subtree.
Suggested remedy is always non-destructive: re-type the commit (test:/docs:/chore:/
refactor:/build:/ci:) rather than editing any release artifact.

Classifies from the PR title (the default squash-commit subject on this repo — CONTRIBUTING.md/
rules.yaml), since the actual squash commit does not exist yet at PR-CI time. A PR title edited
at merge time can still diverge from what this gate saw; that residual gap is why this gate
stays advisory (ADR-0144) rather than a hard block.

stdlib-only; diff-scoped like check-threat-model-diff.py / check-api-contract.py.

Usage:
    check-release-scope-mismatch.py --title "<pr title>" [--base <ref>] [--changed-files <path>] [--enforce]

Modes (ADR-0144 gate graduation):
    default    advisory — findings are ::warning annotations, exit 0
    --enforce  findings are ::error annotations, exit 1
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
CONFIG = REPO / "release-please-config.json"

# rules.yaml: commits.types — only these (or a breaking-change marker/footer) move the
# release axis. refactor/docs/test/chore/build/ci carry semver_bump: none.
RELEASE_TRIGGERING_TYPES = {"feat", "fix", "perf", "security"}

# <type>(<scope>)!?: <summary>  — scope is optional, `!` marks a breaking change.
CONVENTIONAL_RE = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]*\))?(?P<breaking>!)?:\s")

# The release-worthy subtree per package. Every service package uses src/main/** (the
# deployable-artifact rule, CLAUDE.md rule 2 / rules.yaml service_code_change); admin-ui has
# no src/main split and uses its own src/** rule (rules.yaml admin_ui_code_change).
DEFAULT_RELEASE_PREFIX = "src/main/"
PACKAGE_RELEASE_PREFIX_OVERRIDE = {
    "openbank-admin-ui": "src/",
}


def load_packages() -> list[str]:
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    return sorted(data.get("packages", {}).keys())


def changed_files(base: str) -> list[str]:
    res = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],  # 3-dot: the squash delta
        capture_output=True, text=True, cwd=REPO,
    )
    if res.returncode != 0 and "no merge base" in res.stderr:
        # Shallow CI checkout has no merge base; HEAD is the PR merge ref, so a 2-dot
        # diff against the base sha IS the squash delta (same fallback as the sibling
        # diff-scoped gates).
        res = subprocess.run(
            ["git", "diff", "--name-only", base, "HEAD"],
            capture_output=True, text=True, cwd=REPO,
        )
    if res.returncode != 0:
        print(f"::error::release-scope-mismatch gate: git diff against {base} failed: {res.stderr.strip()}")
        sys.exit(1)
    return [line for line in res.stdout.splitlines() if line.strip()]


def classify(title: str, has_breaking_footer: bool) -> tuple[str | None, bool]:
    """Return (type, is_release_triggering) parsed from a conventional-commit subject."""
    m = CONVENTIONAL_RE.match(title.strip())
    if not m:
        return None, False
    ctype = m.group("type")
    breaking = bool(m.group("breaking")) or has_breaking_footer
    triggering = breaking or ctype in RELEASE_TRIGGERING_TYPES
    return ctype, triggering


def find_mismatches(changed: list[str], packages: list[str]) -> list[tuple[str, list[str]]]:
    findings = []
    for pkg in packages:
        pkg_prefix = f"{pkg}/"
        touched = [f for f in changed if f.startswith(pkg_prefix)]
        if not touched:
            continue
        release_prefix = pkg_prefix + PACKAGE_RELEASE_PREFIX_OVERRIDE.get(pkg, DEFAULT_RELEASE_PREFIX)
        if any(f.startswith(release_prefix) for f in touched):
            continue  # a real release-worthy change is in the diff — nothing to flag
        findings.append((pkg, touched))
    return findings


def self_test() -> int:
    """Falsify the commit classifier and the release-scope comparison.

    release-please needs BOTH axes to cut a release: a releasing TYPE and a touched path
    inside the package's release subtree. This gate catches the combination that produces a
    version bump for an artifact that did not change — a feat/fix PR touching only src/test,
    docs or gitops under a package directory.

    Both halves classify strings, and both have a lenient direction that reports clean: a
    type the regex fails to parse is "not releasing", and a package prefix that matches too
    eagerly makes every diff look release-worthy.
    """
    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    # --- the classifier ------------------------------------------------------------------
    for title, want in (
        ("feat(ledger): add x", ("feat", True)),
        ("fix(ledger): repair x", ("fix", True)),
        ("perf(ledger): speed x", ("perf", True)),
        ("security(ledger): patch x", ("security", True)),
        # Hidden types do not release at any path — that is the lever when a PR changes no
        # shipped code.
        ("docs(ledger): explain x", ("docs", False)),
        ("chore(ledger): tidy x", ("chore", False)),
        ("ci(ledger): wire x", ("ci", False)),
        ("refactor(ledger): move x", ("refactor", False)),
        # A breaking marker releases a MAJOR even on a hidden type — the documented lever.
        ("docs(ledger)!: drop x", ("docs", True)),
        ("chore!: drop x", ("chore", True)),
        # No scope at all is still a conventional commit.
        ("feat: add x", ("feat", True)),
    ):
        case(f"classify({title!r})", classify(title, False), want)

    # A BREAKING CHANGE footer releases a major even with no `!` in the subject — missing this
    # would classify a real breaking change as non-releasing, the lenient direction.
    case("a BREAKING CHANGE footer makes a hidden type releasing",
         classify("docs(ledger): drop x", True), ("docs", True))

    # A non-conventional subject is not classifiable. It must yield None rather than a guess:
    # inventing a type here would either force or suppress a release on a subject nobody
    # parsed.
    for junk in ("no colon here", "Feat(ledger): capitalised", "123(ledger): numeric", ""):
        case(f"classify({junk!r}) is unparseable", classify(junk, False), (None, False))

    # --- the scope comparison -------------------------------------------------------------
    pkgs = ["openbank-ledger-service", "openbank-admin-ui"]

    # THE DEFECT: a package touched only outside its release subtree.
    case("test-only changes under a package are a mismatch",
         find_mismatches(["openbank-ledger-service/src/test/kotlin/T.kt"], pkgs),
         [("openbank-ledger-service", ["openbank-ledger-service/src/test/kotlin/T.kt"])])
    # A real source change is release-worthy — nothing to flag.
    case("a src/main change is not a mismatch",
         find_mismatches(["openbank-ledger-service/src/main/kotlin/A.kt"], pkgs), [])
    # MIXED: one release-worthy file is enough to justify the bump.
    case("a mixed diff with one src/main file is not a mismatch",
         find_mismatches(["openbank-ledger-service/src/test/kotlin/T.kt",
                          "openbank-ledger-service/src/main/kotlin/A.kt"], pkgs), [])
    # A package not touched at all must not appear.
    case("an untouched package is not reported", find_mismatches(["docs/adr/x.md"], pkgs), [])
    # PREFIX PRECISION: a sibling directory that merely starts with the package name is a
    # different package. Matching it would attribute someone else's diff to this one.
    case("a same-prefix sibling directory is not this package",
         find_mismatches(["openbank-ledger-service-extras/src/test/T.kt"], pkgs), [])

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: release-scope-mismatch is falsifiable (21 cases)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--title", required=False, help="PR title (the default squash-commit subject)")
    ap.add_argument("--body", default="", help="PR body, scanned for a BREAKING CHANGE: footer")
    ap.add_argument("--base", default="origin/main", help="PR base ref/sha (3-dot diff)")
    ap.add_argument("--changed-files", help="file with a newline-separated changed-file list (skips git)")
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.title:
        ap.error("--title is required")

    ctype, triggering = classify(args.title, "BREAKING CHANGE:" in args.body)
    if not triggering:
        print(
            f"release-scope-mismatch gate: PR title type '{ctype or '(unparsed)'}' does not move the "
            "release axis — nothing to check."
        )
        return 0

    if args.changed_files:
        changed = [
            line.strip()
            for line in pathlib.Path(args.changed_files).read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    else:
        changed = changed_files(args.base)

    packages = load_packages()
    findings = find_mismatches(changed, packages)

    level = "error" if args.enforce else "warning"
    for pkg, touched in findings:
        release_prefix = pkg + "/" + PACKAGE_RELEASE_PREFIX_OVERRIDE.get(pkg, DEFAULT_RELEASE_PREFIX)
        sample = ", ".join(touched[:5]) + ("..." if len(touched) > 5 else "")
        print(
            f"::{level}::release-scope-mismatch gate: PR title type '{ctype}' will make release-please "
            f"propose a release for '{pkg}', but no changed file is under '{release_prefix}' — only "
            f"{sample}. release-please attributes by directory, not by src/main vs. the rest (RELEASE.md), "
            f"so this still proposes a version bump for an unchanged deployable artifact. If this PR "
            f"doesn't actually change {pkg}'s shipped code, re-type the commit (test:/docs:/chore:/"
            f"refactor:/build:/ci:) instead of feat/fix/perf/security."
        )

    if findings and args.enforce:
        return 1
    if findings:
        print(
            f"release-scope-mismatch gate: {len(findings)} finding(s) — advisory until the ADR-0144 "
            "target_enforce_date; will become a hard gate."
        )
    else:
        print("release-scope-mismatch gate: no release-triggering commit without a release-worthy path change.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
