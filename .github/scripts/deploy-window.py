#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Deploy window: at most one bot deploy commit lands on main per window.

WHY. `main-protection` requires PR branches to be up to date with main. Every bot deploy
commit (`chore(gitops): auto-deploy ...`, `chore(admin-ui): deploy ...`) therefore makes every
open PR `behind` and restarts its required checks. Measured over 2026-09-23T09:00Z ..
2026-09-30T09:00Z: 300 of 675 first-parent commits on main were bot deploy commits.

MECHANISM. Nothing about HOW a deploy is built, gated, opened or recorded changes. Only the
moment a deploy PR's auto-merge is ARMED does:

  * `decide`  — pure decision retained for self-tests and manual diagnosis. Deploy producers
                never arm directly, because independent reads can both see no armed PR.
  * `flush`   — the sole auto-merge writer, called by deploy-window-flush.yml after either
                deploy workflow completes and on a short recovery cron. Picks the OLDEST deferred
                deploy PR once the window has elapsed and nothing is armed. The caller re-runs
                supersede-deploy-prs.sh (ancestry + coverage) on it before arming.
  * `carry`   — called by auto-deploy.yml's rewrite step. Re-applies the image pins of every
                open, UNARMED older gitops deploy PR onto the new PR, so the newest PR covers
                every pending service and supersede-deploy-prs.sh closes the older ones. That
                is what turns "several pushes inside one window" into ONE commit. A pin is
                carried only while main still holds the tag that PR replaced — if main moved
                that service since, the old pin is stale and carrying it would rewind it.

NO LOST DEPLOY. A deferred deploy is an open PR: the same durable record the pipeline used
before this change. Nothing here closes a PR; the only closer is supersede-deploy-prs.sh, which
closes a PR only when a newer one covers every file it touches — and `carry` is what makes the
newer one cover them.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile

DEFAULT_WINDOW_SECONDS = 1800
DEPLOY_PREFIXES = ("chore/gitops-auto-deploy-", "chore/admin-ui-deploy-")
DEPLOY_SUBJECTS = ("chore(gitops): auto-deploy ", "chore(admin-ui): deploy ")
PIN_RE = re.compile(r"(?P<image>[A-Za-z0-9.-]+/openbank-[A-Za-z0-9._/-]+):(?P<tag>sandbox-[A-Za-z0-9._-]+)")


def decide(event: str, now: int, last_deploy: int | None, armed_open: bool, window: int) -> tuple[str, str]:
    if event == "workflow_dispatch":
        return "ARM", "manual workflow_dispatch deploys immediately"
    if armed_open:
        return "DEFER", "a deploy PR is already armed; this one waits for the next window"
    if last_deploy is None:
        return "ARM", "no recent deploy commit on main"
    age = now - last_deploy
    if age >= window:
        return "ARM", f"last deploy commit is {age}s old (window {window}s)"
    return "DEFER", f"last deploy commit is {age}s old, window {window}s — flush arms it at {last_deploy + window}"


def flush(prs: list[dict], now: int, last_deploy: int | None, window: int) -> dict | None:
    """Return the deferred PR to arm now, or None. prs: [{number, head, created_at, armed}]."""
    deploy = [p for p in prs if p["head"].startswith(DEPLOY_PREFIXES)]
    if any(p.get("armed") for p in deploy):
        return None
    pending = sorted((p for p in deploy if not p.get("armed")), key=lambda p: (p["created_at"], p["number"]))
    if not pending:
        return None
    verdict, _ = decide("flush", now, last_deploy, False, window)
    if verdict != "ARM":
        return None
    # The kind (gitops / admin-ui) that has waited longest goes first, and within it the NEWEST
    # PR is armed: it carries every older pin of its kind, so supersede-deploy-prs.sh closes the
    # older ones. Arming the oldest instead would hit supersede's STALE_KEEP refusal forever.
    prefix = next(x for x in DEPLOY_PREFIXES if pending[0]["head"].startswith(x))
    return [p for p in pending if p["head"].startswith(prefix)][-1]


