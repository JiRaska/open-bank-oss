#!/usr/bin/env python3
"""Build modules affected by shared library changes in CI or deployment.

The scoped libraries have a small consumer graph. Read the Gradle project
declarations, including test dependencies, and follow transitive edges. Unknown
dependency syntax or a convention-plugin reference falls back to the full matrix.
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

from library_dependency_graph import read_dependencies, reverse_dependents


ROOT = Path(__file__).resolve().parents[2]
SCOPED = {"openbank-libs-lending", "openbank-libs-iso20022", "openbank-libs-finance"}
RUNTIME_CHANGE = re.compile(
    r"^(openbank-(?:libs(?:-[A-Za-z0-9._-]+)?|client-[A-Za-z0-9._-]+))/"
    r"(?:src/main(?:/|$)|build\.gradle\.kts$|gradle/)"
)


def affected(root: Path, changed: list[str], buildable: set[str]) -> set[str]:
    # The workflow's code-global regex names the established core libraries. New
    # shared libraries must remain fleet-wide until their consumer graph is reviewed.
    if any(
        path.split("/", 1)[0].startswith("openbank-libs")
        and path.split("/", 1)[0] not in SCOPED
        for path in changed
        if "/" in path
    ):
        return buildable
    seeds = {path.split("/", 1)[0] for path in changed if "/" in path} & SCOPED
    if not seeds:
        return set()
    if not seeds <= buildable:
        return buildable
    # A concurrent dependency edit can remove an edge from the post-change graph.
    # Its previous consumers are not recoverable from this checkout alone.
    if any(path.endswith("/build.gradle.kts") and path.split("/", 1)[0] not in seeds for path in changed):
        return buildable

    return consumers(root, seeds, buildable, include_tests=True)


def consumers(root: Path, seeds: set[str], buildable: set[str], include_tests: bool) -> set[str]:
    graph = read_dependencies(root, include_tests)
    if graph is None:
        return buildable
    try:
        return reverse_dependents(graph, seeds) & buildable
    except ValueError:
        return buildable


def runtime_affected(root: Path, changed: list[str], buildable: set[str]) -> set[str]:
    """Deployment follows runtime edges; test-only changes never seed a rollout.

    Global build inputs remain the shell caller's responsibility. A concurrent
    consumer build-file change may remove an edge from the checked-out graph:
    without a trustworthy before graph, rebuild the complete deployable set.
    """
    seeds = {match.group(1) for path in changed if (match := RUNTIME_CHANGE.match(path))}
    if not seeds:
        return set()
    if any(path.endswith("/build.gradle.kts") and path.split("/", 1)[0] not in seeds for path in changed):
        return buildable
    return consumers(root, seeds, buildable, include_tests=False)


def self_test() -> int:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        scripts = {
            "openbank-libs-lending": "",
            "openbank-lending-service": 'implementation(project(":openbank-libs-lending"))',
            "openbank-risk-engine": 'testImplementation(project(":openbank-libs-lending"))',
            "openbank-simulation": 'implementation(project(":openbank-risk-engine"))',
            "openbank-unrelated": "",
            "openbank-libs-future": "",
        }
        for name, source in scripts.items():
            (root / name).mkdir()
            (root / name / "build.gradle.kts").write_text(source)
        all_modules = set(scripts)
        changed = ["openbank-libs-lending/src/main/kotlin/X.kt"]
        expected = all_modules - {"openbank-unrelated", "openbank-libs-future"}
        assert affected(root, changed, all_modules) == expected
        assert affected(root, ["openbank-libs-future/src/main/kotlin/X.kt"], all_modules) == all_modules
        assert affected(root, changed + ["openbank-libs-future/build.gradle.kts"], all_modules) == all_modules
        assert affected(root, changed + ["openbank-lending-service/build.gradle.kts"], all_modules) == all_modules
        assert affected(root, ["openbank-libs-lending/src/test/kotlin/X.kt"], all_modules) == expected
        assert affected(root, ["openbank-unrelated/src/main/kotlin/X.kt"], all_modules) == set()
        assert runtime_affected(root, changed, all_modules) == {
            "openbank-libs-lending", "openbank-lending-service",
        }
        assert runtime_affected(root, ["openbank-libs-lending/src/test/kotlin/X.kt"], all_modules) == set()
        assert runtime_affected(root, ["openbank-libs-lending/docs/README.md"], all_modules) == set()
        assert runtime_affected(root, ["openbank-libs-new/src/main/kotlin/X.kt"], all_modules) == all_modules
        assert runtime_affected(root, changed + ["openbank-lending-service/build.gradle.kts"], all_modules) == all_modules
        assert runtime_affected(root, changed, {"openbank-unrelated"}) == set()
        (root / "openbank-unrelated" / "build.gradle.kts").write_text("implementation(project(dynamicName))")
        assert affected(root, changed, all_modules) == all_modules
        assert runtime_affected(root, changed, all_modules) == all_modules
    # Prove the current repository still has a bounded, non-empty real graph.
    modules = {p.parent.name for p in ROOT.glob("openbank-*/build.gradle.kts")}
    actual = affected(ROOT, ["openbank-libs-lending/src/main/kotlin/X.kt"], modules)
    assert {"openbank-libs-lending", "openbank-lending-service", "openbank-risk-engine", "openbank-simulation"} <= actual
    assert actual != modules
    iso = affected(ROOT, ["openbank-libs-iso20022/src/main/kotlin/X.kt"], modules)
    assert {"openbank-libs-iso20022", "openbank-transaction-service", "openbank-simulation"} <= iso
    assert iso != modules
    # The scope selection moved out of services-ci.yml's `detect` step (workflow-run-step-size
    # headroom); the invocations and the lib-path regex now live in the extracted script.
    workflow = (ROOT / ".github/scripts/service-ci-select-modules.sh").read_text()
    assert workflow.count('python3 .github/scripts/service-ci-library-dependents.py "$ALL"') == 2
    for line in workflow.splitlines():
        if "Decision: code-global change" in line:
            continue
        if "if grep -qE '^(openbank-libs/" in line:
            assert "openbank-libs-lending/" not in line
            assert "openbank-libs-iso20022/" not in line
    finance = affected(ROOT, ["openbank-libs-finance/src/main/kotlin/X.kt"], modules)
    assert "openbank-libs-finance" in finance and finance != modules
    print(f"self-test OK: lending {len(actual)}, ISO 20022 {len(iso)}, finance {len(finance)} of {len(modules)} modules")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        raise SystemExit(self_test())
    runtime = len(sys.argv) == 3 and sys.argv[1] == "--runtime"
    if not runtime and len(sys.argv) != 2:
        raise SystemExit("usage: service-ci-library-dependents.py [--runtime] '<buildable modules>' < changed-files")
    resolver = runtime_affected if runtime else affected
    selected = resolver(ROOT, [line.strip() for line in sys.stdin if line.strip()], set(sys.argv[-1].split()))
    print("\n".join(sorted(selected)))
