#!/usr/bin/env python3
"""Derive the Gradle modules whose TESTS read OTHER modules' source trees.

services-ci.yml builds a module on a PR only when a file under `^<module>/` changes.
A test that walks the fleet (`File("..").listFiles { it.name.startsWith("openbank-") }`)
or pins one sibling (`File("../openbank-x/...")`) has inputs OUTSIDE its own directory,
so the path filter cannot see them: #10010 added a dead-letter gauge to referral-service,
libs-runtime's OutboxDeadLetterAlertNamingTest never ran, and main went red (#11513).

The edges are DERIVED from the test sources, never hand-listed:

  (default)  print the modules owning a FLEET-SCANNING test (reads the repo root and
             filters on the `openbank-` prefix). These must build on any module change.
  --edges    print `<reader> <target>` pairs for tests that pin ONE sibling module by
             path. The reader must build when the target changes.
  --expand "<modules>"  print the modules to ADD to that build set (one per line), with a
             `Decision:` line per addition on stderr. Used by services-ci.yml.
  --input-paths-stdin  with --expand, read the filtered changed paths from stdin. A
             library's own src/test is removed by filter-lib-test-only-paths.py:
             with no remaining Gradle-module input, its test cannot change a
             sibling's source tree. Omit this flag to preserve conservative
             expansion for other callers.
  --self-test  known-positive + known-negative fixtures.

An empty fleet-scanner set on the real repo exits 1: today libs-runtime is one, so
empty means the probe broke, not that the fleet is clean.
"""
from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

ROOT_READ = re.compile(r'(?:File|Path\.of|Paths\.get)\(\s*"\.\."\s*\)')
PREFIX_FILTER = re.compile(r'startsWith\(\s*"openbank-"\s*\)')
PINNED = re.compile(r'(?:File\(\s*"\.\./|Path\.of\(\s*"\.\.",\s*"|Paths\.get\(\s*"\.\.",\s*")(openbank-[a-z0-9-]+)')
DIRECT_FILE = re.compile(r'File\(\s*"\.\./(openbank-[^"]+)"\s*\)')
MAIN_TREE_WALK = re.compile(r'File\(\s*module\s*,\s*"src/main/kotlin"\s*\)\.walkTopDown\(\)')
TEST_SOURCE = re.compile(r"^openbank-[^/]+/src/test/")


def is_module(root: Path, name: str) -> bool:
    return (root / name / "build.gradle.kts").is_file() or (root / name / "build.gradle").is_file()


def scan(root: Path) -> tuple[set[str], set[tuple[str, str]], set[tuple[str, str]], bool]:
    fleet: set[str] = set()
    edges: set[tuple[str, str]] = set()
    direct: set[tuple[str, str]] = set()
    main_tree_only = True
    for mod in sorted(p for p in root.iterdir() if p.is_dir() and is_module(root, p.name)):
        test_dir = mod / "src" / "test"
        if not test_dir.is_dir():
            continue
        for f in test_dir.rglob("*"):
            if f.suffix not in (".kt", ".java") or not f.is_file():
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
            if ROOT_READ.search(text) and PREFIX_FILTER.search(text):
                fleet.add(mod.name)
                # Unknown traversal shape stays conservative on test-only changes.
                main_tree_only &= bool(MAIN_TREE_WALK.search(text)) and len(ROOT_READ.findall(text)) == 1
            for target in PINNED.findall(text):
                target = target.rstrip("-")
                if target != mod.name and is_module(root, target):
                    edges.add((mod.name, target))
            for path in DIRECT_FILE.findall(text):
                direct.add((mod.name, path))
    return fleet, edges, direct, main_tree_only


def has_module_input(root: Path, paths: list[str], main_tree_only: bool = False) -> bool:
    """Whether a filtered path belongs to a real Gradle module, not docs or GitOps."""
    return any("/" in path and is_module(root, path.split("/", 1)[0])
               and not (main_tree_only and TEST_SOURCE.match(path)) for path in paths)


def direct_readers(direct: set[tuple[str, str]], paths: list[str]) -> set[str]:
    return {
        reader for reader, watched in direct
        if any(path == watched or path.startswith(watched.rstrip("/") + "/") for path in paths)
    }