def pins_from_diff(diff: str) -> list[tuple[str, str, str, str]]:
    """(file, image, old_tag, new_tag) for every image pin a unified diff changes."""
    out, cur, removed = [], None, {}
    for line in diff.splitlines():
        if line.startswith("+++ "):
            cur = line[4:].removeprefix("b/")
            removed = {}
        elif line.startswith("--- "):
            continue
        elif line.startswith("-") and cur:
            for m in PIN_RE.finditer(line):
                removed.setdefault(m["image"], m["tag"])
        elif line.startswith("+") and cur:
            for m in PIN_RE.finditer(line):
                old = removed.get(m["image"])
                if old and old != m["tag"]:
                    out.append((cur, m["image"], old, m["tag"]))
    return list(dict.fromkeys(out))


def carry(root: str, diff: str, skip_images: set[str]) -> list[str]:
    """Apply an older PR's pins onto the tree at `root` where main has not moved since."""
    carried = []
    for rel, image, old, new in pins_from_diff(diff):
        if image in skip_images:
            continue  # this run pins it itself — the newer build wins
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        text = open(path, encoding="utf-8").read()
        cur = {m["tag"] for m in PIN_RE.finditer(text) if m["image"] == image}
        if cur != {old}:
            continue  # main moved this service since that PR was cut: carrying would rewind it
        open(path, "w", encoding="utf-8").write(text.replace(f"{image}:{old}", f"{image}:{new}"))
        carried.append(f"{image}:{new} in {rel}")
    return carried


def last_deploy_from_commits(commits: list[dict]) -> int | None:
    """commits: [{message, epoch}] newest-first, as the caller reads them from main."""
    for c in commits:
        if c["message"].startswith(DEPLOY_SUBJECTS):
            return int(c["epoch"])
    return None


