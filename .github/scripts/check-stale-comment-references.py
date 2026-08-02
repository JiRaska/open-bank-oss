#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ============================================================================
# STALE COMMENT REFERENCES — prose that names something the repo can prove is gone.
#
# WHY THIS EXISTS
#   Comments are invisible to every other gate in this repo BY DESIGN. Guards over source
#   text strip comments first, precisely so code-about-code does not trip them (#2450), and
#   that is correct for those guards. The consequence is that nothing checks the prose —
#   and the prose is what a human reads and acts on.
#
#   Measured instances of the resulting defect class, all within two days:
#     * a Kafka ACL manifest header claimed the broker ran `allow.everyone.if.no.acl.found`
#       = true. It runs false. The false claim is WHY nobody granted the ACLs, and 13 outbox
#       rows went dead in production.
#     * an application.yaml documented the schema registry as "service schema-registry:8081".
#       The real Service is apicurio-registry.messaging on 8080 — wrong host AND wrong port.
#     * a workflow comment told whoever provisions the tokens to scope them to a repo that
#       had since been archived.
#   CLAUDE.md already records the general form: "stale prose naming a dead identifier is
#   invisible to a source-text guard forever."
#
# WHAT IS DECIDABLE, AND WHAT IS NOT
#   This guard checks only claims the repo can falsify MECHANICALLY. Two rules ship:
#
#   R1 `path`   A repo-ROOT-relative file path named in a comment that is not in the tree.
#               Admission is deliberately narrow (see is_path_claim): the first segment must
#               be a real top-level entry of THIS repo, the last must carry a known source
#               extension, and the path must resolve nowhere — not as a tracked path, not as
#               a tracked directory, and not as the TAIL of any tracked path (which is how
#               module-relative prose such as `scripts/generate-catalog.mjs` inside
#               openbank-admin-ui stays green). Glob/ellipsis forms are not claims and are
#               skipped. Gitignored build outputs are skipped — they exist at build time.
#
#   R2 `repo`   A `JiRaska/<slug>` GitHub repository named in a comment that is archived or
#               does not exist. Needs the network; DEGRADES TO A NOTICE, never to a pass and
#               never to a red, when the API cannot be reached (see resolve_repo).
#
#   Two candidate rules were measured against origin/main and REJECTED. Both rejections are
#   recorded here because "we did not build it" is the part a later reader cannot recover:
#
#   * k8s Service / namespace named in a comment but declared by no gitops manifest.
#     REJECTED — UNSOUND GROUND TRUTH. The Service inventory is not derivable from the tree:
#     Strimzi creates `openbank-cluster-kafka-bootstrap`, CNPG creates `<cluster>-rw`, and
#     the Temporal chart creates `temporal-frontend`, none of which is a committed
#     `kind: Service`. The strict `<svc>.<ns>.svc` form produced 5 findings on main and ALL
#     FIVE were correct references the guard could not see. It would also not have caught
#     the apicurio instance that motivated it, which is written `schema-registry:8081` — a
#     bare name and port, not an FQDN.
#
#   * an identifier (class, function, config key) in a comment that appears nowhere.
#     REJECTED — prose legitimately names deleted things in order to explain why they were
#     deleted, and this repo's comments do that constantly ("the older, shorter form of this
#     rule", "the `platform-admin` prose that survived the rename"). A guard here cannot
#     distinguish a stale reference from a deliberate epitaph, and a gate that cries wolf
#     gets ignored, which is worse than no gate.
#
# THE PRECEDENCE RULE FOR CODE-ABOUT-CODE — decided BEFORE the first run
#   A hypothetical path in explanatory prose is not a claim, and no amount of text analysis
#   can tell the two apart. So: AN EXPLICIT INLINE WAIVER WINS, AND NOTHING ELSE DOES.
#   Write `stale-ref-ok: <reason>` anywhere in the same comment block and that block is
#   skipped. There is no path allow-list and this file is NOT special-cased — it carries the
#   marker like every other file, so the exemption is reviewable in the diff instead of
#   hidden in the guard. That is the opposite of what burnt this repo three times
#   (check-roles-allowed-realm.py, check-advisory-gate-registration.py, the platform-admin
#   sweep), where a guard's own prose was the first thing it flagged.
#
#   KNOWN BLIND SPOT, stated rather than relied upon: a "comment" here means line/block
#   comment SYNTAX. A Python docstring is a string literal, so this file's own module
#   docstring would be out of scope — which is why the explanatory prose you are reading is
#   written as `#` comments, subject to the rule. self_test() proves that: it feeds this
#   very file's comment syntax a stale path and asserts the guard flags it.
#
# Usage:
#     python3 .github/scripts/check-stale-comment-references.py              # advisory
#     python3 .github/scripts/check-stale-comment-references.py --enforce    # exit 1
#     python3 .github/scripts/check-stale-comment-references.py --self-test
# ============================================================================
"""Flag comments that name a file path or GitHub repo the tree can prove is gone."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

# --------------------------------------------------------------------------- waiver

# The one and only escape hatch. See "THE PRECEDENCE RULE" above.
WAIVER = re.compile(r"stale-ref-ok\b")

# --------------------------------------------------------------------------- comments

C_STYLE = {".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".scss"}
HASH_STYLE = {".yaml", ".yml", ".sh", ".bash", ".py", ".toml", ".properties",
              ".tf", ".tfvars", ".rego", ".cfg", ".conf", ".ini"}
DASH_STYLE = {".sql"}
HASH_NAMES = {"Dockerfile", "Makefile", ".gitignore", ".dockerignore"}


def comment_style(path: str) -> str:
    p = Path(path)
    if p.suffix in C_STYLE:
        return "c"
    if p.suffix in HASH_STYLE:
        return "hash"
    if p.suffix in DASH_STYLE:
        return "dash"
    if p.name in HASH_NAMES or p.name.startswith("Dockerfile"):
        return "hash"
    return ""


def _line_comments(text: str, marker: str) -> list[tuple[int, str]]:
    """Consecutive comment lines are ONE block.

    The waiver is documented as covering "the same comment block", and a reader writing a
    ten-line header does not think of it as ten comments. Grouping also makes the waiver
    usable: a `stale-ref-ok:` line placed under the sentence it explains would otherwise
    waive nothing, which is a failure that reads exactly like the guard working.
    Each block is reported at the line of the reference, so `file:line` still points at it.
    """
    pat = re.compile(r"^\s*" + re.escape(marker) + r"\s?(.*)$")
    out: list[tuple[int, str]] = []
    block: list[tuple[int, str]] = []

    def flush() -> None:
        # The waiver is evaluated over the WHOLE block; the lines are then reported
        # individually so a finding still points at the line carrying the reference.
        if block:
            if not any(WAIVER.search(body) for _, body in block):
                out.extend(block)
            block.clear()

    for i, line in enumerate(text.splitlines(), 1):
        m = pat.match(line)
        if m:
            block.append((i, m.group(1)))
        else:
            flush()
    flush()
    return out


def c_comments(text: str) -> list[tuple[int, str]]:
    # Kotlin BLOCK COMMENTS NEST — a KDoc containing `/*` does not close at the first `*/`.
    # Depth-counting mirrors that; a naive find("*/") would truncate the block and hide
    # whatever the rest of it says (repo lore: reference-kotlin-block-comments-nest).
    out: list[tuple[int, str]] = []
    n = len(text)
    i = 0
    line_no = 1
    depth = 0
    buf: list[str] = []
    start = 1
    in_str: str | None = None
    while i < n:
        ch = text[i]
        if ch == "\n":
            line_no += 1
            if depth:
                buf.append("\n")
            i += 1
            continue
        if depth:
            if text.startswith("/*", i):
                depth += 1
                buf.append("/*")
                i += 2
                continue
            if text.startswith("*/", i):
                depth -= 1
                i += 2
                if depth == 0:
                    out.append((start, "".join(buf)))
                continue
            buf.append(ch)
            i += 1
            continue
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == in_str:
                in_str = None
            i += 1
            continue
        if ch in ('"', "'"):
            in_str = ch
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append((line_no, text[i + 2:j]))
            i = j
            continue
        if text.startswith("/*", i):
            depth = 1
            start = line_no
            buf = []
            i += 2
            continue
        i += 1
    return out


def comments_of(path: str, text: str) -> list[tuple[int, str]]:
    style = comment_style(path)
    if style == "c":
        return c_comments(text)
    if style == "hash":
        return _line_comments(text, "#")
    if style == "dash":
        return _line_comments(text, "--")
    return []


# --------------------------------------------------------------------------- R1: paths

# Extensions that make a slash-separated token a FILE reference rather than prose that
# happens to contain a slash ("openbank-libs-domain/openbank-libs-runtime", "and/or").
SOURCE_EXTS = {
    ".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
    ".py", ".sh", ".bash", ".sql", ".rego", ".tf", ".tfvars",
    ".yaml", ".yml", ".json", ".toml", ".xml", ".properties", ".md",
    ".gradle", ".conf", ".txt", ".csv", ".proto", ".svg", ".png",
}
URL = re.compile(r"\b(?:https?|git|ssh|ftp)://\S+")
PATHISH = re.compile(r"(?<![\w./-])((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.+-]+)")
TRAILING = ".,;:)]}'\"`"


def path_candidates(comment: str, top_level: set[str]) -> list[str]:
    """Slash tokens in `comment` that are unambiguously a claim about a file in THIS repo."""
    text = URL.sub(" ", comment)
    out = []
    for m in PATHISH.finditer(text):
        cand = m.group(1).rstrip(TRAILING)
        if "/" not in cand:
            continue
        # A glob, a placeholder or an elision is a PATTERN, not a claim about one file.
        if any(c in cand for c in "*?{}<>$!|") or ".." in cand:
            continue
        segs = cand.split("/")
        if any(not s or s.startswith("-") or s.endswith("-") for s in segs):
            continue
        # ROOT-ANCHORED ONLY. Without this the guard reads third-party paths
        # (an action's `temurin/installer.ts`, oss-fuzz's `projects/openbank/build.sh`)
        # as claims about this tree.
        if segs[0] not in top_level:
            continue
        # `a.yaml/b.yaml` is prose alternation — "one or the other", never a path.
        if "." in segs[0] and "." + segs[0].rsplit(".", 1)[-1] in SOURCE_EXTS:
            continue
        last = segs[-1]
        ext = "." + last.rsplit(".", 1)[-1] if "." in last else ""
        if ext not in SOURCE_EXTS:
            continue
        out.append(cand)
    return out


# --------------------------------------------------------------------------- R2: repos

REPO_SLUG = re.compile(r"\bJiRaska/([A-Za-z0-9._-]+)\b")


def resolve_repo(slug: str) -> tuple[str, str]:
    """('ok'|'archived'|'missing'|'unknown', detail).

    UNKNOWN is the fail-safe direction on purpose: no network must never read as a pass
    (it prints a ::notice) and must never read as a red (a proxy-less runner would fail
    every PR for a reason that has nothing to do with the PR).
    """
    if os.environ.get("STALE_REF_SKIP_NETWORK") == "1":
        return "unknown", "network checks disabled"
    try:
        r = subprocess.run(
            ["gh", "api", f"repos/JiRaska/{slug}", "--jq", "{archived: .archived}"],
            capture_output=True, text=True, timeout=20,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return "unknown", f"gh unavailable ({type(exc).__name__})"
    if r.returncode != 0:
        err = (r.stderr or "").strip()
        if "404" in err or "Not Found" in err:
            return "missing", "GitHub returns 404"
        return "unknown", err.splitlines()[0][:120] if err else "gh failed"
    try:
        archived = json.loads(r.stdout).get("archived")
    except json.JSONDecodeError:
        return "unknown", "unparseable gh output"
    return ("archived", "repository is ARCHIVED") if archived else ("ok", "")


# --------------------------------------------------------------------------- scanning

# DERIVED artifacts are not scanned. Every `*-opa-bundle*.yaml` embeds rules.yaml verbatim,
# so one stale comment in the source shows up 26 times — the guard would report a fleet of
# defects where there is one, and the fix is never in the bundle anyway (it is regenerated).
DERIVED = re.compile(r"opa-bundle[A-Za-z0-9._-]*\.ya?ml$")
# Markdown is entirely prose; a path in an ADR is a HISTORICAL RECORD, not an instruction.
# `docs/adr/*` deliberately cites repos and files that no longer exist. Different artifact,
# different rule, out of scope here.
SKIP_SUFFIX = (".md",)


def tracked_files(root: Path) -> list[str]:
    out = subprocess.run(["git", "-C", str(root), "ls-files", "-z"],
                         capture_output=True, text=True, check=True).stdout
    return [p for p in out.split("\0") if p]


def gitignored(root: Path, paths: list[str]) -> set[str]:
    """Paths git would ignore. A comment naming build/reports/bom.json is not stale —
    the file exists after a build, which is exactly when it is read."""
    if not paths:
        return set()
    r = subprocess.run(["git", "-C", str(root), "check-ignore", "--stdin"],
                       input="\n".join(paths), capture_output=True, text=True)
    return {ln.strip() for ln in r.stdout.splitlines() if ln.strip()}


class Finding:
    def __init__(self, rule: str, path: str, line: int, ref: str, detail: str):
        self.rule, self.path, self.line, self.ref, self.detail = rule, path, line, ref, detail

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: [{self.rule}] {self.ref} — {self.detail}"


def scan_text(path: str, text: str, top_level: set[str], resolvable,
              slug_state: dict[str, tuple[str, str]] | None = None) -> list[Finding]:
    """Pure core, shared by the repo scan and the self-test."""
    out: list[Finding] = []
    for line, comment in comments_of(path, text):
        if WAIVER.search(comment):
            continue
        for cand in path_candidates(comment, top_level):
            if resolvable(cand):
                continue
            out.append(Finding("path", path, line, cand,
                               "no such file in the tree (not tracked, not a directory, "
                               "not the tail of any tracked path, not gitignored)"))
        if slug_state is not None:
            for m in REPO_SLUG.finditer(comment):
                state, detail = slug_state.get(m.group(1), ("unknown", ""))
                if state in ("archived", "missing"):
                    out.append(Finding("repo", path, line, f"JiRaska/{m.group(1)}", detail))
    return out


def build_resolver(files: list[str]):
    """Every suffix of every tracked path, indexed once.

    MODULE-RELATIVE prose: `scripts/generate-catalog.mjs` written inside openbank-admin-ui
    means openbank-admin-ui/scripts/generate-catalog.mjs. Resolving on the TAIL keeps that
    green without the guard having to know module roots. Indexing beats scanning: the
    linear form cost 48 s over ~60k paths.
    """
    universe: set[str] = set()
    for f in files:
        parts = f.split("/")
        for i in range(len(parts)):          # every suffix, file and directory alike
            for j in range(i + 1, len(parts) + 1):
                if i == 0 or j == len(parts):
                    universe.add("/".join(parts[i:j]))
    return universe.__contains__


def run(root: Path, want_network: bool) -> list[Finding]:
    files = tracked_files(root)
    top_level = {f.split("/")[0] for f in files}
    resolvable = build_resolver(files)

    # One pass over the tree: collect R1 findings and every repo slug that needs resolving.
    raw: list[Finding] = []
    slugs: set[str] = set()
    hits: list[tuple[str, int, str]] = []          # (file, line, slug)
    for f in files:
        if f.endswith(SKIP_SUFFIX) or DERIVED.search(f) or not comment_style(f):
            continue
        try:
            text = (root / f).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for line, comment in comments_of(f, text):
            if WAIVER.search(comment):
                continue
            for m in REPO_SLUG.finditer(comment):
                slugs.add(m.group(1))
                hits.append((f, line, m.group(1)))
        raw.extend(scan_text(f, text, top_level, resolvable))

    # R1: drop build outputs git already knows about.
    ignored = gitignored(root, sorted({x.ref for x in raw if x.rule == "path"}))
    findings = [x for x in raw if not (x.rule == "path" and x.ref in ignored)]

    # R2: resolve each distinct slug ONCE, then attribute.
    state: dict[str, tuple[str, str]] = {}
    for slug in sorted(slugs):
        state[slug] = resolve_repo(slug) if want_network else ("unknown", "network skipped")
    unknown = [s for s, (st, _) in state.items() if st == "unknown"]
    if unknown:
        print(f"::notice::stale-comment-references: repo rule UNRESOLVED for "
              f"{', '.join('JiRaska/' + s for s in unknown)} — "
              f"{state[unknown[0]][1]}. Not a pass and not a failure.")
    for f, line, slug in hits:
        st, detail = state.get(slug, ("unknown", ""))
        if st in ("archived", "missing"):
            findings.append(Finding("repo", f, line, f"JiRaska/{slug}", detail))
    return findings


# --------------------------------------------------------------------------- self-test

# A synthetic tree. The resolver under test is build_resolver — the PRODUCTION one. An
# earlier draft of this self-test hand-wrote its own resolver, and a deliberate break of
# build_resolver then left all 22 cases green: the shipped resolution logic was covered by
# nothing. Never let a self-test substitute its own copy of the code it is falsifying.
_FILES = [
    "docs/threat-models/openbank-ledger-service.md",
    "openbank-ledger-service/src/main/resources/openapi.yaml",
    "openbank-libs-domain/src/main/kotlin/Libs.kt",
    "openbank-libs-runtime/src/main/kotlin/Runtime.kt",
    "openbank-admin-ui/scripts/generate-catalog.mjs",
    ".github/scripts/check-stale-comment-references.py",
    "config/detekt/detekt.yml",
    # A root-level `scripts/` MUST exist here, exactly as it does in the real tree. Without
    # it `scripts/generate-catalog.mjs` is rejected by root-anchoring and never reaches the
    # resolver — so the tail-resolution case was vacuous, and a break of build_resolver left
    # the suite green. A must-not-flag case that is filtered out upstream tests nothing.
    "scripts/add-license-headers.sh",
]
_TOP = {f.split("/")[0] for f in _FILES}
_resolver_for_selftest = build_resolver(_FILES)


# Each case is (filename, text). MUST_FLAG proves the rule fires; MUST_NOT_FLAG proves it
# does not fire on the shapes that legitimately look like it. Both directions per rule —
# a rule with only one direction tested is half-unfalsified.
_MUST_FLAG = {
    "hash comment, missing path": (
        "a.yaml", "# see docs/threat-models/ledger-service.md for the model\nkey: 1\n"),
    "kdoc, missing path": (
        "A.kt", "/**\n * Regression guard, see docs/threat-models/ledger-service.md.\n */\nclass A\n"),
    "kdoc NESTED block does not close early": (
        "A.kt",
        "/**\n * An inner /* nested */ comment.\n"
        " * Then docs/threat-models/ledger-service.md.\n */\nclass A\n"),
    "sql dash comment": (
        "V1.sql", "-- rollback documented in docs/runbooks/ledger-tieout.md\nSELECT 1;\n"),
    "dockerfile hash comment": (
        "Dockerfile", "# baked by config/detekt/missing.yml\nFROM scratch\n"),
    # The guard is subject to its own rule — this is THIS FILE's comment syntax carrying a
    # stale path with no waiver. See "THE PRECEDENCE RULE" in the header.
    "the guard's own comment syntax is not exempt": (
        ".github/scripts/check-stale-comment-references.py",
        "# an example such as docs/threat-models/ledger-service.md is still checked\n"),
}
_MUST_NOT_FLAG = {
    "path that exists": (
        "a.yaml", "# see docs/threat-models/openbank-ledger-service.md\n"),
    "module-relative path resolvable by tail": (
        "a.ts", "// built by scripts/generate-catalog.mjs\n"),
    "glob pattern is not a claim": (
        "a.yaml", "# docs/threat-models/*.md are generated\n"),
    "elision is not a claim": (
        "a.yaml", "# openbank-ledger-service/src/main/kotlin/.../Foo.kt\n"),
    "placeholder is not a claim": (
        "a.yaml", "# docs/threat-models/<service>.md\n"),
    "third-party path is not root-anchored": (
        "a.yml", "# documented by gradle/actions (guides/dependency-submission.md)\n"),
    "prose alternation a.yaml/b.yaml": (
        "a.yaml", "# regenerated from rules.yaml/governance.yaml\n"),
    "URL is not a path": (
        "a.yaml", "# https://example.com/docs/threat-models/ledger-service.md\n"),
    "explicit waiver wins (same line)": (
        "a.yaml", "# docs/threat-models/ledger-service.md  stale-ref-ok: hypothetical\n"),
    "explicit waiver wins (elsewhere in the same block)": (
        "a.yaml",
        "# the first draft accepted docs/threat-models/ledger-service.md\n"
        "# stale-ref-ok: that filename is an illustration, not a file\n"),
    "code, not a comment": (
        "a.kt", 'val p = "docs/threat-models/ledger-service.md"\n'),
    "trailing-hyphen stub from an elided glob": (
        "a.yaml", "# docs/runbooks/svc-*.md is generated\n"),
    # Prose that slashes two names together. Only the extension filter rejects these —
    # both segments are real top-level entries, so root-anchoring passes them through.
    "extensionless prose slash between two module names": (
        "a.yml", "# the openbank-libs-domain/openbank-libs-runtime split (ADR-0122)\n"),
    "extensionless prose slash naming a concept": (
        "a.kt", "// the config/CDI wiring is validated by quarkusBuild\n"),
}
_REPO_FLAG = {
    "archived repo": ("a.yml", "# scope the token to JiRaska/open-bank\n",
                      {"open-bank": ("archived", "repository is ARCHIVED")}),
    "missing repo": ("a.yml", "# see JiRaska/does-not-exist\n",
                     {"does-not-exist": ("missing", "GitHub returns 404")}),
}
_REPO_NO_FLAG = {
    "live repo": ("a.yml", "# see JiRaska/open-bank-oss\n", {"open-bank-oss": ("ok", "")}),
    "unresolved never fails": ("a.yml", "# see JiRaska/open-bank\n",
                               {"open-bank": ("unknown", "no network")}),
    "waived": ("a.yml", "# JiRaska/open-bank\n# stale-ref-ok: cited as history\n",
               {"open-bank": ("archived", "x")}),
}


def self_test() -> int:
    failures = 0
    for label, (name, text) in _MUST_FLAG.items():
        if not scan_text(name, text, _TOP, _resolver_for_selftest):
            print(f"SELF-TEST FAIL: should have flagged — {label}")
            failures += 1
    for label, (name, text) in _MUST_NOT_FLAG.items():
        got = scan_text(name, text, _TOP, _resolver_for_selftest)
        if got:
            print(f"SELF-TEST FAIL: false positive — {label}\n    {got[0]}")
            failures += 1
    for label, (name, text, st) in _REPO_FLAG.items():
        if not [f for f in scan_text(name, text, _TOP, _resolver_for_selftest, st)
                if f.rule == "repo"]:
            print(f"SELF-TEST FAIL: repo rule should have flagged — {label}")
            failures += 1
    for label, (name, text, st) in _REPO_NO_FLAG.items():
        got = [f for f in scan_text(name, text, _TOP, _resolver_for_selftest, st)
               if f.rule == "repo"]
        if got:
            print(f"SELF-TEST FAIL: repo rule false positive — {label}\n    {got[0]}")
            failures += 1
    total = len(_MUST_FLAG) + len(_MUST_NOT_FLAG) + len(_REPO_FLAG) + len(_REPO_NO_FLAG)
    if failures:
        print(f"self-test: {failures} of {total} cases failed")
        return 1
    print(f"self-test: {total}/{total} cases pass "
          f"({len(_MUST_FLAG) + len(_REPO_FLAG)} must-flag, "
          f"{len(_MUST_NOT_FLAG) + len(_REPO_NO_FLAG)} must-not-flag)")
    return 0


# --------------------------------------------------------------------------- main

def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    enforce = "--enforce" in sys.argv
    root = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"],
                               capture_output=True, text=True, check=True).stdout.strip())
    findings = run(root, want_network="--no-network" not in sys.argv)
    if not findings:
        print("stale comment references: OK (no comment names a missing file or dead repo)")
        return 0
    header = "comments naming something that does not exist:"
    print(f"{'::error::' if enforce else '::warning::'}{header}")
    for f in sorted(findings, key=lambda x: (x.rule, x.path, x.line)):
        print(f"  - {f}")
    print("\nFix the prose, or — when the reference is deliberately hypothetical — add\n"
          "`stale-ref-ok: <reason>` to the same comment block. See\n"
          ".github/scripts/check-stale-comment-references.py.")
    return 1 if enforce else 0


if __name__ == "__main__":
    raise SystemExit(main())
