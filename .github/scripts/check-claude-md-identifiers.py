#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A class name in backticks in a per-service CLAUDE.md must exist in the tree.

Per-service `openbank-*/CLAUDE.md` files are loaded as agent instructions. One that names a class that
was renamed or never existed sends the next agent after code that is not there. Measured 2026-10-17:
`openbank-agent-service/CLAUDE.md` named five classes that do not exist (`PromptInjectionGuardrail`,
`KillSwitchEntry`, `AgentProposalService`, `McpServerResource`, `AgentProposalResource` — the code has
`PromptInjectionGuard`, `ProposalService`, `McpEndpoint`, `ProposalResource`), and
`openbank-onboarding-service/CLAUDE.md` two (`FourEyesGate`, `OnboardingRecordRepository`) and described
a four-eyes gate the service does not have. Sampling found 2 of 15 files stale; nothing checked them.

WHAT IT CHECKS: every backticked CamelCase identifier (>= 6 chars, at least two humps — `AgentPolicyGate`,
not `Panache` or `PENDING_KYC`) in a `openbank-*/CLAUDE.md` whose directory has `src/main` must occur as a
whole word in some tracked source file (.kt .java .kts .ts .tsx .py .yaml .yml .sql .rego .json .sh .toml).
Markdown is NOT a source: a README mentioning a class does not make it exist, and other CLAUDE.md files
cannot vouch for one another. `docs/adr/` is excluded for the same reason.

A name that is legitimately external (a library class, a Kubernetes condition) is not in the tree; list
it in EXTERNAL below WITH a reason. It is empty today: the first run found only real defects.

Usage: check-claude-md-identifiers.py [--root .] [--enforce | --selftest]
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys
import tempfile

SOURCE_EXT = {".kt", ".java", ".kts", ".ts", ".tsx", ".py", ".yaml", ".yml", ".sql", ".rego", ".json", ".sh", ".toml"}
SKIP_DIRS = {".git", "node_modules", "build", ".gradle", ".claude", ".next", "dist", "docs"}
TOKEN = re.compile(r"`([A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+)`")
WORD = re.compile(r"[A-Za-z][A-Za-z0-9]{5,}")

# identifier -> reason it is not in this tree. EMPTY on purpose.
EXTERNAL: dict[str, str] = {}


def _source_files(root: pathlib.Path):
    """Tracked files when `root` is a git checkout (fast, and build/ node_modules/ are not in it);
    otherwise a filtered walk (the self-test fixtures)."""
    try:
        res = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True)
        for rel in res.stdout.decode("utf-8", "ignore").split("\0"):
            if rel and os.path.splitext(rel)[1] in SOURCE_EXT and not set(rel.split("/")[:-1]) & SKIP_DIRS:
                yield root / rel
        return
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if os.path.splitext(name)[1] in SOURCE_EXT:
                yield pathlib.Path(dirpath) / name


def corpus_words(root: pathlib.Path) -> set[str]:
    words: set[str] = set()
    for f in _source_files(root):
        try:
            words.update(WORD.findall(f.read_text(encoding="utf-8", errors="ignore")))
        except OSError:
            pass
    return words


def service_claude_files(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(c for c in root.glob("openbank-*/CLAUDE.md") if (c.parent / "src" / "main").is_dir())


def findings(root: pathlib.Path, words: set[str] | None = None) -> tuple[list[str], int, int]:
    words = corpus_words(root) if words is None else words
    out: list[str] = []
    checked = 0
    files = service_claude_files(root)
    for c in files:
        for i, line in enumerate(c.read_text(encoding="utf-8").splitlines(), 1):
            for m in TOKEN.finditer(line):
                ident = m.group(1)
                if len(ident) < 6:
                    continue
                checked += 1
                if ident not in words and ident not in EXTERNAL:
                    out.append(f"{c.relative_to(root)}:{i}: `{ident}` is not defined or referenced in any source file")
    return out, checked, len(files)


def selftest() -> int:
    fails: list[str] = []
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        svc = root / "openbank-foo-service"
        (svc / "src" / "main" / "kotlin").mkdir(parents=True)
        (svc / "src" / "main" / "kotlin" / "A.kt").write_text("class RealThing\ninterface OtherPort\n")
        (root / "docs").mkdir()
        (root / "docs" / "x.md").write_text("`OnlyInDocs`")
        (svc / "README.md").write_text("`OnlyInReadme`")
        (svc / "CLAUDE.md").write_text("`RealThing` `OtherPort` `Panache` `PENDING_KYC` `Ghost` `GhostClass`\n"
                                       "`OnlyInDocs` `OnlyInReadme`\n")
        nosrc = root / "openbank-infra"
        nosrc.mkdir()
        (nosrc / "CLAUDE.md").write_text("`DisruptionBlocked`\n")
        got, checked, nfiles = findings(root)
        bad = sorted(re.search(r"`(\w+)`", g).group(1) for g in got)
        if bad != ["GhostClass", "OnlyInDocs", "OnlyInReadme"]:
            fails.append(f"known-positive/negative mix: flagged {bad}")
        if nfiles != 1:
            fails.append(f"a directory without src/main must be out of scope (files={nfiles})")
        if checked != 5:
            fails.append(f"expected 5 checkable identifiers, saw {checked} (single-hump / ALL_CAPS / short must be skipped)")
        # the pre-fix agent-service shape must be flagged against a tree that has the real names
        (svc / "CLAUDE.md").write_text("`PromptInjectionGuardrail` `AgentProposalService`\n")
        (svc / "src" / "main" / "kotlin" / "B.kt").write_text("class PromptInjectionGuard\nclass ProposalService\n")
        got, _, _ = findings(root)
        if len(got) != 2:
            fails.append(f"a renamed class must be flagged (got {got})")
        (svc / "CLAUDE.md").write_text("`PromptInjectionGuard` `ProposalService`\n")
        got, _, _ = findings(root)
        if got:
            fails.append(f"the corrected names must pass (got {got})")
    live, checked, nfiles = findings(pathlib.Path(__file__).resolve().parents[2])
    if nfiles < 10 or checked < 50:
        fails.append(f"the live scan is vacuous: {nfiles} files, {checked} identifiers")
    if fails:
        sys.stderr.write("selftest FAILED:\n  " + "\n  ".join(fails) + "\n")
        return 1
    print("selftest ok: known-positive, known-negative, scope, pre-fix shape + non-vacuous live read")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    out, checked, nfiles = findings(pathlib.Path(a.root))
    if nfiles == 0 or checked == 0:
        print("::error::claude-md-identifiers: scanned NOTHING — the scan is broken, not clean.")
        return 1
    level = "error" if a.enforce else "warning"
    for f in out:
        print(f"::{level}::claude-md-identifiers: {f}")
    print(f"claude-md-identifiers: {checked} identifier(s) in {nfiles} per-service CLAUDE.md file(s), {len(out)} not found.")
    return 1 if out and a.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
