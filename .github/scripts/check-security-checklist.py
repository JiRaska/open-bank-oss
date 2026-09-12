#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Money-path PRs must actually TICK the security checklist, not just carry it.

WHY (ADR-0279 WS4 #26). The PR template's "## Security checklist" is the fleet's
per-PR security control — secrets/PII sweep, no unjustified suppressions, dependency
review, review-required labels. Measured behaviour says a checkbox nobody has to tick
gets read never: the template renders, the boxes stay empty, the review proceeds.
The champions/pitfall programme (AGENTS.md engineering notes) only works if the
money-path PR pauses on the lines written for exactly it.

WHAT IT CHECKS — only when the PR diff touches a `money_path_services` directory
(read from rules.yaml, the one list, so the two-lists-drift bug class cannot apply):
  * the PR body keeps a "## Security checklist" section (deleting the section is a
    finding, same as leaving it blank — otherwise the fix is "delete the question");
  * every checkbox inside that section is ticked (`[x]`/`[X]`). An unticked box is
    listed verbatim in the error, so the author ticks it or states why in the PR.

Deliberately NOT checked: the checkboxes' truthfulness (CI cannot know whether the
author really ran a secrets sweep — the control is the pause, not the proof) and
non-money-path PRs (the pause is priced; spend it where a mistake moves money).

