#!/usr/bin/env python3
"""Advisory diff check for tests alongside changed application source (#11692).

This checks evidence in the same component, not whether a test proves the new
behavior. A refactor, generated source, or a cross-component test may need a
reviewer to dismiss a warning. Kover's module floor remains a separate gate.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[2]
CODE_SUFFIXES = {".kt", ".java", ".ts", ".tsx", ".js", ".jsx"}


def packages(root: Path) -> set[str]:
    config = json.loads((root / "release-please-config.json").read_text())
    return set(config["packages"])


def is_ui_test(path: str) -> bool:
    if not path.startswith("openbank-admin-ui/"):
        return False
    relative = path.removeprefix("openbank-admin-ui/")
    return (
        relative.startswith(("src/test/", "e2e/"))
        or "/__tests__/" in relative
        or any(relative.endswith(suffix) for suffix in (
            ".test.ts", ".test.tsx", ".test.js", ".test.jsx",
            ".spec.ts", ".spec.tsx", ".spec.js", ".spec.jsx",
        ))
    )


def is_code(path: str, package: str) -> bool:
    if package == "openbank-admin-ui":
        return (
            path.startswith(f"{package}/src/")
            and Path(path).suffix in CODE_SUFFIXES
            and not path.endswith(".d.ts")
            and not is_ui_test(path)
        )
    return (
        path.startswith(f"{package}/src/main/")
        and Path(path).suffix in {".kt", ".java"}
    )


def is_test(path: str, package: str) -> bool:
    if package == "openbank-admin-ui":
        return is_ui_test(path)
    return (
        path.startswith(f"{package}/src/test/")
        and Path(path).suffix in {".kt", ".java"}
    )


def missing_test_evidence(
    changed: set[str], updated: set[str], component_names: set[str],
) -> dict[str, list[str]]:
    """Changed includes deletions; updated excludes deletions as test evidence."""
    findings = {}
    for component in sorted(component_names):
        code = sorted(path for path in changed if is_code(path, component))
        if code and not any(is_test(path, component) for path in updated):
            findings[component] = code
    return findings


def diff_paths(base: str, *, include_deletions: bool) -> set[str]:
    args = ["git", "diff", "--no-renames", "--name-only", "-z"]
    if not include_deletions:
        args.append("--diff-filter=ACMRT")
    for comparison in (f"{base}...HEAD", base):
        result = subprocess.run(
            [*args, comparison, "HEAD"] if comparison == base else [*args, comparison],
            cwd=ROOT, capture_output=True,
        )
        if result.returncode == 0:
            return {path.decode("utf-8") for path in result.stdout.split(b"\0") if path}
        if b"no merge base" not in result.stderr:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    raise RuntimeError(f"could not compare {base} to HEAD")


def self_test() -> int:
    names = {"openbank-ledger-service", "openbank-account-service", "openbank-admin-ui"}
    ledger_code = "openbank-ledger-service/src/main/kotlin/Ledger.kt"
    ledger_test = "openbank-ledger-service/src/test/kotlin/LedgerTest.kt"
    account_test = "openbank-account-service/src/test/kotlin/AccountTest.kt"
    ui_code = "openbank-admin-ui/src/app/page.tsx"
    ui_test = "openbank-admin-ui/src/test/page.test.tsx"
    cases = [
        ("service code without test warns", {ledger_code}, {ledger_code}, {"openbank-ledger-service"}),
        ("same-service test clears", {ledger_code, ledger_test}, {ledger_code, ledger_test}, set()),
        ("other-service test does not clear", {ledger_code, account_test},
         {ledger_code, account_test}, {"openbank-ledger-service"}),
        ("deleted test does not clear", {ledger_code, ledger_test},
         {ledger_code}, {"openbank-ledger-service"}),
        ("test-only does not warn", {ledger_test}, {ledger_test}, set()),
        ("service resource-only does not warn", {"openbank-ledger-service/src/main/resources/application.yaml"},
         {"openbank-ledger-service/src/main/resources/application.yaml"}, set()),
        ("UI source without test warns", {ui_code}, {ui_code}, {"openbank-admin-ui"}),
        ("UI src test clears", {ui_code, ui_test}, {ui_code, ui_test}, set()),
        ("UI e2e test clears", {ui_code, "openbank-admin-ui/e2e/page.spec.ts"},
         {ui_code, "openbank-admin-ui/e2e/page.spec.ts"}, set()),
        ("UI test file is not code", {ui_test}, {ui_test}, set()),
        ("UI type declaration is not code", {"openbank-admin-ui/src/types.d.ts"},
         {"openbank-admin-ui/src/types.d.ts"}, set()),
        ("unregistered component does not warn", {"other/src/main/kotlin/A.kt"},
         {"other/src/main/kotlin/A.kt"}, set()),
    ]
    for label, changed, updated, expected in cases:
        actual = set(missing_test_evidence(changed, updated, names))
        if actual != expected:
            print(f"self-test FAILED: {label}: expected {expected}, got {actual}")
            return 1
    print(f"self-test ok: {len(cases)} positive/negative component cases")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--enforce", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        component_names = packages(ROOT)
        changed = diff_paths(args.base, include_deletions=True)
        updated = diff_paths(args.base, include_deletions=False)
        findings = missing_test_evidence(
            changed, updated, component_names,
        )
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(f"::error::test-evidence-change could not inspect PR diff: {error}")
        return 2
    print(f"SUBJECTS={len(component_names)}  # registered components considered")
    print(f"test-evidence-change: {len(changed)} changed path(s), {len(updated)} added/updated path(s)")
    for component, code in findings.items():
        annotation = "error" if args.enforce else "warning"
        print(
            f"::{annotation}::{component}: {len(code)} application source file(s) changed "
            "without a changed test in the same component; review whether new behavior "
            "needs a test. Coverage floor alone cannot prove that."
        )
    print(f"test-evidence-change: {len(findings)} component(s) need test review")
    return 1 if args.enforce and findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
