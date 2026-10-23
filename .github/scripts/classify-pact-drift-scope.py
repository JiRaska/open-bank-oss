#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Classify a complete PR diff for a narrowly scoped Pact regeneration run.

Three outcomes, each fail-closed to the next wider one:

* ``ui-only``  — every changed path is under ``openbank-admin-ui/``. One ``ui`` shard regenerates
  the JVM producers of the Admin UI consumer contracts (the original #468 optimisation).
* ``modules``  — every changed path is under one of at most ``MAX_MODULES`` Gradle service modules
  (``openbank-<x>/`` with its own ``build.gradle.kts``, never ``openbank-libs*``), or under the
  Admin UI (which stands for the modules holding its JVM consumer tests, see
  ``expand-pact-scope-modules.py``), or is a ``pacts/`` file one of those modules regenerates.
  A module that depends on a changed one through ``project(":openbank-<x>")`` joins the scope when
  it has a consumer-pact test of its own (transitively), and is irrelevant otherwise. Paths that no pact
  test reads (``docs/``, most of ``.github/`` and ``openbank-infra/``, release-please files) are
  ignored. One ``modules`` shard reruns only those modules' consumer-pact tests. Sound because a
  consumer pact is a function of its own module's test sources plus the shared libs, and every
  shared input (libs, build-logic, root Gradle files, the gate's own workflow and scripts) is
  outside the allowed set and so selects ``full``.
  Measured 2026-10-23: 10 of 12 drift runs were ``full`` (4 shards x 12.7 min, 26 % of all hosted
  runner minutes) for PRs that changed a single service.
* ``full``     — anything else: missing, empty, malformed, ambiguous, renamed, or out-of-scope diff.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys


FULL = "full"
UI_ONLY = "ui-only"
MODULES = "modules"

# Above this many changed modules the four parallel full shards finish sooner than one serial
# scoped shard, and the saving is gone anyway.
MAX_MODULES = 8

FULL_SHARDS = '["0", "1", "2", "3"]'
UI_SHARDS = '["ui"]'
MODULES_SHARDS = '["modules"]'

_MODULE_NAME = re.compile(r"openbank-[a-z0-9-]+")

# Paths that cannot be an input of any consumer-pact test. A path matching one is dropped from
# the diff before classification. Everything else outside a service module (libs, build-logic,
# root Gradle files, `pacts/`, `openbank-infra/` — the ledger pension-co manifests there are a
# real regeneration input — and the gate's own workflow and scripts) still selects `full`.
# This script is executed from the event BASE, so a pull request cannot widen this list for itself.
_IGNORED_PREFIXES = ("docs/", "openbank-libs/governance/")
_IGNORED_FILES = (".release-please-manifest.json", "release-please-config.json", ".gitignore")
# The one place a consumer-pact test reads a deployment manifest (the ledger pension-co
# instance profile, listed as a trigger path of the gate for that reason).
_INFRA_INPUT_MARKER = "/ledger-pension-co/"
_GATE_OWN_FILES = (".github/workflows/pact-drift-check.yml",)
ADMIN_UI = "openbank-admin-ui"


def _is_ignored(path: str) -> bool:
    if path.startswith(_IGNORED_PREFIXES) or path in _IGNORED_FILES:
        return True
    if path.startswith("openbank-infra/"):
        return _INFRA_INPUT_MARKER not in path
    if path.startswith(".github/"):
        if path in _GATE_OWN_FILES:
            return False
        # The gate's scripts are named for what they do; every other .github file (other gates,
        # baselines, workflows) is not read by a pact test.
        return not (path.startswith(".github/scripts/") and "pact" in path.rsplit("/", 1)[-1])
    return False
_PROJECT_DEP = re.compile(r'project\(\s*"(:openbank-[a-z0-9-]+)"')


def _is_scoped_module(repo: Path, name: str) -> bool:
    """A Gradle service module in this checkout, as settings.gradle.kts discovers them."""
    if not _MODULE_NAME.fullmatch(name):
        return False
    if name == "openbank-admin-ui" or name.startswith("openbank-libs"):
        return False
    return (repo / name / "build.gradle.kts").is_file()


def _unowned_pacts(repo: Path, pacts: set[str], modules: set[str]) -> set[str]:
    """Changed pacts/ files that no selected module's test (and no PactJS test) regenerates.

    Ownership is derived from the HEAD checkout, which is what the scoped run regenerates and
    diffs: a head that claims a pact for a changed module is a head that regenerates it.
    Any derivation failure counts every changed pact as unowned (full run).
    """
    if not pacts:
        return set()
    scripts = repo / ".github/scripts"
    try:
        expanded = subprocess.run(
            ["python3", str(scripts / "expand-pact-scope-modules.py"), ",".join(sorted(modules)),
             "--repo", str(repo)],
            cwd=repo, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        ).stdout.split()
        owned = subprocess.run(
            [str(scripts / "derive-pact-drift-scope.sh"), "--module-pacts"],
            cwd=repo, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        ).stdout
        js = subprocess.run(
            ["python3", str(scripts / "pact-js-contracts.py"), "--pacts"],
            cwd=repo, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        ).stdout.split()
    except (OSError, subprocess.CalledProcessError):
        return set(pacts)
    covered = set(js)
    for line in owned.splitlines():
        module, _, pact = line.partition("\t")
        if module in expanded:
            covered.add(pact)
    return pacts - covered


def _has_consumer_pact_test(repo: Path, module: str) -> bool:
    return any((repo / module / "src/test/kotlin").glob("**/contract/*PactConsumerTest.kt"))


def _dependent_closure(repo: Path, modules: set[str]) -> set[str] | None:
    """Modules outside ``modules`` that depend, transitively, on one of them via project().

    A dependent's consumer pact is a function of the changed module too, so it joins the scope
    when it has a consumer-pact test and is skipped when it has none (nothing to regenerate).
    ``None`` means a build script could not be read: fail closed.
    """
    deps: dict[str, set[str]] = {}
    for build_file in repo.glob("openbank-*/build.gradle.kts"):
        try:
            text = build_file.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            return None
        deps[build_file.parent.name] = {ref[1:] for ref in _PROJECT_DEP.findall(text)}
    closure: set[str] = set()
    frontier = set(modules)
    while frontier:
        frontier = {
            owner for owner, refs in deps.items()
            if refs & frontier and owner not in modules and owner not in closure
        }
        closure |= frontier
    return {owner for owner in closure if _has_consumer_pact_test(repo, owner)}


def classify(repo: Path, base: str, head: str) -> tuple[str, str, list[str]]:
    """Return (mode, reason, modules); ``modules`` is non-empty only for mode ``modules``."""
    mode, reason, modules = _classify(repo, base, head)
    if mode == MODULES and not modules:
        return FULL, "module scope resolved to an empty set", []
    return mode, reason, modules


def _classify(repo: Path, base: str, head: str) -> tuple[str, str, list[str]]:
    try:
        ancestry = subprocess.run(
            ["git", "merge-base", "--is-ancestor", base, head],
            cwd=repo,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        return FULL, f"could not validate diff ancestry ({type(exc).__name__})", []
    if ancestry.returncode != 0:
        return FULL, "base is not an ancestor of head", []

    try:
        result = subprocess.run(
            [
                "git", "diff", "--name-status", "--find-renames", "--find-copies",
                "--find-copies-harder", "-z", f"{base}...{head}",
            ],
            cwd=repo,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        return FULL, f"could not read complete diff ({type(exc).__name__})", []

    fields = result.stdout.split(b"\0")
    if fields and fields[-1] == b"":
        fields.pop()
    if not fields:
        return FULL, "empty diff", []

    paths: list[bytes] = []
    renamed = False
    index = 0
    while index < len(fields):
        status = fields[index]
        index += 1
        if status in {b"A", b"M", b"D"}:
            if index >= len(fields):
                return FULL, "malformed diff status record", []
            paths.append(fields[index])
            index += 1
            continue
        # Rename/copy records carry two paths. Both count as changed; the UI-only scope keeps
        # refusing them (below), the module scope admits them when both ends are scoped modules.
        # All other statuses (type changes, unmerged paths, unknown future forms) are ambiguous.
        if status.startswith((b"R", b"C")):
            if index + 1 >= len(fields):
                return FULL, "malformed rename/copy record", []
            paths.extend(fields[index : index + 2])
            renamed = True
            index += 2
            continue
        return FULL, "unsupported diff status", []

    try:
        decoded = [path.decode("utf-8", errors="strict") for path in paths]
    except UnicodeDecodeError:
        return FULL, "non-UTF-8 path in diff", []

    if not decoded:
        return FULL, "empty path set", []
    if any(path.startswith("/") or "\\" in path or ".." in path.split("/") for path in decoded):
        return FULL, "diff contains an unsafe path", []
    decoded = [path for path in decoded if not _is_ignored(path)]
    if not decoded:
        return FULL, "every changed path is a non-input; nothing to scope", []
    if all(path.startswith("openbank-admin-ui/") for path in decoded):
        if renamed:
            return FULL, "rename/copy in diff", []
        return UI_ONLY, "all changed paths are under openbank-admin-ui/", []

    modules: set[str] = set()
    pacts: set[str] = set()
    for path in decoded:
        top, _, rest = path.partition("/")
        if not rest:
            return FULL, f"diff contains a path outside a scoped service module: {path}", []
        if top == "pacts":
            pacts.add(path)
        elif top == ADMIN_UI:
            modules.add(ADMIN_UI)
        elif _is_scoped_module(repo, top):
            modules.add(top)
        else:
            return FULL, f"diff contains a path outside a scoped service module: {path}", []
    if not modules:
        return FULL, "only pacts/ changed; no module scope to regenerate them from", []
    services = modules - {ADMIN_UI}
    if len(services) > MAX_MODULES:
        return FULL, f"{len(services)} changed modules exceed the scoped limit of {MAX_MODULES}", []
    dependents = _dependent_closure(repo, services)
    if dependents is None:
        return FULL, "could not read a module build script to derive project() dependents", []
    services |= dependents
    modules |= dependents
    if len(services) > MAX_MODULES:
        return FULL, f"{len(services)} modules incl. project() dependents exceed the scoped limit of {MAX_MODULES}", []
    unowned = _unowned_pacts(repo, pacts, modules)
    if unowned:
        return FULL, "changed pact is not regenerated by a changed module: " + ", ".join(sorted(unowned)), []
    return MODULES, "all changed paths are under " + ", ".join(sorted(modules)), sorted(modules)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", type=Path, help="GitHub Actions output file")
    args = parser.parse_args()

    mode, reason, modules = classify(args.repo, args.base, args.head)
    print(f"Pact regeneration scope: {mode} ({reason})")
    if args.output:
        shards = {UI_ONLY: UI_SHARDS, MODULES: MODULES_SHARDS}.get(mode, FULL_SHARDS)
        with args.output.open("a", encoding="utf-8") as output:
            output.write(f"mode={mode}\n")
            output.write(f"shards={shards}\n")
            output.write(f"modules={','.join(modules)}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
