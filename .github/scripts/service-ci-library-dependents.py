#!/usr/bin/env python3
"""Build modules affected by a small shared library change in Services CI.

The two scoped libraries have a small consumer graph. Read the Gradle project
declarations, including test dependencies, and follow transitive edges. Unknown
dependency syntax or a convention-plugin reference falls back to the full matrix.
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCOPED = {"openbank-libs-lending", "openbank-libs-iso20022"}
PROJECT = re.compile(r'project\(\s*"(:openbank-[^"]+)"\s*\)')


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

    # A convention plugin can add project dependencies outside a module's build file.
    # Until that relationship is modelled here, it requires the full matrix.
    for script in [root / "build.gradle.kts", *root.glob("build-logic/**/*.gradle.kts")]:
        if script.is_file() and any(name in script.read_text() for name in seeds):
            return buildable

    dependencies: dict[str, set[str]] = {}
    for script in root.glob("openbank-*/build.gradle.kts"):
        source = re.sub(r"//[^\n]*", "", script.read_text())
        matches = list(PROJECT.finditer(source))
        refs = {match.group(1)[1:] for match in matches}
        # Dynamic project(...) declarations cannot be enumerated safely.
        if len(re.findall(r"\bproject\s*\(", source)) != len(matches):
            return buildable
        dependencies[script.parent.name] = refs
    if not seeds <= dependencies.keys():
        return buildable

    closure = set(seeds)
    while True:
        new = closure | {name for name, refs in dependencies.items() if refs & closure}
        if new == closure:
            return closure & buildable
        closure = new


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
        (root / "openbank-unrelated" / "build.gradle.kts").write_text("implementation(project(dynamicName))")
        assert affected(root, changed, all_modules) == all_modules
    # Prove the current repository still has a bounded, non-empty real graph.
    modules = {p.parent.name for p in ROOT.glob("openbank-*/build.gradle.kts")}
    actual = affected(ROOT, ["openbank-libs-lending/src/main/kotlin/X.kt"], modules)
    assert {"openbank-libs-lending", "openbank-lending-service", "openbank-risk-engine", "openbank-simulation"} <= actual
    assert actual != modules
    iso = affected(ROOT, ["openbank-libs-iso20022/src/main/kotlin/X.kt"], modules)
    assert {"openbank-libs-iso20022", "openbank-transaction-service", "openbank-simulation"} <= iso
    assert iso != modules
    workflow = (ROOT / ".github/workflows/services-ci.yml").read_text()
    assert workflow.count('python3 .github/scripts/service-ci-library-dependents.py "$ALL"') == 2
    for line in workflow.splitlines():
        if "Decision: code-global change" in line:
            continue
        if "if grep -qE '^(openbank-libs/" in line:
            assert "openbank-libs-lending/" not in line
            assert "openbank-libs-iso20022/" not in line
    print(f"self-test OK: lending {len(actual)}, ISO 20022 {len(iso)} of {len(modules)} modules")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        raise SystemExit(self_test())
    if len(sys.argv) != 2:
        raise SystemExit("usage: service-ci-library-dependents.py '<buildable modules>' < changed-files")
    selected = affected(ROOT, [line.strip() for line in sys.stdin if line.strip()], set(sys.argv[1].split()))
    print("\n".join(sorted(selected)))
