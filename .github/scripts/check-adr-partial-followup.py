#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""ADR `partial` delivery-status must declare its follow-up (issue #3965).

WHY THIS EXISTS
    rules.yaml's `governance_followup` says an issue is opened for "the actionable
    tail of an ADR". A `delivery-status: partial` ADR has such a tail BY
    CONSTRUCTION: something shipped and something did not. That rule lived in prose
    with no gate, so 85 of 241 ADRs record that half of a decision is unbuilt and
    nothing forces anyone to say where the rest is tracked. A status inside a
    document is not a work item: it is read once, at authoring time, and after that
    only by whoever goes looking.

WHY THIS IS NOT AN ISSUE SEARCH — the half that makes the naive gate unshippable
    The obvious implementation greps the open-issue backlog for `ADR-NNNN` and
    fails any `partial` ADR with no hit. That gate goes red on ADRs that are
    correctly tracked, in four distinct ways, and it cannot tell any of them apart:

      1. the tail is tracked by an issue that never types the ADR number. Measured
         by hand over a 13-ADR sample on #3965: 5 of 13 (38%) were false positives.
         #1915 is verbatim ADR-0121's unbuilt axis and cites no ADR at all.
      2. the tail is tracked in ANOTHER REPO by design. ADR-0075 says so in its own
         text ("tracked as a GitHub issue, not in this monorepo"). A gate running
         with this repo's GITHUB_TOKEN cannot see openbank-app, and a
         permission-shaped absence is byte-identical to a real one.
      3. `partial` sometimes means "accepted limitation" (ADR-0186: single-region
         until M6) or "delivered differently than prescribed" (ADR-0017: OpenBao +
         External Secrets instead of the Vault extension). No tail exists at all.
      4. the tail IS tracked by a citing issue — the only case the search sees.

    That search is also not a stable quantity, so it cannot be baselined: it is a
    function of the OPEN backlog, not of this repo's contents. The same method gave
    52 untracked (#3965 body), 55 a day later (refutation comment) and 64 the day
    after that — the ADRs did not move, issues closed underneath it.

    So this gate never asks GitHub anything. It asks the ADR AUTHOR, through one
    optional front-matter key that can express all four states, and gates on that:

        followup: "#3679 — 18 of 39 workloads still run AUTHZ_ENFORCE=false"
        followup: "openbank-app — client-side wiring is tracked in the app repo"
        followup: "openbank-app#42 — Sentry KMP SDK wiring"
        followup: "none — single-region is an accepted limitation until M6"

    A bare repo name (no `#N`) is deliberately allowed for the cross-repo case: the
    gate has no read access to those repos, so demanding a number it can never
    verify would only buy a plausible-looking fiction. Same reason `#N` is not
    checked for existence or open-ness — this gate is offline, exactly as
    check-adr-registry.sh is, and says so instead of guessing.

THE RATCHET (shrink-only, both directions)
    Today's 85 `partial` ADRs carry no marker, and 85 hand-written markers is not a
    PR anyone can review. BASELINE below is that set, so the gate is ENFORCED from
    day one and ADR number 86 cannot be added quietly. The baseline is derived from
    REPO STATE ("partial with no marker"), never from the issue backlog, so it is
    reproducible offline and does not drift under it.

    A baseline entry that no longer applies FAILS — the list cannot rot into
    permanent furniture. Three ways it stops applying: the ADR gained a marker, its
    delivery-status is no longer `partial`, or its file is gone. Same contract as
    check-pact-provider-replay.py's KNOWN_UNCOVERED.

ONE PARSER
    The front matter is read through docs/adr/lib-frontmatter.sh — the single shared
    parser the generator and the validator both use (docs/adr/SCHEMA.md). This
    script does NOT reimplement it; a second parser is the exact defect class that
    schema exists to prevent. It also buys the code-about-code property for free:
    only the delimited front-matter block is parsed, so a `followup:` line inside a
    fenced example in an ADR body, or prose quoting `delivery-status: partial`, is
    invisible here. The --self-test proves that end to end rather than asserting it.

Exit: 0 = clean, 1 = at least one violation (or, without --enforce, a warning).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ADRs that were `partial` with no `followup` marker when this gate landed (#3965).
# 85 of 241, re-derived on origin/main f9515c67f. SHRINK-ONLY: adding an entry needs
# a reason in the PR, and an entry that no longer applies is reported, so this cannot
# quietly become permanent. Drain it by writing the marker, not by editing this list.
BASELINE = {
    "0006", "0007", "0014", "0017", "0019", "0022", "0023", "0024",
    "0025", "0026", "0028", "0029", "0030", "0031", "0034", "0041",
    "0047", "0049", "0051", "0055", "0061", "0063", "0068", "0069",
    "0070", "0077", "0078", "0080", "0083", "0085", "0087",
    "0088", "0089", "0093", "0094", "0095", "0096", "0097", "0099",
    "0100", "0112", "0117", "0119", "0122", "0124", "0135",
    "0138", "0139", "0140", "0141", "0143", "0146", "0148", "0155",
    "0160", "0161", "0162", "0163", "0164", "0165", "0166", "0167",
    "0168", "0171", "0172", "0174", "0176", "0180",
    "0189", "0191", "0192", "0193", "0194", "0195", "0198",
    "0207", "0223", "0224", "0226", "0234",
}

# `<refs> — <reason>`. The separator is an em dash (the house style everywhere else
# in this schema) or ` - `; the reason is what a reader actually needs, so it has a
# floor. Placeholders are rejected outright: an unfilled marker is worse than none,
# because it reads as a discharged obligation.
SEP_RE = re.compile(r"\s(?:—|–|-)\s")
REASON_MIN = 20
PLACEHOLDER_RE = re.compile(r"^(tbd|todo|n/?a|none|\?+|xxx)\b", re.IGNORECASE)
REF_ISSUE_RE = re.compile(r"^#[1-9][0-9]*$")
REF_CROSS_RE = re.compile(r"^([A-Za-z0-9._-]+)(?:#[1-9][0-9]*)?$")


def known_repos(adr_dir: str) -> set[str]:
    path = os.path.join(adr_dir, "known-repos.txt")
    out: set[str] = set()
    if not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                out.add(line)
    return out


def parse_frontmatter(adr_dir: str) -> dict[str, dict[str, str]]:
    """{ '0034': {'delivery-status': ..., 'followup': ..., '!file': ...} } via the shared parser.

    Deliberately a subprocess call into docs/adr/lib-frontmatter.sh rather than a
    regex here. One awk process for the whole fleet; first occurrence of a key wins,
    matching fm_field's own semantics.
    """
    lib = os.path.join(adr_dir, "lib-frontmatter.sh")
    if not os.path.isfile(lib):
        sys.exit(f"::error::check-adr-partial-followup: {lib} not found — cannot parse without the shared parser.")
    script = f'. "{lib}"; shopt -s nullglob; files=("{adr_dir}"/[0-9]*.md); ' \
             '[ ${#files[@]} -eq 0 ] && exit 9; fm_extract_many "${files[@]}"'
    proc = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
    if proc.returncode == 9:
        sys.exit(f"::error::check-adr-partial-followup: no numbered ADRs in {adr_dir}.")
    if proc.returncode != 0:
        # A parser failure is a TOOL failure, never a schema verdict — say which.
        sys.exit(
            f"::error::check-adr-partial-followup: the shared front-matter parser exited "
            f"{proc.returncode}; no ADR was judged. stderr: {proc.stderr.strip()[:400]}"
        )
    adrs: dict[str, dict[str, str]] = {}
    for line in proc.stdout.splitlines():
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        path, key, value = parts
        base = os.path.basename(path)
        num = base.split("-", 1)[0]
        rec = adrs.setdefault(num, {"!file": base})
        rec.setdefault(key, value)
    return adrs


def validate_marker(raw: str, repos: set[str]) -> str | None:
    """None = valid; otherwise the reason it is not."""
    if not (raw.startswith('"') and raw.endswith('"') and len(raw) >= 2):
        return "followup must be a double-quoted single-line string (see docs/adr/SCHEMA.md)"
    body = raw[1:-1].replace('\\"', '"').strip()
    m = SEP_RE.search(body)
    if not m:
        return 'followup must read `<refs> — <reason>`; no " — " separator found'
    refs_part, reason = body[: m.start()].strip(), body[m.end():].strip()
    if len(reason) < REASON_MIN:
        return f"followup reason is {len(reason)} chars, minimum {REASON_MIN} — say what is still unbuilt"
    if PLACEHOLDER_RE.match(reason):
        return "followup reason is a placeholder — an unfilled marker reads as a discharged obligation"
    if not refs_part:
        return "followup has no refs before the separator (`none`, `#N`, or `<repo>[#N]`)"
    refs = [r.strip() for r in refs_part.split(",") if r.strip()]
    if refs_part.lower() == "none":
        return None
    for ref in refs:
        if ref.lower() == "none":
            return "`none` cannot be combined with an issue reference — pick one"
        if REF_ISSUE_RE.match(ref):
            continue
        m2 = REF_CROSS_RE.match(ref)
        if m2 and m2.group(1) in repos:
            continue
        return (
            f"followup ref '{ref}' is not `none`, `#N`, or `<repo>[#N]` with the repo "
            f"listed in docs/adr/known-repos.txt"
        )
    return None


def check(adr_dir: str, baseline: set[str]) -> list[str]:
    adrs = parse_frontmatter(adr_dir)
    repos = known_repos(adr_dir)
    problems: list[str] = []

    for num in sorted(adrs):
        rec = adrs[num]
        raw = rec.get("followup")
        delivery = rec.get("delivery-status", "")
        f = rec["!file"]
        if raw is not None:
            why = validate_marker(raw, repos)
            if why:
                problems.append(f"{f}: {why}.")
                continue
        if delivery != "partial":
            continue
        if raw is None and num not in baseline:
            problems.append(
                f"{f}: delivery-status is 'partial' but there is no `followup:` key. "
                f"State where the unbuilt half is tracked — `#N`, `<repo>[#N]`, or "
                f"`none — <why no tail exists>`. See docs/adr/SCHEMA.md."
            )

    # The baseline half. A stale entry FAILS in every direction, so the list shrinks
    # or is corrected — it can never sit there describing a state that has passed.
    for num in sorted(baseline):
        rec = adrs.get(num)
        if rec is None:
            problems.append(
                f"ADR-{num} is in check-adr-partial-followup.py's BASELINE but has no "
                f"docs/adr/{num}-*.md file — drop the stale entry."
            )
            continue
        delivery = rec.get("delivery-status", "")
        if delivery != "partial":
            problems.append(
                f"{rec['!file']}: in BASELINE, but delivery-status is now '{delivery}', not "
                f"'partial' — drop the stale entry."
            )
        elif rec.get("followup") is not None:
            problems.append(
                f"{rec['!file']}: now carries a `followup:` marker, so its BASELINE entry is "
                f"stale — remove ADR-{num} from BASELINE in this PR."
            )
    return problems


# --------------------------------------------------------------------------- #
# Self-test: a gate that has only ever passed is unfalsified.
#
# Every case builds a REAL ADR directory (with the real lib-frontmatter.sh) and runs
# the real check() over it — no fixture short-circuits the parser, which is where the
# code-about-code property actually lives.
# --------------------------------------------------------------------------- #
FM = """---
date: 2026-01-01
decision-status: accepted
delivery-status: {delivery}
authors: [Tester]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance]
{extra}---

# {num}. Fixture

{body}
"""


def _mkadr(d: str, num: str, delivery: str, followup: str | None = None, body: str = "") -> None:
    extra = 'summary: "A fixture ADR used only by the self-test of this gate."\n'
    if followup is not None:
        extra += f"followup: {followup}\n"
    with open(os.path.join(d, f"{num}-fixture.md"), "w", encoding="utf-8") as fh:
        fh.write(FM.format(delivery=delivery, extra=extra, num=num, body=body))


def self_test() -> int:
    adr_src = os.path.join(REPO_ROOT, "docs", "adr")
    failures = 0

    def case(name: str, files, baseline: set[str], expect_hit: str | None, expect_clean: bool = False):
        nonlocal failures
        with tempfile.TemporaryDirectory() as d:
            for src in ("lib-frontmatter.sh", "known-repos.txt"):
                shutil.copy(os.path.join(adr_src, src), os.path.join(d, src))
            for kwargs in files:
                _mkadr(d, **kwargs)
            problems = check(d, baseline)
            joined = " | ".join(problems)
            if expect_clean:
                ok = not problems
            else:
                ok = bool(problems) and (expect_hit is None or expect_hit in joined)
            print(f"  [{'ok' if ok else 'FAIL'}] {name}" + ("" if ok else f"\n        got: {joined or '(no findings)'}"))
            if not ok:
                failures += 1

    print("check-adr-partial-followup --self-test")

    # --- the ratchet: a NEW untracked partial ADR is red ---------------------
    case("new partial ADR with no marker is flagged",
         [dict(num="0900", delivery="partial")], set(), "no `followup:` key")

    # --- and the four legitimate shapes are NOT ------------------------------
    case("marker citing an in-repo issue passes",
         [dict(num="0900", delivery="partial",
               followup='"#1915 — the syft-on-image axis and Kyverno Audit->Enforce are unbuilt"')],
         set(), None, expect_clean=True)
    case("marker citing a cross-repo tracker passes",
         [dict(num="0900", delivery="partial",
               followup='"openbank-app — client-side wiring is tracked in the app repo"')],
         set(), None, expect_clean=True)
    case("marker citing a cross-repo issue number passes",
         [dict(num="0900", delivery="partial",
               followup='"openbank-app#42 — Sentry KMP SDK wiring lives in the app repo"')],
         set(), None, expect_clean=True)
    case("marker declaring no tail passes",
         [dict(num="0900", delivery="partial",
               followup='"none — single-region is an accepted limitation until Milestone M6"')],
         set(), None, expect_clean=True)
    case("several refs on one marker pass",
         [dict(num="0900", delivery="partial",
               followup='"#669, #2365 — tested restore runbooks and the DR drill cadence"')],
         set(), None, expect_clean=True)

    # --- marker grammar: an unfillable marker must not read as discharged ----
    case("unquoted marker is flagged",
         [dict(num="0900", delivery="partial", followup="#123 — some reason that is long enough")],
         set(), "double-quoted")
    case("marker with no separator is flagged",
         [dict(num="0900", delivery="partial", followup='"#123 tracked over there somewhere"')],
         set(), "separator")
    case("marker with a too-short reason is flagged",
         [dict(num="0900", delivery="partial", followup='"#123 — soon"')], set(), "minimum")
    case("marker with a placeholder reason is flagged",
         [dict(num="0900", delivery="partial", followup='"#123 — TBD, someone will pick this up"')],
         set(), "placeholder")
    case("marker naming an unknown repo is flagged",
         [dict(num="0900", delivery="partial",
               followup='"some-other-repo#7 — tracked in a repo nobody declared"')],
         set(), "known-repos.txt")
    case("`none` combined with an issue ref is flagged",
         [dict(num="0900", delivery="partial", followup='"none, #123 — cannot be both at once"')],
         set(), "cannot be combined")
    case("a malformed marker on a BASELINED ADR is still flagged",
         [dict(num="0900", delivery="partial", followup='"#123 — soon"')], {"0900"}, "minimum")

    # --- the baseline half, all three staleness directions -------------------
    case("baselined partial with no marker is tolerated",
         [dict(num="0900", delivery="partial")], {"0900"}, None, expect_clean=True)
    case("baseline entry that gained a marker is flagged as stale",
         [dict(num="0900", delivery="partial",
               followup='"#1915 — the remaining axis is tracked and being drained"')],
         {"0900"}, "BASELINE entry is stale")
    case("baseline entry whose ADR shipped is flagged as stale",
         [dict(num="0900", delivery="shipped")], {"0900"}, "not 'partial'")
    case("baseline entry with no ADR file is flagged as stale",
         [dict(num="0900", delivery="partial")], {"0900", "0901"}, "has no docs/adr/0901")

    # --- code-about-code, end to end through the real shared parser ----------
    # An ADR whose BODY quotes the schema — a fenced `followup:` example and prose
    # naming `delivery-status: partial` — must be judged on its front matter alone.
    # Both directions, because each fails silently in the opposite way: prose must
    # not SATISFY the rule, and prose must not TRIGGER it.
    prose = (
        "Some ADRs write their tail as:\n\n"
        "```yaml\n"
        'followup: "none — this is an example, not this ADR\'s own marker"\n'
        "delivery-status: partial\n"
        "```\n\n"
        "Inline too: `followup: \"#1 — prose\"` and delivery-status: partial.\n"
    )
    case("a followup: example in the BODY does not satisfy the rule",
         [dict(num="0900", delivery="partial", body=prose)], set(), "no `followup:` key")
    case("a delivery-status: partial mention in the BODY of a shipped ADR is not flagged",
         [dict(num="0900", delivery="shipped", body=prose)], set(), None, expect_clean=True)

    print(f"self-test: {'PASS' if failures == 0 else str(failures) + ' FAILURE(S)'}")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--enforce", action="store_true", help="exit 1 on findings (default: ::warning, exit 0)")
    ap.add_argument("--self-test", action="store_true", help="falsify this gate in both directions and exit")
    ap.add_argument("--adr-dir", default=os.path.join(REPO_ROOT, "docs", "adr"))
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    problems = check(args.adr_dir, BASELINE)
    if not problems:
        print(
            f"check-adr-partial-followup: OK — every 'partial' ADR outside the "
            f"{len(BASELINE)}-entry baseline declares its follow-up, and no baseline entry is stale."
        )
        return 0
    level = "error" if args.enforce else "warning"
    for p in problems:
        print(f"::{level} title=ADR partial follow-up::{p}", file=sys.stderr)
    print(f"::{level}::check-adr-partial-followup: {len(problems)} finding(s).", file=sys.stderr)
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