def self_test() -> int:
    with tempfile.TemporaryDirectory() as d:
        r = Path(d)

        def write(mod: str, rel: str, body: str) -> None:
            (r / mod).mkdir(parents=True, exist_ok=True)
            (r / mod / "build.gradle.kts").write_text("")
            p = r / mod / "src/test/kotlin" / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(body)

        write("openbank-scanner", "a/ScanTest.kt",
              'val m = File("..").listFiles { f -> f.name.startsWith("openbank-") }; '
              'File(module, "src/main/kotlin").walkTopDown()')
        write("openbank-pinner", "b/PinTest.kt",
              'val y = File("../openbank-target/src/main/resources/application.yaml")')
        write("openbank-target", "c/OwnTest.kt",
              'val z = File("src/main/kotlin/X.kt"); val s = "openbank-x".startsWith("openbank-")')
        write("openbank-infra-reader", "d/InfraTest.kt",
              'val i = File("../openbank-infra/gitops/x.yaml")')  # openbank-infra is not a module here
        fleet, edges, direct, main_tree_only = scan(r)
        ok = True
        if fleet != {"openbank-scanner"}:
            print(f"FAIL: fleet set {sorted(fleet)} != ['openbank-scanner']")
            ok = False
        if not main_tree_only:
            print("FAIL: known production-tree scanner was not recognized")
            ok = False
        if edges != {("openbank-pinner", "openbank-target")}:
            print(f"FAIL: edges {sorted(edges)} != [('openbank-pinner', 'openbank-target')]")
            ok = False
        if ("openbank-infra-reader", "openbank-infra/gitops/x.yaml") not in direct:
            print("FAIL: direct GitOps input was not discovered")
            ok = False
        if direct_readers(direct, ["openbank-infra/gitops/x.yaml"]) != {"openbank-infra-reader"}:
            print("FAIL: GitOps-only change did not select its direct test reader")
            ok = False
        if direct_readers(direct, ["openbank-infra/gitops/unrelated.yaml"]):
            print("FAIL: unrelated GitOps change selected a direct test reader")
            ok = False
        cases = [
            ([], False),
            (["docs/runbook.md", "openbank-infra/gitops/x.yaml"], False),
            (["openbank-target/src/main/kotlin/X.kt"], True),
            (["openbank-target/src/test/kotlin/XTest.kt"], True),
        ]
        for paths, want in cases:
            if has_module_input(r, paths) != want:
                print(f"FAIL: module input for {paths} != {want}")
                ok = False
        if has_module_input(r, ["openbank-target/src/test/kotlin/XTest.kt"], main_tree_only):
            print("FAIL: test-only change selected a production-tree scanner")
            ok = False
        if not has_module_input(r, ["openbank-target/src/test/kotlin/XTest.kt"], False):
            print("FAIL: unknown scanner shape did not fail closed")
            ok = False
    print("self-test:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    root = Path(__file__).resolve().parents[2]
    fleet, edges, direct, main_tree_only = scan(root)
    if "--expand" in argv:
        if not fleet:
            print("ERROR: empty fleet-scanner set; the probe is broken.", file=sys.stderr)
            return 1
        paths = [line.strip() for line in sys.stdin if line.strip()] if "--input-paths-stdin" in argv else []
        module_input = has_module_input(root, paths, main_tree_only) if "--input-paths-stdin" in argv else True
        changed = set(argv[argv.index("--expand") + 1].split())
        added: list[str] = []
        if module_input:
            for m in sorted(fleet):
                if m not in changed:
                    print(f"Decision: module change -> add fleet-scanning test module {m} (#11513).", file=sys.stderr)
                    added.append(m)
            for reader, target in sorted(edges):
                if target in changed and reader not in changed and reader not in added:
                    print(f"Decision: {target} changed -> add {reader} (its tests read {target}'s tree).", file=sys.stderr)
                    added.append(reader)
        for reader in sorted(direct_readers(direct, paths)):
            if reader not in changed and reader not in added:
                print(f"Decision: changed direct test input -> add {reader}.", file=sys.stderr)
                added.append(reader)
        if not module_input and not added:
            print("Decision: no changed module or direct test input after filtering; "
                  "skip cross-module readers.", file=sys.stderr)
        for m in added:
            print(m)
        return 0
    if "--edges" in argv:
        for reader, target in sorted(edges):
            print(reader, target)
        return 0
    if not fleet:
        print("ERROR: no fleet-scanning test found; the probe is broken "
              "(openbank-libs-runtime's OutboxDeadLetterAlertNamingTest must match).", file=sys.stderr)
        return 1
    for m in sorted(fleet):
        print(m)
    print(f"SUBJECTS={len(fleet)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