# ── self-test ─────────────────────────────────────────────────────────────────────────────────
def self_test() -> int:
    fails = []

    def check(name, cond):
        print(f"self-test {'OK  ' if cond else 'FAIL'} {name}")
        if not cond:
            fails.append(name)

    W, T = 1800, 1_000_000
    # 1. single push, quiet main: armed immediately (no added latency for sparse traffic)
    check("single push after a quiet window -> ARM", decide("push", T, T - 4000, False, W)[0] == "ARM")
    # 2. second push inside the window: deferred, then flushed at the window
    check("push inside the window -> DEFER", decide("push", T, T - 60, False, W)[0] == "DEFER")
    check("push while another deploy PR is armed -> DEFER", decide("push", T, T - 9000, True, W)[0] == "DEFER")
    # 3. manual dispatch is never delayed
    check("workflow_dispatch -> ARM even inside the window", decide("workflow_dispatch", T, T - 1, True, W)[0] == "ARM")
    # 4. nothing pending -> no commit
    check("flush with nothing pending -> no PR", flush([], T, T - 9000, W) is None)
    check("flush ignores non-deploy PRs",
          flush([{"number": 9, "head": "feat/x", "created_at": "a", "armed": False}], T, None, W) is None)
    prs = [
        {"number": 12, "head": "chore/gitops-auto-deploy-b", "created_at": "2026-09-30T10:05:00Z", "armed": False},
        {"number": 11, "head": "chore/admin-ui-deploy-a", "created_at": "2026-09-30T10:01:00Z", "armed": False},
    ]
    check("flush inside the window -> nothing yet", flush(prs, T, T - 60, W) is None)
    pick = flush(prs, T, T - W, W)
    check("flush at the window -> the longest-waiting kind first", pick is not None and pick["number"] == 11)
    prs2 = prs + [{"number": 14, "head": "chore/admin-ui-deploy-d", "created_at": "2026-09-30T10:09:00Z", "armed": False}]
    pick = flush(prs2, T, T - W, W)
    check("flush arms the NEWEST PR of that kind (it supersedes the older)", pick is not None and pick["number"] == 14)
    check("flush never arms a second PR while one is armed",
          flush(prs + [{"number": 13, "head": "chore/admin-ui-deploy-c", "created_at": "z", "armed": True}], T, None, W) is None)
    check("last deploy read from main's subjects",
          last_deploy_from_commits([{"message": "feat(x): y", "epoch": 9},
                                    {"message": "chore(admin-ui): deploy sandbox-1-run2", "epoch": 7}]) == 7)

    # 5. two pushes in one window -> ONE PR pins both (carry), and a moved service is not rewound
    reg = "123.dkr.ecr.eu-north-1.amazonaws.com"
    with tempfile.TemporaryDirectory() as d:
        os.makedirs(os.path.join(d, "g"))
        for svc, tag in (("billing", "sandbox-aaa"), ("lending", "sandbox-bbb"), ("party", "sandbox-ccc")):
            with open(os.path.join(d, "g", f"{svc}.yaml"), "w") as f:
                f.write(f"image: {reg}/openbank-{svc}:{tag}\n")
        # This run (push 2) already pinned lending to sandbox-222.
        p = os.path.join(d, "g", "lending.yaml")
        open(p, "w").write(f"image: {reg}/openbank-lending:sandbox-222\n")
        # Older deferred PR (push 1) pinned billing + lending + party to sandbox-111.
        # party has since moved on main (sandbox-ccc -> sandbox-999 by another merged deploy).
        open(os.path.join(d, "g", "party.yaml"), "w").write(f"image: {reg}/openbank-party:sandbox-999\n")
        older = "".join(
            f"--- a/g/{s}.yaml\n+++ b/g/{s}.yaml\n@@ -1 +1 @@\n-image: {reg}/openbank-{s}:{o}\n+image: {reg}/openbank-{s}:sandbox-111\n"
            for s, o in (("billing", "sandbox-aaa"), ("lending", "sandbox-bbb"), ("party", "sandbox-ccc"))
        )
        got = carry(d, older, {f"{reg}/openbank-lending"})
        read = lambda s: open(os.path.join(d, "g", f"{s}.yaml")).read()
        check("two pushes in one window -> newest PR pins BOTH (billing carried)", "openbank-billing:sandbox-111" in read("billing"))
        check("carry never overrides this run's own newer pin", "openbank-lending:sandbox-222" in read("lending"))
        check("carry never rewinds a service main moved since", "openbank-party:sandbox-999" in read("party"))
        check("carry reports exactly what it applied", len(got) == 1 and "billing" in got[0])
        # Negative control: without carry the newest PR would NOT cover billing, so
        # supersede-deploy-prs.sh (coverage gate) would leave the older PR open -> two commits.
        check("carry is idempotent (second run changes nothing)", carry(d, older, {f"{reg}/openbank-lending"}) == [])

    if fails:
        print(f"self-test: FAILED ({len(fails)})")
        return 1
    print("self-test: all cases OK")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    d = sub.add_parser("decide")
    d.add_argument("--event", required=True)
    d.add_argument("--armed-open", choices=["true", "false"], required=True)
    d.add_argument("--commits-json", required=True, help="file: [{message, epoch}] newest-first from main")
    f = sub.add_parser("flush")
    f.add_argument("--prs-json", required=True, help="file: [{number, head, created_at, armed}]")
    f.add_argument("--commits-json", required=True)
    c = sub.add_parser("carry")
    c.add_argument("--root", default=".")
    c.add_argument("--skip-images", default="", help="space-separated images this run pins itself")
    for p in (d, f):
        p.add_argument("--now", type=int, required=True)
        p.add_argument("--window", type=int, default=int(os.environ.get("DEPLOY_WINDOW_SECONDS", DEFAULT_WINDOW_SECONDS)))
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.cmd == "decide":
        last = last_deploy_from_commits(json.load(open(a.commits_json)))
        verdict, why = decide(a.event, a.now, last, a.armed_open == "true", a.window)
        print(f"decision={verdict}")
        print(f"::notice::deploy window: {verdict} — {why}", file=sys.stderr)
        return 0
    if a.cmd == "flush":
        last = last_deploy_from_commits(json.load(open(a.commits_json)))
        pick = flush(json.load(open(a.prs_json)), a.now, last, a.window)
        print(f"arm={pick['number'] if pick else ''}")
        print(f"arm_head={pick['head'] if pick else ''}")
        return 0
    if a.cmd == "carry":
        for line in carry(a.root, sys.stdin.read(), set(a.skip_images.split())):
            print(f"  [carried] {line}")
        return 0
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
