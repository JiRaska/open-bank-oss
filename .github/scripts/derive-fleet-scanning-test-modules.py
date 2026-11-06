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
from fnmatch import fnmatchcase
from pathlib import Path

ROOT_READ = re.compile(r'(?:File|Path\.of|Paths\.get)\(\s*"\.\."\s*\)')
PREFIX_FILTER = re.compile(r'startsWith\(\s*"openbank-"\s*\)')
PINNED = re.compile(r'(?:File\(\s*"\.\./|Path\.of\(\s*"\.\.",\s*"|Paths\.get\(\s*"\.\.",\s*")(openbank-[a-z0-9-]+)')
DIRECT_FILE = re.compile(r'File\(\s*"\.\./(openbank-[^"]+)"\s*\)')
# Any string literal that names a repository path OUTSIDE the test's own module, however the
# test joins it: `File("../openbank-infra/gitops/components/pension/pension-service.yaml")`,
# `File(repoRoot(), "docs/adr/0039-ledger-as-golden-source-balance-as-projection.md")` after a
# parentFile walk, `Path.of("..", "openbank-contracts", ...)` — all carry the path as a literal.
# A bare top-level NON-module directory ("openbank-infra", "pacts", "docs") is a whole-tree read.
# A bare MODULE name is a service identifier, not a path, and is ignored; module trees are
# covered by PINNED edges and by the `^<module>/` fan-out. Measured 2026-11-04: without this,
# every change under openbank-infra/, docs/ or .github/ fell to the full-fleet fallback — about
# 20 PR branches with 86 queued builds each — while the tests that read those trees number 2-5.
REPO_PATH_LITERAL = re.compile(r'"((?:\.\./)*[A-Za-z0-9_.*-]+(?:/[A-Za-z0-9_.*-]+)*)"')
# `Path.of("..", "openbank-infra", "gitops", "x.yaml")` carries the path as SEGMENTS: joined
# here, so the read is attributed to x.yaml and not to the whole openbank-infra tree.
SEGMENTED_PATH = re.compile(r'(?:Path\.of|Paths\.get|File)\(\s*((?:"[^"]*"\s*,\s*)+"[^"]*")\s*\)')
# `File(dir, "openbank-infra").isDirectory` probes for the repo root; it reads nothing.
EXISTENCE_PROBE = re.compile(r'"[A-Za-z0-9_.-]+"\s*\)\s*\.(?:isDirectory|isFile|exists\(\))')
# A bare top-level DIRECTORY name is a whole-tree read only as the argument of a filesystem
# call: `File(root, "openbank-infra").walkTopDown()`. Elsewhere it is an identifier —
# `consumer = "openbank-admin-ui"` names a Pact party, and treating it as a path made every
# admin-ui PR build 22 JVM modules. A bare ROOT FILE name ("README.md") stays a read anywhere.
TREE_READ = re.compile(r'(?:File|Path\.of|Paths\.get|resolve)\(\s*(?:[^",()]+,\s*)?"([A-Za-z0-9_.-]+)"\s*\)')
MAIN_TREE_WALK = re.compile(r'File\(\s*module\s*,\s*"src/main/kotlin"\s*\)\.walkTopDown\(\)')
TEST_SOURCE = re.compile(r"^openbank-[^/]+/src/test/")


def is_module(root: Path, name: str) -> bool:
    return (root / name / "build.gradle.kts").is_file() or (root / name / "build.gradle").is_file()


def external_tops(root: Path) -> tuple[set[str], set[str]]:
    """Top-level directories that are NOT Gradle modules, and root-level files."""
    dirs = {p.name for p in root.iterdir() if p.is_dir() and p.name != ".git" and not is_module(root, p.name)}
    files = {p.name for p in root.iterdir() if p.is_file()}
    return dirs, files


