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
DECLARED (class/interface/object/typealias/fun/type/...) in a source file under some `src/` directory
(.kt .java .ts .tsx .py .rego), or occur as a word in a Gradle build file. A bare occurrence is not a
definition, and .github/ is never a corpus, so this docstring cannot vouch for the names above.
Markdown is NOT a source: a README mentioning a class does not make it exist, and other CLAUDE.md files
cannot vouch for one another. `docs/adr/` is excluded for the same reason.

A name that is legitimately external (a library class, a Kubernetes condition) is not in the tree; list
it in EXTERNAL below WITH a reason. Entries are library classes or alert names; the rest of the first run were real defects.

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

SOURCE_EXT = {".kt", ".java", ".ts", ".tsx", ".py", ".rego"}
BUILD_EXT = {".kts", ".gradle"}
# Skipped ABOVE the `src` component only: a package named `build` or `docs` inside src/ is source.
SKIP_DIRS = {".git", "node_modules", "build", ".gradle", ".claude", ".next", "dist", "docs", ".github"}
# A name is defined only where it is DECLARED; a bare word occurrence (comment, string, docstring) is not.
DECL = re.compile(
    r"\b(?:class|interface|object|typealias|fun|type|enum|function|const|val|var|def|data)\s+"
    r"(?:<[^>]*>\s*)?(?:[A-Za-z0-9_]+\.)*([A-Za-z][A-Za-z0-9_]{5,})"
)
TOKEN = re.compile(r"`([A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+)`")
WORD = re.compile(r"[A-Za-z][A-Za-z0-9]{5,}")

# identifier -> reason it is not declared in this tree.
EXTERNAL: dict[str, str] = {
    "BlockingNotAllowedException": "SmallRye/Vert.x library exception, not declared in this tree",
    "ObjectNode": "Jackson library class",
    "PanacheEntity": "Quarkus Panache library base class",
    "AuditChainBroken": "Prometheus alert name (openbank-infra prometheus-rules-audit-chain.yaml), not a class",
}


def _rel_paths(root: pathlib.Path):
    try:
        res = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, check=True)
        yield from (r for r in res.stdout.decode("utf-8", "ignore").split("\0") if r)
        return
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in {".git", "node_modules", ".gradle"}]
        for name in filenames:
            yield os.path.relpath(os.path.join(dirpath, name), root)


def _source_files(root: pathlib.Path):
    """Yield (path, is_build). Source = a file under a `src/` directory; build files = *.kts / *.gradle.
    .github/ (this script and its self-test) is never a source."""
    for rel in _rel_paths(root):
        parts = rel.split("/")
        ext = os.path.splitext(rel)[1]
        if ext in BUILD_EXT and not set(parts[:-1]) & SKIP_DIRS:
            yield root / rel, True
        elif ext in SOURCE_EXT and "src" in parts[:-1]:
            if not set(parts[: parts.index("src")]) & SKIP_DIRS:
                yield root / rel, False


def corpus_words(root: pathlib.Path) -> set[str]:
    words: set[str] = set()
    for f, is_build in _source_files(root):
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        words.update(WORD.findall(text) if is_build else DECL.findall(text))
    return words


def service_claude_files(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(c for c in root.glob("openbank-*/CLAUDE.md") if (c.parent / "src" / "main").is_dir())


def findings(root: pathlib.Path, words: set[str] | None = None,
             claude_text: dict[pathlib.Path, str] | None = None) -> tuple[list[str], int, int]:
    words = corpus_words(root) if words is None else words
    out: list[str] = []
    checked = 0
    files = service_claude_files(root)
    for c in files:
        text = (claude_text or {}).get(c)
        for i, line in enumerate((text if text is not None else c.read_text(encoding="utf-8")).splitlines(), 1):
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
        # a name that only OCCURS (comment, string literal, a .github file) is not defined
        (svc / "src" / "main" / "kotlin" / "C.kt").write_text('// MentionedOnly\nval s = "StringOnly"\n')
        (root / ".github" / "scripts").mkdir(parents=True)
        (root / ".github" / "scripts" / "g.py").write_text("class GithubOnly\n")
        (svc / "CLAUDE.md").write_text("`MentionedOnly` `StringOnly` `GithubOnly`\n")
        got, _, _ = findings(root)
        if len(got) != 3:
            fails.append(f"occurrence without declaration / .github must be flagged (got {got})")
        # a package directory named build INSIDE src is still source
        (svc / "src" / "main" / "kotlin" / "build").mkdir()
        (svc / "src" / "main" / "kotlin" / "build" / "D.kt").write_text("class InsideBuildPkg\n")
        (svc / "CLAUDE.md").write_text("`InsideBuildPkg`\n")
        got, _, _ = findings(root)
        if got:
            fails.append(f"a `build` package inside src must count (got {got})")
    real = pathlib.Path(__file__).resolve().parents[2]
    live, checked, nfiles = findings(real)
    # the REAL findings() over the REAL tree, ghost names injected into an in-memory copy of a real CLAUDE.md
    target = service_claude_files(real)[0]
    ghosts = ["PromptInjectionGuardrail", "FourEyesGate", "AgentProposalService", "KillSwitchEntry", "McpServerResource"]
    text = target.read_text(encoding="utf-8") + "\n" + " ".join(f"`{g}`" for g in ghosts) + "\n"
    got, _, _ = findings(real, claude_text={target: text})
    missed = [g for g in ghosts if not any(f"`{g}`" in x for x in got)]
    if missed:
        fails.append(f"real-tree ghost injection not reported: {missed}")
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
    print(f"SUBJECTS={nfiles}  # per-service CLAUDE.md files scanned")
    print(f"claude-md-identifiers: {checked} identifier(s) in {nfiles} per-service CLAUDE.md file(s), {len(out)} not found.")
    return 1 if out and a.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
