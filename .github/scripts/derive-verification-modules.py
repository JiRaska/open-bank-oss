#!/usr/bin/env python3
"""Emit the changed Gradle modules as GitHub Actions CSV and JSON outputs.

The matrix and the skip condition must derive from one identical sorted set.
Shared Gradle inputs can change every module's resolution, so they select the
whole discovered fleet. Unrelated files must not allocate verification runners.
Paths are supplied one per line by the already-authenticated changes detector
in services-ci.yml.
"""

import json
import pathlib
import re
import sys
import tempfile


BUILD_FILE = re.compile(r"(openbank-[^/]+)/build\.gradle\.kts")


SHARED_FILES = {"settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                "gradlew", "gradlew.bat"}


def shared_input(path: str) -> bool:
    return (path in SHARED_FILES or path.startswith("build-logic/")
            or path.startswith("gradle/")
            or path.startswith("openbank-libs/gradle/"))


def modules_from(paths: list[str], root: pathlib.Path = pathlib.Path(".")) -> list[str]:
    paths = [path.strip() for path in paths]
    if any(shared_input(path) for path in paths):
        modules = sorted(directory.name for directory in root.iterdir()
                         if directory.is_dir() and directory.name.startswith("openbank-")
                         and (directory / "build.gradle.kts").is_file())
        if not modules:
            raise ValueError("shared Gradle input changed but no Gradle modules were discovered")
        return modules
    return sorted({match.group(1) for path in paths
                   if (match := BUILD_FILE.fullmatch(path))})


def outputs(paths: list[str]) -> str:
    modules = modules_from(paths)
    return ("verification-modules=" + ",".join(modules) + "\n"
            + "verification-modules-json=" + json.dumps(modules, separators=(",", ":")))


def main() -> None:
    if sys.argv[1:] == ["--self-test"]:
        assert outputs(["openbank-z/build.gradle.kts", "docs/a.md",
                        "openbank-a/build.gradle.kts", "openbank-z/build.gradle.kts",
                        "openbank-a/src/test/build.gradle.kts"]) == (
            'verification-modules=openbank-a,openbank-z\n'
            'verification-modules-json=["openbank-a","openbank-z"]')
        assert outputs([""]) == "verification-modules=\nverification-modules-json=[]"
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for name in ("openbank-a", "openbank-z", "openbank-admin-ui"):
                (root / name).mkdir()
            for name in ("openbank-a", "openbank-z"):
                (root / name / "build.gradle.kts").touch()
            for path in sorted(SHARED_FILES) + [
                "gradle/verification-metadata.xml",
                "build-logic/src/main/kotlin/pins.gradle.kts",
                "openbank-libs/gradle/libs.versions.toml",
            ]:
                assert modules_from([path], root) == ["openbank-a", "openbank-z"], path
            assert modules_from(["docs/architecture.md"], root) == []
            assert modules_from(["openbank-z/build.gradle.kts"], root) == ["openbank-z"]
        with tempfile.TemporaryDirectory() as directory:
            try:
                modules_from(["settings.gradle.kts"], pathlib.Path(directory))
            except ValueError:
                pass
            else:
                raise AssertionError("empty fleet must not silently skip verification")
        print("derive-verification-modules: self-test OK")
        return
    if len(sys.argv) != 1:
        raise SystemExit("usage: derive-verification-modules.py [--self-test]")
    print(outputs(list(sys.stdin)))


if __name__ == "__main__":
    main()