def external_paths(text: str, module: str, tops: set[str], root_files: set[str], root: Path) -> set[str]:
    """Repository paths a source file references by literal, excluding its own module."""
    found: set[str] = set()
    literals: list[str] = []
    for m in SEGMENTED_PATH.finditer(text):
        literals.append("/".join(seg.strip().strip('"') for seg in m.group(1).split(",")))
    rest = SEGMENTED_PATH.sub(" ", text)
    rest = EXISTENCE_PROBE.sub(" ", rest)
    tree_reads = set(TREE_READ.findall(rest))
    literals += REPO_PATH_LITERAL.findall(rest)
    for lit in literals:
        rel = re.sub(r"^(\.\./)+", "", lit).rstrip("/")
        top = rel.split("/", 1)[0]
        if top == module:
            continue
        if rel in root_files or (top in tops and ("/" in rel or rel in tree_reads)):
            found.add(rel)
        elif "/" in rel and is_module(root, top):
            found.add(rel)          # a file or directory inside another module
        elif "/" in rel and "*" in top:
            # Expand only a declared top-level glob against REAL Gradle modules.
            # Keep the suffix as a watch pattern so deleted/not-yet-created inputs
            # still select their reader; never infer an unrestricted fleet scan.
            suffix = rel.split("/", 1)[1]
            for target in root.iterdir():
                if (target.is_dir() and target.name != module and is_module(root, target.name)
                        and fnmatchcase(target.name, top)):
                    found.add(f"{target.name}/{suffix}")
    return found


def scan(root: Path) -> tuple[set[str], set[tuple[str, str]], set[tuple[str, str]], bool]:
    fleet: set[str] = set()
    edges: set[tuple[str, str]] = set()
    direct: set[tuple[str, str]] = set()
    main_tree_only = True
    tops, root_files = external_tops(root)
    for mod in sorted(p for p in root.iterdir() if p.is_dir() and is_module(root, p.name)):
        # Gradle copies external files into a module's resources (prompts, shared fixtures).
        for build in (mod / "build.gradle.kts", mod / "build.gradle"):
            if build.is_file():
                btext = build.read_text(encoding="utf-8", errors="replace")
                for path in external_paths(btext, mod.name, tops, root_files, root):
                    direct.add((mod.name, path))
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
            for path in external_paths(text, mod.name, tops, root_files, root):
                direct.add((mod.name, path))
    return fleet, edges, direct, main_tree_only


def has_module_input(root: Path, paths: list[str], main_tree_only: bool = False) -> bool:
    """Whether a filtered path belongs to a real Gradle module, not docs or GitOps."""
    return any("/" in path and is_module(root, path.split("/", 1)[0])
               and not (main_tree_only and TEST_SOURCE.match(path)) for path in paths)


