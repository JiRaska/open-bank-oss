#!/usr/bin/env python3
"""Emit the changed Gradle modules as GitHub Actions CSV and JSON outputs.

The matrix and the skip condition must derive from one identical sorted set.
Only a module's root build file changes dependency resolution; unrelated files
must not allocate metadata-verification runners. Paths are supplied one per line
by the already-authenticated changes detector in services-ci.yml.
"""

import json
import re
import sys


BUILD_FILE = re.compile(r"(openbank-[^/]+)/build\.gradle\.kts")


def modules_from(paths: list[str]) -> list[str]:
    return sorted({match.group(1) for path in paths
                   if (match := BUILD_FILE.fullmatch(path.strip()))})


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
        print("derive-verification-modules: self-test OK")
        return
    if len(sys.argv) != 1:
        raise SystemExit("usage: derive-verification-modules.py [--self-test]")
    print(outputs(list(sys.stdin)))


if __name__ == "__main__":
    main()