RELEASE-DERIVED FILES ARE NOT MONEY-PATH CODE (#9230). A release-please PR touches
`CHANGELOG.md`, `version.txt` and `.release-please-manifest.json` under every service
that released, and nothing else — no Kotlin, no config, no migration, no spec. Counted
as money-path files those tripped this gate on a PR with no code in it at all, and the
remedy the error names is unanswerable: there is no secrets sweep to run and no
suppression to justify over a generated changelog, and the PR is authored by a bot that
cannot tick a box. Measured on #9230: 42 "money-path files" across 21 services, 88 files
in the diff, and ZERO of them outside the four derived names below — the release train
sat blocked from 2026-09-08.

This is the `gate whose remedy cannot be performed` shape, so the fix is the SCOPE, not
the severity: strip the derived names before deciding whether the PR is money-path. A
release PR that also carried real code still trips the gate, because the strip is
per-file and the code file survives it. That case is the self-test's must-FAIL control —
without it this exclusion would be indistinguishable from switching the gate off.

Usage:  check-security-checklist.py --body-file <file> [--base origin/main]
        check-security-checklist.py --self-test
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

RULES = Path("openbank-libs/governance/rules.yaml")
SECTION = re.compile(r"^##\s+Security checklist\s*$", re.M | re.I)
NEXT_HEADING = re.compile(r"^##\s+", re.M)
BOX = re.compile(r"^\s*-\s*\[(?P<mark>[ xX])\]\s*(?P<text>.*)$", re.M)

# Files release-please writes, and the only files a release-only PR contains. Matched on the
# BASENAME, because each lives under its own service directory (and the manifest at the root).
# Deliberately a closed list of four exact names, not a glob: `*.md` would swallow a threat model
# and `*.json` an OPA bundle, and both of those are exactly the money-path evidence this gate must
# keep seeing.
RELEASE_DERIVED = frozenset({
    "CHANGELOG.md",
    "version.txt",
    ".release-please-manifest.json",
})


def is_release_derived(path: str) -> bool:
    """True for a file release-please generates, which carries no code and no attack surface."""
    return path.rsplit("/", 1)[-1] in RELEASE_DERIVED



def money_path_dirs(root: Path) -> list[str]:
    """The one list from rules.yaml; a bare `- openbank-foo-service` under
    `money_path_services:` — no YAML dependency for a 20-line block."""
    names: list[str] = []
    in_block = False
    for line in (root / RULES).read_text().splitlines():
        if line.startswith("money_path_services:"):
            in_block = True
            continue
        if in_block:
            m = re.match(r"\s+-\s+([a-z0-9-]+)", line)
            if m:
                names.append(m.group(1))
            elif line and not line.startswith(" ") and not line.startswith("#"):
                break
    if not names:
        print("::error::money_path_services not parseable from rules.yaml — fail closed")
        sys.exit(1)
    return names


def changed_files(base: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        capture_output=True, text=True, check=True,
    )
    return [ln for ln in out.stdout.splitlines() if ln]


def unticked(body: str) -> tuple[str, list[str]] | None:
    """None = section absent; otherwise (section, [unticked box texts])."""
    m = SECTION.search(body)
    if not m:
        return None
    rest = body[m.end():]
    nxt = NEXT_HEADING.search(rest)
    section = rest[: nxt.start()] if nxt else rest
    missing = [b.group("text").strip() for b in BOX.finditer(section) if b.group("mark") == " "]
    return section, missing


def run(root: Path, body: str, base: str, enforce: bool) -> int:
    dirs = money_path_dirs(root)
    files = changed_files(base)
    in_money_path = [f for f in files if any(f == d or f.startswith(d + "/") for d in dirs)]
    touched = [f for f in in_money_path if not is_release_derived(f)]
    derived = len(in_money_path) - len(touched)
    if not touched:
        if derived:
            # Say it out loud. A gate that narrows its own scope silently is how a control becomes
            # a no-op nobody notices, so the release-only case reports what it skipped and why.
            print(f"security-checklist: {derived} money-path file(s) are release-derived "
                  f"(CHANGELOG.md / version.txt / .release-please-manifest.json) and carry no code; "
                  f"no other money-path file in this PR — not applicable")
        else:
            print(f"security-checklist: PR touches no money-path directory ({len(files)} files) — not applicable")
        return 0
    if derived:
        print(f"security-checklist: ignoring {derived} release-derived money-path file(s)")
    print(f"security-checklist: {len(touched)} money-path file(s) touched "
          f"({', '.join(sorted({t.split('/')[0] for t in touched}))})")

    res = unticked(body)
    if res is None:
        print("::error::PR touches money-path code but the body has no '## Security checklist' "
              "section — restore it from the template and tick every box (or state why in the PR).")
        return 1 if enforce else 0
    _, missing = res
    if missing:
        print(f"::error::PR touches money-path code and the Security checklist has "
              f"{len(missing)} unticked box(es):")
        for t in missing:
            print(f"  - [ ] {t}")
        print("Tick each box once true, or state the exception in the PR body.")
        return 1 if enforce else 0
    print("security-checklist: every Security checklist box ticked")
    return 0


def self_test() -> int:
    bad = 0
    tpl = (Path(__file__).resolve().parents[2] / ".github/PULL_REQUEST_TEMPLATE.md").read_text()
    # The template itself ships unticked boxes — it must be flagged.
    r = unticked(tpl)
    if r is None or not r[1]:
        print("self-test FAIL: pristine template not detected as unticked"); bad += 1
    ticked = re.sub(r"-\s*\[ \]", "- [x]", tpl)
    r2 = unticked(ticked)
    if r2 is None or r2[1]:
        print("self-test FAIL: fully ticked template still flagged"); bad += 1
    if unticked("## Summary\nno checklist here\n") is not None:
        print("self-test FAIL: missing section not detected"); bad += 1
    # A box OUTSIDE the section must not count.
    other = "## Security checklist\n- [x] done\n\n## Compliance impact\n- [ ] GDPR\n"
    r3 = unticked(other)
    if r3 is None or r3[1]:
        print("self-test FAIL: box in the next section leaked into the check"); bad += 1
    # ── the release-derived exclusion, both directions (#9230) ───────────────────────────────
    #
    # The must-FAIL half is the load-bearing one: an exclusion that also let a real money-path
    # source file through would be indistinguishable from switching the gate off, and every case
    # above would still pass. So drive the real `run()` over a throwaway git repo — the parser,
    # the diff and the decision, not a mock of them.
    import subprocess as _sp
    import tempfile as _tf

    money = money_path_dirs(Path("."))
    if not money:
        print("self-test FAIL: no money-path service to build a fixture from"); bad += 1
        money = ["openbank-ledger-service"]
    svc = sorted(money)[0]

    def fixture(paths: list[str], body: str) -> int:
        with _tf.TemporaryDirectory() as td:
            root = Path(td)
            (root / "openbank-libs" / "governance").mkdir(parents=True)
            (root / "openbank-libs" / "governance" / "rules.yaml").write_text(
                "money_path_services:\n  - " + svc + "\n", encoding="utf-8")
            env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                   "GIT_COMMITTER_EMAIL": "t@t", "PATH": __import__("os").environ.get("PATH", "")}
            def git(*a):
                _sp.run(["git", "-C", str(root), *a], check=True, capture_output=True, env=env)
            git("init", "-q", "-b", "base")
            git("add", "-A"); git("commit", "-q", "-m", "base")
            for rel in paths:
                f = root / rel
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text("x\n", encoding="utf-8")
            git("checkout", "-q", "-b", "head")
            git("add", "-A"); git("commit", "-q", "-m", "head")
            cwd = __import__("os").getcwd()
            try:
                __import__("os").chdir(root)
                return run(root, body, "base", enforce=True)
            finally:
                __import__("os").chdir(cwd)

    no_checklist = "## Summary\nrelease\n"
    release_only = [f"{svc}/CHANGELOG.md", f"{svc}/version.txt", ".release-please-manifest.json"]

    rc = fixture(release_only, no_checklist)
    if rc != 0:
        print("self-test FAIL: a release-only PR (CHANGELOG/version.txt/manifest) still trips the gate")
        bad += 1

    rc = fixture(release_only + [f"{svc}/src/main/kotlin/Money.kt"], no_checklist)
    if rc == 0:
        print("self-test FAIL: a release PR that ALSO changes money-path source passed — the "
              "exclusion is swallowing real code, which is the gate switched off")
        bad += 1

    # A name that merely resembles a derived one must not be excluded: the strip is exact-basename.
    rc = fixture([f"{svc}/docs/CHANGELOG.md.bak", f"{svc}/src/main/kotlin/Money.kt"], no_checklist)
    if rc == 0:
        print("self-test FAIL: a near-miss filename was treated as release-derived"); bad += 1

    print("security-checklist self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--body-file", required=False)
    ap.add_argument("--body", default=None, help="PR body inline (the runner passes $PR_BODY)")
    ap.add_argument("--base", default="origin/main")
    ap.add_argument("--root", default=".")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    body = Path(args.body_file).read_text() if args.body_file else args.body
    if body is None:
        ap.error("--body-file or --body is required outside --self-test")
    return run(Path(args.root), body, args.base, args.enforce)


if __name__ == "__main__":
    sys.exit(main())
