#!/usr/bin/env python3
"""Emit the changed Gradle modules as GitHub Actions CSV and JSON outputs.

The matrix and the skip condition must derive from one identical sorted set.
Shared resolution inputs and the verifier itself affect every Gradle module;
unrelated files must not allocate metadata-verification runners. Paths are supplied one per line
by the already-authenticated changes detector in services-ci.yml.
"""

import json
import re
import sys
from pathlib import Path


BUILD_FILE = re.compile(r"(openbank-[^/]+)/build\.gradle\.kts")
SHARED_FILES = {
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "gradle/libs.versions.toml", "gradle/verification-metadata.xml",
    ".github/scripts/check-verification-metadata-complete.py",
    ".github/scripts/derive-verification-modules.py",
    ".github/workflows/dependabot-verification-metadata.yml",
}


def modules_from(paths: list[str], root: Path = Path(".")) -> list[str]:
    paths = [path.strip() for path in paths]
    if any(path in SHARED_FILES or path.startswith("build-logic/") for path in paths):
        return sorted(directory.name for directory in root.glob("openbank-*")
                      if directory.is_dir() and (directory / "build.gradle.kts").is_file())
    return sorted({match.group(1) for path in paths
                   if (match := BUILD_FILE.fullmatch(path))})


def outputs(paths: list[str]) -> str:
    modules = modules_from(paths)
    return ("verification-modules=" + ",".join(modules) + "\n"
            + "verification-modules-json=" + json.dumps(modules, separators=(",", ":")))


def main() -> None:
    if sys.argv[1:] == ["--self-test"]:
        import tempfile

        assert outputs(["openbank-z/build.gradle.kts", "docs/a.md",
                        "openbank-a/build.gradle.kts", "openbank-z/build.gradle.kts",
                        "openbank-a/src/test/build.gradle.kts"]) == (
            'verification-modules=openbank-a,openbank-z\n'
            'verification-modules-json=["openbank-a","openbank-z"]')
        assert outputs([""]) == "verification-modules=\nverification-modules-json=[]"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("openbank-z", "openbank-a", "openbank-admin-ui", "other"):
                (root / name).mkdir()
                if name != "openbank-admin-ui":
                    (root / name / "build.gradle.kts").touch()
            for shared in sorted(SHARED_FILES) + ["build-logic/src/main/kotlin/convention.gradle.kts"]:
                assert modules_from([shared, "openbank-z/build.gradle.kts"], root) == [
                    "openbank-a", "openbank-z"], shared
            # Fleet membership is discovered, including a newly introduced service.
            (root / "openbank-new").mkdir()
            (root / "openbank-new" / "build.gradle.kts").touch()
            assert modules_from(["gradle/verification-metadata.xml"], root) == [
                "openbank-a", "openbank-new", "openbank-z"]
            assert modules_from(["docs/a.md", "build-logic-not-shared/a.md"], root) == []
        print("derive-verification-modules: self-test OK")
        return
    if len(sys.argv) != 1:
        raise SystemExit("usage: derive-verification-modules.py [--self-test]")
    print(outputs(list(sys.stdin)))


if __name__ == "__main__":
    main()