def direct_readers(direct: set[tuple[str, str]], paths: list[str]) -> set[str]:
    def matches(path: str, watched: str) -> bool:
        if "*" not in watched:
            return path == watched or path.startswith(watched.rstrip("/") + "/")
        # Gradle/Ant '**/' spans zero or more directories, '**' is recursive,
        # and '*' stays within one segment. Escape every other character.
        # Prefix matching keeps existing conservative directory-read behavior.
        glob = watched.rstrip("/")
        parts: list[str] = []
        index = 0
        while index < len(glob):
            if glob.startswith("**/", index):
                parts.append(r"(?:[^/]+/)*")
                index += 3
            elif glob.startswith("**", index):
                parts.append(r".*")
                index += 2
            elif glob[index] == "*":
                parts.append(r"[^/]*")
                index += 1
            else:
                parts.append(re.escape(glob[index]))
                index += 1
        pattern = "".join(parts)
        return re.match(f"^{pattern}(?:/|$)", path) is not None

    return {
        reader for reader, watched in direct
        if any(matches(path, watched) for path in paths)
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
        (r / "openbank-infra" / "gitops").mkdir(parents=True)
        (r / "docs" / "adr").mkdir(parents=True)
        (r / "README.md").write_text("")
        write("openbank-walker", "e/WalkTest.kt",
              'val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }; '
              'val adr = File(root, "docs/adr/0001-x.md"); val readme = File(root, "README.md"); '
              'val name = "openbank-target"')  # a bare module NAME is not a path
        write("openbank-treewalker", "f/TreeTest.kt",
              'val tree = File(root, "openbank-infra").walkTopDown()')  # bare non-module dir = whole tree
        write("openbank-namer", "h/NameTest.kt",
              'val consumer = "openbank-infra"; val pact = "pacts/openbank-infra-openbank-namer.json"')  # identifiers, not reads
        write("openbank-segmenter", "g/SegTest.kt",
              'val y = Path.of("..", "openbank-infra", "gitops", "seg.yaml"); '
              'val marker = generateSequence(File("")) { it.parentFile }.first { File(it, "openbank-infra").isDirectory }')
        (r / "openbank-copier").mkdir()
        (r / "openbank-copier" / "build.gradle.kts").write_text('from(rootProject.file("docs/prompts/x")) {}')
        (r / "openbank-glob-copier").mkdir()
        (r / "openbank-glob-copier" / "build.gradle.kts").write_text(
            'from(rootProject.rootDir) { include("openbank-*/governance.yaml") }; '
            'from(rootProject.rootDir) { include("openbank-target/src/main/resources/*.yaml") }; '
            'from(rootProject.rootDir) { include("openbank-target/src/main/resources/**/*.json") }; '
            'from(rootProject.rootDir) { include("openbank-target/recursive/**") }')
        (r / "openbank-pretender").mkdir()  # matching name, but not a Gradle module
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
        if direct_readers(direct, ["openbank-infra/gitops/x.yaml"]) != {"openbank-infra-reader", "openbank-treewalker"}:
            print("FAIL: GitOps-only change must select its direct reader and the whole-tree reader, nothing else")
            ok = False
        if direct_readers(direct, ["openbank-infra/gitops/unrelated.yaml"]) != {"openbank-treewalker"}:
            print("FAIL: unrelated GitOps change must select only the whole-tree reader")
            ok = False
        if direct_readers(direct, ["docs/adr/0001-x.md"]) != {"openbank-walker"}:
            print(f"FAIL: parentFile-walk reader of docs/adr was not discovered: {sorted(direct_readers(direct, ['docs/adr/0001-x.md']))}")
            ok = False
        if direct_readers(direct, ["openbank-infra/gitops/seg.yaml"]) != {"openbank-segmenter", "openbank-treewalker"}:
            print(f"FAIL: segmented Path.of read was not attributed to its file: {sorted(direct_readers(direct, ['openbank-infra/gitops/seg.yaml']))}")
            ok = False
        if "openbank-segmenter" in direct_readers(direct, ["openbank-infra/aws/main.tf"]):
            print("FAIL: a repo-root existence probe was read as a whole-tree read")
            ok = False
        if "openbank-namer" in direct_readers(direct, ["openbank-infra/aws/main.tf"]):
            print("FAIL: a bare directory name used as an identifier was read as a whole-tree read")
            ok = False
        if direct_readers(direct, ["README.md"]) != {"openbank-walker"}:
            print("FAIL: root-file reader was not discovered")
            ok = False
        if direct_readers(direct, ["docs/prompts/x/a.md"]) != {"openbank-copier"}:
            print("FAIL: build.gradle.kts resource copy was not discovered as a reader")
            ok = False
        if direct_readers(direct, ["openbank-target/governance.yaml"]) != {"openbank-glob-copier"}:
            print("FAIL: declared root module glob did not select its resource copier")
            ok = False
        for changed in ["openbank-target/src/main/kotlin/Unrelated.kt",
                        "openbank-target/nested/governance.yaml",
                        "nested/openbank-target/governance.yaml",
                        "openbank-pretender/governance.yaml",
                        "openbank-missing/governance.yaml",
                        "openbank-target/src/main/resources/nested/deeper.yaml",
                        "openbank-target/src/test/resources/fixture.json",
                        "openbank-target/src/main/resources/nested/wrong.txt",
                        "openbank-target/src/main/resources/rootxjson"]:
            if "openbank-glob-copier" in direct_readers(direct, [changed]):
                print(f"FAIL: module glob selected its reader for unrelated/nonmodule path {changed}")
                ok = False
        if "openbank-glob-copier" not in direct_readers(direct, ["openbank-target/src/main/resources/new.yaml"]):
            print("FAIL: declared single-segment suffix glob was not matched")
            ok = False
        for changed in ["openbank-target/src/main/resources/root.json",
                        "openbank-target/src/main/resources/nested/fixture.json",
                        "openbank-target/src/main/resources/one/two/deep.json",
                        "openbank-target/recursive/root.txt",
                        "openbank-target/recursive/one/two/deep.txt"]:
            if "openbank-glob-copier" not in direct_readers(direct, [changed]):
                print(f"FAIL: declared recursive glob was not matched for {changed}")
                ok = False
        if direct_readers(direct, [".github/scripts/check-x.py", "SECURITY.md", "docs/runbook.md"]):
            print("FAIL: a path no test names selected a reader")
            ok = False
        if any(t == "openbank-target" for _, t in edges if _ == "openbank-walker") or ("openbank-walker", "openbank-target") in direct:
            print("FAIL: a bare module name literal was read as a path")
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
